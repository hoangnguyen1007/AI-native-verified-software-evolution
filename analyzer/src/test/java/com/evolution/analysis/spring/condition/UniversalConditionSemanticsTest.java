package com.evolution.analysis.spring.condition;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.input.ConditionTestInputs;
import com.evolution.analysis.spring.truth.*;
import com.evolution.analysis.spring.universal.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UniversalConditionSemanticsTest {
    static final ConditionEvidence E=ConditionTestInputs.EVIDENCE;
    static final ConditionExpression.Semantics S=new ConditionExpression.Semantics(ExogenousConditionEvaluator.SEMANTICS,ContentDigest.sha256Utf8("authored-framework"));
    static ConditionModel model(int size, boolean impossible, boolean opaque) {
        var domains=new ArrayList<FiniteDomain>();
        for(int i=0;i<size;i++)domains.add(new FiniteDomain(new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE,"p"+i),List.of(FiniteDomain.Value.bool(false),FiniteDomain.Value.bool(true)),E));
        var profile=ConditionExpression.atom(S,new ConditionExpression.Profile("p0"));
        var expression=opaque?ConditionExpression.atom(S,new ConditionExpression.Opaque(ContentDigest.sha256Utf8("opaque"),ConditionExpression.OpaqueReason.CUSTOM_CODE)):profile;
        var rows=List.of(new ConditionOccurrence(expression,E,"fixture","guard",0),
                new ConditionOccurrence(ConditionExpression.any(S,List.of(profile,ConditionExpression.not(profile))),E,"fixture","tautology",1));
        var constraints=impossible?List.of(new ConditionOccurrence(ConditionExpression.all(S,List.of(profile,ConditionExpression.not(profile))),E,"fixture","constraint",2)):List.<ConditionOccurrence>of();
        var space=new ConfigurationSpace(ConditionTestInputs.context(),new ConfigurationEnvelope(ConfigurationEnvelope.Layer.REPOSITORY,List.of(),List.of(),ContentDigest.sha256Utf8("envelope")),Optional.empty(),domains,constraints,
                new ConfigurationSpace.PrecedencePolicy(ExogenousConditionEvaluator.PRECEDENCE,List.of(),E),new ConfigurationSpace.ProfilePolicy(ExogenousConditionEvaluator.PROFILES,List.of(),Map.of(),List.of(),E),
                new VersionedIdentifier("fixture.abstraction","1"),new ConfigurationSpace.FeasibilityPolicy(new VersionedIdentifier("fixture.feasibility","1"),new ConfigurationSpace.Limits(128,100,Long.MAX_VALUE,1000,10000,128),E));
        return ConditionModel.create(space,rows);
    }
    @Test void symbolicTruthRegionsHandleFiftySixProfilesAndReplayBothTruthValues() {
        var m=model(56,false,false);var result=TruthRegionEvaluation.evaluateSymbolic(m,S,SatConfigurationReasoner.INSTANCE,SymbolicConfiguration.Limits.defaults());
        assertEquals(ConfigurationReasoner.Satisfiability.SATISFIABLE,result.feasibility());
        assertEquals(Set.of(TruthRegionEvaluation.Classification.MUST,TruthRegionEvaluation.Classification.MAY),result.rows().stream().map(SymbolicTruthRegions.Row::classification).collect(java.util.stream.Collectors.toSet()));
        assertEquals(3,result.rows().stream().mapToInt(r->r.witnesses().size()).sum());
        assertTrue(result.issues().isEmpty(),result.issues().toString());
        assertTrue(result.gaps().stream().noneMatch(g->g.reasonCode().equals("ENUMERATION_INCOMPLETE")));
        assertEquals(result.identity(),TruthRegionEvaluation.evaluateSymbolic(m,S,SatConfigurationReasoner.INSTANCE,SymbolicConfiguration.Limits.defaults()).identity());
    }
    @Test void emptyFeasibleSpaceAndOpaqueFactsNeverBecomeVacuousUniversalTruth() {
        var result=TruthRegionEvaluation.evaluateSymbolic(model(2,true,false),S,SatConfigurationReasoner.INSTANCE,SymbolicConfiguration.Limits.defaults());
        assertEquals(ConfigurationReasoner.Satisfiability.UNSATISFIABLE,result.feasibility());
        assertTrue(result.rows().stream().allMatch(r->r.classification()==TruthRegionEvaluation.Classification.UNKNOWN));
        var opaque=TruthRegionEvaluation.evaluateSymbolic(model(2,false,true),S,SatConfigurationReasoner.INSTANCE,SymbolicConfiguration.Limits.defaults());
        assertTrue(opaque.rows().stream().anyMatch(r->r.classification()==TruthRegionEvaluation.Classification.UNKNOWN));
        assertFalse(opaque.gaps().isEmpty());
    }
    @Test void spelLiteralBooleanNumericStringAndPlaceholderCases() {
        for(String expression:List.of("true", "#{${enabled:false} and (${limit:2} >= 2)}", "'dev' == '${profile:dev}'", "not false && (1 + 2 * 3 == 7)", "null != 'x'")) {
            var result=BasicSpelEvaluator.evaluate(expression,Map.of("enabled","true"),E,ConditionTestInputs.context().snapshotIdentity(),BasicSpelEvaluator.Limits.defaults());
            assertEquals(LogicalValue.TRUE,result.value(),expression);assertTrue(result.gaps().isEmpty());
        }
        assertEquals(LogicalValue.FALSE,BasicSpelEvaluator.evaluate("${missing:false}",Map.of(),E,ConditionTestInputs.context().snapshotIdentity(),BasicSpelEvaluator.Limits.defaults()).value());
    }
    @Test void spelNeverExecutesBeanTypeOrMethodAccessAndClosesMalformedAndCyclicInputs() {
        for(String expression:List.of("T(java.lang.Runtime).getRuntime().exec('no')","@bean.enabled", "true || @bean", "${x}", "true &&", "(true", "'open", "1 / 2 == 0", "0.1 + 0.2 == 0.3", "2147483647 + 1 < 0", "-(-2147483647 - 1) > 0")) {
            var result=BasicSpelEvaluator.evaluate(expression,Map.of("x","${x}"),E,ConditionTestInputs.context().snapshotIdentity(),BasicSpelEvaluator.Limits.defaults());
            assertEquals(LogicalValue.UNKNOWN,result.value(),expression);assertEquals(1,result.gaps().size());
        }
    }
    @Test void propertySourcesUseLastActivePrecedenceAndMissingFallsThroughWithReplayedChoices() {
        var base=model(3,false,false).space();
        var variable=new FiniteDomain.Variable(FiniteDomain.Kind.PROPERTY,"feature.enabled");
        var values=List.of(FiniteDomain.Value.missing(),FiniteDomain.Value.exact("true"),FiniteDomain.Value.exact("false"));
        var domain=new FiniteDomain(variable,values,E);
        var source=new ConfigurationEnvelope.DeclaredSource("override",ConfigurationEnvelope.SourceKind.TEST_DESCRIPTOR,E,Optional.empty(),List.of(),ConfigurationEnvelope.Availability.AVAILABLE,
                Map.of(variable,List.of(FiniteDomain.Value.missing(),FiniteDomain.Value.exact("true"))),ExogenousConditionEvaluator.CONVERSION);
        var envelope=new ConfigurationEnvelope(ConfigurationEnvelope.Layer.REPOSITORY,List.of(),List.of(source),ContentDigest.sha256Utf8("source-envelope"));
        var space=new ConfigurationSpace(base.buildContext(),envelope,Optional.empty(),List.of(domain),List.of(),
                new ConfigurationSpace.PrecedencePolicy(ExogenousConditionEvaluator.PRECEDENCE,List.of(new ConfigurationSpace.SourceReference(ConfigurationEnvelope.Layer.REPOSITORY,source.identity())),E),
                base.profilePolicy(),base.abstractionVersion(),base.feasibilityPolicy());
        var row=new ConditionOccurrence(ConditionExpression.atom(S,new ConditionExpression.Property("feature",List.of("enabled"),"true",false)),E,"fixture","property",0);
        var m=ConditionModel.create(space,List.of(row));
        var result=TruthRegionEvaluation.evaluateSymbolic(m,S,SatConfigurationReasoner.INSTANCE,SymbolicConfiguration.Limits.defaults());
        assertEquals(TruthRegionEvaluation.Classification.MAY,result.rows().getFirst().classification());
        assertEquals(2,result.rows().getFirst().witnesses().size());assertTrue(result.issues().isEmpty(),result.issues().toString());
        for(var witness:result.rows().getFirst().witnesses())assertEquals(1,witness.assignment().sourceChoices().size());
    }
    @Test void symbolicDefaultProfilesAndCyclicGroupsAgreeWithSmallSpaceOracle() {
        var original=model(3,false,false);var base=original.space();
        var space=new ConfigurationSpace(base.buildContext(),base.repositoryEnvelope(),Optional.empty(),base.domains(),List.of(),base.precedencePolicy(),
                new ConfigurationSpace.ProfilePolicy(ExogenousConditionEvaluator.PROFILES,List.of("p0"),Map.of("p1",List.of("p2"),"p2",List.of("p1")),List.of(),E),base.abstractionVersion(),base.feasibilityPolicy());
        var row=new ConditionOccurrence(ConditionExpression.atom(S,new ConditionExpression.Profile("p0")),E,"fixture","default-profile",0);
        var m=ConditionModel.create(space,List.of(row));var symbolic=TruthRegionEvaluation.evaluateSymbolic(m,S,SatConfigurationReasoner.INSTANCE,SymbolicConfiguration.Limits.defaults());
        var exhaustive=FiniteConfigurationEvaluation.evaluate(m,S,FiniteConfigurationEvaluation.Limits.conservative());
        assertEquals(Set.of(LogicalValue.TRUE,LogicalValue.FALSE),exhaustive.evaluations().stream().flatMap(e->e.rows().stream()).map(ExogenousConditionEvaluator.Row::truth).collect(java.util.stream.Collectors.toSet()));
        assertEquals(TruthRegionEvaluation.Classification.MAY,symbolic.rows().getFirst().classification());assertEquals(2,symbolic.rows().getFirst().witnesses().size());
        assertTrue(symbolic.issues().isEmpty(),symbolic.issues().toString());
    }
    @Test void symbolicExhaustionIsUnknownWithARealCapabilityGap() {
        var result=TruthRegionEvaluation.evaluateSymbolic(model(56,false,false),S,SatConfigurationReasoner.INSTANCE,new SymbolicConfiguration.Limits(1,1,1,1));
        assertTrue(result.rows().stream().allMatch(r->r.classification()==TruthRegionEvaluation.Classification.UNKNOWN));
        assertTrue(result.gaps().stream().anyMatch(g->g.reasonCode().equals("SOLVER_LIMIT")));
    }
    @Test void unprovenConstraintEvidenceCannotProveAnEmptyFeasibleSpace() {
        var base=model(1,false,false).space();
        var missingSpan=new ConditionEvidence.Source(ConditionTestInputs.DOCUMENT.identity(),ConditionTestInputs.DOCUMENT.contentDigest(),Optional.empty(),0);
        var constraint=new ConditionOccurrence(ConditionExpression.atom(S,new ConditionExpression.Constant(LogicalValue.FALSE)),missingSpan,"fixture","unproven",0);
        var space=new ConfigurationSpace(base.buildContext(),base.repositoryEnvelope(),Optional.empty(),base.domains(),List.of(constraint),base.precedencePolicy(),base.profilePolicy(),base.abstractionVersion(),base.feasibilityPolicy());
        var result=TruthRegionEvaluation.evaluateSymbolic(ConditionModel.create(space,List.of(constraint)),S,SatConfigurationReasoner.INSTANCE,SymbolicConfiguration.Limits.defaults());
        assertEquals(ConfigurationReasoner.Satisfiability.UNKNOWN,result.feasibility());assertFalse(result.gaps().isEmpty());
        assertTrue(result.rows().stream().allMatch(r->r.classification()==TruthRegionEvaluation.Classification.UNKNOWN));
    }
    @Test void conditionalOnExpressionLowersIntoTheRealM4bInterpreterAndSymbolicRegions() throws Exception {
        var fixture=ConditionLoweringTest.fixture(List.of(new ConditionLoweringTest.Spec("org.springframework.boot.autoconfigure.condition.ConditionalOnExpression","@ConditionalOnExpression(\"${enabled:false} && 2 > 1\")")));
        var lowered=ConditionLoweringTest.lower(fixture);assertEquals(1,lowered.coverage().loweredRows());
        assertTrue(lowered.occurrences().getFirst().expression().operand() instanceof ConditionExpression.BasicSpel);
        var original=model(1,false,false).space();var variable=new FiniteDomain.Variable(FiniteDomain.Kind.PROPERTY,"enabled");
        var domain=new FiniteDomain(variable,List.of(FiniteDomain.Value.exact("true"),FiniteDomain.Value.exact("false"),FiniteDomain.Value.missing()),fixture.evidence());
        var space=new ConfigurationSpace(fixture.build(),original.repositoryEnvelope(),Optional.empty(),List.of(domain),List.of(),
                new ConfigurationSpace.PrecedencePolicy(ExogenousConditionEvaluator.PRECEDENCE,List.of(),fixture.evidence()),
                new ConfigurationSpace.ProfilePolicy(ExogenousConditionEvaluator.PROFILES,List.of(),Map.of(),List.of(),fixture.evidence()),original.abstractionVersion(),
                new ConfigurationSpace.FeasibilityPolicy(original.feasibilityPolicy().version(),original.feasibilityPolicy().limits(),fixture.evidence()));
        var m=ConditionModel.create(space,lowered.occurrences());
        var assignment=new ConfigurationAssignment(Map.of(variable,FiniteDomain.Value.exact("true")),fixture.evidence());
        assertEquals(LogicalValue.TRUE,ExogenousConditionEvaluator.evaluate(m,lowered.semantics(),assignment,ExogenousConditionEvaluator.Limits.conservative()).rows().getFirst().truth());
        var symbolic=TruthRegionEvaluation.evaluateSymbolic(m,lowered.semantics(),SatConfigurationReasoner.INSTANCE,SymbolicConfiguration.Limits.defaults());
        assertEquals(TruthRegionEvaluation.Classification.MAY,symbolic.rows().getFirst().classification());assertEquals(2,symbolic.rows().getFirst().witnesses().size());
        assertTrue(symbolic.issues().isEmpty(),symbolic.issues().toString());
    }
}
