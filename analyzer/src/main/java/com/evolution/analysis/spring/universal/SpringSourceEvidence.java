package com.evolution.analysis.spring.universal;

import com.evolution.analysis.contract.analysis.AnalysisManifest;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.ingestion.IngestionEvidence;
import com.evolution.analysis.spring.SpringFrameworkEvidence;
import com.evolution.analysis.spring.condition.ConditionEvidence;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Indexed exact M2 evidence. Package spelling alone never establishes framework
 * origin.
 */
public final class SpringSourceEvidence {
    /**
     * Successful document traversal is local evidence. An unrelated missing
     * dependency
     * changes overall completion, not an already acquired declaration's metadata.
     */
    public boolean declarationParsed(EntityIdentity id) {
        if (Set.of(FrontendResult.State.INVALID_INPUT, FrontendResult.State.FAILED, FrontendResult.State.CANCELED)
                .contains(frontend.state()))
            return false;
        var row = declarations.get(id);
        if (row == null || row.entity().origin() != EntityOrigin.PROJECT || row.status() != SemanticStatus.RESOLVED
                || row.entity().declaration().isEmpty())
            return false;
        return parsedDocuments.contains(row.entity().declaration().orElseThrow().document());
    }

    public record Annotation(AnnotationUseRecord use, Optional<String> name, ConditionEvidence evidence) {
    }

    /**
     * Child-to-parent source declarations, including the verified prefix on failure.
     * Only exactly empty source marker interfaces can be discharged without an inherited-member provider.
     */
    public enum HierarchyProblem {
        NONE, SOURCE_DECLARATION_MISSING, SOURCE_TYPES_INCOMPLETE,
        SOURCE_SHAPE_UNSUPPORTED, PARENT_EVIDENCE_MISSING, PARENT_EVIDENCE_CONFLICT, CYCLE, LIMIT
    }

    public record SourceHierarchy(List<EntityIdentity> types, HierarchyProblem problem) {
        public SourceHierarchy {
            types = List.copyOf(types);
            Objects.requireNonNull(problem);
        }

        public boolean complete() {
            return problem == HierarchyProblem.NONE;
        }

        public boolean limited() {
            return problem == HierarchyProblem.LIMIT;
        }
    }

