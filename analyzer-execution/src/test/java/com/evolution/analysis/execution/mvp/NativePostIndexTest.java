package com.evolution.analysis.execution.mvp;

import com.evolution.analysis.acquisition.streaming.ContentHandle;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.filesystem.StreamingContentStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Post-build census extension controls only; no native execution or qualification. */
class NativePostIndexTest {
    @TempDir Path temp;
    StreamingContentStore content;
    Map<String,ContentHandle> files;
    Map<String,Object> post;
    List<Map<String,Object>> rows;
    ContentHandle put(Object value)throws IOException {
        return content.put(new ByteArrayInputStream(CanonicalJson.write(value).getBytes(StandardCharsets.UTF_8)),()->false);
    }
    void setup()throws IOException {
        content=new StreamingContentStore(temp);files=new TreeMap<>();
        files.put("native-cohort.json",put(Map.of("fixture","closed original cohort")));
        files.put("index-tool-classes/Tool.class",put(Map.of("fixture","non-executable contract bytes")));
        rows=new ArrayList<>();var outputs=new TreeMap<String,Object>();
        for(String name:new TreeSet<>(NativeJavaCensusFixtureMain.INDEX_CONTEXTS)){
            String path="index/"+name+".json";var ref=put(Map.of("fixture",name));files.put(path,ref);outputs.put(path,ref);
            rows.add(new TreeMap<>(Map.of("name",name,"output",path,"captureState","CAPTURED","exit",0,"captured",ref)));
        }
        post=new TreeMap<>(Map.of("schema","m844-native-post-index:v1","attempt","a".repeat(32),"cohort",files.get("native-cohort.json"),
                "qualified",false,"semanticTrust","PRODUCER_ASSERTION","contexts",rows,"outputs",outputs,
                "toolClasses",Map.of("Tool.class",files.get("index-tool-classes/Tool.class"))));
    }
    Map<String,ContentHandle> validate()throws IOException {
        files.put("native-post-index.json",put(post));
        return NativeJavaCensusFixtureMain.validatePostIndex(content,files,"a".repeat(32));
    }
    @Test void additiveIndexPreservesTheOriginalCohortAndEveryCapturedContext()throws Exception {
        setup();assertEquals(11,validate().size());
        post.put("cohort",put(Map.of("fixture","foreign original bytes")));
        assertThrows(IOException.class,this::validate);
    }
    @Test void anOmittedOrDuplicateContextCannotBecomeAClosedDenominator()throws Exception {
        setup();rows.removeFirst();assertThrows(IOException.class,this::validate);
        setup();rows.set(0,rows.get(1));assertThrows(IOException.class,this::validate);
    }
    @Test void capturedIndexAndExecutedToolBytesAreRechecked()throws Exception {
        setup();files.put("index/protobuf-client.json",put(Map.of("fixture","changed bytes")));
        assertThrows(IOException.class,this::validate);
        setup();files.put("index-tool-classes/Tool.class",put(Map.of("fixture","changed tool")));
        assertThrows(IOException.class,this::validate);
        setup();files.put("index-tool-classes/Unlisted.class",put(Map.of("fixture","unlisted tool")));
        assertThrows(IOException.class,this::validate);
    }
    @Test void aFailedIndexKeepsItsExplicitDispositionWithoutMintingTrust()throws Exception {
        setup();var row=rows.getFirst();String path=(String)row.get("output");
        row.put("captureState","PROCESS_FAILED");row.put("exit",1);row.put("captured",null);
        files.remove(path);((Map<?,?>)post.get("outputs")).remove(path);
        assertEquals(10,validate().size());
        post.put("qualified",true);assertThrows(IOException.class,this::validate);
    }
}
