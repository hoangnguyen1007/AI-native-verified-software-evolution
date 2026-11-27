package com.evolution.analysis.query.projection;

import com.evolution.analysis.assessment.v2.LeidenCommunityDetector;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.policy.ArchitecturePolicy;
import com.evolution.analysis.policy.PolicyEvaluation;
import com.evolution.analysis.query.ArchitectureQuery;
import com.evolution.analysis.query.ArchitectureQueryService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import static com.evolution.analysis.query.ArchitectureQuery.*;

/** Projects canonical architecture query and policy results into visual workbench models. */
public final class GraphProjectionService {
    public static final VersionedIdentifier VERSION = new VersionedIdentifier("graph.projection", "m8.3-v1");
    public static final int AUTO_MACRO_THRESHOLD = 250;
    public static final int DEFAULT_MAX_NODES = 300;
    public static final int DEFAULT_MAX_EDGES = 1_200;
    public static final int MAX_ALLOWED_NODES = 5_000;
    public static final int MAX_ALLOWED_EDGES = 25_000;
    public static final String COMMUNITY_SUPER_NODE_KIND = "COMMUNITY_SUPER_NODE";
    public static final String COMMUNITY_ID_PREFIX = "community:";
    public static final String EXTERNAL_SUPER_NODE_ID = "community:external";
    private static final double GOLDEN_ANGLE = Math.PI * (3.0 - StrictMath.sqrt(5.0));

    public enum GroupBy {
        PACKAGE,
        COMMUNITY;

        public static GroupBy parse(String raw) {
            if (raw == null || raw.isBlank()) return PACKAGE;
            return "COMMUNITY".equalsIgnoreCase(raw.strip()) ? COMMUNITY : PACKAGE;
        }
    }

    public enum ProjectionLevel {
        AUTO,
        MACRO,
        MESO,
        MICRO;

        public static ProjectionLevel parse(String raw) {
            if (raw == null || raw.isBlank()) return AUTO;
            return switch (raw.strip().toUpperCase(Locale.ROOT)) {
                case "AUTO" -> AUTO;
                case "MACRO" -> MACRO;
                case "MESO" -> MESO;
                case "MICRO" -> MICRO;
                default -> throw new IllegalArgumentException("Unsupported projection level: " + raw);
            };
        }
    }

    public enum CycleMode {
        SCC,
        ELEMENTARY;

        public static CycleMode parse(String raw) {
            if (raw == null || raw.isBlank()) return SCC;
            return switch (raw.strip().toUpperCase(Locale.ROOT)) {
                case "SCC" -> SCC;
                case "ELEMENTARY" -> ELEMENTARY;
                default -> throw new IllegalArgumentException("Unsupported cycle mode: " + raw);
            };
        }
    }

