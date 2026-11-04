package com.evolution.backend.query;

import com.evolution.analysis.graph.*;
import com.evolution.analysis.filesystem.FileGraphStore;
import com.evolution.analysis.filesystem.PartitionedGraphStore;
import com.evolution.analysis.contract.common.ContentDigest;
import java.nio.file.*;
import java.util.concurrent.CountDownLatch;

/** Local demo: explicitly supplied canonical snapshot; no target code or target build runs. */
public final class QueryApiMain {
    public static void main(String[] args) {
        boolean paged=args.length>0&&args[0].equals("--partitions");
        if (paged ? args.length<3||args.length>4 : args.length<1||args.length>2) { System.err.println("Usage: QueryApiMain <graph-snapshot.json> [port] | --partitions <directory> <graph-id> [port]"); System.exit(2); }
        try {
            ArchitectureQueryApi api;String route;int port;
            if(paged){
                var pages=new PartitionedGraphStore(Path.of(args[1]));var graph=new ContentDigest(args[2]);
                pages.partitions(graph,1,java.util.Optional.empty());
                api=ArchitectureQueryApi.withPartitionedGraphs(new ArchitectureQueryApi(new InMemoryGraphStore()),pages::reader);
                route="/api/v1/graph-pages/"+graph.value()+"/partitions";port=args.length==4?Integer.parseInt(args[3]):8080;
            }else{
                var path=Path.of(args[0]);
                if (!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) throw new IllegalArgumentException();
                var graph=loadSnapshot(path);var store=new InMemoryGraphStore();store.save(graph);
                api=new ArchitectureQueryApi(store);route="/api/v1/graphs/"+graph.identity().value();port=args.length==2?Integer.parseInt(args[1]):8080;
            }
            try (var server=new ArchitectureHttpServer(api,port)) {
                var stopped=new CountDownLatch(1);
                var shutdown=new Thread(() -> { server.close(); stopped.countDown(); },"architecture-http-shutdown");
                Runtime.getRuntime().addShutdownHook(shutdown);
                try {
                    server.start();
                    System.out.println("Architecture query API: "+server.uri()+route);
                    stopped.await();
                } finally { try { Runtime.getRuntime().removeShutdownHook(shutdown); } catch (IllegalStateException stopping) { /* VM shutdown owns hook. */ } }
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (Exception failed) { System.err.println("Unable to start local query API; check the snapshot, limits and port."); System.exit(1); }
    }
    static KnowledgeGraph loadSnapshot(Path path)throws java.io.IOException {
        long bytes=Math.max(1,Files.size(path));
        return FileGraphStore.readSnapshot(path,bytes,new GraphJson.StreamLimits(bytes,128,bytes));
    }
    private QueryApiMain() {}
}
