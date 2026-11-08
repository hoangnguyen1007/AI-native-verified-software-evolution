package com.evolution.analysis.javaparser;

import com.github.javaparser.ast.AccessSpecifier;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.Context;
import com.github.javaparser.resolution.MethodUsage;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.*;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.resolution.model.typesystem.ReferenceTypeImpl;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import java.util.*;

/**
 * In SOURCE_RECOVERY mode, resolves unambiguous single-type explicit imports (and Lombok annotations)
 * that are not present in project sources, reactor sources, JDK platform, or supplied dependency JARs.
 */
final class ExplicitImportTypeSolver implements TypeSolver {
    private static final Set<String> LOMBOK_TYPES = Set.of(
            "lombok.Data", "lombok.Value", "lombok.Builder", "lombok.Getter", "lombok.Setter",
            "lombok.With", "lombok.ToString", "lombok.EqualsAndHashCode",
            "lombok.NoArgsConstructor", "lombok.RequiredArgsConstructor", "lombok.AllArgsConstructor",
            "lombok.NonNull", "lombok.AccessLevel", "lombok.CustomLog", "lombok.SneakyThrows", "lombok.Synchronized", "lombok.Cleanup",
            "lombok.experimental.Accessors", "lombok.experimental.UtilityClass", "lombok.experimental.NonFinal",
            "lombok.experimental.SuperBuilder", "lombok.experimental.FieldDefaults", "lombok.experimental.Wither",
            "lombok.extern.slf4j.Slf4j", "lombok.extern.slf4j.XSlf4j",
            "lombok.extern.log4j.Log4j2", "lombok.extern.log4j.Log4j",
            "lombok.extern.java.Log", "lombok.extern.apachecommons.CommonsLog",
            "lombok.extern.jbosslog.JBossLog", "lombok.extern.flogger.Flogger");

    private TypeSolver parent;
    private final Map<String, ExplicitImportTypeDeclaration> declarations = new TreeMap<>();
    private final Map<String, Integer> observedMaxTypeArgs = new HashMap<>();
    private final Set<String> observedInterfaceHints = new HashSet<>();
    private final Set<String> observedAnnotationHints = new HashSet<>();
    private final Map<String, String> observedSimpleToFqn = new LinkedHashMap<>();

    @Override
    public TypeSolver getParent() {
        return parent;
    }

    @Override
    public void setParent(TypeSolver parent) {
        this.parent = parent;
    }

    boolean isRecoveredType(String qualifiedName) {
        return knowsType(qualifiedName);
    }

    boolean knowsType(String qualifiedName) {
        if (declarations.containsKey(qualifiedName)) return true;
        int dot = qualifiedName.lastIndexOf('.');
        while (dot > 0) {
            String prefix = qualifiedName.substring(0, dot);
            if (declarations.containsKey(prefix)) return true;
            dot = prefix.lastIndexOf('.');
        }
        return false;
    }

    Optional<ExplicitImportTypeDeclaration> resolveStaticImportOwner(CompilationUnit unit, String memberName) {
        if (unit == null) return Optional.empty();
        for (ImportDeclaration imp : unit.getImports()) {
            if (!imp.isStatic() || imp.isAsterisk()) continue;
            String fqn = imp.getNameAsString();
            int dot = fqn.lastIndexOf('.');
            if (dot <= 0) continue;
            String member = fqn.substring(dot + 1);
            if (!member.equals(memberName)) continue;
            String ownerFqn = fqn.substring(0, dot);
            var ref = tryToSolveType(ownerFqn);
            if (ref.isSolved() && ref.getCorrespondingDeclaration() instanceof ExplicitImportTypeDeclaration decl) {
                return Optional.of(decl);
            }
        }
        return Optional.empty();
    }

