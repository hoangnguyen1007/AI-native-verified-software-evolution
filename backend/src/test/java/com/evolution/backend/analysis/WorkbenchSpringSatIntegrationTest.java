package com.evolution.backend.analysis;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.query.ArchitectureQuery;
import com.evolution.analysis.query.ArchitectureQueryService;
import com.evolution.backend.query.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WorkbenchSpringSatIntegrationTest {

        @TempDir
        Path directory;

        @Test
        void autoMultiConfigSatRunsEndToEndWithTruthRegionsWitnessesAndExplicitGaps() throws Exception {
                Path source = Files.createDirectory(directory.resolve("spring-sat-app"));
                Path cache = Files.createDirectory(directory.resolve("cache"));

                Files.writeString(source.resolve("pom.xml"), """
                                <project>
                                  <modelVersion>4.0.0</modelVersion>
                                  <groupId>demo</groupId>
                                  <artifactId>sat-app</artifactId>
                                  <version>1.0.0</version>
                                  <properties>
                                    <maven.compiler.release>21</maven.compiler.release>
                                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                                  </properties>
                                </project>
                                """);

                Files.createDirectories(source.resolve("src/main/java/com/example/app"));
                Files.createDirectories(source.resolve("src/main/resources"));

                Files.writeString(source.resolve("src/main/resources/application.yml"), """
                                spring:
                                  profiles:
                                    active: dev
                                app:
                                  cache:
                                    enabled: false
                                """);
                Files.writeString(source.resolve("src/main/resources/application-dev.yml"), """
                                app:
                                  cache:
                                    enabled: true
                                """);

                Files.writeString(source.resolve("src/main/java/com/example/app/AppMain.java"), """
                                package com.example.app;
                                import org.springframework.boot.autoconfigure.SpringBootApplication;

                                @SpringBootApplication
                                public class AppMain {
                                }
                                """);
                Files.writeString(source.resolve("src/main/java/com/example/app/Store.java"), """
                                package com.example.app;
                                public interface Store {}
                                """);
                Files.writeString(source.resolve("src/main/java/com/example/app/DevStore.java"), """
                                package com.example.app;
                                import org.springframework.context.annotation.Profile;
                                import org.springframework.stereotype.Service;

                                @Service
                                @Profile("dev")
                                public class DevStore implements Store {}
                                """);
                Files.writeString(source.resolve("src/main/java/com/example/app/ProdStore.java"), """
                                package com.example.app;
                                import org.springframework.context.annotation.Profile;
                                import org.springframework.stereotype.Service;

                                @Service
                                @Profile("prod")
                                public class ProdStore implements Store {}
                                """);
                Files.writeString(source.resolve("src/main/java/com/example/app/CacheService.java"), """
                                package com.example.app;
                                import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
                                import org.springframework.stereotype.Service;

                                @Service
                                @ConditionalOnProperty(prefix = "app.cache", name = "enabled", havingValue = "true")
                                public class CacheService {
                                    private final Store store;
                                    public CacheService(Store store) {
                                        this.store = store;
                                    }
                                }
                                """);

                var runner = new LocalAnalysisRunner(new LocalAnalysisRunner.Input(
                                source,
                                RepositoryIdentity.fromCanonicalCoordinate("urn:test:workbench-spring-sat"),
                                Path.of(System.getProperty("java.home")),
                                21,
                                cache,
                                Optional.empty(),
                                true,
                                false,
                                List.of(),
                                Optional.empty(),
                                LocalAnalysisRunner.SpringInput.autoMultiConfig()));

                var store = new InMemoryGraphStore();
                var reports = new PublishedReports();
                try (var jobs = new AnalysisJobs(
                                List.of(runner.registration("sat-app")),
                                store,
                                reports,
                                new AnalysisJobs.Limits(1, 1, 4, 90_000, 1000))) {
                        UUID jobId = UUID.fromString(jobs.submit("sat-app", "sat-run").job().id());
                        AnalysisJobs.View view = jobs.get(jobId);
                        long deadline = System.currentTimeMillis() + 90_000L;
                        while (view.state() == AnalysisJobs.State.QUEUED
                                        || view.state() == AnalysisJobs.State.RUNNING) {
                                if (System.currentTimeMillis() > deadline) {
                                        fail("Timed out waiting for SAT analysis job: " + view);
                                }
                                Thread.sleep(50);
                                view = jobs.get(jobId);
                        }
                        final var terminalView = view;
                        assertTrue(Set.of(AnalysisJobs.State.SUCCEEDED, AnalysisJobs.State.PARTIAL)
                                        .contains(terminalView.state()),
                                        () -> "Unexpected job state: " + terminalView);
                        ContentDigest graphId = view.result().orElseThrow().graph().orElseThrow();
                        var graph = store.load(graphId).orElseThrow();

                        var allGaps = new ArrayList<com.evolution.analysis.evidence.CapabilityGapRecord>();
                        Optional<String> gapCursor = Optional.empty();
                        do {
                                var gapPage = jobs.gaps(jobId, 50, gapCursor);
                                allGaps.addAll(gapPage.items());
                                gapCursor = gapPage.continuation();
                        } while (gapCursor.isPresent());
                        var gapReasons = allGaps.stream()
                                        .map(com.evolution.analysis.evidence.CapabilityGapRecord::reasonCode).toList();

                        var regionNodes = graph.nodes().stream()
                                        .filter(n -> "TRUTH_REGION".equals(n.kind()))
                                        .toList();
                        assertFalse(regionNodes.isEmpty(),
                                        () -> "Expected TRUTH_REGION nodes in canonical graph, node kinds="
                                                        + graph.nodes().stream().map(KnowledgeGraph.Node::kind)
                                                                        .distinct().toList()
                                                        + ", evidenceFamilies="
                                                        + graph.evidence().stream().map(GraphEvidence::family)
                                                                        .distinct().toList()
                                                        + ", gaps=" + gapReasons);
                        assertTrue(graph.evidence().stream().anyMatch(e -> "spring.symbolic-region".equals(e.family())),
                                        "Expected spring.symbolic-region evidence from SatConfigurationReasoner");

                        String ctx = regionNodes.getFirst().context();
                        var queries = new ArchitectureQueryService(graph);
                        var req = new ArchitectureQuery.Request(
                                        graphId, ctx, ArchitectureQuery.Scope.SPRING_COMPONENT,
                                        ArchitectureQuery.Projection.BOUNDED_UNION,
                                        ArchitectureQuery.Direction.OUTGOING,
                                        Set.of("graph.depends-on"), ArchitectureQuery.Population.all(), false,
                                        Optional.empty(), ArchitectureQuery.Limits.defaults());
                        var page = new ArchitectureQuery.Page(50, Optional.empty());

                        var springResult = queries.spring(req,
                                        Set.of("BEAN_DEFINITION_CANDIDATE", "REGISTRATION_EVENT"), page);
                        assertFalse(springResult.items().isEmpty(), "Expected /spring query items");
                        var configItems = new ArrayList<ArchitectureQuery.EntityView>();
                        Optional<String> configCursor = Optional.empty();
                        do {
                                var configPage = queries.configurations(req,
                                                new ArchitectureQuery.Page(50, configCursor));
                                configItems.addAll(configPage.items());
                                configCursor = configPage.continuation();
                        } while (configCursor.isPresent());
                        assertFalse(configItems.isEmpty(), "Expected /configurations query items");
                        assertTrue(configItems.stream().anyMatch(item -> "TRUTH_REGION".equals(item.kind())),
                                        () -> "Expected TRUTH_REGION in /configurations query items, got kinds="
                                                        + configItems.stream().map(ArchitectureQuery.EntityView::kind)
                                                                        .distinct().toList());
                        var regionPayloads = graph.evidence().stream()
                                        .filter(e -> "spring.symbolic-region".equals(e.family()))
                                        .map(GraphEvidence::canonicalJson)
                                        .toList();
                        assertFalse(regionPayloads.isEmpty(), "Expected spring.symbolic-region payloads");
                        assertTrue(regionPayloads.stream()
                                        .anyMatch(json -> json.contains("\"MAY\"") || json.contains("\"MUST\"")
                                                        || json.contains("\"NEVER\"")),
                                        () -> "Expected SatConfigurationReasoner to evaluate profiles/properties into MAY/MUST/NEVER regions, got "
                                                        + regionPayloads
                                                        + ", springEvidence=" + graph.evidence().stream()
                                                                        .filter(e -> e.family().startsWith("spring."))
                                                                        .map(e -> e.family() + ":" + e.canonicalJson())
                                                                        .toList()
                                                        + ", gaps=" + gapReasons);
                        assertTrue(regionPayloads.stream().anyMatch(json -> json.contains("\"MAY\"")),
                                        () -> "Expected at least one profile/property-conditional MAY truth region, got "
                                                        + regionPayloads);
                        var realResult = queries.realizations(req, page);
                        assertFalse(realResult.items().isEmpty(), "Expected /realizations query items");

                        assertTrue(gapReasons.contains("EXTERNAL_DEPLOYMENT_OVERRIDES_UNOBSERVED"),
                                        () -> "Expected explicit EXTERNAL_DEPLOYMENT_OVERRIDES_UNOBSERVED gap, got "
                                                        + gapReasons);
                }
        }
}
