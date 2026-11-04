package com.evolution.backend.analysis;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.filesystem.*;
import com.evolution.analysis.ingestion.UniversalArchitecturePipeline;
import com.evolution.analysis.javaparser.JavaParserFrontend;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Normal scan entry into the authenticated native Java lane. A selected source tree cannot
 * register its own authority. Reuse requires the exact source snapshot and readiness plan;
 * generated outputs remain tied to the selected captured context, including sibling gaps. */
public final class CapturedJavaScan {
    public record Expected(RepositoryIdentity repository,SnapshotIdentity snapshot,ContentDigest readiness) {
        public Expected {Objects.requireNonNull(repository);Objects.requireNonNull(snapshot);Objects.requireNonNull(readiness);}
    }
    public record Result(AnalysisJobs.Outcome outcome,ContentDigest capture,String qualification) {}

    public Result run(Path operatorRoot,Expected expected,Path scratch,BooleanSupplier cancelled)throws Exception {
        Objects.requireNonNull(expected);Objects.requireNonNull(cancelled);
        if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException("Scan cancelled");
        var root=LocalAnalysisRunner.safe(operatorRoot).toRealPath();
        var registration=CapturedJavaReader.registration(root);
        // Public fixture signers are useful test evidence, never production scan authority.
        if(!"REGISTERED_VALIDATION".equals(registration.qualification()))throw new IOException("SCAN_CAPTURE_REGISTERED_VALIDATION_REQUIRED");
        var pin=CanonicalJson.sha256(registration);
        var content=new StreamingContentStore(LocalAnalysisRunner.safe(Path.of(registration.contentDirectory())));
        var work=LocalAnalysisRunner.safe(scratch).resolve(registration.capture().digest().value().substring(7));
        var loaded=new CapturedJavaReader(content,cancelled).load(registration,work.resolve("views"));
        var prepared=loaded.prepared();
        if(!prepared.base().snapshot().repository().equals(expected.repository())
                ||!prepared.base().snapshot().identity().equals(expected.snapshot())
                ||!prepared.readiness().identity().equals(expected.readiness()))throw new IOException("SCAN_CAPTURE_INPUT_SCOPE_MISMATCH");
        var policy=UniversalArchitecturePipeline.Policy.builder(new JavaParserFrontend(),JavaParserFrontend.PROVIDER)
                .truthEvaluationMode(UniversalArchitecturePipeline.TruthEvaluationMode.AUTO).build().withReadiness(expected.readiness());
        java.util.function.Supplier<ContentDigest> current=()->{
            try{return pin.equals(CanonicalJson.sha256(CapturedJavaReader.registration(root)))?registration.policy():ContentDigest.sha256Utf8("scan registration changed");}
            catch(IOException unavailable){return ContentDigest.sha256Utf8("scan registration unavailable");}
        };
        var publication=new PreparedAnalysisPublication(new JavaEvidencePublicationStore(work.resolve("publications"),content),content,registration.capture().digest());
        var runner=new PreparedJavaAnalysisRunner(prepared,policy,Optional.of(loaded.semantic()),Optional.empty(),current,loaded.readinessProofs()).withPublication(publication);
        var outcome=runner.runCaptured(cancelled,true);
        return new Result(outcome,registration.capture().digest(),registration.qualification());
    }
}
