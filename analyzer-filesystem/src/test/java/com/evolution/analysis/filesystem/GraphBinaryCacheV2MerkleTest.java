package com.evolution.analysis.filesystem;

import com.evolution.analysis.buildmodel.SourcePlanModel;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.ingestion.*;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * WP3.5 verification suite for:
 * <ol>
 *   <li>Canonical Merkle-Subtree Delta Binary Format {@code EVOGBIN2} (lossless bijection, lazy JSON materialization, EVOGBIN1 backward compatibility)</li>
 *   <li>100/100 seeded random single-file/context incremental edits proving {@code SHA-256(Incremental EVOGBIN2) == SHA-256(Cold EVOGBIN2)}, {@code dirtyLeavesWritten <= 2}, and exact capability-gap {@code occurrenceCount} sums (I2/I3)</li>
 *   <li>10-snapshot historical deduplicated storage reduction ({@code >= 6x} vs naive 10 full snapshots)</li>
 *   <li>2-Pass ABI-Streaming Module Ingestion with {@code SqliteInventoryStore} + {@code PartitionedGraphStore} proving bounded {@code maxInFlightModules <= 2} and 100% parity vs standard ingestion</li>
 * </ol>
 */
class GraphBinaryCacheV2MerkleTest {
    private static final RepositoryIdentity REPO =
            RepositoryIdentity.fromCanonicalCoordinate("urn:test:wp35-evogbin2-merkle");
    private static final VersionedIdentifier TEST_FRONTEND_PROVIDER =
            new VersionedIdentifier("test.abi-streaming-frontend", "1.0");

    @TempDir
    Path temp;

    @Test
    void evogbin2RoundTripBijectionPreservesGapsLazyJsonAndEvogbin1Compatibility() throws Exception {
        SyntheticMultiContextModel model = SyntheticMultiContextModel.create(6, 10, 42L);
        // Inject explicit coalesced & uncoalesced capability gaps plus orphan evidence and cross-context edges.
        model.setGap(0, "UNRESOLVED_DEPENDENCY_SYMBOL", 17L, "mod-0/App.java");
        model.setGap(2, "UNRESOLVED_DEPENDENCY_SYMBOL", 42L, "mod-2/Worker.java");
        model.setGap(4, "MISSING_GENERATED_TYPE", 1L, "mod-4/Mapper.java");
        model.addOrphanEvidence("fixture.orphan", Map.of("note", "preserved-for-strict-bijection"));
        KnowledgeGraph graph = model.toKnowledgeGraph();

        // 1. Standalone EVOGBIN2 encode -> verify header magic & lossless decode bijection.
        byte[] evogbin2 = GraphBinaryCache.encodeEvogbin2(graph);
        assertArrayEquals(GraphBinaryCache.MAGIC_EVOGBIN2, Arrays.copyOf(evogbin2, 8));

        GraphBinaryCache.DecodedEvogbin2 decoded = GraphBinaryCache.decodeEvogbin2(evogbin2);
        KnowledgeGraph restored = decoded.graph();
        assertEquals(graph.identity(), restored.identity());
        assertEquals(graph.manifest(), restored.manifest());
        assertEquals(graph.snapshot(), restored.snapshot());
        assertEquals(graph.contexts(), restored.contexts());
        assertEquals(graph.nodes(), restored.nodes());
        assertEquals(graph.edges(), restored.edges());
        assertEquals(graph.evidence(), restored.evidence());

        // Exact capability gap occurrenceCount sums preserved (I2/I3).
        assertEquals(3L, decoded.capabilitySummary().uniqueGapNodes());
        assertEquals(60L, decoded.capabilitySummary().totalOccurrenceCount());
        assertEquals(59L, decoded.capabilitySummary().occurrenceCountByReason().get("UNRESOLVED_DEPENDENCY_SYMBOL"));
        assertEquals(1L, decoded.capabilitySummary().occurrenceCountByReason().get("MISSING_GENERATED_TYPE"));
        assertEquals(2L, decoded.capabilitySummary().uniqueGapCountByReason().get("UNRESOLVED_DEPENDENCY_SYMBOL"));

        // 2. Lazy canonical .graph.json materialization matches GraphSnapshotCodec bit-for-bit.
        String expectedJson = new GraphSnapshotCodec().encode(graph);
        var lazyJsonWriter = new StringWriter();
        GraphBinaryCache.materializeCanonicalJson(evogbin2, lazyJsonWriter);
        assertEquals(expectedJson, lazyJsonWriter.toString());

        // 3. Corruption detection fails closed.
        byte[] tampered = evogbin2.clone();
        tampered[tampered.length / 2] ^= 0x5A;
        assertThrows(IOException.class, () -> GraphBinaryCache.decodeEvogbin2(tampered));

        // 4. File write/read round-trip.
        Path evogbin2File = temp.resolve("standalone.evogbin2");
        ContentDigest writtenMerkle = GraphBinaryCache.writeEvogbin2(graph, evogbin2File);
        assertEquals(decoded.merkleGraphIdentity(), writtenMerkle);
        assertEquals(graph.identity(), GraphBinaryCache.readEvogbin2(evogbin2File).graph().identity());

        // 5. 100% backward compatibility with legacy EVOGBIN1 v1 and v2 readers.
        Path jsonDir = Files.createDirectory(temp.resolve("legacy-json"));
        var fileStore = new FileGraphStore(jsonDir);
        fileStore.save(graph);
        Path jsonPath = fileStore.jsonPath(graph.identity());

        var legacyV1 = new GraphBinaryCache(Files.createDirectory(temp.resolve("cache-v1")), 0L);
        legacyV1.write(graph, jsonPath, 1);
        assertEquals(graph.identity(), legacyV1.read(graph.identity(), jsonPath).orElseThrow().identity());
        assertEquals(graph.manifest(), legacyV1.readManifest(graph.identity(), jsonPath).orElseThrow());

        var legacyV2 = new GraphBinaryCache(Files.createDirectory(temp.resolve("cache-v2")), 0L);
        legacyV2.write(graph, jsonPath, 2);
        assertEquals(graph.identity(), legacyV2.read(graph.identity(), jsonPath).orElseThrow().identity());
        assertEquals(graph.manifest(), legacyV2.readManifest(graph.identity(), jsonPath).orElseThrow());
    }

