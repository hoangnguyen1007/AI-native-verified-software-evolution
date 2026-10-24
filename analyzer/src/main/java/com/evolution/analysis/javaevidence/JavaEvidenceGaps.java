package com.evolution.analysis.javaevidence;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.evidence.*;
import java.util.*;

/** Exhaustive Step-4 reason mapping; original M3 gap identity/schema is retained. */
public final class JavaEvidenceGaps {
    public static final VersionedIdentifier PROVIDER=new VersionedIdentifier("source.java-build-closure","m8.4-step4-v1");
    public static final VersionedIdentifier CATALOG=new VersionedIdentifier("evidence.java-build-closure","m8.4-step4-v1");
    public enum Reason {
        PUBLICATION_UNAVAILABLE,PUBLICATION_SCOPE_MISMATCH,CONTENT_HASH_MISMATCH,OUTPUT_CENSUS_OPEN,
        LINEAGE_SCOPE_UNPROVEN,GENERATOR_INPUT_MISSING,GENERATOR_TOOL_UNPINNED,GENERATOR_CLOSURE_OPEN,STALE_GENERATED_OUTPUT,
        OUTPUT_OWNER_UNPROVEN,OBSERVED_ROOT_UNVALIDATED,OUTPUT_COLLISION,SOURCE_SET_ROLE_UNKNOWN,
        BINARY_FORMAT_INVALID,ARCHIVE_MEMBER_AMBIGUOUS,BINARY_VERSION_UNSUPPORTED,UNSAFE_OUTPUT_LOCATOR,
        CLASSPATH_ORDER_UNPROVEN,PLATFORM_VIEW_MISSING,JPMS_CONTEXT_UNPROVEN,DUPLICATE_CLASS_CONFLICT,SEARCH_SPACE_OPEN,
        SOURCE_BINARY_CONFLICT,CORRESPONDENCE_UNPROVEN,SEMANTIC_MAPPING_UNPROVEN,SOURCE_PROVENANCE_MISSING,
        VALIDATION_METHOD_UNAVAILABLE,VALIDATION_REJECTED,EVIDENCE_REVOKED,PREREQUISITE_REOPENED,
        WORKER_CAPACITY_REQUIRED,STORAGE_UNAVAILABLE,CANCELLED_WITH_FRONTIER,GENERATOR_NONREPRODUCIBLE
    }
    public static EvidenceRequirement.Kind requirement(Reason reason){return switch(reason){
        case PUBLICATION_UNAVAILABLE,PUBLICATION_SCOPE_MISMATCH,CONTENT_HASH_MISMATCH,OUTPUT_CENSUS_OPEN,UNSAFE_OUTPUT_LOCATOR,STORAGE_UNAVAILABLE,CANCELLED_WITH_FRONTIER->EvidenceRequirement.Kind.REPOSITORY_CONTENT;
        case LINEAGE_SCOPE_UNPROVEN,GENERATOR_INPUT_MISSING,GENERATOR_TOOL_UNPINNED,GENERATOR_CLOSURE_OPEN,STALE_GENERATED_OUTPUT,GENERATOR_NONREPRODUCIBLE->EvidenceRequirement.Kind.GENERATED_SOURCE;
        case OUTPUT_OWNER_UNPROVEN,OBSERVED_ROOT_UNVALIDATED,OUTPUT_COLLISION,SOURCE_SET_ROLE_UNKNOWN->EvidenceRequirement.Kind.SOURCE_OWNERSHIP;
        case BINARY_FORMAT_INVALID,ARCHIVE_MEMBER_AMBIGUOUS,BINARY_VERSION_UNSUPPORTED->EvidenceRequirement.Kind.BYTECODE;
        case CLASSPATH_ORDER_UNPROVEN,JPMS_CONTEXT_UNPROVEN,DUPLICATE_CLASS_CONFLICT,SEARCH_SPACE_OPEN->EvidenceRequirement.Kind.EXACT_CLASSPATH;
        case PLATFORM_VIEW_MISSING->EvidenceRequirement.Kind.PLATFORM_SYMBOLS;
        case SOURCE_BINARY_CONFLICT,CORRESPONDENCE_UNPROVEN,SEMANTIC_MAPPING_UNPROVEN,SOURCE_PROVENANCE_MISSING->EvidenceRequirement.Kind.ALTERNATE_FRONTEND;
        case VALIDATION_METHOD_UNAVAILABLE,VALIDATION_REJECTED,EVIDENCE_REVOKED,PREREQUISITE_REOPENED,WORKER_CAPACITY_REQUIRED->EvidenceRequirement.Kind.ISOLATED_BUILD_OUTPUT;};}
    public static CapabilityGapRecord create(Map<String,Object> scope,Reason reason,ContentDigest observationContent,ContentDigest payload){
        Object snapshot=scope.get("baseSnapshot");if(!(snapshot instanceof String s))throw new IllegalArgumentException("JAVA_GAP_REQUIRES_M1_COMPATIBILITY_CONTEXT");
        var observation=ProviderObservationReference.create(PROVIDER,"java.build-closure",observationContent,payload);
        var subject=EvidenceSubject.observation(observation.identity());
        var requirement=new EvidenceRequirement(requirement(reason),"java.closure."+reason.name().toLowerCase(Locale.ROOT).replace('_','-'),List.of(subject),EvidenceRequirement.AuthorizationClass.PASSIVE,List.of("Supply matching scoped verified evidence for the recorded question."));
        return CapabilityGapRecord.create(CATALOG,EvidenceContext.forSnapshot(new SnapshotIdentity(s)),PROVIDER,"java.build-closure",reason.name(),subject,List.of(),List.of(observation),List.of(requirement),List.of(),List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE,"java.build-closure"),new AffectedOutput(AffectedOutput.Kind.FRONTEND_INPUT,"java.build-closure")),List.of(),List.of(),List.of("Byte acquisition, generated lineage and semantic correctness are separate outcomes."));
    }
    private JavaEvidenceGaps(){}
}
