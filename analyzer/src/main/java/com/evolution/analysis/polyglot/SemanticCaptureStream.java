package com.evolution.analysis.polyglot;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.function.*;

/** Framing only. Native language meaning and qualification stay outside the transport decoder. */
final class SemanticCaptureStream {
    record Parsed(Map<String,Object> header,ContentDigest scope,List<SemanticTables.Table> tables){}
    static Parsed read(ContentHandle export,SemanticTables tables,RepositoryContentStore content,int budget,
            BooleanSupplier cancelled,Predicate<Map<String,Object>> validHeader,String endSchema)throws IOException{
        if(budget<1||budget>64000000)throw new IllegalArgumentException("CAPTURE_WORKER_BUDGET");
        if(!content.verify(export))throw new IOException("CAPTURE_EXPORT_UNAVAILABLE");
        try(var input=content.open(export)){
            var rows=new Rows(input,budget,cancelled);var header=rows.take();
            if(header==null||!validHeader.test(header))throw new IOException("CAPTURE_HEADER_INVALID");
            var scope=new ContentDigest(GraphJson.string(header.get("scope")));var result=new ArrayList<SemanticTables.Table>();
            // Worker wire v1/v2 has exactly these three sections. Adding internal
            // coordinator table kinds must never broaden the admitted worker wire.
            for(var kind:List.of(SemanticTables.Kind.DOCUMENT,SemanticTables.Kind.CENSUS,SemanticTables.Kind.OBSERVATION)){
                Iterator<SemanticTables.Row> iterator=new Iterator<>(){
                    public boolean hasNext(){try{var value=rows.peek();return value!=null&&kind.name().equals(value.get("kind"));}catch(IOException failed){throw new UncheckedIOException(failed);}}
                    public SemanticTables.Row next(){if(!hasNext())throw new NoSuchElementException();try{
                        var value=rows.take();if(!value.keySet().equals(Set.of("kind","key","scope","fields")))throw new IOException("CAPTURE_ROW_FIELDS");
                        var row=new SemanticTables.Row(kind,GraphJson.string(value.get("key")),new ContentDigest(GraphJson.string(value.get("scope"))),GraphJson.object(value.get("fields")));
                        if(!scope.equals(row.scope()))throw new IOException("CAPTURE_ROW_SCOPE");return row;
                    }catch(IOException failed){throw new UncheckedIOException(failed);}}
                };
                try{result.add(tables.write(kind,iterator,cancelled));}catch(UncheckedIOException failed){throw failed.getCause();}
            }
            var end=rows.take();
            if(end==null||!end.keySet().equals(Set.of("schema","scope","documents","census","observations","diagnostics"))||!endSchema.equals(end.get("schema"))||!scope.value().equals(end.get("scope"))||!(end.get("diagnostics") instanceof List<?>))throw new IOException("CAPTURE_END_REQUIRED");
            String[] counts={"documents","census","observations"};
            for(int i=0;i<counts.length;i++)if(!(end.get(counts[i]) instanceof java.math.BigDecimal n)||n.longValueExact()!=result.get(i).rows())throw new IOException("CAPTURE_COUNT_MISMATCH");
            if(rows.take()!=null)throw new IOException("CAPTURE_TRAILING_DATA");return new Parsed(header,scope,List.copyOf(result));
        }catch(IllegalArgumentException|ArithmeticException invalid){throw new IOException("CAPTURE_FORMAT_INVALID",invalid);}
    }
    private static final class Rows {
        final InputStream input;final int budget;final BooleanSupplier cancelled;Map<String,Object> pending;
        Rows(InputStream input,int budget,BooleanSupplier cancelled){this.input=input;this.budget=budget;this.cancelled=cancelled;}
        Map<String,Object> peek()throws IOException{if(pending==null)pending=read();return pending;}
        Map<String,Object> take()throws IOException{var row=peek();pending=null;return row;}
        Map<String,Object> read()throws IOException{
            if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("SEMANTIC_CAPTURE_CANCELLED");
            var bytes=new ByteArrayOutputStream();int b;
            while((b=input.read())!=-1&&b!='\n'){if(bytes.size()==budget)throw new IOException("CAPTURE_ROW_WORKER_REQUIRED");bytes.write(b);}
            if(b==-1){if(bytes.size()!=0)throw new IOException("CAPTURE_UNTERMINATED_ROW");return null;}
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString();
            return GraphJson.object(GraphJson.read(text,new GraphJson.Limits(budget,64,budget)));
        }
    }
}
