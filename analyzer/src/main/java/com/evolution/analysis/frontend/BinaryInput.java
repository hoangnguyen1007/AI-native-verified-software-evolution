package com.evolution.analysis.frontend;

import com.evolution.analysis.contract.analysis.ClasspathEntry;
import com.evolution.analysis.contract.analysis.ClasspathEntryKind;
import com.evolution.analysis.contract.identity.ModuleIdentity;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Supplied JAR handle, not part of stable identity. Never an ambient classpath. */
public record BinaryInput(ClasspathEntry entry, Path path, Optional<ModuleIdentity> reactorModule) {
    public BinaryInput(ClasspathEntry entry, Path path) {
        this(entry, path, Optional.empty());
    }

    public BinaryInput {
        Objects.requireNonNull(entry);
        path = Objects.requireNonNull(path).toAbsolutePath().normalize();
        reactorModule = Objects.requireNonNull(reactorModule);
        if (entry.kind() != ClasspathEntryKind.DEPENDENCY
                && entry.kind() != ClasspathEntryKind.MODULE_OUTPUT) {
            throw new FrontendInputException("frontend.binary-kind", "Binary input must be a dependency or reactor output JAR");
        }
        if ((entry.kind() == ClasspathEntryKind.MODULE_OUTPUT) != reactorModule.isPresent()) {
            throw new FrontendInputException("frontend.binary-origin", "Only reactor output JARs carry a module identity");
        }
    }
}
