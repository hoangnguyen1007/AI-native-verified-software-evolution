package com.evolution.analysis.execution.output;

import com.evolution.analysis.acquisition.streaming.ContentHandle;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.filesystem.StreamingContentStore;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Replays the real authored guest export through the production byte/census parser after VM Off.
 * No compiler/model facts or qualified capture method are created.
 */
public final class NativeCensusFixtureMain {
    static Map<String,Object> read(Path file)throws IOException{
        if(Files.size(file)>65536)throw new IOException("FIXTURE_METADATA_WORKER_REQUIRED");
        return GraphJson.object(GraphJson.read(Files.readString(file),new GraphJson.Limits(65536,24,4096)));
    }
    static ContentHandle capture(StreamingContentStore cas,Path source)throws IOException{
        try(var input=Files.newInputStream(source,LinkOption.NOFOLLOW_LINKS)){return cas.put(input,()->false);}
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException("fixture root and expected work UUID required");
        Path root=Path.of(args[0]);String expectedWork=args[1];var summary=read(root.resolve("summary.json"));var stopped=read(root.resolve("worker-off.json"));var scope=read(root.resolve("scope.json"));
        if(!"Off".equals(stopped.get("state"))||!"01a6f92c-d952-4bd6-abf4-63c5437e71b2".equals(stopped.get("vmId"))||!Boolean.FALSE.equals(summary.get("qualified"))||!expectedWork.equals(summary.get("workAttempt")))throw new IOException("AUTHORED_EXPORT_SCOPE_OR_TERMINATION_INVALID");
        var budget=GraphJson.object(scope.get("budget"));
        var expected=new OutputCensus.Scope(expectedWork,GraphJson.string(summary.get("nonce")),new OutputCensus.Budget(((java.math.BigDecimal)budget.get("maxRowBytes")).intValueExact(),((java.math.BigDecimal)budget.get("maxObjectBytes")).longValueExact(),((java.math.BigDecimal)budget.get("maxCaptureBytes")).longValueExact()));
        var cas=new StreamingContentStore(root.resolve("verification-cas"));
        capture(cas,root.resolve("scope.json"));capture(cas,root.resolve("entries.ndjson"));var source=capture(cas,root.resolve("collector-result.json"));
        var parser=new OutputCensus(cas);var header=parser.header(source,expected);
        try(var journal=java.nio.channels.FileChannel.open(root.resolve("host-row-outcomes.ndjson"),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            var result=parser.verify(header,h->Files.newInputStream(root.resolve("objects").resolve(h.digest().value().substring(7)),LinkOption.NOFOLLOW_LINKS),row->{
                var buffer=ByteBuffer.wrap((CanonicalJson.write(row)+"\n").getBytes(StandardCharsets.UTF_8));while(buffer.hasRemaining())journal.write(buffer);
            },()->false);
            journal.force(true);
            if(result.decision()!=OutputCensus.Decision.CAPTURED||result.unavailableObjects()!=0)throw new IOException("NATIVE_CENSUS_NOT_CAPTURED");
            var encoded=CanonicalJson.write(result);
            Files.writeString(root.resolve("host-verification.json"),encoded,StandardOpenOption.CREATE_NEW);
            System.out.println(CanonicalJson.write(Map.of("schema","authored-native-census-verification:m8.4-v1","decision",result.decision(),"metadataRows",result.observed().orElseThrow().entries(),"byteObjects",result.byteObjects(),"referencedBytes",result.observed().orElseThrow().capturedBytes(),"effectiveTrust",result.effectiveTrust(),"qualified",false)));
        }
    }
}
