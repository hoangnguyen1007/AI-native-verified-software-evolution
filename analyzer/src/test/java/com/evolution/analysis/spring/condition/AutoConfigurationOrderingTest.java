package com.evolution.analysis.spring.condition;

import com.evolution.analysis.spring.registration.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AutoConfigurationOrderingTest {
    static AutoConfigurationOrdering.Metadata metadata(RegistrationDiscoveryTest.Fixture f, String name, int order, List<String> before, List<String> after) {
        return new AutoConfigurationOrdering.Metadata(name, true, AutoConfigurationOrdering.Availability.PRESENT, order, before, after, f.source().evidence());
    }
    @Test void bootSortsByOrderThenWalksAfterDependenciesRatherThanUsingPriorityTopologicalSelection() throws Exception {
        var f = RegistrationDiscoveryTest.fixture("@Profile(\"dev\")");
        // Boot first visits A, recursively visits C, then completes A; B is still pending.
        var inputs = List.of(metadata(f, "example.A", 0, List.of(), List.of("example.C")),
                metadata(f, "example.B", 1, List.of(), List.of()), metadata(f, "example.C", 2, List.of(), List.of()));
        var result = AutoConfigurationOrdering.order(f.source().build(), f.source().inventory().frameworkEvidence(), inputs, AutoConfigurationOrdering.Limits.conservative());
        assertEquals(AutoConfigurationOrdering.Status.ORDERED, result.status());
        assertEquals(List.of("example.C", "example.A", "example.B"), result.orderedClasses());
    }
    @Test void metadataOnlyIntermediateClassesStillConstrainSelectedClasses() throws Exception {
        var f = RegistrationDiscoveryTest.fixture("@Profile(\"dev\")");
        var a = metadata(f, "A", 0, List.of(), List.of("M"));
        var z = metadata(f, "Z", 0, List.of(), List.of());
        var middle = new AutoConfigurationOrdering.Metadata("M", false, AutoConfigurationOrdering.Availability.PRESENT, 0, List.of(), List.of("Z"), f.source().evidence());
        var result = AutoConfigurationOrdering.order(f.source().build(), f.source().inventory().frameworkEvidence(), List.of(a, z, middle), AutoConfigurationOrdering.Limits.conservative());
        assertEquals(List.of("Z", "A"), result.orderedClasses());
        assertFalse(result.orderedClasses().contains("M"));
    }
    @Test void beforeEdgesOverrideOrderAndTiesUseThePinnedBootAlphabeticalRule() throws Exception {
        var f = RegistrationDiscoveryTest.fixture("@Profile(\"dev\")");
        var a = metadata(f, "A", 0, List.of(), List.of()); var b = metadata(f, "B", 0, List.of(), List.of());
        var z = metadata(f, "Z", 100, List.of("A"), List.of());
        var first = AutoConfigurationOrdering.order(f.source().build(), f.source().inventory().frameworkEvidence(), List.of(b, z, a), AutoConfigurationOrdering.Limits.conservative());
        var second = AutoConfigurationOrdering.order(f.source().build(), f.source().inventory().frameworkEvidence(), List.of(a, b, z), AutoConfigurationOrdering.Limits.conservative());
        assertEquals(List.of("Z", "A", "B"), first.orderedClasses()); assertEquals(first.identity(), second.identity());
    }
    @Test void absentMetadataTargetsAreDifferentFromUnknownOrMissingEvidence() throws Exception {
        var f = RegistrationDiscoveryTest.fixture("@Profile(\"dev\")"); var a = metadata(f, "A", 0, List.of(), List.of("External"));
        for (var availability : AutoConfigurationOrdering.Availability.values()) {
            var external = new AutoConfigurationOrdering.Metadata("External", false, availability, 0, List.of(), List.of(), f.source().evidence());
            var result = AutoConfigurationOrdering.order(f.source().build(), f.source().inventory().frameworkEvidence(), List.of(a, external), AutoConfigurationOrdering.Limits.conservative());
            assertEquals(availability == AutoConfigurationOrdering.Availability.UNKNOWN ? AutoConfigurationOrdering.Status.UNKNOWN : AutoConfigurationOrdering.Status.ORDERED, result.status());
        }
        var missing = AutoConfigurationOrdering.order(f.source().build(), f.source().inventory().frameworkEvidence(), List.of(a), AutoConfigurationOrdering.Limits.conservative());
        assertEquals(AutoConfigurationOrdering.Status.UNKNOWN, missing.status()); assertTrue(missing.orderedClasses().isEmpty()); assertFalse(missing.capabilityGaps().isEmpty());
    }
    @Test void cycleAndStepExhaustionReturnNoFabricatedOrderAndRetainTheSelectedDenominator() throws Exception {
        var f = RegistrationDiscoveryTest.fixture("@Profile(\"dev\")"); var a = metadata(f, "A", 0, List.of(), List.of("B")); var b = metadata(f, "B", 0, List.of(), List.of("A"));
        var cycle = AutoConfigurationOrdering.order(f.source().build(), f.source().inventory().frameworkEvidence(), List.of(a, b), AutoConfigurationOrdering.Limits.conservative());
        assertEquals(AutoConfigurationOrdering.Status.ERROR, cycle.status()); assertTrue(cycle.orderedClasses().isEmpty()); assertEquals(2, cycle.selectedClasses().size());
        for (var limits : List.of(new AutoConfigurationOrdering.Limits(1, 100), new AutoConfigurationOrdering.Limits(10, 1))) {
            var limited = AutoConfigurationOrdering.order(f.source().build(), f.source().inventory().frameworkEvidence(), List.of(a, b), limits);
            assertEquals(AutoConfigurationOrdering.Status.LIMIT_EXCEEDED, limited.status()); assertEquals(2, limited.selectedClasses().size()); assertTrue(limited.orderedClasses().isEmpty());
            assertEquals(limited.identity(), AutoConfigurationOrdering.order(f.source().build(), f.source().inventory().frameworkEvidence(), List.of(b, a), limits).identity());
        }
    }
    @Test void missingSelectedClassCannotBecomeAValidEmptyConfigurationAndDuplicatesAreRejected() throws Exception {
        var f = RegistrationDiscoveryTest.fixture("@Profile(\"dev\")");
        var absent = new AutoConfigurationOrdering.Metadata("A", true, AutoConfigurationOrdering.Availability.ABSENT, 0, List.of(), List.of(), f.source().evidence());
        assertEquals(AutoConfigurationOrdering.Status.UNKNOWN, AutoConfigurationOrdering.order(f.source().build(), f.source().inventory().frameworkEvidence(), List.of(absent), AutoConfigurationOrdering.Limits.conservative()).status());
        assertThrows(IllegalArgumentException.class, () -> AutoConfigurationOrdering.order(f.source().build(), f.source().inventory().frameworkEvidence(), List.of(absent, absent), AutoConfigurationOrdering.Limits.conservative()));
    }
}
