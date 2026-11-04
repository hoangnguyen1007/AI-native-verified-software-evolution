package com.evolution.analysis.frontend;

import com.evolution.analysis.contract.semantic.Diagnostic;
import com.evolution.analysis.contract.source.SourceSpan;
import java.util.*;

/** Compares the existing canonical JSON order without serializing observation payloads.
 * Field order, escaped strings, nulls and numeric token terminators deliberately match
 * CanonicalJson: persisted frontend/evidence digests must not change for this optimization. */
final class ObservationOrder {
    private ObservationOrder() {}

    static int compare(ObservationRecord a, ObservationRecord b) {
        if (a == b) return 0;
        int c = a.attribution().name().compareTo(b.attribution().name());
        if (c == 0) c = a.category().compareTo(b.category());
        if (c == 0) c = diagnostics(a.diagnostics(), b.diagnostics());
        if (c == 0) c = a.document().compareTo(b.document());
        // A quoted identity sorts before the unquoted null token.
        if (c == 0) c = optional(a.mappedOccurrence(), b.mappedOccurrence(), Comparator.naturalOrder(), -1);
        if (c == 0) c = a.origin().name().compareTo(b.origin().name());
        if (c == 0) c = a.provenance().name().compareTo(b.provenance().name());
        if (c == 0) c = string(a.reference(), b.reference());
        if (c == 0) c = optional(a.span(), b.span(), ObservationOrder::span, 1);
        return c;
    }

    private static <T> int optional(Optional<T> a, Optional<T> b, Comparator<? super T> order, int presentOrder) {
        if (a.isPresent() != b.isPresent()) return a.isPresent() ? presentOrder : -presentOrder;
        return a.isEmpty() ? 0 : order.compare(a.orElseThrow(), b.orElseThrow());
    }

    private static int diagnostics(List<Diagnostic> a, List<Diagnostic> b) {
        if (a == b) return 0;
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            var left = a.get(i); var right = b.get(i);
            if (left == right) continue;
            int c = left.code().compareTo(right.code());
            if (c == 0) c = details(left.details(), right.details());
            if (c == 0) c = string(left.message(), right.message());
            if (c == 0) c = left.severity().name().compareTo(right.severity().name());
            if (c == 0) c = optional(left.span(), right.span(), ObservationOrder::span, 1);
            if (c != 0) return c;
        }
        if (a.size() == b.size()) return 0;
        // At the first array member ']' sorts before '{'. After a common member,
        // the closing ']' sorts after the continuation comma.
        if (a.isEmpty() || b.isEmpty()) return Integer.compare(a.size(), b.size());
        return Integer.compare(b.size(), a.size());
    }

    private static int details(Map<String, String> a, Map<String, String> b) {
        if (a.equals(b)) return 0;
        var left = new TreeMap<>(a).entrySet().iterator();
        var right = new TreeMap<>(b).entrySet().iterator();
        while (left.hasNext() && right.hasNext()) {
            var x = left.next(); var y = right.next();
            int c = string(x.getKey(), y.getKey());
            if (c == 0) c = string(x.getValue(), y.getValue());
            if (c != 0) return c;
        }
        return Integer.compare(b.size(), a.size());
    }

    private static int span(SourceSpan a, SourceSpan b) {
        if (a == b) return 0;
        int c = a.document().compareTo(b.document());
        if (c == 0) c = number(a.endColumn(), b.endColumn(), ',');
        if (c == 0) c = number(a.endLine(), b.endLine(), ',');
        if (c == 0) c = number(a.startColumn(), b.startColumn(), ',');
        if (c == 0) c = number(a.startLine(), b.startLine(), '}');
        return c;
    }

    private static int number(int a, int b, char terminator) {
        if (a == b) return 0;
        return (Integer.toString(a) + terminator).compareTo(Integer.toString(b) + terminator);
    }

    private static int string(String a, String b) {
        if (a == b || a.equals(b)) return 0;
        var left = new EscapedString(a); var right = new EscapedString(b);
        for (;;) {
            int x = left.next(), y = right.next();
            if (x != y) return Integer.compare(x, y);
            if (x == -1) return 0;
        }
    }

    /** Virtual escaped JSON contents including the closing quote, with constant working space. */
    private static final class EscapedString {
        private static final String HEX = "0123456789abcdef";
        private final String value;
        private int offset, escapeOffset;
        private String escape = "";
        EscapedString(String value) { this.value = value; }
        int next() {
            if (escapeOffset < escape.length()) return escape.charAt(escapeOffset++);
            if (offset == value.length()) { offset++; return '"'; }
            if (offset > value.length()) return -1;
            char c = value.charAt(offset++);
            escape = switch (c) {
                case '"' -> "\\\"";
                case '\\' -> "\\\\";
                case '\b' -> "\\b";
                case '\f' -> "\\f";
                case '\n' -> "\\n";
                case '\r' -> "\\r";
                case '\t' -> "\\t";
                default -> c < 0x20 ? "\\u00" + HEX.charAt(c >>> 4) + HEX.charAt(c & 15) : "";
            };
            escapeOffset = 1;
            return escape.isEmpty() ? c : escape.charAt(0);
        }
    }
}
