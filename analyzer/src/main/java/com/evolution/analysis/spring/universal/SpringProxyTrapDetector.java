package com.evolution.analysis.spring.universal;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.EntityIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.semantic.EntityKind;
import com.evolution.analysis.contract.semantic.EntityOrigin;
import com.evolution.analysis.contract.semantic.RelationshipOccurrence;
import com.evolution.analysis.contract.semantic.RelationshipTarget;
import com.evolution.analysis.contract.semantic.SemanticStatus;
import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.frontend.DeclarationRecord;
import com.evolution.analysis.frontend.ObservationRecord;
import com.evolution.analysis.ingestion.IngestionEvidence;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static Spring AOP / CGLIB proxy trap detectors (Jasmine ASE '22, Sec. 3.2, Fig. 1(b)),
 * exposing a clean public API ready to wire directly into {@code PolicyEvaluator}.
 *
 * <p>Implements two deterministic static rules:
 * <ol>
 *   <li>{@value #RULE_SELF_INVOCATION_BYPASS}: flags intra-class calls ({@code this.m()} or implicit
 *       {@code m()}) when target {@code m} carries {@code @Transactional}, {@code @Cacheable},
 *       {@code @Async}, {@code @PreAuthorize} (or standard cache/security equivalents), or is targeted
 *       by an {@code ADVISES_METHOD} edge.</li>
 *   <li>{@value #RULE_CGLIB_PRIVATE_FINAL_TRAP}: flags {@code private} or {@code final} instance
 *       methods (or methods on {@code final} proxied bean classes) matched by {@code @Aspect},
 *       {@code @Transactional}, or {@code @RequestMapping} (and HTTP mapping shortcuts) on proxied
 *       Spring bean classes, where CGLIB subclassing cannot override the target method.</li>
 * </ol>
 */
public final class SpringProxyTrapDetector {

    public static final VersionedIdentifier PROVIDER =
            new VersionedIdentifier("spring.proxy-trap-detector", "wp2.1-v1");

    public static final String RULE_SELF_INVOCATION_BYPASS = "spring-aop-self-invocation-bypass";
    public static final String RULE_CGLIB_PRIVATE_FINAL_TRAP = "spring-cglib-private-final-trap";

    private static final Set<String> PROXY_INTERCEPTED_ANNOTATIONS = Set.of(
            "org.springframework.transaction.annotation.Transactional",
            "jakarta.transaction.Transactional",
            "javax.transaction.Transactional",
            "org.springframework.cache.annotation.Cacheable",
            "org.springframework.cache.annotation.CachePut",
            "org.springframework.cache.annotation.CacheEvict",
            "org.springframework.scheduling.annotation.Async",
            "org.springframework.security.access.prepost.PreAuthorize",
            "org.springframework.security.access.prepost.PostAuthorize",
            "org.springframework.security.access.annotation.Secured",
            "jakarta.annotation.security.RolesAllowed",
            "javax.annotation.security.RolesAllowed");

    private static final Set<String> PROXY_INTERCEPTED_SIMPLE_NAMES = Set.of(
            "Transactional", "Cacheable", "CachePut", "CacheEvict",
            "Async", "PreAuthorize", "PostAuthorize", "Secured", "RolesAllowed");

    private static final Set<String> REQUEST_MAPPING_ANNOTATIONS = Set.of(
            "org.springframework.web.bind.annotation.RequestMapping",
            "org.springframework.web.bind.annotation.GetMapping",
            "org.springframework.web.bind.annotation.PostMapping",
            "org.springframework.web.bind.annotation.PutMapping",
            "org.springframework.web.bind.annotation.DeleteMapping",
            "org.springframework.web.bind.annotation.PatchMapping");

    private static final Set<String> REQUEST_MAPPING_SIMPLE_NAMES = Set.of(
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping");

    private static final Set<String> SPRING_BEAN_STEREOTYPES = Set.of(
            "org.springframework.stereotype.Component",
            "org.springframework.stereotype.Service",
            "org.springframework.stereotype.Repository",
            "org.springframework.stereotype.Controller",
            "org.springframework.web.bind.annotation.RestController",
            "org.springframework.context.annotation.Configuration",
            "Component", "Service", "Repository", "Controller", "RestController", "Configuration");

    private static final Pattern MODIFIER_PREFIX = Pattern.compile(
            "^\\s*((?:(?:public|protected|private|static|final|abstract|synchronized|native|strictfp|default)\\s+)*)");

    /** Single static proxy trap finding ready for {@code PolicyEvaluator} integration. */
    public record ProxyTrapFinding(
            String ruleId,
            String severity,
            String beanClassFqn,
            String callerMethod,
            String targetMethod,
            Set<String> triggers,
            String sourceFile,
            int line,
            TrustTier trustTier,
            String message) implements Comparable<ProxyTrapFinding> {
        public ProxyTrapFinding {
            ruleId = safe(ruleId);
            severity = safe(severity).isEmpty() ? "HIGH" : safe(severity).toUpperCase(Locale.ROOT);
            beanClassFqn = safe(beanClassFqn);
            callerMethod = safe(callerMethod);
            targetMethod = safe(targetMethod);
            triggers = triggers == null ? Set.of() : Collections.unmodifiableSet(new TreeSet<>(triggers));
            sourceFile = normalizePath(sourceFile);
            trustTier = trustTier == null ? TrustTier.SOURCE_SYNTAX : trustTier;
            message = safe(message);
        }

        public Map<String, Object> toEvidencePayload() {
            var map = new TreeMap<String, Object>();
            map.put("beanClassFqn", beanClassFqn);
            map.put("callerMethod", callerMethod);
            map.put("line", line);
            map.put("message", message);
            map.put("ruleId", ruleId);
            map.put("severity", severity);
            map.put("sourceFile", sourceFile);
            map.put("targetMethod", targetMethod);
            map.put("triggers", List.copyOf(triggers));
            map.put("trustTier", trustTier.name());
            return Collections.unmodifiableMap(map);
        }

        @Override
        public int compareTo(ProxyTrapFinding other) {
            int cmp = ruleId.compareTo(other.ruleId);
            if (cmp != 0) return cmp;
            cmp = beanClassFqn.compareTo(other.beanClassFqn);
            if (cmp != 0) return cmp;
            cmp = targetMethod.compareTo(other.targetMethod);
            if (cmp != 0) return cmp;
            cmp = callerMethod.compareTo(other.callerMethod);
            if (cmp != 0) return cmp;
            cmp = sourceFile.compareTo(other.sourceFile);
            return cmp != 0 ? cmp : Integer.compare(line, other.line);
        }
    }

    /** Structural descriptor for a method on a candidate Spring bean class. */
    public record MethodInfo(
            String classFqn,
            String methodName,
            Set<String> modifiers,
            Set<String> annotations,
            String sourceFile,
            int line) {
        public MethodInfo {
            classFqn = safe(classFqn);
            methodName = safe(methodName);
            modifiers = modifiers == null ? Set.of() : Collections.unmodifiableSet(new TreeSet<>(modifiers));
            annotations = annotations == null ? Set.of() : Collections.unmodifiableSet(new TreeSet<>(annotations));
            sourceFile = normalizePath(sourceFile);
        }
    }

    /** Structural descriptor for a candidate Spring bean class. */
    public record BeanClassInfo(
            String classFqn,
            Set<String> classModifiers,
            Set<String> classAnnotations,
            boolean springBean,
            List<MethodInfo> methods,
            String sourceFile,
            int line) {
        public BeanClassInfo {
            classFqn = safe(classFqn);
            classModifiers = classModifiers == null ? Set.of() : Collections.unmodifiableSet(new TreeSet<>(classModifiers));
            classAnnotations = classAnnotations == null ? Set.of() : Collections.unmodifiableSet(new TreeSet<>(classAnnotations));
            methods = methods == null ? List.of() : List.copyOf(methods);
            sourceFile = normalizePath(sourceFile);
        }
    }

    /** Intra-class method invocation ({@code this.m()} or implicit {@code m()}). */
    public record IntraClassCall(
            String classFqn,
            String callerMethod,
            String targetMethod,
            boolean directSelfReceiver,
            String sourceFile,
            int line) {
        public IntraClassCall {
            classFqn = safe(classFqn);
            callerMethod = safe(callerMethod);
            targetMethod = safe(targetMethod);
            sourceFile = normalizePath(sourceFile);
        }
    }

    public record Result(
            ContentDigest identity,
            List<ProxyTrapFinding> findings,
            List<CapabilityGapRecord> gaps) {
        private static final Result EMPTY = new Result(
                IngestionEvidence.digest(List.of(PROVIDER, "empty")), List.of(), List.of());

        public Result {
            identity = Objects.requireNonNull(identity, "identity");
            findings = findings == null ? List.of() : findings.stream().sorted().distinct().toList();
            gaps = gaps == null ? List.of() : gaps.stream().sorted().distinct().toList();
        }

        public static Result empty() {
            return EMPTY;
        }

        public List<ProxyTrapFinding> selfInvocationFindings() {
            return findingsByRule(RULE_SELF_INVOCATION_BYPASS);
        }

        public List<ProxyTrapFinding> cglibPrivateFinalFindings() {
            return findingsByRule(RULE_CGLIB_PRIVATE_FINAL_TRAP);
        }

        public List<ProxyTrapFinding> findingsByRule(String ruleId) {
            return findings.stream().filter(f -> f.ruleId().equals(ruleId)).toList();
        }
    }

    private SpringProxyTrapDetector() {}

    /**
     * Runs both static proxy trap detectors over {@link SpringSourceEvidence} and matched
     * {@link SpringAopMetadataExtractor.Result}.
     */
    public static Result detect(
            SpringSourceEvidence source,
            SpringAopMetadataExtractor.Result aopResult) {
        return detect(source, aopResult, Set.of());
    }

    /**
     * Runs both static proxy trap detectors over {@link SpringSourceEvidence}, matched AOP edges,
     * and optional XML-declared bean class FQNs.
     */
    public static Result detect(
            SpringSourceEvidence source,
            SpringAopMetadataExtractor.Result aopResult,
            Set<String> extraBeanClassFqns) {
        Objects.requireNonNull(source, "source");
        var snapshot = source.manifest().snapshot().identity();
        var docPaths = new HashMap<com.evolution.analysis.contract.identity.SourceDocumentIdentity, String>();
        for (var doc : source.manifest().snapshot().documents()) {
            docPaths.put(doc.identity(), normalizePath(doc.path()));
        }

        var memberNamesById = new HashMap<EntityIdentity, String>();
        for (var md : source.frontend().memberDeclarations()) {
            memberNamesById.putIfAbsent(md.member(), md.name());
        }
        var classes = new ArrayList<BeanClassInfo>();
        var methodInfoById = new HashMap<EntityIdentity, MethodInfo>();
        var classByMember = new HashMap<EntityIdentity, String>();
        var typeSpans = new ArrayList<TypeSpanEntry>();
        var methodSpans = new ArrayList<MethodSpanEntry>();

        // Index project method declarations by enclosing document for --build=off span fallback
        var projectMethodsByDoc = new HashMap<com.evolution.analysis.contract.identity.SourceDocumentIdentity, List<DeclarationRecord>>();
        for (DeclarationRecord decl : source.declarations().values()) {
            if (decl.entity().origin() == EntityOrigin.PROJECT
                    && decl.entity().kind() == EntityKind.METHOD
                    && decl.entity().declaration().isPresent()) {
                projectMethodsByDoc
                        .computeIfAbsent(decl.entity().declaration().orElseThrow().document(), k -> new ArrayList<>())
                        .add(decl);
            }
        }

        for (DeclarationRecord decl : source.declarations().values()) {
            if (decl.entity().origin() != EntityOrigin.PROJECT || decl.entity().kind() != EntityKind.TYPE) {
                continue;
            }
            EntityIdentity typeId = decl.entity().identity();
            String classFqn = source.typeName(typeId);
            if (classFqn == null || classFqn.isBlank()) continue;

            var classAnns = collectAnnotations(source.annotations(typeId));
            var classMods = extractModifiers(decl.spelling(), source.annotations(typeId));
            var typeSpanOpt = decl.entity().declaration();
            String file = typeSpanOpt.map(sp -> docPaths.getOrDefault(sp.document(), "")).orElse("");
            int line = typeSpanOpt.map(sp -> sp.startLine()).orElse(1);
            typeSpanOpt.ifPresent(sp -> typeSpans.add(new TypeSpanEntry(classFqn, sp)));

            boolean isBean = hasAny(classAnns, SPRING_BEAN_STEREOTYPES)
                    || (extraBeanClassFqns != null && extraBeanClassFqns.contains(classFqn))
                    || (aopResult != null && aopResult.isClassProxied(classFqn));

            var memberIds = new LinkedHashSet<EntityIdentity>(source.declaredMembers(typeId));
            if (typeSpanOpt.isPresent()) {
                var tSpan = typeSpanOpt.orElseThrow();
                for (DeclarationRecord mDecl : projectMethodsByDoc.getOrDefault(tSpan.document(), List.of())) {
                    var mSpan = mDecl.entity().declaration().orElseThrow();
                    if (containsSpan(tSpan, mSpan)) {
                        memberIds.add(mDecl.entity().identity());
                    }
                }
            }

            var methods = new ArrayList<MethodInfo>();
            for (EntityIdentity memberId : memberIds) {
                DeclarationRecord mDecl = source.declarations().get(memberId);
                if (mDecl == null || mDecl.entity().kind() != EntityKind.METHOD) continue;
                var mAnns = collectAnnotations(source.annotations(memberId));
                var mMods = extractModifiers(mDecl.spelling(), source.annotations(memberId));
                String mName = memberNamesById.getOrDefault(memberId, extractSimpleMethodName(mDecl));
                var mSpanOpt = mDecl.entity().declaration();
                int mLine = mSpanOpt.map(sp -> sp.startLine()).orElse(line);
                if (hasProxyTriggerAnnotation(mAnns) || hasRequestMappingAnnotation(mAnns)) {
                    isBean = true;
                }
                var mi = new MethodInfo(classFqn, mName, mMods, mAnns, file, mLine);
                methods.add(mi);
                methodInfoById.put(memberId, mi);
                classByMember.put(memberId, classFqn);
                mSpanOpt.ifPresent(sp -> methodSpans.add(new MethodSpanEntry(classFqn, mName, sp)));
            }
            classes.add(new BeanClassInfo(classFqn, classMods, classAnns, isBean, methods, file, line));
        }

        var seenMethodSpans = new HashSet<com.evolution.analysis.contract.source.SourceSpan>();
        for (MethodSpanEntry mse : methodSpans) {
            seenMethodSpans.add(mse.span());
        }
        // Passive --build=off fallback: also index enclosing method spans from unmapped java.declares
        // observations (e.g. methods whose parameter types are unresolved when Spring JARs are absent)
        // so callerMethod attribution remains exact instead of falling back to <init>.
        for (ObservationRecord obs : source.frontend().observations()) {
            if (!"java.declares".equals(obs.category().value()) || obs.span().isEmpty()) continue;
            var declSpan = obs.span().orElseThrow();
            if (seenMethodSpans.contains(declSpan)) continue;
            String enclosingType = findInnermostEnclosingType(declSpan, typeSpans);
            if (enclosingType == null) continue;
            String mName = extractMethodNameFromDeclarationHeader(obs.reference(), enclosingType);
            if (!mName.isEmpty() && seenMethodSpans.add(declSpan)) {
                methodSpans.add(new MethodSpanEntry(enclosingType, mName, declSpan));
            }
        }

        // Index ObservationRecord references by mapped occurrence identity so we distinguish
        // direct intra-class self-invocations (this.m() / m()) from self-injected proxy calls (self.m()).
        var callRefByOcc = new HashMap<com.evolution.analysis.contract.identity.OccurrenceIdentity, String>();
        var resolvedOccIds = new HashSet<com.evolution.analysis.contract.identity.OccurrenceIdentity>();
        for (ObservationRecord obs : source.frontend().observations()) {
            if ("java.calls".equals(obs.category().value()) && obs.mappedOccurrence().isPresent()) {
                callRefByOcc.put(obs.mappedOccurrence().orElseThrow(), obs.reference());
            }
        }

        var intraCalls = new ArrayList<IntraClassCall>();
        var seenCallKeys = new HashSet<String>();
        for (RelationshipOccurrence occ : source.frontend().occurrences()) {
            if (occ.status() != SemanticStatus.RESOLVED
                    || !"java.calls".equals(occ.relationship().kind().value())
                    || !(occ.relationship().target() instanceof RelationshipTarget.Resolved resolved)) {
                continue;
            }
            resolvedOccIds.add(occ.identity());
            EntityIdentity callerId = occ.relationship().source();
            EntityIdentity targetId = resolved.target();
            MethodInfo callerInfo = methodInfoById.get(callerId);
            MethodInfo targetInfo = methodInfoById.get(targetId);
            if (callerInfo == null || targetInfo == null) continue;
            if (!callerInfo.classFqn().equals(targetInfo.classFqn())) continue;

            String refText = callRefByOcc.getOrDefault(occ.identity(), "");
            boolean directSelf = isDirectSelfInvocationReference(refText, targetInfo.methodName());
            String file = docPaths.getOrDefault(occ.span().document(), callerInfo.sourceFile());
            String callKey = callerInfo.classFqn() + "|" + callerInfo.methodName() + "|"
                    + targetInfo.methodName() + "|" + occ.span().startLine();
            if (seenCallKeys.add(callKey)) {
                intraCalls.add(new IntraClassCall(
                        callerInfo.classFqn(),
                        callerInfo.methodName(),
                        targetInfo.methodName(),
                        directSelf,
                        file,
                        occ.span().startLine()));
            }
        }

        // Passive --build=off fallback: recover intra-class direct self-invocations (this.m() / m())
        // from java.calls observations whose caller or call attribution remained unresolved due to absent JARs.
        var methodNamesByClass = new HashMap<String, Set<String>>();
        for (BeanClassInfo cls : classes) {
            var set = new HashSet<String>();
            for (MethodInfo mi : cls.methods()) set.add(mi.methodName());
            methodNamesByClass.put(cls.classFqn(), set);
        }
        for (ObservationRecord obs : source.frontend().observations()) {
            if (!"java.calls".equals(obs.category().value()) || obs.span().isEmpty()) continue;
            if (obs.mappedOccurrence().filter(resolvedOccIds::contains).isPresent()) continue;
            String targetName = extractDirectSelfTargetMethodName(obs.reference());
            if (targetName.isEmpty()) continue;
            var callSpan = obs.span().orElseThrow();
            MethodSpanEntry enclosingMethod = findInnermostEnclosingMethod(callSpan, methodSpans);
            String classFqn = enclosingMethod != null ? enclosingMethod.classFqn()
                    : findInnermostEnclosingType(callSpan, typeSpans);
            if (classFqn == null || !methodNamesByClass.getOrDefault(classFqn, Set.of()).contains(targetName)) {
                continue;
            }
            String callerName = enclosingMethod != null ? enclosingMethod.methodName() : "<init>";
            String file = docPaths.getOrDefault(callSpan.document(), "");
            String callKey = classFqn + "|" + callerName + "|" + targetName + "|" + callSpan.startLine();
            if (seenCallKeys.add(callKey)) {
                intraCalls.add(new IntraClassCall(classFqn, callerName, targetName, true, file, callSpan.startLine()));
            }
        }

        return detect(snapshot, classes, intraCalls, aopResult);
    }

    private record TypeSpanEntry(String classFqn, com.evolution.analysis.contract.source.SourceSpan span) {}
    private record MethodSpanEntry(String classFqn, String methodName, com.evolution.analysis.contract.source.SourceSpan span) {}

    private static boolean containsSpan(
            com.evolution.analysis.contract.source.SourceSpan outer,
            com.evolution.analysis.contract.source.SourceSpan inner) {
        if (!outer.document().equals(inner.document())) return false;
        boolean startsOk = outer.startLine() < inner.startLine()
                || (outer.startLine() == inner.startLine() && outer.startColumn() <= inner.startColumn());
        boolean endsOk = inner.endLine() < outer.endLine()
                || (inner.endLine() == outer.endLine() && inner.endColumn() <= outer.endColumn());
        return startsOk && endsOk;
    }

    private static MethodSpanEntry findInnermostEnclosingMethod(
            com.evolution.analysis.contract.source.SourceSpan callSpan,
            List<MethodSpanEntry> methods) {
        MethodSpanEntry best = null;
        int bestLines = Integer.MAX_VALUE;
        for (MethodSpanEntry m : methods) {
            if (containsSpan(m.span(), callSpan)) {
                int len = m.span().endLine() - m.span().startLine();
                if (len < bestLines) {
                    bestLines = len;
                    best = m;
                }
            }
        }
        return best;
    }

    private static String findInnermostEnclosingType(
            com.evolution.analysis.contract.source.SourceSpan callSpan,
            List<TypeSpanEntry> types) {
        String best = null;
        int bestLines = Integer.MAX_VALUE;
        for (TypeSpanEntry t : types) {
            if (containsSpan(t.span(), callSpan)) {
                int len = t.span().endLine() - t.span().startLine();
                if (len < bestLines) {
                    bestLines = len;
                    best = t.classFqn();
                }
            }
        }
        return best;
    }

    private static String extractMethodNameFromDeclarationHeader(String ref, String enclosingTypeFqn) {
        if (ref == null || ref.isBlank()) return "";
        int i = 0;
        int n = ref.length();
        // Skip leading whitespace, comments, and annotations (@Name or @Name(...))
        while (i < n) {
            while (i < n && Character.isWhitespace(ref.charAt(i))) i++;
            if (i + 1 < n && ref.charAt(i) == '/' && ref.charAt(i + 1) == '/') {
                int nl = ref.indexOf('\n', i + 2);
                i = nl < 0 ? n : nl + 1;
                continue;
            }
            if (i + 1 < n && ref.charAt(i) == '/' && ref.charAt(i + 1) == '*') {
                int end = ref.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                continue;
            }
            if (i < n && ref.charAt(i) == '@') {
                // Reject @interface type declarations
                if (ref.startsWith("@interface", i)) return "";
                i++;
                while (i < n && (Character.isJavaIdentifierPart(ref.charAt(i)) || ref.charAt(i) == '.')) i++;
                while (i < n && Character.isWhitespace(ref.charAt(i))) i++;
                if (i < n && ref.charAt(i) == '(') {
                    int depth = 0;
                    boolean inStr = false;
                    char quote = 0;
                    while (i < n) {
                        char c = ref.charAt(i++);
                        if (inStr) {
                            if (c == '\\' && i < n) { i++; continue; }
                            if (c == quote) inStr = false;
                        } else if (c == '"' || c == '\'') {
                            inStr = true;
                            quote = c;
                        } else if (c == '(') {
                            depth++;
                        } else if (c == ')' && --depth == 0) {
                            break;
                        }
                    }
                }
                continue;
            }
            break;
        }
        // Scan header up to opening '(' before any '{', ';', or '='
        int parenIdx = -1;
        int angleDepth = 0;
        for (int j = i; j < n; j++) {
            char c = ref.charAt(j);
            if (c == '<') angleDepth++;
            else if (c == '>' && angleDepth > 0) angleDepth--;
            else if (angleDepth == 0) {
                if (c == '{' || c == ';' || c == '=') return "";
                if (c == '(') { parenIdx = j; break; }
            }
        }
        if (parenIdx <= i) return "";
        String beforeParen = ref.substring(i, parenIdx).trim();
        // Extract top-level identifier tokens outside <...>
        var tokens = new ArrayList<String>();
        int d = 0;
        var cur = new StringBuilder();
        for (int j = 0; j < beforeParen.length(); j++) {
            char c = beforeParen.charAt(j);
            if (c == '<') {
                if (!cur.isEmpty()) { tokens.add(cur.toString()); cur.setLength(0); }
                d++;
            } else if (c == '>') {
                if (d > 0) d--;
            } else if (d == 0) {
                if (Character.isJavaIdentifierPart(c)) {
                    cur.append(c);
                } else if (!cur.isEmpty()) {
                    tokens.add(cur.toString());
                    cur.setLength(0);
                }
            }
        }
        if (!cur.isEmpty()) tokens.add(cur.toString());
        if (tokens.isEmpty()) return "";
        for (String tok : tokens) {
            if (tok.equals("class") || tok.equals("interface") || tok.equals("enum")
                    || tok.equals("record") || tok.equals("new") || tok.equals("return")) {
                return "";
            }
        }
        String candidate = tokens.get(tokens.size() - 1);
        if (!Character.isJavaIdentifierStart(candidate.charAt(0))) return "";
        int lastDot = Math.max(enclosingTypeFqn.lastIndexOf('.'), enclosingTypeFqn.lastIndexOf('$'));
        String simpleType = lastDot >= 0 ? enclosingTypeFqn.substring(lastDot + 1) : enclosingTypeFqn;
        if (candidate.equals(simpleType)) return "<init>";
        return candidate;
    }

    private static String extractDirectSelfTargetMethodName(String refText) {
        if (refText == null || refText.isBlank()) return "";
        String trimmed = refText.trim();
        int paren = trimmed.indexOf('(');
        if (paren <= 0) return "";
        String prefix = trimmed.substring(0, paren).trim();
        if (prefix.startsWith("this.")) {
            prefix = prefix.substring(5).trim();
        } else if (prefix.contains(".this.")) {
            prefix = prefix.substring(prefix.lastIndexOf(".this.") + 6).trim();
        } else if (prefix.indexOf('.') >= 0) {
            return ""; // External receiver (e.g. selfProxy.m(), service.m(), Foo.m())
        }
        return prefix.matches("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*") ? prefix : "";
    }

    /**
     * Pure structural entry point for {@code PolicyEvaluator} and unit tests.
     */
    public static Result detect(
            SnapshotIdentity snapshot,
            List<BeanClassInfo> beanClasses,
            List<IntraClassCall> intraClassCalls,
            SpringAopMetadataExtractor.Result aopResult) {
        Objects.requireNonNull(snapshot, "snapshot");
        var safeClasses = beanClasses == null ? List.<BeanClassInfo>of() : beanClasses;
        var safeCalls = intraClassCalls == null ? List.<IntraClassCall>of() : intraClassCalls;

        // Index ADVISES_METHOD triggers per (classFqn, methodName) and PROXIED_BY_ASPECT per classFqn
        var advisedByMethod = new HashMap<String, Set<String>>();
        var proxiedClasses = new HashSet<String>();
        if (aopResult != null) {
            for (var edge : aopResult.advisesMethodEdges()) {
                String key = edge.targetClass() + "#" + edge.targetMethod();
                advisedByMethod.computeIfAbsent(key, k -> new TreeSet<>())
                        .add("ADVISES_METHOD:" + edge.aspectClass() + "@" + edge.adviceKind().name());
                proxiedClasses.add(edge.targetClass());
            }
            for (var pe : aopResult.proxiedByAspectEdges()) {
                proxiedClasses.add(pe.targetClass());
            }
        }

        var classByFqn = new HashMap<String, BeanClassInfo>();
        var methodIndex = new HashMap<String, MethodInfo>();
        for (BeanClassInfo cls : safeClasses) {
            classByFqn.put(cls.classFqn(), cls);
            for (MethodInfo m : cls.methods()) {
                methodIndex.put(cls.classFqn() + "#" + m.methodName(), m);
            }
        }

        var findings = new ArrayList<ProxyTrapFinding>();

        // 1. Detect spring-aop-self-invocation-bypass
        for (IntraClassCall call : safeCalls) {
            if (!call.directSelfReceiver()) {
                continue; // Calls through an injected proxy field (e.g. selfProxy.m()) do not bypass Spring AOP.
            }
            BeanClassInfo cls = classByFqn.get(call.classFqn());
            MethodInfo target = methodIndex.get(call.classFqn() + "#" + call.targetMethod());
            if (target == null) continue;
            if (target.modifiers().contains("static")) continue;

            var triggers = new TreeSet<String>();
            collectProxyTriggerLabels(target.annotations(), triggers);
            if (cls != null && !call.callerMethod().equals(call.targetMethod())) {
                // Class-level @Transactional / @Async / @Cacheable also proxies public methods,
                // except intra-class self-calls bypass it unless already intercepted at entry;
                // method-level triggers or explicit ADVISES_METHOD always fire.
            }
            triggers.addAll(advisedByMethod.getOrDefault(call.classFqn() + "#" + call.targetMethod(), Set.of()));

            if (!triggers.isEmpty()) {
                findings.add(new ProxyTrapFinding(
                        RULE_SELF_INVOCATION_BYPASS,
                        "HIGH",
                        call.classFqn(),
                        call.callerMethod(),
                        call.targetMethod(),
                        triggers,
                        call.sourceFile(),
                        call.line(),
                        TrustTier.SOURCE_SYNTAX,
                        "Intra-class self-invocation " + call.callerMethod() + "() -> this."
                                + call.targetMethod() + "() bypasses Spring AOP proxy triggers " + triggers + "."));
            }
        }

        // 2. Detect spring-cglib-private-final-trap
        for (BeanClassInfo cls : safeClasses) {
            boolean classHasProxyAnnotations = hasProxyTriggerAnnotation(cls.classAnnotations());
            boolean classProxied = proxiedClasses.contains(cls.classFqn())
                    || classHasProxyAnnotations
                    || cls.methods().stream().anyMatch(m ->
                            hasProxyTriggerAnnotation(m.annotations())
                                    || advisedByMethod.containsKey(cls.classFqn() + "#" + m.methodName()));
            boolean classIsFinal = cls.classModifiers().contains("final");

            for (MethodInfo m : cls.methods()) {
                if (m.modifiers().contains("static")) continue;
                boolean methodPrivate = m.modifiers().contains("private");
                boolean methodFinal = m.modifiers().contains("final");
                if (!methodPrivate && !methodFinal && !classIsFinal) continue;

                var triggers = new TreeSet<String>();
                collectProxyTriggerLabels(m.annotations(), triggers);
                if (classProxied) {
                    collectRequestMappingLabels(m.annotations(), triggers);
                }
                triggers.addAll(advisedByMethod.getOrDefault(cls.classFqn() + "#" + m.methodName(), Set.of()));
                if (classHasProxyAnnotations && (methodPrivate || methodFinal || classIsFinal)) {
                    collectProxyTriggerLabels(cls.classAnnotations(), triggers);
                }

                // Trigger if method itself carries proxy/mapping/AOP triggers on a proxied bean,
                // or is private/final with @Transactional/@RequestMapping/@Aspect on a Spring bean.
                if (!triggers.isEmpty() && (cls.springBean() || classProxied || !advisedByMethod.getOrDefault(cls.classFqn() + "#" + m.methodName(), Set.of()).isEmpty())) {
                    String modifierDesc = methodPrivate ? "private"
                            : methodFinal ? "final" : "final-class";
                    findings.add(new ProxyTrapFinding(
                            RULE_CGLIB_PRIVATE_FINAL_TRAP,
                            "HIGH",
                            cls.classFqn(),
                            "",
                            m.methodName(),
                            triggers,
                            m.sourceFile(),
                            m.line(),
                            TrustTier.SOURCE_SYNTAX,
                            "CGLIB proxy cannot override " + modifierDesc + " method "
                                    + cls.classFqn() + "#" + m.methodName()
                                    + "() matched by " + triggers
                                    + "; proxy bypass or null @Autowired field access can occur at runtime."));
                }
            }
        }

        var sortedFindings = findings.stream().sorted().distinct().toList();
        if (sortedFindings.isEmpty()) {
            return Result.empty();
        }
        ContentDigest digest = IngestionEvidence.digest(Map.of(
                "provider", PROVIDER,
                "findings", sortedFindings));
        return new Result(digest, sortedFindings, List.of());
    }

    private static boolean isDirectSelfInvocationReference(String refText, String methodName) {
        if (refText == null || refText.isBlank()) {
            return true;
        }
        String trimmed = refText.trim();
        int paren = trimmed.indexOf('(');
        String prefix = paren >= 0 ? trimmed.substring(0, paren).trim() : trimmed;
        if (prefix.equals(methodName) || prefix.equals("this." + methodName)) {
            return true;
        }
        // Qualified this e.g. Outer.this.methodName
        return prefix.endsWith(".this." + methodName);
    }

    private static boolean hasProxyTriggerAnnotation(Set<String> anns) {
        for (String a : anns) {
            if (PROXY_INTERCEPTED_ANNOTATIONS.contains(a) || PROXY_INTERCEPTED_SIMPLE_NAMES.contains(simpleName(a))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRequestMappingAnnotation(Set<String> anns) {
        for (String a : anns) {
            if (REQUEST_MAPPING_ANNOTATIONS.contains(a) || REQUEST_MAPPING_SIMPLE_NAMES.contains(simpleName(a))) {
                return true;
            }
        }
        return false;
    }

    private static void collectProxyTriggerLabels(Set<String> anns, Set<String> out) {
        for (String a : anns) {
            if (PROXY_INTERCEPTED_ANNOTATIONS.contains(a) || PROXY_INTERCEPTED_SIMPLE_NAMES.contains(simpleName(a))) {
                out.add("@" + simpleName(a));
            }
        }
    }

    private static void collectRequestMappingLabels(Set<String> anns, Set<String> out) {
        for (String a : anns) {
            if (REQUEST_MAPPING_ANNOTATIONS.contains(a) || REQUEST_MAPPING_SIMPLE_NAMES.contains(simpleName(a))) {
                out.add("@" + simpleName(a));
            }
        }
    }

    private static boolean hasAny(Set<String> actual, Set<String> expected) {
        for (String a : actual) {
            if (expected.contains(a) || expected.contains(simpleName(a))) return true;
        }
        return false;
    }

    private static Set<String> collectAnnotations(List<SpringSourceEvidence.Annotation> anns) {
        var set = new TreeSet<String>();
        for (var ann : anns) {
            if (ann.name().isPresent()) {
                set.add(ann.name().orElseThrow());
            } else {
                String tok = extractAnnotationToken(ann.use().spelling());
                if (!tok.isEmpty()) set.add(tok);
            }
        }
        return set;
    }

    private static Set<String> extractModifiers(String spelling, List<SpringSourceEvidence.Annotation> anns) {
        if (spelling == null || spelling.isBlank()) return Set.of();
        String text = spelling;
        for (var ann : anns) {
            text = text.replace(ann.use().spelling(), " ");
        }
        text = text.replaceAll("(?s)/\\*.*?\\*/|//[^\\r\\n]*", " ").stripLeading();
        Matcher m = MODIFIER_PREFIX.matcher(text);
        if (!m.find()) return Set.of();
        String group = m.group(1).trim();
        if (group.isEmpty()) return Set.of();
        var mods = new TreeSet<String>();
        for (String tok : group.split("\\s+")) {
            if (!tok.isBlank()) mods.add(tok.trim());
        }
        return mods;
    }

    private static String extractSimpleMethodName(DeclarationRecord decl) {
        String cn = decl.entity().canonicalName();
        String prefix = "java:v1:[\"method\",";
        if (cn.startsWith(prefix)) {
            int depth = 0;
            for (int i = prefix.length(); i < cn.length(); i++) {
                char c = cn.charAt(i);
                if (c == '[') depth++;
                else if (c == ']' && --depth == 0) {
                    int start = cn.indexOf('"', i + 1);
                    int end = start >= 0 ? cn.indexOf("\",[", start + 1) : -1;
                    if (start >= 0 && end > start) return cn.substring(start + 1, end);
                    break;
                }
            }
        }
        int open = decl.spelling().indexOf('(');
        if (open > 0) {
            String before = decl.spelling().substring(0, open).trim();
            int sp = before.lastIndexOf(' ');
            if (sp >= 0) return before.substring(sp + 1).trim();
        }
        return cn;
    }

    private static String extractAnnotationToken(String spelling) {
        if (spelling == null) return "";
        String s = spelling.stripLeading();
        if (!s.startsWith("@")) return "";
        int i = 1;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        int start = i;
        while (i < s.length() && (Character.isJavaIdentifierPart(s.charAt(i)) || s.charAt(i) == '.')) i++;
        return s.substring(start, i);
    }

    private static String simpleName(String fqn) {
        if (fqn == null) return "";
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    private static String normalizePath(String path) {
        return path == null ? "" : path.replace('\\', '/').trim();
    }

    private static String safe(String v) {
        return v == null ? "" : v.trim();
    }
}
