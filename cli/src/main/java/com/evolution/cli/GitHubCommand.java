package com.evolution.cli;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.filesystem.FileGraphStore;
import com.evolution.analysis.filesystem.GitHostPolicy;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.backend.analysis.AnalysisJobs;
import com.evolution.backend.analysis.AnalysisMode;
import com.evolution.backend.analysis.CanonicalRecordReader;
import com.evolution.backend.analysis.RepositoryConnections;
import com.evolution.backend.auth.*;
import com.evolution.backend.github.*;
import com.evolution.backend.query.CapturedRepair;
import com.evolution.backend.query.PublishedReports;
import com.evolution.backend.query.RepairProposalStore;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * First-class CLI integration for GitHub repository discovery, 1-click PR/branch Architecture
 * Evolution Diff, idempotent PR review comments, and strictly confirmed Verified Repair PR creation.
 */
final class GitHubCommand {
    /** Test-injectable factory hook so offline tests run with loopback HTTP + bare Git remotes. */
    interface ContextFactory {
        GitHubTokenBroker tokenBroker();
        GitHubRestClient restClient(GitHubTokenBroker broker, GitHostPolicy hostPolicy);
        Optional<Path> localBareRemoteOverride();

        default GitHubOAuthService oauthService(UserSessionStore sessions, GitHostPolicy hostPolicy,
                Optional<String> clientIdOverride, Optional<String> hostOverride) {
            var base = GitHubOAuthService.Config.fromEnvironment();
            var cfg = new GitHubOAuthService.Config(
                    base.multiUserMode(),
                    clientIdOverride.filter(s -> !s.isBlank()).orElse(base.clientId()),
                    base.clientSecret(),
                    hostOverride.filter(s -> !s.isBlank()).orElse(base.host()),
                    base.scopes(),
                    base.githubAppEnabled(),
                    base.publicBaseUrl(),
                    base.oauthBaseOverride(),
                    base.apiBaseOverride());
            return new GitHubOAuthService(cfg, sessions, GitHubRestClient.createPinnedTransport(hostPolicy));
        }
    }

    static final ContextFactory DEFAULT_FACTORY = new ContextFactory() {
        @Override
        public GitHubTokenBroker tokenBroker() {
            return new GitHubTokenBroker();
        }

        @Override
        public GitHubRestClient restClient(GitHubTokenBroker broker, GitHostPolicy hostPolicy) {
            return new GitHubRestClient(broker, hostPolicy);
        }

        @Override
        public Optional<Path> localBareRemoteOverride() {
            return Optional.empty();
        }
    };

    private GitHubCommand() {
    }

    @Command(name = "github", mixinStandardHelpOptions = true,
            description = "Inspect GitHub authentication, branches, tags, commits, and pull requests")
    static final class GitHubGroup implements Callable<Integer> {
        final Path cwd;
        final PrintWriter out, err;
        final Supplier<Boolean> jsonMode;
        final ContextFactory factory;

        GitHubGroup(Path cwd, PrintWriter out, PrintWriter err, Supplier<Boolean> jsonMode, ContextFactory factory) {
            this.cwd = cwd;
            this.out = out;
            this.err = err;
            this.jsonMode = jsonMode;
            this.factory = factory;
        }

        @Override
        public Integer call() {
            new CommandLine(this).usage(out);
            return 0;
        }

        @Command(name = "auth", mixinStandardHelpOptions = true, description = "Inspect or clear GitHub authentication")
        public Integer auth(
                @Parameters(index = "0", defaultValue = "status", paramLabel = "status|clear") String action,
                @Option(names = "--host", defaultValue = "github.com") String host) {
            BooleanSupplier cancelled = () -> Thread.currentThread().isInterrupted();
            try (var broker = factory.tokenBroker()) {
                var policy = hostPolicy(broker);
                var client = factory.restClient(broker, policy);
                if ("clear".equalsIgnoreCase(action)) {
                    broker.clearSessionToken();
                    return emitJsonOrText(out, jsonMode.get(),
                            Map.of("cleared", true, "status", client.authStatus(host, cancelled)),
                            "Cleared in-memory GitHub session credentials (ambient env/gh CLI tokens are managed outside evo).");
                }
                if (!"status".equalsIgnoreCase(action)) {
                    err.println("[INVALID_USAGE] Use: evo github auth status|clear");
                    return 2;
                }
                var status = client.authStatus(host, cancelled);
                String text = Boolean.TRUE.equals(status.get("authenticated"))
                        ? "GitHub authenticated as @" + status.get("login") + " on " + status.get("host")
                                + " (source=" + status.get("source") + ", canPushAndOpenPr="
                                + status.get("canPushAndOpenPr") + ")"
                        : "GitHub not authenticated on " + status.get("host")
                                + " (set EVO_GITHUB_TOKEN / GITHUB_TOKEN or run `gh auth login`).";
                return emitJsonOrText(out, jsonMode.get(), status, text);
            }
        }

        @Command(name = "branches", mixinStandardHelpOptions = true, description = "List GitHub repository branches")
        public Integer branches(
                @Parameters(index = "0", paramLabel = "URL|OWNER/REPO") String repo,
                @Option(names = "--max", defaultValue = "50") int limit) {
            return withClient(repo, (coords, client, cancelled) -> {
                var rows = client.branches(coords, limit, cancelled);
                var sb = new StringBuilder("Branches for ").append(coords.fullName()).append(" (").append(rows.size()).append("):\n");
                for (var b : rows) {
                    sb.append("  ").append(b.name()).append("  ").append(shortSha(b.sha()))
                            .append(b.isProtected() ? " [protected]" : "").append('\n');
                }
                return emitJsonOrText(out, jsonMode.get(), Map.of("repository", coords.fullName(), "items", rows), sb.toString());
            });
        }

        @Command(name = "tags", mixinStandardHelpOptions = true, description = "List GitHub repository tags")
        public Integer tags(
                @Parameters(index = "0", paramLabel = "URL|OWNER/REPO") String repo,
                @Option(names = "--max", defaultValue = "50") int limit) {
            return withClient(repo, (coords, client, cancelled) -> {
                var rows = client.tags(coords, limit, cancelled);
                var sb = new StringBuilder("Tags for ").append(coords.fullName()).append(" (").append(rows.size()).append("):\n");
                for (var t : rows) {
                    sb.append("  ").append(t.name()).append("  ").append(shortSha(t.sha())).append('\n');
                }
                return emitJsonOrText(out, jsonMode.get(), Map.of("repository", coords.fullName(), "items", rows), sb.toString());
            });
        }

