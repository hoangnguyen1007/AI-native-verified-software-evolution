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

/** Single-writer, CAS-backed admission ledger. A reservation is durable before dispatch returns.
 * Task leasing/VM launch follows that commit; recovery must retain or reconcile the reserved ticket.
 */
public final class DurableAdmissionQueue implements AutoCloseable {
    private static final String ID="execution-admission";
    private final SqliteInventoryStore journal;
    private final StreamingContentStore content;
    private final ContentDigest policy;
    private final int maxBytes;
    public DurableAdmissionQueue(Path root,StreamingContentStore content,ResourceBudget.Resources capacity,int bypasses,int maxBytes)throws IOException {
        if(maxBytes<1)throw new IllegalArgumentException("INVALID_ADMISSION_WORKER");
        this.content=Objects.requireNonNull(content);this.maxBytes=maxBytes;
        var initial=AdmissionQueue.initial(capacity,bypasses);policy=initial.policy();
        journal=new SqliteInventoryStore(root,content);
        try{decode(journal.create(ID,policy,Optional.of(store(initial))));}
        catch(IOException|RuntimeException failed){try{journal.close();}catch(IOException close){failed.addSuppressed(close);}throw failed;}
    }
    private ContentHandle store(AdmissionQueue.State state)throws IOException {
        if(!CanonicalJson.fitsStructure(state,48,maxBytes))throw new IOException("ADMISSION_QUEUE_WORKER_REQUIRED");
        byte[] bytes=CanonicalJson.write(state).getBytes(StandardCharsets.UTF_8);
        if(bytes.length>maxBytes)throw new IOException("ADMISSION_QUEUE_WORKER_REQUIRED");
        return content.put(new ByteArrayInputStream(bytes),()->false);
    }
    private AdmissionQueue.State decode(JobJournal.Record record)throws IOException {
        var handle=record.payload().orElseThrow(()->new IOException("ADMISSION_STATE_MISSING"));
        if(handle.size()>maxBytes)throw new IOException("ADMISSION_QUEUE_WORKER_REQUIRED");
        if(!content.verify(handle))throw new IOException("ADMISSION_STATE_CORRUPT");
        try(var input=content.open(handle)){
            var state=ExecutionCodec.read(input,AdmissionQueue.State.class,new GraphJson.Limits(maxBytes,48,maxBytes));
            if(state.revision()!=record.revision()||!state.policy().equals(policy))throw new IOException("ADMISSION_STATE_CORRUPT");return state;
        }catch(IllegalArgumentException|ArithmeticException invalid){throw new IOException("ADMISSION_STORAGE_RECONCILIATION_REQUIRED".equals(invalid.getMessage())?"ADMISSION_STORAGE_RECONCILIATION_REQUIRED":"ADMISSION_STATE_CORRUPT",invalid);}
    }
    public synchronized AdmissionQueue.State get()throws IOException{return decode(journal.get(ID).orElseThrow(()->new IOException("ADMISSION_STATE_MISSING")));}
    private AdmissionQueue.State expected(long revision)throws IOException {
        var current=get();if(current.revision()!=revision)throw new IOException("ADMISSION_TRANSITION_CONFLICT");return current;
    }
    private AdmissionQueue.State commit(AdmissionQueue.State previous,AdmissionQueue.State next)throws IOException {
        if(previous.equals(next))return previous;
        if(next.revision()!=Math.addExact(previous.revision(),1)||!next.policy().equals(policy))throw new IOException("ADMISSION_TRANSITION_REJECTED");
        var payload=store(next);journal.transition(ID,previous.revision(),JobJournal.State.RUNNING,"ADMISSION_COMMIT",Optional.of(payload));return next;
    }
    public synchronized AdmissionQueue.State submit(long revision,AdmissionQueue.Request request)throws IOException {
        var previous=expected(revision);
        try{return commit(previous,AdmissionQueue.submit(previous,request));}catch(IllegalArgumentException|ArithmeticException invalid){throw new IOException("ADMISSION_COMMAND_REJECTED",invalid);}
    }
    public synchronized AdmissionQueue.Admission dispatch(long revision)throws IOException {
        var previous=expected(revision);
        try{var result=AdmissionQueue.dispatch(previous);commit(previous,result.state());return result;}catch(IllegalArgumentException|ArithmeticException invalid){throw new IOException("ADMISSION_COMMAND_REJECTED",invalid);}
    }
    public synchronized AdmissionQueue.State release(long revision,AdmissionQueue.Ticket ticket,Optional<ContentDigest> proof)throws IOException {
        var previous=expected(revision);
        try{return commit(previous,AdmissionQueue.release(previous,ticket,proof));}catch(IllegalArgumentException|ArithmeticException invalid){throw new IOException("ADMISSION_COMMAND_REJECTED",invalid);}
    }
    public synchronized AdmissionQueue.State cancelPending(long revision,AdmissionQueue.Ticket ticket)throws IOException {
        var previous=expected(revision);
        try{return commit(previous,AdmissionQueue.cancelPending(previous,ticket));}catch(IllegalArgumentException|ArithmeticException invalid){throw new IOException("ADMISSION_COMMAND_REJECTED",invalid);}
    }
    /** Trusted controller boundary only: the launch intent must prove no backend launch was possible. */
    public synchronized AdmissionQueue.State releaseUnlaunched(long revision,AdmissionQueue.Ticket ticket,Optional<ContentDigest> proof)throws IOException {
        var previous=expected(revision);
        try{return commit(previous,AdmissionQueue.releaseUnlaunched(previous,ticket,proof));}catch(IllegalArgumentException|ArithmeticException invalid){throw new IOException("ADMISSION_COMMAND_REJECTED",invalid);}
    }
    @Override public void close()throws IOException{journal.close();}
}
