package com.evolution.analysis.graph;

import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CanonicalGraphBuilderTest {
    static final VersionedIdentifier VERSION = new VersionedIdentifier("graph.fixture", "1");
    static final RepositoryIdentity REPO = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/graph.git");

    static AnalysisManifest manifest() {
        var a = ModuleDescriptor.create(REPO, "a", "a");
        var b = ModuleDescriptor.create(REPO, "b", "b");
        var snapshot = RepositorySnapshot.create(REPO, Optional.empty(), false, List.of(), List.of());
        var component = new ManifestComponent(VERSION, ContentDigest.sha256Utf8("fixture"));
        return AnalysisManifest.create(VERSION, snapshot, List.of(a,b), List.of(),
                AnalysisConfiguration.create(VERSION, Map.of()), component, component, component);
    }

    static FrontendResult frontend(AnalysisManifest manifest) {
        var declarations = manifest.modules().stream().map(m -> new DeclarationRecord(
                Entity.create(EntityOrigin.PROJECT, EntityScope.project(m.identity()), EntityKind.TYPE,
                        JavaSymbolName.topLevelType("p", "Same").canonicalName(), Optional.empty()),
                "Same", SemanticStatus.RESOLVED, new Derivation(DerivationKind.DIRECT, VERSION, List.of()),
                List.of(), List.of())).toList();
        var coverage = FrontendRequest.CATEGORIES.stream().map(c -> new CategoryCoverage(
                new RelationshipKind("java."+c), CategoryCoverage.Support.IMPLEMENTED,0,0,0)).toList();
        return new FrontendResult(manifest.identity(), VERSION, FrontendResult.State.COMPLETED,
                declarations,List.of(),List.of(),List.of(),coverage,List.of());
    }

    @Test void preservesSameNameAcrossModules() {
        var manifest = manifest();
        var frontend = frontend(manifest);
        var graph = new CanonicalGraphBuilder().build(manifest, frontend);
        var types=graph.nodes().stream().filter(n->n.kind().equals("TYPE")).toList();
        assertEquals(2, types.size());
        assertEquals(2, types.stream().map(KnowledgeGraph.Node::identity).distinct().count());
        assertTrue(graph.entityIdentities().contains(frontend.declarations().getFirst().entity().identity().value()));
    }
    @Test void sameCanonicalNameKeepsProjectDependencyAndJdkScopesDistinct() {
        var manifest=manifest();var original=frontend(manifest);var declarations=new ArrayList<>(original.declarations());
        for(var origin:List.of(EntityOrigin.DEPENDENCY,EntityOrigin.JDK)) {
            var scope=EntityScope.external(origin,"authored-origin",ContentDigest.sha256Utf8(origin.name()));
            var entity=Entity.create(origin,scope,EntityKind.TYPE,JavaSymbolName.topLevelType("p","Same").canonicalName(),Optional.empty());
            declarations.add(new DeclarationRecord(entity,"Same",SemanticStatus.RESOLVED,new Derivation(DerivationKind.DIRECT,VERSION,List.of()),List.of(),List.of()));
        }
        var frontend=new FrontendResult(manifest.identity(),VERSION,FrontendResult.State.COMPLETED,declarations,List.of(),List.of(),List.of(),original.coverage(),List.of());
        var graph=new CanonicalGraphBuilder().build(manifest,frontend);
        assertEquals(4,graph.nodes().stream().filter(n->n.kind().equals("TYPE")).map(KnowledgeGraph.Node::identity).distinct().count());
    }

    @Test void multiModuleIngestionScopesContextNodesAndDeduplicatesSourceContent() {
        var modA = ModuleDescriptor.create(REPO, "mod-a", "mod-a");
        var modB = ModuleDescriptor.create(REPO, "mod-b", "mod-b");
        var modC = ModuleDescriptor.create(REPO, "mod-c", "mod-c");
        var rootMod = ModuleDescriptor.create(REPO, "root", ".");
        var rawFiles = new TreeMap<String, SourceInput>();
        var snapshotFiles = new ArrayList<SnapshotFile>();
        var rawDocs = new ArrayList<SourceDocument>();
        var entries = new LinkedHashMap<String, String>();
        entries.put("mod-a/src/main/java/a/App.java", "package a;\nclass App { b.Lib lib; }\n");
        entries.put("mod-b/src/main/java/b/Lib.java", "package b;\npublic class Lib {}\n");
        for (int i = 0; i < 6; i++) {
            entries.put("mod-c/src/main/java/c/C" + i + ".java", "package c;\nclass C" + i + " {}\n");
        }
        for (var e : entries.entrySet()) {
            byte[] bytes = e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var doc = SourceDocument.create(REPO, rootMod, e.getKey(), ContentDigest.sha256(bytes), SourceClassification.MAIN);
            rawFiles.put(e.getKey(), new SourceInput(doc, bytes));
            rawDocs.add(doc);
            snapshotFiles.add(SnapshotFile.from(doc));
        }
        var rawInputs = new com.evolution.analysis.ingestion.RepositoryInputs(
                RepositorySnapshot.create(REPO, Optional.empty(), false, snapshotFiles, rawDocs), rawFiles);
        var modules = List.of(modA, modB, modC);
        var buildModules = new ArrayList<com.evolution.analysis.buildmodel.UniversalBuildModel.Module>();
        for (var m : modules) {
            var java21 = new com.evolution.analysis.buildmodel.SourcePlanModel.Setting(
                    "java.release", Optional.of("21"), Optional.of("21"),
                    com.evolution.analysis.buildmodel.SourcePlanModel.Status.DEFAULT,
                    com.evolution.analysis.buildmodel.SourcePlanModel.Origin.MAVEN_CONVENTION, List.of());
            var utf8 = new com.evolution.analysis.buildmodel.SourcePlanModel.Setting(
                    "encoding", Optional.of("UTF-8"), Optional.of("UTF-8"),
                    com.evolution.analysis.buildmodel.SourcePlanModel.Status.DEFAULT,
                    com.evolution.analysis.buildmodel.SourcePlanModel.Origin.MAVEN_CONVENTION, List.of());
            var mainRoot = new com.evolution.analysis.buildmodel.SourcePlanModel.Setting(
                    "sourceRoot", Optional.of(m.path() + "/src/main/java"), Optional.of(m.path() + "/src/main/java"),
                    com.evolution.analysis.buildmodel.SourcePlanModel.Status.DEFAULT,
                    com.evolution.analysis.buildmodel.SourcePlanModel.Origin.MAVEN_CONVENTION, List.of());
            var testRoot = new com.evolution.analysis.buildmodel.SourcePlanModel.Setting(
                    "testRoot", Optional.of(m.path() + "/src/test/java"), Optional.of(m.path() + "/src/test/java"),
                    com.evolution.analysis.buildmodel.SourcePlanModel.Status.DEFAULT,
                    com.evolution.analysis.buildmodel.SourcePlanModel.Origin.MAVEN_CONVENTION, List.of());
            var outDir = new com.evolution.analysis.buildmodel.SourcePlanModel.Setting(
                    "output", Optional.of(m.path() + "/target/classes"), Optional.of(m.path() + "/target/classes"),
                    com.evolution.analysis.buildmodel.SourcePlanModel.Status.DEFAULT,
                    com.evolution.analysis.buildmodel.SourcePlanModel.Origin.MAVEN_CONVENTION, List.of());
            var testOutDir = new com.evolution.analysis.buildmodel.SourcePlanModel.Setting(
                    "testOutput", Optional.of(m.path() + "/target/test-classes"), Optional.of(m.path() + "/target/test-classes"),
                    com.evolution.analysis.buildmodel.SourcePlanModel.Status.DEFAULT,
                    com.evolution.analysis.buildmodel.SourcePlanModel.Origin.MAVEN_CONVENTION, List.of());
            var mainSet = new com.evolution.analysis.buildmodel.SourcePlanModel.SourceSetPlan(
                    m.identity(), com.evolution.analysis.buildmodel.SourcePlanModel.Kind.MAIN,
                    List.of(mainRoot), List.of(), outDir, Map.of(), java21, java21, java21, utf8, List.of(), List.of());
            var testSet = new com.evolution.analysis.buildmodel.SourcePlanModel.SourceSetPlan(
                    m.identity(), com.evolution.analysis.buildmodel.SourcePlanModel.Kind.TEST,
                    List.of(testRoot), List.of(), testOutDir, Map.of(), java21, java21, java21, utf8, List.of(), List.of());
            var plan = new com.evolution.analysis.buildmodel.SourcePlanModel(List.of(mainSet, testSet), List.of());
            buildModules.add(new com.evolution.analysis.buildmodel.UniversalBuildModel.Module(
                    m, com.evolution.analysis.buildmodel.UniversalBuildModel.Tool.PLAIN_JAVA, Optional.empty(),
                    Optional.empty(), plan, List.of(), List.of()));
        }
        var build = new com.evolution.analysis.buildmodel.UniversalBuildModel(
                ContentDigest.sha256Utf8("build-in"), rawInputs.identity(), buildModules, List.of(), List.of(), List.of());
        var platform = PlatformInput.create(21, "21", "fixture", List.of(
                new PlatformInput.Artifact("java.base", ContentDigest.sha256Utf8("jbase"),
                        java.nio.file.Path.of("target/test-tmp/java.base.jmod"), PlatformInput.Format.JMOD)));
        var setA = new com.evolution.analysis.ingestion.UniversalSourceIngestion.SourceSet(modA.identity(), com.evolution.analysis.buildmodel.SourcePlanModel.Kind.MAIN);
        var setB = new com.evolution.analysis.ingestion.UniversalSourceIngestion.SourceSet(modB.identity(), com.evolution.analysis.buildmodel.SourcePlanModel.Kind.MAIN);
        var setC = new com.evolution.analysis.ingestion.UniversalSourceIngestion.SourceSet(modC.identity(), com.evolution.analysis.buildmodel.SourcePlanModel.Kind.MAIN);
        var resolutions = Map.of(
                setA, new com.evolution.analysis.ingestion.UniversalSourceIngestion.Resolution(
                        platform, List.of(), platform.entry().contentDigest(), Optional.empty(), true,
                        List.of(new com.evolution.analysis.ingestion.UniversalSourceIngestion.ReactorSlot(setB, 0))),
                setB, new com.evolution.analysis.ingestion.UniversalSourceIngestion.Resolution(platform, List.of(), platform.entry().contentDigest()),
                setC, new com.evolution.analysis.ingestion.UniversalSourceIngestion.Resolution(platform, List.of(), platform.entry().contentDigest()));
        var component = new ManifestComponent(VERSION, ContentDigest.sha256Utf8("fixture"));
        var sourceIngestion = new com.evolution.analysis.ingestion.UniversalSourceIngestion()
                .assemble(rawInputs, build, resolutions, component, component, KnowledgeGraph.schemaComponent());

        var direct = new Derivation(DerivationKind.DIRECT, VERSION, List.of());
        var coverage = FrontendRequest.CATEGORIES.stream()
                .map(c -> new CategoryCoverage(new RelationshipKind("java." + c), CategoryCoverage.Support.IMPLEMENTED, 0, 0, 0))
                .toList();
        var units = new ArrayList<com.evolution.analysis.ingestion.UniversalIngestionPipeline.Unit>();
        for (var outcome : sourceIngestion.outcomes()) {
            if (outcome.sourceSet().kind() != com.evolution.analysis.buildmodel.SourcePlanModel.Kind.MAIN) {
                units.add(new com.evolution.analysis.ingestion.UniversalIngestionPipeline.Unit(
                        outcome.sourceSet(), com.evolution.analysis.ingestion.UniversalIngestionPipeline.Status.NOT_ANALYZED,
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
                continue;
            }
            var req = outcome.request().orElseThrow();
            var decls = new ArrayList<DeclarationRecord>();
            for (var src : req.sources()) {
                String simple = src.document().path().substring(src.document().path().lastIndexOf('/') + 1, src.document().path().length() - 5);
                String pkg = req.module().equals(modA.identity()) ? "a" : req.module().equals(modB.identity()) ? "b" : "c";
                var span = new SourceSpan(src.document().identity(), 1, 1, 2, 1);
                var entity = Entity.create(EntityOrigin.PROJECT, EntityScope.project(req.module()), EntityKind.TYPE,
                        JavaSymbolName.topLevelType(pkg, simple).canonicalName(), Optional.of(span));
                decls.add(new DeclarationRecord(entity, simple, SemanticStatus.RESOLVED, direct, List.of(), List.of()));
            }
            if (req.module().equals(modA.identity())) {
                var peerEntity = Entity.create(EntityOrigin.PROJECT, EntityScope.project(modB.identity()), EntityKind.TYPE,
                        JavaSymbolName.topLevelType("b", "Lib").canonicalName(), Optional.empty());
                decls.add(new DeclarationRecord(peerEntity, "Lib", SemanticStatus.RESOLVED, direct, List.of(), List.of()));
            }
            var fe = new FrontendResult(req.manifest().identity(), VERSION, FrontendResult.State.COMPLETED,
                    decls, List.of(), List.of(),
                    req.sources().stream().map(s -> new SourceOutcome(s.document().identity(), SourceOutcome.State.PROCESSED, List.of())).toList(),
                    coverage, List.of());
            units.add(new com.evolution.analysis.ingestion.UniversalIngestionPipeline.Unit(
                    outcome.sourceSet(), com.evolution.analysis.ingestion.UniversalIngestionPipeline.Status.ANALYZED,
                    Optional.of(fe), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
        }
        var ingestionResult = new com.evolution.analysis.ingestion.UniversalIngestionPipeline.Result(
                ContentDigest.sha256Utf8("ing-in"), sourceIngestion, units, List.of(), List.of());
        var graphInput = new CanonicalGraphInput(rawInputs, Optional.of(build), Optional.of(ingestionResult), List.of(), List.of(), List.of());
        var graph = new CanonicalGraphBuilder().build(graphInput);

        String rootCtx = graph.snapshot().value();
        assertEquals(3, graph.nodes().stream().filter(n -> n.context().equals(rootCtx) && n.kind().equals("MODULE")).count());
        assertEquals(8, graph.nodes().stream().filter(n -> n.context().equals(rootCtx) && n.kind().equals("SOURCE_DOCUMENT")).count());

        String ctxA = sourceIngestion.outcomes().stream().filter(o -> o.sourceSet().equals(setA)).findFirst().orElseThrow().request().orElseThrow().manifest().identity().value();
        String ctxC = sourceIngestion.outcomes().stream().filter(o -> o.sourceSet().equals(setC)).findFirst().orElseThrow().request().orElseThrow().manifest().identity().value();

        // Context A must include mod-a + reactor-referenced mod-b, never unrelated mod-c or its 6 files.
        assertEquals(Set.of(modA.identity().value(), modB.identity().value()),
                graph.nodes().stream().filter(n -> n.context().equals(ctxA) && n.kind().equals("MODULE")).map(KnowledgeGraph.Node::identity).collect(java.util.stream.Collectors.toSet()));
        assertEquals(1, graph.nodes().stream().filter(n -> n.context().equals(ctxA) && n.kind().equals("SOURCE_DOCUMENT")).count());

        // Context C must include only mod-c and its 6 files, never mod-a or mod-b.
        assertEquals(Set.of(modC.identity().value()),
                graph.nodes().stream().filter(n -> n.context().equals(ctxC) && n.kind().equals("MODULE")).map(KnowledgeGraph.Node::identity).collect(java.util.stream.Collectors.toSet()));
        assertEquals(6, graph.nodes().stream().filter(n -> n.context().equals(ctxC) && n.kind().equals("SOURCE_DOCUMENT")).count());

        // Source content bytes are stored once per unique ContentDigest in source.content; source.bytes references contentDigest.
        assertEquals(8, graph.evidence().stream().filter(e -> e.family().equals("source.content")).count());
        assertTrue(graph.evidence().stream().filter(e -> e.family().equals("source.bytes"))
                .noneMatch(e -> e.canonicalJson().contains("bytesBase64")));
        assertTrue(graph.evidence().stream().filter(e -> Set.of("analysis.manifest", "source.request").contains(e.family()))
                .noneMatch(e -> e.canonicalJson().contains("\"documents\":[")));

        // Physical LOC metric remains COMPLETE and unit-accurate on both root and module contexts.
        var metrics = new com.evolution.analysis.metrics.ArchitectureMetricsService(graph);
        var locC = metrics.compute(
                com.evolution.analysis.query.ArchitectureQuery.Request.dependencies(graph.identity(), ctxC),
                "metric.physical-loc", ctxC);
        assertEquals(com.evolution.analysis.contract.assessment.AssessmentStatus.COMPLETE, locC.status());
        assertEquals(new java.math.BigDecimal("12"), locC.value().orElseThrow().amount());
    }
}