    private static final Pattern ORDINARY_CLASS=Pattern.compile("^\\s*(?:(?:public|protected|private|static|abstract|final|strictfp)\\s+)*"
            +"class\\s+[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*"
            +"(?:\\s+extends\\s+([\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}.$]*))?"
            +"(?:\\s+implements\\s+([\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}.$]*(?:\\s*,\\s*[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}.$]*)*))?\\s*\\{");
    private static final Pattern EMPTY_MARKER=Pattern.compile("^\\s*(?:(?:public|protected|private|static|strictfp)\\s+)*interface\\s+[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*\\s*\\{\\s*}\\s*$");
    private record DocumentImports(Map<String, String> explicitBySimpleName, Set<String> wildcards, Set<String> allImports) {}
    private static final Map<String, String> SIMPLE_SPRING_ANNOTATIONS = Map.ofEntries(
            Map.entry("Component", "org.springframework.stereotype.Component"),
            Map.entry("Service", "org.springframework.stereotype.Service"),
            Map.entry("Repository", "org.springframework.stereotype.Repository"),
            Map.entry("Controller", "org.springframework.stereotype.Controller"),
            Map.entry("RestController", "org.springframework.web.bind.annotation.RestController"),
            Map.entry("Configuration", "org.springframework.context.annotation.Configuration"),
            Map.entry("SpringBootApplication", "org.springframework.boot.autoconfigure.SpringBootApplication"),
            Map.entry("ComponentScan", "org.springframework.context.annotation.ComponentScan"),
            Map.entry("Profile", "org.springframework.context.annotation.Profile"),
            Map.entry("Primary", "org.springframework.context.annotation.Primary"),
            Map.entry("Fallback", "org.springframework.context.annotation.Fallback"),
            Map.entry("Qualifier", "org.springframework.beans.factory.annotation.Qualifier"),
            Map.entry("Lazy", "org.springframework.context.annotation.Lazy"),
            Map.entry("Order", "org.springframework.core.annotation.Order"),
            Map.entry("Autowired", "org.springframework.beans.factory.annotation.Autowired"),
            Map.entry("Inject", "jakarta.inject.Inject"),
            Map.entry("Named", "jakarta.inject.Named"),
            Map.entry("Value", "org.springframework.beans.factory.annotation.Value"),
            Map.entry("Resource", "jakarta.annotation.Resource"),
            Map.entry("Bean", "org.springframework.context.annotation.Bean"),
            Map.entry("ConditionalOnProperty", "org.springframework.boot.autoconfigure.condition.ConditionalOnProperty"),
            Map.entry("ConditionalOnExpression", "org.springframework.boot.autoconfigure.condition.ConditionalOnExpression"),
            Map.entry("ConditionalOnBean", "org.springframework.boot.autoconfigure.condition.ConditionalOnBean"),
            Map.entry("ConditionalOnMissingBean", "org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean"),
            Map.entry("ConditionalOnSingleCandidate", "org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate"),
            Map.entry("ConditionalOnClass", "org.springframework.boot.autoconfigure.condition.ConditionalOnClass"),
            Map.entry("ConditionalOnMissingClass", "org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass"),
            Map.entry("ConditionalOnWebApplication", "org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication"),
            Map.entry("ConditionalOnNotWebApplication", "org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication"),
            Map.entry("ConditionalOnResource", "org.springframework.boot.autoconfigure.condition.ConditionalOnResource"),
            Map.entry("ConditionalOnJava", "org.springframework.boot.autoconfigure.condition.ConditionalOnJava"),
            Map.entry("EnableAutoConfiguration", "org.springframework.boot.autoconfigure.EnableAutoConfiguration"),
            Map.entry("Scope", "org.springframework.context.annotation.Scope"),
            Map.entry("DependsOn", "org.springframework.context.annotation.DependsOn"),
            Map.entry("Import", "org.springframework.context.annotation.Import"),
            Map.entry("ImportResource", "org.springframework.context.annotation.ImportResource"),
            Map.entry("ConfigurationProperties", "org.springframework.boot.context.properties.ConfigurationProperties"),
            Map.entry("EnableConfigurationProperties", "org.springframework.boot.context.properties.EnableConfigurationProperties")
    );
    private static final Set<String> QUALIFIED_SPRING_ANNOTATIONS = buildQualifiedSpringAnnotations();
    private static Set<String> buildQualifiedSpringAnnotations() {
        var set = new HashSet<String>(SIMPLE_SPRING_ANNOTATIONS.values());
        set.add("javax.inject.Inject");
        set.add("javax.inject.Named");
        set.add("javax.annotation.Resource");
        return Set.copyOf(set);
    }
    private static final List<String> NON_SPRING_WILDCARD_PREFIXES = List.of(
            "lombok.", "org.junit.jupiter.", "org.apache.dubbo.", "com.alibaba.dubbo.",
            "javax.inject.", "jakarta.inject.", "com.google.inject.",
            "javax.enterprise.", "jakarta.enterprise.", "io.micronaut.",
            "javax.ws.rs.", "jakarta.ws.rs.");
    private static final List<String> JAVAX_EE_PREFIXES = List.of(
            "javax.inject.", "javax.annotation.", "javax.servlet.", "javax.persistence.",
            "javax.validation.", "javax.transaction.", "javax.jms.", "javax.mail.",
            "javax.websocket.", "javax.ws.rs.", "javax.xml.bind.", "javax.xml.ws.",
            "javax.ejb.", "javax.enterprise.", "javax.faces.", "javax.json.",
            "javax.batch.", "javax.security.enterprise.", "javax.interceptor.",
            "javax.decorator.", "javax.el.", "javax.activation.");
    private static final Set<String> JSR305_JAVAX_ANNOTATIONS = Set.of(
            "javax.annotation.Nullable", "javax.annotation.Nonnull",
            "javax.annotation.CheckForNull", "javax.annotation.ParametersAreNonnullByDefault",
            "javax.annotation.ParametersAreNullableByDefault", "javax.annotation.CheckReturnValue",
            "javax.annotation.OverridingMethodsMustInvokeSuper");
    private static final Pattern IMPORT_STMT = Pattern.compile(
            "^\\s*import\\s+(?:static\\s+)?([\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}.*]*)\\s*;");
    private final FrontendResult frontend;private final AnalysisManifest manifest;
    private final SpringFrameworkEvidence framework;
    private final SpringFrameworkEvidence.ValidationMode validationMode;
    private final Map<EntityIdentity,DeclarationRecord> declarations=new TreeMap<>();
    private final Map<EntityIdentity,List<Annotation>> annotations=new TreeMap<>();
    private final Map<EntityIdentity,List<EntityIdentity>> parents=new TreeMap<>();
    private final Map<EntityIdentity,EntityIdentity> owners=new TreeMap<>();
    private final Map<EntityIdentity,SpringFrameworkEvidence.Artifact> artifacts=new TreeMap<>();
    private final Set<SourceDocumentIdentity> parsedDocuments=new HashSet<>();
    private final Map<SourceDocumentIdentity,com.evolution.analysis.contract.source.SourceDocument> snapshotDocsById=new HashMap<>();
    private final Map<SourceDocumentIdentity,DocumentImports> documentImports=new HashMap<>();
    private final Map<Object,List<EntityIdentity>> annotationTargets=new HashMap<>();
    private final Map<EntityIdentity,TypeDeclarationRecord> typeShapesById=new HashMap<>();
    private final Map<SourceDocumentIdentity,List<Diagnostic>> diagnosticsByDoc=new HashMap<>();
    private final Map<EntityIdentity,List<TypeUseRecord>> typesByOwner=new HashMap<>();
    private final Map<EntityIdentity,List<SemanticRelationship>> relationsBySource=new HashMap<>();
    private final List<SemanticRelationship> relations;
    private final ContentDigest identity;
    public SpringSourceEvidence(AnalysisManifest manifest,FrontendResult frontend,SpringFrameworkEvidence framework) {
        this(manifest,frontend,framework,manifest.classpath(),List.of(),SpringFrameworkEvidence.ValidationMode.STRICT_R0_PINNED);
    }
    public SpringSourceEvidence(FrontendRequest request,FrontendResult frontend,SpringFrameworkEvidence framework){
        this(request.manifest(),frontend,framework,request.originClasspath(),allSourceInputs(request),SpringFrameworkEvidence.ValidationMode.STRICT_R0_PINNED);
    }
    public SpringSourceEvidence(FrontendRequest request,FrontendResult frontend,SpringFrameworkEvidence framework,SpringFrameworkEvidence.ValidationMode validationMode){
        this(request.manifest(),frontend,framework,request.originClasspath(),allSourceInputs(request),validationMode);
    }
    private static List<SourceInput> allSourceInputs(FrontendRequest request) {
        var all = new ArrayList<SourceInput>(request.sources());
        for (var reactor : request.reactorSources()) all.addAll(reactor.sources());
        return all;
    }
    private SpringSourceEvidence(AnalysisManifest manifest,FrontendResult frontend,SpringFrameworkEvidence framework,List<com.evolution.analysis.contract.analysis.ClasspathEntry> origins,List<SourceInput> sourceInputs,SpringFrameworkEvidence.ValidationMode validationMode){
        this.manifest=Objects.requireNonNull(manifest);this.frontend=Objects.requireNonNull(frontend);this.framework=Objects.requireNonNull(framework);
        this.validationMode=Objects.requireNonNull(validationMode);
        for (var s : frontend.sources()) {
            if (s.state() == SourceOutcome.State.PROCESSED || s.state() == SourceOutcome.State.PARTIAL) {
                parsedDocuments.add(s.document());
            }
        }
        for (var d : manifest.snapshot().documents()) snapshotDocsById.put(d.identity(), d);
        for (var input : sourceInputs) {
            documentImports.putIfAbsent(input.document().identity(), parseImports(input.text()));
        }
        for (var shape : frontend.typeDeclarations()) typeShapesById.putIfAbsent(shape.type(), shape);
        for (var diag : frontend.diagnostics()) {
            diag.span().ifPresent(sp -> diagnosticsByDoc.computeIfAbsent(sp.document(), k -> new ArrayList<>()).add(diag));
        }
        for (var t : frontend.types()) {
            t.owner().ifPresent(ownerId -> typesByOwner.computeIfAbsent(ownerId, k -> new ArrayList<>()).add(t));
        }
        var relationships = new ArrayList<SemanticRelationship>();
        frontend.occurrences().stream().filter(o -> o.status() == SemanticStatus.RESOLVED)
                .forEach(o -> relationships.add(o.relationship()));
        frontend.derivedRelationships().stream().filter(d -> d.status() == SemanticStatus.RESOLVED)
                .forEach(d -> relationships.add(d.relationship()));
        relations = relationships.stream().distinct().sorted().toList();
        for (var rel : relations)
            relationsBySource.computeIfAbsent(rel.source(), k -> new ArrayList<>()).add(rel);
        if (!manifest.identity().equals(frontend.analysis()))
            throw new IllegalArgumentException("Foreign frontend evidence");
        for (var artifact : framework.artifacts())
            if (origins.stream().noneMatch(c -> c.logicalName().equals(artifact.classpathLogicalName())
                    && c.contentDigest().equals(artifact.contentDigest())))
                throw new IllegalArgumentException("Artifact evidence is outside the exact classpath");
        identity = IngestionEvidence.digest(List.of("spring.source-index:m4u.2-v1", manifest.identity(),
                IngestionEvidence.digest(frontend), framework.identity()));
        var artifactsByScope = new HashMap<CanonicalIdentifier, SpringFrameworkEvidence.Artifact>();
        for (var a : framework.artifacts())
            artifactsByScope.putIfAbsent(a.entityScope(), a);
        for (var declaration : frontend.declarations()) {
            declarations.put(declaration.entity().identity(), declaration);
            if (declaration.entity().origin() == EntityOrigin.DEPENDENCY) {
                var matchedArtifact = artifactsByScope.get(declaration.entity().stableScope());
                if (matchedArtifact != null)
                    artifacts.put(declaration.entity().identity(), matchedArtifact);
            }
        }
        for (var relation : relationships())
            if (relation.target() instanceof RelationshipTarget.Resolved target) {
                if (Set.of("java.extends", "java.implements").contains(relation.kind().value()))
                    parents.computeIfAbsent(relation.source(), k -> new ArrayList<>()).add(target.target());
                if (relation.kind().value().equals("java.declares"))
                    owners.put(target.target(), relation.source());
            }
        frontend.occurrences().stream()
                .filter(o -> o.status() == SemanticStatus.RESOLVED
                        && o.relationship().kind().value().equals("java.annotated-with")
                        && o.relationship().target() instanceof RelationshipTarget.Resolved)
                .forEach(o -> annotationTargets
                        .computeIfAbsent(List.of(o.relationship().source(), o.span()), k -> new ArrayList<>())
                        .add(((RelationshipTarget.Resolved) o.relationship().target()).target()));
        for (var use : frontend.annotations()) {
            var resolved = annotationTargets.getOrDefault(List.of(use.owner(), use.span()), List.of()).stream()
                    .distinct().toList();
            Optional<String> name = resolved.size() == 1 && artifacts.containsKey(resolved.getFirst())
                    ? Optional.ofNullable(typeName(resolved.getFirst()))
                    : Optional.empty();
            if (name.isPresent()) {
                var artifact = artifacts.get(resolved.getFirst());
                String n = name.orElseThrow();
                boolean valid = n.startsWith("org.springframework.boot.")
                        ? artifact.groupId().equals("org.springframework.boot")
                        : n.startsWith("org.springframework.data.")
                                ? artifact.groupId().equals("org.springframework.data")
                                : n.startsWith("org.springframework.")
                                        ? artifact.groupId().equals("org.springframework")
                                        : n.startsWith("javax.inject.") ? artifact.groupId().equals("javax.inject")
                                                : n.startsWith("jakarta.inject.")
                                                        ? artifact.groupId().equals("jakarta.inject")
                                                        : n.startsWith("javax.annotation.")
                                                                ? Set.of("javax.annotation", "jakarta.annotation")
                                                                        .contains(artifact.groupId())
                                                                : n.startsWith("jakarta.annotation.")
                                                                        ? artifact.groupId()
                                                                                .equals("jakarta.annotation")
                                                                        : false;
                if (!valid)
                    name = Optional.empty();
            }
            if (name.isEmpty()
                    && validationMode == SpringFrameworkEvidence.ValidationMode.COMPATIBLE_FAMILY_OR_SOURCE_SCOPED
                    && framework.artifacts().isEmpty()) {
                if (resolved.size() == 1) {
                    String resolvedName = typeName(resolved.getFirst());
                    if (resolvedName != null && QUALIFIED_SPRING_ANNOTATIONS.contains(resolvedName)) {
                        name = Optional.of(resolvedName);
                    }
                } else {
                    name = sourceScopedSpringAnnotation(use, documentImports.get(use.span().document()));
                }
            }
            var document = Objects.requireNonNull(snapshotDocsById.get(use.span().document()));
            ConditionEvidence evidence = new ConditionEvidence.Source(document.identity(), document.contentDigest(),
                    Optional.of(use.span()), 0);
            annotations.computeIfAbsent(use.owner(), k -> new ArrayList<>()).add(new Annotation(use, name, evidence));
        }
    }

