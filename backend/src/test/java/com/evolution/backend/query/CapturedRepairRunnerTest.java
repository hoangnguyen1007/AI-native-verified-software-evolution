package com.evolution.backend.query;

import com.evolution.analysis.graph.InMemoryGraphStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

final class CapturedRepairRunnerTest {

    @TempDir
    Path temp;

    @Test
    void failsClosedWhenRunnerPropertiesAreAbsent() {
        String prevEnabled = System.getProperty("evo.captured.repair.runner.enabled");
        String prevM2 = System.getProperty("evo.captured.repair.runner.m2");
        String prevGradleHome = System.getProperty("evo.captured.repair.runner.gradleHome");
        try {
            System.clearProperty("evo.captured.repair.runner.enabled");
            System.clearProperty("evo.captured.repair.runner.m2");
            System.clearProperty("evo.captured.repair.runner.gradleHome");
            System.clearProperty("evo.captured.repair.runner.mavenZip");
            System.clearProperty("evo.captured.repair.runner.gradleZip");
            assertTrue(CapturedRepairRunner.fromSystemProperties().isEmpty());
        } finally {
            restore("evo.captured.repair.runner.enabled", prevEnabled);
            restore("evo.captured.repair.runner.m2", prevM2);
            restore("evo.captured.repair.runner.gradleHome", prevGradleHome);
        }
    }

    @Test
    void extractsControlResourcesAndBuildsGradleCommand() throws Exception {
        Path controlsDir = temp.resolve("controls");
        CapturedRepairRunner.extractControls(controlsDir);
        for (String name : CapturedRepairRunner.CONTROL_RESOURCES) {
            assertTrue(Files.isRegularFile(controlsDir.resolve(name)), "Missing extracted control: " + name);
        }
        String dockerfile = Files.readString(controlsDir.resolve("Dockerfile"));
        assertTrue(dockerfile.contains("COPY tool-dist/ /tmp/tool-dist/"));
        String guestInit = Files.readString(controlsDir.resolve("guest-init.sh"));
        assertTrue(guestInit.contains("EVO_BUILD_TOOL"));
        assertTrue(guestInit.contains("gradle"));

        var cfg = new CapturedRepairRunner.Config(
                "python3",
                Optional.empty(),
                "gradle",
                Optional.empty(),
                Optional.empty(),
                Optional.of(temp.resolve("gradle-8.10-bin.zip")),
                Optional.of(temp.resolve("gradle-home")),
                Optional.of(""),
                true,
                temp.resolve("work"),
                Duration.ofMinutes(10));
        var cmd = CapturedRepairRunner.buildCommand(
                controlsDir.resolve("run.py"),
                temp.resolve("repo"),
                temp.resolve("proposal.json"),
                temp.resolve("verification.json"),
                temp.resolve("out"),
                cfg);
        assertTrue(cmd.contains("--build-tool"));
        assertTrue(cmd.contains("gradle"));
        assertTrue(cmd.contains("--gradle-zip"));
        assertTrue(cmd.contains("--gradle-home"));
        assertTrue(cmd.contains("--skip-tests"));
        assertFalse(cmd.contains("--m2"));
    }

    @Test
    void serverRejectsRunIsolatedWhenRunnerIsNotConfigured() throws Exception {
        var api = new ArchitectureQueryApi(new InMemoryGraphStore(), new PublishedReports(), ApiLimits.defaults());
        var caps = api.handle("GET", "/api/v1", "application/json", new byte[0], () -> false);
        assertEquals(200, caps.status());
        String json = new String(caps.body());
        assertTrue(json.contains("\"isolatedRepairRunnerEnabled\":false"));
        assertTrue(json.contains("\"repairRecipeCount\":16"));
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
