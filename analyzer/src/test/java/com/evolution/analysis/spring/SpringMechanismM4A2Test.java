package com.evolution.analysis.spring;

import static org.junit.jupiter.api.Assertions.*;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class SpringMechanismM4A2Test {
    private static final RepositoryIdentity REPOSITORY =
            RepositoryIdentity.fromCanonicalCoordinate("https://example.test/m4a2-fixtures.git");
    private static final ModuleDescriptor MODULE = ModuleDescriptor.create(REPOSITORY, ".", "fixture-root");
    private static final AnalysisIdentity ANALYSIS =
            AnalysisIdentity.fromCanonicalManifestInputs("m4a2-fixture-analysis");
    private static final RelationshipKind ANNOTATED_WITH = new RelationshipKind("java.annotated-with");
    private static final RelationshipKind DECLARES = new RelationshipKind("java.declares");

    @Test
    void composedAnnotationGraphRetainsCyclesAliasesDefaultsAndRepeatableContainer() {
        GraphFixture fixture = graphFixture();

        SpringMechanismInventory inventory = SpringMechanismScanner.scan(new SpringMechanismScanRequest(
                fixture.frontend(), validatedFramework531(), List.of(), List.of()));

        assertEquals("spring-mechanism-inventory-v2", inventory.schemaVersion());
        assertEquals(new VersionedIdentifier("spring.mechanism-scanner", "m4a.2"), inventory.provider());
        assertEquals(3, inventory.annotationDeclarations().size());
        assertEquals(4, inventory.annotationMetaEdges().size());
        assertEquals(1, inventory.annotationCycles().size());
        assertEquals(Set.of(fixture.annotationA(), fixture.annotationB()),
                Set.copyOf(inventory.annotationCycles().getFirst().declarations()));

        SpringMechanismInventory.AnnotationDeclarationEvidence annotationA = inventory.annotationDeclarations()
                .stream().filter(value -> value.declaration().equals(fixture.annotationA())).findFirst().orElseThrow();
        assertEquals(List.of(fixture.container()), annotationA.repeatableContainerTargets());
        SpringMechanismInventory.AnnotationAttributeEvidence value = annotationA.attributes().getFirst();
        assertTrue(value.defaultDeclared());
        assertEquals(1, value.aliasObservationIdentities().size());

        SpringMechanismInventory.RawObservation targetUse = inventory.rawObservations().stream()
                .filter(raw -> raw.kind() == SpringMechanismInventory.RawKind.ANNOTATION_USE)
                .filter(raw -> raw.owner().equals(Optional.of(fixture.target())))
                .findFirst().orElseThrow();
        assertEquals(List.of("spring.bean.stereotype.composed"), inventory.obligations().stream()
                .filter(valueObligation -> valueObligation.rawObservationIdentity().equals(targetUse.identity()))
                .map(SpringMechanismInventory.SemanticObligation::primaryMechanism).toList());
        assertTrue(inventory.problems().stream().anyMatch(problem ->
                problem.reason() == SpringMechanismInventory.ProblemReason.ANNOTATION_GRAPH_INCOMPLETE));
        assertThrows(IllegalArgumentException.class, () ->
                SpringMechanismInventory.AnnotationMetaEdge.create(fixture.annotationA(), Optional.empty(),
                        "fixture.Unresolved", targetUse.identity(),
                        SpringMechanismInventory.EvidenceState.VERIFIED));
    }

    private static GraphFixture graphFixture() {
        String text = """
                package fixture;
                import java.lang.annotation.*;
                @Repeatable(Roles.class) @B @interface A { @org.springframework.core.annotation.AliasFor(annotation=org.springframework.stereotype.Component.class, attribute=\"value\") String value() default \"\"; }
                @A @org.springframework.stereotype.Component @interface B {}
                @interface Roles { A[] value(); }
                @A class Target {}
                """;
        SourceInput input = sourceInput("fixture/Graph.java", text);
        EntityScope project = EntityScope.project(MODULE.identity());
        Map<EntityIdentity, DeclarationRecord> declarations = new HashMap<>();
        List<RelationshipOccurrence> occurrences = new ArrayList<>();
        List<ObservationRecord> observations = new ArrayList<>();
        List<AnnotationUseRecord> annotations = new ArrayList<>();
        List<TypeUseRecord> types = new ArrayList<>();

        JavaSymbolName aName = JavaSymbolName.topLevelType("fixture", "A");
        JavaSymbolName bName = JavaSymbolName.topLevelType("fixture", "B");
        JavaSymbolName rolesName = JavaSymbolName.topLevelType("fixture", "Roles");
        JavaSymbolName targetName = JavaSymbolName.topLevelType("fixture", "Target");
        Entity a = projectType(aName, spanOf(input, "@Repeatable(Roles.class) @B @interface A {", "; }"));
        Entity b = projectType(bName, spanOf(input, "@A @org.springframework.stereotype.Component @interface B {}", "{}"));
        Entity roles = projectType(rolesName, spanOf(input, "@interface Roles { A[] value(); }", "}"));
        Entity target = projectType(targetName, spanOf(input, "@A class Target {}", "{}"));
        JavaSymbolName attributeName = JavaSymbolName.method(aName, "value", List.of());
        Entity attribute = Entity.create(EntityOrigin.PROJECT, project, EntityKind.METHOD,
                attributeName.canonicalName(), Optional.of(spanOf(input, "String value() default \"\";", ";")));
        for (Entity entity : List.of(a, b, roles, target, attribute)) {
            declarations.put(entity.identity(), new DeclarationRecord(entity, slice(input, entity.declaration().orElseThrow()),
                    SemanticStatus.RESOLVED, direct(), List.of(), List.of()));
        }

        Entity component = dependencyType("org.springframework.stereotype.Component");
        Entity aliasFor = dependencyType("org.springframework.core.annotation.AliasFor");
        Entity repeatable = Entity.create(EntityOrigin.JDK,
                EntityScope.external(EntityOrigin.JDK, "jdk-platform:21", ContentDigest.sha256Utf8("jdk-21")),
                EntityKind.TYPE, JavaSymbolName.topLevelType("java.lang.annotation", "Repeatable").canonicalName(),
                Optional.empty());
        for (Entity entity : List.of(component, aliasFor, repeatable)) {
            declarations.put(entity.identity(), new DeclarationRecord(entity, entity.canonicalName(),
                    SemanticStatus.RESOLVED, direct(), List.of(), List.of()));
        }

        addRelationship(input, a, DECLARES, attribute, "String value() default \"\";", occurrences, observations);
        addAnnotation(input, aName, a, repeatable, "@Repeatable(Roles.class)", annotations, occurrences, observations);
        addAnnotation(input, aName, a, b, "@B", annotations, occurrences, observations);
        addAnnotation(input, attributeName, attribute, aliasFor,
                "@org.springframework.core.annotation.AliasFor(annotation=org.springframework.stereotype.Component.class, attribute=\"value\")",
                annotations, occurrences, observations);
        addAnnotation(input, bName, b, a, "@A", annotations, occurrences, observations);
        addAnnotation(input, bName, b, component, "@org.springframework.stereotype.Component", annotations, occurrences, observations);
        addAnnotation(input, targetName, target, a, "@A class", "@A", annotations, occurrences, observations);

        int containerOffset = text.indexOf("Roles.class");
        SourceSpan containerSpan = span(input.document().identity(), text, containerOffset, containerOffset + "Roles".length());
        types.add(new TypeUseRecord(Optional.of(a.identity()), new RelationshipKind("java.type-uses"),
                containerSpan, new JavaType(JavaType.Kind.DECLARED, "Roles", Optional.of(roles.identity()),
                        List.of(), Optional.empty(), SemanticStatus.RESOLVED), false));

        List<CategoryCoverage> coverage = FrontendRequest.CATEGORIES.stream().map(category -> {
            RelationshipKind kind = new RelationshipKind("java." + category);
            long count = observations.stream().filter(value -> value.category().equals(kind)).count();
            return new CategoryCoverage(kind, CategoryCoverage.Support.IMPLEMENTED, count, count, 0);
        }).toList();
        FrontendResult frontend = new FrontendResult(ANALYSIS,
                new VersionedIdentifier("frontend.fixture", "m4a2"), FrontendResult.State.COMPLETED,
                List.copyOf(declarations.values()), occurrences, observations,
                List.of(new SourceOutcome(input.document().identity(), SourceOutcome.State.PROCESSED, List.of())),
                coverage, List.of(), types, annotations);
        return new GraphFixture(frontend, a.identity(), b.identity(), roles.identity(), target.identity());
    }

    private static Entity projectType(JavaSymbolName name, SourceSpan span) {
        return Entity.create(EntityOrigin.PROJECT, EntityScope.project(MODULE.identity()), EntityKind.TYPE,
                name.canonicalName(), Optional.of(span));
    }

    private static Entity dependencyType(String canonicalType) {
        int split = canonicalType.lastIndexOf('.');
        SpringFrameworkEvidence.Artifact artifact = canonicalType.contains(".core.")
                ? validatedFramework531().artifacts().stream().filter(value -> value.artifactId().equals("spring-core"))
                        .findFirst().orElseThrow()
                : validatedFramework531().artifacts().stream().filter(value -> value.artifactId().equals("spring-context"))
                        .findFirst().orElseThrow();
        return Entity.create(EntityOrigin.DEPENDENCY, artifact.entityScope(), EntityKind.TYPE,
                JavaSymbolName.topLevelType(canonicalType.substring(0, split), canonicalType.substring(split + 1))
                        .canonicalName(), Optional.empty());
    }

    private static void addAnnotation(SourceInput input, JavaSymbolName ownerName, Entity owner, Entity target,
            String spelling, List<AnnotationUseRecord> annotations, List<RelationshipOccurrence> occurrences,
            List<ObservationRecord> observations) {
        addAnnotation(input, ownerName, owner, target, spelling, spelling, annotations, occurrences, observations);
    }

    private static void addAnnotation(SourceInput input, JavaSymbolName ownerName, Entity owner, Entity target,
            String search, String spelling, List<AnnotationUseRecord> annotations,
            List<RelationshipOccurrence> occurrences, List<ObservationRecord> observations) {
        int offset = input.text().indexOf(search);
        assertTrue(offset >= 0, "missing annotation fixture: " + search);
        SourceSpan sourceSpan = span(input.document().identity(), input.text(), offset, offset + spelling.length());
        SemanticRelationship relationship = SemanticRelationship.create(owner.identity(), ANNOTATED_WITH,
                new RelationshipTarget.Resolved(target.identity()));
        RelationshipOccurrence occurrence = RelationshipOccurrence.create(relationship, sourceSpan, 0,
                SemanticStatus.RESOLVED, direct(), List.of(), List.of());
        occurrences.add(occurrence);
        observations.add(observation(input, occurrence, spelling));
        annotations.add(new AnnotationUseRecord(JavaSymbolName.annotationUse(ownerName,
                input.document().identity(), offset).canonicalName(), owner.identity(), sourceSpan,
                "declaration-syntax", spelling));
    }

    private static void addRelationship(SourceInput input, Entity owner, RelationshipKind kind, Entity target,
            String spelling, List<RelationshipOccurrence> occurrences, List<ObservationRecord> observations) {
        int offset = input.text().indexOf(spelling);
        SourceSpan sourceSpan = span(input.document().identity(), input.text(), offset, offset + spelling.length());
        RelationshipOccurrence occurrence = RelationshipOccurrence.create(
                SemanticRelationship.create(owner.identity(), kind, new RelationshipTarget.Resolved(target.identity())),
                sourceSpan, 0, SemanticStatus.RESOLVED, direct(), List.of(), List.of());
        occurrences.add(occurrence);
        observations.add(observation(input, occurrence, spelling));
    }

    private static ObservationRecord observation(SourceInput input, RelationshipOccurrence occurrence, String spelling) {
        return new ObservationRecord(input.document().identity(), occurrence.relationship().kind(),
                Optional.of(occurrence.span()), SemanticStatus.RESOLVED,
                ObservationRecord.EvidenceState.VERIFIED, ObservationRecord.EvidenceState.VERIFIED,
                Optional.of(occurrence.identity()), spelling, List.of());
    }

    private static SourceInput sourceInput(String path, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return new SourceInput(SourceDocument.create(REPOSITORY, MODULE, path,
                ContentDigest.sha256(bytes), SourceClassification.TEST), bytes);
    }

    private static SourceSpan spanOf(SourceInput input, String startText, String endText) {
        int start = input.text().indexOf(startText);
        int end = input.text().indexOf(endText, start) + endText.length();
        assertTrue(start >= 0 && end > start);
        return span(input.document().identity(), input.text(), start, end);
    }

    private static String slice(SourceInput input, SourceSpan sourceSpan) {
        int start = offset(input.text(), sourceSpan.startLine(), sourceSpan.startColumn());
        int end = offset(input.text(), sourceSpan.endLine(), sourceSpan.endColumn());
        return input.text().substring(start, end);
    }

    private static int offset(String text, int line, int column) {
        int currentLine = 1;
        int index = 0;
        while (currentLine < line) if (text.charAt(index++) == '\n') currentLine++;
        return index + column - 1;
    }

    private static SourceSpan span(SourceDocumentIdentity document, String text, int start, int end) {
        int[] from = position(text, start);
        int[] to = position(text, end);
        return new SourceSpan(document, from[0], from[1], to[0], to[1]);
    }

    private static int[] position(String text, int offset) {
        int line = 1;
        int column = 1;
        for (int index = 0; index < offset; index++) {
            if (text.charAt(index) == '\n') { line++; column = 1; }
            else column++;
        }
        return new int[] {line, column};
    }

    private static Derivation direct() {
        return new Derivation(DerivationKind.DIRECT,
                new VersionedIdentifier("spring.test-direct", "1"), List.of());
    }

    private static SpringFrameworkEvidence validatedFramework531() {
        return new SpringFrameworkEvidence(true,
                Optional.of(ContentDigest.sha256Utf8("m4a2-test-classpath-manifest")), List.of(
                artifact("org.springframework:spring-aop:5.3.31", "3f0c666f317abaa845fc3a24fba219b1f469716bf309cccd755eecb8fee20430"),
                artifact("org.springframework:spring-beans:5.3.31", "a8d6d99003d0a28049cba4273afbcfc64e1107ee3c33f67935853e9711544aa7"),
                artifact("org.springframework:spring-context:5.3.31", "38def055d1e22b5514b1cb19cef4474e5c1b0d2127c483e7d014bde87c4a4cf3"),
                artifact("org.springframework:spring-core:5.3.31", "7013ed3da15a8d4be797f5c310f9aa1b196b97f2313bc41e60ef3f5627224fe9"),
                artifact("org.springframework:spring-expression:5.3.31", "e027f122b8a4e3030339068220bed02d1c9d397eb5897f1e33ba2f63b22591ac"),
                artifact("org.springframework:spring-jcl:5.3.31", "eee0df6a25a9c56d228ea86272546aa5a0656caf2f14e7b375417b066abbc0db"),
                artifact("org.springframework.boot:spring-boot:2.7.18", "530f4e0fdfeb3a0e2b3a369d15cdea38fbdc1696f8b030c35a6ad65c27524950"),
                artifact("org.springframework.boot:spring-boot-autoconfigure:2.7.18", "1c4e0aadcb662b6149b536a2cf288003ffefe81a6cc69846e9f14976529a1b08")));
    }

    private static SpringFrameworkEvidence.Artifact artifact(String coordinate, String hex) {
        return new SpringFrameworkEvidence.Artifact(coordinate, new ContentDigest("sha256:" + hex));
    }

    private record GraphFixture(FrontendResult frontend, EntityIdentity annotationA,
            EntityIdentity annotationB, EntityIdentity container, EntityIdentity target) {}
}
