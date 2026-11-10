package com.evolution.backend.analysis;

import java.util.*;

/** Groups the unchanged gap-reason denominator by who can close it. This never removes, hides or reclassifies a gap:
 * a reason is in the isolated-execution bucket only when it names native build/toolchain/generator evidence that passive analysis cannot produce. */
public final class GapObligations {
    public static final String ISOLATED_EXECUTION="ISOLATED_EXECUTION_REQUIRED";
    public static final String PASSIVE_LIMITS="PASSIVE_ANALYSIS_LIMITS";
    private static final Set<String> EXECUTOR_REASONS=Set.of("NATIVE_MODEL_REQUIRED","BUILD_TOOLCHAIN_REQUIRED","EXACT_DEPENDENCY_CLOSURE_REQUIRED",
            "ANALYZED_PLATFORM_REQUIRED","GENERATOR_CLOSURE_REQUIRED","NATIVE_BUILD_VALIDATION_REQUIRED","NATIVE_CONTEXT_REQUIRED",
            "PROTOBUF_GENERATOR_REQUIRED","GRADLE_EFFECTIVE_CONTEXT_REQUIRED");
    private GapObligations() {}
    public static Map<String,Long> classify(Map<String,Long> byReason) {
        long executor=0,passive=0;
        for(var entry:byReason.entrySet()) {
            if(EXECUTOR_REASONS.contains(entry.getKey()))executor+=entry.getValue();else passive+=entry.getValue();
        }
        var result=new TreeMap<String,Long>();result.put(ISOLATED_EXECUTION,executor);result.put(PASSIVE_LIMITS,passive);
        return Collections.unmodifiableMap(result);
    }
}

