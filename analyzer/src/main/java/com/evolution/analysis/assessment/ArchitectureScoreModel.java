package com.evolution.analysis.assessment;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.metrics.MetricCatalog;
import com.evolution.analysis.policy.ArchitecturePolicy;
import com.evolution.analysis.query.ArchitectureQuery.*;
import java.math.BigDecimal;
import java.util.*;

/** Explicit candidate parameters, never a claim of human approval or empirical calibration. */
public record ArchitectureScoreModel(VersionedIdentifier model, List<Dimension> dimensions, Map<String, BigDecimal> severityPenalties) {
    public static final VersionedIdentifier FORMULA = new VersionedIdentifier("architecture.score-formula", "m6.3-exposure-v1");
    public static final String CALIBRATION = "PROVISIONAL";
    public record MetricInput(VersionedIdentifier metric, Scope scope, BigDecimal threshold, BigDecimal saturation, BigDecimal maximumPenalty) {
        public MetricInput {
            Objects.requireNonNull(metric); Objects.requireNonNull(scope);
            var definition = MetricCatalog.require(metric.id());
            if (!definition.metric().equals(metric) || !Set.of("metric.density", "metric.concentration").contains(metric.id()) || !definition.scopes().contains(scope))
                throw new IllegalArgumentException("Unsupported score metric definition or scope");
            threshold = bounded(threshold, BigDecimal.ONE); saturation = bounded(saturation, BigDecimal.ONE);
            maximumPenalty = bounded(maximumPenalty, BigDecimal.valueOf(100));
            if (threshold.compareTo(saturation) >= 0 || maximumPenalty.signum() == 0) throw new IllegalArgumentException("Invalid score metric thresholds");
        }
    }
    public record Dimension(String id, BigDecimal weight, BigDecimal penaltyCap, Set<ArchitecturePolicy.Kind> ruleKinds, Optional<MetricInput> metric) {
        public Dimension {
            id = ContractChecks.namespacedId(id, "score dimension"); weight = bounded(weight, BigDecimal.valueOf(100));
            penaltyCap = bounded(penaltyCap, BigDecimal.valueOf(100)); ruleKinds = Collections.unmodifiableSet(new TreeSet<>(ruleKinds)); Objects.requireNonNull(metric);
            if (weight.signum() == 0 || penaltyCap.signum() == 0 || ruleKinds.isEmpty() == metric.isEmpty())
                throw new IllegalArgumentException("A dimension needs either policy kinds or one structural metric");
        }
    }
    public ArchitectureScoreModel {
        Objects.requireNonNull(model); dimensions = ContractChecks.sortedDistinct(dimensions, Comparator.comparing(Dimension::id), "score dimensions");
        if (dimensions.isEmpty() || dimensions.size() > 8) throw new IllegalArgumentException("Score model needs 1..8 dimensions");
        var metricScopes = new HashMap<String, Scope>();
        for (var dimension : dimensions) dimension.metric().ifPresent(input -> {
            var prior = metricScopes.putIfAbsent(input.metric().id(), input.scope());
            if (prior != null && prior != input.scope()) throw new IllegalArgumentException("One metric ID must use one declared score scope");
        });
        var values = new TreeMap<String, BigDecimal>(); severityPenalties.forEach((k, v) -> values.put(k, bounded(v, BigDecimal.valueOf(100))));
        if (!values.keySet().equals(Set.of("INFO", "WARNING", "ERROR")) || values.get("INFO").compareTo(values.get("WARNING")) > 0
                || values.get("WARNING").compareTo(values.get("ERROR")) > 0 || values.get("INFO").signum() == 0)
            throw new IllegalArgumentException("Positive monotone severity penalties required");
        severityPenalties = Collections.unmodifiableMap(values);
    }
    public ContentDigest identity() { return ContentDigest.sha256Utf8(CanonicalJson.write(List.of(FORMULA, CALIBRATION, this))); }
    /** Reviewable reference example only: no approved default architecture-quality thresholds. */
    public static ArchitectureScoreModel candidate(Scope scope) {
        var all = Set.of(ArchitecturePolicy.Kind.values());
        return new ArchitectureScoreModel(new VersionedIdentifier("architecture.score-candidate", "m6.3-v1"), List.of(
                new Dimension("health.boundaries", new BigDecimal("25"), new BigDecimal("100"), Set.of(ArchitecturePolicy.Kind.FORBIDDEN_DEPENDENCY, ArchitecturePolicy.Kind.BOUNDARY, ArchitecturePolicy.Kind.LAYER_BOUNDARY), Optional.empty()),
                new Dimension("health.cyclicity", new BigDecimal("20"), new BigDecimal("100"), Set.of(ArchitecturePolicy.Kind.ACYCLIC), Optional.empty()),
                new Dimension("health.coupling", new BigDecimal("20"), new BigDecimal("100"), Set.of(), Optional.of(new MetricInput(MetricCatalog.require("metric.density").metric(), scope, BigDecimal.ZERO, BigDecimal.ONE, new BigDecimal("100")))),
                new Dimension("health.policy", new BigDecimal("25"), new BigDecimal("100"), all, Optional.empty()),
                new Dimension("health.hotspots", new BigDecimal("10"), new BigDecimal("100"), Set.of(), Optional.of(new MetricInput(MetricCatalog.require("metric.concentration").metric(), scope, BigDecimal.ZERO, BigDecimal.ONE, new BigDecimal("100"))))),
                Map.of("INFO", new BigDecimal("2"), "WARNING", new BigDecimal("10"), "ERROR", new BigDecimal("25")));
    }
    static BigDecimal bounded(BigDecimal value, BigDecimal maximum) {
        Objects.requireNonNull(value); if (value.scale() > 12 || value.precision() > 32 || value.signum() < 0 || value.compareTo(maximum) > 0)
            throw new IllegalArgumentException("Invalid bounded score parameter");
        return normalize(value);
    }
    static BigDecimal normalize(BigDecimal value) { return value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros(); }
}
