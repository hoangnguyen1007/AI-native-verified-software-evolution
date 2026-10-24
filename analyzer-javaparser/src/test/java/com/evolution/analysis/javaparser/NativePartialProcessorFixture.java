package com.evolution.analysis.javaparser;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.filesystem.StreamingContentStore;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.javaevidence.*;
import com.evolution.analysis.readiness.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Real failed processor capture. Present bytes never imply complete generation. */
final class NativePartialProcessorFixture {
    private final StreamingContentStore content;private final JavaEvidenceTables tables;private final JavaEvidenceCodec codec=new JavaEvidenceCodec();
    private final Map<String,Object> scope;private final ContentHandle cohortHandle;private final Map<String,ContentHandle> files,inputs;private final Map<String,Object> cohort;
    NativePartialProcessorFixture(Path root)throws IOException {
        root=root.toRealPath();content=new StreamingContentStore(root.resolve("native-import-cas"));tables=new JavaEvidenceTables(content,2_000_000);
        var imported=read(handle(GraphJson.read(Files.readString(root.resolve("native-byte-import.json")),GraphJson.Limits.defaults())));if(!Boolean.TRUE.equals(imported.get("nativeSuccess"))||!Boolean.FALSE.equals(imported.get("qualified"))||!"PRODUCER_ASSERTION".equals(imported.get("semanticTrust")))throw new IOException("NATIVE_PROCESSOR_CAPTURE_SCOPE");files=handles(imported.get("files"));cohortHandle=files.get("native-cohort.json");cohort=read(cohortHandle);
        var binding=read(handle(GraphJson.read(Files.readString(root.resolve("native-input-import.json")),GraphJson.Limits.defaults())));inputs=handles(read(handle(binding.get("inputClosure"))).get("files"));
        var repo=RepositoryIdentity.fromCanonicalCoordinate("urn:evo:fixture:m844-partial-processor:v1");var module=ModuleDescriptor.create(repo,"partialprocessor/client","partial processor client");var bytes=inputs.get("source/partialprocessor/UsePartial.java");var document=SourceDocument.create(repo,module,"partialprocessor/UsePartial.java",bytes.digest(),SourceClassification.MAIN);var snapshot=RepositorySnapshot.create(repo,Optional.empty(),false,List.of(SnapshotFile.from(document)),List.of(document));
        var inventory=new InventoryManifest(InventoryManifest.SCHEMA,repo,CanonicalJson.sha256(snapshot.files()),snapshot.contentDigest(),1,1,bytes.size(),0,Optional.of(snapshot.identity()),Optional.of(snapshot.contentDigest()));var context=ReadinessPlanner.context("partial-client","partial-root","main","native-pipeline-fixture-v1",List.of("partialprocessor"),Map.of("capture",cohortHandle.digest().value()),List.of(),inventory,true);
        var selection=new BuildContextSelection(BuildContextSelection.SCHEMA,Map.of(),List.of(context.id()),List.of(context.id()),List.of(),List.of());var plan=new ReadinessPlan(ReadinessPlan.SCHEMA,inventory,selection,List.of(new ReadinessPlan.Root("partial-root",module.path(),ReadinessPlan.Family.PLAIN,List.of(),List.of())),List.of(new ReadinessPlan.Module("partial-client","partial-root",module.path(),"partial-root",List.of("java"),List.of(),List.of())),List.of(context),List.of(),List.of(),new ReadinessPlan.OwnershipSummary(1,Map.of("OWNED",1L),put(Map.of("source",bytes))));scope=JavaEvidenceScopes.scope(plan,context,module);
    }
    JavaGeneratedOverlay.Result verify()throws IOException {
        var stages=GraphJson.array(cohort.get("stages")).stream().map(GraphJson::object).toList();var failed=stages.stream().filter(s->"partial-processor-failure".equals(s.get("name"))).findFirst().orElseThrow();var healthy=stages.stream().filter(s->"custom-consumer-after-processor-failure".equals(s.get("name"))).findFirst().orElseThrow();
        if(((Number)failed.get("exit")).intValue()!=1||((Number)healthy.get("exit")).intValue()!=0)throw new IOException("NATIVE_PROCESSOR_STAGE_EXPECTATIONS");
        var source=files.get("partialprocessor/generated/fixture/GeneratedBeforeFailure.java");if(source==null||files.containsKey("partialprocessor/client/classes/fixture/UsePartial.class"))throw new IOException("NATIVE_PROCESSOR_PARTIAL_OUTPUT");
        String diagnostic=text(files.get("logs/partial-processor-failure.log"));if(!diagnostic.contains("EVO_EXPECTED_PARTIAL_PROCESSOR_FAILURE"))throw new IOException("NATIVE_PROCESSOR_DIAGNOSTIC");
        String version=text(files.get("logs/javac-version.log")).strip();if(!version.startsWith("javac 21."))throw new IOException("NATIVE_PROCESSOR_COMPILER_VERSION");
        var closure=put(Map.of("inputs",inputs,"stage",failed));var gap=JavaEvidenceGaps.create(scope,JavaEvidenceGaps.Reason.GENERATOR_CLOSURE_OPEN,cohortHandle.digest(),source.digest());
        var inputRows=new ArrayList<JavaEvidenceRecord>();for(var entry:inputs.entrySet())inputRows.add(record("java-evidence-input","role",entry.getKey().startsWith("materials/")?"MATERIAL":entry.getKey().startsWith("tooling/")?"TOOL":"SOURCE","content",entry.getValue(),"locator",null,"provider",new VersionedIdentifier("native.processor-fixture","1")));
        var lineage=record("java-generation-lineage","scope",scope,"generator",new VersionedIdentifier("processor.fixture.partial-processor",inputs.get("source/partialprocessor/PartialProcessor.java").digest().value()),"compiler",new VersionedIdentifier("compiler.javac",version.substring(6)),"compilerApplicability","APPLICABLE","compilerApplicabilityProof",null,"toolchain",CanonicalJson.sha256(version),"recipe",inputs.get("source/recipe.py").digest(),"configuration",CanonicalJson.sha256(failed),"environment",CanonicalJson.sha256(stages.stream().filter(s->"java-version".equals(s.get("name"))).findFirst().orElseThrow()),"nativeResolution",CanonicalJson.sha256(failed.get("argv")),"inputs",table("java-evidence-input",inputRows).wire(),"outputCensus",cohortHandle,"inputClosure",closure.digest(),"closure","OPEN","gaps",List.of(gap.gapIdentity()));
        var rows=new ArrayList<JavaEvidenceRecord>();rows.add(output("partialprocessor/generated/fixture/GeneratedBeforeFailure.java",source,"JAVA_SOURCE","RETAINED",lineage.identity()));rows.add(output("partialprocessor/client/classes/fixture/UsePartial.class",null,"CLASS","ABSENT",lineage.identity()));
        return new JavaGeneratedOverlay(content,2_000_000).importOverlay(scope,cohortHandle,table("java-generation-lineage",List.of(lineage)),table("java-output-row",rows),Map.of(),true,100,()->false);
    }
    private JavaEvidenceRecord output(String path,ContentHandle bytes,String category,String disposition,ContentDigest lineage){var row=record("java-output-row","key",lineage,"scope",scope,"role","partial-native-output","expectation","REQUIRED","locator",Map.of("container",cohortHandle,"members",List.of(),"path",LogicalPath.utf8(path)),"kind","FILE","content",bytes,"lineage",lineage,"rootProof",null,"category",category,"disposition",disposition,"reason","GENERATOR_CLOSURE_OPEN");var fields=new TreeMap<>(row.fields());fields.put("key",JavaGeneratedOverlay.outputKey(row));return codec.create(fields);}
    private JavaEvidenceRecord record(String kind,Object... pairs){var fields=new TreeMap<String,Object>();fields.put("schema",kind+":m8.4-v1");for(int i=0;i<pairs.length;i+=2)fields.put((String)pairs[i],pairs[i+1]);return codec.create(fields);}
    private JavaEvidenceTables.Table table(String kind,List<JavaEvidenceRecord> rows)throws IOException{return tables.write(kind,rows.stream().sorted(JavaEvidenceTables::compare).iterator(),()->false);}
    private ContentHandle put(Object value)throws IOException{return tables.putCanonical(value,()->false);}
    private String text(ContentHandle ref)throws IOException{if(ref==null||!content.verify(ref)||ref.size()>1000000)throw new IOException("NATIVE_PROCESSOR_REFERENCE");try(var in=content.open(ref)){return new String(in.readNBytes(1000001),StandardCharsets.UTF_8);}}
    private Map<String,Object> read(ContentHandle ref)throws IOException{return GraphJson.object(GraphJson.read(text(ref),GraphJson.Limits.defaults()));}
    private static ContentHandle handle(Object value){return JavaEvidenceRecord.handle(value);}
    private static Map<String,ContentHandle> handles(Object value){var refs=new TreeMap<String,ContentHandle>();GraphJson.object(value).forEach((k,v)->refs.put(k,handle(v)));return refs;}
}
