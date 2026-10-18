package com.evolution.backend.analysis;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.filesystem.*;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Durable v1 job receipts under a versioned storage envelope. Only active worker state stays in RAM. */
public final class AnalysisJobDiskStore implements AutoCloseable {
    public record Saved(String schema,String retry,AnalysisJobs.View view,List<CapabilityGapRecord> gaps,List<AnalysisJobs.InputProblem> inputProblems){
        public Saved{if(!schema.equals("analysis-job-storage:m8.4-v2"))throw new IllegalArgumentException("Unknown job storage schema");gaps=List.copyOf(gaps);inputProblems=List.copyOf(inputProblems);}
    }
    public record Envelope(String schema,String retry,AnalysisJobs.Setup setup,AnalysisJobs.View view,ContentHandle gaps,ContentHandle inputProblems){
        public Envelope{if(!schema.equals("analysis-job-storage:m8.4-v2")||!setup.id().equals(view.setup()))throw new IllegalArgumentException("Unknown or mismatched job storage schema");}
    }
    public record GapLedger(List<CapabilityGapRecord> gaps){}
    public record InputLedger(List<AnalysisJobs.InputProblem> inputProblems){}
    private final StreamingContentStore content;private final SqliteInventoryStore journal;
    private final String namespace;
    public AnalysisJobDiskStore(Path root)throws IOException{namespace=ContentDigest.sha256(root.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8)).value().substring(7);content=new StreamingContentStore(root.resolve("content"));journal=new SqliteInventoryStore(root.resolve("metadata"),content);}
    public String namespace(){return namespace;}
    public synchronized void save(String retry,AnalysisJobs.Setup setup,AnalysisJobs.View view,List<CapabilityGapRecord> gaps,List<AnalysisJobs.InputProblem> problems)throws IOException{
        var gapHandle=write(new GapLedger(gaps));var inputHandle=write(new InputLedger(problems));
        var envelope=new Envelope("analysis-job-storage:m8.4-v2",retry,setup,view,gapHandle,inputHandle);var handle=write(envelope);var existing=journal.get(view.id());
        if(existing.isEmpty())existing=Optional.of(journal.create(view.id(),ContentDigest.sha256Utf8(retry),Optional.of(handle)));
        String reason=(view.stop().filter(s->s==AnalysisJobs.Stop.SERVICE_CLOSED).isPresent()?"RECOVERABLE":view.state().name())+"."+view.setup();
        journal.transition(view.id(),existing.orElseThrow().revision(),JobJournal.State.RUNNING,reason,Optional.of(handle));
    }
    private ContentHandle write(Object value)throws IOException{
        byte[] bytes=CanonicalJson.write(value).getBytes(StandardCharsets.UTF_8);if(bytes.length>32_000_000)throw new IOException("JOB_RECEIPT_WORKER_REQUIRED");return content.put(new ByteArrayInputStream(bytes),()->false);
    }
    public synchronized Optional<Saved> get(UUID id)throws IOException{return get(id,false);}
    public synchronized AnalysisJobs.Setup intent(UUID id)throws IOException{return read(journal.get(id.toString()).orElseThrow(()->new IOException("JOB_NOT_FOUND")).payload().orElseThrow(),Envelope.class).setup();}
    public synchronized Optional<Saved> get(UUID id,boolean includeLedgers)throws IOException{
        var record=journal.get(id.toString());if(record.isEmpty()||record.orElseThrow().payload().isEmpty())return Optional.empty();return Optional.of(load(record.orElseThrow().payload().orElseThrow(),includeLedgers));
    }
    private <T>T read(ContentHandle handle,Class<T> type)throws IOException{
        if(handle.size()>32_000_000||!content.verify(handle))throw new IOException("JOB_RECEIPT_CORRUPT");
        try(var input=content.open(handle)){
            byte[] bytes=input.readAllBytes();var json=GraphJson.read(new String(bytes,StandardCharsets.UTF_8),new GraphJson.Limits(32_000_000,128,2_000_000));var value=CanonicalRecordReader.read(json,type);
            if(!CanonicalJson.sha256(value).equals(handle.digest()))throw new IOException("JOB_RECEIPT_NONCANONICAL");return value;
        }catch(IllegalArgumentException invalid){throw new IOException("JOB_RECEIPT_CORRUPT",invalid);}
    }
    private Saved load(ContentHandle handle)throws IOException{return load(handle,false);}
    private Saved load(ContentHandle handle,boolean includeLedgers)throws IOException{
        var envelope=read(handle,Envelope.class);
        return new Saved(envelope.schema(),envelope.retry(),envelope.view(),includeLedgers?read(envelope.gaps(),GapLedger.class).gaps():List.of(),includeLedgers?read(envelope.inputProblems(),InputLedger.class).inputProblems():List.of());
    }
    public record Page(List<Saved> items,long total,Optional<Long> continuation,long highWaterSequence){public Page{items=List.copyOf(items);}}
    public synchronized Page history(long afterSequence,int size)throws IOException{
        return history(afterSequence,size,0);
    }
    public synchronized Page history(long afterSequence,int size,long highWater)throws IOException{
        var page=journal.historyPage(afterSequence,size,highWater);var result=new ArrayList<Saved>();for(var entry:page.entries())if(entry.record().payload().isPresent())result.add(load(entry.record().payload().orElseThrow()));return new Page(result,page.total(),page.continuation(),page.highWaterSequence());
    }
    public synchronized Optional<Saved> retry(String retry)throws IOException{var row=journal.byRequest(ContentDigest.sha256Utf8(retry));return row.isEmpty()||row.orElseThrow().payload().isEmpty()?Optional.empty():Optional.of(load(row.orElseThrow().payload().orElseThrow()));}
    public synchronized List<Saved> queued(String setup)throws IOException{
        var result=new ArrayList<Saved>();for(var row:journal.waiting("QUEUED."+setup,64))if(row.payload().isPresent())result.add(load(row.payload().orElseThrow()));return List.copyOf(result);
    }
    public synchronized void recover(AnalysisJobs.Setup expected)throws IOException{
        String setup=expected.id();
        for(String state:List.of("RUNNING","CANCELLING","RECOVERABLE"))for(var row:journal.waiting(state+"."+setup,64)){
            var saved=load(row.payload().orElseThrow());var v=saved.view();boolean cancelled=v.stop().filter(s->s==AnalysisJobs.Stop.USER_CANCELLED).isPresent();
            var next=new AnalysisJobs.View(v.id(),v.setup(),cancelled?AnalysisJobs.State.CANCELLED:AnalysisJobs.State.QUEUED,cancelled?AnalysisJobs.Stage.FINISHED:AnalysisJobs.Stage.QUEUED,v.revision()+1,v.submittedAt(),Optional.empty(),cancelled?Optional.of(java.time.Instant.now()):Optional.empty(),v.elapsedMillis(),cancelled?v.stop():Optional.empty(),Optional.empty(),List.of(new AnalysisJobs.Event(AnalysisJobs.Stage.QUEUED,java.time.Instant.now())),Optional.empty());
            var original=intent(UUID.fromString(v.id()));if(!original.equals(expected))next=new AnalysisJobs.View(v.id(),v.setup(),AnalysisJobs.State.FAILED,AnalysisJobs.Stage.FINISHED,v.revision()+1,v.submittedAt(),v.startedAt(),Optional.of(java.time.Instant.now()),v.elapsedMillis(),Optional.empty(),Optional.of("SETUP_CONFIGURATION_CHANGED"),v.events(),v.result());
            save(saved.retry(),original,next,saved.gaps(),saved.inputProblems());
        }
    }
    @Override public void close()throws IOException{journal.close();}
}
