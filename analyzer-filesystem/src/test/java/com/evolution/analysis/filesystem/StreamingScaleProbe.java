package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Trusted generated checkpoint, intentionally run in a separate small-heap JVM. No checked-in corpus. */
public final class StreamingScaleProbe {
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]);int count=Integer.parseInt(args[1]);var cas=new StreamingContentStore(root.resolve("cas"));
        long size=160_000_001L;
        try(var input=new InputStream(){long left=size;public int read(){return left--<=0?-1:0;}@Override public int read(byte[] b,int off,int len){if(left==0)return -1;int n=(int)Math.min(left,len);Arrays.fill(b,off,off+n,(byte)0);left-=n;return n;}}){
            var giant=cas.put(input,()->false);if(giant.size()!=size||!cas.verify(giant))throw new AssertionError("Giant content mismatch");
        }
        var empty=cas.put(InputStream.nullInputStream(),()->false);var repo=RepositoryIdentity.fromCanonicalCoordinate("urn:test:scale");var selection=ContentDigest.sha256Utf8("generated-scale-v1");
        InventoryManifest manifest;
        try(var store=new SqliteInventoryStore(root.resolve("metadata"),cas)){
            var job=store.create("scale",selection,Optional.empty());job=store.transition("scale",job.revision(),JobJournal.State.RUNNING,"",Optional.empty());
            var batch=new ArrayList<SqliteInventoryStore.Observation>(256);
            for(int i=0;i<count;i++){
                String name="entry/"+String.format(Locale.ROOT,"%08d",i)+".unknown";
                var entry=new InventoryEntry(LogicalPath.utf8(name),InventoryEntry.Kind.FILE,"100644",InventoryEntry.Population.UNKNOWN,InventoryEntry.Disposition.ACQUIRED,Optional.of(empty),Optional.empty(),Optional.empty(),List.of(),Optional.empty());
                batch.add(new SqliteInventoryStore.Observation(entry,""));if(batch.size()==256){store.entries("scale",batch);batch.clear();}
            }
            if(!batch.isEmpty())store.entries("scale",batch);job=store.transition("scale",job.revision(),JobJournal.State.RUNNING,"FRONTIER_CLOSED",Optional.empty());
            manifest=store.seal("scale",repo,selection);if(manifest.files()!=count||manifest.bytes()!=0)throw new AssertionError("Inventory denominator mismatch");
        }
        try(var store=new SqliteInventoryStore(root.resolve("metadata"),cas)){
            if(!manifest.equals(store.manifest("scale").orElseThrow()))throw new AssertionError("Restart mismatch");
            long actual=0;Optional<String> cursor=Optional.empty();do{var page=store.page("scale",1000,cursor);actual+=page.entries().size();cursor=page.continuation();}while(cursor.isPresent());if(actual!=count)throw new AssertionError("Paging mismatch");
        }
        System.out.println("PASS heap="+Runtime.getRuntime().maxMemory()+" streamedBytes="+size+" entries="+count+" inventory="+manifest.inventory().value());
    }
}
