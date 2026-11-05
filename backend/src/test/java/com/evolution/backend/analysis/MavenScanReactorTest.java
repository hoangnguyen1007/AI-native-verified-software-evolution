package com.evolution.backend.analysis;

import com.evolution.analysis.acquisition.*;
import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.EntityIdentity;
import com.evolution.analysis.contract.identity.EntityScope;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.dependency.DependencyAcquisitionPolicy;
import com.evolution.analysis.filesystem.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.ingestion.*;
import com.evolution.analysis.input.PlatformSymbolRequest;
import com.evolution.analysis.javaparser.JavaParserFrontend;
import com.evolution.analysis.maven.MavenBuildModelProvider;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MavenScanReactorTest {
    @TempDir Path temp;
    @Test void diskAnalysisReplaysTheSameReportAndGraphAsMemoryIncludingUnresolvedEvidence()throws Exception {
        var journey=journey("none",true);var expected=journey.result();
        try(var storage=new FileAnalysisWorkspace(temp.resolve("spool"))){
            var actual=new UniversalArchitecturePipeline(storage).runWithEvidence(journey.acquisition(),journey.policy());
            assertEquals(expected.report().identity(),actual.report().identity());
            assertEquals(expected.report().gaps(),actual.report().gaps());
            assertEquals(expected.graphInput().orElseThrow().ingestion().orElseThrow().identity(),actual.graphInput().orElseThrow().ingestion().orElseThrow().identity());
            assertNotSame(client(actual),client(actual),"Repeated reads reconstruct neutral rows instead of caching completed frontend results");
            var builder=new com.evolution.analysis.graph.CanonicalGraphBuilder();
            assertEquals(builder.build(expected.graphInput().orElseThrow()).identity(),builder.build(actual.graphInput().orElseThrow()).identity());
        }
    }
    @Test void declaredReleaseSelectsItsSuppliedPlatformRatherThanTheFallback()throws Exception {
        Path source=Files.createDirectory(temp.resolve("platform-input"));
        Files.writeString(source.resolve("pom.xml"),"<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>platform</artifactId><version>1</version><properties><maven.compiler.release>17</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties></project>");
        var acquisition=new FilesystemRepositoryAcquirer().acquire(source,new RepositoryAcquisitionRequest(RepositoryIdentity.fromCanonicalCoordinate("urn:test:platform-selection"),Optional.empty(),false,"pom.xml",new RepositoryAcquisitionPolicy(100,100,100000,1000000,32,List.of())));
        Path cache=Files.createDirectory(temp.resolve("cache"));var models=MavenScan.models(acquisition,cache,List.of());
        var views=new TreeMap<Integer,PlatformInput>();
        // Data-only selection controls. No SDK/semantic validity is asserted by these metadata rows.
        for(int release:List.of(17,21))views.put(release,PlatformInput.create(release,"authored-"+release,"fixture",List.of(new PlatformInput.Artifact("fixture-"+release,
                com.evolution.analysis.contract.common.ContentDigest.sha256Utf8("platform-"+release),Path.of("unused-"+release+".jmod"),PlatformInput.Format.JMOD))));
        assertEquals(Set.of(17),MavenScan.platformReleases(models));
        var inputs=MavenScan.inputs(acquisition,models,cache,Optional.of(views.get(21)),new DependencyAcquisitionPolicy(100,1000000,10000000,8,List.of(),1000,1000,0),views);
        assertFalse(inputs.resolutions().isEmpty());assertTrue(inputs.resolutions().values().stream().allMatch(r->r.platform().equals(views.get(17))));
    }
    @Test void completeHandwrittenSiblingSourcesReachProductAtExactReactorPosition()throws Exception {
        var journey=journey("none",true);
        var result=journey.result();var unit=client(result);
        var calls=unit.frontend().orElseThrow().occurrences().stream().filter(o->o.relationship().kind().value().equals("java.calls")).toList();
        assertEquals(1,calls.size(),"A captured handwritten reactor sibling must not disable all attribution");
        assertEquals(SemanticStatus.RESOLVED,calls.getFirst().status());
        var source=result.graphInput().orElseThrow().ingestion().orElseThrow().sourceIngestion().outcomes().stream().filter(o->o.sourceSet().equals(unit.sourceSet())).findFirst().orElseThrow().request().orElseThrow();
        assertFalse(source.sourceRecovery());assertEquals(1,source.reactorSources().size());
        var sibling=source.reactorSources().getFirst();assertEquals(0,sibling.order());
        String expectedName=JavaSymbolName.method(JavaSymbolName.topLevelType("lib","Service"),"work",List.of()).canonicalName();
        var expected=EntityIdentity.from(EntityOrigin.PROJECT,EntityScope.project(sibling.module()),EntityKind.METHOD,expectedName);
        assertEquals(new RelationshipTarget.Resolved(expected),calls.getFirst().relationship().target());
        assertTrue(unit.frontend().orElseThrow().observations().stream().filter(o->o.reference().contains("service.work")).allMatch(o->o.origin()==ObservationRecord.EvidenceState.VERIFIED));
        assertTrue(journey.inputs().gaps().stream().anyMatch(g->g.reasonCode().equals("REACTOR_OUTPUT_NOT_ACQUIRED")),"Source substitution must not claim acquired bytecode");
        assertEquals(result.report().identity(),new UniversalArchitecturePipeline().run(journey.acquisition(),journey.policy()).identity());
    }

    @Test void missingSiblingAndUnacquiredGeneratedSourcesKeepAttributionWithheld()throws Exception {
        for(var journey:List.of(journey("none",false),journey("full",true),journey("none",true,"<compilerArgs><arg>-Xplugin:unknown</arg></compilerArgs>"))) {
            var unit=client(journey.result());
            var source=journey.result().graphInput().orElseThrow().ingestion().orElseThrow().sourceIngestion().outcomes().stream().filter(o->o.sourceSet().equals(unit.sourceSet())).findFirst().orElseThrow().request().orElseThrow();
            assertTrue(source.sourceRecovery());assertTrue(source.reactorSources().isEmpty());
            assertTrue(unit.frontend().orElseThrow().occurrences().stream().noneMatch(o->o.relationship().kind().value().equals("java.calls")&&o.status()==SemanticStatus.RESOLVED));
            assertTrue(journey.result().report().gaps().stream().anyMatch(g->g.reasonCode().equals("SEMANTIC_UNRESOLVED")));
        }
    }

    private static UniversalIngestionPipeline.Unit client(UniversalArchitecturePipeline.DetailedResult result) {
        return result.graphInput().orElseThrow().ingestion().orElseThrow().units().stream()
                .filter(u->u.frontend().stream().flatMap(f->f.observations().stream()).anyMatch(o->o.reference().contains("service.work"))).findFirst().orElseThrow();
    }
    private record Journey(RepositoryAcquisitionResult acquisition,MavenScan.Inputs inputs,UniversalArchitecturePipeline.Policy policy,UniversalArchitecturePipeline.DetailedResult result){}
    private Journey journey(String processing,boolean includeSource)throws Exception {
        return journey(processing,includeSource,"");
    }
    private Journey journey(String processing,boolean includeSource,String compilerArgs)throws Exception {
        Path source=Files.createTempDirectory(temp,"source-");
        String parent="<parent><groupId>example</groupId><artifactId>parent</artifactId><version>1</version></parent>";
        Map<String,String> files=Map.of(
                "pom.xml","<project><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>parent</artifactId><version>1</version><packaging>pom</packaging><modules><module>lib</module><module>app</module></modules><properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties></project>",
                "lib/pom.xml","<project><modelVersion>4.0.0</modelVersion>"+parent+"<artifactId>lib</artifactId><build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId><version>3.15.0</version><configuration><proc>"+processing+"</proc>"+compilerArgs+"</configuration></plugin></plugins></build></project>",
                "app/pom.xml","<project><modelVersion>4.0.0</modelVersion>"+parent+"<artifactId>app</artifactId><dependencies><dependency><groupId>example</groupId><artifactId>lib</artifactId><version>1</version></dependency></dependencies></project>",
                "lib/src/main/java/lib/Service.java","package lib; public class Service { public int work(){ return 1; } }",
                "app/src/main/java/app/Client.java","package app; class Client { int call(lib.Service service){ return service.work(); } }");
        for(var row:files.entrySet()){if(!includeSource&&row.getKey().endsWith("Service.java"))continue;Path path=source.resolve(row.getKey());Files.createDirectories(path.getParent());Files.writeString(path,row.getValue());}
        var acquisition=new FilesystemRepositoryAcquirer().acquire(source,new RepositoryAcquisitionRequest(RepositoryIdentity.fromCanonicalCoordinate("urn:test:product-reactor"),Optional.empty(),false,"pom.xml",new RepositoryAcquisitionPolicy(100,100,100000,1000000,32,List.of())));
        var platform=new FilesystemJdkPlatformProvider().acquire(Path.of(System.getProperty("java.home")),new PlatformSymbolRequest(21,128,128000000,512000000)).platform().orElseThrow();
        Path cache=Files.createTempDirectory(temp,"cache-");
        var models=MavenScan.models(acquisition,cache,List.of());
        assertEquals(List.of("pom.xml"),models.stream().map(m->m.request().rootPom()).toList(),"Aggregator discovery must precede child POMs");
        var inputs=MavenScan.inputs(acquisition,models,cache,Optional.of(platform),new DependencyAcquisitionPolicy(100,1000000,10000000,8,List.of(),1000,1000,0));
        var policy=UniversalArchitecturePipeline.Policy.builder(new JavaParserFrontend(),JavaParserFrontend.PROVIDER).defaultPlatform(platform)
                .resolutions(inputs.resolutions()).mavenProvider(new MavenBuildModelProvider()).externalPoms(inputs.externalPoms()).mavenPolicy(MavenScan.POLICY).build();
        var result=new UniversalArchitecturePipeline().runWithEvidence(acquisition,policy);
        return new Journey(acquisition,inputs,policy,result);
    }
}
