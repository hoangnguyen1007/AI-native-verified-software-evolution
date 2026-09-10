package com.evolution.analysis.maven;

import static org.junit.jupiter.api.Assertions.*;

import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.classpath.*;
import com.evolution.analysis.dependency.*;
import com.evolution.analysis.frontend.BinaryInput;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.source.RepositorySnapshot;
import com.evolution.analysis.contract.source.SnapshotFile;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenDependencyArtifactResolverTest {
    private static final URI REPOSITORY = URI.create("https://repo.example.test/maven2/");
    private static final RepositoryIdentity REPOSITORY_ID =
            RepositoryIdentity.fromCanonicalCoordinate("https://example.test/dependency-fixpoint.git");

    @TempDir
    Path temporary;

    @Test
    void resolvesDescriptorAndBinaryClosureToAFiniteExactClasspath() throws Exception {
        Path cache = Files.createDirectory(temporary.resolve("cache")).toRealPath();
        MavenCoordinate library = new MavenCoordinate("external", "library", "1");
        MavenCoordinate child = new MavenCoordinate("external", "child", "2");
        Map<String, byte[]> remote = Map.of(
                path(library, "pom"), pom("external", "library", "1", """
                        <dependencies><dependency><groupId>external</groupId><artifactId>child</artifactId><version>2</version></dependency></dependencies>
                        """).getBytes(StandardCharsets.UTF_8),
                path(library, "jar"), jar("external/Library.class"),
                path(child, "pom"), pom("external", "child", "2", "").getBytes(StandardCharsets.UTF_8),
                path(child, "jar"), jar("external/Child.class"));
        var transport = transport(remote);
        ClasspathResolutionRequest request = classpathRequest("""
                <dependencies><dependency><groupId>external</groupId><artifactId>library</artifactId><version>1</version></dependency></dependencies>
                """);
        MavenDependencyArtifactResolver resolver = resolver(cache, transport);

        MavenDependencyArtifactResolution resolution = resolver.resolve(request, policy(8));

        assertTrue(resolution.problems().isEmpty(), () -> resolution.problems().toString());
        assertEquals(3, resolution.classpathPasses());
        assertEquals(2, resolution.acquisitions().size());
        assertTrue(resolution.classpath().manifests().stream()
                .allMatch(value -> value.status() == ExactClasspathResult.Status.COMPLETE));
        assertEquals(List.of("external:library:1@jar", "external:child:2@jar"),
                resolution.classpath().manifests().getFirst().entries().stream()
                        .map(value -> value.coordinate().notation()).toList());
        assertEquals(Set.copyOf(remote.keySet()), transport.requestedPaths());

        List<BinaryInput> binaries = resolver.binaryInputs(resolution);
        assertEquals(2, binaries.size());
        assertTrue(binaries.stream().allMatch(value -> Files.isRegularFile(value.path())));
        assertEquals(resolution.classpath().manifests().getFirst().classpath(),
                binaries.stream().map(BinaryInput::entry).toList());

        MavenDependencyArtifactResolution cachedFirst = resolver.resolve(request, policy(8));
        MavenDependencyArtifactResolution cachedSecond = resolver.resolve(request, policy(8));
        assertEquals(cachedFirst.identity(), cachedSecond.identity());
        assertTrue(cachedFirst.acquisitions().isEmpty());
    }

    @Test
    void pomDependencyAcquiresOnlyItsDescriptorAndContributesNoBinary() throws Exception {
        Path cache = Files.createDirectory(temporary.resolve("pom-cache")).toRealPath();
        MavenCoordinate bom = new MavenCoordinate("external", "bom", "1");
        RecordingTransport transport = transport(Map.of(
                path(bom, "pom"), pom("external", "bom", "1", "<packaging>pom</packaging>")
                        .getBytes(StandardCharsets.UTF_8)));
        ClasspathResolutionRequest request = classpathRequest("""
                <dependencies><dependency><groupId>external</groupId><artifactId>bom</artifactId><version>1</version><type>pom</type></dependency></dependencies>
                """);

        MavenDependencyArtifactResolution resolution = resolver(cache, transport).resolve(request, policy(8));

        assertTrue(resolution.problems().isEmpty(), () -> resolution.problems().toString());
        assertTrue(resolution.classpath().manifests().stream().allMatch(value -> value.entries().isEmpty()));
        assertEquals(Set.of(path(bom, "pom")), transport.requestedPaths());
        assertTrue(resolution.acquisitions().stream().flatMap(value -> value.outcomes().stream())
                .allMatch(value -> value.status() == DependencyAcquisitionResult.Status.SKIPPED_POM));
    }

    @Test
    void passLimitRetainsTheNewlyDiscoveredCoordinatesAsExplicitProblems() throws Exception {
        Path cache = Files.createDirectory(temporary.resolve("limited-cache")).toRealPath();
        MavenCoordinate library = new MavenCoordinate("external", "library", "1");
        MavenCoordinate child = new MavenCoordinate("external", "child", "2");
        RecordingTransport transport = transport(Map.of(
                path(library, "pom"), pom("external", "library", "1", """
                        <dependencies><dependency><groupId>external</groupId><artifactId>child</artifactId><version>2</version></dependency></dependencies>
                        """).getBytes(StandardCharsets.UTF_8),
                path(library, "jar"), jar("external/Library.class"),
                path(child, "pom"), pom("external", "child", "2", "").getBytes(StandardCharsets.UTF_8),
                path(child, "jar"), jar("external/Child.class")));
        ClasspathResolutionRequest request = classpathRequest("""
                <dependencies><dependency><groupId>external</groupId><artifactId>library</artifactId><version>1</version></dependency></dependencies>
                """);

        MavenDependencyArtifactResolution result = resolver(cache, transport).resolve(request, policy(1));

        assertTrue(result.problems().stream().anyMatch(
                value -> value.reason() == MavenDependencyArtifactResolution.ProblemReason.PASS_LIMIT));
        assertTrue(result.classpath().hasGaps());
        assertEquals(Set.of(path(library, "pom"), path(library, "jar")), transport.requestedPaths());
    }

    private MavenDependencyArtifactResolver resolver(Path cache, RecordingTransport transport) {
        MavenDependencyArtifactProvider provider =
                new MavenDependencyArtifactProvider(cache, transport, millis -> {});
        return new MavenDependencyArtifactResolver(cache, provider);
    }

    private static DependencyAcquisitionPolicy policy(int passes) {
        return new DependencyAcquisitionPolicy(100, 1_000_000, 10_000_000, passes,
                List.of(REPOSITORY), 1_000, 2_000, 0);
    }

    private static ClasspathResolutionRequest classpathRequest(String body) {
        String xml = pom("app", "application", "1", """
                <properties><maven.compiler.release>17</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                """ + body);
        PomInput input = new PomInput(xml.getBytes(StandardCharsets.UTF_8));
        RepositorySnapshot snapshot = RepositorySnapshot.create(REPOSITORY_ID, Optional.empty(), false,
                List.of(new SnapshotFile("pom.xml", input.digest())), List.of());
        BuildModelRequest buildRequest = new BuildModelRequest(snapshot, "pom.xml", Map.of("pom.xml", input), Map.of(),
                new BuildModelPolicy(List.of(), List.of(), Map.of(), 100_000, 100, 32));
        BuildModelResult build = new MavenBuildModelProvider().build(buildRequest);
        assertTrue(build.problems().isEmpty(), () -> build.problems().toString());
        return new ClasspathResolutionRequest(buildRequest, build,
                new ClasspathResolutionPolicy(100, 200, 1_000_000, 1_000_000, 10_000_000, 32));
    }

    private static RecordingTransport transport(Map<String, byte[]> responses) {
        return new RecordingTransport(responses);
    }

    private static String path(MavenCoordinate coordinate, String extension) {
        return coordinate.groupId().replace('.', '/') + "/" + coordinate.artifactId() + "/" + coordinate.version()
                + "/" + coordinate.artifactId() + "-" + coordinate.version() + "." + extension;
    }

    private static String pom(String group, String artifact, String version, String body) {
        return "<project><modelVersion>4.0.0</modelVersion><groupId>" + group + "</groupId><artifactId>"
                + artifact + "</artifactId><version>" + version + "</version>" + body + "</project>";
    }

    private static byte[] jar(String entry) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var jar = new JarOutputStream(output)) {
            jar.putNextEntry(new JarEntry(entry));
            jar.write(new byte[] {1, 2, 3});
            jar.closeEntry();
        }
        return output.toByteArray();
    }

    private static final class RecordingTransport implements MavenDependencyArtifactProvider.RemoteArtifactTransport {
        private final Map<String, byte[]> responses;
        private final Set<String> requestedPaths = new TreeSet<>();

        private RecordingTransport(Map<String, byte[]> responses) { this.responses = Map.copyOf(responses); }

        @Override
        public MavenDependencyArtifactProvider.RemoteResponse fetch(
                URI uri, long maxBytes, int connectTimeoutMillis, int readTimeoutMillis) {
            String path = uri.getPath().substring("/maven2/".length());
            requestedPaths.add(path);
            byte[] bytes = responses.get(path);
            return new MavenDependencyArtifactProvider.RemoteResponse(bytes == null ? 404 : 200,
                    bytes == null ? new byte[0] : bytes);
        }

        private Set<String> requestedPaths() { return Set.copyOf(requestedPaths); }
    }
}
