package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.frontend.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SemanticBoundariesTest {
    @Test void fullerCatchDeclarationSpansPreserveTheExistingM1IdentityAnchor(){
        String source="class C { void f(){ try{} catch(java.io.IOException failure){} } }";
        var request=TestInputs.request(source);var result=new JavaParserFrontend().analyze(request);
        var owner=JavaSymbolName.method(JavaSymbolName.topLevelType("","C"),"f",List.of());
        var expected=JavaSymbolName.catchParameter(owner,request.sources().getFirst().document().identity(),source.indexOf("IOException failure"),"failure");
        var parameter=result.declarations().stream().map(d->d.entity()).filter(e->e.kind()==EntityKind.PARAMETER).findFirst().orElseThrow();
        assertEquals(expected.canonicalName(),parameter.canonicalName());
        assertEquals("java.io.IOException failure",new OriginalSource(request.sources().getFirst().document().identity(),source).slice(parameter.declaration().orElseThrow()));
    }
    private static FrontendResult analyze(String source) { return new JavaParserFrontend().analyze(TestInputs.request(source)); }
    private static List<com.evolution.analysis.contract.semantic.RelationshipOccurrence> calls(FrontendResult result) {
        return result.occurrences().stream().filter(o -> o.relationship().kind().value().equals("java.calls")).toList();
    }
    @Test void javaUnicodeIdentifierEqualitySurvivesRealAttribution() {
        var result = analyze("class C { void caf\u00e9(){} void cafe\u0301(){} void ignored(){} void run(){caf\u00e9();cafe\u0301();ig\u200cnored();" + "\\" + "u0069gnored();} }");
        assertEquals(4,calls(result).size());
        assertTrue(calls(result).stream().allMatch(o -> o.status() == SemanticStatus.RESOLVED), CanonicalJson.write(result));
        assertEquals(3,calls(result).stream().map(o -> o.relationship().target()).distinct().count());
    }
    @Test void genericBoundAndVarargsUseDeclarationErasure() {
        var result = analyze("class C { <T extends Number> T pick(T t){return t;} void many(String... s){} void run(){pick(Integer.valueOf(1));many(\"a\",\"b\");} }");
        assertTrue(calls(result).stream().allMatch(o -> o.status() == SemanticStatus.RESOLVED),CanonicalJson.write(result));
        var names = result.declarations().stream().map(d -> d.entity().canonicalName()).toList();
        assertTrue(names.contains("java:v1:[\"method\",[\"type\",[],\"C\"],\"pick\",[[\"declared\",[\"type\",[\"java\",\"lang\"],\"Number\"]]]]"));
        assertTrue(names.contains("java:v1:[\"method\",[\"type\",[],\"C\"],\"many\",[[\"array\",[\"declared\",[\"type\",[\"java\",\"lang\"],\"String\"]],1]]]"));
    }
    @Test void fieldInitializersAndLambdasHaveSeparateExecutionOwners() {
        var result = analyze("class C { static String hit(){return \"x\";} String s=hit(); Runnable r=()->hit(); static {hit();} {hit();} }");
        assertEquals(4,calls(result).size());
        var entities = new HashMap<com.evolution.analysis.contract.identity.EntityIdentity,Entity>(); result.declarations().forEach(d -> entities.put(d.entity().identity(),d.entity()));
        var owners = calls(result).stream().map(c -> entities.get(c.relationship().source())).toList();
        assertEquals(3,owners.stream().filter(e -> e.kind() == EntityKind.INITIALIZER).count());
        var lambda = owners.stream().filter(e -> e.kind() == EntityKind.LAMBDA).findFirst().orElseThrow();
        assertTrue(lambda.canonicalName().contains("[\"initializer\",[\"field\""),lambda.canonicalName());
    }
    @Test void recordAttributionUsesCompactConstructorAsCaller() {
        var result = analyze("record R(String s) { R { s.trim(); } }");
        assertEquals(1,calls(result).size());
        assertEquals(SemanticStatus.RESOLVED,calls(result).getFirst().status());
        assertEquals(EntityKind.CONSTRUCTOR,TypeRelationshipsTest.entities(result).get(calls(result).getFirst().relationship().source()).kind());
    }
    @Test void enumConstantBodyCannotMasqueradeAsEnclosingEnumMethod() {
        var result = analyze("enum E { A { void f(){hit();} }; static void hit(){} abstract void f(); }");
        assertEquals(1,calls(result).size());
        var owner=TypeRelationshipsTest.entities(result).get(calls(result).getFirst().relationship().source());
        assertTrue(owner.canonicalName().contains("\"local-type\""));
    }
    @Test void invalidOverloadAmbiguityNeverBecomesAResolvedTarget() {
        var result = analyze("class C { void pick(String s){} void pick(Integer i){} void run(){pick(null);} }");
        assertTrue(calls(result).stream().noneMatch(o -> o.status() == SemanticStatus.RESOLVED),CanonicalJson.write(result));
        assertTrue(result.observations().stream().anyMatch(o -> o.category().value().equals("java.calls") && o.attribution() == SemanticStatus.AMBIGUOUS),CanonicalJson.write(result));
    }
    @Test void duplicateDeclarationsAreWithheldWithoutMergingOrCrashing() {
        var result = analyze("class C { void f(){} void f(){} void run(){f();} }");
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("java.duplicate-declaration")),CanonicalJson.write(result));
        assertTrue(calls(result).stream().noneMatch(o -> o.status() == SemanticStatus.RESOLVED));
        assertTrue(result.declarations().stream().noneMatch(d -> d.entity().canonicalName().contains("\"f\"")));
    }
    @Test void missingDeclarationTypesRemainPartialWithoutDestroyingKnownIdentity() {
        var result = analyze("class C { Missing field; Missing build(){return null;} }");
        assertEquals(FrontendResult.State.PARTIAL,result.state());
        assertEquals(2,result.declarations().stream().filter(d -> Set.of(EntityKind.FIELD,EntityKind.METHOD).contains(d.entity().kind())).count());
        assertTrue(result.declarations().stream().filter(d -> Set.of(EntityKind.FIELD,EntityKind.METHOD).contains(d.entity().kind())).allMatch(d -> d.status() == SemanticStatus.PARTIAL));
    }
    @Test void inheritedCallSelectsActualDeclaringTypeAndDefaultConstructorIsDerived() {
        var result = analyze("class Base { String hit(){return \"x\";} } class Child extends Base {} class C { String run(Child c){return c.hit();} C make(){return new C();} }");
        var call = calls(result).getFirst();
        var id = ((RelationshipTarget.Resolved)call.relationship().target()).target();
        assertEquals("java:v1:[\"method\",[\"type\",[],\"Base\"],\"hit\",[]]",result.declarations().stream().map(DeclarationRecord::entity).filter(e -> e.identity().equals(id)).findFirst().orElseThrow().canonicalName());
        assertTrue(result.observations().stream().anyMatch(o -> o.category().value().equals("java.constructor-calls") && o.attribution() == SemanticStatus.RESOLVED));
    }
}
