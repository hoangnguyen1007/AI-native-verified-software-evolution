package com.evolution.analysis.evolution;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.acquisition.streaming.ContentHandle;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.graph.PartitionedGraphReader.Page;
import java.io.IOException;
import java.util.*;
import java.util.function.BooleanSupplier;
import static com.evolution.analysis.evolution.EvolutionRows.*;

/** Canonical comparison engine. Product consumers use a disclosure projection of these internal rows. */
public final class SnapshotComparison {
    private SnapshotComparison() {}
    public static final String VERSION="architecture.evolution.internal:m11.6-v2";
    public enum Mode { STRICT, OBSERVATION }
    public enum Status { COMPLETE, PARTIAL, WITHHELD }
    public enum Section { CONTEXTS, COMPATIBILITY, CORRESPONDENCE, EVENTS, GAPS, INPUTS, REGIONS, PROOFS, EVIDENCE,
        FINDINGS, HISTORY, METRICS, SCORES }
    public record Selection(Set<String> sourceSets,Set<String> nodeKinds,Set<String> relationshipKinds,
                            boolean conditional,boolean evidence,boolean findings,boolean metrics,boolean score) {
        public Selection(Set<String> sourceSets,Set<String> nodeKinds,Set<String> relationshipKinds) {
            this(sourceSets,nodeKinds,relationshipKinds,false,false);
        }
        public Selection(Set<String> sourceSets,Set<String> nodeKinds,Set<String> relationshipKinds,
                         boolean conditional,boolean evidence) {
            this(sourceSets,nodeKinds,relationshipKinds,conditional,evidence,false,false,false);
        }
        public Selection {
            sourceSets=Set.copyOf(sourceSets);nodeKinds=Set.copyOf(nodeKinds);relationshipKinds=Set.copyOf(relationshipKinds);
            if(!structural(nodeKinds,relationshipKinds)&&!conditional&&!evidence&&!findings&&!metrics&&!score)
                throw new IllegalArgumentException("Empty comparison selection");
        }
        boolean reports(){return findings||metrics||score;}
        boolean spring(){return conditional||reports();}
        boolean structural(){return structural(nodeKinds,relationshipKinds);}
        private static boolean structural(Set<String> nodes,Set<String> relations){return !nodes.isEmpty()||!relations.isEmpty();}
    }
    public record Request(RepositoryIdentity repository,ContentDigest left,ContentDigest right,Mode mode,Selection selection) {
        public Request {
            Objects.requireNonNull(repository);Objects.requireNonNull(left);Objects.requireNonNull(right);
            Objects.requireNonNull(mode);Objects.requireNonNull(selection);
        }
        public ContentDigest identity(){return hash("architecture.evolution.request:v1",List.of(VERSION,this));}
    }
    public enum Side { LEFT, RIGHT }