    @Test
    void hundredRandomIncrementalEditsMatchColdEvogbin2BitIdenticallyWithAtMostTwoDirtyLeaves() throws Exception {
        final int moduleCount = 12;
        SyntheticMultiContextModel model = SyntheticMultiContextModel.create(moduleCount, 8, 20261128L);
        model.setGap(0, "UNRESOLVED_DEPENDENCY_SYMBOL", 11L, "mod-0/Init.java");
        model.setGap(5, "MISSING_GENERATED_TYPE", 7L, "mod-5/Dto.java");

        Path storeDir = Files.createDirectory(temp.resolve("merkle-inc-100"));
        var store = new PartitionedGraphStore(storeDir);

        KnowledgeGraph initialGraph = model.toKnowledgeGraph();
        var currentPub = store.saveMerkleV2(initialGraph, () -> false);
        assertEquals(moduleCount + 1, currentPub.dirtyLeavesWritten());
        assertEquals(18L, currentPub.capabilitySummary().totalOccurrenceCount());

        var rng = new Random(20261128L);
        String[] gapReasons = {
                "UNRESOLVED_DEPENDENCY_SYMBOL",
                "MISSING_GENERATED_TYPE",
                "EXTERNAL_CONFIG_UNRESOLVED",
                "DYNAMIC_PROXY_OPAQUE"
        };

        for (int edit = 1; edit <= 100; edit++) {
            int targetCtx = rng.nextInt(moduleCount);
            int mutationKind = rng.nextInt(4);
            var dirtyContexts = new LinkedHashMap<String, GraphBinaryCache.ContextPartitionData>();

            // Advance snapshot on every edit (simulating Warm 1-File Edit on a new commit/revision).
            model.advanceSnapshot("edit-" + edit);

            switch (mutationKind) {
                case 0 -> model.mutateNodeAndEdge(targetCtx, edit);
                case 1 -> model.addNodeAndEdge(targetCtx, edit);
                case 2 -> {
                    long occ = 1L + rng.nextInt(50);
                    model.setGap(targetCtx, gapReasons[rng.nextInt(gapReasons.length)], occ,
                            "mod-" + targetCtx + "/File" + edit + ".java");
                }
                default -> {
                    // Mutate 2 contexts simultaneously (still <= 2 dirty leaves!)
                    int secondCtx = (targetCtx + 1) % moduleCount;
                    model.mutateNodeAndEdge(targetCtx, edit);
                    model.setGap(secondCtx, gapReasons[rng.nextInt(gapReasons.length)], 1L + rng.nextInt(25),
                            "mod-" + secondCtx + "/Dual" + edit + ".java");
                    dirtyContexts.put(model.contextId(secondCtx), model.partitionData(secondCtx));
                }
            }
            dirtyContexts.put(model.contextId(targetCtx), model.partitionData(targetCtx));

            // Incremental EVOGBIN2 Merkle-leaf delta persistence (ONLY dirty leaves written, ZERO .graph.json!).
            var incPub = store.saveMerkleDeltaPartitionsV2(
                    currentPub.merkleGraphIdentity(),
                    model.snapshotId(),
                    dirtyContexts,
                    Set.of(),
                    Optional.empty(),
                    Optional.empty(),
                    () -> false);

            // Independent Cold full build & Cold EVOGBIN2 encoding for I2 bit-identity proof.
            KnowledgeGraph coldGraph = model.toKnowledgeGraph();
            GraphBinaryCache.MerkleDecomposition coldDecomp = GraphBinaryCache.decomposeMerkle(coldGraph);
            byte[] coldEvogbin2 = GraphBinaryCache.encodeEvogbin2(coldDecomp);
            byte[] incEvogbin2 = store.exportEvogbin2Bytes(incPub.merkleGraphIdentity(), () -> false);

            // 1. Merkle Root SHA-256 parity
            assertEquals(coldDecomp.merkleGraphIdentity(), incPub.merkleGraphIdentity(),
                    "Merkle root mismatch on edit #" + edit);

            // 2. Exact byte-stream SHA-256 parity: SHA-256(Incremental EVOGBIN2) == SHA-256(Cold EVOGBIN2)
            assertEquals(ContentDigest.sha256(coldEvogbin2), ContentDigest.sha256(incEvogbin2),
                    "EVOGBIN2 SHA-256 mismatch on edit #" + edit);

            // 3. Strictly <= 2 dirty Merkle leaves written on every edit
            assertTrue(incPub.dirtyLeavesWritten() <= 2,
                    "Expected <= 2 dirty leaves on edit #" + edit + ", got " + incPub.dirtyLeavesWritten());
            assertTrue(incPub.reusedLeaves() >= moduleCount - 1,
                    "Expected untouched leaves reused on edit #" + edit + ", reused=" + incPub.reusedLeaves());

            // 4. Exact capability-gap occurrenceCount preservation (I2/I3)
            assertEquals(coldDecomp.capabilitySummary(), incPub.capabilitySummary(),
                    "CapabilitySummary mismatch on edit #" + edit);
            assertEquals(model.expectedTotalGapOccurrences(), incPub.capabilitySummary().totalOccurrenceCount(),
                    "Total occurrenceCount mismatch on edit #" + edit);

            // 5. Full decoded graph parity
            KnowledgeGraph decodedInc = store.loadMerkleV2(incPub.merkleGraphIdentity(), () -> false).graph();
            assertEquals(coldGraph.identity(), decodedInc.identity(), "Graph identity mismatch on edit #" + edit);

            currentPub = incPub;
        }

        // Confirm zero monolithic .graph.json files were written during all 100 incremental edits.
        try (var walk = Files.walk(storeDir)) {
            assertEquals(0L, walk.filter(p -> p.toString().endsWith(".graph.json")).count(),
                    "Incremental EVOGBIN2 persistence must never force monolithic .graph.json rebuilds");
        }

        // Confirm lazy audit materialization works on the 100th incremental snapshot.
        Path lazyJson = temp.resolve("audit-edit-100.graph.json");
        store.materializeCanonicalJson(currentPub.merkleGraphIdentity(), lazyJson, () -> false);
        assertEquals(
                new GraphSnapshotCodec().encode(model.toKnowledgeGraph()),
                Files.readString(lazyJson, StandardCharsets.UTF_8));
    }

