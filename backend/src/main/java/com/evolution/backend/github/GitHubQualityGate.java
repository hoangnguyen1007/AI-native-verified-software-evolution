package com.evolution.backend.github;

import com.evolution.backend.query.repair.PrSuggestionFormatter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Deterministic Architecture Quality Gate evaluator for Pull Requests and CI pipelines.
 */
public final class GitHubQualityGate {

    public record Policy(
            boolean failOnNewMustViolations,
            BigDecimal maxScoreDrop,
            BigDecimal minOverallScore,
            boolean warnOnNewMayViolations,
            boolean failOnNewCycles) {

        public Policy {
            maxScoreDrop = maxScoreDrop == null ? new BigDecimal("2.0") : maxScoreDrop.max(BigDecimal.ZERO);
            minOverallScore = minOverallScore == null ? new BigDecimal("60.0") : minOverallScore.max(BigDecimal.ZERO);
        }

        public static Policy defaults() {
            return new Policy(true, new BigDecimal("2.0"), new BigDecimal("60.0"), true, true);
        }

        public static Policy fromMap(Map<String, Object> raw) {
            if (raw == null || raw.isEmpty()) {
                return defaults();
            }
            var d = defaults();
            boolean failMust = raw.get("failOnNewMustViolations") instanceof Boolean b ? b : d.failOnNewMustViolations();
            boolean warnMay = raw.get("warnOnNewMayViolations") instanceof Boolean b ? b : d.warnOnNewMayViolations();
            boolean failCycles = raw.get("failOnNewCycles") instanceof Boolean b ? b : d.failOnNewCycles();
            BigDecimal maxDrop = decimal(raw.get("maxScoreDrop"), d.maxScoreDrop());
            BigDecimal minScore = decimal(raw.get("minOverallScore"), d.minOverallScore());
            return new Policy(failMust, maxDrop, minScore, warnMay, failCycles);
        }

        private static BigDecimal decimal(Object value, BigDecimal fallback) {
            if (value instanceof BigDecimal bd) return bd;
            if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
            return fallback;
        }
    }

    public record Condition(String metric, String operator, String threshold, String actual, String status) {
        public Map<String, Object> toView() {
            var map = new LinkedHashMap<String, Object>();
            map.put("metric", metric);
            map.put("operator", operator);
            map.put("threshold", threshold);
            map.put("actual", actual);
            map.put("status", status);
            return map;
        }
    }

    public record Result(String verdict, String summary, List<Condition> conditions) {
        public Result {
            verdict = verdict == null ? "WARN" : verdict;
            summary = summary == null ? "" : summary;
            conditions = List.copyOf(conditions == null ? List.of() : conditions);
        }

        public Map<String, Object> toView() {
            var map = new LinkedHashMap<String, Object>();
            map.put("verdict", verdict);
            map.put("summary", summary);
            map.put("conditions", conditions.stream().map(Condition::toView).toList());
            return map;
        }

        public PrSuggestionFormatter.QualityGateSummary toPrSummary() {
            var failed = new ArrayList<String>();
            var warnings = new ArrayList<String>();
            for (var c : conditions) {
                String line = c.metric() + " (" + c.actual() + " vs " + c.operator() + " " + c.threshold() + ")";
                if ("FAILED".equals(c.status())) failed.add(line);
                else if ("WARN".equals(c.status())) warnings.add(line);
            }
            return new PrSuggestionFormatter.QualityGateSummary(verdict, failed, warnings);
        }

        public String toCommitStatusState() {
            return switch (verdict) {
                case "PASSED" -> "success";
                case "FAILED" -> "failure";
                default -> "success";
            };
        }

        public String toCheckRunConclusion() {
            return switch (verdict) {
                case "PASSED" -> "success";
                case "FAILED" -> "failure";
                default -> "neutral";
            };
        }
    }

    private GitHubQualityGate() {
    }

    public static Result evaluate(
            Policy policy,
            Optional<BigDecimal> baseScore,
            Optional<BigDecimal> headScore,
            int newMustViolations,
            int newMayViolations,
            int newCycles) {
        return evaluate(policy, baseScore, headScore, newMustViolations, newMayViolations, newCycles, 0);
    }

