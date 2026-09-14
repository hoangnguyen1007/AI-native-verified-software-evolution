package com.evolution.analysis.spring.condition;

import com.evolution.analysis.buildmodel.SourcePlanModel;
import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.input.FrontendAssemblyResult;
import com.evolution.analysis.spring.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.spring.condition.ExogenousConditionEvaluatorTest.*;

class ConditionLoweringTest {
    static final String PROFILE = "org.springframework.context.annotation.Profile";
    static final String PROPERTY = "org.springframework.boot.autoconfigure.condition.ConditionalOnProperty";
    record Spec(String type, String spelling, boolean impostor) { Spec(String type, String spelling) { this(type, spelling, false); } }
    record Fixture(SpringBuildContext build, SpringMechanismInventory inventory, ConditionEvidence evidence) {}
    static List<SpringFrameworkEvidence.Artifact> pins(String framework, String boot) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.isRegularFile(root.resolve("benchmarks/m4-r0/artifacts.lock.json"))) root = root.getParent();
        String json = Files.readString(root.resolve("benchmarks/m4-r0/artifacts.lock.json"));
        var matcher = Pattern.compile("\"coordinate\"\\s*:\\s*\"([^\"]+)\"(?s:.*?)\"sha256\"\\s*:\\s*\"([0-9a-f]{64})\"").matcher(json);
        List<SpringFrameworkEvidence.Artifact> artifacts = new ArrayList<>();
        while (matcher.find()) {
            String coordinate = matcher.group(1);
            if (coordinate.startsWith("org.springframework:") && coordinate.endsWith(":" + framework)
                    || coordinate.startsWith("org.springframework.boot:") && coordinate.endsWith(":" + boot))
                artifacts.add(new SpringFrameworkEvidence.Artifact(coordinate, new ContentDigest("sha256:" + matcher.group(2))));
        }
        assertEquals(8, artifacts.size()); return List.copyOf(artifacts);
    }
    static Fixture fixture(List<Spec> specs) throws Exception { return fixture(specs, pins("6.2.0", "3.4.0")); }
    static Fixture fixture(List<Spec> specs, List<SpringFrameworkEvidence.Artifact> artifacts) {
        var repository = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/m4b2-fixture.git");
        var module = ModuleDescriptor.create(repository, ".", "fixture");
        String source = "package fixture;\n" + specs.stream().map(s -> s.spelling() + "\nclass Site" + specs.indexOf(s) + " {}\n").collect(java.util.stream.Collectors.joining());
        var document = SourceDocument.create(repository, module, "Fixture.java", ContentDigest.sha256Utf8(source), SourceClassification.MAIN);
        var snapshot = RepositorySnapshot.create(repository, Optional.empty(), false, List.of(SnapshotFile.from(document)), List.of(document));
        var platform = PlatformInput.create(21, "21-fixture", "authored", List.of(new PlatformInput.Artifact("java.base.jmod", ContentDigest.sha256Utf8("platform"), Path.of("unused.jmod"), PlatformInput.Format.JMOD)));
        var binaries = artifacts.stream().map(a -> new BinaryInput(new ClasspathEntry(ClasspathEntryKind.DEPENDENCY, a.classpathLogicalName(), a.contentDigest()), Path.of(a.artifactId() + "-unused.jar"))).toList();
        var plan = new FrontendPlan(Optional.of(21), Optional.of(21), false, ContentDigest.sha256Utf8("exact-fixture-classpath"), ContentDigest.sha256Utf8("decoding"));
        var framework = new SpringFrameworkEvidence(true, Optional.of(plan.classpathManifest()), artifacts);
        var component = new ManifestComponent(new VersionedIdentifier("fixture.component", "1"), ContentDigest.sha256Utf8("fixture"));
        List<ClasspathEntry> classpath = new ArrayList<>(List.of(platform.entry())); binaries.forEach(b -> classpath.add(b.entry()));
        var manifest = AnalysisManifest.create(new VersionedIdentifier("analysis.manifest", "1"), snapshot, List.of(module), classpath,
                AnalysisConfiguration.create(new VersionedIdentifier("analysis.configuration", "1"), FrontendRequest.options(plan, module.identity(), SourceClassification.MAIN, List.of(document), platform)), component, component, component);
        var request = new FrontendRequest(manifest, module.identity(), SourceClassification.MAIN, plan, List.of(new SourceInput(document, source.getBytes(StandardCharsets.UTF_8))), platform, binaries);
        var assembly = com.evolution.analysis.input.ConditionTestInputs.assembly(request);
        var build = SpringBuildContext.from(assembly, module.identity(), SourcePlanModel.Kind.MAIN);
        Map<EntityIdentity, DeclarationRecord> declarations = new HashMap<>();
        List<RelationshipOccurrence> relationships = new ArrayList<>(); List<ObservationRecord> observations = new ArrayList<>(); List<AnnotationUseRecord> annotations = new ArrayList<>();
        var derivation = new Derivation(DerivationKind.DIRECT, new VersionedIdentifier("fixture.authored", "1"), List.of());
        int index = 0;
        for (var spec : specs) {
            var span = new SourceSpan(document.identity(), 2 + index * 2, 1, 2 + index * 2, spec.spelling().length() + 1);
            var ownerName = JavaSymbolName.topLevelType("fixture", "Site" + index++);
            var owner = Entity.create(EntityOrigin.PROJECT, EntityScope.project(module.identity()), EntityKind.TYPE, ownerName.canonicalName(), Optional.of(span));
            int dot = spec.type().lastIndexOf('.'); String key = JavaSymbolName.topLevelType(spec.type().substring(0, dot), spec.type().substring(dot + 1)).canonicalName();
            var artifact = artifacts.stream().filter(a -> a.artifactId().equals(spec.type().startsWith("org.springframework.boot.") ? "spring-boot-autoconfigure" : "spring-context")).findFirst().orElseThrow();
            var target = Entity.create(spec.impostor() ? EntityOrigin.PROJECT : EntityOrigin.DEPENDENCY,
                    spec.impostor() ? EntityScope.project(module.identity()) : artifact.entityScope(), EntityKind.TYPE, key, Optional.empty());
            declarations.put(owner.identity(), new DeclarationRecord(owner, spec.spelling(), SemanticStatus.RESOLVED, derivation, List.of(), List.of()));
            declarations.put(target.identity(), new DeclarationRecord(target, spec.type(), SemanticStatus.RESOLVED, derivation, List.of(), List.of()));
            var relationship = RelationshipOccurrence.create(SemanticRelationship.create(owner.identity(), new RelationshipKind("java.annotated-with"), new RelationshipTarget.Resolved(target.identity())), span, 0, SemanticStatus.RESOLVED, derivation, List.of(), List.of());
            relationships.add(relationship);
            observations.add(new ObservationRecord(document.identity(), new RelationshipKind("java.annotated-with"), Optional.of(span), SemanticStatus.RESOLVED,
                    ObservationRecord.EvidenceState.VERIFIED, ObservationRecord.EvidenceState.VERIFIED, Optional.of(relationship.identity()), spec.spelling(), List.of()));
            annotations.add(new AnnotationUseRecord(JavaSymbolName.annotationUse(ownerName, document.identity(), index).canonicalName(), owner.identity(), span, "declaration-syntax", spec.spelling()));
        }
        var coverage = FrontendRequest.CATEGORIES.stream().map(c -> new CategoryCoverage(new RelationshipKind("java." + c), CategoryCoverage.Support.IMPLEMENTED,
                c.equals("annotated-with") ? specs.size() : 0, c.equals("annotated-with") ? specs.size() : 0, 0)).toList();
        var frontend = new FrontendResult(manifest.identity(), new VersionedIdentifier("frontend.fixture", "m4b2"), FrontendResult.State.COMPLETED,
                List.copyOf(declarations.values()), relationships, observations, List.of(new SourceOutcome(document.identity(), SourceOutcome.State.PROCESSED, List.of())), coverage, List.of(), List.of(), annotations);
        var inventory = SpringMechanismScanner.scan(new SpringMechanismScanRequest(frontend, framework, List.of(), List.of()));
        return new Fixture(build, inventory, new ConditionEvidence.Source(document.identity(), document.contentDigest(), Optional.of(new SourceSpan(document.identity(), 1, 1, 2, 1)), 0));
    }
    static ConditionEvidenceLowering.Result lower(Fixture fixture) {
        return ConditionEvidenceLowering.lower(fixture.build(), fixture.inventory(), List.of(), ConditionEvidenceLowering.Limits.conservative());
    }
    @Test void directLiteralConditionsLowerWithExactProvenanceAndAClosedObligationDenominator() throws Exception {
        var fixture = fixture(List.of(new Spec(PROFILE, "@Profile({\"dev & !cloud\", \"test\"})"),
                new Spec(PROPERTY, "@ConditionalOnProperty(prefix=\" feature \", name={\"enabled\",\"enabled\"}, havingValue=\" false \", matchIfMissing=true)"),
                new Spec("org.springframework.context.annotation.Bean", "@Bean")));
        var result = lower(fixture);
        assertEquals(2, result.coverage().loweredRows());
        assertEquals(fixture.inventory().obligations().size(), result.coverage().inputObligations());
        assertEquals(1, result.coverage().otherObligations()); assertTrue(result.capabilityGaps().isEmpty());
        var property = result.occurrences().stream().map(ConditionOccurrence::expression).filter(e -> e.operand() instanceof ConditionExpression.Property).findFirst().orElseThrow();
        var operand = (ConditionExpression.Property) property.operand();
        assertEquals(List.of("feature.enabled"), operand.keys()); assertEquals(" false ", operand.havingValue());
        assertTrue(result.occurrences().stream().allMatch(o -> o.declarationEvidenceKey() instanceof ConditionEvidence.Source source && fixture.build().containsSource(source)));
        assertEquals(result.identity(), lower(fixture).identity());
    }
    @Test void profileGrammarOrArraysCaseSensitivityAndDuplicatesHaveIndependentTruthExpectations() throws Exception {
        var fixture = fixture(List.of(new Spec(PROFILE, "@Profile({\"(dev & !cloud) | test\", \"test\"})")));
        var result = lower(fixture); assertEquals(1, result.coverage().loweredRows());
        var spaceBase = normalized(List.of(ConfigurationSpaceTest.profileDomain("dev"), ConfigurationSpaceTest.profileDomain("cloud"), ConfigurationSpaceTest.profileDomain("test")));
        var space = new ConfigurationSpace(fixture.build(), spaceBase.repositoryEnvelope(), Optional.empty(),
                spaceBase.domains().stream().map(d -> new FiniteDomain(d.variable(), d.values(), fixture.evidence())).toList(), List.of(),
                new ConfigurationSpace.PrecedencePolicy(ExogenousConditionEvaluator.PRECEDENCE, List.of(), fixture.evidence()),
                new ConfigurationSpace.ProfilePolicy(ExogenousConditionEvaluator.PROFILES, List.of(), Map.of(), List.of(), fixture.evidence()),
                ConfigurationSpaceTest.VERSION, new ConfigurationSpace.FeasibilityPolicy(ConfigurationSpaceTest.VERSION, ConfigurationSpace.Limits.conservative(), fixture.evidence()));
        for (int mask = 0; mask < 8; mask++) {
            boolean dev = (mask & 1) != 0, cloud = (mask & 2) != 0, test = (mask & 4) != 0;
            var assignment = new ConfigurationAssignment(Map.of(new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE, "dev"), FiniteDomain.Value.bool(dev),
                    new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE, "cloud"), FiniteDomain.Value.bool(cloud),
                    new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE, "test"), FiniteDomain.Value.bool(test)), fixture.evidence());
            var evaluated = ExogenousConditionEvaluator.evaluate(ConditionModel.create(space, result.occurrences()), result.semantics(), assignment, ExogenousConditionEvaluator.Limits.conservative());
            assertEquals(dev && !cloud || test ? LogicalValue.TRUE : LogicalValue.FALSE, evaluated.rows().getFirst().truth(), "assignment " + mask);
        }
    }
    @Test void unsupportedLiteralFormsConflictsAndMalformedProfilesRemainOpaqueWithoutDroppingRows() throws Exception {
        for (var spec : List.of(new Spec(PROFILE, "@Profile(\"a & b | c\")"), new Spec(PROFILE, "@Profile(\"((a)\")"),
                new Spec(PROFILE, "@Profile(Names.DEV)"), new Spec(PROFILE, "@Profile({})"),
                new Spec(PROPERTY, "@ConditionalOnProperty(name=\"a\", value=\"b\")"), new Spec(PROPERTY, "@ConditionalOnProperty(name=compute())"))) {
            var result = lower(fixture(List.of(spec)));
            assertEquals(1, result.coverage().opaqueRows(), spec.spelling()); assertEquals(1, result.capabilityGaps().size());
        }
    }
    @Test void projectImpostorsUnknownAnnotationsCustomConditionsAndBeanStateNeverBecomeExogenousTruth() throws Exception {
        var fixture = fixture(List.of(new Spec(PROFILE, "@Profile(\"dev\")", true), new Spec("example.Custom", "@Custom"),
                new Spec("org.springframework.context.annotation.Conditional", "@Conditional(Custom.class)"),
                new Spec("org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean", "@ConditionalOnMissingBean")));
        var result = lower(fixture);
        assertEquals(4, result.coverage().opaqueRows()); assertEquals(0, result.coverage().loweredRows());
        assertEquals(4, result.occurrences().size()); assertFalse(result.capabilityGaps().isEmpty());
    }
    @Test void supportedVersionLabelsRequireExactArtifactHashesAndOneMatchingM3Context() throws Exception {
        for (var pair : List.of(List.of("5.3.31", "2.7.18"), List.of("6.1.14", "3.3.5"), List.of("6.2.0", "3.4.0"))) {
            var pins = pins(pair.getFirst(), pair.getLast());
            assertEquals(1, lower(fixture(List.of(new Spec(PROFILE, "@Profile(\"dev\")")), pins)).coverage().loweredRows());
            var changed = new ArrayList<>(pins); var original = changed.getFirst();
            changed.set(0, new SpringFrameworkEvidence.Artifact(original.coordinate(), original.classpathLogicalName(), ContentDigest.sha256Utf8("different-bytes")));
            assertEquals(1, lower(fixture(List.of(new Spec(PROFILE, "@Profile(\"dev\")")), changed)).coverage().opaqueRows());
        }
        var fixture = fixture(List.of(new Spec(PROFILE, "@Profile(\"dev\")")));
        var wrong = ConditionEvidenceLowering.lower(ConfigurationSpaceTest.BUILD, fixture.inventory(), List.of(), ConditionEvidenceLowering.Limits.conservative());
        assertEquals(1, wrong.coverage().opaqueRows());
        assertTrue(wrong.capabilityGaps().stream().anyMatch(g -> g.reasonCode().equals("BUILD_CONTEXT_MISMATCH")));
    }
    @Test void wholeAnnotationBuildObservationsRetainAllProofsAndConflictsRatherThanGuessingPresence() throws Exception {
        var fixture = fixture(List.of(new Spec("org.springframework.boot.autoconfigure.condition.ConditionalOnClass", "@ConditionalOnClass(name=\"missing.Type\")")));
        assertEquals(1, lower(fixture).coverage().opaqueRows());
        var raw = fixture.inventory().rawObservations().getFirst().identity();
        var falseProof = new ConditionEvidenceLowering.BuildObservation(raw, ConditionExpression.BuildPredicate.CLASS_PRESENT, fixture.build().identity(), LogicalValue.FALSE, fixture.evidence());
        var result = ConditionEvidenceLowering.lower(fixture.build(), fixture.inventory(), List.of(falseProof), ConditionEvidenceLowering.Limits.conservative());
        assertEquals(1, result.coverage().loweredRows());
        assertEquals(LogicalValue.FALSE, ((ConditionExpression.Build) result.occurrences().getFirst().expression().operand()).observedValue());
        var trueProof = new ConditionEvidenceLowering.BuildObservation(raw, ConditionExpression.BuildPredicate.CLASS_PRESENT, fixture.build().identity(), LogicalValue.TRUE, fixture.evidence());
        result = ConditionEvidenceLowering.lower(fixture.build(), fixture.inventory(), List.of(falseProof, trueProof), ConditionEvidenceLowering.Limits.conservative());
        assertEquals(1, result.coverage().opaqueRows()); assertEquals("BUILD_EVIDENCE_CONFLICT", result.capabilityGaps().getFirst().reasonCode());
    }
    @Test void loweringBudgetRetainsEveryOriginalObligationAndIdentityBindsTheLimit() throws Exception {
        var fixture = fixture(List.of(new Spec(PROFILE, "@Profile(\"dev\")"), new Spec(PROFILE, "@Profile(\"prod\")")));
        var limited = ConditionEvidenceLowering.lower(fixture.build(), fixture.inventory(), List.of(), new ConditionEvidenceLowering.Limits(1, 100, 10));
        assertEquals(new ConditionEvidenceLowering.Coverage(2, 1, 1, 0), limited.coverage());
        assertNotEquals(lower(fixture).inputIdentity(), limited.inputIdentity());
        var deep = fixture(List.of(new Spec(PROFILE, "@Profile(\"" + "(".repeat(1000) + "dev" + ")".repeat(1000) + "\")")));
        assertEquals(1, lower(deep).coverage().opaqueRows());
    }
}
