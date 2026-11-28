package com.evolution.analysis.javaparser;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded String-only constant folding, generic {@link EnumConstantMap} extraction,
 * and bounded {@code k=2} callsite-sensitive wrapper folding ({@code hopDepth <= 4},
 * cap 16 candidates/slot). Never executes target initializers or reads runtime configuration;
 * unknown, mutable, and dynamic values stay explicitly unknown.
 */
final class HttpClientConstants {
    static final int MAX_HOP_DEPTH = 4;
    static final int CALLSITE_SENSITIVITY_K = 2;
    static final int MAX_CANDIDATES_PER_SLOT = 16;

    private static final Pattern PRINTF_TOKEN = Pattern.compile(
            "%(?:\\d+\\$)?[-+# 0,(]*\\d*(?:\\.\\d+)?([%sdifbBhHcCoxXeEgGaAtTn])");

    private static final Set<String> REST_SINK_METHODS = Set.of(
            "getForObject", "getForEntity", "postForObject", "postForEntity", "postForLocation",
            "put", "patchForObject", "delete", "headForHeaders", "optionsForAllow", "exchange");

    private static final Set<String> HTTP_VERBS = Set.of(
            "get", "post", "put", "patch", "delete", "head", "options");

    enum SinkKind {
        HTTP_URI,
        HTTP_HOST,
        AMQP_EXCHANGE,
        AMQP_KEY,
        KAFKA_TOPIC
    }

    /**
     * Reverse topological summary of a wrapper method parameter that flows into a standard
     * HTTP or messaging sink within {@code hopDepth <= 4}.
     */
    record SinkParamSummary(
            String methodId,
            SinkKind sinkKind,
            int paramIndex,
            String enumFieldAccessor,
            int hopDepth) implements Comparable<SinkParamSummary> {
        SinkParamSummary {
            methodId = methodId == null ? "" : methodId;
            Objects.requireNonNull(sinkKind, "sinkKind");
            enumFieldAccessor = enumFieldAccessor == null ? "" : enumFieldAccessor;
        }

        @Override
        public int compareTo(SinkParamSummary other) {
            int cmp = this.methodId.compareTo(other.methodId);
            if (cmp != 0) return cmp;
            cmp = this.sinkKind.compareTo(other.sinkKind);
            if (cmp != 0) return cmp;
            cmp = Integer.compare(this.paramIndex, other.paramIndex);
            if (cmp != 0) return cmp;
            cmp = this.enumFieldAccessor.compareTo(other.enumFieldAccessor);
            if (cmp != 0) return cmp;
            return Integer.compare(this.hopDepth, other.hopDepth);
        }
    }

    /**
     * Strict {@code k=2} callsite-sensitive key preventing Cartesian mixing across distinct
     * callsites invoking the same wrapper method.
     */
    record CallSiteKey(String outerCallSiteId, String callSiteId, String targetMethodId)
            implements Comparable<CallSiteKey> {
        CallSiteKey {
            outerCallSiteId = outerCallSiteId == null ? "" : outerCallSiteId;
            callSiteId = callSiteId == null ? "" : callSiteId;
            targetMethodId = targetMethodId == null ? "" : targetMethodId;
        }

        @Override
        public int compareTo(CallSiteKey other) {
            int cmp = this.outerCallSiteId.compareTo(other.outerCallSiteId);
            if (cmp != 0) return cmp;
            cmp = this.callSiteId.compareTo(other.callSiteId);
            if (cmp != 0) return cmp;
            return this.targetMethodId.compareTo(other.targetMethodId);
        }
    }

    /**
     * One callsite-sensitive folded sink invocation anchored at the originating callsite.
     */
    record FoldedSinkCall(
            CallSiteKey callSiteKey,
            String sourceDocPath,
            int startLine,
            int startColumn,
            int endLine,
            int endColumn,
            SinkKind primaryKind,
            String uri,
            String host,
            String httpMethod,
            String exchange,
            String routingKey,
            String kafkaTopic,
            boolean dynamicUnresolved,
            String unresolvedReasonCode,
            String rawUnresolvedExpr) implements Comparable<FoldedSinkCall> {
        @Override
        public int compareTo(FoldedSinkCall other) {
            int cmp = this.callSiteKey.compareTo(other.callSiteKey);
            if (cmp != 0) return cmp;
            cmp = this.sourceDocPath.compareTo(other.sourceDocPath);
            if (cmp != 0) return cmp;
            cmp = Integer.compare(this.startLine, other.startLine);
            if (cmp != 0) return cmp;
            cmp = Integer.compare(this.startColumn, other.startColumn);
            if (cmp != 0) return cmp;
            cmp = this.primaryKind.compareTo(other.primaryKind);
            if (cmp != 0) return cmp;
            cmp = this.uri.compareTo(other.uri);
            if (cmp != 0) return cmp;
            cmp = this.exchange.compareTo(other.exchange);
            if (cmp != 0) return cmp;
            cmp = this.routingKey.compareTo(other.routingKey);
            if (cmp != 0) return cmp;
            return this.kafkaTopic.compareTo(other.kafkaTopic);
        }
    }

    /**
     * Generic project-wide enum constant constructor argument and getter/field map,
     * stored deterministically in a {@link TreeMap}.
     * Example: {@code enum Foo { A("svc-a") }} yields {@code Foo.A.field0 = "svc-a"} as well as
     * constructor-assigned field names and zero-arg getter names.
     */
    static final class EnumConstantMap {
        static final EnumConstantMap EMPTY = new EnumConstantMap(Collections.unmodifiableNavigableMap(new TreeMap<>()));

        private final NavigableMap<String, String> entries;

        private EnumConstantMap(NavigableMap<String, String> entries) {
            this.entries = entries;
        }

        static Builder builder() {
            return new Builder();
        }

        static EnumConstantMap from(CompilationUnit... units) {
            var b = new Builder();
            if (units != null) {
                for (CompilationUnit cu : units) {
                    if (cu != null) b.addUnit(cu);
                }
            }
            return b.build();
        }

        static EnumConstantMap from(Collection<CompilationUnit> units) {
            var b = new Builder();
            if (units != null) {
                for (CompilationUnit cu : units) {
                    if (cu != null) b.addUnit(cu);
                }
            }
            return b.build();
        }

        NavigableMap<String, String> entries() {
            return entries;
        }

        boolean isEmpty() {
            return entries.isEmpty();
        }

        Optional<String> lookupKey(String key) {
            if (key == null || key.isEmpty() || entries.isEmpty()) return Optional.empty();
            return Optional.ofNullable(entries.get(key));
        }

        Optional<String> lookup(String enumTypeName, String constantName, String accessor) {
            if (entries.isEmpty() || constantName == null || constantName.isEmpty()) return Optional.empty();
            String cleanAcc = accessor == null ? "" : accessor.trim();
            if (cleanAcc.endsWith("()")) cleanAcc = cleanAcc.substring(0, cleanAcc.length() - 2);
            List<String> accCandidates = cleanAcc.isEmpty()
                    ? List.of("field0", "name")
                    : List.of(cleanAcc + "()", cleanAcc);
            if (enumTypeName != null && !enumTypeName.isEmpty()) {
                String simple = simpleName(enumTypeName);
                for (String typeKey : enumTypeName.equals(simple) ? List.of(simple) : List.of(enumTypeName, simple)) {
                    for (String acc : accCandidates) {
                        String hit = entries.get(typeKey + "." + constantName + "." + acc);
                        if (hit != null) return Optional.of(hit);
                    }
                }
            } else {
                // Static-import constant fallback: resolve only if unique across known enums.
                String found = null;
                for (String acc : accCandidates) {
                    String suffix = "." + constantName + "." + acc;
                    for (var entry : entries.entrySet()) {
                        if (entry.getKey().endsWith(suffix)) {
                            if (found != null && !found.equals(entry.getValue())) {
                                return Optional.empty();
                            }
                            found = entry.getValue();
                        }
                    }
                    if (found != null) return Optional.of(found);
                }
            }
            return Optional.empty();
        }

        static final class Builder {
            private final TreeMap<String, String> map = new TreeMap<>();

            void addUnit(CompilationUnit cu) {
                if (cu == null) return;
                String pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
                for (EnumDeclaration ed : cu.findAll(EnumDeclaration.class)) {
                    indexEnum(ed, pkg);
                }
            }

            private void indexEnum(EnumDeclaration ed, String pkg) {
                String simple = ed.getNameAsString();
                String fqn = enclosingQualifiedName(ed, pkg);

                // Collect static/final string constants declared inside this enum
                var localConsts = new LinkedHashMap<String, String>();
                for (FieldDeclaration fd : ed.getFields()) {
                    if (!isStringType(fd.getCommonType().asString())) continue;
                    if (!fd.isStatic() && !fd.isFinal()) continue;
                    for (VariableDeclarator vd : fd.getVariables()) {
                        vd.getInitializer().ifPresent(init -> {
                            String folded = HttpClientConstants.syntaxUri(init, localConsts);
                            if (!folded.isEmpty()) {
                                localConsts.put(vd.getNameAsString(), folded);
                                localConsts.put(simple + "." + vd.getNameAsString(), folded);
                            }
                        });
                    }
                }

                // Map (ctorParamCount -> (paramIndex -> fieldName))
                var ctorParamFields = new HashMap<Integer, Map<Integer, String>>();
                for (ConstructorDeclaration ctor : ed.getConstructors()) {
                    var paramIndexByName = new HashMap<String, Integer>();
                    for (int i = 0; i < ctor.getParameters().size(); i++) {
                        paramIndexByName.put(ctor.getParameter(i).getNameAsString(), i);
                    }
                    var fieldMap = new HashMap<Integer, String>();
                    for (AssignExpr assign : ctor.getBody().findAll(AssignExpr.class)) {
                        if (assign.getOperator() != AssignExpr.Operator.ASSIGN) continue;
                        if (!(assign.getValue() instanceof NameExpr rhs)) continue;
                        Integer pIdx = paramIndexByName.get(rhs.getNameAsString());
                        if (pIdx == null) continue;
                        String fieldName = assignedFieldName(assign.getTarget());
                        if (!fieldName.isEmpty()) {
                            fieldMap.put(pIdx, fieldName);
                        }
                    }
                    ctorParamFields.put(ctor.getParameters().size(), fieldMap);
                }

                // Map fieldName -> getter method names (0-arg methods returning that field)
                var gettersByField = new HashMap<String, List<String>>();
                for (MethodDeclaration m : ed.getMethods()) {
                    if (!m.getParameters().isEmpty() || m.getBody().isEmpty()) continue;
                    var returns = m.getBody().orElseThrow().findAll(ReturnStmt.class);
                    if (returns.size() != 1 || returns.getFirst().getExpression().isEmpty()) continue;
                    String returnedField = returnedFieldName(returns.getFirst().getExpression().orElseThrow());
                    if (!returnedField.isEmpty()) {
                        gettersByField.computeIfAbsent(returnedField, k -> new ArrayList<>()).add(m.getNameAsString());
                    }
                }

                List<String> typeNames = fqn.equals(simple) ? List.of(simple) : List.of(simple, fqn);
                for (EnumConstantDeclaration constant : ed.getEntries()) {
                    String constName = constant.getNameAsString();
                    for (String t : typeNames) {
                        map.put(t + "." + constName + ".name()", constName);
                        map.put(t + "." + constName + ".name", constName);
                    }
                    var args = constant.getArguments();
                    var fieldByIdx = ctorParamFields.getOrDefault(args.size(), Map.of());
                    for (int i = 0; i < args.size(); i++) {
                        String val = foldEnumArg(args.get(i), localConsts);
                        if (val.isEmpty()) continue;
                        String fieldName = fieldByIdx.getOrDefault(i, "");
                        List<String> getters = fieldName.isEmpty() ? List.of() : gettersByField.getOrDefault(fieldName, List.of());
                        for (String t : typeNames) {
                            String prefix = t + "." + constName + ".";
                            map.put(prefix + "field" + i, val);
                            if (!fieldName.isEmpty()) {
                                map.put(prefix + fieldName, val);
                            }
                            for (String getter : getters) {
                                map.put(prefix + getter + "()", val);
                                map.put(prefix + getter, val);
                            }
                        }
                    }
                }
            }

