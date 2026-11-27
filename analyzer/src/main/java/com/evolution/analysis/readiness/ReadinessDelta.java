package com.evolution.analysis.readiness;

import com.evolution.analysis.acquisition.streaming.InventoryManifest;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.ingestion.IngestionEvidence;
import com.evolution.analysis.polyglot.LanguageDiscovery;
import com.evolution.analysis.readiness.ReadinessPlan.*;
import com.evolution.analysis.readiness.ReadinessPlan.Module;
import java.util.*;

/**
 * Layer-2 change durability classifier and soundness-preserving {@link ReadinessPlan} fast rebinding.
 * <p>
 * Classifies every repository path into 4 durability tiers (D3 build/topology, D2 Spring config,
 * D1 Java/Kotlin source, D0 polyglot/other) and proves when an existing sealed {@link ReadinessPlan}
 * can be deterministically rebound to a new {@link InventoryManifest} with bit-for-bit identical
 * {@link ReadinessPlan#identity()} without re-scanning inventory or rewriting the ownership ledger.
 */
public final class ReadinessDelta {
    private ReadinessDelta() {}

    /** Change durability tiers from highest invalidation blast radius (D3) to lowest (D0). */
    public enum DurabilityClass {
        D3_BUILD,
        D2_SPRING_CONFIG,
        D1_JAVA_SOURCE,
        D0_POLYGLOT_OTHER
    }

