package com.evolution.analysis.javaevidence;

import com.evolution.analysis.contract.common.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JavaEvidencePreAnalysisTest {
    @Test void emptyProviderCannotEraseTheRequiredResolutionAndBuildCensus()throws Exception{
        var fixture=new JavaEvidenceTestStore.AuthorityFixture();var tables=new JavaEvidenceTables(fixture.store,100000);
        var requirements=JavaEvidencePreAnalysis.baseline(JavaEvidenceLedgerTest.scope());
        assertEquals(Set.of("java.resolution-context","java.build-validation"),requirements.stream().map(r->r.text("question")).collect(java.util.stream.Collectors.toSet()));
        var census=fixture.store.put("independently enumerated context");
        var pending=new JavaEvidenceLedger().publish(JavaEvidenceLedgerTest.scope(),census,fixture.verifier.policyIdentity(),requirements,List.of(),List.of(),Optional.empty(),Map.of(),Set.of(),Set.of(),tables,()->false);
        assertEquals(2,pending.current().active());
        assertEquals(2,JavaEvidencePreAnalysis.activeGaps(pending).stream().filter(g->g.reasonCode().equals("VALIDATION_METHOD_UNAVAILABLE")).count());
        assertEquals(1,JavaEvidencePreAnalysis.activeGaps(pending).stream().filter(g->g.reasonCode().equals("OUTPUT_CENSUS_OPEN")).count());
        var forged=new LinkedHashMap<>(pending.projection().fields());var counts=new LinkedHashMap<>(pending.projection().object("counts"));counts.put("active",0);forged.put("counts",counts);
        assertThrows(IllegalArgumentException.class,()->new JavaEvidenceCodec().create(forged));
        var empty=new JavaEvidenceLedger().publish(JavaEvidenceLedgerTest.scope(),census,fixture.verifier.policyIdentity(),List.of(),List.of(),List.of(),Optional.empty(),Map.of(),Set.of(),Set.of(),tables,()->false);
        assertThrows(java.io.IOException.class,()->JavaEvidencePreAnalysis.requireBaseline(empty,tables,()->false));
    }
}
