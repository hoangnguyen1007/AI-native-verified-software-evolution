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

        var envelope = GraphJson.object(GraphJson.read(out.toString(), GraphJson.Limits.defaults()));
        var data = GraphJson.object(envelope.get("data"));
        assertEquals("FAST_ARCHITECTURE", data.get("analysisMode"));
        assertEquals(Boolean.TRUE, data.get("snapshotSelected"));
    }
}
