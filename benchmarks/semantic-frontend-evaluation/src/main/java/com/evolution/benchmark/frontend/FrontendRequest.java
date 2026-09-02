package com.evolution.benchmark.frontend;

import java.nio.file.Path;
import java.util.List;

public record FrontendRequest(Path sourceRoot, List<Path> classpath, String configurationId) {
    public FrontendRequest {
        sourceRoot = sourceRoot.toAbsolutePath().normalize();
        classpath = List.copyOf(classpath);
    }
}
