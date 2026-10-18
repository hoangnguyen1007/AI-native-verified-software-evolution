package com.evolution.backend.analysis;

import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ComprehensiveSelectionTest {
    @TempDir Path root;
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
}
