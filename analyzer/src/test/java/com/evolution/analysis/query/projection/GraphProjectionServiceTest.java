package com.evolution.analysis.query.projection;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.source.SourceSpan;
import com.evolution.analysis.evidence.EvidenceContext;
import com.evolution.analysis.frontend.JavaSymbolName;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.policy.*;
import com.evolution.analysis.query.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.query.ArchitectureQuery.*;

class GraphProjectionServiceTest {
    @Test void focusedDepthIsAnActualLimitAndEdgesRetainEvidenceAndDegradedStatus() {
        var f=new Fixture().type("app","p","A").type("app","p","B").type("app","p","C").type("app","p","D")
                .edge("A","B").edge("B","C").edge("C","D");
        var service=new GraphProjectionService(new ArchitectureQueryService(f.graph()));
        var view=service.focusedSubgraph(f.request(),f.types.get("A"),2,Optional.empty());
        assertTrue(view.nodes().stream().anyMatch(n->n.id().equals(f.types.get("C"))));
        assertFalse(view.nodes().stream().anyMatch(n->n.id().equals(f.types.get("D"))));
        assertFalse(((List<?>)view.edges().getFirst().properties().get("evidence")).isEmpty());
        assertNotNull(view.metadata().get("status"));assertNotNull(view.metadata().get("focusBounds"));
        assertEquals("m8.3-v1",GraphProjectionService.VERSION.version());
        assertThrows(IllegalArgumentException.class,()->service.focusedSubgraph(f.request(),f.types.get("A"),0,Optional.empty()));
    }

    static final class Fixture {
        final RepositoryIdentity repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/repo");
        final SnapshotIdentity snapshot = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("snapshot"));
        final String context = KnowledgeGraph.derivedIdentity("context", "test");
        final GraphEvidence base = GraphEvidence.capture("fixture.context", Map.of("identity", context));
        final List<KnowledgeGraph.Node> nodes = new ArrayList<>();
        final List<KnowledgeGraph.Edge> edges = new ArrayList<>();
        final List<GraphEvidence> evidence = new ArrayList<>(List.of(base));
        final Map<String, String> types = new HashMap<>();

        Fixture() {
            var doc = SourceDocumentIdentity.from(repo, "src/Main.java");
            var digest = ContentDigest.sha256Utf8("class Main {}");
            var inventory = GraphEvidence.capture("source.document", Map.of("identity", doc, "contentDigest", digest));
            evidence.add(inventory);
            nodes.add(new KnowledgeGraph.Node(doc.value(), context, "SOURCE_DOCUMENT", List.of(inventory.identity())));
        }

        Fixture type(String module, String pkg, String name) {
            String qname = pkg.isEmpty() ? name : pkg + "." + name;
            var owner = ModuleIdentity.from(repo, module);
            var entity = Entity.create(EntityOrigin.PROJECT, EntityScope.project(owner), EntityKind.TYPE, JavaSymbolName.topLevelType(pkg, name).canonicalName(), Optional.empty());
            var proof = GraphEvidence.capture("java.declaration", Map.of("entity", entity, "spelling", name));
            evidence.add(proof);

            if (nodes.stream().noneMatch(n -> n.identity().equals(owner.value()))) {
                var moduleProof = GraphEvidence.capture("module.descriptor", Map.of("identity", owner));
                evidence.add(moduleProof);
                nodes.add(new KnowledgeGraph.Node(owner.value(), context, "MODULE", List.of(moduleProof.identity())));
            }
            String packageId = EntityIdentity.from(EntityOrigin.PROJECT, EntityScope.project(owner), EntityKind.PACKAGE, JavaSymbolName.packageName(pkg).canonicalName()).value();
            if (nodes.stream().noneMatch(n -> n.identity().equals(packageId))) {
                nodes.add(new KnowledgeGraph.Node(packageId, context, "PACKAGE", List.of(proof.identity())));
                link(owner.value(), packageId);
            }
            link(packageId, entity.identity().value());
            nodes.add(new KnowledgeGraph.Node(entity.identity().value(), context, "TYPE", List.of(proof.identity())));
            types.put(name, entity.identity().value());
            return this;
        }

