package com.evolution.analysis.maven;

import static org.junit.jupiter.api.Assertions.*;

import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.classpath.*;
import com.evolution.analysis.classpath.ExactClasspathResult.*;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.RepositorySnapshot;
import com.evolution.analysis.contract.source.SnapshotFile;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenLocalClasspathProviderTest {
    private static final RepositoryIdentity REPOSITORY = RepositoryIdentity.fromCanonicalCoordinate(
            "https://example.test/m3-classpath.git");
    private static final BuildModelPolicy MODEL_POLICY =
            new BuildModelPolicy(List.of(), List.of(), Map.of(), 100_000, 100, 32);
    private static final ClasspathResolutionPolicy POLICY =
            new ClasspathResolutionPolicy(100, 300, 100_000, 1_000_000, 10_000_000, 20);

    @TempDir Path temporary;

    @Test
    void resolvesIsolatedMainAndTestPlansWithManagedScopesAndReactorModules() throws Exception {
        String root = pom("root", """
                <packaging>pom</packaging><modules><module>api</module><module>app</module></modules>
                <dependencyManagement><dependencies>
                  <dependency><groupId>ext</groupId><artifactId>shared</artifactId><version>3</version></dependency>
                </dependencies></dependencyManagement>
                """);
        String api = child("api", "<dependencies>"
                + dependency("ext", "api-lib", "1", "compile", false, "")
                + dependency("ext", "provided", "1", "provided", false, "")
                + "</dependencies>");
        String app = child("app", "<dependencies>"
                + dependency("demo", "api", "1", "compile", false, "")
                + dependency("ext", "app-runtime", "1", "runtime", false, "")
                + dependency("ext", "app-test", "1", "test", false, "")
                + "<dependency><groupId>ext</groupId><artifactId>fixtures</artifactId><version>1</version>"
                + "<type>test-jar</type><scope>test</scope></dependency>"
                + "<dependency><groupId>demo</groupId><artifactId>root</artifactId><version>1</version>"
                + "<type>pom</type></dependency>"
                + "</dependencies>");
        var fixture = build(Map.of("pom.xml", root, "api/pom.xml", api, "app/pom.xml", app));

        artifact("ext", "api-lib", "1", "<dependencies>"
                + dependency("ext", "shared", "1", "compile", false, "")
                + dependency("ext", "runtime-child", "1", "runtime", false, "")
                + dependency("ext", "provided-child", "1", "provided", false, "")
                + "</dependencies>", true);
        for (String name : List.of("shared", "runtime-child", "provided-child", "provided", "app-runtime", "app-test")) {
            artifact("ext", name, name.equals("shared") ? "3" : "1", "", true);
        }
        artifact("ext", "fixtures", "1", "", false);
        writeJar("ext", "fixtures", "1", "jar", "tests", jar("fixtures"));

        var result = resolve(fixture, POLICY, cacheRoot());
        var apiMain = manifest(result, "api", SourcePlanModel.Kind.MAIN);
        var apiTest = manifest(result, "api", SourcePlanModel.Kind.TEST);
        var appMain = manifest(result, "app", SourcePlanModel.Kind.MAIN);
        var appTest = manifest(result, "app", SourcePlanModel.Kind.TEST);

        assertEquals(List.of("ext:api-lib:1@jar", "ext:shared:3@jar", "ext:provided:1@jar"), names(apiMain));
        assertEquals(List.of("ext:api-lib:1@jar", "ext:shared:3@jar", "ext:runtime-child:1@jar",
                "ext:provided:1@jar"), names(apiTest));
        assertEquals(List.of("ext:api-lib:1@jar", "ext:shared:3@jar"), names(appMain));
        assertEquals(List.of("ext:api-lib:1@jar", "ext:shared:3@jar", "ext:runtime-child:1@jar",
                "ext:app-runtime:1@jar", "ext:app-test:1@jar", "ext:fixtures:1:tests@jar"), names(appTest));
        assertEquals(1, appMain.reactorEntries().size());
        assertEquals(com.evolution.analysis.contract.identity.ModuleIdentity.from(REPOSITORY, "api"),
                appMain.reactorEntries().getFirst().module());
        assertEquals(Status.PARTIAL, appMain.status(), "unacquired reactor output must stay explicit");
        assertTrue(appMain.problems().stream().anyMatch(problem -> problem.reason() == Reason.REACTOR_OUTPUT_NOT_ACQUIRED));
        assertFalse(names(apiTest).stream().anyMatch(name -> name.contains("app-")), "dependencies must not bleed across modules");
        assertFalse(result.attempts().stream().anyMatch(attempt -> attempt.subject().contains("demo/api")),
                "reactor dependencies must not read a cache copy");
    }

    @Test
    void nearestThenFirstDeclarationWinsAndEveryConflictRemainsExplainable() throws Exception {
        String root = pom("root", "<packaging>pom</packaging><modules><module>nearest</module><module>tie</module></modules>");
        String nearest = child("nearest", "<dependencies>"
                + dependency("ext", "left", "1", "compile", false, "")
                + dependency("ext", "right", "1", "compile", false, "")
                + dependency("ext", "conflict", "3", "compile", false, "") + "</dependencies>");
        String tie = child("tie", "<dependencies>"
                + dependency("ext", "left", "1", "compile", false, "")
                + dependency("ext", "right", "1", "compile", false, "") + "</dependencies>");
        var fixture = build(Map.of("pom.xml", root, "nearest/pom.xml", nearest, "tie/pom.xml", tie));
        artifact("ext", "left", "1", "<dependencies>"
                + dependency("ext", "conflict", "1", "compile", false, "")
                + dependency("ext", "scope-shared", "1", "runtime", false, "") + "</dependencies>", true);
        artifact("ext", "right", "1", "<dependencies>"
                + dependency("ext", "conflict", "2", "compile", false, "")
                + dependency("ext", "scope-shared", "1", "compile", false, "") + "</dependencies>", true);
        for (String version : List.of("1", "2", "3")) artifact("ext", "conflict", version, "", true);
        artifact("ext", "scope-shared", "1", "<dependencies>"
                + dependency("ext", "scope-child", "1", "compile", false, "") + "</dependencies>", true);
        artifact("ext", "scope-child", "1", "", true);

        var result = resolve(fixture, POLICY, cacheRoot());
        var nearestMain = manifest(result, "nearest", SourcePlanModel.Kind.MAIN);
        var tieMain = manifest(result, "tie", SourcePlanModel.Kind.MAIN);

        assertTrue(names(nearestMain).contains("ext:conflict:3@jar"));
        assertFalse(names(nearestMain).stream().anyMatch(name -> name.matches("ext:conflict:[12]@jar")));
        assertTrue(nearestMain.decisions().stream().filter(decision -> decision.reason() == DecisionReason.NEAREST).count() >= 2);
        assertTrue(names(tieMain).contains("ext:conflict:1@jar"));
        assertFalse(names(tieMain).contains("ext:conflict:2@jar"));
        assertTrue(tieMain.decisions().stream().anyMatch(decision -> decision.reason() == DecisionReason.FIRST_DECLARATION));
        assertEquals(DependencyScope.COMPILE, entry(tieMain, "ext:scope-shared:1@jar").scope());
        assertEquals(DependencyScope.COMPILE, entry(tieMain, "ext:scope-child:1@jar").scope());
    }

    @Test
    void exclusionsAndTransitiveOptionalityDoNotHideDirectOptionalDependencies() throws Exception {
        String exclusions = "<exclusions><exclusion><groupId>ext</groupId><artifactId>blocked</artifactId></exclusion></exclusions>";
        var fixture = build(Map.of("pom.xml", pom("app", "<dependencies>"
                + dependency("ext", "gateway", "1", "compile", false, exclusions)
                + dependency("ext", "direct-optional", "1", "compile", true, "")
                + "</dependencies>")));
        artifact("ext", "gateway", "1", "<dependencies>"
                + dependency("ext", "blocked", "1", "compile", false, "")
                + dependency("ext", "hidden-optional", "1", "compile", true, "")
                + dependency("ext", "visible", "1", "compile", false, "") + "</dependencies>", true);
        artifact("ext", "direct-optional", "1", "<dependencies>"
                + dependency("ext", "optional-child", "1", "compile", false, "") + "</dependencies>", true);
        for (String name : List.of("blocked", "hidden-optional", "visible", "optional-child")) {
            artifact("ext", name, "1", "", true);
        }

        var main = manifest(resolve(fixture, POLICY, cacheRoot()), ".", SourcePlanModel.Kind.MAIN);
        assertEquals(List.of("ext:gateway:1@jar", "ext:visible:1@jar", "ext:direct-optional:1@jar",
                "ext:optional-child:1@jar"), names(main));
        assertFalse(names(main).stream().anyMatch(name -> name.contains("blocked") || name.contains("hidden-optional")));
    }

    @Test
    void duplicateReactorCoordinatesAreWithheldWithoutFallingBackToTheLocalCache() throws Exception {
        String root = pom("root", "<packaging>pom</packaging><modules>"
                + "<module>first</module><module>second</module><module>app</module></modules>");
        String duplicate = child("shared", "");
        String app = child("app", "<dependencies>"
                + dependency("demo", "shared", "1", "compile", false, "")
                + "</dependencies>");
        var fixture = build(
                Map.of("pom.xml", root, "first/pom.xml", duplicate, "second/pom.xml", duplicate, "app/pom.xml", app),
                Set.of(BuildModelResult.Reason.MODEL_WARNING, BuildModelResult.Reason.DUPLICATE_COORDINATE));
        Files.createDirectories(cacheRoot());

        var result = resolve(fixture, POLICY, cacheRoot());
        var main = manifest(result, "app", SourcePlanModel.Kind.MAIN);

        assertTrue(main.problems().stream()
                .anyMatch(problem -> problem.reason() == Reason.DUPLICATE_REACTOR_COORDINATE));
        assertFalse(main.problems().stream().anyMatch(problem -> problem.reason() == Reason.MISSING_POM));
        assertTrue(main.entries().isEmpty());
        assertTrue(result.attempts().stream().noneMatch(attempt -> attempt.subject().contains("demo/shared")));
    }

    @Test
    void externalParentsAndImportedBomsUseOnlySecureCacheModels() throws Exception {
        var fixture = build(Map.of("pom.xml", pom("app", "<dependencies>"
                + dependency("ext", "lib", "1", "compile", false, "") + "</dependencies>")));
        writePom("ext", "lib", "1", """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>ext</groupId><artifactId>parent</artifactId><version>1</version></parent>
                  <artifactId>lib</artifactId>
                  <repositories><repository><id>never</id><url>http://127.0.0.1:1/never</url></repository></repositories>
                  <dependencies><dependency><groupId>ext</groupId><artifactId>leaf</artifactId></dependency></dependencies>
                </project>
                """);
        writeJar("ext", "lib", "1", "jar", "", jar("lib"));
        artifact("ext", "parent", "1", "<packaging>pom</packaging><dependencyManagement><dependencies>"
                + "<dependency><groupId>ext</groupId><artifactId>bom</artifactId><version>1</version>"
                + "<type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement>", false);
        artifact("ext", "bom", "1", "<packaging>pom</packaging><dependencyManagement><dependencies>"
                + dependency("ext", "leaf", "4", "compile", false, "")
                + "</dependencies></dependencyManagement>", false);
        artifact("ext", "leaf", "4", "", true);

        var result = resolve(fixture, POLICY, cacheRoot());
        var main = manifest(result, ".", SourcePlanModel.Kind.MAIN);
        assertEquals(List.of("ext:lib:1@jar", "ext:leaf:4@jar"), names(main));
        assertEquals(Status.COMPLETE, main.status(), () -> main.problems().toString());
        assertTrue(result.artifacts().stream().anyMatch(artifact ->
                artifact.coordinate().notation().equals("ext:parent:1@pom")));
        assertTrue(result.artifacts().stream().anyMatch(artifact ->
                artifact.coordinate().notation().equals("ext:bom:1@pom")));
    }

    @Test
    void missingUnsafeUnsupportedAndSystemInputsRemainTypedWithoutLeakingHostDetails() throws Exception {
        String system = "<dependency><groupId>ext</groupId><artifactId>system</artifactId><version>1</version>"
                + "<scope>system</scope><systemPath>C:\\private-marker\\system.jar</systemPath></dependency>";
        String war = "<dependency><groupId>ext</groupId><artifactId>web</artifactId><version>1</version><type>war</type></dependency>";
        var fixture = build(Map.of("pom.xml", pom("app", "<dependencies>"
                + dependency("ext", "missing", "1", "compile", false, "")
                + dependency("ext", "jarless", "1", "compile", false, "")
                + dependency("ext", "unsafe", "1", "compile", false, "")
                + dependency("ext", "range", "[1,2]", "compile", false, "")
                + dependency("ext", "host-profile", "1", "compile", false, "")
                + dependency("ext", "bad-jar", "1", "compile", false, "")
                + dependency("ext", "mismatch", "1", "compile", false, "")
                + system + war + "</dependencies>")));
        artifact("ext", "jarless", "1", "", false);
        writePom("ext", "unsafe", "1", "<!DOCTYPE project [<!ENTITY x SYSTEM 'file:///private-marker'>]>"
                + pom("unsafe", "<name>&x;</name>"));
        writeJar("ext", "unsafe", "1", "jar", "", jar("unsafe"));
        artifact("ext", "host-profile", "1", "<profiles><profile><id>host</id>"
                + "<activation><jdk>[1,99)</jdk></activation></profile></profiles>", true);
        artifact("ext", "bad-jar", "1", "", false);
        writeJar("ext", "bad-jar", "1", "jar", "", "not-a-jar".getBytes(StandardCharsets.UTF_8));
        writePom("ext", "mismatch", "1", pomWithGroup("ext", "impostor", "1", ""));
        writeJar("ext", "mismatch", "1", "jar", "", jar("mismatch"));
        artifact("ext", "web", "1", "<packaging>war</packaging>", false);

        var result = resolve(fixture, POLICY, cacheRoot());
        var main = manifest(result, ".", SourcePlanModel.Kind.MAIN);
        var reasons = main.problems().stream().map(Problem::reason).collect(java.util.stream.Collectors.toSet());

        assertTrue(reasons.containsAll(Set.of(Reason.MISSING_POM, Reason.MISSING_ARTIFACT,
                Reason.UNSAFE_POM, Reason.NON_EXACT_VERSION, Reason.UNSUPPORTED_PROFILE_ACTIVATION,
                Reason.INVALID_JAR, Reason.COORDINATE_MISMATCH, Reason.SYSTEM_PATH_UNAVAILABLE,
                Reason.UNSUPPORTED_ARTIFACT_TYPE)), reasons::toString);
        assertEquals(Status.PARTIAL, main.status());
        assertFalse(CanonicalJson.write(result).contains("private-marker"));
    }

    @Test
    void everyArtifactResourceBoundaryIsFiniteAndReported() throws Exception {
        var fixture = build(Map.of("pom.xml", pom("app", "<dependencies>"
                + dependency("ext", "large", "1", "compile", false, "") + "</dependencies>")));
        artifact("ext", "large", "1", "<dependencies>"
                + dependency("ext", "child", "1", "compile", false, "") + "</dependencies>", true);
        artifact("ext", "child", "1", "", true);

        assertProblem(fixture, new ClasspathResolutionPolicy(1, 100, 100_000, 1_000_000, 10_000_000, 20),
                Reason.COORDINATE_LIMIT);
        assertProblem(fixture, new ClasspathResolutionPolicy(100, 1, 100_000, 1_000_000, 10_000_000, 20),
                Reason.FILE_COUNT_LIMIT);
        assertProblem(fixture, new ClasspathResolutionPolicy(100, 100, 10, 1_000_000, 10_000_000, 20),
                Reason.POM_BYTE_LIMIT);
        assertProblem(fixture, new ClasspathResolutionPolicy(100, 100, 100_000, 10, 10_000_000, 20),
                Reason.ARTIFACT_BYTE_LIMIT);
        assertProblem(fixture, new ClasspathResolutionPolicy(100, 100, 100_000, 1_000_000, 20, 20),
                Reason.TOTAL_BYTE_LIMIT);
        assertProblem(fixture, new ClasspathResolutionPolicy(100, 100, 100_000, 1_000_000, 10_000_000, 1),
                Reason.DEPENDENCY_DEPTH_LIMIT);

        var repeatedOverLimit = build(Map.of("pom.xml", pom("bounded", "<dependencies>"
                + dependency("ext", "left-bound", "1", "compile", false, "")
                + dependency("ext", "right-bound", "1", "compile", false, "")
                + "</dependencies>")));
        artifact("ext", "left-bound", "1", "<dependencies>"
                + dependency("ext", "over-limit", "1", "compile", false, "") + "</dependencies>", true);
        artifact("ext", "right-bound", "1", "<dependencies>"
                + dependency("ext", "over-limit", "1", "compile", false, "") + "</dependencies>", true);
        artifact("ext", "over-limit", "1", "", true);
        var capped = manifest(resolve(repeatedOverLimit,
                new ClasspathResolutionPolicy(2, 100, 100_000, 1_000_000, 10_000_000, 20), cacheRoot()),
                ".", SourcePlanModel.Kind.MAIN);
        assertTrue(capped.problems().stream().anyMatch(problem -> problem.reason() == Reason.COORDINATE_LIMIT));
        assertFalse(names(capped).contains("ext:over-limit:1@jar"),
                "repeating an over-limit coordinate must not bypass the cap");

        var modelLimited = build(
                Map.of("pom.xml", pom("model-bounded", "<dependencies>"
                        + dependency("ext", "model-heavy", "1", "compile", false, "")
                        + "</dependencies>")),
                Set.of(BuildModelResult.Reason.MODEL_WARNING),
                new BuildModelPolicy(List.of(), List.of(), Map.of(), 100_000, 100, 2));
        artifact("ext", "model-heavy", "1", "<parent><groupId>ext</groupId><artifactId>model-parent</artifactId>"
                + "<version>1</version></parent>", true);
        artifact("ext", "model-parent", "1", "<packaging>pom</packaging><dependencyManagement><dependencies>"
                + "<dependency><groupId>ext</groupId><artifactId>model-bom</artifactId><version>1</version>"
                + "<type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement>", false);
        artifact("ext", "model-bom", "1", "<packaging>pom</packaging>", false);
        assertProblem(modelLimited, POLICY, Reason.POM_MODEL_READ_LIMIT);
    }

    @Test
    void identitiesIgnoreCacheLocationButChangeWithArtifactBytesAndLinkedPathsAreDenied() throws Exception {
        var fixture = build(Map.of("pom.xml", pom("app", "<dependencies>"
                + dependency("ext", "lib", "1", "compile", false, "") + "</dependencies>")));
        Path first = temporary.resolve("first-cache");
        Path second = temporary.resolve("second-cache");
        artifact(first, "ext", "lib", "1", "", jar("same"));
        artifact(second, "ext", "lib", "1", "", jar("same"));

        var firstResult = resolve(fixture, POLICY, first);
        var secondResult = resolve(fixture, POLICY, second);
        assertEquals(firstResult.identity(), secondResult.identity());
        assertEquals(CanonicalJson.write(firstResult), CanonicalJson.write(secondResult));

        writeJar(second, "ext", "lib", "1", "jar", "", jar("changed"));
        assertNotEquals(firstResult.identity(), resolve(fixture, POLICY, second).identity());

        Path outside = temporary.resolve("outside");
        artifact(outside, "ext", "lib", "1", "", jar("outside"));
        Path linkedCache = temporary.resolve("linked-cache");
        Files.createDirectories(linkedCache);
        Path link = linkedCache.resolve("ext");
        createDirectoryLink(link, outside.resolve("ext"));
        try {
            var denied = resolve(fixture, POLICY, linkedCache);
            assertTrue(manifest(denied, ".", SourcePlanModel.Kind.MAIN).problems().stream()
                    .anyMatch(problem -> problem.reason() == Reason.PATH_OUTSIDE_CACHE
                            || problem.reason() == Reason.SYMBOLIC_LINK));
        } finally {
            Files.deleteIfExists(link);
        }

        Path rootLink = temporary.resolve("root-cache-link");
        createDirectoryLink(rootLink, first);
        try {
            var denied = resolve(fixture, POLICY, rootLink);
            assertTrue(manifest(denied, ".", SourcePlanModel.Kind.MAIN).problems().stream()
                    .anyMatch(problem -> problem.reason() == Reason.CACHE_ROOT_SYMBOLIC_LINK));
        } finally {
            Files.deleteIfExists(rootLink);
        }

        Path ordinaryFile = temporary.resolve("not-a-cache");
        Files.writeString(ordinaryFile, "file", StandardCharsets.UTF_8);
        var notDirectory = resolve(fixture, POLICY, ordinaryFile);
        assertTrue(manifest(notDirectory, ".", SourcePlanModel.Kind.MAIN).problems().stream()
                .anyMatch(problem -> problem.reason() == Reason.CACHE_ROOT_NOT_DIRECTORY));

        var notFound = resolve(fixture, POLICY, temporary.resolve("absent-cache"));
        assertTrue(manifest(notFound, ".", SourcePlanModel.Kind.MAIN).problems().stream()
                .anyMatch(problem -> problem.reason() == Reason.CACHE_ROOT_NOT_FOUND));
    }

    private void assertProblem(Fixture fixture, ClasspathResolutionPolicy policy, Reason reason) {
        var result = resolve(fixture, policy, cacheRoot());
        assertTrue(manifest(result, ".", SourcePlanModel.Kind.MAIN).problems().stream()
                .anyMatch(problem -> problem.reason() == reason), () -> reason + " absent from " + result);
    }

    private ExactClasspathResult resolve(Fixture fixture, ClasspathResolutionPolicy policy, Path cache) {
        return new MavenLocalClasspathProvider(cache).resolve(
                new ClasspathResolutionRequest(fixture.request(), fixture.model(), policy));
    }

    private Fixture build(Map<String, String> poms) {
        return build(poms, Set.of(BuildModelResult.Reason.MODEL_WARNING));
    }

    private Fixture build(Map<String, String> poms, Set<BuildModelResult.Reason> allowedProblems) {
        return build(poms, allowedProblems, MODEL_POLICY);
    }

    private Fixture build(
            Map<String, String> poms,
            Set<BuildModelResult.Reason> allowedProblems,
            BuildModelPolicy modelPolicy) {
        var inputs = new TreeMap<String, PomInput>();
        poms.forEach((path, xml) -> inputs.put(path, new PomInput(xml.getBytes(StandardCharsets.UTF_8))));
        var snapshot = RepositorySnapshot.create(REPOSITORY, Optional.empty(), false,
                inputs.entrySet().stream().map(entry -> new SnapshotFile(entry.getKey(), entry.getValue().digest())).toList(),
                List.of());
        var request = new BuildModelRequest(snapshot, "pom.xml", inputs, Map.of(), modelPolicy);
        var model = new MavenBuildModelProvider().build(request);
        assertTrue(model.problems().stream().allMatch(problem -> allowedProblems.contains(problem.reason())),
                () -> model.problems().toString());
        assertTrue(model.modules().stream().allMatch(module -> module.effectivePom().isPresent()),
                () -> model.problems().toString());
        return new Fixture(request, model);
    }

    private Manifest manifest(ExactClasspathResult result, String modulePath, SourcePlanModel.Kind kind) {
        return result.manifests().stream()
                .filter(manifest -> manifest.module().equals(com.evolution.analysis.contract.identity.ModuleIdentity.from(
                        REPOSITORY, modulePath)) && manifest.sourceSet() == kind)
                .findFirst().orElseThrow();
    }

    private static List<String> names(Manifest manifest) {
        return manifest.entries().stream().map(entry -> entry.coordinate().notation()).toList();
    }

    private static Entry entry(Manifest manifest, String notation) {
        return manifest.entries().stream()
                .filter(entry -> entry.coordinate().notation().equals(notation))
                .findFirst().orElseThrow();
    }

    private Path cacheRoot() {
        return temporary.resolve("cache");
    }

    private void artifact(String group, String artifact, String version, String body, boolean jar) throws IOException {
        artifact(cacheRoot(), group, artifact, version, body, jar ? jar(artifact) : null);
    }

    private void artifact(Path root, String group, String artifact, String version, String body, byte[] jar)
            throws IOException {
        writePom(root, group, artifact, version, pomWithGroup(group, artifact, version, body));
        if (jar != null) writeJar(root, group, artifact, version, "jar", "", jar);
    }

    private void writePom(String group, String artifact, String version, String xml) throws IOException {
        writePom(cacheRoot(), group, artifact, version, xml);
    }

    private static void writePom(Path root, String group, String artifact, String version, String xml)
            throws IOException {
        Path path = root.resolve(group.replace('.', '/')).resolve(artifact).resolve(version)
                .resolve(artifact + "-" + version + ".pom");
        Files.createDirectories(path.getParent());
        Files.writeString(path, xml, StandardCharsets.UTF_8);
    }

    private void writeJar(String group, String artifact, String version, String extension, String classifier, byte[] bytes)
            throws IOException {
        writeJar(cacheRoot(), group, artifact, version, extension, classifier, bytes);
    }

    private static void writeJar(Path root, String group, String artifact, String version,
            String extension, String classifier, byte[] bytes) throws IOException {
        String suffix = classifier.isEmpty() ? "" : "-" + classifier;
        Path path = root.resolve(group.replace('.', '/')).resolve(artifact).resolve(version)
                .resolve(artifact + "-" + version + suffix + "." + extension);
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
    }

    private static byte[] jar(String marker) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var jar = new JarOutputStream(output)) {
            var entry = new JarEntry("marker-" + marker);
            entry.setTime(0L);
            jar.putNextEntry(entry);
            jar.write(marker.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return output.toByteArray();
    }

    private static String pom(String artifact, String body) {
        return pomWithGroup("demo", artifact, "1", body);
    }

    private static String pomWithGroup(String group, String artifact, String version, String body) {
        return "<project><modelVersion>4.0.0</modelVersion><groupId>" + group + "</groupId><artifactId>"
                + artifact + "</artifactId><version>" + version + "</version>" + body + "</project>";
    }

    private static String child(String artifact, String body) {
        return "<project><modelVersion>4.0.0</modelVersion><parent><groupId>demo</groupId><artifactId>root</artifactId>"
                + "<version>1</version></parent><artifactId>" + artifact + "</artifactId>" + body + "</project>";
    }

    private static String dependency(
            String group, String artifact, String version, String scope, boolean optional, String extra) {
        return "<dependency><groupId>" + group + "</groupId><artifactId>" + artifact + "</artifactId><version>"
                + version + "</version><scope>" + scope + "</scope>"
                + (optional ? "<optional>true</optional>" : "") + extra + "</dependency>";
    }

    private static void createDirectoryLink(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
        } catch (FileSystemException exception) {
            if (!System.getProperty("os.name", "").startsWith("Windows")) throw exception;
            Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true).start();
            if (process.waitFor() != 0) fail("Windows junction fixture could not be created");
        }
    }

    private record Fixture(BuildModelRequest request, BuildModelResult model) {}
}
