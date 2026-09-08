package com.evolution.analysis.maven;

import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.classpath.ClasspathResolutionPolicy;
import com.evolution.analysis.classpath.ExactClasspathResult;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.file.Path;
import java.util.*;

/**
 * Progressively supplies exact external parent/BOM POMs from one selected local Maven2 cache and,
 * only when explicitly configured, bounded credential-free HTTPS repositories. Target-declared
 * repositories, settings, lifecycle execution, plugins and ambient repository lookup are absent.
 */
public final class MavenBuildModelResolver {
    public static final VersionedIdentifier VERSION =
            new VersionedIdentifier("build.maven-local-inputs", "3.9.16-m3.7");

    private final Path selectedCacheRoot;
    private final MavenBuildModelProvider modelProvider;
    private final RemotePomTransport remoteTransport;

    public MavenBuildModelResolver(Path selectedCacheRoot) {
        this(selectedCacheRoot, new JdkRemotePomTransport());
    }

    MavenBuildModelResolver(Path selectedCacheRoot, RemotePomTransport remoteTransport) {
        this.selectedCacheRoot = Objects.requireNonNull(selectedCacheRoot);
        this.modelProvider = new MavenBuildModelProvider();
        this.remoteTransport = Objects.requireNonNull(remoteTransport);
    }

    public MavenBuildModelResolution resolve(BuildModelRequest initial, MavenPomResolutionPolicy policy) {
        Objects.requireNonNull(initial);
        Objects.requireNonNull(policy);
        BuildModelRequest current = initial;
        BuildModelResult model = modelProvider.build(current);
        int passes = 1;
        var supplied = new TreeMap<String, Map.Entry<MavenCoordinate, PomInput>>();
        initial.artifactPoms().forEach((coordinate, input) -> supplied.put(coordinate.notation(), Map.entry(coordinate, input)));
        var acquired = new ArrayList<MavenBuildModelResolution.AcquiredPom>();
        var attempts = new ArrayList<MavenBuildModelResolution.Attempt>();
        var problems = new ArrayList<MavenBuildModelResolution.Problem>();
        var attemptedCoordinates = new HashSet<MavenCoordinate>();
        LocalArtifactCache cache = null;
        boolean cacheUnavailable = false;
        MavenBuildModelResolution.Problem cacheRootProblem = null;
        boolean cacheRootProblemRecorded = false;
        long acquiredBytes = 0;
        int acquisitionRounds = 0;

        while (acquisitionRounds < policy.maxPasses()) {
            List<MavenCoordinate> missing = missingArtifactPoms(model).stream()
                    .filter(coordinate -> !supplied.containsKey(coordinate.notation()))
                    .filter(attemptedCoordinates::add)
                    .toList();
            if (missing.isEmpty()) break;

            if (cache == null && !cacheUnavailable) {
                try {
                    cache = new LocalArtifactCache(selectedCacheRoot, initial, cachePolicy(policy));
                } catch (LocalArtifactCache.RootFailure failure) {
                    cacheUnavailable = true;
                    cacheRootProblem = new MavenBuildModelResolution.Problem(map(failure.reason), "cache-root");
                }
            }

            boolean added = false;
            for (MavenCoordinate coordinate : missing) {
                String repositoryPath = coordinatePath(coordinate);
                if (supplied.size() >= Math.min(policy.maxPomCount(), initial.policy().maxPomCount())) {
                    attempts.add(new MavenBuildModelResolution.Attempt(coordinate,
                            MavenBuildModelResolution.Origin.LOCAL_CACHE, repositoryPath,
                            MavenBuildModelResolution.AttemptOutcome.LIMIT_EXCEEDED, Optional.empty()));
                    problems.add(new MavenBuildModelResolution.Problem(
                            MavenBuildModelResolution.ProblemReason.POM_COUNT_LIMIT, coordinate.notation()));
                    continue;
                }
                boolean acquiredCoordinate = false;
                MavenBuildModelResolution.Problem localProblem = null;
                if (cacheUnavailable) {
                    attempts.add(new MavenBuildModelResolution.Attempt(coordinate,
                            MavenBuildModelResolution.Origin.LOCAL_CACHE, repositoryPath,
                            MavenBuildModelResolution.AttemptOutcome.UNAVAILABLE, Optional.empty()));
                } else {
                    try {
                        LocalArtifactCache.Material material = cache.pom(coordinate);
                        PomInput input = new PomInput(material.bytes());
                        supplied.put(coordinate.notation(), Map.entry(coordinate, input));
                        acquired.add(new MavenBuildModelResolution.AcquiredPom(coordinate,
                                MavenBuildModelResolution.Origin.LOCAL_CACHE,
                                material.record().repositoryPath().orElseThrow(),
                                material.record().contentDigest(), material.record().size()));
                        attempts.add(new MavenBuildModelResolution.Attempt(coordinate,
                                MavenBuildModelResolution.Origin.LOCAL_CACHE,
                                material.record().repositoryPath().orElseThrow(),
                                MavenBuildModelResolution.AttemptOutcome.SUCCEEDED,
                                Optional.of(material.record().contentDigest())));
                        acquiredBytes += material.record().size();
                        acquiredCoordinate = true;
                    } catch (LocalArtifactCache.Failure failure) {
                        attempts.add(new MavenBuildModelResolution.Attempt(coordinate,
                                MavenBuildModelResolution.Origin.LOCAL_CACHE, repositoryPath,
                                map(failure), Optional.empty()));
                        localProblem = new MavenBuildModelResolution.Problem(map(failure.reason), failure.subject);
                    }
                }
                RemoteAcquisition remote = new RemoteAcquisition(
                        Optional.empty(), Optional.empty(), List.of(), List.of());
                if (!acquiredCoordinate && !policy.remoteRepositories().isEmpty()) {
                    remote = acquireRemote(coordinate, policy, acquiredBytes);
                    attempts.addAll(remote.attempts());
                    if (remote.input().isPresent()) {
                        PomInput input = remote.input().orElseThrow();
                        if (cache != null) cache.accountExternallyAcquiredBytes(input.size());
                        supplied.put(coordinate.notation(), Map.entry(coordinate, input));
                        acquired.add(remote.artifact().orElseThrow());
                        acquiredBytes += input.size();
                        acquiredCoordinate = true;
                    }
                }
                if (!acquiredCoordinate) {
                    if (cacheRootProblem != null && !cacheRootProblemRecorded) {
                        problems.add(cacheRootProblem);
                        cacheRootProblemRecorded = true;
                    }
                    if (localProblem != null) problems.add(localProblem);
                    problems.addAll(remote.problems());
                }
                added |= acquiredCoordinate;
            }
            if (!added) break;
            var artifactPoms = new LinkedHashMap<MavenCoordinate, PomInput>();
            supplied.values().forEach(entry -> artifactPoms.put(entry.getKey(), entry.getValue()));
            current = new BuildModelRequest(initial.snapshot(), initial.rootPom(), initial.workspacePoms(),
                    artifactPoms, initial.policy());
            model = modelProvider.build(current);
            passes++;
            acquisitionRounds++;
        }
        if (acquisitionRounds == policy.maxPasses() && !missingArtifactPoms(model).isEmpty()) {
            problems.add(new MavenBuildModelResolution.Problem(
                    MavenBuildModelResolution.ProblemReason.PASS_LIMIT, "build-model"));
        }
        return MavenBuildModelResolution.create(initial.identity(), policy, VERSION, current, model,
                acquired, attempts, problems, passes);
    }

