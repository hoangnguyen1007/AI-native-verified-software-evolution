package com.evolution.analysis.spring.universal;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.ingestion.IngestionEvidence;
import java.util.*;

/**
 * Sound-modulo-analysis container & cache summary table (JackEE PLDI '20, Sec. 4, Fig. 3),
 * abstracting JDK {@code Map}/{@code Collection} implementations and Spring {@code @Cacheable}
 * operations into deterministic {@code put -> contents} and {@code get <- contents} edges.
 *
 * <p>Collapsing internal hash-bucket / {@code TreeNode} allocations avoids precision collapse
 * and exponential points-to blowup while remaining sound modulo container semantics, ready for
 * WP4.1 interprocedural slicing.
 */
public final class CollectionContainerSummary {

    public static final VersionedIdentifier PROVIDER =
            new VersionedIdentifier("spring.collection-container-summary", "wp2.1-v1");

    public static final String ABSTRACT_CONTENTS_FIELD = "contents";

    public enum ContainerFamily {
        MAP,
        LIST,
        SET,
        QUEUE_DEQUE,
        COLLECTION,
        SPRING_CACHE_API,
        SPRING_CACHE_ANNOTATION
    }

    public enum FlowDirection {
        /** Value flows into container abstract field: {@code arg -> container.contents}. */
        PUT_TO_CONTENTS,
        /** Value flows out of container abstract field: {@code container.contents -> return}. */
        GET_FROM_CONTENTS,
        /** Bulk transfer between containers: {@code source.contents -> target.contents}. */
        PASSTHROUGH_CONTENTS,
        /** Entry removal / invalidation from container abstract field. */
        EVICT_CONTENTS
    }

    public record SummaryEntry(
            String ownerTypeFqn,
            String methodName,
            int arity,
            ContainerFamily family,
            FlowDirection direction,
            int valueParamIndex,
            int keyParamIndex,
            String abstractField,
            boolean collapsesInternalTreeNode,
            TrustTier trustTier) implements Comparable<SummaryEntry> {
        public SummaryEntry {
            ownerTypeFqn = Objects.requireNonNull(ownerTypeFqn, "ownerTypeFqn");
            methodName = Objects.requireNonNull(methodName, "methodName");
            family = Objects.requireNonNull(family, "family");
            direction = Objects.requireNonNull(direction, "direction");
            abstractField = (abstractField == null || abstractField.isBlank())
                    ? ABSTRACT_CONTENTS_FIELD : abstractField;
            trustTier = trustTier == null ? TrustTier.SOURCE_SYNTAX : trustTier;
        }

        public Map<String, Object> toEvidenceMap() {
            var map = new TreeMap<String, Object>();
            map.put("abstractField", abstractField);
            map.put("arity", arity);
            map.put("collapsesInternalTreeNode", collapsesInternalTreeNode);
            map.put("direction", direction.name());
            map.put("family", family.name());
            map.put("keyParamIndex", keyParamIndex);
            map.put("methodName", methodName);
            map.put("ownerTypeFqn", ownerTypeFqn);
            map.put("trustTier", trustTier.name());
            map.put("valueParamIndex", valueParamIndex);
            return Collections.unmodifiableMap(map);
        }

        @Override
        public int compareTo(SummaryEntry other) {
            int cmp = ownerTypeFqn.compareTo(other.ownerTypeFqn);
            if (cmp != 0) return cmp;
            cmp = methodName.compareTo(other.methodName);
            if (cmp != 0) return cmp;
            cmp = Integer.compare(arity, other.arity);
            if (cmp != 0) return cmp;
            return direction.compareTo(other.direction);
        }
    }

    /** Abstract dataflow edge ready for WP4.1 interprocedural slicing. */
    public record ContainerFlowEdge(
            String containerId,
            String abstractSlot,
            FlowDirection direction,
            ContainerFamily family,
            int valueParamIndex,
            int keyParamIndex,
            boolean collapsesInternalTreeNode) implements Comparable<ContainerFlowEdge> {
        @Override
        public int compareTo(ContainerFlowEdge other) {
            int cmp = containerId.compareTo(other.containerId);
            if (cmp != 0) return cmp;
            cmp = abstractSlot.compareTo(other.abstractSlot);
            if (cmp != 0) return cmp;
            return direction.compareTo(other.direction);
        }
    }

