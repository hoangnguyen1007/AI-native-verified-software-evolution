package com.evolution.backend.github;

import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.filesystem.GitHostPolicy;
import com.evolution.analysis.filesystem.PinnedHttps;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/**
 * Hardened GitHub REST API v2022-11-28 client with DNS-pinned HTTPS transport,
 * strict host allowlist, ETag conditional caching (304), rate-limit handling, and zero token leakage.
 */
public final class GitHubRestClient {
    public static final String API_VERSION = "2022-11-28";
    public static final String USER_AGENT = "ai-native-verified-software-evolution/0.1";
    public static final String COMMENT_MARKER = "<!-- evo-arch-diff:v1 -->";
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final int ETAG_CACHE_SIZE = 256;
    private static final GraphJson.Limits JSON_LIMITS = new GraphJson.Limits(MAX_RESPONSE_BYTES, 64, 500_000);

    @FunctionalInterface
    public interface Transport {
        RawResponse exchange(URI uri, String method, Map<String, String> headers, byte[] body,
                BooleanSupplier cancelled) throws IOException;
    }

    public record RawResponse(int status, Map<String, String> headers, byte[] body) {
        public RawResponse {
            var copy = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
            if (headers != null) {
                copy.putAll(headers);
            }
            headers = Collections.unmodifiableMap(copy);
            body = body == null ? new byte[0] : body.clone();
        }
    }

    public record RateLimit(int limit, int remaining, int used, long resetEpochSeconds,
            OptionalLong retryAfterSeconds) {
        public Map<String, Object> toMap() {
            var map = new LinkedHashMap<String, Object>();
            map.put("limit", limit);
            map.put("remaining", remaining);
            map.put("used", used);
            map.put("resetEpochSeconds", resetEpochSeconds);
            map.put("retryAfterSeconds", retryAfterSeconds.isPresent() ? retryAfterSeconds.getAsLong() : null);
            return map;
        }
    }

    public record UserInfo(String login, String name, String avatarUrl, List<String> scopes) {
        public UserInfo {
            scopes = List.copyOf(scopes == null ? List.of() : scopes);
        }
    }

    public record RepoMetadata(
            String fullName,
            String owner,
            String name,
            String description,
            String defaultBranch,
            boolean isPrivate,
            boolean archived,
            String htmlUrl,
            String cloneUrl,
            int stargazersCount,
            int openIssuesCount,
            String language) {
    }

    public record BranchInfo(String name, String sha, boolean isProtected) {
    }

    public record TagInfo(String name, String sha) {
    }

    public record CommitInfo(
            String sha,
            String shortSha,
            String message,
            String authorName,
            String authorLogin,
            String committedAt,
            String htmlUrl) {
    }

    public record PullRequestInfo(
            int number,
            String title,
            String state,
            boolean draft,
            String htmlUrl,
            String authorLogin,
            String authorAvatarUrl,
            String baseRef,
            String baseSha,
            String headRef,
            String headSha,
            String headRepoFullName,
            boolean isFork,
            String updatedAt,
            int changedFiles,
            int additions,
            int deletions) {
        public PullRequestInfo(int number, String title, String state, boolean draft, String htmlUrl,
                String authorLogin, String baseRef, String baseSha, String headRef, String headSha,
                String headRepoFullName, boolean isFork, String updatedAt, int changedFiles, int additions, int deletions) {
            this(number, title, state, draft, htmlUrl, authorLogin, "", baseRef, baseSha, headRef, headSha,
                    headRepoFullName, isFork, updatedAt, changedFiles, additions, deletions);
        }
    }

    public record PullFileInfo(String filename, String status, int additions, int deletions, int changes, String patch) {
        public PullFileInfo(String filename, String status, int additions, int deletions, int changes) {
            this(filename, status, additions, deletions, changes, "");
        }
    }

    public record OrgInfo(long id, String login, String avatarUrl, String description) {
    }

    public record RepoPermissions(boolean admin, boolean push, boolean pull) {
    }

    public record UserRepoInfo(
            long id,
            String fullName,
            String owner,
            String name,
            String description,
            boolean privateRepo,
            String defaultBranch,
            String language,
            String htmlUrl,
            String cloneUrl,
            String pushedAt,
            int stargazersCount,
            RepoPermissions permissions) {
    }

    public record PullReviewReceipt(long id, String htmlUrl, String state) {
    }

    public record CommitStatusReceipt(long id, String state, String context, String targetUrl, String description) {
    }

    public record CombinedStatusInfo(String state, int totalCount) {
    }

    public record CreatedCheckRun(long id, String name, String status, String conclusion, String htmlUrl) {
    }

    public record SarifUploadReceipt(String id, String url) {
    }

    public record CreatedIssue(int number, String htmlUrl, String title, String state,
            List<String> labels, List<String> assignees) {
        public CreatedIssue {
            labels = List.copyOf(labels == null ? List.of() : labels);
            assignees = List.copyOf(assignees == null ? List.of() : assignees);
        }
    }

    public record CheckRunInfo(long id, String name, String status, String conclusion,
            String detailsUrl, String completedAt) {
    }

    public record CreatedPullRequest(int number, String htmlUrl, String state, boolean draft,
            String title, String headRef, String baseRef) {
    }

    public record CommentReceipt(long id, String htmlUrl, boolean updated) {
    }

    public static final class GitHubApiException extends RuntimeException {
        private final int httpStatus;
        private final String code;
        private final Optional<RateLimit> rateLimit;

        public GitHubApiException(int httpStatus, String code, String message, Optional<RateLimit> rateLimit) {
            super(GitHubTokenBroker.redact(message));
            this.httpStatus = httpStatus;
            this.code = code;
            this.rateLimit = Objects.requireNonNull(rateLimit);
        }

        public int httpStatus() {
            return httpStatus;
        }

        public String code() {
            return code;
        }

        public Optional<RateLimit> rateLimit() {
            return rateLimit;
        }
    }

    private record ETagEntry(String etag, byte[] body, RateLimit rateLimit) {
    }

