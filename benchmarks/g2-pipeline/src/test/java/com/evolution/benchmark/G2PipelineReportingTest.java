package com.evolution.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import com.evolution.analysis.frontend.FrontendRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class G2PipelineReportingTest {
    @TempDir Path temporary;

    @Test
    void emptyFrontendRunStillReportsTheEntireRegisteredCategoryDenominator() {
        var metrics = G2BenchmarkRunner.categoryMetrics(List.of());

        assertEquals(FrontendRequest.CATEGORIES.size(), metrics.size());
        FrontendRequest.CATEGORIES.forEach(category -> {
            var counts = metrics.get("java." + category);
            assertNotNull(counts);
            assertEquals(0L, counts.get("ATTEMPTED"));
            assertEquals(0L, counts.get("UNMAPPED"));
        });
    }

    @Test
    void reportRendererUsesDeclaredTsvInputsAndNeverSilentlyEmitsAnEmptyBrokenTable() throws Exception {
        Files.writeString(temporary.resolve("category-metrics.tsv"),
                "CATEGORY\tATTEMPTED\tEMITTED\tUNMAPPED\tRESOLVED\njava.calls\t3\t2\t1\t2\n");
        Files.writeString(temporary.resolve("reason-metrics.tsv"),
                "REASON\tCOUNT\nbuild.dependency:MISSING_ARTIFACT\t1\n");
        Files.writeString(temporary.resolve("pipeline-summary.tsv"),
                "METRIC\tVALUE\nfrontend.runs\t1\nfrontend.observations\t3\n");
        Path report = temporary.resolve("README.md");

        G2ReportRenderer.render(temporary, report, "Fixture");

        String text = Files.readString(report);
        assertTrue(text.contains("| java.calls | 3 | 2 | 1 | 2 |"));
        assertTrue(text.contains("build.dependency:MISSING_ARTIFACT"));
        assertFalse(text.contains("Error rendering"));
    }

    @Test
    void reportRendererStatesWhenNoSourceSetReachedTheFrontend() throws Exception {
        Files.writeString(temporary.resolve("category-metrics.tsv"),
                "CATEGORY\tATTEMPTED\tEMITTED\tUNMAPPED\tRESOLVED\njava.calls\t0\t0\t0\t0\n");
        Files.writeString(temporary.resolve("reason-metrics.tsv"),
                "REASON\tCOUNT\nfrontend.input:CLASSPATH_PROBLEM\t2\n");
        Files.writeString(temporary.resolve("pipeline-summary.tsv"),
                "METRIC\tVALUE\nfrontend.runs\t0\nfrontend.observations\t0\n");
        Path report = temporary.resolve("README.md");

        G2ReportRenderer.render(temporary, report, "Fixture");

        assertTrue(Files.readString(report).contains("No source set reached the semantic frontend"));
    }
}