    private static final Set<String> MAP_TYPES = Set.of(
            "java.util.Map",
            "java.util.AbstractMap",
            "java.util.HashMap",
            "java.util.LinkedHashMap",
            "java.util.TreeMap",
            "java.util.IdentityHashMap",
            "java.util.WeakHashMap",
            "java.util.EnumMap",
            "java.util.Hashtable",
            "java.util.SortedMap",
            "java.util.NavigableMap",
            "java.util.concurrent.ConcurrentMap",
            "java.util.concurrent.ConcurrentHashMap",
            "java.util.concurrent.ConcurrentNavigableMap",
            "java.util.concurrent.ConcurrentSkipListMap");

    private static final Set<String> LIST_TYPES = Set.of(
            "java.util.List",
            "java.util.AbstractList",
            "java.util.ArrayList",
            "java.util.LinkedList",
            "java.util.Vector",
            "java.util.Stack",
            "java.util.concurrent.CopyOnWriteArrayList");

    private static final Set<String> SET_TYPES = Set.of(
            "java.util.Set",
            "java.util.AbstractSet",
            "java.util.HashSet",
            "java.util.LinkedHashSet",
            "java.util.TreeSet",
            "java.util.SortedSet",
            "java.util.NavigableSet",
            "java.util.EnumSet",
            "java.util.concurrent.ConcurrentSkipListSet",
            "java.util.concurrent.CopyOnWriteArraySet");

    private static final Set<String> QUEUE_DEQUE_TYPES = Set.of(
            "java.util.Queue",
            "java.util.Deque",
            "java.util.ArrayDeque",
            "java.util.PriorityQueue",
            "java.util.concurrent.BlockingQueue",
            "java.util.concurrent.BlockingDeque",
            "java.util.concurrent.LinkedBlockingQueue",
            "java.util.concurrent.ArrayBlockingQueue",
            "java.util.concurrent.ConcurrentLinkedQueue",
            "java.util.concurrent.ConcurrentLinkedDeque",
            "java.util.concurrent.PriorityBlockingQueue");

    private static final Set<String> COLLECTION_ROOT_TYPES = Set.of(
            "java.util.Collection",
            "java.util.AbstractCollection",
            "java.lang.Iterable");

    private static final Set<String> SPRING_CACHE_API_TYPES = Set.of(
            "org.springframework.cache.Cache",
            "org.springframework.cache.concurrent.ConcurrentMapCache");

    private static final List<SummaryEntry> ENTRIES = buildDefaultEntries();
    private static final Map<String, SummaryEntry> BY_CANONICAL_KEY = indexCanonicalEntries(ENTRIES);
    private static final Map<String, List<SummaryEntry>> BY_ANNOTATION = indexAnnotationEntries(ENTRIES);
    private static final ContentDigest TABLE_IDENTITY = IngestionEvidence.digest(Map.of(
            "provider", PROVIDER,
            "entries", ENTRIES));

    private CollectionContainerSummary() {}

    public static ContentDigest identity() {
        return TABLE_IDENTITY;
    }

    public static List<SummaryEntry> entries() {
        return ENTRIES;
    }

    public static boolean isAbstractedContainerType(String typeFqn) {
        return canonicalOwner(typeFqn).isPresent();
    }

    /**
     * Resolves a sound-modulo-analysis summary for a container or Spring {@code Cache} method call.
     */
    public static Optional<SummaryEntry> lookup(String typeFqn, String methodName, int arity) {
        if (typeFqn == null || methodName == null) return Optional.empty();
        String canonical = canonicalOwner(typeFqn).orElse(null);
        if (canonical == null) return Optional.empty();
        SummaryEntry direct = BY_CANONICAL_KEY.get(canonical + "#" + methodName + "/" + arity);
        if (direct != null) return Optional.of(direct);
        // Fallback to java.util.Collection for List/Set/Queue subtypes
        if (!"java.util.Map".equals(canonical) && !"org.springframework.cache.Cache".equals(canonical)) {
            return Optional.ofNullable(BY_CANONICAL_KEY.get("java.util.Collection#" + methodName + "/" + arity));
        }
        return Optional.empty();
    }

