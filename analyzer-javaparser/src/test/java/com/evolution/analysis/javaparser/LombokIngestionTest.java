package com.evolution.analysis.javaparser;
import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.frontend.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LombokIngestionTest {
    @Test void unsupportedGeneratorTargetsAndGeneratorsRemainExplicit()throws Exception {
        for(String source:List.of("class C { @lombok.Builder C(String value) {} }",
                "class C { @lombok.Builder static C create(String value) { return new C(); } }",
                "@lombok.experimental.SuperBuilder class C { String value; }")) {
            var result=analyze(source);
            assertEquals(FrontendResult.State.PARTIAL,result.state());
            assertTrue(result.diagnostics().stream().anyMatch(d->d.code().equals("lombok.generator-unsupported")));
        }
    }
    @Test void explicitDataConstructorSuppressesGenerationAndExplicitConstructorAnnotationsDoNot()throws Exception {
        var data=analyze("@lombok.Data class C { final String name; C(String n){name=n;} }");
        assertEquals(1,data.declarations().stream().filter(d->d.entity().kind()==EntityKind.CONSTRUCTOR&&d.entity().origin()==EntityOrigin.PROJECT).count());
        var required=analyze("@lombok.RequiredArgsConstructor class C { final String name; C(){name=\"x\";} }");
        assertEquals(2,required.declarations().stream().filter(d->d.entity().kind()==EntityKind.CONSTRUCTOR&&d.entity().origin()==EntityOrigin.PROJECT).count());
    }
    @Test void allNoArgsValueAndBuilderHaveDistinctConstructorRules()throws Exception {
        for(String annotation:List.of("AllArgsConstructor","Value","Builder")) {
            var r=analyze("@lombok."+annotation+" class C { String name; final int fixed=1; }");
            var constructors=r.declarations().stream().filter(d->d.entity().kind()==EntityKind.CONSTRUCTOR&&d.entity().origin()==EntityOrigin.PROJECT&&!d.entity().canonicalName().contains("member-type")).toList();
            assertEquals(1,constructors.size());assertTrue(constructors.getFirst().entity().canonicalName().contains("String"));
        }
        var forced=analyze("@lombok.NoArgsConstructor(force=true) class C { final String name; }");
        assertEquals(1,forced.declarations().stream().filter(d->d.entity().kind()==EntityKind.CONSTRUCTOR&&d.entity().origin()==EntityOrigin.PROJECT).count());
        var invalid=analyze("@lombok.NoArgsConstructor class C { final String name; }");
        assertTrue(invalid.diagnostics().stream().anyMatch(d->d.code().equals("lombok.invalid-no-args")));
    }
    @Test void gettersSettersAndGenericParameterTypesRetainFieldEvidence()throws Exception {
        var r=analyze("@lombok.Data class C { final java.util.List<String> names; boolean ready; }");
        assertTrue(r.declarations().stream().anyMatch(d->d.entity().canonicalName().contains("\"getNames\"")));
        assertTrue(r.declarations().stream().anyMatch(d->d.entity().canonicalName().contains("\"isReady\"")));
        assertTrue(r.declarations().stream().anyMatch(d->d.entity().canonicalName().contains("\"setReady\"")));
        assertTrue(r.types().stream().anyMatch(t->t.role().value().equals("java.parameter-type")&&t.type().spelling().equals("java.util.List<String>")));
    }
    @Test void impostorsAndUnresolvedTypesNeverCreateLombokConstructors()throws Exception {
        var impostor=new JavaParserFrontend().analyze(TestInputs.request("package lombok; @interface RequiredArgsConstructor {} @RequiredArgsConstructor class C { final String name=null; }"));
        assertFalse(impostor.declarations().stream().anyMatch(d->d.derivation().method().id().startsWith("lombok.")));
        var missing=analyze("@lombok.RequiredArgsConstructor class C { final Missing dependency; }");
        assertTrue(missing.diagnostics().stream().anyMatch(d->d.code().equals("lombok.member-type-unresolved")));
    }
    private FrontendResult analyze(String source)throws Exception {
        Path jar=Path.of(lombok.Data.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var binary=new BinaryInput(new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,"org.projectlombok:lombok:1.18.46@jar",ContentDigest.sha256(Files.readAllBytes(jar))),jar);
        return new JavaParserFrontend().analyze(TestInputs.request(Map.of("fixture/C.java",source),List.of(binary)));
    }
    @Test void requiredConstructorKeepsFieldOrderAndHasParameterTypes()throws Exception {
        var r=analyze("@lombok.RequiredArgsConstructor class C { final String name; @lombok.NonNull Integer count; static int ignored; final int initialized=1; }");
        var constructors=r.declarations().stream().filter(d->d.entity().kind()==EntityKind.CONSTRUCTOR&&d.entity().origin()==EntityOrigin.PROJECT).toList();
        assertEquals(1,constructors.size());assertTrue(constructors.getFirst().entity().canonicalName().contains("String"));
        assertTrue(constructors.getFirst().entity().canonicalName().contains("Integer"));
        assertEquals(2,r.declarations().stream().filter(d->d.entity().kind()==EntityKind.PARAMETER).count());
        assertEquals(2,r.types().stream().filter(t->t.role().value().equals("java.parameter-type")).count());
        assertTrue(constructors.getFirst().entity().declaration().isEmpty());
    }
}
