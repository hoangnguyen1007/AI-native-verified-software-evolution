package com.evolution.analysis.metrics;

import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.SourceInput;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.query.ArchitectureQuery;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.metrics.StructuralMetricsTest.*;

class InventoryQualityMetricsTest {
    @Test void staleButSelfConsistentSourceBytesCannotReplaceSelectedDocumentDigest(){
        var f=new Fixture();var selected=source(f,"same.java","new\ncontent",SourceClassification.MAIN,false);
        f.evidence.removeIf(e->e.family().equals("source.bytes"));var oldBytes="old".getBytes(StandardCharsets.UTF_8);var oldDoc=SourceDocument.create(f.repo,f.module,"same.java",ContentDigest.sha256(oldBytes),SourceClassification.MAIN);
        var stale=GraphEvidence.capture("source.bytes",new SourceInput(oldDoc,oldBytes));f.evidence.add(stale);var de=f.evidence.stream().filter(e->e.family().equals("source.document")).findFirst().orElseThrow();
        f.nodes.removeIf(n->n.kind().equals("SOURCE_DOCUMENT"));f.nodes.add(new KnowledgeGraph.Node(selected.document().identity().value(),f.context,"SOURCE_DOCUMENT",List.of(de.identity())));
        var r=new ArchitectureMetricsService(f.graph()).compute(f.request(),"metric.physical-loc",f.context);assertEquals(AssessmentStatus.WITHHELD,r.status());assertFalse(r.gaps().isEmpty());
    }
    static SourceInput source(Fixture f,String name,String text,SourceClassification role,boolean bom){
        byte[] bytes=(bom?"\ufeff"+text:text).getBytes(StandardCharsets.UTF_8);
        var doc=SourceDocument.create(f.repo,f.module,name,ContentDigest.sha256(bytes),role);
        var policy=new SourceInput.Decoding("source-decoding-v1","UTF-8",SourceInput.EncodingOrigin.ANALYSIS_POLICY,List.of(),bom?SourceInput.Bom.UTF8:SourceInput.Bom.NONE,SourceInput.LineEndings.from(text));
        var source=new SourceInput(doc,bytes,policy);var de=GraphEvidence.capture("source.document",doc);var be=GraphEvidence.capture("source.bytes",source);
        f.evidence.add(de);f.evidence.add(be);f.nodes.add(new KnowledgeGraph.Node(doc.identity().value(),f.context,"SOURCE_DOCUMENT",List.of(de.identity(),be.identity())));return source;
    }
    @Test void physicalLocUsesDecodedTerminatorsEmptyBomAndFinalLine(){
        var f=new Fixture();source(f,"empty.java","",SourceClassification.MAIN,false);source(f,"lf.java","a\nb\n",SourceClassification.MAIN,false);
        source(f,"crlf.java","a\r\nb",SourceClassification.TEST,false);source(f,"cr.java","a\rb\r",SourceClassification.MAIN,false);
        source(f,"bom.java","a",SourceClassification.GENERATED_MAIN,true);source(f,"mixed.java","a\r\nb\rc\nd",SourceClassification.MAIN,false);
        var r=new ArchitectureMetricsService(f.graph()).compute(f.request(),"metric.physical-loc",f.context);
        assertEquals(AssessmentStatus.COMPLETE,r.status());assertEquals(new BigDecimal("11"),amount(r));assertEquals(new BigDecimal("6"),r.details().get("decodedFiles"));
        assertTrue(r.evidence().stream().allMatch(e->f.graph().evidence(e).isPresent()));
    }
    @Test void missingAndCorruptSourceBytesAreQualifiedInsteadOfZero(){
        var f=new Fixture().type("p","NoBytes",false);source(f,"valid.java","line",SourceClassification.MAIN,false);
        var r=new ArchitectureMetricsService(f.graph()).compute(f.request(),"metric.physical-loc",f.context);
        assertEquals(AssessmentStatus.PARTIAL,r.status());assertEquals(BigDecimal.ONE,amount(r));assertFalse(r.gaps().isEmpty());
        var g=new Fixture();var original=source(g,"bad.java","x",SourceClassification.MAIN,false);
        g.evidence.removeIf(e->e.family().equals("source.bytes"));
        var bad=GraphEvidence.capture("source.bytes",Map.of("document",original.document(),"decoding",original.decoding(),"bytesBase64",Base64.getEncoder().encodeToString(new byte[]{(byte)0xc3,0x28})));
        g.evidence.add(bad);g.nodes.removeIf(n->n.kind().equals("SOURCE_DOCUMENT"));var de=g.evidence.stream().filter(e->e.family().equals("source.document")).findFirst().orElseThrow();
        g.nodes.add(new KnowledgeGraph.Node(original.document().identity().value(),g.context,"SOURCE_DOCUMENT",List.of(de.identity(),bad.identity())));
        var failed=new ArchitectureMetricsService(g.graph()).compute(g.request(),"metric.physical-loc",g.context);assertEquals(AssessmentStatus.WITHHELD,failed.status());assertTrue(failed.value().isEmpty());assertFalse(failed.gaps().isEmpty());
    }
    @Test void sourceByteBudgetIsExplicitAndBounded(){
        var f=new Fixture();source(f,"large.java","abcdef",SourceClassification.MAIN,false);
        var service=new ArchitectureMetricsService(f.graph(),new ArchitectureMetric.Limits(1000,10000,2,50),()->ArchitectureQuery.StopReason.NONE,Optional.empty(),List.of());
        var r=service.compute(f.request(),"metric.physical-loc",f.context);assertEquals(AssessmentStatus.WITHHELD,r.status());assertTrue(service.evaluate(f.request(),"metric.physical-loc",f.context).bounds().workBudgetExceeded());
    }
    @Test void inventoryKeepsSourceRolesKindsAndMetadataSeparate(){
        var f=golden().type("p","Test",com.evolution.analysis.frontend.TypeDeclarationRecord.Kind.CLASS,false,SourceClassification.TEST,"PROJECT")
                .type("p","Generated",com.evolution.analysis.frontend.TypeDeclarationRecord.Kind.CLASS,false,SourceClassification.GENERATED_MAIN,"PROJECT");
        f.nodes.add(new KnowledgeGraph.Node(id("annotation-use"),f.context,"ANNOTATION_USE",List.of(f.base.identity())));
        var r=new ArchitectureMetricsService(f.graph()).compute(f.request(),"metric.inventory",f.context);
        assertEquals(new BigDecimal("6"),amount(r));var roles=GraphJson.object(r.details().get("declarationsBySourceRole"));assertEquals(new BigDecimal("4"),roles.get("MAIN"));assertEquals(BigDecimal.ONE,roles.get("TEST"));assertEquals(BigDecimal.ONE,roles.get("GENERATED_MAIN"));
        assertEquals(BigDecimal.ONE,GraphJson.object(r.details().get("evidencedMetadataCounts")).get("ANNOTATION_USE"));
        assertEquals(new BigDecimal("0.5"),amount(new ArchitectureMetricsService(f.graph()).compute(f.request(),"metric.abstractness",id("p"))));
    }
    @Test void qualityNeverInventsGroundTruthOrCoverage(){
        var f=golden();var r=new ArchitectureMetricsService(f.graph()).compute(f.request(),"metric.quality",f.context);
        assertEquals(AssessmentStatus.PARTIAL,r.status());assertFalse(r.gaps().isEmpty());assertEquals("NOT_MEASURED_WITHOUT_GROUND_TRUTH",r.details().get("accuracy"));assertTrue(GraphJson.object(r.details().get("categories")).isEmpty());
    }
    @Test void operationalValuesRequireMatchingContentAddressedObservation(){
        var f=golden();var measurement=GraphEvidence.capture("metrics.phase-measurement",Map.of("context",f.context,"phase","phase.frontend","durationNanos",123,"failed",true));f.evidence.add(measurement);
        var service=new ArchitectureMetricsService(f.graph(),ArchitectureMetric.Limits.defaults(),()->ArchitectureQuery.StopReason.NONE,Optional.empty(),List.of(new ArchitectureMetricsService.PhaseMeasurement("phase.frontend",123,true,measurement.identity())));
        assertEquals(new BigDecimal("123"),amount(service.compute(f.request(),"metric.operational",f.context)));
        var forged=new ArchitectureMetricsService(f.graph(),ArchitectureMetric.Limits.defaults(),()->ArchitectureQuery.StopReason.NONE,Optional.empty(),List.of(new ArchitectureMetricsService.PhaseMeasurement("phase.frontend",124,true,measurement.identity())));
        assertEquals(AssessmentStatus.WITHHELD,forged.compute(f.request(),"metric.operational",f.context).status());
    }
    @Test void cancellationPreservesTypedMetricOutcomeAndBridgeOperation(){
        var f=golden();var service=new ArchitectureMetricsService(f.graph(),ArchitectureMetric.Limits.defaults(),()->ArchitectureQuery.StopReason.CANCELLED,Optional.empty(),List.of());
        var r=service.evaluate(f.request(),"metric.density",f.context);assertEquals(ArchitectureQuery.Operation.CANCELLED,r.operation());assertEquals(AssessmentStatus.WITHHELD,r.status());assertTrue(r.bounds().truncated());assertFalse(r.gaps().isEmpty());
    }
}
