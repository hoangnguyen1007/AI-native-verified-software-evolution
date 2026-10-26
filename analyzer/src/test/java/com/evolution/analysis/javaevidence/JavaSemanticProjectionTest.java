package com.evolution.analysis.javaevidence;

import com.evolution.analysis.acquisition.streaming.ContentHandle;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JavaSemanticProjectionTest {
    private static JavaEvidenceRecord slot(String kind,String descriptor,String selected,long ordinal){
        var d=ContentDigest.sha256Utf8(descriptor);var fields=new LinkedHashMap<String,Object>();
        fields.put("schema","java-resolution-slot:m8.4-v1");fields.put("scope",JavaEvidenceLedgerTest.scope());fields.put("space","CLASS_PATH");fields.put("ordinal",ordinal);
        fields.put("logicalName","captured:private/attempt/path");fields.put("kind",kind);fields.put("original",new ContentHandle(d,1));
        fields.put("tree",kind.equals("CLASS_DIRECTORY")?new JavaEvidenceTables.Table("java-output-row-table:m8.4-v1",new ContentHandle(ContentDigest.sha256Utf8(""),0),0).wire():null);
        fields.put("selectedView",new ContentHandle(ContentDigest.sha256Utf8(selected),2));fields.put("origin","PROJECT");fields.put("module",JavaEvidenceLedgerTest.scope().get("module"));
        fields.put("proof",JavaEvidenceCodecTest.D);fields.put("disposition","ACCEPTED");fields.put("gaps",List.of());return new JavaEvidenceCodec().create(fields);
    }
    @Test void onlyTypedDirectoryDescriptorsSeparateAuditFromTheirExactCapturedBytes()throws Exception{
        var a=slot("CLASS_DIRECTORY","attempt-a descriptor","same complete solver bytes",0);
        var b=slot("CLASS_DIRECTORY","attempt-b descriptor","same complete solver bytes",0);
        assertNotEquals(a.identity(),b.identity());
        assertEquals(CanonicalJson.sha256(JavaSemanticProjection.semanticSlot(a)),CanonicalJson.sha256(JavaSemanticProjection.semanticSlot(b)));
        assertNotEquals(CanonicalJson.sha256(JavaSemanticProjection.semanticSlot(a)),CanonicalJson.sha256(JavaSemanticProjection.semanticSlot(slot("CLASS_DIRECTORY","attempt-b descriptor","changed real bytes",0))));
        assertNotEquals(CanonicalJson.sha256(JavaSemanticProjection.semanticSlot(a)),CanonicalJson.sha256(JavaSemanticProjection.semanticSlot(slot("CLASS_DIRECTORY","attempt-a descriptor","same complete solver bytes",1))));
        assertNotEquals(CanonicalJson.sha256(JavaSemanticProjection.semanticSlot(slot("JAR","jar-a bytes","same selected view",0))),
                CanonicalJson.sha256(JavaSemanticProjection.semanticSlot(slot("JAR","jar-b bytes","same selected view",0))),"A real archive change cannot be erased as descriptor metadata");
    }
    @Test void optionsAreNotFilteredByWordsLikeAttemptTimeOrUuid(){
        var input=Map.of("java.evidence-view","captured audit ref","application.attempt-policy","strict","application.timestamp-mode","source-defined","application.uuid","real semantic config");
        var projected=JavaSemanticProjection.semanticOptions(input);
        assertFalse(projected.containsKey("java.evidence-view"));
        assertEquals("strict",projected.get("application.attempt-policy"));assertEquals("source-defined",projected.get("application.timestamp-mode"));assertEquals("real semantic config",projected.get("application.uuid"));
        assertThrows(UnsupportedOperationException.class,()->projected.put("application.uuid","changed"));
    }
}
