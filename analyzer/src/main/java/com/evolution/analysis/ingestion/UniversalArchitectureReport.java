package com.evolution.analysis.ingestion;

import com.evolution.analysis.acquisition.RepositoryUnderstandingInventory;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.source.SourceSpan;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.spring.truth.TruthRegionEvaluation;
import java.util.*;

/**
 * Canonical unified architecture intelligence report.
 * Provides a stable projection contract for downstream consumers (knowledge graph, policy engine, CLI, UI).
 * Combines repository inventory, module boundaries, type declarations, dependencies,
 * Spring components, injection bindings, entry points, truth regions, and root cause explanations.
 */
public final class UniversalArchitectureReport {
    public static final VersionedIdentifier PROVIDER =
            new VersionedIdentifier("architecture.report", "m4uv2.3-v1");

    public enum Status { COMPLETE, PARTIAL, TERMINAL_FAILURE, EMPTY_OR_NON_JAVA, CANCELLED }
    public enum Layer { STRUCTURAL, EXACT_SEMANTIC }
    public enum ConfidenceLevel { FULL, PARTIAL, QUALIFIED, MINIMAL }
    public enum EntryPointKind { BOOT_APPLICATION, CONTROLLER_ENDPOINT, MAIN_METHOD, REPOSITORY_INTERFACE, COMPONENT_SCAN_ROOT }
    public enum Avoidability { AVOIDABLE_DEFECT, UNAVAILABLE_EVIDENCE }
    public enum RootCauseCategory {
        MISSING_GENERATED_CONTRACT_TYPES,
        EXTERNAL_CONFIG_NOT_SUPPLIED,
        OPAQUE_REGISTRAR_OR_CONDITION,
        UNRESOLVED_DEPENDENCIES,
        DYNAMIC_BUILD_LOGIC,
        COMPILER_CONFIGURATION_MISSING,
        MALFORMED_OR_BOUNDED_INPUT,
        MISSING_SOURCE_OR_RESOURCE,
        SEMANTIC_PROVIDER_FAILURE,
        UNSUPPORTED_LANGUAGE_OR_FRAMEWORK_FEATURE,
        RESOURCE_BUDGET_EXCEEDED
    }

    public record ModuleBoundary(
            ModuleIdentity identity,
            String name,
            String path,
            String tool,
            Optional<String> buildFile,
            Optional<String> coordinate,
            List<String> languages,
            List<String> sourceRoots,
            List<String> dependencies
    ) {
        public ModuleBoundary {
            Objects.requireNonNull(identity);
            name = ContractChecks.text(name, "module name");
            path = ContractChecks.text(path, "module path");
            tool = ContractChecks.text(tool, "build tool");
            Objects.requireNonNull(buildFile);
            Objects.requireNonNull(coordinate);
            languages = List.copyOf(languages);
            sourceRoots = List.copyOf(sourceRoots);
            dependencies = List.copyOf(dependencies);
        }
    }

    public record TypeRecord(
            ModuleIdentity module,
            String qualifiedName,
            String kind,
            String sourcePath,
            Optional<SourceSpan> span,
            boolean isPublic,
            List<String> modifiers
    ) {
        public TypeRecord {
            Objects.requireNonNull(module);
            qualifiedName = ContractChecks.text(qualifiedName, "qualifiedName");
            kind = ContractChecks.text(kind, "type kind");
            sourcePath = ContractChecks.text(sourcePath, "sourcePath");
            Objects.requireNonNull(span);
            modifiers = List.copyOf(modifiers);
        }
    }

    public record DependencyRecord(
            String source,
            String target,
            String kind,
            Layer layer,
            String provenance
    ) {
        public DependencyRecord {
            source = ContractChecks.text(source, "dependency source");
            target = ContractChecks.text(target, "dependency target");
            kind = ContractChecks.text(kind, "dependency kind");
            Objects.requireNonNull(layer);
            provenance = ContractChecks.text(provenance, "dependency provenance");
        }
    }

