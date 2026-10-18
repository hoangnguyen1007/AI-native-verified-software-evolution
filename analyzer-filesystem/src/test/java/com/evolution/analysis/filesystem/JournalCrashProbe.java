package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Trusted process used only to interrupt a journal between durable publication and SQL commit. */
public final class JournalCrashProbe {
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]);var cas=new StreamingContentStore(root.resolve("cas"));var commits=new AtomicInteger();
        try(var store=new SqliteInventoryStore(root.resolve("metadata"),cas,()->{if(commits.incrementAndGet()==3)Runtime.getRuntime().halt(71);} )){
            var job=store.create("crash",ContentDigest.sha256Utf8("request"),Optional.empty());store.transition("crash",job.revision(),JobJournal.State.RUNNING,"",Optional.empty());
            var handle=cas.put(new ByteArrayInputStream("captured-before-crash".getBytes()),()->false);
            var entry=new InventoryEntry(LogicalPath.utf8("retained.py"),InventoryEntry.Kind.FILE,"100644",InventoryEntry.Population.SOURCE,InventoryEntry.Disposition.ACQUIRED,Optional.of(handle),Optional.empty(),Optional.empty(),List.of(),Optional.empty());
            store.entries("crash",List.of(new SqliteInventoryStore.Observation(entry,"")));
        }throw new AssertionError("Crash boundary was not reached");
    }
}
