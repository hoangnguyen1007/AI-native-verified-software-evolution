package com.evolution.analysis.buildmodel;

import com.evolution.analysis.buildmodel.BuildModelResult.PomEvidence;
import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.identity.ModuleIdentity;
import com.evolution.analysis.contract.source.SourceClassification;
import java.util.*;

/** Declarative, versioned plans. Paths are inventory-relative candidates, never acquired files. */
public record SourcePlanModel(List<SourceSetPlan> sourceSets, List<PluginDeclaration> plugins) {
    public SourcePlanModel {
        sourceSets = List.copyOf(sourceSets);
        plugins = List.copyOf(plugins);
        if (sourceSets.size() < 2 || sourceSets.get(0).kind() != Kind.MAIN
                || sourceSets.get(1).kind() != Kind.TEST
                || sourceSets.subList(2, sourceSets.size()).stream().anyMatch(s -> s.kind() != Kind.CUSTOM)) {
            throw new IllegalArgumentException("A source plan starts with main/test, then named custom sets");
        }
        ModuleIdentity owner = sourceSets.getFirst().module();
        if (sourceSets.stream().anyMatch(s -> !s.module().equals(owner))) {
            throw new IllegalArgumentException("Source sets must belong to the same module");
        }
        if (sourceSets.stream().map(SourceSetPlan::name).distinct().count() != sourceSets.size())
            throw new IllegalArgumentException("Source-set names must be unique within a module");
        sourceSets = java.util.stream.Stream.concat(sourceSets.subList(0, 2).stream(),
                sourceSets.subList(2, sourceSets.size()).stream()
                        .sorted(Comparator.comparing(SourceSetPlan::name))).toList();
    }

    public enum Kind { MAIN, TEST, CUSTOM }
    public enum Status { DECLARED, DEFAULT, UNSPECIFIED, UNRESOLVED, INVALID, UNSUPPORTED }
    public enum Origin { EFFECTIVE_MODEL, USER_PROPERTY, MAVEN_CONVENTION, GRADLE_CONVENTION, PLAIN_JAVA_CONVENTION, ABSENT }
    public enum Gap {
        UNRESOLVED_SETTING, INVALID_SETTING, UNSAFE_PATH, MISSING_SOURCE_LEVEL, MISSING_PLATFORM_RELEASE,
        MISSING_ENCODING, UNSUPPORTED_ENCODING, UNSUPPORTED_COMPILER_CONFIGURATION, ADDITIONAL_COMPILER_EXECUTION,
        PLUGIN_EFFECTS_NOT_EVALUATED, GENERATED_SOURCES_NOT_ACQUIRED, OVERLAPPING_SOURCE_ROOTS,
        PACKAGING_LIFECYCLE_NOT_EVALUATED, UNRESOLVED_SOURCE_ROLE
    }

    /** Selector addresses the effective projection, not a fabricated span in any one parent POM. */
    public record Setting(String selector, Optional<String> expression, Optional<String> value,
            Status status, Origin origin, List<PomEvidence> inputs) {
        public Setting {
            ContractChecks.text(selector, "setting selector");
            Objects.requireNonNull(expression); Objects.requireNonNull(value);
            Objects.requireNonNull(status); Objects.requireNonNull(origin);
            inputs = ContractChecks.sortedDistinct(inputs, Comparator.comparing(PomEvidence::logicalId), "setting evidence");
            if ((status == Status.DECLARED || status == Status.DEFAULT) != value.isPresent()) {
                throw new IllegalArgumentException("Only declared/default settings have a usable value");
            }
            if ((origin == Origin.ABSENT) != (status == Status.UNSPECIFIED)) {
                throw new IllegalArgumentException("Absent origin is reserved for unspecified settings");
            }
            if (status == Status.DEFAULT && !Set.of(Origin.MAVEN_CONVENTION,Origin.GRADLE_CONVENTION,Origin.PLAIN_JAVA_CONVENTION).contains(origin)) {
                throw new IllegalArgumentException("Defaults require an explicit convention");
            }
            value.ifPresent(v -> ContractChecks.text(v, "setting value"));
            if (status == Status.UNSPECIFIED && (expression.isPresent() || origin != Origin.ABSENT)) {
                throw new IllegalArgumentException("Unspecified settings have no invented declaration");
            }
            if (status != Status.UNSPECIFIED && expression.isEmpty()) throw new IllegalArgumentException("Setting lacks input expression");
            if (origin == Origin.EFFECTIVE_MODEL && inputs.isEmpty()) throw new IllegalArgumentException("Model setting lacks evidence");
        }
    }

