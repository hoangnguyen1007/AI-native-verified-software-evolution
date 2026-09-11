package com.evolution.analysis.maven;

import com.evolution.analysis.classpath.ArtifactCoordinate;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.dependency.*;
import com.evolution.analysis.dependency.DependencyAcquisitionResult.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Bounded credential-free HTTPS acquisition into one explicitly selected standard Maven2 cache.
 * Target repositories, settings, mirrors, metadata search, credentials and lifecycle execution are absent.
 */
public final class MavenDependencyArtifactProvider implements DependencyArtifactProvider {
    public static final VersionedIdentifier VERSION =
            new VersionedIdentifier("dependency.artifact-cache", "m3.8");
    private static final int MAX_ARCHIVE_ENTRIES = 1_000_000;
    private static final long MAX_EXPANDED_ARCHIVE_BYTES = 1_000_000_000L;

    private final Path selectedCacheRoot;
    private final RemoteArtifactTransport remoteTransport;
    private final RetryDelay retryDelay;

    public MavenDependencyArtifactProvider(Path selectedCacheRoot) {
        this(selectedCacheRoot, new JdkRemoteArtifactTransport(), Thread::sleep);
    }

    MavenDependencyArtifactProvider(
            Path selectedCacheRoot, RemoteArtifactTransport remoteTransport, RetryDelay retryDelay) {
        this.selectedCacheRoot = Objects.requireNonNull(selectedCacheRoot);
        this.remoteTransport = Objects.requireNonNull(remoteTransport);
        this.retryDelay = Objects.requireNonNull(retryDelay);
    }

    @Override
    public DependencyAcquisitionResult acquire(DependencyAcquisitionRequest request) {
        Objects.requireNonNull(request);
        List<Outcome> outcomes = new ArrayList<>();
        List<Attempt> attempts = new ArrayList<>();
        AttemptCounter counter = new AttemptCounter();
        Path root;
        try {
            root = validateRoot(selectedCacheRoot);
        } catch (AcquisitionFailure failure) {
            for (DependencyAcquisitionRequest.Requirement requirement : request.requirements()) {
                ArtifactCoordinate coordinate = requirement.coordinate();
                attempts.add(attempt(counter, coordinate, Origin.LOCAL_CACHE, coordinate.repositoryPath(),
                        attemptOutcome(failure.reason), Optional.empty()));
                outcomes.add(failed(requirement, failure.reason));
            }
            return DependencyAcquisitionResult.create(request, VERSION, outcomes, attempts, 0);
        }

        Budget budget = new Budget(request.policy());
        for (DependencyAcquisitionRequest.Requirement requirement : request.requirements()) {
            ArtifactCoordinate coordinate = requirement.coordinate();
            if (coordinate.gav().version().endsWith("-SNAPSHOT")) {
                outcomes.add(failed(requirement, FailureReason.SNAPSHOT_UNSUPPORTED));
                continue;
            }
            try {
                Optional<Artifact> cached = readCached(root, requirement, budget, attempts, counter);
                if (cached.isPresent()) {
                    outcomes.add(success(requirement, cached.orElseThrow()));
                    continue;
                }
                outcomes.add(acquireRemote(root, requirement, request.policy(), budget, attempts, counter));
            } catch (AcquisitionFailure failure) {
                outcomes.add(failed(requirement, failure.reason));
            }
        }
        return DependencyAcquisitionResult.create(request, VERSION, outcomes, attempts, budget.consumed());
    }

