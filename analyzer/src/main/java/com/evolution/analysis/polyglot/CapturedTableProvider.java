package com.evolution.analysis.polyglot;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.contract.common.ContentDigest;
import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Exact captured context replay. Available bytes are not semantic authority. */
public final class CapturedTableProvider implements LanguageSemanticProvider {
    private final LanguageSemanticRequest expected;private final SemanticTables.Table observations;private final ContentHandle producer;
    private final SemanticTables tables;private final RepositoryContentStore content;
    public CapturedTableProvider(LanguageSemanticRequest expected,SemanticTables.Table observations,ContentHandle producer,SemanticTables tables,RepositoryContentStore content){
        this.expected=Objects.requireNonNull(expected);this.observations=Objects.requireNonNull(observations);this.producer=Objects.requireNonNull(producer);this.tables=Objects.requireNonNull(tables);this.content=Objects.requireNonNull(content);
        if(observations.kind()!=SemanticTables.Kind.OBSERVATION)throw new IllegalArgumentException("CAPTURED_OBSERVATIONS_REQUIRED");
    }
    @Override public SemanticTables.Table analyze(LanguageSemanticRequest request,BooleanSupplier cancelled)throws IOException{
        if(!expected.identity().equals(request.identity()))throw new IOException("CAPTURED_SEMANTIC_SCOPE_MISMATCH");
        if(!content.verify(producer)||!content.verify(request.resolution()))throw new IOException("CAPTURED_SEMANTIC_INPUT_UNAVAILABLE");
        for(var table:List.of(request.documents(),request.census(),observations))try(var reader=tables.open(table,cancelled)){
            SemanticTables.Row row;while((row=reader.next())!=null){
                if(!row.scope().equals(request.scope()))throw new IOException("CAPTURED_SEMANTIC_ROW_CONTEXT_MISMATCH");
                if(table.kind()==SemanticTables.Kind.DOCUMENT){
                    try{var handle=GraphJson.object(row.fields().get("content"));if(!handle.keySet().equals(Set.of("digest","size")))throw new IllegalArgumentException("document handle fields");
                        var source=new ContentHandle(new ContentDigest(GraphJson.string(handle.get("digest"))),((java.math.BigDecimal)handle.get("size")).longValueExact());
                        if(!content.verify(source))throw new IOException("CAPTURED_SEMANTIC_SOURCE_UNAVAILABLE");
                    }catch(IllegalArgumentException|ArithmeticException|ClassCastException invalid){throw new IOException("CAPTURED_SEMANTIC_DOCUMENT_INVALID",invalid);}
                }
            }
        }
        return observations;
    }
}