    void observeUnit(CompilationUnit unit) {
        Map<String, String> localSimpleToFqn = new HashMap<>();
        for (ImportDeclaration imp : unit.getImports()) {
            if (imp.isAsterisk()) continue;
            String fqn = imp.getNameAsString();
            int dot = fqn.lastIndexOf('.');
            if (dot <= 0) continue;
            String pkg = fqn.substring(0, dot);
            if (pkg.equals("other")) continue;
            if (imp.isStatic()) {
                String ownerType = pkg;
                int ownerDot = ownerType.lastIndexOf('.');
                if (ownerDot <= 0) continue;
                String simple = ownerType.substring(ownerDot + 1);
                localSimpleToFqn.put(simple, ownerType);
                observedSimpleToFqn.put(simple, ownerType);
            } else {
                String simple = fqn.substring(dot + 1);
                localSimpleToFqn.put(simple, fqn);
                observedSimpleToFqn.put(simple, fqn);
            }
        }
        for (ClassOrInterfaceType type : unit.findAll(ClassOrInterfaceType.class)) {
            String simple = type.getNameAsString();
            String fqn = localSimpleToFqn.getOrDefault(simple, type.getNameWithScope());
            if (type.getTypeArguments().isPresent()) {
                int count = type.getTypeArguments().orElseThrow().size();
                observedMaxTypeArgs.merge(fqn, count, Math::max);
                observedMaxTypeArgs.merge(simple, count, Math::max);
            }
        }
        for (ClassOrInterfaceDeclaration decl : unit.findAll(ClassOrInterfaceDeclaration.class)) {
            for (ClassOrInterfaceType impl : decl.getImplementedTypes()) {
                String fqn = localSimpleToFqn.getOrDefault(impl.getNameAsString(), impl.getNameWithScope());
                observedInterfaceHints.add(fqn);
            }
        }
        for (AnnotationExpr ann : unit.findAll(AnnotationExpr.class)) {
            String simple = ann.getName().getIdentifier();
            String fqn = localSimpleToFqn.getOrDefault(simple, ann.getNameAsString());
            observedAnnotationHints.add(fqn);
        }
    }

    void materialize(
            Set<String> sourceTypeNames,
            ResolutionEnvironment environment,
            boolean includeLombokAnnotations) {
        for (Map.Entry<String, String> entry : observedSimpleToFqn.entrySet()) {
            String simple = entry.getKey();
            String fqn = entry.getValue();
            if (sourceTypeNames.contains(fqn) || environment.isKnownInPrimarySolvers(fqn)) {
                continue;
            }
            int typeParamCount = Math.max(
                    observedMaxTypeArgs.getOrDefault(fqn, 0),
                    observedMaxTypeArgs.getOrDefault(simple, 0));
            registerDeclaration(
                    fqn,
                    typeParamCount,
                    observedInterfaceHints.contains(fqn),
                    observedAnnotationHints.contains(fqn));
        }

        if (includeLombokAnnotations) {
            for (String lombokFqn : LOMBOK_TYPES) {
                if (!sourceTypeNames.contains(lombokFqn) && !environment.isKnownInPrimarySolvers(lombokFqn)) {
                    boolean isAnn = !lombokFqn.endsWith(".AccessLevel");
                    registerDeclaration(lombokFqn, 0, false, isAnn);
                }
            }
        }
    }

    private ExplicitImportTypeDeclaration registerDeclaration(
            String fqn, int typeParamCount, boolean isInterface, boolean isAnnotation) {
        return declarations.computeIfAbsent(fqn, key -> {
            int dot = key.lastIndexOf('.');
            String pkg = dot > 0 ? key.substring(0, dot) : "";
            String simple = dot > 0 ? key.substring(dot + 1) : key;
            return new ExplicitImportTypeDeclaration(pkg, simple, key, Optional.empty(), typeParamCount, isInterface, isAnnotation, this);
        });
    }

    @Override
    public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveTypeInModule(
            String moduleName, String simpleTypeName) {
        return SymbolReference.unsolved();
    }

    @Override
    public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveType(String name) {
        ExplicitImportTypeDeclaration existing = declarations.get(name);
        if (existing != null) {
            return SymbolReference.solved(existing);
        }
        int dot = name.lastIndexOf('.');
        while (dot > 0) {
            String prefix = name.substring(0, dot);
            ExplicitImportTypeDeclaration outer = declarations.get(prefix);
            if (outer != null) {
                ExplicitImportTypeDeclaration nested = createNestedChain(outer, name.substring(dot + 1).split("\\."));
                return SymbolReference.solved(nested);
            }
            dot = prefix.lastIndexOf('.');
        }
        return SymbolReference.unsolved();
    }

