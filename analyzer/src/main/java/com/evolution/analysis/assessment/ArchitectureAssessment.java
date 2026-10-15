package com.evolution.analysis.assessment;

import com.evolution.analysis.contract.assessment.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.metrics.*;
import com.evolution.analysis.query.ArchitectureQuery.Classification;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Additive conditional score/explanation envelope; no change to M1 realization identities. */
public final class ArchitectureAssessment {
    public static final VersionedIdentifier VERSION = new VersionedIdentifier("architecture.assessment", "m6.3-v1");
    public static final String REGION_POLICY = "potential-exposure:max-severity-per-directed-root:v1";
    public record Limits(int maxContributions, int maxMetricInputs) {
        public Limits { if (maxContributions < 1 || maxContributions > 400 || maxMetricInputs < 1 || maxMetricInputs > 16) throw new IllegalArgumentException("Invalid assessment limits"); }
        public static Limits defaults() { return new Limits(400, 16); }
    }
    public record Contribution(ContentDigest root, String unit, BigDecimal numerator, BigDecimal denominator, BigDecimal threshold,
                               BigDecimal saturation, BigDecimal multiplier, BigDecimal penalty, List<ContentDigest> inputs,
                               List<ContentDigest> affectedRegions, List<Classification> quantifiers) {
        public Contribution { Objects.requireNonNull(root); Objects.requireNonNull(unit); Objects.requireNonNull(numerator); Objects.requireNonNull(denominator);
            Objects.requireNonNull(threshold); Objects.requireNonNull(saturation); Objects.requireNonNull(multiplier); Objects.requireNonNull(penalty);
            inputs = inputs.stream().sorted().distinct().toList(); affectedRegions = affectedRegions.stream().sorted().distinct().toList(); quantifiers = quantifiers.stream().sorted().distinct().toList();
            if (numerator.signum() < 0 || denominator.signum() <= 0 || penalty.signum() < 0 || penalty.compareTo(BigDecimal.valueOf(100)) > 0 || inputs.isEmpty())
                throw new IllegalArgumentException("Invalid score contribution"); }
        public ContentDigest identity() { return digest(List.of(VERSION, this)); }
    }
    public record Dimension(String id, BigDecimal weight, AssessmentStatus status, Optional<BigDecimal> score, BigDecimal baseline,
                            BigDecimal uncappedPenalty, BigDecimal cap, BigDecimal appliedPenalty, List<Contribution> contributions,
                            List<ContentDigest> inputs, List<CapabilityGapRecord> gaps, List<String> reasons) {
        public Dimension { contributions = List.copyOf(contributions); inputs = inputs.stream().sorted().distinct().toList(); gaps = gaps.stream().sorted().distinct().toList(); reasons = reasons.stream().sorted().distinct().toList();
            validateScore(status, score, reasons, "assessment dimension"); }
    }
    /** Sufficiency of the declared assessment inputs, not a probability of semantic accuracy. */
    public record EvidenceConfidence(boolean sufficient, long declaredRules, long completeRules, int requiredMetrics, int completeMetrics,
                                     List<CapabilityGapRecord> gaps, List<String> limitations) {
        public EvidenceConfidence { gaps = gaps.stream().sorted().distinct().toList(); limitations = List.copyOf(limitations); }
    }
    public record Result(PolicyMetrics.Context context, ContentDigest policyMetrics, Optional<ArchitectureScoreModel> model,
                         VersionedIdentifier formula, String regionPolicy, String calibration, Limits limits,
                         AssessmentStatus status, Optional<BigDecimal> score, List<Dimension> dimensions,
                         BigDecimal weightedNumerator, BigDecimal applicableWeight, List<ArchitectureMetric.Result> structuralInputs,
                         EvidenceConfidence confidence, List<CapabilityGapRecord> gaps, List<String> reasons, Optional<Instant> computedAt) {
        public Result { dimensions = List.copyOf(dimensions); structuralInputs = List.copyOf(structuralInputs); gaps = gaps.stream().sorted().distinct().toList(); reasons = reasons.stream().sorted().distinct().toList();
            Objects.requireNonNull(context); Objects.requireNonNull(policyMetrics); Objects.requireNonNull(model); Objects.requireNonNull(formula); Objects.requireNonNull(limits); Objects.requireNonNull(confidence); Objects.requireNonNull(computedAt);
            validateScore(status, score, reasons, "architecture assessment");
            if (status == AssessmentStatus.COMPLETE && (!gaps.isEmpty() || !confidence.sufficient())) throw new IllegalArgumentException("Complete assessment has insufficient evidence"); }
        public ContentDigest identity() { return digest(List.of(VERSION, context, policyMetrics, model.map(ArchitectureScoreModel::identity), formula, regionPolicy, calibration, limits,
                status, score, dimensions, weightedNumerator, applicableWeight, structuralInputs.stream().map(ArchitectureMetric.Result::identity).toList(), confidence, gaps, reasons)); }
        public Result withComputedAt(Instant time) { return new Result(context, policyMetrics, model, formula, regionPolicy, calibration, limits, status, score,
                dimensions, weightedNumerator, applicableWeight, structuralInputs, confidence, gaps, reasons, Optional.of(time)); }
    }
    static void validateScore(AssessmentStatus status, Optional<BigDecimal> score, List<String> reasons, String name) {
        AssessmentStateRules.validate(status, score, List.of(), reasons, name);
        score.ifPresent(v -> { if (v.signum() < 0 || v.compareTo(BigDecimal.valueOf(100)) > 0) throw new IllegalArgumentException("Score outside 0..100"); });
    }
    static ContentDigest digest(Object value) { return ContentDigest.sha256Utf8(CanonicalJson.write(value)); }
    private ArchitectureAssessment() {}
}
