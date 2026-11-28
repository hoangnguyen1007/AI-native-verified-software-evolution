package com.evolution.analysis.spring.universal;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.EntityIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.semantic.EntityKind;
import com.evolution.analysis.contract.semantic.EntityOrigin;
import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.evidence.AffectedOutput;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.evidence.EvidenceContext;
import com.evolution.analysis.evidence.EvidenceRequirement;
import com.evolution.analysis.evidence.EvidenceSubject;
import com.evolution.analysis.evidence.ProviderObservationReference;
import com.evolution.analysis.frontend.DeclarationRecord;
import com.evolution.analysis.frontend.TypeUseRecord;
import com.evolution.analysis.ingestion.IngestionEvidence;
import com.evolution.analysis.spring.condition.LiteralConditionAnnotation;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Passive ({@code --build=off}) Spring AOP {@code @Aspect} / {@code @Pointcut} / advice extractor
 * and deterministic pointcut-to-target matcher (Jasmine ASE '22, Algorithms 1–2).
 *
 * <p>Consumes both source-level {@link SpringSourceEvidence} and passive ASM bytecode
 * {@link BytecodeAutoConfigScanner.ScanResult}, parses standard AspectJ static pointcut designators
 * ({@code execution(...)}, {@code @annotation(...)}, {@code within(...)}, {@code @within(...)},
 * named {@code @Pointcut} references, and {@code &&}/{@code ||}/{@code !} combinators), emits
 * {@code ADVISES_METHOD} and {@code PROXIED_BY_ASPECT} edges sorted deterministically by
 * {@code (@Order, aspectClassName, advicePrecedence)}, and emits
 * {@link CapabilityGapRecord}{@code ("AOP_POINTCUT_DYNAMIC")} for dynamic/complex pointcuts ({@code I3}).
 */
public final class SpringAopMetadataExtractor {

    public static final VersionedIdentifier PROVIDER =
            new VersionedIdentifier("spring.aop-metadata", "wp2.1-v1");
    public static final VersionedIdentifier GAP_CATALOG =
            new VersionedIdentifier("evidence.spring-aop-gaps", "wp2.1-v1");

    /** Pinned Shared Evidence Contract constants. */
    public static final String EVIDENCE_FAMILY_AOP_ADVICE = "spring.aop-advice";
    public static final String EDGE_ADVISES_METHOD = "ADVISES_METHOD";
    public static final String EDGE_PROXIED_BY_ASPECT = "PROXIED_BY_ASPECT";
    public static final String GAP_AOP_POINTCUT_DYNAMIC = "AOP_POINTCUT_DYNAMIC";

    static final String ANN_ASPECT = "org.aspectj.lang.annotation.Aspect";
    static final String ANN_POINTCUT = "org.aspectj.lang.annotation.Pointcut";
    static final String ANN_AROUND = "org.aspectj.lang.annotation.Around";
    static final String ANN_BEFORE = "org.aspectj.lang.annotation.Before";
    static final String ANN_AFTER_RETURNING = "org.aspectj.lang.annotation.AfterReturning";
    static final String ANN_AFTER_THROWING = "org.aspectj.lang.annotation.AfterThrowing";
    static final String ANN_AFTER = "org.aspectj.lang.annotation.After";
    static final String ANN_ORDER = "org.springframework.core.annotation.Order";

    private static final int MAX_POINTCUT_EXPANSION_DEPTH = 8;

    private static final Pattern METHOD_HEADER_PATTERN = Pattern.compile(
            "^\\s*((?:(?:public|protected|private|static|final|abstract|synchronized|native|strictfp|default)\\s+)*)"
                    + "(?:<[^>{};]*>\\s+)?"
                    + "([\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}.$<>\\[\\]? ,]*?)\\s+"
                    + "([\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*)\\s*\\(([^)]*)\\)");

    /**
     * Deterministic advice precedence order following Jasmine ASE '22 Algorithm 2:
     * {@code AROUND -> BEFORE -> AFTER_RETURNING -> AFTER_THROWING -> AFTER}.
     */
    public enum AdviceKind {
        AROUND(0, ANN_AROUND),
        BEFORE(1, ANN_BEFORE),
        AFTER_RETURNING(2, ANN_AFTER_RETURNING),
        AFTER_THROWING(3, ANN_AFTER_THROWING),
        AFTER(4, ANN_AFTER);

        private final int precedence;
        private final String annotationFqn;

        AdviceKind(int precedence, String annotationFqn) {
            this.precedence = precedence;
            this.annotationFqn = annotationFqn;
        }

        public int precedence() {
            return precedence;
        }

        public String annotationFqn() {
            return annotationFqn;
        }

        public static Optional<AdviceKind> fromAnnotationFqn(String fqn) {
            if (fqn == null) return Optional.empty();
            for (var kind : values()) {
                if (kind.annotationFqn.equals(fqn)) return Optional.of(kind);
            }
            return Optional.empty();
        }
    }

    public record PointcutDefinition(
            String aspectClass,
            String name,
            String expression,
            String sourceFile,
            int line) implements Comparable<PointcutDefinition> {
        public PointcutDefinition {
            aspectClass = safe(aspectClass);
            name = safe(name);
            expression = safe(expression);
            sourceFile = normalizePath(sourceFile);
        }

        @Override
        public int compareTo(PointcutDefinition other) {
            int cmp = aspectClass.compareTo(other.aspectClass);
            if (cmp != 0) return cmp;
            return name.compareTo(other.name);
        }
    }

    public record AdviceDeclaration(
            String aspectClass,
            String adviceMethod,
            AdviceKind adviceKind,
            int order,
            String pointcutExpression,
            String sourceFile,
            int line,
            TrustTier trustTier) implements Comparable<AdviceDeclaration> {
        public AdviceDeclaration {
            aspectClass = safe(aspectClass);
            adviceMethod = safe(adviceMethod);
            adviceKind = Objects.requireNonNull(adviceKind, "adviceKind");
            pointcutExpression = safe(pointcutExpression);
            sourceFile = normalizePath(sourceFile);
            trustTier = trustTier == null ? TrustTier.SOURCE_SYNTAX : trustTier;
        }

        @Override
        public int compareTo(AdviceDeclaration other) {
            int cmp = Integer.compare(order, other.order);
            if (cmp != 0) return cmp;
            cmp = aspectClass.compareTo(other.aspectClass);
            if (cmp != 0) return cmp;
            cmp = Integer.compare(adviceKind.precedence(), other.adviceKind.precedence());
            if (cmp != 0) return cmp;
            cmp = adviceMethod.compareTo(other.adviceMethod);
            return cmp != 0 ? cmp : pointcutExpression.compareTo(other.pointcutExpression);
        }
    }

    public record TargetMethodDescriptor(
            String classFqn,
            String methodName,
            String returnTypeFqn,
            List<String> paramTypeFqns,
            Set<String> modifiers,
            Set<String> classAnnotations,
            Set<String> methodAnnotations,
            Set<String> superTypes,
            String sourceFile,
            int line) implements Comparable<TargetMethodDescriptor> {
        public TargetMethodDescriptor {
            classFqn = safe(classFqn);
            methodName = safe(methodName);
            returnTypeFqn = safe(returnTypeFqn).isEmpty() ? "void" : safe(returnTypeFqn);
            paramTypeFqns = paramTypeFqns == null ? List.of() : List.copyOf(paramTypeFqns);
            modifiers = modifiers == null ? Set.of() : Collections.unmodifiableSet(new TreeSet<>(modifiers));
            classAnnotations = classAnnotations == null ? Set.of() : Collections.unmodifiableSet(new TreeSet<>(classAnnotations));
            methodAnnotations = methodAnnotations == null ? Set.of() : Collections.unmodifiableSet(new TreeSet<>(methodAnnotations));
            superTypes = superTypes == null ? Set.of() : Collections.unmodifiableSet(new TreeSet<>(superTypes));
            sourceFile = normalizePath(sourceFile);
        }

        public String methodKey() {
            return classFqn + "#" + methodName + "(" + String.join(",", paramTypeFqns) + ")";
        }

        @Override
        public int compareTo(TargetMethodDescriptor other) {
            int cmp = classFqn.compareTo(other.classFqn);
            if (cmp != 0) return cmp;
            cmp = methodName.compareTo(other.methodName);
            if (cmp != 0) return cmp;
            return methodKey().compareTo(other.methodKey());
        }
    }

    /**
     * Pinned {@code ADVISES_METHOD} edge conforming to {@code "spring.aop-advice"} shared contract,
     * ordered deterministically by {@code (@Order, aspectClassName, advicePrecedence)} (Jasmine Alg. 2).
     */
    public record AdviceEdge(
            String aspectClass,
            String adviceMethod,
            AdviceKind adviceKind,
            int order,
            String pointcut,
            String targetClass,
            String targetMethod,
            String sourceFile,
            int line,
            TrustTier trustTier) implements Comparable<AdviceEdge> {
        public AdviceEdge {
            aspectClass = safe(aspectClass);
            adviceMethod = safe(adviceMethod);
            adviceKind = Objects.requireNonNull(adviceKind, "adviceKind");
            pointcut = safe(pointcut);
            targetClass = safe(targetClass);
            targetMethod = safe(targetMethod);
            sourceFile = normalizePath(sourceFile);
            trustTier = trustTier == null ? TrustTier.SOURCE_SYNTAX : trustTier;
        }

        public String edgeType() {
            return EDGE_ADVISES_METHOD;
        }

        public String evidenceFamily() {
            return EVIDENCE_FAMILY_AOP_ADVICE;
        }

        /** Canonical payload matching the pinned {@code spring.aop-advice} contract. */
        public Map<String, Object> toEvidencePayload() {
            var map = new TreeMap<String, Object>();
            map.put("adviceKind", adviceKind.name());
            map.put("adviceMethod", adviceMethod);
            map.put("aspectClass", aspectClass);
            map.put("edgeType", EDGE_ADVISES_METHOD);
            map.put("line", line);
            map.put("order", order);
            map.put("pointcut", pointcut);
            map.put("sourceFile", sourceFile);
            map.put("targetClass", targetClass);
            map.put("targetMethod", targetMethod);
            map.put("trustTier", trustTier.name());
            return Collections.unmodifiableMap(map);
        }

        @Override
        public int compareTo(AdviceEdge other) {
            int cmp = Integer.compare(order, other.order);
            if (cmp != 0) return cmp;
            cmp = aspectClass.compareTo(other.aspectClass);
            if (cmp != 0) return cmp;
            cmp = Integer.compare(adviceKind.precedence(), other.adviceKind.precedence());
            if (cmp != 0) return cmp;
            cmp = targetClass.compareTo(other.targetClass);
            if (cmp != 0) return cmp;
            cmp = targetMethod.compareTo(other.targetMethod);
            if (cmp != 0) return cmp;
            return adviceMethod.compareTo(other.adviceMethod);
        }
    }

    /** Pinned {@code PROXIED_BY_ASPECT} edge linking a target bean class to its advising aspect. */
    public record ProxiedBeanEdge(
            String targetClass,
            String aspectClass,
            int order,
            List<AdviceKind> adviceKinds,
            TrustTier trustTier) implements Comparable<ProxiedBeanEdge> {
        public ProxiedBeanEdge {
            targetClass = safe(targetClass);
            aspectClass = safe(aspectClass);
            adviceKinds = adviceKinds == null ? List.of() : adviceKinds.stream()
                    .distinct()
                    .sorted(Comparator.comparingInt(AdviceKind::precedence))
                    .toList();
            trustTier = trustTier == null ? TrustTier.SOURCE_SYNTAX : trustTier;
        }

        public String edgeType() {
            return EDGE_PROXIED_BY_ASPECT;
        }

        public Map<String, Object> toEvidencePayload() {
            var map = new TreeMap<String, Object>();
            map.put("adviceKinds", adviceKinds.stream().map(Enum::name).toList());
            map.put("aspectClass", aspectClass);
            map.put("edgeType", EDGE_PROXIED_BY_ASPECT);
            map.put("order", order);
            map.put("targetClass", targetClass);
            map.put("trustTier", trustTier.name());
            return Collections.unmodifiableMap(map);
        }

        @Override
        public int compareTo(ProxiedBeanEdge other) {
            int cmp = targetClass.compareTo(other.targetClass);
            if (cmp != 0) return cmp;
            cmp = Integer.compare(order, other.order);
            if (cmp != 0) return cmp;
            return aspectClass.compareTo(other.aspectClass);
        }
    }

    public record Result(
            ContentDigest identity,
            List<AdviceDeclaration> advices,
            List<PointcutDefinition> pointcuts,
            List<AdviceEdge> advisesMethodEdges,
            List<ProxiedBeanEdge> proxiedByAspectEdges,
            List<CapabilityGapRecord> gaps) {
        private static final Result EMPTY = new Result(
                IngestionEvidence.digest(List.of(PROVIDER, "empty")),
                List.of(), List.of(), List.of(), List.of(), List.of());

        public Result {
            identity = Objects.requireNonNull(identity, "identity");
            advices = advices == null ? List.of() : advices.stream().sorted().distinct().toList();
            pointcuts = pointcuts == null ? List.of() : pointcuts.stream().sorted().distinct().toList();
            advisesMethodEdges = advisesMethodEdges == null ? List.of()
                    : advisesMethodEdges.stream().sorted().distinct().toList();
            proxiedByAspectEdges = proxiedByAspectEdges == null ? List.of()
                    : proxiedByAspectEdges.stream().sorted().distinct().toList();
            gaps = gaps == null ? List.of() : gaps.stream().sorted().distinct().toList();
        }

        public static Result empty() {
            return EMPTY;
        }

        public boolean isEmpty() {
            return advices.isEmpty() && pointcuts.isEmpty() && advisesMethodEdges.isEmpty()
                    && proxiedByAspectEdges.isEmpty() && gaps.isEmpty();
        }

        /**
         * Returns the deterministically sorted {@code AdviceList} (Jasmine Alg. 2) for a target method.
         */
        public List<AdviceEdge> advicesForMethod(String targetClass, String targetMethod) {
            return advisesMethodEdges.stream()
                    .filter(e -> e.targetClass().equals(targetClass) && e.targetMethod().equals(targetMethod))
                    .sorted()
                    .toList();
        }

        public boolean isClassProxied(String classFqn) {
            return proxiedByAspectEdges.stream().anyMatch(e -> e.targetClass().equals(classFqn));
        }
    }

    private SpringAopMetadataExtractor() {}

    public static Result extract(SpringSourceEvidence source) {
        return extract(source, null);
    }

    /**
     * Extracts AOP aspects, pointcuts, advice declarations, and matched {@code ADVISES_METHOD} /
     * {@code PROXIED_BY_ASPECT} edges from both {@link SpringSourceEvidence} and optional
     * {@link BytecodeAutoConfigScanner.ScanResult}.
     */
    public static Result extract(SpringSourceEvidence source, BytecodeAutoConfigScanner.ScanResult bytecodeScan) {
        Objects.requireNonNull(source, "source");
        var snapshot = source.manifest().snapshot().identity();
        var advices = new ArrayList<AdviceDeclaration>();
        var pointcuts = new ArrayList<PointcutDefinition>();
        var targets = new ArrayList<TargetMethodDescriptor>();

        var docPathsById = new HashMap<com.evolution.analysis.contract.identity.SourceDocumentIdentity, String>();
        for (var doc : source.manifest().snapshot().documents()) {
            docPathsById.put(doc.identity(), normalizePath(doc.path()));
        }

        // Index return and parameter types by method EntityIdentity
        var returnTypeByMethod = new HashMap<EntityIdentity, String>();
        var paramTypesByMethod = new HashMap<EntityIdentity, List<String>>();
        for (TypeUseRecord t : source.frontend().types()) {
            if (t.owner().isEmpty()) continue;
            EntityIdentity ownerId = t.owner().orElseThrow();
            String role = t.role().value();
            if ("java.returns".equals(role)) {
                String resolved = t.type().target().map(source::typeName).orElse(t.type().spelling());
                returnTypeByMethod.putIfAbsent(ownerId, stripGenerics(resolved));
            } else if ("java.parameter-type".equals(role)) {
                String resolved = t.type().target().map(source::typeName).orElse(t.type().spelling());
                paramTypesByMethod.computeIfAbsent(ownerId, k -> new ArrayList<>()).add(stripGenerics(resolved));
            }
        }

        var memberNamesById = new HashMap<EntityIdentity, String>();
        for (var md : source.frontend().memberDeclarations()) {
            memberNamesById.putIfAbsent(md.member(), md.name());
        }

        // Scan source declarations for @Aspect classes and candidate target bean methods
        var aspectClassIds = new HashSet<EntityIdentity>();
        for (DeclarationRecord decl : source.declarations().values()) {
            if (decl.entity().origin() != EntityOrigin.PROJECT || decl.entity().kind() != EntityKind.TYPE) {
                continue;
            }
            EntityIdentity typeId = decl.entity().identity();
            String classFqn = source.typeName(typeId);
            if (classFqn == null || classFqn.isBlank()) continue;

            var typeAnns = source.annotations(typeId);
            boolean isAspect = typeAnns.stream().anyMatch(a -> matchesAnnotation(a, ANN_ASPECT, "Aspect"));
            String sourceFile = decl.entity().declaration()
                    .map(sp -> docPathsById.getOrDefault(sp.document(), "")).orElse("");
            int classLine = decl.entity().declaration().map(sp -> sp.startLine()).orElse(1);

            if (isAspect) {
                aspectClassIds.add(typeId);
                int classOrder = extractOrder(typeAnns);
                for (EntityIdentity memberId : source.declaredMembers(typeId)) {
                    DeclarationRecord mDecl = source.declarations().get(memberId);
                    if (mDecl == null || mDecl.entity().kind() != EntityKind.METHOD) continue;
                    String mName = memberNamesById.getOrDefault(memberId, extractSimpleMethodName(mDecl));
                    int mLine = mDecl.entity().declaration().map(sp -> sp.startLine()).orElse(classLine);
                    var mAnns = source.annotations(memberId);
                    int effectiveOrder = hasOrderAnnotation(mAnns) ? extractOrder(mAnns) : classOrder;

                    for (var ann : mAnns) {
                        if (matchesAnnotation(ann, ANN_POINTCUT, "Pointcut")) {
                            String expr = extractPointcutAttr(ann.use().spelling(), List.of("value"));
                            if (!expr.isEmpty()) {
                                pointcuts.add(new PointcutDefinition(classFqn, mName, expr, sourceFile, mLine));
                            }
                            continue;
                        }
                        Optional<AdviceKind> kindOpt = resolveAdviceKind(ann);
                        if (kindOpt.isPresent()) {
                            String expr = extractPointcutAttr(ann.use().spelling(), List.of("pointcut", "value"));
                            TrustTier tier = ann.name().isPresent() && !source.framework().artifacts().isEmpty()
                                    ? TrustTier.COMPILER : TrustTier.SOURCE_SYNTAX;
                            advices.add(new AdviceDeclaration(
                                    classFqn, mName, kindOpt.orElseThrow(), effectiveOrder, expr,
                                    sourceFile, mLine, tier));
                        }
                    }
                }
            }
        }

        // Collect all non-aspect project methods as potential AOP targets
        for (DeclarationRecord decl : source.declarations().values()) {
            if (decl.entity().origin() != EntityOrigin.PROJECT || decl.entity().kind() != EntityKind.TYPE) {
                continue;
            }
            EntityIdentity typeId = decl.entity().identity();
            if (aspectClassIds.contains(typeId)) continue;
            String classFqn = source.typeName(typeId);
            if (classFqn == null || classFqn.isBlank()) continue;

            var classAnns = collectAnnotationNames(source.annotations(typeId));
            var superTypes = new TreeSet<String>();
            for (EntityIdentity anc : source.ancestry(typeId, 32)) {
                String ancName = source.typeName(anc);
                if (ancName != null && !ancName.isBlank()) {
                    superTypes.add(ancName);
                }
            }
            String sourceFile = decl.entity().declaration()
                    .map(sp -> docPathsById.getOrDefault(sp.document(), "")).orElse("");

            for (EntityIdentity memberId : source.declaredMembers(typeId)) {
                DeclarationRecord mDecl = source.declarations().get(memberId);
                if (mDecl == null || mDecl.entity().kind() != EntityKind.METHOD) continue;
                ParsedMethodHeader header = parseMethodHeader(mDecl, source.annotations(memberId));
                String methodName = memberNamesById.getOrDefault(memberId,
                        header.name().isEmpty() ? extractSimpleMethodName(mDecl) : header.name());
                String returnType = returnTypeByMethod.getOrDefault(memberId, header.returnType());
                List<String> paramTypes = paramTypesByMethod.containsKey(memberId)
                        ? paramTypesByMethod.get(memberId)
                        : header.paramTypes();
                var methodAnns = collectAnnotationNames(source.annotations(memberId));
                int line = mDecl.entity().declaration().map(sp -> sp.startLine()).orElse(1);

                targets.add(new TargetMethodDescriptor(
                        classFqn, methodName, returnType, paramTypes,
                        header.modifiers(), classAnns, methodAnns, superTypes, sourceFile, line));
            }
        }

        // Merge passive ASM bytecode aspects from BytecodeAutoConfigScanner when supplied
        if (bytecodeScan != null && !bytecodeScan.aspects().isEmpty()) {
            for (var aspect : bytecodeScan.aspects()) {
                for (var pc : aspect.pointcuts()) {
                    pointcuts.add(new PointcutDefinition(
                            aspect.classFqn(), pc.methodName(), pc.expression(),
                            aspect.jarLogicalName() + "!/" + aspect.evidence().metadataPointer(), 1));
                }
                for (var adv : aspect.advices()) {
                    AdviceKind kind = parseAdviceKindToken(adv.adviceKind());
                    if (kind != null) {
                        advices.add(new AdviceDeclaration(
                                aspect.classFqn(), adv.methodName(), kind, aspect.order(),
                                adv.pointcutExpression(),
                                aspect.jarLogicalName() + "!/" + aspect.evidence().metadataPointer(),
                                1, TrustTier.COMPILER));
                    }
                }
            }
        }

        return match(snapshot, advices, pointcuts, targets);
    }

    /**
     * Matches extracted {@link AdviceDeclaration}s and {@link PointcutDefinition}s against target
     * bean methods and produces deterministic {@code ADVISES_METHOD} & {@code PROXIED_BY_ASPECT} edges
     * plus {@code AOP_POINTCUT_DYNAMIC} capability gaps.
     */
    public static Result match(
            SnapshotIdentity snapshot,
            List<AdviceDeclaration> advices,
            List<PointcutDefinition> pointcuts,
            List<TargetMethodDescriptor> targets) {
        Objects.requireNonNull(snapshot, "snapshot");
        var safeAdvices = advices == null ? List.<AdviceDeclaration>of() : advices.stream().sorted().distinct().toList();
        var safePointcuts = pointcuts == null ? List.<PointcutDefinition>of() : pointcuts.stream().sorted().distinct().toList();
        var safeTargets = targets == null ? List.<TargetMethodDescriptor>of() : targets.stream().sorted().distinct().toList();

        if (safeAdvices.isEmpty() && safePointcuts.isEmpty()) {
            return Result.empty();
        }

        var pointcutIndex = new HashMap<String, String>();
        for (var pc : safePointcuts) {
            pointcutIndex.put(pc.aspectClass() + "." + pc.name(), pc.expression());
            String simpleClass = simpleName(pc.aspectClass());
            pointcutIndex.putIfAbsent(simpleClass + "." + pc.name(), pc.expression());
        }

        var edges = new ArrayList<AdviceEdge>();
        var gaps = new ArrayList<CapabilityGapRecord>();
        // (targetClass, aspectClass, order) -> set of AdviceKinds
        var proxyAgg = new TreeMap<String, ProxyAcc>();

        for (var advice : safeAdvices) {
            if (advice.pointcutExpression().isBlank()) {
                gaps.add(createDynamicPointcutGap(snapshot, advice, "Empty or missing pointcut expression"));
                continue;
            }
            ParseOutcome parsed = parsePointcut(
                    advice.pointcutExpression(), advice.aspectClass(), pointcutIndex, new HashSet<>(), 0);
            if (parsed.dynamicReason() != null) {
                gaps.add(createDynamicPointcutGap(snapshot, advice, parsed.dynamicReason()));
            }
            if (parsed.ast() == null) {
                continue;
            }
            for (var target : safeTargets) {
                if (target.classFqn().equals(advice.aspectClass())) {
                    continue; // Aspects do not advise themselves in standard Spring AOP
                }
                if (evaluateExpr(parsed.ast(), target)) {
                    edges.add(new AdviceEdge(
                            advice.aspectClass(),
                            advice.adviceMethod(),
                            advice.adviceKind(),
                            advice.order(),
                            advice.pointcutExpression(),
                            target.classFqn(),
                            target.methodName(),
                            advice.sourceFile(),
                            advice.line(),
                            advice.trustTier()));
                    String proxyKey = target.classFqn() + "|" + advice.order() + "|" + advice.aspectClass();
                    var acc = proxyAgg.computeIfAbsent(proxyKey,
                            k -> new ProxyAcc(target.classFqn(), advice.aspectClass(), advice.order(), advice.trustTier()));
                    acc.kinds.add(advice.adviceKind());
                }
            }
        }

        var sortedEdges = edges.stream().sorted().distinct().toList();
        var proxiedEdges = proxyAgg.values().stream()
                .map(a -> new ProxiedBeanEdge(a.targetClass, a.aspectClass, a.order,
                        new ArrayList<>(a.kinds), a.trustTier))
                .sorted()
                .distinct()
                .toList();
        var sortedGaps = gaps.stream().sorted().distinct().toList();

        ContentDigest digest = IngestionEvidence.digest(Map.of(
                "provider", PROVIDER,
                "advices", safeAdvices,
                "pointcuts", safePointcuts,
                "advisesMethodEdges", sortedEdges,
                "proxiedByAspectEdges", proxiedEdges,
                "gaps", sortedGaps));
        return new Result(digest, safeAdvices, safePointcuts, sortedEdges, proxiedEdges, sortedGaps);
    }

    private static final class ProxyAcc {
        final String targetClass;
        final String aspectClass;
        final int order;
        final TrustTier trustTier;
        final Set<AdviceKind> kinds = new TreeSet<>(Comparator.comparingInt(AdviceKind::precedence));

        ProxyAcc(String targetClass, String aspectClass, int order, TrustTier trustTier) {
            this.targetClass = targetClass;
            this.aspectClass = aspectClass;
            this.order = order;
            this.trustTier = trustTier;
        }
    }

    // -------------------------------------------------------------------------
    // Recursive-descent AspectJ pointcut expression parser & static matcher
    // -------------------------------------------------------------------------

    private sealed interface PointcutExpr permits AndExpr, OrExpr, NotExpr, ExecutionExpr,
            AnnotationExpr, WithinExpr, WithinAnnotationExpr, BeanPatternExpr, AlwaysFalseExpr {}

    private record AndExpr(PointcutExpr left, PointcutExpr right) implements PointcutExpr {}
    private record OrExpr(PointcutExpr left, PointcutExpr right) implements PointcutExpr {}
    private record NotExpr(PointcutExpr inner) implements PointcutExpr {}
    private record ExecutionExpr(ExecutionSpec spec) implements PointcutExpr {}
    private record AnnotationExpr(String annotationPattern) implements PointcutExpr {}
    private record WithinExpr(String typePattern) implements PointcutExpr {}
    private record WithinAnnotationExpr(String annotationPattern) implements PointcutExpr {}
    private record BeanPatternExpr(String beanGlob) implements PointcutExpr {}
    private record AlwaysFalseExpr() implements PointcutExpr {}

    private record ExecutionSpec(
            Set<String> requiredModifiers,
            Set<String> excludedModifiers,
            String returnTypePattern,
            String declaringTypePattern,
            String methodNamePattern,
            List<String> paramPatterns) {}

    private record ParseOutcome(PointcutExpr ast, String dynamicReason) {}

    private static ParseOutcome parsePointcut(
            String raw,
            String currentAspectClass,
            Map<String, String> pointcuts,
            Set<String> expandingRefs,
            int depth) {
        if (depth > MAX_POINTCUT_EXPANSION_DEPTH) {
            return new ParseOutcome(null, "Pointcut reference expansion exceeded max depth " + MAX_POINTCUT_EXPANSION_DEPTH);
        }
        var parser = new PointcutParser(raw, currentAspectClass, pointcuts, expandingRefs, depth);
        PointcutExpr expr = parser.parseOr();
        parser.skipWs();
        if (parser.pos < parser.len) {
            parser.recordDynamic("Unparsed trailing pointcut tokens: " + parser.text.substring(parser.pos));
        }
        return new ParseOutcome(expr, parser.dynamicReasons.isEmpty() ? null : String.join("; ", parser.dynamicReasons));
    }

    private static final class PointcutParser {
        final String text;
        final int len;
        final String currentAspectClass;
        final Map<String, String> pointcuts;
        final Set<String> expandingRefs;
        final int depth;
        final List<String> dynamicReasons = new ArrayList<>();
        int pos;

        PointcutParser(String text, String currentAspectClass, Map<String, String> pointcuts,
                Set<String> expandingRefs, int depth) {
            this.text = text == null ? "" : text;
            this.len = this.text.length();
            this.currentAspectClass = currentAspectClass;
            this.pointcuts = pointcuts;
            this.expandingRefs = expandingRefs;
            this.depth = depth;
        }

        void recordDynamic(String reason) {
            if (!dynamicReasons.contains(reason)) {
                dynamicReasons.add(reason);
            }
        }

        PointcutExpr parseOr() {
            PointcutExpr left = parseAnd();
            while (true) {
                skipWs();
                if (matchOperator("||") || matchWordOperator("or")) {
                    PointcutExpr right = parseAnd();
                    if (left == null) left = right;
                    else if (right != null) left = new OrExpr(left, right);
                } else {
                    break;
                }
            }
            return left;
        }

        PointcutExpr parseAnd() {
            PointcutExpr left = parseUnary();
            while (true) {
                skipWs();
                if (matchOperator("&&") || matchWordOperator("and")) {
                    PointcutExpr right = parseUnary();
                    if (left == null || right == null) {
                        // A dynamic conjunct constrains runtime matches; fail closed without dropping the gap.
                        left = null;
                    } else {
                        left = new AndExpr(left, right);
                    }
                } else {
                    break;
                }
            }
            return left;
        }

        PointcutExpr parseUnary() {
            skipWs();
            if (matchOperator("!") || matchWordOperator("not")) {
                PointcutExpr inner = parseUnary();
                return inner == null ? null : new NotExpr(inner);
            }
            if (pos < len && text.charAt(pos) == '(') {
                pos++;
                PointcutExpr inner = parseOr();
                skipWs();
                if (pos < len && text.charAt(pos) == ')') {
                    pos++;
                } else {
                    recordDynamic("Unclosed parenthesized pointcut expression");
                }
                return inner;
            }
            return parsePrimary();
        }

        PointcutExpr parsePrimary() {
            skipWs();
            if (pos >= len) return null;
            int start = pos;
            if (text.charAt(pos) == '@') {
                pos++;
            }
            while (pos < len && (Character.isJavaIdentifierPart(text.charAt(pos)) || text.charAt(pos) == '.')) {
                pos++;
            }
            String token = text.substring(start, pos);
            skipWs();
            if (token.isEmpty()) {
                recordDynamic("Unexpected pointcut token at offset " + pos);
                pos = len;
                return null;
            }
            String argContent = "";
            boolean hasParens = false;
            if (pos < len && text.charAt(pos) == '(') {
                hasParens = true;
                argContent = readBalancedParens();
            }

            switch (token) {
                case "execution" -> {
                    if (!hasParens || argContent.isBlank()) {
                        recordDynamic("Empty execution() designator");
                        return null;
                    }
                    ExecutionSpec spec = parseExecutionSpec(argContent);
                    if (spec == null) {
                        recordDynamic("Unsupported execution() signature: " + argContent);
                        return null;
                    }
                    return new ExecutionExpr(spec);
                }
                case "@annotation" -> {
                    String inner = argContent.trim();
                    if (inner.isEmpty() || !looksLikeTypePattern(inner)) {
                        recordDynamic("Dynamic @annotation(" + inner + ") parameter binding");
                        return null;
                    }
                    return new AnnotationExpr(inner);
                }
                case "within" -> {
                    String inner = argContent.trim();
                    if (inner.isEmpty()) {
                        recordDynamic("Empty within() designator");
                        return null;
                    }
                    return new WithinExpr(inner);
                }
                case "@within" -> {
                    String inner = argContent.trim();
                    if (inner.isEmpty() || !looksLikeTypePattern(inner)) {
                        recordDynamic("Dynamic @within(" + inner + ") parameter binding");
                        return null;
                    }
                    return new WithinAnnotationExpr(inner);
                }
                case "args", "@args", "this", "target", "@target", "cflow", "cflowbelow", "if" -> {
                    recordDynamic("Dynamic pointcut designator " + token + "(" + argContent.trim() + ")");
                    return null;
                }
                case "bean" -> {
                    String inner = argContent.trim();
                    if (inner.isEmpty() || inner.contains("${") || inner.contains("#{")
                            || inner.contains(" ") || !inner.matches("[A-Za-z0-9_.*-]+")) {
                        recordDynamic("Dynamic bean(" + inner + ") pointcut designator");
                        return null;
                    }
                    // Dynamic/name-based bean() selectors without exact static bean registry fail closed with gap.
                    if (inner.equals("*") || inner.contains("..")) {
                        recordDynamic("Dynamic wildcard bean(" + inner + ") pointcut designator");
                        return null;
                    }
                    return new BeanPatternExpr(inner);
                }
                default -> {
                    // Named @Pointcut reference: local myPc() or qualified pkg.Cls.myPc()
                    if (!hasParens) {
                        recordDynamic("Unrecognized pointcut token: " + token);
                        return null;
                    }
                    // Named pointcut binding runtime arguments e.g. myPc(arg)
                    if (!argContent.isBlank()) {
                        recordDynamic("Dynamic parameterized pointcut reference " + token + "(" + argContent.trim() + ")");
                        return null;
                    }
                    String key = token.contains(".") ? token : (currentAspectClass + "." + token);
                    String referenced = pointcuts.get(key);
                    if (referenced == null && token.contains(".")) {
                        // Try simple class + method key
                        int lastDot = token.lastIndexOf('.');
                        String ownerPart = token.substring(0, lastDot);
                        String methodPart = token.substring(lastDot + 1);
                        referenced = pointcuts.get(simpleName(ownerPart) + "." + methodPart);
                    }
                    if (referenced == null) {
                        recordDynamic("Unresolved named pointcut reference: " + token + "()");
                        return null;
                    }
                    if (!expandingRefs.add(key)) {
                        recordDynamic("Cyclic @Pointcut reference: " + key);
                        return null;
                    }
                    try {
                        String refOwner = token.contains(".")
                                ? token.substring(0, token.lastIndexOf('.'))
                                : currentAspectClass;
                        ParseOutcome nested = parsePointcut(referenced, refOwner, pointcuts, expandingRefs, depth + 1);
                        if (nested.dynamicReason() != null) {
                            recordDynamic(nested.dynamicReason());
                        }
                        return nested.ast();
                    } finally {
                        expandingRefs.remove(key);
                    }
                }
            }
        }

        String readBalancedParens() {
            if (pos >= len || text.charAt(pos) != '(') return "";
            pos++; // consume '('
            int start = pos;
            int parenDepth = 1;
            while (pos < len && parenDepth > 0) {
                char c = text.charAt(pos++);
                if (c == '(') parenDepth++;
                else if (c == ')') parenDepth--;
            }
            if (parenDepth != 0) {
                recordDynamic("Unclosed parenthesis in pointcut designator");
                return text.substring(start).trim();
            }
            return text.substring(start, pos - 1);
        }

        void skipWs() {
            while (pos < len && Character.isWhitespace(text.charAt(pos))) pos++;
        }

        boolean matchOperator(String op) {
            if (text.startsWith(op, pos)) {
                pos += op.length();
                return true;
            }
            return false;
        }

        boolean matchWordOperator(String word) {
            int wlen = word.length();
            if (pos + wlen > len) return false;
            if (!text.regionMatches(true, pos, word, 0, wlen)) return false;
            if (pos + wlen < len && Character.isJavaIdentifierPart(text.charAt(pos + wlen))) return false;
            pos += wlen;
            return true;
        }
    }

    private static ExecutionSpec parseExecutionSpec(String raw) {
        String s = raw.trim();
        // Strip optional trailing "throws ..." clause
        int throwsIdx = indexOfWordOutsideParens(s, "throws");
        if (throwsIdx > 0) {
            s = s.substring(0, throwsIdx).trim();
        }
        int openParen = s.indexOf('(');
        int closeParen = s.lastIndexOf(')');
        if (openParen < 0 || closeParen < openParen) {
            return null;
        }
        String paramPart = s.substring(openParen + 1, closeParen).trim();
        String head = s.substring(0, openParen).trim();
        if (head.isEmpty()) return null;

        // Tokenize head by whitespace outside angle brackets
        List<String> tokens = splitWhitespaceOutsideGenerics(head);
        if (tokens.size() < 2) {
            return null;
        }
        var requiredMods = new TreeSet<String>();
        var excludedMods = new TreeSet<String>();
        int idx = 0;
        while (idx < tokens.size() - 2) {
            String tok = tokens.get(idx);
            if (isModifierToken(tok)) {
                if (tok.startsWith("!")) excludedMods.add(tok.substring(1));
                else requiredMods.add(tok);
                idx++;
            } else {
                break;
            }
        }
        if (idx + 2 > tokens.size()) {
            return null;
        }
        String returnType = tokens.get(idx);
        String nameSpec = tokens.get(tokens.size() - 1);

        String declaringTypePattern;
        String methodNamePattern;
        int dot = nameSpec.lastIndexOf('.');
        if (dot >= 0) {
            declaringTypePattern = nameSpec.substring(0, dot);
            // Handle "com.acme.service..*()" where lastIndexOf('.') splits inside ".."
            if (declaringTypePattern.endsWith(".")) {
                // e.g. "com.acme.service..foo" -> declaringType = "com.acme.service..*", method = "foo"
                declaringTypePattern = declaringTypePattern + "*";
            }
            methodNamePattern = nameSpec.substring(dot + 1);
        } else {
            declaringTypePattern = "*";
            methodNamePattern = nameSpec;
        }

        List<String> params = new ArrayList<>();
        if (!paramPart.isEmpty()) {
            for (String p : splitCommasOutsideGenerics(paramPart)) {
                String pt = p.trim();
                if (!pt.isEmpty()) params.add(pt);
            }
        }
        return new ExecutionSpec(requiredMods, excludedMods, returnType, declaringTypePattern, methodNamePattern, params);
    }

    private static boolean evaluateExpr(PointcutExpr expr, TargetMethodDescriptor target) {
        if (expr instanceof AndExpr a) return evaluateExpr(a.left(), target) && evaluateExpr(a.right(), target);
        if (expr instanceof OrExpr o) return evaluateExpr(o.left(), target) || evaluateExpr(o.right(), target);
        if (expr instanceof NotExpr n) return !evaluateExpr(n.inner(), target);
        if (expr instanceof ExecutionExpr ex) return matchesExecution(ex.spec(), target);
        if (expr instanceof AnnotationExpr ann) return matchesAnnotationSet(ann.annotationPattern(), target.methodAnnotations());
        if (expr instanceof WithinExpr w) return matchesTypePattern(w.typePattern(), target.classFqn(), target.superTypes());
        if (expr instanceof WithinAnnotationExpr wa) return matchesAnnotationSet(wa.annotationPattern(), target.classAnnotations());
        if (expr instanceof BeanPatternExpr b) {
            String simple = simpleName(target.classFqn());
            String decap = simple.isEmpty() ? "" : Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
            return globMatch(b.beanGlob(), decap) || globMatch(b.beanGlob(), simple);
        }
        return false;
    }

    private static boolean matchesExecution(ExecutionSpec spec, TargetMethodDescriptor target) {
        for (String req : spec.requiredModifiers()) {
            if (!target.modifiers().contains(req)) return false;
        }
        for (String exc : spec.excludedModifiers()) {
            if (target.modifiers().contains(exc)) return false;
        }
        if (!matchesReturnType(spec.returnTypePattern(), target.returnTypeFqn())) {
            return false;
        }
        if (!matchesTypePattern(spec.declaringTypePattern(), target.classFqn(), target.superTypes())) {
            return false;
        }
        if (!globMatch(spec.methodNamePattern(), target.methodName())) {
            return false;
        }
        return matchesParams(spec.paramPatterns(), target.paramTypeFqns());
    }

    private static boolean matchesReturnType(String pattern, String actualType) {
        if ("*".equals(pattern) || "..".equals(pattern)) return true;
        String cleanActual = stripGenerics(actualType);
        String cleanPat = stripGenerics(pattern);
        if (cleanPat.equals(cleanActual) || (!cleanPat.contains(".") && cleanPat.equals(simpleName(cleanActual)))) {
            return true;
        }
        return matchesTypePattern(cleanPat, cleanActual, Set.of());
    }

    static boolean matchesTypePattern(String rawPattern, String classFqn, Set<String> superTypes) {
        if (rawPattern == null || rawPattern.isBlank() || "*".equals(rawPattern) || "..*".equals(rawPattern) || "*..*".equals(rawPattern)) {
            return true;
        }
        boolean includeSubtypes = rawPattern.endsWith("+");
        String pattern = includeSubtypes ? rawPattern.substring(0, rawPattern.length() - 1) : rawPattern;
        if (matchesSingleTypePattern(pattern, classFqn)) {
            return true;
        }
        if (includeSubtypes && superTypes != null) {
            for (String sup : superTypes) {
                if (matchesSingleTypePattern(pattern, sup)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matchesSingleTypePattern(String pattern, String fqn) {
        if (pattern.equals(fqn)) return true;
        if (!pattern.contains(".") && !pattern.contains("*")) {
            return pattern.equals(simpleName(fqn));
        }
        String regex = aspectJTypePatternToRegex(pattern);
        return Pattern.matches(regex, fqn);
    }

    private static String aspectJTypePatternToRegex(String pattern) {
        // Convert AspectJ type pattern:
        // ".." matches zero or more package segments (".*" or boundary),
        // "*" matches any chars within a single identifier/segment (no dot),
        // "." matches literal dot.
        var sb = new StringBuilder("^");
        int i = 0;
        int n = pattern.length();
        while (i < n) {
            if (pattern.startsWith("..", i)) {
                if (i == 0) {
                    sb.append("(?:.*\\.)?");
                } else if (i + 2 == n) {
                    sb.append("(?:\\..*)?");
                } else {
                    sb.append("(?:\\.|\\..*\\.)");
                }
                i += 2;
                // Consume a single following dot if pattern had "..*" vs "...": no dot after ".." normally,
                // handle "a.b..*" where after ".." comes "*"
            } else {
                char c = pattern.charAt(i++);
                if (c == '*') {
                    sb.append("[^.]*");
                } else if (c == '.') {
                    sb.append("\\.");
                } else if ("\\^$+?|()[]{}".indexOf(c) >= 0) {
                    sb.append('\\').append(c);
                } else {
                    sb.append(c);
                }
            }
        }
        sb.append('$');
        return sb.toString();
    }

    private static boolean matchesParams(List<String> patterns, List<String> actuals) {
        return matchParamsRec(patterns, 0, actuals, 0);
    }

    private static boolean matchParamsRec(List<String> patterns, int pi, List<String> actuals, int ai) {
        if (pi == patterns.size() && ai == actuals.size()) return true;
        if (pi == patterns.size()) return false;
        String pat = patterns.get(pi);
        if ("..".equals(pat)) {
            if (matchParamsRec(patterns, pi + 1, actuals, ai)) return true;
            return ai < actuals.size() && matchParamsRec(patterns, pi, actuals, ai + 1);
        }
        if (ai >= actuals.size()) return false;
        if ("*".equals(pat) || matchesReturnType(pat, actuals.get(ai))) {
            return matchParamsRec(patterns, pi + 1, actuals, ai + 1);
        }
        return false;
    }

    private static boolean matchesAnnotationSet(String annPattern, Set<String> annotations) {
        if (annotations == null || annotations.isEmpty()) return false;
        for (String ann : annotations) {
            if (matchesSingleTypePattern(annPattern, ann) || simpleName(ann).equals(annPattern)) {
                return true;
            }
        }
        return false;
    }

    private static boolean globMatch(String pattern, String value) {
        if ("*".equals(pattern)) return true;
        var sb = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') sb.append(".*");
            else if (c == '?') sb.append('.');
            else if ("\\^$+()[]{}|.".indexOf(c) >= 0) sb.append('\\').append(c);
            else sb.append(c);
        }
        sb.append('$');
        return Pattern.matches(sb.toString(), value);
    }

    private static boolean looksLikeTypePattern(String s) {
        if (s.contains(".") || s.contains("*")) return true;
        return !s.isEmpty() && Character.isUpperCase(s.charAt(0));
    }

    private static boolean isModifierToken(String tok) {
        String clean = tok.startsWith("!") ? tok.substring(1) : tok;
        return Set.of("public", "protected", "private", "static", "final", "abstract", "synchronized").contains(clean);
    }

    private static List<String> splitWhitespaceOutsideGenerics(String text) {
        var out = new ArrayList<String>();
        var cur = new StringBuilder();
        int angle = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '<') angle++;
            else if (c == '>' && angle > 0) angle--;
            if (angle == 0 && Character.isWhitespace(c)) {
                if (!cur.isEmpty()) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) out.add(cur.toString());
        return out;
    }

    private static List<String> splitCommasOutsideGenerics(String text) {
        var out = new ArrayList<String>();
        var cur = new StringBuilder();
        int angle = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '<') angle++;
            else if (c == '>' && angle > 0) angle--;
            if (angle == 0 && c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) out.add(cur.toString());
        return out;
    }

    private static int indexOfWordOutsideParens(String s, String word) {
        int paren = 0;
        for (int i = 0; i <= s.length() - word.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') paren++;
            else if (c == ')' && paren > 0) paren--;
            else if (paren == 0 && s.regionMatches(i, word, 0, word.length())) {
                boolean leftOk = i == 0 || Character.isWhitespace(s.charAt(i - 1));
                boolean rightOk = i + word.length() == s.length() || Character.isWhitespace(s.charAt(i + word.length()));
                if (leftOk && rightOk) return i;
            }
        }
        return -1;
    }

    // -------------------------------------------------------------------------
    // Source evidence helpers
    // -------------------------------------------------------------------------

    private record ParsedMethodHeader(String name, String returnType, List<String> paramTypes, Set<String> modifiers) {}

    private static ParsedMethodHeader parseMethodHeader(
            DeclarationRecord decl,
            List<SpringSourceEvidence.Annotation> anns) {
        String text = decl.spelling();
        for (var ann : anns) {
            text = text.replace(ann.use().spelling(), " ");
        }
        text = text.replaceAll("(?s)/\\*.*?\\*/|//[^\\r\\n]*", " ").stripLeading();
        Matcher m = METHOD_HEADER_PATTERN.matcher(text);
        if (!m.find()) {
            return new ParsedMethodHeader(extractSimpleMethodName(decl), "void", List.of(), Set.of());
        }
        var mods = new TreeSet<String>();
        String modRaw = m.group(1).trim();
        if (!modRaw.isEmpty()) {
            for (String tok : modRaw.split("\\s+")) {
                if (!tok.isBlank()) mods.add(tok.trim());
            }
        }
        String ret = stripGenerics(m.group(2).trim());
        String name = m.group(3).trim();
        String paramsRaw = m.group(4).trim();
        var paramTypes = new ArrayList<String>();
        if (!paramsRaw.isEmpty()) {
            for (String p : splitCommasOutsideGenerics(paramsRaw)) {
                String clean = p.replaceAll("@[\\w.]+(?:\\([^)]*\\))?", " ").replaceAll("\\bfinal\\b", " ").trim();
                List<String> parts = splitWhitespaceOutsideGenerics(clean);
                if (parts.size() >= 2) {
                    paramTypes.add(stripGenerics(parts.get(parts.size() - 2)).replace("...", "[]"));
                } else if (parts.size() == 1) {
                    paramTypes.add(stripGenerics(parts.getFirst()));
                }
            }
        }
        return new ParsedMethodHeader(name, ret, paramTypes, mods);
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

    private static Set<String> collectAnnotationNames(List<SpringSourceEvidence.Annotation> anns) {
        var set = new TreeSet<String>();
        for (var ann : anns) {
            if (ann.name().isPresent()) {
                set.add(ann.name().orElseThrow());
            } else {
                String raw = extractAnnotationToken(ann.use().spelling());
                if (!raw.isEmpty()) set.add(raw);
            }
        }
        return set;
    }

    private static boolean matchesAnnotation(SpringSourceEvidence.Annotation ann, String fqn, String simple) {
        if (ann.name().filter(fqn::equals).isPresent()) return true;
        String token = extractAnnotationToken(ann.use().spelling());
        return fqn.equals(token) || simple.equals(token);
    }

    private static Optional<AdviceKind> resolveAdviceKind(SpringSourceEvidence.Annotation ann) {
        if (ann.name().isPresent()) {
            Optional<AdviceKind> byFqn = AdviceKind.fromAnnotationFqn(ann.name().orElseThrow());
            if (byFqn.isPresent()) return byFqn;
        }
        String token = extractAnnotationToken(ann.use().spelling());
        return switch (token) {
            case "Around", ANN_AROUND -> Optional.of(AdviceKind.AROUND);
            case "Before", ANN_BEFORE -> Optional.of(AdviceKind.BEFORE);
            case "AfterReturning", ANN_AFTER_RETURNING -> Optional.of(AdviceKind.AFTER_RETURNING);
            case "AfterThrowing", ANN_AFTER_THROWING -> Optional.of(AdviceKind.AFTER_THROWING);
            case "After", ANN_AFTER -> Optional.of(AdviceKind.AFTER);
            default -> Optional.empty();
        };
    }

    private static AdviceKind parseAdviceKindToken(String token) {
        if (token == null) return null;
        return switch (token.toUpperCase(Locale.ROOT)) {
            case "AROUND" -> AdviceKind.AROUND;
            case "BEFORE" -> AdviceKind.BEFORE;
            case "AFTER_RETURNING" -> AdviceKind.AFTER_RETURNING;
            case "AFTER_THROWING" -> AdviceKind.AFTER_THROWING;
            case "AFTER" -> AdviceKind.AFTER;
            default -> null;
        };
    }

    private static boolean hasOrderAnnotation(List<SpringSourceEvidence.Annotation> anns) {
        return anns.stream().anyMatch(a -> matchesAnnotation(a, ANN_ORDER, "Order"));
    }

    private static int extractOrder(List<SpringSourceEvidence.Annotation> anns) {
        for (var ann : anns) {
            if (!matchesAnnotation(ann, ANN_ORDER, "Order")) continue;
            String sp = ann.use().spelling();
            int open = sp.indexOf('(');
            int close = sp.lastIndexOf(')');
            if (open < 0 || close <= open) continue;
            String body = sp.substring(open + 1, close).trim();
            int eq = body.indexOf('=');
            if (eq >= 0) body = body.substring(eq + 1).trim();
            String compact = body.replaceAll("\\s+", "");
            if (compact.endsWith("HIGHEST_PRECEDENCE") || compact.endsWith("MIN_VALUE")) return Integer.MIN_VALUE;
            if (compact.endsWith("LOWEST_PRECEDENCE") || compact.endsWith("MAX_VALUE")) return Integer.MAX_VALUE;
            try {
                return Integer.parseInt(compact.replace("_", ""));
            } catch (NumberFormatException ignored) {
            }
        }
        return Integer.MAX_VALUE;
    }

    private static String extractPointcutAttr(String spelling, List<String> attrPreference) {
        if (spelling == null) return "";
        try {
            var attrs = LiteralConditionAnnotation.parse(spelling);
            for (String key : attrPreference) {
                var val = LiteralConditionAnnotation.strings(attrs, key, List.of());
                if (!val.isEmpty() && !val.getFirst().isBlank()) {
                    return val.getFirst().trim();
                }
            }
        } catch (IllegalArgumentException ignored) {
            // Fallback for string-literal attribute extraction when non-string attributes (e.g. returning="res") coexist
            for (String key : attrPreference) {
                Pattern p = Pattern.compile("\\b" + Pattern.quote(key) + "\\s*=\\s*\"([^\"]+)\"");
                Matcher m = p.matcher(spelling);
                if (m.find()) return m.group(1).trim();
            }
            int q1 = spelling.indexOf('"');
            int q2 = q1 >= 0 ? spelling.indexOf('"', q1 + 1) : -1;
            if (q1 >= 0 && q2 > q1) {
                return spelling.substring(q1 + 1, q2).trim();
            }
        }
        return "";
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

    public static CapabilityGapRecord createDynamicPointcutGap(
            SnapshotIdentity snapshot,
            AdviceDeclaration advice,
            String detail) {
        ContentDigest obsDigest = IngestionEvidence.digest(Map.of(
                "reason", GAP_AOP_POINTCUT_DYNAMIC,
                "aspect", advice.aspectClass(),
                "method", advice.adviceMethod(),
                "kind", advice.adviceKind().name(),
                "pointcut", advice.pointcutExpression(),
                "detail", safe(detail)));
        var obs = ProviderObservationReference.create(
                PROVIDER, EVIDENCE_FAMILY_AOP_ADVICE, obsDigest, obsDigest);
        var subject = EvidenceSubject.observation(obs.identity());
        var req = new EvidenceRequirement(
                EvidenceRequirement.Kind.RUNTIME_OBSERVATION,
                "spring.aop.resolve-dynamic-pointcut",
                List.of(subject),
                EvidenceRequirement.AuthorizationClass.RUNTIME_ACCESS,
                List.of("Supply static execution/@annotation/within pointcuts or runtime AOP weaving evidence."));
        return CapabilityGapRecord.create(
                GAP_CATALOG,
                EvidenceContext.forSnapshot(snapshot),
                PROVIDER,
                "spring.aop-advice",
                GAP_AOP_POINTCUT_DYNAMIC,
                subject,
                List.of(),
                List.of(obs),
                List.of(req),
                List.of(),
                List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE, "spring.aop-advice")),
                List.of(),
                List.of(),
                List.of("Dynamic or complex AOP pointcut on " + advice.aspectClass() + "#" + advice.adviceMethod()
                        + ": " + safe(detail)));
    }

    private static String stripGenerics(String type) {
        if (type == null) return "";
        int lt = type.indexOf('<');
        return (lt >= 0 ? type.substring(0, lt) : type).trim();
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
