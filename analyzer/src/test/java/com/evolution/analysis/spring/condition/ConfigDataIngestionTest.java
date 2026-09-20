package com.evolution.analysis.spring.condition;
import com.evolution.analysis.ingestion.IngestionFixtures;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ConfigDataIngestionTest {
    @Test void profileListsReplaceLowerPriorityListsAndActivationListsAreOrExpressions() {
        var r=load(Map.of("application.yml","spring.profiles.active: [old, obsolete]\n---\nspring.config.activate.on-profile: [fresh, other]\nactive: yes\n",
                "application.properties","spring.profiles.active[0]=fresh\n"));
        assertEquals(List.of("fresh"),r.activeProfiles());assertEquals("true",r.properties().get("active"));
        assertTrue(r.assignment().isPresent(),r.issues().toString());
    }
    @Test void sparseOrMalformedProfileListsCannotCreateAssignments() {
        for(String key:List.of("spring.profiles.active[1]","spring.profiles.active[x]","spring.config.activate.on-profile[2]"))
            assertTrue(load(Map.of("application.properties",key+"=prod\n")).assignment().isEmpty());
    }
    private ConfigDataIngestion.Result load(Map<String,String> files) {
        var paths=new TreeMap<String,String>(); files.forEach((k,v) -> paths.put("src/main/resources/"+k,v));
        return new ConfigDataIngestion().ingest(IngestionFixtures.inputs(paths),ConfigDataIngestion.Policy.defaults());
    }
    @Test void propertiesBeatYamlAndLaterProfilesOverrideEarlierOnes() {
        var r=load(Map.of("application.yml","key: yaml\nspring.profiles.active: a,b\n",
                "application.properties","key=properties\n",
                "application-a.properties","choice=a\n", "application-b.yml","choice: b\n"));
        assertEquals("properties",r.properties().get("key")); assertEquals("b",r.properties().get("choice"));
        assertTrue(r.assignment().isPresent());
    }
    @Test void profilesIncludesGroupsDefaultsAndConditionalDocuments() {
        var r=load(Map.of("application.yml","spring:\n  profiles:\n    include: common\n    active: prod\n    group:\n      prod: [db,metrics]\n---\nspring.config.activate.on-profile: 'prod & !dev'\nmode: deployed\n",
                "application-db.properties","database=on\n"));
        assertEquals(List.of("common","prod","db","metrics"),r.activeProfiles());
        assertEquals("on",r.properties().get("database")); assertEquals("deployed",r.properties().get("mode"));
        assertEquals("yes",load(Map.of("application-default.properties","fallback=yes")).properties().get("fallback"));
    }
    @Test void placeholdersDefaultsNestingEscapesAndWhitespaceArePreserved() {
        var r=load(Map.of("application.properties","name=service\nurl=${host:localhost}/${name}\nempty=\nspace= x \\n\nescaped\\:key=a\\=b\n"));
        assertEquals("localhost/service",r.properties().get("url")); assertEquals("",r.properties().get("empty"));
        assertEquals("a=b",r.properties().get("escaped:key")); assertTrue(r.assignment().isPresent());
    }
    @Test void cyclesMissingExternalPlaceholdersAndImportsWithholdAssignment() {
        for (String text:List.of("a=${b}\nb=${a}","a=${SECRET}","spring.config.import=optional:file:outside.properties")) {
            var r=load(Map.of("application.properties",text)); assertTrue(r.assignment().isEmpty()); assertFalse(r.gaps().isEmpty());
            assertEquals(r.issues().size(),r.gaps().size());
        }
    }
    @Test void malformedUnsafeAndCollidingYamlNeverCreatesAnAssignment() {
        for(String text:List.of("a: [", "a: 1\na: 2", "a.b: x\na: {b: y}", "a: !!java.net.URL [https://example.test]", "a: &a [*a]", "- scalar-root")) {
            var r=load(Map.of("application.yml",text)); assertTrue(r.assignment().isEmpty(),text); assertFalse(r.gaps().isEmpty(),text);
        }
    }
    @Test void missingInputUnknownActivationAndIllegalProfileDocumentsStayInDenominator() {
        var input=IngestionFixtures.inputs(Map.of("src/main/resources/application.yml","a: 1"));
        var missing=new com.evolution.analysis.ingestion.RepositoryInputs(input.snapshot(),Map.of());
        assertEquals(1,new ConfigDataIngestion().ingest(missing,ConfigDataIngestion.Policy.defaults()).gaps().size());
        for(String text:List.of("spring.config.activate.on-cloud-platform: kubernetes\na: 1", "spring.config.activate.on-profile: 'a & b | c'", "spring.config.activate.on-profile: prod\nspring.profiles.active: dev"))
            assertTrue(load(Map.of("application.yml",text)).assignment().isEmpty());
    }
    @Test void resourceLimitsAndLocationSelectionAreExplicit() {
        var input=IngestionFixtures.inputs(Map.of("src/main/resources/application.properties","a=12345", "other/application.properties","a=other"));
        var policy=new ConfigDataIngestion.Policy(List.of("src/main/resources"),List.of(),Map.of(),1,4,3,2);
        assertEquals(1,new ConfigDataIngestion().ingest(input,policy).gaps().size());
        assertEquals("12345",new ConfigDataIngestion().ingest(input,ConfigDataIngestion.Policy.defaults()).properties().get("a"));
    }
    @Test void propertiesSeparatorsRespectContinuationAndCommentAdjacency() {
        var r=load(Map.of("application.properties","a=one\\\n#---\nb=two\n"));
        assertEquals("one#---",r.properties().get("a"));
        assertEquals(1,r.documents().size());
        var adjacent=load(Map.of("application.properties","# header\n#---\na=1\n"));
        assertEquals(1,adjacent.documents().size());
    }
    @Test void blankKeysAndProfileNoneDoNotSilentlyProduceFacts() {
        assertTrue(load(Map.of("application.properties","=secret\n")).assignment().isEmpty());
        var r=load(Map.of("application.properties","spring.profiles.default=none\n","application-none.properties","should.not=load\n"));
        assertFalse(r.properties().containsKey("should.not"));
    }
    @Test void loadsFlattenedYamlAndProfilePropertiesIntoAnAssignment() {
        var input=IngestionFixtures.inputs(Map.of(
            "src/main/resources/application.yml","spring:\n  profiles:\n    active: dev\nfeature:\n  enabled: false\nservers:\n  - a\n  - b\n",
            "src/main/resources/application-dev.properties","feature.enabled=true\nmessage=hello\\\n  world\n"));
        var result=new ConfigDataIngestion().ingest(input,ConfigDataIngestion.Policy.defaults());
        assertEquals("true",result.properties().get("feature.enabled"));
        assertEquals("b",result.properties().get("servers[1]"));
        assertEquals("helloworld",result.properties().get("message"));
        assertEquals(List.of("dev"),result.activeProfiles());
        assertTrue(result.gaps().isEmpty());
        assertEquals("true",result.assignment().orElseThrow().baseline().get(new FiniteDomain.Variable(FiniteDomain.Kind.PROPERTY,"feature.enabled")).exact().orElseThrow());
        assertEquals(result.identity(),new ConfigDataIngestion().ingest(input,ConfigDataIngestion.Policy.defaults()).identity());
    }
}
