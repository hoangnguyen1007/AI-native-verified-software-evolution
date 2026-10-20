package com.evolution.analysis.execution.controller;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.execution.*;
import com.evolution.analysis.filesystem.*;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Exclusive controller ownership plus immutable attempt intent before any reservation or driver call. */
public final class DurableAttemptStore implements AutoCloseable {
    private final SqliteInventoryStore journal;
    private final StreamingContentStore content;
    public DurableAttemptStore(Path root,StreamingContentStore content)throws IOException{this.content=Objects.requireNonNull(content);journal=new SqliteInventoryStore(root,content);}
    private static String id(String attempt){return "attempt-"+com.evolution.analysis.contract.common.ContentDigest.sha256Utf8(ExecutionGraph.text(attempt)).value().substring(7);}
    private ContentHandle put(ExecutionAttempt a)throws IOException{
        byte[] bytes=CanonicalJson.write(a).getBytes(StandardCharsets.UTF_8);
        if(bytes.length>64000)throw new IOException("ATTEMPT_RECORD_WORKER_REQUIRED");
        return content.put(new ByteArrayInputStream(bytes),()->false);
    }
    private ExecutionAttempt decode(JobJournal.Record row)throws IOException{
        var handle=row.payload().orElseThrow(()->new IOException("ATTEMPT_RECORD_MISSING"));
        if(handle.size()>64000||!content.verify(handle))throw new IOException("ATTEMPT_RECORD_CORRUPT");
        try(var input=content.open(handle)){
            var a=ExecutionCodec.read(input,ExecutionAttempt.class,new GraphJson.Limits(64000,32,4096));
            if(a.revision()!=row.revision()||!id(a.launch().attempt()).equals(row.id()))throw new IOException("ATTEMPT_RECORD_CORRUPT");return a;
        }catch(IllegalArgumentException|ArithmeticException invalid){throw new IOException("ATTEMPT_RECORD_CORRUPT",invalid);}
    }
    public synchronized ExecutionAttempt create(ExecutionAttempt prepared)throws IOException{
        if(prepared.phase()!=ExecutionAttempt.Phase.PREPARED||prepared.revision()!=0)throw new IOException("ATTEMPT_PREPARE_REQUIRED");
        return decode(journal.create(id(prepared.launch().attempt()),CanonicalJson.sha256(prepared),Optional.of(put(prepared))));
    }
    public synchronized ExecutionAttempt get(String attempt)throws IOException{return decode(journal.get(id(attempt)).orElseThrow(()->new IOException("ATTEMPT_NOT_FOUND")));}
    public synchronized Optional<ExecutionAttempt> find(String attempt)throws IOException{
        var row=journal.get(id(attempt));return row.isEmpty()?Optional.empty():Optional.of(decode(row.orElseThrow()));
    }
    public synchronized ExecutionAttempt commit(ExecutionAttempt previous,ExecutionAttempt next)throws IOException{
        if(!get(previous.launch().attempt()).equals(previous))throw new IOException("ATTEMPT_REVISION_CONFLICT");
        try{previous.verifySuccessor(next);}catch(IllegalArgumentException|ArithmeticException invalid){throw new IOException("ATTEMPT_TRANSITION_REJECTED",invalid);}
        journal.transition(id(previous.launch().attempt()),previous.revision(),next.phase()==ExecutionAttempt.Phase.RELEASED?JobJournal.State.COMPLETE:JobJournal.State.RUNNING,"ATTEMPT_PROTOCOL",Optional.of(put(next)));
        return next;
    }
    /** Captures a bounded recovery frontier. Only the exclusive controller may update these rows. */
    public JobJournal.HistoryPage page(long cursor,int size,long highWater)throws IOException{return journal.historyPage(cursor,size,highWater);}
    public ExecutionAttempt record(JobJournal.Record row)throws IOException{return decode(row);}
    @Override public void close()throws IOException{journal.close();}
}
