package com.evolution.analysis.execution.scip;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.SourceSpan;
import com.evolution.analysis.execution.scip.ScipProtobufReader.*;
import com.evolution.analysis.execution.scip.ScipSemanticCaptureAdapter.*;
import static com.evolution.analysis.execution.scip.ScipProtobufReader.*;
import com.evolution.analysis.frontend.JavaSymbolName;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.query.ArchitectureQueryService;
import com.evolution.analysis.query.projection.DsmMatrixBuilder;
import com.evolution.analysis.query.projection.GraphProjectionService;
import com.evolution.analysis.query.projection.TarjanSccAnalyzer;
import com.evolution.analysis.query.projection.VisualGraph;
import com.evolution.analysis.query.trace.VerticalTraceEngine;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Moonshot #5 Unit Tests for Pure-Java {@link ScipProtobufReader} &
 * {@link ScipSemanticCaptureAdapter} plus Reproducible Mega-Repo Benchmark v2.
 */
class ScipAndMegaRepoBenchmarkTest {

    private static final int MAX_MACRO_JSON_BYTES = 50 * 1024;

    // ─── Part 1: Pure-Java SCIP Protobuf Decode, SHA-256 Lineage, Guard & Determinism ───

    @Test
    void decodesPolyglotScipIndexUpgradesToCompilerAndEnforcesDeterministicReplay() {
        ScipIndex index = buildPolyglotSampleIndex();
        byte[] wireBytes = ScipProtobufReader.encodeIndex(index);
        ContentDigest expectedSha = ContentDigest.sha256(wireBytes);

        var request = new ScipIngestRequest(wireBytes, Optional.of(expectedSha), Optional.of("ci-capture/index.scip"));
        ScipIngestResult run1 = ScipSemanticCaptureAdapter.ingest(request);
        ScipIngestResult run2 = ScipSemanticCaptureAdapter.ingest(request);

        assertEquals(expectedSha, run1.artifactSha256());
        assertEquals("scip-multi", run1.toolName());
        assertEquals("0.4.2", run1.toolVersion());
        assertEquals(4, run1.documentCount());
        assertEquals(4, run1.symbolCount());
        assertEquals(4, run1.upgradedToCompilerCount());
        assertEquals(9, run1.occurrenceCount());
        assertEquals(0, run1.ambiguousDefinitionCount());
        assertEquals(TrustTier.COMPILER.name(), run1.trustTier());

        // Verify all 4 languages upgraded SOURCE_SYNTAX -> COMPILER with boundCallTargets=true
        var languages = run1.symbols().stream().map(ScipSymbolUpgrade::language).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("typescript", "python", "go", "java"), languages);
        for (ScipSymbolUpgrade up : run1.symbols()) {
            assertEquals(TrustTier.SOURCE_SYNTAX, up.previousTier());
            assertEquals(TrustTier.COMPILER, up.trustTier());
            assertTrue(up.boundCallTargets());
            assertTrue(up.startLine() >= 1);
            assertTrue(up.endLine() >= up.startLine());
        }

        // Verify reference counting and relationship targets
        ScipSymbolUpgrade pyService = run1.symbols().stream()
                .filter(s -> "python".equals(s.language()))
                .findFirst()
                .orElseThrow();
        assertEquals(1, pyService.referenceCount());
        assertEquals(List.of("scip-go gomod github.com/acme/ledger/Gateway#"), pyService.relationshipTargets());

        // Verify unresolved external symbol gap is explicitly preserved (closed denominator I3)
        assertTrue(run1.coverageGaps().stream().anyMatch(g ->
                "UNRESOLVED_EXTERNAL_SYMBOL".equals(g.reasonCode())
                        && "scip-npm npm axios 1.7.0 AxiosInstance#get().".equals(g.symbolOrPath())));

        // Verify JSON-safe view keys and 2-run bit-identical CanonicalJson output
        Map<String, Object> view1 = run1.toView();
        assertEquals(
                Set.of("artifactSha256", "toolName", "toolVersion", "documentCount", "symbolCount",
                        "upgradedToCompilerCount", "occurrenceCount", "ambiguousDefinitionCount",
                        "coverageGaps", "samples", "trustTier"),
                view1.keySet());
        assertEquals(expectedSha.value(), view1.get("artifactSha256"));
        assertEquals("COMPILER", view1.get("trustTier"));

