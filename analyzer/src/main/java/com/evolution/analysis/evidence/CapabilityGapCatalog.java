package com.evolution.analysis.evidence;

import com.evolution.analysis.acquisition.CandidateSourceOwnership;
import com.evolution.analysis.acquisition.RepositoryAcquisitionResult;
import com.evolution.analysis.buildmodel.BuildModelResult;
import com.evolution.analysis.buildmodel.SourcePlanModel;
import com.evolution.analysis.classpath.ExactClasspathResult;
import com.evolution.analysis.dependency.DependencyAcquisitionResult;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.semantic.SemanticStatus;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.input.*;
import java.util.List;

/**
 * Versioned mapping from every currently registered degraded M2/M3 outcome to stable gap semantics.
 * Requirement fields are reason-only fallbacks. When an original provider observation carries a typed
 * requirement, {@link CapabilityGapNormalizer} preserves that more specific requirement instead.
 */
public final class CapabilityGapCatalog {
    public static final VersionedIdentifier CATALOG =
            new VersionedIdentifier("evidence.capability-gap-catalog", "m3.8-v2");
    private static final String SATISFACTION = "Evidence establishes or narrows the requested fact.";

    private CapabilityGapCatalog() {}

    public record Entry(String mechanismCategory, String reasonCode, EvidenceRequirement.Kind requirementKind,
            String question, EvidenceRequirement.AuthorizationClass authorizationClass,
            AffectedOutput affectedOutput) {
        public Entry {
            mechanismCategory = ContractChecks.namespacedId(mechanismCategory, "catalog mechanism category");
            reasonCode = ContractChecks.token(reasonCode, "catalog reason code");
            ContractChecks.notNull(requirementKind, "catalog requirement kind");
            question = ContractChecks.namespacedId(question, "catalog evidence question");
            ContractChecks.notNull(authorizationClass, "catalog authorization class");
            ContractChecks.notNull(affectedOutput, "catalog affected output");
        }

        public EvidenceRequirement requirement(List<EvidenceSubject> inputs) {
            return new EvidenceRequirement(requirementKind, question, inputs, authorizationClass,
                    List.of(SATISFACTION));
        }
    }

    public static Entry entryFor(RepositoryAcquisitionResult.Reason reason) {
        EvidenceRequirement.Kind kind = switch (reason) {
            case MISSING_ROOT_POM -> EvidenceRequirement.Kind.BUILD_MODEL;
            default -> EvidenceRequirement.Kind.REPOSITORY_CONTENT;
        };
        EvidenceRequirement.AuthorizationClass auth = switch (reason) {
            case ENTRY_COUNT_LIMIT, FILE_COUNT_LIMIT, DIRECTORY_COUNT_LIMIT, FILE_BYTE_LIMIT,
                    TOTAL_BYTE_LIMIT, DEPTH_LIMIT -> EvidenceRequirement.AuthorizationClass.PASSIVE;
            default -> EvidenceRequirement.AuthorizationClass.LOCAL_READ;
        };
        return entry("repository.acquisition", reason.name(), kind,
                kind == EvidenceRequirement.Kind.BUILD_MODEL ? "build.acquire-root-pom" : "repository.acquire-content",
                auth, AffectedOutput.Kind.FRONTEND_INPUT, "frontend.input");
    }

    public static Entry entryFor(BuildModelResult.Reason reason) {
        String category = switch (reason) {
            case MISSING_MODULE_POM, MISSING_PARENT_POM, MISSING_IMPORT_BOM, INVALID_XML, INVALID_MODEL,
                    UNSAFE_XML, COORDINATE_MISMATCH, MODEL_WARNING -> "build.pom";
            case PATH_OUTSIDE_WORKSPACE, UNRESOLVED_EXPRESSION, DUPLICATE_MODULE, MODULE_CYCLE,
                    DUPLICATE_COORDINATE, INPUT_LIMIT, MODEL_READ_LIMIT -> "build.model";
            case UNSUPPORTED_ACTIVATION -> "build.configuration";
            case EXTENSIONS_NOT_LOADED -> "build.plugin";
        };
        EvidenceRequirement.Kind kind = reason == BuildModelResult.Reason.UNSUPPORTED_ACTIVATION
                ? EvidenceRequirement.Kind.CONFIGURATION
                : reason == BuildModelResult.Reason.EXTENSIONS_NOT_LOADED
                        ? EvidenceRequirement.Kind.ISOLATED_BUILD_OUTPUT : EvidenceRequirement.Kind.BUILD_MODEL;
        EvidenceRequirement.AuthorizationClass auth = reason == BuildModelResult.Reason.EXTENSIONS_NOT_LOADED
                ? EvidenceRequirement.AuthorizationClass.ISOLATED_EXECUTION : EvidenceRequirement.AuthorizationClass.PASSIVE;
        return entry(category, reason.name(), kind, question(kind), auth,
                AffectedOutput.Kind.FRONTEND_INPUT, "frontend.input");
    }

