package com.evolution.benchmark.frontend;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class RepositoryEvaluationTest {
    @Test
    void summarizesResolvedAndUnresolvedFactsWithoutChangingDenominator() throws URISyntaxException {
        Path sourceRoot = Path.of(getClass().getResource("/fixtures/project").toURI());
        var summary = RepositoryEvaluation.summarize(new JavaParserFrontend().analyze(
                new FrontendRequest(sourceRoot, List.of(), "CONFIG_A")));

        assertEquals(2, summary.attempted());
        assertEquals(2, summary.resolved());
        assertEquals(0, summary.unresolved());
    }
}
