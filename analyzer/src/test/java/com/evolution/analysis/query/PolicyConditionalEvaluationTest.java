package com.evolution.analysis.query;

import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.source.SourceSpan;
import com.evolution.analysis.evidence.EvidenceContext;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.policy.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.query.ArchitectureQuery.*;
import static com.evolution.analysis.query.ConditionalPathCycleTest.*;
import static com.evolution.analysis.spring.truth.SymbolicConfiguration.*;

/** Expected regions come from a separately enumerated Boolean/world oracle, not the query solver. */
class PolicyConditionalEvaluationTest {
    static String yaml(boolean cycles){return """
            schema: architecture-policy:v1
            id: example.policy
            version: "1"
            rules:
              - id: architecture.constraints
                version: "1"
                kind: %s
                severity: ERROR
                scope: TYPE
                relationships: [graph.depends-on]
            %s
            """.formatted(cycles?"ACYCLIC":"FORBIDDEN_DEPENDENCY",cycles?"    within: {names: ['*']}":"    from: {names: ['*']}\n    to: {names: ['*']}");}
    static KnowledgeGraph graph(Fixture f){
        var repository=RepositoryIdentity.fromCanonicalCoordinate("https://example.test/query.git");
        var document=SourceDocumentIdentity.from(repository,"src/Edges.java");
        var inventory=GraphEvidence.capture("source.document",Map.of("identity",document,"contentDigest",ContentDigest.sha256Utf8("class Edges {}")));
        var proof=GraphEvidence.capture("java.occurrence",Map.of("status","RESOLVED","span",new SourceSpan(document,1,1,1,10)));
        var nodes=new ArrayList<>(f.nodes);nodes.add(new KnowledgeGraph.Node(document.value(),f.context,"SOURCE_DOCUMENT",List.of(inventory.identity())));
        var evidence=new ArrayList<>(f.evidence);evidence.add(inventory);evidence.add(proof);
        var edges=f.edges.stream().map(e->new KnowledgeGraph.Edge(e.identity(),e.context(),"graph.depends-on",e.source(),e.target(),List.of(proof.identity()),e.region())).toList();
        return new KnowledgeGraph(f.snapshot,List.of(new KnowledgeGraph.Context(f.context,f.snapshot,Optional.empty(),f.base.identity())),nodes,edges,evidence);
    }
    static PolicyEvaluation.Result evaluate(Fixture f,boolean cycles){return evaluate(graph(f),yaml(cycles),ArchitectureQuery.Limits.defaults());}
    static PolicyEvaluation.Result evaluate(KnowledgeGraph graph,String yaml,ArchitectureQuery.Limits queryLimits){
        var compiled=new PolicyCompiler().compile(yaml.getBytes(StandardCharsets.UTF_8),EvidenceContext.forSnapshot(graph.snapshot()),PolicyCompiler.Limits.defaults());
        var service=new ArchitectureQueryService(graph);var plan=new PolicyGraphMapper().bind(compiled,service,graph.snapshot().value(),new PolicyGraphMapper.Limits(200_000,queryLimits));
        return new PolicyEvaluator().evaluate(compiled,plan,service,PolicyEvaluation.Limits.defaults());
    }
    @Test void complementaryMayFindingsMakeMustRuleAndRetainIndependentCounterWitnesses(){
        var p=variable("p");var f=new Fixture().node("a").node("b").node("c").edge("a","b",p,not(p),FALSE).edge("b","c",not(p),p,FALSE);
        var result=evaluate(f,false);assertEquals(AssessmentStatus.COMPLETE,result.status(),result.gaps().toString());
        assertEquals(Classification.MUST,result.rules().getFirst().classification());assertEquals(2,result.findings().size());
        for(var finding:result.findings()){
            assertEquals(Classification.MAY,finding.classification());var bundle=finding.bundle();
            assertTrue(bundle.region().witness().isPresent());assertTrue(bundle.region().counterWitness().isPresent());
            assertEquals(bundle.region(),new ArchitectureQueryService(graph(f)).ruleCondition(bundle.request(),bundle.predicate()).items().getFirst());
            assertNotEquals(bundle.region().witness().orElseThrow().assignment(),bundle.region().counterWitness().orElseThrow().assignment());
        }
        assertTrue(result.rules().getFirst().region().counterWitness().isEmpty());
    }
    @Test void mutuallyExclusiveCycleAndPairwiseOnlyTripleAreClean(){
        var p=variable("p");var incompatible=new Fixture().node("a").node("b").edge("a","b",p,not(p),FALSE).edge("b","a",not(p),p,FALSE);
        var result=evaluate(incompatible,true);assertEquals(Classification.NEVER,result.rules().getFirst().classification());assertTrue(result.findings().isEmpty());
        var all=Set.of("x","y","z");var triple=new Fixture().node("a").node("b").node("c")
                .explicit("a","b",Set.of("x","y"),Set.of(),all,true).explicit("b","c",Set.of("y","z"),Set.of(),all,true).explicit("c","a",Set.of("x","z"),Set.of(),all,true);
        result=evaluate(triple,true);assertEquals(Classification.NEVER,result.rules().getFirst().classification());assertTrue(result.findings().isEmpty());
    }
    @Test void conditionalAndUnknownCyclesHaveQualifiedBundles(){
        var p=variable("p");var conditional=new Fixture().node("a").node("b").edge("a","b",p,not(p),FALSE).edge("b","a");
        var result=evaluate(conditional,true);assertEquals(Classification.MAY,result.rules().getFirst().classification());assertEquals(1,result.findings().size());
        var bundle=result.findings().getFirst().bundle();assertEquals(2,bundle.path().size());assertEquals(2,bundle.relationships().size());assertEquals(2,bundle.supports().size());
        assertEquals(true,bundle.region().witness().orElseThrow().assignment().get("p"));assertEquals(false,bundle.region().counterWitness().orElseThrow().assignment().get("p"));
        var unknown=new Fixture().node("a").node("b").edge("a","b",FALSE,FALSE,TRUE).edge("b","a");result=evaluate(unknown,true);
        assertEquals(Classification.UNKNOWN,result.rules().getFirst().classification());assertEquals(1,result.findings().size());
        assertEquals(Classification.UNKNOWN,result.findings().getFirst().classification());assertTrue(result.findings().getFirst().bundle().region().witness().isEmpty());
        assertFalse(result.gaps().isEmpty());
    }
    @Test void exhaustiveTwoVariableOracleAgreesForCyclesAndRuleUnion(){
        var p=variable("p");var q=variable("q");var choices=List.of(p,not(p),q,not(q));
        for(var a:choices)for(var b:choices){
            int cycle=0,union=0;
            for(boolean pv:List.of(false,true))for(boolean qv:List.of(false,true)){
                var assignment=Map.of("p",pv,"q",qv);if(a.evaluate(assignment)&&b.evaluate(assignment))cycle++;
                if(a.evaluate(assignment)||b.evaluate(assignment))union++;
            }
            var f=new Fixture().node("a").node("b").edge("a","b",a,not(a),FALSE).edge("b","a",b,not(b),FALSE);
            assertEquals(classification(cycle,4),evaluate(f,true).rules().getFirst().classification());
            assertEquals(classification(union,4),evaluate(f,false).rules().getFirst().classification());
        }
    }

