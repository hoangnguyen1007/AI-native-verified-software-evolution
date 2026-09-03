package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.frontend.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MethodReferencesTest {
    private static List<RelationshipOccurrence> references(FrontendResult result) { return TypeRelationshipsTest.edges(result,"method-references"); }
    @Test void staticBoundUnboundAndOverloadedReferencesSelectDeclarationsWithoutCallingThem() {
        String source = "import java.util.function.*; class C { String text; static int pick(String s){return 1;} static int pick(Integer n){return 2;} "
                + "void f(){ Function<String,Integer> a=C::pick; Supplier<String> b=text::trim; Function<String,String> c=String::trim; Runnable r=this::run; } void run(){} }";
        var request = TestInputs.request(source);
        var result = new JavaParserFrontend().analyze(request);
        var references = TypeRelationshipsTest.edges(result,"method-references");
        assertEquals(4,references.size());
        assertTrue(references.stream().allMatch(o -> o.status() == SemanticStatus.RESOLVED),() -> CanonicalJson.write(result.observations().stream().filter(o -> o.category().value().equals("java.method-references")).toList()));
        var entities = TypeRelationshipsTest.entities(result);
        var names = references.stream().map(o -> entities.get(((RelationshipTarget.Resolved)o.relationship().target()).target()).canonicalName()).toList();
        assertEquals(2,Collections.frequency(names,JavaSymbolName.method(JavaSymbolName.topLevelType("java.lang","String"),"trim",List.of()).canonicalName()));
        assertTrue(names.contains(JavaSymbolName.method(JavaSymbolName.topLevelType("","C"),"pick",List.of(ErasedType.declared(JavaSymbolName.topLevelType("java.lang","String")))).canonicalName()));
        assertTrue(TypeRelationshipsTest.edges(result,"calls").isEmpty());
        assertEquals(1,TypeRelationshipsTest.edges(result,"reads-field").size(),"bound receiver is evaluated when reference is created");
        var original = new OriginalSource(request.sources().getFirst().document().identity(),source);
        assertEquals(Set.of("C::pick","text::trim","String::trim","this::run"),new HashSet<>(references.stream().map(o -> original.slice(o.span())).toList()));
        assertEquals(CanonicalJson.write(result),CanonicalJson.write(new JavaParserFrontend().analyze(request)));
    }
    @Test void genericReceiverAndMethodReferencesApplyFunctionalAndReceiverSubstitutions() {
        var result=new JavaParserFrontend().analyze(TestInputs.request("class C { java.util.List<String> values=java.util.List.of(); void f(){ java.util.function.Function<Integer,String> a=values::get; java.util.function.Function<String,String> b=java.util.Objects::requireNonNull; } }"));
        assertEquals(2,references(result).size());
        assertTrue(references(result).stream().allMatch(o -> o.status()==SemanticStatus.RESOLVED),references(result).toString());
    }
    @Test void checkedExceptionsMustBeCompatibleWithTheFunctionalMethod() {
        var invalid=new JavaParserFrontend().analyze(TestInputs.request("class C { void checked() throws java.io.IOException {} void f(){ Runnable r=this::checked; } }"));
        assertEquals(SemanticStatus.UNRESOLVED,references(invalid).getFirst().status());
        var valid=new JavaParserFrontend().analyze(TestInputs.request("interface Work { void run() throws java.io.IOException; } class C { void checked() throws java.io.IOException {} void f(){ Work r=this::checked; } }"));
        assertEquals(SemanticStatus.RESOLVED,references(valid).getFirst().status());
    }
    @Test void unboundGenericReceiverResolvesAndIncompatibleGenericReturnIsWithheld() {
        var request=TestInputs.request("class C { void f(){ java.util.function.BiFunction<java.util.List<String>,Integer,String> a=java.util.List<String>::get; java.util.function.Function<Integer,String> b=java.util.Objects::requireNonNull; } }");
        var result=new JavaParserFrontend().analyze(request);
        var original=new OriginalSource(request.sources().getFirst().document().identity(),request.sources().getFirst().text());
        assertEquals(2,references(result).size());
        for (var reference:references(result)) assertEquals(original.slice(reference.span()).contains("::get") ? SemanticStatus.RESOLVED : SemanticStatus.UNRESOLVED,reference.status());
    }
    @Test void constructorReferencesSelectOverloadAndArrayConstructorsStayExplicitlyUnsupported() {
        var result = new JavaParserFrontend().analyze(TestInputs.request("import java.util.function.*; class C { C(){} C(String s){} void f(){Supplier<C> a=C::new; Function<String,C> b=C::new; IntFunction<String[]> c=String[]::new;} }"));
        var references = TypeRelationshipsTest.edges(result,"method-references");
        assertEquals(3,references.size());
        assertEquals(2,references.stream().filter(o -> o.status() == SemanticStatus.RESOLVED).count());
        assertEquals(1,references.stream().filter(o -> o.status() == SemanticStatus.UNSUPPORTED).count());
        var entities = TypeRelationshipsTest.entities(result);
        assertTrue(references.stream().filter(o -> o.status() == SemanticStatus.RESOLVED).allMatch(o -> entities.get(((RelationshipTarget.Resolved)o.relationship().target()).target()).kind() == EntityKind.CONSTRUCTOR));
        assertEquals(2,references.stream().filter(o -> o.status() == SemanticStatus.RESOLVED).map(o -> o.relationship().target()).distinct().count());
        assertTrue(TypeRelationshipsTest.edges(result,"constructor-calls").isEmpty());
    }
    @Test void missingMethodAndWrongFunctionalArityDoNotBecomeResolved() {
        var result = new JavaParserFrontend().analyze(TestInputs.request("import java.util.function.*; class C { void run(){} void f(){Runnable a=this::missing; Consumer<String> b=this::run;} }"));
        assertEquals(2,result.observations().stream().filter(o -> o.category().value().equals("java.method-references")).count());
        assertTrue(TypeRelationshipsTest.edges(result,"method-references").stream().noneMatch(o -> o.status() == SemanticStatus.RESOLVED));
    }
}
