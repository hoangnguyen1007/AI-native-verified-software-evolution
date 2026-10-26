package com.evolution.backend.analysis;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.graph.GraphJson;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CapturedAnalysisComponentsTest {
    static class First {static final String PROVIDER="same-version";static class Nested {}}
    static class Second {static final String PROVIDER="same-version";}
    @Test void nominallyEqualVersionsCannotHideDifferentCapturedImplementationBytes()throws Exception{
        var first=CapturedAnalysisComponents.capture(List.of(First.class));var second=CapturedAnalysisComponents.capture(List.of(Second.class));
        assertEquals(First.PROVIDER,Second.PROVIDER);assertNotEquals(first.identity(),second.identity());
        var fields=GraphJson.object(GraphJson.read(first.canonicalJson(),GraphJson.Limits.defaults()));var classes=GraphJson.object(fields.get("classes"));
        try(var input=First.class.getResourceAsStream("/"+First.class.getName().replace('.','/')+".class")){
            assertEquals(ContentDigest.sha256(input.readAllBytes()).value(),classes.get(First.class.getName()));
        }
        assertTrue(classes.containsKey(First.Nested.class.getName()));assertEquals("PRIMARY_IMPLEMENTATION_CLASSES",fields.get("scope"));
        assertEquals(CapturedAnalysisComponents.capture(List.of(First.class,Second.class)).identity(),CapturedAnalysisComponents.capture(List.of(Second.class,First.class,First.class)).identity());
    }
}
