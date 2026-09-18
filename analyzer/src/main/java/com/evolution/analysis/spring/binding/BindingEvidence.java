package com.evolution.analysis.spring.binding;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.spring.condition.*;
import com.evolution.analysis.spring.registration.*;
import java.util.*;

/** Evidence supplied by replaceable semantic/metadata providers, never by host reflection.
 * Match proofs are specific to the WHOLE descriptor, including generic substitutions,
 * merged qualifiers, defaults and eager/non-eager type lookup semantics. */
public final class BindingEvidence {
    private BindingEvidence() {}
    public enum Lane { DIRECT, ELEMENT }
    public enum Knowledge { KNOWN, UNKNOWN, ERROR }
    public enum RuntimeKind { ORDINARY, FACTORY_BEAN, SCOPED_PROXY, UNKNOWN }
    public record Rank(Knowledge knowledge, Optional<Integer> value) {
        public Rank {
            Objects.requireNonNull(knowledge); Objects.requireNonNull(value);
            if (knowledge != Knowledge.KNOWN && value.isPresent()) throw new IllegalArgumentException("Unknown rank has no value");
        }
        public static Rank absent() { return new Rank(Knowledge.KNOWN, Optional.empty()); }
        public static Rank of(int value) { return new Rank(Knowledge.KNOWN, Optional.of(value)); }
        public static Rank unknown() { return new Rank(Knowledge.UNKNOWN, Optional.empty()); }
    }
    /** priority is effective getPriority output, NOT @Order. order is effective comparator order.
     * Factory ownership determines self references for instance @Bean methods. */
    public record Definition(BeanDefinitionCandidate.Identity candidate, LogicalValue autowireCandidate,
                             LogicalValue defaultCandidate, LogicalValue primary, LogicalValue fallback,
                             Optional<BeanDefinitionCandidate.Identity> factoryOwner, RuntimeKind runtimeKind,
                             Rank priority, Rank order, LogicalValue priorityOrdered, ConditionEvidence evidence) {
        public Definition {
            Objects.requireNonNull(candidate); Objects.requireNonNull(autowireCandidate); Objects.requireNonNull(defaultCandidate);
            Objects.requireNonNull(primary); Objects.requireNonNull(fallback); Objects.requireNonNull(factoryOwner);
            Objects.requireNonNull(runtimeKind); Objects.requireNonNull(priority); Objects.requireNonNull(order);
            Objects.requireNonNull(priorityOrdered); Objects.requireNonNull(evidence);
        }
    }
    /** qualifierMatch: null-equivalent no qualifier is represented by hasQualifier=FALSE on
     * the descriptor. TRUE with hasQualifier=TRUE can admit defaultCandidate=false.
     * fallbackGeneric is Spring's forFallbackMatch result, not @Fallback bean metadata.
     * rawType is isTypeMatch(name, requiredClass); typeLookup separately records membership
     * in beanNamesForType (which can exclude abstract or non-eager definitions). */
    public record Match(ContentDigest dependency, BeanDefinitionCandidate.Identity candidate, Lane lane,
                        LogicalValue rawType, LogicalValue typeLookup, LogicalValue strictGeneric, LogicalValue fallbackGeneric,
                        LogicalValue qualifierMatch, Knowledge operation, ConditionEvidence evidence) {
        /** Common proven case where type matching and type enumeration agree. */
        public Match(ContentDigest dependency, BeanDefinitionCandidate.Identity candidate, Lane lane,
                     LogicalValue rawType, LogicalValue strictGeneric, LogicalValue fallbackGeneric,
                     LogicalValue qualifierMatch, Knowledge operation, ConditionEvidence evidence) {
            this(dependency, candidate, lane, rawType, rawType, strictGeneric, fallbackGeneric, qualifierMatch, operation, evidence);
        }
        public Match {
            Objects.requireNonNull(dependency); Objects.requireNonNull(candidate); Objects.requireNonNull(lane);
            Objects.requireNonNull(rawType); Objects.requireNonNull(typeLookup); Objects.requireNonNull(strictGeneric); Objects.requireNonNull(fallbackGeneric);
            Objects.requireNonNull(qualifierMatch); Objects.requireNonNull(operation); Objects.requireNonNull(evidence);
        }
        String key() { return dependency.value() + ":" + candidate.value() + ":" + lane; }
    }
    static boolean valid(ConditionEvidence evidence, SpringBuildContext build) {
        return switch (evidence) {
            case ConditionEvidence.Source source -> source.span().isPresent() && build.containsSource(source);
            case ConditionEvidence.Artifact artifact -> build.containsArtifact(artifact.artifactDigest());
            case ConditionEvidence.Derived ignored -> true;
        };
    }
}
