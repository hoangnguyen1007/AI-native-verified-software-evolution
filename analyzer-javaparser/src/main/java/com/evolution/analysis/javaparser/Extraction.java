package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.source.SourceSpan;
import com.evolution.analysis.frontend.*;
import com.github.javaparser.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.type.TypeParameter;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.*;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import java.util.*;
import java.util.function.Supplier;

/** Per-request state. Traversal order never enters identity or output ordering. */
final class Extraction {
    private static final VersionedIdentifier VERSION = new VersionedIdentifier("frontend.javaparser", "3.26.1-m2.3");
    private static final Derivation DIRECT = new Derivation(DerivationKind.DIRECT, new VersionedIdentifier("java.source", "1"), List.of());
    private final FrontendRequest request;
    private final ResolutionEnvironment environment;
    private final Map<CompilationUnit, Unit> units = new IdentityHashMap<>();
    private final List<Unit> orderedUnits = new ArrayList<>();
    private final Map<Node, JavaSymbolName> names = new IdentityHashMap<>();
    private final Map<Node, Entity> nodes = new IdentityHashMap<>();
    private final Map<EntityIdentity, DeclarationRecord> declarations = new TreeMap<>();
    private final Set<EntityIdentity> duplicateDeclarations = new HashSet<>();
    private final Map<OccurrenceIdentity, RelationshipOccurrence> occurrences = new TreeMap<>();
    private final List<ObservationRecord> observations = new ArrayList<>();
    private final List<TypeUseRecord> types = new ArrayList<>();
    private final Set<Diagnostic> diagnostics = new TreeSet<>();
    private final List<SourceOutcome> rejected = new ArrayList<>();

    private static final class Unit {
        final SourceInput input;
        final OriginalSource source;
        final CompilationUnit ast;
        final Set<Diagnostic> diagnostics = new TreeSet<>();
        Unit(SourceInput input, CompilationUnit ast) { this.input = input; this.ast = ast; source = new OriginalSource(input.document().identity(), input.text()); }
    }
    private static final class MappingFailure extends RuntimeException {
        final SemanticStatus status; final String code;
        MappingFailure(SemanticStatus status, String code) { this.status = status; this.code = code; }
    }
    Extraction(FrontendRequest request) { this.request = Objects.requireNonNull(request); environment = new ResolutionEnvironment(request); }

    FrontendResult run() {
        parse();
        indexTypes();
        // Materialize declaration identities before emitting edges, so collisions can be withheld.
        for (var unit : orderedUnits) for (var node : unit.ast.stream().filter(this::isDeclaration).toList()) {
            try { entity(node); } catch (RuntimeException exception) { /* Recorded in the declaration observation pass below. */ }
        }
        for (var unit : orderedUnits) {
            for (var node : unit.ast.stream().filter(this::isDeclaration).toList()) observe(node, "declares", () -> entity(node), () -> owner(node));
            for (var node : unit.ast.findAll(MethodCallExpr.class)) observe(node, "calls", () -> callable(node.resolve()), () -> owner(node));
            for (var node : unit.ast.findAll(ObjectCreationExpr.class)) observe(node, "constructor-calls", () -> callable(node.resolve()), () -> owner(node));
            for (var node : unit.ast.findAll(ExplicitConstructorInvocationStmt.class)) observe(node, "constructor-calls", () -> callable(node.resolve()), () -> owner(node));
            for (var node : unit.ast.findAll(MethodReferenceExpr.class)) unsupported(node, "method-references");
            types.addAll(new TypeExtraction(this).extract(unit.ast));
            new FieldExtraction(this).extract(unit.ast);
        }
        var outcomes = new ArrayList<>(rejected);
        for (var unit : orderedUnits) outcomes.add(new SourceOutcome(unit.input.document().identity(),
                unit.diagnostics.isEmpty() ? SourceOutcome.State.PROCESSED : SourceOutcome.State.PARTIAL, List.copyOf(unit.diagnostics)));
        var coverage = FrontendRequest.CATEGORIES.stream().map(c -> {
            var kind = new RelationshipKind("java." + c);
            long attempted = observations.stream().filter(o -> o.category().equals(kind)).count();
            long emitted = occurrences.values().stream().filter(o -> o.relationship().kind().equals(kind)).count();
            return new CategoryCoverage(kind, Set.of("declares", "constructor-calls").contains(c) || TypeExtraction.CATEGORIES.contains(c) || FieldExtraction.CATEGORIES.contains(c) ? CategoryCoverage.Support.PARTIAL : c.equals("calls") ? CategoryCoverage.Support.IMPLEMENTED : CategoryCoverage.Support.UNSUPPORTED, attempted, emitted, attempted - emitted);
        }).toList();
        return new FrontendResult(request.manifest().identity(), VERSION,
                outcomes.stream().allMatch(s -> s.state() == SourceOutcome.State.PROCESSED) ? FrontendResult.State.COMPLETED : FrontendResult.State.PARTIAL,
                declarations.values().stream().filter(d -> !duplicateDeclarations.contains(d.entity().identity())).toList(),
                List.copyOf(occurrences.values()), observations, outcomes, coverage, List.copyOf(diagnostics), types);
    }