    public static Result evaluate(
            Policy policy,
            Optional<BigDecimal> baseScore,
            Optional<BigDecimal> headScore,
            int newMustViolations,
            int newMayViolations,
            int newCycles,
            int semanticCommunityOverlaps) {
        Policy p = policy == null ? Policy.defaults() : policy;
        var conditions = new ArrayList<Condition>();
        boolean failed = false;
        boolean warned = false;

        // 1. New MUST violations
        String mustStatus = newMustViolations > 0
                ? (p.failOnNewMustViolations() ? "FAILED" : "WARN")
                : "PASSED";
        if ("FAILED".equals(mustStatus)) failed = true;
        else if ("WARN".equals(mustStatus)) warned = true;
        conditions.add(new Condition("new_must_violations", "==", "0", String.valueOf(newMustViolations), mustStatus));

        // 2. New MAY violations
        String mayStatus = newMayViolations > 0 && p.warnOnNewMayViolations() ? "WARN" : "PASSED";
        if ("WARN".equals(mayStatus)) warned = true;
        conditions.add(new Condition("new_may_violations", "==", "0", String.valueOf(newMayViolations), mayStatus));

        // 3. New dependency cycles
        String cycleStatus = newCycles > 0 ? (p.failOnNewCycles() ? "FAILED" : "WARN") : "PASSED";
        if ("FAILED".equals(cycleStatus)) failed = true;
        else if ("WARN".equals(cycleStatus)) warned = true;
        conditions.add(new Condition("new_cycles", "==", "0", String.valueOf(newCycles), cycleStatus));

        // 4. Architecture Score v2 delta
        if (baseScore != null && baseScore.isPresent() && headScore != null && headScore.isPresent()) {
            BigDecimal delta = headScore.orElseThrow().subtract(baseScore.orElseThrow()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal drop = delta.negate();
            String dropStatus = drop.compareTo(p.maxScoreDrop()) > 0 ? "FAILED"
                    : (delta.signum() < 0 ? "WARN" : "PASSED");
            if ("FAILED".equals(dropStatus)) failed = true;
            else if ("WARN".equals(dropStatus)) warned = true;
            conditions.add(new Condition("score_v2_drop", "<=", p.maxScoreDrop().toPlainString(),
                    (delta.signum() >= 0 ? "+" : "") + delta.toPlainString(), dropStatus));
        }

        // 5. Minimum overall Architecture Score v2
        if (headScore != null && headScore.isPresent()) {
            BigDecimal head = headScore.orElseThrow().setScale(2, RoundingMode.HALF_UP);
            String minStatus = head.compareTo(p.minOverallScore()) < 0 ? "FAILED" : "PASSED";
            if ("FAILED".equals(minStatus)) failed = true;
            conditions.add(new Condition("overall_score_v2", ">=", p.minOverallScore().toPlainString(),
                    head.toPlainString(), minStatus));
        }

        // 6. Optional Leiden semantic community overlap warning across concurrent PRs
        if (semanticCommunityOverlaps > 0) {
            warned = true;
            conditions.add(new Condition("community_semantic_overlap", "==", "0",
                    String.valueOf(semanticCommunityOverlaps), "WARN"));
        }

        String verdict = failed ? "FAILED" : (warned ? "WARN" : "PASSED");
        String summary;
        if ("FAILED".equals(verdict)) {
            var reasons = new ArrayList<String>();
            if (newMustViolations > 0) reasons.add(newMustViolations + " new MUST violations");
            if (newCycles > 0) reasons.add(newCycles + " new cycles");
            for (var cond : conditions) {
                if ("FAILED".equals(cond.status())
                        && !"new_must_violations".equals(cond.metric())
                        && !"new_cycles".equals(cond.metric())) {
                    reasons.add(cond.metric() + "=" + cond.actual());
                }
            }
            summary = reasons.isEmpty()
                    ? "Quality Gate FAILED."
                    : "Quality Gate FAILED (" + String.join(", ", reasons) + ").";
        } else if ("WARN".equals(verdict)) {
            var reasons = new ArrayList<String>();
            if (newMayViolations > 0 && p.warnOnNewMayViolations()) {
                reasons.add(newMayViolations + " new MAY warnings");
            }
            if (semanticCommunityOverlaps > 0) {
                reasons.add(semanticCommunityOverlaps + " semantic community overlap"
                        + (semanticCommunityOverlaps == 1 ? "" : "s"));
            }
            for (var cond : conditions) {
                if ("WARN".equals(cond.status())
                        && !"new_may_violations".equals(cond.metric())
                        && !"community_semantic_overlap".equals(cond.metric())) {
                    reasons.add(cond.metric() + "=" + cond.actual());
                }
            }
            summary = reasons.isEmpty()
                    ? "Quality Gate WARN."
                    : "Quality Gate WARN (" + String.join(", ", reasons) + ").";
        } else {
            summary = "Quality Gate PASSED — no architectural regressions detected.";
        }
        return new Result(verdict, summary, conditions);
    }
}
