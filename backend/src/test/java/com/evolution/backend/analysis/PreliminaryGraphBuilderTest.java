package com.evolution.backend.analysis;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.AnalysisIdentity;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.query.ArchitectureQuery;
import com.evolution.analysis.query.ArchitectureQueryService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PreliminaryGraphBuilderTest {

    private static RepositoryIdentity repo() {
        return RepositoryIdentity.fromCanonicalCoordinate("https://example.test/shop.git");
    }

    private static SnapshotIdentity snapshot(RepositoryIdentity repo) {
        return SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("shop-snapshot-v1"));
    }

    private static FastJavaSkeletonScanner.ScanResult sampleSkeleton() {
        String core = "package com.shop.core;\n@Service\npublic class CatalogService {}\n";
        String api = "package com.shop.api;\nimport com.shop.core.CatalogService;\n@RestController\npublic class CatalogController {}\n";
        return new FastJavaSkeletonScanner().scan(List.of(
                new FastJavaSkeletonScanner.SourceFile("core/src/main/java/com/shop/core/CatalogService.java", "core",
                        ContentDigest.sha256Utf8(core), core),
                new FastJavaSkeletonScanner.SourceFile("api/src/main/java/com/shop/api/CatalogController.java", "api",
                        ContentDigest.sha256Utf8(api), api)),
                () -> false);
    }

    @Test
    void stage1PreliminaryGraphCarriesExplicitNonAuthoritativeEvidenceAndAnswersQueries() {
        var repo = repo();
        var snap = snapshot(repo);
        var skeleton = sampleSkeleton();
        var modules = List.of(
                new PreliminaryGraphBuilder.ModuleInfo("core", "core", "MAVEN", 1),
                new PreliminaryGraphBuilder.ModuleInfo("api", "api", "MAVEN", 1));

        var builder = new PreliminaryGraphBuilder();
        var graph = builder.build(PreliminaryGraphBuilder.Input.stage1(
                repo, snap, modules, skeleton, List.of("core", "api")));

        assertEquals(snap, graph.snapshot());
        assertEquals(1, graph.contexts().size());

        // Verify truthfulness invariant in analysis.publication-stage evidence
        var stageEvidence = graph.evidence().stream()
                .filter(e -> PreliminaryGraphBuilder.PUBLICATION_STAGE_FAMILY.equals(e.family()))
                .findFirst()
                .orElseThrow();
        var stageJson = GraphJson.object(GraphJson.read(stageEvidence.canonicalJson(), GraphJson.Limits.defaults()));
        assertEquals(PreliminaryGraphBuilder.PUBLICATION_STAGE_SCHEMA, stageJson.get("schema"));
        assertEquals("PRELIMINARY_STRUCTURE", stageJson.get("truthTier"));
        assertEquals(Boolean.FALSE, stageJson.get("authoritative"));
        assertEquals("PENDING", stageJson.get("semanticResolution"));
        assertEquals("PENDING", stageJson.get("springSat"));
        assertEquals("PENDING", stageJson.get("policyEvaluation"));
        assertEquals("PENDING", stageJson.get("scoreV2"));

        // Verify preliminary dependency edges carry SYNTAX_IMPORT_PREVIEW + UNKNOWN classification
        var depEdges = graph.edges().stream().filter(e -> "graph.depends-on".equals(e.kind())).toList();
        assertFalse(depEdges.isEmpty());
        for (var edge : depEdges) {
            var ev = graph.evidence(edge.evidence().getFirst()).orElseThrow();
            assertEquals(PreliminaryGraphBuilder.IMPORT_DEPENDENCY_FAMILY, ev.family());
            var payload = GraphJson.object(GraphJson.read(ev.canonicalJson(), GraphJson.Limits.defaults()));
            assertEquals("SYNTAX_IMPORT_PREVIEW", payload.get("derivation"));
            assertEquals("UNKNOWN", payload.get("classification"));
            assertEquals(Boolean.FALSE, payload.get("authoritative"));
        }

        // Verify ArchitectureQueryService can immediately query the Stage 1 graph!
        var queryService = new ArchitectureQueryService(graph);
        var req = new ArchitectureQuery.Request(
                graph.identity(),
                snap.value(),
                ArchitectureQuery.Scope.TYPE,
                ArchitectureQuery.Projection.BOUNDED_UNION,
                ArchitectureQuery.Direction.OUTGOING,
                Set.of("graph.depends-on"),
                ArchitectureQuery.Population.all(),
                false,
                Optional.empty(),
                ArchitectureQuery.Limits.defaults());
        var summary = queryService.summary(req);
        assertFalse(summary.items().isEmpty());
        var entities = queryService.entities(req, "", ArchitectureQuery.Page.first());
        assertTrue(entities.items().stream().anyMatch(e -> e.qualifiedName().orElse("").equals("com.shop.api.CatalogController")));
        assertTrue(entities.items().stream().anyMatch(e -> e.qualifiedName().orElse("").equals("com.shop.core.CatalogService")));
        var deps = queryService.edges(req, ArchitectureQuery.Page.first());
        assertFalse(deps.items().isEmpty());
    }

    @Test
    void stage2MergesCompletedModuleContextsWhileRemainingNonAuthoritative() {
        var repo = repo();
        var snap = snapshot(repo);
        var skeleton = sampleSkeleton();
        var modules = List.of(
                new PreliminaryGraphBuilder.ModuleInfo("core", "core", "MAVEN", 1),
                new PreliminaryGraphBuilder.ModuleInfo("api", "api", "MAVEN", 1));

        // Build a synthetic completed module semantic context for "core"
        var analysisId = new AnalysisIdentity("analysis:" + ContentDigest.sha256Utf8("core-analysis").value());
        var manifestEv = GraphEvidence.capture("analysis.manifest", Map.of("module", "core"));
        var nodeId = KnowledgeGraph.derivedIdentity("entity", "core-type");
        var completedCoreGraph = new KnowledgeGraph(
                snap,
                List.of(new KnowledgeGraph.Context(analysisId.value(), snap, Optional.of(analysisId), manifestEv.identity())),
                List.of(new KnowledgeGraph.Node(nodeId, analysisId.value(), "TYPE", List.of(manifestEv.identity()))),
                List.of(),
                List.of(manifestEv));

        var stage2 = new PreliminaryGraphBuilder().build(PreliminaryGraphBuilder.Input.stage2(
                repo, snap, modules, skeleton, List.of("core"), List.of("api"), List.of(completedCoreGraph)));

        assertEquals(2, stage2.contexts().size());
        assertTrue(stage2.contexts().stream().anyMatch(c -> c.identity().equals(analysisId.value())));
        var stageEvidence = stage2.evidence().stream()
                .filter(e -> PreliminaryGraphBuilder.PUBLICATION_STAGE_FAMILY.equals(e.family()))
                .findFirst()
                .orElseThrow();
        var stageJson = GraphJson.object(GraphJson.read(stageEvidence.canonicalJson(), GraphJson.Limits.defaults()));
        assertEquals("PARTIAL_CONTEXTS", stageJson.get("truthTier"));
        assertEquals(Boolean.FALSE, stageJson.get("authoritative"));
        assertEquals("PARTIAL", stageJson.get("semanticResolution"));
        assertEquals(1, ((Number) stageJson.get("completedContexts")).intValue());
    }

    @Test
    void rejectsAuthoritativeTruthTier() {
        var repo = repo();
        var snap = snapshot(repo);
        var skeleton = sampleSkeleton();
        assertThrows(IllegalArgumentException.class, () -> new PreliminaryGraphBuilder.Input(
                repo, snap, AnalysisJobs.TruthTier.DEEP_SEMANTIC_COMPLETE,
                List.of(), skeleton, List.of(), List.of(), Map.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new PreliminaryGraphBuilder.Input(
                repo, snap, AnalysisJobs.TruthTier.NONE,
                List.of(), skeleton, List.of(), List.of(), Map.of(), List.of()));
    }
}
