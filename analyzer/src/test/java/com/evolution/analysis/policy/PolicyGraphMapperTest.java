package com.evolution.analysis.policy;

import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.frontend.JavaSymbolName;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.query.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.policy.PolicySelector.Match.*;

class PolicyGraphMapperTest {
    static final class Fixture {
        final RepositoryIdentity repository=RepositoryIdentity.fromCanonicalCoordinate("https://example.test/policy.git");
        final SnapshotIdentity snapshot=PolicyCompilerTest.CONTEXT.snapshotIdentity();final String context=snapshot.value();
        final ModuleIdentity module=ModuleIdentity.from(repository,".");
        final GraphEvidence base=GraphEvidence.capture("fixture.context",Map.of("version","1"));
        final List<GraphEvidence> evidence=new ArrayList<>(List.of(base));final List<KnowledgeGraph.Node> nodes=new ArrayList<>();
        final List<KnowledgeGraph.Edge> edges=new ArrayList<>();final Map<String,String> types=new TreeMap<>();
        Fixture type(String pkg,String name) {
            return type(".",pkg,name);
        }
        Fixture type(String modulePath,String pkg,String name) {
            var owner=ModuleIdentity.from(repository,modulePath);
            var entity=Entity.create(EntityOrigin.PROJECT,EntityScope.project(owner),EntityKind.TYPE,JavaSymbolName.topLevelType(pkg,name).canonicalName(),Optional.empty());
            var proof=GraphEvidence.capture("java.declaration",Map.of("entity",entity,"spelling",name));evidence.add(proof);
            if(nodes.stream().noneMatch(n->n.identity().equals(owner.value()))) {
                var moduleProof=GraphEvidence.capture("module.descriptor",Map.of("identity",owner));evidence.add(moduleProof);
                nodes.add(new KnowledgeGraph.Node(owner.value(),context,"MODULE",List.of(moduleProof.identity())));
            }
            String packageId=EntityIdentity.from(EntityOrigin.PROJECT,EntityScope.project(owner),EntityKind.PACKAGE,JavaSymbolName.packageName(pkg).canonicalName()).value();
            if(nodes.stream().noneMatch(n->n.identity().equals(packageId))){
                nodes.add(new KnowledgeGraph.Node(packageId,context,"PACKAGE",List.of(proof.identity())));link(owner.value(),packageId);
            }
            link(packageId,entity.identity().value());
            nodes.add(new KnowledgeGraph.Node(entity.identity().value(),context,"TYPE",List.of(proof.identity())));types.put(name,entity.identity().value());return this;
        }
        void link(String source,String target){edges.add(new KnowledgeGraph.Edge(KnowledgeGraph.derivedIdentity("fixture",edges.size()),context,"graph.contains",source,
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED,List.of(target),Optional.empty()),List.of(base.identity()),Optional.empty()));}
        Fixture edge(String from,String to) {
            edges.add(new KnowledgeGraph.Edge(KnowledgeGraph.derivedIdentity("fixture",edges.size()),context,"graph.depends-on",types.get(from),
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED,List.of(types.get(to)),Optional.empty()),List.of(base.identity()),Optional.empty()));return this;
        }
        KnowledgeGraph graph(){return new KnowledgeGraph(snapshot,List.of(new KnowledgeGraph.Context(context,snapshot,Optional.empty(),base.identity())),nodes,edges,evidence);}
        PolicyGraphMapper.Plan bind(String policy){return new PolicyGraphMapper().bind(PolicyCompilerTest.compile(policy),new ArchitectureQueryService(graph()),context,PolicyGraphMapper.Limits.defaults());}
    }
    @Test void mapsForbiddenAndBoundaryDirectionsAndPreservesEvidence() {
        var f=new Fixture().type("com.example.web","Controller").type("com.example.persistence","Store").edge("Controller","Store");
        var plan=f.bind(PolicyCompilerTest.VALID);assertEquals(AssessmentStatus.COMPLETE,plan.status(),plan.gaps().toString());
        var rule=plan.rules().getFirst();assertEquals(1,rule.candidates().size());var candidate=rule.candidates().getFirst();
        assertEquals(YES,candidate.eligibility());assertEquals(f.types.get("Controller"),candidate.edge().source());
        assertEquals(List.of(f.base.identity()),candidate.edge().evidence());
        assertEquals(ArchitectureQuery.Classification.MUST,new ArchitectureQueryService(f.graph()).condition(rule.request(),candidate.predicate()).items().getFirst().classification());
        assertEquals(plan.identity(),f.bind(PolicyCompilerTest.VALID).identity());
        var boundary=f.bind(PolicyCompilerTest.VALID.replace("FORBIDDEN_DEPENDENCY","BOUNDARY").replace("to:","allowed:"));
        assertTrue(boundary.rules().getFirst().candidates().isEmpty());
        var reversed=new Fixture().type("com.example.web","Controller").type("com.example.persistence","Store").edge("Store","Controller");
        assertTrue(reversed.bind(PolicyCompilerTest.VALID).rules().getFirst().candidates().isEmpty());
    }
    @Test void packageAndModuleBoundariesUseCanonicalOwnership() {
        var f=new Fixture().type("web","com.example.web","Controller").type("storage","com.example.persistence","Store").edge("Controller","Store");
        var packages=f.bind(PolicyCompilerTest.VALID.replace("scope: TYPE","scope: PACKAGE"));
        assertEquals(AssessmentStatus.COMPLETE,packages.status());assertEquals(1,packages.rules().getFirst().candidates().size());
        var from=ModuleIdentity.from(f.repository,"web").value();var to=ModuleIdentity.from(f.repository,"storage").value();
        var modules=f.bind(PolicyCompilerTest.VALID.replace("scope: TYPE","scope: MODULE")
                .replace("packages: [com.example.web.**]","modules: ['"+from+"']").replace("packages: [com.example.persistence.**]","modules: ['"+to+"']"));
        assertEquals(AssessmentStatus.COMPLETE,modules.status());assertEquals(from,modules.rules().getFirst().candidates().getFirst().edge().source());
        assertEquals(to,modules.rules().getFirst().candidates().getFirst().edge().target());
    }
    @Test void unknownAndEmptySelectorsDoNotEstablishConformance() {
        var f=new Fixture().type("com.example.web","Controller").type("com.example.persistence","Store").edge("Controller","Store");
        var empty=f.bind(PolicyCompilerTest.VALID.replace("com.example.web.**","com.example.absent"));
        assertEquals(AssessmentStatus.PARTIAL,empty.status());assertTrue(empty.gaps().stream().anyMatch(g->g.reasonCode().equals("SELECTOR_MATCH_EMPTY")));
        var unknown=f.bind(PolicyCompilerTest.VALID.replace("packages: [com.example.web.**]","sourceSets: [MAIN]"));
        assertEquals(UNKNOWN,unknown.rules().getFirst().candidates().getFirst().eligibility());assertEquals(AssessmentStatus.PARTIAL,unknown.status());
        assertTrue(unknown.gaps().stream().anyMatch(g->g.reasonCode().equals("SELECTOR_METADATA_UNAVAILABLE")));
        assertThrows(IllegalStateException.class,()->unknown.rules().getFirst().candidates().getFirst().predicate());
    }
    @Test void exactCanonicalIdentitiesSelectSpringOrJavaWithoutInventingNames() {
        var f=new Fixture().type("com.example.web","Controller").type("com.example.persistence","Store").edge("Controller","Store");
        var plan=f.bind(PolicyCompilerTest.VALID.replace("packages: [com.example.web.**]","identities: ['"+f.types.get("Controller")+"']"));
        assertEquals(AssessmentStatus.COMPLETE,plan.status());assertEquals(YES,plan.rules().getFirst().candidates().getFirst().eligibility());
    }
    @Test void layerOverlapsAndUnmappedTargetsAreExplicit() {
        var f=new Fixture().type("com.example.web","Controller").type("com.example.persistence","Store").edge("Controller","Store");
        String layers=PolicyCompilerTest.VALID.substring(0,PolicyCompilerTest.VALID.indexOf("    from:"))+"""
                    layers:
                      - {name: web, select: {packages: [com.example.web.**]}}
                      - {name: persistence, select: {packages: [com.example.persistence.**]}}
                    allowed: []
                """;
        layers=layers.replace("FORBIDDEN_DEPENDENCY","LAYER_BOUNDARY");
        var plan=f.bind(layers);assertEquals(1,plan.rules().getFirst().candidates().size());assertEquals(YES,plan.rules().getFirst().candidates().getFirst().eligibility());
        assertTrue(f.bind(layers.replace("allowed: []","allowed: [{from: web, to: [persistence]}]")).rules().getFirst().candidates().isEmpty());
        var overlapping=f.bind(layers.replace("com.example.persistence.**","com.example.**"));
        assertEquals(UNKNOWN,overlapping.rules().getFirst().candidates().getFirst().eligibility());assertTrue(overlapping.gaps().stream().anyMatch(g->g.reasonCode().equals("LAYER_SELECTOR_OVERLAP")));
        var unmapped=f.bind(layers.replace("com.example.persistence.**","com.example.other.**"));
        assertEquals(UNKNOWN,unmapped.rules().getFirst().candidates().getFirst().eligibility());assertTrue(unmapped.gaps().stream().anyMatch(g->g.reasonCode().equals("LAYER_TARGET_UNMAPPED")));
    }
    @Test void cyclePlanCarriesInducedSelectionWithoutClaimingAnEdgeIsACycle() {
        var f=new Fixture().type("com.example.web","Controller").type("com.example.persistence","Store").edge("Controller","Store");
        String cycle=PolicyCompilerTest.VALID.substring(0,PolicyCompilerTest.VALID.indexOf("    from:"))+"    within: {packages: [com.example.**]}\n";
        var plan=f.bind(cycle.replace("FORBIDDEN_DEPENDENCY","ACYCLIC"));
        assertInstanceOf(ArchitecturePolicy.Cycles.class,plan.rules().getFirst().rule());assertEquals(1,plan.rules().getFirst().candidates().size());
        assertTrue(new ArchitectureQueryService(f.graph()).findCycles(plan.rules().getFirst().request()).items().isEmpty());
    }
    @Test void budgetAndCancellationKeepAllRulesInTheDenominator() {
        var f=new Fixture().type("com.example.web","Controller").type("com.example.persistence","Store").edge("Controller","Store");
        String two=PolicyCompilerTest.VALID+PolicyCompilerTest.VALID.substring(PolicyCompilerTest.VALID.indexOf("  - id:")).replace("web.persistence","web.second");
        var mapper=new PolicyGraphMapper();var compilation=PolicyCompilerTest.compile(two);var limits=new PolicyGraphMapper.Limits(1,ArchitectureQuery.Limits.defaults());
        var plan=mapper.bind(compilation,new ArchitectureQueryService(f.graph()),f.context,limits);
        assertEquals(2,plan.rules().size());assertTrue(plan.rules().stream().allMatch(p->p.status()==AssessmentStatus.WITHHELD));
        assertTrue(plan.gaps().stream().allMatch(g->g.reasonCode().equals("MAPPING_LIMIT")));
        var cancelled=mapper.bind(compilation,new ArchitectureQueryService(f.graph(),()->ArchitectureQuery.StopReason.CANCELLED,Optional.empty()),f.context,PolicyGraphMapper.Limits.defaults());
        assertEquals(2,cancelled.rules().size());assertTrue(cancelled.rules().stream().allMatch(p->p.status()==AssessmentStatus.WITHHELD));
        assertThrows(IllegalArgumentException.class,()->mapper.bind(compilation,new ArchitectureQueryService(f.graph()),"foreign",limits));
        assertEquals(AssessmentStatus.WITHHELD,f.bind(PolicyCompilerTest.VALID.replace("scope: TYPE","scope: INVALID")).status());
    }
}
