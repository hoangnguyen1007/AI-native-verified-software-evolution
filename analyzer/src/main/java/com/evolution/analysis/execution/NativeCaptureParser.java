package com.evolution.analysis.execution;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.readiness.CapturedBuildEvidence;
import java.io.*;
import java.util.*;

/** Decodes native capture data; a producer's trust label is never a promotion decision. */
public final class NativeCaptureParser {
    public record Envelope(String schema,CapturedBuildEvidence.Observation observation) {
        public Envelope {if(!"native-build-capture:m8.4-v1".equals(schema))throw new IllegalArgumentException("UNSUPPORTED_NATIVE_CAPTURE");Objects.requireNonNull(observation);}
    }
    public record Captured(Envelope declared,ContentHandle raw) {
        public Captured {Objects.requireNonNull(declared);Objects.requireNonNull(raw);}
        public EvidenceVerifier.Trust trust(){return EvidenceVerifier.Trust.PRODUCER_ASSERTION;}
        public com.evolution.analysis.contract.common.ContentDigest observationIdentity(){return CanonicalJson.sha256(declared.observation());}
    }
    public static Captured read(ContentHandle handle,RepositoryContentStore content,GraphJson.Limits limits)throws IOException {
        Objects.requireNonNull(limits);
        // UTF-8 uses at most three bytes per UTF-16 reader character, plus a possible BOM.
        if(handle.size()>3L*limits.maxCharacters()+3)throw new IOException("NATIVE_CAPTURE_WORKER_REQUIRED");
        if(!content.verify(handle))throw new IOException("NATIVE_CAPTURE_DIGEST_MISMATCH");
        try(var input=content.open(handle)){return new Captured(ExecutionCodec.read(input,Envelope.class,limits),handle);}
        catch(IllegalArgumentException|ArithmeticException invalid){throw new IOException("NATIVE_CAPTURE_INVALID",invalid);}
    }
    private NativeCaptureParser(){}
}
