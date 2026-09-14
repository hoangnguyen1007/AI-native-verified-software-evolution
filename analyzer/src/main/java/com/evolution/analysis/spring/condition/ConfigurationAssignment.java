package com.evolution.analysis.spring.condition;

import com.evolution.analysis.contract.common.ContentDigest;
import java.util.*;

/** Explicit baseline values and independent per-source selections. Never reads ambient configuration. */
public record ConfigurationAssignment(Map<FiniteDomain.Variable, FiniteDomain.Value> baseline,
                                      List<SourceChoice> sourceChoices, ConditionEvidence evidence) {
    public record SourceChoice(ConfigurationSpace.SourceReference source, FiniteDomain.Variable variable,
                               FiniteDomain.Value value) {
        public SourceChoice { Objects.requireNonNull(source); Objects.requireNonNull(variable); Objects.requireNonNull(value); }
    }
    public ConfigurationAssignment {
        baseline = Collections.unmodifiableMap(new TreeMap<>(baseline));
        Objects.requireNonNull(evidence);
        sourceChoices = sourceChoices.stream().sorted(Comparator.comparing(c -> ConditionIdentitySupport.digest(c).value())).toList();
        Set<Object> keys = new HashSet<>();
        for (var choice : sourceChoices) if (!keys.add(List.of(choice.source(), choice.variable()))) {
            throw new IllegalArgumentException("Duplicate source/variable choice");
        }
        baseline.forEach((variable, value) -> new FiniteDomain(variable, List.of(value), evidence));
    }
    public ConfigurationAssignment(Map<FiniteDomain.Variable, FiniteDomain.Value> baseline, ConditionEvidence evidence) {
        this(baseline, List.of(), evidence);
    }
    public ContentDigest identity() { return ConditionIdentitySupport.digest(canonicalForm()); }
    public Object canonicalForm() {
        return Map.of("schema", "spring-configuration-assignment-v1", "baseline", baseline.entrySet().stream()
                .map(e -> Map.of("variable", e.getKey(), "value", e.getValue())).toList(),
                "sourceChoices", sourceChoices, "evidence", evidence);
    }
}
