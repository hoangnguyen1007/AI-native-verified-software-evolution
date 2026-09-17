package com.evolution.analysis.spring.condition;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.spring.*;
import java.util.*;

/** Additive M4C.2 literal bean-query refinement. Original M4B rows and gaps remain intact.
 * Class literals, inferred return types, composed metadata and explicit enum expressions
 * require a supplying semantic metadata provider; source spellings are never resolved here. */
public final class BeanConditionLowering {
    public static final VersionedIdentifier PROVIDER = new VersionedIdentifier("spring.bean-condition-lowering", "m4c.2");
    public enum Outcome { REFINED, NORMALIZATION_REQUIRED, LIMIT_EXCEEDED, NOT_BEAN_CONDITION }
    public record Query(ConditionExpression.Bean selector, List<String> ignoredTypes, ConditionEvidence evidence) {
        public Query { ignoredTypes = List.copyOf(ignoredTypes); Objects.requireNonNull(evidence); }
    }
    public record Row(ConditionOccurrence.Identity occurrence, Outcome outcome, Optional<Query> query) {
        public Row {
            Objects.requireNonNull(occurrence); Objects.requireNonNull(outcome); Objects.requireNonNull(query);
            if ((outcome == Outcome.REFINED) != query.isPresent()) throw new IllegalArgumentException("Refinement outcome mismatch");
        }
    }
    private BeanConditionLowering() {}
    public static List<Row> lower(SpringBuildContext build, SpringMechanismInventory inventory,
                                  ConditionEvidenceLowering.Result original, String container, int maxMetadataCharacters) {
        return lower(build, inventory, original, container, maxMetadataCharacters, 100000);
    }
    public static List<Row> lower(SpringBuildContext build, SpringMechanismInventory inventory,
                                  ConditionEvidenceLowering.Result original, String container, int maxMetadataCharacters, int maxSelectorCells) {
        if (maxMetadataCharacters < 1 || maxSelectorCells < 0) throw new IllegalArgumentException("Invalid metadata bound");
        int remaining = maxSelectorCells;
        Map<ContentDigest, SpringMechanismInventory.RawObservation> raw = new HashMap<>();
        inventory.rawObservations().forEach(r -> raw.put(r.identity(), r));
        Map<ConditionOccurrence.Identity, ConditionEvidenceLowering.Row> lowered = new HashMap<>();
        original.rows().forEach(r -> r.occurrence().ifPresent(o -> lowered.put(o.identity(), r)));
        Set<ConditionOccurrence.Identity> beanRows = new HashSet<>();
        original.issues().stream().filter(i -> i.reason() == ConditionProcessing.Reason.BEAN_STATE_REQUIRED)
                .forEach(i -> i.occurrence().ifPresent(beanRows::add));
        boolean context = build.analysisIdentity().equals(inventory.analysis()) && build.containsFrameworkEvidence(inventory.frameworkEvidence())
                && inventory.identity().equals(original.inventoryIdentity());
        var originalIdentity = original.identity();
        List<Row> result = new ArrayList<>();
        for (var occurrence : original.occurrences()) {
            if (!beanRows.contains(occurrence.identity())) {
                result.add(new Row(occurrence.identity(), Outcome.NOT_BEAN_CONDITION, Optional.empty())); continue;
            }
            Optional<Query> query = Optional.empty();
            var row = lowered.get(occurrence.identity()); var observation = row == null ? null : raw.get(row.rawObservation());
            if (remaining == 0 || observation != null && observation.spelling().length() > maxMetadataCharacters) {
                result.add(new Row(occurrence.identity(), Outcome.LIMIT_EXCEEDED, Optional.empty())); continue;
            }
            if (context && observation != null && observation.spelling().length() <= maxMetadataCharacters) {
                String annotation = ConditionEvidenceLowering.exactAnnotationType(observation, inventory.frameworkEvidence());
                var predicate = predicate(annotation);
                if (predicate != null) try {
                    var attributes = LiteralConditionAnnotation.parse(observation.spelling());
                    Set<String> allowed = predicate == ConditionExpression.BeanPredicate.SINGLE_CANDIDATE ? Set.of("type")
                            : predicate == ConditionExpression.BeanPredicate.MISSING ? Set.of("type", "name", "ignoredType") : Set.of("type", "name");
                    if (!allowed.containsAll(attributes.keySet())) throw LiteralConditionAnnotation.unsupported();
                    var types = LiteralConditionAnnotation.strings(attributes, "type", List.of()).stream().distinct().toList();
                    var names = LiteralConditionAnnotation.strings(attributes, "name", List.of()).stream().distinct().toList();
                    var ignored = LiteralConditionAnnotation.strings(attributes, "ignoredType", List.of()).stream().distinct().sorted().toList();
                    if (predicate == ConditionExpression.BeanPredicate.SINGLE_CANDIDATE && (types.size() != 1 || types.getFirst().isBlank())) throw LiteralConditionAnnotation.unsupported();
                    int cost = 1 + types.size() + names.size() + ignored.size();
                    if (cost > remaining) {
                        remaining = 0; result.add(new Row(occurrence.identity(), Outcome.LIMIT_EXCEEDED, Optional.empty())); continue;
                    }
                    var selector = new ConditionExpression.Bean(predicate, container, types, names, List.of(), ConditionExpression.Search.ALL);
                    var evidence = new ConditionEvidence.Derived(List.of(originalIdentity, observation.identity(), occurrence.declarationEvidenceKey().identity()),
                            PROVIDER, "literal-whole-annotation-query");
                    query = Optional.of(new Query(selector, ignored, evidence));
                    remaining -= cost;
                } catch (IllegalArgumentException unsupported) {
                    // Narrow literal decoder/IR construction only. No target exception text is exposed.
                }
            }
            result.add(new Row(occurrence.identity(), query.isPresent() ? Outcome.REFINED : Outcome.NORMALIZATION_REQUIRED, query));
        }
        return List.copyOf(result);
    }
    public static ConditionExpression.BeanPredicate predicate(String annotation) {
        if (annotation == null) return null;
        return switch (annotation) {
            case "org.springframework.boot.autoconfigure.condition.ConditionalOnBean" -> ConditionExpression.BeanPredicate.PRESENT;
            case "org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean" -> ConditionExpression.BeanPredicate.MISSING;
            case "org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate" -> ConditionExpression.BeanPredicate.SINGLE_CANDIDATE;
            default -> null;
        };
    }
    /** Exact annotation kind for validating externally normalized refinements. */
    public static Optional<ConditionExpression.BeanPredicate> predicate(SpringMechanismInventory inventory,
            ConditionEvidenceLowering.Result original, ConditionOccurrence.Identity occurrence) {
        if (!inventory.identity().equals(original.inventoryIdentity())) return Optional.empty();
        var row = original.rows().stream().filter(r -> r.occurrence().filter(o -> o.identity().equals(occurrence)).isPresent()).findFirst();
        if (row.isEmpty()) return Optional.empty();
        return inventory.rawObservations().stream().filter(r -> r.identity().equals(row.orElseThrow().rawObservation())).findFirst()
                .map(r -> ConditionEvidenceLowering.exactAnnotationType(r, inventory.frameworkEvidence())).map(BeanConditionLowering::predicate);
    }
}
