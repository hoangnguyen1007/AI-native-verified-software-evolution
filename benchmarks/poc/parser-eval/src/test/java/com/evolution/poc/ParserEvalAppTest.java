package com.evolution.poc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParserEvalAppTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void emitsStructuredRelationshipEvidenceInsteadOfUnverifiableStrings() throws Exception {
        Path sourceRoot = fixtureSourceRoot();
        Path output = temporaryDirectory.resolve("result.json");

        ParserEvalApp.main(new String[]{sourceRoot.toString(), output.toString()});

        JsonObject result = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
        JsonArray relationships = result.getAsJsonArray("relationships");
        assertFalse(relationships.isEmpty());
        assertTrue(relationships.get(0).isJsonObject(),
                "Each relationship must be structured evidence, not a display string");

        JsonObject resolvedCall = findRelationship(relationships, "CALLS", "fixture/Caller.java", 9);
        assertEquals("fixture.Caller#call()", resolvedCall.get("sourceIdentity").getAsString());
        assertEquals("fixture.Service.choose(java.lang.String)", resolvedCall.get("targetIdentity").getAsString());
        assertEquals("PROJECT_LOCAL", resolvedCall.get("targetOrigin").getAsString());
        assertEquals("RESOLVED", resolvedCall.get("resolutionStatus").getAsString());
        assertEquals("TEST_DEFAULT", resolvedCall.get("configurationId").getAsString());
        assertEquals(16, resolvedCall.getAsJsonObject("sourceSpan").get("beginColumn").getAsInt());
        assertEquals(9, resolvedCall.getAsJsonObject("sourceSpan").get("endLine").getAsInt());
        assertEquals(38, resolvedCall.getAsJsonObject("sourceSpan").get("endColumn").getAsInt());
        assertNull(resolvedCall.get("exceptionType"));

        JsonObject unresolvedAnnotation = findRelationship(relationships, "ANNOTATED_WITH", "fixture/Caller.java", 3);
        assertEquals("fixture.Caller", unresolvedAnnotation.get("sourceIdentity").getAsString());
        assertEquals("UNRESOLVED", unresolvedAnnotation.get("resolutionStatus").getAsString());
        assertEquals("UNRESOLVED", unresolvedAnnotation.get("targetOrigin").getAsString());
        assertNotNull(unresolvedAnnotation.get("exceptionType"));
        assertNotNull(unresolvedAnnotation.get("exceptionMessage"));
    }

    @Test
    void evaluatesGroundTruthAsCorrectIncorrectUnresolvedOrOmitted() throws Exception {
        Path sourceRoot = fixtureSourceRoot();
        Path output = temporaryDirectory.resolve("ground-truth-result.json");
        Path groundTruth = temporaryDirectory.resolve("ground-truth.json");
        Files.writeString(groundTruth, """
                [
                  {
                    "id": "GT-CORRECT",
                    "description": "Overloaded String call resolves to the String overload",
                    "category": "CALLS",
                    "sourceIdentity": "fixture.Caller#call()",
                    "sourceFile": "fixture/Caller.java",
                    "sourceSpan": {"beginLine": 9, "beginColumn": 16, "endLine": 9, "endColumn": 38},
                    "expectedTargetIdentity": "fixture.Service.choose(java.lang.String)",
                    "expectedResolutionByConfiguration": {"CONFIG_TEST": "RESOLVED"}
                  },
                  {
                    "id": "GT-INCORRECT",
                    "description": "Harness detects an intentionally wrong target label",
                    "category": "CALLS",
                    "sourceIdentity": "fixture.Caller#call()",
                    "sourceFile": "fixture/Caller.java",
                    "sourceSpan": {"beginLine": 9, "beginColumn": 16, "endLine": 9, "endColumn": 38},
                    "expectedTargetIdentity": "fixture.Service.choose(int)",
                    "expectedResolutionByConfiguration": {"CONFIG_TEST": "RESOLVED"}
                  },
                  {
                    "id": "GT-UNRESOLVED",
                    "description": "Missing annotation is an expected unresolved case",
                    "category": "ANNOTATED_WITH",
                    "sourceIdentity": "fixture.Caller",
                    "sourceFile": "fixture/Caller.java",
                    "sourceSpan": {"beginLine": 3, "beginColumn": 1, "endLine": 3, "endColumn": 14},
                    "expectedTargetIdentity": "fixture.MissingMarker",
                    "expectedResolutionByConfiguration": {"CONFIG_TEST": "UNRESOLVED"}
                  },
                  {
                    "id": "GT-OMITTED",
                    "description": "Missing evidence is reported as omitted",
                    "category": "CALLS",
                    "sourceIdentity": "fixture.Caller#call()",
                    "sourceFile": "fixture/Caller.java",
                    "sourceSpan": {"beginLine": 99, "beginColumn": 1, "endLine": 99, "endColumn": 2},
                    "expectedTargetIdentity": "fixture.Service.choose(java.lang.String)",
                    "expectedResolutionByConfiguration": {"CONFIG_TEST": "RESOLVED"}
                  }
                ]
                """);

        ParserEvalApp.main(new String[]{sourceRoot.toString(), output.toString(), "CONFIG_TEST",
                groundTruth.toString()});

        JsonObject result = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
        JsonArray verification = result.getAsJsonArray("groundTruthResults");
        assertNotNull(verification, "Ground truth must be evaluated by the executable harness");

        assertGroundTruth(verification, "GT-CORRECT", true, true, "CORRECTLY_RESOLVED");
        assertGroundTruth(verification, "GT-INCORRECT", true, false, "INCORRECTLY_RESOLVED");
        assertGroundTruth(verification, "GT-UNRESOLVED", true, true, "UNRESOLVED");
        assertGroundTruth(verification, "GT-OMITTED", false, false, "OMITTED");
    }

    @Test
    void recordsReproducibleRuntimeAndExactClasspathProvenance() throws Exception {
        Path sourceRoot = fixtureSourceRoot();
        Path output = temporaryDirectory.resolve("provenance-result.json");
        Path groundTruth = temporaryDirectory.resolve("empty-ground-truth.json");
        Files.writeString(groundTruth, "[]");
        Path gsonJar = Path.of(Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path classpathManifest = temporaryDirectory.resolve("classpath.txt");
        Files.writeString(classpathManifest, gsonJar.toString() + System.lineSeparator());

        ParserEvalApp.main(new String[]{sourceRoot.toString(), output.toString(), "CONFIG_PROVENANCE",
                groundTruth.toString(), classpathManifest.toString(), "fixture-commit"});

        JsonObject experiment = JsonParser.parseString(Files.readString(output)).getAsJsonObject()
                .getAsJsonObject("experiment");
        assertEquals("CONFIG_PROVENANCE", experiment.get("configurationId").getAsString());
        assertNotNull(experiment.get("targetRepositoryCommit"),
                "Experiment output must carry the target revision rather than relying on the report");
        assertEquals("fixture-commit", experiment.get("targetRepositoryCommit").getAsString());
        assertEquals("21", experiment.get("compilerRelease").getAsString());
        assertEquals("3.26.1", experiment.get("javaParserVersion").getAsString());
        assertEquals("JAVA_21", experiment.get("parserLanguageLevel").getAsString());
        assertFalse(experiment.get("runtimeJavaVersion").getAsString().isBlank());
        assertFalse(experiment.get("runtimeJavaVendor").getAsString().isBlank());
        assertFalse(experiment.get("commandLine").getAsString().isBlank());
        assertEquals(64, experiment.get("evaluatorArtifactSha256").getAsString().length());
        assertTrue(experiment.get("runtimeDurationMillis").getAsLong() >= 0);
        Instant.parse(experiment.get("timestampUtc").getAsString());

        JsonArray entries = experiment.getAsJsonArray("classpathManifest");
        assertEquals(1, entries.size());
        JsonObject entry = entries.get(0).getAsJsonObject();
        assertEquals(gsonJar.toAbsolutePath().normalize().toString(), entry.get("path").getAsString());
        assertTrue(entry.get("sizeBytes").getAsLong() > 0);
        assertEquals(64, entry.get("sha256").getAsString().length());
    }

    @Test
    void serializesSummaryMapsInStableEnumOrder() throws Exception {
        Path output = temporaryDirectory.resolve("stable-summary.json");
        ParserEvalApp.main(new String[]{fixtureSourceRoot().toString(), output.toString()});

        JsonObject summary = JsonParser.parseString(Files.readString(output)).getAsJsonObject()
                .getAsJsonObject("summary");
        assertEquals(List.of("EXTENDS", "IMPLEMENTS", "ANNOTATED_WITH", "RETURNS",
                        "CONSTRUCTOR_PARAMETER", "CALLS"),
                propertyNames(summary.getAsJsonObject("byCategory")));
        assertEquals(List.of("PROJECT_LOCAL", "JDK", "LANGUAGE"),
                propertyNames(summary.getAsJsonObject("resolvedByOrigin")));
    }

    private Path fixtureSourceRoot() throws URISyntaxException {
        return Path.of(getClass().getResource("/fixture-src").toURI());
    }

    private JsonObject findRelationship(JsonArray relationships, String category, String sourceFile, int beginLine) {
        for (var element : relationships) {
            JsonObject relationship = element.getAsJsonObject();
            if (category.equals(relationship.get("category").getAsString())
                    && sourceFile.equals(relationship.get("sourceFile").getAsString())
                    && beginLine == relationship.getAsJsonObject("sourceSpan").get("beginLine").getAsInt()) {
                return relationship;
            }
        }
        throw new AssertionError("Missing relationship " + category + " at " + sourceFile + ":" + beginLine);
    }

    private void assertGroundTruth(JsonArray results, String id, boolean attempted, boolean passed, String outcome) {
        for (var element : results) {
            JsonObject result = element.getAsJsonObject();
            if (id.equals(result.get("id").getAsString())) {
                assertEquals(attempted, result.get("attempted").getAsBoolean());
                assertEquals(passed, result.get("passed").getAsBoolean());
                assertEquals(outcome, result.get("outcome").getAsString());
                return;
            }
        }
        throw new AssertionError("Missing ground-truth result " + id);
    }

    private List<String> propertyNames(JsonObject object) {
        List<String> names = new ArrayList<>();
        object.entrySet().forEach(entry -> names.add(entry.getKey()));
        return names;
    }
}
