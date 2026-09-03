package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.frontend.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TypeRelationshipsTest {
    @Test void emitsParameterReturnAndFieldTypesWithRealOwnersAndGenericLeaves() {
        String source = "import java.util.List; class C<T> { List<? extends T[]> values; int count; "
                + "String[] run(List<String> input, int n){return null;} void stop(){} }";
        var request = TestInputs.request(source);
        var result = new JavaParserFrontend().analyze(request);
        assertEquals(2,edges(result,"has-parameter").size());
        assertEquals(2,edges(result,"parameter-type").size());
        assertEquals(1,edges(result,"returns").size());
        assertEquals(2,edges(result,"field-type").size());
        assertEquals(5,edges(result,"type-uses").size());
        var entities = entities(result);
        assertTrue(edges(result,"parameter-type").stream().allMatch(o -> entities.get(o.relationship().source()).kind() == EntityKind.PARAMETER));
        assertTrue(edges(result,"field-type").stream().allMatch(o -> entities.get(o.relationship().source()).kind() == EntityKind.FIELD));
        var original = new OriginalSource(request.sources().getFirst().document().identity(),source);
        assertEquals(Set.of("List","String"),new HashSet<>(edges(result,"parameter-type").stream().map(o -> original.slice(o.span())).toList()));
        assertTrue(edges(result,"field-type").stream().map(o -> entities.get(((RelationshipTarget.Resolved)o.relationship().target()).target())).anyMatch(e -> e.kind() == EntityKind.TYPE_PARAMETER));
        var field = result.types().stream().filter(t -> t.type().spelling().equals("List<? extends T[]>")).findFirst().orElseThrow().type();
        assertEquals(JavaType.Kind.DECLARED,field.kind());
        assertEquals(JavaType.Kind.EXTENDS_WILDCARD,field.components().getFirst().kind());
        assertEquals(JavaType.Kind.ARRAY,field.components().getFirst().components().getFirst().kind());
        assertEquals(JavaType.Kind.TYPE_VARIABLE,field.components().getFirst().components().getFirst().components().getFirst().kind());
        assertTrue(result.types().stream().anyMatch(t -> t.type().kind() == JavaType.Kind.VOID));
        assertTrue(result.types().stream().anyMatch(t -> t.type().kind() == JavaType.Kind.PRIMITIVE));
        assertEquals(CanonicalJson.write(result),CanonicalJson.write(new JavaParserFrontend().analyze(request)));
    }
    @Test void missingGenericArgumentDoesNotEraseItsKnownContainerOrInventTarget() {
        var result = new JavaParserFrontend().analyze(TestInputs.request("class C { java.util.List<Missing> field; Missing result(){return null;} }"));
        assertEquals(2,edges(result,"field-type").size());
        assertEquals(1,edges(result,"field-type").stream().filter(o -> o.status() == SemanticStatus.UNRESOLVED).count());
        assertEquals(1,edges(result,"field-type").stream().filter(o -> o.status() == SemanticStatus.RESOLVED).count());
        assertEquals(SemanticStatus.UNRESOLVED,edges(result,"returns").getFirst().status());
        assertEquals(FrontendResult.State.PARTIAL,result.state());
        assertTrue(result.declarations().stream().noneMatch(d -> d.entity().canonicalName().contains("\"Missing\"")));
        var field = result.types().stream().filter(t -> t.role().value().equals("java.field-type")).findFirst().orElseThrow().type();
        assertEquals(SemanticStatus.PARTIAL,field.status());
        assertEquals(JavaType.Kind.DECLARED,field.kind());
        assertEquals(JavaType.Kind.UNKNOWN,field.components().getFirst().kind());
        assertTrue(field.components().getFirst().target().isEmpty());
    }
    @Test void memberQualifiersAndGenericParentsKeepStructureWithoutFalseSupertypes() {
        var request = TestInputs.request("class Outer<T> { class Inner<U> {} } class Base<T> {} "
                + "class C extends Base<String> { Outer<String>.Inner<Integer> value; void f(String... values) {} }");
        var result = new JavaParserFrontend().analyze(request);
        var entities = entities(result);
        assertEquals(1,edges(result,"extends").size(),"type arguments are not superclasses");
        assertEquals("Base<String>",new OriginalSource(request.sources().getFirst().document().identity(),request.sources().getFirst().text()).slice(edges(result,"extends").getFirst().span()));
        assertEquals(JavaSymbolName.topLevelType("","Base").canonicalName(),entities.get(((RelationshipTarget.Resolved)edges(result,"extends").getFirst().relationship().target()).target()).canonicalName());
        var field = result.types().stream().filter(t -> t.role().value().equals("java.field-type")).findFirst().orElseThrow().type();
        assertEquals(SemanticStatus.RESOLVED,field.status());
        assertEquals("Integer",field.components().getFirst().spelling());
        assertEquals("String",field.qualifier().orElseThrow().components().getFirst().spelling());
        assertEquals(4,edges(result,"field-type").size());
        assertTrue(result.types().stream().anyMatch(t -> t.variadic() && t.type().spelling().equals("String")));
    }
    @Test void hierarchyBoundsAndThrowsUseTheSameTypeEvidence() {
        var result = new JavaParserFrontend().analyze(TestInputs.request("interface I {} sealed class B permits C {} final class C<T extends Number & Runnable> extends B implements I { void f() throws java.io.IOException {} }"));
        assertEquals(1,edges(result,"extends").size());
        assertEquals(1,edges(result,"implements").size());
        assertEquals(1,edges(result,"permits").size());
        assertEquals(2,edges(result,"type-parameter-bound").size());
        assertEquals(1,edges(result,"throws").size());
        assertTrue(edges(result,"type-parameter-bound").stream().allMatch(o -> entities(result).get(o.relationship().source()).kind() == EntityKind.TYPE_PARAMETER));
    }
    @Test void missingMemberTypeRetainsItsKnownGenericQualifier() {
        var result = new JavaParserFrontend().analyze(TestInputs.request("class Outer<T> {} class C { Outer<String>.Missing<Integer> field; }"));
        assertEquals(4,edges(result,"field-type").size());
        assertEquals(3,edges(result,"field-type").stream().filter(o -> o.status() == SemanticStatus.RESOLVED).count());
        var field = result.types().stream().filter(t -> t.role().value().equals("java.field-type")).findFirst().orElseThrow().type();
        assertEquals(JavaType.Kind.UNKNOWN,field.kind());
        assertEquals(SemanticStatus.UNRESOLVED,field.status());
        assertEquals(JavaType.Kind.DECLARED,field.qualifier().orElseThrow().kind());
        assertEquals("String",field.qualifier().orElseThrow().components().getFirst().spelling());
    }
    @Test void missingArgumentPreservesKnownErasedCallableAndParameterIdentity() {
        var result = new JavaParserFrontend().analyze(TestInputs.request("class C { void f(java.util.List<Missing>[] values) {} }"));
        var method = result.declarations().stream().filter(d -> d.entity().kind() == EntityKind.METHOD).findFirst().orElseThrow();
        assertEquals(JavaSymbolName.method(JavaSymbolName.topLevelType("","C"),"f",List.of(
                ErasedType.array(ErasedType.declared(JavaSymbolName.topLevelType("java.util","List")),1))).canonicalName(),method.entity().canonicalName());
        assertEquals(SemanticStatus.PARTIAL,method.status());
        assertEquals(1,edges(result,"has-parameter").size());
        assertEquals(2,edges(result,"parameter-type").size());
        assertEquals(1,edges(result,"parameter-type").stream().filter(o -> o.status() == SemanticStatus.UNRESOLVED).count());
        assertTrue(result.types().stream().allMatch(t -> t.owner().isPresent()));
    }
    static List<RelationshipOccurrence> edges(FrontendResult result,String kind) {
        return result.occurrences().stream().filter(o -> o.relationship().kind().value().equals("java."+kind)).toList();
    }
    static Map<com.evolution.analysis.contract.identity.EntityIdentity,Entity> entities(FrontendResult result) {
        var entities = new HashMap<com.evolution.analysis.contract.identity.EntityIdentity,Entity>();
        result.declarations().forEach(d -> entities.put(d.entity().identity(),d.entity())); return entities;
    }
}
