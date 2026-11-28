package com.evolution.analysis.polyglot;

import java.util.Locale;
import java.util.Objects;

/**
 * Deterministic AMQP/RabbitMQ binding fact linking an exchange and routing-key pattern
 * to a target queue, together with RFC/RabbitMQ AMQP 0-9-1 dot-delimited wildcard matching.
 */
public record AmqpBindingFact(
        String exchangeName,
        String routingKeyPattern,
        String queueName,
        String exchangeType,
        String sourceSpan) implements Comparable<AmqpBindingFact> {

    public static final String TYPE_TOPIC = "TOPIC";
    public static final String TYPE_DIRECT = "DIRECT";
    public static final String TYPE_FANOUT = "FANOUT";
    public static final String TYPE_HEADERS = "HEADERS";
    public static final String TYPE_CUSTOM = "CUSTOM";

    public AmqpBindingFact {
        exchangeName = exchangeName == null ? "" : exchangeName.trim();
        routingKeyPattern = routingKeyPattern == null ? "" : routingKeyPattern.trim();
        queueName = queueName == null ? "" : queueName.trim();
        exchangeType = normalizeExchangeType(exchangeType);
        sourceSpan = sourceSpan == null ? "" : sourceSpan.trim();
    }

    public static String normalizeExchangeType(String raw) {
        if (raw == null || raw.isBlank()) {
            return TYPE_TOPIC;
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        if (upper.contains("FANOUT")) return TYPE_FANOUT;
        if (upper.contains("DIRECT")) return TYPE_DIRECT;
        if (upper.contains("HEADERS")) return TYPE_HEADERS;
        if (upper.contains("CUSTOM")) return TYPE_CUSTOM;
        return TYPE_TOPIC;
    }

    /**
     * Evaluates AMQP 0-9-1 routing semantics for this binding against a candidate routing key.
     */
    public boolean matches(String routingKey) {
        return matchesRoutingKey(routingKeyPattern, routingKey, exchangeType);
    }

    /**
     * Matches an AMQP routing key against a binding pattern according to exchange type:
     * <ul>
     *   <li>{@code FANOUT}: always matches regardless of routing key.</li>
     *   <li>{@code DIRECT}: exact string equality.</li>
     *   <li>{@code HEADERS}: never matched solely by routing key.</li>
     *   <li>{@code TOPIC} / {@code CUSTOM}: dot-delimited wildcard matching where {@code *} matches
     *       exactly one word and {@code #} matches zero or more words.</li>
     * </ul>
     */
    public static boolean matchesRoutingKey(String pattern, String routingKey, String exchangeType) {
        String normalizedType = normalizeExchangeType(exchangeType);
        if (TYPE_FANOUT.equals(normalizedType)) {
            return true;
        }
        if (TYPE_HEADERS.equals(normalizedType)) {
            return false;
        }
        String pat = pattern == null ? "" : pattern.trim();
        String key = routingKey == null ? "" : routingKey.trim();
        if (TYPE_DIRECT.equals(normalizedType)) {
            return pat.equals(key);
        }
        return matchesTopicWildcard(pat, key);
    }

    /**
     * Bounded O(|P| * |K|) AMQP 0-9-1 topic exchange matcher on dot-delimited segments.
     * {@code *} matches exactly 1 segment; {@code #} matches 0 or more segments.
     */
    public static boolean matchesTopicWildcard(String pattern, String routingKey) {
        if (pattern == null || routingKey == null) {
            return false;
        }
        String[] pTokens = pattern.isEmpty() ? new String[0] : pattern.split("\\.", -1);
        String[] kTokens = routingKey.isEmpty() ? new String[0] : routingKey.split("\\.", -1);
        if (pTokens.length > 256 || kTokens.length > 256) {
            return false;
        }
        int pLen = pTokens.length;
        int kLen = kTokens.length;
        boolean[][] dp = new boolean[pLen + 1][kLen + 1];
        dp[0][0] = true;
        for (int i = 1; i <= pLen; i++) {
            if ("#".equals(pTokens[i - 1])) {
                dp[i][0] = dp[i - 1][0];
            }
        }
        for (int i = 1; i <= pLen; i++) {
            String pt = pTokens[i - 1];
            for (int j = 1; j <= kLen; j++) {
                if ("#".equals(pt)) {
                    dp[i][j] = dp[i - 1][j] || dp[i][j - 1];
                } else if ("*".equals(pt) || pt.equals(kTokens[j - 1])) {
                    dp[i][j] = dp[i - 1][j - 1];
                }
            }
        }
        return dp[pLen][kLen];
    }

    @Override
    public int compareTo(AmqpBindingFact other) {
        Objects.requireNonNull(other);
        int cmp = this.exchangeName.compareTo(other.exchangeName);
        if (cmp != 0) return cmp;
        cmp = this.routingKeyPattern.compareTo(other.routingKeyPattern);
        if (cmp != 0) return cmp;
        cmp = this.queueName.compareTo(other.queueName);
        if (cmp != 0) return cmp;
        cmp = this.exchangeType.compareTo(other.exchangeType);
        if (cmp != 0) return cmp;
        return this.sourceSpan.compareTo(other.sourceSpan);
    }
}
