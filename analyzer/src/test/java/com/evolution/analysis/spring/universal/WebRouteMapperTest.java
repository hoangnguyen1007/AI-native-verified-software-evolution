package com.evolution.analysis.spring.universal;

import static org.junit.jupiter.api.Assertions.*;

import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.spring.SpringFrameworkEvidence;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;

class WebRouteMapperTest {
    private static final VersionedIdentifier PROVIDER = new VersionedIdentifier("frontend.test", "1");
    private static final Derivation DIRECT = new Derivation(DerivationKind.DIRECT, PROVIDER, List.of());
    private static final RepositoryIdentity REPO =
            RepositoryIdentity.fromCanonicalCoordinate("https://example.test/web-routes.git");

    @Test
    void mapsSourceScopedWebMappingsAndMediaTypeConstantsWithoutSpringWebJar() {
        String explicitController = """
                package demo;
                import org.springframework.web.bind.annotation.RestController;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.http.MediaType;
                @RestController
                @RequestMapping("/api")
                public class ExplicitController {
                    @GetMapping(value = "/html", produces = MediaType.TEXT_HTML_VALUE)
                    public String html() { return "ok"; }
                    @PostMapping(path = "/json", consumes = org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED_VALUE,
                            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_JSON_UTF8_VALUE})
                    public String json() { return "{}"; }
                }
                """;
        String wildcardController = """
                package demo;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class WildcardController {
                    @PutMapping("/items/{id}")
                    public void put() {}
                    @DeleteMapping("/items/{id}")
                    public void delete() {}
                    @PatchMapping("/items/{id}")
                    public void patch() {}
                }
                """;
        String nonSpringController = """
                package demo;
                import org.springframework.web.bind.annotation.RestController;
                import com.example.custom.GetMapping;
                @RestController
                public class CustomMappingController {
                    @GetMapping("/ignored")
                    public void custom() {}
                }
                """;
        String badMediaController = """
                package demo;
                import org.springframework.web.bind.annotation.RestController;
                import org.springframework.web.bind.annotation.GetMapping;
                @RestController
                public class BadMediaController {
                    @GetMapping(value = "/bad", produces = CustomMedia.UNKNOWN_VALUE)
                    public void bad() {}
                }
                """;

        var module = ModuleDescriptor.create(REPO, ".", "app");
        var docsByPath = new LinkedHashMap<String, SourceDocument>();
        var files = new ArrayList<SnapshotFile>();
        var docs = new ArrayList<SourceDocument>();
        var sourceInputs = new ArrayList<SourceInput>();
        for (var e : new TreeMap<>(Map.of(
                "src/main/java/demo/ExplicitController.java", explicitController,
                "src/main/java/demo/WildcardController.java", wildcardController,
                "src/main/java/demo/CustomMappingController.java", nonSpringController,
                "src/main/java/demo/BadMediaController.java", badMediaController)).entrySet()) {
            byte[] bytes = e.getValue().getBytes(StandardCharsets.UTF_8);
            var doc = SourceDocument.create(REPO, module, e.getKey(), ContentDigest.sha256(bytes), SourceClassification.MAIN);
            docs.add(doc);
            files.add(SnapshotFile.from(doc));
            sourceInputs.add(new SourceInput(doc, bytes));
            docsByPath.put(e.getKey(), doc);
        }

        var snapshot = RepositorySnapshot.create(REPO, Optional.empty(), false, files, docs);
        var platform = PlatformInput.create(21, "21", "test", List.of(
                new PlatformInput.Artifact("java.base.jmod", ContentDigest.sha256Utf8("jbase"),
                        Path.of("java.base.jmod"), PlatformInput.Format.JMOD)));
        var plan = new FrontendPlan(Optional.of(21), Optional.of(21), false,
                ContentDigest.sha256Utf8("cp"), ContentDigest.sha256Utf8("dec"));
        var options = FrontendRequest.options(plan, module.identity(), SourceClassification.MAIN, docs, platform);
        var comp = new ManifestComponent(PROVIDER, ContentDigest.sha256Utf8("comp"));
        var manifest = AnalysisManifest.create(PROVIDER, snapshot, List.of(module), List.of(platform.entry()),
                AnalysisConfiguration.create(PROVIDER, options), comp, comp, comp);
        var request = new FrontendRequest(manifest, module.identity(), SourceClassification.MAIN,
                plan, sourceInputs, platform, List.of());

        var scope = EntityScope.project(module.identity());
        var declarations = new ArrayList<DeclarationRecord>();
        var typeDecls = new ArrayList<TypeDeclarationRecord>();
        var anns = new ArrayList<AnnotationUseRecord>();
        var rels = new ArrayList<RelationshipOccurrence>();

        var explicitDoc = docsByPath.get("src/main/java/demo/ExplicitController.java");
        var explicitType = typeEntity(scope, explicitDoc, "demo", "ExplicitController", 9);
        var htmlMethod = methodEntity(scope, explicitDoc, "demo", "ExplicitController", "html", 11);
        var jsonMethod = methodEntity(scope, explicitDoc, "demo", "ExplicitController", "json", 14);
        addController(declarations, typeDecls, explicitType, "public class ExplicitController {}");
        addMethod(declarations, rels, explicitType, htmlMethod, "public String html()");
        addMethod(declarations, rels, explicitType, jsonMethod, "public String json()");
        anns.add(ann(explicitDoc, explicitType.identity(), 7, 0, "@RestController"));
        anns.add(ann(explicitDoc, explicitType.identity(), 8, 1, "@RequestMapping(\"/api\")"));
        anns.add(ann(explicitDoc, htmlMethod.identity(), 10, 2,
                "@GetMapping(value = \"/html\", produces = MediaType.TEXT_HTML_VALUE)"));
        anns.add(ann(explicitDoc, jsonMethod.identity(), 12, 3,
                "@PostMapping(path = \"/json\", consumes = org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_JSON_UTF8_VALUE})"));

        var wildcardDoc = docsByPath.get("src/main/java/demo/WildcardController.java");
        var wildcardType = typeEntity(scope, wildcardDoc, "demo", "WildcardController", 4);
        var putMethod = methodEntity(scope, wildcardDoc, "demo", "WildcardController", "put", 6);
        var deleteMethod = methodEntity(scope, wildcardDoc, "demo", "WildcardController", "delete", 8);
        var patchMethod = methodEntity(scope, wildcardDoc, "demo", "WildcardController", "patch", 10);
        addController(declarations, typeDecls, wildcardType, "public class WildcardController {}");
        addMethod(declarations, rels, wildcardType, putMethod, "public void put()");
        addMethod(declarations, rels, wildcardType, deleteMethod, "public void delete()");
        addMethod(declarations, rels, wildcardType, patchMethod, "public void patch()");
        anns.add(ann(wildcardDoc, wildcardType.identity(), 3, 4, "@RestController"));
        anns.add(ann(wildcardDoc, putMethod.identity(), 5, 5, "@PutMapping(\"/items/{id}\")"));
        anns.add(ann(wildcardDoc, deleteMethod.identity(), 7, 6, "@DeleteMapping(\"/items/{id}\")"));
        anns.add(ann(wildcardDoc, patchMethod.identity(), 9, 7, "@PatchMapping(\"/items/{id}\")"));

        var customDoc = docsByPath.get("src/main/java/demo/CustomMappingController.java");
        var customType = typeEntity(scope, customDoc, "demo", "CustomMappingController", 5);
        var customMethod = methodEntity(scope, customDoc, "demo", "CustomMappingController", "custom", 7);
        addController(declarations, typeDecls, customType, "public class CustomMappingController {}");
        addMethod(declarations, rels, customType, customMethod, "public void custom()");
        anns.add(ann(customDoc, customType.identity(), 4, 8, "@RestController"));
        anns.add(ann(customDoc, customMethod.identity(), 6, 9, "@GetMapping(\"/ignored\")"));

        var badDoc = docsByPath.get("src/main/java/demo/BadMediaController.java");
        var badType = typeEntity(scope, badDoc, "demo", "BadMediaController", 5);
        var badMethod = methodEntity(scope, badDoc, "demo", "BadMediaController", "bad", 7);
        addController(declarations, typeDecls, badType, "public class BadMediaController {}");
        addMethod(declarations, rels, badType, badMethod, "public void bad()");
        anns.add(ann(badDoc, badType.identity(), 4, 10, "@RestController"));
        anns.add(ann(badDoc, badMethod.identity(), 6, 11,
                "@GetMapping(value = \"/bad\", produces = CustomMedia.UNKNOWN_VALUE)"));

        var observations = rels.stream()
                .map(occ -> new ObservationRecord(occ.span().document(), occ.relationship().kind(),
                        Optional.of(occ.span()), SemanticStatus.RESOLVED,
                        ObservationRecord.EvidenceState.VERIFIED, ObservationRecord.EvidenceState.VERIFIED,
                        Optional.of(occ.identity()), occ.relationship().kind().value(), List.of()))
                .toList();
        var outcomes = docs.stream()
                .map(d -> new SourceOutcome(d.identity(), SourceOutcome.State.PROCESSED, List.of()))
                .toList();
        var coverage = FrontendRequest.CATEGORIES.stream()
                .map(c -> new CategoryCoverage(new RelationshipKind("java." + c), CategoryCoverage.Support.IMPLEMENTED,
                        c.equals("declares") ? rels.size() : 0, c.equals("declares") ? rels.size() : 0, 0))
                .toList();
        var frontend = new FrontendResult(manifest.identity(), PROVIDER, FrontendResult.State.COMPLETED,
                declarations, rels, observations, outcomes, coverage, List.of(), List.of(), anns, List.of(), typeDecls);

        var framework = new SpringFrameworkEvidence(false, Optional.of(plan.classpathManifest()), List.of());
        var evidence = new SpringSourceEvidence(request, frontend, framework,
                SpringFrameworkEvidence.ValidationMode.COMPATIBLE_FAMILY_OR_SOURCE_SCOPED);
        var result = WebRouteMapper.map(evidence, Map.of(), 100);

        assertEquals(5, result.routes().size(), "Expected 5 mapped routes across explicit and wildcard controllers: " + result.routes());

        var htmlRoute = result.routes().stream().filter(r -> r.path().equals("/api/html")).findFirst().orElseThrow();
        assertEquals(List.of("GET"), htmlRoute.methods());
        assertEquals(List.of("text/html"), htmlRoute.produces());

        var jsonRoute = result.routes().stream().filter(r -> r.path().equals("/api/json")).findFirst().orElseThrow();
        assertEquals(List.of("POST"), jsonRoute.methods());
        assertEquals(List.of("application/x-www-form-urlencoded"), jsonRoute.consumes());
        assertEquals(List.of("application/json", "application/json;charset=UTF-8"), jsonRoute.produces());

        assertTrue(result.routes().stream().anyMatch(r -> r.path().equals("/items/{id}") && r.methods().equals(List.of("PUT"))));
        assertTrue(result.routes().stream().anyMatch(r -> r.path().equals("/items/{id}") && r.methods().equals(List.of("DELETE"))));
        assertTrue(result.routes().stream().anyMatch(r -> r.path().equals("/items/{id}") && r.methods().equals(List.of("PATCH"))));

        // Negative controls: non-Spring @GetMapping is ignored (not mapped); unknown MediaType constant stays UNKNOWN
        assertFalse(result.routes().stream().anyMatch(r -> r.path().contains("ignored")),
                "Non-Spring @GetMapping import must never produce a route");
        var customRow = result.rows().stream().filter(r -> r.handler().equals(customMethod.identity())).findFirst().orElseThrow();
        assertEquals(WebRouteMapper.Status.UNKNOWN, customRow.status());
        var badRow = result.rows().stream().filter(r -> r.handler().equals(badMethod.identity())).findFirst().orElseThrow();
        assertEquals(WebRouteMapper.Status.UNKNOWN, badRow.status());
    }

