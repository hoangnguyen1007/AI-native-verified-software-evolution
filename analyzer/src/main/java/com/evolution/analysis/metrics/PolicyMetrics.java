package com.evolution.analysis.metrics;

import com.evolution.analysis.contract.assessment.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.metrics.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.policy.*;
import com.evolution.analysis.query.ArchitectureQuery;
import java.util.*;

/** Additive policy catalog: the frozen M5 catalog and M1 envelopes are unchanged. */
public final class PolicyMetrics {
    public static final VersionedIdentifier VERSION = new VersionedIdentifier("policy.metrics", "m6.3-v1");
    public record Definition(String id, String unit, String formula) {
        public ContentDigest identity() { return digest(List.of(VERSION, this)); }
    }
    public static List<Definition> catalog() { return List.of(
            new Definition("metric.policy-findings", "findings", "Distinct rule-qualified logical finding keys; MUST/MAY/UNKNOWN separate; NEVER only in rule rows."),
            new Definition("metric.policy-density", "ratio", "Rules with MUST or MAY violation / all declared rules; UNKNOWN or open evidence withholds density."),
            new Definition("metric.policy-coverage", "ratio", "Completely evaluated, known-verdict rules / all declared rules; missing rules remain in denominator."),
            new Definition("metric.policy-evidence", "ratio", "Complete Evidence Bundles / observed distinct findings; partial counts are lower bounds, not correctness."),
            new Definition("metric.policy-waivers", "findings", "Waiver/suppression evaluation is unsupported in policy v1; never infer zero waived findings."),
            new Definition("metric.policy-audit", "ratio", "False certainty, false unconditional warning, witness minimality and region accuracy require independent labels; never infer rates from replay.")); }
    public record Limits(int maxInputItems, int maxRegionEvidenceReads) {
        public Limits(int maxInputItems) { this(maxInputItems, 256); }
        public Limits { if (maxInputItems < 1 || maxInputItems > 500_000 || maxRegionEvidenceReads < 1 || maxRegionEvidenceReads > 1024) throw new IllegalArgumentException("Invalid policy metric budget"); }
        public static Limits defaults() { return new Limits(200_000, 256); }
    }
    public record Context(SnapshotIdentity snapshot, Optional<AnalysisIdentity> analysis, ContentDigest graph, String partition,
                          Optional<ContentDigest> policy, ContentDigest source, ContentDigest compilation, ContentDigest mapping,
                          ContentDigest evaluation, List<String> configurationSpaces, List<ArchitectureQuery.Request> requests, Limits limits) {
        public Context { Objects.requireNonNull(snapshot); Objects.requireNonNull(analysis); Objects.requireNonNull(graph); Objects.requireNonNull(partition);
            Objects.requireNonNull(policy); Objects.requireNonNull(source); Objects.requireNonNull(compilation); Objects.requireNonNull(mapping); Objects.requireNonNull(evaluation);
            configurationSpaces = configurationSpaces.stream().sorted().distinct().toList(); requests = List.copyOf(requests); Objects.requireNonNull(limits); }
        public ContentDigest identity() { return digest(List.of(VERSION, this)); }
    }
    public record Coverage(ContentDigest rule, ArchitectureQuery.Classification verdict, ArchitectureQuery.Scope scope, Set<String> relationships, Set<String> unselectedRelationships, AssessmentStatus status,
                           Map<String, Map<String, Long>> selectorCounts, ContentDigest request) {
        public Coverage { relationships = Collections.unmodifiableSet(new TreeSet<>(relationships)); unselectedRelationships = Collections.unmodifiableSet(new TreeSet<>(unselectedRelationships)); selectorCounts = nested(selectorCounts); }
    }
    public record FindingRow(ContentDigest finding, ContentDigest key, ContentDigest bundle, ContentDigest rule,
                             ArchitecturePolicy.Severity severity, ArchitecturePolicy.Kind kind, ArchitectureQuery.Scope scope,
                             ArchitectureQuery.Classification classification, List<String> semanticStatuses, boolean evidenceComplete) {
        public FindingRow { semanticStatuses = semanticStatuses.stream().sorted().distinct().toList(); }
    }
    public record Counts(Map<String, Long> ruleQuantifiers, Map<String, Long> findingQuantifiers,
                         Map<String, Map<String, Long>> byRule, Map<String, Map<String, Long>> bySeverity,
                         Map<String, Map<String, Long>> byScope, Map<String, Map<String, Long>> byKind,
                         Map<String, Map<String, Long>> bySemanticStatus, List<FindingRow> findings, List<Coverage> coverage,
                         long declaredRules, long completeRules, long completeBundles) {
        public Counts { ruleQuantifiers = ordered(ruleQuantifiers); findingQuantifiers = ordered(findingQuantifiers);
            byRule = nested(byRule); bySeverity = nested(bySeverity); byScope = nested(byScope); byKind = nested(byKind); bySemanticStatus = nested(bySemanticStatus);
            findings = List.copyOf(findings); coverage = List.copyOf(coverage); }
    }
    public record Metric(Definition definition, Context context, AssessmentStatus status, Optional<MetricValue> value,
                         Optional<RatioEvidence> ratio, List<CapabilityGapRecord> gaps, List<String> reasons) {
        public Metric { Objects.requireNonNull(definition); Objects.requireNonNull(context); gaps = gaps.stream().sorted().distinct().toList(); reasons = reasons.stream().sorted().distinct().toList();
            AssessmentStateRules.validate(status, value, List.of(), reasons, "policy metric");
            if (status == AssessmentStatus.COMPLETE && !gaps.isEmpty()) throw new IllegalArgumentException("Complete policy metric has gaps");
            if (ratio.isPresent() && ratio.orElseThrow().denominator().signum() == 0 && status != AssessmentStatus.NOT_APPLICABLE)
                throw new IllegalArgumentException("Zero denominator requires NOT_APPLICABLE"); }
        public ContentDigest identity() { return digest(List.of(VERSION, this)); }
    }
    public record Report(Context context, AssessmentStatus status, Counts counts, List<Metric> metrics, List<CapabilityGapRecord> gaps,
                         List<PolicyEvaluation.UpstreamEvidence> upstreamEvidence) {
        public Report { metrics = List.copyOf(metrics); gaps = gaps.stream().sorted().distinct().toList(); upstreamEvidence = List.copyOf(upstreamEvidence); }
        public Metric require(String id) { return metrics.stream().filter(m -> m.definition().id().equals(id)).findFirst().orElseThrow(); }
        public ContentDigest identity() { return digest(List.of(VERSION, this)); }
    }
    static Map<String, Long> ordered(Map<String, Long> values) { return Collections.unmodifiableMap(new TreeMap<>(values)); }
    static Map<String, Map<String, Long>> nested(Map<String, Map<String, Long>> values) {
        var copy = new TreeMap<String, Map<String, Long>>(); values.forEach((k, v) -> copy.put(k, ordered(v))); return Collections.unmodifiableMap(copy);
    }
    static ContentDigest digest(Object value) { return ContentDigest.sha256Utf8(CanonicalJson.write(value)); }
    private PolicyMetrics() {}
}
