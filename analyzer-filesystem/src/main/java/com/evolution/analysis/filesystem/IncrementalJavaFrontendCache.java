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
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Per-source-set content-addressed cache for Java {@link FrontendResult} outputs.
 * <p>
 * The key hashes only the exact inputs that determine a single source set's Java extraction
 * (sources, reactor sources, platform, ordered binaries, frontend provider, and profile),
 * deliberately excluding global snapshot or unrelated module hashes. Any hit rebinds
 * {@code FrontendResult.analysis} to the current request's {@link AnalysisIdentity}.
 * <p>
 * Every entry carries a trailing SHA-256 checksum over the entire payload and returns
 * {@link Optional#empty()} on any mismatch or corruption.
 */
public final class IncrementalJavaFrontendCache {
    private static final byte[] MAGIC = "EVOJFC1\n".getBytes(StandardCharsets.US_ASCII);
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_ENTRIES = Integer.getInteger("evo.incremental.javaCacheMaxEntries", 512);
    private static final int IO_BUFFER_BYTES = 32 * 1024;

    private final Path directory;
    private final Object evictionLock = new Object();

    public IncrementalJavaFrontendCache(Path cacheRoot) {
        this.directory = Objects.requireNonNull(cacheRoot).toAbsolutePath().normalize().resolve("java-sets");
    }

    /** Computes the deterministic key for one {@link FrontendRequest}, independent of unrelated modules. */
    public static ContentDigest cacheKey(FrontendRequest request, VersionedIdentifier provider) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(provider);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (var out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), md))) {
                out.writeUTF("java-source-set-cache:v1");
                out.writeUTF(provider.id());
                out.writeUTF(provider.version());
                String profile = request.manifest().configuration().values().getOrDefault("analysis.profile", "");
                out.writeUTF(profile);
                out.writeBoolean(request.sourceRecovery());
                out.writeUTF(request.manifest().configuration().values()
                        .getOrDefault(FrontendRequest.RECOVERY_PLATFORM_COMPATIBLE, ""));
                out.writeUTF(request.manifest().configuration().values()
                        .getOrDefault("java.binary-native-origins", ""));
                out.writeInt(request.plan().syntaxLevel().orElse(-1));
                out.writeInt(request.plan().bytecodeTarget().orElse(-1));
                out.writeBoolean(request.plan().preview());
                out.writeUTF(request.module().value());
                out.writeUTF(request.sourceSet().name());
                out.writeUTF(request.platform().entry().logicalName());
                out.writeUTF(request.platform().entry().contentDigest().value());
                out.writeInt(request.platform().release());
                out.writeUTF(request.platform().version());
                out.writeUTF(request.platform().vendor());
                out.writeInt(request.platform().artifacts().size());
                for (var art : request.platform().artifacts()) {
                    out.writeUTF(art.logicalName());
                    out.writeUTF(art.contentDigest().value());
                }
                out.writeInt(request.sources().size());
                for (var src : request.sources()) {
                    var doc = src.document();
                    out.writeUTF(doc.identity().value());
                    out.writeUTF(doc.path());
                    out.writeUTF(doc.contentDigest().value());
                    out.writeUTF(doc.classification().name());
                    out.writeUTF(src.decoding().charset());
                }
                out.writeInt(request.dependencies().size());
                for (var dep : request.dependencies()) {
                    out.writeUTF(dep.entry().logicalName());
                    out.writeUTF(dep.entry().contentDigest().value());
                    out.writeUTF(dep.originEntry().logicalName());
                    out.writeUTF(dep.originEntry().contentDigest().value());
                    out.writeUTF(dep.reactorModule().map(ModuleIdentity::value).orElse(""));
                    out.writeUTF(dep.nativeOrigin().map(o -> o.original().contentDigest().value()).orElse(""));
                }
                out.writeInt(request.reactorSources().size());
                for (var r : request.reactorSources()) {
                    out.writeInt(r.order());
                    out.writeUTF(r.identity().value());
                    out.writeInt(r.sources().size());
                    for (var rs : r.sources()) {
                        out.writeUTF(rs.document().identity().value());
                        out.writeUTF(rs.document().path());
                        out.writeUTF(rs.document().contentDigest().value());
                        out.writeUTF(rs.document().classification().name());
                        out.writeUTF(rs.decoding().charset());
                    }
                }
                out.writeBoolean(request.correspondence().isPresent());
                if (request.correspondence().isPresent()) {
                    out.writeUTF(request.manifest().configuration().values()
                            .getOrDefault("java.source-binary-correspondence", ""));
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
        ContentDigest key = cacheKey(request, provider);
        Path file = entryPath(key);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] compressedTrailer = new byte[32];
            byte[] payloadBytes;
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
                payloadBytes = all;
                try (var din = new DataInputStream(new BufferedInputStream(
                        new GZIPInputStream(new ByteArrayInputStream(payloadBytes, 0, bodyLen)), IO_BUFFER_BYTES))) {
                    byte[] magic = new byte[MAGIC.length];
                    din.readFully(magic);
                    if (!Arrays.equals(MAGIC, magic)
                            || din.readInt() != FORMAT_VERSION
                            || !key.value().equals(din.readUTF())) {
                        deleteQuietly(file);
                        return Optional.empty();
                    }
                    FrontendResult loaded = readResult(din, request.manifest().identity());
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

    public void put(FrontendRequest request, FrontendResult result) {
        if (Boolean.getBoolean("evo.incremental.disabled")) return;
        Objects.requireNonNull(request);
        Objects.requireNonNull(result);
        ContentDigest key = cacheKey(request, result.frontend());
        Path target = entryPath(key);
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return;
        Path pending = null;
        try {
            Files.createDirectories(directory);
            pending = Files.createTempFile(directory, "jfc-", ".tmp");
            var bodyBuffer = new ByteArrayOutputStream(8192);
            try (var gz = new GZIPOutputStream(bodyBuffer);
                 var out = new DataOutputStream(new BufferedOutputStream(gz, IO_BUFFER_BYTES))) {
                out.write(MAGIC);
                out.writeInt(FORMAT_VERSION);
                out.writeUTF(key.value());
                writeResult(out, result);
                out.flush();
            }
            byte[] compressed = bodyBuffer.toByteArray();
            byte[] checksum = MessageDigest.getInstance("SHA-256").digest(compressed);
            try (var out = Files.newOutputStream(pending, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                out.write(compressed);
                out.write(checksum);
            }
            try {
                Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING);
            }
            evictIfNeeded();
        } catch (IOException | RuntimeException | NoSuchAlgorithmException ignored) {
            // Incremental cache writes are strictly best-effort.
        } finally {
            if (pending != null) deleteQuietly(pending);
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
    private static void writeVer(DataOutputStream out, VersionedIdentifier v) throws IOException {
        out.writeUTF(v.id()); out.writeUTF(v.version());
    }
    private static VersionedIdentifier readVer(DataInputStream in) throws IOException {
        return new VersionedIdentifier(in.readUTF(), in.readUTF());
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
