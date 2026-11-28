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
    public static final long DEFAULT_MAX_TOTAL_BYTES = 2_147_483_648L;
    private static final JdkRemoteArtifactTransport SHARED_TRANSPORT = new JdkRemoteArtifactTransport();
    private static final int MAX_ARCHIVE_ENTRIES = 1_000_000;
    private static final long MAX_EXPANDED_ARCHIVE_BYTES = 1_000_000_000L;
    private static final int VALIDATED_CONTENT_CACHE_MAX = 8192;
    private static final long NEGATIVE_404_TTL_MILLIS = 24L * 60 * 60 * 1000;
    private static final long NEGATIVE_DENIED_TTL_MILLIS = 15L * 60 * 1000;
    private static final int NEGATIVE_404_CACHE_MAX = 8192;
    private static final Map<ValidatedContentKey, Boolean> VALIDATED_CONTENT_CACHE =
            Collections.synchronizedMap(new LinkedHashMap<>(512, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<ValidatedContentKey, Boolean> eldest) {
                    return size() > VALIDATED_CONTENT_CACHE_MAX;
                }
            });
    private static final Set<Path> LOADED_VALIDATION_ROOTS = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> NEGATIVE_404_CACHE =
            Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > NEGATIVE_404_CACHE_MAX;
                }
            });
    private static final Map<String, Long> NEGATIVE_DENIED_CACHE =
            Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > NEGATIVE_404_CACHE_MAX;
                }
            });
    private static final Set<Path> LOADED_NEGATIVE_ROOTS = ConcurrentHashMap.newKeySet();
    private static final Object INDEX_FILE_LOCK = new Object();
    static final java.util.concurrent.atomic.AtomicLong VALIDATED_CONTENT_CACHE_HITS = new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong NEGATIVE_404_CACHE_HITS = new java.util.concurrent.atomic.AtomicLong();

    private record ValidatedContentKey(ContentDigest digest, String extension) {}

    private static boolean fastDepsEnabled() {
        return Boolean.parseBoolean(System.getProperty("evo.perf.fastDeps", "true"));
    }

    private static boolean persistedValidationEnabled() {
        return fastDepsEnabled()
                && Boolean.parseBoolean(System.getProperty("evo.perf.persistedArtifactValidation", "true"));
    }

    private static boolean negative404CacheEnabled() {
        return fastDepsEnabled()
                && Boolean.parseBoolean(System.getProperty("evo.perf.negative404Cache", "true"));
    }

    static boolean isValidatedArtifact(Path cacheRoot, ContentDigest digest, String extension) {
        var session = com.evolution.analysis.input.VerifiedArtifactSession.current();
        if (session.isPresent() && session.orElseThrow().isArchiveValidated(digest)) {
            VALIDATED_CONTENT_CACHE_HITS.incrementAndGet();
            return true;
        }
        if (cacheRoot != null && persistedValidationEnabled()) {
            ensureValidationIndexLoaded(cacheRoot);
        }
        if (Boolean.TRUE.equals(VALIDATED_CONTENT_CACHE.get(new ValidatedContentKey(digest, extension)))) {
            VALIDATED_CONTENT_CACHE_HITS.incrementAndGet();
            session.ifPresent(s -> s.markArchiveValidated(digest));
            return true;
        }
        return false;
    }

    static void markValidatedArtifact(Path cacheRoot, ContentDigest digest, String extension) {
        VALIDATED_CONTENT_CACHE.put(new ValidatedContentKey(digest, extension), Boolean.TRUE);
        com.evolution.analysis.input.VerifiedArtifactSession.current().ifPresent(s -> s.markArchiveValidated(digest));
        if (cacheRoot != null && persistedValidationEnabled()) {
            appendValidationReceipt(cacheRoot, digest, extension);
        }
    }

    private static void ensureValidationIndexLoaded(Path cacheRoot) {
        Path normalized = cacheRoot.toAbsolutePath().normalize();
        if (!LOADED_VALIDATION_ROOTS.add(normalized)) return;
        Path file = normalized.resolve(".evo-cache-index").resolve("validated-artifacts.v1");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            List<String> lines = Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8);
            for (String line : lines) {
                int space = line.indexOf(' ');
                if (space <= 0 || space + 1 >= line.length()) continue;
                String digestStr = line.substring(0, space);
                String ext = line.substring(space + 1);
                if (digestStr.startsWith("sha256:") && digestStr.length() == 71) {
                    VALIDATED_CONTENT_CACHE.put(new ValidatedContentKey(new ContentDigest(digestStr), ext), Boolean.TRUE);
                }
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // Disposable acceleration index; fall back cleanly to full archive validation.
        }
    }

    private static void appendValidationReceipt(Path cacheRoot, ContentDigest digest, String extension) {
        Path normalized = cacheRoot.toAbsolutePath().normalize();
        Path dir = normalized.resolve(".evo-cache-index");
        Path file = dir.resolve("validated-artifacts.v1");
        synchronized (INDEX_FILE_LOCK) {
            try {
                if (Files.isSymbolicLink(dir) || Files.isSymbolicLink(file)) return;
                Files.createDirectories(dir);
                String entry = digest.value() + " " + extension + "\n";
                Files.writeString(file, entry, java.nio.charset.StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            } catch (IOException | SecurityException ignored) {
                // Read-only caches or concurrent writers remain valid without persisted receipt.
            }
        }
    }

    private static boolean isNegative404Cached(Path cacheRoot, URI uri) {
        ensureNegative404Loaded(cacheRoot);
        Long ts = NEGATIVE_404_CACHE.get(uri.toASCIIString());
        if (ts == null) return false;
        if (System.currentTimeMillis() - ts > NEGATIVE_404_TTL_MILLIS) {
            NEGATIVE_404_CACHE.remove(uri.toASCIIString());
            return false;
        }
        NEGATIVE_404_CACHE_HITS.incrementAndGet();
        return true;
    }

    static void recordNegative404(Path cacheRoot, URI uri) {
        long now = System.currentTimeMillis();
        String key = uri.toASCIIString();
        NEGATIVE_404_CACHE.put(key, now);
        Path normalized = cacheRoot.toAbsolutePath().normalize();
        Path dir = normalized.resolve(".evo-cache-index");
        Path file = dir.resolve("negative-404.v1");
        synchronized (INDEX_FILE_LOCK) {
            try {
                if (Files.isSymbolicLink(dir) || Files.isSymbolicLink(file)) return;
                Files.createDirectories(dir);
                Files.writeString(file, now + " " + key + "\n", java.nio.charset.StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            } catch (IOException | SecurityException ignored) {
                // Best-effort acceleration cache only.
            }
        }
    }

    private static void ensureNegative404Loaded(Path cacheRoot) {
        Path normalized = cacheRoot.toAbsolutePath().normalize();
        if (!LOADED_NEGATIVE_ROOTS.add(normalized)) return;
        Path file = normalized.resolve(".evo-cache-index").resolve("negative-404.v1");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return;
        long cutoff = System.currentTimeMillis() - NEGATIVE_404_TTL_MILLIS;
        try {
            for (String line : Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8)) {
                int space = line.indexOf(' ');
                if (space <= 0 || space + 1 >= line.length()) continue;
                long ts = Long.parseLong(line.substring(0, space));
                if (ts >= cutoff) {
                    NEGATIVE_404_CACHE.put(line.substring(space + 1), ts);
                }
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // Disposable acceleration index.
        }
    }

    private static boolean isNegativeDeniedCached(URI uri) {
        Long ts = NEGATIVE_DENIED_CACHE.get(uri.toASCIIString());
        if (ts == null) return false;
        if (System.currentTimeMillis() - ts > NEGATIVE_DENIED_TTL_MILLIS) {
            NEGATIVE_DENIED_CACHE.remove(uri.toASCIIString());
            return false;
        }
        NEGATIVE_404_CACHE_HITS.incrementAndGet();
        return true;
    }

    static void recordNegativeDenied(URI uri) {
        NEGATIVE_DENIED_CACHE.put(uri.toASCIIString(), System.currentTimeMillis());
    }

    /**
     * Returns true when every configured remote repository URI for {@code coordinate} is already
     * covered by the deterministic negative-404/denied cache for {@code cacheRoot}.
     */
    public static boolean isNegativelyCachedAcrossAllRepositories(
            Path cacheRoot, ArtifactCoordinate coordinate, List<URI> remoteRepositories) {
        if (cacheRoot == null || coordinate == null || remoteRepositories == null || remoteRepositories.isEmpty()) {
            return false;
        }
        if (!negative404CacheEnabled()) {
            return false;
        }
        String rel = coordinate.repositoryPath();
        for (URI repository : remoteRepositories) {
            URI uri = repository.resolve(rel);
            if (!isNegative404Cached(cacheRoot, uri) && !isNegativeDeniedCached(uri)) {
                return false;
            }
        }
        return true;
    }

    public static DependencyAcquisitionPolicy defaultPolicy(List<URI> remoteRepositories) {
        return new DependencyAcquisitionPolicy(4096, 64_000_000L, DEFAULT_MAX_TOTAL_BYTES, 64,
                remoteRepositories, 5_000, 15_000, 1, 16);
    }

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
        // Reserve each task's byte allowance before dispatch. Commit in input order, not
        // completion order: timing cannot change byte admission, failures or ledger ordinals.
        // Avoid serializing concurrent downloads when maxArtifactBytes * (retries+1) * repos >= maxTotalBytes
        // while keeping explicit small-budget limits strictly bounded.
        long perTask = taskAllowance(request.policy());
        long consumed = 0, reserved = 0;
        int next = 0, nextCommit = 0, inFlight = 0;
        boolean interrupted = Thread.interrupted();
        var completedByIndex = new TreeMap<Integer, CompletedTask>();
        var activeCoordinates = new HashSet<ArtifactCoordinate>();
        var activeSession = com.evolution.analysis.input.VerifiedArtifactSession.current().orElse(null);
        var executor = fastDepsEnabled()
                ? Executors.newVirtualThreadPerTaskExecutor()
                : Executors.newFixedThreadPool(request.policy().maxConcurrentDownloads());
        var completion = new ExecutorCompletionService<CompletedTask>(executor);
        try {
            while (next < request.requirements().size() || inFlight > 0) {
                while (!interrupted && next < request.requirements().size() && inFlight < request.policy().maxConcurrentDownloads()) {
                    var requirement = request.requirements().get(next);
                    long available = request.policy().maxTotalBytes() - consumed - reserved;
                    if (inFlight > 0 && (available < perTask || activeCoordinates.contains(requirement.coordinate()))) break;
                    long allowance = Math.min(available, perTask);
                    int taskIndex = next;
                    Callable<CompletedTask> work = () -> new CompletedTask(
                            taskIndex, requirement, allowance, acquireOne(root, requirement, request.policy(), allowance));
                    if (activeSession != null) work = activeSession.wrap(work);
                    completion.submit(work);
                    activeCoordinates.add(requirement.coordinate());
                    reserved += allowance;
                    inFlight++;
                    next++;
                }
                if (inFlight == 0) {
                    while (next < request.requirements().size()) {
                        outcomes.add(failed(request.requirements().get(next++), FailureReason.FETCH_TIMEOUT));
                    }
                    break;
                }
                CompletedTask done;
                for (;;) {
                    try {
                        done = completion.take().get();
                        break;
                    } catch (InterruptedException e) {
                        interrupted = true; /* Drain accepted work before returning a closed ledger. */
                    } catch (ExecutionException e) {
                        throw new IllegalStateException("Artifact acquisition task failed unexpectedly", e.getCause());
                    }
                }
                inFlight--;
                consumed += done.result().consumed();
                reserved -= done.allowance();
                completedByIndex.put(done.index(), done);
                while (completedByIndex.containsKey(nextCommit)) {
                    var ready = completedByIndex.remove(nextCommit++);
                    activeCoordinates.remove(ready.requirement().coordinate());
                    outcomes.add(ready.result().outcome());
                    appendAttempts(attempts, counter, ready.result().attempts());
                }
            }
        } finally {
            executor.shutdownNow();
            if (interrupted) Thread.currentThread().interrupt();
        }
        return DependencyAcquisitionResult.create(request, VERSION, outcomes, attempts, consumed);
    }

    private static long taskAllowance(DependencyAcquisitionPolicy policy) {
        long maxTotal = policy.maxTotalBytes();
        long maxArtifact = policy.maxArtifactBytes();
        int maxConcurrent = policy.maxConcurrentDownloads();
        long multiplier = (policy.maxRetries() + 1L) * Math.max(1, policy.remoteRepositories().size());
        long worstCase = maxArtifact > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : maxArtifact * multiplier;
        if (worstCase <= maxTotal / Math.max(1, maxConcurrent)) {
            return worstCase;
        }
        if (maxArtifact <= maxTotal / 2L) {
            long slots = Math.min(maxConcurrent, Math.max(2L, maxTotal / maxArtifact));
            long fairShare = maxTotal / slots;
            return Math.min(worstCase, Math.max(maxArtifact, fairShare));
        }
        return Math.min(worstCase, maxTotal);
    }

    private static void appendAttempts(List<Attempt> destination,AttemptCounter counter,List<Attempt> source) {
        for(var attempt:source)destination.add(new Attempt(counter.next(),attempt.coordinate(),attempt.origin(),attempt.subject(),attempt.outcome(),attempt.contentDigest()));
    }

    private record CompletedTask(int index, DependencyAcquisitionRequest.Requirement requirement, long allowance, TaskResult result) {}
    private record TaskResult(Outcome outcome,List<Attempt> attempts,long consumed) {}
    private TaskResult acquireOne(Path root,DependencyAcquisitionRequest.Requirement requirement,DependencyAcquisitionPolicy policy,long allowance) {
        var attempts=new ArrayList<Attempt>();var counter=new AttemptCounter();
        boolean isSnapshot = requirement.coordinate().gav().version().endsWith("-SNAPSHOT");
        if (isSnapshot) {
            try {
                Path candidate = resolve(root, requirement.coordinate().repositoryPath());
                if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    return new TaskResult(failed(requirement, FailureReason.SNAPSHOT_UNSUPPORTED), attempts, 0);
                }
            } catch (AcquisitionFailure failure) {
                return new TaskResult(failed(requirement, failure.reason), attempts, 0);
            }
        }
        if(allowance==0)return new TaskResult(failed(requirement,FailureReason.SIZE_EXCEEDED),attempts,0);
        var budget=new Budget(policy.withLimits(1,allowance));Outcome outcome;
        try {
            var cached=readCached(root,requirement,budget,attempts,counter);
            if (cached.isPresent()) {
                outcome = success(requirement, cached.orElseThrow());
            } else if (isSnapshot) {
                outcome = failed(requirement, FailureReason.SNAPSHOT_UNSUPPORTED);
            } else {
                outcome = acquireRemote(root, requirement, policy, budget, attempts, counter);
            }
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
            validateCachedOrFull(root, coordinate, digest, bytes, FailureReason.CORRUPTED_CACHE);
            com.evolution.analysis.input.VerifiedArtifactSession.current()
                    .ifPresent(s -> s.recordVerified(path, digest, bytes.length));
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
        List<URI> repos = policy.remoteRepositories();
        for (int repoIdx = 0; repoIdx < repos.size(); repoIdx++) {
            URI repository = repos.get(repoIdx);
            URI uri = repository.resolve(coordinate.repositoryPath());
            if (remoteTransport == SHARED_TRANSPORT && negative404CacheEnabled() && isNegative404Cached(root, uri)) {
                attempts.add(attempt(counter, coordinate, Origin.REMOTE_REPOSITORY,
                        uri.toASCIIString(), AttemptOutcome.UNAVAILABLE, Optional.empty()));
                lastFailure = FailureReason.ARTIFACT_NOT_FOUND;
                continue;
            }
            if (remoteTransport == SHARED_TRANSPORT && negative404CacheEnabled() && isNegativeDeniedCached(uri)) {
                attempts.add(attempt(counter, coordinate, Origin.REMOTE_REPOSITORY,
                        uri.toASCIIString(), AttemptOutcome.DENIED, Optional.empty()));
                lastFailure = FailureReason.CREDENTIALS_REQUIRED;
                continue;
            }
            // When primary repository (index 0) misses and multiple fallback repositories remain,
            // probe uncached fallback repositories in parallel on virtual threads and commit strictly
            // in policy.remoteRepositories() priority order so missing coordinates do not pay N serial RTTs.
            if (repoIdx == 1 && repos.size() > 2 && repos.size() <= 8 && fastDepsEnabled() && budget.remaining() > 0) {
                Outcome parallelOutcome = acquireFallbackReposParallel(
                        root, requirement, policy, repos.subList(1, repos.size()), budget, attempts, counter);
                if (parallelOutcome != null) {
                    return parallelOutcome;
                }
            }
            for (int retry = 0; retry <= policy.maxRetries(); retry++) {
                boolean mayRetry = retry < policy.maxRetries();
                try {
                    long allowed = Math.min(policy.maxArtifactBytes(), budget.remaining());
                    if (allowed < 1) throw new RemoteLimitExceeded();
                    RemoteResponse response = remoteTransport.fetch(
                            uri, allowed, policy.connectTimeoutMillis(), policy.readTimeoutMillis());
                    byte[] bytes = response.rawBytes();
                    if (bytes.length > allowed) throw new RemoteLimitExceeded();
                    budget.consume(bytes.length);

                    if (response.statusCode() == 404 || response.statusCode() == 410) {
                        if (remoteTransport == SHARED_TRANSPORT && negative404CacheEnabled()) {
                            recordNegative404(root, uri);
                        }
                        attempts.add(attempt(counter, coordinate, Origin.REMOTE_REPOSITORY,
                                uri.toASCIIString(), AttemptOutcome.UNAVAILABLE, Optional.empty()));
                        lastFailure = FailureReason.ARTIFACT_NOT_FOUND;
                        break;
                    }
                    if (response.statusCode() == 401 || response.statusCode() == 403) {
                        if (remoteTransport == SHARED_TRANSPORT && negative404CacheEnabled()) {
                            recordNegativeDenied(uri);
                        }
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
                        validateCachedOrFull(root, coordinate, digest, bytes, FailureReason.CORRUPTED_DOWNLOAD);
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

    private record FallbackStep(AttemptOutcome outcome, long bytesConsumed) {}

    private record FallbackResult(
            URI uri,
            List<FallbackStep> steps,
            boolean succeeded,
            byte[] winnerBytes,
            ContentDigest winnerDigest,
            FailureReason lastFailure,
            boolean terminalSizeExceeded) {}

    /**
     * Probes secondary authorized repositories concurrently on virtual threads, then commits
     * outcomes, per-retry attempts, and byte budgets strictly in {@code fallbacks} declaration order
     * (exact semantic, retry, and ledger parity with sequential probing without paying serial RTTs).
     */
    private Outcome acquireFallbackReposParallel(
            Path root,
            DependencyAcquisitionRequest.Requirement requirement,
            DependencyAcquisitionPolicy policy,
            List<URI> fallbacks,
            Budget budget,
            List<Attempt> attempts,
            AttemptCounter counter) {
        ArtifactCoordinate coordinate = requirement.coordinate();
        int count = fallbacks.size();
        long perRepoCap = Math.min(policy.maxArtifactBytes(), budget.remaining());
        boolean useNegCache = remoteTransport == SHARED_TRANSPORT && negative404CacheEnabled();
        var futures = new ArrayList<Future<FallbackResult>>(count);
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (URI repository : fallbacks) {
                URI uri = repository.resolve(coordinate.repositoryPath());
                if (useNegCache && isNegative404Cached(root, uri)) {
                    futures.add(CompletableFuture.completedFuture(new FallbackResult(
                            uri, List.of(new FallbackStep(AttemptOutcome.UNAVAILABLE, 0L)),
                            false, null, null, FailureReason.ARTIFACT_NOT_FOUND, false)));
                    continue;
                }
                if (useNegCache && isNegativeDeniedCached(uri)) {
                    futures.add(CompletableFuture.completedFuture(new FallbackResult(
                            uri, List.of(new FallbackStep(AttemptOutcome.DENIED, 0L)),
                            false, null, null, FailureReason.CREDENTIALS_REQUIRED, false)));
                    continue;
                }
                futures.add(exec.submit(() -> probeSingleFallback(root, requirement, uri, perRepoCap, policy, useNegCache)));
            }

            FailureReason lastFailure = FailureReason.ARTIFACT_NOT_FOUND;
            for (int i = 0; i < count; i++) {
                FallbackResult probe;
                try {
                    probe = futures.get(i).get();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    for (int j = i + 1; j < count; j++) futures.get(j).cancel(true);
                    return failed(requirement, FailureReason.FETCH_TIMEOUT);
                } catch (ExecutionException ee) {
                    probe = new FallbackResult(
                            fallbacks.get(i).resolve(coordinate.repositoryPath()),
                            List.of(new FallbackStep(AttemptOutcome.FAILED, 0L)),
                            false, null, null, FailureReason.NETWORK_FAILURE, false);
                }

                // Replay every non-winning retry/failure attempt step in exact order with byte budget enforcement.
                for (FallbackStep step : probe.steps()) {
                    if (step.bytesConsumed() > 0L) {
                        try {
                            budget.consume(step.bytesConsumed());
                        } catch (ReadLimitExceeded limit) {
                            attempts.add(attempt(counter, coordinate, Origin.REMOTE_REPOSITORY,
                                    probe.uri().toASCIIString(), AttemptOutcome.LIMIT_EXCEEDED, Optional.empty()));
                            for (int j = i + 1; j < count; j++) futures.get(j).cancel(true);
                            return failed(requirement, FailureReason.SIZE_EXCEEDED);
                        }
                    }
                    attempts.add(attempt(counter, coordinate, Origin.REMOTE_REPOSITORY,
                            probe.uri().toASCIIString(), step.outcome(), Optional.empty()));
                }

                if (probe.terminalSizeExceeded()) {
                    for (int j = i + 1; j < count; j++) futures.get(j).cancel(true);
                    return failed(requirement, FailureReason.SIZE_EXCEEDED);
                }

                if (!probe.succeeded()) {
                    lastFailure = probe.lastFailure();
                    if (lastFailure == FailureReason.FETCH_TIMEOUT && Thread.currentThread().isInterrupted()) {
                        for (int j = i + 1; j < count; j++) futures.get(j).cancel(true);
                        return failed(requirement, FailureReason.FETCH_TIMEOUT);
                    }
                    // Do NOT cancel lower-priority futures: fall through cleanly to mirror i+1.
                    continue;
                }

                // Digest + archive/POM validation already succeeded inside probeSingleFallback.
                // Enforce byte budget BEFORE canceling lower-priority mirrors or promoting to cache.
                byte[] bytes = probe.winnerBytes();
                ContentDigest digest = probe.winnerDigest();
                try {
                    budget.consume(bytes.length);
                } catch (ReadLimitExceeded limit) {
                    attempts.add(attempt(counter, coordinate, Origin.REMOTE_REPOSITORY,
                            probe.uri().toASCIIString(), AttemptOutcome.LIMIT_EXCEEDED, Optional.empty()));
                    for (int j = i + 1; j < count; j++) futures.get(j).cancel(true);
                    return failed(requirement, FailureReason.SIZE_EXCEEDED);
                }

                // Exact priority winner validated & within budget: now cancel lower-priority in-flight probes.
                for (int j = i + 1; j < count; j++) futures.get(j).cancel(true);
                try {
                    Artifact artifact = promote(root, coordinate, bytes, digest);
                    attempts.add(attempt(counter, coordinate, Origin.REMOTE_REPOSITORY,
                            probe.uri().toASCIIString(), AttemptOutcome.SUCCEEDED, Optional.of(digest)));
                    return success(requirement, artifact);
                } catch (AcquisitionFailure failure) {
                    attempts.add(attempt(counter, coordinate, Origin.REMOTE_REPOSITORY,
                            probe.uri().toASCIIString(), attemptOutcome(failure.reason), Optional.empty()));
                    return failed(requirement, failure.reason);
                }
            }
            return failed(requirement, lastFailure);
        }
    }

    private FallbackResult probeSingleFallback(
            Path root,
            DependencyAcquisitionRequest.Requirement requirement,
            URI uri,
            long maxBytes,
            DependencyAcquisitionPolicy policy,
            boolean useNegCache) {
        ArtifactCoordinate coordinate = requirement.coordinate();
        var steps = new ArrayList<FallbackStep>(policy.maxRetries() + 1);
        FailureReason lastFailure = FailureReason.ARTIFACT_NOT_FOUND;
        for (int retry = 0; retry <= policy.maxRetries(); retry++) {
            boolean mayRetry = retry < policy.maxRetries();
            try {
                if (maxBytes < 1L) throw new RemoteLimitExceeded();
                RemoteResponse response = remoteTransport.fetch(
                        uri, maxBytes, policy.connectTimeoutMillis(), policy.readTimeoutMillis());
                byte[] bytes = response.rawBytes();
                if (bytes.length > maxBytes) throw new RemoteLimitExceeded();

                int code = response.statusCode();
                if (code == 404 || code == 410) {
                    if (useNegCache) recordNegative404(root, uri);
                    steps.add(new FallbackStep(AttemptOutcome.UNAVAILABLE, bytes.length));
                    return new FallbackResult(uri, List.copyOf(steps), false, null, null,
                            FailureReason.ARTIFACT_NOT_FOUND, false);
                }
                if (code == 401 || code == 403) {
                    if (useNegCache) recordNegativeDenied(uri);
                    steps.add(new FallbackStep(AttemptOutcome.DENIED, bytes.length));
                    return new FallbackResult(uri, List.copyOf(steps), false, null, null,
                            FailureReason.CREDENTIALS_REQUIRED, false);
                }
                if (code >= 300 && code < 400) {
                    steps.add(new FallbackStep(AttemptOutcome.DENIED, bytes.length));
                    return new FallbackResult(uri, List.copyOf(steps), false, null, null,
                            FailureReason.REMOTE_RESPONSE_DENIED, false);
                }
                if (code != 200) {
                    steps.add(new FallbackStep(AttemptOutcome.FAILED, bytes.length));
                    lastFailure = FailureReason.NETWORK_FAILURE;
                    if (mayRetry && code >= 500) {
                        if (!pause(retry)) {
                            return new FallbackResult(uri, List.copyOf(steps), false, null, null,
                                    FailureReason.FETCH_TIMEOUT, false);
                        }
                        continue;
                    }
                    return new FallbackResult(uri, List.copyOf(steps), false, null, null, lastFailure, false);
                }

                ContentDigest digest = ContentDigest.sha256(bytes);
                if (requirement.expectedDigest().isPresent()
                        && !requirement.expectedDigest().orElseThrow().equals(digest)) {
                    steps.add(new FallbackStep(AttemptOutcome.FAILED, bytes.length));
                    return new FallbackResult(uri, List.copyOf(steps), false, null, null,
                            FailureReason.DIGEST_MISMATCH, false);
                }
                try {
                    validateCachedOrFull(root, coordinate, digest, bytes, FailureReason.CORRUPTED_DOWNLOAD);
                } catch (AcquisitionFailure failure) {
                    steps.add(new FallbackStep(AttemptOutcome.FAILED, bytes.length));
                    lastFailure = failure.reason;
                    if (mayRetry) {
                        if (!pause(retry)) {
                            return new FallbackResult(uri, List.copyOf(steps), false, null, null,
                                    FailureReason.FETCH_TIMEOUT, false);
                        }
                        continue;
                    }
                    return new FallbackResult(uri, List.copyOf(steps), false, null, null, lastFailure, false);
                }
                // Validated candidate! Caller commits byte budget, cancels lower-priority probes, and promotes.
                return new FallbackResult(uri, List.copyOf(steps), true, bytes, digest, lastFailure, false);
            } catch (RemoteLimitExceeded limit) {
                steps.add(new FallbackStep(AttemptOutcome.LIMIT_EXCEEDED, 0L));
                return new FallbackResult(uri, List.copyOf(steps), false, null, null,
                        FailureReason.SIZE_EXCEEDED, true);
            } catch (HttpTimeoutException timeout) {
                steps.add(new FallbackStep(AttemptOutcome.FAILED, 0L));
                lastFailure = FailureReason.FETCH_TIMEOUT;
                if (mayRetry) {
                    if (!pause(retry)) {
                        return new FallbackResult(uri, List.copyOf(steps), false, null, null,
                                FailureReason.FETCH_TIMEOUT, false);
                    }
                    continue;
                }
                return new FallbackResult(uri, List.copyOf(steps), false, null, null, lastFailure, false);
            } catch (IOException io) {
                steps.add(new FallbackStep(AttemptOutcome.FAILED, 0L));
                lastFailure = FailureReason.NETWORK_FAILURE;
                if (mayRetry) {
                    if (!pause(retry)) {
                        return new FallbackResult(uri, List.copyOf(steps), false, null, null,
                                FailureReason.FETCH_TIMEOUT, false);
                    }
                    continue;
                }
                return new FallbackResult(uri, List.copyOf(steps), false, null, null, lastFailure, false);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                steps.add(new FallbackStep(AttemptOutcome.FAILED, 0L));
                return new FallbackResult(uri, List.copyOf(steps), false, null, null,
                        FailureReason.FETCH_TIMEOUT, false);
            }
        }
        return new FallbackResult(uri, List.copyOf(steps), false, null, null, lastFailure, false);
    }

    private Artifact promote(Path root, ArtifactCoordinate coordinate, byte[] bytes, ContentDigest digest)
            throws AcquisitionFailure {
        String relative = coordinate.repositoryPath();
        Path target = resolve(root, relative);
        writeVerifiedCacheFile(root, target, bytes, digest);
        return new Artifact(coordinate, Origin.REMOTE_REPOSITORY, relative, digest, bytes.length);
    }

    static byte[] readVerifiedArtifact(Path selectedRoot, Artifact artifact, long maximum) throws IOException {
        try {
            Path root = validateRoot(selectedRoot);
            Path path = resolve(root, artifact.repositoryPath());
            validateRoot(path.getParent());
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Captured artifact is not a regular file");
            byte[] bytes = readBounded(path, Math.min(maximum, artifact.size()));
            if (bytes.length != artifact.size() || !artifact.contentDigest().equals(ContentDigest.sha256(bytes)))
                throw new IOException("Captured artifact changed");
            return bytes;
        } catch (AcquisitionFailure | ReadLimitExceeded unavailable) {
            throw new IOException("Captured artifact unavailable", unavailable);
        }
    }

    static Path materializeEmbeddedJar(Path selectedRoot, byte[] bytes, ContentDigest digest) throws IOException {
        try {
            Path root = validateRoot(selectedRoot);
            Path target = root.resolve(".evo-aar-views").resolve(digest.value().substring(7) + ".jar");
            ensureParent(root, target.getParent());
            var session = com.evolution.analysis.input.VerifiedArtifactSession.current();
            if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    && session.isPresent()
                    && session.orElseThrow().verifyDigest(target, digest)) {
                return target;
            }
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    writeVerifiedCacheFile(root, target, bytes, digest);
                    session.ifPresent(s -> s.recordVerified(target, digest, bytes.length));
                    return target;
                } catch (AcquisitionFailure unavailable) {
                    if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw unavailable;
                }
            }
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    || !digest.equals(ContentDigest.sha256(readBounded(target, bytes.length))))
                throw new IOException("Invalid embedded JAR cache entry");
            session.ifPresent(s -> s.recordVerified(target, digest, bytes.length));
            return target;
        } catch (AcquisitionFailure | ReadLimitExceeded unavailable) {
            throw new IOException("Embedded JAR cache unavailable", unavailable);
        }
    }

    private static void writeVerifiedCacheFile(Path root, Path target, byte[] bytes, ContentDigest digest)
            throws AcquisitionFailure {
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
                if (channel.size() != bytes.length) throw failure(FailureReason.CACHE_WRITE_FAILED);
            }
            if (!fastDepsEnabled()) {
                byte[] written = readBounded(temporary, bytes.length);
                if (!digest.equals(ContentDigest.sha256(written))) throw failure(FailureReason.CACHE_WRITE_FAILED);
            }
            ensureParent(root, target.getParent());
            moveCacheFileWithRetry(root, temporary, target, bytes.length, digest);
            temporary = null;
        } catch (AcquisitionFailure failure) {
            throw failure;
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

    private static void moveCacheFileWithRetry(
            Path root, Path temporary, Path target, int expectedLength, ContentDigest digest)
            throws AcquisitionFailure, IOException {
        long backoffMs = 10L;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (FileAlreadyExistsException alreadyExists) {
                if (verifyConcurrentWinner(root, target, expectedLength, digest)) {
                    return;
                }
                throw failure(FailureReason.CACHE_CHANGED_DURING_READ);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw failure(FailureReason.CACHE_WRITE_FAILED);
            } catch (FileSystemException transientLock) {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                        && verifyConcurrentWinner(root, target, expectedLength, digest)) {
                    return;
                }
                if (attempt == 4) {
                    throw transientLock;
                }
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw failure(FailureReason.CACHE_WRITE_FAILED);
                }
                backoffMs *= 2L;
            }
        }
    }

    private static boolean verifyConcurrentWinner(
            Path root, Path target, int expectedLength, ContentDigest digest) {
        try {
            if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            Path real = target.toRealPath();
            if (!real.startsWith(root) || !real.equals(target.toAbsolutePath().normalize())) {
                return false;
            }
            if (Files.size(target) != expectedLength) {
                return false;
            }
            byte[] existing = readBounded(target, expectedLength);
            return existing.length == expectedLength && digest.equals(ContentDigest.sha256(existing));
        } catch (IOException | ReadLimitExceeded | SecurityException e) {
            return false;
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

    private static void validateCachedOrFull(
            Path root, ArtifactCoordinate coordinate, ContentDigest digest, byte[] bytes, FailureReason corruptReason)
            throws AcquisitionFailure {
        if (isValidatedArtifact(root, digest, coordinate.extension())) {
            return;
        }
        validate(coordinate, bytes, corruptReason);
        markValidatedArtifact(root, digest, coordinate.extension());
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

        /** Internal read-only view avoiding a second multi-MB array copy during download validation. */
        byte[] rawBytes() { return bytes; }
    }

    static final class RemoteLimitExceeded extends Exception {}

    static final class JdkRemoteArtifactTransport implements RemoteArtifactTransport {
        private final Map<Integer,HttpClient> clients=new HashMap<>();
        synchronized ClientLease lease(int timeout) {
            HttpClient existing=clients.get(timeout);
            if(existing!=null)return new ClientLease(existing,true);
            var client=HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_2)
                    .executor(Executors.newVirtualThreadPerTaskExecutor())
                    .connectTimeout(Duration.ofMillis(timeout))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
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
