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
    void sameNamedClassAndRepeatedKotlinAnnotationsKeepDistinctOriginalSpans() throws Exception {
        String source = """
                package io.realworld.web
                import org.springframework.web.bind.annotation.ExceptionHandler
                import org.springframework.web.bind.annotation.RestControllerAdvice
                @RestControllerAdvice
                class RestControllerAdvice {
                    @ExceptionHandler(Exception::class)
                    fun handle(e: Exception): String = e.message ?: ""
                    @ExceptionHandler(IllegalStateException::class)
                    fun handleOther(e: IllegalStateException): String = e.message ?: ""
                }
                """;
        var files = Map.of("src/main/kotlin/io/realworld/web/RestControllerAdvice.kt",source);
        var request = LombokIngestionTest.recovery(ComponentIngestionTest.fixture(files,false).request());
        var result = new JavaParserFrontend().analyze(request).validateFor(request);
        assertEquals(1,result.sources().size());
        assertEquals(3,result.annotations().size());
        var original = new OriginalSource(request.sources().getFirst().document().identity(),source);
        assertEquals(java.util.Set.of("@RestControllerAdvice","@ExceptionHandler(Exception::class)","@ExceptionHandler(IllegalStateException::class)"),
                result.annotations().stream().map(a -> original.slice(a.span())).collect(java.util.stream.Collectors.toSet()));
        assertEquals(3,result.annotations().stream().map(a -> a.span()).distinct().count());
        assertTrue(result.declarations().stream().noneMatch(d -> d.derivation().method().id().equals("java.explicit-import-recovery")));
    }
}
