package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.source.SourceSpan;
import com.evolution.analysis.contract.source.SourceClassification;
import com.evolution.analysis.frontend.*;
import com.github.javaparser.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.type.TypeParameter;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.*;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import java.util.*;
import java.util.function.Supplier;

/**
 * Per-request state. Traversal order never enters identity or output ordering.
 */
final class Extraction {
    private static final VersionedIdentifier VERSION = JavaParserFrontend.PROVIDER;
    private static final Derivation DIRECT = new Derivation(DerivationKind.DIRECT,
            new VersionedIdentifier("java.source", "1"), List.of());
    private static final Set<String> HTTP_CLIENT_METHODS = Set.of(
            "getForObject", "getForEntity", "postForObject", "postForEntity", "postForLocation",
            "put", "patchForObject", "delete", "headForHeaders", "optionsForAllow", "exchange",
            "uri", "baseUrl", "getInstances", "choose", "request");
    private static final Set<String> WEB_CLIENT_VERB_METHODS = Set.of(
            "get", "post", "put", "patch", "delete", "head", "options");
    private static final Set<String> SPRING_BOUNDARY_METHODS = Set.of(
            "getBean", "getBeanProvider", "getBeansOfType", "publishEvent", "registerBean", "run",
            "send", "convertAndSend", "execute", "query", "queryForObject", "queryForList", "update");
    private static final Set<String> SPRING_CONFIG_ANNOTATIONS = Set.of(
            "Configuration", "SpringBootConfiguration", "SpringBootApplication", "AutoConfiguration", "Bean");
    private static final SkeletonLruCache REACTOR_SKELETON_CACHE = new SkeletonLruCache(96L << 20);
    static final java.util.concurrent.atomic.AtomicLong KOTLIN_SKELETON_CACHE_HITS = new java.util.concurrent.atomic.AtomicLong();

    private record SkeletonCacheKey(int syntaxLevel, com.evolution.analysis.contract.common.ContentDigest digest,
            String kotlinFileName) {
    }

    private record CachedReactorSkeleton(UnicodeSource translated, CompilationUnit template, long weightBytes) {
    }

    private static final class SkeletonLruCache {
        private final long maxBytes;
        private final LinkedHashMap<SkeletonCacheKey, CachedReactorSkeleton> map = new LinkedHashMap<>(128, 0.75f,
                true);
        private long usedBytes;

        SkeletonLruCache(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        synchronized CachedReactorSkeleton get(SkeletonCacheKey key) {
            return map.get(key);
        }

        synchronized void put(SkeletonCacheKey key, CachedReactorSkeleton value) {
            if (value.weightBytes() > maxBytes)
                return;
            var prev = map.put(key, value);
            if (prev != null)
                usedBytes -= prev.weightBytes();
            usedBytes += value.weightBytes();
            var it = map.entrySet().iterator();
            while (usedBytes > maxBytes && it.hasNext()) {
                usedBytes -= it.next().getValue().weightBytes();
                it.remove();
            }
        }
    }

    private final FrontendRequest request;
    private final boolean compactBodies;
    final boolean fastArchitecture;
    private final FrontendProgress progress;
    private final ResolutionEnvironment environment;
    private com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver activeSolver;
    private com.github.javaparser.symbolsolver.resolution.typesolvers.MemoryTypeSolver activeAndroidProject;
    private com.github.javaparser.symbolsolver.resolution.typesolvers.MemoryTypeSolver activeAndroidPlatform;
    private ExplicitImportTypeSolver activeExplicitImports;
    private final Map<CompilationUnit, Unit> units = new IdentityHashMap<>();
    private final Map<Unit, Unit> retainedFullUnits = new IdentityHashMap<>();
    private final List<Unit> orderedUnits = new ArrayList<>();
    private final List<ReactorUnit> reactorUnits = new ArrayList<>();
    private final Map<Node, JavaSymbolName> names = new IdentityHashMap<>();
    private final Map<Node, Entity> nodes = new IdentityHashMap<>();
    private final Set<Node> sourceNodes = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Node> reactorNodes = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<DerivedRelationshipRecord> derived = new TreeSet<>();
    private Set<EntityIdentity> activeUnitDecls;
    private final Map<EntityIdentity, DeclarationRecord> declarations = new TreeMap<>() {
        @Override
        public DeclarationRecord get(Object key) {
            var val = super.get(key);
            if (val != null && activeUnitDecls != null && key instanceof EntityIdentity id)
                activeUnitDecls.add(id);
            return val;
        }

        @Override
        public DeclarationRecord put(EntityIdentity key, DeclarationRecord value) {
            if (activeUnitDecls != null && key != null)
                activeUnitDecls.add(key);
            return super.put(key, value);
        }

        @Override
        public DeclarationRecord putIfAbsent(EntityIdentity key, DeclarationRecord value) {
            if (activeUnitDecls != null && key != null)
                activeUnitDecls.add(key);
            return super.putIfAbsent(key, value);
        }
    };
    private List<RelationshipOccurrence> activeUnitOccurrences;
    private List<DerivedRelationshipRecord> activeUnitDerived;
    private int reextractedUnitCount;
    private ModuleIncrementalCache.IncrementalStats incrementalStats;
    private final Map<SourceDocumentIdentity, SourceAbiFingerprinter.AbiSummary> unitAbis = new LinkedHashMap<>();
    private final Map<SourceDocumentIdentity, ExplicitImportTypeSolver.UnitObservation> unitRecoveryObservations = new HashMap<>();
    private final Map<SourceDocumentIdentity, ModuleIncrementalCache.ReactorFileAbi> reactorAbis = new LinkedHashMap<>();
    private final Map<SourceDocumentIdentity, Set<EntityIdentity>> headerDeclsByDoc = new HashMap<>();
    private final Map<SourceDocumentIdentity, List<DerivedRelationshipRecord>> headerDerivedByDoc = new HashMap<>();
    private final Map<String, List<EntityIdentity>> projectIdsByCanonical = new HashMap<>();
    private final Map<EntityIdentity, List<TypeUseRecord>> directTypesByOwner = new HashMap<>();
    private final Map<SourceDocumentIdentity, Map<SourceSpan, RelationshipOccurrence>> resolvedCallsByDoc = new HashMap<>();
    private final Set<EntityIdentity> duplicateDeclarations = new HashSet<>();
    private final Set<String> duplicateTypeWarnings = new HashSet<>();
    private final Map<OccurrenceIdentity, RelationshipOccurrence> occurrences = new TreeMap<>();
    private final List<ObservationRecord> observations = new ArrayList<>();
    private final List<TypeUseRecord> types = new ArrayList<>();
    private final List<AnnotationUseRecord> annotations = new ArrayList<>();
    private final List<HttpClientCallRecord> httpClientCalls = new ArrayList<>();
    private final Set<Diagnostic> diagnostics = new TreeSet<>();
    private final List<SourceOutcome> rejected = new ArrayList<>();

    private record ClosureFailure(SemanticStatus status, String code) {
    }

    private final Map<String, Optional<ClosureFailure>> recoveryHierarchies = new HashMap<>();
    private final Set<MethodCallExpr> recoveryCallInputs = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Expression, Object> unitValueCache = new IdentityHashMap<>();
    private final Map<Expression, Object> unitTypeNameCache = new IdentityHashMap<>();
    private final Map<AnnotationExpr, Object> unitAnnotationCache = new IdentityHashMap<>();
    private final Map<com.github.javaparser.ast.type.ClassOrInterfaceType, Object> unitNamedTypeCache = new IdentityHashMap<>();
    final Set<String> sourceTypeNames = new HashSet<>();
    private final Set<String> architecturalSimpleTypeNames = new HashSet<>();
    private LombokConfig lombokConfig;
    private boolean reactorResolutionDegraded;
    final LombokExtraction lombok = new LombokExtraction(this);
    private final AndroidSyntheticDeclarations android = new AndroidSyntheticDeclarations();
    private NativeMemberExtraction nativeMembers;

    private static final class Unit {
        final SourceInput input;
        final UnicodeSource translated;
        final OriginalSource source;
        final CompilationUnit ast;
        final Set<Diagnostic> diagnostics = new TreeSet<>();

        Unit(SourceInput input, CompilationUnit ast, UnicodeSource translated) {
            this.input = input;
            this.ast = ast;
            this.translated = translated;
            this.source = new OriginalSource(input.document().identity(), input.text(), translated);
        }
    }

    private record ReactorUnit(ReactorSourceInput input, Unit unit) {
    }

    private record ParsedReactorChange(
            ReactorSourceInput slot,
            SourceInput input,
            UnicodeSource translated,
            CompilationUnit skeletonAst,
            SourceAbiFingerprinter.AbiSummary abi) {
    }

    private static final class MappingFailure extends RuntimeException {
        final SemanticStatus status;
        final String code;

        MappingFailure(SemanticStatus status, String code) {
            this.status = status;
            this.code = code;
        }
    }

    Extraction(FrontendRequest request) {
        this(request, true, FrontendProgress.NOOP);
    }

    Extraction(FrontendRequest request, boolean compactBodies) {
        this(request, compactBodies, FrontendProgress.NOOP);
    }

    Extraction(FrontendRequest request, boolean compactBodies, FrontendProgress progress) {
        this(request, compactBodies, progress, new ResolutionEnvironment(Objects.requireNonNull(request)));
    }

    private Extraction(
            FrontendRequest request,
            boolean compactBodies,
            FrontendProgress progress,
            ResolutionEnvironment sharedEnvironment) {
        this.request = Objects.requireNonNull(request);
        this.compactBodies = compactBodies;
        this.progress = progress != null ? progress : FrontendProgress.NOOP;
        this.fastArchitecture = "FAST_ARCHITECTURE"
                .equals(request.manifest().configuration().values().get("analysis.profile"));
        this.environment = sharedEnvironment;
        this.activeSolver = sharedEnvironment.solver;
        this.activeAndroidProject = sharedEnvironment.androidProject;
        this.activeAndroidPlatform = sharedEnvironment.androidPlatform;
        this.activeExplicitImports = sharedEnvironment.explicitImports;
        diagnostics.addAll(environment.diagnostics);
    }

    ModuleIncrementalCache.IncrementalStats incrementalStats() {
        return incrementalStats;
    }

    FrontendResult run() {
        var moduleContextKey = ModuleIncrementalCache.moduleContextKey(request, VERSION, fastArchitecture);
        String fallbackReason = null;
        if (compactBodies
                && request.correspondence().isEmpty()
                && !Boolean.getBoolean("evo.incremental.disabled")) {
            var prevOpt = ModuleIncrementalCache.get(moduleContextKey);
            if (prevOpt.isPresent()) {
                var incExtraction = new Extraction(request, true, progress, environment);
                var incResult = incExtraction.tryIncrementalRun(prevOpt.orElseThrow(), moduleContextKey);
                if (incResult.isPresent()) {
                    this.reextractedUnitCount = incExtraction.reextractedUnitCount;
                    this.incrementalStats = incExtraction.incrementalStats;
                    return incResult.orElseThrow();
                }
                fallbackReason = incExtraction.incrementalStats != null
                        ? incExtraction.incrementalStats.fallbackReason().orElse("incremental-fallback")
                        : "incremental-fallback";
            } else {
                fallbackReason = "cache-miss";
            }
        }
        long runStartNanos = System.nanoTime();
        progress.onModuleStarted(request.module().value(), request.sourceSet().name(), request.sources().size());
        parse();
        if (request.sourceRecovery())
            diagnostics.add(new Diagnostic(DiagnosticSeverity.WARNING, "java.source-recovery",
                    "Available source, platform and artifact bindings are attributed individually; build closure remains incomplete",
                    Optional.empty(), Map.of("mode", FrontendRequest.SOURCE_RECOVERY)));
        if (fastArchitecture)
            diagnostics.add(new Diagnostic(DiagnosticSeverity.WARNING, "java.fast-architecture",
                    "Architecture-focused profile skips non-boundary local expression attribution; declarations, hierarchy, annotations, written types and boundary calls remain exact",
                    Optional.empty(), Map.of("profile", "FAST_ARCHITECTURE")));
        indexTypes();
        // Materialize declaration identities before emitting edges, so collisions can
        // be withheld.
        for (var unit : orderedUnits) {
            var docId = unit.input.document().identity();
            activeUnitDecls = headerDeclsByDoc.computeIfAbsent(docId, k -> new LinkedHashSet<>());
            activeUnitDerived = headerDerivedByDoc.computeIfAbsent(docId, k -> new ArrayList<>());
            for (var node : unit.ast.stream().filter(this::isDeclaration).toList()) {
                try {
                    entity(node);
                } catch (RuntimeException | LinkageError | StackOverflowError exception) {
                    /* Recorded in the declaration observation pass below. */ }
            }
            activeUnitDecls = null;
            activeUnitDerived = null;
        }
        nativeMembers = new NativeMemberExtraction(this, request, environment);
        nativeMembers.declarations();
        var implicit = new ImplicitExtraction(this);
        for (var unit : orderedUnits) {
            var docId = unit.input.document().identity();
            activeUnitDecls = headerDeclsByDoc.computeIfAbsent(docId, k -> new LinkedHashSet<>());
            activeUnitDerived = headerDerivedByDoc.computeIfAbsent(docId, k -> new ArrayList<>());
            implicit.declarations(unit.ast);
            activeUnitDecls = null;
            activeUnitDerived = null;
        }
        var shapes = new ArrayList<TypeDeclarationRecord>();
        var members = new ArrayList<MemberDeclarationRecord>();
        var constructorCandidates = new ArrayList<LombokConstructorCandidate>();
        var nextUnitEntries = new LinkedHashMap<SourceDocumentIdentity, ModuleIncrementalCache.UnitEntry>();

        boolean canParallelPass2 = compactBodies
                && IncrementalAndParallelSupport.isParallelEnabled()
                && orderedUnits.size() >= IncrementalAndParallelSupport.parallelThreshold()
                && request.correspondence().isEmpty()
                && duplicateDeclarations.isEmpty();
        int extraPermits = canParallelPass2
                ? IncrementalAndParallelSupport.acquireExtraPermits(orderedUnits.size())
                : 0;
        if (extraPermits > 0) {
            try {
                extractPass2Parallel(extraPermits, shapes, members, constructorCandidates, nextUnitEntries);
            } finally {
                IncrementalAndParallelSupport.releaseExtraPermits(extraPermits);
            }
        } else {
            extractPass2Serial(implicit, shapes, members, constructorCandidates, nextUnitEntries);
        }

        if (compactBodies
                && rejected.isEmpty()
                && duplicateDeclarations.isEmpty()
                && duplicateTypeWarnings.isEmpty()
                && request.correspondence().isEmpty()) {
            ModuleIncrementalCache.put(new ModuleIncrementalCache.ModuleEntry(
                    moduleContextKey,
                    Set.copyOf(duplicateDeclarations),
                    Map.copyOf(nextUnitEntries),
                    Map.copyOf(reactorAbis),
                    android.isAndroidUsed(),
                    request.sourceRecovery() ? activeExplicitImports.materializedSignatures() : Map.of()));
        }
        progress.onModuleCompleted(
                request.module().value(),
                request.sourceSet().name(),
                request.sources().size(),
                sourceTypeNames.size(),
                (System.nanoTime() - runStartNanos) / 1_000_000L,
                false);
        incrementalStats = ModuleIncrementalCache.IncrementalStats.cold(reextractedUnitCount, fallbackReason);
        return buildFrontendResult(shapes, members, constructorCandidates);
    }

    private void extractPass2Serial(
            ImplicitExtraction implicit,
            List<TypeDeclarationRecord> shapes,
            List<MemberDeclarationRecord> members,
            List<LombokConstructorCandidate> constructorCandidates,
            Map<SourceDocumentIdentity, ModuleIncrementalCache.UnitEntry> nextUnitEntries) {
        JavaParser reuseParser = null;
        int totalFiles = orderedUnits.size();
        int completedFiles = 0;
        int doneBatch = 0;
        for (var header : orderedUnits) {
            reextractedUnitCount++;
            Unit unit;
            if (compactBodies) {
                Unit retained = retainedFullUnits.remove(header);
                if (retained != null) {
                    units.put(retained.ast, retained);
                    unit = retained;
                } else {
                    if (reuseParser == null)
                        reuseParser = parser();
                    unit = parse(reuseParser, header.input, Optional.empty()).orElseThrow();
                }
            } else {
                unit = header;
            }
            var docId = header.input.document().identity();
            var abi = unitAbis.get(docId);
            if (abi == null) {
                abi = SourceAbiFingerprinter.fingerprint(header.ast, header.input.document().path());
                unitAbis.put(docId, abi);
            }
            var entry = extractSingleUnit(
                    header, unit, implicit, abi, shapes, members, constructorCandidates);
            nextUnitEntries.put(docId, entry);
            if (compactBodies)
                releaseBody(unit);
            doneBatch++;
            completedFiles++;
            if (doneBatch >= 8) {
                progress.onFilesCompleted(request.module().value(), doneBatch, completedFiles, totalFiles, false);
                doneBatch = 0;
            }
        }
        if (doneBatch > 0) {
            progress.onFilesCompleted(request.module().value(), doneBatch, completedFiles, totalFiles, false);
        }
    }

    private record ParallelUnitOutput(
            ModuleIncrementalCache.UnitEntry entry,
            List<Diagnostic> extraGlobalDiagnostics,
            Set<String> duplicateWarnings,
            Set<EntityIdentity> duplicateDecls) {
    }

    private void extractPass2Parallel(
            int extraPermits,
            List<TypeDeclarationRecord> shapes,
            List<MemberDeclarationRecord> members,
            List<LombokConstructorCandidate> constructorCandidates,
            Map<SourceDocumentIdentity, ModuleIncrementalCache.UnitEntry> nextUnitEntries) {
        int total = orderedUnits.size();
        var primaryTemplates = new HashMap<SourceDocumentIdentity, CompilationUnit>(total * 2);
        var primaryInputs = new HashMap<SourceDocumentIdentity, SourceInput>(total * 2);
        var primaryTranslated = new HashMap<SourceDocumentIdentity, UnicodeSource>(total * 2);
        var primaryFqnToDoc = new HashMap<String, SourceDocumentIdentity>(total * 2);
        var retainedAstSlots = new CompilationUnit[total];
        boolean anyLombok = false;

        for (int i = 0; i < total; i++) {
            Unit header = orderedUnits.get(i);
            SourceDocumentIdentity docId = header.input.document().identity();
            primaryTemplates.put(docId, cloneWithRangesAndSolver(header.ast, null));
            primaryInputs.put(docId, header.input);
            primaryTranslated.put(docId, header.translated);
            var abi = unitAbis.get(docId);
            if (abi == null) {
                abi = SourceAbiFingerprinter.fingerprint(header.ast, header.input.document().path());
                unitAbis.put(docId, abi);
            }
            if (abi.hasLombokAnyAnnotation())
                anyLombok = true;
            for (TypeDeclaration<?> td : header.ast.findAll(TypeDeclaration.class)) {
                if (td.getFullyQualifiedName().isEmpty() || hasLocalOwner(td))
                    continue;
                primaryFqnToDoc.putIfAbsent(td.getFullyQualifiedName().orElseThrow(), docId);
            }
            Unit retained = retainedFullUnits.remove(header);
            if (retained != null) {
                retained.ast.removeData(Node.SYMBOL_RESOLVER_KEY);
                retainedAstSlots[i] = retained.ast;
            }
        }

        var reactorTemplates = new HashMap<SourceDocumentIdentity, CompilationUnit>(reactorUnits.size() * 2 + 1);
        var reactorByDoc = new HashMap<SourceDocumentIdentity, ReactorUnit>(reactorUnits.size() * 2 + 1);
        var reactorFqnToDocBySlot = new HashMap<Integer, Map<String, SourceDocumentIdentity>>();
        for (var ru : reactorUnits) {
            SourceDocumentIdentity rDoc = ru.unit().input.document().identity();
            reactorTemplates.put(rDoc, cloneWithRangesAndSolver(ru.unit().ast, null));
            reactorByDoc.put(rDoc, ru);
            var slotMap = reactorFqnToDocBySlot.computeIfAbsent(ru.input().order(), k -> new HashMap<>());
            for (TypeDeclaration<?> td : ru.unit().ast.findAll(TypeDeclaration.class)) {
                if (td.getFullyQualifiedName().isEmpty() || hasLocalOwner(td))
                    continue;
                slotMap.putIfAbsent(td.getFullyQualifiedName().orElseThrow(), rDoc);
            }
        }

        Set<String> sharedSourceTypeNames = Set.copyOf(sourceTypeNames);
        Set<String> sharedArchSimpleNames = Set.copyOf(architecturalSimpleTypeNames);
        boolean finalAnyLombok = anyLombok;
        ParallelUnitOutput[] outputs = new ParallelUnitOutput[total];
        var parallelCompleted = new java.util.concurrent.atomic.AtomicInteger(0);

        IncrementalAndParallelSupport.runWorkStealing(total, extraPermits, nextIndex -> {
            var worker = new Extraction(request, true, progress, environment);
            worker.sourceTypeNames.addAll(sharedSourceTypeNames);
            worker.architecturalSimpleTypeNames.addAll(sharedArchSimpleNames);
            worker.lombok.setHasPotentialLombokInModule(finalAnyLombok);
            worker.unitRecoveryObservations.putAll(this.unitRecoveryObservations);

            var workerAndroidProject = new com.github.javaparser.symbolsolver.resolution.typesolvers.MemoryTypeSolver();
            var workerAndroidPlatform = new com.github.javaparser.symbolsolver.resolution.typesolvers.MemoryTypeSolver();
            var workerExplicitImports = request.sourceRecovery()
                    ? environment.explicitImports.copyForWorker()
                    : null;
            var workerSymbolSolverRef = new JavaSymbolSolver[1];
            var workerImplicitRef = new ImplicitExtraction[1];
            var loadedPrimaryHeaders = new HashMap<SourceDocumentIdentity, Unit>();

            LazySkeletonTypeSolver workerProjectSolver = new LazySkeletonTypeSolver(primaryFqnToDoc,
                    (docId, publishEarly) -> {
                        Unit existingHeader = loadedPrimaryHeaders.get(docId);
                        if (existingHeader != null) {
                            return new LazySkeletonTypeSolver.MaterializedUnit(
                                    docId, existingHeader.input, existingHeader.ast,
                                    LazySkeletonTypeSolver.indexSkeletonTypes(existingHeader.ast,
                                            worker::hasLocalOwner));
                        }
                        CompilationUnit cloned = cloneWithRangesAndSolver(primaryTemplates.get(docId),
                                workerSymbolSolverRef[0]);
                        cloned.setData(ExplicitImportTypeSolver.UNIT_PATH_KEY,
                                primaryInputs.get(docId).document().path());
                        Unit hUnit = new Unit(primaryInputs.get(docId), cloned, primaryTranslated.get(docId));
                        loadedPrimaryHeaders.put(docId, hUnit);
                        worker.units.put(cloned, hUnit);
                        cloned.stream().filter(worker::membershipNode).forEach(worker.sourceNodes::add);
                        var declsMap = LazySkeletonTypeSolver.indexSkeletonTypes(cloned, worker::hasLocalOwner);
                        var materialized = new LazySkeletonTypeSolver.MaterializedUnit(docId, hUnit.input, cloned,
                                declsMap);
                        publishEarly.accept(materialized);
                        worker.materializeHeaderDeclarationsIsolated(hUnit, workerImplicitRef[0]);
                        return materialized;
                    });

            var workerReactorSolvers = new HashMap<Integer, com.github.javaparser.resolution.TypeSolver>();
            for (var slotEntry : reactorFqnToDocBySlot.entrySet()) {
                workerReactorSolvers.put(slotEntry.getKey(), new LazySkeletonTypeSolver(slotEntry.getValue(), rDoc -> {
                    ReactorUnit ru = reactorByDoc.get(rDoc);
                    CompilationUnit cloned = cloneWithRangesAndSolver(reactorTemplates.get(rDoc),
                            workerSymbolSolverRef[0]);
                    Unit rUnit = new Unit(ru.unit().input, cloned, ru.unit().translated);
                    worker.units.put(cloned, rUnit);
                    cloned.stream().filter(worker::membershipNode).forEach(worker.reactorNodes::add);
                    return new LazySkeletonTypeSolver.MaterializedUnit(
                            rDoc, rUnit.input, cloned,
                            LazySkeletonTypeSolver.indexSkeletonTypes(cloned, worker::hasLocalOwner));
                }));
            }

            worker.activeSolver = environment.buildIsolatedWorkerSolver(
                    workerProjectSolver, workerReactorSolvers, workerAndroidProject, workerAndroidPlatform,
                    workerExplicitImports);
            worker.activeAndroidProject = workerAndroidProject;
            worker.activeAndroidPlatform = workerAndroidPlatform;
            worker.activeExplicitImports = workerExplicitImports;
            workerSymbolSolverRef[0] = new JavaSymbolSolver(worker.activeSolver);
            android.populateWorkerSolvers(workerAndroidProject, workerAndroidPlatform, workerSymbolSolverRef[0]);
            worker.nativeMembers = new NativeMemberExtraction(worker, request, environment);
            workerImplicitRef[0] = new ImplicitExtraction(worker);
            JavaParser workerParser = worker.parser();

            var localShapes = new ArrayList<TypeDeclarationRecord>();
            var localMembers = new ArrayList<MemberDeclarationRecord>();
            var localCandidates = new ArrayList<LombokConstructorCandidate>();
            int doneBatch = 0;

            while (true) {
                if (Thread.currentThread().isInterrupted())
                    throw new java.util.concurrent.CancellationException();
                int idx = nextIndex.getAndIncrement();
                if (idx >= total)
                    break;
                Unit mainHeader = orderedUnits.get(idx);
                SourceDocumentIdentity docId = mainHeader.input.document().identity();
                Unit workerHeader = loadedPrimaryHeaders.get(docId);
                if (workerHeader == null) {
                    CompilationUnit cloned = cloneWithRangesAndSolver(primaryTemplates.get(docId),
                            workerSymbolSolverRef[0]);
                    workerHeader = new Unit(primaryInputs.get(docId), cloned, primaryTranslated.get(docId));
                    loadedPrimaryHeaders.put(docId, workerHeader);
                    worker.units.put(cloned, workerHeader);
                    cloned.stream().filter(worker::membershipNode).forEach(worker.sourceNodes::add);
                    for (var e : LazySkeletonTypeSolver.indexSkeletonTypes(cloned, worker::hasLocalOwner).entrySet()) {
                        workerProjectSolver.registerEagerDeclaration(e.getKey(), e.getValue());
                    }
                    worker.materializeHeaderDeclarationsIsolated(workerHeader, workerImplicitRef[0]);
                }

                Unit workerFull;
                CompilationUnit retainedAst = retainedAstSlots[idx];
                retainedAstSlots[idx] = null;
                if (retainedAst != null) {
                    retainedAst.setData(Node.SYMBOL_RESOLVER_KEY, workerSymbolSolverRef[0]);
                    workerFull = new Unit(workerHeader.input, retainedAst, workerHeader.translated);
                    worker.units.put(retainedAst, workerFull);
                } else {
                    workerFull = worker.parse(workerParser, workerHeader.input, Optional.empty()).orElseThrow();
                }

                var prevGlobalDiags = new HashSet<>(worker.diagnostics);
                var entry = worker.extractSingleUnit(
                        workerHeader, workerFull, workerImplicitRef[0], unitAbis.get(docId),
                        localShapes, localMembers, localCandidates);
                var extraGlobal = new ArrayList<Diagnostic>();
                for (var d : worker.diagnostics) {
                    if (!prevGlobalDiags.contains(d) && !workerHeader.diagnostics.contains(d)) {
                        extraGlobal.add(d);
                    }
                }
                outputs[idx] = new ParallelUnitOutput(
                        entry,
                        extraGlobal,
                        Set.copyOf(worker.duplicateTypeWarnings),
                        Set.copyOf(worker.duplicateDeclarations));
                worker.releaseBody(workerFull);
                doneBatch++;
                if (doneBatch >= 8) {
                    int nowDone = parallelCompleted.addAndGet(doneBatch);
                    progress.onFilesCompleted(request.module().value(), doneBatch, nowDone, total, false);
                    doneBatch = 0;
                }
            }
            if (doneBatch > 0) {
                int nowDone = parallelCompleted.addAndGet(doneBatch);
                progress.onFilesCompleted(request.module().value(), doneBatch, nowDone, total, false);
            }
        });

        // Deterministic input-order merge (0..N-1) on main thread!
        for (int i = 0; i < total; i++) {
            reextractedUnitCount++;
            Unit mainHeader = orderedUnits.get(i);
            SourceDocumentIdentity docId = mainHeader.input.document().identity();
            ParallelUnitOutput out = outputs[i];
            ModuleIncrementalCache.UnitEntry entry = out.entry();
            duplicateDeclarations.addAll(out.duplicateDecls());
            duplicateTypeWarnings.addAll(out.duplicateWarnings());
            diagnostics.addAll(out.extraGlobalDiagnostics());
            for (var d : entry.ownDeclarations())
                declarations.put(d.entity().identity(), d);
            for (var d : entry.reactorDeclarations())
                declarations.putIfAbsent(d.entity().identity(), d);
            for (var d : entry.externalDeclarations())
                declarations.putIfAbsent(d.entity().identity(), d);
            for (var o : entry.occurrences())
                occurrences.putIfAbsent(o.identity(), o);
            observations.addAll(entry.observations());
            types.addAll(entry.types());
            annotations.addAll(entry.annotations());
            derived.addAll(entry.derived());
            shapes.addAll(entry.shapes());
            members.addAll(entry.members());
            constructorCandidates.addAll(entry.constructorCandidates());
            httpClientCalls.addAll(entry.httpClientCalls());
            mainHeader.diagnostics.addAll(entry.unitDiagnostics());
            diagnostics.addAll(entry.unitDiagnostics());
            nextUnitEntries.put(docId, entry);
        }
    }

