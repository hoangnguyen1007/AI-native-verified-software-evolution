package com.evolution.analysis.filesystem;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Explicit operator read policy, outside captured target input. It cannot
 * grant semantic qualification. Every access re-reads current withdrawals. */
public final class BridgeReadPolicy {
    private final Path path;
    public BridgeReadPolicy(Path path)throws IOException{this.path=path.toAbsolutePath().normalize();read();}
    public ContentDigest policy(){return readUnchecked().policy();}
    public Set<ContentDigest> revoked(){return readUnchecked().revoked();}
    private record State(ContentDigest policy,Set<ContentDigest> revoked){}
    private State readUnchecked(){try{return read();}catch(IOException failure){throw new UncheckedIOException(failure);}}
    private State read()throws IOException{
        StreamingContentStore.safe(path);if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("BRIDGE_READ_POLICY_UNAVAILABLE");
        try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){byte[] bytes=input.readNBytes(65537);if(bytes.length>65536)throw new IOException("BRIDGE_READ_POLICY_WORKER_REQUIRED");
            var value=GraphJson.object(GraphJson.read(new String(bytes,StandardCharsets.UTF_8),new GraphJson.Limits(65536,8,8192)));if(!value.keySet().equals(Set.of("schema","policy","revoked"))||!"architecture-bridge-read-policy:m8.4-v1".equals(value.get("schema")))throw new IOException("BRIDGE_READ_POLICY_SCHEMA");
            var revoked=new HashSet<ContentDigest>();for(Object item:GraphJson.array(value.get("revoked")))if(!revoked.add(new ContentDigest(GraphJson.string(item))))throw new IOException("BRIDGE_READ_POLICY_DUPLICATE_WITHDRAWAL");return new State(new ContentDigest(GraphJson.string(value.get("policy"))),Set.copyOf(revoked));
        }catch(IllegalArgumentException|ClassCastException invalid){throw new IOException("BRIDGE_READ_POLICY_INVALID",invalid);}
    }
}
