package com.evolution.analysis.javaparser.asm;

import com.github.javaparser.ast.AccessSpecifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.resolution.Context;
import com.github.javaparser.resolution.MethodUsage;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.*;
import com.github.javaparser.resolution.logic.MethodResolutionCapability;
import com.github.javaparser.resolution.model.LambdaArgumentTypePlaceholder;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.resolution.model.typesystem.NullType;
import com.github.javaparser.resolution.model.typesystem.ReferenceTypeImpl;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.resolution.types.ResolvedTypeVariable;
import com.github.javaparser.symbolsolver.core.resolution.MethodUsageResolutionCapability;
import com.github.javaparser.symbolsolver.core.resolution.SymbolResolutionCapability;
import com.github.javaparser.symbolsolver.core.resolution.TypeVariableResolutionCapability;
import com.github.javaparser.symbolsolver.logic.AbstractClassDeclaration;
import com.github.javaparser.symbolsolver.logic.AbstractTypeDeclaration;
import com.github.javaparser.symbolsolver.logic.ObjectProvider;
import java.util.*;
import org.objectweb.asm.Opcodes;

/**
 * Lightweight per-context flyweight declarations binding an immutable {@link AsmClassStub}
 * to a specific {@link TypeSolver}. Never leaks symbols across contexts.
 */
final class AsmDeclarations {
    private AsmDeclarations() {}

    static ResolvedReferenceTypeDeclaration create(AsmClassStub stub, TypeSolver rootSolver) {
        return switch (stub.kind()) {
            case ANNOTATION -> new AsmAnnotationDeclaration(stub, rootSolver);
            case INTERFACE -> new AsmInterfaceDeclaration(stub, rootSolver);
            case ENUM -> new AsmEnumDeclaration(stub, rootSolver);
            case RECORD -> new AsmRecordDeclaration(stub, rootSolver);
            case CLASS -> new AsmClassDeclaration(stub, rootSolver);
        };
    }

    /** Shared helper mirroring {@code JavassistTypeDeclarationAdapter}. */
    static final class Adapter {
        final AsmClassStub stub;
        final TypeSolver rootSolver;
        final ResolvedReferenceTypeDeclaration owner;
        private AsmSignatureResolver.ParsedClassSignature parsedSignature;
        private boolean signatureParsed;
        private List<ResolvedTypeParameterDeclaration> cachedTypeParameters;
        private Set<ResolvedMethodDeclaration> cachedDeclaredMethods;
        private List<ResolvedConstructorDeclaration> cachedConstructors;

        Adapter(AsmClassStub stub, TypeSolver rootSolver, ResolvedReferenceTypeDeclaration owner) {
            this.stub = stub;
            this.rootSolver = rootSolver;
            this.owner = owner;
        }

        private AsmSignatureResolver.ParsedClassSignature classSig() {
            if (!signatureParsed) {
                signatureParsed = true;
                if (stub.signature() != null && !stub.signature().isEmpty()) {
                    try {
                        parsedSignature = AsmSignatureResolver.parseClassSignature(stub.signature());
                    } catch (RuntimeException ignored) {
                        parsedSignature = null;
                    }
                }
            }
            return parsedSignature;
        }

        List<ResolvedTypeParameterDeclaration> getTypeParameters() {
            if (cachedTypeParameters == null) {
                var sig = classSig();
                if (sig == null || sig.typeParams().isEmpty()) {
                    cachedTypeParameters = List.of();
                } else {
                    var list = new ArrayList<ResolvedTypeParameterDeclaration>(sig.typeParams().size());
                    for (var tp : sig.typeParams()) {
                        list.add(new AsmTypeParameter(tp, owner, rootSolver));
                    }
                    cachedTypeParameters = List.copyOf(list);
                }
            }
            // JavaParser's MethodResolutionLogic mutates the returned List via addAll(...)!
            return new ArrayList<>(cachedTypeParameters);
        }

        Optional<ResolvedReferenceType> getSuperClass() {
            if ("java.lang.Object".equals(stub.qualifiedName()) || stub.superInternalName() == null) {
                return Optional.empty();
            }
            var sig = classSig();
            if (sig != null && sig.superClassSignature() != null) {
                ResolvedType resolved = AsmSignatureResolver.resolveDescriptorOrSignature(
                        sig.superClassSignature(), rootSolver, owner);
                return Optional.ofNullable(resolved == null ? null : resolved.asReferenceType());
            }
            return Optional.of(AsmSignatureResolver.resolveInternalClass(stub.superInternalName(), rootSolver).asReferenceType());
        }

        List<ResolvedReferenceType> getInterfaces(boolean acceptIncompleteList) {
            var sig = classSig();
            var result = new ArrayList<ResolvedReferenceType>(stub.interfaceInternalNames().size());
            if (sig != null && !sig.interfaceSignatures().isEmpty()) {
                for (String ifaceSig : sig.interfaceSignatures()) {
                    try {
                        result.add(AsmSignatureResolver.resolveDescriptorOrSignature(ifaceSig, rootSolver, owner).asReferenceType());
                    } catch (UnsolvedSymbolException missing) {
                        if (!acceptIncompleteList) throw missing;
                    }
                }
                return result;
            }
            for (String iface : stub.interfaceInternalNames()) {
                try {
                    result.add(AsmSignatureResolver.resolveInternalClass(iface, rootSolver).asReferenceType());
                } catch (UnsolvedSymbolException missing) {
                    if (!acceptIncompleteList) throw missing;
                }
            }
            return result;
        }

        List<ResolvedReferenceType> getAncestors(boolean acceptIncompleteList) {
            var ancestors = new ArrayList<ResolvedReferenceType>();
            try {
                getSuperClass().ifPresent(ancestors::add);
            } catch (UnsolvedSymbolException missing) {
                if (!acceptIncompleteList) throw missing;
            }
            ancestors.addAll(getInterfaces(acceptIncompleteList));
            return ancestors;
        }

        Set<ResolvedMethodDeclaration> getDeclaredMethods(boolean filterBridgeAndSynthetic) {
            if (cachedDeclaredMethods == null) {
                var result = new LinkedHashSet<ResolvedMethodDeclaration>();
                for (var m : stub.methods()) {
                    if (m.isConstructor() || m.isStaticInitializer()) continue;
                    if (filterBridgeAndSynthetic && (m.isBridge() || m.isSynthetic())) continue;
                    result.add(new AsmMethodDeclaration(m, owner, stub, rootSolver));
                }
                cachedDeclaredMethods = Collections.unmodifiableSet(result);
            }
            return new LinkedHashSet<>(cachedDeclaredMethods);
        }

