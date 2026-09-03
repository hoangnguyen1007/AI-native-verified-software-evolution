package com.evolution.analysis.frontend;

import com.evolution.analysis.contract.analysis.ClasspathEntry;
import com.evolution.analysis.contract.analysis.ClasspathEntryKind;
import java.nio.file.Path;
import java.util.Objects;

/** Runtime handle; adapters must verify the platform content before attribution. */
public record PlatformInput(ClasspathEntry entry, Path javaHome) {
    public PlatformInput {
        Objects.requireNonNull(entry);
        javaHome = Objects.requireNonNull(javaHome).toAbsolutePath().normalize();
        if (entry.kind() != ClasspathEntryKind.JDK_MODULE) throw new FrontendInputException("frontend.platform-kind", "Platform entry must identify a JDK view");
    }
}
