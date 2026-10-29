package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.buildmodel.PassiveReadinessDiscovery;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.EvidenceContext;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.polyglot.*;
import com.evolution.analysis.readiness.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real sealed filesystem/readiness integration, with authored native metadata.
 * These controls qualify the data adapter, not Go semantic accuracy. */
class SemanticRepositoryCensusTest {
    @TempDir Path directory;
    record Fixture(StreamingContentStore content,SemanticTables tables,SqliteInventoryStore inventory,ReadinessPlan readiness,SemanticContextCoordinator.Plan plan,LanguageSemanticRequest request,SemanticTables.Table inputs,SemanticTables.Table bindings,SemanticRepositoryCensus producer) implements AutoCloseable{public void close()throws IOException{inventory.close();}}
    ContentHandle put(RepositoryContentStore content,String text)throws IOException{return content.put(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),()->false);}
    Fixture fixture()throws Exception{return fixture(Map.of());}
    Fixture fixture(Map<String,String> extra)throws Exception{
        Path source=Files.createDirectory(directory.resolve("source"));Files.writeString(source.resolve("main.go"),"package app\nfunc Main() {}\n");Files.writeString(source.resolve("hidden.go"),"package app\nfunc Hidden() {}\n");for(var item:extra.entrySet()){var file=source.resolve(item.getKey());Files.createDirectories(file.getParent());Files.writeString(file,item.getValue());}var content=new StreamingContentStore(directory.resolve("cas"));var tables=new SemanticTables(content,65536);var inventory=new SqliteInventoryStore(directory.resolve("inventory"),content);var repo=RepositoryIdentity.fromCanonicalCoordinate("urn:test:repository-census");var acquired=new StreamingRepositoryAcquirer(content,inventory).acquire(source,"sealed",new StreamingAcquisitionRequest(repo,Optional.empty(),true,List.of()),()->false);var manifest=acquired.manifest().orElseThrow();var readiness=new ReadinessPlanner(List.of(new PassiveReadinessDiscovery())).plan("sealed",inventory,content,ReadinessPlanner.Policy.comprehensive(),()->false);assertFalse(readiness.contexts().isEmpty());var build=readiness.contexts().stream().filter(c->c.sourceRoots().contains(".")).findFirst().orElseThrow();var poly=new PolyglotInventory().scan("sealed",inventory,content,tables,()->false);var empty=tables.write(SemanticTables.Kind.DOCUMENT,Collections.emptyIterator(),()->false);var census=tables.write(SemanticTables.Kind.CENSUS,Collections.emptyIterator(),()->false);var resolution=put(content,"authored ordered resolution");var context=EvidenceContext.forSnapshot(SnapshotIdentity.from(repo,manifest.inventory()));var policy=ContentDigest.sha256Utf8("unqualified fixture policy");var prototype=new LanguageSemanticRequest(context,manifest.inventory(),build.root(),build.module(),build.sourceSet(),build.variant(),"go","1.24",empty,census,resolution,new VersionedIdentifier("authored.go-inputs","v1"),ContentDigest.sha256Utf8("tool"),policy,ContentDigest.sha256Utf8("work"));var nativeInputs=new ArrayList<SemanticTables.Row>();ContentHandle main=null;
        for(String path:List.of("hidden.go","main.go")){var bytes=put(content,Files.readString(source.resolve(path)));if(path.equals("main.go"))main=bytes;nativeInputs.add(new SemanticTables.Row(SemanticTables.Kind.DOCUMENT,"/guest/"+path,prototype.scope(),Map.of("origin","INVENTORY","source",LogicalPath.utf8(path).rawBase64(),"content",bytes,"lineage",resolution)));}
        var documents=tables.write(SemanticTables.Kind.DOCUMENT,List.of(new SemanticTables.Row(SemanticTables.Kind.DOCUMENT,"/guest/main.go",prototype.scope(),Map.of("content",main))).iterator(),()->false);var questions=new ArrayList<SemanticTables.Row>();for(String category:LanguageProfile.requiredCategories())questions.add(new SemanticTables.Row(SemanticTables.Kind.CENSUS,category,prototype.scope(),Map.of("document","/guest/main.go","category",category)));census=tables.write(SemanticTables.Kind.CENSUS,questions.iterator(),()->false);var request=new LanguageSemanticRequest(context,manifest.inventory(),build.root(),build.module(),build.sourceSet(),build.variant(),"go","1.24",documents,census,resolution,prototype.provider(),prototype.tool(),policy,prototype.workPolicy());var plan=new SemanticContextCoordinator(content,tables).plan(context,poly,List.of(new SemanticContextCoordinator.Selection("main",request)).iterator(),policy,()->false);var inputs=tables.write(SemanticTables.Kind.DOCUMENT,nativeInputs.iterator(),()->false);var producer=new SemanticRepositoryCensus(content,tables);var frame=producer.capture(readiness,build,request,inputs,put(content,"authored captured native invocation"),()->false);var bindings=tables.write(SemanticTables.Kind.CONTEXT,List.of(new SemanticTables.Row(SemanticTables.Kind.CONTEXT,"main",readiness.identity(),Map.of("request",request.identity(),"capture",frame))).iterator(),()->false);return new Fixture(content,tables,inventory,readiness,plan,request,inputs,bindings,producer);
    }
    SemanticRootCensus.Input produce(Fixture f,SemanticTables.Table bindings)throws IOException{try(var index=new SqliteSemanticCensusIndex(directory.resolve("producer-"+UUID.randomUUID()+".sqlite"),65536)){return f.producer().produce("sealed",f.inventory(),f.readiness(),f.plan(),bindings,index,ContentDigest.sha256Utf8("validator"),()->false);}}
    @Test void actualSealedInventoryAndReadinessRetainOmittedNativeDocumentAcrossPublication()throws Exception{
        try(var f=fixture()){
            var input=produce(f,f.bindings());assertEquals(f.plan().inventory().entries().rows(),input.sources().rows());assertEquals(2,input.memberships().rows());var coordinator=new SemanticContextCoordinator(f.content(),f.tables());var supplied=new SemanticContextCoordinator.RepositoryInputs("sealed",f.inventory(),f.readiness(),f.bindings(),ContentDigest.sha256Utf8("validator"),()->new SqliteSemanticCensusIndex(directory.resolve("root-"+UUID.randomUUID()+".sqlite"),65536));var root=coordinator.runRepository(f.plan(),supplied,r->Optional.empty(),(r,o)->Map.of(),r->Optional.empty(),(p,i)->{assertEquals(input,i);return Optional.empty();},Map.of(),Set.of(),f.plan()::policy,c->{},()->false);var store=new SemanticPublicationStore(directory.resolve("publications"),f.content(),f.tables());store.commit(f.plan(),root,Optional.empty(),f.plan()::policy,()->false);var cold=new SemanticPublicationStore(directory.resolve("publications"),f.content(),new SemanticTables(f.content(),65536));var summary=cold.queries(f.plan()::policy).summary(f.plan().identity(),()->false);assertEquals("1",summary.accounting().get("documents"));assertEquals("2",summary.accounting().get("memberships"));assertEquals("1",summary.accounting().get("omittedNativeDocuments"));assertFalse(summary.complete());var gaps=cold.queries(f.plan()::policy).rootGaps(f.plan().identity(),50,Optional.empty(),()->false);assertTrue(gaps.items().stream().anyMatch(g->g.get("reasonCode").equals("ROOT_NATIVE_DOCUMENT_OMITTED")));assertTrue(gaps.items().stream().anyMatch(g->g.get("reasonCode").equals("ROOT_CONTEXT_ENUMERATION_OPEN")));
        }
    }
    @Test void absentCapturedContextRemainsUnknownInsteadOfBeingInferredFromSourceRoots()throws Exception{
        try(var f=fixture()){var empty=f.tables().write(SemanticTables.Kind.CONTEXT,Collections.emptyIterator(),()->false);var input=produce(f,empty);assertEquals(0,input.memberships().rows());assertTrue(input.contexts().rows()>0);try(var reader=f.tables().open(input.contexts(),()->false)){SemanticTables.Row row;while((row=reader.next())!=null)assertNotEquals("SELECTED",row.fields().get("disposition"));}}
    }
    Map<String,Object> read(Fixture f,ContentHandle handle)throws IOException{try(var input=f.content().open(handle)){return GraphJson.object(GraphJson.read(new InputStreamReader(input,StandardCharsets.UTF_8),GraphJson.Limits.defaults()));}}
    ContentHandle handle(Object value){var map=GraphJson.object(value);return new ContentHandle(new ContentDigest(GraphJson.string(map.get("digest"))),((java.math.BigDecimal)map.get("size")).longValueExact());}
    SemanticTables.Table binding(Fixture f,ContentHandle frame)throws IOException{return f.tables().write(SemanticTables.Kind.CONTEXT,List.of(new SemanticTables.Row(SemanticTables.Kind.CONTEXT,"main",f.readiness().identity(),Map.of("request",f.request().identity(),"capture",frame))).iterator(),()->false);}
    @Test void changedToolWorkPolicyAndInputScopeCannotReuseCapturedContext()throws Exception{
        try(var f=fixture()){
            ContentHandle captured;try(var reader=f.tables().open(f.bindings(),()->false)){captured=handle(reader.next().fields().get("capture"));}var original=read(f,captured);
            for(String field:List.of("tool","workPolicy","scope","inventory")){var forged=new TreeMap<>(original);forged.put(field,ContentDigest.sha256Utf8("changed-"+field));var frame=put(f.content(),CanonicalJson.write(forged));assertThrows(IOException.class,()->produce(f,binding(f,frame)),field);}
            var bad=f.tables().write(SemanticTables.Kind.DOCUMENT,List.of(new SemanticTables.Row(SemanticTables.Kind.DOCUMENT,"/guest/main.go",ContentDigest.sha256Utf8("foreign-scope"),Map.of("origin","INVENTORY","source",LogicalPath.utf8("main.go").rawBase64(),"content",f.request().resolution(),"lineage",f.request().resolution()))).iterator(),()->false);var frame=f.producer().capture(f.readiness(),f.readiness().contexts().getFirst(),f.request(),bad,f.request().resolution(),()->false);assertThrows(IOException.class,()->produce(f,binding(f,frame)));
        }
    }
    @Test void droppingDerivedMembershipCannotEraseIndependentCapturedInputList()throws Exception{
        try(var f=fixture()){
            var produced=produce(f,f.bindings());var empty=f.tables().write(SemanticTables.Kind.DOCUMENT,Collections.emptyIterator(),()->false);var forged=new SemanticRootCensus.Input(produced.sources(),produced.contexts(),empty,produced.validatorTool());
            try(var index=new SqliteSemanticCensusIndex(directory.resolve("omitted-native.sqlite"),65536)){var result=new SemanticRootCensus(f.content(),f.tables()).reconcile(f.plan(),forged,index,Optional.empty(),Set.of(),f.plan()::policy,()->false);assertFalse(result.structurallyClosed());assertEquals(2,result.accounting().expectedDocuments());assertEquals(0,result.accounting().memberships());assertEquals(1,result.accounting().omittedNativeDocuments());assertEquals(2,result.accounting().omittedNativeMemberships());boolean found=false;try(var gaps=f.tables().open(result.gaps(),()->false)){SemanticTables.Row gap;while((gap=gaps.next())!=null)found|=gap.fields().get("reasonCode").equals("ROOT_NATIVE_MEMBERSHIP_OMITTED");}assertTrue(found);}
        }
    }
    @Test void generatedAndExternalInputsStayCountedEvenWhenExporterOmitsThem()throws Exception{
        try(var f=fixture()){
            var rows=new ArrayList<SemanticTables.Row>();try(var reader=f.tables().open(f.inputs(),()->false)){SemanticTables.Row row;while((row=reader.next())!=null)rows.add(row);}for(String origin:List.of("GENERATED","EXTERNAL")){var values=new TreeMap<String,Object>();values.put("origin",origin);values.put("source",null);values.put("content",put(f.content(),"package app\n// authored "+origin+" metadata control\n"));values.put("lineage",f.request().resolution());rows.add(new SemanticTables.Row(SemanticTables.Kind.DOCUMENT,"native:"+origin,f.request().scope(),values));}
            var inputs=f.tables().write(SemanticTables.Kind.DOCUMENT,rows.stream().sorted(Comparator.comparing(SemanticTables.Row::key)).iterator(),()->false);var frame=f.producer().capture(f.readiness(),f.readiness().contexts().getFirst(),f.request(),inputs,f.request().resolution(),()->false);var produced=produce(f,binding(f,frame));
            try(var index=new SqliteSemanticCensusIndex(directory.resolve("derived-inputs.sqlite"),65536)){var result=new SemanticRootCensus(f.content(),f.tables()).reconcile(f.plan(),produced,index,Optional.empty(),Set.of(),f.plan()::policy,()->false);assertEquals(4,result.accounting().memberships());assertEquals(3,result.accounting().omittedNativeDocuments());assertEquals(1,result.accounting().generated());assertEquals(1,result.accounting().external());assertFalse(result.qualified());}
        }
    }
    @Test void extensionlessScriptIsDiscoveredAndUnselectedSourceStaysInTheDenominator()throws Exception{
        try(var f=fixture(Map.of("tools/run","#!/usr/bin/env python3\nprint(1)\n"))){
            assertTrue(f.readiness().modules().stream().anyMatch(m->m.languages().contains("python")));
            var produced=produce(f,f.bindings());boolean retained=false;try(var reader=f.tables().open(produced.sources(),()->false)){SemanticTables.Row row;while((row=reader.next())!=null)if(row.key().equals(LogicalPath.utf8("tools/run").key())){assertEquals("REQUIRED",row.fields().get("disposition"));retained=true;}}assertTrue(retained);
        }
    }

    @Test void successfulDataProviderRetainsUnqualifiedAnswersAndRootOmissionIndependently()throws Exception{
        try(var f=fixture()){
            var rows=new ArrayList<SemanticTables.Row>();try(var reader=f.tables().open(f.request().census(),()->false)){SemanticTables.Row row;while((row=reader.next())!=null)rows.add(new SemanticTables.Row(SemanticTables.Kind.OBSERVATION,row.key(),f.request().scope(),Map.of("obligation",row.identity(),"status","UNSUPPORTED","targets",List.of(),"reason","AUTHORED_SEMANTICS_UNAVAILABLE")));}
            var output=f.tables().write(SemanticTables.Kind.OBSERVATION,rows.iterator(),()->false);var inputs=new SemanticContextCoordinator.RepositoryInputs("sealed",f.inventory(),f.readiness(),f.bindings(),ContentDigest.sha256Utf8("validator"),()->new SqliteSemanticCensusIndex(directory.resolve("healthy-"+UUID.randomUUID()+".sqlite"),65536));var root=new SemanticContextCoordinator(f.content(),f.tables()).runRepository(f.plan(),inputs,r->Optional.of((request,cancel)->output),(r,o)->Map.of(),r->Optional.empty(),(p,i)->Optional.empty(),Map.of(),Set.of(),f.plan()::policy,c->{},()->false);assertEquals(0,root.failed());assertEquals(0,root.missingProviders());assertFalse(root.complete());
            var store=new SemanticPublicationStore(directory.resolve("healthy-publications"),f.content(),f.tables());store.commit(f.plan(),root,Optional.empty(),f.plan()::policy,()->false);var page=store.queries(f.plan()::policy).answers(f.plan().identity(),"main","CURRENT",50,Optional.empty(),()->false);assertEquals(12,page.items().size());assertTrue(page.items().stream().allMatch(r->GraphJson.object(r.get("fields")).get("state").equals("NARROWED")));assertEquals("1",page.summary().accounting().get("omittedNativeDocuments"));
        }
    }

}
