package com.evolution.analysis.evidence;

import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.Comparator;
import java.util.List;

/** An unanswered evidence question, independent of any particular candidate provider. */
public record EvidenceRequirement(
        Kind kind,
        String question,
        List<EvidenceSubject> requiredInputs,
        AuthorizationClass authorizationClass,
        List<String> satisfactionCriteria) implements Comparable<EvidenceRequirement> {
    public enum Kind {
        REPOSITORY_CONTENT, BUILD_MODEL, DEPENDENCY_ARTIFACT, GENERATED_SOURCE, PLATFORM_SYMBOLS,
        BYTECODE, CONFIGURATION, ISOLATED_BUILD_OUTPUT, RUNTIME_OBSERVATION, ALTERNATE_FRONTEND,
        SOURCE_OWNERSHIP, DECODED_SOURCE, EXACT_CLASSPATH, REACTOR_OUTPUT
    }
    public enum AuthorizationClass { PASSIVE, LOCAL_READ, NETWORK, ISOLATED_EXECUTION, RUNTIME_ACCESS }

    public EvidenceRequirement {
        ContractChecks.notNull(kind, "evidence requirement kind");
        question = ContractChecks.namespacedId(question, "evidence question");
        requiredInputs = ContractChecks.sortedDistinct(
                requiredInputs, Comparator.naturalOrder(), "required evidence inputs");
        ContractChecks.notNull(authorizationClass, "evidence authorization class");
        satisfactionCriteria = ContractChecks.sortedStrings(satisfactionCriteria, "satisfaction criteria");
        if (satisfactionCriteria.isEmpty()) {
            throw new IllegalArgumentException("Evidence requirement needs observable satisfaction criteria");
        }
    }

    @Override public int compareTo(EvidenceRequirement other) {
        return CanonicalJson.write(this).compareTo(CanonicalJson.write(other));
    }
}
