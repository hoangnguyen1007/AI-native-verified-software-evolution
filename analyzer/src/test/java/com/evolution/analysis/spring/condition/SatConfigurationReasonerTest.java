package com.evolution.analysis.spring.condition;

import com.evolution.analysis.spring.truth.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.spring.truth.SymbolicConfiguration.*;
import static com.evolution.analysis.spring.truth.ConfigurationReasoner.Satisfiability.*;

class SatConfigurationReasonerTest {
    @Test void solvesFiftySixDimensionsWithoutEnumeratingAndReplaysWitness() {
        var variables = java.util.stream.IntStream.range(0, 56).mapToObj(i -> variable("profile-" + i)).toList();
        var query = new Query(and(variables), Limits.defaults());
        var result = SatConfigurationReasoner.INSTANCE.solve(query);
        assertEquals(SATISFIABLE, result.status());
        assertEquals(56, result.witness().size());
        assertTrue(query.formula().evaluate(result.witness()));
        assertEquals(result, SatConfigurationReasoner.INSTANCE.solve(query));
        assertTrue(result.steps() < 100_000);
    }
    @Test void provesContradictionAndDoesNotTurnExhaustionIntoUnsatisfiable() {
        var x = variable("x"); var y = variable("y");
        var impossible = and(or(x,y), or(not(x),y), or(x,not(y)), or(not(x),not(y)));
        assertEquals(UNSATISFIABLE, SatConfigurationReasoner.INSTANCE.solve(new Query(impossible, Limits.defaults())).status());
        var limited = SatConfigurationReasoner.INSTANCE.solve(new Query(impossible, new Limits(100,100,1,32)));
        assertEquals(UNKNOWN, limited.status()); assertEquals(Optional.of("SOLVER_LIMIT"), limited.reason());
    }
    @Test void matchesIndependentExhaustiveBooleanOracle() {
        var random = new Random(2142); var variables = List.of(variable("a"), variable("b"), variable("c"), variable("d"));
        for (int trial=0; trial<100; trial++) {
            var clauses = new ArrayList<Formula>();
            for(int c=0;c<12;c++) {
                var terms = new ArrayList<Formula>();
                for(int j=0;j<3;j++) {var term=variables.get(random.nextInt(4));terms.add(random.nextBoolean()?term:not(term));}
                clauses.add(or(terms));
            }
            var formula=and(clauses);boolean exists=false;
            for(int mask=0;mask<16;mask++) {var world=new HashMap<String,Boolean>();for(int j=0;j<4;j++)world.put(variables.get(j).variable(),(mask&(1<<j))!=0);exists|=formula.evaluate(world);}
            var answer=SatConfigurationReasoner.INSTANCE.solve(new Query(formula,Limits.defaults()));
            assertEquals(exists?SATISFIABLE:UNSATISFIABLE,answer.status());
            if(exists)assertTrue(formula.evaluate(answer.witness()));
        }
    }
    @Test void symbolicImplicationEquivalenceConstantsAndCanonicalOrder() {
        var a=variable("a");var b=variable("b");
        assertEquals(and(a,b).identity(),and(b,a,a).identity());
        assertEquals(LogicalValue.TRUE,SatConfigurationReasoner.INSTANCE.implies(and(a,b),a,Limits.defaults()));
        assertEquals(LogicalValue.FALSE,SatConfigurationReasoner.INSTANCE.implies(a,and(a,b),Limits.defaults()));
        assertEquals(LogicalValue.TRUE,SatConfigurationReasoner.INSTANCE.equivalent(and(a,b),and(b,a),Limits.defaults()));
        assertEquals(UNSATISFIABLE,SatConfigurationReasoner.INSTANCE.solve(new Query(and(and(a,b),not(a)),Limits.defaults())).status());
        assertEquals(UNSATISFIABLE,SatConfigurationReasoner.INSTANCE.solve(new Query(or(and(and(a,b),not(and(b,a))),and(and(b,a),not(and(a,b)))),Limits.defaults())).status());
        assertEquals(SATISFIABLE,SatConfigurationReasoner.INSTANCE.solve(new Query(TRUE,Limits.defaults())).status());
        assertEquals(UNSATISFIABLE,SatConfigurationReasoner.INSTANCE.solve(new Query(FALSE,Limits.defaults())).status());
        Formula deep=a;for(int i=0;i<40;i++)deep=or(variable("n"+i),and(b,deep));
        assertEquals(UNKNOWN,SatConfigurationReasoner.INSTANCE.solve(new Query(deep,new Limits(1000,1000,10000,16))).status());
    }
}