    public record SourceSetPlan(ModuleIdentity module, Kind kind, String name,
            SourceClassification semanticRole, List<Setting> sourceRoots,
            List<Setting> resourceRoots, Setting outputDirectory, Map<String, Setting> compilerSettings,
            Setting syntaxLevel, Setting bytecodeTarget, Setting platformRelease, Setting encoding,
            List<Setting> generatedSourceHints, List<Gap> gaps) {
        public SourceSetPlan {
            Objects.requireNonNull(module); Objects.requireNonNull(kind);
            name = ContractChecks.token(name, "source-set name");
            Objects.requireNonNull(semanticRole);
            if (kind == Kind.MAIN && (!name.equals("main") || semanticRole != SourceClassification.MAIN)
                    || kind == Kind.TEST && (!name.equals("test") || semanticRole != SourceClassification.TEST)
                    || kind == Kind.CUSTOM && (Set.of("main", "test").contains(name)
                            || !Set.of(SourceClassification.MAIN, SourceClassification.TEST,
                                    SourceClassification.OTHER).contains(semanticRole)))
                throw new IllegalArgumentException("Source-set kind, name and semantic role disagree");
            sourceRoots = List.copyOf(sourceRoots); resourceRoots = List.copyOf(resourceRoots);
            Objects.requireNonNull(outputDirectory);
            compilerSettings = Map.copyOf(compilerSettings);
            Objects.requireNonNull(syntaxLevel); Objects.requireNonNull(bytecodeTarget); Objects.requireNonNull(platformRelease); Objects.requireNonNull(encoding);
            generatedSourceHints = List.copyOf(generatedSourceHints);
            gaps = gaps.stream().distinct().sorted().toList();
            if (kind == Kind.CUSTOM && semanticRole == SourceClassification.OTHER
                    && !gaps.contains(Gap.UNRESOLVED_SOURCE_ROLE))
                throw new IllegalArgumentException("Unknown custom source role needs an explicit gap");
        }
        public SourceSetPlan(ModuleIdentity module, Kind kind, List<Setting> sourceRoots,
                List<Setting> resourceRoots, Setting outputDirectory, Map<String, Setting> compilerSettings,
                Setting syntaxLevel, Setting bytecodeTarget, Setting platformRelease, Setting encoding,
                List<Setting> generatedSourceHints, List<Gap> gaps) {
            this(module, kind, kind == Kind.MAIN ? "main" : "test",
                    kind == Kind.MAIN ? SourceClassification.MAIN : SourceClassification.TEST,
                    sourceRoots, resourceRoots, outputDirectory, compilerSettings,
                    syntaxLevel, bytecodeTarget, platformRelease, encoding, generatedSourceHints, gaps);
        }
    }

    /** All configuration is preserved as neutral data, including options this projection cannot interpret. */
    public record Configuration(String name, Optional<String> value, Map<String, String> attributes,
            List<Configuration> children) {
        public Configuration {
            ContractChecks.text(name, "configuration name"); Objects.requireNonNull(value);
            attributes = Map.copyOf(attributes); children = List.copyOf(children);
        }
    }
    public record Execution(String id, Optional<String> phase, List<String> goals, Optional<Configuration> configuration) {
        public Execution {
            Objects.requireNonNull(id); Objects.requireNonNull(phase);
            goals = List.copyOf(goals); Objects.requireNonNull(configuration);
        }
    }
    public record PluginDeclaration(String coordinate, Optional<String> version, boolean managementOnly,
            Optional<Configuration> configuration, List<Execution> executions, List<PomEvidence> inputs) {
        public PluginDeclaration {
            ContractChecks.text(coordinate, "plugin coordinate"); Objects.requireNonNull(version);
            Objects.requireNonNull(configuration); executions = List.copyOf(executions);
            inputs = List.copyOf(inputs);
        }
    }
}
