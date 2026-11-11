package com.evolution.analysis.javaparser;

import static org.junit.jupiter.api.Assertions.*;

import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KotlinAstLoweringTest {
    @Test
    void lowersDataClassCompanionObjectAndTopLevelFunction() throws Exception {
        String kt = """
                package org.schabi.newpipe

                data class StreamItem(val url: String, var title: String, val isLive: Boolean = false) {
                    companion object {
                        @JvmStatic
                        fun fromUrl(url: String): StreamItem = StreamItem(url, "Untitled")
                    }
                }

                fun normalizeUrl(raw: String): String = raw.trim()
                """;
        var cu = KotlinAstLowering.lower(
                "src/main/kotlin/org/schabi/newpipe/StreamItem.kt",
                UnicodeSource.translate(kt),
                new JavaSymbolSolver(new CombinedTypeSolver()));
        var typeNames = cu.getTypes().stream().map(t -> t.getNameAsString()).toList();
        assertTrue(typeNames.contains("StreamItem"), () -> "Expected StreamItem in " + typeNames + " AST=\n" + cu);
        assertTrue(typeNames.contains("StreamItemKt"), () -> "Expected StreamItemKt in " + typeNames);

        var fixture = ComponentIngestionTest.fixture(Map.of(
                "src/main/kotlin/org/schabi/newpipe/StreamItem.kt", kt,
                "src/main/java/org/schabi/newpipe/JavaFeedService.java", """
                        package org.schabi.newpipe;
                        public class JavaFeedService {
                            public String loadTitle(String rawUrl) {
                                String clean = StreamItemKt.normalizeUrl(rawUrl);
                                StreamItem item = StreamItem.fromUrl(clean);
                                StreamItem viaCompanion = StreamItem.Companion.fromUrl(clean);
                                if (item.isLive()) {
                                    item.setTitle("LIVE");
                                }
                                return viaCompanion.getTitle();
                            }
                        }
                        """
        ), false);
        var result = new JavaParserFrontend().analyze(fixture.request());
        var canonicalNames = result.declarations().stream().map(d -> d.entity().canonicalName()).toList();
        assertTrue(canonicalNames.stream().anyMatch(n -> n.contains("\"StreamItem\"")),
                () -> "Expected StreamItem in " + canonicalNames);
        assertTrue(canonicalNames.stream().anyMatch(n -> n.contains("\"StreamItemKt\"")),
                () -> "Expected StreamItemKt in " + canonicalNames);
        assertTrue(result.typeDeclarations().size() >= 4,
                () -> "types=" + result.typeDeclarations() + "\ndiags=" + result.diagnostics());
    }

    @Test
    void lowersBodylessSpringBootApplicationAndConstructorInjectedController() throws Exception {
        String appKt = """
                package org.springframework.samples.petclinic

                import org.springframework.boot.autoconfigure.SpringBootApplication
                import org.springframework.boot.runApplication

                @SpringBootApplication
                class PetClinicApplication

                fun main(args: Array<String>) {
                    runApplication<PetClinicApplication>(*args)
                }
                """;
        var cu = KotlinAstLowering.lower(
                "src/main/kotlin/org/springframework/samples/petclinic/PetClinicApplication.kt",
                UnicodeSource.translate(appKt),
                new JavaSymbolSolver(new CombinedTypeSolver()));
        var typeNames = cu.getTypes().stream().map(t -> t.getNameAsString()).toList();
        assertTrue(typeNames.contains("PetClinicApplication"), () -> "Expected PetClinicApplication in " + typeNames);
        assertTrue(typeNames.contains("PetClinicApplicationKt"), () -> "Expected PetClinicApplicationKt in " + typeNames);
    }

    @Test
    void lowersRealworldSpringbootKotlinSourcesCleanly() throws Exception {
        var files = new java.util.LinkedHashMap<String, String>();
        var root = java.nio.file.Path.of("D:/UIT/evo-random-benchmark/realworld-springboot-kotlin/src/main/kotlin");
        if (java.nio.file.Files.isDirectory(root)) {
            try (var stream = java.nio.file.Files.walk(root)) {
                for (var p : stream.filter(p -> p.toString().endsWith(".kt")).sorted().toList()) {
                    String rel = "src/main/kotlin/" + root.relativize(p).toString().replace('\\', '/');
                    files.put(rel, java.nio.file.Files.readString(p));
                }
            }
        } else {
            files.put("src/main/kotlin/io/realworld/web/RestControllerAdvice.kt", """
                    package io.realworld.web
                    import org.springframework.web.bind.annotation.ExceptionHandler
                    import org.springframework.web.bind.annotation.RestControllerAdvice
                    @RestControllerAdvice
                    class RestControllerAdvice {
                        @ExceptionHandler(Exception::class)
                        fun handle(e: Exception): String = e.message ?: ""
                    }
                    """);
        }
        var request = LombokIngestionTest.recovery(ComponentIngestionTest.fixture(files, true).request());
        var result = new JavaParserFrontend().analyze(request).validateFor(request);
        assertFalse(result.typeDeclarations().isEmpty());
        var structKinds = java.util.Set.of(
                "java.extends", "java.implements", "java.permits", "java.field-type",
                "java.parameter-type", "java.returns", "java.throws", "java.annotated-with",
                "java.type-argument", "java.type-parameter-bound");
        var structObs = result.observations().stream()
                .filter(o -> structKinds.contains(o.category().value()))
                .toList();
        var unresolvedStruct = structObs.stream()
                .filter(o -> o.attribution() != com.evolution.analysis.contract.semantic.SemanticStatus.RESOLVED)
                .toList();
        long permille = structObs.isEmpty() ? 1000L
                : ((structObs.size() - unresolvedStruct.size()) * 1000L) / structObs.size();
        assertTrue(permille >= 980L, () -> "permille=" + permille + " unresolved=" + unresolvedStruct);
    }
}

