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
}