        void link(String source, String target) {
            edges.add(new KnowledgeGraph.Edge(
                KnowledgeGraph.derivedIdentity("contains", source + "->" + target + ":" + edges.size()),
                context,
                "graph.contains",
                source,
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(target), Optional.empty()),
                List.of(base.identity()),
                Optional.empty()
            ));
        }

        Fixture edge(String fromName, String toName) {
            String from = types.get(fromName);
            String to = types.get(toName);
            String edgeId = KnowledgeGraph.derivedIdentity("edge", from + "->" + to + ":" + edges.size());

            var doc = SourceDocumentIdentity.from(repo, "src/Main.java");
            var occ = GraphEvidence.capture("java.occurrence", Map.of(
                "status", "RESOLVED",
                "derivation", "DIRECT",
                "span", new SourceSpan(doc, 1, 1, 1, 10)
            ));
            evidence.add(occ);

            edges.add(new KnowledgeGraph.Edge(
                edgeId,
                context,
                "graph.depends-on",
                from,
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(to), Optional.empty()),
                List.of(occ.identity()),
                Optional.empty()
            ));
            return this;
        }

        KnowledgeGraph graph() {
            var ctx = new KnowledgeGraph.Context(context, snapshot, Optional.empty(), base.identity());
            return new KnowledgeGraph(snapshot, List.of(ctx), nodes, edges, evidence);
        }

        Request request() {
            return new Request(
                graph().identity(),
                context,
                Scope.TYPE,
                Projection.BOUNDED_UNION,
                Direction.OUTGOING,
                Set.of("graph.depends-on"),
                Population.all(),
                false,
                Optional.empty(),
                Limits.defaults()
            );
        }
    }

    static PolicyEvaluation.Result evaluate(Fixture f, String yaml) {
        var compiled = new PolicyCompiler().compile(
            yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            EvidenceContext.forSnapshot(f.snapshot),
            PolicyCompiler.Limits.defaults()
        );
        var service = new ArchitectureQueryService(f.graph());
        var mapper = new PolicyGraphMapper();
        var plan = mapper.bind(compiled, service, f.context, PolicyGraphMapper.Limits.defaults());
        return new PolicyEvaluator().evaluate(compiled, plan, service, PolicyEvaluation.Limits.defaults());
    }

    static final String FORBIDDEN_POLICY = """
            schema: architecture-policy:v1
            id: test-policy
            version: "1"
            rules:
              - id: web.persistence
                version: "1"
                kind: FORBIDDEN_DEPENDENCY
                severity: ERROR
                scope: TYPE
                relationships: [graph.depends-on]
                from: {packages: [com.example.service.**]}
                to: {packages: [com.example.repository.**]}
            """;

    @Test
    void compoundHierarchyIsGeneratedForTypesAndPackages() {
        var f = new Fixture()
            .type("mod-a", "com.example.service", "OrderService")
            .type("mod-a", "com.example.repository", "OrderRepository")
            .edge("OrderService", "OrderRepository");

        var queries = new ArchitectureQueryService(f.graph());
        var projectionService = new GraphProjectionService(queries);
        var visualGraph = projectionService.project(f.request(), Optional.empty());

        assertNotNull(visualGraph.id());
        assertFalse(visualGraph.nodes().isEmpty());
        assertFalse(visualGraph.edges().isEmpty());

        // Check compound nodes
        var compoundNodes = visualGraph.nodes().stream()
            .filter(n -> n.id().startsWith("compound:"))
            .toList();
        assertFalse(compoundNodes.isEmpty());

        var packageCompounds = compoundNodes.stream()
            .filter(n -> n.kind().equals("PACKAGE"))
            .toList();
        assertEquals(2, packageCompounds.size()); // com.example.service and com.example.repository

        // Type nodes must have compound parents
        var typeNodes = visualGraph.nodes().stream()
            .filter(n -> !n.id().startsWith("compound:"))
            .toList();
        assertEquals(2, typeNodes.size());
        for (var t : typeNodes) {
            assertTrue(t.parent().isPresent());
            assertTrue(t.parent().orElseThrow().startsWith("compound:package:"));
        }

        // Cytoscape export
        var cy = visualGraph.toCytoscape();
        assertEquals("cytoscape.js", cy.get("format"));
        var elements = (Map<?, ?>) cy.get("elements");
        var nodes = (List<?>) elements.get("nodes");
        var edges = (List<?>) elements.get("edges");
        assertEquals(visualGraph.nodes().size(), nodes.size());
        assertEquals(visualGraph.edges().size(), edges.size());

        // GraphML export
        var xml = visualGraph.toGraphML();
        assertTrue(xml.startsWith("<?xml"));
        assertTrue(xml.contains("<graphml"));
        assertTrue(xml.contains("<node id="));
        assertTrue(xml.contains("<edge id="));
    }

    @Test
    void cyclesAreMarkedOnVisualNodesAndEdges() {
        var f = new Fixture()
            .type("mod-a", "com.example.cycle", "A")
            .type("mod-a", "com.example.cycle", "B")
            .edge("A", "B")
            .edge("B", "A");

        var queries = new ArchitectureQueryService(f.graph());
        var projectionService = new GraphProjectionService(queries);
        var visualGraph = projectionService.project(f.request(), Optional.empty());

        var cyclicNodes = visualGraph.nodes().stream()
            .filter(VisualGraph.VisualNode::isCycleParticipant)
            .toList();
        assertEquals(2, cyclicNodes.size());

        var cyclicEdges = visualGraph.edges().stream()
            .filter(VisualGraph.VisualEdge::isCycle)
            .toList();
        assertEquals(2, cyclicEdges.size());
    }

    @Test
    void policyViolationsAreDecoratedOnVisualNodesAndEdges() {
        var f = new Fixture()
            .type("mod-a", "com.example.service", "OrderService")
            .type("mod-a", "com.example.repository", "OrderRepository")
            .edge("OrderService", "OrderRepository");

        var evaluation = evaluate(f, FORBIDDEN_POLICY);
        assertEquals(1, evaluation.findings().size());

        var queries = new ArchitectureQueryService(f.graph());
        var projectionService = new GraphProjectionService(queries);
        var visualGraph = projectionService.project(f.request(), Optional.of(evaluation));

        var violatingNodes = visualGraph.nodes().stream()
            .filter(n -> n.violationCount() > 0)
            .toList();
        assertEquals(2, violatingNodes.size()); // OrderService and OrderRepository
        for (var vn : violatingNodes) {
            assertEquals(Optional.of("ERROR"), vn.worstSeverity());
        }

        var violatingEdges = visualGraph.edges().stream()
            .filter(VisualGraph.VisualEdge::isViolation)
            .toList();
        assertEquals(1, violatingEdges.size());
    }

    @Test
    void focusedSubgraphExtractsEgoNetwork() {
        var f = new Fixture()
            .type("mod-a", "com.example.pkg", "A")
            .type("mod-a", "com.example.pkg", "B")
            .type("mod-a", "com.example.pkg", "C")
            .type("mod-a", "com.example.pkg", "D")
            .edge("A", "B")
            .edge("B", "C")
            .edge("C", "D");

        var queries = new ArchitectureQueryService(f.graph());
        var projectionService = new GraphProjectionService(queries);

        String bId = f.types.get("B");
        var focused = projectionService.focusedSubgraph(f.request(), bId, 1, Optional.empty());

        var entityNodeIds = focused.nodes().stream()
            .filter(n -> !n.id().startsWith("compound:"))
            .map(VisualGraph.VisualNode::id)
            .toList();

        // Focused around B: B and its outgoing neighbor C are included
        assertTrue(entityNodeIds.contains(bId));
        assertTrue(entityNodeIds.contains(f.types.get("C")));
        assertFalse(entityNodeIds.contains(f.types.get("D")));
    }

    @Test
    void violationsSubgraphExtractsOnlyViolatingElements() {
        var f = new Fixture()
            .type("mod-a", "com.example.clean", "Clean")
            .type("mod-a", "com.example.service", "OrderService")
            .type("mod-a", "com.example.repository", "OrderRepository")
            .edge("OrderService", "OrderRepository");

        var evaluation = evaluate(f, FORBIDDEN_POLICY);
        var queries = new ArchitectureQueryService(f.graph());
        var projectionService = new GraphProjectionService(queries);

        var violationsGraph = projectionService.violationsSubgraph(f.request(), evaluation);

        var nonCompoundIds = violationsGraph.nodes().stream()
            .filter(n -> !n.id().startsWith("compound:"))
            .map(VisualGraph.VisualNode::id)
            .toList();

        assertEquals(2, nonCompoundIds.size());
        assertTrue(nonCompoundIds.contains(f.types.get("OrderService")));
        assertTrue(nonCompoundIds.contains(f.types.get("OrderRepository")));
        assertFalse(nonCompoundIds.contains(f.types.get("Clean")));
    }
}