    public record MultiScaleRequest(
            String context,
            ProjectionLevel level,
            List<String> expandCommunities,
            List<String> focusNodes,
            CycleMode cycleMode,
            int maxNodes,
            int maxEdges,
            Set<String> violatedEdgeKeys,
            Map<String, Integer> nodeViolationCounts
    ) {
        public MultiScaleRequest {
            Objects.requireNonNull(context, "context");
            if (context.isBlank()) throw new IllegalArgumentException("Explicit context required");
            level = level == null ? ProjectionLevel.AUTO : level;
            expandCommunities = expandCommunities == null
                    ? List.of()
                    : List.copyOf(new LinkedHashSet<>(expandCommunities.stream()
                            .filter(Objects::nonNull)
                            .map(String::strip)
                            .filter(s -> !s.isEmpty())
                            .toList()));
            focusNodes = focusNodes == null
                    ? List.of()
                    : List.copyOf(new LinkedHashSet<>(focusNodes.stream()
                            .filter(Objects::nonNull)
                            .map(String::strip)
                            .filter(s -> !s.isEmpty())
                            .toList()));
            cycleMode = cycleMode == null ? CycleMode.SCC : cycleMode;
            maxNodes = maxNodes <= 0 ? DEFAULT_MAX_NODES : Math.min(maxNodes, MAX_ALLOWED_NODES);
            maxEdges = maxEdges <= 0 ? DEFAULT_MAX_EDGES : Math.min(maxEdges, MAX_ALLOWED_EDGES);
            violatedEdgeKeys = violatedEdgeKeys == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new TreeSet<>(violatedEdgeKeys));
            nodeViolationCounts = nodeViolationCounts == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new TreeMap<>(nodeViolationCounts));
        }

        public static MultiScaleRequest defaults(String context) {
            return new MultiScaleRequest(
                    context,
                    ProjectionLevel.AUTO,
                    List.of(),
                    List.of(),
                    CycleMode.SCC,
                    DEFAULT_MAX_NODES,
                    DEFAULT_MAX_EDGES,
                    Set.of(),
                    Map.of());
        }
    }

    public static final class ProjectionInterrupted extends RuntimeException {
        private final StopReason reason;

        public ProjectionInterrupted(StopReason reason) {
            super("Visual graph projection interrupted: " + reason);
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        public StopReason reason() {
            return reason;
        }
    }

    private final ArchitectureQueryService queries;

    public GraphProjectionService(ArchitectureQueryService queries) {
        this.queries = Objects.requireNonNull(queries, "queries");
    }

    public VisualGraph project(Request request, Optional<PolicyEvaluation.Result> policy) {
        return project(request, policy, GroupBy.PACKAGE);
    }

    public VisualGraph project(Request request, Optional<PolicyEvaluation.Result> policy, GroupBy groupBy) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(policy, "policy");
        GroupBy mode = groupBy == null ? GroupBy.PACKAGE : groupBy;

        // 1. Fetch entities and edges via metric projection within limits
        var projectionResult = queries.metricProjection(request);
        var entityItems = projectionResult.items().isEmpty() ? List.<EntityView>of() : projectionResult.items().getFirst().entities();
        var edgeItems = projectionResult.items().isEmpty() ? List.<EdgeView>of() : projectionResult.items().getFirst().edges();
        var selectedDefinitions=new TreeSet<String>();var unknownDefinitions=new TreeSet<String>();
        int observedDefinitionCount=entityItems.size();
        if(request.scope()==Scope.SPRING_COMPONENT&&!projectionResult.items().isEmpty()){
            var source=projectionResult.items().getFirst();selectedDefinitions.addAll(source.selectedDefinitions());unknownDefinitions.addAll(source.unknownDefinitions());
            var included=new HashSet<>(selectedDefinitions);
            if(request.projection()==Projection.BOUNDED_UNION||request.projection()==Projection.UNKNOWN_OVERLAY)included.addAll(unknownDefinitions);
            if(request.projection()==Projection.UNKNOWN_OVERLAY)edgeItems.forEach(edge->{included.add(edge.source());included.add(edge.target());});
            entityItems=entityItems.stream().filter(entity->included.contains(entity.identity())).toList();
            edgeItems=edgeItems.stream().filter(edge->included.contains(edge.source())&&included.contains(edge.target())).toList();
        }

        // 2. Identify cycles
        var cycleParticipants = new HashSet<String>();
        var cycleEdgeIds = new HashSet<String>();
        var cycleResult = queries.findCycles(request);
            for (var cyclePath : cycleResult.items()) {
                cycleParticipants.addAll(cyclePath.vertices());
                for (var e : cyclePath.edges()) {
                    cycleEdgeIds.add(e.identity());
                    cycleParticipants.add(e.source());
                    cycleParticipants.add(e.target());
                }
            }

        // 3. Process policy violations
        var nodeViolations = new HashMap<String, Integer>();
        var nodeWorstSeverity = new HashMap<String, ArchitecturePolicy.Severity>();
        var violatingEdgeIds = new HashSet<String>();

        if (policy.isPresent()) {
            for (var finding : policy.orElseThrow().findings()) {
                var bundle = finding.bundle();
                var severity = finding.rule().severity();

                for (var v : bundle.path()) {
                    nodeViolations.merge(v, 1, Integer::sum);
                    updateWorstSeverity(nodeWorstSeverity, v, severity);
                }
                for (var edge : bundle.relationships()) {
                    violatingEdgeIds.add(edge.identity());
                    nodeViolations.merge(edge.source(), 1, Integer::sum);
                    nodeViolations.merge(edge.target(), 1, Integer::sum);
                    updateWorstSeverity(nodeWorstSeverity, edge.source(), severity);
                    updateWorstSeverity(nodeWorstSeverity, edge.target(), severity);
                }
            }
        }

        // 4. Build compound hierarchy and visual nodes
        var nodes = new ArrayList<VisualGraph.VisualNode>();
        var compoundNodes = new LinkedHashMap<String, VisualGraph.VisualNode>();

        LeidenCommunityDetector.Result leidenResult = null;
        var misplacedByNode = new HashMap<String, LeidenCommunityDetector.MisplacedType>();
        int externalNodeCount = 0;
        if (mode == GroupBy.COMMUNITY && !entityItems.isEmpty()) {
            // Cluster strictly over PROJECT-origin entities (matching ArchitectureInsightsAdvisor /
            // StructuralTopologyAnalyzer) so external JDK/Lombok/JPA library nodes cannot dilute modularity
            // or hide misplaced-type drift.
            var projectEntities = new ArrayList<EntityView>(entityItems.size());
            var projectIds = new HashSet<String>(entityItems.size() * 2);
            for (var entity : entityItems) {
                if (isProjectEntity(entity)) {
                    projectEntities.add(entity);
                    projectIds.add(entity.identity());
                } else {
                    externalNodeCount++;
                }
            }
            if (!projectEntities.isEmpty()) {
                String commonPkgPrefix = computeCommonPackagePrefix(projectEntities, request.scope());
                var leidenNodes = new ArrayList<LeidenCommunityDetector.NodeInput>(projectEntities.size());
                for (var entity : projectEntities) {
                    leidenNodes.add(new LeidenCommunityDetector.NodeInput(
                            entity.identity(),
                            shortLabel(entity, request.scope()),
                            groupName(entity, request.scope(), commonPkgPrefix),
                            entity.kind()));
                }
                var leidenEdges = new ArrayList<LeidenCommunityDetector.EdgeInput>();
                for (var edge : edgeItems) {
                    if (!projectIds.contains(edge.source()) || !projectIds.contains(edge.target())) {
                        continue;
                    }
                    String edgeKind = edge.kinds().isEmpty() ? "graph.depends-on" : edge.kinds().getFirst();
                    leidenEdges.add(new LeidenCommunityDetector.EdgeInput(
                            edge.source(), edge.target(), Math.max(1.0, edge.supportCount()), edgeKind));
                }
                leidenResult = LeidenCommunityDetector.detect(leidenNodes, leidenEdges, () -> {
                    var stop = queries.assessmentStopReason();
                    if (stop != StopReason.NONE) {
                        throw new ProjectionInterrupted(stop);
                    }
                });
                for (var m : leidenResult.misplacedTypes()) {
                    misplacedByNode.put(m.identity(), m);
                }
                for (var comm : leidenResult.communities()) {
                    String commCompoundId = "compound:community:" + comm.id();
                    var commMeta = new LinkedHashMap<String, Object>();
                    commMeta.put("compound", true);
                    commMeta.put("communityId", comm.id());
                    commMeta.put("memberSetDigest", comm.memberSetDigest());
                    commMeta.put("memberCount", comm.memberCount());
                    commMeta.put("cohesion", comm.cohesion());
                    commMeta.put("dominantPackage", comm.dominantPackage());
                    commMeta.put("hubIdentity", comm.hubIdentity());
                    commMeta.put("hubLabel", comm.hubLabel());
                    commMeta.put("misplacedCount", comm.misplacedCount());
                    commMeta.put("bridgeEdgeCount", comm.bridgeEdgeCount());
                    commMeta.put("internalEdgeCount", comm.internalEdgeCount());
                    commMeta.put("externalEdgeCount", comm.externalEdgeCount());
                    compoundNodes.put(commCompoundId, new VisualGraph.VisualNode(
                            commCompoundId,
                            comm.label(),
                            "COMMUNITY",
                            "COMMUNITY",
                            Optional.empty(),
                            Optional.of("PROJECT"),
                            Optional.empty(),
                            0,
                            Optional.empty(),
                            false,
                            Collections.unmodifiableMap(commMeta)
                    ));
                }
            }
            if (externalNodeCount > 0) {
                String extCompoundId = "compound:external";
                var extMeta = new LinkedHashMap<String, Object>();
                extMeta.put("compound", true);
                extMeta.put("externalGroup", true);
                extMeta.put("memberCount", externalNodeCount);
                compoundNodes.put(extCompoundId, new VisualGraph.VisualNode(
                        extCompoundId,
                        "External Dependencies",
                        "EXTERNAL_GROUP",
                        "EXTERNAL",
                        Optional.empty(),
                        Optional.of("DEPENDENCY"),
                        Optional.empty(),
                        0,
                        Optional.empty(),
                        false,
                        Collections.unmodifiableMap(extMeta)
                ));
            }
        }

        for (var entity : entityItems) {
            String label = entity.qualifiedName().orElse(entity.spelling().orElse(entity.canonicalName().orElse(shortId(entity.identity()))));
            Optional<String> parentId = Optional.empty();
            String pkgName = extractPackageName(entity);
            boolean projectEntity = isProjectEntity(entity);

            if (mode == GroupBy.COMMUNITY) {
                if (projectEntity && leidenResult != null) {
                    String commId = leidenResult.communityByNode().get(entity.identity());
                    if (commId != null) {
                        parentId = Optional.of("compound:community:" + commId);
                    }
                } else if (!projectEntity && compoundNodes.containsKey("compound:external")) {
                    parentId = Optional.of("compound:external");
                }
            } else if (request.scope() == Scope.TYPE && entity.kind().equals("TYPE")) {
                if (!pkgName.isEmpty()) {
                    String pkgCompoundId = "compound:package:" + pkgName;
                    Optional<String> moduleCompoundId = Optional.empty();

                    if (entity.module().isPresent()) {
                        String mod = entity.module().orElseThrow();
                        moduleCompoundId = Optional.of("compound:module:" + mod);
                        compoundNodes.putIfAbsent(moduleCompoundId.get(), new VisualGraph.VisualNode(
                            moduleCompoundId.get(),
                            mod,
                            "MODULE",
                            "MODULE",
                            Optional.empty(),
                            entity.origin(),
                            Optional.empty(),
                            0,
                            Optional.empty(),
                            false,
                            Map.of("compound", true)
                        ));
                    }

                    compoundNodes.putIfAbsent(pkgCompoundId, new VisualGraph.VisualNode(
                        pkgCompoundId,
                        pkgName,
                        "PACKAGE",
                        "PACKAGE",
                        moduleCompoundId,
                        entity.origin(),
                        Optional.empty(),
                        0,
                        Optional.empty(),
                        false,
                        Map.of("compound", true)
                    ));

                    parentId = Optional.of(pkgCompoundId);
                }
            } else if (request.scope() == Scope.PACKAGE && entity.kind().equals("PACKAGE")) {
                if (entity.module().isPresent()) {
                    String mod = entity.module().orElseThrow();
                    String modCompoundId = "compound:module:" + mod;
                    compoundNodes.putIfAbsent(modCompoundId, new VisualGraph.VisualNode(
                        modCompoundId,
                        mod,
                        "MODULE",
                        "MODULE",
                        Optional.empty(),
                        entity.origin(),
                        Optional.empty(),
                        0,
                        Optional.empty(),
                        false,
                        Map.of("compound", true)
                    ));
                    parentId = Optional.of(modCompoundId);
                }
            }

            int violations = nodeViolations.getOrDefault(entity.identity(), 0);
            Optional<String> worst = Optional.ofNullable(nodeWorstSeverity.get(entity.identity())).map(Enum::name);
            boolean isCycle = cycleParticipants.contains(entity.identity());

            Map<String, Object> nodeMeta;
            if (mode == GroupBy.COMMUNITY) {
                var m = new LinkedHashMap<String, Object>();
                m.put("evidenceCount", entity.evidence().size());
                if (request.scope() == Scope.SPRING_COMPONENT) {
                    m.put("activationStatus", selectedDefinitions.contains(entity.identity()) ? "SELECTED"
                            : unknownDefinitions.contains(entity.identity()) ? "UNKNOWN" : "CONTEXT");
                }
                if (projectEntity && leidenResult != null) {
                    String commId = leidenResult.communityByNode().get(entity.identity());
                    if (commId != null) m.put("communityId", commId);
                    if (!pkgName.isEmpty()) m.put("declaredPackage", pkgName);
                    var drift = misplacedByNode.get(entity.identity());
                    m.put("misplaced", drift != null);
                    if (drift != null) {
                        m.put("misplacedRationale", drift.rationale());
                        m.put("dominantPackage", drift.dominantPackage());
                    }
                } else {
                    if (!pkgName.isEmpty()) m.put("declaredPackage", pkgName);
                    m.put("misplaced", false);
                    m.put("externalUnclustered", true);
                }
                nodeMeta = Collections.unmodifiableMap(m);
            } else {
                nodeMeta = request.scope() == Scope.SPRING_COMPONENT
                        ? Map.of("evidenceCount", entity.evidence().size(), "activationStatus",
                                selectedDefinitions.contains(entity.identity()) ? "SELECTED"
                                        : unknownDefinitions.contains(entity.identity()) ? "UNKNOWN" : "CONTEXT")
                        : Map.of("evidenceCount", entity.evidence().size());
            }

            nodes.add(new VisualGraph.VisualNode(
                entity.identity(),
                label,
                entity.kind(),
                request.scope().name(),
                parentId,
                entity.origin(),
                entity.sourceSet(),
                violations,
                worst,
                isCycle,
                nodeMeta
            ));
        }

        // Add synthesized compound nodes first so Cytoscape renders containers before children
        var allNodes = new ArrayList<VisualGraph.VisualNode>(compoundNodes.values());
        allNodes.addAll(nodes);

        // 5. Build visual edges
        var edges = new ArrayList<VisualGraph.VisualEdge>();
        int bridgeEdgesCount = 0;
        for (var edge : edgeItems) {
            boolean isViolation = violatingEdgeIds.contains(edge.identity());
            boolean isCycle = cycleEdgeIds.contains(edge.identity());

            Map<String, Object> edgeMeta;
            if (mode == GroupBy.COMMUNITY && leidenResult != null) {
                String srcComm = leidenResult.communityByNode().get(edge.source());
                String tgtComm = leidenResult.communityByNode().get(edge.target());
                boolean isBridge = srcComm != null && tgtComm != null && !srcComm.equals(tgtComm);
                if (isBridge) bridgeEdgesCount++;
                var em = new LinkedHashMap<String, Object>();
                em.put("kinds", edge.kinds());
                em.put("supports", edge.supports());
                em.put("regions", edge.regions());
                em.put("evidence", edge.evidence());
                em.put("isBridge", isBridge);
                if (srcComm != null) em.put("sourceCommunity", srcComm);
                if (tgtComm != null) em.put("targetCommunity", tgtComm);
                edgeMeta = Collections.unmodifiableMap(em);
            } else {
                edgeMeta = Map.of("kinds", edge.kinds(), "supports", edge.supports(), "regions", edge.regions(), "evidence", edge.evidence());
            }

            edges.add(new VisualGraph.VisualEdge(
                edge.identity(),
                edge.source(),
                edge.target(),
                edge.kinds().isEmpty() ? "graph.depends-on" : edge.kinds().getFirst(),
                edge.classification().name(),
                isViolation,
                isCycle,
                edge.supportCount(),
                edge.supportsTruncated(),
                edgeMeta
            ));
        }

        // 6. Metadata
        var metadata = new TreeMap<String, Object>();
        metadata.put("scope", request.scope().name());
        metadata.put("projection", request.projection().name());
        metadata.put("context", request.context());
        metadata.put("totalNodes", allNodes.size());
        metadata.put("totalEdges", edges.size());
        metadata.put("compoundNodesCount", compoundNodes.size());
        metadata.put("isTruncated", projectionResult.bounds().truncated());
        metadata.put("status",projectionResult.status().name());
        metadata.put("operation",projectionResult.operation().name());
        metadata.put("bounds",projectionResult.bounds());metadata.put("gaps",projectionResult.gaps());
        metadata.put("cycleStatus",cycleResult.status().name());metadata.put("cycleBounds",cycleResult.bounds());metadata.put("cycleGaps",cycleResult.gaps());
        if(request.scope()==Scope.SPRING_COMPONENT){metadata.put("observedDefinitionCount",observedDefinitionCount);metadata.put("selectedDefinitionCount",selectedDefinitions.size());metadata.put("unknownDefinitionCount",unknownDefinitions.size());}
        if (mode == GroupBy.COMMUNITY) {
            metadata.put("groupBy", GroupBy.COMMUNITY.name());
            metadata.put("externalNodeCount", externalNodeCount);
            if (leidenResult != null) {
                metadata.put("communityAlgorithm", leidenResult.algorithm());
                metadata.put("communityModularity", leidenResult.modularity());
                metadata.put("communityCount", leidenResult.communities().size());
                metadata.put("misplacedCount", leidenResult.misplacedTypes().size());
                metadata.put("bridgeEdgeCount", bridgeEdgesCount);
                metadata.put("communities", leidenResult.communities().stream().limit(100).toList());
                metadata.put("misplacedTypes", leidenResult.misplacedTypes().stream().limit(40).toList());
                metadata.put("communityBridges", leidenResult.bridges().stream().limit(40).toList());
            } else {
                metadata.put("communityAlgorithm", LeidenCommunityDetector.ALGORITHM);
                metadata.put("communityModularity", java.math.BigDecimal.ZERO.setScale(4, java.math.RoundingMode.HALF_UP));
                metadata.put("communityCount", 0);
                metadata.put("misplacedCount", 0);
                metadata.put("bridgeEdgeCount", 0);
                metadata.put("communities", List.of());
                metadata.put("misplacedTypes", List.of());
                metadata.put("communityBridges", List.of());
            }
        }

        ContentDigest graphId = mode == GroupBy.PACKAGE
                ? ContentDigest.sha256Utf8(CanonicalJson.write(List.of(
                        VERSION,
                        request.identity(),
                        allNodes.stream().map(VisualGraph.VisualNode::id).toList(),
                        edges.stream().map(VisualGraph.VisualEdge::id).toList(),
                        projectionResult.identity(), cycleResult.identity(), policy.map(PolicyEvaluation.Result::identity)
                )))
                : ContentDigest.sha256Utf8(CanonicalJson.write(List.of(
                        VERSION,
                        GroupBy.COMMUNITY.name(),
                        request.identity(),
                        allNodes.stream().map(VisualGraph.VisualNode::id).toList(),
                        edges.stream().map(VisualGraph.VisualEdge::id).toList(),
                        projectionResult.identity(), cycleResult.identity(), policy.map(PolicyEvaluation.Result::identity)
                )));

        return new VisualGraph(graphId, allNodes, edges, metadata);
    }

    public VisualGraph focusedSubgraph(Request request, String seedEntity, int depth, Optional<PolicyEvaluation.Result> policy) {
        return focusedSubgraph(request, seedEntity, depth, policy, GroupBy.PACKAGE);
    }

    public VisualGraph focusedSubgraph(Request request, String seedEntity, int depth, Optional<PolicyEvaluation.Result> policy, GroupBy groupBy) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(seedEntity, "seedEntity");

        if(depth<1||depth>=request.limits().maxDepth())throw new IllegalArgumentException("Dependency steps exceed the request vertex-depth bound");
        // A focused depth is a real traversal limit, not just a display label.
        var l=request.limits();var limited=new Request(request.graph(),request.context(),request.scope(),request.projection(),request.direction(),request.relationshipKinds(),
                request.population(),request.includeMust(),request.realization(),new Limits(depth+1,l.maxSteps(),l.maxResults(),l.maxVertices(),l.maxEdges(),l.maxSolverSteps(),l.maxFormulaNodes(),l.maxAuxiliaryStates()));
        var neighborhood = queries.reachability(limited, seedEntity);
        var visitedNodes = new LinkedHashSet<String>();
        visitedNodes.add(seedEntity);

        for(var path:neighborhood.items())visitedNodes.addAll(path.vertices());

        // 2. Fetch full graph projection and filter down to visited nodes
        var fullGraph = project(request, policy, groupBy);
        var filteredNodes = new ArrayList<VisualGraph.VisualNode>();
        var neededParents = new HashSet<String>();

        for (var n : fullGraph.nodes()) {
            if (visitedNodes.contains(n.id())) {
                filteredNodes.add(n);
                n.parent().ifPresent(neededParents::add);
            }
        }

        // Close all ancestor compounds irrespective of their original sort order.
        var byId=new HashMap<String,VisualGraph.VisualNode>();fullGraph.nodes().forEach(n->byId.put(n.id(),n));
        var included=new HashSet<>(visitedNodes);var pending=new ArrayDeque<>(new TreeSet<>(neededParents));
        while(!pending.isEmpty()){var id=pending.removeFirst();var parent=byId.get(id);if(parent!=null&&included.add(id))parent.parent().ifPresent(pending::add);}
        filteredNodes.clear();fullGraph.nodes().stream().filter(n->included.contains(n.id())).forEach(filteredNodes::add);

        var filteredEdges = new ArrayList<VisualGraph.VisualEdge>();
        for (var e : fullGraph.edges()) {
            if (visitedNodes.contains(e.source()) && visitedNodes.contains(e.target())) {
                filteredEdges.add(e);
            }
        }

        var metadata = new TreeMap<>(fullGraph.metadata());
        metadata.put("seedEntity", seedEntity);
        metadata.put("focusedDepth", depth);
        metadata.put("focusedNodes", filteredNodes.size());
        metadata.put("focusedEdges", filteredEdges.size());
        metadata.put("focusStatus",neighborhood.status().name());metadata.put("focusBounds",neighborhood.bounds());metadata.put("focusGaps",neighborhood.gaps());
        metadata.put("isTruncated",Boolean.TRUE.equals(metadata.get("isTruncated"))||neighborhood.bounds().truncated());
        if(neighborhood.status()!=com.evolution.analysis.contract.assessment.AssessmentStatus.COMPLETE)metadata.put("status","PARTIAL");

        ContentDigest id = ContentDigest.sha256Utf8(CanonicalJson.write(List.of(
            VERSION,
            "focused",
            request.identity(),fullGraph.id(),neighborhood.identity(),
            seedEntity,
            depth,
            filteredNodes.stream().map(VisualGraph.VisualNode::id).toList(),
            filteredEdges.stream().map(VisualGraph.VisualEdge::id).toList()
        )));

        return new VisualGraph(id, filteredNodes, filteredEdges, metadata);
    }

    public VisualGraph violationsSubgraph(Request request, PolicyEvaluation.Result policy) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(policy, "policy");

        var violatingVertices = new LinkedHashSet<String>();
        var violatingEdgeIds = new HashSet<String>();

        for (var finding : policy.findings()) {
            var bundle = finding.bundle();
            violatingVertices.addAll(bundle.path());
            for (var edge : bundle.relationships()) {
                violatingEdgeIds.add(edge.identity());
                violatingVertices.add(edge.source());
                violatingVertices.add(edge.target());
            }
        }

        var fullGraph = project(request, Optional.of(policy));
        var filteredNodes = new ArrayList<VisualGraph.VisualNode>();
        var neededParents = new HashSet<String>();

        for (var n : fullGraph.nodes()) {
            if (violatingVertices.contains(n.id())) {
                filteredNodes.add(n);
                n.parent().ifPresent(neededParents::add);
            }
        }

        for (var n : fullGraph.nodes()) {
            if (neededParents.contains(n.id()) && !filteredNodes.contains(n)) {
                filteredNodes.add(n);
                n.parent().ifPresent(neededParents::add);
            }
        }

        var filteredEdges = new ArrayList<VisualGraph.VisualEdge>();
        for (var e : fullGraph.edges()) {
            if (violatingEdgeIds.contains(e.id()) || (violatingVertices.contains(e.source()) && violatingVertices.contains(e.target()))) {
                filteredEdges.add(e);
            }
        }

        var metadata = new TreeMap<>(fullGraph.metadata());
        metadata.put("violationsOnly", true);
        metadata.put("violatingFindingsCount", policy.findings().size());

        ContentDigest id = ContentDigest.sha256Utf8(CanonicalJson.write(List.of(
            VERSION,
            "violations",
            policy.identity(),
            filteredNodes.stream().map(VisualGraph.VisualNode::id).toList(),
            filteredEdges.stream().map(VisualGraph.VisualEdge::id).toList()
        )));

        return new VisualGraph(id, filteredNodes, filteredEdges, metadata);
    }

    /**
     * Multi-scale deterministic graph projection ({@code AUTO | MACRO | MESO | MICRO}) backed by
     * Leiden community clustering and iterative O(V + E) {@link TarjanSccAnalyzer} cycle &amp;
     * condensation-level layout. Uses this service's bound {@link KnowledgeGraph}.
     */
    public VisualGraph projectMultiScale(MultiScaleRequest request) {
        return projectMultiScale(queries.graph(), request);
    }

    /**
     * Multi-scale deterministic graph projection ({@code AUTO | MACRO | MESO | MICRO}) over one
     * context of {@code graph}. Never mutates {@code graph} and emits strictly
     * {@link CanonicalJson}-safe properties and metadata (all coordinates/scores as {@link BigDecimal}).
     */
    public VisualGraph projectMultiScale(KnowledgeGraph graph, MultiScaleRequest request) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(request, "request");

        Runnable checkpoint = () -> {
            var stop = queries.assessmentStopReason();
            if (stop != StopReason.NONE) {
                throw new ProjectionInterrupted(stop);
            }
        };
        checkpoint.run();

        var extracted = ContextGraphExtractor.extract(graph, request.context(), checkpoint);
        var projectEntities = extracted.projectEntities();
        var projectEdges = extracted.projectEdges();
        var externalEntities = extracted.externalEntities();
        int totalProjectNodes = projectEntities.size();
        int totalProjectEdges = projectEdges.size();

        ProjectionLevel resolvedLevel = request.level() == ProjectionLevel.AUTO
                ? (totalProjectNodes > AUTO_MACRO_THRESHOLD ? ProjectionLevel.MACRO : ProjectionLevel.MICRO)
                : request.level();

        String layoutSeed = "tarjan-layered-v1:" + shortId(
                ContentDigest.sha256Utf8(CanonicalJson.write(List.of(
                        graph.identity().value(),
                        request.context(),
                        resolvedLevel.name()))).value());

        if (projectEntities.isEmpty()) {
            var emptyScc = TarjanSccAnalyzer.analyze(List.of(), List.of(), checkpoint);
            return buildMultiScaleVisualGraph(
                    graph, request, resolvedLevel, layoutSeed,
                    0, 0, 0, false, 0, 0,
                    List.of(), List.of(), emptyScc, Optional.empty(), Optional.empty());
        }

        return switch (resolvedLevel) {
            case MACRO -> buildMacroGraph(graph, request, resolvedLevel, layoutSeed, extracted, checkpoint);
            case MESO -> buildMesoGraph(graph, request, resolvedLevel, layoutSeed, extracted, checkpoint);
            case MICRO, AUTO -> buildMicroGraph(graph, request, ProjectionLevel.MICRO, layoutSeed, extracted, checkpoint);
        };
    }

    private VisualGraph buildMacroGraph(
            KnowledgeGraph graph,
            MultiScaleRequest request,
            ProjectionLevel resolvedLevel,
            String layoutSeed,
            ContextGraphExtractor.ExtractedContext extracted,
            Runnable checkpoint) {
        var leiden = runLeidenForContext(request.context(), extracted.projectEntities(), extracted.projectEdges(), checkpoint);
        var communities = leiden.communities();
        var externalEntities = extracted.externalEntities();
        boolean hasExternal = !externalEntities.isEmpty();

        int totalSuperCandidates = communities.size() + (hasExternal ? 1 : 0);
        int maxProjectComms = hasExternal && request.maxNodes() > 1
                ? Math.min(communities.size(), request.maxNodes() - 1)
                : Math.min(communities.size(), request.maxNodes());
        boolean includeExternal = hasExternal && (maxProjectComms < request.maxNodes());
        int cappedNodeCount = Math.max(0, totalSuperCandidates - (maxProjectComms + (includeExternal ? 1 : 0)));

        var keptComms = communities.subList(0, maxProjectComms);
        var keptSuperIds = new LinkedHashSet<String>();
        var commBySuperId = new LinkedHashMap<String, LeidenCommunityDetector.EmergentCommunity>();
        for (var comm : keptComms) {
            String superId = COMMUNITY_ID_PREFIX + comm.id();
            keptSuperIds.add(superId);
            commBySuperId.put(superId, comm);
        }
        if (includeExternal) {
            keptSuperIds.add(EXTERNAL_SUPER_NODE_ID);
        }

        // Map each entity to its super-node ID
        var entityToSuperId = new HashMap<String, String>(extracted.allEntities().size() * 2);
        for (var e : extracted.projectEntities()) {
            String cid = leiden.communityByNode().get(e.id());
            if (cid != null) {
                String superId = COMMUNITY_ID_PREFIX + cid;
                if (keptSuperIds.contains(superId)) {
                    entityToSuperId.put(e.id(), superId);
                }
            }
        }
        if (includeExternal) {
            for (var ext : externalEntities) {
                entityToSuperId.put(ext.id(), EXTERNAL_SUPER_NODE_ID);
            }
        }

        // Aggregate inter-community edges
        record SuperEdgeAcc(String source, String target, int weight, boolean violation, TrustTier trustTier) {}
        var superEdgeMap = new TreeMap<String, SuperEdgeAcc>();
        for (var edge : extracted.allEdges()) {
            String srcSuper = entityToSuperId.get(edge.source());
            String tgtSuper = entityToSuperId.get(edge.target());
            if (srcSuper == null || tgtSuper == null || srcSuper.equals(tgtSuper)) continue;
            boolean violated = ContextGraphExtractor.isEdgeViolated(edge, request.violatedEdgeKeys())
                    || request.violatedEdgeKeys().contains(TarjanSccAnalyzer.edgeKey(srcSuper, tgtSuper))
                    || request.violatedEdgeKeys().contains(srcSuper + "|" + tgtSuper);
            String key = srcSuper + "|" + tgtSuper;
            var prev = superEdgeMap.get(key);
            if (prev == null) {
                superEdgeMap.put(key, new SuperEdgeAcc(srcSuper, tgtSuper, edge.supportCount(), violated, edge.trustTier()));
            } else {
                superEdgeMap.put(key, new SuperEdgeAcc(
                        srcSuper,
                        tgtSuper,
                        prev.weight() + edge.supportCount(),
                        prev.violation() || violated,
                        ContextGraphExtractor.combineTrustTier(prev.trustTier(), edge.trustTier())));
            }
        }

        // Run O(V + E) iterative Tarjan SCC on the community super-node graph
        var tarjanEdges = new ArrayList<TarjanSccAnalyzer.DirectedEdge>(superEdgeMap.size());
        for (var se : superEdgeMap.values()) {
            tarjanEdges.add(new TarjanSccAnalyzer.DirectedEdge(se.source(), se.target(), se.weight()));
        }
        var superScc = TarjanSccAnalyzer.analyze(keptSuperIds, tarjanEdges, checkpoint);

        // Sort & cap super-edges honestly
        var sortedSuperEdges = new ArrayList<>(superEdgeMap.values());
        sortedSuperEdges.sort(Comparator
                .comparing((SuperEdgeAcc e) -> !e.violation())
                .thenComparing(e -> !superScc.cyclicEdgeKeys().contains(TarjanSccAnalyzer.edgeKey(e.source(), e.target())))
                .thenComparing(Comparator.comparingInt(SuperEdgeAcc::weight).reversed())
                .thenComparing(SuperEdgeAcc::source)
                .thenComparing(SuperEdgeAcc::target));

        int totalSuperEdges = sortedSuperEdges.size();
        int keptEdgeLimit = Math.min(totalSuperEdges, request.maxEdges());
        int cappedEdgeCount = Math.max(0, totalSuperEdges - keptEdgeLimit);
        var keptSuperEdges = sortedSuperEdges.subList(0, keptEdgeLimit);

        // Compute super-node degrees & deterministic layered coordinates
        var inDeg = new HashMap<String, Integer>();
        var outDeg = new HashMap<String, Integer>();
        for (var se : keptSuperEdges) {
            outDeg.merge(se.source(), 1, Integer::sum);
            inDeg.merge(se.target(), 1, Integer::sum);
        }
        var positions = computeLayeredPositions(superScc, includeExternal ? EXTERNAL_SUPER_NODE_ID : null, 280.0, 240.0);

        // Compute entity degree for choosing informative deterministic sampleMembers
        var entityDeg = computeEntityDegrees(extracted.projectEdges());

        // Emit compact Community Super-Nodes
        var visualNodes = new ArrayList<VisualGraph.VisualNode>(keptSuperIds.size());
        for (var entry : commBySuperId.entrySet()) {
            String superId = entry.getKey();
            var comm = entry.getValue();
            TrustTier tier = null;
            int violSum = 0;
            for (String mid : comm.members()) {
                var ent = extracted.allEntities().get(mid);
                if (ent != null) {
                    tier = ContextGraphExtractor.combineTrustTier(tier, ent.trustTier());
                }
                violSum += Math.max(0, request.nodeViolationCounts().getOrDefault(mid, 0));
            }
            if (tier == null) tier = TrustTier.COMPILER;

            var sampleMembers = selectSampleMembers(comm.members(), extracted.allEntities(), entityDeg, 5);
            int sccIdx = superScc.nodeToBlockIndex().getOrDefault(superId, 0);
            int sccLevel = sccIdx < superScc.blocks().size() ? superScc.blocks().get(sccIdx).level() : 0;
            Point2D pos = positions.getOrDefault(superId, Point2D.ORIGIN);

            var props = new TreeMap<String, Object>();
            props.put("x", pos.x());
            props.put("y", pos.y());
            props.put("memberCount", comm.memberCount());
            props.put("internalEdges", comm.internalEdgeCount());
            props.put("cohesion", comm.cohesion());
            props.put("communityId", comm.id());
            if (!comm.dominantPackage().isEmpty()) {
                props.put("dominantPackage", comm.dominantPackage());
            }
            props.put("trustTier", tier.name());
            props.put("expandable", true);
            props.put("sampleMembers", sampleMembers);
            props.put("sccIndex", sccIdx);
            props.put("sccLevel", sccLevel);
            props.put("inDegree", inDeg.getOrDefault(superId, 0));
            props.put("outDegree", outDeg.getOrDefault(superId, 0));

            visualNodes.add(new VisualGraph.VisualNode(
                    superId,
                    comm.label(),
                    COMMUNITY_SUPER_NODE_KIND,
                    "COMMUNITY",
                    Optional.empty(),
                    Optional.of("PROJECT"),
                    Optional.empty(),
                    violSum,
                    violSum > 0 ? Optional.of("ERROR") : Optional.empty(),
                    superScc.cyclicNodeIds().contains(superId),
                    Collections.unmodifiableMap(props)));
        }

        if (includeExternal) {
            TrustTier extTier = null;
            var extIds = new ArrayList<String>(externalEntities.size());
            for (var ext : externalEntities) {
                extTier = ContextGraphExtractor.combineTrustTier(extTier, ext.trustTier());
                extIds.add(ext.id());
            }
            if (extTier == null) extTier = TrustTier.COMPILER;
            int sccIdx = superScc.nodeToBlockIndex().getOrDefault(EXTERNAL_SUPER_NODE_ID, 0);
            int sccLevel = sccIdx < superScc.blocks().size() ? superScc.blocks().get(sccIdx).level() : 0;
            Point2D pos = positions.getOrDefault(EXTERNAL_SUPER_NODE_ID, Point2D.ORIGIN);

            var props = new TreeMap<String, Object>();
            props.put("x", pos.x());
            props.put("y", pos.y());
            props.put("memberCount", externalEntities.size());
            props.put("internalEdges", 0);
            props.put("cohesion", BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
            props.put("communityId", "external");
            props.put("trustTier", extTier.name());
            props.put("expandable", false);
            props.put("externalGroup", true);
            props.put("sampleMembers", selectSampleMembers(extIds, extracted.allEntities(), entityDeg, 5));
            props.put("sccIndex", sccIdx);
            props.put("sccLevel", sccLevel);
            props.put("inDegree", inDeg.getOrDefault(EXTERNAL_SUPER_NODE_ID, 0));
            props.put("outDegree", outDeg.getOrDefault(EXTERNAL_SUPER_NODE_ID, 0));

            visualNodes.add(new VisualGraph.VisualNode(
                    EXTERNAL_SUPER_NODE_ID,
                    "External Dependencies (" + externalEntities.size() + ")",
                    COMMUNITY_SUPER_NODE_KIND,
                    "EXTERNAL",
                    Optional.empty(),
                    Optional.of("DEPENDENCY"),
                    Optional.empty(),
                    0,
                    Optional.empty(),
                    superScc.cyclicNodeIds().contains(EXTERNAL_SUPER_NODE_ID),
                    Collections.unmodifiableMap(props)));
        }

        // Emit aggregated inter-community edges
        var visualEdges = new ArrayList<VisualGraph.VisualEdge>(keptSuperEdges.size());
        for (var se : keptSuperEdges) {
            boolean isCycle = superScc.cyclicEdgeKeys().contains(TarjanSccAnalyzer.edgeKey(se.source(), se.target()));
            var eProps = new TreeMap<String, Object>();
            eProps.put("trustTier", se.trustTier().name());
            eProps.put("weight", se.weight());
            eProps.put("aggregated", true);
            visualEdges.add(new VisualGraph.VisualEdge(
                    "edge:" + se.source() + "->" + se.target(),
                    se.source(),
                    se.target(),
                    "graph.depends-on",
                    "MUST",
                    se.violation(),
                    isCycle,
                    se.weight(),
                    false,
                    Collections.unmodifiableMap(eProps)));
        }

        boolean truncated = cappedNodeCount > 0 || cappedEdgeCount > 0;
        return buildMultiScaleVisualGraph(
                graph, request, resolvedLevel, layoutSeed,
                extracted.projectEntities().size(), extracted.projectEdges().size(),
                visualNodes.size(), truncated, cappedNodeCount, cappedEdgeCount,
                visualNodes, visualEdges, superScc,
                Optional.of(communities.size()), Optional.empty());
    }

    private VisualGraph buildMesoGraph(
            KnowledgeGraph graph,
            MultiScaleRequest request,
            ProjectionLevel resolvedLevel,
            String layoutSeed,
            ContextGraphExtractor.ExtractedContext extracted,
            Runnable checkpoint) {
        var leiden = runLeidenForContext(request.context(), extracted.projectEntities(), extracted.projectEdges(), checkpoint);
        var communities = leiden.communities();
        var externalEntities = extracted.externalEntities();
        boolean hasExternal = !externalEntities.isEmpty();

        var expandSet = new LinkedHashSet<String>();
        for (String raw : request.expandCommunities()) {
            String stripped = raw.startsWith(COMMUNITY_ID_PREFIX) ? raw.substring(COMMUNITY_ID_PREFIX.length()) : raw;
            expandSet.add(stripped);
            expandSet.add(raw);
        }

        // Compute deterministic MACRO anchor positions for all communities first (1.5x spread for breathing room)
        var allCommSuperIds = new LinkedHashSet<String>();
        for (var comm : communities) {
            allCommSuperIds.add(COMMUNITY_ID_PREFIX + comm.id());
        }
        if (hasExternal) allCommSuperIds.add(EXTERNAL_SUPER_NODE_ID);

        var commDirected = new ArrayList<TarjanSccAnalyzer.DirectedEdge>();
        var entityToCommSuper = new HashMap<String, String>(extracted.allEntities().size() * 2);
        for (var e : extracted.projectEntities()) {
            String cid = leiden.communityByNode().get(e.id());
            if (cid != null) entityToCommSuper.put(e.id(), COMMUNITY_ID_PREFIX + cid);
        }
        if (hasExternal) {
            for (var ext : externalEntities) entityToCommSuper.put(ext.id(), EXTERNAL_SUPER_NODE_ID);
        }
        for (var edge : extracted.allEdges()) {
            String s = entityToCommSuper.get(edge.source());
            String t = entityToCommSuper.get(edge.target());
            if (s != null && t != null && !s.equals(t)) {
                commDirected.add(new TarjanSccAnalyzer.DirectedEdge(s, t, edge.supportCount()));
            }
        }
        var anchorScc = TarjanSccAnalyzer.analyze(allCommSuperIds, commDirected, checkpoint);
        var anchorPositions = computeLayeredPositions(anchorScc, hasExternal ? EXTERNAL_SUPER_NODE_ID : null, 420.0, 340.0);

        // Partition communities into collapsed super-nodes vs expanded member lists
        var collapsedComms = new ArrayList<LeidenCommunityDetector.EmergentCommunity>();
        var expandedComms = new ArrayList<LeidenCommunityDetector.EmergentCommunity>();
        for (var comm : communities) {
            if (expandSet.contains(comm.id()) || expandSet.contains(COMMUNITY_ID_PREFIX + comm.id())) {
                expandedComms.add(comm);
            } else {
                collapsedComms.add(comm);
            }
        }

        int collapsedTargetCount = collapsedComms.size() + (hasExternal ? 1 : 0);
        int expandedMemberTotal = 0;
        for (var comm : expandedComms) expandedMemberTotal += comm.members().size();
        int totalDesiredNodes = collapsedTargetCount + expandedMemberTotal;

        // Allocate node budget: keep collapsed super-nodes first, then fill with top members of expanded communities
        int budget = request.maxNodes();
        int keepCollapsedComms = Math.min(collapsedComms.size(), hasExternal && budget > 1 ? budget - 1 : budget);
        boolean includeExternal = hasExternal && (keepCollapsedComms < budget);
        int remainingForMembers = Math.max(0, budget - keepCollapsedComms - (includeExternal ? 1 : 0));

        var keptCollapsedSuperIds = new LinkedHashSet<String>();
        var keptCollapsedMap = new LinkedHashMap<String, LeidenCommunityDetector.EmergentCommunity>();
        for (int i = 0; i < keepCollapsedComms; i++) {
            var comm = collapsedComms.get(i);
            String superId = COMMUNITY_ID_PREFIX + comm.id();
            keptCollapsedSuperIds.add(superId);
            keptCollapsedMap.put(superId, comm);
        }
        if (includeExternal) {
            keptCollapsedSuperIds.add(EXTERNAL_SUPER_NODE_ID);
        }

        var entityDeg = computeEntityDegrees(extracted.projectEdges());
        var expandedMembersByComm = new LinkedHashMap<String, List<ContextGraphExtractor.ExtractedEntity>>();
        var keptMemberIds = new LinkedHashSet<String>();

        if (!expandedComms.isEmpty() && remainingForMembers > 0) {
            // Fair deterministic allocation across expanded communities
            int perCommQuota = Math.max(1, remainingForMembers / expandedComms.size());
            int extra = remainingForMembers - perCommQuota * expandedComms.size();
            for (var comm : expandedComms) {
                int quota = perCommQuota + (extra > 0 ? 1 : 0);
                if (extra > 0) extra--;
                var ranked = comm.members().stream()
                        .map(extracted.allEntities()::get)
                        .filter(Objects::nonNull)
                        .sorted(Comparator
                                .comparingInt((ContextGraphExtractor.ExtractedEntity e) ->
                                        request.nodeViolationCounts().getOrDefault(e.id(), 0)).reversed()
                                .thenComparing(Comparator.comparingInt((ContextGraphExtractor.ExtractedEntity e) ->
                                        entityDeg.getOrDefault(e.id(), 0)).reversed())
                                .thenComparing(ContextGraphExtractor.ExtractedEntity::id))
                        .limit(quota)
                        .toList();
                expandedMembersByComm.put(comm.id(), ranked);
                for (var ent : ranked) keptMemberIds.add(ent.id());
            }
        }

        int keptNodeTotal = keptCollapsedSuperIds.size() + keptMemberIds.size();
        int cappedNodeCount = Math.max(0, totalDesiredNodes - keptNodeTotal);

        // Map each entity in the graph to its displayed node ID in the hybrid MESO graph
        var entityToDisplayId = new HashMap<String, String>(extracted.allEntities().size() * 2);
        for (String memberId : keptMemberIds) {
            entityToDisplayId.put(memberId, memberId);
        }
        for (var e : extracted.projectEntities()) {
            if (entityToDisplayId.containsKey(e.id())) continue;
            String cid = leiden.communityByNode().get(e.id());
            if (cid != null) {
                String superId = COMMUNITY_ID_PREFIX + cid;
                if (keptCollapsedSuperIds.contains(superId)) {
                    entityToDisplayId.put(e.id(), superId);
                }
            }
        }
        if (includeExternal) {
            for (var ext : externalEntities) {
                entityToDisplayId.put(ext.id(), EXTERNAL_SUPER_NODE_ID);
            }
        }

        // Aggregate hybrid edges (member<->member, member<->super, super<->super)
        record MesoEdgeAcc(
                String source,
                String target,
                String primaryKind,
                String classification,
                int weight,
                boolean violation,
                boolean aggregated,
                TrustTier trustTier) {}
        var mesoEdgeMap = new TreeMap<String, MesoEdgeAcc>();
        for (var edge : extracted.allEdges()) {
            String src = entityToDisplayId.get(edge.source());
            String tgt = entityToDisplayId.get(edge.target());
            if (src == null || tgt == null) continue;
            boolean involvesSuper = keptCollapsedSuperIds.contains(src) || keptCollapsedSuperIds.contains(tgt);
            if (src.equals(tgt) && involvesSuper) continue;
            boolean violated = ContextGraphExtractor.isEdgeViolated(edge, request.violatedEdgeKeys())
                    || request.violatedEdgeKeys().contains(TarjanSccAnalyzer.edgeKey(src, tgt))
                    || request.violatedEdgeKeys().contains(src + "|" + tgt);
            String key = src + "|" + tgt;
            var prev = mesoEdgeMap.get(key);
            if (prev == null) {
                mesoEdgeMap.put(key, new MesoEdgeAcc(
                        src, tgt,
                        involvesSuper ? "graph.depends-on" : edge.primaryKind(),
                        involvesSuper ? "MUST" : edge.classification(),
                        edge.supportCount(),
                        violated,
                        involvesSuper,
                        edge.trustTier()));
            } else {
                mesoEdgeMap.put(key, new MesoEdgeAcc(
                        src, tgt,
                        "graph.depends-on",
                        prev.classification(),
                        prev.weight() + edge.supportCount(),
                        prev.violation() || violated,
                        true,
                        ContextGraphExtractor.combineTrustTier(prev.trustTier(), edge.trustTier())));
            }
        }

        var displayedNodeIds = new LinkedHashSet<String>(keptCollapsedSuperIds);
        displayedNodeIds.addAll(keptMemberIds);

        var tarjanEdges = new ArrayList<TarjanSccAnalyzer.DirectedEdge>(mesoEdgeMap.size());
        for (var me : mesoEdgeMap.values()) {
            tarjanEdges.add(new TarjanSccAnalyzer.DirectedEdge(me.source(), me.target(), me.weight()));
        }
        var mesoScc = TarjanSccAnalyzer.analyze(displayedNodeIds, tarjanEdges, checkpoint);

        // Sort & cap hybrid edges honestly
        var sortedEdges = new ArrayList<>(mesoEdgeMap.values());
        sortedEdges.sort(Comparator
                .comparing((MesoEdgeAcc e) -> !e.violation())
                .thenComparing(e -> !mesoScc.cyclicEdgeKeys().contains(TarjanSccAnalyzer.edgeKey(e.source(), e.target())))
                .thenComparing(Comparator.comparingInt(MesoEdgeAcc::weight).reversed())
                .thenComparing(MesoEdgeAcc::source)
                .thenComparing(MesoEdgeAcc::target));
        int keptEdgeLimit = Math.min(sortedEdges.size(), request.maxEdges());
        int cappedEdgeCount = Math.max(0, sortedEdges.size() - keptEdgeLimit);
        var keptEdges = sortedEdges.subList(0, keptEdgeLimit);

        var inDeg = new HashMap<String, Integer>();
        var outDeg = new HashMap<String, Integer>();
        for (var me : keptEdges) {
            outDeg.merge(me.source(), 1, Integer::sum);
            inDeg.merge(me.target(), 1, Integer::sum);
        }

        // Build clustered (x, y) coordinates for expanded member nodes around their community anchor
        var memberPositions = new HashMap<String, Point2D>(keptMemberIds.size() * 2);
        for (var entry : expandedMembersByComm.entrySet()) {
            String cid = entry.getKey();
            var members = entry.getValue();
            Point2D anchor = anchorPositions.getOrDefault(COMMUNITY_ID_PREFIX + cid, Point2D.ORIGIN);
            double cx = anchor.x().doubleValue();
            double cy = anchor.y().doubleValue();
            var ordered = new ArrayList<>(members);
            ordered.sort(Comparator
                    .comparingInt((ContextGraphExtractor.ExtractedEntity e) ->
                            mesoScc.nodeToBlockIndex().getOrDefault(e.id(), 0))
                    .thenComparing(ContextGraphExtractor.ExtractedEntity::id));
            for (int k = 0; k < ordered.size(); k++) {
                double angle = k * GOLDEN_ANGLE;
                double radius = 58.0 * StrictMath.sqrt(k + 1.0);
                double px = cx + radius * StrictMath.cos(angle);
                double py = cy + radius * StrictMath.sin(angle);
                memberPositions.put(ordered.get(k).id(), Point2D.of(px, py));
            }
        }

        var visualNodes = new ArrayList<VisualGraph.VisualNode>(displayedNodeIds.size());
        // Collapsed community super-nodes
        for (var entry : keptCollapsedMap.entrySet()) {
            String superId = entry.getKey();
            var comm = entry.getValue();
            TrustTier tier = null;
            int violSum = 0;
            for (String mid : comm.members()) {
                var ent = extracted.allEntities().get(mid);
                if (ent != null) tier = ContextGraphExtractor.combineTrustTier(tier, ent.trustTier());
                violSum += Math.max(0, request.nodeViolationCounts().getOrDefault(mid, 0));
            }
            if (tier == null) tier = TrustTier.COMPILER;
            Point2D pos = anchorPositions.getOrDefault(superId, Point2D.ORIGIN);
            int sccIdx = mesoScc.nodeToBlockIndex().getOrDefault(superId, 0);
            int sccLevel = sccIdx < mesoScc.blocks().size() ? mesoScc.blocks().get(sccIdx).level() : 0;

            var props = new TreeMap<String, Object>();
            props.put("x", pos.x());
            props.put("y", pos.y());
            props.put("memberCount", comm.memberCount());
            props.put("internalEdges", comm.internalEdgeCount());
            props.put("cohesion", comm.cohesion());
            props.put("communityId", comm.id());
            if (!comm.dominantPackage().isEmpty()) props.put("dominantPackage", comm.dominantPackage());
            props.put("trustTier", tier.name());
            props.put("expandable", true);
            props.put("sampleMembers", selectSampleMembers(comm.members(), extracted.allEntities(), entityDeg, 5));
            props.put("sccIndex", sccIdx);
            props.put("sccLevel", sccLevel);
            props.put("inDegree", inDeg.getOrDefault(superId, 0));
            props.put("outDegree", outDeg.getOrDefault(superId, 0));

            visualNodes.add(new VisualGraph.VisualNode(
                    superId, comm.label(), COMMUNITY_SUPER_NODE_KIND, "COMMUNITY",
                    Optional.empty(), Optional.of("PROJECT"), Optional.empty(),
                    violSum, violSum > 0 ? Optional.of("ERROR") : Optional.empty(),
                    mesoScc.cyclicNodeIds().contains(superId), Collections.unmodifiableMap(props)));
        }

        if (includeExternal) {
            TrustTier extTier = null;
            var extIds = new ArrayList<String>(externalEntities.size());
            for (var ext : externalEntities) {
                extTier = ContextGraphExtractor.combineTrustTier(extTier, ext.trustTier());
                extIds.add(ext.id());
            }
            if (extTier == null) extTier = TrustTier.COMPILER;
            Point2D pos = anchorPositions.getOrDefault(EXTERNAL_SUPER_NODE_ID, Point2D.ORIGIN);
            int sccIdx = mesoScc.nodeToBlockIndex().getOrDefault(EXTERNAL_SUPER_NODE_ID, 0);
            int sccLevel = sccIdx < mesoScc.blocks().size() ? mesoScc.blocks().get(sccIdx).level() : 0;

            var props = new TreeMap<String, Object>();
            props.put("x", pos.x());
            props.put("y", pos.y());
            props.put("memberCount", externalEntities.size());
            props.put("internalEdges", 0);
            props.put("cohesion", BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
            props.put("communityId", "external");
            props.put("trustTier", extTier.name());
            props.put("expandable", false);
            props.put("externalGroup", true);
            props.put("sampleMembers", selectSampleMembers(extIds, extracted.allEntities(), entityDeg, 5));
            props.put("sccIndex", sccIdx);
            props.put("sccLevel", sccLevel);
            props.put("inDegree", inDeg.getOrDefault(EXTERNAL_SUPER_NODE_ID, 0));
            props.put("outDegree", outDeg.getOrDefault(EXTERNAL_SUPER_NODE_ID, 0));

            visualNodes.add(new VisualGraph.VisualNode(
                    EXTERNAL_SUPER_NODE_ID,
                    "External Dependencies (" + externalEntities.size() + ")",
                    COMMUNITY_SUPER_NODE_KIND, "EXTERNAL",
                    Optional.empty(), Optional.of("DEPENDENCY"), Optional.empty(),
                    0, Optional.empty(), mesoScc.cyclicNodeIds().contains(EXTERNAL_SUPER_NODE_ID),
                    Collections.unmodifiableMap(props)));
        }

        // Expanded concrete member nodes
        for (var entry : expandedMembersByComm.entrySet()) {
            String cid = entry.getKey();
            for (var ent : entry.getValue()) {
                Point2D pos = memberPositions.getOrDefault(ent.id(), Point2D.ORIGIN);
                int viol = Math.max(0, request.nodeViolationCounts().getOrDefault(ent.id(), 0));
                int sccIdx = mesoScc.nodeToBlockIndex().getOrDefault(ent.id(), 0);
                int sccLevel = sccIdx < mesoScc.blocks().size() ? mesoScc.blocks().get(sccIdx).level() : 0;

                var props = new TreeMap<String, Object>();
                props.put("x", pos.x());
                props.put("y", pos.y());
                props.put("communityId", cid);
                props.put("expandedFromCommunity", cid);
                if (!ent.packageName().isEmpty()) props.put("packageName", ent.packageName());
                if (!ent.moduleName().isEmpty()) props.put("moduleName", ent.moduleName());
                props.put("trustTier", ent.trustTier().name());
                props.put("sccIndex", sccIdx);
                props.put("sccLevel", sccLevel);
                props.put("inDegree", inDeg.getOrDefault(ent.id(), 0));
                props.put("outDegree", outDeg.getOrDefault(ent.id(), 0));

                visualNodes.add(new VisualGraph.VisualNode(
                        ent.id(),
                        ent.shortLabel(),
                        ent.kind(),
                        "TYPE",
                        Optional.empty(),
                        Optional.of(ent.origin()),
                        ent.sourceSet(),
                        viol,
                        viol > 0 ? Optional.of("ERROR") : Optional.empty(),
                        mesoScc.cyclicNodeIds().contains(ent.id()),
                        Collections.unmodifiableMap(props)));
            }
        }

        var visualEdges = new ArrayList<VisualGraph.VisualEdge>(keptEdges.size());
        for (var me : keptEdges) {
            boolean isCycle = mesoScc.cyclicEdgeKeys().contains(TarjanSccAnalyzer.edgeKey(me.source(), me.target()));
            var eProps = new TreeMap<String, Object>();
            eProps.put("trustTier", me.trustTier().name());
            eProps.put("weight", me.weight());
            eProps.put("aggregated", me.aggregated());
            visualEdges.add(new VisualGraph.VisualEdge(
                    "edge:" + me.source() + "->" + me.target(),
                    me.source(),
                    me.target(),
                    me.primaryKind(),
                    me.classification(),
                    me.violation(),
                    isCycle,
                    me.weight(),
                    false,
                    Collections.unmodifiableMap(eProps)));
        }

        boolean truncated = cappedNodeCount > 0 || cappedEdgeCount > 0;
        return buildMultiScaleVisualGraph(
                graph, request, resolvedLevel, layoutSeed,
                extracted.projectEntities().size(), extracted.projectEdges().size(),
                keptCollapsedSuperIds.size(), truncated, cappedNodeCount, cappedEdgeCount,
                visualNodes, visualEdges, mesoScc,
                Optional.of(communities.size()), Optional.empty());
    }

    private VisualGraph buildMicroGraph(
            KnowledgeGraph graph,
            MultiScaleRequest request,
            ProjectionLevel resolvedLevel,
            String layoutSeed,
            ContextGraphExtractor.ExtractedContext extracted,
            Runnable checkpoint) {
        var allEntities = extracted.allEntities();
        var allEdges = extracted.allEdges();
        var entityDeg = computeEntityDegrees(allEdges);

        var keptIds = new LinkedHashSet<String>();
        var focusSeeds = new LinkedHashSet<String>();
        int cappedNodeCount = 0;

        if (!request.focusNodes().isEmpty()) {
            // Bounded 1-2 hop undirected neighborhood around focusNodes
            var undirected = new HashMap<String, TreeSet<String>>();
            for (var edge : allEdges) {
                undirected.computeIfAbsent(edge.source(), k -> new TreeSet<>()).add(edge.target());
                undirected.computeIfAbsent(edge.target(), k -> new TreeSet<>()).add(edge.source());
            }
            Comparator<String> rankByImportance = Comparator
                    .comparingInt((String id) -> request.nodeViolationCounts().getOrDefault(id, 0)).reversed()
                    .thenComparing(Comparator.comparingInt((String id) -> entityDeg.getOrDefault(id, 0)).reversed())
                    .thenComparing(Comparator.naturalOrder());
            var directSeeds = new LinkedHashSet<String>();
            var containerSeeds = new ArrayList<String>();
            var containerSeedSet = new HashSet<String>();
            for (String f : request.focusNodes()) {
                if (allEntities.containsKey(f)) {
                    directSeeds.add(f);
                } else {
                    for (var ent : extracted.projectEntities()) {
                        if (matchesContainerFocus(ent, f)
                                && !directSeeds.contains(ent.id())
                                && containerSeedSet.add(ent.id())) {
                            containerSeeds.add(ent.id());
                        }
                    }
                }
            }
            containerSeeds.sort(rankByImportance);
            var hop0 = new ArrayList<String>(directSeeds.size() + containerSeeds.size());
            hop0.addAll(directSeeds);
            hop0.addAll(containerSeeds);
            focusSeeds.addAll(hop0);
            var reachable2Hops = new LinkedHashSet<String>(hop0);
            var hop1 = new ArrayList<String>();
            for (String u : hop0) {
                for (String v : undirected.getOrDefault(u, new TreeSet<>())) {
                    if (reachable2Hops.add(v)) hop1.add(v);
                }
            }
            var hop2 = new ArrayList<String>();
            for (String u : hop1) {
                for (String v : undirected.getOrDefault(u, new TreeSet<>())) {
                    if (reachable2Hops.add(v)) hop2.add(v);
                }
            }
            hop1.sort(rankByImportance);
            hop2.sort(rankByImportance);

            for (String id : hop0) {
                if (keptIds.size() >= request.maxNodes()) break;
                keptIds.add(id);
            }
            for (String id : hop1) {
                if (keptIds.size() >= request.maxNodes()) break;
                keptIds.add(id);
            }
            for (String id : hop2) {
                if (keptIds.size() >= request.maxNodes()) break;
                keptIds.add(id);
            }
            cappedNodeCount = Math.max(0, reachable2Hops.size() - keptIds.size());
        } else {
            var projectEntities = extracted.projectEntities();
            if (projectEntities.size() <= request.maxNodes()) {
                for (var ent : projectEntities) keptIds.add(ent.id());
            } else {
                var ranked = projectEntities.stream()
                        .sorted(Comparator
                                .comparingInt((ContextGraphExtractor.ExtractedEntity e) ->
                                        request.nodeViolationCounts().getOrDefault(e.id(), 0)).reversed()
                                .thenComparing(Comparator.comparingInt((ContextGraphExtractor.ExtractedEntity e) ->
                                        entityDeg.getOrDefault(e.id(), 0)).reversed())
                                .thenComparing(ContextGraphExtractor.ExtractedEntity::id))
                        .limit(request.maxNodes())
                        .toList();
                for (var ent : ranked) keptIds.add(ent.id());
                cappedNodeCount = projectEntities.size() - keptIds.size();
            }
        }

        // Gather induced edges & run O(V + E) iterative Tarjan SCC
        var inducedEdges = new ArrayList<ContextGraphExtractor.ExtractedEdge>();
        var tarjanEdges = new ArrayList<TarjanSccAnalyzer.DirectedEdge>();
        for (var edge : allEdges) {
            if (keptIds.contains(edge.source()) && keptIds.contains(edge.target())) {
                inducedEdges.add(edge);
                tarjanEdges.add(new TarjanSccAnalyzer.DirectedEdge(edge.source(), edge.target(), edge.supportCount()));
            }
        }
        var microScc = TarjanSccAnalyzer.analyze(keptIds, tarjanEdges, checkpoint);

        // Sort & cap induced edges honestly
        inducedEdges.sort(Comparator
                .comparing((ContextGraphExtractor.ExtractedEdge e) ->
                        !ContextGraphExtractor.isEdgeViolated(e, request.violatedEdgeKeys()))
                .thenComparing(e -> !microScc.cyclicEdgeKeys().contains(TarjanSccAnalyzer.edgeKey(e.source(), e.target())))
                .thenComparing(Comparator.comparingInt(ContextGraphExtractor.ExtractedEdge::supportCount).reversed())
                .thenComparing(ContextGraphExtractor.ExtractedEdge::source)
                .thenComparing(ContextGraphExtractor.ExtractedEdge::target));
        int keptEdgeLimit = Math.min(inducedEdges.size(), request.maxEdges());
        int cappedEdgeCount = Math.max(0, inducedEdges.size() - keptEdgeLimit);
        var keptEdges = inducedEdges.subList(0, keptEdgeLimit);

        var inDeg = new HashMap<String, Integer>();
        var outDeg = new HashMap<String, Integer>();
        for (var e : keptEdges) {
            outDeg.merge(e.source(), 1, Integer::sum);
            inDeg.merge(e.target(), 1, Integer::sum);
        }

        var positions = computeLayeredPositions(microScc, null, 200.0, 175.0);

        var visualNodes = new ArrayList<VisualGraph.VisualNode>(keptIds.size());
        for (String id : microScc.topologicalOrder()) {
            var ent = allEntities.get(id);
            if (ent == null) continue;
            Point2D pos = positions.getOrDefault(id, Point2D.ORIGIN);
            int viol = Math.max(0, request.nodeViolationCounts().getOrDefault(id, 0));
            int sccIdx = microScc.nodeToBlockIndex().getOrDefault(id, 0);
            int sccLevel = sccIdx < microScc.blocks().size() ? microScc.blocks().get(sccIdx).level() : 0;

            var props = new TreeMap<String, Object>();
            props.put("x", pos.x());
            props.put("y", pos.y());
            props.put("trustTier", ent.trustTier().name());
            if (!ent.packageName().isEmpty()) props.put("packageName", ent.packageName());
            if (!ent.moduleName().isEmpty()) props.put("moduleName", ent.moduleName());
            if (focusSeeds.contains(id)) props.put("focusSeed", true);
            props.put("sccIndex", sccIdx);
            props.put("sccLevel", sccLevel);
            props.put("inDegree", inDeg.getOrDefault(id, 0));
            props.put("outDegree", outDeg.getOrDefault(id, 0));

            visualNodes.add(new VisualGraph.VisualNode(
                    ent.id(),
                    ent.shortLabel(),
                    ent.kind(),
                    "TYPE",
                    Optional.empty(),
                    Optional.of(ent.origin()),
                    ent.sourceSet(),
                    viol,
                    viol > 0 ? Optional.of("ERROR") : Optional.empty(),
                    microScc.cyclicNodeIds().contains(id),
                    Collections.unmodifiableMap(props)));
        }

        var visualEdges = new ArrayList<VisualGraph.VisualEdge>(keptEdges.size());
        for (var e : keptEdges) {
            boolean isViolation = ContextGraphExtractor.isEdgeViolated(e, request.violatedEdgeKeys());
            boolean isCycle = microScc.cyclicEdgeKeys().contains(TarjanSccAnalyzer.edgeKey(e.source(), e.target()));
            var eProps = new TreeMap<String, Object>();
            eProps.put("trustTier", e.trustTier().name());
            eProps.put("weight", e.supportCount());
            visualEdges.add(new VisualGraph.VisualEdge(
                    "edge:" + e.source() + "->" + e.target(),
                    e.source(),
                    e.target(),
                    e.primaryKind(),
                    e.classification(),
                    isViolation,
                    isCycle,
                    e.supportCount(),
                    false,
                    Collections.unmodifiableMap(eProps)));
        }

        boolean truncated = cappedNodeCount > 0 || cappedEdgeCount > 0;
        return buildMultiScaleVisualGraph(
                graph, request, resolvedLevel, layoutSeed,
                extracted.projectEntities().size(), extracted.projectEdges().size(),
                0, truncated, cappedNodeCount, cappedEdgeCount,
                visualNodes, visualEdges, microScc,
                Optional.empty(), Optional.empty());
    }

    private VisualGraph buildMultiScaleVisualGraph(
            KnowledgeGraph graph,
            MultiScaleRequest request,
            ProjectionLevel resolvedLevel,
            String layoutSeed,
            int totalProjectNodes,
            int totalProjectEdges,
            int superNodeCount,
            boolean truncated,
            int cappedNodeCount,
            int cappedEdgeCount,
            List<VisualGraph.VisualNode> visualNodes,
            List<VisualGraph.VisualEdge> visualEdges,
            TarjanSccAnalyzer.SccResult sccResult,
            Optional<Integer> communityCount,
            Optional<TarjanSccAnalyzer.SccResult> projectScc) {
        var sccSummary = buildSccSummaryMap(sccResult);

        var metadata = new TreeMap<String, Object>();
        metadata.put("level", request.level().name());
        metadata.put("resolvedLevel", resolvedLevel.name());
        metadata.put("cycleMode", request.cycleMode().name());
        metadata.put("context", request.context());
        metadata.put("totalProjectNodes", totalProjectNodes);
        metadata.put("totalProjectEdges", totalProjectEdges);
        metadata.put("totalNodes", visualNodes.size());
        metadata.put("totalEdges", visualEdges.size());
        metadata.put("superNodeCount", superNodeCount);
        metadata.put("truncated", truncated);
        metadata.put("isTruncated", truncated);
        metadata.put("cappedNodeCount", cappedNodeCount);
        metadata.put("cappedEdgeCount", cappedEdgeCount);
        metadata.put("layoutSeed", layoutSeed);
        metadata.put("sccSummary", sccSummary);
        communityCount.ifPresent(c -> metadata.put("communityCount", c));
        projectScc.ifPresent(ps -> metadata.put("projectSccSummary", buildSccSummaryMap(ps)));
        if (!request.expandCommunities().isEmpty()) {
            metadata.put("expandCommunities", request.expandCommunities());
        }
        if (!request.focusNodes().isEmpty()) {
            metadata.put("focusNodes", request.focusNodes());
        }

        ContentDigest id = ContentDigest.sha256Utf8(CanonicalJson.write(List.of(
                VERSION,
                "multiscale",
                graph.identity(),
                request.context(),
                request.level().name(),
                resolvedLevel.name(),
                request.cycleMode().name(),
                request.expandCommunities(),
                request.focusNodes(),
                request.maxNodes(),
                request.maxEdges(),
                visualNodes.stream().map(VisualGraph.VisualNode::id).toList(),
                visualEdges.stream().map(VisualGraph.VisualEdge::id).toList())));

        return new VisualGraph(id, visualNodes, visualEdges, metadata);
    }

    private static Map<String, Object> buildSccSummaryMap(TarjanSccAnalyzer.SccResult scc) {
        int cyclicBlockCount = 0;
        int largestSccSize = 0;
        int largestCyclicSccSize = 0;
        for (var block : scc.blocks()) {
            int sz = block.members().size();
            if (sz > largestSccSize) largestSccSize = sz;
            if (block.cyclic()) {
                cyclicBlockCount++;
                if (sz > largestCyclicSccSize) largestCyclicSccSize = sz;
            }
        }
        var m = new TreeMap<String, Object>();
        m.put("sccCount", scc.blocks().size());
        m.put("cyclicBlockCount", cyclicBlockCount);
        m.put("cyclicNodeCount", scc.cyclicNodeIds().size());
        m.put("largestSccSize", largestSccSize);
        m.put("largestCyclicSccSize", largestCyclicSccSize);
        m.put("levelizationScore", scc.levelizationScoreDecimal());
        return Collections.unmodifiableMap(m);
    }

    private static LeidenCommunityDetector.Result runLeidenForContext(
            String context,
            List<ContextGraphExtractor.ExtractedEntity> projectEntities,
            List<ContextGraphExtractor.ExtractedEdge> projectEdges,
            Runnable checkpoint) {
        var views = new ArrayList<EntityView>(projectEntities.size());
        for (var e : projectEntities) {
            views.add(e.toEntityView(context));
        }
        String commonPkgPrefix = computeCommonPackagePrefix(views, Scope.TYPE);
        var leidenNodes = new ArrayList<LeidenCommunityDetector.NodeInput>(projectEntities.size());
        for (int i = 0; i < projectEntities.size(); i++) {
            var ev = views.get(i);
            leidenNodes.add(new LeidenCommunityDetector.NodeInput(
                    ev.identity(),
                    shortLabel(ev, Scope.TYPE),
                    groupName(ev, Scope.TYPE, commonPkgPrefix),
                    ev.kind()));
        }
        var leidenEdges = new ArrayList<LeidenCommunityDetector.EdgeInput>(projectEdges.size());
        for (var edge : projectEdges) {
            leidenEdges.add(new LeidenCommunityDetector.EdgeInput(
                    edge.source(), edge.target(), Math.max(1.0, edge.supportCount()), edge.primaryKind()));
        }
        return LeidenCommunityDetector.detect(leidenNodes, leidenEdges, checkpoint);
    }

    private static boolean matchesContainerFocus(ContextGraphExtractor.ExtractedEntity ent, String focusId) {
        if (focusId == null || focusId.isEmpty()) return false;
        if (!ent.packageId().isEmpty() && ent.packageId().equals(focusId)) return true;
        String pkgLabel = ent.packageName().isEmpty() ? "(default)" : ent.packageName();
        if (("package:" + pkgLabel).equals(focusId)) return true;
        if (!ent.packageName().isEmpty() && ent.packageName().equals(focusId)) return true;
        if (!ent.moduleId().isEmpty() && ent.moduleId().equals(focusId)) return true;
        if (!ent.moduleName().isEmpty() && (("module:" + ent.moduleName()).equals(focusId) || ent.moduleName().equals(focusId))) {
            return true;
        }
        return false;
    }

    private static Map<String, Integer> computeEntityDegrees(List<ContextGraphExtractor.ExtractedEdge> edges) {
        var deg = new HashMap<String, Integer>(Math.max(16, edges.size()));
        for (var e : edges) {
            deg.merge(e.source(), e.supportCount(), Integer::sum);
            deg.merge(e.target(), e.supportCount(), Integer::sum);
        }
        return deg;
    }

    private static List<String> selectSampleMembers(
            List<String> memberIds,
            Map<String, ContextGraphExtractor.ExtractedEntity> allEntities,
            Map<String, Integer> deg,
            int limit) {
        return memberIds.stream()
                .sorted(Comparator
                        .comparingInt((String id) -> deg.getOrDefault(id, 0)).reversed()
                        .thenComparing(Comparator.naturalOrder()))
                .limit(limit)
                .map(id -> {
                    var ent = allEntities.get(id);
                    return ent != null ? ent.shortLabel() : shortId(id);
                })
                .toList();
    }

    private record Point2D(BigDecimal x, BigDecimal y) {
        static final Point2D ORIGIN = new Point2D(
                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));

        static Point2D of(double x, double y) {
            double safeX = Double.isFinite(x) ? x : 0.0;
            double safeY = Double.isFinite(y) ? y : 0.0;
            return new Point2D(
                    BigDecimal.valueOf(safeX).setScale(2, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(safeY).setScale(2, RoundingMode.HALF_UP));
        }
    }

    /**
     * Deterministic server-side layered layout driven by Tarjan condensation levels
     * (high-level callers on top, low-level dependencies on bottom) with golden-angle
     * spreading for dense layers so large graphs never collapse into degenerate lines.
     */
    private static Map<String, Point2D> computeLayeredPositions(
            TarjanSccAnalyzer.SccResult scc,
            String bottomPinNodeId,
            double xSpacing,
            double ySpacing) {
        var positions = new HashMap<String, Point2D>(Math.max(16, scc.topologicalOrder().size() * 2));
        if (scc.blocks().isEmpty()) return positions;

        int maxLevel = 0;
        var nodesByLevel = new TreeMap<Integer, List<String>>();
        boolean hasPinnedBottom = false;

        for (var block : scc.blocks()) {
            int lvl = block.level();
            for (String nid : block.members()) {
                if (nid.equals(bottomPinNodeId)) {
                    hasPinnedBottom = true;
                    continue;
                }
                if (lvl > maxLevel) maxLevel = lvl;
                nodesByLevel.computeIfAbsent(lvl, k -> new ArrayList<>()).add(nid);
            }
        }

        double currentY = 0.0;
        // Iterate from highest level (top callers) down to level 0 (foundation dependencies)
        for (int lvl = maxLevel; lvl >= 0; lvl--) {
            var row = nodesByLevel.get(lvl);
            if (row == null || row.isEmpty()) continue;
            int m = row.size();
            if (m <= 10) {
                double half = (m - 1) / 2.0;
                for (int i = 0; i < m; i++) {
                    double x = (i - half) * xSpacing;
                    double y = currentY + StrictMath.sin((i + 1) * GOLDEN_ANGLE) * (ySpacing * 0.12);
                    positions.put(row.get(i), Point2D.of(x, y));
                }
                currentY += ySpacing;
            } else {
                // Multi-row golden-angle phyllotaxis band for wide layers
                int cols = Math.max(4, (int) StrictMath.ceil(StrictMath.sqrt(m * 1.6)));
                int rows = (m + cols - 1) / cols;
                for (int i = 0; i < m; i++) {
                    int r = i / cols;
                    int c = i % cols;
                    int rowLen = Math.min(cols, m - r * cols);
                    double half = (rowLen - 1) / 2.0;
                    double angle = (i + 1) * GOLDEN_ANGLE;
                    double x = (c - half) * xSpacing + StrictMath.cos(angle) * (xSpacing * 0.14);
                    double y = currentY + r * (ySpacing * 0.72) + StrictMath.sin(angle) * (ySpacing * 0.14);
                    positions.put(row.get(i), Point2D.of(x, y));
                }
                currentY += rows * (ySpacing * 0.72) + ySpacing * 0.5;
            }
        }

        if (hasPinnedBottom) {
            positions.put(bottomPinNodeId, Point2D.of(0.0, currentY + ySpacing * 0.35));
        }
        return positions;
    }

    private static void updateWorstSeverity(Map<String, ArchitecturePolicy.Severity> map, String id, ArchitecturePolicy.Severity s) {
        var existing = map.get(id);
        if (existing == null || rank(s) > rank(existing)) {
            map.put(id, s);
        }
    }

    private static int rank(ArchitecturePolicy.Severity s) {
        return switch (s) {
            case INFO -> 1;
            case WARNING -> 2;
            case ERROR -> 3;
        };
    }

    private static boolean isProjectEntity(EntityView entity) {
        return entity.origin().isEmpty() || "PROJECT".equals(entity.origin().orElse(""));
    }

    /**
     * Canonical shared common package prefix computation used identically by Dependency Map
     * ({@code groupBy=COMMUNITY}) and Architecture Insights so Leiden communities, labels,
     * misplaced-type drift, and cache keys always stay 100% in sync.
     */
    public static String computeCommonPackagePrefix(Collection<EntityView> entities, Scope scope) {
        if (scope == Scope.MODULE || entities == null) return "";
        String common = null;
        int seen = 0;
        for (EntityView ev : entities) {
            String qn = ev.qualifiedName().or(ev::canonicalName).orElse("");
            int lastDot = scope == Scope.PACKAGE ? qn.length() : qn.lastIndexOf('.');
            if (lastDot <= 0) continue;
            String pkg = qn.substring(0, lastDot);
            seen++;
            if (common == null) {
                common = pkg;
            } else {
                common = sharedDotPrefix(common, pkg);
                if (common.isEmpty()) break;
            }
        }
        return seen >= 2 && common != null ? common : "";
    }

    private static String sharedDotPrefix(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int limit = Math.min(pa.length, pb.length);
        int match = 0;
        while (match < limit && pa[match].equals(pb[match])) match++;
        if (match == 0) return "";
        return String.join(".", Arrays.copyOf(pa, match));
    }

    /** Canonical package/module group name matching Architecture Insights DSM &amp; Leiden drift detection. */
    public static String groupName(EntityView ev, Scope scope, String commonPrefix) {
        if (scope == Scope.MODULE) {
            return shortLabel(ev, scope);
        }
        String qn = ev.qualifiedName().or(ev::canonicalName).orElse("");
        if (scope == Scope.PACKAGE) {
            if (!commonPrefix.isEmpty() && qn.startsWith(commonPrefix + ".")) {
                String rem = qn.substring(commonPrefix.length() + 1);
                int dot = rem.indexOf('.');
                return dot > 0 ? rem.substring(0, dot) : rem;
            }
            return qn.isEmpty() ? "(default)" : qn;
        }
        int lastDot = qn.lastIndexOf('.');
        if (lastDot <= 0) return ev.module().orElse("(root)");
        String pkg = qn.substring(0, lastDot);
        if (!commonPrefix.isEmpty()) {
            if (pkg.equals(commonPrefix)) return "(root)";
            if (pkg.startsWith(commonPrefix + ".")) {
                String rem = pkg.substring(commonPrefix.length() + 1);
                int dot = rem.indexOf('.');
                return dot > 0 ? rem.substring(0, dot) : rem;
            }
        }
        int lastPkgDot = pkg.lastIndexOf('.');
        return lastPkgDot >= 0 ? pkg.substring(lastPkgDot + 1) : pkg;
    }

    /** Canonical short display label matching Architecture Insights &amp; Leiden hub/bridge rendering. */
    public static String shortLabel(EntityView ev, Scope scope) {
        if (ev == null) return "unknown";
        String raw = ev.qualifiedName().or(ev::canonicalName).or(ev::spelling).orElse(ev.identity());
        if (scope == Scope.TYPE || scope == Scope.SPRING_COMPONENT) {
            int dot = raw.lastIndexOf('.');
            if (dot > 0 && dot + 1 < raw.length() && !raw.startsWith("sha256:")) {
                int prevDot = raw.lastIndexOf('.', dot - 1);
                return prevDot >= 0 ? raw.substring(prevDot + 1) : raw;
            }
        }
        return raw;
    }

    private static String extractPackageName(EntityView entity) {
        String name = entity.qualifiedName().orElse(entity.canonicalName().orElse(""));
        int lastDot = name.lastIndexOf('.');
        return lastDot > 0 ? name.substring(0, lastDot) : "";
    }

    private static String shortId(String id) {
        int colon = id.lastIndexOf(':');
        return colon >= 0 && id.matches(".*sha256:[0-9a-f]{64}") ? id.substring(colon + 1, Math.min(id.length(), colon + 13)) : id;
    }
}
