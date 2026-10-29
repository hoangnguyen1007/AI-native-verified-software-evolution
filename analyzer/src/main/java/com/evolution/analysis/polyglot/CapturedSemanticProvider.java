package com.evolution.analysis.polyglot;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Reads a captured native export. This adapter neither launches tools nor issues semantic authority. */
public final class CapturedSemanticProvider implements LanguageSemanticProvider {
    public record Capture(ContentDigest scope,String language,String engine,ContentDigest tool,ContentDigest extractor,ContentHandle export,
            SemanticTables.Table documents,SemanticTables.Table census,SemanticTables.Table observations){
        public ContentDigest toolset(){return com.evolution.analysis.contract.serialization.CanonicalJson.sha256(List.of("polyglot-toolset:m8.4-v1",tool,extractor));}
    }
    private final Capture capture;private final SemanticTables tables;private final RepositoryContentStore content;
    public CapturedSemanticProvider(Capture capture,SemanticTables tables,RepositoryContentStore content){this.capture=Objects.requireNonNull(capture);this.tables=Objects.requireNonNull(tables);this.content=Objects.requireNonNull(content);}
    @Override public SemanticTables.Table analyze(LanguageSemanticRequest request,BooleanSupplier cancelled)throws IOException{
        if(!request.scope().equals(capture.scope())||!request.language().equals(capture.language())||!request.dialect().equals(capture.engine())||!request.tool().equals(capture.toolset())
                ||!request.provider().equals(new com.evolution.analysis.contract.common.VersionedIdentifier("frontend.typescript-capture","m8.4-v1"))
                ||!request.documents().equals(capture.documents())||!request.census().equals(capture.census()))throw new IOException("CAPTURED_SEMANTIC_SCOPE_MISMATCH");
        if(!content.verify(capture.export()))throw new IOException("CAPTURED_SEMANTIC_EXPORT_UNAVAILABLE");
        // Recheck all captured table bytes at use. Data alone cannot close a trust predicate.
        for(var table:List.of(capture.documents(),capture.census(),capture.observations()))try(var reader=tables.open(table,cancelled)){while(reader.next()!=null){}}
        return capture.observations();
    }
    public static Capture read(ContentHandle export,SemanticTables tables,RepositoryContentStore content,int rowBytes,BooleanSupplier cancelled)throws IOException{
        var parsed=SemanticCaptureStream.read(export,tables,content,rowBytes,cancelled,header->
            header.keySet().equals(Set.of("schema","scope","language","engine","tool","extractor","trust"))
            &&"typescript-capture-result:m8.4-v2".equals(header.get("schema"))&&"PRODUCER_ASSERTION".equals(header.get("trust"))
            &&Set.of("typescript","javascript").contains(header.get("language")),"typescript-capture-end:m8.4-v1");
        try{
            var header=parsed.header();return new Capture(parsed.scope(),GraphJson.string(header.get("language")),GraphJson.string(header.get("engine")),new ContentDigest(GraphJson.string(header.get("tool"))),new ContentDigest(GraphJson.string(header.get("extractor"))),export,parsed.tables().get(0),parsed.tables().get(1),parsed.tables().get(2));
        }catch(IllegalArgumentException invalid){throw new IOException("CAPTURE_FORMAT_INVALID",invalid);}
    }
}
