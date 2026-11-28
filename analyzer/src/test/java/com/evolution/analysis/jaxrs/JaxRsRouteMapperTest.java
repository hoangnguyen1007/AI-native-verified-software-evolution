package com.evolution.analysis.jaxrs;

import static org.junit.jupiter.api.Assertions.*;

import com.evolution.analysis.buildmodel.SourcePlanModel;
import com.evolution.analysis.buildmodel.UniversalBuildModel;
import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.ingestion.*;
import com.evolution.analysis.polyglot.HttpServiceCallProjection;
import com.evolution.analysis.polyglot.ServiceArchitectureProjection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;

class JaxRsRouteMapperTest {
    private static final VersionedIdentifier PROVIDER = new VersionedIdentifier("frontend.test", "1");
    private static final Derivation DIRECT = new Derivation(DerivationKind.DIRECT, PROVIDER, List.of());
    private static final RepositoryIdentity REPO =
            RepositoryIdentity.fromCanonicalCoordinate("https://example.test/jaxrs-routes.git");

    @Test
    void mapsCrossModuleInheritedJaxRsResourcesServletsAndRejectsUnimportedAnnotations() throws Exception {
        String abstractSrc = """
                package acme.base;
                import javax.ws.rs.GET;
                import javax.ws.rs.POST;
                import javax.ws.rs.DELETE;
                import javax.ws.rs.Path;
                import javax.ws.rs.Produces;
                import javax.ws.rs.core.MediaType;
                @Produces(MediaType.APPLICATION_JSON)
                public abstract class AbstractCrudResource<T> {
                    @GET @Path("/{id}")
                    public T getOne() { return null; }
                    @POST
                    public T create() { return null; }
                    @DELETE @Path("/{id}")
                    public void remove() {}
                }
                """;
        String catalogSrc = """
                package acme.catalog;
                import acme.base.AbstractCrudResource;
                import jakarta.ws.rs.ApplicationPath;
                import jakarta.ws.rs.PUT;
                import jakarta.ws.rs.Path;
                import jakarta.ws.rs.Produces;
                @ApplicationPath("/api")
                @Path("/catalog")
                public class CatalogResource extends AbstractCrudResource<String> {
                    @PUT @Path("/{id}")
                    @Produces("application/json")
                    public String replace() { return "ok"; }
                }
                """;
        String servletSrc = """
                package acme.web;
                import jakarta.servlet.annotation.WebServlet;
                @WebServlet(urlPatterns = {"/cart", "/checkout"})
                public class CartServlet {
                    protected void doGet() {}
                    protected void doPost() {}
                }
                """;
        String fakeSrc = """
                package acme.fake;
                @interface Path { String value(); }
                @interface WebServlet { String value(); }
                @Path("/fake")
                @WebServlet("/fake-servlet")
                public class FakeResource {
                    public void doGet() {}
                }
                """;

        var modBase = ModuleDescriptor.create(REPO, "mod-base", "mod-base");
        var modService = ModuleDescriptor.create(REPO, "mod-service", "mod-service");
        var rootMod = ModuleDescriptor.create(REPO, "root", ".");

        var rawFiles = new TreeMap<String, SourceInput>();
        var snapshotFiles = new ArrayList<SnapshotFile>();
        var docs = new ArrayList<SourceDocument>();
        var docByPath = new LinkedHashMap<String, SourceDocument>();
        var sources = new TreeMap<String, String>(Map.of(
                "mod-base/src/main/java/acme/base/AbstractCrudResource.java", abstractSrc,
                "mod-service/src/main/java/acme/catalog/CatalogResource.java", catalogSrc,
                "mod-service/src/main/java/acme/web/CartServlet.java", servletSrc,
                "mod-service/src/main/java/acme/fake/FakeResource.java", fakeSrc));
        for (var e : sources.entrySet()) {
            byte[] bytes = e.getValue().getBytes(StandardCharsets.UTF_8);
            var doc = SourceDocument.create(REPO, rootMod, e.getKey(), ContentDigest.sha256(bytes), SourceClassification.MAIN);
            docs.add(doc);
            snapshotFiles.add(SnapshotFile.from(doc));
            rawFiles.put(e.getKey(), new SourceInput(doc, bytes));
            docByPath.put(e.getKey(), doc);
        }
        var rawInputs = new RepositoryInputs(
                RepositorySnapshot.create(REPO, Optional.empty(), false, snapshotFiles, docs), rawFiles);

        var buildModules = new ArrayList<UniversalBuildModel.Module>();
        for (var m : List.of(modBase, modService)) {
            var java21 = new SourcePlanModel.Setting("java.release", Optional.of("21"), Optional.of("21"),
                    SourcePlanModel.Status.DEFAULT, SourcePlanModel.Origin.MAVEN_CONVENTION, List.of());
            var utf8 = new SourcePlanModel.Setting("encoding", Optional.of("UTF-8"), Optional.of("UTF-8"),
                    SourcePlanModel.Status.DEFAULT, SourcePlanModel.Origin.MAVEN_CONVENTION, List.of());
            var mainRoot = new SourcePlanModel.Setting("sourceRoot", Optional.of(m.path() + "/src/main/java"),
                    Optional.of(m.path() + "/src/main/java"), SourcePlanModel.Status.DEFAULT, SourcePlanModel.Origin.MAVEN_CONVENTION, List.of());
            var testRoot = new SourcePlanModel.Setting("testRoot", Optional.of(m.path() + "/src/test/java"),
                    Optional.of(m.path() + "/src/test/java"), SourcePlanModel.Status.DEFAULT, SourcePlanModel.Origin.MAVEN_CONVENTION, List.of());
            var outDir = new SourcePlanModel.Setting("output", Optional.of(m.path() + "/target/classes"),
                    Optional.of(m.path() + "/target/classes"), SourcePlanModel.Status.DEFAULT, SourcePlanModel.Origin.MAVEN_CONVENTION, List.of());
            var testOutDir = new SourcePlanModel.Setting("testOutput", Optional.of(m.path() + "/target/test-classes"),
                    Optional.of(m.path() + "/target/test-classes"), SourcePlanModel.Status.DEFAULT, SourcePlanModel.Origin.MAVEN_CONVENTION, List.of());
            var mainSet = new SourcePlanModel.SourceSetPlan(m.identity(), SourcePlanModel.Kind.MAIN,
                    List.of(mainRoot), List.of(), outDir, Map.of(), java21, java21, java21, utf8, List.of(), List.of());
            var testSet = new SourcePlanModel.SourceSetPlan(m.identity(), SourcePlanModel.Kind.TEST,
                    List.of(testRoot), List.of(), testOutDir, Map.of(), java21, java21, java21, utf8, List.of(), List.of());
            buildModules.add(new UniversalBuildModel.Module(m, UniversalBuildModel.Tool.PLAIN_JAVA, Optional.empty(),
                    Optional.empty(), new SourcePlanModel(List.of(mainSet, testSet), List.of()), List.of(), List.of()));
        }
        var build = new UniversalBuildModel(ContentDigest.sha256Utf8("jaxrs-b"), rawInputs.identity(), buildModules, List.of(), List.of(), List.of());
        var platform = PlatformInput.create(21, "21", "jdk", List.of(
                new PlatformInput.Artifact("java.base", ContentDigest.sha256Utf8("jb"), Path.of("java.base.jmod"), PlatformInput.Format.JMOD)));
        var resolutions = Map.of(
                new UniversalSourceIngestion.SourceSet(modBase.identity(), SourcePlanModel.Kind.MAIN),
                new UniversalSourceIngestion.Resolution(platform, List.of(), platform.entry().contentDigest()),
                new UniversalSourceIngestion.SourceSet(modService.identity(), SourcePlanModel.Kind.MAIN),
                new UniversalSourceIngestion.Resolution(platform, List.of(), platform.entry().contentDigest()));
        var comp = new ManifestComponent(PROVIDER, ContentDigest.sha256Utf8("comp"));
        var sourceIngestion = new UniversalSourceIngestion().assemble(rawInputs, build, resolutions, comp, comp, KnowledgeGraph.schemaComponent());
        var coverage = FrontendRequest.CATEGORIES.stream()
                .map(c -> new CategoryCoverage(new RelationshipKind("java." + c), CategoryCoverage.Support.IMPLEMENTED, 0, 0, 0))
                .toList();

        var baseScope = EntityScope.project(modBase.identity());
        var svcScope = EntityScope.project(modService.identity());
        var absDoc = docByPath.get("mod-base/src/main/java/acme/base/AbstractCrudResource.java");
        var catDoc = docByPath.get("mod-service/src/main/java/acme/catalog/CatalogResource.java");
        var srvDoc = docByPath.get("mod-service/src/main/java/acme/web/CartServlet.java");
        var fakeDoc = docByPath.get("mod-service/src/main/java/acme/fake/FakeResource.java");

        var absType = typeEntity(baseScope, absDoc, "acme.base", "AbstractCrudResource", 9);
        var getOneMethod = methodEntity(baseScope, absDoc, "acme.base", "AbstractCrudResource", "getOne", 11);
        var createMethod = methodEntity(baseScope, absDoc, "acme.base", "AbstractCrudResource", "create", 13);
        var removeMethod = methodEntity(baseScope, absDoc, "acme.base", "AbstractCrudResource", "remove", 15);

        var catType = typeEntity(svcScope, catDoc, "acme.catalog", "CatalogResource", 9);
        var replaceMethod = methodEntity(svcScope, catDoc, "acme.catalog", "CatalogResource", "replace", 11);

        var srvType = typeEntity(svcScope, srvDoc, "acme.web", "CartServlet", 4);
        var doGetMethod = methodEntity(svcScope, srvDoc, "acme.web", "CartServlet", "doGet", 5);
        var doPostMethod = methodEntity(svcScope, srvDoc, "acme.web", "CartServlet", "doPost", 6);

        var fakeType = typeEntity(svcScope, fakeDoc, "acme.fake", "FakeResource", 6);
        var fakeGet = methodEntity(svcScope, fakeDoc, "acme.fake", "FakeResource", "doGet", 7);

        // Build FrontendResults per unit
        var units = new ArrayList<UniversalIngestionPipeline.Unit>();
        var unitInputs = new ArrayList<JaxRsRouteMapper.UnitInput>();
        FrontendRequest svcReq = null;
        for (var outcome : sourceIngestion.outcomes()) {
            if (outcome.sourceSet().kind() != SourcePlanModel.Kind.MAIN) {
                units.add(new UniversalIngestionPipeline.Unit(outcome.sourceSet(), UniversalIngestionPipeline.Status.NOT_ANALYZED,
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
                continue;
            }
            var req = outcome.request().orElseThrow();
            boolean isBase = req.module().equals(modBase.identity());
            var decls = new ArrayList<DeclarationRecord>();
            var shapes = new ArrayList<TypeDeclarationRecord>();
            var rels = new ArrayList<RelationshipOccurrence>();
            var anns = new ArrayList<AnnotationUseRecord>();
            var srcOutcomes = req.sources().stream()
                    .map(s -> new SourceOutcome(s.document().identity(), SourceOutcome.State.PROCESSED, List.of())).toList();

            if (isBase) {
                decls.add(decl(absType, "AbstractCrudResource"));
                shapes.add(new TypeDeclarationRecord(absType.identity(), TypeDeclarationRecord.Kind.CLASS, true, true));
                addMethod(decls, rels, absType, getOneMethod, "getOne");
                addMethod(decls, rels, absType, createMethod, "create");
                addMethod(decls, rels, absType, removeMethod, "remove");
                anns.add(ann(absDoc, absType.identity(), 8, 1, "@Produces(MediaType.APPLICATION_JSON)"));
                anns.add(ann(absDoc, getOneMethod.identity(), 10, 2, "@GET"));
                anns.add(ann(absDoc, getOneMethod.identity(), 10, 3, "@Path(\"/{id}\")"));
                anns.add(ann(absDoc, createMethod.identity(), 12, 4, "@POST"));
                anns.add(ann(absDoc, removeMethod.identity(), 14, 5, "@DELETE"));
                anns.add(ann(absDoc, removeMethod.identity(), 14, 6, "@Path(\"/{id}\")"));
            } else {
                svcReq = req;
                decls.add(decl(catType, "CatalogResource"));
                shapes.add(new TypeDeclarationRecord(catType.identity(), TypeDeclarationRecord.Kind.CLASS, false, true));
                addMethod(decls, rels, catType, replaceMethod, "replace");
                // Simulates unresolved superclass ref in --build=off recovery while explicit import exists
                rels.add(RelationshipOccurrence.create(
                        SemanticRelationship.create(catType.identity(), new RelationshipKind("java.extends"),
                                new RelationshipTarget.Unresolved("AbstractCrudResource")),
                        catType.declaration().orElseThrow(), 0, SemanticStatus.UNRESOLVED, DIRECT,
                        List.of(new Uncertainty("java.unresolved", "Unresolved superclass in recovery test",
                                List.of("AbstractCrudResource"))),
                        List.of()));
                anns.add(ann(catDoc, catType.identity(), 7, 1, "@ApplicationPath(\"/api\")"));
                anns.add(ann(catDoc, catType.identity(), 8, 2, "@Path(\"/catalog\")"));
                anns.add(ann(catDoc, replaceMethod.identity(), 10, 3, "@PUT"));
                anns.add(ann(catDoc, replaceMethod.identity(), 10, 4, "@Path(\"/{id}\")"));
                anns.add(ann(catDoc, replaceMethod.identity(), 11, 5, "@Produces(\"application/json\")"));

                decls.add(decl(srvType, "CartServlet"));
                shapes.add(new TypeDeclarationRecord(srvType.identity(), TypeDeclarationRecord.Kind.CLASS, false, true));
                addMethod(decls, rels, srvType, doGetMethod, "doGet");
                addMethod(decls, rels, srvType, doPostMethod, "doPost");
                anns.add(ann(srvDoc, srvType.identity(), 3, 6, "@WebServlet(urlPatterns = {\"/cart\", \"/checkout\"})"));

                decls.add(decl(fakeType, "FakeResource"));
                shapes.add(new TypeDeclarationRecord(fakeType.identity(), TypeDeclarationRecord.Kind.CLASS, false, true));
                addMethod(decls, rels, fakeType, fakeGet, "doGet");
                anns.add(ann(fakeDoc, fakeType.identity(), 4, 7, "@Path(\"/fake\")"));
                anns.add(ann(fakeDoc, fakeType.identity(), 5, 8, "@WebServlet(\"/fake-servlet\")"));
            }

            var observations = rels.stream()
                    .map(occ -> new ObservationRecord(occ.span().document(), occ.relationship().kind(),
                            Optional.of(occ.span()), occ.status(),
                            ObservationRecord.EvidenceState.VERIFIED, ObservationRecord.EvidenceState.VERIFIED,
                            Optional.of(occ.identity()), occ.relationship().kind().value(), List.of()))
                    .toList();
            var unitCoverage = FrontendRequest.CATEGORIES.stream().map(c -> {
                String k = "java." + c;
                long total = rels.stream().filter(r -> r.relationship().kind().value().equals(k)).count();
                return new CategoryCoverage(new RelationshipKind(k), CategoryCoverage.Support.IMPLEMENTED,
                        total, total, 0);
            }).toList();
            var fe = new FrontendResult(req.manifest().identity(), PROVIDER, FrontendResult.State.COMPLETED,
                    decls, rels, observations, srcOutcomes, unitCoverage, List.of(), List.of(), anns, List.of(), shapes);
            units.add(new UniversalIngestionPipeline.Unit(outcome.sourceSet(), UniversalIngestionPipeline.Status.ANALYZED,
                    Optional.of(fe), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
            unitInputs.add(new JaxRsRouteMapper.UnitInput(req, fe));
        }

        // Direct unit assertions on JaxRsRouteMapper.mapAll
        var mapped = JaxRsRouteMapper.mapAll(unitInputs, 100);
        // Abstract base module must emit ZERO standalone endpoints
        assertTrue(mapped.get(unitInputs.getFirst().request().manifest().identity()).routes().isEmpty());

        var svcRoutes = mapped.get(Objects.requireNonNull(svcReq).manifest().identity()).routes();
        // 4 JAX-RS (PUT own + GET/{id}, POST, DELETE/{id} inherited) + 2 Servlet (/cart, /checkout), ZERO fake routes!
        assertEquals(6, svcRoutes.size(), () -> "Unexpected routes: " + svcRoutes);
        assertFalse(svcRoutes.stream().anyMatch(r -> r.path().contains("fake")),
                "Unimported annotations must not fabricate routes");
        assertTrue(svcRoutes.stream().anyMatch(r -> r.path().equals("/api/catalog/{id}")
                && r.methods().equals(List.of("GET"))
                && r.inheritedFrom().filter(absType.identity()::equals).isPresent()
                && r.produces().equals(List.of("application/json"))), () -> "Missing inherited GET route: " + svcRoutes);
        assertTrue(svcRoutes.stream().anyMatch(r -> r.path().equals("/api/catalog")
                && r.methods().equals(List.of("POST"))
                && r.inheritedFrom().filter(absType.identity()::equals).isPresent()));
        assertTrue(svcRoutes.stream().anyMatch(r -> r.path().equals("/api/catalog/{id}")
                && r.methods().equals(List.of("PUT"))
                && r.inheritedFrom().isEmpty()));
        assertTrue(svcRoutes.stream().anyMatch(r -> r.kind() == JaxRsRouteMapper.Kind.SERVLET
                && r.path().equals("/cart")
                && r.methods().equals(List.of("GET", "POST"))));
        assertTrue(svcRoutes.stream().anyMatch(r -> r.kind() == JaxRsRouteMapper.Kind.SERVLET
                && r.path().equals("/checkout")));

        // CanonicalGraphBuilder + HttpServiceCallProjection + ServiceArchitectureProjection end-to-end join (no dangling refs!)
        var pipelineRes = new UniversalIngestionPipeline.Result(
                ContentDigest.sha256Utf8("pipe-jaxrs"), sourceIngestion, units, List.of(), List.of());
        var graph = new CanonicalGraphBuilder().build(
                new CanonicalGraphInput(rawInputs, Optional.of(build), Optional.of(pipelineRes),
                        List.of(), List.of(), List.of()));
        var epNodes = graph.nodes().stream().filter(n -> n.kind().equals("ENDPOINT")).toList();
        assertEquals(6, epNodes.size());
        var memStore = GraphProjectionStore.memory();
        var nodesMap = memStore.records("nodes", GraphSnapshotCodec::node);
        graph.nodes().forEach(n -> nodesMap.put(n.context() + "|" + n.identity(), n));
        var edgesMap = memStore.records("edges", GraphSnapshotCodec::edge);
        graph.edges().forEach(e -> edgesMap.put(e.context() + "|" + e.identity(), e));
        var evMap = memStore.records("evidence", GraphSnapshotCodec::evidence);
        graph.evidence().forEach(e -> evMap.put(e.identity().value(), e));

        // Simulate a JAX-RS WebTarget HTTP call to "/api/catalog/{id}"
        var callProof = GraphEvidence.capture("java.http-client-call",
                Map.of("call", Map.of("uri", "http://catalog/api/catalog/{id}", "methods", List.of("GET"))));
        evMap.put(callProof.identity().value(), callProof);
        String callNodeId = KnowledgeGraph.derivedIdentity("call-jaxrs", 1);
        nodesMap.put(graph.snapshot().value() + "|" + callNodeId,
                new KnowledgeGraph.Node(callNodeId, graph.snapshot().value(), "SOURCE_HTTP_CALL_CANDIDATE",
                        List.of(callProof.identity())));

        HttpServiceCallProjection.project(memStore, graph.snapshot().value(), () -> false);
        ServiceArchitectureProjection.project(memStore, graph.snapshot().value(), () -> false);
        assertTrue(edgesMap.values().stream().anyMatch(e -> e.kind().equals("CALLS_SERVICE")),
                "Expected JAX-RS endpoint join to produce CALLS_SERVICE edge");
    }

    private static DeclarationRecord decl(Entity e, String spelling) {
        return new DeclarationRecord(e, spelling, SemanticStatus.RESOLVED, DIRECT, List.of(), List.of());
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

    private static void addMethod(List<DeclarationRecord> decls, List<RelationshipOccurrence> rels,
            Entity owner, Entity method, String spelling) {
        decls.add(decl(method, spelling));
        rels.add(RelationshipOccurrence.create(
                SemanticRelationship.create(owner.identity(), new RelationshipKind("java.declares"),
                        new RelationshipTarget.Resolved(method.identity())),
                method.declaration().orElseThrow(), 0, SemanticStatus.RESOLVED, DIRECT, List.of(), List.of()));
    }

    private static AnnotationUseRecord ann(SourceDocument doc, EntityIdentity owner, int line, int idx, String spelling) {
        return new AnnotationUseRecord(
                "java:v1:[\"annotation-use\",\"acme.JaxRs\"," + idx + "]",
                owner,
                new SourceSpan(doc.identity(), line, 1, line, Math.max(2, spelling.length())),
                "declaration-syntax",
                spelling);
    }
}
