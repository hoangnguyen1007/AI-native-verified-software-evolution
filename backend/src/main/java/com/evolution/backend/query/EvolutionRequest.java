package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evolution.SnapshotComparison;
import com.evolution.analysis.evolution.SnapshotComparison.*;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.backend.analysis.CanonicalRecordReader;
import java.util.*;

/** The v1 submission pins immutable IDs. The worker supplies the registered readers and work policy. */
public record EvolutionRequest(String schema, Request comparison, List<ReportRef> reports,
                               List<BridgeRef> bridges, Optional<History> history) {
    public static final String VERSION = "architecture.evolution.request:v1";
    public EvolutionRequest {
        if (!VERSION.equals(schema)) throw new IllegalArgumentException("Unknown comparison request schema");
        Objects.requireNonNull(comparison);
        reports = reports.stream().sorted(Comparator.comparing(ReportRef::side).thenComparing(ReportRef::identity)
                .thenComparing(ReportRef::context)).toList();
        bridges = bridges.stream().sorted(Comparator.comparing(BridgeRef::side)).toList();
        Objects.requireNonNull(history);
        if (new HashSet<>(reports).size() != reports.size() ||
                bridges.stream().map(BridgeRef::side).distinct().count() != bridges.size() ||
                !bridges.isEmpty() && !comparison.selection().evidence())
            throw new IllegalArgumentException("Invalid comparison artifact selection");
        if (history.isPresent()) {
            var steps = history.orElseThrow().steps();
            if (!comparison.selection().findings() || !steps.getLast().revision().graph().equals(comparison.right()) ||
                    !steps.get(steps.size() - 2).revision().graph().equals(comparison.left()))
                throw new IllegalArgumentException("History must end at the selected finding pair");
            for (var side : Side.values()) {
                var graph = steps.get(side == Side.LEFT ? steps.size() - 2 : steps.size() - 1).revision();
                var selected = new HashSet<HistoryReport>();
                reports.stream().filter(report -> report.side() == side)
                        .forEach(report -> selected.add(new HistoryReport(report.identity(), report.context())));
                if (!selected.equals(Set.copyOf(graph.reports()))) throw new IllegalArgumentException("History report mismatch");
            }
        }
    }

    public ContentDigest identity() {
        return CanonicalJson.sha256(List.of(VERSION, SnapshotComparison.VERSION, this));
    }

    public static EvolutionRequest decode(Object input) {
        var fields = GraphJson.object(input);
        if (!Set.of("schema", "comparison", "reports", "bridges", "history").containsAll(fields.keySet()))
            throw new IllegalArgumentException("Unknown comparison request field");
        var normalized = new TreeMap<>(fields);
        normalized.putIfAbsent("reports", List.of());
        normalized.putIfAbsent("bridges", List.of());
        normalized.putIfAbsent("history", null);
        try {
            var request = CanonicalRecordReader.read(normalized, EvolutionRequest.class);
            var comparison = GraphJson.object(normalized.get("comparison"));
            var selection = GraphJson.object(comparison.get("selection"));
            for (var key : List.of("sourceSets","nodeKinds","relationshipKinds")) {
                var values = GraphJson.array(selection.get(key));
                if (new HashSet<>(values).size() != values.size()) throw new IllegalArgumentException("Duplicate selection value");
            }
            for (var raw : GraphJson.array(normalized.get("bridges"))) {
                var publication = GraphJson.object(GraphJson.object(raw).get("publication"));
                if (!(publication.get("size") instanceof Number)) throw new IllegalArgumentException("Expected bridge size");
            }
            return request;
        } catch (ClassCastException | ArithmeticException invalid) {
            throw new IllegalArgumentException("Invalid comparison request");
        }
    }
}
