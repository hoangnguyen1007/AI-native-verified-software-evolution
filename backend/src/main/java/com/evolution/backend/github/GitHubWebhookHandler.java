package com.evolution.backend.github;

import com.evolution.analysis.graph.GraphJson;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Hardened GitHub Webhook receiver and PR ChatOps parser ({@code /evo analyze|score|help}).
 *
 * <p>Security invariants:
 * <ul>
 *   <li>Fail-closed ({@code 503 WEBHOOK_SECRET_NOT_CONFIGURED}) unless {@code EVO_GITHUB_WEBHOOK_SECRET} is explicitly set.</li>
 *   <li>Verifies {@code X-Hub-Signature-256} using HMAC-SHA256 over raw unparsed request bytes with constant-time comparison.</li>
 *   <li>Deduplicates {@code X-GitHub-Delivery} IDs in a bounded 500-entry 24h replay cache.</li>
 *   <li>Never executes untrusted target builds on the host and ignores bot comments to prevent reply loops.</li>
 * </ul>
 */
public final class GitHubWebhookHandler {
    public static final String SIGNATURE_HEADER = "X-Hub-Signature-256";
    public static final String EVENT_HEADER = "X-GitHub-Event";
    public static final String DELIVERY_HEADER = "X-GitHub-Delivery";
    private static final int MAX_DEDUP_DELIVERIES = 500;
    private static final int MAX_AUDIT_LOG = 100;
    private static final long DEDUP_TTL_MS = TimeUnit.HOURS.toMillis(24);
    private static final Pattern CHATOPS_PATTERN = Pattern.compile(
            "(?m)^\\s*/evo\\s+(analyze|score|help)(?:\\s+(.*))?$");
    private static final GraphJson.Limits JSON_LIMITS = new GraphJson.Limits(2 * 1024 * 1024, 32, 100_000);

    public record DeliveryRecord(
            String deliveryId,
            String event,
            String action,
            String repository,
            OptionalInt pullNumber,
            Optional<String> chatOpsCommand,
            boolean signatureVerified,
            String status,
            long receivedAtMs) {
        public Map<String, Object> toView() {
            var map = new LinkedHashMap<String, Object>();
            map.put("deliveryId", deliveryId);
            map.put("event", event);
            map.put("action", action);
            map.put("repository", repository);
            map.put("pullNumber", pullNumber.isPresent() ? pullNumber.getAsInt() : null);
            map.put("chatOpsCommand", chatOpsCommand.orElse(null));
            map.put("signatureVerified", signatureVerified);
            map.put("status", status);
            map.put("receivedAtMs", receivedAtMs);
            return map;
        }
    }

    public record HandleResult(int httpStatus, String code, Map<String, Object> payload) {
    }

