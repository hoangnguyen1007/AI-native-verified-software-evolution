package com.evolution.analysis.query;

import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.graph.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;
import java.util.regex.Pattern;
import static com.evolution.analysis.query.ArchitectureQuery.*;

/**
 * Pure Java architecture queries over one immutable graph and one exact context
 * per request.
 */
public final class ArchitectureQueryService {
    public static final int DEFAULT_MEMO_CACHE_CAPACITY = 2048;

    private final KnowledgeGraph graph;
    private final ContentDigest graphIdentity;
    private final Supplier<StopReason> guard;
    private final Optional<Metrics> metrics;
    private final boolean memoizationEnabled;
    private final int memoCacheCapacity;
    private final java.util.concurrent.ConcurrentHashMap<ContentDigest, Map<String, Object>> parsedEvidenceCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, String> QUALIFIED_NAME_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    // WP2.2 MeCheck Fig. 6 inspired bounded deterministic per-session memoization & lazy metadata index
    private final Object memoLock = new Object();
    private final LinkedHashMap<MemoKey, Object> queryMemoCache;
    private final java.util.concurrent.atomic.AtomicLong memoHits = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong memoMisses = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong memoEvictions = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong graphScanCount = new java.util.concurrent.atomic.AtomicLong();
    private volatile SessionMetadataIndex sessionMetadataIndex;

    private record MemoKey(String queryOp, String canonicalArgsDigest) {}

    /** Diagnostic telemetry for the bounded per-session memoization cache (excluded from query identities). */
    public record CacheStats(long hits, long misses, long evictions, int entries, long graphScans, int indexedClasses) {}

    /** Immutable lazy-indexed annotation descriptor for a class or member. */
    public record AnnotationMetadata(String name, String rawSpelling, Optional<String> ownerId,
            Optional<String> sourceFile, int line) {
        public AnnotationMetadata {
            name = name == null ? "" : name;
            rawSpelling = rawSpelling == null ? "" : rawSpelling;
            ownerId = ownerId == null ? Optional.empty() : ownerId;
            sourceFile = sourceFile == null ? Optional.empty() : sourceFile;
        }
    }

    /** Immutable lazy-indexed field, method, or constructor descriptor. */
    public record MemberMetadata(String nodeId, String name, String kind, boolean staticMember,
            boolean abstractMember, int paramCount, List<String> paramTypes, Optional<String> returnType,
            Optional<String> fieldType, List<AnnotationMetadata> annotations) {
        public MemberMetadata {
            nodeId = Objects.requireNonNull(nodeId);
            name = name == null ? "" : name;
            kind = kind == null ? "" : kind;
            paramTypes = List.copyOf(paramTypes == null ? List.of() : paramTypes);
            returnType = returnType == null ? Optional.empty() : returnType;
            fieldType = fieldType == null ? Optional.empty() : fieldType;
            annotations = List.copyOf(annotations == null ? List.of() : annotations);
        }
    }

    /** Immutable lazy-indexed class metadata (populated on demand per class, MeCheck Fig. 6). */
    public record ClassMetadata(String nodeId, String fqcn, String kind, boolean abstractType,
            Set<String> superTypes, List<MemberMetadata> constructors, List<MemberMetadata> methods,
            List<MemberMetadata> fields, List<AnnotationMetadata> annotations) {
        public ClassMetadata {
            nodeId = Objects.requireNonNull(nodeId);
            fqcn = Objects.requireNonNull(fqcn);
            kind = kind == null ? "CLASS" : kind;
            superTypes = Collections.unmodifiableSet(new TreeSet<>(superTypes == null ? Set.of() : superTypes));
            constructors = List.copyOf(constructors == null ? List.of() : constructors);
            methods = List.copyOf(methods == null ? List.of() : methods);
            fields = List.copyOf(fields == null ? List.of() : fields);
            annotations = List.copyOf(annotations == null ? List.of() : annotations);
        }
    }

    /**
     * Operational guards are outside query identity; never treat interrupted output
     * as a complete cache entry.
     */
    public ArchitectureQueryService(KnowledgeGraph graph) {
        this(graph, operationalGuard(() -> false, 0, 8_000_000), Optional.empty());
    }

    public ArchitectureQueryService(KnowledgeGraph graph, Supplier<StopReason> guard, Optional<Metrics> metrics) {
        this(graph, graph.identity(), guard, metrics);
    }

    public ArchitectureQueryService(KnowledgeGraph graph, ContentDigest graphIdentity, Supplier<StopReason> guard,
            Optional<Metrics> metrics) {
        this(graph, graphIdentity, guard, metrics, true, DEFAULT_MEMO_CACHE_CAPACITY);
    }

