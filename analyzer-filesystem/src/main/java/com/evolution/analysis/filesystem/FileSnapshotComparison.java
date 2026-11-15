package com.evolution.analysis.filesystem;

import com.evolution.analysis.evolution.EvolutionSnapshot;
import com.evolution.analysis.evolution.SnapshotComparison;
import com.evolution.analysis.acquisition.streaming.ContentHandle;
import com.evolution.analysis.contract.common.ContentDigest;
import java.io.IOException;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Internal, external-memory comparison over existing authenticated graph publications. */
public final class FileSnapshotComparison {
    private FileSnapshotComparison() {}

    public static Session compare(PartitionedGraphStore graphs,Path scratchDirectory,SnapshotComparison.Request request,
                                  int inputPageSize,BooleanSupplier cancelled)throws IOException {
        return compare(graphs,scratchDirectory,request,List.of(),(graph,report,stop)->SnapshotComparison.ReportStatus.UNAVAILABLE,
                inputPageSize,cancelled);
    }

    public static Session compare(PartitionedGraphStore graphs,Path scratchDirectory,SnapshotComparison.Request request,
                                  Collection<SnapshotComparison.ReportRef> reports,SnapshotComparison.ReportReader reportReader,
                                  int inputPageSize,BooleanSupplier cancelled)throws IOException {
        return compare(graphs,scratchDirectory,request,reports,reportReader,List.of(),(b,s,o,c)->SnapshotComparison.ReportStatus.UNAVAILABLE,
                inputPageSize,cancelled);
    }

    public static Session compare(PartitionedGraphStore graphs,Path scratchDirectory,SnapshotComparison.Request request,
                                  Collection<SnapshotComparison.ReportRef> reports,SnapshotComparison.ReportReader reportReader,
                                  Collection<SnapshotComparison.BridgeRef> bridges,SnapshotComparison.BridgeReader bridgeReader,
                                  int inputPageSize,BooleanSupplier cancelled)throws IOException {
        return compare(graphs,scratchDirectory,request,reports,reportReader,bridges,bridgeReader,Optional.empty(),
                (proof,stop)->Optional.empty(),inputPageSize,cancelled);
    }

    public static Session compare(PartitionedGraphStore graphs,Path scratchDirectory,SnapshotComparison.Request request,
                                  Collection<SnapshotComparison.ReportRef> reports,SnapshotComparison.ReportReader reportReader,
                                  Collection<SnapshotComparison.BridgeRef> bridges,SnapshotComparison.BridgeReader bridgeReader,
                                  Optional<SnapshotComparison.History> history,SnapshotComparison.HistoryReader historyReader,
                                  int inputPageSize,BooleanSupplier cancelled)throws IOException {
        return compare(graphs::openEvolution,scratchDirectory,request,reports,reportReader,bridges,bridgeReader,history,
                historyReader,inputPageSize,cancelled);
    }

    public static Session compare(EvolutionSnapshot.Reader graphs,Path scratchDirectory,SnapshotComparison.Request request,
                                  Collection<SnapshotComparison.ReportRef> reports,SnapshotComparison.ReportReader reportReader,
                                  Collection<SnapshotComparison.BridgeRef> bridges,SnapshotComparison.BridgeReader bridgeReader,
                                  Optional<SnapshotComparison.History> history,SnapshotComparison.HistoryReader historyReader,
                                  int inputPageSize,BooleanSupplier cancelled)throws IOException {
        var index=new SqliteEvolutionIndex(scratchDirectory,cancelled);
        return run(index,()->SnapshotComparison.compare(request,graphs,reports,reportReader,bridges,bridgeReader,
                history,historyReader,index,inputPageSize,cancelled));
    }

    @FunctionalInterface public interface CheckpointSink { void save(ContentHandle handle) throws IOException; }
    @FunctionalInterface private interface Compare { SnapshotComparison.Result run() throws IOException; }

    /** Checkpoint bytes are a committed private DB image with an authenticated content handle in the job journal. */
    public static Session withCheckpoint(EvolutionSnapshot.Reader graphs,Path scratchDirectory,
                                         SnapshotComparison.Request request,Collection<SnapshotComparison.ReportRef> reports,
                                         SnapshotComparison.ReportReader reportReader,Collection<SnapshotComparison.BridgeRef> bridges,
                                         SnapshotComparison.BridgeReader bridgeReader,Optional<SnapshotComparison.History> history,
                                         SnapshotComparison.HistoryReader historyReader,StreamingContentStore content,
                                         Optional<ContentHandle> checkpoint,CheckpointSink sink,int pageSize,
                                         BooleanSupplier cancelled)throws IOException {
        var db=Files.createTempFile(StreamingContentStore.directory(scratchDirectory),"evolution-",".sqlite");
        try {
            if (checkpoint.isPresent()) {
                var handle=checkpoint.orElseThrow();
                restoreCheckpoint(content,handle,db,cancelled);
            }
            var index=new SqliteEvolutionIndex(db,cancelled,checkpoint.isPresent(),file -> {
                try (var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {
                    sink.save(content.put(input,cancelled));
                }
            });
            return run(index,()->checkpoint.isPresent()
                    ?SnapshotComparison.resumeComparison(request,graphs,reports,reportReader,bridges,bridgeReader,history,historyReader,index,pageSize,cancelled)
                    :SnapshotComparison.compare(request,graphs,reports,reportReader,bridges,bridgeReader,history,historyReader,index,pageSize,cancelled));
        } catch (IOException|RuntimeException|Error failure) {
            try { Files.deleteIfExists(db); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static void restoreCheckpoint(StreamingContentStore content,ContentHandle handle,Path db,
                                          BooleanSupplier cancelled)throws IOException {
        try (var source=content.open(handle);
             var destination=Files.newOutputStream(db,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)) {
            var hash=MessageDigest.getInstance("SHA-256");
            var input=new DigestInputStream(source,hash);
            byte[] buffer=new byte[65_536];
            long bytes=0;
            int size;
            while ((size=input.read(buffer))!=-1) {
                EvolutionSnapshot.check(cancelled);
                bytes+=size;
                destination.write(buffer,0,size);
            }
            if (bytes!=handle.size() || !new ContentDigest("sha256:"+HexFormat.of().formatHex(hash.digest()))
                    .equals(handle.digest())) throw new IOException("EVOLUTION_CHECKPOINT_CORRUPT");
        } catch (IOException unavailable) { throw new IOException("EVOLUTION_CHECKPOINT_UNAVAILABLE",unavailable); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static Session run(SqliteEvolutionIndex index,Compare comparison)throws IOException {
        try {
            return new Session(comparison.run(),index);
        } catch(IOException|RuntimeException|Error failure) {
            try{index.close();}catch(IOException close){failure.addSuppressed(close);}throw failure;
        }
    }

    /** A result is queryable until its private scratch session closes. No public publication is created. */
    public static final class Session implements AutoCloseable {
        private final SnapshotComparison.Result result;
        private final SqliteEvolutionIndex index;
        private Session(SnapshotComparison.Result result,SqliteEvolutionIndex index){this.result=result;this.index=index;}
        public SnapshotComparison.Result result(){return result;}
        @Override public void close()throws IOException {index.close();}
    }
}
