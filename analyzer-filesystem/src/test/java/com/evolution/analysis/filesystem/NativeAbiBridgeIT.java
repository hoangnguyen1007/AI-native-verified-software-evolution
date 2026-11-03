package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.polyglot.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit first-party native fixture. No analyzed repository code is loaded. */
class NativeAbiBridgeIT {
    @TempDir Path directory;
    byte[] invoke(Path script)throws Exception{
        var python=System.getProperty("bridge.python");assertNotNull(python,"Explicit Windows x64 Python path is required; no SDK is installed automatically");var executable=Path.of(python).toRealPath();assertTrue(Files.isRegularFile(executable));
        var process=new ProcessBuilder(executable.toString(),"-I",script.toString()).directory(directory.toFile()).start();
        if(!process.waitFor(10,TimeUnit.SECONDS)){process.destroyForcibly();fail("BRIDGE_AUTHORED_NATIVE_FIXTURE_TIMEOUT");}
        byte[] output=process.getInputStream().readNBytes(16385),error=process.getErrorStream().readNBytes(1024);assertEquals(0,process.exitValue(),new String(error,StandardCharsets.UTF_8));assertTrue(output.length<16385);return output;
    }
    ContentHandle put(StreamingContentStore store,byte[] bytes)throws IOException{return store.put(new ByteArrayInputStream(bytes),()->false);}
    SemanticTables.Table table(SemanticTables tables,SemanticTables.Kind kind,List<SemanticTables.Row> rows)throws IOException{return tables.write(kind,rows.stream().sorted(Comparator.comparing(SemanticTables.Row::key)).iterator(),()->false);}
    @Test void realRegisteredPythonNativeAbiReachesPublicationReplayAndRejectsWrongOperands()throws Exception{
        byte[] source;try(var stream=getClass().getResourceAsStream("/bridge-native.py")){source=Objects.requireNonNull(stream).readAllBytes();}var script=directory.resolve("bridge-native.py");Files.write(script,source);byte[] observed=invoke(script);assertArrayEquals(observed,invoke(script),"Fixed native observation excludes PID/address/time from canonical output");
        var nativeResult=GraphJson.object(GraphJson.read(new String(observed,StandardCharsets.UTF_8),GraphJson.Limits.defaults()));assertEquals(true,nativeResult.get("positive"));assertEquals(true,nativeResult.get("missingLibraryRejected"));assertEquals(true,nativeResult.get("missingSymbolRejected"));
        var content=new StreamingContentStore(directory.resolve("cas"));var tables=new SemanticTables(content,65536);var capture=put(content,observed);var sourceHandle=put(content,source);var inventory=sourceHandle.digest();var root=EvidenceContext.forSnapshot(SnapshotIdentity.from(RepositoryIdentity.fromCanonicalCoordinate("urn:fixture:python-winapi-bridge"),inventory));var scope=ArchitectureBridges.scope(root,inventory);var policy=ContentDigest.sha256Utf8("authored-python-winapi-registration:1");
        var registration=put(content,CanonicalJson.write(List.of("python.ctypes-winapi:1",nativeResult.get("runtime"),nativeResult.get("adapter"),sourceHandle)).getBytes(StandardCharsets.UTF_8));ContentHandle library;try(var stream=Files.newInputStream(Path.of(System.getenv("SystemRoot"),"System32","kernel32.dll"))){library=content.put(stream,()->false);}assertEquals(nativeResult.get("library"),library.digest().value());
        var descriptor=new TreeMap<String,String>();for(String key:List.of("library","symbol","signature","abi","callingConvention"))descriptor.put(key,GraphJson.string(nativeResult.get(key)));descriptor.put("registration",registration.digest().value());
        var parts=new ArrayList<SemanticTables.Row>();var endpoints=new ArrayList<SemanticTables.Row>();var census=new ArrayList<SemanticTables.Row>();String text=new String(source,StandardCharsets.UTF_8);int start=text.indexOf("target = kernel.GetCurrentProcessId"),end=start+"target = kernel.GetCurrentProcessId".length();
        for(String key:List.of("native","python")){var context=ContentDigest.sha256Utf8("registered-winapi:"+key);var documents=table(tables,SemanticTables.Kind.DOCUMENT,List.of(new SemanticTables.Row(SemanticTables.Kind.DOCUMENT,"bridge-native.py",context,Map.of("content",sourceHandle,"encoding","UTF-8","role","test"))));parts.add(ArchitectureBridges.row(SemanticTables.Kind.CONTEXT,key,scope,new ArchitectureBridges.Partition(context,root.snapshotIdentity().value(),".",key,"test","windows-x64",key,"COMPLETE",documents,Optional.of(capture))));
            endpoints.add(ArchitectureBridges.row(SemanticTables.Kind.OBSERVATION,key+".pid",scope,new ArchitectureBridges.Endpoint(key,ContentDigest.sha256Utf8(key+":GetCurrentProcessId"),ArchitectureBridges.Profile.B06,key.equals("python")?"REQUIRES":"PROVIDES","RESOLVED",descriptor,new ArchitectureBridges.Condition(policy,Map.of(),false),new ArchitectureBridges.Anchor("bridge-native.py",sourceHandle,start,end),List.of(capture,registration,library))));
            for(var profile:ArchitectureBridges.Profile.values()){boolean applicable=key.equals("python")&&profile==ArchitectureBridges.Profile.B06;census.add(ArchitectureBridges.row(SemanticTables.Kind.CENSUS,key+"."+profile,scope,new ArchitectureBridges.Requirement(key,profile,applicable?"APPLICABLE":"NOT_APPLICABLE",applicable?Optional.of("python.pid"):Optional.empty(),applicable?List.of("native.pid"):List.of(),sourceHandle,"fixed first-party registration applicability")));}
        }
        var input=new ArchitectureBridges.Input(root,inventory,policy,table(tables,SemanticTables.Kind.CONTEXT,parts),table(tables,SemanticTables.Kind.OBSERVATION,endpoints),table(tables,SemanticTables.Kind.CENSUS,census));var publication=new BridgePublicationStore(directory.resolve("bridges"),content,tables,()->policy,Set::of).commit(input,Optional.empty(),()->false);assertEquals(1L,publication.result().counts().get("BOUND"));assertEquals(publication,new BridgePublicationStore(directory.resolve("bridges"),new StreamingContentStore(directory.resolve("cas")),tables,()->policy,Set::of).load(scope,()->false).orElseThrow());
        for(String wrong:List.of("library","signature","abi","callingConvention","registration")){var revised=new ArrayList<>(endpoints);var target=ArchitectureBridges.endpoint(revised.removeFirst().fields());var operands=new TreeMap<>(descriptor);operands.put(wrong,ArchitectureBridges.Profile.B06.byteOperands().contains(wrong)?ContentDigest.sha256Utf8("wrong bytes").value():"wrong:"+wrong);revised.add(ArchitectureBridges.row(SemanticTables.Kind.OBSERVATION,"native.pid",scope,new ArchitectureBridges.Endpoint(target.partition(),target.symbol(),target.profile(),target.direction(),target.status(),operands,target.condition(),target.source(),target.evidence())));var negative=new ArchitectureBridges.Input(root,inventory,policy,input.partitions(),table(tables,SemanticTables.Kind.OBSERVATION,revised),input.requirements());try(var index=SqliteSemanticCensusIndex.temporary(directory.resolve("work"),65536)){var result=new ArchitectureBridges(content,tables,65536,10).reconcile(negative,index,Set.of(),()->policy,()->false);assertEquals(0L,result.counts().get("BOUND"),wrong);assertEquals(1L,result.counts().get("UNRESOLVED"),wrong);}}
        assertFalse(publication.result().qualified());
    }
}
