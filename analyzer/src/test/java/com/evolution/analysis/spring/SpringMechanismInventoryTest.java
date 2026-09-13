package com.evolution.analysis.spring;

import static org.junit.jupiter.api.Assertions.*;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.frontend.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import org.junit.jupiter.api.Test;

class SpringMechanismInventoryTest {
    private static final RepositoryIdentity REPOSITORY =
            RepositoryIdentity.fromCanonicalCoordinate("https://example.test/m4a1-fixtures.git");
    private static final ModuleDescriptor MODULE = ModuleDescriptor.create(REPOSITORY, ".", "fixture-root");
    private static final AnalysisIdentity ANALYSIS =
            AnalysisIdentity.fromCanonicalManifestInputs("m4a1-fixture-analysis");

    @Test
    void acceptedCatalogHasExactlyTheClosedTwentyNineFamilies() {
        assertEquals(new VersionedIdentifier("spring-mechanisms", "v2"), SpringMechanismCatalog.CATALOG);
        assertEquals("r0-candidate-1", SpringMechanismCatalog.REVISION);
        assertEquals(List.of(
                "spring.bean.factory-method",
                "spring.bean.stereotype.composed",
                "spring.bean.stereotype.direct",
                "spring.condition.bean-state",
                "spring.condition.build-context",
                "spring.condition.custom-expression",
                "spring.condition.profile",
                "spring.condition.property",
                "spring.disambiguation.priority",
                "spring.disambiguation.qualifier",
                "spring.discovery.component-scan",
                "spring.entrypoint.framework",
                "spring.expression.value",
                "spring.injection.aggregate",
                "spring.injection.bean-parameter",
                "spring.injection.constructor.explicit",
                "spring.injection.constructor.generated",
                "spring.injection.constructor.implicit",
                "spring.injection.field",
                "spring.injection.method",
                "spring.injection.resource",
                "spring.lookup.container",
                "spring.mechanism.unclassified",
                "spring.registration.auto-configuration",
                "spring.registration.factory",
                "spring.registration.programmatic",
                "spring.registration.spring-data",
                "spring.registration.xml",
                "spring.runtime.proxy-aop"),
                SpringMechanismCatalog.entries().stream().map(SpringMechanismCatalog.Entry::id).toList());
        assertEquals(SpringMechanismCatalog.Target.CONDITIONAL,
                SpringMechanismCatalog.require("spring.registration.xml").target());
        assertEquals(SpringMechanismCatalog.Target.UNSUPPORTED,
                SpringMechanismCatalog.require("spring.mechanism.unclassified").target());
        assertEquals("UNCLASSIFIED_MECHANISM",
                SpringMechanismCatalog.require("spring.mechanism.unclassified").gap().reasonCode());
        assertEquals(Set.of("UNCLASSIFIED_MECHANISM", "ANNOTATION_SEMANTICS_NOT_ADJUDICATED",
                        "VERSION_FRAGMENT_NOT_VALIDATED", "ANNOTATION_GRAPH_INCOMPLETE",
                        "CONSTRUCTOR_SET_INCOMPLETE", "GENERATED_MEMBER_NOT_ACQUIRED",
                        "PARAMETER_EVIDENCE_INCOMPLETE", "AGGREGATE_OR_PROVIDER_UNMODELED",
                        "FACTORY_PRODUCT_TYPE_UNKNOWN", "DYNAMIC_REGISTRY_MUTATION",
                        "DYNAMIC_LOOKUP_TARGET", "REPOSITORY_REGISTRATION_UNPROVED",
                        "ENTRYPOINT_OR_LIFECYCLE_UNMODELED"),
                Arrays.stream(SpringMechanismInventory.ProblemReason.values())
                        .map(Enum::name).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void allThreeAcceptedFrameworkTuplesMatchTheLockedArtifacts() throws Exception {
        for (List<String> tuple : List.of(
                List.of("5.3.31", "2.7.18"),
                List.of("6.1.14", "3.3.5"),
                List.of("6.2.0", "3.4.0"))) {
            SpringFrameworkEvidence evidence = lockedFramework(tuple.get(0), tuple.get(1));
            Fixture fixture = fixtureFrontend("ProfileFixture.java", List.of(
                    annotation("@Profile(\"default\")",
                            "org.springframework.context.annotation.Profile", EntityKind.METHOD)), evidence);

            SpringMechanismInventory inventory = SpringMechanismScanner.scan(new SpringMechanismScanRequest(
                    fixture.frontend(), evidence, List.of(), List.of()));

            assertTrue(inventory.problems().isEmpty(), () -> "tuple failed: " + tuple);
        }
    }

    @Test
    void profileFixtureProducesOneRawRowAndOnePrimaryObligationPerAnnotation() throws Exception {
        Fixture fixture = fixtureFrontend("ProfileFixture.java", List.of(
                annotation("@Configuration(proxyBeanMethods=false)", "org.springframework.context.annotation.Configuration", EntityKind.TYPE),
                annotation("@Bean", "org.springframework.context.annotation.Bean", EntityKind.METHOD),
                annotation("@Profile(\"dev & !prod\")", "org.springframework.context.annotation.Profile", EntityKind.METHOD),
                annotation("@Bean", "org.springframework.context.annotation.Bean", EntityKind.METHOD),
                annotation("@Profile({\"dev\", \"prod\"})", "org.springframework.context.annotation.Profile", EntityKind.METHOD),
                annotation("@Bean", "org.springframework.context.annotation.Bean", EntityKind.METHOD),
                annotation("@Profile(\"default\")", "org.springframework.context.annotation.Profile", EntityKind.METHOD)));

        SpringMechanismInventory first = SpringMechanismScanner.scan(new SpringMechanismScanRequest(
                fixture.frontend(), validatedFramework531(), List.of(), List.of()));
        SpringMechanismInventory replay = SpringMechanismScanner.scan(new SpringMechanismScanRequest(
                fixture.frontend(), validatedFramework531(), List.of(), List.of()));

        assertEquals(first.identity(), replay.identity());
        assertEquals(7, first.coverage().rawObservationCount());
        assertEquals(7, first.coverage().semanticObligationCount());
        assertEquals(0, first.coverage().unclassifiedObservationCount());
        assertEquals(0, first.coverage().markerObservationCount());
        assertTrue(first.problems().isEmpty());
        assertEquals(Map.of(
                        "spring.bean.factory-method", 4L,
                        "spring.condition.profile", 3L),
                first.obligations().stream().collect(java.util.stream.Collectors.groupingBy(
                        SpringMechanismInventory.SemanticObligation::primaryMechanism,
                        TreeMap::new, java.util.stream.Collectors.counting())));
        assertTrue(first.rawObservations().stream().allMatch(raw -> raw.span().isPresent()
                && raw.kind() == SpringMechanismInventory.RawKind.ANNOTATION_USE));
    }

    @Test
    void legacyXmlHasExactClosedElementAndAttributeInventory() throws Exception {
        SourceInput xml = sourceInput("legacy.xml", SourceClassification.OTHER);
        SpringMechanismInventory inventory = SpringMechanismScanner.scan(new SpringMechanismScanRequest(
                emptyFrontend(), validatedFramework531(), List.of(xml), List.of()));

        assertEquals(17, inventory.coverage().rawObservationCount());
        assertEquals(17, inventory.coverage().semanticObligationCount());
        assertEquals(1, inventory.resourceEvidence().size());
        assertEquals(xml.document().contentDigest(),
                inventory.resourceEvidence().getFirst().document().contentDigest());
        assertEquals(0, inventory.coverage().unclassifiedObservationCount());
        assertTrue(inventory.problems().isEmpty());
        assertTrue(inventory.rawObservations().stream().allMatch(raw -> raw.span().isPresent()));
        assertTrue(inventory.obligations().stream().allMatch(
                obligation -> obligation.primaryMechanism().equals("spring.registration.xml")));
        assertEquals(7, inventory.rawObservations().stream()
                .filter(raw -> raw.kind() == SpringMechanismInventory.RawKind.XML_ELEMENT).count());
        assertEquals(9, inventory.rawObservations().stream()
                .filter(raw -> raw.kind() == SpringMechanismInventory.RawKind.XML_ATTRIBUTE).count());
    }

    @Test
    void unclassifiedAnnotationAndUnvalidatedVersionBecomeTypedCapabilityGaps() throws Exception {
        Fixture unknown = fixtureFrontend("ProfileFixture.java", List.of(
                annotation("@Configuration(proxyBeanMethods=false)", "com.acme.UnknownArchitectureExtension", EntityKind.TYPE)));
        SpringMechanismInventory unclassified = SpringMechanismScanner.scan(new SpringMechanismScanRequest(
                unknown.frontend(), validatedFramework531(), List.of(), List.of()));
        SpringFrameworkEvidence unvalidatedVersion = new SpringFrameworkEvidence(true,
                Optional.of(ContentDigest.sha256Utf8("unvalidated-classpath-manifest")), List.of(
                artifact("org.springframework:spring-core:6.0.0", "unvalidated-core"),
                artifact("org.springframework:spring-context:6.0.0", "unvalidated-context")));
        Fixture profile = fixtureFrontend("ProfileFixture.java", List.of(
                annotation("@Profile(\"default\")", "org.springframework.context.annotation.Profile", EntityKind.METHOD)),
                unvalidatedVersion);
        SpringMechanismInventory versionGap = SpringMechanismScanner.scan(new SpringMechanismScanRequest(
                profile.frontend(), unvalidatedVersion, List.of(), List.of()));

        assertEquals("spring.mechanism.unclassified",
                unclassified.obligations().getFirst().primaryMechanism());
        assertEquals(1, unclassified.coverage().unclassifiedObservationCount());
        assertEquals(Set.of("ANNOTATION_GRAPH_INCOMPLETE"), reasons(unclassified));
        assertEquals(1, unclassified.annotationDeclarations().size());
        assertEquals(SpringMechanismInventory.EvidenceState.UNRESOLVED,
                unclassified.annotationDeclarations().getFirst().evidenceState());
        assertTrue(unclassified.annotationDeclarations().getFirst().rawDeclarationObservationIdentity().isEmpty());
        assertFalse(unclassified.annotationDeclarations().getFirst().graphComplete());
        assertEquals(Set.of("VERSION_FRAGMENT_NOT_VALIDATED"), reasons(versionGap));

        RepositorySnapshot snapshot = RepositorySnapshot.create(REPOSITORY, Optional.empty(), false,
                List.of(SnapshotFile.from(unknown.document())), List.of(unknown.document()));
        EvidenceContext context = new EvidenceContext(snapshot.identity(), Optional.of(ANALYSIS));
        EvidenceAcquisitionLedger ledger = CapabilityGapNormalizer.normalize(context,
                EvidenceNormalizationInput.builder().springInventories(List.of(unclassified)).build());
        EvidenceAcquisitionLedger replay = CapabilityGapNormalizer.normalize(context,
                EvidenceNormalizationInput.builder().springInventories(List.of(unclassified)).build());

        assertEquals(new VersionedIdentifier("evidence.gap-normalizer", "m4a.2"), ledger.normalizer());
        assertEquals(ledger.identity(), replay.identity());
        assertEquals(1, ledger.gaps().size());
        CapabilityGapRecord gap = ledger.gaps().getFirst();
        assertEquals(new VersionedIdentifier("evidence.spring-mechanism-gaps", "m4a.2-v1"), gap.catalog());
        assertEquals("ANNOTATION_GRAPH_INCOMPLETE", gap.reasonCode());
        assertEquals("spring.mechanism.unclassified", gap.mechanismCategory());
        assertFalse(gap.observationReferences().isEmpty());
        assertFalse(gap.sourceSpans().isEmpty());
        assertEquals(EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT,
                gap.evidenceRequirements().getFirst().kind());
        assertThrows(IllegalArgumentException.class, () -> CapabilityGapNormalizer.normalize(
                new EvidenceContext(snapshot.identity(), Optional.empty()),
                EvidenceNormalizationInput.builder().springInventories(List.of(unclassified)).build()));

        RepositorySnapshot versionSnapshot = RepositorySnapshot.create(REPOSITORY, Optional.empty(), false,
                List.of(SnapshotFile.from(profile.document())), List.of(profile.document()));
        EvidenceAcquisitionLedger versionLedger = CapabilityGapNormalizer.normalize(
                new EvidenceContext(versionSnapshot.identity(), Optional.of(ANALYSIS)),
                EvidenceNormalizationInput.builder().springInventories(List.of(versionGap)).build());
        assertEquals(1, versionLedger.gaps().size());
        assertEquals("VERSION_FRAGMENT_NOT_VALIDATED", versionLedger.gaps().getFirst().reasonCode());
        assertEquals(EvidenceRequirement.Kind.EXACT_CLASSPATH,
                versionLedger.gaps().getFirst().evidenceRequirements().getFirst().kind());
    }

    @Test
    void annotationRoleUsesResolvedTypeAndOwnerKindRatherThanSimpleNameGuessing() throws Exception {
        Fixture fixture = fixtureFrontend("DiFixture.java", List.of(
                annotation("@Configuration(proxyBeanMethods=false)", "org.springframework.context.annotation.Configuration", EntityKind.TYPE),
                annotation("@javax.annotation.Priority(1)", "javax.annotation.Priority", EntityKind.TYPE),
                annotation("@jakarta.annotation.Priority(1)", "jakarta.annotation.Priority", EntityKind.TYPE),
                annotation("@Autowired", "org.springframework.beans.factory.annotation.Autowired", EntityKind.FIELD),
                annotation("@Bean", "org.springframework.context.annotation.Bean", EntityKind.METHOD),
                annotation("@Bean", "org.springframework.context.annotation.Bean", EntityKind.METHOD),
                annotation("@Bean", "org.springframework.context.annotation.Bean", EntityKind.METHOD)));

        SpringMechanismInventory inventory = SpringMechanismScanner.scan(new SpringMechanismScanRequest(
                fixture.frontend(), validatedFramework531(), List.of(), List.of()));

        assertEquals(1, inventory.obligations().stream()
                .filter(value -> value.primaryMechanism().equals("spring.injection.field")).count());
        assertEquals(2, inventory.obligations().stream()
                .filter(value -> value.primaryMechanism().equals("spring.disambiguation.priority")).count());
        assertFalse(inventory.obligations().stream()
                .anyMatch(value -> value.primaryMechanism().equals("spring.injection.constructor.explicit")
                        || value.primaryMechanism().equals("spring.injection.method")));
    }

    @Test
    void projectDeclarationUsingSpringFqnCannotCrossTheExactArtifactBoundary() throws Exception {
        Fixture fixture = fixtureFrontend("ProfileFixture.java", List.of(
                projectAnnotation("@Profile(\"default\")",
                        "org.springframework.context.annotation.Profile", EntityKind.METHOD)));

        SpringMechanismInventory inventory = SpringMechanismScanner.scan(new SpringMechanismScanRequest(
                fixture.frontend(), validatedFramework531(), List.of(), List.of()));

        assertEquals(1, inventory.coverage().unclassifiedObservationCount());
        assertEquals(Set.of("ANNOTATION_SEMANTICS_NOT_ADJUDICATED"), reasons(inventory));
    }

    @Test
    void completeClasspathWithoutManifestIdentityCannotCertifyTheVersionFragment() throws Exception {
        SpringFrameworkEvidence validated = validatedFramework531();
        Fixture fixture = fixtureFrontend("ProfileFixture.java", List.of(
                annotation("@Profile(\"default\")",
                        "org.springframework.context.annotation.Profile", EntityKind.METHOD)), validated);
        SpringFrameworkEvidence identityMissing = new SpringFrameworkEvidence(
                true, Optional.empty(), validated.artifacts());

        SpringMechanismInventory inventory = SpringMechanismScanner.scan(new SpringMechanismScanRequest(
                fixture.frontend(), identityMissing, List.of(), List.of()));

        assertEquals(Set.of("VERSION_FRAGMENT_NOT_VALIDATED"), reasons(inventory));
    }

    @Test
    void xmlExternalReferenceIsBlockedAndUnknownNamespaceRemainsInCatchAll() {
        SourceInput external = sourceInput("external.xml",
                "<!DOCTYPE beans SYSTEM \"https://example.invalid/spring-beans.dtd\"><beans/>",
                SourceClassification.OTHER);
        SpringMechanismInventory externalInventory = SpringMechanismScanner.scan(new SpringMechanismScanRequest(
                emptyFrontend(), validatedFramework531(), List.of(external), List.of()));
        assertEquals(Set.of("UNCLASSIFIED_MECHANISM"), reasons(externalInventory));

        SourceInput custom = sourceInput("custom.xml",
                "<beans xmlns:x=\"urn:example:custom\"><x:register/></beans>", SourceClassification.OTHER);
        SpringMechanismInventory customInventory = SpringMechanismScanner.scan(new SpringMechanismScanRequest(
                emptyFrontend(), validatedFramework531(), List.of(custom), List.of()));
        assertTrue(reasons(customInventory).contains("UNCLASSIFIED_MECHANISM"));
        assertEquals(2, customInventory.coverage().unclassifiedObservationCount());
        assertEquals(customInventory.coverage().rawObservationCount(),
                customInventory.coverage().semanticObligationCount());
    }

    @Test
    void malformedXmlWithholdsInnerDenominatorInsteadOfReportingAnEmptySuccess() {
        SourceInput malformed = sourceInput("malformed.xml", "<beans><bean id=\"x\"></beans>",
                SourceClassification.OTHER);
        SpringMechanismInventory inventory = SpringMechanismScanner.scan(new SpringMechanismScanRequest(
                emptyFrontend(), validatedFramework531(), List.of(malformed), List.of()));

        assertEquals(1, inventory.coverage().rawObservationCount());
        assertEquals(1, inventory.coverage().semanticObligationCount());
        assertEquals(1, inventory.coverage().unclassifiedObservationCount());
        assertEquals(Set.of("UNCLASSIFIED_MECHANISM"), reasons(inventory));
    }

    @Test
    void internalDtdEntitiesAreNeverExpandedByTheInventoryParser() {
        SourceInput entity = sourceInput("entity.xml",
                "<!DOCTYPE beans [<!ENTITY x \"boom\">]><beans><bean id=\"&x;\"/></beans>",
                SourceClassification.OTHER);

        SpringMechanismInventory inventory = SpringMechanismScanner.scan(new SpringMechanismScanRequest(
                emptyFrontend(), validatedFramework531(), List.of(entity), List.of()));

        assertEquals(1, inventory.coverage().rawObservationCount());
        assertEquals(1, inventory.coverage().unclassifiedObservationCount());
        assertEquals(Set.of("UNCLASSIFIED_MECHANISM"), reasons(inventory));
    }

    @Test
    void metadataEntriesAreCountedAndUnknownExtensionKeysAreNotDropped() {
        SourceInput metadata = sourceInput("META-INF/spring.factories",
                "org.springframework.boot.autoconfigure.EnableAutoConfiguration=com.acme.Known,\\\n"
                        + "  com.acme.Second\n"
                        + "com.acme.CustomExtension=com.acme.Unknown\n", SourceClassification.OTHER);
        SpringMechanismInventory inventory = SpringMechanismScanner.scan(new SpringMechanismScanRequest(
                emptyFrontend(), validatedFramework531(), List.of(), List.of(metadata)));

        assertEquals(3, inventory.coverage().rawObservationCount());
        assertEquals(3, inventory.coverage().semanticObligationCount());
        assertEquals(1, inventory.coverage().unclassifiedObservationCount());
        assertEquals(2, inventory.obligations().stream().filter(value ->
                value.primaryMechanism().equals("spring.registration.auto-configuration")).count());
        assertEquals(Set.of("UNCLASSIFIED_MECHANISM"), reasons(inventory));
    }

    private static Set<String> reasons(SpringMechanismInventory inventory) {
        return inventory.problems().stream().map(problem -> problem.reason().name())
                .collect(java.util.stream.Collectors.toSet());
    }

    private static AnnotationSpec annotation(String spelling, String canonicalType, EntityKind ownerKind) {
        return new AnnotationSpec(spelling, canonicalType, ownerKind, EntityOrigin.DEPENDENCY);
    }

    private static AnnotationSpec projectAnnotation(
            String spelling, String canonicalType, EntityKind ownerKind) {
        return new AnnotationSpec(spelling, canonicalType, ownerKind, EntityOrigin.PROJECT);
    }

    private static Fixture fixtureFrontend(String name, List<AnnotationSpec> specs) throws Exception {
        return fixtureFrontend(name, specs, validatedFramework531());
    }

    private static Fixture fixtureFrontend(String name, List<AnnotationSpec> specs,
            SpringFrameworkEvidence frameworkEvidence) throws Exception {
        SourceInput input = sourceInput(name, SourceClassification.TEST);
        String text = input.text();
        JavaSymbolName ownerType = JavaSymbolName.topLevelType("fixtures", "InventoryFixture");
        EntityScope projectScope = EntityScope.project(MODULE.identity());
        Map<EntityIdentity, DeclarationRecord> declarations = new HashMap<>();
        List<RelationshipOccurrence> occurrences = new ArrayList<>();
        List<ObservationRecord> observations = new ArrayList<>();
        List<AnnotationUseRecord> annotations = new ArrayList<>();
        Map<String, Integer> nextSearch = new HashMap<>();

        for (int index = 0; index < specs.size(); index++) {
            AnnotationSpec spec = specs.get(index);
            int offset = text.indexOf(spec.spelling(), nextSearch.getOrDefault(spec.spelling(), 0));
            assertTrue(offset >= 0, "fixture annotation not found: " + spec.spelling());
            nextSearch.put(spec.spelling(), offset + spec.spelling().length());
            SourceSpan span = span(input.document().identity(), text, offset, offset + spec.spelling().length());
            JavaSymbolName ownerName = switch (spec.ownerKind()) {
                case TYPE -> JavaSymbolName.memberType(ownerType, "Site" + index);
                case METHOD -> JavaSymbolName.method(ownerType, "site" + index, List.of());
                case CONSTRUCTOR -> JavaSymbolName.constructor(ownerType, List.of());
                case FIELD -> JavaSymbolName.field(ownerType, "site" + index);
                default -> throw new IllegalArgumentException("unsupported test owner kind");
            };
            Entity owner = Entity.create(EntityOrigin.PROJECT, projectScope, spec.ownerKind(),
                    ownerName.canonicalName(), Optional.of(span));
            int split = spec.canonicalType().lastIndexOf('.');
            JavaSymbolName targetName = JavaSymbolName.topLevelType(
                    spec.canonicalType().substring(0, split), spec.canonicalType().substring(split + 1));
            EntityScope targetScope = spec.targetOrigin() == EntityOrigin.PROJECT
                    ? projectScope : artifactFor(spec.canonicalType(), frameworkEvidence).entityScope();
            Entity target = Entity.create(spec.targetOrigin(), targetScope, EntityKind.TYPE,
                    targetName.canonicalName(), Optional.empty());
            declarations.put(owner.identity(), new DeclarationRecord(owner, spec.spelling(),
                    SemanticStatus.RESOLVED, direct(), List.of(), List.of()));
            declarations.put(target.identity(), new DeclarationRecord(target, spec.canonicalType(),
                    SemanticStatus.RESOLVED, direct(), List.of(), List.of()));
            SemanticRelationship relationship = SemanticRelationship.create(owner.identity(),
                    new RelationshipKind("java.annotated-with"), new RelationshipTarget.Resolved(target.identity()));
            RelationshipOccurrence occurrence = RelationshipOccurrence.create(relationship, span, 0,
                    SemanticStatus.RESOLVED, direct(), List.of(), List.of());
            occurrences.add(occurrence);
            observations.add(new ObservationRecord(input.document().identity(),
                    new RelationshipKind("java.annotated-with"), Optional.of(span), SemanticStatus.RESOLVED,
                    ObservationRecord.EvidenceState.VERIFIED, ObservationRecord.EvidenceState.VERIFIED,
                    Optional.of(occurrence.identity()), spec.spelling(), List.of()));
            annotations.add(new AnnotationUseRecord(JavaSymbolName.annotationUse(ownerName,
                    input.document().identity(), offset).canonicalName(), owner.identity(), span,
                    "declaration-syntax", spec.spelling()));
        }

        FrontendResult frontend = new FrontendResult(ANALYSIS,
                new VersionedIdentifier("frontend.fixture", "m4a1"), FrontendResult.State.COMPLETED,
                List.copyOf(declarations.values()), occurrences, observations,
                List.of(new SourceOutcome(input.document().identity(), SourceOutcome.State.PROCESSED, List.of())),
                coverage(observations.size(), occurrences.size()), List.of(), List.of(), annotations);
        return new Fixture(frontend, input.document());
    }

    private static FrontendResult emptyFrontend() {
        return new FrontendResult(ANALYSIS, new VersionedIdentifier("frontend.fixture", "m4a1"),
                FrontendResult.State.COMPLETED, List.of(), List.of(), List.of(), List.of(),
                coverage(0, 0), List.of());
    }

    private static List<CategoryCoverage> coverage(long attemptedAnnotations, long emittedAnnotations) {
        return FrontendRequest.CATEGORIES.stream().map(category -> new CategoryCoverage(
                new RelationshipKind("java." + category), CategoryCoverage.Support.IMPLEMENTED,
                category.equals("annotated-with") ? attemptedAnnotations : 0,
                category.equals("annotated-with") ? emittedAnnotations : 0,
                category.equals("annotated-with") ? attemptedAnnotations - emittedAnnotations : 0)).toList();
    }

    private static Derivation direct() {
        return new Derivation(DerivationKind.DIRECT,
                new VersionedIdentifier("spring.test-direct", "1"), List.of());
    }

    private static SourceInput sourceInput(String fixture, SourceClassification classification) throws IOException {
        Path path = repositoryRoot().resolve("benchmarks/m4-r0/src/test/resources/fixtures").resolve(fixture);
        byte[] bytes = Files.readAllBytes(path);
        SourceDocument document = SourceDocument.create(REPOSITORY, MODULE,
                "benchmarks/m4-r0/src/test/resources/fixtures/" + fixture,
                ContentDigest.sha256(bytes), classification);
        return new SourceInput(document, bytes);
    }

    private static SourceInput sourceInput(String path, String text, SourceClassification classification) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        SourceDocument document = SourceDocument.create(REPOSITORY, MODULE, path,
                ContentDigest.sha256(bytes), classification);
        return new SourceInput(document, bytes);
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.isDirectory(current.resolve("benchmarks/m4-r0"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("repository root not found");
        return current;
    }

    private static SourceSpan span(SourceDocumentIdentity document, String text, int start, int end) {
        int[] startPosition = position(text, start);
        int[] endPosition = position(text, end);
        return new SourceSpan(document, startPosition[0], startPosition[1], endPosition[0], endPosition[1]);
    }

    private static int[] position(String text, int offset) {
        int line = 1;
        int column = 1;
        for (int index = 0; index < offset; index++) {
            char value = text.charAt(index);
            if (value == '\r') {
                if (index + 1 < offset && text.charAt(index + 1) == '\n') index++;
                line++;
                column = 1;
            } else if (value == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new int[] {line, column};
    }

    private static SpringFrameworkEvidence validatedFramework531() {
        return new SpringFrameworkEvidence(true,
                Optional.of(ContentDigest.sha256Utf8("m4a1-test-classpath-manifest")), List.of(
                artifactDigest("org.springframework:spring-aop:5.3.31", "3f0c666f317abaa845fc3a24fba219b1f469716bf309cccd755eecb8fee20430"),
                artifactDigest("org.springframework:spring-beans:5.3.31", "a8d6d99003d0a28049cba4273afbcfc64e1107ee3c33f67935853e9711544aa7"),
                artifactDigest("org.springframework:spring-context:5.3.31", "38def055d1e22b5514b1cb19cef4474e5c1b0d2127c483e7d014bde87c4a4cf3"),
                artifactDigest("org.springframework:spring-core:5.3.31", "7013ed3da15a8d4be797f5c310f9aa1b196b97f2313bc41e60ef3f5627224fe9"),
                artifactDigest("org.springframework:spring-expression:5.3.31", "e027f122b8a4e3030339068220bed02d1c9d397eb5897f1e33ba2f63b22591ac"),
                artifactDigest("org.springframework:spring-jcl:5.3.31", "eee0df6a25a9c56d228ea86272546aa5a0656caf2f14e7b375417b066abbc0db"),
                artifactDigest("org.springframework.boot:spring-boot:2.7.18", "530f4e0fdfeb3a0e2b3a369d15cdea38fbdc1696f8b030c35a6ad65c27524950"),
                artifactDigest("org.springframework.boot:spring-boot-autoconfigure:2.7.18", "1c4e0aadcb662b6149b536a2cf288003ffefe81a6cc69846e9f14976529a1b08"),
                artifactDigest("javax.annotation:javax.annotation-api:1.3.2", "e04ba5195bcd555dc95650f7cc614d151e4bcd52d29a10b8aa2197f3ab89ab9b"),
                artifactDigest("jakarta.annotation:jakarta.annotation-api:2.1.1", "5f65fdaf424eee2b55e1d882ba9bb376be93fb09b37b808be6e22e8851c909fe")));
    }

    private static SpringFrameworkEvidence lockedFramework(String framework, String boot) throws IOException {
        String json = Files.readString(repositoryRoot().resolve("benchmarks/m4-r0/artifacts.lock.json"));
        Matcher matcher = Pattern.compile(
                "\\\"coordinate\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"(?s:.*?)"
                        + "\\\"sha256\\\"\\s*:\\s*\\\"([0-9a-f]{64})\\\"").matcher(json);
        ArrayList<SpringFrameworkEvidence.Artifact> artifacts = new ArrayList<>();
        while (matcher.find()) {
            String coordinate = matcher.group(1);
            boolean selectedFramework = coordinate.startsWith("org.springframework:spring-")
                    && coordinate.endsWith(":" + framework);
            boolean selectedBoot = coordinate.startsWith("org.springframework.boot:spring-boot")
                    && coordinate.endsWith(":" + boot);
            if (selectedFramework || selectedBoot) {
                artifacts.add(artifactDigest(coordinate, matcher.group(2)));
            }
        }
        assertEquals(8, artifacts.size(), "locked tuple artifact count");
        return new SpringFrameworkEvidence(true,
                Optional.of(ContentDigest.sha256Utf8("locked-manifest:" + framework + ":" + boot)), artifacts);
    }

    private static SpringFrameworkEvidence.Artifact artifactFor(
            String canonicalType, SpringFrameworkEvidence evidence) {
        String coordinatePrefix;
        if (canonicalType.startsWith("org.springframework.boot.autoconfigure.")) {
            coordinatePrefix = "org.springframework.boot:spring-boot-autoconfigure:";
        } else if (canonicalType.startsWith("org.springframework.boot.")) {
            coordinatePrefix = "org.springframework.boot:spring-boot:";
        } else if (canonicalType.startsWith("org.springframework.beans.")) {
            coordinatePrefix = "org.springframework:spring-beans:";
        } else if (canonicalType.startsWith("org.springframework.core.")) {
            coordinatePrefix = "org.springframework:spring-core:";
        } else if (canonicalType.startsWith("org.springframework.aop.")) {
            coordinatePrefix = "org.springframework:spring-aop:";
        } else if (canonicalType.startsWith("org.springframework.")) {
            coordinatePrefix = "org.springframework:spring-context:";
        } else if (canonicalType.startsWith("javax.annotation.")) {
            coordinatePrefix = "javax.annotation:javax.annotation-api:";
        } else if (canonicalType.startsWith("jakarta.annotation.")) {
            coordinatePrefix = "jakarta.annotation:jakarta.annotation-api:";
        } else {
            return artifact("com.acme:m4a1-unknown:1", "m4a1-unknown");
        }
        return evidence.artifacts().stream()
                .filter(value -> value.coordinate().startsWith(coordinatePrefix))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                        "No test artifact for annotation type " + canonicalType));
    }

    private static SpringFrameworkEvidence.Artifact artifact(String coordinate, String content) {
        return new SpringFrameworkEvidence.Artifact(coordinate, ContentDigest.sha256Utf8(content));
    }

    private static SpringFrameworkEvidence.Artifact artifactDigest(String coordinate, String hex) {
        return new SpringFrameworkEvidence.Artifact(coordinate, new ContentDigest("sha256:" + hex));
    }

    private record AnnotationSpec(String spelling, String canonicalType, EntityKind ownerKind,
            EntityOrigin targetOrigin) {}
    private record Fixture(FrontendResult frontend, SourceDocument document) {}
}
