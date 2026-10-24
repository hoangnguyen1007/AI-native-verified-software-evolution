package com.evolution.analysis.javaevidence;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.acquisition.streaming.ContentHandle;
import java.math.BigDecimal;
import java.util.Map;

/** Deeply immutable schema-checked wire record. It is never a verified claim. */
public record JavaEvidenceRecord(Map<String,Object> fields) {
    public JavaEvidenceRecord {fields=JavaEvidenceCodec.validateAndFreeze(fields);}
    public ContentDigest identity(){return CanonicalJson.sha256(fields);}
    public String kind(){return text("schema").replace(":m8.4-v1","");}
    public String text(String field){return GraphJson.string(fields.get(field));}
    public long number(String field){return ((BigDecimal)fields.get(field)).longValueExact();}
    public ContentDigest digest(String field){return new ContentDigest(text(field));}
    public Map<String,Object> object(String field){return GraphJson.object(fields.get(field));}
    public String json(){return CanonicalJson.write(fields);}
    public static ContentHandle handle(Object value){var map=GraphJson.object(value);return new ContentHandle(new ContentDigest(GraphJson.string(map.get("digest"))),((BigDecimal)map.get("size")).longValueExact());}
}