    private void materializeHeaderDeclarationsIsolated(Unit headerUnit, ImplicitExtraction implicit) {
        var savedDecls = activeUnitDecls;
        var savedDerived = activeUnitDerived;
        var savedOccs = activeUnitOccurrences;
        var docId = headerUnit.input.document().identity();
        try {
            activeUnitDecls = headerDeclsByDoc.computeIfAbsent(docId, k -> new LinkedHashSet<>());
            activeUnitDerived = headerDerivedByDoc.computeIfAbsent(docId, k -> new ArrayList<>());
            activeUnitOccurrences = null;
            for (var node : headerUnit.ast.stream().filter(this::isDeclaration).toList()) {
                try {
                    entity(node);
                } catch (RuntimeException | LinkageError | StackOverflowError ignored) {
                }
            }
            if (implicit != null) {
                implicit.declarations(headerUnit.ast);
            }
        } finally {
            activeUnitDecls = savedDecls;
            activeUnitDerived = savedDerived;
            activeUnitOccurrences = savedOccs;
        }
    }

    private ModuleIncrementalCache.UnitEntry extractSingleUnit(
            Unit header,
            Unit unit,
            ImplicitExtraction implicit,
            SourceAbiFingerprinter.AbiSummary abi,
            List<TypeDeclarationRecord> shapes,
            List<MemberDeclarationRecord> members,
            List<LombokConstructorCandidate> constructorCandidates) {
        var docId = header.input.document().identity();
        unit.ast.setData(ExplicitImportTypeSolver.UNIT_PATH_KEY, unit.input.document().path());
        header.ast.setData(ExplicitImportTypeSolver.UNIT_PATH_KEY, header.input.document().path());
        unitValueCache.clear();
        unitTypeNameCache.clear();
        unitAnnotationCache.clear();
        unitNamedTypeCache.clear();
        int obsStart = observations.size();
        int typesStart = types.size();
        int annsStart = annotations.size();
        int shapesStart = shapes.size();
        int membersStart = members.size();
        int candStart = constructorCandidates.size();
        int httpStart = httpClientCalls.size();
        activeUnitDecls = new LinkedHashSet<>(headerDeclsByDoc.getOrDefault(docId, Set.of()));
        activeUnitOccurrences = new ArrayList<>();
        activeUnitDerived = new ArrayList<>(headerDerivedByDoc.getOrDefault(docId, List.of()));
        if (!fastArchitecture) {
            RecordPatternInference.prepare(unit.ast);
            SwitchAndPatternInference.prepare(unit.ast);
            lombok.prepareExpressions(unit.ast);
            if (LambdaParameterInference.prepare(unit.ast, activeSolver)) {
                SwitchAndPatternInference.prepare(unit.ast);
                lombok.prepareExpressions(unit.ast);
            }
        }
        unit.ast.stream().filter(this::membershipNode).forEach(sourceNodes::add);
        if (compactBodies)
            implicit.declarations(unit.ast, true);
        for (var node : unit.ast.stream().filter(this::isDeclaration).toList())
            observe(node, "declares", () -> entity(node), () -> owner(node));
        if (fastArchitecture) {
            Set<String> injectedNames = collectUnitBoundaryMemberNames(unit.ast);
            for (var node : unit.ast.findAll(MethodCallExpr.class)) {
                if (isArchitecturalCall(node, injectedNames)) {
                    observe(node, "calls", () -> resolveCall(node), () -> owner(node));
                }
            }
            httpClientCalls.addAll(httpClientCalls(unit));
            for (var node : unit.ast.findAll(ObjectCreationExpr.class)) {
                if (isArchitecturalCreation(node)) {
                    observe(node, "constructor-calls", () -> resolveCall(node), () -> owner(node));
                }
            }
            for (var node : unit.ast.findAll(ExplicitConstructorInvocationStmt.class)) {
                observe(node, "constructor-calls", () -> callable(node.resolve()), () -> owner(node));
            }
        } else {
            for (var node : unit.ast.findAll(MethodCallExpr.class))
                observe(node, "calls", () -> resolveCall(node), () -> owner(node));
            httpClientCalls.addAll(httpClientCalls(unit));
            for (var node : unit.ast.findAll(ObjectCreationExpr.class))
                observe(node, "constructor-calls", () -> resolveCall(node), () -> owner(node));
            for (var node : unit.ast.findAll(ExplicitConstructorInvocationStmt.class))
                observe(node, "constructor-calls", () -> callable(node.resolve()), () -> owner(node));
            var references = new ReferenceResolution(activeSolver);
            for (var node : unit.ast.findAll(MethodReferenceExpr.class))
                observe(node, "method-references", () -> {
                    var generated = lombok.resolveReference(node, references);
                    if (generated.isPresent())
                        return generated.orElseThrow();
                    var selected = references.resolve(node);
                    if (request.sourceRecovery() && selected instanceof ResolvedMethodDeclaration method) {
                        var receiver = recoveryReceiver(node, Optional.of(node.getScope()));
                        if (!ownZeroArgumentMethod(method, receiver))
                            requireRecoveryHierarchy(receiver);
                    }
                    return callable(selected, node);
                }, () -> owner(node));
        }
        var unitTypes = new TypeExtraction(this).extract(unit.ast);
        types.addAll(unitTypes);
        recordDirectTypes(unitTypes);
        if (fastArchitecture) {
            new FieldExtraction(this).extractAnnotationConstantsOnly(unit.ast);
        } else {
            new ExpressionTypeExtraction(this).extract(unit.ast);
            new FieldExtraction(this).extract(unit.ast);
        }
        new AnnotationExtraction(this).extract(unit.ast);
        implicit.relationships(unit.ast);
        if (compactBodies)
            lombok.relationships(header.ast);
        lombok.relationships(unit.ast);
        shapes.addAll(typeDeclarations(unit));
        members.addAll(memberDeclarations(unit));
        constructorCandidates.addAll(LombokCandidates.extract(this, List.of(unit.ast), unitTypes, sourceTypeNames));
        if (compactBodies && unit != header)
            header.diagnostics.addAll(unit.diagnostics);

        var unitOccs = List.copyOf(activeUnitOccurrences);
        var unitObs = List.copyOf(observations.subList(obsStart, observations.size()));
        var unitTypeList = List.copyOf(types.subList(typesStart, types.size()));
        var unitAnns = List.copyOf(annotations.subList(annsStart, annotations.size()));
        var unitDerivedList = List.copyOf(activeUnitDerived);
        var unitShapes = List.copyOf(shapes.subList(shapesStart, shapes.size()));
        var unitMembers = List.copyOf(members.subList(membersStart, members.size()));
        var unitCands = List.copyOf(constructorCandidates.subList(candStart, constructorCandidates.size()));
        var unitHttp = List.copyOf(httpClientCalls.subList(httpStart, httpClientCalls.size()));
        var unitDiags = List.copyOf(header.diagnostics);

        var touchedIds = new LinkedHashSet<>(activeUnitDecls);
        IncrementalAndParallelSupport.collectAllReferencedEntityIds(
                touchedIds, unitOccs, unitTypeList, unitAnns, unitDerivedList,
                unitShapes, unitMembers, unitCands, unitHttp);
        var partitioned = IncrementalAndParallelSupport.partitionUnitDeclarations(
                docId, request.module(), touchedIds, declarations);
        var allUnitDecls = new ArrayList<DeclarationRecord>(
                partitioned.ownDeclarations().size() + partitioned.reactorDeclarations().size()
                        + partitioned.externalDeclarations().size());
        allUnitDecls.addAll(partitioned.ownDeclarations());
        allUnitDecls.addAll(partitioned.reactorDeclarations());
        allUnitDecls.addAll(partitioned.externalDeclarations());
        var referencedFqns = ModuleIncrementalCache.collectReferencedFqns(
                allUnitDecls, unitOccs, unitTypeList, unitDerivedList, declarations);
        var referencedSimpleNames = ModuleIncrementalCache.collectReferencedSimpleNames(unit.ast);
        boolean unresolvedOrErr = ModuleIncrementalCache.hasUnresolvedOrErrors(unitOccs, unitDiags);

        activeUnitDecls = null;
        activeUnitOccurrences = null;
        activeUnitDerived = null;
        unitValueCache.clear();
        unitTypeNameCache.clear();
        unitAnnotationCache.clear();
        unitNamedTypeCache.clear();

        return new ModuleIncrementalCache.UnitEntry(
                docId,
                header.input.document().path(),
                header.input.document().contentDigest(),
                abi.externalAbiDigest(),
                abi.publicCrossModuleAbiDigest(),
                abi.springRelevantDigest(),
                abi.staticMembersDigest(),
                abi.packageName(),
                abi.declaredFqns(),
                abi.declaredSimpleNames(),
                abi.supertypeNames(),
                abi.explicitImportedFqns(),
                abi.wildcardImportedPackages(),
                abi.staticWildcardTypes(),
                referencedFqns,
                referencedSimpleNames,
                abi.hasLombokAnyAnnotation(),
                unresolvedOrErr,
                partitioned.ownDeclarations(),
                partitioned.reactorDeclarations(),
                partitioned.externalDeclarations(),
                partitioned.referencedPrimaryPeerIds(),
                unitOccs,
                unitObs,
                unitTypeList,
                unitAnns,
                unitDerivedList,
                unitShapes,
                unitMembers,
                unitCands,
                unitHttp,
                unitDiags,
                unitRecoveryObservations.getOrDefault(docId, ExplicitImportTypeSolver.UnitObservation.EMPTY));
    }

