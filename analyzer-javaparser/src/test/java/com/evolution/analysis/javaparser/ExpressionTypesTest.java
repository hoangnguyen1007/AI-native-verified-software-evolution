package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.frontend.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExpressionTypesTest {
    @Test void illegalTypeVariableArgumentsRemainExplicitInsteadOfCrashingTheResult() {
        var result=new JavaParserFrontend().analyze(TestInputs.request("class C<T> { T<String> bad; String ok; }"));
        assertEquals(FrontendResult.State.PARTIAL,result.state());
        assertTrue(result.types().stream().anyMatch(t -> t.type().spelling().equals("T<String>") && t.type().status()==SemanticStatus.ERROR));
    }
    @Test void expressionContextsRetainNamedLeavesWithoutDuplicatingSignaturesOrPackages() {
        String source = "class C { C(){} <T> void consume(){} void f(Object input) { java.util.List<String> local=null; Object made=new C(); String cast=(String)input; boolean ok=input instanceof String s; Class<?> literal=String.class; this.<Integer>consume(); } }";
        var request = TestInputs.request(source);
        var result = new JavaParserFrontend().analyze(request);
        var uses = TypeRelationshipsTest.edges(result,"type-uses");
        assertEquals(11,uses.size());
        var original = new OriginalSource(request.sources().getFirst().document().identity(),source);
        var names = uses.stream().map(o -> original.slice(o.span())).toList();
        assertEquals(5,Collections.frequency(names,"String"));
        assertEquals(2,Collections.frequency(names,"Object"));
        assertTrue(names.containsAll(List.of("List","C","Class","Integer")));
        assertEquals(1,TypeRelationshipsTest.edges(result,"type-argument").stream().filter(o -> original.slice(o.span()).equals("Integer")).count());
    }
    @Test void intersectionUnionArrayAndLocalTypeFailuresRetainStructureAndEvidence() {
        var result = new JavaParserFrontend().analyze(TestInputs.request("class C { void f(Object x){ Object y=(java.io.Serializable & Cloneable)x; String[][] a=new String[1][2]; Missing absent=null; try{throw new java.io.IOException();}catch(java.io.IOException | IllegalStateException e){} } }"));
        assertTrue(result.types().stream().anyMatch(t -> t.type().kind() == JavaType.Kind.INTERSECTION && t.type().components().size() == 2));
        assertTrue(result.types().stream().anyMatch(t -> t.type().kind() == JavaType.Kind.UNION && t.type().components().size() == 2));
        assertTrue(result.observations().stream().anyMatch(o -> o.category().value().equals("java.type-uses") && o.reference().equals("Missing") && o.attribution() == SemanticStatus.UNRESOLVED));
    }
}
