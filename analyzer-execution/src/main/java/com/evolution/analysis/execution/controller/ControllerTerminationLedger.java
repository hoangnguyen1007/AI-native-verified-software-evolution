package com.evolution.analysis.execution.controller;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.execution.*;
import com.evolution.analysis.filesystem.*;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Controller-owned authority, never exposed as an upload/producer API. A backend records only its
 * independently observed disposition; target reports and mere VM absence on an unavailable host do not qualify.
 */
public final class ControllerTerminationLedger implements AutoCloseable {
    public enum Disposition { OFF,ABSENT,NEVER_LAUNCHED }
    private final SqliteInventoryStore journal;
    private final StreamingContentStore content;
    private final VersionedIdentifier authority;
    public ControllerTerminationLedger(Path root,StreamingContentStore content,VersionedIdentifier authority)throws IOException{
        this.content=Objects.requireNonNull(content);this.authority=Objects.requireNonNull(authority);journal=new SqliteInventoryStore(root,content);
    }
    private static String id(ContentDigest digest){return "termination-"+digest.value().substring(7);}
    public synchronized ContentDigest record(SandboxExecutor.Launch launch,String backendId,Disposition disposition,ContentHandle observation)throws IOException{
        ExecutionGraph.text(backendId);Objects.requireNonNull(disposition);
        if(observation.size()==0||!content.verify(observation))throw new IOException("TERMINATION_OBSERVATION_UNAVAILABLE");
        var bytes=CanonicalJson.write(Map.of("schema","execution-controller-termination:m8.4-v1","launch",CanonicalJson.sha256(launch),"authority",authority,"backendId",backendId,"disposition",disposition,"observation",observation)).getBytes(StandardCharsets.UTF_8);
        if(bytes.length>64000)throw new IOException("TERMINATION_RECORD_WORKER_REQUIRED");
        var handle=content.put(new ByteArrayInputStream(bytes),()->false);
        journal.create(id(handle.digest()),handle.digest(),Optional.of(handle));return handle.digest();
    }
    public synchronized void require(SandboxExecutor.Launch launch,ContentDigest digest)throws IOException{
        var row=journal.get(id(digest)).orElseThrow(()->new IOException("TERMINATION_AUTHORITY_REQUIRED"));
        var handle=row.payload().orElseThrow(()->new IOException("TERMINATION_RECORD_MISSING"));
        if(!handle.digest().equals(digest)||handle.size()>64000||!content.verify(handle))throw new IOException("TERMINATION_RECORD_CORRUPT");
        try(var input=content.open(handle)){
            var object=GraphJson.object(GraphJson.read(new InputStreamReader(input,StandardCharsets.UTF_8),new GraphJson.Limits(64000,16,512)));
            if(!object.keySet().equals(Set.of("schema","launch","authority","backendId","disposition","observation"))
                    ||!"execution-controller-termination:m8.4-v1".equals(object.get("schema"))||!CanonicalJson.sha256(launch).value().equals(object.get("launch"))
                    ||!CanonicalJson.write(authority).equals(CanonicalJson.write(object.get("authority"))))throw new IOException("TERMINATION_SCOPE_MISMATCH");
            ExecutionGraph.text(GraphJson.string(object.get("backendId")));Disposition.valueOf(GraphJson.string(object.get("disposition")));
            var raw=GraphJson.object(object.get("observation"));
            if(!raw.keySet().equals(Set.of("digest","size")))throw new IOException("TERMINATION_RECORD_CORRUPT");
            var observation=new ContentHandle(new ContentDigest(GraphJson.string(raw.get("digest"))),((java.math.BigDecimal)raw.get("size")).longValueExact());
            if(observation.size()==0||!content.verify(observation))throw new IOException("TERMINATION_OBSERVATION_UNAVAILABLE");
        }catch(IllegalArgumentException|ArithmeticException|ClassCastException invalid){throw new IOException("TERMINATION_RECORD_CORRUPT",invalid);}
    }
    @Override public void close()throws IOException{journal.close();}
}
