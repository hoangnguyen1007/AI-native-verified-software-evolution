package com.evolution.backend.query;

import com.evolution.analysis.assessment.*;
import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.evidence.EvidenceContext;
import com.evolution.analysis.frontend.JavaSymbolName;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.metrics.*;
import com.evolution.analysis.policy.*;
import com.evolution.analysis.query.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.backend.query.ArchitectureQueryApiTest.*;
import static com.evolution.analysis.query.ArchitectureQuery.*;

class PublishedReportsTest {
    @Test void batchPublicationIsAtomicAtCapacityAndIdempotentForExistingReports() {
        var f = fixture(); var published = new PublishedReports(); var batch = new ArrayList<PublishedReports.Report>();
        for (int i = 0; i < 33; i++) batch.add(new PublishedReports.Report(f.report.evaluation(),f.report.metrics(),
                Optional.of(f.report.assessment().orElseThrow().withComputedAt(java.time.Instant.ofEpochSecond(i)))));
        published.publishAll(batch.subList(0,31));
        assertThrows(IllegalStateException.class,() -> published.publishAll(batch.subList(31,33)));
        assertEquals(31,published.forGraph(f.graph.identity()).size()); assertTrue(published.get(batch.get(31).identity()).isEmpty());
        assertEquals(List.of(batch.getFirst().identity(),batch.get(31).identity()),published.publishAll(List.of(batch.getFirst(),batch.get(31))));
        assertEquals(32,published.forGraph(f.graph.identity()).size());
    }
    private static final String POLICY="""
            schema: architecture-policy:v1
            id: test.http
            version: "1"
            rules:
              - {id: first.rule, version: "1", kind: FORBIDDEN_DEPENDENCY, severity: ERROR, scope: TYPE, relationships: [graph.depends-on], from: {names: ['p.A']}, to: {names: ['p.B']}}
              - {id: second.rule, version: "1", kind: FORBIDDEN_DEPENDENCY, severity: WARNING, scope: TYPE, relationships: [graph.depends-on], from: {names: ['p.A']}, to: {names: ['p.B']}}
            """;
    record Fixture(KnowledgeGraph graph,PublishedReports.Report report) {
        InMemoryGraphStore store() { var store=new InMemoryGraphStore(); store.save(graph); return store; }
    }
    static Fixture fixture() { return fixture(POLICY,true); }
    static Fixture fixture(String policy,boolean resolved) {
        var f=new QueryFixture(); var repo=RepositoryIdentity.fromCanonicalCoordinate("https://example.test/http.git");
        var module=ModuleDescriptor.create(repo,".","root"); var identities=new ArrayList<String>();
        for(String name : List.of("A","B")) {
            var doc=SourceDocument.create(repo,module,"src/main/java/p/"+name+".java",ContentDigest.sha256Utf8("package p; class "+name+" {}"),SourceClassification.MAIN);
            var entity=Entity.create(EntityOrigin.PROJECT,EntityScope.project(module.identity()),EntityKind.TYPE,JavaSymbolName.topLevelType("p",name).canonicalName(),
                    Optional.of(new SourceSpan(doc.identity(),1,1,1,22)));
            var source=GraphEvidence.capture("source.document",doc); var declaration=GraphEvidence.capture("java.declaration",Map.of("entity",entity,"spelling",name));
            f.evidence.addAll(List.of(source,declaration));
            f.nodes.add(new KnowledgeGraph.Node(doc.identity().value(),f.context,"SOURCE_DOCUMENT",List.of(source.identity())));
            f.nodes.add(new KnowledgeGraph.Node(entity.identity().value(),f.context,"TYPE",List.of(declaration.identity()))); identities.add(entity.identity().value());
        }
        var document=SourceDocumentIdentity.from(repo,"src/main/java/p/A.java");
        var proof=GraphEvidence.capture("java.occurrence",Map.of("status",resolved ? "RESOLVED" : "PARTIAL","derivation","DIRECT","span",new SourceSpan(document,1,1,1,22)));
        f.evidence.add(proof); f.edges.add(new KnowledgeGraph.Edge(QueryFixture.id("dependency"),f.context,"graph.depends-on",identities.getFirst(),
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED,List.of(identities.getLast()),Optional.empty()),List.of(proof.identity()),Optional.empty()));
        var graph=f.graph(); var queries=new ArchitectureQueryService(graph);
        var compilation=new PolicyCompiler().compile(policy.getBytes(StandardCharsets.UTF_8),EvidenceContext.forSnapshot(graph.snapshot()),PolicyCompiler.Limits.defaults());
        var mapping=new PolicyGraphMapper().bind(compilation,queries,f.context,PolicyGraphMapper.Limits.defaults());
        var evaluation=new PolicyEvaluator().evaluate(compilation,mapping,queries,PolicyEvaluation.Limits.defaults());
        var metrics=new PolicyMetricsService().compute(compilation,mapping,evaluation,queries,PolicyMetrics.Limits.defaults());
        var assessment=new ArchitectureAssessmentService().assess(compilation,mapping,evaluation,queries,Optional.of(ArchitectureScoreModel.candidate(Scope.TYPE)),
                PolicyMetrics.Limits.defaults(),ArchitectureMetric.Limits.defaults(),ArchitectureAssessment.Limits.defaults());
        return new Fixture(graph,new PublishedReports.Report(evaluation,Optional.of(metrics),Optional.of(assessment)));
    }
    static ArchitectureQueryApi.Response report(ArchitectureQueryApi api,Fixture f,ContentDigest id,String operation,Object input) {
        return api.handle("POST","/api/v1/graphs/"+f.graph.identity().value()+"/reports/"+id.value()+"/"+operation,"application/json",bytes(input),() -> false);
    }
    @Test void actualM6FindingsBundlesMetricsAndWithheldAssessmentRetainCanonicalValues() {
        var f=fixture(); var published=new PublishedReports(); var id=published.publish(f.report); var api=new ArchitectureQueryApi(f.store(),published,ApiLimits.defaults());
        assertEquals(AssessmentStatus.COMPLETE,f.report.evaluation().status()); assertEquals(2,f.report.evaluation().findings().size());
        var finding=f.report.evaluation().findings().getFirst(); assertEquals(Classification.MUST,finding.classification());
        var response=report(api,f,id,"finding",Map.of("arguments",Map.of("identity",finding.identity())));
        assertEquals(200,response.status()); assertEquals(CanonicalJson.write(finding),CanonicalJson.write(body(response).get("data")));
        response=report(api,f,id,"bundle",Map.of("arguments",Map.of("identity",finding.bundle().identity())));
        assertEquals(CanonicalJson.write(finding.bundle()),CanonicalJson.write(body(response).get("data")));
        assertEquals(1,finding.bundle().sources().size()); assertTrue(finding.bundle().region().witness().orElseThrow().formulaReplayed());
        for(String operation : List.of("policy-metrics","assessment")) {
            response=report(api,f,id,operation,Map.of()); assertEquals(200,response.status());
            Object expected=operation.equals("policy-metrics") ? f.report.metrics().orElseThrow() : f.report.assessment().orElseThrow();
            assertEquals(CanonicalJson.write(expected),CanonicalJson.write(body(response).get("data")));
        }
        assertEquals(AssessmentStatus.WITHHELD,f.report.assessment().orElseThrow().status()); assertTrue(f.report.assessment().orElseThrow().score().isEmpty());
        assertEquals(id,published.publish(f.report)); assertEquals(1,published.forGraph(f.graph.identity()).size());
    }
    @Test void reportPagesRetainRuleDenominatorAndRejectCrossOperationCursors() {
        var f=fixture(); var published=new PublishedReports(); var id=published.publish(f.report); var api=new ArchitectureQueryApi(f.store(),published,ApiLimits.defaults());
        var response=report(api,f,id,"rules",Map.of("page",new Page(1,Optional.empty())));
        var data=GraphJson.object(body(response).get("data")); assertEquals(200,response.status());
        assertEquals(new java.math.BigDecimal(2),data.get("returnedTotal")); assertEquals(1,GraphJson.array(data.get("items")).size()); assertEquals(true,data.get("truncated"));
        String cursor=GraphJson.string(data.get("continuation")); var page=new Page(1,Optional.of(cursor));
        response=report(api,f,id,"rules",Map.of("page",page)); assertEquals(200,response.status());
        assertNull(GraphJson.object(body(response).get("data")).get("continuation"));
        assertEquals(400,report(api,f,id,"findings",Map.of("page",page)).status());
        assertEquals(400,report(api,f,id,"rules",Map.of("page",new Page(2,Optional.of(cursor)))).status());
        assertEquals(400,report(api,f,id,"assessment",Map.of("page",Page.first())).status());
        assertEquals(400,report(api,f,id,"assessment",Map.of("arguments",Map.of("projection","MUST"))).status());
    }
    @Test void absentReportsAndArtifactsNeverBecomeCleanResultsAndForeignInputsAreRejected() {
        var f=fixture(); var published=new PublishedReports(); var id=published.publish(new PublishedReports.Report(f.report.evaluation(),Optional.empty(),Optional.empty()));
        var api=new ArchitectureQueryApi(f.store(),published,ApiLimits.defaults());
        assertEquals(404,report(api,f,id,"assessment",Map.of()).status()); assertEquals(404,report(api,f,id,"policy-metrics",Map.of()).status());
        assertEquals(404,report(api,f,ContentDigest.sha256Utf8("missing"),"rules",Map.of()).status());
        assertEquals(404,report(api,f,id,"finding",Map.of("arguments",Map.of("identity",ContentDigest.sha256Utf8("missing")))).status());
        assertThrows(IllegalArgumentException.class,() -> new PublishedReports.Report(f.report.evaluation(),Optional.empty(),f.report.assessment()));
        var e=f.report.evaluation(); var foreign=new PolicyEvaluation.Result(e.compilation(),e.mapping(),ContentDigest.sha256Utf8("foreign"),e.context(),e.limits(),e.status(),e.rules(),e.gaps(),e.upstreamEvidence());
        assertThrows(IllegalArgumentException.class,() -> new PublishedReports.Report(foreign,f.report.metrics(),Optional.empty()));
    }
    @Test void unknownAndCleanRulesStayInThePublishedDenominator() {
        for(boolean complete : List.of(false,true)) {
            String policy=complete ? POLICY.replace("from: {names: ['p.A']}, to: {names: ['p.B']}","from: {names: ['p.B']}, to: {names: ['p.A']}") : POLICY;
            var f=fixture(policy,complete); var published=new PublishedReports(); var id=published.publish(f.report);
            var api=new ArchitectureQueryApi(f.store(),published,ApiLimits.defaults());
            var response=report(api,f,id,"rules",Map.of()); assertEquals(200,response.status());
            var data=GraphJson.object(body(response).get("data")); assertEquals(2,GraphJson.array(data.get("items")).size());
            for(Object item : GraphJson.array(data.get("items"))) {
                var rule=GraphJson.object(GraphJson.object(item).get("result"));
                assertEquals(complete ? "NEVER" : "UNKNOWN",rule.get("classification"));
            }
            if (complete) { assertTrue(f.report.evaluation().findings().isEmpty()); assertEquals("COMPLETE",data.get("evaluationStatus")); }
            else { assertFalse(GraphJson.array(data.get("gaps")).isEmpty()); assertEquals("PARTIAL",data.get("evaluationStatus")); }
        }
    }
    @Test void policyImpactAndVisualGraphAreServedFromReport() {
        var f=fixture(); var published=new PublishedReports(); var id=published.publish(f.report); var api=new ArchitectureQueryApi(f.store(),published,ApiLimits.defaults());
        var bId = f.graph.nodes().stream().filter(n -> n.kind().equals("TYPE") && f.graph.evidence(n.evidence().getFirst()).orElseThrow().canonicalJson().contains("\"spelling\":\"B\"")).findFirst().orElseThrow().identity();
        var impactResponse = report(api, f, id, "policy-impact", Map.of("arguments", Map.of("identity", bId)));
        assertEquals(200, impactResponse.status());
        var impactData = GraphJson.object(body(impactResponse).get("data"));
        assertEquals(bId, impactData.get("changedEntity"));

        var visualResponse = report(api, f, id, "visual-graph", Map.of());
        assertEquals(200, visualResponse.status());
        var visualData = GraphJson.object(body(visualResponse).get("data"));
        assertNotNull(visualData.get("id"));
        assertFalse(GraphJson.array(visualData.get("nodes")).isEmpty());
    }
}
