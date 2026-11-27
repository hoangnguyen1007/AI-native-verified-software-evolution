package com.evolution.analysis.assessment.v2;

import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Immutable, deterministic, content-addressed Architecture Assessment v2 contracts.
 *
 * <p>Strictly separates Architecture Health ({@code score}, {@code conservativeLowerBound},
 * {@code dimensions}) from independent {@link AnalysisConfidenceV2}. Uses exact
 * {@link BigDecimal} values (zero binary floating-point fields) so every record is
 * directly {@link CanonicalJson} serializable and SHA-256 verifiable.
 */
public final class ArchitectureAssessmentV2 {
    public static final VersionedIdentifier VERSION = new VersionedIdentifier("architecture.assessment", "v2.0");

    public enum ConfidenceTier { HIGH, MODERATE, LOW, INSUFFICIENT }

    public record EvidenceWitness(
            String kind,
            String identity,
            String label,
            String detail,
            List<ContentDigest> evidence) {
        public EvidenceWitness {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(detail, "detail");
            evidence = evidence.stream().sorted().distinct().toList();
        }
    }

    public record SubMetricResult(
            String id,
            String displayName,
            String unit,
            BigDecimal subWeight,
            AssessmentStatus status,
            Optional<BigDecimal> rawValue,
            Optional<BigDecimal> score,
            CalibrationCurve curve,
            String explanation,
            String remediationHint,
            List<EvidenceWitness> witnesses) {
        public SubMetricResult {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(unit, "unit");
            subWeight = CalibrationCurve.normalize(Objects.requireNonNull(subWeight, "subWeight"));
            Objects.requireNonNull(status, "status");
            rawValue = Objects.requireNonNull(rawValue, "rawValue").map(CalibrationCurve::normalize);
            score = Objects.requireNonNull(score, "score").map(CalibrationCurve::normalize);
            Objects.requireNonNull(curve, "curve");
            Objects.requireNonNull(explanation, "explanation");
            Objects.requireNonNull(remediationHint, "remediationHint");
            witnesses = List.copyOf(Objects.requireNonNull(witnesses, "witnesses"));
            validateScoreRange(status, score, "sub-metric " + id);
        }
    }

    public record DimensionResult(
            String id,
            String displayName,
            String iso25010Mapping,
            BigDecimal weight,
            AssessmentStatus status,
            Optional<BigDecimal> score,
            Optional<BigDecimal> conservativeLowerBound,
            BigDecimal uncertaintyDelta,
            List<SubMetricResult> subMetrics,
            List<String> reasons,
            List<CapabilityGapRecord> gaps) {
        public DimensionResult {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(iso25010Mapping, "iso25010Mapping");
            weight = CalibrationCurve.normalize(Objects.requireNonNull(weight, "weight"));
            Objects.requireNonNull(status, "status");
            score = Objects.requireNonNull(score, "score").map(CalibrationCurve::normalize);
            conservativeLowerBound = Objects.requireNonNull(conservativeLowerBound, "conservativeLowerBound")
                    .map(CalibrationCurve::normalize);
            uncertaintyDelta = CalibrationCurve.normalize(Objects.requireNonNull(uncertaintyDelta, "uncertaintyDelta"));
            subMetrics = List.copyOf(Objects.requireNonNull(subMetrics, "subMetrics"));
            reasons = reasons.stream().sorted().distinct().toList();
            gaps = gaps.stream().sorted().distinct().toList();
            validateScoreRange(status, score, "dimension " + id);
            if (score.isPresent() && conservativeLowerBound.isPresent()
                    && conservativeLowerBound.orElseThrow().compareTo(score.orElseThrow()) > 0) {
                throw new IllegalArgumentException("conservativeLowerBound cannot exceed observed score");
            }
        }
    }

