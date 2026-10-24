package com.evolution.analysis.execution.javac;

import java.io.IOException;
import java.io.*;
import java.lang.module.ModuleDescriptor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.*;
import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.filesystem.StreamingContentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NativeContextValidatorTest {
    @TempDir Path temp;
    StreamingContentStore content;
    ContentHandle put(byte[] bytes)throws IOException{return content.put(new ByteArrayInputStream(bytes),()->false);}
    ContentHandle put(String text)throws IOException{return put(text.getBytes(StandardCharsets.UTF_8));}
    Map<String,ContentHandle> materials()throws Exception {
        content=new StreamingContentStore(temp.resolve("cas"));var files=new TreeMap<String,ContentHandle>();
        files.put("release",put("JAVA_RUNTIME_VERSION=\"21.0.1\"\nJAVA_VERSION=\"21.0.1\"\nIMPLEMENTOR=\"fixture\"\nMODULES=\"java.base\"\n"));
        for(String name:List.of("bin/java","bin/javac","lib/modules"))files.put(name,put("passive fixture bytes "+name));
        byte[] descriptor;try(var reader=ModuleLayer.boot().configuration().findModule("java.base").orElseThrow().reference().open();var in=reader.open("module-info.class").orElseThrow()){descriptor=in.readAllBytes();}
        files.put("jmods/java.base.jmod",zip("classes/module-info.class",descriptor,true));
        files.put("lib/ct.sym",zip("L/system-modules","java.base\n".getBytes(StandardCharsets.UTF_8),false));return files;
    }
    ContentHandle zip(String name,byte[] bytes,boolean jmod)throws IOException {
        var out=new ByteArrayOutputStream();if(jmod)out.write(new byte[]{'J','M',1,0});
        try(var zip=new ZipOutputStream(out)){zip.putNextEntry(new ZipEntry(name));zip.write(bytes);zip.closeEntry();}return put(out.toByteArray());
    }
    final List<ContentHandle> images=List.of(new ContentHandle(ContentDigest.sha256Utf8("pinned-image"),12));
    ContentHandle manifest(Map<String,ContentHandle> files)throws IOException {
        var rows=files.entrySet().stream().map(e->Map.of("path",e.getKey(),"digest",e.getValue().digest(),"size",e.getValue().size())).toList();
        return put(CanonicalJson.write(Map.of("schema","native-jdk-image-materials:m8.4-v1","image",images,"nativeHome","/jdk","vendor","fixture","version","21.0.1","platformSelection","UNESTABLISHED","qualified",false,"files",rows)));
    }
    private Map<String,ModuleDescriptor> modules(){
        return Map.of("java.base",ModuleDescriptor.newModule("java.base").exports("java.lang").build(),
            "java.api",ModuleDescriptor.newModule("java.api").exports("api").requires("jdk.helper").build(),
            "jdk.helper",ModuleDescriptor.newModule("jdk.helper").packages(Set.of("internal")).build(),
            "jdk.provider",ModuleDescriptor.newModule("jdk.provider").packages(Set.of("provider")).build(),
            "jdk.qualified",ModuleDescriptor.newModule("jdk.qualified").exports("qualified",Set.of("java.api")).build(),
            "jdk.incubator.example",ModuleDescriptor.newModule("jdk.incubator.example").exports("incubator").build());
    }
    @Test void inventoryIsNotTheUnnamedCompilerModuleGraph()throws Exception {
        var modules=modules();
        var selected=NativeContextValidator.selectedModules(modules,modules.keySet(),Set.of("jdk.incubator.example"));
        assertEquals(Set.of("java.base","java.api","jdk.helper"),selected);
        assertThrows(UnsupportedOperationException.class,()->selected.clear());
    }
    @Test void missingObservableDependencyCannotBeNormalizedToAClosedGraph()throws Exception {
        var incomplete=new HashMap<>(modules());incomplete.remove("jdk.helper");
        assertThrows(IOException.class,()->NativeContextValidator.selectedModules(incomplete,Set.of("java.base","java.api"),Set.of()));
        assertThrows(IOException.class,()->NativeContextValidator.selectedModules(modules(),Set.of("java.base","foreign.module"),Set.of()));
    }
    @Test void requiredInternalModulesAreRetainedOutsideReleaseDefaultRootCandidates()throws Exception {
        assertEquals(Set.of("java.base","java.api","jdk.helper"),NativeContextValidator.selectedModules(modules(),Set.of("java.base","java.api"),Set.of()));
    }
    @Test void imageReleaseDescriptorsAndFullMaterialDenominatorAreValidatedWithoutExecution()throws Exception {
        var files=materials();var manifest=manifest(files);var validator=new NativeContextValidator(content,()->false);
        var checked=validator.platform(manifest,images,files);assertEquals(Set.of("java.base"),checked.selectedModules());assertEquals("21.0.1",checked.runtime());
        assertEquals(false,checked.report().get("semanticAttestation"));
        assertThrows(IOException.class,()->validator.platform(manifest,List.of(files.get("release")),files));
        var missing=new TreeMap<>(files);missing.remove("bin/javac");assertThrows(IOException.class,()->validator.platform(manifest,images,missing));
        var foreign=new TreeMap<>(files);foreign.put("lib/ct.sym",zip("L/system-modules","foreign.module\n".getBytes(StandardCharsets.UTF_8),false));
        assertThrows(IOException.class,()->validator.platform(manifest(foreign),images,foreign));
        assertThrows(IOException.class,()->new NativeContextValidator(content,()->true).platform(manifest,images,files));
    }
    @Test void changedOrMalformedModuleBytesCannotBorrowTheImagePin()throws Exception {
        var files=materials();var validator=new NativeContextValidator(content,()->false);var original=manifest(files);
        var malformed=new TreeMap<>(files);malformed.put("jmods/java.base.jmod",put("not a JMOD"));
        assertThrows(IOException.class,()->validator.platform(original,images,malformed));
        assertThrows(IOException.class,()->validator.platform(manifest(malformed),images,malformed));
        assertThrows(IOException.class,()->NativeContextValidator.resolutionFlags(new byte[16]));
    }
    @Test void centralDirectoryAndEntryCrcAreRequiredEvenWhenLocalZipHeadersLookValid()throws Exception {
        var files=materials();var ref=files.get("jmods/java.base.jmod");byte[] original;try(var in=content.open(ref)){original=in.readAllBytes();}
        var validator=new NativeContextValidator(content,()->false);var truncated=new TreeMap<>(files);
        truncated.put("jmods/java.base.jmod",put(Arrays.copyOf(original,original.length-22)));
        assertThrows(IOException.class,()->validator.platform(manifest(truncated),images,truncated));
        byte[] badCrc=original.clone();int central=-1;for(int i=0;i<badCrc.length-4;i++)if(badCrc[i]=='P'&&badCrc[i+1]=='K'&&badCrc[i+2]==1&&badCrc[i+3]==2){central=i;break;}
        assertTrue(central>0);badCrc[central+16]^=1;var corrupt=new TreeMap<>(files);corrupt.put("jmods/java.base.jmod",put(badCrc));
        var error=assertThrows(IOException.class,()->validator.platform(manifest(corrupt),images,corrupt));assertTrue(error.getMessage().contains("ARCHIVE_CRC_OR_SIZE"));
    }
}