    /**
     * Demand-driven incremental extraction using {@link SourceAbiFingerprinter},
     * unbounded
     * {@link FileDependencyIndex}, {@link LazySkeletonTypeSolver} (0 unchanged
     * files parsed unless queried),
     * and O(k) Declaration-Span Rebinding. Returns {@link Optional#empty()} on any
     * disqualifier to
     * transparently fall back to full cold extraction (sharing the already-verified
     * {@link ResolutionEnvironment}).
     */
    private Optional<FrontendResult> tryIncrementalRun(
            ModuleIncrementalCache.ModuleEntry prevEntry,
            com.evolution.analysis.contract.common.ContentDigest moduleContextKey) {
        if (prevEntry.hasAndroidActivity()) {
            incrementalStats = ModuleIncrementalCache.IncrementalStats.cold(0, "android-synthetic-activity");
            return Optional.empty();
        }
        if (!prevEntry.duplicateDeclarations().isEmpty()) {
            incrementalStats = ModuleIncrementalCache.IncrementalStats.cold(0, "previous-duplicate-declarations");
            return Optional.empty();
        }
        if (request.sourceRecovery()) {
            diagnostics.add(new Diagnostic(DiagnosticSeverity.WARNING, "java.source-recovery",
                    "Available source, platform and artifact bindings are attributed individually; build closure remains incomplete",
                    Optional.empty(), Map.of("mode", FrontendRequest.SOURCE_RECOVERY)));
        }
        if (fastArchitecture) {
            diagnostics.add(new Diagnostic(DiagnosticSeverity.WARNING, "java.fast-architecture",
                    "Architecture-focused profile skips non-boundary local expression attribution; declarations, hierarchy, annotations, written types and boundary calls remain exact",
                    Optional.empty(), Map.of("profile", "FAST_ARCHITECTURE")));
        }

        // 1. Zero-parse diff of primary sources against prevEntry
        var currentSourcesByDoc = new LinkedHashMap<SourceDocumentIdentity, SourceInput>(
                request.sources().size() * 2 + 1);
        var changedOrAddedInputs = new ArrayList<SourceInput>();
        var prevOwnPackages = new HashSet<String>();
        for (var u : prevEntry.units().values()) {
            prevOwnPackages.add(u.packageName());
        }
        for (var input : request.sources()) {
            SourceDocumentIdentity docId = input.document().identity();
            currentSourcesByDoc.put(docId, input);
            var old = prevEntry.units().get(docId);
            if (old == null || !old.contentDigest().equals(input.document().contentDigest())) {
                if (SourceAbiFingerprinter.isMetaFilePath(input.document().path())) {
                    incrementalStats = ModuleIncrementalCache.IncrementalStats.cold(0, "meta-file-changed");
                    return Optional.empty();
                }
                changedOrAddedInputs.add(input);
            }
        }
        var removedDocs = new LinkedHashSet<SourceDocumentIdentity>();
        for (var oldEntry : prevEntry.units().entrySet()) {
            if (!currentSourcesByDoc.containsKey(oldEntry.getKey())) {
                if (SourceAbiFingerprinter.isMetaFilePath(oldEntry.getValue().path())) {
                    incrementalStats = ModuleIncrementalCache.IncrementalStats.cold(0, "meta-file-removed");
                    return Optional.empty();
                }
                removedDocs.add(oldEntry.getKey());
            }
        }

        // 2. Diff upstream reactor sources using PublicCrossModuleAbiDigest (or
        // ExternalAbiDigest for split packages)
        var currentReactorInputByDoc = new LinkedHashMap<SourceDocumentIdentity, SourceInput>();
        var currentReactorSlotByDoc = new HashMap<SourceDocumentIdentity, ReactorSourceInput>();
        var reactorDocOrder = new ArrayList<SourceDocumentIdentity>();
        for (var slot : request.reactorSources()) {
            for (var rIn : slot.sources()) {
                var rDoc = rIn.document().identity();
                currentReactorInputByDoc.put(rDoc, rIn);
                currentReactorSlotByDoc.put(rDoc, slot);
                reactorDocOrder.add(rDoc);
            }
        }
        var changedUpstreamReactorFqns = new LinkedHashSet<String>();
        var changedUpstreamReactorSimpleNames = new LinkedHashSet<String>();
        var shiftedReactorDocs = new LinkedHashSet<SourceDocumentIdentity>();
        var nextReactorAbis = new LinkedHashMap<SourceDocumentIdentity, ModuleIncrementalCache.ReactorFileAbi>();
        var parsedReactorChanges = new LinkedHashMap<SourceDocumentIdentity, ParsedReactorChange>();

        for (var oldReactor : prevEntry.reactorAbis().entrySet()) {
            if (!currentReactorInputByDoc.containsKey(oldReactor.getKey())) {
                if (SourceAbiFingerprinter.isMetaFilePath(oldReactor.getValue().path())) {
                    incrementalStats = ModuleIncrementalCache.IncrementalStats.cold(0, "reactor-meta-file-removed");
                    return Optional.empty();
                }
                changedUpstreamReactorFqns.addAll(oldReactor.getValue().declaredFqns());
                changedUpstreamReactorSimpleNames.addAll(oldReactor.getValue().declaredSimpleNames());
            }
        }

        JavaParser rawReactorParser = null;
        for (var entry : currentReactorInputByDoc.entrySet()) {
            SourceDocumentIdentity rDoc = entry.getKey();
            SourceInput rIn = entry.getValue();
            var old = prevEntry.reactorAbis().get(rDoc);
            if (old != null && old.contentDigest().equals(rIn.document().contentDigest())) {
                nextReactorAbis.put(rDoc, old);
                continue;
            }
            if (SourceAbiFingerprinter.isMetaFilePath(rIn.document().path())) {
                incrementalStats = ModuleIncrementalCache.IncrementalStats.cold(0, "reactor-meta-file-changed");
                return Optional.empty();
            }
            if (rawReactorParser == null)
                rawReactorParser = IncrementalAndParallelSupport.newRawParser(languageLevel());
            var raw = IncrementalAndParallelSupport.parseSingleRaw(rawReactorParser, rIn);
            if (!raw.isSuccess()) {
                incrementalStats = ModuleIncrementalCache.IncrementalStats.cold(0, "reactor-parse-error");
                return Optional.empty();
            }
            skeleton(raw.ast());
            var abi = SourceAbiFingerprinter.fingerprint(raw.ast(), rIn.document().path());
            if (abi.isMetaFile()) {
                incrementalStats = ModuleIncrementalCache.IncrementalStats.cold(0, "reactor-meta-file-ast");
                return Optional.empty();
            }
            var newReactorAbi = new ModuleIncrementalCache.ReactorFileAbi(
                    rDoc, rIn.document().path(), rIn.document().contentDigest(),
                    abi.externalAbiDigest(), abi.publicCrossModuleAbiDigest(),
                    abi.packageName(), abi.declaredFqns(), abi.declaredSimpleNames());
            nextReactorAbis.put(rDoc, newReactorAbi);
            parsedReactorChanges.put(rDoc, new ParsedReactorChange(
                    currentReactorSlotByDoc.get(rDoc), rIn, raw.translated(), raw.ast(), abi));
            if (old == null) {
                changedUpstreamReactorFqns.addAll(abi.declaredFqns());
                changedUpstreamReactorSimpleNames.addAll(abi.declaredSimpleNames());
            } else {
                boolean splitPackage = prevOwnPackages.contains(abi.packageName())
                        || prevOwnPackages.contains(old.packageName());
                boolean abiSame = (splitPackage
                        ? old.externalAbiDigest().equals(abi.externalAbiDigest())
                        : old.publicCrossModuleAbiDigest().equals(abi.publicCrossModuleAbiDigest()))
                        && old.packageName().equals(abi.packageName())
                        && old.declaredFqns().equals(abi.declaredFqns())
                        && old.declaredSimpleNames().equals(abi.declaredSimpleNames());
                if (abiSame) {
                    shiftedReactorDocs.add(rDoc);
                } else {
                    changedUpstreamReactorFqns.addAll(old.declaredFqns());
                    changedUpstreamReactorFqns.addAll(abi.declaredFqns());
                    for (String sn : old.declaredSimpleNames()) {
                        if (!abi.declaredSimpleNames().contains(sn))
                            changedUpstreamReactorSimpleNames.add(sn);
                    }
                    for (String sn : abi.declaredSimpleNames()) {
                        if (!old.declaredSimpleNames().contains(sn))
                            changedUpstreamReactorSimpleNames.add(sn);
                    }
                }
            }
        }

        // Build full reactor FQN index across slots (for lazy resolution &
        // duplicateExternal checks)
        var reactorFqnToDocBySlot = new HashMap<Integer, Map<String, SourceDocumentIdentity>>();
        var allReactorFqns = new HashSet<String>();
        var duplicateReactorFqns = new HashSet<String>();
        for (var entry : nextReactorAbis.entrySet()) {
            ReactorSourceInput slot = currentReactorSlotByDoc.get(entry.getKey());
            var slotMap = reactorFqnToDocBySlot.computeIfAbsent(slot.order(), k -> new HashMap<>());
            for (String fqn : entry.getValue().declaredFqns()) {
                if (slotMap.putIfAbsent(fqn, entry.getKey()) != null) {
                    incrementalStats = ModuleIncrementalCache.IncrementalStats.cold(0, "duplicate-reactor-type");
                    return Optional.empty();
                }
                if (!allReactorFqns.add(fqn)) {
                    duplicateReactorFqns.add(fqn);
                }
            }
        }

        // 3. CASE 0: Zero own edits AND zero upstream ABI breaks!
        if (changedOrAddedInputs.isEmpty()
                && removedDocs.isEmpty()
                && changedUpstreamReactorFqns.isEmpty()
                && (!fastArchitecture || changedUpstreamReactorSimpleNames.isEmpty())) {
            boolean hasKotlinReactor = currentReactorInputByDoc.values().stream()
                    .anyMatch(s -> s.document().path().endsWith(".kt"));
            if (request.sources().size() == 1 && hasKotlinReactor && shiftedReactorDocs.isEmpty()) {
                incrementalStats = ModuleIncrementalCache.IncrementalStats.cold(0, "single-file-kotlin-reactor-noop");
                return Optional.empty();
            }
            var reboundCounter = new java.util.concurrent.atomic.AtomicInteger(0);
            Map<EntityIdentity, DeclarationRecord> freshReactorDecls = Map.of();
            if (!shiftedReactorDocs.isEmpty()) {
                // Build lazy reactor solvers bound to boundSymRef AFTER activeSolver includes
                // them (P1 fix).
                var boundSymRef = new JavaSymbolSolver[1];
                var lazyReactorSolvers = buildLazyReactorSolvers(
                        reactorFqnToDocBySlot, currentReactorInputByDoc, parsedReactorChanges, boundSymRef);
                activeSolver = environment.buildIsolatedWorkerSolver(
                        new LazySkeletonTypeSolver(Map.of(), id -> null),
                        lazyReactorSolvers, null, null, null);
                environment.installLazySolvers(null, lazyReactorSolvers, allReactorFqns, duplicateReactorFqns);
                boundSymRef[0] = new JavaSymbolSolver(activeSolver);
                freshReactorDecls = extractShiftedReactorDeclarations(shiftedReactorDocs, parsedReactorChanges,
                        boundSymRef[0]);
                if (freshReactorDecls == null) {
                    incrementalStats = ModuleIncrementalCache.IncrementalStats.cold(0,
                            "shifted-reactor-extract-failed");
                    return Optional.empty();
                }
            }

            var updatedEntries = new LinkedHashMap<SourceDocumentIdentity, ModuleIncrementalCache.UnitEntry>(
                    request.sources().size() * 2 + 1);
            for (var input : request.sources()) {
                var docId = input.document().identity();
                var cached = prevEntry.units().get(docId);
                if (cached == null) {
                    incrementalStats = ModuleIncrementalCache.IncrementalStats.cold(0, "missing-cached-unit");
                    return Optional.empty();
                }
                if (!shiftedReactorDocs.isEmpty()) {
                    var rebound = IncrementalAndParallelSupport.rebindReactorDeclarations(
                            cached.reactorDeclarations(), shiftedReactorDocs, freshReactorDecls, reboundCounter);
                    if (rebound == null) {
                        incrementalStats = ModuleIncrementalCache.IncrementalStats.cold(0, "reactor-rebind-mismatch");
                        return Optional.empty();
                    }
                    if (rebound != cached.reactorDeclarations()) {
                        cached = withReactorDeclarations(cached, rebound);
                    }
                }
                updatedEntries.put(docId, cached);
            }
            var assembled = IncrementalAndParallelSupport.assembleFromUnitEntries(
                    request, VERSION, fastArchitecture, false,
                    updatedEntries.values(), reactorDocOrder, diagnostics);
            if (assembled.isEmpty()) {
                incrementalStats = ModuleIncrementalCache.IncrementalStats.cold(0, "assembly-invariant-mismatch");
                return Optional.empty();
            }
            if (!shiftedReactorDocs.isEmpty()) {
                ModuleIncrementalCache.put(new ModuleIncrementalCache.ModuleEntry(
                        moduleContextKey, Set.of(), Map.copyOf(updatedEntries), Map.copyOf(nextReactorAbis), false,
                        prevEntry.recoverySignatures()));
            }
            int totalFiles = request.sources().size();
            progress.onModuleStarted(request.module().value(), request.sourceSet().name(), totalFiles);
            progress.onFilesCompleted(request.module().value(), totalFiles, totalFiles, totalFiles, true);
            progress.onModuleCompleted(
                    request.module().value(),
                    request.sourceSet().name(),
                    totalFiles,
                    updatedEntries.values().stream().mapToInt(u -> u.declaredFqns().size()).sum(),
                    0L,
                    true);
            this.reextractedUnitCount = 0;
            this.incrementalStats = new ModuleIncrementalCache.IncrementalStats(
                    true, 0, 0, 0, request.sources().size(), 0, reboundCounter.get(), Optional.empty());
            return assembled;
        }

        // 4. CASE A (Early Cutoff) & CASE B (Unbounded Fixpoint +
        // LazySkeletonTypeSolver)
        // Parse ONLY changedOrAddedInputs first!
        int parsePermits = IncrementalAndParallelSupport.acquireExtraPermits(changedOrAddedInputs.size());
        IncrementalAndParallelSupport.RawParsedSource[] rawChanged;
        try {
            rawChanged = IncrementalAndParallelSupport.parseSourcesInParallel(
                    changedOrAddedInputs, languageLevel(), parsePermits);
        } finally {
            IncrementalAndParallelSupport.releaseExtraPermits(parsePermits);
        }

        var changedFullByDoc = new LinkedHashMap<SourceDocumentIdentity, IncrementalAndParallelSupport.RawParsedSource>();
        var changedAbiByDoc = new LinkedHashMap<SourceDocumentIdentity, SourceAbiFingerprinter.AbiSummary>();
        var changedNodes = new LinkedHashMap<SourceDocumentIdentity, FileDependencyIndex.UnitNode>();
        var changedFqns = new HashSet<String>();

        for (var raw : rawChanged) {
            if (!raw.isSuccess()) {
                incrementalStats = ModuleIncrementalCache.IncrementalStats.cold(0, "changed-unit-parse-error");
                return Optional.empty();
            }
            android.observeUnit(raw.ast());
            if (android.isAndroidUsed()) {
                incrementalStats = ModuleIncrementalCache.IncrementalStats.cold(0, "android-detected-in-change");
                return Optional.empty();
            }
            SourceDocumentIdentity docId = raw.input().document().identity();
            var abi = SourceAbiFingerprinter.fingerprint(raw.ast(), raw.input().document().path());
            if (abi.isMetaFile()) {
                incrementalStats = ModuleIncrementalCache.IncrementalStats.cold(0, "meta-file-ast");
                return Optional.empty();
            }
            for (String fqn : abi.declaredFqns()) {
                if (!changedFqns.add(fqn)) {
                    incrementalStats = ModuleIncrementalCache.IncrementalStats.cold(0, "duplicate-type-in-changed");
                    return Optional.empty();
                }
            }
            changedFullByDoc.put(docId, raw);
            changedAbiByDoc.put(docId, abi);
            changedNodes.put(docId, new FileDependencyIndex.UnitNode(
                    docId, raw.input().document().path(), raw.input().document().contentDigest(),
                    abi.externalAbiDigest(), abi.publicCrossModuleAbiDigest(),
                    abi.springRelevantDigest(), abi.staticMembersDigest(),
                    abi.packageName(), abi.declaredFqns(), abi.declaredSimpleNames(),
                    abi.supertypeNames(), abi.explicitImportedFqns(),
                    abi.wildcardImportedPackages(), abi.staticWildcardTypes(),
                    Set.of(), Set.of(), abi.hasLombokAnyAnnotation(), false));
        }

        // Verify no FQN collision between changed/added units and unchanged clean units
        for (var prev : prevEntry.units().entrySet()) {
            SourceDocumentIdentity docId = prev.getKey();
            if (changedFullByDoc.containsKey(docId) || removedDocs.contains(docId))
                continue;
            for (String fqn : prev.getValue().declaredFqns()) {
                if (changedFqns.contains(fqn)) {
                    incrementalStats = ModuleIncrementalCache.IncrementalStats.cold(0, "duplicate-type-vs-clean");
                    return Optional.empty();
                }
            }
        }

        // If a newly added primary unit created a split package matching a previously
        // "shifted" reactor file,
        // verify whether the reactor file's package-private externalAbiDigest also
        // matches!
        for (var rDoc : new ArrayList<>(shiftedReactorDocs)) {
            var oldR = prevEntry.reactorAbis().get(rDoc);
            var curR = nextReactorAbis.get(rDoc);
            boolean newlySplit = changedAbiByDoc.values().stream()
                    .anyMatch(a -> a.packageName().equals(curR.packageName())
                            || a.packageName().equals(oldR.packageName()));
            if (newlySplit && !oldR.externalAbiDigest().equals(curR.externalAbiDigest())) {
                shiftedReactorDocs.remove(rDoc);
                changedUpstreamReactorFqns.addAll(oldR.declaredFqns());
                changedUpstreamReactorFqns.addAll(curR.declaredFqns());
                changedUpstreamReactorSimpleNames.addAll(oldR.declaredSimpleNames());
                changedUpstreamReactorSimpleNames.addAll(curR.declaredSimpleNames());
            }
        }

        // S6: Under SOURCE_RECOVERY, reconstruct exact ExplicitImportTypeSolver state
        // from cached UnitObservations
        // + changed ASTs (zero clean files parsed!) and feed any changed recovery
        // declaration FQNs into dirty closure!
        ExplicitImportTypeSolver incrementalExplicitImports = null;
        Map<String, String> nextRecoverySignatures = Map.of();
        if (request.sourceRecovery()) {
            incrementalExplicitImports = new ExplicitImportTypeSolver();
            var allKnownSourceAndReactor = new HashSet<String>(allReactorFqns);
            for (var input : request.sources()) {
                SourceDocumentIdentity docId = input.document().identity();
                var raw = changedFullByDoc.get(docId);
                if (raw != null) {
                    var obs = incrementalExplicitImports.observeUnitWithSummary(raw.ast(), input.document().path());
                    unitRecoveryObservations.put(docId, obs);
                    var abi = changedAbiByDoc.get(docId);
                    if (abi != null)
                        allKnownSourceAndReactor.addAll(abi.declaredFqns());
                } else {
                    var cached = prevEntry.units().get(docId);
                    if (cached == null) {
                        incrementalStats = ModuleIncrementalCache.IncrementalStats.cold(0,
                                "missing-clean-recovery-obs");
                        return Optional.empty();
                    }
                    incrementalExplicitImports.replayObservation(cached.recoveryObservation());
                    unitRecoveryObservations.put(docId, cached.recoveryObservation());
                    allKnownSourceAndReactor.addAll(cached.declaredFqns());
                }
            }
            incrementalExplicitImports.materialize(
                    allKnownSourceAndReactor,
                    environment,
                    !environment.hasAnyLombokClassInDependencies());
            nextRecoverySignatures = incrementalExplicitImports.materializedSignatures();
            var prevSigs = prevEntry.recoverySignatures();
            var changedRecoveryFqns = new LinkedHashSet<String>();
            for (var se : nextRecoverySignatures.entrySet()) {
                if (!se.getValue().equals(prevSigs.get(se.getKey()))) {
                    changedRecoveryFqns.add(se.getKey());
                }
            }
            for (var se : prevSigs.entrySet()) {
                if (!nextRecoverySignatures.containsKey(se.getKey())) {
                    changedRecoveryFqns.add(se.getKey());
                }
            }
            if (!changedRecoveryFqns.isEmpty()) {
                changedUpstreamReactorFqns.addAll(changedRecoveryFqns);
                for (String rfqn : changedRecoveryFqns) {
                    int dot = rfqn.lastIndexOf('.');
                    changedUpstreamReactorSimpleNames.add(dot > 0 ? rfqn.substring(dot + 1) : rfqn);
                }
            }
        }

        var previousNodes = new LinkedHashMap<SourceDocumentIdentity, FileDependencyIndex.UnitNode>(
                prevEntry.units().size() * 2 + 1);
        for (var e : prevEntry.units().entrySet()) {
            previousNodes.put(e.getKey(), e.getValue().toIndexNode());
        }

        var closure = FileDependencyIndex.computeDirtyClosure(
                previousNodes, changedNodes, removedDocs,
                changedUpstreamReactorFqns, changedUpstreamReactorSimpleNames,
                fastArchitecture, request.sources().size(), ModuleIncrementalCache.safetyCeilingRatio());
        if (closure.exceededSafetyCeiling()) {
            incrementalStats = ModuleIncrementalCache.IncrementalStats.cold(
                    0, closure.fallbackReason().orElse("safety-ceiling"));
            return Optional.empty();
        }
        Set<SourceDocumentIdentity> dirtyDocs = new LinkedHashSet<>(closure.dirtyDocuments());
        if (request.sourceRecovery() && !changedUpstreamReactorFqns.isEmpty()) {
            // Also invalidate any clean unit whose cached recovery observation
            // imported/used a changed recovery FQN.
            for (var e : prevEntry.units().entrySet()) {
                SourceDocumentIdentity docId = e.getKey();
                if (dirtyDocs.contains(docId) || removedDocs.contains(docId))
                    continue;
                var obs = e.getValue().recoveryObservation();
                boolean touchesChanged = false;
                for (String f : obs.explicitFqns()) {
                    if (changedUpstreamReactorFqns.contains(f)) {
                        touchesChanged = true;
                        break;
                    }
                }
                if (!touchesChanged) {
                    for (String q : obs.qualifiedTypes()) {
                        if (changedUpstreamReactorFqns.contains(q)) {
                            touchesChanged = true;
                            break;
                        }
                    }
                }
                if (!touchesChanged) {
                    for (String v : obs.localSimpleToFqn().values()) {
                        if (changedUpstreamReactorFqns.contains(v)) {
                            touchesChanged = true;
                            break;
                        }
                    }
                }
                if (!touchesChanged) {
                    for (var wu : obs.wildcardUses()) {
                        for (String wp : wu.wildcardPackages()) {
                            if (changedUpstreamReactorFqns.contains(wp + "." + wu.simpleName())) {
                                touchesChanged = true;
                                break;
                            }
                        }
                        if (touchesChanged)
                            break;
                    }
                }
                if (touchesChanged)
                    dirtyDocs.add(docId);
            }
            int ceiling = request.sources().size() <= 5
                    ? Math.max(1, request.sources().size() - 1)
                    : Math.max(2,
                            (int) Math.floor(request.sources().size() * ModuleIncrementalCache.safetyCeilingRatio()));
            if (request.sources().size() > 1 && dirtyDocs.size() > ceiling) {
                incrementalStats = ModuleIncrementalCache.IncrementalStats.cold(0, "DIRTY_RATIO_EXCEEDED");
                return Optional.empty();
            }
        }

        // Parse additional impacted dependents (dirtyDocs \ changedOrAdded)
        var extraDirtyInputs = new ArrayList<SourceInput>();
        for (var docId : dirtyDocs) {
            if (!changedFullByDoc.containsKey(docId)) {
                SourceInput inp = currentSourcesByDoc.get(docId);
                if (inp != null)
                    extraDirtyInputs.add(inp);
            }
        }
        if (!extraDirtyInputs.isEmpty()) {
            int depPermits = IncrementalAndParallelSupport.acquireExtraPermits(extraDirtyInputs.size());
            IncrementalAndParallelSupport.RawParsedSource[] rawDeps;
            try {
                rawDeps = IncrementalAndParallelSupport.parseSourcesInParallel(
                        extraDirtyInputs, languageLevel(), depPermits);
            } finally {
                IncrementalAndParallelSupport.releaseExtraPermits(depPermits);
            }
            for (var raw : rawDeps) {
                if (!raw.isSuccess()) {
                    incrementalStats = ModuleIncrementalCache.IncrementalStats.cold(0, "dependent-parse-error");
                    return Optional.empty();
                }
                android.observeUnit(raw.ast());
                if (android.isAndroidUsed()) {
                    incrementalStats = ModuleIncrementalCache.IncrementalStats.cold(0, "android-detected-in-dependent");
                    return Optional.empty();
                }
                SourceDocumentIdentity docId = raw.input().document().identity();
                changedFullByDoc.put(docId, raw);
                changedAbiByDoc.put(docId,
                        SourceAbiFingerprinter.fingerprint(raw.ast(), raw.input().document().path()));
            }
        }

        // Build clean primary FQN index (0 clean files parsed!) + sourceTypeNames +
        // architecturalSimpleTypeNames + Lombok flag
        var cleanFqnToDoc = new HashMap<String, SourceDocumentIdentity>();
        boolean anyLombokInModule = false;
        for (var input : request.sources()) {
            SourceDocumentIdentity docId = input.document().identity();
            if (dirtyDocs.contains(docId)) {
                var abi = changedAbiByDoc.get(docId);
                if (abi != null) {
                    sourceTypeNames.addAll(abi.declaredFqns());
                    if (abi.hasLombokAnyAnnotation())
                        anyLombokInModule = true;
                }
            } else {
                var cached = prevEntry.units().get(docId);
                if (cached == null) {
                    incrementalStats = ModuleIncrementalCache.IncrementalStats.cold(0, "missing-clean-unit");
                    return Optional.empty();
                }
                for (String fqn : cached.declaredFqns()) {
                    cleanFqnToDoc.put(fqn, docId);
                    sourceTypeNames.add(fqn);
                }
                if (cached.hasLombokClassAnnotation())
                    anyLombokInModule = true;
            }
        }
        if (fastArchitecture) {
            // Match cold indexTypes(): populate ONLY declared type simple names (never field/method/enum/record
            // names from AbiSummary.declaredSimpleNames(), which would spuriously match local-variable receivers).
            for (String fqn : sourceTypeNames) {
                int dot = fqn.lastIndexOf('.');
                architecturalSimpleTypeNames.add(dot >= 0 ? fqn.substring(dot + 1) : fqn);
            }
            for (var rAbi : nextReactorAbis.values()) {
                for (String fqn : rAbi.declaredFqns()) {
                    int dot = fqn.lastIndexOf('.');
                    architecturalSimpleTypeNames.add(dot >= 0 ? fqn.substring(dot + 1) : fqn);
                }
            }
        }
        lombok.setHasPotentialLombokInModule(anyLombokInModule);

        // Install LazySkeletonTypeSolvers so clean files are ONLY parsed if queried by
        // SymbolSolver!
        var symSolverRef = new JavaSymbolSolver[1];
        var implicitRef = new ImplicitExtraction[1];
        var rawLazyParserRef = new JavaParser[1];
        var loadedCleanHeaders = new HashMap<SourceDocumentIdentity, Unit>();

        LazySkeletonTypeSolver lazyProject = new LazySkeletonTypeSolver(cleanFqnToDoc, (docId, publishEarly) -> {
            Unit existingHeader = loadedCleanHeaders.get(docId);
            if (existingHeader != null) {
                return new LazySkeletonTypeSolver.MaterializedUnit(
                        docId, existingHeader.input, existingHeader.ast,
                        LazySkeletonTypeSolver.indexSkeletonTypes(existingHeader.ast, this::hasLocalOwner));
            }
            SourceInput inp = currentSourcesByDoc.get(docId);
            if (inp == null)
                return null;
            if (rawLazyParserRef[0] == null)
                rawLazyParserRef[0] = IncrementalAndParallelSupport.newRawParser(languageLevel());
            var raw = IncrementalAndParallelSupport.parseSingleRaw(rawLazyParserRef[0], inp);
            if (!raw.isSuccess())
                return null;
            skeleton(raw.ast());
            raw.ast().setData(Node.SYMBOL_RESOLVER_KEY, symSolverRef[0]);
            raw.ast().setData(ExplicitImportTypeSolver.UNIT_PATH_KEY, inp.document().path());
            var hUnit = new Unit(inp, raw.ast(), raw.translated());
            loadedCleanHeaders.put(docId, hUnit);
            units.put(raw.ast(), hUnit);
            raw.ast().stream().filter(this::membershipNode).forEach(sourceNodes::add);
            var indexedMap = LazySkeletonTypeSolver.indexSkeletonTypes(raw.ast(), this::hasLocalOwner);
            var materialized = new LazySkeletonTypeSolver.MaterializedUnit(docId, inp, raw.ast(), indexedMap);
            // Publish indexed types BEFORE materializeHeaderDeclarationsIsolated so
            // self-referential generics
            // (e.g. class B<T extends B<T>>) or Lombok @Builder resolve immediately without
            // StackOverflowError!
            publishEarly.accept(materialized);
            materializeHeaderDeclarationsIsolated(hUnit, implicitRef[0]);
            return materialized;
        });

        // Create lazy reactor solvers bound to symSolverRef
        var lazyReactorSolvers = new HashMap<Integer, com.github.javaparser.resolution.TypeSolver>();
        int syntaxKey = request.plan().syntaxLevel().orElse(21);
        for (var slotEntry : reactorFqnToDocBySlot.entrySet()) {
            lazyReactorSolvers.put(slotEntry.getKey(), new LazySkeletonTypeSolver(slotEntry.getValue(), rDoc -> {
                var parsedChange = parsedReactorChanges.get(rDoc);
                if (parsedChange != null) {
                    CompilationUnit cloned = cloneWithRangesAndSolver(parsedChange.skeletonAst(), symSolverRef[0]);
                    Unit rUnit = new Unit(parsedChange.input(), cloned, parsedChange.translated());
                    units.put(cloned, rUnit);
                    cloned.stream().filter(this::membershipNode).forEach(reactorNodes::add);
                    return new LazySkeletonTypeSolver.MaterializedUnit(
                            rDoc, rUnit.input, cloned,
                            LazySkeletonTypeSolver.indexSkeletonTypes(cloned, this::hasLocalOwner));
                }
                SourceInput rIn = currentReactorInputByDoc.get(rDoc);
                if (rIn == null)
                    return null;
                String ktName = kotlinCacheFileName(rIn.document().path());
                var cacheKey = new SkeletonCacheKey(ktName.isEmpty() ? syntaxKey : 0, rIn.document().contentDigest(),
                        ktName);
                var cachedSkel = REACTOR_SKELETON_CACHE.get(cacheKey);
                CompilationUnit skelAst;
                UnicodeSource translated;
                if (cachedSkel != null) {
                    if (!ktName.isEmpty())
                        KOTLIN_SKELETON_CACHE_HITS.incrementAndGet();
                    skelAst = cloneWithRangesAndSolver(cachedSkel.template(), symSolverRef[0]);
                    translated = cachedSkel.translated();
                } else {
                    if (rawLazyParserRef[0] == null)
                        rawLazyParserRef[0] = IncrementalAndParallelSupport.newRawParser(languageLevel());
                    var raw = IncrementalAndParallelSupport.parseSingleRaw(rawLazyParserRef[0], rIn);
                    if (!raw.isSuccess())
                        return null;
                    skeleton(raw.ast());
                    CompilationUnit template = cloneWithRangesAndSolver(raw.ast(), null);
                    long weight = (long) raw.translated().text.length() * 4 + 1024L;
                    REACTOR_SKELETON_CACHE.put(cacheKey, new CachedReactorSkeleton(raw.translated(), template, weight));
                    skelAst = raw.ast();
                    skelAst.setData(Node.SYMBOL_RESOLVER_KEY, symSolverRef[0]);
                    translated = raw.translated();
                }
                Unit rUnit = new Unit(rIn, skelAst, translated);
                units.put(skelAst, rUnit);
                skelAst.stream().filter(this::membershipNode).forEach(reactorNodes::add);
                return new LazySkeletonTypeSolver.MaterializedUnit(
                        rDoc, rIn, skelAst, LazySkeletonTypeSolver.indexSkeletonTypes(skelAst, this::hasLocalOwner));
            }));
        }

        activeSolver = environment.buildIsolatedWorkerSolver(
                lazyProject, lazyReactorSolvers, null, null, incrementalExplicitImports);
        activeExplicitImports = incrementalExplicitImports != null ? incrementalExplicitImports
                : environment.explicitImports;
        symSolverRef[0] = new JavaSymbolSolver(activeSolver);
        environment.installLazySolvers(lazyProject, lazyReactorSolvers, allReactorFqns, duplicateReactorFqns);
        nativeMembers = new NativeMemberExtraction(this, request, environment);
        implicitRef[0] = new ImplicitExtraction(this);

        // Register dirty primary units eagerly into lazyProject (header skeleton +
        // retained full AST)
        var dirtyHeaders = new LinkedHashMap<SourceDocumentIdentity, Unit>();
        var dirtyFulls = new LinkedHashMap<SourceDocumentIdentity, Unit>();
        for (var input : request.sources()) {
            SourceDocumentIdentity docId = input.document().identity();
            if (!dirtyDocs.contains(docId))
                continue;
            var raw = changedFullByDoc.get(docId);
            if (raw == null)
                continue;
            CompilationUnit fullAst = raw.ast();
            fullAst.setData(Node.SYMBOL_RESOLVER_KEY, symSolverRef[0]);
            fullAst.setData(ExplicitImportTypeSolver.UNIT_PATH_KEY, input.document().path());
            CompilationUnit headerAst = extractSkeletonClone(fullAst, symSolverRef[0]);
            headerAst.setData(ExplicitImportTypeSolver.UNIT_PATH_KEY, input.document().path());
            Unit headerUnit = new Unit(input, headerAst, raw.translated());
            Unit fullUnit = new Unit(input, fullAst, raw.translated());
            units.put(headerAst, headerUnit);
            headerAst.stream().filter(this::membershipNode).forEach(sourceNodes::add);
            for (var e : LazySkeletonTypeSolver.indexSkeletonTypes(headerAst, this::hasLocalOwner).entrySet()) {
                lazyProject.registerEagerDeclaration(e.getKey(), e.getValue());
            }
            dirtyHeaders.put(docId, headerUnit);
            dirtyFulls.put(docId, fullUnit);
        }

        // Pass 1 declarations + implicit declarations for dirty units ONLY!
        for (var hUnit : dirtyHeaders.values()) {
            materializeHeaderDeclarationsIsolated(hUnit, implicitRef[0]);
        }

        // Extract fresh reactor DeclarationRecords for O(k) span rebinding on any
        // shifted reactor docs
        var reboundCounter = new java.util.concurrent.atomic.AtomicInteger(0);
        Map<EntityIdentity, DeclarationRecord> freshReactorDecls = Map.of();
        if (!shiftedReactorDocs.isEmpty()) {
            freshReactorDecls = extractShiftedReactorDeclarations(shiftedReactorDocs, parsedReactorChanges,
                    symSolverRef[0]);
            if (freshReactorDecls == null) {
                incrementalStats = ModuleIncrementalCache.IncrementalStats.cold(0, "shifted-reactor-extract-failed");
                return Optional.empty();
            }
        }

        int totalFiles = request.sources().size();
        int cleanCount = totalFiles - dirtyHeaders.size();
        long incStartNanos = System.nanoTime();
        progress.onModuleStarted(request.module().value(), request.sourceSet().name(), totalFiles);
        if (cleanCount > 0) {
            progress.onFilesCompleted(request.module().value(), cleanCount, cleanCount, totalFiles, true);
        }

        // Pass 2 extraction for dirty units ONLY!
        var dirtyShapes = new ArrayList<TypeDeclarationRecord>();
        var dirtyMembers = new ArrayList<MemberDeclarationRecord>();
        var dirtyCandidates = new ArrayList<LombokConstructorCandidate>();
        var extractedDirtyEntries = new LinkedHashMap<SourceDocumentIdentity, ModuleIncrementalCache.UnitEntry>();
        int doneBatch = 0;
        int completedSoFar = cleanCount;
        for (var entry : dirtyHeaders.entrySet()) {
            SourceDocumentIdentity docId = entry.getKey();
            Unit header = entry.getValue();
            Unit full = dirtyFulls.get(docId);
            units.put(full.ast, full);
            var uEntry = extractSingleUnit(
                    header, full, implicitRef[0], changedAbiByDoc.get(docId),
                    dirtyShapes, dirtyMembers, dirtyCandidates);
            extractedDirtyEntries.put(docId, uEntry);
            releaseBody(full);
            doneBatch++;
            completedSoFar++;
            if (doneBatch >= 8) {
                progress.onFilesCompleted(request.module().value(), doneBatch, completedSoFar, totalFiles, false);
                doneBatch = 0;
            }
        }
        if (doneBatch > 0) {
            progress.onFilesCompleted(request.module().value(), doneBatch, completedSoFar, totalFiles, false);
        }

        if (!duplicateDeclarations.isEmpty() || !duplicateTypeWarnings.isEmpty()) {
            incrementalStats = ModuleIncrementalCache.IncrementalStats.cold(0, "dirty-extraction-collision");
            return Optional.empty();
        }

        // Assemble final ordered UnitEntries (clean reused + O(k) reactor rebinding +
        // dirty fresh)
        var nextUnitEntries = new LinkedHashMap<SourceDocumentIdentity, ModuleIncrementalCache.UnitEntry>(
                request.sources().size() * 2 + 1);
        for (var input : request.sources()) {
            SourceDocumentIdentity docId = input.document().identity();
            var fresh = extractedDirtyEntries.get(docId);
            if (fresh != null) {
                nextUnitEntries.put(docId, fresh);
            } else {
                var cached = prevEntry.units().get(docId);
                if (cached == null) {
                    incrementalStats = ModuleIncrementalCache.IncrementalStats.cold(0, "missing-clean-unit");
                    return Optional.empty();
                }
                if (!shiftedReactorDocs.isEmpty()) {
                    var rebound = IncrementalAndParallelSupport.rebindReactorDeclarations(
                            cached.reactorDeclarations(), shiftedReactorDocs, freshReactorDecls, reboundCounter);
                    if (rebound == null) {
                        incrementalStats = ModuleIncrementalCache.IncrementalStats.cold(0, "reactor-rebind-mismatch");
                        return Optional.empty();
                    }
                    if (rebound != cached.reactorDeclarations()) {
                        cached = withReactorDeclarations(cached, rebound);
                    }
                }
                nextUnitEntries.put(docId, cached);
            }
        }

        var assembled = IncrementalAndParallelSupport.assembleFromUnitEntries(
                request, VERSION, fastArchitecture, false,
                nextUnitEntries.values(), reactorDocOrder, diagnostics);
        if (assembled.isEmpty()) {
            incrementalStats = ModuleIncrementalCache.IncrementalStats.cold(0, "assembly-invariant-mismatch");
            return Optional.empty();
        }

        ModuleIncrementalCache.put(new ModuleIncrementalCache.ModuleEntry(
                moduleContextKey, Set.of(), Map.copyOf(nextUnitEntries), Map.copyOf(nextReactorAbis), false,
                nextRecoverySignatures));
        progress.onModuleCompleted(
                request.module().value(),
                request.sourceSet().name(),
                totalFiles,
                sourceTypeNames.size(),
                (System.nanoTime() - incStartNanos) / 1_000_000L,
                true);

        int lazyParsed = lazyProject.materializedDocumentCount();
        for (var rs : lazyReactorSolvers.values()) {
            if (rs instanceof LazySkeletonTypeSolver lrs) {
                lazyParsed += lrs.materializedDocumentCount();
            }
        }
        this.reextractedUnitCount = dirtyHeaders.size();
        this.incrementalStats = new ModuleIncrementalCache.IncrementalStats(
                true,
                closure.abiEarlyCutoffCount(),
                closure.impactedDependentsCount(),
                dirtyHeaders.size(),
                cleanCount,
                lazyParsed,
                reboundCounter.get(),
                Optional.empty());
        return assembled;
    }

