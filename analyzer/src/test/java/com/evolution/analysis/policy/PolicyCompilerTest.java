package com.evolution.analysis.policy;

import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.evidence.EvidenceContext;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class PolicyCompilerTest {
    static final EvidenceContext CONTEXT = EvidenceContext.forSnapshot(SnapshotIdentity.from(
            RepositoryIdentity.fromCanonicalCoordinate("https://example.test/policy.git"), ContentDigest.sha256Utf8("snapshot")));
    static final String VALID = """
            schema: architecture-policy:v1
            id: example.boundaries
            version: "1"
            rules:
              - id: web.persistence
                version: "1"
                kind: FORBIDDEN_DEPENDENCY
                severity: ERROR
                scope: TYPE
                relationships: [graph.depends-on]
                from: {packages: [com.example.web.**]}
                to: {packages: [com.example.persistence.**]}
            """;
    static PolicyCompiler.Result compile(String yaml) {
        return new PolicyCompiler().compile(yaml.getBytes(StandardCharsets.UTF_8), CONTEXT, PolicyCompiler.Limits.defaults());
    }
    @Test void compilesTypedRuleWithExactByteProvenance() {
        var result = compile(VALID);
        assertEquals(AssessmentStatus.COMPLETE, result.status());
        assertTrue(result.gaps().isEmpty());
        assertEquals(ContentDigest.sha256Utf8(VALID), result.source());
        assertEquals(1, result.policy().orElseThrow().rules().size());
        assertEquals("web.persistence", result.policy().orElseThrow().rules().getFirst().header().id());
        assertTrue(result.locations().get("$.rules[0].from").line()>0);
        assertEquals(result,compile(VALID));assertEquals(result.identity(),compile(VALID).identity());
    }
    static Stream<String> invalidPolicies() {
        return Stream.of("", "---\n", VALID+"unknown: secret-value\n", VALID.replace("schema: architecture-policy:v1","schema: future:v2"),
                VALID.replace("version: \"1\"","version: 1"), VALID.replace("severity: ERROR","severity: BANANA"),
                VALID.replace("kind: FORBIDDEN_DEPENDENCY","kind: EXECUTE"), VALID.replace("scope: TYPE","scope: ENTITY"),
                VALID.replace("graph.depends-on","graph.contains"), VALID.replace("from: {packages: [com.example.web.**]}","from: {}"),
                VALID.replace("com.example.web.**","com.*.web"), VALID.replace("id: example.boundaries","id: duplicate.id\nid: second.id"),
                VALID+"---\nsecond: document\n", VALID.replace("from: {packages: [com.example.web.**]}","from: &selector {packages: [com.example.web.**]}"),
                VALID.replace("from: {packages: [com.example.web.**]}","from: !!java.lang.ProcessBuilder {}"),
                VALID.replace("com.example.web.**","com.example.web.**, com.example.web.**"),
                VALID.replace("from: {packages: [com.example.web.**]}","from: {modules: [host-module-name]}"),
                VALID.replace("from: {packages: [com.example.web.**]}","from: {origins: [UNVERIFIED]}"),
                VALID.replace("from: {packages: [com.example.web.**]}","from: {sourceSets: [GENERATED]}"));
    }
    @ParameterizedTest @MethodSource("invalidPolicies") void rejectsInvalidAndUnsafePolicyAtomically(String yaml) {
        var result=compile(yaml);assertEquals(AssessmentStatus.WITHHELD,result.status());assertTrue(result.policy().isEmpty());
        assertFalse(result.problems().isEmpty());assertEquals(result.problems().size(),result.gaps().size());
        assertFalse(com.evolution.analysis.contract.serialization.CanonicalJson.write(result).contains("secret-value"));
    }
    @Test void compilesAllFourRuleKindsAndCanonicalizesOrder() {
        String more="""
              - id: module.boundary
                version: "2"
                kind: BOUNDARY
                severity: WARNING
                scope: PACKAGE
                relationships: [graph.depends-on]
                from: {packages: [com.example.web.**]}
                allowed: {packages: [com.example.service.**, com.example.web.**]}
              - id: architecture.layers
                version: "1"
                kind: LAYER_BOUNDARY
                severity: ERROR
                scope: TYPE
                relationships: [graph.depends-on]
                layers:
                  - {name: web, select: {packages: [com.example.web.**]}}
                  - {name: service, select: {packages: [com.example.service.**]}}
                allowed:
                  - {from: web, to: [service]}
                  - {from: service, to: []}
              - id: package.acyclic
                version: "1"
                kind: ACYCLIC
                severity: INFO
                scope: PACKAGE
                relationships: [graph.depends-on]
                within: {packages: [com.example.**]}
            """;
        var result=compile(VALID+more);assertEquals(AssessmentStatus.COMPLETE,result.status(),result.problems().toString());
        var policy=result.policy().orElseThrow();assertEquals(4,policy.rules().size());
        assertEquals(Set.of(ArchitecturePolicy.Kind.values()),new HashSet<>(policy.rules().stream().map(ArchitecturePolicy.Rule::kind).toList()));
        var reformatted=compile((VALID+more).replace("severity: ERROR","severity:    ERROR").replace("[graph.depends-on]","[ graph.depends-on ]"));
        assertNotEquals(result.source(),reformatted.source());assertEquals(policy.identity(),reformatted.policy().orElseThrow().identity());
        assertNotEquals(result.identity(),reformatted.identity());
        assertThrows(UnsupportedOperationException.class,()->policy.rules().clear());
        assertThrows(UnsupportedOperationException.class,()->policy.rules().getFirst().header().relationships().clear());
        assertEquals(AssessmentStatus.WITHHELD,compile((VALID+more).replace("to: [service]","to: [missing]")).status());
    }
    @Test void retainsIndependentInvalidRuleRowsAndDuplicateIds() {
        String row=VALID.substring(VALID.indexOf("  - id:"));
        var result=compile(VALID+row);assertTrue(result.policy().isEmpty());assertEquals("DUPLICATE_RULE_ID",result.problems().getFirst().code());
        result=compile(VALID.replace("scope: TYPE","scope: WRONG")+row.replace("severity: ERROR","severity: WRONG"));
        assertEquals(2,result.problems().size());assertEquals(2,result.gaps().size());
    }
    @Test void rejectsMalformedEncodingAndEachDeterministicBudget() {
        var compiler=new PolicyCompiler();var limits=PolicyCompiler.Limits.defaults();
        var invalid=compiler.compile(new byte[]{(byte)0xc3,0x28},CONTEXT,limits);assertEquals("INVALID_UTF8",invalid.problems().getFirst().code());
        byte[] bytes=VALID.getBytes(StandardCharsets.UTF_8);
        assertEquals("INPUT_LIMIT",compiler.compile(bytes,CONTEXT,new PolicyCompiler.Limits(bytes.length-1,256,20_000)).problems().getFirst().code());
        assertEquals("AST_LIMIT",compiler.compile(bytes,CONTEXT,new PolicyCompiler.Limits(262_144,256,1)).problems().getFirst().code());
        assertEquals(AssessmentStatus.COMPLETE,compiler.compile(bytes,CONTEXT,new PolicyCompiler.Limits(bytes.length,1,20_000)).status());
        assertThrows(IllegalArgumentException.class,()->new PolicyCompiler.Limits(2_000_000,256,20_000));
    }
    @Test void shippedExampleCompilesAndParserVersionIsPinned() throws Exception {
        try(var stream=PolicyCompilerTest.class.getResourceAsStream("/policy/examples/layered-architecture.yaml")) {
            assertNotNull(stream);var result=new PolicyCompiler().compile(stream.readAllBytes(),CONTEXT,PolicyCompiler.Limits.defaults());
            assertEquals(AssessmentStatus.COMPLETE,result.status(),result.problems().toString());assertEquals(4,result.policy().orElseThrow().rules().size());
        }
        assertEquals("m6.1-v1",ArchitecturePolicy.VERSION.version());assertEquals("m6.1-v1",PolicyCompiler.VERSION.version());
        assertEquals("snakeyaml-2.6-m6.1-v1",PolicyCompiler.SYNTAX.version());
        try(var stream=org.yaml.snakeyaml.Yaml.class.getResourceAsStream("/META-INF/maven/org.yaml/snakeyaml/pom.properties")) {
            assertNotNull(stream);var properties=new Properties();properties.load(stream);assertEquals("2.6",properties.getProperty("version"));
        }
    }
}
