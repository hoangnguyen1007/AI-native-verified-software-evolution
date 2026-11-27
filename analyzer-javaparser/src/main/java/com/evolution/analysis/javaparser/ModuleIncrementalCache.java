package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.source.SourceDocument;
import com.evolution.analysis.contract.source.SourceSpan;
import com.evolution.analysis.frontend.*;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.expr.SimpleName;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Durable, disk-backed + memory-fronted per-module incremental extraction store (v3).
 *
 * <p>Fixes all 5 soundness bugs of the prior prototype:
 * <ol>
 *   <li>Unbounded reverse-dependency closure via {@link FileDependencyIndex} (no {@code hops < 4} cap)</li>
 *   <li>Includes {@code lombok.config} paths and content digests in {@code moduleContextKey}</li>
 *   <li>Tracks {@code FAST_ARCHITECTURE} {@code architecturalSimpleTypeNames} and invalidates symmetric diffs</li>
 *   <li>Separates {@code ownDeclarations} / {@code reactorDeclarations} / {@code externalDeclarations} /
 *       {@code referencedPrimaryPeerIds} for O(k) Declaration-Span Rebinding without stale peer spans</li>
 *   <li>Scoped wildcard-package and static-wildcard invalidation (no global {@code java.util.*} blasting)</li>
 * </ol>
 */
