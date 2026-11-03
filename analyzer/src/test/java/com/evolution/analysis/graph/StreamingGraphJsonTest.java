package com.evolution.analysis.graph;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class StreamingGraphJsonTest {
    @Test void objectVisitorReleasesRowsBeforeReadingTheNextRecord()throws Exception{
        String json="{\"rows\":[{\"name\":\"first\"},{\"name\":\"second\"}]}";
        class Fragmented extends StringReader {int consumed;Fragmented(){super(json);}@Override public int read(char[] chars,int offset,int length)throws IOException{int count=super.read(chars,offset,Math.min(length,1));if(count>0)consumed+=count;return count;}}
        var input=new Fragmented();var names=new ArrayList<String>();
        GraphJson.streamObject(input,new GraphJson.StreamLimits(4_000_000_000L,128,4_000_000_000L),(name,member)->{
            assertEquals("rows",name);member.array(row->{if(names.isEmpty())assertTrue(input.consumed<json.indexOf("second"));names.add((String)GraphJson.object(row).get("name"));});
        });
        assertEquals(List.of("first","second"),names);
    }
    @Test void visitorRetainsStructuralTokenAndSingleConsumptionBounds(){
        assertThrows(GraphJson.LimitExceededException.class,()->GraphJson.streamObject(new StringReader("{\"x\":\"long\"}"),new GraphJson.StreamLimits(100,128,100,3),(name,member)->member.read()));
        assertThrows(IllegalArgumentException.class,()->GraphJson.streamObject(new StringReader("{\"x\":1}"),new GraphJson.StreamLimits(100,128,100),(name,member)->{}));
        assertThrows(IllegalArgumentException.class,()->GraphJson.streamObject(new StringReader("{\"x\":1}"),new GraphJson.StreamLimits(100,128,100),(name,member)->{member.read();member.read();}));
        for(int depth=1;depth<5;depth++)for(int values=1;values<12;values++){
            var limits=new GraphJson.Limits(1000,depth,values);String json="{\"rows\":[{\"x\":1},{\"x\":2}]}";
            boolean legacy;try{GraphJson.read(json,limits);legacy=true;}catch(GraphJson.LimitExceededException limit){legacy=false;}
            boolean streamed;try{GraphJson.streamObject(new StringReader(json),GraphJson.StreamLimits.from(limits),(name,member)->member.array(row->{}));streamed=true;}catch(GraphJson.LimitExceededException limit){streamed=false;}catch(IOException impossible){throw new AssertionError(impossible);}
            assertEquals(legacy,streamed,depth+" / "+values);
        }
    }
    @Test void fragmentedInputPreservesEscapesNumbersUnicodeAndStableEof()throws Exception{
        String json=CanonicalJson.write(Map.of("text","Tiếng Việt 😀\n\\\"", "items",Arrays.asList(true,null,new java.math.BigDecimal("1.25"))));
        var input=new StringReader(json){int eof;@Override public int read(char[] chars,int offset,int length)throws IOException{int count=super.read(chars,offset,Math.min(length,1));if(count<0){eof++;assertEquals(1,eof,"EOF must remain terminal");}return count;}};
        assertEquals(GraphJson.read(json,GraphJson.Limits.defaults()),GraphJson.read(input,GraphJson.Limits.defaults()));
    }
    @Test void streamKeepsStrictMalformedAndResourceOutcomes(){
        for(String bad:List.of("{\"x\":1,\"x\":2}","[1,]","true false","1e+","\"\\ud800\"","{\"x\":"))assertThrows(IllegalArgumentException.class,()->GraphJson.read(new StringReader(bad),GraphJson.Limits.defaults()));
        assertThrows(GraphJson.LimitExceededException.class,()->GraphJson.read(new StringReader("[1,2,3]"),new GraphJson.Limits(5,4,20)));
        assertThrows(GraphJson.LimitExceededException.class,()->GraphJson.read(new StringReader("[[[0]]]"),new GraphJson.Limits(20,1,20)));
        assertThrows(GraphJson.LimitExceededException.class,()->GraphJson.read(new StringReader("[1,2]"),new GraphJson.Limits(20,4,2)));
    }
}
