package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.frontend.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GenericRecoveryTest {
    @Test void lowerWildcardCannotSupplyItsBoundMembersInAnExactClasspathEither() {
        for(boolean recovery:List.of(false,true)) {
            var request=TestInputs.request("import java.util.List; class Pet { void act(){} } class C { void run(List<? super Pet> pets) { pets.get(0).act(); Runnable r=pets.get(0)::act; } }");
            var result=new JavaParserFrontend().analyze(recovery?LombokIngestionTest.recovery(request):request);
            assertTrue(result.observations().stream().filter(o->o.reference().endsWith(".act()")||o.reference().endsWith("::act")).noneMatch(o->o.attribution()==SemanticStatus.RESOLVED));
        }
    }
    @Test void wildcardReceiverFieldReadsRespectUpperRatherThanLowerBounds() {
        for(boolean recovery:List.of(false,true)) {
            var request=TestInputs.request("import java.util.List; class Pet { int count; } class C { int good(List<? extends Pet> pets) {return ++pets.get(0).count;} int bad(List<? super Pet> pets) {return ++pets.get(0).count;} }");
            var result=new JavaParserFrontend().analyze(recovery?LombokIngestionTest.recovery(request):request);
            for(String category:List.of("java.reads-field","java.writes-field")) {
                var accesses=result.observations().stream().filter(o->o.category().value().equals(category)).toList();
                assertEquals(2,accesses.size());assertEquals(1,accesses.stream().filter(o->o.attribution()==SemanticStatus.RESOLVED).count());
            }
        }
    }
    @Test void boundedVariablesWildcardReadsAndObjectMembersRetainTheirActualDeclarations() {
        String text="""
                import java.util.List;
                class Pet { void act() {} }
                class C<T extends Pet,U> {
                  void run(T pet,U any,List<? extends Pet> pets,List<? super Pet> lower) {
                    pet.act(); any.toString(); pets.get(0).act(); lower.get(0).toString();
                  }
                }
                """;
        var result=analyze(text);
        var calls=result.observations().stream().filter(o->o.category().value().equals("java.calls")).toList();
        assertEquals(6,calls.size());assertTrue(calls.stream().allMatch(o->o.attribution()==SemanticStatus.RESOLVED),()->summary(result,"calls"));
        var targets=TypeRelationshipsTest.edges(result,"calls").stream().map(o->((RelationshipTarget.Resolved)o.relationship().target()).target()).toList();
        var act=result.declarations().stream().filter(d->d.entity().canonicalName().equals(JavaSymbolName.method(JavaSymbolName.topLevelType("","Pet"),"act",List.of()).canonicalName())).findFirst().orElseThrow().entity().identity();
        assertEquals(2,Collections.frequency(targets,act));
        var invalid=analyze("import java.util.List; class Pet { void act(){} } class C { void run(List<? super Pet> pets) { pets.get(0).act(); } }");
        assertTrue(invalid.observations().stream().filter(o->o.reference().endsWith(".act()")).noneMatch(o->o.attribution()==SemanticStatus.RESOLVED));
    }

    @Test void everyIntersectionBoundMustBeKnownAndRecursiveBoundsTerminate() {
        var valid=analyze("interface Named { void act(); } class C<T extends Number & Named,U extends Comparable<U>> { void run(T t,U u) { t.act(); u.compareTo(u); } }");
        assertEquals(2,valid.observations().stream().filter(o->o.category().value().equals("java.calls")&&o.attribution()==SemanticStatus.RESOLVED).count());
        var missing=analyze("class Pet { void act() {} } class C<T extends Pet & Missing> { void run(T t) { t.act(); } }");
        assertTrue(missing.observations().stream().filter(o->o.category().value().equals("java.calls")).noneMatch(o->o.attribution()==SemanticStatus.RESOLVED));
    }

    @Test void wildcardSamReferencesNeedCompatibleArgumentsReturnsAndThrows() {
        String text="""
                import java.util.*; import java.util.function.*;
                class Pet { void setType(String s) {} String id() { return ""; } }
                class C { void run(Optional<String> type,Pet pet,List<Pet> pets) {
                  type.ifPresent(pet::setType); pets.stream().map(Pet::id);
                  Consumer<? super String> a=pet::setType;
                  Function<? super Pet,? extends String> b=Pet::id;
                } }
                """;
        var result=analyze(text);var refs=TypeRelationshipsTest.edges(result,"method-references");
        assertEquals(4,refs.size());assertTrue(refs.stream().allMatch(o->o.status()==SemanticStatus.RESOLVED),()->summary(result,"method-references"));
        var invalid=analyze("class Pet { void setType(String s) {} String id() {return \"\";} void checked(String s) throws java.io.IOException {} } class C { void run(Pet p) { java.util.function.Consumer<? super Integer> a=p::setType; java.util.function.Function<? super Pet,? extends Integer> b=Pet::id; java.util.function.Consumer<? super String> c=p::checked; } }");
        assertEquals(3,TypeRelationshipsTest.edges(invalid,"method-references").size());
        assertTrue(TypeRelationshipsTest.edges(invalid,"method-references").stream().noneMatch(o->o.status()==SemanticStatus.RESOLVED));
    }

    private FrontendResult analyze(String source) {
        return new JavaParserFrontend().analyze(LombokIngestionTest.recovery(TestInputs.request(source)));
    }
    private String summary(FrontendResult result,String category) {
        return result.observations().stream().filter(o->o.category().value().equals("java."+category))
                .map(o->o.reference()+":"+o.attribution()+":"+o.diagnostics().stream().map(Diagnostic::code).toList()).toList().toString();
    }
}
