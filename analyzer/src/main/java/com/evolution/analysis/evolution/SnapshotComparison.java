package com.evolution.analysis.evolution;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.graph.PartitionedGraphReader.Page;
import java.io.IOException;
import java.util.*;
import java.util.function.BooleanSupplier;
import static com.evolution.analysis.evolution.EvolutionRows.*;

/** Internal M11 comparison service; its rows are not the proposed public API. */
public final class SnapshotComparison {
    private SnapshotComparison() {}
    public static final String VERSION="architecture.evolution.internal:m11.2-v1";
    public enum Mode { STRICT, OBSERVATION }
    public enum Status { COMPLETE, PARTIAL, WITHHELD }
    public enum Section { CONTEXTS, COMPATIBILITY, CORRESPONDENCE, EVENTS, GAPS, INPUTS }
    public record Selection(Set<String> sourceSets,Set<String> nodeKinds,Set<String> relationshipKinds) {
        public Selection {
            sourceSets=Set.copyOf(sourceSets);nodeKinds=Set.copyOf(nodeKinds);relationshipKinds=Set.copyOf(relationshipKinds);
            if(nodeKinds.isEmpty()&&relationshipKinds.isEmpty())throw new IllegalArgumentException("Empty comparison selection");
        }
    }
    public record Request(RepositoryIdentity repository,ContentDigest left,ContentDigest right,Mode mode,Selection selection) {
        public Request {
            Objects.requireNonNull(repository);Objects.requireNonNull(left);Objects.requireNonNull(right);
            Objects.requireNonNull(mode);Objects.requireNonNull(selection);
        }
        public ContentDigest identity(){return hash("architecture.evolution.request:v1",List.of(VERSION,this));}
    }
    public enum Side { LEFT, RIGHT }
    public record ReportRef(Side side,ContentDigest identity,String context) {
        public ReportRef {Objects.requireNonNull(side);Objects.requireNonNull(identity);Objects.requireNonNull(context);}
    }
    public enum ReportStatus { AVAILABLE, MISSING, CORRUPT, UNSUPPORTED, UNAVAILABLE }
    /** Adapter authenticates report bytes and their exact graph and context before returning AVAILABLE. */
    @FunctionalInterface public interface ReportReader {
        ReportStatus verify(ContentDigest graph,ReportRef report,BooleanSupplier cancelled)throws IOException;
    }
    public record Census(long rows,ContentDigest digest) {}
    public record Summary(ContentDigest request,ContentDigest identity,Status status,Map<String,Census> sections) {
        public Summary {sections=Map.copyOf(sections);}
        public Census census(Section section){return sections.get(section.name());}
    }
    public static final class Result {
        private final Summary summary;
        private final EvolutionIndex index;
        Result(Summary summary,EvolutionIndex index){this.summary=summary;this.index=index;}
        public Summary summary(){return summary;}
        public Page<Map<String,Object>> page(Section section,int size,Optional<String> cursor)throws IOException {
            checkSize(size);
            String binding=hash(VERSION,List.of(summary.identity(),section,size)).value();
            String after="";
            if(cursor.isPresent()) {
                var token=cursor.orElseThrow();
                if(!token.startsWith(binding+"|")||token.length()>1024)throw new IllegalArgumentException("Foreign comparison cursor");
                after=token.substring(binding.length()+1);
                if(index.get(section.name(),after).isEmpty())throw new IllegalArgumentException("Stale comparison cursor");
            }
            var batch=index.page(section.name(),after,size);var rows=batch.rows();
            return new Page<>(rows.stream().map(EvolutionIndex.Entry::value).toList(),
                    batch.more()?Optional.of(binding+"|"+rows.getLast().key()):Optional.empty());
        }
    }

    /** The caller owns a fresh scratch index and keeps it open while reading this result. */
    public static Result compare(Request request,EvolutionSnapshot.Reader reader,EvolutionIndex scratch,
                                 int inputPageSize,BooleanSupplier cancelled)throws IOException {
        return compare(request,reader,List.of(),(graph,report,stop)->ReportStatus.UNAVAILABLE,scratch,inputPageSize,cancelled);
    }

    public static Result compare(Request request,EvolutionSnapshot.Reader reader,Collection<ReportRef> reports,
                                 ReportReader reportReader,EvolutionIndex scratch,int inputPageSize,BooleanSupplier cancelled)throws IOException {
        checkSize(inputPageSize);
        var ordered=reports.stream().sorted(Comparator.comparing(ReportRef::side).thenComparing(ReportRef::identity)
                .thenComparing(ReportRef::context)).toList();
        if(new HashSet<>(ordered).size()!=ordered.size())throw new IllegalArgumentException("Duplicate comparison report reference");
        var identity=ordered.isEmpty()?request.identity():hash("architecture.evolution.request:v1",List.of(request.identity(),ordered));
        if(scratch.get("run","owner").isPresent())throw new IllegalArgumentException("Comparison index already owned");
        scratch.put("run","owner",row("request",identity));
        var result=new EvolutionRun(request,identity,scratch,inputPageSize,cancelled).compare(reader,ordered,reportReader);
        EvolutionSnapshot.check(cancelled);
        scratch.put("run","complete",row("result",result.summary().identity()));
        return result;
    }

    private static void checkSize(int size){if(size<1||size>50)throw new IllegalArgumentException("Invalid evolution page size");}
}