        List<ResolvedConstructorDeclaration> getConstructors() {
            if (cachedConstructors == null) {
                var result = new ArrayList<ResolvedConstructorDeclaration>();
                for (var m : stub.methods()) {
                    if (!m.isConstructor() || m.isSynthetic()) continue;
                    result.add(new AsmConstructorDeclaration(m, owner, stub, rootSolver));
                }
                cachedConstructors = List.copyOf(result);
            }
            return new ArrayList<>(cachedConstructors);
        }

        List<ResolvedFieldDeclaration> getDeclaredFields() {
            var fields = new ArrayList<ResolvedFieldDeclaration>(stub.fields().size());
            for (var f : stub.fields()) {
                fields.add(new AsmFieldDeclaration(f, owner, rootSolver));
            }
            return fields;
        }

        List<ResolvedFieldDeclaration> getAllFields() {
            var fields = new ArrayList<ResolvedFieldDeclaration>(getDeclaredFields());
            var visited = new HashSet<String>();
            visited.add(owner.getQualifiedName());
            for (ResolvedReferenceType ancestor : owner.getAllAncestors()) {
                ancestor.getTypeDeclaration().ifPresent(decl -> {
                    if (visited.add(decl.getQualifiedName())) {
                        fields.addAll(decl.getDeclaredFields());
                    }
                });
            }
            return fields;
        }

        Set<ResolvedAnnotationDeclaration> getDeclaredAnnotations() {
            var result = new LinkedHashSet<ResolvedAnnotationDeclaration>();
            for (var a : stub.annotations()) {
                var solved = rootSolver.tryToSolveType(a.qualifiedName());
                if (solved.isSolved() && solved.getCorrespondingDeclaration().isAnnotation()) {
                    result.add(solved.getCorrespondingDeclaration().asAnnotation());
                }
            }
            return result;
        }

        Optional<ResolvedReferenceTypeDeclaration> containerType() {
            if (stub.outerInternalName() == null) {
                return Optional.empty();
            }
            String outerCanonical = AsmClassStub.internalToQualifiedName(stub.outerInternalName());
            var solved = rootSolver.tryToSolveType(outerCanonical);
            return solved.isSolved() ? Optional.of(solved.getCorrespondingDeclaration()) : Optional.empty();
        }

        Set<ResolvedReferenceTypeDeclaration> internalTypes() {
            if (stub.nestedInternalNames().isEmpty()) return Set.of();
            var result = new LinkedHashSet<ResolvedReferenceTypeDeclaration>();
            for (String nested : stub.nestedInternalNames()) {
                var solved = rootSolver.tryToSolveType(AsmClassStub.internalToQualifiedName(nested));
                if (solved.isSolved()) {
                    result.add(solved.getCorrespondingDeclaration());
                }
            }
            return result;
        }

        SymbolReference<? extends ResolvedValueDeclaration> solveSymbol(String name, boolean includeSuperClass) {
            for (var f : stub.fields()) {
                if (f.name().equals(name)) {
                    return SymbolReference.solved(new AsmFieldDeclaration(f, owner, rootSolver));
                }
            }
            if (includeSuperClass && stub.superInternalName() != null && !"java.lang.Object".equals(stub.qualifiedName())) {
                var res = solveSymbolForInternal(stub.superInternalName(), name);
                if (res.isSolved()) return res;
            }
            for (String iface : stub.interfaceInternalNames()) {
                var res = solveSymbolForInternal(iface, name);
                if (res.isSolved()) return res;
            }
            return SymbolReference.unsolved();
        }

        private SymbolReference<? extends ResolvedValueDeclaration> solveSymbolForInternal(String internal, String name) {
            var solved = rootSolver.tryToSolveType(AsmClassStub.internalToQualifiedName(internal));
            if (solved.isSolved() && solved.getCorrespondingDeclaration() instanceof SymbolResolutionCapability cap) {
                return cap.solveSymbol(name, rootSolver);
            }
            return SymbolReference.unsolved();
        }

        boolean isAssignableBy(ResolvedType type) {
            if (type instanceof NullType) return true;
            if (type instanceof LambdaArgumentTypePlaceholder) {
                return owner.isFunctionalInterface();
            }
            if (type.isArray()) {
                String qn = owner.getQualifiedName();
                return qn.equals("java.lang.Object") || qn.equals("java.lang.Cloneable") || qn.equals("java.io.Serializable");
            }
            if (type.isReferenceType()) {
                var otherOpt = type.asReferenceType().getTypeDeclaration();
                if (otherOpt.isEmpty()) return false;
                var other = otherOpt.orElseThrow();
                if (other.getQualifiedName().equals(owner.getQualifiedName())) return true;
                return other.canBeAssignedTo(owner);
            }
            if (type.isTypeVariable()) {
                var tp = type.asTypeVariable().asTypeParameter();
                if (tp.getBounds().isEmpty()) {
                    return owner.isJavaLangObject();
                }
                for (var bound : tp.getBounds()) {
                    if (bound.isExtends() && isAssignableBy(bound.getType())) return true;
                }
                return false;
            }
            return false;
        }

        boolean isAssignableBy(ResolvedReferenceTypeDeclaration other) {
            return isAssignableBy(new ReferenceTypeImpl(other));
        }

        boolean canBeAssignedTo(ResolvedReferenceTypeDeclaration other) {
            if (other == null) return false;
            if (other.isJavaLangObject()) return true;
            if (other instanceof LambdaArgumentTypePlaceholder) {
                return owner.isFunctionalInterface();
            }
            if (other.getQualifiedName().equals(owner.getQualifiedName())) return true;
            try {
                Optional<ResolvedReferenceType> superClass = getSuperClass();
                if (superClass.isPresent()
                        && superClass.orElseThrow().getTypeDeclaration().isPresent()
                        && superClass.orElseThrow().getTypeDeclaration().orElseThrow().canBeAssignedTo(other)) {
                    return true;
                }
            } catch (UnsolvedSymbolException ignored) {
                // Missing superclass in incomplete/minimal platform views cannot establish assignability.
            }
            for (ResolvedReferenceType iface : getInterfaces(true)) {
                if (iface.getTypeDeclaration().isPresent()
                        && iface.getTypeDeclaration().orElseThrow().canBeAssignedTo(other)) {
                    return true;
                }
            }
            return false;
        }
    }

