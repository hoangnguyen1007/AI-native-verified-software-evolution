package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.frontend.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JavaParserFrontendTest {
    private final SemanticFrontend frontend = new JavaParserFrontend();

    @Test void extractsOverloadsAndGenericChainWithTrueOriginsAndExactOccurrences() {
        String source = "package p; class C { String pick(String s){return s;} String pick(Object s){return s.toString();} "
                + "String run(java.util.List<String> xs){pick(\"x\"); pick((Object)\"x\"); return xs.get(0).trim();} }";
        var request = TestInputs.request(source);
        var result = frontend.analyze(request);
        var calls = result.occurrences().stream().filter(o -> o.relationship().kind().value().equals("java.calls")).toList();
        assertEquals(5, calls.size());
        assertEquals(5, calls.stream().filter(o -> o.status() == SemanticStatus.RESOLVED).count());
        var byId = new HashMap<com.evolution.analysis.contract.identity.EntityIdentity, Entity>();
        result.declarations().forEach(d -> byId.put(d.entity().identity(), d.entity()));
        var original = new OriginalSource(request.sources().getFirst().document().identity(), source);
        for (var call : calls) {
            Entity target = byId.get(((RelationshipTarget.Resolved)call.relationship().target()).target());
            String text = original.slice(call.span());
            if (text.equals("pick(\"x\")")) {
                assertEquals("java:v1:[\"method\",[\"type\",[\"p\"],\"C\"],\"pick\",[[\"declared\",[\"type\",[\"java\",\"lang\"],\"String\"]]]]", target.canonicalName());
                assertEquals(EntityOrigin.PROJECT, target.origin());
                assertNotEquals(call.span(), target.declaration().orElseThrow());
            } else if (text.equals("xs.get(0)")) {
                assertEquals("java:v1:[\"method\",[\"type\",[\"java\",\"util\"],\"List\"],\"get\",[[\"primitive\",\"int\"]]]", target.canonicalName());
                assertEquals(EntityOrigin.JDK, target.origin()); assertTrue(target.declaration().isEmpty());
            }
            assertEquals(0, call.ordinal());
        }
        assertEquals(CanonicalJson.write(result), CanonicalJson.write(frontend.analyze(request)));
    }

    @Test void unresolvedCallsRetainEvidenceAndNeverInventTargets() {
        var result = frontend.analyze(TestInputs.request("class C { Missing missing; String run(String s){ missing.work(); return s.trim(); } }"));
        var calls = result.occurrences().stream().filter(o -> o.relationship().kind().value().equals("java.calls")).toList();
        assertEquals(2, calls.size());
        assertEquals(1, calls.stream().filter(o -> o.status() == SemanticStatus.UNRESOLVED).count());
        assertEquals(1, calls.stream().filter(o -> o.status() == SemanticStatus.RESOLVED).count());
        assertEquals(FrontendResult.State.PARTIAL, result.state());
        assertTrue(result.declarations().stream().noneMatch(d -> d.entity().canonicalName().contains("work")));
    }

    @Test void syntaxErrorsAreExplicitAndNotSuccessfulEmptyAnalyses() {
        var result = frontend.analyze(TestInputs.request("class C { void run( {"));
        assertEquals(SourceOutcome.State.ERROR, result.sources().getFirst().state());
        assertFalse(result.diagnostics().isEmpty());
        assertNotEquals(FrontendResult.State.COMPLETED, result.state());
    }
}
