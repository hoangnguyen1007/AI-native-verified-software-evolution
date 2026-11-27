package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.identity.SourceDocumentIdentity;
import com.evolution.analysis.frontend.SourceInput;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import java.util.*;
import java.util.function.Function;

/**
 * Demand-driven {@link TypeSolver} backed by an index of {@code FQN -> SourceDocumentIdentity}.
 * Unchanged files are NEVER parsed unless {@code SymbolSolver} actually queries one of their types!
 * Also used in worker-isolated parallel Pass 2 so zero AST {@code Node} instances are shared across threads.
 */
public final class LazySkeletonTypeSolver implements TypeSolver {
    public record MaterializedUnit(
            SourceDocumentIdentity document,
            SourceInput input,
            CompilationUnit skeletonAst,
            Map<String, ResolvedReferenceTypeDeclaration> declarations) {}

    private final Map<String, SourceDocumentIdentity> fqnToDocument;
    private final java.util.function.BiFunction<SourceDocumentIdentity, java.util.function.Consumer<MaterializedUnit>, MaterializedUnit> loader;
    private final Map<SourceDocumentIdentity, MaterializedUnit> loadedDocuments = new HashMap<>();
    private final Set<SourceDocumentIdentity> loadingDocuments = new HashSet<>();
    private final Map<String, SymbolReference<ResolvedReferenceTypeDeclaration>> resolvedByFqn = new HashMap<>();
    private final Map<String, SymbolReference<ResolvedReferenceTypeDeclaration>> eagerDeclarations = new HashMap<>();
    private TypeSolver parent;
    private int materializedDocumentCount;

    public LazySkeletonTypeSolver(
            Map<String, SourceDocumentIdentity> fqnToDocument,
            Function<SourceDocumentIdentity, MaterializedUnit> loader) {
        this(fqnToDocument, (docId, publishEarly) -> loader.apply(docId));
    }

    public LazySkeletonTypeSolver(
            Map<String, SourceDocumentIdentity> fqnToDocument,
            java.util.function.BiFunction<SourceDocumentIdentity, java.util.function.Consumer<MaterializedUnit>, MaterializedUnit> loader) {
        this.fqnToDocument = Objects.requireNonNull(fqnToDocument);
        this.loader = Objects.requireNonNull(loader);
    }

    /** Registers an already-parsed declaration (e.g. from dirty units) without triggering lazy load. */
    public void registerEagerDeclaration(String qualifiedName, ResolvedReferenceTypeDeclaration declaration) {
        eagerDeclarations.put(qualifiedName, SymbolReference.solved(declaration));
    }

    /** Checks FQN membership in O(1) without parsing any underlying document! */
    public boolean knowsType(String qualifiedName) {
        return eagerDeclarations.containsKey(qualifiedName) || fqnToDocument.containsKey(qualifiedName);
    }

    public int materializedDocumentCount() {
        return materializedDocumentCount;
    }

    public Collection<MaterializedUnit> materializedUnits() {
        return Collections.unmodifiableCollection(loadedDocuments.values());
    }

    @Override
    public TypeSolver getParent() {
        return parent;
    }

    @Override
    public void setParent(TypeSolver parent) {
        Objects.requireNonNull(parent);
        if (this.parent != null) throw new IllegalStateException("This TypeSolver already has a parent.");
        if (parent == this) throw new IllegalStateException("The parent of this TypeSolver cannot be itself.");
        this.parent = parent;
    }

    @Override
    public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveType(String name) {
        var eager = eagerDeclarations.get(name);
        if (eager != null) {
            return eager;
        }
        var cached = resolvedByFqn.get(name);
        if (cached != null) {
            return cached;
        }
        SourceDocumentIdentity docId = fqnToDocument.get(name);
        if (docId == null) {
            return SymbolReference.unsolved();
        }
        MaterializedUnit unit = loadedDocuments.get(docId);
        if (unit == null) {
            if (!loadingDocuments.add(docId)) {
                var inProgress = resolvedByFqn.get(name);
                return inProgress != null ? inProgress : SymbolReference.unsolved();
            }
            try {
                boolean[] published = {false};
                java.util.function.Consumer<MaterializedUnit> publishEarly = early -> {
                    if (early == null) return;
                    if (!published[0]) {
                        published[0] = true;
                        loadedDocuments.put(docId, early);
                        materializedDocumentCount++;
                    }
                    for (var entry : early.declarations().entrySet()) {
                        resolvedByFqn.put(entry.getKey(), SymbolReference.solved(entry.getValue()));
                    }
                };
                unit = loader.apply(docId, publishEarly);
                if (unit == null) {
                    return SymbolReference.unsolved();
                }
                publishEarly.accept(unit);
            } finally {
                loadingDocuments.remove(docId);
            }
        }
        var found = resolvedByFqn.get(name);
        return found != null ? found : SymbolReference.unsolved();
    }

    @Override
    public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveTypeInModule(
            String qualifiedModuleName, String simpleTypeName) {
        return SymbolReference.unsolved();
    }

    /** Helper to index all non-local top-level and member types in a freshly materialized skeleton CU. */
    static Map<String, ResolvedReferenceTypeDeclaration> indexSkeletonTypes(
            CompilationUnit ast, java.util.function.Predicate<TypeDeclaration<?>> hasLocalOwner) {
        var map = new LinkedHashMap<String, ResolvedReferenceTypeDeclaration>();
        for (TypeDeclaration<?> td : ast.findAll(TypeDeclaration.class)) {
            if (td.getFullyQualifiedName().isEmpty() || hasLocalOwner.test(td)) continue;
            map.put(td.getFullyQualifiedName().orElseThrow(), td.resolve());
        }
        return map;
    }
}
