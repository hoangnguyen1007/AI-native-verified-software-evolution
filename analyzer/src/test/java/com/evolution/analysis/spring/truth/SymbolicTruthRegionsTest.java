package com.evolution.analysis.spring.truth;

import com.evolution.analysis.contract.analysis.AnalysisConfiguration;
import com.evolution.analysis.contract.analysis.AnalysisManifest;
import com.evolution.analysis.contract.analysis.ManifestComponent;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.source.ModuleDescriptor;
import com.evolution.analysis.contract.source.RepositorySnapshot;
import com.evolution.analysis.contract.source.SnapshotFile;
import com.evolution.analysis.contract.source.SourceClassification;
import com.evolution.analysis.contract.source.SourceDocument;
import com.evolution.analysis.frontend.FrontendPlan;
import com.evolution.analysis.frontend.FrontendRequest;
import com.evolution.analysis.frontend.PlatformInput;
import com.evolution.analysis.frontend.SourceInput;
import com.evolution.analysis.ingestion.IngestionFixtures;
import com.evolution.analysis.spring.SpringFrameworkEvidence;
import com.evolution.analysis.spring.condition.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests for {@link SymbolicTruthRegions} quantitative {@code mayExposureRatio},
 * {@code validWorldCount}, SpEL numeric threshold domain hints, and {@code DDNNF_COUNT_BOUNDED} fallback.
 */
class SymbolicTruthRegionsTest {

    private static SpringBuildContext buildContext() {
        var provider = new VersionedIdentifier("sym-truth-test", "1");
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/sym-truth-test.git");
        var module = ModuleDescriptor.create(repo, ".", "app");
        byte[] bytes = "package demo; class App {}".getBytes(StandardCharsets.UTF_8);
        var doc = SourceDocument.create(repo, module, "src/main/java/demo/App.java",
                ContentDigest.sha256(bytes), SourceClassification.MAIN);
        var snapshot = RepositorySnapshot.create(repo, Optional.empty(), false,
                List.of(SnapshotFile.from(doc)), List.of(doc));
        var platform = PlatformInput.create(21, "21", "test", List.of(
                new PlatformInput.Artifact("java.base.jmod", ContentDigest.sha256Utf8("jbase"),
                        Path.of("java.base.jmod"), PlatformInput.Format.JMOD)));
        var plan = new FrontendPlan(Optional.of(21), Optional.of(21), false,
                ContentDigest.sha256Utf8("cp"), ContentDigest.sha256Utf8("dec"));
        var options = FrontendRequest.options(plan, module.identity(), SourceClassification.MAIN, List.of(doc), platform);
        var comp = new ManifestComponent(provider, ContentDigest.sha256Utf8("comp"));
        var manifest = AnalysisManifest.create(provider, snapshot, List.of(module), List.of(platform.entry()),
                AnalysisConfiguration.create(provider, options), comp, comp, comp);
        var request = new FrontendRequest(manifest, module.identity(), SourceClassification.MAIN, plan,
                List.of(new SourceInput(doc, bytes)), platform, List.of());
        return SpringBuildContext.fromExactRequest(request, ContentDigest.sha256Utf8("sym-truth-build"));
    }

    @Test
    void exposesExactMayExposureRatioAndValidWorldCountAcrossMultiValueDomainsAnd56Dimensions() throws Exception {
        var config = new ConfigDataIngestion().ingest(
                IngestionFixtures.inputs(Map.of("src/main/resources/application.properties", "app.cache=redis\n")),
                ConfigDataIngestion.Policy.defaults());
        var build = buildContext();
        var semantics = ConditionEvidenceLowering.semantics(
                new SpringFrameworkEvidence(false, Optional.empty(), List.of()));
        var ev = new ConditionEvidence.Derived(List.of(config.identity()),
                new VersionedIdentifier("sym-truth-test", "1"), "sym-truth-test");

        // 8-value property domain (MISSING + 7 values) + 2-value profile -> 8 * 2 = 16 valid worlds
        var cacheVar = new FiniteDomain.Variable(FiniteDomain.Kind.PROPERTY, "app.cache");
        var cacheVals = List.of(
                FiniteDomain.Value.missing(),
                FiniteDomain.Value.exact("redis"),
                FiniteDomain.Value.exact("caffeine"),
                FiniteDomain.Value.exact("ehcache"),
                FiniteDomain.Value.exact("hazelcast"),
                FiniteDomain.Value.exact("infinispan"),
                FiniteDomain.Value.exact("jcache"),
                FiniteDomain.Value.exact("none"));
        var profVar = new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE, "prod");
        var domains = List.of(
                new FiniteDomain(cacheVar, cacheVals, ev),
                new FiniteDomain(profVar, List.of(FiniteDomain.Value.bool(false), FiniteDomain.Value.bool(true)), ev));

