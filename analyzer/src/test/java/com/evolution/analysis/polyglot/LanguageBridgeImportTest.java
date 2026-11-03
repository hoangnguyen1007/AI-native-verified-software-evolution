package com.evolution.analysis.polyglot;

import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LanguageBridgeImportTest {
    @Test void capturedLanguageFactsReachTheRootWithoutBecomingInteropAbsenceProofs()throws Exception{
        var f=new NativeSemanticCaptureTest.Fixture();var capture=NativeSemanticCapture.read(f.memory.text(f.raw),f.tables,f.memory,8192,()->false);var part=new LanguageBridgeImport(f.memory,f.tables,8192).read("native",f.request,capture,new ArchitectureBridgesTest.Index(),()->false);
        var input=new BridgeAssembly(f.tables,20).assemble(f.request.context(),f.request.inventory(),f.request.policy(),List.of(part).iterator(),new ArchitectureBridgesTest.Index(),()->false);var result=new ArchitectureBridges(f.memory,f.tables,8192,20).reconcile(input,new ArchitectureBridgesTest.Index(),Set.of(),f.request::policy,()->false);
        assertEquals(8L,result.counts().get("UNRESOLVED"));assertEquals(0,result.missingCensus());assertFalse(result.complete());var queries=new BridgeQueryService((scope,cancelled)->Optional.of(new BridgeQueryService.View(capture.export(),1,input,result)),f.tables);
        assertEquals(1,queries.page(input.scope(),new BridgeQueryService.Selection(BridgeQueryService.Section.FACTS,Optional.empty(),Optional.of("native"),Optional.empty()),20,Optional.empty(),()->false).items().size());
        assertEquals(1,queries.page(input.scope(),new BridgeQueryService.Selection(BridgeQueryService.Section.FACTS,Optional.of("A01"),Optional.of("native"),Optional.empty()),20,Optional.empty(),()->false).items().size());
        assertEquals(1,queries.page(input.scope(),new BridgeQueryService.Selection(BridgeQueryService.Section.NATIVE_GAPS,Optional.of("A01"),Optional.of("native"),Optional.empty(),Optional.of("doc")),20,Optional.empty(),()->false).items().size());
        assertThrows(IOException.class,()->new LanguageBridgeImport(f.memory,f.tables,8192).read("native",f.request,capture,new ArchitectureBridgesTest.Index(),()->true));
    }
    @Test void changedNativeMeaningInvalidatesRootSemanticIdentityEvenWithoutAnInteropEndpoint()throws Exception{
        var f=new NativeSemanticCaptureTest.Fixture();var identities=new ArrayList<com.evolution.analysis.contract.common.ContentDigest>();
        for(String status:List.of("UNRESOLVED","UNSUPPORTED")){var capture=NativeSemanticCapture.read(f.memory.text(f.raw.replace("UNRESOLVED",status)),f.tables,f.memory,8192,()->false);var fragment=new LanguageBridgeImport(f.memory,f.tables,8192).read("native",f.request,capture,new ArchitectureBridgesTest.Index(),()->false);var input=new BridgeAssembly(f.tables,20).assemble(f.request.context(),f.request.inventory(),f.request.policy(),List.of(fragment).iterator(),new ArchitectureBridgesTest.Index(),()->false);identities.add(new ArchitectureBridges(f.memory,f.tables,8192,20).reconcile(input,new ArchitectureBridgesTest.Index(),Set.of(),f.request::policy,()->false).semanticIdentity());}
        assertNotEquals(identities.getFirst(),identities.getLast());
    }

}
