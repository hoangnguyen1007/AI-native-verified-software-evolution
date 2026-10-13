package com.evolution.analysis.spring.condition;

import com.evolution.analysis.ingestion.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConfigTreeClosureTest {
    private ConfigDataIngestion.Result load(Map<String,String> files) {
        return new ConfigDataIngestion().ingest(IngestionFixtures.inputs(files),ConfigDataIngestion.Policy.defaults());
    }
    @Test void capturedTreePreservesOriginsAndOverridesItsImporter() {
        var input=Map.of("src/main/resources/application.properties","spring.config.import=configtree:src/main/resources/tree/\nservice.name=baseline",
                "src/main/resources/tree/service/name","captured\r\n", "src/main/resources/tree/multiline","one\ntwo\n",
                "src/main/resources/tree/empty","", "src/main/resources/tree/spaces"," value \n");
        var result=load(input);
        assertEquals("captured",result.properties().get("service.name"));
        assertEquals("one\ntwo\n",result.properties().get("multiline"));
        assertEquals("",result.properties().get("empty"));assertEquals(" value ",result.properties().get("spaces"));
        assertTrue(result.assignment().isPresent(),result.issues().toString());
        assertEquals(5,result.documents().size());
        assertTrue(result.documents().stream().allMatch(d -> d.evidence() instanceof ConditionEvidence.Source));
        assertEquals(result.identity(),load(input).identity());
    }
    @Test void treePrecedenceAndRepeatedImportsUseCapturedClosure() {
        var result=load(Map.of("src/main/resources/application.properties","spring.config.import=configtree:src/main/resources/first/,configtree:src/main/resources/last/",
                "src/main/resources/first/key","first", "src/main/resources/last/key","last"));
        assertEquals("last",result.properties().get("key"));assertTrue(result.assignment().isPresent(),result.issues().toString());
    }
    @Test void overlappingRootsAndOrdinaryImportsDoNotEraseDistinctInterpretations() {
        var result=load(Map.of("src/main/resources/application.properties",
                "spring.config.import=configtree:src/main/resources/tree/,configtree:src/main/resources/tree/nested/,tree/nested/extra.properties",
                "src/main/resources/tree/nested/key","value", "src/main/resources/tree/nested/extra.properties","parsed=yes"));
        assertEquals("value",result.properties().get("nested.key"));assertEquals("value",result.properties().get("key"));
        assertEquals("parsed=yes",result.properties().get("extra.properties"));assertEquals("yes",result.properties().get("parsed"));
        assertTrue(result.assignment().isPresent(),result.issues().toString());
    }
    @Test void optionalAbsenceDoesNotHideCapturedUnavailableBytes() {
        var missing=load(Map.of("src/main/resources/application.properties","spring.config.import=optional:configtree:src/main/resources/tree/\nindependent=ok"));
        assertTrue(missing.assignment().isPresent());assertEquals("ok",missing.properties().get("independent"));
        var input=IngestionFixtures.inputs(Map.of("src/main/resources/application.properties","spring.config.import=optional:configtree:src/main/resources/tree/",
                "src/main/resources/tree/key","unavailable"));
        var files=new TreeMap<>(input.files());files.remove("src/main/resources/tree/key");
        var unavailable=new ConfigDataIngestion().ingest(new RepositoryInputs(input.snapshot(),files),ConfigDataIngestion.Policy.defaults());
        assertTrue(unavailable.assignment().isEmpty());assertTrue(unavailable.gaps().stream().anyMatch(g -> g.reasonCode().equals("INPUT_UNAVAILABLE")));
    }
    @Test void escapeCollisionDirectivesAndUnprovedEncodingCannotCertifyAssignments() {
        for(String location:List.of("/outside/","../outside/","src/main/resources/tree","src/main/resources/*/")) {
            var result=load(Map.of("src/main/resources/application.properties","spring.config.import=optional:configtree:"+location+"\nindependent=ok"));
            assertTrue(result.assignment().isEmpty());assertEquals("ok",result.properties().get("independent"));
        }
        for(var tree:List.of(Map.of("a.b","one","a/b","two"),Map.of("spring.config.import","outside.properties"),Map.of("value","é"))) {
            var input=new TreeMap<String,String>();input.put("src/main/resources/application.properties","spring.config.import=configtree:src/main/resources/tree/");
            tree.forEach((k,v) -> input.put("src/main/resources/tree/"+k,v));
            var result=load(input);assertTrue(result.assignment().isEmpty());assertFalse(result.gaps().isEmpty());
        }
    }
    @Test void treeLimitsKeepIndependentValuesAndEveryGapDeterministic() {
        var input=IngestionFixtures.inputs(Map.of("src/main/resources/application.properties","spring.config.import=configtree:src/main/resources/tree/\nindependent=ok",
                "src/main/resources/tree/a","first", "src/main/resources/tree/b","second"));
        var policy=new ConfigDataIngestion.Policy(List.of("src/main/resources"),List.of(),Map.of(),2,1000,10,100);
        var result=new ConfigDataIngestion().ingest(input,policy);
        assertEquals("ok",result.properties().get("independent"));assertTrue(result.assignment().isEmpty());
        assertTrue(result.gaps().stream().anyMatch(g -> g.reasonCode().equals("INPUT_LIMIT")));
        assertEquals(result.identity(),new ConfigDataIngestion().ingest(input,policy).identity());
    }
}
