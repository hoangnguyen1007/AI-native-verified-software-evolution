package com.evolution.analysis.evolution;

import com.evolution.analysis.spring.condition.LogicalValue;
import com.evolution.analysis.spring.truth.SymbolicConfiguration.Limits;
import org.junit.jupiter.api.Test;
import java.util.*;

import static com.evolution.analysis.evolution.ConditionalRegionDelta.*;
import static com.evolution.analysis.spring.condition.LogicalValue.*;
import static com.evolution.analysis.spring.truth.ConfigurationReasoner.Satisfiability.UNSATISFIABLE;
import static com.evolution.analysis.spring.truth.SymbolicConfiguration.and;
import static com.evolution.analysis.spring.truth.SymbolicConfiguration.not;
import static com.evolution.analysis.spring.truth.SymbolicConfiguration.variable;
import static org.junit.jupiter.api.Assertions.*;

class ConditionalRegionDeltaTest {
    @Test void registeredFiniteOracleAndShapes() {
        var vectors=Map.of("MUST","TTT","MAY","TFF","NEVER","FFF","UNKNOWN","TUU");
        var labels=Map.of("FF",Bucket.CLEAN,"FT",Bucket.INTRODUCED,"TF",Bucket.RESOLVED,"TT",Bucket.PERSISTED);
        for(var left:vectors.entrySet())for(var right:vectors.entrySet()) {
            var result=finite(worlds(left.getValue()),worlds(right.getValue()),true,true);
            assertEquals(left.getKey(),result.left().name());
            assertEquals(right.getKey(),result.right().name());
            for(int i=0;i<3;i++) {
                var expected=labels.getOrDefault(""+left.getValue().charAt(i)+right.getValue().charAt(i),Bucket.UNCERTAIN);
                assertEquals(expected,result.worlds().get("w"+i),"E06-"+left.getKey()+"-"+right.getKey());
            }
        }
        assertEquals(Shape.EXPANDED,finite(worlds("TFF"),worlds("TTF"),true,true).shape());
        assertEquals(Shape.SHRUNK,finite(worlds("TTF"),worlds("TFF"),true,true).shape());
        assertEquals(Shape.RESHAPED,finite(worlds("TFT"),worlds("FTT"),true,true).shape());
        assertEquals(Shape.UNKNOWN,finite(worlds("TFU"),worlds("TTU"),true,true).shape());
        assertEquals(Shape.UNCHANGED,finite(worlds("TFF"),worlds("TFF"),true,true).shape());
    }

    @Test void sideOnlyEmptyAndIncompleteAreDifferent() {
        var left=Map.of("w0",new Cell(TRUE, FALSE),"w1",new Cell(TRUE,TRUE));
        var right=Map.of("w0",new Cell(TRUE, FALSE),"w2",new Cell(TRUE,TRUE));
        var result=finite(left,right,true,true);
        assertEquals(Map.of("w0",Bucket.CLEAN,"w1",Bucket.LEFT_ONLY,"w2",Bucket.RIGHT_ONLY),result.worlds());
        assertEquals(Shape.EMPTY,finite(Map.of("w1",left.get("w1")),right,true,true).shape());
        assertEquals("UNKNOWN",finite(Map.of(),Map.of(),true,true).left().name());
        assertEquals(Bucket.UNKNOWN_FEASIBILITY,finite(left,Map.of("w1",new Cell(UNKNOWN,FALSE)),true,false).worlds().get("w1"));
        assertEquals(Bucket.UNMAPPED,finite(left,Map.of(),true,false).worlds().get("w1"));
    }

    @Test void symbolicIntersectionsReplayAndExhaustionIsUnknown() {
        var a=variable("a");var b=variable("b");
        var before=and(a,b);
        var universe=com.evolution.analysis.spring.truth.SymbolicConfiguration.TRUE;
        var result=symbolic(new Predicates(universe,universe,before,not(before)),new Predicates(universe,universe,a,not(a)),Limits.defaults());
        assertEquals(Shape.EXPANDED,result.shape());
        assertEquals(Map.of("a",true,"b",false),result.proofs().get(Bucket.INTRODUCED).assignment());
        assertEquals(Map.of("a",true,"b",true),result.proofs().get(Bucket.PERSISTED).assignment());
        assertEquals(UNSATISFIABLE,result.proofs().get(Bucket.RESOLVED).status());
        var impossible=and(a,not(a));
        var input=new Predicates(impossible,impossible,a,not(a));
        assertEquals(Shape.EMPTY,symbolic(input,input,Limits.defaults()).shape());
        assertEquals(Shape.UNKNOWN,symbolic(input,input,new Limits(1,1,1,1)).shape());
    }

    private static Map<String,Cell> worlds(String vector) {
        var cells=new TreeMap<String,Cell>();
        for(int i=0;i<vector.length();i++) {
            LogicalValue value=switch(vector.charAt(i)){case 'T'->TRUE;case 'F'->FALSE;default->UNKNOWN;};
            cells.put("w"+i,new Cell(TRUE,value));
        }
        return cells;
    }
}