        @Command(name = "commits", mixinStandardHelpOptions = true, description = "List recent commits on a branch or ref")
        public Integer commits(
                @Parameters(index = "0", paramLabel = "URL|OWNER/REPO") String repo,
                @Option(names = "--sha", paramLabel = "REF") String sha,
                @Option(names = "--max", defaultValue = "20") int limit) {
            return withClient(repo, (coords, client, cancelled) -> {
                var rows = client.commits(coords, Optional.ofNullable(sha), limit, cancelled);
                var sb = new StringBuilder("Commits for ").append(coords.fullName()).append(":\n");
                for (var c : rows) {
                    sb.append("  ").append(c.shortSha()).append("  ").append(c.message())
                            .append(" (").append(c.authorLogin().isEmpty() ? c.authorName() : "@" + c.authorLogin()).append(")\n");
                }
                return emitJsonOrText(out, jsonMode.get(), Map.of("repository", coords.fullName(), "items", rows), sb.toString());
            });
        }

        @Command(name = "pulls", mixinStandardHelpOptions = true, description = "List GitHub pull requests")
        public Integer pulls(
                @Parameters(index = "0", paramLabel = "URL|OWNER/REPO") String repo,
                @Option(names = "--state", defaultValue = "open") String state,
                @Option(names = "--max", defaultValue = "20") int limit) {
            return withClient(repo, (coords, client, cancelled) -> {
                var rows = client.pulls(coords, state, limit, cancelled);
                var sb = new StringBuilder("Pull Requests for ").append(coords.fullName()).append(" [").append(state).append("]:\n");
                for (var pr : rows) {
                    sb.append("  #").append(pr.number()).append(pr.draft() ? " [DRAFT] " : " ")
                            .append(pr.title()).append(" (").append(pr.baseRef()).append(" <- ").append(pr.headRef())
                            .append(")\n");
                }
                return emitJsonOrText(out, jsonMode.get(), Map.of("repository", coords.fullName(), "items", rows), sb.toString());
            });
        }

        @Command(name = "orgs", mixinStandardHelpOptions = true, description = "List GitHub organizations for the signed-in user")
        public Integer orgs(
                @Option(names = "--host", defaultValue = "github.com") String host,
                @Option(names = "--max", defaultValue = "50") int limit) {
            BooleanSupplier cancelled = () -> Thread.currentThread().isInterrupted();
            try (var broker = factory.tokenBroker()) {
                var client = factory.restClient(broker, hostPolicy(broker));
                var rows = client.userOrgs(host, limit, cancelled);
                var sb = new StringBuilder("GitHub Organizations (").append(rows.size()).append("):\n");
                for (var o : rows) {
                    sb.append("  @").append(o.login());
                    if (!o.description().isBlank()) sb.append(" — ").append(o.description());
                    sb.append('\n');
                }
                return emitJsonOrText(out, jsonMode.get(), Map.of("items", rows), sb.toString());
            } catch (GitHubRestClient.GitHubApiException apiErr) {
                return emitError(err, jsonMode.get(), apiErr.code(), apiErr.getMessage(), apiErr.httpStatus() == 401 ? 2 : 1);
            }
        }

        @Command(name = "repos", mixinStandardHelpOptions = true, description = "List and filter your personal or organization GitHub repositories")
        public Integer repos(
                @Option(names = "--org", paramLabel = "ORG") String org,
                @Option(names = "--q", paramLabel = "SEARCH") String query,
                @Option(names = "--visibility", defaultValue = "all") String visibility,
                @Option(names = "--sort", defaultValue = "updated") String sort,
                @Option(names = "--page", defaultValue = "1") int page,
                @Option(names = "--max", defaultValue = "30") int limit,
                @Option(names = "--host", defaultValue = "github.com") String host) {
            BooleanSupplier cancelled = () -> Thread.currentThread().isInterrupted();
            try (var broker = factory.tokenBroker()) {
                var client = factory.restClient(broker, hostPolicy(broker));
                var rows = client.userRepos(host, Optional.ofNullable(org), visibility, sort, page, limit, cancelled);
                var filtered = (query == null || query.isBlank()) ? rows : rows.stream().filter(r -> {
                    String needle = query.strip().toLowerCase(Locale.ROOT);
                    return r.fullName().toLowerCase(Locale.ROOT).contains(needle)
                            || r.description().toLowerCase(Locale.ROOT).contains(needle)
                            || r.language().toLowerCase(Locale.ROOT).contains(needle);
                }).toList();
                var sb = new StringBuilder("GitHub Repositories (").append(filtered.size()).append("):\n");
                for (var r : filtered) {
                    sb.append("  ").append(r.fullName())
                            .append(r.privateRepo() ? " [private]" : " [public]")
                            .append(r.language().isBlank() ? "" : " (" + r.language() + ")")
                            .append('\n');
                }
                return emitJsonOrText(out, jsonMode.get(), Map.of("page", page, "items", filtered), sb.toString());
            } catch (GitHubRestClient.GitHubApiException apiErr) {
                return emitError(err, jsonMode.get(), apiErr.code(), apiErr.getMessage(), apiErr.httpStatus() == 401 ? 2 : 1);
            } catch (IllegalArgumentException bad) {
                return emitError(err, jsonMode.get(), "INVALID_INPUT", GitHubTokenBroker.redact(bad.getMessage()), 2);
            }
        }

        @Command(name = "ci-init", mixinStandardHelpOptions = true, description = "Generate GitHub Actions Evo Architecture Quality Gate workflow YAML")
        public Integer ciInit(
                @Parameters(index = "0", arity = "0..1", paramLabel = "URL|OWNER/REPO") String repo,
                @Option(names = "--branch", defaultValue = "main") String defaultBranch,
                @Option(names = "--fail-on-must", defaultValue = "true") boolean failOnMust,
                @Option(names = "--min-score", defaultValue = "60") int minScore,
                @Option(names = "--out", paramLabel = "FILE") Path outFile) throws IOException {
            GitHubCoordinates.Coordinates coords = (repo == null || repo.isBlank()) ? null : GitHubCoordinates.parse(repo);
            var wf = GitHubCiWorkflowGenerator.generate(coords, defaultBranch, failOnMust, minScore);
            if (outFile != null) {
                Path target = outFile.isAbsolute() ? outFile : cwd.resolve(outFile);
                if (target.getParent() != null) Files.createDirectories(target.getParent());
                Files.writeString(target, wf.yaml(), StandardCharsets.UTF_8);
            }
            return emitJsonOrText(out, jsonMode.get(), wf.toView(), wf.yaml());
        }

