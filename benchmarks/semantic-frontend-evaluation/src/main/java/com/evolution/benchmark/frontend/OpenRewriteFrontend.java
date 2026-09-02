package com.evolution.benchmark.frontend;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;

/** OpenRewrite LST/type-attribution adapter for the shared observation model. */
public final class OpenRewriteFrontend implements SemanticFrontend {
    @Override public String id() { return "openrewrite-java-8.87.7"; }

    @Override public FrontendResult analyze(FrontendRequest request) {
        long start = System.nanoTime();
        List<Observation> observations = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        InMemoryExecutionContext context = new InMemoryExecutionContext(error -> diagnostics.add(error.toString()));
        JavaParser parser = JavaParser.fromJavaVersion().classpath(request.classpath()).build();
        parser.parse(FrontendSupport.javaFiles(request.sourceRoot()), request.sourceRoot(), context)
                .map(source -> (J.CompilationUnit) source).forEach(cu -> {
                    Path relativePath = Path.of(cu.getSourcePath().toString().replace('\\', '/'));
                    String source;
                    try { source = Files.readString(request.sourceRoot().resolve(relativePath)); }
                    catch (IOException exception) { diagnostics.add(relativePath + ": " + exception); return; }
                    new JavaIsoVisitor<Void>() {
                        @Override public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, Void unused) {
                            J.MethodInvocation visited = super.visitMethodInvocation(method, unused);
                            JavaType.Method target = method.getMethodType();
                            String text = method.printTrimmed(getCursor());
                            if (target == null || target.getDeclaringType() == null) {
                                Span span = spanOrPlaceholder(source, text);
                                observations.add(Observation.unresolved(RelationshipCategory.CALLS, relativePath + "#call-site",
                                        text, relativePath, span, "missing OpenRewrite method attribution"));
                                return visited;
                            }
                            String identity = target.getDeclaringType().getFullyQualifiedName() + "." + target.getName()
                                    + "(" + target.getParameterTypes() + ")";
                            try {
                                Span span = FrontendSupport.uniqueTextSpan(source, text);
                                observations.add(new Observation(RelationshipCategory.CALLS, relativePath + "#call-site", identity,
                                        List.of(), FrontendSupport.origin(identity), relativePath, span,
                                        ObservationState.RESOLVED, ""));
                            } catch (RuntimeException exception) {
                                observations.add(new Observation(RelationshipCategory.CALLS, relativePath + "#call-site", identity,
                                        List.of(), FrontendSupport.origin(identity), relativePath, new Span(1, 1, 1, 2),
                                        ObservationState.RESOLVED, "provenance reconstruction failed: " + exception.getMessage()));
                            }
                            return visited;
                        }

                        private Span spanOrPlaceholder(String input, String text) {
                            try { return FrontendSupport.uniqueTextSpan(input, text); }
                            catch (RuntimeException ignored) { return new Span(1, 1, 1, 2); }
                        }
                    }.visit(cu, null);
                });
        return new FrontendResult(id(), observations, diagnostics, (System.nanoTime() - start) / 1_000_000);
    }
}
