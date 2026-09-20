package com.evolution.analysis.javaparser;

import com.evolution.analysis.spring.condition.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConstructorInjectionIngestionTest {
    @Test void recordsLombokAndExplicitConstructorsYieldExactOrderedParameterEvidence()throws Exception {
        var fixture=ComponentIngestionTest.fixture(Map.of("fixture/App.java","""
                package app;
                @org.springframework.boot.autoconfigure.SpringBootApplication class App {}
                @org.springframework.stereotype.Component record RecordService(String name, Integer count) {}
                @org.springframework.stereotype.Component @lombok.RequiredArgsConstructor class Generated { final String name; final Integer count; }
                @org.springframework.stereotype.Component class Explicit { Explicit(String name,Integer count) {} }
                """),true);
        var discovery=new ComponentScanIngestion().scan(fixture.request().manifest(),fixture.frontend(),fixture.framework(),100);
        var provider=new ConstructorInjectionIngestion();var r=provider.ingest(fixture.frontend(),discovery,fixture.framework(),100);
        assertEquals(4,r.rows().stream().filter(row->row.status()==ConstructorInjectionIngestion.Status.SELECTED).count());
        var parameterRows=r.rows().stream().filter(row->!row.parameters().isEmpty()).toList();assertEquals(3,parameterRows.size());
        for(var row:parameterRows) {
            assertEquals(List.of(0,1),row.parameters().stream().map(ConstructorInjectionIngestion.Parameter::index).toList());
            assertEquals(List.of("String","Integer"),row.parameters().stream().map(p->p.type().spelling()).toList());
        }
        assertTrue(r.issues().isEmpty(),r.issues().toString());
        assertEquals(r.identity(),provider.ingest(fixture.frontend(),discovery,fixture.framework(),100).identity());
    }
    @Test void overloadedOrIncompleteConstructorSetsRemainUnknown()throws Exception {
        var fixture=ComponentIngestionTest.fixture(Map.of("fixture/App.java","""
                package app;
                @org.springframework.boot.autoconfigure.SpringBootApplication class App {}
                @org.springframework.stereotype.Component class Overloaded { Overloaded(String s) {} Overloaded(Integer i) {} }
                @org.springframework.stereotype.Component @lombok.RequiredArgsConstructor class Missing { final MissingType value; }
                """),true);
        var discovery=new ComponentScanIngestion().scan(fixture.request().manifest(),fixture.frontend(),fixture.framework(),100);
        var r=new ConstructorInjectionIngestion().ingest(fixture.frontend(),discovery,fixture.framework(),100);
        assertEquals(2,r.rows().stream().filter(row->row.status()==ConstructorInjectionIngestion.Status.UNKNOWN).count());
        assertFalse(r.gaps().isEmpty());
    }
}
