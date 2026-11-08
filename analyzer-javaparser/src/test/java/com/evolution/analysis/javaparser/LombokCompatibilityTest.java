package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.semantic.SemanticStatus;
import com.evolution.analysis.frontend.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LombokCompatibilityTest {
    @TempDir Path directory;
    private Path jar(){return Path.of(System.getProperty("lombok.compatibility.jar"));}
    private BinaryInput input(String version)throws Exception {
        var digest=ContentDigest.sha256(Files.readAllBytes(jar()));assertEquals("sha256:73b6b05b6a2d365b700bab08d30f94de9d336490bc0acce5b6181fef48cbf18e",digest.value());
        return new BinaryInput(new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,"org.projectlombok:lombok:"+version+"@jar",digest),jar());
    }
    private static final String SOURCE="""
        @lombok.Data class Pet { String name; }
        @lombok.Getter class Base { Long id; }
        @lombok.Getter @lombok.Setter class Child extends Base { String description; }
        @lombok.Data @lombok.Builder class Box { String name; }
        class Use { void run(Pet p, Child c) { p.setName("n"); p.getName(); c.setDescription("d"); c.getId(); Box.builder().name("b").build().getName(); } }
        """;
    @Test void petclinicLombok36AccessorsInheritanceAndBuilderCallsResolve()throws Exception {
        var result=new JavaParserFrontend().analyze(TestInputs.request(Map.of("fixture/Use.java",SOURCE),List.of(input("1.18.36"))));
        var calls=result.occurrences().stream().filter(o->o.relationship().kind().value().equals("java.calls")).toList();
        assertEquals(8,calls.size());assertEquals(8,calls.stream().filter(o->o.status()==SemanticStatus.RESOLVED).count());
    }
    @Test void nativePinned36CompilerConfirmsActualGeneratedDescriptors()throws Exception {
        input("1.18.36");Path file=directory.resolve("Use.java");Files.writeString(file,SOURCE);
        var compiler=javax.tools.ToolProvider.getSystemJavaCompiler();assertNotNull(compiler);
        assertEquals(0,compiler.run(null,null,null,"-classpath",jar().toString(),"-processorpath",jar().toString(),"-processor","lombok.launch.AnnotationProcessorHider$AnnotationProcessor","-d",directory.toString(),file.toString()));
        for(var expected:Map.of("Pet",Set.of("getName()Ljava/lang/String;","setName(Ljava/lang/String;)V"),"Child",Set.of("getDescription()Ljava/lang/String;","setDescription(Ljava/lang/String;)V"),"Base",Set.of("getId()Ljava/lang/Long;"),"Box$BoxBuilder",Set.of("name(Ljava/lang/String;)LBox$BoxBuilder;","build()LBox;")).entrySet())
            try(var bytes=new java.io.DataInputStream(Files.newInputStream(directory.resolve(expected.getKey()+".class")))){var type=new javassist.bytecode.ClassFile(bytes);assertTrue(type.getMethods().stream().map(m->m.getName()+m.getDescriptor()).collect(java.util.stream.Collectors.toSet()).containsAll(expected.getValue()),expected.getKey());}
    }
    @Test void mislabeledArtifactAndWrongArgumentCannotEstablishGeneratedCalls()throws Exception {
        var mislabeled=new JavaParserFrontend().analyze(TestInputs.request(Map.of("fixture/C.java","@lombok.Data class C { String name; String run(){return getName();} }"),List.of(input("1.18.46"))));
        assertTrue(mislabeled.occurrences().stream().filter(o->o.relationship().kind().value().equals("java.calls")).noneMatch(o->o.status()==SemanticStatus.RESOLVED));
        var wrong=new JavaParserFrontend().analyze(TestInputs.request(Map.of("fixture/C.java","@lombok.Data class C { String name; void run(){setName(42);} }"),List.of(input("1.18.36"))));
        assertTrue(wrong.occurrences().stream().filter(o->o.relationship().kind().value().equals("java.calls")).noneMatch(o->o.status()==SemanticStatus.RESOLVED));
    }
    @Test void localClassInsideMethodWithUnresolvedParameterDoesNotAbortAnalysis() {
        String source="""
            package app;
            class Host {
                void healthy() { helper(); }
                void helper() {}
                void bind(UnresolvedPreferences prefs) {
                    class LocalListener implements Runnable {
                        @Override public void run() { helper(); }
                    }
                    new LocalListener().run();
                }
            }
            """;
        var result=new JavaParserFrontend().analyze(LombokIngestionTest.recovery(TestInputs.request(Map.of("app/Host.java",source),List.of())));
        assertEquals(FrontendResult.State.PARTIAL,result.state());
        assertTrue(result.occurrences().stream().anyMatch(o->o.relationship().kind().value().equals("java.calls")&&o.status()==SemanticStatus.RESOLVED));
    }
}