        @FunctionalInterface
        private interface ClientOp {
            int run(GitHubCoordinates.Coordinates coords, GitHubRestClient client, BooleanSupplier cancelled);
        }

        private int withClient(String repo, ClientOp op) {
            BooleanSupplier cancelled = () -> Thread.currentThread().isInterrupted();
            try (var broker = factory.tokenBroker()) {
                var coords = GitHubCoordinates.parse(repo);
                var client = factory.restClient(broker, hostPolicy(broker));
                return op.run(coords, client, cancelled);
            } catch (GitHubRestClient.GitHubApiException apiErr) {
                return emitError(err, jsonMode.get(), apiErr.code(), apiErr.getMessage(), apiErr.httpStatus() == 401 ? 2 : 1);
            } catch (IllegalArgumentException bad) {
                return emitError(err, jsonMode.get(), "INVALID_INPUT", GitHubTokenBroker.redact(bad.getMessage()), 2);
            }
        }
    }

    @Command(name = "pr", mixinStandardHelpOptions = true,
            description = "Compare Pull Requests / branches architecturally, comment reviews, and open verified repair PRs")
    static final class PrGroup implements Callable<Integer> {
        final Path cwd;
        final PrintWriter out, err;
        final Supplier<Path> workspaceRoot;
        final Supplier<Boolean> jsonMode;
        final ContextFactory factory;

        PrGroup(Path cwd, PrintWriter out, PrintWriter err, Supplier<Path> workspaceRoot,
                Supplier<Boolean> jsonMode, ContextFactory factory) {
            this.cwd = cwd;
            this.out = out;
            this.err = err;
            this.workspaceRoot = workspaceRoot;
            this.jsonMode = jsonMode;
            this.factory = factory;
        }

        @Override
        public Integer call() {
            new CommandLine(this).usage(out);
            return 0;
        }

        @Command(name = "diff", mixinStandardHelpOptions = true,
                description = "Run 1-click Architecture Evolution Diff between PR base/head or two refs")
        public Integer diff(
                @Parameters(index = "0", paramLabel = "URL|OWNER/REPO") String repo,
                @Option(names = "--pr", paramLabel = "NUMBER") Integer prNumber,
                @Option(names = "--base", paramLabel = "REF") String baseRef,
                @Option(names = "--head", paramLabel = "REF") String headRef,
                @Option(names = "--mode", defaultValue = "fast", paramLabel = "fast|full") String modeStr,
                @Option(names = "--jdk", paramLabel = "DIR") Path jdk,
                @Option(names = "--release", defaultValue = "21") int release,
                @Option(names = "--maven-cache", paramLabel = "DIR") Path mavenCache,
                @Option(names = "--out", paramLabel = "FILE") Path outFile) throws Exception {
            if (prNumber == null && (headRef == null || headRef.isBlank())) {
                return emitError(err, jsonMode.get(), "INVALID_INPUT",
                        "Specify either --pr <number> or --head <ref> (with optional --base <ref>).", 2);
            }
            var mode = AnalysisMode.parse(modeStr);
            Path wsRoot = workspaceRoot.get();
            Files.createDirectories(wsRoot);
            Path resolvedJdk = jdk == null ? Path.of(System.getProperty("java.home")) : resolve(jdk);
            Path resolvedCache = mavenCache == null
                    ? Path.of(System.getProperty("user.home"), ".m2", "repository")
                    : resolve(mavenCache);
            BooleanSupplier cancelled = () -> Thread.currentThread().isInterrupted();

            try (var broker = factory.tokenBroker()) {
                var coords = GitHubCoordinates.parse(repo);
                var policy = hostPolicy(broker);
                var client = factory.restClient(broker, policy);
                var store = new FileGraphStore(wsRoot, 256_000_000L, GraphJson.StreamLimits.from(GraphJson.Limits.defaults()));
                var reports = new PublishedReports();
                try (var jobs = new AnalysisJobs(List.of(), store, reports, AnalysisJobs.Limits.defaults())) {
                    var connections = new RepositoryConnections(jobs, resolvedJdk, release, resolvedCache, wsRoot,
                            Optional.empty(), "git", policy);
                    var conn = connections.connect(coords.canonicalCloneUrl(), Optional.empty());
                    var diffService = new GitHubPrDiffService(client, jobs, connections, store, reports,
                            wsRoot.resolve("github-diffs"));
                    var result = diffService.compare(
                            conn.setup(),
                            prNumber == null ? OptionalInt.empty() : OptionalInt.of(prNumber),
                            Optional.ofNullable(baseRef),
                            Optional.ofNullable(headRef),
                            mode,
                            cancelled);
                    if (outFile != null) {
                        Files.writeString(resolve(outFile), CanonicalJson.write(result.toView()), StandardCharsets.UTF_8);
                    }
                    return emitJsonOrText(out, jsonMode.get(), result.toView(), result.markdownReport());
                }
            } catch (GitHubRestClient.GitHubApiException apiErr) {
                return emitError(err, jsonMode.get(), apiErr.code(), apiErr.getMessage(), apiErr.httpStatus() == 401 ? 2 : 1);
            } catch (IllegalArgumentException | IllegalStateException errEx) {
                return emitError(err, jsonMode.get(), "PR_DIFF_FAILED", GitHubTokenBroker.redact(errEx.getMessage()), 1);
            }
        }

