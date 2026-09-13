package com.evolution.analysis.spring.condition;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.ConfigurationIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.input.ConditionTestInputs;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.input.ConditionTestInputs.EVIDENCE;

class ConfigurationSpaceTest {
    static final VersionedIdentifier VERSION = new VersionedIdentifier("fixture.policy", "1");
    static final SpringBuildContext BUILD = ConditionTestInputs.context();
    static ConfigurationSpace space(List<FiniteDomain> domains) {
        return space(domains, List.of(), ConfigurationSpace.Limits.conservative());
    }
    static ConfigurationSpace space(List<FiniteDomain> domains, List<ConditionOccurrence> constraints, ConfigurationSpace.Limits limits) {
        return new ConfigurationSpace(BUILD, new ConfigurationEnvelope(ConfigurationEnvelope.Layer.REPOSITORY,
                List.of(), List.of(), ContentDigest.sha256Utf8("no-ambient-inputs")), Optional.empty(), domains, constraints,
                new ConfigurationSpace.PrecedencePolicy(VERSION, List.of(), EVIDENCE),
                new ConfigurationSpace.ProfilePolicy(VERSION, List.of(), Map.of(), List.of(), EVIDENCE),
                VERSION, new ConfigurationSpace.FeasibilityPolicy(VERSION, limits, EVIDENCE));
    }
    static FiniteDomain profileDomain(String name) {
        return new FiniteDomain(new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE, name),
                List.of(FiniteDomain.Value.bool(false), FiniteDomain.Value.bool(true)), EVIDENCE);
    }
    static ConditionOccurrence occurrence(ConditionExpression expression, int ordinal) {
        return new ConditionOccurrence(expression, EVIDENCE, "/annotations/" + ordinal, "condition", ordinal);
    }

    @Test void finiteCartesianProductIncludesIndependentProfilesAndEmptyProductIsOne() {
        assertEquals(new ConfigurationSpace.CartesianSize(BigInteger.ONE, true), space(List.of()).cartesianSize());
        assertEquals(new ConfigurationSpace.CartesianSize(BigInteger.valueOf(4), true),
                space(List.of(profileDomain("dev"), profileDomain("prod"))).cartesianSize());
        assertEquals(ConditionModel.Feasibility.NOT_EVALUATED, ConditionModel.create(space(List.of()), List.of()).feasibility());
    }
    @Test void setsAreCanonicalButExactDomainValuesAndAllLimitsAffectIdentity() {
        var a = profileDomain("a"); var b = profileDomain("b");
        assertEquals(space(List.of(a, b)).identity(), space(List.of(b, a)).identity());
        var limits = ConfigurationSpace.Limits.conservative();
        var variants = List.of(new ConfigurationSpace.Limits(11,4096,4096,10000,100000,256),
                new ConfigurationSpace.Limits(12,4095,4096,10000,100000,256),
                new ConfigurationSpace.Limits(12,4096,4095,10000,100000,256),
                new ConfigurationSpace.Limits(12,4096,4096,9999,100000,256),
                new ConfigurationSpace.Limits(12,4096,4096,10000,99999,256),
                new ConfigurationSpace.Limits(12,4096,4096,10000,100000,255));
        for (var variant : variants) assertNotEquals(space(List.of(a), List.of(), limits).identity(), space(List.of(a), List.of(), variant).identity());
        assertThrows(IllegalArgumentException.class, () -> space(List.of(a, a)));
        assertThrows(IllegalArgumentException.class, () -> new ConfigurationSpaceIdentity(ConfigurationIdentity.from(VERSION, Map.of()).value()));
    }
    @Test void valuesAreStrictlyTypedAndImmutableWithoutTrimming() {
        var values = new ArrayList<>(List.of(FiniteDomain.Value.exact(""), FiniteDomain.Value.missing(), FiniteDomain.Value.exact(" ")));
        var domain = new FiniteDomain(new FiniteDomain.Variable(FiniteDomain.Kind.PROPERTY, "x"), values, EVIDENCE);
        values.clear(); assertEquals(3, domain.values().size());
        assertThrows(UnsupportedOperationException.class, () -> domain.values().clear());
        assertThrows(IllegalArgumentException.class, () -> new FiniteDomain(profileDomain("x").variable(), List.of(FiniteDomain.Value.missing()), EVIDENCE));
        assertThrows(IllegalArgumentException.class, () -> new FiniteDomain(domain.variable(), List.of(FiniteDomain.Value.exact(""), FiniteDomain.Value.exact("")), EVIDENCE));
    }
    @Test void emptyAndOversizedSpacesProduceTypedGapsWithoutVacuousTruthOrOverflow() {
        var empty = new FiniteDomain(profileDomain("x").variable(), List.of(), EVIDENCE);
        assertEquals(ConditionModel.Status.INVALID_MODEL, ConditionModel.create(space(List.of(empty)), List.of()).status());
        List<FiniteDomain> many = new ArrayList<>();
        for (int i = 0; i < 70; i++) many.add(profileDomain("p" + i));
        var large = space(many, List.of(), new ConfigurationSpace.Limits(100,2,Long.MAX_VALUE,10,100,100));
        assertEquals(new ConfigurationSpace.CartesianSize(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE), false), large.cartesianSize());
        assertTrue(ConditionModel.create(large, List.of()).problems().stream().anyMatch(p -> p.reason() == ConditionModel.Reason.WORLD_LIMIT));
        many.add(empty);
        assertEquals(new ConfigurationSpace.CartesianSize(BigInteger.ZERO, true), space(many).cartesianSize());
    }
    @Test void sourcePrecedenceAndEveryEnvelopeInputRemainInIdentity() {
        var source = source("one", ""); var source2 = source("two", "false");
        var first = envelope(List.of(source, source2)); var reverse = envelope(List.of(source2, source));
        assertNotEquals(first.identity(), reverse.identity());
        var refs = List.of(new ConfigurationSpace.SourceReference(first.layer(), source.identity()),
                new ConfigurationSpace.SourceReference(first.layer(), source2.identity()));
        var domain = new FiniteDomain(new FiniteDomain.Variable(FiniteDomain.Kind.PROPERTY, "enabled"),
                List.of(FiniteDomain.Value.exact(""), FiniteDomain.Value.exact("false")), EVIDENCE);
        var base = space(List.of(domain));
        var one = withEnvelope(base, first, refs);
        var two = withEnvelope(base, first, refs.reversed());
        assertNotEquals(one.identity(), two.identity());
        assertEquals(ConditionModel.Status.REPRESENTED, ConditionModel.create(one, List.of()).status());
        assertTrue(ConditionModel.create(withEnvelope(base, first, List.of()), List.of()).problems().stream()
                .anyMatch(problem -> problem.reason() == ConditionModel.Reason.PRECEDENCE_INCOMPLETE));
        assertDoesNotThrow(() -> CanonicalJson.write(first.canonicalForm()));
    }
    static ConfigurationEnvelope.DeclaredSource source(String name, String value) {
        return new ConfigurationEnvelope.DeclaredSource(name, ConfigurationEnvelope.SourceKind.DOCUMENT, EVIDENCE,
                Optional.empty(), List.of(), ConfigurationEnvelope.Availability.AVAILABLE,
                Map.of(new FiniteDomain.Variable(FiniteDomain.Kind.PROPERTY, "enabled"), List.of(FiniteDomain.Value.exact(value))), VERSION);
    }
    static ConfigurationEnvelope envelope(List<ConfigurationEnvelope.DeclaredSource> sources) {
        return new ConfigurationEnvelope(ConfigurationEnvelope.Layer.REPOSITORY, List.of(), sources, ContentDigest.sha256Utf8("policy"));
    }
    static ConfigurationSpace withEnvelope(ConfigurationSpace base, ConfigurationEnvelope envelope, List<ConfigurationSpace.SourceReference> precedence) {
        return new ConfigurationSpace(base.buildContext(), envelope, base.deploymentEnvelope(), base.domains(), base.constraints(),
                new ConfigurationSpace.PrecedencePolicy(VERSION, precedence, EVIDENCE), base.profilePolicy(), base.abstractionVersion(), base.feasibilityPolicy());
    }
    @Test void buildContextBindsOrderedClasspathAndPlatformButExcludesHostLocators() {
        assertEquals(BUILD.identity(), ConditionTestInputs.context(List.of("a", "b"), "other-host-locator", 21).identity());
        assertNotEquals(BUILD.identity(), ConditionTestInputs.context(List.of("b", "a"), "fixture-locator", 21).identity());
        assertNotEquals(BUILD.identity(), ConditionTestInputs.context(List.of("a", "b"), "fixture-locator", 17).identity());
    }
    @Test void declaredPoliciesConstraintsAndDeploymentPresenceChangeTheSpacePreimage() {
        var base = space(List.of(profileDomain("a")));
        var deployment = new ConfigurationEnvelope(ConfigurationEnvelope.Layer.DEPLOYMENT, List.of(), List.of(), ContentDigest.sha256Utf8("policy"));
        var explicitDeployment = new ConfigurationSpace(BUILD, base.repositoryEnvelope(), Optional.of(deployment), base.domains(), base.constraints(),
                base.precedencePolicy(), base.profilePolicy(), VERSION, base.feasibilityPolicy());
        assertNotEquals(base.identity(), explicitDeployment.identity(), "absent deployment is distinct from an explicit empty envelope");
        var profilePolicy = new ConfigurationSpace.ProfilePolicy(VERSION, List.of("a"), Map.of("group", List.of("a")), List.of("a"), EVIDENCE);
        var profileChanged = new ConfigurationSpace(BUILD, base.repositoryEnvelope(), Optional.empty(), base.domains(), base.constraints(),
                base.precedencePolicy(), profilePolicy, VERSION, base.feasibilityPolicy());
        assertNotEquals(base.identity(), profileChanged.identity());
        var constrained = space(base.domains(), List.of(occurrence(ConditionExpressionTest.profile("a"), 1)), ConfigurationSpace.Limits.conservative());
        assertNotEquals(base.identity(), constrained.identity());
        assertEquals(ConditionModel.Feasibility.NOT_EVALUATED, ConditionModel.create(constrained, List.of()).feasibility());
        assertThrows(IllegalArgumentException.class, () -> new ConfigurationSpace(BUILD, deployment, Optional.empty(), base.domains(), List.of(),
                base.precedencePolicy(), base.profilePolicy(), VERSION, base.feasibilityPolicy()));
    }
    @Test void finiteBudgetsAcceptTheirExactBoundaryAndAccountForEveryExceededAxis() {
        List<FiniteDomain> domains = new ArrayList<>();
        for (int i = 0; i < 12; i++) domains.add(profileDomain("p" + i));
        var boundary = space(domains);
        assertEquals(new ConfigurationSpace.CartesianSize(BigInteger.valueOf(4096), true), boundary.cartesianSize());
        assertEquals(ConditionModel.Status.REPRESENTED, ConditionModel.create(boundary, List.of()).status());
        var small = space(List.of(profileDomain("a")), List.of(), new ConfigurationSpace.Limits(0,1,1,1,1,1));
        assertEquals(Set.of(ConditionModel.Reason.VARIABLE_LIMIT, ConditionModel.Reason.DOMAIN_VALUE_LIMIT, ConditionModel.Reason.WORLD_LIMIT),
                ConditionModelTest.reasons(ConditionModel.create(small, List.of())));
    }
}
