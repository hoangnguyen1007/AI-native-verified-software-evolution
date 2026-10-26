package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.javaevidence.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class JavaBinaryViewsTest {
    @TempDir Path root;
    static final String D=ContentDigest.sha256Utf8("fixture").value();
    static Map<String,Object> scope(){return Map.of("inventory",D,"baseSnapshot","snapshot:"+D,"readiness",D,"selection",D,"context",D,"logicalScope",D,"module","module:"+D,"sourceSet","main","variant","baseline");}
    static JavaEvidenceRecord slot(ContentHandle original,String kind,Object tree,long ordinal){var fields=new LinkedHashMap<String,Object>();fields.put("schema","java-resolution-slot:m8.4-v1");fields.put("scope",scope());fields.put("space","CLASS_PATH");fields.put("ordinal",ordinal);fields.put("logicalName","captured-module");fields.put("kind",kind);fields.put("original",original);fields.put("tree",tree);fields.put("selectedView",null);fields.put("origin","PROJECT");fields.put("module","module:"+D);fields.put("proof",D);fields.put("disposition","ACCEPTED");fields.put("gaps",List.of());return new JavaEvidenceCodec().create(fields);}
    @Test void aMultiReleaseJarCannotSelectAnAmbientHostRelease()throws Exception{
        var store=new StreamingContentStore(root.resolve("cas"));var manifest=new Manifest();manifest.getMainAttributes().putValue("Manifest-Version","1.0");manifest.getMainAttributes().putValue("Multi-Release","true");
        var bytes=new ByteArrayOutputStream();try(var jar=new JarOutputStream(bytes,manifest)){for(String name:List.of("pkg/C.class","META-INF/versions/9/pkg/C.class")){jar.putNextEntry(new JarEntry(name));jar.write(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));jar.closeEntry();}}
        var original=store.put(new ByteArrayInputStream(bytes.toByteArray()),()->false);var adapter=new JavaBinaryViews(store,root.resolve("views"),new JavaBinaryViews.Budget(100,10000,10000));
        var failure=assertThrows(IOException.class,()->adapter.materialize(slot(original,"JAR",null,0)));assertEquals("JAVA_BINARY_RELEASE_REQUIRED",failure.getMessage());
    }
    @Test void archiveEntryCapacityIsAWorkerLimitRatherThanAnAmbiguousArchive()throws Exception{
        var store=new StreamingContentStore(root.resolve("cas"));var bytes=new ByteArrayOutputStream();try(var jar=new JarOutputStream(bytes)){for(String name:List.of("first","second")){jar.putNextEntry(new JarEntry(name));jar.write(1);jar.closeEntry();}}
        var original=store.put(new ByteArrayInputStream(bytes.toByteArray()),()->false);var adapter=new JavaBinaryViews(store,root.resolve("views"),new JavaBinaryViews.Budget(1,10000,10000));
        assertEquals("JAVA_BINARY_WORKER_CAPACITY_REQUIRED",assertThrows(IOException.class,()->adapter.materialize(slot(original,"JAR",null,0))).getMessage());assertTrue(store.verify(original));
    }
    @ParameterizedTest @CsvSource({"true,8,base","true,9,nine","true,10,nine","true,17,seventeen","true,21,seventeen","false,21,base","missing,21,base","TRUE,17,seventeen"})
    void releaseSelectionUsesOnlyTheCapturedManifestAndRequestedBoundary(String flag,int release,String expected)throws Exception{
        var store=new StreamingContentStore(root.resolve("cas"));var manifest=new Manifest();manifest.getMainAttributes().putValue("Manifest-Version","1.0");if(!flag.equals("missing"))manifest.getMainAttributes().putValue("Multi-Release",flag);
        var payloads=Map.of("pkg/C.class","base","META-INF/versions/9/pkg/C.class","nine","META-INF/versions/17/pkg/C.class","seventeen","META-INF/versions/99/pkg/C.class","future","META-INF/versions/09/pkg/C.class","malformed","META-INF/versions/8/pkg/C.class","ignored","META-INF/versions/17/META-INF/services/service","illegal override");
        var bytes=new ByteArrayOutputStream();try(var jar=new JarOutputStream(bytes,manifest)){for(var entry:new TreeMap<>(payloads).entrySet()){jar.putNextEntry(new JarEntry(entry.getKey()));jar.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));jar.closeEntry();}}
        var original=store.put(new ByteArrayInputStream(bytes.toByteArray()),()->false);var adapter=new JavaBinaryViews(store,root.resolve("views"),new JavaBinaryViews.Budget(100,10000,10000));
        var projected=adapter.selectedReleaseView(original,release);var selectedFields=new LinkedHashMap<>(slot(original,"JAR",null,0).fields());selectedFields.put("selectedView",projected);var binary=adapter.materialize(new JavaEvidenceCodec().create(selectedFields),release);assertEquals(original.digest(),binary.originEntry().contentDigest());assertTrue(store.verify(original));
        try(var jar=new JarFile(binary.path().toFile())){assertEquals(expected,new String(jar.getInputStream(jar.getJarEntry("pkg/C.class")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));if(flag.equalsIgnoreCase("true")){assertNull(jar.getManifest().getMainAttributes().getValue("Multi-Release"));assertFalse(jar.stream().anyMatch(e->e.getName().startsWith("META-INF/versions/")));}}
        assertEquals(binary.entry().contentDigest(),adapter.materialize(new JavaEvidenceCodec().create(selectedFields),release).entry().contentDigest());assertEquals(original.digest(),adapter.materialize(slot(original,"JAR",null,1),release).entry().contentDigest(),"An unprojected protected native slot retains its original bytes for the release-aware frontend");
        if(flag.equalsIgnoreCase("true")){var fields=new LinkedHashMap<>(slot(original,"JAR",null,1).fields());fields.put("selectedView",original);assertThrows(IOException.class,()->adapter.materialize(new JavaEvidenceCodec().create(fields),release));}
    }
    @Test void unsafeAndDuplicateArchiveMembersAreRejectedWithoutExtractingThem()throws Exception{
        var store=new StreamingContentStore(root.resolve("cas"));var adapter=new JavaBinaryViews(store,root.resolve("views"),new JavaBinaryViews.Budget(100,10000,10000));
        for(String name:List.of("../outside","/absolute","x/../../outside","C:/outside","C:\\outside","x\\..\\outside")){
            var bytes=new ByteArrayOutputStream();try(var jar=new JarOutputStream(bytes)){jar.putNextEntry(new JarEntry(name));jar.write(1);jar.closeEntry();}var original=store.put(new ByteArrayInputStream(bytes.toByteArray()),()->false);assertThrows(IOException.class,()->adapter.materialize(slot(original,"JAR",null,0)));assertTrue(store.verify(original));
        }
        var bytes=new ByteArrayOutputStream();try(var jar=new JarOutputStream(bytes)){for(String name:List.of("same1","same2")){jar.putNextEntry(new JarEntry(name));jar.write(1);jar.closeEntry();}}
        byte[] duplicated=bytes.toByteArray();for(int i=0;i+5<=duplicated.length;i++)if(duplicated[i]=='s'&&duplicated[i+1]=='a'&&duplicated[i+2]=='m'&&duplicated[i+3]=='e'&&duplicated[i+4]=='2')duplicated[i+4]='1';
        var original=store.put(new ByteArrayInputStream(duplicated),()->false);assertThrows(IOException.class,()->adapter.materialize(slot(original,"JAR",null,0)));assertTrue(store.verify(original));assertFalse(Files.exists(root.resolve("outside")));
    }
    @Test void aRealEmptyJarIsNotConfusedWithAnUncapturedOutput()throws Exception{
        var store=new StreamingContentStore(root.resolve("cas"));var bytes=new ByteArrayOutputStream();try(var jar=new JarOutputStream(bytes)){}
        var original=store.put(new ByteArrayInputStream(bytes.toByteArray()),()->false);
        var binary=new JavaBinaryViews(store,root.resolve("views"),new JavaBinaryViews.Budget(100,10000,10000)).materialize(slot(original,"JAR",null,0));
        assertEquals(original.digest(),binary.entry().contentDigest());assertTrue(Files.isRegularFile(binary.path()));
    }
    @Test void repeatedNativeSlotsHaveSeparateSafeFilesAndRetainTheirPosition()throws Exception{
        var store=new StreamingContentStore(root.resolve("cas"));var bytes=new ByteArrayOutputStream();try(var jar=new JarOutputStream(bytes)){jar.putNextEntry(new JarEntry("META-INF/fixture.txt"));jar.write("captured".getBytes(java.nio.charset.StandardCharsets.UTF_8));jar.closeEntry();}
        var original=store.put(new ByteArrayInputStream(bytes.toByteArray()),()->false);var adapter=new JavaBinaryViews(store,root.resolve("views"),new JavaBinaryViews.Budget(100,10000,10000));
        var first=adapter.materialize(slot(original,"JAR",null,0));var repeated=adapter.materialize(slot(original,"JAR",null,1));
        assertNotEquals(first.path(),repeated.path());assertEquals(first.entry().contentDigest(),repeated.entry().contentDigest());assertNotEquals(first.entry().logicalName(),repeated.entry().logicalName());
        assertEquals("captured-module",first.originEntry().logicalName());assertEquals(first.originEntry(),repeated.originEntry());assertEquals(original.digest(),first.originEntry().contentDigest());assertTrue(first.nativeOrigin().isPresent());
        Files.writeString(first.path(),"changed");assertThrows(IOException.class,()->adapter.materialize(slot(original,"JAR",null,0)));
    }
    @Test void zipMagicWithoutACentralDirectoryIsNotAValidCapturedJar()throws Exception{
        var store=new StreamingContentStore(root.resolve("cas"));var original=store.put(new ByteArrayInputStream(new byte[]{'P','K',5,6}),()->false);
        var adapter=new JavaBinaryViews(store,root.resolve("views"),new JavaBinaryViews.Budget(100,10000,10000));
        assertThrows(IOException.class,()->adapter.materialize(slot(original,"JAR",null,0)));
    }
    @Test void directoryViewUsesTheCapturedRootAndPreservesEveryResource()throws Exception{
        var store=new StreamingContentStore(root.resolve("cas"));var tables=new JavaEvidenceTables(store,10000);var census=store.put(new ByteArrayInputStream(new byte[]{7}),()->false);
        var rows=new ArrayList<JavaEvidenceRecord>();
        for(var entry:Map.of("out/classes/pkg/C.class","authored class payload","out/classes/META-INF/settings.properties","feature=true").entrySet()){
            var bytes=entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8);var payload=store.put(new ByteArrayInputStream(bytes),()->false);
            var fields=new LinkedHashMap<String,Object>();fields.put("schema","java-output-row:m8.4-v1");fields.put("key",D);fields.put("scope",scope());fields.put("role","compiled-output");fields.put("expectation","OBSERVED");fields.put("locator",Map.of("container",census,"members",List.of(),"path",LogicalPath.utf8(entry.getKey())));fields.put("kind","FILE");fields.put("content",payload);fields.put("lineage",D);fields.put("rootProof",D);fields.put("category",entry.getKey().endsWith(".class")?"CLASS":"RESOURCE");fields.put("disposition","ACCEPTED");fields.put("reason",null);var row=new JavaEvidenceCodec().create(fields);fields.put("key",JavaGeneratedOverlay.outputKey(row));rows.add(new JavaEvidenceCodec().create(fields));
        }
        rows.sort(JavaEvidenceTables::compare);var tree=tables.write("java-output-row",rows.iterator(),()->false);
        var descriptor=Map.of("schema","java-class-directory:m8.4-v1","scope",scope(),"root",LogicalPath.utf8("out/classes"),"tree",tree.wire());var original=tables.putCanonical(descriptor,()->false);
        var binary=new JavaBinaryViews(store,root.resolve("views"),new JavaBinaryViews.Budget(100,10000,10000)).materialize(slot(original,"CLASS_DIRECTORY",tree.wire(),0));
        assertNotEquals(original.digest(),binary.entry().contentDigest());
        assertEquals(original.digest(),binary.originEntry().contentDigest());
        try(var jar=new JarFile(binary.path().toFile())){assertEquals(Set.of("pkg/C.class","META-INF/settings.properties"),jar.stream().map(JarEntry::getName).collect(java.util.stream.Collectors.toSet()));assertEquals("feature=true",new String(jar.getInputStream(jar.getJarEntry("META-INF/settings.properties")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
    }
    @Test void nestedSelectionBindsTheOuterBytesMemberOrdinalAndLeafHash()throws Exception{
        var store=new StreamingContentStore(root.resolve("cas"));var tables=new JavaEvidenceTables(store,10000);var leafBytes=new ByteArrayOutputStream();try(var jar=new JarOutputStream(leafBytes)){}
        var leaf=store.put(new ByteArrayInputStream(leafBytes.toByteArray()),()->false);var outerBytes=new ByteArrayOutputStream();try(var jar=new JarOutputStream(outerBytes)){jar.putNextEntry(new JarEntry("BOOT-INF/lib/leaf.jar"));jar.write(leafBytes.toByteArray());jar.closeEntry();}var outer=store.put(new ByteArrayInputStream(outerBytes.toByteArray()),()->false);
        var fields=new LinkedHashMap<String,Object>();fields.put("schema","java-output-row:m8.4-v1");fields.put("key",D);fields.put("scope",scope());fields.put("role","nested-library");fields.put("expectation","OBSERVED");fields.put("locator",Map.of("container",outer,"members",List.of(Map.of("ordinal",0,"path",LogicalPath.utf8("BOOT-INF/lib/leaf.jar"),"content",leaf)),"path",LogicalPath.utf8("app.jar")));fields.put("kind","FILE");fields.put("content",leaf);fields.put("lineage",D);fields.put("rootProof",D);fields.put("category","ARCHIVE");fields.put("disposition","ACCEPTED");fields.put("reason",null);var row=new JavaEvidenceCodec().create(fields);fields.put("key",JavaGeneratedOverlay.outputKey(row));row=new JavaEvidenceCodec().create(fields);
        var tree=tables.write("java-output-row",List.of(row).iterator(),()->false);var adapter=new JavaBinaryViews(store,root.resolve("views"),new JavaBinaryViews.Budget(100,10000,10000));var binary=adapter.materialize(slot(outer,"NESTED_ARCHIVE",tree.wire(),0));assertEquals(leaf.digest(),binary.entry().contentDigest());assertArrayEquals(leafBytes.toByteArray(),Files.readAllBytes(binary.path()));assertTrue(store.verify(outer));
        var foreign=new LinkedHashMap<>(scope());foreign.put("sourceSet","integrationTest");fields.put("scope",foreign);var wrong=new JavaEvidenceCodec().create(fields);var badTree=tables.write("java-output-row",List.of(wrong).iterator(),()->false);assertThrows(IOException.class,()->adapter.materialize(slot(outer,"NESTED_ARCHIVE",badTree.wire(),0)));
    }
}
