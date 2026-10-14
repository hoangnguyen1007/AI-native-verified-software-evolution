package com.evolution.analysis.metrics;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.query.*;
import java.util.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StructuralMetricsTest {
    static String id(String text){return KnowledgeGraph.derivedIdentity("fixture",text);}
    static final class Fixture {
        final RepositoryIdentity repo=RepositoryIdentity.fromCanonicalCoordinate("https://example.test/metric.git");
        final ModuleDescriptor module=ModuleDescriptor.create(repo,"fixture","fixture");
        final SnapshotIdentity snapshot=SnapshotIdentity.from(repo,ContentDigest.sha256Utf8("snapshot"));
        final String context=snapshot.value();final List<KnowledgeGraph.Node> nodes=new ArrayList<>();final List<KnowledgeGraph.Edge> edges=new ArrayList<>();final List<GraphEvidence> evidence=new ArrayList<>();
        final GraphEvidence base=GraphEvidence.capture("fixture.context",Map.of("version","1"));
        Fixture(){evidence.add(base);var descriptor=GraphEvidence.capture("module.descriptor",module);evidence.add(descriptor);nodes.add(new KnowledgeGraph.Node(module.identity().value(),context,"MODULE",List.of(descriptor.identity())));}
        Fixture type(String pkg,String name,TypeDeclarationRecord.Kind kind,boolean abs,SourceClassification role,String origin){
            var document=SourceDocument.create(repo,module,"src/"+role+"/"+name+".java",ContentDigest.sha256Utf8(name),role);
            var doc=GraphEvidence.capture("source.document",document);evidence.add(doc);nodes.add(new KnowledgeGraph.Node(document.identity().value(),context,"SOURCE_DOCUMENT",List.of(doc.identity())));
            var decl=GraphEvidence.capture("java.declaration",Map.of("entity",Map.of("identity",id(name),"kind","TYPE","origin",origin,"stableScope",module.identity(),"canonicalName",JavaSymbolName.topLevelType(pkg,name).canonicalName(),
                    "declaration",Map.of("document",document.identity(),"startLine",1,"startColumn",1,"endLine",1,"endColumn",2)),"spelling",name,"status","RESOLVED"));evidence.add(decl);
            var shape=GraphEvidence.capture("java.type-shape",Map.of("type",id(name),"kind",kind,"abstractType",abs,"independent",true));evidence.add(shape);
            nodes.add(new KnowledgeGraph.Node(id(name),context,"TYPE",List.of(decl.identity())));
            if(nodes.stream().noneMatch(n->n.identity().equals(id(pkg))))nodes.add(new KnowledgeGraph.Node(id(pkg),context,"PACKAGE",List.of(decl.identity())));
            link("graph.contains",module.identity().value(),id(pkg));link("graph.contains",id(pkg),id(name));return this;
        }
        Fixture type(String pkg,String name,boolean abs){return type(pkg,name,abs?TypeDeclarationRecord.Kind.INTERFACE:TypeDeclarationRecord.Kind.CLASS,abs,SourceClassification.MAIN,"PROJECT");}
        void link(String kind,String from,String to){edges.add(new KnowledgeGraph.Edge(id("edge"+edges.size()),context,kind,from,new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED,List.of(to),Optional.empty()),List.of(base.identity()),Optional.empty()));}
        Fixture dependency(String from,String to){link("graph.depends-on",id(from),id(to));return this;}
        KnowledgeGraph graph(){return new KnowledgeGraph(snapshot,List.of(new KnowledgeGraph.Context(context,snapshot,Optional.empty(),base.identity())),nodes,edges,evidence);}
        ArchitectureQuery.Request request(){return new ArchitectureQuery.Request(graph().identity(),context,ArchitectureQuery.Scope.PACKAGE,ArchitectureQuery.Projection.BOUNDED_UNION,ArchitectureQuery.Direction.OUTGOING,
                Set.of("graph.depends-on"),ArchitectureQuery.Population.all(),false,Optional.empty(),ArchitectureQuery.Limits.defaults());}
    }
    static Fixture golden(){return new Fixture().type("p","PI",true).type("p","PC",false).type("q","Q",false).type("r","R",false).dependency("PC","Q").dependency("R","PC");}
    static java.math.BigDecimal amount(ArchitectureMetric.Result result){return result.value().orElseThrow().amount();}
    static Fixture springFixture(boolean unknownActivation){
        var f=new Fixture().type("app","Controller",false).type("data","Repository",false);
        String world=id("spring-world");f.nodes.add(new KnowledgeGraph.Node(world,f.context,"CONFIGURATION_WORLD",List.of(f.base.identity())));
        for(String name:List.of("controller-bean","repository-bean")){
            String bean=id(name);f.nodes.add(new KnowledgeGraph.Node(bean,f.context,"BEAN_DEFINITION_CANDIDATE",List.of(f.base.identity())));
            boolean unknown=unknownActivation&&name.equals("repository-bean");
            var proof=GraphEvidence.capture("spring.explicit-region",Map.of("encoding","explicit-worlds-m4d-v1","space",id("spring-space"),"semanticsContext",f.context,
                    "region",Map.of("fact",Map.of("kind","DEFINITION_PRESENT","definition",bean,"factSemanticsVersion",Map.of("id","spring.conditional-fact","version","m4d-v1")),
                            "trueWorlds",unknown?List.of():List.of(world),"falseWorlds",List.of(),"unknownWorlds",unknown?List.of(world):List.of(),"classification",unknown?"UNKNOWN":"MUST",
                            "feasibility",Map.of("enumerationComplete",true,"unknownFeasibilityWorlds",0))));
            f.evidence.add(proof);f.nodes.add(new KnowledgeGraph.Node(id(name+"-activation"),f.context,"TRUTH_REGION",List.of(proof.identity())));
        }
        f.link("spring.selected-binding",id("controller-bean"),id("repository-bean"));return f;
    }
    static ArchitectureQuery.Request springRequest(Fixture f){
        var r=f.request();return new ArchitectureQuery.Request(r.graph(),r.context(),ArchitectureQuery.Scope.SPRING_COMPONENT,r.projection(),r.direction(),Set.of("spring.selected-binding"),r.population(),false,Optional.empty(),r.limits());
    }
    @Test void completeSpringComponentMetricsIgnoreJavaMartinOwnership(){
        var f=springFixture(false);var service=new ArchitectureMetricsService(f.graph());
        var expected=Map.of("metric.density",new BigDecimal("0.5"),"metric.scc",new BigDecimal("2"),"metric.cycles",BigDecimal.ZERO,"metric.hotspots",BigDecimal.ONE,"metric.concentration",BigDecimal.ONE);
        for(var row:new TreeMap<>(expected).entrySet()){
            var result=service.compute(springRequest(f),row.getKey(),f.context);
            assertEquals(AssessmentStatus.COMPLETE,result.status(),()->row.getKey()+": "+result.limitations());assertTrue(result.gaps().isEmpty());assertEquals(row.getValue(),amount(result));
        }
    }
    @Test void springUnknownActivationStillWithholdsRatiosWithGenuineGaps(){
        var f=springFixture(true);var result=new ArchitectureMetricsService(f.graph()).compute(springRequest(f),"metric.density",f.context);
        assertEquals(AssessmentStatus.WITHHELD,result.status());assertTrue(result.value().isEmpty());assertFalse(result.gaps().isEmpty());
        assertTrue(result.gaps().stream().anyMatch(g->g.reasonCode().equals("UNKNOWN_FEASIBILITY")));assertFalse(result.gaps().stream().anyMatch(g->g.reasonCode().equals("COMPONENT_OWNER_UNAVAILABLE")));
    }
    @Test void nonMartinJavaMetricsStillValidateTheirProductionPopulation(){
        var f=new Fixture().type("p","P",false);f.nodes.removeIf(n->n.kind().equals("SOURCE_DOCUMENT"));
        var result=new ArchitectureMetricsService(f.graph()).compute(f.request(),"metric.density",f.context);
        assertEquals(AssessmentStatus.WITHHELD,result.status());assertTrue(result.gaps().stream().anyMatch(g->g.reasonCode().equals("SOURCE_POPULATION_UNAVAILABLE")));
    }
    @Test void fanCountsDistinctNeighborsPerComponentAndSumsAcrossContext(){
        var f=golden().dependency("PC","Q").dependency("PC","PI");var service=new ArchitectureMetricsService(f.graph());
        var incoming=Map.of("p",1,"q",1,"r",0);var outgoing=Map.of("p",1,"q",0,"r",1);
        for(String pkg:List.of("p","q","r")){
            var fanIn=service.compute(f.request(),"metric.fan-in",id(pkg));var fanOut=service.compute(f.request(),"metric.fan-out",id(pkg));
            assertEquals(AssessmentStatus.COMPLETE,fanIn.status());assertEquals(AssessmentStatus.COMPLETE,fanOut.status());
            assertEquals(BigDecimal.valueOf(incoming.get(pkg)),amount(fanIn));assertEquals(BigDecimal.valueOf(outgoing.get(pkg)),amount(fanOut));
        }
        assertEquals(new BigDecimal("2"),amount(service.compute(f.request(),"metric.fan-in",f.context)));assertEquals(new BigDecimal("2"),amount(service.compute(f.request(),"metric.fan-out",f.context)));
    }
    @Test void reachabilityCountsIncomingDependentsAndShortestWitnessDistances(){
        var f=golden().dependency("R","PC");var result=new ArchitectureMetricsService(f.graph()).compute(f.request(),"metric.reachability",id("q"));
        assertEquals(AssessmentStatus.COMPLETE,result.status());assertTrue(result.gaps().isEmpty());assertEquals(new BigDecimal("2"),amount(result));
        var distances=new TreeMap<String,BigDecimal>();
        for(Object item:GraphJson.array(result.details().get("distances"))){var row=GraphJson.object(item);assertEquals(Set.of("identity","distance","path"),row.keySet());
            distances.put((String)row.get("identity"),(BigDecimal)row.get("distance"));assertDoesNotThrow(()->new ContentDigest((String)row.get("path")));}
        assertEquals(Map.of(id("p"),BigDecimal.ONE,id("r"),new BigDecimal("2")),distances);
        var service=new ArchitectureMetricsService(f.graph());var empty=service.compute(f.request(),"metric.reachability",id("r"));assertEquals(AssessmentStatus.COMPLETE,empty.status());assertEquals(BigDecimal.ZERO,amount(empty));assertTrue(GraphJson.array(empty.details().get("distances")).isEmpty());
        var context=service.compute(f.request(),"metric.reachability",f.context);assertEquals(AssessmentStatus.NOT_APPLICABLE,context.status());assertTrue(context.value().isEmpty());
    }
    @Test void boundedReachabilityRetainsObservedCountDistancesAndFrontier(){
        var f=golden();var r=f.request();var limits=new ArchitectureQuery.Limits(20,100000,1,100000,500000,1000000,100000,200000);
        var request=new ArchitectureQuery.Request(r.graph(),r.context(),r.scope(),r.projection(),r.direction(),r.relationshipKinds(),r.population(),false,Optional.empty(),limits);
        var service=new ArchitectureMetricsService(f.graph());var result=service.compute(request,"metric.reachability",id("q"));
        assertEquals(AssessmentStatus.PARTIAL,result.status());assertEquals(BigDecimal.ONE,amount(result));assertFalse(result.gaps().isEmpty());
        var row=GraphJson.object(GraphJson.array(result.details().get("distances")).getFirst());assertEquals(id("p"),row.get("identity"));assertEquals(BigDecimal.ONE,row.get("distance"));
        var bounds=service.evaluate(request,"metric.reachability",id("q")).bounds();assertTrue(bounds.truncated());assertTrue(bounds.outputBudgetExceeded());assertEquals(ArchitectureQuery.StopReason.OUTPUT,bounds.stopReason());assertFalse(bounds.frontier().isEmpty());
    }
    @Test void moduleMetricsUseProductionTypesWithoutRequiringASourceRoleOnTheAggregateNode(){
        var f=golden();var r=f.request();var request=new ArchitectureQuery.Request(r.graph(),r.context(),ArchitectureQuery.Scope.MODULE,r.projection(),r.direction(),r.relationshipKinds(),r.population(),false,Optional.empty(),r.limits());
        var engine=new ArchitectureMetricsService(f.graph());var count=engine.compute(request,"metric.ce",f.module.identity().value());assertEquals(com.evolution.analysis.contract.assessment.AssessmentStatus.COMPLETE,count.status());assertEquals(java.math.BigDecimal.ZERO,amount(count));
        assertEquals(new java.math.BigDecimal("0.25"),amount(engine.compute(request,"metric.abstractness",f.module.identity().value())));
    }
    @Test void singletonSelfLoopIsCyclicButNotADensityPair(){
        var f=new Fixture().type("p","P",false).dependency("P","P");var r=f.request();var request=new ArchitectureQuery.Request(r.graph(),r.context(),ArchitectureQuery.Scope.TYPE,r.projection(),r.direction(),r.relationshipKinds(),r.population(),false,Optional.empty(),r.limits());
        var service=new ArchitectureMetricsService(f.graph());assertEquals(java.math.BigDecimal.ONE,service.compute(request,"metric.scc",r.context()).details().get("cyclicComponents"));
        assertEquals(java.math.BigDecimal.ONE,amount(service.compute(request,"metric.cycles",r.context())));assertEquals(com.evolution.analysis.contract.assessment.AssessmentStatus.NOT_APPLICABLE,service.compute(request,"metric.density",r.context()).status());
    }
    @Test void martinGoldenCountsDistinctNeighborsInsteadOfCallMultiplicity(){
        var f=golden();for(int i=0;i<10;i++)f.dependency("PC","Q");var service=new ArchitectureMetricsService(f.graph());
        assertEquals(new java.math.BigDecimal("0.5"),amount(service.compute(f.request(),"metric.instability",id("p"))));
        assertEquals(new java.math.BigDecimal("0.5"),amount(service.compute(f.request(),"metric.abstractness",id("p"))));
        assertEquals(java.math.BigDecimal.ZERO,amount(service.compute(f.request(),"metric.distance",id("p"))));
        assertEquals(java.math.BigDecimal.ONE,amount(service.compute(f.request(),"metric.ca",id("p"))));assertEquals(java.math.BigDecimal.ONE,amount(service.compute(f.request(),"metric.ce",id("p"))));
    }
    @Test void densityUsesNonselfDistinctPairsAndExactOperands(){
        var f=golden().dependency("PC","PI");var result=new ArchitectureMetricsService(f.graph()).compute(f.request(),"metric.density",f.context);
        assertEquals(new java.math.BigDecimal("0.333333333333"),amount(result));assertEquals(new java.math.BigDecimal("2"),result.ratio().orElseThrow().numerator());assertEquals(new java.math.BigDecimal("6"),result.ratio().orElseThrow().denominator());
    }
    @Test void productionAbstractnessIncludesEnumsRecordsAndAnnotationTypesButNotUsesOrOtherRoles(){
        var f=golden().type("p","EnumType",TypeDeclarationRecord.Kind.ENUM,false,SourceClassification.MAIN,"PROJECT")
                .type("p","RecordType",TypeDeclarationRecord.Kind.RECORD,false,SourceClassification.MAIN,"PROJECT")
                .type("p","AnnotationType",TypeDeclarationRecord.Kind.ANNOTATION,false,SourceClassification.MAIN,"PROJECT")
                .type("p","TestInterface",TypeDeclarationRecord.Kind.INTERFACE,true,SourceClassification.TEST,"PROJECT")
                .type("p","GeneratedInterface",TypeDeclarationRecord.Kind.INTERFACE,true,SourceClassification.GENERATED_MAIN,"PROJECT")
                .type("p","ExternalInterface",TypeDeclarationRecord.Kind.INTERFACE,true,SourceClassification.MAIN,"DEPENDENCY");
        for(int i=0;i<10;i++)f.nodes.add(new KnowledgeGraph.Node(id("annotation-use"+i),f.context,"ANNOTATION_USE",List.of(f.base.identity())));
        var result=new ArchitectureMetricsService(f.graph()).compute(f.request(),"metric.abstractness",id("p"));assertEquals(new java.math.BigDecimal("0.4"),amount(result));
        assertEquals(new java.math.BigDecimal("2"),result.details().get("Na"));assertEquals(new java.math.BigDecimal("5"),result.details().get("N"));
        assertEquals(new java.math.BigDecimal("0.5"),amount(new ArchitectureMetricsService(f.graph()).compute(f.request(),"metric.instability",id("p"))));
    }
    @Test void zeroDenominatorsAndMissingPopulationAreNotInventedZeros(){
        var f=new Fixture().type("alone","Only",false);var service=new ArchitectureMetricsService(f.graph());
        for(String metric:List.of("metric.instability","metric.distance")){
            var r=service.compute(f.request(),metric,id("alone"));assertEquals(com.evolution.analysis.contract.assessment.AssessmentStatus.NOT_APPLICABLE,r.status());assertTrue(r.value().isEmpty());assertEquals(java.math.BigDecimal.ZERO,r.ratio().orElseThrow().denominator());}
        assertEquals(java.math.BigDecimal.ZERO,amount(service.compute(f.request(),"metric.abstractness",id("alone"))));
        var empty=new Fixture();empty.nodes.add(new KnowledgeGraph.Node(id("empty"),empty.context,"PACKAGE",List.of(empty.base.identity())));
        var a=new ArchitectureMetricsService(empty.graph()).compute(empty.request(),"metric.abstractness",id("empty"));assertTrue(a.value().isEmpty());assertEquals(com.evolution.analysis.contract.assessment.AssessmentStatus.NOT_APPLICABLE,a.status());assertEquals(java.math.BigDecimal.ZERO,a.ratio().orElseThrow().denominator());
        var broken=golden();broken.evidence.removeIf(e->e.family().equals("java.type-shape"));
        var withheld=new ArchitectureMetricsService(broken.graph()).compute(broken.request(),"metric.abstractness",id("p"));assertEquals(com.evolution.analysis.contract.assessment.AssessmentStatus.WITHHELD,withheld.status());assertFalse(withheld.gaps().isEmpty());
        assertEquals(new java.math.BigDecimal("0.5"),amount(new ArchitectureMetricsService(broken.graph()).compute(broken.request(),"metric.instability",id("p"))));
    }
    @Test void sccHotspotConcentrationAndLayerCrossingsHaveExplicitMeaning(){
        var f=golden().dependency("Q","PC");var graph=f.graph();var service=new ArchitectureMetricsService(graph);
        assertEquals(new java.math.BigDecimal("2"),amount(service.compute(f.request(),"metric.scc",f.context)));
        assertEquals(java.math.BigDecimal.ONE,amount(service.compute(f.request(),"metric.cycles",f.context)));
        var concentration=service.compute(f.request(),"metric.concentration",f.context);assertEquals(new java.math.BigDecimal("0.666666666667"),amount(concentration));
        assertEquals(new java.math.BigDecimal("3"),amount(service.compute(f.request(),"metric.hotspots",f.context)));
        assertEquals(com.evolution.analysis.contract.assessment.AssessmentStatus.NOT_APPLICABLE,service.compute(f.request(),"metric.crossings",f.context).status());
        var mapping=new ArchitectureMetric.LayerMapping(new VersionedIdentifier("layers.authored","1"),Map.of(id("p"),"app",id("q"),"data",id("r"),"app"));
        var mapped=new ArchitectureMetricsService(graph,ArchitectureMetric.Limits.defaults(),()->ArchitectureQuery.StopReason.NONE,Optional.of(mapping),List.of());
        assertEquals(new java.math.BigDecimal("2"),amount(mapped.compute(f.request(),"metric.crossings",f.context)));
    }
}