    static final class AsmClassDeclaration extends AbstractClassDeclaration
            implements MethodUsageResolutionCapability, SymbolResolutionCapability {
        private final Adapter adapter;

        AsmClassDeclaration(AsmClassStub stub, TypeSolver rootSolver) {
            this.adapter = new Adapter(stub, rootSolver, this);
        }

        @Override public String getPackageName() { return adapter.stub.packageName(); }
        @Override public String getClassName() { return adapter.stub.className(); }
        @Override public String getQualifiedName() { return adapter.stub.qualifiedName(); }
        @Override public String getName() { return adapter.stub.simpleName(); }
        @Override public String getId() { return adapter.stub.qualifiedName(); }
        @Override public Optional<Node> toAst() { return Optional.empty(); }
        @Override public AccessSpecifier accessSpecifier() { return AsmSignatureResolver.accessSpecifier(adapter.stub.access()); }
        public boolean isAbstract() { return (adapter.stub.access() & Opcodes.ACC_ABSTRACT) != 0; }
        @Override public boolean hasDirectlyAnnotation(String qualifiedName) { return adapter.stub.hasDirectAnnotation(qualifiedName); }
        @Override public Set<ResolvedAnnotationDeclaration> getDeclaredAnnotations() { return adapter.getDeclaredAnnotations(); }
        @Override public Optional<ResolvedReferenceType> getSuperClass() { return adapter.getSuperClass(); }
        @Override public List<ResolvedReferenceType> getInterfaces() { return adapter.getInterfaces(false); }
        @Override public List<ResolvedReferenceType> getAncestors(boolean acceptIncompleteList) { return adapter.getAncestors(acceptIncompleteList); }
        @Override public Set<ResolvedMethodDeclaration> getDeclaredMethods() { return adapter.getDeclaredMethods(true); }
        @Override public List<ResolvedConstructorDeclaration> getConstructors() { return adapter.getConstructors(); }
        @Override public List<ResolvedFieldDeclaration> getDeclaredFields() { return adapter.getDeclaredFields(); }
        @Override public List<ResolvedFieldDeclaration> getAllFields() { return adapter.getAllFields(); }
        @Override public List<ResolvedTypeParameterDeclaration> getTypeParameters() { return adapter.getTypeParameters(); }
        @Override public Optional<ResolvedReferenceTypeDeclaration> containerType() { return adapter.containerType(); }
        @Override public Set<ResolvedReferenceTypeDeclaration> internalTypes() { return adapter.internalTypes(); }
        @Override public boolean isAssignableBy(ResolvedType type) { return adapter.isAssignableBy(type); }
        @Override public boolean isAssignableBy(ResolvedReferenceTypeDeclaration other) { return adapter.isAssignableBy(other); }
        @Override public boolean canBeAssignedTo(ResolvedReferenceTypeDeclaration other) { return adapter.canBeAssignedTo(other); }

        @Override
        protected ResolvedReferenceType object() {
            return new ReferenceTypeImpl(adapter.rootSolver.getSolvedJavaLangObject());
        }

        @Override
        public SymbolReference<ResolvedMethodDeclaration> solveMethod(
                String name, List<ResolvedType> argumentsTypes, boolean staticOnly) {
            return AsmSignatureResolver.solveMethod(name, argumentsTypes, staticOnly, adapter.rootSolver, this);
        }

        @Override
        public Optional<MethodUsage> solveMethodAsUsage(
                String name, List<ResolvedType> argumentsTypes, Context invokationContext, List<ResolvedType> typeParameters) {
            return AsmSignatureResolver.solveMethodAsUsage(name, argumentsTypes, adapter.rootSolver, invokationContext, typeParameters, this);
        }

        @Override
        public SymbolReference<? extends ResolvedValueDeclaration> solveSymbol(String name, TypeSolver typeSolver) {
            return adapter.solveSymbol(name, true);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            return o instanceof AsmClassDeclaration other && adapter.stub.binaryName().equals(other.adapter.stub.binaryName());
        }

        @Override public int hashCode() { return adapter.stub.binaryName().hashCode(); }
        @Override public String toString() { return "AsmClassDeclaration{" + getQualifiedName() + '}'; }
    }

    static final class AsmInterfaceDeclaration extends AbstractTypeDeclaration
            implements ResolvedInterfaceDeclaration, MethodResolutionCapability, MethodUsageResolutionCapability, SymbolResolutionCapability {
        private final Adapter adapter;

        AsmInterfaceDeclaration(AsmClassStub stub, TypeSolver rootSolver) {
            this.adapter = new Adapter(stub, rootSolver, this);
        }

        @Override public String getPackageName() { return adapter.stub.packageName(); }
        @Override public String getClassName() { return adapter.stub.className(); }
        @Override public String getQualifiedName() { return adapter.stub.qualifiedName(); }
        @Override public String getName() { return adapter.stub.simpleName(); }
        @Override public String getId() { return adapter.stub.qualifiedName(); }
        @Override public Optional<Node> toAst() { return Optional.empty(); }
        @Override public AccessSpecifier accessSpecifier() { return AsmSignatureResolver.accessSpecifier(adapter.stub.access()); }
        @Override public boolean hasDirectlyAnnotation(String qualifiedName) { return adapter.stub.hasDirectAnnotation(qualifiedName); }
        @Override public Set<ResolvedAnnotationDeclaration> getDeclaredAnnotations() { return adapter.getDeclaredAnnotations(); }
        @Override public List<ResolvedReferenceType> getInterfacesExtended() { return adapter.getInterfaces(false); }
        @Override public List<ResolvedReferenceType> getAncestors(boolean acceptIncompleteList) { return adapter.getAncestors(acceptIncompleteList); }
        @Override public Set<ResolvedMethodDeclaration> getDeclaredMethods() { return adapter.getDeclaredMethods(true); }
        @Override public List<ResolvedConstructorDeclaration> getConstructors() { return new ArrayList<>(); }
        @Override public List<ResolvedFieldDeclaration> getDeclaredFields() { return adapter.getDeclaredFields(); }
        @Override public List<ResolvedFieldDeclaration> getAllFields() { return adapter.getAllFields(); }
        @Override public List<ResolvedTypeParameterDeclaration> getTypeParameters() { return adapter.getTypeParameters(); }
        @Override public Optional<ResolvedReferenceTypeDeclaration> containerType() { return adapter.containerType(); }
        @Override public Set<ResolvedReferenceTypeDeclaration> internalTypes() { return adapter.internalTypes(); }
        @Override public boolean isAssignableBy(ResolvedType type) { return adapter.isAssignableBy(type); }
        @Override public boolean isAssignableBy(ResolvedReferenceTypeDeclaration other) { return adapter.isAssignableBy(other); }
        @Override public boolean canBeAssignedTo(ResolvedReferenceTypeDeclaration other) { return adapter.canBeAssignedTo(other); }

        @Override
        public SymbolReference<ResolvedMethodDeclaration> solveMethod(
                String name, List<ResolvedType> argumentsTypes, boolean staticOnly) {
            return AsmSignatureResolver.solveMethod(name, argumentsTypes, staticOnly, adapter.rootSolver, this);
        }

        @Override
        public Optional<MethodUsage> solveMethodAsUsage(
                String name, List<ResolvedType> argumentsTypes, Context invokationContext, List<ResolvedType> typeParameters) {
            return AsmSignatureResolver.solveMethodAsUsage(name, argumentsTypes, adapter.rootSolver, invokationContext, typeParameters, this);
        }

        @Override
        public SymbolReference<? extends ResolvedValueDeclaration> solveSymbol(String name, TypeSolver typeSolver) {
            return adapter.solveSymbol(name, false);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            return o instanceof AsmInterfaceDeclaration other && adapter.stub.binaryName().equals(other.adapter.stub.binaryName());
        }

        @Override public int hashCode() { return adapter.stub.binaryName().hashCode(); }
        @Override public String toString() { return "AsmInterfaceDeclaration{" + getQualifiedName() + '}'; }
    }