    private final GitHubTokenBroker tokenBroker;
    private final Transport transport;
    private final Optional<URI> apiBaseOverride;
    private final Set<String> allowedApiHosts;
    private final Map<String, ETagEntry> etagCache = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ETagEntry> eldest) {
                    return size() > ETAG_CACHE_SIZE;
                }
            });
    private static final int MAX_RATE_LIMIT_USERS = 1_024;
    private final Map<String, RateLimit> rateLimitsByUser = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, RateLimit> eldest) {
                    return size() > MAX_RATE_LIMIT_USERS;
                }
            });

    public GitHubRestClient(GitHubTokenBroker tokenBroker, GitHostPolicy hostPolicy) {
        this(tokenBroker, pinnedTransport(Objects.requireNonNull(hostPolicy)), Optional.empty(),
                GitHubCoordinates.allowedApiHosts());
    }

    /** Constructor for controlled offline/loopback testing with explicit base URI and transport. */
    public GitHubRestClient(GitHubTokenBroker tokenBroker, Transport transport, URI apiBaseOverride) {
        this(tokenBroker, transport, Optional.of(Objects.requireNonNull(apiBaseOverride)),
                Set.of(apiBaseOverride.getHost().toLowerCase(Locale.ROOT)));
    }

    private GitHubRestClient(GitHubTokenBroker tokenBroker, Transport transport, Optional<URI> apiBaseOverride,
            Set<String> allowedApiHosts) {
        this.tokenBroker = Objects.requireNonNull(tokenBroker, "tokenBroker");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.apiBaseOverride = Objects.requireNonNull(apiBaseOverride, "apiBaseOverride");
        this.allowedApiHosts = Set.copyOf(allowedApiHosts);
    }

    public static Transport createPinnedTransport(GitHostPolicy hostPolicy) {
        return pinnedTransport(Objects.requireNonNull(hostPolicy, "hostPolicy"));
    }

    public Optional<RateLimit> lastRateLimit() {
        return Optional.ofNullable(rateLimitsByUser.get(com.evolution.backend.auth.RequestContext.currentUserId()));
    }

    public GitHubTokenBroker tokenBroker() {
        return tokenBroker;
    }

    public Optional<UserInfo> currentUser(String host, BooleanSupplier cancelled) {
        var resolved = tokenBroker.resolve(host);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        var resp = call(apiBase(host), host, "/user", "GET", null, true, cancelled);
        var obj = GraphJson.object(parseJson(resp.body()));
        String login = str(obj.get("login"), "");
        String name = str(obj.get("name"), login);
        String avatar = str(obj.get("avatar_url"), "");
        String rawScopes = resp.headers().getOrDefault("X-OAuth-Scopes", "");
        var scopes = rawScopes.isBlank() ? List.<String>of()
                : Arrays.stream(rawScopes.split(",")).map(String::strip).filter(s -> !s.isEmpty()).sorted().toList();
        return Optional.of(new UserInfo(login, name, avatar, scopes));
    }

    public Map<String, Object> authStatus(String host, BooleanSupplier cancelled) {
        String webHost = (host == null || host.isBlank()) ? GitHubCoordinates.DEFAULT_HOST : host.strip().toLowerCase(Locale.ROOT);
        var resolved = tokenBroker.resolve(webHost);
        var status = new LinkedHashMap<String, Object>();
        status.put("host", webHost);
        if (resolved.isEmpty()) {
            status.put("authenticated", false);
            status.put("source", GitHubTokenBroker.SOURCE_NONE);
            status.put("login", null);
            status.put("name", null);
            status.put("scopes", List.of());
            status.put("canPushAndOpenPr", false);
            status.put("rateLimit", lastRateLimit().map(RateLimit::toMap).orElse(null));
            return status;
        }
        status.put("source", resolved.orElseThrow().source());
        try {
            var userOpt = currentUser(webHost, cancelled);
            if (userOpt.isPresent()) {
                var user = userOpt.orElseThrow();
                boolean canWrite = user.scopes().isEmpty()
                        || user.scopes().contains("repo")
                        || user.scopes().contains("public_repo");
                status.put("authenticated", true);
                status.put("login", user.login());
                status.put("name", user.name());
                status.put("scopes", user.scopes());
                status.put("canPushAndOpenPr", canWrite);
            } else {
                status.put("authenticated", false);
                status.put("login", null);
                status.put("name", null);
                status.put("scopes", List.of());
                status.put("canPushAndOpenPr", false);
            }
        } catch (GitHubApiException authFailed) {
            status.put("authenticated", false);
            status.put("login", null);
            status.put("name", null);
            status.put("scopes", List.of());
            status.put("canPushAndOpenPr", false);
            status.put("errorCode", authFailed.code());
        }
        status.put("rateLimit", lastRateLimit().map(RateLimit::toMap).orElse(null));
        return status;
    }

    public RepoMetadata repository(GitHubCoordinates.Coordinates coords, BooleanSupplier cancelled) {
        var resp = call(apiBase(coords.host()), coords.host(),
                "/repos/" + enc(coords.owner()) + "/" + enc(coords.repo()), "GET", null, false, cancelled);
        var obj = GraphJson.object(parseJson(resp.body()));
        var ownerObj = obj.get("owner") instanceof Map<?, ?> m ? GraphJson.object(m) : Map.<String, Object>of();
        return new RepoMetadata(
                str(obj.get("full_name"), coords.fullName()),
                str(ownerObj.get("login"), coords.owner()),
                str(obj.get("name"), coords.repo()),
                str(obj.get("description"), ""),
                str(obj.get("default_branch"), "main"),
                Boolean.TRUE.equals(obj.get("private")),
                Boolean.TRUE.equals(obj.get("archived")),
                str(obj.get("html_url"), coords.htmlUrl()),
                str(obj.get("clone_url"), coords.canonicalCloneUrl() + ".git"),
                num(obj.get("stargazers_count"), 0),
                num(obj.get("open_issues_count"), 0),
                str(obj.get("language"), ""));
    }

    public List<BranchInfo> branches(GitHubCoordinates.Coordinates coords, int perPage, BooleanSupplier cancelled) {
        int limit = Math.max(1, Math.min(100, perPage));
        var resp = call(apiBase(coords.host()), coords.host(),
                "/repos/" + enc(coords.owner()) + "/" + enc(coords.repo()) + "/branches?per_page=" + limit,
                "GET", null, false, cancelled);
        var rows = GraphJson.array(parseJson(resp.body()));
        var out = new ArrayList<BranchInfo>(rows.size());
        for (var item : rows) {
            var obj = GraphJson.object(item);
            var commit = obj.get("commit") instanceof Map<?, ?> m ? GraphJson.object(m) : Map.<String, Object>of();
            out.add(new BranchInfo(
                    str(obj.get("name"), ""),
                    str(commit.get("sha"), ""),
                    Boolean.TRUE.equals(obj.get("protected"))));
        }
        return List.copyOf(out);
    }

    public List<TagInfo> tags(GitHubCoordinates.Coordinates coords, int perPage, BooleanSupplier cancelled) {
        int limit = Math.max(1, Math.min(100, perPage));
        var resp = call(apiBase(coords.host()), coords.host(),
                "/repos/" + enc(coords.owner()) + "/" + enc(coords.repo()) + "/tags?per_page=" + limit,
                "GET", null, false, cancelled);
        var rows = GraphJson.array(parseJson(resp.body()));
        var out = new ArrayList<TagInfo>(rows.size());
        for (var item : rows) {
            var obj = GraphJson.object(item);
            var commit = obj.get("commit") instanceof Map<?, ?> m ? GraphJson.object(m) : Map.<String, Object>of();
            out.add(new TagInfo(str(obj.get("name"), ""), str(commit.get("sha"), "")));
        }
        return List.copyOf(out);
    }

    public List<CommitInfo> commits(GitHubCoordinates.Coordinates coords, Optional<String> sha, int perPage,
            BooleanSupplier cancelled) {
        int limit = Math.max(1, Math.min(50, perPage));
        StringBuilder path = new StringBuilder("/repos/")
                .append(enc(coords.owner())).append('/').append(enc(coords.repo()))
                .append("/commits?per_page=").append(limit);
        if (sha != null && sha.isPresent() && !sha.orElseThrow().isBlank()) {
            String validSha = GitHubCoordinates.validateRef(sha.orElseThrow());
            path.append("&sha=").append(enc(validSha));
        }
        var resp = call(apiBase(coords.host()), coords.host(), path.toString(), "GET", null, false, cancelled);
        var rows = GraphJson.array(parseJson(resp.body()));
        var out = new ArrayList<CommitInfo>(rows.size());
        for (var item : rows) {
            var obj = GraphJson.object(item);
            String fullSha = str(obj.get("sha"), "");
            var commitObj = obj.get("commit") instanceof Map<?, ?> m ? GraphJson.object(m) : Map.<String, Object>of();
            var authorObj = commitObj.get("author") instanceof Map<?, ?> m ? GraphJson.object(m) : Map.<String, Object>of();
            var ghAuthor = obj.get("author") instanceof Map<?, ?> m ? GraphJson.object(m) : Map.<String, Object>of();
            String msg = str(commitObj.get("message"), "");
            int nl = msg.indexOf('\n');
            String subject = nl >= 0 ? msg.substring(0, nl).strip() : msg.strip();
            out.add(new CommitInfo(
                    fullSha,
                    fullSha.length() > 7 ? fullSha.substring(0, 7) : fullSha,
                    subject,
                    str(authorObj.get("name"), ""),
                    str(ghAuthor.get("login"), ""),
                    str(authorObj.get("date"), ""),
                    str(obj.get("html_url"), "")));
        }
        return List.copyOf(out);
    }

    public List<PullRequestInfo> pulls(GitHubCoordinates.Coordinates coords, String state, int perPage,
            BooleanSupplier cancelled) {
        String safeState = Set.of("open", "closed", "all").contains(state) ? state : "open";
        int limit = Math.max(1, Math.min(50, perPage));
        var resp = call(apiBase(coords.host()), coords.host(),
                "/repos/" + enc(coords.owner()) + "/" + enc(coords.repo())
                        + "/pulls?state=" + safeState + "&sort=updated&direction=desc&per_page=" + limit,
                "GET", null, false, cancelled);
        var rows = GraphJson.array(parseJson(resp.body()));
        var out = new ArrayList<PullRequestInfo>(rows.size());
        for (var item : rows) {
            out.add(parsePull(GraphJson.object(item), coords.fullName()));
        }
        return List.copyOf(out);
    }

    public PullRequestInfo pull(GitHubCoordinates.Coordinates coords, int number, BooleanSupplier cancelled) {
        int valid = GitHubCoordinates.validatePullNumber(number);
        var resp = call(apiBase(coords.host()), coords.host(),
                "/repos/" + enc(coords.owner()) + "/" + enc(coords.repo()) + "/pulls/" + valid,
                "GET", null, false, cancelled);
        return parsePull(GraphJson.object(parseJson(resp.body())), coords.fullName());
    }

    public List<PullFileInfo> pullFiles(GitHubCoordinates.Coordinates coords, int number, int perPage,
            BooleanSupplier cancelled) {
        int valid = GitHubCoordinates.validatePullNumber(number);
        int limit = Math.max(1, Math.min(100, perPage));
        var resp = call(apiBase(coords.host()), coords.host(),
                "/repos/" + enc(coords.owner()) + "/" + enc(coords.repo()) + "/pulls/" + valid + "/files?per_page=" + limit,
                "GET", null, false, cancelled);
        var rows = GraphJson.array(parseJson(resp.body()));
        var out = new ArrayList<PullFileInfo>(rows.size());
        for (var item : rows) {
            var obj = GraphJson.object(item);
            out.add(new PullFileInfo(
                    str(obj.get("filename"), ""),
                    str(obj.get("status"), "modified"),
                    num(obj.get("additions"), 0),
                    num(obj.get("deletions"), 0),
                    num(obj.get("changes"), 0),
                    str(obj.get("patch"), "")));
        }
        return List.copyOf(out);
    }

    public List<OrgInfo> userOrgs(String host, int perPage, BooleanSupplier cancelled) {
        String webHost = (host == null || host.isBlank()) ? GitHubCoordinates.DEFAULT_HOST : host.strip().toLowerCase(Locale.ROOT);
        int limit = Math.max(1, Math.min(100, perPage));
        var resp = call(apiBase(webHost), webHost, "/user/orgs?per_page=" + limit, "GET", null, true, cancelled);
        var rows = GraphJson.array(parseJson(resp.body()));
        var out = new ArrayList<OrgInfo>(rows.size());
        for (var item : rows) {
            var obj = GraphJson.object(item);
            out.add(new OrgInfo(
                    longNum(obj.get("id"), 0L),
                    str(obj.get("login"), ""),
                    str(obj.get("avatar_url"), ""),
                    str(obj.get("description"), "")));
        }
        return List.copyOf(out);
    }

    public List<UserRepoInfo> userRepos(
            String host,
            Optional<String> org,
            String visibility,
            String sort,
            int page,
            int perPage,
            BooleanSupplier cancelled) {
        String webHost = (host == null || host.isBlank()) ? GitHubCoordinates.DEFAULT_HOST : host.strip().toLowerCase(Locale.ROOT);
        int safePage = Math.max(1, Math.min(100, page));
        int limit = Math.max(1, Math.min(100, perPage));
        String safeSort = Set.of("updated", "pushed", "full_name", "created").contains(sort) ? sort : "updated";
        String path;
        if (org != null && org.isPresent() && !org.orElseThrow().isBlank()) {
            String orgName = org.orElseThrow().strip();
            if (!orgName.matches("[A-Za-z0-9._-]{1,100}")) {
                throw new IllegalArgumentException("Invalid GitHub organization name");
            }
            String type = Set.of("all", "public", "private", "member").contains(visibility) ? visibility : "all";
            path = "/orgs/" + enc(orgName) + "/repos?type=" + type + "&sort=" + safeSort
                    + "&per_page=" + limit + "&page=" + safePage;
        } else {
            String vis = Set.of("all", "public", "private").contains(visibility) ? visibility : "all";
            path = "/user/repos?visibility=" + vis
                    + "&affiliation=owner,collaborator,organization_member&sort=" + safeSort
                    + "&per_page=" + limit + "&page=" + safePage;
        }
        var resp = call(apiBase(webHost), webHost, path, "GET", null, true, cancelled);
        var rows = GraphJson.array(parseJson(resp.body()));
        var out = new ArrayList<UserRepoInfo>(rows.size());
        for (var item : rows) {
            var obj = GraphJson.object(item);
            var ownerObj = obj.get("owner") instanceof Map<?, ?> m ? GraphJson.object(m) : Map.<String, Object>of();
            var permObj = obj.get("permissions") instanceof Map<?, ?> m ? GraphJson.object(m) : Map.<String, Object>of();
            String ownerLogin = str(ownerObj.get("login"), "");
            String repoName = str(obj.get("name"), "");
            String fullName = str(obj.get("full_name"), ownerLogin + "/" + repoName);
            out.add(new UserRepoInfo(
                    longNum(obj.get("id"), 0L),
                    fullName,
                    ownerLogin,
                    repoName,
                    str(obj.get("description"), ""),
                    Boolean.TRUE.equals(obj.get("private")),
                    str(obj.get("default_branch"), "main"),
                    str(obj.get("language"), ""),
                    str(obj.get("html_url"), "https://" + webHost + "/" + fullName),
                    str(obj.get("clone_url"), "https://" + webHost + "/" + fullName + ".git"),
                    str(obj.get("pushed_at"), str(obj.get("updated_at"), "")),
                    num(obj.get("stargazers_count"), 0),
                    new RepoPermissions(
                            Boolean.TRUE.equals(permObj.get("admin")),
                            Boolean.TRUE.equals(permObj.get("push")),
                            Boolean.TRUE.equals(permObj.get("pull")) || !permObj.containsKey("pull"))));
        }
        return List.copyOf(out);
    }

    public CombinedStatusInfo combinedStatus(GitHubCoordinates.Coordinates coords, String ref,
            BooleanSupplier cancelled) {
        String validRef = GitHubCoordinates.validateRef(ref);
        var resp = call(apiBase(coords.host()), coords.host(),
                "/repos/" + enc(coords.owner()) + "/" + enc(coords.repo()) + "/commits/" + enc(validRef) + "/status",
                "GET", null, false, cancelled);
        var obj = GraphJson.object(parseJson(resp.body()));
        return new CombinedStatusInfo(str(obj.get("state"), "pending"), num(obj.get("total_count"), 0));
    }

    public PullReviewReceipt createPullReview(
            GitHubCoordinates.Coordinates coords,
            int pullNumber,
            String commitId,
            String body,
            String event,
            List<Map<String, Object>> comments,
            BooleanSupplier cancelled) {
        int valid = GitHubCoordinates.validatePullNumber(pullNumber);
        String safeEvent = Set.of("COMMENT", "REQUEST_CHANGES", "APPROVE").contains(event) ? event : "COMMENT";
        var payload = new LinkedHashMap<String, Object>();
        if (commitId != null && !commitId.isBlank()) {
            payload.put("commit_id", GitHubCoordinates.validateRef(commitId));
        }
        payload.put("body", body == null ? "" : body);
        payload.put("event", safeEvent);
        if (comments != null && !comments.isEmpty()) {
            payload.put("comments", comments);
        }
        var resp = call(apiBase(coords.host()), coords.host(),
                "/repos/" + enc(coords.owner()) + "/" + enc(coords.repo()) + "/pulls/" + valid + "/reviews",
                "POST", payload, true, cancelled);
        var obj = GraphJson.object(parseJson(resp.body()));
        return new PullReviewReceipt(
                longNum(obj.get("id"), 0L),
                str(obj.get("html_url"), ""),
                str(obj.get("state"), safeEvent));
    }

    public CommitStatusReceipt createCommitStatus(
            GitHubCoordinates.Coordinates coords,
            String sha,
            String state,
            String context,
            String description,
            Optional<String> targetUrl,
            BooleanSupplier cancelled) {
        String validSha = GitHubCoordinates.validateRef(sha);
        if (!Set.of("error", "failure", "pending", "success").contains(state)) {
            throw new IllegalArgumentException("Invalid commit status state: " + state);
        }
        String safeDesc = description == null ? "" : description.strip();
        if (safeDesc.length() > 140) {
            safeDesc = safeDesc.substring(0, 137) + "...";
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("state", state);
        payload.put("context", (context == null || context.isBlank()) ? "evo/architecture-gate" : context.strip());
        payload.put("description", safeDesc);
        if (targetUrl != null && targetUrl.isPresent() && !targetUrl.orElseThrow().isBlank()) {
            payload.put("target_url", targetUrl.orElseThrow().strip());
        }
        var resp = call(apiBase(coords.host()), coords.host(),
                "/repos/" + enc(coords.owner()) + "/" + enc(coords.repo()) + "/statuses/" + enc(validSha),
                "POST", payload, true, cancelled);
        var obj = GraphJson.object(parseJson(resp.body()));
        return new CommitStatusReceipt(
                longNum(obj.get("id"), 0L),
                str(obj.get("state"), state),
                str(obj.get("context"), String.valueOf(payload.get("context"))),
                str(obj.get("target_url"), ""),
                str(obj.get("description"), safeDesc));
    }

    public CreatedCheckRun createCheckRun(
            GitHubCoordinates.Coordinates coords,
            String name,
            String headSha,
            String status,
            String conclusion,
            String title,
            String summary,
            List<Map<String, Object>> annotations,
            BooleanSupplier cancelled) {
        String validSha = GitHubCoordinates.validateRef(headSha);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("name", (name == null || name.isBlank()) ? "Evo Architecture Quality Gate" : name.strip());
        payload.put("head_sha", validSha);
        payload.put("status", Set.of("queued", "in_progress", "completed").contains(status) ? status : "completed");
        if ("completed".equals(payload.get("status"))) {
            String safeConclusion = Set.of("action_required", "cancelled", "failure", "neutral", "success", "skipped", "stale", "timed_out")
                    .contains(conclusion) ? conclusion : "neutral";
            payload.put("conclusion", safeConclusion);
        }
        var output = new LinkedHashMap<String, Object>();
        output.put("title", title == null || title.isBlank() ? "Evo Architecture Gate" : title.strip());
        output.put("summary", summary == null ? "" : summary);
        if (annotations != null && !annotations.isEmpty()) {
            output.put("annotations", annotations.size() > 50 ? annotations.subList(0, 50) : annotations);
        }
        payload.put("output", output);
        try {
            var resp = call(apiBase(coords.host()), coords.host(),
                    "/repos/" + enc(coords.owner()) + "/" + enc(coords.repo()) + "/check-runs",
                    "POST", payload, true, cancelled);
            var obj = GraphJson.object(parseJson(resp.body()));
            return new CreatedCheckRun(
                    longNum(obj.get("id"), 0L),
                    str(obj.get("name"), String.valueOf(payload.get("name"))),
                    str(obj.get("status"), "completed"),
                    str(obj.get("conclusion"), ""),
                    str(obj.get("html_url"), ""));
        } catch (GitHubApiException forbidden) {
            if (forbidden.httpStatus() == 403) {
                throw new GitHubApiException(403, "GITHUB_APP_TOKEN_REQUIRED",
                        "GitHub Check Runs API requires a GitHub App installation/user token with checks:write permission. Use Commit Statuses for OAuth/PAT tokens.",
                        forbidden.rateLimit());
            }
            throw forbidden;
        }
    }

    public SarifUploadReceipt uploadSarif(
            GitHubCoordinates.Coordinates coords,
            String commitSha,
            String ref,
            String gzipBase64Sarif,
            String toolName,
            BooleanSupplier cancelled) {
        String validSha = GitHubCoordinates.validateRef(commitSha);
        String validRef = GitHubCoordinates.validateRef(ref);
        if (gzipBase64Sarif == null || gzipBase64Sarif.isBlank()) {
            throw new IllegalArgumentException("SARIF payload is empty");
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("commit_sha", validSha);
        payload.put("ref", validRef);
        payload.put("sarif", gzipBase64Sarif);
        if (toolName != null && !toolName.isBlank()) {
            payload.put("tool_name", toolName.strip());
        }
        var resp = call(apiBase(coords.host()), coords.host(),
                "/repos/" + enc(coords.owner()) + "/" + enc(coords.repo()) + "/code-scanning/sarifs",
                "POST", payload, true, cancelled);
        var obj = GraphJson.object(parseJson(resp.body()));
        return new SarifUploadReceipt(str(obj.get("id"), ""), str(obj.get("url"), ""));
    }

    public CreatedIssue createIssue(
            GitHubCoordinates.Coordinates coords,
            String title,
            String body,
            List<String> labels,
            List<String> assignees,
            BooleanSupplier cancelled) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Issue title is required");
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("title", title.strip());
        payload.put("body", body == null ? "" : body);
        if (labels != null && !labels.isEmpty()) {
            payload.put("labels", labels);
        }
        if (assignees != null && !assignees.isEmpty()) {
            payload.put("assignees", assignees);
        }
        var resp = call(apiBase(coords.host()), coords.host(),
                "/repos/" + enc(coords.owner()) + "/" + enc(coords.repo()) + "/issues",
                "POST", payload, true, cancelled);
        var obj = GraphJson.object(parseJson(resp.body()));
        var returnedLabels = new ArrayList<String>();
        if (obj.get("labels") instanceof List<?> rawLabels) {
            for (Object l : rawLabels) {
                if (l instanceof Map<?, ?> m) returnedLabels.add(str(GraphJson.object(m).get("name"), ""));
                else if (l instanceof String s) returnedLabels.add(s);
            }
        }
        var returnedAssignees = new ArrayList<String>();
        if (obj.get("assignees") instanceof List<?> rawAssignees) {
            for (Object a : rawAssignees) {
                if (a instanceof Map<?, ?> m) returnedAssignees.add(str(GraphJson.object(m).get("login"), ""));
                else if (a instanceof String s) returnedAssignees.add(s);
            }
        }
        return new CreatedIssue(
                num(obj.get("number"), 0),
                str(obj.get("html_url"), ""),
                str(obj.get("title"), title.strip()),
                str(obj.get("state"), "open"),
                returnedLabels,
                returnedAssignees);
    }

    public List<CheckRunInfo> checkRuns(GitHubCoordinates.Coordinates coords, String ref, BooleanSupplier cancelled) {
        String validRef = GitHubCoordinates.validateRef(ref);
        var resp = call(apiBase(coords.host()), coords.host(),
                "/repos/" + enc(coords.owner()) + "/" + enc(coords.repo()) + "/commits/" + enc(validRef) + "/check-runs?per_page=50",
                "GET", null, false, cancelled);
        var obj = GraphJson.object(parseJson(resp.body()));
        var rows = obj.get("check_runs") instanceof List<?> list ? list : List.of();
        var out = new ArrayList<CheckRunInfo>(rows.size());
        for (var item : rows) {
            var r = GraphJson.object(item);
            out.add(new CheckRunInfo(
                    longNum(r.get("id"), 0L),
                    str(r.get("name"), ""),
                    str(r.get("status"), ""),
                    str(r.get("conclusion"), ""),
                    str(r.get("details_url"), ""),
                    str(r.get("completed_at"), "")));
        }
        return List.copyOf(out);
    }

    public CreatedPullRequest createPullRequest(
            GitHubCoordinates.Coordinates coords,
            String title,
            String body,
            String head,
            String base,
            boolean draft,
            BooleanSupplier cancelled) {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(body, "body");
        GitHubCoordinates.validateBranchName(head);
        GitHubCoordinates.validateBranchName(base);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("title", title);
        payload.put("body", body);
        payload.put("head", head);
        payload.put("base", base);
        payload.put("draft", draft);
        var resp = call(apiBase(coords.host()), coords.host(),
                "/repos/" + enc(coords.owner()) + "/" + enc(coords.repo()) + "/pulls",
                "POST", payload, true, cancelled);
        var obj = GraphJson.object(parseJson(resp.body()));
        return new CreatedPullRequest(
                num(obj.get("number"), 0),
                str(obj.get("html_url"), ""),
                str(obj.get("state"), "open"),
                Boolean.TRUE.equals(obj.get("draft")),
                str(obj.get("title"), title),
                head,
                base);
    }

    /**
     * Posts or updates an idempotent PR review comment identified by {@link #COMMENT_MARKER}.
     */
    public CommentReceipt createOrUpdatePrComment(
            GitHubCoordinates.Coordinates coords,
            int pullNumber,
            String markdownBody,
            BooleanSupplier cancelled) {
        int valid = GitHubCoordinates.validatePullNumber(pullNumber);
        String bodyWithMarker = markdownBody.contains(COMMENT_MARKER)
                ? markdownBody
                : COMMENT_MARKER + "\n" + markdownBody;
        long existingId = -1L;
        try {
            var listResp = call(apiBase(coords.host()), coords.host(),
                    "/repos/" + enc(coords.owner()) + "/" + enc(coords.repo()) + "/issues/" + valid + "/comments?per_page=50",
                    "GET", null, true, cancelled);
            for (var item : GraphJson.array(parseJson(listResp.body()))) {
                var obj = GraphJson.object(item);
                if (str(obj.get("body"), "").contains(COMMENT_MARKER)) {
                    existingId = longNum(obj.get("id"), -1L);
                    break;
                }
            }
        } catch (GitHubApiException ignored) {
            existingId = -1L;
        }
        var payload = Map.<String, Object>of("body", bodyWithMarker);
        if (existingId > 0L) {
            var updated = call(apiBase(coords.host()), coords.host(),
                    "/repos/" + enc(coords.owner()) + "/" + enc(coords.repo()) + "/issues/comments/" + existingId,
                    "PATCH", payload, true, cancelled);
            var obj = GraphJson.object(parseJson(updated.body()));
            return new CommentReceipt(longNum(obj.get("id"), existingId), str(obj.get("html_url"), ""), true);
        }
        var created = call(apiBase(coords.host()), coords.host(),
                "/repos/" + enc(coords.owner()) + "/" + enc(coords.repo()) + "/issues/" + valid + "/comments",
                "POST", payload, true, cancelled);
        var obj = GraphJson.object(parseJson(created.body()));
        return new CommentReceipt(longNum(obj.get("id"), 0L), str(obj.get("html_url"), ""), false);
    }

    private URI apiBase(String webHost) {
        if (apiBaseOverride.isPresent()) {
            return apiBaseOverride.orElseThrow();
        }
        String lower = (webHost == null || webHost.isBlank()) ? GitHubCoordinates.DEFAULT_HOST : webHost.toLowerCase(Locale.ROOT);
        return GitHubCoordinates.DEFAULT_HOST.equals(lower)
                ? URI.create("https://" + GitHubCoordinates.DEFAULT_API_HOST)
                : URI.create("https://" + lower + "/api/v3");
    }

    private RawResponse call(URI base, String webHost, String pathAndQuery, String method,
            Object jsonPayload, boolean requireAuth, BooleanSupplier cancelled) {
        if (cancelled != null && cancelled.getAsBoolean()) {
            throw new GitHubApiException(409, "REQUEST_CANCELLED", "Request cancelled", Optional.empty());
        }
        String baseText = base.toASCIIString().replaceFirst("/+$", "");
        URI uri = URI.create(baseText + pathAndQuery);
        String targetHost = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!allowedApiHosts.contains(targetHost)) {
            throw new GitHubApiException(403, "GITHUB_HOST_DENIED", "Target host is not on GitHub allowlist", Optional.empty());
        }
        var tokenOpt = tokenBroker.resolve(webHost);
        if (requireAuth && tokenOpt.isEmpty()) {
            throw new GitHubApiException(401, "GITHUB_AUTH_REQUIRED",
                    "GitHub authentication is required for this operation.", Optional.empty());
        }

        var headers = new LinkedHashMap<String, String>();
        headers.put("Accept", "application/vnd.github+json");
        headers.put("X-GitHub-Api-Version", API_VERSION);
        headers.put("User-Agent", USER_AGENT);
        tokenOpt.ifPresent(tok -> headers.put("Authorization", "Bearer " + tok.token()));

        String cacheKey = null;
        ETagEntry cached = null;
        if ("GET".equals(method)) {
            String tenantKey = com.evolution.backend.auth.RequestContext.currentUserId();
            cacheKey = tenantKey + "|" + uri.toASCIIString() + "|" + (tokenOpt.isPresent() ? tokenOpt.orElseThrow().source() : "anon");
            cached = etagCache.get(cacheKey);
            if (cached != null && cached.etag() != null && !cached.etag().isBlank()) {
                headers.put("If-None-Match", cached.etag());
            }
        }

        byte[] bodyBytes = new byte[0];
        if (jsonPayload != null) {
            headers.put("Content-Type", "application/json; charset=utf-8");
            bodyBytes = CanonicalJson.write(jsonPayload).getBytes(StandardCharsets.UTF_8);
        }

        RawResponse response;
        try {
            response = transport.exchange(uri, method, headers, bodyBytes,
                    cancelled == null ? () -> false : cancelled);
        } catch (IOException io) {
            throw new GitHubApiException(502, "GITHUB_NETWORK_UNAVAILABLE",
                    "GitHub API request failed: " + GitHubTokenBroker.redact(io.getMessage()), Optional.empty());
        }

        var rl = parseRateLimit(response.headers());
        rl.ifPresent(r -> rateLimitsByUser.put(com.evolution.backend.auth.RequestContext.currentUserId(), r));

        if (response.status() == 304 && cached != null) {
            return new RawResponse(200, response.headers(), cached.body());
        }
        if (response.status() >= 200 && response.status() < 300) {
            if ("GET".equals(method) && cacheKey != null) {
                String etag = response.headers().get("ETag");
                if (etag != null && !etag.isBlank()) {
                    etagCache.put(cacheKey, new ETagEntry(etag, response.body(), rl.orElse(null)));
                }
            }
            return response;
        }
        throw mapError(response, rl);
    }

    private static GitHubApiException mapError(RawResponse response, Optional<RateLimit> rl) {
        int status = response.status();
        String apiMessage = extractGitHubMessage(response.body());
        if (status == 401) {
            return new GitHubApiException(401, "GITHUB_AUTH_REQUIRED",
                    apiMessage.isEmpty() ? "GitHub authentication failed or token expired." : apiMessage, rl);
        }
        if (status == 429 || (status == 403 && rl.filter(r -> r.remaining() == 0 || r.retryAfterSeconds().isPresent()).isPresent())) {
            return new GitHubApiException(429, "GITHUB_RATE_LIMITED",
                    apiMessage.isEmpty() ? "GitHub API rate limit exceeded." : apiMessage, rl);
        }
        if (status == 403) {
            return new GitHubApiException(403, "GITHUB_FORBIDDEN",
                    apiMessage.isEmpty() ? "GitHub denied access to this repository or action." : apiMessage, rl);
        }
        if (status == 404) {
            return new GitHubApiException(404, "GITHUB_NOT_FOUND",
                    apiMessage.isEmpty() ? "GitHub repository, ref, or pull request not found." : apiMessage, rl);
        }
        if (status == 422) {
            return new GitHubApiException(422, "GITHUB_VALIDATION_FAILED",
                    apiMessage.isEmpty() ? "GitHub rejected the request parameters." : apiMessage, rl);
        }
        return new GitHubApiException(502, "GITHUB_API_ERROR",
                apiMessage.isEmpty() ? "GitHub API returned HTTP " + status : apiMessage, rl);
    }

    private static String extractGitHubMessage(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        try {
            var obj = GraphJson.object(GraphJson.read(new String(body, StandardCharsets.UTF_8), JSON_LIMITS));
            return GitHubTokenBroker.redact(str(obj.get("message"), ""));
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static Optional<RateLimit> parseRateLimit(Map<String, String> headers) {
        String limit = headers.get("X-RateLimit-Limit");
        String remaining = headers.get("X-RateLimit-Remaining");
        String used = headers.get("X-RateLimit-Used");
        String reset = headers.get("X-RateLimit-Reset");
        String retryAfter = headers.get("Retry-After");
        if (limit == null && remaining == null && retryAfter == null) {
            return Optional.empty();
        }
        return Optional.of(new RateLimit(
                parseInt(limit, -1),
                parseInt(remaining, -1),
                parseInt(used, -1),
                parseLong(reset, 0L),
                retryAfter != null && !retryAfter.isBlank()
                        ? OptionalLong.of(parseLong(retryAfter, 60L))
                        : OptionalLong.empty()));
    }

    private static PullRequestInfo parsePull(Map<String, Object> obj, String repoFullName) {
        var userObj = obj.get("user") instanceof Map<?, ?> m ? GraphJson.object(m) : Map.<String, Object>of();
        var baseObj = obj.get("base") instanceof Map<?, ?> m ? GraphJson.object(m) : Map.<String, Object>of();
        var headObj = obj.get("head") instanceof Map<?, ?> m ? GraphJson.object(m) : Map.<String, Object>of();
        var headRepo = headObj.get("repo") instanceof Map<?, ?> m ? GraphJson.object(m) : Map.<String, Object>of();
        String headFullName = str(headRepo.get("full_name"), repoFullName);
        boolean isFork = !headFullName.equalsIgnoreCase(repoFullName);
        return new PullRequestInfo(
                num(obj.get("number"), 0),
                str(obj.get("title"), ""),
                str(obj.get("state"), "open"),
                Boolean.TRUE.equals(obj.get("draft")),
                str(obj.get("html_url"), ""),
                str(userObj.get("login"), ""),
                str(userObj.get("avatar_url"), ""),
                str(baseObj.get("ref"), ""),
                str(baseObj.get("sha"), ""),
                str(headObj.get("ref"), ""),
                str(headObj.get("sha"), ""),
                headFullName,
                isFork,
                str(obj.get("updated_at"), ""),
                num(obj.get("changed_files"), 0),
                num(obj.get("additions"), 0),
                num(obj.get("deletions"), 0));
    }

    private static Object parseJson(byte[] bytes) {
        try {
            return GraphJson.read(new String(bytes, StandardCharsets.UTF_8), JSON_LIMITS);
        } catch (IllegalArgumentException invalid) {
            throw new GitHubApiException(502, "GITHUB_INVALID_RESPONSE", "GitHub returned invalid JSON.", Optional.empty());
        }
    }

    private static Transport pinnedTransport(GitHostPolicy policy) {
        var pinned = new PinnedHttps(policy);
        return (uri, method, headers, body, cancelled) -> {
            if ("PATCH".equals(method)) {
                return exchangePinnedPatch(policy, uri, headers, body, cancelled);
            }
            try (var resp = pinned.exchange(uri, method, headers, body, cancelled)) {
                byte[] payload = resp.body().readNBytes(MAX_RESPONSE_BYTES + 1);
                if (payload.length > MAX_RESPONSE_BYTES) {
                    throw new IOException("GITHUB_RESPONSE_TOO_LARGE");
                }
                return new RawResponse(resp.status(), resp.headers(), payload);
            }
        };
    }

    /** DNS-pinned TLS exchange for PATCH (updating existing PR review comments) without redirects. */
    private static RawResponse exchangePinnedPatch(GitHostPolicy policy, URI uri, Map<String, String> headers,
            byte[] body, BooleanSupplier cancelled) throws IOException {
        if (!"https".equals(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getFragment() != null) {
            throw new IOException("ENDPOINT_DENIED");
        }
        var endpoint = policy.endpoint(uri);
        int port = uri.getPort() == -1 ? 443 : uri.getPort();
        Socket tcp = new Socket();
        try {
            IOException lastErr = null;
            boolean connected = false;
            for (var address : endpoint.addresses()) {
                try {
                    tcp.connect(new InetSocketAddress(address, port), 30_000);
                    connected = true;
                    break;
                } catch (IOException failed) {
                    lastErr = failed;
                    tcp.close();
                    tcp = new Socket();
                }
            }
            if (!connected) {
                throw new IOException("GITHUB_CONNECTION_UNAVAILABLE", lastErr);
            }
            tcp.setSoTimeout(30_000);
            var sslCtx = SSLContext.getDefault();
            try (var socket = (SSLSocket) sslCtx.getSocketFactory().createSocket(tcp, uri.getHost(), port, true)) {
                var params = socket.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                if (!uri.getHost().startsWith("[") && !uri.getHost().matches("[0-9.]+")) {
                    params.setServerNames(List.of(new SNIHostName(uri.getHost())));
                }
                socket.setSSLParameters(params);
                socket.startHandshake();

                String path = uri.getRawPath();
                if (path == null || path.isEmpty()) path = "/";
                if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
                var req = new StringBuilder("PATCH " + path + " HTTP/1.1\r\nHost: " + uri.getHost()
                        + (port == 443 ? "" : ":" + port)
                        + "\r\nConnection: close\r\nAccept-Encoding: identity\r\nContent-Length: " + body.length + "\r\n");
                for (var h : headers.entrySet()) {
                    if (h.getValue().indexOf('\r') >= 0 || h.getValue().indexOf('\n') >= 0) {
                        throw new IOException("HTTP_HEADER_DENIED");
                    }
                    req.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
                }
                req.append("\r\n");
                var out = socket.getOutputStream();
                out.write(req.toString().getBytes(StandardCharsets.US_ASCII));
                out.write(body);
                out.flush();

                var in = new BufferedInputStream(socket.getInputStream(), 65536);
                String statusLine = readHeaderLine(in);
                if (!statusLine.matches("HTTP/1\\.[01] [1-5][0-9][0-9](?: .*)?")) {
                    throw new IOException("HTTP_STATUS_INVALID");
                }
                int code = Integer.parseInt(statusLine.substring(9, 12));
                var respHeaders = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
                String line;
                while (!(line = readHeaderLine(in)).isEmpty()) {
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        String k = line.substring(0, colon).trim();
                        String v = line.substring(colon + 1).strip();
                        respHeaders.merge(k, v, (a, b) -> a + ", " + b);
                    }
                }
                if (cancelled.getAsBoolean()) {
                    throw new IOException("CANCELLED_CHECKPOINT");
                }
                byte[] respBody = "chunked".equalsIgnoreCase(respHeaders.get("Transfer-Encoding"))
                        ? readChunkedBody(in)
                        : in.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (respBody.length > MAX_RESPONSE_BYTES) {
                    throw new IOException("GITHUB_RESPONSE_TOO_LARGE");
                }
                return new RawResponse(code, respHeaders, respBody);
            }
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        } finally {
            try { tcp.close(); } catch (IOException ignored) {}
        }
    }

    private static String readHeaderLine(InputStream in) throws IOException {
        var buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                byte[] arr = buf.toByteArray();
                int len = arr.length > 0 && arr[arr.length - 1] == '\r' ? arr.length - 1 : arr.length;
                return new String(arr, 0, len, StandardCharsets.US_ASCII);
            }
            if (buf.size() >= 8192) throw new IOException("HTTP_LINE_OVERSIZE");
            buf.write(b);
        }
        throw new EOFException();
    }

    private static byte[] readChunkedBody(InputStream in) throws IOException {
        var out = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readHeaderLine(in).split(";", 2)[0].strip();
            int chunkSize = Integer.parseInt(sizeLine, 16);
            if (chunkSize == 0) {
                while (!readHeaderLine(in).isEmpty()) { /* drain trailers */ }
                return out.toByteArray();
            }
            if (out.size() + chunkSize > MAX_RESPONSE_BYTES) {
                throw new IOException("GITHUB_RESPONSE_TOO_LARGE");
            }
            byte[] buf = in.readNBytes(chunkSize);
            if (buf.length != chunkSize) throw new EOFException();
            out.write(buf);
            readHeaderLine(in); // trailing CRLF
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String str(Object value, String fallback) {
        return value instanceof String s ? s : fallback;
    }

    private static int num(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        return fallback;
    }

    private static long longNum(Object value, long fallback) {
        if (value instanceof Number n) return n.longValue();
        return fallback;
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return Integer.parseInt(value.strip()); } catch (NumberFormatException ignored) { return fallback; }
    }

    private static long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return Long.parseLong(value.strip()); } catch (NumberFormatException ignored) { return fallback; }
    }
}
