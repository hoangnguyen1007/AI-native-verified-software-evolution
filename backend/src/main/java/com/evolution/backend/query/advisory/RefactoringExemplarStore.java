package com.evolution.backend.query.advisory;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.backend.query.llm.LlmTransport;
import com.evolution.backend.query.repair.AstRefactoringPurityVerifier;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Quarantined Pure-Refactoring Exemplar Store with Hybrid BM25 + SKG Structural Similarity RRF
 * (WP4.3 — SWE-Refactor App. F p. 15–17).
 *
 * <p>Hard invariants:
 * <ul>
 *   <li><b>Strict Quarantine ({@code I2}/{@code I4}):</b> Every exemplar carries
 *       {@link #TRUST_TIER} ({@code ADVISORY_EXEMPLAR}) and NEVER mutates or contributes to
 *       {@code KnowledgeGraph.identity()} ({@code canonicalLeakage = 0}).</li>
 *   <li><b>Zero-Overlap Anti-Leakage ({@code I5}):</b> {@link #assertZeroOverlap(Set)} and
 *       {@link #retrieveTopK} explicitly reject any {@code (repo, commitSha)} overlap with
 *       evaluation splits (preventing benchmark contamination).</li>
 *   <li><b>Deterministic RRF ({@code I2}):</b> Uses {@code k = 60} Reciprocal Rank Fusion over
 *       BM25 lexical score and SKG structural tag Jaccard similarity with deterministic ID tie-breaking.</li>
 * </ul>
 */
public final class RefactoringExemplarStore {

    public static final String TRUST_TIER = "ADVISORY_EXEMPLAR";
    public static final int DEFAULT_TOP_K = 3;
    public static final int RRF_K = 60;
    private static final double BM25_K1 = 1.5;
    private static final double BM25_B = 0.75;

    /** Provenance pin identifying a repository commit for strict train/eval overlap checks. */
    public record RepoCommit(String repository, String commitSha) {
        public RepoCommit {
            repository = normalizeKey(repository);
            commitSha = normalizeKey(commitSha);
        }

        private static String normalizeKey(String s) {
            return s == null ? "" : s.strip().toLowerCase(Locale.ROOT);
        }
    }

    /** Normalized pure refactoring exemplar stored in quarantine. */
    public record Exemplar(
            String id,
            AstRefactoringPurityVerifier.RefactoringKind kind,
            RepoCommit provenance,
            String summary,
            String beforeSnippet,
            String afterSnippet,
            Set<String> structuralTags,
            String trustTier,
            ContentDigest digest) {
        public Exemplar {
            id = Objects.requireNonNull(id, "id").strip();
            kind = Objects.requireNonNull(kind, "kind");
            provenance = Objects.requireNonNull(provenance, "provenance");
            summary = LlmTransport.redactSecrets(summary == null ? "" : summary).text();
            beforeSnippet = LlmTransport.redactSecrets(beforeSnippet == null ? "" : beforeSnippet).text();
            afterSnippet = LlmTransport.redactSecrets(afterSnippet == null ? "" : afterSnippet).text();
            var normTags = new TreeSet<String>();
            if (structuralTags != null) {
                for (String t : structuralTags) {
                    if (t != null && !t.isBlank()) normTags.add(t.strip().toLowerCase(Locale.ROOT));
                }
            }
            normTags.add(kind.name().toLowerCase(Locale.ROOT));
            structuralTags = Collections.unmodifiableSet(normTags);
            trustTier = TRUST_TIER;
            digest = digest == null
                    ? CanonicalJson.sha256(List.of(TRUST_TIER, id, kind.name(),
                    provenance.repository(), provenance.commitSha(), summary, beforeSnippet, afterSnippet,
                    List.copyOf(structuralTags)))
                    : digest;
        }
    }

    public record RetrievedExemplar(
            Exemplar exemplar,
            double rrfScore,
            double bm25Score,
            double structuralSimilarity,
            int bm25Rank,
            int structuralRank) {}

    private final Map<String, Exemplar> exemplarsById = new TreeMap<>();

    public RefactoringExemplarStore() {}

    public RefactoringExemplarStore(Collection<Exemplar> initial) {
        if (initial != null) {
            for (var ex : initial) {
                add(ex);
            }
        }
    }

    public void add(Exemplar exemplar) {
        Objects.requireNonNull(exemplar, "exemplar");
        exemplarsById.put(exemplar.id(), exemplar);
    }

    public int size() {
        return exemplarsById.size();
    }

    public List<Exemplar> all() {
        return List.copyOf(exemplarsById.values());
    }

    /**
     * Enforces zero {@code (repo, commitSha)} overlap between this quarantined exemplar store and an
     * evaluation benchmark split ({@code I5}). Throws {@link IllegalStateException} listing every leak.
     */
    public void assertZeroOverlap(Set<RepoCommit> evaluationRepoCommits) {
        if (evaluationRepoCommits == null || evaluationRepoCommits.isEmpty()) return;
        var leaks = new ArrayList<String>();
        for (var ex : exemplarsById.values()) {
            if (evaluationRepoCommits.contains(ex.provenance())) {
                leaks.add(ex.id() + "(" + ex.provenance().repository() + "@" + ex.provenance().commitSha() + ")");
            }
        }
        if (!leaks.isEmpty()) {
            throw new IllegalStateException("EXEMPLAR_EVAL_OVERLAP_DETECTED: " + String.join(", ", leaks));
        }
    }

    /**
     * Retrieves up to {@code topK} (default 3) pure refactoring exemplars using Reciprocal Rank Fusion
     * ({@code k = 60}) of BM25 lexical similarity and SKG structural tag Jaccard similarity, optionally
     * excluding any evaluation {@code excludedProvenance} entries.
     */
    public List<RetrievedExemplar> retrieveTopK(
            String queryText,
            Set<String> queryStructuralTags,
            Optional<AstRefactoringPurityVerifier.RefactoringKind> preferredKind,
            Set<RepoCommit> excludedProvenance,
            int topK) {
        int limit = Math.max(1, Math.min(10, topK <= 0 ? DEFAULT_TOP_K : topK));
        var pool = new ArrayList<Exemplar>();
        for (var ex : exemplarsById.values()) {
            if (excludedProvenance != null && excludedProvenance.contains(ex.provenance())) {
                continue;
            }
            if (preferredKind != null && preferredKind.isPresent() && ex.kind() != preferredKind.orElseThrow()) {
                continue;
            }
            pool.add(ex);
        }
        if (pool.isEmpty()) return List.of();

        // Tokenize documents & query for deterministic BM25
        List<String> queryTokens = tokenize(queryText);
        var docTokens = new HashMap<String, List<String>>();
        var df = new HashMap<String, Integer>();
        double totalLen = 0.0;
        for (var ex : pool) {
            List<String> toks = tokenize(ex.summary() + " " + ex.beforeSnippet() + " " + String.join(" ", ex.structuralTags()));
            docTokens.put(ex.id(), toks);
            totalLen += toks.size();
            for (String uniq : new HashSet<>(toks)) {
                df.merge(uniq, 1, Integer::sum);
            }
        }
        double avgDl = Math.max(1.0, totalLen / pool.size());

        var bm25Scores = new HashMap<String, Double>();
        var structScores = new HashMap<String, Double>();
        var normQueryTags = new HashSet<String>();
        if (queryStructuralTags != null) {
            for (String t : queryStructuralTags) {
                if (t != null && !t.isBlank()) normQueryTags.add(t.strip().toLowerCase(Locale.ROOT));
            }
        }
        if (preferredKind != null && preferredKind.isPresent()) {
            normQueryTags.add(preferredKind.orElseThrow().name().toLowerCase(Locale.ROOT));
        }

        for (var ex : pool) {
            bm25Scores.put(ex.id(), computeBm25(queryTokens, docTokens.get(ex.id()), df, pool.size(), avgDl));
            structScores.put(ex.id(), jaccard(normQueryTags, ex.structuralTags()));
        }

        // Deterministic rankings
        var byBm25 = new ArrayList<>(pool);
        byBm25.sort(Comparator.<Exemplar>comparingDouble(e -> bm25Scores.get(e.id())).reversed()
                .thenComparing(Exemplar::id));
        var byStruct = new ArrayList<>(pool);
        byStruct.sort(Comparator.<Exemplar>comparingDouble(e -> structScores.get(e.id())).reversed()
                .thenComparing(Exemplar::id));

        var bm25Rank = new HashMap<String, Integer>();
        var structRank = new HashMap<String, Integer>();
        for (int i = 0; i < byBm25.size(); i++) bm25Rank.put(byBm25.get(i).id(), i + 1);
        for (int i = 0; i < byStruct.size(); i++) structRank.put(byStruct.get(i).id(), i + 1);

        var scored = new ArrayList<RetrievedExemplar>(pool.size());
        for (var ex : pool) {
            int br = bm25Rank.get(ex.id());
            int sr = structRank.get(ex.id());
            double rrf = (1.0 / (RRF_K + br)) + (1.0 / (RRF_K + sr));
            scored.add(new RetrievedExemplar(
                    ex,
                    round6(rrf),
                    round6(bm25Scores.get(ex.id())),
                    round6(structScores.get(ex.id())),
                    br,
                    sr));
        }
        scored.sort(Comparator.comparingDouble(RetrievedExemplar::rrfScore).reversed()
                .thenComparing(r -> r.exemplar().id()));
        return List.copyOf(scored.subList(0, Math.min(limit, scored.size())));
    }

    /** Formats retrieved exemplars with explicit {@code [ADVISORY_EXEMPLAR]} quarantine framing. */
    public static String formatQuarantinedExemplarBlock(List<RetrievedExemplar> retrieved) {
        if (retrieved == null || retrieved.isEmpty()) return "";
        var sb = new StringBuilder("[ADVISORY_EXEMPLAR — QUARANTINED PURE REFACTORING EXAMPLES]\n");
        for (int i = 0; i < retrieved.size(); i++) {
            var r = retrieved.get(i);
            var ex = r.exemplar();
            sb.append("### Exemplar #").append(i + 1).append(" (").append(ex.kind().name())
                    .append(", rrf=").append(r.rrfScore()).append(")\n");
            sb.append("Summary: ").append(ex.summary()).append('\n');
            sb.append("Before:\n```java\n").append(ex.beforeSnippet()).append("\n```\n");
            sb.append("After:\n```java\n").append(ex.afterSnippet()).append("\n```\n");
        }
        return sb.toString();
    }

    private static double computeBm25(List<String> query, List<String> doc, Map<String, Integer> df, int nDocs, double avgDl) {
        if (query.isEmpty() || doc.isEmpty()) return 0.0;
        var tf = new HashMap<String, Integer>();
        for (String t : doc) tf.merge(t, 1, Integer::sum);
        double score = 0.0;
        double dl = doc.size();
        for (String q : new HashSet<>(query)) {
            int f = tf.getOrDefault(q, 0);
            if (f == 0) continue;
            int docFreq = df.getOrDefault(q, 0);
            double idf = Math.log(1.0 + (nDocs - docFreq + 0.5) / (docFreq + 0.5));
            score += idf * ((f * (BM25_K1 + 1.0)) / (f + BM25_K1 * (1.0 - BM25_B + BM25_B * (dl / avgDl))));
        }
        return score;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 0.0;
        var inter = new HashSet<>(a);
        inter.retainAll(b);
        var union = new HashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0.0 : (double) inter.size() / union.size();
    }

    private static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();
        var out = new ArrayList<String>();
        var cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_') {
                cur.append(Character.toLowerCase(c));
            } else if (!cur.isEmpty()) {
                out.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (!cur.isEmpty()) out.add(cur.toString());
        return out;
    }

    private static double round6(double v) {
        if (!Double.isFinite(v)) return 0.0;
        return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP).doubleValue();
    }
}
