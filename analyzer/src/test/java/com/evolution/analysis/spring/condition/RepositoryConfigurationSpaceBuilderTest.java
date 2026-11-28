package com.evolution.analysis.spring.condition;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.SourceInput;
import com.evolution.analysis.ingestion.*;
import com.evolution.analysis.spring.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RepositoryConfigurationSpaceBuilderTest {

        @Test
        void extractsMultiProfileAndPropertyDomainsWithExplicitExternalOverrideGap() throws Exception {
                var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/sat-space-builder.git");
                var module = ModuleDescriptor.create(repo, ".", "app");
                var files = new LinkedHashMap<String, String>();
                files.put("src/main/resources/application.yml", """
                                spring:
                                  profiles:
                                    active: dev
                                app:
                                  feature: false
                                """);
                files.put("src/main/resources/application-prod.yml", """
                                app:
                                  feature: true
                                """);

                var docs = new ArrayList<SourceDocument>();
                var snapshotFiles = new ArrayList<SnapshotFile>();
                var sourceInputs = new LinkedHashMap<String, SourceInput>();
                files.forEach((path, text) -> {
                        var doc = SourceDocument.create(repo, module, path, ContentDigest.sha256Utf8(text),
                                        SourceClassification.OTHER);
                        docs.add(doc);
                        snapshotFiles.add(SnapshotFile.from(doc));
                        sourceInputs.put(path, new SourceInput(doc, text.getBytes(StandardCharsets.UTF_8)));
                });
                var snapshot = RepositorySnapshot.create(repo, Optional.empty(), false, snapshotFiles, docs);
                var repoInputs = new RepositoryInputs(snapshot, sourceInputs);
                var config = new ConfigDataIngestion().ingest(repoInputs, ConfigDataIngestion.Policy.defaults());

                var build = ConditionLoweringTest.fixture(List.of()).build();
                var semantics = ConditionEvidenceLowering.semantics(
                                new SpringFrameworkEvidence(false, Optional.of(ContentDigest.sha256Utf8("cp")),
                                                List.of()));
                var srcEvidence = config.documents().getFirst().evidence();
                var occurrences = new ArrayList<ConditionOccurrence>();
                occurrences.add(new ConditionOccurrence(
                                ConditionExpression.atom(semantics, new ConditionExpression.Profile("dev")),
                                srcEvidence, "cond/dev", "BEAN", 0));
                occurrences.add(new ConditionOccurrence(
                                ConditionExpression.atom(semantics, new ConditionExpression.Profile("staging")),
                                srcEvidence, "cond/staging", "BEAN", 1));
                occurrences.add(new ConditionOccurrence(
                                ConditionExpression.atom(semantics, new ConditionExpression.Property(
                                                "app", List.of("feature"), "true", false)),
                                srcEvidence, "cond/feature", "BEAN", 2));
                occurrences.add(new ConditionOccurrence(
                                ConditionExpression.atom(semantics,
                                                new ConditionExpression.BasicSpel("${app.spelFlag:false}")),
                                srcEvidence, "cond/spel", "BEAN", 3));

                var selected = RepositoryConfigurationSpaceBuilder.build(build, config, occurrences);
                var domainNames = selected.space().domains().stream()
                                .map(d -> d.variable().kind() + ":" + d.variable().name())
                                .toList();

                assertTrue(domainNames.contains("PROFILE:dev"));
                assertTrue(domainNames.contains("PROFILE:prod"));
                assertTrue(domainNames.contains("PROFILE:staging"));
                assertTrue(domainNames.contains("PROPERTY:app.feature"));
                assertTrue(domainNames.contains("PROPERTY:app.spelFlag"));

                var featureDomain = selected.space().domains().stream()
                                .filter(d -> d.variable().kind() == FiniteDomain.Kind.PROPERTY
                                                && d.variable().name().equals("app.feature"))
                                .findFirst().orElseThrow();
                assertTrue(featureDomain.values().contains(FiniteDomain.Value.missing()));
                assertTrue(featureDomain.values().contains(FiniteDomain.Value.exact("false")));
                assertTrue(featureDomain.values().contains(FiniteDomain.Value.exact("true")));
                assertEquals(FiniteDomain.Value.exact("false"),
                                selected.baseline().baseline().get(
                                                new FiniteDomain.Variable(FiniteDomain.Kind.PROPERTY, "app.feature")));

                assertTrue(selected.space().deploymentEnvelope().isEmpty());
                assertTrue(selected.gaps().stream()
                                .anyMatch(g -> "EXTERNAL_DEPLOYMENT_OVERRIDES_UNOBSERVED".equals(g.reasonCode())));
        }

        @Test
        void capsDimensionsDeterministicallyAndEmitsGap() throws Exception {
                var build = ConditionLoweringTest.fixture(List.of()).build();
                var emptyDocs = List.<SourceDocument>of();
                var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/sat-cap.git");
                var snapshot = RepositorySnapshot.create(repo, Optional.empty(), false, List.of(), emptyDocs);
                var config = new ConfigDataIngestion().ingest(new RepositoryInputs(snapshot, Map.of()),
                                ConfigDataIngestion.Policy.defaults());
                var semantics = ConditionEvidenceLowering.semantics(
                                new SpringFrameworkEvidence(false, Optional.empty(), List.of()));
                var derived = new ConditionEvidence.Derived(List.of(config.identity()),
                                RepositoryConfigurationSpaceBuilder.PROVIDER, "test");
                var occurrences = new ArrayList<ConditionOccurrence>();
                for (int i = 0; i < RepositoryConfigurationSpaceBuilder.MAX_DIMENSIONS + 8; i++) {
                        occurrences.add(new ConditionOccurrence(
                                        ConditionExpression.atom(semantics,
                                                        new ConditionExpression.Profile(
                                                                        "p" + String.format("%02d", i))),
                                        derived, "cond/" + i, "BEAN", i));
                }

                var selected = RepositoryConfigurationSpaceBuilder.build(build, config, occurrences);
                assertEquals(32, RepositoryConfigurationSpaceBuilder.MAX_DIMENSIONS);
                assertEquals(RepositoryConfigurationSpaceBuilder.MAX_DIMENSIONS, selected.space().domains().size());
                assertTrue(selected.gaps().stream()
                                .anyMatch(g -> "CONFIGURATION_SPACE_DIMENSION_BOUNDED".equals(g.reasonCode())));
        }

        @Test
        void prioritizesDirectConditionVariablesAheadOfAmbientConfigProfiles() throws Exception {
                var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/sat-priority.git");
                var module = ModuleDescriptor.create(repo, ".", "app");
                var files = new LinkedHashMap<String, String>();
                var activeProfiles = new StringBuilder();
                for (int i = 0; i < 40; i++) {
                        if (i > 0)
                                activeProfiles.append(",");
                        activeProfiles.append("aaa-ambient-").append(String.format("%02d", i));
                }
                files.put("src/main/resources/application.properties",
                                "spring.profiles.active=" + activeProfiles + "\n");
                var docs = new ArrayList<SourceDocument>();
                var snapshotFiles = new ArrayList<SnapshotFile>();
                var sourceInputs = new LinkedHashMap<String, SourceInput>();
                files.forEach((path, text) -> {
                        var doc = SourceDocument.create(repo, module, path, ContentDigest.sha256Utf8(text),
                                        SourceClassification.OTHER);
                        docs.add(doc);
                        snapshotFiles.add(SnapshotFile.from(doc));
                        sourceInputs.put(path, new SourceInput(doc, text.getBytes(StandardCharsets.UTF_8)));
                });
                var snapshot = RepositorySnapshot.create(repo, Optional.empty(), false, snapshotFiles, docs);
                var config = new ConfigDataIngestion().ingest(new RepositoryInputs(snapshot, sourceInputs),
                                ConfigDataIngestion.Policy.defaults());
                var build = ConditionLoweringTest.fixture(List.of()).build();
                var semantics = ConditionEvidenceLowering
                                .semantics(new SpringFrameworkEvidence(false, Optional.empty(), List.of()));
                var derived = new ConditionEvidence.Derived(List.of(config.identity()),
                                RepositoryConfigurationSpaceBuilder.PROVIDER, "priority-test");
                var occurrences = List.of(
                                new ConditionOccurrence(
                                                ConditionExpression.atom(semantics,
                                                                new ConditionExpression.Profile("zzz-direct-profile")),
                                                derived, "cond/zprof", "BEAN", 0),
                                new ConditionOccurrence(
                                                ConditionExpression.atom(semantics,
                                                                new ConditionExpression.Property("zzz", List.of("flag"),
                                                                                "true", false)),
                                                derived, "cond/zprop", "BEAN", 1));

                var selected = RepositoryConfigurationSpaceBuilder.build(build, config, occurrences);
                var names = selected.space().domains().stream()
                                .map(d -> d.variable().kind() + ":" + d.variable().name()).toList();
                assertEquals(RepositoryConfigurationSpaceBuilder.MAX_DIMENSIONS, names.size());
                assertTrue(names.contains("PROFILE:zzz-direct-profile"),
                                "Direct @Profile must not be evicted by ambient config profiles: " + names);
                assertTrue(names.contains("PROPERTY:zzz.flag"),
                                "Direct @ConditionalOnProperty must not be evicted by ambient config profiles: "
                                                + names);
                assertTrue(selected.gaps().stream()
                                .anyMatch(g -> "CONFIGURATION_SPACE_DIMENSION_BOUNDED".equals(g.reasonCode())));
        }

        @Test
        void collectsLegacySpringProfilesKeysIntoProfileDimensions() throws Exception {
                var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/sat-legacy-profiles.git");
                var module = ModuleDescriptor.create(repo, ".", "app");
                String yml = "spring:\n  profiles: \"!dev & cloud\"\n---\nspring.profiles:\n  - qa\n";
                var doc = SourceDocument.create(repo, module, "src/main/resources/application.yml",
                                ContentDigest.sha256Utf8(yml), SourceClassification.OTHER);
                var config = new ConfigDataIngestion().ingest(
                                new RepositoryInputs(
                                                RepositorySnapshot.create(repo, Optional.empty(), false,
                                                                List.of(SnapshotFile.from(doc)), List.of(doc)),
                                                Map.of(doc.path(), new SourceInput(doc,
                                                                yml.getBytes(StandardCharsets.UTF_8)))),
                                ConfigDataIngestion.Policy.defaults());
                var build = ConditionLoweringTest.fixture(List.of()).build();
                var selected = RepositoryConfigurationSpaceBuilder.build(build, config, List.of());
                var names = selected.space().domains().stream()
                                .filter(d -> d.variable().kind() == FiniteDomain.Kind.PROFILE)
                                .map(d -> d.variable().name()).toList();
                assertTrue(names.containsAll(List.of("dev", "cloud", "qa")), () -> "profiles=" + names);
        }

        @Test
        void retainsNonMatchingWitnessForHavingValueWithMatchIfMissingPreventsFalseMust() throws Exception {
                var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/having-value-soundness.git");
                var module = ModuleDescriptor.create(repo, ".", "app");
                String yml = "app:\n  audit:\n    mode: strict\n";
                var doc = SourceDocument.create(repo, module, "src/main/resources/application.yml",
                                ContentDigest.sha256Utf8(yml), SourceClassification.OTHER);
                var config = new ConfigDataIngestion().ingest(
                                new RepositoryInputs(
                                                RepositorySnapshot.create(repo, Optional.empty(), false,
                                                                List.of(SnapshotFile.from(doc)), List.of(doc)),
                                                Map.of(doc.path(), new SourceInput(doc,
                                                                yml.getBytes(StandardCharsets.UTF_8)))),
                                ConfigDataIngestion.Policy.defaults());
                var build = ConditionLoweringTest.fixture(List.of()).build();
                var semantics = ConditionEvidenceLowering.semantics(
                                new SpringFrameworkEvidence(false, Optional.empty(), List.of()));
                var occ = new ConditionOccurrence(
                                ConditionExpression.atom(semantics, new ConditionExpression.Property(
                                                "app.audit", List.of("mode"), "strict", true)),
                                config.documents().getFirst().evidence(), "cond/audit", "BEAN", 0);

                var selected = RepositoryConfigurationSpaceBuilder.build(build, config, List.of(occ));
                var modeDomain = selected.space().domains().stream()
                                .filter(d -> d.variable().kind() == FiniteDomain.Kind.PROPERTY
                                                && d.variable().name().equals("app.audit.mode"))
                                .findFirst().orElseThrow();
                assertTrue(modeDomain.values().contains(FiniteDomain.Value.missing()));
                assertTrue(modeDomain.values().contains(FiniteDomain.Value.exact("strict")));
                assertTrue(modeDomain.values().contains(FiniteDomain.Value.exact("false")),
                                () -> "Expected non-matching falsifying witness in domain: " + modeDomain.values());
        }
}
