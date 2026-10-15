package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.query.ArchitectureQuery.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class QueryJsonTest {
    static final ContentDigest GRAPH = ContentDigest.sha256Utf8("graph");
    static Map<String,Object> tree(Object value) {
        return GraphJson.object(GraphJson.read(CanonicalJson.write(value), GraphJson.Limits.defaults()));
    }
    @Test void canonicalRequestRoundTripsWithoutChangingQueryIdentity() {
        var request = new Request(GRAPH, ContentDigest.sha256Utf8("context").value(), Scope.TYPE,
                Projection.REALIZED, Direction.INCOMING, Set.of("java.calls", "java.type-use"),
                new Population(Set.of("TYPE"), Set.of("module"), Set.of("PROJECT"), Set.of("MAIN")), true,
                Optional.of(new Realization(Optional.empty(), Map.of("opaque-variable", true))), Limits.defaults());
        assertEquals(request, QueryJson.request(GRAPH, tree(request)));
        assertEquals(request.identity(), QueryJson.request(GRAPH, tree(request)).identity());
    }
    @ParameterizedTest @ValueSource(strings={"maxDepth","maxSteps","maxResults","maxVertices","maxEdges","maxSolverSteps","maxFormulaNodes","maxAuxiliaryStates"})
    void queryLimitsRejectFractionalAndOverflowValues(String field) {
        var request=tree(Request.dependencies(GRAPH,"context"));
        request.put("limits",Map.of(field,new java.math.BigDecimal("1.5")));
        assertThrows(IllegalArgumentException.class,() -> QueryJson.request(GRAPH,request));
        request.put("limits",Map.of(field,new java.math.BigDecimal("9223372036854775808")));
        assertThrows(IllegalArgumentException.class,() -> QueryJson.request(GRAPH,request));
    }
    @ParameterizedTest @ValueSource(strings={"maxDepth","maxSteps","maxResults","maxVertices","maxEdges","maxSolverSteps","maxFormulaNodes","maxAuxiliaryStates"})
    void canonicalHardCeilingsCannotBeRaisedThroughJson(String field) {
        var input=tree(Request.dependencies(GRAPH,"context"));
        long ceiling=switch(field) {
            case "maxDepth" -> 20; case "maxSteps","maxVertices","maxFormulaNodes" -> 100_000;
            case "maxResults" -> 50; case "maxEdges","maxAuxiliaryStates" -> 500_000;
            case "maxSolverSteps" -> 10_000_000; default -> throw new AssertionError();
        };
        input.put("limits",Map.of(field,java.math.BigDecimal.valueOf(ceiling+1)));
        assertThrows(IllegalArgumentException.class,() -> QueryJson.request(GRAPH,input));
        input.put("limits",Map.of(field,java.math.BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,() -> QueryJson.request(GRAPH,input));
        input.put("limits",Map.of(field,java.math.BigDecimal.valueOf(ceiling)));
        assertDoesNotThrow(() -> QueryJson.request(GRAPH,input));
    }
    @Test void unknownFieldsDuplicateSetsForeignGraphAndIncompatibleRealizationAreRejected() {
        var input=tree(Request.dependencies(GRAPH,"context"));
        input.put("surprise",true);
        assertThrows(IllegalArgumentException.class,() -> QueryJson.request(GRAPH,input));
        input.remove("surprise"); input.put("relationshipKinds",List.of("java.calls","java.calls"));
        assertThrows(IllegalArgumentException.class,() -> QueryJson.request(GRAPH,input));
        input.put("relationshipKinds",List.of());
        assertThrows(IllegalArgumentException.class,() -> QueryJson.request(GRAPH,input));
        input.put("relationshipKinds",List.of("java.calls")); input.put("graph",ContentDigest.sha256Utf8("foreign").value());
        assertThrows(IllegalArgumentException.class,() -> QueryJson.request(GRAPH,input));
        input.put("graph",GRAPH.value()); input.put("projection","REALIZED");
        assertThrows(IllegalArgumentException.class,() -> QueryJson.request(GRAPH,input));
        input.put("realization",Map.of("world","world","assignment",Map.of("p",true)));
        assertThrows(IllegalArgumentException.class,() -> QueryJson.request(GRAPH,input));
    }
    @Test void defaultsAndExplicitWorldSurviveJsonAndPageValidation() {
        var input=tree(Request.dependencies(GRAPH,"context"));
        input.remove("limits"); input.remove("population"); input.remove("includeMust"); input.remove("graph");
        assertEquals(Request.dependencies(GRAPH,"context"),QueryJson.request(GRAPH,input));
        input.put("projection","REALIZED"); input.put("realization",Map.of("world","world"));
        assertEquals("world",QueryJson.request(GRAPH,input).realization().orElseThrow().world().orElseThrow());
        assertThrows(IllegalArgumentException.class,() -> QueryJson.page(tree(Map.of("size",51))));
        assertThrows(IllegalArgumentException.class,() -> QueryJson.page(Map.of("unexpected",true)));
        assertThrows(IllegalArgumentException.class,() -> QueryJson.request(GRAPH,tree(Map.of("context","context"))));
    }
}