    /** Stable graph-disposition row. Its preimage is also used to verify a reusable graph-only result. */
    public static Map<String,Object> graphInput(Side side,ContentDigest graph,SnapshotIdentity snapshot,
                                                String status,Object revision,Object dirty) {
        return row("side",side,"graph",graph,"snapshot",snapshot,"kind","GRAPH","status",status,
                "revision",revision,"dirty",dirty);
    }
    public record HistoryReport(ContentDigest identity,String context) {
        public HistoryReport {Objects.requireNonNull(identity);Objects.requireNonNull(context);}
    }
    public record HistoryRevision(ContentDigest graph,List<HistoryReport> reports) {
        public HistoryRevision {
            Objects.requireNonNull(graph);
            reports=reports.stream().sorted(Comparator.comparing(HistoryReport::context).thenComparing(HistoryReport::identity)).toList();
            if(new HashSet<>(reports).size()!=reports.size())throw new IllegalArgumentException("Duplicate history report");
        }
    }
    /** Each optional proof is for the preceding revision and this revision. */
    public record HistoryStep(HistoryRevision revision,Optional<ContentDigest> interval) {
        public HistoryStep {Objects.requireNonNull(revision);Objects.requireNonNull(interval);}
    }
    public record History(List<HistoryStep> steps) {
        public History {
            steps=List.copyOf(steps);
            if(steps.size()<2||steps.getFirst().interval().isPresent())throw new IllegalArgumentException("Invalid ordered history");
        }
    }
    public enum HistoryBasis { AUTHENTICATED_PARENT, DECLARED_SEQUENCE }
    /** Exact, authenticated evidence supplied by the ancestry or explicit-sequence producer. */
    public record HistoryProof(HistoryBasis basis,RepositoryIdentity repository,ContentDigest from,ContentDigest to,
                               SnapshotIdentity before,SnapshotIdentity after,List<ContentDigest> parents,
                               boolean complete,ContentDigest provenance) {
        public HistoryProof {
            Objects.requireNonNull(basis);Objects.requireNonNull(repository);Objects.requireNonNull(from);Objects.requireNonNull(to);
            Objects.requireNonNull(before);Objects.requireNonNull(after);Objects.requireNonNull(provenance);
            parents=parents.stream().sorted().toList();
            if(new HashSet<>(parents).size()!=parents.size())throw new IllegalArgumentException("Duplicate parent");
        }
        public ContentDigest identity(){return hash("architecture.evolution.history-proof:v1",this);}
    }
    @FunctionalInterface public interface HistoryReader {
        /** Verifies the immutable proof and its provenance before returning its content. */
        Optional<HistoryProof> read(ContentDigest proof,BooleanSupplier cancelled)throws IOException;
    }
    public record ReportRef(Side side,ContentDigest identity,String context) {
        public ReportRef {Objects.requireNonNull(side);Objects.requireNonNull(identity);Objects.requireNonNull(context);}
    }
    public enum ReportStatus { AVAILABLE, MISSING, CORRUPT, UNSUPPORTED, UNAVAILABLE }
    /** Adapter authenticates report bytes and their exact graph and context before returning AVAILABLE. */
    @FunctionalInterface public interface ReportReader {
        ReportStatus verify(ContentDigest graph,ReportRef report,BooleanSupplier cancelled)throws IOException;
        default Optional<EvolutionReport> read(ContentDigest graph,ReportRef report,BooleanSupplier cancelled)throws IOException {
            return Optional.empty();
        }
    }
    /** Exact independent publication, bound to the snapshot of the corresponding graph. */
    public record BridgeRef(Side side,ContentHandle publication) {
        public BridgeRef {Objects.requireNonNull(side);Objects.requireNonNull(publication);}
    }
    @FunctionalInterface public interface BridgeReader {
        ReportStatus read(BridgeRef ref,SnapshotIdentity snapshot,
                          BridgeObservation observe,BooleanSupplier cancelled)throws IOException;
    }
    @FunctionalInterface public interface BridgeObservation {
        void accept(String family,ContentDigest identity,Map<String,Object> operands)throws IOException;
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
        return compare(request,reader,reports,reportReader,List.of(),(b,s,o,c)->ReportStatus.UNAVAILABLE,scratch,inputPageSize,cancelled);
    }

    public static Result compare(Request request,EvolutionSnapshot.Reader reader,Collection<ReportRef> reports,
                                 ReportReader reportReader,Collection<BridgeRef> bridges,BridgeReader bridgeReader,
                                 EvolutionIndex scratch,int inputPageSize,BooleanSupplier cancelled)throws IOException {
        return compare(request,reader,reports,reportReader,bridges,bridgeReader,Optional.empty(),
                (proof,stop)->Optional.empty(),scratch,inputPageSize,cancelled);
    }

    public static Result compare(Request request,EvolutionSnapshot.Reader reader,Collection<ReportRef> reports,
                                 ReportReader reportReader,Collection<BridgeRef> bridges,BridgeReader bridgeReader,
                                 Optional<History> history,HistoryReader historyReader,
                                 EvolutionIndex scratch,int inputPageSize,BooleanSupplier cancelled)throws IOException {
        return compare(request,reader,reports,reportReader,bridges,bridgeReader,history,historyReader,scratch,
                inputPageSize,cancelled,false,Optional.empty());
    }

    /** Exact supplemental candidate evidence for the strict, sealed demo pair. */
    public static Result compareWithDemoBindingCensus(Request request,EvolutionSnapshot.Reader reader,Collection<ReportRef> reports,
            ReportReader reportReader,DemoBindingCensus census,EvolutionIndex scratch,int inputPageSize,BooleanSupplier cancelled)
            throws IOException {
        var proof=census.receipt();
        if (request.mode()!=Mode.STRICT || !request.selection().findings() || !request.right().equals(proof.graph())
                || reports.stream().noneMatch(ref->ref.side()==Side.RIGHT && ref.context().equals(proof.context())))
            throw new IllegalArgumentException("Foreign binding census");
        return compare(request,reader,reports,reportReader,List.of(),(b,s,o,c)->ReportStatus.UNAVAILABLE,
                Optional.empty(),(p,c)->Optional.empty(),scratch,inputPageSize,cancelled,false,Optional.of(census));
    }

