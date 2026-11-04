package com.evolution.backend.query;

import com.evolution.analysis.graph.GraphSnapshotCodec;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.filesystem.FileGraphStore;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class QueryApiMainTest {
    @TempDir Path directory;
    @Test void partitionArchiveStartsWithoutALegacySnapshotFile()throws Exception{
        var fixture=new QueryFixture().node("a").node("b").edge("a","b");var graph=fixture.graph();
        new com.evolution.analysis.filesystem.PartitionedGraphStore(directory).save(graph,()->false);
        var process=launch("--partitions",directory.toString(),graph.identity().value(),"0");
        try{
            var first=CompletableFuture.supplyAsync(()->{try{return process.inputReader().readLine();}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}}).get(10,TimeUnit.SECONDS);
            assertNotNull(first);assertTrue(first.contains("/graph-pages/"),first);var uri=URI.create(first.substring("Architecture query API: ".length()));
            var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}" )).timeout(Duration.ofSeconds(5)).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode());assertTrue(response.body().contains("RAW_CONTEXT_FACTS"));assertTrue(response.body().contains(fixture.context));
        }finally{process.destroyForcibly();assertTrue(process.waitFor(3,TimeUnit.SECONDS));}
    }
    @Test void localSnapshotLoaderAcceptsMoreThanTheFormer64MbCeiling()throws Exception{
        var original=new QueryFixture().node("a").graph();
        var evidence=new java.util.ArrayList<>(original.evidence());
        String payload="x".repeat(1_000_000);
        for(int i=0;i<65;i++)evidence.add(GraphEvidence.capture("fixture.scale",java.util.Map.of("row",i,"text",payload)));
        var graph=new KnowledgeGraph(original.snapshot(),original.contexts(),original.nodes(),original.edges(),evidence);
        long budget=Files.getFileStore(directory).getUsableSpace();
        new FileGraphStore(directory,budget,new GraphJson.StreamLimits(budget,128,budget)).save(graph);
        var path=directory.resolve(graph.identity().value().substring(7)+".graph.json");
        assertTrue(Files.size(path)>64_000_000);
        var restored=QueryApiMain.loadSnapshot(path);
        assertEquals(graph.identity(),restored.identity());
        assertEquals(graph.evidence().size(),restored.evidence().size());
    }
    Process launch(String... args) throws Exception {
        String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        var command=new java.util.ArrayList<>(java.util.List.of(executable,"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),QueryApiMain.class.getName()));
        command.addAll(java.util.List.of(args));
        return new ProcessBuilder(command).redirectErrorStream(true).start();
    }
    @Test void suppliedCanonicalSnapshotStartsAUsableLocalApi() throws Exception {
        var fixture=new QueryFixture().node("a"); var path=directory.resolve("graph.json");
        Files.writeString(path,new GraphSnapshotCodec().encode(fixture.graph()));
        var process=launch(path.toString(),"0");
        try {
            var first=CompletableFuture.supplyAsync(() -> {
                try { return process.inputReader().readLine(); } catch(java.io.IOException failed) { throw new java.io.UncheckedIOException(failed); }
            }).get(5,TimeUnit.SECONDS);
            assertNotNull(first); assertTrue(first.startsWith("Architecture query API: http://127.0.0.1:"),first);
            var url=URI.create(first.substring("Architecture query API: ".length()));
            assertTrue(url.getPath().endsWith(fixture.graph().identity().value()));
            var result=HttpClient.newHttpClient().send(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(3)).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,result.statusCode()); assertTrue(result.body().contains(fixture.context));
        } finally { process.destroyForcibly(); assertTrue(process.waitFor(3,TimeUnit.SECONDS)); }
    }
    @Test void invalidUsageAndInvalidSnapshotsExitNonzeroWithoutHostDetails() throws Exception {
        for(String[] args : new String[][]{new String[0],new String[]{directory.resolve("private-missing.json").toString()}}) {
            var process=launch(args);
            try {
                assertTrue(process.waitFor(5,TimeUnit.SECONDS)); assertEquals(args.length==0 ? 2 : 1,process.exitValue());
                String output=new String(process.getInputStream().readNBytes(2048),java.nio.charset.StandardCharsets.UTF_8);
                assertFalse(output.contains(directory.toString())); assertFalse(output.contains("Exception"));
            } finally { if(process.isAlive()) { process.destroyForcibly(); process.waitFor(3,TimeUnit.SECONDS); } }
        }
    }
}