        assertEquals(CanonicalJson.write(run1), CanonicalJson.write(run2));
        assertEquals(CanonicalJson.write(view1), CanonicalJson.write(run2.toView()));
    }

    @Test
    void rejectsTamperedSha256AndMalformedScipPayloadsCleanly() {
        byte[] wireBytes = ScipProtobufReader.encodeIndex(buildPolyglotSampleIndex());
        ContentDigest wrongDigest = ContentDigest.sha256Utf8("tampered-scip-bytes");

        IllegalArgumentException mismatch = assertThrows(IllegalArgumentException.class, () ->
                ScipSemanticCaptureAdapter.ingest(new ScipIngestRequest(
                        wireBytes, Optional.of(wrongDigest), Optional.empty())));
        assertTrue(mismatch.getMessage().contains("SCIP_SHA256_MISMATCH"));

        // 1-bit tamper with original digest -> rejected
        byte[] tampered = Arrays.copyOf(wireBytes, wireBytes.length);
        tampered[tampered.length - 1] ^= 0x01;
        ContentDigest origDigest = ContentDigest.sha256(wireBytes);
        IllegalArgumentException bitFlip = assertThrows(IllegalArgumentException.class, () ->
                ScipSemanticCaptureAdapter.ingest(new ScipIngestRequest(
                        tampered, Optional.of(origDigest), Optional.empty())));
        assertTrue(bitFlip.getMessage().contains("SCIP_SHA256_MISMATCH"));

        // Empty / truncated / invalid wire-type / invalid UTF-8 payloads -> IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> ScipProtobufReader.read(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> ScipProtobufReader.read(Arrays.copyOf(wireBytes, wireBytes.length - 3)));
        assertThrows(IllegalArgumentException.class, () -> ScipProtobufReader.read("not-a-protobuf-stream".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> ScipProtobufReader.read(new byte[]{(byte) 0x0B})); // wire type 3 (group)

        // ZigZag wire helper sanity
        assertEquals(-42, ScipProtobufReader.decodeZigZag32(ScipProtobufReader.encodeZigZag32(-42)));
        assertEquals(-1_000_000_000_000L, ScipProtobufReader.decodeZigZag64(ScipProtobufReader.encodeZigZag64(-1_000_000_000_000L)));
    }

    @Test
    void singleDefinitionGuardKeepsLexicographicallyFirstAndRecordsAmbiguityGap() {
        String duplicatedSymbol = "scip-java maven com.acme/OrderService#";
        Document docB = new Document(
                "java",
                "src/z_copy/OrderService.java",
                List.of(new Occurrence(List.of(24, 13, 25), duplicatedSymbol, ROLE_DEFINITION, 0, List.of())),
                List.of(new SymbolInformation(duplicatedSymbol, List.of(), List.of(), 7, "OrderService", Optional.empty(), "")),
                0);
        Document docA = new Document(
                "java",
                "src/a_main/OrderService.java",
                List.of(new Occurrence(List.of(9, 13, 25), duplicatedSymbol, ROLE_DEFINITION, 0, List.of())),
                List.of(new SymbolInformation(duplicatedSymbol, List.of(), List.of(), 7, "OrderService", Optional.empty(), "")),
                0);

        byte[] bytes = ScipProtobufReader.encodeIndex(new ScipIndex(
                new Metadata(1, new ToolInfo("scip-java", "0.9.0", List.of()), "file:///workspace", 1),
                List.of(docB, docA), // Deliberately out of lexicographical order
                List.of()));

        ScipIngestResult result = ScipSemanticCaptureAdapter.ingest(
                new ScipIngestRequest(bytes, Optional.of(ContentDigest.sha256(bytes)), Optional.empty()));

        assertEquals(1, result.ambiguousDefinitionCount());
        assertEquals(1, result.symbolCount());
        ScipSymbolUpgrade kept = result.symbols().getFirst();
        assertEquals("src/a_main/OrderService.java", kept.relativePath());
        assertEquals(10, kept.startLine());
        assertTrue(result.coverageGaps().stream().anyMatch(g ->
                "AMBIGUOUS_MULTIPLE_DEFINITIONS".equals(g.reasonCode())
                        && duplicatedSymbol.equals(g.symbolOrPath())
                        && g.detail().contains("src/z_copy/OrderService.java:25")));
    }

    // ─── Part 2: Reproducible Mega-Repo Benchmark v2 (100 / 1,000 / 5,000 / 10,000 Nodes + 500-Doc SCIP) ───

    @Test
    void reproducibleMegaRepoBenchmarkV2() {
        int[] scales = {100, 1_000, 5_000, 10_000};

        // Light JVM warmup on tiny 50-node workload before timing
        runScaleBenchmark(50, false);

        var sb = new StringBuilder();
        sb.append("\n=== MEGA_REPO_BENCHMARK_V2_START ===\n");
        sb.append("scale_nodes,total_graph_nodes,total_graph_edges,tarjan_ms,scc_blocks,cyclic_nodes,"
                + "projection_level,projection_ms,projection_nodes,projection_edges,projection_json_bytes,"
                + "dsm_ms,dsm_items,dsm_cells,dsm_levelization,trace_ms,trace_hops,trace_layers\n");

        for (int scale : scales) {
            ScaleMetrics m = runScaleBenchmark(scale, true);
            if (scale >= 1_000) {
                assertEquals("MACRO", m.resolvedLevel(), "Expected MACRO auto-projection at scale " + scale);
                assertTrue(m.projectionJsonBytes() < MAX_MACRO_JSON_BYTES,
                        "Expected < 50 KiB MACRO JSON at scale " + scale + ", got " + m.projectionJsonBytes());
            } else {
                assertEquals("MICRO", m.resolvedLevel(), "Expected MICRO auto-projection at scale 100");
            }
            assertTrue(m.cyclicNodes() > 0, "Planted cycles should be detected at scale " + scale);
            assertTrue(m.traceLayers() >= 5, "Vertical trace should span >= 5 architectural layers at scale " + scale);
            sb.append(String.format(Locale.ROOT,
                    "%d,%d,%d,%.3f,%d,%d,%s,%.3f,%d,%d,%d,%.3f,%d,%d,%.4f,%.3f,%d,%d%n",
                    m.scale(), m.totalGraphNodes(), m.totalGraphEdges(),
                    m.tarjanMs(), m.sccBlocks(), m.cyclicNodes(),
                    m.resolvedLevel(), m.projectionMs(), m.projectionNodes(), m.projectionEdges(), m.projectionJsonBytes(),
                    m.dsmMs(), m.dsmItems(), m.dsmCells(), m.dsmLevelization(),
                    m.traceMs(), m.traceHops(), m.traceLayers()));
        }

        // 500-doc multi-language SCIP archive benchmark
        ScipBenchmarkMetrics scipMetrics = run500DocScipBenchmark();
        assertEquals(500, scipMetrics.documentCount());
        assertEquals(2_000, scipMetrics.symbolCount());
        assertEquals(2_000, scipMetrics.upgradedCount());
        assertEquals(4_000, scipMetrics.occurrenceCount());
        assertEquals(0, scipMetrics.ambiguousCount());

        sb.append(String.format(Locale.ROOT,
                "SCIP_500_DOCS: docs=%d, symbols=%d, upgraded=%d, occurrences=%d, gaps=%d, "
                        + "wire_bytes=%d, decode_ingest_ms=%.3f, canonical_json_ms=%.3f, throughput_occ_per_sec=%.0f%n",
                scipMetrics.documentCount(),
                scipMetrics.symbolCount(),
                scipMetrics.upgradedCount(),
                scipMetrics.occurrenceCount(),
                scipMetrics.gapCount(),
                scipMetrics.wireBytes(),
                scipMetrics.ingestMs(),
                scipMetrics.jsonMs(),
                scipMetrics.occPerSec()));
        sb.append("=== MEGA_REPO_BENCHMARK_V2_END ===\n");

        System.out.print(sb);
    }

    private record ScaleMetrics(
            int scale,
            int totalGraphNodes,
            int totalGraphEdges,
            double tarjanMs,
            int sccBlocks,
            int cyclicNodes,
            String resolvedLevel,
            double projectionMs,
            int projectionNodes,
            int projectionEdges,
            int projectionJsonBytes,
            double dsmMs,
            int dsmItems,
            int dsmCells,
            double dsmLevelization,
            double traceMs,
            int traceHops,
            int traceLayers
    ) {}

    private record ScipBenchmarkMetrics(
            int documentCount,
            int symbolCount,
            int upgradedCount,
            int occurrenceCount,
            int ambiguousCount,
            int gapCount,
            int wireBytes,
            double ingestMs,
            double jsonMs,
            double occPerSec
    ) {}

    private static ScaleMetrics runScaleBenchmark(int scale, boolean verifyDeterminism) {
        SyntheticMegaGraph built = buildMegaRepoGraph(scale);
        KnowledgeGraph graph = built.graph();
        ContentDigest digestBefore = graph.identity();

        // 1. Tarjan SCC O(V+E)
        long t0 = System.nanoTime();
        TarjanSccAnalyzer.SccResult scc = TarjanSccAnalyzer.analyze(built.typeIds(), built.tarjanEdges());
        double tarjanMs = (System.nanoTime() - t0) / 1_000_000.0;

        // 2. GraphProjectionService.projectMultiScale(AUTO)
        var queryService = new ArchitectureQueryService(graph);
        var projectionService = new GraphProjectionService(queryService);
        var req = GraphProjectionService.MultiScaleRequest.defaults(built.javaContext());

        long t1 = System.nanoTime();
        VisualGraph visual = projectionService.projectMultiScale(graph, req);
        String visualJson = CanonicalJson.write(visual.toCytoscape());
        double projectionMs = (System.nanoTime() - t1) / 1_000_000.0;
        int jsonBytes = visualJson.getBytes(StandardCharsets.UTF_8).length;

        if (verifyDeterminism) {
            VisualGraph visualReplay = projectionService.projectMultiScale(graph, req);
            assertEquals(visualJson, CanonicalJson.write(visualReplay.toCytoscape()));
        }

        // 3. DsmMatrixBuilder.build(PACKAGE)
        var dsmReq = DsmMatrixBuilder.DsmRequest.defaults(built.javaContext(), DsmMatrixBuilder.Granularity.PACKAGE);
        long t2 = System.nanoTime();
        DsmMatrixBuilder.DsmMatrix dsm = DsmMatrixBuilder.build(graph, dsmReq);
        double dsmMs = (System.nanoTime() - t2) / 1_000_000.0;

        // 4. VerticalTraceEngine.trace (FE -> API -> Spring Controller -> Spring DI -> Service -> JPA)
        var traceReq = VerticalTraceEngine.TraceRequest.defaults(built.javaContext(), "OrderController");
        long t3 = System.nanoTime();
        VerticalTraceEngine.VerticalTraceResult trace = VerticalTraceEngine.trace(graph, traceReq);
        double traceMs = (System.nanoTime() - t3) / 1_000_000.0;

        // Ensure zero mutation of canonical KnowledgeGraph
        assertEquals(digestBefore, graph.identity());

        String resolvedLevel = String.valueOf(visual.metadata().get("resolvedLevel"));
        return new ScaleMetrics(
                scale,
                graph.nodes().size(),
                graph.edges().size(),
                tarjanMs,
                scc.blocks().size(),
                scc.cyclicNodeIds().size(),
                resolvedLevel,
                projectionMs,
                visual.nodes().size(),
                visual.edges().size(),
                jsonBytes,
                dsmMs,
                dsm.order().size(),
                dsm.cells().size(),
                dsm.levelizationScore().doubleValue(),
                traceMs,
                trace.totalHops(),
                trace.layers().size());
    }

    private static ScipBenchmarkMetrics run500DocScipBenchmark() {
        String[] langs = {"typescript", "python", "go", "java"};
        String[] exts = {".ts", ".py", ".go", ".java"};
        var docs = new ArrayList<Document>(500);

        for (int d = 0; d < 500; d++) {
            int langIdx = d % langs.length;
            String lang = langs[langIdx];
            String path = String.format(Locale.ROOT, "services/mod-%02d/src/unit_%03d%s", d % 12, d, exts[langIdx]);
            var symbols = new ArrayList<SymbolInformation>(4);
            var occurrences = new ArrayList<Occurrence>(8);

            for (int s = 0; s < 4; s++) {
                String sym = String.format(Locale.ROOT, "scip-%s pkg mod-%02d/Unit%03d#Sym%d#", lang, d % 12, d, s);
                int nextDoc = (d + 1) % 500;
                String nextLang = langs[nextDoc % langs.length];
                String targetSym = String.format(Locale.ROOT, "scip-%s pkg mod-%02d/Unit%03d#Sym0#", nextLang, nextDoc % 12, nextDoc);

                var rels = (s == 0)
                        ? List.of(new Relationship(targetSym, true, false, false, false),
                                  new Relationship("scip-ext npm shared-sdk 1.0.0 Client#", true, false, false, false))
                        : List.<Relationship>of();
                symbols.add(new SymbolInformation(sym, List.of(), rels, 7, "Unit" + d + "Sym" + s, Optional.empty(), ""));
                occurrences.add(new Occurrence(List.of(10 + s * 5, 4, 20), sym, ROLE_DEFINITION, 0, List.of()));
                // Reference to target in next doc
                occurrences.add(new Occurrence(List.of(12 + s * 5, 8, 24), targetSym, ROLE_READ_ACCESS, 0, List.of()));
            }
            docs.add(new Document(lang, path, occurrences, symbols, 1));
        }

        ScipIndex index = new ScipIndex(
                new Metadata(1, new ToolInfo("scip-polyglot-capture", "1.0.0", List.of("--offline")), "file:///mega-repo", 1),
                docs,
                List.of());
        byte[] wireBytes = ScipProtobufReader.encodeIndex(index);
        ContentDigest digest = ContentDigest.sha256(wireBytes);

        // Warmup once
        ScipSemanticCaptureAdapter.ingest(new ScipIngestRequest(wireBytes, Optional.of(digest), Optional.of("mega-500")));

        long t0 = System.nanoTime();
        ScipIngestResult result = ScipSemanticCaptureAdapter.ingest(
                new ScipIngestRequest(wireBytes, Optional.of(digest), Optional.of("mega-500")));
        double ingestMs = (System.nanoTime() - t0) / 1_000_000.0;

        long t1 = System.nanoTime();
        String json = CanonicalJson.write(result.toView());
        double jsonMs = (System.nanoTime() - t1) / 1_000_000.0;
        assertFalse(json.isEmpty());

        double occPerSec = result.occurrenceCount() / Math.max(0.0001, ingestMs / 1000.0);
        return new ScipBenchmarkMetrics(
                result.documentCount(),
                result.symbolCount(),
                result.upgradedToCompilerCount(),
                result.occurrenceCount(),
                result.ambiguousDefinitionCount(),
                result.coverageGaps().size(),
                wireBytes.length,
                ingestMs,
                jsonMs,
                occPerSec);
    }

    private static ScipIndex buildPolyglotSampleIndex() {
        String tsSym = "scip-npm npm web-client 1.0.0 src/`api.ts`/fetchOrders().";
        String pySym = "scip-python python ml-scorer 0.1.0 `scorer.py`/RiskScorer#";
        String goSym = "scip-go gomod github.com/acme/ledger/Gateway#";
        String javaSym = "scip-java maven com.acme/OrderController#";
        String externalAxios = "scip-npm npm axios 1.7.0 AxiosInstance#get().";

        Document tsDoc = new Document(
                "typescript",
                "web/src/api.ts",
                List.of(
                        new Occurrence(List.of(4, 16, 27), tsSym, ROLE_DEFINITION, 0, List.of()),
                        new Occurrence(List.of(6, 12, 15), externalAxios, ROLE_READ_ACCESS, 0, List.of()),
                        new Occurrence(List.of(7, 4, 19), javaSym, ROLE_READ_ACCESS, 0, List.of())),
                List.of(new SymbolInformation(
                        tsSym,
                        List.of("Fetches orders from backend API."),
                        List.of(new Relationship(javaSym, true, false, false, false)),
                        12,
                        "fetchOrders",
                        Optional.empty(),
                        "")),
                1);

        Document pyDoc = new Document(
                "python",
                "ml/scorer.py",
                List.of(new Occurrence(List.of(9, 6, 16), pySym, ROLE_DEFINITION, 0, List.of())),
                List.of(new SymbolInformation(
                        pySym,
                        List.of(),
                        List.of(new Relationship(goSym, true, false, false, false)),
                        7,
                        "RiskScorer",
                        Optional.empty(),
                        "")),
                1);

        Document goDoc = new Document(
                "go",
                "gateway/gateway.go",
                List.of(
                        new Occurrence(List.of(14, 5, 12), goSym, ROLE_DEFINITION, 0, List.of()),
                        new Occurrence(List.of(22, 4, 14), pySym, ROLE_READ_ACCESS, 0, List.of())),
                List.of(new SymbolInformation(
                        goSym,
                        List.of(),
                        List.of(new Relationship(javaSym, false, true, false, false)),
                        21,
                        "Gateway",
                        Optional.empty(),
                        "")),
                1);

        Document javaDoc = new Document(
                "java",
                "backend/src/main/java/com/acme/OrderController.java",
                List.of(
                        new Occurrence(List.of(19, 13, 28), javaSym, ROLE_DEFINITION, 0, List.of()),
                        new Occurrence(List.of(30, 8, 19), tsSym, ROLE_READ_ACCESS, 0, List.of()),
                        new Occurrence(List.of(35, 8, 15), goSym, ROLE_READ_ACCESS, 0, List.of())),
                List.of(new SymbolInformation(
                        javaSym,
                        List.of(),
                        List.of(),
                        7,
                        "OrderController",
                        Optional.empty(),
                        "")),
                1);

        return new ScipIndex(
                new Metadata(1, new ToolInfo("scip-multi", "0.4.2", List.of("--offline")), "file:///workspace/mega", 1),
                List.of(tsDoc, pyDoc, goDoc, javaDoc),
                List.of(new SymbolInformation(externalAxios, List.of(), List.of(), 12, "AxiosInstance.get", Optional.empty(), "")));
    }

    private record SyntheticMegaGraph(
            KnowledgeGraph graph,
            String javaContext,
            List<String> typeIds,
            List<TarjanSccAnalyzer.DirectedEdge> tarjanEdges
    ) {}

    private static SyntheticMegaGraph buildMegaRepoGraph(int typeCount) {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/mega-benchmark-v2-" + typeCount);
        var snapshot = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("mega-v2-snap-" + typeCount));
        var analysis = AnalysisIdentity.fromCanonicalManifestInputs("mega-v2-java-" + typeCount);

        String snapCtx = snapshot.value();
        String javaCtx = analysis.value();
        String springCtx = KnowledgeGraph.derivedIdentity("spring-context", List.of(javaCtx, "spring"));

        var evidence = new ArrayList<GraphEvidence>(typeCount + 64);
        var nodes = new ArrayList<KnowledgeGraph.Node>(typeCount + 128);
        var edges = new ArrayList<KnowledgeGraph.Edge>(typeCount * 4 + 128);
        var typeIds = new ArrayList<String>(typeCount);
        var tarjanEdges = new ArrayList<TarjanSccAnalyzer.DirectedEdge>(typeCount * 3 + 32);

        GraphEvidence snapCtxEv = GraphEvidence.capture("repository.inventory-snapshot", Map.of("snapshot", snapCtx));
        GraphEvidence javaCtxEv = GraphEvidence.capture("analysis.manifest", Map.of("analysis", javaCtx));
        GraphEvidence springCtxEv = GraphEvidence.capture("spring.context", Map.of("analysis", javaCtx));
        evidence.add(snapCtxEv);
        evidence.add(javaCtxEv);
        evidence.add(springCtxEv);

        var contexts = List.of(
                new KnowledgeGraph.Context(snapCtx, snapshot, Optional.empty(), snapCtxEv.identity()),
                new KnowledgeGraph.Context(javaCtx, snapshot, Optional.of(analysis), javaCtxEv.identity()),
                new KnowledgeGraph.Context(springCtx, snapshot, Optional.of(analysis), springCtxEv.identity()));

        String docPath = "src/main/java/com/acme/MegaTypes.java";
        String docSha = ContentDigest.sha256Utf8("MegaTypes-" + typeCount).value();
        String docId = SourceDocumentIdentity.from(repo, docPath).value();
        GraphEvidence docEv = GraphEvidence.capture("source.document", Map.of(
                "identity", docId,
                "path", docPath,
                "contentDigest", docSha));
        evidence.add(docEv);
        nodes.add(new KnowledgeGraph.Node(docId, javaCtx, "SOURCE_DOCUMENT", List.of(docEv.identity())));

        GraphEvidence occEv = GraphEvidence.capture("java.occurrence", Map.of(
                "status", "RESOLVED",
                "derivation", "DIRECT",
                "span", new SourceSpan(SourceDocumentIdentity.from(repo, docPath), 1, 1, 1, 10)));
        evidence.add(occEv);

        int communityCount;
        if (typeCount <= 250) {
            communityCount = 8;
        } else if (typeCount <= 1_500) {
            communityCount = 16;
        } else if (typeCount <= 6_000) {
            communityCount = 32;
        } else {
            communityCount = 48;
        }
        int moduleCount = Math.max(2, communityCount / 2);

        var moduleIds = new String[moduleCount];
        var moduleScopes = new EntityScope[moduleCount];
        for (int m = 0; m < moduleCount; m++) {
            var mod = ModuleIdentity.from(repo, "module-" + m);
            moduleIds[m] = mod.value();
            moduleScopes[m] = EntityScope.project(mod);
            GraphEvidence modEv = GraphEvidence.capture("module.descriptor", Map.of("identity", mod, "path", "module-" + m));
            evidence.add(modEv);
            nodes.add(new KnowledgeGraph.Node(mod.value(), javaCtx, "MODULE", List.of(modEv.identity())));
        }

        var packageIds = new String[communityCount];
        var communityTypes = new ArrayList<List<String>>(communityCount);
        for (int c = 0; c < communityCount; c++) {
            int m = c % moduleCount;
            String pkgName = "com.acme.domain" + c;
            String pkgId = EntityIdentity.from(EntityOrigin.PROJECT, moduleScopes[m],
                    EntityKind.PACKAGE, JavaSymbolName.packageName(pkgName).canonicalName()).value();
            packageIds[c] = pkgId;
            GraphEvidence pkgEv = GraphEvidence.capture("java.declaration", Map.of("spelling", pkgName, "package", pkgName));
            evidence.add(pkgEv);
            nodes.add(new KnowledgeGraph.Node(pkgId, javaCtx, "PACKAGE", List.of(pkgEv.identity())));
            edges.add(new KnowledgeGraph.Edge(
                    KnowledgeGraph.derivedIdentity("contains", moduleIds[m] + "->" + pkgId),
                    javaCtx, "graph.contains", moduleIds[m],
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(pkgId), Optional.empty()),
                    List.of(javaCtxEv.identity()), Optional.empty()));
            communityTypes.add(new ArrayList<>());
        }

        // Deterministic project types
        String ctrlId = null;
        String svcId = null;
        String entId = null;

        for (int i = 0; i < typeCount; i++) {
            int c = i % communityCount;
            int m = c % moduleCount;
            String pkgName = "com.acme.domain" + c;
            String simpleName;
            List<String> annotations;
            if (i == 0) {
                simpleName = "OrderController";
                annotations = List.of("org.springframework.web.bind.annotation.RestController");
            } else if (i == 1) {
                simpleName = "OrderService";
                annotations = List.of("org.springframework.stereotype.Service");
            } else if (i == 2) {
                simpleName = "OrderEntity";
                annotations = List.of("jakarta.persistence.Entity");
            } else {
                simpleName = "Component" + i;
                annotations = List.of();
            }

            var entity = Entity.create(EntityOrigin.PROJECT, moduleScopes[m], EntityKind.TYPE,
                    JavaSymbolName.topLevelType(pkgName, simpleName).canonicalName(), Optional.empty());
            String id = entity.identity().value();

            Map<String, Object> evPayload = new LinkedHashMap<>();
            evPayload.put("entity", Map.of(
                    "canonicalName", entity.canonicalName(),
                    "origin", "PROJECT",
                    "stableScope", moduleIds[m],
                    "declaration", Map.of("document", docId, "startLine", 10 + (i % 500), "endLine", 30 + (i % 500))));
            evPayload.put("spelling", simpleName);
            if (!annotations.isEmpty()) {
                evPayload.put("annotations", annotations);
            }
            GraphEvidence typeEv = GraphEvidence.capture("java.declaration", evPayload);
            evidence.add(typeEv);
            nodes.add(new KnowledgeGraph.Node(id, javaCtx, "TYPE", List.of(typeEv.identity())));
            edges.add(new KnowledgeGraph.Edge(
                    KnowledgeGraph.derivedIdentity("contains", packageIds[c] + "->" + id),
                    javaCtx, "graph.contains", packageIds[c],
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(id), Optional.empty()),
                    List.of(typeEv.identity()), Optional.empty()));

            typeIds.add(id);
            communityTypes.get(c).add(id);
            if (i == 0) ctrlId = id;
            if (i == 1) svcId = id;
            if (i == 2) entId = id;
        }

        // Intra-community cohesive chords (density >= 0.07 so Leiden preserves clean communities + cycles)
        int edgeSeq = 0;
        for (int c = 0; c < communityCount; c++) {
            List<String> members = communityTypes.get(c);
            int sz = members.size();
            int outDeg = Math.min(sz - 1, Math.max(3, (int) Math.ceil(sz * 0.038)));
            for (int i = 0; i < sz; i++) {
                String src = members.get(i);
                for (int step = 1; step <= outDeg; step++) {
                    String dst = members.get((i + step) % sz);
                    edges.add(new KnowledgeGraph.Edge(
                            KnowledgeGraph.derivedIdentity("dep", src + "->" + dst + ":" + (edgeSeq++)),
                            javaCtx, "graph.depends-on", src,
                            new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(dst), Optional.empty()),
                            List.of(occEv.identity()), Optional.empty()));
                    tarjanEdges.add(new TarjanSccAnalyzer.DirectedEdge(src, dst, 1));
                }
            }
        }

        // Cross-community DAG + planted inter-community cycle (community 0 <-> community 1)
        for (int c = 1; c < communityCount; c++) {
            String src = communityTypes.get(c).getFirst();
            String dst = communityTypes.get(c - 1).getFirst();
            edges.add(new KnowledgeGraph.Edge(
                    KnowledgeGraph.derivedIdentity("dep", src + "->" + dst + ":" + (edgeSeq++)),
                    javaCtx, "graph.depends-on", src,
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(dst), Optional.empty()),
                    List.of(occEv.identity()), Optional.empty()));
            tarjanEdges.add(new TarjanSccAnalyzer.DirectedEdge(src, dst, 1));
        }
        String c0 = communityTypes.get(0).getLast();
        String c1 = communityTypes.get(1).getLast();
        edges.add(new KnowledgeGraph.Edge(
                KnowledgeGraph.derivedIdentity("dep", c0 + "->" + c1 + ":" + (edgeSeq++)),
                javaCtx, "graph.depends-on", c0,
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(c1), Optional.empty()),
                List.of(occEv.identity()), Optional.empty()));
        tarjanEdges.add(new TarjanSccAnalyzer.DirectedEdge(c0, c1, 1));

        // Vertical stack chain: Frontend (SCIP/Polyglot) -> Endpoint -> OrderController -> Spring DI -> OrderService -> OrderEntity
        String feId = KnowledgeGraph.derivedIdentity("polyglot-symbol", "OrderPage#fetchOrders");
        GraphEvidence feEv = GraphEvidence.capture("scip.symbol", Map.of(
                "path", "web/src/OrderPage.tsx",
                "language", "tsx",
                "contentDigest", ContentDigest.sha256Utf8("OrderPage.tsx").value(),
                "routePath", "/api/orders",
                "fact", Map.of("name", "OrderPage.fetchOrders", "span", Map.of("startLine", 14, "endLine", 22))));
        evidence.add(feEv);
        nodes.add(new KnowledgeGraph.Node(feId, snapCtx, "FRONTEND_COMPONENT", List.of(feEv.identity())));

        String endpointId = KnowledgeGraph.derivedIdentity("endpoint", "GET /api/orders");
        GraphEvidence epEv = GraphEvidence.capture("spring.endpoint-declaration", Map.of(
                "route", Map.of("path", "/api/orders", "methods", List.of("GET"),
                        "span", Map.of("document", docId, "startLine", 15, "endLine", 20))));
        evidence.add(epEv);
        nodes.add(new KnowledgeGraph.Node(endpointId, javaCtx, "ENDPOINT", List.of(epEv.identity())));
        edges.add(new KnowledgeGraph.Edge(
                KnowledgeGraph.derivedIdentity("contains", ctrlId + "->" + endpointId),
                javaCtx, "graph.contains", ctrlId,
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(endpointId), Optional.empty()),
                List.of(epEv.identity()), Optional.empty()));

        GraphEvidence ctrlBeanEv = GraphEvidence.capture("java.declaration", Map.of("spelling", "OrderController"));
        GraphEvidence svcBeanEv = GraphEvidence.capture("java.declaration", Map.of("spelling", "OrderService"));
        evidence.add(ctrlBeanEv);
        evidence.add(svcBeanEv);
        nodes.add(new KnowledgeGraph.Node(ctrlId, springCtx, "BEAN_PRODUCER", List.of(ctrlBeanEv.identity())));
        nodes.add(new KnowledgeGraph.Node(svcId, springCtx, "BEAN_PRODUCER", List.of(svcBeanEv.identity())));

        String diId = KnowledgeGraph.derivedIdentity("bean-candidate", "orderServiceBean");
        GraphEvidence diEv = GraphEvidence.capture("spring.candidate", Map.of("declaredNameKey", Map.of("primary", "orderServiceBean")));
        evidence.add(diEv);
        nodes.add(new KnowledgeGraph.Node(diId, springCtx, "BEAN_DEFINITION_CANDIDATE", List.of(diEv.identity())));

        edges.add(new KnowledgeGraph.Edge(
                KnowledgeGraph.derivedIdentity("spring-bind", ctrlId + "->" + diId),
                springCtx, "spring.selected-binding", ctrlId,
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(diId), Optional.empty()),
                List.of(diEv.identity()), Optional.empty()));
        edges.add(new KnowledgeGraph.Edge(
                KnowledgeGraph.derivedIdentity("spring-prod", diId + "->" + svcId),
                springCtx, "spring.produces-candidate", diId,
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(svcId), Optional.empty()),
                List.of(diEv.identity()), Optional.empty()));
        edges.add(new KnowledgeGraph.Edge(
                KnowledgeGraph.derivedIdentity("dep", svcId + "->" + entId + ":vertical"),
                javaCtx, "graph.depends-on", svcId,
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(entId), Optional.empty()),
                List.of(occEv.identity()), Optional.empty()));

        var graph = new KnowledgeGraph(snapshot, contexts, nodes, edges, evidence);
        return new SyntheticMegaGraph(graph, javaCtx, typeIds, tarjanEdges);
    }
}
