package com.evolution.cli;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.query.ArchitectureQuery.*;
import java.util.*;

final class CliFixture {
    static String id(String name){return KnowledgeGraph.derivedIdentity("cli.fixture",name);}
    final SnapshotIdentity snapshot=SnapshotIdentity.from(RepositoryIdentity.fromCanonicalCoordinate("https://example.test/evo.git"),ContentDigest.sha256Utf8("snapshot"));
    final String context=snapshot.value();
    final GraphEvidence base=GraphEvidence.capture("fixture.context",Map.of("version","1"));
    final List<GraphEvidence> evidence=new ArrayList<>(List.of(base));final List<KnowledgeGraph.Node> nodes=new ArrayList<>();final List<KnowledgeGraph.Edge> edges=new ArrayList<>();
    CliFixture node(String name){nodes.add(new KnowledgeGraph.Node(id(name),context,"TYPE",List.of(base.identity())));return this;}
    CliFixture edge(String from,String to){edges.add(new KnowledgeGraph.Edge(id("edge"+edges.size()),context,"graph.depends-on",id(from),new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED,List.of(id(to)),Optional.empty()),List.of(base.identity()),Optional.empty()));return this;}
    CliFixture conditional(String from,String to,String world){
        for(String w:List.of("dev","prod"))if(nodes.stream().noneMatch(n->n.identity().equals(id(w))))nodes.add(new KnowledgeGraph.Node(id(w),context,"CONFIGURATION_WORLD",List.of(base.identity())));
        var fact=Map.of("kind","SELECTED_BINDING","factSemanticsVersion",Map.of("id","spring.conditional-fact","version","m4d-v1"));
        var region=GraphEvidence.capture("spring.explicit-region",Map.of("encoding","explicit-worlds-m4d-v1","space",id("space"),"semanticsContext",context,
                "region",Map.of("fact",fact,"trueWorlds",List.of(id(world)),"falseWorlds",List.of(id(world.equals("dev")?"prod":"dev")),"unknownWorlds",List.of(),"classification","MAY","feasibility",Map.of("enumerationComplete",true,"unknownFeasibilityWorlds",0))));
        evidence.add(region);String rid=id("region"+edges.size());nodes.add(new KnowledgeGraph.Node(rid,context,"TRUTH_REGION",List.of(region.identity())));
        edges.add(new KnowledgeGraph.Edge(id("edge"+edges.size()),context,"graph.depends-on",id(from),new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED,List.of(id(to)),Optional.empty()),List.of(region.identity()),Optional.of(rid)));return this;
    }
    KnowledgeGraph graph(){return new KnowledgeGraph(snapshot,List.of(new KnowledgeGraph.Context(context,snapshot,Optional.empty(),base.identity())),nodes,edges,evidence);}
    Request request(){return new Request(graph().identity(),context,Scope.ENTITY,Projection.BOUNDED_UNION,Direction.OUTGOING,Set.of("graph.depends-on"),Population.all(),false,Optional.empty(),new Limits(5,100_000,20,100_000,500_000,1_000_000,100_000,200_000));}
}
