package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.frontend.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GeneratedSymbolResolutionTest {
    @TempDir Path directory;
    private FrontendResult analyze(String source) throws Exception {
        Path jar=Path.of(lombok.Data.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var input=new BinaryInput(new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,
                "org.projectlombok:lombok:1.18.46@jar",ContentDigest.sha256(Files.readAllBytes(jar))),jar);
        return new JavaParserFrontend().analyze(TestInputs.request(Map.of("fixture/C.java",source),List.of(input)));
    }
    @Test void realClientResolvesGeneratedConstructorGetterAndSetterToDerivedDeclarations() throws Exception {
        var result=analyze("@lombok.Data class C { final String name; int count; } class Client { String run() { C value=new C(\"x\"); value.setCount(2); return value.getName(); } }");
        var calls=result.occurrences().stream().filter(o -> Set.of("java.calls","java.constructor-calls").contains(o.relationship().kind().value())).toList();
        assertEquals(3,calls.size());
        assertTrue(calls.stream().allMatch(o -> o.status()==SemanticStatus.RESOLVED),result.diagnostics().toString());
        for(var call:calls) {
            var target=((RelationshipTarget.Resolved)call.relationship().target()).target();
            var declaration=result.declarations().stream().filter(d -> d.entity().identity().equals(target)).findFirst().orElseThrow();
            assertEquals(EntityOrigin.PROJECT,declaration.entity().origin());
            assertTrue(declaration.entity().declaration().isEmpty());
            assertTrue(declaration.derivation().method().id().startsWith("lombok."));
            assertFalse(declaration.derivation().inputIdentities().isEmpty());
        }
    }
    @Test void wrongArgumentsAndPrivateConstructorNeverBecomeGeneratedResolution() throws Exception {
        for(String source:List.of("@lombok.Data class C { String name; } class Client { void run(C c) { c.setName(1); } }",
                "@lombok.AllArgsConstructor(access=lombok.AccessLevel.PRIVATE) class C { String name; } class Client { C run() { return new C(\"x\"); } }",
                "@lombok.AllArgsConstructor class C { String name; C(Object value) {} } class Client { C run() { return new C(null); } }")) {
            var result=analyze(source);
            assertTrue(result.occurrences().stream().filter(o -> Set.of("java.calls","java.constructor-calls").contains(o.relationship().kind().value()))
                    .noneMatch(o -> o.status()==SemanticStatus.RESOLVED));
        }
    }
    @Test void explicitAccessorRemainsTheActualTarget() throws Exception {
        var result=analyze("@lombok.Data class C { String name; public String getName(){return name;} } class Client { String run(C c){return c.getName();} }");
        var call=result.occurrences().stream().filter(o -> o.relationship().kind().value().equals("java.calls")).findFirst().orElseThrow();
        assertEquals(SemanticStatus.RESOLVED,call.status());
        var target=((RelationshipTarget.Resolved)call.relationship().target()).target();
        assertTrue(result.declarations().stream().filter(d -> d.entity().identity().equals(target)).allMatch(d -> d.entity().declaration().isPresent()));
    }
    @Test void generatedOverrideCannotFallBackToAnInheritedHandwrittenTarget() throws Exception {
        var result=analyze("class Base { String getName(){return \"base\";} } @lombok.Data class C extends Base { String name; } class Client { String run(C c){return c.getName();} }");
        var call=result.occurrences().stream().filter(o -> o.relationship().kind().value().equals("java.calls")).findFirst().orElseThrow();
        assertNotEquals(SemanticStatus.RESOLVED,call.status(),"An unsupported inherited context must not select the overridden base method");
    }
    @Test void generatedExactConstructorWinsOverAnExplicitWiderOverload() throws Exception {
        String source="@lombok.AllArgsConstructor class C { String name; C(Object value) {name=\"other\";} } class Client { C run(){return new C(\"x\");} }";
        var result=analyze(source);
        var call=result.occurrences().stream().filter(o -> o.relationship().kind().value().equals("java.constructor-calls")).findFirst().orElseThrow();
        assertEquals(SemanticStatus.RESOLVED,call.status());
        var target=((RelationshipTarget.Resolved)call.relationship().target()).target();
        assertTrue(result.declarations().stream().filter(d -> d.entity().identity().equals(target))
                .allMatch(d -> d.derivation().method().id().equals("lombok.all-args-constructor")));
        // Compiler attribution is independent of the adapter and executes only this fixed authored fixture.
        Path file=directory.resolve("C.java");Files.writeString(file,source);
        Path jar=Path.of(lombok.Data.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var compiler=javax.tools.ToolProvider.getSystemJavaCompiler();
        var diagnostics=new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
        try(var manager=compiler.getStandardFileManager(diagnostics,Locale.ROOT,java.nio.charset.StandardCharsets.UTF_8)) {
            var task=(com.sun.source.util.JavacTask)compiler.getTask(null,manager,diagnostics,
                    List.of("--release","21","-classpath",jar.toString(),"-processorpath",jar.toString(),
                            "-processor","lombok.launch.AnnotationProcessorHider$AnnotationProcessor","-d",directory.toString()),
                    null,manager.getJavaFileObjects(file.toFile()));
            var trees=task.parse();task.analyze();
            assertFalse(diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind()==javax.tools.Diagnostic.Kind.ERROR));
            var attributed=new ArrayList<String>();var resolver=com.sun.source.util.Trees.instance(task);
            for(var tree:trees)new com.sun.source.util.TreePathScanner<Void,Void>() {
                @Override public Void visitNewClass(com.sun.source.tree.NewClassTree node,Void unused) {
                    var element=resolver.getElement(getCurrentPath());
                    if(element instanceof javax.lang.model.element.ExecutableElement constructor
                            &&constructor.getEnclosingElement().getSimpleName().contentEquals("C"))
                        attributed.add(constructor.getParameters().getFirst().asType().toString());
                    return super.visitNewClass(node,unused);
                }
            }.scan(tree,null);
            assertEquals(List.of("java.lang.String"),attributed);
        }
    }
}