    @Test
    void tenHistoricalSnapshotsAchieveAtLeastSixXStorageDeduplicationReduction() throws Exception {
        final int moduleCount = 28;
        final int nodesPerModule = 30;
        SyntheticMultiContextModel model = SyntheticMultiContextModel.create(moduleCount, nodesPerModule, 999L);

        Path dedupDir = Files.createDirectory(temp.resolve("merkle-10-snaps"));
        var store = new PartitionedGraphStore(dedupDir);

        var snapshotMerkleIds = new ArrayList<ContentDigest>(10);
        var expectedGraphIds = new ArrayList<ContentDigest>(10);
        long naiveFullJsonBytes = 0L;
        long naiveFullEvogbin2Bytes = 0L;

        // Snapshot 1 (initial cold import)
        KnowledgeGraph snap1 = model.toKnowledgeGraph();
        var pub = store.saveMerkleV2(snap1, () -> false);
        snapshotMerkleIds.add(pub.merkleGraphIdentity());
        expectedGraphIds.add(snap1.identity());
        naiveFullJsonBytes += new GraphSnapshotCodec().encode(snap1).getBytes(StandardCharsets.UTF_8).length;
        naiveFullEvogbin2Bytes += GraphBinaryCache.encodeEvogbin2(snap1).length;

        // Snapshots 2..10 (each edits 1 module context on a new commit snapshot)
        for (int snap = 2; snap <= 10; snap++) {
            int editedCtx = (snap - 1) % moduleCount;
            model.advanceSnapshot("commit-" + snap);
            model.mutateNodeAndEdge(editedCtx, snap);
            if (snap % 3 == 0) {
                model.setGap(editedCtx, "UNRESOLVED_DEPENDENCY_SYMBOL", snap * 5L,
                        "mod-" + editedCtx + "/Snap" + snap + ".java");
            }
            var dirty = Map.of(model.contextId(editedCtx), model.partitionData(editedCtx));
            pub = store.saveMerkleDeltaPartitionsV2(
                    pub.merkleGraphIdentity(),
                    model.snapshotId(),
                    dirty,
                    Set.of(),
                    Optional.empty(),
                    Optional.empty(),
                    () -> false);
            assertEquals(1, pub.dirtyLeavesWritten(), "Single-module commit #" + snap + " must write exactly 1 dirty leaf");
            assertEquals(moduleCount, pub.reusedLeaves(), "All untouched leaves + cross leaf must be reused");

            KnowledgeGraph coldSnap = model.toKnowledgeGraph();
            snapshotMerkleIds.add(pub.merkleGraphIdentity());
            expectedGraphIds.add(coldSnap.identity());
            naiveFullJsonBytes += new GraphSnapshotCodec().encode(coldSnap).getBytes(StandardCharsets.UTF_8).length;
            naiveFullEvogbin2Bytes += GraphBinaryCache.encodeEvogbin2(coldSnap).length;
        }

        long dedupDiskBytes = store.totalMerkleStorageBytes();
        double ratioVsJson = naiveFullJsonBytes / (double) Math.max(1L, dedupDiskBytes);
        double ratioVsStandaloneEvogbin2 = naiveFullEvogbin2Bytes / (double) Math.max(1L, dedupDiskBytes);

        System.out.printf(
                "[WP3.5-MERKLE-BENCH] 10 snapshots (%d contexts): dedupDisk=%d B, naiveJson=%d B (%.2fx reduction), naiveEvogbin2=%d B (%.2fx reduction)%n",
                moduleCount, dedupDiskBytes, naiveFullJsonBytes, ratioVsJson,
                naiveFullEvogbin2Bytes, ratioVsStandaloneEvogbin2);

        assertTrue(ratioVsJson >= 6.0,
                "Expected >= 6x storage reduction vs 10 full JSON snapshots, got " + ratioVsJson + "x");
        assertTrue(ratioVsStandaloneEvogbin2 >= 6.0,
                "Expected >= 6x deduplicated storage reduction vs 10 full EVOGBIN2 snapshots, got "
                        + ratioVsStandaloneEvogbin2 + "x");

        // All 10 historical snapshots remain independently readable and bit-identical.
        for (int i = 0; i < 10; i++) {
            KnowledgeGraph restored = store.loadMerkleV2(snapshotMerkleIds.get(i), () -> false).graph();
            assertEquals(expectedGraphIds.get(i), restored.identity(),
                    "Historical snapshot #" + (i + 1) + " identity mismatch");
        }
    }