public final class ModuleIncrementalCache {
    private static final int MAX_MODULES = Integer.getInteger("evo.incremental.maxCachedModules", 32);
    private static final long MAX_CACHE_WEIGHT = Long.getLong("evo.incremental.maxCachedModuleWeight", 192L * 1024 * 1024);
    private static final double SAFETY_CEILING_RATIO = 0.55;
    private static final byte[] MANIFEST_MAGIC = "EVOM3".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] UNIT_MAGIC = "EVOU3".getBytes(StandardCharsets.US_ASCII);
    private static final int FORMAT_VERSION = 5;
    private static final int IO_BUFFER_BYTES = 32768;
    private static final int PARALLEL_DISK_LOAD_MIN_UNITS = 16;

    private static volatile Path configuredDiskDir;
    private static long totalWeight = 0L;
    private static final Map<ContentDigest, ModuleEntry> CACHE =
            Collections.synchronizedMap(new LinkedHashMap<>(32, 0.75f, true));

    /** Configures an explicit disk directory for durable cross-run persistence. Pass {@code null} to clear. */
    public static void configureDiskDirectory(Path dir) {
        configuredDiskDir = dir == null ? null : dir.toAbsolutePath().normalize();
    }

    /** Returns the active disk cache directory (explicit config or {@code -Devo.incremental.moduleCacheDir}). */
    public static Path diskDirectory() {
        Path explicit = configuredDiskDir;
        if (explicit != null) return explicit;
        String prop = System.getProperty("evo.incremental.moduleCacheDir");
        return (prop == null || prop.isBlank()) ? null : Path.of(prop).toAbsolutePath().normalize();
    }

    /** Clears the in-memory LRU cache (e.g., to test cold disk reload across JVM restarts). */
    public static void clearInMemoryCache() {
        synchronized (CACHE) {
            CACHE.clear();
            totalWeight = 0L;
        }
    }

    public record IncrementalStats(
            boolean incrementalHit,
            int abiEarlyCutoffCount,
            int impactedDependentsCount,
            int reextractedUnits,
            int reusedUnits,
            int lazySkeletonsParsed,
            int reboundReactorDeclarations,
            Optional<String> fallbackReason) {
        static IncrementalStats cold(int totalUnits, String reason) {
            return new IncrementalStats(false, 0, 0, totalUnits, 0, 0, 0, Optional.ofNullable(reason));
        }
    }

    record ReactorFileAbi(
            SourceDocumentIdentity document,
            String path,
            ContentDigest contentDigest,
            ContentDigest externalAbiDigest,
            ContentDigest publicCrossModuleAbiDigest,
            String packageName,
            Set<String> declaredFqns,
            Set<String> declaredSimpleNames) {}

    static final class UnitEntry {
        private final SourceDocumentIdentity document;
        private final String path;
        private final ContentDigest contentDigest;
        private final ContentDigest externalAbiDigest;
        private final ContentDigest publicCrossModuleAbiDigest;
        private final ContentDigest springRelevantDigest;
        private final ContentDigest staticMembersDigest;
        private final String packageName;
        private final Set<String> declaredFqns;
        private final Set<String> declaredSimpleNames;
        private final Set<String> supertypeNames;
        private final Set<String> explicitImportedFqns;
        private final Set<String> wildcardImportedPackages;
        private final Set<String> staticWildcardTypes;
        private final Set<String> referencedFqns;
        private final Set<String> referencedSimpleNames;
        private final boolean hasLombokClassAnnotation;
        private final boolean hadUnresolvedOrError;
        private final List<DeclarationRecord> ownDeclarations;
        private final List<DeclarationRecord> reactorDeclarations;
        private final List<DeclarationRecord> externalDeclarations;
        private final Set<EntityIdentity> referencedPrimaryPeerIds;
        private final List<RelationshipOccurrence> occurrences;
        private final List<ObservationRecord> observations;
        private final List<TypeUseRecord> types;
        private final List<AnnotationUseRecord> annotations;
        private final List<DerivedRelationshipRecord> derived;
        private final List<TypeDeclarationRecord> shapes;
        private final List<MemberDeclarationRecord> members;
        private final List<LombokConstructorCandidate> constructorCandidates;
        private final List<HttpClientCallRecord> httpClientCalls;
        private final List<Diagnostic> unitDiagnostics;
        private final ExplicitImportTypeSolver.UnitObservation recoveryObservation;

        // S1 memoization: skip re-serializing and re-hashing unchanged UnitEntry payloads on warm saves.
        private volatile ContentDigest memoContextKey;
        private volatile ContentDigest memoPayloadKey;
        private volatile Path memoSavedRoot;

        UnitEntry(
                SourceDocumentIdentity document,
                String path,
                ContentDigest contentDigest,
                ContentDigest externalAbiDigest,
                ContentDigest publicCrossModuleAbiDigest,
                ContentDigest springRelevantDigest,
                ContentDigest staticMembersDigest,
                String packageName,
                Set<String> declaredFqns,
                Set<String> declaredSimpleNames,
                Set<String> supertypeNames,
                Set<String> explicitImportedFqns,
                Set<String> wildcardImportedPackages,
                Set<String> staticWildcardTypes,
                Set<String> referencedFqns,
                Set<String> referencedSimpleNames,
                boolean hasLombokClassAnnotation,
                boolean hadUnresolvedOrError,
                List<DeclarationRecord> ownDeclarations,
                List<DeclarationRecord> reactorDeclarations,
                List<DeclarationRecord> externalDeclarations,
                Set<EntityIdentity> referencedPrimaryPeerIds,
                List<RelationshipOccurrence> occurrences,
                List<ObservationRecord> observations,
                List<TypeUseRecord> types,
                List<AnnotationUseRecord> annotations,
                List<DerivedRelationshipRecord> derived,
                List<TypeDeclarationRecord> shapes,
                List<MemberDeclarationRecord> members,
                List<LombokConstructorCandidate> constructorCandidates,
                List<HttpClientCallRecord> httpClientCalls,
                List<Diagnostic> unitDiagnostics) {
            this(document, path, contentDigest, externalAbiDigest, publicCrossModuleAbiDigest,
                    springRelevantDigest, staticMembersDigest, packageName,
                    declaredFqns, declaredSimpleNames, supertypeNames,
                    explicitImportedFqns, wildcardImportedPackages, staticWildcardTypes,
                    referencedFqns, referencedSimpleNames,
                    hasLombokClassAnnotation, hadUnresolvedOrError,
                    ownDeclarations, reactorDeclarations, externalDeclarations, referencedPrimaryPeerIds,
                    occurrences, observations, types, annotations, derived,
                    shapes, members, constructorCandidates, httpClientCalls, unitDiagnostics,
                    ExplicitImportTypeSolver.UnitObservation.EMPTY);
        }

        UnitEntry(
                SourceDocumentIdentity document,
                String path,
                ContentDigest contentDigest,
                ContentDigest externalAbiDigest,
                ContentDigest publicCrossModuleAbiDigest,
                ContentDigest springRelevantDigest,
                ContentDigest staticMembersDigest,
                String packageName,
                Set<String> declaredFqns,
                Set<String> declaredSimpleNames,
                Set<String> supertypeNames,
                Set<String> explicitImportedFqns,
                Set<String> wildcardImportedPackages,
                Set<String> staticWildcardTypes,
                Set<String> referencedFqns,
                Set<String> referencedSimpleNames,
                boolean hasLombokClassAnnotation,
                boolean hadUnresolvedOrError,
                List<DeclarationRecord> ownDeclarations,
                List<DeclarationRecord> reactorDeclarations,
                List<DeclarationRecord> externalDeclarations,
                Set<EntityIdentity> referencedPrimaryPeerIds,
                List<RelationshipOccurrence> occurrences,
                List<ObservationRecord> observations,
                List<TypeUseRecord> types,
                List<AnnotationUseRecord> annotations,
                List<DerivedRelationshipRecord> derived,
                List<TypeDeclarationRecord> shapes,
                List<MemberDeclarationRecord> members,
                List<LombokConstructorCandidate> constructorCandidates,
                List<HttpClientCallRecord> httpClientCalls,
                List<Diagnostic> unitDiagnostics,
                ExplicitImportTypeSolver.UnitObservation recoveryObservation) {
            this.document = document;
            this.path = path;
            this.contentDigest = contentDigest;
            this.externalAbiDigest = externalAbiDigest;
            this.publicCrossModuleAbiDigest = publicCrossModuleAbiDigest;
            this.springRelevantDigest = springRelevantDigest;
            this.staticMembersDigest = staticMembersDigest;
            this.packageName = packageName;
            this.declaredFqns = declaredFqns;
            this.declaredSimpleNames = declaredSimpleNames;
            this.supertypeNames = supertypeNames;
            this.explicitImportedFqns = explicitImportedFqns;
            this.wildcardImportedPackages = wildcardImportedPackages;
            this.staticWildcardTypes = staticWildcardTypes;
            this.referencedFqns = referencedFqns;
            this.referencedSimpleNames = referencedSimpleNames;
            this.hasLombokClassAnnotation = hasLombokClassAnnotation;
            this.hadUnresolvedOrError = hadUnresolvedOrError;
            this.ownDeclarations = ownDeclarations;
            this.reactorDeclarations = reactorDeclarations;
            this.externalDeclarations = externalDeclarations;
            this.referencedPrimaryPeerIds = referencedPrimaryPeerIds;
            this.occurrences = occurrences;
            this.observations = observations;
            this.types = types;
            this.annotations = annotations;
            this.derived = derived;
            this.shapes = shapes;
            this.members = members;
            this.constructorCandidates = constructorCandidates;
            this.httpClientCalls = httpClientCalls;
            this.unitDiagnostics = unitDiagnostics;
            this.recoveryObservation = recoveryObservation != null
                    ? recoveryObservation : ExplicitImportTypeSolver.UnitObservation.EMPTY;
        }

        SourceDocumentIdentity document() { return document; }
        String path() { return path; }
        ContentDigest contentDigest() { return contentDigest; }
        ContentDigest externalAbiDigest() { return externalAbiDigest; }
        ContentDigest publicCrossModuleAbiDigest() { return publicCrossModuleAbiDigest; }
        ContentDigest springRelevantDigest() { return springRelevantDigest; }
        ContentDigest staticMembersDigest() { return staticMembersDigest; }
        String packageName() { return packageName; }
        Set<String> declaredFqns() { return declaredFqns; }
        Set<String> declaredSimpleNames() { return declaredSimpleNames; }
        Set<String> supertypeNames() { return supertypeNames; }
        Set<String> explicitImportedFqns() { return explicitImportedFqns; }
        Set<String> wildcardImportedPackages() { return wildcardImportedPackages; }
        Set<String> staticWildcardTypes() { return staticWildcardTypes; }
        Set<String> referencedFqns() { return referencedFqns; }
        Set<String> referencedSimpleNames() { return referencedSimpleNames; }
        boolean hasLombokClassAnnotation() { return hasLombokClassAnnotation; }
        boolean hadUnresolvedOrError() { return hadUnresolvedOrError; }
        List<DeclarationRecord> ownDeclarations() { return ownDeclarations; }
        List<DeclarationRecord> reactorDeclarations() { return reactorDeclarations; }
        List<DeclarationRecord> externalDeclarations() { return externalDeclarations; }
        Set<EntityIdentity> referencedPrimaryPeerIds() { return referencedPrimaryPeerIds; }
        List<RelationshipOccurrence> occurrences() { return occurrences; }
        List<ObservationRecord> observations() { return observations; }
        List<TypeUseRecord> types() { return types; }
        List<AnnotationUseRecord> annotations() { return annotations; }
        List<DerivedRelationshipRecord> derived() { return derived; }
        List<TypeDeclarationRecord> shapes() { return shapes; }
        List<MemberDeclarationRecord> members() { return members; }
        List<LombokConstructorCandidate> constructorCandidates() { return constructorCandidates; }
        List<HttpClientCallRecord> httpClientCalls() { return httpClientCalls; }
        List<Diagnostic> unitDiagnostics() { return unitDiagnostics; }
        ExplicitImportTypeSolver.UnitObservation recoveryObservation() { return recoveryObservation; }

        ContentDigest memoizedPayloadKey(ContentDigest contextKey) {
            return contextKey.equals(memoContextKey) ? memoPayloadKey : null;
        }

        boolean wasSavedTo(Path root, ContentDigest contextKey) {
            return contextKey.equals(memoContextKey) && root.equals(memoSavedRoot) && memoPayloadKey != null;
        }

        void rememberSaved(ContentDigest contextKey, ContentDigest payloadKey, Path root) {
            this.memoPayloadKey = payloadKey;
            this.memoSavedRoot = root;
            this.memoContextKey = contextKey;
        }

        FileDependencyIndex.UnitNode toIndexNode() {
            return new FileDependencyIndex.UnitNode(
                    document, path, contentDigest, externalAbiDigest, publicCrossModuleAbiDigest,
                    springRelevantDigest, staticMembersDigest, packageName,
                    declaredFqns, declaredSimpleNames, supertypeNames,
                    explicitImportedFqns, wildcardImportedPackages, staticWildcardTypes,
                    referencedFqns, referencedSimpleNames,
                    hasLombokClassAnnotation, hadUnresolvedOrError);
        }
    }

    record ModuleEntry(
            ContentDigest contextKey,
            Set<EntityIdentity> duplicateDeclarations,
            Map<SourceDocumentIdentity, UnitEntry> units,
            Map<SourceDocumentIdentity, ReactorFileAbi> reactorAbis,
            boolean hasAndroidActivity,
            Map<String, String> recoverySignatures,
            long estimatedBytes) {
        ModuleEntry(
                ContentDigest contextKey,
                Set<EntityIdentity> duplicateDeclarations,
                Map<SourceDocumentIdentity, UnitEntry> units,
                Map<SourceDocumentIdentity, ReactorFileAbi> reactorAbis,
                boolean hasAndroidActivity) {
            this(contextKey, duplicateDeclarations, units, reactorAbis, hasAndroidActivity, Map.of(), estimate(units));
        }

        ModuleEntry(
                ContentDigest contextKey,
                Set<EntityIdentity> duplicateDeclarations,
                Map<SourceDocumentIdentity, UnitEntry> units,
                Map<SourceDocumentIdentity, ReactorFileAbi> reactorAbis,
                boolean hasAndroidActivity,
                Map<String, String> recoverySignatures) {
            this(contextKey, duplicateDeclarations, units, reactorAbis, hasAndroidActivity,
                    recoverySignatures == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(recoverySignatures)),
                    estimate(units));
        }

        private static long estimate(Map<SourceDocumentIdentity, UnitEntry> units) {
            long bytes = 4096L;
            for (var u : units.values()) {
                bytes += 768L + (u.ownDeclarations().size() + u.reactorDeclarations().size()
                        + u.externalDeclarations().size() + u.occurrences().size()
                        + u.observations().size() + u.types().size() + u.annotations().size()) * 256L;
            }
            return bytes;
        }
    }

    /**
     * Computes a stable module store key independent of own source file contents AND upstream
     * reactor file contents (enabling both intra-module AND cross-module ABI early cutoff!).
     * Strictly includes {@code lombok.config} paths and content digests (fixing Bug #2).
     */
    static ContentDigest moduleContextKey(FrontendRequest request, VersionedIdentifier provider, boolean fastArchitecture) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            var bytes = new ByteArrayOutputStream(2048);
            try (var out = new DataOutputStream(bytes)) {
                writeLongUtf(out, "javaparser-module-context:v4");
                writeLongUtf(out, provider.id());
                writeLongUtf(out, provider.version());
                out.writeBoolean(fastArchitecture);
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
                out.writeInt(request.dependencies().size());
                for (var dep : request.dependencies()) {
                    writeLongUtf(out, dep.entry().logicalName());
                    writeLongUtf(out, dep.entry().contentDigest().value());
                    writeLongUtf(out, dep.originEntry().logicalName());
                    writeLongUtf(out, dep.originEntry().contentDigest().value());
                    writeLongUtf(out, dep.reactorModule().map(ModuleIdentity::value).orElse(""));
                    writeLongUtf(out, dep.nativeOrigin().map(o -> o.original().contentDigest().value()).orElse(""));
                }
                // Bind reactor slot topology (order + module + sourceSet), NOT raw reactor source digests!
                // Upstream reactor source changes are evaluated via PublicCrossModuleAbiDigest + O(k) span rebinding.
                out.writeInt(request.reactorSources().size());
                for (var r : request.reactorSources()) {
                    out.writeInt(r.order());
                    writeLongUtf(out, r.module().value());
                    writeLongUtf(out, r.sourceSet().name());
                }
                // FIX Bug #2: Track all lombok.config files, their content digests, AND captured option availability!
                var lombokConfigs = new TreeMap<String, String>();
                for (var f : request.manifest().snapshot().files()) {
                    String fp = f.path().replace('\\', '/');
                    if (fp.equals("lombok.config") || fp.endsWith("/lombok.config")) {
                        lombokConfigs.put(fp, f.contentDigest().value());
                    }
                }
                var cfgValues = request.manifest().configuration().values();
                out.writeInt(lombokConfigs.size());
                for (var e : lombokConfigs.entrySet()) {
                    writeLongUtf(out, e.getKey());
                    writeLongUtf(out, e.getValue());
                    writeLongUtf(out, cfgValues.getOrDefault(LombokConfig.CONFIG_OPTION_PREFIX + e.getKey(), ""));
                }
                // Track .aidl / AndroidManifest.xml presence for Android synthetic recovery soundness
                var androidMeta = new TreeMap<String, String>();
                for (var f : request.manifest().snapshot().files()) {
                    String fp = f.path().replace('\\', '/');
                    if (fp.endsWith(".aidl") || fp.endsWith("AndroidManifest.xml")) {
                        androidMeta.put(fp, f.contentDigest().value());
                    }
                }
                out.writeInt(androidMeta.size());
                for (var e : androidMeta.entrySet()) {
                    writeLongUtf(out, e.getKey());
                    writeLongUtf(out, e.getValue());
                }
                out.writeBoolean(request.correspondence().isPresent());
                if (request.correspondence().isPresent()) {
                    writeLongUtf(out, request.manifest().configuration().values()
                            .getOrDefault("java.source-binary-correspondence", ""));
                    var sortedViews = new TreeMap<String, String>();
                    for (var ve : request.correspondence().orElseThrow().binaryViews().entrySet()) {
                        sortedViews.put(ve.getKey().value(), ve.getValue().digest().value() + ":" + ve.getValue().size());
                    }
                    out.writeInt(sortedViews.size());
                    for (var ve : sortedViews.entrySet()) {
                        writeLongUtf(out, ve.getKey());
                        writeLongUtf(out, ve.getValue());
                    }
                }
            }
            md.update(bytes.toByteArray());
            return new ContentDigest("sha256:" + HexFormat.of().formatHex(md.digest()));
        } catch (NoSuchAlgorithmException | IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static Optional<ModuleEntry> get(ContentDigest contextKey) {
        if (Boolean.getBoolean("evo.incremental.disabled")) return Optional.empty();
        ModuleEntry inMem = CACHE.get(contextKey);
        if (inMem != null) {
            return Optional.of(inMem);
        }
        Path dir = diskDirectory();
        if (dir == null) {
            return Optional.empty();
        }
        Optional<ModuleEntry> loaded = loadFromDisk(dir, contextKey);
        loaded.ifPresent(ModuleIncrementalCache::putInMemory);
        return loaded;
    }

    static void put(ModuleEntry entry) {
        if (Boolean.getBoolean("evo.incremental.disabled")) return;
        putInMemory(entry);
        Path dir = diskDirectory();
        if (dir != null) {
            saveToDisk(dir, entry);
        }
    }

    private static void putInMemory(ModuleEntry entry) {
        synchronized (CACHE) {
            if (entry.estimatedBytes() > MAX_CACHE_WEIGHT) {
                var old = CACHE.remove(entry.contextKey());
                if (old != null) totalWeight -= old.estimatedBytes();
                return;
            }
            var old = CACHE.put(entry.contextKey(), entry);
            if (old != null) totalWeight -= old.estimatedBytes();
            totalWeight += entry.estimatedBytes();
            var it = CACHE.entrySet().iterator();
            while ((CACHE.size() > MAX_MODULES || totalWeight > MAX_CACHE_WEIGHT) && it.hasNext()) {
                var eldest = it.next();
                if (eldest.getKey().equals(entry.contextKey()) && CACHE.size() == 1) break;
                totalWeight -= eldest.getValue().estimatedBytes();
                it.remove();
            }
        }
    }

    static double safetyCeilingRatio() {
        return SAFETY_CEILING_RATIO;
    }

    /** Collects identifier tokens referenced anywhere in a full AST unit (for FAST_ARCHITECTURE diff safety). */
    static Set<String> collectReferencedSimpleNames(CompilationUnit ast) {
        var names = new HashSet<String>(64);
        ast.walk(node -> {
            if (node instanceof SimpleName sn) {
                names.add(sn.getIdentifier());
            } else if (node instanceof Name n) {
                names.add(n.getIdentifier());
            }
        });
        return Collections.unmodifiableSet(names);
    }

    /** Extracts exact resolved type FQNs referenced by a unit's extracted records for reverse-dependency indexing. */
    static Set<String> collectReferencedFqns(
            Collection<DeclarationRecord> unitDecls,
            Collection<RelationshipOccurrence> occurrences,
            Collection<TypeUseRecord> types,
            Collection<DerivedRelationshipRecord> derived,
            Map<EntityIdentity, DeclarationRecord> allDeclarations) {
        var fqns = new TreeSet<String>();
        for (var rec : unitDecls) {
            addCanonicalFqn(fqns, rec.entity().canonicalName());
        }
        for (var occ : occurrences) {
            if (occ.relationship().target() instanceof RelationshipTarget.Resolved r) {
                var d = allDeclarations.get(r.target());
                if (d != null) addCanonicalFqn(fqns, d.entity().canonicalName());
            } else if (occ.relationship().target() instanceof RelationshipTarget.Candidates c) {
                for (var cid : c.candidates()) {
                    var d = allDeclarations.get(cid);
                    if (d != null) addCanonicalFqn(fqns, d.entity().canonicalName());
                }
            }
        }
        for (var t : types) {
            for (var eid : t.type().referencedEntities()) {
                var d = allDeclarations.get(eid);
                if (d != null) addCanonicalFqn(fqns, d.entity().canonicalName());
            }
        }
        for (var dr : derived) {
            if (dr.relationship().target() instanceof RelationshipTarget.Resolved r) {
                var d = allDeclarations.get(r.target());
                if (d != null) addCanonicalFqn(fqns, d.entity().canonicalName());
            }
        }
        return Collections.unmodifiableSet(fqns);
    }

    private static void addCanonicalFqn(Set<String> out, String canonicalName) {
        JavaTypeName.fromCanonical(canonicalName).ifPresent(tn -> {
            String qn = tn.qualifiedName();
            if (!qn.isEmpty()) {
                out.add(qn);
                // Also record enclosing outer types for nested type references
                String pkg = tn.packageName();
                int minIdx = pkg.isEmpty() ? 0 : pkg.length() + 1;
                int dot = qn.lastIndexOf('.');
                while (dot > minIdx) {
                    out.add(qn.substring(0, dot));
                    dot = qn.lastIndexOf('.', dot - 1);
                }
            }
        });
    }

    static boolean hasUnresolvedOrErrors(Collection<RelationshipOccurrence> occurrences, Collection<Diagnostic> diagnostics) {
        if (!diagnostics.isEmpty()) return true;
        for (var occ : occurrences) {
            if (occ.status() != SemanticStatus.RESOLVED) return true;
        }
        return false;
    }

    // ---- Disk-backed SHA-256 authenticated persistence (EVOM3 manifest + content-addressed EVOU3 units) ----

    private static Path manifestPath(Path root, ContentDigest contextKey) {
        String hex = stripPrefix(contextKey);
        return root.resolve("modules").resolve(hex.substring(0, Math.min(2, hex.length()))).resolve(hex + ".m3.gz");
    }

    private static Path unitPayloadPath(Path root, ContentDigest unitKey) {
        String hex = stripPrefix(unitKey);
        return root.resolve("units").resolve(hex.substring(0, Math.min(2, hex.length()))).resolve(hex + ".u3.gz");
    }

    private static String stripPrefix(ContentDigest digest) {
        String v = digest.value();
        return v.startsWith("sha256:") ? v.substring(7) : v;
    }

    private static void saveToDisk(Path root, ModuleEntry entry) {
        try {
            var unitKeyMap = new LinkedHashMap<SourceDocumentIdentity, ContentDigest>(entry.units().size() * 2);
            for (var e : entry.units().entrySet()) {
                UnitEntry u = e.getValue();
                // S1 fast path: reuse memoized cryptographic payload key for unchanged clean units!
                ContentDigest memoKey = u.memoizedPayloadKey(entry.contextKey());
                if (memoKey != null) {
                    Path memoPath = unitPayloadPath(root, memoKey);
                    if (u.wasSavedTo(root, entry.contextKey())
                            || Files.isRegularFile(memoPath, LinkOption.NOFOLLOW_LINKS)) {
                        unitKeyMap.put(e.getKey(), memoKey);
                        u.rememberSaved(entry.contextKey(), memoKey, root);
                        continue;
                    }
                }
                var rawBuf = new ByteArrayOutputStream(4096);
                try (var rawOut = new DataOutputStream(rawBuf)) {
                    rawOut.write(UNIT_MAGIC);
                    rawOut.writeInt(FORMAT_VERSION);
                    writeLongUtf(rawOut, entry.contextKey().value());
                    writeUnitEntry(rawOut, u);
                    rawOut.flush();
                }
                byte[] rawBytes = rawBuf.toByteArray();
                // True cryptographic content addressing over the full serialized UnitEntry payload
                // so any semantic delta (e.g. overload target switch with identical sizes) gets a new key.
                ContentDigest uKey = ContentDigest.sha256(rawBytes);
                unitKeyMap.put(e.getKey(), uKey);
                Path uPath = unitPayloadPath(root, uKey);
                if (!Files.isRegularFile(uPath, LinkOption.NOFOLLOW_LINKS)) {
                    writeAuthenticatedRawBytes(uPath, rawBytes, true);
                }
                u.rememberSaved(entry.contextKey(), uKey, root);
            }
            Path mPath = manifestPath(root, entry.contextKey());
            writeAuthenticated(mPath, out -> {
                out.write(MANIFEST_MAGIC);
                out.writeInt(FORMAT_VERSION);
                writeLongUtf(out, entry.contextKey().value());
                out.writeBoolean(entry.hasAndroidActivity());
                writeList(out, List.copyOf(entry.duplicateDeclarations()), id -> writeLongUtf(out, id.value()));
                writeList(out, List.copyOf(entry.reactorAbis().values()), r -> {
                    writeLongUtf(out, r.document().value());
                    writeLongUtf(out, r.path());
                    writeLongUtf(out, r.contentDigest().value());
                    writeLongUtf(out, r.externalAbiDigest().value());
                    writeLongUtf(out, r.publicCrossModuleAbiDigest().value());
                    writeLongUtf(out, r.packageName());
                    writeStringSet(out, r.declaredFqns());
                    writeStringSet(out, r.declaredSimpleNames());
                });
                out.writeInt(entry.recoverySignatures().size());
                for (var re : entry.recoverySignatures().entrySet()) {
                    writeLongUtf(out, re.getKey());
                    writeLongUtf(out, re.getValue());
                }
                out.writeInt(unitKeyMap.size());
                for (var ue : unitKeyMap.entrySet()) {
                    writeLongUtf(out, ue.getKey().value());
                    writeLongUtf(out, ue.getValue().value());
                }
            });
        } catch (IOException | RuntimeException | NoSuchAlgorithmException ignored) {
            // Disk incremental cache persistence is strictly best-effort.
        }
    }

    private static Optional<ModuleEntry> loadFromDisk(Path root, ContentDigest contextKey) {
        Path mPath = manifestPath(root, contextKey);
        if (!Files.isRegularFile(mPath, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            byte[] body = readAuthenticatedBytes(mPath);
            if (body == null) {
                deleteQuietly(mPath);
                return Optional.empty();
            }
            try (var in = new DataInputStream(new BufferedInputStream(
                    new GZIPInputStream(new ByteArrayInputStream(body)), IO_BUFFER_BYTES))) {
                byte[] magic = new byte[MANIFEST_MAGIC.length];
                in.readFully(magic);
                if (!Arrays.equals(MANIFEST_MAGIC, magic)
                        || in.readInt() != FORMAT_VERSION
                        || !contextKey.value().equals(readLongUtf(in))) {
                    deleteQuietly(mPath);
                    return Optional.empty();
                }
                boolean hasAndroid = in.readBoolean();
                var dups = new LinkedHashSet<EntityIdentity>(readList(in, () -> new EntityIdentity(readLongUtf(in))));
                var reactorAbis = new LinkedHashMap<SourceDocumentIdentity, ReactorFileAbi>();
                for (var r : readList(in, () -> new ReactorFileAbi(
                        new SourceDocumentIdentity(readLongUtf(in)),
                        readLongUtf(in),
                        new ContentDigest(readLongUtf(in)),
                        new ContentDigest(readLongUtf(in)),
                        new ContentDigest(readLongUtf(in)),
                        readLongUtf(in),
                        readStringSet(in),
                        readStringSet(in)))) {
                    reactorAbis.put(r.document(), r);
                }
                int recSigCount = in.readInt();
                if (recSigCount < 0 || recSigCount > 2_000_000) return Optional.empty();
                var recoverySignatures = new TreeMap<String, String>();
                for (int i = 0; i < recSigCount; i++) {
                    recoverySignatures.put(readLongUtf(in), readLongUtf(in));
                }
                int unitCount = in.readInt();
                if (unitCount < 0 || unitCount > 2_000_000) return Optional.empty();
                var unitRefs = new ArrayList<Map.Entry<SourceDocumentIdentity, ContentDigest>>(unitCount);
                for (int i = 0; i < unitCount; i++) {
                    unitRefs.add(Map.entry(new SourceDocumentIdentity(readLongUtf(in)), new ContentDigest(readLongUtf(in))));
                }
                UnitEntry[] loadedArr = new UnitEntry[unitCount];
                if (unitCount >= PARALLEL_DISK_LOAD_MIN_UNITS) {
                    int workers = Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
                    if (workers > 1) {
                        var next = new java.util.concurrent.atomic.AtomicInteger(0);
                        var failed = new java.util.concurrent.atomic.AtomicBoolean(false);
                        var threads = new Thread[workers - 1];
                        Runnable task = () -> {
                            while (!failed.get()) {
                                int idx = next.getAndIncrement();
                                if (idx >= unitCount) break;
                                var ref = unitRefs.get(idx);
                                UnitEntry loaded = loadUnitFromDisk(root, contextKey, ref.getValue());
                                if (loaded == null || !loaded.document().equals(ref.getKey())) {
                                    failed.set(true);
                                    break;
                                }
                                loaded.rememberSaved(contextKey, ref.getValue(), root);
                                loadedArr[idx] = loaded;
                            }
                        };
                        for (int w = 0; w < threads.length; w++) {
                            threads[w] = new Thread(task, "evo-inc-load-" + w);
                            threads[w].setDaemon(true);
                            threads[w].start();
                        }
                        task.run();
                        for (Thread t : threads) {
                            try { t.join(); } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                return Optional.empty();
                            }
                        }
                        if (failed.get()) return Optional.empty();
                    } else {
                        for (int i = 0; i < unitCount; i++) {
                            var ref = unitRefs.get(i);
                            UnitEntry loaded = loadUnitFromDisk(root, contextKey, ref.getValue());
                            if (loaded == null || !loaded.document().equals(ref.getKey())) return Optional.empty();
                            loaded.rememberSaved(contextKey, ref.getValue(), root);
                            loadedArr[i] = loaded;
                        }
                    }
                } else {
                    for (int i = 0; i < unitCount; i++) {
                        var ref = unitRefs.get(i);
                        UnitEntry loaded = loadUnitFromDisk(root, contextKey, ref.getValue());
                        if (loaded == null || !loaded.document().equals(ref.getKey())) return Optional.empty();
                        loaded.rememberSaved(contextKey, ref.getValue(), root);
                        loadedArr[i] = loaded;
                    }
                }
                var units = new LinkedHashMap<SourceDocumentIdentity, UnitEntry>(Math.max(16, unitCount * 2));
                for (int i = 0; i < unitCount; i++) {
                    units.put(unitRefs.get(i).getKey(), loadedArr[i]);
                }
                return Optional.of(new ModuleEntry(
                        contextKey,
                        Collections.unmodifiableSet(dups),
                        Collections.unmodifiableMap(units),
                        Collections.unmodifiableMap(reactorAbis),
                        hasAndroid,
                        Collections.unmodifiableMap(recoverySignatures)));
            }
        } catch (IOException | RuntimeException | NoSuchAlgorithmException corrupt) {
            deleteQuietly(mPath);
            return Optional.empty();
        }
    }

    private static UnitEntry loadUnitFromDisk(Path root, ContentDigest contextKey, ContentDigest uKey) {
        Path uPath = unitPayloadPath(root, uKey);
        if (!Files.isRegularFile(uPath, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            byte[] compressed = readAuthenticatedBytes(uPath);
            if (compressed == null) {
                deleteQuietly(uPath);
                return null;
            }
            byte[] rawBytes;
            try (var gzIn = new GZIPInputStream(new ByteArrayInputStream(compressed), IO_BUFFER_BYTES)) {
                rawBytes = gzIn.readAllBytes();
            }
            if (!ContentDigest.sha256(rawBytes).equals(uKey)) {
                deleteQuietly(uPath);
                return null;
            }
            try (var in = new DataInputStream(new ByteArrayInputStream(rawBytes))) {
                byte[] magic = new byte[UNIT_MAGIC.length];
                in.readFully(magic);
                if (!Arrays.equals(UNIT_MAGIC, magic)
                        || in.readInt() != FORMAT_VERSION
                        || !contextKey.value().equals(readLongUtf(in))) {
                    deleteQuietly(uPath);
                    return null;
                }
                UnitEntry entry = readUnitEntry(in);
                if (in.available() != 0) {
                    deleteQuietly(uPath);
                    return null;
                }
                return entry;
            }
        } catch (IOException | RuntimeException | NoSuchAlgorithmException corrupt) {
            deleteQuietly(uPath);
            return null;
        }
    }

    @FunctionalInterface private interface StreamWriter { void write(DataOutputStream out) throws IOException; }
    @FunctionalInterface private interface WriterFn<T> { void write(T value) throws IOException; }
    @FunctionalInterface private interface ReaderFn<T> { T read() throws IOException; }

    private static void writeAuthenticated(Path target, StreamWriter writer)
            throws IOException, NoSuchAlgorithmException {
        var rawBuf = new ByteArrayOutputStream(4096);
        try (var out = new DataOutputStream(rawBuf)) {
            writer.write(out);
            out.flush();
        }
        writeAuthenticatedRawBytes(target, rawBuf.toByteArray(), false);
    }

    private static void writeAuthenticatedRawBytes(Path target, byte[] rawBytes, boolean contentAddressed)
            throws IOException, NoSuchAlgorithmException {
        Files.createDirectories(target.getParent());
        Path pending = Files.createTempFile(target.getParent(), "mc3-", ".tmp");
        try {
            var gzBuf = new ByteArrayOutputStream(Math.max(256, rawBytes.length / 2));
            try (var gz = new FastGzipOutputStream(gzBuf, IO_BUFFER_BYTES)) {
                gz.write(rawBytes);
            }
            byte[] compressed = gzBuf.toByteArray();
            byte[] trailer = MessageDigest.getInstance("SHA-256").digest(compressed);
            try (var fileOut = new BufferedOutputStream(
                    Files.newOutputStream(pending, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING),
                    IO_BUFFER_BYTES)) {
                fileOut.write(compressed);
                fileOut.write(trailer);
                fileOut.flush();
            }
            moveAtomicWithRetry(pending, target, contentAddressed);
        } finally {
            deleteQuietly(pending);
        }
    }

    /** Windows NTFS-safe atomic rename with bounded exponential backoff (5 attempts: 10, 20, 40, 80ms). */
    private static void moveAtomicWithRetry(Path source, Path target, boolean contentAddressed) throws IOException {
        long delayMs = 10L;
        IOException lastFailure = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                try {
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (IOException failure) {
                lastFailure = failure;
                if (contentAddressed && Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    return;
                }
                if (attempt == 4) break;
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw failure;
                }
                delayMs = Math.min(80L, delayMs * 2L);
            }
        }
        throw lastFailure;
    }

    private static void writeLongUtf(DataOutputStream out, String value) throws IOException {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(utf8.length);
        out.write(utf8);
    }

    private static String readLongUtf(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > 32 * 1024 * 1024) throw new IOException("Invalid UTF-8 length: " + len);
        byte[] buf = new byte[len];
        in.readFully(buf);
        return new String(buf, StandardCharsets.UTF_8);
    }

    private static byte[] readAuthenticatedBytes(Path path) throws IOException, NoSuchAlgorithmException {
        byte[] all = Files.readAllBytes(path);
        if (all.length < 32) return null;
        int bodyLen = all.length - 32;
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(all, 0, bodyLen);
        byte[] expected = md.digest();
        for (int i = 0; i < 32; i++) {
            if (all[bodyLen + i] != expected[i]) return null;
        }
        return Arrays.copyOf(all, bodyLen);
    }

    private static void deleteQuietly(Path p) {
        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
    }

    private static final class FastGzipOutputStream extends GZIPOutputStream {
        FastGzipOutputStream(OutputStream out, int bufferSize) throws IOException {
            super(out, bufferSize);
            this.def.setLevel(Deflater.BEST_SPEED);
        }
    }

    // ---- Strongly-typed lossless binary codec for UnitEntry ----

    private static final class Pool {
        final Map<String, Integer> idx = new HashMap<>(256);
        final List<String> strings = new ArrayList<>(256);

        int intern(String s) {
            Integer i = idx.get(s);
            if (i != null) return i;
            int next = strings.size();
            strings.add(s);
            idx.put(s, next);
            return next;
        }
    }

    private static void writeUnitEntry(DataOutputStream out, UnitEntry u) throws IOException {
        var pool = new Pool();
        var bodyBytes = new ByteArrayOutputStream(4096);
        try (var b = new DataOutputStream(bodyBytes)) {
            b.writeInt(pool.intern(u.document().value()));
            b.writeInt(pool.intern(u.path()));
            b.writeInt(pool.intern(u.contentDigest().value()));
            b.writeInt(pool.intern(u.externalAbiDigest().value()));
            b.writeInt(pool.intern(u.publicCrossModuleAbiDigest().value()));
            b.writeInt(pool.intern(u.springRelevantDigest().value()));
            b.writeInt(pool.intern(u.staticMembersDigest().value()));
            b.writeInt(pool.intern(u.packageName()));
            writePooledStringSet(b, pool, u.declaredFqns());
            writePooledStringSet(b, pool, u.declaredSimpleNames());
            writePooledStringSet(b, pool, u.supertypeNames());
            writePooledStringSet(b, pool, u.explicitImportedFqns());
            writePooledStringSet(b, pool, u.wildcardImportedPackages());
            writePooledStringSet(b, pool, u.staticWildcardTypes());
            writePooledStringSet(b, pool, u.referencedFqns());
            writePooledStringSet(b, pool, u.referencedSimpleNames());
            b.writeBoolean(u.hasLombokClassAnnotation());
            b.writeBoolean(u.hadUnresolvedOrError());

            writeList(b, u.ownDeclarations(), d -> writeDeclaration(b, pool, d));
            writeList(b, u.reactorDeclarations(), d -> writeDeclaration(b, pool, d));
            writeList(b, u.externalDeclarations(), d -> writeDeclaration(b, pool, d));
            writeList(b, List.copyOf(u.referencedPrimaryPeerIds()), id -> b.writeInt(pool.intern(id.value())));

            writeList(b, u.occurrences(), o -> {
                b.writeInt(pool.intern(o.identity().value()));
                writeRelationship(b, pool, o.relationship());
                writeSpan(b, pool, o.span());
                b.writeInt(o.ordinal());
                b.writeByte(o.status().ordinal());
                writeDerivation(b, pool, o.derivation());
                writeList(b, o.uncertainties(), un -> writeUncertainty(b, pool, un));
                writeList(b, o.diagnostics(), diag -> writeDiagnostic(b, pool, diag));
            });
            writeList(b, u.observations(), ob -> {
                b.writeInt(pool.intern(ob.document().value()));
                b.writeInt(pool.intern(ob.category().value()));
                writeOpt(b, ob.span(), s -> writeSpan(b, pool, s));
                b.writeByte(ob.attribution().ordinal());
                b.writeByte(ob.origin().ordinal());
                b.writeByte(ob.provenance().ordinal());
                writeOpt(b, ob.mappedOccurrence(), id -> b.writeInt(pool.intern(id.value())));
                b.writeInt(pool.intern(ob.reference()));
                writeList(b, ob.diagnostics(), diag -> writeDiagnostic(b, pool, diag));
            });
            writeList(b, u.types(), t -> {
                writeOpt(b, t.owner(), id -> b.writeInt(pool.intern(id.value())));
                b.writeInt(pool.intern(t.role().value()));
                writeSpan(b, pool, t.span());
                writeJavaType(b, pool, t.type());
                b.writeBoolean(t.variadic());
            });
            writeList(b, u.annotations(), a -> {
                b.writeInt(pool.intern(a.canonicalName()));
                b.writeInt(pool.intern(a.owner().value()));
                writeSpan(b, pool, a.span());
                b.writeInt(pool.intern(a.site()));
                b.writeInt(pool.intern(a.spelling()));
            });
            writeList(b, u.derived(), dr -> {
                writeRelationship(b, pool, dr.relationship());
                b.writeByte(dr.status().ordinal());
                writeDerivation(b, pool, dr.derivation());
                writeList(b, dr.supportingSpans(), sp -> writeSpan(b, pool, sp));
                writeList(b, dr.uncertainties(), un -> writeUncertainty(b, pool, un));
                writeList(b, dr.diagnostics(), diag -> writeDiagnostic(b, pool, diag));
            });
            writeList(b, u.shapes(), td -> {
                b.writeInt(pool.intern(td.type().value()));
                b.writeByte(td.kind().ordinal());
                b.writeBoolean(td.abstractType());
                b.writeBoolean(td.independent());
            });
            writeList(b, u.members(), md -> {
                b.writeInt(pool.intern(md.member().value()));
                b.writeInt(pool.intern(md.name()));
                b.writeBoolean(md.staticMember());
                b.writeBoolean(md.abstractMember());
                b.writeBoolean(md.genericMethod());
            });
            writeList(b, u.constructorCandidates(), lc -> {
                b.writeInt(pool.intern(lc.owner().value()));
                b.writeInt(pool.intern(lc.field().value()));
                b.writeInt(lc.fieldOrder());
                b.writeInt(pool.intern(lc.generator()));
                writeJavaType(b, pool, lc.type());
                writeList(b, lc.supportingSpans(), sp -> writeSpan(b, pool, sp));
                b.writeByte(lc.status().ordinal());
            });
            writeList(b, u.httpClientCalls(), hc -> {
                b.writeInt(pool.intern(hc.caller().value()));
                b.writeInt(pool.intern(hc.clientMethod().value()));
                writeSpan(b, pool, hc.span());
                b.writeInt(pool.intern(hc.uri()));
                writeList(b, hc.methods(), m -> b.writeInt(pool.intern(m)));
            });
            writeList(b, u.unitDiagnostics(), diag -> writeDiagnostic(b, pool, diag));
            writeRecoveryObservation(b, pool, u.recoveryObservation());
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

    private static UnitEntry readUnitEntry(DataInputStream in) throws IOException {
        int sCount = in.readInt();
        if (sCount < 0 || sCount > 10_000_000) throw new IOException("Invalid string pool count");
        String[] s = new String[sCount];
        for (int i = 0; i < sCount; i++) {
            int len = in.readInt();
            if (len < 0 || len > 32 * 1024 * 1024) throw new IOException("Invalid UTF-8 length");
            byte[] buf = new byte[len];
            in.readFully(buf);
            s[i] = new String(buf, StandardCharsets.UTF_8);
        }
        int payloadLen = in.readInt();
        if (payloadLen < 0) throw new IOException("Invalid payload length");
        byte[] payload = new byte[payloadLen];
        in.readFully(payload);
        try (var bin = new DataInputStream(new ByteArrayInputStream(payload))) {
            var doc = new SourceDocumentIdentity(s[bin.readInt()]);
            String path = s[bin.readInt()];
            var contentDigest = new ContentDigest(s[bin.readInt()]);
            var extAbi = new ContentDigest(s[bin.readInt()]);
            var pubAbi = new ContentDigest(s[bin.readInt()]);
            var sprAbi = new ContentDigest(s[bin.readInt()]);
            var staAbi = new ContentDigest(s[bin.readInt()]);
            String pkg = s[bin.readInt()];
            var declaredFqns = readPooledStringSet(bin, s);
            var declaredSimple = readPooledStringSet(bin, s);
            var supertypes = readPooledStringSet(bin, s);
            var explicitImports = readPooledStringSet(bin, s);
            var wildcardPkgs = readPooledStringSet(bin, s);
            var staticWildcards = readPooledStringSet(bin, s);
            var refFqns = readPooledStringSet(bin, s);
            var refSimple = readPooledStringSet(bin, s);
            boolean hasLombok = bin.readBoolean();
            boolean hadUnresolved = bin.readBoolean();

            var ownDecls = readList(bin, () -> readDeclaration(bin, s));
            var reactorDecls = readList(bin, () -> readDeclaration(bin, s));
            var extDecls = readList(bin, () -> readDeclaration(bin, s));
            var peerIds = Set.copyOf(readList(bin, () -> new EntityIdentity(s[bin.readInt()])));

            var occurrences = readList(bin, () -> new RelationshipOccurrence(
                    new OccurrenceIdentity(s[bin.readInt()]),
                    readRelationship(bin, s),
                    readSpan(bin, s),
                    bin.readInt(),
                    SemanticStatus.values()[bin.readUnsignedByte()],
                    readDerivation(bin, s),
                    readList(bin, () -> readUncertainty(bin, s)),
                    readList(bin, () -> readDiagnostic(bin, s))));
            var observations = readList(bin, () -> new ObservationRecord(
                    new SourceDocumentIdentity(s[bin.readInt()]),
                    new RelationshipKind(s[bin.readInt()]),
                    readOpt(bin, () -> readSpan(bin, s)),
                    SemanticStatus.values()[bin.readUnsignedByte()],
                    ObservationRecord.EvidenceState.values()[bin.readUnsignedByte()],
                    ObservationRecord.EvidenceState.values()[bin.readUnsignedByte()],
                    readOpt(bin, () -> new OccurrenceIdentity(s[bin.readInt()])),
                    s[bin.readInt()],
                    readList(bin, () -> readDiagnostic(bin, s))));
            var types = readList(bin, () -> new TypeUseRecord(
                    readOpt(bin, () -> new EntityIdentity(s[bin.readInt()])),
                    new RelationshipKind(s[bin.readInt()]),
                    readSpan(bin, s),
                    readJavaType(bin, s),
                    bin.readBoolean()));
            var annotations = readList(bin, () -> new AnnotationUseRecord(
                    s[bin.readInt()],
                    new EntityIdentity(s[bin.readInt()]),
                    readSpan(bin, s),
                    s[bin.readInt()],
                    s[bin.readInt()]));
            var derived = readList(bin, () -> new DerivedRelationshipRecord(
                    readRelationship(bin, s),
                    SemanticStatus.values()[bin.readUnsignedByte()],
                    readDerivation(bin, s),
                    readList(bin, () -> readSpan(bin, s)),
                    readList(bin, () -> readUncertainty(bin, s)),
                    readList(bin, () -> readDiagnostic(bin, s))));
            var shapes = readList(bin, () -> new TypeDeclarationRecord(
                    new EntityIdentity(s[bin.readInt()]),
                    TypeDeclarationRecord.Kind.values()[bin.readUnsignedByte()],
                    bin.readBoolean(),
                    bin.readBoolean()));
            var members = readList(bin, () -> new MemberDeclarationRecord(
                    new EntityIdentity(s[bin.readInt()]),
                    s[bin.readInt()],
                    bin.readBoolean(), bin.readBoolean(), bin.readBoolean()));
            var candidates = readList(bin, () -> new LombokConstructorCandidate(
                    new EntityIdentity(s[bin.readInt()]),
                    new EntityIdentity(s[bin.readInt()]),
                    bin.readInt(),
                    s[bin.readInt()],
                    readJavaType(bin, s),
                    readList(bin, () -> readSpan(bin, s)),
                    LombokConstructorCandidate.Status.values()[bin.readUnsignedByte()]));
            var httpCalls = readList(bin, () -> new HttpClientCallRecord(
                    new EntityIdentity(s[bin.readInt()]),
                    new EntityIdentity(s[bin.readInt()]),
                    readSpan(bin, s),
                    s[bin.readInt()],
                    readList(bin, () -> s[bin.readInt()])));
            var diags = readList(bin, () -> readDiagnostic(bin, s));
            var recObs = readRecoveryObservation(bin, s);

            return new UnitEntry(
                    doc, path, contentDigest, extAbi, pubAbi, sprAbi, staAbi, pkg,
                    declaredFqns, declaredSimple, supertypes, explicitImports,
                    wildcardPkgs, staticWildcards, refFqns, refSimple,
                    hasLombok, hadUnresolved,
                    ownDecls, reactorDecls, extDecls, peerIds,
                    occurrences, observations, types, annotations, derived,
                    shapes, members, candidates, httpCalls, diags, recObs);
        }
    }

    private static void writeRecoveryObservation(
            DataOutputStream out, Pool pool, ExplicitImportTypeSolver.UnitObservation obs) throws IOException {
        boolean empty = obs == null || (obs.unitKey().isEmpty()
                && obs.localSimpleToFqn().isEmpty()
                && obs.explicitFqns().isEmpty()
                && obs.qualifiedTypes().isEmpty()
                && obs.maxTypeArgs().isEmpty()
                && obs.interfaceHints().isEmpty()
                && obs.annotationHints().isEmpty()
                && obs.wildcardUses().isEmpty());
        out.writeBoolean(!empty);
        if (empty) return;
        out.writeInt(pool.intern(obs.unitKey()));
        out.writeInt(pool.intern(obs.unitPackage()));
        out.writeInt(obs.localSimpleToFqn().size());
        for (var e : new TreeMap<>(obs.localSimpleToFqn()).entrySet()) {
            out.writeInt(pool.intern(e.getKey()));
            out.writeInt(pool.intern(e.getValue()));
        }
        writePooledStringSet(out, pool, obs.explicitFqns());
        writePooledStringSet(out, pool, obs.qualifiedTypes());
        out.writeInt(obs.maxTypeArgs().size());
        for (var e : new TreeMap<>(obs.maxTypeArgs()).entrySet()) {
            out.writeInt(pool.intern(e.getKey()));
            out.writeInt(e.getValue());
        }
        writePooledStringSet(out, pool, obs.interfaceHints());
        writePooledStringSet(out, pool, obs.annotationHints());
        writeList(out, obs.wildcardUses(), wu -> {
            out.writeInt(pool.intern(wu.simpleName()));
            writePooledStringSet(out, pool, wu.wildcardPackages());
            out.writeBoolean(wu.isAnnotation());
        });
    }

    private static ExplicitImportTypeSolver.UnitObservation readRecoveryObservation(
            DataInputStream in, String[] s) throws IOException {
        if (!in.readBoolean()) return ExplicitImportTypeSolver.UnitObservation.EMPTY;
        String unitKey = s[in.readInt()];
        String unitPkg = s[in.readInt()];
        int localCount = in.readInt();
        var localMap = new HashMap<String, String>(Math.max(4, localCount * 2));
        for (int i = 0; i < localCount; i++) {
            localMap.put(s[in.readInt()], s[in.readInt()]);
        }
        var explicitFqns = readPooledStringSet(in, s);
        var qualifiedTypes = readPooledStringSet(in, s);
        int argCount = in.readInt();
        var maxArgs = new TreeMap<String, Integer>();
        for (int i = 0; i < argCount; i++) {
            maxArgs.put(s[in.readInt()], in.readInt());
        }
        var ifaceHints = readPooledStringSet(in, s);
        var annHints = readPooledStringSet(in, s);
        var wildcardUses = readList(in, () -> new ExplicitImportTypeSolver.WildcardUse(
                s[in.readInt()], readPooledStringSet(in, s), in.readBoolean()));
        return new ExplicitImportTypeSolver.UnitObservation(
                unitKey, unitPkg, Map.copyOf(localMap), explicitFqns, qualifiedTypes,
                Collections.unmodifiableMap(maxArgs), ifaceHints, annHints, wildcardUses);
    }

    private static void writeStringSet(DataOutputStream out, Set<String> items) throws IOException {
        out.writeInt(items.size());
        for (String item : items) writeLongUtf(out, item);
    }

    private static Set<String> readStringSet(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n == 0) return Set.of();
        if (n < 0 || n > 10_000_000) throw new IOException("Invalid string set size: " + n);
        var set = new LinkedHashSet<String>(n * 2);
        for (int i = 0; i < n; i++) set.add(readLongUtf(in));
        return Collections.unmodifiableSet(set);
    }

    private static void writePooledStringSet(DataOutputStream out, Pool pool, Set<String> items) throws IOException {
        out.writeInt(items.size());
        for (String item : items) out.writeInt(pool.intern(item));
    }

    private static Set<String> readPooledStringSet(DataInputStream in, String[] s) throws IOException {
        int n = in.readInt();
        if (n == 0) return Set.of();
        var set = new LinkedHashSet<String>(n * 2);
        for (int i = 0; i < n; i++) set.add(s[in.readInt()]);
        return Collections.unmodifiableSet(set);
    }

    private static <T> void writeList(DataOutputStream out, List<T> items, WriterFn<T> fn) throws IOException {
        out.writeInt(items.size());
        for (T item : items) fn.write(item);
    }

    private static <T> List<T> readList(DataInputStream in, ReaderFn<T> fn) throws IOException {
        int n = in.readInt();
        if (n == 0) return List.of();
        if (n < 0 || n > 10_000_000) throw new IOException("Invalid list size: " + n);
        var list = new ArrayList<T>(n);
        for (int i = 0; i < n; i++) list.add(fn.read());
        return Collections.unmodifiableList(list);
    }

    private static <T> void writeOpt(DataOutputStream out, Optional<T> opt, WriterFn<T> fn) throws IOException {
        out.writeBoolean(opt.isPresent());
        if (opt.isPresent()) fn.write(opt.orElseThrow());
    }

    private static <T> Optional<T> readOpt(DataInputStream in, ReaderFn<T> fn) throws IOException {
        return in.readBoolean() ? Optional.of(fn.read()) : Optional.empty();
    }

    private static void writeDeclaration(DataOutputStream out, Pool pool, DeclarationRecord d) throws IOException {
        writeEntity(out, pool, d.entity());
        out.writeInt(pool.intern(d.spelling()));
        out.writeByte(d.status().ordinal());
        writeDerivation(out, pool, d.derivation());
        writeList(out, d.uncertainties(), u -> writeUncertainty(out, pool, u));
        writeList(out, d.diagnostics(), diag -> writeDiagnostic(out, pool, diag));
    }

    private static DeclarationRecord readDeclaration(DataInputStream in, String[] s) throws IOException {
        return new DeclarationRecord(
                readEntity(in, s), s[in.readInt()], SemanticStatus.values()[in.readUnsignedByte()],
                readDerivation(in, s),
                readList(in, () -> readUncertainty(in, s)),
                readList(in, () -> readDiagnostic(in, s)));
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
