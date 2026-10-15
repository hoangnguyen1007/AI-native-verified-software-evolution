package com.evolution.analysis.javaparser;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SharedWrittenTypeTest {
    @Test void threeLocalDeclaratorsAreOneWrittenTypeSiteWithoutDuplicateFailureObservations(){
        var result=assertDoesNotThrow(()->new JavaParserFrontend().analyze(TestInputs.request("class C { void f(){ String a=\"\",b=\"\",c=\"\"; } }")));
        assertEquals(1,result.types().stream().filter(t->t.type().spelling().equals("String")).count());
        assertTrue(result.diagnostics().stream().noneMatch(d->d.code().equals("java.duplicate-fact")));
    }
    @Test void mixedUnicodeTranslationAndOrdinaryEscapesKeepExactOriginalCoordinates(){
        String text="class "+"\\"+"u0043 {\n void f(){String p=\"\\\\R\\\\D\\\\S\\\\d\\\\w\";throw new IllegalArgumentException();\n }\n}";
        var request=TestInputs.request(text);var result=assertDoesNotThrow(()->new JavaParserFrontend().analyze(request));
        var source=new OriginalSource(request.sources().getFirst().document().identity(),text);
        assertTrue(result.declarations().stream().anyMatch(d->d.entity().canonicalName().equals("java:v1:[\"type\",[],\"C\"]")));
        var type=result.types().stream().filter(t->t.type().spelling().equals("IllegalArgumentException")).findFirst().orElseThrow();
        assertEquals("IllegalArgumentException",source.slice(type.span()));
    }
    @Test void unicodeTranslationUsesRawBackslashParityAndRejectsMalformedEligibleMarkers(){
        assertEquals("\\\\"+"u0061",UnicodeSource.translate("\\\\"+"u0061").text);
        assertEquals("a",UnicodeSource.translate("\\"+"u0061").text);
        assertEquals("\\\\a",UnicodeSource.translate("\\\\\\"+"u0061").text);
        assertThrows(IllegalArgumentException.class,()->UnicodeSource.translate("\\"+"uBAD"));
        assertEquals("\\u005a",UnicodeSource.translate("\\"+"u005cu005a").text);
        assertEquals("\\\\",UnicodeSource.translate("\\"+"u005c\\"+"u005c").text);
    }
    @Test void ordinaryEscapedBackslashesKeepExactOriginalTypeSpans(){
        String text="class C {\n void f(){String p=\"\\\\R\\\\D\\\\S\\\\d\\\\w\";throw new IllegalArgumentException();\n }\n}";
        var request=TestInputs.request(text);var result=assertDoesNotThrow(()->new JavaParserFrontend().analyze(request));
        var source=new OriginalSource(request.sources().getFirst().document().identity(),text);
        var type=result.types().stream().filter(t->t.type().spelling().equals("IllegalArgumentException")).findFirst().orElseThrow();
        assertEquals("IllegalArgumentException",source.slice(type.span()));
    }
    @Test void sharedFieldTypeStillKeepsBothDistinctOwners(){
        var result=new JavaParserFrontend().analyze(TestInputs.request("class C { String left,right; }"));
        var facts=result.types().stream().filter(t->t.role().value().equals("java.field-type")).toList();
        assertEquals(2,facts.size());assertNotEquals(facts.get(0).owner(),facts.get(1).owner());
    }
    @Test void sharedLocalDeclarationTypeProducesOneFactWithoutLosingObservationLedger(){
        var request=TestInputs.request("class C { void f(){ String left=\"\", right=\"\"; int x=0,y=1; } }");
        var frontend=new JavaParserFrontend();var result=assertDoesNotThrow(()->frontend.analyze(request));
        assertEquals(1,result.types().stream().filter(t->t.type().spelling().equals("String")).count());
        assertEquals(1,result.types().stream().filter(t->t.type().spelling().equals("int")).count());
        assertEquals(result.occurrences().size(),result.observations().stream().filter(o->o.mappedOccurrence().isPresent()).count());
        assertEquals(CanonicalJson.write(result),CanonicalJson.write(frontend.analyze(request)));
    }
}