    private ExplicitImportTypeDeclaration createNestedChain(ExplicitImportTypeDeclaration outer, String[] parts) {
        ExplicitImportTypeDeclaration current = outer;
        for (String part : parts) {
            String nextFqn = current.getQualifiedName() + "." + part;
            ExplicitImportTypeDeclaration parentDecl = current;
            current = declarations.computeIfAbsent(nextFqn, key -> new ExplicitImportTypeDeclaration(
                    parentDecl.getPackageName(),
                    parentDecl.getClassName() + "." + part,
                    key,
                    Optional.of(parentDecl),
                    0,
                    false,
                    false,
                    this));
        }
        return current;
    }

    static final class ExplicitImportTypeDeclaration
            implements ResolvedClassDeclaration,
                    ResolvedInterfaceDeclaration,
                    ResolvedAnnotationDeclaration {
        private final String packageName;
        private final String className;
        private final String simpleName;
        private final String qualifiedName;
        private final Optional<ResolvedReferenceTypeDeclaration> container;
        private final List<ResolvedTypeParameterDeclaration> typeParameters;
        private final List<ResolvedConstructorDeclaration> constructors;
        private final boolean interfaceHint;
        private final boolean annotationHint;
        private final TypeSolver solver;

        ExplicitImportTypeDeclaration(
                String packageName,
                String className,
                String qualifiedName,
                Optional<ResolvedReferenceTypeDeclaration> container,
                int typeParamCount,
                boolean interfaceHint,
                boolean annotationHint,
                TypeSolver solver) {
            this.packageName = packageName;
            this.className = className;
            int lastDot = className.lastIndexOf('.');
            this.simpleName = lastDot >= 0 ? className.substring(lastDot + 1) : className;
            this.qualifiedName = qualifiedName;
            this.container = container;
            this.interfaceHint = interfaceHint;
            this.annotationHint = annotationHint;
            this.solver = solver;
            var params = new ArrayList<ResolvedTypeParameterDeclaration>(typeParamCount);
            for (int i = 0; i < typeParamCount; i++) {
                params.add(new SyntheticTypeParameter("T" + i, this));
            }
            this.typeParameters = List.copyOf(params);
            this.constructors = List.of(new ZeroArgConstructor(this));
        }

        @Override
        public String getPackageName() {
            return packageName;
        }

        @Override
        public String getClassName() {
            return className;
        }

        @Override
        public String getQualifiedName() {
            return qualifiedName;
        }

        @Override
        public String getName() {
            return simpleName;
        }

        @Override
        public String getId() {
            return qualifiedName;
        }

        @Override
        public Optional<ResolvedReferenceTypeDeclaration> containerType() {
            return container;
        }

        @Override
        public Optional<Node> toAst() {
            return Optional.empty();
        }

        @Override
        public List<ResolvedReferenceType> getAncestors(boolean acceptIncompleteList) {
            return List.of();
        }

        @Override
        public List<ResolvedFieldDeclaration> getAllFields() {
            return List.of();
        }

        @Override
        public Set<ResolvedMethodDeclaration> getDeclaredMethods() {
            return Set.of();
        }

        @Override
        public Set<MethodUsage> getAllMethods() {
            return Set.of();
        }

        public SymbolReference<ResolvedMethodDeclaration> solveMethod(
                String name, List<ResolvedType> argumentsTypes, boolean staticOnly) {
            return SymbolReference.unsolved();
        }

        @Override
        public boolean isAssignableBy(ResolvedType type) {
            if (type.isNull()) return true;
            return type.isReferenceType() && qualifiedName.equals(type.asReferenceType().getQualifiedName());
        }

        @Override
        public boolean isAssignableBy(ResolvedReferenceTypeDeclaration other) {
            return other != null && qualifiedName.equals(other.getQualifiedName());
        }

        @Override
        public boolean hasDirectlyAnnotation(String qualifiedName) {
            return false;
        }

        @Override
        public boolean isFunctionalInterface() {
            return false;
        }

        @Override
        public List<ResolvedConstructorDeclaration> getConstructors() {
            return constructors;
        }

        @Override
        public AccessSpecifier accessSpecifier() {
            return AccessSpecifier.PUBLIC;
        }

        public AccessSpecifier accessLevel() {
            return AccessSpecifier.PUBLIC;
        }

        @Override
        public Optional<ResolvedReferenceType> getSuperClass() {
            if (solver != null && solver.getRoot() != null) {
                var obj = solver.getRoot().tryToSolveType("java.lang.Object");
                if (obj.isSolved()) {
                    return Optional.of(new ReferenceTypeImpl(obj.getCorrespondingDeclaration()));
                }
            }
            return Optional.empty();
        }

        @Override
        public List<ResolvedReferenceType> getInterfaces() {
            return List.of();
        }

        @Override
        public List<ResolvedReferenceType> getAllSuperClasses() {
            return getSuperClass().map(List::of).orElseGet(List::of);
        }

        @Override
        public List<ResolvedReferenceType> getAllInterfaces() {
            return List.of();
        }

        @Override
        public List<ResolvedReferenceType> getInterfacesExtended() {
            return List.of();
        }

        @Override
        public List<ResolvedAnnotationMemberDeclaration> getAnnotationMembers() {
            return List.of();
        }

        @Override
        public boolean isInheritable() {
            return false;
        }

        @Override
        public List<ResolvedTypeParameterDeclaration> getTypeParameters() {
            return typeParameters;
        }

        @Override
        public boolean isType() {
            return true;
        }

        @Override
        public boolean isReferenceType() {
            return true;
        }

        @Override
        public boolean isClass() {
            return !interfaceHint && !annotationHint;
        }

        @Override
        public boolean isInterface() {
            return interfaceHint;
        }

        @Override
        public boolean isAnnotation() {
            return annotationHint;
        }

        @Override
        public ResolvedReferenceTypeDeclaration asReferenceType() {
            return this;
        }

        @Override
        public ResolvedTypeDeclaration asType() {
            return this;
        }

        @Override
        public ResolvedClassDeclaration asClass() {
            return this;
        }

        @Override
        public ResolvedInterfaceDeclaration asInterface() {
            return this;
        }

        @Override
        public ResolvedAnnotationDeclaration asAnnotation() {
            return this;
        }

        public Optional<Context> getContext() {
            return Optional.empty();
        }
    }

