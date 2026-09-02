package com.evolution.benchmark.frontend;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class FrontendCallResolutionTest {

    @Test
    void bothFrontendsResolveJdkOverloadTargetsWithCompleteSpans() throws URISyntaxException {
        Path sourceRoot = Path.of(getClass().getResource("/fixtures/project").toURI());
        FrontendRequest request = new FrontendRequest(sourceRoot, List.of(), "CONFIG_A");

        for (SemanticFrontend frontend : List.of(new JavaParserFrontend(), new OpenRewriteFrontend())) {
            List<Observation> observations = frontend.analyze(request).observations();
            Observation get = observations.stream().filter(o -> o.targetIdentity().contains("java.util.List.get"))
                    .findFirst().orElseThrow();
            assertTrue(get.state() == ObservationState.RESOLVED, frontend.id());
            assertTrue(get.span().startLine() > 0 && get.span().endColumn() > get.span().startColumn(), frontend.id());
        }
    }
}
