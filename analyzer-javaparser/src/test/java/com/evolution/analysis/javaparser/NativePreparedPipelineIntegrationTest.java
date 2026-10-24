package com.evolution.analysis.javaparser;

import java.nio.file.Path;
import java.util.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.query.*;
import static com.evolution.analysis.query.ArchitectureQuery.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Requires the explicitly selected immutable native CAS; never executes a target. */
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="native.fixture.root",matches=".+")
class NativePreparedPipelineIntegrationTest {
    @Test void nativeConsumerGraphHasThePreparedViewAndPostAnalysisClosure()throws Exception {
        var fixture=new NativePipelineWiringFixture(Path.of(System.getProperty("native.fixture.root")));
        var result=fixture.execute("protobuf-client");var graph=result.graph();
        assertTrue(graph.evidence().stream().anyMatch(e->e.family().equals("java.evidence.view")),"Native inputs must pass through the prepared evidence lane");
        assertTrue(graph.evidence().stream().anyMatch(e->e.family().equals("java.evidence.post-projection")),"Native semantic claims must reach post-analysis reconciliation");
        assertClosed(result);
        var caller=result.request().sources().stream().filter(s->s.document().path().endsWith("ProtoCaller.java")).findFirst().orElseThrow();
        var expected=com.evolution.analysis.frontend.JavaSymbolName.method(com.evolution.analysis.frontend.JavaSymbolName.topLevelType("fixture.proto","Request"),"newBuilder",List.of());
        var call=result.frontend().occurrences().stream().filter(o->o.relationship().kind().value().equals("java.calls")&&o.span().equals(new com.evolution.analysis.contract.source.SourceSpan(caller.document().identity(),4,16,4,50))).findFirst().orElseThrow();
        var target=((RelationshipTarget.Resolved)call.relationship().target()).target();var declaration=result.frontend().declarations().stream().filter(d->d.entity().identity().equals(target)).findFirst().orElseThrow();
        assertEquals(expected.canonicalName(),declaration.entity().canonicalName());assertEquals(EntityOrigin.PROJECT,declaration.entity().origin());assertEquals(DerivationKind.DERIVED,declaration.derivation().kind());
    }
    private static void assertClosed(NativePipelineWiringFixture.Result result){
        assertTrue(result.prepared().activeGaps().isEmpty(),()->result.prepared().activeGaps().stream().map(g->g.reasonCode()).toList().toString());
        for(String key:List.of("pendingCallers","pendingTargets","pendingOrigins","errorOrNonresolved"))assertEquals(0,((Number)result.mappings().get(key)).longValue(),key);
        var projection=result.graph().evidence().stream().filter(e->e.family().equals("java.evidence.post-projection")).findFirst().orElseThrow();
        var fields=GraphJson.object(GraphJson.read(projection.canonicalJson(),GraphJson.Limits.defaults()));assertEquals("CLOSED",fields.get("denominator"));assertEquals(0,((Number)GraphJson.object(fields.get("counts")).get("active")).longValue());
        var nativeProjection=result.graph().evidence().stream().filter(e->e.family().equals("java.native.closure-projection")).findFirst().orElseThrow();
        var nativeFields=GraphJson.object(GraphJson.read(nativeProjection.canonicalJson(),GraphJson.Limits.defaults()));assertEquals("CLOSED",nativeFields.get("denominator"));assertEquals(0,((Number)GraphJson.object(nativeFields.get("counts")).get("active")).longValue());
        var dispositions=result.graph().evidence().stream().filter(e->e.family().equals("java.native.gap-disposition")).toList();assertFalse(dispositions.isEmpty());
        assertTrue(dispositions.stream().allMatch(e->Boolean.FALSE.equals(GraphJson.object(GraphJson.read(e.canonicalJson(),GraphJson.Limits.defaults())).get("active"))));
    }
    @Test void nativeLombokAndMapStructConsumersRetainExactProjectOwnersAndQueryEvidence()throws Exception {
        var fixture=new NativePipelineWiringFixture(Path.of(System.getProperty("native.fixture.root")));
        for(var label:List.of(List.of("lombok-client","Person","constructor","4"),List.of("mapstruct-client","PersonMapperImpl","constructor","3"),List.of("custom-client","Generated","method","3"))){
            var result=fixture.execute(label.getFirst());assertClosed(result);
            var matched=result.frontend().occurrences().stream().filter(o->o.span().startLine()==Integer.parseInt(label.get(3))&&o.status()==SemanticStatus.RESOLVED&&o.relationship().target() instanceof RelationshipTarget.Resolved).map(o->(RelationshipTarget.Resolved)o.relationship().target()).map(t->result.frontend().declarations().stream().filter(d->d.entity().identity().equals(t.target())).findFirst().orElseThrow()).filter(d->d.entity().canonicalName().contains("\""+label.get(1)+"\"")&&d.entity().canonicalName().contains("\""+label.get(2)+"\"")).toList();
            assertFalse(matched.isEmpty(),label.toString());assertTrue(matched.stream().allMatch(d->d.entity().origin()==EntityOrigin.PROJECT));
            var graph=result.graph();var context=graph.nodes().stream().filter(n->n.kind().equals("TYPE")).findFirst().orElseThrow().context();var query=new ArchitectureQueryService(graph);var request=ArchitectureQueryPipelineIntegrationTest.request(graph,context,Scope.ENTITY,Projection.BOUNDED_UNION,Set.of("java.calls","java.constructor-calls"));
            assertFalse(query.relationships(request,Page.first()).items().isEmpty());
        }
    }
    @Test void nativeProjectImportsCarryDerivedEvidenceFromTheValidatedBinarySlot()throws Exception {
        var result=new NativePipelineWiringFixture(Path.of(System.getProperty("native.fixture.root"))).execute("lombok-client");
        var imported=result.frontend().declarations().stream().filter(d->d.entity().origin()==EntityOrigin.PROJECT&&d.entity().declaration().isEmpty()&&d.entity().kind()!=EntityKind.PACKAGE).toList();
        assertFalse(imported.isEmpty());assertTrue(imported.stream().allMatch(d->d.derivation().kind()==DerivationKind.DERIVED),"Native PROJECT members must retain their validated transformation evidence");
    }
    @Test void generatedMapStructAndCustomResourcesUseImmutablePreparedOverlays()throws Exception {
        var fixture=new NativePipelineWiringFixture(Path.of(System.getProperty("native.fixture.root")));
        for(String name:List.of("mapstruct-mapper","custom-producer")){
            var result=fixture.execute(name);assertClosed(result);
            assertNotEquals(result.prepared().base().snapshot().identity(),result.prepared().inputs().snapshot().identity());
            assertTrue(result.prepared().inputs().snapshot().documents().stream().anyMatch(d->d.classification()==com.evolution.analysis.contract.source.SourceClassification.GENERATED_MAIN));
            assertTrue(result.graph().evidence().stream().anyMatch(e->e.family().equals("java.evidence.source-map")));
            var generated=result.prepared().inputs().snapshot().documents().stream().filter(d->d.classification()==com.evolution.analysis.contract.source.SourceClassification.GENERATED_MAIN).map(d->d.identity()).collect(java.util.stream.Collectors.toSet());
            assertTrue(result.frontend().declarations().stream().filter(d->d.entity().declaration().filter(s->generated.contains(s.document())).isPresent()).allMatch(d->d.derivation().kind()==DerivationKind.DERIVED));
        }
    }
    @Test void realBrokenSiblingRetainsDiagnosticsAndBlockedDependentWithoutErasingHealthyNativeOutputs()throws Exception {
        var fixture=new NativePipelineWiringFixture(Path.of(System.getProperty("native.fixture.root")));var broken=fixture.broken();
        assertEquals("ERROR",broken.nativeContext().get("state"));assertEquals("custom-broken",broken.blocked().get("prerequisite"));
        assertTrue(broken.graph().evidence().stream().anyMatch(e->e.family().equals("java.native.failure-context")&&e.canonicalJson().contains("MissingType")));
        assertClosed(fixture.execute("custom-client"));
    }
    @Test void changedSourceCannotBorrowTheActualNativeCompilerIndex()throws Exception {
        var fixture=new NativePipelineWiringFixture(Path.of(System.getProperty("native.fixture.root")));
        assertThrows(java.io.IOException.class,()->fixture.readWithChangedSource("lombok-client"));
    }
    @Test void actualProtobufGeneratedSourcesEnterTheOverlayBeforeTheFrontend()throws Exception {
        var result=new NativePipelineWiringFixture(Path.of(System.getProperty("native.fixture.root"))).execute("protobuf-schema");assertClosed(result);
        assertEquals(6,result.prepared().inputs().snapshot().documents().stream().filter(d->d.classification()==com.evolution.analysis.contract.source.SourceClassification.GENERATED_MAIN).count());
        assertTrue(result.prepared().base().snapshot().files().stream().anyMatch(f->f.path().endsWith("echo.proto")));
        assertEquals(2109,((Number)result.mappings().get("compared")).longValue());
    }
    @Test void nativeLombokOutputAugmentsItsSourceOwnerWithoutChangingTheCompilerClasspath()throws Exception {
        var result=new NativePipelineWiringFixture(Path.of(System.getProperty("native.fixture.root"))).execute("lombok-model");
        assertTrue(result.request().correspondence().isPresent(),"The actual Lombok output must augment its source owner");
        assertEquals(1,result.request().dependencies().size(),"The captured compiler has only lombok.jar on its classpath");
        assertEquals(1,result.request().correspondence().orElseThrow().binaryViews().size());assertClosed(result);
        assertTrue(result.frontend().diagnostics().stream().noneMatch(d->d.code().equals("lombok.artifact-unverified")));
        assertTrue(result.frontend().declarations().stream().anyMatch(d->d.entity().kind()==EntityKind.CONSTRUCTOR&&d.entity().declaration().isEmpty()&&d.derivation().kind()==DerivationKind.DERIVED&&d.entity().canonicalName().contains("Person")));
        assertTrue(result.frontend().declarations().stream().noneMatch(d->d.entity().canonicalName().equals(com.evolution.analysis.frontend.JavaSymbolName.constructor(com.evolution.analysis.frontend.JavaSymbolName.topLevelType("fixture","Person"),List.of()).canonicalName())),"The native constructor census excludes a fabricated Person() constructor");
    }
    @Test void nativeLombokVersionAndConfigurationCannotBorrowTheOriginalCapture()throws Exception {
        var fixture=new NativePipelineWiringFixture(Path.of(System.getProperty("native.fixture.root")));fixture.requireLombokVersion("1.18.36");
        assertThrows(java.io.IOException.class,()->fixture.requireLombokVersion("1.18.46"));assertThrows(java.io.IOException.class,fixture::readWithChangedLombokConfiguration);
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="native.failure.root",matches=".+")
    void actualProcessorFailureRetainsTheGeneratedFileAndWithholdsWholeGenerationClosure()throws Exception {
        var partial=new NativePartialProcessorFixture(Path.of(System.getProperty("native.failure.root"))).verify();
        assertEquals("PARTIAL",partial.overlay().text("closure"));assertEquals("SEALED",partial.overlay().text("census"));
        var counts=partial.overlay().object("counts");assertEquals(2,((Number)counts.get("total")).longValue());assertEquals(1,((Number)counts.get("retained")).longValue());assertEquals(1,((Number)counts.get("unavailable")).longValue());assertEquals(0,((Number)counts.get("accepted")).longValue());assertFalse(partial.gaps().isEmpty());
    }
}
