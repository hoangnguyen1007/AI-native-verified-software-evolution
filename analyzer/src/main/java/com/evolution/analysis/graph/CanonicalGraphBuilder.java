package com.evolution.analysis.graph;

import com.evolution.analysis.contract.analysis.AnalysisManifest;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.ingestion.*;
import com.evolution.analysis.spring.binding.*;
import com.evolution.analysis.spring.registration.*;
import com.evolution.analysis.spring.truth.*;
import com.evolution.analysis.evidence.*;
import java.util.*;
import static com.evolution.analysis.graph.KnowledgeGraph.*;

/**
 * Projects neutral provider outputs; never parses source or runs target code.
 */
public final class CanonicalGraphBuilder {
    static final int MAX_CHUNK_ITEMS = 256;
    static final int MAX_CHUNK_UTF8_BYTES = 1_048_576;
    static final int INLINE_PAYLOAD_LIMIT_BYTES = 512_000;
    static final int INLINE_FRONTEND_INDEX_REFS = 2_048;
    static final int MAX_RAW_BLOB_CHUNK_BYTES = 700_000;

    public KnowledgeGraph build(AnalysisManifest manifest, FrontendResult frontend) {
        return build(manifest, frontend, List.of(), false);
    }

    public KnowledgeGraph build(AnalysisManifest manifest, FrontendResult frontend, boolean fastArchitecture) {
        return build(manifest, frontend, List.of(), fastArchitecture);
    }

    public KnowledgeGraph build(AnalysisManifest manifest, FrontendResult frontend,
            List<CanonicalGraphInput.SpringSlice> spring) {
        return build(manifest, frontend, spring, List.of(), false);
    }

    public KnowledgeGraph build(AnalysisManifest manifest, FrontendResult frontend,
            List<CanonicalGraphInput.SpringSlice> spring, boolean fastArchitecture) {
        return build(manifest, frontend, spring, List.of(), fastArchitecture);
    }

    public KnowledgeGraph build(AnalysisManifest manifest, FrontendResult frontend,
            List<CanonicalGraphInput.SpringSlice> spring,
            Collection<EvidenceAcquisitionLedger> ledgers) {
        return build(manifest, frontend, spring, ledgers, false);
    }

    public KnowledgeGraph build(AnalysisManifest manifest, FrontendResult frontend,
            List<CanonicalGraphInput.SpringSlice> spring,
            Collection<EvidenceAcquisitionLedger> ledgers, boolean fastArchitecture) {
        return build(manifest, frontend, spring, ledgers, fastArchitecture, null);
    }

    public KnowledgeGraph build(AnalysisManifest manifest, FrontendResult frontend,
            List<CanonicalGraphInput.SpringSlice> spring,
            Collection<EvidenceAcquisitionLedger> ledgers, boolean fastArchitecture,
            IncrementalGraphProjector projector) {
        return build(manifest, frontend, spring, ledgers, fastArchitecture, projector, null);
    }

    public KnowledgeGraph build(AnalysisManifest manifest, FrontendResult frontend,
            List<CanonicalGraphInput.SpringSlice> spring,
            Collection<EvidenceAcquisitionLedger> ledgers, boolean fastArchitecture,
            IncrementalGraphProjector projector, ContentDigest polyglotProjectionDigest) {
        boolean fast = fastArchitecture
                || "FAST_ARCHITECTURE".equals(manifest.configuration().values().get("analysis.profile"));
        java.util.function.Function<String, ContentDigest> polyFn = polyglotProjectionDigest == null
                ? null : ignored -> polyglotProjectionDigest;
        var projection = new Projection(manifest.snapshot().identity(), GraphProjectionStore.memory(), fast, projector,
                polyFn);
        projection.frontend(manifest, frontend, springContextDigestFor(manifest.identity(), spring, null), ".");
        spring.forEach(projection::spring);
        ledgers.forEach(projection::ledger);
        return projection.finish();
    }

    public KnowledgeGraph build(CanonicalGraphInput input) {
        return build(input, List.of(), false);
    }

    public KnowledgeGraph build(CanonicalGraphInput input, boolean fastArchitecture) {
        return build(input, List.of(), fastArchitecture);
    }

    public KnowledgeGraph build(CanonicalGraphInput input, Collection<EvidenceAcquisitionLedger> ledgers) {
        return build(input, ledgers, false);
    }

    public KnowledgeGraph build(CanonicalGraphInput input, Collection<EvidenceAcquisitionLedger> ledgers,
            boolean fastArchitecture) {
        return build(input, ledgers, fastArchitecture, null);
    }

    public KnowledgeGraph build(CanonicalGraphInput input, Collection<EvidenceAcquisitionLedger> ledgers,
            boolean fastArchitecture, IncrementalGraphProjector projector) {
        return build(input, ledgers, fastArchitecture, projector, (java.util.function.Function<String, ContentDigest>) null);
    }

    public KnowledgeGraph build(CanonicalGraphInput input, Collection<EvidenceAcquisitionLedger> ledgers,
            boolean fastArchitecture, IncrementalGraphProjector projector, ContentDigest polyglotProjectionDigest) {
        return build(input, ledgers, fastArchitecture, projector,
                polyglotProjectionDigest == null ? null : ignored -> polyglotProjectionDigest);
    }

    public KnowledgeGraph build(CanonicalGraphInput input, Collection<EvidenceAcquisitionLedger> ledgers,
            boolean fastArchitecture, IncrementalGraphProjector projector,
            java.util.function.Function<String, ContentDigest> modulePolyglotDigest) {
        return project(input, ledgers, GraphProjectionStore.memory(), fastArchitecture, projector,
                modulePolyglotDigest).finish();
    }

    public KnowledgeGraph build(CanonicalGraphInput input, Collection<EvidenceAcquisitionLedger> ledgers,
            GraphProjectionStore.Contribution contribution) throws java.io.IOException {
        return build(input, ledgers, contribution, false);
    }

    public KnowledgeGraph build(CanonicalGraphInput input, Collection<EvidenceAcquisitionLedger> ledgers,
            GraphProjectionStore.Contribution contribution, boolean fastArchitecture) throws java.io.IOException {
        return build(input, ledgers, contribution, fastArchitecture, null);
    }

    public KnowledgeGraph build(CanonicalGraphInput input, Collection<EvidenceAcquisitionLedger> ledgers,
            GraphProjectionStore.Contribution contribution, boolean fastArchitecture,
            IncrementalGraphProjector projector) throws java.io.IOException {
        return build(input, ledgers, contribution, fastArchitecture, projector,
                (java.util.function.Function<String, ContentDigest>) null);
    }

    public KnowledgeGraph build(CanonicalGraphInput input, Collection<EvidenceAcquisitionLedger> ledgers,
            GraphProjectionStore.Contribution contribution, boolean fastArchitecture,
            IncrementalGraphProjector projector, ContentDigest polyglotProjectionDigest) throws java.io.IOException {
        return build(input, ledgers, contribution, fastArchitecture, projector,
                polyglotProjectionDigest == null ? null : ignored -> polyglotProjectionDigest);
    }

    public KnowledgeGraph build(CanonicalGraphInput input, Collection<EvidenceAcquisitionLedger> ledgers,
            GraphProjectionStore.Contribution contribution, boolean fastArchitecture,
            IncrementalGraphProjector projector,
            java.util.function.Function<String, ContentDigest> modulePolyglotDigest) throws java.io.IOException {
        var store = GraphProjectionStore.memory();
        var p = project(input, ledgers, store, fastArchitecture, projector, modulePolyglotDigest);
        contribution.project(store);
        return p.finish();
    }

    /**
     * Assemble identical canonical rows without constructing or indexing a
     * KnowledgeGraph.
     */
    public void project(CanonicalGraphInput input, GraphProjectionStore store) {
        project(input, store, List.of(), false);
    }

    public void project(CanonicalGraphInput input, GraphProjectionStore store, boolean fastArchitecture) {
        project(input, store, List.of(), fastArchitecture);
    }

    public void project(CanonicalGraphInput input, GraphProjectionStore store,
            Collection<EvidenceAcquisitionLedger> ledgers) {
        project(input, store, ledgers, false);
    }

    public void project(CanonicalGraphInput input, GraphProjectionStore store,
            Collection<EvidenceAcquisitionLedger> ledgers, boolean fastArchitecture) {
        project(input, store, ledgers, fastArchitecture, null);
    }

    public void project(CanonicalGraphInput input, GraphProjectionStore store,
            Collection<EvidenceAcquisitionLedger> ledgers, boolean fastArchitecture,
            IncrementalGraphProjector projector) {
        project(input, store, ledgers, fastArchitecture, projector,
                (java.util.function.Function<String, ContentDigest>) null);
    }

    public void project(CanonicalGraphInput input, GraphProjectionStore store,
            Collection<EvidenceAcquisitionLedger> ledgers, boolean fastArchitecture,
            IncrementalGraphProjector projector, ContentDigest polyglotProjectionDigest) {
        project(input, store, ledgers, fastArchitecture, projector,
                polyglotProjectionDigest == null ? null : ignored -> polyglotProjectionDigest);
    }

    public void project(CanonicalGraphInput input, GraphProjectionStore store,
            Collection<EvidenceAcquisitionLedger> ledgers, boolean fastArchitecture,
            IncrementalGraphProjector projector,
            java.util.function.Function<String, ContentDigest> modulePolyglotDigest) {
        project(input, ledgers, store, fastArchitecture, projector, modulePolyglotDigest).complete();
    }

    public void buildInto(CanonicalGraphInput input, GraphProjectionStore store,
            Collection<EvidenceAcquisitionLedger> ledgers, boolean fastArchitecture) {
        project(input, store, ledgers, fastArchitecture, null, (java.util.function.Function<String, ContentDigest>) null);
    }

    public void buildInto(CanonicalGraphInput input, GraphProjectionStore store,
            Collection<EvidenceAcquisitionLedger> ledgers, boolean fastArchitecture,
            IncrementalGraphProjector projector) {
        project(input, store, ledgers, fastArchitecture, projector, (java.util.function.Function<String, ContentDigest>) null);
    }

    public void buildInto(CanonicalGraphInput input, GraphProjectionStore store,
            Collection<EvidenceAcquisitionLedger> ledgers, boolean fastArchitecture,
            IncrementalGraphProjector projector, ContentDigest polyglotProjectionDigest) {
        project(input, store, ledgers, fastArchitecture, projector, polyglotProjectionDigest);
    }

    public void buildInto(CanonicalGraphInput input, GraphProjectionStore store,
            Collection<EvidenceAcquisitionLedger> ledgers, boolean fastArchitecture,
            IncrementalGraphProjector projector,
            java.util.function.Function<String, ContentDigest> modulePolyglotDigest) {
        project(input, store, ledgers, fastArchitecture, projector, modulePolyglotDigest);
    }

    private static ContentDigest springContextDigestFor(
            AnalysisIdentity analysis,
            List<CanonicalGraphInput.SpringSlice> slices,
            UniversalIngestionPipeline.Unit unit) {
        var components = new ArrayList<Object>();
        if (unit != null) {
            unit.sourceWiring().ifPresent(w -> {
                if (w.observed()) {
                    components.add(List.of("source-wiring", w.beans().size(), w.sites().size(), w.matches().size()));
                }
            });
            unit.components().ifPresent(c -> {
                if (!c.rows().isEmpty()) {
                    components.add(List.of("components", c.rows().stream()
                            .map(r -> List.of(r.type().value(), r.status().name(), r.roots(), r.beanName().orElse("")))
                            .toList()));
                }
            });
            unit.constructors().ifPresent(c -> {
                if (!c.rows().isEmpty()) {
                    components.add(List.of("constructors", c.rows().stream()
                            .map(r -> List.of(r.type().value(), r.status().name(),
                                    r.constructor().map(EntityIdentity::value).orElse(""), r.parameters().size()))
                            .toList()));
                }
            });
            unit.spring().ifPresent(s -> {
                if (!s.web().routes().isEmpty() || !s.bindings().isEmpty()
                        || !s.injections().sites().isEmpty() || !s.repositories().rows().isEmpty()) {
                    components.add(List.of("universal",
                            s.generation(),
                            s.web().routes().stream().map(r -> r.identity().value()).toList(),
                            s.bindings().stream().map(b -> List.of(b.owner().value(), b.status().name(), b.candidates().size())).toList(),
                            s.injections().sites().size(),
                            s.repositories().rows().size()));
                }
            });
        }
        if (slices != null) {
            for (var slice : slices) {
                var sliceAnalysis = slice.plan().binding().registrationPlan().discoveryPlan()
                        .buildContext().analysisIdentity();
                if (!analysis.equals(sliceAnalysis)) continue;
                components.add(List.of(
                        "spring-slice",
                        slice.plan().inventory().coverage(),
                        slice.plan().conditions().coverage(),
                        slice.plan().binding().dependencies().size(),
                        slice.plan().binding().definitions().size(),
                        slice.space().map(s -> s.domains().stream()
                                .map(d -> List.of(d.variable().kind().name(), d.variable().name(), d.values().size()))
                                .toList()).orElse(List.of()),
                        slice.baseline().map(b -> b.baseline().entrySet().stream()
                                .map(e -> List.of(e.getKey().kind().name(), e.getKey().name(), e.getValue().toString()))
                                .sorted(Comparator.comparing(CanonicalJson::write))
                                .toList()).orElse(List.of()),
                        slice.explicitTruth().map(t -> List.of(
                                t.feasibility().name(),
                                t.regions().stream()
                                        .map(r -> List.of(r.fact().kind().name(), r.classification().name()))
                                        .toList())).orElse(List.of()),
                        slice.symbolicTruth().map(t -> List.of(
                                t.signaturesComplete(),
                                t.regions().stream()
                                        .map(r -> List.of(r.fact().kind().name(), r.classification().name()))
                                        .toList())).orElse(List.of()),
                        slice.parameters().map(p -> CanonicalJson.sha256(p).value()).orElse("")));
            }
        }
        if (components.isEmpty()) return null;
        return CanonicalJson.sha256(List.of("spring-context-template-binding:v2", components));
    }

    private static boolean hasFastArchitectureProfile(CanonicalGraphInput input) {
        return input.ingestion().stream()
                .flatMap(ing -> ing.sourceIngestion().outcomes().stream())
                .flatMap(o -> o.request().stream())
                .anyMatch(
                        r -> "FAST_ARCHITECTURE".equals(r.manifest().configuration().values().get("analysis.profile")));
    }