    @Test void guardedMutationsMatchEveryWorldAndReplayOnlyValidWitnesses() {
        var p = variable("p"); var q = variable("q");
        var cyclic = new Fixture().node("a").node("b").edge("a", "b", p, not(p), FALSE).edge("b", "a", q, not(q), FALSE);
        var graph = graph(cyclic);
        var queries = new ArchitectureQueryService(graph);
        var cycles = evaluate(cyclic, true);
        assertEquals(AssessmentStatus.COMPLETE, cycles.status());
        assertEquals(1, cycles.findings().size());
        var cycle = cycles.findings().getFirst().bundle();
        assertEquals(Set.of(id("a"), id("b")), new HashSet<>(cycle.path()));
        assertEquals(Set.of(List.of(id("a"), id("b")), List.of(id("b"), id("a"))), cycle.relationships().stream()
                .map(edge -> List.of(edge.source(), edge.target())).collect(java.util.stream.Collectors.toSet()));
        assertWorlds(queries, cycle, Set.of(Map.of("p", true, "q", true)));

        var boundary = evaluate(cyclic, false);
        assertEquals(AssessmentStatus.COMPLETE, boundary.status());
        assertEquals(2, boundary.findings().size());
        for (var finding : boundary.findings()) {
            var bundle = finding.bundle();
            boolean forward = bundle.path().equals(List.of(id("a"), id("b")));
            assertEquals(forward ? List.of(id("a"), id("b")) : List.of(id("b"), id("a")), bundle.path());
            assertWorlds(queries, bundle, forward ? Set.of(Map.of("p", true, "q", false), Map.of("p", true, "q", true))
                    : Set.of(Map.of("p", false, "q", true), Map.of("p", true, "q", true)));
        }

        var exclusive = new Fixture().node("a").node("b").edge("a", "b", p, not(p), FALSE).edge("b", "a", not(p), p, FALSE);
        var result = evaluate(exclusive, true);
        assertEquals(AssessmentStatus.COMPLETE, result.status());
        assertEquals(Classification.NEVER, result.rules().getFirst().classification());
        assertTrue(result.findings().isEmpty());
    }