    public record ComponentRecord(
            String identity,
            String beanName,
            String declaringType,
            String stereotype,
            String container,
            List<String> profiles,
            boolean conditional
    ) {
        public ComponentRecord {
            identity = ContractChecks.text(identity, "component identity");
            beanName = ContractChecks.text(beanName, "beanName");
            declaringType = ContractChecks.text(declaringType, "declaringType");
            stereotype = ContractChecks.text(stereotype, "stereotype");
            container = ContractChecks.text(container, "container");
            profiles = List.copyOf(profiles);
        }
    }

    public record ProducerRecord(
            String factoryType,
            String methodName,
            String productType,
            String beanName,
            List<String> conditions
    ) {
        public ProducerRecord {
            factoryType = ContractChecks.text(factoryType, "factoryType");
            methodName = ContractChecks.text(methodName, "methodName");
            productType = ContractChecks.text(productType, "productType");
            beanName = ContractChecks.text(beanName, "beanName");
            conditions = List.copyOf(conditions);
        }
    }

    public record InjectionSiteRecord(
            String owner,
            String memberName,
            String targetType,
            String siteKind,
            List<String> qualifiers,
            boolean required
    ) {
        public InjectionSiteRecord {
            owner = ContractChecks.text(owner, "site owner");
            memberName = ContractChecks.text(memberName, "memberName");
            targetType = ContractChecks.text(targetType, "targetType");
            siteKind = ContractChecks.text(siteKind, "siteKind");
            qualifiers = List.copyOf(qualifiers);
        }
    }

    public record EntryPointRecord(
            String declaringType,
            EntryPointKind kind,
            String detail,
            String sourcePath
    ) {
        public EntryPointRecord {
            declaringType = ContractChecks.text(declaringType, "declaringType");
            Objects.requireNonNull(kind);
            detail = ContractChecks.text(detail, "detail");
            sourcePath = ContractChecks.text(sourcePath, "sourcePath");
        }
    }

    public record TruthRegionSummary(
            String factKey,
            String kind,
            TruthRegionEvaluation.Classification classification,
            int feasibleWorlds,
            boolean hasWitness
    ) {
        public TruthRegionSummary {
            factKey = ContractChecks.text(factKey, "factKey");
            kind = ContractChecks.text(kind, "kind");
            Objects.requireNonNull(classification);
        }
    }

    public record RootCauseExplanation(
            RootCauseCategory category,
            String description,
            String affectedArchitecture,
            String attempted,
            String nextEvidenceRequirement,
            List<String> affectedObligations,
            Avoidability mode
    ) {
        public RootCauseExplanation {
            Objects.requireNonNull(category);
            description = ContractChecks.text(description, "description");
            affectedArchitecture = ContractChecks.text(affectedArchitecture, "affectedArchitecture");
            attempted = ContractChecks.text(attempted, "attempted");
            nextEvidenceRequirement = ContractChecks.text(nextEvidenceRequirement, "nextEvidenceRequirement");
            affectedObligations = List.copyOf(affectedObligations);
            Objects.requireNonNull(mode);
        }
    }

    public record ArchitectureMetrics(
            int totalFiles,
            int javaFiles,
            int analyzedModules,
            int declaredTypes,
            int dependenciesCount,
            int evidencedComponents,
            int injectionSitesCount,
            int entryPointsCount,
            int truthRegionsCount,
            int unknownFactsCount,
            ConfidenceLevel confidence,
            String summaryText
    ) {
        public ArchitectureMetrics {
            Objects.requireNonNull(confidence);
            summaryText = ContractChecks.text(summaryText, "summaryText");
        }
    }

    private final ContentDigest inputIdentity;
    private final Status status;
    private final Optional<RepositoryUnderstandingInventory> inventory;
    private final List<ModuleBoundary> modules;
    private final List<TypeRecord> types;
    private final List<DependencyRecord> dependencies;
    private final List<ComponentRecord> components;
    private final List<ProducerRecord> producers;
    private final List<InjectionSiteRecord> injectionSites;
    private final List<EntryPointRecord> entryPoints;
    private final List<TruthRegionSummary> truthRegions;
    private final List<RootCauseExplanation> rootCauses;
    private final List<IngestionEvidence.Issue> issues;
    private final List<CapabilityGapRecord> gaps;
    private final ArchitectureMetrics metrics;
    private final ContentDigest identity;

