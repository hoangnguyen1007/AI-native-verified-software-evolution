package com.evolution.analysis.filesystem;

import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.graph.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Stage-4 regression tests covering Priority 0/1/2 storage integrity, cache soundness, and HTTPS hardening. */
class StorageIntegrityHardeningTest {
    @TempDir
    Path temp;

    @Test
    void partitionedGraphStoreMatchesFileGraphStoreSemanticIdentityExactly() throws Exception {
        KnowledgeGraph graph = FileGraphStoreTest.graph();
        Path fileDir = Files.createDirectory(temp.resolve("file-store"));
        Path partDir = Files.createDirectory(temp.resolve("part-store"));

        var fileStore = new FileGraphStore(fileDir);
        fileStore.save(graph);
        var partStore = new PartitionedGraphStore(partDir);
        partStore.save(graph, () -> false);

        GraphManifest fileManifest = fileStore.manifest(graph.identity()).orElseThrow();
        GraphManifest partManifest = partStore.manifest(graph.identity()).orElseThrow();
        assertEquals(graph.semanticIdentity(), fileManifest.semanticIdentity());
        assertEquals(fileManifest.semanticIdentity(), partManifest.semanticIdentity(),
                "PartitionedGraphStore.manifest() must compute true semanticIdentity matching FileGraphStore");
        assertEquals(fileManifest.contentIdentity(), partManifest.contentIdentity());
        assertEquals(fileManifest.nodes(), partManifest.nodes());
        assertEquals(fileManifest.edges(), partManifest.edges());
        assertEquals(fileManifest.evidencePayloads(), partManifest.evidencePayloads());
    }

