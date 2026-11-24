package com.evolution.analysis.spring.condition;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.spring.universal.BasicSpelEvaluator;
import java.util.*;

/**
 * Builds a bounded multi-configuration Spring {@link ConfigurationSpace} across
 * all
 * discovered {@code @Profile}, {@code @ConditionalOnProperty},
 * {@code @ConditionalOnExpression},
 * and {@code application*.{properties,yml,yaml}} configuration dimensions.
 */
public final class RepositoryConfigurationSpaceBuilder {
    public static final VersionedIdentifier PROVIDER = new VersionedIdentifier("spring.repository-configuration-space",
            "m8.4-multi-config-v1");
    public static final int MAX_DIMENSIONS = 32;
    public static final int MAX_VALUES_PER_PROPERTY = 8;

    public record Selected(ConfigurationSpace space, ConfigurationAssignment baseline, List<CapabilityGapRecord> gaps) {
        public Selected {
            Objects.requireNonNull(space);
            Objects.requireNonNull(baseline);
            gaps = List.copyOf(gaps);
        }
    }

    private RepositoryConfigurationSpaceBuilder() {
    }

    public static Selected build(SpringBuildContext build, ConfigDataIngestion.Result captured,
            List<ConditionOccurrence> conditions) {
        Objects.requireNonNull(build);
        Objects.requireNonNull(captured);
        Objects.requireNonNull(conditions);

        var evidence = new ConditionEvidence.Derived(List.of(captured.identity()), PROVIDER,
                "repository-multi-config-space");
        var gaps = new ArrayList<CapabilityGapRecord>();

        var directProfileNames = new TreeSet<String>();
        var ambientProfileNames = new TreeSet<String>();
        var propertyHints = new TreeMap<String, LinkedHashSet<String>>();

        // 1. Collect direct profile & property variables referenced by lowered
        // condition occurrences
        var pending = new ArrayDeque<ConditionExpression>();
        var seen = new HashSet<ConditionExpression.Identity>();
        conditions.forEach(c -> pending.add(c.expression()));
        while (!pending.isEmpty()) {
            var expr = pending.removeFirst();
            if (!seen.add(expr.identity()))
                continue;
            if (expr.operand() instanceof ConditionExpression.Profile profile) {
                if (!profile.name().isBlank())
                    directProfileNames.add(profile.name());
            } else if (expr.operand() instanceof ConditionExpression.Property property) {
                for (String key : property.keys()) {
                    if (key.isBlank())
                        continue;
                    var hints = propertyHints.computeIfAbsent(key, k -> new LinkedHashSet<>());
                    if (!property.havingValue().isBlank()) {
                        hints.add(property.havingValue());
                    } else {
                        hints.add("true");
                        hints.add("false");
                    }
                }
            } else if (expr.operand() instanceof ConditionExpression.BasicSpel spel) {
                BasicSpelEvaluator.propertyKeys(spel.expression()).ifPresent(keys -> {
                    for (String key : keys) {
                        if (key.isBlank())
                            continue;
                        var hints = propertyHints.computeIfAbsent(key, k -> new LinkedHashSet<>());
                        hints.add("true");
                        hints.add("false");
                    }
                });
            }
            pending.addAll(expr.children());
        }

        // 2. Collect ambient profiles & observed property values across all config
        // documents
        for (String p : captured.activeProfiles()) {
            if (!p.isBlank() && !directProfileNames.contains(p))
                ambientProfileNames.add(p);
        }
        for (var doc : captured.documents()) {
            doc.profile().ifPresent(p -> {
                if (!p.isBlank() && !directProfileNames.contains(p))
                    ambientProfileNames.add(p);
            });
            addProfileKeys(doc.properties(), ambientProfileNames);
            for (var entry : doc.properties().entrySet()) {
                var hints = propertyHints.get(entry.getKey());
                if (hints != null && entry.getValue() != null) {
                    hints.add(entry.getValue());
                }
            }
        }
        ambientProfileNames.removeAll(directProfileNames);
        for (var entry : captured.properties().entrySet()) {
            var hints = propertyHints.get(entry.getKey());
            if (hints != null && entry.getValue() != null) {
                hints.add(entry.getValue());
            }
        }

        // 3. Assemble sorted finite domains prioritizing direct condition variables
        // ahead of ambient config variables
        var directVars = new ArrayList<FiniteDomain.Variable>();
        for (String profile : directProfileNames) {
            directVars.add(new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE, profile));
        }
        for (String prop : propertyHints.keySet()) {
            directVars.add(new FiniteDomain.Variable(FiniteDomain.Kind.PROPERTY, prop));
        }
        directVars.sort(Comparator.naturalOrder());