        var baseSelected = RepositoryConfigurationSpaceBuilder.build(build, config, List.of());
        var space = new ConfigurationSpace(
                baseSelected.space().buildContext(),
                baseSelected.space().repositoryEnvelope(),
                baseSelected.space().deploymentEnvelope(),
                domains,
                List.of(),
                baseSelected.space().precedencePolicy(),
                baseSelected.space().profilePolicy(),
                baseSelected.space().abstractionVersion(),
                baseSelected.space().feasibilityPolicy());

        // Occ 1: @ConditionalOnProperty(app.cache=redis) -> 1/8 = 0.125 (2/16 worlds)
        var occMay = new ConditionOccurrence(
                ConditionExpression.atom(semantics, new ConditionExpression.Property("app", List.of("cache"), "redis", false)),
                ev, "occ/may-redis", "BEAN", 0);
        // Occ 2: (@ConditionalOnProperty(app.cache=redis) AND @Profile("prod")) -> 1/16 = 0.0625
        var occMayTight = new ConditionOccurrence(
                ConditionExpression.all(semantics, List.of(
                        ConditionExpression.atom(semantics, new ConditionExpression.Property("app", List.of("cache"), "redis", false)),
                        ConditionExpression.atom(semantics, new ConditionExpression.Profile("prod")))),
                ev, "occ/may-redis-prod", "BEAN", 1);
        // Occ 3: Constant TRUE -> MUST (ratio 1)
        var occMust = new ConditionOccurrence(
                ConditionExpression.atom(semantics, new ConditionExpression.Constant(LogicalValue.TRUE)),
                ev, "occ/must", "BEAN", 2);
        // Occ 4: Constant FALSE -> NEVER (ratio 0)
        var occNever = new ConditionOccurrence(
                ConditionExpression.atom(semantics, new ConditionExpression.Constant(LogicalValue.FALSE)),
                ev, "occ/never", "BEAN", 3);

        var model = ConditionModel.create(space, List.of(occMay, occMayTight, occMust, occNever));
        var result = SymbolicTruthRegions.evaluate(
                model, semantics, IncrementalCdclReasoner.INSTANCE, SymbolicConfiguration.Limits.defaults());

        assertEquals(Optional.of(BigInteger.valueOf(16)), result.validWorldCount());
        assertEquals(4, result.rows().size());
        var rowByOcc = new HashMap<ConditionOccurrence.Identity, SymbolicTruthRegions.Row>();
        result.rows().forEach(r -> rowByOcc.put(r.occurrence(), r));

        var rMay = rowByOcc.get(occMay.identity());
        assertEquals(TruthRegionEvaluation.Classification.MAY, rMay.classification());
        assertEquals(0, new BigDecimal("0.125").compareTo(rMay.mayExposureRatio().orElseThrow()));
        assertEquals(Optional.of(BigInteger.valueOf(16)), rMay.validWorldCount());

        var rMayTight = rowByOcc.get(occMayTight.identity());
        assertEquals(TruthRegionEvaluation.Classification.MAY, rMayTight.classification());
        assertEquals(0, new BigDecimal("0.0625").compareTo(rMayTight.mayExposureRatio().orElseThrow()));

        var rMust = rowByOcc.get(occMust.identity());
        assertEquals(TruthRegionEvaluation.Classification.MUST, rMust.classification());
        assertEquals(0, BigDecimal.ONE.compareTo(rMust.mayExposureRatio().orElseThrow()));

        var rNever = rowByOcc.get(occNever.identity());
        assertEquals(TruthRegionEvaluation.Classification.NEVER, rNever.classification());
        assertEquals(0, BigDecimal.ZERO.compareTo(rNever.mayExposureRatio().orElseThrow()));

        // I2 determinism check
        var replay = SymbolicTruthRegions.evaluate(
                model, semantics, IncrementalCdclReasoner.INSTANCE, SymbolicConfiguration.Limits.defaults());
        assertEquals(result.identity(), replay.identity());

        // Safe bounded fallback (I3): tight maxNodes preserves exact CDCL MUST/MAY/NEVER + emits DDNNF_COUNT_BOUNDED
        var bounded = SymbolicTruthRegions.evaluate(
                model, semantics, IncrementalCdclReasoner.INSTANCE, SymbolicConfiguration.Limits.defaults(),
                new SlicedDdnnfCompiler.Limits(3, 64));
        var boundedByOcc = new HashMap<ConditionOccurrence.Identity, SymbolicTruthRegions.Row>();
        bounded.rows().forEach(r -> boundedByOcc.put(r.occurrence(), r));
        assertEquals(TruthRegionEvaluation.Classification.MAY, boundedByOcc.get(occMay.identity()).classification());
        assertEquals(TruthRegionEvaluation.Classification.MUST, boundedByOcc.get(occMust.identity()).classification());
        assertEquals(TruthRegionEvaluation.Classification.NEVER, boundedByOcc.get(occNever.identity()).classification());
        assertTrue(bounded.validWorldCount().isEmpty());
        assertTrue(boundedByOcc.get(occMay.identity()).mayExposureRatio().isEmpty());
        assertTrue(bounded.gaps().stream().anyMatch(
                g -> SlicedDdnnfCompiler.GAP_DDNNF_COUNT_BOUNDED.equals(g.reasonCode())),
                () -> "Expected DDNNF_COUNT_BOUNDED gap, got " + bounded.gaps());