    private Map<Integer, com.github.javaparser.resolution.TypeSolver> buildLazyReactorSolvers(
            Map<Integer, Map<String, SourceDocumentIdentity>> reactorFqnToDocBySlot,
            Map<SourceDocumentIdentity, SourceInput> currentReactorInputByDoc,
            Map<SourceDocumentIdentity, ParsedReactorChange> parsedReactorChanges,
            JavaSymbolSolver[] symSolverRef) {
        var solvers = new HashMap<Integer, com.github.javaparser.resolution.TypeSolver>();
        var rawParserRef = new JavaParser[1];
        for (var slotEntry : reactorFqnToDocBySlot.entrySet()) {
            solvers.put(slotEntry.getKey(), new LazySkeletonTypeSolver(slotEntry.getValue(), rDoc -> {
                var parsedChange = parsedReactorChanges != null ? parsedReactorChanges.get(rDoc) : null;
                if (parsedChange != null) {
                    CompilationUnit cloned = cloneWithRangesAndSolver(parsedChange.skeletonAst(), symSolverRef[0]);
                    Unit rUnit = new Unit(parsedChange.input(), cloned, parsedChange.translated());
                    units.put(cloned, rUnit);
                    cloned.stream().filter(this::membershipNode).forEach(reactorNodes::add);
                    return new LazySkeletonTypeSolver.MaterializedUnit(
                            rDoc, rUnit.input, cloned,
                            LazySkeletonTypeSolver.indexSkeletonTypes(cloned, this::hasLocalOwner));
                }
                SourceInput rIn = currentReactorInputByDoc.get(rDoc);
                if (rIn == null)
                    return null;
                if (rawParserRef[0] == null)
                    rawParserRef[0] = IncrementalAndParallelSupport.newRawParser(languageLevel());
                var raw = IncrementalAndParallelSupport.parseSingleRaw(rawParserRef[0], rIn);
                if (!raw.isSuccess())
                    return null;
                skeleton(raw.ast());
                if (symSolverRef[0] != null)
                    raw.ast().setData(Node.SYMBOL_RESOLVER_KEY, symSolverRef[0]);
                Unit rUnit = new Unit(rIn, raw.ast(), raw.translated());
                units.put(raw.ast(), rUnit);
                raw.ast().stream().filter(this::membershipNode).forEach(reactorNodes::add);
                return new LazySkeletonTypeSolver.MaterializedUnit(
                        rDoc, rIn, raw.ast(),
                        LazySkeletonTypeSolver.indexSkeletonTypes(raw.ast(), this::hasLocalOwner));
            }));
        }
        return solvers;
    }

    private Map<EntityIdentity, DeclarationRecord> extractShiftedReactorDeclarations(
            Set<SourceDocumentIdentity> shiftedReactorDocs,
            Map<SourceDocumentIdentity, ParsedReactorChange> parsedReactorChanges,
            JavaSymbolSolver symSolver) {
        var fresh = new HashMap<EntityIdentity, DeclarationRecord>();
        var savedDecls = activeUnitDecls;
        var savedDerived = activeUnitDerived;
        try {
            activeUnitDecls = null;
            activeUnitDerived = null;
            for (var rDoc : shiftedReactorDocs) {
                ParsedReactorChange change = parsedReactorChanges.get(rDoc);
                if (change == null)
                    return null;
                CompilationUnit cloned = cloneWithRangesAndSolver(change.skeletonAst(), symSolver);
                Unit rUnit = new Unit(change.input(), cloned, change.translated());
                units.put(cloned, rUnit);
                cloned.stream().filter(this::membershipNode).forEach(reactorNodes::add);
                for (var node : cloned.stream().filter(this::isDeclaration).toList()) {
                    try {
                        Entity e = entity(node);
                        DeclarationRecord rec = declarations.get(e.identity());
                        if (rec != null)
                            fresh.put(e.identity(), rec);
                    } catch (RuntimeException | LinkageError | StackOverflowError ignored) {
                    }
                }
            }
            return fresh;
        } finally {
            activeUnitDecls = savedDecls;
            activeUnitDerived = savedDerived;
        }
    }

    private static ModuleIncrementalCache.UnitEntry withReactorDeclarations(
            ModuleIncrementalCache.UnitEntry u,
            List<DeclarationRecord> reboundReactorDecls) {
        return new ModuleIncrementalCache.UnitEntry(
                u.document(), u.path(), u.contentDigest(),
                u.externalAbiDigest(), u.publicCrossModuleAbiDigest(),
                u.springRelevantDigest(), u.staticMembersDigest(),
                u.packageName(), u.declaredFqns(), u.declaredSimpleNames(),
                u.supertypeNames(), u.explicitImportedFqns(),
                u.wildcardImportedPackages(), u.staticWildcardTypes(),
                u.referencedFqns(), u.referencedSimpleNames(),
                u.hasLombokClassAnnotation(), u.hadUnresolvedOrError(),
                u.ownDeclarations(), reboundReactorDecls, u.externalDeclarations(),
                u.referencedPrimaryPeerIds(), u.occurrences(), u.observations(),
                u.types(), u.annotations(), u.derived(), u.shapes(), u.members(),
                u.constructorCandidates(), u.httpClientCalls(), u.unitDiagnostics(), u.recoveryObservation());
    }

    private FrontendResult buildFrontendResult(
            List<TypeDeclarationRecord> shapes,
            List<MemberDeclarationRecord> members,
            List<LombokConstructorCandidate> constructorCandidates) {
        var outcomesByDoc = new LinkedHashMap<SourceDocumentIdentity, SourceOutcome>();
        for (var outcome : rejected) {
            outcomesByDoc.merge(outcome.document(), outcome, Extraction::mergeOutcome);
        }
        for (var unit : orderedUnits) {
            var outcome = new SourceOutcome(unit.input.document().identity(),
                    unit.diagnostics.isEmpty() ? SourceOutcome.State.PROCESSED : SourceOutcome.State.PARTIAL,
                    List.copyOf(unit.diagnostics));
            outcomesByDoc.merge(outcome.document(), outcome, Extraction::mergeOutcome);
        }
        for (var reactor : reactorUnits) {
            Unit unit = reactor.unit();
            var outcome = new SourceOutcome(unit.input.document().identity(),
                    unit.diagnostics.isEmpty() ? SourceOutcome.State.PROCESSED : SourceOutcome.State.PARTIAL,
                    List.copyOf(unit.diagnostics));
            outcomesByDoc.merge(outcome.document(), outcome, Extraction::mergeOutcome);
        }
        var outcomes = new ArrayList<>(outcomesByDoc.values());
        var seenObs = new HashSet<ObservationRecord>(Math.max(16, observations.size() * 2));
        var distinctObservations = new ArrayList<ObservationRecord>(observations.size());
        for (var obs : observations) {
            if (seenObs.add(obs))
                distinctObservations.add(obs);
        }
        var obsCountByCategory = new HashMap<RelationshipKind, Long>();
        for (var obs : distinctObservations) {
            obsCountByCategory.merge(obs.category(), 1L, Long::sum);
        }
        var occCountByCategory = new HashMap<RelationshipKind, Long>();
        for (var occ : occurrences.values()) {
            occCountByCategory.merge(occ.relationship().kind(), 1L, Long::sum);
        }
        var coverage = FrontendRequest.CATEGORIES.stream().map(c -> {
            var kind = new RelationshipKind("java." + c);
            long attempted = obsCountByCategory.getOrDefault(kind, 0L);
            long emitted = occCountByCategory.getOrDefault(kind, 0L);
            return new CategoryCoverage(kind,
                    !fastArchitecture && c.equals("calls") ? CategoryCoverage.Support.IMPLEMENTED
                            : CategoryCoverage.Support.PARTIAL,
                    attempted, emitted, attempted - emitted);
        }).toList();
        var distinctTypes = new ArrayList<TypeUseRecord>(new TreeSet<>(types));
        var distinctAnnotations = new ArrayList<AnnotationUseRecord>(new TreeSet<>(annotations));
        var shapesByType = new LinkedHashMap<EntityIdentity, TypeDeclarationRecord>();
        for (var s : shapes)
            shapesByType.putIfAbsent(s.type(), s);
        var distinctShapes = new ArrayList<TypeDeclarationRecord>(new TreeSet<>(shapesByType.values()));
        var membersById = new LinkedHashMap<EntityIdentity, MemberDeclarationRecord>();
        for (var m : members)
            membersById.putIfAbsent(m.member(), m);
        var distinctMembers = new ArrayList<MemberDeclarationRecord>(new TreeSet<>(membersById.values()));
        var distinctCandidates = new ArrayList<LombokConstructorCandidate>(new TreeSet<>(constructorCandidates));
        var distinctHttp = new ArrayList<HttpClientCallRecord>(new TreeSet<>(httpClientCalls));
        return new FrontendResult(request.manifest().identity(), VERSION,
                !fastArchitecture && !request.sourceRecovery() && !reactorResolutionDegraded
                        && outcomes.stream().allMatch(s -> s.state() == SourceOutcome.State.PROCESSED)
                                ? FrontendResult.State.COMPLETED
                                : FrontendResult.State.PARTIAL,
                declarations.values().stream().filter(d -> !duplicateDeclarations.contains(d.entity().identity()))
                        .toList(),
                List.copyOf(occurrences.values()), distinctObservations, outcomes, coverage, List.copyOf(diagnostics),
                distinctTypes, distinctAnnotations,
                List.copyOf(derived), distinctShapes, distinctMembers, distinctCandidates, distinctHttp);
    }

    private Set<String> collectUnitBoundaryMemberNames(CompilationUnit ast) {
        var names = new HashSet<String>();
        for (var field : ast.findAll(FieldDeclaration.class)) {
            if (field.isStatic() && field.isFinal())
                continue;
            for (var v : field.getVariables())
                names.add(v.getNameAsString());
        }
        for (var record : ast.findAll(RecordDeclaration.class)) {
            for (var p : record.getParameters())
                names.add(p.getNameAsString());
        }
        return names;
    }

    private boolean isInSpringConfigContext(Node node) {
        for (Node cur = node.getParentNode().orElse(null); cur != null; cur = cur.getParentNode().orElse(null)) {
            if (cur instanceof MethodDeclaration m) {
                for (var a : m.getAnnotations()) {
                    if (SPRING_CONFIG_ANNOTATIONS.contains(a.getName().getIdentifier()))
                        return true;
                }
            } else if (cur instanceof ClassOrInterfaceDeclaration c) {
                for (var a : c.getAnnotations()) {
                    if (SPRING_CONFIG_ANNOTATIONS.contains(a.getName().getIdentifier()))
                        return true;
                }
            }
        }
        return false;
    }

    private boolean isArchitecturalCall(MethodCallExpr call, Set<String> unitBoundaryMemberNames) {
        String method = call.getNameAsString();
        if (HTTP_CLIENT_METHODS.contains(method)
                || WEB_CLIENT_VERB_METHODS.contains(method)
                || SPRING_BOUNDARY_METHODS.contains(method)) {
            return true;
        }
        if (isInSpringConfigContext(call))
            return true;
        if (call.getScope().isEmpty()) {
            return true;
        }
        Expression root = call.getScope().orElseThrow();
        while (root instanceof FieldAccessExpr fa) {
            if (fa.getScope() instanceof ThisExpr || fa.getScope() instanceof SuperExpr) {
                return true;
            }
            if (architecturalSimpleTypeNames.contains(fa.getNameAsString())
                    || unitBoundaryMemberNames.contains(fa.getNameAsString())) {
                return true;
            }
            root = fa.getScope();
        }
        if (root instanceof ThisExpr || root instanceof SuperExpr)
            return true;
        if (root instanceof NameExpr ne) {
            String id = ne.getNameAsString();
            return unitBoundaryMemberNames.contains(id) || architecturalSimpleTypeNames.contains(id);
        }
        if (root instanceof MethodCallExpr nested) {
            return isArchitecturalCall(nested, unitBoundaryMemberNames);
        }
        if (root instanceof ObjectCreationExpr oc) {
            return isArchitecturalCreation(oc);
        }
        return false;
    }

    private boolean isArchitecturalCreation(ObjectCreationExpr creation) {
        if (creation.getAnonymousClassBody().isPresent())
            return true;
        if (architecturalSimpleTypeNames.contains(creation.getType().getNameAsString()))
            return true;
        return isInSpringConfigContext(creation);
    }

    private List<HttpClientCallRecord> httpClientCalls(Unit unit) {
        var candidates = new ArrayList<MethodCallExpr>();
        for (var call : unit.ast.findAll(MethodCallExpr.class)) {
            if (HTTP_CLIENT_METHODS.contains(call.getNameAsString())) {
                candidates.add(call);
            }
        }
        if (candidates.isEmpty())
            return List.of();
        var bindings = resolvedCallsByDoc.getOrDefault(unit.input.document().identity(), Map.of());
        if (bindings.isEmpty())
            return List.of();
        var result = new ArrayList<HttpClientCallRecord>();
        for (var call : candidates) {
            String method = call.getNameAsString();
            try {
                var selected = call.resolve();
                String type = selected.declaringType().getQualifiedName();
                boolean rest = Set.of(
                        "org.springframework.web.client.RestTemplate",
                        "org.springframework.web.client.RestOperations",
                        "org.springframework.security.oauth2.client.OAuth2RestTemplate",
                        "org.springframework.security.oauth2.client.OAuth2RestOperations").contains(type);
                boolean reactive=method.equals("uri")&&(type.startsWith("org.springframework.web.reactive.function.client.WebClient.")
                        ||type.startsWith("org.springframework.web.client.RestClient."));
                boolean jaxRsRequest = method.equals("request")
                        && Set.of("javax.ws.rs.client.WebTarget", "jakarta.ws.rs.client.WebTarget").contains(type);
                boolean discovery=(method.equals("getInstances")&&type.equals("org.springframework.cloud.client.discovery.DiscoveryClient"))
                        ||(method.equals("choose")&&Set.of(
                                "org.springframework.cloud.client.loadbalancer.LoadBalancerClient",
                                "org.springframework.cloud.client.loadbalancer.ServiceInstanceChooser").contains(type));
                if (!rest && !reactive && !discovery && !jaxRsRequest
                        || typeEntity(selected.declaringType()).origin() != EntityOrigin.DEPENDENCY)
                    continue;
                var bound = callable(selected, call);
                var span = source(call).span(call);
                var caller = owner(call);
                var binding = bindings.get(span);
                if (binding == null || !(binding.relationship().target() instanceof RelationshipTarget.Resolved r)
                        || !r.target().equals(bound.identity()))
                    continue;
                if (jaxRsRequest) {
                    String jaxRsUri = resolveJaxRsWebTargetUri(call);
                    String jaxRsVerb = jaxRsInvocationVerb(call);
                    result.add(new HttpClientCallRecord(caller.identity(), bound.identity(), span,
                            jaxRsUri, jaxRsVerb.isEmpty() ? List.of() : List.of(jaxRsVerb)));
                    continue;
                }
                String rawArg = call.getArguments().isEmpty() ? "" : HttpClientConstants.uri(call.getArgument(0));
                if (discovery) {
                    String svc = rawArg.trim();
                    if (!svc.isEmpty())
                        result.add(new HttpClientCallRecord(caller.identity(), bound.identity(), span,
                                "lb://" + svc + "/", List.of()));
                    continue;
                }
                String baseUrl = reactive ? fluentChainBaseUrl(call) : "";
                String uri = (!baseUrl.isEmpty() && rawArg.startsWith("/")) ? combineBaseUri(baseUrl, rawArg) : rawArg;
                String verb = method.startsWith("getFor") ? "GET"
                        : method.startsWith("postFor") ? "POST"
                                : method.equals("put") ? "PUT"
                                        : method.equals("patchForObject") ? "PATCH"
                                                : method.equals("delete") ? "DELETE"
                                                        : method.equals("headForHeaders") ? "HEAD"
                                                                : method.equals("optionsForAllow") ? "OPTIONS" : "";
                if (reactive)
                    verb = reactiveVerb(call, bindings);
                result.add(new HttpClientCallRecord(caller.identity(), bound.identity(), span, uri,
                        verb.isEmpty() ? List.of() : List.of(verb)));
            } catch (RuntimeException | LinkageError | StackOverflowError unavailable) {
                /* The original attribution ledger retains unavailable bindings. */}
        }
        return result;
    }

    private String resolveJaxRsWebTargetUri(MethodCallExpr requestCall) {
        var pathSegments = new ArrayList<String>();
        String baseUri = "";
        Expression cur = requestCall.getScope().orElse(null);
        var visitedVars = new HashSet<String>();
        int steps = 0;
        while (cur != null && steps++ < 16) {
            if (cur instanceof MethodCallExpr mc) {
                String name = mc.getNameAsString();
                if (name.equals("path") && !mc.getArguments().isEmpty()) {
                    String seg = HttpClientConstants.uri(mc.getArgument(0)).trim();
                    pathSegments.addFirst(seg.isEmpty() ? "{param}" : seg);
                    cur = mc.getScope().orElse(null);
                    continue;
                }
                if (name.equals("target") && !mc.getArguments().isEmpty()) {
                    baseUri = HttpClientConstants.uri(mc.getArgument(0)).trim();
                    break;
                }
                cur = mc.getScope().orElse(null);
                continue;
            }
            if (cur instanceof NameExpr n && visitedVars.add(n.getNameAsString())) {
                cur = findTargetInitializer(requestCall, n.getNameAsString()).orElse(null);
                continue;
            }
            if (cur instanceof FieldAccessExpr f && f.getScope() instanceof ThisExpr
                    && visitedVars.add(f.getNameAsString())) {
                cur = findTargetInitializer(requestCall, f.getNameAsString()).orElse(null);
                continue;
            }
            break;
        }
        if (baseUri.isEmpty() && pathSegments.isEmpty()) return "";
        String uri = baseUri;
        for (String seg : pathSegments) {
            String cleanSeg = seg.startsWith("/") ? seg : "/" + seg;
            uri = uri.isEmpty() ? cleanSeg : combineBaseUri(uri, cleanSeg);
        }
        return uri;
    }

    private static Optional<Expression> findTargetInitializer(Node from, String varName) {
        Node ancestor = from.getParentNode().orElse(null);
        while (ancestor != null) {
            if (ancestor instanceof CallableDeclaration<?> callable) {
                for (VariableDeclarator vd : callable.findAll(VariableDeclarator.class)) {
                    if (vd.getNameAsString().equals(varName) && vd.getInitializer().isPresent()
                            && from.getRange().map(fr -> vd.getRange().map(vr -> vr.isBefore(fr.begin)).orElse(true))
                                    .orElse(true)) {
                        return vd.getInitializer();
                    }
                }
            } else if (ancestor instanceof ClassOrInterfaceDeclaration cls) {
                for (FieldDeclaration fd : cls.getFields()) {
                    for (VariableDeclarator vd : fd.getVariables()) {
                        if (vd.getNameAsString().equals(varName) && vd.getInitializer().isPresent()) {
                            return vd.getInitializer();
                        }
                    }
                }
            }
            ancestor = ancestor.getParentNode().orElse(null);
        }
        return Optional.empty();
    }

    private static String jaxRsInvocationVerb(MethodCallExpr requestCall) {
        Node cur = requestCall;
        for (int depth = 0; depth < 6; depth++) {
            Node parent = cur.getParentNode().orElse(null);
            if (!(parent instanceof MethodCallExpr parentCall)) break;
            Node expectedScope = cur;
            if (parentCall.getScope().filter(s -> s == expectedScope).isEmpty()) break;
            String n = parentCall.getNameAsString();
            if (WEB_CLIENT_VERB_METHODS.contains(n)) {
                return n.toUpperCase(Locale.ROOT);
            }
            if (Set.of("buildGet", "buildPost", "buildPut", "buildDelete").contains(n)) {
                return n.substring(5).toUpperCase(Locale.ROOT);
            }
            if (n.equals("method") && !parentCall.getArguments().isEmpty()) {
                String v = HttpClientConstants.uri(parentCall.getArgument(0)).trim().toUpperCase(Locale.ROOT);
                if (Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").contains(v)) {
                    return v;
                }
            }
            cur = parentCall;
        }
        return "";
    }

    private String fluentChainBaseUrl(MethodCallExpr uriCall) {
        Expression scope = uriCall.getScope().orElse(null);
        while (scope instanceof MethodCallExpr call) {
            if (call.getNameAsString().equals("baseUrl") && !call.getArguments().isEmpty()) {
                String base = HttpClientConstants.uri(call.getArgument(0)).trim();
                if (!base.isEmpty())
                    return base;
            }
            scope = call.getScope().orElse(null);
        }
        return "";
    }

    private static String combineBaseUri(String base, String path) {
        String cleanBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return path.startsWith("/") ? cleanBase + path : cleanBase + "/" + path;
    }

    private static SourceOutcome mergeOutcome(SourceOutcome a, SourceOutcome b) {
        var mergedDiagnostics = new TreeSet<>(a.diagnostics());
        mergedDiagnostics.addAll(b.diagnostics());
        SourceOutcome.State mergedState;
        if (a.state() == SourceOutcome.State.ERROR || b.state() == SourceOutcome.State.ERROR) {
            mergedState = SourceOutcome.State.ERROR;
        } else if (a.state() == SourceOutcome.State.PARTIAL
                || b.state() == SourceOutcome.State.PARTIAL
                || !mergedDiagnostics.isEmpty()) {
            mergedState = SourceOutcome.State.PARTIAL;
        } else {
            mergedState = SourceOutcome.State.PROCESSED;
        }
        return new SourceOutcome(a.document(), mergedState, List.copyOf(mergedDiagnostics));
    }

    private String reactiveVerb(MethodCallExpr uri, Map<SourceSpan, RelationshipOccurrence> bindings) {
        Expression scope = uri.getScope().orElse(null);
        while (scope instanceof MethodCallExpr call) {
            if (Set.of("get", "post", "put", "patch", "delete", "head", "options").contains(call.getNameAsString())) {
                var selected = call.resolve();
                var binding = bindings.get(source(call).span(call));
                String declaring = selected.declaringType().getQualifiedName();
                if (Set.of("org.springframework.web.reactive.function.client.WebClient",
                        "org.springframework.web.client.RestClient").contains(declaring)
                        && typeEntity(selected.declaringType()).origin() == EntityOrigin.DEPENDENCY && binding != null
                        && binding.relationship().target() instanceof RelationshipTarget.Resolved target
                        && target.target().equals(callable(selected, call).identity()))
                    return call.getNameAsString().toUpperCase(Locale.ROOT);
            }
            scope = call.getScope().orElse(null);
        }
        return "";
    }

    private void releaseBody(Unit unit) {
        unit.ast.walk(node -> {
            sourceNodes.remove(node);
            nodes.remove(node);
            names.remove(node);
        });
        units.remove(unit.ast);
        recoveryCallInputs.clear();
        lombok.releaseBody(unit.ast);
    }

    long retainedBodyStatements() {
        return units.keySet().stream()
                .flatMap(ast -> ast.findAll(com.github.javaparser.ast.stmt.Statement.class).stream()).count();
    }

    long retainedCalls() {
        return units.keySet().stream().mapToLong(ast -> ast.findAll(MethodCallExpr.class).size()).sum();
    }

    private List<MemberDeclarationRecord> memberDeclarations(Unit unit) {
        var result = new ArrayList<MemberDeclarationRecord>();
        for (var node : unit.ast.stream().toList()) {
            var entity = nodes.get(node);
            if (entity == null || duplicateDeclarations.contains(entity.identity()))
                continue;
            if (node instanceof VariableDeclarator variable
                    && variable.getParentNode().orElse(null) instanceof FieldDeclaration field) {
                Node parentType = field.getParentNode().orElse(null);
                boolean implicitStatic = parentType instanceof AnnotationDeclaration
                        || parentType instanceof ClassOrInterfaceDeclaration c
                                && (c.isInterface() || lombok.isUtilityClass(c));
                result.add(
                        new MemberDeclarationRecord(entity.identity(), semanticIdentifier(variable.getNameAsString()),
                                field.isStatic() || implicitStatic, false, false));
            } else if (node instanceof MethodDeclaration method) {
                boolean utilityStatic = method.getParentNode().orElse(null) instanceof ClassOrInterfaceDeclaration c
                        && lombok.isUtilityClass(c);
                result.add(new MemberDeclarationRecord(entity.identity(), semanticIdentifier(method.getNameAsString()),
                        method.isStatic() || utilityStatic, method.isAbstract() || method.getBody().isEmpty(),
                        !method.getTypeParameters().isEmpty()));
            }
        }
        return result;
    }

