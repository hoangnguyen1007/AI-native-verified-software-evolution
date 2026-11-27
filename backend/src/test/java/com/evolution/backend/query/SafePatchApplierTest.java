package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.query.ArchitectureQuery;
import com.evolution.backend.query.repair.GitWorktreeManager;
import com.evolution.backend.query.repair.RepairVerificationReport;
import com.evolution.backend.query.repair.SafePatchApplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

final class SafePatchApplierTest {
    @TempDir Path temp;

    @Test
    void enforcesConfirmationTokensRejectsDriftAndRejectedReportsAndAppliesSafelyViaWorktreeAndDirectRollback() throws Exception {
        Path repo = Files.createDirectory(temp.resolve("repo"));
        Path backups = Files.createDirectory(temp.resolve("backups"));
        Path serviceFile = repo.resolve("src/ServiceA.java");
        Files.createDirectories(serviceFile.getParent());
        String beforeSrc = "package demo;\npublic class ServiceA {\n    int x = 1;\n}\n";
        String afterSrc = "package demo;\npublic class ServiceA {\n    int x = 2;\n}\n";
        String newPortSrc = "package demo;\npublic interface ServiceAPort {\n    void ping();\n}\n";
        Files.writeString(serviceFile, beforeSrc, StandardCharsets.UTF_8);

        // Initialize real Git repo with initial commit
        assertTrue(GitWorktreeManager.runGit(repo, List.of("init")).ok());
        assertTrue(GitWorktreeManager.runGit(repo, List.of("config", "user.name", "Test")).ok());
        assertTrue(GitWorktreeManager.runGit(repo, List.of("config", "user.email", "test@local.invalid")).ok());
        assertTrue(GitWorktreeManager.runGit(repo, List.of("add", ".")).ok());
        assertTrue(GitWorktreeManager.runGit(repo, List.of("commit", "-m", "initial")).ok());

        String patch = RepairPatch.file("src/ServiceA.java", beforeSrc, afterSrc)
                + RepairPatch.createFile("src/ServiceAPort.java", newPortSrc);
        var proposal = buildProposal(beforeSrc, afterSrc, newPortSrc, patch);
        var verifiedReport = fakeReport(proposal, CapturedRepairVerification.Verdict.VERIFIED, RepairVerificationReport.TrustLabel.ISOLATED_VM_VERIFIED);
        var rejectedReport = fakeReport(proposal, CapturedRepairVerification.Verdict.REJECTED, RepairVerificationReport.TrustLabel.ISOLATED_VM_VERIFIED);
        var unverifiedReport = fakeReport(proposal, CapturedRepairVerification.Verdict.UNVERIFIED, RepairVerificationReport.TrustLabel.PASSIVE_REANALYSIS_ONLY);

        var applier = new SafePatchApplier(backups);

        // 1. REJECTED report is hard-blocked
        var rejectedPreview = applier.previewApply(proposal, Optional.of(rejectedReport), repo,
                SafePatchApplier.ApplyMode.NEW_BRANCH_WORKTREE, Optional.of("evo/rejected-branch"));
        assertFalse(rejectedPreview.canApply());
        assertTrue(rejectedPreview.approvalToken().isEmpty());
        assertTrue(rejectedPreview.conflicts().stream().anyMatch(c -> c.startsWith("VERIFICATION_REJECTED")));

        // 2. Dirty working tree on an unrelated file does NOT block NEW_BRANCH_WORKTREE and stays untouched!
        Path dirtyFile = repo.resolve("UNRELATED_DIRTY.txt");
        Files.writeString(dirtyFile, "wip local edits\n", StandardCharsets.UTF_8);

        // Plant malicious .git/hooks/post-checkout and pre-commit hooks — must NEVER execute on host!
        Path hooksDir = repo.resolve(".git/hooks");
        Files.createDirectories(hooksDir);
        Path hookSentinel = temp.resolve("hook-executed-sentinel.txt");
        String hookScript = "#!/bin/sh\necho PWNED > \"" + hookSentinel.toAbsolutePath().toString().replace("\\", "/") + "\"\n";
        Path postCheckoutHook = hooksDir.resolve("post-checkout");
        Path preCommitHook = hooksDir.resolve("pre-commit");
        Files.writeString(postCheckoutHook, hookScript, StandardCharsets.UTF_8);
        Files.writeString(preCommitHook, hookScript, StandardCharsets.UTF_8);
        postCheckoutHook.toFile().setExecutable(true);
        preCommitHook.toFile().setExecutable(true);

        var wtPreview = applier.previewApply(proposal, Optional.of(verifiedReport), repo,
                SafePatchApplier.ApplyMode.NEW_BRANCH_WORKTREE, Optional.of("evo/repair-preview-default"));
        assertTrue(wtPreview.canApply(), () -> "Conflicts: " + wtPreview.conflicts());
        assertTrue(wtPreview.approvalToken().isPresent());
        assertEquals(1, applier.pendingTokenCount());
        String token = wtPreview.approvalToken().orElseThrow();

        // Must refuse if userConfirmed=false or forged token
        assertThrows(IllegalArgumentException.class, () -> applier.confirmApply(
                proposal, Optional.of(verifiedReport), token, false, repo,
                SafePatchApplier.ApplyMode.NEW_BRANCH_WORKTREE, Optional.of("evo/repair-safe-test"), Optional.empty(), false));
        assertThrows(IllegalArgumentException.class, () -> applier.confirmApply(
                proposal, Optional.of(verifiedReport), "evo-approve-forged", true, repo,
                SafePatchApplier.ApplyMode.NEW_BRANCH_WORKTREE, Optional.of("evo/repair-safe-test"), Optional.empty(), false));

        // Confirmed apply honors user-edited custom requestedBranch, does NOT leak tokens, and NEVER fires hooks!
        var wtResult = applier.confirmApply(proposal, Optional.of(verifiedReport), token, true, repo,
                SafePatchApplier.ApplyMode.NEW_BRANCH_WORKTREE, Optional.of("evo/repair-safe-test"), Optional.empty(), false);
        assertEquals(Optional.of("evo/repair-safe-test"), wtResult.branchName());
        assertTrue(GitWorktreeManager.branchExists(repo, "evo/repair-safe-test"), "Custom requestedBranch must be created");
        assertFalse(GitWorktreeManager.branchExists(repo, "evo/repair-preview-default"), "Overridden preview branch must not be created");
        assertEquals(0, applier.pendingTokenCount(), "confirmApply must not leak a recursive preview token");
        assertFalse(Files.exists(hookSentinel), "Untrusted repo .git/hooks/post-checkout or pre-commit MUST NOT execute!");
        assertTrue(wtResult.commitSha().isPresent());
        assertEquals(beforeSrc, Files.readString(serviceFile, StandardCharsets.UTF_8), "Main working tree must stay untouched");
        assertTrue(Files.exists(dirtyFile), "User dirty file must stay untouched");
        assertFalse(Files.exists(repo.resolve("src/ServiceAPort.java")), "New file only exists on new branch");

        var branchLog = GitWorktreeManager.runGit(repo, List.of("log", "-1", "--pretty=%B", "evo/repair-safe-test"));
        assertTrue(branchLog.output().contains("Evo-Proposal: " + proposal.identity().value()), branchLog.output());
        assertTrue(branchLog.output().contains("Evo-Verification: VERIFIED"), branchLog.output());
        var showNewFile = GitWorktreeManager.runGit(repo, List.of("show", "evo/repair-safe-test:src/ServiceAPort.java"));
        assertEquals(newPortSrc, showNewFile.output());

        // 3. UNVERIFIED requires acknowledgeUnverified=true for DIRECT_WORKING_TREE
        var directPreview = applier.previewApply(proposal, Optional.of(unverifiedReport), repo,
                SafePatchApplier.ApplyMode.DIRECT_WORKING_TREE, Optional.empty());
        assertTrue(directPreview.canApply());
        assertTrue(directPreview.requiresUnverifiedAck());
        String directToken = directPreview.approvalToken().orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> applier.confirmApply(
                proposal, Optional.of(unverifiedReport), directToken, true, repo,
                SafePatchApplier.ApplyMode.DIRECT_WORKING_TREE, Optional.empty(), Optional.empty(), false));

