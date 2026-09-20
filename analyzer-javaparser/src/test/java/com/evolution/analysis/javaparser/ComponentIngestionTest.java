package com.evolution.analysis.javaparser;
import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.spring.*;
import com.evolution.analysis.spring.condition.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ComponentIngestionTest {
    @Test void allSixStandardStereotypesAreDiscoveredFromExactFrameworkArtifacts()throws Exception {
        var r=scan(Map.of("fixture/App.java","""
                package app;
                @org.springframework.boot.autoconfigure.SpringBootApplication class App {}
                @org.springframework.stereotype.Component class C {}
                @org.springframework.stereotype.Service class S {}
                @org.springframework.stereotype.Repository class R {}
                @org.springframework.stereotype.Controller class Web {}
                @org.springframework.web.bind.annotation.RestController class Rest {}
                @org.springframework.context.annotation.Configuration class Config {}
                """));
        assertEquals(7,r.rows().stream().filter(row->row.status()==ComponentScanIngestion.Status.INCLUDED).count());
        assertTrue(r.gaps().isEmpty(),r.issues().toString());
    }
    @Test void classLiteralPackageMarkersUseResolvedTypeEvidence()throws Exception {
        var r=scan(Map.of("fixture/App.java","package root; @org.springframework.context.annotation.Configuration @org.springframework.context.annotation.ComponentScan(basePackageClasses={app.Marker.class}) class App {}",
                "fixture/Marker.java","package app; public interface Marker {} @org.springframework.stereotype.Service class Service {}"));
        assertEquals(2,r.rows().stream().filter(row->row.status()==ComponentScanIngestion.Status.INCLUDED).count());
        assertTrue(r.gaps().isEmpty(),r.issues().toString());
    }
    @Test void declarationShapeDoesNotDependOnBodyTextOrExplicitStaticKeyword()throws Exception {
        var r=scan(Map.of("fixture/App.java","package app; @org.springframework.boot.autoconfigure.SpringBootApplication class App {}\n"
                +"@org.springframework.stereotype.Component class Body { String text=\"interface Fake\"; interface Nested {} }\n"
                +"interface Holder { @org.springframework.stereotype.Component class Member {} }\n"
                +"class Outer { @org.springframework.stereotype.Component class Inner {} }"));
        assertEquals(3,r.rows().stream().filter(row->row.status()==ComponentScanIngestion.Status.INCLUDED).count());
        assertEquals(1,r.rows().stream().filter(row->row.status()==ComponentScanIngestion.Status.INELIGIBLE).count());
    }
    @Test void incompleteComposedAnnotationCannotProveNonComponent()throws Exception {
        var r=scan(Map.of("fixture/App.java","package app; @org.springframework.boot.autoconfigure.SpringBootApplication class App {}\n"
                +"@MissingMeta @interface Layer {} @Layer class Service {}"));
        assertTrue(r.rows().stream().filter(row->row.status()==ComponentScanIngestion.Status.UNKNOWN).count()>=2);
        assertFalse(r.gaps().isEmpty());
    }
    @Test void literalScanPackagesBeanNamesComposedStereotypesAndEligibility()throws Exception {
        var r=scan(Map.of("fixture/App.java","package root; @org.springframework.context.annotation.Configuration @org.springframework.context.annotation.ComponentScan(basePackages={\"app\",\"extra\"}) class App {}",
                "fixture/Layer.java","package app; @org.springframework.stereotype.Component @interface Layer {} @Layer class S {} @org.springframework.stereotype.Service(\"named\") class Named {} @org.springframework.stereotype.Component abstract class Abstract {}",
                "fixture/URLService.java","package extra; @org.springframework.stereotype.Service class URLService {}"));
        assertEquals(4,r.rows().stream().filter(row->row.status()==ComponentScanIngestion.Status.INCLUDED).count());
        assertEquals(2,r.rows().stream().filter(row->row.status()==ComponentScanIngestion.Status.INELIGIBLE).count());
        assertTrue(r.rows().stream().anyMatch(row->row.beanName().equals(Optional.of("named"))));
        assertTrue(r.rows().stream().anyMatch(row->row.beanName().equals(Optional.of("URLService"))));
    }
    @Test void customFiltersAndUnresolvedStereotypesCannotBecomeExactMembership()throws Exception {
        var r=scan(Map.of("fixture/App.java","package app; @org.springframework.context.annotation.ComponentScan(useDefaultFilters=false) class App {} @org.springframework.stereotype.Component class C {}"));
        assertTrue(r.rows().stream().noneMatch(row->row.status()==ComponentScanIngestion.Status.INCLUDED));
        assertFalse(r.gaps().isEmpty());
        var missing=scan(Map.of("fixture/App.java","package app; @org.springframework.boot.autoconfigure.SpringBootApplication class App {} @Unknown class C {}"));
        assertTrue(missing.rows().stream().anyMatch(row->row.status()==ComponentScanIngestion.Status.UNKNOWN));
    }
    @Test void projectDefinedSpringImpostorIsNotAComponent()throws Exception {
        var r=scan(Map.of("fixture/Component.java","package org.springframework.stereotype; public @interface Component {}",
                "fixture/C.java","package app; @org.springframework.stereotype.Component class C {}"));
        assertTrue(r.rows().stream().noneMatch(row->row.status()==ComponentScanIngestion.Status.INCLUDED));
    }
    private ComponentScanIngestion.Result scan(Map<String,String> sources)throws Exception {
        var fixture=fixture(sources,false);
        return new ComponentScanIngestion().scan(fixture.request().manifest(),fixture.frontend(),fixture.framework(),1000);
    }
    record Fixture(FrontendRequest request,FrontendResult frontend,SpringFrameworkEvidence framework){}
    static Fixture fixture(Map<String,String> sources,boolean lombok)throws Exception {
        Map<String,Class<?>> classes=Map.of("org.springframework:spring-context:6.2.0",org.springframework.stereotype.Component.class,
                "org.springframework:spring-beans:6.2.0",org.springframework.beans.factory.annotation.Autowired.class,
                "org.springframework:spring-core:6.2.0",org.springframework.core.annotation.AliasFor.class,
                "org.springframework:spring-web:6.2.0",org.springframework.web.bind.annotation.RestController.class,
                "org.springframework.boot:spring-boot:3.4.0",org.springframework.boot.SpringApplication.class,
                "org.springframework.boot:spring-boot-autoconfigure:3.4.0",org.springframework.boot.autoconfigure.SpringBootApplication.class);
        var binaries=new ArrayList<BinaryInput>();var artifacts=new ArrayList<SpringFrameworkEvidence.Artifact>();
        for(var e:new TreeMap<>(classes).entrySet()) {
            var path=Path.of(e.getValue().getProtectionDomain().getCodeSource().getLocation().toURI());var digest=ContentDigest.sha256(Files.readAllBytes(path));
            binaries.add(new BinaryInput(new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,e.getKey()+"@jar",digest),path));
            artifacts.add(new SpringFrameworkEvidence.Artifact(e.getKey(),digest));
        }
        if(lombok) {
            var path=Path.of(lombok.Data.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            binaries.add(new BinaryInput(new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,"org.projectlombok:lombok:1.18.46@jar",ContentDigest.sha256(Files.readAllBytes(path))),path));
        }
        var request=TestInputs.request(sources,binaries);var result=new JavaParserFrontend().analyze(request);
        return new Fixture(request,result,new SpringFrameworkEvidence(true,artifacts));
    }
    @Test void bootRootIncludesSubpackagesButNotPrefixNeighbors()throws Exception {
        var r=scan(Map.of("fixture/App.java","package app; @org.springframework.boot.autoconfigure.SpringBootApplication class App {}",
                "fixture/S.java","package app.services; @org.springframework.stereotype.Service class S {}",
                "fixture/Other.java","package application; @org.springframework.stereotype.Component class Other {}"));
        assertEquals(2,r.rows().stream().filter(row->row.status()==ComponentScanIngestion.Status.INCLUDED).count());
        assertEquals(1,r.rows().stream().filter(row->row.status()==ComponentScanIngestion.Status.OUTSIDE_ROOT).count());
        assertTrue(r.gaps().isEmpty(),r.issues().toString());
    }
}