        @Command(name = "comment", mixinStandardHelpOptions = true,
                description = "Post a confirmed Architecture Evolution Diff review comment to a GitHub PR")
        public Integer comment(
                @Parameters(index = "0", paramLabel = "URL|OWNER/REPO") String repo,
                @Option(names = "--pr", required = true, paramLabel = "NUMBER") int prNumber,
                @Option(names = "--diff", required = true, paramLabel = "DIFF_JSON") Path diffFile,
                @Option(names = "--confirm", required = true, paramLabel = "SHA256") String confirmDigest) throws Exception {
            BooleanSupplier cancelled = () -> Thread.currentThread().isInterrupted();
            try (var broker = factory.tokenBroker()) {
                var coords = GitHubCoordinates.parse(repo);
                var obj = GraphJson.object(Workspace.json(resolve(diffFile), 4 * 1024 * 1024));
                var pinnedDigest = new ContentDigest(GraphJson.string(obj.get("diffDigest")));
                var expected = new ContentDigest(confirmDigest);
                if (!pinnedDigest.equals(expected)) {
                    return emitError(err, jsonMode.get(), "DIFF_DIGEST_MISMATCH",
                            "--confirm digest does not match diffDigest inside " + diffFile.getFileName(), 2);
                }
                String markdown = GraphJson.string(obj.get("markdownReport"));
                var client = factory.restClient(broker, hostPolicy(broker));
                var receipt = client.createOrUpdatePrComment(coords, prNumber, markdown, cancelled);
                String text = (receipt.updated() ? "Updated" : "Posted") + " architecture review comment on #"
                        + prNumber + ": " + receipt.htmlUrl();
                return emitJsonOrText(out, jsonMode.get(), receipt, text);
            } catch (GitHubRestClient.GitHubApiException apiErr) {
                return emitError(err, jsonMode.get(), apiErr.code(), apiErr.getMessage(), apiErr.httpStatus() == 401 ? 2 : 1);
            } catch (IllegalArgumentException bad) {
                return emitError(err, jsonMode.get(), "INVALID_INPUT", GitHubTokenBroker.redact(bad.getMessage()), 2);
            }
        }

        @Command(name = "open", mixinStandardHelpOptions = true,
                description = "2-step Verified Repair -> GitHub Branch & Pull Request publication (dry-run without --confirm)")
        public Integer open(
                @Parameters(index = "0", paramLabel = "PROPOSAL_JSON") Path proposalFile,
                @Option(names = "--repo", required = true, paramLabel = "URL|OWNER/REPO") String repo,
                @Option(names = "--base", paramLabel = "BRANCH") String baseBranch,
                @Option(names = "--head", paramLabel = "BRANCH") String headBranch,
                @Option(names = "--title", paramLabel = "TITLE") String title,
                @Option(names = "--draft", description = "Open PR as Draft") boolean draftFlag,
                @Option(names = "--ready", description = "Open PR as Ready for Review (only when VERIFIED)") boolean readyFlag,
                @Option(names = "--allow-unverified", description = "Explicit opt-in for unverified proposals (forced Draft + warning banner)") boolean allowUnverified,
                @Option(names = "--confirm", paramLabel = "PLAN_DIGEST",
                        description = "Explicit confirmation matching publishPlanDigest from dry-run step") String confirmPlanDigest) throws Exception {
            if (draftFlag && readyFlag) {
                return emitError(err, jsonMode.get(), "INVALID_INPUT", "Specify either --draft or --ready, not both.", 2);
            }
            BooleanSupplier cancelled = () -> Thread.currentThread().isInterrupted();
            Path wsRoot = workspaceRoot.get();
            Files.createDirectories(wsRoot);
            try (var broker = factory.tokenBroker()) {
                var proposal = loadCapturedProposal(resolve(proposalFile));
                var proposalStore = new RepairProposalStore(wsRoot.resolve("repairs"));
                proposalStore.put(proposal);
                var policy = hostPolicy(broker);
                var client = factory.restClient(broker, policy);
                var publisher = new GitHubRepairPublisher(
                        client, broker, proposalStore, null, policy,
                        wsRoot.resolve("github-publish"), "git", factory.localBareRemoteOverride());

                Optional<Boolean> draftOpt = draftFlag ? Optional.of(true) : (readyFlag ? Optional.of(false) : Optional.empty());
                var plan = publisher.prepare(new GitHubRepairPublisher.PrepareRequest(
                        proposal.identity(),
                        Optional.empty(),
                        Optional.of(repo),
                        Optional.ofNullable(baseBranch),
                        Optional.ofNullable(headBranch),
                        Optional.ofNullable(title),
                        draftOpt,
                        allowUnverified,
                        Optional.empty()), cancelled);

                if (confirmPlanDigest == null || confirmPlanDigest.isBlank()) {
                    var summary = new StringBuilder();
                    summary.append("DRY-RUN PUBLISH PLAN (zero Git/GitHub writes performed)\n");
                    summary.append("  Repository:        ").append(plan.repository()).append('\n');
                    summary.append("  Branch:            ").append(plan.baseBranch()).append(" <- ").append(plan.headBranch()).append('\n');
                    summary.append("  Draft PR:          ").append(plan.draft()).append('\n');
                    summary.append("  Verdict / Trust:   ").append(plan.verdict()).append(" / ").append(plan.trustBadge()).append('\n');
                    summary.append("  Diff Stats:        ").append(plan.diffStats().filesChanged()).append(" file(s), +")
                            .append(plan.diffStats().insertions()).append(" / -").append(plan.diffStats().deletions()).append('\n');
                    summary.append("  publishPlanDigest: ").append(plan.publishPlanDigest().value()).append('\n');
                    summary.append("\nRe-run with --confirm ").append(plan.publishPlanDigest().value())
                            .append(" to push branch and open the Pull Request.\n");
                    return emitJsonOrText(out, jsonMode.get(), Map.of("dryRun", true, "plan", plan.toView()), summary.toString());
                }

                var expectedDigest = new ContentDigest(confirmPlanDigest.strip());
                if (!plan.publishPlanDigest().equals(expectedDigest)) {
                    return emitError(err, jsonMode.get(), "PUBLISH_PLAN_DIGEST_MISMATCH",
                            "--confirm digest does not match prepared publishPlanDigest (" + plan.publishPlanDigest().value() + ").", 2);
                }
                var receipt = publisher.publish(new GitHubRepairPublisher.PublishRequest(
                        plan.confirmationToken(), expectedDigest, true,
                        Optional.empty(), Optional.empty(), Optional.empty()), cancelled);
                String text = "Opened GitHub Pull Request #" + receipt.prNumber() + " (" + (receipt.draft() ? "DRAFT" : "READY")
                        + "): " + receipt.htmlUrl() + "\n  Branch: " + receipt.branch()
                        + " | Commit: " + shortSha(receipt.commitSha())
                        + " | Verdict: " + receipt.verdict();
                return emitJsonOrText(out, jsonMode.get(), receipt.toView(), text);
            } catch (GitHubRepairPublisher.PublishException pubErr) {
                return emitError(err, jsonMode.get(), pubErr.code(), pubErr.getMessage(), pubErr.status() == 409 ? 4 : 2);
            } catch (GitHubRestClient.GitHubApiException apiErr) {
                return emitError(err, jsonMode.get(), apiErr.code(), apiErr.getMessage(), apiErr.httpStatus() == 401 ? 2 : 1);
            } catch (IllegalArgumentException bad) {
                return emitError(err, jsonMode.get(), "INVALID_INPUT", GitHubTokenBroker.redact(bad.getMessage()), 2);
            }
        }

