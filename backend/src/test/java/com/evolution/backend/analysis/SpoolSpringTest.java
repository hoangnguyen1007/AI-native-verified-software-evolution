package com.evolution.backend.analysis;

import com.evolution.analysis.acquisition.*;
import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.filesystem.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.ingestion.*;
import com.evolution.analysis.input.PlatformSymbolRequest;
import com.evolution.analysis.javaparser.JavaParserFrontend;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SpoolSpringTest {
    @TempDir Path directory;
    @Test void recoveredSpringUnitAndOpenContextEvaluationSurviveDiskReplay()throws Exception {
        Path source=Files.createDirectory(directory.resolve("source"));Files.createDirectories(source.resolve("src/main/java/app"));
        Files.writeString(source.resolve("pom.xml"),"<project><modelVersion>4.0.0</modelVersion><groupId>authored</groupId><artifactId>spool</artifactId><version>1</version><properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties></project>");
        Files.writeString(source.resolve("src/main/java/app/Api.java"),"""
                package app;
                @org.springframework.stereotype.Service class Store {}
                @org.springframework.web.bind.annotation.RestController class Api {
                  Api(Store store) {}
                  @org.springframework.web.bind.annotation.GetMapping("/items") String items(){return "ok";}
                  String remote(org.springframework.web.client.RestTemplate client){return client.getForObject("/items",String.class);}
                }
                """);
        Files.createDirectories(source.resolve("src/test/java/test"));
        Files.writeString(source.resolve("src/test/java/test/Broken.java"),"package test; class Broken { void bad(){ missing(); } }");
        var acquisition=new FilesystemRepositoryAcquirer().acquire(source,new RepositoryAcquisitionRequest(RepositoryIdentity.fromCanonicalCoordinate("urn:test:spool-spring"),Optional.empty(),false,"pom.xml",new RepositoryAcquisitionPolicy(100,100,100000,1000000,32,List.of())));
        var decoded=new RepositoryInputDecoder().decode(acquisition,new RepositoryInputDecoder.Policy("UTF-8",100,100000));var inputs=decoded.inputs().orElseThrow();
        var model=new UniversalBuildIngestion().ingest(inputs,UniversalBuildIngestion.Policy.defaults(),Optional.of(new com.evolution.analysis.maven.MavenBuildModelProvider()),Map.of(),MavenScan.POLICY);
        var key=new UniversalSourceIngestion.SourceSet(model.modules().getFirst().descriptor().identity(),SourcePlanModel.Kind.MAIN);
        var platform=new FilesystemJdkPlatformProvider().acquire(Path.of(System.getProperty("java.home")),new PlatformSymbolRequest(21,128,128000000,512000000)).platform().orElseThrow();
        var binaries=new ArrayList<BinaryInput>();
        for(var row:Map.of("spring-core",org.springframework.core.SpringVersion.class,"spring-beans",org.springframework.beans.factory.BeanFactory.class,"spring-context",org.springframework.context.ApplicationContext.class,"spring-web",org.springframework.web.bind.annotation.GetMapping.class).entrySet()){
            Path jar=Path.of(row.getValue().getProtectionDomain().getCodeSource().getLocation().toURI());
            binaries.add(new BinaryInput(new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,"org.springframework:"+row.getKey()+":6.2.0@jar",ContentDigest.sha256(Files.readAllBytes(jar))),jar));
        }
        var policy=UniversalArchitecturePipeline.Policy.builder(new JavaParserFrontend(),JavaParserFrontend.PROVIDER).defaultPlatform(platform)
                .resolutions(Map.of(key,new UniversalSourceIngestion.Resolution(platform,binaries,ContentDigest.sha256Utf8("authored-partial-classpath"),Optional.empty(),false)))
                .mavenProvider(new com.evolution.analysis.maven.MavenBuildModelProvider()).mavenPolicy(MavenScan.POLICY).build();
        var expected=new UniversalArchitecturePipeline().runWithEvidence(acquisition,policy);
        assertFalse(expected.graphInput().orElseThrow().spring().isEmpty(),"Observed Spring metadata must evaluate even without a proved scan root");
        var main=expected.graphInput().orElseThrow().ingestion().orElseThrow().units().stream().filter(u->u.sourceSet().equals(key)).findFirst().orElseThrow().frontend().orElseThrow().analysis();
        var foreign=expected.report().gaps().stream().filter(g->g.context().analysisIdentity().isPresent()&&!g.context().analysisIdentity().orElseThrow().equals(main)).map(g->g.gapIdentity()).collect(java.util.stream.Collectors.toSet());
        assertFalse(foreign.isEmpty(),"The other source context's unresolved call must stay in the repository denominator");
        assertTrue(expected.graphInput().orElseThrow().spring().stream().flatMap(s->s.plan().gaps().stream()).noneMatch(g->foreign.contains(g.gapIdentity())),"A Spring plan must not multiply unrelated contexts' gaps");
        try(var spool=new FileAnalysisWorkspace(directory.resolve("scratch"))){
            var actual=new UniversalArchitecturePipeline(spool).runWithEvidence(acquisition,policy);
            assertEquals(1,actual.graphInput().orElseThrow().ingestion().orElseThrow().units().stream().flatMap(u->u.frontend().stream()).mapToInt(f->f.httpClientCalls().size()).sum());
            assertEquals(expected.report().identity(),actual.report().identity());
            var pages=new PartitionedGraphStore(directory.resolve("pages")).build(actual.graphInput().orElseThrow(),directory.resolve("snapshots"),()->false);
            assertEquals(new CanonicalGraphBuilder().build(expected.graphInput().orElseThrow()).identity(),pages.graph());
            assertEquals(0L,pages.edgeKinds().getOrDefault("spring.selected-binding",0L));
            assertTrue(pages.edgeKinds().getOrDefault("spring.source-type-candidate",0L)>0);
        }
    }
}
