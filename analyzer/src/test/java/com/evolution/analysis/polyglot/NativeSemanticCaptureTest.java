package com.evolution.analysis.polyglot;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.EvidenceContext;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic transport controls only; these bytes cannot qualify a real native language engine. */
class NativeSemanticCaptureTest {
    static class Fixture {
        final SemanticTablesTest.Memory memory=new SemanticTablesTest.Memory();final SemanticTables tables=new SemanticTables(memory,8192);
        final ContentHandle source;final LanguageSemanticRequest request;final String raw;
        Fixture()throws Exception{
            source=memory.text("opaque protocol-control bytes, not a compiler output");var inventory=ContentDigest.sha256Utf8("synthetic protocol inventory");
            var context=EvidenceContext.forSnapshot(SnapshotIdentity.from(RepositoryIdentity.fromCanonicalCoordinate("urn:test:native-protocol"),inventory));var emptyDoc=tables.write(SemanticTables.Kind.DOCUMENT,List.<SemanticTables.Row>of().iterator(),()->false);var emptyCensus=tables.write(SemanticTables.Kind.CENSUS,List.<SemanticTables.Row>of().iterator(),()->false);
            var prototype=new LanguageSemanticRequest(context,inventory,"root","module","main","baseline","protocolfixture","0",emptyDoc,emptyCensus,memory.text("[]"),new VersionedIdentifier("frontend.protocol-fixture","0"),ContentDigest.sha256Utf8("fixture tool"),ContentDigest.sha256Utf8("unqualified-policy"),ContentDigest.sha256Utf8("worker"));
            var doc=new SemanticTables.Row(SemanticTables.Kind.DOCUMENT,"doc",prototype.scope(),Map.of("content",source));var expected=new SemanticTables.Row(SemanticTables.Kind.CENSUS,"obligation",prototype.scope(),Map.of("document","doc","category","A01"));
            var observed=new SemanticTables.Row(SemanticTables.Kind.OBSERVATION,"obligation",prototype.scope(),Map.of("obligation",expected.identity().value(),"status","UNRESOLVED","targets",List.of()));
            var documents=tables.write(SemanticTables.Kind.DOCUMENT,List.of(doc).iterator(),()->false);var census=tables.write(SemanticTables.Kind.CENSUS,List.of(expected).iterator(),()->false);
            request=new LanguageSemanticRequest(context,inventory,"root","module","main","baseline","protocolfixture","0",documents,census,prototype.resolution(),prototype.provider(),prototype.tool(),prototype.policy(),prototype.workPolicy());
            var header=Map.of("schema","language-capture-result:m8.4-v1","scope",request.scope(),"language",request.language(),"dialect",request.dialect(),"provider",request.provider(),"tool",request.tool(),"trust","PRODUCER_ASSERTION");
            var end=Map.of("schema","language-capture-end:m8.4-v1","scope",request.scope(),"documents",1,"census",1,"observations",1,"diagnostics",List.of());
            raw=java.util.stream.Stream.of(header,doc,expected,observed,end).map(CanonicalJson::write).collect(java.util.stream.Collectors.joining("\n","","\n"));
        }
    }
    @Test void neutralFutureLanguageTransportCanReplayOnlyItsExactCapturedContext()throws Exception{
        var f=new Fixture();var capture=NativeSemanticCapture.read(f.memory.text(f.raw),f.tables,f.memory,8192,()->false);var provider=capture.asProvider(f.request,f.tables,f.memory);
        assertEquals(capture.observations(),provider.analyze(f.request,()->false));assertEquals("protocolfixture",capture.language());
        var foreign=new LanguageSemanticRequest(f.request.context(),f.request.inventory(),"other-root",f.request.module(),f.request.sourceSet(),f.request.variant(),f.request.language(),f.request.dialect(),f.request.documents(),f.request.census(),f.request.resolution(),f.request.provider(),f.request.tool(),f.request.policy(),f.request.workPolicy());
        assertThrows(IOException.class,()->capture.asProvider(foreign,f.tables,f.memory));assertThrows(IOException.class,()->provider.analyze(foreign,()->false));
        f.memory.blobs.remove(f.source.digest());assertEquals("CAPTURED_SEMANTIC_SOURCE_UNAVAILABLE",assertThrows(IOException.class,()->provider.analyze(f.request,()->false)).getMessage());
    }
    @Test void wrongCountsTrustMalformedProviderTailAndCancellationAreRejected()throws Exception{
        var f=new Fixture();
        for(String bad:List.of(f.raw.replace("\"observations\":1","\"observations\":2"),f.raw.replace("PRODUCER_ASSERTION","VERIFIED"),f.raw+"{}\n",f.raw.replace("\"version\":\"0\"","\"extra\":\"0\""),f.raw.replace("\"diagnostics\":[]","\"diagnostics\":{}")))assertThrows(IOException.class,()->NativeSemanticCapture.read(f.memory.text(bad),f.tables,f.memory,8192,()->false));
        assertThrows(IOException.class,()->NativeSemanticCapture.read(f.memory.text(f.raw),f.tables,f.memory,8192,()->true));
    }
    @Test void internalRootAndGapTableKindsCannotBeInjectedIntoTheNativeWorkerWire()throws Exception{
        var f=new Fixture();
        for(var kind:EnumSet.complementOf(EnumSet.of(SemanticTables.Kind.DOCUMENT,SemanticTables.Kind.CENSUS,SemanticTables.Kind.OBSERVATION))){
            var injected=new SemanticTables.Row(kind,"injected",f.request.scope(),Map.of("complete",true));
            int trailer=f.raw.lastIndexOf('{');String bad=f.raw.substring(0,trailer)+CanonicalJson.write(injected)+"\n"+f.raw.substring(trailer);
            assertThrows(IOException.class,()->NativeSemanticCapture.read(f.memory.text(bad),f.tables,f.memory,8192,()->false));
        }
    }
}
