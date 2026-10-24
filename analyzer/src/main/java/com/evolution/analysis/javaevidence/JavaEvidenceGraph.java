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
    JavaEvidenceGraph(SnapshotIdentity base,SnapshotIdentity derived,List<GraphEvidence> evidence){this(base,derived,evidence,Optional.empty(),List.of());}
    private JavaEvidenceGraph(SnapshotIdentity base,SnapshotIdentity derived,List<GraphEvidence> evidence,Optional<JavaSemanticEvidence.Result> postAnalysis,List<JavaCompilerAttribution.Result> compiler){this.base=Objects.requireNonNull(base);this.derived=Objects.requireNonNull(derived);this.evidence=List.copyOf(evidence);this.postAnalysis=postAnalysis;this.compiler=List.copyOf(compiler);}
    public SnapshotIdentity base(){return base;}public SnapshotIdentity derived(){return derived;}public List<GraphEvidence> evidence(){return evidence;}
    public JavaEvidenceGraph withPostAnalysis(JavaSemanticEvidence.Result result)throws IOException{
        if(postAnalysis.isPresent()||!derived.equals(result.snapshot())||evidence.stream().noneMatch(e->e.family().equals("java.evidence.view")&&ContentDigest.sha256Utf8(e.canonicalJson()).equals(result.view())))throw new IllegalArgumentException("Foreign post-analysis Java evidence");
        if(!result.current())throw new IOException("JAVA_SEMANTIC_EVIDENCE_REVOKED");var combined=new ArrayList<>(evidence);combined.addAll(result.evidence());return new JavaEvidenceGraph(base,derived,combined,Optional.of(result),compiler);
    }
    public JavaEvidenceGraph withCompilerAttribution(JavaCompilerAttribution.Result result)throws IOException{
        if(postAnalysis.isPresent()||!derived.equals(result.snapshot())||evidence.stream().noneMatch(e->e.family().equals("java.evidence.view")&&ContentDigest.sha256Utf8(e.canonicalJson()).equals(result.view())))throw new IllegalArgumentException("Foreign compiler attribution");
        if(!result.current())throw new IOException("JAVA_COMPILER_EVIDENCE_REVOKED");var combined=new ArrayList<>(evidence);combined.addAll(result.evidence());var bindings=new ArrayList<>(compiler);bindings.add(result);return new JavaEvidenceGraph(base,derived,combined,postAnalysis,bindings);
    }
    public void validateFor(Optional<UniversalIngestionPipeline.Result> ingestion){
        postAnalysis.ifPresent(post->{if(ingestion.isEmpty()||ingestion.orElseThrow().units().stream().flatMap(u->u.frontend().stream()).noneMatch(f->CanonicalJson.sha256(f).equals(post.frontend())))throw new IllegalArgumentException("Foreign post-analysis frontend result");});
        for(var result:compiler)if(ingestion.isEmpty()||ingestion.orElseThrow().units().stream().flatMap(u->u.frontend().stream()).noneMatch(f->f.equals(result.frontend())))throw new IllegalArgumentException("Foreign compiler-attributed frontend result");
    }
    public void requireCurrent()throws IOException{if(postAnalysis.isPresent()&&!postAnalysis.orElseThrow().current())throw new IOException("JAVA_SEMANTIC_EVIDENCE_REVOKED");for(var result:compiler)if(!result.current())throw new IOException("JAVA_COMPILER_EVIDENCE_REVOKED");}
}