    @Test
    void twoPassAbiStreamingBoundsInFlightModulesAndMatchesStandardIngestionWithFullParity() throws Exception {
        final int moduleCount = 6;
        RepositoryInputs inputs = buildMultiModuleRepositoryInputs(moduleCount);
        var platform = PlatformInput.create(21, "21", "jdk", List.of(
                new PlatformInput.Artifact("java.base", ContentDigest.sha256Utf8("jb21"),
                        Path.of("java.base.jmod"), PlatformInput.Format.JMOD)));

        // Concurrency-instrumented frontend verifying bounded peakInFlightModules <= 2.
        var frontendInFlight = new AtomicInteger();
        var frontendPeakInFlight = new AtomicInteger();
        ConcurrentSemanticFrontend instrumentedFrontend = new ConcurrentSemanticFrontend() {
            @Override
            public FrontendResult analyze(FrontendRequest request) {
                int cur = frontendInFlight.incrementAndGet();
                frontendPeakInFlight.accumulateAndGet(cur, Math::max);
                try {
                    Thread.sleep(5L);
                    return buildDeterministicModuleFrontendResult(request);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(interrupted);
                } finally {
                    frontendInFlight.decrementAndGet();
                }
            }
        };

        var policy = UniversalArchitecturePipeline.Policy.builder(instrumentedFrontend, TEST_FRONTEND_PROVIDER)
                .defaultPlatform(platform)
                .mavenProvider(new com.evolution.analysis.maven.MavenBuildModelProvider())
                .truthEvaluationMode(UniversalArchitecturePipeline.TruthEvaluationMode.NONE)
                .build();

        // 1. Standard single-pass ingestion baseline
        var standardDetailed = new UniversalArchitecturePipeline(AnalysisWorkspace.MEMORY)
                .runWithEvidence(inputs, policy);
        UniversalArchitectureReport standardReport = standardDetailed.report();
        assertTrue(standardReport.modules().size() >= moduleCount,
                "Expected at least " + moduleCount + " modules, got " + standardReport.modules().size());
        assertEquals(moduleCount, standardReport.types().size());
        assertTrue(standardReport.components().size() >= moduleCount);

        // 2. 2-Pass ABI-Streaming ingestion backed by real SqliteInventoryStore + PartitionedGraphStore!
        frontendInFlight.set(0);
        frontendPeakInFlight.set(0);

        Path casDir = Files.createDirectory(temp.resolve("abi-cas"));
        Path invDir = Files.createDirectory(temp.resolve("abi-inv"));
        Path pagesDir = Files.createDirectory(temp.resolve("abi-pages"));
        var contentStore = new StreamingContentStore(casDir);
        var partitionedStore = new PartitionedGraphStore(pagesDir);

        UniversalArchitecturePipeline.StreamingResult streamingResult;
        PartitionedGraphStore.MerklePublication merklePub;
        try (var sqliteInventory = new SqliteInventoryStore(invDir, contentStore)) {
            var sink = partitionedStore.openAbiStreamingSink(
                    inputs.snapshot().identity(), Optional.empty(), false, () -> false);

            streamingResult = new UniversalArchitecturePipeline(AnalysisWorkspace.MEMORY)
                    .runTwoPassAbiStreaming(inputs, policy, "rev-abi-1", sqliteInventory, sink, 2);

            merklePub = sink.publication().orElseThrow();

            // Verify Pass-1 compact ABI index persisted in SqliteInventoryStore.
            assertEquals((long) moduleCount, sqliteInventory.countSignatures("rev-abi-1"));
            var sig0 = sqliteInventory.lookupByQualifiedName("rev-abi-1", "com.acme.mod0.Service0").orElseThrow();
            assertEquals("CLASS", sig0.kind());
            assertTrue(sig0.annotations().contains("@Service"));
            assertTrue(sig0.memberSignatures().stream().anyMatch(m -> m.contains("execute(")),
                    "Expected public method signature in compact ABI entry: " + sig0.memberSignatures());

            // Verify read-only reopened SqliteInventoryStore reads the exact persisted ABI index.
            try (var roInventory = new SqliteInventoryStore(invDir, contentStore, true)) {
                assertEquals((long) moduleCount, roInventory.countSignatures("rev-abi-1"));
                assertEquals(sig0, roInventory.lookupByQualifiedName("rev-abi-1", "com.acme.mod0.Service0").orElseThrow());
            }
        }

        var stats = streamingResult.stats();
        assertEquals(moduleCount, stats.pass1ExtractedModules());
        assertEquals(moduleCount, stats.pass2ResolvedModules());
        assertTrue(stats.peakInFlightModules() >= 1 && stats.peakInFlightModules() <= 2,
                "Expected bounded peakInFlightModules <= 2, got " + stats.peakInFlightModules());
        assertTrue(frontendPeakInFlight.get() >= 1 && frontendPeakInFlight.get() <= 2,
                "Expected bounded frontend peak in-flight <= 2, got " + frontendPeakInFlight.get());
        assertEquals((long) moduleCount, stats.abiSignatureCount());
        assertTrue(stats.crossModuleResolvedRefs() >= moduleCount - 1,
                "Expected cross-module references resolved against AbiSignatureIndex, got "
                        + stats.crossModuleResolvedRefs());
        assertEquals(moduleCount, stats.flushedPartitions());

        // 100% parity between 2-Pass ABI-Streaming report and standard ingestion report!
        UniversalArchitectureReport streamingReport = streamingResult.report();
        assertEquals(standardReport.identity(), streamingReport.identity());
        assertEquals(standardReport.status(), streamingReport.status());
        assertEquals(standardReport.metrics(), streamingReport.metrics());
        assertEquals(standardReport.modules(), streamingReport.modules());
        assertEquals(standardReport.types(), streamingReport.types());
        assertEquals(standardReport.dependencies(), streamingReport.dependencies());
        assertEquals(standardReport.components(), streamingReport.components());
        assertEquals(standardReport.producers(), streamingReport.producers());
        assertEquals(standardReport.injectionSites(), streamingReport.injectionSites());
        assertEquals(standardReport.entryPoints(), streamingReport.entryPoints());
        assertEquals(standardReport.truthRegions(), streamingReport.truthRegions());
        assertEquals(standardReport.issues(), streamingReport.issues());
        assertEquals(standardReport.gaps(), streamingReport.gaps());

        // Flushed PartitionedGraphStore Merkle snapshot decodes cleanly with all module contexts + root context.
        assertEquals(moduleCount + 1, merklePub.contextPartitionHashes().size());
        GraphBinaryCache.DecodedEvogbin2 decodedStreamed =
                partitionedStore.loadMerkleV2(merklePub.merkleGraphIdentity(), () -> false);
        assertEquals(moduleCount + 1, decodedStreamed.graph().contexts().size());
        assertTrue(decodedStreamed.graph().nodes().size() >= moduleCount);

        // Lazy .graph.json materialization succeeds and passes FileGraphStore.readSnapshot validation.
        Path materialized = temp.resolve("streamed-materialized.graph.json");
        partitionedStore.materializeCanonicalJson(merklePub.merkleGraphIdentity(), materialized, () -> false);
        KnowledgeGraph fromJson = FileGraphStore.readSnapshot(materialized, 64_000_000, GraphJson.Limits.defaults());
        assertEquals(decodedStreamed.graph().identity(), fromJson.identity());

        // Verify admission heuristic bounds & property overrides.
        assertFalse(UniversalArchitecturePipeline.shouldUseTwoPassAbiStreaming(10_000_000L, 400, 10));
        assertTrue(UniversalArchitecturePipeline.shouldUseTwoPassAbiStreaming(150_000_000L, 400, 10));
        assertTrue(UniversalArchitecturePipeline.shouldUseTwoPassAbiStreaming(1_000L, 12_000, 10));
        assertTrue(UniversalArchitecturePipeline.shouldUseTwoPassAbiStreaming(1_000L, 100, 70));
        assertEquals(2, UniversalArchitecturePipeline.configuredMaxInFlightModules());
    }

