package com.evolution.analysis.buildmodel;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.ingestion.*;
import java.util.*;

/** Build-tool-neutral envelope. Gradle/plain modules never acquire invented Maven coordinates or POMs. */
public record UniversalBuildModel(ContentDigest inputIdentity, ContentDigest repositoryInputIdentity, List<Module> modules,
                                  List<BuildModelResult> mavenModels, List<IngestionEvidence.Issue> issues,
                                  List<CapabilityGapRecord> gaps) {
    public enum Tool { MAVEN, GRADLE_GROOVY, GRADLE_KOTLIN, PLAIN_JAVA }
    public record Dependency(String configuration, Optional<MavenCoordinate> coordinate,
                             Optional<String> projectPath, String notation, BuildModelResult.PomEvidence evidence) {
        public Dependency { Objects.requireNonNull(configuration); Objects.requireNonNull(coordinate); Objects.requireNonNull(projectPath); Objects.requireNonNull(notation); Objects.requireNonNull(evidence); }
    }
    public record Module(ModuleDescriptor descriptor, Tool tool, Optional<String> buildFile,
                         Optional<MavenCoordinate> coordinate, SourcePlanModel sourcePlan,
                         List<Dependency> dependencies, List<BuildModelResult.PomEvidence> evidence) {
        public Module { Objects.requireNonNull(descriptor); Objects.requireNonNull(tool); Objects.requireNonNull(buildFile);
            Objects.requireNonNull(coordinate); Objects.requireNonNull(sourcePlan); dependencies=List.copyOf(dependencies); evidence=List.copyOf(evidence);
            buildFile.ifPresent(p->ContractChecks.repositoryRelativePath(p,"build file"));
            if(sourcePlan.sourceSets().stream().anyMatch(s->!s.module().equals(descriptor.identity())))throw new IllegalArgumentException("Foreign source plan module");
        }
    }
    public UniversalBuildModel {
        Objects.requireNonNull(inputIdentity);Objects.requireNonNull(repositoryInputIdentity);
        modules=ContractChecks.sortedDistinct(modules,Comparator.comparing(m -> m.descriptor().path()),"universal build modules");
        mavenModels=List.copyOf(mavenModels); issues=List.copyOf(issues); gaps=List.copyOf(gaps);
    }
    public ContentDigest identity() { return IngestionEvidence.digest(this); }
}