    private final byte[] envSecretBytes;
    private volatile byte[] secretBytes;
    private final boolean autoReplyEnabled;
    private final Map<String, Long> seenDeliveries = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > MAX_DEDUP_DELIVERIES;
                }
            });
    private final Deque<DeliveryRecord> auditLog = new ArrayDeque<>(MAX_AUDIT_LOG + 2);

    public GitHubWebhookHandler(Optional<String> secret, boolean autoReplyEnabled) {
        this.envSecretBytes = secret == null || secret.isEmpty() || secret.orElseThrow().isBlank()
                ? null
                : secret.orElseThrow().getBytes(StandardCharsets.UTF_8);
        this.secretBytes = this.envSecretBytes == null ? null : this.envSecretBytes.clone();
        this.autoReplyEnabled = autoReplyEnabled;
    }

    /** Hot-reloads the active webhook HMAC secret (or reverts to env secret when empty). */
    public synchronized void updateSecret(Optional<String> overrideSecret) {
        byte[] old = this.secretBytes;
        if (overrideSecret != null && overrideSecret.isPresent() && !overrideSecret.orElseThrow().isBlank()) {
            this.secretBytes = overrideSecret.orElseThrow().strip().getBytes(StandardCharsets.UTF_8);
        } else {
            this.secretBytes = this.envSecretBytes == null ? null : this.envSecretBytes.clone();
        }
        if (old != null && old != this.envSecretBytes) {
            Arrays.fill(old, (byte) 0);
        }
    }

    public static GitHubWebhookHandler fromEnvironment() {
        return fromEnvironment(System::getenv, System::getProperty);
    }

    public static GitHubWebhookHandler fromEnvironment(Function<String, String> env, Function<String, String> props) {
        String secret = props.apply("evo.github.webhook.secret");
        if (secret == null || secret.isBlank()) {
            secret = env.apply("EVO_GITHUB_WEBHOOK_SECRET");
        }
        String autoReply = props.apply("evo.github.webhook.autoReply");
        if (autoReply == null || autoReply.isBlank()) {
            autoReply = env.apply("EVO_WEBHOOK_AUTO_REPLY");
        }
        return new GitHubWebhookHandler(
                secret == null || secret.isBlank() ? Optional.empty() : Optional.of(secret),
                "true".equalsIgnoreCase(autoReply));
    }

    public boolean isConfigured() {
        return secretBytes != null && secretBytes.length > 0;
    }

    public boolean isAutoReplyEnabled() {
        return autoReplyEnabled;
    }

    /** Computes GitHub's {@code sha256=<hex>} HMAC-SHA256 signature over raw UTF-8 payload bytes. */
    public static String computeSignature(byte[] secret, byte[] rawPayload) {
        Objects.requireNonNull(secret, "secret");
        Objects.requireNonNull(rawPayload, "rawPayload");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] digest = mac.doFinal(rawPayload);
            return "sha256=" + HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 unavailable", impossible);
        }
    }

    /** Constant-time HMAC-SHA256 verification against {@code X-Hub-Signature-256}. */
    public static boolean verifySignature(byte[] secret, byte[] rawPayload, String signatureHeader) {
        if (secret == null || secret.length == 0 || rawPayload == null || signatureHeader == null) {
            return false;
        }
        String supplied = signatureHeader.strip();
        if (!supplied.startsWith("sha256=") || supplied.length() != 71) {
            return false;
        }
        String expected = computeSignature(secret, rawPayload);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                supplied.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    /** Extracts a supported {@code /evo analyze|score|help} ChatOps command from a PR comment body. */
    public static Optional<String> parseChatOpsCommand(String commentBody) {
        if (commentBody == null || commentBody.isBlank()) {
            return Optional.empty();
        }
        Matcher m = CHATOPS_PATTERN.matcher(commentBody);
        if (!m.find()) {
            return Optional.empty();
        }
        return Optional.of("/evo " + m.group(1).toLowerCase(Locale.ROOT));
    }

    public HandleResult handleDelivery(
            String eventHeader,
            String deliveryHeader,
            String signatureHeader,
            byte[] rawBody) {
        long now = System.currentTimeMillis();
        String event = eventHeader == null || eventHeader.isBlank() ? "unknown" : eventHeader.strip().toLowerCase(Locale.ROOT);
        String deliveryId = deliveryHeader == null || deliveryHeader.isBlank()
                ? "delivery-" + UUID.randomUUID()
                : deliveryHeader.strip();

        if (!isConfigured()) {
            return new HandleResult(503, "WEBHOOK_SECRET_NOT_CONFIGURED",
                    Map.of("configured", false, "message", "EVO_GITHUB_WEBHOOK_SECRET is not configured."));
        }
        if (rawBody == null || !verifySignature(secretBytes, rawBody, signatureHeader)) {
            record(new DeliveryRecord(deliveryId, event, "", "", OptionalInt.empty(), Optional.empty(),
                    false, "REJECTED_SIGNATURE", now));
            return new HandleResult(403, "INVALID_WEBHOOK_SIGNATURE",
                    Map.of("deliveryId", deliveryId, "signatureVerified", false));
        }

        // Replay deduplication on X-GitHub-Delivery
        synchronized (seenDeliveries) {
            Long seenAt = seenDeliveries.get(deliveryId);
            if (seenAt != null && (now - seenAt) < DEDUP_TTL_MS) {
                record(new DeliveryRecord(deliveryId, event, "", "", OptionalInt.empty(), Optional.empty(),
                        true, "DUPLICATE_IGNORED", now));
                return new HandleResult(200, "DUPLICATE_IGNORED",
                        Map.of("deliveryId", deliveryId, "status", "DUPLICATE_IGNORED"));
            }
            seenDeliveries.put(deliveryId, now);
        }

        Map<String, Object> body;
        try {
            String text = new String(rawBody, StandardCharsets.UTF_8);
            body = text.isBlank() ? Map.of() : GraphJson.object(GraphJson.read(text, JSON_LIMITS));
        } catch (RuntimeException badJson) {
            record(new DeliveryRecord(deliveryId, event, "", "", OptionalInt.empty(), Optional.empty(),
                    true, "INVALID_JSON", now));
            return new HandleResult(400, "INVALID_REQUEST", Map.of("deliveryId", deliveryId));
        }

        String action = str(body.get("action"), "");
        var repoObj = body.get("repository") instanceof Map<?, ?> m ? GraphJson.object(m) : Map.<String, Object>of();
        String repository = str(repoObj.get("full_name"), "");

        if ("ping".equals(event)) {
            record(new DeliveryRecord(deliveryId, event, action, repository, OptionalInt.empty(), Optional.empty(),
                    true, "PONG", now));
            return new HandleResult(200, "PONG",
                    Map.of("deliveryId", deliveryId, "status", "PONG", "repository", repository));
        }

        if ("pull_request".equals(event)) {
            var prObj = body.get("pull_request") instanceof Map<?, ?> m ? GraphJson.object(m) : Map.<String, Object>of();
            int prNum = num(body.get("number"), num(prObj.get("number"), 0));
            OptionalInt prOpt = prNum > 0 ? OptionalInt.of(prNum) : OptionalInt.empty();
            boolean actionable = Set.of("opened", "synchronize", "reopened").contains(action);
            String status = actionable ? "QUEUED_PR_ANALYSIS" : "IGNORED_ACTION";
            record(new DeliveryRecord(deliveryId, event, action, repository, prOpt, Optional.empty(), true, status, now));
            return new HandleResult(202, status, Map.of(
                    "deliveryId", deliveryId, "event", event, "action", action,
                    "repository", repository, "pullNumber", prNum, "status", status));
        }

        if ("push".equals(event)) {
            record(new DeliveryRecord(deliveryId, event, "push", repository, OptionalInt.empty(), Optional.empty(),
                    true, "RECORDED_PUSH_DELTA", now));
            return new HandleResult(202, "RECORDED_PUSH_DELTA", Map.of(
                    "deliveryId", deliveryId, "event", event, "repository", repository, "status", "RECORDED_PUSH_DELTA"));
        }

        if ("issue_comment".equals(event)) {
            var issueObj = body.get("issue") instanceof Map<?, ?> m ? GraphJson.object(m) : Map.<String, Object>of();
            var commentObj = body.get("comment") instanceof Map<?, ?> m ? GraphJson.object(m) : Map.<String, Object>of();
            var senderObj = body.get("sender") instanceof Map<?, ?> m ? GraphJson.object(m) : Map.<String, Object>of();
            boolean isPr = issueObj.containsKey("pull_request");
            int prNum = num(issueObj.get("number"), 0);
            OptionalInt prOpt = (isPr && prNum > 0) ? OptionalInt.of(prNum) : OptionalInt.empty();
            String senderLogin = str(senderObj.get("login"), "");
            String senderType = str(senderObj.get("type"), "");
            boolean isBot = "Bot".equalsIgnoreCase(senderType) || senderLogin.endsWith("[bot]");
            Optional<String> cmd = ("created".equals(action) && isPr && !isBot)
                    ? parseChatOpsCommand(str(commentObj.get("body"), ""))
                    : Optional.empty();
            String status = cmd.isPresent() ? "CHATOPS_ACCEPTED" : (isBot ? "IGNORED_BOT" : "IGNORED_COMMENT");
            record(new DeliveryRecord(deliveryId, event, action, repository, prOpt, cmd, true, status, now));
            var out = new LinkedHashMap<String, Object>();
            out.put("deliveryId", deliveryId);
            out.put("event", event);
            out.put("repository", repository);
            out.put("pullNumber", prNum > 0 ? prNum : null);
            out.put("chatOpsCommand", cmd.orElse(null));
            out.put("autoReplyEnabled", autoReplyEnabled);
            out.put("status", status);
            return new HandleResult(200, status, out);
        }

        record(new DeliveryRecord(deliveryId, event, action, repository, OptionalInt.empty(), Optional.empty(),
                true, "UNHANDLED_EVENT", now));
        return new HandleResult(200, "UNHANDLED_EVENT",
                Map.of("deliveryId", deliveryId, "event", event, "status", "UNHANDLED_EVENT"));
    }

    public Map<String, Object> deliveriesView() {
        return deliveriesView(Optional.empty());
    }

    public Map<String, Object> deliveriesView(Optional<Set<String>> allowedRepositories) {
        Set<String> normalizedAllowed = allowedRepositories.map(repos -> {
            var set = new HashSet<String>(repos.size());
            for (String repo : repos) {
                if (repo != null && !repo.isBlank()) {
                    set.add(repo.strip().toLowerCase(Locale.ROOT));
                }
            }
            return Set.copyOf(set);
        }).orElse(null);
        List<Map<String, Object>> items;
        synchronized (auditLog) {
            items = auditLog.stream()
                    .filter(rec -> normalizedAllowed == null
                            || (rec.repository() != null
                                    && normalizedAllowed.contains(rec.repository().strip().toLowerCase(Locale.ROOT))))
                    .map(DeliveryRecord::toView)
                    .toList();
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("webhookConfigured", isConfigured());
        out.put("autoReplyEnabled", autoReplyEnabled);
        out.put("supportedCommands", List.of("/evo analyze", "/evo score", "/evo help"));
        out.put("items", items);
        return out;
    }

    private void record(DeliveryRecord rec) {
        synchronized (auditLog) {
            auditLog.addFirst(rec);
            while (auditLog.size() > MAX_AUDIT_LOG) {
                auditLog.removeLast();
            }
        }
    }

    private static String str(Object value, String fallback) {
        return value instanceof String s ? s : fallback;
    }

    private static int num(Object value, int fallback) {
        return value instanceof Number n ? n.intValue() : fallback;
    }
}
