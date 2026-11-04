package com.evolution.analysis.frontend;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.SourceSpan;
import java.lang.management.ManagementFactory;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ObservationRecordTest {
    private static final SourceDocumentIdentity DOCUMENT = new SourceDocumentIdentity("source:" + ContentDigest.sha256Utf8("source").value());

    private static ObservationRecord observation(int line, String reference, List<Diagnostic> diagnostics,
                                                  SemanticStatus status, boolean mapped) {
        var span = new SourceSpan(DOCUMENT, line, 1, line, 12);
        return new ObservationRecord(DOCUMENT, new RelationshipKind("java.calls"), Optional.of(span), status,
                mapped ? ObservationRecord.EvidenceState.VERIFIED : ObservationRecord.EvidenceState.MISSING,
                ObservationRecord.EvidenceState.VERIFIED,
                mapped ? Optional.of(new OccurrenceIdentity("occurrence:" + ContentDigest.sha256Utf8(reference).value())) : Optional.empty(),
                reference, diagnostics);
    }

    @Test void orderingPreservesCanonicalBytesIncludingEscapesNumericPrefixesAndOptionalValues() {
        var values = new ArrayList<ObservationRecord>();
        var spans = List.of(Optional.<SourceSpan>empty(), Optional.of(new SourceSpan(DOCUMENT, 1, 1, 1, 2)),
                Optional.of(new SourceSpan(DOCUMENT, 10, 1, 10, 20)));
        var messages = List.of("a", "a b", "a\"z", "a\\z", "a\nz", "a\tz", "a\u0001z", "Tiếng Việt 😀");
        for (var span : spans) for (String message : messages) {
            var diagnostic = new Diagnostic(DiagnosticSeverity.WARNING, "java.unresolved", message, span, Map.of("reason", message));
            values.add(observation(1, message, List.of(diagnostic), SemanticStatus.UNRESOLVED, false));
            values.add(observation(10, message, List.of(diagnostic), SemanticStatus.UNRESOLVED, false));
            values.add(observation(100, message, List.of(), SemanticStatus.RESOLVED, true));
            values.add(observation(1, message, List.of(diagnostic,
                    new Diagnostic(DiagnosticSeverity.ERROR, "java.error", "error", span, Map.of())), SemanticStatus.ERROR, false));
        }
        values.add(new ObservationRecord(DOCUMENT, new RelationshipKind("java.reads-field"), Optional.empty(),
                SemanticStatus.UNRESOLVED, ObservationRecord.EvidenceState.MISSING, ObservationRecord.EvidenceState.MISSING,
                Optional.empty(), "", List.of(new Diagnostic(DiagnosticSeverity.WARNING, "java.unresolved", "missing", Optional.empty(), Map.of()))));
        for (var left : values) for (var right : values)
            assertEquals(Integer.signum(CanonicalJson.write(left).compareTo(CanonicalJson.write(right))),
                    Integer.signum(left.compareTo(right)), () -> "Canonical observation order changed: " + left + " / " + right);
        var expected = values.stream().sorted(Comparator.comparing(CanonicalJson::write)).toList();
        var actual = values.stream().sorted().toList();
        assertEquals(CanonicalJson.sha256(expected), CanonicalJson.sha256(actual));
    }

    @Test void comparisonsDoNotAllocateInProportionToObservationPayload() {
        var left = observation(1, "x".repeat(65_536), List.of(), SemanticStatus.RESOLVED, true);
        var right = observation(2, "y".repeat(65_536), List.of(), SemanticStatus.RESOLVED, true);
        for (int i = 0; i < 8; i++) assertNotEquals(0, left.compareTo(right));
        if (ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean
                && bean.isThreadAllocatedMemorySupported()) {
            bean.setThreadAllocatedMemoryEnabled(true);
            long thread = Thread.currentThread().threadId(), before = bean.getThreadAllocatedBytes(thread);
            int compared = 0;
            for (int i = 0; i < 256; i++) compared += Integer.signum(left.compareTo(right));
            long allocated = bean.getThreadAllocatedBytes(thread) - before;
            assertNotEquals(0, compared);
            assertTrue(allocated < 2_000_000, "256 comparisons allocated " + allocated + " bytes");
        }
    }
}