    public ArchitectureQueryService(KnowledgeGraph graph, ContentDigest graphIdentity, Supplier<StopReason> guard,
            Optional<Metrics> metrics, boolean memoizationEnabled, int memoCacheCapacity) {
        this.graph = Objects.requireNonNull(graph);
        this.guard = Objects.requireNonNull(guard);
        this.metrics = Objects.requireNonNull(metrics);
        this.graphIdentity = Objects.requireNonNull(graphIdentity);
        this.memoizationEnabled = memoizationEnabled;
        if (memoCacheCapacity < 1 || memoCacheCapacity > 65_536) {
            throw new IllegalArgumentException("Memoization cache capacity must be in 1..65536");
        }
        this.memoCacheCapacity = memoCacheCapacity;
        this.queryMemoCache = new LinkedHashMap<>(Math.min(256, memoCacheCapacity), 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<MemoKey, Object> eldest) {
                if (size() > ArchitectureQueryService.this.memoCacheCapacity) {
                    memoEvictions.incrementAndGet();
                    return true;
                }
                return false;
            }
        };
    }

    /** Creates an uncached query service for parity testing and zero-cache verification. */
    public static ArchitectureQueryService withoutMemoization(KnowledgeGraph graph) {
        return new ArchitectureQueryService(graph, graph.identity(), operationalGuard(() -> false, 0, 8_000_000),
                Optional.empty(), false, DEFAULT_MEMO_CACHE_CAPACITY);
    }

    public static ArchitectureQueryService withoutMemoization(KnowledgeGraph graph, Supplier<StopReason> guard,
            Optional<Metrics> metrics) {
        return new ArchitectureQueryService(graph, graph.identity(), guard, metrics, false,
                DEFAULT_MEMO_CACHE_CAPACITY);
    }

    public boolean isMemoizationEnabled() {
        return memoizationEnabled;
    }

    public CacheStats cacheStats() {
        int entries;
        synchronized (memoLock) {
            entries = queryMemoCache.size();
        }
        SessionMetadataIndex idx = sessionMetadataIndex;
        int indexedClasses = idx == null ? 0 : idx.indexedClassCount();
        return new CacheStats(memoHits.get(), memoMisses.get(), memoEvictions.get(), entries,
                graphScanCount.get(), indexedClasses);
    }

    public void clearMemoizationCache() {
        synchronized (memoLock) {
            queryMemoCache.clear();
        }
        sessionMetadataIndex = null;
    }

    /**
     * The caller authenticates the complete selected partition and applicable
     * ancestor evidence.
     */
    public static ArchitectureQueryService replayPartition(KnowledgeGraph partition, ContentDigest graph,
            BooleanSupplier cancelled) {
        return new ArchitectureQueryService(partition, graph, operationalGuard(cancelled, 0, 0), Optional.empty());
    }

    private static final long MEMORY_RECLAIM_COOLDOWN_NANOS = 500_000_000L;

    public static Supplier<StopReason> operationalGuard(BooleanSupplier cancelled, long deadlineNanos,
            long minimumAvailableHeapBytes) {
        return operationalGuard(cancelled, deadlineNanos, minimumAvailableHeapBytes,
                () -> {
                    var runtime = Runtime.getRuntime();
                    return runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory();
                },
                () -> Runtime.getRuntime().maxMemory(),
                System::gc,
                System::nanoTime);
    }

    static Supplier<StopReason> operationalGuard(BooleanSupplier cancelled, long deadlineNanos,
            long minimumAvailableHeapBytes, LongSupplier availableHeapBytes, LongSupplier maxHeapBytes,
            Runnable reclaim, LongSupplier nanoClock) {
        if (minimumAvailableHeapBytes < 0)
            throw new IllegalArgumentException("Negative heap reserve");
        var lastReclaimNanos = new java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE);
        return () -> {
            if (cancelled.getAsBoolean())
                return StopReason.CANCELLED;
            if (deadlineNanos != 0 && nanoClock.getAsLong() - deadlineNanos >= 0)
                return StopReason.TIME;
            if (minimumAvailableHeapBytes > 0) {
                long available = availableHeapBytes.getAsLong();
                if (available < minimumAvailableHeapBytes) {
                    long maxHeap = maxHeapBytes.getAsLong();
                    if (minimumAvailableHeapBytes < maxHeap) {
                        long now = nanoClock.getAsLong();
                        long prev = lastReclaimNanos.get();
                        if ((prev == Long.MIN_VALUE || now - prev >= MEMORY_RECLAIM_COOLDOWN_NANOS)
                                && lastReclaimNanos.compareAndSet(prev, now)) {
                            reclaim.run();
                            if (deadlineNanos != 0 && nanoClock.getAsLong() - deadlineNanos >= 0)
                                return StopReason.TIME;
                            available = availableHeapBytes.getAsLong();
                        }
                    }
                    if (available < minimumAvailableHeapBytes)
                        return StopReason.MEMORY;
                }
            }
            return StopReason.NONE;
        };
    }

    public KnowledgeGraph graph() {
        return graph;
    }

    public ContentDigest graphIdentity() {
        return graphIdentity;
    }

    /**
     * Applicability of the Java and Spring policy/metric projections. Inventory
     * queries remain available in every context.
     */
    public boolean supportsArchitectureScope(KnowledgeGraph.Context context, Scope scope) {
        return switch (graph.evidence(context.evidence()).orElseThrow().family()) {
            case "analysis.manifest" -> scope != Scope.SPRING_COMPONENT;
            case "spring.context" -> scope == Scope.SPRING_COMPONENT;
            case "repository.snapshot", "repository.inventory-snapshot", "polyglot.context" -> false;
            default -> true; // Older/custom context evidence has no declared language boundary.
        };
    }

    /**
     * Additive M6 assessment/metric orchestration shares this query service's
     * cancellation and operational guard.
     */
    public StopReason assessmentStopReason() {
        return Objects.requireNonNull(guard.get());
    }

    static final class LogicalEdge {
        final String source, target;
        final SortedSet<String> supports = new TreeSet<>(), kinds = new TreeSet<>(), regions = new TreeSet<>();
        final SortedSet<ContentDigest> evidence = new TreeSet<>();
        QueryRegion region;
        EdgeView view;

        LogicalEdge(String source, String target, QueryRegion region) {
            this.source = source;
            this.target = target;
            this.region = region;
        }
    }

    /**
     * Only population columns are retained; paths, hashes and other parsed document
     * fields stay transient.
     */
    private record DocumentPopulation(String module, String sourceSet) {
    }

    final class Prepared {
        final Request request;
        final QueryBudget budget;
        final Map<String, KnowledgeGraph.Node> nodes = new TreeMap<>();
        final Map<String, EntityView> entities = new TreeMap<>(), descriptions = new HashMap<>();
        final List<LogicalEdge> edges = new ArrayList<>();
        final Map<String, List<LogicalEdge>> adjacency = new TreeMap<>();
        GraphRegionCatalog catalog;

        Prepared(Request request, boolean project) {
            this(request, project, false);
        }

        Prepared(Request request, boolean project, boolean contextOnly) {
            this.request = request;
            validate(request);
            budget = new QueryBudget(request.limits(), guard);
            if (contextOnly) {
                var selected = graph.contexts().stream().filter(c -> c.identity().equals(request.context())).findFirst()
                        .orElseThrow();
                for (var node : graph.nodes()) {
                    if (!budget.scan())
                        break;
                    if ((node.kind().equals("CAPABILITY_GAP") || node.kind().equals("PROVIDER_CONFLICT"))
                            && (node.context().equals(request.context())
                                    || node.context().equals(graph.snapshot().value())
                                    || selected.analysis().map(a -> a.value().equals(node.context())).orElse(false))) {
                        budget.qualify("UPSTREAM_CAPABILITY_GAP");
                        break;
                    }
                }
                return;
            }
            for (var node : graph.nodes(request.context())) {
                if (!budget.scan())
                    break;
                if (nodes.size() >= request.limits().maxVertices()) {
                    budget.halt(StopReason.INPUT);
                    break;
                }
                if (!budget.reserve(3))
                    break;
                nodes.put(node.identity(), node);
            }
            var documents = new HashMap<String, DocumentPopulation>();
            for (var node : nodes.values())
                if (node.kind().equals("SOURCE_DOCUMENT"))
                    for (var ref : node.evidence()) {
                        if (!budget.scan())
                            break;
                        var evidence = graph.evidence(ref).orElseThrow();
                        if (evidence.family().equals("source.document") && !documents.containsKey(node.identity())) {
                            // Reserve entry/record/scalar state before parsing or retaining this document's
                            // metadata.
                            if (!budget.reserve(4))
                                break;
                            var payload = read(evidence, budget);
                            documents.put(node.identity(), new DocumentPopulation(optionalText(payload, "module"),
                                    optionalText(payload, "classification")));
                        }
                    }
            var context = graph.contexts().stream().filter(c -> c.identity().equals(request.context())).findFirst()
                    .orElseThrow();
            var contextPayload = read(graph.evidence(context.evidence()).orElseThrow(), budget);
            var contextPartition = Optional.ofNullable(contextPayload.get("build")).filter(Map.class::isInstance)
                    .map(GraphJson::object)
                    .map(b -> b.get("moduleSourceSet")).filter(Map.class::isInstance).map(GraphJson::object)
                    .orElse(Map.of());
            for (var node : nodes.values()) {
                if (!budget.scan())
                    break;
                var entity = describe(node, documents, contextPartition, budget);
                descriptions.put(node.identity(), entity);
                if (request.population().nodeKinds().isEmpty()
                        || request.population().nodeKinds().contains(entity.kind())) {
                    boolean inducedOwner = project
                            && (request.scope() == Scope.PACKAGE || request.scope() == Scope.MODULE)
                            && entity.kind().equals(request.scope().name());
                    if (!inducedOwner && (!request.population().modules().isEmpty() && entity.module().isEmpty()
                            || !request.population().origins().isEmpty() && entity.origin().isEmpty()
                            || !request.population().sourceSets().isEmpty() && entity.sourceSet().isEmpty()))
                        budget.qualify("POPULATION_METADATA_UNAVAILABLE");
                    if (included(entity, request.population()))
                        entities.put(node.identity(), entity);
                }
                if (node.kind().equals("CAPABILITY_GAP") || node.kind().equals("PROVIDER_CONFLICT"))
                    budget.qualify("UPSTREAM_CAPABILITY_GAP");
            }
            // Analysis-wide/snapshot gaps remain relevant even when evidence is partitioned
            // in a Spring context.
            var selected = graph.contexts().stream().filter(c -> c.identity().equals(request.context())).findFirst()
                    .orElseThrow();
            if (hasGap(graph.snapshot().value()) || selected.analysis().map(a -> hasGap(a.value())).orElse(false))
                budget.qualify("UPSTREAM_CAPABILITY_GAP");
            if (project && budget.running()) {
                catalog = new GraphRegionCatalog(graph, request, budget, nodes);
                project();
            }
        }

        private boolean hasGap(String context) {
            return graph.nodes(context).stream()
                    .anyMatch(node -> node.kind().equals("CAPABILITY_GAP") || node.kind().equals("PROVIDER_CONFLICT"));
        }

        private void project() {
            var owners = new HashMap<String, String>();
            var parent = new HashMap<String, String>();
            int count = 0;
            for (var edge : graph.edges(request.context())) {
                if (!budget.scan())
                    return;
                if (++count > request.limits().maxEdges()) {
                    budget.halt(StopReason.INPUT);
                    return;
                }
                if (edge.target().state() == KnowledgeGraph.TargetState.RESOLVED) {
                    String target = edge.target().identities().getFirst();
                    if (edge.kind().equals("spring.declares-injection"))
                        owners.put(target, edge.source());
                    if (edge.kind().equals("graph.contains") || edge.kind().equals("graph.declares"))
                        parent.put(target, edge.source());
                }
            }
            var grouped = new TreeMap<String, LogicalEdge>();
            // A package/module population is induced by eligible source types, not by
            // whichever declaration's
            // metadata happens to be last on the aggregate owner (which may also contain
            // generated/test types).
            if (request.scope() == Scope.PACKAGE || request.scope() == Scope.MODULE) {
                var eligibleOwners = new TreeMap<String, EntityView>();
                for (var type : descriptions.values())
                    if (type.kind().equals("TYPE")
                            && included(type, new Population(Set.of(), request.population().modules(),
                                    request.population().origins(), request.population().sourceSets()))) {
                        String owner = scope(type.identity(), request.scope(), owners, parent);
                        var view = descriptions.get(owner);
                        if (view != null && (request.population().nodeKinds().isEmpty()
                                || request.population().nodeKinds().contains(view.kind())))
                            eligibleOwners.put(owner,
                                    new EntityView(view.identity(), view.context(), view.kind(), view.canonicalName(),
                                            view.qualifiedName(), view.spelling(), type.module(), type.origin(),
                                            type.sourceSet(), view.evidence()));
                    }
                entities.entrySet().removeIf(e -> e.getValue().kind().equals(request.scope().name()));
                entities.putAll(eligibleOwners);
            }
            for (var edge : graph.edges(request.context()))
                if (request.relationshipKinds().contains(edge.kind())) {
                    if (!budget.scan())
                        break;
                    if (!catalog.preferred(edge))
                        continue;
                    if (knownExcluded(descriptions.get(edge.source()), request.population()))
                        continue;
                    String source = scope(edge.source(), request.scope(), owners, parent);
                    var region = catalog.region(edge).combine(catalog.activate(edge.source()), true, budget);
                    if (edge.target().state() != KnowledgeGraph.TargetState.RESOLVED) {
                        budget.qualify("UNRESOLVED_RELATIONSHIP");
                        region = QueryRegion.unknown();
                    }
                    // Raw occurrences may have a target while semantic resolution/provenance
                    // remains qualified.
                    for (var ref : edge.evidence()) {
                        var payload = graph.evidence(ref).orElseThrow();
                        if (payload.family().equals("java.occurrence")) {
                            var status = read(payload, budget).get("status");
                            if (status != null && !"RESOLVED".equals(status)) {
                                region = QueryRegion.unknown();
                                budget.qualify("QUALIFIED_SEMANTIC_RELATIONSHIP");
                            }
                        }
                    }
                    if (edge.target().identities().isEmpty())
                        continue;
                    for (var targetIdentity : edge.target().identities()) {
                        if (knownExcluded(descriptions.get(targetIdentity), request.population()))
                            continue;
                        String target = scope(targetIdentity, request.scope(), owners, parent);
                        if (source == null || target == null || !entities.containsKey(source)
                                || !entities.containsKey(target))
                            continue;
                        if (source.equals(target)
                                && (request.scope() == Scope.PACKAGE || request.scope() == Scope.MODULE))
                            continue;
                        var activated = region.combine(catalog.activate(targetIdentity), true, budget)
                                .combine(catalog.activate(source), true, budget)
                                .combine(catalog.activate(target), true, budget);
                        String a = request.direction() == Direction.OUTGOING ? source : target,
                                b = request.direction() == Direction.OUTGOING ? target : source;
                        String key = a + "|" + b;
                        var previous = grouped.get(key);
                        if (!budget.reserve(4L + edge.evidence().size()))
                            break;
                        if (previous == null) {
                            previous = new LogicalEdge(a, b, activated);
                            grouped.put(key, previous);
                        } else
                            previous.region = previous.region.combine(activated, false, budget);
                        if (previous.supports.size() + previous.evidence.size() + previous.regions.size()
                                + edge.evidence().size() > request.limits().maxAuxiliaryStates()) {
                            budget.halt(StopReason.INPUT);
                            budget.frontier(a);
                            break;
                        }
                        previous.supports.add(edge.identity());
                        previous.kinds.add(edge.kind());
                        previous.regions.addAll(edge.region().stream().toList());
                        previous.evidence.addAll(edge.evidence());
                    }
                }
            for (var edge : grouped.values()) {
                if (!budget.scan())
                    break;
                var classification = edge.region.classify(budget);
                var check = edge.region.check(budget, request.realization());
                boolean include = switch (request.projection()) {
                    case MUST -> classification == Classification.MUST;
                    case MAY -> classification == Classification.MAY
                            || request.includeMust() && classification == Classification.MUST;
                    case REALIZED -> catalog.realizationValid && check.truth() == Truth.SAT;
                    case BOUNDED_UNION -> check.truth() != Truth.UNSAT;
                    case UNKNOWN_OVERLAY -> classification == Classification.UNKNOWN || check.truth() == Truth.UNKNOWN;
                };
                if (check.truth() == Truth.UNKNOWN)
                    budget.qualify("UNKNOWN_FEASIBILITY");
                if (!include)
                    continue;
                edge.view = new EdgeView(
                        KnowledgeGraph.derivedIdentity("query-edge",
                                List.of(VERSION, request.context(), request.scope(), edge.source, edge.target,
                                        edge.supports)),
                        edge.source, edge.target, List.copyOf(edge.kinds), bounded(edge.supports, budget),
                        bounded(edge.evidence, budget), bounded(edge.regions, budget), classification,
                        check.truth() == Truth.SAT,
                        edge.supports.size(), edge.supports.size() > request.limits().maxResults());
                edges.add(edge);
                adjacency.computeIfAbsent(edge.source, k -> new ArrayList<>()).add(edge);
            }
        }

        private String scope(String identity, Scope scope, Map<String, String> owners, Map<String, String> parent) {
            if (scope == Scope.ENTITY)
                return identity;
            if (scope == Scope.SPRING_COMPONENT)
                return owners.getOrDefault(identity, identity);
            String kind = scope.name();
            var seen = new HashSet<String>();
            String cursor = identity;
            while (cursor != null && seen.add(cursor) && seen.size() <= request.limits().maxVertices()) {
                if (!budget.scan())
                    return null;
                var node = nodes.get(cursor);
                if (node != null && node.kind().equals(kind))
                    return cursor;
                cursor = parent.get(cursor);
            }
            budget.qualify("SCOPE_OWNER_UNAVAILABLE");
            return null;
        }
    }

    private void validate(Request request) {
        if (!graphIdentity.equals(request.graph()))
            throw new IllegalArgumentException("Foreign/stale graph identity");
        if (graph.contexts().stream().noneMatch(c -> c.identity().equals(request.context())))
            throw new IllegalArgumentException("Foreign context");
    }

    /**
     * Generic bounded deterministic per-session memoization keyed by {@code (queryOp, canonicalArgs)}.
     * Respects operational guards ({@code CANCELLED}/{@code TIME}/{@code MEMORY}) and never mutates cached values.
     */
    @SuppressWarnings("unchecked")
    public <T> T memoizedQuery(String queryOp, Object canonicalArgs, Supplier<T> supplier) {
        Objects.requireNonNull(queryOp, "queryOp");
        Objects.requireNonNull(canonicalArgs, "canonicalArgs");
        Objects.requireNonNull(supplier, "supplier");
        if (!memoizationEnabled || guard.get() != StopReason.NONE) {
            memoMisses.incrementAndGet();
            return supplier.get();
        }
        String argsDigest = ContentDigest.sha256Utf8(CanonicalJson.write(canonicalArgs)).value();
        var key = new MemoKey(queryOp, argsDigest);
        Object hit;
        synchronized (memoLock) {
            hit = queryMemoCache.get(key);
        }
        if (hit != null) {
            memoHits.incrementAndGet();
            return (T) hit;
        }
        memoMisses.incrementAndGet();
        T computed = supplier.get();
        if (computed != null && guard.get() == StopReason.NONE) {
            synchronized (memoLock) {
                Object raced = queryMemoCache.get(key);
                if (raced != null) {
                    return (T) raced;
                }
                queryMemoCache.put(key, computed);
            }
        }
        return computed;
    }

    @SuppressWarnings("unchecked")
    private <T> Result<T> memoizedResult(String queryOp, Request request, Object canonicalArgs,
            Supplier<Result<T>> supplier) {
        validate(request);
        if (!memoizationEnabled || guard.get() != StopReason.NONE) {
            memoMisses.incrementAndGet();
            return supplier.get();
        }
        String argsDigest = ContentDigest.sha256Utf8(
                CanonicalJson.write(List.of(request.identity().value(), canonicalArgs))).value();
        var key = new MemoKey(queryOp, argsDigest);
        Object hit;
        synchronized (memoLock) {
            hit = queryMemoCache.get(key);
        }
        if (hit != null) {
            memoHits.incrementAndGet();
            return (Result<T>) hit;
        }
        memoMisses.incrementAndGet();
        Result<T> computed = supplier.get();
        // Operational guards are outside query identity; never cache interrupted or resource-limited results.
        if (computed != null && computed.operation() == Operation.COMPLETED && guard.get() == StopReason.NONE) {
            synchronized (memoLock) {
                Object raced = queryMemoCache.get(key);
                if (raced != null) {
                    return (Result<T>) raced;
                }
                queryMemoCache.put(key, computed);
            }
        }
        return computed;
    }

    private SessionMetadataIndex metadataIndex() {
        if (!memoizationEnabled) {
            graphScanCount.incrementAndGet();
            return new SessionMetadataIndex(graph, this);
        }
        SessionMetadataIndex idx = sessionMetadataIndex;
        if (idx != null) {
            return idx;
        }
        synchronized (this) {
            idx = sessionMetadataIndex;
            if (idx == null) {
                graphScanCount.incrementAndGet();
                idx = new SessionMetadataIndex(graph, this);
                sessionMetadataIndex = idx;
            }
            return idx;
        }
    }

    /** Returns all nodes of {@code kind} in {@code context} (or across all contexts when {@code context} is empty). */
    public List<KnowledgeGraph.Node> nodesByKind(String context, String kind) {
        Objects.requireNonNull(kind, "kind");
        return metadataIndex().nodesByKind(context, kind);
    }

    /** Returns all edges of {@code kind} in {@code context} (or across all contexts when {@code context} is empty). */
    public List<KnowledgeGraph.Edge> edgesByKind(String context, String kind) {
        Objects.requireNonNull(kind, "kind");
        return metadataIndex().edgesByKind(context, kind);
    }

    /** Returns all outgoing edges from {@code sourceId} in {@code context} (or across all contexts when empty). */
    public List<KnowledgeGraph.Edge> edgesBySource(String context, String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId");
        return metadataIndex().edgesBySource(context, sourceId);
    }

    /** Returns all graph evidence entries of {@code family} in deterministic graph order (single-scan indexed). */
    public List<GraphEvidence> evidenceByFamily(String family) {
        Objects.requireNonNull(family, "family");
        return metadataIndex().evidenceByFamily(family);
    }

    /** Parses an immutable graph evidence JSON payload with per-session memoization. */
    public Map<String, Object> parsedEvidence(GraphEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        if (memoizationEnabled) {
            var cached = parsedEvidenceCache.get(evidence.identity());
            if (cached != null) {
                return cached;
            }
        }
        try {
            var parsed = Collections.unmodifiableMap(GraphJson.object(
                    GraphJson.read(evidence.canonicalJson(), new GraphJson.Limits(8_000_000, 128, 200_000))));
            if (memoizationEnabled && parsedEvidenceCache.size() < 65_536) {
                parsedEvidenceCache.putIfAbsent(evidence.identity(), parsed);
            }
            return parsed;
        } catch (IllegalArgumentException malformed) {
            return Map.of();
        }
    }

    /**
     * Lazily populates and memoizes member/annotation metadata for one class (MeCheck Fig. 6).
     * {@code context} may be empty to search across all contexts deterministically.
     */
    public Optional<ClassMetadata> classMetadata(String context, String fqcnOrNodeId) {
        Objects.requireNonNull(fqcnOrNodeId, "fqcnOrNodeId");
        return metadataIndex().classMetadata(context, fqcnOrNodeId, memoizationEnabled);
    }

    /** Returns all indexed class FQNs present in {@code context} (or all contexts when empty), sorted deterministically. */
    public List<String> indexedClassNames(String context) {
        return metadataIndex().indexedClassNames(context);
    }

    // -------------------------------------------------------------------------
    // WP3.2: Typed Compositional Graph Queries & Pointer-Indexed Schema Views
    // -------------------------------------------------------------------------

    /**
     * Executes a typed compositional multi-hop query ({@code graph_compose_query}) against this
     * service's graph, guarded by {@code operationalGuard} ({@code StopReason.MEMORY}, hard
     * {@code < 2 s} wall-time cap, and visited-state cap).
     */
    public GraphComposeQuery.ComposeResult composeQuery(GraphComposeQuery query) {
        return composeQuery(this.graph, query, GraphComposeQuery.DEFAULT_TIMEOUT_MILLIS,
                GraphComposeQuery.DEFAULT_MAX_VISITED_STATES);
    }

    /**
     * Executes a typed compositional multi-hop query ({@code graph_compose_query}) against
     * {@code targetGraph}, guarded by this service's {@code operationalGuard}, a hard
     * {@code < 2 s} wall-time cap, and a bounded visited-state cap.
     * Results are pointer-indexed ({@code path:startLine-endLine@sha256Prefix}) and
     * deterministically sorted by {@code (score DESC, entityId ASC)} ({@code I2}).
     */
    public GraphComposeQuery.ComposeResult composeQuery(KnowledgeGraph targetGraph, GraphComposeQuery query) {
        return composeQuery(targetGraph, query, GraphComposeQuery.DEFAULT_TIMEOUT_MILLIS,
                GraphComposeQuery.DEFAULT_MAX_VISITED_STATES);
    }

    /**
     * Bounded execution overload allowing callers/tests to tighten wall-time ({@code <= 2000 ms})
     * and visited-state budgets while always enforcing {@code operationalGuard}.
     */
    public GraphComposeQuery.ComposeResult composeQuery(
            KnowledgeGraph targetGraph,
            GraphComposeQuery query,
            long timeoutMillis,
            int maxVisitedStates) {
        Objects.requireNonNull(targetGraph, "graph");
        Objects.requireNonNull(query, "query");
        long boundedTimeoutMs = Math.max(1L, Math.min(timeoutMillis, GraphComposeQuery.DEFAULT_TIMEOUT_MILLIS));
        int boundedMaxStates = Math.max(1, Math.min(maxVisitedStates, GraphComposeQuery.DEFAULT_MAX_VISITED_STATES));

        boolean canMemoize = memoizationEnabled && targetGraph == this.graph
                && boundedTimeoutMs == GraphComposeQuery.DEFAULT_TIMEOUT_MILLIS
                && boundedMaxStates == GraphComposeQuery.DEFAULT_MAX_VISITED_STATES
                && guard.get() == StopReason.NONE;
        if (canMemoize) {
            var key = new MemoKey("composeQuery",
                    ContentDigest.sha256Utf8(CanonicalJson.write(query.toMap())).value());
            Object hit;
            synchronized (memoLock) {
                hit = queryMemoCache.get(key);
            }
            if (hit instanceof GraphComposeQuery.ComposeResult cached) {
                memoHits.incrementAndGet();
                return cached;
            }
            memoMisses.incrementAndGet();
            var computed = executeComposeQuery(targetGraph, query, boundedTimeoutMs, boundedMaxStates);
            if (computed.stopReason() == StopReason.NONE && guard.get() == StopReason.NONE) {
                synchronized (memoLock) {
                    queryMemoCache.putIfAbsent(key, computed);
                }
            }
            return computed;
        }
        return executeComposeQuery(targetGraph, query, boundedTimeoutMs, boundedMaxStates);
    }

    /** Summarizes the live schema of this service's graph ({@code evo://schema}). */
    public GraphComposeQuery.SchemaView schemaView() {
        return schemaView(this.graph);
    }

    /**
     * Summarizes the live node kinds, edge kinds, trust tiers, evidence families, and queryable
     * fields of {@code targetGraph} in deterministic sorted order ({@code evo://schema}).
     */
    public GraphComposeQuery.SchemaView schemaView(KnowledgeGraph targetGraph) {
        Objects.requireNonNull(targetGraph, "graph");
        boolean canMemoize = memoizationEnabled && targetGraph == this.graph && guard.get() == StopReason.NONE;
        if (canMemoize) {
            return memoizedQuery("schemaView", targetGraph.identity().value(), () -> buildSchemaView(targetGraph));
        }
        return buildSchemaView(targetGraph);
    }

    /**
     * Extracts a deterministic {@code k=1} flattened (or {@code k >= 2} auto signature-compressed)
     * symbol ego-graph ({@code symbol_ego_graph}, {@code <= 2,500} tokens) from this service's graph.
     */
    public SymbolEgoGraphExtractor.Result symbolEgoGraph(SymbolEgoGraphExtractor.Request request) {
        return symbolEgoGraph(this.graph, request);
    }

    /**
     * Extracts a deterministic {@code k=1} flattened (or {@code k >= 2} auto signature-compressed)
     * symbol ego-graph ({@code symbol_ego_graph}, {@code <= 2,500} tokens) from {@code targetGraph},
     * guarded by this service's {@code operationalGuard}.
     */
    public SymbolEgoGraphExtractor.Result symbolEgoGraph(
            KnowledgeGraph targetGraph, SymbolEgoGraphExtractor.Request request) {
        Objects.requireNonNull(targetGraph, "graph");
        Objects.requireNonNull(request, "request");
        boolean canMemoize = memoizationEnabled && targetGraph == this.graph && guard.get() == StopReason.NONE;
        if (canMemoize) {
            String keyStr = ContentDigest.sha256Utf8(CanonicalJson.write(List.of(
                    SymbolEgoGraphExtractor.VERSION,
                    targetGraph.identity().value(),
                    request.context().orElse(""),
                    request.symbolId().orElse(""),
                    request.searchTerm().orElse(""),
                    request.hops(),
                    request.maxTokens(),
                    request.includeStdlib()))).value();
            return memoizedQuery("symbolEgoGraph", keyStr,
                    () -> new SymbolEgoGraphExtractor().extract(targetGraph, request, guard));
        }
        return new SymbolEgoGraphExtractor().extract(targetGraph, request, guard);
    }

    private record ComposeDocMeta(String path, String sha256) {}

    private record ComposeNodeMeta(
            String id,
            String context,
            String kind,
            String fqn,
            String simpleName,
            TrustTier trustTier,
            Optional<String> sourcePath,
            int startLine,
            int endLine,
            String sha256Prefix,
            String pointerRef,
            Optional<String> module,
            Optional<String> origin,
            Optional<String> sourceSet,
            int degree
    ) {
        GraphComposeQuery.ProjectedNode toProjected() {
            return new GraphComposeQuery.ProjectedNode(
                    id, fqn, kind, trustTier,
                    new GraphComposeQuery.SpanRef(sourcePath, startLine, endLine, sha256Prefix),
                    pointerRef);
        }

        String fieldValue(String field) {
            return switch (field) {
                case "id" -> id;
                case "fqn" -> fqn;
                case "name" -> simpleName.isEmpty() ? fqn : simpleName;
                case "kind" -> kind;
                case "trustTier" -> trustTier.name();
                case "path", "sourcePath" -> sourcePath.orElse(null);
                case "context" -> context;
                case "module" -> module.orElse(null);
                case "origin" -> origin.orElse(null);
                case "sourceSet" -> sourceSet.orElse(null);
                case "pointerRef" -> pointerRef;
                default -> null;
            };
        }
    }

    private record ComposeAdj(String neighborId, String edgeKind, String edgeId) {}

    private record HopWitness(int hops, List<String> edgeKinds) {}

    private record ScoredMatch(
            String entityId,
            BigDecimal score,
            String bindingKey,
            GraphComposeQuery.ComposeMatch match
    ) {}

    private static final class ComposeExecutionGuard {
        private final Supplier<StopReason> externalGuard;
        private final long deadlineNanos;
        private final int maxVisitedStates;
        private int visitedStates;
        private StopReason stopReason = StopReason.NONE;

        ComposeExecutionGuard(Supplier<StopReason> externalGuard, long timeoutMillis, int maxVisitedStates) {
            this.externalGuard = externalGuard;
            this.deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000L;
            this.maxVisitedStates = maxVisitedStates;
        }

        boolean step() {
            if (stopReason != StopReason.NONE) {
                return false;
            }
            if (++visitedStates > maxVisitedStates) {
                stopReason = StopReason.STEPS;
                return false;
            }
            if ((visitedStates & 0x3F) == 1) {
                return checkExternal();
            }
            return true;
        }

        boolean checkExternal() {
            if (stopReason != StopReason.NONE) {
                return false;
            }
            StopReason ext = externalGuard.get();
            if (ext != null && ext != StopReason.NONE) {
                stopReason = ext;
                return false;
            }
            if (System.nanoTime() - deadlineNanos >= 0L) {
                stopReason = StopReason.TIME;
                return false;
            }
            return true;
        }

        StopReason stopReason() {
            return stopReason;
        }

        int visitedStates() {
            return visitedStates;
        }
    }

    private GraphComposeQuery.SchemaView buildSchemaView(KnowledgeGraph targetGraph) {
        var contexts = targetGraph.contexts().stream()
                .map(KnowledgeGraph.Context::identity)
                .sorted()
                .toList();
        var nodeKinds = new TreeMap<String, Long>();
        var trustTiers = new TreeMap<String, Long>();
        for (var node : targetGraph.nodes()) {
            nodeKinds.merge(node.kind(), 1L, Long::sum);
            TrustTier tier = inferNodeTrustTier(targetGraph, node);
            trustTiers.merge(tier.name(), 1L, Long::sum);
        }
        var edgeKinds = new TreeMap<String, Long>();
        for (var edge : targetGraph.edges()) {
            edgeKinds.merge(edge.kind(), 1L, Long::sum);
        }
        var evidenceFamilies = new TreeMap<String, Long>();
        for (var ev : targetGraph.evidence()) {
            evidenceFamilies.merge(ev.family(), 1L, Long::sum);
        }
        var operators = Arrays.stream(GraphComposeQuery.FilterOp.values())
                .map(Enum::name)
                .sorted()
                .toList();
        return new GraphComposeQuery.SchemaView(
                GraphComposeQuery.SCHEMA_VIEW_VERSION,
                KnowledgeGraph.SCHEMA,
                targetGraph.identity(),
                targetGraph.snapshot().value(),
                targetGraph.nodes().size(),
                targetGraph.edges().size(),
                targetGraph.evidence().size(),
                contexts,
                nodeKinds,
                edgeKinds,
                trustTiers,
                evidenceFamilies,
                GraphComposeQuery.QUERYABLE_FILTER_FIELDS,
                GraphComposeQuery.ALLOWED_RETURN_FIELDS,
                operators,
                GraphComposeQuery.advertisedLimits(),
                GraphComposeQuery.POINTER_REF_FORMAT,
                GraphComposeQuery.LAZY_READ_TOOL);
    }

    private GraphComposeQuery.ComposeResult executeComposeQuery(
            KnowledgeGraph targetGraph,
            GraphComposeQuery query,
            long timeoutMillis,
            int maxVisitedStates) {
        var execGuard = new ComposeExecutionGuard(guard, timeoutMillis, maxVisitedStates);
        var gaps = new ArrayList<CapabilityGapRecord>();
        ContentDigest queryDigest = ContentDigest.sha256Utf8(
                CanonicalJson.write(List.of(GraphComposeQuery.SCHEMA_VERSION, targetGraph.identity(), query.toMap())));

        if (!execGuard.checkExternal()) {
            gaps.add(composeGap(targetGraph, query.context(), queryDigest, stopReasonGapCode(execGuard.stopReason())));
            return new GraphComposeQuery.ComposeResult(
                    GraphComposeQuery.SCHEMA_VERSION, targetGraph.identity(), query.context(),
                    AssessmentStatus.PARTIAL, execGuard.stopReason(), true, query.limit(),
                    0, execGuard.visitedStates(), query.returnFields(), List.of(), gaps);
        }

        // Validate explicit context filter against live graph contexts
        if (query.context().isPresent()) {
            String reqCtx = query.context().orElseThrow();
            boolean exists = targetGraph.contexts().stream().anyMatch(c -> c.identity().equals(reqCtx));
            if (!exists) {
                gaps.add(composeGap(targetGraph, query.context(), queryDigest, "COMPOSE_CONTEXT_NOT_FOUND"));
                return new GraphComposeQuery.ComposeResult(
                        GraphComposeQuery.SCHEMA_VERSION, targetGraph.identity(), query.context(),
                        AssessmentStatus.PARTIAL, StopReason.NONE, false, query.limit(),
                        0, execGuard.visitedStates(), query.returnFields(), List.of(), gaps);
            }
        }

        // 1. Collect validated document path & content SHA-256 index across all contexts
        var docs = new HashMap<String, ComposeDocMeta>();
        for (var node : targetGraph.nodes()) {
            if (!execGuard.step()) {
                break;
            }
            if ("SOURCE_DOCUMENT".equals(node.kind())) {
                extractComposeDocMeta(targetGraph, node).ifPresent(dm -> docs.putIfAbsent(node.identity(), dm));
            }
        }
        if (execGuard.stopReason() != StopReason.NONE) {
            gaps.add(composeGap(targetGraph, query.context(), queryDigest, stopReasonGapCode(execGuard.stopReason())));
            return new GraphComposeQuery.ComposeResult(
                    GraphComposeQuery.SCHEMA_VERSION, targetGraph.identity(), query.context(),
                    AssessmentStatus.PARTIAL, execGuard.stopReason(), true, query.limit(),
                    0, execGuard.visitedStates(), query.returnFields(), List.of(), gaps);
        }

        // 2. Scope nodes & edges + compute incident degree and sorted adjacency lists
        List<KnowledgeGraph.Node> scopedNodes = query.context().isPresent()
                ? targetGraph.nodes(query.context().orElseThrow())
                : targetGraph.nodes();
        List<KnowledgeGraph.Edge> scopedEdges = query.context().isPresent()
                ? targetGraph.edges(query.context().orElseThrow())
                : targetGraph.edges();

        var degreeByNode = new HashMap<String, Integer>();
        var outgoingAdj = new HashMap<String, List<ComposeAdj>>();
        var incomingAdj = new HashMap<String, List<ComposeAdj>>();
        boolean needsEdges = !query.matchEdges().isEmpty();
        for (var edge : scopedEdges) {
            if (!execGuard.step()) {
                break;
            }
            if (edge.target().identities().isEmpty()) {
                continue;
            }
            for (String tgt : edge.target().identities()) {
                degreeByNode.merge(edge.source(), 1, Integer::sum);
                degreeByNode.merge(tgt, 1, Integer::sum);
                if (needsEdges) {
                    outgoingAdj.computeIfAbsent(edge.source(), k -> new ArrayList<>())
                            .add(new ComposeAdj(tgt, edge.kind(), edge.identity()));
                    incomingAdj.computeIfAbsent(tgt, k -> new ArrayList<>())
                            .add(new ComposeAdj(edge.source(), edge.kind(), edge.identity()));
                }
            }
        }
        if (execGuard.stopReason() != StopReason.NONE) {
            gaps.add(composeGap(targetGraph, query.context(), queryDigest, stopReasonGapCode(execGuard.stopReason())));
            return new GraphComposeQuery.ComposeResult(
                    GraphComposeQuery.SCHEMA_VERSION, targetGraph.identity(), query.context(),
                    AssessmentStatus.PARTIAL, execGuard.stopReason(), true, query.limit(),
                    0, execGuard.visitedStates(), query.returnFields(), List.of(), gaps);
        }
        if (needsEdges) {
            Comparator<ComposeAdj> adjOrder = Comparator.comparing(ComposeAdj::neighborId)
                    .thenComparing(ComposeAdj::edgeKind)
                    .thenComparing(ComposeAdj::edgeId);
            outgoingAdj.values().forEach(list -> list.sort(adjOrder));
            incomingAdj.values().forEach(list -> list.sort(adjOrder));
        }

        // 3. Extract compact pointer-indexed metadata per node (deduplicated deterministically by node id)
        var nodeMetaById = new TreeMap<String, ComposeNodeMeta>();
        for (var node : scopedNodes) {
            if (!execGuard.step()) {
                break;
            }
            int deg = degreeByNode.getOrDefault(node.identity(), 0);
            var meta = extractComposeNodeMeta(targetGraph, node, docs, deg);
            var existing = nodeMetaById.get(node.identity());
            if (existing == null || (existing.sourcePath().isEmpty() && meta.sourcePath().isPresent())) {
                nodeMetaById.put(node.identity(), meta);
            }
        }
        if (execGuard.stopReason() != StopReason.NONE) {
            gaps.add(composeGap(targetGraph, query.context(), queryDigest, stopReasonGapCode(execGuard.stopReason())));
            return new GraphComposeQuery.ComposeResult(
                    GraphComposeQuery.SCHEMA_VERSION, targetGraph.identity(), query.context(),
                    AssessmentStatus.PARTIAL, execGuard.stopReason(), true, query.limit(),
                    0, execGuard.visitedStates(), query.returnFields(), List.of(), gaps);
        }

        // 4. Pre-compile validated REGEX_BOUNDED filters & group filters by alias
        var filtersByAlias = new HashMap<String, List<GraphComposeQuery.FilterClause>>();
        var compiledRegexes = new IdentityHashMap<GraphComposeQuery.FilterClause, Pattern>();
        for (var fc : query.filters()) {
            filtersByAlias.computeIfAbsent(fc.alias(), k -> new ArrayList<>()).add(fc);
            if (fc.op() == GraphComposeQuery.FilterOp.REGEX_BOUNDED && fc.value().isPresent()) {
                compiledRegexes.put(fc, GraphComposeQuery.validateBoundedRegex(
                        fc.value().orElseThrow(), "filters[" + fc.alias() + "." + fc.field() + "].value"));
            }
        }

        // 5. Filter candidate nodes per alias in deterministic ID order
        var patternByAlias = new LinkedHashMap<String, GraphComposeQuery.NodePattern>();
        var candidatesByAlias = new LinkedHashMap<String, List<ComposeNodeMeta>>();
        var candidateIdSets = new LinkedHashMap<String, Set<String>>();
        for (var np : query.matchNodes()) {
            patternByAlias.put(np.alias(), np);
            var aliasFilters = filtersByAlias.getOrDefault(np.alias(), List.of());
            var cands = new ArrayList<ComposeNodeMeta>();
            var candIds = new HashSet<String>();
            for (var meta : nodeMetaById.values()) {
                if (!execGuard.step()) {
                    break;
                }
                if (matchesNodePattern(np, meta) && matchesAllFilters(aliasFilters, compiledRegexes, meta)) {
                    cands.add(meta);
                    candIds.add(meta.id());
                }
            }
            candidatesByAlias.put(np.alias(), cands);
            candidateIdSets.put(np.alias(), candIds);
            if (execGuard.stopReason() != StopReason.NONE) {
                break;
            }
        }
        if (execGuard.stopReason() != StopReason.NONE) {
            gaps.add(composeGap(targetGraph, query.context(), queryDigest, stopReasonGapCode(execGuard.stopReason())));
            return new GraphComposeQuery.ComposeResult(
                    GraphComposeQuery.SCHEMA_VERSION, targetGraph.identity(), query.context(),
                    AssessmentStatus.PARTIAL, execGuard.stopReason(), true, query.limit(),
                    0, execGuard.visitedStates(), query.returnFields(), List.of(), gaps);
        }

        // Fast empty exit when any alias has zero candidates
        for (var cands : candidatesByAlias.values()) {
            if (cands.isEmpty()) {
                return new GraphComposeQuery.ComposeResult(
                        GraphComposeQuery.SCHEMA_VERSION, targetGraph.identity(), query.context(),
                        AssessmentStatus.COMPLETE, StopReason.NONE, false, query.limit(),
                        0, execGuard.visitedStates(), query.returnFields(), List.of(), List.of());
            }
        }

        // 6. Execute bounded multi-hop pattern matching join
        var collected = new ArrayList<ScoredMatch>();
        var seenBindingKeys = new HashSet<String>();
        int poolCap = Math.max(query.limit() * 20, 1_000);
        boolean[] poolTruncated = new boolean[1];
        String anchorAlias = query.matchNodes().getFirst().alias();

        if (query.matchNodes().size() == 1 && query.matchEdges().isEmpty()) {
            var np = query.matchNodes().getFirst();
            for (var meta : candidatesByAlias.get(anchorAlias)) {
                if (!execGuard.step()) {
                    break;
                }
                var bindings = Map.of(anchorAlias, meta);
                BigDecimal score = scoreComposeMatch(query, patternByAlias, bindings, List.of());
                var match = new GraphComposeQuery.ComposeMatch(
                        meta.id(), score, Map.of(anchorAlias, meta.toProjected()), List.of());
                collected.add(new ScoredMatch(meta.id(), score, meta.id(), match));
            }
        } else {
            // Deterministic variable order: smallest candidate set first, tie-break by alias ASC
            var aliasOrder = new ArrayList<>(patternByAlias.keySet());
            aliasOrder.sort(Comparator.<String>comparingInt(a -> candidatesByAlias.get(a).size())
                    .thenComparing(Comparator.naturalOrder()));

            // Pre-index edges incident to each alias for eager constraint pruning
            var boundAssignment = new LinkedHashMap<String, ComposeNodeMeta>();
            var satisfiedEdges = new LinkedHashMap<Integer, GraphComposeQuery.MatchedEdge>();
            backtrackComposeJoin(
                    0, aliasOrder, query, patternByAlias, candidatesByAlias, candidateIdSets,
                    nodeMetaById, outgoingAdj, incomingAdj, boundAssignment, satisfiedEdges,
                    anchorAlias, seenBindingKeys, collected, poolCap, poolTruncated, execGuard);
        }

        // 7. Sort deterministically by (score DESC, entityId ASC, bindingKey ASC) & slice to limit (I2)
        collected.sort(Comparator.comparing(ScoredMatch::score).reversed()
                .thenComparing(ScoredMatch::entityId)
                .thenComparing(ScoredMatch::bindingKey));

        int totalMatched = collected.size();
        boolean slicedByLimit = totalMatched > query.limit();
        boolean stoppedEarly = execGuard.stopReason() != StopReason.NONE;
        boolean truncated = slicedByLimit || poolTruncated[0] || stoppedEarly;

        if (stoppedEarly) {
            gaps.add(composeGap(targetGraph, query.context(), queryDigest, stopReasonGapCode(execGuard.stopReason())));
        } else if (poolTruncated[0]) {
            gaps.add(composeGap(targetGraph, query.context(), queryDigest, "COMPOSE_MATCH_POOL_BOUNDED"));
        }

        var topMatches = collected.stream()
                .limit(query.limit())
                .map(ScoredMatch::match)
                .toList();
        AssessmentStatus status = (stoppedEarly || poolTruncated[0])
                ? AssessmentStatus.PARTIAL
                : AssessmentStatus.COMPLETE;

        return new GraphComposeQuery.ComposeResult(
                GraphComposeQuery.SCHEMA_VERSION,
                targetGraph.identity(),
                query.context(),
                status,
                execGuard.stopReason(),
                truncated,
                query.limit(),
                totalMatched,
                execGuard.visitedStates(),
                query.returnFields(),
                topMatches,
                gaps);
    }

    private void backtrackComposeJoin(
            int depth,
            List<String> aliasOrder,
            GraphComposeQuery query,
            Map<String, GraphComposeQuery.NodePattern> patternByAlias,
            Map<String, List<ComposeNodeMeta>> candidatesByAlias,
            Map<String, Set<String>> candidateIdSets,
            Map<String, ComposeNodeMeta> nodeMetaById,
            Map<String, List<ComposeAdj>> outgoingAdj,
            Map<String, List<ComposeAdj>> incomingAdj,
            Map<String, ComposeNodeMeta> bound,
            Map<Integer, GraphComposeQuery.MatchedEdge> satisfiedEdges,
            String anchorAlias,
            Set<String> seenBindingKeys,
            List<ScoredMatch> collected,
            int poolCap,
            boolean[] poolTruncated,
            ComposeExecutionGuard execGuard) {
        if (execGuard.stopReason() != StopReason.NONE || poolTruncated[0]) {
            return;
        }
        if (depth == aliasOrder.size()) {
            // Verify all edge patterns are satisfied
            var edgeWitnesses = new ArrayList<GraphComposeQuery.MatchedEdge>(query.matchEdges().size());
            for (int i = 0; i < query.matchEdges().size(); i++) {
                var witness = satisfiedEdges.get(i);
                if (witness == null) {
                    var ep = query.matchEdges().get(i);
                    String fromId = bound.get(ep.from()).id();
                    String toId = bound.get(ep.to()).id();
                    var reached = boundedMultiHopSearch(fromId, ep.edgeKinds(), ep.minHops(), ep.maxHops(),
                            outgoingAdj, Set.of(toId), false, execGuard);
                    if (execGuard.stopReason() != StopReason.NONE) {
                        return;
                    }
                    HopWitness hw = reached.get(toId);
                    if (hw == null) {
                        return;
                    }
                    witness = new GraphComposeQuery.MatchedEdge(ep.from(), ep.to(), fromId, toId, hw.hops(), hw.edgeKinds());
                }
                edgeWitnesses.add(witness);
            }
            var keyBuilder = new StringBuilder();
            for (var np : query.matchNodes()) {
                if (keyBuilder.length() > 0) keyBuilder.append('|');
                keyBuilder.append(np.alias()).append('=').append(bound.get(np.alias()).id());
            }
            String bindingKey = keyBuilder.toString();
            if (!seenBindingKeys.add(bindingKey)) {
                return;
            }
            if (collected.size() >= poolCap) {
                poolTruncated[0] = true;
                return;
            }
            String anchorId = bound.get(anchorAlias).id();
            BigDecimal score = scoreComposeMatch(query, patternByAlias, bound, edgeWitnesses);
            var projectedBindings = new TreeMap<String, GraphComposeQuery.ProjectedNode>();
            for (var e : bound.entrySet()) {
                projectedBindings.put(e.getKey(), e.getValue().toProjected());
            }
            var match = new GraphComposeQuery.ComposeMatch(anchorId, score, projectedBindings, edgeWitnesses);
            collected.add(new ScoredMatch(anchorId, score, bindingKey, match));
            return;
        }

        // Pick next unbound alias: prefer one connected by an edge to an already-bound alias for aggressive pruning
        String nextAlias = null;
        int drivingEdgeIdx = -1;
        boolean drivingForward = true;
        for (int i = 0; i < query.matchEdges().size(); i++) {
            var ep = query.matchEdges().get(i);
            boolean fromBound = bound.containsKey(ep.from());
            boolean toBound = bound.containsKey(ep.to());
            if (fromBound && !toBound) {
                nextAlias = ep.to();
                drivingEdgeIdx = i;
                drivingForward = true;
                break;
            }
            if (!fromBound && toBound) {
                nextAlias = ep.from();
                drivingEdgeIdx = i;
                drivingForward = false;
                break;
            }
        }
        if (nextAlias == null) {
            for (String candidateAlias : aliasOrder) {
                if (!bound.containsKey(candidateAlias)) {
                    nextAlias = candidateAlias;
                    break;
                }
            }
        }
        if (nextAlias == null) {
            return;
        }

        List<ComposeNodeMeta> domain;
        Map<String, HopWitness> drivingWitnesses = null;
        if (drivingEdgeIdx >= 0) {
            var ep = query.matchEdges().get(drivingEdgeIdx);
            String startId = bound.get(drivingForward ? ep.from() : ep.to()).id();
            var adj = drivingForward ? outgoingAdj : incomingAdj;
            drivingWitnesses = boundedMultiHopSearch(startId, ep.edgeKinds(), ep.minHops(), ep.maxHops(),
                    adj, candidateIdSets.get(nextAlias), !drivingForward, execGuard);
            if (execGuard.stopReason() != StopReason.NONE || drivingWitnesses.isEmpty()) {
                return;
            }
            domain = new ArrayList<>(drivingWitnesses.size());
            for (String id : drivingWitnesses.keySet()) {
                var meta = nodeMetaById.get(id);
                if (meta != null) {
                    domain.add(meta);
                }
            }
        } else {
            domain = candidatesByAlias.get(nextAlias);
        }

        for (var candidate : domain) {
            if (!execGuard.step() || poolTruncated[0]) {
                return;
            }
            bound.put(nextAlias, candidate);
            var addedEdgeIndices = new ArrayList<Integer>();
            boolean edgesConsistent = true;
            if (drivingEdgeIdx >= 0 && drivingWitnesses != null) {
                var ep = query.matchEdges().get(drivingEdgeIdx);
                HopWitness hw = drivingWitnesses.get(candidate.id());
                satisfiedEdges.put(drivingEdgeIdx, new GraphComposeQuery.MatchedEdge(
                        ep.from(), ep.to(), bound.get(ep.from()).id(), bound.get(ep.to()).id(),
                        hw.hops(), hw.edgeKinds()));
                addedEdgeIndices.add(drivingEdgeIdx);
            }
            // Eagerly validate any other edge whose both endpoints are now bound
            for (int i = 0; i < query.matchEdges().size(); i++) {
                if (i == drivingEdgeIdx || satisfiedEdges.containsKey(i)) {
                    continue;
                }
                var ep = query.matchEdges().get(i);
                if (bound.containsKey(ep.from()) && bound.containsKey(ep.to())) {
                    String fromId = bound.get(ep.from()).id();
                    String toId = bound.get(ep.to()).id();
                    var reached = boundedMultiHopSearch(fromId, ep.edgeKinds(), ep.minHops(), ep.maxHops(),
                            outgoingAdj, Set.of(toId), false, execGuard);
                    if (execGuard.stopReason() != StopReason.NONE) {
                        edgesConsistent = false;
                        break;
                    }
                    HopWitness hw = reached.get(toId);
                    if (hw == null) {
                        edgesConsistent = false;
                        break;
                    }
                    satisfiedEdges.put(i, new GraphComposeQuery.MatchedEdge(
                            ep.from(), ep.to(), fromId, toId, hw.hops(), hw.edgeKinds()));
                    addedEdgeIndices.add(i);
                }
            }

            if (edgesConsistent && execGuard.stopReason() == StopReason.NONE) {
                backtrackComposeJoin(
                        depth + 1, aliasOrder, query, patternByAlias, candidatesByAlias, candidateIdSets,
                        nodeMetaById, outgoingAdj, incomingAdj, bound, satisfiedEdges,
                        anchorAlias, seenBindingKeys, collected, poolCap, poolTruncated, execGuard);
            }
            for (int idx : addedEdgeIndices) {
                satisfiedEdges.remove(idx);
            }
            bound.remove(nextAlias);
        }
    }

    /**
     * Exact bounded multi-hop layered BFS ({@code 1 <= minHops <= maxHops <= 4}) returning reachable
     * targets in deterministic node-ID order with shortest valid hop count and edge-kind witnesses.
     */
    private static Map<String, HopWitness> boundedMultiHopSearch(
            String startId,
            List<String> allowedEdgeKinds,
            int minHops,
            int maxHops,
            Map<String, List<ComposeAdj>> adjacency,
            Set<String> targetFilter,
            boolean reversePathKinds,
            ComposeExecutionGuard execGuard) {
        var reached = new TreeMap<String, HopWitness>();
        Set<String> kindFilter = allowedEdgeKinds.isEmpty() ? Set.of() : Set.copyOf(allowedEdgeKinds);
        var frontier = new TreeMap<String, List<String>>();
        frontier.put(startId, List.of());

        for (int depth = 1; depth <= maxHops && !frontier.isEmpty(); depth++) {
            var nextFrontier = new TreeMap<String, List<String>>();
            for (var entry : frontier.entrySet()) {
                if (!execGuard.step()) {
                    return reached;
                }
                String curr = entry.getKey();
                List<String> prefixKinds = entry.getValue();
                for (var adj : adjacency.getOrDefault(curr, List.of())) {
                    if (!kindFilter.isEmpty() && !kindFilter.contains(adj.edgeKind())) {
                        continue;
                    }
                    if (!execGuard.step()) {
                        return reached;
                    }
                    String next = adj.neighborId();
                    if (next.equals(startId)) {
                        continue;
                    }
                    var nextKinds = new ArrayList<String>(prefixKinds.size() + 1);
                    nextKinds.addAll(prefixKinds);
                    nextKinds.add(adj.edgeKind());
                    List<String> frozenKinds = List.copyOf(nextKinds);
                    nextFrontier.putIfAbsent(next, frozenKinds);
                    if (depth >= minHops && targetFilter.contains(next) && !reached.containsKey(next)) {
                        List<String> orientedKinds = frozenKinds;
                        if (reversePathKinds && frozenKinds.size() > 1) {
                            var rev = new ArrayList<>(frozenKinds);
                            Collections.reverse(rev);
                            orientedKinds = List.copyOf(rev);
                        }
                        reached.put(next, new HopWitness(depth, orientedKinds));
                    }
                }
            }
            frontier = nextFrontier;
        }
        return reached;
    }

    private static boolean matchesNodePattern(GraphComposeQuery.NodePattern np, ComposeNodeMeta meta) {
        if (np.kind().isPresent() && !np.kind().orElseThrow().equalsIgnoreCase(meta.kind())) {
            return false;
        }
        if (np.trustTier().isPresent() && np.trustTier().orElseThrow() != meta.trustTier()) {
            return false;
        }
        if (np.namePattern().isPresent()) {
            return GraphComposeQuery.matchesNamePattern(
                    np.namePattern().orElseThrow(), meta.fqn(), meta.simpleName(), meta.id());
        }
        return true;
    }

    private static boolean matchesAllFilters(
            List<GraphComposeQuery.FilterClause> filters,
            Map<GraphComposeQuery.FilterClause, Pattern> compiledRegexes,
            ComposeNodeMeta meta) {
        for (var fc : filters) {
            String fieldVal = meta.fieldValue(fc.field());
            switch (fc.op()) {
                case EXISTS -> {
                    boolean wantPresent = fc.value().map(v -> !"false".equalsIgnoreCase(v)).orElse(true);
                    boolean present = fieldVal != null && !fieldVal.isBlank();
                    if (wantPresent != present) {
                        return false;
                    }
                }
                case EQ -> {
                    if (fieldVal == null || !fieldVal.equals(fc.value().orElseThrow())) {
                        return false;
                    }
                }
                case NEQ -> {
                    if (fieldVal != null && fieldVal.equals(fc.value().orElseThrow())) {
                        return false;
                    }
                }
                case CONTAINS -> {
                    if (fieldVal == null || !fieldVal.toLowerCase(Locale.ROOT)
                            .contains(fc.value().orElseThrow().toLowerCase(Locale.ROOT))) {
                        return false;
                    }
                }
                case PREFIX -> {
                    if (fieldVal == null || !fieldVal.startsWith(fc.value().orElseThrow())) {
                        return false;
                    }
                }
                case REGEX_BOUNDED -> {
                    Pattern pat = compiledRegexes.get(fc);
                    if (fieldVal == null || pat == null || !GraphComposeQuery.boundedRegexMatch(pat, fieldVal)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static BigDecimal scoreComposeMatch(
            GraphComposeQuery query,
            Map<String, GraphComposeQuery.NodePattern> patternByAlias,
            Map<String, ComposeNodeMeta> bindings,
            List<GraphComposeQuery.MatchedEdge> matchedEdges) {
        double trustSum = 0.0;
        double nameBonus = 0.0;
        double spanBonus = 0.0;
        for (var e : bindings.entrySet()) {
            ComposeNodeMeta meta = e.getValue();
            trustSum += switch (meta.trustTier()) {
                case COMPILER -> 1.00;
                case SPRING_SAT -> 0.95;
                case SOURCE_SYNTAX -> 0.80;
                case ADVISORY_UNVERIFIED -> 0.50;
            };
            var np = patternByAlias.get(e.getKey());
            if (np != null && np.namePattern().isPresent()) {
                String pat = np.namePattern().orElseThrow();
                if (pat.equalsIgnoreCase(meta.fqn()) || pat.equalsIgnoreCase(meta.simpleName())) {
                    nameBonus += 0.30;
                } else if (meta.fqn().toLowerCase(Locale.ROOT).startsWith(pat.toLowerCase(Locale.ROOT))
                        || meta.simpleName().toLowerCase(Locale.ROOT).startsWith(pat.toLowerCase(Locale.ROOT))) {
                    nameBonus += 0.15;
                } else {
                    nameBonus += 0.05;
                }
            }
            if (meta.sourcePath().isPresent() && meta.startLine() >= 1) {
                spanBonus += 0.10;
            }
        }
        int n = Math.max(1, bindings.size());
        double edgeBonus = 0.0;
        for (var me : matchedEdges) {
            edgeBonus += 0.20 / Math.max(1, me.hops());
        }
        ComposeNodeMeta anchor = bindings.get(query.matchNodes().getFirst().alias());
        double degreeTieBreak = anchor == null ? 0.0 : Math.min(0.10, Math.log1p(anchor.degree()) * 0.02);
        double total = (trustSum / n) + (nameBonus / n) + (spanBonus / n) + edgeBonus + degreeTieBreak;
        return BigDecimal.valueOf(total).setScale(4, RoundingMode.HALF_UP);
    }

    private Optional<ComposeDocMeta> extractComposeDocMeta(KnowledgeGraph targetGraph, KnowledgeGraph.Node node) {
        String path = null;
        String sha = null;
        for (var ref : node.evidence()) {
            var ev = targetGraph.evidence(ref).orElse(null);
            if (ev == null) continue;
            if ("source.document".equals(ev.family())) {
                var payload = parseEvidenceForCompose(targetGraph, ev);
                if (path == null && payload.get("path") instanceof String p) {
                    path = GraphComposeQuery.validateRelativeSourcePath(p).orElse(null);
                }
                if (sha == null) {
                    sha = firstNonBlank(optionalText(payload, "contentDigest"), optionalText(payload, "contentSha256"));
                }
            } else if ("source.bytes".equals(ev.family())) {
                var payload = parseEvidenceForCompose(targetGraph, ev);
                if (payload.get("document") instanceof Map<?, ?> docObj) {
                    var doc = GraphJson.object(docObj);
                    if (path == null && doc.get("path") instanceof String p) {
                        path = GraphComposeQuery.validateRelativeSourcePath(p).orElse(null);
                    }
                    if (sha == null) {
                        sha = firstNonBlank(optionalText(doc, "contentDigest"), optionalText(doc, "contentSha256"));
                    }
                }
            }
        }
        if (path == null && sha == null) {
            return Optional.empty();
        }
        return Optional.of(new ComposeDocMeta(path == null ? "" : path, sha == null ? "" : sha));
    }

    private ComposeNodeMeta extractComposeNodeMeta(
            KnowledgeGraph targetGraph,
            KnowledgeGraph.Node node,
            Map<String, ComposeDocMeta> docs,
            int degree) {
        String fqn = "";
        String simpleName = "";
        String sourcePath = "";
        String contentSha = "";
        int startLine = 0;
        int endLine = 0;
        String module = null;
        String origin = null;
        String sourceSet = null;
        TrustTier trustTier = inferNodeTrustTier(targetGraph, node);

        for (var ref : node.evidence()) {
            var ev = targetGraph.evidence(ref).orElse(null);
            if (ev == null) continue;
            var payload = parseEvidenceForCompose(targetGraph, ev);
            String family = ev.family();

            if ("java.declaration".equals(family)) {
                if (payload.get("entity") instanceof Map<?, ?> entObj) {
                    var entity = GraphJson.object(entObj);
                    String canonical = optionalText(entity, "canonicalName");
                    if (canonical != null && !canonical.isBlank() && fqn.isEmpty()) {
                        if ("PACKAGE".equals(node.kind()) && canonical.startsWith("java:v1:")) {
                            try {
                                fqn = qualifiedName(packageName(canonical));
                            } catch (IllegalArgumentException ignored) {
                                fqn = "";
                            }
                        } else {
                            fqn = qualifiedName(canonical);
                        }
                    }
                    if (origin == null) origin = optionalText(entity, "origin");
                    String scope = optionalText(entity, "stableScope");
                    if (module == null && scope != null && scope.startsWith("module:")) {
                        module = scope;
                    }
                    if (entity.get("declaration") instanceof Map<?, ?> spanObj) {
                        var span = GraphJson.object(spanObj);
                        String docId = optionalText(span, "document");
                        if (docId != null) {
                            var dm = docs.get(docId);
                            if (dm != null) {
                                if (sourcePath.isEmpty() && !dm.path().isEmpty()) sourcePath = dm.path();
                                if (contentSha.isEmpty() && !dm.sha256().isEmpty()) contentSha = dm.sha256();
                            }
                        }
                        if (startLine <= 0) startLine = optSpanInt(span, "startLine");
                        if (endLine <= 0) endLine = optSpanInt(span, "endLine");
                    }
                }
                String sp = optionalText(payload, "spelling");
                if (simpleName.isEmpty() && sp != null && safeSpelling(sp)) {
                    simpleName = sp;
                }
            } else if ("source.document".equals(family)) {
                var dm = docs.get(node.identity());
                if (dm != null) {
                    if (sourcePath.isEmpty()) sourcePath = dm.path();
                    if (contentSha.isEmpty()) contentSha = dm.sha256();
                } else {
                    String rawP = optionalText(payload, "path");
                    var validP = GraphComposeQuery.validateRelativeSourcePath(rawP);
                    if (validP.isPresent() && sourcePath.isEmpty()) sourcePath = validP.orElseThrow();
                    String dig = firstNonBlank(optionalText(payload, "contentDigest"), optionalText(payload, "contentSha256"));
                    if (dig != null && contentSha.isEmpty()) contentSha = dig;
                }
                if (fqn.isEmpty() && !sourcePath.isEmpty()) fqn = sourcePath;
                if (startLine <= 0 && !sourcePath.isEmpty()) {
                    startLine = 1;
                    endLine = 1;
                }
                if (module == null) module = optionalText(payload, "module");
                if (sourceSet == null) sourceSet = optionalText(payload, "classification");
            } else if ("source.bytes".equals(family)) {
                if (payload.get("document") instanceof Map<?, ?> docObj) {
                    var doc = GraphJson.object(docObj);
                    var validP = GraphComposeQuery.validateRelativeSourcePath(optionalText(doc, "path"));
                    if (validP.isPresent() && sourcePath.isEmpty()) sourcePath = validP.orElseThrow();
                    String dig = firstNonBlank(optionalText(doc, "contentDigest"), optionalText(doc, "contentSha256"));
                    if (dig != null && contentSha.isEmpty()) contentSha = dig;
                    if (fqn.isEmpty() && !sourcePath.isEmpty()) fqn = sourcePath;
                    if (module == null) module = optionalText(doc, "module");
                    if (sourceSet == null) sourceSet = optionalText(doc, "classification");
                }
            } else if ("module.descriptor".equals(family) || "build.module".equals(family)) {
                var desc = "build.module".equals(family) && payload.get("descriptor") instanceof Map<?, ?> d
                        ? GraphJson.object(d) : payload;
                if (module == null) module = optionalText(desc, "identity");
                if (origin == null) origin = "PROJECT";
                String rawModPath = optionalText(desc, "path");
                if (rawModPath != null && fqn.isEmpty()) {
                    try {
                        fqn = ContractChecks.modulePath(rawModPath);
                    } catch (IllegalArgumentException ignored) {
                        // Never leak host paths
                    }
                }
            } else if ("spring.endpoint-declaration".equals(family)) {
                if (payload.get("route") instanceof Map<?, ?> routeObj) {
                    var route = GraphJson.object(routeObj);
                    String routePath = optionalText(route, "path");
                    String methods = route.get("methods") instanceof List<?> ms
                            ? String.join(",", ms.stream().filter(String.class::isInstance).map(String.class::cast).toList())
                            : "";
                    if (routePath != null && !routePath.isBlank() && fqn.isEmpty()) {
                        fqn = methods.isEmpty() ? routePath : methods + " " + routePath;
                    }
                    if (route.get("span") instanceof Map<?, ?> spanObj) {
                        var span = GraphJson.object(spanObj);
                        String docId = optionalText(span, "document");
                        if (docId != null && docs.containsKey(docId)) {
                            var dm = docs.get(docId);
                            if (sourcePath.isEmpty()) sourcePath = dm.path();
                            if (contentSha.isEmpty()) contentSha = dm.sha256();
                        }
                        if (startLine <= 0) startLine = optSpanInt(span, "startLine");
                        if (endLine <= 0) endLine = optSpanInt(span, "endLine");
                    }
                }
                if (module == null) module = optionalText(payload, "module");
                if (sourceSet == null) sourceSet = optionalText(payload, "sourceSet");
                if (origin == null) origin = "PROJECT";
            } else if ("spring.candidate".equals(family)) {
                if (payload.get("declaredNameKey") instanceof Map<?, ?> nkObj) {
                    var nk = GraphJson.object(nkObj);
                    String primary = optionalText(nk, "primary");
                    if (primary != null && !primary.isBlank() && fqn.isEmpty()) {
                        fqn = primary;
                    }
                }
            } else if (family.startsWith("polyglot.")) {
                var fields = payload.get("fields") instanceof Map<?, ?> fm ? GraphJson.object(fm) : payload;
                String rawP = decodeSafePolyglotPath(fields.get("path"));
                if (rawP.isEmpty()) rawP = decodeSafePolyglotPath(fields.get("rawPath"));
                if (!rawP.isEmpty() && sourcePath.isEmpty()) sourcePath = rawP;
                String dig = firstNonBlank(optionalText(fields, "contentDigest"), optionalText(fields, "contentSha256"));
                if (dig != null && contentSha.isEmpty()) contentSha = dig;
                if (sourceSet == null) sourceSet = safePolyglotLabel(optionalText(fields, "language"));
                if (fields.get("fact") instanceof Map<?, ?> factObj) {
                    var fact = GraphJson.object(factObj);
                    String fname = safePolyglotLabel(optionalText(fact, "name"));
                    if (fact.get("attributes") instanceof Map<?, ?> attrObj) {
                        String qn = safePolyglotLabel(optionalText(GraphJson.object(attrObj), "qualifiedName"));
                        if (qn != null) fname = qn;
                    }
                    if (fname != null && fqn.isEmpty()) {
                        fqn = sourcePath.isEmpty() ? fname : sourcePath + "#" + fname;
                        simpleName = fname;
                    }
                    if (fact.get("span") instanceof Map<?, ?> spanObj) {
                        var span = GraphJson.object(spanObj);
                        if (startLine <= 0) startLine = optSpanInt(span, "startLine");
                        if (endLine <= 0) endLine = optSpanInt(span, "endLine");
                    }
                }
                if (origin == null) origin = "PROJECT";
            }

            // Generic span fallback (validating relative paths strictly — never leaking host paths!)
            if (payload.get("span") instanceof Map<?, ?> spanObj) {
                var span = GraphJson.object(spanObj);
                String docId = optionalText(span, "document");
                if (docId != null && docs.containsKey(docId)) {
                    var dm = docs.get(docId);
                    if (sourcePath.isEmpty()) sourcePath = dm.path();
                    if (contentSha.isEmpty()) contentSha = dm.sha256();
                }
                String spath = optionalText(span, "path");
                if (sourcePath.isEmpty() && spath != null) {
                    sourcePath = GraphComposeQuery.validateRelativeSourcePath(spath).orElse("");
                }
                if (startLine <= 0) startLine = optSpanInt(span, "startLine");
                if (endLine <= 0) endLine = optSpanInt(span, "endLine");
            }
            if (sourcePath.isEmpty()) {
                String spath = firstNonBlank(optionalText(payload, "sourcePath"), null);
                if (spath != null) {
                    sourcePath = GraphComposeQuery.validateRelativeSourcePath(spath).orElse("");
                }
            }
            if (contentSha.isEmpty()) {
                String dig = firstNonBlank(optionalText(payload, "contentSha256"), optionalText(payload, "contentDigest"));
                if (dig != null) contentSha = dig;
            }
            if (startLine <= 0) {
                int sl = optSpanInt(payload, "startLine");
                if (sl > 0) startLine = sl;
            }
            if (endLine <= 0) {
                int el = optSpanInt(payload, "endLine");
                if (el > 0) endLine = el;
            }
        }

        if (fqn.isEmpty()) {
            fqn = !sourcePath.isEmpty() ? sourcePath : node.identity();
        }
        if (simpleName.isEmpty()) {
            int hashIdx = fqn.lastIndexOf('#');
            int dotIdx = fqn.lastIndexOf('.');
            int slashIdx = fqn.lastIndexOf('/');
            int sep = Math.max(hashIdx, Math.max(dotIdx, slashIdx));
            simpleName = (sep >= 0 && sep + 1 < fqn.length()) ? fqn.substring(sep + 1) : fqn;
        }
        int finalStart = Math.max(0, startLine);
        int finalEnd = Math.max(finalStart, endLine);
        String shaPrefix = GraphComposeQuery.normalizeShaPrefix(
                !contentSha.isEmpty() ? contentSha : node.evidence().getFirst().value());
        Optional<String> validPath = sourcePath.isEmpty() ? Optional.empty() : Optional.of(sourcePath);
        String pointerRef = GraphComposeQuery.formatPointerRef(
                validPath, node.kind(), node.identity(), finalStart, finalEnd, shaPrefix);

        return new ComposeNodeMeta(
                node.identity(),
                node.context(),
                node.kind(),
                fqn,
                simpleName,
                trustTier,
                validPath,
                finalStart,
                finalEnd,
                shaPrefix,
                pointerRef,
                Optional.ofNullable(module),
                Optional.ofNullable(origin),
                Optional.ofNullable(sourceSet),
                degree);
    }

    private TrustTier inferNodeTrustTier(KnowledgeGraph targetGraph, KnowledgeGraph.Node node) {
        boolean hasSpring = false;
        boolean hasCompiler = false;
        boolean hasSyntax = false;
        boolean hasAdvisory = false;
        for (var ref : node.evidence()) {
            var ev = targetGraph.evidence(ref).orElse(null);
            if (ev == null) continue;
            String fam = ev.family();
            var payload = parseEvidenceForCompose(targetGraph, ev);
            String explicit = firstNonBlank(optionalText(payload, "trustTier"), optionalText(payload, "trust"));
            if (explicit != null) {
                try {
                    TrustTier parsed = TrustTier.valueOf(explicit.strip().toUpperCase(Locale.ROOT));
                    if (parsed == TrustTier.ADVISORY_UNVERIFIED) hasAdvisory = true;
                    else if (parsed == TrustTier.SPRING_SAT) hasSpring = true;
                    else if (parsed == TrustTier.COMPILER) hasCompiler = true;
                    else if (parsed == TrustTier.SOURCE_SYNTAX) hasSyntax = true;
                    continue;
                } catch (IllegalArgumentException ignored) {
                    // Ignore non-enum trust labels like "HIGH"/"MEDIUM"
                }
            }
            if (fam.startsWith("advisory.")) {
                hasAdvisory = true;
            } else if (fam.startsWith("spring.")) {
                hasSpring = true;
            } else if (fam.startsWith("polyglot.source-syntax") || fam.startsWith("polyglot.inventory")) {
                hasSyntax = true;
            } else if (fam.equals("java.declaration") || fam.equals("java.type-shape")
                    || fam.equals("java.member-shape") || fam.equals("java.occurrence")
                    || fam.equals("java.type-use") || fam.startsWith("scip.")) {
                hasCompiler = true;
            } else {
                hasSyntax = true;
            }
        }
        if (hasAdvisory && !hasCompiler && !hasSpring) return TrustTier.ADVISORY_UNVERIFIED;
        if (hasSpring) return TrustTier.SPRING_SAT;
        if (hasCompiler) return TrustTier.COMPILER;
        if (hasSyntax) return TrustTier.SOURCE_SYNTAX;
        return TrustTier.SOURCE_SYNTAX;
    }

    private Map<String, Object> parseEvidenceForCompose(KnowledgeGraph targetGraph, GraphEvidence ev) {
        if (targetGraph == this.graph) {
            return parsedEvidence(ev);
        }
        try {
            return Collections.unmodifiableMap(GraphJson.object(
                    GraphJson.read(ev.canonicalJson(), new GraphJson.Limits(8_000_000, 128, 200_000))));
        } catch (IllegalArgumentException malformed) {
            return Map.of();
        }
    }

    private static String decodeSafePolyglotPath(Object rawValue) {
        if (!(rawValue instanceof String text) || text.isBlank()) {
            return "";
        }
        try {
            var decoded = new com.evolution.analysis.acquisition.streaming.LogicalPath(text).utf8();
            if (decoded.isPresent()) {
                return GraphComposeQuery.validateRelativeSourcePath(decoded.orElseThrow()).orElse("");
            }
        } catch (IllegalArgumentException notBase64) {
            return GraphComposeQuery.validateRelativeSourcePath(text).orElse("");
        }
        return "";
    }

    private static int optSpanInt(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number n) {
            return Math.max(0, n.intValue());
        }
        return 0;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a.strip();
        if (b != null && !b.isBlank()) return b.strip();
        return null;
    }

    private static String stopReasonGapCode(StopReason reason) {
        return switch (reason) {
            case MEMORY -> "COMPOSE_QUERY_MEMORY_LIMIT";
            case TIME -> "COMPOSE_QUERY_TIME_LIMIT";
            case CANCELLED -> "COMPOSE_QUERY_CANCELLED";
            case STEPS -> "COMPOSE_QUERY_STATE_LIMIT";
            default -> "COMPOSE_QUERY_TRUNCATED";
        };
    }

    private static CapabilityGapRecord composeGap(
            KnowledgeGraph targetGraph,
            Optional<String> contextOpt,
            ContentDigest queryDigest,
            String reasonCode) {
        Optional<com.evolution.analysis.contract.identity.AnalysisIdentity> analysisId = contextOpt
                .flatMap(ctxId -> targetGraph.contexts().stream()
                        .filter(c -> c.identity().equals(ctxId))
                        .findFirst()
                        .flatMap(KnowledgeGraph.Context::analysis));
        var observation = ProviderObservationReference.create(
                VERSION, "query.compose", targetGraph.identity(), queryDigest);
        var subject = EvidenceSubject.observation(observation.identity());
        var requirement = new EvidenceRequirement(
                EvidenceRequirement.Kind.REPOSITORY_CONTENT,
                "query.compose-complete",
                List.of(subject),
                EvidenceRequirement.AuthorizationClass.PASSIVE,
                List.of("Narrow matchNodes/filters or rerun within bounded operational limits."));
        return CapabilityGapRecord.create(
                new VersionedIdentifier("evidence.query-gaps", "m5.2-v1"),
                new EvidenceContext(targetGraph.snapshot(), analysisId),
                VERSION,
                "query.compose",
                reasonCode,
                subject,
                List.of(),
                List.of(observation),
                List.of(requirement),
                List.of(),
                List.of(new AffectedOutput(AffectedOutput.Kind.GRAPH_PROJECTION, "query.compose")),
                List.of(),
                List.of(),
                List.of("Compositional query execution stopped early or was qualified; absence of rows is not proof of absent facts."));
    }

    public Result<EntityView> entities(Request request, String search, Page page) {
        Objects.requireNonNull(search);
        Objects.requireNonNull(page);
        return memoizedResult("entities", request, List.of(search, page), () -> {
            var p = new Prepared(request, false);
            String needle = search.toLowerCase(Locale.ROOT);
            var values = p.entities.values().stream().filter(e -> scopeIncludes(e, request.scope()))
                    .filter(e -> needle.isEmpty() || e.identity().equalsIgnoreCase(search)
                            || e.spelling().map(s -> containsIgnoreCase(s, needle)).orElse(false)
                            || e.canonicalName().map(s -> containsIgnoreCase(s, needle)).orElse(false)
                            || e.qualifiedName().map(s -> containsIgnoreCase(s, needle)).orElse(false))
                    .toList();
            return page(p, "entities", search, values, EntityView::identity, page);
        });
    }

    private static boolean containsIgnoreCase(String value, String lowerNeedle) {
        return value.toLowerCase(Locale.ROOT).contains(lowerNeedle);
    }

    private static boolean scopeIncludes(EntityView entity, Scope scope) {
        return scope == Scope.ENTITY
                || entity.kind().equals(scope == Scope.SPRING_COMPONENT ? "BEAN_DEFINITION_CANDIDATE" : scope.name());
    }

    public Result<EntityView> entity(Request request, String identity) {
        Objects.requireNonNull(identity);
        return memoizedResult("entity", request, identity, () -> {
            var p = new Prepared(request, false);
            var value = p.entities.get(identity);
            if (value == null)
                p.budget.qualify("ENTITY_UNAVAILABLE");
            return result(p, "entity", identity, value == null ? List.of() : List.of(value), Optional.empty(),
                    value == null ? Optional.empty() : Optional.of(1L));
        });
    }

    /**
     * Direct recorded containment only. Does not infer ownership from display names
     * or dependencies.
     */
    public Result<EntityView> children(Request request, String identity, Page page) {
        Objects.requireNonNull(identity);
        Objects.requireNonNull(page);
        return memoizedResult("children", request, List.of(identity, page), () -> {
            var p = new Prepared(request, false);
            if (!p.nodes.containsKey(identity) && p.budget.running())
                throw new IllegalArgumentException("Parent outside context");
            var children = new TreeMap<String, EntityView>();
            int count = 0;
            for (var edge : graph.edges(request.context())) {
                if (!p.budget.scan())
                    break;
                if (++count > request.limits().maxEdges()) {
                    p.budget.halt(StopReason.INPUT);
                    break;
                }
                if (!edge.source().equals(identity)
                        || !Set.of("graph.contains", "graph.declares", "graph.owns-source").contains(edge.kind()))
                    continue;
                if (edge.target().state() != KnowledgeGraph.TargetState.RESOLVED) {
                    p.budget.qualify("UNRESOLVED_CONTAINMENT");
                    continue;
                }
                for (var target : edge.target().identities()) {
                    var child = p.entities.get(target);
                    if (child != null && scopeIncludes(child, request.scope()) && p.budget.reserve(1))
                        children.put(target, child);
                }
            }
            return page(p, "children", identity, List.copyOf(children.values()), EntityView::identity, page);
        });
    }

    public Result<EdgeView> edges(Request request, Page page) {
        Objects.requireNonNull(page);
        return memoizedResult("edges", request, page, () -> {
            var p = new Prepared(request, true);
            return page(p, "edges", "",
                    p.edges.stream().map(e -> e.view).sorted(Comparator.comparing(EdgeView::identity)).toList(),
                    EdgeView::identity, page);
        });
    }

    public Result<EdgeView> dependencies(Request request, String source, Page page) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(page);
        return memoizedResult("dependencies", request, List.of(source, page), () -> {
            var p = new Prepared(request, true);
            requireEndpoint(p, source);
            return page(p, "dependencies", source, p.adjacency.getOrDefault(source, List.of()).stream().map(e -> e.view)
                    .sorted(Comparator.comparing(EdgeView::identity)).toList(), EdgeView::identity, page);
        });
    }

    /**
     * Raw denominator including candidate/unresolved rows; never promoted to a
     * definite projected edge.
     */
    public Result<RelationshipView> relationships(Request request, Page page) {
        Objects.requireNonNull(page);
        return memoizedResult("relationships", request, page, () -> {
            var p = new Prepared(request, false);
            var values = new ArrayList<RelationshipView>();
            int count = 0;
            for (var e : graph.edges(request.context()))
                if (request.relationshipKinds().contains(e.kind())) {
                    if (!p.budget.scan())
                        break;
                    if (++count > request.limits().maxEdges()) {
                        p.budget.halt(StopReason.INPUT);
                        break;
                    }
                    if (!p.entities.containsKey(e.source()))
                        continue;
                    values.add(new RelationshipView(e.identity(), e.source(), e.kind(), e.target().state().name(),
                            bounded(e.target().identities(), p.budget),
                            e.target().unresolvedReference().map(ContentDigest::sha256Utf8),
                            bounded(e.evidence(), p.budget), e.region()));
                }
            return page(p, "relationships", "", values, RelationshipView::identity, page);
        });
    }

    public Result<Neighborhood> expand(Request request, String source) {
        Objects.requireNonNull(source);
        return memoizedResult("expand", request, source, () -> {
            var p = new Prepared(request, true);
            requireEndpoint(p, source);
            var selected = new ArrayList<EdgeView>();
            var ids = new TreeSet<String>();
            ids.add(source);
            for (var e : p.adjacency.getOrDefault(source, List.of())) {
                if (selected.size() == request.limits().maxResults()
                        || !ids.contains(e.target) && ids.size() == request.limits().maxResults()) {
                    p.budget.frontier(e.target);
                    p.budget.halt(StopReason.OUTPUT);
                    break;
                }
                selected.add(e.view);
                ids.add(e.target);
            }
            return result(p, "expand", source,
                    List.of(new Neighborhood(ids.stream().map(p.entities::get).toList(), selected)), Optional.empty(),
                    Optional.empty());
        });
    }

    public Result<Summary> summary(Request request) {
        return memoizedResult("summary", request, "", () -> {
            var p = new Prepared(request, true);
            var n = new TreeMap<String, Long>();
            var e = new TreeMap<String, Long>();
            p.entities.values().forEach(v -> n.merge(v.kind(), 1L, Long::sum));
            p.edges.forEach(v -> v.kinds.forEach(k -> e.merge(k, 1L, Long::sum)));
            return result(p, "summary", "", List.of(new Summary(n, e, graph.contexts().size(), graph.identity(),
                    "Distinct context-qualified nodes; logical projected edges retain raw supports with explicit expansion limits. Counts are source dependencies, not runtime calls.")),
                    Optional.empty(), Optional.of(1L));
        });
    }

    public Result<MetricProjection> metricProjection(Request request) {
        return memoizedResult("metric-projection", request, "full-declared-scope", () -> {
            var p = new Prepared(request, true);
            var vertices = p.entities.values().stream().filter(v -> scopeIncludes(v, request.scope())).toList();
            var active = new TreeSet<String>();
            var unknown = new TreeSet<String>();
            if (p.catalog != null && request.scope() == Scope.SPRING_COMPONENT)
                for (var node : vertices) {
                    if (!p.budget.scan())
                        break;
                    var region = p.catalog.activate(node.identity());
                    // Candidate metadata alone is not proof of activation; a definition fact must
                    // have been supplied.
                    if (!p.catalog.activation.containsKey(node.identity())) {
                        unknown.add(node.identity());
                        p.budget.qualify("ACTIVATION_EVIDENCE_UNAVAILABLE");
                        continue;
                    }
                    var check = region.check(p.budget, request.realization());
                    var c = region.classify(p.budget);
                    if (check.truth() == Truth.UNKNOWN || c == Classification.UNKNOWN) {
                        unknown.add(node.identity());
                        p.budget.qualify("UNKNOWN_FEASIBILITY");
                    }
                    boolean selected = switch (request.projection()) {
                        case REALIZED -> p.catalog.realizationValid && check.truth() == Truth.SAT;
                        case MUST -> c == Classification.MUST;
                        case MAY -> c == Classification.MAY || request.includeMust() && c == Classification.MUST;
                        case BOUNDED_UNION -> check.truth() == Truth.SAT;
                        case UNKNOWN_OVERLAY -> false;
                    };
                    if (selected)
                        active.add(node.identity());
                }
            p.budget.reserve((long) vertices.size() + p.edges.size() + active.size() + unknown.size());
            return result(
                    p, "metric-projection", "full-declared-scope", List.of(new MetricProjection(vertices,
                            p.edges.stream().map(e -> e.view).toList(), List.copyOf(active), List.copyOf(unknown))),
                    Optional.empty(), Optional.empty());
        });
    }

    public Result<Path> findPaths(Request request, String source, String target) {
        return traverse(request, source, Optional.of(target), false);
    }

    public Result<Path> shortestPath(Request request, String source, String target) {
        return traverse(request, source, Optional.of(target), true);
    }

    /**
     * Use INCOMING for structural impact; each returned dependent has a jointly
     * feasible source path.
     */
    public Result<Path> reachability(Request request, String source) {
        return traverse(request, source, Optional.empty(), false);
    }

    public Result<Path> impact(Request request, String changed) {
        if (request.direction() != Direction.INCOMING)
            throw new IllegalArgumentException("Impact requires INCOMING dependencies");
        return reachability(request, changed);
    }

    private Result<Path> traverse(Request request, String source, Optional<String> target, boolean shortest) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(target);
        String op = shortest ? "shortest-path" : target.isPresent() ? "paths" : "reachability";
        return memoizedResult(op, request, List.of(source, target), () -> {
            var p = new Prepared(request, true);
            requireEndpoint(p, source);
            target.ifPresent(t -> requireEndpoint(p, t));
            var paths = BoundedGraphTraversal.paths(p, source, target, shortest);
            return result(p, op, List.of(source, target), paths, Optional.empty(), Optional.empty());
        });
    }

    public Result<Path> findCycles(Request request) {
        return memoizedResult("cycles", request, "", () -> {
            var p = new Prepared(request, true);
            var cycles = BoundedGraphTraversal.cycles(p);
            return result(p, "cycles", "", cycles, Optional.empty(),
                    p.budget.stop == StopReason.NONE && p.budget.unknown == 0 && p.budget.reasons.isEmpty()
                            ? Optional.of((long) cycles.size())
                            : Optional.empty());
        });
    }

    /**
     * Rule-selected induced edges; unknown cycles remain inspectable without a
     * fabricated witness.
     */
    public Result<Path> policyCycles(Request request, Set<String> eligibleEdges) {
        Objects.requireNonNull(eligibleEdges);
        if (eligibleEdges.size() > request.limits().maxEdges())
            throw new IllegalArgumentException("Induced edge budget exceeded");
        if (request.projection() != Projection.BOUNDED_UNION || request.direction() != Direction.OUTGOING)
            throw new IllegalArgumentException("Policy cycles require outgoing BOUNDED_UNION dependencies");
        var sortedEligible = new TreeSet<>(eligibleEdges);
        return memoizedResult("policy-cycles", request, List.of(ArchitectureCondition.RULE_VERSION, sortedEligible), () -> {
            var p = new Prepared(request, true);
            if (p.budget.reserve(sortedEligible.size())) {
                var missing = new HashSet<>(sortedEligible);
                p.edges.forEach(e -> missing.remove(e.view.identity()));
                if (!missing.isEmpty())
                    p.budget.qualify("PREDICATE_EDGE_UNAVAILABLE");
            }
            p.edges.removeIf(e -> !sortedEligible.contains(e.view.identity()));
            p.adjacency.values().forEach(v -> v.removeIf(e -> !sortedEligible.contains(e.view.identity())));
            var cycles = BoundedGraphTraversal.cycles(p, true);
            return result(p, "policy-cycles", List.of(ArchitectureCondition.RULE_VERSION, sortedEligible),
                    cycles, Optional.empty(),
                    p.budget.stop == StopReason.NONE && p.budget.unknown == 0 && p.budget.reasons.isEmpty()
                            ? Optional.of((long) cycles.size())
                            : Optional.empty());
        });
    }

    public Result<ArchitectureCondition.Evaluation> condition(Request request,
            ArchitectureCondition.Predicate predicate) {
        Objects.requireNonNull(predicate);
        if (request.projection() != Projection.BOUNDED_UNION || request.direction() != Direction.OUTGOING)
            throw new IllegalArgumentException("Condition composition requires outgoing BOUNDED_UNION dependencies");
        return memoizedResult("condition", request, List.of(ArchitectureCondition.VERSION, predicate), () -> {
            var p = new Prepared(request, true);
            var index = new HashMap<String, LogicalEdge>();
            if (p.budget.reserve(2L * p.edges.size()))
                p.edges.forEach(e -> index.put(e.view.identity(), e));
            QueryRegion union = null;
            var evidence = new TreeSet<ContentDigest>();
            var regions = new TreeSet<String>();
            for (var term : predicate.alternatives()) {
                QueryRegion joint = QueryRegion.universal();
                for (var id : term.edges()) {
                    if (!p.budget.step()) {
                        joint = QueryRegion.unknown();
                        break;
                    }
                    var edge = index.get(id);
                    if (edge == null) {
                        p.budget.qualify("PREDICATE_EDGE_UNAVAILABLE");
                        joint = joint.combine(QueryRegion.unknown(), true, p.budget);
                    } else {
                        joint = joint.combine(edge.region, true, p.budget);
                        if (p.budget.reserve((long) edge.evidence.size() + edge.regions.size())) {
                            evidence.addAll(edge.evidence);
                            regions.addAll(edge.regions);
                        }
                    }
                }
                union = union == null ? joint : union.combine(joint, false, p.budget);
            }
            if (!p.budget.running())
                union = QueryRegion.unknown();
            var classification = union.classify(p.budget);
            var yes = union.check(p.budget, Optional.empty());
            var no = union.negate().check(p.budget, Optional.empty());
            if (!p.budget.running())
                classification = Classification.UNKNOWN;
            if (classification == Classification.UNKNOWN)
                p.budget.qualify("PREDICATE_TRUTH_UNKNOWN");
            var regionId = ContentDigest.sha256Utf8(
                    CanonicalJson.write(List.of(ArchitectureCondition.VERSION, request.identity(), union.identity())));
            Function<Witness, Witness> bind = w -> new Witness(w.world(), w.assignment(), regionId, w.formulaReplayed(),
                    w.semanticReplayed());
            var value = new ArchitectureCondition.Evaluation(regionId, classification, yes.witness().map(bind),
                    no.witness().map(bind), bounded(evidence, p.budget), bounded(regions, p.budget));
            return result(p, "condition", List.of(ArchitectureCondition.VERSION, predicate), List.of(value),
                    Optional.empty(), Optional.of(1L));
        });
    }

    public Result<ArchitectureCondition.Evaluation> ruleCondition(Request request,
            ArchitectureCondition.RulePredicate predicate) {
        Objects.requireNonNull(predicate);
        if (request.projection() != Projection.BOUNDED_UNION || request.direction() != Direction.OUTGOING)
            throw new IllegalArgumentException("Rule composition requires outgoing BOUNDED_UNION dependencies");
        return memoizedResult("rule-condition", request, List.of(ArchitectureCondition.RULE_VERSION, predicate),
                () -> ruleCondition(prepareRuleRegion(request, predicate), request, predicate));
    }

    private Result<ArchitectureCondition.Evaluation> ruleCondition(RuleRegion region, Request request,
            ArchitectureCondition.RulePredicate predicate) {
        var p = region.prepared();
        var union = region.union();
        var classification = union.classify(p.budget);
        var yes = union.check(p.budget, Optional.empty());
        var no = union.negate().check(p.budget, Optional.empty());
        if (!p.budget.running())
            classification = Classification.UNKNOWN;
        if (classification == Classification.UNKNOWN)
            p.budget.qualify("PREDICATE_TRUTH_UNKNOWN");
        var regionId = ruleRegionIdentity(request, union);
        Function<Witness, Witness> bind = w -> new Witness(w.world(), w.assignment(), regionId, w.formulaReplayed(),
                w.semanticReplayed());
        var value = new ArchitectureCondition.Evaluation(regionId, classification, yes.witness().map(bind),
                no.witness().map(bind), bounded(region.evidence(), p.budget), bounded(region.regions(), p.budget));
        return result(p, "rule-condition", List.of(ArchitectureCondition.RULE_VERSION, predicate), List.of(value),
                Optional.empty(), Optional.of(1L));
    }

    /**
     * Exact truth operands of a canonical rule replay, for comparison on a
     * separately proved domain.
     */
    public record RuleTruth(Result<ArchitectureCondition.Evaluation> evaluation, String encoding, boolean closed,
            Set<String> trueWorlds, Set<String> possibleWorlds, Set<String> worlds,
            com.evolution.analysis.spring.truth.SymbolicConfiguration.Formula trueFormula,
            com.evolution.analysis.spring.truth.SymbolicConfiguration.Formula possibleFormula,
            com.evolution.analysis.spring.truth.SymbolicConfiguration.Formula universeFormula) {
    }

    public RuleTruth ruleTruth(Request request, ArchitectureCondition.RulePredicate predicate) {
        var prepared = prepareRuleRegion(request, predicate);
        var r = prepared.union();
        var evaluation = ruleCondition(prepared, request, predicate);
        return new RuleTruth(evaluation, r.encoding.name(), r.closed, r.yes, r.possible, r.universe,
                r.trueFormula, r.possibleFormula, r.universeFormula);
    }

    private record RuleRegion(Prepared prepared, QueryRegion union, SortedSet<ContentDigest> evidence,
            SortedSet<String> regions) {
    }

    private static ContentDigest ruleRegionIdentity(Request request, QueryRegion union) {
        return ContentDigest.sha256Utf8(
                CanonicalJson.write(List.of(ArchitectureCondition.RULE_VERSION, request.identity(), union.identity())));
    }

    private RuleRegion prepareRuleRegion(Request request, ArchitectureCondition.RulePredicate predicate) {
        Objects.requireNonNull(predicate);
        if (request.projection() != Projection.BOUNDED_UNION || request.direction() != Direction.OUTGOING)
            throw new IllegalArgumentException("Rule composition requires outgoing BOUNDED_UNION dependencies");
        var p = new Prepared(request, true);
        var index = new HashMap<String, LogicalEdge>();
        if (p.budget.reserve(2L * p.edges.size()))
            p.edges.forEach(e -> index.put(e.view.identity(), e));
        var universe = p.catalog == null ? QueryRegion.unknown() : p.catalog.universe();
        QueryRegion union = universe.negate();
        var evidence = new TreeSet<ContentDigest>();
        var regions = new TreeSet<String>();
        for (var alternative : predicate.alternatives()) {
            QueryRegion joint = universe;
            for (String id : alternative.term().edges()) {
                if (!p.budget.step()) {
                    joint = QueryRegion.unknown();
                    break;
                }
                var edge = index.get(id);
                if (edge == null) {
                    p.budget.qualify("PREDICATE_EDGE_UNAVAILABLE");
                    joint = QueryRegion.unknown();
                } else {
                    joint = joint.combine(edge.region, true, p.budget);
                    if (p.budget.reserve((long) edge.evidence.size() + edge.regions.size())) {
                        evidence.addAll(edge.evidence);
                        regions.addAll(edge.regions);
                    }
                }
            }
            if (!alternative.eligible()) {
                joint = joint.uncertain();
                p.budget.qualify("PREDICATE_ELIGIBILITY_UNKNOWN");
            }
            union = union.combine(joint, false, p.budget);
        }
        if (!predicate.complete()) {
            union = union.combine(universe.uncertain(), false, p.budget);
            p.budget.qualify("RULE_DENOMINATOR_INCOMPLETE");
        }
        if (!p.budget.running())
            union = QueryRegion.unknown();
        return new RuleRegion(p, union, evidence, regions);
    }

    public Result<WitnessReplay> replayRuleWitness(Request request, ArchitectureCondition.RulePredicate predicate,
            Witness witness, boolean counterWitness) {
        Objects.requireNonNull(witness);
        var region = prepareRuleRegion(request, predicate);
        var p = region.prepared();
        var union = region.union();
        var identity = ruleRegionIdentity(request, union);
        Truth actual = Truth.UNKNOWN;
        if (!identity.equals(witness.jointRegion()))
            p.budget.qualify("WITNESS_REGION_MISMATCH");
        else if (witness.world().isPresent() || !witness.assignment().isEmpty()) {
            var realization = new Realization(witness.world(), witness.assignment());
            actual = union.check(p.budget, Optional.of(realization)).truth();
        } else if (union.encoding == QueryRegion.Encoding.UNIVERSAL)
            actual = Truth.SAT;
        else if (union.encoding == QueryRegion.Encoding.SYMBOLIC) {
            // Empty assignments are legitimate for a constant structural predicate, never
            // for missing variables.
            try {
                actual = !union.universeFormula.evaluate(Map.of()) ? Truth.UNKNOWN
                        : union.trueFormula.evaluate(Map.of()) ? Truth.SAT
                                : union.possibleFormula.evaluate(Map.of()) ? Truth.UNKNOWN : Truth.UNSAT;
            } catch (NullPointerException missing) {
                p.budget.qualify("INCOMPLETE_REALIZATION");
            }
        } else
            p.budget.qualify("INCOMPLETE_REALIZATION");
        if (!p.budget.running())
            actual = Truth.UNKNOWN;
        Truth expected = counterWitness ? Truth.UNSAT : Truth.SAT;
        boolean matched = actual == expected;
        if (actual == Truth.UNKNOWN)
            p.budget.qualify("WITNESS_REPLAY_UNKNOWN");
        var replay = new WitnessReplay(identity, witness, expected, actual, matched, actual != Truth.UNKNOWN, false);
        return result(p, "rule-witness-replay", List.of("m8.3-v1", predicate, witness, counterWitness), List.of(replay),
                Optional.empty(), Optional.of(1L));
    }

    /**
     * Finite recorded worlds and solver-validated examples; never enumerates the
     * Cartesian space.
     */
    public Result<RealizationView> realizations(Request request, Page page) {
        Objects.requireNonNull(page);
        return memoizedResult("realizations", request, page, () -> {
            var p = new Prepared(request, true);
            var values = new TreeMap<String, RealizationView>();
            if (p.catalog != null) {
                for (var region : new TreeMap<>(p.catalog.regions).values()) {
                    if (!p.budget.step())
                        break;
                    if (region.encoding == QueryRegion.Encoding.EXPLICIT)
                        for (String world : new TreeSet<>(region.universe)) {
                            if (values.containsKey(world))
                                continue;
                            if (!p.budget.scan() || !p.budget.reserve(1))
                                break;
                            var node = p.nodes.get(world);
                            if (node != null)
                                values.put(world,
                                        new RealizationView(world, "RECORDED_WORLD",
                                                new Realization(Optional.of(world), Map.of()),
                                                profileBooleans(node, p.budget), node.evidence()));
                        }
                    if (region.encoding == QueryRegion.Encoding.SYMBOLIC)
                        for (var candidate : List.of(region.check(p.budget, Optional.empty()),
                                region.negate().check(p.budget, Optional.empty())))
                            candidate.witness().filter(w -> !w.assignment().isEmpty()).ifPresent(w -> {
                                var variables = new TreeSet<String>();
                                p.catalog.formulaNodes.values().forEach(f -> {
                                    if ("VARIABLE".equals(f.get("operator")))
                                        variables.add(GraphJson.string(f.get("variable")));
                                });
                                // Incomplete solver assignments are not offered as realized views.
                                if (w.assignment().keySet().equals(variables) && p.budget.reserve(1)) {
                                    String id = KnowledgeGraph.derivedIdentity("realization",
                                            List.of(request.context(), w.assignment()));
                                    values.put(id,
                                            new RealizationView(id, "MODELED_EXAMPLE",
                                                    new Realization(Optional.empty(), w.assignment()),
                                                    symbolicProfileBooleans(w.assignment(), p.budget), List.of()));
                                } else if (!w.assignment().keySet().equals(variables))
                                    p.budget.qualify("INCOMPLETE_REALIZATION_EXAMPLE");
                            });
                }
            }
            return page(p, "realizations", "recorded-and-modeled-examples", List.copyOf(values.values()),
                    RealizationView::identity, page);
        });
    }

    /**
     * Only profile Boolean settings are displayable; property names/values and
     * source choices stay private.
     */
    private Map<String, Boolean> profileBooleans(KnowledgeGraph.Node node, QueryBudget budget) {
        var profiles = new TreeMap<String, Boolean>();
        var conflicting = new HashSet<String>();
        for (var reference : node.evidence()) {
            var evidence = graph.evidence(reference).orElseThrow();
            if (!evidence.family().equals("spring.world"))
                continue;
            var payload = read(evidence, budget);
            if (!(payload.get("assignment") instanceof Map<?, ?> assignment))
                continue;
            var baseline = assignment.get("baseline");
            if (!(baseline instanceof List<?> rows))
                continue;
            for (var row : rows) {
                if (!budget.scan() || !budget.reserve(2))
                    break;
                Map<?, ?> variable, value;
                if (row instanceof List<?> pair && pair.size() == 2 && pair.get(0) instanceof Map<?, ?> key
                        && pair.get(1) instanceof Map<?, ?> val) {
                    variable = key;
                    value = val;
                } else if (row instanceof Map<?, ?> entry && entry.get("variable") instanceof Map<?, ?> key
                        && entry.get("value") instanceof Map<?, ?> val) {
                    variable = key;
                    value = val;
                } else {
                    budget.qualify("INVALID_WORLD_EVIDENCE");
                    continue;
                }
                if ("PROFILE".equals(variable.get("kind")) && "BOOLEAN".equals(value.get("kind"))
                        && variable.get("name") instanceof String name && value.get("bool") instanceof Boolean flag) {
                    if (profiles.size() == budget.limits.maxResults()) {
                        budget.qualify("EVIDENCE_DESCRIPTOR_LIMIT");
                        break;
                    }
                    if (name.isBlank() || name.length() > 256
                            || name.chars().anyMatch(c -> Character.isISOControl(c))) {
                        budget.qualify("PROFILE_LABEL_UNAVAILABLE");
                        continue;
                    }
                    if (conflicting.contains(name))
                        continue;
                    var previous = profiles.putIfAbsent(name, flag);
                    if (previous != null && !previous.equals(flag)) {
                        profiles.remove(name);
                        conflicting.add(name);
                        budget.qualify("PROFILE_LABEL_CONFLICT");
                    }
                }
            }
        }
        return profiles;
    }

    private Map<String, Boolean> symbolicProfileBooleans(Map<String, Boolean> assignment, QueryBudget budget) {
        var profiles = new TreeMap<String, Boolean>();
        var conflicting = new HashSet<String>();
        for (var evidence : graph.evidence())
            if (evidence.family().equals("spring.space")) {
                if (!budget.scan())
                    break;
                var space = read(evidence, budget);
                if (!(space.get("domains") instanceof List<?> domains))
                    continue;
                for (var row : domains) {
                    if (!budget.scan() || !budget.reserve(2))
                        break;
                    if (!(row instanceof Map<?, ?> domain) || !(domain.get("variable") instanceof Map<?, ?> variable)
                            || !"PROFILE".equals(variable.get("kind")) || !(variable.get("name") instanceof String name)
                            || !(domain.get("values") instanceof List<?> values))
                        continue;
                    for (var raw : values)
                        if (raw instanceof Map<?, ?> value && "BOOLEAN".equals(value.get("kind"))
                                && value.get("bool") instanceof Boolean flag) {
                            // Exact upstream baseline-choice preimage from SymbolicTruthRegions.selection;
                            // no name-based inference.
                            if (name.isBlank() || name.length() > 256
                                    || name.chars().anyMatch(c -> Character.isISOControl(c))) {
                                budget.qualify("PROFILE_LABEL_UNAVAILABLE");
                                continue;
                            }
                            String key = com.evolution.analysis.spring.truth.SymbolicTruthRegions
                                    .baselineChoiceVariable(
                                            new com.evolution.analysis.spring.condition.FiniteDomain.Variable(
                                                    com.evolution.analysis.spring.condition.FiniteDomain.Kind.PROFILE,
                                                    name),
                                            com.evolution.analysis.spring.condition.FiniteDomain.Value.bool(flag));
                            if (!Boolean.TRUE.equals(assignment.get(key)))
                                continue;
                            if (conflicting.contains(name))
                                continue;
                            if (!profiles.containsKey(name) && profiles.size() == budget.limits.maxResults()) {
                                budget.qualify("EVIDENCE_DESCRIPTOR_LIMIT");
                                break;
                            }
                            var previous = profiles.putIfAbsent(name, flag);
                            if (previous != null && !previous.equals(flag)) {
                                profiles.remove(name);
                                conflicting.add(name);
                                budget.qualify("PROFILE_LABEL_CONFLICT");
                            }
                        }
                }
            }
        return profiles;
    }

    public Result<EvidenceView> evidence(Request request, ContentDigest reference) {
        Objects.requireNonNull(reference);
        return memoizedResult("evidence", request, reference, () -> {
            var p = new Prepared(request, false);
            boolean allowed = p.nodes.values().stream().anyMatch(n -> n.evidence().contains(reference))
                    || graph.edges().stream()
                            .anyMatch(e -> e.context().equals(request.context()) && e.evidence().contains(reference))
                    || graph.contexts().stream()
                            .anyMatch(c -> c.identity().equals(request.context()) && c.evidence().equals(reference));
            var evidence = allowed ? graph.evidence(reference) : Optional.<GraphEvidence>empty();
            if (evidence.isEmpty())
                p.budget.qualify("EVIDENCE_UNAVAILABLE");
            return result(p, "evidence", reference, evidence.stream().map(e -> describeEvidence(e, p.budget)).toList(),
                    Optional.empty(), Optional.empty());
        });
    }

    public Result<EvidenceView> explain(Request request, String identity, Page page) {
        Objects.requireNonNull(identity);
        Objects.requireNonNull(page);
        return memoizedResult("explain", request, List.of(identity, page), () -> {
            var p = new Prepared(request, false);
            var node = p.nodes.get(identity);
            var refs = new TreeSet<ContentDigest>();
            if (node != null && p.budget.reserve(node.evidence().size()))
                refs.addAll(node.evidence());
            for (var edge : graph.edges())
                if (edge.context().equals(request.context()) && edge.identity().equals(identity)
                        && p.budget.reserve(edge.evidence().size()))
                    refs.addAll(edge.evidence());
            if (refs.isEmpty())
                p.budget.qualify("EVIDENCE_UNAVAILABLE");
            return evidencePage(p, "explain", identity, refs.stream().map(r -> graph.evidence(r).orElseThrow()).toList(),
                    page);
        });
    }

    public Result<EntityView> gaps(Request request, Page page) {
        return gaps(request, GapView.CURRENT, page);
    }

    public Result<EntityView> gaps(Request request, GapView view, Page page) {
        Objects.requireNonNull(view);
        var kinds = switch (view) {
            case CURRENT -> Set.of("CAPABILITY_GAP", "ACQUISITION_ATTEMPT", "PROVIDER_CONFLICT");
            case HISTORY -> Set.of("HISTORICAL_CAPABILITY_GAP");
            case ALL ->
                Set.of("CAPABILITY_GAP", "HISTORICAL_CAPABILITY_GAP", "ACQUISITION_ATTEMPT", "PROVIDER_CONFLICT");
        };
        return inventory(request, kinds, page, "gaps");
    }

    public Result<EntityView> spring(Request request, Set<String> kinds, Page page) {
        Objects.requireNonNull(kinds);
        Objects.requireNonNull(page);
        if (!Set.of("BEAN_PRODUCER", "BEAN_DEFINITION_CANDIDATE", "INJECTION_POINT", "INJECTION_USE",
                "REGISTRATION_EVENT", "REGISTRATION_TRANSITION", "ENDPOINT").containsAll(kinds) || kinds.isEmpty())
            throw new IllegalArgumentException("Explicit Spring inventory kinds required");
        var sortedKinds = new TreeSet<>(kinds);
        return memoizedResult("spring", request, List.of(sortedKinds, page), () -> {
            var p = new Prepared(request, false);
            if (sortedKinds.contains("ENDPOINT") && p.entities.values().stream().noneMatch(n -> n.kind().equals("ENDPOINT")))
                p.budget.qualify("ENDPOINT_COVERAGE_UNAVAILABLE");
            return page(p, "spring", sortedKinds,
                    p.entities.values().stream().filter(n -> sortedKinds.contains(n.kind())).toList(), EntityView::identity,
                    page);
        });
    }

    public Result<EntityView> configurations(Request request, Page page) {
        return inventory(request,
                Set.of("CONFIGURATION_WORLD", "CONFIGURATION_WITNESS", "WITNESS_REPLAY", "CONDITIONAL_FACT",
                        "TRUTH_REGION", "CONFIGURATION_CONDITION", "CONDITION_EXPRESSION", "REGION_FORMULA"),
                page, "configuration");
    }

    private Result<EntityView> inventory(Request request, Set<String> kinds, Page page, String operation) {
        Objects.requireNonNull(page);
        var sortedKinds = new TreeSet<>(kinds);
        return memoizedResult(operation, request, List.of(sortedKinds, page), () -> {
            var p = new Prepared(request, false);
            return page(p, operation, sortedKinds,
                    p.entities.values().stream().filter(n -> sortedKinds.contains(n.kind())).toList(), EntityView::identity,
                    page);
        });
    }

    public Result<EvidenceView> provenance(Request request) {
        return memoizedResult("provenance", request, "", () -> {
            var p = new Prepared(request, false, true);
            var context = graph.contexts().stream().filter(c -> c.identity().equals(request.context())).findFirst()
                    .orElseThrow();
            return result(p, "provenance", "",
                    List.of(describeEvidence(graph.evidence(context.evidence()).orElseThrow(), p.budget)), Optional.empty(),
                    Optional.of(1L));
        });
    }

    /**
     * Graph-level provenance catalog: no cross-context arithmetic. Useful for
     * modeled-space/parameter descriptors.
     */
    public Result<EvidenceView> artifacts(Request request, Set<String> families, Page page) {
        Objects.requireNonNull(families);
        Objects.requireNonNull(page);
        if (families.isEmpty())
            throw new IllegalArgumentException("Explicit evidence families required");
        var sortedFamilies = new TreeSet<>(families);
        return memoizedResult("artifacts", request, List.of(sortedFamilies, page), () -> {
            var p = new Prepared(request, false);
            var values = new ArrayList<GraphEvidence>();
            for (var evidence : graph.evidence())
                if (sortedFamilies.contains(evidence.family())) {
                    if (!p.budget.scan())
                        break;
                    if (!p.budget.reserve(1))
                        break;
                    values.add(evidence);
                }
            return evidencePage(p, "artifacts", sortedFamilies, values, page);
        });
    }

    private Result<EvidenceView> evidencePage(Prepared p, String operation, Object argument,
            List<GraphEvidence> references, Page page) {
        // Select at most a page of references before materializing/redacting any
        // payload descriptors.
        var selected = page(p, operation, argument, references, e -> e.identity().value(), page);
        var descriptors = selected.items().stream().map(e -> describeEvidence(e, p.budget)).toList();
        return result(p, operation,
                List.of(argument, "identity-ascending", Math.min(page.size(), p.request.limits().maxResults())),
                descriptors, selected.continuation(), selected.exactTotal());
    }

    public Result<String> metricCatalog(Request request, Page page) {
        validate(request);
        if (metrics.isPresent())
            return metrics.orElseThrow().catalog(request, page);
        var p = new Prepared(request, false);
        p.budget.qualify("METRICS_PROVIDER_UNAVAILABLE");
        return result(p, "metric-catalog", "", List.of(), Optional.empty(), Optional.empty());
    }

    public Result<Map<String, Object>> metricDefinition(Request request, String metric) {
        var p = new Prepared(request, false, true);
        var definition = metrics.flatMap(provider -> provider.definition(metric));
        if (definition.isEmpty())
            p.budget.qualify("METRIC_DEFINITION_UNAVAILABLE");
        return result(p, "metric-definition", metric, definition.stream().toList(), Optional.empty(), Optional.empty());
    }

    public Result<Map<String, Object>> metric(Request request, String metric, String subject) {
        validate(request);
        if (metrics.isPresent())
            return metrics.orElseThrow().evaluate(request, metric, subject);
        var p = new Prepared(request, false);
        p.budget.qualify("METRICS_PROVIDER_UNAVAILABLE");
        return result(p, "metric", List.of(metric, subject), List.of(), Optional.empty(), Optional.empty());
    }

    /**
     * Conservative preparation for M11, not a temporal diff or approval of an
     * evolution gate.
     */
    public Result<Compatibility> comparisonPreparation(Request request, KnowledgeGraph other, String otherContext) {
        Objects.requireNonNull(other);
        var p = new Prepared(request, false);
        var right = other.contexts().stream().filter(c -> c.identity().equals(otherContext)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Foreign comparison context"));
        var left = graph.contexts().stream().filter(c -> c.identity().equals(request.context())).findFirst()
                .orElseThrow();
        var reasons = new TreeSet<String>();
        var l = graph.evidence(left.evidence()).orElseThrow();
        var r = other.evidence(right.evidence()).orElseThrow();
        if (!l.family().equals(r.family()))
            reasons.add("CONTEXT_FAMILY_DRIFT");
        var leftPolicy = graphPolicy(graph, left, p.budget);
        var rightPolicy = graphPolicy(other, right, p.budget);
        if (!leftPolicy.equals(rightPolicy)) {
            reasons.add("BUILD_OR_PROVIDER_POLICY_DRIFT");
            if (leftPolicy instanceof Map<?, ?> lm && rightPolicy instanceof Map<?, ?> rm) {
                var keys = new TreeSet<String>();
                lm.keySet().forEach(k -> keys.add(k.toString()));
                rm.keySet().forEach(k -> keys.add(k.toString()));
                keys.stream().filter(k -> !Objects.equals(lm.get(k), rm.get(k)))
                        .forEach(k -> reasons.add("POLICY_FIELD_" + k));
            }
        }
        if (!formulaPolicy(graph, left).equals(formulaPolicy(other, right)))
            reasons.add("FORMULA_ENCODING_DRIFT");
        if (!Set.of("analysis.manifest", "spring.context").contains(l.family())
                || !Set.of("analysis.manifest", "spring.context").contains(r.family()))
            reasons.add("COMPATIBILITY_EVIDENCE_UNAVAILABLE");
        if (p.budget.stop != StopReason.NONE || p.budget.reasons.contains("EVIDENCE_DESCRIPTOR_LIMIT")
                || p.budget.reasons.contains("COMPATIBILITY_EVIDENCE_UNAVAILABLE"))
            reasons.add("INCOMPLETE_COMPATIBILITY_EVIDENCE");
        return result(p, "comparison-preparation", List.of(other.identity(), otherContext), List.of(
                new Compatibility(graph.identity().equals(other.identity()), reasons.isEmpty(), List.copyOf(reasons))),
                Optional.empty(), Optional.of(1L));
    }

    private Object graphPolicy(KnowledgeGraph value, KnowledgeGraph.Context context, QueryBudget budget) {
        var evidence = value.evidence(context.evidence()).orElseThrow();
        var payload = read(evidence, budget);
        if (evidence.family().equals("analysis.manifest")) {
            var policy = select(payload, Set.of("manifestVersion", "modules", "classpath", "configuration", "analyzer",
                    "ruleSet", "graphSchema"));
            if (payload.get("configuration") instanceof Map<?, ?>) {
                var configuration = GraphJson.object(payload.get("configuration"));
                var values = new TreeMap<>(GraphJson.object(configuration.get("values")));
                // These are exact-input bindings, not analysis policy. Ordered binary classpath
                // and module/source-set
                // policy remain compared independently. Snapshot/source edits are normal
                // evolution inputs.
                Set.of("java.source-plan", "java.source-decoding", "java.reactor-sources", "java.classpath-manifest",
                        "ingestion.source-input", "ingestion.build-model").forEach(values::remove);
                policy.put("configuration", Map.of("schema", configuration.get("schema"), "values", values));
            } else
                budget.qualify("COMPATIBILITY_EVIDENCE_UNAVAILABLE");
            return policy;
        }
        if (evidence.family().equals("spring.context")) {
            var build = Optional.ofNullable(payload.get("build")).filter(Map.class::isInstance).map(GraphJson::object)
                    .orElse(Map.of());
            var policy = new TreeMap<>(select(build, Set.of("moduleSourceSet", "platformViewIdentity")));
            policy.put("parameters", payload.get("parameters"));
            if (build.get("orderedResolutionInputs") instanceof List<?> ordered)
                policy.put("orderedResolutionInputs", ordered.stream().map(item -> {
                    var m = GraphJson.object(item);
                    return "REACTOR_SOURCE".equals(m.get("kind")) ? Map.of("kind", "REACTOR_SOURCE") : m;
                }).toList());
            var analysis = context.analysis()
                    .flatMap(id -> value.contexts().stream().filter(c -> c.identity().equals(id.value())).findFirst());
            if (analysis.isPresent() && value.evidence(analysis.orElseThrow().evidence()).orElseThrow().family()
                    .equals("analysis.manifest"))
                policy.put("analysisPolicy", graphPolicy(value, analysis.orElseThrow(), budget));
            else
                budget.qualify("COMPATIBILITY_EVIDENCE_UNAVAILABLE");
            return policy;
        }
        return Map.of("family", evidence.family());
    }

    private static Map<String, Object> select(Map<String, Object> input, Set<String> keys) {
        var value = new TreeMap<String, Object>();
        keys.forEach(k -> {
            if (input.containsKey(k))
                value.put(k, input.get(k));
        });
        return value;
    }

    private Set<String> formulaPolicy(KnowledgeGraph value, KnowledgeGraph.Context context) {
        var kinds = new TreeSet<String>();
        for (var node : value.nodes())
            if (node.context().equals(context.identity()) && node.kind().equals("TRUTH_REGION"))
                for (var ref : node.evidence()) {
                    String family = value.evidence(ref).orElseThrow().family();
                    if (family.equals("spring.explicit-region"))
                        kinds.add("explicit-worlds-m4d-v1");
                    else if (family.equals("spring.symbolic-region"))
                        kinds.add("symbolic-dag-m4u.2-v1");
                    else
                        kinds.add("unknown");
                }
        return kinds;
    }

    static void requireEndpoint(Prepared p, String identity) {
        if (!p.entities.containsKey(identity) && p.budget.stop == StopReason.NONE)
            throw new IllegalArgumentException("Endpoint outside query population/context");
    }

    private <T> Result<T> page(Prepared p, String operation, Object argument, List<T> values, Function<T, String> key,
            Page page) {
        int size = Math.min(page.size(), p.request.limits().maxResults());
        var binding = queryId(p.request, operation, List.of(argument, "identity-ascending", size));
        int start = 0;
        if (page.cursor().isPresent()) {
            try {
                String decoded = new String(Base64.getUrlDecoder().decode(page.cursor().orElseThrow()),
                        StandardCharsets.UTF_8);
                String prefix = binding.value() + "|";
                if (!decoded.startsWith(prefix))
                    throw new IllegalArgumentException("Foreign cursor");
                String last = decoded.substring(prefix.length());
                int found = -1;
                for (int i = 0; i < values.size(); i++)
                    if (key.apply(values.get(i)).equals(last)) {
                        found = i;
                        break;
                    }
                if (found < 0)
                    throw new IllegalArgumentException("Stale cursor");
                start = found + 1;
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("Invalid or stale query cursor");
            }
        }
        int end = Math.min(values.size(), start + size);
        var selected = values.subList(start, end);
        Optional<String> continuation = Optional.empty();
        if (end < values.size() && p.budget.stop == StopReason.NONE) {
            continuation = Optional.of(Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (binding.value() + "|" + key.apply(selected.getLast())).getBytes(StandardCharsets.UTF_8)));
            p.budget.halt(StopReason.OUTPUT);
        }
        return result(p, operation, List.of(argument, "identity-ascending", size), selected, continuation,
                p.budget.reasons.stream().allMatch(r -> r.equals("OUTPUT")) ? Optional.of((long) values.size())
                        : Optional.empty());
    }

    private <T> Result<T> result(Prepared p, String operation, Object argument, List<T> values,
            Optional<String> continuation, Optional<Long> total) {
        var query = queryId(p.request, operation, argument);
        var gaps = p.budget.reasons.stream().map(reason -> gap(p, query, reason)).toList();
        var evidence = new TreeSet<ContentDigest>();
        for (var node : p.nodes.values())
            if (node.kind().equals("CAPABILITY_GAP") || node.kind().equals("PROVIDER_CONFLICT"))
                evidence.addAll(node.evidence());
        for (var value : values) {
            if (value instanceof EntityView v)
                evidence.addAll(v.evidence());
            else if (value instanceof EdgeView v)
                evidence.addAll(v.evidence());
            else if (value instanceof EvidenceView v)
                evidence.add(v.identity());
            else if (value instanceof Path v)
                v.edges().forEach(e -> evidence.addAll(e.evidence()));
            else if (value instanceof RelationshipView v)
                evidence.addAll(v.evidence());
            else if (value instanceof RealizationView v)
                evidence.addAll(v.evidence());
            else if (value instanceof ArchitectureCondition.Evaluation v)
                evidence.addAll(v.evidence());
            else if (value instanceof Neighborhood v) {
                v.entities().forEach(e -> evidence.addAll(e.evidence()));
                v.edges().forEach(e -> evidence.addAll(e.evidence()));
            }
        }
        var boundedEvidence = bounded(evidence, p.budget);
        gaps = p.budget.reasons.stream().map(reason -> gap(p, query, reason)).toList();
        boolean partial = !gaps.isEmpty() || p.budget.stop != StopReason.NONE;
        return new Result<>(query, partial ? AssessmentStatus.PARTIAL : AssessmentStatus.COMPLETE,
                p.budget.stop == StopReason.CANCELLED ? Operation.CANCELLED
                        : p.budget.stop == StopReason.MEMORY || p.budget.stop == StopReason.TIME
                                ? Operation.RESOURCE_LIMIT
                                : Operation.COMPLETED,
                values, boundedEvidence, gaps, p.budget.bounds(values.size()), continuation,
                partial && p.budget.stop != StopReason.OUTPUT ? Optional.empty() : total);
    }

    static ContentDigest queryId(Request request, String operation, Object argument) {
        return ContentDigest.sha256Utf8(CanonicalJson.write(List.of(VERSION, request.identity(), operation, argument)));
    }

    private CapabilityGapRecord gap(Prepared p, ContentDigest query, String reason) {
        var context = graph.contexts().stream().filter(c -> c.identity().equals(p.request.context())).findFirst()
                .orElseThrow();
        var observation = ProviderObservationReference.create(VERSION, "query.execution", graphIdentity, query);
        var subject = EvidenceSubject.observation(observation.identity());
        var requirement = new EvidenceRequirement(EvidenceRequirement.Kind.REPOSITORY_CONTENT,
                "query.complete-evidence", List.of(subject), EvidenceRequirement.AuthorizationClass.PASSIVE,
                List.of("Supply compatible complete graph/region evidence or rerun with sufficient bounded query resources."));
        return CapabilityGapRecord.create(new VersionedIdentifier("evidence.query-gaps", "m5.2-v1"),
                new EvidenceContext(graph.snapshot(), context.analysis()), VERSION,
                "query.architecture", reason, subject, List.of(), List.of(observation), List.of(requirement), List.of(),
                List.of(new AffectedOutput(AffectedOutput.Kind.GRAPH_PROJECTION, "query.architecture")), List.of(),
                List.of(),
                List.of("The query is qualified; absence of returned items is not proof of absent architecture facts."));
    }

    private static <T> List<T> bounded(Collection<T> values, QueryBudget budget) {
        if (values.size() > budget.limits.maxResults())
            budget.qualify("EVIDENCE_DESCRIPTOR_LIMIT");
        return values.stream().limit(budget.limits.maxResults()).toList();
    }

    private static String optionalText(Map<String, Object> value, String key) {
        return value.get(key) instanceof String text ? text : null;
    }

    private EntityView describe(KnowledgeGraph.Node node, Map<String, DocumentPopulation> documents,
            Map<String, Object> partition, QueryBudget budget) {
        var name = Optional.<String>empty();
        var spelling = Optional.<String>empty();
        var module = Optional.<String>empty();
        var origin = Optional.<String>empty();
        var sourceSet = Optional.<String>empty();
        var endpointRefPayloads = new ArrayList<Map<String, Object>>();
        for (var ref : node.evidence()) {
            var evidence = graph.evidence(ref).orElseThrow();
            if (evidence.family().equals("architecture.service-endpoint-reference")) {
                endpointRefPayloads.add(read(evidence, budget));
                continue;
            }
            var extracted = extractDescription(node, evidence, documents, budget, name, spelling, module, origin,
                    sourceSet);
            name = extracted.name();
            spelling = extracted.spelling();
            module = extracted.module();
            origin = extracted.origin();
            sourceSet = extracted.sourceSet();
        }
        for (var refPayload : endpointRefPayloads) {
            if (name.isEmpty() && refPayload.get("sourceEvidence") instanceof List<?> sourceRefs) {
                for (var rawRef : sourceRefs) {
                    if (!(rawRef instanceof String digestStr))
                        continue;
                    try {
                        var referenced = graph.evidence(new ContentDigest(digestStr));
                        if (referenced.isPresent()
                                && !referenced.get().family().equals("architecture.service-endpoint-reference")) {
                            var extracted = extractDescription(node, referenced.get(), documents, budget, name,
                                    spelling, module, origin, sourceSet);
                            name = extracted.name();
                            spelling = extracted.spelling();
                            module = extracted.module();
                            origin = extracted.origin();
                            sourceSet = extracted.sourceSet();
                        }
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            String role = optionalText(refPayload, "role");
            String sourceKind = optionalText(refPayload, "sourceKind");
            String sourceIdentity = optionalText(refPayload, "sourceIdentity");
            String sourceContext = optionalText(refPayload, "sourceContext");
            if (name.isEmpty()) {
                String fallback = safePolyglotLabel(sourceIdentity);
                if (fallback != null)
                    name = Optional.of(fallback);
            }
            if (spelling.isEmpty()) {
                String prefix = role != null && !role.isBlank() ? role.trim() : "";
                String kindPart = sourceKind != null && !sourceKind.isBlank() ? sourceKind.trim() : "";
                String label = name.map(ArchitectureQueryService::qualifiedName).orElse(kindPart);
                String combined = prefix.isEmpty() ? label : (label.isEmpty() ? prefix : prefix + " " + label);
                String safe = safePolyglotLabel(combined);
                if (safe != null)
                    spelling = Optional.of(safe);
            }
            if (module.isEmpty() && sourceContext != null && !sourceContext.isBlank()) {
                String safeCtx = safePolyglotLabel(sourceContext);
                if (safeCtx != null)
                    module = Optional.of(safeCtx);
            }
            if (origin.isEmpty())
                origin = Optional.of("PROJECT");
        }
        if (sourceSet.isEmpty())
            sourceSet = Optional.ofNullable((String) partition.get("sourceSet"));
        if (module.isEmpty())
            module = Optional.ofNullable((String) partition.get("module"));
        return new EntityView(node.identity(), node.context(), node.kind(), name,
                name.map(ArchitectureQueryService::qualifiedName), spelling, module, origin, sourceSet,
                bounded(node.evidence(), budget));
    }

    private record DescribedFields(Optional<String> name, Optional<String> spelling, Optional<String> module,
            Optional<String> origin, Optional<String> sourceSet) {
    }

    private DescribedFields extractDescription(KnowledgeGraph.Node node, GraphEvidence evidence,
            Map<String, DocumentPopulation> documents, QueryBudget budget,
            Optional<String> name, Optional<String> spelling, Optional<String> module, Optional<String> origin,
            Optional<String> sourceSet) {
        if (evidence.family().equals("java.declaration")) {
            var payload = read(evidence, budget);
            if (payload.get("entity") instanceof Map<?, ?>) {
                var entity = GraphJson.object(payload.get("entity"));
                name = Optional.ofNullable((String) entity.get("canonicalName"));
                spelling = Optional.ofNullable((String) payload.get("spelling"))
                        .filter(ArchitectureQueryService::safeSpelling);
                origin = Optional.ofNullable((String) entity.get("origin"));
                String scope = (String) entity.get("stableScope");
                if (scope != null && scope.startsWith("module:"))
                    module = Optional.of(scope);
                if (entity.get("declaration") instanceof Map<?, ?> span) {
                    var document = documents.get(span.get("document"));
                    if (document != null)
                        sourceSet = Optional.ofNullable(document.sourceSet());
                }
                if (node.kind().equals("PACKAGE") && name.isPresent() && name.orElseThrow().startsWith("java:v1:")) {
                    try {
                        name = Optional.of(packageName(name.orElseThrow()));
                        spelling = name.map(ArchitectureQueryService::qualifiedName);
                    } catch (IllegalArgumentException malformed) {
                        name = Optional.empty();
                        spelling = Optional.empty();
                        budget.qualify("INVALID_CANONICAL_NAME");
                    }
                }
            }
        }
        if (evidence.family().equals("source.document")) {
            var document = documents.get(node.identity());
            if (document != null) {
                module = Optional.ofNullable(document.module());
                sourceSet = Optional.ofNullable(document.sourceSet());
            }
            name = sourcePath(read(evidence, budget), budget);
            spelling = name;
        }
        if (evidence.family().equals("source.bytes")) {
            var payload = read(evidence, budget);
            if (payload.get("document") instanceof Map<?, ?> document) {
                var descriptor = GraphJson.object(document);
                name = sourcePath(descriptor, budget);
                spelling = name;
                module = Optional.ofNullable((String) descriptor.get("module"));
                sourceSet = Optional.ofNullable((String) descriptor.get("classification"));
                origin = Optional.of("PROJECT");
            }
        }
        if (evidence.family().equals("module.descriptor") || evidence.family().equals("build.module")) {
            var payload = read(evidence, budget);
            var descriptor = evidence.family().equals("build.module") ? GraphJson.object(payload.get("descriptor"))
                    : payload;
            module = Optional.ofNullable((String) descriptor.get("identity"));
            origin = Optional.of("PROJECT");
            if (node.kind().equals("MODULE") && descriptor.get("path") instanceof String path)
                try {
                    name = Optional.of(ContractChecks.modulePath(path));
                } catch (IllegalArgumentException invalid) {
                    budget.qualify("INVALID_MODULE_PATH");
                }
            if (node.kind().equals("SOURCE_ROOT"))
                for (var root : sourceRoots(payload, budget))
                    if (root.identity().equals(node.identity())) {
                        name = Optional.of(root.path());
                        sourceSet = Optional.of(root.sourceSet());
                    }
            spelling = name;
        }
        if (evidence.family().equals("spring.candidate")) {
            var payload = read(evidence, budget);
            if (payload.get("declaredNameKey") instanceof Map<?, ?> names) {
                var declared = GraphJson.object(names);
                if ("EXACT".equals(declared.get("status")) && declared.get("primary") instanceof String primary) {
                    name = Optional.of(primary);
                    spelling = name;
                }
            }
        }
        if (evidence.family().equals("spring.endpoint-declaration")) {
            var payload = read(evidence, budget);
            var route = GraphJson.object(payload.get("route"));
            String label = String.join(",",
                    GraphJson.array(route.get("methods")).stream().map(GraphJson::string).toList()) + " "
                    + GraphJson.string(route.get("path"));
            if (label.length() <= 16512 && label.chars().noneMatch(Character::isISOControl)) {
                name = Optional.of(label);
                spelling = name;
            } else
                budget.qualify("ENDPOINT_LABEL_UNAVAILABLE");
            module = Optional.of(GraphJson.string(payload.get("module")));
            sourceSet = Optional.of(GraphJson.string(payload.get("sourceSet")));
            origin = Optional.of("PROJECT");
        }
        if (evidence.family().equals("java.http-client-call") && name.isEmpty()) {
            var payload = read(evidence, budget);
            if (payload.get("call") instanceof Map<?, ?> callObj) {
                var call = GraphJson.object(callObj);
                String uri = optionalText(call, "uri");
                String methods = call.get("methods") instanceof List<?> list
                        ? String.join(",",
                                list.stream().filter(String.class::isInstance).map(String.class::cast).toList())
                        : "";
                String combined = methods.isEmpty() ? (uri == null ? "" : uri)
                        : (uri == null ? methods : methods + " " + uri);
                String safe = safePolyglotLabel(combined);
                if (safe != null) {
                    name = Optional.of(safe);
                    spelling = Optional.of(safe);
                }
                if (origin.isEmpty())
                    origin = Optional.of("PROJECT");
            }
        }
        if (evidence.family().equals("polyglot.source-syntax")) {
            var payload = read(evidence, budget);
            var fields = payload.get("fields") instanceof Map<?, ?> f ? GraphJson.object(f) : payload;
            var decodedPath = decodePolyglotPath(fields.get("path"), budget);
            String language = safePolyglotLabel(optionalText(fields, "language"));
            String rowType = optionalText(fields, "rowType");
            if ("DOCUMENT".equals(rowType)) {
                if (decodedPath.isPresent()) {
                    name = decodedPath;
                    spelling = Optional
                            .of(language != null ? decodedPath.get() + " [" + language + "]" : decodedPath.get());
                    if (module.isEmpty())
                        module = Optional.of(moduleFromPath(decodedPath.get()));
                }
                if (sourceSet.isEmpty() && language != null)
                    sourceSet = Optional.of(language);
                if (origin.isEmpty())
                    origin = Optional.of("PROJECT");
            } else if (fields.get("fact") instanceof Map<?, ?> factObj) {
                var fact = GraphJson.object(factObj);
                String category = optionalText(fact, "category");
                String rawName = optionalText(fact, "name");
                String written = optionalText(fact, "written");
                var attrs = fact.get("attributes") instanceof Map<?, ?> a ? GraphJson.object(a)
                        : Map.<String, Object>of();
                String qualifiedAttr = optionalText(attrs, "qualifiedName");
                String httpMethod = optionalText(attrs, "method");
                String label = null;
                if (qualifiedAttr != null && !qualifiedAttr.isBlank()) {
                    label = qualifiedAttr.trim();
                } else if (rawName != null && !rawName.isBlank()) {
                    String cleanName = unquoteLiteral(rawName.trim());
                    if (("ROUTE_CANDIDATE".equals(category) || "HTTP_CALL_CANDIDATE".equals(category))
                            && httpMethod != null && !httpMethod.isBlank() && !"UNKNOWN".equalsIgnoreCase(httpMethod)) {
                        label = httpMethod.trim().toUpperCase(Locale.ROOT) + " " + cleanName;
                    } else {
                        label = cleanName;
                    }
                } else if (written != null && !written.isBlank()) {
                    String compact = written.replaceAll("\\s+", " ").trim();
                    label = compact.length() > 160 ? compact.substring(0, 160) : compact;
                }
                String safeLabel = safePolyglotLabel(label);
                if (safeLabel != null) {
                    String displayName = decodedPath.isPresent() && !decodedPath.get().equals(safeLabel)
                            ? decodedPath.get() + "#" + safeLabel
                            : safeLabel;
                    name = Optional.of(displayName);
                    spelling = Optional.of(language != null ? safeLabel + " [" + language + "]" : safeLabel);
                } else if (decodedPath.isPresent()) {
                    name = decodedPath;
                    spelling = Optional
                            .of(language != null ? decodedPath.get() + " [" + language + "]" : decodedPath.get());
                }
                if (module.isEmpty() && decodedPath.isPresent())
                    module = Optional.of(moduleFromPath(decodedPath.get()));
                if (sourceSet.isEmpty() && language != null)
                    sourceSet = Optional.of(language);
                if (origin.isEmpty())
                    origin = Optional.of("PROJECT");
            }
        }
        if (evidence.family().equals("polyglot.inventory-entry")) {
            var payload = read(evidence, budget);
            var fields = payload.get("fields") instanceof Map<?, ?> f ? GraphJson.object(f) : payload;
            var decodedPath = decodePolyglotPath(fields.get("rawPath"), budget);
            String language = null;
            if (fields.get("languageCandidates") instanceof List<?> langs && !langs.isEmpty()
                    && langs.getFirst() instanceof String first) {
                language = safePolyglotLabel(first);
            }
            if (decodedPath.isPresent()) {
                name = decodedPath;
                spelling = Optional
                        .of(language != null ? decodedPath.get() + " [" + language + "]" : decodedPath.get());
                if (module.isEmpty())
                    module = Optional.of(moduleFromPath(decodedPath.get()));
            }
            if (sourceSet.isEmpty()) {
                if (language != null)
                    sourceSet = Optional.of(language);
                else
                    sourceSet = Optional.ofNullable(safePolyglotLabel(optionalText(fields, "population")));
            }
            if (origin.isEmpty()) {
                String reason = optionalText(fields, "reason");
                origin = Optional.of(
                        com.evolution.analysis.polyglot.PolyglotInventory.INSTALLED_DEPENDENCY_DEFERRED.equals(reason)
                                ? "DEPENDENCY"
                                : "PROJECT");
            }
        }
        if (evidence.family().equals("polyglot.build-component")) {
            var payload = read(evidence, budget);
            String directory = optionalText(payload, "directory");
            String compId = optionalText(payload, "id");
            String candidate = directory != null && !directory.isBlank() && !".".equals(directory.strip())
                    ? directory : compId;
            String safeLabel = safePolyglotLabel(candidate);
            if (safeLabel != null && name.isEmpty()) {
                name = Optional.of(safeLabel);
                spelling = Optional.of(safeLabel);
            }
            if (module.isEmpty() && directory != null && !directory.isBlank()) {
                String safeDir = safePolyglotLabel(directory);
                if (safeDir != null)
                    module = Optional.of(safeDir);
            }
            if (origin.isEmpty())
                origin = Optional.of("PROJECT");
        }
        if (evidence.family().equals("architecture.service-identity")) {
            var payload = read(evidence, budget);
            String safeLabel = safePolyglotLabel(optionalText(payload, "serviceName"));
            if (safeLabel == null) {
                safeLabel = safePolyglotLabel(optionalText(payload, "name"));
            }
            if (safeLabel != null) {
                name = Optional.of(safeLabel);
                spelling = Optional.of(safeLabel);
            }
            if (module.isEmpty()) {
                String safeDir = safePolyglotLabel(optionalText(payload, "directory"));
                if (safeDir != null)
                    module = Optional.of(safeDir);
            }
            if (origin.isEmpty())
                origin = Optional.of("PROJECT");
        }
        if (evidence.family().equals("architecture.infra-component")) {
            var payload = read(evidence, budget);
            String safeLabel = safePolyglotLabel(optionalText(payload, "name"));
            if (safeLabel != null && name.isEmpty()) {
                name = Optional.of(safeLabel);
                spelling = Optional.of(safeLabel);
            }
            if (origin.isEmpty())
                origin = Optional.of("INFRA");
        }
        if (evidence.family().equals("architecture.external-actor")) {
            var payload = read(evidence, budget);
            String safeLabel = safePolyglotLabel(optionalText(payload, "name"));
            if (safeLabel != null && name.isEmpty()) {
                name = Optional.of(safeLabel);
                spelling = Optional.of(safeLabel);
            }
            if (origin.isEmpty())
                origin = Optional.of("EXTERNAL");
        }
        return new DescribedFields(name, spelling, module, origin, sourceSet);
    }

    private static Optional<String> decodePolyglotPath(Object rawValue, QueryBudget budget) {
        if (!(rawValue instanceof String text) || text.isBlank())
            return Optional.empty();
        try {
            var decoded = new com.evolution.analysis.acquisition.streaming.LogicalPath(text).utf8();
            if (decoded.isPresent()) {
                try {
                    return Optional.of(ContractChecks.repositoryRelativePath(decoded.get(), "polyglot path"));
                } catch (IllegalArgumentException invalid) {
                    budget.qualify("INVALID_SOURCE_PATH");
                    return Optional.empty();
                }
            }
            return Optional.empty();
        } catch (IllegalArgumentException notBase64) {
            try {
                return Optional.of(ContractChecks.repositoryRelativePath(text, "polyglot path"));
            } catch (IllegalArgumentException invalid) {
                budget.qualify("INVALID_SOURCE_PATH");
                return Optional.empty();
            }
        }
    }

    private static String safePolyglotLabel(String value) {
        if (value == null)
            return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 512 || trimmed.chars().anyMatch(Character::isISOControl))
            return null;
        return trimmed;
    }

    private static String unquoteLiteral(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0), last = value.charAt(value.length() - 1);
            if (first == last && (first == '\'' || first == '"' || first == '`'))
                return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    private static String moduleFromPath(String path) {
        int slash = path.indexOf('/');
        return slash > 0 ? path.substring(0, slash) : ".";
    }

    private static String packageName(String canonical) {
        var tuple = GraphJson.array(GraphJson.read(canonical.substring(8), new GraphJson.Limits(100_000, 64, 10_000)));
        for (int depth = 0; depth < 64; depth++) {
            if (tuple.isEmpty())
                throw new IllegalArgumentException("Empty symbol tuple");
            String kind = GraphJson.string(tuple.getFirst());
            if (kind.equals("type") || kind.equals("package")) {
                // Validate arity and package parts before projecting the owner package.
                label(tuple, depth);
                return "java:v1:" + CanonicalJson.write(List.of("package", tuple.get(1)));
            }
            if (tuple.size() < 2)
                throw new IllegalArgumentException("Missing type owner");
            tuple = GraphJson.array(tuple.get(1));
        }
        throw new IllegalArgumentException("Type owner depth limit");
    }

    /** Display only: structural M1 names remain canonical identity authority. */
    private static String qualifiedName(String canonical) {
        if (!canonical.startsWith("java:v1:"))
            return canonical;
        String cached = QUALIFIED_NAME_CACHE.get(canonical);
        if (cached != null)
            return cached;
        String computed;
        try {
            computed = label(
                    GraphJson.array(GraphJson.read(canonical.substring(8), new GraphJson.Limits(100_000, 64, 10_000))),
                    0);
        } catch (IllegalArgumentException unknown) {
            computed = canonical;
        }
        if (QUALIFIED_NAME_CACHE.size() < 65_536)
            QUALIFIED_NAME_CACHE.putIfAbsent(canonical, computed);
        return computed;
    }

    private static String label(List<Object> tuple, int depth) {
        if (depth > 64 || tuple.size() < 2)
            throw new IllegalArgumentException("Unknown symbol grammar");
        String kind = GraphJson.string(tuple.getFirst());
        if (kind.equals("package") || kind.equals("type")) {
            if (tuple.size() != (kind.equals("package") ? 2 : 3))
                throw new IllegalArgumentException("Invalid package/type tuple arity");
            String pkg = String.join(".", GraphJson.array(tuple.get(1)).stream().map(GraphJson::string)
                    .map(ArchitectureQueryService::decodeName).toList());
            return kind.equals("package") ? pkg
                    : (pkg.isEmpty() ? "" : pkg + ".") + decodeName(GraphJson.string(tuple.get(2)));
        }
        String owner = label(GraphJson.array(tuple.get(1)), depth + 1);
        if (Set.of("member-type", "method", "field", "record-component").contains(kind)) {
            if (tuple.size() < 3)
                throw new IllegalArgumentException("Missing member name");
            return owner + "." + decodeName(GraphJson.string(tuple.get(2)));
        }
        return owner;
    }

    private static String decodeName(String name) {
        var value = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) == '%' && i + 6 < name.length()) {
                value.appendCodePoint(Integer.parseInt(name.substring(i + 1, i + 7), 16));
                i += 6;
            } else
                value.append(name.charAt(i));
        }
        return value.toString();
    }

    private boolean included(EntityView e, Population p) {
        return (p.nodeKinds().isEmpty() || p.nodeKinds().contains(e.kind()))
                && (p.modules().isEmpty() || e.module().filter(p.modules()::contains).isPresent())
                && (p.origins().isEmpty() || e.origin().filter(p.origins()::contains).isPresent())
                && (p.sourceSets().isEmpty() || e.sourceSet().filter(p.sourceSets()::contains).isPresent());
    }

    private static boolean knownExcluded(EntityView e, Population p) {
        return e != null
                && (!p.modules().isEmpty() && e.module().isPresent() && !p.modules().contains(e.module().orElseThrow())
                        || !p.origins().isEmpty() && e.origin().isPresent()
                                && !p.origins().contains(e.origin().orElseThrow())
                        || !p.sourceSets().isEmpty() && e.sourceSet().isPresent()
                                && !p.sourceSets().contains(e.sourceSet().orElseThrow()));
    }

    private static Optional<String> sourcePath(Map<String, Object> payload, QueryBudget budget) {
        if (!(payload.get("path") instanceof String path))
            return Optional.empty();
        try {
            return Optional.of(
                    com.evolution.analysis.contract.common.ContractChecks.repositoryRelativePath(path, "source path"));
        } catch (IllegalArgumentException invalid) {
            budget.qualify("INVALID_SOURCE_PATH");
            return Optional.empty();
        }
    }

    private record SourceRootDescriptor(String identity, String path, String sourceSet) {
    }

    private List<SourceRootDescriptor> sourceRoots(Map<String, Object> payload, QueryBudget budget) {
        var values = new ArrayList<SourceRootDescriptor>();
        try {
            var descriptor = GraphJson.object(payload.get("descriptor"));
            var plan = GraphJson.object(payload.get("sourcePlan"));
            for (var setValue : GraphJson.array(plan.get("sourceSets"))) {
                var set = GraphJson.object(setValue);
                String kind = GraphJson.string(set.get("kind")), name = GraphJson.string(set.get("name"));
                for (var rootValue : GraphJson.array(set.get("sourceRoots"))) {
                    if (!budget.scan() || !budget.reserve(4))
                        return values;
                    var root = GraphJson.object(rootValue);
                    if (!(root.get("value") instanceof String path))
                        continue;
                    String safe = ContractChecks.modulePath(path);
                    values.add(new SourceRootDescriptor(KnowledgeGraph.derivedIdentity("source-root",
                            List.of(descriptor.get("identity"), kind, name, path)), safe, kind));
                }
            }
        } catch (IllegalArgumentException | NullPointerException malformed) {
            budget.qualify("SOURCE_ROOT_METADATA_UNAVAILABLE");
        }
        return values;
    }

    private Map<String, Object> read(GraphEvidence evidence, QueryBudget budget) {
        String raw = evidence.canonicalJson();
        if (raw.length() <= 16_384 && budget.limits.maxAuxiliaryStates() > raw.length()) {
            var cached = parsedEvidenceCache.get(evidence.identity());
            if (cached != null)
                return cached;
            try {
                var parsed = Collections.unmodifiableMap(GraphJson.object(
                        GraphJson.read(raw, new GraphJson.Limits(8_000_000, 128, budget.limits.maxAuxiliaryStates()))));
                if (parsedEvidenceCache.size() < 65_536)
                    parsedEvidenceCache.putIfAbsent(evidence.identity(), parsed);
                return parsed;
            } catch (IllegalArgumentException malformed) {
                budget.qualify("EVIDENCE_DESCRIPTOR_LIMIT");
                return Map.of();
            }
        }
        try {
            return GraphJson.object(
                    GraphJson.read(raw, new GraphJson.Limits(8_000_000, 128, budget.limits.maxAuxiliaryStates())));
        } catch (IllegalArgumentException malformed) {
            budget.qualify("EVIDENCE_DESCRIPTOR_LIMIT");
            return Map.of();
        }
    }

    private static final Set<String> SAFE_FIELDS = Set.of("identity", "kind", "entity", "canonicalName", "spelling",
            "origin", "stableScope", "module", "classification", "document", "span", "sourceSpan", "sourceSpans",
            "startOffset", "endOffset", "startLine", "endLine", "startColumn", "endColumn", "ordinal", "status",
            "derivation", "derivationKind", "provider", "version", "id", "inputs", "relationship", "source", "target",
            "candidates",
            "fact", "definition", "injectionPoint", "bindingOwner", "factSemanticsVersion", "trueWorlds", "falseWorlds",
            "unknownWorlds", "trueRegion", "falseRegion", "unknownRegion", "encoding", "space", "evaluationInput",
            "semanticsContext",
            "reasonCode", "mechanismCategory", "schemaVersion", "detectingProvider", "observationReferences",
            "acquisitionAttemptReferences", "affectedOutputs", "gapIdentity", "outcome", "permissionDecision",
            "trustDecision",
            "factRegionIdentity", "legalOrderIdentity", "expectedTruth", "witnessPolicyVersion", "witness", "replayed",
            "operator", "children", "analysis", "planIdentity", "parameters", "truthLimits", "reasonerPolicy",
            "evaluatorArtifact", "maxSignatures", "symbolicLimits",
            "schema", "contentIdentity", "semanticIdentity", "contexts", "nodes", "edges", "evidencePayloads",
            "snapshotIdentity", "analysisIdentity", "requirement", "attempt", "conflict", "rootIdentity",
            "producer", "producerKind", "buildContextIdentity", "declaredNameKey", "primary", "aliases", "exposedTypes",
            "declarationEvidenceKey", "method", "owner", "point", "site", "type", "qualifiers",
            "actualTruth", "assignmentFeasibility", "valid", "independentEvaluationDigest", "branchEvidence",
            "baselineConfigurationIdentity", "feasibility", "enumerationComplete", "feasibleWorlds",
            "unknownFeasibilityWorlds", "declaredAssignments",
            "operationalStatus", "exogenousEvaluationIdentity", "bindingResultIdentity", "facts", "truth",
            "abstractionVersion", "feasibilityPolicy", "precedencePolicy", "profilePolicy",
            "discoveryEvent", "operation", "readerDecision", "gateExpectation", "gate", "alias", "eventIdentity",
            "expressionIdentity", "role", "evidence",
            "declaration", "manifest", "manifestVersion", "modules", "configuration", "analyzer", "ruleSet",
            "graphSchema", "classpath", "contentDigest", "artifactDigest", "entryDigest", "rawDigest", "build",
            "moduleSourceSet", "sourceSet",
            "maxDepth", "maxSteps", "maxNodes", "maxClauses", "maxVariables", "maxFacts", "maxRegionCells",
            "maxWitnessReplays", "maxCartesianAssignments", "maxExpressionVisits", "maxExpressionDepth",
            "gap", "requirements", "active", "denominator", "counts", "ledger", "pending", "narrowed", "satisfied",
            "notApplicable", "conflicted", "reopened");
    private static final Set<String> SAFE_FAMILIES = Set.of("analysis.manifest", "source.request", "source.document",
            "java.declaration", "java.occurrence", "java.type-use", "java.type-shape", "java.member-shape",
            "java.annotation-use", "java.source-outcome",
            "evidence.gap", "evidence.attempt", "evidence.conflict", "evidence.resolution", "spring.context",
            "spring.producer", "spring.candidate", "spring.injection-dependency", "spring.registration-event",
            "spring.registration-step",
            "spring.condition", "spring.condition-expression", "spring.space", "spring.evaluation-parameters",
            "spring.world", "spring.explicit-region", "spring.symbolic-region", "spring.fact-key", "spring.witness",
            "spring.witness-replay", "spring.formula",
            "java.evidence.gap-disposition", "java.evidence.gap-closure");

    private EvidenceView describeEvidence(GraphEvidence evidence, QueryBudget budget) {
        if (evidence.family().equals("java.evidence.repository-coverage")) {
            var payload = read(evidence, budget);
            var metadata = new TreeMap<>(select(payload, Set.of("plan", "readiness", "policy", "implementation",
                    "semanticAccuracy", "rootCausePopulation")));
            if (payload.get("counts") instanceof Map<?, ?> counts)
                metadata.put("counts",
                        select(GraphJson.object(counts),
                                Set.of("total", "PENDING", "COMPLETE", "PARTIAL", "FAILED", "UNSELECTED",
                                        "readinessRequirements", "readinessActive", "rootCauseBefore",
                                        "rootCauseAfter")));
            if (payload.get("rootCauseDelta") instanceof List<?> delta) {
                if (delta.size() > budget.limits.maxResults())
                    budget.qualify("EVIDENCE_DESCRIPTOR_LIMIT");
                metadata.put("rootCauseDelta",
                        delta.stream().limit(budget.limits.maxResults()).map(GraphJson::object)
                                .map(row -> select(row, Set.of("catalog", "provider", "reasonCode", "before", "after")))
                                .toList());
            }
            return new EvidenceView(evidence.identity(), evidence.family(), true, metadata);
        }
        if (evidence.family().equals("java.evidence.context-result")) {
            var payload = read(evidence, budget);
            var metadata = new TreeMap<>(select(payload, Set.of("context")));
            if (payload.get("result") instanceof Map<?, ?> result)
                metadata.put("result", select(GraphJson.object(result),
                        Set.of("sourceSet", "variant", "state", "graph", "snapshot", "activeGaps", "reason")));
            return new EvidenceView(evidence.identity(), evidence.family(), true, metadata);
        }
        if (evidence.family().equals("java.evidence.semantic-projection")) {
            return new EvidenceView(evidence.identity(), evidence.family(), true, select(read(evidence, budget),
                    Set.of("version", "component", "semanticIdentity", "auditIdentity", "scope", "inputIdentity",
                            "state")));
        }
        if (evidence.family().equals("java.evidence.readiness-coverage")) {
            var payload = read(evidence, budget);
            var metadata = new TreeMap<String, Object>();
            metadata.putAll(select(payload, Set.of("readiness", "selection", "policy", "semanticCoverage")));
            if (payload.get("counts") instanceof Map<?, ?> counts)
                metadata.put("counts",
                        select(GraphJson.object(counts),
                                Set.of("total", "active", "rootCount", "moduleCount", "contextCount",
                                        "selectedContextCount", "frontierCount", "PENDING", "SATISFIED",
                                        "NOT_APPLICABLE", "CONFLICTED", "REOPENED")));
            return new EvidenceView(evidence.identity(), evidence.family(), true, metadata);
        }
        if (evidence.family().equals("java.evidence.gap-closure")) {
            var payload = read(evidence, budget);
            var metadata = new TreeMap<String, Object>();
            metadata.putAll(select(payload, Set.of("denominator", "active", "historyGapCount", "activeHistoryGapCount",
                    "population", "ledger", "policy")));
            if (payload.get("scope") instanceof Map<?, ?> scope)
                metadata.put("scope", select(GraphJson.object(scope),
                        Set.of("baseSnapshot", "context", "logicalScope", "module", "sourceSet", "variant")));
            if (payload.get("rootCauseDelta") instanceof List<?> delta) {
                if (delta.size() > budget.limits.maxResults())
                    budget.qualify("EVIDENCE_DESCRIPTOR_LIMIT");
                metadata.put("rootCauseDelta", delta.stream().limit(budget.limits.maxResults()).map(GraphJson::object)
                        .map(row -> select(row, Set.of("catalog", "provider", "reasonCode", "before", "after")))
                        .toList());
            } else
                budget.qualify("GAP_DELTA_UNAVAILABLE");
            return new EvidenceView(evidence.identity(), evidence.family(), true, metadata);
        }
        if (evidence.family().equals("java.evidence.correspondence-endpoint")) {
            var payload = read(evidence, budget);
            return new EvidenceView(evidence.identity(), evidence.family(), true,
                    select(payload, Set.of("slot", "package", "owners", "kind", "name", "descriptor")));
        }
        if (evidence.family().equals("java.evidence.source-map")) {
            var payload = read(evidence, budget);
            var metadata = new TreeMap<String, Object>();
            metadata.putAll(select(payload, Set.of("projectedDocument", "projectedPath", "role", "lineage")));
            if (payload.get("content") instanceof Map<?, ?> content)
                metadata.put("content", select(GraphJson.object(content), Set.of("digest", "size")));
            if (payload.get("locator") instanceof Map<?, ?> value) {
                var locator = GraphJson.object(value);
                if (locator.get("path") instanceof Map<?, ?> path) {
                    try {
                        var raw = new com.evolution.analysis.acquisition.streaming.LogicalPath(
                                GraphJson.string(GraphJson.object(path).get("rawBase64")));
                        metadata.put("originalPathBytes", raw.rawBase64());
                        raw.utf8().ifPresent(name -> {
                            try {
                                metadata.put("originalPath",
                                        ContractChecks.repositoryRelativePath(name, "generated path"));
                            } catch (IllegalArgumentException nonportable) {
                                /* Raw logical name remains available. */}
                        });
                    } catch (IllegalArgumentException invalid) {
                        budget.qualify("GENERATED_SOURCE_MAP_UNAVAILABLE");
                    }
                }
                metadata.put("capturedLocator", ContentDigest.sha256Utf8(CanonicalJson.write(locator)));
            }
            return new EvidenceView(evidence.identity(), evidence.family(), true, metadata);
        }
        if (evidence.family().equals("java.evidence.view")) {
            var payload = read(evidence, budget);
            var metadata = new TreeMap<String, Object>();
            metadata.putAll(select(payload, Set.of("baseInventory", "derivedSnapshot", "overlay", "resolution",
                    "sourceMap", "correspondence", "policy")));
            if (payload.get("scope") instanceof Map<?, ?> scope)
                metadata.put("scope", select(GraphJson.object(scope),
                        Set.of("baseSnapshot", "context", "logicalScope", "module", "sourceSet", "variant")));
            return new EvidenceView(evidence.identity(), evidence.family(), true, metadata);
        }
        if (evidence.family().equals("java.evidence.binary-origin")) {
            var payload = read(evidence, budget);
            var metadata = new TreeMap<String, Object>();
            metadata.putAll(select(payload, Set.of("slot")));
            if (payload.get("proof") instanceof Map<?, ?> proof)
                metadata.put("proof", select(GraphJson.object(proof), Set.of("resolution", "shape", "context")));
            if (payload.get("original") instanceof Map<?, ?> original)
                metadata.put("original", select(GraphJson.object(original), Set.of("kind", "contentDigest")));
            return new EvidenceView(evidence.identity(), evidence.family(), true, metadata);
        }
        if (evidence.family().equals("source.bytes")) {
            var payload = read(evidence, budget);
            var metadata = new TreeMap<String, Object>();
            if (payload.get("document") instanceof Map<?, ?> document) {
                var descriptor = GraphJson.object(document);
                sourcePath(descriptor, budget).ifPresent(path -> metadata.put("path", path));
                metadata.putAll(select(descriptor, Set.of("identity", "module", "contentDigest", "classification")));
            }
            return new EvidenceView(evidence.identity(), evidence.family(), true, metadata);
        }
        if (evidence.family().equals("build.module")) {
            var payload = read(evidence, budget);
            var metadata = new TreeMap<String, Object>();
            if (payload.get("descriptor") instanceof Map<?, ?> descriptor) {
                var module = GraphJson.object(descriptor);
                metadata.putAll(select(module, Set.of("identity")));
                if (module.get("path") instanceof String path)
                    try {
                        metadata.put("modulePath", ContractChecks.modulePath(path));
                    } catch (IllegalArgumentException invalid) {
                        budget.qualify("INVALID_MODULE_PATH");
                    }
            }
            var roots = sourceRoots(payload, budget);
            if (roots.size() > budget.limits.maxResults())
                budget.qualify("EVIDENCE_DESCRIPTOR_LIMIT");
            metadata.put("sourceRoots", roots.stream().limit(budget.limits.maxResults()).map(
                    root -> Map.of("identity", root.identity(), "path", root.path(), "sourceSet", root.sourceSet()))
                    .toList());
            return new EvidenceView(evidence.identity(), evidence.family(), true, metadata);
        }
        if (evidence.family().equals("spring.endpoint-declaration")) {
            var payload = read(evidence, budget);
            var metadata = new TreeMap<>(
                    select(payload, Set.of("schema", "scope", "activation", "runtimeObserved", "module", "sourceSet")));
            metadata.put("route", select(GraphJson.object(payload.get("route")),
                    Set.of("controller", "handler", "path", "methods", "implicitHead", "evidence")));
            return new EvidenceView(evidence.identity(), evidence.family(), true, metadata);
        }
        if (evidence.family().equals("polyglot.build-component")) {
            return new EvidenceView(evidence.identity(), evidence.family(), true,
                    select(read(evidence, budget), Set.of("id", "directory", "kind", "tool", "name")));
        }
        if (evidence.family().equals("architecture.service-identity")) {
            return new EvidenceView(evidence.identity(), evidence.family(), true,
                    select(read(evidence, budget),
                            Set.of("serviceName", "name", "directory", "spec", "trust", "runtimeObserved",
                                    "evidenceLayers", "hasDockerfile", "componentRole", "isTestOnlyModule")));
        }
        if (evidence.family().equals("architecture.component-classification")) {
            return new EvidenceView(evidence.identity(), evidence.family(), true,
                    select(read(evidence, budget),
                            Set.of("role", "isTestOnlyModule", "directory", "packaging", "evidenceLayers",
                                    "trust", "runtimeObserved")));
        }
        if (evidence.family().equals("architecture.infra-component")) {
            return new EvidenceView(evidence.identity(), evidence.family(), true,
                    select(read(evidence, budget), Set.of("name", "image", "spec", "trust", "runtimeObserved",
                            "evidenceLayers")));
        }
        if (evidence.family().equals("architecture.external-actor")) {
            return new EvidenceView(evidence.identity(), evidence.family(), true,
                    select(read(evidence, budget), Set.of("name", "trust", "rule", "runtimeObserved")));
        }
        if (evidence.family().equals("architecture.declarative-topology")) {
            return new EvidenceView(evidence.identity(), evidence.family(), true,
                    select(read(evidence, budget),
                            Set.of("kind", "mechanism", "source", "target", "trust", "runtimeObserved")));
        }
        if (evidence.family().equals("architecture.service-call")) {
            var payload = read(evidence, budget);
            var metadata = new TreeMap<>(
                    select(payload, Set.of("kind", "certainty", "routingEstablished", "runtimeObserved")));
            if (payload.get("source") instanceof Map<?, ?> src) {
                metadata.put("source", select(GraphJson.object(src), Set.of("context", "identity", "role")));
            }
            if (payload.get("destinations") instanceof List<?> dests) {
                metadata.put("destinations", dests.stream().limit(budget.limits.maxResults())
                        .filter(Map.class::isInstance)
                        .map(d -> select(GraphJson.object(d), Set.of("context", "identity", "role")))
                        .toList());
            }
            return new EvidenceView(evidence.identity(), evidence.family(), true, metadata);
        }
        if (evidence.family().equals("polyglot.event-broker-link")) {
            return new EvidenceView(evidence.identity(), evidence.family(), true,
                    select(read(evidence, budget),
                            Set.of("kind", "topic", "protocol", "site", "brokers", "certainty", "trust",
                                    "orientation", "runtimeObserved")));
        }
        if (evidence.family().equals("polyglot.event-topic-match")) {
            return new EvidenceView(evidence.identity(), evidence.family(), true,
                    select(read(evidence, budget),
                            Set.of("topic", "protocol", "producer", "consumers", "certainty", "runtimeObserved")));
        }
        if (!SAFE_FAMILIES.contains(evidence.family()))
            return new EvidenceView(evidence.identity(), evidence.family(), true, Map.of());
        var value = new TreeMap<>(read(evidence, budget));
        if (!evidence.family().equals("java.declaration"))
            value.remove("spelling");
        var metadata = new TreeMap<>(GraphJson.object(sanitize(value, 0, budget)));
        if (evidence.family().equals("source.document"))
            sourcePath(value, budget).ifPresent(path -> metadata.put("path", path));
        if (evidence.family().equals("analysis.manifest")
                && value.get("configuration") instanceof Map<?, ?> configuration
                && configuration.get("values") instanceof Map<?, ?> options) {
            Object module = options.get("java.module"), set = options.get("java.source-set");
            if (value.get("modules") instanceof List<?> modules)
                for (var descriptor : modules) {
                    if (!(descriptor instanceof Map<?, ?> row) || !Objects.equals(row.get("identity"), module))
                        continue;
                    if (module instanceof String id && budget.reserve(2))
                        metadata.put("module", id);
                    if (row.get("path") instanceof String path)
                        try {
                            String relative = ContractChecks.modulePath(path);
                            if (budget.reserve(2))
                                metadata.put("modulePath", relative);
                        } catch (IllegalArgumentException invalid) {
                            budget.qualify("INVALID_MODULE_PATH");
                        }
                    break;
                }
            if (set instanceof String s && Set.of("MAIN", "TEST", "GENERATED_MAIN", "GENERATED_TEST").contains(s)
                    && budget.reserve(2))
                metadata.put("sourceSet", s);
        }
        if (evidence.family().equals("spring.space") && value.get("domains") instanceof List<?> domains) {
            if (domains.size() > budget.limits.maxResults())
                budget.qualify("EVIDENCE_DESCRIPTOR_LIMIT");
            long permitted = Math.min(budget.limits.maxResults(),
                    Math.max(0, (budget.limits.maxAuxiliaryStates() - budget.auxiliary - budget.descriptorUnits) / 4));
            if (domains.size() > permitted)
                budget.qualify("EVIDENCE_DESCRIPTOR_LIMIT");
            budget.descriptorUnits += 4 * Math.min(domains.size(), permitted);
            metadata.put("domainCount", domains.size());
            metadata.put("domains", domains.stream().limit(permitted).map(d -> {
                var domain = GraphJson.object(d);
                var variable = GraphJson.object(domain.get("variable"));
                return Map.of("kind", variable.get("kind"), "variableDigest",
                        ContentDigest.sha256Utf8(CanonicalJson.write(variable)), "valueCount",
                        GraphJson.array(domain.get("values")).size());
            }).toList());
        }
        return new EvidenceView(evidence.identity(), evidence.family(),
                !CanonicalJson.write(metadata).equals(evidence.canonicalJson()), metadata);
    }

    private Object sanitize(Object value, int depth, QueryBudget budget) {
        if (budget.auxiliary + budget.descriptorUnits >= budget.limits.maxAuxiliaryStates()) {
            budget.qualify("EVIDENCE_DESCRIPTOR_LIMIT");
            return Map.of();
        }
        budget.descriptorUnits++;
        if (depth > 20) {
            budget.qualify("EVIDENCE_DESCRIPTOR_LIMIT");
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            var safe = new TreeMap<String, Object>();
            for (var entry : map.entrySet())
                if (SAFE_FIELDS.contains(entry.getKey())
                        && (!entry.getKey().equals("spelling") || safeSpelling(entry.getValue())))
                    safe.put((String) entry.getKey(), sanitize(entry.getValue(), depth + 1, budget));
            return safe;
        }
        if (value instanceof List<?> list) {
            if (list.size() > budget.limits.maxResults())
                budget.qualify("EVIDENCE_DESCRIPTOR_LIMIT");
            return list.stream().limit(budget.limits.maxResults()).map(v -> sanitize(v, depth + 1, budget)).toList();
        }
        return value;
    }

    /**
     * DeclarationRecord.spelling may contain an entire class/method body, not just
     * a name.
     */
    private static boolean safeSpelling(Object value) {
        return value instanceof String text
                && javax.lang.model.SourceVersion.isName(text, javax.lang.model.SourceVersion.RELEASE_21);
    }

    /**
     * Single-scan immutable graph index with lazy per-class member & annotation population
     * (inspired by MeCheck Fig. 6). Scanning the underlying KnowledgeGraph happens once per session;
     * per-class fields, methods, constructors, and annotations are materialized on demand and memoized.
     */
    private static final class SessionMetadataIndex {
        private final KnowledgeGraph graph;
        private final ArchitectureQueryService owner;
        private final Map<String, Map<String, List<KnowledgeGraph.Node>>> nodesByContextAndKind;
        private final Map<String, List<KnowledgeGraph.Node>> nodesByKindAll;
        private final Map<String, Map<String, KnowledgeGraph.Node>> nodeByContextAndId;
        private final Map<String, KnowledgeGraph.Node> nodeByIdAll;
        private final Map<String, Map<String, List<KnowledgeGraph.Edge>>> edgesByContextAndKind;
        private final Map<String, List<KnowledgeGraph.Edge>> edgesByKindAll;
        private final Map<String, Map<String, List<KnowledgeGraph.Edge>>> edgesByContextAndSource;
        private final Map<String, List<KnowledgeGraph.Edge>> edgesBySourceAll;
        private final Map<String, List<GraphEvidence>> evidenceByFamily;
        private final Map<String, Map<String, String>> fqnToNodeIdByContext;
        private final Map<String, String> fqnToNodeIdAll;
        private final Map<String, Map<String, List<String>>> childMembersByOwner;
        private final Map<String, Map<String, List<AnnotationMetadata>>> annotationsByOwner;
        private final Map<String, Map<String, String>> typeShapeKindByNode;
        private final Map<String, Boolean> typeAbstractByNode;
        private final Map<String, Map<String, Object>> memberShapeByNode;
        private final Map<String, String> returnTypeByMethod;
        private final Map<String, String> fieldTypeByField;
        private final Map<String, List<String>> paramTypesByMethod;
        private final Map<String, Set<String>> superTypesByNode;
        private final Map<String, String> docPathById;
        private final java.util.concurrent.ConcurrentHashMap<String, Optional<ClassMetadata>> lazyClassCache =
                new java.util.concurrent.ConcurrentHashMap<>();

        SessionMetadataIndex(KnowledgeGraph graph, ArchitectureQueryService owner) {
            this.graph = graph;
            this.owner = owner;

            var byCtxKind = new HashMap<String, Map<String, List<KnowledgeGraph.Node>>>();
            var byKindAll = new HashMap<String, List<KnowledgeGraph.Node>>();
            var byCtxId = new HashMap<String, Map<String, KnowledgeGraph.Node>>();
            var byIdAll = new HashMap<String, KnowledgeGraph.Node>();
            var fqnByCtx = new HashMap<String, Map<String, String>>();
            var fqnAll = new TreeMap<String, String>();
            var docs = new HashMap<String, String>();

            // Single scan over nodes
            for (var node : graph.nodes()) {
                byCtxKind.computeIfAbsent(node.context(), k -> new HashMap<>())
                        .computeIfAbsent(node.kind(), k -> new ArrayList<>()).add(node);
                byKindAll.computeIfAbsent(node.kind(), k -> new ArrayList<>()).add(node);
                byCtxId.computeIfAbsent(node.context(), k -> new HashMap<>()).put(node.identity(), node);
                byIdAll.putIfAbsent(node.identity(), node);

                if ("SOURCE_DOCUMENT".equals(node.kind())) {
                    for (var ref : node.evidence()) {
                        var ev = graph.evidence(ref).orElse(null);
                        if (ev != null && ("source.document".equals(ev.family()) || "source.bytes".equals(ev.family()))) {
                            var payload = owner.parsedEvidence(ev);
                            Object pathObj = payload.get("path");
                            if (pathObj == null && payload.get("document") instanceof Map<?, ?> docMap) {
                                pathObj = docMap.get("path");
                            }
                            if (pathObj instanceof String p && !p.isBlank()) {
                                docs.putIfAbsent(node.identity(), p);
                                break;
                            }
                        }
                    }
                } else if ("TYPE".equals(node.kind())) {
                    String fqcn = extractTypeFqcn(node);
                    if (fqcn != null && !fqcn.isBlank()) {
                        fqnByCtx.computeIfAbsent(node.context(), k -> new TreeMap<>()).putIfAbsent(fqcn, node.identity());
                        fqnAll.putIfAbsent(fqcn, node.identity());
                    }
                }
            }

            // Single scan over edges
            var eByCtxKind = new HashMap<String, Map<String, List<KnowledgeGraph.Edge>>>();
            var eByKindAll = new HashMap<String, List<KnowledgeGraph.Edge>>();
            var eByCtxSrc = new HashMap<String, Map<String, List<KnowledgeGraph.Edge>>>();
            var eBySrcAll = new HashMap<String, List<KnowledgeGraph.Edge>>();
            var children = new HashMap<String, Map<String, List<String>>>();
            var supers = new HashMap<String, Set<String>>();

            for (var edge : graph.edges()) {
                eByCtxKind.computeIfAbsent(edge.context(), k -> new HashMap<>())
                        .computeIfAbsent(edge.kind(), k -> new ArrayList<>()).add(edge);
                eByKindAll.computeIfAbsent(edge.kind(), k -> new ArrayList<>()).add(edge);
                eByCtxSrc.computeIfAbsent(edge.context(), k -> new HashMap<>())
                        .computeIfAbsent(edge.source(), k -> new ArrayList<>()).add(edge);
                eBySrcAll.computeIfAbsent(edge.source(), k -> new ArrayList<>()).add(edge);

                if (edge.target().state() == KnowledgeGraph.TargetState.RESOLVED) {
                    String target = edge.target().identities().getFirst();
                    if ("graph.declares".equals(edge.kind()) || "graph.contains".equals(edge.kind())) {
                        children.computeIfAbsent(edge.context(), k -> new HashMap<>())
                                .computeIfAbsent(edge.source(), k -> new ArrayList<>()).add(target);
                    } else if ("java.extends".equals(edge.kind()) || "java.implements".equals(edge.kind())) {
                        var targetNode = byCtxId.getOrDefault(edge.context(), Map.of()).get(target);
                        if (targetNode != null) {
                            String targetFqcn = extractTypeFqcn(targetNode);
                            if (targetFqcn != null && !targetFqcn.isBlank()) {
                                supers.computeIfAbsent(edge.source(), k -> new TreeSet<>()).add(targetFqcn);
                            }
                        }
                    }
                }
            }

            // Single scan over evidence
            var evByFam = new HashMap<String, List<GraphEvidence>>();
            var typeKindMap = new HashMap<String, Map<String, String>>();
            var typeAbsMap = new HashMap<String, Boolean>();
            var memberShapes = new HashMap<String, Map<String, Object>>();
            var retTypes = new HashMap<String, String>();
            var fldTypes = new HashMap<String, String>();
            var pTypes = new HashMap<String, List<String>>();
            var annByOwner = new HashMap<String, Map<String, List<AnnotationMetadata>>>();

            for (var ev : graph.evidence()) {
                evByFam.computeIfAbsent(ev.family(), k -> new ArrayList<>()).add(ev);
                switch (ev.family()) {
                    case "java.type-shape" -> {
                        var m = owner.parsedEvidence(ev);
                        if (m.get("type") instanceof String typeId) {
                            if (m.get("kind") instanceof String k) {
                                typeKindMap.computeIfAbsent("", x -> new HashMap<>()).put(typeId, k);
                            }
                            if (m.get("abstractType") instanceof Boolean abs) {
                                typeAbsMap.put(typeId, abs);
                            }
                        }
                    }
                    case "java.member-shape" -> {
                        var m = owner.parsedEvidence(ev);
                        if (m.get("member") instanceof String memberId) {
                            memberShapes.put(memberId, m);
                        }
                    }
                    case "java.type-use" -> {
                        var m = owner.parsedEvidence(ev);
                        if (m.get("owner") instanceof String ownerId && m.get("role") instanceof String role) {
                            String resolvedType = extractTypeUseName(m.get("type"), byIdAll);
                            if (resolvedType != null && !resolvedType.isBlank()) {
                                if ("java.return-type".equals(role)) {
                                    retTypes.putIfAbsent(ownerId, resolvedType);
                                } else if ("java.field-type".equals(role)) {
                                    fldTypes.putIfAbsent(ownerId, resolvedType);
                                } else if ("java.parameter-type".equals(role)) {
                                    pTypes.computeIfAbsent(ownerId, x -> new ArrayList<>()).add(resolvedType);
                                }
                            }
                        }
                    }
                    case "java.annotation-use" -> {
                        var m = owner.parsedEvidence(ev);
                        if (m.get("owner") instanceof String ownerId) {
                            String spelling = m.get("spelling") instanceof String s ? s : "";
                            String annName = extractAnnotationName(m, spelling);
                            String sourceFile = null;
                            int line = 0;
                            if (m.get("span") instanceof Map<?, ?> sp) {
                                if (sp.get("document") instanceof String docId) {
                                    sourceFile = docs.getOrDefault(docId, docId);
                                }
                                if (sp.get("startLine") instanceof Number n) {
                                    line = n.intValue();
                                }
                            }
                            var ann = new AnnotationMetadata(annName, spelling, Optional.of(ownerId),
                                    Optional.ofNullable(sourceFile), line);
                            annByOwner.computeIfAbsent("", x -> new HashMap<>())
                                    .computeIfAbsent(ownerId, x -> new ArrayList<>()).add(ann);
                        }
                    }
                    default -> {}
                }
            }

            this.nodesByContextAndKind = freezeNestedListMap(byCtxKind);
            this.nodesByKindAll = freezeListMap(byKindAll);
            this.nodeByContextAndId = freezeNestedMap(byCtxId);
            this.nodeByIdAll = Collections.unmodifiableMap(byIdAll);
            this.edgesByContextAndKind = freezeNestedListMap(eByCtxKind);
            this.edgesByKindAll = freezeListMap(eByKindAll);
            this.edgesByContextAndSource = freezeNestedListMap(eByCtxSrc);
            this.edgesBySourceAll = freezeListMap(eBySrcAll);
            this.evidenceByFamily = freezeListMap(evByFam);
            this.fqnToNodeIdByContext = freezeNestedMap(fqnByCtx);
            this.fqnToNodeIdAll = Collections.unmodifiableMap(fqnAll);
            this.childMembersByOwner = freezeNestedListMap(children);
            this.annotationsByOwner = freezeNestedListMap(annByOwner);
            this.typeShapeKindByNode = freezeNestedMap(typeKindMap);
            this.typeAbstractByNode = Collections.unmodifiableMap(typeAbsMap);
            this.memberShapeByNode = Collections.unmodifiableMap(memberShapes);
            this.returnTypeByMethod = Collections.unmodifiableMap(retTypes);
            this.fieldTypeByField = Collections.unmodifiableMap(fldTypes);
            this.paramTypesByMethod = freezeListMap(pTypes);
            this.superTypesByNode = Collections.unmodifiableMap(supers);
            this.docPathById = Collections.unmodifiableMap(docs);
        }

        int indexedClassCount() {
            return lazyClassCache.size();
        }

        List<KnowledgeGraph.Node> nodesByKind(String context, String kind) {
            if (context == null || context.isEmpty()) {
                return nodesByKindAll.getOrDefault(kind, List.of());
            }
            return nodesByContextAndKind.getOrDefault(context, Map.of()).getOrDefault(kind, List.of());
        }

        List<KnowledgeGraph.Edge> edgesByKind(String context, String kind) {
            if (context == null || context.isEmpty()) {
                return edgesByKindAll.getOrDefault(kind, List.of());
            }
            return edgesByContextAndKind.getOrDefault(context, Map.of()).getOrDefault(kind, List.of());
        }

        List<KnowledgeGraph.Edge> edgesBySource(String context, String sourceId) {
            if (context == null || context.isEmpty()) {
                return edgesBySourceAll.getOrDefault(sourceId, List.of());
            }
            return edgesByContextAndSource.getOrDefault(context, Map.of()).getOrDefault(sourceId, List.of());
        }

        List<GraphEvidence> evidenceByFamily(String family) {
            return evidenceByFamily.getOrDefault(family, List.of());
        }

        List<String> indexedClassNames(String context) {
            if (context == null || context.isEmpty()) {
                return List.copyOf(fqnToNodeIdAll.keySet());
            }
            return List.copyOf(fqnToNodeIdByContext.getOrDefault(context, Map.of()).keySet());
        }

        Optional<ClassMetadata> classMetadata(String context, String fqcnOrNodeId, boolean cache) {
            String ctxKey = context == null ? "" : context;
            String cacheKey = ctxKey + "|" + fqcnOrNodeId;
            if (cache) {
                var existing = lazyClassCache.get(cacheKey);
                if (existing != null) {
                    return existing;
                }
            }
            Optional<ClassMetadata> computed = materializeClass(ctxKey, fqcnOrNodeId);
            if (cache) {
                lazyClassCache.putIfAbsent(cacheKey, computed);
            }
            return computed;
        }

        private Optional<ClassMetadata> materializeClass(String context, String fqcnOrNodeId) {
            String nodeId = null;
            KnowledgeGraph.Node typeNode = null;
            if (context.isEmpty()) {
                typeNode = nodeByIdAll.get(fqcnOrNodeId);
                if (typeNode == null) {
                    nodeId = fqnToNodeIdAll.get(fqcnOrNodeId);
                    if (nodeId != null) {
                        typeNode = nodeByIdAll.get(nodeId);
                    }
                } else {
                    nodeId = typeNode.identity();
                }
            } else {
                var ctxNodes = nodeByContextAndId.getOrDefault(context, Map.of());
                typeNode = ctxNodes.get(fqcnOrNodeId);
                if (typeNode == null) {
                    nodeId = fqnToNodeIdByContext.getOrDefault(context, Map.of()).get(fqcnOrNodeId);
                    if (nodeId != null) {
                        typeNode = ctxNodes.get(nodeId);
                    }
                } else {
                    nodeId = typeNode.identity();
                }
            }
            if (typeNode == null || !"TYPE".equals(typeNode.kind())) {
                return Optional.empty();
            }
            String effectiveCtx = typeNode.context();
            String fqcn = extractTypeFqcn(typeNode);
            if (fqcn == null || fqcn.isBlank()) {
                fqcn = fqcnOrNodeId;
            }
            String typeKind = typeShapeKindByNode.getOrDefault("", Map.of()).getOrDefault(nodeId, "CLASS");
            boolean abstractType = typeAbstractByNode.getOrDefault(nodeId, "INTERFACE".equals(typeKind));
            var superTypes = new TreeSet<String>(superTypesByNode.getOrDefault(nodeId, Set.of()));
            var typeAnns = new ArrayList<AnnotationMetadata>(annotationsForNode(effectiveCtx, nodeId));
            var constructors = new ArrayList<MemberMetadata>();
            var methods = new ArrayList<MemberMetadata>();
            var fields = new ArrayList<MemberMetadata>();

            // Also read inline metadata directly from TYPE node's java.declaration evidence if supplied
            for (var ref : typeNode.evidence()) {
                var ev = graph.evidence(ref).orElse(null);
                if (ev == null) continue;
                var payload = owner.parsedEvidence(ev);
                if (payload.get("superTypes") instanceof List<?> sts) {
                    for (var s : sts) {
                        if (s instanceof String st && !st.isBlank()) superTypes.add(st);
                    }
                }
                if (payload.get("abstractType") instanceof Boolean abs) {
                    abstractType = abs;
                }
                if (payload.get("typeKind") instanceof String tk && !tk.isBlank()) {
                    typeKind = tk;
                }
            }

            // Lazily materialize declared members for THIS class only
            String simpleName = fqcn.contains(".") ? fqcn.substring(fqcn.lastIndexOf('.') + 1) : fqcn;
            var childIds = childMembersByOwner.getOrDefault(effectiveCtx, Map.of()).getOrDefault(nodeId, List.of());
            var ctxNodeMap = nodeByContextAndId.getOrDefault(effectiveCtx, Map.of());
            for (String childId : childIds) {
                var childNode = ctxNodeMap.get(childId);
                if (childNode == null) continue;
                if ("METHOD".equals(childNode.kind()) || "CONSTRUCTOR".equals(childNode.kind())) {
                    var mm = materializeMember(effectiveCtx, childNode, simpleName);
                    if ("CONSTRUCTOR".equals(mm.kind()) || "<init>".equals(mm.name()) || simpleName.equals(mm.name())) {
                        constructors.add(mm);
                    } else {
                        methods.add(mm);
                    }
                } else if ("FIELD".equals(childNode.kind()) || "RECORD_COMPONENT".equals(childNode.kind())) {
                    fields.add(materializeMember(effectiveCtx, childNode, simpleName));
                }
            }
            constructors.sort(Comparator.comparingInt(MemberMetadata::paramCount).thenComparing(MemberMetadata::nodeId));
            methods.sort(Comparator.comparing(MemberMetadata::name).thenComparingInt(MemberMetadata::paramCount).thenComparing(MemberMetadata::nodeId));
            fields.sort(Comparator.comparing(MemberMetadata::name).thenComparing(MemberMetadata::nodeId));
            typeAnns.sort(Comparator.comparing(AnnotationMetadata::name).thenComparing(AnnotationMetadata::rawSpelling));

            return Optional.of(new ClassMetadata(nodeId, fqcn, typeKind, abstractType, superTypes,
                    constructors, methods, fields, typeAnns));
        }

        private MemberMetadata materializeMember(String context, KnowledgeGraph.Node memberNode, String ownerSimpleName) {
            String memberId = memberNode.identity();
            String name = "";
            boolean isStatic = false;
            boolean isAbstract = false;
            int paramCount = -1;
            var paramTypes = new ArrayList<String>();
            String returnType = returnTypeByMethod.get(memberId);
            String fieldType = fieldTypeByField.get(memberId);

            var shape = memberShapeByNode.get(memberId);
            if (shape != null) {
                if (shape.get("name") instanceof String n) name = n;
                if (shape.get("staticMember") instanceof Boolean b) isStatic = b;
                if (shape.get("abstractMember") instanceof Boolean b) isAbstract = b;
            }
            var indexedParams = paramTypesByMethod.get(memberId);
            if (indexedParams != null) {
                paramTypes.addAll(indexedParams);
                paramCount = indexedParams.size();
            }

            for (var ref : memberNode.evidence()) {
                var ev = graph.evidence(ref).orElse(null);
                if (ev == null) continue;
                var payload = owner.parsedEvidence(ev);
                if ("java.declaration".equals(ev.family())) {
                    if (payload.get("entity") instanceof Map<?, ?> entityMap) {
                        Object cn = entityMap.get("canonicalName");
                        if (cn instanceof String canonical) {
                            if (name.isEmpty()) {
                                name = extractMemberNameFromCanonical(canonical);
                            }
                            if (paramCount < 0) {
                                int parsedArity = extractMethodArityFromCanonical(canonical);
                                if (parsedArity >= 0) paramCount = parsedArity;
                            }
                        }
                    }
                    if (name.isEmpty() && payload.get("spelling") instanceof String sp && safeSpelling(sp)) {
                        name = sp;
                    }
                    if (payload.get("paramCount") instanceof Number n && paramCount < 0) {
                        paramCount = n.intValue();
                    }
                    if (payload.get("paramTypes") instanceof List<?> pts && paramTypes.isEmpty()) {
                        for (var p : pts) {
                            if (p instanceof String ps) paramTypes.add(ps);
                        }
                        if (paramCount < 0) paramCount = paramTypes.size();
                    }
                    if (returnType == null && payload.get("returnType") instanceof String rt && !rt.isBlank()) {
                        returnType = rt;
                    }
                    if (fieldType == null && payload.get("fieldType") instanceof String ft && !ft.isBlank()) {
                        fieldType = ft;
                    }
                    if (payload.get("staticMember") instanceof Boolean b) isStatic = b;
                    if (payload.get("abstractMember") instanceof Boolean b) isAbstract = b;
                }
            }
            if (paramCount < 0) {
                paramCount = paramTypes.size();
            }
            String kind = memberNode.kind();
            if ("<init>".equals(name) || ownerSimpleName.equals(name)) {
                kind = "CONSTRUCTOR";
            }
            var anns = annotationsForNode(context, memberId);
            return new MemberMetadata(memberId, name, kind, isStatic, isAbstract, paramCount,
                    paramTypes, Optional.ofNullable(returnType), Optional.ofNullable(fieldType), anns);
        }

        private List<AnnotationMetadata> annotationsForNode(String context, String ownerId) {
            var out = new ArrayList<AnnotationMetadata>(
                    annotationsByOwner.getOrDefault("", Map.of()).getOrDefault(ownerId, List.of()));
            // Also follow graph.has-annotation-use edges in case annotation evidence uses edge linkage
            var outEdges = edgesByContextAndSource.getOrDefault(context, Map.of()).getOrDefault(ownerId, List.of());
            var ctxNodeMap = nodeByContextAndId.getOrDefault(context, Map.of());
            var seen = new HashSet<String>();
            out.forEach(a -> seen.add(a.name() + "|" + a.rawSpelling()));
            for (var edge : outEdges) {
                if (!"graph.has-annotation-use".equals(edge.kind()) || edge.target().state() != KnowledgeGraph.TargetState.RESOLVED) {
                    continue;
                }
                var annNode = ctxNodeMap.get(edge.target().identities().getFirst());
                if (annNode == null) continue;
                for (var ref : annNode.evidence()) {
                    var ev = graph.evidence(ref).orElse(null);
                    if (ev == null || !"java.annotation-use".equals(ev.family())) continue;
                    var m = owner.parsedEvidence(ev);
                    String spelling = m.get("spelling") instanceof String s ? s : "";
                    String annName = extractAnnotationName(m, spelling);
                    if (seen.add(annName + "|" + spelling)) {
                        String sourceFile = null;
                        int line = 0;
                        if (m.get("span") instanceof Map<?, ?> sp) {
                            if (sp.get("document") instanceof String docId) {
                                sourceFile = docPathById.getOrDefault(docId, docId);
                            }
                            if (sp.get("startLine") instanceof Number n) {
                                line = n.intValue();
                            }
                        }
                        out.add(new AnnotationMetadata(annName, spelling, Optional.of(ownerId),
                                Optional.ofNullable(sourceFile), line));
                    }
                }
            }
            out.sort(Comparator.comparing(AnnotationMetadata::name).thenComparing(AnnotationMetadata::rawSpelling));
            return List.copyOf(out);
        }

        private String extractTypeFqcn(KnowledgeGraph.Node node) {
            for (var ref : node.evidence()) {
                var ev = graph.evidence(ref).orElse(null);
                if (ev == null || !"java.declaration".equals(ev.family())) continue;
                var payload = owner.parsedEvidence(ev);
                if (payload.get("entity") instanceof Map<?, ?> entityMap
                        && entityMap.get("canonicalName") instanceof String canonical && !canonical.isBlank()) {
                    return qualifiedName(canonical);
                }
            }
            return null;
        }

        private String extractTypeUseName(Object typeObj, Map<String, KnowledgeGraph.Node> allNodes) {
            if (typeObj instanceof String s) {
                return qualifiedName(s);
            }
            if (typeObj instanceof Map<?, ?> tm) {
                if (tm.get("entity") instanceof String entityId) {
                    var n = allNodes.get(entityId);
                    if (n != null) {
                        String fqcn = extractTypeFqcn(n);
                        if (fqcn != null) return fqcn;
                    }
                }
                if (tm.get("canonicalName") instanceof String cn) return qualifiedName(cn);
                if (tm.get("name") instanceof String n) return n;
                if (tm.get("spelling") instanceof String sp) return sp;
                if (tm.get("raw") instanceof Map<?, ?> rawMap) {
                    return extractTypeUseName(rawMap, allNodes);
                }
            }
            return null;
        }

        private static String extractAnnotationName(Map<String, Object> m, String spelling) {
            if (m.get("annotationType") instanceof String at && !at.isBlank()) return at;
            if (m.get("type") instanceof String t && !t.isBlank()) return t;
            if (spelling != null && !spelling.isBlank()) {
                String s = spelling.strip();
                if (s.startsWith("@")) s = s.substring(1);
                int paren = s.indexOf('(');
                if (paren >= 0) s = s.substring(0, paren);
                return s.strip();
            }
            if (m.get("canonicalName") instanceof String cn) return cn;
            return "";
        }

        private static String extractMemberNameFromCanonical(String canonical) {
            if (canonical.startsWith("java:v1:")) {
                try {
                    var tuple = GraphJson.array(GraphJson.read(canonical.substring(8),
                            new GraphJson.Limits(100_000, 64, 10_000)));
                    if (tuple.size() >= 3 && tuple.get(2) instanceof String memberName) {
                        return decodeName(memberName);
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
            int paren = canonical.indexOf('(');
            String base = paren >= 0 ? canonical.substring(0, paren) : canonical;
            int dot = base.lastIndexOf('.');
            return dot >= 0 ? base.substring(dot + 1) : base;
        }

        private static int extractMethodArityFromCanonical(String canonical) {
            if (canonical.startsWith("java:v1:")) {
                try {
                    var tuple = GraphJson.array(GraphJson.read(canonical.substring(8),
                            new GraphJson.Limits(100_000, 64, 10_000)));
                    if (tuple.size() >= 4 && tuple.get(3) instanceof List<?> params) {
                        return params.size();
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
            int open = canonical.indexOf('(');
            int close = canonical.lastIndexOf(')');
            if (open >= 0 && close > open) {
                String inside = canonical.substring(open + 1, close).strip();
                if (inside.isEmpty()) return 0;
                int count = 1;
                int depth = 0;
                for (int i = 0; i < inside.length(); i++) {
                    char c = inside.charAt(i);
                    if (c == '<') depth++;
                    else if (c == '>') depth--;
                    else if (c == ',' && depth == 0) count++;
                }
                return count;
            }
            return -1;
        }

        private static <K, V> Map<K, List<V>> freezeListMap(Map<K, List<V>> map) {
            var out = new HashMap<K, List<V>>(map.size() * 2 + 1);
            map.forEach((k, v) -> out.put(k, List.copyOf(v)));
            return Collections.unmodifiableMap(out);
        }

        private static <K1, K2, V> Map<K1, Map<K2, List<V>>> freezeNestedListMap(Map<K1, Map<K2, List<V>>> map) {
            var out = new HashMap<K1, Map<K2, List<V>>>(map.size() * 2 + 1);
            map.forEach((k, inner) -> out.put(k, freezeListMap(inner)));
            return Collections.unmodifiableMap(out);
        }

        private static <K1, K2, V> Map<K1, Map<K2, V>> freezeNestedMap(Map<K1, Map<K2, V>> map) {
            var out = new HashMap<K1, Map<K2, V>>(map.size() * 2 + 1);
            map.forEach((k, inner) -> out.put(k, Collections.unmodifiableMap(inner)));
            return Collections.unmodifiableMap(out);
        }
    }
}
