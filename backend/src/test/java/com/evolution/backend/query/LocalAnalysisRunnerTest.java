package com.evolution.backend.query;

import com.evolution.analysis.assessment.ArchitectureAssessment;
import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.query.ArchitectureQuery.Scope;
import com.evolution.backend.analysis.*;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LocalAnalysisRunnerTest {
        @TempDir
        Path directory;

        @Test
        void nestedIndependentMavenRootsKeepBothTypesAndReadinessGaps() throws Exception {
                Path source = Files.createDirectory(directory.resolve("multi")),
                                cache = Files.createDirectory(directory.resolve("multi-cache"));
                String pom = "<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId><artifactId>%s</artifactId><version>1</version><properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties></project>";
                for (var file : Map.of("pom.xml", pom.formatted("outer"), "nested/pom.xml", pom.formatted("nested"),
                                "src/main/java/A.java", "class A {}", "nested/src/main/java/B.java", "class B {}",
                                "broken/pom.xml",
                                "<project>").entrySet()) {
                        Path path = source.resolve(file.getKey());
                        Files.createDirectories(path.getParent());
                        Files.writeString(path, file.getValue());
                }
                var runner = new LocalAnalysisRunner(new LocalAnalysisRunner.Input(source,
                                RepositoryIdentity.fromCanonicalCoordinate("urn:test:multi-root-analysis"),
                                Path.of(System.getProperty("java.home")), 21, cache, Optional.empty(), true, true,
                                List.of(),
                                Optional.empty(), LocalAnalysisRunner.SpringInput.absent()));
                var graphs = new InMemoryGraphStore();
                try (var jobs = new AnalysisJobs(List.of(runner.registration("multi")), graphs, new PublishedReports(),
                                new AnalysisJobs.Limits(1, 1, 4, 90_000, 1000))) {
                        var result = finishedReal(jobs, jobs.submit("multi", "first").job().id());
                        assertEquals(AnalysisJobs.State.PARTIAL, result.state(), () -> CanonicalJson.write(result));
                        var graph = graphs.load(result.result().orElseThrow().graph().orElseThrow()).orElseThrow();
                        assertEquals(2, graph.nodes().stream().filter(node -> node.kind().equals("TYPE")).count());
                        var allGaps = new ArrayList<com.evolution.analysis.evidence.CapabilityGapRecord>();
                        Optional<String> cursor = Optional.empty();
                        do {
                                var page = jobs.gaps(UUID.fromString(result.id()), 50, cursor);
                                allGaps.addAll(page.items());
                                cursor = page.continuation();
                        } while (cursor.isPresent());
                        assertTrue(allGaps.stream()
                                        .anyMatch(gap -> gap.reasonCode().equals("NATIVE_BUILD_VALIDATION_REQUIRED")));
                        assertTrue(graph.evidence().stream()
                                        .anyMatch(evidence -> evidence.canonicalJson().contains("\"readiness.plan\"")));
                }
        }

        @Test
        void aSealedCaptureResumesAfterTheOriginalSourceDirectoryIsGone() throws Exception {
                Path source = Files.createDirectory(directory.resolve("captured-source"));
                Files.writeString(source.resolve("Example.java"), "public class Example {}\n");
                Path cache = Files.createDirectory(directory.resolve("captured-cache")),
                                state = directory.resolve("captured-state");
                var repository = RepositoryIdentity.fromCanonicalCoordinate("urn:test:captured-replay");
                String id = UUID.randomUUID().toString();
                try (var intake = new RepositoryIntake(state)) {
                        assertTrue(intake
                                        .local(source, id,
                                                        new com.evolution.analysis.acquisition.streaming.StreamingAcquisitionRequest(
                                                                        repository, Optional.empty(), true,
                                                                        List.of(".git")),
                                                        () -> false)
                                        .manifest().isPresent());
                }
                Files.delete(source.resolve("Example.java"));
                Files.delete(source);
                var runner = new LocalAnalysisRunner(new LocalAnalysisRunner.Input(source, repository,
                                Path.of(System.getProperty("java.home")), 21, cache, Optional.empty(), true, false,
                                List.of(),
                                Optional.empty(), LocalAnalysisRunner.SpringInput.absent()), state);
                var registration = runner.registration("captured");
                try (var disk = new AnalysisJobDiskStore(directory.resolve("captured-jobs"))) {
                        var view = new AnalysisJobs.View(id, "captured", AnalysisJobs.State.RUNNING,
                                        AnalysisJobs.Stage.ACQUISITION,
                                        2, java.time.Instant.now(), Optional.empty(), Optional.empty(), 0,
                                        Optional.empty(),
                                        Optional.empty(), List.of(), Optional.empty());
                        disk.save("resume", registration.setup(), view, List.of(), List.of());
                }
                var graphs = new InMemoryGraphStore();
                try (var disk = new AnalysisJobDiskStore(directory.resolve("captured-jobs"));
                                var jobs = new AnalysisJobs(List.of(registration), graphs, new PublishedReports(),
                                                new AnalysisJobs.Limits(1, 1, 4, 0, 1000), disk)) {
                        var result = finishedReal(jobs, id);
                        assertTrue(Set.of(AnalysisJobs.State.SUCCEEDED, AnalysisJobs.State.PARTIAL)
                                        .contains(result.state()),
                                        () -> CanonicalJson.write(result));
                        assertEquals(1, graphs.load(result.result().orElseThrow().graph().orElseThrow()).orElseThrow()
                                        .nodes()
                                        .stream().filter(n -> n.kind().equals("TYPE")).count());
                }
        }

        static final String POLICY = """
                        schema: architecture-policy:v1
                        id: test.backend
                        version: "1"
                        rules:
                          - {id: api.no-data, version: "1", kind: FORBIDDEN_DEPENDENCY, severity: ERROR, scope: TYPE, relationships: [graph.depends-on], from: {packages: [api]}, to: {packages: [data]}}
                        """;

        private LocalAnalysisRunner runner(boolean broken, boolean missingPlatform, String policy) throws Exception {
                var source = Files.createDirectory(directory.resolve("source"));
                Files.createDirectories(source.resolve("src/main/java/api"));
                Files.createDirectories(source.resolve("src/main/java/data"));
                Files.writeString(source.resolve("src/main/java/api/Controller.java"),
                                "package api; public class Controller { private data.Repository repository; "
                                                + (broken ? "private missing.Library unknown;" : "") + " }\n");
                Files.writeString(source.resolve("src/main/java/data/Repository.java"),
                                "package data; public class Repository {}\n");
                var cache = Files.createDirectory(directory.resolve("cache"));
                return new LocalAnalysisRunner(new LocalAnalysisRunner.Input(source,
                                RepositoryIdentity.fromCanonicalCoordinate("https://example.test/backend-fixture.git"),
                                missingPlatform ? directory.resolve("missing-jdk")
                                                : Path.of(System.getProperty("java.home")),
                                21,
                                cache, Optional.of("caller-revision"), true, false, List.of(),
                                Optional.of(new LocalAnalysisRunner.PolicyInput(policy.getBytes(StandardCharsets.UTF_8),
                                                Scope.TYPE)),
                                LocalAnalysisRunner.SpringInput.absent()));
        }

        static AnalysisJobs.View finishedReal(AnalysisJobs jobs, String id) throws Exception {
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
                while (System.nanoTime() < end) {
                        var view = jobs.get(UUID.fromString(id));
                        if (AnalysisJobs.terminal(view.state()))
                                return view;
                        Thread.sleep(20);
                }
                throw new AssertionError("Real-source analysis exceeded the bounded test deadline");
        }

        @Test
        void actualSourceToGraphReportsQueryAndReplayRemainCanonical() throws Exception {
                var runner = runner(false, false, POLICY);
                var store = new InMemoryGraphStore();
                var reports = new PublishedReports();
                try (var jobs = new AnalysisJobs(List.of(runner.registration("local")), store, reports,
                                new AnalysisJobs.Limits(1, 1, 4, 90_000, 100))) {
                        var api = new ArchitectureQueryApi(store, reports, ApiLimits.defaults(), jobs);
                        var first = finishedReal(jobs, jobs.submit("local", "first").job().id());
                        assertTrue(Set.of(AnalysisJobs.State.SUCCEEDED, AnalysisJobs.State.PARTIAL)
                                        .contains(first.state()),
                                        () -> CanonicalJson.write(first));
                        var result = first.result().orElseThrow();
                        var graph = store.load(result.graph().orElseThrow()).orElseThrow();
                        assertFalse(result.analyses().isEmpty());
                        assertEquals(2, graph.nodes().stream().filter(n -> n.kind().equals("TYPE")).count());
                        assertFalse(result.reports().isEmpty());
                        for (var reportId : result.reports()) {
                                var report = reports.get(reportId).orElseThrow();
                                assertFalse(report.evaluation().findings().isEmpty());
                                assertEquals(graph.identity(), report.evaluation().graph());
                                assertEquals(Set.of("metric.density", "metric.concentration"),
                                                report.assessment().orElseThrow().structuralInputs()
                                                                .stream().map(metric -> metric.metric().id())
                                                                .collect(java.util.stream.Collectors.toSet()));
                                var response = AnalysisJobApiTest.call(api, "POST",
                                                "/api/v1/graphs/" + graph.identity().value() + "/reports/"
                                                                + reportId.value() + "/assessment",
                                                "{}");
                                assertEquals(200, response.status());
                                assertEquals(CanonicalJson.write(report.assessment().orElseThrow()),
                                                CanonicalJson.write(AnalysisJobApiTest.data(response)));
                        }
                        assertEquals(200,
                                        AnalysisJobApiTest.call(api, "GET",
                                                        "/api/v1/graphs/" + graph.identity().value(), "").status());
                        var second = finishedReal(jobs, jobs.submit("local", "second").job().id());
                        assertEquals(first.result(), second.result());
                        assertEquals(first.state(), second.state());
                        assertEquals(
                                        List.of(AnalysisJobs.Stage.QUEUED, AnalysisJobs.Stage.ACQUISITION,
                                                        AnalysisJobs.Stage.PLATFORM,
                                                        AnalysisJobs.Stage.ANALYSIS,
                                                        AnalysisJobs.Stage.GRAPH, AnalysisJobs.Stage.POLICY,
                                                        AnalysisJobs.Stage.PUBLICATION,
                                                        AnalysisJobs.Stage.FINISHED),
                                        second.events().stream().map(AnalysisJobs.Event::stage).toList());
                }
        }

        @Test
        void diagnosisRoundTripsToCapturedSourceAndRejectsAnotherBundle() throws Exception {
                var store = new InMemoryGraphStore();
                var reports = new PublishedReports();
                try (var jobs = new AnalysisJobs(List.of(runner(false, false, POLICY).registration("local")), store,
                                reports,
                                new AnalysisJobs.Limits(1, 1, 4, 90_000, 100))) {
                        var job = finishedReal(jobs, jobs.submit("local", "diagnosis").job().id());
                        var result = job.result().orElseThrow();
                        var graph = result.graph().orElseThrow();
                        var reportId = result.reports().getFirst();
                        var finding = reports.get(reportId).orElseThrow().evaluation().findings().getFirst();
                        var bundle = finding.bundle();
                        var api = new ArchitectureQueryApi(store, reports, ApiLimits.defaults(), jobs);
                        var base = "/api/v1/graphs/" + graph.value() + "/reports/" + reportId.value();
                        var args = Map.of("identity", finding.identity().value(), "bundle", bundle.identity().value(),
                                        "context",
                                        bundle.context());
                        var request = CanonicalJson.write(Map.of("arguments", args));
                        var response = AnalysisJobApiTest.call(api, "POST", base + "/diagnosis", request);
                        assertEquals(200, response.status());
                        var diagnosis = AnalysisJobApiTest.data(response);
                        assertEquals("CITATIONS_VALIDATED", diagnosis.get("status"),
                                        () -> CanonicalJson.write(diagnosis));
                        assertEquals("LOCAL_REPLAY", diagnosis.get("mode"));
                        var pin = GraphJson.object(diagnosis.get("pin"));
                        assertEquals(graph.value(), pin.get("graph"));
                        assertEquals(result.snapshot().orElseThrow().value(), pin.get("snapshot"));
                        assertEquals(reportId.value(), pin.get("report"));
                        assertEquals(finding.identity().value(), pin.get("finding"));
                        var citations = GraphJson.array(diagnosis.get("citations"));
                        assertFalse(citations.isEmpty());
                        var expected = com.evolution.analysis.contract.common.ContentDigest
                                        .sha256Utf8("package api; public class Controller { private data.Repository repository;  }\n");
                        assertTrue(citations.stream().map(GraphJson::object)
                                        .anyMatch(c -> expected.value().equals(c.get("sourceDigest"))
                                                        && "src/main/java/api/Controller.java".equals(c.get("path"))));
                        var chatRef = Map.of("report", reportId.value(), "identity", finding.identity().value(),
                                        "bundle",
                                        bundle.identity().value());
                        var chat = new TreeMap<String, Object>(Map.of("context", bundle.context(), "mode", "REPAIR",
                                        "finding",
                                        chatRef,
                                        "job", job.id(), "messages",
                                        List.of(Map.of("role", "user", "content",
                                                        "Explain this dependency and propose a repair."))));
                        var chatPath = "/api/v1/graphs/" + graph.value() + "/architecture-chat";
                        var read = AnalysisJobApiTest.call(api, "POST", chatPath, CanonicalJson.write(chat));
                        assertEquals(200, read.status());
                        var chatEvidence = GraphJson.array(AnalysisJobApiTest.data(read).get("evidence")).stream()
                                        .map(GraphJson::object).toList();
                        assertTrue(chatEvidence.stream().anyMatch(row -> "FINDING".equals(row.get("kind"))
                                        && bundle.region().jointRegion().value()
                                                        .equals(GraphJson.object(row.get("data")).get("region"))));
                        assertTrue(chatEvidence.stream().anyMatch(row -> "SOURCE".equals(row.get("kind"))
                                        && expected.value().equals(
                                                        GraphJson.object(row.get("data")).get("sourceDigest"))));
                        var gapData = chatEvidence.stream()
                                        .filter(row -> "Analysis capability gaps".equals(row.get("label")))
                                        .map(row -> GraphJson.object(row.get("data"))).findFirst().orElseThrow();
                        assertEquals(job.id(), gapData.get("job"));
                        assertEquals(job.result().orElseThrow().jobGapCount(),
                                        ((Number) gapData.get("counted")).intValue());
                        assertFalse(new String(read.body(), StandardCharsets.UTF_8)
                                        .contains("public class Controller"));
                        chat.put("finding",
                                        Map.of("report", reportId.value(), "identity", finding.identity().value(),
                                                        "bundle",
                                                        reportId.value()));
                        assertEquals(409, AnalysisJobApiTest.call(api, "POST", chatPath, CanonicalJson.write(chat))
                                        .status());
                        for (var value : citations) {
                                var citation = GraphJson.object(value);
                                var evidenceRequest = CanonicalJson.write(Map.of("request",
                                                Map.of("context", citation.get("context"), "scope", "ENTITY",
                                                                "projection", "BOUNDED_UNION", "direction", "OUTGOING",
                                                                "relationshipKinds",
                                                                List.of("graph.depends-on")),
                                                "arguments", Map.of("reference", citation.get("evidence"))));
                                var reopened = AnalysisJobApiTest.call(api, "POST",
                                                "/api/v1/graphs/" + graph.value() + "/queries/evidence",
                                                evidenceRequest);
                                var row = GraphJson.object(GraphJson
                                                .array(AnalysisJobApiTest.data(reopened).get("items")).getFirst());
                                assertEquals(citation.get("evidence"), row.get("identity"));
                        }
                        assertArrayEquals(response.body(),
                                        AnalysisJobApiTest.call(api, "POST", base + "/diagnosis", request).body());
                        checkLiveDiagnosis(api, base + "/diagnosis", request, GraphJson.string(diagnosis.get("input")),
                                        citations);
                        var proposalArgs = new TreeMap<String, String>(args);
                        proposalArgs.put("diagnosisInput", GraphJson.string(diagnosis.get("input")));
                        var options = AnalysisJobApiTest.call(api, "POST", base + "/repair-options",
                                        CanonicalJson.write(Map.of("arguments", proposalArgs)));
                        assertEquals(200, options.status(),
                                        "A real captured repository can discover repair strategies");
                        checkCapturedRepair(api, base, proposalArgs);
                        chat.put("finding", chatRef);
                        checkCapturedAi(api, base, proposalArgs, chatPath, chat);
                        var proposal = AnalysisJobApiTest.call(api, "POST", base + "/repair-proposal",
                                        CanonicalJson.write(Map.of("arguments", proposalArgs)));
                        assertEquals(200, proposal.status());
                        assertEquals("WITHHELD", AnalysisJobApiTest.data(proposal).get("status"),
                                        "A valid diagnosis outside the approved repair fixture cannot produce a patch");
                        assertNull(AnalysisJobApiTest.data(proposal).get("proposal"));
                        proposalArgs.put("proposal", reportId.value());
                        var verification = AnalysisJobApiTest.call(api, "POST", base + "/repair-verification",
                                        CanonicalJson.write(Map.of("arguments", proposalArgs)));
                        assertEquals(409, verification.status());
                        assertEquals("REPAIR_PROPOSAL_MISMATCH",
                                        GraphJson.object(
                                                        GraphJson.object(GraphJson.read(
                                                                        new String(verification.body(),
                                                                                        StandardCharsets.UTF_8),
                                                                        GraphJson.Limits.defaults())).get("error"))
                                                        .get("code"));
                        proposalArgs.remove("proposal");
                        proposalArgs.put("diagnosisInput", reportId.value());
                        assertEquals(409, AnalysisJobApiTest.call(api, "POST", base + "/repair-proposal",
                                        CanonicalJson.write(Map.of("arguments", proposalArgs))).status());
                        var wrong = new TreeMap<String, String>(args);
                        wrong.put("bundle", reportId.value());
                        assertEquals(409, AnalysisJobApiTest
                                        .call(api, "POST", base + "/diagnosis",
                                                        CanonicalJson.write(Map.of("arguments", wrong)))
                                        .status());
                        wrong.putAll(args);
                        wrong.put("context", result.snapshot().orElseThrow().value());
                        assertEquals(409, AnalysisJobApiTest
                                        .call(api, "POST", base + "/diagnosis",
                                                        CanonicalJson.write(Map.of("arguments", wrong)))
                                        .status());

                        var queries = new com.evolution.analysis.query.ArchitectureQueryService(
                                        store.load(graph).orElseThrow());
                        var model = new FindingDiagnosis(new FindingDiagnosis.Model() {
                                @Override
                                public FindingDiagnosis.Mode mode() {
                                        return FindingDiagnosis.Mode.LOCAL_REPLAY;
                                }

                                @Override
                                public com.evolution.analysis.contract.common.VersionedIdentifier version() {
                                        return new com.evolution.analysis.contract.common.VersionedIdentifier(
                                                        "test.foreign-citation", "1");
                                }

                                @Override
                                public Optional<FindingDiagnosis.Proposal> propose(FindingDiagnosis.Input input,
                                                java.util.function.Supplier<com.evolution.analysis.query.ArchitectureQuery.StopReason> stop) {
                                        return Optional.of(new FindingDiagnosis.Proposal(input.identity(),
                                                        List.of(reportId)));
                                }
                        });
                        var absent = model.diagnose(reportId, withSources(finding, List.of()), queries);
                        assertEquals(Optional.of(FindingDiagnosis.Reason.MISSING_SOURCE_EVIDENCE), absent.reason());
                        assertTrue(absent.explanation().isEmpty());
                        assertTrue(absent.citations().isEmpty());
                        var original = bundle.sources().getFirst();
                        var tampered = new com.evolution.analysis.policy.PolicyEvaluation.SourceEvidence(
                                        original.span(), reportId,
                                        original.evidence());
                        assertEquals(Optional.of(FindingDiagnosis.Reason.MISSING_SOURCE_EVIDENCE),
                                        model.diagnose(reportId, withSources(finding, List.of(tampered)), queries)
                                                        .reason());
                        var rejected = model.diagnose(reportId, finding, queries);
                        assertEquals(Optional.of(FindingDiagnosis.Reason.INVALID_MODEL_CITATIONS), rejected.reason());
                        assertTrue(rejected.explanation().isEmpty());
                        assertTrue(rejected.citations().isEmpty());
                }
        }

        private void checkCapturedRepair(ArchitectureQueryApi api, String base, Map<String, String> pinned)
                        throws Exception {
                var args = new TreeMap<>(pinned);
                args.put("strategy", "RECIPE");
                args.put("recipe", "java.remove-unused-private-field");
                var result = AnalysisJobApiTest.call(api, "POST", base + "/repair-proposal",
                                CanonicalJson.write(Map.of("arguments", args)));
                assertEquals(200, result.status());
                var data = AnalysisJobApiTest.data(result);
                assertEquals("PROPOSED", data.get("status"), () -> CanonicalJson.write(data));
                var candidate = GraphJson.object(GraphJson.object(data.get("proposal")).get("candidate"));
                assertEquals("UNVERIFIED", candidate.get("verification"));
                var patch = GraphJson.string(candidate.get("patch"));
                var source = directory.resolve("source/src/main/java/api/Controller.java");
                var workspace = Files.createDirectory(directory.resolve("patch-checkout"));
                var target = workspace.resolve("src/main/java/api/Controller.java");
                Files.createDirectories(target.getParent());
                Files.copy(source, target);
                var patchFile = Files.writeString(directory.resolve("repair.patch"), patch);
                var pb = new ProcessBuilder("git", "-c", "core.autocrlf=false", "apply", patchFile.toString())
                                .directory(workspace.toFile()).redirectErrorStream(true);
                pb.environment().put("GIT_CEILING_DIRECTORIES", workspace.getParent().toAbsolutePath().toString());
                var git = pb.start();
                assertTrue(git.waitFor(10, TimeUnit.SECONDS));
                assertEquals(0, git.exitValue(), () -> new String(readGitOutput(git), StandardCharsets.UTF_8));
                assertEquals("package api; public class Controller {   }\n", Files.readString(target));
                assertEquals("package api; public class Controller { private data.Repository repository;  }\n",
                                Files.readString(source));
                var edit = GraphJson.object(GraphJson.array(candidate.get("edits")).getFirst());
                assertEquals(ContentDigest.sha256(Files.readAllBytes(target)).value(), edit.get("after"));
                assertArrayEquals(result.body(), AnalysisJobApiTest
                                .call(api, "POST", base + "/repair-proposal",
                                                CanonicalJson.write(Map.of("arguments", args)))
                                .body());
        }

        private static byte[] readGitOutput(Process git) {
                try {
                        return git.getInputStream().readAllBytes();
                } catch (java.io.IOException failure) {
                        throw new java.io.UncheckedIOException(failure);
                }
        }

        private static void checkCapturedAi(ArchitectureQueryApi api, String base, Map<String, String> pinned,
                        String chatPath, Map<String, Object> chat) throws Exception {
                var model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                var requests = new AtomicInteger();
                var reply = new AtomicReference<String>();
                var requestBody = new AtomicReference<String>();
                model.createContext("/v1/chat/completions", exchange -> {
                        try (exchange) {
                                requests.incrementAndGet();
                                requestBody.set(new String(exchange.getRequestBody().readAllBytes(),
                                                StandardCharsets.UTF_8));
                                byte[] body = CanonicalJson
                                                .write(Map.of("model", "captured-repair-test", "choices", List.of(
                                                                Map.of("finish_reason", "stop", "message",
                                                                                Map.of("role", "assistant", "content",
                                                                                                reply.get())))))
                                                .getBytes(StandardCharsets.UTF_8);
                                exchange.sendResponseHeaders(200, body.length);
                                exchange.getResponseBody().write(body);
                        }
                });
                model.start();
                try {
                        var endpoint = URI.create("http://127.0.0.1:" + model.getAddress().getPort() + "/v1");
                        var configured = ArchitectureQueryApi.withRepairModel(api, new RepairChatModel(
                                        endpoint, "captured-repair-test", null, false));
                        var catalog = AnalysisJobApiTest
                                        .data(AnalysisJobApiTest.call(configured, "POST", base + "/repair-options",
                                                        CanonicalJson.write(Map.of("arguments", pinned))));
                        assertEquals(0, requests.get(), "Discovery never transmits source");
                        assertEquals(16, GraphJson.array(catalog.get("recipes")).size());
                        var ai = GraphJson.object(catalog.get("ai"));
                        var files = GraphJson.array(ai.get("files"));
                        int controller = 0;
                        String before = "";
                        for (int i = 0; i < files.size(); i++) {
                                var file = GraphJson.object(files.get(i));
                                if ("src/main/java/api/Controller.java".equals(file.get("path"))) {
                                        controller = i + 1;
                                        before = GraphJson.string(file.get("digest"));
                                }
                        }
                        assertTrue(controller > 0);
                        var args = new TreeMap<>(pinned);
                        args.put("strategy", "AI");
                        args.put("modelInput", pinned.get("diagnosisInput"));
                        var stale = AnalysisJobApiTest
                                        .data(AnalysisJobApiTest.call(configured, "POST", base + "/repair-proposal",
                                                        CanonicalJson.write(Map.of("arguments", args))));
                        assertEquals("MODEL_INPUT_MISMATCH", stale.get("reason"));
                        assertEquals(0, requests.get(), "A stale approval must not transmit source");
                        args.put("modelInput", GraphJson.string(ai.get("input")));
                        var body = CanonicalJson.write(Map.of("arguments", args));
                        String old = "private data.Repository repository;";
                        reply.set(CanonicalJson
                                        .write(Map.of("edits", List.of(Map.of("file", controller, "before", before,
                                                        "oldText", old, "newText", "private void broken( {")))));
                        var invalid = AnalysisJobApiTest
                                        .data(AnalysisJobApiTest.call(configured, "POST", base + "/repair-proposal",
                                                        body));
                        assertEquals("CANDIDATE_SYNTAX_REJECTED", invalid.get("reason"));
                        assertNull(invalid.get("proposal"),
                                        "Even a schema-valid model reply must not produce a malformed patch");
                        var checks = GraphJson.array(invalid.get("syntax"));
                        assertFalse(GraphJson
                                        .array(GraphJson.object(GraphJson.object(checks.getFirst()).get("result"))
                                                        .get("diagnostics"))
                                        .isEmpty());
                        assertTrue(requestBody.get().contains(old));
                        assertFalse(requestBody.get().contains("example.test/backend-fixture"),
                                        "Never send repository URLs");

                        reply.set(CanonicalJson
                                        .write(Map.of("edits",
                                                        List.of(Map.of("file", controller, "before",
                                                                        pinned.get("diagnosisInput"),
                                                                        "oldText", old, "newText", "")))));
                        var foreign = AnalysisJobApiTest
                                        .data(AnalysisJobApiTest.call(configured, "POST", base + "/repair-proposal",
                                                        body));
                        assertEquals("INVALID_MODEL_OUTPUT", foreign.get("reason"));
                        assertNull(foreign.get("proposal"));

                        String original = "package api; public class Controller { private data.Repository repository;  }\n";
                        String replacement = "package api; public class Controller { }\n";
                        reply.set("```json\n" + CanonicalJson.write(Map.of("edits",
                                        List.of(Map.of("file", controller, "before", before,
                                                        "startLine", 1, "oldText",
                                                        "  " + original.replace("\n", "\r\n"), "newText",
                                                        "  " + replacement))))
                                        + "\n```");
                        var proposed = AnalysisJobApiTest
                                        .data(AnalysisJobApiTest.call(configured, "POST", base + "/repair-proposal",
                                                        body));
                        assertEquals("PROPOSED", proposed.get("status"), () -> CanonicalJson.write(proposed));
                        var patch = GraphJson.object(GraphJson.object(proposed.get("proposal")).get("candidate"));
                        assertEquals("UNVERIFIED", patch.get("verification"));
                        assertEquals("AI", patch.get("strategy"));
                        assertEquals(ContentDigest.sha256Utf8(reply.get()).value(), patch.get("modelResponse"));
                        assertEquals(ContentDigest.sha256Utf8(GraphJson.string(patch.get("patch"))).value(),
                                        patch.get("patchDigest"));
                        var edits = GraphJson.array(patch.get("edits"));
                        assertEquals(ContentDigest.sha256Utf8(replacement).value(),
                                        GraphJson.object(edits.getFirst()).get("after"));
                        assertEquals(3, requests.get());

                        // Self-healing compiler feedback loop: attempt 1 returns syntax error, attempt
                        // 2 repairs it cleanly.
                        var selfHealAttempt = new java.util.concurrent.atomic.AtomicInteger();
                        var selfHealArgs = new TreeMap<String, Object>(args);
                        selfHealArgs.put("maxRepairAttempts", 3);
                        var selfHealBody = CanonicalJson.write(Map.of("arguments", selfHealArgs));
                        String brokenReply = CanonicalJson
                                        .write(Map.of("edits", List.of(Map.of("file", controller, "before", before,
                                                        "oldText", old, "newText", "private void broken( {"))));
                        String validReply = CanonicalJson
                                        .write(Map.of("edits", List.of(Map.of("file", controller, "before", before,
                                                        "oldText", old, "newText", ""))));
                        var healMock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                        var healSecondRequest = new AtomicReference<String>();
                        healMock.createContext("/v1/chat/completions", exchange -> {
                                try (exchange) {
                                        String req = new String(exchange.getRequestBody().readAllBytes(),
                                                        StandardCharsets.UTF_8);
                                        int n = selfHealAttempt.incrementAndGet();
                                        if (n == 2)
                                                healSecondRequest.set(req);
                                        byte[] resp = CanonicalJson.write(Map.of("model", "captured-repair-test",
                                                        "choices", List.of(
                                                                        Map.of("finish_reason", "stop", "message",
                                                                                        Map.of("role", "assistant",
                                                                                                        "content",
                                                                                                        n == 1 ? brokenReply
                                                                                                                        : validReply)))))
                                                        .getBytes(StandardCharsets.UTF_8);
                                        exchange.sendResponseHeaders(200, resp.length);
                                        exchange.getResponseBody().write(resp);
                                }
                        });
                        healMock.start();
                        try {
                                var healApi = ArchitectureQueryApi.withRepairModel(api, new RepairChatModel(
                                                URI.create("http://127.0.0.1:" + healMock.getAddress().getPort()
                                                                + "/v1"),
                                                "captured-repair-test", null, false));
                                var healOpts = AnalysisJobApiTest
                                                .data(AnalysisJobApiTest.call(healApi, "POST", base + "/repair-options",
                                                                CanonicalJson.write(Map.of("arguments", pinned))));
                                selfHealArgs.put("modelInput",
                                                GraphJson.string(GraphJson.object(healOpts.get("ai")).get("input")));
                                var selfHealUpdatedBody = CanonicalJson.write(Map.of("arguments", selfHealArgs));
                                var healed = AnalysisJobApiTest
                                                .data(AnalysisJobApiTest.call(healApi, "POST",
                                                                base + "/repair-proposal", selfHealUpdatedBody));
                                assertEquals("PROPOSED", healed.get("status"), () -> CanonicalJson.write(healed));
                                assertEquals(2, ((Number) healed.get("attempts")).intValue());
                                assertTrue(healSecondRequest.get().contains("compiler.err"),
                                                "Feedback turn must carry javac diagnostic codes");
                        } finally {
                                healMock.stop(0);
                        }

                        var chatApi = ArchitectureQueryApi.withArchitectureChat(configured, new ArchitectureChatModel(
                                        endpoint, "captured-repair-test", null, false));
                        reply.set("**Review** the controller's dependency.");
                        var metadata = AnalysisJobApiTest.call(chatApi, "POST", chatPath, CanonicalJson.write(chat));
                        assertEquals(200, metadata.status());
                        assertEquals(List.of(), GraphJson.array(modelInput(requestBody.get()).get("capturedSources")));
                        var withSource = new TreeMap<>(chat);
                        withSource.put("includeSource", true);
                        var response = AnalysisJobApiTest.call(chatApi, "POST", chatPath,
                                        CanonicalJson.write(withSource));
                        assertEquals(200, response.status());
                        var sent = GraphJson.array(modelInput(requestBody.get()).get("capturedSources"));
                        assertTrue(sent.stream().map(GraphJson::object)
                                        .anyMatch(file -> file.get("source") instanceof String src
                                                        && src.startsWith("<untrusted_source ")
                                                        && src.endsWith("</untrusted_source>")
                                                        && src.contains(original)));
                        var receipt = GraphJson.object(AnalysisJobApiTest.data(response).get("model"));
                        assertEquals(ContentDigest.sha256Utf8(requestBody.get()).value(), receipt.get("request"));
                        assertTrue(GraphJson.array(receipt.get("sources")).stream().map(GraphJson::object)
                                        .anyMatch(file -> ContentDigest.sha256Utf8(original).value()
                                                        .equals(file.get("digest"))));
                        assertFalse(new String(response.body(), StandardCharsets.UTF_8)
                                        .contains("public class Controller"));
                } finally {
                        model.stop(0);
                }
        }

        private static Map<String, Object> modelInput(String wire) {
                var payload = GraphJson.object(GraphJson.read(wire, GraphJson.Limits.defaults()));
                var message = GraphJson.object(GraphJson.array(payload.get("messages")).getLast());
                return GraphJson.object(
                                GraphJson.read(GraphJson.string(message.get("content")), GraphJson.Limits.defaults()));
        }

        private static void checkLiveDiagnosis(ArchitectureQueryApi api, String route, String request, String input,
                        List<Object> citations) throws Exception {
                var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                var requests = new AtomicInteger();
                var redirected = new AtomicInteger();
                var completion = new AtomicReference<>("{\"citations\":[1]}");
                var lastRequest = new AtomicReference<Map<String, Object>>();
                String model = "fixture-model";
                server.createContext("/v1/chat/completions", exchange -> {
                        try (exchange) {
                                requests.incrementAndGet();
                                lastRequest.set(GraphJson.object(
                                                GraphJson.read(new String(exchange.getRequestBody().readAllBytes(),
                                                                StandardCharsets.UTF_8),
                                                                GraphJson.Limits.defaults())));
                                var content = completion.get();
                                if (content == null) {
                                        exchange.sendResponseHeaders(503, -1);
                                        return;
                                }
                                if ("redirect".equals(content)) {
                                        exchange.getResponseHeaders().set("Location", "/redirected");
                                        exchange.sendResponseHeaders(307, -1);
                                        return;
                                }
                                var body = CanonicalJson.write(Map.of("model", model, "choices", List.of(Map.of(
                                                "finish_reason", "stop", "message",
                                                Map.of("role", "assistant", "content", content)))))
                                                .getBytes(StandardCharsets.UTF_8);
                                exchange.sendResponseHeaders(200, body.length);
                                exchange.getResponseBody().write(body);
                        }
                });
                server.createContext("/redirected", exchange -> {
                        try (exchange) {
                                redirected.incrementAndGet();
                                exchange.sendResponseHeaders(503, -1);
                        }
                });
                server.start();
                try {
                        var mismatched = ArchitectureQueryApi.withDemoDiagnosis(api, server.getAddress().getPort(),
                                        model,
                                        ContentDigest.sha256Utf8("different diagnosis"));
                        var outOfScope = AnalysisJobApiTest
                                        .data(AnalysisJobApiTest.call(mismatched, "POST", route, request));
                        assertEquals("MODEL_SCOPE_NOT_REGISTERED", outOfScope.get("reason"));
                        assertEquals(0, requests.get(), "An unregistered input must never be sent to the model");

                        var configured = ArchitectureQueryApi.withDemoDiagnosis(api, server.getAddress().getPort(),
                                        model,
                                        new ContentDigest(input));
                        var live = AnalysisJobApiTest.data(AnalysisJobApiTest.call(configured, "POST", route, request));
                        assertEquals(1, requests.get());
                        assertEquals("LIVE", live.get("mode"));
                        assertEquals("CITATIONS_VALIDATED", live.get("status"));
                        assertEquals(input, live.get("input"));
                        assertEquals(List.of(citations.getFirst()), live.get("citations"));
                        Object ordinalSchema = lastRequest.get();
                        for (var field : List.of("response_format", "schema", "properties", "citations", "items",
                                        "enum"))
                                ordinalSchema = GraphJson.object(ordinalSchema).get(field);
                        assertEquals(
                                        java.util.stream.IntStream.rangeClosed(1, citations.size())
                                                        .mapToObj(BigDecimal::valueOf).toList(),
                                        ordinalSchema,
                                        "The generation schema must offer citation ordinals, not arbitrary coordinate numbers");

                        for (var invalid : List.of("{\"citations\":[17]}", "{\"citations\":[1,1]}",
                                        "{\"citations\":[1],\"claim\":\"extra text\"}")) {
                                completion.set(invalid);
                                var withheld = AnalysisJobApiTest
                                                .data(AnalysisJobApiTest.call(configured, "POST", route, request));
                                assertEquals("WITHHELD", withheld.get("status"));
                                assertEquals("INVALID_MODEL_CITATIONS", withheld.get("reason"));
                                assertEquals(List.of(), withheld.get("citations"));
                                assertNull(withheld.get("explanation"));
                        }
                        for (var failed : Arrays.asList(null, "redirect", " ".repeat(33_000))) {
                                completion.set(failed);
                                var unavailable = AnalysisJobApiTest
                                                .data(AnalysisJobApiTest.call(configured, "POST", route, request));
                                assertEquals("LIVE", unavailable.get("mode"));
                                assertEquals("MODEL_UNAVAILABLE", unavailable.get("reason"));
                                assertEquals(List.of(), unavailable.get("citations"));
                        }
                        assertEquals(0, redirected.get(), "A model response must never forward the request");
                } finally {
                        server.stop(0);
                }
        }

        private static com.evolution.analysis.policy.PolicyEvaluation.Finding withSources(
                        com.evolution.analysis.policy.PolicyEvaluation.Finding finding,
                        List<com.evolution.analysis.policy.PolicyEvaluation.SourceEvidence> sources) {
                var b = finding.bundle();
                var altered = new com.evolution.analysis.policy.PolicyEvaluation.EvidenceBundle(b.snapshot(),
                                b.analysis(),
                                b.graph(), b.context(),
                                b.policy(), b.policySource(), b.compilation(), b.mapping(), b.configurationSpaces(),
                                b.provenance(),
                                b.upstreamEvidence(),
                                b.rule(), b.request(), b.query(), b.predicate(), b.region(), b.path(),
                                b.relationships(), b.supports(),
                                sources, b.evidence(), b.status(), b.gaps(), b.limitations());
                return new com.evolution.analysis.policy.PolicyEvaluation.Finding(finding.rule(), finding.kind(),
                                finding.classification(), altered);
        }

        @Test
        void missingClasspathKeepsTypedGapsAndNeverProducesACompleteJob() throws Exception {
                var runner = runner(true, false, POLICY);
                var store = new InMemoryGraphStore();
                var reports = new PublishedReports();
                try (var jobs = new AnalysisJobs(List.of(runner.registration("partial")), store, reports,
                                new AnalysisJobs.Limits(1, 1, 4, 90_000, 100))) {
                        var view = finishedReal(jobs, jobs.submit("partial", "once").job().id());
                        assertEquals(AnalysisJobs.State.PARTIAL, view.state(), () -> CanonicalJson.write(view));
                        var result = view.result().orElseThrow();
                        assertTrue(result.gapCount() > 0);
                        assertTrue(result.graphGapCount() > 0);
                        assertTrue(result.jobGapCount() > 0);
                        var page = jobs.gaps(UUID.fromString(view.id()), 1, Optional.empty());
                        assertEquals(1, page.items().size());
                        assertTrue(page.continuation().isPresent());
                        var seen = new HashSet<String>();
                        do {
                                page.items().forEach(g -> assertTrue(seen.add(g.gapIdentity().value())));
                                if (page.continuation().isEmpty())
                                        break;
                                page = jobs.gaps(UUID.fromString(view.id()), 1, page.continuation());
                        } while (true);
                        assertEquals(result.jobGapCount(), seen.size());
                        for (var reportId : result.reports()) {
                                var score = reports.get(reportId).orElseThrow().assessment().orElseThrow();
                                assertEquals(AssessmentStatus.WITHHELD, score.status());
                                assertTrue(score.score().isEmpty());
                        }
                }
        }

        @Test
        void unavailablePlatformPreservesStructuralGraphAndAcquisitionReasons() throws Exception {
                var runner = runner(false, true, POLICY);
                var store = new InMemoryGraphStore();
                var reports = new PublishedReports();
                try (var jobs = new AnalysisJobs(List.of(runner.registration("partial")), store, reports,
                                new AnalysisJobs.Limits(1, 1, 4, 90_000, 100))) {
                        var view = finishedReal(jobs, jobs.submit("partial", "once").job().id());
                        assertEquals(AnalysisJobs.State.FAILED, view.state(), () -> CanonicalJson.write(view));
                        var result = view.result().orElseThrow();
                        assertTrue(result.graph().isPresent());
                        assertTrue(result.gapCount() > 0);
                        assertTrue(result.analyses().isEmpty());
                        assertTrue(jobs.gaps(UUID.fromString(view.id()), 50, Optional.empty()).items().stream()
                                        .anyMatch(g -> g.detectingProvider().id().contains("platform")));
                        assertFalse(CanonicalJson.write(view).contains(directory.toString()));
                }
        }

        @Test
        void invalidPolicyRemainsWithheldWithAnExplicitCompilerGap() throws Exception {
                var runner = runner(false, false, "schema: invalid\n");
                var store = new InMemoryGraphStore();
                var reports = new PublishedReports();
                try (var jobs = new AnalysisJobs(List.of(runner.registration("invalid-policy")), store, reports,
                                new AnalysisJobs.Limits(1, 1, 4, 90_000, 100))) {
                        var view = finishedReal(jobs, jobs.submit("invalid-policy", "once").job().id());
                        assertEquals(AnalysisJobs.State.PARTIAL, view.state(), () -> CanonicalJson.write(view));
                        assertFalse(view.result().orElseThrow().reports().isEmpty());
                        for (var id : view.result().orElseThrow().reports()) {
                                var report = reports.get(id).orElseThrow();
                                assertEquals(AssessmentStatus.WITHHELD, report.evaluation().status());
                                assertEquals(AssessmentStatus.WITHHELD, report.assessment().orElseThrow().status());
                        }
                        assertTrue(jobs.gaps(UUID.fromString(view.id()), 50, Optional.empty()).items().stream()
                                        .anyMatch(g -> g.detectingProvider().id().equals("policy.compiler")));
                }
        }

        @Test
        void passiveMavenSourceSelectionKeepsProductionTypesAndRecordsTestExclusion() throws Exception {
                var runner = runner(false, false, POLICY);
                var source = directory.resolve("source");
                Files.writeString(source.resolve("pom.xml"),
                                """
                                                <project><modelVersion>4.0.0</modelVersion><groupId>test</groupId><artifactId>app</artifactId><version>1</version>
                                                <properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties></project>
                                                """);
                Files.createDirectories(source.resolve("src/test/java"));
                Files.writeString(source.resolve("src/test/java/IgnoredTest.java"), "public class IgnoredTest {}\n");
                var store = new InMemoryGraphStore();
                var reports = new PublishedReports();
                try (var jobs = new AnalysisJobs(List.of(runner.registration("maven")), store, reports,
                                new AnalysisJobs.Limits(1, 1, 4, 90_000, 100))) {
                        var view = finishedReal(jobs, jobs.submit("maven", "once").job().id());
                        assertTrue(Set.of(AnalysisJobs.State.SUCCEEDED, AnalysisJobs.State.PARTIAL)
                                        .contains(view.state()),
                                        () -> CanonicalJson.write(view));
                        var graph = store.load(view.result().orElseThrow().graph().orElseThrow()).orElseThrow();
                        assertEquals(2, graph.nodes().stream().filter(n -> n.kind().equals("TYPE")).count());
                        try (var intake = new RepositoryIntake(source.resolve(".evo/acquisition"), true)) {
                                assertTrue(intake.inventory().page(view.id(), 50, Optional.empty()).entries().stream()
                                                .anyMatch(e -> e.path().utf8()
                                                                .filter("src/test/java/IgnoredTest.java"::equals)
                                                                .isPresent()));
                                assertTrue(intake.inventory().manifest(view.id()).isPresent());
                        }
                }
        }

        @Test
        void failedAcquisitionKeepsTypedProviderProblemsWithoutInventingASnapshot() throws Exception {
                var source = directory.resolve("absent-source");
                var store = new InMemoryGraphStore();
                var reports = new PublishedReports();
                var runner = new LocalAnalysisRunner(new LocalAnalysisRunner.Input(source,
                                RepositoryIdentity.fromCanonicalCoordinate("urn:test:unavailable"),
                                Path.of(System.getProperty("java.home")), 21, directory,
                                Optional.empty(), true, false, List.of(), Optional.empty(),
                                LocalAnalysisRunner.SpringInput.absent()));
                try (var jobs = new AnalysisJobs(List.of(runner.registration("unavailable")), store, reports,
                                AnalysisJobsTest.limits())) {
                        var view = AnalysisJobsTest.finished(jobs, jobs.submit("unavailable", "once").job().id());
                        assertEquals(AnalysisJobs.State.FAILED, view.state());
                        var result = view.result().orElseThrow();
                        assertTrue(result.snapshot().isEmpty());
                        assertTrue(result.graph().isEmpty());
                        assertTrue(result.analyses().isEmpty());
                        assertEquals(1, result.inputProblemCount());
                        var api = new ArchitectureQueryApi(store, reports, ApiLimits.defaults(), jobs);
                        var response = AnalysisJobApiTest.call(api, "POST",
                                        "/api/v1/analysis/jobs/" + view.id() + "/input-diagnostics", "{}");
                        assertEquals(200, response.status());
                        var item = GraphJson.object(
                                        GraphJson.array(AnalysisJobApiTest.data(response).get("items")).getFirst());
                        assertEquals(result.acquisition().orElseThrow().value(), item.get("acquisition"));
                        assertEquals("ROOT_NOT_FOUND", GraphJson.object(item.get("problem")).get("reason"));
                        assertFalse(CanonicalJson.write(item).contains(source.toString()));
                }
        }

        @Test
        void gradleVendoredLocalJarRecoversClasspathAndDegradedPolicyPreservesGraph() throws Exception {
                var source = Files.createDirectory(directory.resolve("gradle-local-jar"));
                Files.writeString(source.resolve("gradle.properties"), "libVersion=1.0.0\n");
                Files.writeString(source.resolve("build.gradle"),
                                "plugins { id 'java' }\ndependencies { implementation fileTree(dir: 'libs', include: ['*.jar']) }\n");
                Files.createDirectories(source.resolve("libs"));
                try (var jar = new java.util.jar.JarOutputStream(
                                Files.newOutputStream(source.resolve("libs/vendored.jar")))) {
                        jar.putNextEntry(new java.util.jar.JarEntry("META-INF/MANIFEST.MF"));
                        jar.write("Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
                        jar.closeEntry();
                }
                var jarDigest = ContentDigest.sha256(Files.readAllBytes(source.resolve("libs/vendored.jar")));
                Files.createDirectories(source.resolve("src/main/java/demo"));
                Files.writeString(source.resolve("src/main/java/demo/App.java"), "package demo; public class App {}\n");
                var cache = Files.createDirectory(directory.resolve("gradle-local-cache"));
                byte[] malformedPolicy = "\u0000: [unclosed".getBytes(StandardCharsets.UTF_8);
                var runner = new LocalAnalysisRunner(new LocalAnalysisRunner.Input(source,
                                RepositoryIdentity.fromCanonicalCoordinate("urn:test:gradle-local-jar"),
                                Path.of(System.getProperty("java.home")), 21, cache, Optional.empty(), true, false,
                                List.of(),
                                Optional.of(new LocalAnalysisRunner.PolicyInput(malformedPolicy, Scope.TYPE)),
                                LocalAnalysisRunner.SpringInput.absent()));
                var store = new InMemoryGraphStore();
                var reports = new PublishedReports();
                try (var jobs = new AnalysisJobs(List.of(runner.registration("gradle-local")), store, reports,
                                new AnalysisJobs.Limits(1, 1, 4, 90_000, 100))) {
                        var view = finishedReal(jobs, jobs.submit("gradle-local", "run-1").job().id());
                        assertTrue(Set.of(AnalysisJobs.State.SUCCEEDED, AnalysisJobs.State.PARTIAL)
                                        .contains(view.state()), () -> CanonicalJson.write(view));
                        var result = view.result().orElseThrow();
                        assertTrue(result.graph().isPresent());
                        var graph = store.load(result.graph().orElseThrow()).orElseThrow();
                        assertEquals(1, graph.nodes().stream().filter(n -> n.kind().equals("TYPE")).count());
                        assertTrue(Files.isRegularFile(
                                        cache.resolve(".evo-aar-views/" + jarDigest.value().substring(7) + ".jar")));
                        var allGaps = jobs.gaps(UUID.fromString(view.id()), 50, Optional.empty()).items();
                        assertTrue(allGaps.stream().anyMatch(g -> g.detectingProvider().id().equals("policy.compiler")
                                        || g.reasonCode().equals("POLICY_EVALUATION_DEGRADED")));
                        assertTrue(allGaps.stream()
                                        .noneMatch(g -> g.reasonCode().equals("GRADLE_VERSION_SELECTION_REQUIRED")
                                                        || g.reasonCode().equals("GRADLE_BINARY_UNAVAILABLE")));
                }
        }
}
