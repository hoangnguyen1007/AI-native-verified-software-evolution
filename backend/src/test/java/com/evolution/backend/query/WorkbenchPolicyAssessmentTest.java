package com.evolution.backend.query;

import com.evolution.analysis.assessment.v2.InferredLayerClassifier;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.evidence.EvidenceContext;
import com.evolution.analysis.frontend.JavaSymbolName;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.policy.PolicyCompiler;
import com.evolution.analysis.query.ArchitectureQuery.Scope;
import com.evolution.analysis.query.ArchitectureQueryService;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkbenchPolicyAssessmentTest {

    @Test void suggestsCompilableHexagonalPolicyViaSharedInferredLayerClassifier() {
        var graph = graphWithPackages(
                "com.acme.domain.order",
                "com.acme.port.in",
                "com.acme.adapter.persistence",
                "com.acme.adapter.http");
        var ctx = analysisContext(graph);
        var suggested = WorkbenchPolicyAssessment.suggestPolicy(
                new ArchitectureQueryService(graph), ctx, Scope.TYPE, () -> {});

        assertTrue(suggested.summary().contains("Hexagonal Architecture"), suggested::summary);
        assertTrue(suggested.yaml().contains("auto.ports-no-adapter-leakage"), suggested::yaml);
        assertTrue(suggested.yaml().contains("auto.domain-no-infrastructure-leakage"), suggested::yaml);
        assertTrue(suggested.yaml().contains("com.acme.adapter.persistence.**"), suggested::yaml);
        assertCompiles(suggested.yaml(), ctx.snapshot());
    }

    @Test void suggestsCompilableCleanArchitecturePolicyUsingExtendedLayerVocabulary() {
        // Exercises segments newly unified via InferredLayerClassifier: ui -> WEB, workflow -> APPLICATION, vo -> DOMAIN, gateway -> ADAPTER
        assertEquals(Optional.of(InferredLayerClassifier.LAYER_WEB), InferredLayerClassifier.classifyPackageName("com.acme.ui"));
        assertEquals(Optional.of(InferredLayerClassifier.LAYER_APPLICATION), InferredLayerClassifier.classifyPackageName("com.acme.workflow"));
        assertEquals(Optional.of(InferredLayerClassifier.LAYER_DOMAIN), InferredLayerClassifier.classifyPackageName("com.acme.vo"));
        assertEquals(Optional.of(InferredLayerClassifier.LAYER_ADAPTER), InferredLayerClassifier.classifyPackageName("com.acme.gateway"));

        var graph = graphWithPackages(
                "com.acme.ui",
                "com.acme.workflow",
                "com.acme.vo",
                "com.acme.gateway");
        var ctx = analysisContext(graph);
        var suggested = WorkbenchPolicyAssessment.suggestPolicy(
                new ArchitectureQueryService(graph), ctx, Scope.TYPE, () -> {});

        assertTrue(suggested.summary().contains("Clean Architecture"), suggested::summary);
        assertTrue(suggested.yaml().contains("auto.domain-no-web-leakage"), suggested::yaml);
        assertTrue(suggested.yaml().contains("auto.domain-no-infrastructure-leakage"), suggested::yaml);
        assertTrue(suggested.yaml().contains("auto.domain-no-application-leakage"), suggested::yaml);
        assertTrue(suggested.yaml().contains("auto.application-no-web-leakage"), suggested::yaml);
        assertCompiles(suggested.yaml(), ctx.snapshot());
    }

    @Test void suggestsCompilableLayeredPolicyForClassicWebServiceRepositoryLayout() {
        var graph = graphWithPackages(
                "com.acme.controller",
                "com.acme.service",
                "com.acme.repository");
        var ctx = analysisContext(graph);
        var suggested = WorkbenchPolicyAssessment.suggestPolicy(
                new ArchitectureQueryService(graph), ctx, Scope.TYPE, () -> {});

        assertTrue(suggested.summary().contains("Layered Architecture"), suggested::summary);
        assertTrue(suggested.yaml().contains("auto.web-no-direct-persistence"), suggested::yaml);
        assertTrue(suggested.yaml().contains("auto.application-no-web-leakage"), suggested::yaml);
        assertTrue(suggested.yaml().contains("auto.no-package-cycles"), suggested::yaml);
        assertCompiles(suggested.yaml(), ctx.snapshot());
    }

    private static KnowledgeGraph.Context analysisContext(KnowledgeGraph graph) {
        return graph.contexts().stream().filter(c -> c.analysis().isPresent()).findFirst().orElseThrow();
    }

    private static void assertCompiles(String yaml, SnapshotIdentity snapshot) {
        var compiled = new PolicyCompiler().compile(
                yaml.getBytes(StandardCharsets.UTF_8),
                EvidenceContext.forSnapshot(snapshot),
                PolicyCompiler.Limits.defaults());
        assertTrue(compiled.policy().isPresent(), () -> "Expected compilable YAML, problems=" + compiled.problems());
        assertTrue(compiled.problems().isEmpty());
    }

    private static KnowledgeGraph graphWithPackages(String... packages) {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/policy-suggest.git");
        var snapshot = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8(String.join(",", packages)));
        var analysisId = AnalysisIdentity.fromCanonicalManifestInputs("policy-suggest-mod");
        String analysisCtx = analysisId.value();
        var module = ModuleIdentity.from(repo, ".");
        var snapEv = GraphEvidence.capture("repository.snapshot", Map.of("snapshot", snapshot));
        var manifestEv = GraphEvidence.capture("analysis.manifest", Map.of("modules", List.of(Map.of("identity", module, "path", "."))));
        var modEv = GraphEvidence.capture("module.descriptor", Map.of("identity", module, "path", "."));

        var evidence = new ArrayList<GraphEvidence>(List.of(snapEv, manifestEv, modEv));
        var nodes = new ArrayList<KnowledgeGraph.Node>();
        var edges = new ArrayList<KnowledgeGraph.Edge>();
        nodes.add(new KnowledgeGraph.Node(module.value(), analysisCtx, "MODULE", List.of(modEv.identity())));

        int typeSeq = 0;
        for (String pkg : packages) {
            String typeName = "SampleType" + (typeSeq++);
            var typeEntity = Entity.create(EntityOrigin.PROJECT, EntityScope.project(module), EntityKind.TYPE,
                    JavaSymbolName.topLevelType(pkg, typeName).canonicalName(), Optional.empty());
            var typeDecl = GraphEvidence.capture("java.declaration",
                    Map.of("entity", typeEntity, "spelling", typeName, "status", "RESOLVED"));
            evidence.add(typeDecl);

            String pkgId = EntityIdentity.from(EntityOrigin.PROJECT, EntityScope.project(module), EntityKind.PACKAGE,
                    JavaSymbolName.packageName(pkg).canonicalName()).value();
            nodes.add(new KnowledgeGraph.Node(pkgId, analysisCtx, "PACKAGE", List.of(typeDecl.identity())));
            edges.add(new KnowledgeGraph.Edge(
                    KnowledgeGraph.derivedIdentity("contains", List.of(module.value(), pkgId)),
                    analysisCtx, "graph.contains", module.value(),
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(pkgId), Optional.empty()),
                    List.of(typeDecl.identity()), Optional.empty()));

            nodes.add(new KnowledgeGraph.Node(typeEntity.identity().value(), analysisCtx, "TYPE", List.of(typeDecl.identity())));
            edges.add(new KnowledgeGraph.Edge(
                    KnowledgeGraph.derivedIdentity("contains", List.of(pkgId, typeEntity.identity().value())),
                    analysisCtx, "graph.contains", pkgId,
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(typeEntity.identity().value()), Optional.empty()),
                    List.of(typeDecl.identity()), Optional.empty()));
        }

        return new KnowledgeGraph(
                snapshot,
                List.of(
                        new KnowledgeGraph.Context(snapshot.value(), snapshot, Optional.empty(), snapEv.identity()),
                        new KnowledgeGraph.Context(analysisCtx, snapshot, Optional.of(analysisId), manifestEv.identity())),
                nodes, edges, evidence);
    }
}
