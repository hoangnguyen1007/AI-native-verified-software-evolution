package com.evolution.analysis.input;

import java.nio.file.Path;

/** Replaceable passive provider for an explicitly configured analyzed JDK. */
public interface PlatformSymbolProvider {
    PlatformSymbolResult acquire(Path configuredJdkHome, PlatformSymbolRequest request);
}
