package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.identity.EntityIdentity;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.frontend.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.type.*;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import java.util.*;
import java.util.function.Supplier;

/** Written declaration types share one tree walk for structure and evidence-backed leaf edges. */
final class TypeExtraction {
    static final Set<String> CATEGORIES = Set.of("has-parameter", "parameter-type", "returns", "field-type",
            "type-uses", "type-argument", "extends", "implements", "permits", "throws", "type-parameter-bound");
    private static final Set<String> ROOT_TYPE_ROLES = Set.of("extends", "implements", "permits", "throws");
    private final Extraction context;
    private final List<TypeUseRecord> result = new ArrayList<>();
    TypeExtraction(Extraction context) { this.context = context; }

    List<TypeUseRecord> extract(CompilationUnit unit) {
        for (var parameter : unit.findAll(Parameter.class)) {
            if (parameter.getParentNode().orElse(null) instanceof CallableDeclaration<?> callable) {
                context.observe(parameter,"has-parameter",() -> context.entity(parameter),() -> context.entity(callable));
                add(parameter,parameter.getType(),"parameter-type",parameter.isVarArgs());
            }
        }
        for (var method : unit.findAll(MethodDeclaration.class)) add(method,method.getType(),"returns",false);
        for (var member : unit.findAll(AnnotationMemberDeclaration.class)) add(member,member.getType(),"returns",false);
        for (var field : unit.findAll(VariableDeclarator.class))
            if (field.getParentNode().orElse(null) instanceof FieldDeclaration) add(field,field.getType(),"field-type",false);
        for (CallableDeclaration<?> callable : unit.findAll(CallableDeclaration.class))
            for (var type : callable.getThrownExceptions()) add(callable,type,"throws",false);
        for (var parameter : unit.findAll(TypeParameter.class))
            for (var bound : parameter.getTypeBound()) add(parameter,bound,"type-parameter-bound",false);
        for (var type : unit.findAll(ClassOrInterfaceDeclaration.class)) {
            for (var parent : type.getExtendedTypes()) add(type,parent,"extends",false);
            for (var implemented : type.getImplementedTypes()) add(type,implemented,"implements",false);
            for (var permitted : type.getPermittedTypes()) add(type,permitted,"permits",false);
        }
        for (var type : unit.findAll(EnumDeclaration.class))
            for (var implemented : type.getImplementedTypes()) add(type,implemented,"implements",false);
        return List.copyOf(result);
    }
    private void add(Node owner, Type type, String role, boolean variadic) {
        var mapped = map(type,owner,role,false);
        Optional<EntityIdentity> identity;
        try { identity = Optional.of(context.entity(owner).identity()); }
        catch (RuntimeException failure) { identity = Optional.empty(); }
        result.add(new TypeUseRecord(identity,new RelationshipKind("java."+role),context.source(type).span(type),mapped,variadic));
    }
    private JavaType map(Type type, Node owner, String role, boolean argument) {
        String spelling = context.source(type).slice(context.source(type).span(type));
        if (type instanceof PrimitiveType) return simple(JavaType.Kind.PRIMITIVE,spelling,List.of());
        if (type instanceof VoidType) return simple(JavaType.Kind.VOID,spelling,List.of());
        if (type instanceof ArrayType array) return simple(JavaType.Kind.ARRAY,spelling,List.of(map(array.getComponentType(),owner,role,argument)));
        if (type instanceof WildcardType wildcard) {
            if (wildcard.getExtendedType().isPresent()) return simple(JavaType.Kind.EXTENDS_WILDCARD,spelling,List.of(map(wildcard.getExtendedType().orElseThrow(),owner,role,argument)));
            if (wildcard.getSuperType().isPresent()) return simple(JavaType.Kind.SUPER_WILDCARD,spelling,List.of(map(wildcard.getSuperType().orElseThrow(),owner,role,argument)));
            return simple(JavaType.Kind.WILDCARD,spelling,List.of());
        }
        if (type instanceof IntersectionType intersection) return simple(JavaType.Kind.INTERSECTION,spelling,intersection.getElements().stream().map(t -> map(t,owner,role,argument)).toList());
        if (type instanceof UnionType union) return simple(JavaType.Kind.UNION,spelling,union.getElements().stream().map(t -> map(t,owner,role,argument)).toList());
        if (type instanceof ClassOrInterfaceType named) {
            Entity target = null; RuntimeException failure = null;
            try { target = context.typeEntity(context.resolveNamed(named)); }
            catch (RuntimeException exception) { failure = exception; }
            Entity selected = target; RuntimeException error = failure;
            Supplier<Entity> resolver = () -> { if (error != null) throw error; return selected; };
            var categories = new LinkedHashSet<String>(); categories.add(role); categories.add("type-uses");
            if (argument) categories.add("type-argument");
            for (var category : categories) context.observe(ROOT_TYPE_ROLES.contains(category) ? named : named.getName(),category,resolver,() -> context.entity(owner));
            // Supertype and throws edges select the written root; their arguments/qualifiers are type uses.
            String nestedRole = ROOT_TYPE_ROLES.contains(role) ? "type-uses" : role;
            var arguments = named.getTypeArguments().orElseGet(NodeList::new).stream().map(t -> map(t,owner,nestedRole,true)).toList();
            Optional<JavaType> qualifier = Optional.empty();
            if (named.getScope().isPresent() && isTypeQualifier(named.getScope().orElseThrow()))
                qualifier = Optional.of(map(named.getScope().orElseThrow(),owner,nestedRole,argument));
            SemanticStatus status = target == null ? Extraction.failureStatus(failure) : completeness(arguments,qualifier);
            return new JavaType(target == null ? JavaType.Kind.UNKNOWN : target.kind() == EntityKind.TYPE_PARAMETER ? JavaType.Kind.TYPE_VARIABLE : JavaType.Kind.DECLARED,
                    spelling,Optional.ofNullable(target).map(Entity::identity),arguments,qualifier,status);
        }
        context.observe(type,role,() -> { throw new UnsupportedOperationException("Unsupported written type"); },() -> context.entity(owner));
        return new JavaType(JavaType.Kind.UNKNOWN,spelling,Optional.empty(),List.of(),Optional.empty(),SemanticStatus.UNSUPPORTED);
    }
    private boolean isTypeQualifier(ClassOrInterfaceType scope) {
        if (scope.getTypeArguments().isPresent()) return true;
        try { context.resolveNamed(scope); return true; }
        catch (RuntimeException failure) { return scope.getScope().map(this::isTypeQualifier).orElse(false); }
    }
    private static JavaType simple(JavaType.Kind kind, String spelling, List<JavaType> children) {
        return new JavaType(kind,spelling,Optional.empty(),children,Optional.empty(),completeness(children,Optional.empty()));
    }
    private static SemanticStatus completeness(List<JavaType> children, Optional<JavaType> qualifier) {
        return children.stream().allMatch(t -> t.status() == SemanticStatus.RESOLVED) && qualifier.stream().allMatch(t -> t.status() == SemanticStatus.RESOLVED)
                ? SemanticStatus.RESOLVED : SemanticStatus.PARTIAL;
    }
}
