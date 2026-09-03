package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.identity.SourceDocumentIdentity;
import com.evolution.analysis.contract.source.SourceSpan;
import com.github.javaparser.ast.Node;
import java.util.ArrayList;
import java.util.List;

final class OriginalSource {
    private final SourceDocumentIdentity document;
    private final String text;
    private final List<Integer> lines = new ArrayList<>();
    OriginalSource(SourceDocumentIdentity document, String text) {
        this.document = document; this.text = text; lines.add(0);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                lines.add(i + 1);
            } else if (c == '\n') lines.add(i + 1);
        }
    }
    SourceSpan span(Node node) {
        var range = node.getRange().orElseThrow(() -> new IllegalArgumentException("missing source range"));
        int start = offset(range.begin.line, range.begin.column);
        int last = offset(range.end.line, range.end.column);
        int end = last + 1;
        // JavaParser maps an inclusive translated end to the START of its original Unicode escape.
        if (last < text.length() && text.charAt(last) == '\\') {
            int digits = last + 1;
            while (digits < text.length() && text.charAt(digits) == 'u') digits++;
            if (digits > last + 1 && digits + 4 <= text.length()) {
                boolean hexadecimal = true;
                for (int i = digits; i < digits + 4; i++) hexadecimal &= Character.digit(text.charAt(i), 16) >= 0;
                if (hexadecimal) end = digits + 4;
            }
        }
        return span(start, end);
    }
    SourceSpan span(int start, int end) {
        if (start < 0 || end > text.length() || end <= start) throw new IllegalArgumentException("invalid source offsets");
        int a = line(start), b = line(end);
        return new SourceSpan(document, a + 1, start - lines.get(a) + 1, b + 1, end - lines.get(b) + 1);
    }
    private int line(int offset) {
        int index = java.util.Collections.binarySearch(lines, offset);
        return index >= 0 ? index : -index - 2;
    }
    int offset(int line, int column) {
        if (line < 1 || line > lines.size() || column < 1) throw new IllegalArgumentException("source position outside document");
        int result = lines.get(line - 1) + column - 1;
        int limit = line == lines.size() ? text.length() : lines.get(line) - 1;
        if (result > limit) throw new IllegalArgumentException("source column outside line");
        return result;
    }
    int start(Node node) { var span = span(node); return offset(span.startLine(), span.startColumn()); }
    String slice(SourceSpan span) {
        if (!document.equals(span.document())) throw new IllegalArgumentException("foreign source span");
        return text.substring(offset(span.startLine(), span.startColumn()), offset(span.endLine(), span.endColumn()));
    }
}
