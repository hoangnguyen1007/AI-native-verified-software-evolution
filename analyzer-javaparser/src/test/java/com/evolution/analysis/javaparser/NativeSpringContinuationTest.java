package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.ingestion.*;
import com.evolution.analysis.spring.*;
import com.evolution.analysis.spring.condition.*;
import com.evolution.analysis.spring.registration.*;
import com.evolution.analysis.spring.truth.*;
import com.evolution.analysis.spring.universal.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.spring.registration.RegistrationEvent.Completeness.*;

/** Real generated Spring bytes; the finite test container is declared separately from native execution. */
@Tag("native-campaign")
class NativeSpringContinuationTest {
    @Test void nativeGeneratorArrayLengthGapsStayActiveWhileIndependentGeneratedBeansRemainAvailable()throws Exception{
        var fixture=fixture("native.spring.root");var generator=fixture.execute("custom-generator");
        assertEquals(2,generator.frontend().observations().stream().filter(o->o.category().value().equals("java.reads-field")&&o.attribution()==com.evolution.analysis.contract.semantic.SemanticStatus.UNSUPPORTED).count());
        assertEquals(2,((Number)generator.mappings().get("errorOrNonresolved")).longValue());
        assertTrue(generator.graph().evidence().stream().anyMatch(e->e.family().equals("java.native.closure-projection")
                &&((Number)GraphJson.object(GraphJson.object(GraphJson.read(e.canonicalJson(),GraphJson.Limits.defaults())).get("counts")).get("active")).longValue()>0));
        var producer=fixture.execute("custom-producer");var framework=SpringFrameworkEvidence.fromRequest(producer.request());
        assertEquals(2,new ComponentScanIngestion().scan(producer.request(),producer.frontend(),framework,1000).rows().stream().filter(r->r.status()==ComponentScanIngestion.Status.INCLUDED).count());
    }
    static NativePipelineWiringFixture fixture(String property)throws Exception{return new NativePipelineWiringFixture(Path.of(System.getProperty(property)));}
    @Test void realMapStructStereotypeDoesNotInventAScanRootOrUniversalActivation()throws Exception{
        var result=fixture("native.spring.root").execute("mapstruct-mapper");
        var framework=SpringFrameworkEvidence.fromRequest(result.request());
        assertTrue(framework.artifacts().stream().anyMatch(a->a.coordinate().equals("org.springframework:spring-context:6.2.0")));
        var source=new SpringSourceEvidence(result.request(),result.frontend(),framework);
        var generated=result.frontend().declarations().stream().map(d->d.entity()).filter(e->"fixture.PersonMapperImpl".equals(source.typeName(e.identity()))).findFirst().orElseThrow();
        assertTrue(source.annotations(generated.identity()).stream().anyMatch(a->a.name().filter("org.springframework.stereotype.Component"::equals).isPresent()));
        assertTrue(generated.declaration().isPresent());
        var rows=new ComponentScanIngestion().scan(result.request(),result.frontend(),framework,1000);
        assertNotEquals(ComponentScanIngestion.Status.INCLUDED,rows.rows().stream().filter(r->r.type().equals(generated.identity())).findFirst().orElseThrow().status(),"A real generated stereotype cannot supply the absent scan/container proof");
        assertTrue(result.pipeline().report().truthRegions().isEmpty());
        var declaration=result.frontend().declarations().stream().filter(d->d.entity().identity().equals(generated.identity())).findFirst().orElseThrow();
        assertEquals(com.evolution.analysis.contract.semantic.DerivationKind.DERIVED,declaration.derivation().kind());
        assertEquals(generated.declaration(),declaration.entity().declaration());assertTrue(result.pipeline().report().truthRegions().isEmpty(),"The positive finite plan does not rewrite the unqualified original pipeline");
    }
    @Test void generatedProfileBeansHaveComplementaryRegionsAndReplayableWitnessesInTheDeclaredContainer()throws Exception{
        var result=fixture("native.spring.root").execute("custom-producer");var input=result.pipeline().graphInput().orElseThrow();var ingestion=input.ingestion().orElseThrow();
        var unit=ingestion.units().stream().filter(u->u.frontend().isPresent()).findFirst().orElseThrow();
        var framework=SpringFrameworkEvidence.fromRequest(result.request());var source=new SpringSourceEvidence(result.request(),result.frontend(),framework);
        assertTrue(framework.acceptedConditionFragment(false),"Positive profile qualification requires the entire exact framework artifact tuple");
        var components=unit.components().orElseThrow();
        var included=components.rows().stream().filter(r->r.status()==ComponentScanIngestion.Status.INCLUDED).map(ComponentScanIngestion.Row::type).toList();
        assertEquals(Set.of("fixture.BlueChoice","fixture.OtherChoice"),included.stream().map(source::typeName).collect(java.util.stream.Collectors.toSet()));
        var version=new VersionedIdentifier("test.native-spring-container","1");
        var proof=new ConditionEvidence.Derived(List.of(source.identity()),version,"explicit-finite-test-container-not-native-runtime-closure");
        var scope=new SourceToSpringPlan.Scope("declared-native-source-container",source.identity(),included,COMPLETE,COMPLETE,COMPLETE,COMPLETE,COMPLETE,RegistrationPlan.OverridePolicy.FORBID,proof);
        var plan=new UniversalIngestionPipeline().prepareSpring(ingestion,unit.sourceSet(),scope,1000);
        assertEquals(RegistrationPlan.OrderStatus.COMPLETE,plan.binding().registrationPlan().discoveryPlan().orderStatus());
        assertEquals("m8.4-framework-registration-v1",BeanRegistrationProcessing.PROVIDER.version());
        assertEquals("m8.4-framework-registration-v1",BeanRegistrationProcessing.SEMANTICS.version());
        assertEquals(2,plan.binding().registrationPlan().discoveryPlan().events().stream().filter(e->e.kind()==RegistrationEvent.Kind.COMPONENT&&e.candidate().isPresent()).count());
        assertTrue(plan.inventory().problems().stream().noneMatch(p->Set.of(SpringMechanismInventory.ProblemReason.CONSTRUCTOR_SET_INCOMPLETE,SpringMechanismInventory.ProblemReason.GENERATED_MEMBER_NOT_ACQUIRED).contains(p.reason())),"Real generated component bytes supply constructor/bean metadata inside their evidenced scan container");
        var build=plan.binding().registrationPlan().discoveryPlan().buildContext();var variable=new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE,"blue");
        var space=new ConfigurationSpace(build,new ConfigurationEnvelope(ConfigurationEnvelope.Layer.REPOSITORY,List.of(),List.of(),source.identity()),Optional.empty(),
                List.of(new FiniteDomain(variable,List.of(FiniteDomain.Value.bool(false),FiniteDomain.Value.bool(true)),proof)),List.of(),
                new ConfigurationSpace.PrecedencePolicy(ExogenousConditionEvaluator.PRECEDENCE,List.of(),proof),new ConfigurationSpace.ProfilePolicy(ExogenousConditionEvaluator.PROFILES,List.of(),Map.of(),List.of(),proof),version,
                new ConfigurationSpace.FeasibilityPolicy(version,ConfigurationSpace.Limits.conservative(),proof));
        var baseline=new ConfigurationAssignment(Map.of(variable,FiniteDomain.Value.bool(false)),proof);var limits=TruthRegionEvaluation.Limits.conservative();ContentDigest artifact;
        try(var bytes=TruthRegionEvaluation.class.getResourceAsStream("TruthRegionEvaluation.class")){artifact=ContentDigest.sha256(bytes.readAllBytes());}
        var truth=plan.evaluate(space,baseline,limits,artifact).truth();
        var regions=truth.regions().stream().filter(r->r.fact().kind()==ConditionalFactKey.Kind.DEFINITION_PRESENT).toList();
        assertEquals(2,regions.size());assertTrue(regions.stream().allMatch(r->r.classification()==TruthRegionEvaluation.Classification.MAY),()->
                "regions="+regions.stream().map(r->List.of(r.classification(),r.trueWorlds().size(),r.falseWorlds().size(),r.unknownWorlds().size())).toList()
                +"; truth="+truth.issues().stream().map(i->i.reason()+":"+i.subject()).distinct().limit(12).toList()
                +"; plan="+plan.gaps().stream().map(g->g.reasonCode()+":"+g.subject().identity()).distinct().limit(12).toList()
                +"; allReasons="+plan.gaps().stream().map(g->g.reasonCode()).distinct().toList()
                +"; events="+plan.binding().registrationPlan().discoveryPlan().events().stream().map(e->List.of(e.kind(),e.membership(),e.conditionMetadata(),e.conditions())).toList()
                +"; operations="+truth.operationalOutcomes().stream().map(o->List.of(o.status(),o.reasons())).toList()
                +"; lowering="+plan.conditions().issues().stream().map(i->i.reason().name()).distinct().toList());
        assertEquals(1,regions.getFirst().trueWorlds().size());assertEquals(regions.getFirst().trueWorlds(),regions.getLast().falseWorlds());
        assertEquals(regions.getFirst().falseWorlds(),regions.getLast().trueWorlds());assertTrue(regions.stream().allMatch(r->r.unknownWorlds().isEmpty()));
        assertEquals(2,truth.worlds().size());assertFalse(truth.replays().isEmpty());assertTrue(truth.replays().stream().allMatch(TruthRegionEvaluation.WitnessReplay::valid));
        var slice=new CanonicalGraphInput.SpringSlice(unit.sourceSet(),plan,Optional.of(space),Optional.of(baseline),Optional.of(truth),Optional.empty(),
                Optional.of(new CanonicalGraphInput.EvaluationParameters(limits,ExhaustiveConfigurationReasoner.INSTANCE.policy(),artifact,0,Optional.empty())));
        var graph=new CanonicalGraphBuilder().build(new CanonicalGraphInput(input.repository(),input.build(),input.ingestion(),List.of(slice),input.issues(),input.gaps(),input.acquisition(),input.javaEvidence()));
        assertTrue(graph.evidence().stream().anyMatch(e->e.family().equals("spring.witness-replay")));
        assertEquals(graph.identity(),new GraphSnapshotCodec().decode(new GraphSnapshotCodec().encode(graph),GraphJson.Limits.defaults()).identity());
        // Withhold order/container closure independently of successful native compilation.
        var open=new SourceToSpringPlan.Scope("declared-native-source-container",source.identity(),List.of(),UNKNOWN,UNKNOWN,UNKNOWN,UNKNOWN,UNKNOWN,RegistrationPlan.OverridePolicy.FORBID,proof);
        var uncertain=new UniversalIngestionPipeline().prepareSpring(ingestion,unit.sourceSet(),open,1000);
        assertFalse(uncertain.gaps().isEmpty());
        assertEquals(RegistrationPlan.OrderStatus.MISSING_EVIDENCE,uncertain.binding().registrationPlan().discoveryPlan().orderStatus());
        var unknownTruth=uncertain.evaluate(space,baseline,limits,artifact).truth();
        var unknownDefinitions=unknownTruth.regions().stream().filter(r->r.fact().kind()==ConditionalFactKey.Kind.DEFINITION_PRESENT).toList();
        assertEquals(2,unknownDefinitions.size(),"Missing order cannot erase the native generated-bean denominator");
        assertTrue(unknownDefinitions.stream().allMatch(r->r.classification()==TruthRegionEvaluation.Classification.UNKNOWN));
        var missing=new ComponentScanIngestion().scan(result.request(),result.frontend(),new SpringFrameworkEvidence(false,Optional.empty(),List.of()),1000);
        assertFalse(missing.gaps().isEmpty());assertTrue(missing.rows().stream().noneMatch(r->r.status()==ComponentScanIngestion.Status.INCLUDED));
    }
}
