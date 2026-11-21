package com.evolution.analysis.javaparser;

import static org.junit.jupiter.api.Assertions.*;

import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.ingestion.IngestionEvidence;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class FastArchitectureProfileTest {

    @Test
    void fastArchitecturePreservesStructureSpringAnnotationsAndBoundaryCallsWhileHonestPartial() {
        Map<String, String> files = Map.of(
                "fixture/Service.java", """
                        package fixture;
                        import java.lang.annotation.*;
                        @Retention(RetentionPolicy.RUNTIME) @interface Configuration {}
                        @Retention(RetentionPolicy.RUNTIME) @interface Bean {}
                        @Retention(RetentionPolicy.RUNTIME) @interface Service {}
                        interface OrderRepository { void save(OrderDto dto); }
                        record OrderDto(String id) {}
                        final class OrderMapper { static OrderDto toDto(String id) { return new OrderDto(id); } }
                        """,
                "fixture/OrderService.java", """
                        package fixture;
                        import java.util.*;
                        @Service
                        public class OrderService {
                            private final OrderRepository repository;
                            public OrderService(OrderRepository repository) {
                                this.repository = repository;
                            }
                            public OrderDto process(String rawId) {
                                var builder = new StringBuilder();
                                builder.append(rawId.trim().toLowerCase());
                                List<String> items = List.of("a", "b");
                                int count = items.size();
                                OrderDto dto = OrderMapper.toDto(rawId);
                                repository.save(dto);
                                return new OrderDto(rawId + count);
                            }
                        }
                        """,
                "fixture/AppConfig.java", """
                        package fixture;
                        @Configuration
                        public class AppConfig {
                            @Bean
                            public OrderService orderService(OrderRepository repo) {
                                return new OrderService(repo);
                            }
                        }
                        """);

        var fullReq = buildRequest(files, Map.of());
        var fastReq = buildRequest(files, Map.of("analysis.profile", "FAST_ARCHITECTURE"));

        var frontend = new JavaParserFrontend();
        var fullFirst = frontend.analyze(fullReq);
        var fullSecond = frontend.analyze(fullReq);
        assertEquals(fullFirst, fullSecond);
        assertEquals(IngestionEvidence.digest(fullFirst), IngestionEvidence.digest(fullSecond));
        assertEquals(FrontendResult.State.COMPLETED, fullFirst.state());

        var fastResult = frontend.analyze(fastReq);
        var fastReplay = frontend.analyze(fastReq);
        assertEquals(fastResult, fastReplay);
        assertEquals(IngestionEvidence.digest(fastResult), IngestionEvidence.digest(fastReplay));
        assertEquals(FrontendResult.State.PARTIAL, fastResult.state());

        // Honest provenance diagnostic and PARTIAL category coverage
        assertTrue(fastResult.diagnostics().stream()
                .anyMatch(d -> d.code().equals("java.fast-architecture")
                        && "FAST_ARCHITECTURE".equals(d.details().get("profile"))));
        var callsCoverage = fastResult.coverage().stream()
                .filter(c -> c.category().value().equals("java.calls"))
                .findFirst()
                .orElseThrow();
        assertEquals(CategoryCoverage.Support.PARTIAL, callsCoverage.support());

        // 100% of project declarations, type shapes, member shapes, and annotations preserved
        var fullProjectDecls = fullFirst.declarations().stream()
                .filter(d -> d.entity().origin() == EntityOrigin.PROJECT
                        && d.entity().kind() != EntityKind.LAMBDA)
                .map(d -> d.entity().canonicalName())
                .sorted()
                .toList();
        var fastProjectDecls = fastResult.declarations().stream()
                .filter(d -> d.entity().origin() == EntityOrigin.PROJECT
                        && d.entity().kind() != EntityKind.LAMBDA)
                .map(d -> d.entity().canonicalName())
                .sorted()
                .toList();
        assertEquals(fullProjectDecls, fastProjectDecls);
        assertEquals(fullFirst.typeDeclarations(), fastResult.typeDeclarations());
        assertEquals(fullFirst.memberDeclarations(), fastResult.memberDeclarations());
        assertEquals(fullFirst.annotations().size(), fastResult.annotations().size());

        // Boundary calls (OrderRepository.save, OrderMapper.toDto) and project constructor calls preserved
        var resolvedCallTargets = fastResult.occurrences().stream()
                .filter(o -> o.relationship().kind().value().equals("java.calls")
                        && o.relationship().target() instanceof RelationshipTarget.Resolved)
                .map(o -> ((RelationshipTarget.Resolved) o.relationship().target()).target())
                .toList();
        var declById = new HashMap<com.evolution.analysis.contract.identity.EntityIdentity, DeclarationRecord>();
        fastResult.declarations().forEach(d -> declById.put(d.entity().identity(), d));
        var calledCanonicalNames = resolvedCallTargets.stream()
                .map(id -> declById.get(id).entity().canonicalName())
                .toList();
        assertTrue(calledCanonicalNames.stream().anyMatch(n -> n.contains("OrderRepository") && n.contains("save")));
        assertTrue(calledCanonicalNames.stream().anyMatch(n -> n.contains("OrderMapper") && n.contains("toDto")));
        // Non-architectural local variable calls (StringBuilder.append, List.size, String.trim) skipped
        assertFalse(calledCanonicalNames.stream().anyMatch(n -> n.contains("StringBuilder") && n.contains("append")));
        assertTrue(fastResult.occurrences().size() < fullFirst.occurrences().size());
    }

    @Test
    void fullModeOptimizationsPreserveExactBitForBitParityWithNonCompactBaseline() {
        Map<String, String> files = Map.of(
                "fixture/Domain.java", """
                        package fixture;
                        import java.util.*;
                        import java.util.function.*;
                        public sealed interface Domain permits Domain.Item, Domain.Group {
                            String label();
                            record Item(String label, int weight) implements Domain {}
                            record Group(String label, List<Item> children) implements Domain {}
                        }
                        """,
                "fixture/Processor.java", """
                        package fixture;
                        import java.util.*;
                        import java.util.function.*;
                        public class Processor {
                            public static int score(Domain d) {
                                return switch (d) {
                                    case Domain.Item(var lbl, var w) when w > 0 -> lbl.length() + w;
                                    case Domain.Item item -> item.label().length();
                                    case Domain.Group grp -> grp.children().stream()
                                            .map(Domain.Item::label)
                                            .mapToInt(s -> s.trim().length())
                                            .sum();
                                };
                            }
                            public List<String> labels(List<Domain.Item> items) {
                                Function<Domain.Item, String> fn = Domain.Item::label;
                                return items.stream().map(fn).filter(Objects::nonNull).toList();
                            }
                        }
                        """);
        var req = buildRequest(files, Map.of());
        var baseline = new Extraction(req, false).run();
        var optimizedCold = new Extraction(req, true).run();
        var optimizedWarm = new Extraction(req, true).run();

        assertEquals(baseline, optimizedCold);
        assertEquals(baseline, optimizedWarm);
        assertEquals(IngestionEvidence.digest(baseline), IngestionEvidence.digest(optimizedCold));
        assertEquals(IngestionEvidence.digest(baseline), IngestionEvidence.digest(optimizedWarm));
    }

    private static FrontendRequest buildRequest(Map<String, String> texts, Map<String, String> extraOptions) {
        var base = TestInputs.request(texts, List.of());
        if (extraOptions.isEmpty()) return base;
        var merged = new TreeMap<>(base.manifest().configuration().values());
        merged.putAll(extraOptions);
        var component = new ManifestComponent(
                new VersionedIdentifier("test.fixture", "1"),
                ContentDigest.sha256Utf8("test-fixture-components-v1"));
        var manifest = AnalysisManifest.create(
                new VersionedIdentifier("analysis.manifest", "1"),
                base.manifest().snapshot(),
                base.manifest().modules(),
                base.manifest().classpath(),
                AnalysisConfiguration.create(new VersionedIdentifier("analysis.configuration", "1"), merged),
                component,
                component,
                component);
        return new FrontendRequest(
                manifest,
                base.module(),
                base.sourceSet(),
                base.plan(),
                base.sources(),
                base.platform(),
                base.dependencies(),
                base.reactorSources());
    }
}
