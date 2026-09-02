package com.evolution.benchmark.frontend;

public interface SemanticFrontend {
    String id();
    FrontendResult analyze(FrontendRequest request);
}
