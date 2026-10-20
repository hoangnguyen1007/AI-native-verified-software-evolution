package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.execution.*;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Streaming, canonically sorted table publication. Oversized decode requests retain their CAS handles. */
public final class ExecutionTables {
    private final RepositoryContentStore content;
    public ExecutionTables(RepositoryContentStore content){this.content=Objects.requireNonNull(content);}
    private static String schema(Class<?> type){
        if(type==ExecutionGraph.Task.class)return "execution-task-table:m8.4-v1";
        if(type==ExecutionGraph.Edge.class)return "execution-edge-table:m8.4-v1";
        if(type==ExecutionPlan.Binding.class)return "execution-binding-table:m8.4-v1";
        throw new IllegalArgumentException("UNREGISTERED_TABLE");
    }
    private static String key(Object record){
        if(record instanceof ExecutionGraph.Task task)return task.identity().value();
        if(record instanceof ExecutionGraph.Edge edge)return edge.predecessor().value()+edge.consumer().value();
        if(record instanceof ExecutionPlan.Binding binding)return binding.requirement().localId();
        throw new IllegalArgumentException("UNREGISTERED_TABLE");
    }
    public <T> ExecutionPlan.Table put(Class<T> type,Iterator<T> records,int maxRowBytes,BooleanSupplier cancelled)throws IOException{
        String schema=schema(type);if(maxRowBytes<1)throw new IllegalArgumentException("INVALID_ROW_WORKER");
        final long[] count={0};
        var stream=new InputStream(){
            byte[] pending=new byte[0];int offset;String previous;
            private boolean fill()throws IOException{
                if(offset<pending.length)return true;if(cancelled.getAsBoolean())throw new IOException("EXECUTION_TABLE_CANCELLED");if(!records.hasNext())return false;
                T record=records.next();if(!type.isInstance(record))throw new IOException("EXECUTION_TABLE_TYPE_MISMATCH");String next=key(record);
                if(previous!=null&&previous.compareTo(next)>=0)throw new IOException("EXECUTION_TABLE_ORDER_INVALID");previous=next;
                if(!CanonicalJson.fitsStructure(record,64,maxRowBytes))throw new IOException("EXECUTION_RECORD_WORKER_REQUIRED");
                pending=(CanonicalJson.write(record)+"\n").getBytes(StandardCharsets.UTF_8);offset=0;
                if(pending.length>maxRowBytes)throw new IOException("EXECUTION_RECORD_WORKER_REQUIRED");count[0]=Math.addExact(count[0],1);return true;
            }
            @Override public int read()throws IOException{return fill()?Byte.toUnsignedInt(pending[offset++]):-1;}
            @Override public int read(byte[] out,int start,int length)throws IOException{Objects.checkFromIndexSize(start,length,out.length);if(length==0)return 0;if(!fill())return -1;int n=Math.min(length,pending.length-offset);System.arraycopy(pending,offset,out,start,n);offset+=n;return n;}
        };
        return new ExecutionPlan.Table(schema,content.put(stream,cancelled),count[0]);
    }
    public <T> List<T> readBounded(ExecutionPlan.Table table,Class<T> type,int maxRows,int maxRowBytes)throws IOException{
        if(!schema(type).equals(table.schema()))throw new IOException("EXECUTION_TABLE_TYPE_MISMATCH");
        if(maxRows<0||maxRowBytes<1)throw new IllegalArgumentException("INVALID_TABLE_WORKER");
        if(table.rows()>maxRows)throw new IOException("EXECUTION_TABLE_WORKER_REQUIRED");
        if(!content.verify(table.content()))throw new IOException("EXECUTION_TABLE_CORRUPT");
        var result=new ArrayList<T>();String previous=null;
        try(var input=content.open(table.content())){
            while(true){
                var bytes=new ByteArrayOutputStream();int b;while((b=input.read())!=-1&&b!='\n'){if(bytes.size()>=maxRowBytes)throw new IOException("EXECUTION_RECORD_WORKER_REQUIRED");bytes.write(b);}
                if(b==-1){if(bytes.size()!=0)throw new IOException("EXECUTION_TABLE_UNSEALED_ROW");break;}
                if(bytes.size()==0||result.size()>=maxRows)throw new IOException("EXECUTION_TABLE_COUNT_MISMATCH");
                T record=ExecutionCodec.read(new ByteArrayInputStream(bytes.toByteArray()),type,new GraphJson.Limits(maxRowBytes,64,maxRowBytes));
                if(!Arrays.equals(bytes.toByteArray(),CanonicalJson.write(record).getBytes(StandardCharsets.UTF_8)))throw new IOException("EXECUTION_TABLE_NONCANONICAL");
                String next=key(record);if(previous!=null&&previous.compareTo(next)>=0)throw new IOException("EXECUTION_TABLE_ORDER_INVALID");previous=next;result.add(record);
            }
        }catch(IllegalArgumentException invalid){throw new IOException("EXECUTION_TABLE_INVALID_RECORD",invalid);}
        if(result.size()!=table.rows())throw new IOException("EXECUTION_TABLE_COUNT_MISMATCH");return List.copyOf(result);
    }
}
