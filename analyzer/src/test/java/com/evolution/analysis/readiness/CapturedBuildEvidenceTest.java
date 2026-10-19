package com.evolution.analysis.readiness;

import com.evolution.analysis.contract.common.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CapturedBuildEvidenceTest {
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