        @Command(name = "review", mixinStandardHelpOptions = true,
                description = "Preview or publish a confirmed GitHub inline PR review with Quality Gate and suggestion blocks")
        public Integer review(
                @Parameters(index = "0", paramLabel = "URL|OWNER/REPO") String repo,
                @Option(names = "--pr", required = true, paramLabel = "NUMBER") int prNumber,
                @Option(names = "--graph-file", paramLabel = "GRAPH_JSON") Path graphFile,
                @Option(names = "--report-file", paramLabel = "REPORT_JSON") Path reportFile,
                @Option(names = "--event", paramLabel = "COMMENT|REQUEST_CHANGES|APPROVE") String eventOverride,
                @Option(names = "--post-status", description = "Also publish Quality Gate Commit Status") boolean postStatus,
                @Option(names = "--confirm", paramLabel = "PLAN_DIGEST", description = "Explicit confirmation matching reviewPlanDigest") String confirmDigest) throws Exception {
            BooleanSupplier cancelled = () -> Thread.currentThread().isInterrupted();
            try (var broker = factory.tokenBroker()) {
                var coords = GitHubCoordinates.parse(repo);
                var client = factory.restClient(broker, hostPolicy(broker));
                var graphs = new com.evolution.analysis.graph.InMemoryGraphStore();
                var reports = new PublishedReports();
                Optional<ContentDigest> graphId = Optional.empty();
                Optional<ContentDigest> reportId = Optional.empty();
                if (graphFile != null) {
                    var loaded = new com.evolution.analysis.graph.GraphSnapshotCodec().decode(
                            Files.readString(resolve(graphFile), StandardCharsets.UTF_8), GraphJson.Limits.defaults());
                    graphs.save(loaded);
                    graphId = Optional.of(loaded.identity());
                }
                if (reportFile != null) {
                    var loadedRep = CanonicalRecordReader.read(Workspace.json(resolve(reportFile), 8 * 1024 * 1024), PublishedReports.Report.class);
                    reportId = Optional.of(reports.publish(loadedRep));
                }
                var reviewSvc = new GitHubPrReviewService(client, null, null, graphs, reports, null);
                var plan = reviewSvc.previewReview("cli", coords, prNumber, new GitHubPrReviewService.PreviewRequest(
                        Optional.empty(), graphId, reportId, Optional.empty(), Optional.empty(),
                        GitHubQualityGate.Policy.defaults(), true, postStatus, false), cancelled);
                if (confirmDigest == null || confirmDigest.isBlank()) {
                    String text = "DRY-RUN PR INLINE REVIEW (zero GitHub writes)\n"
                            + "  PR: #" + prNumber + " (" + coords.fullName() + ")\n"
                            + "  Quality Gate: " + plan.qualityGate().verdict() + "\n"
                            + "  Inline comments: " + plan.inlineComments().size() + "\n"
                            + "  reviewPlanDigest: " + plan.reviewPlanDigest().value() + "\n"
                            + "Re-run with --confirm " + plan.reviewPlanDigest().value() + " to publish.";
                    return emitJsonOrText(out, jsonMode.get(), Map.of("dryRun", true, "plan", plan.toView()), text);
                }
                var expected = new ContentDigest(confirmDigest.strip());
                var receipt = reviewSvc.publishReview("cli", prNumber, new GitHubPrReviewService.PublishRequest(
                        plan.confirmationToken(), expected, true, Optional.ofNullable(eventOverride),
                        Optional.empty(), Optional.of(postStatus), Optional.empty()), cancelled);
                return emitJsonOrText(out, jsonMode.get(), receipt.toView(),
                        "Published PR review #" + receipt.reviewId() + " (" + receipt.state() + "): " + receipt.htmlUrl());
            } catch (GitHubPrReviewService.ReviewException revErr) {
                return emitError(err, jsonMode.get(), revErr.code(), revErr.getMessage(), 2);
            } catch (GitHubRestClient.GitHubApiException apiErr) {
                return emitError(err, jsonMode.get(), apiErr.code(), apiErr.getMessage(), apiErr.httpStatus() == 401 ? 2 : 1);
            } catch (IllegalArgumentException bad) {
                return emitError(err, jsonMode.get(), "INVALID_INPUT", GitHubTokenBroker.redact(bad.getMessage()), 2);
            }
        }

        @Command(name = "status", mixinStandardHelpOptions = true,
                description = "Publish an explicitly confirmed Architecture Quality Gate Commit Status")
        public Integer status(
                @Parameters(index = "0", paramLabel = "URL|OWNER/REPO") String repo,
                @Option(names = "--sha", required = true, paramLabel = "COMMIT_SHA") String sha,
                @Option(names = "--state", required = true, paramLabel = "success|failure|pending|error") String state,
                @Option(names = "--check-context", defaultValue = "evo/architecture-gate") String checkContext,
                @Option(names = "--desc", required = true, paramLabel = "DESCRIPTION") String description,
                @Option(names = "--url", paramLabel = "TARGET_URL") String targetUrl,
                @Option(names = "--confirm", description = "Explicitly confirm posting commit status to GitHub") boolean confirm) {
            if (!confirm) {
                return emitError(err, jsonMode.get(), "EXPLICIT_USER_CONFIRMATION_REQUIRED",
                        "Pass --confirm to publish commit status to GitHub.", 2);
            }
            BooleanSupplier cancelled = () -> Thread.currentThread().isInterrupted();
            try (var broker = factory.tokenBroker()) {
                var coords = GitHubCoordinates.parse(repo);
                var client = factory.restClient(broker, hostPolicy(broker));
                var receipt = client.createCommitStatus(coords, sha, state, checkContext, description,
                        Optional.ofNullable(targetUrl), cancelled);
                return emitJsonOrText(out, jsonMode.get(), receipt,
                        "Posted commit status [" + receipt.state() + "] " + receipt.context() + " on " + shortSha(sha));
            } catch (GitHubRestClient.GitHubApiException apiErr) {
                return emitError(err, jsonMode.get(), apiErr.code(), apiErr.getMessage(), apiErr.httpStatus() == 401 ? 2 : 1);
            } catch (IllegalArgumentException bad) {
                return emitError(err, jsonMode.get(), "INVALID_INPUT", GitHubTokenBroker.redact(bad.getMessage()), 2);
            }
        }

