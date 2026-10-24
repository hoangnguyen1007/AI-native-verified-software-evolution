package com.evolution.analysis.javaparser;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.execution.javac.NativeContextValidator;
import com.evolution.analysis.filesystem.StreamingContentStore;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NativeFixtureOwnershipTest {
    @TempDir Path temp;
    private static final RepositoryIdentity REPO=RepositoryIdentity.fromCanonicalCoordinate("urn:evo:fixture:ownership");
    private ContentHandle put(RepositoryContentStore content,Object value)throws IOException{return content.put(new ByteArrayInputStream(CanonicalJson.write(value).getBytes(StandardCharsets.UTF_8)),()->false);}
    record Data(StreamingContentStore content,Map<String,Object> raw,Map<String,Object> post,ContentHandle cohort,ContentHandle binary){}
    private Data data(boolean successful,boolean duplicate)throws IOException {
        var content=new StreamingContentStore(temp);var source=put(content,Map.of("fixture","source bytes"));var binary=put(content,Map.of("fixture","opaque output bytes"));
        var stage=Map.<String,Object>of("argv",List.of("/usr/bin/javac","-d","/work/attempt/model/classes","/inputs/C.java"),"state",successful?"COMPLETED":"FAILED","exit",successful?0:1);
        var raw=Map.<String,Object>of("schema","m844-authored-native-cohort:v1","attempt","attempt","stages",duplicate?List.of(stage,stage):List.of(stage),"outputs",List.of(Map.of("path","model/classes/C.class","digest",binary.digest(),"size",binary.size())));
        var post=Map.<String,Object>of("attempt","attempt","contexts",List.of(Map.of("name","model","sourceInputs",Map.of("/inputs/C.java",source))));return new Data(content,wire(raw),wire(post),put(content,raw),binary);
    }
    private Map<String,Object> wire(Object value){return GraphJson.object(GraphJson.read(CanonicalJson.write(value),GraphJson.Limits.defaults()));}
    private NativeFixtureOwnership ownership(Data d)throws IOException{return new NativeFixtureOwnership(REPO,d.post(),d.cohort(),d.raw(),d.content());}
    @Test void onlyTheExactSuccessfulCompilerOutputTreeHasProjectOwnership()throws Exception {
        var d=data(true,false);var owner=ownership(d);var exact=new NativeContextValidator.Slot("/work/attempt/model/classes",NativeContextValidator.Slot.Kind.DIRECTORY,Map.of("C.class",d.binary()));
        assertEquals(Optional.of(owner.module("model").identity()),owner.owner(exact));
        assertTrue(owner.owner(new NativeContextValidator.Slot("/external/model/classes",exact.kind(),exact.files())).isEmpty());
        assertThrows(IOException.class,()->owner.owner(new NativeContextValidator.Slot(exact.path(),exact.kind(),Map.of())));
        var changed=put(d.content(),Map.of("fixture","changed bytes"));assertThrows(IOException.class,()->owner.owner(new NativeContextValidator.Slot(exact.path(),exact.kind(),Map.of("C.class",changed))));
    }
    @Test void failedOrAmbiguousCompilerStagesCannotEstablishOwnership()throws Exception {
        var failed=data(false,false);assertThrows(IOException.class,()->ownership(failed).owner(new NativeContextValidator.Slot("/work/attempt/model/classes",NativeContextValidator.Slot.Kind.DIRECTORY,Map.of("C.class",failed.binary()))));
        assertThrows(IOException.class,()->ownership(data(true,true)));
    }
    @Test void parsedMetadataMustBeTheActualCapturedCohort()throws Exception {
        var d=data(true,false);var altered=new TreeMap<>(d.raw());altered.put("attempt","other");var alteredPost=new TreeMap<>(d.post());alteredPost.put("attempt","other");
        assertThrows(IOException.class,()->new NativeFixtureOwnership(REPO,alteredPost,d.cohort(),altered,d.content()));
    }
}
