package com.evolution.analysis.contract.analysis;

import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.AnalysisIdentity;
import com.evolution.analysis.contract.identity.ModuleIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.ModuleDescriptor;
import com.evolution.analysis.contract.source.RepositorySnapshot;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Complete stable input manifest for one analysis identity.
 *
 * <p>The analysis identity covers the snapshot/source hashes, module model, exact classpath,
 * configuration, analyzer binary/version, rule set, graph schema, and manifest schema.
 */
public record AnalysisManifest(
        AnalysisIdentity identity,
        VersionedIdentifier manifestVersion,
        RepositorySnapshot snapshot,
        List<ModuleDescriptor> modules,
        List<ClasspathEntry> classpath,
        AnalysisConfiguration configuration,
        ManifestComponent analyzer,
        ManifestComponent ruleSet,
        ManifestComponent graphSchema) {

    public AnalysisManifest {
        ContractChecks.notNull(identity, "analysis identity");
        ContractChecks.notNull(manifestVersion, "manifest version");
        ContractChecks.notNull(snapshot, "repository snapshot");
        modules = ContractChecks.sortedDistinct(
                modules, Comparator.naturalOrder(), "manifest modules");
        classpath = ContractChecks.distinctInOrder(classpath, "classpath entries");
        ContractChecks.notNull(configuration, "analysis configuration");
        ContractChecks.notNull(analyzer, "analyzer component");
        ContractChecks.notNull(ruleSet, "rule-set component");
        ContractChecks.notNull(graphSchema, "graph-schema component");
        validateModuleCoverage(snapshot, modules);
        AnalysisIdentity expected = deriveIdentity(
                manifestVersion,
                snapshot,
                modules,
                classpath,
                configuration,
                analyzer,
                ruleSet,
                graphSchema);
        if (!identity.equals(expected)) {
            throw new IllegalArgumentException("analysis identity does not match manifest inputs");
        }
    }

    public static AnalysisManifest create(
            VersionedIdentifier manifestVersion,
            RepositorySnapshot snapshot,
            List<ModuleDescriptor> modules,
            List<ClasspathEntry> classpath,
            AnalysisConfiguration configuration,
            ManifestComponent analyzer,
            ManifestComponent ruleSet,
            ManifestComponent graphSchema) {
        List<ModuleDescriptor> sortedModules = ContractChecks.sortedDistinct(
                modules, Comparator.naturalOrder(), "manifest modules");
        List<ClasspathEntry> orderedClasspath =
                ContractChecks.distinctInOrder(classpath, "classpath entries");
        validateModuleCoverage(snapshot, sortedModules);
        AnalysisIdentity identity = deriveIdentity(
                manifestVersion,
                snapshot,
                sortedModules,
                orderedClasspath,
                configuration,
                analyzer,
                ruleSet,
                graphSchema);
        return new AnalysisManifest(
                identity,
                manifestVersion,
                snapshot,
                sortedModules,
                orderedClasspath,
                configuration,
                analyzer,
                ruleSet,
                graphSchema);
    }

    private static AnalysisIdentity deriveIdentity(
            VersionedIdentifier manifestVersion,
            RepositorySnapshot snapshot,
            List<ModuleDescriptor> modules,
            List<ClasspathEntry> classpath,
            AnalysisConfiguration configuration,
            ManifestComponent analyzer,
            ManifestComponent ruleSet,
            ManifestComponent graphSchema) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("analyzer", analyzer);
        inputs.put("classpath", classpath);
        inputs.put("configurationIdentity", configuration.identity());
        inputs.put("graphSchema", graphSchema);
        inputs.put("manifestVersion", manifestVersion);
        inputs.put("modules", modules);
        inputs.put("ruleSet", ruleSet);
        inputs.put("snapshotContentDigest", snapshot.contentDigest());
        inputs.put("snapshotIdentity", snapshot.identity());
        return AnalysisIdentity.fromCanonicalManifestInputs(CanonicalJson.write(inputs));
    }

    private static void validateModuleCoverage(
            RepositorySnapshot snapshot, List<ModuleDescriptor> modules) {
        ContractChecks.notNull(snapshot, "repository snapshot");
        if (modules.stream()
                .anyMatch(module -> !module.repository().equals(snapshot.repository()))) {
            throw new IllegalArgumentException("all modules must belong to the snapshot repository");
        }
        Set<ModuleIdentity> available = modules.stream()
                .map(ModuleDescriptor::identity)
                .collect(Collectors.toUnmodifiableSet());
        if (snapshot.documents().stream().anyMatch(document -> !available.contains(document.module()))) {
            throw new IllegalArgumentException(
                    "every snapshot source document must reference a manifest module");
        }
    }
}