    private void parse() {
        var configuration = new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setTabSize(1).setPreprocessUnicodeEscapes(true).setSymbolResolver(new JavaSymbolSolver(environment.solver));
        var parser = new JavaParser(configuration);
        for (var input : request.sources()) {
            var parsed = parser.parse(input.text());
            if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
                var diagnostic = new Diagnostic(DiagnosticSeverity.ERROR, "java.parse-error", "Source syntax could not be parsed; recovered bindings are withheld", Optional.empty(), Map.of("document", input.document().identity().value()));
                diagnostics.add(diagnostic); rejected.add(new SourceOutcome(input.document().identity(), SourceOutcome.State.ERROR, List.of(diagnostic)));
                continue;
            }
            var ast = parsed.getResult().orElseThrow();
            // Java identifier-ignorable characters do not participate in symbol equality.
            ast.findAll(SimpleName.class).forEach(n -> n.setIdentifier(semanticIdentifier(n.getIdentifier())));
            ast.findAll(Name.class).forEach(n -> n.setIdentifier(semanticIdentifier(n.getIdentifier())));
            var unit = new Unit(input, ast); units.put(ast, unit); orderedUnits.add(unit);
        }
    }
    private static String semanticIdentifier(String raw) {
        var result = new StringBuilder(); raw.codePoints().filter(c -> !Character.isIdentifierIgnorable(c)).forEach(result::appendCodePoint); return result.toString();
    }
    private void indexTypes() {
        var indexed = new HashSet<String>();
        for (var unit : orderedUnits) for (TypeDeclaration<?> type : unit.ast.findAll(TypeDeclaration.class)) {
            if (inRecord(type)) continue;
            if (type.getFullyQualifiedName().isEmpty() || hasLocalOwner(type)) continue;
            String name = type.getFullyQualifiedName().orElseThrow();
            if (!indexed.add(name)) throw new FrontendInputException("frontend.duplicate-type", "Duplicate source type definitions in the supplied environment");
            environment.project.addDeclaration(name, type.resolve());
        }
    }
    private boolean hasLocalOwner(Node node) {
        for (Node current = node; current != null; current = current.getParentNode().orElse(null)) {
            if (current instanceof TypeDeclaration<?> t && !(t.getParentNode().orElse(null) instanceof CompilationUnit) && !(t.getParentNode().orElse(null) instanceof TypeDeclaration<?>)) return true;
            if (current instanceof ObjectCreationExpr) return true;
        }
        return false;
    }
    private boolean isDeclaration(Node node) {
        return node instanceof TypeDeclaration<?> || node instanceof CallableDeclaration<?> || node instanceof CompactConstructorDeclaration || node instanceof AnnotationMemberDeclaration
                || node instanceof VariableDeclarator v && v.getParentNode().orElse(null) instanceof FieldDeclaration
                || node instanceof Parameter || node instanceof TypeParameter || node instanceof EnumConstantDeclaration
                || node instanceof InitializerDeclaration || node instanceof LambdaExpr
                || node instanceof ObjectCreationExpr o && o.getAnonymousClassBody().isPresent();
    }

    private JavaSymbolName name(Node node) {
        var existing = names.get(node); if (existing != null) return existing;
        JavaSymbolName result;
        if (node instanceof CompilationUnit cu) result = JavaSymbolName.packageName(cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse(""));
        else if (node instanceof TypeDeclaration<?> type) {
            Node parent = type.getParentNode().orElseThrow();
            if (parent instanceof CompilationUnit cu) result = JavaSymbolName.topLevelType(cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse(""), type.getNameAsString());
            else if (parent instanceof TypeDeclaration<?> || parent instanceof ObjectCreationExpr) result = JavaSymbolName.memberType(name(parent), type.getNameAsString());
            else result = JavaSymbolName.localType(ownerName(type), unit(type).input.document().identity(), unit(type).source.start(type), type.getNameAsString(), false);
        } else if (node instanceof MethodDeclaration method) result = JavaSymbolName.method(name(enclosingType(node)), method.getNameAsString(), parameters(method));
        else if (node instanceof ConstructorDeclaration constructor) result = JavaSymbolName.constructor(name(enclosingType(node)), parameters(constructor));
        else if (node instanceof CompactConstructorDeclaration) result = JavaSymbolName.constructor(name(enclosingType(node)), parameters(((RecordDeclaration)enclosingType(node)).getParameters()));
        else if (node instanceof AnnotationMemberDeclaration member) result = JavaSymbolName.method(name(enclosingType(node)), member.getNameAsString(), List.of());
        else if (node instanceof VariableDeclarator variable) result = JavaSymbolName.field(name(enclosingType(node)), variable.getNameAsString());
        else if (node instanceof EnumConstantDeclaration constant) result = JavaSymbolName.field(name(enclosingType(node)), constant.getNameAsString());
        else if (node instanceof Parameter parameter) {
            Node parent = parameter.getParentNode().orElseThrow();
            if (parent instanceof CallableDeclaration<?> callable) result = JavaSymbolName.parameter(name(callable), callable.getParameters().indexOf(parameter));
            else if (parent instanceof RecordDeclaration record) result = JavaSymbolName.recordComponent(name(record), parameter.getNameAsString());
            else throw new MappingFailure(SemanticStatus.UNSUPPORTED, "java.parameter-owner");
        } else if (node instanceof TypeParameter parameter) {
            Node parent = parameter.getParentNode().orElseThrow();
            var siblings = parent.getChildNodes().stream().filter(n -> n instanceof TypeParameter).toList();
            result = JavaSymbolName.typeParameter(name(parent), siblings.indexOf(parameter));
        } else if (node instanceof LambdaExpr) result = executionName("lambda", node, "lambda", ownerName(node));
        else if (node instanceof InitializerDeclaration initializer) result = executionName("initializer", node, initializer.isStatic() ? "static" : "instance", name(enclosingType(node)));
        else if (node instanceof ObjectCreationExpr creation && creation.getAnonymousClassBody().isPresent()) result = JavaSymbolName.localType(ownerName(node), unit(node).input.document().identity(), unit(node).source.start(node), "", true);
        else throw new MappingFailure(SemanticStatus.UNSUPPORTED, "java.declaration-kind");
        names.put(node, result); return result;
    }
    private JavaSymbolName executionName(String kind, Node node, String syntax, JavaSymbolName owner) {
        return JavaSymbolName.execution(kind, owner, unit(node).input.document().identity(), unit(node).source.start(node), syntax);
    }
    private List<ErasedType> parameters(CallableDeclaration<?> callable) {
        return parameters(callable.getParameters());
    }
    private List<ErasedType> parameters(List<Parameter> parameters) {
        return parameters.stream().map(p -> {
            var type = erasedParameter(p.getType());
            return p.isVarArgs() ? ErasedType.array(type, 1) : type;
        }).toList();
    }
    private ErasedType erasedParameter(com.github.javaparser.ast.type.Type type) {
        if (type.isArrayType()) return ErasedType.array(erasedParameter(type.asArrayType().getComponentType()), 1);
        if (type.isClassOrInterfaceType()) {
            var declaration = resolveNamed(type.asClassOrInterfaceType());
            // Identity needs declaration erasure, not successful attribution of every generic argument.
            if (!declaration.isTypeParameter()) return ErasedType.declared(typeName(declaration.asReferenceType()));
        }
        return erased(type.resolve(), new HashSet<>());
    }
    private ErasedType erased(ResolvedType type, Set<String> visiting) {
        if (type.isPrimitive()) return ErasedType.primitive(type.describe());
        if (type.isArray()) return ErasedType.array(erased(type.asArrayType().getComponentType(), visiting), 1);
        if (type.isReferenceType()) return ErasedType.declared(typeName(type.asReferenceType().getTypeDeclaration().orElseThrow()));
        if (type.isTypeVariable()) {
            var variable = type.asTypeVariable().asTypeParameter();
            if (!visiting.add(variable.getContainerId() + ":" + variable.getName())) throw new MappingFailure(SemanticStatus.ERROR, "java.cyclic-erasure");
            var bounds = variable.getBounds();
            return bounds.isEmpty() ? ErasedType.declared(JavaSymbolName.topLevelType("java.lang", "Object")) : erased(bounds.getFirst().getType(), visiting);
        }
        throw new MappingFailure(SemanticStatus.UNSUPPORTED, "java.erasure");
    }
    private JavaSymbolName typeName(ResolvedReferenceTypeDeclaration type) {
        if (type.toAst().isPresent()) {
            Node ast = type.toAst().orElseThrow(); unit(ast); return name(ast);
        }
        return type.containerType().map(t -> JavaSymbolName.memberType(typeName(t), type.getName()))
                .orElseGet(() -> JavaSymbolName.topLevelType(type.getPackageName(), type.getName()));
    }
    private Node enclosingType(Node node) {
        for (Node parent = node.getParentNode().orElse(null); parent != null; parent = parent.getParentNode().orElse(null))
            if (parent instanceof TypeDeclaration<?> || parent instanceof ObjectCreationExpr o && o.getAnonymousClassBody().isPresent()) return parent;
        throw new MappingFailure(SemanticStatus.ERROR, "java.type-owner");
    }
    private Node ownerNode(Node node) {
        Node child = node;
        for (Node parent = node.getParentNode().orElse(null); parent != null; child = parent, parent = parent.getParentNode().orElse(null)) {
            if (parent instanceof VariableDeclarator v && v.getParentNode().orElse(null) instanceof FieldDeclaration) return parent;
            if (parent instanceof ObjectCreationExpr creation) {
                if (creation.getAnonymousClassBody().isPresent() && child instanceof BodyDeclaration<?>) return parent;
                continue;
            }
            if (isDeclaration(parent) || parent instanceof CompilationUnit) return parent;
        }
        throw new MappingFailure(SemanticStatus.ERROR, "java.owner");
    }
    private Entity owner(Node node) {
        if (inRecord(node)) throw new MappingFailure(SemanticStatus.UNSUPPORTED, "java.record-unsupported");
        if (inEnumConstantBody(node)) throw new MappingFailure(SemanticStatus.UNSUPPORTED, "java.enum-constant-body");
        Node owner = ownerNode(node);
        if (owner instanceof VariableDeclarator variable && !(node instanceof VariableDeclarator)) return fieldInitializer(variable);
        if (owner instanceof EnumConstantDeclaration constant) return enumInitializer(constant);
        return entity(owner);
    }
    private JavaSymbolName ownerName(Node node) {
        Node owner = ownerNode(node);
        if (owner instanceof VariableDeclarator variable) return fieldInitializerName(variable);
        if (owner instanceof EnumConstantDeclaration constant) return executionName("initializer", constant, "enum-constant", name(constant));
        return name(owner);
    }
    private JavaSymbolName fieldInitializerName(VariableDeclarator variable) {
        boolean isStatic = ((FieldDeclaration)variable.getParentNode().orElseThrow()).isStatic();
        return executionName("initializer", variable.getInitializer().orElseThrow(), isStatic ? "field-static" : "field-instance", name(variable));
    }
    private static boolean inEnumConstantBody(Node node) {
        Node child = node;
        for (Node parent = node.getParentNode().orElse(null); parent != null; child = parent, parent = parent.getParentNode().orElse(null))
            if (parent instanceof EnumConstantDeclaration && child instanceof BodyDeclaration<?>) return true;
        return false;
    }
    private static boolean inRecord(Node node) {
        for (Node current = node; current != null; current = current.getParentNode().orElse(null))
            if (current instanceof RecordDeclaration) return true;
        return false;
    }
    private Entity fieldInitializer(VariableDeclarator variable) {
        var initializer = variable.getInitializer().orElseThrow();
        return register(fieldInitializerName(variable), EntityKind.INITIALIZER,
                Optional.of(unit(variable).source.span(initializer)), unit(variable).source.slice(unit(variable).source.span(initializer)), DIRECT);
    }
    private Entity enumInitializer(EnumConstantDeclaration constant) {
        return register(executionName("initializer", constant, "enum-constant", name(constant)), EntityKind.INITIALIZER,
                Optional.of(unit(constant).source.span(constant)), constant.getNameAsString(), DIRECT);
    }
    Entity entity(Node node) {
        if (inRecord(node)) throw new MappingFailure(SemanticStatus.UNSUPPORTED, "java.record-unsupported");
        if (inEnumConstantBody(node)) throw new MappingFailure(SemanticStatus.UNSUPPORTED, "java.enum-constant-body");
        Entity cached = nodes.get(node);
        if (cached != null) { if (duplicateDeclarations.contains(cached.identity())) throw new MappingFailure(SemanticStatus.ERROR, "java.duplicate-declaration"); return cached; }
        JavaSymbolName symbol = name(node);
        EntityKind kind = node instanceof CompilationUnit ? EntityKind.PACKAGE : node instanceof TypeDeclaration<?> || node instanceof ObjectCreationExpr ? EntityKind.TYPE
                : node instanceof MethodDeclaration || node instanceof AnnotationMemberDeclaration ? EntityKind.METHOD : node instanceof ConstructorDeclaration || node instanceof CompactConstructorDeclaration ? EntityKind.CONSTRUCTOR
                : node instanceof Parameter p ? p.getParentNode().orElse(null) instanceof RecordDeclaration ? EntityKind.RECORD_COMPONENT : EntityKind.PARAMETER
                : node instanceof TypeParameter ? EntityKind.TYPE_PARAMETER : node instanceof LambdaExpr ? EntityKind.LAMBDA
                : node instanceof InitializerDeclaration ? EntityKind.INITIALIZER : EntityKind.FIELD;
        Optional<SourceSpan> span = node instanceof CompilationUnit ? Optional.empty() : Optional.of(unit(node).source.span(node));
        Derivation derivation = node instanceof CompilationUnit ? new Derivation(DerivationKind.DERIVED, new VersionedIdentifier("java.package-scope", "1"), List.of(request.module().value())) : DIRECT;
        var entity = register(symbol, kind, span, span.map(s -> unit(node).source.slice(s)).orElse(""), derivation);
        checkDeclarationTypes(node, entity);
        nodes.put(node, entity); return entity;
    }
    private void checkDeclarationTypes(Node node, Entity entity) {
        try {
            if (node instanceof MethodDeclaration method && !method.getType().isVoidType()) method.getType().resolve();
            if (node instanceof VariableDeclarator variable) variable.getType().resolve();
            if (node instanceof Parameter parameter && !parameter.getType().isUnknownType()) parameter.getType().resolve();
            if (node instanceof CallableDeclaration<?> callable) {
                for (var parameter : callable.getParameters()) parameter.getType().resolve();
                for (var thrown : callable.getThrownExceptions()) thrown.resolve();
            }
            if (node instanceof TypeParameter parameter) for (var bound : parameter.getTypeBound()) bound.resolve();
            if (node instanceof ClassOrInterfaceDeclaration type) {
                for (var extended : type.getExtendedTypes()) extended.resolve();
                for (var implemented : type.getImplementedTypes()) implemented.resolve();
            }
        } catch (RuntimeException exception) {
            boolean unresolved = exception instanceof UnsolvedSymbolException;
            var diagnostic = diagnostic(unresolved ? "java.declaration-type-unresolved" : "java.declaration-type-error", unresolved ? SemanticStatus.UNRESOLVED : SemanticStatus.ERROR, entity.declaration());
            var prior = declarations.get(entity.identity());
            declarations.put(entity.identity(), new DeclarationRecord(entity, prior.spelling(), SemanticStatus.PARTIAL, prior.derivation(),
                    List.of(new Uncertainty("java.declaration-type", "Declaration identity is known but type information is incomplete", List.of("verified declaration type"))), List.of(diagnostic)));
            unit(node).diagnostics.add(diagnostic); diagnostics.add(diagnostic);
        }
    }
    private Entity register(JavaSymbolName name, EntityKind kind, Optional<SourceSpan> span, String spelling, Derivation derivation) {
        var entity = Entity.create(EntityOrigin.PROJECT, EntityScope.project(request.module()), kind, name.canonicalName(), span);
        var record = new DeclarationRecord(entity, spelling, SemanticStatus.RESOLVED, derivation, List.of(), List.of());
        var prior = declarations.putIfAbsent(entity.identity(), record);
        if (prior != null && !prior.entity().declaration().equals(span)) {
            duplicateDeclarations.add(entity.identity()); throw new MappingFailure(SemanticStatus.ERROR, "java.duplicate-declaration");
        }
        return entity;
    }
    private Entity callable(ResolvedMethodLikeDeclaration resolved) {
        if (resolved.toAst().isPresent()) {
            Node node = resolved.toAst().orElseThrow();
            if (node instanceof CallableDeclaration<?> || node instanceof CompactConstructorDeclaration || node instanceof AnnotationMemberDeclaration) return entity(node);
            throw new MappingFailure(SemanticStatus.UNSUPPORTED, "java.implicit-callable");
        }
        var type = resolved.declaringType();
        if (type.toAst().isPresent()) throw new MappingFailure(SemanticStatus.UNSUPPORTED, "java.implicit-callable");
        var origin = environment.origin(type.getQualifiedName());
        if (environment.duplicateExternal(type.getQualifiedName())) diagnostics.add(new Diagnostic(DiagnosticSeverity.WARNING, "java.duplicate-binary-type", "Ordered classpath selected the first definition", Optional.empty(), Map.of("type", typeName(type).canonicalName())));
        var parameters = new ArrayList<ErasedType>();
        for (int i = 0; i < resolved.getNumberOfParams(); i++) parameters.add(erased(resolved.getParam(i).getType(), new HashSet<>()));
        var symbol = resolved instanceof ResolvedConstructorDeclaration ? JavaSymbolName.constructor(typeName(type), parameters) : JavaSymbolName.method(typeName(type), resolved.getName(), parameters);
        var entity = Entity.create(origin.kind(), origin.scope(), resolved instanceof ResolvedConstructorDeclaration ? EntityKind.CONSTRUCTOR : EntityKind.METHOD, symbol.canonicalName(), Optional.empty());
        declarations.putIfAbsent(entity.identity(), new DeclarationRecord(entity, resolved.getName(), SemanticStatus.RESOLVED, DIRECT, List.of(), List.of()));
        return entity;
    }

    Entity field(ResolvedValueDeclaration resolved) {
        if (resolved.toAst().isPresent()) {
            Node node = resolved.toAst().orElseThrow();
            if (node instanceof FieldDeclaration declaration) {
                var matching = declaration.getVariables().stream().filter(v -> v.getNameAsString().equals(resolved.getName())).toList();
                if (matching.size() != 1) throw new MappingFailure(SemanticStatus.ERROR,"java.field-declaration");
                node = matching.getFirst();
            }
            if (node instanceof VariableDeclarator variable && variable.getParentNode().orElse(null) instanceof FieldDeclaration
                    || node instanceof EnumConstantDeclaration) return entity(node);
            throw new MappingFailure(SemanticStatus.UNSUPPORTED,"java.field-declaration");
        }
        ResolvedReferenceTypeDeclaration type = resolved.isField() ? resolved.asField().declaringType().asReferenceType()
                : resolved.asEnumConstant().getType().asReferenceType().getTypeDeclaration().orElseThrow();
        if (type.toAst().isPresent()) throw new MappingFailure(SemanticStatus.UNSUPPORTED,"java.implicit-field");
        var origin = environment.origin(type.getQualifiedName());
        if (environment.duplicateExternal(type.getQualifiedName())) diagnostics.add(new Diagnostic(DiagnosticSeverity.WARNING,"java.duplicate-binary-type","Ordered classpath selected the first definition",Optional.empty(),Map.of("type",typeName(type).canonicalName())));
        var entity = Entity.create(origin.kind(),origin.scope(),EntityKind.FIELD,JavaSymbolName.field(typeName(type),resolved.getName()).canonicalName(),Optional.empty());
        declarations.putIfAbsent(entity.identity(),new DeclarationRecord(entity,resolved.getName(),SemanticStatus.RESOLVED,DIRECT,List.of(),List.of()));
        return entity;
    }
    Entity fieldOwner(Node node) {
        for (Node ancestor = node.getParentNode().orElse(null); ancestor != null; ancestor = ancestor.getParentNode().orElse(null))
            if (ancestor instanceof AnnotationExpr || ancestor instanceof AnnotationMemberDeclaration)
                throw new MappingFailure(SemanticStatus.UNSUPPORTED,"java.field-annotation-context");
        var owner = owner(node);
        if (!Set.of(EntityKind.METHOD,EntityKind.CONSTRUCTOR,EntityKind.INITIALIZER,EntityKind.LAMBDA).contains(owner.kind()))
            throw new MappingFailure(SemanticStatus.UNSUPPORTED,"java.field-execution-owner");
        return owner;
    }
    boolean isTypeName(Node context, String name) {
        try { return com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFactory.getContext(context,environment.solver).solveType(name).isSolved(); }
        catch (RuntimeException failure) { return false; }
    }
    void unmapped(Node node, String category, SemanticStatus status, String code) {
        observe(node,category,() -> { throw new MappingFailure(status,code); },() -> { throw new MappingFailure(status,code); });
    }
    ResolvedTypeDeclaration resolveNamed(com.github.javaparser.ast.type.ClassOrInterfaceType type) {
        var resolved = com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFactory.getContext(type, environment.solver)
                .solveType(type.getNameWithScope());
        if (!resolved.isSolved()) throw new UnsolvedSymbolException(type.getNameWithScope());
        return resolved.getCorrespondingDeclaration();
    }
    Entity typeEntity(ResolvedTypeDeclaration type) {
        if (type.toAst().isPresent()) return entity(type.toAst().orElseThrow());
        if (type.isTypeParameter()) throw new MappingFailure(SemanticStatus.UNSUPPORTED, "java.external-type-parameter");
        var reference = type.asReferenceType();
        var origin = environment.origin(reference.getQualifiedName());
        var entity = Entity.create(origin.kind(), origin.scope(), EntityKind.TYPE, typeName(reference).canonicalName(), Optional.empty());
        declarations.putIfAbsent(entity.identity(), new DeclarationRecord(entity, reference.getQualifiedName(), SemanticStatus.RESOLVED, DIRECT, List.of(), List.of()));
        return entity;
    }
    OriginalSource source(Node node) { return unit(node).source; }
    static SemanticStatus failureStatus(RuntimeException failure) {
        if (failure instanceof MappingFailure mapping) return mapping.status;
        if (failure instanceof UnsolvedSymbolException) return SemanticStatus.UNRESOLVED;
        if (failure instanceof UnsupportedOperationException) return SemanticStatus.UNSUPPORTED;
        return failure.getClass().getSimpleName().equals("MethodAmbiguityException") ? SemanticStatus.AMBIGUOUS : SemanticStatus.ERROR;
    }

    private Unit unit(Node node) {
        var cu = node.findCompilationUnit().orElseThrow(() -> new MappingFailure(SemanticStatus.ERROR, "java.source-membership"));
        var unit = units.get(cu);
        if (unit == null) throw new MappingFailure(SemanticStatus.ERROR, "java.source-membership");
        return unit;
    }
    private void unsupported(Node node, String category) {
        observe(node, category, () -> { throw new MappingFailure(SemanticStatus.UNSUPPORTED, "java.category-unsupported"); }, () -> owner(node));
    }
    void observe(Node node, String category, Supplier<Entity> targetSupplier, Supplier<Entity> ownerSupplier) {
        var unit = unit(node); Optional<SourceSpan> span;
        try { span = Optional.of(unit.source.span(node)); }
        catch (RuntimeException exception) { span = Optional.empty(); }
        Entity source = null, target = null; SemanticStatus status = SemanticStatus.RESOLVED;
        var local = new ArrayList<Diagnostic>();
        try { source = ownerSupplier.get(); target = targetSupplier.get(); }
        catch (UnsolvedSymbolException exception) { status = SemanticStatus.UNRESOLVED; local.add(diagnostic("java.unresolved", status, span)); }
        catch (MappingFailure failure) { status = failure.status; local.add(diagnostic(failure.code, status, span)); }
        catch (UnsupportedOperationException exception) { status = SemanticStatus.UNSUPPORTED; local.add(diagnostic("java.adapter-unsupported", status, span)); }
        catch (RuntimeException exception) {
            status = exception.getClass().getSimpleName().equals("MethodAmbiguityException") ? SemanticStatus.AMBIGUOUS : SemanticStatus.ERROR;
            local.add(new Diagnostic(status == SemanticStatus.ERROR ? DiagnosticSeverity.ERROR : DiagnosticSeverity.WARNING,
                    status == SemanticStatus.ERROR ? "java.adapter-error" : "java.ambiguous", "Attribution did not produce a safely mapped fact", span, Map.of("exception", exception.getClass().getName())));
        }
        if (span.isEmpty()) local.add(diagnostic("java.missing-span", SemanticStatus.ERROR, span));
        Optional<OccurrenceIdentity> mapped = Optional.empty();
        String reference = span.map(unit.source::slice).orElse("");
        if (source != null && span.isPresent() && status != SemanticStatus.AMBIGUOUS && (target != null || !category.equals("declares"))) {
            RelationshipTarget targetState = target == null ? new RelationshipTarget.Unresolved("java:source:" + unit.input.document().identity().value() + ":" + unit.source.start(node)) : new RelationshipTarget.Resolved(target.identity());
            var relation = SemanticRelationship.create(source.identity(), new RelationshipKind("java." + category), targetState);
            var uncertainty = status == SemanticStatus.RESOLVED ? List.<Uncertainty>of() : List.of(new Uncertainty("java.attribution", "Attribution remains incomplete", List.of("verified declaration binding")));
            var occurrence = RelationshipOccurrence.create(relation, span.orElseThrow(), 0, status, DIRECT, uncertainty, local);
            if (occurrences.putIfAbsent(occurrence.identity(), occurrence) == null) mapped = Optional.of(occurrence.identity());
            else local.add(diagnostic("java.duplicate-fact", SemanticStatus.ERROR, span));
        }
        if (mapped.isEmpty() && local.isEmpty()) local.add(diagnostic("java.mapping", SemanticStatus.ERROR, span));
        unit.diagnostics.addAll(local); diagnostics.addAll(local);
        observations.add(new ObservationRecord(unit.input.document().identity(), new RelationshipKind("java." + category), span, status,
                target != null ? ObservationRecord.EvidenceState.VERIFIED : ObservationRecord.EvidenceState.MISSING,
                span.isPresent() ? ObservationRecord.EvidenceState.VERIFIED : ObservationRecord.EvidenceState.MISSING,
                mapped, reference, local));
    }
    private static Diagnostic diagnostic(String code, SemanticStatus status, Optional<SourceSpan> span) {
        return new Diagnostic(status == SemanticStatus.ERROR ? DiagnosticSeverity.ERROR : DiagnosticSeverity.WARNING, code,
                "Semantic observation requires additional evidence or support", span, Map.of());
    }
}