    private Projection project(CanonicalGraphInput input, Collection<EvidenceAcquisitionLedger> ledgers,
            GraphProjectionStore store, boolean fastArchitecture, IncrementalGraphProjector projector,
            java.util.function.Function<String, ContentDigest> modulePolyglotDigest) {
        Objects.requireNonNull(input);
        boolean effectiveFast = fastArchitecture || hasFastArchitectureProfile(input);
        var p = new Projection(input.repository().snapshot().identity(), store, effectiveFast, projector,
                modulePolyglotDigest);
        input.javaEvidence().ifPresent(binding -> {
            try {
                binding.requireCurrent();
            } catch (java.io.IOException unavailable) {
                throw new IllegalArgumentException("Unavailable post-analysis Java evidence");
            }
            p.javaBase = Optional.of(binding.base());
            p.historicalGaps = binding.historicalGapIds();
            binding.evidence().forEach(p::nativeEvidence);
        });
        String repository = p.repository(input);
        input.javaEvidence().ifPresent(binding -> {
            for (var evidence : binding.evidence())
                if (evidence.family().equals("java.evidence.gap-disposition")) {
                    var row = GraphJson.object(GraphJson.read(evidence.canonicalJson(), GraphJson.Limits.defaults()));
                    p.gapDispositions.put(GraphJson.string(row.get("gap")), evidence.identity());
                }
            for (var evidence : binding.evidence())
                if (evidence.family().equals("java.evidence.source-map")) {
                    var row = GraphJson.object(GraphJson.read(evidence.canonicalJson(), GraphJson.Limits.defaults()));
                    String document = GraphJson.string(row.get("projectedDocument"));
                    var source = input.repository().snapshot().documents().stream()
                            .filter(d -> d.identity().value().equals(document)).findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("Foreign generated source map"));
                    p.node(document, repository, "SOURCE_DOCUMENT", p.documentArtifact(source));
                    p.node(document, repository, "SOURCE_DOCUMENT", evidence.identity());
                }
            binding.gapHistory().forEach(g -> p.gapRecord(repository, g));
        });
        input.ingestion().ifPresent(ingestion -> {
            p.emitSourceOwnership(ingestion.sourceIngestion());
            var outcomesBySet = new HashMap<UniversalSourceIngestion.SourceSet, UniversalSourceIngestion.Outcome>(
                    Math.max(16, ingestion.sourceIngestion().outcomes().size() * 2));
            for (var outcome : ingestion.sourceIngestion().outcomes()) {
                outcomesBySet.put(outcome.sourceSet(), outcome);
                p.emitBoundedArtifact("source.configuration", outcome.configuration());
                outcome.metadata().forEach(m -> p.artifact("source.metadata", m));
                outcome.request().ifPresent(request -> {
                    if (!request.manifest().snapshot().identity().equals(p.snapshot))
                        throw new IllegalArgumentException("Foreign frontend snapshot");
                    p.artifact("source.request", Map.of("manifest", compactRequestManifest(request.manifest()),
                            "module", request.module(), "sourceSet", request.sourceSet(), "plan", request.plan()));
                    request.sources().forEach(p::sourceBytes);
                    request.originClasspath(); // Revalidate native capabilities before persisting their provenance.
                    request.dependencies().forEach(binary -> binary.nativeOrigin().ifPresent(origin -> {
                        p.nativeEvidence(GraphEvidence.capture("java.evidence.binary-origin", origin.canonicalForm()));
                        origin.proof().ifPresent(proof -> proof.evidence().forEach(p::nativeEvidence));
                    }));
                    request.correspondence().ifPresent(binding -> {
                        try {
                            if (!binding.current())
                                throw new IllegalArgumentException("Revoked source/native correspondence");
                        } catch (java.io.IOException unavailable) {
                            throw new IllegalArgumentException("Unavailable source/native correspondence");
                        }
                        binding.evidence().forEach(p::nativeEvidence);
                    });
                });
            }
            for (var unit : ingestion.units()) {
                p.artifact("ingestion.unit", Map.of("sourceSet", unit.sourceSet(), "status", unit.status()));
                var outcome = Objects.requireNonNull(outcomesBySet.get(unit.sourceSet()));
                unit.frontend().ifPresent(frontend -> {
                    var req = outcome.request().orElseThrow();
                    var manifest = req.manifest();
                    String modulePath = manifest.modules().stream()
                            .filter(m -> m.identity().equals(req.module()))
                            .map(ModuleDescriptor::path)
                            .findFirst()
                            .orElse(".");
                    var springDigest = springContextDigestFor(manifest.identity(), input.spring(), unit);
                    p.frontend(manifest, frontend, springDigest, modulePath);
                });
                unit.sourceWiring().ifPresent(wiring -> p.sourceWiring(outcome.request().orElseThrow(), wiring));
                unit.components().ifPresent(p::emitSpringComponents);
                unit.constructors().ifPresent(p::emitSpringConstructors);
                unit.spring().ifPresent(s -> {
                    p.emitSpringUniversal(s);
                    var request = outcome.request().orElseThrow();
                    Map<EntityIdentity, Entity> projectEntitiesById = null;
                    if (!s.web().routes().isEmpty()) {
                        projectEntitiesById = new HashMap<>();
                        for (var decl : unit.frontend().orElseThrow().declarations()) {
                            if (decl.entity().origin() == EntityOrigin.PROJECT) {
                                projectEntitiesById.putIfAbsent(decl.entity().identity(), decl.entity());
                            }
                        }
                    }
                    for (var route : s.web().routes()) {
                        var handler = projectEntitiesById.get(route.handler());
                        if (handler == null)
                            throw new IllegalArgumentException("Foreign endpoint declaration");
                        String context = request.manifest().identity().value();
                        var payload = p.artifact("spring.endpoint-declaration",
                                Map.of("schema", "spring.endpoint-declaration:m8.4-v1",
                                        "route", route, "module", handler.stableScope().value(), "sourceSet",
                                        request.sourceSet(),
                                        "scope", "DECLARATION_ONLY", "activation", "UNKNOWN", "runtimeObserved",
                                        false));
                        String endpoint = derivedIdentity("endpoint-declaration",
                                List.of("m8.4-v1", context, route.identity()));
                        p.node(endpoint, context, "ENDPOINT", payload);
                        p.link(context, "graph.contains", route.handler().value(), endpoint, payload);
                    }
                });
            }
            var jaxRsUnits = new ArrayList<com.evolution.analysis.jaxrs.JaxRsRouteMapper.UnitInput>();
            for (var unit : ingestion.units()) {
                var outcome = outcomesBySet.get(unit.sourceSet());
                if (outcome != null && outcome.request().isPresent() && unit.frontend().isPresent()) {
                    jaxRsUnits.add(new com.evolution.analysis.jaxrs.JaxRsRouteMapper.UnitInput(
                            outcome.request().orElseThrow(), unit.frontend().orElseThrow()));
                }
            }
            if (!jaxRsUnits.isEmpty()) {
                var jaxRsByAnalysis = com.evolution.analysis.jaxrs.JaxRsRouteMapper.mapAll(jaxRsUnits, 100_000);
                for (var jaxRsUnit : jaxRsUnits) {
                    var req = jaxRsUnit.request();
                    var res = jaxRsByAnalysis.get(req.manifest().identity());
                    if (res != null && !res.routes().isEmpty()) {
                        p.jaxRsRoutes(req, res.routes());
                    }
                }
            }
            // Index the lossless records instead of nesting the full repeated ledger in one
            // giant payload.
            // Individual issues/gaps retain their own canonical evidence and original
            // provider identity.
            ingestion.issues().forEach(i -> p.artifact("ingestion.issue", i));
            ingestion.gaps().forEach(g -> p.gapRecord(repository, g));
            p.artifact("ingestion.outcome-index", Map.of("schema", "ingestion.outcome-index:m8.4-paged-v2", "identity",
                    ingestion.identity(), "inputIdentity", ingestion.inputIdentity(),
                    "issues", p.indexPages("ingestion.issue-index-page", ingestion.issues(), i -> i.identity().value()),
                    "gaps", p.indexPages("ingestion.gap-index-page", ingestion.gaps(), g -> g.gapIdentity().value())));
        });
        for (var spring : input.spring())
            p.spring(spring);
        input.issues().forEach(
                i -> p.node(i.identity().value(), repository, "ACQUISITION_ISSUE", p.artifact("ingestion.issue", i)));
        p.gapRecords(repository, input.gaps(), input.ingestion()
                .map(UniversalIngestionPipeline.Result::gaps).orElseGet(List::of));
        ledgers.forEach(p::ledger);
        return p;
    }

    private static final class Projection {
        final SnapshotIdentity snapshot;
        final Map<String, Context> contexts;
        final Map<String, Node> nodes;
        final Map<String, Edge> edges;
        final Map<String, GraphEvidence> evidence;
        final Map<String, ContentDigest> statements;
        final Map<String, String> injectionUses = new HashMap<>();
        final Map<String, ContentDigest> requiredAttempts;
        // Scope the cache to one projection and keep at most one variant per stable
        // gap.
        // The full record decides reuse. The stable ID excludes diagnostics and
        // history.
        final Map<CapabilityGapIdentity, EncodedGap> gapEvidence = new LinkedHashMap<>(256, .75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<CapabilityGapIdentity, EncodedGap> eldest) {
                return size() > 4096;
            }
        };

        record EncodedGap(CapabilityGapRecord record, ContentDigest evidence) {
        }

        Optional<SnapshotIdentity> javaBase = Optional.empty();
        Set<String> historicalGaps = Set.of();
        final Map<String, ContentDigest> gapDispositions = new HashMap<>();
        final Map<String, Set<ContentDigest>> nativeSupports = new HashMap<>();
        final Map<ContentDigest, ContentDigest> contentArtifacts = new HashMap<>();
        final Map<ModuleIdentity, ContentDigest> moduleArtifacts = new HashMap<>();
        final Map<SourceDocumentIdentity, ContentDigest> documentArtifacts = new HashMap<>();
        final Map<String, IdentityHashMap<Object, ContentDigest>> cachedArtifacts = new HashMap<>();
        final boolean fastArchitecture;
        final IncrementalGraphProjector projector;
        final java.util.function.Function<String, ContentDigest> modulePolyglotDigest;

        Projection(SnapshotIdentity snapshot) {
            this(snapshot, GraphProjectionStore.memory(), false, null, null);
        }

        Projection(SnapshotIdentity snapshot, GraphProjectionStore store) {
            this(snapshot, store, false, null, null);
        }

        Projection(SnapshotIdentity snapshot, GraphProjectionStore store, boolean fastArchitecture) {
            this(snapshot, store, fastArchitecture, null, null);
        }

        Projection(SnapshotIdentity snapshot, GraphProjectionStore store, boolean fastArchitecture,
                IncrementalGraphProjector projector,
                java.util.function.Function<String, ContentDigest> modulePolyglotDigest) {
            this.snapshot = snapshot;
            this.fastArchitecture = fastArchitecture;
            this.projector = projector;
            this.modulePolyglotDigest = modulePolyglotDigest;
            contexts = store.records("contexts", GraphSnapshotCodec::context);
            nodes = store.records("nodes", GraphSnapshotCodec::node);
            edges = store.records("edges", GraphSnapshotCodec::edge);
            evidence = store.records("evidence", GraphSnapshotCodec::evidence);
            statements = store.records("statements", v -> new ContentDigest(GraphJson.string(v)));
            requiredAttempts = store.records("required-attempts", v -> new ContentDigest(GraphJson.string(v)));
        }

        void nativeEvidence(GraphEvidence value) {
            evidence.put(value.identity().value(), value);
            nativeSupports
                    .computeIfAbsent(ContentDigest.sha256Utf8(value.canonicalJson()).value(), key -> new TreeSet<>())
                    .add(value.identity());
        }

        void jaxRsRoutes(com.evolution.analysis.frontend.FrontendRequest request,
                List<com.evolution.analysis.jaxrs.JaxRsRouteMapper.Route> routes) {
            String context = request.manifest().identity().value();
            for (var route : routes) {
                String family = route.kind() == com.evolution.analysis.jaxrs.JaxRsRouteMapper.Kind.SERVLET
                        ? "servlet.endpoint-declaration"
                        : "jaxrs.endpoint-declaration";
                var payload = artifact(family, Map.of(
                        "schema", family + ":m8.4-v1",
                        "route", route,
                        "module", route.module(),
                        "sourceSet", request.sourceSet(),
                        "scope", "DECLARATION_ONLY",
                        "activation", "UNKNOWN",
                        "runtimeObserved", false));
                String endpoint = derivedIdentity("endpoint-declaration",
                        List.of(family + ":m8.4-v1", context, route.identity()));
                node(endpoint, context, "ENDPOINT", payload);
                String anchor = nodes.containsKey(key(context, route.handler().value()))
                        ? route.handler().value()
                        : route.controller().value();
                link(context, "graph.contains", anchor, endpoint, payload);
            }
        }

        void sourceWiring(com.evolution.analysis.frontend.FrontendRequest request,
                com.evolution.analysis.spring.universal.SourceWiringCandidates.Result result) {
            if (!request.manifest().identity().equals(result.analysis()))
                throw new IllegalArgumentException("Foreign source wiring context");
            if (!result.observed())
                return;
            String context = request.manifest().identity().value();
            var summary = artifact("spring.source-wiring",
                    Map.of("provider", com.evolution.analysis.spring.universal.SourceWiringCandidates.PROVIDER, "input",
                            result.input(),
                            "beans", result.beans().size(), "sites", result.sites().size(), "typeCandidates",
                            result.matches().size(), "activation", "UNKNOWN", "selection", "NOT_SELECTED",
                            "qualifierCompatibility", "NOT_EVALUATED"));
            String evaluation = derivedIdentity("spring-source-wiring", List.of(context, result.input()));
            node(evaluation, context, "SPRING_SOURCE_WIRING", summary);
            link(context, "spring.source-wiring-evaluated", request.module().value(), evaluation, summary);
            for (var bean : result.beans()) {
                var support = artifact("spring.source-bean-candidate", bean);
                String id = derivedIdentity("spring-source-bean", List.of(context, bean));
                node(id, context, "SOURCE_BEAN_CANDIDATE", support);
                link(context, "spring.source-bean-declaration", id, bean.declaration().value(), support);
            }
            for (var site : result.sites()) {
                var support = artifact("spring.source-injection-site", site);
                String id = derivedIdentity("spring-source-injection", List.of(context, site));
                node(id, context, "SOURCE_INJECTION_SITE", support);
                link(context, "spring.source-injection-owner", id, site.owner().value(), support);
            }
            for (var match : result.matches()) {
                var support = artifact("spring.source-type-candidate", match);
                link(context, "spring.source-type-candidate",
                        derivedIdentity("spring-source-injection", List.of(context, match.site())),
                        derivedIdentity("spring-source-bean", List.of(context, match.bean())), support);
            }
        }

        ContentDigest artifact(String family, Object value) {
            boolean cacheable = value != null && value.getClass().isRecord();
            if (cacheable) {
                var byFamily = cachedArtifacts.get(family);
                if (byFamily != null) {
                    var cached = byFamily.get(value);
                    if (cached != null)
                        return cached;
                }
            }
            var artifact = GraphEvidence.capture(family, value);
            var old = evidence.putIfAbsent(artifact.identity().value(), artifact);
            if (old != null && !old.equals(artifact))
                throw new IllegalArgumentException("Conflicting provider artifact");
            if (cacheable) {
                var byFamily = cachedArtifacts.computeIfAbsent(family, k -> new IdentityHashMap<>());
                if (byFamily.size() < 65_536)
                    byFamily.put(value, artifact.identity());
            }
            return artifact.identity();
        }

        ContentDigest sourceBytes(SourceInput s) {
            var digest = s.document().contentDigest();
            var contentRef = contentArtifacts.computeIfAbsent(digest, d -> {
                if (projector != null) {
                    var cached = projector.getSourceContent(d, fastArchitecture);
                    if (cached != null) {
                        var old = evidence.putIfAbsent(cached.identity().value(), cached);
                        if (old != null && !old.equals(cached))
                            throw new IllegalArgumentException("Conflicting provider artifact");
                        return cached.identity();
                    }
                }
                GraphEvidence captured;
                if (fastArchitecture) {
                    captured = GraphEvidence.capture("source.content", Map.of("contentDigest", d, "size", s.byteLength()));
                } else {
                    byte[] raw = s.bytes();
                    captured = GraphEvidence.capture("source.content", Map.of("contentDigest", d, "size", raw.length, "bytesBase64",
                            Base64.getEncoder().encodeToString(raw)));
                }
                var old = evidence.putIfAbsent(captured.identity().value(), captured);
                if (old != null && !old.equals(captured))
                    throw new IllegalArgumentException("Conflicting provider artifact");
                if (projector != null) {
                    projector.putSourceContent(d, fastArchitecture, captured);
                }
                return captured.identity();
            });
            return artifact("source.bytes", Map.of("document", s.document(), "decoding", s.decoding(), "contentDigest",
                    digest, "contentEvidence", contentRef));
        }

        ContentDigest moduleArtifact(ModuleDescriptor m) {
            return moduleArtifacts.computeIfAbsent(m.identity(), k -> artifact("module.descriptor", m));
        }

        ContentDigest documentArtifact(SourceDocument d) {
            return documentArtifacts.computeIfAbsent(d.identity(), k -> artifact("source.document", d));
        }

        <T> List<ContentDigest> indexPages(String family, Iterable<T> rows,
                java.util.function.Function<T, String> identity) {
            var pages = new ArrayList<ContentDigest>();
            var page = new ArrayList<String>(MAX_CHUNK_ITEMS);
            long pageBytes = 64L;
            for (var row : rows) {
                String id = identity.apply(row);
                long itemBytes = GraphEvidence.encodedUtf8Length(id) + 1L;
                if (!page.isEmpty()
                        && (page.size() >= MAX_CHUNK_ITEMS || pageBytes + itemBytes > MAX_CHUNK_UTF8_BYTES)) {
                    pages.add(artifact(family, Map.of("page", pages.size(), "records", List.copyOf(page))));
                    page.clear();
                    pageBytes = 64L;
                }
                page.add(id);
                pageBytes += itemBytes;
            }
            if (!page.isEmpty())
                pages.add(artifact(family, Map.of("page", pages.size(), "records", List.copyOf(page))));
            return rollUpPages(family, pages);
        }

        <T> List<ContentDigest> chunkObjects(String family, String fieldName, Iterable<T> rows) {
            var pages = new ArrayList<ContentDigest>();
            var page = new ArrayList<T>(Math.min(MAX_CHUNK_ITEMS, 64));
            long pageBytes = 64L;
            for (var row : rows) {
                long itemBytes = GraphEvidence.encodedUtf8LengthCapped(row, MAX_CHUNK_UTF8_BYTES) + 1L;
                if (!page.isEmpty()
                        && (page.size() >= MAX_CHUNK_ITEMS || pageBytes + itemBytes > MAX_CHUNK_UTF8_BYTES)) {
                    pages.add(artifact(family, Map.of("page", pages.size(), fieldName, List.copyOf(page))));
                    page.clear();
                    pageBytes = 64L;
                }
                page.add(row);
                pageBytes += itemBytes;
            }
            if (!page.isEmpty())
                pages.add(artifact(family, Map.of("page", pages.size(), fieldName, List.copyOf(page))));
            return rollUpPages(family, pages);
        }

        private List<ContentDigest> rollUpPages(String leafFamily, List<ContentDigest> pages) {
            if (pages.size() <= MAX_CHUNK_ITEMS) return List.copyOf(pages);
            List<ContentDigest> current = pages;
            String dirFamily = leafFamily + ".dir";
            while (current.size() > MAX_CHUNK_ITEMS) {
                var dirs = new ArrayList<ContentDigest>((current.size() + MAX_CHUNK_ITEMS - 1) / MAX_CHUNK_ITEMS);
                for (int start = 0; start < current.size(); start += MAX_CHUNK_ITEMS) {
                    var slice = current.subList(start, Math.min(current.size(), start + MAX_CHUNK_ITEMS));
                    dirs.add(artifact(dirFamily, Map.of("page", dirs.size(), "pages", List.copyOf(slice))));
                }
                current = dirs;
            }
            return List.copyOf(current);
        }

        void emitBoundedArtifact(String family, Object value) {
            if (GraphEvidence.encodedUtf8LengthCapped(value, INLINE_PAYLOAD_LIMIT_BYTES) <= INLINE_PAYLOAD_LIMIT_BYTES) {
                artifact(family, value);
                return;
            }
            if (value instanceof com.evolution.analysis.spring.condition.ConfigDataIngestion.Result cfg) {
                var payload = new TreeMap<String, Object>();
                payload.put("schema", family + ":m8.4-paged-v1");
                payload.put("identity", cfg.identity());
                payload.put("inputIdentity", cfg.inputIdentity());
                payload.put("documentsCount", cfg.documents().size());
                payload.put("documentPages", chunkObjects(family + ".document-page", "documents", cfg.documents()));
                payload.put("propertiesCount", cfg.properties().size());
                payload.put("propertyPages", chunkObjects(family + ".property-page", "properties",
                        cfg.properties().entrySet().stream().map(e -> List.of(e.getKey(), e.getValue())).toList()));
                payload.put("issuesCount", cfg.issues().size());
                payload.put("issuePages", chunkObjects(family + ".issue-page", "issues", cfg.issues()));
                payload.put("gapsCount", cfg.gaps().size());
                payload.put("gapPages", indexPages(family + ".gap-page", cfg.gaps(), g -> g.gapIdentity().value()));
                artifact(family, payload);
                return;
            }
            artifact(family, value);
        }

        void emitSourceOwnership(UniversalSourceIngestion.Result sourceIngestion) {
            if (sourceIngestion.sources().size() <= MAX_CHUNK_ITEMS
                    && sourceIngestion.issues().size() <= MAX_CHUNK_ITEMS
                    && sourceIngestion.gaps().size() <= MAX_CHUNK_ITEMS) {
                var inline = Map.of("inputIdentity", sourceIngestion.inputIdentity(),
                        "sources", sourceIngestion.sources(), "issues", sourceIngestion.issues(),
                        "gaps", sourceIngestion.gaps(), "providerResultIdentity", sourceIngestion.identity());
                if (GraphEvidence.encodedUtf8LengthCapped(inline, INLINE_PAYLOAD_LIMIT_BYTES) <= INLINE_PAYLOAD_LIMIT_BYTES) {
                    artifact("source.ownership", inline);
                    return;
                }
            }
            var manifest = new TreeMap<String, Object>();
            manifest.put("schema", "source.ownership:m8.4-paged-v1");
            manifest.put("inputIdentity", sourceIngestion.inputIdentity());
            manifest.put("providerResultIdentity", sourceIngestion.identity());
            manifest.put("sourcesCount", sourceIngestion.sources().size());
            manifest.put("sourcePages", chunkObjects("source.ownership-source-page", "sources", sourceIngestion.sources()));
            manifest.put("issuesCount", sourceIngestion.issues().size());
            manifest.put("issuePages", chunkObjects("source.ownership-issue-page", "issues", sourceIngestion.issues()));
            manifest.put("gapsCount", sourceIngestion.gaps().size());
            manifest.put("gapPages", indexPages("source.ownership-gap-page", sourceIngestion.gaps(),
                    g -> g.gapIdentity().value()));
            artifact("source.ownership", manifest);
        }

        void emitSpringComponents(com.evolution.analysis.spring.condition.ComponentScanIngestion.Result c) {
            if (GraphEvidence.encodedUtf8LengthCapped(c, INLINE_PAYLOAD_LIMIT_BYTES) <= INLINE_PAYLOAD_LIMIT_BYTES) {
                artifact("spring.components", c);
                return;
            }
            var manifest = new TreeMap<String, Object>();
            manifest.put("schema", "spring.components:m8.4-paged-v1");
            manifest.put("identity", c.identity());
            manifest.put("frontendEvidence", c.frontendEvidence());
            manifest.put("frameworkEvidence", c.frameworkEvidence());
            manifest.put("rowsCount", c.rows().size());
            manifest.put("rowPages", chunkObjects("spring.components-row-page", "rows", c.rows()));
            manifest.put("issuesCount", c.issues().size());
            manifest.put("issuePages", chunkObjects("spring.components-issue-page", "issues", c.issues()));
            manifest.put("gapsCount", c.gaps().size());
            manifest.put("gapPages", indexPages("spring.components-gap-page", c.gaps(), g -> g.gapIdentity().value()));
            artifact("spring.components", manifest);
        }

        void emitSpringConstructors(com.evolution.analysis.spring.condition.ConstructorInjectionIngestion.Result c) {
            if (GraphEvidence.encodedUtf8LengthCapped(c, INLINE_PAYLOAD_LIMIT_BYTES) <= INLINE_PAYLOAD_LIMIT_BYTES) {
                artifact("spring.constructors", c);
                return;
            }
            var manifest = new TreeMap<String, Object>();
            manifest.put("schema", "spring.constructors:m8.4-paged-v1");
            manifest.put("identity", c.identity());
            manifest.put("inputIdentity", c.inputIdentity());
            manifest.put("analysis", c.analysis());
            manifest.put("snapshot", c.snapshot());
            manifest.put("rowsCount", c.rows().size());
            manifest.put("rowPages", chunkObjects("spring.constructors-row-page", "rows", c.rows()));
            manifest.put("issuesCount", c.issues().size());
            manifest.put("issuePages", chunkObjects("spring.constructors-issue-page", "issues", c.issues()));
            manifest.put("gapsCount", c.gaps().size());
            manifest.put("gapPages", indexPages("spring.constructors-gap-page", c.gaps(), g -> g.gapIdentity().value()));
            artifact("spring.constructors", manifest);
        }

        void emitSpringUniversal(com.evolution.analysis.spring.universal.UniversalSpringSemantics.Result s) {
            if (GraphEvidence.encodedUtf8LengthCapped(s, INLINE_PAYLOAD_LIMIT_BYTES) <= INLINE_PAYLOAD_LIMIT_BYTES) {
                artifact("spring.universal", s);
                return;
            }
            var manifest = new TreeMap<String, Object>();
            manifest.put("schema", "spring.universal:m8.4-paged-v1");
            manifest.put("identity", s.identity());
            manifest.put("inputIdentity", s.inputIdentity());
            manifest.put("generation", s.generation());
            manifest.put("repositories", Map.of(
                    "identity", s.repositories().identity(),
                    "scansCount", s.repositories().scans().size(),
                    "scanPages", chunkObjects("spring.universal-repo-scan-page", "scans", s.repositories().scans()),
                    "rowsCount", s.repositories().rows().size(),
                    "rowPages", chunkObjects("spring.universal-repo-page", "rows", s.repositories().rows()),
                    "issuePages", chunkObjects("spring.universal-repo-issue-page", "issues", s.repositories().issues()),
                    "gapPages", indexPages("spring.universal-repo-gap-page", s.repositories().gaps(), g -> g.gapIdentity().value())));
            manifest.put("injections", Map.of(
                    "identity", s.injections().identity(),
                    "sitesCount", s.injections().sites().size(),
                    "sitePages", chunkObjects("spring.universal-injection-page", "sites", s.injections().sites()),
                    "issuePages", chunkObjects("spring.universal-injection-issue-page", "issues", s.injections().issues()),
                    "gapPages", indexPages("spring.universal-injection-gap-page", s.injections().gaps(), g -> g.gapIdentity().value())));
            manifest.put("web", Map.of(
                    "identity", s.web().identity(),
                    "routesCount", s.web().routes().size(),
                    "routePages", chunkObjects("spring.universal-route-page", "routes", s.web().routes()),
                    "issuePages", chunkObjects("spring.universal-route-issue-page", "issues", s.web().issues()),
                    "gapPages", indexPages("spring.universal-route-gap-page", s.web().gaps(), g -> g.gapIdentity().value())));
            manifest.put("bindingsCount", s.bindings().size());
            manifest.put("bindingPages", chunkObjects("spring.universal-binding-page", "bindings", s.bindings()));
            manifest.put("expressionsCount", s.expressions().size());
            manifest.put("expressionPages", chunkObjects("spring.universal-expression-page", "expressions", s.expressions()));
            manifest.put("pathsCount", s.paths().size());
            manifest.put("pathPages", chunkObjects("spring.universal-path-page", "paths", s.paths()));
            manifest.put("metadata", Map.of(
                    "identity", s.metadata().identity(),
                    "candidatesCount", s.metadata().candidates().size(),
                    "candidatePages", chunkObjects("spring.universal-metadata-page", "candidates", s.metadata().candidates()),
                    "issuePages", chunkObjects("spring.universal-metadata-issue-page", "issues", s.metadata().issues()),
                    "gapPages", indexPages("spring.universal-metadata-gap-page", s.metadata().gaps(), g -> g.gapIdentity().value())));
            manifest.put("issuesCount", s.issues().size());
            manifest.put("issuePages", chunkObjects("spring.universal-issue-page", "issues", s.issues()));
            manifest.put("gapsCount", s.gaps().size());
            manifest.put("gapPages", indexPages("spring.universal-gap-page", s.gaps(), g -> g.gapIdentity().value()));
            artifact("spring.universal", manifest);
        }

        void emitAcquisition(com.evolution.analysis.acquisition.RepositoryAcquisitionResult a) {
            if (a.files().size() <= 64
                    && a.directories().size() <= MAX_CHUNK_ITEMS
                    && a.problems().size() <= MAX_CHUNK_ITEMS
                    && a.attempts().size() <= MAX_CHUNK_ITEMS
                    && GraphEvidence.encodedUtf8LengthCapped(a, INLINE_PAYLOAD_LIMIT_BYTES) <= INLINE_PAYLOAD_LIMIT_BYTES) {
                artifact("repository.acquisition", a);
                return;
            }
            var manifest = new TreeMap<String, Object>();
            manifest.put("pagedSchema", "repository.acquisition:m8.4-paged-v1");
            manifest.put("schemaVersion", a.schemaVersion());
            manifest.put("request", a.request());
            manifest.put("provider", a.provider());
            manifest.put("completion", a.completion());
            if (a.snapshot().isPresent()) {
                var snap = a.snapshot().orElseThrow();
                var compactSnap = new TreeMap<String, Object>();
                compactSnap.put("identity", snap.identity());
                compactSnap.put("repository", snap.repository());
                compactSnap.put("contentDigest", snap.contentDigest());
                compactSnap.put("revision", snap.revision());
                compactSnap.put("dirty", snap.dirty());
                compactSnap.put("filesCount", snap.files().size());
                manifest.put("snapshot", compactSnap);
            } else {
                manifest.put("snapshot", null);
            }
            var fileEntries = new ArrayList<Object>(a.files().size());
            for (var file : a.files()) {
                if (file.size() <= MAX_RAW_BLOB_CHUNK_BYTES) {
                    fileEntries.add(file);
                } else {
                    byte[] raw = file.bytes();
                    var blobPages = new ArrayList<ContentDigest>();
                    for (int off = 0; off < raw.length; off += MAX_RAW_BLOB_CHUNK_BYTES) {
                        int end = Math.min(raw.length, off + MAX_RAW_BLOB_CHUNK_BYTES);
                        byte[] slice = Arrays.copyOfRange(raw, off, end);
                        blobPages.add(artifact("repository.acquisition-blob-chunk", Map.of(
                                "chunk", blobPages.size(),
                                "size", slice.length,
                                "contentDigest", ContentDigest.sha256(slice),
                                "bytesBase64", Base64.getEncoder().encodeToString(slice))));
                    }
                    fileEntries.add(Map.of(
                            "path", file.path(),
                            "contentDigest", file.contentDigest(),
                            "size", file.size(),
                            "contentChunkPages", rollUpPages("repository.acquisition-blob-chunk", blobPages)));
                }
            }
            manifest.put("filesCount", a.files().size());
            manifest.put("filePages", chunkObjects("repository.acquisition-file-page", "files", fileEntries));
            manifest.put("directoriesCount", a.directories().size());
            manifest.put("directoryPages", chunkObjects("repository.acquisition-dir-page", "directories", a.directories()));
            manifest.put("problemsCount", a.problems().size());
            manifest.put("problemPages", chunkObjects("repository.acquisition-problem-page", "problems", a.problems()));
            manifest.put("attemptsCount", a.attempts().size());
            manifest.put("attemptPages", chunkObjects("repository.acquisition-attempt-page", "attempts", a.attempts()));
            manifest.put("limitations", a.limitations());
            artifact("repository.acquisition", manifest);
        }

        void emitBuildModel(com.evolution.analysis.buildmodel.UniversalBuildModel build) {
            if (build.modules().size() <= 4
                    && build.mavenModels().size() <= 4
                    && build.issues().size() <= MAX_CHUNK_ITEMS
                    && build.gaps().size() <= MAX_CHUNK_ITEMS
                    && GraphEvidence.encodedUtf8LengthCapped(build, INLINE_PAYLOAD_LIMIT_BYTES) <= INLINE_PAYLOAD_LIMIT_BYTES) {
                artifact("build.model", build);
                return;
            }
            var manifest = new TreeMap<String, Object>();
            manifest.put("schema", "build.model:m8.4-paged-v1");
            manifest.put("inputIdentity", build.inputIdentity());
            manifest.put("repositoryInputIdentity", build.repositoryInputIdentity());
            manifest.put("modulesCount", build.modules().size());
            manifest.put("modulePages", indexPages("build.model-module-index-page", build.modules(),
                    m -> artifact("build.module", m).value()));
            var mavenModelDigests = new ArrayList<ContentDigest>(build.mavenModels().size());
            for (var mm : build.mavenModels()) {
                if (GraphEvidence.encodedUtf8LengthCapped(mm, INLINE_PAYLOAD_LIMIT_BYTES) <= INLINE_PAYLOAD_LIMIT_BYTES) {
                    mavenModelDigests.add(artifact("build.maven-model", mm));
                } else {
                    var mmManifest = new TreeMap<String, Object>();
                    mmManifest.put("schema", "build.maven-model:m8.4-paged-v1");
                    mmManifest.put("schemaVersion", mm.schemaVersion());
                    mmManifest.put("requestIdentity", mm.requestIdentity());
                    mmManifest.put("provider", mm.provider());
                    mmManifest.put("modulesCount", mm.modules().size());
                    mmManifest.put("modulePages", chunkObjects("build.maven-module-page", "modules", mm.modules()));
                    mmManifest.put("problemsCount", mm.problems().size());
                    mmManifest.put("problemPages", chunkObjects("build.maven-problem-page", "problems", mm.problems()));
                    mmManifest.put("attemptsCount", mm.attempts().size());
                    mmManifest.put("attemptPages", chunkObjects("build.maven-attempt-page", "attempts", mm.attempts()));
                    mmManifest.put("limitations", mm.limitations());
                    mavenModelDigests.add(artifact("build.maven-model", mmManifest));
                }
            }
            manifest.put("mavenModelsCount", build.mavenModels().size());
            manifest.put("mavenModelPages", indexPages("build.model-maven-index-page", mavenModelDigests,
                    ContentDigest::value));
            manifest.put("issuesCount", build.issues().size());
            if (build.issues().size() <= 64
                    && GraphEvidence.encodedUtf8LengthCapped(build.issues(), 256_000L) <= 256_000L) {
                manifest.put("issues", build.issues());
            } else {
                manifest.put("issuePages", chunkObjects("build.model-issue-page", "issues", build.issues()));
            }
            manifest.put("gapsCount", build.gaps().size());
            if (build.gaps().size() <= 64
                    && GraphEvidence.encodedUtf8LengthCapped(build.gaps(), 256_000L) <= 256_000L) {
                manifest.put("gaps", build.gaps());
            } else {
                manifest.put("gapPages", indexPages("build.model-gap-page", build.gaps(),
                        g -> g.gapIdentity().value()));
            }
            artifact("build.model", manifest);
        }

        void emitFrontendResultIndex(
                FrontendResult frontend,
                List<Map<String, Object>> sourceSummaries,
                List<ContentDigest> diagnosticPayloads,
                List<ContentDigest> declarationPayloads,
                List<ContentDigest> occurrencePayloads,
                List<ContentDigest> observationPayloads,
                List<ContentDigest> typeUsePayloads,
                List<ContentDigest> annotationPayloads,
                List<ContentDigest> derivedPayloads,
                List<ContentDigest> typeShapePayloads,
                List<ContentDigest> memberShapePayloads,
                List<ContentDigest> ctorCandidatePayloads,
                List<ContentDigest> httpClientCallPayloads) {
            long totalRefs = (long) sourceSummaries.size() + diagnosticPayloads.size() + declarationPayloads.size()
                    + occurrencePayloads.size() + observationPayloads.size() + typeUsePayloads.size()
                    + annotationPayloads.size() + derivedPayloads.size() + typeShapePayloads.size()
                    + memberShapePayloads.size() + ctorCandidatePayloads.size() + httpClientCallPayloads.size();
            var index = new TreeMap<String, Object>();
            index.put("providerResultIdentity", frontend.canonicalDigest());
            index.put("analysis", frontend.analysis());
            index.put("frontend", frontend.frontend());
            index.put("state", frontend.state());
            index.put("coverage", frontend.coverage());
            if (totalRefs <= INLINE_FRONTEND_INDEX_REFS && sourceSummaries.size() <= MAX_CHUNK_ITEMS) {
                index.put("indexSchema", "frontend.result-index:m8.4-v2");
                index.put("sources", List.copyOf(sourceSummaries));
                index.put("diagnostics", List.copyOf(diagnosticPayloads));
                index.put("declarations", List.copyOf(declarationPayloads));
                index.put("occurrences", List.copyOf(occurrencePayloads));
                index.put("observations", List.copyOf(observationPayloads));
                index.put("types", List.copyOf(typeUsePayloads));
                index.put("annotations", List.copyOf(annotationPayloads));
                index.put("derivedRelationships", List.copyOf(derivedPayloads));
                index.put("typeDeclarations", List.copyOf(typeShapePayloads));
                index.put("memberDeclarations", List.copyOf(memberShapePayloads));
                index.put("constructorCandidates", List.copyOf(ctorCandidatePayloads));
                index.put("httpClientCalls", List.copyOf(httpClientCallPayloads));
                artifact("frontend.result-index", index);
                return;
            }
            index.put("indexSchema", "frontend.result-index:m8.4-paged-v3");
            index.put("sourcesCount", sourceSummaries.size());
            index.put("sourcePages", chunkObjects("frontend.result-source-page", "sources", sourceSummaries));
            index.put("diagnosticsCount", diagnosticPayloads.size());
            index.put("diagnosticPages", indexPages("frontend.result-diagnostic-page", diagnosticPayloads, ContentDigest::value));
            index.put("declarationsCount", declarationPayloads.size());
            index.put("declarationPages", indexPages("frontend.result-declaration-page", declarationPayloads, ContentDigest::value));
            index.put("occurrencesCount", occurrencePayloads.size());
            index.put("occurrencePages", indexPages("frontend.result-occurrence-page", occurrencePayloads, ContentDigest::value));
            index.put("observationsCount", observationPayloads.size());
            index.put("observationPages", indexPages("frontend.result-observation-page", observationPayloads, ContentDigest::value));
            index.put("typesCount", typeUsePayloads.size());
            index.put("typePages", indexPages("frontend.result-type-page", typeUsePayloads, ContentDigest::value));
            index.put("annotationsCount", annotationPayloads.size());
            index.put("annotationPages", indexPages("frontend.result-annotation-page", annotationPayloads, ContentDigest::value));
            index.put("derivedRelationshipsCount", derivedPayloads.size());
            index.put("derivedRelationshipPages", indexPages("frontend.result-derived-page", derivedPayloads, ContentDigest::value));
            index.put("typeDeclarationsCount", typeShapePayloads.size());
            index.put("typeDeclarationPages", indexPages("frontend.result-type-decl-page", typeShapePayloads, ContentDigest::value));
            index.put("memberDeclarationsCount", memberShapePayloads.size());
            index.put("memberDeclarationPages", indexPages("frontend.result-member-decl-page", memberShapePayloads, ContentDigest::value));
            index.put("constructorCandidatesCount", ctorCandidatePayloads.size());
            index.put("constructorCandidatePages", indexPages("frontend.result-ctor-page", ctorCandidatePayloads, ContentDigest::value));
            index.put("httpClientCallsCount", httpClientCallPayloads.size());
            index.put("httpClientCallPages", indexPages("frontend.result-http-call-page", httpClientCallPayloads, ContentDigest::value));
            artifact("frontend.result-index", index);
        }

        ContentDigest emitSpringPlan(com.evolution.analysis.spring.universal.SourceToSpringPlan.Result plan) {
            var discovery = plan.binding().registrationPlan().discoveryPlan();
            var reg = plan.binding().registrationPlan();
            var binding = plan.binding();
            var inv = plan.inventory();
            var cond = plan.conditions();
            long smallCount = (long) plan.gaps().size() + inv.rawObservations().size() + inv.obligations().size()
                    + inv.problems().size() + cond.rows().size() + cond.occurrences().size() + cond.issues().size()
                    + cond.capabilityGaps().size() + binding.dependencies().size() + binding.definitions().size()
                    + binding.matches().size() + binding.obligations().size() + discovery.events().size()
                    + discovery.precedence().size() + reg.steps().size() + reg.definitions().size()
                    + plan.methodSelections().size();
            if (smallCount <= 128) {
                var inline = Map.of("inputIdentity", plan.inputIdentity(), "inventory", inv,
                        "conditions", cond, "binding", binding, "discovery",
                        Map.of("canonical", discovery.canonicalForm(), "build", discovery.buildContext(),
                                "events", discovery.events(), "precedence", discovery.precedence(),
                                "initialDefinitions", discovery.initialDefinitions()),
                        "gaps", plan.gaps(), "methodSelections", plan.methodSelections());
                if (GraphEvidence.encodedUtf8LengthCapped(inline, INLINE_PAYLOAD_LIMIT_BYTES) <= INLINE_PAYLOAD_LIMIT_BYTES) {
                    return artifact("spring.plan", inline);
                }
            }
            var fw = inv.frameworkEvidence();
            var fwSummary = new TreeMap<String, Object>();
            fwSummary.put("completeClasspath", fw.completeClasspath());
            fwSummary.put("classpathManifestIdentity", fw.classpathManifestIdentity());
            fwSummary.put("artifactsCount", fw.artifacts().size());
            fwSummary.put("artifactPages", chunkObjects("spring.plan-framework-artifact-page", "artifacts", fw.artifacts()));

            var invSummary = new TreeMap<String, Object>();
            invSummary.put("identity", inv.identity());
            invSummary.put("schemaVersion", inv.schemaVersion());
            invSummary.put("analysis", inv.analysis());
            invSummary.put("provider", inv.provider());
            invSummary.put("mechanismCatalog", inv.mechanismCatalog());
            invSummary.put("catalogRevision", inv.catalogRevision());
            invSummary.put("frontendResultIdentity", inv.frontendResultIdentity());
            invSummary.put("frameworkEvidence", fwSummary);
            invSummary.put("coverage", inv.coverage());
            invSummary.put("limitations", inv.limitations());
            invSummary.put("resourceEvidencePages", chunkObjects("spring.plan-resource-page", "resources", inv.resourceEvidence()));
            invSummary.put("annotationDeclarationPages", chunkObjects("spring.plan-ann-decl-page", "declarations", inv.annotationDeclarations()));
            invSummary.put("annotationMetaEdgePages", chunkObjects("spring.plan-ann-edge-page", "edges", inv.annotationMetaEdges()));
            invSummary.put("annotationCyclePages", chunkObjects("spring.plan-ann-cycle-page", "cycles", inv.annotationCycles()));
            invSummary.put("rawObservationPages", chunkObjects("spring.plan-raw-obs-page", "observations", inv.rawObservations()));
            invSummary.put("obligationPages", chunkObjects("spring.plan-obligation-page", "obligations", inv.obligations()));
            invSummary.put("markerObservationPages", indexPages("spring.plan-marker-page", inv.markerObservations(), ContentDigest::value));
            invSummary.put("problemPages", chunkObjects("spring.plan-inv-problem-page", "problems", inv.problems()));

            var condExpressions = new TreeMap<com.evolution.analysis.spring.condition.ConditionExpression.Identity,
                    com.evolution.analysis.spring.condition.ConditionExpression.View>();
            for (var occ : cond.occurrences()) {
                var stack = new ArrayDeque<com.evolution.analysis.spring.condition.ConditionExpression>();
                stack.push(occ.expression());
                while (!stack.isEmpty()) {
                    var node = stack.pop();
                    if (condExpressions.putIfAbsent(node.identity(), node.view()) == null) {
                        for (var child : node.children()) stack.push(child);
                    }
                }
            }
            var condSummary = new TreeMap<String, Object>();
            condSummary.put("schema", "spring-condition-lowering-v1");
            condSummary.put("provider", com.evolution.analysis.spring.condition.ConditionEvidenceLowering.PROVIDER);
            condSummary.put("identity", cond.identity());
            condSummary.put("inputIdentity", cond.inputIdentity());
            condSummary.put("inventoryIdentity", cond.inventoryIdentity());
            condSummary.put("semantics", cond.semantics());
            if (cond.validationMode() != com.evolution.analysis.spring.SpringFrameworkEvidence.ValidationMode.STRICT_R0_PINNED) {
                condSummary.put("validationMode", cond.validationMode().name());
            }
            condSummary.put("coverage", cond.coverage());
            condSummary.put("rowsCount", cond.rows().size());
            condSummary.put("rowPages", chunkObjects("spring.plan-cond-row-page", "rows", cond.rows()));
            condSummary.put("expressionsCount", condExpressions.size());
            condSummary.put("expressionPages", chunkObjects("spring.plan-cond-expr-page", "expressions", condExpressions.values()));
            condSummary.put("issuesCount", cond.issues().size());
            condSummary.put("issuePages", chunkObjects("spring.plan-cond-issue-page", "issues", cond.issues()));
            condSummary.put("capabilityGapsCount", cond.capabilityGaps().size());
            condSummary.put("capabilityGapPages", indexPages("spring.plan-cond-gap-page", cond.capabilityGaps(),
                    g -> g.gapIdentity().value()));
            condSummary.put("sourceCandidatesCount", cond.sourceCandidates().size());
            condSummary.put("sourceCandidatePages", chunkObjects("spring.plan-cond-candidate-page", "sourceCandidates", cond.sourceCandidates()));

            var regSummary = new TreeMap<String, Object>();
            regSummary.put("identity", reg.identity());
            regSummary.put("discoveryPlanIdentity", discovery.identity());
            regSummary.put("order", reg.order());
            regSummary.put("orderEvidence", reg.orderEvidence());
            regSummary.put("initialRegistry", reg.initialRegistry());
            regSummary.put("noParentContainer", reg.noParentContainer());
            regSummary.put("closureEvidence", reg.closureEvidence());
            regSummary.put("limits", reg.limits());
            regSummary.put("establishedOrderPrefixCount", reg.establishedOrderPrefix().size());
            regSummary.put("establishedOrderPrefixPages", indexPages("spring.plan-reg-prefix-page",
                    reg.establishedOrderPrefix(), s -> s));
            regSummary.put("stepsCount", reg.steps().size());
            regSummary.put("stepPages", chunkObjects("spring.plan-reg-step-page", "steps", reg.steps()));
            regSummary.put("definitionsCount", reg.definitions().size());
            regSummary.put("definitionPages", chunkObjects("spring.plan-reg-def-page", "definitions", reg.definitions()));
            regSummary.put("queriesCount", reg.queries().size());
            regSummary.put("queryPages", chunkObjects("spring.plan-reg-query-page", "queries", reg.queries()));
            regSummary.put("queryTypesCount", reg.queryTypes().size());
            regSummary.put("queryTypePages", chunkObjects("spring.plan-reg-qtype-page", "queryTypes", reg.queryTypes()));

            var env = binding.environment();
            Object envObj;
            if (env.candidateOrder().size() <= MAX_CHUNK_ITEMS
                    && GraphEvidence.encodedUtf8LengthCapped(env, 64_000L) <= 64_000L) {
                envObj = env;
            } else {
                var envSummary = new TreeMap<String, Object>();
                envSummary.put("descriptorsComplete", env.descriptorsComplete());
                envSummary.put("noResolvableDependencies", env.noResolvableDependencies());
                envSummary.put("noPostRegistrationMutation", env.noPostRegistrationMutation());
                envSummary.put("comparator", env.comparator());
                envSummary.put("candidateOrderCount", env.candidateOrder().size());
                envSummary.put("candidateOrderPages", indexPages("spring.plan-env-order-page", env.candidateOrder(), s -> s));
                envSummary.put("candidateOrderComplete", env.candidateOrderComplete());
                envSummary.put("evidence", env.evidence());
                envObj = envSummary;
            }

            var bindingSummary = new TreeMap<String, Object>();
            bindingSummary.put("identity", binding.identity());
            bindingSummary.put("registrationPlan", regSummary);
            bindingSummary.put("environment", envObj);
            bindingSummary.put("limits", binding.limits());
            bindingSummary.put("dependenciesCount", binding.dependencies().size());
            bindingSummary.put("dependencyPages", chunkObjects("spring.plan-binding-dep-page", "dependencies", binding.dependencies()));
            bindingSummary.put("definitionsCount", binding.definitions().size());
            bindingSummary.put("definitionPages", chunkObjects("spring.plan-binding-def-page", "definitions", binding.definitions()));
            bindingSummary.put("matchesCount", binding.matches().size());
            bindingSummary.put("matchPages", chunkObjects("spring.plan-binding-match-page", "matches", binding.matches()));
            bindingSummary.put("groupsCount", binding.groups().size());
            bindingSummary.put("groupPages", chunkObjects("spring.plan-binding-group-page", "groups", binding.groups()));
            bindingSummary.put("obligationsCount", binding.obligations().size());
            bindingSummary.put("obligationPages", chunkObjects("spring.plan-binding-obl-page", "obligations", binding.obligations()));

            var discoverySummary = new TreeMap<String, Object>();
            discoverySummary.put("identity", discovery.identity());
            discoverySummary.put("buildContextIdentity", discovery.buildContext().identity());
            discoverySummary.put("frameworkSemantics", discovery.lowering().semantics());
            discoverySummary.put("overridePolicy", discovery.overridePolicy());
            discoverySummary.put("orderCompleteness", discovery.orderStatus());
            discoverySummary.put("discoveryLimits", discovery.limits());
            discoverySummary.put("containerHierarchy", List.of(discovery.container()));
            discoverySummary.put("coverage", discovery.coverage());
            discoverySummary.put("eventsCount", discovery.events().size());
            discoverySummary.put("eventPages", chunkObjects("spring.plan-disc-event-page", "events", discovery.events()));
            discoverySummary.put("precedenceCount", discovery.precedence().size());
            discoverySummary.put("precedencePages", chunkObjects("spring.plan-disc-prec-page", "precedence", discovery.precedence()));
            discoverySummary.put("initialDefinitionsCount", discovery.initialDefinitions().size());
            discoverySummary.put("initialDefinitionPages", chunkObjects("spring.plan-disc-init-page", "initialDefinitions", discovery.initialDefinitions()));
            discoverySummary.put("rowsCount", discovery.rows().size());
            discoverySummary.put("rowPages", chunkObjects("spring.plan-disc-row-page", "rows", discovery.rows()));
            discoverySummary.put("issuesCount", discovery.issues().size());
            discoverySummary.put("issuePages", chunkObjects("spring.plan-disc-issue-page", "issues", discovery.issues()));
            discoverySummary.put("capabilityGapsCount", discovery.capabilityGaps().size());
            discoverySummary.put("capabilityGapPages", indexPages("spring.plan-disc-gap-page", discovery.capabilityGaps(),
                    g -> g.gapIdentity().value()));

            var planManifest = new TreeMap<String, Object>();
            planManifest.put("schema", "spring.plan:m8.4-paged-v1");
            planManifest.put("planIdentity", plan.identity());
            planManifest.put("inputIdentity", plan.inputIdentity());
            planManifest.put("inventory", invSummary);
            planManifest.put("conditions", condSummary);
            planManifest.put("binding", bindingSummary);
            planManifest.put("discovery", discoverySummary);
            planManifest.put("gapsCount", plan.gaps().size());
            planManifest.put("gapPages", indexPages("spring.plan-gap-index-page", plan.gaps(), g -> g.gapIdentity().value()));
            planManifest.put("methodSelectionsCount", plan.methodSelections().size());
            planManifest.put("methodSelectionPages", chunkObjects("spring.plan-method-selection-page",
                    "methodSelections", plan.methodSelections()));
            return artifact("spring.plan", planManifest);
        }

        ContentDigest emitExplicitResult(TruthRegionEvaluation.Result truth) {
            if (truth.worlds().size() <= MAX_CHUNK_ITEMS
                    && truth.regions().size() <= MAX_CHUNK_ITEMS
                    && truth.witnesses().size() <= MAX_CHUNK_ITEMS
                    && truth.replays().size() <= MAX_CHUNK_ITEMS
                    && truth.operationalOutcomes().size() <= MAX_CHUNK_ITEMS
                    && truth.issues().size() <= MAX_CHUNK_ITEMS
                    && truth.capabilityGaps().size() <= MAX_CHUNK_ITEMS
                    && GraphEvidence.encodedUtf8LengthCapped(truth, MAX_CHUNK_UTF8_BYTES) <= MAX_CHUNK_UTF8_BYTES) {
                return artifact("spring.explicit-result", truth);
            }
            var worldDigests = new ArrayList<String>(truth.worlds().size());
            for (var w : truth.worlds()) worldDigests.add(CanonicalJson.sha256(w.canonicalForm()).value());
            var regionDigests = new ArrayList<String>(truth.regions().size());
            for (var r : truth.regions()) regionDigests.add(CanonicalJson.sha256(r).value());
            var witnessDigests = new ArrayList<String>(truth.witnesses().size());
            for (var w : truth.witnesses()) witnessDigests.add(CanonicalJson.sha256(w.canonicalForm()).value());
            var replayDigests = new ArrayList<String>(truth.replays().size());
            for (var r : truth.replays()) replayDigests.add(CanonicalJson.sha256(r).value());

            var payload = new TreeMap<String, Object>();
            payload.put("schema", "spring-truth-region-result-v1");
            payload.put("pagedSchema", "spring.explicit-result:m8.4-paged-v1");
            payload.put("provider", TruthProcessing.PROVIDER);
            payload.put("identity", truth.identity());
            payload.put("inputIdentity", truth.inputIdentity());
            payload.put("semanticsContextIdentity", truth.semanticsContextIdentity());
            payload.put("legalOrderIdentity", truth.legalOrderIdentity());
            payload.put("coverage", truth.coverage());
            payload.put("feasibility", truth.feasibility());
            payload.put("worldsCount", truth.worlds().size());
            payload.put("worldDigestPages", indexPages("spring.explicit-world-digest-page", worldDigests, s -> s));
            payload.put("regionsCount", truth.regions().size());
            payload.put("regionDigestPages", indexPages("spring.explicit-region-digest-page", regionDigests, s -> s));
            payload.put("witnessesCount", truth.witnesses().size());
            payload.put("witnessDigestPages", indexPages("spring.explicit-witness-digest-page", witnessDigests, s -> s));
            payload.put("replaysCount", truth.replays().size());
            payload.put("replayDigestPages", indexPages("spring.explicit-replay-digest-page", replayDigests, s -> s));
            payload.put("operationalOutcomesCount", truth.operationalOutcomes().size());
            payload.put("operationalOutcomePages", chunkObjects("spring.explicit-outcome-page", "operationalOutcomes", truth.operationalOutcomes()));
            payload.put("issuesCount", truth.issues().size());
            payload.put("issuePages", chunkObjects("spring.explicit-issue-page", "issues", truth.issues()));
            payload.put("capabilityGapsCount", truth.capabilityGaps().size());
            payload.put("gapPages", indexPages("spring.explicit-gap-index-page", truth.capabilityGaps(), g -> g.gapIdentity().value()));
            return artifact("spring.explicit-result", payload);
        }

        ContentDigest emitSymbolicTrace(SymbolicTruthRegions.Trace trace) {
            if (trace.facts().size() <= MAX_CHUNK_ITEMS
                    && GraphEvidence.encodedUtf8LengthCapped(trace, MAX_CHUNK_UTF8_BYTES) <= MAX_CHUNK_UTF8_BYTES) {
                return artifact("spring.symbolic-trace", trace);
            }
            var factPairs = new ArrayList<List<Object>>(trace.facts().size());
            for (var e : trace.facts().entrySet()) {
                factPairs.add(List.of(e.getKey(), e.getValue()));
            }
            var payload = new TreeMap<String, Object>();
            payload.put("schema", "spring.symbolic-trace:m8.4-paged-v1");
            payload.put("traceDigest", GraphEvidence.canonicalSha256(trace));
            payload.put("region", trace.region());
            payload.put("representative", trace.representative());
            payload.put("bindingResult", trace.bindingResult());
            payload.put("operation", trace.operation());
            payload.put("replayed", trace.replayed());
            payload.put("factsCount", factPairs.size());
            payload.put("factPages", chunkObjects("spring.symbolic-trace-fact-page", "facts", factPairs));
            return artifact("spring.symbolic-trace", payload);
        }

        String repository(CanonicalGraphInput input) {
            String context = snapshot.value();
            var snapshotPayload = artifact("repository.snapshot", input.repository().snapshot());
            contexts.put(context, new Context(context, snapshot, Optional.empty(), snapshotPayload));
            node(input.repository().snapshot().repository().value(), context, "REPOSITORY", snapshotPayload);
            input.acquisition().ifPresent(this::emitAcquisition);
            input.repository().files().values().forEach(s -> {
                var payload = sourceBytes(s);
                node(s.document().identity().value(), context, "SOURCE_DOCUMENT", payload);
            });
            input.build().ifPresent(build -> {
                emitBuildModel(build);
                Map<UniversalSourceIngestion.SourceSet, List<UniversalSourceIngestion.SourceRow>> ownedBySet = new HashMap<>();
                input.ingestion().ifPresent(ingestion -> {
                    for (var row : ingestion.sourceIngestion().sources()) {
                        if (row.status() == UniversalSourceIngestion.Status.OWNED
                                && input.repository().files().containsKey(row.path())) {
                            for (var claim : row.claims()) {
                                ownedBySet.computeIfAbsent(claim, k -> new ArrayList<>()).add(row);
                            }
                        }
                    }
                });
                for (var module : build.modules()) {
                    var payload = artifact("build.module", module);
                    String id = module.descriptor().identity().value();
                    node(id, context, "MODULE", payload);
                    link(context, "graph.contains", input.repository().snapshot().repository().value(), id, payload);
                    for (var set : module.sourcePlan().sourceSets()) {
                        var setKey = new UniversalSourceIngestion.SourceSet(module.descriptor().identity(), set.kind(),
                                set.name());
                        var claimedRows = ownedBySet.getOrDefault(setKey, List.of());
                        for (var root : set.sourceRoots())
                            root.value().ifPresent(path -> {
                                String rootId = derivedIdentity("source-root",
                                        List.of(id, set.kind(), set.name(), path));
                                node(rootId, context, "SOURCE_ROOT", payload);
                                link(context, "graph.contains", id, rootId, payload);
                                for (var row : claimedRows) {
                                    if (path.equals(".") || row.path().startsWith(path + "/")) {
                                        link(context, "graph.owns-source", rootId, input.repository().files()
                                                .get(row.path()).document().identity().value(),
                                                artifact("source.ownership-row", row));
                                    }
                                }
                            });
                    }
                }
            });
            return context;
        }

        void frontend(AnalysisManifest manifest, FrontendResult frontend, ContentDigest springContextDigest,
                String modulePath) {
            if (!manifest.identity().equals(frontend.analysis()) || !manifest.snapshot().identity().equals(snapshot))
                throw new IllegalArgumentException("Foreign frontend context");
            String context = manifest.identity().value();
            var manifestPayload = artifact("analysis.manifest", compactManifest(manifest));
            var snapshotDocs = new LinkedHashMap<String, SourceDocument>(
                    Math.max(16, manifest.snapshot().documents().size() * 2));
            for (var d : manifest.snapshot().documents())
                snapshotDocs.put(d.identity().value(), d);
            var documents = snapshotDocs.keySet();
            var referencedDocs = new HashSet<String>();
            frontend.sources().forEach(s -> {
                String id = s.document().value();
                document(documents, id);
                referencedDocs.add(id);
            });
            frontend.observations().forEach(o -> {
                String id = o.document().value();
                document(documents, id);
                referencedDocs.add(id);
            });
            frontend.types().forEach(t -> {
                String id = t.span().document().value();
                document(documents, id);
                referencedDocs.add(id);
            });
            frontend.annotations().forEach(a -> {
                String id = a.span().document().value();
                document(documents, id);
                referencedDocs.add(id);
            });
            frontend.derivedRelationships().forEach(r -> r.supportingSpans().forEach(s -> {
                String id = s.document().value();
                document(documents, id);
                referencedDocs.add(id);
            }));
            frontend.declarations()
                    .forEach(d -> d.entity().declaration().ifPresent(s -> referencedDocs.add(s.document().value())));
            frontend.occurrences().forEach(o -> referencedDocs.add(o.span().document().value()));
            frontend.httpClientCalls().forEach(c -> {
                String id = c.span().document().value();
                document(documents, id);
                referencedDocs.add(id);
            });
            var prior = contexts.putIfAbsent(context,
                    new Context(context, snapshot, Optional.of(manifest.identity()), manifestPayload));
            if (prior != null && !prior.evidence().equals(manifestPayload))
                throw new IllegalArgumentException("Conflicting manifest");
            ledger(CapabilityGapNormalizer.normalize(EvidenceContext.forAnalysis(manifest),
                    EvidenceNormalizationInput.builder().frontendResults(List.of(frontend)).build()));
            String unitModule = manifest.configuration().values().get("java.module");
            String unitSourceSet = manifest.configuration().values().get("java.source-set");
            var knownModules = new HashSet<String>(Math.max(16, manifest.modules().size() * 2));
            for (var m : manifest.modules())
                knownModules.add(m.identity().value());
            List<SourceDocument> contextDocs;
            List<ModuleDescriptor> contextModules;
            if (unitModule == null) {
                contextDocs = manifest.snapshot().documents();
                contextModules = manifest.modules();
            } else {
                contextDocs = manifest.snapshot().documents().stream()
                        .filter(d -> referencedDocs.contains(d.identity().value())
                                || (d.module().value().equals(unitModule)
                                        && matchesSourceSet(d.classification(), unitSourceSet)))
                        .toList();
                var activeModules = new HashSet<String>();
                activeModules.add(unitModule);
                contextDocs.forEach(d -> activeModules.add(d.module().value()));
                for (var declaration : frontend.declarations())
                    if (declaration.entity().origin() == EntityOrigin.PROJECT)
                        activeModules.add(declaration.entity().stableScope().value());
                contextModules = manifest.modules().stream().filter(m -> activeModules.contains(m.identity().value()))
                        .toList();
            }
            for (var module : contextModules)
                node(module.identity().value(), context, "MODULE", moduleArtifact(module));
            for (var document : contextDocs)
                node(document.identity().value(), context, "SOURCE_DOCUMENT", documentArtifact(document));

            ContentDigest unitPolyglotDigest = modulePolyglotDigest != null
                    ? modulePolyglotDigest.apply(modulePath) : null;
            ContentDigest structuralKey = projector != null
                    ? IncrementalGraphProjector.frontendStructuralKey(
                            frontend, fastArchitecture, springContextDigest, unitPolyglotDigest)
                    : null;
            var cachedTemplate = structuralKey != null ? projector.getFrontendTemplate(structuralKey) : null;
            if (cachedTemplate != null) {
                for (String scope : cachedTemplate.projectEntityScopes()) {
                    if (!knownModules.contains(scope))
                        throw new IllegalArgumentException("Foreign project entity scope");
                }
                for (String docId : cachedTemplate.declarationDocIds()) {
                    if (!nodes.containsKey(key(context, docId)))
                        throw new IllegalArgumentException("Declaration source absent from manifest");
                }
                for (String docId : cachedTemplate.occurrenceDocIds()) {
                    if (!nodes.containsKey(key(context, docId)))
                        throw new IllegalArgumentException("Occurrence source absent from manifest");
                }
                for (var ev : cachedTemplate.evidence()) {
                    var old = evidence.putIfAbsent(ev.identity().value(), ev);
                    if (old != null && !old.equals(ev))
                        throw new IllegalArgumentException("Conflicting provider artifact");
                }
                for (var st : cachedTemplate.statements()) {
                    statement(context, st.family(), st.identity(), st.payload());
                }
                for (var n : cachedTemplate.nodes()) {
                    for (var sup : n.evidence()) {
                        node(n.identity(), context, n.kind(), sup);
                    }
                }
                if (!nativeSupports.isEmpty()) {
                    for (var ref : cachedTemplate.declarationSupports()) {
                        for (String support : ref.inputIdentities()) {
                            for (var reference : nativeSupports.getOrDefault(support, Set.of())) {
                                node(ref.entityIdentity(), context, ref.entityKind(), reference);
                            }
                        }
                    }
                }
                for (var e : cachedTemplate.edges()) {
                    edge(new Edge(e.identity(), context, e.kind(), e.source(), e.target(), e.evidence(), e.region()));
                }
                for (var mg : cachedTemplate.missingOwnerGaps()) {
                    projectionGap(context, mg.relationIdentity(), mg.payload(), "TYPE_OWNER_UNAVAILABLE");
                }
                var httpClientCallPayloads = new ArrayList<ContentDigest>(frontend.httpClientCalls().size());
                for (var call : frontend.httpClientCalls()) {
                    var document = Objects.requireNonNull(snapshotDocs.get(call.span().document().value()));
                    var payload = artifact("java.http-client-call",
                            Map.of("call", call, "analysis", manifest.identity(), "sourceContent", document.contentDigest(),
                                    "binding", "COMPILE_TIME", "runtimeObserved", false));
                    httpClientCallPayloads.add(payload);
                    String site = derivedIdentity("http-client-call", payload);
                    node(site, context, "SOURCE_HTTP_CALL_CANDIDATE", payload);
                    link(context, "graph.has-http-client-call", call.caller().value(), site, payload);
                }
                emitFrontendResultIndex(
                        frontend,
                        cachedTemplate.sourceSummaries(),
                        cachedTemplate.diagnosticPayloads(),
                        cachedTemplate.declarationPayloads(),
                        cachedTemplate.occurrencePayloads(),
                        cachedTemplate.observationPayloads(),
                        cachedTemplate.typeUsePayloads(),
                        cachedTemplate.annotationPayloads(),
                        cachedTemplate.derivedPayloads(),
                        cachedTemplate.typeShapePayloads(),
                        cachedTemplate.memberShapePayloads(),
                        cachedTemplate.ctorCandidatePayloads(),
                        httpClientCallPayloads);
                return;
            }

            TemplateRecorder recorder = structuralKey != null ? new TemplateRecorder(structuralKey) : null;
            var byCanonical = new HashMap<String, Entity>();
            var byId = new HashMap<String, Entity>();
            var declarationPayloads = new ArrayList<ContentDigest>(frontend.declarations().size());
            for (var declaration : frontend.declarations()) {
                var entity = declaration.entity();
                var payload = recordArtifact(recorder, "java.declaration", declaration);
                declarationPayloads.add(payload);
                if (entity.origin() == EntityOrigin.PROJECT) {
                    if (!knownModules.contains(entity.stableScope().value()))
                        throw new IllegalArgumentException("Foreign project entity scope");
                    if (recorder != null) recorder.projectEntityScopes.add(entity.stableScope().value());
                }
                recordStatement(recorder, context, "java.declaration", entity.identity().value(), payload);
                recordNode(recorder, entity.identity().value(), context, entity.kind().name(), payload);
                if (recorder != null && !declaration.derivation().inputIdentities().isEmpty()) {
                    recorder.declarationSupports.add(new IncrementalGraphProjector.DeclarationSupportRef(
                            entity.identity().value(), entity.kind().name(), declaration.derivation().inputIdentities()));
                }
                for (String support : declaration.derivation().inputIdentities())
                    for (var reference : nativeSupports.getOrDefault(support, Set.of()))
                        node(entity.identity().value(), context, entity.kind().name(), reference);
                byCanonical.put(entity.stableScope().value() + "|" + entity.canonicalName(), entity);
                byId.put(entity.identity().value(), entity);
                entity.declaration().ifPresent(span -> {
                    if (!nodes.containsKey(key(context, span.document().value())))
                        throw new IllegalArgumentException("Declaration source absent from manifest");
                    if (recorder != null) recorder.declarationDocIds.add(span.document().value());
                });
            }
            for (int i = 0; i < frontend.declarations().size(); i++) {
                var declaration = frontend.declarations().get(i);
                var entity = declaration.entity();
                var payload = declarationPayloads.get(i);
                var parsedTuple = tuple(entity.canonicalName());
                if (parsedTuple.isPresent()) {
                    var parts = parsedTuple.get();
                    if (parts.size() >= 2 && parts.get(1) instanceof List<?> owner
                            && !Set.of("package", "type").contains(parts.getFirst())) {
                        var parent = byCanonical
                                .get(entity.stableScope().value() + "|java:v1:" + CanonicalJson.write(owner));
                        if (parent != null)
                            recordLink(recorder, context, "graph.declares", parent.identity().value(), entity.identity().value(),
                                    payload);
                    }
                    if (entity.kind() == EntityKind.TYPE) {
                        packageNameFromTuple(parts).ifPresent(name -> {
                            var pkg = EntityIdentity.from(entity.origin(), entity.stableScope(), EntityKind.PACKAGE,
                                    JavaSymbolName.packageName(name).canonicalName());
                            recordNode(recorder, pkg.value(), context, "PACKAGE", payload);
                            recordLink(recorder, context, "graph.contains", pkg.value(), entity.identity().value(), payload);
                            if (entity.origin() == EntityOrigin.PROJECT)
                                recordLink(recorder, context, "graph.contains", entity.stableScope().value(), pkg.value(), payload);
                        });
                    }
                }
            }
            var typeOwnerCache = new HashMap<String, Optional<Entity>>(Math.max(16, byId.size() * 2));
            var occurrencePayloads = new ArrayList<ContentDigest>(frontend.occurrences().size());
            for (var occurrence : frontend.occurrences()) {
                String occDocId = occurrence.span().document().value();
                if (!nodes.containsKey(key(context, occDocId)))
                    throw new IllegalArgumentException("Occurrence source absent from manifest");
                if (recorder != null) recorder.occurrenceDocIds.add(occDocId);
                var payload = recordArtifact(recorder, "java.occurrence", occurrence);
                occurrencePayloads.add(payload);
                recordNode(recorder, occurrence.identity().value(), context, "OCCURRENCE", payload);
                recordStatement(recorder, context, "java.occurrence", occurrence.identity().value(), payload);
                recordRelationship(recorder, context, occurrence.relationship(), payload);
                if (occurrence.status() == SemanticStatus.RESOLVED)
                    recordDependency(recorder, context, occurrence.relationship(), byCanonical, byId, typeOwnerCache, payload);
            }
            var derivedPayloads = new ArrayList<ContentDigest>(frontend.derivedRelationships().size());
            for (var derived : frontend.derivedRelationships()) {
                var payload = recordArtifact(recorder, "java.derived-relationship", derived);
                derivedPayloads.add(payload);
                recordRelationship(recorder, context, derived.relationship(), payload);
                if (derived.status() == SemanticStatus.RESOLVED)
                    recordDependency(recorder, context, derived.relationship(), byCanonical, byId, typeOwnerCache, payload);
            }
            var httpClientCallPayloads = new ArrayList<ContentDigest>(frontend.httpClientCalls().size());
            for (var call : frontend.httpClientCalls()) {
                var document = Objects.requireNonNull(snapshotDocs.get(call.span().document().value()));
                var payload = artifact("java.http-client-call",
                        Map.of("call", call, "analysis", manifest.identity(), "sourceContent", document.contentDigest(),
                                "binding", "COMPILE_TIME", "runtimeObserved", false));
                httpClientCallPayloads.add(payload);
                String site = derivedIdentity("http-client-call", payload);
                node(site, context, "SOURCE_HTTP_CALL_CANDIDATE", payload);
                link(context, "graph.has-http-client-call", call.caller().value(), site, payload);
            }
            var observationPayloads = new ArrayList<ContentDigest>(frontend.observations().size());
            for (var observation : frontend.observations()) {
                var payload = recordArtifact(recorder, "java.observation", observation);
                observationPayloads.add(payload);
                recordNode(recorder, derivedIdentity("observation", payload), context, "OBSERVATION", payload);
            }
            var sourceSummaries = new ArrayList<Map<String, Object>>(frontend.sources().size());
            for (var s : frontend.sources()) {
                var payload = recordArtifact(recorder, "java.source-outcome", s);
                sourceSummaries.add(Map.of("document", s.document(), "state", s.state(), "evidence", payload));
            }
            var typeUsePayloads = new ArrayList<ContentDigest>(frontend.types().size());
            for (var t : frontend.types()) {
                var payload = recordArtifact(recorder, "java.type-use", t);
                typeUsePayloads.add(payload);
                String id = derivedIdentity("type-use", payload);
                recordNode(recorder, id, context, "TYPE_USE", payload);
                t.owner().ifPresent(owner -> recordLink(recorder, context, "graph.has-type-use", owner.value(), id, payload));
            }
            var annotationPayloads = new ArrayList<ContentDigest>(frontend.annotations().size());
            for (var a : frontend.annotations()) {
                var payload = recordArtifact(recorder, "java.annotation-use", a);
                annotationPayloads.add(payload);
                var owner = byId.get(a.owner().value());
                if (owner == null)
                    throw new IllegalArgumentException("Annotation owner is absent");
                String id = EntityIdentity
                        .from(owner.origin(), owner.stableScope(), EntityKind.ANNOTATION_USE, a.canonicalName())
                        .value();
                recordNode(recorder, id, context, "ANNOTATION_USE", payload);
                recordLink(recorder, context, "graph.has-annotation-use", owner.identity().value(), id, payload);
            }
            var typeShapePayloads = frontend.typeDeclarations().stream().map(t -> recordArtifact(recorder, "java.type-shape", t))
                    .toList();
            var memberShapePayloads = frontend.memberDeclarations().stream().map(m -> recordArtifact(recorder, "java.member-shape", m))
                    .toList();
            var ctorCandidatePayloads = new ArrayList<ContentDigest>(frontend.constructorCandidates().size());
            for (var c : frontend.constructorCandidates()) {
                var payload = recordArtifact(recorder, "java.lombok-constructor-candidate", c);
                ctorCandidatePayloads.add(payload);
                String id = derivedIdentity("constructor-candidate", payload);
                recordNode(recorder, id, context, "CONSTRUCTOR_INJECTION_CANDIDATE", payload);
                recordLink(recorder, context, "graph.has-constructor-candidate", c.owner().value(), id, payload);
                recordLink(recorder, context, "graph.candidate-field", id, c.field().value(), payload);
            }
            var diagnosticPayloads = frontend.diagnostics().stream().map(d -> recordArtifact(recorder, "java.diagnostic", d)).toList();
            if (recorder != null) {
                projector.putFrontendTemplate(recorder.build(
                        declarationPayloads, occurrencePayloads, observationPayloads, typeUsePayloads,
                        annotationPayloads, derivedPayloads, typeShapePayloads, memberShapePayloads,
                        ctorCandidatePayloads, diagnosticPayloads, sourceSummaries));
            }
            emitFrontendResultIndex(
                    frontend,
                    sourceSummaries,
                    diagnosticPayloads,
                    declarationPayloads,
                    occurrencePayloads,
                    observationPayloads,
                    typeUsePayloads,
                    annotationPayloads,
                    derivedPayloads,
                    typeShapePayloads,
                    memberShapePayloads,
                    ctorCandidatePayloads,
                    httpClientCallPayloads);
        }

        private final class TemplateRecorder {
            final ContentDigest structuralKey;
            final Map<String, GraphEvidence> evidenceMap = new LinkedHashMap<>();
            final Map<String, IncrementalGraphProjector.ContextNode> nodeMap = new LinkedHashMap<>();
            final Map<String, IncrementalGraphProjector.ContextEdge> edgeMap = new LinkedHashMap<>();
            final List<IncrementalGraphProjector.StatementRef> statementRefs = new ArrayList<>();
            final List<IncrementalGraphProjector.DeclarationSupportRef> declarationSupports = new ArrayList<>();
            final List<IncrementalGraphProjector.MissingOwnerGap> missingOwnerGaps = new ArrayList<>();
            final Set<String> projectEntityScopes = new LinkedHashSet<>();
            final Set<String> declarationDocIds = new LinkedHashSet<>();
            final Set<String> occurrenceDocIds = new LinkedHashSet<>();

            TemplateRecorder(ContentDigest structuralKey) {
                this.structuralKey = structuralKey;
            }

            void addNode(String id, String kind, ContentDigest sup) {
                var old = nodeMap.get(id);
                if (old == null) {
                    nodeMap.put(id, new IncrementalGraphProjector.ContextNode(id, kind, List.of(sup)));
                    return;
                }
                if (old.evidence().contains(sup)) return;
                var set = new TreeSet<>(old.evidence());
                set.add(sup);
                nodeMap.put(id, new IncrementalGraphProjector.ContextNode(id, kind, List.copyOf(set)));
            }

            void addEdge(Edge e) {
                var old = edgeMap.get(e.identity());
                if (old == null) {
                    edgeMap.put(e.identity(), new IncrementalGraphProjector.ContextEdge(
                            e.identity(), e.kind(), e.source(), e.target(), e.evidence(), e.region()));
                    return;
                }
                if (old.evidence().containsAll(e.evidence())) return;
                var set = new TreeSet<>(old.evidence());
                set.addAll(e.evidence());
                edgeMap.put(e.identity(), new IncrementalGraphProjector.ContextEdge(
                        old.identity(), old.kind(), old.source(), old.target(), List.copyOf(set), old.region()));
            }

            IncrementalGraphProjector.ProjectedFrontendTemplate build(
                    List<ContentDigest> declarationPayloads,
                    List<ContentDigest> occurrencePayloads,
                    List<ContentDigest> observationPayloads,
                    List<ContentDigest> typeUsePayloads,
                    List<ContentDigest> annotationPayloads,
                    List<ContentDigest> derivedPayloads,
                    List<ContentDigest> typeShapePayloads,
                    List<ContentDigest> memberShapePayloads,
                    List<ContentDigest> ctorCandidatePayloads,
                    List<ContentDigest> diagnosticPayloads,
                    List<Map<String, Object>> sourceSummaries) {
                return new IncrementalGraphProjector.ProjectedFrontendTemplate(
                        structuralKey,
                        List.copyOf(evidenceMap.values()),
                        List.copyOf(nodeMap.values()),
                        List.copyOf(edgeMap.values()),
                        List.copyOf(statementRefs),
                        List.copyOf(declarationSupports),
                        List.copyOf(missingOwnerGaps),
                        List.copyOf(projectEntityScopes),
                        List.copyOf(declarationDocIds),
                        List.copyOf(occurrenceDocIds),
                        List.copyOf(declarationPayloads),
                        List.copyOf(occurrencePayloads),
                        List.copyOf(observationPayloads),
                        List.copyOf(typeUsePayloads),
                        List.copyOf(annotationPayloads),
                        List.copyOf(derivedPayloads),
                        List.copyOf(typeShapePayloads),
                        List.copyOf(memberShapePayloads),
                        List.copyOf(ctorCandidatePayloads),
                        List.copyOf(diagnosticPayloads),
                        List.copyOf(sourceSummaries));
            }
        }

        private ContentDigest recordArtifact(TemplateRecorder recorder, String family, Object value) {
            ContentDigest id = artifact(family, value);
            if (recorder != null) {
                var ev = evidence.get(id.value());
                if (ev != null) recorder.evidenceMap.putIfAbsent(id.value(), ev);
            }
            return id;
        }

        private void recordStatement(TemplateRecorder recorder, String context, String family, String id, ContentDigest payload) {
            statement(context, family, id, payload);
            if (recorder != null) recorder.statementRefs.add(new IncrementalGraphProjector.StatementRef(family, id, payload));
        }

        private void recordNode(TemplateRecorder recorder, String id, String context, String kind, ContentDigest payload) {
            node(id, context, kind, payload);
            if (recorder != null) recorder.addNode(id, kind, payload);
        }

        private void recordLink(TemplateRecorder recorder, String context, String kind, String source, String target, ContentDigest payload) {
            String id = derivedIdentity("graph-relation", List.of("projection:m5.1-v1", kind, source, target));
            var e = new Edge(id, context, kind, source,
                    new Target(TargetState.RESOLVED, List.of(target), Optional.empty()), List.of(payload),
                    Optional.empty());
            edge(e);
            if (recorder != null) recorder.addEdge(e);
        }

        private void recordRelationship(TemplateRecorder recorder, String context, SemanticRelationship relation, ContentDigest payload) {
            Target target = switch (relation.target()) {
                case RelationshipTarget.Resolved r ->
                    new Target(TargetState.RESOLVED, List.of(r.target().value()), Optional.empty());
                case RelationshipTarget.Candidates c -> new Target(TargetState.CANDIDATES,
                        c.candidates().stream().map(EntityIdentity::value).toList(), Optional.empty());
                case RelationshipTarget.Unresolved u ->
                    new Target(TargetState.UNRESOLVED, List.of(), Optional.of(u.stableReference()));
            };
            var e = new Edge(relation.identity().value(), context, relation.kind().value(), relation.source().value(),
                    target, List.of(payload), Optional.empty());
            edge(e);
            if (recorder != null) recorder.addEdge(e);
        }

        private void recordDependency(TemplateRecorder recorder, String context, SemanticRelationship relation,
                Map<String, Entity> byCanonical, Map<String, Entity> byId,
                Map<String, Optional<Entity>> typeOwnerCache, ContentDigest payload) {
            if (Set.of("java.declares", "java.has-parameter").contains(relation.kind().value())
                    || !(relation.target() instanceof RelationshipTarget.Resolved target))
                return;
            Entity from = cachedTypeOwner(byId.get(relation.source().value()), byCanonical, typeOwnerCache),
                    to = cachedTypeOwner(byId.get(target.target().value()), byCanonical, typeOwnerCache);
            if (from != null && to != null && !from.identity().equals(to.identity())) {
                recordLink(recorder, context, "graph.depends-on", from.identity().value(), to.identity().value(), payload);
            } else if (from == null || to == null) {
                var source = byId.get(relation.source().value());
                if (source != null && source.kind() != EntityKind.PACKAGE) {
                    projectionGap(context, relation.identity().value(), payload, "TYPE_OWNER_UNAVAILABLE");
                    if (recorder != null) {
                        recorder.missingOwnerGaps.add(new IncrementalGraphProjector.MissingOwnerGap(
                                relation.identity().value(), payload));
                    }
                }
            }
        }

        void relationship(String context, SemanticRelationship relation, ContentDigest payload) {
            Target target = switch (relation.target()) {
                case RelationshipTarget.Resolved r ->
                    new Target(TargetState.RESOLVED, List.of(r.target().value()), Optional.empty());
                case RelationshipTarget.Candidates c -> new Target(TargetState.CANDIDATES,
                        c.candidates().stream().map(EntityIdentity::value).toList(), Optional.empty());
                case RelationshipTarget.Unresolved u ->
                    new Target(TargetState.UNRESOLVED, List.of(), Optional.of(u.stableReference()));
            };
            edge(new Edge(relation.identity().value(), context, relation.kind().value(), relation.source().value(),
                    target, List.of(payload), Optional.empty()));
        }

        void dependency(String context, SemanticRelationship relation, Map<String, Entity> byCanonical,
                Map<String, Entity> byId, Map<String, Optional<Entity>> typeOwnerCache, ContentDigest payload) {
            if (Set.of("java.declares", "java.has-parameter").contains(relation.kind().value())
                    || !(relation.target() instanceof RelationshipTarget.Resolved target))
                return;
            Entity from = cachedTypeOwner(byId.get(relation.source().value()), byCanonical, typeOwnerCache),
                    to = cachedTypeOwner(byId.get(target.target().value()), byCanonical, typeOwnerCache);
            if (from != null && to != null && !from.identity().equals(to.identity()))
                link(context, "graph.depends-on", from.identity().value(), to.identity().value(), payload);
            else if (from == null || to == null) {
                var source = byId.get(relation.source().value());
                if (source != null && source.kind() != EntityKind.PACKAGE)
                    projectionGap(context, relation.identity().value(), payload, "TYPE_OWNER_UNAVAILABLE");
            }
        }

        Entity cachedTypeOwner(Entity entity, Map<String, Entity> entities, Map<String, Optional<Entity>> cache) {
            if (entity == null)
                return null;
            if (entity.kind() == EntityKind.TYPE)
                return entity;
            var cached = cache.get(entity.identity().value());
            if (cached != null)
                return cached.orElse(null);
            Entity resolved = typeOwner(entity, entities);
            cache.put(entity.identity().value(), Optional.ofNullable(resolved));
            return resolved;
        }

        Entity typeOwner(Entity entity, Map<String, Entity> entities) {
            var seen = new HashSet<String>();
            while (entity != null && entity.kind() != EntityKind.TYPE && seen.add(entity.identity().value())) {
                var owner = ownerTuple(entity.canonicalName());
                if (owner.isEmpty())
                    return null;
                entity = entities
                        .get(entity.stableScope().value() + "|java:v1:" + CanonicalJson.write(owner.orElseThrow()));
            }
            return entity != null && entity.kind() == EntityKind.TYPE ? entity : null;
        }

        void spring(CanonicalGraphInput.SpringSlice slice) {
            var plan = slice.plan();
            var discovery = plan.binding().registrationPlan().discoveryPlan();
            String analysis = discovery.buildContext().analysisIdentity().value();
            if (!contexts.containsKey(analysis) || !discovery.buildContext().snapshotIdentity().equals(snapshot))
                throw new IllegalArgumentException("Foreign Spring build context");
            String context = slice.explicitTruth().map(t -> t.semanticsContextIdentity().value())
                    .orElseGet(() -> derivedIdentity("spring-context", List.of(
                            analysis, plan.identity(), slice.space().map(s -> s.identity()),
                            slice.symbolicTruth().map(SymbolicTruthRegions.StagedResult::inputIdentity),
                            slice.parameters())));
            var contextPayload = artifact("spring.context",
                    Map.of("analysis", analysis, "build", discovery.buildContext(), "planIdentity", plan.identity(),
                            "space", slice.space().map(s -> s.identity()), "parameters", slice.parameters()));
            var oldContext = contexts.putIfAbsent(context, new Context(context, snapshot,
                    Optional.of(discovery.buildContext().analysisIdentity()), contextPayload));
            if (oldContext != null && !oldContext.evidence().equals(contextPayload))
                throw new IllegalArgumentException("Conflicting Spring context");
            // Embedded plan/result gaps must also participate in the graph's searchable
            // denominator.
            plan.gaps().forEach(g -> gapRecord(context, g));
            var planPayload = emitSpringPlan(plan);
            var candidates = new TreeMap<String, BeanDefinitionCandidate>();
            discovery.initialDefinitions().forEach(c -> candidate(candidates, c));
            discovery.events().forEach(e -> e.candidate().ifPresent(c -> candidate(candidates, c)));
            for (var candidate : candidates.values()) {
                var candidatePayload = artifact("spring.candidate", candidate);
                statement(context, "spring.candidate", candidate.identity().value(), candidatePayload);
                node(candidate.producer().identity().value(), context, "BEAN_PRODUCER",
                        artifact("spring.producer", candidate.producer()));
                node(candidate.identity().value(), context, "BEAN_DEFINITION_CANDIDATE", candidatePayload);
                link(context, "spring.produces-candidate", candidate.producer().identity().value(),
                        candidate.identity().value(), planPayload);
            }
            for (var dep : plan.binding().dependencies()) {
                var payload = artifact("spring.injection-dependency", dep);
                node(dep.point().identity().value(), context, "INJECTION_POINT", payload);
                String use = derivedIdentity("injection-use",
                        List.of(dep.point().identity(), dep.owner(), dep.identity()));
                node(use, context, "INJECTION_USE", payload);
                link(context, "spring.has-site", use, dep.point().identity().value(), payload);
                String siteKey = key(context, dep.point().identity().value()) + "|" + dep.owner();
                var previousUse = injectionUses.putIfAbsent(siteKey, use);
                if (previousUse != null && !previousUse.equals(use))
                    throw new IllegalArgumentException("Conflicting contextual injection descriptor");
                dep.owner().ifPresent(owner -> link(context, "spring.declares-injection", owner.value(), use, payload));
            }
            for (var event : discovery.events())
                node(event.identity().value(), context, "REGISTRATION_EVENT",
                        artifact("spring.registration-event", event));
            for (var precedence : discovery.precedence())
                link(context, "spring.precedes-registration", precedence.before().value(), precedence.after().value(),
                        artifact("spring.registration-precedence", precedence));
            var transitions = new TreeMap<String, String>();
            for (var step : plan.binding().registrationPlan().steps()) {
                var payload = artifact("spring.registration-step", step);
                String id = derivedIdentity("registration-transition",
                        List.of(plan.binding().registrationPlan().identity(), step.identity()));
                transitions.put(step.key(), id);
                node(id, context, "REGISTRATION_TRANSITION", payload);
                link(context, "spring.invokes-event", id, step.discoveryEvent().value(), payload);
            }
            var prefix = plan.binding().registrationPlan().establishedOrderPrefix();
            for (int i = 1; i < prefix.size(); i++)
                link(context, "spring.precedes-transition", transitions.get(prefix.get(i - 1)),
                        transitions.get(prefix.get(i)), planPayload);
            var expressions = new HashSet<String>();
            var conditionRows = new ArrayList<>(plan.conditions().occurrences());
            slice.space().ifPresent(s -> conditionRows.addAll(s.constraints()));
            for (var condition : conditionRows) {
                var payload = artifact("spring.condition", condition);
                node(condition.identity().value(), context, "CONFIGURATION_CONDITION", payload);
                var pending = new ArrayDeque<com.evolution.analysis.spring.condition.ConditionExpression>();
                pending.add(condition.expression());
                while (!pending.isEmpty()) {
                    var expression = pending.removeFirst();
                    if (!expressions.add(expression.identity().value()))
                        continue;
                    var expressionPayload = artifact("spring.condition-expression", expression.view());
                    node(expression.identity().value(), context, "CONDITION_EXPRESSION", expressionPayload);
                    for (var child : expression.children()) {
                        pending.add(child);
                        link(context, "spring.expression-input", expression.identity().value(),
                                child.identity().value(), expressionPayload);
                    }
                }
                link(context, "spring.condition-expression", condition.identity().value(),
                        condition.expression().identity().value(), payload);
            }
            for (var event : discovery.events())
                for (var condition : event.conditions())
                    link(context, "spring.has-condition", event.identity().value(), condition.occurrence().value(),
                            planPayload);
            slice.space().ifPresent(s -> {
                artifact("spring.space", s);
                s.envelopes().forEach(e -> artifact("spring.configuration-envelope", e));
            });
            slice.baseline().ifPresent(b -> artifact("spring.baseline", b));
            slice.parameters().ifPresent(p -> artifact("spring.evaluation-parameters", p));
            slice.explicitTruth().ifPresent(truth -> {
                truth.capabilityGaps().forEach(g -> gapRecord(context, g));
                emitExplicitResult(truth);
                truth.worlds().forEach(
                        w -> node(w.identity().value(), context, "CONFIGURATION_WORLD", artifact("spring.world", w)));
                for (var region : truth.regions()) {
                    var payload = artifact("spring.explicit-region",
                            Map.of("encoding", "explicit-worlds-m4d-v1", "space",
                                    slice.space().orElseThrow().identity(), "semanticsContext",
                                    truth.semanticsContextIdentity(), "region", region));
                    node(region.identity().value(), context, "TRUTH_REGION", payload);
                    fact(context, region.fact(), region.identity().value(), payload);
                    region.trueWorlds().forEach(w -> link(context, "spring.region-true-world",
                            region.identity().value(), w.value(), payload));
                    region.falseWorlds().forEach(w -> link(context, "spring.region-false-world",
                            region.identity().value(), w.value(), payload));
                    region.unknownWorlds().forEach(w -> link(context, "spring.region-unknown-world",
                            region.identity().value(), w.value(), payload));
                }
                truth.witnesses().forEach(w -> {
                    var payload = artifact("spring.witness", w);
                    node(w.identity().value(), context, "CONFIGURATION_WITNESS", payload);
                    link(context, "spring.has-witness", w.factRegionIdentity().value(), w.identity().value(), payload);
                });
                truth.replays().forEach(r -> {
                    var payload = artifact("spring.witness-replay", r);
                    String id = derivedIdentity("witness-replay", payload);
                    node(id, context, "WITNESS_REPLAY", payload);
                    if (nodes.containsKey(key(context, r.witness().value())))
                        link(context, "spring.has-replay", r.witness().value(), id, payload);
                });
            });
            slice.symbolicTruth().ifPresent(truth -> {
                truth.gaps().forEach(g -> gapRecord(context, g));
                truth.formulas().forEach(f -> {
                    var payload = artifact("spring.formula", f);
                    node(f.identity().value(), context, "REGION_FORMULA", payload);
                    f.children().forEach(child -> link(context, "spring.formula-input", f.identity().value(),
                            child.value(), payload));
                });
                var regionDigests = new ArrayList<String>(truth.regions().size());
                for (var region : truth.regions()) {
                    regionDigests.add(CanonicalJson.sha256(region).value());
                    var payload = artifact("spring.symbolic-region",
                            Map.of("encoding", "symbolic-dag-m4u.2-v1", "space", slice.space().orElseThrow().identity(),
                                    "evaluationInput", truth.inputIdentity(), "region", region));
                    String id = derivedIdentity("symbolic-region", List.of(truth.inputIdentity(), payload));
                    node(id, context, "TRUTH_REGION", payload);
                    fact(context, region.fact(), id, payload);
                    link(context, "spring.true-formula", id, region.trueRegion().value(), payload);
                    link(context, "spring.false-formula", id, region.falseRegion().value(), payload);
                    link(context, "spring.unknown-formula", id, region.unknownRegion().value(), payload);
                }
                var traceEvidenceIds = new ArrayList<ContentDigest>(truth.traces().size());
                for (var trace : truth.traces()) {
                    traceEvidenceIds.add(emitSymbolicTrace(trace));
                }
                var queryPages = chunkObjects("spring.symbolic-query-page", "queries", truth.queries());
                var issuePages = chunkObjects("spring.symbolic-issue-page", "issues", truth.issues());
                // Index lossless symbolic result records into bounded pages instead of duplicating
                // every trace, region, formula, query and gap inside one monolithic JSON payload.
                var resultPayload = new TreeMap<String, Object>();
                resultPayload.put("schema", "spring.symbolic-result:m8.4-paged-v1");
                resultPayload.put("inputIdentity", truth.inputIdentity());
                resultPayload.put("resultIdentity", truth.identity());
                resultPayload.put("signaturesComplete", truth.signaturesComplete());
                resultPayload.put("regionsCount", truth.regions().size());
                resultPayload.put("tracesCount", truth.traces().size());
                resultPayload.put("formulasCount", truth.formulas().size());
                resultPayload.put("queriesCount", truth.queries().size());
                resultPayload.put("issuesCount", truth.issues().size());
                resultPayload.put("gapsCount", truth.gaps().size());
                resultPayload.put("regionDigestPages",
                        indexPages("spring.symbolic-region-digest-page", regionDigests, s -> s));
                resultPayload.put("traceEvidencePages",
                        indexPages("spring.symbolic-trace-index-page", traceEvidenceIds, ContentDigest::value));
                resultPayload.put("formulaPages",
                        indexPages("spring.symbolic-formula-index-page", truth.formulas(), f -> f.identity().value()));
                resultPayload.put("queryPages", queryPages);
                resultPayload.put("issuePages", issuePages);
                resultPayload.put("gapPages",
                        indexPages("spring.symbolic-gap-index-page", truth.gaps(), g -> g.gapIdentity().value()));
                artifact("spring.symbolic-result", resultPayload);
            });
        }

        void fact(String context, ConditionalFactKey fact, String region, ContentDigest payload) {
            node(fact.identity().value(), context, "CONDITIONAL_FACT", artifact("spring.fact-key", fact));
            link(context, "spring.has-truth-region", fact.identity().value(), region, payload);
            fact.definition().ifPresent(
                    d -> link(context, "spring.definition-fact", fact.identity().value(), d.value(), payload));
            if (fact.kind() != ConditionalFactKey.Kind.DEFINITION_PRESENT) {
                String id = derivedIdentity("conditional-edge", List.of(fact.identity(), region));
                edge(new Edge(id, context, "spring." + fact.kind().name().toLowerCase(Locale.ROOT).replace('_', '-'),
                        Optional.ofNullable(injectionUses.get(
                                key(context, fact.injectionPoint().orElseThrow().value()) + "|" + fact.bindingOwner()))
                                .orElseThrow(() -> new IllegalArgumentException(
                                        "Conditional fact has no contextual injection use")),
                        new Target(TargetState.RESOLVED, List.of(fact.target().orElseThrow().value()),
                                Optional.empty()),
                        List.of(payload), Optional.of(region)));
            }
        }

        void candidate(Map<String, BeanDefinitionCandidate> candidates, BeanDefinitionCandidate candidate) {
            var old = candidates.putIfAbsent(candidate.identity().value(), candidate);
            if (old != null && !old.equals(candidate))
                throw new IllegalArgumentException("Conflicting candidate payload");
        }

        void node(String identity, String context, String kind, ContentDigest payload) {
            String key = key(context, identity);
            var old = nodes.get(key);
            if (old != null && !old.kind().equals(kind))
                throw new IllegalArgumentException("Conflicting graph node kind");
            if (old != null && old.evidence().contains(payload))
                return;
            var support = new TreeSet<ContentDigest>();
            if (old != null)
                support.addAll(old.evidence());
            support.add(payload);
            nodes.put(key, new Node(identity, context, kind, List.copyOf(support)));
        }

        void statement(String context, String family, String identity, ContentDigest payload) {
            var prior = statements.putIfAbsent(context + "|" + family + "|" + identity, payload);
            if (prior != null && !prior.equals(payload))
                throw new IllegalArgumentException("Conflicting canonical provider statement");
        }

        void document(Set<String> documents, String identity) {
            if (!documents.contains(identity))
                throw new IllegalArgumentException("Foreign provider source document");
        }

        void ledger(EvidenceAcquisitionLedger ledger) {
            if (!ledger.context().snapshotIdentity().equals(snapshot))
                throw new IllegalArgumentException("Foreign evidence ledger snapshot");
            String context = ledger.context().analysisIdentity().map(AnalysisIdentity::value).orElse(snapshot.value());
            if (!contexts.containsKey(context))
                throw new IllegalArgumentException("Foreign evidence ledger analysis");
            if (ledger.gaps().size() <= MAX_CHUNK_ITEMS
                    && ledger.attempts().size() <= MAX_CHUNK_ITEMS
                    && ledger.conflicts().size() <= MAX_CHUNK_ITEMS
                    && ledger.resolutions().size() <= MAX_CHUNK_ITEMS) {
                var inlineLedger = Map.of("indexSchema", "evidence.ledger-index:m7.2-v1", "identity", ledger.identity(),
                        "schemaVersion", ledger.schemaVersion(), "normalizer", ledger.normalizer(),
                        "context", ledger.context(), "gaps",
                        ledger.gaps().stream().map(g -> g.gapIdentity()).toList(), "attempts", ledger.attempts(),
                        "conflicts", ledger.conflicts(), "resolutions", ledger.resolutions());
                if (GraphEvidence.encodedUtf8LengthCapped(inlineLedger, INLINE_PAYLOAD_LIMIT_BYTES) <= INLINE_PAYLOAD_LIMIT_BYTES) {
                    artifact("evidence.ledger-index", inlineLedger);
                } else {
                    emitPagedLedgerIndex(ledger);
                }
            } else {
                emitPagedLedgerIndex(ledger);
            }
            ledger.gaps().forEach(g -> gapRecord(context, g));
            ledger.attempts().forEach(a -> node(a.attemptIdentity().value(), context, "ACQUISITION_ATTEMPT",
                    artifact("evidence.attempt", a)));
            ledger.conflicts().forEach(c -> node(c.conflictIdentity().value(), context, "PROVIDER_CONFLICT",
                    artifact("evidence.conflict", c)));
            ledger.resolutions().forEach(r -> artifact("evidence.resolution", r));
        }

        private void emitPagedLedgerIndex(EvidenceAcquisitionLedger ledger) {
            var paged = new TreeMap<String, Object>();
            paged.put("indexSchema", "evidence.ledger-index:m8.4-paged-v2");
            paged.put("identity", ledger.identity());
            paged.put("schemaVersion", ledger.schemaVersion());
            paged.put("normalizer", ledger.normalizer());
            paged.put("context", ledger.context());
            paged.put("gapsCount", ledger.gaps().size());
            paged.put("gapPages", indexPages("evidence.ledger-gap-page", ledger.gaps(), g -> g.gapIdentity().value()));
            paged.put("attemptsCount", ledger.attempts().size());
            paged.put("attemptPages", indexPages("evidence.ledger-attempt-page", ledger.attempts(), a -> a.attemptIdentity().value()));
            paged.put("conflictsCount", ledger.conflicts().size());
            paged.put("conflictPages", indexPages("evidence.ledger-conflict-page", ledger.conflicts(), c -> c.conflictIdentity().value()));
            paged.put("resolutionsCount", ledger.resolutions().size());
            paged.put("resolutionPages", chunkObjects("evidence.ledger-resolution-page", "resolutions", ledger.resolutions()));
            artifact("evidence.ledger-index", paged);
        }

        void link(String context, String kind, String source, String target, ContentDigest payload) {
            String id = derivedIdentity("graph-relation", List.of("projection:m5.1-v1", kind, source, target));
            edge(new Edge(id, context, kind, source,
                    new Target(TargetState.RESOLVED, List.of(target), Optional.empty()), List.of(payload),
                    Optional.empty()));
        }

        void edge(Edge edge) {
            String key = key(edge.context(), edge.identity());
            var old = edges.get(key);
            if (old == null) {
                edges.put(key, edge);
                return;
            }
            if (!old.kind().equals(edge.kind()) || !old.source().equals(edge.source())
                    || !old.target().equals(edge.target()) || !old.region().equals(edge.region()))
                throw new IllegalArgumentException("Conflicting graph relationship");
            if (old.evidence().containsAll(edge.evidence()))
                return;
            var supports = new TreeSet<>(old.evidence());
            supports.addAll(edge.evidence());
            edges.put(key, new Edge(old.identity(), old.context(), old.kind(), old.source(), old.target(),
                    List.copyOf(supports), old.region()));
        }

        void gapRecord(String context, CapabilityGapRecord gap) {
            if (!gap.context().snapshotIdentity().equals(snapshot)
                    && !javaBase.filter(gap.context().snapshotIdentity()::equals).isPresent())
                throw new IllegalArgumentException("Foreign graph capability gap snapshot");
            var id = gap.gapIdentity();
            var encoded = gapEvidence.get(id);
            if (encoded == null || !encoded.record().equals(gap)) {
                encoded = new EncodedGap(gap, artifact("evidence.gap", gap));
                gapEvidence.put(id, encoded);
            }
            var payload = encoded.evidence();
            String kind = historicalGaps.contains(id.value()) ? "HISTORICAL_CAPABILITY_GAP" : "CAPABILITY_GAP";
            node(id.value(), context, kind, payload);
            var disposition = gapDispositions.get(id.value());
            if (disposition != null)
                node(id.value(), context, kind, disposition);
            String expectedContext = gap.context().analysisIdentity().map(AnalysisIdentity::value)
                    .orElse(snapshot.value());
            gap.acquisitionAttemptReferences()
                    .forEach(ref -> requiredAttempts.put(key(expectedContext, ref.value()), payload));
        }

        void gapRecords(String context, List<CapabilityGapRecord> gaps, List<CapabilityGapRecord> projected) {
            // The ingestion and final lists are usually sorted by gap ID. Walk the
            // earlier list once and use the node's existing evidence only on exact
            // record equality. No index proportional to the gap population is needed.
            var previous = projected.iterator();
            CapabilityGapRecord prior = previous.hasNext() ? previous.next() : null;
            for (var gap : gaps) {
                while (prior != null && prior.compareTo(gap) < 0)
                    prior = previous.hasNext() ? previous.next() : null;
                if (gap.equals(prior) && gap.acquisitionAttemptReferences().isEmpty()) {
                    // Same repository node, kind, evidence and disposition already written.
                    continue;
                }
                gapRecord(context, gap);
            }
        }

        void projectionGap(String context, String subjectIdentity, ContentDigest support, String reason) {
            var provider = new VersionedIdentifier("graph.canonical-builder", "m5.1-v1");
            var observation = ProviderObservationReference.create(provider, "graph.projection", support,
                    ContentDigest.sha256Utf8(subjectIdentity));
            var subject = EvidenceSubject.observation(observation.identity());
            var requirement = new EvidenceRequirement(EvidenceRequirement.Kind.REPOSITORY_CONTENT,
                    "graph.resolve-supporting-evidence", List.of(subject),
                    EvidenceRequirement.AuthorizationClass.PASSIVE,
                    List.of("Supply the exact missing referenced evidence or neutral owner declaration."));
            var gap = CapabilityGapRecord.create(new VersionedIdentifier("evidence.graph-gaps", "m5.1-v1"),
                    new EvidenceContext(snapshot, contexts.get(context).analysis()), provider,
                    "graph.projection", reason, subject, List.of(), List.of(observation), List.of(requirement),
                    List.of(),
                    List.of(new AffectedOutput(AffectedOutput.Kind.GRAPH_PROJECTION, "graph.canonical")), List.of(),
                    List.of(), List.of("Retained upstream facts are not proof of the missing projection evidence."));
            node(gap.gapIdentity().value(), context, "CAPABILITY_GAP", artifact("evidence.gap", gap));
            artifact("graph.unavailable-reference", Map.of("subject", subjectIdentity, "availability", "UNAVAILABLE",
                    "support", support, "gap", gap.gapIdentity()));
        }

        void complete() {
            for (var reference : requiredAttempts.entrySet())
                if (!nodes.containsKey(reference.getKey())) {
                    String context = reference.getKey().substring(0, reference.getKey().indexOf('|'));
                    if (!contexts.containsKey(context))
                        context = contexts.containsKey(snapshot.value()) ? snapshot.value()
                                : contexts.keySet().iterator().next();
                    projectionGap(context, reference.getKey(), reference.getValue(), "REFERENCED_EVIDENCE_UNAVAILABLE");
                }
        }

        KnowledgeGraph finish() {
            complete();
            return new KnowledgeGraph(snapshot, List.copyOf(contexts.values()), List.copyOf(nodes.values()),
                    List.copyOf(edges.values()), List.copyOf(evidence.values()));
        }
    }

    private static Map<String, Object> compactSnapshot(RepositorySnapshot s) {
        var map = new TreeMap<String, Object>();
        map.put("identity", s.identity());
        map.put("repository", s.repository());
        map.put("revision", s.revision());
        map.put("dirty", s.dirty());
        map.put("contentDigest", s.contentDigest());
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, Object> compactManifest(AnalysisManifest m) {
        var map = new TreeMap<String, Object>();
        map.put("identity", m.identity());
        map.put("manifestVersion", m.manifestVersion());
        map.put("snapshot", compactSnapshot(m.snapshot()));
        map.put("modules", m.modules());
        map.put("classpath", m.classpath());
        map.put("configuration", m.configuration());
        map.put("analyzer", m.analyzer());
        map.put("ruleSet", m.ruleSet());
        map.put("graphSchema", m.graphSchema());
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, Object> compactRequestManifest(AnalysisManifest m) {
        var map = new TreeMap<String, Object>();
        map.put("identity", m.identity());
        map.put("manifestVersion", m.manifestVersion());
        map.put("snapshot", compactSnapshot(m.snapshot()));
        map.put("configuration", m.configuration());
        map.put("analyzer", m.analyzer());
        map.put("ruleSet", m.ruleSet());
        map.put("graphSchema", m.graphSchema());
        return Collections.unmodifiableMap(map);
    }

    private static boolean matchesSourceSet(SourceClassification docRole, String unitSourceSet) {
        if ("MAIN".equals(unitSourceSet))
            return docRole == SourceClassification.MAIN || docRole == SourceClassification.GENERATED_MAIN;
        if ("TEST".equals(unitSourceSet))
            return docRole == SourceClassification.TEST || docRole == SourceClassification.GENERATED_TEST;
        return unitSourceSet == null || docRole.name().equals(unitSourceSet);
    }

    private static Optional<List<Object>> ownerTuple(String name) {
        var tuple = tuple(name);
        if (tuple.isEmpty())
            return Optional.empty();
        var parts = tuple.orElseThrow();
        if (parts.size() < 2 || !(parts.get(1) instanceof List<?> owner)
                || Set.of("package", "type").contains(parts.getFirst()))
            return Optional.empty();
        return Optional.of(new ArrayList<>(owner));
    }

    private static Optional<List<Object>> tuple(String name) {
        if (!name.startsWith("java:v1:"))
            return Optional.empty();
        try {
            return Optional
                    .of(GraphJson.array(GraphJson.read(name.substring(8), new GraphJson.Limits(100000, 64, 10000))));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    private static Optional<String> packageName(String name) {
        var tuple = tuple(name);
        if (tuple.isEmpty())
            return Optional.empty();
        return packageNameFromTuple(tuple.orElseThrow());
    }

    private static Optional<String> packageNameFromTuple(List<Object> parts) {
        for (int depth = 0; depth < 64; depth++) {
            if (parts.isEmpty())
                return Optional.empty();
            if (parts.getFirst().equals("type"))
                return Optional
                        .of(String.join(".", GraphJson.array(parts.get(1)).stream().map(GraphJson::string).toList()));
            if (parts.size() < 2 || !(parts.get(1) instanceof List<?>))
                return Optional.empty();
            parts = GraphJson.array(parts.get(1));
        }
        return Optional.empty();
    }
}
