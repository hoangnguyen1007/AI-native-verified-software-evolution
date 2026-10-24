package com.evolution.analysis.javaparser;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.filesystem.StreamingContentStore;
import com.evolution.analysis.javaevidence.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NativePipelineSigningFixtureTest {
    @TempDir Path temp;
    record Data(StreamingContentStore content,ContentHandle closure,ContentHandle answer,JavaEvidenceTables.Table inputs,Map<String,Object> scope){}
    private ContentHandle put(RepositoryContentStore store,Object value)throws IOException{return store.put(new ByteArrayInputStream(CanonicalJson.write(value).getBytes(StandardCharsets.UTF_8)),()->false);}
    private Data data()throws IOException {
        var store=new StreamingContentStore(temp);var closure=put(store,Map.of("fixture","authored authority boundary"));var answer=put(store,Map.of("answer","authored predicate check"));String d=closure.digest().value();
        var scope=Map.<String,Object>of("inventory",d,"baseSnapshot","snapshot:"+d,"readiness",d,"selection",d,"context",d,"logicalScope",d,"module","module:"+d,"sourceSet","main","variant","native-pipeline-fixture-v1");
        var row=new LinkedHashMap<String,Object>();row.put("schema","java-evidence-input:m8.4-v1");row.put("role","SOURCE");row.put("content",closure);row.put("locator",null);row.put("provider",Map.of("id","fixture.source","version","1"));
        var inputs=new JavaEvidenceTables(store,100000).write("java-evidence-input",List.of(new JavaEvidenceCodec().create(row)).iterator(),()->false);return new Data(store,closure,answer,inputs,scope);
    }
    private Map<String,List<ContentHandle>> checks(String question,ContentHandle evidence){var result=new TreeMap<String,List<ContentHandle>>();JavaEvidenceAuthority.requiredChecks(question).forEach(name->result.put(name,List.of(evidence)));return result;}
    @Test void allRegisteredQuestionsReplayButNeitherProductionNorOldFixtureScopesCanBeSigned()throws Exception {
        var data=data();var signer=new NativePipelineSigningFixture(data.content());var replay=new NativePipelineSigningFixture(data.content());
        for(String question:List.of("java.output-bytes","java.generated-lineage","java.resolution-context","java.binary-shape","java.source-binary-correspondence","java.semantic-occurrence","java.generator-not-applicable","java.build-validation")){
            var issued=signer.issue(data.scope(),question,data.closure().digest().value(),data.closure(),data.answer(),data.inputs(),checks(question,data.answer()));
            assertEquals(issued.verified().identity(),replay.replay(issued.envelope()).verified().identity());assertTrue(issued.verified().current());
        }
        for(String variant:List.of("baseline","native-fixture-v1")){var wrong=new TreeMap<>(data.scope());wrong.put("variant",variant);assertThrows(IOException.class,()->signer.issue(wrong,"java.resolution-context","context",data.closure(),data.answer(),data.inputs(),checks("java.resolution-context",data.answer())));}
        var incomplete=checks("java.semantic-occurrence",data.answer());incomplete.remove("TARGET_MAPPING");assertThrows(IOException.class,()->signer.issue(data.scope(),"java.semantic-occurrence","site",data.closure(),data.answer(),data.inputs(),incomplete));
    }
    @Test void reusedWritesStillVerifyTheirBytesAndDoNotRepairCorruptedEvidence()throws Exception {
        var data=data();var signer=new NativePipelineSigningFixture(data.content());var checked=checks("java.resolution-context",data.answer());
        var issued=signer.issue(data.scope(),"java.resolution-context","context",data.closure(),data.answer(),data.inputs(),checked);
        String hex=issued.envelope().digest().value().substring(7);Path path=temp.resolve("sha256").resolve(hex.substring(0,2)).resolve(hex.substring(2));byte[] changed=Files.readAllBytes(path);changed[0]^=1;Files.write(path,changed);
        assertThrows(IOException.class,()->signer.issue(data.scope(),"java.resolution-context","context",data.closure(),data.answer(),data.inputs(),checked));assertArrayEquals(changed,Files.readAllBytes(path));
    }
}
