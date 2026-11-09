package com.evolution.backend.analysis;

import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ComprehensiveSelectionTest {
    @TempDir Path root;
    @Test void metadataPolicyUsesCentralByDefaultAndOfflineDoesNotCreateCache() throws Exception {
        assertEquals(List.of(
                java.net.URI.create("https://repo.maven.apache.org/maven2/"),
                java.net.URI.create("https://dl.google.com/dl/android/maven2/")),MavenScan.DEFAULT_REPOSITORIES);
        Path source=Files.createDirectory(root.resolve("source"));
        Files.writeString(source.resolve("pom.xml"),"<project><modelVersion>4.0.0</modelVersion><groupId>test</groupId><artifactId>app</artifactId><version>1</version></project>");
        var repo=com.evolution.analysis.contract.identity.RepositoryIdentity.fromCanonicalCoordinate("urn:test:metadata");
        try(var intake=new RepositoryIntake(root.resolve("state"))){
            intake.local(source,"capture",new com.evolution.analysis.acquisition.streaming.StreamingAcquisitionRequest(repo,java.util.Optional.empty(),true,List.of()),()->false);
            var acquisition=intake.compatibility("capture",repo,java.util.Optional.empty(),true,true,false,List.of());
            Path offline=root.resolve("offline-cache");
            var local=MavenScan.models(acquisition,offline,List.of());
            assertFalse(Files.exists(offline));assertEquals(MavenScan.pomPolicy(List.of()).identity(),local.getFirst().policyIdentity());
            Path online=root.resolve("new/cache");
            var result=MavenScan.models(acquisition,online);
            assertTrue(Files.isDirectory(online));assertEquals(MavenScan.pomPolicy(MavenScan.DEFAULT_REPOSITORIES).identity(),result.getFirst().policyIdentity());
        }
    }
    @Test void nestedIndependentRootIsNeverExcludedFromSemanticSelection() throws Exception {
        Path source=Files.createDirectory(root.resolve("multi"));
        String pom="<project><modelVersion>4.0.0</modelVersion><groupId>test</groupId><artifactId>%s</artifactId><version>1</version></project>";
        for(String path:List.of("pom.xml","nested/pom.xml","src/main/java/A.java","nested/src/main/java/B.java")){
            Path file=source.resolve(path);Files.createDirectories(file.getParent());
            Files.writeString(file,path.endsWith("pom.xml")?pom.formatted(path.startsWith("nested")?"nested":"outer"):"class Example {}");
        }
        var repo=com.evolution.analysis.contract.identity.RepositoryIdentity.fromCanonicalCoordinate("urn:test:multi-root");
        try(var intake=new RepositoryIntake(root.resolve("state"))){
            intake.local(source,"capture",new com.evolution.analysis.acquisition.streaming.StreamingAcquisitionRequest(repo,java.util.Optional.empty(),true,List.of()),()->false);
            var acquisition=intake.compatibility("capture",repo,java.util.Optional.empty(),true,true,false,List.of());
            assertEquals(java.util.Set.of("pom.xml","nested/pom.xml"),new java.util.HashSet<>(MavenScan.models(acquisition,root).stream().map(model->model.request().rootPom()).toList()));
        }
    }
    @Test void inventoryRetainsEveryLanguageSourceSetAndIndependentBuild() throws Exception {
        for (String path : List.of("api/schema.proto", "worker/main.py", "odd/data.unrecognized",
                "nested/pom.xml", "src/test/Example.java", "target/generated/File.java", "assets/image.bin", ".evo/real-project.py")) {
            Path file = root.resolve(path); Files.createDirectories(file.getParent()); Files.writeString(file, "input");
        }
        var selection = ScanSelection.plan(root, false, false, List.of());
        assertEquals(List.of(".git"), selection.excluded(), "Inventory must not inherit a semantic file whitelist or assume a tool directory is disposable");
        assertEquals("repository.selection:m8.4-v2", selection.policy());
    }
    @Test void semanticCompatibilityDoesNotConfuseJavaPackageNamesWithBuildOutputs()throws Exception{
        Path source=Files.createDirectory(root.resolve("source"));for(String path:List.of("pom.xml","src/main/java/build/C.java","src/main/java/test/T.java","target/Output.java")){
            Path file=source.resolve(path);Files.createDirectories(file.getParent());Files.writeString(file,path);
        }
        var repo=com.evolution.analysis.contract.identity.RepositoryIdentity.fromCanonicalCoordinate("urn:test:compatibility");
        try(var intake=new RepositoryIntake(root.resolve("state"))){
            var receipt=intake.local(source,"capture",new com.evolution.analysis.acquisition.streaming.StreamingAcquisitionRequest(repo,java.util.Optional.empty(),true,List.of()),()->false);assertTrue(receipt.manifest().isPresent());assertEquals(4,receipt.manifest().orElseThrow().files());
            var view=intake.compatibility("capture",repo,java.util.Optional.empty(),true,false,false,List.of());assertEquals(List.of("pom.xml","src/main/java/build/C.java","src/main/java/test/T.java"),view.files().stream().map(com.evolution.analysis.acquisition.AcquiredFile::path).toList());
        }
    }
    @Test void compatibilityIntakeHandlesOverTenThousandFilesAndOver128MbTotalBytes()throws Exception{
        Path source=Files.createDirectory(root.resolve("thingsboard-scale"));
        Files.writeString(source.resolve("pom.xml"),"<project><modelVersion>4.0.0</modelVersion><groupId>org.thingsboard</groupId><artifactId>root</artifactId><version>1.0</version></project>");
        Path pkg=Files.createDirectories(source.resolve("src/main/java/p"));
        for(int i=0;i<10_050;i++){
            Files.writeString(pkg.resolve("C"+i+".java"),"package p; class C"+i+" {}");
        }
        Path res=Files.createDirectories(source.resolve("src/main/resources"));
        byte[] largeBlob=new byte[9_500_000];
        java.util.Arrays.fill(largeBlob,(byte)'a');
        for(int i=0;i<14;i++){
            Files.write(res.resolve("bundle"+i+".properties"),largeBlob);
        }
        boolean symlinkCreated=false;
        try{
            Files.createSymbolicLink(res.resolve("linked.properties"),Path.of("bundle0.properties"));
            symlinkCreated=true;
        }catch(UnsupportedOperationException|FileSystemException ignoredPrivilege){
            // Symlink inventory handling is also covered deterministically via SqliteInventoryStore in StreamingRepositoryAcquirerTest.
        }
        var repo=com.evolution.analysis.contract.identity.RepositoryIdentity.fromCanonicalCoordinate("urn:test:thingsboard-scale");
        try(var intake=new RepositoryIntake(root.resolve("scale-state"))){
            var receipt=intake.local(source,"scale-capture",new com.evolution.analysis.acquisition.streaming.StreamingAcquisitionRequest(repo,java.util.Optional.empty(),true,List.of()),()->false);
            assertTrue(receipt.manifest().isPresent(),receipt.reason());
            var view=intake.compatibility("scale-capture",repo,java.util.Optional.empty(),true,false,false,List.of());
            assertEquals(1+10_050+14,view.files().size());
            if(symlinkCreated){
                assertTrue(view.attempts().stream().anyMatch(a->a.subject().equals("src/main/resources/linked.properties")
                        &&a.outcome()==com.evolution.analysis.acquisition.RepositoryAcquisitionResult.Outcome.EXCLUDED));
            }
        }
    }
}
