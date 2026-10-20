package com.evolution.analysis.execution;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModuleTopologyTest {
    @Test void allFourNodeGraphsMatchIndependentTransitiveClosureOracle(){
        var pairs=new ArrayList<int[]>();for(int a=0;a<4;a++)for(int b=0;b<4;b++)if(a!=b)pairs.add(new int[]{a,b});
        for(int mask=0;mask<4096;mask++){
            var graph=new TreeMap<String,List<String>>();for(int a=0;a<4;a++)graph.put(""+a,new ArrayList<>());
            boolean[][] reachable=new boolean[4][4];for(int a=0;a<4;a++)reachable[a][a]=true;
            for(int i=0;i<pairs.size();i++)if((mask&(1<<i))!=0){var edge=pairs.get(i);graph.get(""+edge[0]).add(""+edge[1]);reachable[edge[0]][edge[1]]=true;}
            for(int k=0;k<4;k++)for(int a=0;a<4;a++)for(int b=0;b<4;b++)reachable[a][b]|=reachable[a][k]&&reachable[k][b];
            var expected=new HashSet<List<String>>();for(int a=0;a<4;a++){var group=new ArrayList<String>();for(int b=0;b<4;b++)if(reachable[a][b]&&reachable[b][a])group.add(""+b);expected.add(group);}
            assertEquals(expected,new HashSet<>(ModuleTopology.analyze(graph).components()),"mask="+mask);
        }
    }
    @Test void missingEndpointsSelfLoopsAndDownstreamTailRemainDistinct(){
        var result=ModuleTopology.analyze(Map.of("a",List.of("b","missing"),"b",List.of("a","c"),"c",List.of("d"),"d",List.of("d")));
        assertEquals(List.of(List.of("a","b"),List.of("d")),result.cycles());assertEquals(List.of(new ModuleTopology.Missing("a","missing")),result.missing());
    }
    @Test void deepGraphsUseAnExplicitStack(){
        var graph=new TreeMap<String,List<String>>();for(int i=0;i<15000;i++)graph.put("n"+i,i==14999?List.of():List.of("n"+(i+1)));
        assertEquals(15000,ModuleTopology.analyze(graph).components().size());
    }
}
