package com.evolution.backend.query;

import com.evolution.analysis.graph.GraphJson;
import com.evolution.backend.analysis.WorkbenchApiMain;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WorkbenchApiMainTest {
    @TempDir Path directory;
    private Process launch(String... args) throws Exception {
        var executable = Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        var command = new ArrayList<>(List.of(executable,"-Xmx512m","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),WorkbenchApiMain.class.getName()));
        command.addAll(List.of(args)); return new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
    }
    @Test void actualLauncherAcceptsAJobAndPersistsItsVerifiedGraphWithoutATargetBuild() throws Exception {
        var source = Files.createDirectory(directory.resolve("source")); var store = Files.createDirectory(directory.resolve("graphs")); var cache = Files.createDirectory(directory.resolve("cache"));
        Files.writeString(source.resolve("Example.java"),"public class Example { public void work() {} }\n");
        Files.writeString(source.resolve("gradlew.bat"),"echo forbidden > target-executed.txt\r\n");
        var process = launch(source.toString(),"urn:test:launcher",System.getProperty("java.home"),"21",cache.toString(),store.toString(),"0");
        try {
            var first = CompletableFuture.supplyAsync(() -> { try { return process.inputReader().readLine(); } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); } }).get(10,TimeUnit.SECONDS);
            assertNotNull(first); assertTrue(first.startsWith("Architecture workbench API: http://127.0.0.1:"),first);
            var base = URI.create(first.substring("Architecture workbench API: ".length())); var client = HttpClient.newHttpClient();
            var created = client.send(HttpRequest.newBuilder(URI.create(base+"/analysis/jobs")).timeout(Duration.ofSeconds(5)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"setup\":\"local\",\"idempotencyKey\":\"once\"}")).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(202,created.statusCode()); var job = data(created.body()); String id = GraphJson.string(job.get("id")); long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
            do {
                var response = client.send(HttpRequest.newBuilder(URI.create(base+"/analysis/jobs/"+id)).timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(200,response.statusCode()); job = data(response.body());
                if (Set.of("SUCCEEDED","PARTIAL","EMPTY","FAILED","CANCELLED","TIMED_OUT").contains(job.get("state"))) break;
                Thread.sleep(30);
            } while (System.nanoTime()<deadline);
            assertTrue(Set.of("SUCCEEDED","PARTIAL").contains(job.get("state")),job.toString());
            String graph = GraphJson.string(GraphJson.object(job.get("result")).get("graph")); assertTrue(Files.isRegularFile(store.resolve(graph.substring(7)+".graph.json")));
            assertFalse(Files.exists(source.resolve("target-executed.txt"))); assertFalse(Files.exists(directory.resolve("target-executed.txt")));
            var discovery = client.send(HttpRequest.newBuilder(URI.create(base+"/graphs/"+graph)).GET().build(),HttpResponse.BodyHandlers.ofString()); assertEquals(200,discovery.statusCode());
        } finally { process.destroyForcibly(); assertTrue(process.waitFor(5,TimeUnit.SECONDS)); }
    }
    private static Map<String,Object> data(String body) { return GraphJson.object(GraphJson.object(GraphJson.read(body,GraphJson.Limits.defaults())).get("data")); }
    @Test void invalidUsageAndPathsExitWithSanitizedDiagnostics() throws Exception {
        for (var args : List.of(new String[0],new String[]{directory.resolve("private-missing").toString(),"urn:test:launcher",System.getProperty("java.home"),"21",directory.toString(),directory.toString()})) {
            var process = launch(args);
            try {
                assertTrue(process.waitFor(10,TimeUnit.SECONDS)); assertEquals(args.length==0 ? 2 : 1,process.exitValue());
                var output = new String(process.getInputStream().readNBytes(2048),java.nio.charset.StandardCharsets.UTF_8); assertFalse(output.contains(directory.toString())); assertFalse(output.contains("Exception"));
            } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5,TimeUnit.SECONDS); } }
        }
    }
}
