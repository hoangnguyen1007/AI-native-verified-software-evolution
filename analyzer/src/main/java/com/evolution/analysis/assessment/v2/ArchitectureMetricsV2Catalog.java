package com.evolution.analysis.assessment.v2;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.*;

/**
 * Additive v2 metric catalog documenting all 25 sub-metrics used by Architecture Scoring v2.
 * Leaves the frozen 19-entry M5 {@code MetricCatalog} completely untouched.
 */
public final class ArchitectureMetricsV2Catalog {
    public static final VersionedIdentifier VERSION = new VersionedIdentifier("architecture.metrics-catalog", "v2.0");

    public record Entry(
            String metricId,
            String dimensionId,
            String displayName,
            String unit,
            String iso25010Mapping,
            String formula,
            String literatureAnchor,
            CalibrationCurve curve) {
        public ContentDigest identity() {
            return ContentDigest.sha256Utf8(CanonicalJson.write(List.of(VERSION, this)));
        }
    }

    private static final Map<String, String> LITERATURE = Map.ofEntries(
            Map.entry(ArchitectureScoreModelV2.SUB_D1_PACKAGE_RC, "Sonargraph Maintainability Level (ML_alt); Martin ADP; Mo/Cai Package Cycle"),
            Map.entry(ArchitectureScoreModelV2.SUB_D1_TYPE_RC, "Sonargraph Relative Cyclicity; Mo/Cai Clique Anti-Pattern"),
            Map.entry(ArchitectureScoreModelV2.SUB_D1_MODULE_RC, "Lakos Physical Design Levelization; Spring Modulith Acyclic Modules"),
            Map.entry(ArchitectureScoreModelV2.SUB_D1_TANGLE_EDGE, "Structure101 Tangle Ratio (Feedback Edge Density)"),
            Map.entry(ArchitectureScoreModelV2.SUB_D2_DECOUPLING_LEVEL, "Mo, Cai, Kazman, Xiao & Feng (ICSE 2016) Design Rule Hierarchy Decoupling Level"),
            Map.entry(ArchitectureScoreModelV2.SUB_D2_ENCAPSULATION, "Parnas Information Hiding; Spring Modulith Internal Package Encapsulation"),
            Map.entry(ArchitectureScoreModelV2.SUB_D2_TURBOMQ, "Mitchell & Mancoridis (IEEE TSE 2006) Bunch TurboMQ Cluster Factor"),
            Map.entry(ArchitectureScoreModelV2.SUB_D3_NCCD, "John Lakos (1996) Large-Scale C++ Software Design — Balanced Binary Tree NCCD"),
            Map.entry(ArchitectureScoreModelV2.SUB_D3_PROPAGATION_COST, "MacCormack, Rusnak & Baldwin (Management Science 2006) Size-Normalized Propagation Cost"),
            Map.entry(ArchitectureScoreModelV2.SUB_D3_EFF_COUPLING, "Structure101 Fat Coupling; Cognitive Working-Memory Fan-Out Budget"),
            Map.entry(ArchitectureScoreModelV2.SUB_D4_SDP, "Robert C. Martin Stable Dependencies Principle (SDP)"),
            Map.entry(ArchitectureScoreModelV2.SUB_D4_MAIN_SEQUENCE, "Robert C. Martin Stable Abstractions Principle (SAP) & Distance from Main Sequence"),
            Map.entry(ArchitectureScoreModelV2.SUB_D4_DSM_BACK_EDGES, "Lakos Levelization; Sangal/Sonargraph DSM Back-Edge Ratio"),
            Map.entry(ArchitectureScoreModelV2.SUB_D4_INFERRED_LAYERING, "Carola Lilienthal Modularity Maturity Index (MMI) Hierarchy; Clean/Hexagonal Layering"),
            Map.entry(ArchitectureScoreModelV2.SUB_D5_CROSSING_HUBS, "Mo, Cai & Kazman (IEEE TSE) Crossing & Hub Architectural Anti-Pattern"),
            Map.entry(ArchitectureScoreModelV2.SUB_D5_FAT_COMPONENTS, "Structure101 Excess Structural Complexity (Fat); Lilienthal Large Class Ratio"),
            Map.entry(ArchitectureScoreModelV2.SUB_D5_GINI, "Vasa, Lumpe, Branch & Nierstrasz (2009) Gini Coefficient in Software Engineering"),
            Map.entry(ArchitectureScoreModelV2.SUB_D6_SPRING_FIELD_INJ, "Spring Framework Reference & ArchUnit Constructor-Injection Best Practice"),
            Map.entry(ArchitectureScoreModelV2.SUB_D6_SPRING_BEAN_CYCLES, "Spring Modulith / Boot 2.6+ Circular Reference Prohibition; ADR-004 Conditional Soundness"),
            Map.entry(ArchitectureScoreModelV2.SUB_D6_SPRING_WIRING, "Spring Container Ambiguous/Unsatisfied Injection Diagnosis"),
            Map.entry(ArchitectureScoreModelV2.SUB_D6_SPRING_BYPASS, "ArchUnit Layered Spring Boot Rule (Controller -> Service -> Repository)"),
            Map.entry(ArchitectureScoreModelV2.SUB_D6_POLYGLOT_CYCLES, "Sam Newman Building Microservices — Acyclic Synchronous Service Topology"),
            Map.entry(ArchitectureScoreModelV2.SUB_D6_POLYGLOT_COUPLING, "Distributed Monolith Temporal Coupling vs Event-Driven Choreography"),
            Map.entry(ArchitectureScoreModelV2.SUB_D6_POLYGLOT_CONTRACTS, "Contract-First Polyglot API Hygiene (OpenAPI / Protobuf / GraphQL)"),
            Map.entry(ArchitectureScoreModelV2.SUB_D7_POLICY_EXPOSURE, "ADR-002 / M6.3 Potential-Exposure Root-Deduplicated Policy Conformance"));

    private static final List<Entry> ENTRIES = buildEntries();

    private static List<Entry> buildEntries() {
        var model = ArchitectureScoreModelV2.standard();
        var list = new ArrayList<Entry>();
        for (var dim : model.dimensions()) {
            for (var sub : dim.subMetrics()) {
                list.add(new Entry(
                        sub.id(),
                        dim.id(),
                        sub.displayName(),
                        sub.unit(),
                        dim.iso25010Mapping(),
                        sub.formulaSummary(),
                        LITERATURE.getOrDefault(sub.id(), "Software Architecture Metrics"),
                        sub.curve()));
            }
        }
        list.sort(Comparator.comparing(Entry::metricId));
        return List.copyOf(list);
    }

    public static List<Entry> entries() {
        return ENTRIES;
    }

    public static Entry require(String metricId) {
        return ENTRIES.stream()
                .filter(e -> e.metricId().equals(metricId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown v2 metric ID: " + metricId));
    }

    public static ContentDigest identity() {
        return ContentDigest.sha256Utf8(CanonicalJson.write(List.of(VERSION, ENTRIES)));
    }

    private ArchitectureMetricsV2Catalog() {}
}