    private List<TypeDeclarationRecord> typeDeclarations(Unit unit) {
        var result = new ArrayList<TypeDeclarationRecord>();
        for (var type : unit.ast.findAll(TypeDeclaration.class)) {
            var entity = nodes.get(type);
            if (entity == null || duplicateDeclarations.contains(entity.identity()))
                continue;
            TypeDeclarationRecord.Kind kind = type instanceof AnnotationDeclaration
                    ? TypeDeclarationRecord.Kind.ANNOTATION
                    : type instanceof RecordDeclaration ? TypeDeclarationRecord.Kind.RECORD
                            : type instanceof EnumDeclaration ? TypeDeclarationRecord.Kind.ENUM
                                    : type instanceof ClassOrInterfaceDeclaration c && c.isInterface()
                                            ? TypeDeclarationRecord.Kind.INTERFACE
                                            : TypeDeclarationRecord.Kind.CLASS;
            Node parent = type.getParentNode().orElse(null);
            boolean member = parent instanceof TypeDeclaration<?>;
            boolean implicitStatic = kind != TypeDeclarationRecord.Kind.CLASS || parent instanceof AnnotationDeclaration
                    || parent instanceof ClassOrInterfaceDeclaration c && c.isInterface();
            boolean independent = parent instanceof CompilationUnit || member && (type.isStatic() || implicitStatic);
            boolean abstractType = type instanceof ClassOrInterfaceDeclaration c && (c.isAbstract() || c.isInterface())
                    || kind == TypeDeclarationRecord.Kind.ANNOTATION;
            result.add(new TypeDeclarationRecord(entity.identity(), kind, abstractType, independent));
        }
        return result;
    }

    private static long totalPrimaryChars(List<SourceInput> sources) {
        long total = 0L;
        for (var s : sources)
            total += s.text().length();
        return total;
    }

    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, List<com.github.javaparser.metamodel.PropertyMetaModel>> NODE_PROPERTIES_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private static List<com.github.javaparser.metamodel.PropertyMetaModel> nodeProperties(Node node) {
        return NODE_PROPERTIES_CACHE.computeIfAbsent(node.getClass(),
                cls -> node.getMetaModel().getAllPropertyMetaModels().stream()
                        .filter(pm -> pm.isNode() || pm.isNodeList())
                        .toList());
    }

    private static void copyRanges(Node src, Node dst) {
        if (src == null || dst == null)
            return;
        src.getRange().ifPresent(dst::setRange);
        for (var pm : nodeProperties(src)) {
            Object sv = pm.getValue(src);
            Object dv = pm.getValue(dst);
            if (sv instanceof Node sn && dv instanceof Node dn) {
                copyRanges(sn, dn);
            } else if (sv instanceof NodeList<?> sl && dv instanceof NodeList<?> dl) {
                int len = Math.min(sl.size(), dl.size());
                for (int i = 0; i < len; i++) {
                    copyRanges(sl.get(i), dl.get(i));
                }
            }
        }
    }

    private static CompilationUnit cloneWithRangesAndSolver(
            CompilationUnit template, JavaSymbolSolver symbolSolver) {
        CompilationUnit cloned = template.clone();
        copyRanges(template, cloned);
        if (symbolSolver != null) {
            cloned.setData(Node.SYMBOL_RESOLVER_KEY, symbolSolver);
        } else {
            cloned.removeData(Node.SYMBOL_RESOLVER_KEY);
        }
        return cloned;
    }

    private static CompilationUnit extractSkeletonClone(
            CompilationUnit fullAst, JavaSymbolSolver symbolSolver) {
        record SavedBlock(
                com.github.javaparser.ast.stmt.BlockStmt block,
                NodeList<com.github.javaparser.ast.stmt.Statement> statements) {
        }
        record SavedLambda(LambdaExpr lambda, com.github.javaparser.ast.stmt.Statement body) {
        }
        var savedBlocks = new ArrayList<SavedBlock>();
        var savedLambdas = new ArrayList<SavedLambda>();
        for (var method : fullAst.findAll(MethodDeclaration.class)) {
            method.getBody().ifPresent(b -> {
                if (!b.getStatements().isEmpty()) {
                    savedBlocks.add(new SavedBlock(b, b.getStatements()));
                    b.setStatements(new NodeList<>());
                }
            });
        }
        for (var ctor : fullAst.findAll(ConstructorDeclaration.class)) {
            var b = ctor.getBody();
            if (!b.getStatements().isEmpty()) {
                savedBlocks.add(new SavedBlock(b, b.getStatements()));
                b.setStatements(new NodeList<>());
            }
        }
        for (var ctor : fullAst.findAll(CompactConstructorDeclaration.class)) {
            var b = ctor.getBody();
            if (!b.getStatements().isEmpty()) {
                savedBlocks.add(new SavedBlock(b, b.getStatements()));
                b.setStatements(new NodeList<>());
            }
        }
        for (var init : fullAst.findAll(InitializerDeclaration.class)) {
            var b = init.getBody();
            if (!b.getStatements().isEmpty()) {
                savedBlocks.add(new SavedBlock(b, b.getStatements()));
                b.setStatements(new NodeList<>());
            }
        }
        for (var lambda : fullAst.findAll(LambdaExpr.class)) {
            var oldBody = lambda.getBody();
            savedLambdas.add(new SavedLambda(lambda, oldBody));
            var emptyBody = new com.github.javaparser.ast.stmt.BlockStmt();
            oldBody.getRange().ifPresent(emptyBody::setRange);
            lambda.setBody(emptyBody);
        }
        CompilationUnit cloned = cloneWithRangesAndSolver(fullAst, symbolSolver);
        for (int i = savedLambdas.size() - 1; i >= 0; i--) {
            var sl = savedLambdas.get(i);
            sl.lambda().setBody(sl.body());
        }
        for (int i = savedBlocks.size() - 1; i >= 0; i--) {
            var sb = savedBlocks.get(i);
            sb.block().setStatements(sb.statements());
        }
        return cloned;
    }

    static long primaryBodyRetentionCharBudget(long maxHeapBytes, long usedHeapBytes) {
        long effectiveMax = maxHeapBytes == Long.MAX_VALUE || maxHeapBytes <= 0L
                ? 1024L << 20
                : maxHeapBytes;
        long headroom = Math.max(0L, effectiveMax - Math.max(0L, usedHeapBytes));
        if (headroom < (96L << 20))
            return 0L;
        long maxAstBytes = Math.min(64L << 20, Math.min(effectiveMax / 20L, (headroom * 3L) / 20L));
        return maxAstBytes / 16L;
    }

    static int primaryBodyRetentionFileLimit(long charBudget) {
        if (charBudget <= 0L)
            return 0;
        return (int) Math.min(500L, Math.max(32L, charBudget / 8192L));
    }

    private static String kotlinCacheFileName(String path) {
        if (path == null || !path.endsWith(".kt"))
            return "";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private void parse() {
        var runtime = Runtime.getRuntime();
        long charBudget = primaryBodyRetentionCharBudget(
                runtime.maxMemory(), runtime.totalMemory() - runtime.freeMemory());
        long retainedChars = 0L;
        int retainedFiles = 0;
        int fileLimit = primaryBodyRetentionFileLimit(charBudget);
        var symbolSolver = new JavaSymbolSolver(activeSolver);
        var sources = request.sources();
        int extraPermits = IncrementalAndParallelSupport.acquireExtraPermits(sources.size());

        // Parallel Pass 1 parsing (when sources >= threshold and permits available) or
        // fast serial parsing
        if (extraPermits > 0) {
            IncrementalAndParallelSupport.RawParsedSource[] rawParsed;
            try {
                rawParsed = IncrementalAndParallelSupport.parseSourcesInParallel(
                        sources, languageLevel(), extraPermits);
            } finally {
                IncrementalAndParallelSupport.releaseExtraPermits(extraPermits);
            }
            for (var raw : rawParsed) {
                if (!raw.isSuccess()) {
                    diagnostics.add(raw.errorDiagnostic());
                    rejected.add(raw.errorOutcome());
                    continue;
                }
                SourceInput input = raw.input();
                CompilationUnit ast = raw.ast();
                ast.setData(Node.SYMBOL_RESOLVER_KEY, symbolSolver);
                var unit = new Unit(input, ast, raw.translated());
                units.put(ast, unit);
                android.observeUnit(unit.ast);
                if (request.sourceRecovery()) {
                    unitRecoveryObservations.put(input.document().identity(),
                            environment.explicitImports.observeUnitWithSummary(unit.ast, input.document().path()));
                }
                String ktName = kotlinCacheFileName(input.document().path());
                long len = input.text().length();
                boolean retainThisUnit = compactBodies
                        && charBudget > 0L
                        && retainedFiles < fileLimit
                        && retainedChars + len <= charBudget;
                if (retainThisUnit) {
                    retainedChars += len;
                    retainedFiles++;
                    CompilationUnit headerAst = extractSkeletonClone(unit.ast, symbolSolver);
                    if (!ktName.isEmpty()) {
                        var ktKey = new SkeletonCacheKey(0, input.document().contentDigest(), ktName);
                        if (REACTOR_SKELETON_CACHE.get(ktKey) == null) {
                            CompilationUnit template = cloneWithRangesAndSolver(headerAst, null);
                            long weight = (long) unit.translated.text.length() * 4 + 1024L;
                            REACTOR_SKELETON_CACHE.put(ktKey,
                                    new CachedReactorSkeleton(unit.translated, template, weight));
                        }
                    }
                    units.remove(unit.ast);
                    var headerUnit = new Unit(input, headerAst, unit.translated);
                    units.put(headerAst, headerUnit);
                    retainedFullUnits.put(headerUnit, unit);
                    orderedUnits.add(headerUnit);
                    headerAst.stream().filter(this::membershipNode).forEach(sourceNodes::add);
                    unitAbis.put(input.document().identity(),
                            SourceAbiFingerprinter.fingerprint(headerAst, input.document().path()));
                } else {
                    if (compactBodies) {
                        skeleton(unit.ast);
                        if (!ktName.isEmpty()) {
                            var ktKey = new SkeletonCacheKey(0, input.document().contentDigest(), ktName);
                            CompilationUnit template = cloneWithRangesAndSolver(unit.ast, null);
                            long weight = (long) unit.translated.text.length() * 4 + 1024L;
                            REACTOR_SKELETON_CACHE.put(ktKey,
                                    new CachedReactorSkeleton(unit.translated, template, weight));
                        }
                    }
                    orderedUnits.add(unit);
                    unit.ast.stream().filter(this::membershipNode).forEach(sourceNodes::add);
                    if (compactBodies) {
                        unitAbis.put(input.document().identity(),
                                SourceAbiFingerprinter.fingerprint(unit.ast, input.document().path()));
                    }
                }
            }
        } else {
            var parser = parser();
            for (var input : sources) {
                if (Thread.currentThread().isInterrupted())
                    throw new java.util.concurrent.CancellationException();
                long len = input.text().length();
                boolean retainThisUnit = compactBodies
                        && charBudget > 0L
                        && retainedFiles < fileLimit
                        && retainedChars + len <= charBudget;
                String ktName = kotlinCacheFileName(input.document().path());
                if (compactBodies && !retainThisUnit && !ktName.isEmpty()) {
                    var ktKey = new SkeletonCacheKey(0, input.document().contentDigest(), ktName);
                    var cached = REACTOR_SKELETON_CACHE.get(ktKey);
                    if (cached != null) {
                        KOTLIN_SKELETON_CACHE_HITS.incrementAndGet();
                        CompilationUnit cloned = cloneWithRangesAndSolver(cached.template(), symbolSolver);
                        var unit = new Unit(input, cloned, cached.translated());
                        units.put(cloned, unit);
                        android.observeUnit(unit.ast);
                        if (request.sourceRecovery()) {
                            unitRecoveryObservations.put(input.document().identity(),
                                    environment.explicitImports.observeUnitWithSummary(unit.ast,
                                            input.document().path()));
                        }
                        orderedUnits.add(unit);
                        unit.ast.stream().filter(this::membershipNode).forEach(sourceNodes::add);
                        unitAbis.put(input.document().identity(),
                                SourceAbiFingerprinter.fingerprint(unit.ast, input.document().path()));
                        continue;
                    }
                }
                final boolean retainFinal = retainThisUnit;
                var parsedOpt = parse(parser, input, Optional.empty());
                if (parsedOpt.isPresent()) {
                    var unit = parsedOpt.orElseThrow();
                    android.observeUnit(unit.ast);
                    if (request.sourceRecovery()) {
                        unitRecoveryObservations.put(input.document().identity(),
                                environment.explicitImports.observeUnitWithSummary(unit.ast, input.document().path()));
                    }
                    if (retainFinal) {
                        retainedChars += len;
                        retainedFiles++;
                        CompilationUnit headerAst = extractSkeletonClone(unit.ast, symbolSolver);
                        if (!ktName.isEmpty()) {
                            var ktKey = new SkeletonCacheKey(0, input.document().contentDigest(), ktName);
                            if (REACTOR_SKELETON_CACHE.get(ktKey) == null) {
                                CompilationUnit template = cloneWithRangesAndSolver(headerAst, null);
                                long weight = (long) unit.translated.text.length() * 4 + 1024L;
                                REACTOR_SKELETON_CACHE.put(ktKey,
                                        new CachedReactorSkeleton(unit.translated, template, weight));
                            }
                        }
                        units.remove(unit.ast);
                        var headerUnit = new Unit(input, headerAst, unit.translated);
                        units.put(headerAst, headerUnit);
                        retainedFullUnits.put(headerUnit, unit);
                        orderedUnits.add(headerUnit);
                        headerAst.stream().filter(this::membershipNode).forEach(sourceNodes::add);
                        unitAbis.put(input.document().identity(),
                                SourceAbiFingerprinter.fingerprint(headerAst, input.document().path()));
                    } else {
                        if (compactBodies) {
                            skeleton(unit.ast);
                            if (!ktName.isEmpty()) {
                                var ktKey = new SkeletonCacheKey(0, input.document().contentDigest(), ktName);
                                CompilationUnit template = cloneWithRangesAndSolver(unit.ast, null);
                                long weight = (long) unit.translated.text.length() * 4 + 1024L;
                                REACTOR_SKELETON_CACHE.put(ktKey,
                                        new CachedReactorSkeleton(unit.translated, template, weight));
                            }
                        }
                        orderedUnits.add(unit);
                        unit.ast.stream().filter(this::membershipNode).forEach(sourceNodes::add);
                        if (compactBodies) {
                            unitAbis.put(input.document().identity(),
                                    SourceAbiFingerprinter.fingerprint(unit.ast, input.document().path()));
                        }
                    }
                }
            }
        }
        var reactorParser = parser();
        int syntaxKey = request.plan().syntaxLevel().orElse(21);
        for (ReactorSourceInput reactor : request.reactorSources()) {
            for (SourceInput input : reactor.sources()) {
                if (compactBodies) {
                    String ktName = kotlinCacheFileName(input.document().path());
                    var cacheKey = new SkeletonCacheKey(
                            ktName.isEmpty() ? syntaxKey : 0,
                            input.document().contentDigest(),
                            ktName);
                    var cached = REACTOR_SKELETON_CACHE.get(cacheKey);
                    if (cached != null) {
                        if (!ktName.isEmpty())
                            KOTLIN_SKELETON_CACHE_HITS.incrementAndGet();
                        CompilationUnit cloned = cloneWithRangesAndSolver(cached.template(), symbolSolver);
                        var unit = new Unit(input, cloned, cached.translated());
                        units.put(cloned, unit);
                        reactorUnits.add(new ReactorUnit(reactor, unit));
                        unit.ast.stream().filter(this::membershipNode).forEach(reactorNodes::add);
                        recordReactorAbi(input, unit.ast);
                        continue;
                    }
                    parse(reactorParser, input, Optional.of(reactor)).ifPresent(unit -> {
                        skeleton(unit.ast);
                        CompilationUnit template = cloneWithRangesAndSolver(unit.ast, null);
                        long weight = (long) unit.translated.text.length() * 4 + 1024L;
                        REACTOR_SKELETON_CACHE.put(
                                cacheKey, new CachedReactorSkeleton(unit.translated, template, weight));
                        reactorUnits.add(new ReactorUnit(reactor, unit));
                        unit.ast.stream().filter(this::membershipNode).forEach(reactorNodes::add);
                        recordReactorAbi(input, unit.ast);
                    });
                } else {
                    parse(reactorParser, input, Optional.of(reactor)).ifPresent(unit -> {
                        reactorUnits.add(new ReactorUnit(reactor, unit));
                        unit.ast.stream().filter(this::membershipNode).forEach(reactorNodes::add);
                    });
                }
            }
        }
    }

    private void recordReactorAbi(SourceInput input, CompilationUnit skeletonAst) {
        var abi = SourceAbiFingerprinter.fingerprint(skeletonAst, input.document().path());
        reactorAbis.put(input.document().identity(), new ModuleIncrementalCache.ReactorFileAbi(
                input.document().identity(),
                input.document().path(),
                input.document().contentDigest(),
                abi.externalAbiDigest(),
                abi.publicCrossModuleAbiDigest(),
                abi.packageName(),
                abi.declaredFqns(),
                abi.declaredSimpleNames()));
    }

    private JavaParser parser() {
        return new JavaParser(new ParserConfiguration().setLanguageLevel(languageLevel()).setTabSize(1)
                .setStoreTokens(true).setAttributeComments(false).setPreprocessUnicodeEscapes(false)
                .setSymbolResolver(new JavaSymbolSolver(activeSolver)));
    }

    /**
     * Preserve signatures, annotations, constants, ranges and member shape for
     * cross-file
     * resolution. No method/constructor/initializer executable body enters the
     * global index.
     */
    private static void skeleton(CompilationUnit ast) {
        for (var method : ast.findAll(MethodDeclaration.class))
            method.getBody().ifPresent(body -> body.getStatements().clear());
        for (var constructor : ast.findAll(ConstructorDeclaration.class))
            constructor.getBody().getStatements().clear();
        for (var constructor : ast.findAll(CompactConstructorDeclaration.class))
            constructor.getBody().getStatements().clear();
        for (var initializer : ast.findAll(InitializerDeclaration.class))
            initializer.getBody().getStatements().clear();
        for (var lambda : ast.findAll(LambdaExpr.class)) {
            var body = new com.github.javaparser.ast.stmt.BlockStmt();
            lambda.getBody().getRange().ifPresent(body::setRange);
            lambda.setBody(body);
        }
    }

    static void detachTokensAndNormalizeIdentifiers(CompilationUnit ast, boolean detachTokens) {
        ast.walk(node -> {
            if (detachTokens) {
                var range = node.getRange().orElse(null);
                node.setTokenRange(null);
                node.setRange(range);
            }
            if (node instanceof SimpleName n) {
                String raw = n.getIdentifier();
                String norm = semanticIdentifier(raw);
                if (!norm.equals(raw))
                    n.setIdentifier(norm);
            } else if (node instanceof Name n) {
                String raw = n.getIdentifier();
                String norm = semanticIdentifier(raw);
                if (!norm.equals(raw))
                    n.setIdentifier(norm);
            } else if (node instanceof MethodReferenceExpr n) {
                String raw = n.getIdentifier();
                String norm = semanticIdentifier(raw);
                if (!norm.equals(raw))
                    n.setIdentifier(norm);
            }
        });
    }

    private Optional<Unit> parse(
            JavaParser parser, SourceInput input, Optional<ReactorSourceInput> reactor) {
        if (input.document().path().endsWith(".kt")) {
            UnicodeSource ktSource;
            try {
                ktSource = UnicodeSource.translate(input.text());
            } catch (IllegalArgumentException ignored) {
                ktSource = UnicodeSource.translate(input.text().replace("\\u", "\\\\u"));
            }
            var ast = KotlinAstLowering.lower(
                    input.document().path(), ktSource, new JavaSymbolSolver(activeSolver));
            detachTokensAndNormalizeIdentifiers(ast, false);
            var unit = new Unit(input, ast, ktSource);
            units.put(ast, unit);
            return Optional.of(unit);
        }
        UnicodeSource translated;
        try {
            translated = UnicodeSource.translate(input.text());
        } catch (IllegalArgumentException invalid) {
            var diagnostic = new Diagnostic(DiagnosticSeverity.ERROR, "java.unicode-escape-error",
                    "Invalid eligible Unicode escape", Optional.empty(),
                    Map.of("document", input.document().identity().value()));
            diagnostics.add(diagnostic);
            rejected.add(
                    new SourceOutcome(input.document().identity(), SourceOutcome.State.ERROR, List.of(diagnostic)));
            if (reactor.isPresent())
                reactorResolutionDegraded = true;
            return Optional.empty();
        }
        parser.getParserConfiguration().setPreprocessUnicodeEscapes(false);
        var parsed = parser.parse(translated.text);
        if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
            String code = reactor.isPresent() ? "java.reactor-source-parse-error" : "java.parse-error";
            var diagnostic = new Diagnostic(DiagnosticSeverity.ERROR, code,
                    reactor.isPresent()
                            ? "Sibling-module source syntax could not be parsed; its bindings are unavailable"
                            : "Source syntax could not be parsed; recovered bindings are withheld",
                    Optional.empty(), Map.of("document", input.document().identity().value()));
            diagnostics.add(diagnostic);
            rejected.add(new SourceOutcome(
                    input.document().identity(), SourceOutcome.State.ERROR, List.of(diagnostic)));
            if (reactor.isPresent())
                reactorResolutionDegraded = true;
            return Optional.empty();
        }
        var ast = parsed.getResult().orElseThrow();
        // JavaParser derives source ranges while storing tokens. Detach its linked
        // token stream only after preserving those ranges, so headers cannot retain
        // every token from discarded bodies and raw source spans remain exact.
        detachTokensAndNormalizeIdentifiers(ast, true);
        var unit = new Unit(input, ast, translated);
        units.put(ast, unit);
        return Optional.of(unit);
    }

    private ParserConfiguration.LanguageLevel languageLevel() {
        if (request.plan().preview()) {
            throw new FrontendInputException("frontend.preview",
                    "Preview syntax is not supported by this frontend version");
        }
        int level = request.plan().syntaxLevel().orElseThrow(
                () -> new FrontendInputException("frontend.syntax-level", "A supported syntax level is required"));
        return switch (level) {
            case 1 -> ParserConfiguration.LanguageLevel.JAVA_1_1;
            case 2 -> ParserConfiguration.LanguageLevel.JAVA_1_2;
            case 3 -> ParserConfiguration.LanguageLevel.JAVA_1_3;
            case 4 -> ParserConfiguration.LanguageLevel.JAVA_1_4;
            case 5 -> ParserConfiguration.LanguageLevel.JAVA_5;
            case 6 -> ParserConfiguration.LanguageLevel.JAVA_6;
            case 7 -> ParserConfiguration.LanguageLevel.JAVA_7;
            case 8 -> ParserConfiguration.LanguageLevel.JAVA_8;
            case 9 -> ParserConfiguration.LanguageLevel.JAVA_9;
            case 10 -> ParserConfiguration.LanguageLevel.JAVA_10;
            case 11 -> ParserConfiguration.LanguageLevel.JAVA_11;
            case 12 -> ParserConfiguration.LanguageLevel.JAVA_12;
            case 13 -> ParserConfiguration.LanguageLevel.JAVA_13;
            case 14 -> ParserConfiguration.LanguageLevel.JAVA_14;
            case 15 -> ParserConfiguration.LanguageLevel.JAVA_15;
            case 16 -> ParserConfiguration.LanguageLevel.JAVA_16;
            case 17 -> ParserConfiguration.LanguageLevel.JAVA_17;
            case 18 -> ParserConfiguration.LanguageLevel.JAVA_18;
            case 19 -> ParserConfiguration.LanguageLevel.JAVA_19;
            case 20 -> ParserConfiguration.LanguageLevel.JAVA_20;
            case 21 -> ParserConfiguration.LanguageLevel.JAVA_21;
            case 22 -> ParserConfiguration.LanguageLevel.JAVA_22;
            case 23 -> ParserConfiguration.LanguageLevel.JAVA_23;
            case 24 -> ParserConfiguration.LanguageLevel.JAVA_24;
            case 25 -> ParserConfiguration.LanguageLevel.JAVA_25;
            case 26 -> ParserConfiguration.LanguageLevel.JAVA_26;
            default -> throw new FrontendInputException("frontend.syntax-level",
                    "Syntax level is outside this parser's Java 1-26 capability; a newer syntax provider is required");
        };
    }

