package com.evolution.analysis.assessment.v2;

import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.frontend.JavaSymbolName;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.query.ArchitectureQueryService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpringAndPolyglotScoreV2Test {

    @Test void springFieldInjectionAndControllerBypassReduceD6Score() {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/spring-v2.git");
        var snap = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("spring-snap"));
        String javaCtx = KnowledgeGraph.derivedIdentity("analysis", List.of("spring-java"));
        var mod = ModuleIdentity.from(repo, ".");

        var snapEv = GraphEvidence.capture("repository.snapshot", Map.of("snapshot", snap));
        var manEv = GraphEvidence.capture("analysis.manifest", Map.of("modules", List.of(Map.of("identity", mod, "path", "."))));
        var modEv = GraphEvidence.capture("module.descriptor", Map.of("identity", mod, "path", "."));

        var evidence = new ArrayList<GraphEvidence>(List.of(snapEv, manEv, modEv));
        var nodes = new ArrayList<KnowledgeGraph.Node>();
        var edges = new ArrayList<KnowledgeGraph.Edge>();
        nodes.add(new KnowledgeGraph.Node(mod.value(), javaCtx, "MODULE", List.of(modEv.identity())));

        String ctrlId = addAnnotatedType(javaCtx, mod, "com.app.web", "OrderController", "RestController", nodes, edges, evidence);
        String svcId = addAnnotatedType(javaCtx, mod, "com.app.service", "OrderService", "Service", nodes, edges, evidence);
        String repoId = addAnnotatedType(javaCtx, mod, "com.app.repository", "OrderRepo", "Repository", nodes, edges, evidence);
        // Clean controller -> service -> repo PLUS bad bypass controller -> repo
        edges.add(resolvedEdge(javaCtx, "graph.depends-on", ctrlId, svcId, manEv.identity(), edges.size()));
        edges.add(resolvedEdge(javaCtx, "graph.depends-on", svcId, repoId, manEv.identity(), edges.size()));
        edges.add(resolvedEdge(javaCtx, "graph.depends-on", ctrlId, repoId, manEv.identity(), edges.size()));

        // Add 3 source injection sites: 2 FIELD, 1 CONSTRUCTOR => 66.7% field injection
        var swEv = GraphEvidence.capture("spring.source-wiring", Map.of("beans", 3));
        evidence.add(swEv);
        nodes.add(new KnowledgeGraph.Node(KnowledgeGraph.derivedIdentity("sw", 1), javaCtx, "SPRING_SOURCE_WIRING", List.of(swEv.identity())));
        addSourceSite(javaCtx, ctrlId, "FIELD", "orderRepo", nodes, evidence, 1);
        addSourceSite(javaCtx, svcId, "FIELD", "helper", nodes, evidence, 2);
        addSourceSite(javaCtx, svcId, "CONSTRUCTOR", "repo", nodes, evidence, 3);

        var graph = new KnowledgeGraph(snap, List.of(
                new KnowledgeGraph.Context(snap.value(), snap, Optional.empty(), snapEv.identity()),
                new KnowledgeGraph.Context(javaCtx, snap, Optional.empty(), manEv.identity())), nodes, edges, evidence);

        var res = new ArchitectureAssessmentV2Service().assess(new ArchitectureQueryService(graph), javaCtx);
        var d6 = res.dimension(ArchitectureScoreModelV2.DIM_FRAMEWORK_POLYGLOT).orElseThrow();
        assertNotEquals(AssessmentStatus.NOT_APPLICABLE, d6.status());
        assertTrue(d6.score().isPresent());

        var fieldSub = d6.subMetrics().stream().filter(s -> s.id().equals(ArchitectureScoreModelV2.SUB_D6_SPRING_FIELD_INJ)).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("0.666667").compareTo(fieldSub.rawValue().orElseThrow()));
        assertTrue(fieldSub.score().orElseThrow().compareTo(new BigDecimal("25")) < 0);

        var bypassSub = d6.subMetrics().stream().filter(s -> s.id().equals(ArchitectureScoreModelV2.SUB_D6_SPRING_BYPASS)).findFirst().orElseThrow();
        assertEquals(0, BigDecimal.ONE.compareTo(bypassSub.rawValue().orElseThrow()));
        assertEquals(0, BigDecimal.ZERO.compareTo(bypassSub.score().orElseThrow()));
    }

    @Test void mutuallyInfeasibleSpringMayCycleIsNeverFlattenedIntoDefiniteCycle() {
        // World W1 enables edge A->B; World W2 enables edge B->A; W1 != W2 (mutually infeasible!)
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/spring-cond.git");
        var snap = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("spring-cond-snap"));
        String sCtx = KnowledgeGraph.derivedIdentity("spring-context", List.of("cond-test"));

        var snapEv = GraphEvidence.capture("repository.snapshot", Map.of("snapshot", snap));
        var ctxEv = GraphEvidence.capture("spring.context", Map.of("space", "space:cond"));
        var w1Ev = GraphEvidence.capture("spring.world", Map.of("name", "profile-a"));
        var w2Ev = GraphEvidence.capture("spring.world", Map.of("name", "profile-b"));
        String w1Id = KnowledgeGraph.derivedIdentity("world", List.of("w1"));
        String w2Id = KnowledgeGraph.derivedIdentity("world", List.of("w2"));

        String beanA = KnowledgeGraph.derivedIdentity("bean", List.of("BeanA"));
        String beanB = KnowledgeGraph.derivedIdentity("bean", List.of("BeanB"));
        var beanAEv = GraphEvidence.capture("spring.candidate", Map.of("identity", beanA, "beanName", "beanA"));
        var beanBEv = GraphEvidence.capture("spring.candidate", Map.of("identity", beanB, "beanName", "beanB"));
        var useAEv = GraphEvidence.capture("spring.injection-dependency", Map.of("required", true, "point", Map.of("siteKind", "CONSTRUCTOR_PARAMETER", "ownerDeclarationKey", "BeanA", "siteSlot", "b")));
        var useBEv = GraphEvidence.capture("spring.injection-dependency", Map.of("required", true, "point", Map.of("siteKind", "CONSTRUCTOR_PARAMETER", "ownerDeclarationKey", "BeanB", "siteSlot", "a")));
        String useA = KnowledgeGraph.derivedIdentity("injection-use", List.of("useA"));
        String useB = KnowledgeGraph.derivedIdentity("injection-use", List.of("useB"));

        // Explicit truth regions: beanA & beanB active in both worlds; edge A->B true ONLY in W1; edge B->A true ONLY in W2!
        String regAlways = KnowledgeGraph.derivedIdentity("region", List.of("always"));
        String regW1 = KnowledgeGraph.derivedIdentity("region", List.of("only-w1"));
        String regW2 = KnowledgeGraph.derivedIdentity("region", List.of("only-w2"));
        var regAlwaysEv = GraphEvidence.capture("spring.explicit-region", Map.of(
                "encoding", "explicit-worlds-m4d-v1", "space", "space:cond",
                "region", Map.of("trueWorlds", List.of(w1Id, w2Id), "falseWorlds", List.of(), "unknownWorlds", List.of())));
        var regW1Ev = GraphEvidence.capture("spring.explicit-region", Map.of(
                "encoding", "explicit-worlds-m4d-v1", "space", "space:cond",
                "region", Map.of("trueWorlds", List.of(w1Id), "falseWorlds", List.of(w2Id), "unknownWorlds", List.of())));
        var regW2Ev = GraphEvidence.capture("spring.explicit-region", Map.of(
                "encoding", "explicit-worlds-m4d-v1", "space", "space:cond",
                "region", Map.of("trueWorlds", List.of(w2Id), "falseWorlds", List.of(w1Id), "unknownWorlds", List.of())));
        String factA = KnowledgeGraph.derivedIdentity("fact", List.of("defA"));
        String factB = KnowledgeGraph.derivedIdentity("fact", List.of("defB"));
        var factAEv = GraphEvidence.capture("spring.fact-key", Map.of("kind", "DEFINITION_PRESENT", "definition", beanA));
        var factBEv = GraphEvidence.capture("spring.fact-key", Map.of("kind", "DEFINITION_PRESENT", "definition", beanB));

        var evidence = List.of(snapEv, ctxEv, w1Ev, w2Ev, beanAEv, beanBEv, useAEv, useBEv, regAlwaysEv, regW1Ev, regW2Ev, factAEv, factBEv);
        var nodes = List.of(
                new KnowledgeGraph.Node(w1Id, sCtx, "CONFIGURATION_WORLD", List.of(w1Ev.identity())),
                new KnowledgeGraph.Node(w2Id, sCtx, "CONFIGURATION_WORLD", List.of(w2Ev.identity())),
                new KnowledgeGraph.Node(beanA, sCtx, "BEAN_DEFINITION_CANDIDATE", List.of(beanAEv.identity())),
                new KnowledgeGraph.Node(beanB, sCtx, "BEAN_DEFINITION_CANDIDATE", List.of(beanBEv.identity())),
                new KnowledgeGraph.Node(useA, sCtx, "INJECTION_USE", List.of(useAEv.identity())),
                new KnowledgeGraph.Node(useB, sCtx, "INJECTION_USE", List.of(useBEv.identity())),
                new KnowledgeGraph.Node(regAlways, sCtx, "TRUTH_REGION", List.of(regAlwaysEv.identity())),
                new KnowledgeGraph.Node(regW1, sCtx, "TRUTH_REGION", List.of(regW1Ev.identity())),
                new KnowledgeGraph.Node(regW2, sCtx, "TRUTH_REGION", List.of(regW2Ev.identity())),
                new KnowledgeGraph.Node(factA, sCtx, "CONDITIONAL_FACT", List.of(factAEv.identity())),
                new KnowledgeGraph.Node(factB, sCtx, "CONDITIONAL_FACT", List.of(factBEv.identity())));
        var edges = List.of(
                resolvedEdge(sCtx, "spring.declares-injection", beanA, useA, useAEv.identity(), 1),
                resolvedEdge(sCtx, "spring.declares-injection", beanB, useB, useBEv.identity(), 2),
                resolvedEdge(sCtx, "spring.definition-fact", factA, beanA, factAEv.identity(), 3),
                resolvedEdge(sCtx, "spring.definition-fact", factB, beanB, factBEv.identity(), 4),
                resolvedEdge(sCtx, "spring.has-truth-region", factA, regAlways, regAlwaysEv.identity(), 5),
                resolvedEdge(sCtx, "spring.has-truth-region", factB, regAlways, regAlwaysEv.identity(), 6),
                new KnowledgeGraph.Edge(KnowledgeGraph.derivedIdentity("bind", 1), sCtx, "spring.selected-binding", useA,
                        new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(beanB), Optional.empty()),
                        List.of(regW1Ev.identity()), Optional.of(regW1)),
                new KnowledgeGraph.Edge(KnowledgeGraph.derivedIdentity("bind", 2), sCtx, "spring.selected-binding", useB,
                        new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(beanA), Optional.empty()),
                        List.of(regW2Ev.identity()), Optional.of(regW2)));

        var graph = new KnowledgeGraph(snap, List.of(
                new KnowledgeGraph.Context(snap.value(), snap, Optional.empty(), snapEv.identity()),
                new KnowledgeGraph.Context(sCtx, snap, Optional.empty(), ctxEv.identity())), nodes, edges, evidence);

        var springMetrics = SpringArchitectureAnalyzer.analyze(new ArchitectureQueryService(graph),
                Optional.of(sCtx), Optional.empty(), 10, () -> {});
        assertTrue(springMetrics.applicable());
        assertEquals(0, springMetrics.mustCyclicBeanCount());
        assertEquals(0, springMetrics.feasibleMayOnlyCyclicBeanCount(),
                "Mutually exclusive W1 (A->B) and W2 (B->A) edges MUST NOT form a feasible bean cycle!");
        assertEquals(0, BigDecimal.ZERO.compareTo(springMetrics.beanRelativeCyclicity().orElseThrow()));
        assertEquals(0, BigDecimal.ONE.compareTo(springMetrics.spaceDefiniteness().orElseThrow()));
    }

    @Test void polyglotContextAndWholeRepositoryCrossServiceScoringWorkEndToEnd() {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/polyglot-mesh.git");
        var snap = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("poly-snap"));
        String root = snap.value();
        String tsCtx = KnowledgeGraph.derivedIdentity("polyglot-context", List.of("frontend-ts"));
        String pyCtx = KnowledgeGraph.derivedIdentity("polyglot-context", List.of("billing-py"));

        var snapEv = GraphEvidence.capture("repository.snapshot", Map.of("snapshot", snap));
        var tsCtxEv = GraphEvidence.capture("polyglot.context", Map.of("request", Map.of("module", "frontend", "language", "typescript")));
        var pyCtxEv = GraphEvidence.capture("polyglot.context", Map.of("request", Map.of("module", "billing", "language", "python")));

        var evidence = new ArrayList<GraphEvidence>(List.of(snapEv, tsCtxEv, pyCtxEv));
        var nodes = new ArrayList<KnowledgeGraph.Node>();
        var edges = new ArrayList<KnowledgeGraph.Edge>();

        // Clean acyclic TS files: web/App.ts -> service/Api.ts -> domain/Order.ts
        addPolyglotFile(tsCtx, "web/App.ts", "./../service/Api", nodes, evidence, 1);
        addPolyglotFile(tsCtx, "service/Api.ts", "./../domain/Order", nodes, evidence, 2);
        addPolyglotFile(tsCtx, "domain/Order.ts", null, nodes, evidence, 3);

        // Clean acyclic Python files
        addPolyglotFile(pyCtx, "api/routes.py", "service.billing", nodes, evidence, 4);
        addPolyglotFile(pyCtx, "service/billing.py", "model.invoice", nodes, evidence, 5);
        addPolyglotFile(pyCtx, "model/invoice.py", null, nodes, evidence, 6);

        // Cross-service synchronous cycle frontend <-> billing PLUS external unmatched URL (must NOT penalize!)
        String tsRef = KnowledgeGraph.derivedIdentity("svc-ref", List.of("ts"));
        String pyRef = KnowledgeGraph.derivedIdentity("svc-ref", List.of("py"));
        var tsRefEv = GraphEvidence.capture("architecture.service-endpoint-reference", Map.of("sourceContext", tsCtx));
        var pyRefEv = GraphEvidence.capture("architecture.service-endpoint-reference", Map.of("sourceContext", pyCtx));
        var extCallEv = GraphEvidence.capture("polyglot.http-uri-match", Map.of(
                "path", "https://api.stripe.com/v1/charges", "candidates", List.of(), "outcome", "HTTP_URI_NO_CANDIDATE"));
        evidence.addAll(List.of(tsRefEv, pyRefEv, extCallEv));
        nodes.add(new KnowledgeGraph.Node(tsRef, root, "SERVICE_ENDPOINT_REFERENCE", List.of(tsRefEv.identity())));
        nodes.add(new KnowledgeGraph.Node(pyRef, root, "SERVICE_ENDPOINT_REFERENCE", List.of(pyRefEv.identity())));
        edges.add(resolvedEdge(root, "CALLS_SERVICE", tsRef, pyRef, tsRefEv.identity(), 10));
        edges.add(resolvedEdge(root, "CALLS_SERVICE", pyRef, tsRef, pyRefEv.identity(), 11));

        var graph = new KnowledgeGraph(snap, List.of(
                new KnowledgeGraph.Context(root, snap, Optional.empty(), snapEv.identity()),
                new KnowledgeGraph.Context(tsCtx, snap, Optional.empty(), tsCtxEv.identity()),
                new KnowledgeGraph.Context(pyCtx, snap, Optional.empty(), pyCtxEv.identity())), nodes, edges, evidence);

        var queries = new ArchitectureQueryService(graph);
        var svc = new ArchitectureAssessmentV2Service();

        // Single polyglot context: capped at MODERATE confidence (SOURCE_SYNTAX)
        var tsRes = svc.assess(queries, tsCtx);
        assertEquals("POLYGLOT_CONTEXT", tsRes.scopeMode());
        assertEquals(ArchitectureAssessmentV2.ConfidenceTier.MODERATE, tsRes.confidence().tier());
        assertEquals("SOURCE_SYNTAX", tsRes.confidence().evidenceTier());
        assertTrue(tsRes.score().orElseThrow().compareTo(new BigDecimal("85")) >= 0);

        // Whole repository composite: includes both contexts in contextBreakdown + catches cross-service sync cycle!
        var repoRes = svc.assess(queries, root);
        assertEquals("WHOLE_REPOSITORY", repoRes.scopeMode());
        assertEquals(2, repoRes.contextBreakdown().size());
        var d6 = repoRes.dimension(ArchitectureScoreModelV2.DIM_FRAMEWORK_POLYGLOT).orElseThrow();
        assertNotEquals(AssessmentStatus.NOT_APPLICABLE, d6.status());
        var cycSub = d6.subMetrics().stream().filter(s -> s.id().equals(ArchitectureScoreModelV2.SUB_D6_POLYGLOT_CYCLES)).findFirst().orElseThrow();
        assertTrue(cycSub.rawValue().orElseThrow().signum() > 0, "Cross-service CALLS_SERVICE cycle must be detected");
        assertTrue(cycSub.score().orElseThrow().compareTo(new BigDecimal("50")) < 0);
        // External unmatched Stripe URL should NOT trigger contract risk
        var contractSub = d6.subMetrics().stream().filter(s -> s.id().equals(ArchitectureScoreModelV2.SUB_D6_POLYGLOT_CONTRACTS)).findFirst().orElseThrow();
        assertEquals(AssessmentStatus.NOT_APPLICABLE, contractSub.status(),
                "External HTTP_URI_NO_CANDIDATE must never penalize internal contract hygiene");
    }

    private static String addAnnotatedType(String ctx, ModuleIdentity mod, String pkg, String name, String stereotype,
                                           List<KnowledgeGraph.Node> nodes, List<KnowledgeGraph.Edge> edges, List<GraphEvidence> evidence) {
        var entity = Entity.create(EntityOrigin.PROJECT, EntityScope.project(mod), EntityKind.TYPE,
                JavaSymbolName.topLevelType(pkg, name).canonicalName(), Optional.empty());
        var declEv = GraphEvidence.capture("java.declaration", Map.of("entity", entity, "spelling", name, "status", "RESOLVED"));
        var shapeEv = GraphEvidence.capture("java.type-shape", Map.of("type", entity.identity().value(), "kind", "CLASS", "abstractType", false));
        var annEv = GraphEvidence.capture("java.annotation-use", Map.of("annotationName", "org.springframework.stereotype." + stereotype));
        evidence.addAll(List.of(declEv, shapeEv, annEv));

        String pkgId = EntityIdentity.from(EntityOrigin.PROJECT, EntityScope.project(mod), EntityKind.PACKAGE,
                JavaSymbolName.packageName(pkg).canonicalName()).value();
        if (nodes.stream().noneMatch(n -> n.identity().equals(pkgId))) {
            nodes.add(new KnowledgeGraph.Node(pkgId, ctx, "PACKAGE", List.of(declEv.identity())));
            edges.add(resolvedEdge(ctx, "graph.contains", mod.value(), pkgId, declEv.identity(), edges.size()));
        }
        nodes.add(new KnowledgeGraph.Node(entity.identity().value(), ctx, "TYPE", List.of(declEv.identity(), shapeEv.identity())));
        edges.add(resolvedEdge(ctx, "graph.contains", pkgId, entity.identity().value(), declEv.identity(), edges.size()));

        String annId = KnowledgeGraph.derivedIdentity("ann", List.of(entity.identity().value(), stereotype));
        nodes.add(new KnowledgeGraph.Node(annId, ctx, "ANNOTATION_USE", List.of(annEv.identity())));
        edges.add(resolvedEdge(ctx, "graph.has-annotation-use", entity.identity().value(), annId, annEv.identity(), edges.size()));
        return entity.identity().value();
    }

    private static void addSourceSite(String ctx, String ownerId, String kind, String name,
                                      List<KnowledgeGraph.Node> nodes, List<GraphEvidence> evidence, int seq) {
        var ev = GraphEvidence.capture("spring.source-injection-site", Map.of("owner", ownerId, "kind", kind, "name", name));
        evidence.add(ev);
        nodes.add(new KnowledgeGraph.Node(KnowledgeGraph.derivedIdentity("site", List.of(seq)), ctx, "SOURCE_INJECTION_SITE", List.of(ev.identity())));
    }

    private static void addPolyglotFile(String ctx, String path, String importSpec,
                                        List<KnowledgeGraph.Node> nodes, List<GraphEvidence> evidence, int seq) {
        String encodedPath = Base64.getEncoder().encodeToString(path.getBytes(StandardCharsets.UTF_8));
        var declEv = GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "path", encodedPath, "fact", Map.of("category", "FUNCTION", "name", "fn" + seq))));
        evidence.add(declEv);
        nodes.add(new KnowledgeGraph.Node(KnowledgeGraph.derivedIdentity("pdecl", List.of(seq)), ctx, "SYNTAX_FACT", List.of(declEv.identity())));
        if (importSpec != null) {
            var impEv = GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                    "path", encodedPath, "fact", Map.of("category", "IMPORT", "name", "\"" + importSpec + "\""))));
            evidence.add(impEv);
            nodes.add(new KnowledgeGraph.Node(KnowledgeGraph.derivedIdentity("pimp", List.of(seq)), ctx, "SYNTAX_FACT", List.of(impEv.identity())));
        }
    }

    private static KnowledgeGraph.Edge resolvedEdge(String ctx, String kind, String src, String dst, ContentDigest ev, int seq) {
        return new KnowledgeGraph.Edge(
                KnowledgeGraph.derivedIdentity("e", List.of(kind, src, dst, seq)),
                ctx, kind, src,
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(dst), Optional.empty()),
                List.of(ev), Optional.empty());
    }
}
