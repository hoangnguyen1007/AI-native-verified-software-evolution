package com.evolution.analysis.javaparser.slice;

import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.query.trace.MethodSliceResult.SliceDirection;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** WP4.1 tests: CausalRepair Test-Side SDG Slicing, CollectionContainerSummary flow, and AST Skeleton Tree. */
class DualSlicingAndSkeletonTest {

    @Test
    void sliceFailingTestCollectsIntraSliceSetupFieldsLocalAndSkgExternalHelpersUpToDepth3() {
        String testSource = """
                package com.acme;
                import org.junit.jupiter.api.BeforeEach;
                import org.junit.jupiter.api.Test;
                class PricingTest {
                    private int baseTax;
                    private int unusedField;

                    @BeforeEach
                    void initFixture() {
                        baseTax = 5;
                    }

                    private int computeTotal(int raw) {
                        return applyDiscount(raw) + baseTax;
                    }

                    private int applyDiscount(int raw) {
                        return ExternalFixture.normalize(raw) - 1;
                    }

                    @Test
                    void failsOnNegativePrice() {
                        int unrelated = 999;
                        int price = 20;
                        int total = computeTotal(price);
                        assertEquals(24, total);
                    }
                }
                """;
        String externalHelper = """
                package com.acme;
                class ExternalFixture {
                    static int normalize(int v) {
                        return Math.max(0, v);
                    }
                }
                """;
        int failingLine = findLine(testSource, "assertEquals(24, total);");
        int unrelatedLine = findLine(testSource, "int unrelated = 999;");

        JavaMethodSlicer.TestHelperResolver resolver = (callerPath, recv, name, arity) -> {
            if ("normalize".equals(name)) {
                return Optional.of(new JavaMethodSlicer.ResolvedHelperSource(
                        "src/test/java/com/acme/ExternalFixture.java", externalHelper, "normalize(int)", 3));
            }
            return Optional.empty();
        };

        var res = JavaMethodSlicer.sliceFailingTest("src/test/java/com/acme/PricingTest.java", testSource, failingLine, resolver);
        assertEquals(TrustTier.SOURCE_SYNTAX, res.trustTier());
        assertTrue(res.intraSlice().sliceLines().contains(failingLine));
        assertFalse(res.intraSlice().sliceLines().contains(unrelatedLine), "Unrelated variable must be sliced away");
        assertTrue(res.externalDependencies().stream().anyMatch(d -> "SETUP_METHOD".equals(d.kind()) && d.symbolName().contains("initFixture")));
        assertTrue(res.externalDependencies().stream().anyMatch(d -> "TEST_FIELD".equals(d.kind()) && d.symbolName().contains("baseTax")));
        assertFalse(res.externalDependencies().stream().anyMatch(d -> d.symbolName().contains("unusedField")));
        assertTrue(res.externalDependencies().stream().anyMatch(d -> "LOCAL_HELPER".equals(d.kind()) && d.symbolName().contains("computeTotal")));
        assertTrue(res.externalDependencies().stream().anyMatch(d -> "LOCAL_HELPER".equals(d.kind()) && d.symbolName().contains("applyDiscount")));
        assertTrue(res.externalDependencies().stream().anyMatch(d -> "SKG_EXTERNAL_HELPER".equals(d.kind()) && d.symbolName().contains("normalize")));
        assertTrue(res.externalDependencies().stream().allMatch(d -> d.depth() <= 3));
    }

    @Test
    void collectionContainerSummaryTracksMapPutToGetDataflowAcrossNonTopLevelExpressions() {
        String src = """
                package com.acme;
                import java.util.HashMap;
                import java.util.Map;
                class CacheFlow {
                    String lookup(String key, String value) {
                        Map<String, String> cache = new HashMap<>();
                        int noise = 42;
                        String prev = cache.put(key, value);
                        String fetched = cache.get(key);
                        return fetched;
                    }
                }
                """;
        int returnLine = findLine(src, "return fetched;");
        int putLine = findLine(src, "String prev = cache.put(key, value);");
        int noiseLine = findLine(src, "int noise = 42;");

        var slice = JavaMethodSlicer.slice("src/main/java/com/acme/CacheFlow.java", src, returnLine, SliceDirection.BACKWARD);
        assertTrue(slice.sliceLines().contains(putLine), "WP4.1/WP2.1 Map.put -> Map.get abstract contents flow must be captured");
        assertFalse(slice.sliceLines().contains(noiseLine));
    }

