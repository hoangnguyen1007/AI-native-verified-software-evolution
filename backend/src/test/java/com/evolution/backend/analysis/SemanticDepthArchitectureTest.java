package com.evolution.backend.analysis;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SemanticDepthArchitectureTest {
    @Test void declarationStructureIsMeasuredSeparatelyFromExpressionUsageOverTheSameDenominator() {
        var categories=new TreeMap<String,long[]>();
        categories.put("java.extends",new long[]{10,9});categories.put("java.field-type",new long[]{40,40});categories.put("java.parameter-type",new long[]{50,49});
        categories.put("java.calls",new long[]{1000,300});categories.put("java.reads-field",new long[]{100,60});
        categories.put("java.some-future-category",new long[]{7,0});
        var split=SemanticDepth.architectureResolution(categories);
        @SuppressWarnings("unchecked") var structure=(Map<String,Object>)split.get("structure");@SuppressWarnings("unchecked") var usage=(Map<String,Object>)split.get("usage");
        assertEquals(100L,structure.get("attempted"));assertEquals(98L,structure.get("resolved"));assertEquals(2L,structure.get("notResolved"));assertEquals(980L,structure.get("resolvedPerMille"));
        assertEquals(1100L,usage.get("attempted"));assertEquals(360L,usage.get("resolved"));assertEquals(327L,usage.get("resolvedPerMille"));
        assertEquals(0L,((Map<?,?>)SemanticDepth.architectureResolution(Map.of()).get("structure")).get("resolvedPerMille"),"An empty denominator is not a perfect score");
        assertEquals(com.evolution.analysis.contract.serialization.CanonicalJson.write(split),com.evolution.analysis.contract.serialization.CanonicalJson.write(SemanticDepth.architectureResolution(categories)));
    }

    @Test void passiveArchitectureThresholdRequiresNinetyEightPercentStructureAndNoFailedUnits() {
        var jvmCompleteDepth = Map.<String, Object>of(
                "analyzedContexts", 4L,
                "architectureResolution", Map.of(
                        "structure", Map.of("attempted", 1000L, "resolved", 985L, "notResolved", 15L, "resolvedPerMille", 985L)),
                "relationships", Map.of(
                        "byCategory", Map.of(
                                "java.extends", Map.of("attempted", 50L, "resolved", 50L),
                                "java.annotated-with", Map.of("attempted", 150L, "resolved", 148L))));
        var polyglotOk = Map.<String, Object>of(
                "sourceSyntax", Map.of("documents", 22L, "parsedDocuments", 22L),
                "edges", 120L);

        assertTrue(SemanticDepth.meetsPassiveArchitectureThreshold(
                jvmCompleteDepth, polyglotOk, false, false, 40L, false));
        assertEquals("PASSIVE_ARCHITECTURE_RECOVERY", SemanticDepth.evidenceTier(
                com.evolution.analysis.ingestion.UniversalArchitectureReport.Status.PARTIAL, false, true));
        assertEquals("VERIFIED_EXACT", SemanticDepth.evidenceTier(
                com.evolution.analysis.ingestion.UniversalArchitectureReport.Status.COMPLETE, true, false));

        // Fails if a unit was rejected or provider failed
        assertFalse(SemanticDepth.meetsPassiveArchitectureThreshold(
                jvmCompleteDepth, polyglotOk, true, false, 40L, false));

        // Fails if below 980 per-mille (98.0%)
        var belowThresholdDepth = Map.<String, Object>of(
                "analyzedContexts", 1L,
                "architectureResolution", Map.of(
                        "structure", Map.of("attempted", 1000L, "resolved", 979L, "notResolved", 21L, "resolvedPerMille", 979L)),
                "relationships", Map.of(
                        "byCategory", Map.of(
                                "java.extends", Map.of("attempted", 100L, "resolved", 100L))));
        assertFalse(SemanticDepth.meetsPassiveArchitectureThreshold(
                belowThresholdDepth, polyglotOk, false, false, 10L, false));

        // Pure non-JVM polyglot repository (e.g. FastAPI + React/TS) with >= 98% parsed syntax and edges > 0
        var nonJvmDepth = Map.<String, Object>of(
                "analyzedContexts", 0L,
                "architectureResolution", Map.of(
                        "structure", Map.of("attempted", 0L, "resolved", 0L, "notResolved", 0L, "resolvedPerMille", 0L)),
                "relationships", Map.of("byCategory", Map.of()));
        var fastApiPolyglot = Map.<String, Object>of(
                "sourceSyntax", Map.of("documents", 152L, "parsedDocuments", 152L),
                "edges", 4897L);
        assertTrue(SemanticDepth.meetsPassiveArchitectureThreshold(
                nonJvmDepth, fastApiPolyglot, false, false, 0L, false));
        assertFalse(SemanticDepth.meetsPassiveArchitectureThreshold(
                nonJvmDepth, fastApiPolyglot, false, false, 0L, true));
    }
}

