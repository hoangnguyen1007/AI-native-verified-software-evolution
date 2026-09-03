package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.frontend.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FieldAccessTest {
    @Test void exactFixtureCoversReadWriteModesReceiversInheritanceAndStaticImports() throws Exception {
        String source;
        try (var stream = Objects.requireNonNull(getClass().getResourceAsStream("/m2/fields/Accesses.java"))) {
            source = new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }
        var request = TestInputs.request(Map.of("fixture/fields/Accesses.java",source),List.of());
        var result = new JavaParserFrontend().analyze(request);
        var entities = TypeRelationshipsTest.entities(result);
        var original = new OriginalSource(request.sources().getFirst().document().identity(),source);
        // Source-authored labels: line | mode | complete access | declaring type.field.
        List<String> expected = List.of(
                "10|writes-field|this.own|fixture.fields.Accesses.own",
                "11|reads-field|this.own|fixture.fields.Accesses.own", "11|writes-field|this.own|fixture.fields.Accesses.own",
                "11|reads-field|arg.value|fixture.fields.Box.value",
                "12|reads-field|this.own|fixture.fields.Accesses.own", "12|writes-field|this.own|fixture.fields.Accesses.own",
                "13|reads-field|this.own|fixture.fields.Accesses.own", "13|writes-field|this.own|fixture.fields.Accesses.own",
                "14|writes-field|this.own|fixture.fields.Accesses.own",
                "15|writes-field|box.value|fixture.fields.Box.value", "15|reads-field|box|fixture.fields.Accesses.box", "15|reads-field|arg.value|fixture.fields.Box.value",
                "16|reads-field|box.next.value|fixture.fields.Box.value", "16|writes-field|box.next.value|fixture.fields.Box.value",
                "16|reads-field|box.next|fixture.fields.Box.next", "16|reads-field|box|fixture.fields.Accesses.box",
                "17|reads-field|box.numbers|fixture.fields.Box.numbers", "17|reads-field|box|fixture.fields.Accesses.box",
                "19|reads-field|this.own|fixture.fields.Accesses.own",
                "20|writes-field|inherited|fixture.fields.Base.inherited",
                "21|reads-field|super.inherited|fixture.fields.Base.inherited", "21|writes-field|super.inherited|fixture.fields.Base.inherited",
                "22|reads-field|MAX_VALUE|java.lang.Integer.MAX_VALUE",
                "23|reads-field|System.out|java.lang.System.out", "23|reads-field|java.lang.Integer.MAX_VALUE|java.lang.Integer.MAX_VALUE");
        var actual = accesses(result);
        assertEquals(expected.size(),actual.size(),() -> CanonicalJson.write(result.observations().stream().filter(o -> isFieldCategory(o.category().value())).toList()));
        for (var label : expected) {
            String[] parts = label.split("\\|");
            var matches = actual.stream().filter(o -> o.span().startLine() == Integer.parseInt(parts[0])
                    && o.relationship().kind().value().equals("java."+parts[1]) && original.slice(o.span()).equals(parts[2])).toList();
            assertEquals(1,matches.size(),label);
            var occurrence = matches.getFirst();
            assertEquals(SemanticStatus.RESOLVED,occurrence.status(),label);
            var target = entities.get(((RelationshipTarget.Resolved)occurrence.relationship().target()).target());
            assertEquals(fieldName(parts[3]),target.canonicalName(),label);
            assertEquals(EntityKind.FIELD,target.kind());
            assertEquals(parts[3].startsWith("java.") ? EntityOrigin.JDK : EntityOrigin.PROJECT,target.origin());
            var owner = entities.get(occurrence.relationship().source());
            assertEquals(EntityKind.METHOD,owner.kind());
            assertEquals(JavaSymbolName.method(JavaSymbolName.topLevelType("fixture.fields","Accesses"),"run",List.of(
                    ErasedType.declared(JavaSymbolName.topLevelType("fixture.fields","Box")),ErasedType.primitive("int"))).canonicalName(),owner.canonicalName());
            String line = source.lines().toList().get(Integer.parseInt(parts[0])-1);
            int start = line.indexOf(parts[2]);
            assertEquals(start+1,occurrence.span().startColumn(),label);
            assertEquals(start+parts[2].length()+1,occurrence.span().endColumn(),label);
        }
        assertEquals(25,result.observations().stream().filter(o -> isFieldCategory(o.category().value())).count(),"no package/type/local-name pseudo attempts");
        assertEquals(CanonicalJson.write(result),CanonicalJson.write(new JavaParserFrontend().analyze(request)));
        Path output = Path.of("target/m2-evidence/fields.json"); Files.createDirectories(output.getParent());
        Files.writeString(output,CanonicalJson.write(result),StandardCharsets.UTF_8);
        Files.writeString(output.resolveSibling("fields-manifest.json"),CanonicalJson.write(request.manifest()),StandardCharsets.UTF_8);
    }
    @Test void fieldsInInitializersConstructorsAndLambdasKeepTheirExecutionOwners() {
        var result = new JavaParserFrontend().analyze(TestInputs.request("class C { int value; int initial=value; Runnable r=()->value++; C(){value=2;} {value++;} }"));
        var entities = TypeRelationshipsTest.entities(result);
        assertEquals(6,accesses(result).size());
        var ownerKinds = accesses(result).stream().map(o -> entities.get(o.relationship().source()).kind()).toList();
        assertEquals(3,Collections.frequency(ownerKinds,EntityKind.INITIALIZER));
        assertEquals(2,Collections.frequency(ownerKinds,EntityKind.LAMBDA));
        assertEquals(1,Collections.frequency(ownerKinds,EntityKind.CONSTRUCTOR));
        assertTrue(accesses(result).stream().allMatch(o -> entities.get(((RelationshipTarget.Resolved)o.relationship().target()).target()).canonicalName().equals(fieldName("C.value"))));
    }
    @Test void unknownQualifiedFieldsAreExplicitAndUnknownBareNamesStayUnmapped() {
        var result = new JavaParserFrontend().analyze(TestInputs.request("class C { void f(){ this.missing += unknown; missing = 1; dep.Absent.VALUE++; } }"));
        assertEquals(4,accesses(result).size(),"two explicit compound accesses each have read and write");
        assertTrue(accesses(result).stream().allMatch(o -> o.status() == SemanticStatus.UNRESOLVED && o.relationship().target() instanceof RelationshipTarget.Unresolved));
        var ledger = result.observations().stream().filter(o -> isFieldCategory(o.category().value())).toList();
        assertEquals(6,ledger.size(),"qualifier fragments are not standalone field attempts");
        assertEquals(Set.of("unknown","missing"),new HashSet<>(ledger.stream().filter(o -> o.mappedOccurrence().isEmpty()).map(ObservationRecord::reference).toList()));
        assertTrue(ledger.stream().allMatch(o -> !o.diagnostics().isEmpty()));
        assertEquals(FrontendResult.State.PARTIAL,result.state());
        assertTrue(result.declarations().stream().noneMatch(d -> d.entity().kind() == EntityKind.FIELD));
    }
    @Test void arrayLengthHasNoFabricatedDeclaredFieldButItsReceiverIsRead() {
        var result = new JavaParserFrontend().analyze(TestInputs.request("class C { int[] data; int f(){data[0]++; return data.length;} }"));
        var entities = TypeRelationshipsTest.entities(result);
        assertEquals(0,accesses(result).stream().filter(o -> o.relationship().kind().value().equals("java.writes-field")).count());
        assertEquals(2,accesses(result).stream().filter(o -> o.status() == SemanticStatus.RESOLVED).count());
        assertTrue(accesses(result).stream().filter(o -> o.status() == SemanticStatus.RESOLVED).allMatch(o -> entities.get(((RelationshipTarget.Resolved)o.relationship().target()).target()).canonicalName().equals(fieldName("C.data"))));
        assertTrue(result.observations().stream().anyMatch(o -> o.reference().equals("data.length") && o.attribution() == SemanticStatus.UNSUPPORTED));
        assertTrue(result.declarations().stream().noneMatch(d -> d.entity().canonicalName().contains("\"length\"")));
    }
    @Test void unresolvedReceiverMembersAreNotDroppedAsPackageQualifiers() {
        var result = new JavaParserFrontend().analyze(TestInputs.request("class C { C child; void f(C arg){arg.missing.next++; child.missing.next=1;} }"));
        assertEquals(6,accesses(result).size());
        assertEquals(5,accesses(result).stream().filter(o -> o.status() == SemanticStatus.UNRESOLVED).count());
        assertEquals(1,accesses(result).stream().filter(o -> o.status() == SemanticStatus.RESOLVED).count());
        var references = result.observations().stream().filter(o -> isFieldCategory(o.category().value())).map(ObservationRecord::reference).toList();
        assertTrue(references.containsAll(List.of("arg.missing","child.missing","child")));
    }
    @Test void enumConstantsAreFieldsAndUnsupportedContextsNeverBecomeTypeCallers() {
        var result = new JavaParserFrontend().analyze(TestInputs.request("enum E { A,B } class C { E e=E.A; java.time.DayOfWeek f(){return java.time.DayOfWeek.MONDAY;} }"));
        assertEquals(2,accesses(result).size());
        var entities = TypeRelationshipsTest.entities(result);
        var targets = accesses(result).stream().map(o -> entities.get(((RelationshipTarget.Resolved)o.relationship().target()).target())).toList();
        assertTrue(targets.stream().allMatch(e -> e.kind() == EntityKind.FIELD));
        assertEquals(Set.of(fieldName("E.A"),fieldName("java.time.DayOfWeek.MONDAY")),new HashSet<>(targets.stream().map(Entity::canonicalName).toList()));
        var unsupported = new JavaParserFrontend().analyze(TestInputs.request("record R(int x) { int f(){return this.x;} } class Constants { static final String NAME=\"old\"; } @Deprecated(since=Constants.NAME) class C {}"));
        assertEquals(1,accesses(unsupported).size()); // Record field is supported; annotation values retain their boundary.
        var ledger = unsupported.observations().stream().filter(o -> isFieldCategory(o.category().value())).toList();
        assertEquals(2,ledger.size());
        assertEquals(1,ledger.stream().filter(o -> o.attribution()==SemanticStatus.UNSUPPORTED && o.mappedOccurrence().isEmpty()).count());
    }
    @Test void fieldHidingAndMultiDeclaratorsUseSelectedDeclarations() {
        var result = new JavaParserFrontend().analyze(TestInputs.request("class Base { int value; } class Child extends Base { int value; } class C { int left,right; void f(Child c){right=left; c.value=1; ((Base)c).value++;} }"));
        var entities = TypeRelationshipsTest.entities(result);
        assertEquals(5,accesses(result).size());
        var targets = accesses(result).stream().map(o -> entities.get(((RelationshipTarget.Resolved)o.relationship().target()).target()).canonicalName()).toList();
        assertEquals(1,Collections.frequency(targets,fieldName("C.right")));
        assertEquals(1,Collections.frequency(targets,fieldName("C.left")));
        assertEquals(1,Collections.frequency(targets,fieldName("Child.value")));
        assertEquals(2,Collections.frequency(targets,fieldName("Base.value")));
    }
    static List<RelationshipOccurrence> accesses(FrontendResult result) {
        return result.occurrences().stream().filter(o -> isFieldCategory(o.relationship().kind().value())).toList();
    }
    private static boolean isFieldCategory(String category) { return category.equals("java.reads-field") || category.equals("java.writes-field"); }
    static String fieldName(String qualified) {
        int member = qualified.lastIndexOf('.'); String owner = qualified.substring(0,member);
        int pkg = owner.lastIndexOf('.');
        return JavaSymbolName.field(JavaSymbolName.topLevelType(pkg < 0 ? "" : owner.substring(0,pkg),owner.substring(pkg+1)),qualified.substring(member+1)).canonicalName();
    }
}