    /**
     * Resolves abstract cache-flow summaries for Spring cache annotations
     * ({@code @Cacheable}, {@code @CachePut}, {@code @CacheEvict}).
     */
    public static List<SummaryEntry> lookupAnnotation(String annotationFqn) {
        if (annotationFqn == null || annotationFqn.isBlank()) return List.of();
        List<SummaryEntry> exact = BY_ANNOTATION.get(annotationFqn.trim());
        if (exact != null) return exact;
        int dot = annotationFqn.lastIndexOf('.');
        String simple = dot >= 0 ? annotationFqn.substring(dot + 1) : annotationFqn;
        return BY_ANNOTATION.getOrDefault("org.springframework.cache.annotation." + simple, List.of());
    }

    /**
     * Produces an abstract {@code containerVarId.contents} flow edge for WP4.1 slicing.
     */
    public static Optional<ContainerFlowEdge> resolveFlowEdge(
            String containerVarId,
            String typeFqn,
            String methodName,
            int arity) {
        if (containerVarId == null || containerVarId.isBlank()) return Optional.empty();
        return lookup(typeFqn, methodName, arity).map(entry -> new ContainerFlowEdge(
                containerVarId.trim(),
                containerVarId.trim() + "." + entry.abstractField(),
                entry.direction(),
                entry.family(),
                entry.valueParamIndex(),
                entry.keyParamIndex(),
                entry.collapsesInternalTreeNode()));
    }

    private static Optional<String> canonicalOwner(String rawTypeFqn) {
        if (rawTypeFqn == null || rawTypeFqn.isBlank()) return Optional.empty();
        int lt = rawTypeFqn.indexOf('<');
        String clean = (lt >= 0 ? rawTypeFqn.substring(0, lt) : rawTypeFqn).trim();
        if (MAP_TYPES.contains(clean)) return Optional.of("java.util.Map");
        if (LIST_TYPES.contains(clean)) return Optional.of("java.util.List");
        if (SET_TYPES.contains(clean)) return Optional.of("java.util.Set");
        if (QUEUE_DEQUE_TYPES.contains(clean)) return Optional.of("java.util.Deque");
        if (COLLECTION_ROOT_TYPES.contains(clean)) return Optional.of("java.util.Collection");
        if (SPRING_CACHE_API_TYPES.contains(clean)) return Optional.of("org.springframework.cache.Cache");
        return Optional.empty();
    }

