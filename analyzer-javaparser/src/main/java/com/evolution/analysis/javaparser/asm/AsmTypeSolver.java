package com.evolution.analysis.javaparser.asm;

import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * Flyweight per-context {@link TypeSolver} backed by an immutable shared {@link AsmBytecodeIndex}.
 * Zero bytecode re-parsing across modules or threads; automatically falls back to Javassist on
 * corrupt/unexpected bytecode or when {@code -Devo.perf.asmTypeSolver=false}.
 */
public final class AsmTypeSolver implements TypeSolver {
    private final AsmBytecodeIndex index;
    private final Map<String, SymbolReference<ResolvedReferenceTypeDeclaration>> solvedCache = new HashMap<>(64);
    private TypeSolver parent;
    private javassist.ClassPool fallbackPool;

    public AsmTypeSolver(AsmBytecodeIndex index) {
        this.index = Objects.requireNonNull(index);
    }

    public static boolean isEnabled() {
        String explicitSolver = System.getProperty("evo.perf.bytecodeSolver");
        if ("javassist".equalsIgnoreCase(explicitSolver)) {
            return false;
        }
        String flag = System.getProperty("evo.perf.asmTypeSolver");
        return flag == null || !"false".equalsIgnoreCase(flag);
    }

    public Set<String> getKnownClasses() {
        return index.knownClasses().keySet();
    }

    public AsmBytecodeIndex index() {
        return index;
    }

    /**
     * Resolves record component types directly from bytecode {@link AsmClassStub#recordComponents()}
     * when {@code declaration} is an ASM-backed bytecode record (so {@code RecordPatternInference}
     * never requires {@code toAst()} on compiled dependency/JDK records).
     */
    public static Optional<List<com.github.javaparser.resolution.types.ResolvedType>> recordComponentTypes(
            ResolvedReferenceTypeDeclaration declaration) {
        if (!(declaration instanceof AsmDeclarations.AsmRecordDeclaration rec)) {
            return Optional.empty();
        }
        int count = rec.recordComponents().size();
        var types = new ArrayList<com.github.javaparser.resolution.types.ResolvedType>(count);
        for (int i = 0; i < count; i++) {
            types.add(rec.resolveRecordComponentType(i));
        }
        return Optional.of(List.copyOf(types));
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
        String poolName = index.poolNameForCanonical(name);
        if (poolName == null) {
            return SymbolReference.unsolved();
        }
        var cached = solvedCache.get(name);
        if (cached != null) {
            return cached;
        }
        if (isEnabled()) {
            Optional<AsmClassStub> stub = index.loadStub(poolName);
            if (stub.isPresent()) {
                var solved = SymbolReference.solved(AsmDeclarations.create(stub.orElseThrow(), getRoot()));
                solvedCache.put(name, solved);
                return solved;
            }
        }
        // Clean fallback to Javassist when ASM is disabled or bytecode is unusual/corrupt.
        return solveWithJavassistFallback(name, poolName);
    }

    @Override
    public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveTypeInModule(
            String qualifiedModuleName, String simpleTypeName) {
        if (index.moduleToExportedPackages().isPresent()) {
            var pair = index.moduleToExportedPackages().get();
            if (pair.a.equals(qualifiedModuleName)) {
                for (String pkg : pair.b) {
                    var candidate = tryToSolveType(pkg + "." + simpleTypeName);
                    if (candidate.isSolved()) return candidate;
                }
            }
        }
        return SymbolReference.unsolved();
    }

    private SymbolReference<ResolvedReferenceTypeDeclaration> solveWithJavassistFallback(
            String canonicalName, String poolName) {
        try {
            if (fallbackPool == null) {
                var p = new javassist.ClassPool(false);
                p.appendClassPath(mapClassPath(index.classBytesByPoolName()));
                fallbackPool = p;
            }
            var solved = SymbolReference.solved(
                    com.github.javaparser.symbolsolver.javassistmodel.JavassistFactory.toTypeDeclaration(
                            fallbackPool.get(poolName), getRoot()));
            solvedCache.put(canonicalName, solved);
            return solved;
        } catch (javassist.NotFoundException e) {
            throw new IllegalStateException("Unable to get class with name " + poolName + " from class pool.", e);
        }
    }

    private static javassist.ClassPath mapClassPath(Map<String, byte[]> pool) {
        return new javassist.ClassPath() {
            @Override
            public InputStream openClassfile(String classname) {
                byte[] raw = pool.get(classname);
                return raw == null ? null : new ByteArrayInputStream(raw);
            }

            @Override
            public java.net.URL find(String classname) {
                if (!pool.containsKey(classname)) return null;
                try {
                    return new java.net.URL(null, "file:/CachedArchive/" + classname.replace('.', '/') + ".class",
                            new java.net.URLStreamHandler() {
                                @Override
                                protected java.net.URLConnection openConnection(java.net.URL url) {
                                    return new java.net.URLConnection(url) {
                                        @Override public void connect() {}
                                        @Override public int getContentLength() {
                                            byte[] raw = pool.get(classname);
                                            return raw == null ? -1 : raw.length;
                                        }
                                        @Override public InputStream getInputStream() throws IOException {
                                            byte[] raw = pool.get(classname);
                                            if (raw == null) throw new FileNotFoundException(classname);
                                            return new ByteArrayInputStream(raw);
                                        }
                                    };
                                }
                            });
                } catch (java.net.MalformedURLException impossible) {
                    return null;
                }
            }
        };
    }
}
