package com.evolution.analysis.polyglot;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.graph.GraphJson;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Query controls, not additional native-language qualification. */
class BridgeTopologyTest {
    @Test void scopedCycleAndBoundaryAnswersRetainStateAndJointFeatureFeasibility()throws Exception{
        for(String variant:List.of("compatible","candidate","conflict","unknown")){
            var f=new ArchitectureBridgesTest.Fixture();f.pair(ArchitectureBridges.Profile.B06);var input=f.input();var original=f.run();var base=f.read(original.relations()).getFirst().fields();var a=GraphJson.object(base.get("from"));var b=GraphJson.object(GraphJson.array(base.get("targets")).getFirst());
            var first=new TreeMap<String,Object>(base);first.put("from",qualified(a,"on",false));first.put("targets",List.of(qualified(b,"on",false)));
            var second=new TreeMap<String,Object>(base);second.put("from",qualified(b,variant.equals("conflict")?"off":"on",variant.equals("unknown")));second.put("targets",List.of(qualified(a,"on",false)));if(variant.equals("candidate"))second.put("state","CANDIDATE");
            SemanticTables.Row requirement;try(var reader=f.tables.open(input.requirements(),()->false)){requirement=reader.next();}
            var needA=new SemanticTables.Row(SemanticTables.Kind.CENSUS,"a",input.scope(),requirement.fields());var needB=new SemanticTables.Row(SemanticTables.Kind.CENSUS,"b",input.scope(),requirement.fields());
            input=new ArchitectureBridges.Input(input.root(),input.inventory(),input.policy(),input.partitions(),input.endpoints(),f.tables.write(SemanticTables.Kind.CENSUS,List.of(needA,needB).iterator(),()->false));first.put("requirement",needA.identity());second.put("requirement",needB.identity());
            var rows=List.of(new SemanticTables.Row(SemanticTables.Kind.OBSERVATION,"a",input.scope(),first),new SemanticTables.Row(SemanticTables.Kind.OBSERVATION,"b",input.scope(),second));var relations=f.tables.write(SemanticTables.Kind.OBSERVATION,rows.iterator(),()->false);var counts=new TreeMap<>(original.counts());counts.put("BOUND",variant.equals("candidate")?1L:2L);if(variant.equals("candidate"))counts.put("CANDIDATE",1L);var result=new ArchitectureBridges.Result(input.identity(),original.revocations(),original.semanticIdentity(),relations,original.gaps(),counts,original.partitions(),original.endpoints(),original.missingCensus());
            var view=new BridgeQueryService.View(f.capture,1,input,result);var queries=new BridgeQueryService((scope,cancelled)->Optional.of(view),f.tables);var topology=queries.topology(input.scope(),new ContentDigest(GraphJson.string(a.get("identity"))),4,20,100,()->false);
            assertEquals(1,topology.cycles().size());assertEquals(2,topology.boundaries().size());assertEquals(variant.equals("compatible")?"DECLARED_STRUCTURAL_CYCLE":variant.equals("conflict")?"INFEASIBLE":"POTENTIAL_CYCLE",topology.cycles().getFirst().classification());assertFalse(topology.impact().runtimeBreakageProven());
            assertTrue(queries.topology(input.scope(),new ContentDigest(GraphJson.string(a.get("identity"))),4,20,1,()->false).cycles().isEmpty());
        }
    }
    private static Map<String,Object> qualified(Map<String,Object> ref,String feature,boolean unknown){var result=new TreeMap<String,Object>(ref);result.put("condition",Map.of("domain",ContentDigest.sha256Utf8("shared feature domain").value(),"selection",Map.of("feature",feature),"unknown",unknown));return result;}
}
