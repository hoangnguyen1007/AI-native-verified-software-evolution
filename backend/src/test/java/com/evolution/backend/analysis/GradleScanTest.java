package com.evolution.backend.analysis;

import com.evolution.analysis.acquisition.*;
import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.semantic.SemanticStatus;
import com.evolution.analysis.dependency.DependencyAcquisitionPolicy;
import com.evolution.analysis.filesystem.*;
import com.evolution.analysis.ingestion.*;
import com.evolution.analysis.input.PlatformSymbolRequest;
import com.evolution.analysis.javaparser.JavaParserFrontend;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GradleScanTest {
    @TempDir Path directory;
    @Test void literalProjectDependenciesSupplyOwnedSourceWithoutLeakingPrivateTransitiveProjects()throws Exception {
        Path source=Files.createDirectory(directory.resolve("projects")),cache=Files.createDirectory(directory.resolve("project-cache"));
        var files=Map.of("settings.gradle","include ':app', ':api', ':privateLib'\n",
                "app/build.gradle","plugins { id 'java' }\ndependencies { implementation project(':api') }\n",
                "api/build.gradle","plugins { id 'java-library' }\ndependencies { implementation project(':privateLib') }\n",
                "privateLib/build.gradle","plugins { id 'java-library' }\n",
                "api/src/main/java/external/Api.java","package external; public class Api { public static int id() { return 42; } }",
                "api/src/test/java/ApiTest.java","class ApiTest { int test() { return external.Api.id(); } }",
                "privateLib/src/main/java/hidden/Secret.java","package hidden; public class Secret { public static int id() { return 1; } }",
                "app/src/main/java/Client.java","class Client { int call() { return external.Api.id(); } int forbidden() { return hidden.Secret.id(); } }");
        for(var file:files.entrySet()){Path path=source.resolve(file.getKey());Files.createDirectories(path.getParent());Files.writeString(path,file.getValue());}
        var acquisition=new FilesystemRepositoryAcquirer().acquire(source,new RepositoryAcquisitionRequest(RepositoryIdentity.fromCanonicalCoordinate("urn:test:gradle-project-product"),Optional.empty(),false,"pom.xml",new RepositoryAcquisitionPolicy(100,100,1_000_000,10_000_000,32,List.of())));
        var buildPolicy=new UniversalBuildIngestion.Policy(Optional.of(21),Optional.of(21),Optional.of("UTF-8"),2048,1_000_000,200_000,64);var decoding=new RepositoryInputDecoder.Policy("UTF-8",100,1_000_000);
        var model=GradleScan.model(acquisition,buildPolicy,decoding,Map.of());
        var platform=new FilesystemJdkPlatformProvider().acquire(Path.of(System.getProperty("java.home")),new PlatformSymbolRequest(21,128,128_000_000,512_000_000)).platform();assertTrue(platform.isPresent());
        var inputs=GradleScan.augment(new MavenScan.Inputs(Map.of(),Map.of(),List.of(),Map.of()),acquisition,model,cache,platform,new DependencyAcquisitionPolicy(100,1_000_000,10_000_000,16,List.of(),1000,1000,0),Map.of());
        var policy=new UniversalArchitecturePipeline.Policy(buildPolicy,decoding,new JavaParserFrontend(),JavaParserFrontend.PROVIDER,inputs.resolutions(),platform,Optional.of(new com.evolution.analysis.maven.MavenBuildModelProvider()),Map.of(),MavenScan.POLICY,1000,100,UniversalArchitecturePipeline.TruthEvaluationMode.NONE,Map.of(),Optional.empty(),Optional.empty(),com.evolution.analysis.spring.truth.TruthRegionEvaluation.Limits.conservative());
        var result=new UniversalArchitecturePipeline().runWithEvidence(acquisition,policy);
        var calls=result.graphInput().orElseThrow().ingestion().orElseThrow().units().stream().flatMap(u->u.frontend().stream()).flatMap(f->f.observations().stream()).filter(o->o.category().value().equals("java.calls")).toList();
        assertEquals(2,calls.stream().filter(o->o.reference().equals("external.Api.id()")&&o.attribution()==SemanticStatus.RESOLVED).count());
        var forbidden=calls.stream().filter(o->o.reference().equals("hidden.Secret.id()")).toList();assertEquals(1,forbidden.size());assertNotEquals(SemanticStatus.RESOLVED,forbidden.getFirst().attribution());
        assertTrue(inputs.resolutions().values().stream().noneMatch(UniversalSourceIngestion.Resolution::complete));
        assertTrue(inputs.gaps().stream().anyMatch(g->g.reasonCode().equals("GRADLE_EFFECTIVE_CONTEXT_REQUIRED")));
    }
    @Test void cachedGradleJarReachesTheSemanticFrontendAndDurableLedgerWithoutTargetExecution()throws Exception {
        Path source=Files.createDirectory(directory.resolve("source")),cache=Files.createDirectory(directory.resolve("cache"));
        Files.writeString(source.resolve("build.gradle"),"plugins { id 'java' }\njava { toolchain { languageVersion = JavaLanguageVersion.of(21) } }\ndependencies { implementation 'external:library:1' }\n");
        Path code=Files.createDirectories(source.resolve("src/main/java"));Files.writeString(code.resolve("Client.java"),"class Client { int call() { return external.Library.id(); } }");
        Path authored=Files.createDirectory(directory.resolve("authored")),classes=Files.createDirectory(directory.resolve("classes"));
        Path fixture=authored.resolve("Library.java");Files.writeString(fixture,"package external; public class Library { public static int id() { return 42; } }");
        assertEquals(0,javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-d",classes.toString(),fixture.toString()));
        Path library=Files.createDirectories(cache.resolve("external/library/1"));
        try(var jar=new JarOutputStream(Files.newOutputStream(library.resolve("library-1.jar")))){jar.putNextEntry(new JarEntry("external/Library.class"));jar.write(Files.readAllBytes(classes.resolve("external/Library.class")));jar.closeEntry();}
        Files.writeString(library.resolve("library-1.pom"),"<project><modelVersion>4.0.0</modelVersion><groupId>external</groupId><artifactId>library</artifactId><version>1</version></project>");
        var acquisition=new FilesystemRepositoryAcquirer().acquire(source,new RepositoryAcquisitionRequest(RepositoryIdentity.fromCanonicalCoordinate("urn:test:gradle-product"),Optional.empty(),false,"pom.xml",new RepositoryAcquisitionPolicy(100,100,1_000_000,10_000_000,32,List.of())));
        var buildPolicy=new UniversalBuildIngestion.Policy(Optional.of(21),Optional.of(21),Optional.of("UTF-8"),2048,1_000_000,200_000,64);
        var decoding=new RepositoryInputDecoder.Policy("UTF-8",100,1_000_000);var model=GradleScan.model(acquisition,buildPolicy,decoding,Map.of());
        var platform=new FilesystemJdkPlatformProvider().acquire(Path.of(System.getProperty("java.home")),new PlatformSymbolRequest(21,128,128_000_000,512_000_000)).platform();assertTrue(platform.isPresent());
        var empty=new MavenScan.Inputs(Map.of(),Map.of(),List.of(),Map.of("mode","NOT_APPLICABLE"));
        var inputs=GradleScan.augment(empty,acquisition,model,cache,platform,new DependencyAcquisitionPolicy(100,1_000_000,10_000_000,16,List.of(),1000,1000,0),Map.of(21,platform.orElseThrow()));
        assertEquals(1,inputs.resolutions().values().iterator().next().binaries().size());assertFalse(inputs.resolutions().values().iterator().next().complete());assertFalse(inputs.ledgers().isEmpty());
        var policy=new UniversalArchitecturePipeline.Policy(buildPolicy,decoding,new JavaParserFrontend(),JavaParserFrontend.PROVIDER,inputs.resolutions(),platform,Optional.of(new com.evolution.analysis.maven.MavenBuildModelProvider()),Map.of(),MavenScan.POLICY,1000,100,UniversalArchitecturePipeline.TruthEvaluationMode.NONE,Map.of(),Optional.empty(),Optional.empty(),com.evolution.analysis.spring.truth.TruthRegionEvaluation.Limits.conservative());
        var result=new UniversalArchitecturePipeline().runWithEvidence(acquisition,policy);
        var calls=result.graphInput().orElseThrow().ingestion().orElseThrow().units().stream().flatMap(u->u.frontend().stream()).flatMap(f->f.occurrences().stream()).filter(o->o.relationship().kind().value().equals("java.calls")).toList();
        assertEquals(1,calls.size());assertEquals(SemanticStatus.RESOLVED,calls.getFirst().status());
        assertTrue(inputs.gaps().stream().anyMatch(g->g.reasonCode().equals("GRADLE_EFFECTIVE_CONTEXT_REQUIRED")));
        assertEquals(inputs.resolutions(),GradleScan.augment(empty,acquisition,model,cache,platform,new DependencyAcquisitionPolicy(100,1_000_000,10_000_000,16,List.of(),1000,1000,0),Map.of(21,platform.orElseThrow())).resolutions());
    }
}
