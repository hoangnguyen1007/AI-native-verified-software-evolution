package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.SourceDocumentIdentity;
import java.util.*;

/**
 * Fine-grained symbol-level reverse dependency graph and unbounded fixpoint dirty-closure solver.
 * Fixes:
 * <ul>
 *   <li>Unbounded inheritance/subtype transitive propagation (eliminates the old {@code hops < 4} soundness bug)</li>
 *   <li>Scoped package wildcard ({@code import pkg.*}) and static wildcard ({@code import static T.*}) invalidation</li>
 *   <li>{@code FAST_ARCHITECTURE} {@code architecturalSimpleTypeNames} symmetric-diff invalidation</li>
 *   <li>Body-only Early Cutoff when {@code externalAbiDigest} is unchanged</li>
 * </ul>
 */
public final class FileDependencyIndex {
    public record UnitNode(
            SourceDocumentIdentity document,
            String path,
            ContentDigest contentDigest,
            ContentDigest externalAbiDigest,
            ContentDigest publicCrossModuleAbiDigest,
            ContentDigest springRelevantDigest,
            ContentDigest staticMembersDigest,
            String packageName,
            Set<String> declaredFqns,
            Set<String> declaredSimpleNames,
            Set<String> supertypeNames,
            Set<String> explicitImportedFqns,
            Set<String> wildcardImportedPackages,
            Set<String> staticWildcardTypes,
            Set<String> referencedFqns,
            Set<String> referencedSimpleNames,
            boolean hasLombokClassAnnotation,
            boolean hadUnresolvedOrError) {}

    public record DirtyClosureResult(
            Set<SourceDocumentIdentity> dirtyDocuments,
            int abiEarlyCutoffCount,
            int impactedDependentsCount,
            boolean exceededSafetyCeiling,
            Optional<String> fallbackReason) {}

    private FileDependencyIndex() {}

