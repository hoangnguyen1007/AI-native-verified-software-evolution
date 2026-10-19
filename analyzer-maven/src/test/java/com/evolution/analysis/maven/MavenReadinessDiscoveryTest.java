package com.evolution.analysis.maven;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.readiness.*;
import com.evolution.analysis.readiness.ReadinessPlan.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MavenReadinessDiscoveryTest {
    @Test void separatesAggregationParentAndNestedIndependentRoot(){
        var result=discover(Map.of("pom.xml",pom("aggregator","<packaging>pom</packaging><modules><module>service</module><module>missing</module></modules>"),
                "service/pom.xml",pom("service","<parent><groupId>t</groupId><artifactId>parent</artifactId><version>1</version><relativePath>../parent/pom.xml</relativePath></parent>"),
                "parent/pom.xml",pom("parent","<packaging>pom</packaging>"),"nested/pom.xml",pom("independent","")));
        assertEquals(Set.of(".","parent","nested"),new HashSet<>(result.roots().stream().map(Root::directory).toList()));
        assertTrue(result.modules().stream().anyMatch(module->module.directory().equals("service")&&module.root().equals("MAVEN:.")));
        assertTrue(result.requirements().stream().anyMatch(requirement->requirement.reason().equals("DECLARED_MODULE_UNAVAILABLE")));
        assertTrue(result.contexts().stream().anyMatch(context->context.module().contains("nested")&&context.sourceSet().equals("test")));
    }
    @Test void keepsProfilesAndDuplicateCoordinatesExplicit(){
        var result=discover(Map.of("pom.xml",pom("root","<modules><module>a</module><module>b</module></modules><profiles><profile><id>mobile</id><modules><module>android</module></modules><properties><maven.compiler.release>17</maven.compiler.release></properties></profile></profiles>"),
                "a/pom.xml",pom("same",""),"b/pom.xml",pom("same",""),"android/pom.xml",pom("android","")));
        assertTrue(result.contexts().stream().anyMatch(context->context.variant().equals("profile:mobile")&&context.environment().get("release").equals("17")));
        assertTrue(result.requirements().stream().anyMatch(requirement->requirement.reason().equals("DUPLICATE_GAV")));
        assertTrue(result.unknown().stream().anyMatch(value->value.contains("profile-combinations")));
        assertFalse(result.contexts().stream().anyMatch(context->context.module().endsWith("->android")&&context.variant().equals("baseline")),"A profile-only module cannot become part of the baseline reactor");
    }
    @Test void malformedOrUnsafePomKeepsRootAndTypedRequirement(){
        var result=discover(Map.of("bad/pom.xml","<!DOCTYPE project [<!ENTITY x SYSTEM 'file:///sentinel'>]><project>&x;</project>","healthy/pom.xml",pom("ok","")));
        assertEquals(2,result.roots().size());assertEquals(1,result.modules().stream().filter(module->module.directory().equals("healthy")).count());
        assertTrue(result.requirements().stream().anyMatch(requirement->requirement.reason().equals("POM_MODEL_UNAVAILABLE")));
    }
    @Test void unresolvedSourceDirectoryNeverBecomesAConventionalRootFact(){
        var result=discover(Map.of("pom.xml",pom("dynamic","<build><sourceDirectory>${unavailable}</sourceDirectory></build>")));
        assertTrue(result.contexts().stream().filter(context->context.sourceSet().equals("main")).allMatch(context->context.sourceRoots().isEmpty()));
        assertTrue(result.requirements().stream().anyMatch(requirement->requirement.reason().equals("SOURCE_ROOT_MODEL_REQUIRED")));
    }
    private static String pom(String artifact,String body){return "<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId><artifactId>"+artifact+"</artifactId><version>1</version>"+body+"</project>";}
    static ReadinessDiscovery.Projection discover(Map<String,String> files){
        var descriptors=new TreeMap<String,ReadinessDiscovery.Descriptor>();files.forEach((path,text)->{byte[] bytes=text.getBytes(StandardCharsets.UTF_8);descriptors.put(path,new ReadinessDiscovery.Descriptor(path,new ContentHandle(ContentDigest.sha256(bytes),bytes.length),bytes));});
        var manifest=new InventoryManifest(InventoryManifest.SCHEMA,RepositoryIdentity.fromCanonicalCoordinate("urn:test:readiness"),ContentDigest.sha256Utf8("selection"),ContentDigest.sha256Utf8("inventory"),files.size(),files.size(),0,0,Optional.empty(),Optional.empty());
        return new MavenReadinessDiscovery().discover(descriptors,manifest);
    }
}
