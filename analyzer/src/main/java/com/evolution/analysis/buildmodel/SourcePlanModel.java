package com.evolution.analysis.buildmodel;

import com.evolution.analysis.buildmodel.BuildModelResult.PomEvidence;
import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.identity.ModuleIdentity;
import java.util.*;

/** Declarative, versioned plans. Paths are inventory-relative candidates, never acquired files. */
public record SourcePlanModel(List<SourceSetPlan> sourceSets, List<PluginDeclaration> plugins) {
    public SourcePlanModel {
        sourceSets = List.copyOf(sourceSets);
        plugins = List.copyOf(plugins);
        if (!sourceSets.stream().map(SourceSetPlan::kind).toList().equals(List.of(Kind.MAIN, Kind.TEST))) {
            throw new IllegalArgumentException("A source plan must account for main and test exactly once");
        }
        if (!sourceSets.getFirst().module().equals(sourceSets.getLast().module())) {
            throw new IllegalArgumentException("Source sets must belong to the same module");
        }
    }

    public enum Kind { MAIN, TEST }
    public enum Status { DECLARED, DEFAULT, UNSPECIFIED, UNRESOLVED, INVALID, UNSUPPORTED }
    public enum Origin { EFFECTIVE_MODEL, USER_PROPERTY, MAVEN_CONVENTION, ABSENT }
    public enum Gap {
        UNRESOLVED_SETTING, INVALID_SETTING, UNSAFE_PATH, MISSING_SOURCE_LEVEL, MISSING_PLATFORM_RELEASE,
        MISSING_ENCODING, UNSUPPORTED_ENCODING, UNSUPPORTED_COMPILER_CONFIGURATION, ADDITIONAL_COMPILER_EXECUTION,
        PLUGIN_EFFECTS_NOT_EVALUATED, GENERATED_SOURCES_NOT_ACQUIRED, OVERLAPPING_SOURCE_ROOTS,
        PACKAGING_LIFECYCLE_NOT_EVALUATED
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
            if (status == Status.DEFAULT && origin != Origin.MAVEN_CONVENTION) {
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

    public record SourceSetPlan(ModuleIdentity module, Kind kind, List<Setting> sourceRoots,
            List<Setting> resourceRoots, Setting outputDirectory, Map<String, Setting> compilerSettings,
            Setting syntaxLevel, Setting bytecodeTarget, Setting platformRelease, Setting encoding,
            List<Setting> generatedSourceHints, List<Gap> gaps) {
        public SourceSetPlan {
            Objects.requireNonNull(module); Objects.requireNonNull(kind);
            sourceRoots = List.copyOf(sourceRoots); resourceRoots = List.copyOf(resourceRoots);
            Objects.requireNonNull(outputDirectory);
            compilerSettings = Map.copyOf(compilerSettings);
            Objects.requireNonNull(syntaxLevel); Objects.requireNonNull(bytecodeTarget); Objects.requireNonNull(platformRelease); Objects.requireNonNull(encoding);
            generatedSourceHints = List.copyOf(generatedSourceHints);
            gaps = gaps.stream().distinct().sorted().toList();
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
