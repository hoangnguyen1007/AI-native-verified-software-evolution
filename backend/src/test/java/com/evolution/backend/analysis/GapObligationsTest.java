package com.evolution.backend.analysis;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GapObligationsTest {
    @Test void separatesObligationsOnlyAnIsolatedExecutorCanCloseFromLimitsOfThePassiveAnalysisItself() {
        var reasons=new TreeMap<String,Long>(Map.of("NATIVE_MODEL_REQUIRED",11L,"ANALYZED_PLATFORM_REQUIRED",24L,"GRADLE_EFFECTIVE_CONTEXT_REQUIRED",2L,
                "SEMANTIC_UNRESOLVED",16L,"DYNAMIC_BUILD_LOGIC",12L,"SOME_FUTURE_REASON",3L));
        var split=GapObligations.classify(reasons);
        assertEquals(37L,split.get(GapObligations.ISOLATED_EXECUTION));
        assertEquals(31L,split.get(GapObligations.PASSIVE_LIMITS),"Unknown reasons never disappear into the executor bucket");
        assertEquals(reasons.values().stream().mapToLong(Long::longValue).sum(),split.values().stream().mapToLong(Long::longValue).sum());
        assertEquals(Map.of(GapObligations.ISOLATED_EXECUTION,0L,GapObligations.PASSIVE_LIMITS,0L),GapObligations.classify(Map.of()));
    }
}

