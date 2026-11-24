package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.EntityIdentity;
import com.evolution.analysis.contract.identity.ModuleIdentity;
import com.evolution.analysis.contract.identity.SourceDocumentIdentity;
import com.evolution.analysis.contract.semantic.DerivedRelationshipRecord;
import com.evolution.analysis.contract.semantic.Diagnostic;
import com.evolution.analysis.contract.semantic.RelationshipOccurrence;
import com.evolution.analysis.contract.semantic.SemanticStatus;
import com.evolution.analysis.contract.source.SourceDocument;
import com.evolution.analysis.frontend.*;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.expr.SimpleName;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Bounded per-module incremental extraction cache enabling intra-module file-level
 * incremental analysis in {@link JavaParserFrontend} while preserving bit-for-bit
 * identical {@link FrontendResult} outputs with cold extraction.
 */
final class ModuleIncrementalCache {
    private static final int MAX_MODULES = Integer.getInteger("evo.incremental.maxCachedModules", 16);
    private static final long MAX_CACHE_WEIGHT = Long.getLong("evo.incremental.maxCachedModuleWeight", 128L * 1024 * 1024);
    private static final Set<String> LOMBOK_CLASS_ANNOTATIONS = Set.of(
            "Data", "Value", "Builder", "SuperBuilder", "RequiredArgsConstructor",
            "AllArgsConstructor", "NoArgsConstructor", "UtilityClass", "Getter", "Setter",
            "EqualsAndHashCode", "ToString", "With", "Delegate");

