package com.evolution.analysis.javaparser;

import com.evolution.analysis.evolution.*;
import com.evolution.analysis.filesystem.FileSnapshotComparison;
import com.evolution.analysis.filesystem.PartitionedGraphStore;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.ingestion.*;
import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.spring.condition.*;
import com.evolution.analysis.spring.registration.*;
import com.evolution.analysis.spring.truth.*;
import com.evolution.analysis.spring.universal.*;
import com.evolution.analysis.policy.*;
import com.evolution.analysis.evidence.EvidenceContext;
import com.evolution.analysis.query.ArchitectureQueryService;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CanonicalGraphPipelineIntegrationTest {
    static final RepositoryIdentity REPO=RepositoryIdentity.fromCanonicalCoordinate("https://example.test/graph-pipeline.git");
    static RepositoryInputs inputs(Map<String,String> texts) {
        var module=ModuleDescriptor.create(REPO,"fixture","fixture");var files=new TreeMap<String,SourceInput>();
        texts.forEach((path,text)->{var bytes=text.getBytes(StandardCharsets.UTF_8);var doc=SourceDocument.create(REPO,module,path,ContentDigest.sha256(bytes),SourceClassification.MAIN);files.put(path,new SourceInput(doc,bytes));});
        var docs=files.values().stream().map(SourceInput::document).toList();
        return new RepositoryInputs(RepositorySnapshot.create(REPO,Optional.empty(),false,docs.stream().map(SnapshotFile::from).toList(),docs),files);
    }
    static UniversalArchitecturePipeline.Policy policy() {
        return UniversalArchitecturePipeline.Policy.builder(new JavaParserFrontend(),JavaParserFrontend.PROVIDER)
                .defaultPlatform(TestInputs.request("class PlatformStub {}").platform()).build();
    }
    @Test void actualSourcePipelinePreservesOccurrencesEvidenceAndRoundTrip() {
        var input=inputs(Map.of("src/main/java/p/A.java","package p; class A { void f(){ new B().g(); new B().g(); } }",
                "src/main/java/p/B.java","package p; class B { void g(){} }"));
        var pipeline=new UniversalArchitecturePipeline();var detailed=pipeline.runWithEvidence(input,policy());
        assertTrue(detailed.graphInput().isPresent());
        assertTrue(detailed.graphInput().orElseThrow().ingestion().orElseThrow().sourceIngestion().outcomes().stream()
                .flatMap(o->o.request().stream()).allMatch(r->r.manifest().graphSchema().equals(KnowledgeGraph.schemaComponent())));
        var graph=new CanonicalGraphBuilder().build(detailed.graphInput().orElseThrow());
        var calls=graph.edges().stream().filter(e->e.kind().equals("java.calls")).toList();
        assertEquals(1,calls.size());assertEquals(2,calls.getFirst().evidence().size());
        assertTrue(graph.edges().stream().anyMatch(e->e.kind().equals("graph.depends-on")));
        assertTrue(graph.evidence().stream().anyMatch(e->e.family().equals("source.bytes")));
        var frontend=detailed.graphInput().orElseThrow().ingestion().orElseThrow().units().stream().flatMap(u->u.frontend().stream()).findFirst().orElseThrow();
        var indexed=graph.evidence().stream().filter(e->e.family().equals("frontend.result-index")).findFirst().orElseThrow();
        var restoredFrontend=new TreeMap<>(GraphJson.object(GraphJson.read(indexed.canonicalJson(),GraphJson.Limits.defaults())));
        restoredFrontend.remove("indexSchema");restoredFrontend.remove("providerResultIdentity");
        for(String field:List.of("diagnostics","declarations","occurrences","observations","types","annotations","derivedRelationships","typeDeclarations","memberDeclarations","constructorCandidates")){
            var records=GraphJson.array(restoredFrontend.get(field)).stream().map(id->GraphJson.read(graph.evidence(new ContentDigest(GraphJson.string(id))).orElseThrow().canonicalJson(),GraphJson.Limits.defaults())).toList();restoredFrontend.put(field,records);
        }
        restoredFrontend.put("sources",GraphJson.array(restoredFrontend.get("sources")).stream().map(entry->{var ref=GraphJson.string(GraphJson.object(entry).get("evidence"));return GraphJson.read(graph.evidence(new ContentDigest(ref)).orElseThrow().canonicalJson(),GraphJson.Limits.defaults());}).toList());
        restoredFrontend.put("httpClientCalls",GraphJson.array(restoredFrontend.get("httpClientCalls")).stream().map(id->{var wrapper=GraphJson.object(GraphJson.read(graph.evidence(new ContentDigest(GraphJson.string(id))).orElseThrow().canonicalJson(),GraphJson.Limits.defaults()));return wrapper.get("call");}).toList());
        assertEquals(com.evolution.analysis.contract.serialization.CanonicalJson.write(frontend),com.evolution.analysis.contract.serialization.CanonicalJson.write(restoredFrontend),"Index must reconstruct every canonical frontend field without loss");
        var codec=new GraphSnapshotCodec();var restored=codec.decode(codec.encode(graph),GraphJson.Limits.defaults());
        assertEquals(graph.identity(),restored.identity());assertEquals(graph.evidence(),restored.evidence());
        assertEquals(detailed.report().identity(),pipeline.run(input,policy()).identity());
    }
    @Test void parseFailureRetainsValidSiblingAndGapLedger() {
        var detailed=new UniversalArchitecturePipeline().runWithEvidence(inputs(Map.of(
                "src/main/java/p/Good.java","package p; class Good { int n(){ return 1; } }",
                "src/main/java/p/Bad.java","package p; class Bad { broken(")),policy());
        var graph=new CanonicalGraphBuilder().build(detailed.graphInput().orElseThrow());
        assertTrue(graph.nodes().stream().anyMatch(n->n.kind().equals("TYPE")));
        var failed=detailed.graphInput().orElseThrow().ingestion().orElseThrow().units().stream().flatMap(u->u.frontend().stream())
                .flatMap(f->f.sources().stream()).filter(s->s.state()!=SourceOutcome.State.PROCESSED).findFirst().orElseThrow();
        assertFalse(failed.diagnostics().isEmpty());
        assertTrue(graph.evidence().stream().filter(e->e.family().equals("java.source-outcome"))
                .map(e->GraphJson.object(GraphJson.read(e.canonicalJson(),GraphJson.Limits.defaults())))
                .anyMatch(e->e.get("document").equals(failed.document().value())&&e.get("state").equals(failed.state().name())));
        assertFalse(detailed.graphInput().orElseThrow().gaps().isEmpty());
        assertTrue(graph.nodes().stream().anyMatch(n->n.kind().equals("CAPABILITY_GAP")));
        var index=graph.evidence().stream().filter(e->e.family().equals("ingestion.outcome-index")).findFirst().orElseThrow();
        var payload=GraphJson.object(GraphJson.read(index.canonicalJson(),GraphJson.Limits.defaults()));
        var ingestion=detailed.graphInput().orElseThrow().ingestion().orElseThrow();
        var indexedGaps=GraphJson.array(payload.get("gaps")).stream().flatMap(pageId->GraphJson.array(GraphJson.object(GraphJson.read(graph.evidence(new ContentDigest(GraphJson.string(pageId))).orElseThrow().canonicalJson(),GraphJson.Limits.defaults())).get("records")).stream().map(GraphJson::string)).toList();
        assertEquals(ingestion.gaps().stream().map(g->g.gapIdentity().value()).sorted().distinct().toList(),indexedGaps);
        assertTrue(graph.evidence().stream().noneMatch(e->e.family().equals("ingestion.outcomes")),"Do not embed the entire repeated gap ledger inside one bounded evidence payload");
        for(var gap:ingestion.gaps())assertTrue(graph.evidence().stream().anyMatch(e->e.family().equals("evidence.gap")&&e.canonicalJson().contains(gap.gapIdentity().value())),"Every indexed gap must remain lossless");
    }
    @Test void emptyAndNonJavaInputsHaveHonestGraphStructure() {
        var detailed=new UniversalArchitecturePipeline().runWithEvidence(inputs(Map.of("README.md","Only documentation")),policy());
        var graph=new CanonicalGraphBuilder().build(detailed.graphInput().orElseThrow());
        assertEquals(UniversalArchitectureReport.Status.EMPTY_OR_NON_JAVA,detailed.report().status());
        assertTrue(graph.entityIdentities().isEmpty());assertEquals(1,graph.contexts().size());
    }
    @Test void revisionLabelsAndDirtyFlagsRemainProvenanceRatherThanSemanticIdentity() {
        var original=inputs(Map.of("src/main/java/p/A.java","package p; class A {}"));
        var revised=new RepositoryInputs(RepositorySnapshot.create(REPO,Optional.of("authored-revision"),true,original.snapshot().files(),original.snapshot().documents()),original.files());
        var pipeline=new UniversalArchitecturePipeline();var first=pipeline.runWithEvidence(original,policy());var second=pipeline.runWithEvidence(revised,policy());
        var builder=new CanonicalGraphBuilder();var a=builder.build(first.graphInput().orElseThrow());var b=builder.build(second.graphInput().orElseThrow());
        assertEquals(a.snapshot(),b.snapshot());assertNotEquals(a.identity(),b.identity());assertEquals(a.semanticIdentity(),b.semanticIdentity());
    }
    @Test void acquisitionApiRetainsOriginalBytesAndAttemptsWithoutRerunningProviders() {
        var original=inputs(Map.of("src/main/java/p/A.java","package p; class A {}"));
        var file=new com.evolution.analysis.acquisition.AcquiredFile("src/main/java/p/A.java",original.files().get("src/main/java/p/A.java").bytes());
        var request=new com.evolution.analysis.acquisition.RepositoryAcquisitionRequest(REPO,Optional.empty(),false,"pom.xml",
                new com.evolution.analysis.acquisition.RepositoryAcquisitionPolicy(100,100,10000,10000,8,List.of()));
        var raw=RepositorySnapshot.create(REPO,Optional.empty(),false,original.snapshot().files(),List.of());
        var attempt=new com.evolution.analysis.acquisition.RepositoryAcquisitionResult.Attempt(com.evolution.analysis.acquisition.RepositoryAcquisitionResult.AttemptKind.FILE,
                file.path(),com.evolution.analysis.acquisition.RepositoryAcquisitionResult.Outcome.SUCCEEDED,Optional.of(file.contentDigest()));
        var acquisition=new com.evolution.analysis.acquisition.RepositoryAcquisitionResult(com.evolution.analysis.acquisition.RepositoryAcquisitionResult.SCHEMA,request,
                new VersionedIdentifier("fixture.acquisition","1"),com.evolution.analysis.acquisition.RepositoryAcquisitionResult.Completion.COMPLETE,
                Optional.of(raw),List.of(file),List.of("."),List.of(),List.of(attempt),List.of());
        var graphInput=new UniversalArchitecturePipeline().runWithEvidence(acquisition,policy()).graphInput().orElseThrow();
        assertTrue(graphInput.acquisition().isPresent());
        var graph=new CanonicalGraphBuilder().build(graphInput);
        var retained=graph.evidence().stream().filter(e->e.family().equals("repository.acquisition")).findFirst().orElseThrow();
        assertTrue(retained.canonicalJson().contains("SUCCEEDED"));assertTrue(retained.canonicalJson().contains(Base64.getEncoder().encodeToString(file.bytes())));
    }

    @Test void explicitAndSymbolicSpringRegionsPreserveOwnerUsesAndReplayedWitnesses(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var helper=new SourceToSpringPlanTest();
        var fixture=helper.sourceFixture(Map.of("base/Base.java","""
                package base;
                public abstract class Base {
                  @org.springframework.beans.factory.annotation.Autowired private app.Store store;
                }
                ""","app/App.java","""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                @org.springframework.stereotype.Component @org.springframework.context.annotation.Profile("dev") class Client extends base.Base {}
                @org.springframework.stereotype.Component class Other extends base.Base {}
                ""","app/Store.java","package app; @org.springframework.stereotype.Component public class Store {}"));
        var plan=helper.beanPlan(fixture,true,"Store","Client","Other");
        var version=new VersionedIdentifier("graph.authored-config","1");
        var proof=new ConditionEvidence.Derived(List.of(fixture.source().identity()),version,"finite-space");
        var variable=new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE,"dev");
        var space=new ConfigurationSpace(fixture.build(),new ConfigurationEnvelope(ConfigurationEnvelope.Layer.REPOSITORY,List.of(),List.of(),fixture.source().identity()),
                Optional.empty(),List.of(new FiniteDomain(variable,List.of(FiniteDomain.Value.bool(false),FiniteDomain.Value.bool(true)),proof)),List.of(),
                new ConfigurationSpace.PrecedencePolicy(ExogenousConditionEvaluator.PRECEDENCE,List.of(),proof),
                new ConfigurationSpace.ProfilePolicy(ExogenousConditionEvaluator.PROFILES,List.of(),Map.of(),List.of(),proof),version,
                new ConfigurationSpace.FeasibilityPolicy(version,ConfigurationSpace.Limits.conservative(),proof));
        var baseline=new ConfigurationAssignment(Map.of(variable,FiniteDomain.Value.bool(false)),proof);
        var limits=TruthRegionEvaluation.Limits.conservative();var artifact=ContentDigest.sha256Utf8("graph-authored-evaluator");
        var explicit=plan.evaluate(space,baseline,limits,artifact).truth();
        assertFalse(explicit.witnesses().isEmpty());assertTrue(explicit.replays().stream().allMatch(TruthRegionEvaluation.WitnessReplay::valid));
        var slice=new CanonicalGraphInput.SpringSlice(fixture.key(),plan,Optional.of(space),Optional.of(baseline),Optional.of(explicit),Optional.empty(),
                Optional.of(new CanonicalGraphInput.EvaluationParameters(limits,ExhaustiveConfigurationReasoner.INSTANCE.policy(),artifact,0,Optional.empty())));
        var graph=new CanonicalGraphBuilder().build(fixture.source().manifest(),fixture.source().frontend(),List.of(slice));
        assertEquals(1,plan.binding().dependencies().stream().map(d->d.point().identity()).distinct().count());
        assertEquals(2,graph.nodes().stream().filter(n->n.kind().equals("INJECTION_USE")).count());
        assertEquals(2,graph.edges().stream().filter(e->e.kind().equals("spring.selected-binding")).map(KnowledgeGraph.Edge::source).distinct().count());
        assertTrue(graph.edges().stream().filter(e->e.kind().equals("spring.selected-binding")).allMatch(e->e.region().isPresent()));
        assertEquals(explicit.witnesses().size(),graph.nodes().stream().filter(n->n.kind().equals("CONFIGURATION_WITNESS")).count());
        assertTrue(graph.evidence().stream().anyMatch(e->e.family().equals("spring.witness-replay")));
        assertEquals(explicit.regions().size(),graph.nodes().stream().filter(n->n.kind().equals("TRUTH_REGION")).count());
        var codec=new GraphSnapshotCodec();assertEquals(graph.evidence(),codec.decode(codec.encode(graph),GraphJson.Limits.defaults()).evidence());
        var symbolic=plan.evaluateSymbolic(space,baseline,limits,artifact,4,SymbolicConfiguration.Limits.defaults()).truth();
        var symbolicSlice=new CanonicalGraphInput.SpringSlice(fixture.key(),plan,Optional.of(space),Optional.of(baseline),Optional.empty(),Optional.of(symbolic),
                Optional.of(new CanonicalGraphInput.EvaluationParameters(limits,SatConfigurationReasoner.INSTANCE.policy(),artifact,4,Optional.of(SymbolicConfiguration.Limits.defaults()))));
        var symbolicGraph=new CanonicalGraphBuilder().build(fixture.source().manifest(),fixture.source().frontend(),List.of(symbolicSlice));
        assertEquals(symbolic.regions().size(),symbolicGraph.nodes().stream().filter(n->n.kind().equals("TRUTH_REGION")).count());
        assertTrue(symbolicGraph.nodes().stream().anyMatch(n->n.kind().equals("REGION_FORMULA")));
        assertEquals(symbolicGraph.identity(),codec.decode(codec.encode(symbolicGraph),GraphJson.Limits.defaults()).identity());
        var compared=conditional(graph,symbolicGraph,2);
        var summaries=rows(compared,SnapshotComparison.Section.REGIONS).stream().filter(r->"FACT_SUMMARY".equals(r.get("kind"))).toList();
        assertEquals(explicit.regions().size(),summaries.size(),()->compared.summary().status().name());
        assertTrue(summaries.stream().allMatch(r->"UNCHANGED".equals(r.get("shape"))));
        assertEquals(0,compared.summary().census(SnapshotComparison.Section.EVENTS).rows());
        assertEquals(compared.summary(),conditional(graph,symbolicGraph,17).summary());
        var store=new PartitionedGraphStore(dir.resolve("graphs"));
        store.save(graph,()->false);store.save(symbolicGraph,()->false);
        try(var session=FileSnapshotComparison.compare(store,dir.resolve("scratch"),conditionalRequest(graph,symbolicGraph),7,()->false)) {
            assertEquals(compared.summary(),session.result().summary());
            assertEquals(rows(compared,SnapshotComparison.Section.PROOFS),rows(session.result(),SnapshotComparison.Section.PROOFS));
        }
        var changedBaseline=new ConfigurationAssignment(Map.of(variable,FiniteDomain.Value.bool(true)),proof);
        assertThrows(IllegalArgumentException.class,()->new CanonicalGraphInput.SpringSlice(fixture.key(),plan,Optional.of(space),Optional.of(changedBaseline),Optional.of(explicit),Optional.empty(),slice.parameters()));
        assertThrows(IllegalArgumentException.class,()->new CanonicalGraphInput.SpringSlice(fixture.key(),plan,Optional.of(space),Optional.of(changedBaseline),Optional.empty(),Optional.of(symbolic),symbolicSlice.parameters()));
        var alteredLimits=new TruthRegionEvaluation.Limits(limits.enumeration(),1,limits.maxRegionCells(),limits.maxWitnessReplays());
        assertThrows(IllegalArgumentException.class,()->new CanonicalGraphInput.SpringSlice(fixture.key(),plan,Optional.of(space),Optional.of(baseline),Optional.of(explicit),Optional.empty(),
                Optional.of(new CanonicalGraphInput.EvaluationParameters(alteredLimits,ExhaustiveConfigurationReasoner.INSTANCE.policy(),artifact,0,Optional.empty()))));
    }

    @Test void conditionalEvolutionSeparatesRegistrationOrderFromSelectedBindings() throws Exception {
        var helper=new SourceToSpringPlanTest();
        var f=helper.sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                class Store {}
                @org.springframework.context.annotation.Configuration(proxyBeanMethods=false) class ConfigFirst {
                  @org.springframework.context.annotation.Bean Store first() { return new Store(); }
                }
                @org.springframework.context.annotation.Configuration(proxyBeanMethods=false) class ConfigSecond {
                  @org.springframework.context.annotation.Bean
                  @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(name="first")
                  Store second() { return new Store(); }
                }
                """);
        var l=helper.beanPlan(f,true,"ConfigFirst","first","ConfigSecond","second");
        var r=helper.beanPlan(f,true,"ConfigSecond","second","ConfigFirst","first");
        var a=evolutionGraph(f,l,List.of(),Map.of());var b=evolutionGraph(f,r,List.of(),Map.of());
        var order=conditional(a,b,2);
        var events=rows(order,SnapshotComparison.Section.EVENTS);
        assertEquals(List.of("REGION_INTRODUCED","REGION_INTRODUCED"),events.stream().map(e->e.get("kind")).toList(),
                rows(order,SnapshotComparison.Section.COMPATIBILITY).stream().map(e->e.get("reasons")).toList()+" "+
                        rows(order,SnapshotComparison.Section.REGIONS).stream().map(e->List.of(e.get("kind"),
                                e.getOrDefault("shape","?"),Objects.toString(e.get("reason"),"-"))).toList());
        assertEquals(List.of("REGION_RESOLVED","REGION_RESOLVED"),rows(conditional(b,a,4),SnapshotComparison.Section.EVENTS).stream().map(e->e.get("kind")).toList());
        var unproved=evolutionGraph(f,helper.beanPlan(f,false),List.of(),Map.of());
        assertTrue(rows(conditional(a,unproved,3),SnapshotComparison.Section.EVENTS).isEmpty());

        var first=selectionPriority(helper,true);var second=selectionPriority(helper,false);
        var g1=evolutionGraph(first,helper.beanPlan(first,true,"Config","first","second","Client"),List.of(),Map.of());
        var g2=evolutionGraph(second,helper.beanPlan(second,true,"Config","first","second","Client"),List.of(),Map.of());
        var selection=conditional(g1,g2,3);
        var binding=rows(selection,SnapshotComparison.Section.EVENTS).stream().filter(e->"SELECTED_BINDING_CHANGED".equals(e.get("kind"))).toList();
        assertEquals(2,binding.size(),()->selection.summary().toString());
        assertNotEquals(binding.getFirst().get("before"),binding.getFirst().get("after"));
        var candidate=rows(selection,SnapshotComparison.Section.REGIONS).stream().filter(e->"INJECTION_CANDIDATE".equals(e.get("factKind"))).toList();
        assertEquals(4,candidate.size());
        assertTrue(candidate.stream().allMatch(e->"UNCHANGED".equals(e.get("shape"))));
    }

    @Test void conditionalEvolutionDistinguishesDomainChangesAndWitnessObservations() throws Exception {
        var helper=new SourceToSpringPlanTest();
        var f=helper.sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                @org.springframework.stereotype.Component class Store {}
                """);
        var plan=helper.beanPlan(f,true,"Store");
        var variable=new FiniteDomain.Variable(FiniteDomain.Kind.PROPERTY,"flavor");
        var zero=FiniteDomain.Value.exact("vanilla");
        var one=FiniteDomain.Value.exact("chocolate");
        var two=FiniteDomain.Value.exact("strawberry");
        var g1=evolutionGraph(f,plan,List.of(zero,one),Map.of(variable,zero));
        var g2=evolutionGraph(f,plan,List.of(zero,two),Map.of(variable,zero));
        var delta=conditional(g1,g2,2);
        assertEquals(Set.of("DOMAIN_ADDED","DOMAIN_REMOVED"),new HashSet<>(rows(delta,SnapshotComparison.Section.EVENTS).stream().map(e->e.get("kind")).toList()));
        var reps=evolutionGraph(f,plan,List.of(zero,one),Map.of(variable,one));
        var changed=conditional(g1,reps,3);
        assertTrue(rows(changed,SnapshotComparison.Section.EVENTS).isEmpty());
        var witnesses=rows(changed,SnapshotComparison.Section.EVIDENCE).stream().filter(e->"WITNESS_OBSERVATION_CHANGED".equals(e.get("kind"))).toList();
        assertEquals(1,witnesses.size());
        var left=GraphJson.object(witnesses.getFirst().get("left"));
        var right=GraphJson.object(witnesses.getFirst().get("right"));
        assertEquals(g1.identity().value(),left.get("graph"));
        assertEquals(reps.identity().value(),right.get("graph"));
        assertEquals("CAPTURED_EVALUATOR_REPLAY",left.get("tier"));
        assertNotEquals(left.get("assignment"),right.get("assignment"));
        var other=FiniteDomain.Value.other(Optional.empty());
        var unknown=evolutionGraph(f,plan,List.of(zero,other),Map.of(variable,zero));
        assertTrue(rows(conditional(g1,unknown,4),SnapshotComparison.Section.EVENTS).isEmpty());
    }

    @Test void findingLifecycleUsesRealSpringRegionsAcrossAuthenticatedReaders(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var helper=new SourceToSpringPlanTest();
        var variable=new FiniteDomain.Variable(FiniteDomain.Kind.PROPERTY,"flavor");
        var vanilla=FiniteDomain.Value.exact("vanilla");
        var chocolate=FiniteDomain.Value.exact("chocolate");
        var strawberry=FiniteDomain.Value.exact("strawberry");
        var values=List.of(vanilla,chocolate,strawberry);
        var left=regionalGraph(helper,"chocolate",values,Map.of(variable,vanilla),false);
        var right=regionalGraph(helper,"strawberry",values,Map.of(variable,vanilla),false);
        var a=springPolicy(left);var b=springPolicy(right);
        assertEquals(1,a.evaluation().findings().size(),()->a.evaluation().gaps().stream().map(g->g.reasonCode()).toList().toString());
        assertEquals(1,b.evaluation().findings().size());
        var refs=List.of(new SnapshotComparison.ReportRef(SnapshotComparison.Side.LEFT,a.identity(),a.evaluation().context()),
                new SnapshotComparison.ReportRef(SnapshotComparison.Side.RIGHT,b.identity(),b.evaluation().context()));
        var reports=Map.of(a.identity(),a,b.identity(),b);
        var reader=new SnapshotComparison.ReportReader() {
            public SnapshotComparison.ReportStatus verify(ContentDigest graph,SnapshotComparison.ReportRef report,java.util.function.BooleanSupplier stop) {
                return SnapshotComparison.ReportStatus.AVAILABLE;
            }
            public Optional<EvolutionReport> read(ContentDigest graph,SnapshotComparison.ReportRef report,java.util.function.BooleanSupplier stop) {
                return Optional.ofNullable(reports.get(report.identity()));
            }
        };
        var request=new SnapshotComparison.Request(TestInputs.REPO,left.identity(),right.identity(),SnapshotComparison.Mode.STRICT,
                new SnapshotComparison.Selection(Set.of("MAIN"),Set.of(),Set.of(),false,false,true,false,false));
        var result=SnapshotComparison.compare(request,EvolutionSnapshot.memory(left,right),refs,reader,EvolutionIndex.memory(),3,()->false);
        var outcomes=rows(result,SnapshotComparison.Section.FINDINGS);
        assertEquals(Set.of("INTRODUCED","RESOLVED","PERSISTED"),new HashSet<>(outcomes.stream()
                .filter(r->"FINDING_TRANSITION".equals(r.get("kind"))).map(r->r.get("transition")).toList()),
                ()->outcomes.stream().map(r->Arrays.asList(r.get("kind"),r.get("transition"),r.get("reasons"))).toList()+" "+
                        a.evaluation().rules().getFirst().findings().getFirst().bundle().gaps().stream().map(g->g.reasonCode()).toList()+" "+
                        a.evaluation().gaps().stream().map(g->g.reasonCode()).toList()+" "+
                        left.evidence().stream().filter(e->"evidence.gap".equals(e.family())).map(e->
                                GraphJson.object(GraphJson.read(e.canonicalJson(),GraphJson.Limits.defaults())).get("reasonCode")).distinct().toList());
        var proofs=rows(result,SnapshotComparison.Section.PROOFS);
        assertEquals(3,proofs.stream().filter(r->"FINDING_REGION".equals(r.get("kind"))).count());
        var graphs=new PartitionedGraphStore(dir.resolve("graphs"));
        graphs.save(left,()->false);graphs.save(right,()->false);
        try(var replay=FileSnapshotComparison.compare(graphs,dir.resolve("scratch"),request,refs,reader,1,()->false)) {
            assertEquals(result.summary(),replay.result().summary());
            assertEquals(outcomes,rows(replay.result(),SnapshotComparison.Section.FINDINGS));
        }

        var symbolic=regionalGraph(helper,"strawberry",values,Map.of(variable,vanilla),true);
        var middle=regionalGraph(helper,"vanilla",values,Map.of(variable,vanilla),true);
        var c=springPolicy(symbolic);var m=springPolicy(middle);
        var mixedReader=new SnapshotComparison.ReportReader() {
            public SnapshotComparison.ReportStatus verify(ContentDigest graph,SnapshotComparison.ReportRef ref,java.util.function.BooleanSupplier stop) {
                return SnapshotComparison.ReportStatus.AVAILABLE;
            }
            public Optional<EvolutionReport> read(ContentDigest graph,SnapshotComparison.ReportRef ref,java.util.function.BooleanSupplier stop) {
                return Optional.ofNullable(Map.of(a.identity(),a,c.identity(),c,m.identity(),m).get(ref.identity()));
            }
        };
        var mixed=new SnapshotComparison.Request(TestInputs.REPO,left.identity(),symbolic.identity(),request.mode(),request.selection());
        var mixedResult=SnapshotComparison.compare(mixed,EvolutionSnapshot.memory(left,symbolic),
                List.of(refs.getFirst(),new SnapshotComparison.ReportRef(SnapshotComparison.Side.RIGHT,c.identity(),c.evaluation().context())),
                mixedReader,EvolutionIndex.memory(),2,()->false);
        assertEquals(Set.of("INTRODUCED","RESOLVED","PERSISTED"),new HashSet<>(rows(mixedResult,SnapshotComparison.Section.FINDINGS).stream()
                .filter(r->"FINDING_TRANSITION".equals(r.get("kind"))).map(r->r.get("transition")).toList()));
        var p=historyInterval(left,middle);var q=historyInterval(middle,symbolic);
        var history=new SnapshotComparison.History(List.of(historyStep(left,a,null),historyStep(middle,m,p),historyStep(symbolic,c,q)));
        var historyRequest=new SnapshotComparison.Request(TestInputs.REPO,middle.identity(),symbolic.identity(),request.mode(),request.selection());
        var historyResult=SnapshotComparison.compare(historyRequest,EvolutionSnapshot.memory(left,middle,symbolic),
                List.of(new SnapshotComparison.ReportRef(SnapshotComparison.Side.LEFT,m.identity(),m.evaluation().context()),
                        new SnapshotComparison.ReportRef(SnapshotComparison.Side.RIGHT,c.identity(),c.evaluation().context())),
                mixedReader,List.of(),(ref,s,o,stop)->SnapshotComparison.ReportStatus.UNAVAILABLE,Optional.of(history),
                (id,stop)->Optional.ofNullable(Map.of(p.identity(),p,q.identity(),q).get(id)),EvolutionIndex.memory(),3,()->false);
        assertEquals(1,rows(historyResult,SnapshotComparison.Section.HISTORY).stream().filter(r->"REINTRODUCED".equals(r.get("transition"))).count());
        String export = System.getProperty("evolution.history.fixture");
        if (export != null) exportHistoryFixture(java.nio.file.Path.of(export), List.of(left,middle,symbolic), List.of(a,m,c));
    }

    private static void exportHistoryFixture(java.nio.file.Path output, List<KnowledgeGraph> graphs,
                                             List<EvolutionReport> operands) throws Exception {
        var path = java.nio.file.Files.createDirectory(output.toAbsolutePath().normalize());
        var store = new PartitionedGraphStore(path.resolve("graph-pages"));
        var revisions = new ArrayList<Map<String,Object>>();
        for (int i = 0; i < graphs.size(); i++) {
            var graph = graphs.get(i);
            var operand = operands.get(i);
            var queries = new ArchitectureQueryService(graph);
            var metrics = new com.evolution.analysis.metrics.PolicyMetricsService().compute(
                    operand.compilation().orElseThrow(), operand.mapping().orElseThrow(), operand.evaluation(),
                    queries, com.evolution.analysis.metrics.PolicyMetrics.Limits.defaults());
            store.save(graph, () -> false);
            revisions.add(Map.of("graph", graph.identity(), "snapshot", graph.snapshot(), "context",
                    operand.evaluation().context(), "report",
                    Map.of("evaluation", operand.evaluation(), "metrics", metrics, "assessment", Optional.empty())));
        }
        java.nio.file.Files.writeString(path.resolve("revisions.json"),
                com.evolution.analysis.contract.serialization.CanonicalJson.write(
                        Map.of("repository", TestInputs.REPO, "revisions", revisions)),
                java.nio.file.StandardOpenOption.CREATE_NEW);
    }

    private static SnapshotComparison.HistoryProof historyInterval(KnowledgeGraph from,KnowledgeGraph to) {
        return new SnapshotComparison.HistoryProof(SnapshotComparison.HistoryBasis.DECLARED_SEQUENCE,TestInputs.REPO,
                from.identity(),to.identity(),from.snapshot(),to.snapshot(),List.of(),true,ContentDigest.sha256Utf8("authored sequence"));
    }

    private static SnapshotComparison.HistoryStep historyStep(KnowledgeGraph graph,EvolutionReport report,SnapshotComparison.HistoryProof proof) {
        return new SnapshotComparison.HistoryStep(new SnapshotComparison.HistoryRevision(graph.identity(),
                List.of(new SnapshotComparison.HistoryReport(report.identity(),report.evaluation().context()))),
                Optional.ofNullable(proof).map(SnapshotComparison.HistoryProof::identity));
    }

    private static KnowledgeGraph regionalGraph(SourceToSpringPlanTest helper,String excluded,List<FiniteDomain.Value> values,
                                               Map<FiniteDomain.Variable,FiniteDomain.Value> assignment,boolean symbolic) throws Exception {
        var f=helper.sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                class Store {}
                @org.springframework.context.annotation.Configuration(proxyBeanMethods=false) class Config {
                  @org.springframework.context.annotation.Bean
                  @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="flavor", havingValue="%s")
                  @org.springframework.context.annotation.Primary
                  Store fallback() { return new Store(); }
                  @org.springframework.context.annotation.Bean Store store() { return new Store(); }
                }
                @org.springframework.stereotype.Component class Client {
                  @org.springframework.beans.factory.annotation.Autowired Store store;
                }
                """.formatted(excluded));
        return evolutionGraph(f,helper.beanPlan(f,true,"Config","fallback","store","Client"),values,assignment,symbolic);
    }

    private static EvolutionReport springPolicy(KnowledgeGraph graph) {
        var text="""
                schema: architecture-policy:v1
                id: evolution.regional
                version: "1"
                rules:
                  - {id: dependency.rule, version: "1", kind: FORBIDDEN_DEPENDENCY, severity: ERROR, scope: SPRING_COMPONENT, relationships: [spring.selected-binding], from: {names: ['*']}, to: {names: ['store']}}
                """;
        var queries=new ArchitectureQueryService(graph);
        var context=graph.contexts().stream().filter(c->"spring.context".equals(graph.evidence(c.evidence()).orElseThrow().family()))
                .findFirst().orElseThrow().identity();
        var compiled=new PolicyCompiler().compile(text.getBytes(StandardCharsets.UTF_8),EvidenceContext.forSnapshot(graph.snapshot()),PolicyCompiler.Limits.defaults());
        var plan=new PolicyGraphMapper().bind(compiled,queries,context,PolicyGraphMapper.Limits.defaults());
        var evaluation=new PolicyEvaluator().evaluate(compiled,plan,queries,PolicyEvaluation.Limits.defaults());
        return new EvolutionReport(evaluation,Optional.empty(),Optional.empty(),Optional.of(compiled),Optional.of(plan));
    }

    private static SourceToSpringPlanTest.Fixture selectionPriority(SourceToSpringPlanTest helper,boolean first)throws Exception {
        String primary="@org.springframework.context.annotation.Primary";
        return helper.sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                class Store {}
                @org.springframework.context.annotation.Configuration(proxyBeanMethods=false) class Config {
                  @org.springframework.context.annotation.Bean %s Store first() { return new Store(); }
                  @org.springframework.context.annotation.Bean %s Store second() { return new Store(); }
                }
                @org.springframework.stereotype.Component class Client {
                  @org.springframework.beans.factory.annotation.Autowired Store store;
                }
                """.formatted(first?primary:"",first?"":primary));
    }

    private static KnowledgeGraph evolutionGraph(SourceToSpringPlanTest.Fixture f,com.evolution.analysis.spring.universal.SourceToSpringPlan.Result plan,
                                                 List<FiniteDomain.Value> values,Map<FiniteDomain.Variable,FiniteDomain.Value> assignment) {
        return evolutionGraph(f,plan,values,assignment,false);
    }

    private static KnowledgeGraph evolutionGraph(SourceToSpringPlanTest.Fixture f,SourceToSpringPlan.Result plan,
                                                 List<FiniteDomain.Value> values,Map<FiniteDomain.Variable,FiniteDomain.Value> assignment,
                                                 boolean symbolic) {
        var version=new VersionedIdentifier("graph.evolution-config","1");
        var proof=new ConditionEvidence.Derived(List.of(f.source().identity()),version,"finite-space");
        // Keep a typed profile even for unconditional fixture classes; the staged
        // source acquisition models an explicit finite environment.
        var variables=assignment.isEmpty()?Map.of(new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE,"dev"),FiniteDomain.Value.bool(false)):assignment;
        var choices=assignment.isEmpty()?List.of(FiniteDomain.Value.bool(false),FiniteDomain.Value.bool(true)):values;
        var domains=variables.keySet().stream().map(v->new FiniteDomain(v,choices,proof)).toList();
        var space=new ConfigurationSpace(f.build(),new ConfigurationEnvelope(ConfigurationEnvelope.Layer.REPOSITORY,List.of(),List.of(),
                ContentDigest.sha256Utf8("authored empty envelope policy")),
                Optional.empty(),domains,List.of(),
                new ConfigurationSpace.PrecedencePolicy(ExogenousConditionEvaluator.PRECEDENCE,List.of(),proof),
                new ConfigurationSpace.ProfilePolicy(ExogenousConditionEvaluator.PROFILES,List.of(),Map.of(),List.of(),proof),version,
                new ConfigurationSpace.FeasibilityPolicy(version,ConfigurationSpace.Limits.conservative(),proof));
        var baseline=new ConfigurationAssignment(variables,proof);
        var limits=TruthRegionEvaluation.Limits.conservative();
        var artifact=ContentDigest.sha256Utf8("graph-authored-evaluator");
        if(symbolic) {
            var symbolicLimits=SymbolicConfiguration.Limits.defaults();
            var truth=plan.evaluateSymbolic(space,baseline,limits,artifact,8,symbolicLimits).truth();
            var parameters=new CanonicalGraphInput.EvaluationParameters(limits,SatConfigurationReasoner.INSTANCE.policy(),artifact,8,Optional.of(symbolicLimits));
            var slice=new CanonicalGraphInput.SpringSlice(f.key(),plan,Optional.of(space),Optional.of(baseline),Optional.empty(),Optional.of(truth),Optional.of(parameters));
            return new CanonicalGraphBuilder().build(f.source().manifest(),f.source().frontend(),List.of(slice));
        }
        var truth=plan.evaluate(space,baseline,limits,artifact).truth();
        if(values.isEmpty()&&"COMPLETE".equals(plan.binding().registrationPlan().order().name()))
            assertTrue(truth.regions().stream().anyMatch(region->region.classification()!=TruthRegionEvaluation.Classification.UNKNOWN),
                    ()->truth.issues().stream().map(i->i.reason().name()).toList().toString()+" "+
                            truth.capabilityGaps().stream().map(g->g.reasonCode()).distinct().sorted().toList());
        var parameters=new CanonicalGraphInput.EvaluationParameters(limits,ExhaustiveConfigurationReasoner.INSTANCE.policy(),artifact,0,Optional.empty());
        var slice=new CanonicalGraphInput.SpringSlice(f.key(),plan,Optional.of(space),Optional.of(baseline),Optional.of(truth),Optional.empty(),Optional.of(parameters));
        return new CanonicalGraphBuilder().build(f.source().manifest(),f.source().frontend(),List.of(slice));
    }

    private static SnapshotComparison.Result conditional(KnowledgeGraph a,KnowledgeGraph b,int page)throws Exception {
        return SnapshotComparison.compare(conditionalRequest(a,b),EvolutionSnapshot.memory(a,b),EvolutionIndex.memory(),page,()->false);
    }

    private static SnapshotComparison.Request conditionalRequest(KnowledgeGraph a,KnowledgeGraph b) {
        var selection=new SnapshotComparison.Selection(Set.of("MAIN"),Set.of(),Set.of(),true,false);
        return new SnapshotComparison.Request(TestInputs.REPO,a.identity(),b.identity(),SnapshotComparison.Mode.STRICT,selection);
    }

    private static List<Map<String,Object>> rows(SnapshotComparison.Result result,SnapshotComparison.Section section)throws Exception {
        var all=new ArrayList<Map<String,Object>>();Optional<String> cursor=Optional.empty();
        do {
            var page=result.page(section,13,cursor);all.addAll(page.items());cursor=page.continuation();
        } while(cursor.isPresent());
        return all;
    }

    @Test void sameQualifiedNameAcrossMavenModulesStaysInSeparateAnalysisContexts() {
        String root="<project><modelVersion>4.0.0</modelVersion><groupId>x</groupId><artifactId>root</artifactId><version>1</version><packaging>pom</packaging><modules><module>a</module><module>b</module></modules></project>";
        String child="<project><modelVersion>4.0.0</modelVersion><parent><groupId>x</groupId><artifactId>root</artifactId><version>1</version></parent><artifactId>%s</artifactId></project>";
        var input=inputs(Map.of("pom.xml",root,"a/pom.xml",child.formatted("a"),"b/pom.xml",child.formatted("b"),
                "a/src/main/java/p/Same.java","package p; class Same {}","b/src/main/java/p/Same.java","package p; class Same {}"));
        var policy=UniversalArchitecturePipeline.Policy.builder(new JavaParserFrontend(),JavaParserFrontend.PROVIDER)
                .defaultPlatform(TestInputs.request("class PlatformStub {}").platform())
                .mavenProvider(new com.evolution.analysis.maven.MavenBuildModelProvider()).build();
        var detailed=new UniversalArchitecturePipeline().runWithEvidence(input,policy);
        var graph=new CanonicalGraphBuilder().build(detailed.graphInput().orElseThrow());
        var types=graph.nodes().stream().filter(n->n.kind().equals("TYPE")).toList();
        assertEquals(2,types.size());assertEquals(2,types.stream().map(KnowledgeGraph.Node::identity).distinct().count());
        assertEquals(2,types.stream().map(KnowledgeGraph.Node::context).distinct().count());
        assertTrue(graph.evidence().stream().anyMatch(e->e.family().equals("build.model")));
    }

    @Test void pagedSymbolicSpringResultStaysBoundedSurvivesTightRowBudgetAndAuthenticatesEvolution(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var helper = new SourceToSpringPlanTest();
        var fixture = helper.sourceFixture(Map.of(
                "app/App.java", """
                        package app;
                        @org.springframework.context.annotation.ComponentScan("app") class App {}
                        @org.springframework.stereotype.Component @org.springframework.context.annotation.Profile("dev") class DevClient {
                          @org.springframework.beans.factory.annotation.Autowired private Store store;
                        }
                        @org.springframework.stereotype.Component class ProdClient {
                          @org.springframework.beans.factory.annotation.Autowired private Store store;
                        }
                        """,
                "app/Store.java", "package app; @org.springframework.stereotype.Component public class Store {}"));
        var plan = helper.beanPlan(fixture, true, "Store", "DevClient", "ProdClient");
        var version = new VersionedIdentifier("graph.paged-symbolic-config", "1");
        var proof = new ConditionEvidence.Derived(List.of(fixture.source().identity()), version, "finite-space");
        var variable = new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE, "dev");
        var space = new ConfigurationSpace(fixture.build(),
                new ConfigurationEnvelope(ConfigurationEnvelope.Layer.REPOSITORY, List.of(), List.of(), fixture.source().identity()),
                Optional.empty(),
                List.of(new FiniteDomain(variable, List.of(FiniteDomain.Value.bool(false), FiniteDomain.Value.bool(true)), proof)),
                List.of(),
                new ConfigurationSpace.PrecedencePolicy(ExogenousConditionEvaluator.PRECEDENCE, List.of(), proof),
                new ConfigurationSpace.ProfilePolicy(ExogenousConditionEvaluator.PROFILES, List.of(), Map.of(), List.of(), proof),
                version,
                new ConfigurationSpace.FeasibilityPolicy(version, ConfigurationSpace.Limits.conservative(), proof));
        var baseline = new ConfigurationAssignment(Map.of(variable, FiniteDomain.Value.bool(false)), proof);
        var limits = TruthRegionEvaluation.Limits.conservative();
        var artifact = ContentDigest.sha256Utf8("graph-paged-symbolic-evaluator");
        var symbolicLimits = SymbolicConfiguration.Limits.defaults();
        var symbolic = plan.evaluateSymbolic(space, baseline, limits, artifact, 8, symbolicLimits, true).truth();
        assertFalse(symbolic.traces().isEmpty());
        assertTrue(symbolic.traces().stream().allMatch(SymbolicTruthRegions.Trace::replayed),
                "Precomputed-exogenous staged evaluation must match independent witness replay bit-for-bit");

        var slice = new CanonicalGraphInput.SpringSlice(fixture.key(), plan, Optional.of(space), Optional.of(baseline),
                Optional.empty(), Optional.of(symbolic),
                Optional.of(new CanonicalGraphInput.EvaluationParameters(limits, SatConfigurationReasoner.INSTANCE.policy(),
                        artifact, 8, Optional.of(symbolicLimits))));
        var graph = new CanonicalGraphBuilder().build(fixture.source().manifest(), fixture.source().frontend(), List.of(slice));

        var resultEv = graph.evidence().stream().filter(e -> e.family().equals("spring.symbolic-result")).findFirst().orElseThrow();
        assertTrue(resultEv.canonicalJson().length() < 4096,
                "Paged spring.symbolic-result must stay compact instead of duplicating traces/regions inline, got " + resultEv.canonicalJson().length());
        var parsedResult = GraphJson.object(GraphJson.read(resultEv.canonicalJson(), GraphJson.Limits.defaults()));
        assertEquals("spring.symbolic-result:m8.4-paged-v1", parsedResult.get("schema"));
        assertEquals(symbolic.identity().value(), parsedResult.get("resultIdentity"));
        assertEquals(symbolic.inputIdentity().value(), parsedResult.get("inputIdentity"));
        assertEquals(symbolic.signaturesComplete(), parsedResult.get("signaturesComplete"));
        assertFalse(parsedResult.containsKey("traces"), "Must not inline full traces inside spring.symbolic-result");
        assertFalse(parsedResult.containsKey("regions"), "Must not inline full regions inside spring.symbolic-result");
        assertTrue(graph.evidence().stream().anyMatch(e -> e.family().equals("spring.symbolic-region-digest-page")));
        assertTrue(graph.evidence().stream().anyMatch(e -> e.family().equals("spring.symbolic-trace-index-page")));
        assertEquals(symbolic.traces().size(), graph.evidence().stream().filter(e -> e.family().equals("spring.symbolic-trace")).count());

        // Partitioned disk projection must match in-memory canonical digest exactly even at the exact tight rowBytes
        // of the largest remaining evidence row (exercising leaf-envelope framing too).
        int maxRowBytes = graph.evidence().stream()
                .mapToInt(ev -> GraphCanonicalWriter.encodeValue(ev).getBytes(StandardCharsets.UTF_8).length)
                .max().orElse(4096);
        int tightBudget = Math.max(4096, maxRowBytes);
        var pagesDir = dir.resolve("paged-spring-pages");
        var snapsDir = dir.resolve("paged-spring-snaps");
        var published = new PartitionedGraphStore(pagesDir, tightBudget, 1).build(graph, snapsDir, () -> false, ws -> {});
        assertEquals(graph.identity(), published.graph());
        assertTrue(new PartitionedGraphStore(pagesDir, tightBudget, 1).verifyPublished(graph.identity(), () -> false));

        // ArchitectureQueryService / GraphRegionCatalog must query conditional regions cleanly without INVALID_REGION_EVIDENCE.
        String springCtx = graph.nodes().stream().filter(n -> n.kind().equals("TRUTH_REGION")).findFirst().orElseThrow().context();
        var queryReq = new com.evolution.analysis.query.ArchitectureQuery.Request(
                graph.identity(), springCtx,
                com.evolution.analysis.query.ArchitectureQuery.Scope.SPRING_COMPONENT,
                com.evolution.analysis.query.ArchitectureQuery.Projection.BOUNDED_UNION,
                com.evolution.analysis.query.ArchitectureQuery.Direction.OUTGOING,
                Set.of("graph.depends-on"),
                com.evolution.analysis.query.ArchitectureQuery.Population.all(),
                false, Optional.empty(), com.evolution.analysis.query.ArchitectureQuery.Limits.defaults());
        var configView = new ArchitectureQueryService(graph).configurations(queryReq,
                new com.evolution.analysis.query.ArchitectureQuery.Page(50, Optional.empty()));
        assertFalse(configView.items().isEmpty());
        var gapReasons = configView.gaps().stream().map(com.evolution.analysis.evidence.CapabilityGapRecord::reasonCode).toList();
        assertTrue(gapReasons.stream().noneMatch(r -> r.equals("INVALID_REGION_EVIDENCE") || r.equals("REGION_EVALUATION_UNAVAILABLE")),
                "GraphRegionCatalog must read paged spring.symbolic-result cleanly: " + gapReasons);

        // FileSnapshotComparison on PartitionedGraphStore must match in-memory SnapshotComparison identically.
        var memCompared = conditional(graph, graph, 5);
        try (var session = FileSnapshotComparison.compare(new PartitionedGraphStore(pagesDir, tightBudget, 1),
                dir.resolve("cmp-scratch"), conditionalRequest(graph, graph), 5, () -> false)) {
            assertEquals(memCompared.summary(), session.result().summary());
            assertEquals(SnapshotComparison.Status.COMPLETE, session.result().summary().status());
        }

        // Tampering with declared regionsCount on a paged spring.symbolic-result must fail closed.
        var tamperedMap = new TreeMap<>(parsedResult);
        tamperedMap.put("regionsCount", ((Number) parsedResult.get("regionsCount")).longValue() + 1L);
        var tamperedEv = GraphEvidence.capture("spring.symbolic-result", tamperedMap);
        var tamperedList = new ArrayList<GraphEvidence>();
        for (var ev : graph.evidence()) {
            tamperedList.add(ev.identity().equals(resultEv.identity()) ? tamperedEv : ev);
        }
        var tamperedGraph = new KnowledgeGraph(graph.snapshot(), graph.contexts(), graph.nodes(), graph.edges(), tamperedList);
        var tamperedCmp = conditional(graph, tamperedGraph, 5);
        assertNotEquals(SnapshotComparison.Status.COMPLETE, tamperedCmp.summary().status(),
                "Tampered paged spring.symbolic-result count mismatch must fail closed");
    }
}
