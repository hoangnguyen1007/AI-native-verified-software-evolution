package com.evolution.backend.analysis;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.filesystem.*;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.ingestion.UniversalArchitecturePipeline;
import com.evolution.analysis.javaparser.JavaParserFrontend;
import com.evolution.analysis.query.*;
import com.evolution.backend.query.PublishedReports;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Product composition for an explicit captured-input registration. It has no execution
 * port and cannot build, issue attestations, or discover target lifecycle commands. */
public final class CapturedJavaExecution {
    public record Result(Map<String,Object> semantic,Map<String,Object> audit) {
        public Result {semantic=Map.copyOf(semantic);audit=Map.copyOf(audit);}
    }
    public static Result run(Path root,BooleanSupplier cancelled)throws Exception{
        var registration=CapturedJavaReader.registration(root);var pin=CanonicalJson.sha256(registration);
        var content=new StreamingContentStore(Path.of(registration.contentDirectory()));
        var loaded=new CapturedJavaReader(content,cancelled).load(registration,root.resolve("views"));
        var graphs=new FileGraphStore(Files.createDirectories(root.resolve("graphs")));var reports=new PublishedReports();
        var store=new JavaEvidencePublicationStore(root.resolve("publications"),content);
        var publication=new PreparedAnalysisPublication(store,content,registration.capture().digest());
        var policy=UniversalArchitecturePipeline.Policy.builder(new JavaParserFrontend(),JavaParserFrontend.PROVIDER)
                .truthEvaluationMode(UniversalArchitecturePipeline.TruthEvaluationMode.NONE).build();
        java.util.function.Supplier<ContentDigest> livePolicy=()->{
            try{return CanonicalJson.sha256(CapturedJavaReader.registration(root)).equals(pin)?registration.policy():ContentDigest.sha256Utf8("registration changed");}
            catch(IOException unavailable){return ContentDigest.sha256Utf8("registration unavailable");}
        };
        var runner=new PreparedJavaAnalysisRunner(loaded.prepared(),policy,Optional.of(loaded.semantic()),Optional.empty(),livePolicy,loaded.readinessProofs()).withPublication(publication);
        var repository=new PreparedJavaRepositoryRunner(loaded.prepared().readiness(),registration.policy(),livePolicy,loaded.readinessProofs(),List.of(runner),graphs)
                .withCheckpoint(store,content,CanonicalJson.sha256(Map.of("root",registration.capture())));
        var rootSetup=new AnalysisJobs.Setup("captured-root",loaded.prepared().base().snapshot().repository(),loaded.prepared().replayInputs().platform().release(),false,Optional.empty(),Optional.empty());
        try(var disk=new AnalysisJobDiskStore(root.resolve("jobs"));var jobs=new AnalysisJobs(List.of(runner.registration("captured-context"),repository.registration(rootSetup)),graphs,reports,new AnalysisJobs.Limits(1,1,8,0,1000),disk)){
            var child=finish(jobs,jobs.submit("captured-context","context-"+UUID.randomUUID()).job(),cancelled);
            if(child.result().isEmpty()||child.result().orElseThrow().graph().isEmpty())throw new IOException("CAPTURED_JAVA_CONTEXT_FAILED");
            var graph=graphs.load(child.result().orElseThrow().graph().orElseThrow()).orElseThrow();
            var request=ArchitectureQuery.Request.dependencies(graph.identity(),graph.snapshot().value());var queries=new ArchitectureQueryService(graph);
            var current=gapIds(graph,queries,request,ArchitectureQuery.GapView.CURRENT);
            var history=gapIds(graph,queries,request,ArchitectureQuery.GapView.HISTORY);
            var all=gapIds(graph,queries,request,ArchitectureQuery.GapView.ALL);
            var rootJob=finish(jobs,jobs.submit("captured-root","root-"+UUID.randomUUID()).job(),cancelled);
            if(rootJob.result().isEmpty()||rootJob.result().orElseThrow().graph().isEmpty())throw new IOException("CAPTURED_JAVA_ROOT_FAILED");
            var semantic=new TreeMap<String,Object>();semantic.put("capture",registration.capture());semantic.put("policy",registration.policy());
            semantic.put("qualification",registration.qualification());semantic.put("graph",graph.identity());semantic.put("snapshot",graph.snapshot());
            semantic.put("rootGraph",rootJob.result().orElseThrow().graph().orElseThrow());semantic.put("state",child.state());semantic.put("rootState",rootJob.state());
            semantic.put("current",current.size());semantic.put("history",history.size());semantic.put("all",all.size());semantic.put("jobGaps",child.result().orElseThrow().gapCount());
            semantic.put("gapPopulation","ALL_CONTEXT_GAP_VIEW_IDS");
            semantic.put("publication",publication.latest().orElseThrow().document());
            return new Result(semantic,Map.of("contextJob",child.id(),"contextSubmittedAt",child.submittedAt(),"rootJob",rootJob.id(),"rootSubmittedAt",rootJob.submittedAt()));
        }
    }
    private static Set<String> gapIds(KnowledgeGraph graph,ArchitectureQueryService queries,ArchitectureQuery.Request request,ArchitectureQuery.GapView view)throws IOException{
        var ids=new TreeSet<String>();
        for(var context:graph.contexts()){
        var scopedIds=new HashSet<String>();Optional<String> cursor=Optional.empty();var scoped=ArchitectureQuery.Request.dependencies(graph.identity(),context.identity());
        do{var page=queries.gaps(scoped,view,new ArchitectureQuery.Page(50,cursor));
            if(page.operation()!=ArchitectureQuery.Operation.COMPLETED)throw new IOException("CAPTURED_JAVA_QUERY_INCOMPLETE");
            for(var item:page.items()){if(!scopedIds.add(item.identity()))throw new IOException("CAPTURED_JAVA_QUERY_DUPLICATE");ids.add(item.identity());}cursor=page.continuation();
        }while(cursor.isPresent());
        }
        Set<String> kinds=switch(view){case CURRENT->Set.of("CAPABILITY_GAP","ACQUISITION_ATTEMPT","PROVIDER_CONFLICT");case HISTORY->Set.of("HISTORICAL_CAPABILITY_GAP");case ALL->Set.of("CAPABILITY_GAP","HISTORICAL_CAPABILITY_GAP","ACQUISITION_ATTEMPT","PROVIDER_CONFLICT");};
        var expected=new TreeSet<String>();graph.nodes().stream().filter(n->kinds.contains(n.kind())).forEach(n->expected.add(n.identity()));
        if(!ids.equals(expected))throw new IOException("CAPTURED_JAVA_QUERY_CENSUS_MISMATCH");return ids;
    }
    private static AnalysisJobs.View finish(AnalysisJobs jobs,AnalysisJobs.View view,BooleanSupplier cancelled)throws Exception{
        while(!AnalysisJobs.terminal(view.state())){
            if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted()){jobs.cancel(UUID.fromString(view.id()));throw new java.util.concurrent.CancellationException();}
            Thread.sleep(10);view=jobs.get(UUID.fromString(view.id()));
        }return view;
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("registered capture directory required");
        System.out.println(CanonicalJson.write(run(Path.of(args[0]),()->false)));
    }
    private CapturedJavaExecution(){}
}
