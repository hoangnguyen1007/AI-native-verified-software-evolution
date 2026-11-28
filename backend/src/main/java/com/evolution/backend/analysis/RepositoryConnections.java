package com.evolution.backend.analysis;

import com.evolution.analysis.acquisition.RepositoryAcquisitionResult;
import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.dependency.DependencyAcquisitionPolicy;
import com.evolution.analysis.filesystem.GitHostPolicy;
import com.evolution.analysis.filesystem.StreamingRepositoryAcquirer;
import com.evolution.analysis.query.ArchitectureQuery.Scope;
import com.evolution.backend.github.GitHubCoordinates;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

/**
 * Opt-in loopback connection boundary. Reads local sources or clones HTTPS/SSH
 * Git repositories (including authenticated GitHub via brokered read-only credentials)
 * without target execution.
 */
public final class RepositoryConnections {
    private static final System.Logger LOG = System.getLogger(RepositoryConnections.class.getName());

    public record Connection(String setup, String label, String kind, RepositoryIdentity repository) {
    }

    private static final VersionedIdentifier PROVIDER = new VersionedIdentifier("source.git-cas", "m8.4-v2");
    private final AnalysisJobs jobs;
    private final Path jdk, cache, directory;
    private final int release;
    private final String gitExecutable;
    private final Optional<LocalAnalysisRunner.PolicyInput> defaultPolicy;
    private final Optional<DependencyAcquisitionPolicy> artifactPolicy;
    private final GitHostPolicy hostPolicy;
    private final boolean onlineOnly;
    private final Map<String, Connection> connections = new LinkedHashMap<>();
    private final Map<String, String> connectionOwners = new LinkedHashMap<>();
    private final Map<String, String> remoteUrls = new LinkedHashMap<>();
    private final Map<ContentDigest, LocalAnalysisRunner.PolicyInput> policies = new LinkedHashMap<>();
    private final Map<RepositoryIdentity, Path> localCheckouts = new LinkedHashMap<>();

    public RepositoryConnections(AnalysisJobs jobs, Path jdk, int release, Path cache, Path directory,
            Optional<LocalAnalysisRunner.PolicyInput> policy) {
        this(jobs, jdk, release, cache, directory, policy, "git");
    }

    /**
     * Executable selection is trusted embedding configuration, never an HTTP
     * operand.
     */
    RepositoryConnections(AnalysisJobs jobs, Path jdk, int release, Path cache, Path directory,
            Optional<LocalAnalysisRunner.PolicyInput> policy, String gitExecutable) {
        this(jobs, jdk, release, cache, directory, policy, gitExecutable, new GitHostPolicy());
    }

    public RepositoryConnections(AnalysisJobs jobs, Path jdk, int release, Path cache, Path directory,
            Optional<LocalAnalysisRunner.PolicyInput> policy, String gitExecutable, GitHostPolicy hostPolicy) {
        this(jobs, jdk, release, cache, directory, policy, gitExecutable, hostPolicy, Optional.empty());
    }

    RepositoryConnections(AnalysisJobs jobs, Path jdk, int release, Path cache, Path directory,
            Optional<LocalAnalysisRunner.PolicyInput> policy,
            String gitExecutable, GitHostPolicy hostPolicy, Optional<DependencyAcquisitionPolicy> artifactPolicy) {
        this(jobs, jdk, release, cache, directory, policy, gitExecutable, hostPolicy, artifactPolicy, false);
    }

    public RepositoryConnections(AnalysisJobs jobs, Path jdk, int release, Path cache, Path directory,
            Optional<LocalAnalysisRunner.PolicyInput> policy,
            String gitExecutable, GitHostPolicy hostPolicy, Optional<DependencyAcquisitionPolicy> artifactPolicy,
            boolean onlineOnly) {
        this.jobs = jobs;
        this.jdk = jdk;
        this.release = release;
        this.cache = cache;
        this.directory = directory;
        this.defaultPolicy = policy;
        this.gitExecutable = gitExecutable;
        this.hostPolicy = Objects.requireNonNull(hostPolicy);
        this.artifactPolicy = Objects.requireNonNull(artifactPolicy);
        this.onlineOnly = onlineOnly;
        policy.ifPresent(p -> policies.put(ContentDigest.sha256(p.bytes()), p));
        try (var catalog = new ConnectionCatalog(directory.resolve("connections"))) {
            for (var saved : catalog.load()) {
                try {
                    connect(saved.source(), saved.policy(), false);
                } catch (IllegalArgumentException | IllegalStateException | SecurityException badEntry) {
                    LOG.log(System.Logger.Level.WARNING, "Skipping invalid saved connection entry ({0})",
                            badEntry.getClass().getSimpleName());
                }
            }
        } catch (IOException unavailable) {
            throw new IllegalStateException("Connection catalog unavailable", unavailable);
        }
    }