            EnumConstantMap build() {
                if (map.isEmpty()) return EMPTY;
                return new EnumConstantMap(Collections.unmodifiableNavigableMap(new TreeMap<>(map)));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Lightweight AST-independent expression IR for k-callsite wrapper folding
    // -------------------------------------------------------------------------

    sealed interface FoldableExpr permits LitExpr, ParamExpr, EnumConstExpr, EnumAccessExpr,
            ConcatExpr, FormatExpr, DynamicExpr {}

    record LitExpr(String value) implements FoldableExpr {}
    record ParamExpr(int paramIndex, String accessor, String rawText) implements FoldableExpr {}
    record EnumConstExpr(String enumType, String constantName, String rawText) implements FoldableExpr {}
    record EnumAccessExpr(String enumType, String constantName, String accessor, String rawText) implements FoldableExpr {}
    record ConcatExpr(List<FoldableExpr> parts, String rawText) implements FoldableExpr {}
    record FormatExpr(FoldableExpr template, List<FoldableExpr> args, String rawText) implements FoldableExpr {}
    record DynamicExpr(String rawText) implements FoldableExpr {}

    private enum DirectSinkFamily {
        HTTP_REST,
        HTTP_FLUENT,
        HTTP_WEB_TARGET,
        HTTP_DISCOVERY,
        AMQP_SEND,
        KAFKA_SEND
    }

    private record DirectSinkTemplate(
            DirectSinkFamily family,
            String httpMethod,
            FoldableExpr uriOrHostExpr,
            FoldableExpr secondaryExpr,
            boolean amqpHasExplicitExchange) {}

    private record OutgoingCallTemplate(
            String callSiteId,
            String sourceDocPath,
            String receiverTypeHint,
            String calleeName,
            int argCount,
            List<FoldableExpr> args,
            int startLine,
            int startColumn,
            int endLine,
            int endColumn) {}

    private record MethodSummaryNode(
            String methodId,
            String ownerFqn,
            String ownerSimple,
            String methodName,
            int paramCount,
            List<DirectSinkTemplate> directSinks,
            List<OutgoingCallTemplate> outgoingCalls) {}

    /**
     * Deterministic reverse-topological {@link SinkParamSummary} index and {@code k=2}
     * callsite-sensitive wrapper folding engine. Retains zero JavaParser AST references so it
     * works cleanly across both full modules ({@code Extraction.java}) and passive single-file
     * scans ({@code JavaServiceSyntaxExtractor.java}).
     */
    static final class WrapperFoldingIndex {
        private final EnumConstantMap enumMap;
        private final TreeMap<String, MethodSummaryNode> methodsById;
        private final TreeMap<String, List<String>> methodsBySimpleKey;
        private final TreeMap<String, List<String>> methodsByNameArity;
        private final TreeMap<String, TreeSet<SinkParamSummary>> summariesByMethod;

        private WrapperFoldingIndex(
                EnumConstantMap enumMap,
                TreeMap<String, MethodSummaryNode> methodsById,
                TreeMap<String, List<String>> methodsBySimpleKey,
                TreeMap<String, List<String>> methodsByNameArity,
                TreeMap<String, TreeSet<SinkParamSummary>> summariesByMethod) {
            this.enumMap = enumMap;
            this.methodsById = methodsById;
            this.methodsBySimpleKey = methodsBySimpleKey;
            this.methodsByNameArity = methodsByNameArity;
            this.summariesByMethod = summariesByMethod;
        }

        static Builder builder() {
            return new Builder();
        }

        static WrapperFoldingIndex fromUnits(Map<String, CompilationUnit> unitsByPath) {
            var b = new Builder();
            var sorted = new TreeMap<>(unitsByPath);
            for (var entry : sorted.entrySet()) {
                b.addUnit(entry.getKey(), entry.getValue());
            }
            return b.build();
        }

        EnumConstantMap enumConstants() {
            return enumMap;
        }

        NavigableMap<String, List<SinkParamSummary>> summaries() {
            var out = new TreeMap<String, List<SinkParamSummary>>();
            summariesByMethod.forEach((k, v) -> out.put(k, List.copyOf(v)));
            return Collections.unmodifiableNavigableMap(out);
        }

        /**
         * Folds all wrapper callsites in the indexed units (or restricted to {@code onlyDocPath}
         * when non-null) using strict {@code k=2} callsite sensitivity and a hard cap of
         * {@link #MAX_CANDIDATES_PER_SLOT} candidates per {@code (callSiteKey, paramIndex)} slot.
         */
        List<FoldedSinkCall> foldCallSites(String onlyDocPath) {
            if (summariesByMethod.isEmpty()) return List.of();
            String normOnlyPath = onlyDocPath == null ? null : onlyDocPath.replace('\\', '/');
            var results = new TreeSet<FoldedSinkCall>();
            var slotCandidates = new TreeMap<CallSiteKey, Map<Integer, TreeSet<String>>>();

            for (MethodSummaryNode caller : methodsById.values()) {
                for (OutgoingCallTemplate call : caller.outgoingCalls()) {
                    if (normOnlyPath != null && !normOnlyPath.equals(call.sourceDocPath())) {
                        continue;
                    }
                    String targetId = resolveCallee(caller, call);
                    if (targetId == null || !summariesByMethod.containsKey(targetId)) {
                        continue;
                    }
                    // Skip calls inside a wrapper that merely forward its own parameter without binding concrete values
                    if (allArgsReferenceCallerParams(call.args())) {
                        continue;
                    }
                    var rootKey = new CallSiteKey("", call.callSiteId(), targetId);
                    Map<Integer, EvalArg> initialEnv = buildCallSiteEnv(call.args(), Map.of(), rootKey, slotCandidates);
                    evaluateWrapperChain(
                            call,
                            rootKey,
                            targetId,
                            initialEnv,
                            1,
                            new HashSet<>(Set.of(targetId)),
                            slotCandidates,
                            results);
                }
            }
            return List.copyOf(results);
        }

        private void evaluateWrapperChain(
                OutgoingCallTemplate originCall,
                CallSiteKey currentKey,
                String targetMethodId,
                Map<Integer, EvalArg> env,
                int hopDepth,
                Set<String> activeStack,
                Map<CallSiteKey, Map<Integer, TreeSet<String>>> slotCandidates,
                Set<FoldedSinkCall> out) {
            if (hopDepth > MAX_HOP_DEPTH) return;
            MethodSummaryNode target = methodsById.get(targetMethodId);
            if (target == null) return;

            // 1. Evaluate any direct sinks inside target using ONLY this callsite's env
            for (DirectSinkTemplate sink : target.directSinks()) {
                evaluateDirectSink(originCall, currentKey, sink, env, out);
            }

            // 2. Follow nested wrapper calls (hopDepth < MAX_HOP_DEPTH), advancing k=2 callsite context
            if (hopDepth < MAX_HOP_DEPTH) {
                for (OutgoingCallTemplate nested : target.outgoingCalls()) {
                    String nextTargetId = resolveCallee(target, nested);
                    if (nextTargetId == null || !summariesByMethod.containsKey(nextTargetId)) continue;
                    if (!activeStack.add(nextTargetId)) continue;
                    try {
                        // k=2 callsite sensitivity: (currentKey.callSiteId, nested.callSiteId, nextTargetId)
                        var nextKey = new CallSiteKey(currentKey.callSiteId(), nested.callSiteId(), nextTargetId);
                        Map<Integer, EvalArg> nextEnv = buildCallSiteEnv(nested.args(), env, nextKey, slotCandidates);
                        evaluateWrapperChain(originCall, nextKey, nextTargetId, nextEnv,
                                hopDepth + 1, activeStack, slotCandidates, out);
                    } finally {
                        activeStack.remove(nextTargetId);
                    }
                }
            }
        }

        private record EvalArg(String literal, String enumType, String enumConst, boolean dynamic, String rawText,
                boolean slotOverflow) {
            static EvalArg lit(String v) { return new EvalArg(v, "", "", false, v, false); }
            static EvalArg enumRef(String type, String constant, String raw) {
                return new EvalArg("", type, constant, false, raw, false);
            }
            static EvalArg unresolved(String raw, boolean overflow) {
                return new EvalArg("", "", "", true, raw == null ? "" : raw, overflow);
            }
        }

        private Map<Integer, EvalArg> buildCallSiteEnv(
                List<FoldableExpr> argExprs,
                Map<Integer, EvalArg> callerEnv,
                CallSiteKey callSiteKey,
                Map<CallSiteKey, Map<Integer, TreeSet<String>>> slotCandidates) {
            var nextEnv = new TreeMap<Integer, EvalArg>();
            var slots = slotCandidates.computeIfAbsent(callSiteKey, k -> new TreeMap<>());
            for (int i = 0; i < argExprs.size(); i++) {
                EvalArg evaluated = evalToArg(argExprs.get(i), callerEnv);
                var bucket = slots.computeIfAbsent(i, k -> new TreeSet<>());
                if (!evaluated.dynamic()) {
                    String token = !evaluated.enumConst().isEmpty()
                            ? evaluated.enumType() + "." + evaluated.enumConst()
                            : evaluated.literal();
                    if (bucket.size() >= MAX_CANDIDATES_PER_SLOT && !bucket.contains(token)) {
                        nextEnv.put(i, EvalArg.unresolved(evaluated.rawText(), true));
                        continue;
                    }
                    bucket.add(token);
                }
                nextEnv.put(i, evaluated);
            }
            return nextEnv;
        }

        private EvalArg evalToArg(FoldableExpr expr, Map<Integer, EvalArg> env) {
            if (expr instanceof LitExpr lit) {
                return EvalArg.lit(lit.value());
            }
            if (expr instanceof EnumConstExpr ec) {
                return EvalArg.enumRef(ec.enumType(), ec.constantName(), ec.rawText());
            }
            if (expr instanceof EnumAccessExpr ea) {
                Optional<String> val = enumMap.lookup(ea.enumType(), ea.constantName(), ea.accessor());
                return val.map(EvalArg::lit).orElseGet(() -> EvalArg.unresolved(ea.rawText(), false));
            }
            if (expr instanceof ParamExpr pe) {
                EvalArg bound = env.get(pe.paramIndex());
                if (bound == null) return EvalArg.unresolved(pe.rawText(), false);
                if (bound.dynamic()) return bound;
                if (pe.accessor().isEmpty()) return bound;
                if (!bound.enumConst().isEmpty()) {
                    Optional<String> val = enumMap.lookup(bound.enumType(), bound.enumConst(), pe.accessor());
                    return val.map(EvalArg::lit).orElseGet(() -> EvalArg.unresolved(pe.rawText(), false));
                }
                return EvalArg.unresolved(pe.rawText(), false);
            }
            Optional<String> folded = evalString(expr, env, false);
            return folded.map(EvalArg::lit).orElseGet(() -> EvalArg.unresolved(rawOf(expr), false));
        }

        private Optional<String> evalString(FoldableExpr expr, Map<Integer, EvalArg> env, boolean allowUriParamPlaceholder) {
            if (expr == null) return Optional.empty();
            if (expr instanceof LitExpr lit) {
                return bounded(lit.value());
            }
            if (expr instanceof EnumAccessExpr ea) {
                return enumMap.lookup(ea.enumType(), ea.constantName(), ea.accessor()).flatMap(HttpClientConstants::bounded);
            }
            if (expr instanceof EnumConstExpr ec) {
                return enumMap.lookup(ec.enumType(), ec.constantName(), "field0").flatMap(HttpClientConstants::bounded);
            }
            if (expr instanceof ParamExpr pe) {
                EvalArg bound = env.get(pe.paramIndex());
                if (bound == null || bound.dynamic()) return Optional.empty();
                if (!bound.enumConst().isEmpty()) {
                    return enumMap.lookup(bound.enumType(), bound.enumConst(), pe.accessor()).flatMap(HttpClientConstants::bounded);
                }
                return pe.accessor().isEmpty() ? bounded(bound.literal()) : Optional.empty();
            }
            if (expr instanceof ConcatExpr concat) {
                var sb = new StringBuilder();
                for (FoldableExpr part : concat.parts()) {
                    Optional<String> pVal = evalString(part, env, allowUriParamPlaceholder);
                    if (pVal.isPresent()) {
                        sb.append(pVal.orElseThrow());
                    } else if (allowUriParamPlaceholder && isUriOrPathPrefix(sb.toString()) && hasHostAfterScheme(sb.toString())) {
                        sb.append("{param}");
                    } else {
                        return Optional.empty();
                    }
                }
                return bounded(sb.toString());
            }
            if (expr instanceof FormatExpr fmt) {
                Optional<String> tmplOpt = evalString(fmt.template(), env, false);
                if (tmplOpt.isEmpty()) return Optional.empty();
                String template = tmplOpt.orElseThrow();
                Matcher m = PRINTF_TOKEN.matcher(template);
                var sb = new StringBuilder(template.length());
                int cursor = 0;
                while (m.find()) {
                    String conv = m.group(1);
                    if ("%".equals(conv)) {
                        m.appendReplacement(sb, Matcher.quoteReplacement("%"));
                        continue;
                    }
                    String rep = null;
                    if (cursor < fmt.args().size()) {
                        Optional<String> argVal = evalString(fmt.args().get(cursor++), env, false);
                        if (argVal.isPresent()) {
                            rep = argVal.orElseThrow();
                        }
                    }
                    if (rep == null) {
                        if (allowUriParamPlaceholder && isUriOrPathPrefix(template)) {
                            rep = "{param}";
                        } else {
                            return Optional.empty();
                        }
                    }
                    m.appendReplacement(sb, Matcher.quoteReplacement(rep));
                }
                m.appendTail(sb);
                return bounded(sb.toString());
            }
            return Optional.empty();
        }

        private void evaluateDirectSink(
                OutgoingCallTemplate originCall,
                CallSiteKey callSiteKey,
                DirectSinkTemplate sink,
                Map<Integer, EvalArg> env,
                Set<FoldedSinkCall> out) {
            switch (sink.family()) {
                case HTTP_REST, HTTP_FLUENT, HTTP_WEB_TARGET -> {
                    Optional<String> uriOpt = evalString(sink.uriOrHostExpr(), env, true);
                    if (uriOpt.isPresent()) {
                        String uri = uriOpt.orElseThrow().trim();
                        if (!uri.isEmpty()) {
                            String host = com.evolution.analysis.polyglot.HttpUriPattern.host(uri).orElse("");
                            out.add(new FoldedSinkCall(callSiteKey, originCall.sourceDocPath(),
                                    originCall.startLine(), originCall.startColumn(),
                                    originCall.endLine(), originCall.endColumn(),
                                    SinkKind.HTTP_URI, uri, host, sink.httpMethod(),
                                    "", "", "", false, "", ""));
                        }
                    } else if (referencesAnyParam(sink.uriOrHostExpr())) {
                        out.add(new FoldedSinkCall(callSiteKey, originCall.sourceDocPath(),
                                originCall.startLine(), originCall.startColumn(),
                                originCall.endLine(), originCall.endColumn(),
                                SinkKind.HTTP_URI, "", "", sink.httpMethod(),
                                "", "", "", true, "DYNAMIC_HTTP_URI_UNRESOLVED", rawOf(sink.uriOrHostExpr())));
                    }
                }
                case HTTP_DISCOVERY -> {
                    Optional<String> hostOpt = evalString(sink.uriOrHostExpr(), env, false);
                    if (hostOpt.isPresent()) {
                        String svc = hostOpt.orElseThrow().trim();
                        if (!svc.isEmpty()) {
                            out.add(new FoldedSinkCall(callSiteKey, originCall.sourceDocPath(),
                                    originCall.startLine(), originCall.startColumn(),
                                    originCall.endLine(), originCall.endColumn(),
                                    SinkKind.HTTP_HOST, "lb://" + svc + "/", svc, "",
                                    "", "", "", false, "", ""));
                        }
                    } else if (referencesAnyParam(sink.uriOrHostExpr())) {
                        out.add(new FoldedSinkCall(callSiteKey, originCall.sourceDocPath(),
                                originCall.startLine(), originCall.startColumn(),
                                originCall.endLine(), originCall.endColumn(),
                                SinkKind.HTTP_HOST, "", "", "",
                                "", "", "", true, "DYNAMIC_HTTP_URI_UNRESOLVED", rawOf(sink.uriOrHostExpr())));
                    }
                }
                case AMQP_SEND -> {
                    if (sink.amqpHasExplicitExchange()) {
                        Optional<String> exchOpt = evalString(sink.uriOrHostExpr(), env, false);
                        Optional<String> keyOpt = evalString(sink.secondaryExpr(), env, false);
                        if (exchOpt.isPresent() && keyOpt.isPresent()) {
                            out.add(new FoldedSinkCall(callSiteKey, originCall.sourceDocPath(),
                                    originCall.startLine(), originCall.startColumn(),
                                    originCall.endLine(), originCall.endColumn(),
                                    SinkKind.AMQP_KEY, "", "", "",
                                    exchOpt.orElseThrow().trim(), keyOpt.orElseThrow().trim(), "",
                                    false, "", ""));
                        } else {
                            String exch = exchOpt.map(String::trim).orElse("");
                            String rawUnresolved = keyOpt.isEmpty() ? rawOf(sink.secondaryExpr()) : rawOf(sink.uriOrHostExpr());
                            out.add(new FoldedSinkCall(callSiteKey, originCall.sourceDocPath(),
                                    originCall.startLine(), originCall.startColumn(),
                                    originCall.endLine(), originCall.endColumn(),
                                    SinkKind.AMQP_KEY, "", "", "",
                                    exch, "", "",
                                    true, "DYNAMIC_ROUTING_KEY_UNRESOLVED", rawUnresolved));
                        }
                    } else {
                        Optional<String> keyOpt = evalString(sink.uriOrHostExpr(), env, false);
                        if (keyOpt.isPresent()) {
                            out.add(new FoldedSinkCall(callSiteKey, originCall.sourceDocPath(),
                                    originCall.startLine(), originCall.startColumn(),
                                    originCall.endLine(), originCall.endColumn(),
                                    SinkKind.AMQP_KEY, "", "", "",
                                    "", keyOpt.orElseThrow().trim(), "",
                                    false, "", ""));
                        } else if (referencesAnyParam(sink.uriOrHostExpr())) {
                            out.add(new FoldedSinkCall(callSiteKey, originCall.sourceDocPath(),
                                    originCall.startLine(), originCall.startColumn(),
                                    originCall.endLine(), originCall.endColumn(),
                                    SinkKind.AMQP_KEY, "", "", "",
                                    "", "", "",
                                    true, "DYNAMIC_ROUTING_KEY_UNRESOLVED", rawOf(sink.uriOrHostExpr())));
                        }
                    }
                }
                case KAFKA_SEND -> {
                    Optional<String> topicOpt = evalString(sink.uriOrHostExpr(), env, false);
                    if (topicOpt.isPresent()) {
                        out.add(new FoldedSinkCall(callSiteKey, originCall.sourceDocPath(),
                                originCall.startLine(), originCall.startColumn(),
                                originCall.endLine(), originCall.endColumn(),
                                SinkKind.KAFKA_TOPIC, "", "", "",
                                "", "", topicOpt.orElseThrow().trim(),
                                false, "", ""));
                    } else if (referencesAnyParam(sink.uriOrHostExpr())) {
                        out.add(new FoldedSinkCall(callSiteKey, originCall.sourceDocPath(),
                                originCall.startLine(), originCall.startColumn(),
                                originCall.endLine(), originCall.endColumn(),
                                SinkKind.KAFKA_TOPIC, "", "", "",
                                "", "", "",
                                true, "DYNAMIC_ROUTING_KEY_UNRESOLVED", rawOf(sink.uriOrHostExpr())));
                    }
                }
            }
        }

        private String resolveCallee(MethodSummaryNode caller, OutgoingCallTemplate call) {
            String hint = call.receiverTypeHint();
            if (hint.isEmpty() || "this".equals(hint)) {
                String sameOwner = caller.ownerFqn() + "#" + call.calleeName() + "(" + call.argCount() + ")";
                if (methodsById.containsKey(sameOwner)) return sameOwner;
            }
            if (!hint.isEmpty()) {
                String exactFqn = hint + "#" + call.calleeName() + "(" + call.argCount() + ")";
                if (methodsById.containsKey(exactFqn)) return exactFqn;
                String simpleKey = simpleName(hint) + "#" + call.calleeName() + "(" + call.argCount() + ")";
                List<String> bySimple = methodsBySimpleKey.getOrDefault(simpleKey, List.of());
                if (bySimple.size() == 1) return bySimple.getFirst();
            }
            List<String> byName = methodsByNameArity.getOrDefault(call.calleeName() + "(" + call.argCount() + ")", List.of());
            if (byName.size() == 1) return byName.getFirst();
            return null;
        }

        private static boolean allArgsReferenceCallerParams(List<FoldableExpr> args) {
            if (args.isEmpty()) return false;
            boolean sawParam = false;
            for (FoldableExpr arg : args) {
                if (referencesAnyParam(arg)) {
                    sawParam = true;
                } else if (arg instanceof LitExpr || arg instanceof EnumConstExpr || arg instanceof EnumAccessExpr) {
                    return false;
                }
            }
            return sawParam;
        }

        private static boolean referencesAnyParam(FoldableExpr expr) {
            if (expr instanceof ParamExpr) return true;
            if (expr instanceof ConcatExpr c) {
                for (FoldableExpr p : c.parts()) if (referencesAnyParam(p)) return true;
            }
            if (expr instanceof FormatExpr f) {
                if (referencesAnyParam(f.template())) return true;
                for (FoldableExpr a : f.args()) if (referencesAnyParam(a)) return true;
            }
            return false;
        }

        private static String rawOf(FoldableExpr expr) {
            if (expr instanceof LitExpr l) return l.value();
            if (expr instanceof ParamExpr p) return p.rawText();
            if (expr instanceof EnumConstExpr e) return e.rawText();
            if (expr instanceof EnumAccessExpr e) return e.rawText();
            if (expr instanceof ConcatExpr c) return c.rawText();
            if (expr instanceof FormatExpr f) return f.rawText();
            if (expr instanceof DynamicExpr d) return d.rawText();
            return "";
        }

        private static boolean hasHostAfterScheme(String prefix) {
            String lower = prefix.toLowerCase(Locale.ROOT);
            int schemeEnd = -1;
            if (lower.startsWith("http://")) schemeEnd = 7;
            else if (lower.startsWith("https://")) schemeEnd = 8;
            else if (lower.startsWith("lb://")) schemeEnd = 5;
            if (schemeEnd < 0) return prefix.startsWith("/");
            String rest = prefix.substring(schemeEnd);
            return !rest.isEmpty() && rest.contains("/");
        }

        static final class Builder {
            private final EnumConstantMap.Builder enumBuilder = EnumConstantMap.builder();
            private final TreeMap<String, MethodSummaryNode> methodsById = new TreeMap<>();
            private final TreeMap<String, List<String>> methodsBySimpleKey = new TreeMap<>();
            private final TreeMap<String, List<String>> methodsByNameArity = new TreeMap<>();
            private final TreeMap<String, TreeSet<SinkParamSummary>> summariesByMethod = new TreeMap<>();

            void addUnit(String docPath, CompilationUnit cu) {
                if (cu == null) return;
                String safePath = docPath == null ? "" : docPath.replace('\\', '/');
                enumBuilder.addUnit(cu);
                String pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
                Map<String, String> explicitImports = new HashMap<>();
                Set<String> wildcardImports = new LinkedHashSet<>();
                for (ImportDeclaration imp : cu.getImports()) {
                    if (imp.isStatic()) continue;
                    String q = imp.getNameAsString();
                    if (imp.isAsterisk()) {
                        wildcardImports.add(q + ".");
                    } else {
                        explicitImports.put(simpleName(q), q);
                    }
                }

                for (ClassOrInterfaceDeclaration typeDecl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                    String ownerSimple = typeDecl.getNameAsString();
                    String ownerFqn = enclosingQualifiedName(typeDecl, pkg);
                    Map<String, String> typeFieldTypes = new HashMap<>();
                    Map<String, String> typeStringConstants = new LinkedHashMap<>();
                    Map<String, Expression> typeTargetInitializers = new HashMap<>();

                    var reassignedFields = new HashSet<String>();
                    for (AssignExpr a : typeDecl.findAll(AssignExpr.class)) {
                        if (a.getTarget() instanceof NameExpr n) reassignedFields.add(n.getNameAsString());
                        else if (a.getTarget() instanceof FieldAccessExpr f) reassignedFields.add(f.getNameAsString());
                    }
                    for (FieldDeclaration fd : typeDecl.getFields()) {
                        String rawType = stripGenerics(fd.getCommonType().asString());
                        String resolved = resolveImportOrLocal(rawType, pkg, explicitImports, wildcardImports);
                        for (VariableDeclarator vd : fd.getVariables()) {
                            typeFieldTypes.put(vd.getNameAsString(), resolved);
                            if (vd.getInitializer().isPresent()) {
                                typeTargetInitializers.put(vd.getNameAsString(), vd.getInitializer().orElseThrow());
                            }
                            if (isStringType(rawType)
                                    && (fd.isFinal() || (fd.isPrivate() && !reassignedFields.contains(vd.getNameAsString())))
                                    && vd.getInitializer().isPresent()) {
                                String folded = HttpClientConstants.syntaxUri(vd.getInitializer().orElseThrow(), typeStringConstants);
                                if (!folded.isEmpty()) typeStringConstants.put(vd.getNameAsString(), folded);
                            }
                        }
                    }

                    for (MethodDeclaration method : typeDecl.getMethods()) {
                        if (method.getBody().isEmpty()) continue;
                        indexMethod(safePath, pkg, ownerFqn, ownerSimple, method, typeFieldTypes,
                                typeStringConstants, typeTargetInitializers, explicitImports, wildcardImports);
                    }
                }
            }

            private void indexMethod(
                    String docPath,
                    String pkg,
                    String ownerFqn,
                    String ownerSimple,
                    MethodDeclaration method,
                    Map<String, String> typeFieldTypes,
                    Map<String, String> typeStringConstants,
                    Map<String, Expression> typeTargetInitializers,
                    Map<String, String> explicitImports,
                    Set<String> wildcardImports) {
                BlockStmt body = method.getBody().orElseThrow();
                String methodName = method.getNameAsString();
                int paramCount = method.getParameters().size();
                String methodId = ownerFqn + "#" + methodName + "(" + paramCount + ")";

                Map<String, Integer> paramIndexByName = new HashMap<>();
                Map<String, String> varTypes = new HashMap<>(typeFieldTypes);
                Map<String, String> localConstants = new LinkedHashMap<>(typeStringConstants);
                Map<String, Expression> localExprDefs = new HashMap<>(typeTargetInitializers);

                for (int i = 0; i < paramCount; i++) {
                    Parameter p = method.getParameter(i);
                    String pName = p.getNameAsString();
                    paramIndexByName.put(pName, i);
                    localConstants.remove(pName);
                    localExprDefs.remove(pName);
                    String rawType = stripGenerics(p.getType().asString());
                    varTypes.put(pName, resolveImportOrLocal(rawType, pkg, explicitImports, wildcardImports));
                }

                var reassignedLocals = new HashSet<String>();
                for (AssignExpr a : body.findAll(AssignExpr.class)) {
                    if (a.getTarget() instanceof NameExpr n) reassignedLocals.add(n.getNameAsString());
                }
                for (VariableDeclarationExpr vde : body.findAll(VariableDeclarationExpr.class)) {
                    String rawType = stripGenerics(vde.getCommonType().asString());
                    String resolved = resolveImportOrLocal(rawType, pkg, explicitImports, wildcardImports);
                    for (VariableDeclarator vd : vde.getVariables()) {
                        String vName = vd.getNameAsString();
                        varTypes.put(vName, resolved);
                        localConstants.remove(vName);
                        if (!vde.isFinal() && reassignedLocals.contains(vName)) continue;
                        vd.getInitializer().ifPresent(init -> {
                            localExprDefs.put(vName, init);
                            if (isStringType(rawType)) {
                                String folded = HttpClientConstants.syntaxUri(init, localConstants);
                                if (!folded.isEmpty()) localConstants.put(vName, folded);
                            }
                        });
                    }
                }

                var directSinks = new ArrayList<DirectSinkTemplate>();
                var outgoingCalls = new ArrayList<OutgoingCallTemplate>();

                for (MethodCallExpr call : body.findAll(MethodCallExpr.class)) {
                    String callName = call.getNameAsString();
                    String scopeRoot = rootIdOf(call.getScope().orElse(null));
                    String scopeType = varTypes.getOrDefault(scopeRoot, "");
                    if (scopeType.isEmpty() && !scopeRoot.isEmpty()) {
                        scopeType = resolveImportOrLocal(scopeRoot, pkg, explicitImports, wildcardImports);
                    }

                    // Check standard sinks (import-verified!)
                    DirectSinkTemplate sink = matchDirectSink(call, callName, scopeType, varTypes,
                            paramIndexByName, localConstants, localExprDefs, pkg, explicitImports, wildcardImports);
                    if (sink != null) {
                        directSinks.add(sink);
                        recordDirectSummaries(methodId, sink);
                        continue;
                    }

                    // Otherwise record as potential wrapper call if it takes >= 1 argument
                    if (!call.getArguments().isEmpty()) {
                        String receiverHint = scopeType;
                        if (call.getScope().isEmpty() || call.getScope().orElseThrow() instanceof ThisExpr) {
                            receiverHint = ownerFqn;
                        }
                        var loweredArgs = new ArrayList<FoldableExpr>(call.getArguments().size());
                        for (Expression arg : call.getArguments()) {
                            loweredArgs.add(lowerExpr(arg, paramIndexByName, localConstants, localExprDefs,
                                    explicitImports, 0));
                        }
                        int sl = call.getRange().map(r -> r.begin.line).orElse(0);
                        int sc = call.getRange().map(r -> r.begin.column).orElse(0);
                        int el = call.getRange().map(r -> r.end.line).orElse(0);
                        int ec = call.getRange().map(r -> r.end.column).orElse(0);
                        String callSiteId = docPath + ":" + sl + ":" + sc + "#" + callName;
                        outgoingCalls.add(new OutgoingCallTemplate(
                                callSiteId, docPath, receiverHint, callName,
                                call.getArguments().size(), List.copyOf(loweredArgs),
                                sl, sc, el, ec));
                    }
                }

                var node = new MethodSummaryNode(methodId, ownerFqn, ownerSimple, methodName, paramCount,
                        List.copyOf(directSinks), List.copyOf(outgoingCalls));
                methodsById.put(methodId, node);
                String simpleKey = ownerSimple + "#" + methodName + "(" + paramCount + ")";
                methodsBySimpleKey.computeIfAbsent(simpleKey, k -> new ArrayList<>()).add(methodId);
                String nameArity = methodName + "(" + paramCount + ")";
                methodsByNameArity.computeIfAbsent(nameArity, k -> new ArrayList<>()).add(methodId);
            }

            private DirectSinkTemplate matchDirectSink(
                    MethodCallExpr call,
                    String callName,
                    String scopeType,
                    Map<String, String> varTypes,
                    Map<String, Integer> paramIndexByName,
                    Map<String, String> localConstants,
                    Map<String, Expression> localExprDefs,
                    String pkg,
                    Map<String, String> explicitImports,
                    Set<String> wildcardImports) {
                // 1. RabbitTemplate / AmqpTemplate convertAndSend
                if ("convertAndSend".equals(callName) && isRabbitType(scopeType)) {
                    if (call.getArguments().size() >= 3) {
                        Expression arg0 = call.getArgument(0);
                        boolean emptyDefaultExch = arg0 instanceof StringLiteralExpr lit && lit.asString().isBlank();
                        if (emptyDefaultExch) {
                            FoldableExpr keyExpr = lowerExpr(call.getArgument(1), paramIndexByName, localConstants, localExprDefs, explicitImports, 0);
                            return new DirectSinkTemplate(DirectSinkFamily.AMQP_SEND, "", keyExpr, null, false);
                        }
                        FoldableExpr exchExpr = lowerExpr(arg0, paramIndexByName, localConstants, localExprDefs, explicitImports, 0);
                        FoldableExpr keyExpr = lowerExpr(call.getArgument(1), paramIndexByName, localConstants, localExprDefs, explicitImports, 0);
                        return new DirectSinkTemplate(DirectSinkFamily.AMQP_SEND, "", exchExpr, keyExpr, true);
                    }
                    if (call.getArguments().size() >= 1) {
                        FoldableExpr keyExpr = lowerExpr(call.getArgument(0), paramIndexByName, localConstants, localExprDefs, explicitImports, 0);
                        return new DirectSinkTemplate(DirectSinkFamily.AMQP_SEND, "", keyExpr, null, false);
                    }
                    return null;
                }
                // 2. KafkaTemplate send
                if ("send".equals(callName) && isKafkaType(scopeType) && !call.getArguments().isEmpty()) {
                    FoldableExpr topicExpr = lowerExpr(call.getArgument(0), paramIndexByName, localConstants, localExprDefs, explicitImports, 0);
                    return new DirectSinkTemplate(DirectSinkFamily.KAFKA_SEND, "", topicExpr, null, false);
                }
                // 3. DiscoveryClient / LoadBalancerClient
                if (("getInstances".equals(callName) && scopeType.equals("org.springframework.cloud.client.discovery.DiscoveryClient"))
                        || ("choose".equals(callName) && (scopeType.equals("org.springframework.cloud.client.loadbalancer.LoadBalancerClient")
                                || scopeType.equals("org.springframework.cloud.client.loadbalancer.ServiceInstanceChooser")))) {
                    if (!call.getArguments().isEmpty()) {
                        FoldableExpr hostExpr = lowerExpr(call.getArgument(0), paramIndexByName, localConstants, localExprDefs, explicitImports, 0);
                        return new DirectSinkTemplate(DirectSinkFamily.HTTP_DISCOVERY, "", hostExpr, null, false);
                    }
                    return null;
                }
                // 4. RestTemplate / RestOperations / OAuth2RestTemplate
                if (REST_SINK_METHODS.contains(callName) && isRestTemplateType(scopeType) && !call.getArguments().isEmpty()) {
                    FoldableExpr uriExpr = lowerExpr(call.getArgument(0), paramIndexByName, localConstants, localExprDefs, explicitImports, 0);
                    return new DirectSinkTemplate(DirectSinkFamily.HTTP_REST, restVerbOf(callName), uriExpr, null, false);
                }
                // 5. WebClient / RestClient .uri(...)
                if ("uri".equals(callName) && isFluentClientChain(call, scopeType, varTypes, pkg, explicitImports, wildcardImports)
                        && !call.getArguments().isEmpty()) {
                    FoldableExpr relExpr = lowerExpr(call.getArgument(0), paramIndexByName, localConstants, localExprDefs, explicitImports, 0);
                    FoldableExpr baseExpr = extractFluentBaseExpr(call.getScope().orElse(null), paramIndexByName, localConstants, localExprDefs, explicitImports);
                    FoldableExpr fullExpr = combineUriFoldable(baseExpr, relExpr);
                    return new DirectSinkTemplate(DirectSinkFamily.HTTP_FLUENT, fluentVerbOf(call), fullExpr, null, false);
                }
                // 6. JAX-RS WebTarget / ClientBuilder .request()
                if ("request".equals(callName) && isWebTargetChain(call, scopeType, varTypes, pkg, explicitImports, wildcardImports)) {
                    FoldableExpr fullExpr = extractWebTargetFoldable(call, paramIndexByName, localConstants, localExprDefs, explicitImports);
                    if (fullExpr != null) {
                        return new DirectSinkTemplate(DirectSinkFamily.HTTP_WEB_TARGET, jaxRsVerbOf(call), fullExpr, null, false);
                    }
                }
                return null;
            }

            private void recordDirectSummaries(String methodId, DirectSinkTemplate sink) {
                switch (sink.family()) {
                    case HTTP_REST, HTTP_FLUENT, HTTP_WEB_TARGET -> {
                        var refs = new ArrayList<ParamRefRole>();
                        collectParamRoles(sink.uriOrHostExpr(), true, refs);
                        for (ParamRefRole role : refs) {
                            addSummary(new SinkParamSummary(methodId,
                                    role.hostPosition() ? SinkKind.HTTP_HOST : SinkKind.HTTP_URI,
                                    role.paramIndex(), role.accessor(), 1));
                        }
                    }
                    case HTTP_DISCOVERY -> {
                        var refs = new ArrayList<ParamRefRole>();
                        collectParamRoles(sink.uriOrHostExpr(), false, refs);
                        for (ParamRefRole role : refs) {
                            addSummary(new SinkParamSummary(methodId, SinkKind.HTTP_HOST,
                                    role.paramIndex(), role.accessor(), 1));
                        }
                    }
                    case AMQP_SEND -> {
                        if (sink.amqpHasExplicitExchange()) {
                            var exRefs = new ArrayList<ParamRefRole>();
                            collectParamRoles(sink.uriOrHostExpr(), false, exRefs);
                            for (ParamRefRole role : exRefs) {
                                addSummary(new SinkParamSummary(methodId, SinkKind.AMQP_EXCHANGE,
                                        role.paramIndex(), role.accessor(), 1));
                            }
                            var keyRefs = new ArrayList<ParamRefRole>();
                            collectParamRoles(sink.secondaryExpr(), false, keyRefs);
                            for (ParamRefRole role : keyRefs) {
                                addSummary(new SinkParamSummary(methodId, SinkKind.AMQP_KEY,
                                        role.paramIndex(), role.accessor(), 1));
                            }
                        } else {
                            var keyRefs = new ArrayList<ParamRefRole>();
                            collectParamRoles(sink.uriOrHostExpr(), false, keyRefs);
                            for (ParamRefRole role : keyRefs) {
                                addSummary(new SinkParamSummary(methodId, SinkKind.AMQP_KEY,
                                        role.paramIndex(), role.accessor(), 1));
                            }
                        }
                    }
                    case KAFKA_SEND -> {
                        var refs = new ArrayList<ParamRefRole>();
                        collectParamRoles(sink.uriOrHostExpr(), false, refs);
                        for (ParamRefRole role : refs) {
                            addSummary(new SinkParamSummary(methodId, SinkKind.KAFKA_TOPIC,
                                    role.paramIndex(), role.accessor(), 1));
                        }
                    }
                }
            }

            private void addSummary(SinkParamSummary summary) {
                summariesByMethod.computeIfAbsent(summary.methodId(), k -> new TreeSet<>()).add(summary);
            }

            WrapperFoldingIndex build() {
                // Reverse topological propagation for hopDepth = 2 .. MAX_HOP_DEPTH (4)
                for (int hop = 2; hop <= MAX_HOP_DEPTH; hop++) {
                    boolean changed = false;
                    var toAdd = new ArrayList<SinkParamSummary>();
                    for (MethodSummaryNode caller : methodsById.values()) {
                        for (OutgoingCallTemplate call : caller.outgoingCalls()) {
                            String calleeId = resolveCalleeStatic(caller, call);
                            if (calleeId == null) continue;
                            var calleeSummaries = summariesByMethod.get(calleeId);
                            if (calleeSummaries == null || calleeSummaries.isEmpty()) continue;
                            for (SinkParamSummary cs : calleeSummaries) {
                                if (cs.hopDepth() != hop - 1) continue;
                                if (cs.paramIndex() < 0 || cs.paramIndex() >= call.args().size()) continue;
                                FoldableExpr arg = call.args().get(cs.paramIndex());
                                var refs = new ArrayList<ParamRefRole>();
                                collectParamRoles(arg, false, refs);
                                for (ParamRefRole role : refs) {
                                    String accessor = !role.accessor().isEmpty()
                                            ? role.accessor()
                                            : cs.enumFieldAccessor();
                                    toAdd.add(new SinkParamSummary(caller.methodId(), cs.sinkKind(),
                                            role.paramIndex(), accessor, hop));
                                }
                            }
                        }
                    }
                    for (SinkParamSummary s : toAdd) {
                        if (summariesByMethod.computeIfAbsent(s.methodId(), k -> new TreeSet<>()).add(s)) {
                            changed = true;
                        }
                    }
                    if (!changed) break;
                }
                return new WrapperFoldingIndex(
                        enumBuilder.build(),
                        new TreeMap<>(methodsById),
                        new TreeMap<>(methodsBySimpleKey),
                        new TreeMap<>(methodsByNameArity),
                        new TreeMap<>(summariesByMethod));
            }

            private String resolveCalleeStatic(MethodSummaryNode caller, OutgoingCallTemplate call) {
                String hint = call.receiverTypeHint();
                if (hint.isEmpty() || "this".equals(hint)) {
                    String sameOwner = caller.ownerFqn() + "#" + call.calleeName() + "(" + call.argCount() + ")";
                    if (methodsById.containsKey(sameOwner)) return sameOwner;
                }
                if (!hint.isEmpty()) {
                    String exactFqn = hint + "#" + call.calleeName() + "(" + call.argCount() + ")";
                    if (methodsById.containsKey(exactFqn)) return exactFqn;
                    String simpleKey = simpleName(hint) + "#" + call.calleeName() + "(" + call.argCount() + ")";
                    List<String> bySimple = methodsBySimpleKey.getOrDefault(simpleKey, List.of());
                    if (bySimple.size() == 1) return bySimple.getFirst();
                }
                List<String> byName = methodsByNameArity.getOrDefault(call.calleeName() + "(" + call.argCount() + ")", List.of());
                if (byName.size() == 1) return byName.getFirst();
                return null;
            }

            private FoldableExpr lowerExpr(
                    Expression expr,
                    Map<String, Integer> paramIndexByName,
                    Map<String, String> localConstants,
                    Map<String, Expression> localExprDefs,
                    Map<String, String> explicitImports,
                    int depth) {
                if (expr == null || depth > 24) return new DynamicExpr("");
                if (expr instanceof EnclosedExpr enclosed) {
                    return lowerExpr(enclosed.getInner(), paramIndexByName, localConstants, localExprDefs, explicitImports, depth + 1);
                }
                if (expr instanceof StringLiteralExpr lit) return new LitExpr(lit.asString());
                if (expr instanceof TextBlockLiteralExpr lit) return new LitExpr(lit.asString());
                if (expr instanceof IntegerLiteralExpr || expr instanceof BooleanLiteralExpr) {
                    return new LitExpr(expr.toString());
                }
                if (expr instanceof LongLiteralExpr ll) return new LitExpr(ll.asNumber().toString());
                if (expr instanceof CharLiteralExpr cl) return new LitExpr(String.valueOf(cl.asChar()));
                if (expr instanceof NameExpr ne) {
                    String name = ne.getNameAsString();
                    Integer pIdx = paramIndexByName.get(name);
                    if (pIdx != null) return new ParamExpr(pIdx, "", name);
                    String cVal = localConstants.get(name);
                    if (cVal != null) return new LitExpr(cVal);
                    Expression localInit = localExprDefs.get(name);
                    if (localInit != null) {
                        return lowerExpr(localInit, paramIndexByName, localConstants, localExprDefs, explicitImports, depth + 1);
                    }
                    return new DynamicExpr(name);
                }
                if (expr instanceof FieldAccessExpr fa) {
                    String full = fa.toString();
                    if (fa.getScope() instanceof ThisExpr) {
                        String cVal = localConstants.get(fa.getNameAsString());
                        if (cVal != null) return new LitExpr(cVal);
                        return new DynamicExpr(full);
                    }
                    if (fa.getScope() instanceof NameExpr scopeName) {
                        Integer pIdx = paramIndexByName.get(scopeName.getNameAsString());
                        if (pIdx != null) {
                            return new ParamExpr(pIdx, fa.getNameAsString(), full);
                        }
                        String cVal = localConstants.get(full);
                        if (cVal != null) return new LitExpr(cVal);
                        // Enum constant reference e.g. Service.AUTH
                        String resolvedType = explicitImports.getOrDefault(scopeName.getNameAsString(), scopeName.getNameAsString());
                        if (looksLikeTypeName(scopeName.getNameAsString())) {
                            return new EnumConstExpr(resolvedType, fa.getNameAsString(), full);
                        }
                    }
                    if (fa.getScope() instanceof FieldAccessExpr innerFa) {
                        // Enum constant field access e.g. Service.AUTH.host or pkg.Service.AUTH.host
                        String maybeType = innerFa.getScope().toString();
                        String maybeConst = innerFa.getNameAsString();
                        if (looksLikeTypeName(simpleName(maybeType))) {
                            String resolvedType = explicitImports.getOrDefault(maybeType, maybeType);
                            return new EnumAccessExpr(resolvedType, maybeConst, fa.getNameAsString(), full);
                        }
                    }
                    return new DynamicExpr(full);
                }
                if (expr instanceof BinaryExpr bin && bin.getOperator() == BinaryExpr.Operator.PLUS) {
                    var parts = new ArrayList<FoldableExpr>();
                    flattenConcat(bin, parts, paramIndexByName, localConstants, localExprDefs, explicitImports, depth + 1);
                    return new ConcatExpr(List.copyOf(parts), expr.toString());
                }
                if (expr instanceof MethodCallExpr mc) {
                    String mName = mc.getNameAsString();
                    // String.format(...)
                    if ("format".equals(mName) && !mc.getArguments().isEmpty()) {
                        boolean stringScope = mc.getScope().map(s -> {
                            String t = s.toString();
                            return "String".equals(t) || "java.lang.String".equals(t);
                        }).orElse(true);
                        if (stringScope) {
                            int tmplIdx = 0;
                            if (mc.getArguments().size() >= 2) {
                                String first = mc.getArgument(0).toString();
                                if (first.startsWith("Locale.") || first.startsWith("java.util.Locale.")) tmplIdx = 1;
                            }
                            if (tmplIdx < mc.getArguments().size()) {
                                FoldableExpr tmpl = lowerExpr(mc.getArgument(tmplIdx), paramIndexByName, localConstants, localExprDefs, explicitImports, depth + 1);
                                var fArgs = new ArrayList<FoldableExpr>();
                                for (int i = tmplIdx + 1; i < mc.getArguments().size(); i++) {
                                    fArgs.add(lowerExpr(mc.getArgument(i), paramIndexByName, localConstants, localExprDefs, explicitImports, depth + 1));
                                }
                                return new FormatExpr(tmpl, List.copyOf(fArgs), mc.toString());
                            }
                        }
                    }
                    // URI.create(x)
                    if ("create".equals(mName) && mc.getArguments().size() == 1
                            && mc.getScope().map(s -> "URI".equals(s.toString()) || "java.net.URI".equals(s.toString())).orElse(false)) {
                        return lowerExpr(mc.getArgument(0), paramIndexByName, localConstants, localExprDefs, explicitImports, depth + 1);
                    }
                    // 0-arg accessor call on parameter or enum constant: param.getHost() or Service.AUTH.getHost()
                    if (mc.getArguments().isEmpty() && mc.getScope().isPresent()) {
                        Expression scope = mc.getScope().orElseThrow();
                        if (scope instanceof NameExpr ne) {
                            Integer pIdx = paramIndexByName.get(ne.getNameAsString());
                            if (pIdx != null) {
                                return new ParamExpr(pIdx, mName, mc.toString());
                            }
                        }
                        if (scope instanceof FieldAccessExpr fa) {
                            String maybeType = fa.getScope().toString();
                            String maybeConst = fa.getNameAsString();
                            if (looksLikeTypeName(simpleName(maybeType))) {
                                String resolvedType = explicitImports.getOrDefault(maybeType, maybeType);
                                return new EnumAccessExpr(resolvedType, maybeConst, mName, mc.toString());
                            }
                        }
                    }
                }
                return new DynamicExpr(expr.toString());
            }

            private void flattenConcat(
                    BinaryExpr bin,
                    List<FoldableExpr> parts,
                    Map<String, Integer> paramIndexByName,
                    Map<String, String> localConstants,
                    Map<String, Expression> localExprDefs,
                    Map<String, String> explicitImports,
                    int depth) {
                if (bin.getLeft() instanceof BinaryExpr leftBin && leftBin.getOperator() == BinaryExpr.Operator.PLUS && depth < 24) {
                    flattenConcat(leftBin, parts, paramIndexByName, localConstants, localExprDefs, explicitImports, depth + 1);
                } else {
                    parts.add(lowerExpr(bin.getLeft(), paramIndexByName, localConstants, localExprDefs, explicitImports, depth + 1));
                }
                if (bin.getRight() instanceof BinaryExpr rightBin && rightBin.getOperator() == BinaryExpr.Operator.PLUS && depth < 24) {
                    flattenConcat(rightBin, parts, paramIndexByName, localConstants, localExprDefs, explicitImports, depth + 1);
                } else {
                    parts.add(lowerExpr(bin.getRight(), paramIndexByName, localConstants, localExprDefs, explicitImports, depth + 1));
                }
            }

            private FoldableExpr extractFluentBaseExpr(
                    Expression scope,
                    Map<String, Integer> paramIndexByName,
                    Map<String, String> localConstants,
                    Map<String, Expression> localExprDefs,
                    Map<String, String> explicitImports) {
                Expression cur = scope;
                var visited = new HashSet<String>();
                int steps = 0;
                while (cur != null && steps++ < 12) {
                    if (cur instanceof MethodCallExpr mc) {
                        String n = mc.getNameAsString();
                        if (("baseUrl".equals(n) || "create".equals(n)) && mc.getArguments().size() == 1) {
                            return lowerExpr(mc.getArgument(0), paramIndexByName, localConstants, localExprDefs, explicitImports, 0);
                        }
                        cur = mc.getScope().orElse(null);
                        continue;
                    }
                    if (cur instanceof NameExpr ne && visited.add(ne.getNameAsString())) {
                        cur = localExprDefs.get(ne.getNameAsString());
                        continue;
                    }
                    if (cur instanceof FieldAccessExpr fa && fa.getScope() instanceof ThisExpr && visited.add(fa.getNameAsString())) {
                        cur = localExprDefs.get(fa.getNameAsString());
                        continue;
                    }
                    break;
                }
                return null;
            }

            private FoldableExpr extractWebTargetFoldable(
                    MethodCallExpr requestCall,
                    Map<String, Integer> paramIndexByName,
                    Map<String, String> localConstants,
                    Map<String, Expression> localExprDefs,
                    Map<String, String> explicitImports) {
                var pathParts = new ArrayList<FoldableExpr>();
                FoldableExpr base = null;
                Expression cur = requestCall.getScope().orElse(null);
                var visited = new HashSet<String>();
                int steps = 0;
                while (cur != null && steps++ < 16) {
                    if (cur instanceof MethodCallExpr mc) {
                        String n = mc.getNameAsString();
                        if ("path".equals(n) && !mc.getArguments().isEmpty()) {
                            pathParts.addFirst(lowerExpr(mc.getArgument(0), paramIndexByName, localConstants, localExprDefs, explicitImports, 0));
                            cur = mc.getScope().orElse(null);
                            continue;
                        }
                        if ("target".equals(n) && !mc.getArguments().isEmpty()) {
                            base = lowerExpr(mc.getArgument(0), paramIndexByName, localConstants, localExprDefs, explicitImports, 0);
                            break;
                        }
                        cur = mc.getScope().orElse(null);
                        continue;
                    }
                    if (cur instanceof NameExpr ne && visited.add(ne.getNameAsString())) {
                        cur = localExprDefs.get(ne.getNameAsString());
                        continue;
                    }
                    if (cur instanceof FieldAccessExpr fa && fa.getScope() instanceof ThisExpr && visited.add(fa.getNameAsString())) {
                        cur = localExprDefs.get(fa.getNameAsString());
                        continue;
                    }
                    break;
                }
                if (base == null && pathParts.isEmpty()) return null;
                var all = new ArrayList<FoldableExpr>();
                if (base != null) all.add(base);
                for (FoldableExpr seg : pathParts) {
                    if (seg instanceof LitExpr l && l.value().startsWith("/")) {
                        all.add(seg);
                    } else {
                        all.add(new LitExpr("/"));
                        all.add(seg);
                    }
                }
                return all.size() == 1 ? all.getFirst() : new ConcatExpr(List.copyOf(all), requestCall.toString());
            }

            private static FoldableExpr combineUriFoldable(FoldableExpr base, FoldableExpr rel) {
                if (base == null) return rel;
                if (rel == null) return base;
                if (rel instanceof LitExpr l && isUriOrPathPrefix(l.value()) && !l.value().startsWith("/")) {
                    return rel;
                }
                return new ConcatExpr(List.of(base, rel), "");
            }

            private record ParamRefRole(int paramIndex, String accessor, boolean hostPosition) {}

            private static void collectParamRoles(FoldableExpr expr, boolean checkHostPos, List<ParamRefRole> out) {
                if (expr instanceof ParamExpr pe) {
                    out.add(new ParamRefRole(pe.paramIndex(), pe.accessor(), false));
                    return;
                }
                if (expr instanceof ConcatExpr c) {
                    var prefix = new StringBuilder();
                    for (FoldableExpr p : c.parts()) {
                        if (p instanceof LitExpr l) {
                            prefix.append(l.value());
                        } else if (p instanceof ParamExpr pe) {
                            boolean hostPos = checkHostPos && isSchemeOnlyPrefix(prefix.toString());
                            out.add(new ParamRefRole(pe.paramIndex(), pe.accessor(), hostPos));
                            prefix.append("{x}");
                        } else {
                            collectParamRoles(p, checkHostPos, out);
                        }
                    }
                    return;
                }
                if (expr instanceof FormatExpr f) {
                    String tmpl = f.template() instanceof LitExpr l ? l.value() : "";
                    for (int i = 0; i < f.args().size(); i++) {
                        FoldableExpr a = f.args().get(i);
                        boolean hostPos = checkHostPos && i == 0 && (tmpl.startsWith("http://%s") || tmpl.startsWith("https://%s") || tmpl.startsWith("lb://%s"));
                        if (a instanceof ParamExpr pe) {
                            out.add(new ParamRefRole(pe.paramIndex(), pe.accessor(), hostPos));
                        } else {
                            collectParamRoles(a, false, out);
                        }
                    }
                }
            }

            private static boolean isSchemeOnlyPrefix(String s) {
                String lower = s.trim().toLowerCase(Locale.ROOT);
                return lower.equals("http://") || lower.equals("https://") || lower.equals("lb://");
            }

            private static boolean isRabbitType(String t) {
                return t.equals("org.springframework.amqp.rabbit.core.RabbitTemplate")
                        || t.equals("org.springframework.amqp.core.AmqpTemplate");
            }

            private static boolean isKafkaType(String t) {
                return t.equals("org.springframework.kafka.core.KafkaTemplate");
            }

            private static boolean isRestTemplateType(String t) {
                return t.equals("org.springframework.web.client.RestTemplate")
                        || t.equals("org.springframework.web.client.RestOperations")
                        || t.equals("org.springframework.security.oauth2.client.OAuth2RestTemplate")
                        || t.equals("org.springframework.security.oauth2.client.OAuth2RestOperations");
            }

            private static boolean isFluentClientChain(MethodCallExpr call, String scopeType,
                    Map<String, String> varTypes, String pkg,
                    Map<String, String> explicitImports, Set<String> wildcardImports) {
                if (scopeType.startsWith("org.springframework.web.client.RestClient")
                        || scopeType.startsWith("org.springframework.web.reactive.function.client.WebClient")) {
                    return true;
                }
                Expression cur = call.getScope().orElse(null);
                int steps = 0;
                while (cur instanceof MethodCallExpr mc && steps++ < 8) {
                    cur = mc.getScope().orElse(null);
                }
                String root = rootIdOf(cur);
                String t = varTypes.getOrDefault(root, resolveImportOrLocal(root, pkg, explicitImports, wildcardImports));
                return t.startsWith("org.springframework.web.client.RestClient")
                        || t.startsWith("org.springframework.web.reactive.function.client.WebClient");
            }

            private static boolean isWebTargetChain(MethodCallExpr call, String scopeType,
                    Map<String, String> varTypes, String pkg,
                    Map<String, String> explicitImports, Set<String> wildcardImports) {
                if (scopeType.startsWith("javax.ws.rs.client.") || scopeType.startsWith("jakarta.ws.rs.client.")) {
                    return true;
                }
                Expression cur = call.getScope().orElse(null);
                int steps = 0;
                while (cur instanceof MethodCallExpr mc && steps++ < 12) {
                    if ("target".equals(mc.getNameAsString()) && !mc.getArguments().isEmpty()) {
                        String root = rootIdOf(mc.getScope().orElse(null));
                        String t = varTypes.getOrDefault(root, resolveImportOrLocal(root, pkg, explicitImports, wildcardImports));
                        if (t.startsWith("javax.ws.rs.client.") || t.startsWith("jakarta.ws.rs.client.")) return true;
                    }
                    cur = mc.getScope().orElse(null);
                }
                String root = rootIdOf(cur);
                String t = varTypes.getOrDefault(root, resolveImportOrLocal(root, pkg, explicitImports, wildcardImports));
                return t.startsWith("javax.ws.rs.client.") || t.startsWith("jakarta.ws.rs.client.");
            }

            private static String restVerbOf(String m) {
                return m.startsWith("getFor") ? "GET"
                        : m.startsWith("postFor") ? "POST"
                        : "put".equals(m) ? "PUT"
                        : "patchForObject".equals(m) ? "PATCH"
                        : "delete".equals(m) ? "DELETE"
                        : "headForHeaders".equals(m) ? "HEAD"
                        : "optionsForAllow".equals(m) ? "OPTIONS" : "";
            }

            private static String fluentVerbOf(MethodCallExpr uriCall) {
                Expression cur = uriCall.getScope().orElse(null);
                while (cur instanceof MethodCallExpr mc) {
                    String n = mc.getNameAsString();
                    if (HTTP_VERBS.contains(n)) return n.toUpperCase(Locale.ROOT);
                    cur = mc.getScope().orElse(null);
                }
                return "";
            }

            private static String jaxRsVerbOf(MethodCallExpr reqCall) {
                Node cur = reqCall;
                for (int d = 0; d < 6; d++) {
                    Node parent = cur.getParentNode().orElse(null);
                    if (!(parent instanceof MethodCallExpr pmc)) break;
                    Node expected = cur;
                    if (pmc.getScope().filter(s -> s == expected).isEmpty()) break;
                    String n = pmc.getNameAsString();
                    if (HTTP_VERBS.contains(n)) return n.toUpperCase(Locale.ROOT);
                    if (Set.of("buildGet", "buildPost", "buildPut", "buildDelete").contains(n)) {
                        return n.substring(5).toUpperCase(Locale.ROOT);
                    }
                    cur = pmc;
                }
                return "";
            }
        }
    }

    // -------------------------------------------------------------------------
    // Existing & Enum-enhanced constant folding entry points
    // -------------------------------------------------------------------------

    private HttpClientConstants() {}

    static String uri(Expression expression) {
        return uri(expression, EnumConstantMap.EMPTY);
    }

    static String uri(Expression expression, EnumConstantMap enumMap) {
        try {
            return new Evaluation(enumMap == null ? EnumConstantMap.EMPTY : enumMap).value(expression, 0).orElse("");
        } catch (RuntimeException unknown) {
            if (unknown instanceof java.util.concurrent.CancellationException) throw unknown;
            return "";
        }
    }

    /** Syntax-only constant folding for --build=off Java source extraction using pre-collected
     * effectively-final local/private constants, without invoking symbol resolution. */
    static String syntaxUri(Expression expression, Map<String, String> constants) {
        return syntaxUri(expression, constants, EnumConstantMap.EMPTY);
    }

    static String syntaxUri(Expression expression, Map<String, String> constants, EnumConstantMap enumMap) {
        if (expression == null) return "";
        try {
            return syntaxValue(expression, constants == null ? Map.of() : constants,
                    enumMap == null ? EnumConstantMap.EMPTY : enumMap, 0).orElse("");
        } catch (RuntimeException unknown) {
            if (unknown instanceof java.util.concurrent.CancellationException) throw unknown;
            return "";
        }
    }

    private static Optional<String> syntaxValue(Expression expression, Map<String, String> constants,
            EnumConstantMap enumMap, int depth) {
        if (depth > 64 || expression == null) return Optional.empty();
        if (expression instanceof StringLiteralExpr literal) return bounded(literal.asString());
        if (expression instanceof TextBlockLiteralExpr literal) return bounded(literal.asString());
        if (expression instanceof EnclosedExpr enclosed) return syntaxValue(enclosed.getInner(), constants, enumMap, depth + 1);
        if (expression instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.PLUS) {
            var left = syntaxValue(binary.getLeft(), constants, enumMap, depth + 1);
            if (left.isEmpty()) return Optional.empty();
            String leftStr = left.orElseThrow();
            var right = syntaxValue(binary.getRight(), constants, enumMap, depth + 1);
            if (right.isPresent()) {
                return bounded(leftStr + right.orElseThrow());
            }
            if (isUriOrPathPrefix(leftStr)) {
                return bounded(leftStr + dynamicOrPrimitiveSegment(binary.getRight()));
            }
            return Optional.empty();
        }
        if (expression instanceof NameExpr name) {
            String v = constants.get(name.getNameAsString());
            return v != null ? bounded(v) : Optional.empty();
        }
        if (expression instanceof FieldAccessExpr field) {
            if (field.getScope() instanceof ThisExpr || field.getScope() instanceof NameExpr) {
                String scoped = constants.get(field.getNameAsString());
                if (scoped != null) return bounded(scoped);
                String qualified = constants.get(field.toString());
                if (qualified != null) return bounded(qualified);
            }
            Optional<String> enumField = resolveEnumAccess(field, enumMap);
            if (enumField.isPresent()) return enumField.flatMap(HttpClientConstants::bounded);
            return Optional.empty();
        }
        if (expression instanceof MethodCallExpr call) {
            Optional<String> enumGetter = resolveEnumGetterCall(call, enumMap);
            if (enumGetter.isPresent()) return enumGetter.flatMap(HttpClientConstants::bounded);
            return foldFormatCall(call, arg -> syntaxValue(arg, constants, enumMap, depth + 1));
        }
        return Optional.empty();
    }

    private static Optional<String> resolveEnumAccess(FieldAccessExpr field, EnumConstantMap enumMap) {
        if (enumMap == null || enumMap.isEmpty()) return Optional.empty();
        if (field.getScope() instanceof FieldAccessExpr inner) {
            String typeName = inner.getScope().toString();
            String constName = inner.getNameAsString();
            return enumMap.lookup(typeName, constName, field.getNameAsString());
        }
        return Optional.empty();
    }

    private static Optional<String> resolveEnumGetterCall(MethodCallExpr call, EnumConstantMap enumMap) {
        if (enumMap == null || enumMap.isEmpty() || !call.getArguments().isEmpty() || call.getScope().isEmpty()) {
            return Optional.empty();
        }
        Expression scope = call.getScope().orElseThrow();
        if (scope instanceof FieldAccessExpr fa) {
            return enumMap.lookup(fa.getScope().toString(), fa.getNameAsString(), call.getNameAsString());
        }
        if (scope instanceof NameExpr ne) {
            return enumMap.lookup("", ne.getNameAsString(), call.getNameAsString());
        }
        return Optional.empty();
    }

    private static Optional<String> foldFormatCall(MethodCallExpr call, java.util.function.Function<Expression, Optional<String>> resolver) {
        if (!call.getNameAsString().equals("format") || call.getArguments().isEmpty()) return Optional.empty();
        boolean stringScope = call.getScope().map(s -> {
            String t = s.toString();
            return t.equals("String") || t.equals("java.lang.String");
        }).orElse(true);
        if (!stringScope) return Optional.empty();
        int templateIdx = 0;
        if (call.getArguments().size() >= 2) {
            String first = call.getArgument(0).toString();
            if (first.startsWith("Locale.") || first.startsWith("java.util.Locale.")) {
                templateIdx = 1;
            }
        }
        if (templateIdx >= call.getArguments().size()) return Optional.empty();
        var templateOpt = resolver.apply(call.getArgument(templateIdx));
        if (templateOpt.isEmpty()) return Optional.empty();
        String template = templateOpt.orElseThrow();
        Matcher m = PRINTF_TOKEN.matcher(template);
        var sb = new StringBuilder(template.length());
        int argCursor = templateIdx + 1;
        while (m.find()) {
            String conv = m.group(1);
            if ("%".equals(conv)) {
                m.appendReplacement(sb, Matcher.quoteReplacement("%"));
                continue;
            }
            String rep = "{param}";
            if (argCursor < call.getArguments().size()) {
                Expression arg = call.getArgument(argCursor++);
                var folded = resolver.apply(arg);
                if (folded.isPresent()) {
                    rep = folded.orElseThrow();
                } else if (arg instanceof IntegerLiteralExpr || arg instanceof LongLiteralExpr
                        || arg instanceof BooleanLiteralExpr || arg instanceof CharLiteralExpr) {
                    rep = arg.toString();
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return bounded(sb.toString());
    }

    private static boolean isReassignedInScope(VariableDeclarationExpr local, VariableDeclarator variable) {
        String targetName = variable.getNameAsString();
        Node scope = local.findAncestor(CallableDeclaration.class).map(Node.class::cast)
                .or(() -> local.findAncestor(InitializerDeclaration.class).map(Node.class::cast))
                .or(() -> local.findAncestor(BlockStmt.class).map(Node.class::cast))
                .orElse(null);
        if (scope == null) return true;
        for (AssignExpr assign : scope.findAll(AssignExpr.class)) {
            if (assign.getTarget() instanceof NameExpr n && n.getNameAsString().equals(targetName)) return true;
        }
        for (UnaryExpr unary : scope.findAll(UnaryExpr.class)) {
            if (unary.getExpression() instanceof NameExpr n && n.getNameAsString().equals(targetName)) return true;
        }
        return false;
    }

    private static boolean isReassignedInType(FieldDeclaration field, VariableDeclarator variable) {
        String targetName = variable.getNameAsString();
        Node owner = field.getParentNode().orElse(null);
        if (owner == null) return true;
        for (AssignExpr assign : owner.findAll(AssignExpr.class)) {
            Expression tgt = assign.getTarget();
            if (tgt instanceof NameExpr n && n.getNameAsString().equals(targetName)) return true;
            if (tgt instanceof FieldAccessExpr f && f.getNameAsString().equals(targetName)
                    && (f.getScope() instanceof ThisExpr || f.getScope() instanceof NameExpr)) return true;
        }
        return false;
    }

    private static final class Evaluation {
        private final EnumConstantMap enumMap;
        private final Set<VariableDeclarator> visiting = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Map<VariableDeclarator, Optional<String>> memo = new IdentityHashMap<>();
        private int remaining = 4096;

        Evaluation(EnumConstantMap enumMap) {
            this.enumMap = enumMap;
        }

        private Optional<String> value(Expression expression, int depth) {
            if (depth > 128 || --remaining < 0) return Optional.empty();
            if (expression instanceof StringLiteralExpr literal) return bounded(literal.asString());
            if (expression instanceof TextBlockLiteralExpr literal) return bounded(literal.asString());
            if (expression instanceof EnclosedExpr enclosed) return value(enclosed.getInner(), depth + 1);
            if (expression instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.PLUS) {
                var left = value(binary.getLeft(), depth + 1);
                var right = left.isEmpty() ? Optional.<String>empty() : value(binary.getRight(), depth + 1);
                return left.isPresent() && right.isPresent() ? bounded(left.orElseThrow() + right.orElseThrow()) : Optional.empty();
            }
            if (expression instanceof MethodCallExpr call) {
                var enumGetter = resolveEnumGetterCall(call, enumMap);
                if (enumGetter.isPresent()) return enumGetter.flatMap(HttpClientConstants::bounded);
                var formatted = foldFormatCall(call, arg -> value(arg, depth + 1));
                if (formatted.isPresent()) return formatted;
            }
            if (expression instanceof FieldAccessExpr fa) {
                var enumField = resolveEnumAccess(fa, enumMap);
                if (enumField.isPresent()) return enumField.flatMap(HttpClientConstants::bounded);
            }
            var resolved = expression instanceof NameExpr name ? name.resolve()
                    : expression instanceof FieldAccessExpr field ? field.resolve() : null;
            if (resolved == null) return Optional.empty();
            Node ast = resolved.toAst().orElse(null);
            VariableDeclarator variable;
            if (ast instanceof VariableDeclarator candidate) variable = candidate;
            else if (ast instanceof FieldDeclaration field)
                variable = field.getVariables().stream().filter(v -> v.getNameAsString().equals(resolved.getName())).findFirst().orElse(null);
            else if (ast instanceof VariableDeclarationExpr local)
                variable = local.getVariables().stream().filter(v -> v.getNameAsString().equals(resolved.getName())).findFirst().orElse(null);
            else variable = null;
            if (variable == null) return Optional.empty();
            if (memo.containsKey(variable)) return memo.get(variable);
            if (!visiting.add(variable)) return Optional.empty();
            try {
                Node parent = variable.getParentNode().orElse(null);
                boolean fixed = parent instanceof VariableDeclarationExpr local && (local.isFinal() || !isReassignedInScope(local, variable))
                        || parent instanceof FieldDeclaration field && (field.isFinal()
                                || field.getParentNode().filter(n -> n instanceof ClassOrInterfaceDeclaration type && type.isInterface()).isPresent()
                                || (field.isPrivate() && !isReassignedInType(field, variable)));
                if (!fixed || !variable.getType().resolve().describe().equals("java.lang.String")) return Optional.empty();
                var result = variable.getInitializer().flatMap(initializer -> value(initializer, depth + 1));
                memo.put(variable, result);
                return result;
            } finally {
                visiting.remove(variable);
            }
        }
    }

    private static String foldEnumArg(Expression arg, Map<String, String> localConsts) {
        if (arg == null) return "";
        String folded = syntaxUri(arg, localConsts).trim();
        if (!folded.isEmpty()) return folded;
        if (arg instanceof IntegerLiteralExpr || arg instanceof BooleanLiteralExpr) return arg.toString();
        if (arg instanceof LongLiteralExpr ll) return ll.asNumber().toString();
        if (arg instanceof CharLiteralExpr cl) return String.valueOf(cl.asChar());
        return "";
    }

    private static String assignedFieldName(Expression target) {
        if (target instanceof NameExpr n) return n.getNameAsString();
        if (target instanceof FieldAccessExpr f && f.getScope() instanceof ThisExpr) return f.getNameAsString();
        return "";
    }

    private static String returnedFieldName(Expression expr) {
        Expression cur = expr;
        while (cur instanceof EnclosedExpr e) cur = e.getInner();
        if (cur instanceof NameExpr n) return n.getNameAsString();
        if (cur instanceof FieldAccessExpr f && f.getScope() instanceof ThisExpr) return f.getNameAsString();
        return "";
    }

    private static String enclosingQualifiedName(TypeDeclaration<?> td, String pkg) {
        var names = new ArrayList<String>();
        Node cur = td;
        while (cur != null) {
            if (cur instanceof TypeDeclaration<?> t) names.addFirst(t.getNameAsString());
            cur = cur.getParentNode().orElse(null);
        }
        String joined = String.join(".", names);
        return pkg.isEmpty() ? joined : pkg + "." + joined;
    }

    private static String resolveImportOrLocal(String rawType, String pkg,
            Map<String, String> explicitImports, Set<String> wildcardImports) {
        if (rawType == null || rawType.isEmpty()) return "";
        String clean = stripGenerics(rawType);
        if (clean.contains(".")) {
            int dot = clean.indexOf('.');
            String outer = clean.substring(0, dot);
            if (explicitImports.containsKey(outer)) {
                return explicitImports.get(outer) + clean.substring(dot);
            }
            return clean;
        }
        String direct = explicitImports.get(clean);
        if (direct != null) return direct;
        for (String wc : wildcardImports) {
            if (wc.startsWith("org.springframework.") && Set.of(
                    "RestTemplate", "RestOperations", "OAuth2RestTemplate", "OAuth2RestOperations",
                    "RestClient", "WebClient", "DiscoveryClient", "LoadBalancerClient", "ServiceInstanceChooser",
                    "RabbitTemplate", "AmqpTemplate", "KafkaTemplate").contains(clean)) {
                return wc + clean;
            }
            if ((wc.equals("javax.ws.rs.client.") || wc.equals("jakarta.ws.rs.client."))
                    && Set.of("ClientBuilder", "Client", "WebTarget").contains(clean)) {
                return wc + clean;
            }
        }
        return pkg.isEmpty() ? clean : pkg + "." + clean;
    }

    private static String rootIdOf(Expression expr) {
        Expression cur = expr;
        while (cur != null) {
            if (cur instanceof NameExpr n) return n.getNameAsString();
            if (cur instanceof FieldAccessExpr f && f.getScope() instanceof ThisExpr) return f.getNameAsString();
            if (cur instanceof FieldAccessExpr f) {
                String full = f.toString();
                if (full.startsWith("org.springframework.") || full.startsWith("javax.ws.rs.client.")
                        || full.startsWith("jakarta.ws.rs.client.")) {
                    return full;
                }
                cur = f.getScope();
            } else if (cur instanceof MethodCallExpr mc) {
                cur = mc.getScope().orElse(null);
            } else if (cur instanceof ObjectCreationExpr oc) {
                return oc.getType().asString();
            } else {
                break;
            }
        }
        return "";
    }

    private static String stripGenerics(String type) {
        if (type == null) return "";
        int lt = type.indexOf('<');
        return (lt >= 0 ? type.substring(0, lt) : type).trim();
    }

    private static String simpleName(String raw) {
        if (raw == null) return "";
        int dot = raw.lastIndexOf('.');
        return dot >= 0 ? raw.substring(dot + 1) : raw;
    }

    private static boolean looksLikeTypeName(String s) {
        return s != null && !s.isEmpty() && Character.isUpperCase(s.charAt(0));
    }

    private static boolean isStringType(String typeName) {
        return "String".equals(typeName) || "java.lang.String".equals(typeName);
    }

    private static boolean isUriOrPathPrefix(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("lb://") || value.startsWith("/");
    }

    private static String dynamicOrPrimitiveSegment(Expression expr) {
        Expression cur = expr;
        while (cur instanceof EnclosedExpr enclosed) {
            cur = enclosed.getInner();
        }
        if (cur instanceof IntegerLiteralExpr || cur instanceof BooleanLiteralExpr) {
            return cur.toString();
        }
        if (cur instanceof LongLiteralExpr longLit) {
            return longLit.asNumber().toString();
        }
        if (cur instanceof CharLiteralExpr charLit) {
            return String.valueOf(charLit.asChar());
        }
        return "{param}";
    }

    private static Optional<String> bounded(String value) {
        return value.length() <= 16_384 ? Optional.of(value) : Optional.empty();
    }

    // -------------------------------------------------------------------------
    // WP2.1: Passive BeanFactory.getBean(...) / ApplicationContext.getBean(...) Constant Folding
    // -------------------------------------------------------------------------

    private static final Set<String> SPRING_BEAN_FACTORY_TYPES = Set.of(
            "org.springframework.beans.factory.BeanFactory",
            "org.springframework.beans.factory.ListableBeanFactory",
            "org.springframework.beans.factory.HierarchicalBeanFactory",
            "org.springframework.beans.factory.config.ConfigurableBeanFactory",
            "org.springframework.beans.factory.config.ConfigurableListableBeanFactory",
            "org.springframework.beans.factory.config.AutowireCapableBeanFactory",
            "org.springframework.beans.factory.support.DefaultListableBeanFactory",
            "org.springframework.context.ApplicationContext",
            "org.springframework.context.ConfigurableApplicationContext",
            "org.springframework.web.context.WebApplicationContext",
            "org.springframework.web.context.ConfigurableWebApplicationContext",
            "org.springframework.context.support.AbstractApplicationContext",
            "org.springframework.context.support.ClassPathXmlApplicationContext",
            "org.springframework.context.support.FileSystemXmlApplicationContext",
            "org.springframework.context.support.GenericApplicationContext",
            "org.springframework.context.annotation.AnnotationConfigApplicationContext");

    private static final Set<String> SPRING_BEAN_FACTORY_SIMPLE_NAMES = Set.of(
            "BeanFactory", "ListableBeanFactory", "HierarchicalBeanFactory",
            "ConfigurableBeanFactory", "ConfigurableListableBeanFactory", "AutowireCapableBeanFactory",
            "DefaultListableBeanFactory", "ApplicationContext", "ConfigurableApplicationContext",
            "WebApplicationContext", "ConfigurableWebApplicationContext", "AbstractApplicationContext",
            "ClassPathXmlApplicationContext", "FileSystemXmlApplicationContext",
            "GenericApplicationContext", "AnnotationConfigApplicationContext");

    /**
     * One constant-folded or dynamic {@code BeanFactory.getBean(...)} /
     * {@code ApplicationContext.getBean(...)} invocation conforming to the pinned
     * {@code "RESOLVES_BEAN"} / {@code "spring.bean-lookup"} contract.
     */
    record BeanLookupCall(
            MethodCallExpr astNode,
            String beanName,
            String beanType,
            boolean dynamic,
            String sourceFile,
            int line,
            String rawExpression) implements Comparable<BeanLookupCall> {
        BeanLookupCall {
            beanName = beanName == null ? "" : beanName.trim();
            beanType = beanType == null ? "" : beanType.trim();
            sourceFile = sourceFile == null ? "" : sourceFile.replace('\\', '/');
            rawExpression = rawExpression == null ? "" : rawExpression.trim();
        }

        @Override
        public int compareTo(BeanLookupCall other) {
            int cmp = sourceFile.compareTo(other.sourceFile);
            if (cmp != 0) return cmp;
            cmp = Integer.compare(line, other.line);
            if (cmp != 0) return cmp;
            cmp = beanName.compareTo(other.beanName);
            if (cmp != 0) return cmp;
            cmp = beanType.compareTo(other.beanType);
            if (cmp != 0) return cmp;
            cmp = Boolean.compare(dynamic, other.dynamic);
            return cmp != 0 ? cmp : rawExpression.compareTo(other.rawExpression);
        }
    }

    static boolean isSpringBeanFactoryType(String qualifiedOrSimpleType) {
        if (qualifiedOrSimpleType == null || qualifiedOrSimpleType.isBlank()) return false;
        String clean = stripGenerics(qualifiedOrSimpleType);
        return SPRING_BEAN_FACTORY_TYPES.contains(clean);
    }

    /**
     * Extracts and constant-folds {@code BeanFactory.getBean(...)} and
     * {@code ApplicationContext.getBean(...)} calls within {@code cu}.
     * Uses semantic constant folding when symbol resolution is active and syntax-scoped
     * constant folding (including {@link EnumConstantMap}) under {@code --build=off}.
     */
    static List<BeanLookupCall> extractBeanLookups(
            CompilationUnit cu,
            String docPath,
            EnumConstantMap enumMap) {
        if (cu == null) return List.of();
        String safePath = docPath == null ? "" : docPath.replace('\\', '/');
        EnumConstantMap activeEnums = enumMap == null ? EnumConstantMap.EMPTY : enumMap;
        String pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");

        Map<String, String> explicitImports = new HashMap<>();
        Set<String> wildcardImports = new LinkedHashSet<>();
        for (ImportDeclaration imp : cu.getImports()) {
            if (imp.isStatic()) continue;
            String q = imp.getNameAsString();
            if (imp.isAsterisk()) {
                wildcardImports.add(q + ".");
            } else {
                explicitImports.put(simpleName(q), q);
            }
        }

        var results = new ArrayList<BeanLookupCall>();
        for (TypeDeclaration<?> typeDecl : cu.findAll(TypeDeclaration.class)) {
            if (!(typeDecl instanceof ClassOrInterfaceDeclaration cls)) continue;
            Map<String, String> fieldTypes = new HashMap<>();
            Map<String, String> fieldConsts = new LinkedHashMap<>();

            var reassignedFields = new HashSet<String>();
            for (AssignExpr a : cls.findAll(AssignExpr.class)) {
                if (a.getTarget() instanceof NameExpr n) reassignedFields.add(n.getNameAsString());
                else if (a.getTarget() instanceof FieldAccessExpr f) reassignedFields.add(f.getNameAsString());
            }
            for (FieldDeclaration fd : cls.getFields()) {
                String rawType = stripGenerics(fd.getCommonType().asString());
                String resolvedType = resolveBeanFactoryOrType(rawType, pkg, explicitImports, wildcardImports);
                for (VariableDeclarator vd : fd.getVariables()) {
                    fieldTypes.put(vd.getNameAsString(), resolvedType);
                    if (isStringType(rawType)
                            && (fd.isFinal() || (fd.isPrivate() && !reassignedFields.contains(vd.getNameAsString())))
                            && vd.getInitializer().isPresent()) {
                        String folded = syntaxUri(vd.getInitializer().orElseThrow(), fieldConsts, activeEnums);
                        if (!folded.isEmpty()) {
                            fieldConsts.put(vd.getNameAsString(), folded);
                            fieldConsts.put(cls.getNameAsString() + "." + vd.getNameAsString(), folded);
                        }
                    }
                }
            }

            // Collect callables inside this type (excluding nested type bodies handled in their own iteration)
            var callables = new ArrayList<Node>();
            callables.addAll(cls.getMethods());
            callables.addAll(cls.getConstructors());
            for (BodyDeclaration<?> member : cls.getMembers()) {
                if (member instanceof InitializerDeclaration) callables.add(member);
            }

            for (Node callable : callables) {
                Map<String, String> varTypes = new HashMap<>(fieldTypes);
                Map<String, String> localConsts = new LinkedHashMap<>(fieldConsts);

                if (callable instanceof CallableDeclaration<?> cd) {
                    for (Parameter p : cd.getParameters()) {
                        String pName = p.getNameAsString();
                        localConsts.remove(pName);
                        String rawType = stripGenerics(p.getType().asString());
                        varTypes.put(pName, resolveBeanFactoryOrType(rawType, pkg, explicitImports, wildcardImports));
                    }
                }

                var reassignedLocals = new HashSet<String>();
                for (AssignExpr a : callable.findAll(AssignExpr.class)) {
                    if (a.getTarget() instanceof NameExpr n) reassignedLocals.add(n.getNameAsString());
                }
                for (VariableDeclarationExpr vde : callable.findAll(VariableDeclarationExpr.class)) {
                    String rawType = stripGenerics(vde.getCommonType().asString());
                    String resolvedType = resolveBeanFactoryOrType(rawType, pkg, explicitImports, wildcardImports);
                    for (VariableDeclarator vd : vde.getVariables()) {
                        String vName = vd.getNameAsString();
                        varTypes.put(vName, resolvedType);
                        localConsts.remove(vName);
                        if (!vde.isFinal() && reassignedLocals.contains(vName)) continue;
                        vd.getInitializer().ifPresent(init -> {
                            if (isStringType(rawType)) {
                                String folded = syntaxUri(init, localConsts, activeEnums);
                                if (!folded.isEmpty()) localConsts.put(vName, folded);
                            }
                        });
                    }
                }

                for (MethodCallExpr call : callable.findAll(MethodCallExpr.class)) {
                    if (!"getBean".equals(call.getNameAsString()) || call.getArguments().isEmpty()) continue;
                    // Avoid double-visiting calls inside nested classes
                    if (call.findAncestor(TypeDeclaration.class).map(t -> t != cls).orElse(false)) continue;
                    if (!isBeanFactoryReceiver(call, varTypes, pkg, explicitImports, wildcardImports)) continue;

                    BeanLookupCall folded = foldGetBeanCall(
                            call, safePath, localConsts, activeEnums, pkg, explicitImports, wildcardImports);
                    if (folded != null) {
                        results.add(folded);
                    }
                }
            }
        }
        results.sort(Comparator.naturalOrder());
        return List.copyOf(results);
    }

    private static boolean isBeanFactoryReceiver(
            MethodCallExpr call,
            Map<String, String> varTypes,
            String pkg,
            Map<String, String> explicitImports,
            Set<String> wildcardImports) {
        // 1. Prefer resolved declaring type when JavaSymbolSolver is available
        try {
            var resolved = call.resolve();
            String declaring = resolved.declaringType().getQualifiedName();
            if (isSpringBeanFactoryType(declaring)) return true;
            for (var anc : resolved.declaringType().getAllAncestors()) {
                if (isSpringBeanFactoryType(anc.getQualifiedName())) return true;
            }
            // Resolved to a non-Spring class (e.g. custom cache/service with a getBean method)
            return false;
        } catch (RuntimeException | LinkageError ignored) {
            // Fall through to passive syntax + import verification (--build=off)
        }
        String root = rootIdOf(call.getScope().orElse(null));
        if (root.isEmpty()) return false;
        String varType = varTypes.getOrDefault(root, "");
        if (isSpringBeanFactoryType(varType)) return true;
        String resolvedRoot = resolveBeanFactoryOrType(root, pkg, explicitImports, wildcardImports);
        return isSpringBeanFactoryType(resolvedRoot);
    }

    private static BeanLookupCall foldGetBeanCall(
            MethodCallExpr call,
            String sourceFile,
            Map<String, String> localConsts,
            EnumConstantMap enumMap,
            String pkg,
            Map<String, String> explicitImports,
            Set<String> wildcardImports) {
        int line = call.getRange().map(r -> r.begin.line).orElse(1);
        Expression arg0 = call.getArgument(0);
        String rawExpr = call.toString();

        // Case A: getBean(Class<T> requiredType [, Object... args])
        if (arg0 instanceof ClassExpr classExpr) {
            String typeName = resolveClassExprFqn(classExpr, pkg, explicitImports, wildcardImports);
            return new BeanLookupCall(call, "", typeName, typeName.isEmpty(), sourceFile, line, rawExpr);
        }

        // Case B: getBean(String name) or getBean(String name, Class<T> requiredType) or getBean(String name, Object...)
        String foldedName = foldStringConstantStrict(arg0, localConsts, enumMap);
        String beanType = "";
        boolean typeArgDynamic = false;
        if (call.getArguments().size() == 2) {
            Expression arg1 = call.getArgument(1);
            if (arg1 instanceof ClassExpr ce) {
                beanType = resolveClassExprFqn(ce, pkg, explicitImports, wildcardImports);
            } else if (looksLikeClassTypedExpr(arg1)) {
                typeArgDynamic = true;
            }
        }
        boolean dynamic = foldedName.isEmpty() || typeArgDynamic;
        return new BeanLookupCall(call, foldedName, beanType, dynamic, sourceFile, line, rawExpr);
    }

    private static String foldStringConstantStrict(
            Expression expr,
            Map<String, String> localConsts,
            EnumConstantMap enumMap) {
        String semantic = uri(expr, enumMap).trim();
        if (!semantic.isEmpty()) {
            return semantic;
        }
        // Strict syntax folding: reject partial "{param}" placeholders so dynamic keys stay dynamic=true
        String syntax = syntaxUri(expr, localConsts, enumMap).trim();
        if (syntax.isEmpty() || syntax.contains("{param}")) {
            return "";
        }
        return syntax;
    }

    private static String resolveClassExprFqn(
            ClassExpr classExpr,
            String pkg,
            Map<String, String> explicitImports,
            Set<String> wildcardImports) {
        try {
            var resolved = classExpr.getType().resolve();
            if (resolved.isReferenceType()) {
                return resolved.asReferenceType().getQualifiedName();
            }
        } catch (RuntimeException | LinkageError ignored) {
        }
        String raw = stripGenerics(classExpr.getType().asString());
        return resolveBeanFactoryOrType(raw, pkg, explicitImports, wildcardImports);
    }

    private static boolean looksLikeClassTypedExpr(Expression expr) {
        try {
            var resolved = expr.calculateResolvedType();
            if (resolved.isReferenceType()
                    && "java.lang.Class".equals(resolved.asReferenceType().getQualifiedName())) {
                return true;
            }
        } catch (RuntimeException | LinkageError ignored) {
        }
        String s = expr.toString();
        return s.endsWith("Class") || s.endsWith("Type") || s.contains("Class.forName");
    }

    private static String resolveBeanFactoryOrType(
            String rawType,
            String pkg,
            Map<String, String> explicitImports,
            Set<String> wildcardImports) {
        if (rawType == null || rawType.isEmpty()) return "";
        String clean = stripGenerics(rawType);
        if (clean.contains(".")) {
            int dot = clean.indexOf('.');
            String outer = clean.substring(0, dot);
            if (explicitImports.containsKey(outer)) {
                return explicitImports.get(outer) + clean.substring(dot);
            }
            return clean;
        }
        String direct = explicitImports.get(clean);
        if (direct != null) return direct;
        for (String wc : wildcardImports) {
            if (wc.startsWith("org.springframework.") && SPRING_BEAN_FACTORY_SIMPLE_NAMES.contains(clean)) {
                String candidate = wc + clean;
                if (SPRING_BEAN_FACTORY_TYPES.contains(candidate)) return candidate;
            }
        }
        return pkg.isEmpty() ? clean : pkg + "." + clean;
    }
}
