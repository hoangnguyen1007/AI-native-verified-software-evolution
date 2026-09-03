package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.frontend.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AnnotationsTest {
    @Test void declarationAndTypeSyntaxAnnotationsHaveRealOwnersOriginsAndWholeSpans() {
        String source = "import java.lang.annotation.*; @Target({ElementType.TYPE_USE,ElementType.FIELD,ElementType.TYPE,ElementType.PARAMETER}) @interface Mark {} "
                + "@Mark class C { @Mark String a,b; void f(@Mark String p){ Object x=new @Mark C(); } }";
        var request = TestInputs.request(source);
        var result = new JavaParserFrontend().analyze(request);
        var annotations = TypeRelationshipsTest.edges(result,"annotated-with");
        assertEquals(6,annotations.size()); // @Target, class, two fields, parameter and constructed type.
        var entities = TypeRelationshipsTest.entities(result);
        var original = new OriginalSource(request.sources().getFirst().document().identity(),source);
        assertTrue(annotations.stream().allMatch(o -> o.status() == SemanticStatus.RESOLVED && original.slice(o.span()).startsWith("@")), () -> annotations.toString());
        assertEquals(2,annotations.stream().filter(o -> entities.get(o.relationship().source()).kind() == EntityKind.FIELD).count());
        assertTrue(annotations.stream().anyMatch(o -> entities.get(((RelationshipTarget.Resolved)o.relationship().target()).target()).origin() == EntityOrigin.JDK));
    }
    @Test void unknownAnnotationRemainsAnUnresolvedObservation() {
        var result = new JavaParserFrontend().analyze(TestInputs.request("@Missing class C {}"));
        assertEquals(1,TypeRelationshipsTest.edges(result,"annotated-with").size());
        assertEquals(SemanticStatus.UNRESOLVED,TypeRelationshipsTest.edges(result,"annotated-with").getFirst().status());
    }
    @Test void nestedAnnotationValueIsNotASecondDirectAnnotationOfTheClass() {
        var result=new JavaParserFrontend().analyze(TestInputs.request("@interface Inner {} @interface Outer { Inner value(); } @Outer(@Inner) class C {}"));
        assertEquals(1,TypeRelationshipsTest.edges(result,"annotated-with").size());
        assertEquals(2,result.annotations().size());
        assertEquals(1,result.annotations().stream().filter(a -> a.site().equals("annotation-value")).count());
        assertEquals(3,TypeRelationshipsTest.edges(result,"type-uses").stream().filter(o -> {
            var target=((RelationshipTarget.Resolved)o.relationship().target()).target();
            return TypeRelationshipsTest.entities(result).get(target).canonicalName().contains("\"Outer\"") || TypeRelationshipsTest.entities(result).get(target).canonicalName().contains("\"Inner\"");
        }).count());
    }
    @Test void defaultAnnotationValueDoesNotAnnotateTheMember() {
        var result=new JavaParserFrontend().analyze(TestInputs.request("@interface Inner {} @interface Outer { Inner value() default @Inner; }"));
        assertTrue(TypeRelationshipsTest.edges(result,"annotated-with").isEmpty());
        assertEquals("annotation-value",result.annotations().getFirst().site());
        assertEquals(2,TypeRelationshipsTest.edges(result,"type-uses").size());
    }
}
