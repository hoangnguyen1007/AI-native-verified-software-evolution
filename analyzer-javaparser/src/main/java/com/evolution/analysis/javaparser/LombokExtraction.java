package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.frontend.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.model.typesystem.ReferenceTypeImpl;
import com.github.javaparser.resolution.types.ResolvedType;
import java.util.*;

/** Evidence-bound structural synthesis. Never runs annotation processors or changes source ASTs. */
final class LombokExtraction {
    private static final Set<String> GENERATORS = Set.of(
            "RequiredArgsConstructor", "AllArgsConstructor", "NoArgsConstructor",
            "Data", "Value", "Builder", "SuperBuilder", "Getter", "Setter", "With",
            "ToString", "EqualsAndHashCode", "UtilityClass", "StandardException");
    private static final Set<String> ACCESSORS = Set.of("Getter", "Setter", "With");
    private static final Set<String> NON_CONSTRUCTOR_GENERATORS = Set.of(
            "Getter", "Setter", "With", "ToString", "EqualsAndHashCode");
    private static final Set<String> STANDARD_NULLNESS_FQNS = Set.of(
            "lombok.NonNull",
            "jakarta.annotation.Nonnull",
            "javax.annotation.Nonnull",
            "org.jetbrains.annotations.NotNull",
            "org.springframework.lang.NonNull");
    private static final Set<String> NULLNESS_SIMPLE_NAMES = Set.of(
            "NonNull", "Nonnull", "NotNull", "NonNullDecl");
    private static final Map<String, String> LOG_ANNOTATIONS = Map.of(
            "Slf4j", "lombok.extern.slf4j.Slf4j",
            "XSlf4j", "lombok.extern.slf4j.XSlf4j",
            "Log4j2", "lombok.extern.log4j.Log4j2",
            "Log4j", "lombok.extern.log4j.Log4j",
            "Log", "lombok.extern.java.Log",
            "CommonsLog", "lombok.extern.apachecommons.CommonsLog",
            "JBossLog", "lombok.extern.jbosslog.JBossLog",
            "Flogger", "lombok.extern.flogger.Flogger",
            "CustomLog", "lombok.CustomLog");
    private static final Map<String, JavaSymbolName> LOGGER_TYPES = Map.of(
            "Slf4j", JavaSymbolName.topLevelType("org.slf4j", "Logger"),
            "XSlf4j", JavaSymbolName.topLevelType("org.slf4j.ext", "XLogger"),
            "Log4j2", JavaSymbolName.topLevelType("org.apache.logging.log4j", "Logger"),
            "Log4j", JavaSymbolName.topLevelType("org.apache.log4j", "Logger"),
            "Log", JavaSymbolName.topLevelType("java.util.logging", "Logger"),
            "CommonsLog", JavaSymbolName.topLevelType("org.apache.commons.logging", "Log"),
            "JBossLog", JavaSymbolName.topLevelType("org.jboss.logging", "Logger"),
            "Flogger", JavaSymbolName.topLevelType("com.google.common.flogger", "FluentLogger"),
            "CustomLog", JavaSymbolName.topLevelType("lombok", "CustomLogger"));
    private static final Set<String> LOGGER_METHODS = Set.of(
            "trace", "debug", "info", "warn", "error", "fatal", "log", "fine", "finer", "finest", "severe", "warning", "config",
            "isTraceEnabled", "isDebugEnabled", "isInfoEnabled", "isWarnEnabled", "isErrorEnabled", "isFatalEnabled",
            "atTrace", "atDebug", "atInfo", "atWarn", "atError", "atFine", "atFiner", "atFinest", "atSevere", "atWarning", "atConfig",
            "entering", "exiting", "throwing", "printf", "catching");
    private static final Set<String> SINGULAR_COLLECTION_TYPES = Set.of(
            "java.util.List", "java.util.Collection", "java.util.Set",
            "java.util.SortedSet", "java.util.NavigableSet", "java.lang.Iterable",
            "com.google.common.collect.ImmutableList", "com.google.common.collect.ImmutableSet",
            "com.google.common.collect.ImmutableCollection", "com.google.common.collect.ImmutableSortedSet");
    private static final Set<String> SINGULAR_MAP_TYPES = Set.of(
            "java.util.Map", "java.util.SortedMap", "java.util.NavigableMap",
            "com.google.common.collect.ImmutableMap", "com.google.common.collect.ImmutableSortedMap",
            "com.google.common.collect.ImmutableBiMap");
    private static final Map<String, String> IRREGULAR_SINGULARS = Map.ofEntries(
            Map.entry("axes", "axis"),
            Map.entry("children", "child"),
            Map.entry("people", "person"),
            Map.entry("men", "man"),
            Map.entry("women", "woman"),
            Map.entry("feet", "foot"),
            Map.entry("teeth", "tooth"),
            Map.entry("geese", "goose"),
            Map.entry("mice", "mouse"),
            Map.entry("lice", "louse"),
            Map.entry("oxen", "ox"),
            Map.entry("data", "datum"),
            Map.entry("indices", "index"),
            Map.entry("matrices", "matrix"),
            Map.entry("vertices", "vertex"),
            Map.entry("statuses", "status"),
            Map.entry("aliases", "alias"),
            Map.entry("buses", "bus"),
            Map.entry("quizzes", "quiz"));

    private final Extraction context;
    private final List<Generated> generated = new ArrayList<>();
    private final Map<CompilationUnit, List<Generated>> generatedByUnit = new IdentityHashMap<>();
    private final Map<Entity, Generated> generatedByEntity = new HashMap<>();
    private final Map<JavaSymbolName, List<Generated>> generatedByOwnerName = new HashMap<>();
    private final Set<JavaSymbolName> ownersWithGeneratedConstructor = new HashSet<>();
    private final Map<Entity, ClassOrInterfaceDeclaration> generatedOwners = new HashMap<>();
    private final Map<Entity, String> generatedMethodNames = new HashMap<>();
    private final Map<Entity, String> generatedAccess = new HashMap<>();

    private record BuilderMethodSpec(
            Entity entity,
            List<Type> paramAstTypes,
            boolean isClear,
            boolean isSingularBulk,
            boolean isSingularMapBulk) {}

    private record BuilderShape(
            ClassOrInterfaceDeclaration owner,
            Entity builderType,
            Type builtReturnAstType,
            String factoryMethodName,
            String buildMethodName,
            Entity factory,
            Entity toBuilder,
            Entity build,
            Map<String, List<BuilderMethodSpec>> methodsByName,
            AnnotationExpr sourceAnnotation) {}

    private record BuilderCall(Entity target, BuilderShape builder, boolean built) {}
    private record LoggerShape(TypeDeclaration<?> owner, Entity field, JavaSymbolName loggerType, String fieldName) {}
    private record AccessorConfig(boolean fluent, boolean chain, List<String> prefixes, boolean noIsPrefix, boolean supported) {}
    private record FieldDefaultsConfig(boolean makeFinal, String level, boolean supported) {}
    private record BuilderOptions(
            boolean supported,
            boolean toBuilder,
            String builderClassName,
            String builderMethodName,
            String buildMethodName,
            String setterPrefix,
            String access) {}
    private record BuilderEntry(String name, Type type, Node sourceNode, AnnotationExpr singularAnnotation) {}

    private final Map<JavaSymbolName, List<BuilderShape>> builders = new HashMap<>();
    private final Map<JavaSymbolName, LoggerShape> loggers = new HashMap<>();
    private final Map<JavaSymbolName, ClassOrInterfaceDeclaration> utilityClasses = new HashMap<>();
    private final Set<ClassOrInterfaceDeclaration> unsupportedConstructorOwners = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean hasPotentialLombokInModule;

    private record Generated(
            Entity callable,
            JavaSymbolName name,
            List<VariableDeclarator> fields,
            List<Entity> parameters,
            String rule,
            boolean setter,
            boolean returnsOwner,
            List<String> fixedParamTypes) {
        Generated(Entity callable, JavaSymbolName name, List<VariableDeclarator> fields,
                  List<Entity> parameters, String rule, boolean setter, boolean returnsOwner) {
            this(callable, name, fields, parameters, rule, setter, returnsOwner, List.of());
        }
    }

    LombokExtraction(Extraction context) { this.context = context; }

    void setHasPotentialLombokInModule(boolean value) { this.hasPotentialLombokInModule = value; }

    private void addGenerated(ClassOrInterfaceDeclaration owner, Generated g) {
        generated.add(g);
        generatedByEntity.put(g.callable(), g);
        if (owner != null) {
            JavaSymbolName ownerSym = context.name(owner);
            generatedByOwnerName.computeIfAbsent(ownerSym, k -> new ArrayList<>(4)).add(g);
            if (g.callable().kind() == EntityKind.CONSTRUCTOR) {
                ownersWithGeneratedConstructor.add(ownerSym);
            }
        }
        Set<CompilationUnit> seenUnits = null;
        for (var f : g.fields()) {
            var cuOpt = f.findCompilationUnit();
            if (cuOpt.isPresent()) {
                CompilationUnit cu = cuOpt.orElseThrow();
                if (seenUnits == null) seenUnits = Collections.newSetFromMap(new IdentityHashMap<>());
                if (seenUnits.add(cu)) {
                    generatedByUnit.computeIfAbsent(cu, k -> new ArrayList<>(4)).add(g);
                }
            }
        }
        if (g.fields().isEmpty() && owner != null) {
            owner.findCompilationUnit().ifPresent(cu -> generatedByUnit.computeIfAbsent(cu, k -> new ArrayList<>(4)).add(g));
        }
    }

