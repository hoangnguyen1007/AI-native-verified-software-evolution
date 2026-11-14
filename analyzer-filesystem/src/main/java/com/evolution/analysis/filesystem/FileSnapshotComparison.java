package com.evolution.analysis.filesystem;

import com.evolution.analysis.evolution.SnapshotComparison;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
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
        var index=new SqliteEvolutionIndex(scratchDirectory,cancelled);
        try {
            return new Session(SnapshotComparison.compare(request,graphs::openEvolution,reports,reportReader,index,inputPageSize,cancelled),index);
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
