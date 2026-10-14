package com.evolution.analysis.metrics;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.query.ArchitectureQuery.Scope;
import java.util.*;

/** Reviewable PROVISIONAL baseline; catalog approval is distinct from implementation/test evidence. */
public final class MetricCatalog {
    public record Entry(VersionedIdentifier metric,String meaning,String unit,Set<Scope> scopes,String population,Set<String> relationships,
                        String formula,String deduplication,String statusPolicy,String rounding,String inputs) {
        public Entry {scopes=Collections.unmodifiableSet(new TreeSet<>(scopes));relationships=Collections.unmodifiableSet(new TreeSet<>(relationships));}
        public ContentDigest identity(){return ArchitectureMetric.digest(this);}
    }
    private static Entry entry(String id,String meaning,String unit,Set<Scope> scopes,String formula){
        return new Entry(new VersionedIdentifier("metric."+id,"m5.3-v1"),meaning,unit,scopes,
                Set.of("ca","ce","instability","abstractness","distance","density","fan-in","fan-out","hotspots","scc","cycles","crossings","reachability","concentration").contains(id)?ArchitectureMetric.POPULATION:"evidenced-context-inventory:v1",
                Set.of("graph.depends-on","java.* (excluding java.declares and java.has-parameter)","spring.selected-binding","spring.injection-candidate"),formula,
                "Context-qualified canonical identities; distinct directed neighbor pairs; occurrence counts separate.",
                "Observed counts may be PARTIAL. Unknown populations/denominators withhold ratios. Zero denominator is NOT_APPLICABLE. Unknown overlay never contributes definite edges.",
                "Exact integer operands; decimal scale 12, HALF_EVEN; D uses exact rational operands before rounding.",
                "Graph/snapshot, all contributing manifests, query projection/realization/limits, source classification, type shapes, evidence and gaps.");
    }
    private static final Set<Scope> ALL=Set.of(Scope.values()),COMPONENTS=Set.of(Scope.PACKAGE,Scope.MODULE),GRAPH=Set.of(Scope.TYPE,Scope.PACKAGE,Scope.MODULE,Scope.SPRING_COMPONENT);
    private static final List<Entry> ENTRIES=List.of(
        entry("inventory","Observed declarations and source/build inventory; canonical metadata nodes are not declarations.","entities",ALL,"Count distinct declarations; break down kind/origin/source role; count explicit source roots/files/modules separately."),
        entry("physical-loc","Physical decoded source lines, never logical LOC.","lines",ALL,"Empty=0; CRLF/CR/LF each one terminator; final unterminated line counts; BOM does not add a line."),
        entry("quality","Evidence acquisition and semantic accounting, not semantic accuracy or architecture health.","observations",ALL,"Attempted=emitted+unmapped; category/status/origin/parse/attempt/conflict/gap and full-span counts; no accuracy without ground truth."),
        entry("ca","Afferent component coupling.","neighbors",COMPONENTS,"Distinct incoming project MAIN nongenerated neighboring components; no self edges."),
        entry("ce","Efferent component coupling.","neighbors",COMPONENTS,"Distinct outgoing project MAIN nongenerated neighboring components; no syntax-call multiplicity."),
        entry("instability","Martin instability I.","ratio",COMPONENTS,"Ce/(Ca+Ce); denominator zero => NOT_APPLICABLE."),
        entry("abstractness","Martin abstractness A.","ratio",COMPONENTS,"Na/N; interface, abstract class, annotation type in Na; enums/records concrete; N=0 => NOT_APPLICABLE."),
        entry("distance","Distance to main sequence D.","ratio",COMPONENTS,"abs(Na*(Ca+Ce)+Ce*N-N*(Ca+Ce))/(N*(Ca+Ce)); absent A/I => NOT_APPLICABLE."),
        entry("fan-in","Distinct incoming projected neighbors.","neighbors",GRAPH,"Count incoming neighbors by component; whole-context value sums per-component counts."),
        entry("fan-out","Distinct outgoing projected neighbors.","neighbors",GRAPH,"Count outgoing neighbors by component; whole-context value sums per-component counts."),
        entry("hotspots","Stable structural degree ranking; no bad-architecture threshold.","pairs",GRAPH,"Rank by fanIn+fanOut descending, then canonical identity; return bounded ranking and exact population size."),
        entry("density","Directed simple dependency density within the declared component population.","ratio",GRAPH,"Distinct nonself pairs/(n*(n-1)); n<2 => NOT_APPLICABLE; external exposures separate."),
        entry("scc","Structural SCC inventory, separate from feasible cycle proofs.","components",GRAPH,"Iterative SCC decomposition; sizes/participants/cyclic components; union SCCs remain exploratory."),
        entry("cycles","Jointly feasible simple directed cycles; witness count is not exact cycle total.","cycles",GRAPH,"Delegate to bounded query cycle engine; value only if exactTotal proven; witnesses/frontier always explicit."),
        entry("crossings","Distinct component/layer boundary pairs.","pairs",GRAPH,"Count distinct component nonself pairs whose assigned layers differ, using versioned mapping; absent mapping => NOT_APPLICABLE. Multiple component pairs in the same layer pair remain distinct."),
        entry("reachability","Bounded structural dependent reachability and shortest-distance summaries.","entities",GRAPH,"INCOMING query reachability; distinct dependent endpoints, distances and frontier; no exact longest path."),
        entry("concentration","Maximum dependency-pair share by target component.","ratio",GRAPH,"Maximum target incoming pairs/total internal projected pairs; stable target ranking; unresolved/external exposures separate."),
        entry("spring","Discovered and projected-active Spring evidence inventory.","candidates",Set.of(Scope.SPRING_COMPONENT),"Distinct candidates/producers/sites/uses/events; active definitions by selected projection, unknown residue and binding/candidate cardinalities separate; routes only from evidence."),
        entry("operational","Measured phase duration with observation provenance.","nanoseconds",ALL,"Supplied measured phase durations only; absent measurement => WITHHELD, projection sizes reported separately."));
    public static List<Entry> entries(){return ENTRIES.stream().sorted(Comparator.comparing(e->e.metric().id())).toList();}
    public static Entry require(String id){return entries().stream().filter(e->e.metric().id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown metric ID"));}
    public static ContentDigest identity(){return ArchitectureMetric.digest(List.of("metric-catalog:provisional-m5.3-v1",entries()));}
    private MetricCatalog() {}
}
