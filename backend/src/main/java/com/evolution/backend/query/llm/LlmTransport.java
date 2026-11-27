package com.evolution.backend.query.llm;

import com.evolution.analysis.graph.GraphJson;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Shared hardened HTTP transport, cancellation-aware streaming, exponential backoff retries,
 * sanitized error extraction, reasoning-tag stripping, and preamble-safe JSON unwrapping.
 */
public final class LlmTransport {
    public static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int MAX_ERROR_BODY_BYTES = 4096;
    private static final int MAX_RETRIES = 2;
    private static final long MAX_RETRY_DELAY_MS = 5_000L;
    public static final String REDACTION_VERSION = "llm-redact:v2";
    public static final String REDACTED_PLACEHOLDER = "[REDACTED:SECRET_PATTERN]";
    private static final int ENTROPY_MIN_LEN = 24;
    private static final int ENTROPY_MAX_LEN = 512;
    private static final double ENTROPY_THRESHOLD = 4.2;
    private static final double LN2 = StrictMath.log(2.0);
    private static final Pattern ENTROPY_TOKEN = Pattern.compile(
            "[A-Za-z0-9_/+\\-.!$@#%^&*~]{22,510}(?:={1,2}(?=$|[\\s'\"`\\\\,;)}\\]])|)");
    private static final Pattern PURE_HEX = Pattern.compile("^(?:0x)?[0-9a-fA-F]{24,512}$");
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern ISO_TIMESTAMP = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}[T_ ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?$");
    private static final Pattern DIGEST_OR_SCHEME_PREFIX = Pattern.compile(
            "^(?:sha(?:1|256|384|512)[:\\-]|md5:|blake2[bs]?:|https?:|ftps?:|sftp:|jdbc:|postgres(?:ql)?:|mysql:|mongodb(?:\\+srv)?:|redis[s]?:|amqp[s]?:|wss?:|mailto:|file:|data:|urn:|evo:|java:v\\d+:|spring:|polyglot:|type-use:|comm:|maven:|gradle:|npm:|pypi:|pkg:)");
    private static final Pattern DOTTED_FQN = Pattern.compile(
            "^[A-Za-z_$][A-Za-z0-9_$-]*(?:\\.[A-Za-z_$][A-Za-z0-9_$-]*)+$");
    private static final Pattern MAVEN_GAV = Pattern.compile(
            "^[a-z0-9._-]+:[a-z0-9._-]+:[0-9A-Za-z._-]+$");
    private static final Pattern FILE_OR_ROUTE_PATH = Pattern.compile(
            "^(?:\\.\\.?/|//|(?:[A-Za-z0-9._-]+/){2,}[A-Za-z0-9._-]+\\.[a-z0-9]{1,8}|/(?:[a-z0-9._-]+/)+[a-z0-9._-]+)$");
    private static final Pattern THINK_BLOCK = Pattern.compile("(?is)<think>.*?</think>");
    private static final Pattern SECRET_LIKE = Pattern.compile(
            "-----BEGIN [A-Z0-9 ]{0,40}PRIVATE KEY-----[\\s\\S]{0,16384}?-----END [A-Z0-9 ]{0,40}PRIVATE KEY-----"
                    + "|(?i)\\bbearer\\s+[A-Za-z0-9._\\-+/=]{8,}"
                    + "|(?i)\\bauthorization\\s*[:=]\\s*basic\\s+[A-Za-z0-9+/=]{8,}"
                    + "|(?i)\\bbasic\\s+(?-i:(?=[A-Za-z0-9+/=]*(?:[0-9+/=]|[A-Z]{2}[a-z]|[a-z][A-Z]{2}|[a-z][A-Z][a-z][A-Z]))[A-Za-z0-9+/=]{16,})"
                    + "|\\b(?:sk|rk)_live_[0-9A-Za-z]{16,}\\b"
                    + "|\\bsk-(?!learn\\b|image\\b|test\\b|example\\b|placeholder\\b|demo\\b|dummy\\b)(?:proj-|ant-api\\d{2}-|svcacct-)?[A-Za-z0-9_\\-]{12,}"
                    + "|\\bAIza[0-9A-Za-z_\\-]{20,}\\b"
                    + "|\\bgh[pousr]_[A-Za-z0-9_]{16,}\\b"
                    + "|\\bgithub_pat_[A-Za-z0-9_]{20,}\\b"
                    + "|\\bevo_pat_[A-Za-z0-9_\\-]{20,}\\b"
                    + "|\\bnpm_[A-Za-z0-9]{20,}\\b"
                    + "|\\bpypi-[A-Za-z0-9_\\-]{20,}\\b"
                    + "|\\bhf_[A-Za-z0-9]{20,}\\b"
                    + "|\\bgsk_[A-Za-z0-9]{20,}\\b"
                    + "|\\bpplx-[A-Za-z0-9]{20,}\\b"
                    + "|(?i)\\bAccountKey=[A-Za-z0-9+/=]{20,}"
                    + "|(?i)(?<=[?&])sig=[A-Za-z0-9%+/=]{20,}"
                    + "|\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"
                    + "|\\bxox[baprs]-[0-9A-Za-z\\-]{10,}\\b"
                    + "|\\beyJ[A-Za-z0-9_\\-]{8,}\\.eyJ[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}\\b"
                    + "|(?i)\\b(?:https?|postgres(?:ql)?|mysql|mariadb|mongodb(?:\\+srv)?|redis|rediss|amqp|amqps|ftp|ftps|sftp|jdbc:[a-z0-9]+):\\/\\/[^/\\s:@\"'`<>]{0,128}:[^/\\s@\"'`<>]{3,128}@[^\\s\"'`<>]+");
    private static final Pattern INJECTION_SENTINELS = Pattern.compile(
            "(?i)<\\|[A-Za-z0-9_\\-]{1,40}\\|>|\\[/\\s*INST\\s*\\]|\\[\\s*INST\\s*\\]|<<\\s*/\\s*SYS\\s*>>|<<\\s*SYS\\s*>>|<\\s*/\\s*untrusted_source\\b[^>]{0,1024}>|<\\s*untrusted_source\\b[^>]{0,1024}>|<\\s*/\\s*untrusted_source\\b|<\\s*untrusted_source\\b");
    private static final Pattern CONFIG_SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)^(\\s*(?:export\\s+|-\\s+)?['\"]?([A-Za-z0-9_.\\-]{0,128}(?:password|passwd|secret|token|api_?key|private_?key|access_?key|credential|signing_?key|client_?secret|auth_?key)[A-Za-z0-9_.\\-]{0,128})['\"]?\\s*[=:]\\s*)(.+)$");
    private static final Pattern XML_SECRET_ELEMENT = Pattern.compile(
            "(?i)(<([A-Za-z0-9_.:-]{0,64}(?:password|passwd|secret|token|api_?key|private_?key|access_?key|credential|signing_?key|client_?secret|auth_?key)[A-Za-z0-9_.:-]{0,64})(?:\\s+[^<>]{0,256})?>)([^<]{1,512})(</\\2>)");
    /**
     * Benign configuration metadata keys (e.g. {@code maxTokens}, {@code tokenType}, {@code tokenEndpoint},
     * {@code tokenExpirySeconds}, {@code passwordEncoder}, {@code secretName}, {@code privateKeyPath}) whose
     * values are structural settings rather than secret material. Global {@link #SECRET_LIKE} redaction STILL
     * runs over the full document, so any actual token/credential literal inside these fields is always redacted.
     */
    private static final Pattern BENIGN_CONFIG_METADATA_KEY = Pattern.compile(
            "(?i)(?:^|[._:-])(?:max|min|total|input|output|prompt|completion|context|budget|chunk)[._-]?tokens$"
                    + "|(?:^|[._:-])(?:token|password|passwd|secret|key|credential)s?[._-]?(?:type|header|url|uri|endpoint|issuer|audience|scope|scopes|param|parameter|field|name|env|envvar|env[._-]?var|path|file|dir|directory|ref|alias|alg|algorithm|size|bits|len|length|min[._-]?len(?:gth)?|max[._-]?len(?:gth)?|count|limit|budget|ttl|expiry|expiration|timeout|lifetime|duration|seconds|millis|ms|prefix|format|encoder|hasher|policy|provider|helper|store|mode|enabled|required|version|arn)$"
                    + "|(?:^|[._:-])(?:token|password|passwd|secret|key|credential)s?[._-](?:expiry|expiration|timeout|lifetime|ttl|min|max)[._-](?:seconds|secs|sec|minutes|mins|min|hours|hrs|days|millis|ms|length|len|chars|bytes|bits|size|count|attempts|age)$");
    private static final Pattern YAML_BLOCK_SCALAR_HEADER = Pattern.compile(
            "^(?:(?:![A-Za-z0-9_./:-]+|&[A-Za-z0-9_.-]+)\\s+)*[|>](?:[1-9][+-]?|[+-][1-9]?)?$");
    private static final Pattern SAFE_CONFIG_PLACEHOLDER = Pattern.compile(
            "^(?:\\$\\{[^{}]+\\}|\\$[A-Za-z0-9_]+|<[^<>]+>|\\*[^\\s]+|&[^\\s]+|workspace:\\*|latest|(?:[\\^~]|[<>]=?)?\\d+\\.\\d+\\.\\d+(?:-[A-Za-z0-9.\\-]+)?)$");
    public static final int DEFAULT_LLM_CONCURRENCY = 6;
    public static final int MIN_LLM_CONCURRENCY = 1;
    public static final int MAX_LLM_CONCURRENCY = 32;

    /** Returns the configured LLM concurrency slot limit via {@code -Devo.llm.concurrency} (default 6, clamped 1..32). */
    public static int configuredConcurrencySlots() {
        String raw = System.getProperty("evo.llm.concurrency");
        if (raw == null || raw.isBlank()) return DEFAULT_LLM_CONCURRENCY;
        try {
            int parsed = Integer.parseInt(raw.strip());
            return Math.max(MIN_LLM_CONCURRENCY, Math.min(MAX_LLM_CONCURRENCY, parsed));
        } catch (NumberFormatException ignored) {
            return DEFAULT_LLM_CONCURRENCY;
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .proxy(HttpClient.Builder.NO_PROXY)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private LlmTransport() {}

    public static boolean isLoopback(URI uri) {
        if (uri == null || uri.getHost() == null) return false;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return "127.0.0.1".equals(host) || "[::1]".equals(host) || "::1".equals(host) || "localhost".equals(host);
    }

    public static void validateEndpoint(URI input, String model, String key, boolean allowRemote, String errorCode) {
        if (input == null || input.getHost() == null || input.getRawUserInfo() != null
                || input.getRawQuery() != null || input.getRawFragment() != null
                || input.getPort() == 0 || input.getPort() > 65535) {
            throw new IllegalArgumentException(errorCode);
        }
        boolean local = isLoopback(input);
        String scheme = input.getScheme();
        boolean schemeOk = (local && "http".equalsIgnoreCase(scheme))
                || ("https".equalsIgnoreCase(scheme) && (local || allowRemote));
        if (!schemeOk
                || model == null || model.isBlank() || model.length() > 200
                || model.codePoints().anyMatch(Character::isISOControl)
                || (key != null && (key.length() > 8192 || key.codePoints().anyMatch(c -> c <= 32 || c >= 127)))) {
            throw new IllegalArgumentException(errorCode);
        }
    }

    public static boolean isReasoningModel(String model) {
        if (model == null) return false;
        String m = model.strip().toLowerCase(Locale.ROOT);
        int slash = m.lastIndexOf('/');
        if (slash >= 0) m = m.substring(slash + 1);
        return m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4")
                || m.startsWith("gpt-5") || m.contains("deepseek-r1") || m.contains("qwq")
                || m.contains("thinking");
    }

    public static boolean compatibleModel(String configured, String returned, boolean remote) {
        if (configured == null || returned == null) return false;
        if (configured.equals(returned)) return true;
        if (returned.isBlank() || returned.length() > 200 || returned.codePoints().anyMatch(Character::isISOControl)) {
            return false;
        }
        String c = normalizeModelName(configured);
        String r = normalizeModelName(returned);
        if (c.equals(r)) return true;
        if (!remote) {
            return r.startsWith(c + ":") || c.startsWith(r + ":") || r.startsWith(c + "-");
        }
        return r.startsWith(c + "-") || r.startsWith(c + ":") || c.startsWith(r + ":")
                || r.endsWith("/" + c) || c.endsWith("/" + r);
    }

    private static String normalizeModelName(String model) {
        String s = model.strip();
        if (s.startsWith("models/")) s = s.substring("models/".length());
        int slash = s.indexOf('/');
        if (slash > 0 && slash < s.length() - 1) {
            // Keep bare tail for cross-matching provider-prefixed OpenRouter IDs
            return s.substring(slash + 1);
        }
        return s;
    }

    public static boolean isAcceptableFinishReason(String raw) {
        if (raw == null || raw.isBlank()) return true;
        String norm = raw.strip().toLowerCase(Locale.ROOT);
        return norm.equals("stop") || norm.equals("length") || norm.equals("end_turn")
                || norm.equals("max_tokens") || norm.equals("tool_calls") || norm.equals("tool_use")
                || norm.equals("function_call");
    }

    public static boolean isTruncatedFinishReason(String raw) {
        if (raw == null) return false;
        String norm = raw.strip().toLowerCase(Locale.ROOT);
        return norm.equals("length") || norm.equals("max_tokens");
    }

    public record HttpResult(int statusCode, Map<String, List<String>> headers, byte[] body) {}

    public static HttpResult sendJsonWithRetry(URI uri, Map<String, String> headers, byte[] body,
                                               Duration timeout, BooleanSupplier cancelled) {
        int attempt = 0;
        while (true) {
            if (cancelled.getAsBoolean()) {
                throw new LlmProviderAdapter.LlmException(LlmProviderAdapter.ErrorCode.RESOURCE_LIMIT, 0, "Request cancelled");
            }
            var req = buildRequest(uri, headers, body, timeout, "POST");
            long deadline = System.nanoTime() + timeout.toNanos();
            var pending = HTTP.sendAsync(req, info -> info.statusCode() == 200
                    ? boundedBody(MAX_RESPONSE_BYTES)
                    : boundedBody(MAX_ERROR_BODY_BYTES));
            try {
                HttpResponse<byte[]> response = awaitPending(pending, deadline, cancelled);
                int status = response.statusCode();
                if (status == 200) {
                    return new HttpResult(status, response.headers().map(), response.body());
                }
                if (isRetryableStatus(status) && attempt < MAX_RETRIES) {
                    long delayMs = retryDelayMs(response.headers(), attempt);
                    attempt++;
                    sleepCancellable(delayMs, cancelled);
                    continue;
                }
                String detail = sanitizeErrorDetail(status, response.body());
                var code = classifyError(status);
                throw new LlmProviderAdapter.LlmException(code, status, detail);
            } catch (LlmProviderAdapter.LlmException ex) {
                throw ex;
            } catch (IOException io) {
                if (attempt < MAX_RETRIES && !cancelled.getAsBoolean()) {
                    attempt++;
                    sleepCancellable(300L << attempt, cancelled);
                    continue;
                }
                throw new LlmProviderAdapter.LlmException(
                        cancelled.getAsBoolean() ? LlmProviderAdapter.ErrorCode.RESOURCE_LIMIT : LlmProviderAdapter.ErrorCode.PROVIDER_UNAVAILABLE,
                        0, sanitizeText(io.getMessage()));
            } finally {
                pending.cancel(true);
            }
        }
    }

    public static HttpResult sendGet(URI uri, Map<String, String> headers, Duration timeout, BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new LlmProviderAdapter.LlmException(LlmProviderAdapter.ErrorCode.RESOURCE_LIMIT, 0, "Request cancelled");
        }
        var req = buildRequest(uri, headers, null, timeout, "GET");
        long deadline = System.nanoTime() + timeout.toNanos();
        var pending = HTTP.sendAsync(req, info -> boundedBody(MAX_RESPONSE_BYTES));
        try {
            var response = awaitPending(pending, deadline, cancelled);
            return new HttpResult(response.statusCode(), response.headers().map(), response.body());
        } catch (IOException io) {
            throw new LlmProviderAdapter.LlmException(
                    cancelled.getAsBoolean() ? LlmProviderAdapter.ErrorCode.RESOURCE_LIMIT : LlmProviderAdapter.ErrorCode.PROVIDER_UNAVAILABLE,
                    0, sanitizeText(io.getMessage()));
        } finally {
            pending.cancel(true);
        }
    }

    public record StreamOutcome(int statusCode, String contentType, byte[] rawBytes) {}

    /**
     * Cancellation-aware streaming POST using sendAsync so client disconnect cancels immediately even during TTFB.
     */
    public static StreamOutcome sendStreamWithRetry(URI uri, Map<String, String> headers, byte[] body,
                                                    Duration timeout, BooleanSupplier cancelled,
                                                    Consumer<String> onSseLine) {
        int attempt = 0;
        while (true) {
            if (cancelled.getAsBoolean()) {
                throw new LlmProviderAdapter.LlmException(LlmProviderAdapter.ErrorCode.RESOURCE_LIMIT, 0, "Request cancelled");
            }
            var req = buildRequest(uri, headers, body, timeout, "POST");
            long deadline = System.nanoTime() + timeout.toNanos();
            var pending = HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofInputStream());
            HttpResponse<InputStream> response = null;
            try {
                response = awaitPending(pending, deadline, cancelled);
                int status = response.statusCode();
                if (status != 200) {
                    byte[] errBody;
                    try (var in = response.body()) {
                        errBody = in.readNBytes(MAX_ERROR_BODY_BYTES);
                    }
                    if (isRetryableStatus(status) && attempt < MAX_RETRIES) {
                        long delayMs = retryDelayMs(response.headers(), attempt);
                        attempt++;
                        sleepCancellable(delayMs, cancelled);
                        continue;
                    }
                    throw new LlmProviderAdapter.LlmException(classifyError(status), status, sanitizeErrorDetail(status, errBody));
                }
                String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
                if (!contentType.contains("text/event-stream")) {
                    byte[] all;
                    try (var in = response.body()) {
                        all = in.readNBytes(MAX_RESPONSE_BYTES + 1);
                    }
                    if (all.length > MAX_RESPONSE_BYTES) {
                        throw new LlmProviderAdapter.LlmException(LlmProviderAdapter.ErrorCode.RESOURCE_LIMIT, 200, "Response exceeded limit");
                    }
                    return new StreamOutcome(200, contentType, all);
                }
                var rawCapture = new ByteArrayOutputStream();
                try (var in = response.body();
                     var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (cancelled.getAsBoolean() || System.nanoTime() > deadline) {
                            throw new LlmProviderAdapter.LlmException(LlmProviderAdapter.ErrorCode.RESOURCE_LIMIT, 200, "Stream cancelled or timed out");
                        }
                        byte[] lineBytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
                        if (rawCapture.size() + lineBytes.length <= MAX_RESPONSE_BYTES) {
                            rawCapture.writeBytes(lineBytes);
                        }
                        onSseLine.accept(line);
                    }
                }
                return new StreamOutcome(200, contentType, rawCapture.toByteArray());
            } catch (LlmProviderAdapter.LlmException ex) {
                throw ex;
            } catch (IOException io) {
                if (attempt < MAX_RETRIES && !cancelled.getAsBoolean()) {
                    attempt++;
                    sleepCancellable(300L << attempt, cancelled);
                    continue;
                }
                throw new LlmProviderAdapter.LlmException(
                        cancelled.getAsBoolean() ? LlmProviderAdapter.ErrorCode.RESOURCE_LIMIT : LlmProviderAdapter.ErrorCode.PROVIDER_UNAVAILABLE,
                        0, sanitizeText(io.getMessage()));
            } finally {
                pending.cancel(true);
                if (response != null) {
                    try { response.body().close(); } catch (IOException ignored) {}
                }
            }
        }
    }

    private static HttpRequest buildRequest(URI uri, Map<String, String> headers, byte[] body, Duration timeout, String method) {
        var builder = HttpRequest.newBuilder(uri).timeout(timeout);
        if (headers != null) {
            headers.forEach((k, v) -> {
                if (k != null && v != null && !v.isEmpty()) builder.header(k, v);
            });
        }
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.POST(HttpRequest.BodyPublishers.ofByteArray(body == null ? new byte[0] : body));
        }
        return builder.build();
    }

    private static <T> HttpResponse<T> awaitPending(CompletableFuture<HttpResponse<T>> pending, long deadlineNanos,
                                                    BooleanSupplier cancelled) throws IOException {
        try {
            while (!cancelled.getAsBoolean() && System.nanoTime() < deadlineNanos) {
                try {
                    var resp = pending.get(100, TimeUnit.MILLISECONDS);
                    if (cancelled.getAsBoolean()) {
                        throw new LlmProviderAdapter.LlmException(LlmProviderAdapter.ErrorCode.RESOURCE_LIMIT, 0, "Request cancelled");
                    }
                    return resp;
                } catch (TimeoutException waiting) {
                    // Re-check cancellation and deadline.
                }
            }
            throw new LlmProviderAdapter.LlmException(
                    cancelled.getAsBoolean() ? LlmProviderAdapter.ErrorCode.RESOURCE_LIMIT : LlmProviderAdapter.ErrorCode.PROVIDER_UNAVAILABLE,
                    0, cancelled.getAsBoolean() ? "Request cancelled" : "Provider request timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new LlmProviderAdapter.LlmException(LlmProviderAdapter.ErrorCode.RESOURCE_LIMIT, 0, "Interrupted");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException(cause == null ? "HTTP failure" : cause.getMessage(), cause);
        }
    }

    private static boolean isRetryableStatus(int status) {
        return status == 429 || status == 502 || status == 503 || status == 504;
    }

    private static long retryDelayMs(HttpHeaders headers, int attempt) {
        var retryAfter = headers.firstValue("Retry-After");
        if (retryAfter.isPresent()) {
            try {
                long sec = Long.parseLong(retryAfter.orElseThrow().strip());
                if (sec > 0) return Math.min(MAX_RETRY_DELAY_MS, sec * 1000L);
            } catch (NumberFormatException ignored) {}
        }
        return Math.min(MAX_RETRY_DELAY_MS, 400L << attempt);
    }

    private static void sleepCancellable(long millis, BooleanSupplier cancelled) {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < end) {
            if (cancelled.getAsBoolean()) {
                throw new LlmProviderAdapter.LlmException(LlmProviderAdapter.ErrorCode.RESOURCE_LIMIT, 0, "Request cancelled");
            }
            try {
                Thread.sleep(Math.min(100L, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime()))));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new LlmProviderAdapter.LlmException(LlmProviderAdapter.ErrorCode.RESOURCE_LIMIT, 0, "Interrupted");
            }
        }
    }

    private static LlmProviderAdapter.ErrorCode classifyError(int status) {
        if (status == 401 || status == 403) return LlmProviderAdapter.ErrorCode.AUTH_FAILED;
        if (status == 429) return LlmProviderAdapter.ErrorCode.RATE_LIMITED;
        return LlmProviderAdapter.ErrorCode.PROVIDER_UNAVAILABLE;
    }

    public static String sanitizeErrorDetail(int status, byte[] body) {
        String raw = body == null || body.length == 0 ? "" : new String(body, StandardCharsets.UTF_8);
        String extracted = raw;
        if (raw.stripLeading().startsWith("{")) {
            try {
                var obj = GraphJson.object(GraphJson.read(raw, new GraphJson.Limits(MAX_ERROR_BODY_BYTES + 256, 16, 512)));
                if (obj.get("error") instanceof Map<?, ?> err && err.get("message") instanceof String msg) {
                    extracted = msg;
                } else if (obj.get("message") instanceof String msg) {
                    extracted = msg;
                } else if (obj.get("error") instanceof String msg) {
                    extracted = msg;
                }
            } catch (RuntimeException ignored) {}
        }
        String clean = sanitizeText(extracted);
        return clean.isBlank() ? ("HTTP " + status) : ("HTTP " + status + ": " + clean);
    }

    public record RedactionResult(String text, int redactedCount) {}

    public record RedactionSpan(int startLine, int endLine) {}

    public record RedactedDocument(String text, List<String> lines, List<RedactionSpan> spans) {
        public RedactedDocument {
            text = text == null ? "" : text;
            lines = List.copyOf(lines);
            spans = List.copyOf(spans);
        }

        public int lineCount() {
            return lines.size();
        }

        public int totalRedactedCount() {
            return spans.size();
        }

        public int redactedCountInLineRange(int startLine, int endLine) {
            if (spans.isEmpty() || endLine < startLine) return 0;
            int count = 0;
            for (var span : spans) {
                if (span.startLine() <= endLine && span.endLine() >= startLine) {
                    count++;
                }
            }
            return count;
        }
    }

    /**
     * Deterministically redacts secret-like patterns from outbound LLM/MCP/UI excerpts without mutating stored CAS bytes.
     */
    public static RedactionResult redactSecrets(String raw) {
        return redactSecrets(raw, false);
    }

    /**
     * Deterministically redacts secret-like patterns while preserving the exact line count for multi-line
     * matches (e.g. PEM private keys) so 1-based line numbers in {@code numberedSource} remain exact.
     */
    public static RedactionResult redactSecretsPreserveLines(String raw) {
        return redactSecrets(raw, true);
    }

    private static RedactionResult redactSecrets(String raw, boolean preserveLineCount) {
        return redactSecretsWithSpans(raw, preserveLineCount, null);
    }

    private static RedactionResult redactSecretsWithSpans(String raw, boolean preserveLineCount, List<RedactionSpan> outSpans) {
        if (raw == null || raw.isEmpty()) {
            return new RedactionResult("", 0);
        }
        var matcher = SECRET_LIKE.matcher(raw);
        if (!matcher.find()) {
            return new RedactionResult(raw, 0);
        }
        var sb = new StringBuilder(raw.length());
        int count = 0;
        int currentLine = 1;
        int cursor = 0;
        do {
            count++;
            int matchLineBreaks = countLineBreaks(raw, matcher.start(), matcher.end());
            if (outSpans != null) {
                currentLine += countLineBreaks(raw, cursor, matcher.start());
                int matchStartLine = currentLine;
                int matchEndLine = matchStartLine + matchLineBreaks;
                currentLine = matchEndLine;
                cursor = matcher.end();
                outSpans.add(new RedactionSpan(matchStartLine, matchEndLine));
            }
            String replacement = REDACTED_PLACEHOLDER;
            if (preserveLineCount && matchLineBreaks > 0) {
                var multi = new StringBuilder((REDACTED_PLACEHOLDER.length() + 1) * (matchLineBreaks + 1));
                for (int k = 0; k <= matchLineBreaks; k++) {
                    if (k > 0) multi.append('\n');
                    multi.append(REDACTED_PLACEHOLDER);
                }
                replacement = multi.toString();
            }
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        } while (matcher.find());
        matcher.appendTail(sb);
        return new RedactionResult(sb.toString(), count);
    }

    private static int countLineBreaks(String s, int from, int to) {
        int count = 0;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c == '\n') {
                count++;
            } else if (c == '\r') {
                if (i + 1 < to && s.charAt(i + 1) == '\n') {
                    i++;
                }
                count++;
            }
        }
        return count;
    }

    /** Returns true when {@code path} is an environment or configuration file where key=value secrets live. */
    public static boolean isConfigOrEnvPath(String path) {
        if (path == null || path.isEmpty()) return false;
        String lower = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        String name = lower.substring(lower.lastIndexOf('/') + 1);
        return name.equals(".env") || name.startsWith(".env.") || name.endsWith(".env")
                || name.endsWith(".properties") || name.endsWith(".cfg")
                || name.endsWith(".conf") || name.endsWith(".ini")
                || name.endsWith(".yml") || name.endsWith(".yaml")
                || name.endsWith(".json") || name.endsWith(".toml")
                || name.endsWith(".xml") || name.endsWith(".tfvars")
                || name.endsWith(".sh");
    }

    private static boolean isYamlPath(String path) {
        if (path == null || path.isEmpty()) return false;
        String lower = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        return lower.endsWith(".yml") || lower.endsWith(".yaml");
    }

    private static boolean isXmlPath(String path) {
        if (path == null || path.isEmpty()) return false;
        return path.replace('\\', '/').toLowerCase(Locale.ROOT).endsWith(".xml");
    }

    private static String stripInlineCommentAndQuotes(String rawVal) {
        String candidate = rawVal == null ? "" : rawVal.strip();
        if (!candidate.startsWith("\"") && !candidate.startsWith("'")) {
            int hashIdx = candidate.indexOf(" #");
            if (hashIdx >= 0) candidate = candidate.substring(0, hashIdx).strip();
        }
        if (candidate.endsWith(",") || candidate.endsWith(";")) {
            candidate = candidate.substring(0, candidate.length() - 1).strip();
        }
        return candidate.replaceAll("^['\"]|['\"]$", "").strip();
    }

    private static boolean isSafeConfigValue(String unquoted) {
        return unquoted.isEmpty()
                || unquoted.equals("{") || unquoted.equals("}") || unquoted.equals("{}")
                || unquoted.equals("[") || unquoted.equals("]") || unquoted.equals("[]")
                || SAFE_CONFIG_PLACEHOLDER.matcher(unquoted).matches()
                || YAML_BLOCK_SCALAR_HEADER.matcher(unquoted).matches()
                || unquoted.equalsIgnoreCase("true") || unquoted.equalsIgnoreCase("false")
                || unquoted.equalsIgnoreCase("null") || unquoted.equals("~")
                || unquoted.equalsIgnoreCase("change_me") || unquoted.equalsIgnoreCase("changeme")
                || unquoted.equalsIgnoreCase("placeholder") || unquoted.equalsIgnoreCase("example")
                || unquoted.equalsIgnoreCase("dummy") || unquoted.equalsIgnoreCase("redacted")
                || unquoted.equalsIgnoreCase("none") || unquoted.equalsIgnoreCase("disabled")
                || unquoted.equals(REDACTED_PLACEHOLDER);
    }

    private static boolean isBenignConfigMetadataKey(String rawKey) {
        if (rawKey == null || rawKey.isEmpty()) return false;
        // Normalize acronym & camelCase boundaries (e.g. JWTTokenVersion -> jwt_token_version,
        // tokenExpirySeconds -> token_expiry_seconds) while keeping dotted/colon segments intact
        // (e.g. oauth.token.endpoint, security.password.encoder, vault.secret.name, <jwt.token.version>)
        // so benign metadata is preserved without whitelisting actual secret keys.
        String normalized = rawKey
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toLowerCase(Locale.ROOT);
        return BENIGN_CONFIG_METADATA_KEY.matcher(normalized).find();
    }

    private static boolean isYamlBlockScalarSecretHeader(String line) {
        if (line == null || line.isEmpty()) return false;
        String trimmed = line.stripLeading();
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) return false;
        var m = CONFIG_SECRET_ASSIGNMENT.matcher(line);
        if (!m.matches()) return false;
        if (isBenignConfigMetadataKey(m.group(2))) return false;
        // Require YAML colon separator on the key header
        String prefix = m.group(1);
        if (prefix.lastIndexOf(':') < 0) return false;
        String rhs = m.group(3).strip().replaceFirst("\\s+#.*$", "").strip();
        return YAML_BLOCK_SCALAR_HEADER.matcher(rhs).matches();
    }

    private static int leadingIndent(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
        return i;
    }

    private static int yamlKeyIndent(String line) {
        int indent = leadingIndent(line);
        if (indent + 1 < line.length() && line.charAt(indent) == '-'
                && (line.charAt(indent + 1) == ' ' || line.charAt(indent + 1) == '\t')) {
            int j = indent + 1;
            while (j < line.length() && (line.charAt(j) == ' ' || line.charAt(j) == '\t')) j++;
            return j;
        }
        return indent;
    }

    /** Redacts a single sensitive key=value or key: value line in config/env files while preserving safe placeholders. */
    public static RedactionResult redactConfigAssignmentLine(String line) {
        if (line == null || line.isEmpty()) return new RedactionResult("", 0);
        String trimmed = line.stripLeading();
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) {
            return new RedactionResult(line, 0);
        }
        var m = CONFIG_SECRET_ASSIGNMENT.matcher(line);
        if (!m.matches()) {
            return new RedactionResult(line, 0);
        }
        if (isBenignConfigMetadataKey(m.group(2))) {
            return new RedactionResult(line, 0);
        }
        String unquoted = stripInlineCommentAndQuotes(m.group(3));
        if (isSafeConfigValue(unquoted)) {
            return new RedactionResult(line, 0);
        }
        return new RedactionResult(m.group(1) + REDACTED_PLACEHOLDER, 1);
    }

    private static String redactConfigAndYamlLines(String path, String raw, List<RedactionSpan> outSpans, int[] outConfigCount) {
        String[] lines = raw.split("\\R", -1);
        boolean yaml = isYamlPath(path);
        boolean xml = isXmlPath(path);
        for (int i = 0; i < lines.length; i++) {
            if (yaml && isYamlBlockScalarSecretHeader(lines[i])) {
                int keyIndent = yamlKeyIndent(lines[i]);
                int j = i + 1;
                boolean hasSecretBody = false;
                while (j < lines.length) {
                    if (lines[j].isBlank()) {
                        j++;
                        continue;
                    }
                    if (leadingIndent(lines[j]) > keyIndent) {
                        // Inside YAML block scalars ('|' / '>'), '#' is literal secret content, never an inline comment.
                        String bodyUnquoted = lines[j].strip().replaceAll("^['\"]|['\"]$", "").strip();
                        if (!isSafeConfigValue(bodyUnquoted)) {
                            hasSecretBody = true;
                        }
                        j++;
                    } else {
                        break;
                    }
                }
                if (hasSecretBody) {
                    int firstLine = -1;
                    int lastLine = -1;
                    for (int k = i + 1; k < j; k++) {
                        if (!lines[k].isBlank()) {
                            int ind = leadingIndent(lines[k]);
                            lines[k] = lines[k].substring(0, ind) + REDACTED_PLACEHOLDER;
                            if (firstLine < 0) firstLine = k + 1;
                            lastLine = k + 1;
                        }
                    }
                    if (firstLine > 0) {
                        outConfigCount[0]++;
                        if (outSpans != null) {
                            outSpans.add(new RedactionSpan(firstLine, lastLine));
                        }
                    }
                }
                i = j - 1;
                continue;
            }
            if (xml) {
                var xmlRes = redactXmlSecretLine(lines[i]);
                if (xmlRes.redactedCount() > 0) {
                    lines[i] = xmlRes.text();
                    outConfigCount[0] += xmlRes.redactedCount();
                    if (outSpans != null) {
                        outSpans.add(new RedactionSpan(i + 1, i + 1));
                    }
                }
            }
            var cfg = redactConfigAssignmentLine(lines[i]);
            if (cfg.redactedCount() > 0) {
                lines[i] = cfg.text();
                outConfigCount[0] += cfg.redactedCount();
                if (outSpans != null) {
                    outSpans.add(new RedactionSpan(i + 1, i + 1));
                }
            }
        }
        return String.join("\n", lines);
    }

    private static RedactionResult redactXmlSecretLine(String line) {
        if (line == null || line.isEmpty()) return new RedactionResult("", 0);
        var m = XML_SECRET_ELEMENT.matcher(line);
        if (!m.find()) return new RedactionResult(line, 0);
        var sb = new StringBuilder(line.length());
        int count = 0;
        do {
            String tagName = m.group(2);
            String value = stripInlineCommentAndQuotes(m.group(3));
            if (isBenignConfigMetadataKey(tagName) || isSafeConfigValue(value)) {
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(m.group(0)));
            } else {
                count++;
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(m.group(1) + REDACTED_PLACEHOLDER + m.group(4)));
            }
        } while (m.find());
        m.appendTail(sb);
        return new RedactionResult(sb.toString(), count);
    }

    /**
     * Computes deterministic Shannon entropy in bits/character using {@link StrictMath#log(double)}.
     */
    public static double shannonEntropy(String token) {
        if (token == null || token.isEmpty()) return 0.0;
        int[] ascii = new int[128];
        Map<Integer, Integer> nonAscii = null;
        int len = token.length();
        for (int i = 0; i < len; i++) {
            char c = token.charAt(i);
            if (c < 128) {
                ascii[c]++;
            } else {
                if (nonAscii == null) nonAscii = new HashMap<>();
                nonAscii.merge((int) c, 1, Integer::sum);
            }
        }
        double inv = 1.0 / len;
        double entropy = 0.0;
        for (int count : ascii) {
            if (count > 0) {
                double p = count * inv;
                entropy -= p * (StrictMath.log(p) / LN2);
            }
        }
        if (nonAscii != null) {
            for (int count : nonAscii.values()) {
                double p = count * inv;
                entropy -= p * (StrictMath.log(p) / LN2);
            }
        }
        return entropy;
    }

    /**
     * Returns true when {@code token} is a high-entropy secret candidate ({@code len >= 24},
     * Shannon entropy {@code >= 4.2} bits/char across mixed character classes) while strictly
     * excluding SHA/hex digests, UUIDs, URLs, file/route paths, Java FQNs, Maven GAVs,
     * ISO timestamps, and normal camelCase/PascalCase identifiers.
     */
    public static boolean isHighEntropySecretToken(String token) {
        if (token == null) return false;
        int len = token.length();
        if (len < ENTROPY_MIN_LEN || len > ENTROPY_MAX_LEN) return false;
        if (token.contains("REDACTED") || isSafeConfigValue(token)
                || token.startsWith("${") || token.startsWith("$(") || token.startsWith("{{")) {
            return false;
        }
        String lower = token.toLowerCase(Locale.ROOT);
        if (lower.contains("://") || DIGEST_OR_SCHEME_PREFIX.matcher(lower).find()) {
            return false;
        }
        if (PURE_HEX.matcher(token).matches()
                || UUID_PATTERN.matcher(token).matches()
                || ISO_TIMESTAMP.matcher(token).matches()
                || MAVEN_GAV.matcher(token).matches()
                || FILE_OR_ROUTE_PATH.matcher(token).matches()) {
            return false;
        }
        if (DOTTED_FQN.matcher(token).matches()) {
            // Preserve 2+ segment Java/JS identifiers & FQNs unless an individual segment is itself a high-entropy secret.
            String[] segs = token.split("\\.");
            boolean anySecretSegment = false;
            for (String seg : segs) {
                if (seg.length() >= ENTROPY_MIN_LEN && isHighEntropySecretToken(seg)) {
                    anySecretSegment = true;
                    break;
                }
            }
            if (!anySecretSegment) {
                return false;
            }
        }

        int upper = 0;
        int lowerCount = 0;
        int digits = 0;
        int secretSyms = 0;
        int separators = 0;
        int digitRuns = 0;
        boolean inDigitRun = false;
        int upperFollowedByLower = 0;
        for (int i = 0; i < len; i++) {
            char c = token.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                upper++;
                inDigitRun = false;
                if (i + 1 < len && token.charAt(i + 1) >= 'a' && token.charAt(i + 1) <= 'z') {
                    upperFollowedByLower++;
                }
            } else if (c >= 'a' && c <= 'z') {
                lowerCount++;
                inDigitRun = false;
            } else if (c >= '0' && c <= '9') {
                digits++;
                if (!inDigitRun) {
                    digitRuns++;
                    inDigitRun = true;
                }
            } else if (c == '_' || c == '-' || c == '.') {
                separators++;
                inDigitRun = false;
            } else {
                secretSyms++;
                inDigitRun = false;
            }
        }
        int classes = (upper > 0 ? 1 : 0) + (lowerCount > 0 ? 1 : 0)
                + (digits > 0 ? 1 : 0) + (secretSyms > 0 ? 1 : 0);
        if (classes < 3) {
            return false;
        }
        // Require at least 2 digits or an explicit non-identifier symbol so camelCase identifiers
        // with a single version suffix (e.g. ArchitectureScoreModelV2Impl) never false-positive.
        if (digits < 2 && secretSyms == 0) {
            return false;
        }
        // Guard pure alphanumeric camelCase identifiers that only carry a single contiguous trailing digit run.
        if (secretSyms == 0 && separators == 0 && digitRuns == 1 && upper > 0 && upperFollowedByLower >= upper - 1
                && Character.isDigit(token.charAt(len - 1))) {
            return false;
        }
        return shannonEntropy(token) >= ENTROPY_THRESHOLD;
    }

    private static RedactionResult redactHighEntropyTokensWithSpans(String raw, List<RedactionSpan> outSpans) {
        if (raw == null || raw.isEmpty()) {
            return new RedactionResult("", 0);
        }
        String[] lines = raw.split("\\R", -1);
        int totalCount = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.length() < ENTROPY_MIN_LEN) continue;
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("import ") || trimmed.startsWith("package ")) continue;

            var tm = ENTROPY_TOKEN.matcher(line);
            if (!tm.find()) continue;
            var sb = new StringBuilder(line.length());
            int lineHits = 0;
            do {
                int matchStart = tm.start();
                String matched = tm.group(0);
                // Preserve URL bodies immediately after "scheme://"
                if (matchStart >= 1 && line.charAt(matchStart - 1) == ':' && matched.startsWith("//")) {
                    tm.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(matched));
                    continue;
                }
                // Handle backslash-escaped leading escape char (e.g. \nSECRET, \tSECRET) cleanly
                String leadingPrefix = "";
                String candidate = matched;
                if (matchStart >= 1 && line.charAt(matchStart - 1) == '\\'
                        && candidate.length() > ENTROPY_MIN_LEN
                        && (candidate.charAt(0) == 'n' || candidate.charAt(0) == 'r' || candidate.charAt(0) == 't')) {
                    String afterEsc = candidate.substring(1);
                    if (isHighEntropySecretToken(afterEsc)) {
                        leadingPrefix = candidate.substring(0, 1);
                        candidate = afterEsc;
                    }
                }
                // Strip trailing sentence punctuation (e.g. prose "token XYZ." or "token XYZ!")
                String trailingSuffix = "";
                while (!candidate.isEmpty()) {
                    char last = candidate.charAt(candidate.length() - 1);
                    if (last == '.' || last == '!' || last == ',') {
                        trailingSuffix = last + trailingSuffix;
                        candidate = candidate.substring(0, candidate.length() - 1);
                    } else {
                        break;
                    }
                }
                if (isHighEntropySecretToken(candidate)) {
                    lineHits++;
                    tm.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                            leadingPrefix + REDACTED_PLACEHOLDER + trailingSuffix));
                } else {
                    tm.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(matched));
                }
            } while (tm.find());
            tm.appendTail(sb);

            if (lineHits > 0) {
                lines[i] = sb.toString();
                totalCount += lineHits;
                if (outSpans != null) {
                    for (int h = 0; h < lineHits; h++) {
                        outSpans.add(new RedactionSpan(i + 1, i + 1));
                    }
                }
            }
        }
        if (totalCount == 0) {
            return new RedactionResult(raw, 0);
        }
        return new RedactionResult(String.join("\n", lines), totalCount);
    }

    /**
     * Full-document path-aware redaction with {@code preserveLineCount=true} and exact 1-based redaction line spans.
     * Applies config/env assignment redaction, all 11 standard secret patterns, and the deterministic Shannon-entropy
     * secret guard ({@value #REDACTION_VERSION}). Callers slicing partial windows MUST redact via this method FIRST
     * before slicing so multi-line secrets (e.g. PEM private keys or YAML block scalars) can never leak across slice boundaries.
     */
    public static RedactedDocument redactDocumentForPath(String path, String raw) {
        if (raw == null || raw.isEmpty()) {
            return new RedactedDocument("", List.of(""), List.of());
        }
        var spans = new ArrayList<RedactionSpan>();
        int[] configCount = new int[1];
        String working = isConfigOrEnvPath(path)
                ? redactConfigAndYamlLines(path, raw, spans, configCount)
                : raw;
        var block = redactSecretsWithSpans(working, true, spans);
        var entropy = redactHighEntropyTokensWithSpans(block.text(), spans);
        String finalText = entropy.text();
        List<String> finalLines = Arrays.asList(finalText.split("\\R", -1));
        return new RedactedDocument(finalText, finalLines, spans);
    }

    /** Path-aware redaction combining config-file key=value, YAML block scalars, 11 standard patterns, and Shannon-entropy guard. */
    public static RedactionResult redactForPath(String path, String raw, boolean preserveLineCount) {
        if (raw == null || raw.isEmpty()) return new RedactionResult("", 0);
        int[] configCount = new int[1];
        String working = isConfigOrEnvPath(path)
                ? redactConfigAndYamlLines(path, raw, null, configCount)
                : raw;
        var block = redactSecrets(working, preserveLineCount);
        var entropy = redactHighEntropyTokensWithSpans(block.text(), null);
        return new RedactionResult(entropy.text(), configCount[0] + block.redactedCount() + entropy.redactedCount());
    }

    /** Returns true when raw text matches any of the 11 standard secret patterns. */
    public static boolean containsSecret(String raw) {
        return raw != null && !raw.isEmpty() && SECRET_LIKE.matcher(raw).find();
    }

    /** Path-aware secret check (also catches plaintext config/YAML secrets and high-entropy secret tokens). */
    public static boolean containsSecretForPath(String path, String raw) {
        if (raw == null || raw.isEmpty()) return false;
        if (containsSecret(raw)) return true;
        if (isConfigOrEnvPath(path)) {
            int[] configCount = new int[1];
            redactConfigAndYamlLines(path, raw, null, configCount);
            if (configCount[0] > 0) return true;
        }
        return redactHighEntropyTokensWithSpans(raw, null).redactedCount() > 0;
    }

    /**
     * Neutralizes chat-template control sentinels and spoofed {@code untrusted_source} delimiter tags
     * before embedding untrusted repository content in an LLM prompt.
     */
    public static String neutralizeInjectionSentinels(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        var matcher = INJECTION_SENTINELS.matcher(raw);
        if (!matcher.find()) return raw;
        var sb = new StringBuilder(raw.length() + 16);
        do {
            String hit = matcher.group();
            String safe = hit
                    .replace("<|", "< |")
                    .replace("|>", "| >")
                    .replace("<<", "< <")
                    .replace(">>", "> >")
                    .replace("[", "[ ")
                    .replace("]", " ]")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;");
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(safe));
        } while (matcher.find());
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Wraps untrusted repository text in a provenance-pinned {@code <untrusted_source>} block after
     * redacting secrets (including config-file assignments when applicable) and neutralizing prompt-injection sentinels.
     */
    public static String wrapUntrustedSource(String path, String sha256, String content) {
        return wrapPreRedactedUntrustedSource(path, sha256, redactForPath(path, content, true).text());
    }

    /** Wraps already-redacted text in {@code <untrusted_source>} with sentinel neutralization. */
    public static String wrapPreRedactedUntrustedSource(String path, String sha256, String redactedContent) {
        String safePath = sanitizeAttr(path);
        String safeSha = sanitizeAttr(sha256);
        String safeBody = neutralizeInjectionSentinels(redactedContent);
        return "<untrusted_source path=\"" + safePath + "\" sha256=\"" + safeSha + "\">\n"
                + safeBody
                + "\n</untrusted_source>";
    }

    /** Recursively redacts secrets and neutralizes prompt-injection sentinels on all strings in a map/list payload. */
    public static Map<String, Object> neutralizeMapForLlm(Map<String, ?> input) {
        if (input == null || input.isEmpty()) return Map.of();
        var out = new LinkedHashMap<String, Object>();
        for (var e : input.entrySet()) {
            String key = e.getKey() == null ? "" : neutralizeInjectionSentinels(redactSecrets(e.getKey()).text());
            out.put(key, neutralizeValueForLlm(e.getValue(), 0));
        }
        return out;
    }

    private static Object neutralizeValueForLlm(Object val, int depth) {
        if (val == null) return null;
        if (depth > 12) return "[TRUNCATED]";
        if (val instanceof String s) {
            return neutralizeInjectionSentinels(redactSecrets(s).text());
        }
        if (val instanceof Map<?, ?> m) {
            var copy = new LinkedHashMap<String, Object>();
            for (var e : m.entrySet()) {
                String k = e.getKey() == null ? "" : neutralizeInjectionSentinels(redactSecrets(String.valueOf(e.getKey())).text());
                copy.put(k, neutralizeValueForLlm(e.getValue(), depth + 1));
            }
            return copy;
        }
        if (val instanceof List<?> list) {
            var copy = new ArrayList<Object>(list.size());
            for (var item : list) copy.add(neutralizeValueForLlm(item, depth + 1));
            return copy;
        }
        return val;
    }

    private static String sanitizeAttr(String value) {
        if (value == null) return "";
        return neutralizeInjectionSentinels(value).replaceAll("[\\r\\n\"<>]", "").strip();
    }

    public static String sanitizeText(String raw) {
        if (raw == null) return "";
        String cleaned = SECRET_LIKE.matcher(raw).replaceAll("[REDACTED]");
        cleaned = cleaned.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ").replaceAll("\\s+", " ").strip();
        return cleaned.length() > 320 ? cleaned.substring(0, 320) + "..." : cleaned;
    }

    private static HttpResponse.BodySubscriber<byte[]> boundedBody(int limit) {
        var bytes = new ByteArrayOutputStream();
        return HttpResponse.BodySubscribers.mapping(
                HttpResponse.BodySubscribers.ofByteArrayConsumer(chunk -> chunk.ifPresent(data -> {
                    if (data.length > limit - bytes.size()) {
                        throw new IllegalArgumentException("Response exceeds limit");
                    }
                    bytes.writeBytes(data);
                })),
                ignored -> bytes.toByteArray());
    }

    /** Strips {@code <think>...</think>} blocks and returns visible text plus extracted reasoning. */
    public record SeparatedText(String visible, String reasoning) {}

    public static SeparatedText separateThinking(String content) {
        if (content == null || content.isEmpty()) return new SeparatedText("", "");
        var reasoning = new StringBuilder();
        var matcher = THINK_BLOCK.matcher(content);
        var visible = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            visible.append(content, last, matcher.start());
            String inner = matcher.group();
            int startTag = inner.indexOf('>');
            int endTag = inner.lastIndexOf('<');
            if (startTag >= 0 && endTag > startTag) {
                if (!reasoning.isEmpty()) reasoning.append('\n');
                reasoning.append(inner, startTag + 1, endTag);
            }
            last = matcher.end();
        }
        visible.append(content, last, content.length());
        String vis = visible.toString();
        // Handle unclosed <think> at start
        int unclosed = vis.toLowerCase(Locale.ROOT).indexOf("<think>");
        if (unclosed >= 0) {
            if (!reasoning.isEmpty()) reasoning.append('\n');
            reasoning.append(vis.substring(unclosed + "<think>".length()));
            vis = vis.substring(0, unclosed);
        }
        return new SeparatedText(vis.strip(), reasoning.toString().strip());
    }

    /**
     * Extracts a JSON object safely even when preceded by reasoning traces, prose preamble,
     * or wrapped in a markdown code fence.
     */
    public static String extractJsonObject(String content) {
        if (content == null) return "";
        String cleaned = separateThinking(content).visible().strip();
        if (cleaned.startsWith("{") && cleaned.endsWith("}")) {
            return cleaned;
        }
        // Check fenced ```json ... ``` or ``` ... ``` anywhere in text
        int fenceStart = cleaned.indexOf("```");
        while (fenceStart >= 0) {
            int lineEnd = cleaned.indexOf('\n', fenceStart + 3);
            if (lineEnd < 0) break;
            String lang = cleaned.substring(fenceStart + 3, lineEnd).strip().toLowerCase(Locale.ROOT);
            int fenceEnd = cleaned.indexOf("```", lineEnd + 1);
            if (fenceEnd < 0) break;
            if (lang.isEmpty() || lang.equals("json")) {
                String inner = cleaned.substring(lineEnd + 1, fenceEnd).strip();
                if (inner.startsWith("{") && inner.endsWith("}")) {
                    return inner;
                }
            }
            fenceStart = cleaned.indexOf("```", fenceEnd + 3);
        }
        // Scan for first balanced top-level JSON object {...}
        int firstBrace = cleaned.indexOf('{');
        if (firstBrace < 0) return cleaned;
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = firstBrace; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (inString) {
                if (c == '\\') escape = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return cleaned.substring(firstBrace, i + 1);
                }
            }
        }
        return cleaned;
    }

    /**
     * Stateful SSE chunk filter that suppresses {@code <think>...</think>} blocks and optional leading
     * {@code <conversation_title>...</conversation_title>} tags without stalling streaming.
     */
    public static final class StreamDisplayFilter {
        private final Consumer<String> downstream;
        private final StringBuilder reasoning = new StringBuilder();
        private final StringBuilder pending = new StringBuilder();
        private boolean inThink = false;
        private boolean titleResolved = false;

        public StreamDisplayFilter(Consumer<String> downstream) {
            this.downstream = downstream;
        }

        public void accept(String chunk) {
            if (chunk == null || chunk.isEmpty()) return;
            pending.append(chunk);
            drain(false);
        }

        public void finish() {
            drain(true);
        }

        public String reasoning() {
            return reasoning.toString().strip();
        }

        private void drain(boolean eof) {
            while (!pending.isEmpty()) {
                String buf = pending.toString();
                if (inThink) {
                    int close = buf.toLowerCase(Locale.ROOT).indexOf("</think>");
                    if (close >= 0) {
                        reasoning.append(buf, 0, close);
                        pending.delete(0, close + "</think>".length());
                        inThink = false;
                        continue;
                    }
                    if (!eof && buf.length() > 8) {
                        int safe = buf.length() - 8;
                        reasoning.append(buf, 0, safe);
                        pending.delete(0, safe);
                    } else if (eof) {
                        reasoning.append(buf);
                        pending.setLength(0);
                    }
                    return;
                }

                // Check for <think> tag anywhere in pending buffer
                String lower = buf.toLowerCase(Locale.ROOT);
                int thinkStart = lower.indexOf("<think>");
                if (thinkStart == 0) {
                    pending.delete(0, "<think>".length());
                    inThink = true;
                    continue;
                }

                if (!titleResolved) {
                    String trimmed = buf.stripLeading();
                    String trimmedLow = trimmed.toLowerCase(Locale.ROOT);
                    if (trimmed.isEmpty()) {
                        if (eof) pending.setLength(0);
                        return;
                    }
                    if (trimmedLow.startsWith("<think>")) {
                        int lead = buf.length() - trimmed.length();
                        pending.delete(0, lead + "<think>".length());
                        inThink = true;
                        continue;
                    }
                    if (!eof && "<think>".startsWith(trimmedLow)) {
                        return;
                    }
                    if (trimmedLow.startsWith("<conversation_title>")) {
                        int closeTitle = lower.indexOf("</conversation_title>");
                        if (closeTitle >= 0) {
                            titleResolved = true;
                            int after = closeTitle + "</conversation_title>".length();
                            while (after < buf.length() && Character.isWhitespace(buf.charAt(after))) after++;
                            pending.delete(0, after);
                            continue;
                        }
                        int nl = buf.indexOf('\n');
                        if (nl >= 0 || buf.length() > 300 || eof) {
                            titleResolved = true;
                            int after = nl >= 0 ? nl + 1 : buf.length();
                            while (after < buf.length() && Character.isWhitespace(buf.charAt(after))) after++;
                            pending.delete(0, after);
                            continue;
                        }
                        return;
                    }
                    if (!eof && "<conversation_title>".startsWith(trimmedLow)) {
                        return;
                    }
                    titleResolved = true;
                }

                if (thinkStart > 0) {
                    emit(buf.substring(0, thinkStart));
                    pending.delete(0, thinkStart + "<think>".length());
                    inThink = true;
                    continue;
                }
                if (!eof) {
                    int partialTag = lastPossibleTagStart(buf);
                    if (partialTag >= 0) {
                        if (partialTag > 0) {
                            emit(buf.substring(0, partialTag));
                            pending.delete(0, partialTag);
                        }
                        return;
                    }
                }
                emit(buf);
                pending.setLength(0);
            }
        }

        private static int lastPossibleTagStart(String s) {
            int maxSuffix = Math.min(s.length(), "<think>".length() - 1);
            String lower = s.toLowerCase(Locale.ROOT);
            for (int len = maxSuffix; len >= 1; len--) {
                String tail = lower.substring(s.length() - len);
                if ("<think>".startsWith(tail)) {
                    return s.length() - len;
                }
            }
            return -1;
        }

        private void emit(String text) {
            if (downstream != null && text != null && !text.isEmpty()) {
                downstream.accept(text);
            }
        }
    }
}
