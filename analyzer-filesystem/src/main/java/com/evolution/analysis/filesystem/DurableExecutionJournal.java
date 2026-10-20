package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.execution.*;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Reuses the fsynced journal/CAS publication protocol; execution transitions are checked separately. */
public final class DurableExecutionJournal implements ExecutionJournal,AutoCloseable {
    private final SqliteInventoryStore journal;
    private final StreamingContentStore content;
    public DurableExecutionJournal(Path root,StreamingContentStore content)throws IOException {this.content=Objects.requireNonNull(content);this.journal=new SqliteInventoryStore(root,content);}
    private static String id(ContentDigest execution,ContentDigest task){return "execution-"+execution.value().substring(7)+"-"+task.value().substring(7);}
    private ContentHandle store(ExecutionState state)throws IOException {
        return content.put(new ByteArrayInputStream(CanonicalJson.write(state).getBytes(StandardCharsets.UTF_8)),()->false);
    }
    @Override public synchronized ExecutionState create(ContentDigest execution,ContentDigest task)throws IOException {
        var initial=ExecutionState.initial(task);var payload=store(initial);var record=journal.create(id(execution,task),execution,Optional.of(payload));return decode(task,record);
    }
    @Override public synchronized Optional<ExecutionState> get(ContentDigest execution,ContentDigest task)throws IOException {
        var record=journal.get(id(execution,task));return record.isEmpty()?Optional.empty():Optional.of(decode(task,record.orElseThrow()));
    }
    private ExecutionState decode(ContentDigest task,JobJournal.Record record)throws IOException {
        var payload=record.payload().orElseThrow(()->new IOException("EXECUTION_STATE_MISSING"));
        if(payload.size()>64_000||!content.verify(payload))throw new IOException("EXECUTION_STATE_CORRUPT");
        try(var input=content.open(payload)){
            var state=ExecutionCodec.read(input,ExecutionState.class,new GraphJson.Limits(64_000,24,512));
            if(!state.task().equals(task)||state.revision()!=record.revision())throw new IOException("EXECUTION_STATE_CORRUPT");return state;
        }catch(IllegalArgumentException invalid){throw new IOException("EXECUTION_STATE_CORRUPT",invalid);}
    }
    @Override public synchronized ExecutionState compareAndSet(ContentDigest execution,ExecutionState expected,ExecutionState next)throws IOException {
        var current=get(execution,expected.task()).orElseThrow(()->new IOException("EXECUTION_TASK_MISSING"));
        if(!current.equals(expected))throw new IOException("EXECUTION_TRANSITION_CONFLICT");
        try{current.verifySuccessor(next);}catch(IllegalArgumentException invalid){throw new IOException("EXECUTION_TRANSITION_REJECTED",invalid);}
        if(next.result().isPresent()&&!content.verify(next.result().orElseThrow()))throw new IOException("EXECUTION_PUBLICATION_UNAVAILABLE");
        // This operational publication does not promote its content to semantic truth.
        var payload=store(next);
        journal.transition(id(execution,next.task()),expected.revision(),JobJournal.State.RUNNING,next.reason(),Optional.of(payload));
        return next;
    }
    @Override public void close()throws IOException{journal.close();}
}
