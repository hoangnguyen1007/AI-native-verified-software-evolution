package com.evolution.analysis.spring.condition;

import com.evolution.analysis.ingestion.IngestionFixtures;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConfigImportClosureTest {
    private ConfigDataIngestion.Result load(Map<String,String> files) {
        var paths = new TreeMap<String,String>();
        files.forEach((k,v) -> paths.put("src/main/resources/" + k,v));
        return new ConfigDataIngestion().ingest(IngestionFixtures.inputs(paths),ConfigDataIngestion.Policy.defaults());
    }
    @Test void nestedImportsOverrideTheirImporterAndPreserveDocumentEvidence() {
        var files = Map.of("application.properties", "spring.config.import=parts/first.properties,second.yml\nvalue=base\n",
                "parts/first.properties", "spring.config.import=../shared.properties\nvalue=first\n",
                "shared.properties", "value=shared\nshared=yes\n", "second.yml", "value: second\n");
        var result = load(files);
        assertEquals("second",result.properties().get("value"));
        assertEquals("yes",result.properties().get("shared"));
        assertTrue(result.assignment().isPresent(),result.issues().toString());
        assertEquals(4,result.documents().size());
        assertTrue(result.documents().stream().allMatch(d -> d.evidence() instanceof ConditionEvidence.Source));
        assertEquals(result.identity(),load(files).identity());
    }
    @Test void missingOptionalRelativeImportIsAbsenceButRequiredOrExternalInputIsUnknown() {
        var optional = load(Map.of("application.properties","spring.config.import=optional:missing.properties\nindependent=ok"));
        assertEquals("ok",optional.properties().get("independent"));
        assertTrue(optional.assignment().isPresent(),optional.issues().toString());
        for (var path : List.of("missing.properties","optional:file:/outside.properties", "https://example.invalid/a.properties")) {
            var result = load(Map.of("application.properties","spring.config.import="+path+"\nindependent=ok"));
            assertEquals("ok",result.properties().get("independent"));
            assertTrue(result.assignment().isEmpty());
            assertFalse(result.gaps().isEmpty());
        }
    }
    @Test void importsContributeProfileGroupsAndProfileVariantsOverrideImportedBase() {
        var result = load(Map.of("application.properties","spring.config.import=shared.properties\n",
                "shared.properties","spring.profiles.active=prod\nspring.profiles.group.prod=db\nvalue=base\n",
                "shared-db.properties","value=database\n"));
        assertEquals(List.of("prod","db"),result.activeProfiles());
        assertEquals("database",result.properties().get("value"));
        assertTrue(result.assignment().isPresent(),result.issues().toString());
    }
    @Test void inactiveImportsAreNotRequiredAndCyclesRemainExplicit() {
        var inactive = load(Map.of("application.properties","spring.config.activate.on-profile=prod\nspring.config.import=missing.properties"));
        assertTrue(inactive.assignment().isPresent(),inactive.issues().toString());
        var cycle = load(Map.of("application.properties","spring.config.import=a.properties",
                "a.properties","spring.config.import=application.properties\nlocal=retained"));
        assertEquals("retained",cycle.properties().get("local"));
        assertTrue(cycle.assignment().isEmpty());
        assertFalse(cycle.gaps().isEmpty());
    }
    @Test void importCannotEscapeCapturedSelectionAndMalformedOptionalIsNotAbsence() {
        for (String path : List.of("../../../secret.properties", "optional:bad.yml")) {
            var result = load(Map.of("application.properties","spring.config.import="+path, "bad.yml","a: ["));
            assertTrue(result.assignment().isEmpty());
            assertFalse(result.gaps().isEmpty());
        }
    }
    @Test void repeatedImportsUseTheHighestPrecedenceDiscoveryPosition() {
        var result=load(Map.of("application.properties","spring.config.import=first.properties,second.properties",
                "first.properties","spring.config.import=shared.properties\nvalue=first",
                "second.properties","spring.config.import=shared.properties\nvalue=second",
                "shared.properties","value=shared"));
        assertEquals("shared",result.properties().get("value"));
        assertEquals(4,result.documents().size());
        assertTrue(result.assignment().isPresent(),result.issues().toString());
    }
    @Test void importedConditionalDocumentsCannotSetProfilesBeforeActivation() {
        var result=load(Map.of("application.properties","spring.config.import=shared.yml",
                "shared.yml","spring.config.activate.on-profile: absent\nspring.profiles.active: prod\n",
                "application-prod.properties","incorrect=active"));
        assertFalse(result.activeProfiles().contains("prod"));
        assertFalse(result.properties().containsKey("incorrect"));
        assertTrue(result.assignment().isEmpty(),"illegal profile declarations remain explicit");
    }
    @Test void byteAndDepthLimitsKeepIndependentCapturedProperties() {
        var input=IngestionFixtures.inputs(Map.of("src/main/resources/application.properties","spring.config.import=a.properties\nindependent=ok",
                "src/main/resources/a.properties","spring.config.import=b.properties\na=yes",
                "src/main/resources/b.properties","spring.config.import=c.properties\nb=yes",
                "src/main/resources/c.properties","value="+"x".repeat(200)));
        for(var policy:List.of(new ConfigDataIngestion.Policy(List.of("src/main/resources"),List.of(),Map.of(),10,1000,1,100),
                new ConfigDataIngestion.Policy(List.of("src/main/resources"),List.of(),Map.of(),10,100,10,100))) {
            var result=new ConfigDataIngestion().ingest(input,policy);
            assertEquals("ok",result.properties().get("independent"));
            assertTrue(result.assignment().isEmpty());
            assertTrue(result.gaps().stream().anyMatch(g -> g.reasonCode().equals("INPUT_LIMIT")));
            assertEquals(result.identity(),new ConfigDataIngestion().ingest(input,policy).identity());
        }
    }
    @Test void optionalCapturedButUnavailableFileDoesNotBecomeProvenAbsence() {
        var input=IngestionFixtures.inputs(Map.of("src/main/resources/application.properties","spring.config.import=optional:a.properties",
                "src/main/resources/a.properties","a=1"));
        var files=new TreeMap<>(input.files());files.remove("src/main/resources/a.properties");
        var partial=new com.evolution.analysis.ingestion.RepositoryInputs(input.snapshot(),files);
        var result=new ConfigDataIngestion().ingest(partial,ConfigDataIngestion.Policy.defaults());
        assertTrue(result.assignment().isEmpty());
        assertTrue(result.gaps().stream().anyMatch(g -> g.reasonCode().equals("INPUT_UNAVAILABLE")));
    }
}
