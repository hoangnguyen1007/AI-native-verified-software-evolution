package com.evolution.analysis.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evolution.analysis.contract.analysis.AnalysisManifest;
import com.evolution.analysis.contract.analysis.AnalysisProvenance;
import com.evolution.analysis.contract.assessment.AnalysisConfidence;
import com.evolution.analysis.contract.assessment.ArchitectureHealthAssessment;
import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.metrics.MetricEnvelope;
import com.evolution.analysis.contract.metrics.MetricScope;
import com.evolution.analysis.contract.metrics.MetricScopeType;
import com.evolution.analysis.contract.metrics.MetricValue;
import com.evolution.analysis.contract.semantic.Uncertainty;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AnalysisAndAssessmentContractTest {

    private static final Instant STARTED = Instant.parse("2026-09-02T03:00:00Z");
    private static final Instant COMPLETED = Instant.parse("2026-09-02T03:00:05Z");

    @Test
    void analysisIdentitySortsSetsButPreservesSemanticallyMeaningfulClasspathOrder() {
        AnalysisManifest first = ContractFixtures.manifest(
                List.of(ContractFixtures.sourceA(), ContractFixtures.sourceB()),
                List.of(
                        ContractFixtures.dependency("z:dep:1", "z"),
                        ContractFixtures.dependency("a:dep:1", "a")),
                Map.of("language", "java", "release", "21"));
        AnalysisManifest reordered = ContractFixtures.manifest(
                List.of(ContractFixtures.sourceB(), ContractFixtures.sourceA()),
                List.of(
                        ContractFixtures.dependency("a:dep:1", "a"),
                        ContractFixtures.dependency("z:dep:1", "z")),
                Map.of("release", "21", "language", "java"));
        AnalysisManifest unorderedSetsOnly = ContractFixtures.manifest(
                List.of(ContractFixtures.sourceB(), ContractFixtures.sourceA()),
                List.of(
                        ContractFixtures.dependency("z:dep:1", "z"),
                        ContractFixtures.dependency("a:dep:1", "a")),
                Map.of("release", "21", "language", "java"));
        AnalysisManifest changedClasspath = ContractFixtures.manifest(
                List.of(ContractFixtures.sourceA(), ContractFixtures.sourceB()),
                List.of(
                        ContractFixtures.dependency("z:dep:1", "changed"),
                        ContractFixtures.dependency("a:dep:1", "a")),
                Map.of("language", "java", "release", "21"));

        assertNotEquals(first, reordered);
        assertNotEquals(first.identity(), reordered.identity());
        assertEquals(first, unorderedSetsOnly);
        assertNotEquals(first.identity(), changedClasspath.identity());
    }

    @Test
    void manifestRejectsSourceDocumentsWhoseModuleIsAbsent() {
        AnalysisManifest valid = ContractFixtures.manifest(
                List.of(ContractFixtures.sourceA()), List.of(), Map.of());

        assertThrows(
                IllegalArgumentException.class,
                () -> AnalysisManifest.create(
                        valid.manifestVersion(),
                        valid.snapshot(),
                        List.of(),
                        valid.classpath(),
                        valid.configuration(),
                        valid.analyzer(),
                        valid.ruleSet(),
                        valid.graphSchema()));
    }

    @Test
    void provenanceUsesTheManifestIdentityAndRejectsBackwardsTime() {
        AnalysisManifest manifest = ContractFixtures.manifest(
                List.of(ContractFixtures.sourceA()), List.of(), Map.of());
        AnalysisProvenance provenance = AnalysisProvenance.create(
                manifest, STARTED, COMPLETED, List.of(), List.of("No production parser was run."));

        assertEquals(manifest.identity(), provenance.analysisIdentity());
        assertThrows(
                IllegalArgumentException.class,
                () -> AnalysisProvenance.create(
                        manifest, COMPLETED, STARTED, List.of(), List.of()));
    }

    @Test
    void metricEnvelopeRejectsFalsePrecisionForWithheldAndPartialStates() {
        AnalysisManifest manifest = ContractFixtures.manifest(
                List.of(ContractFixtures.sourceA()), List.of(), Map.of());
        MetricScope scope = new MetricScope(
                MetricScopeType.REPOSITORY, ContractFixtures.REPOSITORY.value());

        assertThrows(
                IllegalArgumentException.class,
                () -> metric(
                        manifest,
                        scope,
                        AssessmentStatus.WITHHELD,
                        Optional.of(new MetricValue(new BigDecimal("42"), "count")),
                        List.of(),
                        List.of("Evidence missing.")));
        assertThrows(
                IllegalArgumentException.class,
                () -> metric(
                        manifest,
                        scope,
                        AssessmentStatus.PARTIAL,
                        Optional.of(new MetricValue(new BigDecimal("42"), "count")),
                        List.of(),
                        List.of()));
    }

    @Test
    void metricScopeRejectsUnstableHumanLabels() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MetricScope(MetricScopeType.REPOSITORY, "my-repository"));
    }

    @Test
    void allRequiredAssessmentStatesHaveExplicitValueRules() {
        AnalysisManifest manifest = ContractFixtures.manifest(
                List.of(ContractFixtures.sourceA()), List.of(), Map.of());
        MetricScope scope = new MetricScope(
                MetricScopeType.REPOSITORY, ContractFixtures.REPOSITORY.value());
        Uncertainty missing = new Uncertainty(
                "metric.missing-input", "One input is unresolved.", List.of("relationship"));

        assertEquals(
                AssessmentStatus.COMPLETE,
                metric(
                                manifest,
                                scope,
                                AssessmentStatus.COMPLETE,
                                Optional.of(new MetricValue(new BigDecimal("7"), "count")),
                                List.of(),
                                List.of())
                        .status());
        assertEquals(
                AssessmentStatus.PARTIAL,
                metric(
                                manifest,
                                scope,
                                AssessmentStatus.PARTIAL,
                                Optional.of(new MetricValue(new BigDecimal("6"), "count")),
                                List.of(missing),
                                List.of())
                        .status());
        assertEquals(
                AssessmentStatus.WITHHELD,
                metric(
                                manifest,
                                scope,
                                AssessmentStatus.WITHHELD,
                                Optional.empty(),
                                List.of(missing),
                                List.of())
                        .status());
        assertEquals(
                AssessmentStatus.NOT_APPLICABLE,
                metric(
                                manifest,
                                scope,
                                AssessmentStatus.NOT_APPLICABLE,
                                Optional.empty(),
                                List.of(),
                                List.of("Metric does not apply to this scope."))
                        .status());
    }

    @Test
    void architectureHealthAndAnalysisConfidenceAreDifferentContractTypes() {
        AnalysisManifest manifest = ContractFixtures.manifest(
                List.of(ContractFixtures.sourceA()), List.of(), Map.of());
        ArchitectureHealthAssessment health = new ArchitectureHealthAssessment(
                new VersionedIdentifier("architecture.health", "1"),
                AssessmentStatus.WITHHELD,
                Optional.empty(),
                Map.of(),
                List.of("metric:dependency-direction"),
                manifest.identity(),
                manifest.configuration().identity(),
                List.of(new Uncertainty(
                        "assessment.insufficient-evidence",
                        "Required semantic evidence is incomplete.",
                        List.of("java.calls"))),
                List.of(),
                COMPLETED);
        AnalysisConfidence confidence = new AnalysisConfidence(
                new VersionedIdentifier("analysis.confidence", "1"),
                AssessmentStatus.PARTIAL,
                Optional.of(new BigDecimal("0.72")),
                List.of("semantic-resolution-coverage"),
                manifest.identity(),
                manifest.configuration().identity(),
                List.of(new Uncertainty(
                        "confidence.partial-classpath",
                        "Classpath evidence is incomplete.",
                        List.of("external-dependencies"))),
                List.of(),
                COMPLETED);

        assertEquals(AssessmentStatus.WITHHELD, health.status());
        assertEquals(AssessmentStatus.PARTIAL, confidence.status());
        assertEquals(new BigDecimal("0.72"), confidence.value().orElseThrow());
    }

    private static MetricEnvelope metric(
            AnalysisManifest manifest,
            MetricScope scope,
            AssessmentStatus status,
            Optional<MetricValue> value,
            List<Uncertainty> uncertainties,
            List<String> limitations) {
        return new MetricEnvelope(
                new VersionedIdentifier("inventory.types", "1"),
                "Types",
                "Number of declared types.",
                scope,
                status,
                value,
                Optional.empty(),
                List.of("entity:type"),
                manifest.identity(),
                manifest.configuration().identity(),
                uncertainties,
                limitations,
                COMPLETED);
    }
}
