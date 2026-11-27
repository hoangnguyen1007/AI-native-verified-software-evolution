package com.evolution.backend.query.repair;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Hardened, shell-free Git command wrapper for safe local patch verification, temporary worktree
 * creation, branch committing, and cleanup. Never executes repository hooks ({@code core.hooksPath})
 * and never pushes to any remote.
 */
public final class GitWorktreeManager {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern SAFE_BRANCH = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/-]{0,119}$");

    public record GitResult(int exitCode, String output) {
        public boolean ok() { return exitCode == 0; }
    }

    public record RepoStatus(
            boolean isGitRepo,
            String headBranch,
            String headCommit,
            boolean cleanWorkingTree,
            List<String> dirtyFiles) {
        public RepoStatus { dirtyFiles = List.copyOf(dirtyFiles); }
    }

    public record CommitAuthor(String name, String email, Optional<String> coAuthorLine) {
        public static final CommitAuthor DEFAULT_AUTHOR = new CommitAuthor(
                "Evo Verified Repair", "evo-repair@local.invalid", Optional.empty());

        public CommitAuthor {
            name = sanitizeAuthorField(name, "Evo Verified Repair");
            email = sanitizeAuthorField(email, "evo-repair@local.invalid");
            coAuthorLine = coAuthorLine == null ? Optional.empty() : coAuthorLine.map(String::strip).filter(s -> !s.isEmpty());
        }

        public static CommitAuthor forGitHubUser(String displayNameOrLogin, Optional<String> email,
                                                 String githubLogin, Optional<Long> githubUserId) {
            String safeLogin = (githubLogin == null || githubLogin.isBlank()) ? "evo-user" : githubLogin.strip();
            String effectiveName = (displayNameOrLogin == null || displayNameOrLogin.isBlank())
                    ? safeLogin : displayNameOrLogin.strip();
            String effectiveEmail;
            if (email != null && email.isPresent() && !email.orElseThrow().isBlank()) {
                effectiveEmail = email.orElseThrow().strip();
            } else if (githubUserId != null && githubUserId.isPresent() && githubUserId.orElseThrow() > 0) {
                effectiveEmail = githubUserId.orElseThrow() + "+" + safeLogin + "@users.noreply.github.com";
            } else {
                effectiveEmail = safeLogin + "@users.noreply.github.com";
            }
            return new CommitAuthor(effectiveName, effectiveEmail,
                    Optional.of("Co-authored-by: Evo Verified Repair <evo-repair@local.invalid>"));
        }

        private static String sanitizeAuthorField(String raw, String fallback) {
            if (raw == null || raw.isBlank()) return fallback;
            String s = raw.strip();
            if (s.length() > 128 || s.startsWith("-") || s.indexOf('<') >= 0 || s.indexOf('>') >= 0
                    || s.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Unsafe Git commit author metadata");
            }
            return s;
        }
    }

    public record WorktreeCommitResult(
            String branchName,
            String commitSha,
            String baseCommitSha,
            List<String> changedFiles) {
        public WorktreeCommitResult { changedFiles = List.copyOf(changedFiles); }
    }

    private GitWorktreeManager() {}

    public static void validateBranchName(String branch) {
        if (branch == null || !SAFE_BRANCH.matcher(branch).matches()
                || branch.contains("..") || branch.contains("//")
                || branch.endsWith("/") || branch.endsWith(".lock") || branch.startsWith("-")) {
            throw new IllegalArgumentException("Unsafe Git branch name: " + branch);
        }
    }

    public static RepoStatus inspect(Path repoDir) throws IOException {
        Path root = repoDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return new RepoStatus(false, "", "", false, List.of());
        }
        var inside = runGit(root, List.of("rev-parse", "--is-inside-work-tree"));
        if (!inside.ok() || !"true".equals(inside.output().strip())) {
            return new RepoStatus(false, "", "", false, List.of());
        }
        var branchRes = runGit(root, List.of("rev-parse", "--abbrev-ref", "HEAD"));
        var commitRes = runGit(root, List.of("rev-parse", "HEAD"));
        var statusRes = runGit(root, List.of("status", "--porcelain=v1"));
        var dirty = new ArrayList<String>();
        if (statusRes.ok()) {
            for (String line : statusRes.output().split("\\r?\\n")) {
                if (!line.isBlank()) dirty.add(line.length() > 3 ? line.substring(3).strip() : line.strip());
            }
        }
        return new RepoStatus(
                true,
                branchRes.ok() ? branchRes.output().strip() : "",
                commitRes.ok() ? commitRes.output().strip() : "",
                dirty.isEmpty(),
                dirty);
    }

    public static boolean branchExists(Path repoDir, String branchName) throws IOException {
        validateBranchName(branchName);
        var res = runGit(repoDir, List.of("show-ref", "--verify", "--quiet", "refs/heads/" + branchName));
        return res.ok();
    }

    public static GitResult checkPatchApplies(Path repoDir, String unifiedDiff) throws IOException {
        Path tempPatch = Files.createTempFile("evo-patch-check-", ".patch");
        try {
            Files.writeString(tempPatch, unifiedDiff, StandardCharsets.UTF_8);
            return runGit(repoDir, List.of("-c", "core.autocrlf=false", "apply", "--check", "--whitespace=nowarn",
                    tempPatch.toAbsolutePath().toString()));
        } finally {
            Files.deleteIfExists(tempPatch);
        }
    }

    /**
     * Applies a verified unified diff inside a brand-new temporary Git worktree branched from HEAD,
     * commits the change with provenance trailers (disabling hooks), and removes the temporary worktree
     * so the user's current working tree and checked-out branch remain 100% untouched.
     */
    public static WorktreeCommitResult applyInNewBranchWorktree(
            Path repoDir,
            String branchName,
            String unifiedDiff,
            String commitMessage,
            List<String> expectedPaths) throws IOException {
        return applyInNewBranchWorktree(repoDir, branchName, unifiedDiff, commitMessage, expectedPaths,
                CommitAuthor.DEFAULT_AUTHOR);
    }

    public static WorktreeCommitResult applyInNewBranchWorktree(
            Path repoDir,
            String branchName,
            String unifiedDiff,
            String commitMessage,
            List<String> expectedPaths,
            CommitAuthor author) throws IOException {
        validateBranchName(branchName);
        CommitAuthor effectiveAuthor = author == null ? CommitAuthor.DEFAULT_AUTHOR : author;
        Path root = repoDir.toAbsolutePath().normalize();
        if (branchExists(root, branchName)) {
            throw new IOException("BRANCH_ALREADY_EXISTS: " + branchName);
        }
        var baseCommitRes = runGit(root, List.of("rev-parse", "HEAD"));
        if (!baseCommitRes.ok()) {
            throw new IOException("GIT_HEAD_UNAVAILABLE: " + baseCommitRes.output());
        }
        String baseCommit = baseCommitRes.output().strip();
        Path worktreeDir = Files.createTempDirectory("evo-repair-wt-");
        // git worktree add requires a non-existent directory
        Files.delete(worktreeDir);
        Path tempPatch = Files.createTempFile("evo-repair-apply-", ".patch");
        Path emptyHooksDir = Files.createTempDirectory("evo-git-nohooks-");
        Path emptyGlobalConfig = Files.createTempFile("evo-git-cfg-", ".gitconfig");
        boolean worktreeCreated = false;
        try {
            Files.writeString(tempPatch, unifiedDiff, StandardCharsets.UTF_8);
            var addRes = runGitIsolated(root, emptyHooksDir, emptyGlobalConfig, List.of(
                    "-c", "core.autocrlf=false", "worktree", "add", "-b", branchName,
                    worktreeDir.toAbsolutePath().toString(), baseCommit));
            if (!addRes.ok()) {
                throw new IOException("GIT_WORKTREE_ADD_FAILED: " + addRes.output());
            }
            worktreeCreated = true;

            var checkRes = runGitIsolated(worktreeDir, emptyHooksDir, emptyGlobalConfig, List.of(
                    "-c", "core.autocrlf=false", "apply", "--check", "--whitespace=nowarn",
                    tempPatch.toAbsolutePath().toString()));
            if (!checkRes.ok()) {
                throw new IOException("GIT_APPLY_CHECK_FAILED: " + checkRes.output());
            }
            var applyRes = runGitIsolated(worktreeDir, emptyHooksDir, emptyGlobalConfig, List.of(
                    "-c", "core.autocrlf=false", "apply", "--whitespace=nowarn",
                    tempPatch.toAbsolutePath().toString()));
            if (!applyRes.ok()) {
                throw new IOException("GIT_APPLY_FAILED: " + applyRes.output());
            }
            var stageCmd = new ArrayList<String>(List.of("add", "-A", "--"));
            if (expectedPaths != null && !expectedPaths.isEmpty()) {
                stageCmd.addAll(expectedPaths);
            } else {
                stageCmd.add(".");
            }
            var addFilesRes = runGitIsolated(worktreeDir, emptyHooksDir, emptyGlobalConfig, stageCmd);
            if (!addFilesRes.ok()) {
                throw new IOException("GIT_STAGE_FAILED: " + addFilesRes.output());
            }
            var commitRes = runGitIsolated(worktreeDir, emptyHooksDir, emptyGlobalConfig, List.of(
                    "-c", "user.name=" + effectiveAuthor.name(),
                    "-c", "user.email=" + effectiveAuthor.email(),
                    "commit", "--no-verify", "-m", commitMessage));
            if (!commitRes.ok()) {
                throw new IOException("GIT_COMMIT_FAILED: " + commitRes.output());
            }
            var newShaRes = runGitIsolated(worktreeDir, emptyHooksDir, emptyGlobalConfig, List.of("rev-parse", "HEAD"));
            if (!newShaRes.ok()) {
                throw new IOException("GIT_COMMIT_SHA_FAILED: " + newShaRes.output());
            }
            var diffNames = runGitIsolated(worktreeDir, emptyHooksDir, emptyGlobalConfig,
                    List.of("diff-tree", "--no-commit-id", "--name-only", "-r", "HEAD"));
            var files = diffNames.ok()
                    ? Arrays.stream(diffNames.output().split("\\r?\\n")).map(String::strip).filter(s -> !s.isEmpty()).toList()
                    : List.<String>of();
            return new WorktreeCommitResult(branchName, newShaRes.output().strip(), baseCommit, files);
        } catch (IOException | RuntimeException failed) {
            if (worktreeCreated) {
                runGitQuietly(root, emptyHooksDir, emptyGlobalConfig,
                        List.of("worktree", "remove", "--force", worktreeDir.toAbsolutePath().toString()));
                runGitQuietly(root, emptyHooksDir, emptyGlobalConfig, List.of("branch", "-D", branchName));
            }
            throw failed;
        } finally {
            Files.deleteIfExists(tempPatch);
            if (worktreeCreated) {
                runGitQuietly(root, emptyHooksDir, emptyGlobalConfig,
                        List.of("worktree", "remove", "--force", worktreeDir.toAbsolutePath().toString()));
            }
            deleteRecursivelyQuietly(worktreeDir);
            Files.deleteIfExists(emptyGlobalConfig);
            deleteRecursivelyQuietly(emptyHooksDir);
        }
    }

    public static GitResult runGit(Path dir, List<String> args) throws IOException {
        Path emptyHooksDir = Files.createTempDirectory("evo-git-nohooks-");
        Path emptyGlobalConfig = Files.createTempFile("evo-git-cfg-", ".gitconfig");
        try {
            return runGitIsolated(dir, emptyHooksDir, emptyGlobalConfig, args);
        } finally {
            Files.deleteIfExists(emptyGlobalConfig);
            deleteRecursivelyQuietly(emptyHooksDir);
        }
    }

    private static GitResult runGitIsolated(Path dir, Path emptyHooksDir, Path emptyGlobalConfig,
                                            List<String> args) throws IOException {
        var cmd = new ArrayList<String>(args.size() + 10);
        cmd.add("git");
        cmd.add("--no-pager");
        cmd.add("-c");
        cmd.add("core.hooksPath=" + emptyHooksDir.toAbsolutePath());
        cmd.add("-c");
        cmd.add("commit.gpgsign=false");
        cmd.add("-c");
        cmd.add("credential.helper=");
        cmd.add("-c");
        cmd.add("core.longpaths=true");
        cmd.addAll(args);
        var pb = new ProcessBuilder(cmd)
                .directory(dir.toFile())
                .redirectErrorStream(true);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("GCM_INTERACTIVE", "never");
        pb.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        pb.environment().put("GIT_CONFIG_GLOBAL", emptyGlobalConfig.toAbsolutePath().toString());
        Process process = pb.start();
        var buf = new ByteArrayOutputStream(2048);
        Thread drainer = new Thread(() -> {
            byte[] chunk = new byte[4096];
            try (var in = process.getInputStream()) {
                int r;
                while ((r = in.read(chunk)) != -1) {
                    if (buf.size() < 256 * 1024) buf.write(chunk, 0, Math.min(r, 256 * 1024 - buf.size()));
                }
            } catch (IOException ignored) {}
        }, "evo-git-drain");
        drainer.setDaemon(true);
        drainer.start();
        try {
            if (!process.waitFor(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("GIT_TIMEOUT: " + String.join(" ", args));
            }
            drainer.join(2000L);
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("GIT_INTERRUPTED", interrupted);
        }
        return new GitResult(process.exitValue(), buf.toString(StandardCharsets.UTF_8));
    }

    private static void runGitQuietly(Path dir, Path emptyHooksDir, Path emptyGlobalConfig, List<String> args) {
        try { runGitIsolated(dir, emptyHooksDir, emptyGlobalConfig, args); } catch (IOException ignored) {}
    }

    private static void deleteRecursivelyQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }
}