    private static long totalWeight = 0L;
    private static final Map<ContentDigest, ModuleEntry> CACHE =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<ContentDigest, ModuleEntry> eldest) {
                    if (size() > MAX_MODULES || totalWeight > MAX_CACHE_WEIGHT) {
                        totalWeight -= eldest.getValue().estimatedBytes();
                        return true;
                    }
                    return false;
                }
            });

    record UnitEntry(
            SourceDocumentIdentity document,
            ContentDigest contentDigest,
            ContentDigest apiSignatureDigest,
            String packageName,
            Set<String> declaredSimpleNames,
            Set<String> referencedSimpleNames,
            boolean hasWildcardImport,
            boolean hasLombokClassAnnotation,
            boolean hadUnresolvedOrError,
            List<DeclarationRecord> declarations,
            List<RelationshipOccurrence> occurrences,
            List<ObservationRecord> observations,
            List<TypeUseRecord> types,
            List<AnnotationUseRecord> annotations,
            List<DerivedRelationshipRecord> derived,
            List<TypeDeclarationRecord> shapes,
            List<MemberDeclarationRecord> members,
            List<LombokConstructorCandidate> constructorCandidates,
            List<HttpClientCallRecord> httpClientCalls,
            List<Diagnostic> unitDiagnostics) {}

    record ModuleEntry(
            ContentDigest contextKey,
            List<DeclarationRecord> headerDeclarations,
            Set<EntityIdentity> duplicateDeclarations,
            Map<SourceDocumentIdentity, UnitEntry> units,
            long estimatedBytes) {
        ModuleEntry(ContentDigest contextKey, List<DeclarationRecord> headerDeclarations,
                    Set<EntityIdentity> duplicateDeclarations, Map<SourceDocumentIdentity, UnitEntry> units) {
            this(contextKey, headerDeclarations, duplicateDeclarations, units, estimate(units));
        }

        private static long estimate(Map<SourceDocumentIdentity, UnitEntry> units) {
            long bytes = 4096L;
            for (var u : units.values()) {
                bytes += 512L + (u.declarations().size() + u.occurrences().size() + u.observations().size()
                        + u.types().size() + u.annotations().size()) * 256L;
            }
            return bytes;
        }
    }

    static final class Plan {
        final ModuleEntry previous;
        final Set<SourceDocumentIdentity> dirtyDocuments;

        Plan(ModuleEntry previous, Set<SourceDocumentIdentity> dirtyDocuments) {
            this.previous = previous;
            this.dirtyDocuments = dirtyDocuments;
        }
    }

    static ContentDigest moduleContextKey(FrontendRequest request, VersionedIdentifier provider, boolean fastArchitecture) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            var bytes = new ByteArrayOutputStream(2048);
            try (var out = new DataOutputStream(bytes)) {
                out.writeUTF("javaparser-module-context:v2");
                out.writeUTF(provider.id());
                out.writeUTF(provider.version());
                out.writeBoolean(fastArchitecture);
                out.writeBoolean(request.sourceRecovery());
                out.writeUTF(request.manifest().configuration().values()
                        .getOrDefault(FrontendRequest.RECOVERY_PLATFORM_COMPATIBLE, ""));
                out.writeUTF(request.manifest().configuration().values()
                        .getOrDefault("java.binary-native-origins", ""));
                out.writeInt(request.plan().syntaxLevel().orElse(-1));
                out.writeInt(request.plan().bytecodeTarget().orElse(-1));
                out.writeBoolean(request.plan().preview());
                out.writeUTF(request.module().value());
                out.writeUTF(request.sourceSet().name());
                out.writeUTF(request.platform().entry().logicalName());
                out.writeUTF(request.platform().entry().contentDigest().value());
                out.writeInt(request.platform().release());
                out.writeUTF(request.platform().version());
                out.writeUTF(request.platform().vendor());
                out.writeInt(request.platform().artifacts().size());
                for (var art : request.platform().artifacts()) {
                    out.writeUTF(art.logicalName());
                    out.writeUTF(art.contentDigest().value());
                }
                out.writeInt(request.dependencies().size());
                for (var dep : request.dependencies()) {
                    out.writeUTF(dep.entry().logicalName());
                    out.writeUTF(dep.entry().contentDigest().value());
                    out.writeUTF(dep.originEntry().logicalName());
                    out.writeUTF(dep.originEntry().contentDigest().value());
                    out.writeUTF(dep.reactorModule().map(ModuleIdentity::value).orElse(""));
                    out.writeUTF(dep.nativeOrigin().map(o -> o.original().contentDigest().value()).orElse(""));
                }
                out.writeInt(request.reactorSources().size());
                for (var r : request.reactorSources()) {
                    out.writeInt(r.order());
                    out.writeUTF(r.identity().value());
                    out.writeInt(r.sources().size());
                    for (var rs : r.sources()) {
                        out.writeUTF(rs.document().identity().value());
                        out.writeUTF(rs.document().path());
                        out.writeUTF(rs.document().contentDigest().value());
                        out.writeUTF(rs.document().classification().name());
                        out.writeUTF(rs.decoding().charset());
                    }
                }
                out.writeBoolean(request.correspondence().isPresent());
                if (request.correspondence().isPresent()) {
                    out.writeUTF(request.manifest().configuration().values()
                            .getOrDefault("java.source-binary-correspondence", ""));
                }
            }
            md.update(bytes.toByteArray());
            return new ContentDigest("sha256:" + HexFormat.of().formatHex(md.digest()));
        } catch (NoSuchAlgorithmException | IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static Optional<ModuleEntry> get(ContentDigest contextKey) {
        if (Boolean.getBoolean("evo.incremental.disabled")) return Optional.empty();
        return Optional.ofNullable(CACHE.get(contextKey));
    }

    static void put(ModuleEntry entry) {
        if (Boolean.getBoolean("evo.incremental.disabled")) return;
        synchronized (CACHE) {
            var old = CACHE.put(entry.contextKey(), entry);
            if (old != null) totalWeight -= old.estimatedBytes();
            totalWeight += entry.estimatedBytes();
        }
    }

    /**
     * Computes which documents must be re-extracted given the previous {@link ModuleEntry}
     * and the current parsed header units. Returns {@link Optional#empty()} when a full cold
     * extraction is required or more economical.
     */
    static Optional<Plan> planIncremental(
            ModuleEntry prev,
            List<SourceDocument> currentDocs,
            Map<SourceDocumentIdentity, HeaderFacts> currentHeaders,
            Set<EntityIdentity> currentDuplicateDeclarations,
            boolean hasRejectedDocs) {
        if (prev == null || hasRejectedDocs || currentDocs.size() <= 4) {
            return Optional.empty();
        }
        if (!currentDuplicateDeclarations.equals(prev.duplicateDeclarations())) {
            return Optional.empty();
        }

        var currentById = new HashMap<SourceDocumentIdentity, SourceDocument>(currentDocs.size() * 2);
        for (var doc : currentDocs) {
            if (!currentHeaders.containsKey(doc.identity())) {
                return Optional.empty();
            }
            if (isMetaFile(doc.path())) {
                // module-info / package-info / lombok.config edits require whole-module analysis.
                var old = prev.units().get(doc.identity());
                if (old == null || !old.contentDigest().equals(doc.contentDigest())) {
                    return Optional.empty();
                }
            }
            currentById.put(doc.identity(), doc);
        }

        var changed = new HashSet<SourceDocumentIdentity>();
        var removed = new HashSet<SourceDocumentIdentity>();
        for (var oldId : prev.units().keySet()) {
            if (!currentById.containsKey(oldId)) {
                removed.add(oldId);
            }
        }
        for (var doc : currentDocs) {
            var old = prev.units().get(doc.identity());
            if (old == null || !old.contentDigest().equals(doc.contentDigest())) {
                changed.add(doc.identity());
            }
        }

        if (changed.isEmpty() && removed.isEmpty()) {
            return Optional.of(new Plan(prev, Set.of()));
        }

        boolean apiOrTopologyChanged = !removed.isEmpty();
        var affectedSimpleNames = new HashSet<String>();
        var affectedPackages = new HashSet<String>();

        for (var remId : removed) {
            var old = prev.units().get(remId);
            affectedSimpleNames.addAll(old.declaredSimpleNames());
            affectedPackages.add(old.packageName());
        }
        for (var chId : changed) {
            var old = prev.units().get(chId);
            var cur = currentHeaders.get(chId);
            if (old == null
                    || !old.apiSignatureDigest().equals(cur.apiSignatureDigest())
                    || old.hasLombokClassAnnotation() != cur.hasLombokClassAnnotation()) {
                apiOrTopologyChanged = true;
                if (old != null) {
                    affectedSimpleNames.addAll(old.declaredSimpleNames());
                    affectedPackages.add(old.packageName());
                }
                affectedSimpleNames.addAll(cur.declaredSimpleNames());
                affectedPackages.add(cur.packageName());
            }
        }

        var dirty = new LinkedHashSet<SourceDocumentIdentity>(changed);
        if (apiOrTopologyChanged) {
            var queue = new ArrayDeque<String>(affectedSimpleNames);
            var visitedNames = new HashSet<String>(affectedSimpleNames);
            var visitedPackages = new HashSet<String>(affectedPackages);
            for (var doc : currentDocs) {
                var id = doc.identity();
                if (dirty.contains(id)) continue;
                var prevUnit = prev.units().get(id);
                if (prevUnit == null || prevUnit.hadUnresolvedOrError() || prevUnit.hasWildcardImport()) {
                    dirty.add(id);
                    if (prevUnit != null) {
                        for (String dn : prevUnit.declaredSimpleNames()) {
                            if (visitedNames.add(dn)) queue.add(dn);
                        }
                    }
                }
            }
            boolean expanded = true;
            int hops = 0;
            while (expanded && hops < 4) {
                expanded = false;
                hops++;
                for (var doc : currentDocs) {
                    var id = doc.identity();
                    if (dirty.contains(id)) continue;
                    var prevUnit = prev.units().get(id);
                    if (prevUnit == null) continue;
                    if (visitedPackages.contains(prevUnit.packageName())
                            || intersects(prevUnit.referencedSimpleNames(), visitedNames)) {
                        dirty.add(id);
                        expanded = true;
                        for (String dn : prevUnit.declaredSimpleNames()) {
                            if (visitedNames.add(dn)) queue.add(dn);
                        }
                    }
                }
            }
        }

        int safetyCeiling = Math.max(2, (int) Math.floor(currentDocs.size() * 0.45));
        if (dirty.size() > safetyCeiling) {
            return Optional.empty();
        }
        return Optional.of(new Plan(prev, Collections.unmodifiableSet(dirty)));
    }

    private static boolean intersects(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        Set<String> smaller = a.size() <= b.size() ? a : b;
        Set<String> larger = a.size() <= b.size() ? b : a;
        for (String item : smaller) {
            if (larger.contains(item)) return true;
        }
        return false;
    }

    private static boolean isMetaFile(String path) {
        return path.endsWith("/module-info.java") || path.equals("module-info.java")
                || path.endsWith("/package-info.java") || path.equals("package-info.java");
    }

    record HeaderFacts(
            ContentDigest apiSignatureDigest,
            String packageName,
            Set<String> declaredSimpleNames,
            boolean hasWildcardImport,
            boolean hasLombokClassAnnotation) {}

    /** Computes a signature digest covering only API-visible AST structure (excluding executable bodies). */
    static HeaderFacts summarizeHeader(CompilationUnit headerAst) {
        String pkg = headerAst.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        boolean wildcard = headerAst.getImports().stream().anyMatch(i -> i.isAsterisk());
        var declared = new TreeSet<String>();
        boolean[] hasLombok = {false};

        var sb = new StringBuilder(1024);
        sb.append("pkg:").append(pkg).append('\n');
        headerAst.getModule().ifPresent(m -> sb.append("module:").append(m).append('\n'));
        for (var imp : headerAst.getImports()) {
            sb.append("imp:").append(imp.isStatic() ? "static:" : "")
                    .append(imp.getNameAsString()).append(imp.isAsterisk() ? ".*" : "").append('\n');
        }
        for (TypeDeclaration<?> type : headerAst.findAll(TypeDeclaration.class)) {
            String tName = type.getNameAsString();
            declared.add(tName);
            sb.append("type:").append(type.getClass().getSimpleName()).append(':').append(tName)
                    .append(":mods=").append(type.getModifiers());
            for (com.github.javaparser.ast.expr.AnnotationExpr ann : type.getAnnotations()) {
                sb.append(":@").append(ann.toString());
                if (LOMBOK_CLASS_ANNOTATIONS.contains(ann.getName().getIdentifier())) {
                    hasLombok[0] = true;
                }
            }
            if (type instanceof ClassOrInterfaceDeclaration ci) {
                sb.append(":iface=").append(ci.isInterface())
                        .append(":tp=").append(ci.getTypeParameters())
                        .append(":ext=").append(ci.getExtendedTypes())
                        .append(":impl=").append(ci.getImplementedTypes())
                        .append(":perm=").append(ci.getPermittedTypes());
            } else if (type instanceof RecordDeclaration rec) {
                for (var rp : rec.getParameters()) declared.add(rp.getNameAsString());
                sb.append(":tp=").append(rec.getTypeParameters())
                        .append(":params=").append(rec.getParameters())
                        .append(":impl=").append(rec.getImplementedTypes());
            } else if (type instanceof EnumDeclaration en) {
                sb.append(":impl=").append(en.getImplementedTypes())
                        .append(":entries=").append(en.getEntries().stream().map(e -> e.getNameAsString()
                                + (e.getClassBody().isEmpty() ? "" : e.getClassBody().toString())).toList());
            }
            sb.append('\n');
            boolean isInterfaceType = type instanceof ClassOrInterfaceDeclaration ci && ci.isInterface();
            for (var member : type.getMembers()) {
                if (member instanceof FieldDeclaration fd) {
                    sb.append("field:").append(fd.getModifiers()).append(':').append(fd.getAnnotations())
                            .append(':').append(fd.getElementType());
                    for (var v : fd.getVariables()) {
                        declared.add(v.getNameAsString());
                        sb.append(':').append(v.getType()).append(' ').append(v.getNameAsString());
                        if ((fd.isStatic() && fd.isFinal()) || isInterfaceType) {
                            v.getInitializer().ifPresent(init -> sb.append('=').append(init));
                        }
                    }
                    sb.append('\n');
                } else if (member instanceof MethodDeclaration md) {
                    declared.add(md.getNameAsString());
                    sb.append("method:").append(md.getModifiers()).append(':').append(md.getAnnotations())
                            .append(':').append(md.getTypeParameters()).append(':')
                            .append(md.getType()).append(' ').append(md.getNameAsString())
                            .append('(').append(md.getParameters()).append(')')
                            .append(":throws=").append(md.getThrownExceptions()).append('\n');
                } else if (member instanceof ConstructorDeclaration cd) {
                    sb.append("ctor:").append(cd.getModifiers()).append(':').append(cd.getAnnotations())
                            .append(':').append(cd.getTypeParameters()).append(':')
                            .append(cd.getNameAsString()).append('(').append(cd.getParameters()).append(')')
                            .append(":throws=").append(cd.getThrownExceptions()).append('\n');
                } else if (member instanceof CompactConstructorDeclaration ccd) {
                    sb.append("cctor:").append(ccd.getModifiers()).append(':').append(ccd.getAnnotations())
                            .append(':').append(ccd.getNameAsString()).append('\n');
                } else if (member instanceof AnnotationMemberDeclaration amd) {
                    declared.add(amd.getNameAsString());
                    sb.append("annm:").append(amd.getType()).append(' ').append(amd.getNameAsString())
                            .append(':').append(amd.getAnnotations()).append(':').append(amd.getDefaultValue()).append('\n');
                }
            }
        }
        ContentDigest sig = ContentDigest.sha256Utf8(sb.toString());
        return new HeaderFacts(sig, pkg, Collections.unmodifiableSet(declared), wildcard, hasLombok[0]);
    }

    /** Collects identifier tokens referenced anywhere in a full AST unit for dirty-closure propagation. */
    static Set<String> collectReferencedSimpleNames(CompilationUnit ast) {
        var names = new HashSet<String>(64);
        ast.walk(node -> {
            if (node instanceof SimpleName sn) {
                names.add(sn.getIdentifier());
            } else if (node instanceof Name n) {
                names.add(n.getIdentifier());
            }
        });
        return Collections.unmodifiableSet(names);
    }

    static boolean hasUnresolvedOrErrors(Collection<RelationshipOccurrence> occurrences, Collection<Diagnostic> diagnostics) {
        if (!diagnostics.isEmpty()) return true;
        for (var occ : occurrences) {
            if (occ.status() != SemanticStatus.RESOLVED) return true;
        }
        return false;
    }
}
