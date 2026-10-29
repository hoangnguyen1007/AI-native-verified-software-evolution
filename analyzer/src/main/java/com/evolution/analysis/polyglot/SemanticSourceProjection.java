package com.evolution.analysis.polyglot;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Replays source projections against captured native rows and exact source bytes.
 * Stored precision labels are data; they confer no verification authority. */
final class SemanticSourceProjection implements AutoCloseable {
    private final LanguageSemanticRequest base;
    private final SemanticTables.Table nativeOutput,maps;
    private final RepositoryContentStore content;
    private final SemanticTables tables;
    private final BooleanSupplier cancelled;
    private final SemanticTables.Reader nativeRows;
    private String cachedDocument;
    private EmbeddedSourceMap.Prepared prepared;

    static SemanticSourceProjection open(LanguageSemanticRequest request,SemanticTables tables,RepositoryContentStore content,BooleanSupplier cancelled)throws IOException {
        if(!request.provider().equals(SourceMappedSemanticProvider.PROVIDER))return null;
        try {
            var input=object(request.resolution(),content);
            if(!input.keySet().equals(Set.of("schema","base","observations","maps","mapper"))||!"semantic-source-projection-input:m8.4-v1".equals(input.get("schema"))||!same(input.get("mapper"),SourceMappedSemanticProvider.PROVIDER))throw new IOException("SEMANTIC_SOURCE_PROJECTION_INPUT");
            var base=LanguageSemanticRequest.from(object(LanguageSemanticRequest.handle(input.get("base")),content));
            if(base.provider().equals(SourceMappedSemanticProvider.PROVIDER))throw new IOException("SEMANTIC_SOURCE_PROJECTION_RECURSION");
            var expected=new LanguageSemanticRequest(base.context(),base.inventory(),base.root(),base.module(),base.sourceSet(),base.variant(),base.language(),base.dialect(),base.documents(),base.census(),request.resolution(),SourceMappedSemanticProvider.PROVIDER,CanonicalJson.sha256(List.of(SourceMappedSemanticProvider.PROVIDER,base.tool())),base.policy(),base.workPolicy());
            if(!request.equals(expected))throw new IOException("SEMANTIC_SOURCE_PROJECTION_INPUT_BINDING");
            var nativeOutput=LanguageSemanticRequest.table(input.get("observations"));var maps=LanguageSemanticRequest.table(input.get("maps"));
            if(nativeOutput.kind()!=SemanticTables.Kind.OBSERVATION||maps.kind()!=SemanticTables.Kind.DOCUMENT)throw new IOException("SEMANTIC_SOURCE_PROJECTION_TABLE_KIND");
            var empty=tables.write(SemanticTables.Kind.OBSERVATION,Collections.emptyIterator(),cancelled);
            new SemanticReconciler().reconcile(base,empty,tables,content,Map.of(),Set.of(),base::policy,g->{},cancelled);
            validateMaps(base,maps,tables,content,cancelled);
            return new SemanticSourceProjection(base,nativeOutput,maps,content,tables,cancelled);
        }catch(IllegalArgumentException|ArithmeticException|ClassCastException invalid){throw new IOException("SEMANTIC_SOURCE_PROJECTION_INVALID",invalid);}
    }
    private SemanticSourceProjection(LanguageSemanticRequest base,SemanticTables.Table nativeOutput,SemanticTables.Table maps,RepositoryContentStore content,SemanticTables tables,BooleanSupplier cancelled)throws IOException {
        this.base=base;this.nativeOutput=nativeOutput;this.maps=maps;this.content=content;this.tables=tables;this.cancelled=cancelled;nativeRows=tables.open(nativeOutput,cancelled);
    }
    /** The target status is preserved. A separate gap covers incomplete mapping. */
    boolean validate(SemanticTables.Row row,SemanticTables.Row census)throws IOException {
        try {
            var nativeRow=nativeRows.next();var projection=GraphJson.object(row.fields().get("sourceProjection"));
            if(nativeRow==null||!nativeRow.key().equals(row.key())||!nativeRow.scope().equals(row.scope()))throw new IOException("SEMANTIC_SOURCE_PROJECTION_NATIVE_CENSUS");
            var unchanged=new TreeMap<>(row.fields());unchanged.remove("sourceProjection");
            if(!same(unchanged,nativeRow.fields())||nativeRow.fields().containsKey("sourceProjection"))throw new IOException("SEMANTIC_SOURCE_PROJECTION_NATIVE_MUTATION");
            String document=GraphJson.string(census.fields().get("document"));
            if(!projection.keySet().equals(Set.of("schema","nativeObservation","nativeTable","nativeProvider","map","document","mapRowOffset","precision","mapping"))||!"semantic-source-projection:m8.4-v1".equals(projection.get("schema"))||!nativeRow.identity().value().equals(projection.get("nativeObservation"))||!same(nativeOutput,projection.get("nativeTable"))||!same(base.provider(),projection.get("nativeProvider"))||!document.equals(projection.get("document")))throw new IOException("SEMANTIC_SOURCE_PROJECTION_NATIVE_BINDING");
            var mapRow=mapRow(GraphJson.string(projection.get("mapRowOffset")));
            if(!mapRow.key().equals(document)||!same(mapRow.fields().get("map"),projection.get("map")))throw new IOException("SEMANTIC_SOURCE_PROJECTION_MAP_BINDING");
            String precision;Object mapping=null;
            if(mapRow.fields().get("map")==null){precision="NOT_EMBEDDED";}
            else if(row.fields().get("span")==null){precision="ABSENT_NATIVE_LOCATION";}
            else {
                if(!document.equals(cachedDocument)){prepared=readMap(LanguageSemanticRequest.handle(mapRow.fields().get("map")),content).prepare(content,cancelled);cachedDocument=document;}
                var span=GraphJson.object(row.fields().get("span"));if(span.get("path")!=null&&!document.equals(span.get("path")))throw new IOException("SEMANTIC_SOURCE_PROJECTION_SPAN_DOCUMENT");
                int start=((java.math.BigDecimal)span.get("start")).intValueExact(),end=((java.math.BigDecimal)span.get("end")).intValueExact();if(start==end){precision="POINT_ONLY";mapping=prepared.point(start,cancelled);}else{var actual=prepared.map(start,end,cancelled);precision=actual.precision().name();mapping=actual;}
            }
            if(!precision.equals(projection.get("precision"))||!same(mapping,projection.get("mapping")))throw new IOException("SEMANTIC_SOURCE_PROJECTION_MAPPING_MISMATCH");
            return Set.of("PIECEWISE","UNMAPPED","POINT_ONLY").contains(precision);
        }catch(IllegalArgumentException|ArithmeticException|ClassCastException invalid){throw new IOException("SEMANTIC_SOURCE_PROJECTION_INVALID",invalid);}
    }
    void finish()throws IOException {
        if(nativeRows.next()!=null)throw new IOException("SEMANTIC_SOURCE_PROJECTION_NATIVE_OMITTED");
        validateMaps(base,maps,tables,content,cancelled);
    }
    private SemanticTables.Row mapRow(String offset)throws IOException {
        long position;try{position=Long.parseLong(offset);if(position<0||!Long.toString(position).equals(offset)||position>=maps.content().size())throw new NumberFormatException();}catch(NumberFormatException invalid){throw new IOException("SEMANTIC_SOURCE_PROJECTION_MAP_OFFSET",invalid);}
        if(position!=0)try(var previous=content.range(maps.content(),position-1,1)){if(previous.read()!='\n'||previous.read()!=-1)throw new IOException("SEMANTIC_SOURCE_PROJECTION_MAP_OFFSET");}
        try(var input=content.range(maps.content(),position,Math.min(65537,maps.content().size()-position))){
            var bytes=new ByteArrayOutputStream();int b;while((b=input.read())!=-1&&b!='\n'){if(bytes.size()==65536)throw new IOException("SEMANTIC_SOURCE_MAP_ROW_WORKER_REQUIRED");bytes.write(b);}if(b!='\n')throw new IOException("SEMANTIC_SOURCE_PROJECTION_MAP_OFFSET");
            String line=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();var raw=GraphJson.object(GraphJson.read(line,new GraphJson.Limits(65536,64,65536)));
            if(!raw.keySet().equals(Set.of("kind","key","scope","fields")))throw new IOException("SEMANTIC_SOURCE_PROJECTION_MAP_ROW");
            var row=new SemanticTables.Row(SemanticTables.Kind.valueOf(GraphJson.string(raw.get("kind"))),GraphJson.string(raw.get("key")),new com.evolution.analysis.contract.common.ContentDigest(GraphJson.string(raw.get("scope"))),GraphJson.object(raw.get("fields")));
            if(row.kind()!=SemanticTables.Kind.DOCUMENT||!row.scope().equals(base.scope())||!row.fields().keySet().equals(Set.of("map"))||!CanonicalJson.write(row).equals(line))throw new IOException("SEMANTIC_SOURCE_PROJECTION_MAP_ROW");return row;
        }
    }
    private static void validateMaps(LanguageSemanticRequest base,SemanticTables.Table maps,SemanticTables tables,RepositoryContentStore content,BooleanSupplier cancelled)throws IOException {
        try(var documents=tables.open(base.documents(),cancelled);var rows=tables.open(maps,cancelled)){
            SemanticTables.Row doc;while((doc=documents.next())!=null){var row=rows.next();if(row==null||!row.key().equals(doc.key())||!row.scope().equals(base.scope())||!row.fields().keySet().equals(Set.of("map")))throw new IOException("SEMANTIC_SOURCE_MAP_DOCUMENT_CENSUS_OPEN");if(row.fields().get("map")==null)continue;
                var map=readMap(LanguageSemanticRequest.handle(row.fields().get("map")),content);if(!map.generated().path().equals(doc.key())||!same(map.generated().content(),doc.fields().get("content")))throw new IOException("SEMANTIC_SOURCE_MAP_GENERATED_BINDING");map.prepare(content,cancelled);
            }if(rows.next()!=null)throw new IOException("SEMANTIC_SOURCE_MAP_OUTSIDE_DOCUMENTS");
        }
    }
    private static EmbeddedSourceMap readMap(ContentHandle handle,RepositoryContentStore content)throws IOException{return EmbeddedSourceMap.from(object(handle,content));}
    private static Map<String,Object> object(ContentHandle handle,RepositoryContentStore content)throws IOException {
        if(handle.size()>65536||!content.verify(handle))throw new IOException("SEMANTIC_SOURCE_MAP_UNAVAILABLE_OR_WORKER_REQUIRED");try(var input=content.open(handle)){var value=GraphJson.object(GraphJson.read(new InputStreamReader(input,StandardCharsets.UTF_8),new GraphJson.Limits(65536,64,65536)));if(!CanonicalJson.sha256(value).equals(handle.digest()))throw new IOException("SEMANTIC_SOURCE_MAP_NONCANONICAL");return value;}
    }
    private static boolean same(Object left,Object right){return CanonicalJson.write(left).equals(CanonicalJson.write(right));}
    @Override public void close()throws IOException{nativeRows.close();}
}
