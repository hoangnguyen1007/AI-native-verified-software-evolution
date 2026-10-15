package com.evolution.analysis.javaparser;

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
        for(String field:List.of("declarations","occurrences","observations","types","annotations","derivedRelationships","typeDeclarations","memberDeclarations")){
            var records=GraphJson.array(restoredFrontend.get(field)).stream().map(id->GraphJson.read(graph.evidence(new ContentDigest(GraphJson.string(id))).orElseThrow().canonicalJson(),GraphJson.Limits.defaults())).toList();restoredFrontend.put(field,records);
        }
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
        assertEquals(ingestion.gaps().stream().map(g->g.gapIdentity().value()).sorted().distinct().toList(),GraphJson.array(payload.get("gaps")));
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

    @Test void explicitAndSymbolicSpringRegionsPreserveOwnerUsesAndReplayedWitnesses() throws Exception {
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
        var changedBaseline=new ConfigurationAssignment(Map.of(variable,FiniteDomain.Value.bool(true)),proof);
        assertThrows(IllegalArgumentException.class,()->new CanonicalGraphInput.SpringSlice(fixture.key(),plan,Optional.of(space),Optional.of(changedBaseline),Optional.of(explicit),Optional.empty(),slice.parameters()));
        assertThrows(IllegalArgumentException.class,()->new CanonicalGraphInput.SpringSlice(fixture.key(),plan,Optional.of(space),Optional.of(changedBaseline),Optional.empty(),Optional.of(symbolic),symbolicSlice.parameters()));
        var alteredLimits=new TruthRegionEvaluation.Limits(limits.enumeration(),1,limits.maxRegionCells(),limits.maxWitnessReplays());
        assertThrows(IllegalArgumentException.class,()->new CanonicalGraphInput.SpringSlice(fixture.key(),plan,Optional.of(space),Optional.of(baseline),Optional.of(explicit),Optional.empty(),
                Optional.of(new CanonicalGraphInput.EvaluationParameters(alteredLimits,ExhaustiveConfigurationReasoner.INSTANCE.policy(),artifact,0,Optional.empty()))));
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
}
