package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.frontend.*;
import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.tools.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent JDK binding, execution-owner and original-span checks on authored valid source. */
class M9JavacOracleTest {
    private record Written(TreePath path, int start, int end, String category) {}

    @Test void exactAndRecoveryAgreeWithJavacOnShadowingInheritanceGenericsAndLocalOwners() throws Exception {
        String source = """
                package fixture;
                import java.util.*;
                class Base { Number pick(Number value) { return value; } }
                class C extends Base {
                    String pick(String value) { return value.trim(); }
                    <T> T identity(T value) { return value; }
                    void run() {
                        String value = pick("x"); Number number = pick(Integer.valueOf(2));
                        class Local { Local() {} String text() { return C.this.pick("y"); } }
                        String local = new Local().text();
                        java.util.function.Function<String,String> chosen = this::pick;
                        identity(value).trim(); List<String> list = List.of("x"); list.get(0).trim();
                    }
                }
                """;
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var compiler = ToolProvider.getSystemJavaCompiler();
        var input = new SimpleJavaFileObject(URI.create("string:///fixture/C.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignored) { return source; }
        };
        try (var files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            files.setLocationFromPaths(StandardLocation.CLASS_PATH, List.of());
            files.setLocationFromPaths(StandardLocation.SOURCE_PATH, List.of());
            var task = (JavacTask) compiler.getTask(null, files, diagnostics,
                    List.of("--release", "21", "-proc:none", "-implicit:none"), null, List.of(input));
            var unit = task.parse().iterator().next(); var trees = Trees.instance(task);
            var written = new ArrayList<Written>();
            new TreePathScanner<Void, Void>() {
                void add(Tree tree, String category) {
                    written.add(new Written(getCurrentPath(), (int) trees.getSourcePositions().getStartPosition(unit, tree),
                            (int) trees.getSourcePositions().getEndPosition(unit, tree), category));
                }
                @Override public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) { add(tree,"calls"); return super.visitMethodInvocation(tree,unused); }
                @Override public Void visitNewClass(NewClassTree tree, Void unused) { add(tree,"constructor-calls"); return super.visitNewClass(tree,unused); }
                @Override public Void visitMemberReference(MemberReferenceTree tree, Void unused) { add(tree,"method-references"); return super.visitMemberReference(tree,unused); }
            }.scan(unit, null);
            assertEquals(13, written.size(), "Frozen explicit invocation denominator; no generated compiler calls");
            task.analyze();
            assertTrue(diagnostics.getDiagnostics().stream().noneMatch(d -> d.getKind() == javax.tools.Diagnostic.Kind.ERROR),
                    () -> diagnostics.getDiagnostics().stream().map(javax.tools.Diagnostic::getCode).toList().toString());
            var exact = TestInputs.request(source);
            for (var request : List.of(exact, LombokIngestionTest.recovery(exact))) {
                var result = new JavaParserFrontend().analyze(request);
                var original = new OriginalSource(request.sources().getFirst().document().identity(), source);
                var entities = TypeRelationshipsTest.entities(result);
                var calls = result.occurrences().stream().filter(o -> Set.of("java.calls", "java.constructor-calls", "java.method-references").contains(o.relationship().kind().value())).toList();
                assertEquals(written.size(), calls.size(), "Omitted and extra invocations both fail");
                for (var label : written) {
                    var selected = (ExecutableElement) trees.getElement(label.path());
                    assertNotNull(selected);
                    var actual = calls.stream().filter(o -> o.relationship().kind().value().equals("java." + label.category())
                            && o.span().equals(original.span(label.start(), label.end()))).findFirst().orElseThrow();
                    assertEquals(SemanticStatus.RESOLVED, actual.status(), original.slice(actual.span()));
                    var target = entities.get(((RelationshipTarget.Resolved) actual.relationship().target()).target());
                    var declared = trees.getPath(selected);
                    if (declared != null) {
                        int start = (int) trees.getSourcePositions().getStartPosition(unit, declared.getLeaf());
                        int end = (int) trees.getSourcePositions().getEndPosition(unit, declared.getLeaf());
                        assertEquals(EntityOrigin.PROJECT, target.origin());
                        assertEquals(Optional.of(original.span(start, end)), target.declaration());
                    } else {
                        assertEquals(EntityOrigin.JDK, target.origin());
                        var owner = typeName((TypeElement) selected.getEnclosingElement());
                        var parameters = selected.getParameters().stream().map(p -> erased(task.getTypes().erasure(p.asType()))).toList();
                        assertEquals(JavaSymbolName.method(owner, selected.getSimpleName().toString(), parameters).canonicalName(), target.canonicalName());
                        assertTrue(target.declaration().isEmpty());
                    }
                    TreePath caller = label.path().getParentPath();
                    while (!(caller.getLeaf() instanceof MethodTree)) caller = caller.getParentPath();
                    int start = (int) trees.getSourcePositions().getStartPosition(unit, caller.getLeaf());
                    int end = (int) trees.getSourcePositions().getEndPosition(unit, caller.getLeaf());
                    assertEquals(Optional.of(original.span(start, end)), entities.get(actual.relationship().source()).declaration(), "Execution owner");
                }
            }
        }
    }
    private static JavaSymbolName typeName(TypeElement type) {
        return type.getEnclosingElement() instanceof TypeElement parent
                ? JavaSymbolName.memberType(typeName(parent), type.getSimpleName().toString())
                : JavaSymbolName.topLevelType(((PackageElement) type.getEnclosingElement()).getQualifiedName().toString(), type.getSimpleName().toString());
    }
    private static ErasedType erased(TypeMirror type) {
        if (type.getKind().isPrimitive()) return ErasedType.primitive(type.toString());
        if (type instanceof ArrayType array) return ErasedType.array(erased(array.getComponentType()), 1);
        if (type instanceof DeclaredType declared) return ErasedType.declared(typeName((TypeElement) declared.asElement()));
        throw new AssertionError("Unsupported oracle type: " + type.getKind());
    }
}
