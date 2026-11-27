package com.evolution.backend.query.repair;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.backend.query.CapturedRepair;
import com.evolution.backend.query.CapturedRepairVerification;
import com.evolution.backend.query.RepairPatch;
import com.evolution.backend.query.RepairSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Two-phase, explicit-user-confirmation local patch applier with cryptographic SHA-256 before/after
 * guards, clean-tree inspection, isolated Git worktree branch commits ({@link ApplyMode#NEW_BRANCH_WORKTREE}),
 * atomic backup & rollback for direct working-tree applies ({@link ApplyMode#DIRECT_WORKING_TREE}),
 * and hard rejection of {@code REJECTED} proposals.
 *
 * <p>NEVER auto-pushes to any remote and NEVER mutates repository files during {@link #previewApply}.
 */
public final class SafePatchApplier {
    public static final String DEFAULT_OWNER = "local";
    private static final Duration TOKEN_TTL = Duration.ofMinutes(15);
    private static final int MAX_PENDING_TOKENS = 128;

    public static final class AccessDeniedException extends SecurityException {
        private final String code;

        public AccessDeniedException(String message) {
            this("APPLY_ACCESS_DENIED", message);
        }

        public AccessDeniedException(String code, String message) {
            super(message);
            this.code = (code == null || code.isBlank()) ? "APPLY_ACCESS_DENIED" : code;
        }

        public String code() {
            return code;
        }
    }

    public enum ApplyMode {
        NEW_BRANCH_WORKTREE,
        DIRECT_WORKING_TREE
    }

    public record FileCheck(
            String path,
            String operation,
            ContentDigest expectedBefore,
            Optional<ContentDigest> actualDiskDigest,
            boolean matchesBaseline,
            String detail) {}

    public record ApplyPreview(
            boolean canApply,
            ApplyMode mode,
            String suggestedBranch,
            Optional<CapturedRepairVerification.Verdict> verdict,
            Optional<RepairVerificationReport.TrustLabel> trustLabel,
            boolean requiresUnverifiedAck,
            boolean isGitRepo,
            boolean cleanWorkingTree,
            boolean gitApplyCheckPassed,
            List<FileCheck> fileChecks,
            List<String> conflicts,
            List<String> warnings,
            Optional<String> approvalToken,
            Optional<Instant> tokenExpiresAt) {
        public ApplyPreview {
            fileChecks = List.copyOf(fileChecks);
            conflicts = List.copyOf(conflicts);
            warnings = List.copyOf(warnings);
        }
    }

    public record ApplyResult(
            String applyId,
            ApplyMode mode,
            ContentDigest proposalIdentity,
            ContentDigest patchDigest,
            Optional<String> branchName,
            Optional<String> commitSha,
            Optional<String> baseCommitSha,
            List<String> modifiedFiles,
            List<String> createdFiles,
            List<String> deletedFiles,
            Optional<Path> backupDir,
            boolean rollbackAvailable,
            Instant appliedAt,
            String ownerUserId) {
        public ApplyResult {
            modifiedFiles = List.copyOf(modifiedFiles);
            createdFiles = List.copyOf(createdFiles);
            deletedFiles = List.copyOf(deletedFiles);
            ownerUserId = normalizeOwner(ownerUserId);
        }

        public ApplyResult(
                String applyId,
                ApplyMode mode,
                ContentDigest proposalIdentity,
                ContentDigest patchDigest,
                Optional<String> branchName,
                Optional<String> commitSha,
                Optional<String> baseCommitSha,
                List<String> modifiedFiles,
                List<String> createdFiles,
                List<String> deletedFiles,
                Optional<Path> backupDir,
                boolean rollbackAvailable,
                Instant appliedAt) {
            this(applyId, mode, proposalIdentity, patchDigest, branchName, commitSha, baseCommitSha,
                    modifiedFiles, createdFiles, deletedFiles, backupDir, rollbackAvailable, appliedAt, DEFAULT_OWNER);
        }
    }

    public record RollbackResult(
            String applyId,
            boolean restored,
            List<String> restoredFiles,
            List<String> removedCreatedFiles,
            String message) {
        public RollbackResult {
            restoredFiles = List.copyOf(restoredFiles);
            removedCreatedFiles = List.copyOf(removedCreatedFiles);
        }
    }

    private record PendingApproval(
            String token,
            String ownerUserId,
            ContentDigest proposalIdentity,
            ContentDigest patchDigest,
            Path repoDir,
            ApplyMode mode,
            String branchName,
            Instant expiresAt) {}

    private final Path backupStoreRoot;
    private final byte[] tokenSecret;
    private final Map<String, PendingApproval> pendingTokens = Collections.synchronizedMap(
            new LinkedHashMap<>(32, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, PendingApproval> eldest) {
                    return size() > MAX_PENDING_TOKENS;
                }
            });

    public SafePatchApplier(Path backupStoreRoot) {
        this.backupStoreRoot = Objects.requireNonNull(backupStoreRoot).toAbsolutePath().normalize();
        this.tokenSecret = new byte[32];
        new SecureRandom().nextBytes(this.tokenSecret);
    }

    /** Number of currently active pending approval tokens (for diagnostics and leak regression checks). */
    public int pendingTokenCount() {
        return pendingTokens.size();
    }

    /**
     * Strictly read-only preflight check. Never modifies any file or Git ref.
     * Issues a time-limited content-addressed {@code approvalToken} when {@code canApply == true}.
     */
    public ApplyPreview previewApply(
            CapturedRepair.Proposal proposal,
            Optional<RepairVerificationReport.Report> report,
            Path repoDir,
            ApplyMode mode,
            Optional<String> requestedBranch) throws IOException {
        return previewApply(DEFAULT_OWNER, proposal, report, repoDir, mode, requestedBranch);
    }

    public ApplyPreview previewApply(
            String ownerUserId,
            CapturedRepair.Proposal proposal,
            Optional<RepairVerificationReport.Report> report,
            Path repoDir,
            ApplyMode mode,
            Optional<String> requestedBranch) throws IOException {
        return evaluatePreflight(normalizeOwner(ownerUserId), proposal, report, repoDir, mode, requestedBranch, true);
    }

    private ApplyPreview evaluatePreflight(
            String effectiveOwner,
            CapturedRepair.Proposal proposal,
            Optional<RepairVerificationReport.Report> report,
            Path repoDir,
            ApplyMode mode,
            Optional<String> requestedBranch,
            boolean issueToken) throws IOException {
        Objects.requireNonNull(proposal);
        Objects.requireNonNull(repoDir);
        ApplyMode effectiveMode = mode == null ? ApplyMode.NEW_BRANCH_WORKTREE : mode;
        Path root = repoDir.toAbsolutePath().normalize();

        var conflicts = new ArrayList<String>();
        var warnings = new ArrayList<String>();
        var fileChecks = new ArrayList<FileCheck>();

        if (!CapturedRepair.authentic(proposal)) {
            conflicts.add("PROPOSAL_INTEGRITY_INVALID: proposal digest or patch digest failed verification.");
        }
        var verdict = report.map(RepairVerificationReport.Report::verdict);
        var trustLabel = report.map(RepairVerificationReport.Report::trustLabel);
        boolean requiresUnverifiedAck = verdict.isEmpty() || verdict.orElseThrow() != CapturedRepairVerification.Verdict.VERIFIED;
        if (verdict.isPresent() && verdict.orElseThrow() == CapturedRepairVerification.Verdict.REJECTED) {
            conflicts.add("VERIFICATION_REJECTED: rejected proposals cannot be applied to source code.");
        } else if (requiresUnverifiedAck) {
            warnings.add("Proposal is not VERIFIED by isolated sandbox build+tests; explicit acknowledgeUnverified=true is required.");
        }

        if (!Files.isDirectory(root)) {
            conflicts.add("REPO_DIRECTORY_MISSING: " + root);
        }

        String shortHash = proposal.identity().value().replace("sha256:", "");
        shortHash = shortHash.substring(0, Math.min(10, shortHash.length()));
        String branch = requestedBranch != null && requestedBranch.isPresent() && !requestedBranch.orElseThrow().isBlank()
                ? requestedBranch.orElseThrow().strip()
                : ("evo/repair-" + shortHash);
        if (effectiveMode == ApplyMode.NEW_BRANCH_WORKTREE) {
            try {
                GitWorktreeManager.validateBranchName(branch);
            } catch (IllegalArgumentException invalid) {
                conflicts.add("INVALID_BRANCH_NAME: " + invalid.getMessage());
            }
        }

        var gitStatus = Files.isDirectory(root)
                ? GitWorktreeManager.inspect(root)
                : new GitWorktreeManager.RepoStatus(false, "", "", false, List.of());

        if (effectiveMode == ApplyMode.NEW_BRANCH_WORKTREE) {
            if (!gitStatus.isGitRepo()) {
                conflicts.add("NOT_A_GIT_REPOSITORY: NEW_BRANCH_WORKTREE requires a Git repository.");
            } else if (conflicts.stream().noneMatch(c -> c.startsWith("INVALID_BRANCH_NAME"))
                    && GitWorktreeManager.branchExists(root, branch)) {
                conflicts.add("BRANCH_ALREADY_EXISTS: local branch '" + branch + "' already exists.");
            }
            if (!gitStatus.cleanWorkingTree()) {
                warnings.add("Current working tree has uncommitted changes; NEW_BRANCH_WORKTREE will leave your working tree untouched and branch cleanly from HEAD.");
            }
        } else {
            if (gitStatus.isGitRepo() && !gitStatus.cleanWorkingTree()) {
                warnings.add("Working tree has uncommitted changes; verify backups before DIRECT_WORKING_TREE apply.");
            }
        }

        // Verify every edited file's live SHA-256 matches proposal.before (or non-existence for CREATE)
        for (var edit : proposal.candidate().edits()) {
            String rel = edit.path();
            if (!RepairSource.isSafeRelativePath(rel)) {
                conflicts.add("UNSAFE_EDIT_PATH: " + rel);
                continue;
            }
            Path diskFile = root.resolve(rel).normalize();
            if (!diskFile.startsWith(root)) {
                conflicts.add("PATH_ESCAPES_REPO: " + rel);
                continue;
            }
            if (edit.isCreate()) {
                boolean exists = Files.exists(diskFile, LinkOption.NOFOLLOW_LINKS);
                Optional<ContentDigest> actual = exists && Files.isRegularFile(diskFile)
                        ? Optional.of(ContentDigest.sha256(Files.readAllBytes(diskFile))) : Optional.empty();
                if (exists) {
                    conflicts.add("CREATE_TARGET_EXISTS: " + rel);
                    fileChecks.add(new FileCheck(rel, "CREATE", edit.before(), actual, false, "File already exists on disk"));
                } else {
                    fileChecks.add(new FileCheck(rel, "CREATE", edit.before(), Optional.empty(), true, "Target does not exist (ready to create)"));
                }
            } else {
                String op = edit.isDelete() ? "DELETE" : "MODIFY";
                if (!Files.isRegularFile(diskFile, LinkOption.NOFOLLOW_LINKS)) {
                    conflicts.add("TARGET_FILE_MISSING: " + rel);
                    fileChecks.add(new FileCheck(rel, op, edit.before(), Optional.empty(), false, "File missing on disk"));
                } else {
                    ContentDigest actual = ContentDigest.sha256(Files.readAllBytes(diskFile));
                    boolean matches = actual.equals(edit.before());
                    if (!matches) {
                        conflicts.add("BASELINE_DRIFT: " + rel + " has changed since analysis (expected "
                                + edit.before().value() + ", disk " + actual.value() + ")");
                    }
                    fileChecks.add(new FileCheck(rel, op, edit.before(), Optional.of(actual), matches,
                            matches ? "Exact SHA-256 match" : "Disk contents drifted from analyzed snapshot"));
                }
            }
        }

        boolean gitApplyOk = false;
        if (gitStatus.isGitRepo() && conflicts.stream().noneMatch(c -> c.startsWith("PROPOSAL_INTEGRITY_INVALID"))) {
            var checkRes = GitWorktreeManager.checkPatchApplies(root, proposal.candidate().patch());
            gitApplyOk = checkRes.ok();
            if (!gitApplyOk && effectiveMode == ApplyMode.DIRECT_WORKING_TREE) {
                conflicts.add("GIT_APPLY_CHECK_FAILED: " + checkRes.output().strip());
            }
        } else if (!gitStatus.isGitRepo() && conflicts.isEmpty()) {
            // Non-git directory: verify in-memory RepairPatch round-trip against disk bytes
            try {
                verifyInMemoryApply(root, proposal);
                gitApplyOk = true;
            } catch (IllegalArgumentException mismatch) {
                conflicts.add("PATCH_CONTEXT_MISMATCH: " + mismatch.getMessage());
            }
        }

        boolean canApply = conflicts.isEmpty();
        Optional<String> token = Optional.empty();
        Optional<Instant> expires = Optional.empty();
        if (canApply && issueToken) {
            Instant exp = Instant.now().plus(TOKEN_TTL);
            String issued = issueApprovalToken(effectiveOwner, proposal, root, effectiveMode, branch, exp);
            pendingTokens.put(issued, new PendingApproval(issued, effectiveOwner, proposal.identity(),
                    proposal.candidate().patchDigest(), root, effectiveMode, branch, exp));
            token = Optional.of(issued);
            expires = Optional.of(exp);
        }

        return new ApplyPreview(canApply, effectiveMode, branch, verdict, trustLabel, requiresUnverifiedAck,
                gitStatus.isGitRepo(), gitStatus.cleanWorkingTree(), gitApplyOk,
                fileChecks, conflicts, warnings, token, expires);
    }

    /**
     * Executes patch application ONLY after explicit user confirmation with a valid, non-expired
     * {@code approvalToken} issued by {@link #previewApply}.
     */
    public ApplyResult confirmApply(
            CapturedRepair.Proposal proposal,
            Optional<RepairVerificationReport.Report> report,
            String approvalToken,
            boolean userConfirmed,
            Path repoDir,
            ApplyMode mode,
            Optional<String> requestedBranch,
            Optional<String> commitMessage,
            boolean acknowledgeUnverified) throws IOException {
        return confirmApply(DEFAULT_OWNER, proposal, report, approvalToken, userConfirmed, repoDir, mode,
                requestedBranch, commitMessage, acknowledgeUnverified, Optional.empty());
    }

    public ApplyResult confirmApply(
            String ownerUserId,
            CapturedRepair.Proposal proposal,
            Optional<RepairVerificationReport.Report> report,
            String approvalToken,
            boolean userConfirmed,
            Path repoDir,
            ApplyMode mode,
            Optional<String> requestedBranch,
            Optional<String> commitMessage,
            boolean acknowledgeUnverified,
            Optional<GitWorktreeManager.CommitAuthor> commitAuthor) throws IOException {
        String effectiveOwner = normalizeOwner(ownerUserId);
        if (!userConfirmed) {
            throw new IllegalArgumentException("EXPLICIT_USER_CONFIRMATION_REQUIRED");
        }
        if (approvalToken == null || approvalToken.isBlank()) {
            throw new IllegalArgumentException("APPROVAL_TOKEN_REQUIRED");
        }
        var pending = pendingTokens.get(approvalToken);
        if (pending == null || Instant.now().isAfter(pending.expiresAt())) {
            pendingTokens.remove(approvalToken);
            throw new IllegalArgumentException("APPROVAL_TOKEN_INVALID_OR_EXPIRED");
        }
        // Tenant isolation: reject cross-user confirmation WITHOUT consuming the owner's token!
        if (!pending.ownerUserId().equals(effectiveOwner)) {
            throw new AccessDeniedException("Approval token belongs to another user.");
        }
        Path root = repoDir.toAbsolutePath().normalize();
        ApplyMode effectiveMode = mode == null ? pending.mode() : mode;
        if (!pending.proposalIdentity().equals(proposal.identity())
                || !pending.patchDigest().equals(proposal.candidate().patchDigest())
                || !pending.repoDir().equals(root)
                || pending.mode() != effectiveMode) {
            throw new IllegalArgumentException("APPROVAL_TOKEN_SCOPE_MISMATCH");
        }
        var verdict = report.map(RepairVerificationReport.Report::verdict);
        if (verdict.isPresent() && verdict.orElseThrow() == CapturedRepairVerification.Verdict.REJECTED) {
            throw new IllegalStateException("VERIFICATION_REJECTED");
        }
        boolean isVerified = verdict.isPresent() && verdict.orElseThrow() == CapturedRepairVerification.Verdict.VERIFIED;
        if (!isVerified && !acknowledgeUnverified) {
            throw new IllegalArgumentException("UNVERIFIED_ACKNOWLEDGMENT_REQUIRED");
        }

        // Resolve user-edited branch override (if non-blank) or fall back to previewed branch
        String effectiveBranch = (requestedBranch != null && requestedBranch.isPresent()
                && !requestedBranch.orElseThrow().isBlank())
                ? requestedBranch.orElseThrow().strip()
                : pending.branchName();

        // Re-check live preflight right before mutating to catch TOCTOU drift without leaking a new token
        var liveCheck = evaluatePreflight(effectiveOwner, proposal, report, root, effectiveMode,
                Optional.of(effectiveBranch), false);
        if (!liveCheck.canApply()) {
            throw new IOException("PREFLIGHT_CONFLICT: " + String.join("; ", liveCheck.conflicts()));
        }
        pendingTokens.remove(approvalToken);

        String applyId = "apply-" + UUID.randomUUID().toString().substring(0, 12);
        Instant now = Instant.now();
        var effectiveAuthor = (commitAuthor != null && commitAuthor.isPresent())
                ? commitAuthor.orElseThrow() : GitWorktreeManager.CommitAuthor.DEFAULT_AUTHOR;

        if (effectiveMode == ApplyMode.NEW_BRANCH_WORKTREE) {
            String branch = liveCheck.suggestedBranch();
            String msg = buildCommitMessage(proposal, report, commitMessage, effectiveOwner, effectiveAuthor);
            var expectedPaths = proposal.candidate().edits().stream().map(CapturedRepair.Edit::path).toList();
            var wtRes = GitWorktreeManager.applyInNewBranchWorktree(root, branch, proposal.candidate().patch(),
                    msg, expectedPaths, effectiveAuthor);
            var modified = new ArrayList<String>();
            var created = new ArrayList<String>();
            var deleted = new ArrayList<String>();
            for (var e : proposal.candidate().edits()) {
                if (e.isCreate()) created.add(e.path());
                else if (e.isDelete()) deleted.add(e.path());
                else modified.add(e.path());
            }
            return new ApplyResult(applyId, effectiveMode, proposal.identity(), proposal.candidate().patchDigest(),
                    Optional.of(wtRes.branchName()), Optional.of(wtRes.commitSha()), Optional.of(wtRes.baseCommitSha()),
                    modified, created, deleted, Optional.empty(), false, now, effectiveOwner);
        }

        // DIRECT_WORKING_TREE: write atomic backup manifest + backup copies BEFORE modifying working tree
        var applied = verifyInMemoryApply(root, proposal);
        Path backupDir = backupStoreRoot.resolve("apply-backups").resolve(applyId);
        Path backupFilesDir = backupDir.resolve("files");
        Files.createDirectories(backupFilesDir);

        var manifestEntries = new ArrayList<Map<String, Object>>();
        var modified = new ArrayList<String>();
        var created = new ArrayList<String>();
        var deleted = new ArrayList<String>();

        for (var edit : proposal.candidate().edits()) {
            String rel = edit.path();
            Path diskFile = root.resolve(rel).normalize();
            var entry = new LinkedHashMap<String, Object>();
            entry.put("path", rel);
            entry.put("before", edit.before().value());
            entry.put("after", edit.after().value());
            if (edit.isCreate()) {
                entry.put("op", "CREATE");
                created.add(rel);
            } else {
                entry.put("op", edit.isDelete() ? "DELETE" : "MODIFY");
                byte[] orig = Files.readAllBytes(diskFile);
                if (!ContentDigest.sha256(orig).equals(edit.before())) {
                    throw new IOException("TOCTOU_BASELINE_DRIFT: " + rel);
                }
                Path bak = backupFilesDir.resolve(rel).normalize();
                if (bak.getParent() != null) Files.createDirectories(bak.getParent());
                Files.write(bak, orig);
                if (edit.isDelete()) deleted.add(rel);
                else modified.add(rel);
            }
            manifestEntries.add(entry);
        }

        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("applyId", applyId);
        manifest.put("ownerUserId", effectiveOwner);
        manifest.put("appliedByName", effectiveAuthor.name());
        manifest.put("repoDir", root.toString());
        manifest.put("proposal", proposal.identity().value());
        manifest.put("patchDigest", proposal.candidate().patchDigest().value());
        manifest.put("appliedAt", now.toString());
        manifest.put("edits", manifestEntries);
        Files.writeString(backupDir.resolve("manifest.json"), CanonicalJson.write(manifest), StandardCharsets.UTF_8);

        // Apply files atomically and verify post-write SHA-256 == edit.after
        try {
            for (var edit : proposal.candidate().edits()) {
                String rel = edit.path();
                Path diskFile = root.resolve(rel).normalize();
                if (edit.isDelete()) {
                    Files.deleteIfExists(diskFile);
                } else {
                    String nextText = applied.updated().get(rel);
                    if (nextText == null) throw new IOException("PATCH_OUTPUT_MISSING: " + rel);
                    byte[] nextBytes = nextText.getBytes(StandardCharsets.UTF_8);
                    if (!ContentDigest.sha256(nextBytes).equals(edit.after())) {
                        throw new IOException("POST_APPLY_DIGEST_MISMATCH: " + rel);
                    }
                    if (diskFile.getParent() != null) Files.createDirectories(diskFile.getParent());
                    Path tmp = diskFile.getParent().resolve("." + diskFile.getFileName() + ".evo-tmp");
                    Files.write(tmp, nextBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    try {
                        Files.move(tmp, diskFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException fallback) {
                        Files.move(tmp, diskFile, StandardCopyOption.REPLACE_EXISTING);
                    }
                    // Final disk read-back SHA-256 verification
                    if (!ContentDigest.sha256(Files.readAllBytes(diskFile)).equals(edit.after())) {
                        throw new IOException("DISK_VERIFY_FAILED: " + rel);
                    }
                }
            }
        } catch (IOException | RuntimeException failed) {
            // Auto-rollback on partial write failure so working tree is never left half-patched
            try { rollbackApply(effectiveOwner, applyId, true); } catch (IOException ignored) {}
            throw failed;
        }

        return new ApplyResult(applyId, effectiveMode, proposal.identity(), proposal.candidate().patchDigest(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                modified, created, deleted, Optional.of(backupDir), true, now, effectiveOwner);
    }

    /**
     * Rolls back a previous {@link ApplyMode#DIRECT_WORKING_TREE} apply using its persisted backup bundle.
     * Refuses to clobber user edits made after apply unless {@code force == true}.
     */
    public RollbackResult rollbackApply(String applyId, boolean force) throws IOException {
        return rollbackApply(DEFAULT_OWNER, applyId, force);
    }

    public RollbackResult rollbackApply(String ownerUserId, String applyId, boolean force) throws IOException {
        String effectiveOwner = normalizeOwner(ownerUserId);
        if (applyId == null || !applyId.matches("[A-Za-z0-9._-]{4,64}")) {
            throw new IllegalArgumentException("Invalid applyId");
        }
        Path backupDir = backupStoreRoot.resolve("apply-backups").resolve(applyId);
        Path manifestFile = backupDir.resolve("manifest.json");
        if (!Files.isRegularFile(manifestFile)) {
            throw new IOException("APPLY_BACKUP_NOT_FOUND: " + applyId);
        }
        var manifest = com.evolution.analysis.graph.GraphJson.object(
                com.evolution.analysis.graph.GraphJson.read(
                        Files.readString(manifestFile, StandardCharsets.UTF_8),
                        com.evolution.analysis.graph.GraphJson.Limits.defaults()));
        String manifestOwner = manifest.get("ownerUserId") instanceof String s && !s.isBlank()
                ? s.strip() : DEFAULT_OWNER;
        if (!manifestOwner.equals(effectiveOwner)) {
            throw new AccessDeniedException("Apply backup belongs to another user.");
        }
        Path root = Path.of(com.evolution.analysis.graph.GraphJson.string(manifest.get("repoDir"))).toAbsolutePath().normalize();
        var edits = com.evolution.analysis.graph.GraphJson.array(manifest.get("edits"));

        // Pre-check that current disk state still matches `after` unless force=true
        if (!force) {
            for (Object item : edits) {
                var row = com.evolution.analysis.graph.GraphJson.object(item);
                String rel = com.evolution.analysis.graph.GraphJson.string(row.get("path"));
                String op = com.evolution.analysis.graph.GraphJson.string(row.get("op"));
                var expectedAfter = new ContentDigest(com.evolution.analysis.graph.GraphJson.string(row.get("after")));
                Path diskFile = root.resolve(rel).normalize();
                if ("DELETE".equals(op)) {
                    if (Files.exists(diskFile)) {
                        throw new IOException("ROLLBACK_CLOBBER_GUARD: " + rel + " was recreated after apply; pass force=true to overwrite.");
                    }
                } else {
                    if (!Files.isRegularFile(diskFile) || !ContentDigest.sha256(Files.readAllBytes(diskFile)).equals(expectedAfter)) {
                        throw new IOException("ROLLBACK_CLOBBER_GUARD: " + rel + " was modified after apply; pass force=true to overwrite.");
                    }
                }
            }
        }

        var restored = new ArrayList<String>();
        var removed = new ArrayList<String>();
        Path backupFilesDir = backupDir.resolve("files");
        for (Object item : edits) {
            var row = com.evolution.analysis.graph.GraphJson.object(item);
            String rel = com.evolution.analysis.graph.GraphJson.string(row.get("path"));
            String op = com.evolution.analysis.graph.GraphJson.string(row.get("op"));
            var expectedBefore = new ContentDigest(com.evolution.analysis.graph.GraphJson.string(row.get("before")));
            Path diskFile = root.resolve(rel).normalize();
            if ("CREATE".equals(op)) {
                Files.deleteIfExists(diskFile);
                removed.add(rel);
            } else {
                Path bak = backupFilesDir.resolve(rel).normalize();
                byte[] orig = Files.readAllBytes(bak);
                if (!ContentDigest.sha256(orig).equals(expectedBefore)) {
                    throw new IOException("BACKUP_CORRUPT: " + rel);
                }
                if (diskFile.getParent() != null) Files.createDirectories(diskFile.getParent());
                Files.write(diskFile, orig, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                restored.add(rel);
            }
        }
        return new RollbackResult(applyId, true, restored, removed, "Rollback verified via SHA-256.");
    }

    private static RepairPatch.ApplyResult verifyInMemoryApply(Path root, CapturedRepair.Proposal proposal) throws IOException {
        var originals = new LinkedHashMap<String, String>();
        for (var edit : proposal.candidate().edits()) {
            if (!edit.isCreate()) {
                Path f = root.resolve(edit.path()).normalize();
                originals.put(edit.path(), Files.readString(f, StandardCharsets.UTF_8));
            }
        }
        return RepairPatch.applyAllDetailed(originals, proposal.candidate().patch());
    }

    private static String normalizeOwner(String ownerUserId) {
        return (ownerUserId == null || ownerUserId.isBlank()) ? DEFAULT_OWNER : ownerUserId.strip();
    }

    private String issueApprovalToken(String ownerUserId, CapturedRepair.Proposal proposal, Path root,
                                      ApplyMode mode, String branch, Instant expires) {
        String payload = CanonicalJson.write(List.of(
                ownerUserId,
                proposal.identity().value(),
                proposal.candidate().patchDigest().value(),
                root.toString(),
                mode.name(),
                branch,
                expires.toEpochMilli(),
                Base64.getEncoder().encodeToString(tokenSecret)));
        return "evo-approve-" + ContentDigest.sha256Utf8(payload).value().substring(7, 39);
    }

    private static String buildCommitMessage(CapturedRepair.Proposal proposal,
                                             Optional<RepairVerificationReport.Report> report,
                                             Optional<String> custom,
                                             String ownerUserId,
                                             GitWorktreeManager.CommitAuthor author) {
        String subject = (custom != null && custom.isPresent() && !custom.orElseThrow().isBlank())
                ? custom.orElseThrow().strip()
                : ("fix(arch): apply verified repair for " + proposal.candidate().affectedScope().rule().id());
        var sb = new StringBuilder(subject).append("\n\n");
        sb.append("Evo-Proposal: ").append(proposal.identity().value()).append('\n');
        sb.append("Evo-Patch-SHA256: ").append(proposal.candidate().patchDigest().value()).append('\n');
        sb.append("Evo-Owner: ").append(ownerUserId).append('\n');
        report.ifPresent(r -> {
            sb.append("Evo-Verification: ").append(r.verdict().name()).append('\n');
            sb.append("Evo-Trust-Level: ").append(r.trustLabel().name()).append('\n');
            sb.append("Evo-Report: ").append(r.reportIdentity().value()).append('\n');
        });
        if (author != null && author.coAuthorLine().isPresent()) {
            sb.append(author.coAuthorLine().orElseThrow()).append('\n');
        }
        return sb.toString();
    }
}
