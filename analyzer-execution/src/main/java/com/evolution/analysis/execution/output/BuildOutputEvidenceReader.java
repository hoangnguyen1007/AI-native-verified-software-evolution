package com.evolution.analysis.execution.output;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.execution.*;
import com.evolution.analysis.execution.controller.DurableOutputPublication;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.javaevidence.JavaEvidenceRecord;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Generic controller-bound byte publication bridge. It never launches or promotes a semantic claim. */
public final class BuildOutputEvidenceReader {
    public record Capture(ContentHandle publication,ContentHandle rawCensus,ContentHandle outcomes,OutputCensus.Decision decision,List<DurableOutputPublication.RoleResult> roles,String trust){
        public Capture{roles=List.copyOf(roles);if(!"PRODUCER_ASSERTION".equals(trust))throw new IllegalArgumentException("BUILD_OUTPUT_TRUST_PROMOTION_FORBIDDEN");}
    }
    private final RepositoryContentStore content;
    public BuildOutputEvidenceReader(RepositoryContentStore content){this.content=Objects.requireNonNull(content);}
    public Capture read(DurableOutputPublication registeredPublisher,SandboxExecutor.Launch launch,ContentHandle header,OutputCensus.Recorder rows,BooleanSupplier cancelled)throws IOException{
        var published=registeredPublisher.publish(launch,header,cancelled);
        if(!content.verify(published.document())||published.document().size()>65536)throw new IOException("BUILD_OUTPUT_PUBLICATION_UNAVAILABLE");
        Map<String,Object> value;try(var in=content.open(published.document());var reader=new InputStreamReader(in,StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT))){value=GraphJson.object(GraphJson.read(reader,new GraphJson.Limits(65536,32,65536)));}
        if(!"execution-output-capture-publication:m8.4-v1".equals(value.get("schema"))||!"PRODUCER_ASSERTION".equals(value.get("trust"))||!Boolean.FALSE.equals(value.get("activeSemanticPublication"))||!JavaEvidenceRecord.handle(value.get("header")).equals(header)||!com.evolution.analysis.contract.serialization.CanonicalJson.sha256(launch).value().equals(value.get("launch")))throw new IOException("BUILD_OUTPUT_PUBLICATION_SCOPE_MISMATCH");
        var censusValue=GraphJson.object(value.get("census"));var verifiedHeader=GraphJson.object(censusValue.get("header"));var scope=GraphJson.object(verifiedHeader.get("scope"));var budget=GraphJson.object(scope.get("budget"));
        var expected=new OutputCensus.Scope(GraphJson.string(scope.get("attempt")),GraphJson.string(scope.get("nonce")),new OutputCensus.Budget(Math.toIntExact(number(budget.get("maxRowBytes"))),number(budget.get("maxObjectBytes")),number(budget.get("maxCaptureBytes"))));
        var census=new OutputCensus(content);var checked=census.verify(census.header(header,expected),content::open,rows,cancelled);
        if(checked.decision()!=published.decision()&&published.decision()!=OutputCensus.Decision.OPEN)throw new IOException("BUILD_OUTPUT_DISPOSITION_MISMATCH");
        return new Capture(published.document(),header,published.outcomes(),published.decision(),published.roles(),"PRODUCER_ASSERTION");
    }
    private static long number(Object value){return ((java.math.BigDecimal)value).longValueExact();}
}
