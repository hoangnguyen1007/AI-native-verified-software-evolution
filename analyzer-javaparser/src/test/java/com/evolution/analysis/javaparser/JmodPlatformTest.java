package com.evolution.analysis.javaparser;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.frontend.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class JmodPlatformTest {
    @TempDir Path directory;
    @Test void validDescriptorOnlyJdkModuleDoesNotEraseAllJavaAnalysis()throws Exception{
        var baseline=TestInputs.request("class C { String value; }");var empty=Path.of(System.getProperty("java.home"),"jmods","java.se.jmod");
        var artifacts=new ArrayList<>(baseline.platform().artifacts());artifacts.add(new PlatformInput.Artifact("java.se.jmod",ContentDigest.sha256(Files.readAllBytes(empty)),empty,PlatformInput.Format.JMOD));
        var platform=PlatformInput.create(21,baseline.platform().version(),baseline.platform().vendor(),artifacts);
        var request=TestInputs.request(baseline.sources(),platform,List.of());var result=new JavaParserFrontend().analyze(request);
        assertEquals(FrontendResult.State.COMPLETED,result.state());assertEquals(1,result.typeDeclarations().size());
        assertEquals(result,new JavaParserFrontend().analyze(request));
        assertEquals("3.28.2-m8.4-native-wiring-v1",JavaParserFrontend.PROVIDER.version());
    }
    @Test void emptyArchiveWithoutValidModuleDescriptorStillFails()throws Exception{
        var baseline=TestInputs.request("class C {}");var file=directory.resolve("empty.jmod");try(var jar=new JarOutputStream(Files.newOutputStream(file))){}
        var platform=PlatformInput.create(21,"21","fixture",List.of(new PlatformInput.Artifact("empty.jmod",ContentDigest.sha256(Files.readAllBytes(file)),file,PlatformInput.Format.JMOD)));
        assertEquals("frontend.jmod-empty",assertThrows(FrontendInputException.class,()->new JavaParserFrontend().analyze(TestInputs.request(baseline.sources(),platform,List.of()))).diagnostic().code());
    }
    @Test void corruptDescriptorCannotCertifyAnEmptyPlatformModule()throws Exception{
        var baseline=TestInputs.request("class C {}");var file=directory.resolve("corrupt.jmod");try(var jar=new JarOutputStream(Files.newOutputStream(file))){jar.putNextEntry(new JarEntry("classes/module-info.class"));jar.write(new byte[]{1,2,3});jar.closeEntry();}
        var platform=PlatformInput.create(21,"21","fixture",List.of(new PlatformInput.Artifact("corrupt.jmod",ContentDigest.sha256(Files.readAllBytes(file)),file,PlatformInput.Format.JMOD)));
        assertEquals("frontend.jmod-descriptor",assertThrows(FrontendInputException.class,()->new JavaParserFrontend().analyze(TestInputs.request(baseline.sources(),platform,List.of()))).diagnostic().code());
    }
}
