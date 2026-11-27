package com.evolution.backend.query.trace;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.javaparser.slice.JavaMethodSlicer;
import com.evolution.analysis.query.trace.MethodSliceResult;
import com.evolution.analysis.query.trace.MethodSliceResult.SliceDirection;
import com.evolution.analysis.query.trace.VerticalTraceEngine;
import com.evolution.analysis.query.trace.VerticalTraceEngine.TraceHop;
import com.evolution.analysis.query.trace.VerticalTraceEngine.TraceRequest;
import com.evolution.analysis.query.trace.VerticalTraceEngine.VerticalLayer;
import com.evolution.analysis.query.trace.VerticalTraceEngine.VerticalTraceResult;
import com.evolution.backend.query.CapturedFileIndex;
import com.evolution.backend.query.advisory.AdvisoryOverlay;
import com.evolution.backend.query.advisory.AdvisoryOverlayService;
import com.evolution.backend.query.advisory.SqlDdlExtractor;
import com.evolution.backend.query.llm.LlmTransport;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Backend orchestrator for Moonshot #4 full-stack vertical trace advisory stitching and
 * pre-redacted lazy intra-method Java code slicing.
 *
 * <p>Invariants:
 * <ul>
 *   <li>Runs canonical {@link VerticalTraceEngine#trace(KnowledgeGraph, TraceRequest)} FIRST without
 *       mutating {@link KnowledgeGraph} ({@code I4}).</li>
 *   <li>Hard Quarantine ({@code I2}/{@code I3}): every stitched SQL/Terraform advisory hop carries
 *       {@link TrustTier#ADVISORY_UNVERIFIED}, never promotes to canonical tiers, and increments
 *       {@code advisoryHopCount} separately from {@code canonicalHopCount}.</li>
 *   <li>Hard Secret Rule ({@code I2}): captured Java source is redacted via
 *       {@link LlmTransport#redactDocumentForPath(String, String)} BEFORE passing to
 *       {@link JavaMethodSlicer#slice} so zero raw secrets ever appear in sliced statements while
 *       preserving exact 1-based line numbers.</li>
 * </ul>
 */
public final class FullStackVerticalTraceService {

    private static final int MAX_CONFIG_FILES_SCANNED = 32;
    private static final long MAX_CONFIG_FILE_BYTES = 131_072L;

    private static final Set<String> SQL_ADVISORY_KINDS = Set.of(
            "SQL_TABLE", "SQL_VIEW", "SQL_TRIGGER", "SQL_ROUTINE");
    private static final Set<String> TF_ADVISORY_KINDS = Set.of(
            "TF_RESOURCE", "TF_MODULE", "TF_DATA", "TF_OUTPUT");

    private static final Set<String> GENERIC_TOKENS = Set.of(
            "app", "api", "service", "services", "controller", "controllers", "rest", "entity", "entities",
            "repo", "repository", "repositories", "dao", "impl", "java", "spring", "boot", "main", "src",
            "test", "tests", "com", "org", "net", "io", "dto", "model", "models", "domain", "core", "base",
            "common", "util", "utils", "helper", "helpers", "config", "configuration", "data", "default",
            "public", "private", "web", "http", "client", "server", "handler", "manager", "factory",
            "builder", "mapper", "adapter", "port", "bean", "component", "type", "class", "method",
            "module", "package", "file", "path", "index", "item", "items", "list", "get", "set", "find",
            "save", "create", "update", "delete", "read", "write", "init", "run", "exec", "start", "stop",
            "info", "meta", "status", "health", "check", "value", "values", "name", "names", "key", "keys",
            "aws", "gcp", "google", "azure", "azurerm", "kubernetes", "helm", "null", "random", "local",
            "prod", "production", "staging", "stage", "dev", "development", "primary", "secondary", "main_db",
            "resource", "resources", "output", "outputs", "instance", "cluster", "bucket", "table", "schema");

    private static final Pattern WORD_SPLIT = Pattern.compile("[^a-z0-9]+");
    private static final Pattern DRIVE_PREFIX = Pattern.compile("^[A-Za-z]:.*");

    /** Typed exception for missing captured source files (mapped to HTTP 404). */
    public static final class CapturedSourceNotFoundException extends RuntimeException {
        private final String requestedPath;

        public CapturedSourceNotFoundException(String requestedPath) {
            super("Captured source file not found: " + requestedPath);
            this.requestedPath = requestedPath;
        }

        public String requestedPath() {
            return requestedPath;
        }
    }

    /** Result of pre-redacted lazy Java slicing along with captured provenance metadata. */
    public record SliceOutcome(
            MethodSliceResult result,
            Optional<ContentDigest> contentDigest,
            int redactedSecretCount) {}

    private FullStackVerticalTraceService() {}

    /**
     * Executes canonical {@link VerticalTraceEngine#trace(KnowledgeGraph, TraceRequest)} first, and
     * when {@code includeAdvisory} is {@code true}, deterministically stitches quarantined
     * {@link TrustTier#ADVISORY_UNVERIFIED} SQL DDL and Terraform/HCL hops from
     * {@link AdvisoryOverlayService}.
     */
    public static VerticalTraceResult trace(
            KnowledgeGraph graph,
            TraceRequest request,
            boolean includeAdvisory) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(request, "request");

        // 1. Canonical trace ALWAYS runs first over immutable KnowledgeGraph.
        VerticalTraceResult canonical = VerticalTraceEngine.trace(graph, request);
        if (!includeAdvisory) {
            return canonical;
        }

        AdvisoryOverlay.Snapshot advisory = AdvisoryOverlayService.forGraph(graph);
        if (advisory.nodes().isEmpty() && advisory.links().isEmpty()) {
            return canonical;
        }

        var advNodesById = new LinkedHashMap<String, AdvisoryOverlay.AdvisoryNode>();
        for (var node : advisory.nodes()) {
            advNodesById.putIfAbsent(node.id(), node);
        }

        // 2. Check if the seed itself directly targets a SQL_TABLE or TERRAFORM_RESOURCE advisory node.
        var directSeedNodes = findSeedMatchedAdvisoryNodes(advisory.nodes(), request.seed(), request.seedKind());

        // If canonical trace found 0 hops, but seed matched a SQL_TABLE mapped to a canonical JPA entity,
        // trace upward from that canonical entity so full-stack canonical layers are preserved alongside
        // the strictly quarantined ADVISORY_UNVERIFIED SQL/Terraform hops.
        if (canonical.hops().isEmpty() && !directSeedNodes.isEmpty()) {
            String linkedCanonicalSeed = findLinkedCanonicalEntitySeed(directSeedNodes, advisory.links());
            if (linkedCanonicalSeed != null) {
                var upwardReq = new TraceRequest(
                        request.context(),
                        linkedCanonicalSeed,
                        Optional.empty(),
                        request.world(),
                        request.maxDepth(),
                        request.maxHops());
                VerticalTraceResult upward = VerticalTraceEngine.trace(graph, upwardReq);
                if (!upward.hops().isEmpty()) {
                    canonical = new VerticalTraceResult(
                            upward.graphDigest(),
                            upward.context(),
                            request.seed(),
                            upward.totalHops(),
                            upward.canonicalHopCount(),
                            0,
                            upward.truncated(),
                            upward.layers(),
                            upward.hops(),
                            upward.gaps());
                }
            }
        }

        // 3. Collect canonical hop descriptors for deterministic advisory stitching.
        var canonicalNodeIds = new HashSet<String>();
        var canonicalLabelsLower = new HashSet<String>();
        var canonicalSimpleNamesLower = new HashSet<String>();
        var canonicalPathsLower = new HashSet<String>();
        var canonicalJpaSimpleNames = new LinkedHashSet<String>();
        var domainTokens = new LinkedHashSet<String>();

        for (TraceHop hop : canonical.hops()) {
            canonicalNodeIds.add(hop.nodeId());
            String lbl = hop.label();
            if (lbl != null && !lbl.isBlank()) {
                String lblLow = lbl.strip().toLowerCase(Locale.ROOT);
                canonicalLabelsLower.add(lblLow);
                String simple = simpleNameOf(lbl.strip());
                if (!simple.isBlank()) {
                    canonicalSimpleNamesLower.add(simple.toLowerCase(Locale.ROOT));
                    if (hop.layer() == VerticalLayer.JPA_ENTITY) {
                        canonicalJpaSimpleNames.add(simple);
                    }
                    addDomainTokens(domainTokens, simple);
                }
                addDomainTokens(domainTokens, lbl);
            }
            hop.sourcePath().ifPresent(sp -> {
                String norm = CapturedFileIndex.normalizePath(sp).toLowerCase(Locale.ROOT);
                if (!norm.isEmpty()) {
                    canonicalPathsLower.add(norm);
                }
            });
        }

        // Candidate advisory hops keyed by advisory node ID (deterministic insertion order).
        record StitchedCandidate(AdvisoryOverlay.AdvisoryNode node, VerticalLayer layer, String edgeKind) {}
        var stitchedById = new LinkedHashMap<String, StitchedCandidate>();

        // 3a. Direct seed-matched SQL / Terraform advisory nodes.
        for (var node : directSeedNodes) {
            VerticalLayer layer = layerForAdvisoryNode(node);
            if (layer != null) {
                stitchedById.putIfAbsent(node.id(), new StitchedCandidate(node, layer, "ADVISORY_SEED_MATCH"));
                addAdvisoryNodeTokens(domainTokens, node);
            }
        }

        // 3b. Advisory links directly touching any canonical hop (JPA <-> SQL_TABLE, docs/config <-> TF).
        for (var link : advisory.links()) {
            boolean srcIsCanonical = matchesCanonicalHop(
                    link.sourceId(), link.sourceLabel(), canonicalNodeIds, canonicalLabelsLower,
                    canonicalSimpleNamesLower, canonicalPathsLower);
            boolean dstIsCanonical = matchesCanonicalHop(
                    link.targetId(), link.targetLabel(), canonicalNodeIds, canonicalLabelsLower,
                    canonicalSimpleNamesLower, canonicalPathsLower);
            if (!srcIsCanonical && !dstIsCanonical) {
                continue;
            }
            if (dstIsCanonical) {
                var advNode = advNodesById.get(link.sourceId());
                if (advNode != null) {
                    VerticalLayer layer = layerForAdvisoryNode(advNode);
                    if (layer != null) {
                        stitchedById.putIfAbsent(advNode.id(), new StitchedCandidate(advNode, layer, link.kind()));
                        addAdvisoryNodeTokens(domainTokens, advNode);
                    }
                }
            }
            if (srcIsCanonical) {
                var advNode = advNodesById.get(link.targetId());
                if (advNode != null) {
                    VerticalLayer layer = layerForAdvisoryNode(advNode);
                    if (layer != null) {
                        stitchedById.putIfAbsent(advNode.id(), new StitchedCandidate(advNode, layer, link.kind()));
                        addAdvisoryNodeTokens(domainTokens, advNode);
                    }
                }
            }
        }

        // 3c. Fallback JPA entity name <-> SQL_TABLE match when canonical JPA_ENTITY hops exist.
        if (!canonicalJpaSimpleNames.isEmpty()) {
            for (var node : advisory.nodes()) {
                if (!"SQL_TABLE".equals(node.kind()) || stitchedById.containsKey(node.id())) {
                    continue;
                }
                String tableBare = extractTableBareName(node);
                if (tableBare.isEmpty()) continue;
                for (String simple : canonicalJpaSimpleNames) {
                    if (matchesEntitySimpleToTable(simple, tableBare)) {
                        stitchedById.putIfAbsent(node.id(),
                                new StitchedCandidate(node, VerticalLayer.SQL_TABLE, "SQL_TABLE_MAPS_JPA_ENTITY"));
                        addAdvisoryNodeTokens(domainTokens, node);
                        break;
                    }
                }
            }
        }

        // 3d. Expand 1-hop SQL FOREIGN_KEY / SQL_TRIGGER_ON links from already-stitched SQL nodes.
        var initialSqlIds = new LinkedHashSet<String>();
        for (var cand : stitchedById.values()) {
            if (cand.layer() == VerticalLayer.SQL_TABLE) {
                initialSqlIds.add(cand.node().id());
            }
        }
        if (!initialSqlIds.isEmpty()) {
            for (var link : advisory.links()) {
                if (!"FOREIGN_KEY".equals(link.kind()) && !"SQL_TRIGGER_ON".equals(link.kind())) {
                    continue;
                }
                if (initialSqlIds.contains(link.sourceId())) {
                    var targetNode = advNodesById.get(link.targetId());
                    if (targetNode != null && layerForAdvisoryNode(targetNode) == VerticalLayer.SQL_TABLE) {
                        stitchedById.putIfAbsent(targetNode.id(),
                                new StitchedCandidate(targetNode, VerticalLayer.SQL_TABLE, link.kind()));
                        addAdvisoryNodeTokens(domainTokens, targetNode);
                    }
                } else if (initialSqlIds.contains(link.targetId())) {
                    var sourceNode = advNodesById.get(link.sourceId());
                    if (sourceNode != null && layerForAdvisoryNode(sourceNode) == VerticalLayer.SQL_TABLE) {
                        stitchedById.putIfAbsent(sourceNode.id(),
                                new StitchedCandidate(sourceNode, VerticalLayer.SQL_TABLE, link.kind()));
                        addAdvisoryNodeTokens(domainTokens, sourceNode);
                    }
                }
            }
        }

        // 3e. Spring/config/properties & SQL/domain <-> TERRAFORM_RESOURCE matching.
        boolean hasAnyCanonicalOrSqlAnchor = !canonical.hops().isEmpty() || !initialSqlIds.isEmpty();
        if (hasAnyCanonicalOrSqlAnchor) {
            SpringConfigSummary configSummary = scanCapturedSpringConfig(graph);
            boolean traceTouchesPersistenceOrSpring = !initialSqlIds.isEmpty()
                    || canonical.hops().stream().anyMatch(h ->
                            h.layer() == VerticalLayer.JPA_ENTITY
                                    || h.layer() == VerticalLayer.SPRING_CONTROLLER
                                    || h.layer() == VerticalLayer.SPRING_DI
                                    || h.layer() == VerticalLayer.SERVICE);

            for (var node : advisory.nodes()) {
                if (!TF_ADVISORY_KINDS.contains(node.kind()) || stitchedById.containsKey(node.id())) {
                    continue;
                }
                String edgeKind = matchTerraformNode(
                        node, domainTokens, configSummary, traceTouchesPersistenceOrSpring);
                if (edgeKind != null) {
                    stitchedById.putIfAbsent(node.id(),
                            new StitchedCandidate(node, VerticalLayer.TERRAFORM_RESOURCE, edgeKind));
                }
            }
        }

        // 3f. Expand 1-hop TF_REFERENCES from already-stitched Terraform nodes.
        var initialTfIds = new LinkedHashSet<String>();
        for (var cand : stitchedById.values()) {
            if (cand.layer() == VerticalLayer.TERRAFORM_RESOURCE) {
                initialTfIds.add(cand.node().id());
            }
        }
        if (!initialTfIds.isEmpty()) {
            for (var link : advisory.links()) {
                if (!"TF_REFERENCES".equals(link.kind())) {
                    continue;
                }
                if (initialTfIds.contains(link.sourceId())) {
                    var targetNode = advNodesById.get(link.targetId());
                    if (targetNode != null && TF_ADVISORY_KINDS.contains(targetNode.kind())) {
                        stitchedById.putIfAbsent(targetNode.id(),
                                new StitchedCandidate(targetNode, VerticalLayer.TERRAFORM_RESOURCE, link.kind()));
                    }
                } else if (initialTfIds.contains(link.targetId())) {
                    var sourceNode = advNodesById.get(link.sourceId());
                    if (sourceNode != null && TF_ADVISORY_KINDS.contains(sourceNode.kind())) {
                        stitchedById.putIfAbsent(sourceNode.id(),
                                new StitchedCandidate(sourceNode, VerticalLayer.TERRAFORM_RESOURCE, link.kind()));
                    }
                }
            }
        }

        if (stitchedById.isEmpty()) {
            return canonical;
        }

        // 4. Deterministically sort advisory candidates and apply maxHops bound honestly.
        var sortedAdvisory = new ArrayList<>(stitchedById.values());
        sortedAdvisory.sort(Comparator
                .comparingInt((StitchedCandidate c) -> c.layer().ordinal())
                .thenComparing(c -> c.node().sourcePath())
                .thenComparingInt(c -> c.node().startLine())
                .thenComparing(c -> c.node().id()));

        int maxHops = request.maxHops();
        List<TraceHop> canonicalHops = canonical.hops();
        int remainingBudget = Math.max(0, maxHops - canonicalHops.size());
        boolean truncated = canonical.truncated() || sortedAdvisory.size() > remainingBudget;

        var retainedAdvisory = sortedAdvisory.subList(0, Math.min(sortedAdvisory.size(), remainingBudget));
        if (retainedAdvisory.isEmpty()) {
            if (truncated == canonical.truncated()) {
                return canonical;
            }
            return new VerticalTraceResult(
                    canonical.graphDigest(),
                    canonical.context(),
                    canonical.seed(),
                    canonical.totalHops(),
                    canonical.canonicalHopCount(),
                    canonical.advisoryHopCount(),
                    true,
                    canonical.layers(),
                    canonical.hops(),
                    canonical.gaps());
        }

        // 5. Materialize combined hops with Hard Quarantine: every stitched hop MUST be ADVISORY_UNVERIFIED.
        record OrderedHop(boolean isAdvisory, int originalOrder, TraceHop hop) {}
        var combined = new ArrayList<OrderedHop>(canonicalHops.size() + retainedAdvisory.size());
        for (int i = 0; i < canonicalHops.size(); i++) {
            combined.add(new OrderedHop(false, i, canonicalHops.get(i)));
        }
        for (int i = 0; i < retainedAdvisory.size(); i++) {
            var cand = retainedAdvisory.get(i);
            var n = cand.node();
            TraceHop advHop = new TraceHop(
                    canonicalHops.size() + i,
                    cand.layer(),
                    n.id(),
                    n.label(),
                    n.kind(),
                    TrustTier.ADVISORY_UNVERIFIED,
                    Optional.empty(),
                    Optional.empty(),
                    n.sourcePath().isBlank() ? Optional.empty() : Optional.of(n.sourcePath()),
                    OptionalInt.of(n.startLine()),
                    OptionalInt.of(n.endLine()),
                    n.contentSha256().isBlank() ? Optional.empty() : Optional.of(n.contentSha256()),
                    Optional.of(cand.edgeKind()),
                    Optional.empty());
            combined.add(new OrderedHop(true, i, advHop));
        }

        combined.sort(Comparator
                .comparingInt((OrderedHop o) -> o.hop().layer().ordinal())
                .thenComparing(OrderedHop::isAdvisory)
                .thenComparingInt(OrderedHop::originalOrder)
                .thenComparing(o -> o.hop().nodeId()));

        var finalHops = new ArrayList<TraceHop>(combined.size());
        var layerSet = new TreeSet<VerticalLayer>(Comparator.comparingInt(Enum::ordinal));
        int canonicalCount = 0;
        int advisoryCount = 0;
        for (int i = 0; i < combined.size(); i++) {
            TraceHop raw = combined.get(i).hop();
            TraceHop reindexed = new TraceHop(
                    i,
                    raw.layer(),
                    raw.nodeId(),
                    raw.label(),
                    raw.kind(),
                    raw.trustTier(),
                    raw.classification(),
                    raw.witnessId(),
                    raw.sourcePath(),
                    raw.startLine(),
                    raw.endLine(),
                    raw.contentSha256(),
                    raw.edgeKind(),
                    raw.gapReason());
            finalHops.add(reindexed);
            layerSet.add(reindexed.layer());
            if (reindexed.trustTier() == TrustTier.ADVISORY_UNVERIFIED) {
                advisoryCount++;
            } else {
                canonicalCount++;
            }
        }

        return new VerticalTraceResult(
                canonical.graphDigest(),
                canonical.context(),
                canonical.seed(),
                canonicalCount + advisoryCount,
                canonicalCount,
                advisoryCount,
                truncated,
                layerSet.stream().map(Enum::name).toList(),
                finalHops,
                canonical.gaps());
    }

    /**
     * Reads a captured Java source file from {@link CapturedFileIndex}, redacts all secret patterns
     * via {@link LlmTransport#redactDocumentForPath(String, String)} BEFORE parsing/slicing, and
     * computes a lazy intra-procedural CFG/DFG/CDG slice via {@link JavaMethodSlicer}.
     */
    public static MethodSliceResult sliceCapturedJava(
            KnowledgeGraph graph,
            String rawPath,
            Optional<String> methodSignature,
            int line,
            SliceDirection direction,
            int maxStatements) {
        return sliceCapturedJavaWithMetadata(graph, rawPath, methodSignature, line, direction, maxStatements).result();
    }

    /** Same as {@link #sliceCapturedJava} with captured content digest and redaction count metadata. */
    public static SliceOutcome sliceCapturedJavaWithMetadata(
            KnowledgeGraph graph,
            String rawPath,
            Optional<String> methodSignature,
            int line,
            SliceDirection direction,
            int maxStatements) {
        Objects.requireNonNull(graph, "graph");
        String validatedPath = validateRelativePath(rawPath);
        if (line < 1) {
            throw new IllegalArgumentException("Slice seed line must be >= 1");
        }
        SliceDirection safeDirection = direction == null ? SliceDirection.BACKWARD : direction;
        int safeMax = maxStatements <= 0
                ? JavaMethodSlicer.DEFAULT_MAX_STATEMENTS
                : Math.max(JavaMethodSlicer.MIN_MAX_STATEMENTS,
                        Math.min(JavaMethodSlicer.MAX_MAX_STATEMENTS, maxStatements));
        Optional<String> safeSig = methodSignature == null
                ? Optional.empty()
                : methodSignature.map(String::strip).filter(s -> !s.isEmpty());

        var fileIndex = CapturedFileIndex.forGraph(graph);
        CapturedFileIndex.Entry entry = resolveCapturedEntry(fileIndex, validatedPath);

        boolean isJava = "java".equalsIgnoreCase(entry.language())
                || entry.path().toLowerCase(Locale.ROOT).endsWith(".java");
        if (!isJava) {
            var res = new MethodSliceResult(
                    entry.path(),
                    safeSig.orElse(""),
                    line,
                    safeDirection,
                    TrustTier.SOURCE_SYNTAX,
                    false,
                    0,
                    List.of(),
                    List.of(),
                    List.of("Intra-procedural CFG/DFG/CDG slicing is supported for Java (.java) files only; "
                            + "captured file '" + entry.path() + "' has language '" + entry.language() + "'."));
            return new SliceOutcome(res, entry.digest(), 0);
        }

        if (!entry.readable()) {
            var res = new MethodSliceResult(
                    entry.path(),
                    safeSig.orElse(""),
                    line,
                    safeDirection,
                    TrustTier.SOURCE_SYNTAX,
                    false,
                    0,
                    List.of(),
                    List.of(),
                    List.of("Captured file '" + entry.path() + "' is binary, oversized, or unreadable as UTF-8; "
                            + "intra-procedural slicing is unavailable."));
            return new SliceOutcome(res, entry.digest(), 0);
        }

        Optional<String> rawTextOpt = fileIndex.readText(graph, entry);
        if (rawTextOpt.isEmpty()) {
            var res = new MethodSliceResult(
                    entry.path(),
                    safeSig.orElse(""),
                    line,
                    safeDirection,
                    TrustTier.SOURCE_SYNTAX,
                    false,
                    0,
                    List.of(),
                    List.of(),
                    List.of("Captured file '" + entry.path() + "' could not be decoded as UTF-8 text "
                            + "(binary or missing CAS payload); intra-procedural slicing is unavailable."));
            return new SliceOutcome(res, entry.digest(), 0);
        }

        // Hard Secret Rule (I2): ALWAYS redact via LlmTransport BEFORE passing to JavaMethodSlicer.
        // redactDocumentForPath is strictly line-preserving so 1-based line numbers remain exact.
        LlmTransport.RedactedDocument redacted = LlmTransport.redactDocumentForPath(entry.path(), rawTextOpt.orElseThrow());
        MethodSliceResult sliced = JavaMethodSlicer.slice(
                entry.path(),
                redacted.text(),
                safeSig,
                line,
                safeDirection,
                safeMax);
        return new SliceOutcome(sliced, entry.digest(), redacted.totalRedactedCount());
    }

    // ─── Validation & Captured File Resolution ───

    private static String validateRelativePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("Source path is required");
        }
        String trimmed = rawPath.strip();
        if (trimmed.length() > 1024 || trimmed.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid source path");
        }
        if (trimmed.startsWith("/") || trimmed.startsWith("\\") || DRIVE_PREFIX.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Source path must be repository-relative");
        }
        String slashNorm = trimmed.replace('\\', '/');
        for (String segment : slashNorm.split("/", -1)) {
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("Source path must not contain parent-directory traversal");
            }
        }
        String normalized = CapturedFileIndex.normalizePath(trimmed);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Source path is required");
        }
        return normalized;
    }

    private static CapturedFileIndex.Entry resolveCapturedEntry(CapturedFileIndex fileIndex, String normalizedPath) {
        var exact = fileIndex.findExact(normalizedPath);
        if (exact.isPresent()) {
            return exact.orElseThrow();
        }
        var matches = new ArrayList<CapturedFileIndex.Entry>();
        String suffix = "/" + normalizedPath;
        for (var e : fileIndex.entries()) {
            if (e.path().equalsIgnoreCase(normalizedPath) || e.path().endsWith(suffix)) {
                matches.add(e);
            }
        }
        if (matches.isEmpty()) {
            throw new CapturedSourceNotFoundException(normalizedPath);
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("Ambiguous source path '" + normalizedPath + "'; provide full repository-relative path.");
        }
        return matches.getFirst();
    }

    // ─── Advisory Matching Helpers ───

    private static VerticalLayer layerForAdvisoryNode(AdvisoryOverlay.AdvisoryNode node) {
        if (SQL_ADVISORY_KINDS.contains(node.kind())) {
            return VerticalLayer.SQL_TABLE;
        }
        if (TF_ADVISORY_KINDS.contains(node.kind())) {
            return VerticalLayer.TERRAFORM_RESOURCE;
        }
        return null;
    }

    private static List<AdvisoryOverlay.AdvisoryNode> findSeedMatchedAdvisoryNodes(
            List<AdvisoryOverlay.AdvisoryNode> nodes,
            String seed,
            Optional<String> seedKindOpt) {
        String s = seed == null ? "" : seed.strip();
        if (s.isEmpty()) return List.of();
        String sLow = s.toLowerCase(Locale.ROOT);
        String sPath = CapturedFileIndex.normalizePath(s).toLowerCase(Locale.ROOT);
        String sk = seedKindOpt.map(v -> v.strip().toUpperCase(Locale.ROOT)).orElse("");

        boolean restrictSql = sk.equals("SQL") || sk.equals("SQL_TABLE") || sk.equals("DDL");
        boolean restrictTf = sk.equals("TERRAFORM") || sk.equals("TERRAFORM_RESOURCE")
                || sk.equals("TF") || sk.equals("TF_RESOURCE") || sk.equals("IAC");

        var exactMatches = new ArrayList<AdvisoryOverlay.AdvisoryNode>();
        var fuzzyMatches = new ArrayList<AdvisoryOverlay.AdvisoryNode>();
        for (var node : nodes) {
            VerticalLayer layer = layerForAdvisoryNode(node);
            if (layer == null) continue;
            if (restrictSql && layer != VerticalLayer.SQL_TABLE) continue;
            if (restrictTf && layer != VerticalLayer.TERRAFORM_RESOURCE) continue;

            String labelLow = node.label().toLowerCase(Locale.ROOT);
            String pathLow = node.sourcePath().toLowerCase(Locale.ROOT);
            String tableBare = extractTableBareName(node);
            String tfName = extractTfShortName(node);

            boolean exact = node.id().equals(s)
                    || labelLow.equals(sLow)
                    || (!tableBare.isEmpty() && tableBare.equals(sLow))
                    || (!tfName.isEmpty() && tfName.equals(sLow))
                    || (!sPath.isEmpty() && !pathLow.isEmpty() && pathLow.equals(sPath));
            if (exact) {
                exactMatches.add(node);
            } else if (sLow.length() >= 3
                    && (labelLow.contains(sLow)
                            || (!tableBare.isEmpty() && tableBare.contains(sLow))
                            || (!tfName.isEmpty() && tfName.contains(sLow)))) {
                fuzzyMatches.add(node);
            }
        }
        return !exactMatches.isEmpty() ? exactMatches : fuzzyMatches;
    }

    private static String findLinkedCanonicalEntitySeed(
            List<AdvisoryOverlay.AdvisoryNode> directSeedNodes,
            List<AdvisoryOverlay.AdvisoryLink> links) {
        var seedIds = new HashSet<String>();
        for (var n : directSeedNodes) {
            seedIds.add(n.id());
        }
        for (var link : links) {
            if (!"SQL_TABLE_MAPS_JPA_ENTITY".equals(link.kind())) continue;
            if (seedIds.contains(link.sourceId())) {
                if (link.targetId() != null && !link.targetId().startsWith("file:") && !link.targetId().isBlank()) {
                    return link.targetId();
                }
                if (link.targetLabel() != null && !link.targetLabel().isBlank()) {
                    return link.targetLabel();
                }
            }
        }
        return null;
    }

    private static boolean matchesCanonicalHop(
            String id,
            String label,
            Set<String> canonicalNodeIds,
            Set<String> canonicalLabelsLower,
            Set<String> canonicalSimpleNamesLower,
            Set<String> canonicalPathsLower) {
        if (id != null && canonicalNodeIds.contains(id)) {
            return true;
        }
        if (label != null && !label.isBlank()) {
            String low = label.strip().toLowerCase(Locale.ROOT);
            if (canonicalLabelsLower.contains(low)) {
                return true;
            }
            String simple = simpleNameOf(label.strip()).toLowerCase(Locale.ROOT);
            if (!simple.isEmpty() && canonicalSimpleNamesLower.contains(simple)) {
                return true;
            }
        }
        if (id != null && id.startsWith("file:")) {
            String rest = id.substring("file:".length());
            int hash = rest.indexOf('#');
            String pathPart = hash >= 0 ? rest.substring(0, hash) : rest;
            String norm = CapturedFileIndex.normalizePath(pathPart).toLowerCase(Locale.ROOT);
            if (!norm.isEmpty() && canonicalPathsLower.contains(norm)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesEntitySimpleToTable(String entitySimpleName, String tableBareLower) {
        String snake = SqlDdlExtractor.camelToSnake(entitySimpleName).toLowerCase(Locale.ROOT);
        if (snake.isEmpty()) return false;
        if (tableBareLower.equals(snake) || tableBareLower.equals(entitySimpleName.toLowerCase(Locale.ROOT))) {
            return true;
        }
        if (tableBareLower.equals(snake + "s") || tableBareLower.equals(snake + "es")) {
            return true;
        }
        if (snake.endsWith("y") && tableBareLower.equals(snake.substring(0, snake.length() - 1) + "ies")) {
            return true;
        }
        // Strip common "Entity" suffix (e.g., OrderEntity -> order / orders)
        if (entitySimpleName.endsWith("Entity") && entitySimpleName.length() > "Entity".length()) {
            String stem = entitySimpleName.substring(0, entitySimpleName.length() - "Entity".length());
            return matchesEntitySimpleToTable(stem, tableBareLower);
        }
        return false;
    }

    private record SpringConfigSummary(
            boolean hasConfigFiles,
            boolean hasDatasourceConfig,
            Set<String> configTokens,
            String lowerRedactedText) {}

    private static SpringConfigSummary scanCapturedSpringConfig(KnowledgeGraph graph) {
        var fileIndex = CapturedFileIndex.forGraph(graph);
        var tokens = new LinkedHashSet<String>();
        var sb = new StringBuilder();
        int scanned = 0;
        boolean hasDatasource = false;
        for (var entry : fileIndex.entries()) {
            if (scanned >= MAX_CONFIG_FILES_SCANNED) break;
            if (!entry.readable() || entry.size() > MAX_CONFIG_FILE_BYTES) continue;
            if (!isSpringOrInfraConfigFile(entry.path())) continue;
            var textOpt = fileIndex.readText(graph, entry);
            if (textOpt.isEmpty()) continue;
            scanned++;
            // Pre-redact before scanning so no secret tokens ever enter matching memory
            String redacted = LlmTransport.redactDocumentForPath(entry.path(), textOpt.orElseThrow()).text();
            String low = redacted.toLowerCase(Locale.ROOT);
            if (low.contains("spring.datasource") || low.contains("datasource:") || low.contains("jdbc:")) {
                hasDatasource = true;
            }
            sb.append(low).append('\n');
            for (String tok : WORD_SPLIT.split(low)) {
                if (tok.length() >= 3 && !GENERIC_TOKENS.contains(tok)) {
                    tokens.add(tok);
                }
            }
        }
        return new SpringConfigSummary(scanned > 0, hasDatasource, tokens, sb.toString());
    }

    private static boolean isSpringOrInfraConfigFile(String path) {
        String low = path.toLowerCase(Locale.ROOT);
        String name = low.substring(low.lastIndexOf('/') + 1);
        return (name.startsWith("application") && (name.endsWith(".properties") || name.endsWith(".yml") || name.endsWith(".yaml")))
                || (name.startsWith("bootstrap") && (name.endsWith(".properties") || name.endsWith(".yml") || name.endsWith(".yaml")))
                || name.equals("docker-compose.yml") || name.equals("docker-compose.yaml");
    }

    private static String matchTerraformNode(
            AdvisoryOverlay.AdvisoryNode node,
            Set<String> domainTokens,
            SpringConfigSummary config,
            boolean traceTouchesPersistenceOrSpring) {
        String tfAddress = node.label().toLowerCase(Locale.ROOT);
        String tfShort = extractTfShortName(node);
        String tfType = extractTfType(node);

        var candidateNames = new LinkedHashSet<String>();
        if (!tfShort.isEmpty()) candidateNames.add(tfShort);
        if (!tfAddress.isEmpty()) candidateNames.add(tfAddress);
        String attrName = extractTfAttribute(node, "name");
        String attrBucket = extractTfAttribute(node, "bucket");
        String attrEngine = extractTfAttribute(node, "engine");
        if (!attrName.isEmpty()) candidateNames.add(attrName);
        if (!attrBucket.isEmpty()) candidateNames.add(attrBucket);

        // 1. Direct Spring config/properties <-> Terraform match
        if (config.hasConfigFiles()) {
            for (String cand : candidateNames) {
                if (cand.length() >= 3 && !GENERIC_TOKENS.contains(cand)
                        && containsWholeToken(config.lowerRedactedText(), cand)) {
                    return "ADVISORY_CONFIG_TO_TERRAFORM";
                }
            }
            if (config.hasDatasourceConfig() && traceTouchesPersistenceOrSpring && isDatabaseTerraformType(tfType)) {
                if (!attrEngine.isEmpty() && config.lowerRedactedText().contains(attrEngine)) {
                    return "ADVISORY_CONFIG_TO_TERRAFORM";
                }
                for (String tok : splitMeaningfulTokens(tfShort + " " + attrName)) {
                    if (config.configTokens().contains(tok)) {
                        return "ADVISORY_CONFIG_TO_TERRAFORM";
                    }
                }
            }
        }

        // 2. Domain / SQL table token <-> Terraform resource name or attribute
        if (!domainTokens.isEmpty()) {
            var tfTokens = new LinkedHashSet<String>();
            tfTokens.addAll(splitMeaningfulTokens(tfShort));
            tfTokens.addAll(splitMeaningfulTokens(attrName));
            tfTokens.addAll(splitMeaningfulTokens(attrBucket));
            for (String tok : tfTokens) {
                if (matchesAnyDomainToken(tok, domainTokens)) {
                    return "ADVISORY_TERRAFORM_LINK";
                }
            }
        }

        return null;
    }

    private static boolean isDatabaseTerraformType(String tfType) {
        return tfType.contains("db_instance")
                || tfType.contains("rds")
                || tfType.contains("sql_database")
                || tfType.contains("postgresql")
                || tfType.contains("mysql")
                || tfType.contains("aurora")
                || tfType.contains("dynamodb")
                || tfType.contains("database");
    }

    private static boolean containsWholeToken(String haystack, String needle) {
        if (haystack.isEmpty() || needle.isEmpty()) return false;
        Pattern p = Pattern.compile("(?<![a-z0-9_])" + Pattern.quote(needle) + "(?![a-z0-9_])");
        Matcher m = p.matcher(haystack);
        return m.find();
    }

    private static boolean matchesAnyDomainToken(String tfToken, Set<String> domainTokens) {
        if (domainTokens.contains(tfToken)) return true;
        if (tfToken.endsWith("s") && tfToken.length() > 3 && domainTokens.contains(tfToken.substring(0, tfToken.length() - 1))) {
            return true;
        }
        if (domainTokens.contains(tfToken + "s")) {
            return true;
        }
        return false;
    }

    private static String extractTableBareName(AdvisoryOverlay.AdvisoryNode node) {
        Object t = node.metadata().get("tableName");
        if (t instanceof String s && !s.isBlank()) {
            return s.strip().toLowerCase(Locale.ROOT);
        }
        String lbl = node.label().strip().toLowerCase(Locale.ROOT);
        int dot = lbl.lastIndexOf('.');
        return dot >= 0 && dot < lbl.length() - 1 ? lbl.substring(dot + 1) : lbl;
    }

    private static String extractTfShortName(AdvisoryOverlay.AdvisoryNode node) {
        for (String key : List.of("resourceName", "moduleName", "dataName", "outputName")) {
            Object v = node.metadata().get(key);
            if (v instanceof String s && !s.isBlank()) {
                return s.strip().toLowerCase(Locale.ROOT);
            }
        }
        String lbl = node.label().strip().toLowerCase(Locale.ROOT);
        int dot = lbl.lastIndexOf('.');
        return dot >= 0 && dot < lbl.length() - 1 ? lbl.substring(dot + 1) : lbl;
    }

    private static String extractTfType(AdvisoryOverlay.AdvisoryNode node) {
        for (String key : List.of("resourceType", "dataType")) {
            Object v = node.metadata().get(key);
            if (v instanceof String s && !s.isBlank()) {
                return s.strip().toLowerCase(Locale.ROOT);
            }
        }
        return "";
    }

    private static String extractTfAttribute(AdvisoryOverlay.AdvisoryNode node, String attrKey) {
        if (node.metadata().get("attributes") instanceof Map<?, ?> attrs
                && attrs.get(attrKey) instanceof String s && !s.isBlank()) {
            return s.strip().toLowerCase(Locale.ROOT);
        }
        return "";
    }

    private static void addAdvisoryNodeTokens(Set<String> out, AdvisoryOverlay.AdvisoryNode node) {
        if (SQL_ADVISORY_KINDS.contains(node.kind())) {
            String bare = extractTableBareName(node);
            if (bare.length() >= 3 && !GENERIC_TOKENS.contains(bare)) {
                out.add(bare);
            }
            out.addAll(splitMeaningfulTokens(bare));
        } else if (TF_ADVISORY_KINDS.contains(node.kind())) {
            out.addAll(splitMeaningfulTokens(extractTfShortName(node)));
            out.addAll(splitMeaningfulTokens(extractTfAttribute(node, "name")));
            out.addAll(splitMeaningfulTokens(extractTfAttribute(node, "bucket")));
        }
    }

    private static void addDomainTokens(Set<String> out, String raw) {
        if (raw == null || raw.isBlank()) return;
        String snake = SqlDdlExtractor.camelToSnake(raw).toLowerCase(Locale.ROOT);
        for (String tok : WORD_SPLIT.split(snake)) {
            if (tok.length() >= 3 && !GENERIC_TOKENS.contains(tok)) {
                out.add(tok);
            }
        }
    }

    private static Set<String> splitMeaningfulTokens(String raw) {
        var set = new LinkedHashSet<String>();
        if (raw == null || raw.isBlank()) return set;
        for (String tok : WORD_SPLIT.split(raw.toLowerCase(Locale.ROOT))) {
            if (tok.length() >= 3 && !GENERIC_TOKENS.contains(tok)) {
                set.add(tok);
            }
        }
        return set;
    }

    private static String simpleNameOf(String name) {
        if (name == null) return "";
        String cleaned = name;
        int paren = cleaned.indexOf('(');
        if (paren >= 0) cleaned = cleaned.substring(0, paren);
        int dot = cleaned.lastIndexOf('.');
        int slash = cleaned.lastIndexOf('/');
        int sep = Math.max(dot, slash);
        return sep >= 0 && sep < cleaned.length() - 1 ? cleaned.substring(sep + 1) : cleaned;
    }
}
