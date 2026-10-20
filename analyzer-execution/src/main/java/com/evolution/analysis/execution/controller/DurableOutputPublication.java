package com.evolution.analysis.execution.controller;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.execution.*;
import com.evolution.analysis.execution.output.OutputCensus;
import com.evolution.analysis.filesystem.*;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/** Durable byte-evidence publication, never an active semantic/success pointer. Controller-owned API.
 * Expectations and nonce are committed before collection. Target paths remain opaque logical bytes.
 * Missing/corrupt inputs cannot replace an earlier immutable publication with a different result.
 */
public final class DurableOutputPublication implements AutoCloseable {
    private static final String INTENT="execution-output-capture-intent:m8.4-v1",REPORT="execution-output-capture-publication:m8.4-v1";
    public enum Checkpoint { OUTCOMES_CAPTURED,REPORT_CAPTURED,PUBLISHED }
    public record Expectation(String role,LogicalPath path,boolean required){public Expectation{ExecutionGraph.text(role);Objects.requireNonNull(path);}}
    public record RoleResult(String role,String outcome,Optional<ContentHandle> captured,Optional<ContentDigest> rawRow){
        public RoleResult{
            ExecutionGraph.text(role);Objects.requireNonNull(captured);Objects.requireNonNull(rawRow);
            if(!Set.of("CAPTURED_BYTES","OPTIONAL_ABSENT","MISSING_REQUIRED","ABSENCE_UNPROVEN","REJECTED","UNAVAILABLE","ROLE_BYTES_UNPROVEN").contains(outcome)||"CAPTURED_BYTES".equals(outcome)&&(captured.isEmpty()||rawRow.isEmpty()))throw new IllegalArgumentException("INVALID_OUTPUT_ROLE_OUTCOME");
        }
    }
    public record Published(ContentHandle document,ContentHandle outcomes,OutputCensus.Decision decision,List<RoleResult> roles){
        public Published{roles=List.copyOf(roles);}
        public EvidenceVerifier.Trust effectiveTrust(){return EvidenceVerifier.Trust.PRODUCER_ASSERTION;}
    }
    private record Intent(ContentHandle source,OutputCensus.Scope scope,List<Expectation> expectations){}
    private final SqliteInventoryStore journal;
    private final StreamingContentStore content;
    private final DurableAttemptStore attempts;
    private final ControllerTerminationLedger terminations;
    private final Path spool;
    private final int maxRoles;
    private final long maxOutcomeBytes;
    private final Consumer<Checkpoint> checkpoint;
    public DurableOutputPublication(Path root,StreamingContentStore content,DurableAttemptStore attempts,ControllerTerminationLedger terminations,int maxRoles,long maxOutcomeBytes)throws IOException{this(root,content,attempts,terminations,maxRoles,maxOutcomeBytes,p->{});}
    public DurableOutputPublication(Path root,StreamingContentStore content,DurableAttemptStore attempts,ControllerTerminationLedger terminations,int maxRoles,long maxOutcomeBytes,Consumer<Checkpoint> checkpoint)throws IOException{
        if(maxRoles<1||maxOutcomeBytes<0)throw new IllegalArgumentException("INVALID_PUBLICATION_WORKER");
        this.content=Objects.requireNonNull(content);this.attempts=Objects.requireNonNull(attempts);this.terminations=Objects.requireNonNull(terminations);this.maxRoles=maxRoles;this.maxOutcomeBytes=maxOutcomeBytes;this.checkpoint=Objects.requireNonNull(checkpoint);
        spool=root.resolve("spool");Files.createDirectories(spool);journal=new SqliteInventoryStore(root.resolve("journal"),content);
    }
    private static String id(SandboxExecutor.Launch launch){return "capture-"+CanonicalJson.sha256(launch).value().substring(7);}
    private ExecutionAttempt attempt(SandboxExecutor.Launch launch)throws IOException{
        var a=attempts.get(launch.attempt());if(!a.launch().equals(launch))throw new IOException("PUBLICATION_LAUNCH_MISMATCH");return a;
    }
    private static String guestAttempt(String attempt)throws IOException{
        if(attempt.matches("[0-9a-f]{32}"))return attempt;
        try{var uuid=UUID.fromString(attempt);if(uuid.toString().equals(attempt))return attempt.replace("-","");}catch(IllegalArgumentException invalid){/* no inferred namespace */}
        throw new IOException("PUBLICATION_ATTEMPT_NAMESPACE_UNSUPPORTED");
    }
    public synchronized void prepare(SandboxExecutor.Launch launch,OutputCensus.Scope scope,List<Expectation> expectations)throws IOException{
        var a=attempt(launch);
        // Register the capture scope before a launch call can lose its returned handle. This also
        // preserves byte evidence on failed/cancelled work without ever qualifying execution.
        if((a.stop()!=ExecutionAttempt.Stop.NONE||a.phase()==ExecutionAttempt.Phase.RELEASED||a.phase()==ExecutionAttempt.Phase.QUIESCENT)&&journal.get(id(launch)).isEmpty())throw new IOException("PUBLICATION_PREPARE_REQUIRES_OPEN_ATTEMPT");
        if(!guestAttempt(launch.attempt()).equals(scope.attempt()))throw new IOException("PUBLICATION_SCOPE_MISMATCH");
        if(expectations.size()>maxRoles)throw new IOException("OUTPUT_PUBLICATION_WORKER_REQUIRED");
        var roles=expectations.stream().sorted(Comparator.comparing(Expectation::role)).toList();
        validateRoles(roles);var handle=put(Map.of("schema",INTENT,"launch",CanonicalJson.sha256(launch),"scope",scope,"expectations",roles));
        journal.create(id(launch),handle.digest(),Optional.of(handle));
    }
    private void validateRoles(List<Expectation> roles)throws IOException{
        if(roles.size()>maxRoles)throw new IOException("OUTPUT_PUBLICATION_WORKER_REQUIRED");
        var seen=new HashSet<String>();for(var role:roles)if(!seen.add(role.role()))throw new IOException("PUBLICATION_DUPLICATE_ROLE");
    }
    private ContentHandle put(Object value)throws IOException{
        if(!CanonicalJson.fitsStructure(value,24,64000))throw new IOException("OUTPUT_PUBLICATION_WORKER_REQUIRED");
        byte[] bytes=CanonicalJson.write(value).getBytes(StandardCharsets.UTF_8);if(bytes.length>64000)throw new IOException("OUTPUT_PUBLICATION_WORKER_REQUIRED");
        return content.put(new ByteArrayInputStream(bytes),()->false);
    }
    private Map<String,Object> object(ContentHandle handle)throws IOException{
        if(handle.size()>64000)throw new IOException("OUTPUT_PUBLICATION_WORKER_REQUIRED");
        if(!content.verify(handle))throw new IOException("PUBLICATION_CONTENT_UNAVAILABLE");
        try(var input=content.open(handle)){
            var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            return GraphJson.object(GraphJson.read(new InputStreamReader(input,decoder),new GraphJson.Limits(64000,24,64000)));
        }catch(IllegalArgumentException|ArithmeticException|ClassCastException invalid){throw new IOException("PUBLICATION_RECORD_INVALID",invalid);}
    }
    private static void fields(Map<String,Object> value,String... names){if(!value.keySet().equals(Set.of(names)))throw new IllegalArgumentException("PUBLICATION_FIELDS_INVALID");}
    private static long number(Object value){return ((BigDecimal)value).longValueExact();}
    private static boolean bool(Object value){if(!(value instanceof Boolean b))throw new IllegalArgumentException("BOOLEAN_REQUIRED");return b;}
    private static ContentHandle handle(Object value){var map=GraphJson.object(value);fields(map,"digest","size");return new ContentHandle(new ContentDigest(GraphJson.string(map.get("digest"))),number(map.get("size")));}
    private Intent intent(SandboxExecutor.Launch launch,ContentHandle source)throws IOException{
        try{
            var value=object(source);fields(value,"schema","launch","scope","expectations");
            if(!INTENT.equals(value.get("schema"))||!CanonicalJson.sha256(launch).value().equals(value.get("launch")))throw new IOException("PUBLICATION_SCOPE_MISMATCH");
            var s=GraphJson.object(value.get("scope"));fields(s,"attempt","nonce","budget");var b=GraphJson.object(s.get("budget"));fields(b,"maxRowBytes","maxObjectBytes","maxCaptureBytes");
            var scope=new OutputCensus.Scope(GraphJson.string(s.get("attempt")),GraphJson.string(s.get("nonce")),new OutputCensus.Budget(Math.toIntExact(number(b.get("maxRowBytes"))),number(b.get("maxObjectBytes")),number(b.get("maxCaptureBytes"))));
            if(!guestAttempt(launch.attempt()).equals(scope.attempt()))throw new IOException("PUBLICATION_SCOPE_MISMATCH");
            var rawRoles=GraphJson.array(value.get("expectations"));if(rawRoles.size()>maxRoles)throw new IOException("OUTPUT_PUBLICATION_WORKER_REQUIRED");
            var roles=new ArrayList<Expectation>();for(var raw:rawRoles){var r=GraphJson.object(raw);fields(r,"role","path","required");var path=GraphJson.object(r.get("path"));fields(path,"rawBase64");roles.add(new Expectation(GraphJson.string(r.get("role")),new LogicalPath(GraphJson.string(path.get("rawBase64"))),bool(r.get("required"))));}
            validateRoles(roles);if(!roles.equals(roles.stream().sorted(Comparator.comparing(Expectation::role)).toList()))throw new IOException("PUBLICATION_RECORD_INVALID");
            return new Intent(source,scope,List.copyOf(roles));
        }catch(IllegalArgumentException|ArithmeticException|ClassCastException invalid){throw new IOException("PUBLICATION_RECORD_INVALID",invalid);}
    }
    public synchronized Published publish(SandboxExecutor.Launch launch,ContentHandle headerSource,BooleanSupplier cancelled)throws IOException{
        Objects.requireNonNull(cancelled);var a=attempt(launch);
        if(!Set.of(ExecutionAttempt.Phase.QUIESCENT,ExecutionAttempt.Phase.RELEASED).contains(a.phase())||a.termination().isEmpty())throw new IOException("PUBLICATION_REQUIRES_QUIESCENCE");
        terminations.require(launch,a.termination().orElseThrow());
        var row=journal.get(id(launch)).orElseThrow(()->new IOException("PUBLICATION_INTENT_REQUIRED"));
        var previous=row.payload().orElseThrow(()->new IOException("PUBLICATION_RECORD_INVALID"));var value=object(previous);
        boolean already=REPORT.equals(value.get("schema"));ContentHandle intentSource;
        try{if(already){fields(value,"schema","launch","intent","termination","header","outcomes","census","roles","decision","trust","activeSemanticPublication");intentSource=handle(value.get("intent"));}else intentSource=previous;}
        catch(IllegalArgumentException|ArithmeticException|ClassCastException invalid){throw new IOException("PUBLICATION_RECORD_INVALID",invalid);}
        var intent=intent(launch,intentSource);var census=new OutputCensus(content);var header=census.header(headerSource,intent.scope());
        if(journal.byRequest(intent.source().digest()).filter(r->r.id().equals(id(launch))).isEmpty())throw new IOException("PUBLICATION_REGISTERED_INTENT_REQUIRED");
        var matches=new HashMap<LogicalPath,OutputCensus.RowOutcome>();var wanted=new HashSet<LogicalPath>();for(var expected:intent.expectations())wanted.add(expected.path());
        Path temporary=Files.createTempFile(spool,"capture-",".ndjson");long[] bytes={0};OutputCensus.Result result;
        try{
            try(var output=new BufferedOutputStream(Files.newOutputStream(temporary))){
                result=census.verify(header,content::open,outcome->{
                    byte[] encoded=(CanonicalJson.write(outcome)+"\n").getBytes(StandardCharsets.UTF_8);
                    if(encoded.length>maxOutcomeBytes-bytes[0])throw new IOException("OUTPUT_PUBLICATION_WORKER_REQUIRED");
                    output.write(encoded);bytes[0]+=encoded.length;
                    if(outcome.path().isPresent()&&wanted.contains(outcome.path().orElseThrow())){
                        var path=outcome.path().orElseThrow();var prior=matches.putIfAbsent(path,outcome);
                        if(prior!=null)matches.put(path,new OutputCensus.RowOutcome(outcome.ordinal(),outcome.rawRow(),outcome.path(),OutputCensus.RowState.REJECTED,outcome.captured(),"OUTPUT_PATH_ORDER_OR_DUPLICATE"));
                    }
                },cancelled);
            }
            try(var channel=java.nio.channels.FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.force(true);}
            ContentHandle outcomes;try(var input=Files.newInputStream(temporary)){outcomes=content.put(input,cancelled);}
            checkpoint.accept(Checkpoint.OUTCOMES_CAPTURED);
            var roles=new ArrayList<RoleResult>();boolean incomplete=false;
            for(var expected:intent.expectations()){
                var match=matches.get(expected.path());String outcome;
                if(match==null)outcome=result.decision()==OutputCensus.Decision.REJECTED||!header.inventoryClosed()?"ABSENCE_UNPROVEN":expected.required()?"MISSING_REQUIRED":"OPTIONAL_ABSENT";
                else if(match.state()==OutputCensus.RowState.CAPTURED&&match.reason().isEmpty())outcome="CAPTURED_BYTES";
                else outcome=match.state()==OutputCensus.RowState.REJECTED?"REJECTED":match.state()==OutputCensus.RowState.PENDING?"UNAVAILABLE":"ROLE_BYTES_UNPROVEN";
                if(!Set.of("CAPTURED_BYTES","OPTIONAL_ABSENT").contains(outcome))incomplete=true;
                roles.add(new RoleResult(expected.role(),outcome,match==null?Optional.empty():match.captured(),match==null?Optional.empty():Optional.of(match.rawRow())));
            }
            var decision=result.decision()==OutputCensus.Decision.REJECTED?OutputCensus.Decision.REJECTED:incomplete?OutputCensus.Decision.OPEN:result.decision();
            var report=new TreeMap<String,Object>();report.put("schema",REPORT);report.put("launch",CanonicalJson.sha256(launch));report.put("intent",intent.source());report.put("termination",a.termination().orElseThrow());report.put("header",headerSource);report.put("outcomes",outcomes);report.put("census",result);report.put("roles",roles);report.put("decision",decision);report.put("trust","PRODUCER_ASSERTION");report.put("activeSemanticPublication",false);
            var document=put(report);checkpoint.accept(Checkpoint.REPORT_CAPTURED);
            if(cancelled.getAsBoolean())throw new IOException("OUTPUT_PUBLICATION_CANCELLED");
            if(already){if(!document.equals(previous))throw new IOException("PUBLICATION_EVIDENCE_REVISION_REQUIRED");}
            else journal.transition(id(launch),row.revision(),JobJournal.State.COMPLETE,"CAPTURED_BYTES_ONLY",Optional.of(document));
            checkpoint.accept(Checkpoint.PUBLISHED);return new Published(document,outcomes,decision,roles);
        }catch(IOException unavailable){
            if(!already&&"OUTPUT_PUBLICATION_WORKER_REQUIRED".equals(unavailable.getMessage()))journal.transition(id(launch),row.revision(),JobJournal.State.WAITING_FOR_RESOURCES,"OUTPUT_PUBLICATION_WORKER_REQUIRED",Optional.of(intent.source()));
            throw unavailable;
        }catch(java.util.concurrent.CancellationException cancelledWrite){throw new IOException("OUTPUT_PUBLICATION_CANCELLED");}
    }
    @Override public void close()throws IOException{journal.close();}
}
