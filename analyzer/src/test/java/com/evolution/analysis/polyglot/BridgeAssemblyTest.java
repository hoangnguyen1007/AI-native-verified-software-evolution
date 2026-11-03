package com.evolution.analysis.polyglot;

import com.evolution.analysis.contract.common.ContentDigest;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BridgeAssemblyTest {
    @Test void indexedJoinReadsMatchingBucketsInsteadOfEveryEndpointPerCall()throws Exception{
        var f=new ArchitectureBridgesTest.Fixture();var condition=new ArchitectureBridges.Condition(f.domain,Map.of(),false);
        for(int i=0;i<1000;i++)f.endpoint("operation."+i,"server",ArchitectureBridges.Profile.B02,"PROVIDES","RESOLVED",Map.of("method","GET","path","/item/"+i),condition);
        for(int i=0;i<500;i++){String key="call."+i;f.endpoint(key,"client",ArchitectureBridges.Profile.B02,"REQUIRES","CANDIDATES",Map.of("method","GET",i%2==0?"path":"pathDigest",i%2==0?"/item/"+i:ContentDigest.sha256Utf8("/item/"+i).value()),condition);f.require(key,"client",ArchitectureBridges.Profile.B02,Optional.of(key),List.of());}
        var index=new CountingIndex();var input=new BridgeAssembly(f.tables,20).assemble(f.root,f.inventory,f.policy,fragments(f).iterator(),index,()->false);
        assertEquals(500,input.requirements().rows());assertTrue(f.read(input.requirements()).stream().allMatch(row->ArchitectureBridges.requirement(row.fields()).candidates().size()==1));
        assertTrue(index.reads<10_000,"Read "+index.reads+" rows for 1,000 endpoints and 500 calls");
        System.out.println("Bridge indexed join: endpoints=1000, calls=500, indexReads="+index.reads+", candidates=500");
    }
    static final class CountingIndex implements SemanticRootCensus.Index {
        final ArchitectureBridgesTest.Index delegate=new ArchitectureBridgesTest.Index();long reads;
        public void put(String space,String key,SemanticTables.Row row)throws IOException{delegate.put(space,key,row);}
        public Optional<SemanticTables.Row> get(String space,String key){reads++;return delegate.get(space,key);}
        public void mark(String space,String key){delegate.mark(space,key);}
        public SemanticRootCensus.Rows rows(String space,boolean unmarked)throws IOException{return counted(delegate.rows(space,unmarked));}
        public SemanticRootCensus.Rows range(String space,String lower,String upper){var values=delegate.spaces.getOrDefault(space,new TreeMap<>()).subMap(lower,true,upper,false).values().iterator();return counted(new SemanticRootCensus.Rows(){public SemanticTables.Row next(){return values.hasNext()?values.next():null;}public void close(){}});}
        private SemanticRootCensus.Rows counted(SemanticRootCensus.Rows source){return new SemanticRootCensus.Rows(){public SemanticTables.Row next()throws IOException{var row=source.next();if(row!=null)reads++;return row;}public void close()throws IOException{source.close();}};}
        public void close(){}
    }
    @Test void localKeyCollisionsRemainDistinctAndCrossContextLiteralCandidatesAreNeverPromoted()throws Exception{
        var f=new ArchitectureBridgesTest.Fixture();var condition=new ArchitectureBridges.Condition(f.domain,Map.of(),false);
        f.endpoint("call","client",ArchitectureBridges.Profile.B02,"REQUIRES","CANDIDATES",Map.of("method","GET","pathDigest",ContentDigest.sha256Utf8("/users").value()),condition);
        f.endpoint("operation","server",ArchitectureBridges.Profile.B02,"PROVIDES","RESOLVED",Map.of("method","GET","path","/users"),condition);
        f.require("category.B02","client",ArchitectureBridges.Profile.B02,Optional.of("call"),List.of("missing"));
        f.require("category.B02","server",ArchitectureBridges.Profile.B02,Optional.empty(),List.of());
        var fragments=fragments(f);var first=new BridgeAssembly(f.tables,20).assemble(f.root,f.inventory,f.policy,fragments.iterator(),new ArchitectureBridgesTest.Index(),()->false);
        assertEquals(2,first.requirements().rows());var requirement=f.read(first.requirements()).stream().map(row->ArchitectureBridges.requirement(row.fields())).filter(row->row.endpoint().isPresent()).findFirst().orElseThrow();assertEquals(2,requirement.candidates().size());
        var result=new ArchitectureBridges(f.content,f.tables,65536,20).reconcile(first,new ArchitectureBridgesTest.Index(),Set.of(),()->f.policy,()->false);assertEquals(0L,result.counts().get("BOUND"));assertEquals(1L,result.counts().get("CANDIDATE"));
        Collections.reverse(fragments);assertEquals(first,new BridgeAssembly(f.tables,20).assemble(f.root,f.inventory,f.policy,fragments.iterator(),new ArchitectureBridgesTest.Index(),()->false));
    }
    @Test void methodMismatchAndHttpPathNormalizationAreNotInvented()throws Exception{
        var f=new ArchitectureBridgesTest.Fixture();var condition=new ArchitectureBridges.Condition(f.domain,Map.of(),false);
        f.endpoint("call","client",ArchitectureBridges.Profile.B02,"REQUIRES","CANDIDATES",Map.of("method","POST","path","/users"),condition);
        f.endpoint("operation","server",ArchitectureBridges.Profile.B02,"PROVIDES","RESOLVED",Map.of("method","GET","path","/users"),condition);f.require("need","client",ArchitectureBridges.Profile.B02,Optional.of("call"),List.of());
        var input=new BridgeAssembly(f.tables,20).assemble(f.root,f.inventory,f.policy,fragments(f).iterator(),new ArchitectureBridgesTest.Index(),()->false);
        assertTrue(ArchitectureBridges.requirement(f.read(input.requirements()).getFirst().fields()).candidates().isEmpty());
    }
    @Test void capturedDocumentPickerUsesTheLogicalTableKey()throws Exception{
        var f=new ArchitectureBridgesTest.Fixture();var input=f.input();var result=f.run();var query=new BridgeQueryService((scope,cancelled)->Optional.of(new BridgeQueryService.View(f.capture,1,input,result)),f.tables);
        var page=query.page(input.scope(),new BridgeQueryService.Selection(BridgeQueryService.Section.DOCUMENTS,Optional.empty(),Optional.of("server"),Optional.empty(),Optional.of("src/main")),20,Optional.empty(),()->false);
        assertEquals(1,page.items().size());assertEquals("src/main",page.items().getFirst().key());
    }
    @Test void foreignRootAndCandidateWorkerOverflowCannotSilentlyPublishAPartialJoin()throws Exception{
        var f=new ArchitectureBridgesTest.Fixture();var condition=new ArchitectureBridges.Condition(f.domain,Map.of(),false);f.endpoint("call","client",ArchitectureBridges.Profile.B02,"REQUIRES","CANDIDATES",Map.of("path","/users"),condition);f.endpoint("a","server",ArchitectureBridges.Profile.B02,"PROVIDES","RESOLVED",Map.of("method","GET","path","/users"),condition);f.endpoint("b","server",ArchitectureBridges.Profile.B02,"PROVIDES","RESOLVED",Map.of("method","POST","path","/users"),condition);f.require("need","client",ArchitectureBridges.Profile.B02,Optional.of("call"),List.of());
        assertEquals("BRIDGE_ASSEMBLY_CANDIDATE_WORKER_REQUIRED",assertThrows(IOException.class,()->new BridgeAssembly(f.tables,1).assemble(f.root,f.inventory,f.policy,fragments(f).iterator(),new ArchitectureBridgesTest.Index(),()->false)).getMessage());
        assertThrows(IOException.class,()->new BridgeAssembly(f.tables,20).assemble(f.root,ContentDigest.sha256Utf8("another inventory"),f.policy,fragments(f).iterator(),new ArchitectureBridgesTest.Index(),()->false));
        assertThrows(IOException.class,()->new BridgeAssembly(f.tables,20).assemble(f.root,f.inventory,f.policy,fragments(f).iterator(),new ArchitectureBridgesTest.Index(),()->true));
    }
    private static List<BridgeAssembly.Fragment> fragments(ArchitectureBridgesTest.Fixture f)throws Exception{
        var result=new ArrayList<BridgeAssembly.Fragment>();for(var partition:f.partitions){String key=partition.key();result.add(new BridgeAssembly.Fragment(partition,f.table(SemanticTables.Kind.OBSERVATION,f.endpoints.stream().filter(row->ArchitectureBridges.endpoint(row.fields()).partition().equals(key)).toList()),f.table(SemanticTables.Kind.CENSUS,f.requirements.stream().filter(row->ArchitectureBridges.requirement(row.fields()).partition().equals(key)).toList())));}return result;
    }
}
