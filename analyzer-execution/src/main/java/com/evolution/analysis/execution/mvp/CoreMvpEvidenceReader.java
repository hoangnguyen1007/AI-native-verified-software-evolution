package com.evolution.analysis.execution.mvp;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.execution.output.OutputCensus;
import com.evolution.analysis.filesystem.StreamingContentStore;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.javaevidence.JavaEvidenceRecord;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Reads the registered fixed-fixture publication without acquiring an execution entitlement.
 * Raw census rows keep module ownership unknown until independent scoped lineage is supplied. */
public final class CoreMvpEvidenceReader {
    public record Capture(ContentHandle publication,ContentHandle census,OutputCensus.Result result,Map<String,ContentHandle> roles,String trust){public Capture{roles=Map.copyOf(roles);if(!trust.equals("PRODUCER_ASSERTION"))throw new IllegalArgumentException("CORE_MVP_TRUST_PROMOTION_FORBIDDEN");}}
    public Capture read(Path registeredRoot,OutputCensus.Recorder rows,BooleanSupplier cancelled)throws IOException{
        CoreMvpRegistrations.require(registeredRoot);var root=registeredRoot.toRealPath();
        var setup=CoreMvpBuildPipeline.read(root.resolve("setup.json"),65536);var store=new StreamingContentStore(root.resolve("publication-cas"));
        var launcher=new HyperVMvpLauncher(setup);var publication=new CoreMvpBuildPipeline(store,launcher).replay(root);
        Map<String,Object> report;try(var in=store.open(publication);var reader=new InputStreamReader(in,StandardCharsets.UTF_8)){report=GraphJson.object(GraphJson.read(reader,new GraphJson.Limits(1_000_000,32,100_000)));}
        if(!"core-mvp-build-result:m8.4-v1".equals(report.get("schema"))||!"PRODUCER_ASSERTION".equals(report.get("semanticTrust"))||!Boolean.FALSE.equals(report.get("qualified")))throw new IOException("CORE_MVP_EVIDENCE_SCOPE_MISMATCH");
        var request=GraphJson.object(CoreMvpBuildPipeline.read(root.resolve("export-request.json"),65536).get("request"));
        var scope=new OutputCensus.Scope(GraphJson.string(report.get("attempt")).replace("-",""),GraphJson.string(request.get("nonce")),new OutputCensus.Budget(65536,134217728,536870912));
        var census=JavaEvidenceRecord.handle(report.get("census"));var verifier=new OutputCensus(store);var result=verifier.verify(verifier.header(census,scope),store::open,rows,cancelled);
        var roles=new TreeMap<String,ContentHandle>();for(var entry:GraphJson.object(report.get("outputs")).entrySet())roles.put(entry.getKey(),JavaEvidenceRecord.handle(entry.getValue()));
        return new Capture(publication,census,result,roles,"PRODUCER_ASSERTION");
    }
}