    private static List<SummaryEntry> buildDefaultEntries() {
        var list = new ArrayList<SummaryEntry>();

        // 1. java.util.Map (HashMap, LinkedHashMap, ConcurrentHashMap, TreeMap, ...) — collapses TreeNode!
        add(list, "java.util.Map", "put", 2, ContainerFamily.MAP, FlowDirection.PUT_TO_CONTENTS, 1, 0, true);
        add(list, "java.util.Map", "putIfAbsent", 2, ContainerFamily.MAP, FlowDirection.PUT_TO_CONTENTS, 1, 0, true);
        add(list, "java.util.Map", "replace", 2, ContainerFamily.MAP, FlowDirection.PUT_TO_CONTENTS, 1, 0, true);
        add(list, "java.util.Map", "replace", 3, ContainerFamily.MAP, FlowDirection.PUT_TO_CONTENTS, 2, 0, true);
        add(list, "java.util.Map", "compute", 2, ContainerFamily.MAP, FlowDirection.PUT_TO_CONTENTS, 1, 0, true);
        add(list, "java.util.Map", "computeIfAbsent", 2, ContainerFamily.MAP, FlowDirection.PUT_TO_CONTENTS, 1, 0, true);
        add(list, "java.util.Map", "computeIfPresent", 2, ContainerFamily.MAP, FlowDirection.PUT_TO_CONTENTS, 1, 0, true);
        add(list, "java.util.Map", "merge", 3, ContainerFamily.MAP, FlowDirection.PUT_TO_CONTENTS, 1, 0, true);
        add(list, "java.util.Map", "get", 1, ContainerFamily.MAP, FlowDirection.GET_FROM_CONTENTS, -1, 0, true);
        add(list, "java.util.Map", "getOrDefault", 2, ContainerFamily.MAP, FlowDirection.GET_FROM_CONTENTS, 1, 0, true);
        add(list, "java.util.Map", "values", 0, ContainerFamily.MAP, FlowDirection.GET_FROM_CONTENTS, -1, -1, true);
        add(list, "java.util.Map", "entrySet", 0, ContainerFamily.MAP, FlowDirection.GET_FROM_CONTENTS, -1, -1, true);
        add(list, "java.util.Map", "putAll", 1, ContainerFamily.MAP, FlowDirection.PASSTHROUGH_CONTENTS, 0, -1, true);
        add(list, "java.util.Map", "remove", 1, ContainerFamily.MAP, FlowDirection.EVICT_CONTENTS, -1, 0, true);
        add(list, "java.util.Map", "clear", 0, ContainerFamily.MAP, FlowDirection.EVICT_CONTENTS, -1, -1, true);

        // 2. java.util.Collection (shared root for List, Set, Queue, Deque)
        add(list, "java.util.Collection", "add", 1, ContainerFamily.COLLECTION, FlowDirection.PUT_TO_CONTENTS, 0, -1, false);
        add(list, "java.util.Collection", "addAll", 1, ContainerFamily.COLLECTION, FlowDirection.PASSTHROUGH_CONTENTS, 0, -1, false);
        add(list, "java.util.Collection", "iterator", 0, ContainerFamily.COLLECTION, FlowDirection.GET_FROM_CONTENTS, -1, -1, false);
        add(list, "java.util.Collection", "spliterator", 0, ContainerFamily.COLLECTION, FlowDirection.GET_FROM_CONTENTS, -1, -1, false);
        add(list, "java.util.Collection", "stream", 0, ContainerFamily.COLLECTION, FlowDirection.GET_FROM_CONTENTS, -1, -1, false);
        add(list, "java.util.Collection", "toArray", 0, ContainerFamily.COLLECTION, FlowDirection.GET_FROM_CONTENTS, -1, -1, false);
        add(list, "java.util.Collection", "toArray", 1, ContainerFamily.COLLECTION, FlowDirection.GET_FROM_CONTENTS, -1, -1, false);
        add(list, "java.util.Collection", "remove", 1, ContainerFamily.COLLECTION, FlowDirection.EVICT_CONTENTS, 0, -1, false);
        add(list, "java.util.Collection", "clear", 0, ContainerFamily.COLLECTION, FlowDirection.EVICT_CONTENTS, -1, -1, false);

        // 3. java.util.List
        add(list, "java.util.List", "add", 2, ContainerFamily.LIST, FlowDirection.PUT_TO_CONTENTS, 1, 0, false);
        add(list, "java.util.List", "set", 2, ContainerFamily.LIST, FlowDirection.PUT_TO_CONTENTS, 1, 0, false);
        add(list, "java.util.List", "get", 1, ContainerFamily.LIST, FlowDirection.GET_FROM_CONTENTS, -1, 0, false);
        add(list, "java.util.List", "getFirst", 0, ContainerFamily.LIST, FlowDirection.GET_FROM_CONTENTS, -1, -1, false);
        add(list, "java.util.List", "getLast", 0, ContainerFamily.LIST, FlowDirection.GET_FROM_CONTENTS, -1, -1, false);

        // 4. java.util.Set (HashSet/TreeSet backed by HashMap/TreeMap -> collapses TreeNode)
        add(list, "java.util.Set", "add", 1, ContainerFamily.SET, FlowDirection.PUT_TO_CONTENTS, 0, -1, true);

        // 5. java.util.Deque / Queue
        add(list, "java.util.Deque", "offer", 1, ContainerFamily.QUEUE_DEQUE, FlowDirection.PUT_TO_CONTENTS, 0, -1, false);
        add(list, "java.util.Deque", "push", 1, ContainerFamily.QUEUE_DEQUE, FlowDirection.PUT_TO_CONTENTS, 0, -1, false);
        add(list, "java.util.Deque", "put", 1, ContainerFamily.QUEUE_DEQUE, FlowDirection.PUT_TO_CONTENTS, 0, -1, false);
        add(list, "java.util.Deque", "addFirst", 1, ContainerFamily.QUEUE_DEQUE, FlowDirection.PUT_TO_CONTENTS, 0, -1, false);
        add(list, "java.util.Deque", "addLast", 1, ContainerFamily.QUEUE_DEQUE, FlowDirection.PUT_TO_CONTENTS, 0, -1, false);
        add(list, "java.util.Deque", "peek", 0, ContainerFamily.QUEUE_DEQUE, FlowDirection.GET_FROM_CONTENTS, -1, -1, false);
        add(list, "java.util.Deque", "poll", 0, ContainerFamily.QUEUE_DEQUE, FlowDirection.GET_FROM_CONTENTS, -1, -1, false);
        add(list, "java.util.Deque", "pop", 0, ContainerFamily.QUEUE_DEQUE, FlowDirection.GET_FROM_CONTENTS, -1, -1, false);
        add(list, "java.util.Deque", "take", 0, ContainerFamily.QUEUE_DEQUE, FlowDirection.GET_FROM_CONTENTS, -1, -1, false);
        add(list, "java.util.Deque", "element", 0, ContainerFamily.QUEUE_DEQUE, FlowDirection.GET_FROM_CONTENTS, -1, -1, false);

        // 6. Spring Cache API
        add(list, "org.springframework.cache.Cache", "put", 2, ContainerFamily.SPRING_CACHE_API, FlowDirection.PUT_TO_CONTENTS, 1, 0, true);
        add(list, "org.springframework.cache.Cache", "putIfAbsent", 2, ContainerFamily.SPRING_CACHE_API, FlowDirection.PUT_TO_CONTENTS, 1, 0, true);
        add(list, "org.springframework.cache.Cache", "get", 1, ContainerFamily.SPRING_CACHE_API, FlowDirection.GET_FROM_CONTENTS, -1, 0, true);
        add(list, "org.springframework.cache.Cache", "get", 2, ContainerFamily.SPRING_CACHE_API, FlowDirection.GET_FROM_CONTENTS, -1, 0, true);
        add(list, "org.springframework.cache.Cache", "evict", 1, ContainerFamily.SPRING_CACHE_API, FlowDirection.EVICT_CONTENTS, -1, 0, true);
        add(list, "org.springframework.cache.Cache", "evictIfPresent", 1, ContainerFamily.SPRING_CACHE_API, FlowDirection.EVICT_CONTENTS, -1, 0, true);
        add(list, "org.springframework.cache.Cache", "clear", 0, ContainerFamily.SPRING_CACHE_API, FlowDirection.EVICT_CONTENTS, -1, -1, true);

        // 7. Spring Cache Annotations (@Cacheable performs both cache-hit GET and cache-miss PUT on contents)
        add(list, "org.springframework.cache.annotation.Cacheable", "@Cacheable", -1,
                ContainerFamily.SPRING_CACHE_ANNOTATION, FlowDirection.GET_FROM_CONTENTS, -1, 0, true);
        add(list, "org.springframework.cache.annotation.Cacheable", "@Cacheable", -1,
                ContainerFamily.SPRING_CACHE_ANNOTATION, FlowDirection.PUT_TO_CONTENTS, -1, 0, true);
        add(list, "org.springframework.cache.annotation.CachePut", "@CachePut", -1,
                ContainerFamily.SPRING_CACHE_ANNOTATION, FlowDirection.PUT_TO_CONTENTS, -1, 0, true);
        add(list, "org.springframework.cache.annotation.CacheEvict", "@CacheEvict", -1,
                ContainerFamily.SPRING_CACHE_ANNOTATION, FlowDirection.EVICT_CONTENTS, -1, 0, true);

        Collections.sort(list);
        return List.copyOf(list);
    }