    private static Entity typeEntity(EntityScope scope, SourceDocument doc, String pkg, String name, int line) {
        return Entity.create(EntityOrigin.PROJECT, scope, EntityKind.TYPE,
                JavaSymbolName.topLevelType(pkg, name).canonicalName(),
                Optional.of(new SourceSpan(doc.identity(), line, 1, line, 20)));
    }

    private static Entity methodEntity(EntityScope scope, SourceDocument doc, String pkg, String typeName, String method, int line) {
        return Entity.create(EntityOrigin.PROJECT, scope, EntityKind.METHOD,
                JavaSymbolName.method(JavaSymbolName.topLevelType(pkg, typeName), method, List.of()).canonicalName(),
                Optional.of(new SourceSpan(doc.identity(), line, 5, line, 25)));
    }

    private static void addController(List<DeclarationRecord> declarations, List<TypeDeclarationRecord> typeDecls,
                                      Entity type, String spelling) {
        declarations.add(new DeclarationRecord(type, spelling, SemanticStatus.RESOLVED, DIRECT, List.of(), List.of()));
        typeDecls.add(new TypeDeclarationRecord(type.identity(), TypeDeclarationRecord.Kind.CLASS, false, true));
    }

    private static void addMethod(List<DeclarationRecord> declarations, List<RelationshipOccurrence> rels,
                                  Entity owner, Entity method, String spelling) {
        declarations.add(new DeclarationRecord(method, spelling, SemanticStatus.RESOLVED, DIRECT, List.of(), List.of()));
        rels.add(RelationshipOccurrence.create(
                SemanticRelationship.create(owner.identity(), new RelationshipKind("java.declares"),
                        new RelationshipTarget.Resolved(method.identity())),
                method.declaration().orElseThrow(), 0, SemanticStatus.RESOLVED, DIRECT, List.of(), List.of()));
    }

    private static AnnotationUseRecord ann(SourceDocument doc, EntityIdentity owner, int line, int idx, String spelling) {
        return new AnnotationUseRecord(
                "java:v1:[\"annotation-use\",\"demo.WebRoutes\"," + idx + "]",
                owner,
                new SourceSpan(doc.identity(), line, 1, line, Math.max(2, spelling.length())),
                "declaration-syntax",
                spelling);
    }
}
