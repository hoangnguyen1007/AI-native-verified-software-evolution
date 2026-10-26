package com.evolution.backend.analysis;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.ingestion.UniversalArchitecturePipeline;
import java.io.IOException;
import java.util.*;

/** Additive pins for the named implementation classes, not a transitive dependency certificate. */
final class CapturedAnalysisComponents {
    static GraphEvidence prepared(UniversalArchitecturePipeline.Policy policy)throws IOException{
        return capture(List.of(policy.frontend().getClass(),PreparedJavaAnalysisRunner.class,PreparedAnalysisPublication.class,CapturedJavaReader.class,CapturedJavaExecution.class,
                com.evolution.analysis.ingestion.UniversalArchitecturePipeline.class,com.evolution.analysis.ingestion.UniversalIngestionPipeline.class,
                com.evolution.analysis.ingestion.UniversalSourceIngestion.class,com.evolution.analysis.graph.CanonicalGraphBuilder.class,
                com.evolution.analysis.javaevidence.JavaReadinessClosure.class,com.evolution.analysis.javaevidence.JavaSemanticProjection.class,com.evolution.analysis.javaevidence.JavaSpringSemanticProjection.class,
                com.evolution.analysis.javaevidence.JavaEvidenceAuthority.class,com.evolution.analysis.javaevidence.JavaEvidenceLedger.class,
                com.evolution.analysis.spring.condition.SpringBuildContext.class,com.evolution.analysis.spring.SpringFrameworkEvidence.class,
                com.evolution.analysis.spring.condition.ComponentScanIngestion.class,com.evolution.analysis.spring.condition.ConditionEvidenceLowering.class,
                com.evolution.analysis.spring.universal.SourceToSpringPlan.class,com.evolution.analysis.spring.universal.SpringSourceEvidence.class,
                com.evolution.analysis.spring.SpringMechanismScanner.class,com.evolution.analysis.spring.registration.BeanRegistrationTransitions.class,
                com.evolution.analysis.spring.registration.BeanRegistrationProcessing.class,com.evolution.analysis.spring.truth.TruthRegionEvaluation.class));
    }
    static GraphEvidence repository()throws IOException{
        return capture(List.of(PreparedJavaRepositoryRunner.class,com.evolution.analysis.javaevidence.JavaReadinessClosure.class,
                com.evolution.analysis.graph.KnowledgeGraph.class,com.evolution.analysis.graph.GraphSnapshotCodec.class));
    }
    static GraphEvidence capture(Collection<Class<?>> roots)throws IOException{
        var classes=new TreeMap<String,ContentDigest>();var pending=new ArrayDeque<>(roots);pending.add(CapturedAnalysisComponents.class);
        while(!pending.isEmpty()){
            var type=pending.removeFirst();if(classes.containsKey(type.getName()))continue;
            try(var input=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")){
                if(input==null)throw new IOException("JAVA_CAPTURED_COMPONENT_BYTES_UNAVAILABLE");
                classes.put(type.getName(),ContentDigest.sha256(input.readAllBytes()));
            }
            Collections.addAll(pending,type.getDeclaredClasses());
        }
        return GraphEvidence.capture("java.evidence.execution-components",Map.of("schema","java-execution-components:m8.4-v1",
                "scope","PRIMARY_IMPLEMENTATION_CLASSES","runtime",Runtime.version().toString(),"classes",classes));
    }
}
