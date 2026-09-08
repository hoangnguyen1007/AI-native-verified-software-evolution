package com.evolution.analysis.maven;

import static org.junit.jupiter.api.Assertions.*;

import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.buildmodel.BuildModelResult.EffectivePom;
import com.evolution.analysis.buildmodel.BuildModelResult.ModuleModel;
import com.evolution.analysis.buildmodel.BuildModelResult.Reason;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.source.RepositorySnapshot;
import com.evolution.analysis.contract.source.SnapshotFile;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenBuildModelResolverTest {
    private static final RepositoryIdentity REPOSITORY =
            RepositoryIdentity.fromCanonicalCoordinate("https://example.test/adaptive-build.git");
    private static final BuildModelPolicy MODEL_POLICY =
            new BuildModelPolicy(List.of(), List.of(), Map.of(), 100_000, 100, 32);
    private static final MavenPomResolutionPolicy RESOLUTION_POLICY =
            new MavenPomResolutionPolicy(100, 100_000, 5_000_000, 16);

    @TempDir
    Path temporary;

    @Test
    void resolvesExternalParentAndItsImportedBomFromSelectedLocalCache() throws IOException {
        MavenCoordinate parent = new MavenCoordinate("external", "parent", "1");
        MavenCoordinate bom = new MavenCoordinate("external", "platform", "1");
        writePom(parent, pom("external", "parent", "1", """
                <packaging>pom</packaging>
                <properties><java.version>17</java.version><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                <dependencyManagement><dependencies>
                  <dependency><groupId>external</groupId><artifactId>platform</artifactId><version>1</version><type>pom</type><scope>import</scope></dependency>
                </dependencies></dependencyManagement>
                <build><pluginManagement><plugins><plugin>
                  <groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.15.0</version>
                  <configuration><release>${java.version}</release><encoding>${project.build.sourceEncoding}</encoding></configuration>
                </plugin></plugins></pluginManagement></build>
                """));
        writePom(bom, pom("external", "platform", "1", """
                <packaging>pom</packaging><dependencyManagement><dependencies>
                  <dependency><groupId>external</groupId><artifactId>library</artifactId><version>2</version></dependency>
                </dependencies></dependencyManagement>
                """));
        String application = """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>external</groupId><artifactId>parent</artifactId><version>1</version><relativePath/></parent>
                  <groupId>app</groupId><artifactId>application</artifactId><version>1</version>
                  <dependencies><dependency><groupId>external</groupId><artifactId>library</artifactId></dependency></dependencies>
                </project>
                """;

        MavenBuildModelResolution resolution = resolver(cacheRoot()).resolve(
                request(Map.of("pom.xml", application)), RESOLUTION_POLICY);

        assertEquals(Set.of(parent, bom), resolution.request().artifactPoms().keySet(),
                () -> "attempts=" + resolution.attempts() + ", modelProblems=" + resolution.model().problems());
        assertTrue(resolution.model().problems().isEmpty(), () -> resolution.model().problems().toString());
        assertEquals(2, resolution.acquiredPoms().size());
        assertTrue(resolution.attempts().stream().allMatch(
                attempt -> attempt.outcome() == MavenBuildModelResolution.AttemptOutcome.SUCCEEDED));
        EffectivePom effective = effective(resolution.model(), "pom.xml");
        assertEquals("2", effective.dependencies().getFirst().version());
        assertEquals(Optional.of("17"), effective.sourcePlan().sourceSets().getFirst().syntaxLevel().value());
        assertEquals(Optional.of("UTF-8"), effective.sourcePlan().sourceSets().getFirst().encoding().value());
        assertTrue(effective.inputs().stream().anyMatch(input -> input.logicalId().equals("artifact:external:parent:1")));
        assertTrue(effective.inputs().stream().anyMatch(input -> input.logicalId().equals("artifact:external:platform:1")));
    }

    @Test
    void workspaceRelativeParentWinsWithoutRequiringAnyExternalRepository() {
        String root = pom("workspace", "parent", "1", """
                <packaging>pom</packaging><modules><module>child</module></modules>
                <properties><maven.compiler.release>17</maven.compiler.release></properties>
                """);
        String child = """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>workspace</groupId><artifactId>parent</artifactId><version>1</version></parent>
                  <artifactId>child</artifactId>
                </project>
                """;

        MavenBuildModelResolution resolution = resolver(temporary.resolve("cache-does-not-exist")).resolve(
                request(Map.of("pom.xml", root, "child/pom.xml", child)), RESOLUTION_POLICY);

        assertTrue(resolution.model().problems().isEmpty(), () -> resolution.model().problems().toString());
        assertTrue(resolution.request().artifactPoms().isEmpty());
        assertTrue(resolution.attempts().isEmpty());
        assertEquals(Optional.of("17"), effective(resolution.model(), "child/pom.xml")
                .sourcePlan().sourceSets().getFirst().syntaxLevel().value());
    }

    @Test
    void selfContainedPomDoesNotRequireAConfiguredCacheToExist() {
        MavenBuildModelResolution resolution = resolver(temporary.resolve("absent-cache")).resolve(
                request(Map.of("pom.xml", pom("app", "standalone", "1",
                        "<properties><maven.compiler.release>21</maven.compiler.release></properties>"))),
                RESOLUTION_POLICY);

        assertTrue(resolution.model().problems().isEmpty(), () -> resolution.model().problems().toString());
        assertTrue(resolution.attempts().isEmpty());
        assertTrue(resolution.problems().isEmpty());
    }

    @Test
    void absentPomAndMissingExternalParentRemainExplicitInsteadOfCrashingOrInventingDefaults() throws IOException {
        MavenBuildModelResolution pomless = resolver(temporary.resolve("absent-cache")).resolve(
                request(Map.of()), RESOLUTION_POLICY);
        assertTrue(reasons(pomless.model()).contains(Reason.MISSING_MODULE_POM));
        assertTrue(pomless.attempts().isEmpty());

        Files.createDirectories(cacheRoot());
        String child = """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>missing</groupId><artifactId>parent</artifactId><version>1</version><relativePath/></parent>
                  <artifactId>child</artifactId>
                </project>
                """;
        MavenBuildModelResolution missing = resolver(cacheRoot()).resolve(
                request(Map.of("pom.xml", child)), RESOLUTION_POLICY);

        assertTrue(reasons(missing.model()).contains(Reason.MISSING_PARENT_POM));
        assertEquals(1, missing.attempts().size());
        assertEquals(MavenBuildModelResolution.AttemptOutcome.UNAVAILABLE, missing.attempts().getFirst().outcome());
        assertTrue(missing.request().artifactPoms().isEmpty());
    }

    @Test
    void malformedOrCoordinateMismatchedCachedParentCannotBecomeBuildEvidence() throws IOException {
        MavenCoordinate parent = new MavenCoordinate("external", "parent", "1");
        String child = """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>external</groupId><artifactId>parent</artifactId><version>1</version><relativePath/></parent>
                  <artifactId>child</artifactId>
                </project>
                """;
        writePom(parent, pom("external", "impostor", "1", "<packaging>pom</packaging>"));

        MavenBuildModelResolution mismatch = resolver(cacheRoot()).resolve(
                request(Map.of("pom.xml", child)), RESOLUTION_POLICY);

        assertTrue(reasons(mismatch.model()).contains(Reason.COORDINATE_MISMATCH));
        assertTrue(mismatch.model().modules().getFirst().effectivePom().isEmpty());
        assertEquals(parent, mismatch.acquiredPoms().getFirst().coordinate());
    }

    @Test
    void explicitHttpsFallbackCanResolveAReleaseParentWithoutReadingTargetRepositories() {
        MavenCoordinate parent = new MavenCoordinate("external", "parent", "1");
        byte[] parentBytes = pom("external", "parent", "1", """
                <packaging>pom</packaging><properties><maven.compiler.release>17</maven.compiler.release></properties>
                """).getBytes(StandardCharsets.UTF_8);
        var requestedUris = new ArrayList<URI>();
        MavenBuildModelResolver.RemotePomTransport transport = (uri, maxBytes, connectTimeout, requestTimeout) -> {
            requestedUris.add(uri);
            return new MavenBuildModelResolver.RemoteResponse(200, parentBytes);
        };
        MavenPomResolutionPolicy remotePolicy = new MavenPomResolutionPolicy(
                100, 100_000, 5_000_000, 16,
                List.of(URI.create("https://repo.example.test/maven2/")), 1_000, 2_000);
        String child = """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>external</groupId><artifactId>parent</artifactId><version>1</version><relativePath/></parent>
                  <artifactId>child</artifactId>
                  <repositories><repository><id>ignored</id><url>https://target.example.test/private/</url></repository></repositories>
                </project>
                """;

        MavenBuildModelResolution resolution = new MavenBuildModelResolver(
                temporary.resolve("absent-cache"), transport).resolve(request(Map.of("pom.xml", child)), remotePolicy);

        assertTrue(resolution.model().problems().isEmpty(), () -> resolution.model().problems().toString());
        assertTrue(resolution.problems().isEmpty(),
                () -> "A successful fallback must not remain classified as an open resolution problem: "
                        + resolution.problems());
        assertEquals(List.of(URI.create(
                "https://repo.example.test/maven2/external/parent/1/parent-1.pom")), requestedUris);
        assertEquals(MavenBuildModelResolution.Origin.REMOTE_REPOSITORY,
                resolution.acquiredPoms().getFirst().origin());
        assertFalse(resolution.acquiredPoms().getFirst().logicalLocation().contains("target.example.test"));
    }

    @Test
    void totalByteBudgetIsSharedAcrossRemoteAndLocalCacheAcquisitionRounds() throws IOException {
        MavenCoordinate parent = new MavenCoordinate("external", "remote-parent", "1");
        MavenCoordinate bom = new MavenCoordinate("external", "local-platform", "1");
        byte[] parentBytes = pom("external", "remote-parent", "1", """
                <packaging>pom</packaging><dependencyManagement><dependencies>
                  <dependency><groupId>external</groupId><artifactId>local-platform</artifactId><version>1</version><type>pom</type><scope>import</scope></dependency>
                </dependencies></dependencyManagement>
                """).getBytes(StandardCharsets.UTF_8);
        String bomXml = pom("external", "local-platform", "1", "<packaging>pom</packaging>");
        writePom(bom, bomXml);
        MavenBuildModelResolver.RemotePomTransport transport = (uri, maxBytes, connectTimeout, requestTimeout) ->
                uri.getPath().endsWith("/remote-parent-1.pom")
                        ? new MavenBuildModelResolver.RemoteResponse(200, parentBytes)
                        : new MavenBuildModelResolver.RemoteResponse(404, new byte[0]);
        long totalBudget = parentBytes.length + bomXml.getBytes(StandardCharsets.UTF_8).length - 1L;
        MavenPomResolutionPolicy policy = new MavenPomResolutionPolicy(
                100, 100_000, totalBudget, 16,
                List.of(URI.create("https://repo.example.test/maven2/")), 1_000, 2_000);
        String child = """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>external</groupId><artifactId>remote-parent</artifactId><version>1</version><relativePath/></parent>
                  <artifactId>child</artifactId>
                </project>
                """;

        MavenBuildModelResolution resolution = new MavenBuildModelResolver(cacheRoot(), transport)
                .resolve(request(Map.of("pom.xml", child)), policy);

        assertEquals(List.of(parent), resolution.acquiredPoms().stream()
                .map(MavenBuildModelResolution.AcquiredPom::coordinate).toList());
        assertTrue(resolution.attempts().stream().anyMatch(attempt -> attempt.coordinate().equals(bom)
                && attempt.origin() == MavenBuildModelResolution.Origin.LOCAL_CACHE
                && attempt.outcome() == MavenBuildModelResolution.AttemptOutcome.LIMIT_EXCEEDED),
                () -> resolution.attempts().toString());
        assertTrue(resolution.acquiredPoms().stream().mapToLong(MavenBuildModelResolution.AcquiredPom::size).sum()
                <= totalBudget);
    }

    @Test
    void remoteRepositoriesRequireExplicitCredentialFreeHttpsConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new MavenPomResolutionPolicy(
                10, 1_000, 10_000, 2, List.of(URI.create("http://repo.example.test/")), 1_000, 2_000));
        assertThrows(IllegalArgumentException.class, () -> new MavenPomResolutionPolicy(
                10, 1_000, 10_000, 2, List.of(URI.create("https://user:secret@repo.example.test/")), 1_000, 2_000));
        assertThrows(IllegalArgumentException.class, () -> new MavenPomResolutionPolicy(
                10, 1_000, 10_000, 2, List.of(URI.create("https://repo.example.test/no-trailing-slash")), 1_000, 2_000));
    }

    private MavenBuildModelResolver resolver(Path cache) {
        return new MavenBuildModelResolver(cache);
    }

    private Path cacheRoot() {
        return temporary.resolve("cache");
    }

    private void writePom(MavenCoordinate coordinate, String xml) throws IOException {
        Path path = cacheRoot().resolve(coordinate.groupId().replace('.', '/'))
                .resolve(coordinate.artifactId()).resolve(coordinate.version())
                .resolve(coordinate.artifactId() + "-" + coordinate.version() + ".pom");
        Files.createDirectories(path.getParent());
        Files.writeString(path, xml, StandardCharsets.UTF_8);
    }

    private static BuildModelRequest request(Map<String, String> poms) {
        var inputs = new TreeMap<String, PomInput>();
        poms.forEach((path, xml) -> inputs.put(path, new PomInput(xml.getBytes(StandardCharsets.UTF_8))));
        RepositorySnapshot snapshot = RepositorySnapshot.create(REPOSITORY, Optional.empty(), false,
                inputs.entrySet().stream().map(entry -> new SnapshotFile(entry.getKey(), entry.getValue().digest())).toList(),
                List.of());
        return new BuildModelRequest(snapshot, "pom.xml", inputs, Map.of(), MODEL_POLICY);
    }

    private static EffectivePom effective(BuildModelResult result, String pomPath) {
        return result.modules().stream().filter(module -> module.pomPath().equals(pomPath))
                .findFirst().flatMap(ModuleModel::effectivePom).orElseThrow();
    }

    private static Set<Reason> reasons(BuildModelResult result) {
        var reasons = EnumSet.noneOf(Reason.class);
        result.problems().forEach(problem -> reasons.add(problem.reason()));
        return reasons;
    }

    private static String pom(String group, String artifact, String version, String body) {
        return "<project><modelVersion>4.0.0</modelVersion><groupId>" + group + "</groupId><artifactId>"
                + artifact + "</artifactId><version>" + version + "</version>" + body + "</project>";
    }
}
