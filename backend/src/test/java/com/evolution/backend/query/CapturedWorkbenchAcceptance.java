package com.evolution.backend.query;

import com.evolution.analysis.assessment.*;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.evidence.EvidenceContext;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.metrics.*;
import com.evolution.analysis.policy.*;
import com.evolution.analysis.query.ArchitectureQuery.Scope;
import com.evolution.backend.analysis.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/** Test-only interactive harness over source-generated, hash-validated snapshots. No fabricated production data. */
public final class CapturedWorkbenchAcceptance {
    public static void main(String[] args)throws Exception{
        if(args.length!=3&&args.length!=5)throw new IllegalArgumentException("graph.json policy.yaml port [candidate.graph.json control.graph.json]");
        var graph=new GraphSnapshotCodec().decode(Files.readString(Path.of(args[0])),GraphJson.Limits.defaults());
        var policy=Files.readAllBytes(Path.of(args[1]));var store=new InMemoryGraphStore();var reports=new PublishedReports();
        var repository=RepositoryIdentity.fromCanonicalCoordinate("https://example.test/captured-source-acceptance");
        var setup=new AnalysisJobs.Setup("captured-source-acceptance",repository,21,false,Optional.of(com.evolution.analysis.contract.common.ContentDigest.sha256(policy)),Optional.empty());
        for(int i=3;i<args.length;i++) {
            var operand = new GraphSnapshotCodec().decode(Files.readString(Path.of(args[i])),GraphJson.Limits.defaults());
            store.save(operand); reports.publishAll(evaluate(operand,policy,()->{}));
        }
        AnalysisJobs.Runner runner=control->{
            control.stage(AnalysisJobs.Stage.GRAPH);
            var results=evaluate(graph,policy,control::checkpoint);
            return new AnalysisJobs.Outcome(AnalysisJobs.Completion.PARTIAL,Optional.of(graph),results,List.of(),Optional.empty(),Optional.empty(),List.of());
        };
        try(var jobs=new AnalysisJobs(List.of(new AnalysisJobs.Registration(setup,runner)),store,reports,AnalysisJobs.Limits.defaults())){
            var api=ArchitectureQueryApi.withConfiguredDemoRepairCapture(new ArchitectureQueryApi(store,reports,ApiLimits.defaults(),jobs));
            try(var server=new ArchitectureHttpServer(ArchitectureQueryApi.withConfiguredDemoDiagnosis(api),Integer.parseInt(args[2]))){
                server.start();System.out.println("Source-generated acceptance workbench: "+server.uri());new CountDownLatch(1).await();
            }
        }
    }
    @FunctionalInterface private interface Checkpoint { void check() throws Exception; }
    private static List<PublishedReports.Report> evaluate(KnowledgeGraph graph,byte[] policy,Checkpoint checkpoint) throws Exception {
        var results=new ArrayList<PublishedReports.Report>();
        var q=new ArchitectureMetricsService(graph).queries();
        var compiled=new PolicyCompiler().compile(policy,EvidenceContext.forSnapshot(graph.snapshot()),PolicyCompiler.Limits.defaults());
        for(var context:graph.contexts())if(context.analysis().isPresent()){
            checkpoint.check();
            var mapping=new PolicyGraphMapper().bind(compiled,q,context.identity(),PolicyGraphMapper.Limits.defaults());
            var evaluation=new PolicyEvaluator().evaluate(compiled,mapping,q,PolicyEvaluation.Limits.defaults());
            var metrics=new PolicyMetricsService().compute(compiled,mapping,evaluation,q,PolicyMetrics.Limits.defaults());
            var score=new ArchitectureAssessmentService().evaluate(metrics,evaluation,q,Optional.of(ArchitectureScoreModel.candidate(Scope.SPRING_COMPONENT)),List.of(),ArchitectureAssessment.Limits.defaults());
            results.add(new PublishedReports.Report(evaluation,Optional.of(metrics),Optional.of(score)));
        }
        return results;
    }
    private CapturedWorkbenchAcceptance(){}
}