    public UniversalArchitectureReport(
            ContentDigest inputIdentity,
            Status status,
            Optional<RepositoryUnderstandingInventory> inventory,
            List<ModuleBoundary> modules,
            List<TypeRecord> types,
            List<DependencyRecord> dependencies,
            List<ComponentRecord> components,
            List<ProducerRecord> producers,
            List<InjectionSiteRecord> injectionSites,
            List<EntryPointRecord> entryPoints,
            List<TruthRegionSummary> truthRegions,
            List<RootCauseExplanation> rootCauses,
            List<IngestionEvidence.Issue> issues,
            List<CapabilityGapRecord> gaps,
            ArchitectureMetrics metrics
    ) {
        this.inputIdentity = Objects.requireNonNull(inputIdentity);
        this.status = Objects.requireNonNull(status);
        this.inventory = Objects.requireNonNull(inventory);
        this.modules = List.copyOf(modules);
        this.types = List.copyOf(types);
        this.dependencies = List.copyOf(dependencies);
        this.components = List.copyOf(components);
        this.producers = List.copyOf(producers);
        this.injectionSites = List.copyOf(injectionSites);
        this.entryPoints = List.copyOf(entryPoints);
        this.truthRegions = List.copyOf(truthRegions);
        this.rootCauses = List.copyOf(rootCauses);
        this.issues = List.copyOf(issues);
        this.gaps = List.copyOf(gaps);
        this.metrics = Objects.requireNonNull(metrics);

        this.identity = IngestionEvidence.digest(List.of(
                PROVIDER,
                inputIdentity,
                status.name(),
                this.modules.stream().map(m -> List.of(m.identity().value(), m.name(), m.path(), m.tool())).toList(),
                this.types.stream().map(t -> List.of(t.module().value(), t.qualifiedName(), t.kind())).toList(),
                this.dependencies.stream().map(d -> List.of(d.source(), d.target(), d.kind(), d.layer().name())).toList(),
                this.components.stream().map(c -> List.of(c.identity(), c.beanName(), c.declaringType(), c.stereotype())).toList(),
                this.producers.stream().map(p -> List.of(p.factoryType(), p.methodName(), p.productType(), p.beanName())).toList(),
                this.injectionSites.stream().map(s -> List.of(s.owner(), s.memberName(), s.targetType(), s.siteKind())).toList(),
                this.entryPoints.stream().map(e -> List.of(e.declaringType(), e.kind().name(), e.detail())).toList(),
                this.truthRegions.stream().map(r -> List.of(r.factKey(), r.kind(), r.classification().name(), r.feasibleWorlds())).toList(),
                this.rootCauses.stream().map(rc -> List.of(rc.category().name(), rc.affectedArchitecture(), rc.mode().name(), rc.affectedObligations())).toList(),
                this.issues.stream().map(IngestionEvidence.Issue::identity).toList(),
                this.gaps.stream().map(g -> g.gapIdentity().value()).toList(),
                List.of(metrics.totalFiles(), metrics.javaFiles(), metrics.analyzedModules(), metrics.declaredTypes(),
                        metrics.evidencedComponents(), metrics.truthRegionsCount(), metrics.unknownFactsCount(), metrics.confidence().name())
        ));
    }

    public ContentDigest inputIdentity() { return inputIdentity; }
    public Status status() { return status; }
    public Optional<RepositoryUnderstandingInventory> inventory() { return inventory; }
    public List<ModuleBoundary> modules() { return modules; }
    public List<TypeRecord> types() { return types; }
    public List<DependencyRecord> dependencies() { return dependencies; }
    public List<ComponentRecord> components() { return components; }
    public List<ProducerRecord> producers() { return producers; }
    public List<InjectionSiteRecord> injectionSites() { return injectionSites; }
    public List<EntryPointRecord> entryPoints() { return entryPoints; }
    public List<TruthRegionSummary> truthRegions() { return truthRegions; }
    public List<RootCauseExplanation> rootCauses() { return rootCauses; }
    public List<IngestionEvidence.Issue> issues() { return issues; }
    public List<CapabilityGapRecord> gaps() { return gaps; }
    public ArchitectureMetrics metrics() { return metrics; }
    public ContentDigest identity() { return identity; }

