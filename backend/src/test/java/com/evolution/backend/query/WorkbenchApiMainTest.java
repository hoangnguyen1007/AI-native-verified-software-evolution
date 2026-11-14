package com.evolution.backend.query;

import com.evolution.analysis.graph.GraphJson;
import com.evolution.backend.analysis.WorkbenchApiMain;
import java.io.File;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.JarOutputStream;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WorkbenchApiMainTest {
    @TempDir Path directory;
    private Process launch(String... args) throws Exception {
        var executable = Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        // Surefire's trailing separator would add this test's temporary cwd to a real launch.
        var classpath = System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        classpath = String.join(File.pathSeparator, classpath.split(Pattern.quote(File.pathSeparator)));
        var command = new ArrayList<>(List.of(executable,"-Xmx512m","-cp",classpath,WorkbenchApiMain.class.getName()));
        command.addAll(List.of(args)); return new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
    }
    @Test void verifiedUnchangedInputReusesPublishedResultAcrossRestart() throws Exception {
        var source = Files.createDirectory(directory.resolve("source"));
        var store = Files.createDirectory(directory.resolve("graphs"));
        var cache = Files.createDirectory(directory.resolve("cache"));
        var policy = Files.writeString(directory.resolve("policy.yaml"), LocalAnalysisRunnerTest.POLICY);
        var controller = source.resolve("src/main/java/api/Controller.java");
        var dependency = source.resolve("src/main/java/data/Repository.java");
        Files.createDirectories(controller.getParent());
        Files.createDirectories(dependency.getParent());
        Files.writeString(controller, "package api; public class Controller { private data.Repository repository; }\n");
        Files.writeString(dependency, "package data; public class Repository { }\n");
        var args = new String[]{source.toString(), "urn:test:reused-launcher", System.getProperty("java.home"), "17",
                cache.toString(), store.toString(), "0", policy.toString()};
        var client = HttpClient.newHttpClient();
        Map<String,Object> first;
        Map<String,Object> firstGaps;
        try (var server = start(args)) {
            first = runJob(client, server.base(), "first", false);
            assertEquals("PARTIAL", first.get("state"), first::toString);
            assertTrue(stages(first).containsAll(List.of("ANALYSIS", "GRAPH", "PUBLICATION")));
            assertFalse(GraphJson.array(GraphJson.object(first.get("result")).get("reports")).isEmpty());
            firstGaps = gapPage(client, server.base(), first);
            var warm = runJob(client, server.base(), "warm", false);
            assertReused(first, warm);
            System.out.println("Authored 2-file Workbench fixture, job ms: initial=" + first.get("elapsedMillis")
                    + ", unchanged=" + warm.get("elapsedMillis"));
        }
        try (var server = start(args)) {
            var second = runJob(client, server.base(), "restart", false);
            assertReused(first, second);
            var reusedGaps = gapPage(client, server.base(), second);
            assertEquals(firstGaps.get("items"), reusedGaps.get("items"));
            assertEquals(firstGaps.get("total"), reusedGaps.get("total"));
            var forced = runJob(client, server.base(), "force", true);
            assertEquals(true, forced.get("force"));
            assertNull(forced.get("reuse"));
            assertEquals("BYPASSED", forced.get("reuseDecision"));
            assertTrue(stages(forced).contains("ANALYSIS"));
            assertEquals(first.get("result"), forced.get("result"));

            var graph = GraphJson.string(GraphJson.object(first.get("result")).get("graph"));
            Files.move(store.resolve(graph.substring(7) + ".graph.json"), directory.resolve("withheld.graph.json"));
            var repaired = runJob(client, server.base(), "missing-publication", false);
            assertNull(repaired.get("reuse"));
            assertEquals("RESULT_UNAVAILABLE", repaired.get("reuseDecision"));
            assertTrue(stages(repaired).contains("ANALYSIS"));
            assertEquals(first.get("result"), repaired.get("result"));

            // Change content without changing either cheap metadata signal.
            var originalTime = Files.getLastModifiedTime(dependency);
            long originalSize = Files.size(dependency);
            Files.writeString(dependency, "package data; public class Repository {} \n");
            Files.setLastModifiedTime(dependency, originalTime);
            assertEquals(originalSize, Files.size(dependency));
            var changed = runJob(client, server.base(), "changed", false);
            assertNull(changed.get("reuse"));
            assertEquals("NO_MATCH", changed.get("reuseDecision"));
            assertTrue(stages(changed).contains("ANALYSIS"));
            assertNotEquals(GraphJson.object(first.get("result")).get("snapshot"),
                    GraphJson.object(changed.get("result")).get("snapshot"));
            System.out.println("Authored 2-file Workbench fixture, job ms: restart=" + second.get("elapsedMillis")
                    + ", forced=" + forced.get("elapsedMillis") + ", changed=" + changed.get("elapsedMillis"));
        }
    }
    @Test void changedDependencyBytesInvalidateAnOtherwiseIdenticalMavenCapture() throws Exception {
        var source = Files.createDirectory(directory.resolve("source"));
        var store = Files.createDirectory(directory.resolve("graphs"));
        var cache = Files.createDirectory(directory.resolve("cache"));
        var javaSources = Files.createDirectories(source.resolve("src/main/java/sample"));
        Files.writeString(javaSources.resolve("App.java"), "package sample; public class App {}\n");
        Files.writeString(source.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>sample</groupId><artifactId>app</artifactId><version>1</version>
                <properties><maven.compiler.release>17</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                <dependencies><dependency><groupId>sample</groupId><artifactId>library</artifactId><version>1</version></dependency></dependencies></project>
                """);
        var library = Files.createDirectories(cache.resolve("sample/library/1"));
        Files.writeString(library.resolve("library-1.pom"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>sample</groupId><artifactId>library</artifactId><version>1</version></project>
                """);
        var jar = library.resolve("library-1.jar");
        writeLibrary(jar, (byte) 1);
        var args = new String[]{source.toString(), "urn:test:reuse-classpath", System.getProperty("java.home"), "17",
                cache.toString(), store.toString(), "0"};
        try (var server = start(args)) {
            var client = HttpClient.newHttpClient();
            var first = runJob(client, server.base(), "first", false);
            assertReused(first, runJob(client, server.base(), "warm", false));
            long originalSize = Files.size(jar);
            var originalTime = Files.getLastModifiedTime(jar);
            writeLibrary(jar, (byte) 2);
            Files.setLastModifiedTime(jar, originalTime);
            assertEquals(originalSize, Files.size(jar));
            var changed = runJob(client, server.base(), "changed", false);
            assertNull(changed.get("reuse"));
            assertEquals("NO_MATCH", changed.get("reuseDecision"));
            assertTrue(stages(changed).contains("ANALYSIS"));
            var before = GraphJson.object(first.get("result"));
            var after = GraphJson.object(changed.get("result"));
            assertEquals(before.get("snapshot"), after.get("snapshot"));
            assertNotEquals(before.get("analyses"), after.get("analyses"));
        }
    }
    private static void writeLibrary(Path path, byte value) throws Exception {
        var entry = new ZipEntry("sample/metadata.bin");
        entry.setTime(0);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(1);
        var checksum = new CRC32();
        checksum.update(value);
        entry.setCrc(checksum.getValue());
        try (var jar = new JarOutputStream(Files.newOutputStream(path))) {
            jar.putNextEntry(entry);
            jar.write(value);
            jar.closeEntry();
        }
    }
    private static void assertReused(Map<String,Object> first, Map<String,Object> next) {
        assertNotEquals(first.get("id"), next.get("id"));
        assertEquals(first.get("result"), next.get("result"));
        assertEquals(first.get("state"), next.get("state"));
        assertEquals("REUSED", next.get("reuseDecision"));
        assertEquals(List.of("QUEUED", "ACQUISITION", "PLATFORM", "REUSED", "FINISHED"), stages(next), next::toString);
        var reuse = GraphJson.object(next.get("reuse"));
        assertEquals(first.get("id"), reuse.get("sourceJob"));
        assertEquals(first.get("finishedAt"), reuse.get("sourceFinishedAt"));
        assertNotNull(reuse.get("verifiedAt"));
    }
    private static List<String> stages(Map<String,Object> job) {
        return GraphJson.array(job.get("events")).stream().map(row -> GraphJson.string(GraphJson.object(row).get("stage"))).toList();
    }
    private static Map<String,Object> gapPage(HttpClient client, URI base, Map<String,Object> job) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create(base + "/analysis/jobs/" + job.get("id") + "/gaps"))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"size\":50}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return data(response.body());
    }
    private static Map<String,Object> runJob(HttpClient client, URI base, String key, boolean force) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create(base + "/analysis/jobs")).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(
                        "{\"setup\":\"local\",\"idempotencyKey\":\"" + key + "\",\"force\":" + force + "}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(202, response.statusCode());
        String id = GraphJson.string(data(response.body()).get("id"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        do {
            var status = client.send(HttpRequest.newBuilder(URI.create(base + "/analysis/jobs/" + id)).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, status.statusCode());
            var job = data(status.body());
            if (Set.of("SUCCEEDED", "PARTIAL", "EMPTY", "FAILED", "CANCELLED", "TIMED_OUT").contains(job.get("state"))) return job;
            Thread.sleep(30);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Workbench job did not finish");
    }
    private record RunningServer(Process process, URI base) implements AutoCloseable {
        @Override public void close() throws InterruptedException {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        }
    }
    private RunningServer start(String[] args) throws Exception {
        var process = launch(args);
        try {
            var first = CompletableFuture.supplyAsync(() -> {
                try { return process.inputReader().readLine(); }
                catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            }).get(10, TimeUnit.SECONDS);
            var prefix = "Architecture workbench API: ";
            assertNotNull(first);
            assertTrue(first.startsWith(prefix + "http://127.0.0.1:"), first);
            return new RunningServer(process, URI.create(first.substring(prefix.length())));
        } catch (Exception | AssertionError failure) {
            process.destroyForcibly();
            throw failure;
        }
    }
    @Test void actualLauncherAcceptsAJobAndPersistsItsVerifiedGraphWithoutATargetBuild() throws Exception {
        var source = Files.createDirectory(directory.resolve("source")); var store = Files.createDirectory(directory.resolve("graphs")); var cache = Files.createDirectory(directory.resolve("cache"));
        Files.writeString(source.resolve("Example.java"),"public class Example { public void work() {} }\n");
        Files.writeString(source.resolve("gradlew.bat"),"echo forbidden > target-executed.txt\r\n");
        var args = new String[]{source.toString(),"urn:test:launcher",System.getProperty("java.home"),"21",cache.toString(),store.toString(),"0"};
        var process = launch(args);
        String graph;
        String pageResult;
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
            graph = GraphJson.string(GraphJson.object(job.get("result")).get("graph")); assertTrue(Files.isRegularFile(store.resolve(graph.substring(7)+".graph.json")));
            var index = store.resolve("graph-pages").resolve(graph.substring(7)+".partitions.json");
            assertFalse(Files.exists(index), "The result must be available before the optional raw page index");
            assertFalse(Files.exists(source.resolve("target-executed.txt"))); assertFalse(Files.exists(directory.resolve("target-executed.txt")));
            var discovery = client.send(HttpRequest.newBuilder(URI.create(base+"/graphs/"+graph)).GET().build(),HttpResponse.BodyHandlers.ofString()); assertEquals(200,discovery.statusCode());
            assertFalse(Files.exists(index));
            pageResult = readPreparedRawPage(client, base, graph);
            assertEquals("RAW_CONTEXT_FACTS",data(pageResult).get("scope"));
            assertFalse(GraphJson.array(GraphJson.object(data(pageResult).get("page")).get("items")).isEmpty());
            assertTrue(Files.isRegularFile(index));
        } finally { process.destroyForcibly(); assertTrue(process.waitFor(5,TimeUnit.SECONDS)); }
        try (var restarted = start(args)) {
            assertEquals(pageResult, readPreparedRawPage(HttpClient.newHttpClient(), restarted.base(), graph));
        }
    }
    private static String readPreparedRawPage(HttpClient client, URI base, String graph) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base+"/graph-pages/"+graph+"/partitions"))
                .timeout(Duration.ofSeconds(5)).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"size\":1}")).build();
        long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        boolean wasPreparing = false;
        while (System.nanoTime()<deadline) {
            var response = client.send(request,HttpResponse.BodyHandlers.ofString());
            if (response.statusCode()==200) {
                assertTrue(wasPreparing);
                return response.body();
            }
            assertEquals(503,response.statusCode(),response.body());
            var error = GraphJson.object(GraphJson.object(GraphJson.read(response.body(),GraphJson.Limits.defaults())).get("error"));
            assertEquals("PARTITION_INDEX_PREPARING",error.get("code"));
            wasPreparing = true;
            Thread.sleep(30);
        }
        throw new AssertionError("The background index did not finish");
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