    private RemoteAcquisition acquireRemote(
            MavenCoordinate coordinate, MavenPomResolutionPolicy policy, long acquiredBytes) {
        var attempts = new ArrayList<MavenBuildModelResolution.Attempt>();
        var problems = new ArrayList<MavenBuildModelResolution.Problem>();
        if (coordinate.version().endsWith("-SNAPSHOT")) {
            String subject = coordinate.notation();
            problems.add(new MavenBuildModelResolution.Problem(
                    MavenBuildModelResolution.ProblemReason.REMOTE_SNAPSHOT_UNSUPPORTED, subject));
            return new RemoteAcquisition(Optional.empty(), Optional.empty(), attempts, problems);
        }
        long allowed = Math.min(policy.maxPomBytes(), policy.maxTotalBytes() - acquiredBytes);
        if (allowed < 1) {
            problems.add(new MavenBuildModelResolution.Problem(
                    MavenBuildModelResolution.ProblemReason.TOTAL_BYTE_LIMIT, coordinate.notation()));
            return new RemoteAcquisition(Optional.empty(), Optional.empty(), attempts, problems);
        }
        for (URI repository : policy.remoteRepositories()) {
            URI uri = repository.resolve(coordinatePath(coordinate));
            try {
                RemoteResponse response = remoteTransport.fetch(uri, allowed,
                        policy.connectTimeoutMillis(), policy.requestTimeoutMillis());
                if (response.statusCode() == 404 || response.statusCode() == 410) {
                    attempts.add(remoteAttempt(coordinate, uri,
                            MavenBuildModelResolution.AttemptOutcome.UNAVAILABLE, Optional.empty()));
                    continue;
                }
                if (response.statusCode() >= 300 && response.statusCode() < 400) {
                    attempts.add(remoteAttempt(coordinate, uri,
                            MavenBuildModelResolution.AttemptOutcome.DENIED, Optional.empty()));
                    problems.add(new MavenBuildModelResolution.Problem(
                            MavenBuildModelResolution.ProblemReason.REMOTE_RESPONSE_DENIED, uri.toASCIIString()));
                    continue;
                }
                if (response.statusCode() != 200) {
                    attempts.add(remoteAttempt(coordinate, uri,
                            MavenBuildModelResolution.AttemptOutcome.FAILED, Optional.empty()));
                    problems.add(new MavenBuildModelResolution.Problem(
                            MavenBuildModelResolution.ProblemReason.REMOTE_RESPONSE_INVALID, uri.toASCIIString()));
                    continue;
                }
                byte[] bytes = response.bytes();
                if (bytes.length > allowed) throw new RemoteLimitExceeded();
                ContentDigest digest = ContentDigest.sha256(bytes);
                attempts.add(remoteAttempt(coordinate, uri,
                        MavenBuildModelResolution.AttemptOutcome.SUCCEEDED, Optional.of(digest)));
                var artifact = new MavenBuildModelResolution.AcquiredPom(coordinate,
                        MavenBuildModelResolution.Origin.REMOTE_REPOSITORY,
                        uri.toASCIIString(), digest, bytes.length);
                return new RemoteAcquisition(Optional.of(new PomInput(bytes)), Optional.of(artifact), attempts, List.of());
            } catch (RemoteLimitExceeded exception) {
                attempts.add(remoteAttempt(coordinate, uri,
                        MavenBuildModelResolution.AttemptOutcome.LIMIT_EXCEEDED, Optional.empty()));
                problems.add(new MavenBuildModelResolution.Problem(
                        MavenBuildModelResolution.ProblemReason.POM_BYTE_LIMIT, uri.toASCIIString()));
            } catch (IOException exception) {
                attempts.add(remoteAttempt(coordinate, uri,
                        MavenBuildModelResolution.AttemptOutcome.FAILED, Optional.empty()));
                problems.add(new MavenBuildModelResolution.Problem(
                        MavenBuildModelResolution.ProblemReason.REMOTE_REQUEST_FAILED, uri.toASCIIString()));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                attempts.add(remoteAttempt(coordinate, uri,
                        MavenBuildModelResolution.AttemptOutcome.FAILED, Optional.empty()));
                problems.add(new MavenBuildModelResolution.Problem(
                        MavenBuildModelResolution.ProblemReason.REMOTE_REQUEST_FAILED, uri.toASCIIString()));
                break;
            }
        }
        return new RemoteAcquisition(Optional.empty(), Optional.empty(), attempts, problems);
    }

