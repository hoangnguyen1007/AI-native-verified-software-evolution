package com.evolution.analysis.execution.output;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.execution.ExecutionGraph;
import java.io.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Captures output objects into CAS as opaque bytes. Never resolves a guest name against a host directory,
 * follows a link, loads a class/archive, or promotes a producer's semantic assertion.
 * The caller must join these per-object dispositions to its complete, sealed output census.
 */
public final class OutputImporter {
    public enum Kind { FILE, DIRECTORY, SYMLINK, HARDLINK, FIFO, SOCKET, DEVICE, UNKNOWN }
    public enum Disposition { CAPTURED, REJECTED, PENDING }
    public record Entry(LogicalPath path,Kind kind,Optional<ContentHandle> expected,String role,ContentDigest context,ContentDigest origin) {
        public Entry {
            Objects.requireNonNull(path);Objects.requireNonNull(kind);Objects.requireNonNull(expected);ExecutionGraph.text(role);Objects.requireNonNull(context);Objects.requireNonNull(origin);
            if((kind==Kind.FILE)!=expected.isPresent())throw new IllegalArgumentException("INVALID_OUTPUT_ENTRY");
        }
    }
    public record Result(Entry declared,Disposition disposition,Optional<ContentHandle> captured,String reason) {
        public Result {Objects.requireNonNull(declared);Objects.requireNonNull(disposition);Objects.requireNonNull(captured);Objects.requireNonNull(reason);}
    }
    @FunctionalInterface public interface Source {InputStream open(Entry entry)throws IOException;}
    private final RepositoryContentStore content;
    public OutputImporter(RepositoryContentStore content){this.content=Objects.requireNonNull(content);}
    public Result capture(Entry entry,Source source,ContentDigest expectedContext,long maxBytes,BooleanSupplier cancelled)throws IOException {
        Objects.requireNonNull(entry);Objects.requireNonNull(source);Objects.requireNonNull(expectedContext);Objects.requireNonNull(cancelled);
        if(maxBytes<0)throw new IllegalArgumentException("INVALID_OUTPUT_WORKER");
        if(!entry.context().equals(expectedContext))return result(entry,Disposition.REJECTED,"OUTPUT_CONTEXT_MISMATCH");
        if(cancelled.getAsBoolean())return result(entry,Disposition.PENDING,"OUTPUT_CANCELLED");
        if(entry.kind()==Kind.DIRECTORY)return result(entry,Disposition.PENDING,"DIRECTORY_CENSUS_REQUIRED");
        if(entry.kind()!=Kind.FILE)return result(entry,Disposition.REJECTED,"OUTPUT_SPECIAL_ENTRY");
        if(entry.expected().orElseThrow().size()>maxBytes)return result(entry,Disposition.PENDING,"OUTPUT_BYTES_WORKER_REQUIRED");
        try(var raw=source.open(entry)){
            var bounded=new FilterInputStream(raw){
                long remaining=maxBytes;
                @Override public int read()throws IOException {
                    int b=in.read();if(b!=-1){if(remaining==0)throw new WorkerRequired();remaining--;}return b;
                }
                @Override public int read(byte[] b,int offset,int length)throws IOException {
                    Objects.checkFromIndexSize(offset,length,b.length);if(length==0)return 0;
                    if(remaining==0)return read()==-1?-1:0;
                    int count=in.read(b,offset,(int)Math.min(remaining,length));if(count>0)remaining-=count;return count;
                }
            };
            ContentHandle captured=content.put(bounded,cancelled);
            boolean exact=captured.equals(entry.expected().orElseThrow());
            return new Result(entry,exact?Disposition.CAPTURED:Disposition.REJECTED,Optional.of(captured),exact?"":"OUTPUT_CONTENT_MISMATCH");
        }catch(WorkerRequired exhausted){return result(entry,Disposition.PENDING,"OUTPUT_BYTES_WORKER_REQUIRED");}
        catch(CancellationException cancelledWrite){return result(entry,Disposition.PENDING,"OUTPUT_CANCELLED");}
        catch(IOException unavailable){return result(entry,Disposition.PENDING,"OUTPUT_UNAVAILABLE");}
    }
    private static final class WorkerRequired extends IOException {}
    private static Result result(Entry entry,Disposition disposition,String reason){return new Result(entry,disposition,Optional.empty(),reason);}
}
