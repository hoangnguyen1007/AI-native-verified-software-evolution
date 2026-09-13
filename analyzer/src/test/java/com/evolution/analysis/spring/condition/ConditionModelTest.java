package com.evolution.analysis.spring.condition;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.input.ConditionTestInputs;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.spring.condition.ConfigurationSpaceTest.*;
import static com.evolution.analysis.spring.condition.ConditionExpressionTest.*;
import static com.evolution.analysis.input.ConditionTestInputs.EVIDENCE;

class ConditionModelTest {
    static ConditionExpression opaque(ConditionExpression.OpaqueReason reason) {
        return ConditionExpression.atom(SEMANTICS, new ConditionExpression.Opaque(ContentDigest.sha256Utf8("same-computation"), reason));
    }
    static ConditionExpression bean() {
        return ConditionExpression.atom(SEMANTICS, new ConditionExpression.Bean(ConditionExpression.BeanPredicate.MISSING,
                "fixture-container", List.of("fixture.Service"), List.of(), List.of(), ConditionExpression.Search.CURRENT));
    }
    static Set<ConditionModel.Reason> reasons(ConditionModel result) {
        return result.problems().stream().map(ConditionModel.Problem::reason).collect(java.util.stream.Collectors.toSet());
    }

    @Test void closedRowsRetainSharedExpressionsAndEveryDependencyClassWithDeterministicGaps() {
        var profile = profile("dev");
        var build = ConditionExpression.atom(SEMANTICS, new ConditionExpression.Build(ConditionExpression.BuildPredicate.CLASS_PRESENT,
                "fixture.Type", BUILD.identity(), LogicalValue.TRUE, EVIDENCE));
        var rows = List.of(occurrence(profile, 0), occurrence(profile, 1), occurrence(build, 2),
                occurrence(bean(), 3), occurrence(opaque(ConditionExpression.OpaqueReason.CUSTOM_CODE), 4));
        var model = ConditionModel.create(space(List.of(profileDomain("dev"))), rows);
        assertEquals(new ConditionModel.Coverage(5, 4, 1, 0), model.coverage());
        assertEquals(4, model.inspectedExpressions().size());
        assertEquals(ConditionModel.Status.PARTIAL, model.status());
        assertEquals(ConditionModel.Feasibility.NOT_EVALUATED, model.feasibility());
        assertNotEquals(rows.get(0).identity(), rows.get(1).identity());
        assertEquals(Set.of(ConditionExpression.Dependency.BUILD_CONTEXT), build.dependencies());
        assertEquals(Set.of(ConditionExpression.Dependency.BEAN_STATE), bean().dependencies());
        var gap = model.capabilityGaps().getFirst();
        assertEquals(CapabilityGapRecord.SCHEMA, gap.schemaVersion());
        assertEquals(EVIDENCE.spans(), gap.sourceSpans());
        assertEquals(EvidenceRequirement.Kind.RUNTIME_OBSERVATION, gap.evidenceRequirements().getFirst().kind());
        assertEquals(EvidenceRequirement.AuthorizationClass.RUNTIME_ACCESS, gap.evidenceRequirements().getFirst().authorizationClass());
        assertEquals(model.inputIdentity(), gap.observationReferences().getFirst().sourceResultIdentity());
        assertTrue(gap.candidateProviders().isEmpty());
        var reversed = ConditionModel.create(space(List.of(profileDomain("dev"))), rows.reversed());
        assertEquals(model.identity(), reversed.identity());
        assertEquals(CanonicalJson.write(model.canonicalForm()), CanonicalJson.write(reversed.canonicalForm()));
    }
    @Test void opaqueUnrecognizedVersionsAndMissingSpansNeverBecomeFalseOrDisappear() {
        List<ConditionOccurrence> rows = new ArrayList<>();
        for (var reason : ConditionExpression.OpaqueReason.values()) rows.add(occurrence(opaque(reason), rows.size()));
        var noSpan = new ConditionEvidence.Source(ConditionTestInputs.DOCUMENT.identity(), ConditionTestInputs.DOCUMENT.contentDigest(), Optional.empty(), 0);
        rows.add(new ConditionOccurrence(profile("unknown"), noSpan, "/missing-span", "condition", 9));
        var model = ConditionModel.create(space(List.of()), rows);
        assertEquals(new ConditionModel.Coverage(5, 0, 5, 0), model.coverage());
        assertEquals(Set.of(ConditionModel.Reason.OPAQUE_CONDITION, ConditionModel.Reason.UNSUPPORTED_PREDICATE,
                ConditionModel.Reason.VERSION_FRAGMENT_NOT_VALIDATED, ConditionModel.Reason.MISSING_SOURCE_SPAN,
                ConditionModel.Reason.DOMAIN_NOT_DECLARED), reasons(model));
        assertEquals(model.problems().size(), model.capabilityGaps().size());
    }
    @Test void everyDeterministicTraversalLimitClosesRowsAndReplaysIdentically() {
        var rows = List.of(occurrence(profile("a"), 1), occurrence(profile("b"), 2),
                occurrence(ConditionExpression.not(ConditionExpression.not(profile("a"))), 3));
        var domains = List.of(profileDomain("a"), profileDomain("b"));
        var rowLimited = ConditionModel.create(space(domains, List.of(), new ConfigurationSpace.Limits(12,10,100,1,100,100)), rows);
        assertEquals(3, rowLimited.coverage().inputRows()); assertEquals(2, rowLimited.coverage().unexpandedRows());
        assertTrue(reasons(rowLimited).contains(ConditionModel.Reason.CONDITION_ROW_LIMIT));
        var depthLimited = ConditionModel.create(space(domains, List.of(), new ConfigurationSpace.Limits(12,10,100,10,100,1)), rows);
        assertEquals(1, depthLimited.coverage().unexpandedRows());
        assertTrue(reasons(depthLimited).contains(ConditionModel.Reason.EXPRESSION_DEPTH_LIMIT));
        var visitSpace = space(domains, List.of(), new ConfigurationSpace.Limits(12,10,100,10,1,100));
        var visits = ConditionModel.create(visitSpace, rows);
        assertEquals(1, visits.inspectedExpressions().size());
        assertEquals(3, visits.sourceRows().size(), "bounded expansion must retain the original immutable formulas");
        assertEquals(2, visits.coverage().unexpandedRows());
        assertTrue(reasons(visits).contains(ConditionModel.Reason.EXPRESSION_VISIT_LIMIT));
        assertEquals(visits.identity(), ConditionModel.create(visitSpace, rows.reversed()).identity());
    }
    @Test void opaqueAndStatefulConstraintsRemainExplicitAndDoNotDefineFeasibleWorlds() {
        var constraints = List.of(occurrence(bean(), 0), occurrence(opaque(ConditionExpression.OpaqueReason.CUSTOM_CODE), 1));
        var model = ConditionModel.create(space(List.of(), constraints, ConfigurationSpace.Limits.conservative()), constraints);
        assertEquals(2, model.coverage().inputRows(), "constraint references do not double-count an occurrence");
        assertTrue(reasons(model).containsAll(Set.of(ConditionModel.Reason.STATE_DEPENDENT_CONSTRAINT, ConditionModel.Reason.OPAQUE_CONSTRAINT)));
        assertEquals(0, model.coverage().representedRows(), "invalid constraint roles must qualify their own denominator rows");
        assertTrue(model.problems().stream().filter(problem -> problem.reason() == ConditionModel.Reason.STATE_DEPENDENT_CONSTRAINT)
                .allMatch(problem -> problem.occurrence().isPresent()), "constraint gaps must be linked to their occurrence");
        assertEquals(ConditionModel.Feasibility.NOT_EVALUATED, model.feasibility());
        assertThrows(IllegalArgumentException.class, () -> ConditionModel.create(space(List.of()), List.of(constraints.getFirst(), constraints.getFirst())));
    }
    @Test void otherPartitionsAreNeverTreatedAsProvenByJustSupplyingAProofHash() {
        for (var proof : List.of(Optional.<ContentDigest>empty(), Optional.of(ContentDigest.sha256Utf8("unverified-proof")))) {
            var domain = new FiniteDomain(new FiniteDomain.Variable(FiniteDomain.Kind.PROPERTY, "x"),
                    List.of(FiniteDomain.Value.missing(), FiniteDomain.Value.other(proof)), EVIDENCE);
            var result = ConditionModel.create(space(List.of(domain)), List.of());
            assertEquals(Set.of(ConditionModel.Reason.OTHER_ABSTRACTION_NOT_VALIDATED), reasons(result));
        }
    }
    @Test void allSourceAvailabilityKindsAndDanglingGuardsRemainAddressable() {
        for (var availability : ConfigurationEnvelope.Availability.values()) {
            var document = new ConfigurationEnvelope.Document(EVIDENCE, Optional.of(profile("absent").identity()), List.of(), availability);
            var envelope = new ConfigurationEnvelope(ConfigurationEnvelope.Layer.REPOSITORY, List.of(document), List.of(), ContentDigest.sha256Utf8("policy"));
            var result = ConditionModel.create(withEnvelope(space(List.of()), envelope, List.of()), List.of());
            assertTrue(reasons(result).contains(ConditionModel.Reason.UNRESOLVED_ACTIVATION));
            assertEquals(availability != ConfigurationEnvelope.Availability.AVAILABLE && availability != ConfigurationEnvelope.Availability.MISSING_OPTIONAL,
                    reasons(result).contains(ConditionModel.Reason.CONFIG_SOURCE_UNAVAILABLE));
        }
    }
    @Test void unknownBuildEvidenceAndWrongExactContextsCannotSupportCertainPredicates() {
        var wrong = ConditionTestInputs.context(List.of("b", "a"), "fixture", 21);
        var expr = ConditionExpression.atom(SEMANTICS, new ConditionExpression.Build(ConditionExpression.BuildPredicate.CLASS_PRESENT,
                "fixture.Type", wrong.identity(), LogicalValue.UNKNOWN, EVIDENCE));
        var result = ConditionModel.create(space(List.of()), List.of(occurrence(expr, 0)));
        assertEquals(Set.of(ConditionModel.Reason.BUILD_CONTEXT_MISMATCH, ConditionModel.Reason.BUILD_EVIDENCE_UNKNOWN), reasons(result));
        assertEquals(1, result.coverage().qualifiedRows());
    }
    @Test void noBooleanSimplificationMayEraseOpaqueObligationsOrTheirOrder() {
        var falseNode = ConditionExpression.atom(SEMANTICS, new ConditionExpression.Constant(LogicalValue.FALSE));
        var expression = ConditionExpression.all(SEMANTICS, List.of(falseNode, opaque(ConditionExpression.OpaqueReason.CUSTOM_CODE)));
        var result = ConditionModel.create(space(List.of()), List.of(occurrence(expression, 0)));
        assertEquals(3, result.inspectedExpressions().size());
        assertEquals(Set.of(ConditionModel.Reason.OPAQUE_CONDITION), reasons(result));
        var nested = ConditionExpression.not(bean());
        assertNotEquals(ConditionExpression.any(SEMANTICS, List.of(nested, profile("a"))).identity(),
                ConditionExpression.any(SEMANTICS, List.of(profile("a"), nested)).identity());
    }
    @Test void veryDeepIrIsHashedAndWithheldWithoutRecursiveTraversal() {
        var expression = profile("a");
        for (int i = 0; i < 10000; i++) expression = ConditionExpression.not(expression);
        var result = ConditionModel.create(space(List.of(profileDomain("a"))), List.of(occurrence(expression, 0)));
        assertEquals(1, result.coverage().unexpandedRows());
        assertEquals(Set.of(ConditionModel.Reason.EXPRESSION_DEPTH_LIMIT), reasons(result));
        assertEquals(0, result.inspectedExpressions().size());
    }
    @Test void allPropertyNamesRequireDomainsAndPrefixSemanticsDoNotNormalizeValues() {
        var property = new ConditionExpression.Property("feature", List.of("b", "a"), " false ", true);
        assertEquals(List.of("feature.a", "feature.b"), property.keys());
        assertEquals(" false ", property.havingValue());
        var expression = ConditionExpression.atom(SEMANTICS, property);
        var first = new FiniteDomain(new FiniteDomain.Variable(FiniteDomain.Kind.PROPERTY, "feature.a"), List.of(FiniteDomain.Value.exact("")), EVIDENCE);
        assertEquals(Set.of(ConditionModel.Reason.DOMAIN_NOT_DECLARED),
                reasons(ConditionModel.create(space(List.of(first)), List.of(occurrence(expression, 0)))));
    }
    @Test void sourceEvidenceMustMatchBothSnapshotMembershipAndExactBytes() {
        var document = ConditionTestInputs.DOCUMENT;
        var wrongBytes = new ConditionEvidence.Source(document.identity(), ContentDigest.sha256Utf8("different-revision"),
                Optional.of(EVIDENCE.spans().getFirst()), 0);
        var foreignDocument = com.evolution.analysis.contract.identity.SourceDocumentIdentity.from(ConditionTestInputs.REPOSITORY, "Missing.java");
        var foreign = new ConditionEvidence.Source(foreignDocument, document.contentDigest(), Optional.of(
                new com.evolution.analysis.contract.source.SourceSpan(foreignDocument, 1,1,2,1)), 0);
        for (var evidence : List.of(wrongBytes, foreign)) {
            var row = new ConditionOccurrence(profile("a"), evidence, "/annotation", "condition", 0);
            var result = ConditionModel.create(space(List.of(profileDomain("a"))), List.of(row));
            assertEquals(1, result.coverage().qualifiedRows(), "foreign or stale source bytes cannot become evidenced IR");
            assertTrue(result.capabilityGaps().stream().anyMatch(gap -> gap.reasonCode().equals("SOURCE_EVIDENCE_MISMATCH")));
        }
    }
}