        @Command(name = "sarif", mixinStandardHelpOptions = true,
                description = "Export SARIF 2.1.0 for a canonical policy report and optionally upload to GitHub Code Scanning")
        public Integer sarif(
                @Option(names = "--graph-file", required = true, paramLabel = "GRAPH_JSON") Path graphFile,
                @Option(names = "--report-file", required = true, paramLabel = "REPORT_JSON") Path reportFile,
                @Option(names = "--out", paramLabel = "SARIF_FILE") Path outFile,
                @Option(names = "--repo", paramLabel = "URL|OWNER/REPO") String repo,
                @Option(names = "--sha", paramLabel = "COMMIT_SHA") String sha,
                @Option(names = "--ref", paramLabel = "GIT_REF") String ref,
                @Option(names = "--upload", description = "Upload gzip+base64 SARIF to GitHub Code Scanning") boolean upload,
                @Option(names = "--confirm", description = "Explicitly confirm uploading SARIF to GitHub") boolean confirm) throws Exception {
            BooleanSupplier cancelled = () -> Thread.currentThread().isInterrupted();
            var graph = new com.evolution.analysis.graph.GraphSnapshotCodec().decode(
                    Files.readString(resolve(graphFile), StandardCharsets.UTF_8), GraphJson.Limits.defaults());
            var report = CanonicalRecordReader.read(Workspace.json(resolve(reportFile), 8 * 1024 * 1024), PublishedReports.Report.class);
            var exported = SarifExporter.export(graph, report);
            if (outFile != null) {
                Path target = resolve(outFile);
                if (target.getParent() != null) Files.createDirectories(target.getParent());
                Files.writeString(target, exported.json(), StandardCharsets.UTF_8);
            }
            if (!upload) {
                if (jsonMode.get()) {
                    out.println(exported.json());
                    out.flush();
                    return 0;
                }
                String msg = "Exported SARIF 2.1.0 (" + exported.ruleCount() + " rules, " + exported.resultCount()
                        + " findings, digest=" + exported.sarifDigest().value() + ")"
                        + (outFile != null ? " -> " + outFile : "");
                out.println(msg);
                out.flush();
                return 0;
            }
            if (!confirm) {
                return emitError(err, jsonMode.get(), "EXPLICIT_USER_CONFIRMATION_REQUIRED",
                        "Pass --confirm alongside --upload to submit SARIF to GitHub Code Scanning.", 2);
            }
            if (repo == null || repo.isBlank() || sha == null || sha.isBlank() || ref == null || ref.isBlank()) {
                return emitError(err, jsonMode.get(), "INVALID_INPUT",
                        "--repo, --sha, and --ref are required when using --upload.", 2);
            }
            try (var broker = factory.tokenBroker()) {
                var coords = GitHubCoordinates.parse(repo);
                var client = factory.restClient(broker, hostPolicy(broker));
                var uploaded = SarifExporter.uploadToGitHub(client, coords, graph, report, sha, ref, true, cancelled);
                return emitJsonOrText(out, jsonMode.get(), uploaded.toView(),
                        "Uploaded SARIF 2.1.0 to GitHub Code Scanning (id=" + uploaded.sarifId() + ", findings=" + uploaded.resultCount() + ")");
            } catch (GitHubRestClient.GitHubApiException apiErr) {
                return emitError(err, jsonMode.get(), apiErr.code(), apiErr.getMessage(), apiErr.httpStatus() == 401 ? 2 : 1);
            }
        }

        private Path resolve(Path path) {
            return path.isAbsolute() ? path : cwd.resolve(path);
        }
    }

    @Command(name = "issue", mixinStandardHelpOptions = true, description = "Create confirmed GitHub Issues from architectural findings or hotspots")
    static final class IssueGroup implements Callable<Integer> {
        final Path cwd;
        final PrintWriter out, err;
        final Supplier<Boolean> jsonMode;
        final ContextFactory factory;

        IssueGroup(Path cwd, PrintWriter out, PrintWriter err, Supplier<Boolean> jsonMode, ContextFactory factory) {
            this.cwd = cwd;
            this.out = out;
            this.err = err;
            this.jsonMode = jsonMode;
            this.factory = factory;
        }

        @Override
        public Integer call() {
            new CommandLine(this).usage(out);
            return 0;
        }

