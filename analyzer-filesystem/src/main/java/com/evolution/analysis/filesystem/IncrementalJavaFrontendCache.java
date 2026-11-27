package com.evolution.analysis.filesystem;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Per-source-set content-addressed cache for Java {@link FrontendResult} outputs.
 * <p>
 * The key hashes only the exact inputs that determine a single source set's Java extraction
 * (sources, reactor sources or reactor ABIs, {@code lombok.config} files, platform, ordered binaries,
 * frontend provider, and profile), deliberately excluding global snapshot or unrelated module hashes.
 * Any hit rebinds {@code FrontendResult.analysis} to the current request's {@link AnalysisIdentity}
 * (and rebinds peer upstream declaration spans when using ABI-aware lookup).
 * <p>
 * Every entry carries a trailing SHA-256 checksum over the entire payload and returns
 * {@link Optional#empty()} on any mismatch or corruption.
 */
public final class IncrementalJavaFrontendCache {
    private static final byte[] MAGIC = "EVOJFC2\n".getBytes(StandardCharsets.US_ASCII);
    private static final int FORMAT_VERSION = 2;
    private static final int MAX_ENTRIES = Integer.getInteger("evo.incremental.javaCacheMaxEntries", 512);
    private static final int IO_BUFFER_BYTES = 32 * 1024;
    private static final int EVICT_CHECK_INTERVAL = 32;

    private final Path directory;
    private final Object evictionLock = new Object();
    private final AtomicInteger writeCount = new AtomicInteger();

    public IncrementalJavaFrontendCache(Path cacheRoot) {
        this.directory = Objects.requireNonNull(cacheRoot).toAbsolutePath().normalize().resolve("java-sets");
    }

    /** Computes the deterministic key for one {@link FrontendRequest}, independent of unrelated modules. */
    public static ContentDigest cacheKey(FrontendRequest request, VersionedIdentifier provider) {
        return computeKey(request, provider, null);
    }

    /**
     * Computes an ABI-aware cache key where upstream {@code reactorSources} are hashed by their
     * deterministic module ABI digest when provided (falling back to raw file digests for any module
     * without an ABI digest). Enables cross-module Early Cutoff on upstream body-only edits.
     */
    public static ContentDigest cacheKeyWithReactorAbi(
            FrontendRequest request,
            VersionedIdentifier provider,
            Map<ModuleIdentity, ContentDigest> reactorModuleAbiDigests) {
        Objects.requireNonNull(reactorModuleAbiDigests);
        return computeKey(request, provider, reactorModuleAbiDigests);
    }

    private static ContentDigest computeKey(
            FrontendRequest request,
            VersionedIdentifier provider,
            Map<ModuleIdentity, ContentDigest> reactorModuleAbiDigests) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(provider);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (var out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), md))) {
                writeLongUtf(out, reactorModuleAbiDigests == null
                        ? "java-source-set-cache:v4"
                        : "java-source-set-abi-cache:v4");
                writeLongUtf(out, provider.id());
                writeLongUtf(out, provider.version());
                String profile = request.manifest().configuration().values().getOrDefault("analysis.profile", "");
                writeLongUtf(out, profile);
                out.writeBoolean(request.sourceRecovery());
                writeLongUtf(out, request.manifest().configuration().values()
                        .getOrDefault(FrontendRequest.RECOVERY_PLATFORM_COMPATIBLE, ""));
                writeLongUtf(out, request.manifest().configuration().values()
                        .getOrDefault("java.binary-native-origins", ""));
                writeLongUtf(out, request.manifest().configuration().values()
                        .getOrDefault("java.generated-overlay", ""));
                out.writeInt(request.plan().syntaxLevel().orElse(-1));
                out.writeInt(request.plan().bytecodeTarget().orElse(-1));
                out.writeBoolean(request.plan().preview());
                writeLongUtf(out, request.module().value());
                writeLongUtf(out, request.sourceSet().name());
                writeLongUtf(out, request.platform().entry().logicalName());
                writeLongUtf(out, request.platform().entry().contentDigest().value());
                out.writeInt(request.platform().release());
                writeLongUtf(out, request.platform().version());
                writeLongUtf(out, request.platform().vendor());
                out.writeInt(request.platform().artifacts().size());
                for (var art : request.platform().artifacts()) {
                    writeLongUtf(out, art.logicalName());
                    writeLongUtf(out, art.contentDigest().value());
                }
                // Soundness invariant: Extraction.hasLombokConfiguration() inspects all lombok.config files in snapshot,
                // and AndroidSyntheticDeclarations.populate() reads all .aidl and AndroidManifest.xml snapshot files.
                var lombokConfigs = new TreeMap<String, String>();
                var androidMeta = new TreeMap<String, String>();
                for (var sf : request.manifest().snapshot().files()) {
                    String path = sf.path().replace('\\', '/');
                    if ("lombok.config".equals(path) || path.endsWith("/lombok.config")) {
                        lombokConfigs.put(path, sf.contentDigest().value());
                    } else if (path.endsWith(".aidl") || path.endsWith("AndroidManifest.xml")) {
                        androidMeta.put(path, sf.contentDigest().value());
                    }
                }
                out.writeInt(lombokConfigs.size());
                for (var entry : lombokConfigs.entrySet()) {
                    writeLongUtf(out, entry.getKey());
                    writeLongUtf(out, entry.getValue());
                }
                out.writeInt(androidMeta.size());
                for (var entry : androidMeta.entrySet()) {
                    writeLongUtf(out, entry.getKey());
                    writeLongUtf(out, entry.getValue());
                }
                out.writeInt(request.sources().size());
                for (var src : request.sources()) {
                    var doc = src.document();
                    writeLongUtf(out, doc.identity().value());
                    writeLongUtf(out, doc.path());
                    writeLongUtf(out, doc.contentDigest().value());
                    writeLongUtf(out, doc.classification().name());
                    writeLongUtf(out, src.decoding().charset());
                }
                out.writeInt(request.dependencies().size());
                for (var dep : request.dependencies()) {
                    writeLongUtf(out, dep.entry().logicalName());
                    writeLongUtf(out, dep.entry().contentDigest().value());
                    writeLongUtf(out, dep.originEntry().logicalName());
                    writeLongUtf(out, dep.originEntry().contentDigest().value());
                    writeLongUtf(out, dep.reactorModule().map(ModuleIdentity::value).orElse(""));
                    writeLongUtf(out, dep.nativeOrigin().map(o -> o.original().contentDigest().value()).orElse(""));
                }
                out.writeInt(request.reactorSources().size());
                for (var r : request.reactorSources()) {
                    out.writeInt(r.order());
                    writeLongUtf(out, r.module().value());
                    writeLongUtf(out, r.sourceSet().name());
                    ContentDigest abiDigest = reactorModuleAbiDigests == null ? null : reactorModuleAbiDigests.get(r.module());
                    if (abiDigest != null) {
                        out.writeByte(1);
                        writeLongUtf(out, abiDigest.value());
                    } else {
                        out.writeByte(0);
                        writeLongUtf(out, r.identity().value());
                        out.writeInt(r.sources().size());
                        for (var rs : r.sources()) {
                            writeLongUtf(out, rs.document().identity().value());
                            writeLongUtf(out, rs.document().path());
                            writeLongUtf(out, rs.document().contentDigest().value());
                            writeLongUtf(out, rs.document().classification().name());
                            writeLongUtf(out, rs.decoding().charset());
                        }
                    }
                }
                out.writeBoolean(request.correspondence().isPresent());
                if (request.correspondence().isPresent()) {
                    var corr = request.correspondence().orElseThrow();
                    writeLongUtf(out, request.manifest().configuration().values()
                            .getOrDefault("java.source-binary-correspondence", ""));
                    writeLongUtf(out, com.evolution.analysis.contract.serialization.CanonicalJson
                            .sha256(corr.canonicalForm()).value());
                    var sortedViews = new TreeMap<>(corr.binaryViews());
                    out.writeInt(sortedViews.size());
                    for (var v : sortedViews.entrySet()) {
                        writeLongUtf(out, v.getKey().value());
                        writeLongUtf(out, v.getValue().digest().value());
                        out.writeLong(v.getValue().size());
                    }
                }
                out.flush();
            }
            return new ContentDigest("sha256:" + HexFormat.of().formatHex(md.digest()));
        } catch (NoSuchAlgorithmException | IOException impossible) {
            throw new IllegalStateException("Cache key computation failed", impossible);
        }
    }

    public Path entryPath(ContentDigest key) {
        Objects.requireNonNull(key);
        return directory.resolve(key.value().substring(7) + ".fres.gz");
    }

    public Optional<FrontendResult> get(FrontendRequest request, VersionedIdentifier provider) {
        if (Boolean.getBoolean("evo.incremental.disabled")) return Optional.empty();
        return getByKey(cacheKey(request, provider), request, Map.of());
    }

    /**
     * Looks up a cached {@link FrontendResult} by its reactor-ABI key and rebinds any upstream
     * peer {@link DeclarationRecord} spans to match {@code currentReactorDeclarations}, guaranteeing
     * bit-for-bit identical spans after upstream body-only edits.
     */
    public Optional<FrontendResult> getByAbi(
            FrontendRequest request,
            VersionedIdentifier provider,
            Map<ModuleIdentity, ContentDigest> reactorModuleAbiDigests,
            Map<EntityIdentity, DeclarationRecord> currentReactorDeclarations) {
        if (Boolean.getBoolean("evo.incremental.disabled")) return Optional.empty();
        Objects.requireNonNull(reactorModuleAbiDigests);
        Objects.requireNonNull(currentReactorDeclarations);
        return getByKey(cacheKeyWithReactorAbi(request, provider, reactorModuleAbiDigests), request, currentReactorDeclarations);
    }

    private Optional<FrontendResult> getByKey(
            ContentDigest key,
            FrontendRequest request,
            Map<EntityIdentity, DeclarationRecord> currentReactorDeclarations) {
        Path file = entryPath(key);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] compressedTrailer = new byte[32];
            try (var in = new BufferedInputStream(Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS), IO_BUFFER_BYTES)) {
                byte[] all = in.readAllBytes();
                if (all.length < 32) {
                    deleteQuietly(file);
                    return Optional.empty();
                }
                int bodyLen = all.length - 32;
                System.arraycopy(all, bodyLen, compressedTrailer, 0, 32);
                md.update(all, 0, bodyLen);
                if (!MessageDigest.isEqual(compressedTrailer, md.digest())) {
                    deleteQuietly(file);
                    return Optional.empty();
                }
                try (var din = new DataInputStream(new BufferedInputStream(
                        new GZIPInputStream(new ByteArrayInputStream(all, 0, bodyLen)), IO_BUFFER_BYTES))) {
                    byte[] magic = new byte[MAGIC.length];
                    din.readFully(magic);
                    if (!Arrays.equals(MAGIC, magic)
                            || din.readInt() != FORMAT_VERSION
                            || !key.value().equals(readLongUtf(din))) {
                        deleteQuietly(file);
                        return Optional.empty();
                    }
                    FrontendResult loaded = readResult(din, request.manifest().identity());
                    if (!currentReactorDeclarations.isEmpty()) {
                        loaded = rebindPeerDeclarations(loaded, request.module(), currentReactorDeclarations);
                    }
                    if (!request.reactorSources().isEmpty()) {
                        loaded = reconcileReactorSourceOutcomes(loaded, request);
                        if (loaded == null) {
                            return Optional.empty();
                        }
                    }
                    loaded.validateFor(request);
                    touchQuietly(file);
                    return Optional.of(loaded);
                }
            }
        } catch (IOException | RuntimeException | NoSuchAlgorithmException corrupt) {
            deleteQuietly(file);
            return Optional.empty();
        }
    }

    /**
     * Rebinds upstream reactor {@link DeclarationRecord} entries inside a cached {@link FrontendResult}
     * to match current upstream declarations (e.g. updated {@link SourceSpan} line numbers after an
     * upstream method body edit that kept ABI identical).
     */
    public static FrontendResult rebindPeerDeclarations(
            FrontendResult result,
            ModuleIdentity owningModule,
            Map<EntityIdentity, DeclarationRecord> currentReactorDeclarations) {
        Objects.requireNonNull(result);
        Objects.requireNonNull(owningModule);
        if (currentReactorDeclarations == null || currentReactorDeclarations.isEmpty() || result.declarations().isEmpty()) {
            return result;
        }
        EntityScope ownScope = EntityScope.project(owningModule);
        var coveredDocs = new HashSet<SourceDocumentIdentity>(result.sources().size() * 2);
        for (var s : result.sources()) coveredDocs.add(s.document());
        List<DeclarationRecord> rebound = null;
        var orig = result.declarations();
        for (int i = 0; i < orig.size(); i++) {
            DeclarationRecord decl = orig.get(i);
            var entity = decl.entity();
            if (entity.origin() == EntityOrigin.PROJECT && !entity.stableScope().equals(ownScope)) {
                DeclarationRecord fresh = currentReactorDeclarations.get(entity.identity());
                if (fresh != null && !fresh.equals(decl)) {
                    boolean spanCovered = fresh.entity().declaration()
                            .map(sp -> coveredDocs.contains(sp.document()))
                            .orElse(true);
                    if (spanCovered) {
                        if (rebound == null) {
                            rebound = new ArrayList<>(orig);
                        }
                        rebound.set(i, fresh);
                    }
                }
            }
        }
        if (rebound == null) return result;
        return new FrontendResult(
                result.analysis(), result.frontend(), result.state(),
                rebound, result.occurrences(), result.observations(), result.sources(),
                result.coverage(), result.diagnostics(), result.types(), result.annotations(),
                result.derivedRelationships(), result.typeDeclarations(), result.memberDeclarations(),
                result.constructorCandidates(), result.httpClientCalls());
    }

    /**
     * Reconciles upstream reactor {@link SourceOutcome} entries after an ABI-preserving edit in an upstream
     * module (such as adding/removing a private helper file). Never drops a document still referenced by any
     * declaration, occurrence, observation, type, annotation, or derived record (returns {@code null} to force
     * clean re-extraction instead of deleting or corrupting the cache entry).
     */
    private static FrontendResult reconcileReactorSourceOutcomes(FrontendResult result, FrontendRequest request) {
        var primaryDocs = new HashSet<SourceDocumentIdentity>(request.sources().size() * 2);
        for (var s : request.sources()) primaryDocs.add(s.document().identity());
        var expectedReactorDocs = new LinkedHashSet<SourceDocumentIdentity>();
        for (var r : request.reactorSources()) {
            for (var s : r.sources()) expectedReactorDocs.add(s.document().identity());
        }
        var currentByDoc = new LinkedHashMap<SourceDocumentIdentity, SourceOutcome>(result.sources().size() * 2);
        for (var so : result.sources()) currentByDoc.put(so.document(), so);

        boolean changed = false;
        var updatedSources = new ArrayList<SourceOutcome>(primaryDocs.size() + expectedReactorDocs.size());
        for (var doc : primaryDocs) {
            var existing = currentByDoc.get(doc);
            if (existing == null) return null;
            updatedSources.add(existing);
        }
        for (var doc : expectedReactorDocs) {
            var existing = currentByDoc.get(doc);
            if (existing != null) {
                updatedSources.add(existing);
            } else {
                // Newly added private upstream file that did not change public ABI: never alters downstream facts.
                updatedSources.add(new SourceOutcome(doc, SourceOutcome.State.PROCESSED, List.of()));
                changed = true;
            }
        }
        if (updatedSources.size() != result.sources().size()) {
            changed = true;
        }
        if (!changed) return result;

        var keptDocs = new HashSet<SourceDocumentIdentity>(updatedSources.size() * 2);
        for (var so : updatedSources) keptDocs.add(so.document());
        for (var d : result.declarations()) {
            if (d.entity().declaration().isPresent() && !keptDocs.contains(d.entity().declaration().orElseThrow().document())) {
                return null;
            }
        }
        for (var o : result.observations()) {
            if (!keptDocs.contains(o.document())) return null;
        }
        for (var t : result.types()) {
            if (!keptDocs.contains(t.span().document())) return null;
        }
        for (var a : result.annotations()) {
            if (!keptDocs.contains(a.span().document())) return null;
        }
        for (var dr : result.derivedRelationships()) {
            for (var sp : dr.supportingSpans()) {
                if (!keptDocs.contains(sp.document())) return null;
            }
        }
        for (var c : result.constructorCandidates()) {
            for (var sp : c.supportingSpans()) {
                if (!keptDocs.contains(sp.document())) return null;
            }
        }
        for (var hc : result.httpClientCalls()) {
            if (!keptDocs.contains(hc.span().document())) return null;
        }
        return new FrontendResult(
                result.analysis(), result.frontend(), result.state(),
                result.declarations(), result.occurrences(), result.observations(), updatedSources,
                result.coverage(), result.diagnostics(), result.types(), result.annotations(),
                result.derivedRelationships(), result.typeDeclarations(), result.memberDeclarations(),
                result.constructorCandidates(), result.httpClientCalls());
    }

    public void put(FrontendRequest request, FrontendResult result) {
        if (Boolean.getBoolean("evo.incremental.disabled")) return;
        Objects.requireNonNull(request);
        Objects.requireNonNull(result);
        putByKey(cacheKey(request, result.frontend()), result);
    }

    public void putByAbi(
            FrontendRequest request,
            FrontendResult result,
            Map<ModuleIdentity, ContentDigest> reactorModuleAbiDigests) {
        if (Boolean.getBoolean("evo.incremental.disabled")) return;
        Objects.requireNonNull(request);
        Objects.requireNonNull(result);
        Objects.requireNonNull(reactorModuleAbiDigests);
        putByKey(cacheKeyWithReactorAbi(request, result.frontend(), reactorModuleAbiDigests), result);
    }

    private void putByKey(ContentDigest key, FrontendResult result) {
        Path target = entryPath(key);
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return;
        Path pending = null;
        try {
            Files.createDirectories(directory);
            pending = Files.createTempFile(directory, "jfc-", ".tmp");
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (var fileOut = new BufferedOutputStream(
                    Files.newOutputStream(pending, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING),
                    IO_BUFFER_BYTES)) {
                var digestOut = new DigestOutputStream(fileOut, md);
                var keepOpen = new FilterOutputStream(digestOut) {
                    @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); }
                    @Override public void close() throws IOException { flush(); }
                };
                try (var gz = new FastGzipOutputStream(keepOpen, IO_BUFFER_BYTES);
                     var out = new DataOutputStream(new BufferedOutputStream(gz, IO_BUFFER_BYTES))) {
                    out.write(MAGIC);
                    out.writeInt(FORMAT_VERSION);
                    writeLongUtf(out, key.value());
                    writeResult(out, result);
                    out.flush();
                }
                fileOut.write(md.digest());
                fileOut.flush();
            }
            StreamingContentStore.moveAtomicWithRetry(pending, target);
            if ((writeCount.incrementAndGet() % EVICT_CHECK_INTERVAL) == 0) {
                evictIfNeeded();
            }
        } catch (IOException | RuntimeException | NoSuchAlgorithmException ignored) {
            // Incremental cache writes are strictly best-effort.
        } finally {
            if (pending != null) deleteQuietly(pending);
        }
    }

    private static final class FastGzipOutputStream extends GZIPOutputStream {
        FastGzipOutputStream(OutputStream out, int bufferSize) throws IOException {
            super(out, bufferSize);
            this.def.setLevel(Deflater.BEST_SPEED);
        }
    }

    private void evictIfNeeded() {
        synchronized (evictionLock) {
            try (var stream = Files.list(directory)) {
                var files = stream.filter(p -> p.getFileName().toString().endsWith(".fres.gz")).toList();
                if (files.size() <= MAX_ENTRIES) return;
                var sortable = new ArrayList<Path>(files);
                sortable.sort(Comparator.comparingLong(IncrementalJavaFrontendCache::mtime));
                int remove = sortable.size() - MAX_ENTRIES;
                for (int i = 0; i < remove; i++) {
                    deleteQuietly(sortable.get(i));
                }
            } catch (IOException ignored) {
            }
        }
    }

    private static long mtime(Path p) {
        try { return Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).toMillis(); }
        catch (IOException e) { return 0L; }
    }

    private static void touchQuietly(Path p) {
        try { Files.setLastModifiedTime(p, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis())); }
        catch (IOException ignored) {}
    }

    private static void deleteQuietly(Path p) {
        try { Files.deleteIfExists(p); }
        catch (IOException ignored) {}
    }

    // ---- Strongly-typed lossless binary codec for FrontendResult ----

    private static final class Pool {
        final Map<String, Integer> stringIdx = new HashMap<>();
        final List<String> strings = new ArrayList<>();
        int intern(String s) {
            Integer idx = stringIdx.get(s);
            if (idx != null) return idx;
            idx = strings.size();
            strings.add(s);
            stringIdx.put(s, idx);
            return idx;
        }
    }

    private static void writeResult(DataOutputStream out, FrontendResult r) throws IOException {
        // String interning buffer: serialize body into a byte stream first, then write string pool ahead of body.
        var pool = new Pool();
        var bodyBytes = new ByteArrayOutputStream(8192);
        try (var body = new DataOutputStream(bodyBytes)) {
            writeVer(body, r.frontend());
            body.writeByte(r.state().ordinal());

            writeList(body, r.declarations(), d -> {
                writeEntity(body, pool, d.entity());
                body.writeInt(pool.intern(d.spelling()));
                body.writeByte(d.status().ordinal());
                writeDerivation(body, pool, d.derivation());
                writeList(body, d.uncertainties(), u -> writeUncertainty(body, pool, u));
                writeList(body, d.diagnostics(), diag -> writeDiagnostic(body, pool, diag));
            });
            writeList(body, r.occurrences(), o -> {
                body.writeInt(pool.intern(o.identity().value()));
                writeRelationship(body, pool, o.relationship());
                writeSpan(body, pool, o.span());
                body.writeInt(o.ordinal());
                body.writeByte(o.status().ordinal());
                writeDerivation(body, pool, o.derivation());
                writeList(body, o.uncertainties(), u -> writeUncertainty(body, pool, u));
                writeList(body, o.diagnostics(), diag -> writeDiagnostic(body, pool, diag));
            });
            writeList(body, r.observations(), ob -> {
                body.writeInt(pool.intern(ob.document().value()));
                body.writeInt(pool.intern(ob.category().value()));
                writeOpt(body, ob.span(), s -> writeSpan(body, pool, s));
                body.writeByte(ob.attribution().ordinal());
                body.writeByte(ob.origin().ordinal());
                body.writeByte(ob.provenance().ordinal());
                writeOpt(body, ob.mappedOccurrence(), id -> body.writeInt(pool.intern(id.value())));
                body.writeInt(pool.intern(ob.reference()));
                writeList(body, ob.diagnostics(), diag -> writeDiagnostic(body, pool, diag));
            });
            writeList(body, r.sources(), s -> {
                body.writeInt(pool.intern(s.document().value()));
                body.writeByte(s.state().ordinal());
                writeList(body, s.diagnostics(), diag -> writeDiagnostic(body, pool, diag));
            });
            writeList(body, r.coverage(), c -> {
                body.writeInt(pool.intern(c.category().value()));
                body.writeByte(c.support().ordinal());
                body.writeLong(c.attempted());
                body.writeLong(c.emitted());
                body.writeLong(c.unmappable());
            });
            writeList(body, r.diagnostics(), diag -> writeDiagnostic(body, pool, diag));
            writeList(body, r.types(), t -> {
                writeOpt(body, t.owner(), id -> body.writeInt(pool.intern(id.value())));
                body.writeInt(pool.intern(t.role().value()));
                writeSpan(body, pool, t.span());
                writeJavaType(body, pool, t.type());
                body.writeBoolean(t.variadic());
            });
            writeList(body, r.annotations(), a -> {
                body.writeInt(pool.intern(a.canonicalName()));
                body.writeInt(pool.intern(a.owner().value()));
                writeSpan(body, pool, a.span());
                body.writeInt(pool.intern(a.site()));
                body.writeInt(pool.intern(a.spelling()));
            });
            writeList(body, r.derivedRelationships(), dr -> {
                writeRelationship(body, pool, dr.relationship());
                body.writeByte(dr.status().ordinal());
                writeDerivation(body, pool, dr.derivation());
                writeList(body, dr.supportingSpans(), sp -> writeSpan(body, pool, sp));
                writeList(body, dr.uncertainties(), u -> writeUncertainty(body, pool, u));
                writeList(body, dr.diagnostics(), diag -> writeDiagnostic(body, pool, diag));
            });
            writeList(body, r.typeDeclarations(), td -> {
                body.writeInt(pool.intern(td.type().value()));
                body.writeByte(td.kind().ordinal());
                body.writeBoolean(td.abstractType());
                body.writeBoolean(td.independent());
            });
            writeList(body, r.memberDeclarations(), md -> {
                body.writeInt(pool.intern(md.member().value()));
                body.writeInt(pool.intern(md.name()));
                body.writeBoolean(md.staticMember());
                body.writeBoolean(md.abstractMember());
                body.writeBoolean(md.genericMethod());
            });
            writeList(body, r.constructorCandidates(), lc -> {
                body.writeInt(pool.intern(lc.owner().value()));
                body.writeInt(pool.intern(lc.field().value()));
                body.writeInt(lc.fieldOrder());
                body.writeInt(pool.intern(lc.generator()));
                writeJavaType(body, pool, lc.type());
                writeList(body, lc.supportingSpans(), sp -> writeSpan(body, pool, sp));
                body.writeByte(lc.status().ordinal());
            });
            writeList(body, r.httpClientCalls(), hc -> {
                body.writeInt(pool.intern(hc.caller().value()));
                body.writeInt(pool.intern(hc.clientMethod().value()));
                writeSpan(body, pool, hc.span());
                body.writeInt(pool.intern(hc.uri()));
                writeList(body, hc.methods(), m -> body.writeInt(pool.intern(m)));
            });
        }

        out.writeInt(pool.strings.size());
        for (String s : pool.strings) {
            byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
            out.writeInt(utf8.length);
            out.write(utf8);
        }
        byte[] payload = bodyBytes.toByteArray();
        out.writeInt(payload.length);
        out.write(payload);
    }

    private static FrontendResult readResult(DataInputStream in, AnalysisIdentity newAnalysis) throws IOException {
        int sCount = in.readInt();
        if (sCount < 0 || sCount > 20_000_000) throw new IOException("Invalid string table count");
        String[] strings = new String[sCount];
        for (int i = 0; i < sCount; i++) {
            int len = in.readInt();
            if (len < 0 || len > 32 * 1024 * 1024) throw new IOException("Invalid UTF8 length");
            byte[] b = new byte[len];
            in.readFully(b);
            strings[i] = new String(b, StandardCharsets.UTF_8);
        }
        int payloadLen = in.readInt();
        if (payloadLen < 0) throw new IOException("Invalid payload length");
        byte[] payload = new byte[payloadLen];
        in.readFully(payload);
        try (var bin = new DataInputStream(new ByteArrayInputStream(payload))) {
            VersionedIdentifier frontend = readVer(bin);
            FrontendResult.State state = FrontendResult.State.values()[bin.readUnsignedByte()];
            var declarations = readList(bin, () -> new DeclarationRecord(
                    readEntity(bin, strings), strings[bin.readInt()], SemanticStatus.values()[bin.readUnsignedByte()],
                    readDerivation(bin, strings),
                    readList(bin, () -> readUncertainty(bin, strings)),
                    readList(bin, () -> readDiagnostic(bin, strings))));
            var occurrences = readList(bin, () -> new RelationshipOccurrence(
                    new OccurrenceIdentity(strings[bin.readInt()]),
                    readRelationship(bin, strings),
                    readSpan(bin, strings),
                    bin.readInt(),
                    SemanticStatus.values()[bin.readUnsignedByte()],
                    readDerivation(bin, strings),
                    readList(bin, () -> readUncertainty(bin, strings)),
                    readList(bin, () -> readDiagnostic(bin, strings))));
            var observations = readList(bin, () -> new ObservationRecord(
                    new SourceDocumentIdentity(strings[bin.readInt()]),
                    new RelationshipKind(strings[bin.readInt()]),
                    readOpt(bin, () -> readSpan(bin, strings)),
                    SemanticStatus.values()[bin.readUnsignedByte()],
                    ObservationRecord.EvidenceState.values()[bin.readUnsignedByte()],
                    ObservationRecord.EvidenceState.values()[bin.readUnsignedByte()],
                    readOpt(bin, () -> new OccurrenceIdentity(strings[bin.readInt()])),
                    strings[bin.readInt()],
                    readList(bin, () -> readDiagnostic(bin, strings))));
            var sources = readList(bin, () -> new SourceOutcome(
                    new SourceDocumentIdentity(strings[bin.readInt()]),
                    SourceOutcome.State.values()[bin.readUnsignedByte()],
                    readList(bin, () -> readDiagnostic(bin, strings))));
            var coverage = readList(bin, () -> new CategoryCoverage(
                    new RelationshipKind(strings[bin.readInt()]),
                    CategoryCoverage.Support.values()[bin.readUnsignedByte()],
                    bin.readLong(), bin.readLong(), bin.readLong()));
            var diagnostics = readList(bin, () -> readDiagnostic(bin, strings));
            var types = readList(bin, () -> new TypeUseRecord(
                    readOpt(bin, () -> new EntityIdentity(strings[bin.readInt()])),
                    new RelationshipKind(strings[bin.readInt()]),
                    readSpan(bin, strings),
                    readJavaType(bin, strings),
                    bin.readBoolean()));
            var annotations = readList(bin, () -> new AnnotationUseRecord(
                    strings[bin.readInt()],
                    new EntityIdentity(strings[bin.readInt()]),
                    readSpan(bin, strings),
                    strings[bin.readInt()],
                    strings[bin.readInt()]));
            var derived = readList(bin, () -> new DerivedRelationshipRecord(
                    readRelationship(bin, strings),
                    SemanticStatus.values()[bin.readUnsignedByte()],
                    readDerivation(bin, strings),
                    readList(bin, () -> readSpan(bin, strings)),
                    readList(bin, () -> readUncertainty(bin, strings)),
                    readList(bin, () -> readDiagnostic(bin, strings))));
            var typeDeclarations = readList(bin, () -> new TypeDeclarationRecord(
                    new EntityIdentity(strings[bin.readInt()]),
                    TypeDeclarationRecord.Kind.values()[bin.readUnsignedByte()],
                    bin.readBoolean(),
                    bin.readBoolean()));
            var memberDeclarations = readList(bin, () -> new MemberDeclarationRecord(
                    new EntityIdentity(strings[bin.readInt()]),
                    strings[bin.readInt()],
                    bin.readBoolean(), bin.readBoolean(), bin.readBoolean()));
            var constructorCandidates = readList(bin, () -> new LombokConstructorCandidate(
                    new EntityIdentity(strings[bin.readInt()]),
                    new EntityIdentity(strings[bin.readInt()]),
                    bin.readInt(),
                    strings[bin.readInt()],
                    readJavaType(bin, strings),
                    readList(bin, () -> readSpan(bin, strings)),
                    LombokConstructorCandidate.Status.values()[bin.readUnsignedByte()]));
            var httpCalls = readList(bin, () -> new HttpClientCallRecord(
                    new EntityIdentity(strings[bin.readInt()]),
                    new EntityIdentity(strings[bin.readInt()]),
                    readSpan(bin, strings),
                    strings[bin.readInt()],
                    readList(bin, () -> strings[bin.readInt()])));
            return new FrontendResult(newAnalysis, frontend, state, declarations, occurrences, observations, sources,
                    coverage, diagnostics, types, annotations, derived, typeDeclarations, memberDeclarations,
                    constructorCandidates, httpCalls);
        }
    }

    @FunctionalInterface private interface WriterFn<T> { void write(T value) throws IOException; }
    @FunctionalInterface private interface ReaderFn<T> { T read() throws IOException; }

    private static <T> void writeList(DataOutputStream out, List<T> items, WriterFn<T> fn) throws IOException {
        out.writeInt(items.size());
        for (T item : items) fn.write(item);
    }
    private static <T> List<T> readList(DataInputStream in, ReaderFn<T> fn) throws IOException {
        int n = in.readInt();
        if (n == 0) return List.of();
        if (n < 0 || n > 20_000_000) throw new IOException("Invalid list size: " + n);
        var list = new ArrayList<T>(n);
        for (int i = 0; i < n; i++) list.add(fn.read());
        return list;
    }
    private static <T> void writeOpt(DataOutputStream out, Optional<T> opt, WriterFn<T> fn) throws IOException {
        out.writeBoolean(opt.isPresent());
        if (opt.isPresent()) fn.write(opt.orElseThrow());
    }
    private static <T> Optional<T> readOpt(DataInputStream in, ReaderFn<T> fn) throws IOException {
        return in.readBoolean() ? Optional.of(fn.read()) : Optional.empty();
    }
    private static void writeLongUtf(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
    private static String readLongUtf(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > 32 * 1024 * 1024) throw new IOException("Invalid UTF length: " + len);
        if (len == 0) return "";
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
    private static void writeVer(DataOutputStream out, VersionedIdentifier v) throws IOException {
        writeLongUtf(out, v.id()); writeLongUtf(out, v.version());
    }
    private static VersionedIdentifier readVer(DataInputStream in) throws IOException {
        return new VersionedIdentifier(readLongUtf(in), readLongUtf(in));
    }
    private static void writeEntity(DataOutputStream out, Pool pool, Entity e) throws IOException {
        out.writeInt(pool.intern(e.identity().value()));
        out.writeByte(e.origin().ordinal());
        out.writeInt(pool.intern(e.stableScope().value()));
        out.writeByte(e.kind().ordinal());
        out.writeInt(pool.intern(e.canonicalName()));
        writeOpt(out, e.declaration(), sp -> writeSpan(out, pool, sp));
    }
    private static Entity readEntity(DataInputStream in, String[] s) throws IOException {
        return new Entity(
                new EntityIdentity(s[in.readInt()]),
                EntityOrigin.values()[in.readUnsignedByte()],
                new EntityScope(s[in.readInt()]),
                EntityKind.values()[in.readUnsignedByte()],
                s[in.readInt()],
                readOpt(in, () -> readSpan(in, s)));
    }
    private static void writeSpan(DataOutputStream out, Pool pool, SourceSpan sp) throws IOException {
        out.writeInt(pool.intern(sp.document().value()));
        out.writeInt(sp.startLine());
        out.writeInt(sp.startColumn());
        out.writeInt(sp.endLine());
        out.writeInt(sp.endColumn());
    }
    private static SourceSpan readSpan(DataInputStream in, String[] s) throws IOException {
        return new SourceSpan(new SourceDocumentIdentity(s[in.readInt()]),
                in.readInt(), in.readInt(), in.readInt(), in.readInt());
    }
    private static void writeDerivation(DataOutputStream out, Pool pool, Derivation d) throws IOException {
        out.writeByte(d.kind().ordinal());
        out.writeInt(pool.intern(d.method().id()));
        out.writeInt(pool.intern(d.method().version()));
        writeList(out, d.inputIdentities(), id -> out.writeInt(pool.intern(id)));
    }
    private static Derivation readDerivation(DataInputStream in, String[] s) throws IOException {
        return new Derivation(DerivationKind.values()[in.readUnsignedByte()],
                new VersionedIdentifier(s[in.readInt()], s[in.readInt()]),
                readList(in, () -> s[in.readInt()]));
    }
    private static void writeUncertainty(DataOutputStream out, Pool pool, Uncertainty u) throws IOException {
        out.writeInt(pool.intern(u.code()));
        out.writeInt(pool.intern(u.message()));
        writeList(out, u.missingInputs(), m -> out.writeInt(pool.intern(m)));
    }
    private static Uncertainty readUncertainty(DataInputStream in, String[] s) throws IOException {
        return new Uncertainty(s[in.readInt()], s[in.readInt()], readList(in, () -> s[in.readInt()]));
    }
    private static void writeDiagnostic(DataOutputStream out, Pool pool, Diagnostic d) throws IOException {
        out.writeByte(d.severity().ordinal());
        out.writeInt(pool.intern(d.code()));
        out.writeInt(pool.intern(d.message()));
        writeOpt(out, d.span(), sp -> writeSpan(out, pool, sp));
        out.writeInt(d.details().size());
        for (var e : d.details().entrySet()) {
            out.writeInt(pool.intern(e.getKey()));
            out.writeInt(pool.intern(e.getValue()));
        }
    }
    private static Diagnostic readDiagnostic(DataInputStream in, String[] s) throws IOException {
        DiagnosticSeverity sev = DiagnosticSeverity.values()[in.readUnsignedByte()];
        String code = s[in.readInt()];
        String msg = s[in.readInt()];
        Optional<SourceSpan> sp = readOpt(in, () -> readSpan(in, s));
        int count = in.readInt();
        var details = new TreeMap<String, String>();
        for (int i = 0; i < count; i++) details.put(s[in.readInt()], s[in.readInt()]);
        return new Diagnostic(sev, code, msg, sp, details);
    }
    private static void writeRelationship(DataOutputStream out, Pool pool, SemanticRelationship r) throws IOException {
        out.writeInt(pool.intern(r.identity().value()));
        out.writeInt(pool.intern(r.source().value()));
        out.writeInt(pool.intern(r.kind().value()));
        if (r.target() instanceof RelationshipTarget.Resolved res) {
            out.writeByte(0);
            out.writeInt(pool.intern(res.target().value()));
        } else if (r.target() instanceof RelationshipTarget.Candidates cand) {
            out.writeByte(1);
            writeList(out, cand.candidates(), id -> out.writeInt(pool.intern(id.value())));
        } else if (r.target() instanceof RelationshipTarget.Unresolved unr) {
            out.writeByte(2);
            out.writeInt(pool.intern(unr.stableReference()));
        }
    }
    private static SemanticRelationship readRelationship(DataInputStream in, String[] s) throws IOException {
        RelationshipIdentity id = new RelationshipIdentity(s[in.readInt()]);
        EntityIdentity src = new EntityIdentity(s[in.readInt()]);
        RelationshipKind kind = new RelationshipKind(s[in.readInt()]);
        int tKind = in.readUnsignedByte();
        RelationshipTarget target = switch (tKind) {
            case 0 -> new RelationshipTarget.Resolved(new EntityIdentity(s[in.readInt()]));
            case 1 -> new RelationshipTarget.Candidates(readList(in, () -> new EntityIdentity(s[in.readInt()])));
            case 2 -> new RelationshipTarget.Unresolved(s[in.readInt()]);
            default -> throw new IOException("Invalid RelationshipTarget tag: " + tKind);
        };
        return new SemanticRelationship(id, src, kind, target);
    }
    private static void writeJavaType(DataOutputStream out, Pool pool, JavaType t) throws IOException {
        out.writeByte(t.kind().ordinal());
        out.writeInt(pool.intern(t.spelling()));
        writeOpt(out, t.target(), id -> out.writeInt(pool.intern(id.value())));
        writeList(out, t.components(), c -> writeJavaType(out, pool, c));
        writeOpt(out, t.qualifier(), q -> writeJavaType(out, pool, q));
        out.writeByte(t.status().ordinal());
    }
    private static JavaType readJavaType(DataInputStream in, String[] s) throws IOException {
        JavaType.Kind kind = JavaType.Kind.values()[in.readUnsignedByte()];
        String spelling = s[in.readInt()];
        Optional<EntityIdentity> target = readOpt(in, () -> new EntityIdentity(s[in.readInt()]));
        List<JavaType> components = readList(in, () -> readJavaType(in, s));
        Optional<JavaType> qualifier = readOpt(in, () -> readJavaType(in, s));
        SemanticStatus status = SemanticStatus.values()[in.readUnsignedByte()];
        return new JavaType(kind, spelling, target, components, qualifier, status);
    }
}
