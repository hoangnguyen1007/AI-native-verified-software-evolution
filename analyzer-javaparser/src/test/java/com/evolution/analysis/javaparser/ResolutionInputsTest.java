package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.EntityScope;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.frontend.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ResolutionInputsTest {
    @TempDir Path temp;
    private BinaryInput jar(String name, String code) throws Exception {
        Path root = temp.resolve(name); Files.createDirectories(root);
        Path source = root.resolve("Library.java"); Files.writeString(source,code);
        // Only test-owned fixed source, no annotation processors or target build lifecycle.
        int exit = ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","21","-proc:none","-encoding","UTF-8","-classpath",root.toString(),"-sourcepath",root.toString(),"-d",root.toString(),source.toString());
        assertEquals(0,exit);
        Path jar = temp.resolve(name + ".jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar)); var files = Files.walk(root)) {
            for (var file : files.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                var entry = new JarEntry(root.relativize(file).toString().replace('\\','/')); entry.setTime(0);
                output.putNextEntry(entry); Files.copy(file,output); output.closeEntry();
            }
        }
        return new BinaryInput(new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,"fixture:" + name + ":1",ContentDigest.sha256(Files.readAllBytes(jar))),jar);
    }
    private static List<RelationshipOccurrence> calls(FrontendResult result) { return result.occurrences().stream().filter(o -> o.relationship().kind().value().equals("java.calls")).toList(); }
    @Test void exactJarOriginsAndRemovedDependencyAreEvidenceBacked() throws Exception {
        var dependency = jar("library","package javax.fixture; public class Library { public static String text(){return \"x\";} }");
        var source = Map.of("fixture/C.java","class C { String run(){return javax.fixture.Library.text().trim();} }");
        var request = TestInputs.request(source,List.of(dependency));
        var result = new JavaParserFrontend().analyze(request);
        assertEquals(2,calls(result).size()); assertTrue(calls(result).stream().allMatch(o -> o.status() == SemanticStatus.RESOLVED));
        var target = result.declarations().stream().map(DeclarationRecord::entity).filter(e -> e.canonicalName().contains("\"text\"")).findFirst().orElseThrow();
        assertEquals(EntityOrigin.DEPENDENCY,target.origin());
        assertEquals(EntityScope.external(EntityOrigin.DEPENDENCY,dependency.entry().logicalName(),dependency.entry().contentDigest()),target.stableScope());
        assertTrue(target.declaration().isEmpty());
        var removed = new JavaParserFrontend().analyze(TestInputs.request(source,List.of()));
        assertEquals(2,calls(removed).size()); assertTrue(calls(removed).stream().allMatch(o -> o.status() == SemanticStatus.UNRESOLVED));
        assertNotEquals(request.manifest().identity(),TestInputs.request(source,List.of()).manifest().identity());
    }
    @Test void classpathOrderControlsSelectionAndReportsDuplicateDefinitions() throws Exception {
        String code = "package dep; public class Library { public static String text(){return \"x\";} }";
        var first = jar("first",code); var second = jar("second",code);
        var source = Map.of("fixture/C.java","class C { String run(){return dep.Library.text();} }");
        var forward = TestInputs.request(source,List.of(first,second)); var reversed = TestInputs.request(source,List.of(second,first));
        var a = new JavaParserFrontend().analyze(forward); var b = new JavaParserFrontend().analyze(reversed);
        assertNotEquals(forward.manifest().identity(),reversed.manifest().identity());
        assertTrue(a.diagnostics().stream().anyMatch(d -> d.code().equals("java.duplicate-binary-type")));
        assertNotEquals(calls(a).getFirst().relationship().target(),calls(b).getFirst().relationship().target());
    }
    @Test void mutableArtifactHandlesAreRecheckedBeforeUse() throws Exception {
        var dependency = jar("changed","package dep; public class Library { public static void hit(){} }");
        var request = TestInputs.request(Map.of("fixture/C.java","class C {}"),List.of(dependency));
        Files.writeString(dependency.path(),"changed");
        var failure = assertThrows(FrontendInputException.class,() -> new JavaParserFrontend().analyze(request));
        assertEquals("frontend.artifact-digest",failure.diagnostic().code());
    }
    @Test void hostApplicationDependenciesNeverLeakIntoAnalysis() {
        var result = new JavaParserFrontend().analyze(TestInputs.request("class C { void run(){org.junit.jupiter.api.Assertions.assertTrue(true);} }"));
        assertEquals(1,calls(result).size()); assertEquals(SemanticStatus.UNRESOLVED,calls(result).getFirst().status());
    }
    @Test void sourceInputPermutationIsDeterministicAndCrossFileTargetsRemainProject() {
        var sources = new LinkedHashMap<String,String>(); sources.put("fixture/B.java","class B { static String hit(){return \"x\";} }"); sources.put("fixture/A.java","class A { String run(){return B.hit();} }");
        var a = TestInputs.request(sources,List.of());
        var b = new FrontendRequest(a.manifest(),a.module(),a.sourceSet(),a.sources().reversed(),a.platform(),a.dependencies());
        var result = new JavaParserFrontend().analyze(a);
        assertEquals(CanonicalJson.write(result),CanonicalJson.write(new JavaParserFrontend().analyze(b)));
        assertEquals(1,calls(result).size());
        var targetId = ((RelationshipTarget.Resolved)calls(result).getFirst().relationship().target()).target();
        assertEquals(EntityOrigin.PROJECT,result.declarations().stream().map(DeclarationRecord::entity).filter(e -> e.identity().equals(targetId)).findFirst().orElseThrow().origin());
    }
    @Test void wrongPlatformHashIsRejectedBeforeParsing() {
        var valid = TestInputs.request("class C {}");
        var invalidPlatform = new PlatformInput(new ClasspathEntry(ClasspathEntryKind.JDK_MODULE,valid.platform().entry().logicalName(),ContentDigest.sha256Utf8("wrong")),valid.platform().javaHome());
        var request = TestInputs.request(valid.sources(),invalidPlatform,List.of());
        assertEquals("frontend.artifact-digest",assertThrows(FrontendInputException.class,() -> new JavaParserFrontend().analyze(request)).diagnostic().code());
    }
}