        // 56-dimension decoupled space: exact 2^56 validWorldCount and 0.5 exposure ratio in milliseconds!
        var bigDomains = new ArrayList<FiniteDomain>(56);
        bigDomains.add(new FiniteDomain(profVar, List.of(FiniteDomain.Value.bool(false), FiniteDomain.Value.bool(true)), ev));
        for (int i = 0; i < 55; i++) {
            bigDomains.add(new FiniteDomain(
                    new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE, "extra-" + i),
                    List.of(FiniteDomain.Value.bool(false), FiniteDomain.Value.bool(true)),
                    ev));
        }
        var bigSpace = new ConfigurationSpace(
                baseSelected.space().buildContext(),
                baseSelected.space().repositoryEnvelope(),
                baseSelected.space().deploymentEnvelope(),
                bigDomains,
                List.of(),
                baseSelected.space().precedencePolicy(),
                baseSelected.space().profilePolicy(),
                baseSelected.space().abstractionVersion(),
                new ConfigurationSpace.FeasibilityPolicy(
                        baseSelected.space().feasibilityPolicy().version(),
                        new ConfigurationSpace.Limits(128, 4096, Long.MAX_VALUE, 10_000, 100_000, 256),
                        baseSelected.space().feasibilityPolicy().evidence()));
        var occProf = new ConditionOccurrence(
                ConditionExpression.atom(semantics, new ConditionExpression.Profile("prod")),
                ev, "occ/prof-56d", "BEAN", 0);
        var bigResult = SymbolicTruthRegions.evaluate(
                ConditionModel.create(bigSpace, List.of(occProf)),
                semantics, IncrementalCdclReasoner.INSTANCE, SymbolicConfiguration.Limits.defaults());
        assertEquals(Optional.of(BigInteger.ONE.shiftLeft(56)), bigResult.validWorldCount());
        assertEquals(0, new BigDecimal("0.5").compareTo(bigResult.rows().getFirst().mayExposureRatio().orElseThrow()));
    }

    @Test
    void spelNumericThresholdExtractsNumericBoundaryDomainHintsAndComputesExactRatio() throws Exception {
        var config = new ConfigDataIngestion().ingest(
                IngestionFixtures.inputs(Map.of("src/main/resources/application.properties", "app.workers=2\n")),
                ConfigDataIngestion.Policy.defaults());
        var build = buildContext();
        var semantics = ConditionEvidenceLowering.semantics(
                new SpringFrameworkEvidence(false, Optional.empty(), List.of()));
        var ev = new ConditionEvidence.Derived(List.of(config.identity()),
                new VersionedIdentifier("spel-num-test", "1"), "spel-num-test");

        var occSpel = new ConditionOccurrence(
                ConditionExpression.atom(semantics, new ConditionExpression.BasicSpel("#{${app.workers:2} >= 4}")),
                ev, "occ/spel-workers", "BEAN", 0);

        var selected = RepositoryConfigurationSpaceBuilder.build(build, config, List.of(occSpel));
        var workersDomain = selected.space().domains().stream()
                .filter(d -> d.variable().name().equals("app.workers"))
                .findFirst().orElseThrow();
        var exactVals = workersDomain.values().stream()
                .flatMap(v -> v.exact().stream()).toList();
        assertTrue(exactVals.containsAll(List.of("2", "3", "4", "5")),
                () -> "Expected numeric boundary hints {2, 3, 4, 5}, got " + exactVals);

        var model = ConditionModel.create(selected.space(), List.of(occSpel));
        var result = SymbolicTruthRegions.evaluate(
                model, semantics, IncrementalCdclReasoner.INSTANCE, SymbolicConfiguration.Limits.defaults());
        var row = result.rows().getFirst();
        assertEquals(TruthRegionEvaluation.Classification.MAY, row.classification());
        // Domain has {MISSING(default 2->false), 2(false), 3(false), 4(true), 5(true)} -> 2/5 = 0.4!
        assertEquals(0, new BigDecimal("0.4").compareTo(row.mayExposureRatio().orElseThrow()),
                () -> "Expected exact 2/5=0.4 ratio for #{${app.workers:2} >= 4}, got " + row.mayExposureRatio());
        assertTrue(result.validWorldCount().orElseThrow().compareTo(BigInteger.ZERO) > 0);
    }
}