    private static String semanticIdentifier(String raw) {
        boolean purePrintableAscii = true;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                purePrintableAscii = false;
                break;
            }
        }
        if (purePrintableAscii)
            return raw;
        var result = new StringBuilder(raw.length());
        raw.codePoints().filter(c -> !Character.isIdentifierIgnorable(c)).forEach(result::appendCodePoint);
        String built = result.toString();
        return built.equals(raw) ? raw : built;
    }

    private static boolean isMainSourcePath(Unit unit) {
        String p = unit.input.document().path().replace('\\', '/');
        return p.startsWith("src/main/") || p.contains("/src/main/");
    }

    private static int compareRecoveryPriority(Unit a, Unit b) {
        boolean mainA = isMainSourcePath(a);
        boolean mainB = isMainSourcePath(b);
        if (mainA != mainB)
            return mainA ? -1 : 1;
        int sizeCmp = Integer.compare(b.input.bytes().length, a.input.bytes().length);
        if (sizeCmp != 0)
            return sizeCmp;
        return a.input.document().path().compareTo(b.input.document().path());
    }

    private void indexTypes() {
        Map<String, Unit> recoveryWinners = new HashMap<>();
        if (request.sourceRecovery()) {
            for (var unit : orderedUnits) {
                for (TypeDeclaration<?> type : unit.ast.findAll(TypeDeclaration.class)) {
                    if (type.getFullyQualifiedName().isEmpty() || hasLocalOwner(type))
                        continue;
                    String name = type.getFullyQualifiedName().orElseThrow();
                    recoveryWinners.merge(name, unit, (existing,
                            candidate) -> compareRecoveryPriority(existing, candidate) <= 0 ? existing : candidate);
                }
            }
        }
        var indexed = new HashSet<String>();
        var unitIterator = orderedUnits.iterator();
        while (unitIterator.hasNext()) {
            var unit = unitIterator.next();
            var unitTopTypes = new ArrayList<Map.Entry<String, TypeDeclaration<?>>>();
            var unitSeen = new HashSet<String>();
            String duplicateName = null;
            for (TypeDeclaration<?> type : unit.ast.findAll(TypeDeclaration.class)) {
                type.getFullyQualifiedName().ifPresent(sourceTypeNames::add);
                if (type.getFullyQualifiedName().isEmpty() || hasLocalOwner(type))
                    continue;
                String name = type.getFullyQualifiedName().orElseThrow();
                boolean lostRecoveryPriority = request.sourceRecovery() && recoveryWinners.get(name) != unit;
                if (!unitSeen.add(name) || indexed.contains(name) || lostRecoveryPriority) {
                    duplicateName = name;
                    break;
                }
                unitTopTypes.add(Map.entry(name, type));
            }
            if (duplicateName != null) {
                if (!request.sourceRecovery()) {
                    throw new FrontendInputException("frontend.duplicate-type",
                            "Duplicate source type definitions in the supplied environment");
                }
                var warning = new Diagnostic(DiagnosticSeverity.WARNING, "java.duplicate-source-type",
                        "Duplicate source type definition in recovery mode; colliding source document is withheld",
                        Optional.empty(),
                        Map.of("type", duplicateName, "document", unit.input.document().identity().value()));
                diagnostics.add(warning);
                rejected.add(new SourceOutcome(unit.input.document().identity(), SourceOutcome.State.PARTIAL,
                        List.of(warning)));
                retainedFullUnits.remove(unit);
                releaseBody(unit);
                unitIterator.remove();
                continue;
            }
            for (var entry : unitTopTypes) {
                indexed.add(entry.getKey());
                environment.project.addDeclaration(entry.getKey(), entry.getValue().resolve());
            }
        }
        var reactorIndexed = new HashMap<Integer, Set<String>>();
        var reactorIterator = reactorUnits.iterator();
        while (reactorIterator.hasNext()) {
            ReactorUnit reactor = reactorIterator.next();
            Set<String> names = reactorIndexed.computeIfAbsent(reactor.input().order(), ignored -> new HashSet<>());
            var unitTopTypes = new ArrayList<Map.Entry<String, TypeDeclaration<?>>>();
            var unitSeen = new HashSet<String>();
            String duplicateName = null;
            for (TypeDeclaration<?> type : reactor.unit().ast.findAll(TypeDeclaration.class)) {
                if (type.getFullyQualifiedName().isEmpty() || hasLocalOwner(type))
                    continue;
                String name = type.getFullyQualifiedName().orElseThrow();
                if (!unitSeen.add(name) || names.contains(name)) {
                    duplicateName = name;
                    break;
                }
                unitTopTypes.add(Map.entry(name, type));
            }
            if (duplicateName != null) {
                if (!request.sourceRecovery()) {
                    throw new FrontendInputException(
                            "frontend.duplicate-reactor-source-type",
                            "Duplicate type definitions occur in one sibling-module source input");
                }
                var warning = new Diagnostic(DiagnosticSeverity.WARNING, "java.duplicate-reactor-source-type",
                        "Duplicate sibling-module source type in recovery mode; colliding source document is withheld",
                        Optional.empty(),
                        Map.of("type", duplicateName, "document", reactor.unit().input.document().identity().value()));
                diagnostics.add(warning);
                rejected.add(new SourceOutcome(reactor.unit().input.document().identity(), SourceOutcome.State.PARTIAL,
                        List.of(warning)));
                releaseBody(reactor.unit());
                reactorIterator.remove();
                continue;
            }
            for (var entry : unitTopTypes) {
                names.add(entry.getKey());
                environment.addReactorDeclaration(reactor.input().order(), entry.getKey(), entry.getValue().resolve());
            }
        }
        var allReactorFqns = new HashSet<String>();
        var duplicateReactorFqns = new HashSet<String>();
        for (Set<String> slotNames : reactorIndexed.values()) {
            for (String fqn : slotNames) {
                if (!allReactorFqns.add(fqn)) {
                    duplicateReactorFqns.add(fqn);
                }
            }
        }
        environment.setReactorTypeIndex(allReactorFqns, duplicateReactorFqns);
        android.populate(request, environment, parser());
        if (request.sourceRecovery()) {
            environment.explicitImports.materialize(
                    sourceTypeNames,
                    environment,
                    !environment.hasAnyLombokClassInDependencies());
        }
        if (fastArchitecture) {
            for (String fqn : sourceTypeNames) {
                int dot = fqn.lastIndexOf('.');
                architecturalSimpleTypeNames.add(dot >= 0 ? fqn.substring(dot + 1) : fqn);
            }
            for (var reactor : reactorUnits) {
                for (TypeDeclaration<?> td : reactor.unit().ast.findAll(TypeDeclaration.class)) {
                    architecturalSimpleTypeNames.add(td.getNameAsString());
                }
            }
        }
        environment.invalidateSolverCaches();
    }

    private boolean isSourceOrReactorNode(Node node) {
        return sourceNodes.contains(node) || reactorNodes.contains(node);
    }

    boolean hasLocalOwner(Node node) {
        for (Node current = node; current != null; current = current.getParentNode().orElse(null)) {
            if (current instanceof TypeDeclaration<?> t && !(t.getParentNode().orElse(null) instanceof CompilationUnit)
                    && !(t.getParentNode().orElse(null) instanceof TypeDeclaration<?>))
                return true;
            if (current instanceof ObjectCreationExpr)
                return true;
        }
        return false;
    }

    private boolean isDeclaration(Node node) {
        return node instanceof TypeDeclaration<?> || node instanceof CallableDeclaration<?>
                || node instanceof CompactConstructorDeclaration || node instanceof AnnotationMemberDeclaration
                || node instanceof VariableDeclarator v && v.getParentNode().orElse(null) instanceof FieldDeclaration
                || node instanceof Parameter || node instanceof TypeParameter || node instanceof EnumConstantDeclaration
                || node instanceof InitializerDeclaration || node instanceof LambdaExpr
                || node instanceof ObjectCreationExpr o && o.getAnonymousClassBody().isPresent();
    }

    // Only declaration-shaped nodes can cross the resolver's toAst boundary.
    // Retaining
    // every expression/token in a second identity hash table scales with the entire
    // AST.
    private boolean membershipNode(Node node) {
        return node instanceof CompilationUnit || node instanceof FieldDeclaration || isDeclaration(node);
    }

    JavaSymbolName name(Node node) {
        var existing = names.get(node);
        if (existing != null)
            return existing;
        JavaSymbolName result;
        if (node instanceof CompilationUnit cu)
            result = JavaSymbolName.packageName(cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse(""));
        else if (node instanceof TypeDeclaration<?> type) {
            Node parent = type.getParentNode().orElseThrow();
            if (parent instanceof CompilationUnit cu)
                result = JavaSymbolName.topLevelType(
                        cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse(""), type.getNameAsString());
            else if (parent instanceof TypeDeclaration<?> || parent instanceof ObjectCreationExpr
                    || parent instanceof EnumConstantDeclaration)
                result = JavaSymbolName.memberType(typeOwnerName(parent), type.getNameAsString());
            else
                result = JavaSymbolName.localType(ownerName(type), unit(type).input.document().identity(),
                        unit(type).source.start(type), type.getNameAsString(), false);
        } else if (node instanceof MethodDeclaration method)
            result = JavaSymbolName.method(typeOwnerName(enclosingType(node)), method.getNameAsString(),
                    parameters(method));
        else if (node instanceof ConstructorDeclaration constructor)
            result = JavaSymbolName.constructor(typeOwnerName(enclosingType(node)), parameters(constructor));
        else if (node instanceof CompactConstructorDeclaration)
            result = JavaSymbolName.constructor(typeOwnerName(enclosingType(node)),
                    parameters(((RecordDeclaration) enclosingType(node)).getParameters()));
        else if (node instanceof AnnotationMemberDeclaration member)
            result = JavaSymbolName.method(typeOwnerName(enclosingType(node)), member.getNameAsString(), List.of());
        else if (node instanceof VariableDeclarator variable)
            result = JavaSymbolName.field(typeOwnerName(enclosingType(node)), variable.getNameAsString());
        else if (node instanceof EnumConstantDeclaration constant)
            result = JavaSymbolName.field(name(enclosingType(node)), constant.getNameAsString());
        else if (node instanceof Parameter parameter) {
            Node parent = parameter.getParentNode().orElseThrow();
            if (parent instanceof CallableDeclaration<?> callable)
                result = JavaSymbolName.parameter(name(callable), callable.getParameters().indexOf(parameter));
            else if (parent instanceof RecordDeclaration record)
                result = JavaSymbolName.recordComponent(name(record), parameter.getNameAsString());
            else if (parent instanceof LambdaExpr lambda)
                result = JavaSymbolName.parameter(name(lambda), lambda.getParameters().indexOf(parameter));
            else if (parent instanceof com.github.javaparser.ast.stmt.CatchClause)
                result = JavaSymbolName.catchParameter(
                        ownerName(parameter), unit(parameter).input.document().identity(),
                        unit(parameter).source.start(parameter), parameter.getNameAsString());
            else
                throw new MappingFailure(SemanticStatus.UNSUPPORTED, "java.parameter-owner");
        } else if (node instanceof TypeParameter parameter) {
            Node parent = parameter.getParentNode().orElseThrow();
            var siblings = parent.getChildNodes().stream().filter(n -> n instanceof TypeParameter).toList();
            result = JavaSymbolName.typeParameter(name(parent), siblings.indexOf(parameter));
        } else if (node instanceof LambdaExpr)
            result = executionName("lambda", node, "lambda", ownerName(node));
        else if (node instanceof InitializerDeclaration initializer)
            result = executionName("initializer", node, initializer.isStatic() ? "static" : "instance",
                    typeOwnerName(enclosingType(node)));
        else if (node instanceof ObjectCreationExpr creation && creation.getAnonymousClassBody().isPresent())
            result = JavaSymbolName.localType(ownerName(node), unit(node).input.document().identity(),
                    unit(node).source.start(node), "", true);
        else
            throw new MappingFailure(SemanticStatus.UNSUPPORTED, "java.declaration-kind");
        names.put(node, result);
        return result;
    }

    private JavaSymbolName executionName(String kind, Node node, String syntax, JavaSymbolName owner) {
        return JavaSymbolName.execution(kind, owner, unit(node).input.document().identity(),
                unit(node).source.start(node), syntax);
    }

    private List<ErasedType> parameters(CallableDeclaration<?> callable) {
        return parameters(callable.getParameters());
    }

    List<ErasedType> parameters(List<Parameter> parameters) {
        return parameters.stream().map(p -> {
            var type = erasedParameter(p.getType());
            return p.isVarArgs() ? ErasedType.array(type, 1) : type;
        }).toList();
    }

    ErasedType erasedParameter(com.github.javaparser.ast.type.Type type) {
        if (type.isArrayType())
            return ErasedType.array(erasedParameter(type.asArrayType().getComponentType()), 1);
        if (type.isClassOrInterfaceType()) {
            var declaration = resolveNamed(type.asClassOrInterfaceType());
            // Identity needs declaration erasure, not successful attribution of every
            // generic argument.
            if (!declaration.isTypeParameter())
                return ErasedType.declared(typeName(declaration.asReferenceType()));
        }
        return erased(type.resolve(), new HashSet<>());
    }

    private ErasedType erased(ResolvedType type, Set<String> visiting) {
        if (type.isPrimitive())
            return ErasedType.primitive(type.describe());
        if (type.isArray())
            return ErasedType.array(erased(type.asArrayType().getComponentType(), visiting), 1);
        if (type.isReferenceType())
            return ErasedType.declared(typeName(type.asReferenceType().getTypeDeclaration().orElseThrow()));
        if (type.isTypeVariable()) {
            var variable = type.asTypeVariable().asTypeParameter();
            if (!visiting.add(variable.getContainerId() + ":" + variable.getName()))
                throw new MappingFailure(SemanticStatus.ERROR, "java.cyclic-erasure");
            var bounds = variable.getBounds();
            return bounds.isEmpty() ? ErasedType.declared(JavaSymbolName.topLevelType("java.lang", "Object"))
                    : erased(bounds.getFirst().getType(), visiting);
        }
        throw new MappingFailure(SemanticStatus.UNSUPPORTED, "java.erasure");
    }

    JavaSymbolName typeName(ResolvedReferenceTypeDeclaration type) {
        if (type.toAst().filter(this::isSourceOrReactorNode).isPresent()) {
            Node ast = type.toAst().orElseThrow();
            unit(ast);
            return typeOwnerName(ast);
        }
        if (environment.isRecoveryScaffold(type, activeAndroidProject, activeAndroidPlatform))
            throw new MappingFailure(SemanticStatus.UNRESOLVED, "java.declaration-evidence-required");
        // ReflectionAnnotationDeclaration does not implement containerType(). Its
        // verified
        // canonical name still distinguishes package and nested declaration components.
        if (type.isAnnotation()) {
            String pkg = type.getPackageName();
            String[] parts = type.getQualifiedName().substring(pkg.isEmpty() ? 0 : pkg.length() + 1).split("\\.");
            var symbol = JavaSymbolName.topLevelType(pkg, parts[0]);
            for (int i = 1; i < parts.length; i++)
                symbol = JavaSymbolName.memberType(symbol, parts[i]);
            return symbol;
        }
        return type.containerType().map(t -> JavaSymbolName.memberType(typeName(t), type.getName()))
                .orElseGet(() -> {
                    String pkg = type.getPackageName();
                    if (pkg == null) {
                        if (!type.getQualifiedName().equals(type.getName()))
                            throw new MappingFailure(SemanticStatus.UNSUPPORTED, "java.binary-package-evidence");
                        pkg = "";
                    }
                    return JavaSymbolName.topLevelType(pkg, type.getName());
                });
    }

    private Node enclosingType(Node node) {
        for (Node parent = node.getParentNode().orElse(null); parent != null; parent = parent.getParentNode()
                .orElse(null))
            if (parent instanceof TypeDeclaration<?>
                    || parent instanceof ObjectCreationExpr o && o.getAnonymousClassBody().isPresent()
                    || parent instanceof EnumConstantDeclaration e && !e.getClassBody().isEmpty())
                return parent;
        throw new MappingFailure(SemanticStatus.ERROR, "java.type-owner");
    }

    private Node ownerNode(Node node) {
        Node child = node;
        for (Node parent = node.getParentNode().orElse(null); parent != null; child = parent, parent = parent
                .getParentNode().orElse(null)) {
            if (parent instanceof VariableDeclarator v && v.getParentNode().orElse(null) instanceof FieldDeclaration)
                return parent;
            if (parent instanceof ObjectCreationExpr creation) {
                if (creation.getAnonymousClassBody().isPresent() && child instanceof BodyDeclaration<?>)
                    return parent;
                continue;
            }
            if (isDeclaration(parent) || parent instanceof CompilationUnit)
                return parent;
        }
        throw new MappingFailure(SemanticStatus.ERROR, "java.owner");
    }

    private Entity owner(Node node) {
        Node owner = ownerNode(node);
        if (owner instanceof VariableDeclarator variable && !(node instanceof VariableDeclarator))
            return fieldInitializer(variable);
        if (owner instanceof EnumConstantDeclaration constant)
            return inEnumConstantBody(node) ? enumBody(constant) : enumInitializer(constant);
        return entity(owner);
    }

    private JavaSymbolName ownerName(Node node) {
        Node owner = ownerNode(node);
        if (owner instanceof VariableDeclarator variable)
            return fieldInitializerName(variable);
        if (owner instanceof EnumConstantDeclaration constant)
            return inEnumConstantBody(node) ? typeOwnerName(constant)
                    : executionName("initializer", constant, "enum-constant", name(constant));
        return name(owner);
    }

    private JavaSymbolName fieldInitializerName(VariableDeclarator variable) {
        boolean isStatic = ((FieldDeclaration) variable.getParentNode().orElseThrow()).isStatic();
        return executionName("initializer", variable.getInitializer().orElseThrow(),
                isStatic ? "field-static" : "field-instance", name(variable));
    }

    private static boolean inEnumConstantBody(Node node) {
        Node child = node;
        for (Node parent = node.getParentNode().orElse(null); parent != null; child = parent, parent = parent
                .getParentNode().orElse(null))
            if (parent instanceof EnumConstantDeclaration && child instanceof BodyDeclaration<?>)
                return true;
        return false;
    }

    JavaSymbolName typeOwnerName(Node node) {
        return node instanceof EnumConstantDeclaration
                ? JavaSymbolName.localType(name(node), unit(node).input.document().identity(), source(node).start(node),
                        "", true)
                : name(node);
    }

    private static final int COMPACT_SLICE_THRESHOLD = 1024;

    static String compactSourceSlice(Node node, String raw) {
        if (raw == null || raw.length() <= COMPACT_SLICE_THRESHOLD) {
            return raw;
        }
        if (node instanceof TypeDeclaration<?>
                || node instanceof CallableDeclaration<?>
                || node instanceof CompactConstructorDeclaration
                || node instanceof InitializerDeclaration
                || node instanceof EnumConstantDeclaration
                || node instanceof ObjectCreationExpr) {
            int braceIdx = findTopLevelOpeningBrace(raw, COMPACT_SLICE_THRESHOLD);
            if (braceIdx >= 0) {
                return raw.substring(0, braceIdx + 1) + " /* ... */ }";
            }
        }
        return raw.substring(0, 768) + " /* ... */ " + raw.substring(raw.length() - 192);
    }

    private static int findTopLevelOpeningBrace(String raw, int maxSearch) {
        int limit = Math.min(raw.length(), maxSearch);
        int parens = 0;
        for (int i = 0; i < limit; i++) {
            char c = raw.charAt(i);
            if (c == '/' && i + 1 < raw.length()) {
                char next = raw.charAt(i + 1);
                if (next == '/') {
                    int nl = raw.indexOf('\n', i + 2);
                    if (nl < 0)
                        return -1;
                    i = nl;
                    continue;
                }
                if (next == '*') {
                    int end = raw.indexOf("*/", i + 2);
                    if (end < 0)
                        return -1;
                    i = end + 1;
                    continue;
                }
            }
            if (c == '"' || c == '\'') {
                char quote = c;
                i++;
                while (i < raw.length()) {
                    char q = raw.charAt(i);
                    if (q == '\\') {
                        i += 2;
                        continue;
                    }
                    if (q == quote)
                        break;
                    i++;
                }
                continue;
            }
            if (c == '(')
                parens++;
            else if (c == ')' && parens > 0)
                parens--;
            else if (c == '{' && parens == 0)
                return i;
        }
        return -1;
    }

    Entity enumBody(EnumConstantDeclaration constant) {
        return register(typeOwnerName(constant), EntityKind.TYPE, Optional.of(source(constant).span(constant)),
                compactSourceSlice(constant, source(constant).slice(source(constant).span(constant))), DIRECT);
    }

    private Entity fieldInitializer(VariableDeclarator variable) {
        var initializer = variable.getInitializer().orElseThrow();
        return register(fieldInitializerName(variable), EntityKind.INITIALIZER,
                Optional.of(unit(variable).source.span(initializer)),
                compactSourceSlice(initializer, unit(variable).source.slice(unit(variable).source.span(initializer))),
                DIRECT);
    }

    Entity enumInitializer(EnumConstantDeclaration constant) {
        return register(executionName("initializer", constant, "enum-constant", name(constant)), EntityKind.INITIALIZER,
                Optional.of(unit(constant).source.span(constant)), constant.getNameAsString(), DIRECT);
    }

    Entity entity(Node node) {
        if (!sourceNodes.contains(node) && !reactorNodes.contains(node)) {
            throw new MappingFailure(SemanticStatus.UNSUPPORTED, "java.generated-ast");
        }
        Entity cached = nodes.get(node);
        if (cached != null) {
            if (duplicateDeclarations.contains(cached.identity()))
                throw new MappingFailure(SemanticStatus.ERROR, "java.duplicate-declaration");
            return cached;
        }
        JavaSymbolName symbol = name(node);
        EntityKind kind = node instanceof CompilationUnit ? EntityKind.PACKAGE
                : node instanceof TypeDeclaration<?> || node instanceof ObjectCreationExpr ? EntityKind.TYPE
                        : node instanceof MethodDeclaration || node instanceof AnnotationMemberDeclaration
                                ? EntityKind.METHOD
                                : node instanceof ConstructorDeclaration
                                        || node instanceof CompactConstructorDeclaration ? EntityKind.CONSTRUCTOR
                                                : node instanceof Parameter p
                                                        ? p.getParentNode().orElse(null) instanceof RecordDeclaration
                                                                ? EntityKind.RECORD_COMPONENT
                                                                : EntityKind.PARAMETER
                                                        : node instanceof TypeParameter ? EntityKind.TYPE_PARAMETER
                                                                : node instanceof LambdaExpr ? EntityKind.LAMBDA
                                                                        : node instanceof InitializerDeclaration
                                                                                ? EntityKind.INITIALIZER
                                                                                : EntityKind.FIELD;
        Optional<SourceSpan> span = node instanceof CompilationUnit ? Optional.empty()
                : Optional.of(unit(node).source.span(node));
        Derivation derivation = node instanceof CompilationUnit
                ? new Derivation(DerivationKind.DERIVED, new VersionedIdentifier("java.package-scope", "1"),
                        List.of(request.module().value()))
                : DIRECT;
        String overlay = request.manifest().configuration().values().get("java.generated-overlay");
        var document = unit(node).input.document();
        if (!(node instanceof CompilationUnit) && overlay != null
                && Set.of(SourceClassification.GENERATED_MAIN, SourceClassification.GENERATED_TEST)
                        .contains(document.classification()))
            derivation = new Derivation(DerivationKind.DERIVED,
                    new VersionedIdentifier("java.generated-source-import", "m8.4-v1"),
                    List.of(overlay, document.contentDigest().value()));
        ModuleIdentity module = sourceNodes.contains(node) ? request.module() : unit(node).input.document().module();
        var entity = register(symbol, kind, span,
                span.map(s -> compactSourceSlice(node, unit(node).source.slice(s))).orElse(""), derivation, module);
        checkDeclarationTypes(node, entity);
        nodes.put(node, entity);
        return entity;
    }

    private void checkDeclarationTypes(Node node, Entity entity) {
        try {
            if (node instanceof MethodDeclaration method && !method.getType().isVoidType())
                method.getType().resolve();
            if (node instanceof VariableDeclarator variable)
                variable.getType().resolve();
            if (node instanceof Parameter parameter && !parameter.getType().isUnknownType())
                parameter.getType().resolve();
            if (node instanceof CallableDeclaration<?> callable) {
                for (var parameter : callable.getParameters())
                    parameter.getType().resolve();
                for (var thrown : callable.getThrownExceptions())
                    thrown.resolve();
            }
            if (node instanceof TypeParameter parameter)
                for (var bound : parameter.getTypeBound())
                    bound.resolve();
            if (node instanceof ClassOrInterfaceDeclaration type) {
                for (var extended : type.getExtendedTypes())
                    extended.resolve();
                for (var implemented : type.getImplementedTypes())
                    implemented.resolve();
            }
        } catch (RuntimeException | LinkageError | StackOverflowError exception) {
            boolean unresolved = exception instanceof UnsolvedSymbolException;
            var diagnostic = diagnostic(unresolved ? "java.declaration-type-unresolved" : "java.declaration-type-error",
                    unresolved ? SemanticStatus.UNRESOLVED : SemanticStatus.ERROR, entity.declaration());
            var prior = declarations.get(entity.identity());
            declarations.put(entity.identity(),
                    new DeclarationRecord(entity, prior.spelling(), SemanticStatus.PARTIAL, prior.derivation(),
                            List.of(new Uncertainty("java.declaration-type",
                                    "Declaration identity is known but type information is incomplete",
                                    List.of("verified declaration type"))),
                            List.of(diagnostic)));
            unit(node).diagnostics.add(diagnostic);
            diagnostics.add(diagnostic);
        }
    }

    private void recordProjectCanonical(Entity entity) {
        if (entity.origin() == EntityOrigin.PROJECT) {
            projectIdsByCanonical.computeIfAbsent(entity.canonicalName(), k -> new ArrayList<>(2))
                    .add(entity.identity());
        }
    }

    private Entity register(JavaSymbolName name, EntityKind kind, Optional<SourceSpan> span, String spelling,
            Derivation derivation) {
        return register(name, kind, span, spelling, derivation, request.module());
    }

    private Entity register(JavaSymbolName name, EntityKind kind, Optional<SourceSpan> span, String spelling,
            Derivation derivation, ModuleIdentity module) {
        var entity = Entity.create(EntityOrigin.PROJECT, EntityScope.project(module), kind, name.canonicalName(), span);
        var record = new DeclarationRecord(entity, spelling, SemanticStatus.RESOLVED, derivation, List.of(), List.of());
        var prior = declarations.putIfAbsent(entity.identity(), record);
        if (prior == null) {
            recordProjectCanonical(entity);
        } else if (!prior.entity().declaration().equals(span)) {
            duplicateDeclarations.add(entity.identity());
            throw new MappingFailure(SemanticStatus.ERROR, "java.duplicate-declaration");
        }
        return entity;
    }

    private Entity callable(ResolvedMethodLikeDeclaration resolved, Node site) {
        // JavaParser 3.27.1 exposes implicit record Object methods through
        // java.lang.Record.
        // Java's record rule declares these overrides on the receiver record itself.
        if (resolved instanceof ResolvedMethodDeclaration method && !method.isStatic()
                && Set.of("java.lang.Record", "java.lang.Object").contains(method.declaringType().getQualifiedName())
                && Set.of("equals", "hashCode", "toString").contains(method.getName())) {
            RecordDeclaration record = null;
            Expression scope = site instanceof MethodCallExpr call ? call.getScope().orElse(null)
                    : ((MethodReferenceExpr) site).getScope();
            if (scope == null) {
                if (enclosingType(site) instanceof RecordDeclaration r)
                    record = r;
            } else if (!(scope instanceof SuperExpr)) {
                ResolvedType receiver;
                if (site instanceof MethodReferenceExpr reference && scope instanceof TypeExpr type) {
                    var value = ReferenceResolution.value(reference, scope.toString());
                    receiver = value.isPresent() ? value.orElseThrow().getType() : type.getType().resolve();
                } else
                    receiver = scope.calculateResolvedType();
                if (receiver.isReferenceType() && receiver.asReferenceType().getTypeDeclaration().orElseThrow().toAst()
                        .filter(this::isSourceOrReactorNode).orElse(null) instanceof RecordDeclaration r)
                    record = r;
            }
            if (record != null) {
                var parameters = new ArrayList<ErasedType>();
                for (int i = 0; i < method.getNumberOfParams(); i++)
                    parameters.add(erased(method.getParam(i).getType(), new HashSet<>()));
                var symbol = JavaSymbolName.method(name(record), method.getName(), parameters);
                if (reactorNodes.contains(record)) {
                    warnDuplicateExternal(record.resolve().getQualifiedName());
                    return reactorImplicit(symbol, EntityKind.METHOD, record, record,
                            method.getName(), "java.record-object-methods");
                }
                return existing(symbol);
            }
        }
        return callable(resolved);
    }

    private Entity resolveCall(Expression expression) {
        var nativeCall = nativeMembers.resolve(expression);
        if (nativeCall.isPresent())
            return nativeCall.orElseThrow();
        var generated = lombok.resolve(expression);
        if (generated.isPresent()) {
            if (expression instanceof MethodCallExpr call)
                recoveryCallInputs.add(call);
            return generated.orElseThrow();
        }
        if (request.sourceRecovery() && expression instanceof MethodCallExpr call)
            requireRecoveryReceiver(call);
        if (expression instanceof MethodCallExpr call) {
            if (!request.sourceRecovery() && boundedReceiver(call.getScope()))
                requireRecoverySelected(call, recoveryReceiver(call, call.getScope()));
            try {
                var result = callable(call.resolve(), call);
                if (request.sourceRecovery())
                    recoveryCallInputs.add(call);
                return result;
            } catch (UnsolvedSymbolException missing) {
                var screened = screenDeclaredMethodByArity(call, recoveryReceiver(call, call.getScope()));
                if (screened.isPresent()) {
                    var result = callable(screened.orElseThrow(), call);
                    if (request.sourceRecovery())
                        recoveryCallInputs.add(call);
                    return result;
                }
                throw missing;
            }
        }
        return callable(((ObjectCreationExpr) expression).resolve());
    }

    private boolean boundedReceiver(Optional<Expression> scope) {
        if (scope.isEmpty())
            return false;
        try {
            Expression receiver = scope.orElseThrow();
            while (receiver instanceof EnclosedExpr enclosed)
                receiver = enclosed.getInner();
            if (receiver.containsData(LambdaParameterInference.INPUT))
                return true;
            var type = scope.orElseThrow().calculateResolvedType();
            return type.isConstraint() || type.isWildcard() || type.isTypeVariable()
                    || type instanceof com.github.javaparser.resolution.types.ResolvedIntersectionType;
        } catch (UnsolvedSymbolException typeQualifier) {
            return false;
        } // Static type qualifiers need not have a value type.
    }

    /**
     * A missing parent may introduce a more specific overload even when resolve()
     * picks
     * a local method. Close only this receiver hierarchy, never the entire module.
     */
    private void requireRecoveryReceiver(MethodCallExpr call) {
        if (recoveryCallInputs.contains(call))
            return;
        // Resolve inputs before using their inferred return types for an outer
        // overload.
        // An uncertain nested call cannot manufacture an exact outer receiver/argument
        // type.
        requireRecoveryInputs(call, call);
    }

    private void requireRecoveryInputs(Node input, MethodCallExpr deferred) {
        if (input instanceof MethodCallExpr call && recoveryCallInputs.contains(call))
            return;
        input.walk(Node.TreeTraversal.POSTORDER, node -> {
            if (node instanceof MethodCallExpr nested && !recoveryCallInputs.contains(nested)) {
                if (lombok.resolve(nested).isPresent()) {
                    recoveryCallInputs.add(nested);
                    return;
                }
                requireRecoveryReceiverType(nested);
                if (nested != deferred) {
                    nested.resolve();
                    recoveryCallInputs.add(nested);
                }
            }
        });
    }

    private void requireRecoveryReceiverType(MethodCallExpr call) {
        requireRecoverySelected(call, recoveryReceiver(call, call.getScope()));
    }

    private ResolvedReferenceTypeDeclaration recoveryReceiver(Node site, Optional<Expression> receiverScope) {
        ResolvedReferenceTypeDeclaration receiver;
        if (receiverScope.isEmpty()) {
            Node owner = enclosingType(site);
            if (owner instanceof TypeDeclaration<?> type)
                receiver = type.resolve();
            else if (owner instanceof ObjectCreationExpr creation)
                receiver = creation.getType().resolve().asReferenceType().getTypeDeclaration().orElseThrow();
            else if (owner instanceof EnumConstantDeclaration constant)
                receiver = constant.resolve().getType().asReferenceType().getTypeDeclaration().orElseThrow();
            else
                throw new MappingFailure(SemanticStatus.UNSUPPORTED, "java.receiver-shape-unsupported");
        } else {
            var scope = receiverScope.orElseThrow();
            requireRecoveryInputs(scope, null);
            ResolvedType type;
            try {
                var value = site instanceof MethodReferenceExpr reference
                        ? ReferenceResolution.value(reference, scope.toString())
                        : Optional.<ResolvedValueDeclaration>empty();
                type = value.isPresent() ? value.orElseThrow().getType()
                        : scope instanceof TypeExpr expression ? expression.getType().resolve()
                                : scope.calculateResolvedType();
            } catch (UnsolvedSymbolException missing) {
                var context = com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFactory.getContext(scope,
                        activeSolver);
                if (scope instanceof NameExpr name && context.solveSymbol(name.getNameAsString()).isSolved())
                    throw missing;
                if (!(scope instanceof NameExpr || scope instanceof FieldAccessExpr || scope instanceof TypeExpr))
                    throw missing;
                var named = context.solveType(scope.toString());
                if (!named.isSolved())
                    throw missing;
                return named.getCorrespondingDeclaration().asReferenceType();
            }
            if (type.isConstraint())
                requireLambdaCaptureContext(site);
            if (type.isArray())
                receiver = activeSolver.solveType("java.lang.Object");
            else if (type.isReferenceType())
                receiver = type.asReferenceType().getTypeDeclaration().orElseThrow();
            else {
                var bounds = new ArrayList<ResolvedReferenceTypeDeclaration>();
                recoveryUpperBounds(type, new HashSet<>(), bounds);
                // The solver still selects the actual member. This is a closure
                // check over every bound, never an erasure-based target guess.
                for (var bound : bounds)
                    requireRecoveryHierarchy(bound);
                if (bounds.isEmpty())
                    throw new MappingFailure(SemanticStatus.UNRESOLVED, "java.receiver-type-required");
                ResolvedMethodLikeDeclaration selected = site instanceof MethodCallExpr call ? call.resolve()
                        : site instanceof MethodReferenceExpr reference
                                ? new ReferenceResolution(activeSolver).resolve(reference)
                                : null;
                ResolvedReferenceTypeDeclaration declaringType = selected == null ? null : selected.declaringType();
                if (site instanceof FieldAccessExpr access) {
                    var value = access.resolve();
                    if (value.isField())
                        declaringType = value.asField().declaringType().asReferenceType();
                }
                if (declaringType != null) {
                    String declaring = hierarchyKey(declaringType);
                    boolean member = declaring.equals(hierarchyKey(activeSolver.solveType("java.lang.Object")));
                    for (var bound : bounds)
                        if (hierarchyKey(bound).equals(declaring)
                                || bound.getAllAncestors().stream().anyMatch(parent -> parent.getTypeDeclaration()
                                        .map(this::hierarchyKey).filter(declaring::equals).isPresent()))
                            member = true;
                    if (!member)
                        throw new MappingFailure(SemanticStatus.UNRESOLVED, "java.receiver-bound-member-required");
                }
                receiver = bounds.getFirst();
            }
        }
        return receiver;
    }

    private void recoveryUpperBounds(ResolvedType type, Set<String> visiting,
            List<ResolvedReferenceTypeDeclaration> result) {
        if (type.isConstraint()) {
            recoveryUpperBounds(type.asConstraintType().getBound(), visiting, result);
            return;
        }
        if (type.isReferenceType()) {
            result.add(type.asReferenceType().getTypeDeclaration().orElseThrow());
            return;
        }
        if (type.isArray()) {
            result.add(activeSolver.solveType("java.lang.Object"));
            return;
        }
        if (type.isWildcard()) {
            var wildcard = type.asWildcard();
            if (wildcard.isUpperBounded())
                recoveryUpperBounds(wildcard.getBoundedType(), visiting, result);
            else
                result.add(activeSolver.solveType("java.lang.Object"));
            return;
        }
        if (type.isTypeVariable()) {
            var parameter = type.asTypeVariable().asTypeParameter();
            String key = parameter.getQualifiedName();
            if (!visiting.add(key))
                throw new MappingFailure(SemanticStatus.UNRESOLVED, "java.receiver-bound-cycle");
            try {
                if (parameter.getBounds().isEmpty())
                    result.add(activeSolver.solveType("java.lang.Object"));
                else
                    for (var bound : parameter.getBounds()) {
                        if (!bound.isExtends())
                            throw new MappingFailure(SemanticStatus.UNRESOLVED, "java.receiver-type-required");
                        recoveryUpperBounds(bound.getType(), visiting, result);
                    }
            } finally {
                visiting.remove(key);
            }
            return;
        }
        if (type instanceof com.github.javaparser.resolution.types.ResolvedIntersectionType intersection) {
            for (var bound : intersection.getElements())
                recoveryUpperBounds(bound, visiting, result);
            return;
        }
        throw new MappingFailure(SemanticStatus.UNRESOLVED, "java.receiver-type-required");
    }

    private void requireLambdaCaptureContext(Node site) {
        // The parser constraint wrapper drops wildcard direction. A receiver capture
        // such as Iterable<? super Pet> cannot establish a Pet-typed lambda value.
        // Keep this case unknown until its actual capture/ground target is available.
        for (Node node = site; node != null; node = node.getParentNode().orElse(null))
            if (node instanceof LambdaExpr lambda) {
                Node parent = lambda.getParentNode().orElse(null);
                while (parent instanceof EnclosedExpr)
                    parent = parent.getParentNode().orElse(null);
                if (parent instanceof MethodCallExpr call && call.getScope().isPresent()
                        && hasLowerWildcard(call.getScope().orElseThrow().calculateResolvedType()))
                    throw new MappingFailure(SemanticStatus.UNRESOLVED, "java.lambda-capture-context-required");
            }
    }

    private boolean hasLowerWildcard(ResolvedType type) {
        if (type.isConstraint())
            return hasLowerWildcard(type.asConstraintType().getBound());
        if (type.isWildcard())
            return type.asWildcard().isLowerBounded()
                    || type.asWildcard().isUpperBounded() && hasLowerWildcard(type.asWildcard().getBoundedType());
        if (type.isArray())
            return hasLowerWildcard(type.asArrayType().getComponentType());
        return type.isReferenceType()
                && type.asReferenceType().typeParametersValues().stream().anyMatch(this::hasLowerWildcard);
    }

    private void requireRecoverySelected(MethodCallExpr call, ResolvedReferenceTypeDeclaration receiver) {
        for (var argument : call.getArguments())
            requireRecoveryArgumentType(argument.calculateResolvedType(), new HashSet<>());
        // A fixed own zero-argument declaration shadows any inherited alternative;
        // varargs and inherited selections still require the receiver hierarchy.
        if (call.getArguments().isEmpty()) {
            try {
                var selected = call.resolve();
                if (ownZeroArgumentMethod(selected, receiver))
                    return;
            } catch (UnsolvedSymbolException missing) {
                var screened = screenDeclaredMethodByArity(call, receiver);
                if (screened.isPresent() && ownZeroArgumentMethod(screened.orElseThrow(), receiver))
                    return;
                throw missing;
            }
        }
        requireRecoveryHierarchy(receiver);
    }

    /**
     * Arity-first overload screening guard: when an unresolvable sibling overload
     * causes
     * JavaParser overload resolution to throw despite a unique declared exact-arity
     * candidate,
     * verify uniqueness across the declaring type (and closed hierarchy when arity
     * > 0) and check
     * argument assignability without weakening source-recovery closure
     * requirements.
     */
    private Optional<ResolvedMethodDeclaration> screenDeclaredMethodByArity(MethodCallExpr call,
            ResolvedReferenceTypeDeclaration receiver) {
        if (!(receiver.toAst().filter(this::isSourceOrReactorNode)
                .orElse(null) instanceof TypeDeclaration<?> ownerDecl)) {
            return Optional.empty();
        }
        int argCount = call.getArguments().size();
        String name = call.getNameAsString();
        var exact = new ArrayList<MethodDeclaration>();
        for (var member : ownerDecl.getMembers()) {
            if (!(member instanceof MethodDeclaration method) || !method.getNameAsString().equals(name))
                continue;
            var params = method.getParameters();
            boolean varargs = !params.isEmpty() && params.get(params.size() - 1).isVarArgs();
            if (varargs && params.size() - 1 <= argCount)
                return Optional.empty();
            if (!varargs && params.size() == argCount)
                exact.add(method);
        }
        if (exact.size() != 1)
            return Optional.empty();
        MethodDeclaration candidate = exact.getFirst();
        if (argCount == 0) {
            try {
                ResolvedMethodDeclaration resolved = candidate.resolve();
                return ownZeroArgumentMethod(resolved, receiver) ? Optional.of(resolved) : Optional.empty();
            } catch (UnsolvedSymbolException ignored) {
                return Optional.empty();
            }
        }
        requireRecoveryHierarchy(receiver);
        try {
            for (var ancestor : receiver.getAllAncestors()) {
                var ancDecl = ancestor.getTypeDeclaration().orElse(null);
                if (ancDecl == null)
                    return Optional.empty();
                for (var ancMethod : ancDecl.getDeclaredMethods()) {
                    if (!ancMethod.getName().equals(name))
                        continue;
                    int ancParams = ancMethod.getNumberOfParams();
                    boolean ancVarargs = ancMethod.hasVariadicParameter();
                    if ((ancVarargs && ancParams - 1 <= argCount) || (!ancVarargs && ancParams == argCount)) {
                        return Optional.empty();
                    }
                }
            }
            ResolvedMethodDeclaration resolved = candidate.resolve();
            for (int i = 0; i < argCount; i++) {
                ResolvedType paramType = resolved.getParam(i).getType();
                if (paramType.isTypeVariable() || paramType.isWildcard() || paramType.isConstraint()) {
                    return Optional.empty();
                }
                ResolvedType argType = call.getArgument(i).calculateResolvedType();
                if (!paramType.isAssignableBy(argType)) {
                    return Optional.empty();
                }
            }
            return Optional.of(resolved);
        } catch (UnsolvedSymbolException | UnsupportedOperationException ignored) {
            return Optional.empty();
        }
    }

    private void requireRecoveryArgumentType(ResolvedType type, Set<String> visited) {
        if (!visited.add(type.describe()))
            return;
        if (type.isArray()) {
            requireRecoveryArgumentType(type.asArrayType().getComponentType(), visited);
        } else if (type.isReferenceType()) {
            var reference = type.asReferenceType();
            var declaration = reference.getTypeDeclaration().orElseThrow();
            if (declaration.toAst().filter(this::isSourceOrReactorNode).isEmpty())
                environment.origin(declaration.getQualifiedName(), activeAndroidProject, activeAndroidPlatform,
                        activeExplicitImports);
            for (var parameter : reference.typeParametersValues())
                requireRecoveryArgumentType(parameter, visited);
        } else if (type.isTypeVariable()) {
            for (var bound : type.asTypeVariable().asTypeParameter().getBounds())
                requireRecoveryArgumentType(bound.getType(), visited);
        } else if (type.isWildcard() && type.asWildcard().isBounded()) {
            requireRecoveryArgumentType(type.asWildcard().getBoundedType(), visited);
        } else if (type.isConstraint()) {
            requireRecoveryArgumentType(type.asConstraintType().getBound(), visited);
        }
    }

    private boolean ownZeroArgumentMethod(ResolvedMethodLikeDeclaration selected,
            ResolvedReferenceTypeDeclaration receiver) {
        return selected.getNumberOfParams() == 0
                && selected.toAst().filter(this::isSourceOrReactorNode).filter(MethodDeclaration.class::isInstance)
                        .isPresent()
                && hierarchyKey(selected.declaringType()).equals(hierarchyKey(receiver));
    }

    private String hierarchyKey(ResolvedReferenceTypeDeclaration type) {
        return type.toAst().filter(this::isSourceOrReactorNode)
                .map(n -> unit(n).input.document().identity().value() + ":" + unit(n).source.start(n))
                .orElse("external:" + type.getQualifiedName());
    }

    private void requireRecoveryHierarchy(ResolvedReferenceTypeDeclaration receiver) {
        String root = hierarchyKey(receiver);
        var cached = recoveryHierarchies.get(root);
        if (cached != null) {
            if (cached.isPresent())
                throw new MappingFailure(cached.orElseThrow().status(), cached.orElseThrow().code());
            return;
        }
        try {
            var pending = new ArrayDeque<ResolvedReferenceTypeDeclaration>();
            pending.add(receiver);
            var visited = new HashSet<String>();
            while (!pending.isEmpty()) {
                var type = pending.removeFirst();
                if (!visited.add(hierarchyKey(type)))
                    continue;
                if (type instanceof ExplicitImportTypeSolver.ExplicitImportTypeDeclaration) {
                    throw new UnsolvedSymbolException(type.getQualifiedName());
                }
                // Some solver paths omit unknown ancestors. Inspect written source parents too.
                if (type.toAst().filter(this::isSourceOrReactorNode)
                        .orElse(null) instanceof ClassOrInterfaceDeclaration declaration) {
                    for (var parent : declaration.getExtendedTypes()) {
                        var parentDecl = parent.resolve().asReferenceType().getTypeDeclaration().orElseThrow();
                        if (parentDecl instanceof ExplicitImportTypeSolver.ExplicitImportTypeDeclaration) {
                            throw new UnsolvedSymbolException(parentDecl.getQualifiedName());
                        }
                        pending.add(parentDecl);
                    }
                    for (var parent : declaration.getImplementedTypes()) {
                        var parentDecl = parent.resolve().asReferenceType().getTypeDeclaration().orElseThrow();
                        if (parentDecl instanceof ExplicitImportTypeSolver.ExplicitImportTypeDeclaration) {
                            throw new UnsolvedSymbolException(parentDecl.getQualifiedName());
                        }
                        pending.add(parentDecl);
                    }
                }
                for (var parent : type.getAncestors(false))
                    pending.add(parent.getTypeDeclaration().orElseThrow());
            }
            recoveryHierarchies.put(root, Optional.empty());
        } catch (UnsolvedSymbolException missing) {
            recoveryHierarchies.put(root,
                    Optional.of(new ClosureFailure(SemanticStatus.UNRESOLVED, "java.receiver-hierarchy-required")));
            throw new MappingFailure(SemanticStatus.UNRESOLVED, "java.receiver-hierarchy-required");
        }
    }

    Entity callable(ResolvedMethodLikeDeclaration resolved) {
        if (nativeMembers != null) {
            var nativeDeclaration = nativeMembers.callable(resolved);
            if (nativeDeclaration.isPresent())
                return nativeDeclaration.orElseThrow();
        }
        if (resolved.toAst().filter(this::isSourceOrReactorNode).isPresent()) {
            Node node = resolved.toAst().orElseThrow();
            if (node instanceof CallableDeclaration<?> || node instanceof CompactConstructorDeclaration
                    || node instanceof AnnotationMemberDeclaration) {
                if (reactorNodes.contains(node))
                    warnDuplicateExternal(resolved.declaringType().getQualifiedName());
                return entity(node);
            }
        }
        var type = resolved.declaringType();
        var parameters = new ArrayList<ErasedType>();
        for (int i = 0; i < resolved.getNumberOfParams(); i++)
            parameters.add(erased(resolved.getParam(i).getType(), new HashSet<>()));
        var symbol = resolved instanceof ResolvedConstructorDeclaration
                ? JavaSymbolName.constructor(typeName(type), parameters)
                : JavaSymbolName.method(typeName(type), resolved.getName(), parameters);
        if (type.toAst().filter(this::isSourceOrReactorNode).isPresent()) {
            Node declaration = type.toAst().orElseThrow();
            if (reactorNodes.contains(declaration)) {
                warnDuplicateExternal(type.getQualifiedName());
                if (resolved instanceof ResolvedMethodDeclaration method
                        && declaration instanceof RecordDeclaration record
                        && method.getNumberOfParams() == 0) {
                    var component = record.getParameters().stream()
                            .filter(candidate -> candidate.getNameAsString().equals(method.getName()))
                            .findFirst();
                    if (component.isPresent()) {
                        return reactorImplicit(symbol, EntityKind.METHOD, record, component.orElseThrow(),
                                method.getName(), "java.record-component-accessor");
                    }
                }
                EntityKind kind = resolved instanceof ResolvedConstructorDeclaration
                        ? EntityKind.CONSTRUCTOR
                        : EntityKind.METHOD;
                return reactorImplicit(symbol, kind, declaration, declaration,
                        resolved.getName(), "java.reactor-source-symbol");
            }
            return existing(symbol);
        }
        var origin = environment.origin(type.getQualifiedName(), activeAndroidProject, activeAndroidPlatform,
                activeExplicitImports);
        if (environment.duplicateExternal(type.getQualifiedName())) {
            duplicateTypeWarnings.add(type.getQualifiedName());
            diagnostics.add(new Diagnostic(DiagnosticSeverity.WARNING, "java.duplicate-binary-type",
                    "Ordered classpath selected the first definition", Optional.empty(),
                    Map.of("type", typeName(type).canonicalName())));
        }
        var entity = Entity.create(origin.kind(), origin.scope(),
                resolved instanceof ResolvedConstructorDeclaration ? EntityKind.CONSTRUCTOR : EntityKind.METHOD,
                symbol.canonicalName(), Optional.empty());
        declarations
                .putIfAbsent(entity.identity(), new DeclarationRecord(entity, resolved.getName(),
                        SemanticStatus.RESOLVED, environment.nativeDerivation(type.getQualifiedName(),
                                activeAndroidProject, activeAndroidPlatform, activeExplicitImports),
                        List.of(), List.of()));
        return entity;
    }

    Entity field(ResolvedValueDeclaration resolved, Node site) {
        var scope = site instanceof FieldAccessExpr access ? Optional.of(access.getScope())
                : Optional.<Expression>empty();
        if (resolved.isField() && (request.sourceRecovery() || boundedReceiver(scope))) {
            var receiver = recoveryReceiver(site, scope);
            if (!hierarchyKey(resolved.asField().declaringType().asReferenceType()).equals(hierarchyKey(receiver)))
                requireRecoveryHierarchy(receiver);
        }
        return field(resolved);
    }

    Entity field(ResolvedValueDeclaration resolved) {
        if (resolved.isField()
                && resolved.asField().declaringType().toAst().filter(this::isSourceOrReactorNode)
                        .orElse(null) instanceof RecordDeclaration record
                && record.getParameters().stream().anyMatch(p -> p.getNameAsString().equals(resolved.getName()))) {
            var symbol = JavaSymbolName.field(name(record), resolved.getName());
            if (reactorNodes.contains(record)) {
                warnDuplicateExternal(resolved.asField().declaringType().getQualifiedName());
                var component = record.getParameters().stream()
                        .filter(candidate -> candidate.getNameAsString().equals(resolved.getName()))
                        .findFirst().orElseThrow();
                return reactorImplicit(symbol, EntityKind.FIELD, record, component,
                        resolved.getName(), "java.record-component-field");
            }
            return existing(symbol);
        }
        if (resolved.toAst().filter(this::isSourceOrReactorNode).isPresent()) {
            Node node = resolved.toAst().orElseThrow();
            if (node instanceof FieldDeclaration declaration) {
                var matching = declaration.getVariables().stream()
                        .filter(v -> v.getNameAsString().equals(resolved.getName())).toList();
                if (matching.size() != 1)
                    throw new MappingFailure(SemanticStatus.ERROR, "java.field-declaration");
                node = matching.getFirst();
            }
            if (node instanceof VariableDeclarator variable
                    && variable.getParentNode().orElse(null) instanceof FieldDeclaration
                    || node instanceof EnumConstantDeclaration) {
                if (reactorNodes.contains(node) && resolved.isField()) {
                    warnDuplicateExternal(resolved.asField().declaringType().getQualifiedName());
                }
                return entity(node);
            }
            throw new MappingFailure(SemanticStatus.UNSUPPORTED, "java.field-declaration");
        }
        ResolvedReferenceTypeDeclaration type = resolved.isField()
                ? resolved.asField().declaringType().asReferenceType()
                : resolved.asEnumConstant().getType().asReferenceType().getTypeDeclaration().orElseThrow();
        if (type.toAst().filter(this::isSourceOrReactorNode).isPresent())
            throw new MappingFailure(SemanticStatus.UNSUPPORTED, "java.implicit-field");
        var origin = environment.origin(type.getQualifiedName(), activeAndroidProject, activeAndroidPlatform,
                activeExplicitImports);
        if (environment.duplicateExternal(type.getQualifiedName())) {
            duplicateTypeWarnings.add(type.getQualifiedName());
            diagnostics.add(new Diagnostic(DiagnosticSeverity.WARNING, "java.duplicate-binary-type",
                    "Ordered classpath selected the first definition", Optional.empty(),
                    Map.of("type", typeName(type).canonicalName())));
        }
        var entity = Entity.create(origin.kind(), origin.scope(), EntityKind.FIELD,
                JavaSymbolName.field(typeName(type), resolved.getName()).canonicalName(), Optional.empty());
        declarations
                .putIfAbsent(entity.identity(), new DeclarationRecord(entity, resolved.getName(),
                        SemanticStatus.RESOLVED, environment.nativeDerivation(type.getQualifiedName(),
                                activeAndroidProject, activeAndroidPlatform, activeExplicitImports),
                        List.of(), List.of()));
        return entity;
    }

    Entity fieldOwner(Node node) {
        for (Node ancestor = node.getParentNode().orElse(null); ancestor != null; ancestor = ancestor.getParentNode()
                .orElse(null))
            if (ancestor instanceof AnnotationExpr annotation)
                return typeUseOwner(annotation);
            else if (ancestor instanceof AnnotationMemberDeclaration)
                return entity(ancestor);
        var owner = owner(node);
        if (!Set.of(EntityKind.METHOD, EntityKind.CONSTRUCTOR, EntityKind.INITIALIZER, EntityKind.LAMBDA)
                .contains(owner.kind()))
            throw new MappingFailure(SemanticStatus.UNSUPPORTED, "java.field-execution-owner");
        return owner;
    }

    ResolvedValueDeclaration resolveValueCached(Expression expression) {
        Object cached = unitValueCache.get(expression);
        if (cached != null) {
            if (cached instanceof ResolvedValueDeclaration val)
                return val;
            throw (RuntimeException) cached;
        }
        try {
            ResolvedValueDeclaration resolved = expression instanceof FieldAccessExpr access
                    ? access.resolve()
                    : ((NameExpr) expression).resolve();
            unitValueCache.put(expression, resolved);
            return resolved;
        } catch (RuntimeException failure) {
            unitValueCache.put(expression, failure);
            throw failure;
        }
    }

    ResolvedTypeDeclaration resolveTypeNameCached(Expression expression, String name) {
        Object cached = unitTypeNameCache.get(expression);
        if (cached != null) {
            if (cached instanceof ResolvedTypeDeclaration type)
                return type;
            throw (RuntimeException) cached;
        }
        try {
            ResolvedTypeDeclaration resolved = resolveTypeName(expression, name);
            unitTypeNameCache.put(expression, resolved);
            return resolved;
        } catch (RuntimeException failure) {
            unitTypeNameCache.put(expression, failure);
            throw failure;
        }
    }

    boolean isTypeName(Node context, String name) {
        try {
            ResolvedTypeDeclaration declaration;
            if (context instanceof Expression expr) {
                declaration = resolveTypeNameCached(expr, name);
            } else {
                var selected = com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFactory
                        .getContext(context, activeSolver).solveType(name);
                if (!selected.isSolved())
                    return false;
                declaration = selected.getCorrespondingDeclaration();
            }
            if (declaration.toAst().filter(this::isSourceOrReactorNode).isEmpty())
                environment.origin(declaration.asReferenceType().getQualifiedName(), activeAndroidProject,
                        activeAndroidPlatform, activeExplicitImports);
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    ResolvedTypeDeclaration resolveTypeName(Node node, String name) {
        var result = com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFactory.getContext(node, activeSolver)
                .solveType(name);
        if (!result.isSolved())
            throw new UnsolvedSymbolException(name);
        return result.getCorrespondingDeclaration();
    }

    void unmapped(Node node, String category, SemanticStatus status, String code) {
        observe(node, category, () -> {
            throw new MappingFailure(status, code);
        }, () -> {
            throw new MappingFailure(status, code);
        });
    }

    ResolvedTypeDeclaration resolveNamed(com.github.javaparser.ast.type.ClassOrInterfaceType type) {
        Object cached = unitNamedTypeCache.get(type);
        if (cached != null) {
            if (cached instanceof ResolvedTypeDeclaration resolved)
                return resolved;
            throw (RuntimeException) cached;
        }
        try {
            var resolved = com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFactory
                    .getContext(type, activeSolver)
                    .solveType(type.getNameWithScope());
            if (!resolved.isSolved())
                throw new UnsolvedSymbolException(type.getNameWithScope());
            var decl = resolved.getCorrespondingDeclaration();
            unitNamedTypeCache.put(type, decl);
            return decl;
        } catch (RuntimeException failure) {
            unitNamedTypeCache.put(type, failure);
            throw failure;
        }
    }

    Entity typeEntity(ResolvedTypeDeclaration type) {
        if (type.toAst().filter(this::isSourceOrReactorNode).isPresent()) {
            Node declaration = type.toAst().orElseThrow();
            if (reactorNodes.contains(declaration) && type.isReferenceType()) {
                warnDuplicateExternal(type.asReferenceType().getQualifiedName());
            }
            return entity(declaration);
        }
        if (type.isTypeParameter())
            throw new MappingFailure(SemanticStatus.UNSUPPORTED, "java.external-type-parameter");
        var reference = type.asReferenceType();
        var origin = environment.origin(reference.getQualifiedName(), activeAndroidProject, activeAndroidPlatform,
                activeExplicitImports);
        var entity = Entity.create(origin.kind(), origin.scope(), EntityKind.TYPE, typeName(reference).canonicalName(),
                Optional.empty());
        declarations
                .putIfAbsent(entity.identity(), new DeclarationRecord(entity, reference.getQualifiedName(),
                        SemanticStatus.RESOLVED, environment.nativeDerivation(reference.getQualifiedName(),
                                activeAndroidProject, activeAndroidPlatform, activeExplicitImports),
                        List.of(), List.of()));
        return entity;
    }

    OriginalSource source(Node node) {
        return unit(node).source;
    }

    Entity existing(JavaSymbolName symbol) {
        String canonical = symbol.canonicalName();
        List<EntityIdentity> ids = projectIdsByCanonical.get(canonical);
        DeclarationRecord found = null;
        if (ids != null && !ids.isEmpty()) {
            if (ids.size() == 1) {
                found = declarations.get(ids.get(0));
            } else {
                var sorted = new ArrayList<>(ids);
                Collections.sort(sorted);
                found = declarations.get(sorted.get(0));
            }
        }
        if (found == null || duplicateDeclarations.contains(found.entity().identity()))
            throw new MappingFailure(SemanticStatus.UNSUPPORTED, "java.implicit-callable");
        return found.entity();
    }

    Entity sourceDeclaration(Entity expected) {
        var value = declarations.get(expected.identity());
        if (value == null || duplicateDeclarations.contains(expected.identity()) || !value.entity().equals(expected))
            throw new FrontendInputException("frontend.native-source-declaration",
                    "Correspondence does not match the actual handwritten declaration");
        return value.entity();
    }

    Node sourceDeclarationNode(Entity entity) {
        sourceDeclaration(entity);
        var matches = nodes.entrySet().stream().filter(e -> e.getValue().equals(entity)).map(Map.Entry::getKey)
                .toList();
        if (matches.size() != 1)
            throw new FrontendInputException("frontend.native-source-declaration",
                    "Correspondence source node is missing or ambiguous");
        return matches.getFirst();
    }

    List<Entity> sourceMembers(JavaSymbolName type) {
        return nodes.entrySet().stream().filter(e -> {
            try {
                return typeNameOfOwner(e.getKey()).equals(type);
            } catch (RuntimeException absent) {
                return false;
            }
        }).map(Map.Entry::getValue)
                .filter(e -> Set.of(EntityKind.FIELD, EntityKind.METHOD, EntityKind.CONSTRUCTOR).contains(e.kind()))
                .distinct().toList();
    }

    private JavaSymbolName typeNameOfOwner(Node node) {
        return name(enclosingType(node));
    }

    boolean nativeOwner(TypeDeclaration<?> type) {
        if (nativeMembers == null || !nativeMembers.hasOwners())
            return false;
        try {
            return nativeMembers.owns(name(type));
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    JavaSymbolName callableName(ResolvedMethodLikeDeclaration resolved) {
        var parameters = new ArrayList<ErasedType>();
        for (int i = 0; i < resolved.getNumberOfParams(); i++)
            parameters.add(erased(resolved.getParam(i).getType(), new HashSet<>()));
        return resolved instanceof ResolvedConstructorDeclaration
                ? JavaSymbolName.constructor(typeName(resolved.declaringType()), parameters)
                : JavaSymbolName.method(typeName(resolved.declaringType()), resolved.getName(), parameters);
    }

    Entity nativeDeclaration(JavaSymbolName symbol, EntityKind kind, JavaEvidenceMapping mapping) {
        var entity = Entity.create(EntityOrigin.PROJECT, EntityScope.project(request.module()), kind,
                symbol.canonicalName(), Optional.empty());
        if (declarations.containsKey(entity.identity()))
            throw new FrontendInputException("frontend.native-member-conflict",
                    "An augmentation would overwrite an existing declaration");
        var derivation = new Derivation(DerivationKind.DERIVED,
                new VersionedIdentifier("java.native-correspondence", "m8.4-v1"),
                List.of(mapping.record().identity().value(), mapping.record().text("proof"),
                        mapping.source().identity().value()));
        declarations.put(entity.identity(), new DeclarationRecord(entity, mapping.spelling(), SemanticStatus.RESOLVED,
                derivation, List.of(), List.of()));
        recordProjectCanonical(entity);
        return entity;
    }

    record JavaEvidenceMapping(com.evolution.analysis.javaevidence.JavaEvidenceRecord record, Entity source,
            String spelling) {
    }

    private Entity reactorImplicit(JavaSymbolName symbol, EntityKind kind, Node ownerNode,
            Node evidenceNode, String spelling, String rule) {
        Entity owner = entity(ownerNode);
        Entity evidence = entity(evidenceNode);
        var identity = EntityIdentity.from(EntityOrigin.PROJECT, owner.stableScope(), kind, symbol.canonicalName());
        var prior = declarations.get(identity);
        if (prior != null)
            return prior.entity();
        var member = Entity.create(EntityOrigin.PROJECT, owner.stableScope(), kind,
                symbol.canonicalName(), Optional.empty());
        var derivation = new Derivation(DerivationKind.DERIVED,
                new VersionedIdentifier(rule, "1"), List.of(evidence.identity().value()));
        declarations.put(identity, new DeclarationRecord(
                member, spelling, SemanticStatus.RESOLVED, derivation, List.of(), List.of()));
        recordProjectCanonical(member);
        return member;
    }

    private void warnDuplicateExternal(String qualifiedName) {
        if (environment.duplicateExternal(qualifiedName) && duplicateTypeWarnings.add(qualifiedName)) {
            diagnostics.add(new Diagnostic(DiagnosticSeverity.WARNING, "java.duplicate-binary-type",
                    "Ordered classpath selected the first definition", Optional.empty(),
                    Map.of("type", qualifiedName)));
        }
    }

    Entity implicit(JavaSymbolName symbol, EntityKind kind, Node input, String rule) {
        return implicit(symbol, kind, entity(input), input, rule);
    }

    Entity implicit(JavaSymbolName symbol, EntityKind kind, Entity owner, Node input, String rule) {
        var evidence = entity(input);
        var identity = EntityIdentity.from(EntityOrigin.PROJECT, EntityScope.project(request.module()), kind,
                symbol.canonicalName());
        if (declarations.containsKey(identity))
            return existing(symbol);
        var derivation = new Derivation(DerivationKind.DERIVED, new VersionedIdentifier(rule, "1"),
                List.of(evidence.identity().value()));
        var result = register(symbol, kind, Optional.empty(), "", derivation);
        derive(owner, result, "declares", input, rule);
        return result;
    }

    void derive(Entity owner, Entity target, String category, Node input, String rule) {
        var evidence = entity(input);
        var derivation = new Derivation(DerivationKind.DERIVED, new VersionedIdentifier(rule, "1"),
                List.of(evidence.identity().value()));
        var rec = new DerivedRelationshipRecord(
                SemanticRelationship.create(owner.identity(), new RelationshipKind("java." + category),
                        new RelationshipTarget.Resolved(target.identity())),
                SemanticStatus.RESOLVED, derivation, List.of(source(input).span(input)), List.of(), List.of());
        derived.add(rec);
        if (activeUnitDerived != null)
            activeUnitDerived.add(rec);
    }

    void implicitFailure(Node node, RuntimeException failure) {
        var problem = diagnostic("java.implicit-member-incomplete", failureStatus(failure),
                Optional.of(source(node).span(node)));
        unit(node).diagnostics.add(problem);
        diagnostics.add(problem);
    }

    boolean isImplicit(Entity entity) {
        return declarations.get(entity.identity()).derivation().kind() == DerivationKind.DERIVED;
    }

    boolean hasDeclaration(JavaSymbolName name) {
        String canonical = name.canonicalName();
        List<EntityIdentity> ids = projectIdsByCanonical.get(canonical);
        if (ids == null || ids.isEmpty())
            return false;
        var scope = EntityScope.project(request.module());
        for (var id : ids) {
            var d = declarations.get(id);
            if (d != null && d.entity().stableScope().equals(scope))
                return true;
        }
        return false;
    }

    LombokConfig.Effective lombokConfig(Node node) {
        if (lombokConfig == null) {
            lombokConfig = LombokConfig.from(request);
        }
        if (lombokConfig.isEmpty())
            return LombokConfig.Effective.DEFAULT;
        return lombokConfig.forDocumentPath(unit(node).input.document().path());
    }

    boolean hasLombokConfiguration(Node node) {
        return lombokConfig(node).requiresConfigurationGap(true, true, true, true);
    }

    boolean isLombok(Entity entity, String name) {
        if (entity.origin() != EntityOrigin.DEPENDENCY)
            return false;
        String canonical = JavaTypeName.fromCanonical(entity.canonicalName())
                .map(JavaTypeName::qualifiedName)
                .orElse("");
        if (!canonical.equals(name) && !canonical.replace('$', '.').equals(name))
            return false;
        return request.dependencies().stream().anyMatch(b -> environment.isVerifiedLombokArtifact(b)
                && entity.stableScope().equals(EntityScope.external(EntityOrigin.DEPENDENCY,
                        b.originEntry().logicalName(), b.originEntry().contentDigest())));
    }

    boolean isSourceRecoveryLombok(AnnotationExpr annotation, String name) {
        return request.sourceRecovery() && !environment.hasAnyLombokClassInDependencies()
                && LombokCandidates.isLombok(annotation, name, sourceTypeNames);
    }

    void synthesisGap(Node node, String code) {
        var problem = new Diagnostic(DiagnosticSeverity.WARNING, code, "Generated-member evidence is incomplete",
                Optional.of(source(node).span(node)), Map.of());
        unit(node).diagnostics.add(problem);
        diagnostics.add(problem);
    }

    private void recordDirectTypes(List<TypeUseRecord> unitTypes) {
        for (var use : unitTypes) {
            use.owner().ifPresent(id -> directTypesByOwner.computeIfAbsent(id, k -> new ArrayList<>(2)).add(use));
        }
    }

    void derivedMemberTypes(Node input, Entity target, String role, String rule) {
        var inputId = entity(input).identity();
        var owned = directTypesByOwner.get(inputId);
        if (owned == null || owned.isEmpty())
            return;
        for (var use : List.copyOf(owned)) {
            var derivedUse = new TypeUseRecord(Optional.of(target.identity()), new RelationshipKind("java." + role),
                    use.span(), use.type(), use.variadic());
            types.add(derivedUse);
            directTypesByOwner.computeIfAbsent(target.identity(), k -> new ArrayList<>(2)).add(derivedUse);
            for (var id : use.type().referencedEntities())
                derive(target, declarations.get(id).entity(), role, input, rule);
        }
    }

    void derivedComponentTypes(Parameter component, Entity target, String role) {
        derivedMemberTypes(component, target, role, "java.record-component-type");
    }

    Entity enumConstructor(EnumConstantDeclaration constant) {
        var type = (EnumDeclaration) constant.getParentNode().orElseThrow();
        var selected = com.github.javaparser.resolution.logic.ConstructorResolutionLogic.findMostApplicable(
                type.resolve().getConstructors(),
                constant.getArguments().stream().map(Expression::calculateResolvedType).toList(), activeSolver);
        if (!selected.isSolved())
            throw new UnsolvedSymbolException("enum constructor");
        var target = callable(selected.getCorrespondingDeclaration());
        if (constant.getClassBody().isEmpty())
            return target;
        var parameters = new ArrayList<ErasedType>();
        for (int i = 0; i < selected.getCorrespondingDeclaration().getNumberOfParams(); i++)
            parameters.add(erased(selected.getCorrespondingDeclaration().getParam(i).getType(), new HashSet<>()));
        var anonymous = implicit(JavaSymbolName.constructor(typeOwnerName(constant), parameters),
                EntityKind.CONSTRUCTOR, enumBody(constant), constant, "java.enum-anonymous-constructor");
        derive(anonymous, target, "constructor-calls", constant, "java.enum-anonymous-super");
        return anonymous;
    }

    Entity typeUseOwner(Node node) {
        for (Node parent = node.getParentNode().orElse(null); parent != null; parent = parent.getParentNode()
                .orElse(null))
            if (parent instanceof Parameter parameter
                    && !(parameter.getParentNode().orElse(null) instanceof CallableDeclaration<?>
                            || parameter.getParentNode().orElse(null) instanceof RecordDeclaration))
                return owner(parameter);
        return owner(node);
    }

    void annotation(AnnotationExpr node, Node declaration, String site) {
        annotation(node, declaration, site, true);
    }

    void annotation(AnnotationExpr node, Node declaration, String site, boolean direct) {
        Supplier<Entity> source = declaration == null ? () -> typeUseOwner(node) : () -> entity(declaration);
        if (direct)
            observe(node, "annotated-with", () -> typeEntity(resolveAnnotation(node)), source);
        observe(node.getName(), "type-uses", () -> typeEntity(resolveAnnotation(node)), source);
        try {
            var entity = source.get();
            var symbol = declaration == null ? ownerName(node) : name(declaration);
            var key = JavaSymbolName.annotationUse(symbol, unit(node).input.document().identity(),
                    unit(node).source.start(node));
            annotations.add(new AnnotationUseRecord(key.canonicalName(), entity.identity(),
                    unit(node).source.span(node), site, unit(node).source.slice(unit(node).source.span(node))));
        } catch (RuntimeException failure) {
            /* Missing owner remains in the observation ledger. */ }
    }

    private ResolvedReferenceTypeDeclaration resolveAnnotation(AnnotationExpr node) {
        Object cached = unitAnnotationCache.get(node);
        if (cached != null) {
            if (cached instanceof ResolvedReferenceTypeDeclaration resolved)
                return resolved;
            throw (RuntimeException) cached;
        }
        try {
            ResolvedReferenceTypeDeclaration resolved = resolveAnnotationUncached(node);
            unitAnnotationCache.put(node, resolved);
            return resolved;
        } catch (RuntimeException failure) {
            unitAnnotationCache.put(node, failure);
            throw failure;
        }
    }

    private ResolvedReferenceTypeDeclaration resolveAnnotationUncached(AnnotationExpr node) {
        try {
            return node.resolve();
        } catch (ClassCastException sameNameAsEnclosingType) {
            String simple = node.getNameAsString();
            var cu = node.findCompilationUnit().orElse(null);
            if (cu != null) {
                for (var imp : cu.getImports()) {
                    if (!imp.isStatic() && !imp.isAsterisk() && imp.getName().getIdentifier().equals(simple)) {
                        var solved = activeSolver.tryToSolveType(imp.getNameAsString());
                        if (solved.isSolved())
                            return solved.getCorrespondingDeclaration();
                    }
                }
                for (var imp : cu.getImports()) {
                    if (!imp.isStatic() && imp.isAsterisk()) {
                        var solved = activeSolver.tryToSolveType(imp.getNameAsString() + "." + simple);
                        if (solved.isSolved())
                            return solved.getCorrespondingDeclaration();
                    }
                }
            }
            throw new UnsolvedSymbolException(simple);
        }
    }

    static SemanticStatus failureStatus(Throwable failure) {
        if (failure instanceof MappingFailure mapping)
            return mapping.status;
        if (failure instanceof UnsolvedSymbolException)
            return SemanticStatus.UNRESOLVED;
        if (failure instanceof UnsupportedOperationException)
            return SemanticStatus.UNSUPPORTED;
        if (providerInferenceCollision(failure))
            return SemanticStatus.UNSUPPORTED;
        return failure.getClass().getSimpleName().equals("MethodAmbiguityException") ? SemanticStatus.AMBIGUOUS
                : SemanticStatus.ERROR;
    }

    private static boolean providerInferenceCollision(Throwable failure) {
        // Pinned parser's name-keyed inference can conflate unrelated T parameters.
        // Recognize only its specific failure site; retain the observation as an
        // explicit provider capability gap, without choosing an equivalent type.
        if (!(failure instanceof IllegalStateException) || failure.getMessage() == null
                || !failure.getMessage().startsWith("Equivalent types are: "))
            return false;
        var frames = failure.getStackTrace();
        return frames.length > 0
                && frames[0].getClassName().equals("com.github.javaparser.resolution.logic.InferenceVariableType")
                && frames[0].getMethodName().equals("equivalentType");
    }

    private Unit unit(Node node) {
        var cu = node.findCompilationUnit()
                .orElseThrow(() -> new MappingFailure(SemanticStatus.ERROR, "java.source-membership"));
        var unit = units.get(cu);
        if (unit == null)
            throw new MappingFailure(SemanticStatus.ERROR, "java.source-membership");
        return unit;
    }

    private void unsupported(Node node, String category) {
        observe(node, category, () -> {
            throw new MappingFailure(SemanticStatus.UNSUPPORTED, "java.category-unsupported");
        }, () -> owner(node));
    }

    void observe(Node node, String category, Supplier<Entity> targetSupplier, Supplier<Entity> ownerSupplier) {
        if (Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException();
        var unit = unit(node);
        Optional<SourceSpan> span;
        Throwable spanFailure = null;
        try {
            span = Optional.of(unit.source.span(node));
        } catch (RuntimeException exception) {
            span = Optional.empty();
            spanFailure = exception;
        }
        Entity source = null, target = null;
        SemanticStatus status = SemanticStatus.RESOLVED;
        var local = new ArrayList<Diagnostic>();
        try {
            source = observableDeclaration(ownerSupplier.get());
            target = observableDeclaration(targetSupplier.get());
        } catch (UnsolvedSymbolException exception) {
            status = SemanticStatus.UNRESOLVED;
            local.add(diagnostic("java.unresolved", status, span));
        } catch (MappingFailure failure) {
            status = failure.status;
            local.add(diagnostic(failure.code, status, span));
        } catch (UnsupportedOperationException exception) {
            status = SemanticStatus.UNSUPPORTED;
            local.add(diagnostic("java.adapter-unsupported", status, span));
        } catch (RuntimeException | LinkageError | StackOverflowError exception) {
            boolean inferenceCollision = providerInferenceCollision(exception);
            status = inferenceCollision ? SemanticStatus.UNSUPPORTED
                    : exception.getClass().getSimpleName().equals("MethodAmbiguityException") ? SemanticStatus.AMBIGUOUS
                            : SemanticStatus.ERROR;
            local.add(new Diagnostic(
                    status == SemanticStatus.ERROR ? DiagnosticSeverity.ERROR : DiagnosticSeverity.WARNING,
                    inferenceCollision ? "java.parser-inference-collision"
                            : status == SemanticStatus.ERROR ? "java.adapter-error" : "java.ambiguous",
                    "Attribution did not produce a safely mapped fact", span,
                    Map.of("exception", exception.getClass().getName())));
        }
        if (span.isEmpty())
            local.add(missingSpanDiagnostic(node, spanFailure));
        Optional<OccurrenceIdentity> mapped = Optional.empty();
        String reference = span.map(s -> compactSourceSlice(node, unit.source.slice(s))).orElse("");
        if (source != null && span.isPresent() && status != SemanticStatus.AMBIGUOUS
                && (target != null || !category.equals("declares"))) {
            RelationshipTarget targetState = target == null
                    ? new RelationshipTarget.Unresolved(
                            "java:source:" + unit.input.document().identity().value() + ":" + unit.source.start(node))
                    : new RelationshipTarget.Resolved(target.identity());
            var relation = SemanticRelationship.create(source.identity(), new RelationshipKind("java." + category),
                    targetState);
            var uncertainty = status == SemanticStatus.RESOLVED ? List.<Uncertainty>of()
                    : List.of(new Uncertainty("java.attribution", "Attribution remains incomplete",
                            List.of("verified declaration binding")));
            var occurrence = RelationshipOccurrence.create(relation, span.orElseThrow(), 0, status, DIRECT, uncertainty,
                    local);
            if (occurrences.putIfAbsent(occurrence.identity(), occurrence) == null) {
                mapped = Optional.of(occurrence.identity());
                if (activeUnitOccurrences != null)
                    activeUnitOccurrences.add(occurrence);
                if (category.equals("calls") && status == SemanticStatus.RESOLVED) {
                    resolvedCallsByDoc
                            .computeIfAbsent(unit.input.document().identity(), k -> new HashMap<>())
                            .put(occurrence.span(), occurrence);
                }
            } else
                local.add(diagnostic("java.duplicate-fact", SemanticStatus.ERROR, span));
        }
        if (mapped.isEmpty() && local.isEmpty())
            local.add(diagnostic("java.mapping", SemanticStatus.ERROR, span));
        unit.diagnostics.addAll(local);
        diagnostics.addAll(local);
        observations.add(new ObservationRecord(unit.input.document().identity(),
                new RelationshipKind("java." + category), span, status,
                target != null ? ObservationRecord.EvidenceState.VERIFIED : ObservationRecord.EvidenceState.MISSING,
                span.isPresent() ? ObservationRecord.EvidenceState.VERIFIED : ObservationRecord.EvidenceState.MISSING,
                mapped, reference, local));
    }

    int reextractedUnitCount() {
        return reextractedUnitCount;
    }

    private void collectDeclarationIfPresent(
            List<DeclarationRecord> list, Set<EntityIdentity> seen, EntityIdentity id) {
        if (id != null && seen.add(id)) {
            var rec = declarations.get(id);
            if (rec != null)
                list.add(rec);
        }
    }

    private Entity observableDeclaration(Entity entity) {
        if (entity != null && duplicateDeclarations.contains(entity.identity()))
            throw new MappingFailure(SemanticStatus.ERROR, "java.duplicate-declaration");
        return entity;
    }

    private static Diagnostic diagnostic(String code, SemanticStatus status, Optional<SourceSpan> span) {
        return new Diagnostic(status == SemanticStatus.ERROR ? DiagnosticSeverity.ERROR : DiagnosticSeverity.WARNING,
                code,
                "Semantic observation requires additional evidence or support", span, Map.of());
    }

    static Diagnostic missingSpanDiagnostic(Node node, Throwable failure) {
        return new Diagnostic(DiagnosticSeverity.ERROR, "java.missing-span",
                "Semantic observation has no verified source coordinates; the deterministic AST path preserves its denominator entry",
                Optional.empty(), Map.of("astPath", astPath(node), "nodeType", node.getClass().getName(),
                        "failure", failure == null ? "unknown" : failure.getClass().getName()));
    }

    static String astPath(Node node) {
        var indexes = new ArrayList<Integer>();
        Node child = node;
        while (child.getParentNode().isPresent()) {
            Node parent = child.getParentNode().orElseThrow();
            int index = -1;
            var children = parent.getChildNodes();
            for (int candidate = 0; candidate < children.size(); candidate++) {
                if (children.get(candidate) == child) {
                    index = candidate;
                    break;
                }
            }
            if (index < 0)
                throw new IllegalArgumentException("AST parent does not own child");
            indexes.add(index);
            child = parent;
        }
        Collections.reverse(indexes);
        return child.getClass().getName() + indexes.stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining("/", "/", ""));
    }
}
