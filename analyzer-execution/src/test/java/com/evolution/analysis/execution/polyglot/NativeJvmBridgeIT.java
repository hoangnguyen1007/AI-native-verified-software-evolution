package com.evolution.analysis.execution.polyglot;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.filesystem.*;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.polyglot.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Reuses the exact native Java/Scala adapter compilation from the registered
 * JVM fixture. No SDK restoration, class loading or worker execution occurs. */
class NativeJvmBridgeIT {
    @TempDir Path temporary;
    Map<String,Object> object(Path file)throws IOException{try(var input=Files.newInputStream(file)){return GraphJson.object(GraphJson.read(new InputStreamReader(input,StandardCharsets.UTF_8),new GraphJson.Limits(2_000_000,64,2_000_000)));}}
    ContentHandle handle(Object value){var m=GraphJson.object(value);return new ContentHandle(new ContentDigest(GraphJson.string(m.get("digest"))),((java.math.BigDecimal)m.get("size")).longValueExact());}
    ContentHandle put(StreamingContentStore content,Path file)throws IOException{try(var stream=Files.newInputStream(file)){return content.put(stream,()->false);}}
    ContentHandle put(StreamingContentStore content,Object value)throws IOException{return content.put(new ByteArrayInputStream(CanonicalJson.write(value).getBytes(StandardCharsets.UTF_8)),()->false);}
    SemanticTables.Table table(SemanticTables tables,SemanticTables.Kind kind,List<SemanticTables.Row> rows)throws IOException{return tables.write(kind,rows.stream().sorted(Comparator.comparing(SemanticTables.Row::key)).iterator(),()->false);}
    @Test void actualScalaReferenceAndJavaConstructorMatchExactBinaryDescriptorsWithNoHostLoading()throws Exception{
        String configured=System.getProperty("bridge.jvmRoot");assertNotNull(configured,"Explicit retained JVM fixture root is required");Path root=Path.of(configured).toRealPath();var profile=object(root.resolve("profile.json"));var campaign=object(root.resolve("campaign.json"));var setup=object(root.resolve("setup.json"));var off=object(root.resolve("termination-result.json"));assertEquals("Off",off.get("state"));String exportAttempt=GraphJson.string(GraphJson.object(object(root.resolve("export-request.json")).get("request")).get("attempt"));var owner=object(root.resolve("owner-"+exportAttempt+".json"));for(String field:List.of("attempt","vmId","fence","launch"))assertEquals(owner.get(field),off.get(field));assertEquals(setup.get("attempt"),off.get("attempt"));
        var content=new StreamingContentStore(temporary.resolve("cas"));var tables=new SemanticTables(content,65536);assertEquals(handle(profile.get("campaign")),put(content,root.resolve("campaign.json")));var asset=GraphJson.object(campaign.get("adapters"));var bundle=put(content,Path.of(GraphJson.string(asset.get("path"))));assertEquals(handle(asset),bundle);
        var compiled=new TreeMap<String,ContentHandle>();try(var zip=new ZipInputStream(content.open(bundle))){for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry())if(Set.of("evo/polyglot/ScalaEngine$.class","evo/polyglot/NeutralJvm.class").contains(entry.getName())){byte[] bytes=zip.readNBytes(65537);assertTrue(bytes.length<=65536);assertNull(compiled.put(entry.getName(),content.put(new ByteArrayInputStream(bytes),()->false)));}}
        assertEquals(2,compiled.size());var scala=JvmBridgeDescriptors.read(compiled.get("evo/polyglot/ScalaEngine$.class"),content,65536);var javaView=JvmBridgeDescriptors.read(compiled.get("evo/polyglot/NeutralJvm.class"),content,65536);assertEquals(Optional.of("ScalaEngine.scala"),scala.sourceFile());assertEquals(Optional.of("NeutralJvm.java"),javaView.sourceFile());
        var reference=scala.references().stream().filter(m->m.owner().equals("evo/polyglot/NeutralJvm")&&m.name().equals("<init>")&&m.descriptor().equals("(Ljava/nio/file/Path;)V")).findFirst().orElseThrow();var declaration=javaView.declarations().stream().filter(m->JvmBridgeDescriptors.matches(reference,m)).findFirst().orElseThrow();assertEquals("METHOD",reference.kind());
        var sourcePins=GraphJson.object(profile.get("controls"));var sources=new TreeMap<String,ContentHandle>();for(String name:List.of("ScalaEngine.scala","NeutralJvm.java")){String path="polyglot/jvm/"+name;var source=put(content,root.resolve("control-sources").resolve(path));assertEquals(sourcePins.get(path),source.digest().value());sources.put(name,source);}
        // The native compiler runner used this same pinned bundle. Reuse its
        // successful observations without repeating an unchanged campaign.
        var result=object(root.resolve("native-campaign-result.json"));var observed=GraphJson.object(GraphJson.object(result.get("results")).get("scala-multifile"));assertEquals(java.math.BigDecimal.ZERO,observed.get("exit"));
        var inventory=CanonicalJson.sha256(List.of(bundle,sources));var evidenceRoot=EvidenceContext.forSnapshot(SnapshotIdentity.from(RepositoryIdentity.fromCanonicalCoordinate("urn:fixture:registered-jvm-bridge"),inventory));var scope=ArchitectureBridges.scope(evidenceRoot,inventory);var policy=ContentDigest.sha256Utf8("captured-jvm-member-correspondence:1");var generation=put(content,Map.of("bundle",bundle,"sourcePins",sources,"profile",put(content,root.resolve("profile.json"))));var correspondence=put(content,Map.of("reader",JvmBridgeDescriptors.PROVIDER,"reference",reference,"declaration",declaration,"from",scala.binary(),"to",javaView.binary()));
        var descriptor=Map.of("module","registered-jvm-adapters","binaryOwner",reference.owner(),"member",reference.name(),"descriptor",reference.descriptor(),"correspondence",correspondence.digest().value(),"generation",generation.digest().value());
        var partitions=new ArrayList<SemanticTables.Row>();var endpoints=new ArrayList<SemanticTables.Row>();var census=new ArrayList<SemanticTables.Row>();
        for(String language:List.of("java","scala")){var view=language.equals("java")?javaView:scala;String path=language.equals("java")?"NeutralJvm.class":"ScalaEngine$.class";String sourceName=view.sourceFile().orElseThrow();var context=ContentDigest.sha256Utf8("native-jvm:"+language);var documents=table(tables,SemanticTables.Kind.DOCUMENT,List.of(new SemanticTables.Row(SemanticTables.Kind.DOCUMENT,path,context,Map.of("content",view.binary(),"role","generated")),new SemanticTables.Row(SemanticTables.Kind.DOCUMENT,sourceName,context,Map.of("content",sources.get(sourceName),"encoding","UTF-8","role","build-logic"))));
            partitions.add(ArchitectureBridges.row(SemanticTables.Kind.CONTEXT,language,scope,new ArchitectureBridges.Partition(context,evidenceRoot.snapshotIdentity().value(),"native/jvm","adapters","custom","jdk21",language,"COMPLETE",documents,Optional.of(bundle))));
            endpoints.add(ArchitectureBridges.row(SemanticTables.Kind.OBSERVATION,language+".constructor",scope,new ArchitectureBridges.Endpoint(language,ContentDigest.sha256Utf8(language+":"+view.owner()+":constructor"),ArchitectureBridges.Profile.B01,language.equals("scala")?"REQUIRES":"PROVIDES","RESOLVED",descriptor,new ArchitectureBridges.Condition(policy,Map.of(),false),new ArchitectureBridges.Anchor(path,view.binary(),Optional.empty()),List.of(bundle,correspondence,generation,view.binary()))));
            for(var profileId:ArchitectureBridges.Profile.values()){boolean applicable=language.equals("scala")&&profileId==ArchitectureBridges.Profile.B01;census.add(ArchitectureBridges.row(SemanticTables.Kind.CENSUS,language+"."+profileId,scope,new ArchitectureBridges.Requirement(language,profileId,applicable?"APPLICABLE":"NOT_APPLICABLE",applicable?Optional.of("scala.constructor"):Optional.empty(),applicable?List.of("java.constructor"):List.of(),generation,"Registered member-correspondence fixture; no runtime CALLS claim.")));}
        }
        var input=new ArchitectureBridges.Input(evidenceRoot,inventory,policy,table(tables,SemanticTables.Kind.CONTEXT,partitions),table(tables,SemanticTables.Kind.OBSERVATION,endpoints),table(tables,SemanticTables.Kind.CENSUS,census));var publication=new BridgePublicationStore(temporary.resolve("bridges"),content,tables,()->policy,Set::of).commit(input,Optional.empty(),()->false);assertEquals(1L,publication.result().counts().get("BOUND"));assertFalse(publication.result().qualified());
        try(var rows=tables.open(publication.result().relations(),()->false)){SemanticTables.Row row;while((row=rows.next())!=null)if(row.fields().get("state").equals("BOUND"))assertEquals("SYMBOL_CORRESPONDENCE",row.fields().get("relation"));}
        var wrong=new JvmBridgeDescriptors.Member(reference.owner(),reference.name(),"(I)V","METHOD",0);assertFalse(javaView.declarations().stream().anyMatch(m->JvmBridgeDescriptors.matches(wrong,m)));assertEquals(publication,new BridgePublicationStore(temporary.resolve("bridges"),content,tables,()->policy,Set::of).load(scope,()->false).orElseThrow());
    }
}
