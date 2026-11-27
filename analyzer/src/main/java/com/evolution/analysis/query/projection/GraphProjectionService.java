package com.evolution.analysis.query.projection;

import com.evolution.analysis.assessment.v2.LeidenCommunityDetector;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.policy.ArchitecturePolicy;
import com.evolution.analysis.policy.PolicyEvaluation;
import com.evolution.analysis.query.ArchitectureQuery;
import com.evolution.analysis.query.ArchitectureQueryService;
import java.util.*;
import static com.evolution.analysis.query.ArchitectureQuery.*;

/** Projects canonical architecture query and policy results into visual workbench models. */
public final class GraphProjectionService {
    public static final VersionedIdentifier VERSION = new VersionedIdentifier("graph.projection", "m8.3-v1");

    public enum GroupBy {
        PACKAGE,
        COMMUNITY;

        public static GroupBy parse(String raw) {
            if (raw == null || raw.isBlank()) return PACKAGE;
            return "COMMUNITY".equalsIgnoreCase(raw.strip()) ? COMMUNITY : PACKAGE;
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
