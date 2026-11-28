package com.evolution.analysis.jaxrs;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.AnalysisIdentity;
import com.evolution.analysis.contract.identity.EntityIdentity;
import com.evolution.analysis.contract.identity.SourceDocumentIdentity;
import com.evolution.analysis.contract.semantic.EntityKind;
import com.evolution.analysis.contract.semantic.EntityOrigin;
import com.evolution.analysis.contract.semantic.RelationshipTarget;
import com.evolution.analysis.contract.semantic.SemanticStatus;
import com.evolution.analysis.contract.source.SourceSpan;
import com.evolution.analysis.frontend.AnnotationUseRecord;
import com.evolution.analysis.frontend.DeclarationRecord;
import com.evolution.analysis.frontend.FrontendRequest;
import com.evolution.analysis.frontend.FrontendResult;
import com.evolution.analysis.frontend.JavaTypeName;
import com.evolution.analysis.frontend.TypeDeclarationRecord;
import com.evolution.analysis.ingestion.IngestionEvidence;
import com.evolution.analysis.spring.condition.LiteralConditionAnnotation;
import java.util.*;

/**
 * Declaration-level HTTP endpoint mapper for open standard JAX-RS ({@code jakarta.ws.rs.*},
 * {@code javax.ws.rs.*}) resources (including concrete subclasses inheriting methods from
 * abstract resource classes/interfaces) and Servlet {@code @WebServlet} classes.
 */
public final class JaxRsRouteMapper {
    public enum Kind { JAX_RS, SERVLET }

    public record UnitInput(FrontendRequest request, FrontendResult frontend) {}

    public record Route(
            EntityIdentity controller,
            EntityIdentity handler,
            String module,
            String path,
            List<String> methods,
            List<String> consumes,
            List<String> produces,
            boolean implicitHead,
            Kind kind,
            Optional<EntityIdentity> inheritedFrom,
            String spec,
            String derivation) {
        public Route {
            methods = sorted(methods);
            consumes = sorted(consumes);
            produces = sorted(produces);
        }

        public ContentDigest identity() {
            return IngestionEvidence.digest(this);
        }
    }

    public record Result(List<Route> routes) {
        public Result {
            routes = List.copyOf(routes);
        }
    }

    private record ResolvedAnn(
            AnnotationUseRecord use,
            String qualifiedName,
            String spec,
            String derivation) {}

    private record DocImports(Map<String, String> explicit, Set<String> wildcards) {}

    private static final String JAKARTA_RS = "jakarta.ws.rs.";
    private static final String JAVAX_RS = "javax.ws.rs.";
    private static final String JAKARTA_SERVLET = "jakarta.servlet.annotation.WebServlet";
    private static final String JAVAX_SERVLET = "javax.servlet.annotation.WebServlet";

    private static final Set<String> HTTP_VERBS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");
    private static final Map<String, String> SERVLET_DO_METHODS = Map.of(
            "doGet", "GET",
            "doPost", "POST",
            "doPut", "PUT",
            "doDelete", "DELETE",
            "doHead", "HEAD",
            "doOptions", "OPTIONS",
            "doTrace", "TRACE");

    private static final Map<String, String> MEDIA_TYPE_VALUES = Map.ofEntries(
            Map.entry("WILDCARD", "*/*"),
            Map.entry("APPLICATION_JSON", "application/json"),
            Map.entry("APPLICATION_XML", "application/xml"),
            Map.entry("APPLICATION_ATOM_XML", "application/atom+xml"),
            Map.entry("APPLICATION_XHTML_XML", "application/xhtml+xml"),
            Map.entry("APPLICATION_FORM_URLENCODED", "application/x-www-form-urlencoded"),
            Map.entry("MULTIPART_FORM_DATA", "multipart/form-data"),
            Map.entry("APPLICATION_OCTET_STREAM", "application/octet-stream"),
            Map.entry("APPLICATION_SVG_XML", "application/svg+xml"),
            Map.entry("APPLICATION_JSON_PATCH_JSON", "application/json-patch+json"),
            Map.entry("TEXT_PLAIN", "text/plain"),
            Map.entry("TEXT_HTML", "text/html"),
            Map.entry("TEXT_XML", "text/xml"),
            Map.entry("SERVER_SENT_EVENTS", "text/event-stream"));

