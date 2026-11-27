package com.evolution.backend.github;

import com.evolution.analysis.assessment.v2.ArchitectureAssessmentV2Service;
import com.evolution.analysis.assessment.v2.ArchitectureScoreModelV2;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.GraphStore;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.metrics.ArchitectureMetric;
import com.evolution.analysis.metrics.ArchitectureMetricsService;
import com.evolution.analysis.policy.PolicyEvaluation;
import com.evolution.analysis.query.ArchitectureQueryService;
import com.evolution.backend.auth.RequestContext;
import com.evolution.backend.query.CapturedFileIndex;
import com.evolution.backend.query.GitHubProvenance;
import com.evolution.backend.query.PublishedReports;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Two-phase confirmed GitHub Issue creation from architectural findings or hotspots,
 * enriched with GitHub source permalinks, bounded Mermaid dependency graphs, Score v2 impact,
 * and passive CODEOWNERS attribution.
 */
public final class GitHubIssueService {
    public static final String PLAN_SCHEMA = "github.issue-plan:v1";
    private static final long PLAN_TTL_MS = TimeUnit.MINUTES.toMillis(15);
    private static final int MAX_PENDING_PLANS = 64;

    public record PrepareRequest(
            ContentDigest graphId,
            Optional<ContentDigest> reportId,
            Optional<ContentDigest> findingId,
            Optional<String> hotspotSymbol,
            Optional<String> titleOverride,
            List<String> labels,
            List<String> assignees) {
    }

    public record IssuePlan(
            String schema,
            ContentDigest issuePlanDigest,
            String confirmationToken,
            Instant expiresAt,
            String repository,
            String title,
            String bodyMarkdown,
            List<String> labels,
            List<String> assignees,
            List<String> codeowners,
            Optional<String> permalink) {
        public Map<String, Object> toView() {
            var map = new LinkedHashMap<String, Object>();
            map.put("schema", schema);
            map.put("issuePlanDigest", issuePlanDigest.value());
            map.put("confirmationToken", confirmationToken);
            map.put("expiresAt", expiresAt);
            map.put("repository", repository);
            map.put("title", title);
            map.put("bodyMarkdown", bodyMarkdown);
            map.put("labels", labels);
            map.put("assignees", assignees);
            map.put("codeowners", codeowners);
            permalink.ifPresent(url -> map.put("permalink", url));
            return map;
        }
    }

    public record PublishRequest(
            String confirmationToken,
            ContentDigest issuePlanDigest,
            boolean confirmedByUser,
            Optional<String> titleOverride,
            Optional<String> bodyOverride,
            Optional<List<String>> labelsOverride,
            Optional<List<String>> assigneesOverride) {
    }

    public record PublishReceipt(
            int issueNumber,
            String htmlUrl,
            String title,
            String state,
            List<String> labels,
            List<String> assignees) {
        public Map<String, Object> toView() {
            var map = new LinkedHashMap<String, Object>();
            map.put("issueNumber", issueNumber);
            map.put("number", issueNumber);
            map.put("htmlUrl", htmlUrl);
            map.put("url", htmlUrl);
            map.put("title", title);
            map.put("state", state);
            map.put("labels", labels);
            map.put("assignees", assignees);
            return map;
        }
    }

    public static final class IssueException extends RuntimeException {
        private final int status;
        private final String code;

        public IssueException(int status, String code, String message) {
            super(GitHubTokenBroker.redact(message));
            this.status = status;
            this.code = code;
        }

        public int status() { return status; }
        public String code() { return code; }
    }

    private record PendingIssuePlan(
            String ownerUserId,
            String setupId,
            GitHubCoordinates.Coordinates coords,
            IssuePlan plan,
            long expiresAtMs) {
    }

