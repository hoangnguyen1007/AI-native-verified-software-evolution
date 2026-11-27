package com.evolution.analysis.evolution;

import com.evolution.analysis.assessment.v2.LeidenCommunityDetector;
import com.evolution.analysis.assessment.v2.LeidenCommunityDetector.EmergentCommunity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Pairwise Leiden community evolution matcher for Snapshot A -> Snapshot B comparisons.
 *
 * <p>Operates strictly on pairwise comparisons so single-snapshot canonical community identities
 * remain completely independent of prior snapshots ({@code CleanFullRun(B) == IncrementalRun(A->B)}).
 */
public final class CommunityEvolutionMatcher {
    public static final String SCHEMA = "evolution.community-matcher:v1";
    public static final double DEFAULT_MIN_JACCARD = 0.25;
    public static final double DEFAULT_MIN_CONTAINMENT = 0.40;

    public enum Status {
        SURVIVED,
        SPLIT,
        MERGED,
        BORN,
        DISSOLVED
    }

    public record Link(
            String beforeCommunityId,
            String beforeLabel,
            int beforeSize,
            String afterCommunityId,
            String afterLabel,
            int afterSize,
            int sharedMembers,
            BigDecimal jaccard,
            BigDecimal overlapRatio) {}

    public record Transition(
            Status status,
            List<String> beforeCommunityIds,
            List<String> beforeLabels,
            List<String> afterCommunityIds,
            List<String> afterLabels,
            int sharedMembers,
            BigDecimal jaccard,
            String summary) {
        public Transition {
            beforeCommunityIds = List.copyOf(beforeCommunityIds);
            beforeLabels = List.copyOf(beforeLabels);
            afterCommunityIds = List.copyOf(afterCommunityIds);
            afterLabels = List.copyOf(afterLabels);
        }
    }

    public record Report(
            String schema,
            int beforeCommunityCount,
            int afterCommunityCount,
            int survivedCount,
            int splitCount,
            int mergedCount,
            int bornCount,
            int dissolvedCount,
            BigDecimal stabilityIndex,
            List<Transition> transitions,
            List<Link> links) {
        public Report {
            transitions = List.copyOf(transitions);
            links = List.copyOf(links);
        }
    }

    private CommunityEvolutionMatcher() {}

    public static Report match(LeidenCommunityDetector.Result before, LeidenCommunityDetector.Result after) {
        return match(
                before == null ? List.of() : before.communities(),
                after == null ? List.of() : after.communities(),
                DEFAULT_MIN_JACCARD,
                DEFAULT_MIN_CONTAINMENT);
    }

