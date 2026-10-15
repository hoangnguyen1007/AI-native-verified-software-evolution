package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.evidence.EvidenceContext;
import com.evolution.analysis.filesystem.FileGraphStore;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.ingestion.UniversalArchitecturePipeline;
import com.evolution.analysis.policy.*;
import com.evolution.analysis.metrics.*;
import com.evolution.analysis.assessment.*;
import com.evolution.analysis.contract.assessment.AssessmentStatus;
import java.math.BigDecimal;
import com.evolution.analysis.query.*;
import com.evolution.analysis.spring.condition.*;
import com.evolution.analysis.spring.truth.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.query.ArchitectureQuery.*;
import static com.evolution.analysis.javaparser.CanonicalGraphPipelineIntegrationTest.*;

/** Passive source -> frontend -> graph -> typed policy; no analyzed target execution. */
class M6PolicyIntegrationTest {
    @TempDir Path directory;
    static PolicyCompiler.Result compile(KnowledgeGraph graph,String yaml){return new PolicyCompiler().compile(yaml.getBytes(StandardCharsets.UTF_8),
            EvidenceContext.forSnapshot(graph.snapshot()),PolicyCompiler.Limits.defaults());}
    static ArchitectureScoreModel scoreModel(){return new ArchitectureScoreModel(new VersionedIdentifier("test.source-policy-score","1"),
            List.of(new ArchitectureScoreModel.Dimension("health.policy",BigDecimal.ONE,new BigDecimal("100"),Set.of(ArchitecturePolicy.Kind.values()),Optional.empty())),
            Map.of("INFO",new BigDecimal("2"),"WARNING",new BigDecimal("10"),"ERROR",new BigDecimal("25")));}
    @Test void sourceMutationChangesCandidatesAndRetainsFullSourceSpansAfterFileReload(){
        String yaml="""
                schema: architecture-policy:v1
                id: application.architecture
                version: "1"
                rules:
                  - id: controller.no-repository
                    version: "1"
                    kind: FORBIDDEN_DEPENDENCY
                    severity: ERROR
                    scope: TYPE
                    relationships: [graph.depends-on]
                    from: {packages: [api]}
                    to: {packages: [data]}
                """;
        var identities=new HashSet<ContentDigest>();
        for(boolean dependency:List.of(false,true)){
            var detailed=new UniversalArchitecturePipeline().runWithEvidence(inputs(Map.of(
                    "src/main/java/api/Controller.java","package api; public class Controller { public void run(){ "+(dependency?"new data.Repository().load();":"")+" } }",
                    "src/main/java/data/Repository.java","package data; public class Repository { public void load(){} }")),policy());
            var graph=new CanonicalGraphBuilder().build(detailed.graphInput().orElseThrow());
            var context=graph.nodes().stream().filter(n->n.kind().equals("TYPE")).findFirst().orElseThrow().context();
            var service=new ArchitectureQueryService(graph);var compiled=compile(graph,yaml);var mapper=new PolicyGraphMapper();
            var plan=mapper.bind(compiled,service,context,PolicyGraphMapper.Limits.defaults());identities.add(plan.identity());
            assertEquals(dependency?1:0,plan.rules().getFirst().candidates().size());
            var evaluation=new PolicyEvaluator().evaluate(compiled,plan,service,PolicyEvaluation.Limits.defaults());
            assertEquals(dependency?Classification.MUST:Classification.UNKNOWN,evaluation.rules().getFirst().classification(),
                    evaluation.gaps().stream().map(g->g.reasonCode()).toList().toString());
            assertTrue(evaluation.gaps().stream().anyMatch(g->g.reasonCode().equals("UPSTREAM_CAPABILITY_GAP")));
            assertFalse(evaluation.upstreamEvidence().isEmpty());
            assertTrue(evaluation.upstreamEvidence().stream().anyMatch(e->e.family().equals("evidence.gap")));
            assertEquals(dependency?1:0,evaluation.findings().size());
            var metrics=new PolicyMetricsService().compute(compiled,plan,evaluation,service,PolicyMetrics.Limits.defaults());
            assertEquals(1,metrics.counts().declaredRules());assertEquals(dependency?1:0,metrics.counts().findings().size());
            assertEquals(AssessmentStatus.WITHHELD,metrics.require("metric.policy-density").status());
            var assessment=new ArchitectureAssessmentService().evaluate(metrics,evaluation,service,Optional.of(scoreModel()),List.of(),ArchitectureAssessment.Limits.defaults());
            assertEquals(AssessmentStatus.WITHHELD,assessment.status());assertTrue(assessment.score().isEmpty());assertFalse(assessment.confidence().sufficient());
            assertFalse(metrics.upstreamEvidence().isEmpty());
            if(dependency)assertEquals(new BigDecimal("25"),assessment.dimensions().getFirst().uncappedPenalty());
            if(dependency){
                var candidate=plan.rules().getFirst().candidates().getFirst();assertEquals(PolicySelector.Match.YES,candidate.eligibility());
                assertEquals(Classification.MUST,service.condition(plan.rules().getFirst().request(),candidate.predicate()).items().getFirst().classification());
                assertTrue(candidate.edge().evidence().stream().map(ref->service.evidence(plan.rules().getFirst().request(),ref))
                        .flatMap(r->r.items().stream()).anyMatch(e->e.metadata().containsKey("span")));
                var bundle=evaluation.findings().getFirst().bundle();
                assertFalse(bundle.sources().isEmpty());assertFalse(bundle.supports().isEmpty());
                assertTrue(bundle.sources().stream().allMatch(s->s.span().startLine()>=1&&s.span().endColumn()>0));
                assertEquals(bundle.region(),service.ruleCondition(bundle.request(),bundle.predicate()).items().getFirst());
                assertEquals(graph.identity(),bundle.graph());assertEquals(graph.snapshot(),bundle.snapshot());
            }
            new FileGraphStore(directory).save(graph);var restored=new FileGraphStore(directory).load(graph.identity()).orElseThrow();
            assertEquals(plan,mapper.bind(compiled,new ArchitectureQueryService(restored),context,PolicyGraphMapper.Limits.defaults()));
            assertEquals(plan.identity(),mapper.bind(compiled,new ArchitectureQueryService(restored),context,PolicyGraphMapper.Limits.defaults()).identity());
            assertEquals(evaluation,new PolicyEvaluator().evaluate(compiled,plan,new ArchitectureQueryService(restored),PolicyEvaluation.Limits.defaults()));
            var restoredMetrics=new PolicyMetricsService().compute(compiled,plan,evaluation,new ArchitectureQueryService(restored),PolicyMetrics.Limits.defaults());
            assertEquals(metrics.identity(),restoredMetrics.identity());
            assertEquals(assessment.identity(),new ArchitectureAssessmentService().evaluate(restoredMetrics,evaluation,new ArchitectureQueryService(restored),Optional.of(scoreModel()),List.of(),ArchitectureAssessment.Limits.defaults()).identity());
        }
        assertEquals(2,identities.size());
    }
    @Test void actualProfileBindingStaysMayUnderExplicitAndSymbolicPolicyPredicates() throws Exception {
        var helper=new SourceToSpringPlanTest();var fixture=helper.sourceFixture(Map.of(
                "app/App.java","package app; @org.springframework.context.annotation.ComponentScan(\"app\") class App {}",
                "app/Client.java","package app; @org.springframework.stereotype.Component @org.springframework.context.annotation.Profile(\"dev\") class Client { public Client(Store store){} }",
                "app/Store.java","package app; @org.springframework.stereotype.Component public class Store { public Store(){} }"));
        var spring=helper.beanPlan(fixture,true,"Store","Client");
        assertFalse(spring.binding().dependencies().isEmpty(),()->spring.gaps().stream().map(g->g.reasonCode()).distinct().toList().toString());
        var version=new VersionedIdentifier("policy.authored-config","1");
        var proof=new ConditionEvidence.Derived(List.of(fixture.source().identity()),version,"finite-space");
        var variable=new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE,"dev");
        var space=new ConfigurationSpace(fixture.build(),new ConfigurationEnvelope(ConfigurationEnvelope.Layer.REPOSITORY,List.of(),List.of(),fixture.source().identity()),Optional.empty(),
                List.of(new FiniteDomain(variable,List.of(FiniteDomain.Value.bool(false),FiniteDomain.Value.bool(true)),proof)),List.of(),
                new ConfigurationSpace.PrecedencePolicy(ExogenousConditionEvaluator.PRECEDENCE,List.of(),proof),
                new ConfigurationSpace.ProfilePolicy(ExogenousConditionEvaluator.PROFILES,List.of(),Map.of(),List.of(),proof),version,
                new ConfigurationSpace.FeasibilityPolicy(version,ConfigurationSpace.Limits.conservative(),proof));
        var baseline=new ConfigurationAssignment(Map.of(variable,FiniteDomain.Value.bool(false)),proof);var limits=TruthRegionEvaluation.Limits.conservative();
        var artifact=ContentDigest.sha256Utf8("m6-authored-evaluation");var explicit=spring.evaluate(space,baseline,limits,artifact).truth();
        var symbolic=spring.evaluateSymbolic(space,baseline,limits,artifact,4,SymbolicConfiguration.Limits.defaults()).truth();
        String client=helper.candidate(spring,"client").identity().value(),store=helper.candidate(spring,"store").identity().value();
        String yaml="""
                schema: architecture-policy:v1
                id: application.spring
                version: "1"
                rules:
                  - id: client.no-store
                    version: "1"
                    kind: FORBIDDEN_DEPENDENCY
                    severity: ERROR
                    scope: SPRING_COMPONENT
                    relationships: [spring.selected-binding]
                    from: {identities: ['%s']}
                    to: {identities: ['%s']}
                """.formatted(client,store);
        var slices=List.of(
                new CanonicalGraphInput.SpringSlice(fixture.key(),spring,Optional.of(space),Optional.of(baseline),Optional.of(explicit),Optional.empty(),
                        Optional.of(new CanonicalGraphInput.EvaluationParameters(limits,ExhaustiveConfigurationReasoner.INSTANCE.policy(),artifact,0,Optional.empty()))),
                new CanonicalGraphInput.SpringSlice(fixture.key(),spring,Optional.of(space),Optional.of(baseline),Optional.empty(),Optional.of(symbolic),
                        Optional.of(new CanonicalGraphInput.EvaluationParameters(limits,SatConfigurationReasoner.INSTANCE.policy(),artifact,4,Optional.of(SymbolicConfiguration.Limits.defaults())))));
        for(var slice:slices){
            var graph=new CanonicalGraphBuilder().build(fixture.source().manifest(),fixture.source().frontend(),List.of(slice));
            String context=graph.nodes().stream().filter(n->n.kind().equals("INJECTION_USE")).findFirst().orElseThrow().context();
            var service=new ArchitectureQueryService(graph);var plan=new PolicyGraphMapper().bind(compile(graph,yaml),service,context,PolicyGraphMapper.Limits.defaults());
            var rule=plan.rules().getFirst();assertEquals(1,rule.candidates().size());var candidate=rule.candidates().getFirst();
            assertEquals(PolicySelector.Match.YES,candidate.eligibility());assertEquals(ArchitecturePolicy.Severity.ERROR,rule.rule().header().severity());
            var result=service.condition(rule.request(),candidate.predicate()).items().getFirst();assertEquals(Classification.MAY,result.classification());
            assertTrue(result.witness().isPresent());assertTrue(result.counterWitness().isPresent());assertFalse(result.witness().orElseThrow().semanticReplayed());
            var restored=new GraphSnapshotCodec().decode(new GraphSnapshotCodec().encode(graph),GraphJson.Limits.defaults());
            assertEquals(result,new ArchitectureQueryService(restored).condition(rule.request(),candidate.predicate()).items().getFirst());
            var evaluated=new PolicyEvaluator().evaluate(compile(graph,yaml),plan,service,PolicyEvaluation.Limits.defaults());
            assertEquals(1,evaluated.findings().size());var bundle=evaluated.findings().getFirst().bundle();
            assertEquals(Classification.MAY,bundle.region().classification(),evaluated.gaps().stream().map(g->g.reasonCode()).toList().toString());
            assertFalse(bundle.sources().isEmpty());assertEquals(List.of(space.identity().value()),bundle.configurationSpaces());
            assertEquals(Classification.UNKNOWN,evaluated.rules().getFirst().classification());
            assertTrue(evaluated.gaps().stream().anyMatch(g->g.reasonCode().equals("UPSTREAM_CAPABILITY_GAP")));
            assertFalse(bundle.upstreamEvidence().isEmpty());
            assertEquals(bundle.region(),service.ruleCondition(bundle.request(),bundle.predicate()).items().getFirst());
            assertEquals(evaluated,new PolicyEvaluator().evaluate(compile(graph,yaml),plan,new ArchitectureQueryService(restored),PolicyEvaluation.Limits.defaults()));
            var metrics=new PolicyMetricsService().compute(compile(graph,yaml),plan,evaluated,service,PolicyMetrics.Limits.defaults());
            assertEquals(1L,metrics.counts().findingQuantifiers().get("MAY"));assertEquals(1L,metrics.counts().ruleQuantifiers().get("UNKNOWN"));
            assertEquals(List.of(space.identity().value()),metrics.context().configurationSpaces());
            var assessment=new ArchitectureAssessmentService().evaluate(metrics,evaluated,service,Optional.of(scoreModel()),List.of(),ArchitectureAssessment.Limits.defaults());
            assertEquals(AssessmentStatus.WITHHELD,assessment.status());assertTrue(assessment.score().isEmpty());
            assertEquals(new BigDecimal("25"),assessment.dimensions().getFirst().uncappedPenalty());
        }
    }
}