    // =========================================================================
    // Test fixtures (purely generic synthetic multi-module structures)
    // =========================================================================

    private static RepositoryInputs buildMultiModuleRepositoryInputs(int moduleCount) {
        var rootMod = ModuleDescriptor.create(REPO, "root", ".");
        var rawFiles = new TreeMap<String, SourceInput>();
        var snapshotFiles = new ArrayList<SnapshotFile>();
        var rawDocs = new ArrayList<SourceDocument>();

        var rootPom = new StringBuilder();
        rootPom.append("<project><modelVersion>4.0.0</modelVersion>")
                .append("<groupId>com.acme</groupId><artifactId>mono-root</artifactId><version>1.0.0</version>")
                .append("<packaging>pom</packaging>")
                .append("<properties><maven.compiler.release>21</maven.compiler.release>")
                .append("<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties><modules>");
        for (int i = 0; i < moduleCount; i++) {
            rootPom.append("<module>mod-").append(i).append("</module>");
        }
        rootPom.append("</modules></project>");
        addSourceFile(rootMod, "pom.xml", rootPom.toString(), SourceClassification.OTHER, rawFiles, snapshotFiles, rawDocs);

        for (int i = 0; i < moduleCount; i++) {
            String modName = "mod-" + i;
            String modPom = "<project><modelVersion>4.0.0</modelVersion>"
                    + "<parent><groupId>com.acme</groupId><artifactId>mono-root</artifactId><version>1.0.0</version></parent>"
                    + "<artifactId>" + modName + "</artifactId>"
                    + "<properties><maven.compiler.release>21</maven.compiler.release></properties>"
                    + "</project>";
            addSourceFile(rootMod, modName + "/pom.xml", modPom, SourceClassification.OTHER, rawFiles, snapshotFiles, rawDocs);

            String depField = i > 0
                    ? "    private final com.acme.mod" + (i - 1) + ".Service" + (i - 1) + " upstream;\n"
                    : "";
            String depCall = i > 0
                    ? "        return upstream.execute(input) + " + i + ";\n"
                    : "        return input + " + i + ";\n";
            String javaSrc = "package com.acme.mod" + i + ";\n"
                    + "import org.springframework.stereotype.Service;\n"
                    + "@Service\n"
                    + "public class Service" + i + " {\n"
                    + depField
                    + "    public int execute(int input) {\n"
                    + depCall
                    + "    }\n"
                    + "}\n";
            addSourceFile(rootMod, modName + "/src/main/java/com/acme/mod" + i + "/Service" + i + ".java",
                    javaSrc, SourceClassification.MAIN, rawFiles, snapshotFiles, rawDocs);
        }

        var snapshot = RepositorySnapshot.create(REPO, Optional.of("rev-multi-6"), false, snapshotFiles, rawDocs);
        return new RepositoryInputs(snapshot, rawFiles);
    }

