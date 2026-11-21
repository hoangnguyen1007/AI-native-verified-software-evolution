package com.evolution.analysis.ingestion;

import static org.junit.jupiter.api.Assertions.*;

import com.evolution.analysis.acquisition.AcquiredFile;
import com.evolution.analysis.acquisition.RepositoryAcquisitionPolicy;
import com.evolution.analysis.acquisition.RepositoryAcquisitionRequest;
import com.evolution.analysis.acquisition.RepositoryAcquisitionResult;
import com.evolution.analysis.buildmodel.UniversalBuildIngestion;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.RepositorySnapshot;
import com.evolution.analysis.contract.source.SnapshotFile;
import com.evolution.analysis.frontend.CategoryCoverage;
import com.evolution.analysis.frontend.FrontendRequest;
import com.evolution.analysis.frontend.FrontendResult;
import com.evolution.analysis.frontend.SourceOutcome;
import com.evolution.analysis.graph.CanonicalGraphBuilder;
import com.evolution.analysis.graph.CanonicalGraphInput;
import com.evolution.analysis.graph.GraphEvidence;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FastArchitecturePipelineContractTest {

    @Test
    void decoderPolicyPreservesFullCanonicalParityAndFiltersNonArchitecturalInputsWhenRequested() {
        var legacy = new RepositoryInputDecoder.Policy("UTF-8", 10_000, 10_000_000);
        var explicitFull = legacy.withArchitecturalInputsOnly(false);
        var fast = legacy.withArchitecturalInputsOnly(true);

        assertEquals(legacy, explicitFull);
        assertNotEquals(legacy, fast);
        assertEquals("{\"charset\":\"UTF-8\",\"maxTextBytes\":10000000,\"maxTextFiles\":10000}",
                CanonicalJson.write(legacy));
        assertEquals(CanonicalJson.sha256(legacy), CanonicalJson.sha256(explicitFull));
        assertNotEquals(CanonicalJson.sha256(legacy), CanonicalJson.sha256(fast));

        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/fast-decode.git");
        var files = List.of(
                new AcquiredFile("src/main/java/demo/App.java", "package demo; class App {}".getBytes(StandardCharsets.UTF_8)),
                new AcquiredFile("src/main/resources/beans.xml", "<beans><bean id=\"a\" class=\"demo.App\"/></beans>".getBytes(StandardCharsets.UTF_8)),
                new AcquiredFile("src/main/webapp/WEB-INF/web.xml", "<web-app><servlet><servlet-name>s</servlet-name></servlet></web-app>".getBytes(StandardCharsets.UTF_8)),
                new AcquiredFile("config/checkstyle.xml", "<module name=\"Checker\"/>".getBytes(StandardCharsets.UTF_8)),
                new AcquiredFile("package-lock.json", "{\"name\":\"ui\",\"lockfileVersion\":3}".getBytes(StandardCharsets.UTF_8)),
                new AcquiredFile("node_modules/pkg/index.yml", "ignored: true".getBytes(StandardCharsets.UTF_8)));
        var snapshotFiles = files.stream().map(f -> new SnapshotFile(f.path(), f.contentDigest())).toList();
        var snapshot = RepositorySnapshot.create(repo, Optional.of("rev-1"), false, snapshotFiles, List.of());
        var req = new RepositoryAcquisitionRequest(repo, Optional.of("rev-1"), false, "pom.xml",
                new RepositoryAcquisitionPolicy(100, 100, 1000, 100_000, 8, List.of()));
        var acquisition = new RepositoryAcquisitionResult(
                RepositoryAcquisitionResult.SCHEMA, req, new VersionedIdentifier("test.acquirer", "1"),
                RepositoryAcquisitionResult.Completion.COMPLETE, Optional.of(snapshot), files,
                List.of("."), List.of(), List.of(), List.of());

        var fullDecoded = new RepositoryInputDecoder().decode(acquisition, legacy);
        var fastDecoded = new RepositoryInputDecoder().decode(acquisition, fast);

        assertEquals(6, fullDecoded.inputs().orElseThrow().files().size());
        assertEquals(3, fastDecoded.inputs().orElseThrow().files().size());
        assertTrue(fastDecoded.inputs().orElseThrow().files().containsKey("src/main/java/demo/App.java"));
        assertTrue(fastDecoded.inputs().orElseThrow().files().containsKey("src/main/resources/beans.xml"));
        assertTrue(fastDecoded.inputs().orElseThrow().files().containsKey("src/main/webapp/WEB-INF/web.xml"));
        assertNotEquals(fullDecoded.identity(), fastDecoded.identity());
    }

    @Test
    void pipelinePrecomputedBuildOptionsAndFastGraphBuilderPreserveFullParityAndOmitBase64WhenFast() {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/fast-pipeline.git");
        var javaFile = new AcquiredFile("src/main/java/demo/App.java",
                "package demo;\npublic class App {}\n".getBytes(StandardCharsets.UTF_8));
        var snapshot = RepositorySnapshot.create(repo, Optional.of("rev-1"), false,
                List.of(new SnapshotFile(javaFile.path(), javaFile.contentDigest())), List.of());
        var req = new RepositoryAcquisitionRequest(repo, Optional.of("rev-1"), false, "pom.xml",
                new RepositoryAcquisitionPolicy(100, 100, 1000, 100_000, 8, List.of()));
        var acquisition = new RepositoryAcquisitionResult(
                RepositoryAcquisitionResult.SCHEMA, req, new VersionedIdentifier("test.acquirer", "1"),
                RepositoryAcquisitionResult.Completion.COMPLETE, Optional.of(snapshot), List.of(javaFile),
                List.of("."), List.of(), List.of(), List.of());

        var provider = new VersionedIdentifier("frontend.stub", "1");
        com.evolution.analysis.frontend.SemanticFrontend stubFrontend = request -> {
            var coverage = FrontendRequest.CATEGORIES.stream()
                    .map(c -> new CategoryCoverage(
                            new com.evolution.analysis.contract.semantic.RelationshipKind("java." + c),
                            CategoryCoverage.Support.IMPLEMENTED, 0, 0, 0))
                    .toList();
            var outcomes = request.sources().stream()
                    .map(s -> new SourceOutcome(s.document().identity(), SourceOutcome.State.PROCESSED, List.of()))
                    .toList();
            return new FrontendResult(request.manifest().identity(), provider, FrontendResult.State.COMPLETED,
                    List.of(), List.of(), List.of(), outcomes, coverage, List.of());
        };

        var basePolicy = UniversalArchitecturePipeline.Policy.builder(stubFrontend, provider)
                .defaultPlatformRelease(21)
                .build();
        var prepared = new RepositoryInputDecoder.Prepared(acquisition, basePolicy.decodePolicy());
        var precomputedBuild = new UniversalBuildIngestion().ingest(
                prepared.result().inputs().orElseThrow(), basePolicy.buildPolicy(),
                basePolicy.mavenProvider(), basePolicy.externalPoms(), basePolicy.mavenPolicy());

        var pipeline = new UniversalArchitecturePipeline();
        var baselineDetailed = pipeline.runWithEvidence(prepared, basePolicy);
        var precomputedDetailed = pipeline.runWithEvidence(prepared, basePolicy.withPrecomputedBuild(precomputedBuild));
        assertEquals(baselineDetailed.report().identity(), precomputedDetailed.report().identity());

        var builder = new CanonicalGraphBuilder();
        var fullGraph = builder.build(baselineDetailed.graphInput().orElseThrow(), false);
        var defaultGraph = builder.build(baselineDetailed.graphInput().orElseThrow());
        assertEquals(defaultGraph.identity(), fullGraph.identity());
        assertTrue(fullGraph.evidence().stream()
                .filter(e -> e.family().equals("source.content"))
                .allMatch(e -> e.canonicalJson().contains("bytesBase64")));

        var fastPolicy = basePolicy
                .withPrecomputedBuild(precomputedBuild)
                .withAnalysisOptions(Map.of("analysis.profile", "FAST_ARCHITECTURE"));
        var fastDetailed = pipeline.runWithEvidence(prepared, fastPolicy);
        assertNotEquals(baselineDetailed.report().identity(), fastDetailed.report().identity());

        CanonicalGraphInput fastInput = fastDetailed.graphInput().orElseThrow();
        var fastGraphAuto = builder.build(fastInput);
        var fastGraphExplicit = builder.build(fastInput, true);
        assertEquals(fastGraphExplicit.identity(), fastGraphAuto.identity());
        List<GraphEvidence> fastSourceContents = fastGraphExplicit.evidence().stream()
                .filter(e -> e.family().equals("source.content"))
                .toList();
        assertFalse(fastSourceContents.isEmpty());
        assertTrue(fastSourceContents.stream().noneMatch(e -> e.canonicalJson().contains("bytesBase64")));
        assertTrue(fastSourceContents.stream().allMatch(e -> e.canonicalJson().contains(javaFile.contentDigest().value())));
    }
}
