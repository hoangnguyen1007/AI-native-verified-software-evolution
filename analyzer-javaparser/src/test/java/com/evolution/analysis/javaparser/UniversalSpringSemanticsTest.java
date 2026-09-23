package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.spring.*;
import com.evolution.analysis.spring.condition.*;
import com.evolution.analysis.spring.universal.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UniversalSpringSemanticsTest {
    record Fixture(SpringSourceEvidence source,SpringFrameworkEvidence framework,ComponentScanIngestion.Result components,ConstructorInjectionIngestion.Result constructors){}
    static Fixture fixture(Map<String,String> sources,boolean legacy) throws Exception {
        var base=ComponentIngestionTest.fixture(sources,false);var binaries=new ArrayList<>(base.request().dependencies());var artifacts=new ArrayList<>(base.framework().artifacts());
        var extra=Map.of("org.springframework.data:spring-data-commons:3.4.0",org.springframework.data.repository.Repository.class,
                "org.springframework.data:spring-data-jpa:3.4.0",org.springframework.data.jpa.repository.JpaRepository.class,
                "jakarta.inject:jakarta.inject-api:2.0.1",jakarta.inject.Inject.class,"javax.inject:javax.inject:1",javax.inject.Inject.class);
        for(var entry:new TreeMap<>(extra).entrySet()) {
            var path=Path.of(entry.getValue().getProtectionDomain().getCodeSource().getLocation().toURI());var digest=ContentDigest.sha256(Files.readAllBytes(path));
            binaries.add(new BinaryInput(new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,entry.getKey()+"@jar",digest),path));artifacts.add(new SpringFrameworkEvidence.Artifact(entry.getKey(),digest));
        }
        var request=TestInputs.request(sources,binaries);var frontend=new JavaParserFrontend().analyze(request);var framework=new SpringFrameworkEvidence(true,Optional.of(request.plan().classpathManifest()),artifacts);
        var components=new ComponentScanIngestion().scan(request.manifest(),frontend,framework,1000);
        var constructors=new ConstructorInjectionIngestion().ingest(frontend,components,framework,1000);
        return new Fixture(new SpringSourceEvidence(request.manifest(),frontend,framework),framework,components,constructors);
    }
    static UniversalSpringSemantics.Result analyze(Fixture f) {
        var config=new ConfigDataIngestion.Result(ContentDigest.sha256Utf8("empty-config"),List.of(),Map.of("enabled","true"),List.of(),Optional.empty(),List.of(),List.of());
        return UniversalSpringSemantics.analyze(f.source(),f.framework(),f.components(),f.constructors(),config,1000);
    }
    @Test void sourceToRepositoryInjectionAndControllerServiceRepositoryPath() throws Exception {
        var f=fixture(Map.of("fixture/App.java","""
                package app;
                @org.springframework.boot.autoconfigure.SpringBootApplication class App {}
                interface Users extends org.springframework.data.repository.CrudRepository<String, Long> {}
                @org.springframework.stereotype.Service class Service { Service(Users users) {} }
                @org.springframework.web.bind.annotation.RestController
                @org.springframework.web.bind.annotation.RequestMapping("/api")
                class Controller {
                  Controller(Service service) {}
                  @org.springframework.web.bind.annotation.GetMapping("/users") String users() {return "ok";}
                }
                """),false);
        var result=analyze(f);
        assertEquals(1,result.repositories().rows().stream().filter(r->r.status()==SpringDataRepositories.Status.CANDIDATE).count(),result.repositories().issues().toString());
        assertEquals(2,result.bindings().stream().filter(b->b.status()==UniversalSpringSemantics.BindingStatus.UNIQUE_CANDIDATE).count(),result.issues().toString());
        assertEquals("/api/users",result.web().routes().getFirst().path());
        assertEquals(List.of("GET"),result.web().routes().getFirst().methods());
        assertTrue(result.paths().stream().anyMatch(p->p.components().size()==3),result.paths().toString());
        assertEquals(result.identity(),analyze(f).identity());
    }
    @Test void inheritedRepositoryFamilyAndNoRepositoryBeanAreDistinguished()throws Exception {
        var f=fixture(Map.of("fixture/App.java","""
                package app;
                @org.springframework.boot.autoconfigure.SpringBootApplication class App {}
                @org.springframework.data.repository.NoRepositoryBean interface Base<T> extends org.springframework.data.repository.CrudRepository<T,Long> {}
                interface Users extends Base<String> {}
                interface JpaUsers extends org.springframework.data.jpa.repository.JpaRepository<String,Long> {}
                interface Plain {}
                """),false);
        var result=SpringDataRepositories.synthesize(f.source(),1000);
        assertEquals(2,result.rows().stream().filter(r->r.status()==SpringDataRepositories.Status.CANDIDATE).count());
        assertEquals(1,result.rows().stream().filter(r->r.status()==SpringDataRepositories.Status.NO_REPOSITORY_BEAN).count());
        assertEquals(1,result.rows().stream().filter(r->r.status()==SpringDataRepositories.Status.NOT_REPOSITORY).count());
    }
    @Test void componentScanOverrideDoesNotMoveRepositoryAutoConfigurationPackage()throws Exception {
        var f=fixture(Map.of("fixture/App.java","package app; @org.springframework.boot.autoconfigure.SpringBootApplication(scanBasePackages=\"elsewhere\") class App {}",
                "fixture/R.java","package elsewhere; interface R extends org.springframework.data.repository.CrudRepository<String,Long> {}"),false);
        assertEquals(SpringDataRepositories.Status.OUTSIDE_SCAN,SpringDataRepositories.synthesize(f.source(),100).rows().getFirst().status());
    }
    @Test void disabledAutoRepositoriesAndExplicitNamesDoNotBecomeUnconditionalCandidates()throws Exception {
        var f=fixture(Map.of("fixture/App.java","package app; @org.springframework.boot.autoconfigure.SpringBootApplication class App {} interface R extends org.springframework.data.repository.CrudRepository<String,Long> {}"),false);
        var disabled=SpringDataRepositories.synthesize(f.source(),Map.of("spring.data.jpa.repositories.enabled","false"),100);
        assertTrue(disabled.rows().stream().noneMatch(r->r.status()==SpringDataRepositories.Status.CANDIDATE));
        var excluded=SpringDataRepositories.synthesize(f.source(),Map.of("spring.autoconfigure.exclude","app.Custom"),100);
        assertEquals(SpringDataRepositories.Status.UNKNOWN,excluded.rows().getFirst().status());assertFalse(excluded.gaps().isEmpty());
    }
    @Test void dualNamespaceIsResolvedFromArtifactAndGeneration()throws Exception {
        var f=fixture(Map.of("fixture/App.java","""
                package app;
                @org.springframework.boot.autoconfigure.SpringBootApplication class App {}
                @org.springframework.stereotype.Component class Service {}
                @org.springframework.stereotype.Component class Uses {
                  @jakarta.inject.Inject Service modern;
                  @javax.inject.Inject Service legacy;
                }
                """),false);
        var result=analyze(f).injections();
        assertEquals(1,result.sites().stream().filter(s->s.status()==SpringInjectionSites.Status.ACQUIRED).count());
        assertEquals(1,result.sites().stream().filter(s->s.status()==SpringInjectionSites.Status.UNKNOWN).count());
        assertTrue(result.gaps().stream().anyMatch(g->g.reasonCode().equals("NAMESPACE_INCOMPATIBLE")));
    }
    @Test void repositoryNamesRespectTheActiveInjectNamespace()throws Exception {
        var f=fixture(Map.of("fixture/App.java","""
                package app;
                @org.springframework.boot.autoconfigure.SpringBootApplication class App {}
                @jakarta.inject.Named("modernBean") interface Modern extends org.springframework.data.repository.CrudRepository<String,Long> {}
                @javax.inject.Named("ignoredLegacyName") interface Legacy extends org.springframework.data.repository.CrudRepository<String,Long> {}
                """),false);
        var result=SpringDataRepositories.synthesize(f.source(),100);
        assertTrue(result.rows().stream().anyMatch(r->f.source().typeName(r.repository()).equals("app.Modern")&&r.beanName().equals(Optional.of("modernBean"))));
        assertTrue(result.rows().stream().anyMatch(r->f.source().typeName(r.repository()).equals("app.Legacy")&&r.beanName().equals(Optional.of("legacy"))&&r.status()==SpringDataRepositories.Status.UNKNOWN));
        assertTrue(result.gaps().stream().anyMatch(g->g.reasonCode().equals("NAMESPACE_INCOMPATIBLE")));
    }
    @Test void routeCrossProductMethodUnionAndMediaOverrideMatchSpringContract()throws Exception {
        var f=fixture(Map.of("fixture/App.java","""
                package app;
                @org.springframework.web.bind.annotation.RestController
                @org.springframework.web.bind.annotation.RequestMapping(path={"/v1","/v2"},method=org.springframework.web.bind.annotation.RequestMethod.POST, produces="text/plain", headers="X-Trace")
                class Controller {
                  @org.springframework.web.bind.annotation.GetMapping(path={"/a","/b"},produces="application/json",params="id") String get(){return "";}
                }
                """),false);
        var result=WebRouteMapper.map(f.source(),Map.of(),100);
        assertEquals(4,result.routes().size(),result.issues().toString());
        for(var route:result.routes()){assertEquals(List.of("GET","POST"),route.methods());assertEquals(List.of("application/json"),route.produces());assertEquals(List.of("X-Trace"),route.headers());assertEquals(List.of("id"),route.params());}
        assertEquals(WebRouteMapper.Status.LIMITED,WebRouteMapper.map(f.source(),Map.of(),1).rows().getFirst().status());
    }
    @Test void conditionalExpressionAcquisitionUsesConfigurationAndCannotExecuteCode()throws Exception {
        var f=fixture(Map.of("fixture/App.java","""
                package app;
                @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("${enabled:false} && 3 > 2") class Enabled {}
                @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("T(java.lang.System).exit(0)") class Dynamic {}
                """),false);
        var result=analyze(f);assertEquals(2,result.expressions().size());
        assertEquals(Set.of(LogicalValue.TRUE,LogicalValue.UNKNOWN),result.expressions().stream().map(e->e.result().value()).collect(java.util.stream.Collectors.toSet()));
    }
    @Test void inheritedCustomAndUnresolvedMappingsRemainVisibleWithoutInventedRoutes()throws Exception {
        var f=fixture(Map.of("fixture/App.java","""
                package app;
                class Base { @org.springframework.web.bind.annotation.GetMapping("/inherited") String inherited(){return "";} }
                @org.springframework.web.bind.annotation.RestController class Controller extends Base {
                  @org.springframework.web.bind.annotation.GetMapping("${unprovided}") String missing(){return "";}
                  @org.springframework.web.bind.annotation.GetMapping("/duplicate") @org.springframework.web.bind.annotation.PostMapping("/other") String conflict(){return "";}
                }
                """),false);
        var result=WebRouteMapper.map(f.source(),Map.of(),100);assertEquals(3,result.rows().size());assertTrue(result.routes().isEmpty());assertEquals(3,result.gaps().size());
    }
    @Test void basicSpelAgreesWithActualPinnedSpringInterpreterForSupportedControls() throws Exception {
        var f=fixture(Map.of("fixture/X.java","class X {}"),false);var evidence=UniversalSpringEvidence.derived(f.source().identity(),"authored-control");
        var oracle=new org.springframework.expression.spel.standard.SpelExpressionParser();
        for(String expression:List.of("true", "false", "!false && 2 >= 1", "'a' != 'b'", "1 + 2 * 3 == 7", "null == null", "(false or true) and true")) {
            var actual=BasicSpelEvaluator.evaluate(expression,Map.of(),evidence,f.source().manifest().snapshot().identity(),BasicSpelEvaluator.Limits.defaults());
            assertEquals(Boolean.TRUE.equals(oracle.parseExpression(expression).getValue(Boolean.class))?LogicalValue.TRUE:LogicalValue.FALSE,actual.value(),expression);
        }
    }
}
