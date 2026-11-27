package com.evolution.backend.github;

import com.evolution.analysis.filesystem.GitHostPolicy;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GitHubRestClientTest {

    static GitHubRestClient.Transport loopbackTransport() {
        var http = HttpClient.newHttpClient();
        return (uri, method, headers, body, cancelled) -> {
            if (cancelled.getAsBoolean()) {
                throw new IOException("CANCELLED");
            }
            var reqBuilder = HttpRequest.newBuilder(uri);
            headers.forEach(reqBuilder::header);
            var publisher = body == null || body.length == 0
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body);
            reqBuilder.method(method, publisher);
            try {
                var resp = http.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());
                var map = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
                resp.headers().map().forEach((k, v) -> map.put(k, String.join(", ", v)));
                return new GitHubRestClient.RawResponse(resp.statusCode(), map, resp.body());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("INTERRUPTED", interrupted);
            }
        };
    }

    static void respondJson(HttpExchange ex, int status, String json, Map<String, String> extraHeaders) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        if (extraHeaders != null) {
            extraHeaders.forEach((k, v) -> ex.getResponseHeaders().set(k, v));
        }
        ex.sendResponseHeaders(status, status == 304 ? -1 : bytes.length);
        if (status != 304) {
            ex.getResponseBody().write(bytes);
        }
        ex.close();
    }

    @Test
    void coordinatesParserEnforcesAllowlistAndRejectsSsrfAndUnsafeRefs() {
        var https = GitHubCoordinates.parse("https://github.com/Acme-Org/Demo.Repo.git");
        assertEquals("github.com", https.host());
        assertEquals("Acme-Org", https.owner());
        assertEquals("Demo.Repo", https.repo());
        assertEquals("https://github.com/Acme-Org/Demo.Repo", https.canonicalCloneUrl());

        var ssh = GitHubCoordinates.parse("git@github.com:acme/service.git");
        assertEquals("acme/service", ssh.fullName());

        var shorthand = GitHubCoordinates.parse("acme/service");
        assertEquals("acme/service", shorthand.fullName());

        var prUrl = GitHubCoordinates.parse("https://github.com/acme/service/pull/42");
        assertEquals(Optional.of(42), prUrl.pullNumber());

        var branchUrl = GitHubCoordinates.parse("https://github.com/acme/service/tree/feature/arch-v2");
        assertEquals(Optional.of("feature/arch-v2"), branchUrl.ref());

        // Host allowlist & SSRF rejection
        assertThrows(IllegalArgumentException.class, () -> GitHubCoordinates.parse("https://127.0.0.1/acme/demo"));
        assertThrows(IllegalArgumentException.class, () -> GitHubCoordinates.parse("https://evil.example.com/acme/demo"));
        assertThrows(IllegalArgumentException.class, () -> GitHubCoordinates.parse("http://github.com/acme/demo"));
        assertThrows(IllegalArgumentException.class, () -> GitHubCoordinates.validateBranchName("../etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> GitHubCoordinates.validateBranchName("-flag"));
        assertTrue(GitHubCoordinates.isProtectedDefaultBranch("main", "main"));
        assertTrue(GitHubCoordinates.isProtectedDefaultBranch("master", "develop"));
        assertFalse(GitHubCoordinates.isProtectedDefaultBranch("evo/repair-1234", "main"));
    }

    @Test
    void tokenBrokerEnforcesRamOnlyZeroingRedactionAndApiPathIsolation() throws Exception {
        var env = Map.of("GITHUB_TOKEN", "ghp_envtoken1234567890abcdef");
        try (var broker = new GitHubTokenBroker(env::get, host -> Optional.empty())) {
            // Env fallback works
            var resolvedEnv = broker.resolve("github.com").orElseThrow();
            assertEquals(GitHubTokenBroker.SOURCE_ENV_GITHUB, resolvedEnv.source());

            // Session PAT overrides env & caller char[] is zeroed
            char[] pat = "github_pat_11ABCDEF1234567890_secretvalue".toCharArray();
            broker.setSessionToken(pat, Optional.empty());
            for (char c : pat) {
                assertEquals('\0', c, "Caller char[] must be zeroed immediately");
            }
            var resolvedSession = broker.resolve("github.com").orElseThrow();
            assertEquals(GitHubTokenBroker.SOURCE_SESSION_PAT, resolvedSession.source());
            assertTrue(broker.hasSessionToken());

            // Read-only Git credentials bridge attaches Basic auth for github.com clone URLs, skips /api/v3 and other hosts
            var creds = broker.readOnlyGitCredentials();
            assertTrue(creds.authorization(URI.create("https://github.com/acme/demo.git")).isPresent());
            assertTrue(creds.reference(URI.create("https://github.com/acme/demo.git")).orElseThrow().readOnly());
            assertTrue(creds.authorization(URI.create("https://github.com/api/v3/repos/acme/demo")).isEmpty());
            assertTrue(creds.authorization(URI.create("https://evil.example.com/acme/demo.git")).isEmpty());

            // Secret redaction scrubs all token formats
            String dirty = "Failed with ghp_secret1234567890 and github_pat_1234567890abcdef1234 and Bearer abcdefgh123456 and x-access-token:tok123@github.com";
            String clean = GitHubTokenBroker.redact(dirty);
            assertFalse(clean.contains("ghp_secret"));
            assertFalse(clean.contains("github_pat_"));
            assertFalse(clean.contains("abcdefgh123456"));
            assertFalse(clean.contains("tok123"));

            broker.clearSessionToken();
            assertFalse(broker.hasSessionToken());
        }
    }

    @Test
    void restClientSendsV20221128HeadersCachesEtag304HandlesRateLimitsAndUpsertsPrComment() throws Exception {
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 8);
        var repoHits = new AtomicInteger();
        var capturedHeaders = new ConcurrentHashMap<String, String>();
        var comments = Collections.synchronizedList(new ArrayList<Map<String, Object>>());
        var patchHits = new AtomicInteger();
        var postHits = new AtomicInteger();

        server.createContext("/repos/acme/demo", ex -> {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            capturedHeaders.put("version", String.valueOf(ex.getRequestHeaders().getFirst("X-GitHub-Api-Version")));
            capturedHeaders.put("accept", String.valueOf(ex.getRequestHeaders().getFirst("Accept")));
            capturedHeaders.put("auth", String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));

            if (path.equals("/repos/acme/demo")) {
                repoHits.incrementAndGet();
                if ("\"etag-v1\"".equals(ex.getRequestHeaders().getFirst("If-None-Match"))) {
                    respondJson(ex, 304, "", Map.of("ETag", "\"etag-v1\"", "X-RateLimit-Limit", "5000", "X-RateLimit-Remaining", "4998"));
                    return;
                }
                respondJson(ex, 200,
                        "{\"full_name\":\"acme/demo\",\"default_branch\":\"main\",\"private\":false,\"html_url\":\"https://github.com/acme/demo\",\"description\":\"Demo\",\"language\":\"Java\",\"stargazers_count\":12}",
                        Map.of("ETag", "\"etag-v1\"", "X-RateLimit-Limit", "5000", "X-RateLimit-Remaining", "4999"));
                return;
            }
            if (path.equals("/repos/acme/demo/issues/7/comments") && "GET".equals(method)) {
                var sb = new StringBuilder("[");
                for (int i = 0; i < comments.size(); i++) {
                    if (i > 0) sb.append(',');
                    var c = comments.get(i);
                    String escapedBody = String.valueOf(c.get("body")).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
                    sb.append("{\"id\":").append(c.get("id")).append(",\"html_url\":\"").append(c.get("html_url"))
                            .append("\",\"body\":\"").append(escapedBody).append("\"}");
                }
                sb.append(']');
                respondJson(ex, 200, sb.toString(), Map.of());
                return;
            }
            if (path.equals("/repos/acme/demo/issues/7/comments") && "POST".equals(method)) {
                postHits.incrementAndGet();
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                comments.add(Map.of("id", 901L, "html_url", "https://github.com/acme/demo/pull/7#issuecomment-901", "body", body));
                respondJson(ex, 201, "{\"id\":901,\"html_url\":\"https://github.com/acme/demo/pull/7#issuecomment-901\"}", Map.of());
                return;
            }
            if (path.equals("/repos/acme/demo/issues/comments/901") && "PATCH".equals(method)) {
                patchHits.incrementAndGet();
                respondJson(ex, 200, "{\"id\":901,\"html_url\":\"https://github.com/acme/demo/pull/7#issuecomment-901\"}", Map.of());
                return;
            }
            if (path.equals("/repos/acme/demo/branches")) {
                respondJson(ex, 429, "{\"message\":\"API rate limit exceeded\"}",
                        Map.of("X-RateLimit-Limit", "60", "X-RateLimit-Remaining", "0", "Retry-After", "45"));
                return;
            }
            respondJson(ex, 404, "{\"message\":\"Not Found\"}", Map.of());
        });
        server.start();
        try (var broker = new GitHubTokenBroker(k -> null, h -> Optional.empty())) {
            broker.setSessionToken("ghp_unittesttoken1234567890".toCharArray(), Optional.empty());
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var client = new GitHubRestClient(broker, loopbackTransport(), base);
            var coords = GitHubCoordinates.parse("acme/demo");

            // First call populates ETag cache; second call sends If-None-Match and reuses cached response on 304
            var meta1 = client.repository(coords, () -> false);
            var meta2 = client.repository(coords, () -> false);
            assertEquals("acme/demo", meta1.fullName());
            assertEquals("main", meta1.defaultBranch());
            assertEquals(meta1, meta2);
            assertEquals(2, repoHits.get());
            assertEquals("2022-11-28", capturedHeaders.get("version"));
            assertEquals("application/vnd.github+json", capturedHeaders.get("accept"));
            assertTrue(capturedHeaders.get("auth").startsWith("Bearer ghp_unittest"));
            assertEquals(4998, client.lastRateLimit().orElseThrow().remaining());

            // Rate-limit 429 parsing
            var rateErr = assertThrows(GitHubRestClient.GitHubApiException.class,
                    () -> client.branches(coords, 10, () -> false));
            assertEquals(429, rateErr.httpStatus());
            assertEquals("GITHUB_RATE_LIMITED", rateErr.code());
            assertEquals(OptionalLong.of(45L), rateErr.rateLimit().orElseThrow().retryAfterSeconds());

            // Idempotent PR review comment: first POST creates, second PATCH updates existing marker comment
            var c1 = client.createOrUpdatePrComment(coords, 7, "Initial architecture report", () -> false);
            assertFalse(c1.updated());
            assertEquals(901L, c1.id());
            var c2 = client.createOrUpdatePrComment(coords, 7, "Updated architecture report", () -> false);
            assertTrue(c2.updated());
            assertEquals(1, postHits.get());
            assertEquals(1, patchHits.get());
        } finally {
            server.stop(0);
        }
    }
}
