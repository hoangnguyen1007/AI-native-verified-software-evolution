package com.evolution.analysis.maven;

import com.evolution.analysis.classpath.ArtifactCoordinate;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.dependency.*;
import com.evolution.analysis.dependency.DependencyAcquisitionResult.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import java.util.concurrent.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Bounded credential-free HTTPS acquisition into one explicitly selected standard Maven2 cache.
 * Target repositories, settings, mirrors, metadata search, credentials and lifecycle execution are absent.
 */
public final class MavenDependencyArtifactProvider implements DependencyArtifactProvider {
    public static final VersionedIdentifier VERSION =
            new VersionedIdentifier("dependency.artifact-cache", "m3.9-parallel");
    private static final JdkRemoteArtifactTransport SHARED_TRANSPORT = new JdkRemoteArtifactTransport();
    private static final int MAX_ARCHIVE_ENTRIES = 1_000_000;
    private static final long MAX_EXPANDED_ARCHIVE_BYTES = 1_000_000_000L;

    private final Path selectedCacheRoot;
    private final RemoteArtifactTransport remoteTransport;
    private final RetryDelay retryDelay;

    public MavenDependencyArtifactProvider(Path selectedCacheRoot) {
        this(selectedCacheRoot, SHARED_TRANSPORT, Thread::sleep);
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

        if(request.requirements().size()==1||request.policy().maxConcurrentDownloads()==1) {
            long consumed=0;
            for(var requirement:request.requirements()) {
                var result=Thread.currentThread().isInterrupted()
                        ?new TaskResult(failed(requirement,FailureReason.FETCH_TIMEOUT),List.of(),0)
                        :acquireOne(root,requirement,request.policy(),request.policy().maxTotalBytes()-consumed);
                outcomes.add(result.outcome());consumed+=result.consumed();
                appendAttempts(attempts,counter,result.attempts());
            }
            return DependencyAcquisitionResult.create(request,VERSION,outcomes,attempts,consumed);
        }
        // Reserve each task's worst-case transfer before dispatch. Commit in input order, not
        // completion order: timing cannot change byte admission, failures or ledger ordinals.
        long perTask = request.policy().maxArtifactBytes() * (request.policy().maxRetries()+1L)
                * Math.max(1,request.policy().remoteRepositories().size());
        long consumed=0,reserved=0;int next=0;boolean interrupted=Thread.interrupted();
        var pending=new ArrayDeque<Pending>();var activeCoordinates=new HashSet<ArtifactCoordinate>();
        var executor=Executors.newFixedThreadPool(request.policy().maxConcurrentDownloads());
        try {
            while(next<request.requirements().size()||!pending.isEmpty()) {
                while(!interrupted&&next<request.requirements().size()&&pending.size()<request.policy().maxConcurrentDownloads()) {
                    var requirement=request.requirements().get(next);
                    long available=request.policy().maxTotalBytes()-consumed-reserved;
                    if(!pending.isEmpty()&&(available<perTask||activeCoordinates.contains(requirement.coordinate())))break;
                    long allowance=Math.min(available,perTask);
                    pending.addLast(new Pending(requirement,allowance,executor.submit(()->acquireOne(root,requirement,request.policy(),allowance))));
                    activeCoordinates.add(requirement.coordinate());reserved+=allowance;next++;
                }
                if(pending.isEmpty()) {
                    while(next<request.requirements().size())outcomes.add(failed(request.requirements().get(next++),FailureReason.FETCH_TIMEOUT));
                    break;
                }
                var task=pending.removeFirst();TaskResult result;
                for(;;)try {result=task.future().get();break;}
                catch(InterruptedException e){interrupted=true;/* Drain accepted work before returning a closed ledger. */}
                catch(ExecutionException e){throw new IllegalStateException("Artifact acquisition task failed unexpectedly",e.getCause());}
                consumed+=result.consumed();reserved-=task.allowance();activeCoordinates.remove(task.requirement().coordinate());
                outcomes.add(result.outcome());
                appendAttempts(attempts,counter,result.attempts());
            }
        } finally {
            executor.shutdownNow();if(interrupted)Thread.currentThread().interrupt();
        }
        return DependencyAcquisitionResult.create(request, VERSION, outcomes, attempts, consumed);
    }

    private static void appendAttempts(List<Attempt> destination,AttemptCounter counter,List<Attempt> source) {
        for(var attempt:source)destination.add(new Attempt(counter.next(),attempt.coordinate(),attempt.origin(),attempt.subject(),attempt.outcome(),attempt.contentDigest()));
    }

