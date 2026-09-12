package com.evolution.analysis.evidence;

import com.evolution.analysis.acquisition.*;
import com.evolution.analysis.buildmodel.BuildModelResult;
import com.evolution.analysis.classpath.ExactClasspathResult;
import com.evolution.analysis.dependency.DependencyAcquisitionResult;
import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.frontend.FrontendResult;
import com.evolution.analysis.input.*;
import com.evolution.analysis.spring.SpringMechanismInventory;
import java.util.List;

/** Explicit provider outputs selected for one normalization pass. No provider is invoked by this value. */
public record EvidenceNormalizationInput(
        List<RepositoryAcquisitionResult> repositoryAcquisitions,
        List<BuildModelResult> buildModels,
        List<CandidateSourceOwnership> sourceOwnerships,
        List<ExactClasspathResult> classpaths,
        List<DependencyAcquisitionResult> dependencyAcquisitions,
        List<SourceDecodingResult> sourceDecodings,
        List<PlatformSymbolResult> platformResults,
        List<FrontendAssemblyResult> frontendAssemblies,
        List<FrontendResult> frontendResults,
        List<SpringMechanismInventory> springInventories,
        List<AcquisitionAttemptRecord> additionalAttempts,
        List<ProviderConflictRecord> conflicts,
        List<GapResolutionRecord> resolutions) {

    public EvidenceNormalizationInput {
        repositoryAcquisitions = copy(repositoryAcquisitions, "repository acquisitions");
        buildModels = copy(buildModels, "build models");
        sourceOwnerships = copy(sourceOwnerships, "source ownerships");
        classpaths = copy(classpaths, "classpath results");
        dependencyAcquisitions = copy(dependencyAcquisitions, "dependency acquisitions");
        sourceDecodings = copy(sourceDecodings, "source decodings");
        platformResults = copy(platformResults, "platform results");
        frontendAssemblies = copy(frontendAssemblies, "frontend assemblies");
        frontendResults = copy(frontendResults, "frontend results");
        springInventories = copy(springInventories, "Spring mechanism inventories");
        additionalAttempts = copy(additionalAttempts, "additional attempts");
        conflicts = copy(conflicts, "provider conflicts");
        resolutions = copy(resolutions, "gap resolutions");
    }

    /** Source-compatible M3 constructor; M4 inputs are opt-in and empty by default. */
    public EvidenceNormalizationInput(
            List<RepositoryAcquisitionResult> repositoryAcquisitions,
            List<BuildModelResult> buildModels,
            List<CandidateSourceOwnership> sourceOwnerships,
            List<ExactClasspathResult> classpaths,
            List<DependencyAcquisitionResult> dependencyAcquisitions,
            List<SourceDecodingResult> sourceDecodings,
            List<PlatformSymbolResult> platformResults,
            List<FrontendAssemblyResult> frontendAssemblies,
            List<FrontendResult> frontendResults,
            List<AcquisitionAttemptRecord> additionalAttempts,
            List<ProviderConflictRecord> conflicts,
            List<GapResolutionRecord> resolutions) {
        this(repositoryAcquisitions, buildModels, sourceOwnerships, classpaths,
                dependencyAcquisitions, sourceDecodings, platformResults, frontendAssemblies,
                frontendResults, List.of(), additionalAttempts, conflicts, resolutions);
    }

    private static <T> List<T> copy(List<T> values, String name) {
        ContractChecks.notNull(values, name);
        values.forEach(value -> ContractChecks.notNull(value, name + " element"));
        return List.copyOf(values);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private List<RepositoryAcquisitionResult> repositoryAcquisitions = List.of();
        private List<BuildModelResult> buildModels = List.of();
        private List<CandidateSourceOwnership> sourceOwnerships = List.of();
        private List<ExactClasspathResult> classpaths = List.of();
        private List<DependencyAcquisitionResult> dependencyAcquisitions = List.of();
        private List<SourceDecodingResult> sourceDecodings = List.of();
        private List<PlatformSymbolResult> platformResults = List.of();
        private List<FrontendAssemblyResult> frontendAssemblies = List.of();
        private List<FrontendResult> frontendResults = List.of();
        private List<SpringMechanismInventory> springInventories = List.of();
        private List<AcquisitionAttemptRecord> additionalAttempts = List.of();
        private List<ProviderConflictRecord> conflicts = List.of();
        private List<GapResolutionRecord> resolutions = List.of();

        public Builder repositoryAcquisitions(List<RepositoryAcquisitionResult> values) { repositoryAcquisitions = values; return this; }
        public Builder buildModels(List<BuildModelResult> values) { buildModels = values; return this; }
        public Builder sourceOwnerships(List<CandidateSourceOwnership> values) { sourceOwnerships = values; return this; }
        public Builder classpaths(List<ExactClasspathResult> values) { classpaths = values; return this; }
        public Builder dependencyAcquisitions(List<DependencyAcquisitionResult> values) { dependencyAcquisitions = values; return this; }
        public Builder sourceDecodings(List<SourceDecodingResult> values) { sourceDecodings = values; return this; }
        public Builder platformResults(List<PlatformSymbolResult> values) { platformResults = values; return this; }
        public Builder frontendAssemblies(List<FrontendAssemblyResult> values) { frontendAssemblies = values; return this; }
        public Builder frontendResults(List<FrontendResult> values) { frontendResults = values; return this; }
        public Builder springInventories(List<SpringMechanismInventory> values) { springInventories = values; return this; }
        public Builder additionalAttempts(List<AcquisitionAttemptRecord> values) { additionalAttempts = values; return this; }
        public Builder conflicts(List<ProviderConflictRecord> values) { conflicts = values; return this; }
        public Builder resolutions(List<GapResolutionRecord> values) { resolutions = values; return this; }

        public EvidenceNormalizationInput build() {
            return new EvidenceNormalizationInput(repositoryAcquisitions, buildModels, sourceOwnerships,
                    classpaths, dependencyAcquisitions, sourceDecodings, platformResults, frontendAssemblies, frontendResults,
                    springInventories, additionalAttempts, conflicts, resolutions);
        }
    }
}