        // Confirmed DIRECT_WORKING_TREE apply + verified SHA-256 + backup + rollback + clobber guard
        var directResult = applier.confirmApply(proposal, Optional.of(unverifiedReport), directToken, true, repo,
                SafePatchApplier.ApplyMode.DIRECT_WORKING_TREE, Optional.empty(), Optional.empty(), true);
        assertTrue(directResult.rollbackAvailable());
        assertEquals(afterSrc, Files.readString(serviceFile, StandardCharsets.UTF_8));
        assertEquals(newPortSrc, Files.readString(repo.resolve("src/ServiceAPort.java"), StandardCharsets.UTF_8));

        // Modify file after apply -> rollback clobber guard blocks force=false, allows force=true
        Files.writeString(serviceFile, afterSrc + "// user edit\n", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> applier.rollbackApply(directResult.applyId(), false));
        var rolledBack = applier.rollbackApply(directResult.applyId(), true);
        assertTrue(rolledBack.restored());
        assertEquals(beforeSrc, Files.readString(serviceFile, StandardCharsets.UTF_8));
        assertFalse(Files.exists(repo.resolve("src/ServiceAPort.java")));

        // 4. Baseline SHA-256 drift blocks previewApply
        Files.writeString(serviceFile, "// drifted externally\n", StandardCharsets.UTF_8);
        var driftPreview = applier.previewApply(proposal, Optional.of(verifiedReport), repo,
                SafePatchApplier.ApplyMode.DIRECT_WORKING_TREE, Optional.empty());
        assertFalse(driftPreview.canApply());
        assertTrue(driftPreview.conflicts().stream().anyMatch(c -> c.startsWith("BASELINE_DRIFT")));
    }

    @Test
    void enforcesMultiUserTenantIsolationAndGitHubCommitAuthorAttribution() throws Exception {
        Path repo = Files.createDirectory(temp.resolve("multi-user-repo"));
        Path backups = Files.createDirectory(temp.resolve("multi-user-backups"));
        Path serviceFile = repo.resolve("src/ServiceA.java");
        Files.createDirectories(serviceFile.getParent());
        String beforeSrc = "package demo;\npublic class ServiceA {\n    int x = 1;\n}\n";
        String afterSrc = "package demo;\npublic class ServiceA {\n    int x = 2;\n}\n";
        String newPortSrc = "package demo;\npublic interface ServiceAPort {\n    void ping();\n}\n";
        Files.writeString(serviceFile, beforeSrc, StandardCharsets.UTF_8);

        assertTrue(GitWorktreeManager.runGit(repo, List.of("init")).ok());
        assertTrue(GitWorktreeManager.runGit(repo, List.of("config", "user.name", "Init")).ok());
        assertTrue(GitWorktreeManager.runGit(repo, List.of("config", "user.email", "init@local.invalid")).ok());
        assertTrue(GitWorktreeManager.runGit(repo, List.of("add", ".")).ok());
        assertTrue(GitWorktreeManager.runGit(repo, List.of("commit", "-m", "initial")).ok());

        // Plant post-checkout hook to prove hookless isolation remains intact with custom CommitAuthor
        Path hooksDir = repo.resolve(".git/hooks");
        Files.createDirectories(hooksDir);
        Path hookSentinel = temp.resolve("multi-user-hook.txt");
        Path hook = hooksDir.resolve("post-checkout");
        Files.writeString(hook, "#!/bin/sh\necho PWN > \"" + hookSentinel.toAbsolutePath().toString().replace("\\", "/") + "\"\n", StandardCharsets.UTF_8);
        hook.toFile().setExecutable(true);

        String patch = RepairPatch.file("src/ServiceA.java", beforeSrc, afterSrc)
                + RepairPatch.createFile("src/ServiceAPort.java", newPortSrc);
        var proposal = buildProposal(beforeSrc, afterSrc, newPortSrc, patch);
        var verifiedReport = fakeReport(proposal, CapturedRepairVerification.Verdict.VERIFIED, RepairVerificationReport.TrustLabel.ISOLATED_VM_VERIFIED);

        var applier = new SafePatchApplier(backups);

        // User A creates preview token
        var alicePreview = applier.previewApply("gh:42", proposal, Optional.of(verifiedReport), repo,
                SafePatchApplier.ApplyMode.NEW_BRANCH_WORKTREE, Optional.of("evo/alice-repair"));
        assertTrue(alicePreview.canApply());
        String aliceToken = alicePreview.approvalToken().orElseThrow();

        // User B attempts to hijack User A's token -> AccessDeniedException AND token MUST remain valid for Alice!
        var hijack = assertThrows(SafePatchApplier.AccessDeniedException.class, () -> applier.confirmApply(
                "gh:99", proposal, Optional.of(verifiedReport), aliceToken, true, repo,
                SafePatchApplier.ApplyMode.NEW_BRANCH_WORKTREE, Optional.of("evo/alice-repair"),
                Optional.empty(), false, Optional.empty()));
        assertEquals("APPLY_ACCESS_DENIED", hijack.code());
        assertEquals(1, applier.pendingTokenCount(), "Cross-user hijack attempt must not consume owner's token");

        // User A confirms with GitHub CommitAuthor attribution
        var aliceAuthor = GitWorktreeManager.CommitAuthor.forGitHubUser(
                "Alice Engineer", Optional.empty(), "alice-dev", Optional.of(42L));
        var wtRes = applier.confirmApply("gh:42", proposal, Optional.of(verifiedReport), aliceToken, true, repo,
                SafePatchApplier.ApplyMode.NEW_BRANCH_WORKTREE, Optional.of("evo/alice-repair"),
                Optional.empty(), false, Optional.of(aliceAuthor));
        assertEquals("gh:42", wtRes.ownerUserId());
        assertEquals(0, applier.pendingTokenCount());
        assertFalse(Files.exists(hookSentinel), "Hooks must remain disabled with custom CommitAuthor");

        var authorLog = GitWorktreeManager.runGit(repo, List.of("log", "-1", "--pretty=%an <%ae>", "evo/alice-repair"));
        assertEquals("Alice Engineer <42+alice-dev@users.noreply.github.com>", authorLog.output().strip());
        var bodyLog = GitWorktreeManager.runGit(repo, List.of("log", "-1", "--pretty=%B", "evo/alice-repair"));
        assertTrue(bodyLog.output().contains("Evo-Owner: gh:42"), bodyLog.output());
        assertTrue(bodyLog.output().contains("Co-authored-by: Evo Verified Repair <evo-repair@local.invalid>"), bodyLog.output());

        // Direct apply + cross-user rollback isolation
        var directPrev = applier.previewApply("gh:42", proposal, Optional.of(verifiedReport), repo,
                SafePatchApplier.ApplyMode.DIRECT_WORKING_TREE, Optional.empty());
        var directRes = applier.confirmApply("gh:42", proposal, Optional.of(verifiedReport),
                directPrev.approvalToken().orElseThrow(), true, repo,
                SafePatchApplier.ApplyMode.DIRECT_WORKING_TREE, Optional.empty(), Optional.empty(), false, Optional.of(aliceAuthor));
        assertEquals(afterSrc, Files.readString(serviceFile, StandardCharsets.UTF_8));

        // Bob cannot rollback Alice's direct apply (even with force=true), and disk stays untouched!
        var rbDenied = assertThrows(SafePatchApplier.AccessDeniedException.class,
                () -> applier.rollbackApply("gh:99", directRes.applyId(), true));
        assertEquals("APPLY_ACCESS_DENIED", rbDenied.code());
        assertEquals(afterSrc, Files.readString(serviceFile, StandardCharsets.UTF_8), "Denied rollback must not touch disk");

        // Alice rolls back cleanly
        var rbOk = applier.rollbackApply("gh:42", directRes.applyId(), false);
        assertTrue(rbOk.restored());
        assertEquals(beforeSrc, Files.readString(serviceFile, StandardCharsets.UTF_8));
    }

    private static CapturedRepair.Proposal buildProposal(String beforeSrc, String afterSrc, String newPortSrc, String patch) {
        var repoId = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/safe-apply.git");
        var snap = SnapshotIdentity.from(repoId, ContentDigest.sha256Utf8("snap"));
        var bDig = ContentDigest.sha256Utf8(beforeSrc);
        var aDig = ContentDigest.sha256Utf8(afterSrc);
        var nDig = ContentDigest.sha256Utf8(newPortSrc);
        var pin = new FindingDiagnosis.Pin(snap, Optional.empty(), bDig, snap.value(), bDig, bDig, bDig);
        var proof = new FindingDiagnosis.Proof(new VersionedIdentifier("demo.rule", "1"),
                ArchitectureQuery.Scope.TYPE, "ServiceA", "ServiceB", "ServiceA -> ServiceB",
                List.of("graph.depends-on"), ArchitectureQuery.Classification.MUST, bDig);
        var candidate = new CapturedRepair.Candidate(pin, bDig, proof, List.of(),
                CapturedRepair.Strategy.AI, Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(
                        new CapturedRepair.Edit("src/ServiceA.java", bDig, aDig, bDig, List.of()),
                        new CapturedRepair.Edit("src/ServiceAPort.java", CapturedRepair.ABSENT_DIGEST, nDig, nDig, List.of())),
                List.of(), ContentDigest.sha256Utf8(patch), patch, "UNVERIFIED");
        return new CapturedRepair.Proposal(CanonicalJson.sha256(List.of(CapturedRepair.VERSION, candidate)), candidate);
    }

    private static RepairVerificationReport.Report fakeReport(CapturedRepair.Proposal proposal,
                                                              CapturedRepairVerification.Verdict verdict,
                                                              RepairVerificationReport.TrustLabel trust) {
        var id = proposal.identity();
        return new RepairVerificationReport.Report(
                RepairVerificationReport.VERSION, id, verdict, trust, "Summary",
                new RepairVerificationReport.ScoreDelta(Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), "N/A", "N/A",
                        java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, "LOW", "LOW", List.of()),
                Optional.empty(),
                new RepairVerificationReport.ViolationDelta(List.of(), List.of(), List.of(), 0, 0),
                new RepairVerificationReport.CapabilityGapDelta(0, 0, 0, Map.of(), Map.of()),
                new RepairVerificationReport.SandboxEvidence(true, "QEMU_IN_DOCKER", "maven", List.of("."), 0, 0, 1, 0, 0, 1,
                        List.of(), "", Optional.of(id), Optional.of(id), Optional.of(id)),
                List.of(),
                new RepairVerificationReport.Provenance(id, proposal.candidate().patchDigest(), id, Optional.empty(),
                        id, "AI", Optional.empty(), List.of()),
                proposal.candidate().patch(), "# Report\n");
    }
}