    private Optional<Artifact> readCached(
            Path root,
            DependencyAcquisitionRequest.Requirement requirement,
            Budget budget,
            List<Attempt> attempts,
            AttemptCounter counter) throws AcquisitionFailure {
        ArtifactCoordinate coordinate = requirement.coordinate();
        String relative = coordinate.repositoryPath();
        Path path = resolve(root, relative);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            attempts.add(attempt(counter, coordinate, Origin.LOCAL_CACHE, relative,
                    AttemptOutcome.UNAVAILABLE, Optional.empty()));
            return Optional.empty();
        }
        try {
            if (Files.isSymbolicLink(path)) throw failure(FailureReason.SYMBOLIC_LINK);
            BasicFileAttributes before = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Path real = path.toRealPath();
            if (!real.startsWith(root)) throw failure(FailureReason.PATH_OUTSIDE_CACHE);
            if (!real.equals(path.toAbsolutePath().normalize())) throw failure(FailureReason.SYMBOLIC_LINK);
            if (!before.isRegularFile()) throw failure(FailureReason.NON_REGULAR_ARTIFACT);
            if (before.size() < 1 || before.size() > budget.policy.maxArtifactBytes()
                    || before.size() > budget.remaining()) {
                throw failure(FailureReason.SIZE_EXCEEDED);
            }
            byte[] bytes = readBounded(path, Math.min(budget.policy.maxArtifactBytes(), budget.remaining()));
            BasicFileAttributes after = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (Files.isSymbolicLink(path) || !after.isRegularFile()
                    || before.size() != after.size()
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || before.fileKey() != null && after.fileKey() != null
                            && !before.fileKey().equals(after.fileKey())) {
                throw failure(FailureReason.CACHE_CHANGED_DURING_READ);
            }
            budget.consume(bytes.length);
            ContentDigest digest = ContentDigest.sha256(bytes);
            if (requirement.expectedDigest().isPresent()
                    && !requirement.expectedDigest().orElseThrow().equals(digest)) {
                throw failure(FailureReason.DIGEST_MISMATCH);
            }
            validate(coordinate, bytes, FailureReason.CORRUPTED_CACHE);
            Artifact artifact = new Artifact(coordinate, Origin.LOCAL_CACHE, relative, digest, bytes.length);
            attempts.add(attempt(counter, coordinate, Origin.LOCAL_CACHE, relative,
                    AttemptOutcome.SUCCEEDED, Optional.of(digest)));
            return Optional.of(artifact);
        } catch (AcquisitionFailure failure) {
            attempts.add(attempt(counter, coordinate, Origin.LOCAL_CACHE, relative,
                    attemptOutcome(failure.reason), Optional.empty()));
            throw failure;
        } catch (IOException | SecurityException exception) {
            attempts.add(attempt(counter, coordinate, Origin.LOCAL_CACHE, relative,
                    AttemptOutcome.FAILED, Optional.empty()));
            throw failure(FailureReason.CACHE_READ_FAILED);
        } catch (ReadLimitExceeded exception) {
            attempts.add(attempt(counter, coordinate, Origin.LOCAL_CACHE, relative,
                    AttemptOutcome.LIMIT_EXCEEDED, Optional.empty()));
            throw failure(FailureReason.SIZE_EXCEEDED);
        }
    }

    private Outcome acquireRemote(
            Path root,
            DependencyAcquisitionRequest.Requirement requirement,
            DependencyAcquisitionPolicy policy,
            Budget budget,
            List<Attempt> attempts,
            AttemptCounter counter) {
        ArtifactCoordinate coordinate = requirement.coordinate();
        FailureReason lastFailure = FailureReason.ARTIFACT_NOT_FOUND;
        for (URI repository : policy.remoteRepositories()) {
            URI uri = repository.resolve(coordinate.repositoryPath());
            for (int retry = 0; retry <= policy.maxRetries(); retry++) {
                boolean mayRetry = retry < policy.maxRetries();
                try {
                    long allowed = Math.min(policy.maxArtifactBytes(), budget.remaining());
                    if (allowed < 1) throw new RemoteLimitExceeded();
                    RemoteResponse response = remoteTransport.fetch(
                            uri, allowed, policy.connectTimeoutMillis(), policy.readTimeoutMillis());
                    byte[] bytes = response.bytes();
                    if (bytes.length > allowed) throw new RemoteLimitExceeded();
                    budget.consume(bytes.length);

                    if (response.statusCode() == 404 || response.statusCode() == 410) {
                        attempts.add(attempt(counter, coordinate, Origin.REMOTE_REPOSITORY,
                                uri.toASCIIString(), AttemptOutcome.UNAVAILABLE, Optional.empty()));
                        lastFailure = FailureReason.ARTIFACT_NOT_FOUND;
                        break;
                    }
                    if (response.statusCode() == 401 || response.statusCode() == 403) {
                        attempts.add(attempt(counter, coordinate, Origin.REMOTE_REPOSITORY,
                                uri.toASCIIString(), AttemptOutcome.DENIED, Optional.empty()));
                        lastFailure = FailureReason.CREDENTIALS_REQUIRED;
                        break;
                    }
                    if (response.statusCode() >= 300 && response.statusCode() < 400) {
                        attempts.add(attempt(counter, coordinate, Origin.REMOTE_REPOSITORY,
                                uri.toASCIIString(), AttemptOutcome.DENIED, Optional.empty()));
                        lastFailure = FailureReason.REMOTE_RESPONSE_DENIED;
                        break;
                    }
                    if (response.statusCode() != 200) {
                        attempts.add(attempt(counter, coordinate, Origin.REMOTE_REPOSITORY,
                                uri.toASCIIString(), AttemptOutcome.FAILED, Optional.empty()));
                        lastFailure = FailureReason.NETWORK_FAILURE;
                        if (mayRetry && response.statusCode() >= 500) {
                            if (!pause(retry)) return failed(requirement, FailureReason.FETCH_TIMEOUT);
                            continue;
                        }
                        break;
                    }

                    ContentDigest digest = ContentDigest.sha256(bytes);
                    if (requirement.expectedDigest().isPresent()
                            && !requirement.expectedDigest().orElseThrow().equals(digest)) {
                        attempts.add(attempt(counter, coordinate, Origin.REMOTE_REPOSITORY,
                                uri.toASCIIString(), AttemptOutcome.FAILED, Optional.empty()));
                        lastFailure = FailureReason.DIGEST_MISMATCH;
                        break;
                    }
                    try {
                        validate(coordinate, bytes, FailureReason.CORRUPTED_DOWNLOAD);
                    } catch (AcquisitionFailure failure) {
                        attempts.add(attempt(counter, coordinate, Origin.REMOTE_REPOSITORY,
                                uri.toASCIIString(), AttemptOutcome.FAILED, Optional.empty()));
                        lastFailure = failure.reason;
                        if (mayRetry) {
                            if (!pause(retry)) return failed(requirement, FailureReason.FETCH_TIMEOUT);
                            continue;
                        }
                        break;
                    }

                    Artifact artifact;
                    try {
                        artifact = promote(root, coordinate, bytes, digest);
                    } catch (AcquisitionFailure failure) {
                        attempts.add(attempt(counter, coordinate, Origin.REMOTE_REPOSITORY,
                                uri.toASCIIString(), attemptOutcome(failure.reason), Optional.empty()));
                        return failed(requirement, failure.reason);
                    }
                    attempts.add(attempt(counter, coordinate, Origin.REMOTE_REPOSITORY,
                            uri.toASCIIString(), AttemptOutcome.SUCCEEDED, Optional.of(digest)));
                    return success(requirement, artifact);
                } catch (RemoteLimitExceeded | ReadLimitExceeded exception) {
                    attempts.add(attempt(counter, coordinate, Origin.REMOTE_REPOSITORY,
                            uri.toASCIIString(), AttemptOutcome.LIMIT_EXCEEDED, Optional.empty()));
                    return failed(requirement, FailureReason.SIZE_EXCEEDED);
                } catch (HttpTimeoutException exception) {
                    attempts.add(attempt(counter, coordinate, Origin.REMOTE_REPOSITORY,
                            uri.toASCIIString(), AttemptOutcome.FAILED, Optional.empty()));
                    lastFailure = FailureReason.FETCH_TIMEOUT;
                    if (mayRetry) {
                        if (!pause(retry)) return failed(requirement, FailureReason.FETCH_TIMEOUT);
                        continue;
                    }
                    break;
                } catch (IOException exception) {
                    attempts.add(attempt(counter, coordinate, Origin.REMOTE_REPOSITORY,
                            uri.toASCIIString(), AttemptOutcome.FAILED, Optional.empty()));
                    lastFailure = FailureReason.NETWORK_FAILURE;
                    if (mayRetry) {
                        if (!pause(retry)) return failed(requirement, FailureReason.FETCH_TIMEOUT);
                        continue;
                    }
                    break;
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    attempts.add(attempt(counter, coordinate, Origin.REMOTE_REPOSITORY,
                            uri.toASCIIString(), AttemptOutcome.FAILED, Optional.empty()));
                    return failed(requirement, FailureReason.FETCH_TIMEOUT);
                }
            }
        }
        return failed(requirement, lastFailure);
    }

    private Artifact promote(Path root, ArtifactCoordinate coordinate, byte[] bytes, ContentDigest digest)
            throws AcquisitionFailure {
        String relative = coordinate.repositoryPath();
        Path target = resolve(root, relative);
        Path parent = ensureParent(root, target.getParent());
        Path temporary = null;
        try {
            temporary = Files.createTempFile(parent, "." + target.getFileName() + ".m3.8-", ".tmp");
            if (Files.isSymbolicLink(temporary)) throw failure(FailureReason.CACHE_WRITE_FAILED);
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            byte[] written = readBounded(temporary, bytes.length);
            if (!digest.equals(ContentDigest.sha256(written))) throw failure(FailureReason.CACHE_WRITE_FAILED);
            validate(coordinate, written, FailureReason.CORRUPTED_DOWNLOAD);
            ensureParent(root, target.getParent());
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            temporary = null;
            return new Artifact(coordinate, Origin.REMOTE_REPOSITORY, relative, digest, bytes.length);
        } catch (FileAlreadyExistsException exception) {
            throw failure(FailureReason.CACHE_CHANGED_DURING_READ);
        } catch (AtomicMoveNotSupportedException exception) {
            throw failure(FailureReason.CACHE_WRITE_FAILED);
        } catch (ReadLimitExceeded | IOException | SecurityException exception) {
            throw failure(FailureReason.CACHE_WRITE_FAILED);
        } finally {
            if (temporary != null) {
                try {
                    Path normalized = temporary.toAbsolutePath().normalize();
                    if (normalized.startsWith(root) && Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
                        Files.deleteIfExists(normalized);
                    }
                } catch (IOException | SecurityException ignored) {
                    // The exact provider-owned temp file may remain visible after a failed cleanup.
                }
            }
        }
    }

    private static Path ensureParent(Path root, Path requestedParent) throws AcquisitionFailure {
        Path parent = requestedParent.toAbsolutePath().normalize();
        if (!parent.startsWith(root)) throw failure(FailureReason.PATH_OUTSIDE_CACHE);
        Path current = root;
        for (Path segment : root.relativize(parent)) {
            Path next = current.resolve(segment).toAbsolutePath().normalize();
            try {
                if (!Files.exists(next, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        Files.createDirectory(next);
                    } catch (FileAlreadyExistsException ignored) {
                        // A concurrent creator must still pass the checks below.
                    }
                }
                if (Files.isSymbolicLink(next)) throw failure(FailureReason.SYMBOLIC_LINK);
                BasicFileAttributes attributes = Files.readAttributes(
                        next, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isDirectory()) throw failure(FailureReason.NON_REGULAR_ARTIFACT);
                Path real = next.toRealPath();
                if (!real.startsWith(root)) throw failure(FailureReason.PATH_OUTSIDE_CACHE);
                if (!real.equals(next)) throw failure(FailureReason.SYMBOLIC_LINK);
                current = real;
            } catch (AcquisitionFailure failure) {
                throw failure;
            } catch (IOException | SecurityException exception) {
                throw failure(FailureReason.CACHE_WRITE_FAILED);
            }
        }
        return current;
    }

    private static void validate(
            ArtifactCoordinate coordinate, byte[] bytes, FailureReason corruptReason) throws AcquisitionFailure {
        if (bytes.length < 1) throw failure(corruptReason);
        if (coordinate.extension().equals("pom")) {
            try {
                SecurePomReader.read(bytes);
            } catch (SecurePomReader.Rejected rejected) {
                throw failure(FailureReason.INVALID_POM);
            }
            return;
        }
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K' || bytes[2] != 3 || bytes[3] != 4) {
            throw failure(corruptReason);
        }
        int entries = 0;
        long expanded = 0;
        byte[] buffer = new byte[8192];
        try (var archive = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                if (++entries > MAX_ARCHIVE_ENTRIES) throw failure(corruptReason);
                int read;
                while ((read = archive.read(buffer)) >= 0) {
                    expanded += read;
                    if (expanded > MAX_EXPANDED_ARCHIVE_BYTES) throw failure(corruptReason);
                }
            }
            if (entries == 0) throw failure(corruptReason);
        } catch (ZipException exception) {
            throw failure(corruptReason);
        } catch (IOException exception) {
            throw failure(corruptReason);
        }
    }

    private boolean pause(int retry) {
        try {
            retryDelay.pause(100L << retry);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Path resolve(Path root, String relative) throws AcquisitionFailure {
        Path path = root.resolve(relative.replace('/', java.io.File.separatorChar)).toAbsolutePath().normalize();
        if (!path.startsWith(root)) throw failure(FailureReason.PATH_OUTSIDE_CACHE);
        return path;
    }

    private static Path validateRoot(Path selectedRoot) throws AcquisitionFailure {
        Path absolute = selectedRoot.toAbsolutePath().normalize();
        if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(FailureReason.CACHE_ROOT_NOT_FOUND);
        }
        if (Files.isSymbolicLink(absolute)) throw failure(FailureReason.CACHE_ROOT_SYMBOLIC_LINK);
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory()) throw failure(FailureReason.CACHE_ROOT_NOT_DIRECTORY);
            Path real = absolute.toRealPath();
            if (!absolute.equals(real)) throw failure(FailureReason.CACHE_ROOT_SYMBOLIC_LINK);
            return real;
        } catch (AcquisitionFailure failure) {
            throw failure;
        } catch (IOException | SecurityException exception) {
            throw failure(FailureReason.CACHE_ROOT_READ_FAILED);
        }
    }

    private static byte[] readBounded(Path path, long maximum) throws IOException, ReadLimitExceeded {
        try (SeekableByteChannel channel = Files.newByteChannel(
                path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            var output = new ByteArrayOutputStream((int) Math.min(8192, maximum));
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            long count = 0;
            while (channel.read(buffer) >= 0) {
                buffer.flip();
                int amount = buffer.remaining();
                count += amount;
                if (count > maximum) throw new ReadLimitExceeded();
                output.write(buffer.array(), buffer.position(), amount);
                buffer.clear();
            }
            return output.toByteArray();
        }
    }

    private static Outcome success(
            DependencyAcquisitionRequest.Requirement requirement, Artifact artifact) {
        Status status = requirement.coordinate().extension().equals("pom")
                ? Status.SKIPPED_POM
                : artifact.origin() == Origin.LOCAL_CACHE ? Status.CACHED : Status.ACQUIRED;
        return new Outcome(requirement, status, Optional.of(artifact), Optional.empty());
    }

    private static Outcome failed(
            DependencyAcquisitionRequest.Requirement requirement, FailureReason reason) {
        return new Outcome(requirement, Status.FAILED, Optional.empty(), Optional.of(reason));
    }

    private static Attempt attempt(
            AttemptCounter counter,
            ArtifactCoordinate coordinate,
            Origin origin,
            String subject,
            AttemptOutcome outcome,
            Optional<ContentDigest> digest) {
        return new Attempt(counter.next(), coordinate, origin, subject, outcome, digest);
    }

    private static AttemptOutcome attemptOutcome(FailureReason reason) {
        return switch (reason) {
            case CACHE_ROOT_NOT_FOUND, ARTIFACT_NOT_FOUND -> AttemptOutcome.UNAVAILABLE;
            case CACHE_ROOT_SYMBOLIC_LINK, PATH_OUTSIDE_CACHE, SYMBOLIC_LINK,
                    NON_REGULAR_ARTIFACT, REMOTE_RESPONSE_DENIED, CREDENTIALS_REQUIRED -> AttemptOutcome.DENIED;
            case SIZE_EXCEEDED -> AttemptOutcome.LIMIT_EXCEEDED;
            default -> AttemptOutcome.FAILED;
        };
    }

    private static AcquisitionFailure failure(FailureReason reason) {
        return new AcquisitionFailure(reason);
    }

    interface RemoteArtifactTransport {
        RemoteResponse fetch(URI uri, long maxBytes, int connectTimeoutMillis, int readTimeoutMillis)
                throws IOException, InterruptedException, RemoteLimitExceeded;
    }

    interface RetryDelay {
        void pause(long millis) throws InterruptedException;
    }

    record RemoteResponse(int statusCode, byte[] bytes) {
        RemoteResponse {
            if (statusCode < 100 || statusCode > 599) throw new IllegalArgumentException("Invalid HTTP status");
            bytes = Objects.requireNonNull(bytes).clone();
        }

        @Override public byte[] bytes() { return bytes.clone(); }
    }

    static final class RemoteLimitExceeded extends Exception {}

    private static final class JdkRemoteArtifactTransport implements RemoteArtifactTransport {
        @Override
        public RemoteResponse fetch(URI uri, long maxBytes, int connectTimeoutMillis, int readTimeoutMillis)
                throws IOException, InterruptedException, RemoteLimitExceeded {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(readTimeoutMillis))
                    .header("Accept", "application/java-archive,application/xml,text/xml,application/octet-stream")
                    .GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                OptionalLong declared = response.headers().firstValueAsLong("Content-Length");
                if (declared.isPresent() && declared.getAsLong() > maxBytes) throw new RemoteLimitExceeded();
                return new RemoteResponse(response.statusCode(), readRemote(input, maxBytes));
            }
        }

        private static byte[] readRemote(InputStream input, long maxBytes)
                throws IOException, RemoteLimitExceeded {
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

    private static final class Budget {
        private final DependencyAcquisitionPolicy policy;
        private long consumed;

        private Budget(DependencyAcquisitionPolicy policy) { this.policy = policy; }
        private long remaining() { return policy.maxTotalBytes() - consumed; }
        private long consumed() { return consumed; }
        private void consume(long bytes) throws ReadLimitExceeded {
            if (bytes < 0 || bytes > remaining()) throw new ReadLimitExceeded();
            consumed += bytes;
        }
    }

    private static final class AttemptCounter {
        private int value;
        private int next() { return value++; }
    }

    private static final class AcquisitionFailure extends Exception {
        private final FailureReason reason;
        private AcquisitionFailure(FailureReason reason) { super(reason.name()); this.reason = reason; }
    }

    private static final class ReadLimitExceeded extends Exception {}
}
