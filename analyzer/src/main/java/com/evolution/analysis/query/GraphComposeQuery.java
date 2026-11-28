package com.evolution.analysis.query;

import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.graph.GraphJson;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Strict typed JSON-AST for compositional multi-hop architecture graph queries
 * ({@code graph_compose_query}) and pointer-indexed live schema views ({@code evo://schema}).
 *
 * <p>Replaces free-form query strings (such as Cypher) with a closed, statically validated AST:
 * <ul>
 *   <li>Zero free-form syntax failures: every structural, alias, hop-bound, limit, operator,
 *       field, and bounded-regex violation raises a deterministic {@link ValidationException}
 *       with a typed {@link ErrorCode}.</li>
 *   <li>Zero external graph server (in-process read-only SKG traversal, {@code I1}).</li>
 *   <li>Hard bounds: {@code 1 <= minHops <= maxHops <= 4}, {@code 1 <= limit <= 50} (default {@code 25}),
 *       bounded regex length &amp; ReDoS static+runtime guards, and {@code < 2 s} wall-time cap.</li>
 *   <li>Pointer-indexed results: every returned node carries a compact {@code pointerRef}
 *       ({@code path:startLine-endLine@sha256Prefix}) instead of raw file bodies, driving lazy
 *       {@code read_captured_source} lookups.</li>
 * </ul>
 */
public record GraphComposeQuery(
        Optional<String> context,
        List<NodePattern> matchNodes,
        List<EdgePattern> matchEdges,
        List<FilterClause> filters,
        List<String> returnFields,
        int limit
) {
    public static final String SCHEMA_VERSION = "graph-compose-query:v1";
    public static final String SCHEMA_VIEW_VERSION = "evo-schema:v1";

    public static final int DEFAULT_LIMIT = 25;
    public static final int MIN_LIMIT = 1;
    public static final int MAX_LIMIT = 50;
    public static final int MIN_HOPS = 1;
    public static final int MAX_HOPS = 4;

    public static final int MAX_MATCH_NODES = 12;
    public static final int MAX_MATCH_EDGES = 16;
    public static final int MAX_FILTERS = 32;
    public static final int MAX_ALIAS_LENGTH = 32;
    public static final int MAX_PATTERN_LENGTH = 128;
    public static final int MAX_FILTER_VALUE_LENGTH = 256;
    public static final int MAX_EDGE_KINDS_PER_PATTERN = 16;
    public static final int MAX_GLOB_WILDCARDS = 6;
    public static final int MAX_REGEX_STEPS = 4_096;
    public static final int MAX_REGEX_INPUT_CHARS = 512;
    public static final int POINTER_SHA_PREFIX_LEN = 12;

    public static final long DEFAULT_TIMEOUT_MILLIS = 2_000L;
    public static final int DEFAULT_MAX_VISITED_STATES = 25_000;

    public static final String POINTER_REF_FORMAT = "path:startLine-endLine@sha256Prefix";
    public static final String LAZY_READ_TOOL = "read_captured_source";

    public static final List<String> ALLOWED_RETURN_FIELDS = List.of(
            "id", "fqn", "kind", "trustTier", "span", "pointerRef");
    public static final Set<String> ALLOWED_RETURN_FIELD_SET = Set.copyOf(ALLOWED_RETURN_FIELDS);

    public static final List<String> QUERYABLE_FILTER_FIELDS = List.of(
            "context", "fqn", "id", "kind", "module", "name", "origin",
            "path", "pointerRef", "sourcePath", "sourceSet", "trustTier");
    public static final Set<String> QUERYABLE_FILTER_FIELD_SET = Set.copyOf(QUERYABLE_FILTER_FIELDS);

    private static final Set<String> ALLOWED_TOP_KEYS = Set.of(
            "graph", "context", "matchNodes", "matchEdges", "filters", "returnFields", "limit");
    private static final Set<String> ALLOWED_NODE_KEYS = Set.of(
            "alias", "kind", "namePattern", "trustTier");
    private static final Set<String> ALLOWED_EDGE_KEYS = Set.of(
            "from", "to", "edgeKinds", "minHops", "maxHops");
    private static final Set<String> ALLOWED_FILTER_KEYS = Set.of(
            "alias", "field", "op", "value");

    private static final Pattern ALIAS_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]{0,31}$");
    private static final Pattern KIND_TOKEN_PATTERN = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._:-]{0,127}$");
    private static final GraphJson.Limits QUERY_JSON_LIMITS = new GraphJson.Limits(65_536, 16, 4_096);

    /** Deterministic validation error codes preventing uncaught query syntax failures. */
    public enum ErrorCode {
        INVALID_QUERY_OBJECT,
        UNKNOWN_QUERY_FIELD,
        EMPTY_MATCH_NODES,
        TOO_MANY_MATCH_NODES,
        INVALID_ALIAS,
        DUPLICATE_ALIAS,
        UNKNOWN_ALIAS_REFERENCE,
        INVALID_NODE_KIND,
        INVALID_TRUST_TIER,
        TOO_MANY_MATCH_EDGES,
        INVALID_EDGE_KIND,
        INVALID_HOP_BOUNDS,
        SELF_LOOP_EDGE_PATTERN,
        TOO_MANY_FILTERS,
        UNKNOWN_FILTER_FIELD,
        UNKNOWN_FILTER_OPERATOR,
        INVALID_FILTER_VALUE,
        UNSAFE_REGEX_PATTERN,
        OVERSIZED_PATTERN,
        INVALID_RETURN_FIELD,
        INVALID_LIMIT,
        INVALID_CONTEXT
    }

    /** Closed filter operator set for typed compositional queries. */
    public enum FilterOp {
        EQ,
        NEQ,
        CONTAINS,
        PREFIX,
        REGEX_BOUNDED,
        EXISTS
    }

    /** Deterministic, structured validation error carrying a typed {@link ErrorCode}. */
    public static final class ValidationException extends IllegalArgumentException {
        private final ErrorCode errorCode;
        private final String field;

        public ValidationException(ErrorCode errorCode, String field, String message) {
            super(errorCode.name() + " [" + (field == null ? "" : field) + "]: " + message);
            this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
            this.field = field == null ? "" : field;
        }

        public ErrorCode errorCode() {
            return errorCode;
        }

        public String field() {
            return field;
        }

        public Map<String, Object> toErrorView() {
            var err = new TreeMap<String, Object>();
            err.put("code", errorCode.name());
            err.put("field", field);
            err.put("message", getMessage());
            var root = new TreeMap<String, Object>();
            root.put("schema", SCHEMA_VERSION);
            root.put("error", Collections.unmodifiableMap(err));
            return Collections.unmodifiableMap(root);
        }
    }

    /** Typed node pattern clause: {@code {alias, kind?, namePattern?, trustTier?}}. */
    public record NodePattern(
            String alias,
            Optional<String> kind,
            Optional<String> namePattern,
            Optional<TrustTier> trustTier
    ) {
        public NodePattern {
            if (alias == null || alias.isBlank() || alias.length() > MAX_ALIAS_LENGTH
                    || !ALIAS_PATTERN.matcher(alias).matches()) {
                throw new ValidationException(ErrorCode.INVALID_ALIAS, "matchNodes.alias",
                        "Alias must match ^[a-zA-Z][a-zA-Z0-9_]{0,31}$");
            }
            kind = normalizeOptionalToken(kind, ErrorCode.INVALID_NODE_KIND, "matchNodes[" + alias + "].kind");
            namePattern = normalizeOptionalNamePattern(namePattern, "matchNodes[" + alias + "].namePattern");
            trustTier = trustTier == null ? Optional.empty() : trustTier;
        }

        public Map<String, Object> toMap() {
            var m = new TreeMap<String, Object>();
            m.put("alias", alias);
            kind.ifPresent(k -> m.put("kind", k));
            namePattern.ifPresent(p -> m.put("namePattern", p));
            trustTier.ifPresent(t -> m.put("trustTier", t.name()));
            return Collections.unmodifiableMap(m);
        }
    }

    /** Typed multi-hop edge pattern clause: {@code {from, to, edgeKinds[], minHops, maxHops}}. */
    public record EdgePattern(
            String from,
            String to,
            List<String> edgeKinds,
            int minHops,
            int maxHops
    ) {
        public EdgePattern {
            if (from == null || !ALIAS_PATTERN.matcher(from).matches()) {
                throw new ValidationException(ErrorCode.INVALID_ALIAS, "matchEdges.from",
                        "Edge 'from' must be a valid alias");
            }
            if (to == null || !ALIAS_PATTERN.matcher(to).matches()) {
                throw new ValidationException(ErrorCode.INVALID_ALIAS, "matchEdges.to",
                        "Edge 'to' must be a valid alias");
            }
            if (from.equals(to)) {
                throw new ValidationException(ErrorCode.SELF_LOOP_EDGE_PATTERN, "matchEdges",
                        "Self-loop edge patterns ('" + from + "' -> '" + to + "') are disallowed");
            }
            if (minHops < MIN_HOPS || maxHops > MAX_HOPS || minHops > maxHops) {
                throw new ValidationException(ErrorCode.INVALID_HOP_BOUNDS, "matchEdges[" + from + "->" + to + "]",
                        "Hop bounds must satisfy 1 <= minHops <= maxHops <= 4 (got minHops="
                                + minHops + ", maxHops=" + maxHops + ")");
            }
            List<String> rawKinds = edgeKinds == null ? List.of() : edgeKinds;
            if (rawKinds.size() > MAX_EDGE_KINDS_PER_PATTERN) {
                throw new ValidationException(ErrorCode.INVALID_EDGE_KIND, "matchEdges.edgeKinds",
                        "At most " + MAX_EDGE_KINDS_PER_PATTERN + " edgeKinds are allowed per edge pattern");
            }
            var normalizedKinds = new TreeSet<String>();
            for (String ek : rawKinds) {
                if (ek == null || ek.isBlank() || !KIND_TOKEN_PATTERN.matcher(ek.strip()).matches()) {
                    throw new ValidationException(ErrorCode.INVALID_EDGE_KIND, "matchEdges.edgeKinds",
                            "Invalid edge kind token: " + ek);
                }
                normalizedKinds.add(ek.strip());
            }
            edgeKinds = List.copyOf(normalizedKinds);
        }

        public Map<String, Object> toMap() {
            var m = new TreeMap<String, Object>();
            m.put("from", from);
            m.put("to", to);
            m.put("edgeKinds", edgeKinds);
            m.put("minHops", minHops);
            m.put("maxHops", maxHops);
            return Collections.unmodifiableMap(m);
        }
    }

    /** Typed filter clause: {@code {alias, field, op, value}}. */
    public record FilterClause(
            String alias,
            String field,
            FilterOp op,
            Optional<String> value
    ) {
        public FilterClause {
            if (alias == null || !ALIAS_PATTERN.matcher(alias).matches()) {
                throw new ValidationException(ErrorCode.INVALID_ALIAS, "filters.alias",
                        "Filter alias must be a valid identifier");
            }
            if (field == null || !QUERYABLE_FILTER_FIELD_SET.contains(field.strip())) {
                throw new ValidationException(ErrorCode.UNKNOWN_FILTER_FIELD, "filters.field",
                        "Unsupported filter field '" + field + "'; allowed: " + QUERYABLE_FILTER_FIELDS);
            }
            field = field.strip();
            if (op == null) {
                throw new ValidationException(ErrorCode.UNKNOWN_FILTER_OPERATOR, "filters.op",
                        "Filter operator is required");
            }
            value = value == null ? Optional.empty() : value;
            if (op == FilterOp.EXISTS) {
                if (value.isPresent()) {
                    String v = value.orElseThrow().strip().toLowerCase(Locale.ROOT);
                    if (!"true".equals(v) && !"false".equals(v)) {
                        throw new ValidationException(ErrorCode.INVALID_FILTER_VALUE, "filters.value",
                                "EXISTS filter value must be 'true', 'false', or omitted");
                    }
                    value = Optional.of(v);
                }
            } else {
                if (value.isEmpty()) {
                    throw new ValidationException(ErrorCode.INVALID_FILTER_VALUE, "filters.value",
                            "Filter operator " + op.name() + " requires a non-null value");
                }
                String v = value.orElseThrow();
                if (hasControlChars(v)) {
                    throw new ValidationException(ErrorCode.INVALID_FILTER_VALUE, "filters.value",
                            "Filter value must not contain ISO control characters");
                }
                if (op == FilterOp.REGEX_BOUNDED) {
                    validateBoundedRegex(v, "filters[" + alias + "." + field + "].value");
                } else if (v.length() > MAX_FILTER_VALUE_LENGTH) {
                    throw new ValidationException(ErrorCode.OVERSIZED_PATTERN, "filters.value",
                            "Filter value exceeds maximum length of " + MAX_FILTER_VALUE_LENGTH);
                }
            }
        }

        public Map<String, Object> toMap() {
            var m = new TreeMap<String, Object>();
            m.put("alias", alias);
            m.put("field", field);
            m.put("op", op.name());
            value.ifPresent(v -> m.put("value", v));
            return Collections.unmodifiableMap(m);
        }
    }

    /** Compact source span metadata attached to projected nodes (zero raw source bodies). */
    public record SpanRef(
            Optional<String> path,
            int startLine,
            int endLine,
            String sha256Prefix
    ) {
        public SpanRef {
            path = path == null ? Optional.empty() : path;
            startLine = Math.max(0, startLine);
            endLine = Math.max(startLine, endLine);
            sha256Prefix = Objects.requireNonNull(sha256Prefix, "sha256Prefix");
        }

        public Map<String, Object> toView() {
            var m = new TreeMap<String, Object>();
            path.ifPresent(p -> m.put("path", p));
            m.put("startLine", startLine);
            m.put("endLine", endLine);
            m.put("sha256Prefix", sha256Prefix);
            return Collections.unmodifiableMap(m);
        }
    }

    /** Pointer-indexed node projection returned inside {@link ComposeMatch}. */
    public record ProjectedNode(
            String id,
            String fqn,
            String kind,
            TrustTier trustTier,
            SpanRef span,
            String pointerRef
    ) {
        public ProjectedNode {
            Objects.requireNonNull(id, "id");
            fqn = fqn == null ? id : fqn;
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(trustTier, "trustTier");
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(pointerRef, "pointerRef");
        }

        /** Projects only the requested {@code returnFields} while ALWAYS preserving {@code pointerRef}. */
        public Map<String, Object> toView(List<String> requestedFields) {
            var fields = requestedFields == null || requestedFields.isEmpty()
                    ? ALLOWED_RETURN_FIELDS
                    : requestedFields;
            var m = new TreeMap<String, Object>();
            for (String f : fields) {
                switch (f) {
                    case "id" -> m.put("id", id);
                    case "fqn" -> m.put("fqn", fqn);
                    case "kind" -> m.put("kind", kind);
                    case "trustTier" -> m.put("trustTier", trustTier.name());
                    case "span" -> m.put("span", span.toView());
                    case "pointerRef" -> m.put("pointerRef", pointerRef);
                    default -> {}
                }
            }
            // Pointer-indexed contract: every returned node ALWAYS carries pointerRef.
            m.put("pointerRef", pointerRef);
            return Collections.unmodifiableMap(m);
        }
    }

    /** Compact witness for one satisfied {@link EdgePattern} inside a {@link ComposeMatch}. */
    public record MatchedEdge(
            String from,
            String to,
            String fromId,
            String toId,
            int hops,
            List<String> edgeKinds
    ) {
        public MatchedEdge {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(fromId, "fromId");
            Objects.requireNonNull(toId, "toId");
            edgeKinds = List.copyOf(Objects.requireNonNull(edgeKinds, "edgeKinds"));
        }

        public Map<String, Object> toView() {
            var m = new TreeMap<String, Object>();
            m.put("from", from);
            m.put("to", to);
            m.put("fromId", fromId);
            m.put("toId", toId);
            m.put("hops", hops);
            m.put("edgeKinds", edgeKinds);
            return Collections.unmodifiableMap(m);
        }
    }

    /** Single deterministic match tuple sorted by {@code (score DESC, entityId ASC)}. */
    public record ComposeMatch(
            String entityId,
            BigDecimal score,
            Map<String, ProjectedNode> bindings,
            List<MatchedEdge> matchedEdges
    ) {
        public ComposeMatch {
            Objects.requireNonNull(entityId, "entityId");
            Objects.requireNonNull(score, "score");
            bindings = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(bindings, "bindings")));
            matchedEdges = List.copyOf(Objects.requireNonNull(matchedEdges, "matchedEdges"));
        }

        public Map<String, Object> toView(List<String> returnFields) {
            var m = new TreeMap<String, Object>();
            m.put("entityId", entityId);
            m.put("score", score);
            var nodesMap = new TreeMap<String, Object>();
            for (var e : bindings.entrySet()) {
                nodesMap.put(e.getKey(), e.getValue().toView(returnFields));
            }
            m.put("bindings", Collections.unmodifiableMap(nodesMap));
            if (!matchedEdges.isEmpty()) {
                m.put("matchedEdges", matchedEdges.stream().map(MatchedEdge::toView).toList());
            }
            return Collections.unmodifiableMap(m);
        }
    }

    /** Bounded, deterministic result of {@link ArchitectureQueryService#composeQuery}. */
    public record ComposeResult(
            String schema,
            ContentDigest graphDigest,
            Optional<String> context,
            AssessmentStatus status,
            ArchitectureQuery.StopReason stopReason,
            boolean truncated,
            int limit,
            int totalMatched,
            int visitedStates,
            List<String> returnFields,
            List<ComposeMatch> matches,
            List<CapabilityGapRecord> gaps
    ) {
        public ComposeResult {
            schema = schema == null || schema.isBlank() ? SCHEMA_VERSION : schema;
            Objects.requireNonNull(graphDigest, "graphDigest");
            context = context == null ? Optional.empty() : context;
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(stopReason, "stopReason");
            returnFields = List.copyOf(Objects.requireNonNull(returnFields, "returnFields"));
            matches = List.copyOf(Objects.requireNonNull(matches, "matches"));
            gaps = List.copyOf(Objects.requireNonNull(gaps, "gaps"));
        }

        public ContentDigest identity() {
            return ContentDigest.sha256Utf8(CanonicalJson.write(toView()));
        }

        public Map<String, Object> toView() {
            var m = new TreeMap<String, Object>();
            m.put("schema", schema);
            m.put("graphDigest", graphDigest.value());
            context.ifPresent(c -> m.put("context", c));
            m.put("status", status.name());
            m.put("stopReason", stopReason.name());
            m.put("truncated", truncated);
            m.put("limit", limit);
            m.put("totalMatched", totalMatched);
            m.put("visitedStates", visitedStates);
            m.put("returnFields", returnFields);
            m.put("pointerRefFormat", POINTER_REF_FORMAT);
            m.put("lazyReadTool", LAZY_READ_TOOL);
            m.put("matches", matches.stream().map(match -> match.toView(returnFields)).toList());
            m.put("gaps", gaps.stream().map(g -> Map.<String, Object>of(
                    "reasonCode", g.reasonCode(),
                    "mechanismCategory", g.mechanismCategory(),
                    "limitations", g.limitations())).toList());
            return Collections.unmodifiableMap(m);
        }
    }

    /** Deterministic live schema summary exposed by {@link ArchitectureQueryService#schemaView} and {@code evo://schema}. */
    public record SchemaView(
            String schema,
            String graphSchema,
            ContentDigest graphDigest,
            String snapshot,
            int totalNodes,
            int totalEdges,
            int totalEvidence,
            List<String> contexts,
            Map<String, Long> nodeKinds,
            Map<String, Long> edgeKinds,
            Map<String, Long> trustTiers,
            Map<String, Long> evidenceFamilies,
            List<String> queryableFields,
            List<String> returnFields,
            List<String> filterOperators,
            Map<String, Object> limits,
            String pointerRefFormat,
            String lazyReadTool
    ) {
        public SchemaView {
            schema = schema == null || schema.isBlank() ? SCHEMA_VIEW_VERSION : schema;
            Objects.requireNonNull(graphSchema, "graphSchema");
            Objects.requireNonNull(graphDigest, "graphDigest");
            Objects.requireNonNull(snapshot, "snapshot");
            contexts = List.copyOf(new TreeSet<>(Objects.requireNonNull(contexts, "contexts")));
            nodeKinds = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(nodeKinds, "nodeKinds")));
            edgeKinds = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(edgeKinds, "edgeKinds")));
            trustTiers = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(trustTiers, "trustTiers")));
            evidenceFamilies = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(evidenceFamilies, "evidenceFamilies")));
            queryableFields = List.copyOf(Objects.requireNonNull(queryableFields, "queryableFields"));
            returnFields = List.copyOf(Objects.requireNonNull(returnFields, "returnFields"));
            filterOperators = List.copyOf(Objects.requireNonNull(filterOperators, "filterOperators"));
            limits = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(limits, "limits")));
            pointerRefFormat = pointerRefFormat == null ? POINTER_REF_FORMAT : pointerRefFormat;
            lazyReadTool = lazyReadTool == null ? LAZY_READ_TOOL : lazyReadTool;
        }

        public ContentDigest identity() {
            return ContentDigest.sha256Utf8(CanonicalJson.write(toView()));
        }

        public Map<String, Object> toView() {
            var m = new TreeMap<String, Object>();
            m.put("schema", schema);
            m.put("graphSchema", graphSchema);
            m.put("graphDigest", graphDigest.value());
            m.put("snapshot", snapshot);
            m.put("totalNodes", totalNodes);
            m.put("totalEdges", totalEdges);
            m.put("totalEvidence", totalEvidence);
            m.put("contexts", contexts);
            m.put("nodeKinds", nodeKinds);
            m.put("edgeKinds", edgeKinds);
            m.put("trustTiers", trustTiers);
            m.put("evidenceFamilies", evidenceFamilies);
            m.put("queryableFields", queryableFields);
            m.put("returnFields", returnFields);
            m.put("filterOperators", filterOperators);
            m.put("limits", limits);
            m.put("pointerRefFormat", pointerRefFormat);
            m.put("lazyReadTool", lazyReadTool);
            return Collections.unmodifiableMap(m);
        }
    }

    public GraphComposeQuery {
        context = context == null ? Optional.empty() : context.map(String::strip).filter(s -> !s.isEmpty());
        if (context.isPresent()) {
            String ctx = context.orElseThrow();
            if (ctx.length() > 256 || hasControlChars(ctx)) {
                throw new ValidationException(ErrorCode.INVALID_CONTEXT, "context",
                        "Context must be <= 256 non-control characters");
            }
        }
        if (matchNodes == null || matchNodes.isEmpty()) {
            throw new ValidationException(ErrorCode.EMPTY_MATCH_NODES, "matchNodes",
                    "At least one matchNodes pattern is required");
        }
        if (matchNodes.size() > MAX_MATCH_NODES) {
            throw new ValidationException(ErrorCode.TOO_MANY_MATCH_NODES, "matchNodes",
                    "At most " + MAX_MATCH_NODES + " matchNodes are allowed");
        }
        var declaredAliases = new LinkedHashSet<String>();
        for (NodePattern np : matchNodes) {
            if (np == null) {
                throw new ValidationException(ErrorCode.INVALID_QUERY_OBJECT, "matchNodes",
                        "matchNodes entry must not be null");
            }
            if (!declaredAliases.add(np.alias())) {
                throw new ValidationException(ErrorCode.DUPLICATE_ALIAS, "matchNodes." + np.alias(),
                        "Duplicate node alias: " + np.alias());
            }
        }
        matchNodes = List.copyOf(matchNodes);

        List<EdgePattern> rawEdges = matchEdges == null ? List.of() : matchEdges;
        if (rawEdges.size() > MAX_MATCH_EDGES) {
            throw new ValidationException(ErrorCode.TOO_MANY_MATCH_EDGES, "matchEdges",
                    "At most " + MAX_MATCH_EDGES + " matchEdges are allowed");
        }
        for (EdgePattern ep : rawEdges) {
            if (ep == null) {
                throw new ValidationException(ErrorCode.INVALID_QUERY_OBJECT, "matchEdges",
                        "matchEdges entry must not be null");
            }
            if (!declaredAliases.contains(ep.from())) {
                throw new ValidationException(ErrorCode.UNKNOWN_ALIAS_REFERENCE, "matchEdges.from",
                        "Edge 'from' references undeclared alias: " + ep.from());
            }
            if (!declaredAliases.contains(ep.to())) {
                throw new ValidationException(ErrorCode.UNKNOWN_ALIAS_REFERENCE, "matchEdges.to",
                        "Edge 'to' references undeclared alias: " + ep.to());
            }
        }
        matchEdges = List.copyOf(rawEdges);

        List<FilterClause> rawFilters = filters == null ? List.of() : filters;
        if (rawFilters.size() > MAX_FILTERS) {
            throw new ValidationException(ErrorCode.TOO_MANY_FILTERS, "filters",
                    "At most " + MAX_FILTERS + " filters are allowed");
        }
        for (FilterClause fc : rawFilters) {
            if (fc == null) {
                throw new ValidationException(ErrorCode.INVALID_QUERY_OBJECT, "filters",
                        "filters entry must not be null");
            }
            if (!declaredAliases.contains(fc.alias())) {
                throw new ValidationException(ErrorCode.UNKNOWN_ALIAS_REFERENCE, "filters.alias",
                        "Filter references undeclared alias: " + fc.alias());
            }
        }
        filters = List.copyOf(rawFilters);

        List<String> rawReturn = returnFields == null || returnFields.isEmpty()
                ? ALLOWED_RETURN_FIELDS
                : returnFields;
        var dedupReturn = new LinkedHashSet<String>();
        for (String rf : rawReturn) {
            if (rf == null || !ALLOWED_RETURN_FIELD_SET.contains(rf.strip())) {
                throw new ValidationException(ErrorCode.INVALID_RETURN_FIELD, "returnFields",
                        "Unsupported returnField '" + rf + "'; allowed: " + ALLOWED_RETURN_FIELDS);
            }
            dedupReturn.add(rf.strip());
        }
        returnFields = List.copyOf(dedupReturn);

        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new ValidationException(ErrorCode.INVALID_LIMIT, "limit",
                    "limit must be in " + MIN_LIMIT + ".." + MAX_LIMIT + " (got " + limit + ")");
        }
    }

    /** Convenience factory with default returnFields and default limit (25). */
    public static GraphComposeQuery of(List<NodePattern> matchNodes, List<EdgePattern> matchEdges,
                                       List<FilterClause> filters) {
        return new GraphComposeQuery(Optional.empty(), matchNodes, matchEdges, filters,
                ALLOWED_RETURN_FIELDS, DEFAULT_LIMIT);
    }

    /** Canonical JSON-compatible map representation (omits transport-only {@code graph} key). */
    public Map<String, Object> toMap() {
        var m = new TreeMap<String, Object>();
        context.ifPresent(c -> m.put("context", c));
        m.put("matchNodes", matchNodes.stream().map(NodePattern::toMap).toList());
        m.put("matchEdges", matchEdges.stream().map(EdgePattern::toMap).toList());
        m.put("filters", filters.stream().map(FilterClause::toMap).toList());
        m.put("returnFields", returnFields);
        m.put("limit", limit);
        return Collections.unmodifiableMap(m);
    }

    /** Parses and strictly validates a raw JSON string into a typed {@link GraphComposeQuery}. */
    public static GraphComposeQuery fromJson(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            throw new ValidationException(ErrorCode.INVALID_QUERY_OBJECT, "", "Query JSON must not be blank");
        }
        try {
            Object parsed = GraphJson.read(rawJson, QUERY_JSON_LIMITS);
            if (!(parsed instanceof Map<?, ?> map)) {
                throw new ValidationException(ErrorCode.INVALID_QUERY_OBJECT, "", "Query root must be a JSON object");
            }
            return fromMap(GraphJson.object(map));
        } catch (ValidationException ve) {
            throw ve;
        } catch (RuntimeException malformed) {
            throw new ValidationException(ErrorCode.INVALID_QUERY_OBJECT, "",
                    "Malformed query JSON: " + malformed.getMessage());
        }
    }

    /** Parses and strictly validates an untrusted JSON map into a typed {@link GraphComposeQuery}. */
    public static GraphComposeQuery fromMap(Map<String, Object> raw) {
        if (raw == null) {
            throw new ValidationException(ErrorCode.INVALID_QUERY_OBJECT, "", "Query object is required");
        }
        rejectUnknownKeys(raw, ALLOWED_TOP_KEYS, "");

        Optional<String> context = Optional.empty();
        if (raw.containsKey("context") && raw.get("context") != null) {
            if (!(raw.get("context") instanceof String s)) {
                throw new ValidationException(ErrorCode.INVALID_CONTEXT, "context", "context must be a string");
            }
            if (!s.isBlank()) {
                context = Optional.of(s.strip());
            }
        }

        Object rawNodesObj = raw.get("matchNodes");
        if (!(rawNodesObj instanceof List<?> rawNodesList)) {
            throw new ValidationException(ErrorCode.EMPTY_MATCH_NODES, "matchNodes",
                    "matchNodes must be a non-empty array");
        }
        if (rawNodesList.isEmpty()) {
            throw new ValidationException(ErrorCode.EMPTY_MATCH_NODES, "matchNodes",
                    "matchNodes must contain at least one node pattern");
        }
        if (rawNodesList.size() > MAX_MATCH_NODES) {
            throw new ValidationException(ErrorCode.TOO_MANY_MATCH_NODES, "matchNodes",
                    "At most " + MAX_MATCH_NODES + " matchNodes are allowed");
        }
        var matchNodes = new ArrayList<NodePattern>(rawNodesList.size());
        for (int i = 0; i < rawNodesList.size(); i++) {
            Object item = rawNodesList.get(i);
            if (!(item instanceof Map<?, ?> itemMap)) {
                throw new ValidationException(ErrorCode.INVALID_QUERY_OBJECT, "matchNodes[" + i + "]",
                        "Each matchNodes item must be an object");
            }
            var nodeObj = GraphJson.object(itemMap);
            rejectUnknownKeys(nodeObj, ALLOWED_NODE_KEYS, "matchNodes[" + i + "]");
            String alias = requireString(nodeObj, "alias", ErrorCode.INVALID_ALIAS, "matchNodes[" + i + "].alias");
            Optional<String> kind = optionalString(nodeObj, "kind", ErrorCode.INVALID_NODE_KIND,
                    "matchNodes[" + i + "].kind");
            Optional<String> namePattern = optionalString(nodeObj, "namePattern", ErrorCode.INVALID_FILTER_VALUE,
                    "matchNodes[" + i + "].namePattern");
            Optional<TrustTier> trustTier = parseOptionalTrustTier(nodeObj.get("trustTier"),
                    "matchNodes[" + i + "].trustTier");
            matchNodes.add(new NodePattern(alias, kind, namePattern, trustTier));
        }

        var matchEdges = new ArrayList<EdgePattern>();
        if (raw.containsKey("matchEdges") && raw.get("matchEdges") != null) {
            if (!(raw.get("matchEdges") instanceof List<?> rawEdgesList)) {
                throw new ValidationException(ErrorCode.INVALID_QUERY_OBJECT, "matchEdges",
                        "matchEdges must be an array");
            }
            if (rawEdgesList.size() > MAX_MATCH_EDGES) {
                throw new ValidationException(ErrorCode.TOO_MANY_MATCH_EDGES, "matchEdges",
                        "At most " + MAX_MATCH_EDGES + " matchEdges are allowed");
            }
            for (int i = 0; i < rawEdgesList.size(); i++) {
                Object item = rawEdgesList.get(i);
                if (!(item instanceof Map<?, ?> itemMap)) {
                    throw new ValidationException(ErrorCode.INVALID_QUERY_OBJECT, "matchEdges[" + i + "]",
                            "Each matchEdges item must be an object");
                }
                var edgeObj = GraphJson.object(itemMap);
                rejectUnknownKeys(edgeObj, ALLOWED_EDGE_KEYS, "matchEdges[" + i + "]");
                String from = requireString(edgeObj, "from", ErrorCode.INVALID_ALIAS, "matchEdges[" + i + "].from");
                String to = requireString(edgeObj, "to", ErrorCode.INVALID_ALIAS, "matchEdges[" + i + "].to");
                var edgeKinds = new ArrayList<String>();
                if (edgeObj.containsKey("edgeKinds") && edgeObj.get("edgeKinds") != null) {
                    if (!(edgeObj.get("edgeKinds") instanceof List<?> ekList)) {
                        throw new ValidationException(ErrorCode.INVALID_EDGE_KIND, "matchEdges[" + i + "].edgeKinds",
                                "edgeKinds must be an array of strings");
                    }
                    for (Object ekObj : ekList) {
                        if (!(ekObj instanceof String eks)) {
                            throw new ValidationException(ErrorCode.INVALID_EDGE_KIND,
                                    "matchEdges[" + i + "].edgeKinds", "Every edgeKind must be a string");
                        }
                        edgeKinds.add(eks);
                    }
                }
                int minHops = parseStrictInt(edgeObj.get("minHops"), MIN_HOPS,
                        ErrorCode.INVALID_HOP_BOUNDS, "matchEdges[" + i + "].minHops");
                int maxHops = parseStrictInt(edgeObj.get("maxHops"), Math.max(minHops, MIN_HOPS),
                        ErrorCode.INVALID_HOP_BOUNDS, "matchEdges[" + i + "].maxHops");
                matchEdges.add(new EdgePattern(from, to, edgeKinds, minHops, maxHops));
            }
        }

        var filters = new ArrayList<FilterClause>();
        if (raw.containsKey("filters") && raw.get("filters") != null) {
            if (!(raw.get("filters") instanceof List<?> rawFiltersList)) {
                throw new ValidationException(ErrorCode.INVALID_QUERY_OBJECT, "filters",
                        "filters must be an array");
            }
            if (rawFiltersList.size() > MAX_FILTERS) {
                throw new ValidationException(ErrorCode.TOO_MANY_FILTERS, "filters",
                        "At most " + MAX_FILTERS + " filters are allowed");
            }
            for (int i = 0; i < rawFiltersList.size(); i++) {
                Object item = rawFiltersList.get(i);
                if (!(item instanceof Map<?, ?> itemMap)) {
                    throw new ValidationException(ErrorCode.INVALID_QUERY_OBJECT, "filters[" + i + "]",
                            "Each filters item must be an object");
                }
                var fObj = GraphJson.object(itemMap);
                rejectUnknownKeys(fObj, ALLOWED_FILTER_KEYS, "filters[" + i + "]");
                String alias = requireString(fObj, "alias", ErrorCode.INVALID_ALIAS, "filters[" + i + "].alias");
                String field = requireString(fObj, "field", ErrorCode.UNKNOWN_FILTER_FIELD, "filters[" + i + "].field");
                String rawOp = requireString(fObj, "op", ErrorCode.UNKNOWN_FILTER_OPERATOR, "filters[" + i + "].op");
                FilterOp op = parseFilterOp(rawOp, "filters[" + i + "].op");
                Optional<String> value = Optional.empty();
                if (fObj.containsKey("value") && fObj.get("value") != null) {
                    Object rawVal = fObj.get("value");
                    if (rawVal instanceof String s) {
                        value = Optional.of(s);
                    } else if (rawVal instanceof Boolean b && op == FilterOp.EXISTS) {
                        value = Optional.of(Boolean.toString(b));
                    } else {
                        throw new ValidationException(ErrorCode.INVALID_FILTER_VALUE, "filters[" + i + "].value",
                                "Filter value must be a string");
                    }
                }
                filters.add(new FilterClause(alias, field, op, value));
            }
        }

        var returnFields = new ArrayList<String>();
        if (raw.containsKey("returnFields") && raw.get("returnFields") != null) {
            if (!(raw.get("returnFields") instanceof List<?> rfList)) {
                throw new ValidationException(ErrorCode.INVALID_RETURN_FIELD, "returnFields",
                        "returnFields must be an array of strings");
            }
            if (rfList.isEmpty()) {
                throw new ValidationException(ErrorCode.INVALID_RETURN_FIELD, "returnFields",
                        "returnFields must not be empty when explicitly specified");
            }
            for (Object rfObj : rfList) {
                if (!(rfObj instanceof String s)) {
                    throw new ValidationException(ErrorCode.INVALID_RETURN_FIELD, "returnFields",
                            "Every returnField must be a string");
                }
                returnFields.add(s);
            }
        } else {
            returnFields.addAll(ALLOWED_RETURN_FIELDS);
        }

        int limit = parseStrictInt(raw.get("limit"), DEFAULT_LIMIT, ErrorCode.INVALID_LIMIT, "limit");
        return new GraphComposeQuery(context, matchNodes, matchEdges, filters, returnFields, limit);
    }

    /**
     * Validates a bounded regular expression against ReDoS and oversized inputs and compiles it.
     * Always throws {@link ValidationException} (never raw {@link PatternSyntaxException}).
     */
    public static Pattern validateBoundedRegex(String regex, String field) {
        if (regex == null || regex.isEmpty()) {
            throw new ValidationException(ErrorCode.INVALID_FILTER_VALUE, field,
                    "REGEX_BOUNDED pattern must not be empty");
        }
        if (regex.length() > MAX_PATTERN_LENGTH) {
            throw new ValidationException(ErrorCode.OVERSIZED_PATTERN, field,
                    "REGEX_BOUNDED pattern exceeds max length " + MAX_PATTERN_LENGTH);
        }
        if (hasControlChars(regex)) {
            throw new ValidationException(ErrorCode.UNSAFE_REGEX_PATTERN, field,
                    "REGEX_BOUNDED pattern must not contain ISO control characters");
        }
        // Reject lookaround, backreferences, embedded flags, and inline mode switches
        if (regex.contains("(?") || containsBackreference(regex)) {
            throw new ValidationException(ErrorCode.UNSAFE_REGEX_PATTERN, field,
                    "Lookarounds, backreferences, and inline flags are disallowed in REGEX_BOUNDED");
        }
        // Static nesting & quantifier explosion guard (blocks (a+)+, (a*)*, (a|aa)+, {1,999}, etc.)
        int parenDepth = 0;
        boolean[] groupHasQuantifier = new boolean[8];
        boolean[] groupHasAlternation = new boolean[8];
        int totalQuantifiers = 0;
        int totalAlternations = 0;
        boolean inCharClass = false;
        boolean prevWasQuantifier = false;

        for (int i = 0; i < regex.length(); i++) {
            char c = regex.charAt(i);
            if (c == '\\') {
                if (i + 1 >= regex.length()) {
                    throw new ValidationException(ErrorCode.UNSAFE_REGEX_PATTERN, field,
                            "Dangling escape in REGEX_BOUNDED");
                }
                i++;
                prevWasQuantifier = false;
                continue;
            }
            if (inCharClass) {
                if (c == ']') {
                    inCharClass = false;
                }
                prevWasQuantifier = false;
                continue;
            }
            if (c == '[') {
                inCharClass = true;
                prevWasQuantifier = false;
                continue;
            }
            if (c == '(') {
                parenDepth++;
                if (parenDepth > 3) {
                    throw new ValidationException(ErrorCode.UNSAFE_REGEX_PATTERN, field,
                            "Regex group nesting depth exceeds maximum of 3");
                }
                groupHasQuantifier[parenDepth] = false;
                groupHasAlternation[parenDepth] = false;
                prevWasQuantifier = false;
                continue;
            }
            if (c == ')') {
                if (parenDepth == 0) {
                    throw new ValidationException(ErrorCode.UNSAFE_REGEX_PATTERN, field,
                            "Unbalanced closing parenthesis in REGEX_BOUNDED");
                }
                boolean innerRisk = groupHasQuantifier[parenDepth] || groupHasAlternation[parenDepth];
                parenDepth--;
                if (innerRisk && parenDepth > 0) {
                    groupHasQuantifier[parenDepth] = true;
                }
                // Check if this closing group is immediately followed by a quantifier -> catastrophic backtracking!
                if (innerRisk && i + 1 < regex.length()) {
                    char next = regex.charAt(i + 1);
                    if (next == '*' || next == '+' || next == '{') {
                        throw new ValidationException(ErrorCode.UNSAFE_REGEX_PATTERN, field,
                                "Nested quantifier or repeated alternation group rejected (ReDoS guard)");
                    }
                }
                prevWasQuantifier = false;
                continue;
            }
            if (c == '|') {
                totalAlternations++;
                if (totalAlternations > 8) {
                    throw new ValidationException(ErrorCode.UNSAFE_REGEX_PATTERN, field,
                            "Too many alternations (max 8) in REGEX_BOUNDED");
                }
                if (parenDepth > 0) {
                    groupHasAlternation[parenDepth] = true;
                }
                prevWasQuantifier = false;
                continue;
            }
            if (c == '*' || c == '+' || c == '?') {
                if (prevWasQuantifier && c != '?') {
                    throw new ValidationException(ErrorCode.UNSAFE_REGEX_PATTERN, field,
                            "Consecutive quantifiers rejected in REGEX_BOUNDED");
                }
                totalQuantifiers++;
                if (totalQuantifiers > 10) {
                    throw new ValidationException(ErrorCode.UNSAFE_REGEX_PATTERN, field,
                            "Too many quantifiers (max 10) in REGEX_BOUNDED");
                }
                if (parenDepth > 0 && c != '?') {
                    groupHasQuantifier[parenDepth] = true;
                }
                prevWasQuantifier = true;
                continue;
            }
            if (c == '{') {
                int close = regex.indexOf('}', i + 1);
                if (close < 0) {
                    throw new ValidationException(ErrorCode.UNSAFE_REGEX_PATTERN, field,
                            "Unclosed repetition brace in REGEX_BOUNDED");
                }
                String inner = regex.substring(i + 1, close);
                validateRepetitionBounds(inner, field);
                totalQuantifiers++;
                if (totalQuantifiers > 10) {
                    throw new ValidationException(ErrorCode.UNSAFE_REGEX_PATTERN, field,
                            "Too many quantifiers (max 10) in REGEX_BOUNDED");
                }
                if (parenDepth > 0) {
                    groupHasQuantifier[parenDepth] = true;
                }
                i = close;
                prevWasQuantifier = true;
                continue;
            }
            prevWasQuantifier = false;
        }
        if (inCharClass || parenDepth != 0) {
            throw new ValidationException(ErrorCode.UNSAFE_REGEX_PATTERN, field,
                    "Unclosed group or character class in REGEX_BOUNDED");
        }
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException syntax) {
            throw new ValidationException(ErrorCode.UNSAFE_REGEX_PATTERN, field,
                    "Invalid regex syntax: " + syntax.getDescription());
        }
    }

    /** Executes a validated bounded regex against a capped candidate string with a hard runtime step cap. */
    public static boolean boundedRegexMatch(Pattern pattern, String candidate) {
        if (pattern == null || candidate == null) {
            return false;
        }
        String clipped = candidate.length() > MAX_REGEX_INPUT_CHARS
                ? candidate.substring(0, MAX_REGEX_INPUT_CHARS)
                : candidate;
        try {
            Matcher matcher = pattern.matcher(new BoundedCharSequence(clipped, MAX_REGEX_STEPS));
            return matcher.find();
        } catch (RegexStepLimitExceeded exceeded) {
            return false;
        }
    }

    /** Evaluates a {@link NodePattern#namePattern()} (glob when {@code *} or {@code ?} present, else case-insensitive substring). */
    public static boolean matchesNamePattern(String pattern, String fqn, String simpleName, String id) {
        if (pattern == null || pattern.isEmpty()) {
            return true;
        }
        boolean isGlob = pattern.indexOf('*') >= 0 || pattern.indexOf('?') >= 0;
        if (!isGlob) {
            String lower = pattern.toLowerCase(Locale.ROOT);
            return (fqn != null && fqn.toLowerCase(Locale.ROOT).contains(lower))
                    || (simpleName != null && simpleName.toLowerCase(Locale.ROOT).contains(lower))
                    || (id != null && id.toLowerCase(Locale.ROOT).contains(lower));
        }
        Pattern compiled = compileGlobPattern(pattern);
        return (fqn != null && boundedGlobFullMatch(compiled, fqn))
                || (simpleName != null && boundedGlobFullMatch(compiled, simpleName))
                || (id != null && boundedGlobFullMatch(compiled, id));
    }

    /** Formats a compact pointer-indexed reference {@code path:startLine-endLine@sha256Prefix}. */
    public static String formatPointerRef(Optional<String> validatedSourcePath, String kind, String nodeId,
                                          int startLine, int endLine, String sha256Prefix) {
        String sha = normalizeShaPrefix(sha256Prefix);
        int sLine = Math.max(0, startLine);
        int eLine = Math.max(sLine, endLine);
        if (validatedSourcePath != null && validatedSourcePath.isPresent() && !validatedSourcePath.orElseThrow().isBlank()) {
            return validatedSourcePath.orElseThrow() + ":" + sLine + "-" + eLine + "@" + sha;
        }
        String safeKind = (kind == null || kind.isBlank() ? "node" : kind.toLowerCase(Locale.ROOT))
                .replaceAll("[^a-z0-9._-]", "_");
        String shortId = shortDigestSegment(nodeId);
        return "unmapped/" + safeKind + "/" + shortId + ":" + sLine + "-" + eLine + "@" + sha;
    }

    /** Extracts a 12-hex-char SHA-256 prefix from a {@code sha256:<hex>} or raw hex digest. */
    public static String normalizeShaPrefix(String rawDigest) {
        if (rawDigest == null || rawDigest.isBlank()) {
            return "000000000000";
        }
        String s = rawDigest.strip();
        int idx = s.lastIndexOf("sha256:");
        if (idx >= 0) {
            s = s.substring(idx + "sha256:".length());
        }
        var hex = new StringBuilder(POINTER_SHA_PREFIX_LEN);
        for (int i = 0; i < s.length() && hex.length() < POINTER_SHA_PREFIX_LEN; i++) {
            char c = Character.toLowerCase(s.charAt(i));
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) {
                hex.append(c);
            }
        }
        while (hex.length() < POINTER_SHA_PREFIX_LEN) {
            hex.append('0');
        }
        return hex.toString();
    }

    /** Default schema limits map advertised in {@code evo://schema}. */
    public static Map<String, Object> advertisedLimits() {
        var m = new TreeMap<String, Object>();
        m.put("defaultLimit", DEFAULT_LIMIT);
        m.put("maxEdgeKindsPerPattern", MAX_EDGE_KINDS_PER_PATTERN);
        m.put("maxFilters", MAX_FILTERS);
        m.put("maxHops", MAX_HOPS);
        m.put("maxLimit", MAX_LIMIT);
        m.put("maxMatchEdges", MAX_MATCH_EDGES);
        m.put("maxMatchNodes", MAX_MATCH_NODES);
        m.put("maxPatternLength", MAX_PATTERN_LENGTH);
        m.put("maxVisitedStates", DEFAULT_MAX_VISITED_STATES);
        m.put("minHops", MIN_HOPS);
        m.put("timeoutMillis", DEFAULT_TIMEOUT_MILLIS);
        return Collections.unmodifiableMap(m);
    }

    // -------------------------------------------------------------------------
    // Internal Validation & Bounded Matching Helpers
    // -------------------------------------------------------------------------

    private static String shortDigestSegment(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return "000000000000";
        }
        int idx = nodeId.lastIndexOf("sha256:");
        if (idx >= 0) {
            return normalizeShaPrefix(nodeId.substring(idx));
        }
        return normalizeShaPrefix(ContentDigest.sha256Utf8(nodeId).value());
    }

    private static Optional<String> normalizeOptionalToken(Optional<String> raw, ErrorCode code, String field) {
        if (raw == null || raw.isEmpty()) {
            return Optional.empty();
        }
        String v = raw.orElseThrow().strip();
        if (v.isEmpty()) {
            return Optional.empty();
        }
        if (!KIND_TOKEN_PATTERN.matcher(v).matches()) {
            throw new ValidationException(code, field, "Invalid token: " + v);
        }
        return Optional.of(v);
    }

    private static Optional<String> normalizeOptionalNamePattern(Optional<String> raw, String field) {
        if (raw == null || raw.isEmpty()) {
            return Optional.empty();
        }
        String v = raw.orElseThrow().strip();
        if (v.isEmpty()) {
            return Optional.empty();
        }
        if (v.length() > MAX_PATTERN_LENGTH) {
            throw new ValidationException(ErrorCode.OVERSIZED_PATTERN, field,
                    "namePattern exceeds max length " + MAX_PATTERN_LENGTH);
        }
        if (hasControlChars(v)) {
            throw new ValidationException(ErrorCode.INVALID_FILTER_VALUE, field,
                    "namePattern must not contain ISO control characters");
        }
        int stars = 0;
        for (int i = 0; i < v.length(); i++) {
            if (v.charAt(i) == '*') {
                stars++;
            }
        }
        if (stars > MAX_GLOB_WILDCARDS) {
            throw new ValidationException(ErrorCode.UNSAFE_REGEX_PATTERN, field,
                    "namePattern exceeds maximum of " + MAX_GLOB_WILDCARDS + " '*' wildcards");
        }
        return Optional.of(v);
    }

    private static Pattern compileGlobPattern(String glob) {
        var sb = new StringBuilder(glob.length() * 2 + 4);
        sb.append('^');
        boolean prevStar = false;
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (!prevStar) {
                    sb.append(".*");
                    prevStar = true;
                }
            } else if (c == '?') {
                sb.append('.');
                prevStar = false;
            } else {
                sb.append(Pattern.quote(String.valueOf(c)));
                prevStar = false;
            }
        }
        sb.append('$');
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
    }

    private static boolean boundedGlobFullMatch(Pattern compiled, String candidate) {
        String clipped = candidate.length() > MAX_REGEX_INPUT_CHARS
                ? candidate.substring(0, MAX_REGEX_INPUT_CHARS)
                : candidate;
        try {
            return compiled.matcher(new BoundedCharSequence(clipped, MAX_REGEX_STEPS)).matches();
        } catch (RegexStepLimitExceeded exceeded) {
            return false;
        }
    }

    private static boolean containsBackreference(String regex) {
        boolean inClass = false;
        for (int i = 0; i < regex.length(); i++) {
            char c = regex.charAt(i);
            if (c == '\\' && i + 1 < regex.length()) {
                char next = regex.charAt(i + 1);
                if (!inClass && next >= '1' && next <= '9') {
                    return true;
                }
                i++;
            } else if (c == '[') {
                inClass = true;
            } else if (c == ']') {
                inClass = false;
            }
        }
        return false;
    }

    private static void validateRepetitionBounds(String inner, String field) {
        if (inner.isEmpty() || inner.length() > 12) {
            throw new ValidationException(ErrorCode.UNSAFE_REGEX_PATTERN, field,
                    "Invalid quantifier repetition {" + inner + "}");
        }
        String[] parts = inner.split(",", -1);
        if (parts.length > 2) {
            throw new ValidationException(ErrorCode.UNSAFE_REGEX_PATTERN, field,
                    "Invalid quantifier repetition {" + inner + "}");
        }
        try {
            int min = Integer.parseInt(parts[0].strip());
            if (min < 0 || min > 32) {
                throw new ValidationException(ErrorCode.UNSAFE_REGEX_PATTERN, field,
                        "Quantifier min bound out of 0..32 range: " + min);
            }
            if (parts.length == 2 && !parts[1].isBlank()) {
                int max = Integer.parseInt(parts[1].strip());
                if (max < min || max > 32) {
                    throw new ValidationException(ErrorCode.UNSAFE_REGEX_PATTERN, field,
                            "Quantifier max bound out of min..32 range: " + max);
                }
            }
        } catch (NumberFormatException nfe) {
            throw new ValidationException(ErrorCode.UNSAFE_REGEX_PATTERN, field,
                    "Non-numeric quantifier repetition {" + inner + "}");
        }
    }

    private static void rejectUnknownKeys(Map<String, Object> obj, Set<String> allowed, String pathPrefix) {
        for (String key : obj.keySet()) {
            if (!allowed.contains(key)) {
                String loc = pathPrefix.isEmpty() ? key : pathPrefix + "." + key;
                throw new ValidationException(ErrorCode.UNKNOWN_QUERY_FIELD, loc,
                        "Unknown field '" + key + "'; allowed: " + new TreeSet<>(allowed));
            }
        }
    }

    private static String requireString(Map<String, Object> obj, String key, ErrorCode code, String field) {
        Object val = obj.get(key);
        if (!(val instanceof String s) || s.isBlank()) {
            throw new ValidationException(code, field, "Required non-blank string field: " + key);
        }
        return s.strip();
    }

    private static Optional<String> optionalString(Map<String, Object> obj, String key, ErrorCode code, String field) {
        if (!obj.containsKey(key) || obj.get(key) == null) {
            return Optional.empty();
        }
        Object val = obj.get(key);
        if (!(val instanceof String s)) {
            throw new ValidationException(code, field, "Field '" + key + "' must be a string");
        }
        return s.isBlank() ? Optional.empty() : Optional.of(s.strip());
    }

    private static Optional<TrustTier> parseOptionalTrustTier(Object raw, String field) {
        if (raw == null) {
            return Optional.empty();
        }
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ValidationException(ErrorCode.INVALID_TRUST_TIER, field,
                    "trustTier must be one of " + Arrays.toString(TrustTier.values()));
        }
        try {
            return Optional.of(TrustTier.valueOf(s.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException invalid) {
            throw new ValidationException(ErrorCode.INVALID_TRUST_TIER, field,
                    "Unknown trustTier '" + s + "'; allowed: " + Arrays.toString(TrustTier.values()));
        }
    }

    private static FilterOp parseFilterOp(String rawOp, String field) {
        try {
            return FilterOp.valueOf(rawOp.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new ValidationException(ErrorCode.UNKNOWN_FILTER_OPERATOR, field,
                    "Unknown operator '" + rawOp + "'; allowed: " + Arrays.toString(FilterOp.values()));
        }
    }

    private static int parseStrictInt(Object raw, int defaultValue, ErrorCode code, String field) {
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Integer i) {
            return i;
        }
        if (raw instanceof Long l && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
            return l.intValue();
        }
        if (raw instanceof BigDecimal bd) {
            try {
                return bd.intValueExact();
            } catch (ArithmeticException nonInt) {
                throw new ValidationException(code, field, "Expected exact integer for " + field);
            }
        }
        if (raw instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isFinite(d) && d == Math.rint(d) && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE) {
                return (int) d;
            }
        }
        throw new ValidationException(code, field, "Expected integer for " + field);
    }

    private static boolean hasControlChars(String text) {
        return text.chars().anyMatch(Character::isISOControl);
    }

    /** Helper validating a candidate source path without ever exposing host paths. */
    public static Optional<String> validateRelativeSourcePath(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ContractChecks.repositoryRelativePath(candidate.strip(), "compose source path"));
        } catch (IllegalArgumentException unsafe) {
            return Optional.empty();
        }
    }

    private static final class RegexStepLimitExceeded extends RuntimeException {
        RegexStepLimitExceeded() {
            super("Regex step budget exceeded", null, false, false);
        }
    }

    private static final class BoundedCharSequence implements CharSequence {
        private final String delegate;
        private final int maxSteps;
        private int steps;

        BoundedCharSequence(String delegate, int maxSteps) {
            this.delegate = delegate;
            this.maxSteps = maxSteps;
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public char charAt(int index) {
            if (++steps > maxSteps) {
                throw new RegexStepLimitExceeded();
            }
            return delegate.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new BoundedCharSequence(delegate.substring(start, end), Math.max(1, maxSteps - steps));
        }

        @Override
        public String toString() {
            return delegate;
        }
    }
}