    /** Classifies a repository-relative POSIX path into its invalidation durability tier. */
    public static DurabilityClass classifyPath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) return DurabilityClass.D0_POLYGLOT_OTHER;
        String path = rawPath.replace('\\', '/');
        String name = ReadinessPlanner.filename(path);
        String lower = name.toLowerCase(Locale.ROOT);
        if (ReadinessPlanner.descriptor(path)
                || isReadinessMarker(path)
                || isBuildLogicOrLocalBinary(path, lower)
                || "lombok.config".equals(name)
                || "gradle-wrapper.jar".equals(lower)
                || "maven-wrapper.jar".equals(lower)
                || path.endsWith(".versions.toml")) {
            return DurabilityClass.D3_BUILD;
        }
        if (isSpringConfigPath(path, lower)) {
            return DurabilityClass.D2_SPRING_CONFIG;
        }
        if (lower.endsWith(".java") || lower.endsWith(".kt")) {
            return DurabilityClass.D1_JAVA_SOURCE;
        }
        return DurabilityClass.D0_POLYGLOT_OTHER;
    }

    private static boolean isBuildLogicOrLocalBinary(String path, String lowerName) {
        if (path.startsWith("buildSrc/") || path.contains("/buildSrc/")
                || path.startsWith("build-logic/") || path.contains("/build-logic/")) {
            return true;
        }
        if ((lowerName.endsWith(".jar") || lowerName.endsWith(".aar"))
                && (path.startsWith("libs/") || path.contains("/libs/"))) {
            return true;
        }
        return lowerName.endsWith(".proto") || lowerName.endsWith(".aidl");
    }

    /** True if {@code path} triggers a dedicated readiness platform/generation requirement in {@link ReadinessPlanner}. */
    public static boolean isReadinessMarker(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) return false;
        String path = rawPath.replace('\\', '/');
        String name = ReadinessPlanner.filename(path);
        String lower = name.toLowerCase(Locale.ROOT);
        return path.equals("module-info.java")
                || path.endsWith("/module-info.java")
                || path.endsWith(".jsp")
                || path.endsWith(".fxml")
                || path.endsWith(".aj")
                || lower.endsWith(".proto")
                || lower.endsWith(".aidl")
                || isBuildLogicOrLocalBinary(path, lower)
                || name.equals("AndroidManifest.xml");
    }

    /** True if {@code path} can influence Spring Boot configuration spaces, properties, or auto-configuration. */
    public static boolean isSpringConfigPath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) return false;
        String path = rawPath.replace('\\', '/');
        return isSpringConfigPath(path, ReadinessPlanner.filename(path).toLowerCase(Locale.ROOT));
    }

    private static boolean isSpringConfigPath(String path, String lowerName) {
        if (lowerName.equals("spring.factories")
                || lowerName.equals("org.springframework.boot.autoconfigure.autoconfiguration.imports")
                || lowerName.equals("additional-spring-configuration-metadata.json")
                || lowerName.equals("spring-configuration-metadata.json")
                || path.contains("META-INF/spring/")) {
            return true;
        }
        boolean isConfigExt = lowerName.endsWith(".properties")
                || lowerName.endsWith(".yml")
                || lowerName.endsWith(".yaml")
                || lowerName.endsWith(".xml");
        if (!isConfigExt) return false;
        return lowerName.startsWith("application")
                || lowerName.startsWith("bootstrap")
                || lowerName.equals("web.xml")
                || path.contains("/resources/");
    }

    /**
     * True iff modifying the bytes of an already-existing file at {@code path} (without adding,
     * deleting, or changing entry kind/disposition) cannot alter descriptors, readiness markers,
     * language hints, source anchors, or ownership ledger lines.
     */
    public static boolean isReadinessNeutralModification(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) return false;
        String path = rawPath.replace('\\', '/');
        if (ReadinessPlanner.descriptor(path) || isReadinessMarker(path)) {
            return false;
        }
        // Only paths classified purely from filename/extension never inspect file bytes for shebangs.
        return ReadinessPlanner.language(path).isPresent()
                || !LanguageDiscovery.hints(path).isEmpty()
                || ReadinessPlanner.isNonSourceBinaryExtension(path);
    }

    /**
     * Checks whether {@code basePlan} can be soundly rebound to {@code newManifest} without re-running
     * {@link ReadinessPlanner}, preserving bit-for-bit identical {@link ReadinessPlan#identity()}.
     */
    public static boolean canFastRebind(
            ReadinessPlan basePlan,
            InventoryManifest newManifest,
            Collection<String> addedPaths,
            Collection<String> modifiedPaths,
            Collection<String> deletedPaths) {
        Objects.requireNonNull(basePlan);
        Objects.requireNonNull(newManifest);
        Objects.requireNonNull(addedPaths);
        Objects.requireNonNull(modifiedPaths);
        Objects.requireNonNull(deletedPaths);
        var oldManifest = basePlan.inventory();
        if (!oldManifest.repository().equals(newManifest.repository())
                || !oldManifest.selection().equals(newManifest.selection())
                || oldManifest.entries() != newManifest.entries()
                || oldManifest.files() != newManifest.files()
                || oldManifest.excluded() != newManifest.excluded()) {
            return false;
        }
        if (!addedPaths.isEmpty() || !deletedPaths.isEmpty()) {
            return false;
        }
        for (String modified : modifiedPaths) {
            if (!isReadinessNeutralModification(modified)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Rebinds {@code basePlan} to {@code newManifest} by updating inventory digest references in
     * {@code PLAIN} root/module/context evidence, {@link Context#inputClosure()}, recipe inputs,
     * and inventory-subject requirements while retaining the unchanged ownership summary & ledger.
     */
    public static ReadinessPlan rebindPlan(ReadinessPlan basePlan, InventoryManifest newManifest) {
        Objects.requireNonNull(basePlan);
        Objects.requireNonNull(newManifest);
        if (basePlan.inventory().equals(newManifest)) {
            return basePlan;
        }
        ContentDigest oldInv = basePlan.inventory().inventory();
        ContentDigest newInv = newManifest.inventory();

        var newRoots = new ArrayList<Root>(basePlan.roots().size());
        for (var r : basePlan.roots()) {
            newRoots.add(new Root(r.id(), r.directory(), r.family(),
                    rebindEvidence(r.evidence(), oldInv, newInv), r.relatedRoots()));
        }

        var newModules = new ArrayList<Module>(basePlan.modules().size());
        for (var m : basePlan.modules()) {
            newModules.add(new Module(m.id(), m.root(), m.directory(), m.target(),
                    m.languages(), m.dependencies(), rebindEvidence(m.evidence(), oldInv, newInv)));
        }

        var newContexts = new ArrayList<Context>(basePlan.contexts().size());
        for (var c : basePlan.contexts()) {
            ContentDigest newClosure = IngestionEvidence.digest(List.of(
                    newManifest.inventory(), newManifest.selection(),
                    c.module(), c.sourceSet(), c.variant(), c.environment()));
            newContexts.add(new Context(c.id(), c.module(), c.root(), c.sourceSet(), c.variant(),
                    c.sourceRoots(), c.environment(), rebindEvidence(c.evidence(), oldInv, newInv),
                    newClosure, c.buildable()));
        }

        var newRecipes = new ArrayList<Recipe>(basePlan.recipes().size());
        for (var rec : basePlan.recipes()) {
            newRecipes.add(new Recipe(rec.id(), rec.family(), rec.root(), rec.contexts(), rec.requirements(),
                    rec.actions(), rebindEvidence(rec.declaredInputs(), oldInv, newInv), rec.expectedOutputs(),
                    rec.toolPins(), rec.executionScope(), rec.networkPolicy(), rec.resourcePolicy(),
                    rec.sideEffects(), rec.satisfactionChecks()));
        }

        var idRemap = new HashMap<String, String>();
        for (var req : basePlan.requirements()) {
            if (req.subject().equals(oldInv.value())) {
                String newId = "requirement:" + IngestionEvidence.digest(List.of(req.kind(), newInv.value(), req.reason())).value();
                idRemap.put(req.id(), newId);
            }
        }
        var newRequirements = new ArrayList<Requirement>(basePlan.requirements().size());
        for (var req : basePlan.requirements()) {
            String subject = req.subject().equals(oldInv.value()) ? newInv.value() : req.subject();
            String id = idRemap.getOrDefault(req.id(), req.id());
            List<String> prereqs = req.prerequisites();
            if (!prereqs.isEmpty() && !idRemap.isEmpty()) {
                var remapped = new ArrayList<String>(prereqs.size());
                for (String p : prereqs) remapped.add(idRemap.getOrDefault(p, p));
                prereqs = remapped;
            }
            newRequirements.add(new Requirement(id, req.kind(), subject, req.reason(), prereqs,
                    rebindEvidence(req.evidence(), oldInv, newInv), req.state(), req.satisfaction()));
        }
        newRequirements.sort(Comparator.comparing(Requirement::id));

        return new ReadinessPlan(ReadinessPlan.SCHEMA, newManifest, basePlan.selection(),
                newRoots, newModules, newContexts, newRequirements, newRecipes, basePlan.ownership());
    }

    private static List<Evidence> rebindEvidence(List<Evidence> evidence, ContentDigest oldInv, ContentDigest newInv) {
        List<Evidence> updated = null;
        for (int i = 0; i < evidence.size(); i++) {
            var e = evidence.get(i);
            if ("inventory".equals(e.path()) && e.digest().equals(oldInv)) {
                if (updated == null) updated = new ArrayList<>(evidence);
                updated.set(i, new Evidence("inventory", newInv));
            }
        }
        return updated == null ? evidence : updated;
    }

    /** Summarizes a file delta by durability tier. */
    public record Summary(int d3Build, int d2SpringConfig, int d1JavaSource, int d0PolyglotOther) {
        public boolean hasBuildChanges() { return d3Build > 0; }
        public boolean hasSpringConfigChanges() { return d2SpringConfig > 0; }
        public boolean hasJavaChanges() { return d1JavaSource > 0; }
        public boolean hasPolyglotChanges() { return d0PolyglotOther > 0; }
    }

    public static Summary summarize(Collection<String> changedPaths) {
        int d3 = 0, d2 = 0, d1 = 0, d0 = 0;
        for (String path : changedPaths) {
            switch (classifyPath(path)) {
                case D3_BUILD -> d3++;
                case D2_SPRING_CONFIG -> d2++;
                case D1_JAVA_SOURCE -> d1++;
                case D0_POLYGLOT_OTHER -> d0++;
            }
        }
        return new Summary(d3, d2, d1, d0);
    }
}
