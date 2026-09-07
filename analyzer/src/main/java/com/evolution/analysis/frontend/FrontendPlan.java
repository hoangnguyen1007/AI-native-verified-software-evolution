package com.evolution.analysis.frontend;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.ContractChecks;
import java.util.Optional;

/** Exact source-plan and upstream evidence identities bound into one frontend request. */
public record FrontendPlan(
        Optional<Integer> syntaxLevel,
        Optional<Integer> bytecodeTarget,
        boolean preview,
        ContentDigest classpathManifest,
        ContentDigest sourceDecoding) {
    public FrontendPlan {
        syntaxLevel = positive(syntaxLevel, "syntax level");
        bytecodeTarget = positive(bytecodeTarget, "bytecode target");
        ContractChecks.notNull(classpathManifest, "classpath manifest identity");
        ContractChecks.notNull(sourceDecoding, "source decoding identity");
    }

    public static FrontendPlan legacy() {
        return new FrontendPlan(Optional.of(21), Optional.of(21), false,
                ContentDigest.sha256Utf8("m2-explicit-classpath"),
                ContentDigest.sha256Utf8("m2-strict-utf8"));
    }

    private static Optional<Integer> positive(Optional<Integer> value, String name) {
        ContractChecks.notNull(value, name);
        value.ifPresent(number -> {
            if (number < 1) throw new IllegalArgumentException(name + " must be positive");
        });
        return value;
    }
}
