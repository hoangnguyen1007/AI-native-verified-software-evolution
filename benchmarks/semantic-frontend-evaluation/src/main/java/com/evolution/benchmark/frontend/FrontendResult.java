package com.evolution.benchmark.frontend;

import java.util.List;

public record FrontendResult(String frontendId, List<Observation> observations, List<String> diagnostics, long elapsedMillis) {
    public FrontendResult {
        observations = List.copyOf(observations);
        diagnostics = List.copyOf(diagnostics);
    }
}
