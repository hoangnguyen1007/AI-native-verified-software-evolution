package com.evolution.cli;

import com.evolution.analysis.filesystem.FileGraphStore;
import com.evolution.analysis.graph.GraphJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InsightsAndFastScanCliTest {

    @Test
    void insightsAndMarkdownExportProduceDeterministicCycleAndHotspotReports(@TempDir Path temp) throws Exception {
        var fixture = new CliFixture()
                .node("Controller")
                .node("ServiceA")
                .node("ServiceB")
                .edge("Controller", "ServiceA")
                .edge("ServiceA", "ServiceB")
                .edge("ServiceB", "ServiceA");
        Path graphFile = temp.resolve("cycle.graph.json");
        new FileGraphStore(temp, 16_000_000L, new GraphJson.StreamLimits(16_000_000L, 128, 16_000_000L)).save(fixture.graph());
        Path saved = temp.resolve(fixture.graph().identity().value().substring(7) + ".graph.json");
        Files.copy(saved, graphFile);

        Path mdOut = temp.resolve("advisor-report.md");
        var out = new StringWriter();
        var err = new StringWriter();
        var app = new EvoApp(temp, new StringReader(""), new PrintWriter(out), new PrintWriter(err), false, Map.of());

        int code = app.execute("insights", "-g", graphFile.toString(), "-c", fixture.context,
                "-s", "entity", "--top", "10", "--report-md", mdOut.toString(), "--json");
        assertEquals(0, code, () -> "stderr: " + err);
        assertTrue(Files.isRegularFile(mdOut));
        String md = Files.readString(mdOut);
        assertTrue(md.contains("# Architecture Intelligence & Refactoring Executive Report"));
        assertTrue(md.contains("Tangle #1"));

        var parsed = GraphJson.object(GraphJson.read(out.toString(), GraphJson.Limits.defaults()));
        var data = GraphJson.object(parsed.get("data"));
        var summary = GraphJson.object(data.get("summary"));
        assertEquals(1, ((Number) summary.get("tangleCount")).intValue());
        assertEquals(2, ((Number) summary.get("tangledEntityCount")).intValue());

        Path exportedMd = temp.resolve("exported.md");
        var out2 = new StringWriter();
        var err2 = new StringWriter();
        var app2 = new EvoApp(temp, new StringReader(""), new PrintWriter(out2), new PrintWriter(err2), false, Map.of());
        int exportCode = app2.execute("export", "markdown", "-g", graphFile.toString(), "-c", fixture.context,
                "-s", "entity", "-o", exportedMd.toString(), "--json");
        assertEquals(0, exportCode, () -> "stderr: " + err2);
        assertEquals(md, Files.readString(exportedMd));
    }

    @Test
    void scanFastModeEmitsTelemetryToStderrWithoutPollutingJsonStdout(@TempDir Path temp) throws Exception {
        Path repo = Files.createDirectory(temp.resolve("repo"));
        Path ws = Files.createDirectory(temp.resolve("ws"));
        Path cache = Files.createDirectory(temp.resolve("m2"));
        Files.writeString(repo.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>demo</groupId>
                  <artifactId>cli-fast</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                  </properties>
                </project>
                """);
        Path src = Files.createDirectories(repo.resolve("src/main/java/demo"));
        Files.writeString(src.resolve("Api.java"), """
                package demo;
                public class Api {
                    private final Core core = new Core();
                    public int run() { return core.compute(); }
                }
                """);
        Files.writeString(src.resolve("Core.java"), """
                package demo;
                public class Core {
                    public int compute() { int x = 42; return x; }
                }
                """);

        var out = new StringWriter();
        var err = new StringWriter();
        var app = new EvoApp(ws, new StringReader(""), new PrintWriter(out), new PrintWriter(err), false, Map.of());
        int exit = app.execute("scan", repo.toString(), "-w", ws.toString(),
                "--maven-cache", cache.toString(), "--offline", "--build", "off",
                "--mode", "fast", "--telemetry", "--json");
        assertTrue(exit == 0 || exit == 3, () -> "exit=" + exit + " err=" + err);
        assertTrue(err.toString().contains("[evo] Telemetry (FAST_ARCHITECTURE):"), () -> "stderr=" + err);
        assertTrue(err.toString().contains("[evo] Cache: javaHits="), () -> "stderr=" + err);

        var envelope = GraphJson.object(GraphJson.read(out.toString(), GraphJson.Limits.defaults()));
        var data = GraphJson.object(envelope.get("data"));
        assertEquals("FAST_ARCHITECTURE", data.get("analysisMode"));
        assertEquals(Boolean.TRUE, data.get("snapshotSelected"));
    }

    @Test
    void scanFastFlagSpringSatFlagAndCacheHitsWorkEndToEnd(@TempDir Path temp) throws Exception {
        Path repo = Files.createDirectory(temp.resolve("spring-cli-repo"));
        Path ws = Files.createDirectory(temp.resolve("spring-cli-ws"));
        Path cache = Files.createDirectory(temp.resolve("spring-cli-m2"));
        Files.writeString(repo.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>demo</groupId>
                  <artifactId>cli-sat</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                  </properties>
                </project>
                """);
        Files.createDirectories(repo.resolve("src/main/java/demo"));
        Files.createDirectories(repo.resolve("src/main/resources"));
        Files.writeString(repo.resolve("src/main/resources/application.yml"),
                "spring:\n  profiles:\n    active: dev\n");
        Files.writeString(repo.resolve("src/main/java/demo/App.java"), """
                package demo;
                import org.springframework.boot.autoconfigure.SpringBootApplication;
                @SpringBootApplication
                public class App {}
                """);
        Files.writeString(repo.resolve("src/main/java/demo/DevBean.java"), """
                package demo;
                import org.springframework.context.annotation.Profile;
                import org.springframework.stereotype.Service;
                @Service
                @Profile("dev")
                public class DevBean {}
                """);
        Files.writeString(repo.resolve("src/main/java/demo/ProdBean.java"), """
                package demo;
                import org.springframework.context.annotation.Profile;
                import org.springframework.stereotype.Service;
                @Service
                @Profile("prod")
                public class ProdBean {}
                """);

        // Conflicting --fast and --mode full is rejected with usage/input exit code 2
        var badOut = new StringWriter();
        var badErr = new StringWriter();
        int badExit = new EvoApp(ws, new StringReader(""), new PrintWriter(badOut), new PrintWriter(badErr), false, Map.of())
                .execute("scan", repo.toString(), "-w", ws.toString(), "--fast", "--mode", "full", "--offline", "--json");
        assertEquals(2, badExit);

        // Cold run with --fast --spring-sat --telemetry
        var out1 = new StringWriter();
        var err1 = new StringWriter();
        int exit1 = new EvoApp(ws, new StringReader(""), new PrintWriter(out1), new PrintWriter(err1), false, Map.of())
                .execute("scan", repo.toString(), "-w", ws.toString(), "--maven-cache", cache.toString(),
                        "--offline", "--build", "off", "--fast", "--spring-sat", "--telemetry", "--json");
        assertTrue(exit1 == 0 || exit1 == 3, () -> "exit=" + exit1 + " err=" + err1 + " out=" + out1);
        assertTrue(err1.toString().contains("[evo] Cache: javaHits=0 javaMisses=1"), () -> "err1=" + err1);
        var data1 = GraphJson.object(GraphJson.object(GraphJson.read(out1.toString(), GraphJson.Limits.defaults())).get("data"));
        assertEquals("FAST_ARCHITECTURE", data1.get("analysisMode"));
        assertEquals("AUTO_MULTI_CONFIG_SAT", data1.get("springSatMode"));
        var graph = new Workspace(ws).selectedGraph();
        assertTrue(graph.nodes().stream().anyMatch(n -> "TRUTH_REGION".equals(n.kind())),
                "Expected --spring-sat to emit TRUTH_REGION nodes");

        // Warm run hits Java incremental cache
        var out2 = new StringWriter();
        var err2 = new StringWriter();
        int exit2 = new EvoApp(ws, new StringReader(""), new PrintWriter(out2), new PrintWriter(err2), false, Map.of())
                .execute("scan", repo.toString(), "-w", ws.toString(), "--maven-cache", cache.toString(),
                        "--offline", "--build", "off", "--fast", "--spring-sat", "--telemetry", "--json");
        assertEquals(exit1, exit2);
        assertTrue(err2.toString().contains("[evo] Cache: javaHits=1 javaMisses=0"), () -> "err2=" + err2);
    }
}
