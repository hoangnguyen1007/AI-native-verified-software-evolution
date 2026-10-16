package com.evolution.analysis.query;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.graph.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.query.ArchitectureQuery.*;
import static com.evolution.analysis.query.ConditionalPathCycleTest.*;

class ArchitectureQueryContractTest {
    @Test void provenanceDoesNotRequireEnumeratingAnUnrelatedLargeEntityPopulation(){
        var f=new Fixture();for(int i=0;i<12;i++)f.node("n"+i);String module=id("module");
        var evidence=GraphEvidence.capture("analysis.manifest",Map.of("modules",List.of(Map.of("identity",module,"path","analyzer")),"configuration",Map.of("values",Map.of("java.module",module,"java.source-set","MAIN"))));
        var graph=new KnowledgeGraph(f.snapshot,List.of(new KnowledgeGraph.Context(f.context,f.snapshot,Optional.empty(),evidence.identity())),f.nodes,f.edges,List.of(f.base,evidence));var d=f.request();
        var limits=new Limits(5,100,20,1,10,100,100,200_000);var request=new Request(graph.identity(),f.context,d.scope(),d.projection(),d.direction(),d.relationshipKinds(),d.population(),false,Optional.empty(),limits);
        var metadata=new ArchitectureQueryService(graph).provenance(request).items().getFirst().metadata();assertEquals("analyzer",metadata.get("modulePath"));assertEquals("MAIN",metadata.get("sourceSet"));
    }
    @Test void sourceDocumentLabelsExposeValidatedRelativePathsWithoutHostPaths(){
        var f=new Fixture();var module=ModuleDescriptor.create(RepositoryIdentity.fromCanonicalCoordinate("https://example.test/source"),"app","app");
        var doc=SourceDocument.create(module.repository(),module,"app/src/main/java/p/C.java",ContentDigest.sha256Utf8("source"),SourceClassification.MAIN);
        var evidence=GraphEvidence.capture("source.document",doc);f.evidence.add(evidence);f.nodes.add(new KnowledgeGraph.Node(doc.identity().value(),f.context,"SOURCE_DOCUMENT",List.of(evidence.identity())));
        var q=new ArchitectureQueryService(f.graph());assertEquals(doc.path(),q.entity(f.request(),doc.identity().value()).items().getFirst().qualifiedName().orElse(""));
        assertEquals(doc.path(),q.evidence(f.request(),evidence.identity()).items().getFirst().metadata().get("path"));
        var bad=GraphEvidence.capture("source.document",Map.of("identity",id("bad-document"),"path","C:/PRIVATE_HOST_PATH"));f.evidence.add(bad);f.nodes.add(new KnowledgeGraph.Node(id("bad-document"),f.context,"SOURCE_DOCUMENT",List.of(bad.identity())));
        var rejected=new ArchitectureQueryService(f.graph()).evidence(f.request(),bad.identity());
        assertFalse(com.evolution.analysis.contract.serialization.CanonicalJson.write(rejected).contains("PRIVATE_HOST_PATH"));assertTrue(rejected.gaps().stream().anyMatch(g->g.reasonCode().equals("INVALID_SOURCE_PATH")));
    }
    @Test void provenanceExposesOnlyValidatedFrontendPartitionWithoutConfigurationSecrets(){
        var f=new Fixture();var module=id("module");var evidence=GraphEvidence.capture("analysis.manifest",Map.of("modules",List.of(Map.of("identity",module)),"configuration",Map.of("values",Map.of("java.module",module,"java.source-set","MAIN","secret","PRIVATE_SENTINEL"))));
        var graph=new KnowledgeGraph(f.snapshot,List.of(new KnowledgeGraph.Context(f.context,f.snapshot,Optional.empty(),evidence.identity())),List.of(),List.of(),List.of(evidence));
        var request=Request.dependencies(graph.identity(),f.context);var metadata=new ArchitectureQueryService(graph).provenance(request).items().getFirst().metadata();
        assertEquals(module,metadata.get("module"));assertEquals("MAIN",metadata.get("sourceSet"));assertFalse(com.evolution.analysis.contract.serialization.CanonicalJson.write(metadata).contains("PRIVATE_SENTINEL"));
    }
    @Test void moduleLabelsComeFromCapturedDescriptorsAndRetainExactIdentity(){
        var f=new Fixture();var descriptor=ModuleDescriptor.create(RepositoryIdentity.fromCanonicalCoordinate("https://example.test/modules"),"analyzer","analyzer");
        var e=GraphEvidence.capture("module.descriptor",descriptor);f.evidence.add(e);f.nodes.add(new KnowledgeGraph.Node(descriptor.identity().value(),f.context,"MODULE",List.of(e.identity())));
        var result=new ArchitectureQueryService(f.graph()).entity(f.request(),descriptor.identity().value()).items().getFirst();
        assertEquals("analyzer",result.qualifiedName().orElse(""));assertEquals(descriptor.identity().value(),result.identity());assertEquals(List.of(e.identity()),result.evidence());
        var unsafe=GraphEvidence.capture("module.descriptor",Map.of("path","C:/PRIVATE_HOST_PATH","displayName","PRIVATE_DISPLAY_TEXT"));f.evidence.add(unsafe);f.nodes.add(new KnowledgeGraph.Node(id("unsafe-module"),f.context,"MODULE",List.of(unsafe.identity())));
        var rejected=new ArchitectureQueryService(f.graph()).entity(f.request(),id("unsafe-module"));
        assertFalse(com.evolution.analysis.contract.serialization.CanonicalJson.write(rejected).contains("PRIVATE_"));assertTrue(rejected.gaps().stream().anyMatch(g->g.reasonCode().equals("INVALID_MODULE_PATH")));
    }
    @Test void declarationBodiesCannotLeakThroughEntityOrEvidenceSpelling(){
        var f=new Fixture();var entity=Map.of("canonicalName","p.A","origin","PROJECT","stableScope",id("module"));
        var declaration=GraphEvidence.capture("java.declaration",Map.of("entity",entity,"spelling","class A { String password = \"PRIVATE_SENTINEL\"; }"));
        f.evidence.add(declaration);f.nodes.add(new KnowledgeGraph.Node(id("a"),f.context,"TYPE",List.of(declaration.identity())));
        var service=new ArchitectureQueryService(f.graph());
        assertFalse(com.evolution.analysis.contract.serialization.CanonicalJson.write(service.entities(f.request(),"",Page.first())).contains("PRIVATE_SENTINEL"));
        assertFalse(com.evolution.analysis.contract.serialization.CanonicalJson.write(service.explain(f.request(),id("a"),Page.first())).contains("PRIVATE_SENTINEL"));
        var evidence=service.evidence(f.request(),declaration.identity()).items().getFirst();assertTrue(evidence.redacted());assertFalse(evidence.metadata().containsKey("spelling"));
        assertEquals(declaration,service.graph().evidence(declaration.identity()).orElseThrow());
    }
    @Test void pagesAreStableAcrossReloadAndBindFiltersAndProjection(){
        var f=new Fixture();for(int i=0;i<7;i++)f.node("n"+i);
        var graph=f.graph();var service=new ArchitectureQueryService(graph);var request=f.request();var seen=new ArrayList<String>();Optional<String> cursor=Optional.empty();
        do {
            var result=service.entities(request,"",new Page(2,cursor));assertEquals(7,result.exactTotal().orElseThrow());seen.addAll(result.items().stream().map(EntityView::identity).toList());
            var copy=new GraphSnapshotCodec().decode(new GraphSnapshotCodec().encode(graph),GraphJson.Limits.defaults());
            assertEquals(result,new ArchitectureQueryService(copy).entities(request,"",new Page(2,cursor)));cursor=result.continuation();
        }while(cursor.isPresent());
        assertEquals(7,seen.size());assertEquals(7,new HashSet<>(seen).size());assertEquals(seen.stream().sorted().toList(),seen);
        var first=service.entities(request,"",new Page(2,Optional.empty()));
        assertThrows(IllegalArgumentException.class,()->service.entities(request,"n",new Page(2,first.continuation())));
        var must=ArchitectureProjectionTest.project(request,Projection.MUST,false,Optional.empty());
        assertThrows(IllegalArgumentException.class,()->service.entities(must,"",new Page(2,first.continuation())));
        assertThrows(IllegalArgumentException.class,()->service.entities(request,"",new Page(2,Optional.of("garbage"))));
    }
    @Test void moduleOriginAndSourcePopulationAreEvidenceBacked(){
        var f=new Fixture();var repo=RepositoryIdentity.fromCanonicalCoordinate("https://example.test/query.git");var module=ModuleDescriptor.create(repo,"module-a","a");
        var document=SourceDocument.create(repo,module,"src/main/java/p/A.java",ContentDigest.sha256Utf8("source"),SourceClassification.MAIN);
        var entity=Entity.create(EntityOrigin.PROJECT,EntityScope.project(module.identity()),EntityKind.TYPE,"p.A",Optional.of(new SourceSpan(document.identity(),1,1,1,10)));
        var decl=GraphEvidence.capture("java.declaration",Map.of("entity",entity,"spelling","A"));var source=GraphEvidence.capture("source.document",document);
        f.evidence.add(decl);f.evidence.add(source);f.nodes.add(new KnowledgeGraph.Node(entity.identity().value(),f.context,"TYPE",List.of(decl.identity())));
        f.nodes.add(new KnowledgeGraph.Node(document.identity().value(),f.context,"SOURCE_DOCUMENT",List.of(source.identity())));
        var graph=f.graph();var r=new Request(graph.identity(),f.context,Scope.TYPE,Projection.BOUNDED_UNION,Direction.OUTGOING,Set.of("fixture.depends"),
                new Population(Set.of("TYPE"),Set.of(module.identity().value()),Set.of("PROJECT"),Set.of("MAIN")),false,Optional.empty(),Limits.defaults());
        var result=new ArchitectureQueryService(graph).entities(r,"A",Page.first());assertEquals(1,result.items().size());assertEquals("MAIN",result.items().getFirst().sourceSet().orElseThrow());
    }
    @Test void malformedLimitsForeignContextAndMetricsAbsenceStayExplicit(){
        assertThrows(IllegalArgumentException.class,()->limits(21,100,50));assertThrows(IllegalArgumentException.class,()->limits(20,100_001,50));assertThrows(IllegalArgumentException.class,()->limits(20,100,51));
        var f=new Fixture().node("a");var service=new ArchitectureQueryService(f.graph());var r=f.request();
        var other=new Request(ContentDigest.sha256Utf8("foreign"),r.context(),r.scope(),r.projection(),r.direction(),r.relationshipKinds(),r.population(),false,Optional.empty(),r.limits());
        assertThrows(IllegalArgumentException.class,()->service.summary(other));
        var catalog=service.metricCatalog(r,Page.first());assertTrue(catalog.items().isEmpty());assertEquals(com.evolution.analysis.contract.assessment.AssessmentStatus.PARTIAL,catalog.status());
        assertEquals("METRICS_PROVIDER_UNAVAILABLE",catalog.gaps().getFirst().reasonCode());assertTrue(catalog.exactTotal().isEmpty());
    }
    @Test void boundedEvidenceDescriptorsPreserveSpansAndHideSecretPayloads(){
        var f=new Fixture().node("a");var secret=GraphEvidence.capture("source.configuration",Map.of("password","secret-value","path","C:/private/host","bytesBase64","private"));
        var occurrence=GraphEvidence.capture("java.occurrence",Map.of("status","PARTIAL","span",Map.of("document",id("document"),"startLine",3,"startColumn",4,"endLine",3,"endColumn",9),
                "derivation",Map.of("kind","DERIVED","provider",Map.of("id","test.provider","version","1"))));
        f.evidence.add(secret);f.evidence.add(occurrence);f.nodes.add(new KnowledgeGraph.Node(id("evidence-node"),f.context,"OCCURRENCE",List.of(secret.identity(),occurrence.identity())));
        var service=new ArchitectureQueryService(f.graph());var span=service.evidence(f.request(),occurrence.identity()).items().getFirst();
        assertEquals(new java.math.BigDecimal(9),GraphJson.object(span.metadata().get("span")).get("endColumn"));
        assertEquals("PARTIAL",span.metadata().get("status"));
        var safe=service.evidence(f.request(),secret.identity()).items().getFirst();assertTrue(safe.redacted());assertTrue(safe.metadata().isEmpty());
        assertFalse(com.evolution.analysis.contract.serialization.CanonicalJson.write(safe).contains("secret-value"));assertEquals(secret,service.graph().evidence(secret.identity()).orElseThrow());
        assertFalse(service.evidence(f.request(),ContentDigest.sha256Utf8("absent")).gaps().isEmpty());
    }
    @Test void shortestReachabilityAndIncomingImpactUseDirectedStructuralPaths(){
        var f=new Fixture().node("a").node("b").node("c").node("d").edge("a","b").edge("b","c").edge("a","c").edge("c","d");var service=new ArchitectureQueryService(f.graph());
        assertEquals(List.of(id("a"),id("c"),id("d")),service.shortestPath(f.request(),id("a"),id("d")).items().getFirst().vertices());
        assertEquals(3,service.reachability(f.request(),id("a")).items().size());
        var r=f.request();var incoming=new Request(r.graph(),r.context(),r.scope(),r.projection(),Direction.INCOMING,r.relationshipKinds(),r.population(),false,Optional.empty(),r.limits());
        assertEquals(3,service.impact(incoming,id("d")).items().size());assertThrows(IllegalArgumentException.class,()->service.impact(r,id("d")));
        assertEquals(2,service.expand(r,id("a")).items().getFirst().edges().size());
    }
    @Test void rawDenominatorKeepsCandidatesUnresolvedAndSafeAnnotationEvidence(){
        var f=new Fixture().node("a").node("b").node("c");
        f.edges.add(new KnowledgeGraph.Edge(id("candidate"),f.context,"fixture.depends",id("a"),new KnowledgeGraph.Target(KnowledgeGraph.TargetState.CANDIDATES,List.of(id("b"),id("c")),Optional.empty()),List.of(f.base.identity()),Optional.empty()));
        f.edges.add(new KnowledgeGraph.Edge(id("unresolved"),f.context,"fixture.depends",id("a"),new KnowledgeGraph.Target(KnowledgeGraph.TargetState.UNRESOLVED,List.of(),Optional.of("secret literal in missing expression")),List.of(f.base.identity()),Optional.empty()));
        var annotation=GraphEvidence.capture("java.annotation-use",Map.of("canonicalName","java:v1:[\"annotation-use\"]","spelling","@Value(\"secret-value\")","owner",id("a")));
        f.evidence.add(annotation);f.nodes.add(new KnowledgeGraph.Node(id("annotation"),f.context,"ANNOTATION_USE",List.of(annotation.identity())));
        var service=new ArchitectureQueryService(f.graph());var raw=service.relationships(f.request(),Page.first());assertEquals(2,raw.items().size());
        assertTrue(raw.items().stream().anyMatch(r->r.targetState().equals("UNRESOLVED")&&r.targets().isEmpty()&&r.unresolvedReference().isPresent()));
        var overlay=service.edges(ArchitectureProjectionTest.project(f.request(),Projection.UNKNOWN_OVERLAY,false,Optional.empty()),Page.first());
        assertEquals(2,overlay.items().size());assertTrue(overlay.items().stream().noneMatch(EdgeView::definite));assertFalse(overlay.gaps().isEmpty());
        assertFalse(com.evolution.analysis.contract.serialization.CanonicalJson.write(raw).contains("secret literal"));
        assertFalse(com.evolution.analysis.contract.serialization.CanonicalJson.write(service.evidence(f.request(),annotation.identity())).contains("secret-value"));
        assertEquals("m8.3-v1",VERSION.version());assertNotEquals(f.request().identity(),ArchitectureProjectionTest.project(f.request(),Projection.MUST,false,Optional.empty()).identity());
    }
    @Test void documentPopulationMetadataConsumesBudgetBeforeItIsRetained(){
        var f=new Fixture().node("type");var repo=RepositoryIdentity.fromCanonicalCoordinate("https://example.test/query.git");var module=ModuleDescriptor.create(repo,"module-a","a");
        for(int i=0;i<4;i++){
            var document=SourceDocument.create(repo,module,"src/main/java/p/A"+i+".java",ContentDigest.sha256Utf8("source"+i),SourceClassification.MAIN);
            var payload=GraphEvidence.capture("source.document",document);f.evidence.add(payload);f.nodes.add(new KnowledgeGraph.Node(document.identity().value(),f.context,"SOURCE_DOCUMENT",List.of(payload.identity())));
        }
        var limits=new Limits(20,100_000,50,100,100,1000,100,20);var graph=f.graph();
        var result=new ArchitectureQueryService(graph,()->StopReason.NONE,Optional.empty()).entities(f.request(limits),"",Page.first());
        assertEquals(com.evolution.analysis.contract.assessment.AssessmentStatus.PARTIAL,result.status());assertEquals(StopReason.INPUT,result.bounds().stopReason());
        assertTrue(result.bounds().truncated());assertTrue(result.exactTotal().isEmpty());assertFalse(result.gaps().isEmpty());
    }
    @Test void malformedPackageCanonicalNamesKeepEvidenceAndReturnTypedGaps(){
        for(String canonical:List.of("java:v1:[broken","java:v1:{}","java:v1:[]","java:v1:[null]","java:v1:[true,[]]",
                "java:v1:[\"type\",[]]","java:v1:[\"type\",\"not-package-parts\",\"A\"]","java:v1:[\"type\",[],\"A\",0]")){
            var f=new Fixture();var payload=GraphEvidence.capture("java.declaration",Map.of("entity",Map.of("canonicalName",canonical,"origin","PROJECT","stableScope",id("module")),"spelling","A"));
            f.evidence.add(payload);f.nodes.add(new KnowledgeGraph.Node(id("bad-package"),f.context,"PACKAGE",List.of(payload.identity())));
            var graph=f.graph();var service=new ArchitectureQueryService(graph);var result=assertDoesNotThrow(()->service.entities(f.request(),"",Page.first()));
            assertEquals(1,result.items().size());assertTrue(result.items().getFirst().canonicalName().isEmpty());assertEquals(List.of(payload.identity()),result.items().getFirst().evidence());
            assertEquals(com.evolution.analysis.contract.assessment.AssessmentStatus.PARTIAL,result.status());
            assertTrue(result.gaps().stream().anyMatch(g->g.reasonCode().equals("INVALID_CANONICAL_NAME")));
            assertEquals(payload,graph.evidence(payload.identity()).orElseThrow());
        }
    }
}
