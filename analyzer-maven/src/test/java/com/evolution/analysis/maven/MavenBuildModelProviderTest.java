package com.evolution.analysis.maven;

import static org.junit.jupiter.api.Assertions.*;

import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.buildmodel.BuildModelResult.*;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.RepositorySnapshot;
import com.evolution.analysis.contract.source.SnapshotFile;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class MavenBuildModelProviderTest {
    private static final RepositoryIdentity REPOSITORY =
            RepositoryIdentity.fromCanonicalCoordinate("https://example.test/reactor.git");
    private static final BuildModelPolicy POLICY = new BuildModelPolicy(List.of(), List.of(), Map.of(), 100_000, 100, 16);
    private final BuildModelProvider provider = new MavenBuildModelProvider();

    @Test
    void effectiveReactorPreservesRootInheritanceBomPrecedenceScopesAndDeclarationOrder() {
        var root = pom("root", """
                <packaging>pom</packaging><modules><module>api</module><module>app</module></modules>
                <properties><lib.version>2.0</lib.version><maven.compiler.release>17</maven.compiler.release></properties>
                <dependencyManagement><dependencies>
                  <dependency><groupId>demo</groupId><artifactId>platform</artifactId><version>1</version><type>pom</type><scope>import</scope></dependency>
                  <dependency><groupId>demo</groupId><artifactId>lib</artifactId><version>${lib.version}</version></dependency>
                </dependencies></dependencyManagement>
                """);
        var api = child("api", "<dependencies>" + dependency("demo", "lib", "", "provided") + "</dependencies>");
        var app = child("app", """
                <dependencies>
                  <dependency><groupId>demo</groupId><artifactId>api</artifactId><version>${project.version}</version></dependency>
                  <dependency><groupId>demo</groupId><artifactId>util</artifactId><scope>test</scope><optional>true</optional>
                    <exclusions><exclusion><groupId>demo</groupId><artifactId>excluded</artifactId></exclusion></exclusions>
                  </dependency>
                </dependencies>
                """);
        var bom = pom("platform", "<packaging>pom</packaging><dependencyManagement><dependencies>"
                + dependency("demo", "lib", "1.0", "compile") + dependency("demo", "util", "3.0", "compile")
                + "</dependencies></dependencyManagement>");
        var result = provider.build(request(Map.of("pom.xml", root, "api/pom.xml", api, "app/pom.xml", app),
                Map.of(new MavenCoordinate("demo", "platform", "1"), bom), POLICY));

        assertFalse(result.hasGaps(), () -> result.problems().toString());
        assertEquals(List.of("api/pom.xml", "app/pom.xml", "pom.xml"), result.modules().stream().map(ModuleModel::pomPath).toList());
        assertEquals(".", module(result, "pom.xml").module().path());
        assertEquals(Optional.of("pom.xml"), module(result, "api/pom.xml").aggregatorPom());
        assertEquals("17", effective(result, "app/pom.xml").properties().get("maven.compiler.release"));
        assertEquals(List.of(new Dependency("demo", "lib", "2.0", "jar", "", "provided", false, List.of())),
                effective(result, "api/pom.xml").dependencies());
        assertEquals(List.of(
                new Dependency("demo", "api", "1", "jar", "", "compile", false, List.of()),
                new Dependency("demo", "util", "3.0", "jar", "", "test", true, List.of("demo:excluded"))),
                effective(result, "app/pom.xml").dependencies());
        assertTrue(effective(result, "app/pom.xml").inputs().stream().anyMatch(e -> e.logicalId().equals("artifact:demo:platform:1")));
        assertTrue(result.attempts().stream().anyMatch(a -> a.kind() == AttemptKind.IMPORT_BOM && a.outcome() == Outcome.SUCCEEDED));
    }

    @Test
    void missingParentBomAndModuleRemainExplicitAndDoNotEraseHealthySiblings() {
        var root = pom("root", "<packaging>pom</packaging><modules><module>ok</module><module>bad</module><module>absent</module><module>bom</module></modules>");
        var bad = "<project><modelVersion>4.0.0</modelVersion><parent><groupId>missing</groupId><artifactId>parent</artifactId><version>1</version><relativePath/></parent><artifactId>bad</artifactId></project>";
        var bom = pom("bom", "<dependencyManagement><dependencies>"
                + "<dependency><groupId>missing</groupId><artifactId>bom</artifactId><version>1</version><type>pom</type><scope>import</scope></dependency>"
                + "</dependencies></dependencyManagement>");
        var result = provider.build(request(Map.of("pom.xml", root, "ok/pom.xml", child("ok", ""),
                "bad/pom.xml", bad, "bom/pom.xml", bom), Map.of(), POLICY));
        assertEquals(5, result.modules().size());
        assertTrue(module(result, "ok/pom.xml").effectivePom().isPresent());
        for (String path : List.of("bad/pom.xml", "absent/pom.xml", "bom/pom.xml")) {
            assertTrue(module(result, path).effectivePom().isEmpty(), path);
        }
        assertTrue(reasons(result).containsAll(List.of(Reason.MISSING_PARENT_POM, Reason.MISSING_IMPORT_BOM, Reason.MISSING_MODULE_POM)));
    }

    @Test
    void explicitProfilesAndPropertyActivationUseOnlyTheSuppliedContext() {
        String root = pom("root", """
                <packaging>pom</packaging><profiles>
                <profile><id>default</id><activation><activeByDefault>true</activeByDefault></activation><properties><choice>default</choice></properties></profile>
                <profile><id>selected</id><properties><choice>selected</choice></properties><modules><module>child</module></modules></profile>
                <profile><id>property</id><activation><property><name>build.flag</name><value>yes</value></property></activation><properties><flag>present</flag></properties></profile>
                </profiles>
                """);
        var poms = Map.of("pom.xml", root, "child/pom.xml", child("child", ""));
        var defaults = provider.build(request(poms, Map.of(), POLICY));
        assertEquals("default", effective(defaults, "pom.xml").properties().get("choice"));
        var selected = provider.build(request(poms, Map.of(), new BuildModelPolicy(
                List.of("selected"), List.of(), Map.of("build.flag", "yes"), 100_000, 100, 16)));
        assertEquals(2, selected.modules().size());
        assertEquals("selected", effective(selected, "pom.xml").properties().get("choice"));
        assertEquals("present", effective(selected, "pom.xml").properties().get("flag"));
        assertEquals(List.of("property", "selected"), effective(selected, "pom.xml").activeProfiles());
    }

    @Test
    void ambientPropertiesNeverChangeModelOrLeakIntoDiagnostics() {
        String key = "m3.test.ambient";
        String before = System.getProperty(key);
        try {
            var request = request(Map.of("pom.xml", pom("root", "<properties><leak>${m3.test.ambient}</leak></properties>")), Map.of(), POLICY);
            System.setProperty(key, "first-private-value");
            var first = provider.build(request);
            System.setProperty(key, "second-private-value");
            var second = provider.build(request);
            assertEquals(CanonicalJson.write(first), CanonicalJson.write(second));
            assertFalse(CanonicalJson.write(first).contains("private-value"));
            assertTrue(reasons(first).contains(Reason.UNRESOLVED_EXPRESSION));
        } finally {
            if (before == null) System.clearProperty(key); else System.setProperty(key, before);
        }
    }

    @Test
    void hostDependentProfileActivationIsWithheldUnlessExplicitlyDecided() {
        for (String activation : List.of("<jdk>[1,99)</jdk>", "<os><family>windows</family></os>",
                "<file><exists>pom.xml</exists></file>")) {
            String root = pom("root", "<profiles><profile><id>host</id><activation>" + activation
                    + "</activation><properties><selected>true</selected></properties></profile></profiles>");
            var result = provider.build(request(Map.of("pom.xml", root), Map.of(), POLICY));
            assertTrue(reasons(result).contains(Reason.UNSUPPORTED_ACTIVATION));
            assertTrue(module(result, "pom.xml").effectivePom().isEmpty());
            var explicit = provider.build(request(Map.of("pom.xml", root), Map.of(),
                    new BuildModelPolicy(List.of("host"), List.of(), Map.of(), 100_000, 100, 16)));
            assertEquals("true", effective(explicit, "pom.xml").properties().get("selected"));
        }
    }

    @Test
    void xxeDoctypesAndMalformedXmlAreRejectedWithSanitizedProblems() {
        for (String xml : List.of(
                "<!DOCTYPE project [<!ENTITY xxe SYSTEM 'file:///private-marker'>]><project>&xxe;</project>",
                "<!DOCTYPE project [<!ENTITY x 'expanded'>]><project>&x;</project>")) {
            var result = provider.build(request(Map.of("pom.xml", xml), Map.of(), POLICY));
            assertTrue(reasons(result).contains(Reason.UNSAFE_XML));
            assertFalse(CanonicalJson.write(result).contains("private-marker"));
            assertTrue(module(result, "pom.xml").effectivePom().isEmpty());
        }
        var malformed = provider.build(request(Map.of("pom.xml", "<project><unclosed>"), Map.of(), POLICY));
        assertTrue(reasons(malformed).contains(Reason.INVALID_XML));
    }

    @Test
    void traversalCyclesDuplicatesAndForeignCoordinatesCannotBecomeValidModules() {
        var root = pom("root", "<packaging>pom</packaging><modules><module>../escape</module><module>child</module><module>child</module></modules>");
        var child = pom("child", "<packaging>pom</packaging><modules><module>..</module></modules>");
        var result = provider.build(request(Map.of("pom.xml", root, "child/pom.xml", child), Map.of(), POLICY));
        assertTrue(reasons(result).containsAll(List.of(Reason.PATH_OUTSIDE_WORKSPACE, Reason.DUPLICATE_MODULE, Reason.MODULE_CYCLE)));
        assertEquals(2, result.modules().size());
    }

    @Test
    void relativeParentMustMatchCoordinatesAndExplicitEmptyRelativePathDisablesLookup() {
        String root = pom("root", "<packaging>pom</packaging><modules><module>child</module></modules>");
        String child = "<project><modelVersion>4.0.0</modelVersion><parent><groupId>demo</groupId><artifactId>other</artifactId><version>1</version></parent><artifactId>child</artifactId></project>";
        var result = provider.build(request(Map.of("pom.xml", root, "child/pom.xml", child), Map.of(), POLICY));
        assertTrue(module(result, "child/pom.xml").effectivePom().isEmpty());
        assertTrue(reasons(result).contains(Reason.MISSING_PARENT_POM));
        String noRelative = child("child", "").replace("</parent>", "<relativePath/></parent>");
        var supplied = provider.build(request(Map.of("pom.xml", root, "child/pom.xml", noRelative),
                Map.of(new MavenCoordinate("demo", "root", "1"), root), POLICY));
        assertTrue(module(supplied, "child/pom.xml").effectivePom().isPresent());
    }

    @Test
    void pluginsExtensionsAndRepositoryUrlsAreDataAndNeverExecutedOrContacted() {
        String root = pom("root", """
                <build><extensions><extension><groupId>never</groupId><artifactId>execute</artifactId><version>1</version></extension></extensions>
                <plugins><plugin><groupId>never</groupId><artifactId>execute</artifactId><version>1</version><extensions>true</extensions></plugin></plugins></build>
                <repositories><repository><id>untrusted</id><url>http://127.0.0.1:1/never</url></repository></repositories>
                """);
        var result = provider.build(request(Map.of("pom.xml", root), Map.of(), POLICY));
        assertEquals(new MavenCoordinate("demo", "root", "1"), effective(result, "pom.xml").coordinate());
        assertTrue(reasons(result).contains(Reason.EXTENSIONS_NOT_LOADED));
    }

    @Test
    void requestBindsAllBytesAndPolicyAndIgnoresMapInsertionOrder() {
        var original = request(Map.of("pom.xml", pom("root", "")), Map.of(), POLICY);
        var again = request(new TreeMap<>(Map.of("pom.xml", pom("root", ""))), Map.of(), POLICY);
        assertEquals(original.identity(), again.identity());
        assertEquals(CanonicalJson.write(provider.build(original)), CanonicalJson.write(provider.build(again)));
        byte[] returned = original.workspacePoms().get("pom.xml").bytes();
        returned[0] = 'X';
        assertEquals(original.identity(), again.identity());
        assertThrows(IllegalArgumentException.class, () -> new BuildModelRequest(original.snapshot(), "pom.xml",
                Map.of("pom.xml", new PomInput("different".getBytes(StandardCharsets.UTF_8))), Map.of(), POLICY));
        var changed = request(Map.of("pom.xml", pom("root", "")),
                Map.of(new MavenCoordinate("demo", "unused", "1"), pom("unused", "")), POLICY);
        assertNotEquals(original.identity(), changed.identity());
    }

    @Test
    void inputAndModelReadLimitsAreExplicitFailuresRatherThanSuccessfulEmptyModels() {
        var limited = provider.build(request(Map.of("pom.xml", pom("root", "")), Map.of(),
                new BuildModelPolicy(List.of(), List.of(), Map.of(), 20, 100, 16)));
        assertTrue(reasons(limited).contains(Reason.INPUT_LIMIT));
        assertTrue(module(limited, "pom.xml").effectivePom().isEmpty());
        String root = pom("root", "<packaging>pom</packaging><modules><module>child</module></modules>");
        var depth = provider.build(request(Map.of("pom.xml", root, "child/pom.xml", child("child", "")),
                Map.of(), new BuildModelPolicy(List.of(), List.of(), Map.of(), 100_000, 100, 1)));
        assertTrue(reasons(depth).contains(Reason.MODEL_READ_LIMIT));
    }

    @Test
    void importedBomProfilesCannotSilentlyIgnoreAnExplicitActivationDecision() {
        var root = pom("root", "<dependencyManagement><dependencies>"
                + "<dependency><groupId>demo</groupId><artifactId>platform</artifactId><version>1</version><type>pom</type><scope>import</scope></dependency>"
                + "</dependencies></dependencyManagement><dependencies>" + dependency("demo", "lib", "", "compile") + "</dependencies>");
        var bom = pom("platform", "<packaging>pom</packaging><profiles><profile><id>selected</id>"
                + "<activation><jdk>[1,99)</jdk></activation><dependencyManagement><dependencies>"
                + dependency("demo", "lib", "7.0", "compile")
                + "</dependencies></dependencyManagement></profile></profiles>");
        var result = provider.build(request(Map.of("pom.xml", root), Map.of(new MavenCoordinate("demo", "platform", "1"), bom),
                new BuildModelPolicy(List.of("selected"), List.of(), Map.of(), 100_000, 100, 16)));
        assertEquals("7.0", effective(result, "pom.xml").dependencies().getFirst().version());
    }

    @Test
    void artifactCoordinatesAreVerifiedAndNestedBomInputsRemainTraceable() {
        String root = pom("root", "<dependencyManagement><dependencies>"
                + "<dependency><groupId>demo</groupId><artifactId>platform</artifactId><version>1</version><type>pom</type><scope>import</scope></dependency>"
                + "</dependencies></dependencyManagement>");
        var mismatch = provider.build(request(Map.of("pom.xml", root),
                Map.of(new MavenCoordinate("demo", "platform", "1"), pom("impostor", "<packaging>pom</packaging>")), POLICY));
        assertTrue(reasons(mismatch).contains(Reason.COORDINATE_MISMATCH));
        assertTrue(module(mismatch, "pom.xml").effectivePom().isEmpty());
        String nested = root.replace("<artifactId>root</artifactId>", "<artifactId>platform</artifactId>")
                .replace("<artifactId>platform</artifactId><version>1</version><type>pom</type>", "<artifactId>nested</artifactId><version>1</version><type>pom</type>");
        var result = provider.build(request(Map.of("pom.xml", root), Map.of(
                new MavenCoordinate("demo", "platform", "1"), nested,
                new MavenCoordinate("demo", "nested", "1"), pom("nested", "<packaging>pom</packaging>")), POLICY));
        assertEquals(3, effective(result, "pom.xml").inputs().size());
    }

    @Test
    void missingRelativeParentCanBeSatisfiedByTheSuppliedArtifactWithoutAFalseMissingGap() {
        String child = "<project><modelVersion>4.0.0</modelVersion><parent><groupId>demo</groupId><artifactId>parent</artifactId><version>1</version><relativePath>missing.xml</relativePath></parent><artifactId>child</artifactId></project>";
        var result = provider.build(request(Map.of("pom.xml", child),
                Map.of(new MavenCoordinate("demo", "parent", "1"), pom("parent", "<packaging>pom</packaging>")), POLICY));
        assertFalse(reasons(result).contains(Reason.MISSING_PARENT_POM));
        assertTrue(module(result, "pom.xml").effectivePom().isPresent());
        assertTrue(result.attempts().stream().anyMatch(a -> a.outcome() == Outcome.UNAVAILABLE));
    }

    @Test
    void unresolvedDependencyVersionsWithholdTheModelAndRetainTheOriginalEvidence() {
        var request = request(Map.of("pom.xml", pom("root", "<dependencies>"
                + dependency("demo", "lib", "${not.supplied}", "compile") + "</dependencies>")), Map.of(), POLICY);
        var result = provider.build(request);
        assertTrue(reasons(result).contains(Reason.UNRESOLVED_EXPRESSION), () -> result.toString());
        assertTrue(module(result, "pom.xml").effectivePom().isEmpty());
        assertTrue(result.problems().stream().flatMap(p -> p.evidence().stream())
                .anyMatch(e -> e.digest().equals(request.workspacePoms().get("pom.xml").digest())));
    }

    @Test
    void repeatedRunsIgnoreLocaleTimezoneAndMapOrderButTrackChangedModelInputs() {
        var locale = Locale.getDefault();
        var timezone = TimeZone.getDefault();
        try {
            String root = pom("root", "<packaging>pom</packaging><modules><module>child</module></modules>");
            var forward = new LinkedHashMap<String, String>();
            forward.put("pom.xml", root);
            forward.put("child/pom.xml", child("child", ""));
            var reverse = new LinkedHashMap<String, String>();
            reverse.put("child/pom.xml", child("child", ""));
            reverse.put("pom.xml", root);
            var first = provider.build(request(forward, Map.of(), POLICY));
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
            var second = provider.build(request(reverse, Map.of(), POLICY));
            assertEquals(CanonicalJson.write(first), CanonicalJson.write(second));
            assertEquals(first.identity(), second.identity());
            var changed = provider.build(request(forward, Map.of(), new BuildModelPolicy(List.of(), List.of(), Map.of("revision", "2"), 100_000, 100, 16)));
            assertNotEquals(first.requestIdentity(), changed.requestIdentity());
        } finally {
            Locale.setDefault(locale);
            TimeZone.setDefault(timezone);
        }
    }

    @Test
    void bomAndParentCyclesAreFailuresAndRetainTheirAttemptHistory() {
        String selfBom = pom("platform", "<packaging>pom</packaging><dependencyManagement><dependencies>"
                + "<dependency><groupId>demo</groupId><artifactId>platform</artifactId><version>1</version><type>pom</type><scope>import</scope></dependency>"
                + "</dependencies></dependencyManagement>");
        var cycle = provider.build(request(Map.of("pom.xml", selfBom),
                Map.of(new MavenCoordinate("demo", "platform", "1"), selfBom), POLICY));
        assertTrue(module(cycle, "pom.xml").effectivePom().isEmpty());
        assertTrue(reasons(cycle).contains(Reason.INVALID_MODEL));
        String parent = child("root", "<packaging>pom</packaging>");
        var parentCycle = provider.build(request(Map.of("pom.xml", parent),
                Map.of(new MavenCoordinate("demo", "root", "1"), parent), POLICY));
        assertTrue(module(parentCycle, "pom.xml").effectivePom().isEmpty());
        assertFalse(parentCycle.attempts().isEmpty());
    }

    @Test
    void siblingAggregatorsNormalizePortablePathsAndModuleIdsCannotCollide() {
        String root = pom("root", "<packaging>pom</packaging><modules><module>nested\\aggregator</module><module>same/a.xml</module><module>same/b.xml</module></modules>");
        String nested = pom("nested", "<packaging>pom</packaging><modules><module>../leaf</module></modules>");
        var result = provider.build(request(Map.of("pom.xml", root,
                "nested/aggregator/pom.xml", nested, "nested/leaf/pom.xml", pom("leaf", ""),
                "same/a.xml", pom("first", ""), "same/b.xml", pom("second", "")), Map.of(), POLICY));
        assertTrue(module(result, "nested/leaf/pom.xml").effectivePom().isPresent());
        assertTrue(reasons(result).contains(Reason.DUPLICATE_MODULE));
        assertEquals(result.modules().size(), result.modules().stream().map(m -> m.module().identity()).distinct().count());
    }

    @Test
    void activeProfileExtensionsRemainExplicitlyUnevaluatedAndInactiveProfilesStayInactive() {
        String root = pom("root", "<profiles><profile><id>extension</id><build><plugins><plugin>"
                + "<groupId>untrusted</groupId><artifactId>extension</artifactId><version>1</version><extensions>true</extensions>"
                + "</plugin></plugins></build></profile></profiles>");
        var inactive = provider.build(request(Map.of("pom.xml", root), Map.of(), POLICY));
        assertFalse(reasons(inactive).contains(Reason.EXTENSIONS_NOT_LOADED));
        var active = provider.build(request(Map.of("pom.xml", root), Map.of(),
                new BuildModelPolicy(List.of("extension"), List.of(), Map.of(), 100_000, 100, 16)));
        assertTrue(reasons(active).contains(Reason.EXTENSIONS_NOT_LOADED));
    }

    @Test
    void utf16DoctypesAndOverdeepXmlCannotBypassTheByteLevelSecurityBoundary() {
        String xml = "<?xml version='1.0' encoding='UTF-16'?><!DOCTYPE project [<!ENTITY x 'expansion'>]><project>&x;</project>";
        var original = request(Map.of("pom.xml", pom("root", "")), Map.of(), POLICY);
        var input = new PomInput(xml.getBytes(StandardCharsets.UTF_16));
        var snapshot = RepositorySnapshot.create(REPOSITORY, Optional.empty(), false,
                List.of(new SnapshotFile("pom.xml", input.digest())), List.of());
        var result = provider.build(new BuildModelRequest(snapshot, "pom.xml", Map.of("pom.xml", input), Map.of(), POLICY));
        assertTrue(reasons(result).contains(Reason.UNSAFE_XML));
        String deep = "<project>" + "<nested>".repeat(70) + "</nested>".repeat(70) + "</project>";
        var overdeep = provider.build(request(Map.of("pom.xml", deep), Map.of(), POLICY));
        assertTrue(reasons(overdeep).contains(Reason.INPUT_LIMIT));
        assertNotEquals(original.identity(), result.requestIdentity());
    }

    static ModuleModel module(BuildModelResult result, String path) {
        return result.modules().stream().filter(m -> m.pomPath().equals(path)).findFirst().orElseThrow();
    }
    static EffectivePom effective(BuildModelResult result, String path) {
        return module(result, path).effectivePom().orElseThrow(() -> new AssertionError(result.problems()));
    }
    static List<Reason> reasons(BuildModelResult result) { return result.problems().stream().map(Problem::reason).toList(); }
    static String pom(String artifact, String body) {
        return "<project xmlns='http://maven.apache.org/POM/4.0.0'><modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>"
                + artifact + "</artifactId><version>1</version>" + body + "</project>";
    }
    static String child(String artifact, String body) {
        return "<project><modelVersion>4.0.0</modelVersion><parent><groupId>demo</groupId><artifactId>root</artifactId><version>1</version></parent><artifactId>"
                + artifact + "</artifactId>" + body + "</project>";
    }
    static String dependency(String group, String artifact, String version, String scope) {
        return "<dependency><groupId>" + group + "</groupId><artifactId>" + artifact + "</artifactId>"
                + (version.isEmpty() ? "" : "<version>" + version + "</version>") + "<scope>" + scope + "</scope></dependency>";
    }
    static BuildModelRequest request(Map<String, String> poms, Map<MavenCoordinate, String> artifacts, BuildModelPolicy policy) {
        var inputs = new TreeMap<String, PomInput>();
        poms.forEach((key, value) -> inputs.put(key, new PomInput(value.getBytes(StandardCharsets.UTF_8))));
        var external = new HashMap<MavenCoordinate, PomInput>();
        artifacts.forEach((key, value) -> external.put(key, new PomInput(value.getBytes(StandardCharsets.UTF_8))));
        var files = inputs.entrySet().stream().map(e -> new SnapshotFile(e.getKey(), e.getValue().digest())).toList();
        var snapshot = RepositorySnapshot.create(REPOSITORY, Optional.empty(), false, files, List.of());
        return new BuildModelRequest(snapshot, "pom.xml", inputs, external, policy);
    }
}
