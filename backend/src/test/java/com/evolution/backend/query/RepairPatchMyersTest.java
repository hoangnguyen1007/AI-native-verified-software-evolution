package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class RepairPatchMyersTest {
    @TempDir Path temp;

    @Test
    void myersMultiHunkDiffEmitsCompactSeparateHunksAndAppliesCleanlyInGit() throws Exception {
        var beforeLines = new ArrayList<String>();
        for (int i = 1; i <= 60; i++) beforeLines.add("    int field" + i + " = " + i + ";");
        String before = "class Large {\n" + String.join("\n", beforeLines) + "\n}\n";

        var afterLines = new ArrayList<>(beforeLines);
        afterLines.set(1, "    int field2 = 200;");   // line 3
        afterLines.set(54, "    int field55 = 5500;"); // line 56
        String after = "class Large {\n" + String.join("\n", afterLines) + "\n}\n";

        String patch = RepairPatch.file("src/Large.java", before, after);
        long hunkHeaders = patch.lines().filter(l -> l.startsWith("@@ ")).count();
        assertEquals(2, hunkHeaders, "Expected 2 compact Myers hunks, not 1 monolithic hunk:\n" + patch);
        assertFalse(patch.contains("field25"), "Unchanged middle lines must not bloat the diff");

        var roundTrip = RepairPatch.applyAll(Map.of("src/Large.java", before), patch);
        assertEquals(after, roundTrip.get("src/Large.java"));

        Path file = temp.resolve("src/Large.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, before, StandardCharsets.UTF_8);
        Path patchFile = Files.writeString(temp.resolve("multi.patch"), patch, StandardCharsets.UTF_8);
        assertEquals(0, runGitApply(patchFile, "--check"));
        assertEquals(0, runGitApply(patchFile));
        assertEquals(after, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void createAndDeletePatchesRoundTripAndApplyInGit() throws Exception {
        String existing = "package demo;\nclass Old {}\n";
        String created = "package demo;\npublic interface NewPort {\n    void run();\n}\n";

        String combinedPatch = RepairPatch.createFile("src/NewPort.java", created)
                + RepairPatch.deleteFile("src/Old.java", existing);

        var detailed = RepairPatch.applyAllDetailed(Map.of("src/Old.java", existing), combinedPatch);
        assertEquals(Set.of("src/NewPort.java"), detailed.created());
        assertEquals(Set.of("src/Old.java"), detailed.deleted());
        assertEquals(created, detailed.updated().get("src/NewPort.java"));
        assertFalse(detailed.updated().containsKey("src/Old.java"));

        Path oldFile = temp.resolve("src/Old.java");
        Files.createDirectories(oldFile.getParent());
        Files.writeString(oldFile, existing, StandardCharsets.UTF_8);
        Path patchFile = Files.writeString(temp.resolve("cd.patch"), combinedPatch, StandardCharsets.UTF_8);
        assertEquals(0, runGitApply(patchFile, "--check"));
        assertEquals(0, runGitApply(patchFile));
        assertFalse(Files.exists(oldFile));
        assertEquals(created, Files.readString(temp.resolve("src/NewPort.java"), StandardCharsets.UTF_8));
    }

    @Test
    void fourTierAnchorsHandleLineWindowShiftOuterWhitespaceAndEmitNearestLines() {
        String source = """
                class Demo {
                    int a = 1;
                    int b = 2;
                    int c = 3;
                    int d = 4;
                    String s = "two  spaces";
                    int target = 10 + 20;
                }
                """;
        var digest = ContentDigest.sha256Utf8(source);

        // Tier 2: startLine off by 4 lines still resolves unambiguously in +/-15 window
        var shifted = new RepairChatModel.Change(1, digest, "    int target = 10 + 20;", "    int target = 30;", 3);
        var e2 = RepairAnchors.locate(source, shifted);
        assertEquals("    int target = 10 + 20;", source.substring(e2.start(), e2.end()));

        // Tier 4: extra outer spacing outside literals matches cleanly
        var outerWs = new RepairChatModel.Change(1, digest, "  int   target  =  10 + 20;", "  int target = 99;", null);
        var e4 = RepairAnchors.locate(source, outerWs);
        assertEquals("    int target = 10 + 20;", source.substring(e4.start(), e4.end()));

        // String literal interior spaces are NEVER normalized
        var literalDiff = new RepairChatModel.Change(1, digest, "  String s = \"two spaces\";", "  String s = \"x\";", null);
        var ex = assertThrows(RepairAnchors.AnchorFailure.class, () -> RepairAnchors.locate(source, literalDiff));
        assertTrue(ex.nearestLines().contains("two  spaces"), "AnchorFailure must provide nearest lines for self-healing");
    }

    private int runGitApply(Path diff, String... flags) throws Exception {
        var cmd = new ArrayList<>(List.of("git", "-c", "core.autocrlf=false", "apply", "--whitespace=nowarn"));
        cmd.addAll(List.of(flags));
        cmd.add(diff.toString());
        var pb = new ProcessBuilder(cmd).directory(temp.toFile()).redirectErrorStream(true);
        if (temp.getParent() != null) {
            pb.environment().put("GIT_CEILING_DIRECTORIES", temp.getParent().toAbsolutePath().toString());
        }
        var p = pb.start();
        assertTrue(p.waitFor(10, TimeUnit.SECONDS));
        return p.exitValue();
    }
}
