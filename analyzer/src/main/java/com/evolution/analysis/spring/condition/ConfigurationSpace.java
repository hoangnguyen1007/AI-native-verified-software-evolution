package com.evolution.analysis.spring.condition;

import com.evolution.analysis.contract.common.*;
import java.math.BigInteger;
import java.util.*;

/** Finite domain/constraint declarations inside a single M3 context; does not claim feasible worlds. */
public record ConfigurationSpace(SpringBuildContext buildContext, ConfigurationEnvelope repositoryEnvelope,
                                 Optional<ConfigurationEnvelope> deploymentEnvelope, List<FiniteDomain> domains,
                                 List<ConditionOccurrence> constraints, PrecedencePolicy precedencePolicy,
                                 ProfilePolicy profilePolicy, VersionedIdentifier abstractionVersion,
                                 FeasibilityPolicy feasibilityPolicy) {
    /** All counters are deterministic. Wall-clock aborts belong to a separate execution attempt. */
    public record Limits(int maxVariables, int maxValuesPerDomain, long maxCartesianAssignments,
                         int maxConditionRows, int maxExpressionVisits, int maxExpressionDepth) {
        public Limits {
            if (maxVariables < 0 || maxValuesPerDomain < 1 || maxCartesianAssignments < 1
                    || maxConditionRows < 1 || maxExpressionVisits < 1 || maxExpressionDepth < 1) {
                throw new IllegalArgumentException("Invalid finite condition/model limits");
            }
        }
        public static Limits conservative() { return new Limits(12, 4096, 4096, 10000, 100000, 256); }
    }
    public record SourceReference(ConfigurationEnvelope.Layer layer, ContentDigest sourceIdentity) {
        public SourceReference { Objects.requireNonNull(layer); Objects.requireNonNull(sourceIdentity); }
    }
    /** Explicit low-to-high precedence. A later evaluator applies the last active supplied definition. */
    public record PrecedencePolicy(VersionedIdentifier version, List<SourceReference> lowToHigh,
                                   ConditionEvidence evidence) {
        public PrecedencePolicy {
            Objects.requireNonNull(version); Objects.requireNonNull(evidence);
            lowToHigh = ContractChecks.distinctInOrder(lowToHigh, "source precedence");
        }
    }
    /** Profile booleans denote set membership; no implicit mutual exclusion or host defaults. */
    public record ProfilePolicy(VersionedIdentifier version, List<String> defaultProfiles,
                                Map<String, List<String>> groups, List<String> includes, ConditionEvidence evidence) {
        public ProfilePolicy {
            Objects.requireNonNull(version); Objects.requireNonNull(evidence);
            defaultProfiles = strings(defaultProfiles); includes = strings(includes);
            Map<String, List<String>> copy = new TreeMap<>();
            groups.forEach((key, values) -> copy.put(ConditionIdentitySupport.name(key), strings(values)));
            groups = Collections.unmodifiableMap(copy);
        }
    }
    public record FeasibilityPolicy(VersionedIdentifier version, Limits limits, ConditionEvidence evidence) {
        public FeasibilityPolicy { Objects.requireNonNull(version); Objects.requireNonNull(limits); Objects.requireNonNull(evidence); }
    }
    /** A cap+1 lower bound when saturated, never misreported as the exact number of configurations. */
    public record CartesianSize(BigInteger value, boolean exact) {
        public CartesianSize {
            Objects.requireNonNull(value);
            if (value.signum() < 0 || !exact && value.signum() == 0) throw new IllegalArgumentException("Invalid Cartesian cardinality");
        }
    }

    public ConfigurationSpace {
        Objects.requireNonNull(buildContext); Objects.requireNonNull(repositoryEnvelope); Objects.requireNonNull(deploymentEnvelope);
        Objects.requireNonNull(precedencePolicy); Objects.requireNonNull(profilePolicy);
        Objects.requireNonNull(abstractionVersion); Objects.requireNonNull(feasibilityPolicy);
        if (repositoryEnvelope.layer() != ConfigurationEnvelope.Layer.REPOSITORY
                || deploymentEnvelope.isPresent() && deploymentEnvelope.orElseThrow().layer() != ConfigurationEnvelope.Layer.DEPLOYMENT) {
            throw new IllegalArgumentException("Configuration envelope layer mismatch");
        }
        domains = ContractChecks.sortedDistinct(domains, Comparator.comparing(FiniteDomain::variable), "finite variables");
        constraints = ContractChecks.sortedDistinct(constraints, Comparator.comparing(ConditionOccurrence::identity), "constraints");
    }
    private static List<String> strings(List<String> values) {
        return ContractChecks.sortedDistinct(values.stream().map(ConditionIdentitySupport::name).toList(),
                Comparator.naturalOrder(), "profile set");
    }
    public ConfigurationSpaceIdentity identity() {
        return new ConfigurationSpaceIdentity(ConditionIdentitySupport.derive("spring-configuration-space", canonicalForm()));
    }
    public Map<String, Object> canonicalForm() {
        return Map.of("buildContextIdentity", buildContext.identity(), "repositoryEnvelopeIdentity", repositoryEnvelope.identity(),
                "deploymentEnvelopeIdentity", deploymentEnvelope.map(ConfigurationEnvelope::identity), "domains", domains,
                "constraints", constraints.stream().map(ConditionOccurrence::canonicalForm).toList(),
                "precedencePolicy", precedencePolicy, "profilePolicy", profilePolicy,
                "abstractionVersion", abstractionVersion, "feasibilityPolicy", feasibilityPolicy);
    }
    public List<ConfigurationEnvelope> envelopes() {
        List<ConfigurationEnvelope> values = new ArrayList<>(); values.add(repositoryEnvelope);
        deploymentEnvelope.ifPresent(values::add); return List.copyOf(values);
    }
    public CartesianSize cartesianSize() {
        if (domains.stream().anyMatch(domain -> domain.values().isEmpty())) return new CartesianSize(BigInteger.ZERO, true);
        BigInteger product = BigInteger.ONE;
        BigInteger cap = BigInteger.valueOf(feasibilityPolicy.limits().maxCartesianAssignments());
        for (FiniteDomain domain : domains) {
            product = product.multiply(BigInteger.valueOf(domain.values().size()));
            if (product.compareTo(cap) > 0) return new CartesianSize(cap.add(BigInteger.ONE), false);
        }
        return new CartesianSize(product, true);
    }
}
