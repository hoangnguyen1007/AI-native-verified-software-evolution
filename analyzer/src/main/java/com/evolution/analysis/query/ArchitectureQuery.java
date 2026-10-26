package com.evolution.analysis.query;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import java.util.*;

/** Storage/parser-neutral, immutable M5 query wire contract. No runtime-impact or policy verdicts. */
public final class ArchitectureQuery {
    public static final VersionedIdentifier VERSION = new VersionedIdentifier("architecture.query", "m8.3-v1");
    public enum Projection { REALIZED, MUST, MAY, BOUNDED_UNION, UNKNOWN_OVERLAY }
    public enum Scope { ENTITY, TYPE, PACKAGE, MODULE, SPRING_COMPONENT }
    public enum Direction { OUTGOING, INCOMING }
    public enum Operation { COMPLETED, CANCELLED, RESOURCE_LIMIT }
    public enum Truth { SAT, UNSAT, UNKNOWN }
    public enum Classification { MUST, MAY, NEVER, UNKNOWN }
    public enum StopReason { NONE, DEPTH, STEPS, OUTPUT, INPUT, SOLVER, CANCELLED, MEMORY, TIME, UNKNOWN_EVIDENCE }
    public enum GapView { CURRENT, HISTORY, ALL }
    public record Limits(int maxDepth, long maxSteps, int maxResults, int maxVertices, int maxEdges,
                         long maxSolverSteps, int maxFormulaNodes, int maxAuxiliaryStates) {
        public Limits {
            if (maxDepth < 1 || maxDepth > 20 || maxSteps < 1 || maxSteps > 100_000 || maxResults < 1 || maxResults > 50
                    || maxVertices < 1 || maxVertices > 100_000 || maxEdges < 1 || maxEdges > 500_000
                    || maxSolverSteps < 1 || maxSolverSteps > 10_000_000 || maxFormulaNodes < 1 || maxFormulaNodes > 100_000
                    || maxAuxiliaryStates < 1 || maxAuxiliaryStates > 500_000) throw new IllegalArgumentException("Invalid query limits");
        }
        public static Limits defaults() { return new Limits(20,100_000,50,100_000,500_000,1_000_000,100_000,200_000); }
    }
    /** World IDs bind explicit assignment/order evidence. Boolean keys are upstream opaque variable digests. */
    public record Realization(Optional<String> world, Map<String,Boolean> assignment) {
        public Realization { Objects.requireNonNull(world); assignment=Collections.unmodifiableMap(new TreeMap<>(assignment));
            if(world.isPresent()==!assignment.isEmpty())throw new IllegalArgumentException("Choose an explicit world or symbolic assignment"); }
    }
    public record Population(Set<String> nodeKinds, Set<String> modules, Set<String> origins, Set<String> sourceSets) {
        public Population {nodeKinds=ordered(nodeKinds);modules=ordered(modules);origins=ordered(origins);sourceSets=ordered(sourceSets);}
        public static Population all() {return new Population(Set.of(),Set.of(),Set.of(),Set.of());}
    }
    /** One context only: source sets/build contexts must never be fused into a Spring world. */
    public record Request(ContentDigest graph, String context, Scope scope, Projection projection, Direction direction,
                          Set<String> relationshipKinds, Population population, boolean includeMust,
                          Optional<Realization> realization, Limits limits) {
        public Request {
            Objects.requireNonNull(graph);Objects.requireNonNull(context);Objects.requireNonNull(scope);Objects.requireNonNull(projection);
            Objects.requireNonNull(direction);relationshipKinds=ordered(relationshipKinds);Objects.requireNonNull(population);
            Objects.requireNonNull(realization);Objects.requireNonNull(limits);
            if(context.isBlank() || relationshipKinds.isEmpty())throw new IllegalArgumentException("Explicit context and relationship kinds required");
            if((projection==Projection.REALIZED)!=realization.isPresent())throw new IllegalArgumentException("REALIZED requires exactly one realization");
        }
        public static Request dependencies(ContentDigest graph,String context) {return new Request(graph,context,Scope.TYPE,
                Projection.BOUNDED_UNION,Direction.OUTGOING,Set.of("graph.depends-on"),Population.all(),false,Optional.empty(),Limits.defaults());}
        public ContentDigest identity() { return ContentDigest.sha256Utf8(CanonicalJson.write(List.of(VERSION,this))); }
    }
    public record Page(int size, Optional<String> cursor) {
        public Page {if(size<1||size>50)throw new IllegalArgumentException("Invalid page size");Objects.requireNonNull(cursor);}
        public static Page first() {return new Page(50,Optional.empty());}
    }
    public record EntityView(String identity,String context,String kind,Optional<String> canonicalName,Optional<String> qualifiedName,Optional<String> spelling,
                             Optional<String> module,Optional<String> origin,Optional<String> sourceSet,List<ContentDigest> evidence) {
        public EntityView {evidence=List.copyOf(evidence);}
    }
    public record EdgeView(String identity,String source,String target,List<String> kinds,List<String> supports,
                           List<ContentDigest> evidence,List<String> regions,Classification classification,boolean definite,int supportCount,boolean supportsTruncated) {
        public EdgeView {kinds=List.copyOf(kinds);supports=List.copyOf(supports);evidence=List.copyOf(evidence);regions=List.copyOf(regions);}
    }
    public record Witness(Optional<String> world,Map<String,Boolean> assignment,ContentDigest jointRegion,
                          boolean formulaReplayed,boolean semanticReplayed) {
        public Witness {assignment=Collections.unmodifiableMap(new TreeMap<>(assignment));}
    }
    /** A replay checks the captured rule model only; it never executes the analyzed application. */
    public record WitnessReplay(ContentDigest jointRegion, Witness witness, Truth expectedTruth, Truth actualTruth,
                                boolean matched, boolean formulaReplayed, boolean semanticReplayed) {}
    public record RealizationView(String identity, String kind, Realization realization, Map<String,Boolean> profiles,List<ContentDigest> evidence) {
        public RealizationView { profiles=Collections.unmodifiableMap(new TreeMap<>(profiles));evidence=List.copyOf(evidence); }
    }
    public record Path(List<String> vertices,List<EdgeView> edges,Truth feasibility,Optional<Witness> witness) {
        public Path {vertices=List.copyOf(vertices);edges=List.copyOf(edges);Objects.requireNonNull(feasibility);Objects.requireNonNull(witness);}
        public ContentDigest identity(){return ContentDigest.sha256Utf8(CanonicalJson.write(List.of(VERSION,vertices,edges.stream().map(EdgeView::identity).toList())));}
    }
    public record Bounds(boolean truncated,boolean workBudgetExceeded,boolean outputBudgetExceeded,StopReason stopReason,
                         long stepsUsed,long solverStepsUsed,int witnessesReturned,long rejectedInfeasible,long unknownCandidates,
                         int structuralComponents,int cyclicComponents,List<String> frontier) {
        public Bounds {frontier=List.copyOf(frontier);}
    }
    public record Result<T>(ContentDigest query,AssessmentStatus status,Operation operation,List<T> items,List<ContentDigest> evidence,
                            List<CapabilityGapRecord> gaps,Bounds bounds,Optional<String> continuation,Optional<Long> exactTotal) {
        public Result {items=List.copyOf(items);evidence=List.copyOf(evidence);gaps=List.copyOf(gaps);Objects.requireNonNull(continuation);Objects.requireNonNull(exactTotal);}
        public ContentDigest identity(){return ContentDigest.sha256Utf8(CanonicalJson.write(this));}
    }
    public record Summary(Map<String,Long> nodeCounts,Map<String,Long> edgeCounts,int contexts,ContentDigest provenance,
                          String populationMeaning) {
        public Summary {nodeCounts=Collections.unmodifiableMap(new TreeMap<>(nodeCounts));edgeCounts=Collections.unmodifiableMap(new TreeMap<>(edgeCounts));}
    }
    /** Payload is allowlisted metadata only; raw source/configuration/binary bytes never enter query responses. */
    public record EvidenceView(ContentDigest identity,String family,boolean redacted,Map<String,Object> metadata) {
        public EvidenceView {metadata=Collections.unmodifiableMap(new TreeMap<>(metadata));}
    }
    public record RelationshipView(String identity,String source,String kind,String targetState,List<String> targets,
                                   Optional<ContentDigest> unresolvedReference,List<ContentDigest> evidence,Optional<String> region) {
        public RelationshipView {targets=List.copyOf(targets);evidence=List.copyOf(evidence);}
    }
    public record Neighborhood(List<EntityView> entities,List<EdgeView> edges) {
        public Neighborhood {entities=List.copyOf(entities);edges=List.copyOf(edges);}
    }
    /** Explicit full declared scope for domain metrics; bounded by input/solver/auxiliary limits, never a UI page. */
    public record MetricProjection(List<EntityView> entities,List<EdgeView> edges,List<String> selectedDefinitions,List<String> unknownDefinitions) {
        public MetricProjection {entities=List.copyOf(entities);edges=List.copyOf(edges);selectedDefinitions=List.copyOf(selectedDefinitions);unknownDefinitions=List.copyOf(unknownDefinitions);}
    }
    public record Compatibility(boolean sameArtifact,boolean comparisonCompatible,List<String> reasons) {
        public Compatibility {reasons=List.copyOf(reasons);}
    }
    /** M5.3 supplies this port; an absent provider is UNSUPPORTED, never an invented metric or zero. */
    public interface Metrics {
        Result<String> catalog(Request request,Page page);
        Result<Map<String,Object>> evaluate(Request request,String metric,String subject);
        default Optional<Map<String,Object>> definition(String metric){return Optional.empty();}
    }
    private static Set<String> ordered(Set<String> values) {return Collections.unmodifiableSet(new TreeSet<>(values));}
    private ArchitectureQuery() {}
}
