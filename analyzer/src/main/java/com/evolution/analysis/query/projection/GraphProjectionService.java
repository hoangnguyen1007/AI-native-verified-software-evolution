package com.evolution.analysis.query.projection;

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

    private final ArchitectureQueryService queries;

    public GraphProjectionService(ArchitectureQueryService queries) {
        this.queries = Objects.requireNonNull(queries, "queries");
    }

    public VisualGraph project(Request request, Optional<PolicyEvaluation.Result> policy) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(policy, "policy");

        // 1. Fetch entities and edges via metric projection within limits
        var projectionResult = queries.metricProjection(request);
        var entityItems = projectionResult.items().isEmpty() ? List.<EntityView>of() : projectionResult.items().getFirst().entities();
        var edgeItems = projectionResult.items().isEmpty() ? List.<EdgeView>of() : projectionResult.items().getFirst().edges();

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

        for (var entity : entityItems) {
            String label = entity.qualifiedName().orElse(entity.spelling().orElse(entity.canonicalName().orElse(shortId(entity.identity()))));
            Optional<String> parentId = Optional.empty();

            if (request.scope() == Scope.TYPE && entity.kind().equals("TYPE")) {
                String pkgName = extractPackageName(entity);
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
                Map.of("evidenceCount", entity.evidence().size())
            ));
        }

        // Add synthesized compound nodes first so Cytoscape renders containers before children
        var allNodes = new ArrayList<VisualGraph.VisualNode>(compoundNodes.values());
        allNodes.addAll(nodes);

        // 5. Build visual edges
        var edges = new ArrayList<VisualGraph.VisualEdge>();
        for (var edge : edgeItems) {
            boolean isViolation = violatingEdgeIds.contains(edge.identity());
            boolean isCycle = cycleEdgeIds.contains(edge.identity());

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
                Map.of("kinds", edge.kinds(),"supports",edge.supports(),"regions",edge.regions(),"evidence",edge.evidence())
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

        ContentDigest graphId = ContentDigest.sha256Utf8(CanonicalJson.write(List.of(
            VERSION,
            request.identity(),
            allNodes.stream().map(VisualGraph.VisualNode::id).toList(),
            edges.stream().map(VisualGraph.VisualEdge::id).toList(),projectionResult.identity(),cycleResult.identity(),policy.map(PolicyEvaluation.Result::identity)
        )));

        return new VisualGraph(graphId, allNodes, edges, metadata);
    }

    public VisualGraph focusedSubgraph(Request request, String seedEntity, int depth, Optional<PolicyEvaluation.Result> policy) {
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
        var fullGraph = project(request, policy);
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
