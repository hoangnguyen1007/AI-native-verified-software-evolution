package com.evolution.analysis.javaparser;
import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.ingestion.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class UniversalIngestionPipelineTest {
    @Test void onePipelineConnectsConfigurationComponentsAndGeneratedConstructorSites()throws Exception {
        String source="""
                package app;
                @org.springframework.boot.autoconfigure.SpringBootApplication class App {}
                @org.springframework.stereotype.Component @lombok.RequiredArgsConstructor class Generated { final RecordService service; }
                @org.springframework.stereotype.Component record RecordService(String name) {}
                """;
        var exact=ComponentIngestionTest.fixture(Map.of("fixture/App.java",source),true).request();
        var input=inputs(Map.of("src/main/java/app/App.java",source,"src/main/resources/application.properties","feature.enabled=true\n"));
        var policy=new UniversalBuildIngestion.Policy(Optional.of(21),Optional.of(21),Optional.of("UTF-8"),100,10000,1000,16);
        var build=new UniversalBuildIngestion().ingest(input,policy,Optional.empty(),Map.of(),new BuildModelPolicy(List.of(),List.of(),Map.of(),10000,100,16));
        var key=new UniversalSourceIngestion.SourceSet(build.modules().getFirst().descriptor().identity(),SourcePlanModel.Kind.MAIN);
        var resolution=new UniversalSourceIngestion.Resolution(exact.platform(),exact.dependencies(),ContentDigest.sha256Utf8("authored-exact-spring-classpath"));
        var component=new ManifestComponent(new VersionedIdentifier("test.components","1"),ContentDigest.sha256Utf8("test-components"));
        var assembly=new UniversalSourceIngestion().assemble(input,build,Map.of(key,resolution),component,component,component);
        var result=new UniversalIngestionPipeline().run(assembly,new JavaParserFrontend(),new VersionedIdentifier("frontend.javaparser","3.28.2-m4u.1"),100,100);
        var main=result.units().stream().filter(u->u.sourceSet().equals(key)).findFirst().orElseThrow();
        assertEquals(UniversalIngestionPipeline.Status.ANALYZED,main.status(),result.issues().toString());
        assertEquals(3,main.components().orElseThrow().rows().stream().filter(r->r.status()==com.evolution.analysis.spring.condition.ComponentScanIngestion.Status.INCLUDED).count());
        assertEquals(2,main.constructors().orElseThrow().rows().stream().flatMap(r->r.parameters().stream()).count());
        assertTrue(assembly.outcomes().getFirst().configuration().assignment().isPresent());
    }
    private RepositoryInputs inputs(Map<String,String> text) {
        var files=new TreeMap<String,SourceInput>();
        text.forEach((path,value)->{byte[] bytes=value.getBytes(StandardCharsets.UTF_8);files.put(path,new SourceInput(SourceDocument.create(TestInputs.REPO,TestInputs.MODULE,path,ContentDigest.sha256(bytes),SourceClassification.MAIN),bytes));});
        var docs=files.values().stream().map(SourceInput::document).toList();
        return new RepositoryInputs(RepositorySnapshot.create(TestInputs.REPO,Optional.empty(),false,docs.stream().map(SnapshotFile::from).toList(),docs),files);
    }
    @Test void gradleAndPlainSourcesReachFrontendAndConfigurationWithoutAPom() {
        for(boolean gradle:List.of(false,true)) {
            var files=new TreeMap<String,String>();files.put("src/main/java/app/R.java","package app; record R(String dependency) {}");
            files.put("src/main/resources/application.yml","feature:\n  enabled: true\n");
            if(gradle)files.put("build.gradle.kts","plugins { java }\n");
            var input=inputs(files);var policy=new UniversalBuildIngestion.Policy(Optional.of(21),Optional.of(21),Optional.of("UTF-8"),100,10000,1000,16);
            var build=new UniversalBuildIngestion().ingest(input,policy,Optional.empty(),Map.of(),new BuildModelPolicy(List.of(),List.of(),Map.of(),10000,100,16));
            var base=TestInputs.request("class Platform {}");var key=new UniversalSourceIngestion.SourceSet(build.modules().getFirst().descriptor().identity(),SourcePlanModel.Kind.MAIN);
            var resolution=new UniversalSourceIngestion.Resolution(base.platform(),List.of(),ContentDigest.sha256Utf8("exact-empty-dependency-classpath"));
            var component=new ManifestComponent(new VersionedIdentifier("test.components","1"),ContentDigest.sha256Utf8("test-components"));
            var result=new UniversalSourceIngestion().assemble(input,build,Map.of(key,resolution),component,component,component);
            assertEquals(1,result.sources().size());assertEquals(UniversalSourceIngestion.Status.OWNED,result.sources().getFirst().status());
            var main=result.outcomes().stream().filter(o->o.sourceSet().equals(key)).findFirst().orElseThrow();
            var request=main.request().orElseThrow(()->new AssertionError(result.issues().toString()));
            var pipeline=new UniversalIngestionPipeline().run(result,new JavaParserFrontend(),new VersionedIdentifier("frontend.javaparser","3.28.2-m4u.1"),100,100);
            var frontend=pipeline.units().stream().filter(u->u.sourceSet().equals(key)).findFirst().orElseThrow().frontend().orElseThrow();
            assertEquals(FrontendResult.State.COMPLETED,frontend.state());
            assertEquals(pipeline.identity(),new UniversalIngestionPipeline().run(result,new JavaParserFrontend(),new VersionedIdentifier("frontend.javaparser","3.28.2-m4u.1"),100,100).identity());
            var rejected=new UniversalIngestionPipeline().run(result,r->{throw new FrontendInputException("frontend.syntax-level","sensitive input must not escape");},new VersionedIdentifier("frontend.test","1"),100,100);
            assertTrue(rejected.units().stream().anyMatch(u->u.status()==UniversalIngestionPipeline.Status.REJECTED));
            assertFalse(rejected.gaps().isEmpty());assertFalse(rejected.issues().toString().contains("sensitive"));
            assertEquals("true",main.configuration().properties().get("feature.enabled"));
            assertTrue(main.configuration().assignment().isPresent());
            assertEquals(result.identity(),new UniversalSourceIngestion().assemble(input,build,Map.of(key,resolution),component,component,component).identity());
            assertTrue(new UniversalSourceIngestion().assemble(input,build,Map.of(),component,component,component).outcomes().stream().allMatch(o->o.request().isEmpty()));
        }
    }
}
