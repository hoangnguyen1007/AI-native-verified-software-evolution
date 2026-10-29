package com.evolution.analysis.polyglot;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Native-neutral v1 export, separate from the retained TS-specific v2 wire contract. */
public record NativeSemanticCapture(ContentDigest scope,String language,String dialect,VersionedIdentifier provider,
        ContentDigest tool,ContentHandle export,SemanticTables.Table documents,SemanticTables.Table census,SemanticTables.Table observations) {
    public static NativeSemanticCapture read(ContentHandle export,SemanticTables tables,RepositoryContentStore content,int rowBytes,BooleanSupplier cancelled)throws IOException{
        var parsed=SemanticCaptureStream.read(export,tables,content,rowBytes,cancelled,header->
            header.keySet().equals(Set.of("schema","scope","language","dialect","provider","tool","trust"))
            &&"language-capture-result:m8.4-v1".equals(header.get("schema"))&&"PRODUCER_ASSERTION".equals(header.get("trust")),"language-capture-end:m8.4-v1");
        try{
            var h=parsed.header();var p=GraphJson.object(h.get("provider"));if(!p.keySet().equals(Set.of("id","version")))throw new IllegalArgumentException("provider fields");
            return new NativeSemanticCapture(parsed.scope(),ContractChecks.token(GraphJson.string(h.get("language")),"language"),ContractChecks.token(GraphJson.string(h.get("dialect")),"dialect"),
                new VersionedIdentifier(GraphJson.string(p.get("id")),GraphJson.string(p.get("version"))),new ContentDigest(GraphJson.string(h.get("tool"))),export,parsed.tables().get(0),parsed.tables().get(1),parsed.tables().get(2));
        }catch(IllegalArgumentException invalid){throw new IOException("NATIVE_CAPTURE_HEADER_INVALID",invalid);}
    }
    public LanguageSemanticProvider asProvider(LanguageSemanticRequest request,SemanticTables tables,RepositoryContentStore content)throws IOException{
        if(!request.scope().equals(scope)||!request.language().equals(language)||!request.dialect().equals(dialect)||!request.provider().equals(provider)||!request.tool().equals(tool)
                ||!request.documents().equals(documents)||!request.census().equals(census))throw new IOException("NATIVE_CAPTURE_REQUEST_MISMATCH");
        return new CapturedTableProvider(request,observations,export,tables,content);
    }
}
