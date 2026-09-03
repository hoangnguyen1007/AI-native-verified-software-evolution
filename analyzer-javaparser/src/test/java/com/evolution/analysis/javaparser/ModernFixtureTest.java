package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.frontend.*;
import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.tools.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated JDK 21 attribution of frozen source; never executes compiled target code. */
class ModernFixtureTest {
    private record WrittenCall(TreePath path,int start,int end,String category) {}
    @Test void modernFixtureHasExactCompilerSelectedTargetsAndEveryCatalogFamily() throws Exception {
        String source;
        try (var in=Objects.requireNonNull(getClass().getResourceAsStream("/m2/modern/Modern.java"))) {
            source=new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
        var compiler=ToolProvider.getSystemJavaCompiler();
        var diagnostics=new DiagnosticCollector<JavaFileObject>();
        var file=new SimpleJavaFileObject(URI.create("string:///fixture/Modern.java"),JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
        };
        try (var manager=compiler.getStandardFileManager(diagnostics,Locale.ROOT,StandardCharsets.UTF_8)) {
            manager.setLocationFromPaths(StandardLocation.CLASS_PATH,List.of());
            manager.setLocationFromPaths(StandardLocation.SOURCE_PATH,List.of());
            var task=(JavacTask)compiler.getTask(null,manager,diagnostics,List.of("--release","21","-proc:none","-implicit:none"),null,List.of(file));
            var unit=task.parse().iterator().next(); var trees=Trees.instance(task);
            var written=new ArrayList<WrittenCall>();
            new TreePathScanner<Void,Void>() {
                private void add(Tree node,String category) {
                    written.add(new WrittenCall(getCurrentPath(),(int)trees.getSourcePositions().getStartPosition(unit,node),(int)trees.getSourcePositions().getEndPosition(unit,node),category));
                }
                @Override public Void visitMethodInvocation(MethodInvocationTree node,Void unused) { add(node,"calls"); return super.visitMethodInvocation(node,unused); }
                @Override public Void visitNewClass(NewClassTree node,Void unused) {
                    var parent=getCurrentPath().getParentPath();
                    // javac parses an enum constant as a NewClassTree before attribution.
                    boolean enumConstant=parent!=null && parent.getLeaf() instanceof VariableTree && parent.getParentPath().getLeaf().getKind()==Tree.Kind.ENUM;
                    if (!enumConstant) add(node,"constructor-calls");
                    return super.visitNewClass(node,unused);
                }
                @Override public Void visitMemberReference(MemberReferenceTree node,Void unused) { add(node,"method-references"); return super.visitMemberReference(node,unused); }
            }.scan(unit,null);
            assertEquals(15,written.size(),"frozen explicit invocation denominator; compiler-generated trees excluded");
            task.analyze();
            assertTrue(diagnostics.getDiagnostics().stream().noneMatch(d -> d.getKind()==javax.tools.Diagnostic.Kind.ERROR),diagnostics.getDiagnostics().toString());
            var request=TestInputs.request(Map.of("fixture/Modern.java",source),List.of());
            var result=new JavaParserFrontend().analyze(request);
            var original=new OriginalSource(request.sources().getFirst().document().identity(),source);
            var entities=TypeRelationshipsTest.entities(result);
            var invocations=result.occurrences().stream().filter(o -> Set.of("java.calls","java.constructor-calls","java.method-references").contains(o.relationship().kind().value())).toList();
            assertEquals(written.size(),invocations.size(),"no omission or extra explicit invocation");
            var evidence=new ArrayList<Map<String,Object>>();
            for (var call:written) {
                var executable=(ExecutableElement)trees.getElement(call.path());
                assertNotNull(executable);
                var parameters=executable.getParameters().stream().map(p -> erased(task.getTypes().erasure(p.asType()))).toList();
                var owner=typeName((TypeElement)executable.getEnclosingElement());
                var expected=executable.getKind()==ElementKind.CONSTRUCTOR ? JavaSymbolName.constructor(owner,parameters) : JavaSymbolName.method(owner,executable.getSimpleName().toString(),parameters);
                var span=original.span(call.start(),call.end());
                var actual=invocations.stream().filter(o -> o.span().equals(span) && o.relationship().kind().value().equals("java."+call.category())).findFirst().orElseThrow(() -> new AssertionError(original.slice(span)));
                assertEquals(SemanticStatus.RESOLVED,actual.status(),original.slice(span)+" "+actual.diagnostics());
                var selected=entities.get(((RelationshipTarget.Resolved)actual.relationship().target()).target());
                assertEquals(expected.canonicalName(),selected.canonicalName(),original.slice(span));
                boolean sourceDeclaration=trees.getPath(executable.getEnclosingElement())!=null;
                assertEquals(sourceDeclaration ? EntityOrigin.PROJECT : EntityOrigin.JDK,selected.origin());
                evidence.add(Map.of("category",call.category(),"span",span,"text",original.slice(span),"checkedCanonicalTarget",expected.canonicalName(),
                        "compilerOwner",((TypeElement)executable.getEnclosingElement()).getQualifiedName().toString(),"compilerName",executable.getSimpleName().toString(),
                        "compilerErasedParameters",executable.getParameters().stream().map(p -> task.getTypes().erasure(p.asType()).toString()).toList(),"compilerOwnerHasSource",sourceDeclaration));
            }
            assertTrue(result.coverage().stream().allMatch(c -> c.attempted()>0),result.coverage().toString());
            assertEquals(CanonicalJson.write(result),CanonicalJson.write(new JavaParserFrontend().analyze(request)));
            Path output=Path.of("target/m2-evidence"); Files.createDirectories(output);
            Files.writeString(output.resolve("modern.json"),CanonicalJson.write(result));
            Files.writeString(output.resolve("modern-manifest.json"),CanonicalJson.write(request.manifest()));
            Files.writeString(output.resolve("modern-oracle.json"),CanonicalJson.write(Map.of("jdk",Runtime.version().toString(),"diagnostics",diagnostics.getDiagnostics().stream().map(d -> d.getCode()).toList(),"explicitInvocations",evidence)));
        }
    }
    private static JavaSymbolName typeName(TypeElement type) {
        return type.getEnclosingElement() instanceof TypeElement parent ? JavaSymbolName.memberType(typeName(parent),type.getSimpleName().toString())
                : JavaSymbolName.topLevelType(((PackageElement)type.getEnclosingElement()).getQualifiedName().toString(),type.getSimpleName().toString());
    }
    private static ErasedType erased(TypeMirror type) {
        if (type.getKind().isPrimitive()) return ErasedType.primitive(type.toString());
        if (type instanceof ArrayType array) return ErasedType.array(erased(array.getComponentType()),1);
        if (type instanceof DeclaredType declared) return ErasedType.declared(typeName((TypeElement)declared.asElement()));
        throw new AssertionError("Unmapped compiler type: "+type);
    }
}