    public static Report match(
            List<EmergentCommunity> beforeCommunities,
            List<EmergentCommunity> afterCommunities,
            double minJaccard,
            double minContainment) {
        List<EmergentCommunity> before = canonicalize(beforeCommunities);
        List<EmergentCommunity> after = canonicalize(afterCommunities);

        var beforeById = new LinkedHashMap<String, EmergentCommunity>();
        var beforeSets = new LinkedHashMap<String, Set<String>>();
        for (var c : before) {
            beforeById.put(c.id(), c);
            beforeSets.put(c.id(), new HashSet<>(c.members()));
        }

        var afterById = new LinkedHashMap<String, EmergentCommunity>();
        var afterSets = new LinkedHashMap<String, Set<String>>();
        for (var c : after) {
            afterById.put(c.id(), c);
            afterSets.put(c.id(), new HashSet<>(c.members()));
        }

        // Compute deterministic significant overlap links
        var links = new ArrayList<Link>();
        var afterByBefore = new LinkedHashMap<String, List<Link>>();
        var beforeByAfter = new LinkedHashMap<String, List<Link>>();

        for (var bc : before) {
            Set<String> bSet = beforeSets.get(bc.id());
            for (var ac : after) {
                Set<String> aSet = afterSets.get(ac.id());
                int shared = intersectionSize(bSet, aSet);
                if (shared == 0) continue;
                int union = bSet.size() + aSet.size() - shared;
                int minSize = Math.min(bSet.size(), aSet.size());
                double jaccard = union > 0 ? (double) shared / union : 0.0;
                double overlap = minSize > 0 ? (double) shared / minSize : 0.0;
                if (jaccard >= minJaccard || (overlap >= minContainment && shared >= 2)) {
                    var link = new Link(
                            bc.id(), bc.label(), bc.memberCount(),
                            ac.id(), ac.label(), ac.memberCount(),
                            shared,
                            BigDecimal.valueOf(jaccard).setScale(4, RoundingMode.HALF_UP),
                            BigDecimal.valueOf(overlap).setScale(4, RoundingMode.HALF_UP));
                    links.add(link);
                    afterByBefore.computeIfAbsent(bc.id(), k -> new ArrayList<>()).add(link);
                    beforeByAfter.computeIfAbsent(ac.id(), k -> new ArrayList<>()).add(link);
                }
            }
        }
        links.sort(Comparator.comparing(Link::jaccard).reversed()
                .thenComparing(Comparator.comparingInt(Link::sharedMembers).reversed())
                .thenComparing(Link::beforeCommunityId)
                .thenComparing(Link::afterCommunityId));

        var transitions = new ArrayList<Transition>();
        var handledBefore = new HashSet<String>();
        var handledAfter = new HashSet<String>();

        int survived = 0, split = 0, merged = 0, born = 0, dissolved = 0;

        // 1. Detect SPLIT (1 before -> >=2 unhandled after)
        for (var bc : before) {
            if (handledBefore.contains(bc.id())) continue;
            var outs = afterByBefore.getOrDefault(bc.id(), List.of()).stream()
                    .filter(l -> !handledAfter.contains(l.afterCommunityId()))
                    .sorted(Comparator.comparingInt(Link::sharedMembers).reversed().thenComparing(Link::afterCommunityId))
                    .toList();
            if (outs.size() >= 2) {
                var afterIds = outs.stream().map(Link::afterCommunityId).toList();
                var afterLabels = outs.stream().map(Link::afterLabel).toList();
                int sharedSum = outs.stream().mapToInt(Link::sharedMembers).sum();
                BigDecimal avgJaccard = averageJaccard(outs);
                transitions.add(new Transition(
                        Status.SPLIT,
                        List.of(bc.id()), List.of(bc.label()),
                        afterIds, afterLabels,
                        sharedSum, avgJaccard,
                        bc.label() + " split into " + afterIds.size() + " communities"));
                handledBefore.add(bc.id());
                handledAfter.addAll(afterIds);
                split++;
            }
        }

        // 2. Detect MERGED (>=2 before -> 1 after)
        for (var ac : after) {
            if (handledAfter.contains(ac.id())) continue;
            var ins = beforeByAfter.getOrDefault(ac.id(), List.of()).stream()
                    .filter(l -> !handledBefore.contains(l.beforeCommunityId()))
                    .sorted(Comparator.comparingInt(Link::sharedMembers).reversed().thenComparing(Link::beforeCommunityId))
                    .toList();
            if (ins.size() >= 2) {
                var beforeIds = ins.stream().map(Link::beforeCommunityId).toList();
                var beforeLabels = ins.stream().map(Link::beforeLabel).toList();
                int sharedSum = ins.stream().mapToInt(Link::sharedMembers).sum();
                BigDecimal avgJaccard = averageJaccard(ins);
                transitions.add(new Transition(
                        Status.MERGED,
                        beforeIds, beforeLabels,
                        List.of(ac.id()), List.of(ac.label()),
                        sharedSum, avgJaccard,
                        beforeIds.size() + " communities merged into " + ac.label()));
                handledBefore.addAll(beforeIds);
                handledAfter.add(ac.id());
                merged++;
            }
        }

        // 3. Detect SURVIVED (1-to-1 primary match)
        for (var bc : before) {
            if (handledBefore.contains(bc.id())) continue;
            var outs = afterByBefore.getOrDefault(bc.id(), List.of()).stream()
                    .filter(l -> !handledAfter.contains(l.afterCommunityId()))
                    .sorted(Comparator.comparing(Link::jaccard).reversed().thenComparing(Link::afterCommunityId))
                    .toList();
            if (!outs.isEmpty()) {
                var best = outs.getFirst();
                transitions.add(new Transition(
                        Status.SURVIVED,
                        List.of(bc.id()), List.of(bc.label()),
                        List.of(best.afterCommunityId()), List.of(best.afterLabel()),
                        best.sharedMembers(), best.jaccard(),
                        bc.label() + " survived as " + best.afterLabel() + " (Jaccard " + best.jaccard() + ")"));
                handledBefore.add(bc.id());
                handledAfter.add(best.afterCommunityId());
                survived++;
            }
        }

        // 4. Detect DISSOLVED (unmatched before)
        for (var bc : before) {
            if (handledBefore.contains(bc.id())) continue;
            transitions.add(new Transition(
                    Status.DISSOLVED,
                    List.of(bc.id()), List.of(bc.label()),
                    List.of(), List.of(),
                    0, BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
                    bc.label() + " dissolved (" + bc.memberCount() + " members)"));
            dissolved++;
        }

        // 5. Detect BORN (unmatched after)
        for (var ac : after) {
            if (handledAfter.contains(ac.id())) continue;
            transitions.add(new Transition(
                    Status.BORN,
                    List.of(), List.of(),
                    List.of(ac.id()), List.of(ac.label()),
                    0, BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
                    ac.label() + " emerged (" + ac.memberCount() + " members)"));
            born++;
        }

        // Compute stability index: weighted average Jaccard of survived communities over max(before, after)
        int denom = Math.max(1, Math.max(before.size(), after.size()));
        double jaccardSum = 0.0;
        for (var t : transitions) {
            if (t.status() == Status.SURVIVED) {
                jaccardSum += t.jaccard().doubleValue();
            } else if (t.status() == Status.SPLIT || t.status() == Status.MERGED) {
                jaccardSum += t.jaccard().doubleValue() * 0.5;
            }
        }
        BigDecimal stability = (before.isEmpty() && after.isEmpty())
                ? BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(Math.min(1.0, jaccardSum / denom)).setScale(4, RoundingMode.HALF_UP);

        return new Report(
                SCHEMA,
                before.size(),
                after.size(),
                survived,
                split,
                merged,
                born,
                dissolved,
                stability,
                transitions,
                links);
    }

    private static List<EmergentCommunity> canonicalize(List<EmergentCommunity> communities) {
        if (communities == null || communities.isEmpty()) return List.of();
        var sorted = new ArrayList<EmergentCommunity>(communities.size());
        for (var c : communities) {
            if (c != null) sorted.add(c);
        }
        sorted.sort(Comparator.comparing(EmergentCommunity::id));
        return List.copyOf(sorted);
    }

    private static int intersectionSize(Set<String> a, Set<String> b) {
        Set<String> small = a.size() <= b.size() ? a : b;
        Set<String> large = small == a ? b : a;
        int count = 0;
        for (String item : small) {
            if (large.contains(item)) count++;
        }
        return count;
    }

    private static BigDecimal averageJaccard(List<Link> links) {
        if (links.isEmpty()) return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        double sum = 0.0;
        for (var l : links) sum += l.jaccard().doubleValue();
        return BigDecimal.valueOf(sum / links.size()).setScale(4, RoundingMode.HALF_UP);
    }
}
