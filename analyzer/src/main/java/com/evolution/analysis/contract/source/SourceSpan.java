package com.evolution.analysis.contract.source;

import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.identity.SourceDocumentIdentity;

/**
 * Complete one-based source coordinates with an end-exclusive position.
 *
 * <p>The document identity prevents a line/column pair from being detached from its source.
 */
public record SourceSpan(
        SourceDocumentIdentity document,
        int startLine,
        int startColumn,
        int endLine,
        int endColumn)
        implements Comparable<SourceSpan> {

    public SourceSpan {
        ContractChecks.notNull(document, "source document");
        if (startLine < 1 || startColumn < 1 || endLine < 1 || endColumn < 1) {
            throw new IllegalArgumentException("source coordinates are one-based");
        }
        if (endLine < startLine || endLine == startLine && endColumn <= startColumn) {
            throw new IllegalArgumentException("source span end must be after its start");
        }
    }

    @Override
    public int compareTo(SourceSpan other) {
        int comparison = document.compareTo(other.document);
        if (comparison != 0) return comparison;
        comparison = Integer.compare(startLine, other.startLine);
        if (comparison != 0) return comparison;
        comparison = Integer.compare(startColumn, other.startColumn);
        if (comparison != 0) return comparison;
        comparison = Integer.compare(endLine, other.endLine);
        return comparison != 0 ? comparison : Integer.compare(endColumn, other.endColumn);
    }
}
