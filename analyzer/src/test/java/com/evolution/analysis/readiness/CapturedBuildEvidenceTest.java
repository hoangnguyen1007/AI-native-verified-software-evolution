package com.evolution.analysis.readiness;

import com.evolution.analysis.contract.common.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CapturedBuildEvidenceTest {
    @Test void noCompilePhaseDoesNotMakeSemanticValidationNotApplicable(){
        var inventory=new com.evolution.analysis.acquisition.streaming.InventoryManifest(com.evolution.analysis.acquisition.streaming.InventoryManifest.SCHEMA,
                com.evolution.analysis.contract.identity.RepositoryIdentity.fromCanonicalCoordinate("urn:test:execution-reconcile"),digest("selection"),digest("inventory"),0,0,0,0,Optional.empty(),Optional.empty());
        var context=new ReadinessPlan.Context("ctx","module","root","script","baseline",List.of(),Map.of(),List.of(),digest("inputs"),false);
        var selection=new BuildContextSelection(BuildContextSelection.SCHEMA,Map.of(),List.of("ctx"),List.of("ctx"),List.of(),List.of());
        var build=ReadinessPlanner.requirement(ReadinessPlan.Kind.BUILD_VALIDATION,"ctx","BUILD",List.of(),List.of());
        var semantic=ReadinessPlanner.requirement(ReadinessPlan.Kind.SEMANTIC_VALIDATION,"ctx","SEMANTIC",List.of(),List.of());
        var plan=new ReadinessPlan(ReadinessPlan.SCHEMA,inventory,selection,List.of(),List.of(),List.of(context),List.of(build,semantic),List.of(),new ReadinessPlan.OwnershipSummary(0,Map.of(),new com.evolution.analysis.acquisition.streaming.ContentHandle(digest("ledger"),0)));
        var manifest=new CapturedBuildEvidence.Manifest(context.identity(),selection.identity(),context.inputClosure(),new VersionedIdentifier("native.capture","1"),digest("tool"),List.of(),List.of(),List.of(),List.of());
        var capture=CapturedBuildEvidence.validate(manifest,observation(manifest,CapturedBuildEvidence.Trust.VALIDATED_OBSERVATION,0,false,true,Optional.of(digest("proof"))),value->true).orElseThrow();
        var reconciled=CapturedBuildEvidence.reconcile(plan,List.of(capture,capture));
        assertEquals(ReadinessPlan.State.SATISFIED,reconciled.requirements().stream().filter(r->r.kind()==ReadinessPlan.Kind.SEMANTIC_VALIDATION).findFirst().orElseThrow().state());
        assertEquals(ReadinessPlan.State.NOT_APPLICABLE,reconciled.requirements().stream().filter(r->r.kind()==ReadinessPlan.Kind.BUILD_VALIDATION).findFirst().orElseThrow().state());
    }
    @Test void receiptOrSuccessfulProducerAssertionCannotPromoteFacts(){
        var expected=manifest("tool",List.of(digest("a"),digest("b")),"variant");
        for(var trust:CapturedBuildEvidence.Trust.values()){
            var observation=observation(expected,trust,0,true,true,Optional.empty());
            assertEquals(trust==CapturedBuildEvidence.Trust.VALIDATED_OBSERVATION,CapturedBuildEvidence.validate(expected,observation,value->true).isPresent());
            assertTrue(CapturedBuildEvidence.validate(expected,observation,value->false).isEmpty());
        }
    }
    @Test void exactOrderVersionVariantToolchainAndFailureAreMandatory(){
        var expected=manifest("tool",List.of(digest("a"),digest("b")),"variant");
        for(var wrong:List.of(manifest("wrong-tool",expected.orderedClasspath(),"variant"),manifest("tool",List.of(digest("b"),digest("a")),"variant"),manifest("tool",expected.orderedClasspath(),"other-variant"),new CapturedBuildEvidence.Manifest(expected.context(),expected.selection(),expected.inputClosure(),new VersionedIdentifier("native.capture","2"),expected.toolchain(),expected.orderedClasspath(),List.of(),List.of("--release","21"),List.of()))){
            assertTrue(CapturedBuildEvidence.validate(expected,observation(wrong,CapturedBuildEvidence.Trust.VALIDATED_OBSERVATION,0,true,true,Optional.empty()),value->true).isEmpty());
        }
        assertTrue(CapturedBuildEvidence.validate(expected,observation(expected,CapturedBuildEvidence.Trust.VALIDATED_OBSERVATION,1,true,true,Optional.empty()),value->true).isEmpty());
        assertTrue(CapturedBuildEvidence.validate(expected,observation(expected,CapturedBuildEvidence.Trust.VALIDATED_OBSERVATION,0,true,false,Optional.empty()),value->true).isEmpty());
    }
    @Test void noCompilePhaseRequiresProofAndNativeSemanticValidation(){
        var expected=manifest("tool",List.of(),"script");
        assertTrue(CapturedBuildEvidence.validate(expected,observation(expected,CapturedBuildEvidence.Trust.VALIDATED_OBSERVATION,0,false,true,Optional.empty()),value->true).isEmpty());
        assertTrue(CapturedBuildEvidence.validate(expected,observation(expected,CapturedBuildEvidence.Trust.VALIDATED_OBSERVATION,0,false,true,Optional.of(digest("no-compile-phase"))),value->true).isPresent());
    }
    private static CapturedBuildEvidence.Manifest manifest(String tool,List<ContentDigest> classpath,String variant){return new CapturedBuildEvidence.Manifest(digest(variant),digest("selection"),digest("inputs"),new VersionedIdentifier("native.capture","1"),digest(tool),classpath,List.of(),List.of("--release","21"),List.of());}
    private static CapturedBuildEvidence.Observation observation(CapturedBuildEvidence.Manifest manifest,CapturedBuildEvidence.Trust trust,int exit,boolean compile,boolean semantic,Optional<ContentDigest> proof){return new CapturedBuildEvidence.Observation(manifest,trust,exit,compile,semantic,proof,List.of(digest("outputs")),digest("protected-receipt"));}
    private static ContentDigest digest(String value){return ContentDigest.sha256Utf8(value);}
}
