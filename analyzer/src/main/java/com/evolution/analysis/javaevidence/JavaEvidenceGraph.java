package com.evolution.analysis.javaevidence;

import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.ingestion.UniversalIngestionPipeline;
import java.io.IOException;
import java.util.*;

/** Neutral, immutable base-to-derived binding captured by the assembler. */
public final class JavaEvidenceGraph {
    private final SnapshotIdentity base,derived;private final List<GraphEvidence> evidence;private final Optional<JavaSemanticEvidence.Result> postAnalysis;private final List<JavaCompilerAttribution.Result> compiler;
    private final Optional<JavaEvidenceGapClosure> gapClosure;
    private final Optional<JavaEvidenceGapClosure.Current> gapCurrent;
    JavaEvidenceGraph(SnapshotIdentity base,SnapshotIdentity derived,List<GraphEvidence> evidence){this(base,derived,evidence,Optional.empty(),List.of(),Optional.empty(),Optional.empty());}
    private JavaEvidenceGraph(SnapshotIdentity base,SnapshotIdentity derived,List<GraphEvidence> evidence,Optional<JavaSemanticEvidence.Result> postAnalysis,List<JavaCompilerAttribution.Result> compiler,Optional<JavaEvidenceGapClosure> gapClosure,Optional<JavaEvidenceGapClosure.Current> gapCurrent){this.base=Objects.requireNonNull(base);this.derived=Objects.requireNonNull(derived);this.evidence=List.copyOf(evidence);this.postAnalysis=postAnalysis;this.compiler=List.copyOf(compiler);this.gapClosure=gapClosure;this.gapCurrent=gapCurrent;}
    public SnapshotIdentity base(){return base;}public SnapshotIdentity derived(){return derived;}public List<GraphEvidence> evidence(){return evidence;}
    public JavaEvidenceGraph withPostAnalysis(JavaSemanticEvidence.Result result)throws IOException{
        if(postAnalysis.isPresent()||!derived.equals(result.snapshot())||evidence.stream().noneMatch(e->e.family().equals("java.evidence.view")&&ContentDigest.sha256Utf8(e.canonicalJson()).equals(result.view())))throw new IllegalArgumentException("Foreign post-analysis Java evidence");
        if(!result.current())throw new IOException("JAVA_SEMANTIC_EVIDENCE_REVOKED");var combined=new ArrayList<>(evidence);combined.addAll(result.evidence());return new JavaEvidenceGraph(base,derived,combined,Optional.of(result),compiler,gapClosure,gapCurrent);
    }
    public JavaEvidenceGraph withCompilerAttribution(JavaCompilerAttribution.Result result)throws IOException{
        if(postAnalysis.isPresent()||!derived.equals(result.snapshot())||evidence.stream().noneMatch(e->e.family().equals("java.evidence.view")&&ContentDigest.sha256Utf8(e.canonicalJson()).equals(result.view())))throw new IllegalArgumentException("Foreign compiler attribution");
        if(!result.current())throw new IOException("JAVA_COMPILER_EVIDENCE_REVOKED");var combined=new ArrayList<>(evidence);combined.addAll(result.evidence());var bindings=new ArrayList<>(compiler);bindings.add(result);return new JavaEvidenceGraph(base,derived,combined,postAnalysis,bindings,gapClosure,gapCurrent);
    }
    public JavaEvidenceGraph withGapClosure(JavaEvidenceGapClosure closure)throws IOException{
        if(gapClosure.isPresent())throw new IllegalArgumentException("Duplicate Java gap closure");
        var view=evidence.stream().filter(e->e.family().equals("java.evidence.view")).findFirst().orElseThrow(()->new IllegalArgumentException("Missing Java input view"));
        var fields=com.evolution.analysis.graph.GraphJson.object(com.evolution.analysis.graph.GraphJson.read(view.canonicalJson(),com.evolution.analysis.graph.GraphJson.Limits.defaults()));
        if(!closure.scope().equals(fields.get("scope"))||!closure.policy().value().equals(fields.get("policy")))throw new IllegalArgumentException("Foreign Java gap closure");
        var current=closure.current(closure.policy());var combined=new ArrayList<>(evidence);combined.addAll(closure.evidence(current));
        return new JavaEvidenceGraph(base,derived,combined,postAnalysis,compiler,Optional.of(closure),Optional.of(current));
    }
    public List<com.evolution.analysis.evidence.CapabilityGapRecord> gapHistory(){return gapClosure.map(JavaEvidenceGapClosure::history).orElse(List.of());}
    public Optional<JavaEvidenceGapClosure> gapClosure(){return gapClosure;}
    public Set<String> historicalGapIds(){return gapCurrent.map(c->c.historicalGaps().stream().map(g->g.gapIdentity().value()).collect(java.util.stream.Collectors.toUnmodifiableSet())).orElse(Set.of());}
    public List<com.evolution.analysis.evidence.CapabilityGapRecord> activeGaps(Collection<com.evolution.analysis.evidence.CapabilityGapRecord> gaps){
        var result=new TreeSet<com.evolution.analysis.evidence.CapabilityGapRecord>(gaps);result.addAll(gapHistory());gapCurrent.ifPresent(c->result.addAll(c.unmetGaps()));var historical=historicalGapIds();result.removeIf(g->historical.contains(g.gapIdentity().value()));return List.copyOf(result);
    }
    public boolean closedGapDenominator(){return gapCurrent.map(JavaEvidenceGapClosure.Current::closedDenominator).orElse(true);}
    public void validateFor(Optional<UniversalIngestionPipeline.Result> ingestion){
        postAnalysis.ifPresent(post->{if(ingestion.isEmpty()||ingestion.orElseThrow().units().stream().flatMap(u->u.frontend().stream()).noneMatch(f->CanonicalJson.sha256(f).equals(post.frontend())))throw new IllegalArgumentException("Foreign post-analysis frontend result");});
        for(var result:compiler)if(ingestion.isEmpty()||ingestion.orElseThrow().units().stream().flatMap(u->u.frontend().stream()).noneMatch(f->f.equals(result.frontend())))throw new IllegalArgumentException("Foreign compiler-attributed frontend result");
    }
    public void requireCurrent()throws IOException{if(postAnalysis.isPresent()&&!postAnalysis.orElseThrow().current())throw new IOException("JAVA_SEMANTIC_EVIDENCE_REVOKED");for(var result:compiler)if(!result.current())throw new IOException("JAVA_COMPILER_EVIDENCE_REVOKED");if(gapClosure.isPresent()){
        var now=gapClosure.orElseThrow().current(gapClosure.orElseThrow().policy());var before=gapCurrent.orElseThrow();
        if(now.closedDenominator()!=before.closedDenominator()||!now.requirements().identity().equals(before.requirements().identity())||!now.dispositions().equals(before.dispositions()))throw new IOException("JAVA_GAP_CLOSURE_REVOKED");
    }}
}
