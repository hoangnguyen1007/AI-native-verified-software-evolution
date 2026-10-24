package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.frontend.FrontendResult;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExpressionTypeUsesTest {
    @Test void staticCallQualifiersRetainNamedTypeLeavesAndTheirExactOrigins(){
        String source="class C { static void f(){ java.lang.Integer.valueOf(1); C.f(); } }";
        var request=TestInputs.request(source);var result=new JavaParserFrontend().analyze(request);var sites=TypeRelationshipsTest.edges(result,"type-uses");
        var original=new OriginalSource(request.sources().getFirst().document().identity(),source);
        assertEquals(Set.of("Integer","C"),new HashSet<>(sites.stream().map(site->original.slice(site.span())).toList()));
        assertEquals(2,sites.size());var entities=TypeRelationshipsTest.entities(result);
        assertEquals(Set.of(EntityOrigin.JDK,EntityOrigin.PROJECT),new HashSet<>(sites.stream().map(site->entities.get(((RelationshipTarget.Resolved)site.relationship().target()).target()).origin()).toList()));
    }
    @Test void aValueShadowingATypeDoesNotInventASecondTypeUse(){
        String source="class X { int n; } class C { X X; void f(){ X.n++; } }";
        var request=TestInputs.request(source);var result=new JavaParserFrontend().analyze(request);var sites=TypeRelationshipsTest.edges(result,"type-uses");
        assertEquals(1,sites.size());var original=new OriginalSource(request.sources().getFirst().document().identity(),source);
        assertEquals("X",original.slice(sites.getFirst().span()));assertEquals(source.indexOf("X X")+1,sites.getFirst().span().startColumn());
    }
    @Test void nestedTypeQualifiersPreserveUnicodeCoordinatesAndDoNotIndexImports(){
        String source="import java.util.List;\r\nclass O { static class I { static void f(){} } }\r\nclass C { void f(){ O."+"\\u"+"0049.f(); } }";
        var request=TestInputs.request(source);var result=new JavaParserFrontend().analyze(request);var sites=TypeRelationshipsTest.edges(result,"type-uses");
        var original=new OriginalSource(request.sources().getFirst().document().identity(),source);
        assertEquals(Set.of("O","\\u"+"0049"),new HashSet<>(sites.stream().map(site->original.slice(site.span())).toList()));
        assertEquals(2,sites.size());assertTrue(sites.stream().allMatch(site->site.span().startLine()==3));
        assertEquals(FrontendResult.State.COMPLETED,result.state());
    }
    @Test void aQualifiedFieldDoesNotBecomeItsSameSpelledNestedType(){
        String source="class C { static class N {} static int N; void f(){ int x=C.N; } }";
        var request=TestInputs.request(source);var result=new JavaParserFrontend().analyze(request);var sites=TypeRelationshipsTest.edges(result,"type-uses");
        var original=new OriginalSource(request.sources().getFirst().document().identity(),source);
        assertEquals(List.of("C"),sites.stream().map(site->original.slice(site.span())).toList());
        assertEquals(1,TypeRelationshipsTest.edges(result,"reads-field").size());
    }
}