        @Command(name = "create", mixinStandardHelpOptions = true,
                description = "2-step confirmed GitHub Issue creation (dry-run without --confirm)")
        public Integer create(
                @Parameters(index = "0", paramLabel = "URL|OWNER/REPO") String repo,
                @Option(names = "--graph-file", required = true, paramLabel = "GRAPH_JSON") Path graphFile,
                @Option(names = "--report-file", paramLabel = "REPORT_JSON") Path reportFile,
                @Option(names = "--finding", paramLabel = "DIGEST") String findingId,
                @Option(names = "--symbol", paramLabel = "SYMBOL_ID") String symbol,
                @Option(names = "--title", paramLabel = "TITLE") String title,
                @Option(names = "--label", paramLabel = "LABEL") List<String> labels,
                @Option(names = "--assignee", paramLabel = "LOGIN") List<String> assignees,
                @Option(names = "--confirm", paramLabel = "PLAN_DIGEST") String confirmDigest) throws Exception {
            BooleanSupplier cancelled = () -> Thread.currentThread().isInterrupted();
            try (var broker = factory.tokenBroker()) {
                var coords = GitHubCoordinates.parse(repo);
                var client = factory.restClient(broker, hostPolicy(broker));
                var graphs = new com.evolution.analysis.graph.InMemoryGraphStore();
                var reports = new PublishedReports();
                Path resolvedGraph = graphFile.isAbsolute() ? graphFile : cwd.resolve(graphFile);
                var loadedGraph = new com.evolution.analysis.graph.GraphSnapshotCodec().decode(
                        Files.readString(resolvedGraph, StandardCharsets.UTF_8), GraphJson.Limits.defaults());
                graphs.save(loadedGraph);
                Optional<ContentDigest> reportId = Optional.empty();
                if (reportFile != null) {
                    Path resolvedReport = reportFile.isAbsolute() ? reportFile : cwd.resolve(reportFile);
                    var loadedReport = CanonicalRecordReader.read(Workspace.json(resolvedReport, 8 * 1024 * 1024), PublishedReports.Report.class);
                    reportId = Optional.of(reports.publish(loadedReport));
                }
                var issueSvc = new GitHubIssueService(client, graphs, reports);
                var plan = issueSvc.prepare("cli", coords, new GitHubIssueService.PrepareRequest(
                        loadedGraph.identity(), reportId,
                        findingId == null || findingId.isBlank() ? Optional.empty() : Optional.of(new ContentDigest(findingId)),
                        Optional.ofNullable(symbol), Optional.ofNullable(title),
                        labels == null ? List.of() : labels,
                        assignees == null ? List.of() : assignees));
                if (confirmDigest == null || confirmDigest.isBlank()) {
                    String text = "DRY-RUN GITHUB ISSUE PLAN (zero GitHub writes)\n"
                            + "  Repository:      " + plan.repository() + "\n"
                            + "  Title:           " + plan.title() + "\n"
                            + "  CODEOWNERS:      " + plan.codeowners() + "\n"
                            + "  issuePlanDigest: " + plan.issuePlanDigest().value() + "\n"
                            + "Re-run with --confirm " + plan.issuePlanDigest().value() + " to create the issue.";
                    return emitJsonOrText(out, jsonMode.get(), Map.of("dryRun", true, "plan", plan.toView()), text);
                }
                var expected = new ContentDigest(confirmDigest.strip());
                var receipt = issueSvc.publish("cli", new GitHubIssueService.PublishRequest(
                        plan.confirmationToken(), expected, true,
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()), cancelled);
                return emitJsonOrText(out, jsonMode.get(), receipt.toView(),
                        "Created GitHub Issue #" + receipt.issueNumber() + ": " + receipt.htmlUrl());
            } catch (GitHubIssueService.IssueException issErr) {
                return emitError(err, jsonMode.get(), issErr.code(), issErr.getMessage(), 2);
            } catch (GitHubRestClient.GitHubApiException apiErr) {
                return emitError(err, jsonMode.get(), apiErr.code(), apiErr.getMessage(), apiErr.httpStatus() == 401 ? 2 : 1);
            } catch (IllegalArgumentException bad) {
                return emitError(err, jsonMode.get(), "INVALID_INPUT", GitHubTokenBroker.redact(bad.getMessage()), 2);
            }
        }
    }

    @Command(name = "login", mixinStandardHelpOptions = true, description = "Sign in with GitHub using OAuth Device Flow (RFC 8628)")
    static final class LoginCmd implements Callable<Integer> {
        final PrintWriter out, err;
        final Supplier<Path> workspaceRoot;
        final Supplier<Boolean> jsonMode;
        final ContextFactory factory;
        @Option(names = "--host", defaultValue = "github.com") String host;
        @Option(names = "--client-id", paramLabel = "CLIENT_ID") String clientId;
        @Option(names = "--scopes", defaultValue = GitHubOAuthService.DEFAULT_SCOPES) String scopes;
        @Option(names = "--max-polls", defaultValue = "60") int maxPolls;
        @Option(names = "--poll-ms", defaultValue = "0") long pollMs;

        LoginCmd(PrintWriter out, PrintWriter err, Supplier<Path> workspaceRoot, Supplier<Boolean> jsonMode, ContextFactory factory) {
            this.out = out;
            this.err = err;
            this.workspaceRoot = workspaceRoot;
            this.jsonMode = jsonMode;
            this.factory = factory;
        }

        @Override
        public Integer call() {
            BooleanSupplier cancelled = () -> Thread.currentThread().isInterrupted();
            var secret = UserSessionStore.secretFromEnvironment(System::getenv);
            Optional<Path> encFile = secret.isPresent() ? Optional.of(workspaceRoot.get().resolve("auth-sessions.enc")) : Optional.empty();
            try (var broker = factory.tokenBroker();
                 var sessions = new UserSessionStore(encFile, secret)) {
                var oauth = factory.oauthService(sessions, hostPolicy(broker), Optional.ofNullable(clientId), Optional.ofNullable(host));
                var start = oauth.startDeviceFlow(Optional.ofNullable(scopes), Optional.ofNullable(host), cancelled);
                if (!jsonMode.get()) {
                    out.println("Sign in with GitHub (Device Flow)");
                    out.println("  1. Open: " + start.verificationUri());
                    out.println("  2. Enter code: " + start.userCode());
                    out.flush();
                }
                int intervalSec = start.interval();
                for (int i = 0; i < Math.max(1, maxPolls); i++) {
                    var poll = oauth.pollDeviceFlow(start.deviceFlowId(), cancelled);
                    if ("AUTHORIZED".equals(poll.status()) && poll.session().isPresent()) {
                        var user = poll.session().orElseThrow().user();
                        var res = new LinkedHashMap<String, Object>();
                        res.put("authenticated", true);
                        res.put("user", user.toPublicUserMap());
                        res.put("persistedEncrypted", sessions.isEncryptedPersistenceEnabled());
                        return emitJsonOrText(out, jsonMode.get(), res,
                                "Signed in to GitHub as @" + user.login() + " (" + user.authMethod() + ").");
                    }
                    if ("EXPIRED".equals(poll.status()) || "DENIED".equals(poll.status()) || "ERROR".equals(poll.status())) {
                        return emitError(err, jsonMode.get(), "OAUTH_DEVICE_" + poll.status(),
                                poll.error().orElse("Device authorization failed."), 2);
                    }
                    intervalSec = Math.max(1, poll.interval());
                    long sleepMs = pollMs > 0 ? pollMs : (intervalSec * 1_000L);
                    if (i + 1 < maxPolls) {
                        Thread.sleep(Math.min(sleepMs, 30_000L));
                    }
                }
                return emitError(err, jsonMode.get(), "OAUTH_DEVICE_TIMEOUT", "Timed out waiting for GitHub authorization.", 2);
            } catch (GitHubOAuthService.OAuthException authErr) {
                return emitError(err, jsonMode.get(), authErr.code(), authErr.getMessage(), 2);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return emitError(err, jsonMode.get(), "CANCELLED", "Login cancelled.", 130);
            }
        }
    }

    @Command(name = "logout", mixinStandardHelpOptions = true, description = "Sign out and revoke cached Evo GitHub sessions")
    static final class LogoutCmd implements Callable<Integer> {
        final PrintWriter out, err;
        final Supplier<Path> workspaceRoot;
        final Supplier<Boolean> jsonMode;
        final ContextFactory factory;
        @Option(names = "--all", description = "Revoke all stored sessions") boolean all;