    /**
     * Computes the exact transitive set of dirty documents starting from changed, added, removed,
     * and upstream-reactor-ABI-changed symbols using an unbounded worklist fixpoint.
     */
    public static DirtyClosureResult computeDirtyClosure(
            Map<SourceDocumentIdentity, UnitNode> previousUnits,
            Map<SourceDocumentIdentity, UnitNode> currentChangedAndAdded,
            Set<SourceDocumentIdentity> removedDocuments,
            Set<String> changedUpstreamReactorFqns,
            Set<String> changedUpstreamReactorSimpleNames,
            boolean fastArchitecture,
            int totalCurrentDocs,
            double safetyCeilingRatio) {
        var dirty = new LinkedHashSet<SourceDocumentIdentity>(currentChangedAndAdded.keySet());
        var affectedFqns = new LinkedHashSet<String>(changedUpstreamReactorFqns);
        var affectedPackages = new LinkedHashSet<String>();
        var affectedStaticTypes = new LinkedHashSet<String>(changedUpstreamReactorFqns);
        var symmetricDiffSimpleNames = new LinkedHashSet<String>(changedUpstreamReactorSimpleNames);

        // Build exact FQN -> package index across previous, changed, and removed units so nested types
        // (pkg.Outer.Inner) never mis-slice their package to "pkg.Outer".
        var fqnToPackage = new HashMap<String, String>(Math.max(16, previousUnits.size() * 2));
        for (var u : previousUnits.values()) {
            for (String f : u.declaredFqns()) fqnToPackage.put(f, u.packageName());
        }
        for (var u : currentChangedAndAdded.values()) {
            for (String f : u.declaredFqns()) fqnToPackage.put(f, u.packageName());
        }

        for (String upFqn : changedUpstreamReactorFqns) {
            String pkg = resolvePackageForFqn(upFqn, fqnToPackage);
            if (!pkg.isEmpty()) {
                affectedPackages.add(pkg);
            }
            // If nested type (pkg.Outer.Inner), also mark enclosing owner prefixes so `import pkg.Outer.*;` invalidates.
            int dot = upFqn.lastIndexOf('.');
            while (dot > pkg.length()) {
                String owner = upFqn.substring(0, dot);
                affectedFqns.add(owner);
                dot = upFqn.lastIndexOf('.', dot - 1);
            }
        }

        boolean abiChanged = !removedDocuments.isEmpty() || !changedUpstreamReactorFqns.isEmpty();
        boolean newTypesAdded = false;
        int earlyCutoffs = 0;

        for (var remId : removedDocuments) {
            UnitNode old = previousUnits.get(remId);
            if (old != null) {
                affectedFqns.addAll(old.declaredFqns());
                affectedStaticTypes.addAll(old.declaredFqns());
                affectedPackages.add(old.packageName());
                symmetricDiffSimpleNames.addAll(old.declaredSimpleNames());
            }
        }

        for (var entry : currentChangedAndAdded.entrySet()) {
            SourceDocumentIdentity docId = entry.getKey();
            UnitNode cur = entry.getValue();
            UnitNode old = previousUnits.get(docId);
            if (old == null) {
                // Brand new file added
                abiChanged = true;
                newTypesAdded = true;
                affectedFqns.addAll(cur.declaredFqns());
                affectedStaticTypes.addAll(cur.declaredFqns());
                affectedPackages.add(cur.packageName());
                symmetricDiffSimpleNames.addAll(cur.declaredSimpleNames());
                continue;
            }
            boolean sameExternalAbi = old.externalAbiDigest().equals(cur.externalAbiDigest())
                    && old.packageName().equals(cur.packageName())
                    && old.hasLombokClassAnnotation() == cur.hasLombokClassAnnotation()
                    && old.declaredFqns().equals(cur.declaredFqns())
                    && old.declaredSimpleNames().equals(cur.declaredSimpleNames());
            if (sameExternalAbi) {
                earlyCutoffs++;
            } else {
                abiChanged = true;
                affectedFqns.addAll(old.declaredFqns());
                affectedFqns.addAll(cur.declaredFqns());
                affectedPackages.add(old.packageName());
                affectedPackages.add(cur.packageName());
                if (!old.staticMembersDigest().equals(cur.staticMembersDigest())
                        || !old.declaredFqns().equals(cur.declaredFqns())) {
                    affectedStaticTypes.addAll(old.declaredFqns());
                    affectedStaticTypes.addAll(cur.declaredFqns());
                }
                for (String fqn : cur.declaredFqns()) {
                    if (!old.declaredFqns().contains(fqn)) newTypesAdded = true;
                }
                for (String sn : old.declaredSimpleNames()) {
                    if (!cur.declaredSimpleNames().contains(sn)) symmetricDiffSimpleNames.add(sn);
                }
                for (String sn : cur.declaredSimpleNames()) {
                    if (!old.declaredSimpleNames().contains(sn)) symmetricDiffSimpleNames.add(sn);
                }
            }
        }

        if (!abiChanged && symmetricDiffSimpleNames.isEmpty()) {
            return new DirtyClosureResult(
                    Collections.unmodifiableSet(dirty),
                    earlyCutoffs,
                    0,
                    false,
                    Optional.empty());
        }

        // Build O(N) reverse indexes over clean previous units only
        var byReferencedFqn = new HashMap<String, List<SourceDocumentIdentity>>();
        var byExplicitImport = new HashMap<String, List<SourceDocumentIdentity>>();
        var bySupertype = new HashMap<String, List<SourceDocumentIdentity>>();
        var byPackage = new HashMap<String, List<SourceDocumentIdentity>>();
        var byWildcardPackage = new HashMap<String, List<SourceDocumentIdentity>>();
        var byStaticWildcardType = new HashMap<String, List<SourceDocumentIdentity>>();
        var byReferencedSimpleName = new HashMap<String, List<SourceDocumentIdentity>>();

        for (var entry : previousUnits.entrySet()) {
            SourceDocumentIdentity docId = entry.getKey();
            if (dirty.contains(docId) || removedDocuments.contains(docId)) continue;
            UnitNode u = entry.getValue();
            byPackage.computeIfAbsent(u.packageName(), k -> new ArrayList<>(4)).add(docId);
            for (String refFqn : u.referencedFqns()) {
                byReferencedFqn.computeIfAbsent(refFqn, k -> new ArrayList<>(2)).add(docId);
            }
            for (String impFqn : u.explicitImportedFqns()) {
                byExplicitImport.computeIfAbsent(impFqn, k -> new ArrayList<>(2)).add(docId);
            }
            for (String sup : u.supertypeNames()) {
                bySupertype.computeIfAbsent(sup, k -> new ArrayList<>(2)).add(docId);
            }
            for (String wPkg : u.wildcardImportedPackages()) {
                byWildcardPackage.computeIfAbsent(wPkg, k -> new ArrayList<>(2)).add(docId);
            }
            for (String swType : u.staticWildcardTypes()) {
                byStaticWildcardType.computeIfAbsent(swType, k -> new ArrayList<>(2)).add(docId);
            }
            if (fastArchitecture && !symmetricDiffSimpleNames.isEmpty()) {
                for (String sn : u.referencedSimpleNames()) {
                    if (symmetricDiffSimpleNames.contains(sn)) {
                        byReferencedSimpleName.computeIfAbsent(sn, k -> new ArrayList<>(2)).add(docId);
                    }
                }
            }
        }

        // Unbounded worklist fixpoint (ZERO hops < 4 cap!)
        var fqnQueue = new ArrayDeque<String>(affectedFqns);
        var seenFqns = new HashSet<String>(affectedFqns);
        var seenStaticTypes = new HashSet<String>(affectedStaticTypes);
        var propagatedSubtypes = new HashSet<SourceDocumentIdentity>();

        java.util.function.Consumer<SourceDocumentIdentity> markDirtySubtype = docId -> {
            dirty.add(docId);
            if (propagatedSubtypes.add(docId)) {
                UnitNode sub = previousUnits.get(docId);
                if (sub != null) {
                    for (String subFqn : sub.declaredFqns()) {
                        if (seenFqns.add(subFqn)) {
                            fqnQueue.addLast(subFqn);
                        }
                        if (seenStaticTypes.add(subFqn)) {
                            for (var sw : byStaticWildcardType.getOrDefault(subFqn, List.of())) {
                                dirty.add(sw);
                            }
                        }
                    }
                }
            }
        };

        var processedPackages = new HashSet<String>();
        var pendingPackages = new ArrayDeque<String>(affectedPackages);

        while (!fqnQueue.isEmpty() || !pendingPackages.isEmpty()) {
            while (!fqnQueue.isEmpty()) {
                String fqn = fqnQueue.removeFirst();
                // 1. Transitive subtype hierarchy (extends / implements / permits) — unbounded!
                for (var subDoc : bySupertype.getOrDefault(fqn, List.of())) {
                    markDirtySubtype.accept(subDoc);
                }
                int lastDot = fqn.lastIndexOf('.');
                String fqnPkg = resolvePackageForFqn(fqn, fqnToPackage);
                String simple = lastDot > 0 ? fqn.substring(lastDot + 1) : fqn;
                String enclosingOwner = lastDot > fqnPkg.length() ? fqn.substring(0, lastDot) : "";
                String ownerQualified = enclosingOwner.isEmpty()
                        ? simple
                        : (fqnPkg.isEmpty() ? fqn : fqn.substring(fqnPkg.length() + 1));
                for (var subDoc : bySupertype.getOrDefault(simple, List.of())) {
                    if (propagatedSubtypes.contains(subDoc)) continue;
                    UnitNode sub = previousUnits.get(subDoc);
                    if (sub != null && (sub.packageName().equals(fqnPkg)
                            || sub.explicitImportedFqns().contains(fqn)
                            || (!fqnPkg.isEmpty() && sub.wildcardImportedPackages().contains(fqnPkg))
                            || (!enclosingOwner.isEmpty() && sub.wildcardImportedPackages().contains(enclosingOwner))
                            || sub.referencedFqns().contains(fqn))) {
                        markDirtySubtype.accept(subDoc);
                    }
                }
                if (!ownerQualified.equals(simple)) {
                    for (var subDoc : bySupertype.getOrDefault(ownerQualified, List.of())) {
                        if (propagatedSubtypes.contains(subDoc)) continue;
                        UnitNode sub = previousUnits.get(subDoc);
                        if (sub != null && (sub.packageName().equals(fqnPkg)
                                || (!fqnPkg.isEmpty() && sub.wildcardImportedPackages().contains(fqnPkg))
                                || (!enclosingOwner.isEmpty() && sub.explicitImportedFqns().contains(enclosingOwner))
                                || sub.referencedFqns().contains(fqn))) {
                            markDirtySubtype.accept(subDoc);
                        }
                    }
                }
                // 2. Direct FQN consumers (calls, field accesses, type uses, annotations, explicit imports)
                for (var consumer : byReferencedFqn.getOrDefault(fqn, List.of())) {
                    dirty.add(consumer);
                }
                for (var importer : byExplicitImport.getOrDefault(fqn, List.of())) {
                    dirty.add(importer);
                }
                // 2b. Nested-type wildcard importers (import pkg.Outer.*; or inherited import pkg.SubOuter.*;)
                for (var nestedWildcard : byWildcardPackage.getOrDefault(fqn, List.of())) {
                    markDirtySubtype.accept(nestedWildcard);
                }
                // 3. Static wildcard importers (import static fqn.*;)
                if (seenStaticTypes.contains(fqn)) {
                    for (var sw : byStaticWildcardType.getOrDefault(fqn, List.of())) {
                        dirty.add(sw);
                    }
                }
            }
            // 4. Package-scoped closure: same-package units + TARGETED wildcard package importers ONLY
            //    (import java.util.* is NEVER invalidated unless java.util itself changed!)
            //    Propagates transitively via markDirtySubtype so shadowing a wildcard-imported superclass
            //    also invalidates downstream subclasses in other packages!
            while (!pendingPackages.isEmpty()) {
                String pkg = pendingPackages.removeFirst();
                if (!processedPackages.add(pkg)) continue;
                for (var doc : byPackage.getOrDefault(pkg, List.of())) {
                    markDirtySubtype.accept(doc);
                }
                for (var doc : byWildcardPackage.getOrDefault(pkg, List.of())) {
                    markDirtySubtype.accept(doc);
                }
            }
        }

        // 5. Previously unresolved/error units when new types or ABI changes could resolve prior failures
        if (newTypesAdded || abiChanged) {
            for (var entry : previousUnits.entrySet()) {
                SourceDocumentIdentity docId = entry.getKey();
                if (!removedDocuments.contains(docId) && !dirty.contains(docId)
                        && entry.getValue().hadUnresolvedOrError()) {
                    dirty.add(docId);
                }
            }
        }

        // 6. FAST_ARCHITECTURE architecturalSimpleTypeNames guard
        if (fastArchitecture && !symmetricDiffSimpleNames.isEmpty()) {
            for (String sn : symmetricDiffSimpleNames) {
                for (var doc : byReferencedSimpleName.getOrDefault(sn, List.of())) {
                    dirty.add(doc);
                }
            }
        }

        int impactedDependents = Math.max(0, dirty.size() - currentChangedAndAdded.size());
        int ceiling = totalCurrentDocs <= 5
                ? Math.max(1, totalCurrentDocs - 1)
                : Math.max(2, (int) Math.floor(totalCurrentDocs * safetyCeilingRatio));
        boolean exceeded = totalCurrentDocs > 1 && dirty.size() > ceiling;
        return new DirtyClosureResult(
                Collections.unmodifiableSet(dirty),
                earlyCutoffs,
                impactedDependents,
                exceeded,
                exceeded ? Optional.of("DIRTY_RATIO_EXCEEDED") : Optional.empty());
    }

    /**
     * Resolves the exact CompilationUnit package of an FQN (avoiding mis-slicing nested types like
     * {@code pkg.Outer.Inner} into {@code pkg.Outer}). Uses recorded CU packages first, falling back
     * to stripping trailing capitalized class-name segments for external/reactor FQNs.
     */
    private static String resolvePackageForFqn(String fqn, Map<String, String> fqnToPackage) {
        if (fqn == null || fqn.isEmpty()) return "";
        String known = fqnToPackage.get(fqn);
        if (known != null) return known;
        int dot = fqn.lastIndexOf('.');
        if (dot <= 0) return "";
        String prefix = fqn.substring(0, dot);
        while (!prefix.isEmpty()) {
            int last = prefix.lastIndexOf('.');
            String tail = last >= 0 ? prefix.substring(last + 1) : prefix;
            if (!tail.isEmpty() && Character.isUpperCase(tail.charAt(0))) {
                prefix = last > 0 ? prefix.substring(0, last) : "";
            } else {
                break;
            }
        }
        return prefix;
    }
}
