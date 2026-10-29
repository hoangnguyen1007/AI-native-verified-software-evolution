package com.evolution.analysis.polyglot;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Checks supplied locations against captured bytes. It never resolves a symbol
 * or infers a full span from a native point. One decoded source is cached. */
final class SemanticCoordinates {
    private record Document(ContentHandle content,String charset){}
    private record Region(String document,int start,int end){}
    private final RepositoryContentStore content;private final Map<String,Document> documents=new HashMap<>();private final Map<String,String> aliases=new HashMap<>();private final BooleanSupplier cancelled;
    private String loaded;private SourceCoordinates coordinates;
    SemanticCoordinates(RepositoryContentStore content,BooleanSupplier cancelled){this.content=content;this.cancelled=cancelled;}
    void document(SemanticTables.Row row)throws IOException{
        var fields=row.fields();String charset=fields.get("charset")!=null?GraphJson.string(fields.get("charset")):fields.get("decoding")!=null?GraphJson.string(GraphJson.object(fields.get("decoding")).get("charset")):"UTF-8";documents.put(row.key(),new Document(LanguageSemanticRequest.handle(fields.get("content")),charset));
        if(fields.get("legacyDocument")!=null){String alias=GraphJson.string(fields.get("legacyDocument"));new com.evolution.analysis.contract.identity.SourceDocumentIdentity(alias);if(aliases.putIfAbsent(alias,row.key())!=null)throw new IOException("SEMANTIC_SOURCE_COORDINATES_DUPLICATE_ALIAS");}
    }
    void validate(SemanticTables.Row census,SemanticTables.Row observation)throws IOException{
        try{
            String document=GraphJson.string(census.fields().get("document"));var expected=span(census.fields().get("span"),document,true);if(observation==null)return;
            var actual=span(observation.fields().get("span"),document,true);if(expected!=null&&actual!=null&&!expected.equals(actual))throw new IOException("SEMANTIC_SOURCE_COORDINATES_OCCURRENCE_MISMATCH");
            for(String field:List.of("symbols","calleeSymbols"))if(observation.fields().get(field)!=null)for(var raw:GraphJson.array(observation.fields().get(field))){var symbol=GraphJson.object(raw);span(symbol.get("span"),optionalString(symbol,"path"),false);generation(symbol.get("generationLineage"));}
        }catch(IllegalArgumentException|ArithmeticException|ClassCastException invalid){throw new IOException("SEMANTIC_SOURCE_COORDINATES_INVALID",invalid);}
    }
    private void generation(Object raw)throws IOException{
        if(raw==null)return;var value=GraphJson.object(raw);
        if(!value.keySet().equals(Set.of("schema","mechanism","source","sourceSpan"))||!"semantic-generation-lineage:m8.4-v1".equals(value.get("schema")))throw new IOException("SEMANTIC_GENERATION_LINEAGE_SCHEMA");
        com.evolution.analysis.contract.common.ContractChecks.token(GraphJson.string(value.get("mechanism")),"generation mechanism");var region=span(value.get("sourceSpan"),null,true);
        if(region==null||region.start()==region.end())throw new IOException("SEMANTIC_GENERATION_ORIGIN_RANGE");
        if(!documents.get(region.document()).content().equals(LanguageSemanticRequest.handle(value.get("source"))))throw new IOException("SEMANTIC_GENERATION_ORIGIN_CONTENT");
    }
    private Region span(Object raw,String fallback,boolean occurrence)throws IOException{
        if(raw==null)return null;var value=GraphJson.object(raw);String byPath=optionalString(value,"path"),nativeDocument=optionalString(value,"document"),byDocument=nativeDocument==null?null:aliases.getOrDefault(nativeDocument,nativeDocument);
        if(byPath!=null&&byDocument!=null&&!byPath.equals(byDocument))throw new IOException("SEMANTIC_SOURCE_COORDINATES_IDENTIFIER_CONFLICT");String path=byPath!=null?byPath:byDocument!=null?byDocument:fallback;
        if(fallback!=null&&!Objects.equals(path,fallback))throw new IOException("SEMANTIC_SOURCE_COORDINATES_SCOPE");
        // External symbol coordinates retain their native locator. Captured
        // source membership is necessary before those bytes can be checked.
        if(path==null||!documents.containsKey(path)){if(occurrence)throw new IOException("SEMANTIC_SOURCE_COORDINATES_DOCUMENT");return null;}
        var source=load(path);var start=source.at(number(value.get("startLine")),number(value.get("startColumn")));var end=source.at(number(value.get("endLine")),number(value.get("endColumn")));
        if(end.offset()<start.offset())throw new IOException("SEMANTIC_SOURCE_COORDINATES_REVERSED");
        if(value.containsKey("start")!=value.containsKey("end"))throw new IOException("SEMANTIC_SOURCE_COORDINATES_OFFSETS");
        if(value.containsKey("start")&&(number(value.get("start"))!=start.offset()||number(value.get("end"))!=end.offset()))throw new IOException("SEMANTIC_SOURCE_COORDINATES_OFFSETS");
        return new Region(path,start.offset(),end.offset());
    }
    private SourceCoordinates load(String path)throws IOException{
        if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("SEMANTIC_SOURCE_COORDINATES_CANCELLED");
        if(path.equals(loaded))return coordinates;var document=documents.get(path);var handle=document.content();
        if(handle.size()>Integer.MAX_VALUE-8)throw new IOException("SEMANTIC_SOURCE_COORDINATES_WORKER_REQUIRED");if(!content.verify(handle))throw new IOException("SEMANTIC_SOURCE_COORDINATES_UNAVAILABLE");
        try(var input=content.open(handle)){coordinates=new SourceCoordinates(input.readAllBytes(),document.charset());}loaded=path;return coordinates;
    }
    private static int number(Object raw){if(raw instanceof java.math.BigDecimal value)return value.intValueExact();if(raw instanceof Integer value)return value;if(raw instanceof Long value)return Math.toIntExact(value);throw new IllegalArgumentException("SOURCE_COORDINATE_INTEGER_REQUIRED");}
    private static String optionalString(Map<String,Object> value,String field){return value.get(field)==null?null:GraphJson.string(value.get(field));}
}
