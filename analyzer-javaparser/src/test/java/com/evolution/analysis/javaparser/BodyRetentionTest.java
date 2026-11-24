package com.evolution.analysis.javaparser;

import com.evolution.analysis.ingestion.IngestionEvidence;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BodyRetentionTest {
    @Test void sourceBodiesAreReleasedAndCrossFileLocalLambdaAndAnonymousFactsReplayExactly()throws Exception {
        var request=TestInputs.request(Map.of(
                "fixture/Api.java","package fixture; interface Api { int work(); } class Service implements Api { public int work(){ return 1; } }",
                "fixture/Client.java","""
                    package fixture;
                    class Client {
                      Runnable initialization=()->{ Service service=new Service(); service.work(); service.work(); };
                      int run(Service service) {
                        class Local { int get(){ return service.work(); } }
                        java.util.function.IntSupplier f=()->service.work();
                        Api anonymous=new Api(){ public int work(){return service.work();} };
                        return new Local().get()+f.getAsInt()+anonymous.work();
                      }
                    }
                    """),List.of());
        var baseline=new Extraction(request,false);var expected=baseline.run();
        var compact=new Extraction(request,true);var actual=compact.run();
        assertEquals(expected,actual);
        assertEquals(IngestionEvidence.digest(expected),IngestionEvidence.digest(actual));
        assertTrue(baseline.retainedBodyStatements()>10);
        // Empty declaration body blocks remain; executable statements/local bodies do not.
        assertTrue(compact.retainedBodyStatements()<baseline.retainedBodyStatements()/2);
        assertEquals(0,compact.retainedCalls(),"Field lambda bodies must not leak through declaration skeletons");
    }

    @Test void primaryBodyRetentionScalesWithHeapAndCachesKotlinSkeletonsByDigestAndFilename() {
        // Low heap headroom disables full-body retention cleanly to prevent OOM
        assertEquals(0L, Extraction.primaryBodyRetentionCharBudget(512L << 20, 450L << 20));
        assertEquals(0, Extraction.primaryBodyRetentionFileLimit(0L));
        long smallBudget = Extraction.primaryBodyRetentionCharBudget(256L << 20, 64L << 20);
        long largeBudget = Extraction.primaryBodyRetentionCharBudget(4096L << 20, 256L << 20);
        assertTrue(smallBudget > 0L && smallBudget < largeBudget,
                "Body retention budget should scale monotonically with available heap");
        assertTrue(largeBudget <= (64L << 20) / 16L, "Body retention budget must stay bounded on huge heaps");

        // Kotlin skeleton caching by (filename, digest) for reactor sources & distinct facade names
        String ktSource = "package demo\nfun helper(x: Int): Int { return x + 1 }\n";
        var repo = com.evolution.analysis.contract.identity.RepositoryIdentity
                .fromCanonicalCoordinate("https://example.test/kt-skeleton-cache.git");
        var appMod = com.evolution.analysis.contract.source.ModuleDescriptor.create(repo, "app", "app");
        var siblingMod = com.evolution.analysis.contract.source.ModuleDescriptor.create(repo, "sibling", "sibling");
        byte[] callerBytes = "package demo; class Caller { int run() { return AlphaKt.helper(1) + BetaKt.helper(2); } }"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var callerInput = new com.evolution.analysis.frontend.SourceInput(
                com.evolution.analysis.contract.source.SourceDocument.create(
                        repo, appMod, "app/src/main/java/demo/Caller.java",
                        com.evolution.analysis.contract.common.ContentDigest.sha256(callerBytes),
                        com.evolution.analysis.contract.source.SourceClassification.MAIN),
                callerBytes);
        byte[] ktBytes = ktSource.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var ktDigest = com.evolution.analysis.contract.common.ContentDigest.sha256(ktBytes);
        var alphaInput = new com.evolution.analysis.frontend.SourceInput(
                com.evolution.analysis.contract.source.SourceDocument.create(
                        repo, siblingMod, "sibling/src/main/kotlin/demo/Alpha.kt", ktDigest,
                        com.evolution.analysis.contract.source.SourceClassification.MAIN),
                ktBytes);
        var betaInput = new com.evolution.analysis.frontend.SourceInput(
                com.evolution.analysis.contract.source.SourceDocument.create(
                        repo, siblingMod, "sibling/src/main/kotlin/demo/Beta.kt", ktDigest,
                        com.evolution.analysis.contract.source.SourceClassification.MAIN),
                ktBytes);
        var platform = TestInputs.request("class Dummy {}").platform();
        var reactorInput = com.evolution.analysis.frontend.ReactorSourceInput.create(
                siblingMod.identity(), com.evolution.analysis.contract.source.SourceClassification.MAIN, 0,
                List.of(alphaInput, betaInput));
        var plan = new com.evolution.analysis.frontend.FrontendPlan(
                Optional.of(21), Optional.of(21), false,
                com.evolution.analysis.contract.common.ContentDigest.sha256Utf8("kt-reactor-cp"),
                com.evolution.analysis.contract.common.ContentDigest.sha256Utf8("kt-reactor-dec"));
        var docs = List.of(callerInput.document(), alphaInput.document(), betaInput.document());
        var snapshot = com.evolution.analysis.contract.source.RepositorySnapshot.create(
                repo, Optional.empty(), false,
                docs.stream().map(com.evolution.analysis.contract.source.SnapshotFile::from).toList(), docs);
        var component = new com.evolution.analysis.contract.analysis.ManifestComponent(
                new com.evolution.analysis.contract.common.VersionedIdentifier("test.fixture", "1"),
                com.evolution.analysis.contract.common.ContentDigest.sha256Utf8("kt-skeleton-fixture"));
        var manifest = com.evolution.analysis.contract.analysis.AnalysisManifest.create(
                new com.evolution.analysis.contract.common.VersionedIdentifier("analysis.manifest", "2"),
                snapshot, List.of(appMod, siblingMod), List.of(platform.entry()),
                com.evolution.analysis.contract.analysis.AnalysisConfiguration.create(
                        new com.evolution.analysis.contract.common.VersionedIdentifier("analysis.configuration", "2"),
                        com.evolution.analysis.frontend.FrontendRequest.options(
                                plan, appMod.identity(),
                                com.evolution.analysis.contract.source.SourceClassification.MAIN,
                                List.of(callerInput.document()), platform, List.of(reactorInput))),
                component, component, component);
        var req = new com.evolution.analysis.frontend.FrontendRequest(
                manifest, appMod.identity(), com.evolution.analysis.contract.source.SourceClassification.MAIN,
                plan, List.of(callerInput), platform, List.of(), List.of(reactorInput));
        var first = new JavaParserFrontend().analyze(req);
        long hitsBefore = Extraction.KOTLIN_SKELETON_CACHE_HITS.get();
        var second = new JavaParserFrontend().analyze(req);
        assertEquals(first, second);
        assertTrue(Extraction.KOTLIN_SKELETON_CACHE_HITS.get() >= hitsBefore + 2,
                "Second run must reuse cached .kt skeletons for both Alpha.kt and Beta.kt");
        assertEquals(2, first.observations().stream()
                .filter(o -> o.category().value().equals("java.calls")
                        && o.attribution() == com.evolution.analysis.contract.semantic.SemanticStatus.RESOLVED).count(),
                "Identical .kt bytes with distinct filenames must keep distinct AlphaKt and BetaKt facades");
    }
}