    public static Entry entryFor(SourcePlanModel.Gap reason) {
        String category = switch (reason) {
            case MISSING_ENCODING, UNSUPPORTED_ENCODING -> "build.encoding";
            case GENERATED_SOURCES_NOT_ACQUIRED -> "build.generated-source";
            case PLUGIN_EFFECTS_NOT_EVALUATED, PACKAGING_LIFECYCLE_NOT_EVALUATED,
                    ADDITIONAL_COMPILER_EXECUTION -> "build.plugin";
            default -> "build.source-plan";
        };
        EvidenceRequirement.Kind kind = switch (reason) {
            case GENERATED_SOURCES_NOT_ACQUIRED -> EvidenceRequirement.Kind.GENERATED_SOURCE;
            case PLUGIN_EFFECTS_NOT_EVALUATED, PACKAGING_LIFECYCLE_NOT_EVALUATED,
                    ADDITIONAL_COMPILER_EXECUTION -> EvidenceRequirement.Kind.ISOLATED_BUILD_OUTPUT;
            case MISSING_ENCODING, UNSUPPORTED_ENCODING -> EvidenceRequirement.Kind.CONFIGURATION;
            default -> EvidenceRequirement.Kind.BUILD_MODEL;
        };
        EvidenceRequirement.AuthorizationClass auth = kind == EvidenceRequirement.Kind.ISOLATED_BUILD_OUTPUT
                ? EvidenceRequirement.AuthorizationClass.ISOLATED_EXECUTION : EvidenceRequirement.AuthorizationClass.PASSIVE;
        return entry(category, reason.name(), kind, question(kind), auth,
                AffectedOutput.Kind.FRONTEND_INPUT, "frontend.input");
    }

    public static Entry entryFor(CandidateSourceOwnership.Reason reason) {
        EvidenceRequirement.Kind kind = reason == CandidateSourceOwnership.Reason.MISSING_SOURCE_ROOT
                ? EvidenceRequirement.Kind.REPOSITORY_CONTENT : EvidenceRequirement.Kind.SOURCE_OWNERSHIP;
        return entry("workspace.source-ownership", reason.name(), kind, question(kind),
                EvidenceRequirement.AuthorizationClass.PASSIVE,
                AffectedOutput.Kind.FRONTEND_INPUT, "frontend.input");
    }