    private static void addSourceFile(
            ModuleDescriptor rootMod,
            String path,
            String content,
            SourceClassification classification,
            Map<String, SourceInput> rawFiles,
            List<SnapshotFile> snapshotFiles,
            List<SourceDocument> rawDocs) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        var doc = SourceDocument.create(REPO, rootMod, path, ContentDigest.sha256(bytes), classification);
        rawFiles.put(path, new SourceInput(doc, bytes));
        rawDocs.add(doc);
        snapshotFiles.add(SnapshotFile.from(doc));
    }

    private static FrontendResult buildDeterministicModuleFrontendResult(FrontendRequest request) {
        var direct = new Derivation(DerivationKind.DIRECT, TEST_FRONTEND_PROVIDER, List.of());
        var declarations = new ArrayList<DeclarationRecord>();
        var typeDecls = new ArrayList<TypeDeclarationRecord>();
        var memberDecls = new ArrayList<MemberDeclarationRecord>();
        var annotations = new ArrayList<AnnotationUseRecord>();
        var typeUses = new ArrayList<TypeUseRecord>();
        var sources = new ArrayList<SourceOutcome>();

        for (var src : request.sources()) {
            sources.add(new SourceOutcome(src.document().identity(), SourceOutcome.State.PROCESSED, List.of()));
            String path = src.document().path();
            if (!path.endsWith(".java")) continue;
            String fileName = path.substring(path.lastIndexOf('/') + 1, path.length() - 5);
            String text = src.text();
            String pkg = "com.acme";
            int pkgStart = text.indexOf("package ");
            if (pkgStart >= 0) {
                int semi = text.indexOf(';', pkgStart);
                if (semi > pkgStart) pkg = text.substring(pkgStart + 8, semi).strip();
            }
            var typeSpan = new SourceSpan(src.document().identity(), 4, 1, 4, 24);
            var typeEntity = Entity.create(EntityOrigin.PROJECT, EntityScope.project(request.module()),
                    EntityKind.TYPE, JavaSymbolName.topLevelType(pkg, fileName).canonicalName(), Optional.of(typeSpan));
            declarations.add(new DeclarationRecord(typeEntity, "public class " + fileName,
                    SemanticStatus.RESOLVED, direct, List.of(), List.of()));
            typeDecls.add(new TypeDeclarationRecord(typeEntity.identity(), TypeDeclarationRecord.Kind.CLASS,
                    false, true));

            if (text.contains("@Service")) {
                var annSpan = new SourceSpan(src.document().identity(), 3, 1, 3, 9);
                annotations.add(new AnnotationUseRecord(
                        "java:v1:[\"annotation-use\",\"" + pkg + "." + fileName + "\",\"@Service\"]",
                        typeEntity.identity(), annSpan, "declaration-syntax", "@Service"));
            }

            var methodSpan = new SourceSpan(src.document().identity(), 6, 5, 6, 35);
            var methodEntity = Entity.create(EntityOrigin.PROJECT, EntityScope.project(request.module()),
                    EntityKind.METHOD, pkg + "." + fileName + "#execute(int)", Optional.of(methodSpan));
            declarations.add(new DeclarationRecord(methodEntity, "public int execute(int input)",
                    SemanticStatus.RESOLVED, direct, List.of(), List.of()));
            memberDecls.add(new MemberDeclarationRecord(methodEntity.identity(), "execute",
                    false, false, false));

            // Record cross-module upstream field type reference when present.
            int upIdx = text.indexOf("com.acme.mod");
            if (upIdx >= 0 && text.contains("upstream")) {
                int end = text.indexOf(" upstream", upIdx);
                if (end > upIdx) {
                    String refFqn = text.substring(upIdx, end).strip();
                    var refSpan = new SourceSpan(src.document().identity(), 5, 19, 5, 19 + refFqn.length());
                    typeUses.add(new TypeUseRecord(
                            Optional.of(typeEntity.identity()),
                            new RelationshipKind("java.field-type"),
                            refSpan,
                            new JavaType(JavaType.Kind.UNKNOWN, refFqn, Optional.empty(), List.of(),
                                    Optional.empty(), SemanticStatus.UNRESOLVED),
                            false));
                }
            }
        }
        for (var reactor : request.reactorSources()) {
            for (var src : reactor.sources()) {
                sources.add(new SourceOutcome(src.document().identity(), SourceOutcome.State.PROCESSED, List.of()));
            }
        }
        var coverage = FrontendRequest.CATEGORIES.stream()
                .map(c -> new CategoryCoverage(new RelationshipKind("java." + c),
                        CategoryCoverage.Support.IMPLEMENTED, 0, 0, 0))
                .toList();
        return new FrontendResult(request.manifest().identity(), TEST_FRONTEND_PROVIDER,
                FrontendResult.State.COMPLETED, declarations, List.of(), List.of(), sources, coverage,
                List.of(), typeUses, annotations, List.of(), typeDecls, memberDecls, List.of(), List.of());
    }

    /** Mutable multi-context graph fixture for deterministic Cold vs Incremental Merkle proofs. */
    private static final class SyntheticMultiContextModel {
        private SnapshotIdentity snapshot;
        private final List<AnalysisIdentity> analyses;
        private final List<GraphEvidence> ctxEvidences;
        private final List<List<KnowledgeGraph.Node>> nodesByCtx;
        private final List<List<KnowledgeGraph.Edge>> edgesByCtx;
        private final List<Map<ContentDigest, GraphEvidence>> evidenceByCtx;
        private final List<KnowledgeGraph.Edge> crossEdges = new ArrayList<>();
        private final Map<ContentDigest, GraphEvidence> crossOrOrphanEv = new TreeMap<>();
        private final Map<Integer, Long> gapOccByCtx = new HashMap<>();

        private SyntheticMultiContextModel(
                SnapshotIdentity snapshot,
                List<AnalysisIdentity> analyses,
                List<GraphEvidence> ctxEvidences,
                List<List<KnowledgeGraph.Node>> nodesByCtx,
                List<List<KnowledgeGraph.Edge>> edgesByCtx,
                List<Map<ContentDigest, GraphEvidence>> evidenceByCtx) {
            this.snapshot = snapshot;
            this.analyses = analyses;
            this.ctxEvidences = ctxEvidences;
            this.nodesByCtx = nodesByCtx;
            this.edgesByCtx = edgesByCtx;
            this.evidenceByCtx = evidenceByCtx;
        }

        static SyntheticMultiContextModel create(int moduleCount, int nodesPerModule, long seed) {
            var snap = SnapshotIdentity.from(REPO, ContentDigest.sha256Utf8("snap-seed-" + seed));
            var analyses = new ArrayList<AnalysisIdentity>(moduleCount);
            var ctxEvs = new ArrayList<GraphEvidence>(moduleCount);
            var nodesByCtx = new ArrayList<List<KnowledgeGraph.Node>>(moduleCount);
            var edgesByCtx = new ArrayList<List<KnowledgeGraph.Edge>>(moduleCount);
            var evByCtx = new ArrayList<Map<ContentDigest, GraphEvidence>>(moduleCount);

            for (int m = 0; m < moduleCount; m++) {
                var ctxEv = GraphEvidence.capture("analysis.manifest", Map.of("module", "mod-" + m, "seed", seed));
                var analysis = AnalysisIdentity.fromCanonicalManifestInputs(ctxEv.canonicalJson());
                analyses.add(analysis);
                ctxEvs.add(ctxEv);

                var ctxNodes = new ArrayList<KnowledgeGraph.Node>(nodesPerModule);
                var ctxEdges = new ArrayList<KnowledgeGraph.Edge>(nodesPerModule);
                var localEv = new TreeMap<ContentDigest, GraphEvidence>();
                localEv.put(ctxEv.identity(), ctxEv);

                for (int i = 0; i < nodesPerModule; i++) {
                    var ev = GraphEvidence.capture("java.observation", Map.of(
                            "module", "mod-" + m,
                            "file", "mod-" + m + "/src/main/java/com/acme/m" + m + "/Type" + i + ".java",
                            "symbol", "com.acme.m" + m + ".Type" + i + "#handle(java.util.List,java.lang.String)",
                            "detail", "deterministic-payload-segment-" + m + "-" + i));
                    localEv.put(ev.identity(), ev);
                    String nodeId = KnowledgeGraph.derivedIdentity("type", "m" + m + "-n" + i);
                    ctxNodes.add(new KnowledgeGraph.Node(nodeId, analysis.value(), "TYPE", List.of(ev.identity())));
                }
                for (int i = 0; i < nodesPerModule; i++) {
                    String src = ctxNodes.get(i).identity();
                    String tgt = ctxNodes.get((i + 1) % nodesPerModule).identity();
                    var evId = ctxNodes.get(i).evidence().getFirst();
                    KnowledgeGraph.Target target;
                    if (i % 7 == 0 && nodesPerModule >= 3) {
                        target = new KnowledgeGraph.Target(
                                KnowledgeGraph.TargetState.CANDIDATES,
                                List.of(tgt, ctxNodes.get((i + 2) % nodesPerModule).identity()),
                                Optional.empty());
                    } else if (i % 11 == 0) {
                        target = new KnowledgeGraph.Target(
                                KnowledgeGraph.TargetState.UNRESOLVED,
                                List.of(),
                                Optional.of("external.UnresolvedType" + i));
                    } else {
                        target = new KnowledgeGraph.Target(
                                KnowledgeGraph.TargetState.RESOLVED,
                                List.of(tgt),
                                Optional.empty());
                    }
                    Optional<String> region = (i % 5 == 0)
                            ? Optional.of(ctxNodes.get(0).identity())
                            : Optional.empty();
                    ctxEdges.add(new KnowledgeGraph.Edge(
                            KnowledgeGraph.derivedIdentity("edge", "m" + m + "-e" + i),
                            analysis.value(),
                            "java.calls",
                            src,
                            target,
                            List.of(evId),
                            region));
                }
                nodesByCtx.add(ctxNodes);
                edgesByCtx.add(ctxEdges);
                evByCtx.add(localEv);
            }

            var model = new SyntheticMultiContextModel(snap, analyses, ctxEvs, nodesByCtx, edgesByCtx, evByCtx);
            // Add one cross-context boundary edge inside ctx 0 to verify cross-context leaf handling.
            if (moduleCount >= 2 && nodesPerModule >= 2) {
                var crossEv = GraphEvidence.capture("service.contract", Map.of("protocol", "http", "path", "/v1/sync"));
                model.crossOrOrphanEv.put(crossEv.identity(), crossEv);
                model.crossEdges.add(new KnowledgeGraph.Edge(
                        KnowledgeGraph.derivedIdentity("cross-edge", "sync-0"),
                        analyses.get(0).value(),
                        "cross.depends-on",
                        nodesByCtx.get(0).get(0).identity(),
                        new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED,
                                List.of(nodesByCtx.get(0).get(1).identity()), Optional.empty()),
                        List.of(crossEv.identity()),
                        Optional.empty()));
            }
            return model;
        }

        SnapshotIdentity snapshotId() {
            return snapshot;
        }

        String contextId(int idx) {
            return analyses.get(idx).value();
        }

        void advanceSnapshot(String label) {
            this.snapshot = SnapshotIdentity.from(REPO, ContentDigest.sha256Utf8(label));
        }

        void addOrphanEvidence(String family, Map<String, Object> payload) {
            var ev = GraphEvidence.capture(family, payload);
            crossOrOrphanEv.put(ev.identity(), ev);
        }

        void mutateNodeAndEdge(int ctxIdx, int editNumber) {
            var nodes = nodesByCtx.get(ctxIdx);
            var edges = edgesByCtx.get(ctxIdx);
            var evMap = evidenceByCtx.get(ctxIdx);
            var updatedEv = GraphEvidence.capture("java.observation", Map.of(
                    "module", "mod-" + ctxIdx,
                    "edit", editNumber,
                    "symbol", "com.acme.m" + ctxIdx + ".Type0#updated_" + editNumber));
            evMap.put(updatedEv.identity(), updatedEv);
            String nodeId = nodes.get(0).identity();
            nodes.set(0, new KnowledgeGraph.Node(nodeId, contextId(ctxIdx), "TYPE", List.of(updatedEv.identity())));
            var oldEdge = edges.get(0);
            edges.set(0, new KnowledgeGraph.Edge(oldEdge.identity(), oldEdge.context(), oldEdge.kind(),
                    oldEdge.source(), oldEdge.target(), List.of(updatedEv.identity()), oldEdge.region()));
        }

        void addNodeAndEdge(int ctxIdx, int editNumber) {
            var nodes = nodesByCtx.get(ctxIdx);
            var edges = edgesByCtx.get(ctxIdx);
            var evMap = evidenceByCtx.get(ctxIdx);
            var addedEv = GraphEvidence.capture("java.observation", Map.of(
                    "module", "mod-" + ctxIdx,
                    "addedInEdit", editNumber,
                    "symbol", "com.acme.m" + ctxIdx + ".Added" + editNumber));
            evMap.put(addedEv.identity(), addedEv);
            String newNodeId = KnowledgeGraph.derivedIdentity("type-added", ctxIdx + "-" + editNumber);
            nodes.add(new KnowledgeGraph.Node(newNodeId, contextId(ctxIdx), "TYPE", List.of(addedEv.identity())));
            edges.add(new KnowledgeGraph.Edge(
                    KnowledgeGraph.derivedIdentity("edge-added", ctxIdx + "-" + editNumber),
                    contextId(ctxIdx),
                    "java.calls",
                    newNodeId,
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED,
                            List.of(nodes.get(0).identity()), Optional.empty()),
                    List.of(addedEv.identity()),
                    Optional.empty()));
        }

        void setGap(int ctxIdx, String reasonCode, long occurrenceCount, String subjectFile) {
            var nodes = nodesByCtx.get(ctxIdx);
            var evMap = evidenceByCtx.get(ctxIdx);
            String gapNodeId = KnowledgeGraph.derivedIdentity("gap", "ctx-" + ctxIdx);
            var limitations = occurrenceCount > 1L
                    ? List.of("occurrenceCount=" + occurrenceCount, "subject=" + subjectFile)
                    : List.of("subject=" + subjectFile);
            var gapEv = GraphEvidence.capture("evidence.gap", Map.of(
                    "reasonCode", reasonCode,
                    "subject", subjectFile,
                    "limitations", limitations));
            evMap.put(gapEv.identity(), gapEv);
            nodes.removeIf(n -> n.identity().equals(gapNodeId));
            nodes.add(new KnowledgeGraph.Node(gapNodeId, contextId(ctxIdx), "CAPABILITY_GAP", List.of(gapEv.identity())));
            gapOccByCtx.put(ctxIdx, occurrenceCount);
        }

        long expectedTotalGapOccurrences() {
            return gapOccByCtx.values().stream().mapToLong(Long::longValue).sum();
        }

        GraphBinaryCache.ContextPartitionData partitionData(int ctxIdx) {
            var ctx = new KnowledgeGraph.Context(
                    contextId(ctxIdx), snapshot, Optional.of(analyses.get(ctxIdx)), ctxEvidences.get(ctxIdx).identity());
            var nodes = nodesByCtx.get(ctxIdx);
            var edges = edgesByCtx.get(ctxIdx);
            var evMap = evidenceByCtx.get(ctxIdx);
            var needed = new TreeSet<ContentDigest>();
            needed.add(ctx.evidence());
            for (var n : nodes) needed.addAll(n.evidence());
            for (var e : edges) needed.addAll(e.evidence());
            var localEv = new ArrayList<GraphEvidence>(needed.size());
            for (var id : needed) {
                GraphEvidence ev = evMap.get(id);
                if (ev != null) localEv.add(ev);
            }
            return new GraphBinaryCache.ContextPartitionData(ctx, nodes, edges, localEv);
        }

        KnowledgeGraph toKnowledgeGraph() {
            var contexts = new ArrayList<KnowledgeGraph.Context>(analyses.size());
            var allNodes = new ArrayList<KnowledgeGraph.Node>();
            var allEdges = new ArrayList<KnowledgeGraph.Edge>();
            var allEvidence = new TreeMap<ContentDigest, GraphEvidence>();
            for (int m = 0; m < analyses.size(); m++) {
                var part = partitionData(m);
                contexts.add(part.context());
                allNodes.addAll(part.nodes());
                allEdges.addAll(part.intraContextEdges());
                for (var ev : part.evidence()) allEvidence.putIfAbsent(ev.identity(), ev);
            }
            allEdges.addAll(crossEdges);
            for (var ev : crossOrOrphanEv.values()) allEvidence.putIfAbsent(ev.identity(), ev);
            return new KnowledgeGraph(snapshot, contexts, allNodes, allEdges, List.copyOf(allEvidence.values()));
        }
    }
}