    private static final Set<String> SUPPORTED_SIMPLE_ANNOTATIONS = Set.of(
            "Path", "ApplicationPath", "Produces", "Consumes",
            "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS",
            "WebServlet");

    private JaxRsRouteMapper() {}

    public static Result map(FrontendRequest request, FrontendResult frontend, int maxRoutes) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(frontend);
        var mapped = mapAll(List.of(new UnitInput(request, frontend)), maxRoutes);
        return mapped.getOrDefault(request.manifest().identity(), new Result(List.of()));
    }

    public static Map<AnalysisIdentity, Result> mapAll(List<UnitInput> units, int maxRoutes) {
        if (maxRoutes < 1) throw new IllegalArgumentException("Positive route limit required");
        Objects.requireNonNull(units);
        if (units.isEmpty()) return Map.of();

        // Cross-module repository-wide semantic & import index
        var projectTypesById = new LinkedHashMap<EntityIdentity, DeclarationRecord>();
        var projectTypesByQfn = new HashMap<String, List<EntityIdentity>>();
        var typeShapes = new HashMap<EntityIdentity, TypeDeclarationRecord>();
        var methodNames = new HashMap<EntityIdentity, String>();
        var memberSignatureKeys = new HashMap<EntityIdentity, String>();
        var declaredMethodsByType = new HashMap<EntityIdentity, List<EntityIdentity>>();
        var ownerByMember = new HashMap<EntityIdentity, EntityIdentity>();
        var parentsByType = new HashMap<EntityIdentity, List<EntityIdentity>>();
        var unresolvedParentRefs = new HashMap<EntityIdentity, List<UnresolvedParentRef>>();
        var docImports = new HashMap<SourceDocumentIdentity, DocImports>();
        var packageByDoc = new HashMap<SourceDocumentIdentity, String>();
        var annotationsByOwner = new HashMap<EntityIdentity, List<ResolvedAnn>>();

        // First pass: index all sources, imports, declarations, type shapes, and annotation targets
        var resolvedAnnTargetsByUnit = new ArrayList<Map<List<Object>, List<String>>>(units.size());
        var resolvedTypeNames = new HashMap<EntityIdentity, String>();

        for (var unit : units) {
            var req = unit.request();
            var fe = unit.frontend();
            for (var src : req.sources()) {
                docImports.putIfAbsent(src.document().identity(), parseImports(src.text()));
            }
            for (var reactor : req.reactorSources()) {
                for (var src : reactor.sources()) {
                    docImports.putIfAbsent(src.document().identity(), parseImports(src.text()));
                }
            }
            for (var shape : fe.typeDeclarations()) {
                typeShapes.putIfAbsent(shape.type(), shape);
            }
            for (var m : fe.memberDeclarations()) {
                methodNames.putIfAbsent(m.member(), m.name());
            }
            for (var decl : fe.declarations()) {
                var e = decl.entity();
                String qfn = JavaTypeName.fromCanonical(e.canonicalName()).map(JavaTypeName::qualifiedName).orElse(null);
                if (qfn != null) {
                    resolvedTypeNames.putIfAbsent(e.identity(), qfn);
                }
                if (e.origin() != EntityOrigin.PROJECT) continue;
                if (e.kind() == EntityKind.TYPE) {
                    projectTypesById.putIfAbsent(e.identity(), decl);
                    if (qfn != null) {
                        projectTypesByQfn.computeIfAbsent(qfn, k -> new ArrayList<>()).add(e.identity());
                        if (e.declaration().isPresent()) {
                            int dot = qfn.lastIndexOf('.');
                            packageByDoc.putIfAbsent(e.declaration().orElseThrow().document(),
                                    dot > 0 ? qfn.substring(0, dot) : "");
                        }
                    }
                } else if (e.kind() == EntityKind.METHOD) {
                    methodNames.putIfAbsent(e.identity(), decl.spelling());
                    memberSignatureKeys.putIfAbsent(e.identity(), signatureKey(e.canonicalName(), decl.spelling()));
                }
            }

            var unitAnnTargets = new HashMap<List<Object>, List<String>>();
            for (var occ : fe.occurrences()) {
                if (occ.status() == SemanticStatus.RESOLVED
                        && occ.relationship().kind().value().equals("java.annotated-with")
                        && occ.relationship().target() instanceof RelationshipTarget.Resolved target) {
                    String annFqn = resolvedTypeNames.get(target.target());
                    if (annFqn != null) {
                        unitAnnTargets.computeIfAbsent(
                                List.of(occ.relationship().source(), occ.span()), k -> new ArrayList<>())
                                .add(annFqn);
                    }
                }
            }
            resolvedAnnTargetsByUnit.add(unitAnnTargets);
        }

        // Second pass: index hierarchy links (supports intra-module, cross-module, and unresolved recovery refs) and annotations
        for (int u = 0; u < units.size(); u++) {
            var fe = units.get(u).frontend();
            var unitAnnTargets = resolvedAnnTargetsByUnit.get(u);

            var allRels = new ArrayList<com.evolution.analysis.contract.semantic.SemanticRelationship>();
            fe.occurrences().forEach(o -> allRels.add(o.relationship()));
            fe.derivedRelationships().forEach(d -> allRels.add(d.relationship()));

            for (var rel : allRels) {
                String kind = rel.kind().value();
                if (kind.equals("java.declares") && rel.target() instanceof RelationshipTarget.Resolved t) {
                    declaredMethodsByType.computeIfAbsent(rel.source(), k -> new ArrayList<>()).add(t.target());
                    ownerByMember.putIfAbsent(t.target(), rel.source());
                } else if (kind.equals("java.extends") || kind.equals("java.implements")) {
                    if (rel.target() instanceof RelationshipTarget.Resolved t) {
                        if (projectTypesById.containsKey(t.target())) {
                            parentsByType.computeIfAbsent(rel.source(), k -> new ArrayList<>()).add(t.target());
                        } else {
                            // Cross-module dependency JAR target -> bridge back to matching PROJECT type by FQN if present
                            String parentFqn = resolvedTypeNames.get(t.target());
                            if (parentFqn != null) {
                                for (EntityIdentity projParent : projectTypesByQfn.getOrDefault(parentFqn, List.of())) {
                                    parentsByType.computeIfAbsent(rel.source(), k -> new ArrayList<>()).add(projParent);
                                }
                            }
                        }
                    } else if (rel.target() instanceof RelationshipTarget.Unresolved unres) {
                        var decl = projectTypesById.get(rel.source());
                        SourceDocumentIdentity doc = decl != null && decl.entity().declaration().isPresent()
                                ? decl.entity().declaration().orElseThrow().document()
                                : null;
                        unresolvedParentRefs.computeIfAbsent(rel.source(), k -> new ArrayList<>())
                                .add(new UnresolvedParentRef(unres.stableReference(), doc));
                    }
                }
            }

            for (var ann : fe.annotations()) {
                var targets = unitAnnTargets.getOrDefault(List.of(ann.owner(), ann.span()), List.of());
                Optional<ResolvedAnn> resolved = resolveStandardAnnotation(
                        ann, targets, docImports.get(ann.span().document()));
                resolved.ifPresent(r -> annotationsByOwner.computeIfAbsent(ann.owner(), k -> new ArrayList<>()).add(r));
            }
        }

        // Resolve any recoverable source-recovery unresolved parent references across known project types
        for (var entry : unresolvedParentRefs.entrySet()) {
            EntityIdentity child = entry.getKey();
            for (var ref : entry.getValue()) {
                resolveProjectTypeByReference(ref.ref(), ref.doc(), docImports, packageByDoc, projectTypesByQfn)
                        .ifPresent(parent -> parentsByType.computeIfAbsent(child, k -> new ArrayList<>()).add(parent));
            }
        }

        // Compute outputs per unit
        var results = new LinkedHashMap<AnalysisIdentity, Result>();
        int remainingBudget = maxRoutes;

        for (var unit : units) {
            var req = unit.request();
            var fe = unit.frontend();
            var unitAnalysis = req.manifest().identity();
            String moduleId = req.module().value();

            String appPath = findSingleApplicationPath(fe, annotationsByOwner);
            var unitRoutes = new LinkedHashSet<Route>();

            for (var decl : fe.declarations()) {
                if (remainingBudget <= 0) break;
                var entity = decl.entity();
                if (entity.origin() != EntityOrigin.PROJECT || entity.kind() != EntityKind.TYPE) continue;
                var shape = typeShapes.get(entity.identity());
                if (shape != null && (shape.abstractType() || shape.kind() != TypeDeclarationRecord.Kind.CLASS)) continue;

                // 1. Standard @WebServlet on concrete class
                var classAnns = annotationsByOwner.getOrDefault(entity.identity(), List.of());
                for (var ann : classAnns) {
                    if (ann.qualifiedName().equals(JAKARTA_SERVLET) || ann.qualifiedName().equals(JAVAX_SERVLET)) {
                        var patterns = parseServletUrlPatterns(ann.use().spelling());
                        if (!patterns.isEmpty()) {
                            var ancestry = bfsAncestry(entity.identity(), parentsByType, 16);
                            var methods = collectServletMethods(ancestry, declaredMethodsByType, methodNames);
                            for (String pattern : patterns) {
                                if (remainingBudget <= 0) break;
                                String normalizedPath = normalizePath(pattern);
                                if (!normalizedPath.isEmpty()) {
                                    unitRoutes.add(new Route(
                                            entity.identity(), entity.identity(), moduleId, normalizedPath,
                                            methods, List.of(), List.of(), methods.contains("GET"),
                                            Kind.SERVLET, Optional.empty(), ann.spec(), ann.derivation()));
                                    remainingBudget--;
                                }
                            }
                        }
                    }
                }

                // 2. Standard JAX-RS concrete resource (+ inherited abstract/interface endpoints)
                var ancestry = bfsAncestry(entity.identity(), parentsByType, 16);
                ClassPathInfo classPathInfo = findClassPath(ancestry, annotationsByOwner);
                boolean hasAnyJaxRsSignal = classPathInfo != null || hasMethodLevelJaxRsRoute(entity.identity(), declaredMethodsByType, annotationsByOwner);
                if (!hasAnyJaxRsSignal) continue;

                String baseClassPath = classPathInfo != null ? classPathInfo.path() : "";
                List<String> classProduces = collectMediaTypes(ancestry, annotationsByOwner, "Produces");
                List<String> classConsumes = collectMediaTypes(ancestry, annotationsByOwner, "Consumes");

                // Walk concrete class first, then ancestors so subclass overrides suppress base duplicates
                var seenMethodKeys = new HashSet<String>();
                for (EntityIdentity typeInHierarchy : ancestry) {
                    boolean inherited = !typeInHierarchy.equals(entity.identity());
                    Optional<EntityIdentity> inheritedFrom = inherited ? Optional.of(typeInHierarchy) : Optional.empty();
                    for (EntityIdentity methodId : declaredMethodsByType.getOrDefault(typeInHierarchy, List.of())) {
                        String sigKey = memberSignatureKeys.getOrDefault(methodId, methodId.value());
                        var mAnns = annotationsByOwner.getOrDefault(methodId, List.of());
                        var httpMethods = new ArrayList<String>();
                        String methodPath = "";
                        boolean explicitMethodPath = false;
                        List<String> methodProduces = List.of();
                        List<String> methodConsumes = List.of();
                        String spec = "";
                        String derivation = "";

                        for (var mAnn : mAnns) {
                            String simple = simpleName(mAnn.qualifiedName());
                            if (HTTP_VERBS.contains(simple)) {
                                httpMethods.add(simple);
                                if (spec.isEmpty()) { spec = mAnn.spec(); derivation = mAnn.derivation(); }
                            } else if (simple.equals("Path")) {
                                explicitMethodPath = true;
                                methodPath = parseSinglePathAttribute(mAnn.use().spelling());
                                if (spec.isEmpty()) { spec = mAnn.spec(); derivation = mAnn.derivation(); }
                            } else if (simple.equals("Produces")) {
                                methodProduces = parseMediaTypes(mAnn.use().spelling());
                            } else if (simple.equals("Consumes")) {
                                methodConsumes = parseMediaTypes(mAnn.use().spelling());
                            }
                        }

                        if (!seenMethodKeys.add(sigKey)) {
                            continue;
                        }
                        if (httpMethods.isEmpty()) continue;
                        if (classPathInfo == null && !explicitMethodPath) continue;

                        if (spec.isEmpty() && classPathInfo != null) {
                            spec = classPathInfo.spec();
                            derivation = classPathInfo.derivation();
                        }
                        String fullPath = combine(appPath, combine(baseClassPath, methodPath));
                        if (fullPath.isEmpty()) fullPath = "/";
                        if (remainingBudget <= 0) break;
                        unitRoutes.add(new Route(
                                entity.identity(),
                                methodId,
                                moduleId,
                                fullPath,
                                httpMethods,
                                methodConsumes.isEmpty() ? classConsumes : methodConsumes,
                                methodProduces.isEmpty() ? classProduces : methodProduces,
                                httpMethods.contains("GET"),
                                Kind.JAX_RS,
                                inheritedFrom,
                                spec,
                                derivation));
                        remainingBudget--;
                    }
                }
            }

            var ordered = unitRoutes.stream().sorted(Comparator.comparing(Route::identity)).toList();
            results.put(unitAnalysis, new Result(ordered));
        }

        return Collections.unmodifiableMap(results);
    }

    private record UnresolvedParentRef(String ref, SourceDocumentIdentity doc) {}
    private record ClassPathInfo(String path, String spec, String derivation) {}

    private static Optional<EntityIdentity> resolveProjectTypeByReference(
            String stableRef,
            SourceDocumentIdentity doc,
            Map<SourceDocumentIdentity, DocImports> docImports,
            Map<SourceDocumentIdentity, String> packageByDoc,
            Map<String, List<EntityIdentity>> projectTypesByQfn) {
        if (stableRef == null || stableRef.isBlank()) return Optional.empty();
        String ref = stableRef.trim();
        int lt = ref.indexOf('<');
        if (lt > 0) ref = ref.substring(0, lt).trim();
        if (ref.indexOf('.') >= 0) {
            var direct = projectTypesByQfn.get(ref);
            return direct != null && direct.size() == 1 ? Optional.of(direct.getFirst()) : Optional.empty();
        }
        if (doc != null) {
            DocImports imps = docImports.get(doc);
            if (imps != null) {
                String explicit = imps.explicit().get(ref);
                if (explicit != null) {
                    var found = projectTypesByQfn.get(explicit);
                    if (found != null && found.size() == 1) return Optional.of(found.getFirst());
                }
                String pkg = packageByDoc.get(doc);
                if (pkg != null && !pkg.isEmpty()) {
                    var samePkg = projectTypesByQfn.get(pkg + "." + ref);
                    if (samePkg != null && samePkg.size() == 1) return Optional.of(samePkg.getFirst());
                }
                var matches = new ArrayList<EntityIdentity>();
                for (String wc : imps.wildcards()) {
                    var list = projectTypesByQfn.get(wc + ref);
                    if (list != null) matches.addAll(list);
                }
                if (matches.size() == 1) return Optional.of(matches.getFirst());
            }
        }
        return Optional.empty();
    }

    private static Optional<ResolvedAnn> resolveStandardAnnotation(
            AnnotationUseRecord use, List<String> resolvedTargets, DocImports imports) {
        if (resolvedTargets.size() == 1) {
            String fqn = resolvedTargets.getFirst();
            String spec = standardSpec(fqn);
            if (!spec.isEmpty()) {
                return Optional.of(new ResolvedAnn(use, fqn, spec, "COMPILER"));
            }
        }
        String raw = annotationNameFromSpelling(use.spelling());
        if (raw.isEmpty()) return Optional.empty();
        if (raw.indexOf('.') >= 0) {
            String spec = standardSpec(raw);
            return spec.isEmpty() ? Optional.empty()
                    : Optional.of(new ResolvedAnn(use, raw, spec, "SOURCE_IMPORT_VERIFIED"));
        }
        if (!SUPPORTED_SIMPLE_ANNOTATIONS.contains(raw) || imports == null) return Optional.empty();
        String explicit = imports.explicit().get(raw);
        if (explicit != null) {
            String spec = standardSpec(explicit);
            return spec.isEmpty() ? Optional.empty()
                    : Optional.of(new ResolvedAnn(use, explicit, spec, "SOURCE_IMPORT_VERIFIED"));
        }
        var wildcardMatches = new ArrayList<String>();
        for (String wc : imports.wildcards()) {
            String candidate = wc + raw;
            if (!standardSpec(candidate).isEmpty()) wildcardMatches.add(candidate);
        }
        if (wildcardMatches.size() == 1) {
            String fqn = wildcardMatches.getFirst();
            return Optional.of(new ResolvedAnn(use, fqn, standardSpec(fqn), "SOURCE_IMPORT_VERIFIED"));
        }
        return Optional.empty();
    }

    private static String standardSpec(String fqn) {
        if (fqn == null) return "";
        if (fqn.startsWith(JAKARTA_RS) && SUPPORTED_SIMPLE_ANNOTATIONS.contains(fqn.substring(JAKARTA_RS.length()))) {
            return "jakarta.ws.rs";
        }
        if (fqn.startsWith(JAVAX_RS) && SUPPORTED_SIMPLE_ANNOTATIONS.contains(fqn.substring(JAVAX_RS.length()))) {
            return "javax.ws.rs";
        }
        if (fqn.equals(JAKARTA_SERVLET)) return "jakarta.servlet";
        if (fqn.equals(JAVAX_SERVLET)) return "javax.servlet";
        return "";
    }

    private static String annotationNameFromSpelling(String spelling) {
        if (spelling == null) return "";
        String sp = spelling.stripLeading();
        if (!sp.startsWith("@")) return "";
        int i = 1;
        while (i < sp.length() && Character.isWhitespace(sp.charAt(i))) i++;
        int start = i;
        while (i < sp.length() && (Character.isJavaIdentifierPart(sp.charAt(i)) || sp.charAt(i) == '.')) i++;
        return i > start ? sp.substring(start, i) : "";
    }

    private static List<EntityIdentity> bfsAncestry(
            EntityIdentity start, Map<EntityIdentity, List<EntityIdentity>> parentsByType, int maxDepth) {
        var visited = new LinkedHashSet<EntityIdentity>();
        var queue = new ArrayDeque<Map.Entry<EntityIdentity, Integer>>();
        visited.add(start);
        queue.add(Map.entry(start, 0));
        while (!queue.isEmpty()) {
            var cur = queue.removeFirst();
            if (cur.getValue() >= maxDepth) continue;
            for (EntityIdentity p : parentsByType.getOrDefault(cur.getKey(), List.of())) {
                if (visited.add(p)) {
                    queue.addLast(Map.entry(p, cur.getValue() + 1));
                }
            }
        }
        return List.copyOf(visited);
    }

    private static String findSingleApplicationPath(
            FrontendResult fe, Map<EntityIdentity, List<ResolvedAnn>> annsByOwner) {
        var paths = new TreeSet<String>();
        for (var decl : fe.declarations()) {
            var e = decl.entity();
            if (e.origin() != EntityOrigin.PROJECT || e.kind() != EntityKind.TYPE) continue;
            for (var ann : annsByOwner.getOrDefault(e.identity(), List.of())) {
                if (simpleName(ann.qualifiedName()).equals("ApplicationPath")) {
                    String p = parseSinglePathAttribute(ann.use().spelling());
                    if (!p.isEmpty()) paths.add(p);
                }
            }
        }
        return paths.size() == 1 ? paths.getFirst() : "";
    }

    private static ClassPathInfo findClassPath(
            List<EntityIdentity> ancestry, Map<EntityIdentity, List<ResolvedAnn>> annsByOwner) {
        for (EntityIdentity t : ancestry) {
            for (var ann : annsByOwner.getOrDefault(t, List.of())) {
                if (simpleName(ann.qualifiedName()).equals("Path")) {
                    return new ClassPathInfo(parseSinglePathAttribute(ann.use().spelling()), ann.spec(), ann.derivation());
                }
            }
        }
        return null;
    }

    private static boolean hasMethodLevelJaxRsRoute(
            EntityIdentity type,
            Map<EntityIdentity, List<EntityIdentity>> declaredMethodsByType,
            Map<EntityIdentity, List<ResolvedAnn>> annsByOwner) {
        for (EntityIdentity m : declaredMethodsByType.getOrDefault(type, List.of())) {
            boolean hasVerb = false;
            boolean hasPath = false;
            for (var a : annsByOwner.getOrDefault(m, List.of())) {
                String sn = simpleName(a.qualifiedName());
                if (HTTP_VERBS.contains(sn)) hasVerb = true;
                else if (sn.equals("Path")) hasPath = true;
            }
            if (hasVerb && hasPath) return true;
        }
        return false;
    }

    private static List<String> collectMediaTypes(
            List<EntityIdentity> ancestry, Map<EntityIdentity, List<ResolvedAnn>> annsByOwner, String simpleAnnName) {
        for (EntityIdentity t : ancestry) {
            for (var ann : annsByOwner.getOrDefault(t, List.of())) {
                if (simpleName(ann.qualifiedName()).equals(simpleAnnName)) {
                    var parsed = parseMediaTypes(ann.use().spelling());
                    if (!parsed.isEmpty()) return parsed;
                }
            }
        }
        return List.of();
    }

    private static List<String> collectServletMethods(
            List<EntityIdentity> ancestry,
            Map<EntityIdentity, List<EntityIdentity>> declaredMethodsByType,
            Map<EntityIdentity, String> methodNames) {
        var methods = new TreeSet<String>();
        for (EntityIdentity t : ancestry) {
            for (EntityIdentity m : declaredMethodsByType.getOrDefault(t, List.of())) {
                String name = methodNames.getOrDefault(m, "");
                String verb = SERVLET_DO_METHODS.get(name);
                if (verb != null) methods.add(verb);
            }
        }
        return List.copyOf(methods);
    }

    private static String parseSinglePathAttribute(String spelling) {
        try {
            var attrs = LiteralConditionAnnotation.parse(spelling);
            var vals = LiteralConditionAnnotation.strings(attrs, "value", List.of());
            return vals.isEmpty() ? "" : vals.getFirst();
        } catch (IllegalArgumentException unsupported) {
            return "";
        }
    }

    private static List<String> parseServletUrlPatterns(String spelling) {
        try {
            var attrs = LiteralConditionAnnotation.parse(spelling);
            var patterns = new LinkedHashSet<String>();
            patterns.addAll(LiteralConditionAnnotation.strings(attrs, "urlPatterns", List.of()));
            patterns.addAll(LiteralConditionAnnotation.strings(attrs, "value", List.of()));
            return List.copyOf(patterns);
        } catch (IllegalArgumentException unsupported) {
            return List.of();
        }
    }

    private static List<String> parseMediaTypes(String spelling) {
        try {
            var attrs = LiteralConditionAnnotation.parse(spelling);
            var val = attrs.get("value");
            if (val instanceof LiteralConditionAnnotation.Strings strings) return strings.values();
            if (val instanceof LiteralConditionAnnotation.Enums enums) {
                var out = new ArrayList<String>(enums.names().size());
                for (String raw : enums.names()) {
                    String constant = raw.substring(raw.lastIndexOf('.') + 1);
                    String resolved = MEDIA_TYPE_VALUES.get(constant);
                    if (resolved != null) out.add(resolved);
                }
                return List.copyOf(out);
            }
            return List.of();
        } catch (IllegalArgumentException unsupported) {
            return List.of();
        }
    }

    private static String combine(String parent, String child) {
        String p = parent == null ? "" : parent.trim();
        String c = child == null ? "" : child.trim();
        p = stripRegexSegments(p);
        c = stripRegexSegments(c);
        if (p.isEmpty() && c.isEmpty()) return "";
        if (p.isEmpty()) return normalizePath(c);
        if (c.isEmpty() || c.equals("/")) return normalizePath(p);
        String base = normalizePath(p);
        String tail = c.startsWith("/") ? c : "/" + c;
        if (base.equals("/")) return tail;
        return base.endsWith("/") ? base + tail.substring(1) : base + tail;
    }

    private static String normalizePath(String p) {
        String s = p == null ? "" : p.trim();
        if (s.isEmpty()) return "";
        if (!s.startsWith("/")) s = "/" + s;
        while (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s.replaceAll("/{2,}", "/");
    }

    private static String stripRegexSegments(String path) {
        // Normalize JAX-RS "{id : \\d+}" or "{id:\\d+}" to "{id}" cleanly without regex evaluation.
        if (path.indexOf('{') < 0) return path;
        var out = new StringBuilder(path.length());
        int i = 0;
        while (i < path.length()) {
            char ch = path.charAt(i);
            if (ch == '{') {
                int close = path.indexOf('}', i + 1);
                if (close > i + 1) {
                    String inner = path.substring(i + 1, close).trim();
                    int colon = inner.indexOf(':');
                    String varName = colon >= 0 ? inner.substring(0, colon).trim() : inner;
                    if (!varName.isEmpty() && Character.isJavaIdentifierStart(varName.charAt(0))) {
                        out.append('{').append(varName).append('}');
                        i = close + 1;
                        continue;
                    }
                }
            }
            out.append(ch);
            i++;
        }
        return out.toString();
    }

    private static String signatureKey(String canonicalName, String fallbackSpelling) {
        // Canonical method format: "java:v1:[\"method\",[\"type\",...],\"name\",[paramTypes...]]"
        if (canonicalName != null && canonicalName.startsWith("java:v1:")) {
            int bracket = canonicalName.indexOf("],\"");
            if (bracket > 0) {
                return canonicalName.substring(bracket + 2);
            }
        }
        return fallbackSpelling == null ? "" : fallbackSpelling;
    }

    private static String simpleName(String qfn) {
        int dot = qfn.lastIndexOf('.');
        return dot >= 0 ? qfn.substring(dot + 1) : qfn;
    }

    private static List<String> sorted(Collection<String> values) {
        return values.stream().filter(Objects::nonNull).filter(s -> !s.isEmpty()).distinct().sorted().toList();
    }

    private static DocImports parseImports(String source) {
        var explicit = new HashMap<String, String>();
        var wildcards = new LinkedHashSet<String>();
        if (source == null || source.isEmpty()) return new DocImports(Map.of(), Set.of());
        String stripped = source.replaceAll("(?s)/\\*.*?\\*/|//[^\\r\\n]*", "");
        for (String line : stripped.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("import ") && !trimmed.startsWith("import\t")) continue;
            String rest = trimmed.substring(6).trim();
            if (rest.startsWith("static ") || rest.startsWith("static\t")) continue;
            int semi = rest.indexOf(';');
            if (semi >= 0) rest = rest.substring(0, semi).trim();
            if (rest.isEmpty()) continue;
            if (rest.endsWith(".*")) {
                wildcards.add(rest.substring(0, rest.length() - 1));
            } else {
                int dot = rest.lastIndexOf('.');
                if (dot > 0 && dot + 1 < rest.length()) {
                    explicit.put(rest.substring(dot + 1), rest);
                }
            }
        }
        return new DocImports(Collections.unmodifiableMap(explicit), Collections.unmodifiableSet(wildcards));
    }
}
