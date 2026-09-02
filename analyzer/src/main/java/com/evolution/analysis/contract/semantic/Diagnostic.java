package com.evolution.analysis.contract.semantic;

import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.SourceSpan;
import java.util.Map;
import java.util.Optional;

/** Structured diagnostic with optional complete source evidence and deterministic details. */
public record Diagnostic(
        DiagnosticSeverity severity,
        String code,
        String message,
        Optional<SourceSpan> span,
        Map<String, String> details)
        implements Comparable<Diagnostic> {

    public Diagnostic {
        ContractChecks.notNull(severity, "diagnostic severity");
        code = ContractChecks.namespacedId(code, "diagnostic code");
        message = ContractChecks.text(message, "diagnostic message");
        span = ContractChecks.notNull(span, "diagnostic span");
        details = ContractChecks.sortedStringMap(details, "diagnostic details");
    }

    @Override
    public int compareTo(Diagnostic other) {
        int comparison = severity.compareTo(other.severity);
        if (comparison != 0) return comparison;
        comparison = code.compareTo(other.code);
        if (comparison != 0) return comparison;
        comparison = message.compareTo(other.message);
        if (comparison != 0) return comparison;
        comparison = CanonicalJson.write(span).compareTo(CanonicalJson.write(other.span));
        return comparison != 0
                ? comparison
                : CanonicalJson.write(details).compareTo(CanonicalJson.write(other.details));
    }
}