    /** Renders the canonical root-causes table formatted according to V2.3 specification. */
    public String toRootCausesTable() {
        if (rootCauses.isEmpty()) {
            return "No open UNKNOWN root causes. All evidenced obligations are closed.\n";
        }
        var sb = new StringBuilder();
        sb.append("| Root cause | Ảnh hưởng | Đã thử | Bằng chứng tiếp theo |\n");
        sb.append("|---|---|---|---|\n");
        for (var rc : rootCauses) {
            sb.append("| ").append(rc.description())
              .append(" | ").append(rc.affectedArchitecture())
              .append(" | ").append(rc.attempted())
              .append(" | ").append(rc.nextEvidenceRequirement())
              .append(" |\n");
        }
        return sb.toString();
    }

    /** Formats a full user-facing and machine-readable architectural summary report. */
    public String toMarkdownSummary() {
        var sb = new StringBuilder();
        sb.append("# Universal Architecture Report\n\n");
        sb.append("- **Status:** ").append(status).append("\n");
        sb.append("- **Confidence:** ").append(metrics.confidence).append("\n");
        sb.append("- **Report Identity:** `").append(identity.value()).append("`\n");
        sb.append("- **Input Identity:** `").append(inputIdentity.value()).append("`\n\n");

        sb.append("## Architecture Metrics\n\n");
        sb.append("- Total Files: ").append(metrics.totalFiles()).append("\n");
        sb.append("- Java Files: ").append(metrics.javaFiles()).append("\n");
        sb.append("- Analyzed Modules: ").append(metrics.analyzedModules()).append("\n");
        sb.append("- Declared Types: ").append(metrics.declaredTypes()).append("\n");
        sb.append("- Dependencies: ").append(metrics.dependenciesCount()).append("\n");
        sb.append("- Evidenced Components: ").append(metrics.evidencedComponents()).append("\n");
        sb.append("- Injection Sites: ").append(metrics.injectionSitesCount()).append("\n");
        sb.append("- Entry Points: ").append(metrics.entryPointsCount()).append("\n");
        sb.append("- Truth Regions: ").append(metrics.truthRegionsCount()).append(" (UNKNOWN facts: ").append(metrics.unknownFactsCount()).append(")\n\n");

        sb.append("## Module Boundaries\n\n");
        for (var m : modules) {
            sb.append("- **").append(m.name()).append("** (").append(m.tool()).append(" at `").append(m.path()).append("`)\n");
            sb.append("  - Languages: ").append(String.join(", ", m.languages())).append("\n");
            sb.append("  - Source Roots: ").append(String.join(", ", m.sourceRoots())).append("\n");
        }
        sb.append("\n");

        sb.append("## Entry Points\n\n");
        if (entryPoints.isEmpty()) {
            sb.append("None detected.\n\n");
        } else {
            for (var ep : entryPoints) {
                sb.append("- [").append(ep.kind()).append("] `").append(ep.declaringType()).append("`: ").append(ep.detail()).append("\n");
            }
            sb.append("\n");
        }

        sb.append("## Architecture Inventory\n\n");
        if (inventory.isPresent()) {
            var inv = inventory.get();
            sb.append("- Observed Files: ").append(inv.observedFiles().size()).append("\n");
            sb.append("- Observed Directories: ").append(inv.observedDirectories().size()).append("\n");
            sb.append("- Java Applicability: ").append(inv.javaApplicability()).append("\n\n");
        } else {
            sb.append("- Declared Types: ").append(types.size()).append("\n");
            sb.append("- Components: ").append(components.size()).append("\n");
            sb.append("- Dependencies: ").append(dependencies.size()).append("\n\n");
        }

        if (!truthRegions.isEmpty()) {
            sb.append("## Truth Regions\n\n");
            for (var tr : truthRegions) {
                sb.append("- `").append(tr.factKey()).append("`: **").append(tr.classification())
                        .append("** (").append(tr.kind()).append(", feasible worlds: ").append(tr.feasibleWorlds()).append(")\n");
            }
            sb.append("\n");
        }

        sb.append("## UNKNOWN Root Cause Explanations\n\n");
        sb.append(toRootCausesTable()).append("\n");

        return sb.toString();
    }
}
