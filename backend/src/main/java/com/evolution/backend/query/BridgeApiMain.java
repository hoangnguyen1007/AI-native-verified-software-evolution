package com.evolution.backend.query;

import com.evolution.analysis.filesystem.*;
import com.evolution.analysis.graph.InMemoryGraphStore;
import com.evolution.analysis.polyglot.SemanticTables;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/** Explicit read-only loopback composition over an existing captured store. */
public final class BridgeApiMain {
    public static void main(String[] args){
        if(args.length<2||args.length>4){System.err.println("Usage: BridgeApiMain <existing-store> <operator-read-policy.json> [port] [warm-scope]");System.exit(2);return;}
        try{var root=StreamingContentStore.existingDirectory(Path.of(args[0]));StreamingContentStore.existingDirectory(root.resolve("cas"));StreamingContentStore.existingDirectory(root.resolve("bridges"));var policy=new BridgeReadPolicy(Path.of(args[1]));var content=new StreamingContentStore(root.resolve("cas"));var tables=new SemanticTables(content,65536);var publications=new BridgePublicationStore(root.resolve("bridges"),content,tables,policy::policy,policy::revoked);var api=ArchitectureQueryApi.withBridgeQueries(new ArchitectureQueryApi(new InMemoryGraphStore()),publications.queries());
            if(args.length==4)publications.load(new com.evolution.analysis.contract.common.ContentDigest(args[3]),()->false).orElseThrow(()->new java.io.IOException("BRIDGE_QUERY_NOT_FOUND"));
            try(var server=new ArchitectureHttpServer(api,args.length>=3?Integer.parseInt(args[2]):8080)){var stopped=new CountDownLatch(1);var shutdown=new Thread(()->{server.close();stopped.countDown();},"bridge-query-shutdown");Runtime.getRuntime().addShutdownHook(shutdown);try{server.start();System.out.println("Architecture bridges: "+server.uri()+"/bridges.html");stopped.await();}finally{try{Runtime.getRuntime().removeShutdownHook(shutdown);}catch(IllegalStateException stopping){/* shutdown owns cleanup */}}}
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}catch(Exception failure){System.err.println("Unable to open captured bridges; check the explicit store, read policy and port.");System.exit(1);}
    }
    private BridgeApiMain(){}
}