    private static MavenBuildModelResolution.Attempt remoteAttempt(
            MavenCoordinate coordinate,
            URI uri,
            MavenBuildModelResolution.AttemptOutcome outcome,
            Optional<ContentDigest> digest) {
        return new MavenBuildModelResolution.Attempt(coordinate,
                MavenBuildModelResolution.Origin.REMOTE_REPOSITORY, uri.toASCIIString(), outcome, digest);
    }

    private static List<MavenCoordinate> missingArtifactPoms(BuildModelResult model) {
        return model.attempts().stream()
                .filter(attempt -> attempt.outcome() == BuildModelResult.Outcome.UNAVAILABLE)
                .map(BuildModelResult.Attempt::requested)
                .filter(requested -> requested.startsWith("artifact:"))
                .map(requested -> coordinate(requested.substring("artifact:".length())))
                .flatMap(Optional::stream)
                .distinct()
                .sorted(Comparator.comparing(MavenCoordinate::notation))
                .toList();
    }

    private static Optional<MavenCoordinate> coordinate(String notation) {
        String[] parts = notation.split(":", -1);
        if (parts.length != 3) return Optional.empty();
        try {
            return Optional.of(new MavenCoordinate(parts[0], parts[1], parts[2]));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static ClasspathResolutionPolicy cachePolicy(MavenPomResolutionPolicy policy) {
        return new ClasspathResolutionPolicy(policy.maxPomCount(), policy.maxPomCount(), policy.maxPomBytes(),
                policy.maxPomBytes(), policy.maxTotalBytes(), policy.maxPasses());
    }

    private static String coordinatePath(MavenCoordinate coordinate) {
        return coordinate.groupId().replace('.', '/') + "/" + coordinate.artifactId() + "/" + coordinate.version()
                + "/" + coordinate.artifactId() + "-" + coordinate.version() + ".pom";
    }

    private static MavenBuildModelResolution.AttemptOutcome map(LocalArtifactCache.Failure failure) {
        return switch (failure.reason) {
            case PATH_OUTSIDE_CACHE, SYMBOLIC_LINK, NON_REGULAR_ARTIFACT ->
                    MavenBuildModelResolution.AttemptOutcome.DENIED;
            case FILE_COUNT_LIMIT, POM_BYTE_LIMIT, TOTAL_BYTE_LIMIT ->
                    MavenBuildModelResolution.AttemptOutcome.LIMIT_EXCEEDED;
            case MISSING_POM -> MavenBuildModelResolution.AttemptOutcome.UNAVAILABLE;
            default -> MavenBuildModelResolution.AttemptOutcome.FAILED;
        };
    }

    private static MavenBuildModelResolution.ProblemReason map(ExactClasspathResult.Reason reason) {
        return switch (reason) {
            case CACHE_ROOT_NOT_FOUND -> MavenBuildModelResolution.ProblemReason.CACHE_ROOT_NOT_FOUND;
            case CACHE_ROOT_NOT_DIRECTORY -> MavenBuildModelResolution.ProblemReason.CACHE_ROOT_NOT_DIRECTORY;
            case CACHE_ROOT_SYMBOLIC_LINK -> MavenBuildModelResolution.ProblemReason.CACHE_ROOT_SYMBOLIC_LINK;
            case CACHE_ROOT_READ_FAILED -> MavenBuildModelResolution.ProblemReason.CACHE_ROOT_READ_FAILED;
            case PATH_OUTSIDE_CACHE -> MavenBuildModelResolution.ProblemReason.PATH_OUTSIDE_CACHE;
            case SYMBOLIC_LINK -> MavenBuildModelResolution.ProblemReason.SYMBOLIC_LINK;
            case NON_REGULAR_ARTIFACT -> MavenBuildModelResolution.ProblemReason.NON_REGULAR_POM;
            case ARTIFACT_READ_FAILED -> MavenBuildModelResolution.ProblemReason.POM_READ_FAILED;
            case ARTIFACT_CHANGED_DURING_READ -> MavenBuildModelResolution.ProblemReason.POM_CHANGED_DURING_READ;
            case FILE_COUNT_LIMIT -> MavenBuildModelResolution.ProblemReason.POM_COUNT_LIMIT;
            case POM_BYTE_LIMIT -> MavenBuildModelResolution.ProblemReason.POM_BYTE_LIMIT;
            case TOTAL_BYTE_LIMIT -> MavenBuildModelResolution.ProblemReason.TOTAL_BYTE_LIMIT;
            case MISSING_POM -> MavenBuildModelResolution.ProblemReason.MISSING_POM;
            default -> MavenBuildModelResolution.ProblemReason.POM_READ_FAILED;
        };
    }

    interface RemotePomTransport {
        RemoteResponse fetch(URI uri, long maxBytes, int connectTimeoutMillis, int requestTimeoutMillis)
                throws IOException, InterruptedException, RemoteLimitExceeded;
    }

    record RemoteResponse(int statusCode, byte[] bytes) {
        RemoteResponse {
            if (statusCode < 100 || statusCode > 599) throw new IllegalArgumentException("Invalid HTTP status");
            bytes = Objects.requireNonNull(bytes).clone();
        }

        @Override public byte[] bytes() { return bytes.clone(); }
    }

    private record RemoteAcquisition(
            Optional<PomInput> input,
            Optional<MavenBuildModelResolution.AcquiredPom> artifact,
            List<MavenBuildModelResolution.Attempt> attempts,
            List<MavenBuildModelResolution.Problem> problems) {}

    static final class RemoteLimitExceeded extends Exception {}

    private static final class JdkRemotePomTransport implements RemotePomTransport {
        @Override
        public RemoteResponse fetch(URI uri, long maxBytes, int connectTimeoutMillis, int requestTimeoutMillis)
                throws IOException, InterruptedException, RemoteLimitExceeded {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(requestTimeoutMillis))
                    .header("Accept", "application/xml,text/xml,application/octet-stream")
                    .GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                OptionalLong declared = response.headers().firstValueAsLong("Content-Length");
                if (declared.isPresent() && declared.getAsLong() > maxBytes) throw new RemoteLimitExceeded();
                return new RemoteResponse(response.statusCode(), readBounded(input, maxBytes));
            }
        }

        private static byte[] readBounded(InputStream input, long maxBytes) throws IOException, RemoteLimitExceeded {
            var output = new ByteArrayOutputStream((int) Math.min(8192, maxBytes));
            byte[] buffer = new byte[8192];
            long count = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                count += read;
                if (count > maxBytes) throw new RemoteLimitExceeded();
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }
}
