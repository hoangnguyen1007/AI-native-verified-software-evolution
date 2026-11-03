package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.polyglot.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BridgePublicationStoreTest {
    @TempDir Path directory;
    final AtomicReference<ContentDigest> policy=new AtomicReference<>(ContentDigest.sha256Utf8("operator policy"));
    final AtomicReference<Set<ContentDigest>> revoked=new AtomicReference<>(Set.of());
    StreamingContentStore content;SemanticTables tables;ContentHandle registration;
    ContentHandle put(String value)throws IOException{return content.put(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)),()->false);}
    SemanticTables.Table table(SemanticTables.Kind kind,List<SemanticTables.Row> rows)throws IOException{return tables.write(kind,rows.stream().sorted(Comparator.comparing(SemanticTables.Row::key)).iterator(),()->false);}
    ArchitectureBridges.Input input()throws Exception{
        content=new StreamingContentStore(directory.resolve("cas"));tables=new SemanticTables(content,65536);var inventory=ContentDigest.sha256Utf8("sealed fixture inventory");var root=EvidenceContext.forSnapshot(SnapshotIdentity.from(RepositoryIdentity.fromCanonicalCoordinate("urn:test:bridge-publication"),inventory));var scope=ArchitectureBridges.scope(root,inventory);
        var source=put("native int add(int a, int b);\n");var capture=put("fixed native metadata");registration=put("independent exact library/ABI registration");var parts=new ArrayList<SemanticTables.Row>();var endpoints=new ArrayList<SemanticTables.Row>();var census=new ArrayList<SemanticTables.Row>();
        for(String key:List.of("java","c","broken")){var context=ContentDigest.sha256Utf8(key);var docs=table(SemanticTables.Kind.DOCUMENT,List.of(new SemanticTables.Row(SemanticTables.Kind.DOCUMENT,"native.api",context,Map.of("content",source,"encoding","UTF-8"))));var part=new ArchitectureBridges.Partition(context,root.snapshotIdentity().value(),".",key,key.equals("c")?"custom":"main","linux-x64",key,key.equals("broken")?"FAILED":"COMPLETE",docs,Optional.of(capture));parts.add(ArchitectureBridges.row(SemanticTables.Kind.CONTEXT,key,scope,part));
            for(var profile:ArchitectureBridges.Profile.values()){boolean applicable=profile==ArchitectureBridges.Profile.B06&&!key.equals("broken");var requirement=new ArchitectureBridges.Requirement(key,profile,applicable?"APPLICABLE":"NOT_APPLICABLE",applicable&&key.equals("java")?Optional.of("java.add"):Optional.empty(),applicable&&key.equals("java")?List.of("c.add"):List.of(),registration,"fixed fixture applicability");if(key.equals("c")&&applicable)requirement=new ArchitectureBridges.Requirement(key,profile,"NOT_APPLICABLE",Optional.empty(),List.of(),registration,"provider has no importing occurrence");census.add(ArchitectureBridges.row(SemanticTables.Kind.CENSUS,key+"."+profile,scope,requirement));}
            if(!key.equals("broken")){var descriptor=new TreeMap<String,String>();for(String operand:ArchitectureBridges.Profile.B06.operands)descriptor.put(operand,"exact:"+operand);for(String operand:ArchitectureBridges.Profile.B06.byteOperands())descriptor.put(operand,registration.digest().value());endpoints.add(ArchitectureBridges.row(SemanticTables.Kind.OBSERVATION,key+".add",scope,new ArchitectureBridges.Endpoint(key,ContentDigest.sha256Utf8(key+".add"),ArchitectureBridges.Profile.B06,key.equals("java")?"REQUIRES":"PROVIDES","RESOLVED",descriptor,new ArchitectureBridges.Condition(ContentDigest.sha256Utf8("fixture-domain"),Map.of(),false),new ArchitectureBridges.Anchor("native.api",source,0,27),List.of(capture,registration))));}
        }
        return new ArchitectureBridges.Input(root,inventory,policy.get(),table(SemanticTables.Kind.CONTEXT,parts),table(SemanticTables.Kind.OBSERVATION,endpoints),table(SemanticTables.Kind.CENSUS,census));
    }
    BridgePublicationStore store()throws IOException{return new BridgePublicationStore(directory.resolve("bridges"),content,tables,policy::get,revoked::get);}
    @Test void catalogIsBoundedAndRetainsUnreadableSiblingsWithoutGrantingAuthority()throws Exception{
        var input=input();var store=store();store.commit(input,Optional.empty(),()->false);var unavailable=ContentDigest.sha256Utf8("unreadable saved analysis");Files.writeString(directory.resolve("bridges").resolve(unavailable.value().substring(7)+".json"),"{");var all=store.queries().catalog(Optional.empty(),20,()->false);assertEquals(2,all.items().size());assertTrue(all.items().stream().anyMatch(item->item.scope().equals(unavailable)&&item.availability().equals("UNAVAILABLE")));assertTrue(all.items().stream().anyMatch(item->item.scope().equals(input.scope())&&item.availability().equals("SAVED_UNVERIFIED")));
        var first=store.queries().catalog(Optional.empty(),1,()->false);assertEquals(1,first.items().size());assertTrue(first.continuation().isPresent());var second=store.queries().catalog(first.continuation(),1,()->false);assertEquals(1,second.items().size());assertFalse(second.continuation().isPresent());assertNotEquals(first.items().getFirst().scope(),second.items().getFirst().scope());
    }
    @Test void coldReplaySqliteJoinAndFilteredPagesKeepTheWholeDenominator()throws Exception{
        var input=input();var published=store().commit(input,Optional.empty(),()->false);assertEquals(1L,published.result().counts().get("BOUND"));assertEquals(0,published.result().missingCensus());
        var coldContent=new StreamingContentStore(directory.resolve("cas"));var coldTables=new SemanticTables(coldContent,65536);var cold=new BridgePublicationStore(directory.resolve("bridges"),coldContent,coldTables,policy::get,revoked::get);var loaded=cold.load(input.scope(),()->false).orElseThrow();assertEquals(published,loaded);
        var query=cold.queries();var selection=new BridgeQueryService.Selection(BridgeQueryService.Section.RELATIONS,Optional.of("B06"),Optional.empty(),Optional.empty());Optional<BridgeQueryService.Cursor> cursor=Optional.empty();var actual=new ArrayList<SemanticTables.Row>();int pages=0;
        do{var page=query.page(input.scope(),selection,1,cursor,()->false);assertEquals("24",page.summary().requirements());actual.addAll(page.items());cursor=page.continuation();assertTrue(++pages<30);}while(cursor.isPresent());assertEquals(3,actual.size());assertTrue(pages>actual.size());assertEquals("WITHHELD",query.summary(input.scope(),()->false).assessment());
        var filtered=query.page(input.scope(),new BridgeQueryService.Selection(BridgeQueryService.Section.GAPS,Optional.of("B06"),Optional.of("broken"),Optional.empty()),50,Optional.empty(),()->false);assertTrue(filtered.items().stream().anyMatch(row->"BRIDGE_CHILD_FAILED".equals(row.fields().get("reasonCode"))));assertTrue(filtered.items().stream().anyMatch(row->"BRIDGE_METHOD_UNQUALIFIED".equals(row.fields().get("reasonCode"))));assertEquals("24",filtered.summary().requirements());
    }
    @Test void liveWithdrawalChangesTheViewRejectsOldCursorAndRestoresNoAuthority()throws Exception{
        var input=input();store().commit(input,Optional.empty(),()->false);var query=store().queries();var selection=new BridgeQueryService.Selection(BridgeQueryService.Section.RELATIONS,Optional.empty(),Optional.empty(),Optional.empty());var page=query.page(input.scope(),selection,1,Optional.empty(),()->false);
        // This fixture intentionally shares its applicability registration:
        // withdrawing it also reopens all 23 formerly justified N/A rows.
        revoked.set(Set.of(registration.digest()));var summary=query.summary(input.scope(),()->false);assertEquals("24",summary.outcomes().get("REOPENED"));assertEquals("0",summary.outcomes().get("BOUND"));assertEquals("0",summary.missingCensus());assertFalse(summary.qualified());assertThrows(IOException.class,()->query.page(input.scope(),selection,1,page.continuation(),()->false));
        assertEquals("SAVED_UNVERIFIED",query.catalog(Optional.empty(),5,()->false).items().getFirst().availability(),"Withdrawn semantics do not make immutable navigation unreadable");
        revoked.set(Set.of());assertEquals("1",query.summary(input.scope(),()->false).outcomes().get("BOUND"));
    }
    @Test void staleParentCancellationAndPolicyChangeCannotReplaceCurrentHead()throws Exception{
        var input=input();var store=store();var first=store.commit(input,Optional.empty(),()->false);assertThrows(IOException.class,()->store.commit(input,Optional.empty(),()->false));assertThrows(IOException.class,()->store.commit(input,Optional.of(first.publication().digest()),()->true));assertEquals(Optional.of(first.publication()),store.head(input.scope()));
        var second=store.commit(input,Optional.of(first.publication().digest()),()->false);assertEquals(2,second.revision());assertEquals(first.result(),second.result());policy.set(ContentDigest.sha256Utf8("new policy"));assertThrows(IOException.class,()->store.load(input.scope(),()->false));
    }
    @Test void missingOrCorruptedEvidenceCannotSurviveColdPublicationReplay()throws Exception{
        var input=input();var store=store();store.commit(input,Optional.empty(),()->false);var digest=registration.digest().value().substring(7);var path=directory.resolve("cas/sha256").resolve(digest.substring(0,2)).resolve(digest.substring(2));
        // Locate only this test-owned immutable object; no production evidence is edited.
        if(!Files.isRegularFile(path)){try(var paths=Files.walk(directory.resolve("cas/sha256"))){path=paths.filter(Files::isRegularFile).filter(p->p.getFileName().toString().equals(digest)||p.getFileName().toString().equals(digest.substring(2))).findFirst().orElseThrow();}}
        Files.writeString(path,"corrupted fixture");assertThrows(IOException.class,()->store.load(input.scope(),()->false));
    }
    @Test void rehashedNewSchemaOrForgedCountsCannotReplaceDomainReplay()throws Exception{
        var input=input();var store=store();var original=store.commit(input,Optional.empty(),()->false);String raw;try(var stream=content.open(original.publication())){raw=new String(stream.readAllBytes(),StandardCharsets.UTF_8);}
        for(String attack:List.of("schema","counts")){var value=com.evolution.analysis.graph.GraphJson.object(com.evolution.analysis.graph.GraphJson.read(raw,com.evolution.analysis.graph.GraphJson.Limits.defaults()));if(attack.equals("schema"))value.put("schema","architecture-bridge-publication:m8.4-v999");else com.evolution.analysis.graph.GraphJson.object(com.evolution.analysis.graph.GraphJson.object(value.get("result")).get("counts")).put("BOUND",999);
            var forged=put(CanonicalJson.write(value));Files.writeString(directory.resolve("bridges").resolve(input.scope().value().substring(7)+".json"),CanonicalJson.write(Map.of("document",forged)));assertThrows(IOException.class,()->store.load(input.scope(),()->false),attack);
        }
    }
    @Test void cancellationAfterTableWritesPreservesHeadAndCanResume()throws Exception{
        var input=input();var store=store();var first=store.commit(input,Optional.empty(),()->false);var sawPending=new java.util.concurrent.atomic.AtomicBoolean();java.util.function.BooleanSupplier cancelled=()->{try(var paths=Files.list(directory.resolve("bridges"))){boolean pending=paths.anyMatch(path->path.getFileName().toString().endsWith(".pending"));if(pending)sawPending.set(true);return pending;}catch(IOException failure){throw new UncheckedIOException(failure);}};
        assertThrows(IOException.class,()->store.commit(input,Optional.of(first.publication().digest()),cancelled));assertTrue(sawPending.get());assertEquals(Optional.of(first.publication()),store.head(input.scope()));assertEquals(2,store.commit(input,Optional.of(first.publication().digest()),()->false).revision());
    }
    @Test void warmPagesRehashTheCompleteReadSetWithoutReemittingTablesAndStillRejectTampering()throws Exception{
        var input=input();store().commit(input,Optional.empty(),()->false);var writes=new java.util.concurrent.atomic.AtomicInteger();
        com.evolution.analysis.acquisition.streaming.RepositoryContentStore tracked=new com.evolution.analysis.acquisition.streaming.RepositoryContentStore(){
            public ContentHandle put(InputStream in,java.util.function.BooleanSupplier cancelled)throws IOException{writes.incrementAndGet();return content.put(in,cancelled);}public InputStream open(ContentHandle handle)throws IOException{return content.open(handle);}public InputStream range(ContentHandle handle,long offset,long bytes)throws IOException{return content.range(handle,offset,bytes);}public boolean verify(ContentHandle handle)throws IOException{return content.verify(handle);}};
        var store=new BridgePublicationStore(directory.resolve("bridges"),tracked,new SemanticTables(tracked,65536),policy::get,revoked::get);var loaded=store.load(input.scope(),()->false).orElseThrow();writes.set(0);
        assertEquals(loaded,store.load(input.scope(),()->false).orElseThrow());assertEquals(0,writes.get(),"A warm read must verify bytes without rerunning semantic projection");
        String hex=registration.digest().value().substring(7);Path object=directory.resolve("cas/sha256").resolve(hex.substring(0,2)).resolve(hex.substring(2));byte[] original=Files.readAllBytes(object);original[0]^=1;Files.write(object,original);
        assertThrows(IOException.class,()->store.load(input.scope(),()->false));
    }
    @Test void freshJvmRecomputesTheSamePublicationWithoutSavedAuthority()throws Exception{
        var input=input();store().commit(input,Optional.empty(),()->false);String expected=CanonicalJson.sha256(store().queries().summary(input.scope(),()->false)).value();
        var executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java");var process=new ProcessBuilder(executable.toString(),"-cp",System.getProperty("java.class.path"),ColdRead.class.getName(),directory.toString(),input.scope().value(),policy.get().value()).redirectErrorStream(true).start();
        if(!process.waitFor(15,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();fail("BRIDGE_FRESH_JVM_REPLAY_TIMEOUT");}String output=new String(process.getInputStream().readNBytes(4097),StandardCharsets.UTF_8).strip();assertEquals(0,process.exitValue(),output);assertEquals(expected,output);
    }
    public static final class ColdRead {
        public static void main(String[] args)throws Exception{var root=Path.of(args[0]);var content=new StreamingContentStore(root.resolve("cas"));var tables=new SemanticTables(content,65536);var policy=new ContentDigest(args[2]);var summary=new BridgePublicationStore(root.resolve("bridges"),content,tables,()->policy,Set::of).queries().summary(new ContentDigest(args[1]),()->false);System.out.print(CanonicalJson.sha256(summary).value());}
    }
}
