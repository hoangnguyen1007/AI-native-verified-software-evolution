package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.backend.analysis.AnalysisJobEventBroker;
import com.evolution.backend.query.repair.RepairVerificationJobs;
import com.sun.net.httpserver.*;
import java.io.IOException;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Read-only local HTTP server, owned and closed by its embedding application. */
public final class ArchitectureHttpServer implements AutoCloseable {
    public static final String DEFAULT_BIND_HOST = "127.0.0.1";
    private static final int MAX_SSE_CLIENTS = 8;
    private static final int MAX_CHAT_SSE_CLIENTS = 4;
    private static final long DEFAULT_SSE_HEARTBEAT_MILLIS = 15_000L;
    private static final long SSE_MAX_DURATION_MILLIS = TimeUnit.HOURS.toMillis(2);
    private static final java.util.regex.Pattern SAFE_STATIC_PATH = java.util.regex.Pattern.compile("^/[A-Za-z0-9._/-]*$");
    private static final java.util.regex.Pattern SAFE_HOST_HEADER =
            java.util.regex.Pattern.compile("^(?:\\[[0-9a-fA-F:]+\\]|[A-Za-z0-9.-]{1,253})(?::[0-9]{1,5})?$");

    private final ArchitectureQueryApi api;
    private final String bindHost;
    private final boolean trustProxyHeaders;
    private final HttpServer server;
    private final ExecutorService executor;
    private final ExecutorService sseExecutor;
    private final ScheduledThreadPoolExecutor deadlines;
    private final Semaphore slots;
    private final Semaphore graphSlots;
    private final Semaphore sseSlots;
    private final Semaphore chatSseSlots;
    private final long sseHeartbeatMillis;
    private final AtomicBoolean closed = new AtomicBoolean();

    public static boolean trustProxyHeadersFromEnvironment() {
        return trustProxyHeadersFromEnvironment(System::getenv, System::getProperty);
    }

    public static boolean trustProxyHeadersFromEnvironment(
            java.util.function.Function<String, String> env,
            java.util.function.Function<String, String> props) {
        String prop = props.apply("evo.http.trustProxyHeaders");
        String raw = (prop != null && !prop.isBlank()) ? prop.strip() : env.apply("EVO_TRUST_PROXY_HEADERS");
        return raw != null && ("true".equalsIgnoreCase(raw.strip()) || "1".equals(raw.strip()));
    }

    public static String bindHostFromEnvironment() {
        return bindHostFromEnvironment(System::getenv, System::getProperty);
    }

    public static String bindHostFromEnvironment(
            java.util.function.Function<String, String> env,
            java.util.function.Function<String, String> props) {
        String prop = props.apply("evo.http.bindHost");
        if (prop != null && !prop.isBlank()) return prop.strip();
        String envVal = env.apply("EVO_BIND_HOST");
        if (envVal != null && !envVal.isBlank()) return envVal.strip();
        return DEFAULT_BIND_HOST;
    }