    private static void assertWorlds(ArchitectureQueryService queries, PolicyEvaluation.EvidenceBundle bundle, Set<Map<String, Boolean>> affected) {
        var region = bundle.region();
        assertEquals(Classification.MAY, region.classification());
        var witnessEntries = region.witness().orElseThrow().assignment().entrySet();
        var counterEntries = region.counterWitness().orElseThrow().assignment().entrySet();
        int witnessWorlds = 0, counterWorlds = 0;
        for (boolean pv : List.of(false, true)) for (boolean qv : List.of(false, true)) {
            var assignment = Map.of("p", pv, "q", qv);
            if (assignment.entrySet().containsAll(witnessEntries)) { assertTrue(affected.contains(assignment)); witnessWorlds++; }
            if (assignment.entrySet().containsAll(counterEntries)) { assertFalse(affected.contains(assignment)); counterWorlds++; }
            var witness = new Witness(Optional.empty(), assignment, region.jointRegion(), false, false);
            var replay = queries.replayRuleWitness(bundle.request(), bundle.predicate(), witness, !affected.contains(assignment)).items().getFirst();
            assertEquals(affected.contains(assignment) ? Truth.SAT : Truth.UNSAT, replay.actualTruth(), assignment.toString());
            assertTrue(replay.matched()); assertTrue(replay.formulaReplayed()); assertFalse(replay.semanticReplayed());
        }
        assertTrue(witnessWorlds > 0); assertTrue(counterWorlds > 0);
    }

    @Test void boundedIncomingImpactKeepsJointWitnessAndAnOpenFrontier() {
        var p = variable("p"); var q = variable("q");
        var fixture = new Fixture().node("a").node("b").node("c").edge("a", "b", p, not(p), FALSE).edge("b", "c", q, not(q), FALSE);
        var r = fixture.request();
        var queries = new ArchitectureQueryService(fixture.graph());
        var incoming = new Request(r.graph(), r.context(), r.scope(), r.projection(), Direction.INCOMING, r.relationshipKinds(),
                r.population(), false, Optional.empty(), r.limits());
        var exact = queries.impact(incoming, id("c"));
        assertEquals(AssessmentStatus.COMPLETE, exact.status());
        assertEquals(Set.of(List.of(id("c"), id("b")), List.of(id("c"), id("b"), id("a"))),
                exact.items().stream().map(Path::vertices).collect(java.util.stream.Collectors.toSet()));
        var indirect = exact.items().stream().filter(path -> path.vertices().size() == 3).findFirst().orElseThrow();
        assertEquals(Map.of("p", true, "q", true), indirect.witness().orElseThrow().assignment());
        var limited = new Request(r.graph(), r.context(), r.scope(), r.projection(), Direction.INCOMING, r.relationshipKinds(),
                r.population(), false, Optional.empty(), new ArchitectureQuery.Limits(2, 100_000, 50, 100_000, 500_000, 1_000_000, 100_000, 200_000));
        var partial = queries.impact(limited, id("c"));
        assertEquals(AssessmentStatus.PARTIAL, partial.status());
        assertEquals(StopReason.DEPTH, partial.bounds().stopReason());
        assertTrue(partial.bounds().frontier().contains(id("a")));
        assertFalse(partial.gaps().isEmpty());
        assertEquals(List.of(List.of(id("c"), id("b"))), partial.items().stream().map(Path::vertices).toList());
    }
    static Classification classification(int yes,int count){return yes==0?Classification.NEVER:yes==count?Classification.MUST:Classification.MAY;}
    @Test void explicitAndSymbolicEquivalentSpacesHaveSameVerdictsAndReplayedWitnesses(){
        var p=variable("p");var symbolic=new Fixture().node("a").node("b").edge("a","b",p,not(p),FALSE).edge("b","a");
        var explicit=new Fixture().node("a").node("b").explicit("a","b",Set.of("on"),Set.of(),Set.of("off","on"),true).edge("b","a");
        var a=evaluate(symbolic,true);var b=evaluate(explicit,true);assertEquals(a.rules().getFirst().classification(),b.rules().getFirst().classification());
        var witness=b.findings().getFirst().bundle().region().witness().orElseThrow();assertEquals(id("worldon"),witness.world().orElseThrow());
        assertEquals(id("worldoff"),b.findings().getFirst().bundle().region().counterWitness().orElseThrow().world().orElseThrow());assertTrue(witness.formulaReplayed());
    }
    @Test void incompleteOrEmptyUniverseAndDepthOrSolverExhaustionCannotProveClean(){
        for(var f:List.of(new Fixture().node("a").node("b").explicit("a","b",Set.of("x"),Set.of(),Set.of("x"),false),
                new Fixture().node("a").node("b").explicit("a","b",Set.of(),Set.of(),Set.of(),true))){
            var result=evaluate(f,false);assertEquals(Classification.UNKNOWN,result.rules().getFirst().classification());assertFalse(result.gaps().isEmpty());
        }
        var p=variable("p");var f=new Fixture().node("a").node("b").edge("a","b",p,not(p),FALSE).edge("b","a");
        for(var limits:List.of(new ArchitectureQuery.Limits(1,100_000,50,100_000,500_000,1_000_000,100_000,200_000),
                new ArchitectureQuery.Limits(20,100_000,50,100_000,500_000,1,100_000,200_000))){
            var result=evaluate(graph(f),yaml(true),limits);assertEquals(Classification.UNKNOWN,result.rules().getFirst().classification());assertFalse(result.gaps().isEmpty());
        }
    }
}