    @Test
    void sqliteGraphProjectionIgnoresNestedReasonCodeAndCountsTopLevelReasonCode() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("nested-reason.sqlite"))) {
            try (var sql = db.createStatement()) {
                sql.execute("CREATE TABLE rows(space TEXT NOT NULL,k TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(space,k)) WITHOUT ROWID");
            }
            try (var projection = new SqliteGraphProjection(db, 4096, () -> false)) {
                var evidence = projection.records("evidence", GraphSnapshotCodec::evidence);
                var nodes = projection.records("nodes", GraphSnapshotCodec::node);
                // Put nested "details":{"reasonCode":"INNER_NESTED"} alphabetically BEFORE top-level "reasonCode":"OUTER_REAL"
                var proof = GraphEvidence.capture("evidence.gap", Map.of(
                        "details", Map.of("reasonCode", "INNER_NESTED"),
                        "reasonCode", "OUTER_REAL"));
                evidence.put(proof.identity().value(), proof);
                String id = ContentDigest.sha256Utf8("gap-nested").value();
                String ctx = ContentDigest.sha256Utf8("ctx-nested").value();
                nodes.put(ctx + "|" + id, new KnowledgeGraph.Node(id, ctx, "CAPABILITY_GAP", List.of(proof.identity())));

                var counts = projection.gapCounts();
                assertEquals(1, counts.nodes());
                assertEquals(Map.of("OUTER_REAL", 1L), counts.reasons(),
                        "Nested reasonCode must never hijack top-level gap reasonCode");
            }
        }
    }

    @Test
    void graphBinaryCacheParallelChunkCompressionRoundTripsLargeGraphIdentically() throws Exception {
        // Build a large graph (> 400 KiB uncompressed) to exercise S10 multi-chunk parallel deflate/inflate.
        var base = FileGraphStoreTest.graph();
        var ctx = base.contexts().getFirst().identity();
        var nodes = new ArrayList<>(base.nodes());
        var edges = new ArrayList<>(base.edges());
        var evidence = new ArrayList<>(base.evidence());
        for (int i = 0; i < 600; i++) {
            var ev = GraphEvidence.capture("fixture.large-chunk", Map.of(
                    "index", i,
                    "payload", "deterministic-chunk-content-" + i + "-" + "x".repeat(420)));
            evidence.add(ev);
            String nid = KnowledgeGraph.derivedIdentity("chunk-node", List.of(ctx, Integer.toString(i)));
            nodes.add(new KnowledgeGraph.Node(nid, ctx, "TYPE", List.of(ev.identity())));
            if (i > 0) {
                String prev = KnowledgeGraph.derivedIdentity("chunk-node", List.of(ctx, Integer.toString(i - 1)));
                String eid = KnowledgeGraph.derivedIdentity("chunk-edge", List.of(prev, nid));
                edges.add(new KnowledgeGraph.Edge(eid, ctx, "REFERENCES", prev,
                        new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(nid), Optional.empty()),
                        List.of(ev.identity()), Optional.empty()));
            }
        }
        var largeGraph = new KnowledgeGraph(base.snapshot(), base.contexts(), nodes, edges, evidence);

        Path storeDir = Files.createDirectory(temp.resolve("chunk-store"));
        var store = new FileGraphStore(storeDir);
        store.save(largeGraph);
        Path jsonPath = store.jsonPath(largeGraph.identity());

        var binaryCache = new GraphBinaryCache(storeDir, 0L);
        binaryCache.write(largeGraph, jsonPath);
        byte[] firstBytes = Files.readAllBytes(binaryCache.cachePath(largeGraph.identity()));
        binaryCache.write(largeGraph, jsonPath);
        byte[] secondBytes = Files.readAllBytes(binaryCache.cachePath(largeGraph.identity()));
        assertArrayEquals(firstBytes, secondBytes, "Parallel chunked compression must be bit-for-bit deterministic");

        var loaded = binaryCache.read(largeGraph.identity(), jsonPath).orElseThrow();
        assertEquals(largeGraph.identity(), loaded.identity());
        assertEquals(largeGraph.semanticIdentity(), loaded.semanticIdentity());
        assertEquals(largeGraph.nodes().size(), loaded.nodes().size());
        assertEquals(largeGraph.edges().size(), loaded.edges().size());
        assertEquals(largeGraph.evidence().size(), loaded.evidence().size());
    }

    @Test
    void pinnedHttpsAllowsGithubHeadersAndRfc9110MultiValueWhileRejectingDuplicateContentLength() throws Exception {
        Path keystore = temp.resolve("tls.p12"), ca = temp.resolve("tls.pem");
        runKeytool("-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-dname", "CN=fixture.test",
                "-ext", "SAN=dns:fixture.test", "-validity", "1", "-storetype", "PKCS12",
                "-keystore", keystore.toString(), "-storepass", "fixture-password", "-keypass", "fixture-password", "-noprompt");
        runKeytool("-exportcert", "-rfc", "-alias", "fixture", "-keystore", keystore.toString(),
                "-storepass", "fixture-password", "-file", ca.toString());

        var keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(keystore)) {
            keys.load(input, "fixture-password".toCharArray());
        }
        var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(keys, "fixture-password".toCharArray());
        var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tm.init(keys);
        var tls = SSLContext.getInstance("TLS");
        tls.init(km.getKeyManagers(), tm.getTrustManagers(), new SecureRandom());

        // Use raw SSLServerSocket so we control exact duplicate headers on the wire (RFC 9110 Vary + duplicate Content-Length).
        try (SSLServerSocket server = (SSLServerSocket) tls.getServerSocketFactory()
                .createServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            int port = server.getLocalPort();
            var receivedHeaders = new ConcurrentHashMap<String, String>();
            ExecutorService exec = Executors.newSingleThreadExecutor();
            exec.submit(() -> {
                for (int reqIdx = 0; reqIdx < 2; reqIdx++) {
                    try (Socket client = server.accept()) {
                        var in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                        String reqLine = in.readLine();
                        String h;
                        while ((h = in.readLine()) != null && !h.isEmpty()) {
                            int idx = h.indexOf(':');
                            if (idx > 0) receivedHeaders.put(h.substring(0, idx).toLowerCase(Locale.ROOT), h.substring(idx + 1).trim());
                        }
                        String resp;
                        if (reqLine != null && reqLine.contains("/multi")) {
                            resp = "HTTP/1.1 200 OK\r\n"
                                    + "Vary: Accept\r\n"
                                    + "Vary: Authorization\r\n"
                                    + "Set-Cookie: a=1\r\n"
                                    + "Set-Cookie: b=2\r\n"
                                    + "Content-Length: 2\r\n"
                                    + "\r\nOK";
                        } else {
                            resp = "HTTP/1.1 200 OK\r\n"
                                    + "Content-Length: 2\r\n"
                                    + "Content-Length: 2\r\n"
                                    + "\r\nOK";
                        }
                        client.getOutputStream().write(resp.getBytes(StandardCharsets.US_ASCII));
                        client.getOutputStream().flush();
                    } catch (IOException ignored) {
                    }
                }
            });
            try {
                var policy = new GitHostPolicy(Set.of("fixture.test"), Map.of(),
                        host -> new InetAddress[]{InetAddress.getLoopbackAddress()},
                        uri -> Optional.empty(), Optional.of(ca), tls);
                var http = new PinnedHttps(policy);

                try (var resp = http.exchange(URI.create("https://fixture.test:" + port + "/multi"), "GET",
                        Map.of("User-Agent", "evo-test/1.0", "X-GitHub-Api-Version", "2022-11-28", "If-None-Match", "\"etag1\""),
                        new byte[0], () -> false)) {
                    assertEquals(200, resp.status());
                    assertEquals("Accept, Authorization", resp.headers().get("vary"));
                    assertEquals("a=1\nb=2", resp.headers().get("set-cookie"));
                    assertEquals("OK", new String(resp.body().readAllBytes(), StandardCharsets.UTF_8));
                }
                assertEquals("evo-test/1.0", receivedHeaders.get("user-agent"));
                assertEquals("2022-11-28", receivedHeaders.get("x-github-api-version"));
                assertEquals("\"etag1\"", receivedHeaders.get("if-none-match"));

                IOException dupLen = assertThrows(IOException.class, () ->
                        http.exchange(URI.create("https://fixture.test:" + port + "/dup-cl"), "GET",
                                Map.of(), new byte[0], () -> false));
                assertEquals("HTTP_HEADERS_INVALID", dupLen.getMessage());
            } finally {
                exec.shutdownNow();
            }
        }
    }

    @Test
    void incrementalJavaFrontendCacheHandlesLongSpellingsAndPrivateUpstreamReactorFiles() throws Exception {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/jfc-hardening.git");
        var provider = new VersionedIdentifier("frontend.test", "1");
        var direct = new Derivation(DerivationKind.DIRECT, provider, List.of());
        var modDown = ModuleDescriptor.create(repo, "app", "app");
        var modUp = ModuleDescriptor.create(repo, "core", "core");
        var platform = PlatformInput.create(21, "21", "test", List.of(
                new PlatformInput.Artifact("java.base.jmod", ContentDigest.sha256Utf8("jbase"),
                        Path.of("java.base.jmod"), PlatformInput.Format.JMOD)));

        byte[] appBytes = "package app; class App {}".getBytes(StandardCharsets.UTF_8);
        var appDoc = SourceDocument.create(repo, modDown, "app/src/main/java/app/App.java",
                ContentDigest.sha256(appBytes), SourceClassification.MAIN);
        byte[] apiBytes = "package core; public interface Api {}".getBytes(StandardCharsets.UTF_8);
        var apiDoc = SourceDocument.create(repo, modUp, "core/src/main/java/core/Api.java",
                ContentDigest.sha256(apiBytes), SourceClassification.MAIN);
        byte[] privBytes = "package core; class PrivateHelper {}".getBytes(StandardCharsets.UTF_8);
        var privDoc = SourceDocument.create(repo, modUp, "core/src/main/java/core/PrivateHelper.java",
                ContentDigest.sha256(privBytes), SourceClassification.MAIN);

        var req1 = buildFrontendRequest(repo, provider, platform, modDown, List.of(modDown, modUp),
                List.of(new SourceInput(appDoc, appBytes)),
                List.of(ReactorSourceInput.create(modUp.identity(), SourceClassification.MAIN, 0,
                        List.of(new SourceInput(apiDoc, apiBytes), new SourceInput(privDoc, privBytes)))));

        // Request 2 deletes the unreferenced private upstream file PrivateHelper.java while keeping the SAME module ABI digest.
        var req2 = buildFrontendRequest(repo, provider, platform, modDown, List.of(modDown, modUp),
                List.of(new SourceInput(appDoc, appBytes)),
                List.of(ReactorSourceInput.create(modUp.identity(), SourceClassification.MAIN, 0,
                        List.of(new SourceInput(apiDoc, apiBytes)))));

        // Include a >70 KiB declaration spelling to verify writeLongUtf/readLongUtf handles >64 KiB cleanly.
        String hugeSpelling = "public class App { String big = \"" + "A".repeat(70_000) + "\"; }";
        var appSpan = new SourceSpan(appDoc.identity(), 1, 1, 1, 20);
        var appEntity = Entity.create(EntityOrigin.PROJECT, EntityScope.project(modDown.identity()), EntityKind.TYPE,
                JavaSymbolName.topLevelType("app", "App").canonicalName(), Optional.of(appSpan));
        var apiSpan = new SourceSpan(apiDoc.identity(), 1, 1, 1, 20);
        var apiEntity = Entity.create(EntityOrigin.PROJECT, EntityScope.project(modUp.identity()), EntityKind.TYPE,
                JavaSymbolName.topLevelType("core", "Api").canonicalName(), Optional.of(apiSpan));

        var result1 = new FrontendResult(
                req1.manifest().identity(), provider, FrontendResult.State.COMPLETED,
                List.of(
                        new DeclarationRecord(appEntity, hugeSpelling, SemanticStatus.RESOLVED, direct, List.of(), List.of()),
                        new DeclarationRecord(apiEntity, "public interface Api {}", SemanticStatus.RESOLVED, direct, List.of(), List.of())),
                List.of(), List.of(),
                List.of(
                        new SourceOutcome(appDoc.identity(), SourceOutcome.State.PROCESSED, List.of()),
                        new SourceOutcome(apiDoc.identity(), SourceOutcome.State.PROCESSED, List.of()),
                        new SourceOutcome(privDoc.identity(), SourceOutcome.State.PROCESSED, List.of())),
                FrontendRequest.CATEGORIES.stream()
                        .map(c -> new CategoryCoverage(new RelationshipKind("java." + c), CategoryCoverage.Support.IMPLEMENTED, 0, 0, 0))
                        .toList(),
                List.of(), List.of(), List.of(), List.of(), List.of());

        var cache = new IncrementalJavaFrontendCache(temp.resolve("jfc"));
        var abiMap = Map.of(modUp.identity(), ContentDigest.sha256Utf8("stable-core-abi-v1"));
        ContentDigest key1 = IncrementalJavaFrontendCache.cacheKeyWithReactorAbi(req1, provider, abiMap);
        ContentDigest key2 = IncrementalJavaFrontendCache.cacheKeyWithReactorAbi(req2, provider, abiMap);
        assertEquals(key1, key2, "Deleting an unreferenced private upstream file with identical ABI must keep the ABI cache key");

        cache.putByAbi(req1, result1, abiMap);
        var hit = cache.getByAbi(req2, provider, abiMap, Map.of()).orElseThrow();
        assertEquals(req2.manifest().identity(), hit.analysis());
        assertEquals(hugeSpelling, hit.declarations().stream()
                .filter(d -> d.entity().identity().equals(appEntity.identity()))
                .findFirst().orElseThrow().spelling());
        assertEquals(Set.of(appDoc.identity(), apiDoc.identity()),
                hit.sources().stream().map(SourceOutcome::document).collect(java.util.stream.Collectors.toSet()),
                "Reconciled cached result must drop removed unreferenced upstream SourceOutcome cleanly");
    }

    private static FrontendRequest buildFrontendRequest(
            RepositoryIdentity repo, VersionedIdentifier provider, PlatformInput platform,
            ModuleDescriptor targetModule, List<ModuleDescriptor> allModules,
            List<SourceInput> sources, List<ReactorSourceInput> reactors) {
        var docs = new ArrayList<SourceDocument>();
        var files = new ArrayList<SnapshotFile>();
        for (var s : sources) {
            docs.add(s.document());
            files.add(SnapshotFile.from(s.document()));
        }
        for (var r : reactors) {
            for (var s : r.sources()) {
                docs.add(s.document());
                files.add(SnapshotFile.from(s.document()));
            }
        }
        var snapshot = RepositorySnapshot.create(repo, Optional.empty(), false, files, docs);
        var plan = new FrontendPlan(Optional.of(21), Optional.of(21), false,
                ContentDigest.sha256Utf8("cp"), ContentDigest.sha256Utf8("dec"));
        var options = FrontendRequest.options(plan, targetModule.identity(), SourceClassification.MAIN,
                sources.stream().map(SourceInput::document).toList(), platform, reactors);
        var comp = new ManifestComponent(provider, ContentDigest.sha256Utf8("comp"));
        var manifest = AnalysisManifest.create(provider, snapshot, allModules, List.of(platform.entry()),
                AnalysisConfiguration.create(provider, options), comp, comp, comp);
        return new FrontendRequest(manifest, targetModule.identity(), SourceClassification.MAIN,
                plan, sources, platform, List.of(), reactors);
    }

    private static void runKeytool(String... args) throws Exception {
        String exe = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool").toString();
        var cmd = new ArrayList<String>();
        cmd.add(exe);
        cmd.addAll(List.of(args));
        var proc = new ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(proc.waitFor(15, TimeUnit.SECONDS));
        assertEquals(0, proc.exitValue());
    }
}