    public boolean isOnlineOnly() {
        return onlineOnly;
    }

    public synchronized void describe(String setup, String label, String kind, RepositoryIdentity repository) {
        connections.put(setup, new Connection(setup, label, kind, repository));
        connectionOwners.putIfAbsent(setup, com.evolution.backend.auth.AuthenticatedUser.LOCAL_USER_ID);
    }

    public synchronized void describeLocal(String setup, String label, RepositoryIdentity repository, Path checkout,
            Optional<LocalAnalysisRunner.PolicyInput> policy) {
        describe(setup, label, "LOCAL", repository);
        if (checkout != null)
            localCheckouts.put(repository, checkout.toAbsolutePath().normalize());
        policy.ifPresent(p -> policies.put(ContentDigest.sha256(p.bytes()), p));
    }

    public synchronized Optional<LocalAnalysisRunner.PolicyInput> policy(ContentDigest digest) {
        return Optional.ofNullable(policies.get(digest));
    }

    public synchronized Optional<Path> localCheckout(RepositoryIdentity repository) {
        if (isHostLocalCheckoutDisabledForCaller()) {
            return Optional.empty();
        }
        return Optional.ofNullable(localCheckouts.get(repository));
    }

    public synchronized boolean isRegisteredLocalCheckout(Path candidate) {
        if (candidate == null || isHostLocalCheckoutDisabledForCaller())
            return false;
        Path resolved = resolveCanonical(candidate);
        for (Path registered : localCheckouts.values()) {
            Path regResolved = resolveCanonical(registered);
            if (resolved.equals(regResolved) || resolved.startsWith(regResolved)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHostLocalCheckoutDisabledForCaller() {
        return com.evolution.backend.auth.RequestContext.isMultiUserMode()
                && !com.evolution.backend.auth.AuthenticatedUser.LOCAL_USER_ID
                        .equals(com.evolution.backend.auth.RequestContext.currentUserId());
    }

    private static Path resolveCanonical(Path path) {
        Path abs = path.toAbsolutePath().normalize();
        try {
            return abs.toRealPath();
        } catch (IOException ignored) {
            Path cursor = abs;
            var tail = new java.util.ArrayDeque<Path>();
            while (cursor != null && !java.nio.file.Files.exists(cursor)) {
                Path fileName = cursor.getFileName();
                if (fileName == null)
                    break;
                tail.addFirst(fileName);
                cursor = cursor.getParent();
            }
            if (cursor != null) {
                try {
                    Path base = cursor.toRealPath();
                    for (Path part : tail) {
                        base = base.resolve(part);
                    }
                    return base.normalize();
                } catch (IOException ignoredAgain) {
                    return abs;
                }
            }
            return abs;
        }
    }

    public synchronized Optional<Connection> connection(String setupId) {
        if (!connections.containsKey(setupId)) return Optional.empty();
        if (!isVisibleToCaller(setupId)) return Optional.empty();
        return Optional.ofNullable(connections.get(setupId));
    }

    public synchronized String ownerOf(String setupId) {
        return connectionOwners.getOrDefault(setupId, com.evolution.backend.auth.AuthenticatedUser.LOCAL_USER_ID);
    }

    public synchronized boolean existsOwnedByOther(String setupId) {
        return connections.containsKey(setupId) && !isVisibleToCaller(setupId);
    }

    public synchronized Optional<String> remoteUrl(String setupId) {
        if (!connections.containsKey(setupId) || !isVisibleToCaller(setupId)) return Optional.empty();
        return Optional.ofNullable(remoteUrls.get(setupId));
    }

    public synchronized List<Connection> list() {
        var out = new ArrayList<Connection>();
        for (var e : connections.entrySet()) {
            if (isVisibleToCaller(e.getKey())) {
                out.add(e.getValue());
            }
        }
        return List.copyOf(out);
    }

    public synchronized boolean has(String setupId) {
        return connections.containsKey(setupId) && isVisibleToCaller(setupId);
    }

    private boolean isVisibleToCaller(String setupId) {
        String owner = connectionOwners.getOrDefault(setupId, com.evolution.backend.auth.AuthenticatedUser.LOCAL_USER_ID);
        String caller = com.evolution.backend.auth.RequestContext.currentUserId();
        if (owner.equals(caller)) {
            return true;
        }
        return com.evolution.backend.auth.AuthenticatedUser.LOCAL_USER_ID.equals(owner)
                && !com.evolution.backend.auth.RequestContext.isMultiUserMode();
    }

    public synchronized void disconnect(String setupId) {
        if (setupId == null || setupId.isBlank())
            throw new IllegalArgumentException("Invalid setup");
        if (!connections.containsKey(setupId))
            throw new IllegalArgumentException("Connection not found: " + setupId);
        if (!isVisibleToCaller(setupId))
            throw new SecurityException("ACCESS_DENIED");
        // Unregister before removing so a running job rejects cleanly without leaving
        // an orphan active setup.
        jobs.unregister(setupId);
        var removed = connections.remove(setupId);
        connectionOwners.remove(setupId);
        remoteUrls.remove(setupId);
        if (removed != null
                && connections.values().stream().noneMatch(c -> c.repository().equals(removed.repository()))) {
            localCheckouts.remove(removed.repository());
        }
        try (var catalog = new ConnectionCatalog(directory.resolve("connections"))) {
            catalog.delete(setupId);
        } catch (IOException unavailable) {
            throw new IllegalStateException("Connection catalog unavailable", unavailable);
        }
    }

    public Connection connect(String source, Optional<String> policyText) {
        return connect(source, policyText, true);
    }

    private Connection connect(String source, Optional<String> policyText, boolean persist) {
        if (source == null || source.isBlank() || source.length() > 4096 || source.indexOf('\n') >= 0
                || source.indexOf('\r') >= 0)
            throw new IllegalArgumentException("Invalid source");
        String trimmedSource = source.strip();
        var policy = policyText
                .map(text -> new LocalAnalysisRunner.PolicyInput(text.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        Scope.TYPE))
                .or(() -> defaultPolicy);

        // Normalize GitHub URLs / SCP SSH / github:owner/repo shorthands without holding synchronized lock.
        Optional<GitHubCoordinates.Coordinates> ghCoords = looksLikeGitHubShorthandOrRemote(trimmedSource)
                ? GitHubCoordinates.tryParse(trimmedSource)
                : Optional.empty();
        boolean remote = ghCoords.isPresent() || trimmedSource.contains("://") || trimmedSource.startsWith("git@");
        String coordinate, label;
        Path local = null;
        if (remote) {
            if (ghCoords.isPresent() && !trimmedSource.startsWith("ssh://") && !trimmedSource.startsWith("git@")) {
                coordinate = configuredRemote(URI.create(ghCoords.orElseThrow().canonicalCloneUrl()));
                label = ghCoords.orElseThrow().host() + "/" + ghCoords.orElseThrow().fullName();
            } else if (ghCoords.isPresent() && trimmedSource.startsWith("git@")
                    && hostPolicy.ssh(ghCoords.orElseThrow().host()).isEmpty()) {
                // Convert git@github.com:O/R.git to canonical HTTPS when SSH identity is not configured.
                coordinate = configuredRemote(URI.create(ghCoords.orElseThrow().canonicalCloneUrl()));
                label = ghCoords.orElseThrow().host() + "/" + ghCoords.orElseThrow().fullName();
            } else {
                String normalizedInput = trimmedSource;
                if (ghCoords.isPresent() && trimmedSource.startsWith("git@")) {
                    var c = ghCoords.orElseThrow();
                    normalizedInput = "ssh://git@" + c.host() + "/" + c.owner() + "/" + c.repo() + ".git";
                }
                var uri = GitHostPolicy.syntax(normalizedInput);
                if (uri.getScheme().equals("ssh")) {
                    if (hostPolicy.ssh(uri.getHost()).isEmpty())
                        throw new IllegalArgumentException("SSH identity required");
                    coordinate = uri.toASCIIString();
                } else {
                    coordinate = configuredRemote(uri);
                }
                label = URI.create(coordinate).getHost() + URI.create(coordinate).getPath().replaceFirst("\\.git$", "");
            }
        } else {
            if (onlineOnly) {
                throw new SecurityException("LOCAL_CHECKOUT_DISABLED_IN_ONLINE_MODE");
            }
            if (persist && isHostLocalCheckoutDisabledForCaller()) {
                throw new SecurityException("LOCAL_CHECKOUT_DISABLED_IN_MULTI_USER");
            }
            try {
                var requested = Path.of(trimmedSource);
                if (!requested.isAbsolute() || trimmedSource.startsWith("\\\\") || trimmedSource.startsWith("//"))
                    throw new IllegalArgumentException("Use an absolute local path");
                local = persist ? LocalAnalysisRunner.safe(requested) : requested.toAbsolutePath().normalize();
                if (persist && !Files.isDirectory(local, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException();
            } catch (IOException | InvalidPathException invalid) {
                throw new IllegalArgumentException("Local directory unavailable");
            }
            if (local.getFileName() == null || local.equals(directory.toAbsolutePath().normalize()))
                throw new IllegalArgumentException("Source must have its own directory");
            coordinate = local.toUri().toASCIIString();
            label = local.getFileName().toString();
        }

        final String finalCoordinate = coordinate;
        final String finalLabel = label;
        final Path sourcePath = local;
        final boolean isRemote = remote;
        final String catalogSource = isRemote ? finalCoordinate : trimmedSource;

        synchronized (this) {
            policy.ifPresent(p -> policies.put(ContentDigest.sha256(p.bytes()), p));
            String ownerUserId = persist
                    ? com.evolution.backend.auth.RequestContext.currentUserId()
                    : com.evolution.backend.auth.AuthenticatedUser.LOCAL_USER_ID;
            boolean userScoped = !com.evolution.backend.auth.AuthenticatedUser.LOCAL_USER_ID.equals(ownerUserId);
            String policyHash = policy.map(p -> ContentDigest.sha256(p.bytes()).value()).orElse("none");
            String id = "repo-" + ContentDigest
                    .sha256Utf8(CanonicalJson.write(
                            userScoped
                                    ? List.of(finalCoordinate, policyHash, "user:" + ownerUserId)
                                    : List.of(finalCoordinate, policyHash)))
                    .value().substring(7, 31);
            if (connections.containsKey(id))
                return connections.get(id);
            var repository = RepositoryIdentity.fromCanonicalCoordinate(finalCoordinate);
            var setup = new AnalysisJobs.Setup(id, repository, release, false,
                    policy.map(p -> ContentDigest.sha256(p.bytes())), Optional.empty(),
                    artifactPolicy.map(DependencyAcquisitionPolicy::identity));
            AnalysisJobs.Runner runner = isRemote
                    ? control -> cloneAndRun(finalCoordinate, repository, policy, control)
                    : new LocalAnalysisRunner(input(sourcePath, repository, Optional.empty(), true, policy),
                            directory.resolve("acquisition"), artifactPolicy,
                            LocalAnalysisRunner.NativeExecutionConfig.fromEnvironment(), Optional.of(directory));
            jobs.register(new AnalysisJobs.Registration(setup, runner));
            // Only persist local/single-operator connections to the shared catalog; per-user remote
            // connections remain tenant-isolated in RAM to avoid disclosing private repo URLs across users.
            if (persist && !userScoped) {
                try (var catalog = new ConnectionCatalog(directory.resolve("connections"))) {
                    catalog.save(id, catalogSource, policyText);
                } catch (IOException unavailable) {
                    try {
                        jobs.unregister(id);
                    } catch (RuntimeException ignored) {}
                    throw new IllegalStateException("Connection catalog unavailable", unavailable);
                }
            }
            var connection = new Connection(id, finalLabel,
                    isRemote ? (URI.create(finalCoordinate).getScheme().equals("ssh") ? "GIT_SSH" : "GIT_HTTPS")
                            : "LOCAL",
                    repository);
            connections.put(id, connection);
            connectionOwners.put(id, ownerUserId);
            if (isRemote) {
                remoteUrls.put(id, finalCoordinate);
            } else if (sourcePath != null) {
                localCheckouts.put(repository, sourcePath);
            }
            return connection;
        }
    }

    private static boolean looksLikeGitHubShorthandOrRemote(String source) {
        if (source.contains("://") || source.startsWith("git@") || source.startsWith("github:")) {
            return true;
        }
        // Only treat bare "owner/repo" as GitHub if it has no drive letter / leading slash / spaces.
        return !source.startsWith("/") && !source.startsWith("\\") && !source.contains(":") && !source.contains("\\")
                && source.matches("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9._-]{1,100}(?:\\.git)?");
    }

    private String configuredRemote(URI uri) {
        try {
            return remote(uri.toASCIIString());
        } catch (IllegalArgumentException restricted) {
            try {
                hostPolicy.endpoint(uri);
                return uri.toASCIIString();
            } catch (IOException denied) {
                throw new IllegalArgumentException("Operator endpoint policy required");
            }
        }
    }

    static String remote(String source) {
        URI uri = GitHostPolicy.syntax(source);
        if (uri.getPort() != -1 && uri.getPort() != 443)
            throw new IllegalArgumentException("Operator endpoint policy required");
        if (!uri.getScheme().equals("https"))
            throw new IllegalArgumentException("SSH requires operator-configured identity and strict host keys");
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.matches("[0-9.]+") || host.equals("localhost") || host.endsWith(".localhost"))
            throw new IllegalArgumentException("Endpoint denied");
        String path = uri.getPath().replaceFirst("/$", "");
        return "https://" + host + (uri.getPort() == -1 ? "" : ":" + uri.getPort()) + path;
    }

    public Object acquisition(String revision, int size, Optional<String> cursor) throws IOException {
        if (!revision.matches("[A-Za-z0-9._-]{1,256}"))
            throw new IllegalArgumentException("Invalid acquisition revision");
        try (var intake = new RepositoryIntake(directory.resolve("acquisition"), true)) {
            return Map.of("version", "repository.acquisition-api:m8.4-v2", "revision", revision, "job",
                    intake.journal().get(revision), "manifest", intake.inventory().manifest(revision), "inventory",
                    intake.inventory().page(revision, size, cursor), "semanticCoverage", "NOT_EVALUATED");
        }
    }

    public Object acquisitionHistory(long after, int size) throws IOException {
        try (var intake = new RepositoryIntake(directory.resolve("acquisition"), true)) {
            return intake.journal().historyPage(after, size);
        }
    }

    @FunctionalInterface
    public interface InventoryRead<T> {
        T read(InventoryReader reader) throws IOException;
    }

    public <T> T readPublishedInventory(InventoryRead<T> operation) throws IOException {
        try (var intake = new RepositoryIntake(directory.resolve("acquisition"), true)) {
            return operation.read(intake.inventory());
        }
    }

    public Object readiness(String revision, String section, int size, Optional<String> cursor) throws IOException {
        if (!revision.matches("[A-Za-z0-9._-]{1,256}"))
            throw new IllegalArgumentException("Invalid acquisition revision");
        try (var intake = new RepositoryIntake(directory.resolve("acquisition"))) {
            return intake.readinessPage(revision, section, size, cursor);
        }
    }

    public Map<String, Object> publishedOverview(String revision) throws IOException {
        return publishedOverview(revision, Optional.empty());
    }

    public Map<String, Object> publishedOverview(String revision, Optional<AnalysisMode> mode) throws IOException {
        if (!revision.matches("[A-Za-z0-9._-]{1,256}"))
            throw new IllegalArgumentException("Invalid acquisition revision");
        try (var intake = new RepositoryIntake(directory.resolve("acquisition"), true)) {
            return intake.publishedOverview(revision, mode);
        }
    }

    private LocalAnalysisRunner.Input input(Path source, RepositoryIdentity repository, Optional<String> revision,
            boolean dirty, Optional<LocalAnalysisRunner.PolicyInput> policy) {
        var normalized = directory.toAbsolutePath().normalize();
        var exclusions = normalized.startsWith(source)
                ? List.of(source.relativize(normalized).toString().replace('\\', '/'))
                : List.<String>of();
        return new LocalAnalysisRunner.Input(source, repository, jdk, release, cache, revision, dirty, false,
                exclusions, policy, LocalAnalysisRunner.SpringInput.autoMultiConfig());
    }

    private static final java.util.regex.Pattern HTTP_ERROR_CODE = java.util.regex.Pattern.compile(
            "\\bHTTP\\s*([45][0-9]{2})\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

    static String sanitizeRemoteCode(String raw) {
        if (raw == null || raw.isBlank())
            return "REMOTE_FETCH_FAILED";
        String trimmed = raw.strip();
        if (trimmed.matches("[A-Z][A-Z0-9_]{1,99}"))
            return trimmed;
        var matcher = HTTP_ERROR_CODE.matcher(trimmed);
        if (matcher.find())
            return "REMOTE_HTTP_" + matcher.group(1);
        return "REMOTE_FETCH_FAILED";
    }

    private AnalysisJobs.RunResult cloneAndRun(String url, RepositoryIdentity repository,
            Optional<LocalAnalysisRunner.PolicyInput> policy, AnalysisJobs.Control control) throws Exception {
        control.stage(AnalysisJobs.Stage.ACQUISITION);
        String targetRef = control.requestedRef().orElse("HEAD");
        String code = "REMOTE_FETCH_FAILED";
        RepositoryAcquisitionResult acquisition = null;
        Optional<String> pin = Optional.empty();
        try (var intake = new RepositoryIntake(directory.resolve("acquisition"))) {
            var request = new StreamingAcquisitionRequest(repository, Optional.empty(), false, List.of());
            boolean incremental = !Boolean.getBoolean("evo.incremental.disabled");
            StreamingRepositoryAcquirer.Receipt receipt;
            if (incremental) {
                Optional<String> baseRevision = intake.latestSealed(repository, request.identity())
                        .map(com.evolution.analysis.filesystem.SqliteInventoryStore.SealedHead::revision);
                receipt = intake.gitIncremental(URI.create(url), targetRef, control.id(), baseRevision,
                        request, hostPolicy, gitExecutable, control).receipt();
            } else {
                receipt = intake.git(URI.create(url), targetRef, control.id(), request, hostPolicy, gitExecutable,
                        control);
            }
            control.checkpoint();
            if (receipt.manifest().isPresent()) {
                pin = intake.pinnedCommit(control.id());
                acquisition = intake.compatibility(control.id(), repository, pin, false, false, false, List.of());
            }
            if (receipt.reason() != null && !receipt.reason().isBlank()) {
                code = sanitizeRemoteCode(receipt.reason());
            }
        } catch (IOException unavailable) {
            code = sanitizeRemoteCode(unavailable.getMessage());
        }
        if (acquisition != null)
            return new LocalAnalysisRunner(input(directory, repository, pin, false, policy),
                    directory.resolve("acquisition"), artifactPolicy,
                    LocalAnalysisRunner.NativeExecutionConfig.fromEnvironment(), Optional.of(directory))
                    .runCaptured(acquisition, control);
        var digest = ContentDigest.sha256Utf8(CanonicalJson.write(List.of(PROVIDER, repository, code)));
        var problem = new RepositoryAcquisitionResult.Problem(RepositoryAcquisitionResult.Reason.ROOT_READ_FAILED, code,
                RepositoryAcquisitionResult.Requirement.REPOSITORY_SELECTION);
        return new AnalysisJobs.Outcome(AnalysisJobs.Completion.FAILED, Optional.empty(), List.of(), List.of(),
                Optional.of(digest), Optional.empty(),
                List.of(new AnalysisJobs.InputProblem(PROVIDER, digest, problem)));
    }
}
