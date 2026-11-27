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
import java.util.function.BooleanSupplier;

/** Durable v1 job receipts under a versioned storage envelope. Only active worker state stays in RAM. */
public final class AnalysisJobDiskStore implements AutoCloseable {
    private static final String LEGACY_SCHEMA = "analysis-job-storage:m8.4-v2";
    private static final String PREVIOUS_SCHEMA = "analysis-job-storage:m8.4-v3";
    private static final String SCHEMA = "analysis-job-storage:m8.4-v4";
    public static final String SCHEMA_V5 = "analysis-job-storage:m8.4-v5";
    private static final String REUSE_INDEX = "workbench-result-index-v1";
    private static final String REUSE_SCHEMA = "workbench-result-publication:v1";
    private static final System.Logger LOG = System.getLogger(AnalysisJobDiskStore.class.getName());

    public record PersistedTelemetry(
            Map<String, Long> stageMillis,
            Map<String, Long> cacheMetrics,
            Optional<AnalysisJobs.IncrementalMetrics> incremental) {
        public PersistedTelemetry {
            stageMillis = Collections.unmodifiableMap(new TreeMap<>(stageMillis == null ? Map.of() : stageMillis));
            cacheMetrics = Collections.unmodifiableMap(new TreeMap<>(cacheMetrics == null ? Map.of() : cacheMetrics));
            incremental = Objects.requireNonNull(incremental, "incremental");
        }

        public boolean isEmpty() {
            return stageMillis.isEmpty() && cacheMetrics.isEmpty() && incremental.isEmpty();
        }

        boolean hasExtendedV5Data() {
            if (incremental.isPresent() || !cacheMetrics.isEmpty()) {
                return true;
            }
            for (String stage : stageMillis.keySet()) {
                if (!"QUEUED".equals(stage) && !"PUBLICATION".equals(stage)) {
                    return true;
                }
            }
            return false;
        }
    }

    public record Saved(String schema, String retry, AnalysisJobs.View view, List<CapabilityGapRecord> gaps,
            List<AnalysisJobs.InputProblem> inputProblems, Optional<PersistedTelemetry> telemetry) {
        public Saved(String schema, String retry, AnalysisJobs.View view, List<CapabilityGapRecord> gaps,
                List<AnalysisJobs.InputProblem> inputProblems) {
            this(schema, retry, view, gaps, inputProblems, Optional.empty());
        }

        public Saved {
            if (!Set.of(SCHEMA_V5, SCHEMA, PREVIOUS_SCHEMA, LEGACY_SCHEMA).contains(schema))
                throw new IllegalArgumentException("Unknown job storage schema");
            gaps = List.copyOf(gaps);
            inputProblems = List.copyOf(inputProblems);
            telemetry = Objects.requireNonNull(telemetry, "telemetry");
        }
    }

    public record Envelope(String schema, String retry, AnalysisJobs.Setup setup, AnalysisJobs.View view,
            ContentHandle gaps, ContentHandle inputProblems, Optional<ContentHandle> telemetry) {
        public Envelope(String schema, String retry, AnalysisJobs.Setup setup, AnalysisJobs.View view,
                ContentHandle gaps, ContentHandle inputProblems) {
            this(schema, retry, setup, view, gaps, inputProblems, Optional.empty());
        }

        public Envelope {
            if (!Set.of(SCHEMA_V5, SCHEMA, PREVIOUS_SCHEMA).contains(schema) || !setup.id().equals(view.setup()))
                throw new IllegalArgumentException("Unknown or mismatched job storage schema");
            telemetry = Objects.requireNonNull(telemetry, "telemetry");
        }
    }

    private record EnvelopeV4(String schema, String retry, AnalysisJobs.Setup setup, AnalysisJobs.View view,
            ContentHandle gaps, ContentHandle inputProblems) {
    }

    private static boolean usesSegmentedGaps(String schema) {
        return SCHEMA.equals(schema) || SCHEMA_V5.equals(schema);
    }
    public record InputLedger(List<AnalysisJobs.InputProblem> inputProblems){}
    private record IndexedResult(String schema, ContentDigest input, ContentHandle receipt) {
        IndexedResult {
            if (!REUSE_SCHEMA.equals(schema)) throw new IllegalArgumentException("Unknown result index schema");
        }
    }
    private final StreamingContentStore content;private final SqliteInventoryStore journal;
    private final StoredGapLedger ledgers;
    private final String namespace;
    public AnalysisJobDiskStore(Path root) throws IOException {
        this(root, false);
    }