    public static Entry entryFor(ExactClasspathResult.Reason reason) {
        String category = switch (reason) {
            case CACHE_ROOT_NOT_FOUND, CACHE_ROOT_NOT_DIRECTORY, CACHE_ROOT_SYMBOLIC_LINK,
                    CACHE_ROOT_READ_FAILED, PATH_OUTSIDE_CACHE, SYMBOLIC_LINK, NON_REGULAR_ARTIFACT,
                    ARTIFACT_READ_FAILED, ARTIFACT_CHANGED_DURING_READ, COORDINATE_LIMIT,
                    FILE_COUNT_LIMIT, POM_BYTE_LIMIT, POM_MODEL_READ_LIMIT, ARTIFACT_BYTE_LIMIT,
                    TOTAL_BYTE_LIMIT, DEPENDENCY_DEPTH_LIMIT -> "build.artifact-acquisition";
            case REACTOR_OUTPUT_NOT_ACQUIRED, DUPLICATE_REACTOR_COORDINATE -> "build.reactor-output";
            case UNSUPPORTED_PROFILE_ACTIVATION -> "build.configuration";
            case MISSING_POM, INVALID_POM, UNSAFE_POM, POM_MODEL_FAILED, POM_MODEL_WARNING,
                    COORDINATE_MISMATCH, BUILD_MODEL_INCOMPLETE -> "build.pom";
            default -> "build.dependency";
        };
        EvidenceRequirement.Kind kind = switch (reason) {
            case REACTOR_OUTPUT_NOT_ACQUIRED, DUPLICATE_REACTOR_COORDINATE -> EvidenceRequirement.Kind.REACTOR_OUTPUT;
            case UNSUPPORTED_PROFILE_ACTIVATION -> EvidenceRequirement.Kind.CONFIGURATION;
            case MISSING_POM, INVALID_POM, UNSAFE_POM, POM_MODEL_FAILED, POM_MODEL_WARNING,
                    COORDINATE_MISMATCH, BUILD_MODEL_INCOMPLETE -> EvidenceRequirement.Kind.BUILD_MODEL;
            case CACHE_ROOT_NOT_FOUND, CACHE_ROOT_NOT_DIRECTORY, CACHE_ROOT_SYMBOLIC_LINK,
                    CACHE_ROOT_READ_FAILED, PATH_OUTSIDE_CACHE, SYMBOLIC_LINK, NON_REGULAR_ARTIFACT,
                    ARTIFACT_READ_FAILED, ARTIFACT_CHANGED_DURING_READ, COORDINATE_LIMIT,
                    FILE_COUNT_LIMIT, POM_BYTE_LIMIT, POM_MODEL_READ_LIMIT, ARTIFACT_BYTE_LIMIT,
                    TOTAL_BYTE_LIMIT, DEPENDENCY_DEPTH_LIMIT, NON_EXACT_VERSION -> EvidenceRequirement.Kind.EXACT_CLASSPATH;
            default -> EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT;
        };
        EvidenceRequirement.AuthorizationClass auth = switch (kind) {
            case DEPENDENCY_ARTIFACT, REACTOR_OUTPUT -> EvidenceRequirement.AuthorizationClass.LOCAL_READ;
            default -> EvidenceRequirement.AuthorizationClass.PASSIVE;
        };
        return entry(category, reason.name(), kind, question(kind), auth,
                AffectedOutput.Kind.FRONTEND_INPUT, "frontend.input");
    }

    public static Entry entryFor(DependencyAcquisitionResult.FailureReason reason) {
        String category = switch (reason) {
            case INVALID_POM -> "build.pom";
            case SNAPSHOT_UNSUPPORTED -> "build.dependency";
            default -> "build.artifact-acquisition";
        };
        EvidenceRequirement.AuthorizationClass authorization = switch (reason) {
            case CACHE_ROOT_NOT_FOUND, CACHE_ROOT_NOT_DIRECTORY, CACHE_ROOT_SYMBOLIC_LINK,
                    CACHE_ROOT_READ_FAILED, PATH_OUTSIDE_CACHE, SYMBOLIC_LINK, NON_REGULAR_ARTIFACT,
                    CACHE_READ_FAILED, CACHE_CHANGED_DURING_READ, CORRUPTED_CACHE ->
                    EvidenceRequirement.AuthorizationClass.LOCAL_READ;
            case CACHE_WRITE_FAILED -> EvidenceRequirement.AuthorizationClass.LOCAL_WRITE;
            case SNAPSHOT_UNSUPPORTED -> EvidenceRequirement.AuthorizationClass.PASSIVE;
            default -> EvidenceRequirement.AuthorizationClass.NETWORK;
        };
        return entry(category, reason.name(), EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT,
                "build.acquire-dependency-artifact", authorization,
                AffectedOutput.Kind.FRONTEND_INPUT, "frontend.input");
    }