    void releaseBody(CompilationUnit unit) {
        var removed = generatedByUnit.remove(unit);
        if (removed != null && !removed.isEmpty()) {
            Set<Generated> toRemove = Collections.newSetFromMap(new IdentityHashMap<>());
            toRemove.addAll(removed);
            generated.removeIf(toRemove::contains);
            for (var g : removed) {
                generatedByEntity.remove(g.callable());
            }
            for (var list : generatedByOwnerName.values()) {
                list.removeIf(toRemove::contains);
            }
        } else if (!generated.isEmpty()) {
            generated.removeIf(g -> g.fields().stream().anyMatch(f -> f.findCompilationUnit().filter(c -> c == unit).isPresent()));
        }
        generatedOwners.entrySet().removeIf(e -> e.getValue().findCompilationUnit().filter(c -> c == unit).isPresent());
        unsupportedConstructorOwners.removeIf(t -> t.findCompilationUnit().filter(c -> c == unit).isPresent());
        builders.values().forEach(list -> list.removeIf(b -> b.owner().findCompilationUnit().filter(c -> c == unit).isPresent()));
        builders.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    private static String generatorFqn(String simple) {
        return switch (simple) {
            case "UtilityClass" -> "lombok.experimental.UtilityClass";
            case "SuperBuilder" -> "lombok.experimental.SuperBuilder";
            case "StandardException" -> "lombok.experimental.StandardException";
            case "FieldDefaults" -> "lombok.experimental.FieldDefaults";
            case "Accessors" -> "lombok.experimental.Accessors";
            case "Tolerate" -> "lombok.experimental.Tolerate";
            case "Delegate" -> "lombok.experimental.Delegate";
            default -> "lombok." + simple;
        };
    }

    void loggerDeclarations(TypeDeclaration<?> type) {
        if (type instanceof ClassOrInterfaceDeclaration c && c.isInterface() || type instanceof AnnotationDeclaration) return;
        for (var annotation : type.getAnnotations()) {
            String simple = annotation.getName().getIdentifier();
            String fqn = LOG_ANNOTATIONS.get(simple);
            if (fqn == null) continue;
            if (!verifiedOrRecovered(annotation, fqn)) continue;
            var cfg = context.lombokConfig(type);
            if (cfg.requiresConfigurationGap(false, false, true, false)) {
                context.synthesisGap(type, "lombok.configuration-required");
                return;
            }
            String logFieldName = cfg.logFieldName();
            boolean hasWrittenLog = type.getFields().stream()
                    .flatMap(f -> f.getVariables().stream())
                    .anyMatch(v -> v.getNameAsString().equals(logFieldName));
            if (hasWrittenLog) return;
            var ownerSymbol = context.name(type);
            var logSymbol = JavaSymbolName.field(ownerSymbol, logFieldName);
            var logField = context.implicit(logSymbol, EntityKind.FIELD, type, "lombok.logger-field");
            loggers.put(ownerSymbol, new LoggerShape(type, logField, LOGGER_TYPES.get(simple), logFieldName));
            return;
        }
    }

    void unsupportedGenerators(CompilationUnit unit) {
        unsupportedGenerators(unit, false);
    }

    void unsupportedGenerators(CompilationUnit unit, boolean localsOnly) {
        var otherGenerators = Set.of("Getter", "Setter", "With", "WithBy", "ToString", "EqualsAndHashCode", "UtilityClass", "Delegate");
        for (var annotation : unit.findAll(AnnotationExpr.class)) {
            String simple = annotation.getName().getIdentifier();
            var parent = annotation.getParentNode().orElse(null);
            if (GENERATORS.contains(simple) && parent instanceof ClassOrInterfaceDeclaration type && !type.isInterface()) continue;
            if (ACCESSORS.contains(simple) && parent instanceof FieldDeclaration) continue;
            // Constructor and static non-void method @Builder are supported!
            if (simple.equals("Builder") && parent instanceof ConstructorDeclaration) continue;
            if (simple.equals("Builder") && parent instanceof MethodDeclaration md && md.isStatic() && !md.getType().isVoidType()) continue;
            if (!GENERATORS.contains(simple) && !otherGenerators.contains(simple)) continue;
            var nativeOwner = annotation.findAncestor(TypeDeclaration.class);
            if (localsOnly != (nativeOwner.isPresent() && context.hasLocalOwner(nativeOwner.orElseThrow()))) continue;
            String expectedFqn = generatorFqn(simple);
            try {
                if (nativeOwner.isPresent() && context.nativeOwner(nativeOwner.orElseThrow())) continue;
                var entity = context.typeEntity(annotation.resolve());
                if (entity.origin() == EntityOrigin.PROJECT) continue;
                String name = JavaTypeName.fromCanonical(entity.canonicalName()).map(JavaTypeName::qualifiedName).orElse("");
                if (!name.startsWith("lombok.")) continue;
                context.synthesisGap(annotation, context.isLombok(entity, name) || context.isSourceRecoveryLombok(annotation, expectedFqn)
                        ? "lombok.generator-unsupported" : "lombok.artifact-unverified");
                if (parent instanceof ClassOrInterfaceDeclaration type && Set.of("UtilityClass").contains(simple)) {
                    unsupportedConstructorOwners.add(type);
                }
            } catch (RuntimeException failure) {
                if (context.isSourceRecoveryLombok(annotation, expectedFqn)) {
                    context.synthesisGap(annotation, "lombok.generator-unsupported");
                    if (parent instanceof ClassOrInterfaceDeclaration type && Set.of("UtilityClass").contains(simple)) {
                        unsupportedConstructorOwners.add(type);
                    }
                } else {
                    context.synthesisGap(annotation, "lombok.annotation-unresolved");
                }
            }
        }
    }

    boolean isUtilityClass(ClassOrInterfaceDeclaration type) {
        try { return utilityClasses.containsKey(context.name(type)); }
        catch (RuntimeException unavailable) { return false; }
    }

    boolean declarations(ClassOrInterfaceDeclaration type) {
        if (unsupportedConstructorOwners.contains(type)) return true;
        if (type.isInterface()) return false;
        Map<String, AnnotationExpr> annotations = new TreeMap<>();
        Set<String> artifactVerified = new HashSet<>();
        boolean potential = false, constructorPotential = false;
        for (var annotation : type.getAnnotations()) {
            String simple = annotation.getName().getIdentifier();
            if (!GENERATORS.contains(simple)) continue;
            String fqn = generatorFqn(simple);
            boolean ownsConstructor = !NON_CONSTRUCTOR_GENERATORS.contains(simple);
            try {
                var entity = context.typeEntity(annotation.resolve());
                if (entity.origin() == EntityOrigin.PROJECT) continue;
                if (context.isLombok(entity, fqn) || simple.equals("UtilityClass") && context.isLombok(entity, "lombok.UtilityClass")) {
                    annotations.put(simple, annotation);
                    artifactVerified.add(simple);
                    potential = true;
                    constructorPotential |= ownsConstructor;
                } else if (context.isSourceRecoveryLombok(annotation, fqn) || simple.equals("UtilityClass") && context.isSourceRecoveryLombok(annotation, "lombok.UtilityClass")) {
                    annotations.put(simple, annotation);
                    potential = true;
                    constructorPotential |= ownsConstructor;
                } else if (annotation.getNameAsString().startsWith("lombok.") || entity.canonicalName().contains("lombok")) {
                    potential = true;
                    constructorPotential |= ownsConstructor;
                    context.synthesisGap(type, "lombok.artifact-unverified");
                }
            } catch (RuntimeException failure) {
                if (context.isSourceRecoveryLombok(annotation, fqn) || simple.equals("UtilityClass") && context.isSourceRecoveryLombok(annotation, "lombok.UtilityClass")) {
                    annotations.put(simple, annotation);
                    potential = true;
                    constructorPotential |= ownsConstructor;
                } else {
                    potential = true;
                    constructorPotential |= ownsConstructor;
                    context.synthesisGap(type, "lombok.annotation-unresolved");
                }
            }
        }
        // Check constructor-level and static-method-level @Builder annotations.
        var memberBuilders = new ArrayList<BodyDeclaration<?>>();
        var memberBuilderAnns = new ArrayList<AnnotationExpr>();
        for (var ctor : type.getConstructors()) {
            for (var ann : ctor.getAnnotations()) {
                if (!ann.getName().getIdentifier().equals("Builder")) continue;
                if (verifiedOrRecovered(ann, "lombok.Builder")) {
                    memberBuilders.add(ctor);
                    memberBuilderAnns.add(ann);
                    potential = true;
                } else if (isUnverifiedLombok(ann)) {
                    potential = true;
                    context.synthesisGap(ann, "lombok.artifact-unverified");
                }
            }
        }
        for (var method : type.getMethods()) {
            if (!method.isStatic() || method.getType().isVoidType()) continue;
            for (var ann : method.getAnnotations()) {
                if (!ann.getName().getIdentifier().equals("Builder")) continue;
                if (verifiedOrRecovered(ann, "lombok.Builder")) {
                    memberBuilders.add(method);
                    memberBuilderAnns.add(ann);
                    potential = true;
                } else if (isUnverifiedLombok(ann)) {
                    potential = true;
                    context.synthesisGap(ann, "lombok.artifact-unverified");
                }
            }
        }
        // Field-only accessors do not own or suppress Java's implicit constructor.
        boolean hasFieldAccessors = false;
        for (var field : type.getFields()) {
            for (var annotation : field.getAnnotations()) {
                String simple = annotation.getName().getIdentifier();
                if (ACCESSORS.contains(simple)) {
                    try {
                        var entity = context.typeEntity(annotation.resolve());
                        if (entity.origin() == EntityOrigin.PROJECT) continue;
                        if (context.isLombok(entity, "lombok." + simple) || context.isSourceRecoveryLombok(annotation, "lombok." + simple)) {
                            potential = true;
                            hasFieldAccessors = true;
                        } else if (entity.canonicalName().contains("lombok")) {
                            potential = true;
                            context.synthesisGap(annotation, "lombok.artifact-unverified");
                        }
                    } catch (RuntimeException failure) {
                        if (context.isSourceRecoveryLombok(annotation, "lombok." + simple)) {
                            potential = true;
                            hasFieldAccessors = true;
                        } else {
                            potential = true;
                            context.synthesisGap(annotation, "lombok.annotation-unresolved");
                        }
                    }
                }
            }
        }
        if (!potential) return false;

        boolean hasAccessors = hasFieldAccessors
                || annotations.containsKey("Getter") || annotations.containsKey("Setter")
                || annotations.containsKey("With") || annotations.containsKey("Data") || annotations.containsKey("Value");
        boolean hasBuilder = annotations.containsKey("Builder") || annotations.containsKey("SuperBuilder") || !memberBuilders.isEmpty();
        boolean hasLogger = type.getAnnotations().stream().anyMatch(a -> LOG_ANNOTATIONS.containsKey(a.getName().getIdentifier()));

        LombokConfig.Effective cfg = context.lombokConfig(type);
        if (cfg.requiresConfigurationGap(hasAccessors, hasBuilder, hasLogger, constructorPotential)) {
            context.synthesisGap(type, "lombok.configuration-required");
            return constructorPotential;
        }
        if (constructorPotential && type.isNestedType() && !type.isStatic()) {
            context.synthesisGap(type, "lombok.inner-class-constructor");
            return true;
        }
        // Only reject genuine Lombok @Tolerate (not unrelated non-Lombok annotations).
        if (type.findAll(AnnotationExpr.class).stream()
                .anyMatch(a -> a.getName().getIdentifier().equals("Tolerate") && verifiedOrRecovered(a, "lombok.experimental.Tolerate"))) {
            context.synthesisGap(type, "lombok.member-customization");
            return constructorPotential;
        }

        // Parse @FieldDefaults (qualified via isLombok/recovery) + lombok.config defaults.
        FieldDefaultsConfig fieldDefaults = fieldDefaultsConfig(type, cfg);
        if (!fieldDefaults.supported()) {
            context.synthesisGap(type, "lombok.member-customization");
            return constructorPotential;
        }

        // Parse @Accessors (qualified via isLombok/recovery) + lombok.config defaults.
        AccessorConfig baseAccessors = new AccessorConfig(
                cfg.accessorsFluent(),
                cfg.accessorsChain() || cfg.accessorsFluent(),
                cfg.accessorsPrefix(),
                cfg.getterNoIsPrefix(),
                true);
        AccessorConfig typeAccessors = mergeAccessorConfig(type.getAnnotations(), baseAccessors);
        if (!typeAccessors.supported()) {
            context.synthesisGap(type, "lombok.member-customization");
            return constructorPotential;
        }

        if (annotations.containsKey("UtilityClass")) {
            utilityClasses.put(context.name(type), type);
            if (artifactVerified.contains("UtilityClass") && type.getConstructors().isEmpty()) {
                var ctorName = JavaSymbolName.constructor(context.name(type), List.of());
                if (!context.hasDeclaration(ctorName)) {
                    var callable = context.implicit(ctorName, EntityKind.CONSTRUCTOR, type, "lombok.utility-class-constructor");
                    generatedOwners.put(callable, type);
                    generatedAccess.put(callable, "PRIVATE");
                    addGenerated(type, new Generated(callable, ctorName, List.of(), List.of(), "lombok.utility-class-constructor", true, false));
                }
            }
            return true;
        }

        var fields = type.getFields().stream()
                .filter(f -> !f.isStatic())
                .flatMap(f -> f.getVariables().stream())
                .filter(f -> !f.getNameAsString().startsWith("$"))
                .toList();

        if (constructorPotential && fields.stream().anyMatch(f -> field(f).getAnnotations().stream()
                .anyMatch(a -> NULLNESS_SIMPLE_NAMES.contains(a.getName().getIdentifier()) && !isVerifiedNullness(a)))) {
            context.synthesisGap(type, "lombok.nullness-annotation-unverified");
            return true;
        }

        boolean value = annotations.containsKey("Value");
        boolean makeFinal = fieldDefaults.makeFinal() || value;
        var required = fields.stream()
                .filter(f -> f.getInitializer().isEmpty() && (isEffectiveFinal(field(f), makeFinal) || nonNull(field(f))))
                .toList();
        var all = fields.stream()
                .filter(f -> f.getInitializer().isEmpty() || !isEffectiveFinal(field(f), makeFinal))
                .toList();
        var builderAll = fields.stream()
                .filter(f -> f.getInitializer().isEmpty() || !isEffectiveFinal(field(f), makeFinal) || isBuilderDefault(field(f)))
                .toList();
        boolean explicitAnnotation = annotations.keySet().stream().anyMatch(n -> n.endsWith("ArgsConstructor"));

        for (var entry : annotations.entrySet()) {
            try {
                String name = entry.getKey();
                var annotation = entry.getValue();
                if (NON_CONSTRUCTOR_GENERATORS.contains(name)) continue;
                if (name.equals("Builder") || name.equals("SuperBuilder")) {
                    BuilderOptions opts = parseBuilderOptions(annotation, name.equals("SuperBuilder"));
                    if (!opts.supported()) {
                        context.synthesisGap(annotation, "lombok.annotation-options");
                        continue;
                    }
                    if (name.equals("Builder")) {
                        if (artifactVerified.contains(name) && type.getConstructors().isEmpty() && !explicitAnnotation) {
                            constructor(type, builderAll, "lombok.builder-constructor", "PACKAGE");
                        }
                        synthesizeClassBuilder(type, builderAll, annotation, opts, false);
                    } else {
                        synthesizeSuperBuilder(type, annotation, opts, makeFinal);
                    }
                    continue;
                }
                if (name.equals("StandardException")) {
                    String access = parseStandardExceptionAccess(annotation);
                    if (access == null) {
                        context.synthesisGap(annotation, "lombok.annotation-options");
                        continue;
                    }
                    if (artifactVerified.contains(name) || context.isSourceRecoveryLombok(annotation, "lombok.experimental.StandardException")) {
                        synthesizeStandardExceptionConstructors(type, access);
                    }
                    continue;
                }
                if (!optionsSupported(annotation)) {
                    context.synthesisGap(annotation, "lombok.annotation-options");
                    continue;
                }
                if (artifactVerified.contains(name)) {
                    String ctorAccess = extractConstructorAccess(annotation);
                    if (name.equals("RequiredArgsConstructor")) {
                        constructor(type, required, "lombok.required-args-constructor", ctorAccess);
                    }
                    if (name.equals("AllArgsConstructor")) {
                        constructor(type, all, "lombok.all-args-constructor", ctorAccess);
                    }
                    if (name.equals("NoArgsConstructor")) {
                        boolean force = annotation instanceof NormalAnnotationExpr n && n.getPairs().stream()
                                .anyMatch(p -> p.getNameAsString().equals("force")
                                        && p.getValue().isBooleanLiteralExpr()
                                        && p.getValue().asBooleanLiteralExpr().getValue());
                        if (!force && fields.stream().anyMatch(f -> isEffectiveFinal(field(f), makeFinal) && f.getInitializer().isEmpty())) {
                            context.synthesisGap(annotation, "lombok.invalid-no-args");
                        } else {
                            constructor(type, List.of(), "lombok.no-args-constructor", ctorAccess);
                        }
                    }
                    if (Set.of("Data", "Value").contains(name) && type.getConstructors().isEmpty() && !explicitAnnotation) {
                        if (name.equals("Data") && !value && !annotations.containsKey("Builder") && !annotations.containsKey("SuperBuilder")) {
                            constructor(type, required, "lombok.data-constructor", "PUBLIC");
                        }
                        if (name.equals("Value") && !annotations.containsKey("Builder") && !annotations.containsKey("SuperBuilder")) {
                            constructor(type, all, "lombok.value-constructor", "PUBLIC");
                        }
                    }
                }
            } catch (RuntimeException failure) {
                context.synthesisGap(type, "lombok.member-type-unresolved");
            }
        }

        // Synthesize constructor-level and static-method-level @Builder declarations.
        for (int i = 0; i < memberBuilders.size(); i++) {
            BodyDeclaration<?> decl = memberBuilders.get(i);
            AnnotationExpr ann = memberBuilderAnns.get(i);
            try {
                BuilderOptions opts = parseBuilderOptions(ann, false);
                if (!opts.supported()) {
                    context.synthesisGap(ann, "lombok.annotation-options");
                    continue;
                }
                if (decl instanceof ConstructorDeclaration ctor) {
                    synthesizeCallableBuilder(type, ctor, null, ann, opts);
                } else if (decl instanceof MethodDeclaration method) {
                    synthesizeCallableBuilder(type, method, method.getType(), ann, opts);
                }
            } catch (RuntimeException failure) {
                context.synthesisGap(decl, "lombok.member-type-unresolved");
            }
        }

        boolean hasDataOrValue = annotations.containsKey("Data") || value;
        if (hasDataOrValue || annotations.containsKey("ToString") || annotations.containsKey("EqualsAndHashCode")) {
            objectMembers(type, value, hasDataOrValue || annotations.containsKey("ToString"), hasDataOrValue || annotations.containsKey("EqualsAndHashCode"));
        }

        for (var f : fields) {
            try {
                AccessorConfig fieldAccessors = mergeAccessorConfig(field(f).getAnnotations(), typeAccessors);
                if (!fieldAccessors.supported()) {
                    context.synthesisGap(f, "lombok.member-customization");
                    continue;
                }
                String rawName = f.getNameAsString();
                String strippedName = stripPrefix(rawName, fieldAccessors.prefixes());
                if (strippedName == null) continue; // Lombok skips fields that don't match configured prefix list.
                boolean bool = f.getType().isPrimitiveType()
                        && f.getType().asPrimitiveType().getType() == com.github.javaparser.ast.type.PrimitiveType.Primitive.BOOLEAN;
                boolean useIsPrefix = bool && !fieldAccessors.noIsPrefix();
                String capital = capitalize(strippedName);
                String getter = fieldAccessors.fluent()
                        ? strippedName
                        : (useIsPrefix && strippedName.matches("is[A-Z].*")
                                ? strippedName
                                : (useIsPrefix ? "is" : "get") + capital);
                var alternatives = new HashSet<String>();
                alternatives.add(getter);
                if (!fieldAccessors.fluent() && bool) {
                    String base = strippedName.matches("is[A-Z].*") ? strippedName.substring(2) : capital;
                    alternatives.add("get" + base);
                    alternatives.add("is" + capital);
                    alternatives.add("get" + capital);
                }
                String getterAccess = accessorAccess(field(f), annotations.get("Getter"), "Getter", annotations.containsKey("Data") || value);
                if (!getterAccess.equals("NONE") && !suppressed(type, alternatives, 0)) {
                    member(type, getter, List.of(), List.of(f), "lombok.getter", false, false);
                    generatedAccess.put(context.existing(JavaSymbolName.method(context.name(type), getter, List.of())), getterAccess);
                }
                String setterAccess = accessorAccess(field(f), annotations.get("Setter"), "Setter", annotations.containsKey("Data") && !value);
                if (!setterAccess.equals("NONE") && !isEffectiveFinal(field(f), makeFinal)) {
                    String setter = fieldAccessors.fluent()
                            ? strippedName
                            : "set" + (bool && strippedName.matches("is[A-Z].*") ? strippedName.substring(2) : capital);
                    if (!suppressed(type, Set.of(setter), 1)) {
                        member(type, setter, List.of(f), List.of(f), "lombok.setter", true, fieldAccessors.chain());
                        generatedAccess.put(context.existing(JavaSymbolName.method(context.name(type), setter, List.of(context.erasedParameter(f.getType())))), setterAccess);
                    }
                }
                String witherAccess = accessorAccess(field(f), annotations.get("With"), "With", annotations.containsKey("With"));
                if (!witherAccess.equals("NONE") && !(isEffectiveFinal(field(f), makeFinal) && f.getInitializer().isPresent())) {
                    String wither = "with" + (bool && strippedName.matches("is[A-Z].*") ? strippedName.substring(2) : capital);
                    if (!suppressed(type, Set.of(wither), 1)) {
                        member(type, wither, List.of(f), List.of(f), "lombok.with", true, true);
                        generatedAccess.put(context.existing(JavaSymbolName.method(context.name(type), wither, List.of(context.erasedParameter(f.getType())))), witherAccess);
                    }
                }
            } catch (RuntimeException failure) {
                context.synthesisGap(f, "lombok.member-customization");
            }
        }
        for (var f : type.getFields()) {
            if (f.isStatic() && f.getAnnotations().stream().anyMatch(a ->
                    ACCESSORS.contains(a.getName().getIdentifier()) && verifiedOrRecovered(a, "lombok." + a.getName().getIdentifier()))) {
                context.synthesisGap(f, "lombok.static-accessor-required");
            }
        }
        return constructorPotential;
    }

    private FieldDefaultsConfig fieldDefaultsConfig(ClassOrInterfaceDeclaration type, LombokConfig.Effective cfg) {
        boolean makeFinal = cfg.fieldDefaultsFinal();
        String level = cfg.fieldDefaultsPrivate() ? "PRIVATE" : "NONE";
        for (var ann : type.getAnnotations()) {
            if (!ann.getName().getIdentifier().equals("FieldDefaults")) continue;
            if (!verifiedOrRecovered(ann, "lombok.experimental.FieldDefaults")) {
                if (isUnverifiedLombok(ann)) return new FieldDefaultsConfig(false, "NONE", false);
                continue; // Unrelated non-Lombok @FieldDefaults annotation.
            }
            if (ann.isMarkerAnnotationExpr()) continue;
            if (!(ann instanceof NormalAnnotationExpr normal)) return new FieldDefaultsConfig(false, "NONE", false);
            for (var pair : normal.getPairs()) {
                String key = pair.getNameAsString();
                if (key.equals("makeFinal") && pair.getValue().isBooleanLiteralExpr()) {
                    makeFinal = pair.getValue().asBooleanLiteralExpr().getValue();
                } else if (key.equals("level")) {
                    String parsed = parseAccessLevel(pair.getValue());
                    if (parsed == null) return new FieldDefaultsConfig(false, "NONE", false);
                    level = parsed;
                } else {
                    return new FieldDefaultsConfig(false, "NONE", false);
                }
            }
        }
        return new FieldDefaultsConfig(makeFinal, level, true);
    }

    private AccessorConfig mergeAccessorConfig(NodeList<AnnotationExpr> annotations, AccessorConfig base) {
        AnnotationExpr found = null;
        for (var a : annotations) {
            if (!a.getName().getIdentifier().equals("Accessors")) continue;
            if (verifiedOrRecovered(a, "lombok.experimental.Accessors")) {
                found = a;
                break;
            }
            if (isUnverifiedLombok(a)) {
                return new AccessorConfig(false, false, List.of(), false, false);
            }
        }
        if (found == null) return base;
        if (found.isMarkerAnnotationExpr()) return base;
        if (!(found instanceof NormalAnnotationExpr normal)) {
            return new AccessorConfig(false, false, List.of(), false, false);
        }
        boolean fluent = base.fluent();
        Boolean chainExplicit = null;
        List<String> prefixes = base.prefixes();
        for (var pair : normal.getPairs()) {
            String key = pair.getNameAsString();
            Expression val = pair.getValue();
            if (key.equals("fluent") && val.isBooleanLiteralExpr()) {
                fluent = val.asBooleanLiteralExpr().getValue();
            } else if (key.equals("chain") && val.isBooleanLiteralExpr()) {
                chainExplicit = val.asBooleanLiteralExpr().getValue();
            } else if (key.equals("prefix")) {
                List<String> parsed = parseStringList(val);
                if (parsed == null) return new AccessorConfig(false, false, List.of(), false, false);
                prefixes = parsed;
            } else {
                return new AccessorConfig(false, false, List.of(), false, false);
            }
        }
        boolean chain = chainExplicit != null ? chainExplicit : (fluent || base.chain());
        return new AccessorConfig(fluent, chain, prefixes, base.noIsPrefix(), true);
    }

    private static List<String> parseStringList(Expression expr) {
        if (expr.isStringLiteralExpr()) {
            return List.of(expr.asStringLiteralExpr().asString());
        }
        if (expr instanceof ArrayInitializerExpr arr) {
            var list = new ArrayList<String>(arr.getValues().size());
            for (var item : arr.getValues()) {
                if (!item.isStringLiteralExpr()) return null;
                list.add(item.asStringLiteralExpr().asString());
            }
            return List.copyOf(list);
        }
        return null;
    }

    private static String stripPrefix(String fieldName, List<String> prefixes) {
        if (prefixes == null || prefixes.isEmpty()) return fieldName;
        for (String prefix : prefixes) {
            if (prefix.isEmpty()) return fieldName;
            if (fieldName.startsWith(prefix) && fieldName.length() > prefix.length()) {
                char lastOfPrefix = prefix.charAt(prefix.length() - 1);
                char nextChar = fieldName.charAt(prefix.length());
                if (Character.isLetter(lastOfPrefix) && Character.isLowerCase(nextChar)) {
                    continue;
                }
                String rest = fieldName.substring(prefix.length());
                return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
            }
        }
        return null;
    }

    private String parseAccessLevel(Expression level) {
        try {
            var resolved = level instanceof FieldAccessExpr access ? access.resolve()
                    : level instanceof NameExpr simple ? simple.resolve() : null;
            if (resolved != null && resolved.isField()
                    && context.isLombok(context.typeEntity(resolved.asField().declaringType().asReferenceType()), "lombok.AccessLevel")) {
                String value = resolved.getName();
                if (Set.of("PUBLIC", "PROTECTED", "PACKAGE", "PRIVATE", "NONE").contains(value)) return value;
            }
        } catch (RuntimeException ignored) {}
        String raw = level.toString();
        if (raw.matches("(?:lombok\\.)?AccessLevel\\.(PUBLIC|PROTECTED|PACKAGE|PRIVATE|NONE)")) {
            return raw.substring(raw.lastIndexOf('.') + 1);
        }
        return null;
    }

    private String accessorAccess(FieldDeclaration field, AnnotationExpr typeAnnotation, String name, boolean implicit) {
        var annotation = field.getAnnotations().stream()
                .filter(a -> verifiedOrRecovered(a, "lombok." + name))
                .findFirst().orElse(typeAnnotation);
        if (annotation == null) return implicit ? "PUBLIC" : "NONE";
        if (annotation.isMarkerAnnotationExpr()) return "PUBLIC";
        Expression level;
        if (annotation instanceof SingleMemberAnnotationExpr single) level = single.getMemberValue();
        else if (annotation instanceof NormalAnnotationExpr normal) {
            level = null;
            for (var pair : normal.getPairs()) {
                if (pair.getNameAsString().equals("value")) level = pair.getValue();
                else if (!pair.getNameAsString().equals("lazy") || !pair.getValue().isBooleanLiteralExpr() || pair.getValue().asBooleanLiteralExpr().getValue()) {
                    throw new IllegalArgumentException();
                }
            }
            if (level == null) return "PUBLIC";
        } else throw new IllegalArgumentException();
        String parsed = parseAccessLevel(level);
        if (parsed != null) return parsed;
        throw new IllegalArgumentException();
    }

    private static String capitalize(String name) {
        if (name.isEmpty()) return name;
        if (name.length() > 1 && Character.isLowerCase(name.charAt(0))
                && (Character.isUpperCase(name.charAt(1)) || Character.isTitleCase(name.charAt(1)))) {
            return Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }
        return Character.toTitleCase(name.charAt(0)) + name.substring(1);
    }

    private static boolean suppressed(ClassOrInterfaceDeclaration type, Set<String> names, int arity) {
        return type.getMethods().stream().anyMatch(m -> names.stream().anyMatch(n -> n.equalsIgnoreCase(m.getNameAsString()))
                && (m.getParameters().size() == arity
                        || !m.getParameters().isEmpty() && m.getParameter(m.getParameters().size() - 1).isVarArgs()
                                && arity >= m.getParameters().size() - 1));
    }

    private void objectMembers(ClassOrInterfaceDeclaration type, boolean value, boolean includeToString, boolean includeEqualsHashCode) {
        boolean equalitySuppressed = !includeEqualsHashCode
                || type.getMethodsByName("equals").stream().anyMatch(m -> m.getParameters().size() == 1)
                || type.getMethodsByName("hashCode").stream().anyMatch(m -> m.getParameters().isEmpty());
        for (String method : List.of("toString", "hashCode", "equals", "canEqual")) {
            if (method.equals("toString") && !includeToString) continue;
            if (!method.equals("toString") && equalitySuppressed) continue;
            if (method.equals("canEqual") && (value || type.isFinal()) && type.getExtendedTypes().isEmpty()) continue;
            int count = Set.of("equals", "canEqual").contains(method) ? 1 : 0;
            if (type.getMethodsByName(method).stream().anyMatch(m -> m.getParameters().size() == count)) continue;
            var object = ErasedType.declared(JavaSymbolName.topLevelType("java.lang", "Object"));
            var name = JavaSymbolName.method(context.name(type), method, count == 1 ? List.of(object) : List.of());
            var entity = context.implicit(name, EntityKind.METHOD, type, "lombok.object-methods");
            if (count == 1) {
                var parameter = context.implicit(JavaSymbolName.parameter(name, 0), EntityKind.PARAMETER, entity, type, "lombok.equals-parameter");
                context.derive(entity, parameter, "has-parameter", type, "lombok.object-methods");
            }
        }
    }

    private BuilderOptions parseBuilderOptions(AnnotationExpr annotation, boolean isSuperBuilder) {
        boolean toBuilder = false;
        String builderClassName = "";
        String builderMethodName = "builder";
        String buildMethodName = "build";
        String setterPrefix = "";
        String access = "PUBLIC";
        if (annotation.isMarkerAnnotationExpr()) {
            return new BuilderOptions(true, false, builderClassName, builderMethodName, buildMethodName, setterPrefix, access);
        }
        if (!(annotation instanceof NormalAnnotationExpr normal)) {
            return new BuilderOptions(false, false, "", "", "", "", "");
        }
        for (var pair : normal.getPairs()) {
            String key = pair.getNameAsString();
            Expression val = pair.getValue();
            if (key.equals("toBuilder") && val.isBooleanLiteralExpr()) {
                toBuilder = val.asBooleanLiteralExpr().getValue();
            } else if (key.equals("builderClassName") && val.isStringLiteralExpr() && !isSuperBuilder) {
                builderClassName = val.asStringLiteralExpr().asString();
            } else if (key.equals("builderMethodName") && val.isStringLiteralExpr()) {
                builderMethodName = val.asStringLiteralExpr().asString();
            } else if (key.equals("buildMethodName") && val.isStringLiteralExpr()) {
                buildMethodName = val.asStringLiteralExpr().asString();
            } else if (key.equals("setterPrefix") && val.isStringLiteralExpr()) {
                setterPrefix = val.asStringLiteralExpr().asString();
            } else if (key.equals("access") && !isSuperBuilder) {
                String parsed = parseAccessLevel(val);
                if (parsed == null || parsed.equals("NONE")) return new BuilderOptions(false, false, "", "", "", "", "");
                access = parsed;
            } else {
                return new BuilderOptions(false, false, "", "", "", "", "");
            }
        }
        return new BuilderOptions(true, toBuilder, builderClassName, builderMethodName, buildMethodName, setterPrefix, access);
    }

    private void synthesizeClassBuilder(
            ClassOrInterfaceDeclaration type,
            List<VariableDeclarator> fields,
            AnnotationExpr annotation,
            BuilderOptions opts,
            boolean isSuperBuilder) {
        var entries = new ArrayList<BuilderEntry>(fields.size());
        for (var f : fields) {
            entries.add(new BuilderEntry(f.getNameAsString(), f.getType(), f, findSingularAnnotation(field(f).getAnnotations())));
        }
        synthesizeBuilderCommon(type, entries, null, annotation, opts, isSuperBuilder, type.isAbstract());
    }

    private void synthesizeSuperBuilder(
            ClassOrInterfaceDeclaration type,
            AnnotationExpr annotation,
            BuilderOptions opts,
            boolean makeFinal) {
        // Walk superclass chain (outermost @SuperBuilder ancestor -> subclass).
        var chain = new ArrayList<ClassOrInterfaceDeclaration>();
        var visited = new HashSet<JavaSymbolName>();
        ClassOrInterfaceDeclaration current = type;
        while (current != null && visited.add(context.name(current))) {
            boolean hasSuperBuilder = current == type || current.getAnnotations().stream()
                    .anyMatch(a -> a.getName().getIdentifier().equals("SuperBuilder")
                            && verifiedOrRecovered(a, "lombok.experimental.SuperBuilder"));
            if (!hasSuperBuilder) break;
            chain.add(current);
            if (current.getExtendedTypes().size() != 1) break;
            try {
                var ast = current.getExtendedTypes().get(0).resolve().asReferenceType()
                        .getTypeDeclaration().orElseThrow().toAst().orElse(null);
                current = ast instanceof ClassOrInterfaceDeclaration next ? next : null;
            } catch (RuntimeException unavailable) {
                break;
            }
        }
        Collections.reverse(chain);
        var entries = new ArrayList<BuilderEntry>();
        var seenFieldNames = new HashSet<String>();
        for (var cls : chain) {
            boolean clsMakeFinal = cls == type ? makeFinal
                    : fieldDefaultsConfig(cls, context.lombokConfig(cls)).makeFinal()
                            || cls.getAnnotations().stream().anyMatch(a -> verifiedOrRecovered(a, "lombok.Value"));
            for (var fd : cls.getFields()) {
                if (fd.isStatic()) continue;
                boolean isDefault = isBuilderDefault(fd);
                AnnotationExpr singular = findSingularAnnotation(fd.getAnnotations());
                for (var v : fd.getVariables()) {
                    String fn = v.getNameAsString();
                    if (fn.startsWith("$") || !seenFieldNames.add(fn)) continue;
                    if (v.getInitializer().isPresent() && isEffectiveFinal(fd, clsMakeFinal) && !isDefault) continue;
                    entries.add(new BuilderEntry(fn, v.getType(), v, singular));
                }
            }
        }
        synthesizeBuilderCommon(type, entries, null, annotation, opts, true, type.isAbstract());
    }

    private void synthesizeCallableBuilder(
            ClassOrInterfaceDeclaration owner,
            CallableDeclaration<?> callable,
            Type explicitReturnType,
            AnnotationExpr annotation,
            BuilderOptions opts) {
        var entries = new ArrayList<BuilderEntry>(callable.getParameters().size());
        for (var p : callable.getParameters()) {
            entries.add(new BuilderEntry(p.getNameAsString(), p.getType(), p, findSingularAnnotation(p.getAnnotations())));
        }
        synthesizeBuilderCommon(owner, entries, explicitReturnType, annotation, opts, false, false);
    }

    private void synthesizeBuilderCommon(
            ClassOrInterfaceDeclaration type,
            List<BuilderEntry> entries,
            Type explicitReturnType,
            AnnotationExpr annotation,
            BuilderOptions opts,
            boolean isSuperBuilder,
            boolean isAbstractOwner) {
        String defaultBuilderSimple;
        if (!opts.builderClassName().isEmpty()) {
            defaultBuilderSimple = opts.builderClassName();
        } else if (explicitReturnType != null) {
            String retSimple = explicitReturnType.isClassOrInterfaceType()
                    ? explicitReturnType.asClassOrInterfaceType().getNameAsString()
                    : type.getNameAsString();
            defaultBuilderSimple = retSimple + "Builder";
        } else {
            defaultBuilderSimple = type.getNameAsString() + "Builder";
        }
        var builderName = JavaSymbolName.memberType(context.name(type), defaultBuilderSimple);
        if (context.hasDeclaration(builderName)) {
            context.synthesisGap(type, "lombok.existing-builder-required");
            return;
        }
        String rulePrefix = isSuperBuilder ? "lombok.super-builder" : "lombok.builder";
        var builder = context.implicit(builderName, EntityKind.TYPE, type, rulePrefix + "-type");
        context.implicit(JavaSymbolName.constructor(builderName, List.of()), EntityKind.CONSTRUCTOR, builder, type, rulePrefix + "-no-args");

        Entity factory = null;
        String factoryMethodName = opts.builderMethodName();
        if (!isAbstractOwner && !factoryMethodName.isEmpty()) {
            var factoryName = JavaSymbolName.method(context.name(type), factoryMethodName, List.of());
            if (type.getMethodsByName(factoryMethodName).stream().noneMatch(m -> m.getParameters().isEmpty())) {
                factory = context.implicit(factoryName, EntityKind.METHOD, type, rulePrefix + "-factory");
                context.derive(factory, builder, "returns", type, rulePrefix + "-factory");
            }
        }

        Entity toBuilderEntity = null;
        if (opts.toBuilder() && !isAbstractOwner && explicitReturnType == null) {
            var toBuilderName = JavaSymbolName.method(context.name(type), "toBuilder", List.of());
            if (type.getMethodsByName("toBuilder").stream().noneMatch(m -> m.getParameters().isEmpty())) {
                toBuilderEntity = context.implicit(toBuilderName, EntityKind.METHOD, type, rulePrefix + "-to-builder");
                context.derive(toBuilderEntity, builder, "returns", type, rulePrefix + "-to-builder");
            }
        }

        Entity build = null;
        String buildMethodName = opts.buildMethodName();
        if (!isAbstractOwner && !buildMethodName.isEmpty()) {
            build = context.implicit(JavaSymbolName.method(builderName, buildMethodName, List.of()), EntityKind.METHOD, builder, type, rulePrefix + "-build");
            Entity returnTarget = type == null ? null : context.entity(type);
            if (explicitReturnType != null) {
                try {
                    var resolved = explicitReturnType.resolve();
                    if (resolved.isReferenceType()) {
                        returnTarget = context.typeEntity(resolved.asReferenceType().getTypeDeclaration().orElseThrow());
                    }
                } catch (RuntimeException ignored) {}
            }
            if (returnTarget != null) {
                context.derive(build, returnTarget, "returns", type, rulePrefix + "-build");
            }
        }
        context.implicit(JavaSymbolName.method(builderName, "toString", List.of()), EntityKind.METHOD, builder, type, rulePrefix + "-to-string");

        var methodsByName = new TreeMap<String, List<BuilderMethodSpec>>();
        for (var entry : entries) {
            String fieldName = entry.name();
            Node srcNode = entry.sourceNode();
            if (entry.singularAnnotation() != null) {
                synthesizeSingularBuilderMethods(type, builder, builderName, entry, opts.setterPrefix(), methodsByName);
                continue;
            }
            String setterMethodName = applySetterPrefix(fieldName, opts.setterPrefix());
            var erasedParam = context.erasedParameter(entry.type());
            var name = JavaSymbolName.method(builderName, setterMethodName, List.of(erasedParam));
            var setter = context.implicit(name, EntityKind.METHOD, builder, srcNode, "lombok.builder-setter");
            var parameter = context.implicit(JavaSymbolName.parameter(name, 0), EntityKind.PARAMETER, setter, srcNode, "lombok.builder-parameter");
            context.derive(setter, parameter, "has-parameter", srcNode, "lombok.builder-setter");
            context.derive(setter, builder, "returns", srcNode, "lombok.builder-setter");
            methodsByName.computeIfAbsent(setterMethodName, k -> new ArrayList<>(2))
                    .add(new BuilderMethodSpec(setter, List.of(entry.type()), false, false, false));
            if (srcNode instanceof VariableDeclarator vd && srcNode.findCompilationUnit().equals(type.findCompilationUnit())) {
                addGenerated(type, new Generated(setter, name, List.of(vd), List.of(parameter), "lombok.builder-setter", true, false));
            }
        }

        builders.computeIfAbsent(context.name(type), k -> new ArrayList<>(2))
                .add(new BuilderShape(type, builder, explicitReturnType, factoryMethodName, buildMethodName,
                        factory, toBuilderEntity, build, methodsByName, annotation));
    }

    private void synthesizeSingularBuilderMethods(
            ClassOrInterfaceDeclaration type,
            Entity builder,
            JavaSymbolName builderName,
            BuilderEntry entry,
            String setterPrefix,
            Map<String, List<BuilderMethodSpec>> methodsByName) {
        AnnotationExpr singularAnn = entry.singularAnnotation();
        String explicitSingular = parseSingularExplicitName(singularAnn);
        if (explicitSingular == null) {
            context.synthesisGap(singularAnn, "lombok.member-customization");
            return;
        }
        String singularName = explicitSingular.isEmpty() ? singularize(entry.name()) : explicitSingular;
        if (singularName == null || singularName.isEmpty()) {
            context.synthesisGap(singularAnn, "lombok.member-customization");
            return;
        }
        // Inspect the declared collection / map type.
        String rawFqn = null;
        try {
            var resolved = entry.type().resolve();
            if (resolved.isReferenceType()) {
                rawFqn = resolved.asReferenceType().getQualifiedName();
            }
        } catch (RuntimeException ignored) {}
        if (rawFqn == null && entry.type() instanceof ClassOrInterfaceType cit) {
            rawFqn = switch (cit.getNameAsString()) {
                case "List" -> "java.util.List";
                case "Set" -> "java.util.Set";
                case "Collection" -> "java.util.Collection";
                case "SortedSet" -> "java.util.SortedSet";
                case "NavigableSet" -> "java.util.NavigableSet";
                case "Iterable" -> "java.lang.Iterable";
                case "Map" -> "java.util.Map";
                case "SortedMap" -> "java.util.SortedMap";
                case "NavigableMap" -> "java.util.NavigableMap";
                default -> null;
            };
        }
        boolean isCollection = rawFqn != null && SINGULAR_COLLECTION_TYPES.contains(rawFqn);
        boolean isMap = rawFqn != null && SINGULAR_MAP_TYPES.contains(rawFqn);
        if (!isCollection && !isMap) {
            context.synthesisGap(singularAnn, "lombok.member-customization");
            return;
        }
        Node srcNode = entry.sourceNode();
        List<Type> typeArgs = entry.type() instanceof ClassOrInterfaceType cit
                ? cit.getTypeArguments().map(NodeList::stream).map(java.util.stream.Stream::toList).orElse(List.of())
                : List.of();

        if (isCollection) {
            Type elemAst = typeArgs.size() >= 1 ? unwrapWildcardAst(typeArgs.get(0)) : null;
            ErasedType elemErased = elemAst != null
                    ? safeErasedParameter(elemAst)
                    : ErasedType.declared(JavaSymbolName.topLevelType("java.lang", "Object"));
            String singleMethodName = applySetterPrefix(singularName, setterPrefix);
            var singleSym = JavaSymbolName.method(builderName, singleMethodName, List.of(elemErased));
            var singleEntity = context.implicit(singleSym, EntityKind.METHOD, builder, srcNode, "lombok.builder-singular");
            var singleParam = context.implicit(JavaSymbolName.parameter(singleSym, 0), EntityKind.PARAMETER, singleEntity, srcNode, "lombok.builder-parameter");
            context.derive(singleEntity, singleParam, "has-parameter", srcNode, "lombok.builder-singular");
            context.derive(singleEntity, builder, "returns", srcNode, "lombok.builder-singular");
            methodsByName.computeIfAbsent(singleMethodName, k -> new ArrayList<>(2))
                    .add(new BuilderMethodSpec(singleEntity, elemAst != null ? List.of(elemAst) : List.of(), false, false, false));

            // Bulk collection method: <plural>(Collection<? extends E>)
            String bulkMethodName = applySetterPrefix(entry.name(), setterPrefix);
            var bulkErased = ErasedType.declared(JavaSymbolName.topLevelType("java.util", "Collection"));
            var bulkSym = JavaSymbolName.method(builderName, bulkMethodName, List.of(bulkErased));
            var bulkEntity = context.implicit(bulkSym, EntityKind.METHOD, builder, srcNode, "lombok.builder-singular-all");
            var bulkParam = context.implicit(JavaSymbolName.parameter(bulkSym, 0), EntityKind.PARAMETER, bulkEntity, srcNode, "lombok.builder-parameter");
            context.derive(bulkEntity, bulkParam, "has-parameter", srcNode, "lombok.builder-singular-all");
            context.derive(bulkEntity, builder, "returns", srcNode, "lombok.builder-singular-all");
            methodsByName.computeIfAbsent(bulkMethodName, k -> new ArrayList<>(2))
                    .add(new BuilderMethodSpec(bulkEntity, List.of(), false, true, false));
        } else {
            Type keyAst = typeArgs.size() >= 2 ? unwrapWildcardAst(typeArgs.get(0)) : null;
            Type valAst = typeArgs.size() >= 2 ? unwrapWildcardAst(typeArgs.get(1)) : null;
            ErasedType keyErased = keyAst != null
                    ? safeErasedParameter(keyAst)
                    : ErasedType.declared(JavaSymbolName.topLevelType("java.lang", "Object"));
            ErasedType valErased = valAst != null
                    ? safeErasedParameter(valAst)
                    : ErasedType.declared(JavaSymbolName.topLevelType("java.lang", "Object"));
            String singleMethodName = applySetterPrefix(singularName, setterPrefix);
            var singleSym = JavaSymbolName.method(builderName, singleMethodName, List.of(keyErased, valErased));
            var singleEntity = context.implicit(singleSym, EntityKind.METHOD, builder, srcNode, "lombok.builder-singular");
            var kParam = context.implicit(JavaSymbolName.parameter(singleSym, 0), EntityKind.PARAMETER, singleEntity, srcNode, "lombok.builder-parameter");
            var vParam = context.implicit(JavaSymbolName.parameter(singleSym, 1), EntityKind.PARAMETER, singleEntity, srcNode, "lombok.builder-parameter");
            context.derive(singleEntity, kParam, "has-parameter", srcNode, "lombok.builder-singular");
            context.derive(singleEntity, vParam, "has-parameter", srcNode, "lombok.builder-singular");
            context.derive(singleEntity, builder, "returns", srcNode, "lombok.builder-singular");
            methodsByName.computeIfAbsent(singleMethodName, k -> new ArrayList<>(2))
                    .add(new BuilderMethodSpec(singleEntity,
                            keyAst != null && valAst != null ? List.of(keyAst, valAst) : List.of(),
                            false, false, false));

            // Bulk map method: <plural>(Map<? extends K, ? extends V>)
            String bulkMethodName = applySetterPrefix(entry.name(), setterPrefix);
            var bulkErased = ErasedType.declared(JavaSymbolName.topLevelType("java.util", "Map"));
            var bulkSym = JavaSymbolName.method(builderName, bulkMethodName, List.of(bulkErased));
            var bulkEntity = context.implicit(bulkSym, EntityKind.METHOD, builder, srcNode, "lombok.builder-singular-all");
            var bulkParam = context.implicit(JavaSymbolName.parameter(bulkSym, 0), EntityKind.PARAMETER, bulkEntity, srcNode, "lombok.builder-parameter");
            context.derive(bulkEntity, bulkParam, "has-parameter", srcNode, "lombok.builder-singular-all");
            context.derive(bulkEntity, builder, "returns", srcNode, "lombok.builder-singular-all");
            methodsByName.computeIfAbsent(bulkMethodName, k -> new ArrayList<>(2))
                    .add(new BuilderMethodSpec(bulkEntity, List.of(), false, false, true));
        }

        // Clear method: clear<CapitalizedPlural>()
        String clearMethodName = "clear" + capitalize(entry.name());
        var clearSym = JavaSymbolName.method(builderName, clearMethodName, List.of());
        var clearEntity = context.implicit(clearSym, EntityKind.METHOD, builder, srcNode, "lombok.builder-singular-clear");
        context.derive(clearEntity, builder, "returns", srcNode, "lombok.builder-singular-clear");
        methodsByName.computeIfAbsent(clearMethodName, k -> new ArrayList<>(2))
                .add(new BuilderMethodSpec(clearEntity, List.of(), true, false, false));
    }

    private ErasedType safeErasedParameter(Type t) {
        try {
            return context.erasedParameter(t);
        } catch (RuntimeException unavailable) {
            return ErasedType.declared(JavaSymbolName.topLevelType("java.lang", "Object"));
        }
    }

    private static Type unwrapWildcardAst(Type t) {
        if (t.isWildcardType()) {
            var w = t.asWildcardType();
            if (w.getExtendedType().isPresent()) return w.getExtendedType().orElseThrow();
            if (w.getSuperType().isPresent()) return w.getSuperType().orElseThrow();
            return null;
        }
        return t;
    }

    private static String applySetterPrefix(String name, String prefix) {
        if (prefix == null || prefix.isEmpty()) return name;
        return prefix + capitalize(name);
    }

    private static String parseSingularExplicitName(AnnotationExpr ann) {
        if (ann.isMarkerAnnotationExpr()) return "";
        if (ann instanceof SingleMemberAnnotationExpr single) {
            return single.getMemberValue().isStringLiteralExpr()
                    ? single.getMemberValue().asStringLiteralExpr().asString()
                    : null;
        }
        if (ann instanceof NormalAnnotationExpr normal) {
            String value = "";
            for (var pair : normal.getPairs()) {
                if (pair.getNameAsString().equals("value") && pair.getValue().isStringLiteralExpr()) {
                    value = pair.getValue().asStringLiteralExpr().asString();
                } else if (pair.getNameAsString().equals("ignoreNullCollections") && pair.getValue().isBooleanLiteralExpr()) {
                    // Runtime null check only; does not change signature.
                } else {
                    return null;
                }
            }
            return value;
        }
        return null;
    }

    static String singularize(String plural) {
        if (plural == null || plural.length() < 2) return null;
        String lower = plural.toLowerCase(Locale.ROOT);
        if (IRREGULAR_SINGULARS.containsKey(lower)) {
            return matchCase(plural, IRREGULAR_SINGULARS.get(lower));
        }
        if (lower.endsWith("ies") && plural.length() > 3) {
            char before = lower.charAt(lower.length() - 4);
            if ("aeiou".indexOf(before) < 0) {
                return plural.substring(0, plural.length() - 3) + "y";
            }
        }
        if (lower.endsWith("ches") || lower.endsWith("shes") || lower.endsWith("xes") || lower.endsWith("zes") || lower.endsWith("sses")) {
            if (plural.length() > 4) return plural.substring(0, plural.length() - 2);
        }
        if (lower.endsWith("s") && !lower.endsWith("ss") && !lower.endsWith("us") && !lower.endsWith("is")) {
            return plural.substring(0, plural.length() - 1);
        }
        return null;
    }

    private static String matchCase(String original, String replacement) {
        if (!original.isEmpty() && Character.isUpperCase(original.charAt(0))) {
            return Character.toUpperCase(replacement.charAt(0)) + replacement.substring(1);
        }
        return replacement;
    }

    private String parseStandardExceptionAccess(AnnotationExpr annotation) {
        if (annotation.isMarkerAnnotationExpr()) return "PUBLIC";
        if (!(annotation instanceof NormalAnnotationExpr normal)) return null;
        String access = "PUBLIC";
        for (var pair : normal.getPairs()) {
            if (pair.getNameAsString().equals("access")) {
                String parsed = parseAccessLevel(pair.getValue());
                if (parsed == null || parsed.equals("NONE")) return null;
                access = parsed;
            } else {
                return null;
            }
        }
        return access;
    }

    private void synthesizeStandardExceptionConstructors(ClassOrInterfaceDeclaration type, String access) {
        var stringErased = ErasedType.declared(JavaSymbolName.topLevelType("java.lang", "String"));
        var throwableErased = ErasedType.declared(JavaSymbolName.topLevelType("java.lang", "Throwable"));
        List<List<ErasedType>> paramSignatures = List.of(
                List.of(),
                List.of(stringErased),
                List.of(throwableErased),
                List.of(stringErased, throwableErased));
        List<List<String>> paramTypeNames = List.of(
                List.of(),
                List.of("java.lang.String"),
                List.of("java.lang.Throwable"),
                List.of("java.lang.String", "java.lang.Throwable"));
        for (int s = 0; s < paramSignatures.size(); s++) {
            var sig = paramSignatures.get(s);
            var ctorName = JavaSymbolName.constructor(context.name(type), sig);
            if (context.hasDeclaration(ctorName)) continue; // Existing handwritten constructor wins.
            var callable = context.implicit(ctorName, EntityKind.CONSTRUCTOR, type, "lombok.standard-exception-constructor");
            var params = new ArrayList<Entity>(sig.size());
            for (int i = 0; i < sig.size(); i++) {
                var param = context.implicit(JavaSymbolName.parameter(ctorName, i), EntityKind.PARAMETER, callable, type, "lombok.standard-exception-constructor-parameter");
                context.derive(callable, param, "has-parameter", type, "lombok.standard-exception-constructor");
                params.add(param);
            }
            generatedOwners.put(callable, type);
            generatedAccess.put(callable, access);
            addGenerated(type, new Generated(callable, ctorName, List.of(), params, "lombok.standard-exception-constructor", true, false, paramTypeNames.get(s)));
        }
    }

    boolean hasGeneratedConstructor(ClassOrInterfaceDeclaration type) {
        try {
            if (ownersWithGeneratedConstructor.contains(context.name(type))) return true;
        } catch (RuntimeException ignored) {}
        return generatedOwners.entrySet().stream().anyMatch(e -> sameOwner(e.getKey(), type) && e.getKey().kind() == EntityKind.CONSTRUCTOR);
    }

    private void constructor(ClassOrInterfaceDeclaration type, List<VariableDeclarator> fields, String rule, String access) {
        var name = JavaSymbolName.constructor(context.name(type), fields.stream().map(f -> context.erasedParameter(f.getType())).toList());
        if (context.hasDeclaration(name)) {
            context.synthesisGap(type, "lombok.constructor-collision");
            return;
        }
        var callable = context.implicit(name, EntityKind.CONSTRUCTOR, type, rule);
        generatedOwners.put(callable, type);
        generatedAccess.put(callable, access);
        addGenerated(type, new Generated(callable, name, fields, parameters(callable, name, fields, rule), rule, true, false));
    }

    private String extractConstructorAccess(AnnotationExpr annotation) {
        if (annotation instanceof NormalAnnotationExpr normal) {
            for (var p : normal.getPairs()) {
                if (p.getNameAsString().equals("access")) {
                    String parsed = parseAccessLevel(p.getValue());
                    if (parsed != null) return parsed;
                }
            }
        }
        return "PUBLIC";
    }

    private void member(ClassOrInterfaceDeclaration type, String method, List<VariableDeclarator> parameters,
                        List<VariableDeclarator> fields, String rule, boolean setter, boolean returnsOwner) {
        var name = JavaSymbolName.method(context.name(type), method, parameters.stream().map(f -> context.erasedParameter(f.getType())).toList());
        if (context.hasDeclaration(name)) return;
        var callable = context.implicit(name, EntityKind.METHOD, type, rule);
        if (returnsOwner) context.derive(callable, context.entity(type), "returns", type, rule);
        generatedOwners.put(callable, type);
        generatedMethodNames.put(callable, method);
        addGenerated(type, new Generated(callable, name, fields, parameters(callable, name, parameters, rule), rule, setter, returnsOwner));
    }

    private List<Entity> parameters(Entity callable, JavaSymbolName name, List<VariableDeclarator> fields, String rule) {
        var result = new ArrayList<Entity>();
        for (int i = 0; i < fields.size(); i++) {
            var field = fields.get(i);
            var parameter = context.implicit(JavaSymbolName.parameter(name, i), EntityKind.PARAMETER, callable, field, rule + "-parameter");
            context.derive(callable, parameter, "has-parameter", field, rule);
            result.add(parameter);
        }
        return result;
    }

    void relationships(CompilationUnit unit) {
        var unitGenerated = generatedByUnit.get(unit);
        if (unitGenerated == null || unitGenerated.isEmpty()) return;
        for (var g : unitGenerated) {
            for (int i = 0; i < g.fields().size(); i++) {
                var f = g.fields().get(i);
                if (f.findCompilationUnit().orElseThrow() != unit) continue;
                if (!g.rule().equals("lombok.builder-setter")) {
                    context.derive(g.callable(), context.entity(f), g.setter() ? "writes-field" : "reads-field", f, g.rule());
                }
                if (g.setter() && i < g.parameters().size()) {
                    context.derivedMemberTypes(f, g.parameters().get(i), "parameter-type", g.rule());
                } else if (!g.setter()) {
                    context.derivedMemberTypes(f, g.callable(), "returns", g.rule());
                }
            }
        }
    }

    private boolean sameOwner(Entity callable, ClassOrInterfaceDeclaration owner) {
        var recorded = generatedOwners.get(callable);
        if (recorded == null) return false;
        if (recorded == owner) return true;
        try { return context.name(recorded).equals(context.name(owner)); }
        catch (RuntimeException unavailable) { return false; }
    }

    /** Cache proven return types on Lombok-resolved calls so downstream JavaParser resolution
     * (e.g. order.getItems().stream() or service.findById(dto.getId())) sees the exact return type. */
    void prepareExpressions(CompilationUnit unit) {
        if (!hasPotentialLombokInModule && generated.isEmpty() && builders.isEmpty() && utilityClasses.isEmpty()) return;
        unit.walk(Node.TreeTraversal.POSTORDER, node -> {
            if (!(node instanceof MethodCallExpr call)) return;
            try {
                var returnType = inferredReturnType(call);
                if (returnType.isEmpty()) return;
                cacheResolvedType(call, returnType.orElseThrow());
            } catch (RuntimeException ignored) {}
        });
    }

    private static void cacheResolvedType(Expression node, ResolvedType resolvedType) {
        SwitchAndPatternInference.cacheResolvedType(node, resolvedType);
    }

    private Optional<ResolvedType> inferredReturnType(MethodCallExpr call) {
        var built = builderCall(call, 0);
        if (built.isPresent()) {
            if (built.orElseThrow().built()) {
                BuilderShape shape = built.orElseThrow().builder();
                if (shape.builtReturnAstType() != null) {
                    return Optional.of(shape.builtReturnAstType().resolve());
                }
                return Optional.of(new ReferenceTypeImpl(shape.owner().resolve()));
            }
            return Optional.empty();
        }
        var utility = resolveUtilityMethodDeclaration(call);
        if (utility.isPresent() && !utility.orElseThrow().getType().isVoidType()) {
            return Optional.of(utility.orElseThrow().getType().resolve());
        }
        var resolvedEntity = resolve(call);
        if (resolvedEntity.isEmpty()) return Optional.empty();
        var target = resolvedEntity.orElseThrow();
        var candidate = generatedByEntity.get(target);
        if (candidate != null) {
            if (candidate.returnsOwner()) {
                var owner = generatedOwners.get(target);
                if (owner != null) return Optional.of(new ReferenceTypeImpl(owner.resolve()));
            }
            if (!candidate.setter() && candidate.fields().size() == 1) {
                return Optional.of(candidate.fields().getFirst().getType().resolve());
            }
        }
        return Optional.empty();
    }

    Optional<Entity> resolveField(Expression expression) {
        if (!hasPotentialLombokInModule && loggers.isEmpty() && utilityClasses.isEmpty()) return Optional.empty();
        try {
            if (expression instanceof NameExpr name) {
                String id = name.getNameAsString();
                if (!hasLocalShadow(name, id)) {
                    for (Node current = name.getParentNode().orElse(null); current != null; current = current.getParentNode().orElse(null)) {
                        if (current instanceof TypeDeclaration<?> enclosing) {
                            var shape = loggers.get(context.name(enclosing));
                            if (shape != null && shape.fieldName().equals(id)) return Optional.of(shape.field());
                        }
                    }
                }
                for (Node current = name.getParentNode().orElse(null); current != null; current = current.getParentNode().orElse(null)) {
                    if (current instanceof ClassOrInterfaceDeclaration enclosing && isUtilityClass(enclosing)) {
                        var match = enclosing.getFields().stream().flatMap(f -> f.getVariables().stream())
                                .filter(v -> v.getNameAsString().equals(id)).findFirst();
                        if (match.isPresent()) return Optional.of(context.entity(match.orElseThrow()));
                    }
                }
            } else if (expression instanceof FieldAccessExpr access) {
                String id = access.getNameAsString();
                if (access.getScope() instanceof ThisExpr) {
                    for (Node current = access.getParentNode().orElse(null); current != null; current = current.getParentNode().orElse(null)) {
                        if (current instanceof TypeDeclaration<?> enclosing) {
                            var shape = loggers.get(context.name(enclosing));
                            if (shape != null && shape.fieldName().equals(id)) return Optional.of(shape.field());
                        }
                    }
                } else if (access.getScope() instanceof NameExpr || access.getScope() instanceof FieldAccessExpr) {
                    var ast = context.resolveTypeName(access.getScope(), access.getScope().toString()).toAst().orElse(null);
                    if (ast instanceof TypeDeclaration<?> owner) {
                        var shape = loggers.get(context.name(owner));
                        if (shape != null && shape.fieldName().equals(id)) return Optional.of(shape.field());
                    }
                    if (ast instanceof ClassOrInterfaceDeclaration owner && isUtilityClass(owner)) {
                        var match = owner.getFields().stream().flatMap(f -> f.getVariables().stream())
                                .filter(v -> v.getNameAsString().equals(id)).findFirst();
                        if (match.isPresent()) return Optional.of(context.entity(match.orElseThrow()));
                    }
                }
            }
        } catch (RuntimeException ignored) {}
        return Optional.empty();
    }

    private static boolean hasLocalShadow(Node site, String identifier) {
        for (Node current = site.getParentNode().orElse(null); current != null && !(current instanceof TypeDeclaration<?>); current = current.getParentNode().orElse(null)) {
            if (current instanceof CallableDeclaration<?> callable && callable.getParameters().stream().anyMatch(p -> p.getNameAsString().equals(identifier))) return true;
            if (current instanceof LambdaExpr lambda && lambda.getParameters().stream().anyMatch(p -> p.getNameAsString().equals(identifier))) return true;
            if (current instanceof com.github.javaparser.ast.stmt.CatchClause catchClause && catchClause.getParameter().getNameAsString().equals(identifier)) return true;
            if (current instanceof com.github.javaparser.ast.stmt.BlockStmt block) {
                for (var stmt : block.getStatements()) {
                    if (stmt.isAncestorOf(site)) break;
                    if (stmt.findAll(VariableDeclarator.class).stream().anyMatch(v -> v.getNameAsString().equals(identifier))) return true;
                }
            }
        }
        return false;
    }

    private Optional<LoggerShape> resolveLoggerReceiver(Expression scope, int depth) {
        if (depth > 16 || (!hasPotentialLombokInModule && loggers.isEmpty())) return Optional.empty();
        var field = resolveField(scope);
        if (field.isPresent()) {
            return loggers.values().stream().filter(s -> s.field().equals(field.orElseThrow())).findFirst();
        }
        if (scope instanceof MethodCallExpr nested && nested.getScope().isPresent()) {
            return resolveLoggerReceiver(nested.getScope().orElseThrow(), depth + 1);
        }
        return Optional.empty();
    }

    private Optional<Entity> resolveLoggerCall(MethodCallExpr call) {
        if (call.getScope().isEmpty() || (!hasPotentialLombokInModule && loggers.isEmpty())) return Optional.empty();
        String method = call.getNameAsString();
        if (!LOGGER_METHODS.contains(method)) return Optional.empty();
        var shape = resolveLoggerReceiver(call.getScope().orElseThrow(), 0);
        if (shape.isEmpty()) return Optional.empty();
        var logger = shape.orElseThrow();
        var erasedArgs = new ArrayList<ErasedType>();
        for (var arg : call.getArguments()) {
            try {
                var t = arg.calculateResolvedType();
                if (t.isPrimitive()) erasedArgs.add(ErasedType.primitive(t.describe()));
                else if (t.isReferenceType()) erasedArgs.add(ErasedType.declared(context.typeName(t.asReferenceType().getTypeDeclaration().orElseThrow())));
                else erasedArgs.add(ErasedType.declared(JavaSymbolName.topLevelType("java.lang", "Object")));
            } catch (RuntimeException fallback) {
                erasedArgs.add(ErasedType.declared(JavaSymbolName.topLevelType("java.lang", "Object")));
            }
        }
        var symbol = JavaSymbolName.method(logger.loggerType(), method, erasedArgs);
        return Optional.of(context.implicit(symbol, EntityKind.METHOD, logger.field(), logger.owner(), "lombok.logger-call"));
    }

    private Optional<MethodDeclaration> resolveUtilityMethodDeclaration(MethodCallExpr call) {
        if (!hasPotentialLombokInModule && utilityClasses.isEmpty()) return Optional.empty();
        try {
            ClassOrInterfaceDeclaration owner = null;
            if (call.getScope().isEmpty()) {
                var enclosing = call.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
                if (enclosing != null && isUtilityClass(enclosing)) owner = enclosing;
            } else {
                var scope = call.getScope().orElseThrow();
                if (scope instanceof NameExpr || scope instanceof FieldAccessExpr) {
                    var ast = context.resolveTypeName(scope, scope.toString()).toAst().orElse(null);
                    if (ast instanceof ClassOrInterfaceDeclaration c && isUtilityClass(c)) owner = c;
                }
            }
            if (owner == null) return Optional.empty();
            var candidates = owner.getMethodsByName(call.getNameAsString()).stream()
                    .filter(m -> m.getParameters().size() == call.getArguments().size()).toList();
            if (candidates.size() == 1) return Optional.of(candidates.getFirst());
        } catch (RuntimeException ignored) {}
        return Optional.empty();
    }

    /** Exact-argument generated overloads participate before native resolution can select a
     * wider handwritten overload. No AST mutation, processor execution, or guessed coercion. */
    Optional<Entity> resolve(Expression expression) {
        if (expression instanceof MethodCallExpr call) {
            var loggerCall = resolveLoggerCall(call);
            if (loggerCall.isPresent()) return loggerCall;
            var utilityMethod = resolveUtilityMethodDeclaration(call);
            if (utilityMethod.isPresent()) return Optional.of(context.entity(utilityMethod.orElseThrow()));
        }
        if (!hasPotentialLombokInModule && generated.isEmpty() && builders.isEmpty()) return Optional.empty();
        boolean affectedGenerated = false;
        try {
            ClassOrInterfaceDeclaration receiver;
            List<Expression> arguments;
            String method;
            boolean constructor = expression instanceof ObjectCreationExpr;
            if (expression instanceof ObjectCreationExpr creation) {
                if (creation.getAnonymousClassBody().isPresent()) return Optional.empty();
                var ast = creation.getType().resolve().asReferenceType().getTypeDeclaration().orElseThrow().toAst().orElse(null);
                if (!(ast instanceof ClassOrInterfaceDeclaration type)) return Optional.empty();
                receiver = type;
                arguments = creation.getArguments();
                method = "";
            } else if (expression instanceof MethodCallExpr call) {
                var built = builderCall(call, 0);
                if (built.isPresent()) return Optional.of(built.orElseThrow().target());
                if (call.getTypeArguments().isPresent()) return Optional.empty();
                if (call.getScope().isPresent()) {
                    var scope = call.getScope().orElseThrow();
                    if (scope instanceof NameExpr name && context.isTypeName(scope, name.getNameAsString())) return Optional.empty();
                    var prior = scope instanceof MethodCallExpr nested ? builderCall(nested, 0) : Optional.<BuilderCall>empty();
                    if (prior.isPresent() && prior.orElseThrow().built()) {
                        BuilderShape priorShape = prior.orElseThrow().builder();
                        if (priorShape.builtReturnAstType() != null) {
                            var ast = priorShape.builtReturnAstType().resolve().asReferenceType()
                                    .getTypeDeclaration().orElseThrow().toAst().orElse(null);
                            if (!(ast instanceof ClassOrInterfaceDeclaration retType)) return Optional.empty();
                            receiver = retType;
                        } else {
                            receiver = priorShape.owner();
                        }
                    } else {
                        var resolved = scope instanceof MethodCallExpr nestedCall
                                ? inferredReturnType(nestedCall).orElseGet(scope::calculateResolvedType)
                                : scope.calculateResolvedType();
                        if (!resolved.isReferenceType()) return Optional.empty();
                        var ast = resolved.asReferenceType().getTypeDeclaration().orElseThrow().toAst().orElse(null);
                        if (!(ast instanceof ClassOrInterfaceDeclaration type)) return Optional.empty();
                        receiver = type;
                    }
                } else {
                    receiver = call.findAncestor(ClassOrInterfaceDeclaration.class).orElseThrow();
                    for (Node parent = call.getParentNode().orElse(null); parent != receiver; parent = parent.getParentNode().orElse(null)) {
                        if (parent == null || parent instanceof ObjectCreationExpr
                                || parent instanceof MethodDeclaration m && m.isStatic()
                                || parent instanceof InitializerDeclaration i && i.isStatic()
                                || parent instanceof FieldDeclaration f && f.isStatic()) return Optional.empty();
                    }
                }
                method = call.getNameAsString();
                arguments = call.getArguments();
            } else return Optional.empty();
            final ClassOrInterfaceDeclaration owner = !constructor ? inheritedAccessorOwner(receiver, method, arguments.size()) : receiver;
            List<Generated> ownerGenerated = generatedByOwnerName.getOrDefault(context.name(owner), List.of());
            boolean hasStdExceptionCtors = constructor && ownerGenerated.stream()
                    .anyMatch(g -> g.rule().equals("lombok.standard-exception-constructor"));
            boolean generatedConstructor = constructor && ownerGenerated.stream().anyMatch(g -> g.callable().kind() == EntityKind.CONSTRUCTOR);
            affectedGenerated = generatedConstructor || !constructor && ownerGenerated.stream().anyMatch(g ->
                    method.equals(generatedMethodNames.get(g.callable())));
            if (!owner.getTypeParameters().isEmpty() && (constructor || !arguments.isEmpty())
                    || constructor && !owner.getExtendedTypes().isEmpty() && !hasStdExceptionCtors
                    || !constructor && hasAncestorHandwrittenMethod(owner, method, arguments.size())) {
                if (affectedGenerated) throw new GeneratedAccessFailure();
                return Optional.empty();
            }
            var actualTypes = arguments.stream()
                    .map(arg -> arg instanceof MethodCallExpr nestedCall ? inferredReturnType(nestedCall).orElseGet(arg::calculateResolvedType) : arg.calculateResolvedType())
                    .toList();
            var actual = actualTypes.stream().map(ResolvedType::describe).toList();
            var matches = new ArrayList<Entity>();
            for (var candidate : ownerGenerated) {
                if (!sameOwner(candidate.callable(), owner)) continue;
                if (constructor != (candidate.callable().kind() == EntityKind.CONSTRUCTOR)) continue;
                if (candidate.rule().equals("lombok.standard-exception-constructor")) {
                    if (!matchesFixedParamTypes(candidate.fixedParamTypes(), actualTypes)) continue;
                    memberAccess(owner, candidate.callable(), expression);
                    matches.add(candidate.callable());
                    continue;
                }
                if (!constructor && !candidate.name().equals(JavaSymbolName.method(context.name(owner), method,
                        candidate.setter() ? candidate.fields().stream().map(f -> context.erasedParameter(f.getType())).toList() : List.of()))) {
                    continue;
                }
                var formal = (constructor || candidate.setter() ? candidate.fields() : List.<VariableDeclarator>of()).stream()
                        .map(f -> f.getType().resolve().describe()).toList();
                if (!formal.equals(actual)) {
                    if (constructor || !candidate.setter() || candidate.fields().size() != 1 || actualTypes.size() != 1) continue;
                    var expected = candidate.fields().getFirst().getType().resolve();
                    var supplied = actualTypes.getFirst();
                    boolean nullable = supplied.isNull() && (expected.isReferenceType() || expected.isArray());
                    var boxing = Map.of("boolean", "java.lang.Boolean", "byte", "java.lang.Byte", "short", "java.lang.Short",
                            "char", "java.lang.Character", "int", "java.lang.Integer", "long", "java.lang.Long",
                            "float", "java.lang.Float", "double", "java.lang.Double");
                    boolean boxed = expected.isPrimitive() && supplied.isReferenceType() && supplied.asReferenceType().getQualifiedName().equals(boxing.get(expected.describe()))
                            || supplied.isPrimitive() && expected.isReferenceType() && expected.asReferenceType().getQualifiedName().equals(boxing.get(supplied.describe()));
                    if (!nullable && !boxed) continue;
                    if (!owner.getExtendedTypes().isEmpty()) throw new GeneratedAccessFailure();
                }
                memberAccess(owner, candidate.callable(), expression);
                if (constructor && (candidate.rule().equals("lombok.builder-constructor") || owner.getAnnotations().stream()
                        .filter(NormalAnnotationExpr.class::isInstance)
                        .map(NormalAnnotationExpr.class::cast).flatMap(a -> a.getPairs().stream())
                        .anyMatch(p -> p.getNameAsString().equals("access") && !p.getValue().toString().matches("(?:lombok\\.)?AccessLevel.PUBLIC")))) {
                    throw new GeneratedAccessFailure();
                }
                if (!owner.isPublic() && !owner.findCompilationUnit().orElseThrow().getPackageDeclaration().map(p -> p.getNameAsString())
                        .equals(expression.findCompilationUnit().orElseThrow().getPackageDeclaration().map(p -> p.getNameAsString()))) {
                    throw new GeneratedAccessFailure();
                }
                for (Node enclosing = owner; enclosing != null; enclosing = enclosing.getParentNode().orElse(null)) {
                    if (enclosing instanceof ClassOrInterfaceDeclaration type && (type.isPrivate() || type.isProtected())) {
                        throw new GeneratedAccessFailure();
                    }
                }
                matches.add(candidate.callable());
            }
            if (matches.size() == 1) return Optional.of(matches.getFirst());
            if (affectedGenerated) throw new GeneratedAccessFailure();
            return Optional.empty();
        } catch (GeneratedAccessFailure unavailable) {
            throw unavailable;
        } catch (RuntimeException unavailable) {
            if (affectedGenerated) throw new GeneratedAccessFailure();
            return Optional.empty();
        }
    }

    private static boolean matchesFixedParamTypes(List<String> expectedFqns, List<ResolvedType> actualTypes) {
        if (expectedFqns.size() != actualTypes.size()) return false;
        for (int i = 0; i < expectedFqns.size(); i++) {
            String expected = expectedFqns.get(i);
            ResolvedType actual = actualTypes.get(i);
            if (actual.isNull()) continue;
            if (!actual.isReferenceType()) return false;
            var ref = actual.asReferenceType();
            if (ref.getQualifiedName().equals(expected)) continue;
            if (expected.equals("java.lang.Throwable")
                    && ref.getAllAncestors().stream().anyMatch(a -> a.getQualifiedName().equals("java.lang.Throwable"))) {
                continue;
            }
            return false;
        }
        return true;
    }

    private boolean hasAncestorHandwrittenMethod(ClassOrInterfaceDeclaration owner, String method, int arity) {
        var current = owner;
        var visited = new HashSet<JavaSymbolName>();
        while (visited.add(context.name(current)) && current.getExtendedTypes().size() == 1) {
            var parent = current.getExtendedTypes().get(0).resolve().asReferenceType().getTypeDeclaration().orElseThrow().toAst().orElse(null);
            if (!(parent instanceof ClassOrInterfaceDeclaration next)) return false;
            if (suppressed(next, Set.of(method), arity)) return true;
            current = next;
        }
        return false;
    }

    private ClassOrInterfaceDeclaration inheritedAccessorOwner(ClassOrInterfaceDeclaration receiver, String method, int arity) {
        var current = receiver;
        var visited = new HashSet<JavaSymbolName>();
        while (visited.add(context.name(current))) {
            final var at = current;
            var owned = generatedByOwnerName.getOrDefault(context.name(at), List.of());
            if (owned.stream().anyMatch(g -> sameOwner(g.callable(), at)
                    && method.equals(generatedMethodNames.get(g.callable()))
                    && (g.setter() ? g.fields().size() : 0) == arity)) {
                return current;
            }
            if (suppressed(current, Set.of(method), arity) || current.getExtendedTypes().size() != 1) return receiver;
            var parent = current.getExtendedTypes().get(0).resolve().asReferenceType().getTypeDeclaration().orElseThrow().toAst().orElse(null);
            if (!(parent instanceof ClassOrInterfaceDeclaration next)) return receiver;
            current = next;
        }
        throw new GeneratedAccessFailure();
    }

    private Optional<BuilderCall> builderCall(MethodCallExpr call, int depth) {
        if (depth >= 128) throw new GeneratedAccessFailure();
        if (call.getScope().isEmpty()) return Optional.empty();
        var scope = call.getScope().orElseThrow();
        String methodName = call.getNameAsString();

        // 1. Static builder factory call: Owner.builder() or Owner.<T>builder() or custom builderMethodName()
        if (call.getArguments().isEmpty() && (scope instanceof NameExpr || scope instanceof FieldAccessExpr)) {
            try {
                boolean isValue = false;
                if (scope instanceof NameExpr name) {
                    try { name.resolve(); isValue = true; } catch (com.github.javaparser.resolution.UnsolvedSymbolException ignored) {}
                } else if (scope instanceof FieldAccessExpr field) {
                    try { field.resolve(); isValue = true; } catch (com.github.javaparser.resolution.UnsolvedSymbolException ignored) {}
                }
                if (!isValue) {
                    var ast = context.resolveTypeName(scope, scope.toString()).toAst().orElse(null);
                    if (ast instanceof ClassOrInterfaceDeclaration owner) {
                        var ownerShapes = builders.get(context.name(owner));
                        if (ownerShapes != null) {
                            for (var shape : ownerShapes) {
                                if (shape.factory() != null && shape.factoryMethodName().equals(methodName)) {
                                    builderAccess(shape.owner(), shape.sourceAnnotation(), call);
                                    return Optional.of(new BuilderCall(shape.factory(), shape, false));
                                }
                            }
                        }
                    }
                    return Optional.empty();
                }
            } catch (GeneratedAccessFailure failed) {
                throw failed;
            } catch (RuntimeException unavailable) {
                // Fall through to instance checks (e.g. instance.toBuilder()).
            }
        }

        // 2. Instance toBuilder() call: instance.toBuilder()
        if (methodName.equals("toBuilder") && call.getArguments().isEmpty() && call.getTypeArguments().isEmpty()) {
            try {
                var resolved = scope instanceof MethodCallExpr nestedCall
                        ? inferredReturnType(nestedCall).orElseGet(scope::calculateResolvedType)
                        : scope.calculateResolvedType();
                if (resolved.isReferenceType()) {
                    var ast = resolved.asReferenceType().getTypeDeclaration().orElseThrow().toAst().orElse(null);
                    if (ast instanceof ClassOrInterfaceDeclaration owner) {
                        var ownerShapes = builders.get(context.name(owner));
                        if (ownerShapes != null) {
                            for (var shape : ownerShapes) {
                                if (shape.toBuilder() != null) {
                                    builderAccess(shape.owner(), shape.sourceAnnotation(), call);
                                    return Optional.of(new BuilderCall(shape.toBuilder(), shape, false));
                                }
                            }
                        }
                    }
                }
            } catch (GeneratedAccessFailure failed) {
                throw failed;
            } catch (RuntimeException ignored) {}
        }

        // 3. Chained builder setter / singular / clear / build calls
        if (call.getTypeArguments().isPresent()) return Optional.empty();
        if (!(scope instanceof MethodCallExpr nested)) return Optional.empty();
        var previous = builderCall(nested, depth + 1);
        if (previous.isEmpty() || previous.orElseThrow().built()) return Optional.empty();
        var shape = previous.orElseThrow().builder();
        builderAccess(shape.owner(), shape.sourceAnnotation(), call);
        if (shape.build() != null && methodName.equals(shape.buildMethodName()) && call.getArguments().isEmpty()) {
            return Optional.of(new BuilderCall(shape.build(), shape, true));
        }
        var specs = shape.methodsByName().get(methodName);
        if (specs == null || specs.isEmpty()) throw new GeneratedAccessFailure();
        var matched = new ArrayList<Entity>();
        for (var spec : specs) {
            if (matchesBuilderSpec(spec, call.getArguments())) {
                matched.add(spec.entity());
            }
        }
        if (matched.size() != 1) throw new GeneratedAccessFailure();
        return Optional.of(new BuilderCall(matched.getFirst(), shape, false));
    }

    private boolean matchesBuilderSpec(BuilderMethodSpec spec, List<Expression> args) {
        if (spec.isClear()) return args.isEmpty();
        if (spec.isSingularBulk()) {
            if (args.size() != 1) return false;
            var argType = resolveArgType(args.getFirst());
            if (argType.isNull()) return true;
            if (!argType.isReferenceType()) return false;
            var ref = argType.asReferenceType();
            return ref.getQualifiedName().equals("java.util.Collection")
                    || ref.getAllAncestors().stream().anyMatch(a -> a.getQualifiedName().equals("java.util.Collection"));
        }
        if (spec.isSingularMapBulk()) {
            if (args.size() != 1) return false;
            var argType = resolveArgType(args.getFirst());
            if (argType.isNull()) return true;
            if (!argType.isReferenceType()) return false;
            var ref = argType.asReferenceType();
            return ref.getQualifiedName().equals("java.util.Map")
                    || ref.getAllAncestors().stream().anyMatch(a -> a.getQualifiedName().equals("java.util.Map"));
        }
        if (spec.paramAstTypes().isEmpty()) {
            // Raw collection/map singular method accepts 1 or 2 Object args matching declared parameter count.
            return args.size() == 1 || args.size() == 2;
        }
        if (args.size() != spec.paramAstTypes().size()) return false;
        for (int i = 0; i < args.size(); i++) {
            var expected = spec.paramAstTypes().get(i).resolve();
            var actual = resolveArgType(args.get(i));
            if (!isBuilderArgAssignable(expected, actual)) return false;
        }
        return true;
    }

    private ResolvedType resolveArgType(Expression arg) {
        return arg instanceof MethodCallExpr nestedCall
                ? inferredReturnType(nestedCall).orElseGet(arg::calculateResolvedType)
                : arg.calculateResolvedType();
    }

    private static boolean isBuilderArgAssignable(ResolvedType expected, ResolvedType actual) {
        if (expected.isTypeVariable()) {
            if (actual.isNull()) return true;
            var tp = expected.asTypeVariable().asTypeParameter();
            for (var bound : tp.getBounds()) {
                if (bound.isExtends() && !bound.getType().isTypeVariable() && !bound.getType().isAssignableBy(actual)) {
                    return false;
                }
            }
            return true;
        }
        return expected.isAssignableBy(actual);
    }

    private void builderAccess(ClassOrInterfaceDeclaration owner, AnnotationExpr sourceAnnotation, Expression site) {
        boolean samePackage = owner.findCompilationUnit().orElseThrow().getPackageDeclaration().map(p -> p.getNameAsString())
                .equals(site.findCompilationUnit().orElseThrow().getPackageDeclaration().map(p -> p.getNameAsString()));
        if (!owner.isPublic() && !samePackage) throw new GeneratedAccessFailure();
        for (Node enclosing = owner; enclosing != null; enclosing = enclosing.getParentNode().orElse(null)) {
            if (enclosing instanceof ClassOrInterfaceDeclaration type && (type.isPrivate() || type.isProtected())) {
                throw new GeneratedAccessFailure();
            }
        }
        if (sourceAnnotation instanceof NormalAnnotationExpr normal) {
            boolean customized = normal.getPairs().stream()
                    .anyMatch(p -> p.getNameAsString().equals("access")
                            && !p.getValue().toString().matches("(?:lombok\\.)?AccessLevel.PUBLIC"));
            if (customized) throw new GeneratedAccessFailure();
        }
    }

    private static final class GeneratedAccessFailure extends com.github.javaparser.resolution.UnsolvedSymbolException {
        GeneratedAccessFailure() { super("Generated member applicability or accessibility requires evidence"); }
    }

    Optional<Entity> resolveReference(MethodReferenceExpr reference, ReferenceResolution references) {
        if (!hasPotentialLombokInModule && generated.isEmpty()) return Optional.empty();
        boolean affected = false;
        try {
            var functional = references.groundTarget(references.targetType(reference));
            var sam = com.github.javaparser.resolution.logic.FunctionalInterfaceLogic.getFunctionalMethod(functional).orElseThrow();
            var substitutions = functional.asReferenceType().typeParametersMap();
            var arguments = new ArrayList<>(sam.getParamTypes().stream().map(substitutions::replaceAll).toList());
            var expectedReturn = substitutions.replaceAll(sam.returnType());
            var value = ReferenceResolution.value(reference, reference.getScope().toString());
            boolean unbound = reference.getScope() instanceof TypeExpr && value.isEmpty();
            var receiver = value.isPresent() ? value.orElseThrow().getType()
                    : reference.getScope() instanceof TypeExpr type ? type.getType().resolve()
                    : reference.getScope().calculateResolvedType();
            if (!receiver.isReferenceType()) return Optional.empty();
            var ast = receiver.asReferenceType().getTypeDeclaration().orElseThrow().toAst().orElse(null);
            if (!(ast instanceof ClassOrInterfaceDeclaration owner)) return Optional.empty();
            String method = reference.getIdentifier();
            var candidates = generatedByOwnerName.getOrDefault(context.name(owner), List.of()).stream()
                    .filter(g -> sameOwner(g.callable(), owner) && method.equals(generatedMethodNames.get(g.callable()))).toList();
            if (candidates.isEmpty()) return Optional.empty();
            affected = true;
            if (unbound) {
                if (arguments.isEmpty() || !receiver.isAssignableBy(arguments.removeFirst())) throw new GeneratedAccessFailure();
            }
            var matches = new ArrayList<Entity>();
            for (var candidate : candidates) {
                int count = candidate.setter() ? candidate.fields().size() : 0;
                if (count != arguments.size()) continue;
                var typeMap = receiver.asReferenceType().typeParametersMap();
                boolean compatible = true;
                for (int i = 0; i < count; i++) {
                    if (!typeMap.replaceAll(candidate.fields().get(i).getType().resolve()).isAssignableBy(arguments.get(i))) {
                        compatible = false;
                    }
                }
                if (!compatible) continue;
                if (!expectedReturn.isVoid()) {
                    if (candidate.returnsOwner()) {
                        if (!expectedReturn.isAssignableBy(receiver)) continue;
                    } else if (candidate.setter() || !expectedReturn.isAssignableBy(typeMap.replaceAll(candidate.fields().getFirst().getType().resolve()))) {
                        continue;
                    }
                }
                memberAccess(owner, candidate.callable(), reference);
                matches.add(candidate.callable());
            }
            if (matches.size() != 1) throw new GeneratedAccessFailure();
            return Optional.of(matches.getFirst());
        } catch (GeneratedAccessFailure failed) {
            throw failed;
        } catch (RuntimeException unavailable) {
            if (affected) throw new GeneratedAccessFailure();
            return Optional.empty();
        }
    }

    private void memberAccess(ClassOrInterfaceDeclaration owner, Entity callable, Expression site) {
        String access = generatedAccess.getOrDefault(callable, "PUBLIC");
        boolean samePackage = owner.findCompilationUnit().orElseThrow().getPackageDeclaration().map(p -> p.getNameAsString())
                .equals(site.findCompilationUnit().orElseThrow().getPackageDeclaration().map(p -> p.getNameAsString()));
        boolean sameType = site.findAncestor(ClassOrInterfaceDeclaration.class).map(c -> context.name(c).equals(context.name(owner))).orElse(false);
        if (access.equals("PRIVATE") && !sameType || !access.equals("PUBLIC") && !access.equals("PRIVATE") && !samePackage) {
            throw new GeneratedAccessFailure();
        }
        if (!owner.isPublic() && !samePackage) throw new GeneratedAccessFailure();
        for (Node enclosing = owner; enclosing != null; enclosing = enclosing.getParentNode().orElse(null)) {
            if (enclosing instanceof ClassOrInterfaceDeclaration type && (type.isPrivate() || type.isProtected())) {
                throw new GeneratedAccessFailure();
            }
        }
    }

    private boolean isEffectiveFinal(FieldDeclaration field, boolean classMakeFinal) {
        return field.isFinal() || (classMakeFinal && !nonFinal(field) && !field.isStatic());
    }

    private boolean isBuilderDefault(FieldDeclaration field) {
        return field.getAnnotations().stream()
                .anyMatch(a -> a.getName().getIdentifier().equals("Default") && verifiedOrRecovered(a, "lombok.Builder.Default"));
    }

    private AnnotationExpr findSingularAnnotation(NodeList<AnnotationExpr> annotations) {
        for (var a : annotations) {
            if (a.getName().getIdentifier().equals("Singular") && verifiedOrRecovered(a, "lombok.Singular")) {
                return a;
            }
        }
        return null;
    }

    private boolean isVerifiedNullness(AnnotationExpr annotation) {
        try {
            var entity = context.typeEntity(annotation.resolve());
            if (entity.origin() == EntityOrigin.PROJECT) return false;
            String fqn = JavaTypeName.fromCanonical(entity.canonicalName())
                    .map(JavaTypeName::qualifiedName)
                    .orElse("");
            if (fqn.equals("lombok.NonNull")) return context.isLombok(entity, "lombok.NonNull");
            if (STANDARD_NULLNESS_FQNS.contains(fqn) && entity.origin() == EntityOrigin.DEPENDENCY) return true;
        } catch (RuntimeException ignored) {}
        if (context.isSourceRecoveryLombok(annotation, "lombok.NonNull")) return true;
        // Also recognize explicit/qualified standard nullness annotations (Jakarta/Javax/JetBrains/Spring) in recovery mode.
        for (String fqn : STANDARD_NULLNESS_FQNS) {
            if (!fqn.equals("lombok.NonNull") && LombokCandidates.isLombok(annotation, fqn, context.sourceTypeNames)) {
                return true;
            }
        }
        return false;
    }

    private boolean isUnverifiedLombok(AnnotationExpr annotation) {
        if (annotation.getNameAsString().startsWith("lombok.")) return true;
        try {
            var entity = context.typeEntity(annotation.resolve());
            return entity.origin() != EntityOrigin.PROJECT && entity.canonicalName().contains("lombok");
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean nonNull(FieldDeclaration field) {
        return field.getAnnotations().stream().anyMatch(this::isVerifiedNullness);
    }

    private boolean nonFinal(FieldDeclaration field) {
        return field.getAnnotations().stream().anyMatch(a -> verifiedOrRecovered(a, "lombok.experimental.NonFinal"));
    }

    private boolean verifiedOrRecovered(AnnotationExpr annotation, String name) {
        return exact(annotation, name) || context.isSourceRecoveryLombok(annotation, name);
    }

    private boolean exact(AnnotationExpr annotation, String name) {
        try { return context.isLombok(context.typeEntity(annotation.resolve()), name); }
        catch (RuntimeException failure) { return false; }
    }

    private static FieldDeclaration field(VariableDeclarator variable) {
        return (FieldDeclaration) variable.getParentNode().orElseThrow();
    }

    private static boolean optionsSupported(AnnotationExpr annotation) {
        if (annotation.isMarkerAnnotationExpr()) return true;
        if (!(annotation instanceof NormalAnnotationExpr normal)) return false;
        return normal.getPairs().stream().allMatch(p -> p.getNameAsString().equals("force") && p.getValue().isBooleanLiteralExpr()
                || p.getNameAsString().equals("access") && p.getValue().toString().matches("(?:lombok\\.)?AccessLevel\\.(PUBLIC|PROTECTED|PACKAGE|PRIVATE)")
                || Set.of("staticName", "staticConstructor").contains(p.getNameAsString()) && p.getValue().isStringLiteralExpr() && p.getValue().asStringLiteralExpr().asString().isEmpty());
    }
}
