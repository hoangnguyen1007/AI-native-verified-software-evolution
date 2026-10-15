package com.evolution.backend.query;

import com.evolution.analysis.graph.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.CountDownLatch;

/** Local demo: explicitly supplied canonical snapshot; no target code or target build runs. */
public final class QueryApiMain {
    public static void main(String[] args) {
        if (args.length<1 || args.length>2) { System.err.println("Usage: QueryApiMain <graph-snapshot.json> [port]"); System.exit(2); }
        try {
            var path=Path.of(args[0]);
            if (!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) throw new IllegalArgumentException();
            // Bound the actual read even when size changes after inspection.
            byte[] bytes;
            try (var input=Files.newInputStream(path)) { bytes=input.readNBytes(64_000_001); }
            if (bytes.length>64_000_000) throw new IllegalArgumentException();
            String json=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            var graph=new GraphSnapshotCodec().decode(json,GraphJson.Limits.defaults());
            var store=new InMemoryGraphStore(); store.save(graph);
            try (var server=new ArchitectureHttpServer(new ArchitectureQueryApi(store),args.length==2 ? Integer.parseInt(args[1]) : 8080)) {
                var stopped=new CountDownLatch(1);
                var shutdown=new Thread(() -> { server.close(); stopped.countDown(); },"architecture-http-shutdown");
                Runtime.getRuntime().addShutdownHook(shutdown);
                try {
                    server.start();
                    System.out.println("Architecture query API: "+server.uri()+"/api/v1/graphs/"+graph.identity().value());
                    stopped.await();
                } finally { try { Runtime.getRuntime().removeShutdownHook(shutdown); } catch (IllegalStateException stopping) { /* VM shutdown owns hook. */ } }
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (Exception failed) { System.err.println("Unable to start local query API; check the snapshot, limits and port."); System.exit(1); }
    }
    private QueryApiMain() {}
}