    static final class AsmEnumDeclaration extends AbstractTypeDeclaration
            implements ResolvedEnumDeclaration, MethodResolutionCapability, MethodUsageResolutionCapability, SymbolResolutionCapability {
        private final Adapter adapter;
        private List<ResolvedEnumConstantDeclaration> cachedConstants;

        AsmEnumDeclaration(AsmClassStub stub, TypeSolver rootSolver) {
            this.adapter = new Adapter(stub, rootSolver, this);
        }

        @Override public String getPackageName() { return adapter.stub.packageName(); }
        @Override public String getClassName() { return adapter.stub.className(); }
        @Override public String getQualifiedName() { return adapter.stub.qualifiedName(); }
        @Override public String getName() { return adapter.stub.simpleName(); }
        @Override public String getId() { return adapter.stub.qualifiedName(); }
        @Override public Optional<Node> toAst() { return Optional.empty(); }
        @Override public AccessSpecifier accessSpecifier() { return AsmSignatureResolver.accessSpecifier(adapter.stub.access()); }
        @Override public boolean hasDirectlyAnnotation(String qualifiedName) { return adapter.stub.hasDirectAnnotation(qualifiedName); }
        @Override public Set<ResolvedAnnotationDeclaration> getDeclaredAnnotations() { return adapter.getDeclaredAnnotations(); }
        @Override public List<ResolvedReferenceType> getAncestors(boolean acceptIncompleteList) { return adapter.getAncestors(acceptIncompleteList); }
        @Override public Set<ResolvedMethodDeclaration> getDeclaredMethods() { return adapter.getDeclaredMethods(true); }
        @Override public List<ResolvedConstructorDeclaration> getConstructors() { return adapter.getConstructors(); }
        @Override public List<ResolvedFieldDeclaration> getDeclaredFields() { return adapter.getDeclaredFields(); }
        @Override public List<ResolvedFieldDeclaration> getAllFields() { return adapter.getAllFields(); }
        @Override public List<ResolvedTypeParameterDeclaration> getTypeParameters() { return new ArrayList<>(); }
        @Override public Optional<ResolvedReferenceTypeDeclaration> containerType() { return adapter.containerType(); }
        @Override public Set<ResolvedReferenceTypeDeclaration> internalTypes() { return adapter.internalTypes(); }
        @Override public boolean isAssignableBy(ResolvedType type) { return adapter.isAssignableBy(type); }
        @Override public boolean isAssignableBy(ResolvedReferenceTypeDeclaration other) { return adapter.isAssignableBy(other); }

        @Override
        public boolean canBeAssignedTo(ResolvedReferenceTypeDeclaration other) {
            if (other == null) return false;
            String qn = other.getQualifiedName();
            if (qn.equals(getQualifiedName())
                    || qn.equals(JAVA_LANG_ENUM)
                    || qn.equals(JAVA_LANG_COMPARABLE)
                    || qn.equals(JAVA_IO_SERIALIZABLE)
                    || other.isJavaLangObject()) {
                return true;
            }
            for (ResolvedReferenceType iface : adapter.getInterfaces(true)) {
                if (iface.getTypeDeclaration().isPresent()
                        && iface.getTypeDeclaration().orElseThrow().canBeAssignedTo(other)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public List<ResolvedEnumConstantDeclaration> getEnumConstants() {
            if (cachedConstants != null) return cachedConstants;
            var list = new ArrayList<ResolvedEnumConstantDeclaration>();
            for (var f : adapter.stub.fields()) {
                if ((f.access() & Opcodes.ACC_ENUM) != 0) {
                    list.add(new AsmEnumConstantDeclaration(f, this, adapter.rootSolver));
                }
            }
            cachedConstants = Collections.unmodifiableList(list);
            return cachedConstants;
        }

        @Override
        public SymbolReference<ResolvedMethodDeclaration> solveMethod(
                String name, List<ResolvedType> argumentsTypes, boolean staticOnly) {
            return AsmSignatureResolver.solveMethod(name, argumentsTypes, staticOnly, adapter.rootSolver, this);
        }

        @Override
        public Optional<MethodUsage> solveMethodAsUsage(
                String name, List<ResolvedType> argumentsTypes, Context invokationContext, List<ResolvedType> typeParameters) {
            return AsmSignatureResolver.solveMethodAsUsage(name, argumentsTypes, adapter.rootSolver, invokationContext, typeParameters, this);
        }

        @Override
        public SymbolReference<? extends ResolvedValueDeclaration> solveSymbol(String name, TypeSolver typeSolver) {
            return adapter.solveSymbol(name, false);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            return o instanceof AsmEnumDeclaration other && adapter.stub.binaryName().equals(other.adapter.stub.binaryName());
        }

        @Override public int hashCode() { return adapter.stub.binaryName().hashCode(); }
        @Override public String toString() { return "AsmEnumDeclaration{" + getQualifiedName() + '}'; }
    }

    public static final class AsmRecordDeclaration extends AbstractTypeDeclaration
            implements ResolvedRecordDeclaration, MethodResolutionCapability, MethodUsageResolutionCapability, SymbolResolutionCapability {
        private final Adapter adapter;

        AsmRecordDeclaration(AsmClassStub stub, TypeSolver rootSolver) {
            this.adapter = new Adapter(stub, rootSolver, this);
        }

        public List<AsmClassStub.RecordComponentStub> recordComponents() {
            return adapter.stub.recordComponents();
        }

        public ResolvedType resolveRecordComponentType(int index) {
            var comp = adapter.stub.recordComponents().get(index);
            String sig = comp.signature() != null && !comp.signature().isEmpty()
                    ? comp.signature()
                    : comp.descriptor();
            return AsmSignatureResolver.resolveDescriptorOrSignature(sig, adapter.rootSolver, this);
        }

        @Override public String getPackageName() { return adapter.stub.packageName(); }
        @Override public String getClassName() { return adapter.stub.className(); }
        @Override public String getQualifiedName() { return adapter.stub.qualifiedName(); }
        @Override public String getName() { return adapter.stub.simpleName(); }
        @Override public String getId() { return adapter.stub.qualifiedName(); }
        @Override public Optional<Node> toAst() { return Optional.empty(); }
        @Override public AccessSpecifier accessSpecifier() { return AsmSignatureResolver.accessSpecifier(adapter.stub.access()); }
        @Override public boolean hasDirectlyAnnotation(String qualifiedName) { return adapter.stub.hasDirectAnnotation(qualifiedName); }
        @Override public Set<ResolvedAnnotationDeclaration> getDeclaredAnnotations() { return adapter.getDeclaredAnnotations(); }
        @Override public Optional<ResolvedReferenceType> getSuperClass() { return adapter.getSuperClass(); }
        @Override public List<ResolvedReferenceType> getInterfaces() { return adapter.getInterfaces(false); }
        @Override public List<ResolvedReferenceType> getAllSuperClasses() { return getSuperClass().map(List::of).orElseGet(List::of); }
        @Override public List<ResolvedReferenceType> getAllInterfaces() {
            var all = new ArrayList<ResolvedReferenceType>();
            for (var iface : getInterfaces()) {
                all.add(iface);
                all.addAll(iface.getAllInterfacesAncestors());
            }
            return all;
        }
        @Override public List<ResolvedReferenceType> getAncestors(boolean acceptIncompleteList) { return adapter.getAncestors(acceptIncompleteList); }
        @Override public Set<ResolvedMethodDeclaration> getDeclaredMethods() { return adapter.getDeclaredMethods(true); }
        @Override public List<ResolvedConstructorDeclaration> getConstructors() { return adapter.getConstructors(); }
        @Override public List<ResolvedFieldDeclaration> getDeclaredFields() { return adapter.getDeclaredFields(); }
        @Override public List<ResolvedFieldDeclaration> getAllFields() { return adapter.getAllFields(); }
        @Override public List<ResolvedTypeParameterDeclaration> getTypeParameters() { return adapter.getTypeParameters(); }
        @Override public Optional<ResolvedReferenceTypeDeclaration> containerType() { return adapter.containerType(); }
        @Override public Set<ResolvedReferenceTypeDeclaration> internalTypes() { return adapter.internalTypes(); }
        @Override public boolean isAssignableBy(ResolvedType type) { return adapter.isAssignableBy(type); }
        @Override public boolean isAssignableBy(ResolvedReferenceTypeDeclaration other) { return adapter.isAssignableBy(other); }
        @Override public boolean canBeAssignedTo(ResolvedReferenceTypeDeclaration other) { return adapter.canBeAssignedTo(other); }

        @Override
        public SymbolReference<ResolvedMethodDeclaration> solveMethod(
                String name, List<ResolvedType> argumentsTypes, boolean staticOnly) {
            return AsmSignatureResolver.solveMethod(name, argumentsTypes, staticOnly, adapter.rootSolver, this);
        }

        @Override
        public Optional<MethodUsage> solveMethodAsUsage(
                String name, List<ResolvedType> argumentsTypes, Context invokationContext, List<ResolvedType> typeParameters) {
            return AsmSignatureResolver.solveMethodAsUsage(name, argumentsTypes, adapter.rootSolver, invokationContext, typeParameters, this);
        }

        @Override
        public SymbolReference<? extends ResolvedValueDeclaration> solveSymbol(String name, TypeSolver typeSolver) {
            return adapter.solveSymbol(name, true);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            return o instanceof AsmRecordDeclaration other && adapter.stub.binaryName().equals(other.adapter.stub.binaryName());
        }

        @Override public int hashCode() { return adapter.stub.binaryName().hashCode(); }
        @Override public String toString() { return "AsmRecordDeclaration{" + getQualifiedName() + '}'; }
    }

    static final class AsmAnnotationDeclaration extends AbstractTypeDeclaration
            implements ResolvedAnnotationDeclaration {
        private final Adapter adapter;
        private List<ResolvedAnnotationMemberDeclaration> cachedMembers;

        AsmAnnotationDeclaration(AsmClassStub stub, TypeSolver rootSolver) {
            this.adapter = new Adapter(stub, rootSolver, this);
        }

        @Override public String getPackageName() { return adapter.stub.packageName(); }
        @Override public String getQualifiedName() { return adapter.stub.qualifiedName(); }
        @Override public String getClassName() { return adapter.stub.className(); }
        @Override public String getName() { return adapter.stub.simpleName(); }
        @Override public String getId() { return adapter.stub.qualifiedName(); }
        @Override public Optional<Node> toAst() { return Optional.empty(); }
        @Override public boolean hasDirectlyAnnotation(String qualifiedName) { return adapter.stub.hasDirectAnnotation(qualifiedName); }
        @Override public Set<ResolvedAnnotationDeclaration> getDeclaredAnnotations() { return adapter.getDeclaredAnnotations(); }
        @Override public List<ResolvedReferenceType> getAncestors(boolean acceptIncompleteList) { return adapter.getAncestors(acceptIncompleteList); }
        @Override public List<ResolvedFieldDeclaration> getDeclaredFields() { return adapter.getDeclaredFields(); }
        @Override public List<ResolvedFieldDeclaration> getAllFields() { return adapter.getAllFields(); }
        @Override public List<ResolvedConstructorDeclaration> getConstructors() { return new ArrayList<>(); }
        @Override public List<ResolvedTypeParameterDeclaration> getTypeParameters() { return new ArrayList<>(); }
        @Override public Set<ResolvedReferenceTypeDeclaration> internalTypes() { return adapter.internalTypes(); }
        @Override public boolean isInheritable() { return adapter.stub.hasDirectAnnotation("java.lang.annotation.Inherited"); }

        @Override
        public List<ResolvedAnnotationMemberDeclaration> getAnnotationMembers() {
            if (cachedMembers != null) return cachedMembers;
            var list = new ArrayList<ResolvedAnnotationMemberDeclaration>();
            for (var m : adapter.stub.methods()) {
                if (!m.isConstructor() && !m.isStaticInitializer()) {
                    list.add(new AsmAnnotationMemberDeclaration(m, adapter.rootSolver));
                }
            }
            cachedMembers = Collections.unmodifiableList(list);
            return cachedMembers;
        }

        @Override
        public Set<ResolvedMethodDeclaration> getDeclaredMethods() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isAssignableBy(ResolvedType type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isAssignableBy(ResolvedReferenceTypeDeclaration other) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ResolvedReferenceTypeDeclaration> containerType() {
            return adapter.containerType();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            return o instanceof AsmAnnotationDeclaration other && adapter.stub.binaryName().equals(other.adapter.stub.binaryName());
        }

        @Override public int hashCode() { return adapter.stub.binaryName().hashCode(); }
        @Override public String toString() { return "AsmAnnotationDeclaration{" + getQualifiedName() + '}'; }
    }

    private static final class MethodLikeHelper {
        final AsmClassStub.MethodStub methodStub;
        final ResolvedReferenceTypeDeclaration declaringType;
        final AsmClassStub ownerStub;
        final TypeSolver rootSolver;
        final ResolvedMethodLikeDeclaration self;
        private AsmSignatureResolver.ParsedMethodSignature parsedSig;
        private boolean sigParsed;
        private List<String> rawParamDescriptors;
        private List<ResolvedTypeParameterDeclaration> cachedTypeParams;

        MethodLikeHelper(
                AsmClassStub.MethodStub methodStub,
                ResolvedReferenceTypeDeclaration declaringType,
                AsmClassStub ownerStub,
                TypeSolver rootSolver,
                ResolvedMethodLikeDeclaration self) {
            this.methodStub = methodStub;
            this.declaringType = declaringType;
            this.ownerStub = ownerStub;
            this.rootSolver = rootSolver;
            this.self = self;
        }

        private AsmSignatureResolver.ParsedMethodSignature methodSig() {
            if (!sigParsed) {
                sigParsed = true;
                if (methodStub.signature() != null && !methodStub.signature().isEmpty()) {
                    try {
                        parsedSig = AsmSignatureResolver.parseMethodSignature(methodStub.signature());
                    } catch (RuntimeException ignored) {
                        parsedSig = null;
                    }
                }
            }
            return parsedSig;
        }

        private List<String> rawParams() {
            if (rawParamDescriptors == null) {
                rawParamDescriptors = AsmSignatureResolver.parseMethodDescriptorParams(methodStub.descriptor());
            }
            return rawParamDescriptors;
        }

        private int syntheticLeadingParams() {
            if (!methodStub.isConstructor()) return 0;
            List<String> raw = rawParams();
            if (ownerStub.kind() == AsmClassStub.Kind.ENUM
                    && raw.size() >= 2
                    && "Ljava/lang/String;".equals(raw.get(0))
                    && "I".equals(raw.get(1))) {
                return 2;
            }
            if (ownerStub.outerInternalName() != null
                    && (ownerStub.access() & Opcodes.ACC_STATIC) == 0
                    && !raw.isEmpty()
                    && ("L" + ownerStub.outerInternalName() + ";").equals(raw.get(0))) {
                return 1;
            }
            return 0;
        }

        int getNumberOfParams() {
            return Math.max(0, rawParams().size() - syntheticLeadingParams());
        }

        ResolvedParameterDeclaration getParam(int i) {
            List<String> raw = rawParams();
            int offset = syntheticLeadingParams();
            int logicalCount = Math.max(0, raw.size() - offset);
            if (i < 0 || i >= logicalCount) {
                throw new IndexOutOfBoundsException("Parameter index " + i + " out of bounds for " + logicalCount);
            }
            int rawIndex = i + offset;
            boolean variadic = methodStub.isVarArgs() && rawIndex == raw.size() - 1;
            String pName = null;
            if (methodStub.parameterNames().size() == raw.size()) {
                pName = methodStub.parameterNames().get(rawIndex);
            } else if (i < methodStub.parameterNames().size()) {
                pName = methodStub.parameterNames().get(i);
            }
            String sigToResolve = raw.get(rawIndex);
            var sig = methodSig();
            if (sig != null && !sig.paramSignatures().isEmpty()) {
                if (sig.paramSignatures().size() == logicalCount) {
                    sigToResolve = sig.paramSignatures().get(i);
                } else if (sig.paramSignatures().size() == raw.size()) {
                    sigToResolve = sig.paramSignatures().get(rawIndex);
                } else {
                    int diff = raw.size() - sig.paramSignatures().size();
                    if (diff > 0 && rawIndex >= diff) {
                        sigToResolve = sig.paramSignatures().get(rawIndex - diff);
                    }
                }
            }
            ResolvedType type = AsmSignatureResolver.resolveDescriptorOrSignature(sigToResolve, rootSolver, self);
            return new AsmParameterDeclaration(type, variadic, pName);
        }

        ResolvedType getReturnType() {
            var sig = methodSig();
            String retSig = sig != null && sig.returnSignature() != null
                    ? sig.returnSignature()
                    : AsmSignatureResolver.parseMethodDescriptorReturn(methodStub.descriptor());
            return AsmSignatureResolver.resolveDescriptorOrSignature(retSig, rootSolver, self);
        }

        List<ResolvedTypeParameterDeclaration> getTypeParameters() {
            if (cachedTypeParams == null) {
                var sig = methodSig();
                if (sig == null || sig.typeParams().isEmpty()) {
                    cachedTypeParams = List.of();
                } else {
                    var list = new ArrayList<ResolvedTypeParameterDeclaration>(sig.typeParams().size());
                    for (var tp : sig.typeParams()) {
                        list.add(new AsmTypeParameter(tp, self, rootSolver));
                    }
                    cachedTypeParams = List.copyOf(list);
                }
            }
            return new ArrayList<>(cachedTypeParams);
        }

        int getNumberOfSpecifiedExceptions() {
            var sig = methodSig();
            if (sig != null && !sig.exceptionSignatures().isEmpty()) {
                return sig.exceptionSignatures().size();
            }
            return methodStub.exceptions().size();
        }

        ResolvedType getSpecifiedException(int index) {
            var sig = methodSig();
            if (sig != null && !sig.exceptionSignatures().isEmpty()) {
                return AsmSignatureResolver.resolveDescriptorOrSignature(
                        sig.exceptionSignatures().get(index), rootSolver, self);
            }
            return AsmSignatureResolver.resolveInternalClass(methodStub.exceptions().get(index), rootSolver);
        }
    }

    static final class AsmMethodDeclaration
            implements ResolvedMethodDeclaration, TypeVariableResolutionCapability {
        private final MethodLikeHelper helper;

        AsmMethodDeclaration(
                AsmClassStub.MethodStub methodStub,
                ResolvedReferenceTypeDeclaration declaringType,
                AsmClassStub ownerStub,
                TypeSolver rootSolver) {
            this.helper = new MethodLikeHelper(methodStub, declaringType, ownerStub, rootSolver, this);
        }

        @Override public ResolvedReferenceTypeDeclaration declaringType() { return helper.declaringType; }
        @Override public String getName() { return helper.methodStub.name(); }
        @Override public ResolvedType getReturnType() { return helper.getReturnType(); }
        @Override public boolean isAbstract() { return helper.methodStub.isAbstract(); }
        @Override public boolean isDefaultMethod() { return helper.declaringType.isInterface() && !isAbstract() && !isStatic(); }
        @Override public boolean isStatic() { return helper.methodStub.isStatic(); }
        @Override public AccessSpecifier accessSpecifier() { return AsmSignatureResolver.accessSpecifier(helper.methodStub.access()); }
        @Override public int getNumberOfParams() { return helper.getNumberOfParams(); }
        @Override public ResolvedParameterDeclaration getParam(int i) { return helper.getParam(i); }
        @Override public List<ResolvedTypeParameterDeclaration> getTypeParameters() { return helper.getTypeParameters(); }
        @Override public int getNumberOfSpecifiedExceptions() { return helper.getNumberOfSpecifiedExceptions(); }
        @Override public ResolvedType getSpecifiedException(int index) { return helper.getSpecifiedException(index); }
        @Override public String toDescriptor() { return helper.methodStub.descriptor(); }

        @Override
        public MethodUsage resolveTypeVariables(Context context, List<ResolvedType> parameterTypes) {
            return new com.github.javaparser.symbolsolver.declarations.common.MethodDeclarationCommonLogic(
                    this, helper.rootSolver).resolveTypeVariables(context, parameterTypes);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof AsmMethodDeclaration other)) return false;
            return helper.ownerStub.binaryName().equals(other.helper.ownerStub.binaryName())
                    && helper.methodStub.name().equals(other.helper.methodStub.name())
                    && helper.methodStub.descriptor().equals(other.helper.methodStub.descriptor());
        }

        @Override
        public int hashCode() {
            return Objects.hash(helper.ownerStub.binaryName(), helper.methodStub.name(), helper.methodStub.descriptor());
        }

        @Override
        public String toString() {
            return "AsmMethodDeclaration{" + helper.ownerStub.qualifiedName() + "#" + getName() + toDescriptor() + '}';
        }
    }

    static final class AsmConstructorDeclaration implements ResolvedConstructorDeclaration {
        private final MethodLikeHelper helper;

        AsmConstructorDeclaration(
                AsmClassStub.MethodStub methodStub,
                ResolvedReferenceTypeDeclaration declaringType,
                AsmClassStub ownerStub,
                TypeSolver rootSolver) {
            this.helper = new MethodLikeHelper(methodStub, declaringType, ownerStub, rootSolver, this);
        }

        @Override public ResolvedReferenceTypeDeclaration declaringType() { return helper.declaringType; }
        @Override
        public String getName() {
            String bin = helper.ownerStub.binaryName();
            int lastDot = bin.lastIndexOf('.');
            return lastDot >= 0 ? bin.substring(lastDot + 1) : bin;
        }
        @Override public AccessSpecifier accessSpecifier() { return AsmSignatureResolver.accessSpecifier(helper.methodStub.access()); }
        @Override public int getNumberOfParams() { return helper.getNumberOfParams(); }
        @Override public ResolvedParameterDeclaration getParam(int i) { return helper.getParam(i); }
        @Override public List<ResolvedTypeParameterDeclaration> getTypeParameters() { return helper.getTypeParameters(); }
        @Override public int getNumberOfSpecifiedExceptions() { return helper.getNumberOfSpecifiedExceptions(); }
        @Override public ResolvedType getSpecifiedException(int index) { return helper.getSpecifiedException(index); }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof AsmConstructorDeclaration other)) return false;
            return helper.ownerStub.binaryName().equals(other.helper.ownerStub.binaryName())
                    && helper.methodStub.descriptor().equals(other.helper.methodStub.descriptor());
        }

