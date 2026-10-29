package com.evolution.analysis.polyglot;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Data-only native -> original-source projection. Native spans, identities and
 * semantic statuses stay intact; mapping precision is a separate evidence axis. */
public final class SourceMappedSemanticProvider implements LanguageSemanticProvider {
    public static final VersionedIdentifier PROVIDER=new VersionedIdentifier("frontend.source-map-projection","m8.4-v1");
    private final LanguageSemanticRequest base,request;private final SemanticTables.Table nativeOutput;private final SemanticTables.Table maps;private final RepositoryContentStore content;private final SemanticTables tables;private final SemanticContextCoordinator.CensusIndex indexes;
    private SourceMappedSemanticProvider(LanguageSemanticRequest base,SemanticTables.Table nativeOutput,SemanticTables.Table maps,RepositoryContentStore content,SemanticTables tables,SemanticContextCoordinator.CensusIndex indexes,LanguageSemanticRequest request){this.base=base;this.nativeOutput=nativeOutput;this.maps=maps;this.content=content;this.tables=tables;this.indexes=indexes;this.request=request;}
    public static SourceMappedSemanticProvider create(LanguageSemanticRequest base,SemanticTables.Table nativeOutput,SemanticTables.Table maps,RepositoryContentStore content,SemanticTables tables,SemanticContextCoordinator.CensusIndex indexes,BooleanSupplier cancelled)throws IOException{
        if(nativeOutput.kind()!=SemanticTables.Kind.OBSERVATION||maps.kind()!=SemanticTables.Kind.DOCUMENT)throw new IOException("SEMANTIC_SOURCE_MAP_TABLE_KIND");var baseHandle=put(content,base,cancelled);var resolution=put(content,Map.of("schema","semantic-source-projection-input:m8.4-v1","base",baseHandle,"observations",nativeOutput,"maps",maps,"mapper",PROVIDER),cancelled);var request=new LanguageSemanticRequest(base.context(),base.inventory(),base.root(),base.module(),base.sourceSet(),base.variant(),base.language(),base.dialect(),base.documents(),base.census(),resolution,PROVIDER,CanonicalJson.sha256(List.of(PROVIDER,base.tool())),base.policy(),base.workPolicy());
        return new SourceMappedSemanticProvider(base,Objects.requireNonNull(nativeOutput),maps,Objects.requireNonNull(content),Objects.requireNonNull(tables),Objects.requireNonNull(indexes),request);
    }
    public LanguageSemanticRequest request(){return request;}
    @Override public SemanticTables.Table analyze(LanguageSemanticRequest selected,BooleanSupplier cancelled)throws IOException{
        if(!request.equals(selected))throw new IOException("SEMANTIC_SOURCE_PROJECTION_REQUEST_BINDING");
        try(var index=indexes.open()){
            for(String space:List.of("mapped.documents","mapped.maps"))try(var rows=index.rows(space,false)){if(rows.next()!=null)throw new IOException("SEMANTIC_SOURCE_MAP_INDEX_NOT_EMPTY");}
            try(var docs=tables.open(base.documents(),cancelled)){SemanticTables.Row row;while((row=docs.next())!=null){if(!row.scope().equals(base.scope()))throw new IOException("SEMANTIC_SOURCE_MAP_DOCUMENT_SCOPE");index.put("mapped.documents",row.key(),row);}}
            long offset=0;try(var reader=tables.open(maps,cancelled)){SemanticTables.Row row;while((row=reader.next())!=null){
                if(!row.scope().equals(base.scope())||!row.fields().keySet().equals(Set.of("map")))throw new IOException("SEMANTIC_SOURCE_MAP_ROW_FIELDS");var document=index.get("mapped.documents",row.key()).orElseThrow(()->new IOException("SEMANTIC_SOURCE_MAP_OUTSIDE_DOCUMENTS"));if(row.fields().get("map")!=null){var map=readMap(handle(row.fields().get("map")));if(!map.generated().path().equals(row.key())||!map.generated().content().equals(handle(document.fields().get("content"))))throw new IOException("SEMANTIC_SOURCE_MAP_GENERATED_BINDING");map.prepare(content,cancelled);}var indexed=new TreeMap<String,Object>(row.fields());indexed.put("offset",Long.toString(offset));index.put("mapped.maps",row.key(),new SemanticTables.Row(row.kind(),row.key(),row.scope(),indexed));offset=Math.addExact(offset,(CanonicalJson.write(row)+"\n").getBytes(StandardCharsets.UTF_8).length);
            }}
            new SemanticReconciler().reconcile(base,nativeOutput,tables,content,Map.of(),Set.of(),base::policy,g->{},cancelled);
            try(var census=tables.open(base.census(),cancelled);var nativeRows=tables.open(nativeOutput,cancelled)){
                var iterator=new Iterator<SemanticTables.Row>(){SemanticTables.Row pending,question;String cached;EmbeddedSourceMap.Prepared prepared;ContentHandle mapHandle;
                    public boolean hasNext(){try{if(pending==null)pending=nativeRows.next();return pending!=null;}catch(IOException e){throw new UncheckedIOException(e);}}
                    public SemanticTables.Row next(){if(!hasNext())throw new NoSuchElementException();var row=pending;pending=null;try{
                        while(question==null||question.key().compareTo(row.key())<0){question=census.next();if(question==null)throw new IOException("SEMANTIC_SOURCE_PROJECTION_CENSUS_BINDING");}if(!question.key().equals(row.key()))throw new IOException("SEMANTIC_SOURCE_PROJECTION_CENSUS_BINDING");String document=GraphJson.string(question.fields().get("document"));var entry=index.get("mapped.maps",document);if(entry.isEmpty())throw new IOException("SEMANTIC_SOURCE_MAP_DOCUMENT_CENSUS_OPEN");
                        if(row.fields().containsKey("sourceProjection"))throw new IOException("SEMANTIC_SOURCE_PROJECTION_ALREADY_PRESENT");
                        if(!document.equals(cached)){mapHandle=entry.orElseThrow().fields().get("map")==null?null:handle(entry.orElseThrow().fields().get("map"));prepared=mapHandle==null?null:readMap(mapHandle).prepare(content,cancelled);cached=document;}
                        var projection=new TreeMap<String,Object>();projection.put("schema","semantic-source-projection:m8.4-v1");projection.put("nativeObservation",row.identity());projection.put("nativeTable",nativeOutput);projection.put("nativeProvider",base.provider());projection.put("map",mapHandle);projection.put("document",document);projection.put("mapRowOffset",entry.orElseThrow().fields().get("offset"));
                        if(mapHandle==null){projection.put("precision","NOT_EMBEDDED");projection.put("mapping",null);}
                        else if(row.fields().get("span")==null){projection.put("precision","ABSENT_NATIVE_LOCATION");projection.put("mapping",null);}
                        else{var span=GraphJson.object(row.fields().get("span"));if(span.get("path")!=null&&!document.equals(span.get("path")))throw new IOException("SEMANTIC_SOURCE_PROJECTION_SPAN_DOCUMENT");int start=((java.math.BigDecimal)span.get("start")).intValueExact(),end=((java.math.BigDecimal)span.get("end")).intValueExact();if(start==end){projection.put("precision","POINT_ONLY");projection.put("mapping",neutral(prepared.point(start,cancelled)));}else{var mapping=prepared.map(start,end,cancelled);projection.put("precision",mapping.precision().name());projection.put("mapping",neutral(mapping));}}
                        var fields=new TreeMap<>(row.fields());fields.put("sourceProjection",neutral(projection));return new SemanticTables.Row(row.kind(),row.key(),row.scope(),fields);
                    }catch(IOException e){throw new UncheckedIOException(e);}}
                };var projected=tables.write(SemanticTables.Kind.OBSERVATION,iterator,cancelled);
                // Hash-check original/generated inputs again before publishing a batch.
                try(var rows=index.rows("mapped.maps",false)){SemanticTables.Row row;while((row=rows.next())!=null)if(row.fields().get("map")!=null)readMap(handle(row.fields().get("map"))).prepare(content,cancelled);}return projected;
            }
        }catch(UncheckedIOException e){throw e.getCause();}catch(IllegalArgumentException|ArithmeticException|ClassCastException invalid){throw new IOException("SEMANTIC_SOURCE_PROJECTION_INVALID",invalid);}
    }
    private EmbeddedSourceMap readMap(ContentHandle handle)throws IOException{if(handle.size()>65536||!content.verify(handle))throw new IOException("SEMANTIC_SOURCE_MAP_UNAVAILABLE_OR_WORKER_REQUIRED");try(var input=content.open(handle)){var value=GraphJson.object(GraphJson.read(new InputStreamReader(input,StandardCharsets.UTF_8),new GraphJson.Limits(65536,64,65536)));if(!CanonicalJson.sha256(value).equals(handle.digest()))throw new IOException("SEMANTIC_SOURCE_MAP_NONCANONICAL");return EmbeddedSourceMap.from(value);}}
    private static ContentHandle handle(Object value){return value instanceof ContentHandle h?h:LanguageSemanticRequest.handle(value);}
    private static Map<String,Object> neutral(Object value){return GraphJson.object(GraphJson.read(CanonicalJson.write(value),GraphJson.Limits.defaults()));}
    private static ContentHandle put(RepositoryContentStore content,Object value,BooleanSupplier cancelled)throws IOException{return content.put(new ByteArrayInputStream(CanonicalJson.write(value).getBytes(StandardCharsets.UTF_8)),cancelled);}
}
