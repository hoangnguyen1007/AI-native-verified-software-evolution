package com.evolution.backend.github;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.GraphStore;
import com.evolution.analysis.query.ArchitectureQuery;
import com.evolution.backend.analysis.AnalysisMode;
import com.evolution.backend.analysis.RepositoryConnections;
import com.evolution.backend.auth.AuthHttpApi;
import com.evolution.backend.auth.RequestContext;
import com.evolution.backend.query.ApiLimits;
import com.evolution.backend.query.ArchitectureQueryApi;
import com.evolution.backend.query.BoundedJson;
import com.evolution.backend.query.CapturedRepairVerification;
import com.evolution.backend.query.PublishedReports;
import com.evolution.backend.query.repair.RepairVerificationJobs;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * HTTP/JSON router for all {@code /api/v1/github/*} endpoints in the canonical Workbench contract.
 */
public final class GitHubHttpApi {
    public static final String PREFIX = "/api/v1/github/";

    private final GitHubTokenBroker tokenBroker;
    private final GitHubRestClient restClient;
    private final RepositoryConnections connections;
    private final GitHubPrDiffService prDiffService;
    private final GitHubRepairPublisher repairPublisher;
    private final RepairVerificationJobs repairVerificationJobs;
    private final ApiLimits limits;
    private final AuthHttpApi authApi;
    private final GitHubPrReviewService prReviewService;
    private final GitHubIssueService issueService;
    private final GitHubWebhookHandler webhookHandler;
    private final GraphStore graphs;
    private final PublishedReports reports;

    public GitHubHttpApi(
            GitHubTokenBroker tokenBroker,
            GitHubRestClient restClient,
            RepositoryConnections connections,
            GitHubPrDiffService prDiffService,
            GitHubRepairPublisher repairPublisher,
            ApiLimits limits) {
        this(tokenBroker, restClient, connections, prDiffService, repairPublisher, null, limits,
                null, null, null, null, null, null);
    }

    public GitHubHttpApi(
            GitHubTokenBroker tokenBroker,
            GitHubRestClient restClient,
            RepositoryConnections connections,
            GitHubPrDiffService prDiffService,
            GitHubRepairPublisher repairPublisher,
            RepairVerificationJobs repairVerificationJobs,
            ApiLimits limits) {
        this(tokenBroker, restClient, connections, prDiffService, repairPublisher, repairVerificationJobs, limits,
                null, null, null, null, null, null);
    }

    private GitHubHttpApi(
            GitHubTokenBroker tokenBroker,
            GitHubRestClient restClient,
            RepositoryConnections connections,
            GitHubPrDiffService prDiffService,
            GitHubRepairPublisher repairPublisher,
            RepairVerificationJobs repairVerificationJobs,
            ApiLimits limits,
            AuthHttpApi authApi,
            GitHubPrReviewService prReviewService,
            GitHubIssueService issueService,
            GitHubWebhookHandler webhookHandler,
            GraphStore graphs,
            PublishedReports reports) {
        this.tokenBroker = Objects.requireNonNull(tokenBroker, "tokenBroker");
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        this.connections = connections;
        this.prDiffService = prDiffService;
        this.repairPublisher = repairPublisher;
        this.repairVerificationJobs = repairVerificationJobs;
        this.limits = Objects.requireNonNull(limits, "limits");
        this.authApi = authApi;
        this.prReviewService = prReviewService;
        this.issueService = issueService;
        this.webhookHandler = webhookHandler;
        this.graphs = graphs;
        this.reports = reports;
    }

    public GitHubHttpApi withEnterpriseServices(
            AuthHttpApi authApi,
            GitHubPrReviewService prReviewService,
            GitHubIssueService issueService,
            GitHubWebhookHandler webhookHandler,
            GraphStore graphs,
            PublishedReports reports) {
        return new GitHubHttpApi(tokenBroker, restClient, connections, prDiffService, repairPublisher,
                repairVerificationJobs, limits, authApi, prReviewService, issueService, webhookHandler, graphs, reports);
    }

    public boolean hasEnterpriseServices() {
        return prReviewService != null || issueService != null;
    }

    public boolean isWebhookConfigured() {
        return webhookHandler != null && webhookHandler.isConfigured();
    }

    public static String allowedMethods(String path) {
        if (path.equals("/api/v1/github/auth/status")) return "GET";
        if (path.equals("/api/v1/github/auth/session")) return "POST, DELETE";
        if (path.equals("/api/v1/github/refs")) return "GET, POST";
        if (path.equals("/api/v1/github/orgs") || path.equals("/api/v1/github/user/repos")
                || path.equals("/api/v1/github/webhooks/deliveries")) return "GET";
        if (path.equals("/api/v1/github/webhooks")
                || path.equals("/api/v1/github/repairs/prepare")
                || path.equals("/api/v1/github/repairs/publish")) return "POST";
        if (path.endsWith("/compare") || path.endsWith("/review-comment")
                || path.endsWith("/review/preview") || path.endsWith("/review/publish")
                || path.endsWith("/status") || path.endsWith("/code-scanning/sarif")
                || path.endsWith("/issues/prepare") || path.endsWith("/issues/publish")) return "POST";
        return "GET";
    }

    public ArchitectureQueryApi.Response handle(
            String method,
            String rawPath,
            String rawQuery,
            String contentType,
            byte[] body,
            BooleanSupplier cancelled) {
        try {
            if (cancelled != null && cancelled.getAsBoolean()) {
                return error(409, "REQUEST_CANCELLED", "Request cancelled.");
            }
            if (body != null && body.length > limits.requestBytes()) {
                return error(413, "REQUEST_TOO_LARGE", "Request exceeds transport limit.");
            }
            BooleanSupplier cancelCheck = cancelled == null ? () -> false : cancelled;

            if (rawPath.equals("/api/v1/github/auth/status")) {
                requireMethod(method, "GET");
                requireEmpty(body);
                String host = queryParam(rawQuery, "host").orElse(GitHubCoordinates.DEFAULT_HOST);
                return ok(200, restClient.authStatus(host, cancelCheck));
            }
            if (rawPath.equals("/api/v1/github/auth/session")) {
                if ("POST".equals(method)) {
                    var input = decode(body, contentType);
                    checkKeys(input, "token", "host");
                    String token = requiredText(input, "token");
                    Optional<String> host = optionalText(input, "host");
                    if (authApi != null) {
                        var session = authApi.oauthService().authenticateSessionPat(token.toCharArray(), host, cancelCheck);
                        var u = session.user();
                        boolean canWrite = u.scopes().isEmpty()
                                || u.scopes().contains("repo")
                                || u.scopes().contains("public_repo");
                        var status = new LinkedHashMap<String, Object>();
                        status.put("host", u.host());
                        status.put("authenticated", true);
                        status.put("source", GitHubTokenBroker.SOURCE_SESSION_PAT);
                        status.put("login", u.login());
                        status.put("name", u.name());
                        status.put("scopes", u.scopes());
                        status.put("canPushAndOpenPr", canWrite);
                        status.put("user", u.toPublicUserMap());
                        status.put("csrfToken", u.csrfToken());
                        restClient.lastRateLimit().ifPresent(rl -> status.put("rateLimit", rl.toMap()));
                        return okWithHeaders(200, status, Map.of(
                                "Set-Cookie", List.of(authApi.sessionCookieHeader(session.sessionId()))));
                    }
                    tokenBroker.setSessionToken(token.toCharArray(), host);
                    var status = restClient.authStatus(host.orElse(GitHubCoordinates.DEFAULT_HOST), cancelCheck);
                    if (!Boolean.TRUE.equals(status.get("authenticated"))) {
                        tokenBroker.clearSessionToken();
                        return error(401, "GITHUB_AUTH_REQUIRED",
                                "Token could not be authenticated with GitHub and was not stored.");
                    }
                    return ok(200, status);
                }
                if ("DELETE".equals(method)) {
                    requireEmpty(body);
                    if (authApi != null) {
                        RequestContext.current()
                                .flatMap(RequestContext.ActiveRequest::sessionId)
                                .ifPresent(authApi.sessionStore()::invalidate);
                        var cleared = new LinkedHashMap<String, Object>();
                        cleared.put("host", GitHubCoordinates.DEFAULT_HOST);
                        cleared.put("authenticated", false);
                        cleared.put("source", GitHubTokenBroker.SOURCE_NONE);
                        cleared.put("login", null);
                        cleared.put("name", null);
                        cleared.put("scopes", List.of());
                        cleared.put("canPushAndOpenPr", false);
                        return okWithHeaders(200, cleared, Map.of(
                                "Set-Cookie", List.of(authApi.clearSessionCookieHeader())));
                    }
                    tokenBroker.clearSessionToken();
                    return ok(200, restClient.authStatus(GitHubCoordinates.DEFAULT_HOST, cancelCheck));
                }
                return error(405, "METHOD_NOT_ALLOWED", "Use POST or DELETE.");
            }
            if (rawPath.equals("/api/v1/github/orgs")) {
                requireMethod(method, "GET");
                requireEmpty(body);
                String host = queryParam(rawQuery, "host").orElse(GitHubCoordinates.DEFAULT_HOST);
                var orgs = restClient.userOrgs(host, 100, cancelCheck);
                return ok(200, Map.of("items", orgs));
            }
            if (rawPath.equals("/api/v1/github/user/repos")) {
                requireMethod(method, "GET");
                requireEmpty(body);
                String host = queryParam(rawQuery, "host").orElse(GitHubCoordinates.DEFAULT_HOST);
                Optional<String> org = queryParam(rawQuery, "org");
                String visibility = queryParam(rawQuery, "visibility").orElse("all");
                String sort = queryParam(rawQuery, "sort").orElse("updated");
                int page = queryParam(rawQuery, "page").map(GitHubHttpApi::parsePositiveInt).orElse(1);
                Optional<String> q = queryParam(rawQuery, "q").map(s -> s.toLowerCase(Locale.ROOT));
                var fetched = restClient.userRepos(host, org, visibility, sort, page, 30, cancelCheck);
                var filtered = q.isEmpty() ? fetched : fetched.stream().filter(r ->
                        r.fullName().toLowerCase(Locale.ROOT).contains(q.orElseThrow())
                                || r.description().toLowerCase(Locale.ROOT).contains(q.orElseThrow())
                                || r.language().toLowerCase(Locale.ROOT).contains(q.orElseThrow())).toList();
                return ok(200, Map.of("page", page, "hasMore", fetched.size() >= 30, "items", filtered));
            }
            if (rawPath.equals("/api/v1/github/webhooks/deliveries")) {
                requireMethod(method, "GET");
                requireEmpty(body);
                if (webhookHandler == null) {
                    return ok(200, Map.of("webhookConfigured", false, "items", List.of()));
                }
                boolean multiUser = RequestContext.isMultiUserMode()
                        || (authApi != null && authApi.isMultiUserMode());
                if (multiUser) {
                    var allowedRepos = new HashSet<String>();
                    if (connections != null) {
                        for (var conn : connections.list()) {
                            connections.remoteUrl(conn.setup())
                                    .flatMap(GitHubCoordinates::tryParse)
                                    .ifPresent(c -> allowedRepos.add(c.fullName().toLowerCase(Locale.ROOT)));
                        }
                    }
                    return ok(200, webhookHandler.deliveriesView(Optional.of(allowedRepos)));
                }
                return ok(200, webhookHandler.deliveriesView());
            }
            if (rawPath.equals("/api/v1/github/webhooks")) {
                requireMethod(method, "POST");
                return handleWebhook(null, null, null, body);
            }
            if (rawPath.equals("/api/v1/github/refs")) {
                String repoParam;
                if ("GET".equals(method)) {
                    requireEmpty(body);
                    repoParam = queryParam(rawQuery, "repo")
                            .orElseThrow(() -> new IllegalArgumentException("Missing repo query parameter"));
                } else if ("POST".equals(method)) {
                    var input = decode(body, contentType);
                    checkKeys(input, "repo");
                    repoParam = requiredText(input, "repo");
                } else {
                    return error(405, "METHOD_NOT_ALLOWED", "Use GET or POST.");
                }
                var coords = GitHubCoordinates.parse(repoParam);
                var meta = restClient.repository(coords, cancelCheck);
                var branches = restClient.branches(coords, 50, cancelCheck);
                var tags = restClient.tags(coords, 30, cancelCheck);
                var pulls = restClient.pulls(coords, "open", 20, cancelCheck);
                var data = new LinkedHashMap<String, Object>();
                data.put("repository", meta);
                data.put("defaultBranch", meta.defaultBranch());
                data.put("branches", branches);
                data.put("tags", tags);
                data.put("pulls", pulls);
                data.put("rateLimit", restClient.lastRateLimit().map(GitHubRestClient.RateLimit::toMap).orElse(null));
                return ok(200, data);
            }
            if (rawPath.startsWith("/api/v1/github/comparisons/")) {
                requireMethod(method, "GET");
                requireEmpty(body);
                if (prDiffService == null) {
                    return error(503, "GITHUB_COMPARE_UNAVAILABLE", "GitHub PR diff service is not configured.");
                }
                String diffId = rawPath.substring("/api/v1/github/comparisons/".length());
                var diff = prDiffService.get(diffId)
                        .orElseThrow(() -> new HttpFailure(404, "DIFF_NOT_FOUND", "Comparison receipt not found."));
                return ok(200, diff.toView());
            }
            if (rawPath.equals("/api/v1/github/repairs/prepare")) {
                requireMethod(method, "POST");
                if (repairPublisher == null) {
                    return error(503, "GITHUB_PUBLISH_UNAVAILABLE", "GitHub repair publisher is not configured.");
                }
                var input = decode(body, contentType);
                checkKeys(input, "proposalId", "setupId", "setup", "repoCoordinate", "baseBranch", "headBranch",
                        "title", "draft", "allowUnverified", "verificationJobId");
                var proposalId = new ContentDigest(requiredText(input, "proposalId"));
                Optional<String> setupId = optionalText(input, "setupId").or(() -> optionalText(input, "setup"));
                Optional<Boolean> draft = input.containsKey("draft") && input.get("draft") != null
                        ? Optional.of(requireBoolean(input, "draft"))
                        : Optional.empty();
                Optional<CapturedRepairVerification.Result> verification = resolveVerification(
                        optionalText(input, "verificationJobId"), proposalId);
                boolean allowUnverified = input.containsKey("allowUnverified") && input.get("allowUnverified") != null
                        && requireBoolean(input, "allowUnverified");
                var plan = repairPublisher.prepare(new GitHubRepairPublisher.PrepareRequest(
                        proposalId,
                        setupId,
                        optionalText(input, "repoCoordinate"),
                        optionalText(input, "baseBranch"),
                        optionalText(input, "headBranch"),
                        optionalText(input, "title"),
                        draft,
                        allowUnverified,
                        verification), cancelCheck);
                return ok(200, plan.toView());
            }
            if (rawPath.equals("/api/v1/github/repairs/publish")) {
                requireMethod(method, "POST");
                if (repairPublisher == null) {
                    return error(503, "GITHUB_PUBLISH_UNAVAILABLE", "GitHub repair publisher is not configured.");
                }
                var input = decode(body, contentType);
                checkKeys(input, "confirmationToken", "publishPlanDigest", "confirmedByUser",
                        "title", "bodyMarkdown", "draft", "allowUnverified");
                String token = requiredText(input, "confirmationToken");
                var digest = new ContentDigest(requiredText(input, "publishPlanDigest"));
                boolean confirmed = input.containsKey("confirmedByUser")
                        && requireBoolean(input, "confirmedByUser");
                Optional<Boolean> draft = input.containsKey("draft") && input.get("draft") != null
                        ? Optional.of(requireBoolean(input, "draft"))
                        : Optional.empty();
                var receipt = repairPublisher.publish(new GitHubRepairPublisher.PublishRequest(
                        token, digest, confirmed,
                        optionalText(input, "title"),
                        optionalText(input, "bodyMarkdown"),
                        draft), cancelCheck);
                return ok(201, receipt.toView());
            }
            if (rawPath.startsWith("/api/v1/github/connections/")) {
                return handleConnectionRoute(method, rawPath, rawQuery, contentType, body, cancelCheck);
            }
            return error(404, "NOT_FOUND", "GitHub API route not found.");
        } catch (HttpFailure failure) {
            return error(failure.status, failure.code, failure.getMessage());
        } catch (com.evolution.backend.auth.UserAccountStore.AccountException accErr) {
            return error(accErr.status(), accErr.code(), accErr.getMessage());
        } catch (com.evolution.backend.auth.GitHubOAuthService.OAuthException authErr) {
            return error(authErr.status(), authErr.code(), authErr.getMessage());
        } catch (GitHubRestClient.GitHubApiException apiErr) {
            return errorWithRateLimit(apiErr.httpStatus(), apiErr.code(), apiErr.getMessage(), apiErr.rateLimit());
        } catch (GitHubRepairPublisher.PublishException pubErr) {
            return error(pubErr.status(), pubErr.code(), pubErr.getMessage());
        } catch (GitHubPrReviewService.ReviewException revErr) {
            return error(revErr.status(), revErr.code(), revErr.getMessage());
        } catch (GitHubIssueService.IssueException issErr) {
            return error(issErr.status(), issErr.code(), issErr.getMessage());
        } catch (com.evolution.backend.query.repair.SafePatchApplier.AccessDeniedException denied) {
            return error(403, "APPLY_ACCESS_DENIED", "Access denied for this user session.");
        } catch (SecurityException denied) {
            return error(403, "ACCESS_DENIED", "Access denied for this user session.");
        } catch (BoundedJson.TooLarge large) {
            return error(422, "RESPONSE_TOO_LARGE", "Response exceeds transport limit.");
        } catch (GraphJson.LimitExceededException large) {
            return error(413, "JSON_LIMIT_EXCEEDED", "JSON payload exceeds transport limit.");
        } catch (IllegalArgumentException invalid) {
            return error(400, "INVALID_REQUEST", GitHubTokenBroker.redact(invalid.getMessage()));
        } catch (IllegalStateException conflict) {
            String msg = conflict.getMessage() == null ? "CONFLICT" : conflict.getMessage();
            if ("REQUEST_CANCELLED".equals(msg)) {
                return error(409, "REQUEST_CANCELLED", "Request cancelled.");
            }
            return error(409, "GITHUB_OPERATION_CONFLICT", GitHubTokenBroker.redact(msg));
        } catch (RuntimeException unexpected) {
            return error(500, "INTERNAL_ERROR", "Internal GitHub integration error.");
        }
    }

    private ArchitectureQueryApi.Response handleConnectionRoute(
            String method,
            String rawPath,
            String rawQuery,
            String contentType,
            byte[] body,
            BooleanSupplier cancelled) {
        if (connections == null) {
            return error(503, "CONNECTIONS_UNAVAILABLE", "Repository connections are unavailable.");
        }
        // /api/v1/github/connections/{setup}/{op}[/{num}[/{sub}]]
        String[] parts = rawPath.split("/", -1);
        if (parts.length < 7) {
            return error(404, "NOT_FOUND", "Unknown GitHub connection route.");
        }
        String setup = parts[5];
        String op = parts[6];
        var coords = resolveConnectionCoords(setup);

        if (parts.length == 7) {
            return switch (op) {
                case "metadata" -> {
                    requireMethod(method, "GET");
                    requireEmpty(body);
                    yield ok(200, restClient.repository(coords, cancelled));
                }
                case "branches" -> {
                    requireMethod(method, "GET");
                    requireEmpty(body);
                    yield ok(200, Map.of("repository", coords.fullName(),
                            "items", restClient.branches(coords, 100, cancelled)));
                }
                case "tags" -> {
                    requireMethod(method, "GET");
                    requireEmpty(body);
                    yield ok(200, Map.of("repository", coords.fullName(),
                            "items", restClient.tags(coords, 50, cancelled)));
                }
                case "commits" -> {
                    requireMethod(method, "GET");
                    requireEmpty(body);
                    Optional<String> sha = queryParam(rawQuery, "sha");
                    yield ok(200, Map.of("repository", coords.fullName(),
                            "items", restClient.commits(coords, sha, 30, cancelled)));
                }
                case "pulls" -> {
                    requireMethod(method, "GET");
                    requireEmpty(body);
                    String state = queryParam(rawQuery, "state").orElse("open");
                    yield ok(200, Map.of("repository", coords.fullName(),
                            "items", restClient.pulls(coords, state, 30, cancelled)));
                }
                case "compare" -> {
                    requireMethod(method, "POST");
                    if (prDiffService == null) {
                        throw new HttpFailure(503, "GITHUB_COMPARE_UNAVAILABLE", "PR compare service unavailable.");
                    }
                    var input = decode(body, contentType);
                    checkKeys(input, "pullNumber", "baseRef", "headRef", "mode");
                    OptionalInt prNum = OptionalInt.empty();
                    if (input.containsKey("pullNumber") && input.get("pullNumber") != null) {
                        if (!(input.get("pullNumber") instanceof Number n)) {
                            throw new IllegalArgumentException("pullNumber must be an integer");
                        }
                        prNum = OptionalInt.of(GitHubCoordinates.validatePullNumber(n.intValue()));
                    }
                    var mode = input.containsKey("mode")
                            ? AnalysisMode.parse(requiredText(input, "mode"))
                            : AnalysisMode.FAST_ARCHITECTURE;
                    var diff = prDiffService.compare(setup, prNum, optionalText(input, "baseRef"),
                            optionalText(input, "headRef"), mode, cancelled);
                    yield ok(200, diff.toView());
                }
                case "ci-workflow" -> {
                    requireMethod(method, "GET");
                    requireEmpty(body);
                    boolean failOnMust = !"false".equalsIgnoreCase(queryParam(rawQuery, "failOnMust").orElse("true"));
                    int minScore = queryParam(rawQuery, "minScore").map(GitHubHttpApi::parsePositiveInt).orElse(60);
                    String defaultBranch = "main";
                    try {
                        defaultBranch = restClient.repository(coords, cancelled).defaultBranch();
                    } catch (GitHubRestClient.GitHubApiException ignored) {}
                    var wf = GitHubCiWorkflowGenerator.generate(coords, defaultBranch, failOnMust, minScore);
                    yield ok(200, wf.toView());
                }
                default -> error(404, "NOT_FOUND", "Unknown GitHub connection operation.");
            };
        }

        // GET /api/v1/github/connections/{setup}/pulls/enriched
        if ("pulls".equals(op) && parts.length == 8 && "enriched".equals(parts[7])) {
            requireMethod(method, "GET");
            requireEmpty(body);
            if (prReviewService == null) {
                return error(503, "GITHUB_PR_REVIEW_UNAVAILABLE", "Enriched PR review service is not configured.");
            }
            String state = queryParam(rawQuery, "state").orElse("open");
            return ok(200, prReviewService.enrichedPulls(setup, coords, state, cancelled));
        }

        // POST /api/v1/github/connections/{setup}/code-scanning/sarif
        if ("code-scanning".equals(op) && parts.length == 8 && "sarif".equals(parts[7])) {
            requireMethod(method, "POST");
            if (graphs == null || reports == null) {
                return error(503, "SARIF_UPLOAD_UNAVAILABLE", "Graph/report store unavailable for SARIF upload.");
            }
            var input = decode(body, contentType);
            checkKeys(input, "graph", "report", "commitSha", "ref", "confirmedByUser");
            boolean confirmed = input.containsKey("confirmedByUser") && requireBoolean(input, "confirmedByUser");
            if (!confirmed) {
                return error(400, "EXPLICIT_USER_CONFIRMATION_REQUIRED", "confirmedByUser=true is required before uploading SARIF.");
            }
            var graphId = new ContentDigest(requiredText(input, "graph"));
            var reportId = new ContentDigest(requiredText(input, "report"));
            String commitSha = requiredText(input, "commitSha");
            String ref = requiredText(input, "ref");
            var graph = graphs.load(graphId)
                    .orElseThrow(() -> new HttpFailure(404, "GRAPH_NOT_FOUND", "Snapshot graph not found."));
            var report = reports.get(reportId)
                    .orElseThrow(() -> new HttpFailure(404, "REPORT_NOT_FOUND", "Policy report not found."));
            var outcome = SarifExporter.uploadToGitHub(restClient, coords, graph, report, commitSha, ref, true, cancelled);
            return ok(201, outcome.toView());
        }

        // POST /api/v1/github/connections/{setup}/issues/{prepare|publish}
        if ("issues".equals(op) && parts.length == 8) {
            if (issueService == null) {
                return error(503, "GITHUB_ISSUES_UNAVAILABLE", "GitHub Issue service is not configured.");
            }
            requireMethod(method, "POST");
            var input = decode(body, contentType);
            if ("prepare".equals(parts[7])) {
                checkKeys(input, "graph", "report", "findingId", "hotspotSymbol", "title", "labels", "assignees");
                var graphId = new ContentDigest(requiredText(input, "graph"));
                var plan = issueService.prepare(setup, coords, new GitHubIssueService.PrepareRequest(
                        graphId,
                        optionalText(input, "report").map(ContentDigest::new),
                        optionalText(input, "findingId").map(ContentDigest::new),
                        optionalText(input, "hotspotSymbol"),
                        optionalText(input, "title"),
                        stringList(input.get("labels")),
                        stringList(input.get("assignees"))));
                return ok(200, plan.toView());
            }
            if ("publish".equals(parts[7])) {
                checkKeys(input, "confirmationToken", "issuePlanDigest", "confirmedByUser",
                        "titleOverride", "bodyOverride", "labelsOverride", "assigneesOverride");
                String token = requiredText(input, "confirmationToken");
                var digest = new ContentDigest(requiredText(input, "issuePlanDigest"));
                boolean confirmed = input.containsKey("confirmedByUser") && requireBoolean(input, "confirmedByUser");
                var receipt = issueService.publish(setup, new GitHubIssueService.PublishRequest(
                        token, digest, confirmed,
                        optionalText(input, "titleOverride"),
                        optionalText(input, "bodyOverride"),
                        input.containsKey("labelsOverride") ? Optional.of(stringList(input.get("labelsOverride"))) : Optional.empty(),
                        input.containsKey("assigneesOverride") ? Optional.of(stringList(input.get("assigneesOverride"))) : Optional.empty()),
                        cancelled);
                return ok(201, receipt.toView());
            }
        }

        // POST /api/v1/github/connections/{setup}/commits/{sha}/status
        if ("commits".equals(op) && parts.length == 9 && "status".equals(parts[8])) {
            requireMethod(method, "POST");
            String sha = GitHubCoordinates.validateRef(parts[7]);
            var input = decode(body, contentType);
            checkKeys(input, "confirmedByUser", "state", "context", "description", "targetUrl",
                    "createCheckRun", "annotations", "checkRunTitle", "checkRunSummary");
            boolean confirmed = input.containsKey("confirmedByUser") && requireBoolean(input, "confirmedByUser");
            if (!confirmed) {
                return error(400, "EXPLICIT_USER_CONFIRMATION_REQUIRED", "confirmedByUser=true is required before posting commit status.");
            }
            String state = requiredText(input, "state");
            String context = optionalText(input, "context").orElse("evo/architecture-gate");
            String desc = requiredText(input, "description");
            var statusReceipt = restClient.createCommitStatus(coords, sha, state, context, desc,
                    optionalText(input, "targetUrl"), cancelled);
            var out = new LinkedHashMap<String, Object>();
            out.put("statusId", statusReceipt.id());
            out.put("state", statusReceipt.state());
            out.put("context", statusReceipt.context());
            out.put("description", statusReceipt.description());
            if (Boolean.TRUE.equals(input.get("createCheckRun"))) {
                String conclusion = "success".equals(state) ? "success" : ("failure".equals(state) ? "failure" : "neutral");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> annotations = input.get("annotations") instanceof List<?> rawList
                        ? rawList.stream().filter(Map.class::isInstance).map(m -> (Map<String, Object>) m).toList()
                        : List.of();
                var check = restClient.createCheckRun(
                        coords, context, sha, "completed", conclusion,
                        optionalText(input, "checkRunTitle").orElse(context),
                        optionalText(input, "checkRunSummary").orElse(desc),
                        annotations, cancelled);
                out.put("checkRunId", check.id());
                out.put("checkRunUrl", check.htmlUrl());
            }
            return ok(201, out);
        }

        if ("pulls".equals(op) && (parts.length == 8 || parts.length == 9 || parts.length == 10)) {
            int prNum;
            try {
                prNum = GitHubCoordinates.validatePullNumber(Integer.parseInt(parts[7]));
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("Invalid pull request number");
            }
            if (parts.length == 10 && "review".equals(parts[8])) {
                if (prReviewService == null) {
                    return error(503, "GITHUB_PR_REVIEW_UNAVAILABLE", "PR inline review service is not configured.");
                }
                requireMethod(method, "POST");
                var input = decode(body, contentType);
                if ("preview".equals(parts[9])) {
                    checkKeys(input, "diffId", "graph", "report", "baseGraph", "proposalId",
                            "qualityGate", "includeSuggestions", "postCommitStatus", "postCheckRun");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> qgMap = input.get("qualityGate") instanceof Map<?, ?> m
                            ? (Map<String, Object>) m : Map.of();
                    boolean includeSuggestions = !input.containsKey("includeSuggestions") || requireBoolean(input, "includeSuggestions");
                    boolean postStatus = Boolean.TRUE.equals(input.get("postCommitStatus"));
                    boolean postCheck = Boolean.TRUE.equals(input.get("postCheckRun"));
                    var plan = prReviewService.previewReview(setup, coords, prNum, new GitHubPrReviewService.PreviewRequest(
                            optionalText(input, "diffId"),
                            optionalText(input, "graph").map(ContentDigest::new),
                            optionalText(input, "report").map(ContentDigest::new),
                            optionalText(input, "baseGraph").map(ContentDigest::new),
                            optionalText(input, "proposalId").map(ContentDigest::new),
                            GitHubQualityGate.Policy.fromMap(qgMap),
                            includeSuggestions,
                            postStatus,
                            postCheck), cancelled);
                    return ok(200, plan.toView());
                }
                if ("publish".equals(parts[9])) {
                    checkKeys(input, "confirmationToken", "reviewPlanDigest", "confirmedByUser",
                            "event", "bodyOverride", "postCommitStatus", "postCheckRun");
                    String token = requiredText(input, "confirmationToken");
                    var digest = new ContentDigest(requiredText(input, "reviewPlanDigest"));
                    boolean confirmed = input.containsKey("confirmedByUser") && requireBoolean(input, "confirmedByUser");
                    var receipt = prReviewService.publishReview(setup, prNum, new GitHubPrReviewService.PublishRequest(
                            token, digest, confirmed,
                            optionalText(input, "event"),
                            optionalText(input, "bodyOverride"),
                            input.containsKey("postCommitStatus") && input.get("postCommitStatus") != null
                                    ? Optional.of(requireBoolean(input, "postCommitStatus")) : Optional.empty(),
                            input.containsKey("postCheckRun") && input.get("postCheckRun") != null
                                    ? Optional.of(requireBoolean(input, "postCheckRun")) : Optional.empty()),
                            cancelled);
                    return ok(201, receipt.toView());
                }
            }
            if (parts.length == 8) {
                requireMethod(method, "GET");
                requireEmpty(body);
                var pr = restClient.pull(coords, prNum, cancelled);
                var files = restClient.pullFiles(coords, prNum, 100, cancelled);
                return ok(200, Map.of("pull", pr, "files", files));
            }
            String sub = parts[8];
            if ("checks".equals(sub)) {
                requireMethod(method, "GET");
                requireEmpty(body);
                var pr = restClient.pull(coords, prNum, cancelled);
                var checks = restClient.checkRuns(coords, pr.headSha(), cancelled);
                return ok(200, Map.of("pullNumber", prNum, "headSha", pr.headSha(),
                        "checkRuns", checks, "items", checks));
            }
            if ("review-comment".equals(sub)) {
                requireMethod(method, "POST");
                if (prDiffService == null) {
                    throw new HttpFailure(503, "GITHUB_COMPARE_UNAVAILABLE", "PR compare service unavailable.");
                }
                var input = decode(body, contentType);
                checkKeys(input, "diffId", "diffDigest", "bodyMarkdown", "confirmedByUser");
                Optional<String> diffId = optionalText(input, "diffId");
                Optional<ContentDigest> digest = optionalText(input, "diffDigest").map(ContentDigest::new);
                Optional<String> bodyMarkdown = optionalText(input, "bodyMarkdown");
                boolean confirmed = input.containsKey("confirmedByUser")
                        && requireBoolean(input, "confirmedByUser");
                var receipt = prDiffService.postConfirmedReviewComment(
                        setup, prNum, diffId, digest, bodyMarkdown, confirmed, cancelled);
                return ok(200, Map.of(
                        "commentId", receipt.id(),
                        "htmlUrl", receipt.htmlUrl(),
                        "url", receipt.htmlUrl(),
                        "updatedExisting", receipt.updated()));
            }
        }
        return error(404, "NOT_FOUND", "Unknown GitHub pull request route.");
    }

    private Optional<CapturedRepairVerification.Result> resolveVerification(
            Optional<String> jobIdText, ContentDigest proposalId) {
        if (repairVerificationJobs == null) {
            return Optional.empty();
        }
        String ownerUserId = RequestContext.currentUserId();
        if (jobIdText != null && jobIdText.isPresent()) {
            UUID id = null;
            try {
                id = UUID.fromString(jobIdText.orElseThrow());
            } catch (IllegalArgumentException ignored) {
                // Fall through to historical rejection scan so malformed UUIDs never bypass REJECTED blocks.
            }
            if (id != null) {
                var viewOpt = repairVerificationJobs.get(ownerUserId, id);
                if (viewOpt.isPresent()) {
                    var view = viewOpt.orElseThrow();
                    if (view.effectiveProposalId().equals(proposalId)) {
                        var resolved = view.verification()
                                .or(() -> view.report().flatMap(RepairVerificationJobs::reconstructVerificationFromReport))
                                .filter(ver -> ver.proposal().equals(proposalId));
                        if (resolved.isPresent()) {
                            return resolved;
                        }
                    }
                    checkRejectedInRoundsOrThrow(view, proposalId);
                }
            }
        }
        // Even when verificationJobId is omitted, unknown, malformed, or points to an unrelated job,
        // never allow a previously REJECTED proposal (final or earlier round) owned by this user
        // to bypass the hard rejection block via allowUnverified=true.
        for (var view : repairVerificationJobs.list(ownerUserId)) {
            if (view.effectiveProposalId().equals(proposalId)) {
                var resolved = view.verification()
                        .or(() -> view.report().flatMap(RepairVerificationJobs::reconstructVerificationFromReport))
                        .filter(ver -> ver.proposal().equals(proposalId));
                if (resolved.isPresent()
                        && resolved.orElseThrow().verdict() == CapturedRepairVerification.Verdict.REJECTED) {
                    return resolved;
                }
            }
            checkRejectedInRoundsOrThrow(view, proposalId);
        }
        return Optional.empty();
    }

    private static void checkRejectedInRoundsOrThrow(RepairVerificationJobs.JobView view, ContentDigest proposalId) {
        var rounds = !view.rounds().isEmpty()
                ? view.rounds()
                : view.report().map(com.evolution.backend.query.repair.RepairVerificationReport.Report::rounds).orElse(List.of());
        for (var round : rounds) {
            if (proposalId.equals(round.proposalId()) && "REJECTED".equalsIgnoreCase(round.verdict())) {
                throw new HttpFailure(409, "REPAIR_VERIFICATION_REJECTED",
                        "Repair proposal was rejected in verification round " + round.round()
                                + " and cannot be published to GitHub.");
            }
        }
    }

    /** Direct webhook entrypoint preserving raw unparsed request bytes for HMAC-SHA256 verification. */
    public ArchitectureQueryApi.Response handleWebhook(
            String eventHeader,
            String deliveryHeader,
            String signature256Header,
            byte[] rawBody) {
        if (webhookHandler == null) {
            return error(503, "WEBHOOK_SECRET_NOT_CONFIGURED", "GitHub webhooks are not configured on this server.");
        }
        var res = webhookHandler.handleDelivery(eventHeader, deliveryHeader, signature256Header, rawBody);
        if (res.httpStatus() >= 400) {
            return error(res.httpStatus(), res.code(), String.valueOf(res.payload().getOrDefault("message", res.code())));
        }
        return ok(res.httpStatus(), res.payload());
    }

    private GitHubCoordinates.Coordinates resolveConnectionCoords(String setup) {
        if (connections.existsOwnedByOther(setup)) {
            throw new HttpFailure(403, "ACCESS_DENIED", "Connection belongs to another user session.");
        }
        if (!connections.has(setup)) {
            throw new HttpFailure(404, "CONNECTION_NOT_FOUND", "Connection not found.");
        }
        String remoteUrl = connections.remoteUrl(setup)
                .orElseThrow(() -> new HttpFailure(400, "NOT_A_GITHUB_CONNECTION",
                        "Selected connection is not a GitHub remote repository."));
        return GitHubCoordinates.tryParse(remoteUrl)
                .orElseThrow(() -> new HttpFailure(400, "NOT_A_GITHUB_CONNECTION",
                        "Selected remote repository is not hosted on an allowlisted GitHub host."));
    }

    private ArchitectureQueryApi.Response ok(int status, Object data) {
        return okWithHeaders(status, data, Map.of());
    }

    private ArchitectureQueryApi.Response okWithHeaders(int status, Object data, Map<String, List<String>> headers) {
        byte[] canonical = BoundedJson.encode(data, limits.responseBytes());
        byte[] envelope = BoundedJson.encode(Map.of(
                "apiVersion", ArchitectureQueryApi.VERSION,
                "queryVersion", ArchitectureQuery.VERSION,
                "resultIdentity", ContentDigest.sha256(canonical),
                "data", data), limits.responseBytes());
        return new ArchitectureQueryApi.Response(status, envelope, headers);
    }

    private static int parsePositiveInt(String raw) {
        try {
            int val = Integer.parseInt(raw.strip());
            return Math.max(0, val);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Expected integer parameter");
        }
    }

    private static List<String> stringList(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("Expected string array");
        }
        var out = new ArrayList<String>(list.size());
        for (Object item : list) {
            if (item instanceof String s && !s.isBlank()) out.add(s.strip());
        }
        return List.copyOf(out);
    }

    private ArchitectureQueryApi.Response error(int status, String code, String msg) {
        return errorWithRateLimit(status, code, msg, Optional.empty());
    }

    private ArchitectureQueryApi.Response errorWithRateLimit(int status, String code, String msg,
            Optional<GitHubRestClient.RateLimit> rl) {
        var errObj = new LinkedHashMap<String, Object>();
        errObj.put("code", code);
        errObj.put("message", GitHubTokenBroker.redact(msg == null || msg.isBlank() ? code : msg));
        rl.ifPresent(r -> errObj.put("rateLimit", r.toMap()));
        return new ArchitectureQueryApi.Response(status, BoundedJson.encode(Map.of(
                "apiVersion", ArchitectureQueryApi.VERSION,
                "queryVersion", ArchitectureQuery.VERSION,
                "error", errObj), limits.responseBytes()));
    }

    private Map<String, Object> decode(byte[] body, String contentType) {
        if (contentType == null
                || !Set.of("application/json", "application/json;charset=utf-8")
                        .contains(contentType.toLowerCase(Locale.ROOT).replace(" ", ""))) {
            throw new HttpFailure(415, "UNSUPPORTED_MEDIA_TYPE", "Use application/json with UTF-8.");
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body)).toString();
            return GraphJson.object(GraphJson.read(text,
                    new GraphJson.Limits(limits.requestBytes(), limits.jsonDepth(), limits.jsonValues())));
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("Invalid UTF-8 body");
        }
    }

    private static void checkKeys(Map<String, Object> map, String... allowed) {
        var set = Set.of(allowed);
        for (String key : map.keySet()) {
            if (!set.contains(key)) {
                throw new IllegalArgumentException("Unexpected field: " + key);
            }
        }
    }

    private static String requiredText(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (!(val instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException("Required string field: " + key);
        }
        return s.strip();
    }

    private static Optional<String> optionalText(Map<String, Object> map, String key) {
        if (!map.containsKey(key) || map.get(key) == null) return Optional.empty();
        if (!(map.get(key) instanceof String s)) {
            throw new IllegalArgumentException("Expected string field: " + key);
        }
        String trimmed = s.strip();
        return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
    }

    private static boolean requireBoolean(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (!(val instanceof Boolean b)) {
            throw new IllegalArgumentException("Expected boolean field: " + key);
        }
        return b;
    }

    private static void requireMethod(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new HttpFailure(405, "METHOD_NOT_ALLOWED", "Expected HTTP " + expected);
        }
    }

    private static void requireEmpty(byte[] body) {
        if (body != null && body.length > 0) {
            throw new IllegalArgumentException("Request body not allowed");
        }
    }

    private static Optional<String> queryParam(String rawQuery, String key) {
        if (rawQuery == null || rawQuery.isBlank()) return Optional.empty();
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            if (key.equals(URLDecoder.decode(k, StandardCharsets.UTF_8))) {
                String decoded = URLDecoder.decode(v, StandardCharsets.UTF_8).strip();
                return decoded.isEmpty() ? Optional.empty() : Optional.of(decoded);
            }
        }
        return Optional.empty();
    }

    private static final class HttpFailure extends RuntimeException {
        final int status;
        final String code;

        HttpFailure(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }
}