    private record SyntheticTypeParameter(String name, ExplicitImportTypeDeclaration container)
            implements ResolvedTypeParameterDeclaration {
        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getContainerQualifiedName() {
            return container.getQualifiedName();
        }

        @Override
        public String getContainerId() {
            return container.getId();
        }

        @Override
        public ResolvedTypeParametrizable getContainer() {
            return container;
        }

        @Override
        public List<Bound> getBounds() {
            return List.of();
        }

        @Override
        public ResolvedReferenceType object() {
            return container.getSuperClass().orElseThrow();
        }

        @Override
        public Optional<ResolvedReferenceTypeDeclaration> containerType() {
            return Optional.of(container);
        }
    }

    private record ZeroArgConstructor(ExplicitImportTypeDeclaration owner) implements ResolvedConstructorDeclaration {
        @Override
        public ResolvedReferenceTypeDeclaration declaringType() {
            return owner;
        }

        @Override
        public int getNumberOfParams() {
            return 0;
        }

        @Override
        public ResolvedParameterDeclaration getParam(int i) {
            throw new IndexOutOfBoundsException(i);
        }

        @Override
        public int getNumberOfSpecifiedExceptions() {
            return 0;
        }

        @Override
        public ResolvedType getSpecifiedException(int index) {
            throw new IndexOutOfBoundsException(index);
        }

        @Override
        public AccessSpecifier accessSpecifier() {
            return AccessSpecifier.PUBLIC;
        }

        @Override
        public String getName() {
            return owner.getName();
        }

        @Override
        public List<ResolvedTypeParameterDeclaration> getTypeParameters() {
            return List.of();
        }
    }
}
