package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.*;
import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.ingestion.RepositoryInputDecoder;
import com.evolution.analysis.buildmodel.UniversalBuildIngestion;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AcquisitionCheckpointStoreTest {
    @TempDir Path temporary;

    @Test void capturedCompleteInputReplaysAfterRepositoryChanges() throws Exception {
        Path repository = Files.createDirectory(temporary.resolve("repository"));
        Files.writeString(repository.resolve("build.gradle"), "plugins { java }", StandardCharsets.UTF_8);
        Files.writeString(repository.resolve("A.java"), "class A {}", StandardCharsets.UTF_8);
        var request = request(10);
        var original = new FilesystemRepositoryAcquirer().acquire(repository, request);
        Path checkpoints = Files.createDirectory(temporary.resolve("checkpoints"));
        var store = new AcquisitionCheckpointStore();
        assertThrows(IllegalArgumentException.class,
                () -> store.save(checkpoints, "too-small.bin", original, 16));
        assertFalse(Files.exists(checkpoints.resolve("too-small.bin")));
        var saved = store.save(checkpoints, "capture.bin", original, 100000);
        Files.writeString(repository.resolve("A.java"), "class Changed {}", StandardCharsets.UTF_8);
        var changed = new FilesystemRepositoryAcquirer().acquire(repository, request);
        assertThrows(FileAlreadyExistsException.class,
                () -> store.save(checkpoints, "capture.bin", changed, 100000));
        var loaded = store.load(checkpoints, "capture.bin", request, 100000);
        assertEquals(AcquisitionCheckpointStore.Status.LOADED, loaded.status());
        assertEquals(original.identity(), loaded.acquisition().orElseThrow().identity());
        assertEquals(saved, loaded.checkpointDigest().orElseThrow());
        var policy = new AdaptiveRepositoryJourney.Policy(new RepositoryInputDecoder.Policy("UTF-8", 20, 1000),
                new UniversalBuildIngestion.Policy(Optional.of(21), Optional.of(21), Optional.of("UTF-8"),
                        20, 1000, 100, 8),
                new BuildModelPolicy(List.of(), List.of(), Map.of(), 1000, 100, 8));
        var replay = new AdaptiveRepositoryJourney().intakeCaptured(loaded.acquisition().orElseThrow(), policy);
        assertTrue(replay.inputs().isPresent());
        assertEquals(original.snapshot().orElseThrow().identity(), replay.inputs().orElseThrow().snapshot().identity());
    }

    @Test void partialCheckpointRemainsPartialAndCorruptionIsNotAccepted() throws Exception {
        Path repository = Files.createDirectory(temporary.resolve("repository"));
        Files.writeString(repository.resolve("a.txt"), "a", StandardCharsets.UTF_8);
        Files.writeString(repository.resolve("b.txt"), "b", StandardCharsets.UTF_8);
        Files.writeString(repository.resolve("c.txt"), "c", StandardCharsets.UTF_8);
        var request = request(1);
        var partial = new FilesystemRepositoryAcquirer().acquire(repository, request);
        assertEquals(RepositoryAcquisitionResult.Completion.PARTIAL, partial.completion());
        Path checkpoints = Files.createDirectory(temporary.resolve("checkpoints"));
        var store = new AcquisitionCheckpointStore();
        store.save(checkpoints, "capture.bin", partial, 100000);
        var loaded = store.load(checkpoints, "capture.bin", request, 100000);
        assertEquals(RepositoryAcquisitionResult.Completion.PARTIAL,
                loaded.acquisition().orElseThrow().completion());
        assertTrue(loaded.acquisition().orElseThrow().snapshot().isEmpty());
        assertEquals(AcquisitionCheckpointStore.Status.STALE,
                store.load(checkpoints, "capture.bin", request(2), 100000).status());
        Path file = checkpoints.resolve("capture.bin");
        byte[] bytes = Files.readAllBytes(file); bytes[12] ^= 1; Files.write(file, bytes);
        assertEquals(AcquisitionCheckpointStore.Status.CORRUPT,
                store.load(checkpoints, "capture.bin", request, 100000).status());
    }

    private static RepositoryAcquisitionRequest request(int maxFiles) {
        return new RepositoryAcquisitionRequest(RepositoryIdentity.fromCanonicalCoordinate(
                "https://example.test/checkpoint.git"), Optional.empty(), false, "pom.xml",
                new RepositoryAcquisitionPolicy(maxFiles, 20, 1000, 10000, 8, List.of()));
    }
}