    public static Entry entryFor(SourceDecodingResult.Reason reason) {
        EvidenceRequirement.Kind kind = switch (reason) {
            case UNOWNED_SOURCE, OVERLAPPING_OWNERSHIP -> EvidenceRequirement.Kind.SOURCE_OWNERSHIP;
            case MISSING_ACQUIRED_BYTES -> EvidenceRequirement.Kind.REPOSITORY_CONTENT;
            case MISSING_SOURCE_PLAN -> EvidenceRequirement.Kind.BUILD_MODEL;
            case MISSING_ENCODING, INVALID_ENCODING_DECLARATION, UNSUPPORTED_ENCODING,
                    BOM_ENCODING_MISMATCH, MALFORMED_BYTE_SEQUENCE -> EvidenceRequirement.Kind.CONFIGURATION;
        };
        return entry("build.encoding", reason.name(), kind, question(kind),
                kind == EvidenceRequirement.Kind.REPOSITORY_CONTENT
                        ? EvidenceRequirement.AuthorizationClass.LOCAL_READ : EvidenceRequirement.AuthorizationClass.PASSIVE,
                AffectedOutput.Kind.FRONTEND_INPUT, "frontend.input");
    }

    public static Entry entryFor(PlatformSymbolResult.Reason reason) {
        EvidenceRequirement.Kind kind = switch (reason) {
            case RELEASE_FILE_INVALID, RELEASE_MISMATCH -> EvidenceRequirement.Kind.CONFIGURATION;
            default -> EvidenceRequirement.Kind.PLATFORM_SYMBOLS;
        };
        return entry("build.platform", reason.name(), kind, question(kind),
                kind == EvidenceRequirement.Kind.PLATFORM_SYMBOLS
                        ? EvidenceRequirement.AuthorizationClass.LOCAL_READ : EvidenceRequirement.AuthorizationClass.PASSIVE,
                AffectedOutput.Kind.FRONTEND_INPUT, "frontend.input");
    }

    public static Entry entryFor(FrontendAssemblyResult.Reason reason) {
        EvidenceRequirement.Kind kind = switch (reason) {
            case MISSING_SOURCE_INPUT, INVALID_SOURCE_INPUT -> EvidenceRequirement.Kind.DECODED_SOURCE;
            case MISSING_SOURCE_PLAN, INVALID_SOURCE_PLAN, UNSUPPORTED_SYNTAX_LEVEL, PREVIEW_UNSUPPORTED -> EvidenceRequirement.Kind.BUILD_MODEL;
            case PLATFORM_UNAVAILABLE, PLATFORM_RELEASE_MISMATCH -> EvidenceRequirement.Kind.PLATFORM_SYMBOLS;
            case CLASSPATH_PROBLEM -> EvidenceRequirement.Kind.EXACT_CLASSPATH;
            case MISSING_DEPENDENCY_BINARY -> EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT;
            case MISSING_REACTOR_OUTPUT, REACTOR_OUTPUT_MISMATCH -> EvidenceRequirement.Kind.REACTOR_OUTPUT;
            case UNREFERENCED_BINARY_INPUT -> EvidenceRequirement.Kind.CONFIGURATION;
        };
        return entry("frontend.input", reason.name(), kind, question(kind),
                switch (kind) {
                    case DEPENDENCY_ARTIFACT, PLATFORM_SYMBOLS, REACTOR_OUTPUT -> EvidenceRequirement.AuthorizationClass.LOCAL_READ;
                    default -> EvidenceRequirement.AuthorizationClass.PASSIVE;
                }, AffectedOutput.Kind.ANALYSIS_COVERAGE, "analysis.coverage");
    }

    public static Entry entryFor(SemanticStatus status) {
        if (status == SemanticStatus.RESOLVED) throw new IllegalArgumentException("Resolved semantics are not a capability gap");
        EvidenceRequirement.Kind kind = status == SemanticStatus.CONDITIONAL
                ? EvidenceRequirement.Kind.CONFIGURATION : EvidenceRequirement.Kind.ALTERNATE_FRONTEND;
        return entry("java.semantic", "SEMANTIC_" + status.name(), kind, question(kind),
                EvidenceRequirement.AuthorizationClass.PASSIVE,
                AffectedOutput.Kind.ANALYSIS_COVERAGE, "analysis.coverage");
    }

    public static Entry entryFor(ObservationRecord.EvidenceState state) {
        if (state != ObservationRecord.EvidenceState.MISSING) {
            throw new IllegalArgumentException("Only missing evidence is a capability gap");
        }
        return entry("java.provenance", "MISSING_EVIDENCE", EvidenceRequirement.Kind.ALTERNATE_FRONTEND,
                "java.establish-observation-evidence", EvidenceRequirement.AuthorizationClass.PASSIVE,
                AffectedOutput.Kind.ANALYSIS_COVERAGE, "analysis.coverage");
    }

