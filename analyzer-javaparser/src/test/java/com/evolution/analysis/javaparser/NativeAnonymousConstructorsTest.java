package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.semantic.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeAnonymousConstructorsTest {
    @Test void anonymousGenericInterfaceConstructorsHaveSeparateProjectOwnersAndDerivedEvidence(){
        var request=TestInputs.request("""
                interface F<T> { T get(); }
                class C {
                  F<String> a(){ return new F<String>() { public String get(){return "a";} }; }
                  F<Integer> b(){ return new F<Integer>() { public Integer get(){return 1;} }; }
                  F<Long> c(){ return new F<Long>() { public Long get(){return 1L;} }; }
                }
                """);
        var result=new JavaParserFrontend().analyze(request);
        var calls=result.occurrences().stream().filter(o->o.relationship().kind().value().equals("java.constructor-calls")).toList();
        assertEquals(3,calls.size());assertTrue(calls.stream().allMatch(o->o.status()==SemanticStatus.RESOLVED),()->calls.stream().map(o->o.diagnostics().toString()).toList().toString());
        var targets=new HashSet<Object>();
        for(var call:calls){
            var id=((RelationshipTarget.Resolved)call.relationship().target()).target();targets.add(id);
            var declaration=result.declarations().stream().filter(d->d.entity().identity().equals(id)).findFirst().orElseThrow();
            assertEquals(EntityOrigin.PROJECT,declaration.entity().origin());assertEquals(EntityKind.CONSTRUCTOR,declaration.entity().kind());
            assertTrue(declaration.entity().declaration().isEmpty());assertEquals(DerivationKind.DERIVED,declaration.derivation().kind());
            assertTrue(declaration.entity().canonicalName().contains("\"anonymous\""));assertFalse(declaration.derivation().inputIdentities().isEmpty());
        }
        assertEquals(3,targets.size());
    }
    @Test void unavailableInterfaceAndIllegalArgumentsCannotProduceAnAnonymousConstructor(){
        for(String expression:List.of("new Missing() {}","new Runnable(1) { public void run(){} }")){
            var result=new JavaParserFrontend().analyze(TestInputs.request("class C { Object f(){ return "+expression+"; } }"));
            assertTrue(result.observations().stream().filter(o->o.category().value().equals("java.constructor-calls")).noneMatch(o->o.attribution()==SemanticStatus.RESOLVED));
        }
    }
    @Test void anonymousClassConstructionTargetsItsOwnConstructorRatherThanTheSuperclass(){
        var result=new JavaParserFrontend().analyze(TestInputs.request("class C { Object f(){ return new Object() {}; } }"));
        var call=result.occurrences().stream().filter(o->o.relationship().kind().value().equals("java.constructor-calls")).findFirst().orElseThrow();
        var id=((RelationshipTarget.Resolved)call.relationship().target()).target();var target=result.declarations().stream().filter(d->d.entity().identity().equals(id)).findFirst().orElseThrow();
        assertEquals(EntityOrigin.PROJECT,target.entity().origin());assertEquals(DerivationKind.DERIVED,target.derivation().kind());assertTrue(target.entity().canonicalName().contains("\"anonymous\""));
    }
}
