package com.evolution.analysis.evolution;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.PartitionedGraphReader.Page;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.function.BooleanSupplier;

final class EvolutionRows {
    private EvolutionRows() {}

    static Map<String,Object> row(Object... pairs) {
        var result=new TreeMap<String,Object>();
        for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);
        return Collections.unmodifiableMap(result);
    }
    static Map<String,Object> obj(Object value){return GraphJson.object(value);}
    static List<Object> list(Object value){return GraphJson.array(value);}
    static String text(Map<String,Object> row,String key){return GraphJson.string(row.get(key));}
    static String maybe(Map<String,Object> row,String key){return row.get(key) instanceof String s?s:null;}
    static long number(Object value){return new java.math.BigDecimal(value.toString()).longValueExact();}
    static ContentDigest hash(String tag,Object value){return CanonicalJson.sha256(List.of(tag,value));}

    static void unique(EvolutionIndex index,String table,String key,Map<String,Object> value)throws IOException {
        if(index.get(table,key).isPresent())throw new EvolutionSnapshot.ReadFailure(EvolutionSnapshot.Failure.CORRUPT);
        index.put(table,key,value);
    }

    @FunctionalInterface interface Pages<T>{Page<T> get(Optional<String> cursor)throws IOException;}
    @FunctionalInterface interface Visit<T>{void accept(T item)throws IOException;}
    static <T> void scan(Pages<T> read,long expected,int size,BooleanSupplier cancelled,Visit<T> visit)throws IOException {
        long count=0;Optional<String> cursor=Optional.empty();
        do {
            EvolutionSnapshot.check(cancelled);
            var page=read.get(cursor);
            if(page.items().size()>size || page.items().isEmpty()&&page.continuation().isPresent()
                    || cursor.isPresent()&&cursor.equals(page.continuation()))throw new EvolutionSnapshot.ReadFailure(EvolutionSnapshot.Failure.CORRUPT);
            for(var item:page.items()) {
                EvolutionSnapshot.check(cancelled);
                if(++count>expected)throw new EvolutionSnapshot.ReadFailure(EvolutionSnapshot.Failure.CORRUPT);
                visit.accept(item);
            }
            cursor=page.continuation();
        }while(cursor.isPresent());
        if(count!=expected)throw new EvolutionSnapshot.ReadFailure(EvolutionSnapshot.Failure.CORRUPT);
    }

    static final class Rows {
        private final EvolutionIndex index;
        private final String table,prefix;
        private Iterator<EvolutionIndex.Entry> page=Collections.emptyIterator();
        private String after;
        private EvolutionIndex.Entry head;
        private boolean exhausted;
        Rows(EvolutionIndex index,String table,String prefix){this.index=index;this.table=table;this.prefix=prefix;this.after=prefix;}
        EvolutionIndex.Entry head()throws IOException {
            if(head==null&&!exhausted) {
                if(!page.hasNext())page=index.page(table,after,32).rows().iterator();
                if(page.hasNext()){
                    var next=page.next();
                    if(next.key().startsWith(prefix))head=next;
                    else exhausted=true;
                }else exhausted=true;
            }
            return head;
        }
        EvolutionIndex.Entry next()throws IOException {
            var item=head();if(item!=null){after=item.key();head=null;}return item;
        }
    }

    /** Hash the canonical logical row stream, independently of input and output pages. */
    static final class ArrayDigest implements AutoCloseable {
        private final MessageDigest digest;
        private final Writer output;
        private boolean first=true,finished;
        ArrayDigest()throws IOException {
            try{digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
            output=new BufferedWriter(new OutputStreamWriter(new DigestOutputStream(OutputStream.nullOutputStream(),digest),StandardCharsets.UTF_8));
            output.write('[');
        }
        void add(Object value)throws IOException {if(!first)output.write(',');first=false;CanonicalJson.writeTo(value,output);}
        ContentDigest finish()throws IOException {
            if(finished)throw new IllegalStateException("Digest already finished");
            finished=true;output.write(']');output.close();return new ContentDigest("sha256:"+HexFormat.of().formatHex(digest.digest()));
        }
        public void close()throws IOException {output.close();}
    }
}
