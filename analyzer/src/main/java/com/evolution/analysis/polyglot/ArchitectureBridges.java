package com.evolution.analysis.polyglot;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.util.*;
import java.util.function.*;

/** Cross-context declaration bindings. Neither matching bytes nor producer
 * assertions grant semantic authority or establish observed runtime calls. */
public final class ArchitectureBridges {
    public static final VersionedIdentifier PROVIDER=new VersionedIdentifier("architecture.bridges","m8.4-v1");
    public static final VersionedIdentifier CATALOG=new VersionedIdentifier("evidence.bridge-gaps","m8.4-v1");
    public enum Profile {
        B01("SYMBOL_CORRESPONDENCE","module","binaryOwner","member","descriptor","correspondence","generation"),
        B02("CONSUMES_CONTRACT","contract","version","method","path","parameters"),
        B03("CONSUMES_CONTRACT","schema","namespace","version","service","operation","lineage"),
        B04("PUBLISHES_TO","protocol","namespace","channel","schema"),
        B05("DATA_ACCESS","database","connection","schema","object"),
        B06("ABI_BINDING","library","symbol","signature","abi","callingConvention","registration"),
        B07("SENDS_TO","protocol","namespace","channel","payload","registration"),
        B08("GENERATED_FROM","source","generator","artifact","lineage");
        public final String relation; public final List<String> operands;
        Profile(String relation,String... operands){this.relation=relation;this.operands=List.of(operands);}
        public List<String> byteOperands(){return switch(this){case B01->List.of("correspondence","generation");case B02->List.of("contract","parameters","base");case B03->List.of("schema","lineage");case B04->List.of("schema","routing");case B05->List.of("database","connection");case B06->List.of("library","registration");case B07->List.of("payload","registration");case B08->operands;};}
    }
    public enum State { BOUND, CONTRACT_ONLY, CANDIDATE, AMBIGUOUS, UNRESOLVED, INFEASIBLE, REOPENED, NOT_APPLICABLE }
    public record Condition(ContentDigest domain,Map<String,String> selection,boolean unknown){
        public Condition{Objects.requireNonNull(domain);selection=strings(selection);}
    }
    public record Range(long start,long end){public Range{if(start<0||end<=start)throw new IllegalArgumentException("BRIDGE_SOURCE_RANGE");}}
    public record Anchor(String path,ContentHandle content,Optional<Range> range){
        public Anchor{LogicalPath.utf8(path);Objects.requireNonNull(content);Objects.requireNonNull(range);}
        public Anchor(String path,ContentHandle content,long start,long end){this(path,content,Optional.of(new Range(start,end)));}
    }
    public record Child(ContentHandle request,ContentDigest identity,SemanticTables.Table facts,SemanticTables.Table obligations,SemanticTables.Table artifacts){
        public Child{Objects.requireNonNull(request);Objects.requireNonNull(identity);if(facts.kind()!=SemanticTables.Kind.OBSERVATION||obligations.kind()!=SemanticTables.Kind.OBSERVATION||artifacts.kind()!=SemanticTables.Kind.DOCUMENT)throw new IllegalArgumentException("BRIDGE_CHILD_TABLE_KIND");}
    }
    public record Partition(ContentDigest context,String snapshot,String root,String module,String sourceSet,String variant,String language,String state,
            SemanticTables.Table documents,Optional<ContentHandle> capture,Optional<Child> child){
        public Partition(ContentDigest context,String snapshot,String root,String module,String sourceSet,String variant,String language,String state,SemanticTables.Table documents,Optional<ContentHandle> capture){this(context,snapshot,root,module,sourceSet,variant,language,state,documents,capture,Optional.empty());}
        public Partition{Objects.requireNonNull(context);Objects.requireNonNull(snapshot);Objects.requireNonNull(capture);Objects.requireNonNull(child);for(String text:List.of(root,module,sourceSet,variant,language))if(text.isBlank())throw new IllegalArgumentException("BRIDGE_PARTITION_SCOPE");
            if(!Set.of("COMPLETE","PARTIAL","FAILED","MISSING_PROVIDER").contains(state)||documents.kind()!=SemanticTables.Kind.DOCUMENT)throw new IllegalArgumentException("BRIDGE_PARTITION_STATE");}
    }
    public record Endpoint(String partition,ContentDigest symbol,Profile profile,String direction,String status,Map<String,String> descriptor,Condition condition,Anchor source,List<ContentHandle> evidence){
        public Endpoint{ContractChecks.token(partition,"bridge partition");Objects.requireNonNull(symbol);Objects.requireNonNull(profile);Objects.requireNonNull(condition);Objects.requireNonNull(source);descriptor=strings(descriptor);evidence=List.copyOf(evidence);
            if(!Set.of("REQUIRES","PROVIDES").contains(direction)||!Set.of("RESOLVED","CANDIDATES","AMBIGUOUS","PARTIAL","CONDITIONAL","UNRESOLVED","UNSUPPORTED","ERROR").contains(status)||evidence.isEmpty())throw new IllegalArgumentException("BRIDGE_ENDPOINT_STATUS");
            if(profile==Profile.B02&&descriptor.containsKey("bindingRole")&&!Set.of("CONSUMES","EXPOSES").contains(descriptor.get("bindingRole")))throw new IllegalArgumentException("BRIDGE_HTTP_BINDING_ROLE");
            for(String operand:profile.byteOperands())if(descriptor.containsKey(operand))new ContentDigest(descriptor.get(operand));}
    }
    /** Supplied independently of emitted endpoints. N/A needs captured evidence. */
    public record Requirement(String partition,Profile profile,String applicability,Optional<String> endpoint,List<String> candidates,ContentHandle evidence,String reason){
        public Requirement{ContractChecks.token(partition,"bridge partition");Objects.requireNonNull(profile);Objects.requireNonNull(endpoint);Objects.requireNonNull(evidence);Objects.requireNonNull(reason);
            candidates=ContractChecks.sortedStrings(candidates,"bridge candidates");if(!Set.of("APPLICABLE","UNKNOWN","NOT_APPLICABLE").contains(applicability)||applicability.equals("NOT_APPLICABLE")&&(endpoint.isPresent()||!candidates.isEmpty()||reason.isBlank()))throw new IllegalArgumentException("BRIDGE_REQUIREMENT");}
    }
    public record Input(EvidenceContext root,ContentDigest inventory,ContentDigest policy,SemanticTables.Table partitions,SemanticTables.Table endpoints,SemanticTables.Table requirements){
        public Input{Objects.requireNonNull(root);Objects.requireNonNull(inventory);Objects.requireNonNull(policy);if(partitions.kind()!=SemanticTables.Kind.CONTEXT||endpoints.kind()!=SemanticTables.Kind.OBSERVATION||requirements.kind()!=SemanticTables.Kind.CENSUS)throw new IllegalArgumentException("BRIDGE_INPUT_KIND");}
        public ContentDigest scope(){return ArchitectureBridges.scope(root,inventory);}
        public ContentDigest identity(){return CanonicalJson.sha256(List.of("architecture-bridges-input:m8.4-v1",PROVIDER,this));}
    }
    public record Result(ContentDigest input,ContentDigest revocations,ContentDigest semanticIdentity,SemanticTables.Table relations,SemanticTables.Table gaps,Map<String,Long> counts,long partitions,long endpoints,long missingCensus){
        public Result{counts=Collections.unmodifiableMap(new TreeMap<>(counts));}
        public ContentDigest identity(){return CanonicalJson.sha256(List.of("architecture-bridges-result:m8.4-v1",this));}
        public boolean qualified(){return false;}
        public boolean complete(){return false;}
    }
    private final RepositoryContentStore content;private final SemanticTables tables;private final int sourceBytes,candidates;
    public ArchitectureBridges(RepositoryContentStore content,SemanticTables tables,int sourceBytes,int candidates){this.content=Objects.requireNonNull(content);this.tables=Objects.requireNonNull(tables);if(sourceBytes<1||candidates<1)throw new IllegalArgumentException("BRIDGE_WORK_BUDGET");this.sourceBytes=sourceBytes;this.candidates=candidates;}
    public Result reconcile(Input input,SemanticRootCensus.Index index,Set<ContentDigest> revoked,Supplier<ContentDigest> livePolicy,BooleanSupplier cancelled)throws IOException {
        check(input,livePolicy,cancelled);revoked=Set.copyOf(revoked);
        for(String space:List.of("bridge.partitions","bridge.documents","bridge.endpoints","bridge.census","bridge.covered","bridge.relations","bridge.gaps","bridge.meanings"))try(var rows=index.rows(space,false)){if(rows.next()!=null)throw new IOException("BRIDGE_INDEX_NOT_EMPTY");}
        var counts=new TreeMap<String,Long>();for(var state:State.values())counts.put(state.name(),0L);long missing=0;
        try {
            try(var rows=tables.open(input.partitions(),cancelled)){SemanticTables.Row row;while((row=rows.next())!=null){
                check(input,livePolicy,cancelled);scope(input,row);var partition=partition(row.fields());
                if(!partition.snapshot().equals(input.root().snapshotIdentity().value()))throw new IOException("BRIDGE_FOREIGN_SNAPSHOT");
                if(partition.capture().isPresent())available(partition.capture().orElseThrow());
                index.put("bridge.partitions",row.key(),row);
                var meaning=new TreeMap<>(row.fields());meaning.remove("capture");meaning.remove("documents");meaning.remove("child");
                try(var documents=tables.open(partition.documents(),cancelled)){SemanticTables.Row document;while((document=documents.next())!=null){
                    if(!document.scope().equals(partition.context()))throw new IOException("BRIDGE_DOCUMENT_CONTEXT");available(LanguageSemanticRequest.handle(document.fields().get("content")));
                    index.put("bridge.documents",row.key()+"\0"+document.key(),document);
                }}
                if(partition.child().isPresent())validateChild(input,row.key(),partition,index,cancelled).ifPresent(identity->meaning.put("nativeSemantic",identity));
                index.put("bridge.meanings","context."+row.key(),new SemanticTables.Row(SemanticTables.Kind.OBSERVATION,"context."+row.key(),input.scope(),meaning));
                if(!partition.state().equals("COMPLETE"))issue(input,index,"BRIDGE_CHILD_"+partition.state(),row.identity(),row.key(),null);
                if(List.of(partition.root(),partition.module(),partition.sourceSet(),partition.variant()).contains("NOT_RECORDED"))issue(input,index,"BRIDGE_CONTEXT_SELECTION_INCOMPLETE",row.identity(),row.key(),null);
            }}
            try(var rows=tables.open(input.endpoints(),cancelled)){SemanticTables.Row row;while((row=rows.next())!=null){
                check(input,livePolicy,cancelled);scope(input,row);var endpoint=endpoint(row.fields());var partitionRow=index.get("bridge.partitions",endpoint.partition()).orElseThrow(()->new IOException("BRIDGE_ENDPOINT_PARTITION_MISSING"));var partition=partition(partitionRow.fields());
                var document=index.get("bridge.documents",endpoint.partition()+"\0"+endpoint.source().path()).orElseThrow(()->new IOException("BRIDGE_ENDPOINT_DOCUMENT_MISSING"));
                if(!endpoint.source().content().equals(LanguageSemanticRequest.handle(document.fields().get("content"))))throw new IOException("BRIDGE_ENDPOINT_SOURCE_BINDING");
                if(partition.capture().isEmpty()||!endpoint.evidence().contains(partition.capture().orElseThrow()))throw new IOException("BRIDGE_ENDPOINT_CAPTURE_BINDING");
                for(var evidence:endpoint.evidence())available(evidence);
                if(endpoint.source().range().isPresent()){
                    if(endpoint.source().content().size()>sourceBytes)throw new IOException("BRIDGE_SOURCE_WORKER_REQUIRED");var encoding=GraphJson.string(document.fields().get("encoding"));var range=endpoint.source().range().orElseThrow();
                    try(var source=content.open(endpoint.source().content())){var coordinates=new SourceCoordinates(source.readNBytes(sourceBytes),encoding);coordinates.point(range.start(),SourceCoordinates.Encoding.UTF16);coordinates.point(range.end(),SourceCoordinates.Encoding.UTF16);}
                }else if(document.fields().get("encoding")!=null)issue(input,index,"BRIDGE_SOURCE_LOCATION_ABSENT",row.identity(),endpoint.partition(),endpoint.profile());
                index.put("bridge.endpoints",row.key(),row);
            }}
            try(var rows=tables.open(input.requirements(),cancelled)){SemanticTables.Row row;while((row=rows.next())!=null){
                check(input,livePolicy,cancelled);scope(input,row);var requirement=requirement(row.fields());available(requirement.evidence());
                if(requirement.candidates().size()>candidates)throw new IOException("BRIDGE_CANDIDATE_WORKER_REQUIRED");
                var population=requirement.partition()+"\0"+requirement.profile();if(index.get("bridge.covered",population).isEmpty())index.put("bridge.covered",population,row);
                if(requirement.endpoint().isPresent()){
                    var observed=index.get("bridge.endpoints",requirement.endpoint().orElseThrow());if(observed.isPresent()){var endpoint=endpoint(observed.orElseThrow().fields());if(!endpoint.partition().equals(requirement.partition())||endpoint.profile()!=requirement.profile()||!endpoint.direction().equals("REQUIRES"))throw new IOException("BRIDGE_REQUIREMENT_ENDPOINT_SCOPE");index.put("bridge.census",observed.orElseThrow().key(),observed.orElseThrow());}
                }
                var evaluation=evaluate(input,index,requirement,revoked);counts.compute(evaluation.state().name(),(key,n)->Math.addExact(n,1));
                var fields=new TreeMap<String,Object>();fields.put("requirement",row.identity());fields.put("category",requirement.profile().name());fields.put("state",evaluation.state().name());fields.put("relation",evaluation.relation());fields.put("from",evaluation.from());fields.put("targets",evaluation.targets());fields.put("candidates",requirement.candidates());fields.put("reasons",evaluation.reasons());fields.put("evidence",evaluation.evidence());fields.put("trust","PRODUCER_ASSERTION");fields.put("qualified",false);fields.put("runtimeObserved",false);
                index.put("bridge.relations",row.key(),new SemanticTables.Row(SemanticTables.Kind.OBSERVATION,row.key(),input.scope(),fields));
                var meaning=new TreeMap<>(fields);meaning.remove("requirement");meaning.remove("evidence");index.put("bridge.meanings","requirement."+row.key(),new SemanticTables.Row(SemanticTables.Kind.OBSERVATION,"requirement."+row.key(),input.scope(),meaning));
                if(!Set.of(State.BOUND,State.NOT_APPLICABLE).contains(evaluation.state()))issue(input,index,"BRIDGE_"+evaluation.state(),row.identity(),requirement.partition(),requirement.profile());
            }}
            try(var rows=index.rows("bridge.partitions",false)){SemanticTables.Row row;while((row=rows.next())!=null)for(var profile:Profile.values())if(index.get("bridge.covered",row.key()+"\0"+profile).isEmpty()){missing=Math.addExact(missing,1);issue(input,index,"BRIDGE_CATEGORY_CENSUS_OMITTED",CanonicalJson.sha256(List.of(row.identity(),profile.name())),row.key(),profile);}}
            try(var rows=index.rows("bridge.endpoints",false)){SemanticTables.Row row;while((row=rows.next())!=null){var endpoint=endpoint(row.fields());if(endpoint.direction().equals("REQUIRES")&&index.get("bridge.census",row.key()).isEmpty()){missing=Math.addExact(missing,1);issue(input,index,"BRIDGE_OCCURRENCE_CENSUS_OMITTED",row.identity(),endpoint.partition(),endpoint.profile());}}}
            issue(input,index,"BRIDGE_METHOD_UNQUALIFIED",input.identity());
            var relations=write(index,"bridge.relations",SemanticTables.Kind.OBSERVATION,cancelled);var gaps=write(index,"bridge.gaps",SemanticTables.Kind.CAPABILITY_GAP,cancelled);check(input,livePolicy,cancelled);
            var meanings=write(index,"bridge.meanings",SemanticTables.Kind.OBSERVATION,cancelled);var semanticIdentity=CanonicalJson.sha256(List.of("architecture-bridge-meanings:m8.4-v1",PROVIDER,input.root(),input.inventory(),input.policy(),meanings,counts,missing));
            return new Result(input.identity(),revocationIdentity(revoked),semanticIdentity,relations,gaps,counts,input.partitions().rows(),input.endpoints().rows(),missing);
        }catch(IllegalArgumentException|ArithmeticException|ClassCastException|NullPointerException invalid){throw new IOException("BRIDGE_INPUT_INVALID",invalid);}
    }
    private record Evaluation(State state,String relation,Object from,List<Object> targets,List<String> reasons,List<ContentDigest> evidence){}
    private Optional<ContentDigest> validateChild(Input input,String key,Partition partition,SemanticRootCensus.Index index,BooleanSupplier cancelled)throws IOException{
        var child=partition.child().orElseThrow();available(child.request());if(child.request().size()>65536)throw new IOException("BRIDGE_CHILD_REQUEST_WORKER_REQUIRED");ArchitectureCapture.Request request;
        if(!partition.language().equals("frontendarchitecture")){
            LanguageSemanticRequest language;try(var stream=content.open(child.request())){var value=GraphJson.object(GraphJson.read(new InputStreamReader(stream,java.nio.charset.StandardCharsets.UTF_8),new GraphJson.Limits(65536,64,65536)));if(!CanonicalJson.sha256(value).equals(child.request().digest()))throw new IOException("BRIDGE_CHILD_REQUEST_NONCANONICAL");language=LanguageSemanticRequest.from(value);}
            if(!language.context().snapshotIdentity().equals(input.root().snapshotIdentity())||!language.inventory().equals(input.inventory())||!language.policy().equals(input.policy())||partition.capture().isEmpty())throw new IOException("BRIDGE_CHILD_CONTEXT_BINDING");
            var actual=NativeSemanticCapture.read(partition.capture().orElseThrow(),tables,content,sourceBytes,cancelled);var projected=new LanguageBridgeImport(content,tables,sourceBytes).readDetailed(key,language,actual,index,cancelled);
            if(!projected.fragment().partition().fields().equals(row(SemanticTables.Kind.CONTEXT,key,input.scope(),partition).fields()))throw new IOException("BRIDGE_CHILD_CAPTURE_BINDING");return Optional.of(projected.semanticIdentity());
        }
        try(var stream=content.open(child.request())){var value=GraphJson.object(GraphJson.read(new InputStreamReader(stream,java.nio.charset.StandardCharsets.UTF_8),new GraphJson.Limits(65536,64,65536)));if(!CanonicalJson.sha256(value).equals(child.request().digest()))throw new IOException("BRIDGE_CHILD_REQUEST_NONCANONICAL");request=ArchitectureCapture.request(value);}
        if(!request.context().snapshotIdentity().equals(input.root().snapshotIdentity())||!request.scope().equals(partition.context())||partition.capture().isEmpty()||!partition.language().equals("frontendarchitecture"))throw new IOException("BRIDGE_CHILD_CONTEXT_BINDING");var actual=ArchitectureCapture.read(request,partition.capture().orElseThrow(),tables,content,sourceBytes,gap->{},cancelled);
        if(!actual.identity().equals(child.identity())||!actual.facts().equals(child.facts())||!actual.obligations().equals(child.obligations())||!actual.artifacts().equals(child.artifacts()))throw new IOException("BRIDGE_CHILD_CAPTURE_BINDING");
        try(var rows=tables.open(request.documents(),cancelled)){SemanticTables.Row row;while((row=rows.next())!=null){var document=index.get("bridge.documents",key+"\0"+row.key()).orElseThrow(()->new IOException("BRIDGE_CHILD_DOCUMENT_OMITTED"));if(!document.equals(row))throw new IOException("BRIDGE_CHILD_DOCUMENT_BINDING");}}return Optional.empty();
    }
    private Evaluation evaluate(Input input,SemanticRootCensus.Index index,Requirement requirement,Set<ContentDigest> revoked)throws IOException{
        var evidence=new TreeSet<ContentDigest>();evidence.add(requirement.evidence().digest());String relation=requirement.profile().relation;
        var sourceRow=requirement.endpoint().isPresent()?index.get("bridge.endpoints",requirement.endpoint().orElseThrow()):Optional.<SemanticTables.Row>empty();
        if(requirement.profile()==Profile.B02&&sourceRow.isPresent()&&"EXPOSES".equals(endpoint(sourceRow.orElseThrow().fields()).descriptor().get("bindingRole")))relation="EXPOSES_CONTRACT";
        if(revoked.contains(requirement.evidence().digest()))return new Evaluation(State.REOPENED,relation,null,List.of(),List.of("REQUIREMENT_EVIDENCE_REVOKED"),List.copyOf(evidence));
        if(index.get("bridge.partitions",requirement.partition()).isEmpty())return new Evaluation(State.UNRESOLVED,relation,null,List.of(),List.of("REQUIREMENT_CONTEXT_MISSING"),List.copyOf(evidence));
        if(requirement.applicability().equals("NOT_APPLICABLE"))return new Evaluation(State.NOT_APPLICABLE,relation,null,List.of(),List.of(requirement.reason()),List.copyOf(evidence));
        if(sourceRow.isEmpty())return new Evaluation(State.UNRESOLVED,relation,null,List.of(),List.of("REQUIRING_ENDPOINT_MISSING"),List.copyOf(evidence));
        var row=sourceRow.orElseThrow();var source=endpoint(row.fields());
        if(!source.partition().equals(requirement.partition())||source.profile()!=requirement.profile()||!source.direction().equals("REQUIRES"))throw new IOException("BRIDGE_REQUIREMENT_ENDPOINT_SCOPE");
        var owner=index.get("bridge.partitions",source.partition()).orElseThrow();var from=reference(input,partition(owner.fields()),source);addEvidence(evidence,row,source,owner);
        if(source.profile()==Profile.B04&&"SUBSCRIBES".equals(source.descriptor().get("bindingRole")))relation="SUBSCRIBES_TO";
        if(source.profile()==Profile.B05)relation=source.descriptor().getOrDefault("access","DATA_ACCESS");
        if(!Set.of("READS","WRITES","READS_WRITES").contains(relation)&&source.profile()==Profile.B05)return new Evaluation(State.CANDIDATE,"DATA_ACCESS",from,List.of(),List.of("ACCESS_MEANING_MISSING"),List.copyOf(evidence));
        if(revoked(row,source,owner,revoked))return new Evaluation(State.REOPENED,relation,from,List.of(),List.of("SOURCE_EVIDENCE_REVOKED"),List.copyOf(evidence));
        var exact=new ArrayList<Object>();var uncertain=new ArrayList<Object>();var reasons=new TreeSet<String>();boolean infeasible=false,reopened=false,contractOnly=false,openCandidate=false;
        for(String key:requirement.candidates()){
            var candidate=index.get("bridge.endpoints",key);if(candidate.isEmpty()){openCandidate=true;reasons.add("CANDIDATE_ENDPOINT_MISSING");continue;}
            var targetRow=candidate.orElseThrow();var target=endpoint(targetRow.fields());var targetOwner=index.get("bridge.partitions",target.partition()).orElseThrow();addEvidence(evidence,targetRow,target,targetOwner);
            if(target.profile()!=source.profile()||!target.direction().equals("PROVIDES")){reasons.add("CANDIDATE_PROFILE_OR_DIRECTION_MISMATCH");continue;}
            if(revoked(targetRow,target,targetOwner,revoked)){reopened=true;reasons.add("TARGET_EVIDENCE_REVOKED");continue;}
            var ref=reference(input,partition(targetOwner.fields()),target);boolean missing=false,mismatch=false;
            for(String operand:source.profile().operands){String a=source.descriptor().get(operand),b=target.descriptor().get(operand);if(a==null||b==null){missing=true;reasons.add("MISSING_"+operand);}else if(!a.equals(b)){mismatch=true;reasons.add("MISMATCH_"+operand);}}
            if(mismatch)continue;
            for(var endpoint:List.of(source,target))for(String operand:endpoint.profile().byteOperands())if(endpoint.descriptor().containsKey(operand)&&!hasBytes(endpoint,endpoint.descriptor().get(operand))){missing=true;reasons.add("UNCAPTURED_"+operand);}
            if(source.profile()==Profile.B08&&(!Objects.equals(source.descriptor().get("artifact"),source.source().content().digest().value())||!Objects.equals(target.descriptor().get("source"),target.source().content().digest().value()))){missing=true;reasons.add("GENERATION_SOURCE_ARTIFACT_BINDING_MISSING");}
            if(source.profile()==Profile.B02&&(!canonicalHttp(source.descriptor())||!canonicalHttp(target.descriptor()))){missing=true;reasons.add("HTTP_NORMALIZATION_REQUIRED");}
            if(!source.condition().domain().equals(target.condition().domain())||source.condition().unknown()||target.condition().unknown()){missing=true;reasons.add("CONDITION_DOMAIN_UNPROVEN");}
            else if(source.condition().selection().entrySet().stream().anyMatch(e->target.condition().selection().containsKey(e.getKey())&&!e.getValue().equals(target.condition().selection().get(e.getKey())))){infeasible=true;reasons.add("INCOMPATIBLE_SELECTION");continue;}
            if(!source.status().equals("RESOLVED")||!target.status().equals("RESOLVED")||requirement.applicability().equals("UNKNOWN")||Set.of("FAILED","MISSING_PROVIDER").contains(partition(owner.fields()).state())||Set.of("FAILED","MISSING_PROVIDER").contains(partition(targetOwner.fields()).state())){missing=true;reasons.add("ENDPOINT_OR_APPLICABILITY_UNCERTAIN");}
            if(missing){uncertain.add(ref);continue;}
            if(source.profile()==Profile.B02||source.profile()==Profile.B04){var routing=source.profile()==Profile.B02?List.of("service","base"):List.of("routing");
                for(String operand:routing)if(source.descriptor().get(operand)==null||!Objects.equals(source.descriptor().get(operand),target.descriptor().get(operand))){contractOnly=true;reasons.add("ENDPOINT_ROUTING_UNPROVEN");}}
            exact.add(ref);
        }
        State state;List<Object> targets;
        if(exact.size()>1){state=State.AMBIGUOUS;targets=exact;}
        else if(!uncertain.isEmpty()||!exact.isEmpty()&&(openCandidate||reopened)){state=State.CANDIDATE;targets=new ArrayList<>(exact);targets.addAll(uncertain);}
        else if(exact.size()==1){state=contractOnly?State.CONTRACT_ONLY:State.BOUND;targets=exact;}
        else{state=reopened?State.REOPENED:infeasible?State.INFEASIBLE:State.UNRESOLVED;targets=List.of();}
        if(state==State.UNRESOLVED&&reasons.isEmpty())reasons.add("NO_CAPTURED_TARGET");
        return new Evaluation(state,relation,from,targets,List.copyOf(reasons),List.copyOf(evidence));
    }
    // Lexical HTTP bridge guard, not request-target validation. CONNECT is an HTTP
    // method; the OpenAPI 3.0/3.1 reader has a narrower fixed operation vocabulary.
    private static boolean canonicalHttp(Map<String,String> values){String method=values.get("method"),path=values.get("path");return method!=null&&path!=null&&method.matches("GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|TRACE|CONNECT")&&path.startsWith("/")&&!path.contains("?")&&!path.contains("#")&&!path.contains(":")&&!path.contains("//");}
    private static boolean hasBytes(Endpoint endpoint,String digest){return endpoint.source().content().digest().value().equals(digest)||endpoint.evidence().stream().anyMatch(handle->handle.digest().value().equals(digest));}
    private static Map<String,Object> reference(Input input,Partition partition,Endpoint endpoint){var source=new TreeMap<String,Object>();source.put("path",endpoint.source().path());source.put("content",endpoint.source().content());source.put("precision",endpoint.descriptor().getOrDefault("sourcePrecision","NOT_RECORDED"));source.put("range",endpoint.source().range().map(r->Map.of("start",r.start(),"end",r.end())).orElse(null));return Map.of("snapshot",input.root().snapshotIdentity().value(),"context",partition.context(),"symbol",endpoint.symbol(),"identity",CanonicalJson.sha256(List.of("architecture-bridge-symbol:m8.4-v1",input.root().snapshotIdentity(),partition.context(),endpoint.symbol())),"partition",endpoint.partition(),"condition",Map.of("domain",endpoint.condition().domain(),"selection",endpoint.condition().selection(),"unknown",endpoint.condition().unknown()),"source",source);}
    private static boolean revoked(SemanticTables.Row row,Endpoint endpoint,SemanticTables.Row partition,Set<ContentDigest> revoked){return revoked.contains(row.identity())||revoked.contains(partition.identity())||revoked.contains(endpoint.source().content().digest())||endpoint.evidence().stream().anyMatch(h->revoked.contains(h.digest()));}
    private static void addEvidence(Set<ContentDigest> evidence,SemanticTables.Row row,Endpoint endpoint,SemanticTables.Row partition){evidence.add(row.identity());evidence.add(partition.identity());evidence.add(endpoint.source().content().digest());endpoint.evidence().forEach(h->evidence.add(h.digest()));}
    private void issue(Input input,SemanticRootCensus.Index index,String reason,ContentDigest cause)throws IOException{
        issue(input,index,reason,cause,null,null);
    }
    private void issue(Input input,SemanticRootCensus.Index index,String reason,ContentDigest cause,String partition,Profile profile)throws IOException{
        var subject=new EvidenceSubject(EvidenceSubject.Kind.OBSERVATION,cause.value());var observation=ProviderObservationReference.create(PROVIDER,"architecture.bridge",input.identity(),cause);
        var need=new EvidenceRequirement(EvidenceRequirement.Kind.ALTERNATE_FRONTEND,"architecture.bridge-evidence",List.of(subject),EvidenceRequirement.AuthorizationClass.ISOLATED_EXECUTION,List.of("Acquire the missing exact context, correspondence, routing or native evidence; retain the original requirement."));
        var gap=CapabilityGapRecord.create(CATALOG,input.root(),PROVIDER,"architecture.bridge",reason,subject,List.of(),List.of(observation),List.of(need),List.of(),List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE,"architecture.bridge")),List.of(),List.of(),List.of("Captured declaration compatibility is producer evidence; runtime traffic and production method qualification are not established."));
        var fields=new TreeMap<String,Object>();fields.put("schema","architecture-bridge-gap-row:m8.4-v1");fields.put("reasonCode",reason);fields.put("partition",partition);fields.put("category",profile==null?null:profile.name());fields.put("gap",GraphJson.read(CanonicalJson.write(gap),new GraphJson.Limits(65536,64,65536)));
        var row=new SemanticTables.Row(SemanticTables.Kind.CAPABILITY_GAP,gap.gapIdentity().value(),input.scope(),fields);if(index.get("bridge.gaps",row.key()).isEmpty())index.put("bridge.gaps",row.key(),row);
    }
    private SemanticTables.Table write(SemanticRootCensus.Index index,String space,SemanticTables.Kind kind,BooleanSupplier cancelled)throws IOException{
        try(var rows=index.rows(space,false)){var iterator=new Iterator<SemanticTables.Row>(){SemanticTables.Row next;boolean loaded;public boolean hasNext(){if(!loaded)try{next=rows.next();loaded=true;}catch(IOException failure){throw new UncheckedIOException(failure);}return next!=null;}public SemanticTables.Row next(){if(!hasNext())throw new NoSuchElementException();loaded=false;return next;}};
            try{return tables.write(kind,iterator,cancelled);}catch(UncheckedIOException failure){throw failure.getCause();}}
    }
    public static ContentDigest revocationIdentity(Set<ContentDigest> revoked){return CanonicalJson.sha256(revoked.stream().sorted().toList());}
    private void available(ContentHandle handle)throws IOException{if(!content.verify(handle))throw new IOException("BRIDGE_EVIDENCE_UNAVAILABLE");}
    private static void scope(Input input,SemanticTables.Row row)throws IOException{if(!row.scope().equals(input.scope()))throw new IOException("BRIDGE_TABLE_SCOPE");}
    private static void check(Input input,Supplier<ContentDigest> livePolicy,BooleanSupplier cancelled)throws IOException{if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("BRIDGE_CANCELLED_WITH_FRONTIER");if(!input.policy().equals(livePolicy.get()))throw new IOException("BRIDGE_POLICY_REVOKED");}
    public static Partition partition(Object value){var m=fields(value,"context","snapshot","root","module","sourceSet","variant","language","state","documents","capture","child");return new Partition(digest(m,"context"),text(m,"snapshot"),text(m,"root"),text(m,"module"),text(m,"sourceSet"),text(m,"variant"),text(m,"language"),text(m,"state"),LanguageSemanticRequest.table(m.get("documents")),Optional.ofNullable(m.get("capture")).map(LanguageSemanticRequest::handle),Optional.ofNullable(m.get("child")).map(v->{var c=fields(v,"request","identity","facts","obligations","artifacts");return new Child(LanguageSemanticRequest.handle(c.get("request")),digest(c,"identity"),LanguageSemanticRequest.table(c.get("facts")),LanguageSemanticRequest.table(c.get("obligations")),LanguageSemanticRequest.table(c.get("artifacts")));}));}
    public static Endpoint endpoint(Object value){var m=fields(value,"partition","symbol","profile","direction","status","descriptor","condition","source","evidence");var c=fields(m.get("condition"),"domain","selection","unknown");var a=fields(m.get("source"),"path","content","range");var range=Optional.ofNullable(a.get("range")).map(v->{var r=fields(v,"start","end");return new Range(number(r,"start"),number(r,"end"));});return new Endpoint(text(m,"partition"),digest(m,"symbol"),Profile.valueOf(text(m,"profile")),text(m,"direction"),text(m,"status"),stringMap(m.get("descriptor")),new Condition(digest(c,"domain"),stringMap(c.get("selection")),bool(c,"unknown")),new Anchor(text(a,"path"),LanguageSemanticRequest.handle(a.get("content")),range),GraphJson.array(m.get("evidence")).stream().map(LanguageSemanticRequest::handle).toList());}
    public static Requirement requirement(Object value){var m=fields(value,"partition","profile","applicability","endpoint","candidates","evidence","reason");return new Requirement(text(m,"partition"),Profile.valueOf(text(m,"profile")),text(m,"applicability"),Optional.ofNullable(m.get("endpoint")).map(GraphJson::string),GraphJson.array(m.get("candidates")).stream().map(GraphJson::string).toList(),LanguageSemanticRequest.handle(m.get("evidence")),text(m,"reason"));}
    public static Input input(Object value){var m=fields(value,"root","inventory","policy","partitions","endpoints","requirements");var root=fields(m.get("root"),"snapshotIdentity","analysisIdentity");return new Input(new EvidenceContext(new com.evolution.analysis.contract.identity.SnapshotIdentity(text(root,"snapshotIdentity")),Optional.ofNullable(root.get("analysisIdentity")).map(v->new com.evolution.analysis.contract.identity.AnalysisIdentity(GraphJson.string(v)))),digest(m,"inventory"),digest(m,"policy"),LanguageSemanticRequest.table(m.get("partitions")),LanguageSemanticRequest.table(m.get("endpoints")),LanguageSemanticRequest.table(m.get("requirements")));}
    public static Result result(Object value){var m=fields(value,"input","revocations","semanticIdentity","relations","gaps","counts","partitions","endpoints","missingCensus");var counts=new TreeMap<String,Long>();GraphJson.object(m.get("counts")).forEach((key,n)->counts.put(key,((java.math.BigDecimal)n).longValueExact()));return new Result(digest(m,"input"),digest(m,"revocations"),digest(m,"semanticIdentity"),LanguageSemanticRequest.table(m.get("relations")),LanguageSemanticRequest.table(m.get("gaps")),counts,number(m,"partitions"),number(m,"endpoints"),number(m,"missingCensus"));}
    private static Map<String,Object> fields(Object value,String... expected){var map=GraphJson.object(value);if(!map.keySet().equals(Set.of(expected)))throw new IllegalArgumentException("BRIDGE_RECORD_FIELDS");return map;}
    private static String text(Map<String,Object> map,String key){return GraphJson.string(map.get(key));}
    private static ContentDigest digest(Map<String,Object> map,String key){return new ContentDigest(text(map,key));}
    private static long number(Map<String,Object> map,String key){return ((java.math.BigDecimal)map.get(key)).longValueExact();}
    private static boolean bool(Map<String,Object> map,String key){if(!(map.get(key) instanceof Boolean value))throw new IllegalArgumentException("BRIDGE_BOOLEAN");return value;}
    private static Map<String,String> stringMap(Object value){var result=new TreeMap<String,String>();GraphJson.object(value).forEach((key,member)->result.put(key,GraphJson.string(member)));return result;}
    public static ContentDigest scope(EvidenceContext root,ContentDigest inventory){return CanonicalJson.sha256(List.of("architecture-bridge-scope:m8.4-v1",root,inventory));}
    public static SemanticTables.Row row(SemanticTables.Kind kind,String key,ContentDigest scope,Object value){return new SemanticTables.Row(kind,key,scope,GraphJson.object(GraphJson.read(CanonicalJson.write(value),new GraphJson.Limits(1_000_000,64,1_000_000))));}
    private static Map<String,String> strings(Map<String,String> values){var copy=new TreeMap<String,String>();values.forEach((key,value)->{if(key==null||key.isBlank()||value==null||value.isBlank())throw new IllegalArgumentException("BRIDGE_EMPTY_OPERAND");copy.put(key,value);});return Collections.unmodifiableMap(copy);}
}
