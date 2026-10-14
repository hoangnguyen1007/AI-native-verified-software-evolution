package com.evolution.analysis.query;

import java.util.*;
import java.util.function.Supplier;
import static com.evolution.analysis.query.ArchitectureQuery.*;

/** One cumulative request budget. Preprocessing is bounded separately from enumeration work. */
final class QueryBudget {
    final Limits limits;
    final Supplier<StopReason> operationalGuard;
    long steps,solverSteps,rejected,unknown,preprocessing,auxiliary,descriptorUnits;
    int components,cyclic;
    StopReason stop=StopReason.NONE;
    final SortedSet<String> reasons=new TreeSet<>(),frontier=new TreeSet<>();
    QueryBudget(Limits limits,Supplier<StopReason> guard){this.limits=limits;this.operationalGuard=guard;}
    boolean running(){
        if(stop!=StopReason.NONE)return false;
        StopReason reason=Objects.requireNonNull(operationalGuard.get());
        if(reason!=StopReason.NONE)halt(reason);
        return stop==StopReason.NONE;
    }
    boolean step(){if(!running())return false;if(steps==limits.maxSteps()){halt(StopReason.STEPS);return false;}steps++;return true;}
    boolean scan(){if(!running())return false;if(preprocessing>=8L*(limits.maxVertices()+limits.maxEdges()+limits.maxFormulaNodes())){halt(StopReason.INPUT);return false;}preprocessing++;return true;}
    boolean reserve(long units){if(!running())return false;if(units<0||units>limits.maxAuxiliaryStates()-auxiliary){halt(StopReason.INPUT);return false;}auxiliary+=units;return true;}
    void halt(StopReason reason){if(stop==StopReason.NONE)stop=reason;reasons.add(reason.name());}
    void qualify(String reason){reasons.add(reason);}
    void frontier(String identity){if(frontier.size()<limits.maxResults())frontier.add(identity);}
    Bounds bounds(int returned){return new Bounds(stop!=StopReason.NONE,stop==StopReason.STEPS||stop==StopReason.DEPTH,
            stop==StopReason.OUTPUT,stop,steps,solverSteps,returned,rejected,unknown,components,cyclic,List.copyOf(frontier));}
}
