package com.evolution.benchmark.frontend;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.evolution.analysis.contract.semantic.SemanticStatus;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class M1ContractMapperTest {

    @Test
    void mapsUnresolvedObservationToExplicitM1UnresolvedOccurrence() {
        Observation observation = Observation.unresolved(
                RelationshipCategory.CALLS,
                "fixture.Service#run()",
                "missing.api.MissingService.call()",
                Path.of("fixture/Service.java"),
                new Span(10, 9, 10, 31),
                "missing external dependency");

        var occurrence = M1ContractMapper.forFixture().map(observation);

        assertEquals(SemanticStatus.UNRESOLVED, occurrence.status());
        assertEquals("missing.api.MissingService.call()",
                ((com.evolution.analysis.contract.semantic.RelationshipTarget.Unresolved)
                        occurrence.relationship().target()).stableReference());
    }
}