    public ArchitectureHttpServer(ArchitectureQueryApi api, int port) throws IOException {
        this(api, DEFAULT_BIND_HOST, port, DEFAULT_SSE_HEARTBEAT_MILLIS, trustProxyHeadersFromEnvironment());
    }
    public ArchitectureHttpServer(ArchitectureQueryApi api, String bindHost, int port) throws IOException {
        this(api, bindHost, port, DEFAULT_SSE_HEARTBEAT_MILLIS, trustProxyHeadersFromEnvironment());
    }
    ArchitectureHttpServer(ArchitectureQueryApi api, int port, long sseHeartbeatMillis) throws IOException {
        this(api, DEFAULT_BIND_HOST, port, sseHeartbeatMillis, trustProxyHeadersFromEnvironment());
    }
    public ArchitectureHttpServer(ArchitectureQueryApi api, String bindHost, int port,
            long sseHeartbeatMillis, boolean trustProxyHeaders) throws IOException {
        this.api=Objects.requireNonNull(api);
        if (port<0 || port>65535) throw new IllegalArgumentException("Invalid port");
        if (sseHeartbeatMillis < 10L || sseHeartbeatMillis > 60_000L) throw new IllegalArgumentException("Invalid SSE heartbeat");
        String resolvedHost = (bindHost == null || bindHost.isBlank()) ? DEFAULT_BIND_HOST : bindHost.strip();
        if (resolvedHost.length() > 255 || resolvedHost.chars().anyMatch(c -> c <= 0x20 || c == 0x7F)) {
            throw new IllegalArgumentException("Invalid bind host");
        }
        this.bindHost = resolvedHost;
        this.trustProxyHeaders = trustProxyHeaders;
        this.sseHeartbeatMillis = sseHeartbeatMillis;
        server=HttpServer.create(new InetSocketAddress(InetAddress.getByName(this.bindHost),port),16);
        int workers=api.limits().concurrentRequests();
        int graphWorkers=workers >= 4 ? workers - 2 : workers;
        slots=new Semaphore(workers);
        graphSlots=new Semaphore(graphWorkers);
        sseSlots=new Semaphore(MAX_SSE_CLIENTS);
        chatSseSlots=new Semaphore(MAX_CHAT_SSE_CLIENTS);
        sseExecutor=Executors.newVirtualThreadPerTaskExecutor();
        deadlines=new ScheduledThreadPoolExecutor(1,runnable -> { var thread=new Thread(runnable,"architecture-http-deadlines"); thread.setDaemon(true); return thread; });
        deadlines.setRemoveOnCancelPolicy(true);
        executor=new ThreadPoolExecutor(workers + 4,workers + 4,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(workers + 16),
                runnable -> { var thread=new Thread(runnable,"architecture-http"); thread.setDaemon(true); return thread; },
                new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(task -> {
            try {
                executor.execute(task);
            } catch (RejectedExecutionException overloaded) {
                // Never run slow handlers on the selector thread; fail fast via virtual thread if overloaded.
                try {
                    sseExecutor.execute(task);
                } catch (RejectedExecutionException shuttingDown) {
                    // Server is closing.
                }
            }
        });
        server.createContext("/",this::handle);
    }
    public void start() { if (closed.get()) throw new IllegalStateException("Server closed"); server.start(); }
    public String bindHost() { return bindHost; }
    public boolean trustProxyHeaders() { return trustProxyHeaders; }
    public URI uri() {
        String host = (bindHost.equals("0.0.0.0") || bindHost.equals("::") || bindHost.equals("[::]"))
                ? DEFAULT_BIND_HOST
                : (bindHost.contains(":") && !bindHost.startsWith("[") ? "[" + bindHost + "]" : bindHost);
        return URI.create("http://" + host + ":" + server.getAddress().getPort());
    }
    private void handle(HttpExchange exchange) throws IOException {
        boolean acquired=false;
        boolean acquiredGraph=false;
        boolean handedOffToSse=false;
        var expired=new AtomicBoolean();
        var handlerThread=new java.util.concurrent.atomic.AtomicReference<>(Thread.currentThread());
        ScheduledFuture<?> deadline=deadlines.schedule(() -> {
            expired.set(true);
            var worker=handlerThread.get();
            if (worker!=null) worker.interrupt();
            exchange.close();
        },api.limits().queryTimeoutMillis(),TimeUnit.MILLISECONDS);
        try {
            String rawPath = exchange.getRequestURI().getRawPath();
            String method = exchange.getRequestMethod();
            boolean isOAuthCallback = "GET".equals(method)
                    && (rawPath.equals("/api/v1/auth/github/callback")
                            || rawPath.equals("/api/v1/auth/github/manifest/callback"));
            boolean isWebhook = "POST".equals(method) && rawPath.equals("/api/v1/github/webhooks");
            if (!isAllowedOrigin(exchange, isOAuthCallback || isWebhook)) {
                sendJsonResponse(exchange, api.error(403,"FORBIDDEN_ORIGIN"));
                return;
            }
            if (!rawPath.startsWith("/api/") && !rawPath.equals("/api/v1")) {
                serveStatic(exchange, rawPath);
                return;
            }
            // Resolve optional multi-user session cookie or Bearer token into RequestContext for all API & SSE lanes.
            var authApi = api.auth();
            var activeReq = authApi == null ? null
                    : authApi.resolveRequestContext(
                            exchange.getRequestHeaders().get("Cookie"),
                            exchange.getRequestHeaders().getFirst("Authorization"));
            if (isWebhook) {
                // Dedicated webhook path: captures exact raw bytes for HMAC-SHA256 verification before JSON parsing.
                byte[] rawBody = exchange.getRequestBody().readNBytes(api.limits().requestBytes() + 1);
                ArchitectureQueryApi.Response whResp = rawBody.length > api.limits().requestBytes()
                        ? api.error(413, "REQUEST_TOO_LARGE")
                        : (api.github() == null
                                ? api.error(503, "GITHUB_INTEGRATION_UNAVAILABLE")
                                : api.github().handleWebhook(
                                        exchange.getRequestHeaders().getFirst("X-GitHub-Event"),
                                        exchange.getRequestHeaders().getFirst("X-GitHub-Delivery"),
                                        exchange.getRequestHeaders().getFirst("X-Hub-Signature-256"),
                                        rawBody));
                sendJsonResponse(exchange, whResp);
                return;
            }
            if (activeReq != null && activeReq.cookieAuthenticated() && isMutatingMethod(method)
                    && !isCsrfExempt(method, rawPath)) {
                String suppliedCsrf = exchange.getRequestHeaders().getFirst(com.evolution.backend.auth.AuthHttpApi.CSRF_HEADER);
                if (!com.evolution.backend.auth.AuthHttpApi.isCsrfValid(activeReq.user().csrfToken(), suppliedCsrf)) {
                    sendJsonResponse(exchange, api.error(403, "CSRF_TOKEN_INVALID"));
                    return;
                }
            }
            boolean multiUserAuthDenied = authApi != null && authApi.isMultiUserMode()
                    && (activeReq == null || !activeReq.user().isAuthenticated());
            boolean patScopeDenied = activeReq != null
                    && activeReq.user().authMethod() == com.evolution.backend.auth.AuthenticatedUser.AuthMethod.PERSONAL_ACCESS_TOKEN
                    && !ArchitectureQueryApi.isPatAuthorized(activeReq.user().scopes(), method, rawPath);
            if (isJobEventsRoute(rawPath)) {
                if (multiUserAuthDenied) {
                    sendJsonResponse(exchange, api.error(401, "AUTH_REQUIRED"));
                    return;
                }
                if (patScopeDenied) {
                    sendJsonResponse(exchange, api.error(403, "INSUFFICIENT_TOKEN_SCOPE"));
                    return;
                }
                // Dedicated virtual-thread SSE lane: never consumes query/graph slots or 5-min request deadline.
                if (deadline != null) {
                    deadline.cancel(false);
                    deadline = null;
                }
                final var sseReq = activeReq;
                handedOffToSse = com.evolution.backend.auth.RequestContext.runWith(sseReq, () -> {
                    try {
                        return handleJobEventsDispatch(exchange, rawPath);
                    } catch (IOException io) {
                        throw new java.io.UncheckedIOException(io);
                    }
                });
                return;
            }
            if (isRepairJobEventsRoute(rawPath)) {
                if (multiUserAuthDenied) {
                    sendJsonResponse(exchange, api.error(401, "AUTH_REQUIRED"));
                    return;
                }
                if (patScopeDenied) {
                    sendJsonResponse(exchange, api.error(403, "INSUFFICIENT_TOKEN_SCOPE"));
                    return;
                }
                if (deadline != null) {
                    deadline.cancel(false);
                    deadline = null;
                }
                final var sseReq = activeReq;
                handedOffToSse = com.evolution.backend.auth.RequestContext.runWith(sseReq, () -> {
                    try {
                        return handleRepairJobEventsDispatch(exchange, rawPath, sseReq);
                    } catch (IOException io) {
                        throw new java.io.UncheckedIOException(io);
                    }
                });
                return;
            }
            if (isRepairJobMarkdownRoute(rawPath)) {
                if (multiUserAuthDenied) {
                    sendJsonResponse(exchange, api.error(401, "AUTH_REQUIRED"));
                    return;
                }
                if (patScopeDenied) {
                    sendJsonResponse(exchange, api.error(403, "INSUFFICIENT_TOKEN_SCOPE"));
                    return;
                }
                final var mdReq = activeReq;
                com.evolution.backend.auth.RequestContext.runWithVoid(mdReq, () -> {
                    try {
                        handleRepairJobMarkdown(exchange, rawPath);
                    } catch (IOException io) {
                        throw new java.io.UncheckedIOException(io);
                    }
                });
                return;
            }
            if (rawPath.startsWith("/api/v1/graphs/") && rawPath.endsWith("/architecture-chat/stream")) {
                if (multiUserAuthDenied) {
                    sendJsonResponse(exchange, api.error(401, "AUTH_REQUIRED"));
                    return;
                }
                if (patScopeDenied) {
                    sendJsonResponse(exchange, api.error(403, "INSUFFICIENT_TOKEN_SCOPE"));
                    return;
                }
                // Dedicated virtual-thread chat SSE lane so streaming LLM turns never hold graphSlots or query slots.
                if (deadline != null) {
                    deadline.cancel(false);
                    deadline = null;
                }
                final var chatReq = activeReq;
                handedOffToSse = com.evolution.backend.auth.RequestContext.runWith(chatReq, () -> {
                    try {
                        return handleChatStreamDispatch(exchange, rawPath, chatReq);
                    } catch (IOException io) {
                        throw new java.io.UncheckedIOException(io);
                    }
                });
                return;
            }
            try {
                if (!(acquired=slots.tryAcquire(Math.min(api.limits().queryTimeoutMillis(), 30_000L), TimeUnit.MILLISECONDS))) {
                    sendJsonResponse(exchange, api.error(503,"BUSY"));
                    return;
                }
                if (rawPath.startsWith("/api/v1/graphs/") &&
                        !(acquiredGraph=graphSlots.tryAcquire(Math.min(api.limits().queryTimeoutMillis(), 30_000L), TimeUnit.MILLISECONDS))) {
                    sendJsonResponse(exchange, api.error(503,"BUSY"));
                    return;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                sendJsonResponse(exchange, api.error(503,"BUSY"));
                return;
            }
            ArchitectureQueryApi.Response response;
            String rawQuery = exchange.getRequestURI().getRawQuery();
            boolean queryAllowed = isQueryStringAllowed(method, rawPath);
            boolean acceptOk = isBrowserRedirectOrDownloadRoute(rawPath)
                    || acceptable(exchange.getRequestHeaders().containsKey("Accept") ? String.join(",",exchange.getRequestHeaders().get("Accept")) : null);
            if (rawQuery != null && !queryAllowed) response=api.error(400,"INVALID_REQUEST");
            else if (!acceptOk) response=api.error(406,"NOT_ACCEPTABLE");
            else if (exchange.getRequestHeaders().containsKey("Content-Encoding")) response=api.error(415,"UNSUPPORTED_MEDIA_TYPE");
            else if (exchange.getRequestHeaders().getOrDefault("Content-Type",List.of()).size()>1) response=api.error(415,"UNSUPPORTED_MEDIA_TYPE");
            else {
                byte[] body=exchange.getRequestBody().readNBytes(api.limits().requestBytes()+1);
                String serverOrigin = resolveServerOrigin(exchange);
                response=com.evolution.backend.auth.RequestContext.runWith(activeReq, serverOrigin, () ->
                        api.handle(method,rawPath,rawQuery,
                                exchange.getRequestHeaders().getFirst("Content-Type"),body,serverOrigin,
                                () -> closed.get() || expired.get() || Thread.currentThread().isInterrupted()));
            }
            sendJsonResponse(exchange, response);
        } catch (java.io.UncheckedIOException wrapped) {
            throw wrapped.getCause();
        } finally {
            if (!handedOffToSse) exchange.close();
            if (deadline!=null) deadline.cancel(false);
            if (acquiredGraph) graphSlots.release();
            if (acquired) slots.release();
            Thread.interrupted();
        }
    }

    private static boolean isQueryStringAllowed(String method, String rawPath) {
        if (!"GET".equals(method)) {
            return false;
        }
        return rawPath.equals("/api/v1/auth/github/login")
                || rawPath.equals("/api/v1/auth/github/callback")
                || rawPath.equals("/api/v1/auth/github/manifest/callback")
                || rawPath.equals("/api/v1/github/refs")
                || rawPath.equals("/api/v1/github/auth/status")
                || rawPath.equals("/api/v1/github/orgs")
                || rawPath.equals("/api/v1/github/user/repos")
                || (rawPath.startsWith("/api/v1/github/connections/")
                        && (rawPath.endsWith("/commits")
                                || rawPath.endsWith("/pulls")
                                || rawPath.endsWith("/pulls/enriched")
                                || rawPath.endsWith("/ci-workflow")))
                || rawPath.equals("/api/v1/mcp/setup")
                || (rawPath.startsWith("/api/v1/graphs/")
                        && (rawPath.endsWith("/codeowners")
                                || rawPath.endsWith("/sarif")
                                || rawPath.endsWith("/advisory-links")
                                || rawPath.endsWith("/wiki-dossier")));
    }

    private static boolean isMutatingMethod(String method) {
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method) || "DELETE".equals(method);
    }

    private static boolean isCsrfExempt(String method, String rawPath) {
        return ("POST".equals(method) && rawPath.equals("/api/v1/auth/register"))
                || ("POST".equals(method) && rawPath.equals("/api/v1/auth/login"))
                || rawPath.equals("/api/v1/auth/github/device/start")
                || rawPath.equals("/api/v1/auth/github/device/poll")
                || ("POST".equals(method) && rawPath.equals("/api/v1/github/auth/session"))
                || rawPath.equals("/api/v1/github/webhooks");
    }

    private static boolean isBrowserRedirectOrDownloadRoute(String rawPath) {
        return rawPath.equals("/api/v1/auth/github/login")
                || rawPath.equals("/api/v1/auth/github/callback")
                || rawPath.equals("/api/v1/auth/github/manifest/callback")
                || (rawPath.startsWith("/api/v1/graphs/") && (rawPath.endsWith("/sarif") || rawPath.endsWith("/wiki-dossier")))
                || (rawPath.startsWith("/api/v1/github/connections/") && rawPath.endsWith("/ci-workflow"));
    }

    private String resolveServerOrigin(HttpExchange exchange) {
        String directHost = singleValidHost(exchange.getRequestHeaders().get("Host"));
        if (trustProxyHeaders) {
            String fwdProto = firstForwardedProto(exchange.getRequestHeaders().get("X-Forwarded-Proto"));
            String fwdHost = firstForwardedHost(exchange.getRequestHeaders().get("X-Forwarded-Host"));
            if (fwdProto != null || fwdHost != null) {
                String proto = fwdProto != null ? fwdProto : "http";
                String host = fwdHost != null ? fwdHost : directHost;
                if (host != null) {
                    return formatOrigin(proto, host);
                }
            }
        }
        if (directHost != null) {
            return formatOrigin("http", directHost);
        }
        return uri().toString();
    }

    private static String formatOrigin(String proto, String host) {
        String cleanProto = proto.toLowerCase(Locale.ROOT);
        String cleanHost = host.toLowerCase(Locale.ROOT);
        if ("https".equals(cleanProto) && cleanHost.endsWith(":443")) {
            cleanHost = cleanHost.substring(0, cleanHost.length() - 4);
        } else if ("http".equals(cleanProto) && cleanHost.endsWith(":80")) {
            cleanHost = cleanHost.substring(0, cleanHost.length() - 3);
        }
        return cleanProto + "://" + cleanHost;
    }

    private static String singleValidHost(List<String> values) {
        if (values == null || values.size() != 1) return null;
        return parseValidHost(values.getFirst());
    }

    private static String firstForwardedHost(List<String> values) {
        if (values == null || values.isEmpty()) return null;
        String first = values.getFirst();
        if (first == null || first.isBlank()) return null;
        int comma = first.indexOf(',');
        String token = comma >= 0 ? first.substring(0, comma) : first;
        return parseValidHost(token);
    }

    private static String firstForwardedProto(List<String> values) {
        if (values == null || values.isEmpty()) return null;
        String first = values.getFirst();
        if (first == null || first.isBlank()) return null;
        int comma = first.indexOf(',');
        String token = (comma >= 0 ? first.substring(0, comma) : first).strip().toLowerCase(Locale.ROOT);
        return ("http".equals(token) || "https".equals(token)) ? token : null;
    }

    private static String parseValidHost(String raw) {
        if (raw == null) return null;
        String trimmed = raw.strip();
        if (trimmed.isEmpty() || !SAFE_HOST_HEADER.matcher(trimmed).matches()) {
            return null;
        }
        int lastColon = trimmed.lastIndexOf(':');
        if (lastColon > 0 && (!trimmed.startsWith("[") || trimmed.indexOf(']') < lastColon)) {
            try {
                int port = Integer.parseInt(trimmed.substring(lastColon + 1));
                if (port < 1 || port > 65535) return null;
            } catch (NumberFormatException invalid) {
                return null;
            }
        }
        return trimmed;
    }

    private boolean handleChatStreamDispatch(HttpExchange exchange, String rawPath,
            com.evolution.backend.auth.RequestContext.ActiveRequest activeReq) throws IOException {
        if (!chatSseSlots.tryAcquire()) {
            sendJsonResponse(exchange, api.error(503, "BUSY"));
            return false;
        }
        try {
            sseExecutor.execute(() -> {
                var expired = new AtomicBoolean();
                var thread = Thread.currentThread();
                ScheduledFuture<?> chatDeadline = deadlines.schedule(() -> {
                    expired.set(true);
                    thread.interrupt();
                    exchange.close();
                }, Math.max(api.limits().queryTimeoutMillis(), 180_000L), TimeUnit.MILLISECONDS);
                try {
                    com.evolution.backend.auth.RequestContext.runWithVoid(activeReq, () -> {
                        try {
                            handleArchitectureChatStream(exchange, rawPath,
                                    () -> closed.get() || expired.get() || Thread.currentThread().isInterrupted());
                        } catch (IOException ignored) {
                            // Client disconnected cleanly.
                        }
                    });
                } finally {
                    chatDeadline.cancel(false);
                    try {
                        exchange.close();
                    } catch (Exception ignored) {}
                    chatSseSlots.release();
                }
            });
            return true;
        } catch (RejectedExecutionException shuttingDown) {
            chatSseSlots.release();
            sendJsonResponse(exchange, api.error(503, "BUSY"));
            return false;
        }
    }

    private static boolean isJobEventsRoute(String rawPath) {
        if (!rawPath.startsWith("/api/v1/analysis/jobs/") || !rawPath.endsWith("/events")) {
            return false;
        }
        String[] parts = rawPath.split("/", -1);
        return parts.length == 7 && "jobs".equals(parts[4]) && "events".equals(parts[6]);
    }

    private static boolean isRepairJobEventsRoute(String rawPath) {
        if (!rawPath.startsWith("/api/v1/repairs/jobs/") || !rawPath.endsWith("/events")) {
            return false;
        }
        String[] parts = rawPath.split("/", -1);
        return parts.length == 7 && "repairs".equals(parts[3]) && "jobs".equals(parts[4]) && "events".equals(parts[6]);
    }

    private static boolean isRepairJobMarkdownRoute(String rawPath) {
        if (!rawPath.startsWith("/api/v1/repairs/jobs/") || !rawPath.endsWith("/report.md")) {
            return false;
        }
        String[] parts = rawPath.split("/", -1);
        return parts.length == 7 && "repairs".equals(parts[3]) && "jobs".equals(parts[4]) && "report.md".equals(parts[6]);
    }

    private void handleRepairJobMarkdown(HttpExchange exchange, String rawPath) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendJsonResponse(exchange, api.error(405, "METHOD_NOT_ALLOWED"));
            return;
        }
        if (exchange.getRequestURI().getRawQuery() != null) {
            sendJsonResponse(exchange, api.error(400, "INVALID_REQUEST"));
            return;
        }
        String[] parts = rawPath.split("/", -1);
        UUID jobId;
        try {
            jobId = UUID.fromString(parts[5]);
        } catch (IllegalArgumentException invalid) {
            sendJsonResponse(exchange, api.error(400, "INVALID_REQUEST"));
            return;
        }
        Optional<String> md;
        try {
            md = api.repairJobReportMarkdown(jobId);
        } catch (com.evolution.backend.query.repair.SafePatchApplier.AccessDeniedException denied) {
            sendJsonResponse(exchange, api.error(403, "APPLY_ACCESS_DENIED"));
            return;
        }
        if (md.isEmpty()) {
            sendJsonResponse(exchange, api.error(404, "REPAIR_JOB_NOT_FOUND"));
            return;
        }
        byte[] bytes = md.orElseThrow().getBytes(StandardCharsets.UTF_8);
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/markdown; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private boolean handleRepairJobEventsDispatch(HttpExchange exchange, String rawPath,
            com.evolution.backend.auth.RequestContext.ActiveRequest activeReq) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendJsonResponse(exchange, api.error(405, "METHOD_NOT_ALLOWED"));
            return false;
        }
        if (exchange.getRequestURI().getRawQuery() != null) {
            sendJsonResponse(exchange, api.error(400, "INVALID_REQUEST"));
            return false;
        }
        String accept = exchange.getRequestHeaders().containsKey("Accept")
                ? String.join(",", exchange.getRequestHeaders().get("Accept"))
                : null;
        if (!acceptableSse(accept)) {
            sendJsonResponse(exchange, api.error(406, "NOT_ACCEPTABLE"));
            return false;
        }
        var jobs = api.repairVerificationJobs();
        if (jobs == null) {
            sendJsonResponse(exchange, api.error(503, "REPAIR_VERIFICATION_JOBS_UNAVAILABLE"));
            return false;
        }
        String[] parts = rawPath.split("/", -1);
        UUID jobId;
        try {
            jobId = UUID.fromString(parts[5]);
        } catch (IllegalArgumentException invalid) {
            sendJsonResponse(exchange, api.error(400, "INVALID_REQUEST"));
            return false;
        }
        final String ownerUserId = com.evolution.backend.auth.RequestContext.currentUserId();
        try {
            if (jobs.get(ownerUserId, jobId).isEmpty()) {
                sendJsonResponse(exchange, api.error(404, "REPAIR_JOB_NOT_FOUND"));
                return false;
            }
        } catch (com.evolution.backend.query.repair.SafePatchApplier.AccessDeniedException denied) {
            sendJsonResponse(exchange, api.error(403, "APPLY_ACCESS_DENIED"));
            return false;
        }
        long afterSeq = 0L;
        String rawLastId = exchange.getRequestHeaders().getFirst("Last-Event-ID");
        if (rawLastId != null && !rawLastId.isBlank()) {
            try {
                afterSeq = Long.parseLong(rawLastId.trim());
                if (afterSeq < 0L) {
                    sendJsonResponse(exchange, api.error(400, "INVALID_REQUEST"));
                    return false;
                }
            } catch (NumberFormatException invalid) {
                sendJsonResponse(exchange, api.error(400, "INVALID_REQUEST"));
                return false;
            }
        }
        if (!sseSlots.tryAcquire()) {
            sendJsonResponse(exchange, api.error(503, "BUSY"));
            return false;
        }
        final long startSeq = afterSeq;
        try {
            sseExecutor.execute(() -> com.evolution.backend.auth.RequestContext.runWithVoid(activeReq,
                    () -> streamRepairJobEvents(exchange, jobs, ownerUserId, jobId, startSeq)));
            return true;
        } catch (RejectedExecutionException shuttingDown) {
            sseSlots.release();
            sendJsonResponse(exchange, api.error(503, "BUSY"));
            return false;
        }
    }

    private void streamRepairJobEvents(HttpExchange exchange, RepairVerificationJobs jobs, String ownerUserId,
            UUID jobId, long afterSeq) {
        long hardDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SSE_MAX_DURATION_MILLIS);
        var queue = new LinkedBlockingQueue<RepairVerificationJobs.JobEvent>(256);
        AutoCloseable subscription = null;
        try {
            subscription = jobs.subscribe(ownerUserId, jobId, afterSeq, queue::offer);
            var headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "text/event-stream; charset=utf-8");
            headers.set("Cache-Control", "no-store");
            headers.set("Connection", "keep-alive");
            headers.set("X-Accel-Buffering", "no");
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            boolean terminalSent = false;
            while (!closed.get() && !Thread.currentThread().isInterrupted() && System.nanoTime() < hardDeadlineNanos) {
                var ev = queue.poll(sseHeartbeatMillis, TimeUnit.MILLISECONDS);
                if (ev == null) {
                    var current = jobs.get(ownerUserId, jobId);
                    if (terminalSent || current.isEmpty() || current.orElseThrow().terminal()) {
                        if (!terminalSent && current.isPresent()) {
                            var st = current.orElseThrow().stage();
                            String doneEvt = st == RepairVerificationJobs.Stage.REPORT_READY ? "completed" : "failed";
                            String payload = CanonicalJson.write(Map.of("jobId", jobId.toString(), "stage", st.name()));
                            out.write(("event: " + doneEvt + "\ndata: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        }
                        break;
                    }
                    out.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    continue;
                }
                out.write(RepairVerificationJobs.formatSseEvent(ev).getBytes(StandardCharsets.UTF_8));
                if (RepairVerificationJobs.isTerminalStage(ev.stage())) {
                    String doneEvt = ev.stage() == RepairVerificationJobs.Stage.REPORT_READY ? "completed" : "failed";
                    String payload = CanonicalJson.write(Map.of(
                            "jobId", jobId.toString(),
                            "stage", ev.stage().name(),
                            "message", ev.message()));
                    out.write(("event: " + doneEvt + "\ndata: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    terminalSent = true;
                    break;
                }
                out.flush();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // Client disconnected or job removed.
        } finally {
            if (subscription != null) {
                try { subscription.close(); } catch (Exception ignored) {}
            }
            try { exchange.close(); } catch (Exception ignored) {}
            sseSlots.release();
        }
    }

    private boolean handleJobEventsDispatch(HttpExchange exchange, String rawPath) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendJsonResponse(exchange, api.error(405, "METHOD_NOT_ALLOWED"));
            return false;
        }
        if (exchange.getRequestURI().getRawQuery() != null) {
            sendJsonResponse(exchange, api.error(400, "INVALID_REQUEST"));
            return false;
        }
        String accept = exchange.getRequestHeaders().containsKey("Accept")
                ? String.join(",", exchange.getRequestHeaders().get("Accept"))
                : null;
        if (!acceptableSse(accept)) {
            sendJsonResponse(exchange, api.error(406, "NOT_ACCEPTABLE"));
            return false;
        }
        long lastEventId = 0L;
        String rawLastId = exchange.getRequestHeaders().getFirst("Last-Event-ID");
        if (rawLastId != null && !rawLastId.isBlank()) {
            try {
                lastEventId = Long.parseLong(rawLastId.trim());
                if (lastEventId < 0L) {
                    sendJsonResponse(exchange, api.error(400, "INVALID_REQUEST"));
                    return false;
                }
            } catch (NumberFormatException invalid) {
                sendJsonResponse(exchange, api.error(400, "INVALID_REQUEST"));
                return false;
            }
        }
        if (!sseSlots.tryAcquire()) {
            sendJsonResponse(exchange, api.error(503, "BUSY"));
            return false;
        }
        boolean released = false;
        try {
            var handshake = new AnalysisJobApi.SseHandshake(lastEventId);
            AnalysisJobApi.SSE_HANDSHAKE.set(handshake);
            ArchitectureQueryApi.Response probe;
            try {
                probe = api.handle("GET", rawPath, null, new byte[0], closed::get);
            } finally {
                AnalysisJobApi.SSE_HANDSHAKE.remove();
            }
            if (probe.status() != 200 || handshake.subscription == null) {
                sseSlots.release();
                released = true;
                sendJsonResponse(exchange, probe);
                return false;
            }
            var subscription = handshake.subscription;
            try {
                sseExecutor.execute(() -> streamJobEvents(exchange, subscription));
                return true;
            } catch (RejectedExecutionException shuttingDown) {
                subscription.close();
                sseSlots.release();
                released = true;
                sendJsonResponse(exchange, api.error(503, "BUSY"));
                return false;
            }
        } catch (RuntimeException | IOException failed) {
            if (!released) {
                sseSlots.release();
            }
            throw failed;
        }
    }

    private void streamJobEvents(HttpExchange exchange, AnalysisJobEventBroker.Subscription subscription) {
        long hardDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SSE_MAX_DURATION_MILLIS);
        try {
            var headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "text/event-stream; charset=utf-8");
            headers.set("Cache-Control", "no-store");
            headers.set("Connection", "keep-alive");
            headers.set("X-Accel-Buffering", "no");
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            while (!closed.get() && !Thread.currentThread().isInterrupted() && System.nanoTime() < hardDeadlineNanos) {
                var event = subscription.poll(sseHeartbeatMillis, TimeUnit.MILLISECONDS);
                if (event == null) {
                    if (subscription.isTerminalDelivered() || subscription.isClosed() || closed.get()) {
                        break;
                    }
                    out.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    continue;
                }
                String frame = "id: " + event.seq() + "\nevent: " + event.event() + "\ndata: " + event.json() + "\n\n";
                out.write(frame.getBytes(StandardCharsets.UTF_8));
                out.flush();
                if (event.isTerminal()) {
                    break;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException disconnected) {
            // Client disconnected; clean unsubscribe happens in finally without cancelling the job.
        } finally {
            subscription.close();
            try {
                exchange.close();
            } catch (Exception ignored) {
            }
            sseSlots.release();
        }
    }
    private void handleArchitectureChatStream(HttpExchange exchange, String rawPath, java.util.function.BooleanSupplier cancelled) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendJsonResponse(exchange, api.error(405, "METHOD_NOT_ALLOWED"));
            return;
        }
        String[] parts = rawPath.split("/", -1);
        if (parts.length != 7 || !"graphs".equals(parts[3]) || !"architecture-chat".equals(parts[5]) || !"stream".equals(parts[6])) {
            sendJsonResponse(exchange, api.error(404, "NOT_FOUND"));
            return;
        }
        ContentDigest graphId;
        try { graphId = new ContentDigest(parts[4]); }
        catch (IllegalArgumentException invalid) {
            sendJsonResponse(exchange, api.error(400, "INVALID_REQUEST"));
            return;
        }
        if (exchange.getRequestURI().getRawQuery() != null) {
            sendJsonResponse(exchange, api.error(400, "INVALID_REQUEST"));
            return;
        }
        if (exchange.getRequestHeaders().containsKey("Content-Encoding")
                || exchange.getRequestHeaders().getOrDefault("Content-Type", List.of()).size() > 1) {
            sendJsonResponse(exchange, api.error(415, "UNSUPPORTED_MEDIA_TYPE"));
            return;
        }
        byte[] body = exchange.getRequestBody().readNBytes(api.limits().requestBytes() + 1);
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        var started = new AtomicBoolean(false);
        var clientDisconnected = new AtomicBoolean(false);
        var streamOut = new OutputStream[1];
        java.util.function.BooleanSupplier streamCancelled = () -> cancelled.getAsBoolean() || clientDisconnected.get();
        Runnable ensureStarted = () -> {
            if (clientDisconnected.get()) return;
            if (started.compareAndSet(false, true)) {
                try {
                    var headers = exchange.getResponseHeaders();
                    headers.set("Content-Type", "text/event-stream; charset=utf-8");
                    headers.set("Cache-Control", "no-store");
                    headers.set("X-Content-Type-Options", "nosniff");
                    headers.set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
                    exchange.sendResponseHeaders(200, 0);
                    streamOut[0] = exchange.getResponseBody();
                } catch (IOException disconnected) {
                    clientDisconnected.set(true);
                    streamOut[0] = null;
                    exchange.close();
                }
            }
        };
        api.streamArchitectureChat(graphId, body, contentType, streamCancelled, new ArchitectureQueryApi.SseEmitter() {
            private synchronized void writeEvent(String event, String json) {
                if (clientDisconnected.get()) return;
                ensureStarted.run();
                if (streamOut[0] == null || clientDisconnected.get()) return;
                try {
                    byte[] bytes = ("event: " + event + "\ndata: " + json + "\n\n").getBytes(StandardCharsets.UTF_8);
                    streamOut[0].write(bytes);
                    streamOut[0].flush();
                } catch (IOException disconnected) {
                    clientDisconnected.set(true);
                    streamOut[0] = null;
                    exchange.close();
                }
            }
            @Override public void stage(String message) { writeEvent("stage", CanonicalJson.write(Map.of("message", message))); }
            @Override public void tool(Map<String, Object> payload) { writeEvent("tool", CanonicalJson.write(payload)); }
            @Override public void delta(String text) { writeEvent("delta", CanonicalJson.write(Map.of("text", text))); }
            @Override public void complete(byte[] envelopeJson) { writeEvent("complete", new String(envelopeJson, StandardCharsets.UTF_8)); }
            @Override public void error(int status, String code, String message) {
                if (clientDisconnected.get()) return;
                if (!started.get()) {
                    try { sendJsonResponse(exchange, api.error(status, code)); }
                    catch (IOException disconnected) { clientDisconnected.set(true); exchange.close(); }
                    return;
                }
                writeEvent("error", CanonicalJson.write(Map.of("code", code, "message", message)));
            }
        });
    }

    private void sendJsonResponse(HttpExchange exchange, ArchitectureQueryApi.Response response) throws IOException {
        var headers=exchange.getResponseHeaders();
        headers.set("Content-Type","application/json; charset=utf-8");
        headers.set("Cache-Control","no-store");
        headers.set("X-Content-Type-Options","nosniff");
        headers.set("Content-Security-Policy","default-src 'none'; frame-ancestors 'none'");
        for (var entry : response.headers().entrySet()) {
            String key = entry.getKey();
            if ("Content-Type".equalsIgnoreCase(key) || "Cache-Control".equalsIgnoreCase(key)
                    || "Location".equalsIgnoreCase(key) || "Content-Disposition".equalsIgnoreCase(key)) {
                if (!entry.getValue().isEmpty()) {
                    headers.set(key, entry.getValue().getFirst());
                }
            } else {
                for (String value : entry.getValue()) {
                    headers.add(key, value);
                }
            }
        }
        if (response.status()==405) {
            String rawPath = exchange.getRequestURI().getRawPath();
            String allow = (rawPath.startsWith("/api/v1/analysis/jobs/") && (rawPath.endsWith("/events") || rawPath.endsWith("/progress")))
                    ? "GET"
                    : ArchitectureQueryApi.allowedMethods(rawPath);
            headers.set("Allow", allow);
        }
        byte[] bytes=response.body();
        long respLen = (exchange.getRequestMethod().equals("HEAD") || (bytes.length == 0 && response.status() >= 300 && response.status() < 400))
                ? -1 : bytes.length;
        exchange.sendResponseHeaders(response.status(), respLen);
        if (respLen > 0) exchange.getResponseBody().write(bytes);
    }
    private void serveStatic(HttpExchange exchange, String rawPath) throws IOException {
        if (!Set.of("GET","HEAD").contains(exchange.getRequestMethod())) {
            var headers = exchange.getResponseHeaders();
            headers.set("Allow", "GET, HEAD");
            headers.set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        if (rawPath.contains("..") || rawPath.contains("//") || !SAFE_STATIC_PATH.matcher(rawPath).matches()) {
            exchange.sendResponseHeaders(400, -1);
            return;
        }
        String path = rawPath.equals("/") ? "/index.html" : rawPath;
        byte[] bytes;
        try (var in = ArchitectureHttpServer.class.getResourceAsStream("/web" + path)) {
            if (in == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            bytes = in.readAllBytes();
        }
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", mimeType(path));
        headers.set("Cache-Control", "no-cache");
        headers.set("X-Content-Type-Options", "nosniff");
        boolean allowGitHubAvatars = api.auth() != null
                && (api.auth().isMultiUserMode()
                        || api.auth().oauthService().config().oauthWebEnabled()
                        || api.auth().oauthService().isDeviceFlowEnabled()
                        || api.auth().oauthService().isGitHubSetupConfigured());
        String imgSrc = allowGitHubAvatars
                ? "img-src 'self' data: https://avatars.githubusercontent.com"
                : "img-src 'self' data:";
        headers.set("Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                        + imgSrc
                        + "; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'");
        exchange.sendResponseHeaders(200, exchange.getRequestMethod().equals("HEAD") ? -1 : bytes.length);
        if (!exchange.getRequestMethod().equals("HEAD")) {
            exchange.getResponseBody().write(bytes);
        }
    }
    private static String mimeType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".woff2")) return "font/woff2";
        if (path.endsWith(".json")) return "application/json; charset=utf-8";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }
    private boolean isAllowedOrigin(HttpExchange exchange, boolean exemptCrossOrigin) {
        String host = singleValidHost(exchange.getRequestHeaders().get("Host"));
        if (host == null) return false;
        String hostLower = host.toLowerCase(Locale.ROOT);
        int port = server.getAddress().getPort();
        var allowedHosts = new HashSet<String>(Set.of(
                "127.0.0.1:" + port,
                "localhost:" + port));
        var allowedOrigins = new HashSet<String>(Set.of(
                "http://127.0.0.1:" + port,
                "http://localhost:" + port));
        boolean wildcardBind = bindHost.equals("0.0.0.0") || bindHost.equals("::") || bindHost.equals("[::]");
        if (!wildcardBind && !bindHost.equals("127.0.0.1") && !bindHost.equalsIgnoreCase("localhost")) {
            String formattedBind = (bindHost.contains(":") && !bindHost.startsWith("[") ? "[" + bindHost + "]" : bindHost)
                    .toLowerCase(Locale.ROOT);
            allowedHosts.add(formattedBind + ":" + port);
            allowedOrigins.add("http://" + formattedBind + ":" + port);
        }
        Optional<String> publicOrigin = api.auth() == null
                ? Optional.empty()
                : api.auth().oauthService().config().publicBaseUrl();
        if (publicOrigin.isPresent()) {
            try {
                String normPub = publicOrigin.orElseThrow().replaceFirst("/+$", "");
                URI pub = URI.create(normPub);
                if (pub.getHost() != null && pub.getScheme() != null) {
                    String pubScheme = pub.getScheme().toLowerCase(Locale.ROOT);
                    String pubHostOnly = pub.getHost().toLowerCase(Locale.ROOT);
                    String pubHost = pub.getPort() > 0 ? pubHostOnly + ":" + pub.getPort() : pubHostOnly;
                    String canonicalPubOrigin = formatOrigin(pubScheme, pubHost);
                    String canonicalPubHost = canonicalPubOrigin.substring(pubScheme.length() + 3);
                    allowedHosts.add(pubHost);
                    allowedHosts.add(canonicalPubHost);
                    if ("https".equals(pubScheme) && (pub.getPort() < 0 || pub.getPort() == 443)) {
                        allowedHosts.add(pubHostOnly + ":443");
                    } else if ("http".equals(pubScheme) && (pub.getPort() < 0 || pub.getPort() == 80)) {
                        allowedHosts.add(pubHostOnly + ":80");
                    }
                    allowedOrigins.add(canonicalPubOrigin);
                }
            } catch (IllegalArgumentException ignored) {}
        }
        boolean proxyHostAllowed = false;
        String trustedFwdProto = null;
        if (trustProxyHeaders) {
            String fwdHost = firstForwardedHost(exchange.getRequestHeaders().get("X-Forwarded-Host"));
            trustedFwdProto = firstForwardedProto(exchange.getRequestHeaders().get("X-Forwarded-Proto"));
            if (fwdHost != null) {
                String fwdHostLower = fwdHost.toLowerCase(Locale.ROOT);
                String proto = trustedFwdProto != null ? trustedFwdProto : "https";
                String canonicalFwdOrigin = formatOrigin(proto, fwdHostLower);
                String canonicalFwdHost = canonicalFwdOrigin.substring(proto.length() + 3);
                boolean matchesConfigured = allowedHosts.contains(fwdHostLower) || allowedHosts.contains(canonicalFwdHost);
                if (publicOrigin.isPresent() && !matchesConfigured) {
                    return false;
                }
                allowedHosts.add(fwdHostLower);
                allowedHosts.add(canonicalFwdHost);
                if (publicOrigin.isEmpty()) {
                    allowedOrigins.add(canonicalFwdOrigin);
                }
                proxyHostAllowed = true;
            } else if (trustedFwdProto != null && allowedHosts.contains(hostLower)) {
                allowedOrigins.add(formatOrigin(trustedFwdProto, hostLower));
            }
        }
        boolean hostAllowed = allowedHosts.contains(hostLower) || proxyHostAllowed || (wildcardBind && publicOrigin.isEmpty());
        if (!hostAllowed) return false;
        if (exemptCrossOrigin) return true;
        var origins = exchange.getRequestHeaders().get("Origin");
        if (origins == null) return true;
        if (origins.size() != 1) return false;
        String origin = origins.getFirst().strip().toLowerCase(Locale.ROOT);
        if (origin.equals(formatOrigin("http", hostLower))) return true;
        if (trustProxyHeaders && trustedFwdProto != null && origin.equals(formatOrigin(trustedFwdProto, hostLower))) {
            return true;
        }
        return allowedOrigins.contains(origin);
    }
    static boolean acceptable(String accept) {
        if (accept==null) return true;
        // The most specific matching range determines JSON's quality, including an explicit q=0.
        int specificity=-1;
        java.math.BigDecimal quality=java.math.BigDecimal.ZERO;
        for (String range : accept.toLowerCase(Locale.ROOT).split(",")) {
            String[] fields=range.trim().split(";");
            if (!Set.of("application/json","application/*","*/*").contains(fields[0].trim())) continue;
            int current=fields[0].trim().equals("application/json") ? 2 : fields[0].trim().equals("application/*") ? 1 : 0;
            var q=java.math.BigDecimal.ONE;
            boolean valid=true; boolean seenQuality=false;
            for (int i=1;i<fields.length;i++) {
                String parameter=fields[i].trim();
                if (!parameter.startsWith("q=") || seenQuality) { valid=false; break; }
                seenQuality=true;
                try { q=new java.math.BigDecimal(parameter.substring(2)); valid=q.signum()>=0 && q.compareTo(java.math.BigDecimal.ONE)<=0; }
                catch (NumberFormatException invalid) { valid=false; }
            }
            if (valid && (current>specificity || current==specificity && q.compareTo(quality)>0)) { specificity=current; quality=q; }
        }
        return specificity>=0 && quality.signum()>0;
    }
    static boolean acceptableSse(String accept) {
        if (accept == null) return true;
        int specificity = -1;
        java.math.BigDecimal quality = java.math.BigDecimal.ZERO;
        for (String range : accept.toLowerCase(Locale.ROOT).split(",")) {
            String[] fields = range.trim().split(";");
            String media = fields[0].trim();
            if (!Set.of("text/event-stream", "text/*", "*/*").contains(media)) continue;
            int current = media.equals("text/event-stream") ? 2 : media.equals("text/*") ? 1 : 0;
            var q = java.math.BigDecimal.ONE;
            boolean valid = true;
            boolean seenQuality = false;
            for (int i = 1; i < fields.length; i++) {
                String parameter = fields[i].trim();
                if (!parameter.startsWith("q=") || seenQuality) { valid = false; break; }
                seenQuality = true;
                try { q = new java.math.BigDecimal(parameter.substring(2)); valid = q.signum() >= 0 && q.compareTo(java.math.BigDecimal.ONE) <= 0; }
                catch (NumberFormatException invalid) { valid = false; }
            }
            if (valid && (current > specificity || current == specificity && q.compareTo(quality) > 0)) {
                specificity = current;
                quality = q;
            }
        }
        return specificity >= 0 && quality.signum() > 0;
    }

    @Override public void close() {
        if (closed.compareAndSet(false,true)) { server.stop(0); executor.shutdownNow(); sseExecutor.shutdownNow(); deadlines.shutdownNow(); }
    }
}
