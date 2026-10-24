package com.evolution.analysis.execution.mvp;

import com.evolution.analysis.acquisition.streaming.ContentHandle;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.filesystem.StreamingContentStore;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CoreMvpBuildPipelineTest {
    @TempDir Path root;
    static final String ATTEMPT="ac681704-11fb-493b-a826-68cedd9eea01",VM="c982d6fe-20f9-4a1b-af83-5d5854b8c04b",NAME="Generated.java";
    static final byte[] SOURCE="package fixture; public final class Generated {}\n".getBytes(StandardCharsets.UTF_8);
    static ContentHandle handle(byte[] raw){return new ContentHandle(ContentDigest.sha256(raw),raw.length);}
    static void json(Path file,Object value)throws IOException{Files.writeString(file,CanonicalJson.write(value),StandardOpenOption.CREATE_NEW);}
    static String base64(String value){return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));}
    class Fake implements CoreMvpBuildPipeline.Launcher {
        int launches,observations;boolean failed,wrongOutput,running,changed;
        public ContentDigest fingerprint(){return ContentDigest.sha256Utf8(changed?"changed":"registered-launcher");}
        public void launch(Path dir)throws IOException{
            launches++;if(failed)throw new IOException("NATIVE_FAILED");
            var request=GraphJson.object(CoreMvpBuildPipeline.read(dir.resolve("export-request.json"),65536).get("request"));
            String attempt=ATTEMPT.replace("-","");byte[] raw=wrongOutput?"wrong output".getBytes(StandardCharsets.UTF_8):SOURCE;
            var output=handle(raw);Path capture=Files.createDirectory(dir.resolve("capture"));Files.createDirectory(capture.resolve("objects"));
            Files.write(capture.resolve("objects").resolve(output.digest().value().substring(7)),raw);
            var row=new TreeMap<String,Object>();row.put("path",base64(NAME));row.put("kind","FILE");row.put("size",raw.length);row.put("mode",384);row.put("blocks",0);row.put("links",1);
            row.put("content",output);row.put("linkTarget",null);row.put("linkGroup",null);row.put("disposition","PRESENT");row.put("reason","");
            byte[] manifest=(CanonicalJson.write(row)+"\n").getBytes(StandardCharsets.UTF_8);Files.write(capture.resolve("entries.ndjson"),manifest);
            byte[] scope=CanonicalJson.write(Map.of("schema","guest-output-scope:m8.4-v1","nonce",request.get("nonce"),"work",base64("/work/"+attempt),
                "budget",Map.of("maxRowBytes",65536,"maxObjectBytes",134217728,"maxCaptureBytes",536870912))).getBytes(StandardCharsets.UTF_8);Files.write(capture.resolve("scope.json"),scope);
            json(capture.resolve("collector-result.json"),Map.ofEntries(Map.entry("schema","guest-output-census:m8.4-v2"),Map.entry("attempt",attempt),Map.entry("nonce",request.get("nonce")),
                Map.entry("entries",1),Map.entry("logicalBytes",raw.length),Map.entry("capturedBytes",raw.length),Map.entry("pending",0),Map.entry("rejected",0),Map.entry("inventoryClosed",true),Map.entry("captureClosed",true),
                Map.entry("manifest",handle(manifest)),Map.entry("scope",handle(scope)),Map.entry("trust","PRODUCER_ASSERTION")));
            json(dir.resolve("native-result.json"),Map.of("attempt",attempt,"buildExit",0,"consumerExit",0,"outputs",Map.of(NAME,output)));
            json(dir.resolve("owner-"+attempt+".json"),Map.of("vmId",VM));
        }
        public Map<String,Object> observeOff(Path dir){observations++;return Map.of("state",running?"Running":"Off","attempt",ATTEMPT,"launch",ContentDigest.sha256Utf8("launch").value(),"fence",java.math.BigDecimal.ONE,"vmId",VM);}
    }
    Fake setup()throws Exception{
        json(root.resolve("setup.json"),Map.of("acceptanceScope","CORE_MVP","approvedBy","HUMAN","attempt",ATTEMPT));
        json(root.resolve("provision-request.json"),Map.of("attempt",ATTEMPT,"workspace",root.toRealPath().toString(),"launch",ContentDigest.sha256Utf8("launch"),"fence",1));return new Fake();
    }
    CoreMvpBuildPipeline pipeline(Fake fake)throws IOException{return new CoreMvpBuildPipeline(new StreamingContentStore(root.resolve("cas")),fake,Map.of(NAME,ContentDigest.sha256(SOURCE)));}
    @Test void launcherFeedsOpaqueExportCasAndDurableReplayWithoutRerunningTheBuild()throws Exception{
        var fake=setup();var first=pipeline(fake).run(root);var replay=pipeline(fake).run(root);
        assertEquals(first,replay);assertEquals(1,fake.launches);assertEquals(1,fake.observations);
        assertArrayEquals(SOURCE,Files.readAllBytes(root.resolve("generated/Generated.java")));
        var store=new StreamingContentStore(root.resolve("cas"));assertTrue(store.verify(handle(SOURCE)));
        try(var input=store.open(first)){
            var report=GraphJson.object(GraphJson.read(new InputStreamReader(input,StandardCharsets.UTF_8),GraphJson.Limits.defaults()));
            assertEquals("CAPTURED_NATIVE_OUTPUTS",report.get("status"));assertEquals("CORE_MVP",report.get("scope"));
            assertEquals("PRODUCER_ASSERTION",report.get("semanticTrust"));assertEquals(false,report.get("qualified"));
        }
    }
    @Test void failedLaunchIsNotBlindlyRetriedOrPublished()throws Exception{
        var fake=setup();fake.failed=true;
        assertThrows(IOException.class,()->pipeline(fake).run(root));
        assertEquals("CORE_MVP_RECOVERY_REQUIRED",assertThrows(IOException.class,()->pipeline(fake).run(root)).getMessage());
        assertEquals(1,fake.launches);assertFalse(Files.exists(root.resolve("core-mvp-publication.json")));
    }
    @Test void wrongOutputCannotCloseTheApprovedArtifactRoles()throws Exception{
        var fake=setup();fake.wrongOutput=true;
        assertEquals("CORE_MVP_OUTPUT_DIGEST_MISMATCH",assertThrows(IOException.class,()->pipeline(fake).run(root)).getMessage());
        assertFalse(Files.exists(root.resolve("core-mvp-publication.json")));
    }
    @Test void offObservationAndApprovalAreRequiredForPublication()throws Exception{
        var fake=setup();fake.running=true;
        assertEquals("CORE_MVP_TERMINATION_UNCONFIRMED",assertThrows(IOException.class,()->pipeline(fake).run(root)).getMessage());
        assertFalse(Files.exists(root.resolve("core-mvp-publication.json")));
    }
    @Test void changedLauncherInputsCannotReuseThePublishedReceipt()throws Exception{
        var fake=setup();pipeline(fake).run(root);fake.changed=true;
        assertEquals("CORE_MVP_INPUTS_CHANGED",assertThrows(IOException.class,()->pipeline(fake).run(root)).getMessage());assertEquals(1,fake.launches);
    }
    @Test void evidenceReplayCannotStartANewBuildWhenPublicationIsMissing()throws Exception{
        var fake=setup();assertEquals("CORE_MVP_CAPTURE_REQUIRED",assertThrows(IOException.class,()->pipeline(fake).replay(root)).getMessage());
        assertEquals(0,fake.launches);assertEquals(0,fake.observations);assertFalse(Files.exists(root.resolve("core-mvp-intent.json")));
    }
}