    /** Independent analysis evidence quality &amp; coverage report, strictly separate from Architecture Health. */
    public record AnalysisConfidenceV2(
            BigDecimal confidenceScore,
            ConfidenceTier tier,
            String evidenceTier,
            BigDecimal parseCompleteness,
            BigDecimal internalEdgeCompleteness,
            BigDecimal overallSemanticResolution,
            BigDecimal buildCompleteness,
            Optional<BigDecimal> springSpaceDefiniteness,
            long openGapCount,
            Map<String, Long> gapBuckets,
            List<String> limitations) {
        public AnalysisConfidenceV2 {
            confidenceScore = CalibrationCurve.clamp(
                    CalibrationCurve.normalize(Objects.requireNonNull(confidenceScore)),
                    BigDecimal.ZERO, CalibrationCurve.HUNDRED);
            Objects.requireNonNull(tier, "tier");
            Objects.requireNonNull(evidenceTier, "evidenceTier");
            parseCompleteness = CalibrationCurve.clamp(CalibrationCurve.normalize(parseCompleteness), BigDecimal.ZERO, BigDecimal.ONE);
            internalEdgeCompleteness = CalibrationCurve.clamp(CalibrationCurve.normalize(internalEdgeCompleteness), BigDecimal.ZERO, BigDecimal.ONE);
            overallSemanticResolution = CalibrationCurve.clamp(CalibrationCurve.normalize(overallSemanticResolution), BigDecimal.ZERO, BigDecimal.ONE);
            buildCompleteness = CalibrationCurve.clamp(CalibrationCurve.normalize(buildCompleteness), BigDecimal.ZERO, BigDecimal.ONE);
            springSpaceDefiniteness = Objects.requireNonNull(springSpaceDefiniteness)
                    .map(v -> CalibrationCurve.clamp(CalibrationCurve.normalize(v), BigDecimal.ZERO, BigDecimal.ONE));
            if (openGapCount < 0) throw new IllegalArgumentException("Negative openGapCount");
            var sortedBuckets = new TreeMap<String, Long>(Objects.requireNonNull(gapBuckets));
            gapBuckets = Collections.unmodifiableMap(sortedBuckets);
            limitations = List.copyOf(Objects.requireNonNull(limitations));
        }
    }

    public record ContextScoreSummary(
            String contextId,
            String label,
            String kind,
            int entityCount,
            AssessmentStatus status,
            Optional<BigDecimal> score,
            Optional<BigDecimal> conservativeLowerBound,
            ConfidenceTier confidenceTier) {
        public ContextScoreSummary {
            Objects.requireNonNull(contextId);
            Objects.requireNonNull(label);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(status);
            score = Objects.requireNonNull(score).map(CalibrationCurve::normalize);
            conservativeLowerBound = Objects.requireNonNull(conservativeLowerBound).map(CalibrationCurve::normalize);
            Objects.requireNonNull(confidenceTier);
        }
    }

