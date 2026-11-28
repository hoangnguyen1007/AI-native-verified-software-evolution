package com.evolution.analysis.query;

import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.AnalysisIdentity;
import com.evolution.analysis.contract.identity.EntityScope;
import com.evolution.analysis.contract.identity.ModuleIdentity;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.semantic.Entity;
import com.evolution.analysis.contract.semantic.EntityKind;
import com.evolution.analysis.contract.semantic.EntityOrigin;
import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.ModuleDescriptor;
import com.evolution.analysis.contract.source.SourceClassification;
import com.evolution.analysis.contract.source.SourceDocument;
import com.evolution.analysis.contract.source.SourceSpan;
import com.evolution.analysis.frontend.JavaSymbolName;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.GraphSnapshotCodec;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.query.ArchitectureQuery.StopReason;
import com.evolution.analysis.query.GraphComposeQuery.*;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit and contract verification for WP3.2:
 * Typed Compositional Graph Queries ({@link GraphComposeQuery}), bounded execution in
 * {@link ArchitectureQueryService#composeQuery}, pointer-indexed results ({@code path:startLine-endLine@sha256Prefix}),
 * and live schema views ({@link ArchitectureQueryService#schemaView}).
 */
class GraphComposeQueryTest {

    private static final Pattern POINTER_REF_PATTERN = Pattern.compile("^[^:@\\s]+:\\d+-\\d+@[0-9a-f]{12}$");

    @Test
    void strictSchemaValidatorRejectsInvalidAstWithDeterministicTypedErrorCodes() {
        // 1. Malformed JSON & empty matchNodes
        assertValidationError(() -> GraphComposeQuery.fromJson("{broken"), ErrorCode.INVALID_QUERY_OBJECT);
        assertValidationError(() -> GraphComposeQuery.fromMap(Map.of("matchNodes", List.of())), ErrorCode.EMPTY_MATCH_NODES);

        // 2. Invalid & duplicate aliases
        assertValidationError(() -> GraphComposeQuery.fromMap(Map.of(
                "matchNodes", List.of(Map.of("alias", "123bad")))), ErrorCode.INVALID_ALIAS);
        assertValidationError(() -> GraphComposeQuery.fromMap(Map.of(
                "matchNodes", List.of(Map.of("alias", "bad-dash")))), ErrorCode.INVALID_ALIAS);
        assertValidationError(() -> GraphComposeQuery.fromMap(Map.of(
                "matchNodes", List.of(Map.of("alias", "a"), Map.of("alias", "a")))), ErrorCode.DUPLICATE_ALIAS);

        // 3. Unknown alias references in matchEdges & filters
        assertValidationError(() -> GraphComposeQuery.fromMap(Map.of(
                "matchNodes", List.of(Map.of("alias", "a")),
                "matchEdges", List.of(Map.of("from", "a", "to", "missing", "minHops", 1, "maxHops", 2)))),
                ErrorCode.UNKNOWN_ALIAS_REFERENCE);
        assertValidationError(() -> GraphComposeQuery.fromMap(Map.of(
                "matchNodes", List.of(Map.of("alias", "a")),
                "filters", List.of(Map.of("alias", "ghost", "field", "fqn", "op", "CONTAINS", "value", "Order")))),
                ErrorCode.UNKNOWN_ALIAS_REFERENCE);

        // 4. Self-loop & hop bounds (minHops < 1, maxHops > 4, minHops > maxHops)
        assertValidationError(() -> GraphComposeQuery.fromMap(Map.of(
                "matchNodes", List.of(Map.of("alias", "a")),
                "matchEdges", List.of(Map.of("from", "a", "to", "a", "minHops", 1, "maxHops", 2)))),
                ErrorCode.SELF_LOOP_EDGE_PATTERN);
        assertValidationError(() -> GraphComposeQuery.fromMap(Map.of(
                "matchNodes", List.of(Map.of("alias", "a"), Map.of("alias", "b")),
                "matchEdges", List.of(Map.of("from", "a", "to", "b", "minHops", 0, "maxHops", 2)))),
                ErrorCode.INVALID_HOP_BOUNDS);
        assertValidationError(() -> GraphComposeQuery.fromMap(Map.of(
                "matchNodes", List.of(Map.of("alias", "a"), Map.of("alias", "b")),
                "matchEdges", List.of(Map.of("from", "a", "to", "b", "minHops", 1, "maxHops", 5)))),
                ErrorCode.INVALID_HOP_BOUNDS);
        assertValidationError(() -> GraphComposeQuery.fromMap(Map.of(
                "matchNodes", List.of(Map.of("alias", "a"), Map.of("alias", "b")),
                "matchEdges", List.of(Map.of("from", "a", "to", "b", "minHops", 3, "maxHops", 2)))),
                ErrorCode.INVALID_HOP_BOUNDS);

        // 5. Hard limit cap (1..50, default 25)
        assertValidationError(() -> GraphComposeQuery.fromMap(Map.of(
                "matchNodes", List.of(Map.of("alias", "a")), "limit", 0)), ErrorCode.INVALID_LIMIT);
        assertValidationError(() -> GraphComposeQuery.fromMap(Map.of(
                "matchNodes", List.of(Map.of("alias", "a")), "limit", 51)), ErrorCode.INVALID_LIMIT);
        var defaultLimitQuery = GraphComposeQuery.fromMap(Map.of("matchNodes", List.of(Map.of("alias", "a"))));
        assertEquals(25, defaultLimitQuery.limit());

        // 6. Unknown operators, fields, returnFields, trustTiers, and unknown JSON keys
        assertValidationError(() -> GraphComposeQuery.fromMap(Map.of(
                "matchNodes", List.of(Map.of("alias", "a")),
                "filters", List.of(Map.of("alias", "a", "field", "fqn", "op", "MATCH_CYPHER", "value", "x")))),
                ErrorCode.UNKNOWN_FILTER_OPERATOR);
        assertValidationError(() -> GraphComposeQuery.fromMap(Map.of(
                "matchNodes", List.of(Map.of("alias", "a")),
                "filters", List.of(Map.of("alias", "a", "field", "rawSourceBody", "op", "CONTAINS", "value", "x")))),
                ErrorCode.UNKNOWN_FILTER_FIELD);
        assertValidationError(() -> GraphComposeQuery.fromMap(Map.of(
                "matchNodes", List.of(Map.of("alias", "a")),
                "returnFields", List.of("id", "sourceBody"))),
                ErrorCode.INVALID_RETURN_FIELD);
        assertValidationError(() -> GraphComposeQuery.fromMap(Map.of(
                "matchNodes", List.of(Map.of("alias", "a", "trustTier", "UNTRUSTED_TIER")))),
                ErrorCode.INVALID_TRUST_TIER);
        assertValidationError(() -> GraphComposeQuery.fromMap(Map.of(
                "matchNodes", List.of(Map.of("alias", "a")),
                "cypher", "MATCH (n) RETURN n")),
                ErrorCode.UNKNOWN_QUERY_FIELD);

        // 7. Oversized & unsafe ReDoS regexes (0% uncaught PatternSyntaxException!)
        String oversized = "a".repeat(GraphComposeQuery.MAX_PATTERN_LENGTH + 1);
        assertValidationError(() -> GraphComposeQuery.fromMap(Map.of(
                "matchNodes", List.of(Map.of("alias", "a", "namePattern", oversized)))),
                ErrorCode.OVERSIZED_PATTERN);
        assertValidationError(() -> GraphComposeQuery.fromMap(Map.of(
                "matchNodes", List.of(Map.of("alias", "a")),
                "filters", List.of(Map.of("alias", "a", "field", "fqn", "op", "REGEX_BOUNDED", "value", oversized)))),
                ErrorCode.OVERSIZED_PATTERN);

        for (String unsafeRegex : List.of("(a+)+", "(a*)*", "([a-z]+)+", "(a|aa)+", "a{1,500}", "(?=foo)", "(a)\\1", "[unclosed")) {
            var ex = assertValidationError(() -> GraphComposeQuery.fromMap(Map.of(
                    "matchNodes", List.of(Map.of("alias", "a")),
                    "filters", List.of(Map.of("alias", "a", "field", "fqn", "op", "REGEX_BOUNDED", "value", unsafeRegex)))),
                    ErrorCode.UNSAFE_REGEX_PATTERN);
            var errView = ex.toErrorView();
            assertEquals(GraphComposeQuery.SCHEMA_VERSION, errView.get("schema"));
            assertEquals(ErrorCode.UNSAFE_REGEX_PATTERN.name(), GraphJson.object(errView.get("error")).get("code"));
        }
    }

    @Test
    void multiHopCompositionalQueries1To4HopsFiltersAndReturnFieldProjection() {
        var fx = buildMultiLayerFixture();
        var service = new ArchitectureQueryService(fx.graph());

        // 1-hop query: OrderController --java.calls--> OrderService
        var hop1 = GraphComposeQuery.of(
                List.of(
                        new NodePattern("ctrl", Optional.of("TYPE"), Optional.of("*Controller"), Optional.of(TrustTier.COMPILER)),
                        new NodePattern("svc", Optional.of("TYPE"), Optional.of("*Service"), Optional.empty())),
                List.of(new EdgePattern("ctrl", "svc", List.of("java.calls"), 1, 1)),
                List.of());
        var res1 = service.composeQuery(fx.graph(), hop1);
        assertEquals(AssessmentStatus.COMPLETE, res1.status());
        assertEquals(StopReason.NONE, res1.stopReason());
        assertEquals(1, res1.matches().size());
        assertEquals("com.acme.web.OrderController", res1.matches().getFirst().bindings().get("ctrl").fqn());
        assertEquals("com.acme.service.OrderService", res1.matches().getFirst().bindings().get("svc").fqn());
        assertEquals(1, res1.matches().getFirst().matchedEdges().getFirst().hops());

        // Exact 2-hop query (even with a 1-hop shortcut present!): Controller -> Service -> Domain
        var hop2 = GraphComposeQuery.of(
                List.of(
                        new NodePattern("ctrl", Optional.of("TYPE"), Optional.of("OrderController"), Optional.empty()),
                        new NodePattern("dom", Optional.of("TYPE"), Optional.of("OrderDomain"), Optional.empty())),
                List.of(new EdgePattern("ctrl", "dom", List.of("java.calls"), 2, 2)),
                List.of());
        var res2 = service.composeQuery(fx.graph(), hop2);
        assertEquals(1, res2.matches().size(), "Exact minHops=2 must find 2-hop path even when a 1-hop shortcut exists");
        assertEquals(2, res2.matches().getFirst().matchedEdges().getFirst().hops());

        // Shortest-within-range 1..3 hops finds the 2-hop shortcut (Controller -> Domain -> Repository)
        var hop1To3 = GraphComposeQuery.of(
                List.of(
                        new NodePattern("ctrl", Optional.of("TYPE"), Optional.of("OrderController"), Optional.empty()),
                        new NodePattern("repo", Optional.of("TYPE"), Optional.of("OrderRepository"), Optional.empty())),
                List.of(new EdgePattern("ctrl", "repo", List.of("java.calls", "graph.depends-on"), 1, 3)),
                List.of());
        var res1To3 = service.composeQuery(fx.graph(), hop1To3);
        assertEquals(1, res1To3.matches().size());
        assertEquals(2, res1To3.matches().getFirst().matchedEdges().getFirst().hops());

        // Exact 3-hop query finds Controller -> Service -> Domain -> Repository (minHops=3, maxHops=3)
        var hop3 = GraphComposeQuery.of(
                List.of(
                        new NodePattern("ctrl", Optional.of("TYPE"), Optional.of("OrderController"), Optional.empty()),
                        new NodePattern("repo", Optional.of("TYPE"), Optional.of("OrderRepository"), Optional.empty())),
                List.of(new EdgePattern("ctrl", "repo", List.of("java.calls", "graph.depends-on"), 3, 3)),
                List.of());
        var res3 = service.composeQuery(fx.graph(), hop3);
        assertEquals(1, res3.matches().size());
        assertEquals(3, res3.matches().getFirst().matchedEdges().getFirst().hops());

        // 4-hop query: Controller -> Service -> Domain -> Repository -> Entity (minHops=4, maxHops=4)
        var hop4 = GraphComposeQuery.of(
                List.of(
                        new NodePattern("ctrl", Optional.of("TYPE"), Optional.of("OrderController"), Optional.empty()),
                        new NodePattern("ent", Optional.of("TYPE"), Optional.of("OrderEntity"), Optional.empty())),
                List.of(new EdgePattern("ctrl", "ent", List.of("java.calls", "graph.depends-on"), 4, 4)),
                List.of());
        var res4 = service.composeQuery(fx.graph(), hop4);
        assertEquals(1, res4.matches().size());
        assertEquals("com.acme.model.OrderEntity", res4.matches().getFirst().bindings().get("ent").fqn());
        assertEquals(4, res4.matches().getFirst().matchedEdges().getFirst().hops());

        // Edge-kind filter excludes graph.depends-on when only java.calls is requested for 4 hops
        var hop4CallsOnly = GraphComposeQuery.of(
                List.of(
                        new NodePattern("ctrl", Optional.of("TYPE"), Optional.of("OrderController"), Optional.empty()),
                        new NodePattern("ent", Optional.of("TYPE"), Optional.of("OrderEntity"), Optional.empty())),
                List.of(new EdgePattern("ctrl", "ent", List.of("java.calls"), 4, 4)),
                List.of());
        assertTrue(service.composeQuery(fx.graph(), hop4CallsOnly).matches().isEmpty());

        // All 6 filter operators: EQ, NEQ, CONTAINS, PREFIX, REGEX_BOUNDED, EXISTS
        var filtered = new GraphComposeQuery(
                Optional.of(fx.contextId()),
                List.of(new NodePattern("t", Optional.of("TYPE"), Optional.empty(), Optional.of(TrustTier.COMPILER))),
                List.of(),
                List.of(
                        new FilterClause("t", "fqn", FilterOp.PREFIX, Optional.of("com.acme.")),
                        new FilterClause("t", "fqn", FilterOp.CONTAINS, Optional.of("order")),
                        new FilterClause("t", "fqn", FilterOp.NEQ, Optional.of("com.acme.model.OrderEntity")),
                        new FilterClause("t", "fqn", FilterOp.REGEX_BOUNDED, Optional.of("^com\\.acme\\.(web|service)\\.Order[A-Z][a-z]+$")),
                        new FilterClause("t", "path", FilterOp.EXISTS, Optional.of("true")),
                        new FilterClause("t", "trustTier", FilterOp.EQ, Optional.of("COMPILER"))),
                List.of("id", "fqn"),
                10);
        var resFiltered = service.composeQuery(fx.graph(), filtered);
        assertEquals(2, resFiltered.matches().size());
        var matchedFqns = resFiltered.matches().stream()
                .map(m -> m.bindings().get("t").fqn())
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("com.acme.web.OrderController", "com.acme.service.OrderService"), matchedFqns);

        // Verify returnFields projection omits unrequested fields while ALWAYS preserving pointerRef
        var viewMatches = GraphJson.array(resFiltered.toView().get("matches"));
        var projectedNode = GraphJson.object(GraphJson.object(GraphJson.object(viewMatches.getFirst()).get("bindings")).get("t"));
        assertTrue(projectedNode.containsKey("id"));
        assertTrue(projectedNode.containsKey("fqn"));
        assertTrue(projectedNode.containsKey("pointerRef"));
        assertFalse(projectedNode.containsKey("span"), "Unrequested span must be omitted when returnFields=[id, fqn]");
        assertFalse(projectedNode.containsKey("kind"), "Unrequested kind must be omitted when returnFields=[id, fqn]");
    }

    @Test
    void pointerIndexedResultsMatchFormatAndCutTokenBloatByMoreThan4xVsRawFileBodies() {
        var fx = buildMultiLayerFixture();
        var service = new ArchitectureQueryService(fx.graph());
        var query = GraphComposeQuery.of(
                List.of(new NodePattern("n", Optional.of("TYPE"), Optional.empty(), Optional.empty())),
                List.of(),
                List.of());
        var res = service.composeQuery(fx.graph(), query);
        assertEquals(5, res.matches().size());

        String serialized = CanonicalJson.write(res.toView());
        for (var match : res.matches()) {
            var node = match.bindings().get("n");
            assertTrue(POINTER_REF_PATTERN.matcher(node.pointerRef()).matches(),
                    () -> "pointerRef must match path:startLine-endLine@sha256Prefix, got: " + node.pointerRef());
            assertTrue(node.pointerRef().startsWith("src/main/java/com/acme/"),
                    () -> "Expected validated relative path in pointerRef, got: " + node.pointerRef());
            assertTrue(node.span().startLine() >= 1);
        }
        // Raw source bodies must NEVER be embedded in composeQuery results
        assertFalse(serialized.contains("RAW_BODY_SENTINEL_MARKER"), "Raw source body must never be embedded");
        assertTrue(fx.totalRawBodyBytes() >= serialized.length() * 4L,
                () -> "Pointer-indexed payload (" + serialized.length()
                        + " B) must cut bloat >= 4x vs raw file bodies (" + fx.totalRawBodyBytes() + " B)");
    }

    @Test
    void determinismScoreDescEntityIdAscLimitTruncationAndReloadParity() {
        var fx = buildMultiLayerFixture();
        var cached = new ArchitectureQueryService(fx.graph());
        var uncached = ArchitectureQueryService.withoutMemoization(fx.graph());

        var query = new GraphComposeQuery(
                Optional.of(fx.contextId()),
                List.of(new NodePattern("n", Optional.of("TYPE"), Optional.of("Order"), Optional.empty())),
                List.of(),
                List.of(),
                GraphComposeQuery.ALLOWED_RETURN_FIELDS,
                3);
        var r1 = cached.composeQuery(fx.graph(), query);
        var r2 = cached.composeQuery(fx.graph(), query);
        var rUncached = uncached.composeQuery(fx.graph(), query);

        assertEquals(3, r1.matches().size());
        assertEquals(5, r1.totalMatched());
        assertTrue(r1.truncated(), "Slicing 5 matches to limit=3 must set truncated=true");
        assertEquals(rUncached, r1);
        assertEquals(r1, r2);
        assertEquals(rUncached.identity(), r2.identity());

        // Reload via GraphSnapshotCodec and assert bit-identical SHA-256 identity (I2)
        var reloaded = new GraphSnapshotCodec().decode(
                new GraphSnapshotCodec().encode(fx.graph()), GraphJson.Limits.defaults());
        var rReloaded = new ArchitectureQueryService(reloaded).composeQuery(reloaded, query);
        assertEquals(r1.identity(), rReloaded.identity());

        // Verify strict ordering: (score DESC, entityId ASC)
        for (int i = 1; i < r1.matches().size(); i++) {
            var prev = r1.matches().get(i - 1);
            var curr = r1.matches().get(i);
            int cmpScore = prev.score().compareTo(curr.score());
            assertTrue(cmpScore > 0 || (cmpScore == 0 && prev.entityId().compareTo(curr.entityId()) <= 0),
                    () -> "Matches must be sorted by (score DESC, entityId ASC): " + prev + " vs " + curr);
        }
    }

    @Test
    void operationalGuardEnforcesMemoryTimeCancelledAndVisitedStateCaps() {
        var fx = buildMultiLayerFixture();
        var query = GraphComposeQuery.of(
                List.of(
                        new NodePattern("a", Optional.of("TYPE"), Optional.empty(), Optional.empty()),
                        new NodePattern("b", Optional.of("TYPE"), Optional.empty(), Optional.empty())),
                List.of(new EdgePattern("a", "b", List.of(), 1, 4)),
                List.of());

        // StopReason.MEMORY
        var memSvc = new ArchitectureQueryService(fx.graph(), () -> StopReason.MEMORY, Optional.empty());
        var memRes = memSvc.composeQuery(fx.graph(), query);
        assertEquals(AssessmentStatus.PARTIAL, memRes.status());
        assertEquals(StopReason.MEMORY, memRes.stopReason());
        assertTrue(memRes.truncated());
        assertTrue(memRes.gaps().stream().anyMatch(g -> "COMPOSE_QUERY_MEMORY_LIMIT".equals(g.reasonCode())));

        // StopReason.TIME
        var timeSvc = new ArchitectureQueryService(fx.graph(), () -> StopReason.TIME, Optional.empty());
        var timeRes = timeSvc.composeQuery(fx.graph(), query);
        assertEquals(AssessmentStatus.PARTIAL, timeRes.status());
        assertEquals(StopReason.TIME, timeRes.stopReason());
        assertTrue(timeRes.gaps().stream().anyMatch(g -> "COMPOSE_QUERY_TIME_LIMIT".equals(g.reasonCode())));

        // StopReason.CANCELLED
        var cancelSvc = new ArchitectureQueryService(fx.graph(), () -> StopReason.CANCELLED, Optional.empty());
        var cancelRes = cancelSvc.composeQuery(fx.graph(), query);
        assertEquals(AssessmentStatus.PARTIAL, cancelRes.status());
        assertEquals(StopReason.CANCELLED, cancelRes.stopReason());
        assertTrue(cancelRes.gaps().stream().anyMatch(g -> "COMPOSE_QUERY_CANCELLED".equals(g.reasonCode())));

        // Visited-state cap -> StopReason.STEPS
        var stateSvc = new ArchitectureQueryService(fx.graph());
        var stateRes = stateSvc.composeQuery(fx.graph(), query, 2_000L, 4);
        assertEquals(AssessmentStatus.PARTIAL, stateRes.status());
        assertEquals(StopReason.STEPS, stateRes.stopReason());
        assertTrue(stateRes.truncated());
        assertTrue(stateRes.gaps().stream().anyMatch(g -> "COMPOSE_QUERY_STATE_LIMIT".equals(g.reasonCode())));

        // Missing context -> PARTIAL with COMPOSE_CONTEXT_NOT_FOUND
        var badCtxQuery = new GraphComposeQuery(
                Optional.of("analysis:sha256:" + "0".repeat(64)),
                List.of(new NodePattern("a", Optional.empty(), Optional.empty(), Optional.empty())),
                List.of(), List.of(), GraphComposeQuery.ALLOWED_RETURN_FIELDS, 10);
        var badCtxRes = stateSvc.composeQuery(fx.graph(), badCtxQuery);
        assertEquals(AssessmentStatus.PARTIAL, badCtxRes.status());
        assertTrue(badCtxRes.matches().isEmpty());
        assertTrue(badCtxRes.gaps().stream().anyMatch(g -> "COMPOSE_CONTEXT_NOT_FOUND".equals(g.reasonCode())));
    }

    @Test
    void schemaViewSummarizesLiveNodeKindsEdgeKindsTrustTiersAndEvidenceFamiliesDeterministically() {
        var fx = buildMultiLayerFixture();
        var service = new ArchitectureQueryService(fx.graph());
        var schema = service.schemaView(fx.graph());

        assertEquals(GraphComposeQuery.SCHEMA_VIEW_VERSION, schema.schema());
        assertEquals(KnowledgeGraph.SCHEMA, schema.graphSchema());
        assertEquals(fx.graph().identity(), schema.graphDigest());
        assertEquals(5L, schema.nodeKinds().get("TYPE"));
        assertEquals(1L, schema.nodeKinds().get("ENDPOINT"));
        assertTrue(schema.edgeKinds().containsKey("java.calls"));
        assertTrue(schema.edgeKinds().containsKey("graph.depends-on"));
        assertTrue(schema.trustTiers().containsKey("COMPILER"));
        assertTrue(schema.trustTiers().containsKey("SPRING_SAT"));
        assertTrue(schema.evidenceFamilies().containsKey("java.declaration"));
        assertTrue(schema.evidenceFamilies().containsKey("spring.endpoint-declaration"));
        assertEquals(GraphComposeQuery.QUERYABLE_FILTER_FIELDS, schema.queryableFields());
        assertEquals(GraphComposeQuery.ALLOWED_RETURN_FIELDS, schema.returnFields());
        assertEquals(GraphComposeQuery.MAX_HOPS, schema.limits().get("maxHops"));
        assertEquals(GraphComposeQuery.MAX_LIMIT, schema.limits().get("maxLimit"));
        assertEquals(GraphComposeQuery.POINTER_REF_FORMAT, schema.pointerRefFormat());
        assertEquals("read_captured_source", schema.lazyReadTool());

        // Keys must be strictly sorted & reload identity bit-identical
        assertEquals(schema.nodeKinds().keySet().stream().sorted().toList(),
                List.copyOf(schema.nodeKinds().keySet()));
        assertEquals(schema.edgeKinds().keySet().stream().sorted().toList(),
                List.copyOf(schema.edgeKinds().keySet()));
        var reloaded = new GraphSnapshotCodec().decode(
                new GraphSnapshotCodec().encode(fx.graph()), GraphJson.Limits.defaults());
        assertEquals(schema.identity(), new ArchitectureQueryService(reloaded).schemaView(reloaded).identity());
    }

    // -------------------------------------------------------------------------
    // Helpers & Multi-Layer Fixture
    // -------------------------------------------------------------------------

    private static ValidationException assertValidationError(Runnable action, ErrorCode expected) {
        var ex = assertThrows(ValidationException.class, action::run);
        assertEquals(expected, ex.errorCode(), () -> "Expected " + expected + " but got " + ex.getMessage());
        return ex;
    }

    private record Fixture(KnowledgeGraph graph, String contextId, long totalRawBodyBytes) {}

    private static Fixture buildMultiLayerFixture() {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/compose-fixture.git");
        var snap = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("compose-snap"));
        var analysis = AnalysisIdentity.fromCanonicalManifestInputs("compose-mod");
        String ctx = analysis.value();
        var modDesc = ModuleDescriptor.create(repo, "orders", ".");
        var modId = modDesc.identity();

        var snapEv = GraphEvidence.capture("repository.snapshot", Map.of("snapshot", snap));
        var manEv = GraphEvidence.capture("analysis.manifest", Map.of("modules", List.of(Map.of("identity", modId, "path", "."))));
        var modEv = GraphEvidence.capture("module.descriptor", modDesc);

        var evidence = new ArrayList<GraphEvidence>(List.of(snapEv, manEv, modEv));
        var nodes = new ArrayList<KnowledgeGraph.Node>();
        var edges = new ArrayList<KnowledgeGraph.Edge>();
        nodes.add(new KnowledgeGraph.Node(modId.value(), ctx, "MODULE", List.of(modEv.identity())));

        var specs = List.of(
                List.of("com.acme.web", "OrderController", "src/main/java/com/acme/web/OrderController.java", "10", "55"),
                List.of("com.acme.service", "OrderService", "src/main/java/com/acme/service/OrderService.java", "12", "78"),
                List.of("com.acme.domain", "OrderDomain", "src/main/java/com/acme/domain/OrderDomain.java", "8", "64"),
                List.of("com.acme.repo", "OrderRepository", "src/main/java/com/acme/repo/OrderRepository.java", "5", "40"),
                List.of("com.acme.model", "OrderEntity", "src/main/java/com/acme/model/OrderEntity.java", "6", "48"));

        var typeIds = new HashMap<String, String>();
        long rawBodyBytes = 0L;
        for (var spec : specs) {
            String pkg = spec.get(0);
            String cls = spec.get(1);
            String relPath = spec.get(2);
            int sLine = Integer.parseInt(spec.get(3));
            int eLine = Integer.parseInt(spec.get(4));

            // Large synthetic raw source body (simulating realistic captured files for token-bloat ratio proof)
            String body = "// RAW_BODY_SENTINEL_MARKER " + cls + "\n" + "public class " + cls + " {\n"
                    + "    // business logic line padding for realistic source file length\n".repeat(90) + "}\n";
            rawBodyBytes += body.length();
            var doc = SourceDocument.create(repo, modDesc, relPath, ContentDigest.sha256Utf8(body), SourceClassification.MAIN);
            var docEv = GraphEvidence.capture("source.document", doc);
            evidence.add(docEv);
            nodes.add(new KnowledgeGraph.Node(doc.identity().value(), ctx, "SOURCE_DOCUMENT", List.of(docEv.identity())));

            var entity = Entity.create(EntityOrigin.PROJECT, EntityScope.project(modId), EntityKind.TYPE,
                    JavaSymbolName.topLevelType(pkg, cls).canonicalName(),
                    Optional.of(new SourceSpan(doc.identity(), sLine, 1, eLine, 1)));
            var declEv = GraphEvidence.capture("java.declaration",
                    Map.of("entity", entity, "spelling", cls, "status", "RESOLVED"));
            evidence.add(declEv);
            nodes.add(new KnowledgeGraph.Node(entity.identity().value(), ctx, "TYPE", List.of(declEv.identity())));
            typeIds.put(cls, entity.identity().value());
        }

        // Spring endpoint node (TrustTier.SPRING_SAT)
        String epId = KnowledgeGraph.derivedIdentity("endpoint", List.of("GET", "/api/orders"));
        var epEv = GraphEvidence.capture("spring.endpoint-declaration", Map.of(
                "schema", "spring.endpoint:v1",
                "module", modId.value(),
                "sourceSet", "MAIN",
                "route", Map.of("path", "/api/orders", "methods", List.of("GET"),
                        "controller", typeIds.get("OrderController"))));
        evidence.add(epEv);
        nodes.add(new KnowledgeGraph.Node(epId, ctx, "ENDPOINT", List.of(epEv.identity())));

        // 4-hop chain: Controller -> Service -> Domain -> Repository -> Entity + 1-hop shortcut Controller -> Domain
        addEdge(edges, ctx, "spring.exposes-endpoint", epId, typeIds.get("OrderController"), epEv.identity());
        addEdge(edges, ctx, "java.calls", typeIds.get("OrderController"), typeIds.get("OrderService"), manEv.identity());
        addEdge(edges, ctx, "java.calls", typeIds.get("OrderService"), typeIds.get("OrderDomain"), manEv.identity());
        addEdge(edges, ctx, "java.calls", typeIds.get("OrderController"), typeIds.get("OrderDomain"), manEv.identity());
        addEdge(edges, ctx, "graph.depends-on", typeIds.get("OrderDomain"), typeIds.get("OrderRepository"), manEv.identity());
        addEdge(edges, ctx, "graph.depends-on", typeIds.get("OrderRepository"), typeIds.get("OrderEntity"), manEv.identity());

        var graph = new KnowledgeGraph(snap,
                List.of(
                        new KnowledgeGraph.Context(snap.value(), snap, Optional.empty(), snapEv.identity()),
                        new KnowledgeGraph.Context(ctx, snap, Optional.of(analysis), manEv.identity())),
                nodes, edges, evidence);
        return new Fixture(graph, ctx, rawBodyBytes);
    }

    private static void addEdge(List<KnowledgeGraph.Edge> edges, String ctx, String kind,
                                String from, String to, ContentDigest ev) {
        edges.add(new KnowledgeGraph.Edge(
                KnowledgeGraph.derivedIdentity("edge", List.of(kind, from, to)),
                ctx, kind, from,
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(to), Optional.empty()),
                List.of(ev), Optional.empty()));
    }
}
