package com.evolution.backend.analysis;

import java.util.Locale;

/**
 * Explicit analysis execution profile.
 * FULL preserves 100% bit-for-bit canonical deep evidence.
 * FAST_ARCHITECTURE skips intra-method micro-syntax and raw source base64 bloat
 * while preserving full architectural structure, Spring wiring, routes, cross-module dependencies,
 * policy evaluation, metrics, and explicit scope provenance.
 */
public enum AnalysisMode {
    FULL,
    FAST_ARCHITECTURE;

    public static AnalysisMode parse(String value) {
        if (value == null || value.isBlank()) return FULL;
        return switch (value.trim().toUpperCase(Locale.ROOT).replace('-', '_')) {
            case "FULL", "DEEP", "COMPLETE" -> FULL;
            case "FAST", "FAST_ARCHITECTURE", "TURBO" -> FAST_ARCHITECTURE;
            default -> throw new IllegalArgumentException("Unsupported analysis mode: " + value);
        };
    }

    public boolean isFastArchitecture() {
        return this == FAST_ARCHITECTURE;
    }
}