    public record Result(
            ContentDigest graph,
            String context,
            String scopeMode,
            VersionedIdentifier formula,
            ContentDigest modelIdentity,
            String calibration,
            AssessmentStatus status,
            Optional<BigDecimal> score,
            Optional<BigDecimal> conservativeLowerBound,
            BigDecimal uncertaintyDelta,
            String healthBand,
            BigDecimal applicableWeight,
            List<DimensionResult> dimensions,
            AnalysisConfidenceV2 confidence,
            Optional<InferredLayerClassifier.LayerMap> inferredLayers,
            List<ContextScoreSummary> contextBreakdown,
            Optional<ContentDigest> policyReport,
            List<CapabilityGapRecord> gaps,
            List<String> reasons,
            Optional<Instant> computedAt) {

        public Result {
            Objects.requireNonNull(graph, "graph");
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(scopeMode, "scopeMode");
            Objects.requireNonNull(formula, "formula");
            Objects.requireNonNull(modelIdentity, "modelIdentity");
            Objects.requireNonNull(calibration, "calibration");
            Objects.requireNonNull(status, "status");
            score = Objects.requireNonNull(score, "score").map(CalibrationCurve::normalize);
            conservativeLowerBound = Objects.requireNonNull(conservativeLowerBound, "conservativeLowerBound")
                    .map(CalibrationCurve::normalize);
            uncertaintyDelta = CalibrationCurve.normalize(Objects.requireNonNull(uncertaintyDelta, "uncertaintyDelta"));
            Objects.requireNonNull(healthBand, "healthBand");
            applicableWeight = CalibrationCurve.normalize(Objects.requireNonNull(applicableWeight, "applicableWeight"));
            dimensions = List.copyOf(Objects.requireNonNull(dimensions, "dimensions"));
            Objects.requireNonNull(confidence, "confidence");
            Objects.requireNonNull(inferredLayers, "inferredLayers");
            contextBreakdown = List.copyOf(Objects.requireNonNull(contextBreakdown, "contextBreakdown"));
            Objects.requireNonNull(policyReport, "policyReport");
            gaps = gaps.stream().sorted().distinct().toList();
            reasons = reasons.stream().sorted().distinct().toList();
            Objects.requireNonNull(computedAt, "computedAt");
            validateScoreRange(status, score, "architecture assessment v2");
            if (status == AssessmentStatus.WITHHELD && (score.isPresent() || conservativeLowerBound.isPresent())) {
                throw new IllegalArgumentException("WITHHELD assessment must not emit numeric scores");
            }
            if (score.isPresent() && conservativeLowerBound.isPresent()
                    && conservativeLowerBound.orElseThrow().compareTo(score.orElseThrow()) > 0) {
                throw new IllegalArgumentException("conservativeLowerBound cannot exceed observed score");
            }
        }

        /** Deterministic content digest excluding operational {@code computedAt} telemetry. */
        public ContentDigest identity() {
            return ContentDigest.sha256Utf8(CanonicalJson.write(List.of(
                    VERSION, graph, context, scopeMode, formula, modelIdentity, calibration, status,
                    score, conservativeLowerBound, uncertaintyDelta, healthBand, applicableWeight,
                    dimensions, confidence, inferredLayers, contextBreakdown, policyReport, gaps, reasons)));
        }

        public Result withComputedAt(Instant instant) {
            return new Result(graph, context, scopeMode, formula, modelIdentity, calibration, status,
                    score, conservativeLowerBound, uncertaintyDelta, healthBand, applicableWeight,
                    dimensions, confidence, inferredLayers, contextBreakdown, policyReport, gaps, reasons,
                    Optional.of(Objects.requireNonNull(instant)));
        }

        public Optional<DimensionResult> dimension(String id) {
            return dimensions.stream().filter(d -> d.id().equals(id)).findFirst();
        }
    }

    public static String bandFor(Optional<BigDecimal> score, ConfidenceTier tier) {
        if (score.isEmpty() || tier == ConfidenceTier.INSUFFICIENT) return "Withheld";
        BigDecimal s = score.orElseThrow();
        if (tier == ConfidenceTier.LOW) {
            return s.compareTo(new BigDecimal("50")) >= 0 ? "Qualified · Low Confidence" : "At Risk · Low Confidence";
        }
        if (s.compareTo(new BigDecimal("85")) >= 0) return "Strong";
        if (s.compareTo(new BigDecimal("70")) >= 0) return "Healthy";
        if (s.compareTo(new BigDecimal("50")) >= 0) return "Moderate Drift";
        if (s.compareTo(new BigDecimal("30")) >= 0) return "At Risk";
        return "Critical Debt";
    }

    private static void validateScoreRange(AssessmentStatus status, Optional<BigDecimal> score, String name) {
        if ((status == AssessmentStatus.WITHHELD || status == AssessmentStatus.NOT_APPLICABLE) && score.isPresent()) {
            throw new IllegalArgumentException(name + " with status " + status + " must not have a score");
        }
        if ((status == AssessmentStatus.COMPLETE || status == AssessmentStatus.PARTIAL) && score.isEmpty()) {
            throw new IllegalArgumentException(name + " with status " + status + " requires a score");
        }
        score.ifPresent(v -> {
            if (v.signum() < 0 || v.compareTo(CalibrationCurve.HUNDRED) > 0) {
                throw new IllegalArgumentException(name + " score outside [0, 100]: " + v);
            }
        });
    }

    private ArchitectureAssessmentV2() {}
}
