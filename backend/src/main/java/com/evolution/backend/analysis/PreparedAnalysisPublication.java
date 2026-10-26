package com.evolution.backend.analysis;

import com.evolution.analysis.acquisition.streaming.RepositoryContentStore;
import com.evolution.analysis.acquisition.streaming.ContentHandle;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.filesystem.JavaEvidencePublicationStore;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.graph.GraphSnapshotCodec;
import com.evolution.analysis.ingestion.UniversalArchitecturePipeline;
import com.evolution.analysis.javaevidence.*;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Durable captured results. Loading bytes is never a fresh semantic trust decision. */
public final class PreparedAnalysisPublication {
    @FunctionalInterface public interface CurrentCheck {void verify()throws IOException;}
    private final JavaEvidencePublicationStore store;
    private final RepositoryContentStore content;
    private final ContentDigest registration;
    public PreparedAnalysisPublication(JavaEvidencePublicationStore store,RepositoryContentStore content,ContentDigest registration){
        this.store=Objects.requireNonNull(store);this.content=Objects.requireNonNull(content);this.registration=Objects.requireNonNull(registration);
    }
    public Optional<JavaEvidencePublicationStore.Publication> latest()throws IOException{return store.load(registration);}
    public void publish(JavaEvidenceAssembler.Prepared prepared,UniversalArchitecturePipeline.Policy policy,JavaReadinessClosure.View readiness,
            KnowledgeGraph graph,BooleanSupplier cancelled,CurrentCheck current)throws IOException{
        if(!prepared.readiness().identity().equals(readiness.plan().identity())||!prepared.policy().equals(readiness.policy())
                ||!prepared.inputs().snapshot().identity().equals(graph.snapshot()))throw new IOException("JAVA_PUBLICATION_INPUT_SCOPE");
        current.verify();var previous=store.head(registration);
        if(graph.evidence().stream().filter(e->e.family().equals("java.evidence.execution-components")).count()!=1)throw new IOException("JAVA_PUBLICATION_COMPONENTS_MISSING");
        var captured=graph.evidence().stream().filter(e->Set.of("analysis.manifest","source.request","java.evidence.view","java.evidence.overlay","java.evidence.resolution",
                "java.evidence.proof","java.evidence.semantic-capture","java.evidence.compiler-capture","java.evidence.execution-components").contains(e.family())).map(e->e.identity()).sorted().toList();
        var work=new LinkedHashMap<String,Object>();work.put("frontend",policy.frontendProvider());work.put("pipeline",UniversalArchitecturePipeline.PROVIDER);
        work.put("graphSchema",KnowledgeGraph.SCHEMA);work.put("buildPolicy",policy.buildPolicy());work.put("decodePolicy",policy.decodePolicy());
        work.put("maxTypes",policy.maxTypes());work.put("maxParameters",policy.maxParameters());work.put("truthMode",policy.truthEvaluationMode());work.put("truthLimits",policy.truthLimits());
        work.put("configurationSpace",policy.configurationSpace().map(s->s.identity()));work.put("baseline",policy.baselineConfiguration().map(s->s.identity()));
        work.put("springScopes",policy.springScopes().entrySet().stream().map(e->Map.of("sourceSet",e.getKey(),"scope",e.getValue())).sorted(Comparator.comparing(CanonicalJson::write)).toList());
        work.put("semanticProjection",Map.of("version",JavaSemanticProjection.VERSION,"component",JavaSemanticProjection.componentIdentity()));
        var footprint=Map.of("schema","java-analysis-publication-footprint:m8.4-v1","view",prepared.view().identity(),"readiness",readiness.identity(),
                "trustPolicy",prepared.policy(),"capturedInputs",captured,"work",work,"semanticReuse","REVALIDATION_REQUIRED");
        var artifacts=new TreeMap<String,ContentHandle>();
        artifacts.put("input-footprint",put(CanonicalJson.write(footprint),cancelled));
        artifacts.put("semantic-view",put(prepared.view().json(),cancelled));
        artifacts.put("readiness",put(CanonicalJson.write(readiness.wire()),cancelled));
        artifacts.put("graph",put(new GraphSnapshotCodec().encode(graph),cancelled));
        if(previous.isPresent()){
            var prior=previous.orElseThrow().artifacts();
            if(!prior.keySet().equals(artifacts.keySet()))throw new IOException("JAVA_PUBLICATION_CACHE_SCHEMA_MISMATCH");
            if(prior.get("input-footprint").equals(artifacts.get("input-footprint"))&&!prior.get("graph").equals(artifacts.get("graph")))
                throw new IOException("JAVA_PUBLICATION_REPLAY_CONFLICT");
        }
        current.verify();
        store.commit(registration,previous.map(p->p.document().digest()),artifacts,cancelled);
    }
    private ContentHandle put(String json,BooleanSupplier cancelled)throws IOException{
        try{return content.put(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),cancelled);}
        catch(java.util.concurrent.CancellationException stopped){throw new IOException("JAVA_PUBLICATION_CANCELLED",stopped);}
    }
}
