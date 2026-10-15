package com.evolution.analysis.javaparser;
import com.evolution.analysis.frontend.SourceOutcome;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class UnicodeSourceTest {
    @Test void jlsExamplesPreserveEligibilityAndDoNotTranslateRecursively(){
        String slash="\\";
        assertEquals(slash+slash+"u2122=™",UnicodeSource.translate(slash+slash+"u2122="+slash+"u2122").text);
        assertEquals(slash+"u005a",UnicodeSource.translate(slash+"u005cu005a").text);
        assertEquals(slash+slash+"n",UnicodeSource.translate(slash+"u005c"+slash+"u005c"+slash+"u006e").text);
        assertEquals("A",UnicodeSource.translate(slash+"uuuu0041").text);
        assertThrows(IllegalArgumentException.class,()->UnicodeSource.translate(slash+"u０００Ａ"));
    }
    @Test void escapedLineAndFinalDelimiterRetainExactRawSourceSpans(){
        String slash="\\",escaped=slash+"u0068it("+slash+"u0029";
        String text="class C { void hit(){} void f(){ "+escaped+"; // "+slash+"u000a hit();\r\n} }";
        var request=TestInputs.request(text);var result=new JavaParserFrontend().analyze(request);
        var source=new OriginalSource(request.sources().getFirst().document().identity(),text);
        var calls=result.occurrences().stream().filter(o->o.relationship().kind().value().equals("java.calls")).toList();
        assertEquals(2,calls.size());assertEquals(Set.of(escaped,"hit()"),calls.stream().map(o->source.slice(o.span())).collect(java.util.stream.Collectors.toSet()));
        assertTrue(calls.stream().allMatch(o->o.span().startLine()==1));
    }
    @Test void malformedEligibleEscapeInCommentIsExplicitAndDoesNotDiscardValidSibling(){
        var request=TestInputs.request(Map.of("fixture/Bad.java","// "+"\\"+"uBAD\nclass Bad {}","fixture/Good.java","class Good {}"),List.of());
        var result=new JavaParserFrontend().analyze(request);
        assertEquals(1,result.sources().stream().filter(s->s.state()==SourceOutcome.State.ERROR).count());
        assertTrue(result.diagnostics().stream().anyMatch(d->d.code().equals("java.unicode-escape-error")));
        assertTrue(result.declarations().stream().anyMatch(d->d.entity().canonicalName().contains("Good")));
        assertTrue(result.declarations().stream().noneMatch(d->d.entity().canonicalName().contains("Bad")));
    }
}