    private record Pending(DependencyAcquisitionRequest.Requirement requirement,long allowance,Future<TaskResult> future) {}
    private record TaskResult(Outcome outcome,List<Attempt> attempts,long consumed) {}
    private TaskResult acquireOne(Path root,DependencyAcquisitionRequest.Requirement requirement,DependencyAcquisitionPolicy policy,long allowance) {
        var attempts=new ArrayList<Attempt>();var counter=new AttemptCounter();
        if(requirement.coordinate().gav().version().endsWith("-SNAPSHOT"))return new TaskResult(failed(requirement,FailureReason.SNAPSHOT_UNSUPPORTED),attempts,0);
        if(allowance==0)return new TaskResult(failed(requirement,FailureReason.SIZE_EXCEEDED),attempts,0);
        var budget=new Budget(policy.withLimits(1,allowance));Outcome outcome;
        try {
            var cached=readCached(root,requirement,budget,attempts,counter);
            outcome=cached.isPresent()?success(requirement,cached.orElseThrow()):acquireRemote(root,requirement,policy,budget,attempts,counter);
        }catch(AcquisitionFailure failure){outcome=failed(requirement,failure.reason);}
        return new TaskResult(outcome,attempts,budget.consumed());
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

    static final class JdkRemoteArtifactTransport implements RemoteArtifactTransport {
        private final Map<Integer,HttpClient> clients=new HashMap<>();
        synchronized ClientLease lease(int timeout) {
            HttpClient existing=clients.get(timeout);
            if(existing!=null)return new ClientLease(existing,true);
            var client=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeout)).followRedirects(HttpClient.Redirect.NEVER).build();
            boolean pooled=clients.size()<16;if(pooled)clients.put(timeout,client);
            return new ClientLease(client,pooled);
        }
        record ClientLease(HttpClient client,boolean pooled) implements AutoCloseable {
            public void close(){if(!pooled)client.shutdown();}
        }
        @Override
        public RemoteResponse fetch(URI uri, long maxBytes, int connectTimeoutMillis, int readTimeoutMillis)
                throws IOException, InterruptedException, RemoteLimitExceeded {
            try(var lease=lease(connectTimeoutMillis)) {
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofMillis(readTimeoutMillis))
                        .header("Accept", "application/java-archive,application/xml,text/xml,application/octet-stream")
                        .GET().build();
                var future=lease.client().sendAsync(request,info->{
                    var body=new BoundedBodySubscriber(maxBytes);
                    if(info.headers().firstValueAsLong("Content-Length").orElse(0)>maxBytes)body.rejectOversized();
                    return body;
                });
                try {
                    var response=future.get(readTimeoutMillis,TimeUnit.MILLISECONDS);
                    return new RemoteResponse(response.statusCode(),response.body());
                }catch(TimeoutException e){throw new HttpTimeoutException("Artifact response deadline exceeded");}
                catch(ExecutionException e){
                    if(e.getCause() instanceof RemoteLimitExceeded limit)throw limit;
                    if(e.getCause() instanceof IOException io)throw io;
                    throw new IOException("Artifact HTTP transport failed",e.getCause());
                }finally{future.cancel(true);}
            }
        }
    }

    /** Back pressure and the byte cap cover the entire response, including chunked bodies. */
    static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final long maximum;private long count;
        private final ByteArrayOutputStream output=new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body=new CompletableFuture<>();
        private Flow.Subscription subscription;
        BoundedBodySubscriber(long maximum){this.maximum=maximum;}
        void rejectOversized(){body.completeExceptionally(new RemoteLimitExceeded());}
        public CompletionStage<byte[]> getBody(){return body;}
        public void onSubscribe(Flow.Subscription value){
            if(subscription!=null||body.isDone()){value.cancel();return;}subscription=value;value.request(1);
        }
        public void onNext(List<ByteBuffer> buffers){
            if(body.isDone())return;
            for(var buffer:buffers){
                if(buffer.remaining()>maximum-count){subscription.cancel();body.completeExceptionally(new RemoteLimitExceeded());return;}
                int amount=buffer.remaining();count+=amount;
                byte[] bytes=new byte[amount];buffer.get(bytes);output.writeBytes(bytes);
            }
            subscription.request(1);
        }
        public void onError(Throwable failure){body.completeExceptionally(failure);}
        public void onComplete(){body.complete(output.toByteArray());}
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
