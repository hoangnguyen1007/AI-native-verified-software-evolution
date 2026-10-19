package com.evolution.backend.analysis;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.filesystem.SqliteInventoryStore;
import com.evolution.analysis.filesystem.StreamingContentStore;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.readiness.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

final class ReadinessReports implements AutoCloseable {
    private final Path directory;private final RepositoryContentStore content;private final SqliteInventoryStore journal;
    ReadinessReports(Path directory,StreamingContentStore content)throws IOException{this.directory=directory;this.content=content;journal=new SqliteInventoryStore(directory,content);}
    synchronized Optional<ReadinessPlan> get(ContentDigest request)throws IOException{
        var record=journal.byRequest(request);if(record.isEmpty()||record.orElseThrow().state()!=JobJournal.State.COMPLETE)return Optional.empty();
        var handle=record.orElseThrow().payload().orElseThrow();if(handle.size()>32_000_000)throw new IOException("READINESS_REPORT_WORKER_REQUIRED");
        if(!content.verify(handle))throw new IOException("READINESS_REPORT_CORRUPT");
        try(var input=content.open(handle)){
            var json=GraphJson.read(new String(input.readAllBytes(),StandardCharsets.UTF_8),new GraphJson.Limits(32_000_000,128,2_000_000));
            var plan=CanonicalRecordReader.read(json,ReadinessPlan.class);
            if(!plan.identity().equals(handle.digest())||!content.verify(plan.ownership().ledger()))throw new IOException("READINESS_REPORT_CORRUPT");
            return Optional.of(plan);
        }catch(IllegalArgumentException invalid){throw new IOException("READINESS_REPORT_CORRUPT",invalid);}
    }
    synchronized void save(ContentDigest request,ReadinessPlan plan,BooleanSupplier cancelled)throws IOException{
        Path temporary=Files.createTempFile(directory,"readiness-",".tmp");ContentHandle handle;
        try{
            try(var writer=Files.newBufferedWriter(temporary,StandardCharsets.UTF_8)){CanonicalJson.writeTo(plan,writer);}
            try(var input=Files.newInputStream(temporary)){handle=content.put(input,cancelled);}
        }finally{Files.deleteIfExists(temporary);}
        if(!handle.digest().equals(plan.identity()))throw new IOException("READINESS_IDENTITY_MISMATCH");
        if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("READINESS_CANCELLED");
        String id="readiness-"+request.value().substring(7);var prior=journal.get(id).orElseGet(()->{try{return journal.create(id,request,Optional.empty());}catch(IOException failure){throw new UncheckedIOException(failure);}});
        journal.transition(id,prior.revision(),JobJournal.State.COMPLETE,"PASSIVE_PLAN",Optional.of(handle));
    }
    @Override public void close()throws IOException{journal.close();}
}
