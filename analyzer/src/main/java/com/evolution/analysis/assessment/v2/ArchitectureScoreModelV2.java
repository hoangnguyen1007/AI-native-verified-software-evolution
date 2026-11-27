package com.evolution.analysis.assessment.v2;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.math.BigDecimal;
import java.util.*;

/**
 * Versioned, content-addressed Architecture Scoring v2 model.
 *
 * <p>Additive alongside frozen M6 v1 ({@code architecture.score-formula:m6.3-exposure-v1}).
 * Uses a single fixed canonical weight vector across 7 orthogonal architecture dimensions
 * grounded in ISO/IEC 25010:2023, Lakos (NCCD), Mo/Cai/Kazman (DL &amp; Crossings),
 * MacCormack (Propagation Cost), Lilienthal (MMI), Robert C. Martin (ADP/SDP/SAP),
 * Mitchell &amp; Mancoridis (TurboMQ), Sonargraph (Relative Cyclicity), Structure101 (Fat/Tangle),
 * Spring Modulith / ArchUnit, and Polyglot Microservice coupling principles.
 */
public record ArchitectureScoreModelV2(
        VersionedIdentifier model,
        List<DimensionSpec> dimensions,
        Map<String, BigDecimal> policySeverityWeights,
        BigDecimal uncertaintySlope,
        BigDecimal minParseCompleteness,
        BigDecimal minInternalEdgeCompleteness) {

    public static final VersionedIdentifier FORMULA = new VersionedIdentifier("architecture.score-formula", "v2.0");
    public static final VersionedIdentifier DEFAULT_MODEL = new VersionedIdentifier("architecture.score-model", "v2.0-default");
    public static final String CALIBRATION = "EMPIRICALLY_GROUNDED_PROVISIONAL";

    // Canonical Dimension IDs
    public static final String DIM_ACYCLIC = "arch.acyclic-integrity";
    public static final String DIM_MODULARITY = "arch.modularity-cohesion";
    public static final String DIM_COUPLING = "arch.coupling-propagation";
    public static final String DIM_HIERARCHY = "arch.hierarchy-direction";
    public static final String DIM_HOTSPOTS = "arch.complexity-hotspots";
    public static final String DIM_FRAMEWORK_POLYGLOT = "arch.framework-polyglot";
    public static final String DIM_POLICY = "arch.policy-conformance";

    // Canonical Sub-Metric IDs
    public static final String SUB_D1_PACKAGE_RC = "d1.package-rc";
    public static final String SUB_D1_TYPE_RC = "d1.type-rc";
    public static final String SUB_D1_MODULE_RC = "d1.module-rc";
    public static final String SUB_D1_TANGLE_EDGE = "d1.tangle-edge-ratio";

    public static final String SUB_D2_DECOUPLING_LEVEL = "d2.decoupling-level";
    public static final String SUB_D2_ENCAPSULATION = "d2.encapsulation-ratio";
    public static final String SUB_D2_TURBOMQ = "d2.package-turbomq";

    public static final String SUB_D3_NCCD = "d3.nccd";
    public static final String SUB_D3_PROPAGATION_COST = "d3.propagation-cost";
    public static final String SUB_D3_EFF_COUPLING = "d3.eff-coupling-excess";

    public static final String SUB_D4_SDP = "d4.sdp-conformance";
    public static final String SUB_D4_MAIN_SEQUENCE = "d4.main-sequence";
    public static final String SUB_D4_DSM_BACK_EDGES = "d4.dsm-levelization";
    public static final String SUB_D4_INFERRED_LAYERING = "d4.inferred-layering";

    public static final String SUB_D5_CROSSING_HUBS = "d5.crossing-hubs";
    public static final String SUB_D5_FAT_COMPONENTS = "d5.fat-components";
    public static final String SUB_D5_GINI = "d5.coupling-gini";

    public static final String SUB_D6_SPRING_FIELD_INJ = "d6.spring.field-injection";
    public static final String SUB_D6_SPRING_BEAN_CYCLES = "d6.spring.bean-cycles";
    public static final String SUB_D6_SPRING_WIRING = "d6.spring.wiring-hygiene";
    public static final String SUB_D6_SPRING_BYPASS = "d6.spring.controller-bypass";
    public static final String SUB_D6_POLYGLOT_CYCLES = "d6.polyglot.service-cycles";
    public static final String SUB_D6_POLYGLOT_COUPLING = "d6.polyglot.sync-coupling";
    public static final String SUB_D6_POLYGLOT_CONTRACTS = "d6.polyglot.contract-hygiene";

    public static final String SUB_D7_POLICY_EXPOSURE = "d7.policy.exposure";

    public record SubMetricSpec(
            String id,
            String displayName,
            String unit,
            BigDecimal subWeight,
            CalibrationCurve curve,
            String formulaSummary) {
        public SubMetricSpec {
            id = ContractChecks.namespacedId(id, "sub-metric id");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(unit, "unit");
            subWeight = CalibrationCurve.normalize(Objects.requireNonNull(subWeight, "subWeight"));
            if (subWeight.signum() <= 0 || subWeight.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("subWeight must lie in (0, 1]");
            }
            Objects.requireNonNull(curve, "curve");
            Objects.requireNonNull(formulaSummary, "formulaSummary");
        }
    }

    public record DimensionSpec(
            String id,
            String displayName,
            String iso25010Mapping,
            BigDecimal weight,
            List<SubMetricSpec> subMetrics) {
        public DimensionSpec {
            id = ContractChecks.namespacedId(id, "dimension id");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(iso25010Mapping, "iso25010Mapping");
            weight = CalibrationCurve.normalize(Objects.requireNonNull(weight, "weight"));
            if (weight.signum() <= 0 || weight.compareTo(CalibrationCurve.HUNDRED) > 0) {
                throw new IllegalArgumentException("dimension weight must lie in (0, 100]");
            }
            subMetrics = List.copyOf(Objects.requireNonNull(subMetrics, "subMetrics"));
            if (subMetrics.isEmpty()) {
                throw new IllegalArgumentException("dimension requires at least one sub-metric");
            }
        }
    }

    public ArchitectureScoreModelV2 {
        Objects.requireNonNull(model, "model");
        dimensions = List.copyOf(Objects.requireNonNull(dimensions, "dimensions"));
        if (dimensions.isEmpty() || dimensions.size() > 10) {
            throw new IllegalArgumentException("Score model v2 requires 1..10 dimensions");
        }
        var seen = new HashSet<String>();
        for (var dim : dimensions) {
            if (!seen.add(dim.id())) {
                throw new IllegalArgumentException("Duplicate dimension id: " + dim.id());
            }
        }
        var sev = new TreeMap<String, BigDecimal>();
        Objects.requireNonNull(policySeverityWeights, "policySeverityWeights")
                .forEach((k, v) -> sev.put(k, CalibrationCurve.normalize(Objects.requireNonNull(v))));
        if (!sev.keySet().equals(Set.of("INFO", "WARNING", "ERROR"))
                || sev.get("INFO").signum() <= 0
                || sev.get("INFO").compareTo(sev.get("WARNING")) > 0
                || sev.get("WARNING").compareTo(sev.get("ERROR")) > 0) {
            throw new IllegalArgumentException("Monotone positive INFO <= WARNING <= ERROR weights required");
        }
        policySeverityWeights = Collections.unmodifiableMap(sev);
        uncertaintySlope = CalibrationCurve.normalize(Objects.requireNonNull(uncertaintySlope, "uncertaintySlope"));
        minParseCompleteness = CalibrationCurve.normalize(Objects.requireNonNull(minParseCompleteness, "minParseCompleteness"));
        minInternalEdgeCompleteness = CalibrationCurve.normalize(Objects.requireNonNull(minInternalEdgeCompleteness, "minInternalEdgeCompleteness"));
        if (uncertaintySlope.signum() < 0 || uncertaintySlope.compareTo(CalibrationCurve.HUNDRED) > 0
                || minParseCompleteness.signum() < 0 || minParseCompleteness.compareTo(BigDecimal.ONE) > 0
                || minInternalEdgeCompleteness.signum() < 0 || minInternalEdgeCompleteness.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Invalid confidence floor or uncertainty slope");
        }
    }

    public ContentDigest identity() {
        return ContentDigest.sha256Utf8(CanonicalJson.write(List.of(FORMULA, CALIBRATION, this)));
    }

    public Optional<DimensionSpec> dimension(String id) {
        return dimensions.stream().filter(d -> d.id().equals(id)).findFirst();
    }

    public Optional<SubMetricSpec> subMetric(String id) {
        for (var d : dimensions) {
            for (var s : d.subMetrics()) {
                if (s.id().equals(id)) return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    /** Canonical 7-dimension Architecture Health Score v2.0 model. */
    public static ArchitectureScoreModelV2 standard() {
        return new ArchitectureScoreModelV2(
                DEFAULT_MODEL,
                List.of(
                        new DimensionSpec(
                                DIM_ACYCLIC,
                                "Acyclicity & Tangle Freedom",
                                "ISO/IEC 25010:2023 Analyzability & Modularity (ADP, Sonargraph RC, Structure101 Tangle)",
                                new BigDecimal("20"),
                                List.of(
                                        new SubMetricSpec(SUB_D1_PACKAGE_RC, "Package Relative Cyclicity", "ratio",
                                                new BigDecimal("0.45"),
                                                CalibrationCurve.lowerIsBetter("0", "0.12", "0.35", "0.70", "92"),
                                                "sqrt(sum(|SCC_pkg|^2)) / max(N_pkg, 10)"),
                                        new SubMetricSpec(SUB_D1_TYPE_RC, "Type Relative Cyclicity", "ratio",
                                                new BigDecimal("0.30"),
                                                CalibrationCurve.lowerIsBetter("0", "0.08", "0.25", "0.60", "95"),
                                                "sqrt(sum(|SCC_type|^2)) / max(N_type, 20)"),
                                        new SubMetricSpec(SUB_D1_MODULE_RC, "Module Relative Cyclicity", "ratio",
                                                new BigDecimal("0.15"),
                                                CalibrationCurve.lowerIsBetter("0", "0.10", "0.25", "0.50", "60"),
                                                "sqrt(sum(|SCC_mod|^2)) / N_mod"),
                                        new SubMetricSpec(SUB_D1_TANGLE_EDGE, "Cyclic Edge Tangle Ratio", "ratio",
                                                new BigDecimal("0.10"),
                                                CalibrationCurve.lowerIsBetter("0", "0.05", "0.20", "0.50"),
                                                "|E_intra-cyclic-SCC| / |E_internal|"))),
                        new DimensionSpec(
                                DIM_MODULARITY,
                                "Modularity & Boundary Encapsulation",
                                "ISO/IEC 25010:2023 Modularity & Reusability (Mo/Cai DL, TurboMQ, Parnas Information Hiding)",
                                new BigDecimal("18"),
                                List.of(
                                        new SubMetricSpec(SUB_D2_DECOUPLING_LEVEL, "DRH Decoupling Level (DL)", "ratio",
                                                new BigDecimal("0.50"),
                                                CalibrationCurve.higherIsBetter("0.80", "0.60", "0.35", "0.15"),
                                                "sum(SizeFactor(M_j) * (1 - |TransitiveDependents(M_j)| / N))"),
                                        new SubMetricSpec(SUB_D2_ENCAPSULATION, "Package Encapsulation Ratio", "ratio",
                                                new BigDecimal("0.25"),
                                                CalibrationCurve.higherIsBetter("0.70", "0.45", "0.20", "0.05"),
                                                "Size-weighted 1 - (externallyReferencedTypes / packageSize) over implementation packages"),
                                        new SubMetricSpec(SUB_D2_TURBOMQ, "Normalized Package TurboMQ", "ratio",
                                                new BigDecimal("0.25"),
                                                CalibrationCurve.higherIsBetter("0.60", "0.35", "0.15", "0.03"),
                                                "avg(2*mu_p / (2*mu_p + eps_in_p + eps_out_p)) over active non-trivial packages"))),
                        new DimensionSpec(
                                DIM_COUPLING,
                                "Coupling & Change Propagation",
                                "ISO/IEC 25010:2023 Modifiability (Lakos NCCD, MacCormack Normalized PC, Efferent Budget)",
                                new BigDecimal("16"),
                                List.of(
                                        new SubMetricSpec(SUB_D3_NCCD, "Lakos Normalized CCD (NCCD)", "index",
                                                new BigDecimal("0.40"),
                                                CalibrationCurve.lowerIsBetter("1.50", "2.50", "5.00", "10.0"),
                                                "CCD / ((N+1)*log2(N+1) - N) blended across Type and Package scopes"),
                                        new SubMetricSpec(SUB_D3_PROPAGATION_COST, "Normalized Propagation Cost", "ratio",
                                                new BigDecimal("0.35"),
                                                CalibrationCurve.lowerIsBetter("0.03", "0.12", "0.30", "0.60"),
                                                "max(0, PC - PC_tree(N)) / (1 - PC_tree(N))"),
                                        new SubMetricSpec(SUB_D3_EFF_COUPLING, "Excess Internal Fan-Out", "ratio",
                                                new BigDecimal("0.25"),
                                                CalibrationCurve.lowerIsBetter("0.01", "0.05", "0.15", "0.35"),
                                                "avg(min(1, max(0, fanOut_internal - 10) / 15))"))),
                        new DimensionSpec(
                                DIM_HIERARCHY,
                                "Hierarchy, Layering & Dependency Direction",
                                "ISO/IEC 25010:2023 Analyzability & Modifiability (Lilienthal MMI Hierarchy, Martin SDP/SAP)",
                                new BigDecimal("18"),
                                List.of(
                                        new SubMetricSpec(SUB_D4_SDP, "Stable Dependencies Principle (SDP)", "ratio",
                                                new BigDecimal("0.30"),
                                                CalibrationCurve.lowerIsBetter("0.02", "0.10", "0.25", "0.50"),
                                                "|E_SDP_viol (I_target - I_source > 0.20)| / |E_inter-pkg|"),
                                        new SubMetricSpec(SUB_D4_MAIN_SEQUENCE, "Main Sequence Excess Distance (D)", "ratio",
                                                new BigDecimal("0.20"),
                                                CalibrationCurve.lowerIsBetter("0.05", "0.18", "0.35", "0.55"),
                                                "Size-weighted avg(max(0, |A + I - 1| - 0.35)) over active non-value packages"),
                                        new SubMetricSpec(SUB_D4_DSM_BACK_EDGES, "DSM Levelization Back-Edges", "ratio",
                                                new BigDecimal("0.20"),
                                                CalibrationCurve.lowerIsBetter("0", "0.05", "0.18", "0.40"),
                                                "|E_DSM_back_edges| / |E_inter-pkg|"),
                                        new SubMetricSpec(SUB_D4_INFERRED_LAYERING, "Inferred Layering Conformance", "ratio",
                                                new BigDecimal("0.30"),
                                                CalibrationCurve.lowerIsBetter("0", "0.04", "0.15", "0.35"),
                                                "Weighted upward leaks (1.0) & layer skips (0.5) / |E_cross-layer| [INFERRED_LAYER_CONVENTION]"))),
                        new DimensionSpec(
                                DIM_HOTSPOTS,
                                "Structural Balance & Hotspot Control",
                                "ISO/IEC 25010:2023 Analyzability & Testability (Mo/Cai Crossings, Structure101 Fat, Gini)",
                                new BigDecimal("10"),
                                List.of(
                                        new SubMetricSpec(SUB_D5_CROSSING_HUBS, "Crossing Hub Anti-Pattern Density", "ratio",
                                                new BigDecimal("0.40"),
                                                CalibrationCurve.lowerIsBetter("0", "0.02", "0.08", "0.20"),
                                                "avg(min(1, (sqrt(Ca*Ce) - T_hub)/(2*T_hub))) for Ca>=4 && Ce>=4"),
                                        new SubMetricSpec(SUB_D5_FAT_COMPONENTS, "Fat Types & Packages Ratio", "ratio",
                                                new BigDecimal("0.35"),
                                                CalibrationCurve.lowerIsBetter("0.01", "0.05", "0.15", "0.35"),
                                                "0.60*FatTypes(members>30) + 0.40*FatPackages(types>35)"),
                                        new SubMetricSpec(SUB_D5_GINI, "Coupling Distribution Gini Skew", "ratio",
                                                new BigDecimal("0.25"),
                                                CalibrationCurve.lowerIsBetter("0.55", "0.68", "0.80", "0.92"),
                                                "Gini coefficient over total internal coupling (Ca + Ce)"))),
                        new DimensionSpec(
                                DIM_FRAMEWORK_POLYGLOT,
                                "Framework & Cross-Service Architecture",
                                "ISO/IEC 25010:2023 Modifiability & Testability (Spring Modulith/ArchUnit DI, Polyglot Service Topology)",
                                new BigDecimal("10"),
                                List.of(
                                        new SubMetricSpec(SUB_D6_SPRING_FIELD_INJ, "Spring Field Injection Ratio", "ratio",
                                                new BigDecimal("0.30"),
                                                CalibrationCurve.lowerIsBetter("0.05", "0.20", "0.50", "0.85"),
                                                "#FIELD_DI_SITES / #ALL_DI_SITES (excluding @Value)"),
                                        new SubMetricSpec(SUB_D6_SPRING_BEAN_CYCLES, "Spring Bean Wiring Cyclicity", "ratio",
                                                new BigDecimal("0.30"),
                                                CalibrationCurve.lowerIsBetter("0", "0.05", "0.20", "0.45", "90"),
                                                "min(1, RC_must + 0.65 * max(0, RC_feasible_may - RC_must))"),
                                        new SubMetricSpec(SUB_D6_SPRING_WIRING, "Spring Wiring Hygiene", "ratio",
                                                new BigDecimal("0.25"),
                                                CalibrationCurve.lowerIsBetter("0", "0.05", "0.15", "0.35"),
                                                "(#ambiguous + #unsatisfied required sites) / #evaluated_sites"),
                                        new SubMetricSpec(SUB_D6_SPRING_BYPASS, "Controller-to-Repository Bypass", "ratio",
                                                new BigDecimal("0.15"),
                                                CalibrationCurve.lowerIsBetter("0", "0.10", "0.35", "0.70"),
                                                "#controllers_depending_directly_on_repositories / #controllers"),
                                        new SubMetricSpec(SUB_D6_POLYGLOT_CYCLES, "Cross-Service Sync Cyclicity", "ratio",
                                                new BigDecimal("0.40"),
                                                CalibrationCurve.lowerIsBetter("0", "0.05", "0.20", "0.45", "85"),
                                                "Relative Cyclicity over CALLS_SERVICE + INVOKES_RPC [STATIC_CANDIDATE]"),
                                        new SubMetricSpec(SUB_D6_POLYGLOT_COUPLING, "Cross-Service Sync Coupling Excess", "ratio",
                                                new BigDecimal("0.35"),
                                                CalibrationCurve.lowerIsBetter("0", "0.10", "0.30", "0.60"),
                                                "Excess synchronous service fan-out (>3), discounted when event-driven >= 30%"),
                                        new SubMetricSpec(SUB_D6_POLYGLOT_CONTRACTS, "Service Contract & Route Hygiene", "ratio",
                                                new BigDecimal("0.25"),
                                                CalibrationCurve.lowerIsBetter("0", "0.08", "0.25", "0.50"),
                                                "Route collision ambiguity + missing contract schema indicator [STATIC_CANDIDATE]"))),
                        new DimensionSpec(
                                DIM_POLICY,
                                "Explicit Policy Conformance",
                                "ISO/IEC 25010:2023 Modifiability & Architectural Governance (Owner-Authored architecture-policy:v1)",
                                new BigDecimal("18"),
                                List.of(
                                        new SubMetricSpec(SUB_D7_POLICY_EXPOSURE, "Normalized Policy Exposure", "penalty-points",
                                                BigDecimal.ONE,
                                                CalibrationCurve.lowerIsBetter("0", "25", "65", "100"),
                                                "min(100, sum(maxSeverityPerRoot) / max(1, log10(max(10, N_scope))))")))),
                Map.of("INFO", BigDecimal.ONE, "WARNING", new BigDecimal("4"), "ERROR", new BigDecimal("10")),
                new BigDecimal("45"),
                new BigDecimal("0.60"),
                new BigDecimal("0.50"));
    }
}