    private static void add(
            List<SummaryEntry> out,
            String owner,
            String method,
            int arity,
            ContainerFamily family,
            FlowDirection dir,
            int valueParam,
            int keyParam,
            boolean collapsesTreeNode) {
        out.add(new SummaryEntry(owner, method, arity, family, dir, valueParam, keyParam,
                ABSTRACT_CONTENTS_FIELD, collapsesTreeNode, TrustTier.SOURCE_SYNTAX));
    }

    private static Map<String, SummaryEntry> indexCanonicalEntries(List<SummaryEntry> entries) {
        var map = new HashMap<String, SummaryEntry>(entries.size() * 2);
        for (SummaryEntry e : entries) {
            if (e.family() != ContainerFamily.SPRING_CACHE_ANNOTATION) {
                map.putIfAbsent(e.ownerTypeFqn() + "#" + e.methodName() + "/" + e.arity(), e);
            }
        }
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, List<SummaryEntry>> indexAnnotationEntries(List<SummaryEntry> entries) {
        var map = new HashMap<String, List<SummaryEntry>>();
        for (SummaryEntry e : entries) {
            if (e.family() == ContainerFamily.SPRING_CACHE_ANNOTATION) {
                map.computeIfAbsent(e.ownerTypeFqn(), k -> new ArrayList<>()).add(e);
            }
        }
        var frozen = new HashMap<String, List<SummaryEntry>>();
        map.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
        return Collections.unmodifiableMap(frozen);
    }
}
