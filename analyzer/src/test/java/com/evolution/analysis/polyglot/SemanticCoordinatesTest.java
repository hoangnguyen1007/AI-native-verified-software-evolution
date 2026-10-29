package com.evolution.analysis.polyglot;

import com.evolution.analysis.acquisition.streaming.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SemanticCoordinatesTest {
    Map<String,Object> span(long start,long end,int line,int column,int endLine,int endColumn){return Map.of("start",start,"end",end,"startLine",line,"startColumn",column,"endLine",endLine,"endColumn",endColumn);}
    void analyze(String source,Map<String,Object> expected,Map<String,Object> observed,List<?> symbols)throws Exception{
        analyze(source,expected,observed,symbols,Map.of());
    }
    void analyze(String source,Map<String,Object> expected,Map<String,Object> observed,List<?> symbols,Map<String,Object> metadata)throws Exception{
        var f=new SemanticReconcilerTest.Fixture();var bytes=f.content.put(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)),()->false);
        var fields=new TreeMap<String,Object>(metadata);fields.put("content",bytes);fields.put("charset","UTF-8");var documents=f.tables.write(SemanticTables.Kind.DOCUMENT,List.of(new SemanticTables.Row(SemanticTables.Kind.DOCUMENT,"app.ts",f.request.scope(),fields)).iterator(),()->false);
        var question=new SemanticTables.Row(SemanticTables.Kind.CENSUS,"A01",f.request.scope(),Map.of("document","app.ts","category","A01","span",expected));var request=f.with(documents,f.tables.write(SemanticTables.Kind.CENSUS,List.of(question).iterator(),()->false));var output=f.tables.write(SemanticTables.Kind.OBSERVATION,List.of(new SemanticTables.Row(SemanticTables.Kind.OBSERVATION,"A01",request.scope(),Map.of("obligation",question.identity().value(),"status","RESOLVED","targets",List.of(),"span",observed,"symbols",symbols))).iterator(),()->false);
        new SemanticReconciler().reconcile(request,output,f.tables,f.content,Map.of(),Set.of(),request::policy,g->{},()->false);
    }
    @Test void forgedColumnsForeignOccurrencePathsAndSplitSurrogatesAreRejected()throws Exception{
        var correct=span(0,6,1,1,1,7);var wrongColumn=span(0,6,1,2,1,8);var foreign=new TreeMap<String,Object>(correct);foreign.put("path","other.ts");
        assertThrows(IOException.class,()->analyze("export const value=1;",correct,wrongColumn,List.of()));
        assertThrows(IOException.class,()->analyze("export const value=1;",foreign,foreign,List.of()));
        for(String field:List.of("path","document")){var malformed=new TreeMap<String,Object>(correct);malformed.put(field,42);assertThrows(IOException.class,()->analyze("export const value=1;",malformed,malformed,List.of()));}
        var split=span(1,2,1,2,1,3);assertThrows(IOException.class,()->analyze("😀x",split,split,List.of()));
    }
    @Test void symbolCoordinatesCannotBorrowAnUnrelatedOccurrenceSpan()throws Exception{
        var correct=span(0,6,1,1,1,7);var symbol=Map.of("path","app.ts","span",span(7,12,3,1,3,6));assertThrows(IOException.class,()->analyze("export value",correct,correct,List.of(symbol)));
        var claimed=new TreeMap<String,Object>(correct);claimed.put("path","app.ts");assertThrows(IOException.class,()->analyze("export value",correct,correct,List.of(Map.of("path","other.ts","span",claimed))));
    }
    @Test void originalBomCrLfUnicodeFullRangesAndActualPointsRemainRepresentable()throws Exception{
        var full=span(3,6,2,1,2,4);analyze("\ufeff\r\n😀x",full,full,List.of(Map.of("path","app.ts","span",span(3,5,2,1,2,3))));var point=span(6,6,2,4,2,4);analyze("\ufeff\r\n😀x",point,point,List.of());
        var lines=Map.<String,Object>of("startLine",2,"startColumn",1,"endLine",2,"endColumn",4);analyze("\ufeff\r\n😀x",lines,lines,List.of());
    }
    @Test void declaredLegacyDocumentIdentityMapsToItsOwnBytesWithoutChangingLegacySpans()throws Exception{
        var repository=com.evolution.analysis.contract.identity.RepositoryIdentity.fromCanonicalCoordinate("urn:test:polyglot");var identity=com.evolution.analysis.contract.identity.SourceDocumentIdentity.from(repository,"app.ts").value();var span=Map.<String,Object>of("document",identity,"startLine",1,"startColumn",1,"endLine",1,"endColumn",7);analyze("export value",span,span,List.of(),Map.of("legacyDocument",identity));var wrong=new TreeMap<>(span);wrong.put("document",com.evolution.analysis.contract.identity.SourceDocumentIdentity.from(repository,"other.ts").value());assertThrows(IOException.class,()->analyze("export value",wrong,wrong,List.of(),Map.of("legacyDocument",identity)));
    }
    @Test void aLiteralNativePathCannotBeRedirectedThroughALegacyDocumentAlias()throws Exception{
        var f=new SemanticReconcilerTest.Fixture();var repository=com.evolution.analysis.contract.identity.RepositoryIdentity.fromCanonicalCoordinate("urn:test:polyglot");var alias=com.evolution.analysis.contract.identity.SourceDocumentIdentity.from(repository,"app.ts").value();var rows=new ArrayList<SemanticTables.Row>();try(var documents=f.tables.open(f.request.documents(),()->false)){var document=documents.next();var fields=new TreeMap<>(document.fields());fields.put("legacyDocument",alias);rows.add(new SemanticTables.Row(document.kind(),document.key(),document.scope(),fields));}rows.add(new SemanticTables.Row(SemanticTables.Kind.DOCUMENT,alias,f.request.scope(),Map.of("content",f.content.text("x"))));rows.sort(Comparator.comparing(SemanticTables.Row::key));
        var span=new TreeMap<String,Object>(span(0,6,1,1,1,7));span.put("path",alias);var question=new SemanticTables.Row(SemanticTables.Kind.CENSUS,"A01",f.request.scope(),Map.of("document",alias,"category","A01","span",span));var request=f.with(f.tables.write(SemanticTables.Kind.DOCUMENT,rows.iterator(),()->false),f.tables.write(SemanticTables.Kind.CENSUS,List.of(question).iterator(),()->false));var output=f.tables.write(SemanticTables.Kind.OBSERVATION,List.of(new SemanticTables.Row(SemanticTables.Kind.OBSERVATION,"A01",request.scope(),Map.of("obligation",question.identity().value(),"status","RESOLVED","targets",List.of(),"span",span))).iterator(),()->false);assertThrows(IOException.class,()->new SemanticReconciler().reconcile(request,output,f.tables,f.content,Map.of(),Set.of(),request::policy,g->{},()->false));
    }
    @Test void suppliedGenerationLineageMustBindTheCapturedOriginBytesAndFullOriginRange()throws Exception{
        var range=span(0,6,1,1,1,7);var origin=new TreeMap<String,Object>(range);origin.put("path","app.ts");String source="export value";var bytes=source.getBytes(StandardCharsets.UTF_8);var handle=new ContentHandle(com.evolution.analysis.contract.common.ContentDigest.sha256(bytes),bytes.length);var lineage=new TreeMap<String,Object>(Map.of("schema","semantic-generation-lineage:m8.4-v1","mechanism","AUTHORED_NATIVE_GENERATION_CONTROL","source",handle,"sourceSpan",origin));var bad=new TreeMap<>(lineage);bad.put("source",new ContentHandle(com.evolution.analysis.contract.common.ContentDigest.sha256Utf8("foreign"),bytes.length));assertThrows(IOException.class,()->analyze(source,range,range,List.of(Map.of("origin","GENERATED","generationLineage",bad))));analyze(source,range,range,List.of(Map.of("origin","GENERATED","generationLineage",lineage)));
    }
}
