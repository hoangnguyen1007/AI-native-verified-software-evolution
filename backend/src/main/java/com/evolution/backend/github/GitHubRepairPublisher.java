package com.evolution.backend.github;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.filesystem.GitHostPolicy;
import com.evolution.backend.analysis.RepositoryConnections;
import com.evolution.backend.query.CapturedRepair;
import com.evolution.backend.query.CapturedRepairVerification;
import com.evolution.backend.query.RepairProposalStore;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Two-step, strictly user-confirmed Verified Repair -> GitHub Branch & Pull Request publisher.
 *
 * <p>Safety invariants:
 * <ul>
 *   <li>Step 1 ({@link #prepare}) is 100% read-only (zero Git/GitHub writes) and issues a single-use
 *       15-minute RAM confirmation token bound to {@code publishPlanDigest}.</li>
 *   <li>Step 2 ({@link #publish}) requires explicit {@code confirmedByUser == true}, matching
 *       {@code confirmationToken} and {@code publishPlanDigest}, verifies every {@code Edit.before()}
 *       SHA-256 against the fetched base branch (aborting with {@code 409 BASE_BRANCH_DRIFTED} on drift),
 *       runs Git with {@code core.hooksPath=<empty>}, {@code GIT_CONFIG_NOSYSTEM=1}, {@code commit.gpgsign=false},
 *       never pushes to protected/default branches, and never uses {@code --force}.</li>
 *   <li>Unverified proposals require explicit {@code allowUnverified=true}, force {@code draft=true},
 *       and prepend a non-removable warning banner.</li>
 * </ul>
 */
public final class GitHubRepairPublisher {
    public static final String PLAN_SCHEMA = "github.repair-publish-plan:v1";
    public static final String RECEIPT_SCHEMA = "github.repair-publish-receipt:v1";
    public static final String UNVERIFIED_TRUST_BANNER =
            "> [!WARNING]\n"
            + "> **UNVERIFIED REPAIR PROPOSAL — ISOLATED SANDBOX VERIFICATION NOT RUN.** "
            + "Do not merge without manual code review and CI verification.\n\n";

    private static final long PLAN_TTL_MILLIS = TimeUnit.MINUTES.toMillis(15);
    private static final int MAX_PENDING_PLANS = 64;
    private static final long GIT_TIMEOUT_SECONDS = 90L;

    public record DiffStats(int filesChanged, int insertions, int deletions) {
    }

    public record PrepareRequest(
            ContentDigest proposalId,
            Optional<String> setupId,
            Optional<String> repoCoordinate,
            Optional<String> baseBranch,
            Optional<String> headBranch,
            Optional<String> title,
            Optional<Boolean> draft,
            boolean allowUnverified,
            Optional<CapturedRepairVerification.Result> verification) {
    }

    public record PublishPlan(
            String schema,
            ContentDigest publishPlanDigest,
            String confirmationToken,
            Instant expiresAt,
            String repository,
            String cloneUrl,
            String host,
            ContentDigest proposalId,
            ContentDigest patchDigest,
            String verdict,
            String trustBadge,
            boolean verified,
            String baseBranch,
            String headBranch,
            boolean draft,
            String title,
            String commitMessage,
            String bodyMarkdown,
            String trustBanner,
            DiffStats diffStats,
            List<String> editedPaths) {
        public Map<String, Object> toView() {
            var map = new LinkedHashMap<String, Object>();
            map.put("schema", schema);
            map.put("publishPlanDigest", publishPlanDigest.value());
            map.put("confirmationToken", confirmationToken);
            map.put("expiresAt", expiresAt);
            map.put("repository", repository);
            map.put("proposalId", proposalId.value());
            map.put("patchDigest", patchDigest.value());
            map.put("verdict", verdict);
            map.put("trustBadge", trustBadge);
            map.put("verified", verified);
            map.put("baseBranch", baseBranch);
            map.put("headBranch", headBranch);
            map.put("draft", draft);
            map.put("title", title);
            map.put("commitMessage", commitMessage);
            map.put("bodyMarkdown", bodyMarkdown);
            map.put("trustBanner", trustBanner);
            map.put("diffStats", diffStats);
            map.put("editedPaths", editedPaths);
            return map;
        }
    }

    public record PublishRequest(
            String confirmationToken,
            ContentDigest publishPlanDigest,
            boolean confirmedByUser,
            Optional<String> titleOverride,
            Optional<String> bodyOverride,
            Optional<Boolean> draftOverride) {
    }

    public record PublishReceipt(
            String schema,
            int prNumber,
            String htmlUrl,
            String repository,
            String branch,
            String baseBranch,
            String commitSha,
            String baseSha,
            boolean draft,
            String verdict,
            String trustBadge,
            ContentDigest proposalId,
            ContentDigest patchDigest,
            ContentDigest publishPlanDigest) {
        public Map<String, Object> toView() {
            var map = new LinkedHashMap<String, Object>();
            map.put("schema", schema);
            map.put("prNumber", prNumber);
            map.put("number", prNumber);
            map.put("htmlUrl", htmlUrl);
            map.put("url", htmlUrl);
            map.put("repository", repository);
            map.put("branch", branch);
            map.put("headBranch", branch);
            map.put("baseBranch", baseBranch);
            map.put("commitSha", commitSha);
            map.put("baseSha", baseSha);
            map.put("draft", draft);
            map.put("verdict", verdict);
            map.put("trustBadge", trustBadge);
            map.put("proposalId", proposalId.value());
            map.put("patchDigest", patchDigest.value());
            map.put("publishPlanDigest", publishPlanDigest.value());
            return map;
        }
    }

    public static final class PublishException extends RuntimeException {
        private final int status;
        private final String code;

        public PublishException(int status, String code, String message) {
            super(GitHubTokenBroker.redact(message));
            this.status = status;
            this.code = code;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }
    }

    private record PendingPlan(String ownerUserId, PublishPlan plan, CapturedRepair.Proposal proposal,
            GitHubCoordinates.Coordinates coords, String defaultBranch, long expiresAtMillis) {
    }

    private final GitHubRestClient restClient;
    private final GitHubTokenBroker tokenBroker;
    private final RepairProposalStore proposalStore;
    private final RepositoryConnections connections;
    private final GitHostPolicy hostPolicy;
    private final Path workRoot;
    private final String gitExecutable;
    private final Optional<Path> localBareRemoteOverride; // ONLY for offline unit/integration tests!
    private final SecureRandom random = new SecureRandom();
    private final Map<String, PendingPlan> pendingPlans = Collections.synchronizedMap(
            new LinkedHashMap<>(32, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, PendingPlan> eldest) {
                    return size() > MAX_PENDING_PLANS;
                }
            });

    public GitHubRepairPublisher(
            GitHubRestClient restClient,
            GitHubTokenBroker tokenBroker,
            RepairProposalStore proposalStore,
            RepositoryConnections connections,
            GitHostPolicy hostPolicy,
            Path workRoot,
            String gitExecutable) {
        this(restClient, tokenBroker, proposalStore, connections, hostPolicy, workRoot, gitExecutable, Optional.empty());
    }

    /** Package-private constructor allowing offline tests to push to a local {@code git init --bare} remote. */
    public GitHubRepairPublisher(
            GitHubRestClient restClient,
            GitHubTokenBroker tokenBroker,
            RepairProposalStore proposalStore,
            RepositoryConnections connections,
            GitHostPolicy hostPolicy,
            Path workRoot,
            String gitExecutable,
            Optional<Path> localBareRemoteOverride) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        this.tokenBroker = Objects.requireNonNull(tokenBroker, "tokenBroker");
        this.proposalStore = Objects.requireNonNull(proposalStore, "proposalStore");
        this.connections = connections;
        this.hostPolicy = Objects.requireNonNull(hostPolicy, "hostPolicy");
        this.workRoot = Objects.requireNonNull(workRoot, "workRoot").toAbsolutePath().normalize();
        this.gitExecutable = gitExecutable == null || gitExecutable.isBlank() ? "git" : gitExecutable;
        this.localBareRemoteOverride = Objects.requireNonNull(localBareRemoteOverride, "localBareRemoteOverride");
    }

    /** Step 1: Dry-run plan generation with zero Git or GitHub writes. */
    public PublishPlan prepare(PrepareRequest req, BooleanSupplier cancelled) {
        Objects.requireNonNull(req, "req");
        BooleanSupplier cancelCheck = cancelled == null ? () -> false : cancelled;
        var proposal = proposalStore.get(Objects.requireNonNull(req.proposalId(), "proposalId"))
                .orElseThrow(() -> new PublishException(404, "REPAIR_PROPOSAL_NOT_FOUND",
                        "Repair proposal is not present in the local proposal store."));
        var candidate = proposal.candidate();
        if (!CapturedRepair.authentic(proposal)
                || !ContentDigest.sha256Utf8(candidate.patch()).equals(candidate.patchDigest())) {
            throw new PublishException(409, "REPAIR_PROPOSAL_CORRUPT",
                    "Repair proposal failed cryptographic integrity verification.");
        }
        if (candidate.edits().isEmpty()) {
            throw new PublishException(422, "REPAIR_PROPOSAL_EMPTY", "Repair proposal contains no file edits.");
        }

        // Resolve verification state & enforce trust gates
        String verdict = "UNVERIFIED";
        String trustBadge = "UNVERIFIED_SYNTAX_CHECKED_ONLY";
        boolean verified = false;
        String planIdentity = "NOT_RUN";
        Optional<CapturedRepairVerification.IsolatedProof> isolatedProof = Optional.empty();
        if (req.verification().isPresent()) {
            var v = req.verification().orElseThrow();
            if (!v.proposal().equals(proposal.identity())) {
                throw new PublishException(409, "REPAIR_VERIFICATION_MISMATCH",
                        "Verification receipt does not match the pinned proposal and patch digest.");
            }
            if (v.verdict() == CapturedRepairVerification.Verdict.REJECTED) {
                throw new PublishException(409, "REPAIR_VERIFICATION_REJECTED",
                        "Rejected repair proposals cannot be published to GitHub.");
            }
            if (!v.plan().patch().equals(candidate.patchDigest())) {
                throw new PublishException(409, "REPAIR_VERIFICATION_MISMATCH",
                        "Verification receipt does not match the pinned proposal and patch digest.");
            }
            planIdentity = v.planIdentity().value();
            isolatedProof = v.isolatedProof();
            if (v.verdict() == CapturedRepairVerification.Verdict.VERIFIED) {
                verdict = "VERIFIED";
                trustBadge = "VERIFIED_ISOLATED";
                verified = true;
            }
        }
        if (!verified && !req.allowUnverified()) {
            throw new PublishException(409, "REPAIR_UNVERIFIED_REQUIRES_OPT_IN",
                    "Publishing an unverified proposal requires allowUnverified=true (forced Draft PR with warning banner).");
        }

        var coords = resolveCoordinates(req);
        String defaultBranch = "main";
        try {
            defaultBranch = restClient.repository(coords, cancelCheck).defaultBranch();
        } catch (GitHubRestClient.GitHubApiException ignored) {
            if (req.baseBranch().isEmpty()) {
                defaultBranch = "main";
            }
        }
        String baseBranch = req.baseBranch().filter(s -> !s.isBlank())
                .map(GitHubCoordinates::validateBranchName)
                .orElse(defaultBranch);
        String shortHex = proposal.identity().value().substring(7, 19);
        String headBranch = req.headBranch().filter(s -> !s.isBlank())
                .map(GitHubCoordinates::validateBranchName)
                .orElse("evo/repair-" + shortHex);
        if (GitHubCoordinates.isProtectedDefaultBranch(headBranch, defaultBranch) || headBranch.equals(baseBranch)) {
            throw new PublishException(400, "PROTECTED_BRANCH_DENIED",
                    "Repair target branch cannot be the base or default protected branch.");
        }

        boolean draft = !verified || req.draft().orElse(false);
        String trustBanner = verified ? "" : UNVERIFIED_TRUST_BANNER;
        String ruleLabel = candidate.recipe().map(r -> r.id()).orElse(candidate.strategy().name());
        String title = req.title().filter(s -> !s.isBlank()).map(String::strip)
                .orElse("[Evo " + (verified ? "Verified" : "Draft") + " Repair] " + ruleLabel + " (" + shortHex + ")");
        if (title.length() > 200) {
            title = title.substring(0, 200);
        }

        var diffStats = computeDiffStats(candidate.patch(), candidate.edits().size());
        var editedPaths = candidate.edits().stream().map(CapturedRepair.Edit::path).distinct().sorted().toList();
        String commitMessage = buildCommitMessage(title, proposal, planIdentity, verdict, trustBadge, editedPaths);
        String bodyMarkdown = buildPrBody(proposal, req.verification(), isolatedProof,
                verdict, trustBadge, trustBanner, diffStats, editedPaths);

        var planPreimage = new LinkedHashMap<String, Object>();
        planPreimage.put("schema", PLAN_SCHEMA);
        planPreimage.put("repository", coords.fullName());
        planPreimage.put("proposalId", proposal.identity().value());
        planPreimage.put("patchDigest", candidate.patchDigest().value());
        planPreimage.put("verdict", verdict);
        planPreimage.put("trustBadge", trustBadge);
        planPreimage.put("baseBranch", baseBranch);
        planPreimage.put("headBranch", headBranch);
        planPreimage.put("draft", draft);
        planPreimage.put("title", title);
        planPreimage.put("editedPaths", editedPaths);
        ContentDigest publishPlanDigest = ContentDigest.sha256Utf8(CanonicalJson.write(planPreimage));

        String token = randomTokenHex();
        long expiresAtMillis = System.currentTimeMillis() + PLAN_TTL_MILLIS;
        var plan = new PublishPlan(
                PLAN_SCHEMA,
                publishPlanDigest,
                token,
                Instant.ofEpochMilli(expiresAtMillis),
                coords.fullName(),
                coords.canonicalCloneUrl() + ".git",
                coords.host(),
                proposal.identity(),
                candidate.patchDigest(),
                verdict,
                trustBadge,
                verified,
                baseBranch,
                headBranch,
                draft,
                title,
                commitMessage,
                bodyMarkdown,
                trustBanner,
                diffStats,
                editedPaths);

        purgeExpiredPlans();
        String ownerUserId = com.evolution.backend.auth.RequestContext.currentUserId();
        pendingPlans.put(token, new PendingPlan(ownerUserId, plan, proposal, coords, defaultBranch, expiresAtMillis));
        return plan;
    }

    /** Step 2: Explicitly confirmed branch creation, verified patch commit, push, and GitHub PR creation. */
    public PublishReceipt publish(PublishRequest req, BooleanSupplier cancelled) {
        Objects.requireNonNull(req, "req");
        BooleanSupplier cancelCheck = cancelled == null ? () -> false : cancelled;
        if (!req.confirmedByUser()) {
            throw new PublishException(400, "EXPLICIT_USER_CONFIRMATION_REQUIRED",
                    "confirmedByUser=true is mandatory before pushing any branch or opening a Pull Request.");
        }
        if (req.confirmationToken() == null || req.confirmationToken().isBlank() || req.publishPlanDigest() == null) {
            throw new PublishException(400, "INVALID_CONFIRMATION", "confirmationToken and publishPlanDigest are required.");
        }
        purgeExpiredPlans();
        String callerUserId = com.evolution.backend.auth.RequestContext.currentUserId();
        PendingPlan pending;
        synchronized (pendingPlans) {
            PendingPlan existing = pendingPlans.get(req.confirmationToken());
            if (existing == null) {
                throw new PublishException(409, "CONFIRMATION_TOKEN_INVALID_OR_EXPIRED",
                        "Confirmation token is unknown, expired, or already consumed. Run prepare again.");
            }
            if (!existing.ownerUserId().equals(callerUserId)) {
                // Do NOT consume User A's token when User B tries to hijack it!
                throw new PublishException(403, "ACCESS_DENIED",
                        "Confirmation token belongs to another user session.");
            }
            pending = pendingPlans.remove(req.confirmationToken());
        }
        if (System.currentTimeMillis() > pending.expiresAtMillis()) {
            throw new PublishException(409, "CONFIRMATION_TOKEN_INVALID_OR_EXPIRED",
                    "Confirmation token expired. Run prepare again.");
        }
        var plan = pending.plan();
        if (!plan.publishPlanDigest().equals(req.publishPlanDigest())) {
            throw new PublishException(409, "PUBLISH_PLAN_DIGEST_MISMATCH",
                    "publishPlanDigest does not match the prepared publish plan.");
        }

        // Enforce unverified safeguards even if caller supplies overrides
        boolean effectiveDraft = !plan.verified() || req.draftOverride().orElse(plan.draft());
        String effectiveTitle = req.titleOverride().filter(s -> !s.isBlank()).map(String::strip).orElse(plan.title());
        if (effectiveTitle.length() > 200) {
            effectiveTitle = effectiveTitle.substring(0, 200);
        }
        String effectiveBody = req.bodyOverride().filter(s -> !s.isBlank()).orElse(plan.bodyMarkdown());
        if (!plan.verified() && !effectiveBody.contains("UNVERIFIED REPAIR PROPOSAL")) {
            effectiveBody = UNVERIFIED_TRUST_BANNER + effectiveBody;
        }

        Optional<String> authHeader = Optional.empty();
        if (localBareRemoteOverride.isEmpty()) {
            authHeader = tokenBroker.writeGitAuthorization(plan.host());
            if (authHeader.isEmpty()) {
                throw new PublishException(401, "GITHUB_AUTH_REQUIRED",
                        "A GitHub token with repository push and pull-request permissions is required.");
            }
        }

        Path runDir = workRoot.resolve("pub-" + UUID.randomUUID());
        Path workDir = runDir.resolve("work");
        Path emptyHooks = runDir.resolve("empty-hooks");
        try {
            Files.createDirectories(workDir);
            Files.createDirectories(emptyHooks);

            String remoteTarget = localBareRemoteOverride.isPresent()
                    ? localBareRemoteOverride.orElseThrow().toAbsolutePath().normalize().toString()
                    : plan.cloneUrl();
            boolean allowLocalFile = localBareRemoteOverride.isPresent();

            runGit(workDir, emptyHooks, remoteTarget, authHeader, allowLocalFile,
                    List.of("init", "--quiet"), cancelCheck);
            runGit(workDir, emptyHooks, remoteTarget, authHeader, allowLocalFile,
                    List.of("fetch", "--depth=1", "--no-tags", remoteTarget,
                            "refs/heads/" + plan.baseBranch() + ":refs/remotes/origin/" + plan.baseBranch()),
                    cancelCheck);
            runGit(workDir, emptyHooks, remoteTarget, authHeader, allowLocalFile,
                    List.of("checkout", "-B", plan.headBranch(), "refs/remotes/origin/" + plan.baseBranch()),
                    cancelCheck);
            String baseSha = runGit(workDir, emptyHooks, remoteTarget, authHeader, allowLocalFile,
                    List.of("rev-parse", "HEAD"), cancelCheck).strip();

            // Verify EVERY before-file SHA-256 against the fetched base branch (detect base drift!)
            var edits = pending.proposal().candidate().edits();
            for (var edit : edits) {
                Path targetFile = resolveSafeRepoFile(workDir, edit.path());
                if (edit.isCreate()) {
                    if (Files.exists(targetFile, LinkOption.NOFOLLOW_LINKS)) {
                        throw new PublishException(409, "BASE_BRANCH_DRIFTED",
                                "Create target already exists on base branch '" + plan.baseBranch() + "': " + edit.path());
                    }
                    continue;
                }
                if (!Files.isRegularFile(targetFile, LinkOption.NOFOLLOW_LINKS)) {
                    throw new PublishException(409, "BASE_BRANCH_DRIFTED",
                            "Target file is missing on base branch '" + plan.baseBranch() + "': " + edit.path());
                }
                ContentDigest actualBefore = ContentDigest.sha256(Files.readAllBytes(targetFile));
                if (!actualBefore.equals(edit.before())) {
                    throw new PublishException(409, "BASE_BRANCH_DRIFTED",
                            "File changed on base branch '" + plan.baseBranch() + "' since analysis: " + edit.path());
                }
            }

            // Apply pinned verified patch via git apply --check && git apply
            Path patchFile = runDir.resolve("verified.patch");
            Files.writeString(patchFile, pending.proposal().candidate().patch(), StandardCharsets.UTF_8);
            runGit(workDir, emptyHooks, remoteTarget, authHeader, allowLocalFile,
                    List.of("apply", "--check", "--whitespace=nowarn", patchFile.toString()), cancelCheck);
            runGit(workDir, emptyHooks, remoteTarget, authHeader, allowLocalFile,
                    List.of("apply", "--whitespace=nowarn", patchFile.toString()), cancelCheck);

            // Verify EVERY after-file SHA-256 postcondition!
            for (var edit : edits) {
                Path targetFile = resolveSafeRepoFile(workDir, edit.path());
                if (edit.isDelete()) {
                    if (Files.exists(targetFile, LinkOption.NOFOLLOW_LINKS)) {
                        throw new PublishException(409, "PATCH_POSTCONDITION_MISMATCH",
                                "Deleted file still exists after git apply: " + edit.path());
                    }
                    continue;
                }
                if (!Files.isRegularFile(targetFile, LinkOption.NOFOLLOW_LINKS)) {
                    throw new PublishException(409, "PATCH_POSTCONDITION_MISMATCH",
                            "Patched file missing after git apply: " + edit.path());
                }
                ContentDigest actualAfter = ContentDigest.sha256(Files.readAllBytes(targetFile));
                if (!actualAfter.equals(edit.after())) {
                    throw new PublishException(409, "PATCH_POSTCONDITION_MISMATCH",
                            "Patched bytes do not match pinned candidate digest: " + edit.path());
                }
            }

            // Stage ONLY the exact edited paths and commit with provenance trailers (never execute target hooks!)
            var addArgs = new ArrayList<String>();
            addArgs.add("add");
            addArgs.add("--");
            addArgs.addAll(plan.editedPaths());
            runGit(workDir, emptyHooks, remoteTarget, authHeader, allowLocalFile, addArgs, cancelCheck);

            var actor = com.evolution.backend.auth.RequestContext.currentUser();
            String commitAuthorName = actor.isAuthenticated() ? sanitizeGitIdentity(actor.name(), "Evo Verified Repair Bot") : "Evo Verified Repair Bot";
            String commitAuthorEmail = actor.isAuthenticated() ? sanitizeGitIdentity(actor.email(), "evo-repair@local") : "evo-repair@local";
            Path msgFile = runDir.resolve("commit-msg.txt");
            Files.writeString(msgFile, plan.commitMessage(), StandardCharsets.UTF_8);
            runGit(workDir, emptyHooks, remoteTarget, authHeader, allowLocalFile,
                    List.of("-c", "user.name=" + commitAuthorName,
                            "-c", "user.email=" + commitAuthorEmail,
                            "-c", "commit.gpgsign=false",
                            "commit", "--no-verify", "-F", msgFile.toString()),
                    cancelCheck);
            String commitSha = runGit(workDir, emptyHooks, remoteTarget, authHeader, allowLocalFile,
                    List.of("rev-parse", "HEAD"), cancelCheck).strip();

            // Push new repair branch WITHOUT --force
            runGit(workDir, emptyHooks, remoteTarget, authHeader, allowLocalFile,
                    List.of("push", remoteTarget, "HEAD:refs/heads/" + plan.headBranch()), cancelCheck);

            // Open Pull Request via GitHub REST API
            var createdPr = restClient.createPullRequest(
                    pending.coords(), effectiveTitle, effectiveBody, plan.headBranch(), plan.baseBranch(),
                    effectiveDraft, cancelCheck);

            return new PublishReceipt(
                    RECEIPT_SCHEMA,
                    createdPr.number(),
                    createdPr.htmlUrl(),
                    plan.repository(),
                    plan.headBranch(),
                    plan.baseBranch(),
                    commitSha,
                    baseSha,
                    createdPr.draft(),
                    plan.verdict(),
                    plan.trustBadge(),
                    plan.proposalId(),
                    plan.patchDigest(),
                    plan.publishPlanDigest());
        } catch (PublishException | GitHubRestClient.GitHubApiException known) {
            throw known;
        } catch (IOException io) {
            throw new PublishException(500, "GITHUB_PUBLISH_FAILED",
                    "Git repair branch publication failed: " + GitHubTokenBroker.redact(io.getMessage()));
        } finally {
            deleteQuietly(runDir);
        }
    }

    private GitHubCoordinates.Coordinates resolveCoordinates(PrepareRequest req) {
        if (req.repoCoordinate().isPresent() && !req.repoCoordinate().orElseThrow().isBlank()) {
            return GitHubCoordinates.parse(req.repoCoordinate().orElseThrow());
        }
        if (req.setupId().isPresent() && connections != null) {
            var remote = connections.remoteUrl(req.setupId().orElseThrow());
            if (remote.isPresent()) {
                return GitHubCoordinates.parse(remote.orElseThrow());
            }
        }
        throw new PublishException(400, "GITHUB_REPOSITORY_REQUIRED",
                "Specify a connected GitHub setupId or explicit repoCoordinate (owner/repo).");
    }

    private String runGit(Path workDir, Path emptyHooks, String remoteUrl, Optional<String> authHeader,
            boolean allowLocalFile, List<String> args, BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new PublishException(409, "REQUEST_CANCELLED", "Publication cancelled.");
        }
        var cmd = new ArrayList<String>();
        cmd.add(gitExecutable);
        cmd.add("-c"); cmd.add("credential.helper=");
        cmd.add("-c"); cmd.add("core.hooksPath=" + emptyHooks);
        cmd.add("-c"); cmd.add("commit.gpgsign=false");
        cmd.add("-c"); cmd.add("protocol.allow=never");
        cmd.add("-c"); cmd.add("protocol.https.allow=always");
        if (allowLocalFile) {
            cmd.add("-c"); cmd.add("protocol.file.allow=always");
        }
        cmd.add("-c"); cmd.add("http.followRedirects=false");
        cmd.add("-c"); cmd.add("http.sslVerify=true");
        cmd.add("-c"); cmd.add("http.proxy=");
        cmd.add("-c"); cmd.add("core.autocrlf=false");
        cmd.add("-c"); cmd.add("core.longpaths=true");

        if (!allowLocalFile && remoteUrl.startsWith("https://")) {
            URI uri = URI.create(remoteUrl);
            var endpoint = hostPolicy.endpoint(uri);
            String host = uri.getHost();
            int port = uri.getPort() == -1 ? 443 : uri.getPort();
            String addresses = String.join(",", endpoint.addresses().stream()
                    .map(a -> a.getAddress().length == 16 ? "[" + a.getHostAddress() + "]" : a.getHostAddress())
                    .toList());
            cmd.add("-c"); cmd.add("http.curloptResolve=" + host + ":" + port + ":" + addresses);
        }
        cmd.addAll(args);

        var pb = new ProcessBuilder(cmd).directory(workDir.toFile());
        pb.redirectErrorStream(false);
        var inherited = new HashMap<>(pb.environment());
        pb.environment().clear();
        for (String key : List.of("PATH", "Path", "SystemRoot", "SYSTEMROOT", "TEMP", "TMP")) {
            if (inherited.containsKey(key)) {
                pb.environment().put(key, inherited.get(key));
            }
        }
        pb.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        pb.environment().put("GIT_CONFIG_GLOBAL", emptyHooks.resolve("no-config").toString());
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("GIT_ASKPASS", "");
        pb.environment().put("GIT_LFS_SKIP_SMUDGE", "1");
        if (authHeader.isPresent() && !allowLocalFile && remoteUrl.startsWith("https://")) {
            pb.environment().put("GIT_CONFIG_COUNT", "1");
            pb.environment().put("GIT_CONFIG_KEY_0", "http." + remoteUrl + ".extraHeader");
            pb.environment().put("GIT_CONFIG_VALUE_0", "Authorization: " + authHeader.orElseThrow());
        }

        Process proc = pb.start();
        try {
            var stdoutFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try (var in = proc.getInputStream()) {
                    return new String(in.readNBytes(256 * 1024), StandardCharsets.UTF_8);
                } catch (IOException ignored) { return ""; }
            });
            var stderrFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try (var in = proc.getErrorStream()) {
                    return new String(in.readNBytes(64 * 1024), StandardCharsets.UTF_8);
                } catch (IOException ignored) { return ""; }
            });
            boolean ok = proc.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!ok) {
                proc.destroyForcibly();
                throw new IOException("GIT_COMMAND_TIMEOUT");
            }
            String stdout = stdoutFuture.join();
            String stderr = stderrFuture.join();
            if (proc.exitValue() != 0) {
                throw new IOException("GIT_COMMAND_FAILED (" + proc.exitValue() + "): "
                        + GitHubTokenBroker.redact(stderr.isBlank() ? stdout : stderr));
            }
            return stdout;
        } catch (InterruptedException interrupted) {
            proc.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new PublishException(409, "REQUEST_CANCELLED", "Git command interrupted.");
        } finally {
            if (proc.isAlive()) {
                proc.destroyForcibly();
            }
        }
    }

    private static Path resolveSafeRepoFile(Path workDir, String relativePath) {
        String norm = relativePath.replace('\\', '/').replaceFirst("^/+", "");
        if (norm.isEmpty() || norm.contains("..") || norm.startsWith(".git/") || norm.equals(".git")) {
            throw new PublishException(400, "UNSAFE_EDIT_PATH", "Unsafe repository path: " + relativePath);
        }
        Path resolved = workDir.resolve(norm).toAbsolutePath().normalize();
        if (!resolved.startsWith(workDir)) {
            throw new PublishException(400, "UNSAFE_EDIT_PATH", "Path escapes repository root: " + relativePath);
        }
        return resolved;
    }

    private static DiffStats computeDiffStats(String patch, int editedFilesCount) {
        int ins = 0;
        int del = 0;
        for (String line : patch.split("\\R")) {
            if (line.startsWith("+") && !line.startsWith("+++")) ins++;
            else if (line.startsWith("-") && !line.startsWith("---")) del++;
        }
        return new DiffStats(editedFilesCount, ins, del);
    }

    private static String buildCommitMessage(String title, CapturedRepair.Proposal proposal,
            String planIdentity, String verdict, String trustBadge, List<String> editedPaths) {
        var sb = new StringBuilder();
        sb.append(title).append("\n\n");
        sb.append("Automated architecture repair patch prepared by AI-Native Verified Software Evolution Platform.\n");
        sb.append("Files: ").append(String.join(", ", editedPaths)).append("\n\n");
        sb.append("Evo-Proposal-Digest: ").append(proposal.identity().value()).append('\n');
        sb.append("Evo-Patch-Digest: ").append(proposal.candidate().patchDigest().value()).append('\n');
        sb.append("Evo-Verification-Plan: ").append(planIdentity).append('\n');
        sb.append("Evo-Verdict: ").append(verdict).append('\n');
        sb.append("Evo-Trust-Label: ").append(trustBadge).append('\n');
        return sb.toString();
    }

    private static String buildPrBody(
            CapturedRepair.Proposal proposal,
            Optional<CapturedRepairVerification.Result> verification,
            Optional<CapturedRepairVerification.IsolatedProof> proof,
            String verdict,
            String trustBadge,
            String trustBanner,
            DiffStats stats,
            List<String> editedPaths) {
        var c = proposal.candidate();
        var sb = new StringBuilder();
        if (!trustBanner.isEmpty()) {
            sb.append(trustBanner);
        }
        sb.append("## Verified Architecture Repair Report\n\n");
        sb.append("| Field | Value |\n|---|---|\n");
        sb.append("| **Verification Verdict** | `").append(verdict).append("` |\n");
        sb.append("| **Trust Badge** | `").append(trustBadge).append("` |\n");
        sb.append("| **Strategy** | `").append(c.strategy()).append("`");
        c.recipe().ifPresent(r -> sb.append(" (`").append(r.id()).append("`)"));
        sb.append(" |\n");
        sb.append("| **Diff Stats** | ").append(stats.filesChanged()).append(" file(s), +")
                .append(stats.insertions()).append(" / -").append(stats.deletions()).append(" lines |\n");
        sb.append("| **Baseline Context** | `").append(c.baseline().context()).append("` |\n\n");

        if (verification.isPresent()) {
            var v = verification.orElseThrow();
            sb.append("### Verification Checks\n");
            sb.append("| Check | Status | Reason |\n|---|---|---|\n");
            for (var chk : v.checks()) {
                sb.append("| `").append(chk.id()).append("` | `").append(chk.status())
                        .append("` | `").append(chk.reason()).append("` |\n");
            }
            sb.append('\n');
        }

        if (proof.isPresent()) {
            var p = proof.orElseThrow();
            sb.append("### Isolated Sandbox Proof\n");
            sb.append("- **Build Tool:** `").append(p.buildTool()).append("`  \n");
            sb.append("- **Compile Exit:** `").append(p.compileExit()).append("` | **Test Exit:** `")
                    .append(p.testExit()).append("`  \n");
            sb.append("- **Tests:** ").append(p.testsRun()).append(" run, ")
                    .append(p.testsFailed()).append(" failed, ").append(p.testsErrors()).append(" errors  \n");
            sb.append("- **Capture Digest:** `").append(p.capture().value()).append("`\n\n");
        }

        sb.append("### Edited Files\n");
        for (String path : editedPaths) {
            sb.append("- `").append(path).append("`\n");
        }
        sb.append("\n<details><summary>Cryptographic Provenance</summary>\n\n");
        sb.append("- **Proposal Digest:** `").append(proposal.identity().value()).append("`\n");
        sb.append("- **Patch Digest:** `").append(c.patchDigest().value()).append("`\n");
        sb.append("- **Baseline Snapshot:** `").append(c.baseline().snapshot().value()).append("`\n");
        sb.append("- **Baseline Finding:** `").append(c.baseline().finding().value()).append("`\n");
        sb.append("</details>\n");
        return sb.toString();
    }

    private static String sanitizeGitIdentity(String raw, String fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String cleaned = raw.replaceAll("[\\r\\n<>\\x00]", "").strip();
        if (cleaned.isEmpty() || cleaned.startsWith("-")) return fallback;
        return cleaned.length() > 120 ? cleaned.substring(0, 120) : cleaned;
    }

    private String randomTokenHex() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private void purgeExpiredPlans() {
        long now = System.currentTimeMillis();
        synchronized (pendingPlans) {
            pendingPlans.values().removeIf(p -> now > p.expiresAtMillis());
        }
    }

    private static void deleteQuietly(Path root) {
        if (root == null || !Files.exists(root)) return;
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        file.toFile().setWritable(true);
                        Files.deleteIfExists(file);
                    } catch (IOException ignored) {}
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    try { Files.deleteIfExists(dir); } catch (IOException ignored) {}
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {}
    }
}
