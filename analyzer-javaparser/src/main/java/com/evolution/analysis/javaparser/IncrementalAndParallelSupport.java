package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.source.SourceSpan;
import com.evolution.analysis.frontend.*;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

/**
 * Shared support for:
 * <ul>
 *   <li>Race-free parallel Pass 1 parsing with {@link ThreadLocal} {@link JavaParser}s and global worker permits</li>
 *   <li>Unit declaration partitioning (eliminating stale peer {@link SourceSpan} caching)</li>
 *   <li>O(k) Declaration-Span Rebinding for body-only line shifts</li>
 *   <li>Deterministic {@link FrontendResult} assembly from merged {@link ModuleIncrementalCache.UnitEntry}s</li>
 * </ul>
 */
final class IncrementalAndParallelSupport {
    private static final int MAX_INTRA_WORKERS =
            Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), 8));
    private static final Semaphore INTRA_PERMITS = new Semaphore(MAX_INTRA_WORKERS);

    private IncrementalAndParallelSupport() {}

    static boolean isParallelEnabled() {
        String prop = System.getProperty("evo.perf.parallelModuleExtraction");
        return prop == null || !"false".equalsIgnoreCase(prop);
    }

    static int parallelThreshold() {
        return Math.max(2, Integer.getInteger("evo.perf.parallelThreshold", 32));
    }

    static int acquireExtraPermits(int itemCount) {
        if (!isParallelEnabled() || itemCount < parallelThreshold()) return 0;
        int desired = Math.min(MAX_INTRA_WORKERS - 1, Math.max(1, itemCount / 16));
        int acquired = 0;
        for (int i = 0; i < desired; i++) {
            if (INTRA_PERMITS.tryAcquire()) {
                acquired++;
            } else {
                break;
            }
        }
        return acquired;
    }

    static void releaseExtraPermits(int permits) {
        if (permits > 0) {
            INTRA_PERMITS.release(permits);
        }
    }

    record RawParsedSource(
            SourceInput input,
            UnicodeSource translated,
            CompilationUnit ast,
            Diagnostic errorDiagnostic,
            SourceOutcome errorOutcome) {
        boolean isSuccess() {
            return ast != null;
        }
    }

    /**
     * Parses Java/Kotlin source inputs concurrently across {@code extraPermits + 1} threads using strictly
     * thread-local {@link JavaParser} instances (zero shared parser or solver state).
     * Results are returned in exact original input index order {@code 0..N-1}.
     */
    static RawParsedSource[] parseSourcesInParallel(
            List<SourceInput> sources,
            ParserConfiguration.LanguageLevel languageLevel,
            int extraPermits) {
        int n = sources.size();
        RawParsedSource[] results = new RawParsedSource[n];
        if (extraPermits <= 0 || n < 2) {
            JavaParser parser = newRawParser(languageLevel);
            for (int i = 0; i < n; i++) {
                if (Thread.currentThread().isInterrupted()) throw new CancellationException();
                results[i] = parseSingleRaw(parser, sources.get(i));
            }
            return results;
        }
        int totalWorkers = Math.min(extraPermits + 1, n);
        AtomicInteger nextIndex = new AtomicInteger(0);
        ThreadLocal<JavaParser> localParser = ThreadLocal.withInitial(() -> newRawParser(languageLevel));
        var pool = Executors.newFixedThreadPool(totalWorkers - 1, r -> {
            Thread t = new Thread(r, "evo-jp-p1");
            t.setDaemon(true);
            return t;
        });
        List<Future<?>> futures = new ArrayList<>(totalWorkers - 1);
        Runnable workerTask = () -> {
            try {
                JavaParser parser = localParser.get();
                while (true) {
                    if (Thread.currentThread().isInterrupted()) throw new CancellationException();
                    int idx = nextIndex.getAndIncrement();
                    if (idx >= n) break;
                    results[idx] = parseSingleRaw(parser, sources.get(idx));
                }
            } finally {
                localParser.remove();
            }
        };
        try {
            for (int w = 0; w < totalWorkers - 1; w++) {
                futures.add(pool.submit(workerTask));
            }
            // Current thread participates so zero threads sit idle waiting!
            workerTask.run();
            for (Future<?> f : futures) {
                f.get();
            }
            return results;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("JAVAPARSER_PASS1_CANCELLED");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException rt) throw rt;
            if (failed.getCause() instanceof Error err) throw err;
            throw new IllegalStateException("JAVAPARSER_PASS1_FAILED", failed.getCause());
        } finally {
            for (Future<?> f : futures) f.cancel(true);
            pool.shutdownNow();
            awaitTerminationQuietly(pool);
        }
    }

    static JavaParser newRawParser(ParserConfiguration.LanguageLevel level) {
        return new JavaParser(new ParserConfiguration()
                .setLanguageLevel(level)
                .setTabSize(1)
                .setStoreTokens(true)
                .setAttributeComments(false)
                .setPreprocessUnicodeEscapes(false));
    }

    static RawParsedSource parseSingleRaw(JavaParser parser, SourceInput input) {
        if (input.document().path().endsWith(".kt")) {
            UnicodeSource ktSource;
            try {
                ktSource = UnicodeSource.translate(input.text());
            } catch (IllegalArgumentException ignored) {
                ktSource = UnicodeSource.translate(input.text().replace("\\u", "\\\\u"));
            }
            CompilationUnit ast = KotlinAstLowering.lower(input.document().path(), ktSource, null);
            Extraction.detachTokensAndNormalizeIdentifiers(ast, false);
            return new RawParsedSource(input, ktSource, ast, null, null);
        }
        UnicodeSource translated;
        try {
            translated = UnicodeSource.translate(input.text());
        } catch (IllegalArgumentException invalid) {
            var diag = new Diagnostic(
                    DiagnosticSeverity.ERROR,
                    "java.unicode-escape-error",
                    "Invalid eligible Unicode escape",
                    Optional.empty(),
                    Map.of("document", input.document().identity().value()));
            return new RawParsedSource(
                    input, null, null, diag,
                    new SourceOutcome(input.document().identity(), SourceOutcome.State.ERROR, List.of(diag)));
        }
        parser.getParserConfiguration().setPreprocessUnicodeEscapes(false);
        var parsed = parser.parse(translated.text);
        if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
            var diag = new Diagnostic(
                    DiagnosticSeverity.ERROR,
                    "java.parse-error",
                    "Source syntax could not be parsed; recovered bindings are withheld",
                    Optional.empty(),
                    Map.of("document", input.document().identity().value()));
            return new RawParsedSource(
                    input, null, null, diag,
                    new SourceOutcome(input.document().identity(), SourceOutcome.State.ERROR, List.of(diag)));
        }
        CompilationUnit ast = parsed.getResult().orElseThrow();
        Extraction.detachTokensAndNormalizeIdentifiers(ast, true);
        return new RawParsedSource(input, translated, ast, null, null);
    }

    /**
     * Runs work-stealing parallel tasks across {@code extraPermits + 1} threads, calling {@code workerRunner}
     * once per thread with a shared {@link AtomicInteger} work index counter.
     */
    static void runWorkStealing(int itemCount, int extraPermits, java.util.function.Consumer<AtomicInteger> workerRunner) {
        var nextIndex = new AtomicInteger(0);
        if (extraPermits <= 0 || itemCount < 2) {
            workerRunner.accept(nextIndex);
            return;
        }
        int totalWorkers = Math.min(extraPermits + 1, itemCount);
        var pool = Executors.newFixedThreadPool(totalWorkers - 1, r -> {
            Thread t = new Thread(r, "evo-jp-p2");
            t.setDaemon(true);
            return t;
        });
        List<Future<?>> futures = new ArrayList<>(totalWorkers - 1);
        try {
            for (int w = 0; w < totalWorkers - 1; w++) {
                futures.add(pool.submit(() -> workerRunner.accept(nextIndex)));
            }
            workerRunner.accept(nextIndex);
            for (Future<?> f : futures) {
                f.get();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("JAVAPARSER_PASS2_CANCELLED");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException rt) throw rt;
            if (failed.getCause() instanceof Error err) throw err;
            throw new IllegalStateException("JAVAPARSER_PASS2_FAILED", failed.getCause());
        } finally {
            for (Future<?> f : futures) f.cancel(true);
            pool.shutdownNow();
            awaitTerminationQuietly(pool);
        }
    }

    private static void awaitTerminationQuietly(ExecutorService pool) {
        boolean interrupted = Thread.interrupted();
        try {
            while (!pool.isTerminated()) {
                try {
                    pool.awaitTermination(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException again) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /**
     * Partitions declarations touched while extracting a single unit into:
     * <ul>
     *   <li>{@code ownDeclarations}: entities declared in {@code docId} + package decl + derived/implicit decls owned by {@code docId}</li>
     *   <li>{@code reactorDeclarations}: upstream reactor declarations referenced by this unit</li>
     *   <li>{@code externalDeclarations}: immutable JDK/dependency declarations referenced by this unit</li>
     *   <li>{@code referencedPrimaryPeerIds}: IDs of peer declarations in OTHER primary files (never caching their {@link SourceSpan}!)</li>
     * </ul>
     */
    record PartitionedDeclarations(
            List<DeclarationRecord> ownDeclarations,
            List<DeclarationRecord> reactorDeclarations,
            List<DeclarationRecord> externalDeclarations,
            Set<EntityIdentity> referencedPrimaryPeerIds) {}

    static PartitionedDeclarations partitionUnitDeclarations(
            SourceDocumentIdentity docId,
            ModuleIdentity owningModule,
            Set<EntityIdentity> touchedIds,
            Map<EntityIdentity, DeclarationRecord> allDeclarations) {
        EntityScope ownScope = EntityScope.project(owningModule);
        var ownById = new LinkedHashMap<EntityIdentity, DeclarationRecord>();
        var ownEntityIdValues = new HashSet<String>();
        var deferredImplicit = new ArrayList<DeclarationRecord>();
        var reactorDecls = new LinkedHashMap<EntityIdentity, DeclarationRecord>();
        var externalDecls = new LinkedHashMap<EntityIdentity, DeclarationRecord>();
        var peerPrimaryIds = new LinkedHashSet<EntityIdentity>();

        // First pass: classify declarations with explicit spans, external origin, reactor scope, or PACKAGE kind.
        for (EntityIdentity id : touchedIds) {
            DeclarationRecord rec = allDeclarations.get(id);
            if (rec == null) continue;
            Entity entity = rec.entity();
            if (entity.origin() != EntityOrigin.PROJECT) {
                externalDecls.putIfAbsent(id, rec);
            } else if (!entity.stableScope().equals(ownScope)) {
                reactorDecls.putIfAbsent(id, rec);
            } else if (entity.declaration().isPresent()) {
                if (entity.declaration().orElseThrow().document().equals(docId)) {
                    ownById.putIfAbsent(id, rec);
                    ownEntityIdValues.add(id.value());
                } else {
                    peerPrimaryIds.add(id);
                }
            } else if (entity.kind() == EntityKind.PACKAGE
                    || "lombok.logger-call".equals(rec.derivation().method().id())) {
                ownById.putIfAbsent(id, rec);
                ownEntityIdValues.add(id.value());
            } else {
                deferredImplicit.add(rec);
            }
        }

        // Second pass: classify span-less implicit/Lombok/record/native declarations in owningModule
        // by checking whether their derivation input entities belong to THIS document or a peer document.
        boolean progressed = true;
        var remaining = deferredImplicit;
        while (progressed && !remaining.isEmpty()) {
            progressed = false;
            var nextRemaining = new ArrayList<DeclarationRecord>();
            for (DeclarationRecord rec : remaining) {
                List<String> inputs = rec.derivation().inputIdentities();
                boolean ownedHere = false;
                boolean ownedElsewhere = false;
                for (String inp : inputs) {
                    if (ownEntityIdValues.contains(inp)) {
                        ownedHere = true;
                        break;
                    }
                    if (!inp.startsWith("entity:")) continue;
                    DeclarationRecord inpRec = allDeclarations.get(new EntityIdentity(inp));
                    if (inpRec != null && inpRec.entity().declaration().isPresent()) {
                        if (inpRec.entity().declaration().orElseThrow().document().equals(docId)) {
                            ownedHere = true;
                            break;
                        } else {
                            ownedElsewhere = true;
                        }
                    }
                }
                if (ownedHere) {
                    ownById.putIfAbsent(rec.entity().identity(), rec);
                    ownEntityIdValues.add(rec.entity().identity().value());
                    progressed = true;
                } else if (ownedElsewhere) {
                    peerPrimaryIds.add(rec.entity().identity());
                } else {
                    nextRemaining.add(rec);
                }
            }
            remaining = nextRemaining;
        }
        // Any remaining unresolved implicit declaration (e.g. from peer chain) goes to own if it has no peer input,
        // or peer if its inputs aren't owned here.
        for (DeclarationRecord rec : remaining) {
            peerPrimaryIds.add(rec.entity().identity());
        }

        return new PartitionedDeclarations(
                List.copyOf(ownById.values()),
                List.copyOf(reactorDecls.values()),
                List.copyOf(externalDecls.values()),
                Collections.unmodifiableSet(peerPrimaryIds));
    }

    /**
     * Collects every {@link EntityIdentity} referenced by a unit's extracted records so the declaration
     * partition and invariant self-check cover 100% of {@link FrontendResult} referential invariants.
     */
    static void collectAllReferencedEntityIds(
            Set<EntityIdentity> out,
            Collection<RelationshipOccurrence> occurrences,
            Collection<TypeUseRecord> types,
            Collection<AnnotationUseRecord> annotations,
            Collection<DerivedRelationshipRecord> derived,
            Collection<TypeDeclarationRecord> shapes,
            Collection<MemberDeclarationRecord> members,
            Collection<LombokConstructorCandidate> candidates,
            Collection<HttpClientCallRecord> httpCalls) {
        for (var occ : occurrences) {
            out.add(occ.relationship().source());
            if (occ.relationship().target() instanceof RelationshipTarget.Resolved r) {
                out.add(r.target());
            } else if (occ.relationship().target() instanceof RelationshipTarget.Candidates c) {
                out.addAll(c.candidates());
            }
        }
        for (var t : types) {
            t.owner().ifPresent(out::add);
            out.addAll(t.type().referencedEntities());
        }
        for (var a : annotations) {
            out.add(a.owner());
        }
        for (var d : derived) {
            out.add(d.relationship().source());
            if (d.relationship().target() instanceof RelationshipTarget.Resolved r) {
                out.add(r.target());
            } else if (d.relationship().target() instanceof RelationshipTarget.Candidates c) {
                out.addAll(c.candidates());
            }
            for (String inp : d.derivation().inputIdentities()) {
                if (inp.startsWith("entity:")) out.add(new EntityIdentity(inp));
            }
        }
        for (var s : shapes) out.add(s.type());
        for (var m : members) out.add(m.member());
        for (var c : candidates) {
            out.add(c.owner());
            out.add(c.field());
            out.addAll(c.type().referencedEntities());
        }
        for (var h : httpCalls) {
            out.add(h.caller());
            out.add(h.clientMethod());
        }
    }

    /**
     * Rebinds cached upstream reactor {@link DeclarationRecord}s in O(k) when an upstream reactor file
     * had a body-only edit shifting source line numbers while keeping its ABI identical.
     * Returns {@code null} if a shifted reactor declaration cannot be safely rebound (triggering safe fallback).
     */
    static List<DeclarationRecord> rebindReactorDeclarations(
            List<DeclarationRecord> cachedReactorDecls,
            Set<SourceDocumentIdentity> shiftedReactorDocs,
            Map<EntityIdentity, DeclarationRecord> freshReactorDeclarations,
            AtomicInteger reboundCounter) {
        if (cachedReactorDecls.isEmpty() || shiftedReactorDocs.isEmpty()) {
            return cachedReactorDecls;
        }
        List<DeclarationRecord> updated = null;
        for (int i = 0; i < cachedReactorDecls.size(); i++) {
            DeclarationRecord rec = cachedReactorDecls.get(i);
            var spanOpt = rec.entity().declaration();
            boolean belongsToShifted = spanOpt.isPresent() && shiftedReactorDocs.contains(spanOpt.orElseThrow().document());
            DeclarationRecord fresh = freshReactorDeclarations.get(rec.entity().identity());
            if (fresh != null) {
                if (!fresh.equals(rec)) {
                    if (updated == null) updated = new ArrayList<>(cachedReactorDecls);
                    updated.set(i, fresh);
                    reboundCounter.incrementAndGet();
                }
            } else if (belongsToShifted) {
                // Referenced reactor declaration disappeared or changed identity -> trigger safe fallback!
                return null;
            }
        }
        return updated == null ? cachedReactorDecls : Collections.unmodifiableList(updated);
    }

    /**
     * Assembles a deterministic {@link FrontendResult} from ordered {@link ModuleIncrementalCache.UnitEntry}s
     * and verifies 100% of peer-entity referential invariants. Returns {@link Optional#empty()} if any
     * referenced project peer entity is missing (signaling a safe cold-extraction fallback).
     */
    static Optional<FrontendResult> assembleFromUnitEntries(
            FrontendRequest request,
            VersionedIdentifier version,
            boolean fastArchitecture,
            boolean reactorResolutionDegraded,
            Collection<ModuleIncrementalCache.UnitEntry> orderedEntries,
            List<SourceDocumentIdentity> reactorDocIds,
            Collection<Diagnostic> globalDiagnostics) {
        var declarations = new TreeMap<EntityIdentity, DeclarationRecord>();
        var occurrences = new TreeMap<OccurrenceIdentity, RelationshipOccurrence>();
        var observations = new ArrayList<ObservationRecord>();
        var types = new ArrayList<TypeUseRecord>();
        var annotations = new ArrayList<AnnotationUseRecord>();
        var derived = new TreeSet<DerivedRelationshipRecord>();
        var shapes = new ArrayList<TypeDeclarationRecord>();
        var members = new ArrayList<MemberDeclarationRecord>();
        var candidates = new ArrayList<LombokConstructorCandidate>();
        var httpCalls = new ArrayList<HttpClientCallRecord>();
        var diagnostics = new TreeSet<Diagnostic>(globalDiagnostics);
        var outcomesByDoc = new LinkedHashMap<SourceDocumentIdentity, SourceOutcome>();

        // Pass 1: install ALL ownDeclarations first (guaranteed fresh SourceSpans for every primary file!)
        for (var entry : orderedEntries) {
            for (var d : entry.ownDeclarations()) {
                declarations.put(d.entity().identity(), d);
            }
        }
        // Pass 2: install reactorDeclarations and externalDeclarations
        for (var entry : orderedEntries) {
            for (var d : entry.reactorDeclarations()) {
                declarations.putIfAbsent(d.entity().identity(), d);
            }
            for (var d : entry.externalDeclarations()) {
                declarations.putIfAbsent(d.entity().identity(), d);
            }
        }
        // Self-check invariant: EVERY referenced primary peer entity MUST be present in fresh ownDeclarations!
        for (var entry : orderedEntries) {
            for (var peerId : entry.referencedPrimaryPeerIds()) {
                if (!declarations.containsKey(peerId)) {
                    return Optional.empty();
                }
            }
        }
        // Also verify all derivation input entities exist (e.g., Lombok derived relationships pointing to own/peer entities)
        for (var entry : orderedEntries) {
            for (var o : entry.occurrences()) occurrences.putIfAbsent(o.identity(), o);
            observations.addAll(entry.observations());
            types.addAll(entry.types());
            annotations.addAll(entry.annotations());
            derived.addAll(entry.derived());
            shapes.addAll(entry.shapes());
            members.addAll(entry.members());
            candidates.addAll(entry.constructorCandidates());
            httpCalls.addAll(entry.httpClientCalls());
            diagnostics.addAll(entry.unitDiagnostics());
            var state = entry.unitDiagnostics().isEmpty() ? SourceOutcome.State.PROCESSED : SourceOutcome.State.PARTIAL;
            outcomesByDoc.put(entry.document(), new SourceOutcome(entry.document(), state, List.copyOf(entry.unitDiagnostics())));
        }
        for (var rDoc : reactorDocIds) {
            outcomesByDoc.putIfAbsent(rDoc, new SourceOutcome(rDoc, SourceOutcome.State.PROCESSED, List.of()));
        }

        // Verify all referenced entities across occurrences/types/annotations/derived exist before constructing FrontendResult
        var allReferenced = new HashSet<EntityIdentity>();
        collectAllReferencedEntityIds(allReferenced, occurrences.values(), types, annotations, derived, shapes, members, candidates, httpCalls);
        for (var refId : allReferenced) {
            if (!declarations.containsKey(refId)) {
                return Optional.empty();
            }
        }

        var outcomes = new ArrayList<>(outcomesByDoc.values());
        var seenObs = new HashSet<ObservationRecord>(Math.max(16, observations.size() * 2));
        var distinctObservations = new ArrayList<ObservationRecord>(observations.size());
        for (var obs : observations) {
            if (seenObs.add(obs)) distinctObservations.add(obs);
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
            return new CategoryCoverage(
                    kind,
                    !fastArchitecture && c.equals("calls") ? CategoryCoverage.Support.IMPLEMENTED : CategoryCoverage.Support.PARTIAL,
                    attempted, emitted, attempted - emitted);
        }).toList();

        var distinctTypes = new ArrayList<TypeUseRecord>(new TreeSet<>(types));
        var distinctAnnotations = new ArrayList<AnnotationUseRecord>(new TreeSet<>(annotations));
        var shapesByType = new LinkedHashMap<EntityIdentity, TypeDeclarationRecord>();
        for (var s : shapes) shapesByType.putIfAbsent(s.type(), s);
        var distinctShapes = new ArrayList<TypeDeclarationRecord>(new TreeSet<>(shapesByType.values()));
        var membersById = new LinkedHashMap<EntityIdentity, MemberDeclarationRecord>();
        for (var m : members) membersById.putIfAbsent(m.member(), m);
        var distinctMembers = new ArrayList<MemberDeclarationRecord>(new TreeSet<>(membersById.values()));
        var distinctCandidates = new ArrayList<LombokConstructorCandidate>(new TreeSet<>(candidates));
        var distinctHttp = new ArrayList<HttpClientCallRecord>(new TreeSet<>(httpCalls));

        try {
            return Optional.of(new FrontendResult(
                    request.manifest().identity(),
                    version,
                    !fastArchitecture && !request.sourceRecovery() && !reactorResolutionDegraded
                            && outcomes.stream().allMatch(s -> s.state() == SourceOutcome.State.PROCESSED)
                            ? FrontendResult.State.COMPLETED : FrontendResult.State.PARTIAL,
                    List.copyOf(declarations.values()),
                    List.copyOf(occurrences.values()),
                    distinctObservations,
                    outcomes,
                    coverage,
                    List.copyOf(diagnostics),
                    distinctTypes,
                    distinctAnnotations,
                    List.copyOf(derived),
                    distinctShapes,
                    distinctMembers,
                    distinctCandidates,
                    distinctHttp));
        } catch (IllegalArgumentException invariantMismatch) {
            return Optional.empty();
        }
    }
}
