package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused regression tests for honest Lombok, Lambda, and MethodReference semantic gap reductions.
 */
class LombokAndLambdaGapTest {

    private static BinaryInput lombokJar() throws Exception {
        Path jar = Path.of(lombok.Data.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return new BinaryInput(
                new ClasspathEntry(ClasspathEntryKind.DEPENDENCY, "org.projectlombok:lombok:1.18.46@jar", ContentDigest.sha256(Files.readAllBytes(jar))),
                jar);
    }

    private static FrontendResult analyzeWithLombok(Map<String, String> sources) throws Exception {
        return new JavaParserFrontend().analyze(TestInputs.request(sources, List.of(lombokJar())));
    }

    private static FrontendResult analyzeWithLombokConfig(
            Map<String, String> javaSources,
            Map<String, String> configFiles,
            boolean supplyCapturedText) throws Exception {
        var base = TestInputs.request(javaSources, List.of(lombokJar()));
        var allSnapshotFiles = new ArrayList<SnapshotFile>(base.manifest().snapshot().files());
        var options = new TreeMap<>(base.manifest().configuration().values());
        for (var e : configFiles.entrySet()) {
            ContentDigest digest = ContentDigest.sha256Utf8(e.getValue());
            allSnapshotFiles.add(new SnapshotFile(e.getKey(), digest));
            if (supplyCapturedText) {
                options.put(LombokConfig.CONFIG_OPTION_PREFIX + e.getKey(),
                        "base64:" + Base64.getEncoder().encodeToString(e.getValue().getBytes(StandardCharsets.UTF_8)));
            }
        }
        var snapshot = RepositorySnapshot.create(
                base.manifest().snapshot().repository(), Optional.empty(), false,
                allSnapshotFiles, base.manifest().snapshot().documents());
        var old = base.manifest();
        var manifest = AnalysisManifest.create(
                old.manifestVersion(), snapshot, old.modules(), old.classpath(),
                AnalysisConfiguration.create(old.configuration().schema(), options),
                old.analyzer(), old.ruleSet(), old.graphSchema());
        var req = new FrontendRequest(manifest, base.module(), base.sourceSet(), base.plan(),
                base.sources(), base.platform(), base.dependencies(), base.reactorSources(), base.correspondence());
        return new JavaParserFrontend().analyze(req);
    }

    @Test
    void passiveLombokConfigHonorsSafeKeysAndFlagsUnsupportedBehavioralKeysHonestly() throws Exception {
        String safeConfig = """
                # benign + honored keys
                config.stopBubbling = true
                lombok.addLombokGeneratedAnnotation = true
                lombok.addNullAnnotations = spring
                lombok.accessors.chain = true
                lombok.accessors.prefix += m_
                lombok.getter.noIsPrefix = true
                lombok.log.fieldName = LOGGER
                """;
        String source = """
                package app;
                import lombok.Data;
                import lombok.extern.slf4j.Slf4j;
                @Slf4j @Data class Account {
                    String m_name;
                    boolean m_active;
                    String run() {
                        LOGGER.info("active={}", getActive());
                        return setName("alice").getName();
                    }
                }
                """;
        var ok = analyzeWithLombokConfig(Map.of("app/Account.java", source), Map.of("lombok.config", safeConfig), true);
        assertTrue(ok.diagnostics().stream().noneMatch(d -> d.code().equals("lombok.configuration-required")),
                () -> "Unexpected configuration gap: " + ok.diagnostics());
        var calls = ok.occurrences().stream().filter(o -> o.relationship().kind().value().equals("java.calls")).toList();
        assertEquals(4, calls.size());
        assertTrue(calls.stream().allMatch(o -> o.status() == SemanticStatus.RESOLVED), () -> "Calls: " + calls);

        // Unsupported behavioral key affecting @Builder MUST still emit lombok.configuration-required
        String unsupportedConfig = "lombok.builder.className = Custom*Builder\n";
        String builderSrc = "package app; @lombok.Builder class Item { String code; }";
        var flagged = analyzeWithLombokConfig(Map.of("app/Item.java", builderSrc), Map.of("lombok.config", unsupportedConfig), true);
        assertTrue(flagged.diagnostics().stream().anyMatch(d -> d.code().equals("lombok.configuration-required")),
                "Unsupported behavioral key must emit lombok.configuration-required");

        // Snapshot lombok.config WITHOUT captured verified text MUST still emit lombok.configuration-required
        var opaque = analyzeWithLombokConfig(Map.of("app/Account.java", source), Map.of("lombok.config", safeConfig), false);
        assertTrue(opaque.diagnostics().stream().anyMatch(d -> d.code().equals("lombok.configuration-required")),
                "Opaque uncaptured lombok.config must emit lombok.configuration-required");
    }

    @Test
    void builderDefaultSingularFieldDefaultsAccessorsPrefixSuperBuilderGenericToBuilderAndStandardException() throws Exception {
        String source = """
                package app;
                import java.util.*;
                import lombok.*;
                import lombok.experimental.*;

                @interface Default {}

                @Data @FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
                class ImmutableRecord {
                    @Default String id;
                    @NonFinal String tag;
                    static String use() {
                        ImmutableRecord r = new ImmutableRecord("1");
                        r.setTag("v1");
                        return r.getId() + r.getTag();
                    }
                }

                @Getter @Setter @Accessors(fluent = true, chain = true, prefix = {"m_", "f"})
                class Prefixed {
                    String m_title;
                    boolean fReady;
                    static boolean use(Prefixed p) {
                        return p.title("hi").ready(true).ready();
                    }
                }

                @Builder(toBuilder = true) @Getter
                class Order {
                    @Builder.Default final String status = "NEW";
                    @Singular List<String> tags;
                    @Singular Map<String, Integer> scores;
                    @Singular("datum") List<String> metadata;
                    static String use(List<String> extra, Map<String, Integer> more) {
                        Order o = Order.builder()
                                .status("OPEN")
                                .tag("a").tags(extra).clearTags()
                                .score("k", 1).scores(more).clearScores()
                                .datum("d1")
                                .build();
                        Order updated = o.toBuilder().status("DONE").build();
                        return updated.getStatus();
                    }
                }

                @SuperBuilder @Getter
                class BaseEntity { String baseId; }

                @SuperBuilder @Getter
                class ChildEntity extends BaseEntity {
                    int version;
                    static String use() {
                        ChildEntity c = ChildEntity.builder().baseId("b1").version(2).build();
                        return c.getBaseId();
                    }
                }

                @Builder @Getter
                class GenericBox<T> {
                    T value;
                    static GenericBox<String> use() {
                        return GenericBox.<String>builder().value("ok").build();
                    }
                }

                class CallableBuilders {
                    final String a;
                    @Builder CallableBuilders(String a) { this.a = a; }
                    @Builder(builderMethodName = "pairBuilder", builderClassName = "PairBuilder")
                    static String createPair(String left, int right) { return left + right; }
                    static String use() {
                        CallableBuilders cb = CallableBuilders.builder().a("x").build();
                        return CallableBuilders.pairBuilder().left(cb.a).right(1).build();
                    }
                }

                @StandardException
                class AppException extends RuntimeException {
                    static AppException[] use(IllegalStateException cause) {
                        return new AppException[] {
                                new AppException(),
                                new AppException("boom"),
                                new AppException(cause),
                                new AppException("boom", cause)
                        };
                    }
                }
                """;
        var result = analyzeWithLombok(Map.of("app/Showcase.java", source));
        assertTrue(result.diagnostics().stream().noneMatch(d -> d.code().startsWith("lombok.")),
                () -> "Unexpected Lombok gaps: " + result.diagnostics());
        var calls = result.occurrences().stream().filter(o -> o.relationship().kind().value().equals("java.calls")).toList();
        assertTrue(calls.size() >= 25, () -> "Expected >=25 calls, got " + calls.size());
        assertTrue(calls.stream().allMatch(o -> o.status() == SemanticStatus.RESOLVED),
                () -> "Unresolved calls: " + calls.stream().filter(o -> o.status() != SemanticStatus.RESOLVED).toList());
        var ctors = result.occurrences().stream().filter(o -> o.relationship().kind().value().equals("java.constructor-calls")).toList();
        assertTrue(ctors.stream().allMatch(o -> o.status() == SemanticStatus.RESOLVED),
                () -> "Unresolved constructors: " + ctors.stream().filter(o -> o.status() != SemanticStatus.RESOLVED).toList());

        // Irregular unsingularizable @Singular without explicit value MUST still emit lombok.member-customization
        var badSingular = analyzeWithLombok(Map.of("app/Bad.java",
                "package app; import java.util.List; @lombok.Builder class Bad { @lombok.Singular List<String> metadata; }"));
        assertTrue(badSingular.diagnostics().stream().anyMatch(d -> d.code().equals("lombok.member-customization")),
                "Un-singularizable @Singular without explicit name must emit lombok.member-customization");
    }

    @Test
    void standardNullnessAnnotationsAcceptedWhileUnknownNullnessStaysExplicitGap() throws Exception {
        String source = """
                package app;
                import lombok.RequiredArgsConstructor;
                @RequiredArgsConstructor
                class StandardNullness {
                    @org.jetbrains.annotations.NotNull String jetbrains;
                    @org.springframework.lang.NonNull String spring;
                    @jakarta.annotation.Nonnull String jakarta;
                    @javax.annotation.Nonnull String javax;
                    int optional;
                }
                """;
        var rec = new JavaParserFrontend().analyze(LombokIngestionTest.recovery(
                TestInputs.request(Map.of("app/StandardNullness.java", source), List.of())));
        assertTrue(rec.diagnostics().stream().noneMatch(d -> d.code().equals("lombok.nullness-annotation-unverified")),
                () -> "Standard nullness annotations should not emit unverified gap: " + rec.diagnostics());
        assertEquals(4, rec.constructorCandidates().size());

        // Unknown custom @NotNull MUST still emit lombok.nullness-annotation-unverified
        String unknownSrc = """
                package app;
                @interface NotNull {}
                @lombok.RequiredArgsConstructor
                class CustomNullness { @NotNull String s; }
                """;
        var unknown = analyzeWithLombok(Map.of("app/CustomNullness.java", unknownSrc));
        assertTrue(unknown.diagnostics().stream().anyMatch(d -> d.code().equals("lombok.nullness-annotation-unverified")),
                "Unknown custom nullness annotation must emit lombok.nullness-annotation-unverified");
    }

    @Test
    void staticUnqualifiedOverloadedConstructorAndReturnLambdasAndGenericMethodReferencesResolve() {
        String source = """
                package app;
                import java.util.*;
                import java.util.function.*;
                import static java.util.Collections.sort;

                record Pet(String name, int age) {}

                class Worker {
                    Worker(Function<Pet, String> fn) {}
                    void apply(Function<Pet, String> fn) {}
                    void overloaded(String tag, Consumer<Pet> consumer) {}
                    void overloaded(int id, Consumer<String> consumer) {}
                    void arity(Function<Pet, String> one) {}
                    void arity(BiFunction<Pet, String, Integer> two) {}
                }

                class Gen {
                    static <T> T make() { return null; }
                    static <T extends Number> T makeNum() { return null; }
                    static int measure(List<String> list) { return list.size(); }
                }

                class Use {
                    Function<Pet, String> fieldFn = p -> p.name();

                    Function<Pet, Integer> retLambda() {
                        return p -> p.name().length();
                    }

                    void run(List<Pet> pets, Worker w, int choice) {
                        Collections.sort(pets, (a, b) -> a.name().compareTo(b.name()));
                        sort(pets, (a, b) -> Integer.compare(a.age(), b.age()));
                        new Worker(p -> p.name());
                        w.apply(p -> p.name());
                        w.overloaded("t", p -> p.name());
                        w.overloaded(1, s -> s.length());
                        w.arity(p -> p.name());
                        w.arity((p, s) -> p.name().length() + s.length());

                        Function<Pet, String> assigned;
                        assigned = p -> p.name();
                        Object casted = (Function<Pet, String>) p -> p.name();

                        // Generic method-ref return inference + new target contexts
                        Supplier<List<String>> empty = Collections::emptyList;
                        Supplier<String> made = Gen::make;
                        new Worker(Pet::name);
                        Function<Pet, String>[] arr = new Function[] { Pet::name };
                        Function<Pet, String> switched = switch (choice) {
                            case 1 -> Pet::name;
                            default -> { yield Pet::name; }
                        };
                    }
                }
                """;
        var result = new JavaParserFrontend().analyze(TestInputs.request(Map.of("app/Use.java", source), List.of()));
        var calls = result.observations().stream().filter(o -> o.category().value().equals("java.calls")).toList();
        assertTrue(calls.size() >= 15);
        assertTrue(calls.stream().allMatch(o -> o.attribution() == SemanticStatus.RESOLVED),
                () -> "Unresolved lambda calls: " + calls.stream().filter(o -> o.attribution() != SemanticStatus.RESOLVED).toList());
        var refs = result.observations().stream().filter(o -> o.category().value().equals("java.method-references")).toList();
        assertEquals(6, refs.size());
        assertTrue(refs.stream().allMatch(o -> o.attribution() == SemanticStatus.RESOLVED),
                () -> "Unresolved method refs: " + refs.stream().filter(o -> o.attribution() != SemanticStatus.RESOLVED).toList());

        // Incompatible generic bound on method-ref return MUST still be withheld!
        String badRef = """
                package app;
                import java.util.function.Supplier;
                class Gen { static <T extends Number> T makeNum() { return null; } }
                class Bad { Supplier<String> s = Gen::makeNum; }
                """;
        var badResult = new JavaParserFrontend().analyze(TestInputs.request(Map.of("app/Bad.java", badRef), List.of()));
        var badRefs = badResult.observations().stream().filter(o -> o.category().value().equals("java.method-references")).toList();
        assertEquals(1, badRefs.size());
        assertNotEquals(SemanticStatus.RESOLVED, badRefs.getFirst().attribution(),
                "Incompatible generic bound must not resolve");
    }
}
