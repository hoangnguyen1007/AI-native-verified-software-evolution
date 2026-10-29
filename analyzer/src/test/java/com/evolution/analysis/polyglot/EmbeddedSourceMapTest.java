package com.evolution.analysis.polyglot;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EmbeddedSourceMapTest {
    final SemanticTablesTest.Memory content=new SemanticTablesTest.Memory();
    EmbeddedSourceMap.Document document(String path,String text)throws IOException{return new EmbeddedSourceMap.Document(path,content.text(text),"UTF-8");}
    @Test void exactNativeRegionRetainsBomCrLfAndNonBmpCoordinates()throws Exception{
        var original=document("page.vue","\uFEFF<script>\r\nconst marker='😀'; twice(4)\r\n</script>");var generated=document("page.vue.ts","const marker='😀'; twice(4)");
        var map=new EmbeddedSourceMap(generated,Map.of(original.path(),original),List.of(new EmbeddedSourceMap.Segment(0,27,original.path(),11,38)),"captured-native-extractor-v1");
        var result=map.map(19,27,content,()->false);assertEquals(EmbeddedSourceMap.Precision.FULL,result.precision());assertEquals(new SourceCoordinates.Range(30,38,2,20,2,28),result.pieces().getFirst().range());
    }
    @Test void wrappersAndSeparatedOriginalRegionsNeverBecomeAnInventedFullSpan()throws Exception{
        var original=document("page.html","aa gap bb");var generated=document("virtual.ts","aaXXbb");
        var map=new EmbeddedSourceMap(generated,Map.of(original.path(),original),List.of(new EmbeddedSourceMap.Segment(0,2,original.path(),0,2),new EmbeddedSourceMap.Segment(4,6,original.path(),7,9)),"extract-v1");
        var result=map.map(0,6,content,()->false);assertEquals(EmbeddedSourceMap.Precision.PIECEWISE,result.precision());assertEquals(4,result.mappedUnits());assertEquals(2,result.pieces().size());
        assertEquals(EmbeddedSourceMap.Precision.UNMAPPED,map.map(2,4,content,()->false).precision());
    }
    @Test void adjacentExactSegmentsCanComposeAndMultipleDocumentsRemainPiecewise()throws Exception{
        var first=document("a","abcd");var second=document("b","ef");var generated=document("virtual","abcdef");
        var map=new EmbeddedSourceMap(generated,Map.of("a",first,"b",second),List.of(new EmbeddedSourceMap.Segment(0,2,"a",0,2),new EmbeddedSourceMap.Segment(2,4,"a",2,4),new EmbeddedSourceMap.Segment(4,6,"b",0,2)),"native-v1");
        assertEquals(EmbeddedSourceMap.Precision.FULL,map.map(0,4,content,()->false).precision());var result=map.map(0,6,content,()->false);assertEquals(EmbeddedSourceMap.Precision.PIECEWISE,result.precision());assertEquals(6,result.mappedUnits());assertEquals(2,result.pieces().size());
    }
    @Test void wrongMappingOverlapMissingBytesAndCancellationCannotAuthorizeNavigation()throws Exception{
        var source=document("a","abc");var generated=document("virtual","xyz");var map=new EmbeddedSourceMap(generated,Map.of("a",source),List.of(new EmbeddedSourceMap.Segment(0,3,"a",0,3)),"native-v1");
        assertThrows(IOException.class,()->map.map(0,3,content,()->false));assertThrows(IOException.class,()->map.map(0,3,content,()->true));
        assertThrows(IllegalArgumentException.class,()->new EmbeddedSourceMap(generated,Map.of("a",source),List.of(new EmbeddedSourceMap.Segment(0,2,"a",0,2),new EmbeddedSourceMap.Segment(1,3,"a",1,3)),"native-v1"));
        assertThrows(IllegalArgumentException.class,()->new EmbeddedSourceMap.Segment(0,1,"a",0,2));
        content.blobs.remove(generated.content().digest());assertThrows(IOException.class,()->map.map(0,3,content,()->false));
    }
}