    public AnalysisJobDiskStore(Path root, boolean readOnly) throws IOException {
        namespace = ContentDigest.sha256(root.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8)).value().substring(7);
        content = new StreamingContentStore(root.resolve("content"));
        ledgers = new StoredGapLedger(content);
        journal = new SqliteInventoryStore(root.resolve("metadata"), content, readOnly);
    }
    public String namespace(){return namespace;}
    record PreparedLedgers(ContentHandle gaps, ContentHandle inputs) {}
    PreparedLedgers prepareLedgers(List<CapabilityGapRecord> gaps,List<AnalysisJobs.InputProblem> problems,
            BooleanSupplier cancelled)throws IOException{
        return new PreparedLedgers(ledgers.write(gaps,cancelled),write(new InputLedger(problems),cancelled));
    }
    public synchronized void save(String retry,AnalysisJobs.Setup setup,AnalysisJobs.View view,List<CapabilityGapRecord> gaps,List<AnalysisJobs.InputProblem> problems)throws IOException{
        save(retry,setup,view,gaps,problems,null,Optional.empty(),null,Optional.empty(),false);
    }
    public synchronized void save(String retry,AnalysisJobs.Setup setup,AnalysisJobs.View view,List<CapabilityGapRecord> gaps,List<AnalysisJobs.InputProblem> problems,PersistedTelemetry telemetry)throws IOException{
        save(retry,setup,view,gaps,problems,null,Optional.empty(),null,Optional.ofNullable(telemetry),telemetry!=null);
    }
    synchronized void save(String retry, AnalysisJobs.Setup setup, AnalysisJobs.View view, List<CapabilityGapRecord> gaps,
                           List<AnalysisJobs.InputProblem> problems, Envelope reused, Optional<ContentDigest> reusableKey) throws IOException {
        save(retry,setup,view,gaps,problems,reused,reusableKey,null,Optional.empty(),false);
    }
    synchronized void save(String retry, AnalysisJobs.Setup setup, AnalysisJobs.View view, List<CapabilityGapRecord> gaps,
                           List<AnalysisJobs.InputProblem> problems, Envelope reused, Optional<ContentDigest> reusableKey,
                           PreparedLedgers prepared) throws IOException {
        save(retry,setup,view,gaps,problems,reused,reusableKey,prepared,Optional.empty(),false);
    }
    synchronized void save(String retry, AnalysisJobs.Setup setup, AnalysisJobs.View view, List<CapabilityGapRecord> gaps,
                           List<AnalysisJobs.InputProblem> problems, Envelope reused, Optional<ContentDigest> reusableKey,
                           PreparedLedgers prepared, Optional<PersistedTelemetry> telemetry) throws IOException {
        save(retry,setup,view,gaps,problems,reused,reusableKey,prepared,telemetry,false);
    }
    private void save(String retry, AnalysisJobs.Setup setup, AnalysisJobs.View view, List<CapabilityGapRecord> gaps,
                      List<AnalysisJobs.InputProblem> problems, Envelope reused, Optional<ContentDigest> reusableKey,
                      PreparedLedgers prepared, Optional<PersistedTelemetry> telemetry, boolean forceV5) throws IOException {
        var blocks = reused != null ? new PreparedLedgers(reused.gaps(),reused.inputProblems())
                : prepared != null ? prepared : prepareLedgers(gaps,problems,()->false);
        var gapHandle = blocks.gaps();
        var inputHandle = blocks.inputs();
        boolean writeV5;
        String targetSchema;
        Optional<ContentHandle> telemetryHandle;
        if (reused != null) {
            targetSchema = reused.schema();
            telemetryHandle = reused.telemetry();
            writeV5 = SCHEMA_V5.equals(targetSchema);
        } else if (telemetry.isPresent() && (forceV5 || telemetry.orElseThrow().hasExtendedV5Data())) {
            targetSchema = SCHEMA_V5;
            telemetryHandle = Optional.of(write(telemetry.orElseThrow()));
            writeV5 = true;
        } else {
            targetSchema = SCHEMA;
            telemetryHandle = Optional.empty();
            writeV5 = false;
        }
        var envelope = new Envelope(targetSchema,retry,setup,view,gapHandle,inputHandle,telemetryHandle);
        var handle = writeV5 ? write(envelope) : write(new EnvelopeV4(targetSchema,retry,setup,view,gapHandle,inputHandle));
        Optional<ContentDigest> candidate = Optional.empty();
        if (reused == null && reusableKey.isPresent() && eligiblePublication(view)) {
            try {
                if (prepareIndex(reusableKey.orElseThrow(), envelope, handle)) candidate = reusableKey;
            } catch (IOException unavailable) { LOG.log(System.Logger.Level.WARNING, "WORKBENCH_RESULT_INDEX_WRITE_UNAVAILABLE"); }
        }
        var existing=journal.get(view.id());
        if(existing.isEmpty())existing=Optional.of(journal.create(view.id(),ContentDigest.sha256Utf8(retry),Optional.of(handle)));
        String reason=(view.stop().filter(s->s==AnalysisJobs.Stop.SERVICE_CLOSED).isPresent()?"RECOVERABLE":view.state().name())+"."+view.setup();
        journal.transition(view.id(),existing.orElseThrow().revision(),JobJournal.State.RUNNING,reason,Optional.of(handle));
        if (candidate.isPresent()) {
            // An interrupted index update can only lose a hit. The authoritative job is already durable.
            var key = candidate.orElseThrow();
            try { putIndex(keyPath(key), write(new IndexedResult(REUSE_SCHEMA, key, handle)), Optional.empty()); }
            catch (IOException unavailable) { LOG.log(System.Logger.Level.WARNING, "WORKBENCH_RESULT_INDEX_WRITE_UNAVAILABLE"); }
        }
    }
    private static LogicalPath keyPath(ContentDigest key) { return LogicalPath.utf8(key.value().substring(7)); }
    private static boolean eligiblePublication(AnalysisJobs.View view) {
        return view.stage() == AnalysisJobs.Stage.FINISHED && view.stop().isEmpty() && view.error().isEmpty()
                && view.reuse().isEmpty() && view.finishedAt().isPresent()
                && (view.state() == AnalysisJobs.State.SUCCEEDED || view.state() == AnalysisJobs.State.PARTIAL)
                && view.result().filter(r -> r.graph().isPresent() && r.snapshot().isPresent()
                        && r.completion() == (view.state() == AnalysisJobs.State.SUCCEEDED
                                ? AnalysisJobs.Completion.COMPLETE : AnalysisJobs.Completion.PARTIAL)).isPresent();
    }
    private Optional<Envelope> indexed(ContentDigest key, InventoryEntry entry) throws IOException {
        if (entry.disposition() != InventoryEntry.Disposition.ACQUIRED) return Optional.empty();
        var index = read(entry.content().orElseThrow(() -> new IOException("WORKBENCH_RESULT_INDEX_UNAVAILABLE")), IndexedResult.class);
        if (!key.equals(index.input())) throw new IOException("WORKBENCH_RESULT_KEY_MISMATCH");
        var receipt = readEnvelope(index.receipt());
        if (!eligiblePublication(receipt.view())
                || journal.get(receipt.view().id()).flatMap(JobJournal.Record::payload).filter(index.receipt()::equals).isEmpty())
            throw new IOException("WORKBENCH_RESULT_RECEIPT_UNAVAILABLE");
        return Optional.of(receipt);
    }
    Optional<Envelope> reusable(ContentDigest key, AnalysisJobs.Setup setup, BooleanSupplier cancelled) throws IOException {
        var entry = journal.observation(REUSE_INDEX, keyPath(key));
        if (entry.isEmpty() || cancelled.getAsBoolean()) return Optional.empty();
        var candidate = indexed(key, entry.orElseThrow().entry());
        if (candidate.isEmpty()) return Optional.empty();
        var receipt = candidate.orElseThrow();
        if (!setup.equals(receipt.setup())) return Optional.empty();
        boolean verifiedGaps = usesSegmentedGaps(receipt.schema())
                ? ledgers.verify(ledgerIndex(receipt), cancelled) : content.verify(receipt.gaps(), cancelled);
        if (!verifiedGaps || !content.verify(receipt.inputProblems(), cancelled))
            throw new IOException("WORKBENCH_RESULT_LEDGERS_UNAVAILABLE");
        return candidate;
    }
    /** Disable a contradicted key before the recomputation's terminal receipt becomes visible. */
    private boolean prepareIndex(ContentDigest key, Envelope receipt, ContentHandle handle) throws IOException {
        var path = keyPath(key);
        var previous = journal.observation(REUSE_INDEX, path);
        if (previous.isPresent()) {
            var entry = previous.orElseThrow().entry();
            if (entry.disposition() == InventoryEntry.Disposition.GAP) return false;
            Optional<Envelope> old;
            try { old = indexed(key, entry); }
            catch (IOException unavailable) { old = Optional.empty(); }
            if (old.isPresent()) {
                var published = old.orElseThrow();
                if (published.view().result().equals(receipt.view().result())
                        && gapDigest(published).equals(gapDigest(receipt)) && published.inputProblems().equals(receipt.inputProblems())) return false;
                var conflict = write(Map.of("schema", "workbench-result-conflict:v1", "input", key,
                        "first", entry.content().orElseThrow(), "second", handle));
                putIndex(path, conflict, Optional.of("ANALYSIS_REPLAY_CONFLICT"));
                LOG.log(System.Logger.Level.WARNING, "WORKBENCH_RESULT_REPLAY_CONFLICT");
                return false;
            }
        }
        return true;
    }
    private void putIndex(LogicalPath path, ContentHandle handle, Optional<String> problem) throws IOException {
        var entry = new InventoryEntry(path, InventoryEntry.Kind.FILE, "100644", InventoryEntry.Population.CACHE,
                problem.isPresent() ? InventoryEntry.Disposition.GAP : InventoryEntry.Disposition.ACQUIRED,
                Optional.of(handle), Optional.empty(), Optional.of("UTF-8"), List.of(), problem);
        journal.entries(REUSE_INDEX, List.of(new SqliteInventoryStore.Observation(entry, "")));
    }
    private ContentHandle write(Object value)throws IOException{
        return write(value,()->false);
    }
    private ContentHandle write(Object value,BooleanSupplier cancelled)throws IOException{
        byte[] bytes=CanonicalJson.write(value).getBytes(StandardCharsets.UTF_8);if(bytes.length>2_000_000_000)throw new IOException("JOB_RECEIPT_WORKER_REQUIRED");return content.put(new ByteArrayInputStream(bytes),cancelled);
    }
    public synchronized Optional<Saved> get(UUID id)throws IOException{return get(id,false);}
    public synchronized AnalysisJobs.Setup intent(UUID id)throws IOException{return readEnvelope(journal.get(id.toString()).orElseThrow(()->new IOException("JOB_NOT_FOUND")).payload().orElseThrow()).setup();}
    public synchronized Optional<Saved> get(UUID id,boolean includeLedgers)throws IOException{
        var record=journal.get(id.toString());if(record.isEmpty()||record.orElseThrow().payload().isEmpty())return Optional.empty();return Optional.of(load(record.orElseThrow().payload().orElseThrow(),includeLedgers));
    }
    private Envelope required(UUID id)throws IOException{
        var record=journal.get(id.toString()).flatMap(JobJournal.Record::payload);
        if(record.isEmpty())throw new AnalysisJobs.Rejected("JOB_NOT_FOUND");
        var envelope=readEnvelope(record.orElseThrow());
        if(!AnalysisJobs.terminal(envelope.view().state()))throw new AnalysisJobs.Rejected("JOB_NOT_FINISHED");
        return envelope;
    }
    record GapPage(List<CapabilityGapRecord> items,int total){}
    GapPage gaps(UUID id,int offset,int size,OptionalInt expectedTotal)throws IOException{
        var envelope=required(id);
        if(usesSegmentedGaps(envelope.schema())){
            var index=ledgerIndex(envelope);validateOffset(index.total(),offset,expectedTotal);
            return new GapPage(ledgers.page(index,offset,size),index.total());
        }
        return legacyPage(envelope,offset,size,expectedTotal);
    }
    List<AnalysisJobs.InputProblem> inputProblems(UUID id)throws IOException{
        return read(required(id).inputProblems(),InputLedger.class).inputProblems();
    }
    private StoredGapLedger.Index ledgerIndex(Envelope envelope)throws IOException{
        var index=ledgers.index(envelope.gaps());
        if(envelope.view().result().isPresent()&&envelope.view().result().orElseThrow().jobGapCount()!=index.total())
            throw new IOException("JOB_GAP_LEDGER_COUNT_MISMATCH");
        return index;
    }
    private ContentDigest gapDigest(Envelope envelope)throws IOException{
        return usesSegmentedGaps(envelope.schema())?ledgerIndex(envelope).canonical():envelope.gaps().digest();
    }
    private static void validateOffset(int total,int offset,OptionalInt expected){
        if(expected.isPresent()&&expected.getAsInt()!=total||offset<0||offset>=total&&offset!=0)
            throw new IllegalArgumentException("Invalid gap cursor");
    }
    private GapPage legacyPage(Envelope envelope,int offset,int size,OptionalInt expected)throws IOException{
        // Old ledgers have no range index. Authenticate the full stream, but retain only
        // the requested typed records; older 350 MB results must not exhaust the heap.
        var handle=envelope.gaps();var selected=new ArrayList<CapabilityGapRecord>();int[] count={0};boolean[] readRows={false};
        final java.security.MessageDigest digest;
        try{digest=java.security.MessageDigest.getInstance("SHA-256");}
        catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        try(var input=new java.security.DigestInputStream(content.open(handle),digest);
            var reader=new InputStreamReader(input,StandardCharsets.UTF_8.newDecoder())){
            GraphJson.streamObject(reader,new GraphJson.StreamLimits(Math.max(1,handle.size()),128,200_000_000L),
                    (name,member)->{
                        if(!name.equals("gaps")||readRows[0])throw new IOException("JOB_GAP_LEDGER_CORRUPT");
                        readRows[0]=true;
                        member.array(value->{
                            int index=count[0]++;
                            if(index>=offset&&(long)index-offset<size)selected.add(CanonicalRecordReader.read(value,CapabilityGapRecord.class));
                        });
                    });
            if(!readRows[0]||!handle.digest().value().equals("sha256:"+HexFormat.of().formatHex(digest.digest())))
                throw new IOException("JOB_GAP_LEDGER_CORRUPT");
        }catch(IllegalArgumentException invalid){throw new IOException("JOB_GAP_LEDGER_CORRUPT",invalid);}
        validateOffset(count[0],offset,expected);
        if(envelope.view().result().isPresent()&&envelope.view().result().orElseThrow().jobGapCount()!=count[0])
            throw new IOException("JOB_GAP_LEDGER_COUNT_MISMATCH");
        return new GapPage(List.copyOf(selected),count[0]);
    }
    private Object readJson(ContentHandle handle)throws IOException{
        if(handle.size()>2_000_000_000||!content.verify(handle))throw new IOException("JOB_RECEIPT_CORRUPT");
        try(var input=content.open(handle)){
            return GraphJson.read(new String(input.readAllBytes(),StandardCharsets.UTF_8),new GraphJson.Limits(2_000_000_000,128,200_000_000));
        }catch(IllegalArgumentException invalid){throw new IOException("JOB_RECEIPT_CORRUPT",invalid);}
    }
    private <T>T read(ContentHandle handle,Class<T> type)throws IOException{
        return checked(readJson(handle), handle, type);
    }
    private <T> T checked(Object json, ContentHandle handle, Class<T> type) throws IOException {
        try {
            var value = CanonicalRecordReader.read(json,type);
            if(!CanonicalJson.sha256(value).equals(handle.digest()))throw new IOException("JOB_RECEIPT_NONCANONICAL");
            return value;
        } catch (IllegalArgumentException invalid) { throw new IOException("JOB_RECEIPT_CORRUPT", invalid); }
    }
    private Envelope readEnvelope(ContentHandle handle) throws IOException {
        try {
            var row = GraphJson.object(readJson(handle));
            Object schema = row.get("schema");
            var view = new TreeMap<>(GraphJson.object(row.get("view")));
            var setup = new TreeMap<>(GraphJson.object(row.get("setup")));
            if (SCHEMA_V5.equals(schema) && view.containsKey("mode") && setup.containsKey("artifactPolicy")
                    && row.containsKey("telemetry")) {
                return checked(row, handle, Envelope.class);
            }
            // Authenticate the original preimage before adding backward-compatible defaults.
            // Existing v2/v3/v4 history is never rewritten for migration.
            if (!CanonicalJson.sha256(row).equals(handle.digest())) throw new IOException("JOB_RECEIPT_NONCANONICAL");
            boolean legacy = LEGACY_SCHEMA.equals(schema);
            var upgraded = new TreeMap<>(row);
            if (legacy) {
                if (view.containsKey("force") || view.containsKey("reuse") || view.containsKey("reuseDecision")
                        || view.containsKey("mode")) throw new IOException("JOB_RECEIPT_CORRUPT");
                view.put("force", false);
                view.put("reuse", null);
                view.put("reuseDecision", null);
                upgraded.put("schema", PREVIOUS_SCHEMA);
            }
            view.putIfAbsent("mode", null);
            setup.putIfAbsent("artifactPolicy", null);
            upgraded.putIfAbsent("telemetry", null);
            upgraded.put("view", view);
            upgraded.put("setup", setup);
            return CanonicalRecordReader.read(upgraded, Envelope.class);
        } catch (IllegalArgumentException invalid) { throw new IOException("JOB_RECEIPT_CORRUPT", invalid); }
    }
    private Saved load(ContentHandle handle)throws IOException{return load(handle,false);}
    private Saved load(ContentHandle handle,boolean includeLedgers)throws IOException{
        var envelope=readEnvelope(handle);
        List<CapabilityGapRecord> gaps=List.of();
        if(includeLedgers){
            if(usesSegmentedGaps(envelope.schema())){
                var index=ledgerIndex(envelope);gaps=ledgers.page(index,0,index.total());
            }else gaps=legacyPage(envelope,0,Integer.MAX_VALUE,OptionalInt.empty()).items();
        }
        Optional<PersistedTelemetry> telemetry = Optional.empty();
        if (envelope.telemetry().isPresent()) {
            telemetry = Optional.of(read(envelope.telemetry().orElseThrow(), PersistedTelemetry.class));
        }
        return new Saved(envelope.schema(),envelope.retry(),envelope.view(),gaps,
                includeLedgers?read(envelope.inputProblems(),InputLedger.class).inputProblems():List.of(),
                telemetry);
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
        for(String state:List.of("RUNNING","CANCELLING","RECOVERABLE")){
            var seen=new HashSet<String>();
            while(true){
                var batch=journal.waiting(state+"."+setup,64);
                if(batch.isEmpty())break;
                int progressed=0;
                for(var row:batch){
                    var saved=load(row.payload().orElseThrow());var v=saved.view();
                    if(!seen.add(v.id()))continue;
                    progressed++;
                    boolean cancelled=v.stop().filter(s->s==AnalysisJobs.Stop.USER_CANCELLED).isPresent();
                    var next=new AnalysisJobs.View(v.id(),v.setup(),cancelled?AnalysisJobs.State.CANCELLED:AnalysisJobs.State.QUEUED,cancelled?AnalysisJobs.Stage.FINISHED:AnalysisJobs.Stage.QUEUED,v.revision()+1,v.submittedAt(),Optional.empty(),cancelled?Optional.of(java.time.Instant.now()):Optional.empty(),v.elapsedMillis(),cancelled?v.stop():Optional.empty(),Optional.empty(),List.of(new AnalysisJobs.Event(AnalysisJobs.Stage.QUEUED,java.time.Instant.now())),Optional.empty(),v.force(),Optional.empty(),Optional.empty(),v.mode());
                    var original=intent(UUID.fromString(v.id()));if(!original.equals(expected))next=new AnalysisJobs.View(v.id(),v.setup(),AnalysisJobs.State.FAILED,AnalysisJobs.Stage.FINISHED,v.revision()+1,v.submittedAt(),v.startedAt(),Optional.of(java.time.Instant.now()),v.elapsedMillis(),Optional.empty(),Optional.of("SETUP_CONFIGURATION_CHANGED"),v.events(),v.result(),v.force(),Optional.empty(),Optional.empty(),v.mode());
                    save(saved.retry(),original,next,saved.gaps(),saved.inputProblems());
                }
                if(progressed==0)break;
            }
        }
    }
    @Override public void close()throws IOException{journal.close();}
}