    /** The caller must authenticate the durable index and reverify the exact graph publications before resuming. */
    public static Result resumeComparison(Request request,EvolutionSnapshot.Reader reader,Collection<ReportRef> reports,
                                          ReportReader reportReader,Collection<BridgeRef> bridges,BridgeReader bridgeReader,
                                          Optional<History> history,HistoryReader historyReader,EvolutionIndex scratch,
                                          int inputPageSize,BooleanSupplier cancelled)throws IOException {
        return compare(request,reader,reports,reportReader,bridges,bridgeReader,history,historyReader,scratch,
                inputPageSize,cancelled,true,Optional.empty());
    }

    private static Result compare(Request request,EvolutionSnapshot.Reader reader,Collection<ReportRef> reports,
                                 ReportReader reportReader,Collection<BridgeRef> bridges,BridgeReader bridgeReader,
                                 Optional<History> history,HistoryReader historyReader,
                                 EvolutionIndex scratch,int inputPageSize,BooleanSupplier cancelled,boolean resume,
                                 Optional<DemoBindingCensus> census)throws IOException {
        checkSize(inputPageSize);
        var ordered=reports.stream().sorted(Comparator.comparing(ReportRef::side).thenComparing(ReportRef::identity)
                .thenComparing(ReportRef::context)).toList();
        if(new HashSet<>(ordered).size()!=ordered.size())throw new IllegalArgumentException("Duplicate comparison report reference");
        var orderedBridges=bridges.stream().sorted(Comparator.comparing(BridgeRef::side).thenComparing(b->b.publication().digest())).toList();
        if(new HashSet<>(orderedBridges.stream().map(BridgeRef::side).toList()).size()!=orderedBridges.size()
                ||!orderedBridges.isEmpty()&&!request.selection().evidence())throw new IllegalArgumentException("Invalid bridge selection");
        history.ifPresent(h -> {
            if (!request.selection().findings() || !h.steps().getLast().revision().graph().equals(request.right())
                    || !h.steps().get(h.steps().size()-2).revision().graph().equals(request.left()))
                throw new IllegalArgumentException("History must end at the selected finding pair");
            for(var side:Side.values()){
                var position=side==Side.RIGHT?h.steps().size()-1:h.steps().size()-2;
                var expected=h.steps().get(position).revision().reports();
                var actual=ordered.stream().filter(ref->ref.side()==side)
                        .map(ref->new HistoryReport(ref.identity(),ref.context()))
                        .sorted(Comparator.comparing(HistoryReport::context).thenComparing(HistoryReport::identity)).toList();
                if(!expected.equals(actual))throw new IllegalArgumentException("History endpoint report mismatch");
            }
        });
        var baseIdentity=ordered.isEmpty()&&orderedBridges.isEmpty()&&history.isEmpty()?request.identity():
                hash("architecture.evolution.request:v1",List.of(request.identity(),ordered,orderedBridges,history));
        var identity=census.map(c -> hash("architecture.evolution.scoped-census-request:v1",List.of(baseIdentity,c.receipt().identity())))
                .orElse(baseIdentity);
        var owner=scratch.get("run","owner");
        var checkpoint=Optional.<Map<String,Object>>empty();
        if (resume) {
            checkpoint=scratch.get("run","checkpoint");
            if (owner.filter(value->identity.value().equals(value.get("request"))).isEmpty() ||
                    checkpoint.filter(value->identity.value().equals(value.get("request"))).isEmpty())
                throw new IOException("EVOLUTION_CHECKPOINT_SCOPE_MISMATCH");
        } else {
            if(owner.isPresent())throw new IllegalArgumentException("Comparison index already owned");
            scratch.put("run","owner",row("request",identity));
        }
        var result=new EvolutionRun(request,identity,scratch,inputPageSize,cancelled,census)
                .compare(reader,ordered,reportReader,orderedBridges,bridgeReader,history,historyReader,checkpoint);
        EvolutionSnapshot.check(cancelled);
        scratch.put("run","complete",row("result",result.summary().identity()));
        return result;
    }

    private static void checkSize(int size){if(size<1||size>50)throw new IllegalArgumentException("Invalid evolution page size");}
}
