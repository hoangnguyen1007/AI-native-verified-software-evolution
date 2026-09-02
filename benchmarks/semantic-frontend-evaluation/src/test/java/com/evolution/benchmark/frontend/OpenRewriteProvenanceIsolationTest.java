package com.evolution.benchmark.frontend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenRewriteProvenanceIsolationTest {
    @Test
    void duplicateTextDoesNotTurnResolvedAttributionIntoUnresolvedSemanticFact() throws URISyntaxException {
        Path sourceRoot = Path.of(getClass().getResource("/fixtures/duplicate").toURI());

        var result = new OpenRewriteFrontend().analyze(new FrontendRequest(sourceRoot, List.of(), "CONFIG_A"));
        var trims = result.observations().stream().filter(observation -> observation.targetIdentity().contains("trim")).toList();

        assertEquals(2, trims.size());
        assertTrue(trims.stream().allMatch(observation -> observation.state() == ObservationState.RESOLVED));
    }
}
