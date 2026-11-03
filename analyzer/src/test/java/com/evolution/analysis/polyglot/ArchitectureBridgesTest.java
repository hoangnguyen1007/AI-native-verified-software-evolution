package com.evolution.analysis.polyglot;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.evidence.*;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArchitectureBridgesTest {
    static final class Index implements SemanticRootCensus.Index {
        final Map<String,TreeMap<String,SemanticTables.Row>> spaces=new HashMap<>();final Set<String> marked=new HashSet<>();
        public void put(String space,String key,SemanticTables.Row row)throws IOException{if(spaces.computeIfAbsent(space,k->new TreeMap<>()).putIfAbsent(key,row)!=null)throw new IOException("duplicate");}
        public Optional<SemanticTables.Row> get(String space,String key){return Optional.ofNullable(spaces.getOrDefault(space,new TreeMap<>()).get(key));}
        public void mark(String space,String key){marked.add(space+"\0"+key);}
        public SemanticRootCensus.Rows rows(String space,boolean unmarked){var iterator=spaces.getOrDefault(space,new TreeMap<>()).entrySet().stream().filter(e->!unmarked||!marked.contains(space+"\0"+e.getKey())).map(Map.Entry::getValue).toList().iterator();return new SemanticRootCensus.Rows(){public SemanticTables.Row next(){return iterator.hasNext()?iterator.next():null;}public void close(){}};}
        public void close(){}
    }
    static final class Fixture {
        final SemanticTablesTest.Memory content=new SemanticTablesTest.Memory();final SemanticTables tables=new SemanticTables(content,65536);
        final ContentDigest inventory=ContentDigest.sha256Utf8("sealed mixed inventory"),policy=ContentDigest.sha256Utf8("policy");
        final EvidenceContext root=EvidenceContext.forSnapshot(SnapshotIdentity.from(RepositoryIdentity.fromCanonicalCoordinate("urn:test:bridges"),inventory));
        final ContentDigest scope=ArchitectureBridges.scope(root,inventory),domain=ContentDigest.sha256Utf8("selected condition domain");
        final List<SemanticTables.Row> partitions=new ArrayList<>(),endpoints=new ArrayList<>(),requirements=new ArrayList<>();
        final ContentHandle source,capture,registration;
        Fixture()throws Exception{source=content.text("// é😀\r\nexport function operation() {}\n");capture=content.text("fixed independent native capture");registration=content.text("precommitted owner and context registration");
            partition("client","typescript","COMPLETE");partition("server","java","PARTIAL");}
        void partition(String key,String language,String state)throws Exception{var context=ContentDigest.sha256Utf8(key);var docs=tables.write(SemanticTables.Kind.DOCUMENT,List.of(new SemanticTables.Row(SemanticTables.Kind.DOCUMENT,"src/main",context,Map.of("content",source,"encoding","UTF-8","role","main"))).iterator(),()->false);
            partitions.add(ArchitectureBridges.row(SemanticTables.Kind.CONTEXT,key,scope,new ArchitectureBridges.Partition(context,root.snapshotIdentity().value(),".",key,"main","default",language,state,docs,Optional.of(capture))));}
        Map<String,String> descriptor(ArchitectureBridges.Profile profile){var result=new TreeMap<String,String>();for(String key:profile.operands)result.put(key,"exact:"+key);for(String key:profile.byteOperands())result.put(key,registration.digest().value());if(profile==ArchitectureBridges.Profile.B08){result.put("source",source.digest().value());result.put("artifact",source.digest().value());}if(profile==ArchitectureBridges.Profile.B02){result.put("method","GET");result.put("path","/orders/{id}");result.put("service","owner-service");}if(profile==ArchitectureBridges.Profile.B05)result.put("access","READS");return result;}
        void endpoint(String key,String partition,ArchitectureBridges.Profile profile,String direction,String status,Map<String,String> descriptor,ArchitectureBridges.Condition condition){endpoints.add(ArchitectureBridges.row(SemanticTables.Kind.OBSERVATION,key,scope,new ArchitectureBridges.Endpoint(partition,ContentDigest.sha256Utf8(partition+":"+key),profile,direction,status,descriptor,condition,new ArchitectureBridges.Anchor("src/main",source,8,35),List.of(capture,registration))));}
        void pair(ArchitectureBridges.Profile profile){var condition=new ArchitectureBridges.Condition(domain,Map.of(),false);endpoint(profile+".client","client",profile,"REQUIRES","RESOLVED",descriptor(profile),condition);endpoint(profile+".server","server",profile,"PROVIDES","RESOLVED",descriptor(profile),condition);require(profile+".required","client",profile,Optional.of(profile+".client"),List.of(profile+".server"));}
        void require(String key,String partition,ArchitectureBridges.Profile profile,Optional<String> endpoint,List<String> candidates){requirements.add(ArchitectureBridges.row(SemanticTables.Kind.CENSUS,key,scope,new ArchitectureBridges.Requirement(partition,profile,"APPLICABLE",endpoint,candidates,registration,"independent occurrence")));}
        ArchitectureBridges.Input input()throws Exception{return new ArchitectureBridges.Input(root,inventory,policy,table(SemanticTables.Kind.CONTEXT,partitions),table(SemanticTables.Kind.OBSERVATION,endpoints),table(SemanticTables.Kind.CENSUS,requirements));}
        SemanticTables.Table table(SemanticTables.Kind kind,List<SemanticTables.Row> rows)throws Exception{return tables.write(kind,rows.stream().sorted(Comparator.comparing(SemanticTables.Row::key)).iterator(),()->false);}
        ArchitectureBridges.Result run()throws Exception{return run(Set.of());}
        ArchitectureBridges.Result run(Set<ContentDigest> revoked)throws Exception{return new ArchitectureBridges(content,tables,65536,100).reconcile(input(),new Index(),revoked,()->policy,()->false);}
        List<SemanticTables.Row> read(SemanticTables.Table table)throws Exception{var result=new ArrayList<SemanticTables.Row>();try(var reader=tables.open(table,()->false)){SemanticTables.Row row;while((row=reader.next())!=null)result.add(row);}return result;}
    }
    @Test void exactEightMechanismsPreserveRelationMeaningsAndUnqualifiedTrust()throws Exception {
        var f=new Fixture();for(var profile:ArchitectureBridges.Profile.values())f.pair(profile);var result=f.run();
        assertEquals(8L,result.counts().get("BOUND"));assertEquals(8,result.relations().rows());assertFalse(result.qualified());assertFalse(result.complete());
        var meanings=f.read(result.relations()).stream().map(r->r.fields().get("relation")).toList();assertTrue(meanings.containsAll(List.of("SYMBOL_CORRESPONDENCE","CONSUMES_CONTRACT","ABI_BINDING","GENERATED_FROM","READS")));assertFalse(meanings.contains("CALLS"));
    }
    @Test void backendContractExposureKeepsItsOwnRelationMeaning()throws Exception {
        var f=new Fixture();f.pair(ArchitectureBridges.Profile.B02);var row=f.endpoints.removeFirst();var endpoint=ArchitectureBridges.endpoint(row.fields());var descriptor=new TreeMap<>(endpoint.descriptor());descriptor.put("bindingRole","EXPOSES");f.endpoint(row.key(),endpoint.partition(),endpoint.profile(),endpoint.direction(),endpoint.status(),descriptor,endpoint.condition());
        assertEquals("EXPOSES_CONTRACT",f.read(f.run().relations()).getFirst().fields().get("relation"));
    }
    @Test void wrongOwnerSchemaSignatureLibraryAndAbiNeverBindEvenWithOneCandidate()throws Exception {
        for(var profile:ArchitectureBridges.Profile.values())for(String operand:profile.operands){
            var f=new Fixture();f.pair(profile);var row=f.endpoints.removeLast();var endpoint=ArchitectureBridges.endpoint(row.fields());var changed=new TreeMap<>(endpoint.descriptor());changed.put(operand,profile.byteOperands().contains(operand)?ContentDigest.sha256Utf8("different bytes").value():"different-owner-or-version");
            f.endpoint(row.key(),endpoint.partition(),profile,endpoint.direction(),endpoint.status(),changed,endpoint.condition());
            var result=f.run();assertEquals(0L,result.counts().get("BOUND"),profile+":"+operand);assertEquals(1L,result.counts().get("UNRESOLVED"),profile+":"+operand);
        }
    }
    @Test void uncertainSingleTargetAndDifferentConditionDomainsStayCandidates()throws Exception {
        for(boolean domainMismatch:List.of(false,true)){
            var f=new Fixture();f.pair(ArchitectureBridges.Profile.B06);var row=f.endpoints.removeLast();var endpoint=ArchitectureBridges.endpoint(row.fields());
            f.endpoint(row.key(),endpoint.partition(),endpoint.profile(),endpoint.direction(),domainMismatch?"RESOLVED":"CANDIDATES",endpoint.descriptor(),new ArchitectureBridges.Condition(domainMismatch?ContentDigest.sha256Utf8("other domain"):f.domain,Map.of(),false));
            var result=f.run();assertEquals(1L,result.counts().get("CANDIDATE"));assertEquals(0L,result.counts().get("BOUND"));
        }
    }
    @Test void incompatibleFeaturesHaveNoExecutableBindingAndRoutingIsContractOnly()throws Exception {
        var f=new Fixture();var profile=ArchitectureBridges.Profile.B02;f.pair(profile);var row=f.endpoints.removeLast();var endpoint=ArchitectureBridges.endpoint(row.fields());var descriptor=new TreeMap<>(endpoint.descriptor());descriptor.remove("base");
        f.endpoint(row.key(),endpoint.partition(),profile,endpoint.direction(),endpoint.status(),descriptor,endpoint.condition());assertEquals(1L,f.run().counts().get("CONTRACT_ONLY"));
        f.endpoints.clear();f.endpoint("B02.client","client",profile,"REQUIRES","RESOLVED",descriptor,new ArchitectureBridges.Condition(f.domain,Map.of("feature","on"),false));
        f.endpoint("B02.server","server",profile,"PROVIDES","RESOLVED",descriptor,new ArchitectureBridges.Condition(f.domain,Map.of("feature","off"),false));assertEquals(1L,f.run().counts().get("INFEASIBLE"));
    }
    @Test void missingEndpointsBrokenSiblingAndOmittedCensusRemainInAccounting()throws Exception {
        var f=new Fixture();f.pair(ArchitectureBridges.Profile.B03);f.partition("broken","python","FAILED");f.endpoints.removeLast();
        var result=f.run();assertEquals(1L,result.counts().get("UNRESOLVED"));assertEquals(3,result.partitions());assertTrue(result.missingCensus()>0);
        var reasons=f.read(result.gaps()).stream().map(row->row.fields().get("reasonCode")).toList();assertTrue(reasons.contains("BRIDGE_CHILD_FAILED"));assertTrue(reasons.contains("BRIDGE_CATEGORY_CENSUS_OMITTED"));
        f.requirements.clear();result=f.run();assertTrue(f.read(result.gaps()).stream().anyMatch(row->"BRIDGE_OCCURRENCE_CENSUS_OMITTED".equals(row.fields().get("reasonCode"))));
    }
    @Test void evidenceAddedClosesMissingBindingAndWithdrawalReopensIt()throws Exception {
        var f=new Fixture();f.pair(ArchitectureBridges.Profile.B06);var target=f.endpoints.removeLast();assertEquals(1L,f.run().counts().get("UNRESOLVED"));
        f.endpoints.add(target);var complete=f.run();assertEquals(1L,complete.counts().get("BOUND"));
        var revoked=f.run(Set.of(f.registration.digest()));assertEquals(1L,revoked.counts().get("REOPENED"));assertNotEquals(complete.identity(),revoked.identity());assertEquals(1L,f.run().counts().get("BOUND"));
    }
    @Test void arrivalOrderCannotChooseBetweenCompetingOwners()throws Exception {
        var f=new Fixture();f.pair(ArchitectureBridges.Profile.B01);var endpoint=ArchitectureBridges.endpoint(f.endpoints.getLast().fields());
        f.endpoint("other-provider","server",endpoint.profile(),"PROVIDES","RESOLVED",endpoint.descriptor(),endpoint.condition());
        f.requirements.clear();f.require("required","client",endpoint.profile(),Optional.of("B01.client"),List.of("B01.server","other-provider"));
        var a=f.run();Collections.reverse(f.endpoints);Collections.reverse(f.partitions);var b=f.run();assertEquals(a,b);assertEquals(1L,a.counts().get("AMBIGUOUS"));
    }
    @Test void changedSourceForeignContextAndSurrogateSplitCannotPassByteValidation()throws Exception {
        var f=new Fixture();f.pair(ArchitectureBridges.Profile.B07);var row=f.endpoints.removeLast();var endpoint=ArchitectureBridges.endpoint(row.fields());
        var split=new ArchitectureBridges.Endpoint(endpoint.partition(),endpoint.symbol(),endpoint.profile(),endpoint.direction(),endpoint.status(),endpoint.descriptor(),endpoint.condition(),new ArchitectureBridges.Anchor("src/main",f.source,5,8),endpoint.evidence());
        f.endpoints.add(ArchitectureBridges.row(row.kind(),row.key(),f.scope,split));assertThrows(IOException.class,f::run);
        f.endpoints.removeLast();f.endpoints.add(new SemanticTables.Row(row.kind(),row.key(),ContentDigest.sha256Utf8("foreign"),row.fields()));assertThrows(IOException.class,f::run);
        f.endpoints.removeLast();f.endpoints.add(row);f.content.blobs.put(f.source.digest(),new byte[]{1});assertThrows(IOException.class,f::run);
    }
    @Test void cancellationPolicyChangeAndWorkerBoundsDoNotReturnACompleteResult()throws Exception {
        var f=new Fixture();f.pair(ArchitectureBridges.Profile.B01);var evaluator=new ArchitectureBridges(f.content,f.tables,65536,1);var input=f.input();
        assertThrows(IOException.class,()->evaluator.reconcile(input,new Index(),Set.of(),()->f.policy,()->true));assertThrows(IOException.class,()->evaluator.reconcile(input,new Index(),Set.of(),()->ContentDigest.sha256Utf8("changed"),()->false));
        f.requirements.clear();f.require("oversize","client",ArchitectureBridges.Profile.B01,Optional.of("B01.client"),List.of("B01.server","other"));var oversize=f.input();assertThrows(IOException.class,()->evaluator.reconcile(oversize,new Index(),Set.of(),()->f.policy,()->false));
    }
    @Test void boundedIncomingImpactKeepsQualifiedHopsAndUnknownFrontier()throws Exception{
        var f=new Fixture();f.pair(ArchitectureBridges.Profile.B06);var input=f.input();var result=f.run();var relation=f.read(result.relations()).getFirst();var target=com.evolution.analysis.graph.GraphJson.object(com.evolution.analysis.graph.GraphJson.array(relation.fields().get("targets")).getFirst());
        var publication=new ContentHandle(ContentDigest.sha256Utf8("immutable publication"),10);var queries=new BridgeQueryService((scope,cancelled)->Optional.of(new BridgeQueryService.View(publication,1,input,result)),f.tables);
        var impact=queries.impact(input.scope(),new ContentDigest((String)target.get("identity")),2,10,20,()->false);assertEquals(1,impact.paths().size());assertEquals(List.of("BOUND"),impact.paths().getFirst().states());assertFalse(impact.runtimeBreakageProven());assertTrue(impact.frontier().stream().anyMatch(v->v.startsWith("UNKNOWN_BOUNDARIES:")));
        var stopped=queries.impact(input.scope(),new ContentDigest((String)target.get("identity")),2,10,1,()->false);assertEquals("SCAN_LIMIT",stopped.stop());assertFalse(stopped.frontier().isEmpty());
    }
    @Test void auditCaptureChangesDoNotRenameSymbolsOrSemanticMeanings()throws Exception{
        var f=new Fixture();f.pair(ArchitectureBridges.Profile.B06);var original=f.run();var newCapture=f.content.text("same native facts; another attempt/time receipt");
        for(int i=0;i<f.partitions.size();i++){var row=f.partitions.get(i);var p=ArchitectureBridges.partition(row.fields());f.partitions.set(i,ArchitectureBridges.row(row.kind(),row.key(),row.scope(),new ArchitectureBridges.Partition(p.context(),p.snapshot(),p.root(),p.module(),p.sourceSet(),p.variant(),p.language(),p.state(),p.documents(),Optional.of(newCapture))));}
        for(int i=0;i<f.endpoints.size();i++){var row=f.endpoints.get(i);var e=ArchitectureBridges.endpoint(row.fields());f.endpoints.set(i,ArchitectureBridges.row(row.kind(),row.key(),row.scope(),new ArchitectureBridges.Endpoint(e.partition(),e.symbol(),e.profile(),e.direction(),e.status(),e.descriptor(),e.condition(),e.source(),List.of(newCapture,f.registration))));}
        var other=f.run();assertEquals(original.semanticIdentity(),other.semanticIdentity());assertNotEquals(original.identity(),other.identity());
    }
    @Test void aMissingCompetingCandidateOrUncapturedDescriptorCannotMakeTheOneKnownTargetCertain()throws Exception{
        var f=new Fixture();f.pair(ArchitectureBridges.Profile.B06);f.requirements.clear();f.require("required","client",ArchitectureBridges.Profile.B06,Optional.of("B06.client"),List.of("B06.server","not-yet-captured"));assertEquals(1L,f.run().counts().get("CANDIDATE"));
        f.requirements.clear();f.require("required","client",ArchitectureBridges.Profile.B06,Optional.of("B06.client"),List.of("B06.server"));
        for(int i=0;i<f.endpoints.size();i++){var row=f.endpoints.get(i);var e=ArchitectureBridges.endpoint(row.fields());var descriptor=new TreeMap<>(e.descriptor());descriptor.put("library",ContentDigest.sha256Utf8("uncaptured library").value());f.endpoints.set(i,ArchitectureBridges.row(row.kind(),row.key(),row.scope(),new ArchitectureBridges.Endpoint(e.partition(),e.symbol(),e.profile(),e.direction(),e.status(),descriptor,e.condition(),e.source(),e.evidence())));}
        assertEquals(1L,f.run().counts().get("CANDIDATE"));
    }
    @Test void cursorCountsStayLosslessBeyondJavaScriptIntegerPrecision(){
        var digest=ContentDigest.sha256Utf8("cursor");var cursor=new BridgeQueryService.Cursor(digest,new SemanticTables.Cursor(digest,9007199254740993L,Optional.of("last")));String json=com.evolution.analysis.contract.serialization.CanonicalJson.write(cursor);assertTrue(json.contains("\"rows\":\"9007199254740993\""));assertEquals(cursor,BridgeQueryService.cursor(com.evolution.analysis.graph.GraphJson.read(json,com.evolution.analysis.graph.GraphJson.Limits.defaults())));
    }
    @Test void nativeLogicalNamesArePreservedWithoutHostPathNormalization(){
        var source=new com.evolution.analysis.acquisition.streaming.ContentHandle(ContentDigest.sha256Utf8("source"),6);var anchor=new ArchitectureBridges.Anchor("src/Linux:name\\part.kt",source,Optional.empty());assertEquals("src/Linux:name\\part.kt",anchor.path());assertThrows(IllegalArgumentException.class,()->new ArchitectureBridges.Anchor("../outside",source,Optional.empty()));assertThrows(IllegalArgumentException.class,()->new ArchitectureBridges.Range(0,0));
    }
    @Test void revokedLiveViewCannotEscapeAfterAQueryHasReadItsRows()throws Exception{
        var f=new Fixture();f.pair(ArchitectureBridges.Profile.B06);var input=f.input();var result=f.run();var view=new BridgeQueryService.View(f.capture,1,input,result);var queries=new BridgeQueryService(new BridgeQueryService.Repository(){public Optional<BridgeQueryService.View> load(ContentDigest scope,java.util.function.BooleanSupplier cancelled){return Optional.of(view);}public void check(BridgeQueryService.View ignored,java.util.function.BooleanSupplier cancelled)throws IOException{throw new IOException("BRIDGE_QUERY_STALE_VIEW");}},f.tables);
        assertThrows(IOException.class,()->queries.summary(input.scope(),()->false));assertThrows(IOException.class,()->queries.page(input.scope(),new BridgeQueryService.Selection(BridgeQueryService.Section.RELATIONS,Optional.empty(),Optional.empty(),Optional.empty()),20,Optional.empty(),()->false));assertThrows(IOException.class,()->queries.impact(input.scope(),ContentDigest.sha256Utf8("target"),1,10,10,()->false));
    }
    @Test void namedSearchAndSourcePickerKeepScopeAndRejectForeignContinuations()throws Exception{
        var f=new Fixture();f.pair(ArchitectureBridges.Profile.B06);var input=f.input();var view=new BridgeQueryService.View(f.capture,1,input,f.run());var queries=new BridgeQueryService((scope,cancelled)->Optional.of(view),f.tables);var search=new BridgeQueryService.Selection(BridgeQueryService.Section.PARTITIONS,Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.of("CLIENT"));var page=queries.page(input.scope(),search,1,Optional.empty(),()->false);assertEquals(List.of("client"),page.items().stream().map(SemanticTables.Row::key).toList());assertEquals(Long.toString(view.result().partitions()),page.summary().partitions());
        var source=queries.page(input.scope(),new BridgeQueryService.Selection(BridgeQueryService.Section.DOCUMENTS,Optional.empty(),Optional.of("client"),Optional.empty(),Optional.empty(),Optional.of("src/main")),10,Optional.empty(),()->false);assertEquals(List.of("src/main"),source.items().stream().map(SemanticTables.Row::key).toList());
        assertThrows(IOException.class,()->queries.page(input.scope(),new BridgeQueryService.Selection(BridgeQueryService.Section.PARTITIONS,Optional.empty(),Optional.empty(),Optional.empty()),1,page.continuation(),()->false));
    }
}
