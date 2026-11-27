package com.evolution.backend.query;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import static org.junit.jupiter.api.Assertions.*;

class ApiSpecificationTest {
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object value) { return (Map<String,Object>)value; }
    @Test void packagedOpenApiNamesEveryImplementedRouteAndOperation() throws Exception {
        try(var input=getClass().getResourceAsStream("/api/openapi.yaml")) {
            assertNotNull(input); var spec=map(new Yaml().load(input)); assertEquals("3.1.0",spec.get("openapi"));
            var paths=map(spec.get("paths"));
            var expectedEvolution=Set.of("jobs","jobs/{job}","jobs/{job}/cancel","jobs/{job}/resume",
                    "results/{result}","results/{result}/query","results/{result}/export","results/{result}/graph","results/{result}/evidence");
            var evolutionPrefix="/api/v1/evolution/";
            assertEquals(expectedEvolution,paths.keySet().stream().filter(path->path.startsWith(evolutionPrefix))
                    .map(path->path.substring(evolutionPrefix.length())).collect(java.util.stream.Collectors.toSet()));
            for(String operation:List.of("summary","contexts","gaps","root-gaps","answers"))assertTrue(paths.containsKey("/api/v1/semantic/"+operation));
            for(String path:List.of("/api/v2/acquisitions/{revision}","/api/v2/acquisitions/history","/api/v2/acquisitions/{revision}/readiness","/api/v2/analysis/jobs/{job}/resume"))assertTrue(paths.containsKey(path));
            assertTrue(paths.containsKey("/api/v1/analysis/connections"));
            assertTrue(paths.containsKey("/api/v1/analysis/connections/{setup}"));
            assertTrue(paths.containsKey("/api/v1/analysis/connections/{setup}/disconnect"));
            for (var path : List.of("/api/v1/graphs/{graph}/captured-files","/api/v1/graphs/{graph}/captured-source","/api/v1/graphs/{graph}/search-captured-source","/api/v1/graphs/{graph}/repo-map","/api/v1/graphs/{graph}/architecture-score")) assertTrue(paths.containsKey(path));
            for (var path : List.of("/api/v1/analysis/setups","/api/v1/analysis/jobs","/api/v1/analysis/jobs/history","/api/v1/analysis/jobs/{job}","/api/v1/analysis/jobs/{job}/progress","/api/v1/analysis/jobs/{job}/events","/api/v1/analysis/jobs/{job}/cancel","/api/v1/analysis/jobs/{job}/gaps","/api/v1/analysis/jobs/{job}/input-diagnostics")) assertTrue(paths.containsKey(path));
            for (var path : List.of(
                    "/api/v1/github/auth/status", "/api/v1/github/auth/session", "/api/v1/github/refs",
                    "/api/v1/github/connections/{setup}/metadata", "/api/v1/github/connections/{setup}/branches",
                    "/api/v1/github/connections/{setup}/tags", "/api/v1/github/connections/{setup}/commits",
                    "/api/v1/github/connections/{setup}/pulls", "/api/v1/github/connections/{setup}/pulls/{number}",
                    "/api/v1/github/connections/{setup}/pulls/{number}/checks", "/api/v1/github/connections/{setup}/compare",
                    "/api/v1/github/comparisons/{diffId}", "/api/v1/github/connections/{setup}/pulls/{number}/review-comment",
                    "/api/v1/github/repairs/prepare", "/api/v1/github/repairs/publish",
                    "/api/v1/auth/config", "/api/v1/auth/me",
                    "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/profile",
                    "/api/v1/auth/tokens", "/api/v1/auth/tokens/{tokenId}",
                    "/api/v1/auth/github/unlink", "/api/v1/auth/users", "/api/v1/auth/users/{userId}/role",
                    "/api/v1/auth/github/setup", "/api/v1/auth/github/manifest/prepare",
                    "/api/v1/auth/github/manifest/callback",
                    "/api/v1/auth/github/login", "/api/v1/auth/github/callback",
                    "/api/v1/auth/github/device/start", "/api/v1/auth/github/device/poll",
                    "/api/v1/auth/logout", "/api/v1/auth/logout-all",
                    "/api/v1/github/orgs", "/api/v1/github/user/repos",
                    "/api/v1/github/webhooks", "/api/v1/github/webhooks/deliveries",
                    "/api/v1/github/connections/{setup}/pulls/enriched",
                    "/api/v1/github/connections/{setup}/pulls/{number}/review/preview",
                    "/api/v1/github/connections/{setup}/pulls/{number}/review/publish",
                    "/api/v1/github/connections/{setup}/commits/{sha}/status",
                    "/api/v1/github/connections/{setup}/code-scanning/sarif",
                    "/api/v1/github/connections/{setup}/issues/prepare",
                    "/api/v1/github/connections/{setup}/issues/publish",
                    "/api/v1/github/connections/{setup}/ci-workflow",
                    "/api/v1/graphs/{graph}/codeowners",
                    "/api/v1/graphs/{graph}/reports/{report}/sarif",
                    "/api/v1/ai/providers", "/api/v1/ai/providers/probe",
                    "/api/v1/graphs/{graph}/repair-proposal/ad-hoc",
                    "/api/v1/graphs/{graph}/reports/{report}/repair-verification/jobs",
                    "/api/v1/repairs/sandbox/setup", "/api/v1/repairs/sandbox/probe",
                    "/api/v1/repairs/{proposalId}/verify", "/api/v1/repairs/jobs", "/api/v1/repairs/jobs/{jobId}",
                    "/api/v1/repairs/jobs/{jobId}/events", "/api/v1/repairs/jobs/{jobId}/report.md",
                    "/api/v1/repairs/jobs/{jobId}/cancel",
                    "/api/v1/repairs/{proposalId}/apply/preview", "/api/v1/repairs/{proposalId}/apply/confirm",
                    "/api/v1/repairs/applies/{applyId}/rollback",
                    "/api/v2/executions/{operation}")) {
                assertTrue(paths.containsKey(path), "Missing OpenAPI path: " + path);
            }
            assertOperations(paths,"/api/v1/graphs/{graph}/queries/{operation}",ArchitectureQueryApi.OPERATIONS);
            assertOperations(paths,"/api/v1/graphs/{graph}/reports/{report}/{operation}",ArchitectureQueryApi.REPORT_OPERATIONS);
            checkReferences(spec,spec);
            var schemas=map(map(spec.get("components")).get("schemas"));
            for (var schemaName : List.of("TruthTier","SemanticTrustTier","BehavioralVerdict","SpringPreservationStatus","RepairVerificationJobStage","RepairRestartRecoveryErrorCode","RepairTrustLabel","PreviewMeta","PreviewPublication","IncrementalMetrics","ModuleProgress","LiveProgress")) assertTrue(schemas.containsKey(schemaName));
            assertEquals(Arrays.stream(com.evolution.backend.query.repair.RepairVerificationJobs.Stage.values()).map(Enum::name).toList(),
                    map(schemas.get("RepairVerificationJobStage")).get("enum"),
                    "RepairVerificationJobStage enum must match RepairVerificationJobs.Stage including INTERRUPTED_ON_RESTART");
            assertEquals(Arrays.stream(com.evolution.backend.query.repair.RepairVerificationReport.TrustLabel.values()).map(Enum::name).toList(),
                    map(schemas.get("RepairTrustLabel")).get("enum"),
                    "RepairTrustLabel enum must match RepairVerificationReport.TrustLabel");
            var verifyPost = map(map(paths.get("/api/v1/repairs/{proposalId}/verify")).get("post"));
            var verifySchema = map(map(map(map(verifyPost.get("requestBody")).get("content")).get("application/json")).get("schema"));
            assertTrue(map(verifySchema.get("properties")).containsKey("proposal"),
                    "/api/v1/repairs/{proposalId}/verify must include 'proposal' property");
            var reportJobsPost = map(map(paths.get("/api/v1/graphs/{graph}/reports/{report}/repair-verification/jobs")).get("post"));
            var reportJobsSchema = map(map(map(map(reportJobsPost.get("requestBody")).get("content")).get("application/json")).get("schema"));
            assertEquals(Boolean.FALSE, reportJobsSchema.get("additionalProperties"),
                    "/api/v1/graphs/{graph}/reports/{report}/repair-verification/jobs must disallow arbitrary additionalProperties");
            assertTrue(map(reportJobsSchema.get("properties")).keySet().containsAll(Set.of("graph", "report", "proposal", "arguments")));
            var incProps = map(map(schemas.get("IncrementalMetrics")).get("properties")).keySet();
            var expectedInc = Arrays.stream(com.evolution.backend.analysis.AnalysisJobs.IncrementalMetrics.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName).collect(java.util.stream.Collectors.toSet());
            assertEquals(expectedInc, incProps, "IncrementalMetrics OpenAPI schema drifted from AnalysisJobs.IncrementalMetrics");
            var liveProps = map(map(schemas.get("LiveProgress")).get("properties")).keySet();
            var expectedLive = Arrays.stream(com.evolution.backend.analysis.AnalysisJobs.LiveProgress.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName).collect(java.util.stream.Collectors.toSet());
            assertEquals(expectedLive, liveProps, "LiveProgress OpenAPI schema drifted from AnalysisJobs.LiveProgress");
            var states = map(map(schemas.get("AnalysisJob")).get("properties"));
            assertTrue(states.keySet().containsAll(Set.of("publicationStage","previewGraphId","preview","incremental")));
            assertEquals(Arrays.stream(com.evolution.backend.analysis.AnalysisJobs.State.values()).map(Enum::name).toList(),map(states.get("state")).get("enum"));
            var limits=map(map(schemas.get("Limits")).get("properties"));
            assertEquals(20,map(limits.get("maxDepth")).get("maximum")); assertEquals(100000,map(limits.get("maxSteps")).get("maximum"));
            assertEquals(50,map(limits.get("maxResults")).get("maximum"));

            var registerPost = map(map(paths.get("/api/v1/auth/register")).get("post"));
            var registerSchema = map(map(map(map(registerPost.get("requestBody")).get("content")).get("application/json")).get("schema"));
            assertEquals(List.of("email", "password"), registerSchema.get("required"),
                    "/api/v1/auth/register must make username optional in OpenAPI");
            assertTrue(map(registerSchema.get("properties")).keySet().containsAll(Set.of("email", "username", "displayName", "password")));
            String authConfigDesc = String.valueOf(map(map(paths.get("/api/v1/auth/config")).get("get")).get("description"));
            assertTrue(authConfigDesc.contains("publicBaseUrl") && authConfigDesc.contains("isLoopbackHost")
                    && authConfigDesc.contains("webhookReachable"), authConfigDesc);
            String setupGetDesc = String.valueOf(map(map(paths.get("/api/v1/auth/github/setup")).get("get")).get("description"));
            assertTrue(setupGetDesc.contains("publicBaseUrl") && setupGetDesc.contains("isLoopbackHost")
                    && setupGetDesc.contains("webhookReachable"), setupGetDesc);
        }
    }
    static void assertOperations(Map<String,Object> paths,String path,List<String> expected) {
        var parameters=(List<?>)map(paths.get(path)).get("parameters");
        var operation=parameters.stream().map(ApiSpecificationTest::map).filter(p -> "operation".equals(p.get("name"))).findFirst().orElseThrow();
        assertEquals(expected,map(operation.get("schema")).get("enum"));
    }
    static void checkReferences(Object value,Map<String,Object> root) {
        if(value instanceof Map<?,?> row) {
            if(row.get("$ref") instanceof String ref) {
                assertTrue(ref.startsWith("#/")); Object target=root;
                for(String part:ref.substring(2).split("/")) target=map(target).get(part);
                assertNotNull(target,ref);
            }
            row.values().forEach(v -> checkReferences(v,root));
        } else if(value instanceof List<?> list) list.forEach(v -> checkReferences(v,root));
    }
}
