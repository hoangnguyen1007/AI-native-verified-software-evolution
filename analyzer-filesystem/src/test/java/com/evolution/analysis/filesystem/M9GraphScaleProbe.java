package com.evolution.analysis.filesystem;

import java.nio.file.Files;
import java.nio.file.Path;

/** Runs the registered 33,000-row generator in a fresh process against an explicit store. */
public final class M9GraphScaleProbe {
    private M9GraphScaleProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected an explicit graph store directory");
        var test = new PartitionedGraphStoreTest();
        test.directory = Path.of(args[0]).toAbsolutePath().normalize();
        if (!Files.isDirectory(test.directory)) throw new IllegalArgumentException("Graph store directory unavailable");
        test.indexSpansMultipleDirectoryLevelsWithoutARepositoryRowCeiling();
        try (var files = Files.list(test.directory)) {
            var roots = files.filter(file -> file.getFileName().toString().matches("[a-f0-9]{64}\\.partitions\\.json")).toList();
            if (roots.size() != 1) throw new IllegalStateException("Expected one authenticated graph root");
            System.out.println(roots.getFirst().getFileName());
        }
    }
}
