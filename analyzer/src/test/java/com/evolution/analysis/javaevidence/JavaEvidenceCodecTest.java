package com.evolution.analysis.javaevidence;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JavaEvidenceCodecTest {
    static final String D=ContentDigest.sha256Utf8("fixture").value();
    static Map<String,Object> decision(){return new LinkedHashMap<>(Map.of("schema","java-closure-decision:m8.4-v1","requirement",D,"state","SATISFIED","proofs",List.of(D),"prerequisites",List.of(),"conflicts",List.of(),"supersedes",List.of()));}
    static String valid(){var value=decision();value.put("reason",null);return CanonicalJson.write(value);}
    @Test void strictWireAcceptsCanonicalDecisionAndPreservesIdentity(){
        var result=new JavaEvidenceCodec().read(valid());
        assertEquals(ContentDigest.sha256Utf8(valid()),result.identity());
        assertEquals("SATISFIED",result.fields().get("state"));
        assertThrows(UnsupportedOperationException.class,()->result.fields().put("state","PENDING"));
    }
    @Test void malformedOrForgedShapesCannotCrossTheWire(){
        var codec=new JavaEvidenceCodec();
        for(String bad:List.of(valid().replace("SATISFIED","REOPENED"),valid().replace("\"proofs\":[\""+D+"\"]","\"proofs\":[]"),valid().replace("m8.4-v1","m8.4-v99"),valid().replace("{","{\"extra\":true,"),valid().replace("{","{\"state\":\"PENDING\",")))
            assertThrows(IllegalArgumentException.class,()->codec.read(bad),bad);
    }
    @Test void packagedSchemaMatchesTheReviewedContractAndRejectsNumericCoercion()throws Exception{
        try(var input=JavaEvidenceCodec.class.getResourceAsStream(JavaEvidenceCodec.RESOURCE)){
            var expected=java.nio.file.Path.of(System.getProperty("basedir")).getParent().resolve("docs/architecture/schemas/m8-java-evidence-v1.schema.json");assertArrayEquals(java.nio.file.Files.readAllBytes(expected),input.readAllBytes());
        }
        var codec=new JavaEvidenceCodec();String input="{\"schema\":\"java-evidence-input:m8.4-v1\",\"role\":\"TOOL\",\"content\":{\"digest\":\""+D+"\",\"size\":%s},\"locator\":null,\"provider\":{\"id\":\"test.tool\",\"version\":\"1\"}}";
        for(String size:List.of("-1","1.5","9223372036854775808","true","\"1\""))assertThrows(IllegalArgumentException.class,()->codec.read(input.formatted(size)));
        assertEquals(0,JavaEvidenceRecord.handle(codec.read(input.formatted("0")).fields().get("content")).size());
    }
    @Test void productionCodecAcceptsEveryRegisteredShapeAndRejectsEachMissingOrForeignWireField()throws Exception{
        var codec=new JavaEvidenceCodec();List<String> lines;
        try(var input=getClass().getResourceAsStream("/fixtures/java-evidence-codec-positives.jsonl");var reader=new java.io.BufferedReader(new java.io.InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8))){lines=reader.lines().toList();}
        assertEquals(26,lines.size());var kinds=new HashSet<String>();
        for(int index=0;index<lines.size();index++){
            var record=codec.read(lines.get(index));kinds.add(record.kind());assertEquals(record,codec.read(record.json()));
            if(index>=17)continue; // The generator independently checks every required field in these baseline shapes.
            var foreign=new LinkedHashMap<>(record.fields());foreign.put("unknownField",true);assertThrows(IllegalArgumentException.class,()->codec.create(foreign),record.kind());
            var version=new LinkedHashMap<>(record.fields());version.put("schema",record.text("schema")+"-future");assertThrows(IllegalArgumentException.class,()->codec.create(version),record.kind());
            for(String field:record.fields().keySet()){
                var omitted=new LinkedHashMap<>(record.fields());omitted.remove(field);assertThrows(IllegalArgumentException.class,()->codec.create(omitted),record.kind()+" / "+field);
            }
            for(String field:List.of("kind","origin","status","closure","census","trust","outcome","state","disposition"))if(record.fields().containsKey(field)){
                var future=new LinkedHashMap<>(record.fields());future.put(field,"FUTURE_ENUM_VALUE");assertThrows(IllegalArgumentException.class,()->codec.create(future),record.kind()+" / "+field);
            }
        }
        assertEquals(17,kinds.size());
    }
    @Test void nestedDuplicateKeysInvalidSurrogatesAndOversizedNumericExpansionsAreRejected(){
        var codec=new JavaEvidenceCodec();
        String input="{\"schema\":\"java-evidence-input:m8.4-v1\",\"role\":\"TOOL\",\"content\":{\"digest\":\""+D+"\",\"size\":%s},\"locator\":null,\"provider\":{\"id\":\"test.tool\",\"version\":\"1\"}}";
        for(String number:List.of("1e1000000","-1e100","9223372036854775808","0.0001"))assertThrows(IllegalArgumentException.class,()->codec.read(input.formatted(number)),number);
        assertThrows(IllegalArgumentException.class,()->codec.read(input.formatted("0").replace("\"size\":0","\"size\":0,\"size\":1")));
        assertThrows(IllegalArgumentException.class,()->codec.read(input.formatted("0").replace("test.tool","test.\\ud800")));
        assertThrows(IllegalArgumentException.class,()->codec.read(input.formatted("0").replace("test.tool","test.\\udc00")));
    }
}
