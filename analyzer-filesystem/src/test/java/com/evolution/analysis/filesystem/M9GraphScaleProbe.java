package com.evolution.analysis.filesystem;

import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/** Runs the registered 33,000-row generator in a fresh process against an explicit store. */
public final class M9GraphScaleProbe {
    private M9GraphScaleProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && "--evolution".equals(args[0])) {
            publishEvolutionPair(Path.of(args[1]).toAbsolutePath().normalize());
            return;
        }
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

    private static void publishEvolutionPair(Path output) throws Exception {
        Files.createDirectory(output);
        var store = new PartitionedGraphStore(output.resolve("graph-pages"));
        var left = FileSnapshotComparisonTest.graph(false, 33_000);
        store.save(left, () -> false);
        var right = FileSnapshotComparisonTest.graph(true, 33_000);
        store.save(right, () -> false);
        Files.writeString(output.resolve("request.json"), CanonicalJson.write(Map.of(
                "schema", "architecture.evolution.request:v1",
                "comparison", FileSnapshotComparisonTest.request(left, right))), StandardOpenOption.CREATE_NEW);
    }
}
