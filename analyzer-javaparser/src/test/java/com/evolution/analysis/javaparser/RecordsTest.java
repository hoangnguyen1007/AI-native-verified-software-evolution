package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.frontend.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecordsTest {
    @Test void explicitRecordMembersReplaceImplicitOnesWithoutDerivedBodyFacts() {
        var result=new JavaParserFrontend().analyze(TestInputs.request("record R(String value) { R(String value){this.value=value;} public String value(){return \"custom\";} }"));
        var declarations=result.declarations();
        var accessor=declarations.stream().filter(d -> d.entity().canonicalName().equals("java:v1:[\"method\",[\"type\",[],\"R\"],\"value\",[]]")).findFirst().orElseThrow();
        assertEquals(DerivationKind.DIRECT,accessor.derivation().kind());
        assertTrue(result.derivedRelationships().stream().noneMatch(d -> d.relationship().source().equals(accessor.entity().identity())));
        assertEquals(1,TypeRelationshipsTest.edges(result,"writes-field").size());
        assertTrue(result.derivedRelationships().stream().noneMatch(d -> Set.of("java.has-parameter","java.writes-field").contains(d.relationship().kind().value())));
    }
    @Test void generatedRecordObjectMethodsBelongToTheRecord() {
        var result=new JavaParserFrontend().analyze(TestInputs.request("record R(int n){} class C { String f(R r){r.hashCode();r.equals(r);return r.toString();} }"));
        var calls=TypeRelationshipsTest.edges(result,"calls");
        assertEquals(3,calls.size());
        var entities=TypeRelationshipsTest.entities(result);
        for (var call:calls) {
            assertEquals(SemanticStatus.RESOLVED,call.status());
            var target=entities.get(((RelationshipTarget.Resolved)call.relationship().target()).target());
            assertEquals(EntityOrigin.PROJECT,target.origin());
            assertTrue(target.canonicalName().contains("[\"type\",[],\"R\"]"));
            assertTrue(target.declaration().isEmpty());
        }
    }
    @Test void componentsCompactConstructorAndImplicitAccessorsKeepSeparateIdentities() {
        String source = "record R(String value) { R { value.trim(); } int size(){return this.value.length();} } "
                + "class C { String f(){return new R(\"x\").value();} }";
        var result = new JavaParserFrontend().analyze(TestInputs.request(source));
        var calls = TypeRelationshipsTest.edges(result,"calls");
        assertEquals(3,calls.size());
        assertTrue(calls.stream().allMatch(o -> o.status()==SemanticStatus.RESOLVED),calls.toString());
        assertEquals(1,TypeRelationshipsTest.edges(result,"constructor-calls").size());
        assertEquals(1,result.declarations().stream().filter(d -> d.entity().kind()==EntityKind.RECORD_COMPONENT).count());
        var read = TypeRelationshipsTest.edges(result,"reads-field");
        assertEquals(1,read.size()); // compact constructor value is a parameter, not a field read.
        var entities = TypeRelationshipsTest.entities(result);
        var field = entities.get(((RelationshipTarget.Resolved)read.getFirst().relationship().target()).target());
        assertEquals(EntityKind.FIELD,field.kind()); assertEquals(EntityOrigin.PROJECT,field.origin());
        assertTrue(field.declaration().isEmpty());
        var accessor = result.declarations().stream().filter(d -> d.entity().canonicalName().equals("java:v1:[\"method\",[\"type\",[],\"R\"],\"value\",[]]")).findFirst().orElseThrow();
        assertEquals(DerivationKind.DERIVED,accessor.derivation().kind());
        assertTrue(accessor.entity().declaration().isEmpty());
        assertFalse(accessor.derivation().inputIdentities().isEmpty());
        assertTrue(result.derivedRelationships().stream().anyMatch(d -> d.relationship().source().equals(accessor.entity().identity()) && d.relationship().kind().value().equals("java.reads-field")));
        assertTrue(result.derivedRelationships().stream().anyMatch(d -> d.relationship().source().equals(field.identity()) && d.relationship().kind().value().equals("java.field-type")));
    }
    @Test void enumConstantBodyHasAnAnonymousTypeOwnerAndDerivedConstruction() {
        var result = new JavaParserFrontend().analyze(TestInputs.request("enum E { A { void f(){hit();} }; static void hit(){} abstract void f(); }"));
        var calls=TypeRelationshipsTest.edges(result,"calls");
        assertEquals(1,calls.size()); assertEquals(SemanticStatus.RESOLVED,calls.getFirst().status());
        var entities=TypeRelationshipsTest.entities(result);
        assertTrue(entities.get(calls.getFirst().relationship().source()).canonicalName().contains("[\"local-type\",[\"field\""));
        assertEquals(0,TypeRelationshipsTest.edges(result,"constructor-calls").size());
        assertTrue(result.derivedRelationships().stream().anyMatch(d -> d.relationship().kind().value().equals("java.constructor-calls")));
    }
    @Test void defaultAndCanonicalConstructorsResolveWithoutInventedSourceDeclarations() {
        var result = new JavaParserFrontend().analyze(TestInputs.request("record R(int n) {} class C { Object f(){new C(); return new R(1);} }"));
        var calls = TypeRelationshipsTest.edges(result,"constructor-calls");
        assertEquals(2,calls.size());
        assertTrue(calls.stream().allMatch(o -> o.status()==SemanticStatus.RESOLVED),calls.toString());
        var entities = TypeRelationshipsTest.entities(result);
        assertTrue(calls.stream().allMatch(o -> entities.get(((RelationshipTarget.Resolved)o.relationship().target()).target()).declaration().isEmpty()));
    }
}