        @Override
        public int hashCode() {
            return Objects.hash(helper.ownerStub.binaryName(), helper.methodStub.descriptor());
        }
    }

    static final class AsmFieldDeclaration implements ResolvedFieldDeclaration {
        private final AsmClassStub.FieldStub fieldStub;
        private final ResolvedReferenceTypeDeclaration declaringType;
        private final TypeSolver rootSolver;

        AsmFieldDeclaration(
                AsmClassStub.FieldStub fieldStub,
                ResolvedReferenceTypeDeclaration declaringType,
                TypeSolver rootSolver) {
            this.fieldStub = fieldStub;
            this.declaringType = declaringType;
            this.rootSolver = rootSolver;
        }

        @Override public String getName() { return fieldStub.name(); }
        @Override public boolean isStatic() { return (fieldStub.access() & Opcodes.ACC_STATIC) != 0; }
        @Override public boolean isVolatile() { return (fieldStub.access() & Opcodes.ACC_VOLATILE) != 0; }
        @Override public AccessSpecifier accessSpecifier() { return AsmSignatureResolver.accessSpecifier(fieldStub.access()); }
        @Override public ResolvedTypeDeclaration declaringType() { return declaringType; }

        @Override
        public ResolvedType getType() {
            String sig = fieldStub.signature() != null && !fieldStub.signature().isEmpty()
                    ? fieldStub.signature()
                    : fieldStub.descriptor();
            return AsmSignatureResolver.resolveDescriptorOrSignature(sig, rootSolver, declaringType);
        }
    }

    static final class AsmParameterDeclaration implements ResolvedParameterDeclaration {
        private final ResolvedType type;
        private final boolean variadic;
        private final String name;

        AsmParameterDeclaration(ResolvedType type, boolean variadic, String name) {
            this.type = type;
            this.variadic = variadic;
            this.name = name;
        }

        @Override public boolean hasName() { return name != null && !name.isEmpty(); }
        @Override public String getName() { return name; }
        @Override public boolean isVariadic() { return variadic; }
        @Override public ResolvedType getType() { return type; }
    }

    static final class AsmTypeParameter implements ResolvedTypeParameterDeclaration {
        private final AsmSignatureResolver.ParsedTypeParam parsed;
        private final ResolvedTypeParametrizable container;
        private final TypeSolver rootSolver;

        AsmTypeParameter(
                AsmSignatureResolver.ParsedTypeParam parsed,
                ResolvedTypeParametrizable container,
                TypeSolver rootSolver) {
            this.parsed = parsed;
            this.container = container;
            this.rootSolver = rootSolver;
        }

        @Override public String getName() { return parsed.name(); }

        @Override
        public String getContainerQualifiedName() {
            if (container instanceof ResolvedReferenceTypeDeclaration ref) return ref.getQualifiedName();
            if (container instanceof ResolvedMethodLikeDeclaration m) return m.getQualifiedName();
            throw new UnsupportedOperationException();
        }

        @Override
        public String getContainerId() {
            return getContainerQualifiedName();
        }

        @Override public ResolvedTypeParametrizable getContainer() { return container; }

        @Override
        public List<Bound> getBounds() {
            if (parsed.boundSignatures().isEmpty()) return List.of();
            var list = new ArrayList<Bound>(parsed.boundSignatures().size());
            for (String b : parsed.boundSignatures()) {
                list.add(Bound.extendsBound(AsmSignatureResolver.resolveDescriptorOrSignature(b, rootSolver, container)));
            }
            return list;
        }

        @Override
        public ResolvedReferenceType object() {
            return new ReferenceTypeImpl(rootSolver.getSolvedJavaLangObject());
        }

        @Override
        public Optional<ResolvedReferenceTypeDeclaration> containerType() {
            if (container instanceof ResolvedReferenceTypeDeclaration ref) return Optional.of(ref);
            return Optional.empty();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ResolvedTypeParameterDeclaration other)) return false;
            if (!getQualifiedName().equals(other.getQualifiedName())) return false;
            if (declaredOnType() == other.declaredOnType()) return true;
            return declaredOnMethod() == other.declaredOnMethod();
        }

        @Override
        public int hashCode() {
            return Objects.hash(getQualifiedName(), declaredOnType(), declaredOnMethod());
        }
    }

    static final class AsmAnnotationMemberDeclaration implements ResolvedAnnotationMemberDeclaration {
        private final AsmClassStub.MethodStub methodStub;
        private final TypeSolver rootSolver;

        AsmAnnotationMemberDeclaration(AsmClassStub.MethodStub methodStub, TypeSolver rootSolver) {
            this.methodStub = methodStub;
            this.rootSolver = rootSolver;
        }

        @Override public String getName() { return methodStub.name(); }

        @Override
        public ResolvedType getType() {
            String ret = AsmSignatureResolver.parseMethodDescriptorReturn(methodStub.descriptor());
            return AsmSignatureResolver.resolveDescriptorOrSignature(ret, rootSolver, null);
        }

        @Override
        public Expression getDefaultValue() {
            return toExpression(methodStub.annotationDefault());
        }

        private static Expression toExpression(Object val) {
            if (val == null) return null;
            if (val instanceof Boolean b) return new BooleanLiteralExpr(b);
            if (val instanceof Character c) return new CharLiteralExpr(c);
            if (val instanceof Integer i) return new IntegerLiteralExpr(Integer.toString(i));
            if (val instanceof Byte b) return new IntegerLiteralExpr(Byte.toString(b));
            if (val instanceof Short s) return new IntegerLiteralExpr(Short.toString(s));
            if (val instanceof Long l) return new LongLiteralExpr(l + "L");
            if (val instanceof Float f) return new DoubleLiteralExpr(f + "f");
            if (val instanceof Double d) return new DoubleLiteralExpr(Double.toString(d));
            if (val instanceof String s) return new StringLiteralExpr(s);
            if (val instanceof AsmClassStub.EnumConstantDefault e) {
                String qn = AsmClassStub.descriptorToQualifiedName(e.enumDescriptor());
                return new FieldAccessExpr(new NameExpr(qn), e.constantName());
            }
            if (val instanceof AsmClassStub.ClassDefault cd) {
                var astType = descriptorToAstType(cd.descriptor());
                return astType == null ? null : new ClassExpr(astType);
            }
            if (val instanceof AsmClassStub.AnnotationDefault ad) {
                String qn = AsmClassStub.descriptorToQualifiedName(ad.descriptor());
                if (qn.isEmpty()) return null;
                var nameNode = com.github.javaparser.StaticJavaParser.parseName(qn);
                if (ad.values().isEmpty()) {
                    return new MarkerAnnotationExpr(nameNode);
                }
                if (ad.values().size() == 1 && ad.values().containsKey("value")) {
                    Expression v = toExpression(ad.values().get("value"));
                    if (v != null) return new SingleMemberAnnotationExpr(nameNode, v);
                }
                var pairs = new com.github.javaparser.ast.NodeList<MemberValuePair>();
                for (var entry : ad.values().entrySet()) {
                    Expression v = toExpression(entry.getValue());
                    if (v != null) {
                        pairs.add(new MemberValuePair(entry.getKey(), v));
                    }
                }
                return new NormalAnnotationExpr(nameNode, pairs);
            }
            if (val instanceof List<?> items) {
                var nodeList = new com.github.javaparser.ast.NodeList<Expression>();
                for (Object item : items) {
                    Expression expr = toExpression(item);
                    if (expr != null) nodeList.add(expr);
                }
                return new ArrayInitializerExpr(nodeList);
            }
            return null;
        }

        private static com.github.javaparser.ast.type.Type descriptorToAstType(String desc) {
            if (desc == null || desc.isEmpty()) return null;
            int dims = 0;
            while (dims < desc.length() && desc.charAt(dims) == '[') dims++;
            if (dims >= desc.length()) return null;
            String elem = desc.substring(dims);
            com.github.javaparser.ast.type.Type base;
            if (elem.length() == 1) {
                base = switch (elem.charAt(0)) {
                    case 'V' -> new com.github.javaparser.ast.type.VoidType();
                    case 'Z' -> com.github.javaparser.ast.type.PrimitiveType.booleanType();
                    case 'B' -> com.github.javaparser.ast.type.PrimitiveType.byteType();
                    case 'C' -> com.github.javaparser.ast.type.PrimitiveType.charType();
                    case 'S' -> com.github.javaparser.ast.type.PrimitiveType.shortType();
                    case 'I' -> com.github.javaparser.ast.type.PrimitiveType.intType();
                    case 'J' -> com.github.javaparser.ast.type.PrimitiveType.longType();
                    case 'F' -> com.github.javaparser.ast.type.PrimitiveType.floatType();
                    case 'D' -> com.github.javaparser.ast.type.PrimitiveType.doubleType();
                    default -> null;
                };
            } else if (elem.startsWith("L") && elem.endsWith(";")) {
                String qn = AsmClassStub.descriptorToQualifiedName(elem);
                base = com.github.javaparser.StaticJavaParser.parseClassOrInterfaceType(qn);
            } else {
                base = null;
            }
            if (base == null) return null;
            com.github.javaparser.ast.type.Type current = base;
            for (int d = 0; d < dims; d++) {
                current = new com.github.javaparser.ast.type.ArrayType(current);
            }
            return current;
        }
    }

    static final class AsmEnumConstantDeclaration implements ResolvedEnumConstantDeclaration {
        private final AsmClassStub.FieldStub fieldStub;
        private final AsmEnumDeclaration enumDecl;
        private final TypeSolver rootSolver;

        AsmEnumConstantDeclaration(
                AsmClassStub.FieldStub fieldStub, AsmEnumDeclaration enumDecl, TypeSolver rootSolver) {
            this.fieldStub = fieldStub;
            this.enumDecl = enumDecl;
            this.rootSolver = rootSolver;
        }

        @Override public String getName() { return fieldStub.name(); }
        @Override public ResolvedType getType() { return new ReferenceTypeImpl(enumDecl); }
    }
}