    @Test
    void astSkeletonTreePreservesAllThreeSemanticAnchorsAndCompressesIrrelevantCode() {
        String src = """
                package com.acme;
                /**
                 * Critical formatter specification: preserves ISO year rules.
                 */
                public class DateRule {
                    /** Formats a year using strict ISO rules (do not truncate centuries). */
                    public String formatYear(int year) {
                        int noise1 = 1;
                        int noise2 = 2;
                        int noise3 = 3;
                        int noise4 = 4;
                        if (year < 0) {
                            return "BCE";
                        }
                        return String.valueOf(year);
                    }

                    public void completelyUnrelatedMethod() {
                        int a = 10;
                        int b = 20;
                    }
                }
                """;
        int targetLine = findLine(src, "return \"BCE\";");
        var skel1 = JavaMethodSlicer.buildAstSkeletonTree("src/main/java/com/acme/DateRule.java", src, Set.of(targetLine), true);
        var skel2 = JavaMethodSlicer.buildAstSkeletonTree("src/main/java/com/acme/DateRule.java", src, Set.of(targetLine), true);

        assertEquals(skel1.digest(), skel2.digest(), "I2: AST Skeleton must be deterministic");
        assertTrue(skel1.renderedSkeleton().startsWith("// File: src/main/java/com/acme/DateRule.java\n"));
        assertTrue(skel1.renderedSkeleton().contains("Critical formatter specification"), "Class Javadoc must be preserved");
        assertTrue(skel1.renderedSkeleton().contains("Formats a year using strict ISO rules"), "Method Javadoc must be preserved");
        assertTrue(skel1.renderedSkeleton().contains("Line " + targetLine + ":"), "Relative Line N: anchor must be preserved");
        assertFalse(skel1.renderedSkeleton().contains("completelyUnrelatedMethod"), "Unrelated method must be elided");
        assertTrue(skel1.lineReductionPercent() > 25.0);

        // Edge case: empty input
        var empty = JavaMethodSlicer.buildAstSkeletonTree("Empty.java", "", Set.of(1), true);
        assertEquals(0, empty.originalLineCount());
        assertEquals("// File: Empty.java\n", empty.renderedSkeleton());
    }

    @Test
    void sliceFailingTestCollectsFieldReferencedOnlyInsideLocalHelper() {
        String testSource = """
                package com.acme;
                import org.junit.jupiter.api.Test;
                class HelperFieldTest {
                    private int helperOnlyThreshold = 42;
                    private int unusedField = 7;

                    private int computeThreshold(int v) {
                        return v + helperOnlyThreshold;
                    }

                    @Test
                    void checksComputed() {
                        int actual = computeThreshold(1);
                        assertEquals(43, actual);
                    }
                }
                """;
        int failingLine = findLine(testSource, "assertEquals(43, actual);");
        var res = JavaMethodSlicer.sliceFailingTest("src/test/java/com/acme/HelperFieldTest.java", testSource, failingLine, null);
        assertTrue(res.externalDependencies().stream()
                .anyMatch(d -> "TEST_FIELD".equals(d.kind()) && d.symbolName().contains("helperOnlyThreshold")),
                "Field referenced only by resolved local helper must be captured in E");
        assertFalse(res.externalDependencies().stream()
                .anyMatch(d -> d.symbolName().contains("unusedField")));
    }

    private static int findLine(String src, String needle) {
        String[] lines = src.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(needle)) return i + 1;
        }
        throw new AssertionError("Needle not found: " + needle);
    }
}
