package com.evolution.cli;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ScanSelectionTest {
    @TempDir Path root;
    @Test void dailyScanRecordsBuildOutputsAssetsAndTestExclusionsWhileKeepingRealInputs()throws Exception{
        for(String p:List.of("module/src/main/java/build/C.java","module/src/main/resources/application.yml","module/src/main/resources/data.json","module/src/test/java/T.java","module/target/evo.jar","docs/large-result.json")){
            Path file=root.resolve(p);Files.createDirectories(file.getParent());Files.writeString(file,"fixture");
        }
        Files.writeString(root.resolve("pom.xml"),"<project/>");Files.writeString(root.resolve("module/pom.xml"),"<project/>");
        var result=ScanSelection.plan(root,false,false,List.of());
        assertTrue(result.excluded().containsAll(List.of("module/target","docs/large-result.json","module/src/test")));
        assertFalse(result.excluded().contains("module/src/main/java/build"));
        assertFalse(result.excluded().contains("module/src/main/resources/data.json"));
        assertEquals(result,ScanSelection.plan(root,false,false,List.of()));
    }
    @Test void explicitAllFilesAndTestsRestoreRequestedInputScope()throws Exception{
        Files.createDirectories(root.resolve("src/test/java"));Files.writeString(root.resolve("src/test/java/T.java"),"class T {}");
        Files.writeString(root.resolve("results.json"),"{}");
        assertFalse(ScanSelection.plan(root,true,false,List.of()).excluded().contains("src/test"));
        assertFalse(ScanSelection.plan(root,false,true,List.of()).excluded().contains("results.json"));
        assertTrue(ScanSelection.plan(root,true,true,List.of("src")).excluded().contains("src"));
    }
}
