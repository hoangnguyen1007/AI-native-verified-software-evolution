package com.evolution.analysis.query;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.*;
import static com.evolution.analysis.query.ArchitectureQuery.*;

/** Additive M6 predicate contract: OR of nonempty AND terms over context-qualified logical edges. */
public final class ArchitectureCondition {
    public static final VersionedIdentifier VERSION = new VersionedIdentifier("architecture.condition", "m6.1-v1");
    public static final VersionedIdentifier RULE_VERSION = new VersionedIdentifier("architecture.rule-condition", "m6.2-v1");
    /** Unknown selector membership gates the whole term, not its individual edge facts. */
    public record Alternative(Term term, boolean eligible) {
        public Alternative { Objects.requireNonNull(term); }
    }
    /** Empty is false; an open denominator adds unresolved residue across the modeled universe. */
    public record RulePredicate(List<Alternative> alternatives, boolean complete) {
        public RulePredicate {
            alternatives=alternatives.stream().distinct().sorted(Comparator.comparing(CanonicalJson::write)).toList();
            if(alternatives.size()>50)throw new IllegalArgumentException("Rule predicate supports at most 50 alternatives");
        }
    }
    public record Term(List<String> edges) {
        public Term { edges=ContractChecks.sortedDistinct(edges,Comparator.naturalOrder(),"predicate edges");
            if(edges.isEmpty()||edges.size()>20)throw new IllegalArgumentException("Predicate term needs 1..20 logical edges"); }
    }
    public record Predicate(List<Term> alternatives) {
        public Predicate {alternatives=alternatives.stream().distinct().sorted(Comparator.comparing(CanonicalJson::write)).toList();
            if(alternatives.isEmpty()||alternatives.size()>50)throw new IllegalArgumentException("Predicate needs 1..50 alternatives");}
    }
    public record Evaluation(ContentDigest jointRegion, Classification classification, Optional<Witness> witness,
                             Optional<Witness> counterWitness, List<ContentDigest> evidence, List<String> regions) {
        public Evaluation {Objects.requireNonNull(jointRegion);Objects.requireNonNull(classification);Objects.requireNonNull(witness);
            Objects.requireNonNull(counterWitness);evidence=List.copyOf(evidence);regions=List.copyOf(regions);}
    }
    private ArchitectureCondition() {}
}