        var ambientVars = new ArrayList<FiniteDomain.Variable>();
        for (String profile : ambientProfileNames) {
            ambientVars.add(new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE, profile));
        }
        ambientVars.sort(Comparator.naturalOrder());

        int totalDiscovered = directVars.size() + ambientVars.size();
        var allVars = new ArrayList<FiniteDomain.Variable>(Math.min(MAX_DIMENSIONS, totalDiscovered));
        for (var v : directVars) {
            if (allVars.size() >= MAX_DIMENSIONS)
                break;
            allVars.add(v);
        }
        for (var v : ambientVars) {
            if (allVars.size() >= MAX_DIMENSIONS)
                break;
            allVars.add(v);
        }
        allVars.sort(Comparator.naturalOrder());

        if (totalDiscovered > MAX_DIMENSIONS) {
            gaps.add(gap(build, captured.identity(), "CONFIGURATION_SPACE_DIMENSION_BOUNDED",
                    "Configuration dimensions capped at " + MAX_DIMENSIONS + " of " + totalDiscovered
                            + " discovered variables."));
        }

        var baselineValues = new TreeMap<FiniteDomain.Variable, FiniteDomain.Value>();
        var domains = new ArrayList<FiniteDomain>(allVars.size());
        for (var variable : allVars) {
            if (variable.kind() == FiniteDomain.Kind.PROFILE) {
                boolean active = captured.activeProfiles().contains(variable.name());
                baselineValues.put(variable, FiniteDomain.Value.bool(active));
                domains.add(new FiniteDomain(variable,
                        List.of(FiniteDomain.Value.bool(false), FiniteDomain.Value.bool(true)),
                        evidence));
            } else {
                var valueSet = new LinkedHashSet<FiniteDomain.Value>();
                String current = captured.properties().get(variable.name());
                FiniteDomain.Value baseVal = current != null
                        ? FiniteDomain.Value.exact(current)
                        : FiniteDomain.Value.missing();
                valueSet.add(FiniteDomain.Value.missing());
                valueSet.add(baseVal);
                for (String hint : propertyHints.getOrDefault(variable.name(), new LinkedHashSet<>())) {
                    if (valueSet.size() >= MAX_VALUES_PER_PROPERTY)
                        break;
                    if (!hint.isBlank())
                        valueSet.add(FiniteDomain.Value.exact(hint));
                }
                baselineValues.put(variable, baseVal);
                domains.add(new FiniteDomain(variable, List.copyOf(valueSet), evidence));
            }
        }

        var repoDocs = captured.documents().stream()
                .map(d -> {
                    ConditionEvidence docEv = (d.evidence() instanceof ConditionEvidence.Source s && s.span().isEmpty())
                            ? new ConditionEvidence.Derived(List.of(captured.identity(), d.evidence().identity()),
                                    PROVIDER, "config-document:" + d.path())
                            : d.evidence();
                    return new ConfigurationEnvelope.Document(
                            docEv, Optional.empty(), List.of(), ConfigurationEnvelope.Availability.AVAILABLE);
                })
                .toList();
        var repository = new ConfigurationEnvelope(ConfigurationEnvelope.Layer.REPOSITORY, repoDocs, List.of(),
                captured.identity());

        // Retain explicit gap noting that external env/CLI/runtime overrides are not
        // observed in passive analysis.
        // Keep deploymentEnvelope absent (repo-scoped space) so SymbolicTruthRegions
        // evaluates feasible
        // repository-scoped realizations without falsely claiming an AVAILABLE
        // deployment envelope.
        gaps.add(gap(build, captured.identity(), "EXTERNAL_DEPLOYMENT_OVERRIDES_UNOBSERVED",
                "Multi-configuration SAT models captured repository properties and profiles only; external runtime environment/CLI overrides remain unobserved."));

        var limits = new ConfigurationSpace.Limits(MAX_DIMENSIONS, 64, 1L << 24, 10_000, 100_000, 256);
        var space = new ConfigurationSpace(
                build,
                repository,
                Optional.empty(),
                domains,
                List.of(),
                new ConfigurationSpace.PrecedencePolicy(ExogenousConditionEvaluator.PRECEDENCE, List.of(), evidence),
                new ConfigurationSpace.ProfilePolicy(ExogenousConditionEvaluator.PROFILES, List.of(), Map.of(),
                        List.of(), evidence),
                PROVIDER,
                new ConfigurationSpace.FeasibilityPolicy(PROVIDER, limits, evidence));

        return new Selected(space, new ConfigurationAssignment(baselineValues, evidence), gaps);
    }

    private static void addProfileKeys(Map<String, String> props, Set<String> profiles) {
        for (String key : List.of("spring.profiles.active", "spring.profiles.default", "spring.profiles.include",
                "spring.config.activate.on-profile")) {
            String raw = props.get(key);
            if (raw != null)
                parseProfileTokens(raw, profiles);
        }
        for (var entry : props.entrySet()) {
            if (entry.getKey().startsWith("spring.profiles.group.") && entry.getValue() != null) {
                String group = entry.getKey().substring("spring.profiles.group.".length());
                if (!group.isBlank())
                    profiles.add(group);
                parseProfileTokens(entry.getValue(), profiles);
            }
        }
    }

    private static void parseProfileTokens(String raw, Set<String> out) {
        for (String token : raw.split("[,;\\s|&()!]+")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty() && !trimmed.contains("${") && trimmed.matches("[A-Za-z0-9][A-Za-z0-9._+@-]*")) {
                out.add(trimmed);
            }
        }
    }

    private static CapabilityGapRecord gap(SpringBuildContext build, ContentDigest input, String reasonCode,
            String note) {
        var obs = ProviderObservationReference.create(PROVIDER, "spring.configuration-space", input,
                ContentDigest.sha256Utf8(reasonCode));
        var subject = EvidenceSubject.observation(obs.identity());
        var req = new EvidenceRequirement(
                EvidenceRequirement.Kind.CONFIGURATION,
                "spring.configuration." + reasonCode.toLowerCase(Locale.ROOT).replace('_', '-'),
                List.of(subject),
                EvidenceRequirement.AuthorizationClass.PASSIVE,
                List.of(note));
        return CapabilityGapRecord.create(
                ConditionProcessing.GAP_CATALOG,
                EvidenceContext.forSnapshot(build.snapshotIdentity()),
                PROVIDER,
                "spring.configuration-space",
                reasonCode,
                subject,
                List.of(),
                List.of(obs),
                List.of(req),
                List.of(),
                List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE, "spring.configuration-space")),
                List.of(),
                List.of(),
                List.of(note));
    }
}