    private static DocumentImports parseImports(String source) {
        var explicit = new HashMap<String, String>();
        var wildcards = new HashSet<String>();
        var all = new LinkedHashSet<String>();
        if (source == null || source.isEmpty()) return new DocumentImports(Map.of(), Set.of(), Set.of());
        String stripped = source.replaceAll("(?s)/\\*.*?\\*/|//[^\\r\\n]*", "");
        for (String line : stripped.split("\\R")) {
            var matcher = IMPORT_STMT.matcher(line);
            if (!matcher.find()) continue;
            String imported = matcher.group(1).trim();
            if (imported.isEmpty()) continue;
            all.add(imported);
            if (imported.endsWith(".*")) {
                wildcards.add(imported.substring(0, imported.length() - 1));
            } else {
                int dot = imported.lastIndexOf('.');
                if (dot > 0 && dot + 1 < imported.length()) {
                    explicit.put(imported.substring(dot + 1), imported);
                }
            }
        }
        return new DocumentImports(Map.copyOf(explicit), Set.copyOf(wildcards), Set.copyOf(all));
    }
    private static Optional<String> sourceScopedSpringAnnotation(AnnotationUseRecord use, DocumentImports imports) {
        String sp = use.spelling().stripLeading();
        if (!sp.startsWith("@"))
            return Optional.empty();
        int i = 1;
        while (i < sp.length() && Character.isWhitespace(sp.charAt(i)))
            i++;
        int start = i;
        while (i < sp.length() && (Character.isJavaIdentifierPart(sp.charAt(i)) || sp.charAt(i) == '.'))
            i++;
        if (i <= start)
            return Optional.empty();
        String raw = sp.substring(start, i);
        if (raw.indexOf('.') >= 0) {
            return QUALIFIED_SPRING_ANNOTATIONS.contains(raw) ? Optional.of(raw) : Optional.empty();
        }
        if (imports != null) {
            String explicit = imports.explicitBySimpleName().get(raw);
            if (explicit != null) {
                return QUALIFIED_SPRING_ANNOTATIONS.contains(explicit) ? Optional.of(explicit) : Optional.empty();
            }
            if (!imports.wildcards().isEmpty()) {
                var wildcardMatches = new ArrayList<String>();
                for (String prefix : imports.wildcards()) {
                    String candidate = prefix + raw;
                    if (QUALIFIED_SPRING_ANNOTATIONS.contains(candidate)) {
                        wildcardMatches.add(candidate);
                    }
                }
                if (wildcardMatches.size() == 1) {
                    return Optional.of(wildcardMatches.getFirst());
                }
                if (wildcardMatches.size() > 1) {
                    return Optional.empty();
                }
                for (String prefix : imports.wildcards()) {
                    for (String nonSpring : NON_SPRING_WILDCARD_PREFIXES) {
                        if (prefix.startsWith(nonSpring)) {
                            return Optional.empty();
                        }
                    }
                }
            }
        }
        return Optional.ofNullable(SIMPLE_SPRING_ANNOTATIONS.get(raw));
    }
    /** Observed EE/Spring namespace signal (javax.* vs jakarta.*) across source imports, annotations and declarations. */
    public Optional<FrameworkGeneration.Namespace> observedNamespaceHint() {
        boolean hasJavax = false;
        boolean hasJakarta = false;
        for (var docImports : documentImports.values()) {
            for (String imp : docImports.allImports()) {
                if (isJakartaNamespace(imp)) hasJakarta = true;
                else if (isJavaxEeNamespace(imp)) hasJavax = true;
            }
        }
        for (var use : frontend.annotations()) {
            String sp = use.spelling().stripLeading();
            if (sp.startsWith("@")) {
                String raw = sp.substring(1).stripLeading();
                if (isJakartaNamespace(raw)) hasJakarta = true;
                else if (isJavaxEeNamespace(raw)) hasJavax = true;
            }
        }
        for (var list : annotations.values()) {
            for (var ann : list) {
                if (ann.name().isPresent()) {
                    String n = ann.name().get();
                    if (isJakartaNamespace(n)) hasJakarta = true;
                    else if (isJavaxEeNamespace(n)) hasJavax = true;
                }
            }
        }
        for (var decl : declarations.values()) {
            String fqn = JavaTypeName.fromCanonical(decl.entity().canonicalName()).map(JavaTypeName::qualifiedName).orElse(null);
            if (fqn != null) {
                if (isJakartaNamespace(fqn)) hasJakarta = true;
                else if (isJavaxEeNamespace(fqn)) hasJavax = true;
            }
        }
        if (hasJavax && !hasJakarta) return Optional.of(FrameworkGeneration.Namespace.JAVAX);
        if (hasJakarta && !hasJavax) return Optional.of(FrameworkGeneration.Namespace.JAKARTA);
        return Optional.empty();
    }
    private static boolean isJakartaNamespace(String name) {
        return name.startsWith("jakarta.");
    }
    private static boolean isJavaxEeNamespace(String name) {
        if (!name.startsWith("javax.") || JSR305_JAVAX_ANNOTATIONS.contains(name) || name.startsWith("javax.annotation.concurrent.")) {
            return false;
        }
        for (String prefix : JAVAX_EE_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }
    private static boolean isSuppressibleUnresolvedDiagnostic(Diagnostic diag) {
        if (diag.severity() == DiagnosticSeverity.ERROR) return false;
        String code = diag.code();
        return code.equals("java.unresolved")
                || code.equals("frontend.unresolved")
                || code.equals("java.source-recovery")
                || code.equals("java.fast-architecture")
                || code.endsWith(".unresolved");
    }

    public ContentDigest identity() {
        return identity;
    }

    public AnalysisManifest manifest() {
        return manifest;
    }

    public FrontendResult frontend() {
        return frontend;
    }

    public SpringFrameworkEvidence framework() {
        return framework;
    }

    public SpringFrameworkEvidence.ValidationMode validationMode() {
        return validationMode;
    }

    public Map<EntityIdentity, DeclarationRecord> declarations() {
        return Collections.unmodifiableMap(declarations);
    }

    public List<Annotation> annotations(EntityIdentity owner) {
        return List.copyOf(annotations.getOrDefault(owner, List.of()));
    }

    /**
     * JDK Override is declaration metadata, not an unresolved Spring annotation.
     */
    public boolean javaOverride(Annotation annotation) {
        for (var target : annotationTargets.getOrDefault(List.of(annotation.use().owner(), annotation.use().span()),
                List.of())) {
            var decl = declarations.get(target);
            if (decl != null && decl.entity().origin() == EntityOrigin.JDK
                    && "java.lang.Override".equals(typeName(target))) {
                return true;
            }
        }
        return false;
    }

    public Optional<SpringFrameworkEvidence.Artifact> artifact(EntityIdentity type) {
        return Optional.ofNullable(artifacts.get(type));
    }

    public Optional<SpringFrameworkEvidence.Artifact> annotationArtifact(Annotation annotation) {
        var targets = annotationTargets
                .getOrDefault(List.of(annotation.use().owner(), annotation.use().span()), List.of()).stream().distinct()
                .toList();
        return targets.size() == 1 ? artifact(targets.getFirst()) : Optional.empty();
    }

    public Optional<EntityIdentity> owner(EntityIdentity member) {
        return Optional.ofNullable(owners.get(member));
    }

    public String typeName(EntityIdentity type) {
        var d = declarations.get(type);
        return d == null ? null
                : JavaTypeName.fromCanonical(d.entity().canonicalName()).map(JavaTypeName::qualifiedName).orElse(null);
    }

    public List<SemanticRelationship> relationships() {
        return relations;
    }

    public Set<EntityIdentity> ancestry(EntityIdentity type, int limit) {
        var result = new TreeSet<EntityIdentity>();
        var pending = new ArrayDeque<EntityIdentity>();
        pending.add(type);
        while (!pending.isEmpty() && result.size() < limit) {
            var next = pending.removeFirst();
            if (result.add(next))
                pending.addAll(parents.getOrDefault(next, List.of()));
        }
        return Collections.unmodifiableSet(result);
    }

    /**
     * Close only ordinary, non-generic source superclass chains from exact
     * declaration,
     * written-type and relationship evidence. Never infer closure from a truncated
     * ancestry set.
     */
    public SourceHierarchy sourceHierarchy(EntityIdentity type, int limit) {
        if (limit < 1)
            throw new IllegalArgumentException("Positive hierarchy limit required");
        var chain = new ArrayList<EntityIdentity>();
        var seen = new HashSet<EntityIdentity>();
        var problem = HierarchyProblem.NONE;
        EntityIdentity current = type;
        while (true) {
            if (chain.size() >= limit)
                return new SourceHierarchy(chain, HierarchyProblem.LIMIT);
            if (!seen.add(current))
                return new SourceHierarchy(chain, HierarchyProblem.CYCLE);
            var declaration = declarations.get(current);
            if (declaration == null || declaration.entity().origin() != EntityOrigin.PROJECT
                    || declaration.entity().kind() != EntityKind.TYPE || declaration.entity().declaration().isEmpty())
                return new SourceHierarchy(chain, HierarchyProblem.SOURCE_DECLARATION_MISSING);
            chain.add(current);
            var id = current;
            var shapeRec = typeShapesById.get(id);
            boolean ordinary = shapeRec != null && shapeRec.kind() == TypeDeclarationRecord.Kind.CLASS
                    && shapeRec.independent();
            String header = declaration.spelling();
            for (var annotation : annotations(current))
                header = header.replace(annotation.use().spelling(), " ");
            var matcher = ORDINARY_CLASS.matcher(header.replaceAll("(?s)/\\*.*?\\*/|//[^\\r\\n]*", " "));
            boolean syntax = !header.contains("\\u") && matcher.find();
            if (problem == HierarchyProblem.NONE) {
                if (declaration.status() != SemanticStatus.RESOLVED)
                    problem = HierarchyProblem.SOURCE_TYPES_INCOMPLETE;
                else if (!ordinary || !syntax)
                    problem = HierarchyProblem.SOURCE_SHAPE_UNSUPPORTED;
            }
            var span=declaration.entity().declaration().orElseThrow();
            boolean suppressUnresolved = validationMode == SpringFrameworkEvidence.ValidationMode.COMPATIBLE_FAMILY_OR_SOURCE_SCOPED
                    && framework.artifacts().isEmpty();
            boolean blockingDiagnostics=diagnosticsByDoc.getOrDefault(span.document(), List.of()).stream()
                    .filter(d -> !suppressUnresolved || !isSuppressibleUnresolvedDiagnostic(d))
                    .map(d -> d.span().orElseThrow())
                    .anyMatch(s -> before(s.startLine(),s.startColumn(),span.endLine(),span.endColumn())
                            &&before(span.startLine(),span.startColumn(),s.endLine(),s.endColumn()));
            if(problem==HierarchyProblem.NONE&&blockingDiagnostics)problem=HierarchyProblem.SOURCE_TYPES_INCOMPLETE;
            var ownerTypes = typesByOwner.getOrDefault(id, List.of());
            var ownerRels = relationsBySource.getOrDefault(id, List.of());
            if (problem == HierarchyProblem.NONE) {
                var implemented = ownerTypes.stream().filter(t -> t.role().value().equals("java.implements")).toList();
                var interfaceEdges = ownerRels.stream().filter(r -> r.kind().value().equals("java.implements"))
                        .flatMap(r -> r.target() instanceof RelationshipTarget.Resolved target
                                ? java.util.stream.Stream.of(target.target())
                                : java.util.stream.Stream.empty())
                        .distinct().toList();
                int declared = matcher.group(2) == null ? 0 : matcher.group(2).split(",").length;
                if (declared > limit - chain.size())
                    problem = HierarchyProblem.LIMIT;
                else if (implemented.size() != declared || interfaceEdges.size() != declared)
                    problem = HierarchyProblem.PARENT_EVIDENCE_MISSING;
                else {
                    var targets = new HashSet<EntityIdentity>();
                    for (var implementedType : implemented) {
                        var value = implementedType.type();
                        if (value.status() != SemanticStatus.RESOLVED || value.target().isEmpty()) {
                            problem = HierarchyProblem.SOURCE_TYPES_INCOMPLETE;
                            break;
                        }
                        if (value.kind() != JavaType.Kind.DECLARED || !value.components().isEmpty()
                                || !emptySourceMarker(value.target().orElseThrow())) {
                            problem = HierarchyProblem.SOURCE_SHAPE_UNSUPPORTED;
                            break;
                        }
                        targets.add(value.target().orElseThrow());
                    }
                    if (problem == HierarchyProblem.NONE && !targets.equals(new HashSet<>(interfaceEdges)))
                        problem = HierarchyProblem.PARENT_EVIDENCE_CONFLICT;
                }
            }
            var written = ownerTypes.stream().filter(t -> t.role().value().equals("java.extends")).toList();
            var edges = ownerRels.stream().filter(r -> r.kind().value().equals("java.extends"))
                    .flatMap(r -> r.target() instanceof RelationshipTarget.Resolved t
                            ? java.util.stream.Stream.of(t.target())
                            : java.util.stream.Stream.empty())
                    .distinct().toList();
            if (written.isEmpty() && edges.isEmpty())
                return new SourceHierarchy(chain,
                        problem != HierarchyProblem.NONE ? problem
                                : matcher.group(1) == null ? HierarchyProblem.NONE
                                        : HierarchyProblem.PARENT_EVIDENCE_MISSING);
            if (syntax && matcher.group(1) == null)
                return new SourceHierarchy(chain, HierarchyProblem.PARENT_EVIDENCE_CONFLICT);
            if (written.size() != 1 || edges.size() != 1)
                return new SourceHierarchy(chain, HierarchyProblem.PARENT_EVIDENCE_MISSING);
            var parent = written.getFirst().type();
            if (parent.target().isEmpty() || !parent.target().orElseThrow().equals(edges.getFirst()))
                return new SourceHierarchy(chain, HierarchyProblem.PARENT_EVIDENCE_CONFLICT);
            if (problem == HierarchyProblem.NONE) {
                if (parent.status() != SemanticStatus.RESOLVED)
                    problem = HierarchyProblem.SOURCE_TYPES_INCOMPLETE;
                else if (parent.kind() != JavaType.Kind.DECLARED || !parent.components().isEmpty())
                    problem = HierarchyProblem.SOURCE_SHAPE_UNSUPPORTED;
            }
            current = edges.getFirst();
            var terminal = declarations.get(current);
            if (terminal != null && terminal.entity().origin() == EntityOrigin.JDK
                    && "java.lang.Object".equals(typeName(current)))
                return new SourceHierarchy(chain, problem);
        }
    }

    private boolean emptySourceMarker(EntityIdentity type) {
        var declaration = declarations.get(type);
        var shape = typeShapesById.get(type);
        if (declaration == null || declaration.status() != SemanticStatus.RESOLVED
                || declaration.entity().origin() != EntityOrigin.PROJECT || declaration.entity().declaration().isEmpty()
                || !declaration.uncertainties().isEmpty() || !declaration.diagnostics().isEmpty()
                || !annotations(type).isEmpty()
                || shape == null || shape.kind() != TypeDeclarationRecord.Kind.INTERFACE || !shape.independent())
            return false;
        String text = declaration.spelling();
        if (text.contains("\\u")
                || !EMPTY_MARKER.matcher(text.replaceAll("(?s)/\\*.*?\\*/|//[^\\r\\n]*", " ")).matches())
            return false;
        if (relationsBySource.getOrDefault(type, List.of()).stream()
                .anyMatch(r -> Set.of("java.extends", "java.implements", "java.declares").contains(r.kind().value())))
            return false;
        var span = declaration.entity().declaration().orElseThrow();
        boolean suppressUnresolved = validationMode == SpringFrameworkEvidence.ValidationMode.COMPATIBLE_FAMILY_OR_SOURCE_SCOPED
                && framework.artifacts().isEmpty();
        return diagnosticsByDoc.getOrDefault(span.document(), List.of()).stream()
                .filter(d -> !suppressUnresolved || !isSuppressibleUnresolvedDiagnostic(d))
                .map(d -> d.span().orElseThrow())
                .noneMatch(s -> before(s.startLine(), s.startColumn(), span.endLine(), span.endColumn())
                        && before(span.startLine(), span.startColumn(), s.endLine(), s.endColumn()));
    }

    private static boolean before(int line, int column, int otherLine, int otherColumn) {
        return line < otherLine || line == otherLine && column < otherColumn;
    }

    public ConditionEvidence evidence(EntityIdentity entity) {
        var declaration = declarations.get(entity);
        if (declaration != null && declaration.entity().declaration().isPresent()) {
            var span = declaration.entity().declaration().orElseThrow();
            var doc = snapshotDocsById.get(span.document());
            if (doc != null)
                return new ConditionEvidence.Source(span.document(), doc.contentDigest(), Optional.of(span), 0);
        }
        return UniversalSpringEvidence.derived(identity, "entity:" + entity.value());
    }
}
