package com.evolution.analysis.spring.registration;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.spring.condition.*;
import java.util.*;

/** Passive, query-specific evidence. No class loading or Java-name guessing occurs here. */
public final class BeanRegistrationEvidence {
    private BeanRegistrationEvidence() {}
    public enum SelectorKind { TYPE, ANNOTATION }
    public enum TypeStatus { AVAILABLE, ABSENT, UNKNOWN, ERROR }
    /** Whole framework query-type resolution, including linkage/annotation-kind checks.
     * ABSENT denotes the framework's caught missing-class outcome; ERROR denotes an
     * uncaught operational failure. Neither is inferred from an empty registry. */
    public record QueryType(SelectorKind kind, String selector, TypeStatus status, ConditionEvidence evidence) {
        public QueryType {
            Objects.requireNonNull(kind); selector = RegistrationIdentity.text(selector);
            Objects.requireNonNull(status); Objects.requireNonNull(evidence);
        }
        String key() { return kind + ":" + selector; }
    }
    public record Match(SelectorKind kind, String selector, LogicalValue value, ConditionEvidence evidence) {
        public Match {
            Objects.requireNonNull(kind); selector = RegistrationIdentity.text(selector);
            Objects.requireNonNull(value); Objects.requireNonNull(evidence);
        }
        String key() { return kind + ":" + selector; }
    }
    /** Matches describe the framework's non-eager lookup, including assignability, abstract
     * definitions, factory products and annotation lookup. Missing rows mean UNKNOWN, never FALSE.
     * Flags are effective definition metadata, not inferred annotation defaults. */
    public record Definition(BeanDefinitionCandidate.Identity candidate, List<Match> matches,
                             LogicalValue autowireCandidate, LogicalValue defaultCandidate,
                             LogicalValue primary, LogicalValue fallback, LogicalValue factoryBean,
                             ConditionEvidence evidence) {
        public Definition {
            Objects.requireNonNull(candidate);
            matches = ContractChecks.sortedDistinct(matches, Comparator.comparing(Match::key), "definition match evidence");
            Objects.requireNonNull(autowireCandidate); Objects.requireNonNull(defaultCandidate);
            Objects.requireNonNull(primary); Objects.requireNonNull(fallback); Objects.requireNonNull(factoryBean);
            Objects.requireNonNull(evidence);
        }
    }
    /** Refines one original M4B occurrence without changing or deleting that occurrence/gap.
     * The supplier must prove the WHOLE annotation query (including defaults/return-type
     * inference, resolved class names, merged attributes and search strategy). */
    public record Query(ConditionOccurrence.Identity occurrence, ConditionExpression.Bean selector,
                        List<String> ignoredTypes, List<String> parameterizedContainers,
                        RegistrationEvent.Completeness metadata, ConditionEvidence evidence) {
        public Query {
            Objects.requireNonNull(occurrence); Objects.requireNonNull(selector); Objects.requireNonNull(metadata);
            ignoredTypes = strings(ignoredTypes); parameterizedContainers = strings(parameterizedContainers);
            Objects.requireNonNull(evidence);
        }
        public ContentDigest identity() { return RegistrationIdentity.digest(this); }
    }
    private static List<String> strings(List<String> values) {
        return ContractChecks.sortedDistinct(values.stream().map(RegistrationIdentity::text).toList(),
                Comparator.naturalOrder(), "query selectors");
    }
    static boolean valid(ConditionEvidence evidence, SpringBuildContext build) {
        return switch (evidence) {
            case ConditionEvidence.Source source -> source.span().isPresent() && build.containsSource(source);
            case ConditionEvidence.Artifact artifact -> build.containsArtifact(artifact.artifactDigest());
            case ConditionEvidence.Derived ignored -> true;
        };
    }
}
