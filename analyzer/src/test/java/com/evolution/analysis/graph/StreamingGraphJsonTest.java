package com.evolution.analysis.graph;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class StreamingGraphJsonTest {
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