    private final GitHubRestClient restClient;
    private final GraphStore graphs;
    private final PublishedReports reports;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, PendingIssuePlan> pendingPlans = Collections.synchronizedMap(
            new LinkedHashMap<>(32, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, PendingIssuePlan> eldest) {
                    return size() > MAX_PENDING_PLANS;
                }
            });

    public GitHubIssueService(GitHubRestClient restClient, GraphStore graphs, PublishedReports reports) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        this.graphs = Objects.requireNonNull(graphs, "graphs");
        this.reports = reports;
    }

    /** Step 1: Prepare a confirmed GitHub Issue preview with zero remote writes. */
    public IssuePlan prepare(String setupId, GitHubCoordinates.Coordinates coords, PrepareRequest req) {
        KnowledgeGraph graph = graphs.load(Objects.requireNonNull(req.graphId(), "graphId"))
                .orElseThrow(() -> new IssueException(404, "GRAPH_NOT_FOUND", "Snapshot graph not found."));
        var codeowners = GitHubProvenance.CodeownersIndex.fromGraph(graph);
        var repoCoords = GitHubProvenance.extractFromGraph(graph);

        String targetSymbol = req.hotspotSymbol().orElse("");
        String targetPath = "";
        int startLine = 1;
        int endLine = 1;
        String defaultTitle;
        var mermaidEdges = new ArrayList<String>();
        var body = new StringBuilder();

        PolicyEvaluation.Finding matchedFinding = null;
        if (req.reportId().isPresent() && reports != null) {
            var repOpt = reports.get(req.reportId().orElseThrow());
            if (repOpt.isPresent()) {
                var eval = repOpt.orElseThrow().evaluation();
                if (req.findingId().isPresent()) {
                    matchedFinding = eval.findings().stream()
                            .filter(f -> f.identity().equals(req.findingId().orElseThrow()))
                            .findFirst().orElse(null);
                } else if (!eval.findings().isEmpty()) {
                    matchedFinding = eval.findings().getFirst();
                }
            }
        }

        if (matchedFinding != null) {
            var header = matchedFinding.rule();
            var bundle = matchedFinding.bundle();
            String src = bundle.path().isEmpty() ? "" : bundle.path().getFirst();
            String dst = bundle.path().size() > 1 ? bundle.path().getLast() : src;
            String relKind = bundle.relationships().isEmpty() || bundle.relationships().getFirst().kinds().isEmpty()
                    ? header.relationships().iterator().next()
                    : bundle.relationships().getFirst().kinds().getFirst();
            targetSymbol = src;
            Loc loc = findLocFromBundleOrSymbol(graph, bundle, src);
            targetPath = loc.path();
            startLine = loc.startLine();
            endLine = loc.endLine();
            defaultTitle = "[Architecture] " + header.id() + ": " + shortName(src) + " -> " + shortName(dst);
            mermaidEdges.add("  A[\"" + escapeMermaid(shortName(src))
                    + "\"] -->|\"" + escapeMermaid(relKind) + "\"| B[\""
                    + escapeMermaid(shortName(dst)) + "\"]");
            body.append("## Architecture Policy Finding\n\n");
            body.append("- **Rule:** `").append(header.id()).append('@').append(header.version()).append("`\n");
            body.append("- **Severity:** `").append(matchedFinding.classification().name()).append("`\n");
            body.append("- **Source:** `").append(src).append("`\n");
            body.append("- **Target:** `").append(dst).append("`\n");
            body.append("- **Finding Digest:** `").append(matchedFinding.identity().value()).append("`\n");
        } else {
            if (targetSymbol.isBlank()) {
                targetSymbol = graph.nodes().stream()
                        .filter(n -> !n.kind().equals("REPOSITORY") && !n.kind().equals("CAPABILITY_GAP"))
                        .map(KnowledgeGraph.Node::identity)
                        .findFirst().orElse(coords.fullName());
            }
            var loc = findPathForSymbol(graph, targetSymbol);
            targetPath = loc.path();
            startLine = loc.startLine();
            endLine = loc.endLine();
            defaultTitle = "[Architecture Hotspot] Refactor " + shortName(targetSymbol);
            body.append("## Architecture Hotspot\n\n");
            body.append("- **Symbol:** `").append(targetSymbol).append("`\n");
            int edgeCount = 0;
            for (var edge : graph.edges()) {
                String targetId = edge.target().identities().isEmpty()
                        ? edge.target().unresolvedReference().orElse("")
                        : edge.target().identities().getFirst();
                if ((edge.source().equals(targetSymbol) || targetId.equals(targetSymbol)) && edgeCount < 8) {
                    mermaidEdges.add("  N" + edgeCount + "S[\"" + escapeMermaid(shortName(edge.source()))
                            + "\"] --> N" + edgeCount + "T[\"" + escapeMermaid(shortName(targetId)) + "\"]");
                    edgeCount++;
                }
            }
        }

        Optional<String> permalink = Optional.empty();
        if (!targetPath.isEmpty()) {
            if (repoCoords.isPresent()) {
                permalink = GitHubProvenance.blobPermalink(repoCoords.orElseThrow(), targetPath, startLine, endLine);
            }
            body.append("- **File:** `").append(targetPath).append(':').append(startLine).append("`");
            if (permalink.isPresent()) {
                body.append(" ([View on GitHub](").append(permalink.orElseThrow()).append("))");
            }
            body.append('\n');
        }

        // Architecture Score v2 snapshot context
        try {
            var guard = ArchitectureQueryService.operationalGuard(() -> false,
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(15), 8_000_000);
            var queries = new ArchitectureMetricsService(graph, ArchitectureMetric.Limits.defaults(), guard,
                    Optional.empty(), List.of()).queries();
            var assess = new ArchitectureAssessmentV2Service()
                    .assessRepository(queries, Map.of(), Optional.empty(), ArchitectureScoreModelV2.standard());
            if (assess.score().isPresent()) {
                body.append("- **Architecture Score v2:** `").append(assess.score().orElseThrow())
                        .append(" / 100` (").append(assess.healthBand()).append(")\n");
            }
        } catch (RuntimeException ignored) {}

        List<String> owners = targetPath.isEmpty() ? List.of() : codeowners.ownersOf(targetPath);
        if (!owners.isEmpty()) {
            body.append("- **CODEOWNERS:** ").append(String.join(", ", owners)).append('\n');
        }

        if (!mermaidEdges.isEmpty()) {
            body.append("\n### Dependency Context\n\n```mermaid\nflowchart LR\n");
            for (String mLine : mermaidEdges) {
                body.append(mLine).append('\n');
            }
            body.append("```\n");
        }

        body.append("\n<details><summary>Cryptographic Provenance</summary>\n\n");
        body.append("- **Graph Digest:** `").append(graph.identity().value()).append("`\n");
        body.append("- **Snapshot:** `").append(graph.snapshot().value()).append("`\n");
        req.reportId().ifPresent(r -> body.append("- **Report Digest:** `").append(r.value()).append("`\n"));
        body.append("</details>\n");

        String title = req.titleOverride().filter(s -> !s.isBlank()).map(String::strip).orElse(defaultTitle);
        if (title.length() > 200) title = title.substring(0, 200);

        var labels = new LinkedHashSet<String>();
        labels.add("architecture");
        if (matchedFinding != null) labels.add("policy-violation");
        if (req.labels() != null) {
            for (String l : req.labels()) {
                if (l != null && !l.isBlank()) labels.add(l.strip());
            }
        }

        // GitHub Issues assignees[] only accepts individual logins (not @org/team handles)
        var assignees = new LinkedHashSet<String>();
        if (req.assignees() != null) {
            for (String a : req.assignees()) {
                String clean = cleanIndividualAssignee(a);
                if (!clean.isEmpty()) assignees.add(clean);
            }
        }
        if (assignees.isEmpty()) {
            for (String o : owners) {
                String clean = cleanIndividualAssignee(o);
                if (!clean.isEmpty()) assignees.add(clean);
            }
        }

        String markdown = body.toString();
        var preimage = new LinkedHashMap<String, Object>();
        preimage.put("schema", PLAN_SCHEMA);
        preimage.put("repository", coords.fullName());
        preimage.put("title", title);
        preimage.put("labels", List.copyOf(labels));
        preimage.put("assignees", List.copyOf(assignees));
        preimage.put("bodyDigest", ContentDigest.sha256Utf8(markdown).value());
        ContentDigest planDigest = ContentDigest.sha256Utf8(CanonicalJson.write(preimage));

        String token = randomTokenHex();
        long expiresAtMs = System.currentTimeMillis() + PLAN_TTL_MS;
        var plan = new IssuePlan(
                PLAN_SCHEMA,
                planDigest,
                token,
                Instant.ofEpochMilli(expiresAtMs),
                coords.fullName(),
                title,
                markdown,
                List.copyOf(labels),
                List.copyOf(assignees),
                owners,
                permalink);
        purgeExpired();
        pendingPlans.put(token, new PendingIssuePlan(
                RequestContext.currentUserId(), setupId, coords, plan, expiresAtMs));
        return plan;
    }

    /** Step 2: Explicitly user-confirmed GitHub Issue publication. */
    public PublishReceipt publish(String setupId, PublishRequest req, BooleanSupplier cancelled) {
        if (!req.confirmedByUser()) {
            throw new IssueException(400, "EXPLICIT_USER_CONFIRMATION_REQUIRED",
                    "confirmedByUser=true is mandatory before creating a GitHub Issue.");
        }
        if (req.confirmationToken() == null || req.confirmationToken().isBlank() || req.issuePlanDigest() == null) {
            throw new IssueException(400, "INVALID_CONFIRMATION", "confirmationToken and issuePlanDigest are required.");
        }
        purgeExpired();
        String caller = RequestContext.currentUserId();
        PendingIssuePlan pending;
        synchronized (pendingPlans) {
            PendingIssuePlan existing = pendingPlans.get(req.confirmationToken());
            if (existing == null || System.currentTimeMillis() > existing.expiresAtMs()) {
                pendingPlans.remove(req.confirmationToken());
                throw new IssueException(409, "CONFIRMATION_TOKEN_INVALID_OR_EXPIRED",
                        "Issue confirmation token is unknown, expired, or already used. Run prepare again.");
            }
            if (!existing.ownerUserId().equals(caller)) {
                throw new IssueException(403, "ACCESS_DENIED",
                        "Issue confirmation token belongs to another user session.");
            }
            if (!existing.setupId().equals(setupId)) {
                throw new IssueException(409, "ISSUE_SETUP_MISMATCH",
                        "Issue confirmation token does not belong to this repository connection.");
            }
            if (!existing.plan().issuePlanDigest().equals(req.issuePlanDigest())) {
                throw new IssueException(409, "ISSUE_PLAN_DIGEST_MISMATCH",
                        "issuePlanDigest does not match the prepared issue plan.");
            }
            pending = pendingPlans.remove(req.confirmationToken());
        }

        var plan = pending.plan();
        String title = req.titleOverride().filter(s -> !s.isBlank()).map(String::strip).orElse(plan.title());
        String body = req.bodyOverride().filter(s -> !s.isBlank()).orElse(plan.bodyMarkdown());
        List<String> labels = req.labelsOverride().orElse(plan.labels());
        List<String> assignees = req.assigneesOverride().orElse(plan.assignees()).stream()
                .map(GitHubIssueService::cleanIndividualAssignee)
                .filter(s -> !s.isEmpty())
                .distinct().toList();

        var created = restClient.createIssue(pending.coords(), title, body, labels, assignees, cancelled);
        return new PublishReceipt(
                created.number(), created.htmlUrl(), created.title(), created.state(),
                created.labels(), created.assignees());
    }

    private record Loc(String path, int startLine, int endLine) {}

    private static Loc findLocFromBundleOrSymbol(KnowledgeGraph graph, PolicyEvaluation.EvidenceBundle bundle, String symbolId) {
        var evidenceById = new HashMap<ContentDigest, GraphEvidence>();
        for (var ev : graph.evidence()) evidenceById.put(ev.identity(), ev);
        for (var se : bundle.sources()) {
            var ev = evidenceById.get(se.evidence());
            var fromEv = parseLocFromEvidence(ev);
            if (fromEv != null && !fromEv.path().isEmpty()) {
                return new Loc(fromEv.path(), Math.max(1, se.span().startLine()), Math.max(se.span().startLine(), se.span().endLine()));
            }
        }
        return findPathForSymbol(graph, symbolId);
    }

    private static Loc parseLocFromEvidence(GraphEvidence ev) {
        if (ev == null) return null;
        try {
            var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(),
                    new GraphJson.Limits(1024 * 1024, 24, 50_000)));
            Object spanObj = obj.get("span");
            if (spanObj instanceof Map<?, ?> m) {
                var span = GraphJson.object(m);
                String p = span.get("path") instanceof String s ? CapturedFileIndex.normalizePath(s) : "";
                int sLine = span.get("startLine") instanceof Number n ? Math.max(1, n.intValue()) : 1;
                int eLine = span.get("endLine") instanceof Number n ? Math.max(sLine, n.intValue()) : sLine;
                if (!p.isEmpty()) return new Loc(p, sLine, eLine);
            }
            if (obj.get("path") instanceof String p && !p.isBlank()) {
                return new Loc(CapturedFileIndex.normalizePath(p), 1, 1);
            }
        } catch (RuntimeException ignored) {}
        return null;
    }

    private static Loc findPathForSymbol(KnowledgeGraph graph, String symbolId) {
        var evidenceById = new HashMap<ContentDigest, GraphEvidence>();
        for (var ev : graph.evidence()) evidenceById.put(ev.identity(), ev);
        for (var node : graph.nodes()) {
            if (node.identity().equals(symbolId)) {
                for (var evId : node.evidence()) {
                    var ev = evidenceById.get(evId);
                    if (ev == null) continue;
                    try {
                        var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(),
                                new GraphJson.Limits(1024 * 1024, 24, 50_000)));
                        Object spanObj = obj.get("span");
                        if (spanObj instanceof Map<?, ?> m) {
                            var span = GraphJson.object(m);
                            String p = span.get("path") instanceof String s ? CapturedFileIndex.normalizePath(s) : "";
                            int sLine = span.get("startLine") instanceof Number n ? Math.max(1, n.intValue()) : 1;
                            int eLine = span.get("endLine") instanceof Number n ? Math.max(sLine, n.intValue()) : sLine;
                            if (!p.isEmpty()) return new Loc(p, sLine, eLine);
                        }
                    } catch (RuntimeException ignored) {}
                }
            }
        }
        return new Loc("", 1, 1);
    }

    private static String cleanIndividualAssignee(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String s = raw.strip();
        if (s.startsWith("@")) s = s.substring(1);
        if (s.contains("/") || s.contains("@") || !s.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?")) {
            return "";
        }
        return s;
    }

    private static String shortName(String fqn) {
        if (fqn == null) return "";
        int idx = Math.max(fqn.lastIndexOf('.'), fqn.lastIndexOf(':'));
        return idx >= 0 && idx + 1 < fqn.length() ? fqn.substring(idx + 1) : fqn;
    }

    private static String escapeMermaid(String s) {
        return s == null ? "" : s.replace("\"", "'").replace("\r", " ").replace("\n", " ");
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        synchronized (pendingPlans) {
            pendingPlans.values().removeIf(p -> now > p.expiresAtMs());
        }
    }

    private String randomTokenHex() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
