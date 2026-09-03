package com.evolution.analysis.frontend;

import com.evolution.analysis.contract.semantic.*;
import java.util.Map;
import java.util.Optional;

/** A rejected request carries a stable diagnostic, never a successful empty result. */
public final class FrontendInputException extends IllegalArgumentException {
    private final Diagnostic diagnostic;
    public FrontendInputException(String code, String message) {
        super(message);
        diagnostic = new Diagnostic(DiagnosticSeverity.ERROR, code, message, Optional.empty(), Map.of());
    }
    public Diagnostic diagnostic() { return diagnostic; }
}
