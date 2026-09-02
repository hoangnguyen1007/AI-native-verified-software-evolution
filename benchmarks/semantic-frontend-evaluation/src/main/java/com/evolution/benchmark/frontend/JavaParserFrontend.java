package com.evolution.benchmark.frontend;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** JavaParser/SymbolSolver adapter for the shared observation model. */
public final class JavaParserFrontend implements SemanticFrontend {
    @Override public String id() { return "javaparser-symbolsolver-3.26.1"; }

    @Override public FrontendResult analyze(FrontendRequest request) {
        long start = System.nanoTime();
        List<Observation> observations = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        CombinedTypeSolver solver = new CombinedTypeSolver(new ReflectionTypeSolver(), new JavaParserTypeSolver(request.sourceRoot()));
        for (Path entry : request.classpath()) {
            try {
                solver.add(new JarTypeSolver(entry));
            } catch (java.io.IOException exception) {
                throw new IllegalArgumentException("cannot add JavaParser classpath entry " + entry, exception);
            }
        }
        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setSymbolResolver(new JavaSymbolSolver(solver)));
        for (Path file : FrontendSupport.javaFiles(request.sourceRoot())) {
            com.github.javaparser.ParseResult<com.github.javaparser.ast.CompilationUnit> parsed;
            try {
                parsed = parser.parse(file);
            } catch (java.io.IOException exception) {
                diagnostics.add(file + ": " + exception);
                continue;
            }
            if (parsed.getResult().isEmpty()) { diagnostics.add(file + ": " + parsed.getProblems()); continue; }
            String relative = request.sourceRoot().relativize(file).toString().replace('\\', '/');
            parsed.getResult().orElseThrow().findAll(MethodCallExpr.class).forEach(call -> {
                var range = call.getRange();
                if (range.isEmpty()) { diagnostics.add(relative + ": missing JavaParser range"); return; }
                var r = range.orElseThrow();
                Span span = new Span(r.begin.line, r.begin.column, r.end.line, r.end.column + 1);
                try {
                    ResolvedMethodDeclaration target = call.resolve();
                    String identity = target.getQualifiedSignature();
                    observations.add(new Observation(RelationshipCategory.CALLS, relative + "#call-site", identity,
                            List.of(), FrontendSupport.origin(identity), Path.of(relative), span,
                            ObservationState.RESOLVED, ""));
                } catch (RuntimeException exception) {
                    observations.add(Observation.unresolved(RelationshipCategory.CALLS, relative + "#call-site",
                            call.toString(), Path.of(relative), span, exception.getClass().getSimpleName() + ": " + exception.getMessage()));
                }
            });
        }
        return new FrontendResult(id(), observations, diagnostics, (System.nanoTime() - start) / 1_000_000);
    }
}
