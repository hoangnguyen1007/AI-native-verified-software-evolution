package com.evolution.analysis.polyglot;

import com.evolution.analysis.acquisition.streaming.ContentHandle;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.EvidenceContext;
import java.util.*;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.contract.identity.*;

/** One captured compilation context. Ordered resolution bytes are not a union of workspace contexts. */
public record LanguageSemanticRequest(EvidenceContext context,ContentDigest inventory,String root,String module,String sourceSet,String variant,
        String language,String dialect,SemanticTables.Table documents,SemanticTables.Table census,ContentHandle resolution,
        VersionedIdentifier provider,ContentDigest tool,ContentDigest policy,ContentDigest workPolicy) {
    public LanguageSemanticRequest {
        Objects.requireNonNull(context);Objects.requireNonNull(inventory);Objects.requireNonNull(documents);Objects.requireNonNull(census);Objects.requireNonNull(resolution);
        Objects.requireNonNull(provider);Objects.requireNonNull(tool);Objects.requireNonNull(policy);Objects.requireNonNull(workPolicy);
        for(String value:List.of(root,module,sourceSet,variant,language,dialect))if(value.isBlank())throw new IllegalArgumentException("SEMANTIC_SCOPE_REQUIRED");
        if(documents.kind()!=SemanticTables.Kind.DOCUMENT||census.kind()!=SemanticTables.Kind.CENSUS)throw new IllegalArgumentException("SEMANTIC_INPUT_TABLE_KIND");
    }
    public ContentDigest scope(){return CanonicalJson.sha256(List.of("language-context:m8.4-v1",inventory,root,module,sourceSet,variant,language,dialect));}
    public ContentDigest identity(){return CanonicalJson.sha256(List.of("language-request:m8.4-v1",this));}
    /** Scope is a snapshot-relative transport locator. Proof inputs must also
     * bind the repository/snapshot context, not only equal source bytes. */
    public ContentDigest inputClosure(){return CanonicalJson.sha256(List.of("language-input-closure:m8.4-v2",context,inventory,scope(),documents,census,resolution,provider,tool,workPolicy));}
    /** Fixed neutral schema. Captured JSON cannot select or instantiate arbitrary types. */
    public static LanguageSemanticRequest from(Object value){
        var row=GraphJson.object(value);
        if(!row.keySet().equals(Set.of("context","inventory","root","module","sourceSet","variant","language","dialect","documents","census","resolution","provider","tool","policy","workPolicy")))throw new IllegalArgumentException("SEMANTIC_REQUEST_FIELDS");
        var context=GraphJson.object(row.get("context"));var provider=GraphJson.object(row.get("provider"));
        if(!context.keySet().equals(Set.of("snapshotIdentity","analysisIdentity"))||!provider.keySet().equals(Set.of("id","version")))throw new IllegalArgumentException("SEMANTIC_REQUEST_SCOPE_FIELDS");
        return new LanguageSemanticRequest(new EvidenceContext(new SnapshotIdentity(GraphJson.string(context.get("snapshotIdentity"))),Optional.ofNullable(context.get("analysisIdentity")).map(id->new AnalysisIdentity(GraphJson.string(id)))),digest(row,"inventory"),
            GraphJson.string(row.get("root")),GraphJson.string(row.get("module")),GraphJson.string(row.get("sourceSet")),GraphJson.string(row.get("variant")),GraphJson.string(row.get("language")),GraphJson.string(row.get("dialect")),
            table(row.get("documents")),table(row.get("census")),handle(row.get("resolution")),new VersionedIdentifier(GraphJson.string(provider.get("id")),GraphJson.string(provider.get("version"))),digest(row,"tool"),digest(row,"policy"),digest(row,"workPolicy"));
    }
    static ContentHandle handle(Object value){var row=GraphJson.object(value);if(!row.keySet().equals(Set.of("digest","size")))throw new IllegalArgumentException("SEMANTIC_HANDLE_FIELDS");return new ContentHandle(digest(row,"digest"),((java.math.BigDecimal)row.get("size")).longValueExact());}
    static SemanticTables.Table table(Object value){var row=GraphJson.object(value);if(!row.keySet().equals(Set.of("schema","kind","content","rows")))throw new IllegalArgumentException("SEMANTIC_TABLE_FIELDS");return new SemanticTables.Table(GraphJson.string(row.get("schema")),SemanticTables.Kind.valueOf(GraphJson.string(row.get("kind"))),handle(row.get("content")),((java.math.BigDecimal)row.get("rows")).longValueExact());}
    private static ContentDigest digest(Map<String,Object> row,String key){return new ContentDigest(GraphJson.string(row.get(key)));}
}