        LogoutCmd(PrintWriter out, PrintWriter err, Supplier<Path> workspaceRoot, Supplier<Boolean> jsonMode, ContextFactory factory) {
            this.out = out;
            this.err = err;
            this.workspaceRoot = workspaceRoot;
            this.jsonMode = jsonMode;
            this.factory = factory;
        }

        @Override
        public Integer call() throws IOException {
            try (var broker = factory.tokenBroker()) {
                broker.clearSessionToken();
            }
            Path encFile = workspaceRoot.get().resolve("auth-sessions.enc");
            boolean removedFile = Files.deleteIfExists(encFile);
            return emitJsonOrText(out, jsonMode.get(),
                    Map.of("signedOut", true, "all", all, "encryptedSessionFileRemoved", removedFile),
                    "Signed out of Evo GitHub sessions.");
        }
    }

    @Command(name = "whoami", mixinStandardHelpOptions = true, description = "Show currently authenticated GitHub user identity")
    static final class WhoamiCmd implements Callable<Integer> {
        final PrintWriter out, err;
        final Supplier<Boolean> jsonMode;
        final ContextFactory factory;
        @Option(names = "--host", defaultValue = "github.com") String host;

        WhoamiCmd(PrintWriter out, PrintWriter err, Supplier<Boolean> jsonMode, ContextFactory factory) {
            this.out = out;
            this.err = err;
            this.jsonMode = jsonMode;
            this.factory = factory;
        }

        @Override
        public Integer call() {
            BooleanSupplier cancelled = () -> Thread.currentThread().isInterrupted();
            try (var broker = factory.tokenBroker()) {
                var client = factory.restClient(broker, hostPolicy(broker));
                var status = client.authStatus(host, cancelled);
                String text = Boolean.TRUE.equals(status.get("authenticated"))
                        ? "@" + status.get("login") + " (" + status.get("name") + ") on " + status.get("host")
                                + " [source=" + status.get("source") + "]"
                        : "Not signed in to GitHub on " + status.get("host") + ". Run `evo login` or set EVO_GITHUB_TOKEN.";
                return emitJsonOrText(out, jsonMode.get(), status, text);
            }
        }
    }

    @Command(name = "users", mixinStandardHelpOptions = true,
            description = "Inspect registered local multi-user workspace accounts (read-only)")
    static final class UsersGroup implements Callable<Integer> {
        final PrintWriter out, err;
        final Supplier<Path> workspaceRoot;
        final Supplier<Boolean> jsonMode;

        UsersGroup(PrintWriter out, PrintWriter err, Supplier<Path> workspaceRoot, Supplier<Boolean> jsonMode) {
            this.out = out;
            this.err = err;
            this.workspaceRoot = workspaceRoot;
            this.jsonMode = jsonMode;
        }

        @Override
        public Integer call() {
            new CommandLine(this).usage(out);
            return 0;
        }

        @Command(name = "list", mixinStandardHelpOptions = true,
                description = "List registered multi-user accounts from the local SQLite account store (zero secrets)")
        public Integer list(@Option(names = "--db", description = "Optional path to accounts.db") Path dbOverride) {
            Path dbPath = dbOverride != null
                    ? dbOverride.toAbsolutePath().normalize()
                    : workspaceRoot.get().resolve("auth").resolve("accounts.db");
            if (!Files.isRegularFile(dbPath)) {
                return emitJsonOrText(out, jsonMode.get(),
                        Map.of("dbPath", dbPath.toString(), "items", List.of()),
                        "No registered workspace users found at " + dbPath);
            }
            try (var store = new UserAccountStore(dbPath)) {
                var items = store.listUsers().stream().map(UserAccountStore.Account::toPublicMap).toList();
                var sb = new StringBuilder("Registered workspace users (").append(items.size()).append("):\n");
                for (var u : items) {
                    sb.append("  ").append(u.get("login"))
                            .append(" [").append(u.get("role")).append("]")
                            .append(Boolean.TRUE.equals(u.get("disabled")) ? " [DISABLED]" : "")
                            .append("  ").append(u.get("email"));
                    if (Boolean.TRUE.equals(u.get("githubLinked"))) {
                        sb.append("  github=@").append(u.get("githubLogin"));
                    }
                    sb.append('\n');
                }
                return emitJsonOrText(out, jsonMode.get(),
                        Map.of("dbPath", dbPath.toString(), "items", items), sb.toString());
            } catch (RuntimeException failed) {
                return emitError(err, jsonMode.get(), "USER_STORE_UNAVAILABLE", failed.getMessage(), 1);
            }
        }
    }

    private static CapturedRepair.Proposal loadCapturedProposal(Path file) throws IOException {
        var raw = GraphJson.object(Workspace.json(file, 2 * 1024 * 1024));
        Object proposalObj = raw.containsKey("proposal") && raw.get("proposal") instanceof Map<?, ?>
                ? raw.get("proposal")
                : raw;
        var parsed = CanonicalRecordReader.read(proposalObj, CapturedRepair.Proposal.class);
        if (!CapturedRepair.authentic(parsed)) {
            throw new IllegalArgumentException("INVALID_REPAIR_PROPOSAL");
        }
        return parsed;
    }

    private static GitHostPolicy hostPolicy(GitHubTokenBroker broker) {
        return new GitHostPolicy(Set.of(), Map.of(), InetAddress::getAllByName, broker.readOnlyGitCredentials());
    }

    private static int emitJsonOrText(PrintWriter out, boolean json, Object data, String text) {
        if (json) {
            out.println(CanonicalJson.write(EvoApp.envelope(data)));
        } else {
            out.println(text);
        }
        out.flush();
        return 0;
    }

    private static int emitError(PrintWriter err, boolean json, String code, String message, int exitCode) {
        String safe = GitHubTokenBroker.redact(message == null ? code : message);
        if (json) {
            err.println(CanonicalJson.write(Map.of("cliVersion", "architecture.cli:m7.2-v1",
                    "error", Map.of("code", code, "message", safe))));
        } else {
            err.println("[" + code + "] " + safe);
        }
        err.flush();
        return exitCode;
    }

    private static String shortSha(String sha) {
        return sha != null && sha.length() > 10 ? sha.substring(0, 10) : (sha == null ? "" : sha);
    }
}