    public static Entry entryFor(SourceOutcome.State state) {
        if (state == SourceOutcome.State.PROCESSED) throw new IllegalArgumentException("Processed source is not a capability gap");
        return entry("java.source", "SOURCE_" + state.name(), EvidenceRequirement.Kind.ALTERNATE_FRONTEND,
                "java.process-source", EvidenceRequirement.AuthorizationClass.PASSIVE,
                AffectedOutput.Kind.ANALYSIS_COVERAGE, "analysis.coverage");
    }

    public static Entry entryFor(CategoryCoverage.Support support) {
        if (support == CategoryCoverage.Support.IMPLEMENTED) {
            throw new IllegalArgumentException("Implemented category support is not a capability gap");
        }
        return entry("java.category", "CATEGORY_" + support.name(), EvidenceRequirement.Kind.ALTERNATE_FRONTEND,
                "java.support-relationship-category", EvidenceRequirement.AuthorizationClass.PASSIVE,
                AffectedOutput.Kind.ANALYSIS_COVERAGE, "analysis.coverage");
    }

    public static Entry entryFor(FrontendResult.State state) {
        if (state == FrontendResult.State.COMPLETED) throw new IllegalArgumentException("Completed frontend is not a capability gap");
        return entry("java.frontend", "FRONTEND_" + state.name(), EvidenceRequirement.Kind.ALTERNATE_FRONTEND,
                "java.complete-frontend-analysis", EvidenceRequirement.AuthorizationClass.PASSIVE,
                AffectedOutput.Kind.ANALYSIS_COVERAGE, "analysis.coverage");
    }

    public static Entry missingOrigin() {
        return entry("java.origin", "MISSING_ORIGIN", EvidenceRequirement.Kind.ALTERNATE_FRONTEND,
                "java.establish-entity-origin", EvidenceRequirement.AuthorizationClass.PASSIVE,
                AffectedOutput.Kind.ANALYSIS_COVERAGE, "analysis.coverage");
    }

    public static Entry missingProvenance() {
        return entry("java.provenance", "MISSING_PROVENANCE", EvidenceRequirement.Kind.ALTERNATE_FRONTEND,
                "java.establish-source-provenance", EvidenceRequirement.AuthorizationClass.PASSIVE,
                AffectedOutput.Kind.ANALYSIS_COVERAGE, "analysis.coverage");
    }

    public static Entry unmappedObservation() {
        return entry("java.observation", "UNMAPPED_OBSERVATION", EvidenceRequirement.Kind.ALTERNATE_FRONTEND,
                "java.map-observation", EvidenceRequirement.AuthorizationClass.PASSIVE,
                AffectedOutput.Kind.ANALYSIS_COVERAGE, "analysis.coverage");
    }

    private static Entry entry(String mechanism, String reason, EvidenceRequirement.Kind kind,
            String question, EvidenceRequirement.AuthorizationClass auth,
            AffectedOutput.Kind outputKind, String outputId) {
        return new Entry(mechanism, reason, kind, question, auth, new AffectedOutput(outputKind, outputId));
    }

    private static String question(EvidenceRequirement.Kind kind) {
        return switch (kind) {
            case REPOSITORY_CONTENT -> "repository.acquire-content";
            case BUILD_MODEL -> "build.establish-model";
            case DEPENDENCY_ARTIFACT -> "build.acquire-dependency-artifact";
            case GENERATED_SOURCE -> "build.acquire-generated-source";
            case PLATFORM_SYMBOLS -> "build.acquire-platform-symbols";
            case BYTECODE -> "java.acquire-bytecode";
            case CONFIGURATION -> "analysis.establish-configuration";
            case ISOLATED_BUILD_OUTPUT -> "build.acquire-isolated-output";
            case RUNTIME_OBSERVATION -> "runtime.acquire-observation";
            case ALTERNATE_FRONTEND -> "java.acquire-alternate-semantic-evidence";
            case SOURCE_OWNERSHIP -> "workspace.establish-source-ownership";
            case DECODED_SOURCE -> "build.decode-source";
            case EXACT_CLASSPATH -> "build.establish-exact-classpath";
            case REACTOR_OUTPUT -> "build.acquire-reactor-output";
        };
    }
}
