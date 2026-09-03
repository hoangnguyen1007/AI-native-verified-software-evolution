package com.evolution.analysis.frontend;

import com.evolution.analysis.contract.analysis.ClasspathEntry;
import com.evolution.analysis.contract.analysis.ClasspathEntryKind;
import java.nio.file.Path;
import java.util.Objects;

/** Supplied JAR handle, not part of stable identity. Never an ambient classpath. */
public record BinaryInput(ClasspathEntry entry, Path path) {
    public BinaryInput {
        Objects.requireNonNull(entry);
        path = Objects.requireNonNull(path).toAbsolutePath().normalize();
        if (entry.kind() != ClasspathEntryKind.DEPENDENCY) throw new FrontendInputException("frontend.binary-kind", "This frontend requires explicit dependency JARs");
    }
}
