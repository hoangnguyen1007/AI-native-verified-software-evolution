package com.evolution.analysis.filesystem;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.graph.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GraphBinaryCacheTest {
    @TempDir
    Path dir;

    @Test
    void roundTripsReadsManifestInIsolationAndRejectsCorruptionOrDeletedJson() throws Exception {
        KnowledgeGraph graph = FileGraphStoreTest.graph();
        var storeDir = Files.createDirectory(dir.resolve("store"));
        var store = new FileGraphStore(storeDir);
        store.save(graph);
        Path jsonPath = store.jsonPath(graph.identity());
        assertTrue(Files.isRegularFile(jsonPath));

        var binaryCache = new GraphBinaryCache(storeDir, 0L);
        binaryCache.write(graph, jsonPath);
        Path binPath = binaryCache.cachePath(graph.identity());
        assertTrue(Files.isRegularFile(binPath));

        var manifest = binaryCache.readManifest(graph.identity(), jsonPath).orElseThrow();
        assertEquals(graph.manifest(), manifest);

        var loaded = binaryCache.read(graph.identity(), jsonPath).orElseThrow();
        assertEquals(graph.identity(), loaded.identity());
        assertEquals(graph.nodes(), loaded.nodes());
        assertEquals(graph.edges(), loaded.edges());
        assertEquals(graph.evidence(), loaded.evidence());

        // Corrupt a byte inside the binary cache -> must be rejected & deleted, and store.load falls back to JSON
        byte[] corrupted = Files.readAllBytes(binPath);
        corrupted[corrupted.length / 2] ^= 0x55;
        Files.write(binPath, corrupted);
        assertTrue(binaryCache.read(graph.identity(), jsonPath).isEmpty());
        assertFalse(Files.exists(binPath));
        assertEquals(graph.identity(), store.load(graph.identity()).orElseThrow().identity());

        // Rewrite binary cache, then delete authoritative .graph.json -> read & readManifest must refuse!
        binaryCache.write(graph, jsonPath);
        assertTrue(Files.isRegularFile(binPath));
        Files.delete(jsonPath);
        assertTrue(binaryCache.readManifest(graph.identity(), jsonPath).isEmpty());
        assertTrue(binaryCache.read(graph.identity(), jsonPath).isEmpty());
    }

    @Test
    void storeWithLowThresholdPopulatesAndLoadsBinaryCacheTransparently() throws Exception {
        String prev = System.getProperty("evo.graph.binaryCacheMinBytes");
        System.setProperty("evo.graph.binaryCacheMinBytes", "1");
        try {
            KnowledgeGraph graph = FileGraphStoreTest.graph();
            var storeDir = Files.createDirectory(dir.resolve("auto-store"));
            var store = new FileGraphStore(storeDir);
            store.save(graph);
            Path binPath = new GraphBinaryCache(storeDir, 1L).cachePath(graph.identity());
            assertTrue(Files.isRegularFile(binPath));

            GraphManifest m = store.manifest(graph.identity()).orElseThrow();
            assertEquals(graph.manifest(), m);

            KnowledgeGraph loaded = store.load(graph.identity()).orElseThrow();
            assertEquals(graph.identity(), loaded.identity());
        } finally {
            if (prev == null) System.clearProperty("evo.graph.binaryCacheMinBytes");
            else System.setProperty("evo.graph.binaryCacheMinBytes", prev);
        }
    }
}
