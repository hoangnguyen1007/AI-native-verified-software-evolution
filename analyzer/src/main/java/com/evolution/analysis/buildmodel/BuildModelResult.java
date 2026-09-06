package com.evolution.analysis.buildmodel;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.ModuleDescriptor;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Declarative model/source-plan evidence, not a resolved classpath or a complete workspace analysis. */
public record BuildModelResult(String schemaVersion, ContentDigest requestIdentity, VersionedIdentifier provider,
        List<ModuleModel> modules, List<Problem> problems, List<Attempt> attempts, List<String> limitations) {
    public static final String SCHEMA = "build-model-result-v2";
    public static final List<String> DECLARATIVE_LIMITATIONS = List.of(
            "Source plans describe declared candidate roots and compiler requirements; file membership and existence are not verified.",
            "Source decoding, platform symbols and full Maven lifecycle/plugin parameter equivalence are not implemented.",
            "Dependency closures, JARs, generated outputs and target plugin effects are not evaluated.",
            "Parent and BOM lookup uses supplied relative POMs or exact-coordinate artifact POMs; no cache or network discovery.",
            "Build problems and read attempts are observations for the future normalized capability-gap contract.");
    public BuildModelResult {
        if (!SCHEMA.equals(schemaVersion)) throw new IllegalArgumentException("Unsupported build result schema");
        ContractChecks.notNull(requestIdentity, "request identity");
        ContractChecks.notNull(provider, "provider");
        modules = ContractChecks.sortedDistinct(modules, Comparator.comparing(ModuleModel::pomPath), "modules");
        problems = problems.stream().distinct().sorted(Comparator.comparing(CanonicalJson::write)).toList();
        attempts = List.copyOf(attempts);
        limitations = ContractChecks.sortedStrings(limitations, "build model limitations");
    }

    public ContentDigest identity() { return ContentDigest.sha256Utf8(CanonicalJson.write(this)); }
    /** Includes source-plan gaps; even an empty result does not remove declared limitations. */
    public boolean hasGaps() {
        return !problems.isEmpty() || modules.stream().flatMap(m -> m.effectivePom().stream())
                .flatMap(p -> p.sourcePlan().sourceSets().stream()).anyMatch(s -> !s.gaps().isEmpty());
    }

    public enum Reason {
        MISSING_MODULE_POM, MISSING_PARENT_POM, MISSING_IMPORT_BOM,
        INVALID_XML, INVALID_MODEL, UNSAFE_XML, PATH_OUTSIDE_WORKSPACE,
        UNRESOLVED_EXPRESSION, DUPLICATE_MODULE, MODULE_CYCLE, DUPLICATE_COORDINATE,
        UNSUPPORTED_ACTIVATION, EXTENSIONS_NOT_LOADED, MODEL_WARNING,
        INPUT_LIMIT, MODEL_READ_LIMIT, COORDINATE_MISMATCH
    }
    public enum Requirement { BUILD_MODEL, ANALYSIS_CONFIGURATION, AUTHORIZED_EXTENSION_PROVIDER }
    public enum AttemptKind { MODULE, PARENT, IMPORT_BOM }
    public enum Outcome { SUCCEEDED, FAILED, DENIED, UNAVAILABLE }

    public record PomEvidence(String logicalId, ContentDigest digest) {
        public PomEvidence {
            ContractChecks.text(logicalId, "POM evidence ID");
            ContractChecks.notNull(digest, "POM digest");
        }
    }

    /** Build-level evidence has no fabricated source spans or raw exception messages. */
    public record Problem(Reason reason, String subject, Requirement requirement, List<PomEvidence> evidence) {
        public Problem {
            ContractChecks.notNull(reason, "reason");
            ContractChecks.text(subject, "problem subject");
            ContractChecks.notNull(requirement, "evidence requirement");
            evidence = ContractChecks.sortedDistinct(evidence, Comparator.comparing(PomEvidence::logicalId), "problem evidence");
        }
    }

    public record Attempt(String requester, AttemptKind kind, String requested,
            Outcome outcome, Optional<PomEvidence> evidence) {
        public Attempt {
            ContractChecks.text(requester, "requester");
            ContractChecks.notNull(kind, "attempt kind");
            ContractChecks.text(requested, "requested input");
            ContractChecks.notNull(outcome, "attempt outcome");
            ContractChecks.notNull(evidence, "attempt evidence");
            if (outcome == Outcome.SUCCEEDED && evidence.isEmpty()) {
                throw new IllegalArgumentException("Successful reads require input evidence");
            }
        }
    }

    public record Dependency(String groupId, String artifactId, String version, String type,
            String classifier, String scope, boolean optional, List<String> exclusions) {
        public Dependency {
            ContractChecks.text(groupId, "dependency group");
            ContractChecks.text(artifactId, "dependency artifact");
            ContractChecks.notNull(version, "dependency version");
            ContractChecks.text(type, "dependency type");
            ContractChecks.notNull(classifier, "classifier");
            ContractChecks.text(scope, "dependency scope");
            exclusions = ContractChecks.sortedStrings(exclusions, "dependency exclusions");
        }
    }

    /** Effective declarations in Maven order; versions/scopes are not evidence of acquired artifacts. */
    public record EffectivePom(MavenCoordinate coordinate, String packaging,
            List<String> declaredModules, List<Dependency> dependencies, List<Dependency> managedDependencies,
            Map<String, String> properties, List<String> activeProfiles, List<PomEvidence> inputs, SourcePlanModel sourcePlan) {
        public EffectivePom {
            ContractChecks.notNull(coordinate, "coordinate");
            ContractChecks.text(packaging, "packaging");
            declaredModules = List.copyOf(declaredModules);
            dependencies = List.copyOf(dependencies);
            managedDependencies = List.copyOf(managedDependencies);
            properties = Map.copyOf(properties);
            activeProfiles = ContractChecks.sortedStrings(activeProfiles, "active profiles");
            inputs = ContractChecks.sortedDistinct(inputs, Comparator.comparing(PomEvidence::logicalId), "POM inputs");
            ContractChecks.notNull(sourcePlan, "source plan");
        }
    }

    /** Aggregation is independent of Maven inheritance; failed modules remain in the denominator. */
    public record ModuleModel(ModuleDescriptor module, String pomPath,
            Optional<String> aggregatorPom, Optional<EffectivePom> effectivePom) {
        public ModuleModel {
            ContractChecks.notNull(module, "module");
            ContractChecks.repositoryRelativePath(pomPath, "module POM");
            int slash = pomPath.lastIndexOf('/');
            String directory = slash < 0 ? "." : pomPath.substring(0, slash);
            if (!module.path().equals(directory)) throw new IllegalArgumentException("Module path must contain its POM");
            aggregatorPom = ContractChecks.notNull(aggregatorPom, "aggregator")
                    .map(path -> ContractChecks.repositoryRelativePath(path, "aggregator POM"));
            ContractChecks.notNull(effectivePom, "effective POM");
            if (effectivePom.isPresent() && effectivePom.get().sourcePlan().sourceSets().stream()
                    .anyMatch(s -> !s.module().equals(module.identity()))) {
                throw new IllegalArgumentException("Source plan belongs to a different module");
            }
        }
    }
}
