package com.evolution.benchmark.frontend;

import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.Element;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/** Independent compiler-binding oracle; it does not invoke either candidate frontend. */
final class JavacOracle {
    record Binding(String invocationText, String targetIdentity) { }

    List<Binding> analyze(Path sourceRoot, List<Path> classpath) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("a JDK compiler is required");
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
            files.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);
            var units = files.getJavaFileObjectsFromPaths(FrontendSupport.javaFiles(sourceRoot));
            JavacTask task = (JavacTask) compiler.getTask(null, files, null,
                    List.of("--release", "21", "-proc:none"), null, units);
            Iterable<? extends com.sun.source.tree.CompilationUnitTree> parsed = task.parse();
            task.analyze();
            Trees trees = Trees.instance(task);
            List<Binding> output = new ArrayList<>();
            for (var unit : parsed) {
                new TreePathScanner<Void, Void>() {
                    @Override public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                        Element element = trees.getElement(getCurrentPath());
                        if (element instanceof ExecutableElement method && method.getEnclosingElement() instanceof TypeElement owner) {
                            String parameters = method.getParameters().stream()
                                    .map(parameter -> parameter.asType().toString()).collect(java.util.stream.Collectors.joining(","));
                            output.add(new Binding(node.toString(), owner.getQualifiedName() + "." + method.getSimpleName() + "(" + parameters + ")"));
                        }
                        return super.visitMethodInvocation(node, unused);
                    }
                }.scan(unit, null);
            }
            return List.copyOf(output);
        } catch (IOException exception) {
            throw new IllegalStateException("javac oracle setup failed", exception);
        }
    }
}
