package com.evolution.analysis.javaparser;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.filesystem.StreamingContentStore;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.javaevidence.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NativeEvidenceSigningFixtureTest {
    @TempDir Path temp;
    record Data(StreamingContentStore content,ContentHandle closure,ContentHandle answer,JavaEvidenceTables.Table inputs,Map<String,Object> scope){}
    private ContentHandle put(RepositoryContentStore content,Object value)throws IOException{return content.put(new ByteArrayInputStream(CanonicalJson.write(value).getBytes(StandardCharsets.UTF_8)),()->false);}
    private Data data()throws IOException {
        var store=new StreamingContentStore(temp);var closure=put(store,Map.of("fixture","authored signing boundary"));var answer=put(store,Map.of("answer","authored checks only"));String d=closure.digest().value();
        var scope=Map.<String,Object>of("inventory",d,"baseSnapshot","snapshot:"+d,"readiness",d,"selection",d,"context",d,"logicalScope",d,"module","module:"+d,"sourceSet","main","variant","native-fixture-v1");
        var row=new LinkedHashMap<String,Object>();row.put("schema","java-evidence-input:m8.4-v1");row.put("role","SOURCE");row.put("content",closure);row.put("locator",null);row.put("provider",Map.of("id","fixture.source","version","1"));
        var inputs=new JavaEvidenceTables(store,100000).write("java-evidence-input",List.of(new JavaEvidenceCodec().create(row)).iterator(),()->false);
        return new Data(store,closure,answer,inputs,scope);
    }
    private Map<String,List<ContentHandle>> checks(String question,ContentHandle evidence){var result=new TreeMap<String,List<ContentHandle>>();JavaEvidenceAuthority.requiredChecks(question).forEach(name->result.put(name,List.of(evidence)));return result;}
    @Test void bothQuestionsIssueOpaqueClaimsAndReplayWithTheSamePolicy()throws Exception {
        var data=data();var first=new NativeEvidenceSigningFixture(data.content());var second=new NativeEvidenceSigningFixture(data.content());assertEquals(first.policy(),second.policy());
        for(String question:List.of("java.resolution-context","java.semantic-occurrence")){
            var issued=first.issue(data.scope(),question,data.closure().digest().value(),data.closure(),data.answer(),data.inputs(),checks(question,data.answer()));
            assertTrue(issued.verified().current());assertEquals(question,issued.authenticated().expected().predicate().id());
            assertEquals(issued.verified().identity(),second.replay(issued.envelope()).verified().identity());
        }
    }
    @Test void missingChecksAndProductionScopesCannotBeSigned()throws Exception {
        var data=data();var fixture=new NativeEvidenceSigningFixture(data.content());var checks=checks("java.semantic-occurrence",data.answer());checks.remove("TARGET_MAPPING");
        assertThrows(IOException.class,()->fixture.issue(data.scope(),"java.semantic-occurrence","site",data.closure(),data.answer(),data.inputs(),checks));
        var production=new TreeMap<>(data.scope());production.put("variant","baseline");
        assertThrows(IOException.class,()->fixture.issue(production,"java.resolution-context","context",data.closure(),data.answer(),data.inputs(),checks("java.resolution-context",data.answer())));
    }
    @Test void substitutedSignatureAndChangedInputAreRejected()throws Exception {
        var data=data();var fixture=new NativeEvidenceSigningFixture(data.content());var issued=fixture.issue(data.scope(),"java.resolution-context","context",data.closure(),data.answer(),data.inputs(),checks("java.resolution-context",data.answer()));
        Map<String,Object> raw;try(var in=data.content().open(issued.envelope())){raw=new TreeMap<>(GraphJson.object(GraphJson.read(new String(in.readAllBytes(),StandardCharsets.UTF_8),GraphJson.Limits.defaults())));}
        raw.put("validatorSignature",raw.get("controllerSignature"));var forged=put(data.content(),raw);assertThrows(IOException.class,()->fixture.replay(forged));
        String hex=data.closure().digest().value().substring(7);Files.writeString(temp.resolve("sha256").resolve(hex.substring(0,2)).resolve(hex.substring(2)),"changed fixture input");
        assertThrows(IOException.class,()->issued.verified().current());assertThrows(IOException.class,()->fixture.replay(issued.envelope()));
    }
}
