package com.evolution.analysis.treesitter;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.identity.SourceDocumentIdentity;
import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.SourceSpan;
import com.evolution.analysis.evidence.AffectedOutput;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.evidence.EvidenceContext;
import com.evolution.analysis.evidence.EvidenceRequirement;
import com.evolution.analysis.evidence.EvidenceSubject;
import com.evolution.analysis.evidence.ProviderObservationReference;
import com.evolution.analysis.polyglot.SourceCoordinates;
import com.evolution.analysis.treesitter.PolyglotScopeGraphResolver.EdgeKind;
import com.evolution.analysis.treesitter.PolyglotScopeGraphResolver.ResolvedEdge;
import com.evolution.analysis.treesitter.PolyglotScopeGraphResolver.ScopeGraph;
import com.evolution.analysis.treesitter.PolyglotScopeGraphResolver.SymbolDef;
import com.evolution.analysis.treesitter.PolyglotScopeGraphResolver.SymbolKind;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.treesitter.TSNode;

/**
 * Passive ({@code --build=off}, I1) SALT-style bidirectional type hint propagator {@code (F, B_s, B_w)}
 * for Python and TypeScript/TSX over Tree-sitter ASTs and {@link PolyglotScopeGraphResolver} (WP1.5).
 *
 * <p>Implements the conservative SALT combination equation (Sec. III-A):
 * <pre>
 *   T(s) = B_s(s) U {t}   if B_w(s) == {t} and F(s) == {Unknown}
 *        = B_s(s) U F(s)  otherwise
 * </pre>
 *
 * <p><strong>Anti-Over-Narrowing Safety Guard (I4):</strong> Exported/public APIs (Python symbols not
 * starting with {@code _} and TypeScript {@code export} functions/classes) are NEVER locked to
 * {@link TrustTier#COMPILER} from local caller subsets. All propagated hints carry
 * {@link TrustTier#SOURCE_SYNTAX} and provenance {@value #PROVENANCE_SOURCE_SYNTAX_HINT}, with explicit
 * open-world extensibility metadata on public APIs. Whenever {@value #UNKNOWN_TYPE} remains in
 * {@code T(s)}, an explicit {@link CapabilityGapRecord} with reason code
 * {@value #GAP_REASON_UNKNOWN} is emitted (I3).
 */
public final class BidirectionalTypeHintPropagator {

    public static final String UNKNOWN_TYPE = "Unknown";
    public static final String PROVENANCE_SOURCE_SYNTAX_HINT = "SOURCE_SYNTAX_HINT";
    public static final String GAP_REASON_UNKNOWN = "BIDIRECTIONAL_TYPE_UNKNOWN";
    public static final TrustTier HINT_TRUST_TIER = TrustTier.SOURCE_SYNTAX;
    public static final int MAX_FIXED_POINT_ITERATIONS = 16;

    public static final VersionedIdentifier PROVIDER =
            new VersionedIdentifier("source.salt-bidirectional-type-hints", "wp2.4-v1");
    public static final VersionedIdentifier GAP_CATALOG =
            new VersionedIdentifier("evidence.salt-bidirectional-type-hints", "wp2.4-v1");

    private static final RepositoryIdentity DEFAULT_REPO =
            RepositoryIdentity.fromCanonicalCoordinate("urn:evo:salt-type-hints");
    private static final Set<String> SUPPORTED_LANGUAGES = Set.of("python", "typescript", "tsx", "javascript");
    private static final Set<String> UNTYPED_PLACEHOLDERS = Set.of(
            "any", "Any", "unknown", "object", "Object", "typing.Any");
    private static final Pattern STRING_TOKEN = Pattern.compile("[\"']([^\"']+)[\"']");

    private final TreeSitterSourceFrontend frontend;

    public BidirectionalTypeHintPropagator() {
        this(new TreeSitterSourceFrontend());
    }

    public BidirectionalTypeHintPropagator(TreeSitterSourceFrontend frontend) {
        this.frontend = Objects.requireNonNull(frontend, "frontend must not be null");
    }

    // -------------------------------------------------------------------------
    // Public Domain Model (immutable, deterministic, Comparable)
    // -------------------------------------------------------------------------

    public enum SlotKind {
        PARAMETER,
        RETURN,
        LOCAL_VAR
    }

    public enum TypeStatus {
        EXPLICIT_ANNOTATION,
        INFERRED_OPEN_HINT,
        INFERRED_LOCAL_HINT,
        PARTIAL_OR_UNKNOWN
    }

    public record SymbolTypeHint(
            String symbolId,
            String filePath,
            String language,
            String callableQualifiedName,
            String slotName,
            SlotKind slotKind,
            int parameterIndex,
            boolean exportedPublicApi,
            boolean explicitlyAnnotated,
            boolean overNarrowingGuarded,
            boolean openWorldExtensible,
            Set<String> forwardTypes,
            Set<String> strongBackwardTypes,
            Set<String> weakBackwardTypes,
            Set<String> inferredTypes,
            TypeStatus status,
            TrustTier trustTier,
            String provenance,
            SourceCoordinates.Range span) implements Comparable<SymbolTypeHint> {

        public SymbolTypeHint {
            symbolId = Objects.requireNonNull(symbolId, "symbolId");
            filePath = normalizePath(Objects.requireNonNull(filePath, "filePath"));
            language = Objects.requireNonNull(language, "language");
            callableQualifiedName = Objects.requireNonNull(callableQualifiedName, "callableQualifiedName");
            slotName = Objects.requireNonNull(slotName, "slotName");
            slotKind = Objects.requireNonNull(slotKind, "slotKind");
            forwardTypes = Collections.unmodifiableSet(new TreeSet<>(Objects.requireNonNull(forwardTypes)));
            strongBackwardTypes = Collections.unmodifiableSet(new TreeSet<>(Objects.requireNonNull(strongBackwardTypes)));
            weakBackwardTypes = Collections.unmodifiableSet(new TreeSet<>(Objects.requireNonNull(weakBackwardTypes)));
            inferredTypes = Collections.unmodifiableSet(new TreeSet<>(Objects.requireNonNull(inferredTypes)));
            status = Objects.requireNonNull(status, "status");
            trustTier = Objects.requireNonNull(trustTier, "trustTier");
            if (trustTier == TrustTier.COMPILER) {
                throw new IllegalArgumentException("Passive bidirectional type hints must never claim TrustTier.COMPILER (I4)");
            }
            provenance = Objects.requireNonNull(provenance, "provenance");
            span = Objects.requireNonNull(span, "span");
        }

        public boolean hasUnknown() {
            return inferredTypes.contains(UNKNOWN_TYPE);
        }

        @Override
        public int compareTo(SymbolTypeHint other) {
            int cmp = filePath.compareTo(other.filePath);
            if (cmp != 0) return cmp;
            cmp = callableQualifiedName.compareTo(other.callableQualifiedName);
            if (cmp != 0) return cmp;
            cmp = slotKind.compareTo(other.slotKind);
            if (cmp != 0) return cmp;
            cmp = Integer.compare(parameterIndex, other.parameterIndex);
            if (cmp != 0) return cmp;
            return symbolId.compareTo(other.symbolId);
        }

        public Map<String, Object> toMap() {
            var map = new TreeMap<String, Object>();
            map.put("callableQualifiedName", callableQualifiedName);
            map.put("explicitlyAnnotated", explicitlyAnnotated);
            map.put("exportedPublicApi", exportedPublicApi);
            map.put("filePath", filePath);
            map.put("forwardTypes", List.copyOf(forwardTypes));
            map.put("inferredTypes", List.copyOf(inferredTypes));
            map.put("language", language);
            map.put("openWorldExtensible", openWorldExtensible);
            map.put("overNarrowingGuarded", overNarrowingGuarded);
            map.put("parameterIndex", BigDecimal.valueOf(parameterIndex));
            map.put("provenance", provenance);
            map.put("slotKind", slotKind.name());
            map.put("slotName", slotName);
            map.put("span", rangeToMap(span));
            map.put("status", status.name());
            map.put("strongBackwardTypes", List.copyOf(strongBackwardTypes));
            map.put("symbolId", symbolId);
            map.put("trustTier", trustTier.name());
            map.put("weakBackwardTypes", List.copyOf(weakBackwardTypes));
            return map;
        }
    }

    public record ResolvedHintCallEdge(
            String sourcePath,
            String callerSymbol,
            String receiverName,
            String receiverTypeHint,
            String targetPath,
            String targetSymbol,
            TrustTier trustTier,
            String provenance,
            SourceCoordinates.Range sourceSpan) implements Comparable<ResolvedHintCallEdge> {

        public ResolvedHintCallEdge {
            sourcePath = normalizePath(Objects.requireNonNull(sourcePath, "sourcePath"));
            callerSymbol = Objects.requireNonNull(callerSymbol, "callerSymbol");
            receiverName = Objects.requireNonNull(receiverName, "receiverName");
            receiverTypeHint = Objects.requireNonNull(receiverTypeHint, "receiverTypeHint");
            targetPath = normalizePath(Objects.requireNonNull(targetPath, "targetPath"));
            targetSymbol = Objects.requireNonNull(targetSymbol, "targetSymbol");
            trustTier = Objects.requireNonNull(trustTier, "trustTier");
            if (trustTier == TrustTier.COMPILER) {
                throw new IllegalArgumentException("Passive type-hint call edges must never claim TrustTier.COMPILER (I4)");
            }
            provenance = Objects.requireNonNull(provenance, "provenance");
            sourceSpan = Objects.requireNonNull(sourceSpan, "sourceSpan");
        }

        @Override
        public int compareTo(ResolvedHintCallEdge other) {
            int cmp = sourcePath.compareTo(other.sourcePath);
            if (cmp != 0) return cmp;
            cmp = Integer.compare(sourceSpan.start(), other.sourceSpan.start());
            if (cmp != 0) return cmp;
            cmp = callerSymbol.compareTo(other.callerSymbol);
            if (cmp != 0) return cmp;
            cmp = targetPath.compareTo(other.targetPath);
            if (cmp != 0) return cmp;
            cmp = targetSymbol.compareTo(other.targetSymbol);
            if (cmp != 0) return cmp;
            return receiverTypeHint.compareTo(other.receiverTypeHint);
        }

        public Map<String, Object> toMap() {
            var map = new TreeMap<String, Object>();
            map.put("callerSymbol", callerSymbol);
            map.put("provenance", provenance);
            map.put("receiverName", receiverName);
            map.put("receiverTypeHint", receiverTypeHint);
            map.put("sourcePath", sourcePath);
            map.put("sourceSpan", rangeToMap(sourceSpan));
            map.put("targetPath", targetPath);
            map.put("targetSymbol", targetSymbol);
            map.put("trustTier", trustTier.name());
            return map;
        }
    }

    public record Result(
            List<SymbolTypeHint> hints,
            List<ResolvedHintCallEdge> recoveredCallEdges,
            List<CapabilityGapRecord> capabilityGaps,
            int fixedPointIterations,
            ContentDigest canonicalDigest) {

        public Result {
            var sortedHints = new ArrayList<>(Objects.requireNonNull(hints, "hints"));
            Collections.sort(sortedHints);
            hints = List.copyOf(sortedHints);
            var sortedEdges = new ArrayList<>(Objects.requireNonNull(recoveredCallEdges, "recoveredCallEdges"));
            Collections.sort(sortedEdges);
            recoveredCallEdges = List.copyOf(sortedEdges);
            var sortedGaps = new ArrayList<>(Objects.requireNonNull(capabilityGaps, "capabilityGaps"));
            Collections.sort(sortedGaps);
            capabilityGaps = List.copyOf(sortedGaps);
            canonicalDigest = Objects.requireNonNull(canonicalDigest, "canonicalDigest");
        }

        public Optional<SymbolTypeHint> findHint(
                String filePath, String callableQualifiedName, SlotKind slotKind, String slotName) {
            String norm = normalizePath(filePath);
            return hints.stream()
                    .filter(h -> h.filePath().equals(norm)
                            && h.callableQualifiedName().equals(callableQualifiedName)
                            && h.slotKind() == slotKind
                            && h.slotName().equals(slotName))
                    .findFirst();
        }

        public Optional<SymbolTypeHint> findBySymbolId(String symbolId) {
            return hints.stream().filter(h -> h.symbolId().equals(symbolId)).findFirst();
        }

        public List<SymbolTypeHint> hintsInFile(String filePath) {
            String norm = normalizePath(filePath);
            return hints.stream().filter(h -> h.filePath().equals(norm)).toList();
        }

        public long unknownCount() {
            return hints.stream().filter(SymbolTypeHint::hasUnknown).count();
        }

        public long publicApiGuardedCount() {
            return hints.stream().filter(SymbolTypeHint::overNarrowingGuarded).count();
        }

        public Map<String, Object> toMap() {
            var map = new TreeMap<String, Object>();
            map.put("capabilityGaps", capabilityGaps.stream().map(g -> {
                var gm = new TreeMap<String, Object>();
                gm.put("gapIdentity", g.gapIdentity().value());
                gm.put("limitations", g.limitations());
                gm.put("mechanismCategory", g.mechanismCategory());
                gm.put("reasonCode", g.reasonCode());
                gm.put("subject", g.subject().kind().name() + ":" + g.subject().identity());
                return gm;
            }).toList());
            map.put("fixedPointIterations", BigDecimal.valueOf(fixedPointIterations));
            map.put("hints", hints.stream().map(SymbolTypeHint::toMap).toList());
            map.put("provenance", PROVENANCE_SOURCE_SYNTAX_HINT);
            map.put("recoveredCallEdges", recoveredCallEdges.stream().map(ResolvedHintCallEdge::toMap).toList());
            map.put("trustTier", HINT_TRUST_TIER.name());
            return map;
        }
    }

    // -------------------------------------------------------------------------
    // Exact SALT Conservative Combination Equation (Sec. III-A)
    // -------------------------------------------------------------------------

    /**
     * Conservative SALT type combination rule:
     * {@code T(s) = B_s(s) U {t}} if {@code B_w(s) == {t}} and {@code F(s) == {Unknown}},
     * else {@code B_s(s) U F(s)}.
     */
    public static Set<String> combineSaltTypes(
            Set<String> forwardF,
            Set<String> strongBs,
            Set<String> weakBw) {
        var f = new TreeSet<String>();
        if (forwardF != null) {
            for (String t : forwardF) {
                if (t != null && !t.isBlank()) f.add(t);
            }
        }
        var bs = new TreeSet<String>();
        if (strongBs != null) {
            for (String t : strongBs) {
                if (t != null && !t.isBlank() && !UNKNOWN_TYPE.equals(t)) bs.add(t);
            }
        }
        var bwConcrete = new TreeSet<String>();
        if (weakBw != null) {
            for (String t : weakBw) {
                if (t != null && !t.isBlank() && !UNKNOWN_TYPE.equals(t)) bwConcrete.add(t);
            }
        }
        var out = new TreeSet<String>(bs);
        if (bwConcrete.size() == 1 && f.size() == 1 && f.contains(UNKNOWN_TYPE)) {
            out.addAll(bwConcrete);
        } else {
            out.addAll(f);
        }
        return Collections.unmodifiableSet(out);
    }

    // -------------------------------------------------------------------------
    // Public Entry Points (--build=off, deterministic)
    // -------------------------------------------------------------------------

    public Result propagate(Map<String, byte[]> filesByPath, BooleanSupplier cancelled) throws IOException {
        return propagate(filesByPath, null, null, cancelled);
    }

    public Result propagate(
            Map<String, byte[]> filesByPath,
            EvidenceContext context,
            BooleanSupplier cancelled) throws IOException {
        return propagate(filesByPath, null, context, cancelled);
    }

    public Result propagate(
            Map<String, byte[]> filesByPath,
            ScopeGraph precomputedScopeGraph,
            EvidenceContext context,
            BooleanSupplier cancelled) throws IOException {
        BooleanSupplier cancel = cancelled == null ? () -> false : cancelled;
        checkCancelled(cancel);

        var sortedFiles = new TreeMap<String, byte[]>();
        if (filesByPath != null) {
            for (var entry : filesByPath.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    sortedFiles.put(normalizePath(entry.getKey()), entry.getValue());
                }
            }
        }

        EvidenceContext effectiveContext = context != null ? context : deriveDefaultContext(sortedFiles);
        ScopeGraph scopeGraph = precomputedScopeGraph != null
                ? precomputedScopeGraph
                : new PolyglotScopeGraphResolver(frontend).resolve(sortedFiles, effectiveContext, cancel);

        // Phase 1: Parse Python & TypeScript/TSX ASTs reusing frontend pooled parsers
        var modules = new TreeMap<String, ModuleModel>();
        for (var entry : sortedFiles.entrySet()) {
            checkCancelled(cancel);
            String path = entry.getKey();
            String lang = detectLanguage(path);
            if (lang == null || !SUPPORTED_LANGUAGES.contains(lang)) continue;
            byte[] bytes = entry.getValue();
            ContentDigest digest = ContentDigest.sha256(bytes);
            ModuleModel mod = new ModuleModel(path, lang, digest);
            frontend.parseWithTreeHook(lang, bytes, null,
                    (root, utf8, pos) -> extractModule(mod, root, utf8, pos, cancel),
                    cancel, false);
            finalizeModuleExports(mod);
            modules.put(path, mod);
        }

        // Index ScopeGraph cross-file edges and declarations for O(1) lookup
        var callEdgeBySite = new HashMap<String, List<ResolvedEdge>>();
        var importedSymbolsByFile = new HashMap<String, Map<String, List<ResolvedEdge>>>();
        for (ResolvedEdge edge : scopeGraph.edges()) {
            if (edge.kind() == EdgeKind.CALLS_SYMBOL) {
                String key = edge.sourcePath() + "|" + edge.sourceSpan().start() + ":" + edge.sourceSpan().end();
                callEdgeBySite.computeIfAbsent(key, k -> new ArrayList<>()).add(edge);
            } else if (edge.kind() == EdgeKind.IMPORTS) {
                importedSymbolsByFile
                        .computeIfAbsent(edge.sourcePath(), k -> new TreeMap<>())
                        .computeIfAbsent(edge.targetSymbol(), k -> new ArrayList<>())
                        .add(edge);
            }
        }

        // Index callables by (filePath, qualifiedName), (filePath, simpleName), and classes/methods workspace-wide
        var callablesById = new TreeMap<String, CallableModel>();
        var callablesByFileAndQName = new HashMap<String, CallableModel>();
        var callablesByFileAndName = new HashMap<String, List<CallableModel>>();
        var methodsByLangAndClass = new TreeMap<String, Map<String, List<CallableModel>>>();
        var methodsByLangAndName = new TreeMap<String, List<CallableModel>>();
        var classesByLangAndName = new TreeMap<String, Set<String>>();
        var slotsById = new TreeMap<String, SlotState>();

        for (ModuleModel mod : modules.values()) {
            String langGroup = languageGroup(mod.language);
            for (String cls : mod.declaredClasses) {
                classesByLangAndName.computeIfAbsent(langGroup + "|" + cls, k -> new TreeSet<>()).add(mod.path);
            }
            for (CallableModel fn : mod.callables) {
                callablesById.put(fn.callableId, fn);
                callablesByFileAndQName.put(mod.path + "::" + fn.qualifiedName, fn);
                callablesByFileAndName.computeIfAbsent(mod.path + "::" + fn.simpleName, k -> new ArrayList<>()).add(fn);
                if (!fn.enclosingType.isEmpty()) {
                    methodsByLangAndClass
                            .computeIfAbsent(langGroup + "|" + fn.enclosingType, k -> new TreeMap<>())
                            .computeIfAbsent(fn.simpleName, k -> new ArrayList<>())
                            .add(fn);
                    methodsByLangAndName
                            .computeIfAbsent(langGroup + "|" + fn.simpleName, k -> new ArrayList<>())
                            .add(fn);
                }
                for (SlotState slot : fn.slots()) {
                    slotsById.put(slot.symbolId, slot);
                }
            }
        }

        // Phase 2: Deterministic Fixed-Point Propagation over (F, B_s, B_w)
        // Recompute nextF and nextBw cleanly from the previous iteration's T(s) so stale Unknowns
        // from early iterations never poison later converged iterations (order-independent fixed point, I2).
        int iterations = 0;
        var recoveredEdges = new TreeSet<ResolvedHintCallEdge>();
        for (SlotState slot : slotsById.values()) {
            slot.currentT.clear();
            slot.currentT.addAll(slot.computeCurrentT());
        }
        for (int iter = 1; iter <= MAX_FIXED_POINT_ITERATIONS; iter++) {
            checkCancelled(cancel);
            iterations = iter;
            var nextF = new HashMap<String, TreeSet<String>>(slotsById.size() * 2);
            var nextBw = new HashMap<String, TreeSet<String>>(slotsById.size() * 2);
            for (String sid : slotsById.keySet()) {
                nextF.put(sid, new TreeSet<>());
                nextBw.put(sid, new TreeSet<>());
            }

            // Propagate assignments, returns, and calls deterministically using currentT
            for (ModuleModel mod : modules.values()) {
                String langGroup = languageGroup(mod.language);
                for (CallableModel fn : mod.callables) {
                    // B1. Local variable assignments: var = expr
                    for (AssignStmt assign : fn.assignments) {
                        SlotState lhsSlot = fn.findSlot(assign.varName);
                        if (lhsSlot == null) continue;
                        Set<String> rhsTypes = evaluateExpr(
                                mod, fn, assign.expr, callEdgeBySite, importedSymbolsByFile,
                                callablesByFileAndQName, callablesByFileAndName, methodsByLangAndClass,
                                classesByLangAndName, scopeGraph, langGroup);
                        if (!rhsTypes.isEmpty()) {
                            nextF.get(lhsSlot.symbolId).addAll(rhsTypes);
                        } else if (!lhsSlot.explicitlyAnnotated && lhsSlot.strongBs.isEmpty()) {
                            nextF.get(lhsSlot.symbolId).add(UNKNOWN_TYPE);
                        }
                    }

                    // B2. Return statements: return expr -> F(fn#return), and B_w(exprVar) if fn#return is concrete
                    if (fn.returnSlot != null) {
                        for (ExprRef retExpr : fn.returnExprs) {
                            Set<String> retTypes = evaluateExpr(
                                    mod, fn, retExpr, callEdgeBySite, importedSymbolsByFile,
                                    callablesByFileAndQName, callablesByFileAndName, methodsByLangAndClass,
                                    classesByLangAndName, scopeGraph, langGroup);
                            if (!retTypes.isEmpty()) {
                                nextF.get(fn.returnSlot.symbolId).addAll(retTypes);
                            } else if (!fn.returnSlot.explicitlyAnnotated) {
                                nextF.get(fn.returnSlot.symbolId).add(UNKNOWN_TYPE);
                            }
                            if (retExpr.kind == ExprKind.IDENTIFIER && fn.returnSlot.explicitlyAnnotated) {
                                SlotState retVar = fn.findSlot(retExpr.text);
                                if (retVar != null) {
                                    for (String t : fn.returnSlot.strongBs) {
                                        if (!UNKNOWN_TYPE.equals(t)) nextBw.get(retVar.symbolId).add(t);
                                    }
                                }
                            }
                        }
                    }

                    // B3. Call sites: forward arg -> F(callee.param_i), backward callee.param_i -> B_w(argVar)
                    for (CallSiteStmt call : fn.calls) {
                        List<CallableModel> targets = resolveCallTargets(
                                mod, fn, call, callEdgeBySite, importedSymbolsByFile,
                                callablesByFileAndQName, callablesByFileAndName, methodsByLangAndClass,
                                scopeGraph, langGroup, recoveredEdges);
                        if (targets.size() == 1) {
                            CallableModel callee = targets.getFirst();
                            for (int i = 0; i < call.args.size(); i++) {
                                ArgRef arg = call.args.get(i);
                                SlotState paramSlot = callee.resolveParameter(arg.keywordName, i);
                                if (paramSlot == null) continue;

                                // Forward propagation: caller arg types -> F(callee.param)
                                Set<String> argTypes = evaluateExpr(
                                        mod, fn, arg.expr, callEdgeBySite, importedSymbolsByFile,
                                        callablesByFileAndQName, callablesByFileAndName, methodsByLangAndClass,
                                        classesByLangAndName, scopeGraph, langGroup);
                                if (!argTypes.isEmpty()) {
                                    nextF.get(paramSlot.symbolId).addAll(argTypes);
                                } else {
                                    nextF.get(paramSlot.symbolId).add(UNKNOWN_TYPE);
                                }

                                // Backward weak propagation: callee param expectations -> B_w(argVar)
                                Set<String> expectedTypes = concreteTypesOf(paramSlot);
                                if (!expectedTypes.isEmpty()) {
                                    if (arg.expr.kind == ExprKind.IDENTIFIER) {
                                        SlotState argSlot = fn.findSlot(arg.expr.text);
                                        if (argSlot != null) {
                                            nextBw.get(argSlot.symbolId).addAll(expectedTypes);
                                        }
                                    } else if (arg.expr.kind == ExprKind.CALL && arg.expr.nestedCall != null) {
                                        List<CallableModel> innerTargets = resolveCallTargets(
                                                mod, fn, arg.expr.nestedCall, callEdgeBySite, importedSymbolsByFile,
                                                callablesByFileAndQName, callablesByFileAndName, methodsByLangAndClass,
                                                scopeGraph, langGroup, recoveredEdges);
                                        if (innerTargets.size() == 1 && innerTargets.getFirst().returnSlot != null) {
                                            SlotState innerRet = innerTargets.getFirst().returnSlot;
                                            nextBw.get(innerRet.symbolId).addAll(expectedTypes);
                                        }
                                    }
                                }
                            }
                        } else if (!call.receiver.isEmpty() && !call.methodName.isEmpty()) {
                            // Weak backward receiver expectation when receiver is an untyped local/param
                            // and a unique class in scope defines the called method.
                            SlotState recvSlot = fn.findSlot(call.receiver);
                            if (recvSlot != null && concreteTypesOf(recvSlot).isEmpty()) {
                                List<CallableModel> methodCands = methodsByLangAndName.getOrDefault(
                                        langGroup + "|" + call.methodName, List.of());
                                var ownerTypes = new TreeSet<String>();
                                for (CallableModel mc : methodCands) {
                                    if (!mc.enclosingType.isEmpty()) ownerTypes.add(mc.enclosingType);
                                }
                                if (ownerTypes.size() == 1) {
                                    nextBw.get(recvSlot.symbolId).add(ownerTypes.first());
                                }
                            }
                        }
                    }
                }
            }

            // Commit nextF, nextBw, and recompute T(s) cleanly; check convergence
            boolean changed = false;
            for (SlotState slot : slotsById.values()) {
                TreeSet<String> newF = nextF.get(slot.symbolId);
                TreeSet<String> newBw = nextBw.get(slot.symbolId);
                if (!newF.equals(slot.forwardF)) {
                    slot.forwardF.clear();
                    slot.forwardF.addAll(newF);
                    changed = true;
                }
                if (!newBw.equals(slot.weakBw)) {
                    slot.weakBw.clear();
                    slot.weakBw.addAll(newBw);
                    changed = true;
                }
                Set<String> nextT = slot.computeCurrentT();
                if (!nextT.equals(slot.currentT)) {
                    slot.currentT.clear();
                    slot.currentT.addAll(nextT);
                    changed = true;
                }
            }
            if (!changed) break;
        }

        // Final edge recovery pass using converged T(s)
        for (ModuleModel mod : modules.values()) {
            String langGroup = languageGroup(mod.language);
            for (CallableModel fn : mod.callables) {
                for (CallSiteStmt call : fn.calls) {
                    resolveCallTargets(
                            mod, fn, call, callEdgeBySite, importedSymbolsByFile,
                            callablesByFileAndQName, callablesByFileAndName, methodsByLangAndClass,
                            scopeGraph, langGroup, recoveredEdges);
                }
            }
        }

        // Phase 3: Materialize immutable SymbolTypeHints + Anti-Over-Narrowing Guard + CapabilityGaps (I3/I4)
        var outHints = new ArrayList<SymbolTypeHint>(slotsById.size());
        var outGaps = new TreeSet<CapabilityGapRecord>();

        for (SlotState slot : slotsById.values()) {
            Set<String> effectiveF = slot.effectiveForwardF();
            Set<String> finalT = combineSaltTypes(effectiveF, slot.strongBs, slot.weakBw);
            boolean hasUnknown = finalT.contains(UNKNOWN_TYPE);
            boolean publicApi = slot.exportedPublicApi && slot.slotKind != SlotKind.LOCAL_VAR;
            // Anti-Over-Narrowing Guard (I4): never lock public API param/return types from local callers
            boolean overNarrowingGuarded = publicApi && !slot.explicitlyAnnotated;
            boolean openWorldExtensible = publicApi && !slot.explicitlyAnnotated;

            TypeStatus status;
            if (slot.explicitlyAnnotated && !hasUnknown) {
                status = TypeStatus.EXPLICIT_ANNOTATION;
            } else if (hasUnknown) {
                status = TypeStatus.PARTIAL_OR_UNKNOWN;
            } else if (overNarrowingGuarded) {
                status = TypeStatus.INFERRED_OPEN_HINT;
            } else {
                status = TypeStatus.INFERRED_LOCAL_HINT;
            }

            SymbolTypeHint hint = new SymbolTypeHint(
                    slot.symbolId,
                    slot.filePath,
                    slot.language,
                    slot.callableQualifiedName,
                    slot.slotName,
                    slot.slotKind,
                    slot.parameterIndex,
                    publicApi,
                    slot.explicitlyAnnotated,
                    overNarrowingGuarded,
                    openWorldExtensible,
                    effectiveF,
                    slot.strongBs,
                    slot.weakBw,
                    finalT,
                    status,
                    HINT_TRUST_TIER,
                    PROVENANCE_SOURCE_SYNTAX_HINT,
                    slot.span);
            outHints.add(hint);

            if (hasUnknown) {
                ModuleModel mod = modules.get(slot.filePath);
                ContentDigest docDigest = mod != null ? mod.contentDigest : ContentDigest.sha256Utf8(slot.filePath);
                outGaps.add(buildUnknownTypeGap(hint, docDigest, effectiveContext));
            }
        }

        var tempResult = new Result(
                outHints,
                List.copyOf(recoveredEdges),
                List.copyOf(outGaps),
                iterations,
                ContentDigest.sha256Utf8("pending"));
        ContentDigest digest = CanonicalJson.sha256(tempResult.toMap());
        return new Result(
                tempResult.hints(),
                tempResult.recoveredCallEdges(),
                tempResult.capabilityGaps(),
                iterations,
                digest);
    }

    // -------------------------------------------------------------------------
    // Internal Models & Fixed-Point Helpers
    // -------------------------------------------------------------------------

    private enum ExprKind {
        LITERAL,
        CONSTRUCTOR,
        IDENTIFIER,
        CALL,
        UNKNOWN
    }

    private record ExprRef(
            ExprKind kind,
            String text,
            String literalOrCtorType,
            CallSiteStmt nestedCall) {
        static ExprRef literal(String type) { return new ExprRef(ExprKind.LITERAL, type, type, null); }
        static ExprRef ctor(String type) { return new ExprRef(ExprKind.CONSTRUCTOR, type, type, null); }
        static ExprRef ident(String name) { return new ExprRef(ExprKind.IDENTIFIER, name, "", null); }
        static ExprRef call(CallSiteStmt call) { return new ExprRef(ExprKind.CALL, call.expression, "", call); }
        static ExprRef unknown(String raw) { return new ExprRef(ExprKind.UNKNOWN, raw == null ? "" : raw, "", null); }
    }

    private record ArgRef(String keywordName, ExprRef expr) {}

    private record AssignStmt(String varName, ExprRef expr) {}

    private record CallSiteStmt(
            String expression,
            String receiver,
            String methodName,
            boolean constructorCall,
            List<ArgRef> args,
            SourceCoordinates.Range span) {}

    private static final class SlotState {
        final String symbolId;
        final String filePath;
        final String language;
        final String callableQualifiedName;
        final String slotName;
        final SlotKind slotKind;
        final int parameterIndex;
        final boolean exportedPublicApi;
        final boolean explicitlyAnnotated;
        final SourceCoordinates.Range span;
        final Set<String> forwardF = new TreeSet<>();
        final Set<String> strongBs = new TreeSet<>();
        final Set<String> weakBw = new TreeSet<>();
        final Set<String> currentT = new TreeSet<>();

        SlotState(
                String symbolId, String filePath, String language, String callableQualifiedName,
                String slotName, SlotKind slotKind, int parameterIndex,
                boolean exportedPublicApi, boolean explicitlyAnnotated, SourceCoordinates.Range span) {
            this.symbolId = symbolId;
            this.filePath = filePath;
            this.language = language;
            this.callableQualifiedName = callableQualifiedName;
            this.slotName = slotName;
            this.slotKind = slotKind;
            this.parameterIndex = parameterIndex;
            this.exportedPublicApi = exportedPublicApi;
            this.explicitlyAnnotated = explicitlyAnnotated;
            this.span = span;
        }

        Set<String> effectiveForwardF() {
            if (!forwardF.isEmpty()) {
                return Collections.unmodifiableSet(new TreeSet<>(forwardF));
            }
            if (explicitlyAnnotated || (slotKind == SlotKind.LOCAL_VAR && !strongBs.isEmpty())) {
                return Set.of();
            }
            return Set.of(UNKNOWN_TYPE);
        }

        Set<String> computeCurrentT() {
            return combineSaltTypes(effectiveForwardF(), strongBs, weakBw);
        }
    }

    private static final class CallableModel {
        final String callableId;
        final String filePath;
        final String language;
        final String simpleName;
        final String qualifiedName;
        final String enclosingType;
        boolean exportedPublicApi;
        final SourceCoordinates.Range span;
        final List<SlotState> params = new ArrayList<>();
        final Map<String, SlotState> paramsByName = new LinkedHashMap<>();
        final Map<String, SlotState> localsByName = new TreeMap<>();
        SlotState returnSlot;
        final List<AssignStmt> assignments = new ArrayList<>();
        final List<ExprRef> returnExprs = new ArrayList<>();
        final List<CallSiteStmt> calls = new ArrayList<>();

        CallableModel(
                String filePath, String language, String simpleName, String qualifiedName,
                String enclosingType, boolean exportedPublicApi, SourceCoordinates.Range span) {
            this.callableId = filePath + "::" + qualifiedName;
            this.filePath = filePath;
            this.language = language;
            this.simpleName = simpleName;
            this.qualifiedName = qualifiedName;
            this.enclosingType = enclosingType == null ? "" : enclosingType;
            this.exportedPublicApi = exportedPublicApi;
            this.span = span;
        }

        SlotState findSlot(String name) {
            if (name == null || name.isBlank()) return null;
            SlotState p = paramsByName.get(name);
            if (p != null) return p;
            return localsByName.get(name);
        }

        SlotState resolveParameter(String keywordName, int positionalIndex) {
            if (keywordName != null && !keywordName.isBlank()) {
                return paramsByName.get(keywordName);
            }
            if (positionalIndex >= 0 && positionalIndex < params.size()) {
                return params.get(positionalIndex);
            }
            return null;
        }

        List<SlotState> slots() {
            var all = new ArrayList<SlotState>();
            all.addAll(params);
            if (returnSlot != null) all.add(returnSlot);
            all.addAll(localsByName.values());
            return all;
        }
    }

    private static final class ModuleModel {
        final String path;
        final String language;
        final ContentDigest contentDigest;
        final List<CallableModel> callables = new ArrayList<>();
        final Set<String> declaredClasses = new TreeSet<>();
        final Set<String> pythonDunderAll = new TreeSet<>();
        boolean hasPythonDunderAll = false;
        final Set<String> tsExportedNames = new TreeSet<>();
        final Map<String, String> localImportAliases = new TreeMap<>();

        ModuleModel(String path, String language, ContentDigest contentDigest) {
            this.path = path;
            this.language = language;
            this.contentDigest = contentDigest;
        }
    }

    private static Set<String> concreteTypesOf(SlotState slot) {
        var out = new TreeSet<String>();
        for (String t : slot.computeCurrentT()) {
            if (!UNKNOWN_TYPE.equals(t)) out.add(t);
        }
        return out;
    }

    private static Set<String> evaluateExpr(
            ModuleModel mod,
            CallableModel fn,
            ExprRef expr,
            Map<String, List<ResolvedEdge>> callEdgeBySite,
            Map<String, Map<String, List<ResolvedEdge>>> importedSymbolsByFile,
            Map<String, CallableModel> callablesByFileAndQName,
            Map<String, List<CallableModel>> callablesByFileAndName,
            Map<String, Map<String, List<CallableModel>>> methodsByLangAndClass,
            Map<String, Set<String>> classesByLangAndName,
            ScopeGraph scopeGraph,
            String langGroup) {
        if (expr == null) return Set.of();
        return switch (expr.kind) {
            case LITERAL, CONSTRUCTOR -> Set.of(expr.literalOrCtorType);
            case IDENTIFIER -> {
                if ("self".equals(expr.text) || "this".equals(expr.text)) {
                    yield fn.enclosingType.isEmpty() ? Set.of() : Set.of(fn.enclosingType);
                }
                SlotState s = fn.findSlot(expr.text);
                if (s == null) yield Set.of();
                yield s.computeCurrentT();
            }
            case CALL -> {
                if (expr.nestedCall == null) yield Set.of();
                CallSiteStmt nested = expr.nestedCall;
                if (nested.constructorCall && !nested.methodName.isEmpty()) {
                    yield Set.of(stripGenericArgs(nested.methodName));
                }
                // Python class constructor idiom: Foo(...) where Foo starts with uppercase or is a declared class
                if (mod.language.equals("python") && nested.receiver.isEmpty()
                        && isPythonConstructorName(nested.methodName, langGroup, classesByLangAndName)) {
                    yield Set.of(nested.methodName);
                }
                var tempRecovered = new TreeSet<ResolvedHintCallEdge>();
                List<CallableModel> targets = resolveCallTargets(
                        mod, fn, nested, callEdgeBySite, importedSymbolsByFile,
                        callablesByFileAndQName, callablesByFileAndName, methodsByLangAndClass,
                        scopeGraph, langGroup, tempRecovered);
                if (targets.size() == 1 && targets.getFirst().returnSlot != null) {
                    yield targets.getFirst().returnSlot.computeCurrentT();
                }
                yield Set.of();
            }
            case UNKNOWN -> Set.of();
        };
    }

    private static boolean isPythonConstructorName(
            String name, String langGroup, Map<String, Set<String>> classesByLangAndName) {
        if (name == null || name.isBlank()) return false;
        if (classesByLangAndName.containsKey(langGroup + "|" + name)) return true;
        return Character.isUpperCase(name.charAt(0)) && !name.equals(name.toUpperCase(Locale.ROOT));
    }

    private static List<CallableModel> resolveCallTargets(
            ModuleModel mod,
            CallableModel caller,
            CallSiteStmt call,
            Map<String, List<ResolvedEdge>> callEdgeBySite,
            Map<String, Map<String, List<ResolvedEdge>>> importedSymbolsByFile,
            Map<String, CallableModel> callablesByFileAndQName,
            Map<String, List<CallableModel>> callablesByFileAndName,
            Map<String, Map<String, List<CallableModel>>> methodsByLangAndClass,
            ScopeGraph scopeGraph,
            String langGroup,
            Set<ResolvedHintCallEdge> recoveredEdges) {
        // 1. Direct ScopeGraph CALLS_SYMBOL resolution (WP1.5)
        String siteKey = mod.path + "|" + call.span.start() + ":" + call.span.end();
        List<ResolvedEdge> sgEdges = callEdgeBySite.get(siteKey);
        if (sgEdges != null && !sgEdges.isEmpty()) {
            var resolved = new ArrayList<CallableModel>();
            for (ResolvedEdge e : sgEdges) {
                CallableModel target = callablesByFileAndQName.get(e.targetPath() + "::" + e.targetSymbol());
                if (target != null && !resolved.contains(target)) {
                    resolved.add(target);
                }
            }
            if (!resolved.isEmpty()) return resolved;
        }

        // 2. Unqualified direct function call in same file or via explicit import
        if (call.receiver.isEmpty()) {
            String targetName = mod.localImportAliases.getOrDefault(call.methodName, call.methodName);
            CallableModel local = callablesByFileAndQName.get(mod.path + "::" + targetName);
            if (local != null) return List.of(local);
            var importsForFile = importedSymbolsByFile.get(mod.path);
            if (importsForFile != null) {
                var impEdges = importsForFile.get(targetName);
                if (impEdges != null && impEdges.size() == 1) {
                    ResolvedEdge ie = impEdges.getFirst();
                    CallableModel imported = callablesByFileAndQName.get(ie.targetPath() + "::" + ie.targetSymbol());
                    if (imported != null) return List.of(imported);
                }
            }
            // Constructor call -> class constructor / __init__
            if (call.constructorCall || (mod.language.equals("python") && !call.methodName.isEmpty()
                    && Character.isUpperCase(call.methodName.charAt(0)))) {
                String ctorName = mod.language.equals("python") ? "__init__" : "constructor";
                var classMethods = methodsByLangAndClass.get(langGroup + "|" + targetName);
                if (classMethods != null) {
                    var ctors = classMethods.get(ctorName);
                    if (ctors != null && ctors.size() == 1) return ctors;
                }
            }
            return List.of();
        }

        // 3. Receiver method call: self/this -> enclosing class
        if (("self".equals(call.receiver) || "this".equals(call.receiver)) && !caller.enclosingType.isEmpty()) {
            CallableModel sameClass = callablesByFileAndQName.get(mod.path + "::" + caller.enclosingType + "." + call.methodName);
            if (sameClass != null) return List.of(sameClass);
        }

        // 4. Receiver variable/parameter typed by bidirectional propagation T(recv)!
        SlotState recvSlot = caller.findSlot(call.receiver);
        if (recvSlot != null) {
            Set<String> concreteTypes = concreteTypesOf(recvSlot);
            if (concreteTypes.size() == 1) {
                String recvType = concreteTypes.iterator().next();
                String simpleType = tailIdent(stripGenericArgs(recvType));
                var classMethods = methodsByLangAndClass.get(langGroup + "|" + simpleType);
                if (classMethods != null) {
                    List<CallableModel> cands = classMethods.get(call.methodName);
                    if (cands != null && cands.size() == 1) {
                        CallableModel target = cands.getFirst();
                        recoveredEdges.add(new ResolvedHintCallEdge(
                                mod.path,
                                caller.qualifiedName,
                                call.receiver,
                                simpleType,
                                target.filePath,
                                target.qualifiedName,
                                HINT_TRUST_TIER,
                                PROVENANCE_SOURCE_SYNTAX_HINT,
                                call.span));
                        return List.of(target);
                    }
                }
            }
        }

        // 5. Module namespace import alias call (e.g., svc.process())
        var importsForFile = importedSymbolsByFile.get(mod.path);
        if (importsForFile != null) {
            var nsImports = importsForFile.get("<module>");
            if (nsImports != null) {
                for (ResolvedEdge ie : nsImports) {
                    CallableModel target = callablesByFileAndQName.get(ie.targetPath() + "::" + call.methodName);
                    if (target != null) return List.of(target);
                }
            }
        }
        return List.of();
    }

    // -------------------------------------------------------------------------
    // Phase 1 AST Extraction (Python & TypeScript/TSX)
    // -------------------------------------------------------------------------

    private static void extractModule(
            ModuleModel mod, TSNode root, byte[] utf8, SourceCoordinates pos, BooleanSupplier cancelled)
            throws IOException {
        CallableModel moduleScope = new CallableModel(
                mod.path, mod.language, "<module>", "<module>", "", false, spanOf(root, pos));
        if (mod.language.equals("python")) {
            walkPythonModule(mod, root, utf8, pos, "", true, moduleScope, cancelled);
        } else {
            walkTsModule(mod, root, utf8, pos, "", false, false, moduleScope, cancelled);
        }
        if (!moduleScope.calls.isEmpty() || !moduleScope.assignments.isEmpty() || !moduleScope.localsByName.isEmpty()) {
            mod.callables.add(moduleScope);
        }
    }

    private static void finalizeModuleExports(ModuleModel mod) {
        if (mod.language.equals("python") && mod.hasPythonDunderAll) {
            for (CallableModel fn : mod.callables) {
                if (fn.enclosingType.isEmpty()) {
                    fn.exportedPublicApi = mod.pythonDunderAll.contains(fn.simpleName);
                }
            }
        } else if (!mod.language.equals("python") && !mod.tsExportedNames.isEmpty()) {
            for (CallableModel fn : mod.callables) {
                if (fn.enclosingType.isEmpty() && mod.tsExportedNames.contains(fn.simpleName)) {
                    fn.exportedPublicApi = true;
                }
            }
        }
        // Propagate updated export visibility to each callable's parameter and return slots
        for (CallableModel fn : mod.callables) {
            for (int i = 0; i < fn.params.size(); i++) {
                SlotState old = fn.params.get(i);
                if (old.exportedPublicApi != fn.exportedPublicApi) {
                    SlotState replaced = copySlotWithExport(old, fn.exportedPublicApi);
                    fn.params.set(i, replaced);
                    fn.paramsByName.put(replaced.slotName, replaced);
                }
            }
            if (fn.returnSlot != null && fn.returnSlot.exportedPublicApi != fn.exportedPublicApi) {
                fn.returnSlot = copySlotWithExport(fn.returnSlot, fn.exportedPublicApi);
            }
        }
    }

    private static SlotState copySlotWithExport(SlotState old, boolean exported) {
        SlotState s = new SlotState(
                old.symbolId, old.filePath, old.language, old.callableQualifiedName,
                old.slotName, old.slotKind, old.parameterIndex,
                exported, old.explicitlyAnnotated, old.span);
        s.forwardF.addAll(old.forwardF);
        s.strongBs.addAll(old.strongBs);
        s.weakBw.addAll(old.weakBw);
        return s;
    }

    // --- Python AST Walker ---

    private static void walkPythonModule(
            ModuleModel mod, TSNode node, byte[] utf8, SourceCoordinates pos,
            String enclosingType, boolean enclosingClassExported, CallableModel currentFn,
            BooleanSupplier cancelled) throws IOException {
        checkCancelled(cancelled);
        String kind = node.getType();
        if (kind.contains("comment") || kind.equals("string")) return;

        if (kind.equals("import_from_statement") && (currentFn == null || "<module>".equals(currentFn.simpleName))) {
            for (int i = 0; i < node.getNamedChildCount(); i++) {
                TSNode c = node.getNamedChild(i);
                if (c.getType().equals("aliased_import")) {
                    String orig = text(field(c, "name"), utf8).strip();
                    String alias = text(field(c, "alias"), utf8).strip();
                    if (!orig.isEmpty() && !alias.isEmpty()) {
                        mod.localImportAliases.put(alias, orig);
                    }
                }
            }
            return;
        }

        if (kind.equals("class_definition")) {
            String className = text(field(node, "name"), utf8).strip();
            if (!className.isEmpty()) {
                String qname = enclosingType.isEmpty() ? className : enclosingType + "." + className;
                boolean classExported = enclosingClassExported && !className.startsWith("_");
                mod.declaredClasses.add(className);
                TSNode body = field(node, "body");
                if (body != null) {
                    for (int i = 0; i < body.getNamedChildCount(); i++) {
                        walkPythonModule(mod, body.getNamedChild(i), utf8, pos, qname, classExported, null, cancelled);
                    }
                }
                return;
            }
        }

        if (kind.equals("function_definition")) {
            String fnName = text(field(node, "name"), utf8).strip();
            if (!fnName.isEmpty()) {
                boolean isMethod = !enclosingType.isEmpty();
                String qname = isMethod ? enclosingType + "." + fnName : fnName;
                boolean exported = isMethod
                        ? (enclosingClassExported && (!fnName.startsWith("_") || fnName.equals("__init__")))
                        : !fnName.startsWith("_");
                var fnSpan = spanOf(node, pos);
                CallableModel fn = new CallableModel(mod.path, mod.language, fnName, qname, enclosingType, exported, fnSpan);

                // Extract parameters
                TSNode params = field(node, "parameters");
                if (params != null) {
                    int paramIdx = 0;
                    for (int i = 0; i < params.getNamedChildCount(); i++) {
                        TSNode p = params.getNamedChild(i);
                        String pk = p.getType();
                        String pName = "";
                        String explicitType = "";
                        ExprRef defaultExpr = null;
                        if (pk.equals("identifier")) {
                            pName = text(p, utf8).strip();
                        } else if (pk.equals("default_parameter")) {
                            pName = text(field(p, "name"), utf8).strip();
                            defaultExpr = extractPythonExpr(field(p, "value"), utf8, pos);
                        } else if (pk.equals("typed_parameter")) {
                            TSNode nameNode = field(p, "name");
                            if (nameNode != null) pName = text(nameNode, utf8).strip();
                            else if (p.getNamedChildCount() > 0 && p.getNamedChild(0).getType().equals("identifier")) {
                                pName = text(p.getNamedChild(0), utf8).strip();
                            }
                            explicitType = normalizePythonType(text(field(p, "type"), utf8));
                        } else if (pk.equals("typed_default_parameter")) {
                            pName = text(field(p, "name"), utf8).strip();
                            explicitType = normalizePythonType(text(field(p, "type"), utf8));
                            defaultExpr = extractPythonExpr(field(p, "value"), utf8, pos);
                        }
                        if (pName.isEmpty()) continue;
                        if (isMethod && paramIdx == 0 && (pName.equals("self") || pName.equals("cls"))) continue;
                        boolean annotated = !explicitType.isEmpty();
                        SlotState slot = new SlotState(
                                mod.path + "::" + qname + "#param:" + pName,
                                mod.path, mod.language, qname, pName, SlotKind.PARAMETER,
                                paramIdx++, exported, annotated, spanOf(p, pos));
                        if (annotated) {
                            slot.strongBs.add(explicitType);
                        } else if (defaultExpr != null && (defaultExpr.kind == ExprKind.LITERAL || defaultExpr.kind == ExprKind.CONSTRUCTOR)
                                && !"None".equals(defaultExpr.literalOrCtorType)) {
                            slot.strongBs.add(defaultExpr.literalOrCtorType);
                        }
                        fn.params.add(slot);
                        fn.paramsByName.put(pName, slot);
                    }
                }

                // Extract optional explicit return annotation
                TSNode retTypeNode = field(node, "return_type");
                String retType = retTypeNode != null ? normalizePythonType(text(retTypeNode, utf8)) : "";
                if (!retType.isEmpty()) {
                    fn.returnSlot = new SlotState(
                            mod.path + "::" + qname + "#return",
                            mod.path, mod.language, qname, "return", SlotKind.RETURN,
                            -1, exported, true, spanOf(retTypeNode, pos));
                    fn.returnSlot.strongBs.add(retType);
                }

                mod.callables.add(fn);
                TSNode body = field(node, "body");
                if (body != null) {
                    walkPythonBody(mod, fn, body, utf8, pos, cancelled);
                }
                return;
            }
        }

        if (kind.equals("assignment") && currentFn != null && "<module>".equals(currentFn.simpleName)) {
            TSNode left = field(node, "left");
            TSNode typeNode = field(node, "type");
            TSNode right = field(node, "right");
            String lhs = left != null ? text(left, utf8).strip() : "";
            if ("__all__".equals(lhs) && right != null) {
                mod.hasPythonDunderAll = true;
                Matcher m = STRING_TOKEN.matcher(text(right, utf8));
                while (m.find()) mod.pythonDunderAll.add(m.group(1));
            } else if (left != null && left.getType().equals("identifier") && !lhs.isEmpty()) {
                String explicitType = typeNode != null ? normalizePythonType(text(typeNode, utf8)) : "";
                SlotState slot = currentFn.findSlot(lhs);
                if (slot == null) {
                    slot = new SlotState(
                            mod.path + "::<module>#var:" + lhs,
                            mod.path, mod.language, "<module>", lhs, SlotKind.LOCAL_VAR,
                            -1, false, !explicitType.isEmpty(), spanOf(left, pos));
                    currentFn.localsByName.put(lhs, slot);
                }
                if (!explicitType.isEmpty()) slot.strongBs.add(explicitType);
                if (right != null) {
                    ExprRef rhs = extractPythonExpr(right, utf8, pos);
                    if (rhs.kind == ExprKind.LITERAL || rhs.kind == ExprKind.CONSTRUCTOR) {
                        slot.strongBs.add(rhs.literalOrCtorType);
                    } else {
                        currentFn.assignments.add(new AssignStmt(lhs, rhs));
                    }
                }
            }
        } else if (kind.equals("call") && currentFn != null && "<module>".equals(currentFn.simpleName)) {
            CallSiteStmt cs = extractPythonCall(node, utf8, pos);
            if (cs != null) currentFn.calls.add(cs);
        }

        for (int i = 0; i < node.getNamedChildCount(); i++) {
            walkPythonModule(mod, node.getNamedChild(i), utf8, pos, enclosingType, enclosingClassExported, currentFn, cancelled);
        }
    }

    private static void walkPythonBody(
            ModuleModel mod, CallableModel fn, TSNode node, byte[] utf8, SourceCoordinates pos,
            BooleanSupplier cancelled) throws IOException {
        checkCancelled(cancelled);
        String kind = node.getType();
        if (kind.contains("comment") || kind.equals("string") || kind.equals("function_definition") || kind.equals("class_definition")) {
            return;
        }

        if (kind.equals("assignment")) {
            TSNode left = field(node, "left");
            TSNode typeNode = field(node, "type");
            TSNode right = field(node, "right");
            String varName = left != null && left.getType().equals("identifier") ? text(left, utf8).strip() : "";
            if (!varName.isEmpty()) {
                String explicitType = typeNode != null ? normalizePythonType(text(typeNode, utf8)) : "";
                SlotState slot = fn.findSlot(varName);
                if (slot == null) {
                    slot = new SlotState(
                            mod.path + "::" + fn.qualifiedName + "#var:" + varName,
                            mod.path, mod.language, fn.qualifiedName, varName, SlotKind.LOCAL_VAR,
                            -1, false, !explicitType.isEmpty(), spanOf(left, pos));
                    fn.localsByName.put(varName, slot);
                }
                if (!explicitType.isEmpty()) {
                    slot.strongBs.add(explicitType);
                }
                if (right != null) {
                    ExprRef rhs = extractPythonExpr(right, utf8, pos);
                    if (rhs.kind == ExprKind.LITERAL || rhs.kind == ExprKind.CONSTRUCTOR) {
                        slot.strongBs.add(rhs.literalOrCtorType);
                    } else {
                        fn.assignments.add(new AssignStmt(varName, rhs));
                    }
                }
            }
        } else if (kind.equals("return_statement")) {
            if (node.getNamedChildCount() > 0) {
                TSNode retVal = node.getNamedChild(0);
                if (fn.returnSlot == null) {
                    fn.returnSlot = new SlotState(
                            mod.path + "::" + fn.qualifiedName + "#return",
                            mod.path, mod.language, fn.qualifiedName, "return", SlotKind.RETURN,
                            -1, fn.exportedPublicApi, false, spanOf(node, pos));
                }
                fn.returnExprs.add(extractPythonExpr(retVal, utf8, pos));
            }
        } else if (kind.equals("call")) {
            TSNode fnNode = field(node, "function");
            String fnText = fnNode != null ? text(fnNode, utf8).strip() : "";
            if ("isinstance".equals(fnText)) {
                extractPythonIsInstanceGuard(fn, node, utf8);
            } else {
                CallSiteStmt callStmt = extractPythonCall(node, utf8, pos);
                if (callStmt != null) {
                    fn.calls.add(callStmt);
                }
            }
        }

        for (int i = 0; i < node.getNamedChildCount(); i++) {
            walkPythonBody(mod, fn, node.getNamedChild(i), utf8, pos, cancelled);
        }
    }

    private static void extractPythonIsInstanceGuard(CallableModel fn, TSNode callNode, byte[] utf8) {
        TSNode args = field(callNode, "arguments");
        if (args == null || args.getNamedChildCount() < 2) return;
        TSNode targetNode = args.getNamedChild(0);
        TSNode typeArg = args.getNamedChild(1);
        if (!targetNode.getType().equals("identifier")) return;
        String varName = text(targetNode, utf8).strip();
        SlotState slot = fn.findSlot(varName);
        if (slot == null) return;
        if (typeArg.getType().equals("tuple")) {
            for (int i = 0; i < typeArg.getNamedChildCount(); i++) {
                String t = normalizePythonType(text(typeArg.getNamedChild(i), utf8));
                if (!t.isEmpty()) slot.strongBs.add(t);
            }
        } else {
            String t = normalizePythonType(text(typeArg, utf8));
            if (!t.isEmpty()) slot.strongBs.add(t);
        }
    }

    private static ExprRef extractPythonExpr(TSNode node, byte[] utf8, SourceCoordinates pos) {
        if (node == null) return ExprRef.unknown("");
        String k = node.getType();
        if (k.equals("parenthesized_expression") && node.getNamedChildCount() == 1) {
            return extractPythonExpr(node.getNamedChild(0), utf8, pos);
        }
        return switch (k) {
            case "integer" -> ExprRef.literal("int");
            case "float" -> ExprRef.literal("float");
            case "string", "concatenated_string" -> ExprRef.literal("str");
            case "true", "false" -> ExprRef.literal("bool");
            case "none" -> ExprRef.literal("None");
            case "list", "list_comprehension" -> ExprRef.literal("list");
            case "dictionary", "dictionary_comprehension" -> ExprRef.literal("dict");
            case "set", "set_comprehension" -> ExprRef.literal("set");
            case "tuple" -> ExprRef.literal("tuple");
            case "identifier" -> ExprRef.ident(text(node, utf8).strip());
            case "call" -> {
                CallSiteStmt cs = extractPythonCall(node, utf8, pos);
                if (cs == null) yield ExprRef.unknown(text(node, utf8));
                if (cs.receiver.isEmpty() && !cs.methodName.isEmpty()
                        && Character.isUpperCase(cs.methodName.charAt(0))
                        && !cs.methodName.equals(cs.methodName.toUpperCase(Locale.ROOT))) {
                    yield ExprRef.ctor(cs.methodName);
                }
                yield ExprRef.call(cs);
            }
            default -> ExprRef.unknown(text(node, utf8));
        };
    }

    private static CallSiteStmt extractPythonCall(TSNode node, byte[] utf8, SourceCoordinates pos) {
        TSNode fnNode = field(node, "function");
        if (fnNode == null) return null;
        String fnKind = fnNode.getType();
        String expr = text(fnNode, utf8).strip();
        String recv = "";
        String method = "";
        if (fnKind.equals("attribute")) {
            TSNode obj = field(fnNode, "object");
            TSNode attr = field(fnNode, "attribute");
            recv = obj != null ? text(obj, utf8).strip() : "";
            method = attr != null ? text(attr, utf8).strip() : "";
        } else if (fnKind.equals("identifier")) {
            method = expr;
        } else {
            return null;
        }
        if (method.isEmpty()) return null;
        var argsList = new ArrayList<ArgRef>();
        TSNode argsNode = field(node, "arguments");
        if (argsNode != null) {
            for (int i = 0; i < argsNode.getNamedChildCount(); i++) {
                TSNode arg = argsNode.getNamedChild(i);
                if (arg.getType().equals("keyword_argument")) {
                    String kw = text(field(arg, "name"), utf8).strip();
                    argsList.add(new ArgRef(kw, extractPythonExpr(field(arg, "value"), utf8, pos)));
                } else {
                    argsList.add(new ArgRef("", extractPythonExpr(arg, utf8, pos)));
                }
            }
        }
        return new CallSiteStmt(expr, recv, method, false, List.copyOf(argsList), spanOf(node, pos));
    }

    // --- TypeScript / TSX / JavaScript AST Walker ---

    private static void walkTsModule(
            ModuleModel mod, TSNode node, byte[] utf8, SourceCoordinates pos,
            String enclosingType, boolean insideExport, boolean enclosingClassExported,
            CallableModel currentFn, BooleanSupplier cancelled) throws IOException {
        checkCancelled(cancelled);
        String kind = node.getType();
        if (kind.contains("comment") || kind.equals("string") || kind.equals("template_string")) return;

        if (kind.equals("import_statement") && (currentFn == null || "<module>".equals(currentFn.simpleName))) {
            for (int i = 0; i < node.getNamedChildCount(); i++) {
                TSNode c = node.getNamedChild(i);
                if (c.getType().equals("import_clause")) {
                    for (int j = 0; j < c.getNamedChildCount(); j++) {
                        TSNode part = c.getNamedChild(j);
                        if (part.getType().equals("named_imports")) {
                            for (int k = 0; k < part.getNamedChildCount(); k++) {
                                TSNode sp = part.getNamedChild(k);
                                if (sp.getType().equals("import_specifier")) {
                                    String orig = text(field(sp, "name"), utf8).strip();
                                    String alias = text(field(sp, "alias"), utf8).strip();
                                    if (!orig.isEmpty() && !alias.isEmpty()) {
                                        mod.localImportAliases.put(alias, orig);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return;
        }

        if (kind.equals("export_statement")) {
            if (field(node, "source") != null) return;
            for (int i = 0; i < node.getNamedChildCount(); i++) {
                TSNode c = node.getNamedChild(i);
                if (c.getType().equals("export_clause")) {
                    for (int j = 0; j < c.getNamedChildCount(); j++) {
                        TSNode sp = c.getNamedChild(j);
                        if (sp.getType().equals("export_specifier")) {
                            String orig = text(field(sp, "name"), utf8).strip();
                            if (!orig.isEmpty()) mod.tsExportedNames.add(orig);
                        }
                    }
                    return;
                }
            }
            for (int i = 0; i < node.getNamedChildCount(); i++) {
                TSNode c = node.getNamedChild(i);
                if (c.getType().equals("identifier")) {
                    mod.tsExportedNames.add(text(c, utf8).strip());
                } else {
                    walkTsModule(mod, c, utf8, pos, enclosingType, true, enclosingClassExported, null, cancelled);
                }
            }
            return;
        }

        if (kind.equals("class_declaration") || kind.equals("abstract_class_declaration")) {
            String className = text(field(node, "name"), utf8).strip();
            if (!className.isEmpty()) {
                String qname = enclosingType.isEmpty() ? className : enclosingType + "." + className;
                mod.declaredClasses.add(className);
                TSNode body = field(node, "body");
                if (body != null) {
                    for (int i = 0; i < body.getNamedChildCount(); i++) {
                        walkTsModule(mod, body.getNamedChild(i), utf8, pos, qname, false, insideExport, null, cancelled);
                    }
                }
                return;
            }
        }

        if (kind.equals("function_declaration") || kind.equals("generator_function_declaration")) {
            String fnName = text(field(node, "name"), utf8).strip();
            if (!fnName.isEmpty()) {
                extractTsCallable(mod, node, fnName, fnName, "", insideExport, utf8, pos, cancelled);
                return;
            }
        }

        if (kind.equals("method_definition") && !enclosingType.isEmpty()) {
            String mName = text(field(node, "name"), utf8).strip();
            if (!mName.isEmpty()) {
                boolean isPrivate = hasTsPrivateModifier(node, utf8) || mName.startsWith("#");
                boolean exported = enclosingClassExported && !isPrivate;
                extractTsCallable(mod, node, mName, enclosingType + "." + mName, enclosingType, exported, utf8, pos, cancelled);
                return;
            }
        }

        if (kind.equals("variable_declarator")) {
            TSNode nameNode = field(node, "name");
            TSNode typeAnn = field(node, "type");
            TSNode valNode = field(node, "value");
            String varName = nameNode != null && nameNode.getType().equals("identifier") ? text(nameNode, utf8).strip() : "";
            if (!varName.isEmpty() && valNode != null
                    && (valNode.getType().equals("arrow_function")
                    || valNode.getType().equals("function_expression")
                    || valNode.getType().equals("function"))) {
                extractTsCallable(mod, valNode, varName, varName, enclosingType, insideExport, utf8, pos, cancelled);
                return;
            }
            if (!varName.isEmpty() && currentFn != null && "<module>".equals(currentFn.simpleName)) {
                String explicitType = typeAnn != null ? normalizeTsType(text(typeAnn, utf8)) : "";
                SlotState slot = currentFn.findSlot(varName);
                if (slot == null) {
                    slot = new SlotState(
                            mod.path + "::<module>#var:" + varName,
                            mod.path, mod.language, "<module>", varName, SlotKind.LOCAL_VAR,
                            -1, false, !explicitType.isEmpty(), spanOf(nameNode, pos));
                    currentFn.localsByName.put(varName, slot);
                }
                if (!explicitType.isEmpty()) slot.strongBs.add(explicitType);
                if (valNode != null) {
                    ExprRef rhs = extractTsExpr(valNode, utf8, pos);
                    if (rhs.kind == ExprKind.LITERAL || rhs.kind == ExprKind.CONSTRUCTOR) {
                        slot.strongBs.add(rhs.literalOrCtorType);
                    } else {
                        currentFn.assignments.add(new AssignStmt(varName, rhs));
                    }
                }
            }
        } else if ((kind.equals("call_expression") || kind.equals("new_expression"))
                && currentFn != null && "<module>".equals(currentFn.simpleName)) {
            CallSiteStmt cs = extractTsCall(node, utf8, pos);
            if (cs != null) currentFn.calls.add(cs);
        }

        boolean passExport = insideExport && (kind.equals("lexical_declaration") || kind.equals("variable_declaration"));
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            walkTsModule(mod, node.getNamedChild(i), utf8, pos, enclosingType, passExport, enclosingClassExported, currentFn, cancelled);
        }
    }

    private static void extractTsCallable(
            ModuleModel mod, TSNode fnNode, String simpleName, String qname, String enclosingType,
            boolean exported, byte[] utf8, SourceCoordinates pos, BooleanSupplier cancelled) throws IOException {
        CallableModel fn = new CallableModel(mod.path, mod.language, simpleName, qname, enclosingType, exported, spanOf(fnNode, pos));
        TSNode params = field(fnNode, "parameters");
        if (params != null) {
            int paramIdx = 0;
            for (int i = 0; i < params.getNamedChildCount(); i++) {
                TSNode p = params.getNamedChild(i);
                String pk = p.getType();
                String pName = "";
                String explicitType = "";
                ExprRef defaultExpr = null;
                if (pk.equals("identifier")) {
                    pName = text(p, utf8).strip();
                } else if (pk.equals("required_parameter") || pk.equals("optional_parameter")) {
                    TSNode pat = field(p, "pattern");
                    if (pat != null && pat.getType().equals("identifier")) pName = text(pat, utf8).strip();
                    else if (pat != null && pat.getType().equals("assignment_pattern")) {
                        pName = text(field(pat, "left"), utf8).strip();
                        defaultExpr = extractTsExpr(field(pat, "right"), utf8, pos);
                    }
                    TSNode typeAnn = field(p, "type");
                    if (typeAnn != null) explicitType = normalizeTsType(text(typeAnn, utf8));
                    TSNode val = field(p, "value");
                    if (val != null) defaultExpr = extractTsExpr(val, utf8, pos);
                } else if (pk.equals("assignment_pattern")) {
                    pName = text(field(p, "left"), utf8).strip();
                    defaultExpr = extractTsExpr(field(p, "right"), utf8, pos);
                }
                if (pName.isEmpty() || "this".equals(pName)) continue;
                boolean annotated = !explicitType.isEmpty();
                SlotState slot = new SlotState(
                        mod.path + "::" + qname + "#param:" + pName,
                        mod.path, mod.language, qname, pName, SlotKind.PARAMETER,
                        paramIdx++, exported, annotated, spanOf(p, pos));
                if (annotated) {
                    slot.strongBs.add(explicitType);
                } else if (defaultExpr != null && (defaultExpr.kind == ExprKind.LITERAL || defaultExpr.kind == ExprKind.CONSTRUCTOR)) {
                    slot.strongBs.add(defaultExpr.literalOrCtorType);
                }
                fn.params.add(slot);
                fn.paramsByName.put(pName, slot);
            }
        } else {
            // Single unparenthesized arrow parameter: x => ...
            TSNode singleParam = field(fnNode, "parameter");
            if (singleParam != null && singleParam.getType().equals("identifier")) {
                String pName = text(singleParam, utf8).strip();
                if (!pName.isEmpty()) {
                    SlotState slot = new SlotState(
                            mod.path + "::" + qname + "#param:" + pName,
                            mod.path, mod.language, qname, pName, SlotKind.PARAMETER,
                            0, exported, false, spanOf(singleParam, pos));
                    fn.params.add(slot);
                    fn.paramsByName.put(pName, slot);
                }
            }
        }

        TSNode retAnn = field(fnNode, "return_type");
        String retType = retAnn != null ? normalizeTsType(text(retAnn, utf8)) : "";
        if (!retType.isEmpty() && !"void".equals(retType)) {
            fn.returnSlot = new SlotState(
                    mod.path + "::" + qname + "#return",
                    mod.path, mod.language, qname, "return", SlotKind.RETURN,
                    -1, exported, true, spanOf(retAnn, pos));
            fn.returnSlot.strongBs.add(retType);
        }

        mod.callables.add(fn);
        TSNode body = field(fnNode, "body");
        if (body != null) {
            if (!body.getType().equals("statement_block")) {
                // Concise arrow expression body: (x) => expr
                if (fn.returnSlot == null) {
                    fn.returnSlot = new SlotState(
                            mod.path + "::" + qname + "#return",
                            mod.path, mod.language, qname, "return", SlotKind.RETURN,
                            -1, exported, false, spanOf(body, pos));
                }
                fn.returnExprs.add(extractTsExpr(body, utf8, pos));
            }
            walkTsBody(mod, fn, body, utf8, pos, cancelled);
        }
    }

    private static void walkTsBody(
            ModuleModel mod, CallableModel fn, TSNode node, byte[] utf8, SourceCoordinates pos,
            BooleanSupplier cancelled) throws IOException {
        checkCancelled(cancelled);
        String kind = node.getType();
        if (kind.contains("comment") || kind.equals("string") || kind.equals("template_string")
                || kind.equals("function_declaration") || kind.equals("class_declaration")) {
            return;
        }

        if (kind.equals("binary_expression")) {
            extractTsTypeGuard(fn, node, utf8);
        } else if (kind.equals("variable_declarator")) {
            TSNode nameNode = field(node, "name");
            TSNode typeAnn = field(node, "type");
            TSNode valNode = field(node, "value");
            String varName = nameNode != null && nameNode.getType().equals("identifier") ? text(nameNode, utf8).strip() : "";
            if (!varName.isEmpty()) {
                String explicitType = typeAnn != null ? normalizeTsType(text(typeAnn, utf8)) : "";
                SlotState slot = fn.findSlot(varName);
                if (slot == null) {
                    slot = new SlotState(
                            mod.path + "::" + fn.qualifiedName + "#var:" + varName,
                            mod.path, mod.language, fn.qualifiedName, varName, SlotKind.LOCAL_VAR,
                            -1, false, !explicitType.isEmpty(), spanOf(nameNode, pos));
                    fn.localsByName.put(varName, slot);
                }
                if (!explicitType.isEmpty()) slot.strongBs.add(explicitType);
                if (valNode != null) {
                    ExprRef rhs = extractTsExpr(valNode, utf8, pos);
                    if (rhs.kind == ExprKind.LITERAL || rhs.kind == ExprKind.CONSTRUCTOR) {
                        slot.strongBs.add(rhs.literalOrCtorType);
                    } else {
                        fn.assignments.add(new AssignStmt(varName, rhs));
                    }
                }
            }
        } else if (kind.equals("assignment_expression")) {
            TSNode left = field(node, "left");
            TSNode right = field(node, "right");
            if (left != null && left.getType().equals("identifier") && right != null) {
                String varName = text(left, utf8).strip();
                SlotState slot = fn.findSlot(varName);
                if (slot != null) {
                    ExprRef rhs = extractTsExpr(right, utf8, pos);
                    if (rhs.kind == ExprKind.LITERAL || rhs.kind == ExprKind.CONSTRUCTOR) {
                        slot.strongBs.add(rhs.literalOrCtorType);
                    } else {
                        fn.assignments.add(new AssignStmt(varName, rhs));
                    }
                }
            }
        } else if (kind.equals("return_statement")) {
            if (node.getNamedChildCount() > 0) {
                TSNode retVal = node.getNamedChild(0);
                if (fn.returnSlot == null) {
                    fn.returnSlot = new SlotState(
                            mod.path + "::" + fn.qualifiedName + "#return",
                            mod.path, mod.language, fn.qualifiedName, "return", SlotKind.RETURN,
                            -1, fn.exportedPublicApi, false, spanOf(node, pos));
                }
                fn.returnExprs.add(extractTsExpr(retVal, utf8, pos));
            }
        } else if (kind.equals("call_expression") || kind.equals("new_expression")) {
            CallSiteStmt cs = extractTsCall(node, utf8, pos);
            if (cs != null) fn.calls.add(cs);
        }

        for (int i = 0; i < node.getNamedChildCount(); i++) {
            walkTsBody(mod, fn, node.getNamedChild(i), utf8, pos, cancelled);
        }
    }

    private static void extractTsTypeGuard(CallableModel fn, TSNode binExpr, byte[] utf8) {
        TSNode left = field(binExpr, "left");
        TSNode op = field(binExpr, "operator");
        TSNode right = field(binExpr, "right");
        if (left == null || right == null) return;
        String opText = op != null ? text(op, utf8).strip() : "";
        if (opText.isEmpty()) {
            String full = text(binExpr, utf8);
            if (full.contains(" instanceof ")) opText = "instanceof";
            else if (full.contains("===")) opText = "===";
            else if (full.contains("==")) opText = "==";
        }
        if ("instanceof".equals(opText) && left.getType().equals("identifier")) {
            SlotState slot = fn.findSlot(text(left, utf8).strip());
            String typeName = normalizeTsType(text(right, utf8));
            if (slot != null && !typeName.isEmpty()) {
                slot.strongBs.add(typeName);
            }
            return;
        }
        if ("===".equals(opText) || "==".equals(opText)) {
            if (extractTypeofPair(fn, left, right, utf8)) return;
            extractTypeofPair(fn, right, left, utf8);
        }
    }

    private static boolean extractTypeofPair(CallableModel fn, TSNode typeofSide, TSNode literalSide, byte[] utf8) {
        if (!typeofSide.getType().equals("unary_expression")) return false;
        String unaryText = text(typeofSide, utf8).strip();
        if (!unaryText.startsWith("typeof ")) return false;
        TSNode arg = field(typeofSide, "argument");
        if (arg == null && typeofSide.getNamedChildCount() > 0) arg = typeofSide.getNamedChild(0);
        if (arg == null || !arg.getType().equals("identifier")) return false;
        if (!literalSide.getType().equals("string")) return false;
        String varName = text(arg, utf8).strip();
        String lit = unquote(text(literalSide, utf8));
        String mapped = mapJsTypeof(lit);
        if (mapped.isEmpty()) return false;
        SlotState slot = fn.findSlot(varName);
        if (slot != null) {
            slot.strongBs.add(mapped);
            return true;
        }
        return false;
    }

    private static String mapJsTypeof(String lit) {
        return switch (lit) {
            case "string" -> "string";
            case "number" -> "number";
            case "boolean" -> "boolean";
            case "bigint" -> "bigint";
            case "symbol" -> "symbol";
            case "function" -> "Function";
            case "object" -> "object";
            default -> "";
        };
    }

    private static ExprRef extractTsExpr(TSNode node, byte[] utf8, SourceCoordinates pos) {
        if (node == null) return ExprRef.unknown("");
        String k = node.getType();
        if ((k.equals("parenthesized_expression") || k.equals("await_expression")) && node.getNamedChildCount() == 1) {
            return extractTsExpr(node.getNamedChild(0), utf8, pos);
        }
        if (k.equals("as_expression") && node.getNamedChildCount() >= 2) {
            String castType = normalizeTsType(text(node.getNamedChild(node.getNamedChildCount() - 1), utf8));
            if (!castType.isEmpty()) return ExprRef.literal(castType);
        }
        return switch (k) {
            case "number" -> ExprRef.literal("number");
            case "string", "template_string" -> ExprRef.literal("string");
            case "true", "false" -> ExprRef.literal("boolean");
            case "array" -> ExprRef.literal("Array");
            case "object" -> ExprRef.literal("object");
            case "identifier" -> ExprRef.ident(text(node, utf8).strip());
            case "new_expression" -> {
                TSNode ctor = field(node, "constructor");
                String typeName = ctor != null ? stripGenericArgs(text(ctor, utf8).strip()) : "";
                yield typeName.isEmpty() ? ExprRef.unknown(text(node, utf8)) : ExprRef.ctor(typeName);
            }
            case "call_expression" -> {
                CallSiteStmt cs = extractTsCall(node, utf8, pos);
                yield cs == null ? ExprRef.unknown(text(node, utf8)) : ExprRef.call(cs);
            }
            default -> ExprRef.unknown(text(node, utf8));
        };
    }

    private static CallSiteStmt extractTsCall(TSNode node, byte[] utf8, SourceCoordinates pos) {
        boolean isNew = node.getType().equals("new_expression");
        TSNode fnNode = field(node, isNew ? "constructor" : "function");
        if (fnNode == null) return null;
        String fnKind = fnNode.getType();
        String expr = stripGenericArgs(text(fnNode, utf8).strip());
        String recv = "";
        String method = "";
        if (fnKind.equals("member_expression")) {
            TSNode obj = field(fnNode, "object");
            TSNode prop = field(fnNode, "property");
            recv = obj != null ? text(obj, utf8).strip() : "";
            method = prop != null ? text(prop, utf8).strip() : "";
        } else if (fnKind.equals("identifier") || fnKind.equals("type_identifier")) {
            method = expr;
        } else {
            return null;
        }
        if (method.isEmpty()) return null;
        var argsList = new ArrayList<ArgRef>();
        TSNode argsNode = field(node, "arguments");
        if (argsNode != null) {
            for (int i = 0; i < argsNode.getNamedChildCount(); i++) {
                argsList.add(new ArgRef("", extractTsExpr(argsNode.getNamedChild(i), utf8, pos)));
            }
        }
        return new CallSiteStmt(expr, recv, method, isNew, List.copyOf(argsList), spanOf(node, pos));
    }

    private static boolean hasTsPrivateModifier(TSNode methodNode, byte[] utf8) {
        for (int i = 0; i < methodNode.getNamedChildCount(); i++) {
            TSNode c = methodNode.getNamedChild(i);
            if (c.getType().equals("accessibility_modifier")) {
                String mod = text(c, utf8).strip();
                if ("private".equals(mod) || "protected".equals(mod)) return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Capability Gap Creation (I3) & Utility Helpers
    // -------------------------------------------------------------------------

    private static CapabilityGapRecord buildUnknownTypeGap(
            SymbolTypeHint hint, ContentDigest docDigest, EvidenceContext context) {
        var payload = new TreeMap<String, Object>();
        payload.put("exportedPublicApi", hint.exportedPublicApi());
        payload.put("filePath", hint.filePath());
        payload.put("inferredTypes", List.copyOf(hint.inferredTypes()));
        payload.put("language", hint.language());
        payload.put("provenance", PROVENANCE_SOURCE_SYNTAX_HINT);
        payload.put("slotKind", hint.slotKind().name());
        payload.put("symbolId", hint.symbolId());

        ContentDigest payloadDigest = CanonicalJson.sha256(payload);
        ProviderObservationReference obsRef = ProviderObservationReference.create(
                PROVIDER, "polyglot.bidirectional-type-unknown", docDigest, payloadDigest);
        EvidenceSubject subject = EvidenceSubject.observation(obsRef.identity());
        EvidenceRequirement requirement = new EvidenceRequirement(
                EvidenceRequirement.Kind.ALTERNATE_FRONTEND,
                "polyglot.resolve-bidirectional-type",
                List.of(subject),
                EvidenceRequirement.AuthorizationClass.ISOLATED_EXECUTION,
                List.of("Supply explicit type annotation or isolated compiler/SCIP type binding for unknown polyglot symbol."));

        List<SourceSpan> spans = toSourceSpans(hint.filePath(), hint.span());
        var limitations = List.of(
                "symbol=" + hint.symbolId(),
                "slot=" + hint.slotKind().name(),
                "file=" + hint.filePath(),
                "language=" + hint.language(),
                "exportedPublicApi=" + hint.exportedPublicApi(),
                "provenance=" + PROVENANCE_SOURCE_SYNTAX_HINT);

        return CapabilityGapRecord.create(
                GAP_CATALOG,
                context,
                PROVIDER,
                "polyglot.bidirectional-type-hint",
                GAP_REASON_UNKNOWN,
                subject,
                spans,
                List.of(obsRef),
                List.of(requirement),
                List.of(),
                List.of(new AffectedOutput(AffectedOutput.Kind.RELATIONSHIP_CATEGORY, "polyglot.type-hints")),
                List.of(),
                List.of(),
                limitations);
    }

    private static List<SourceSpan> toSourceSpans(String repoPath, SourceCoordinates.Range range) {
        try {
            if (range == null || range.startLine() < 1 || range.startColumn() < 1
                    || range.endLine() < range.startLine()
                    || (range.endLine() == range.startLine() && range.endColumn() <= range.startColumn())) {
                return List.of();
            }
            SourceDocumentIdentity docId = SourceDocumentIdentity.from(DEFAULT_REPO, repoPath);
            return List.of(new SourceSpan(docId, range.startLine(), range.startColumn(),
                    range.endLine(), range.endColumn()));
        } catch (IllegalArgumentException ignored) {
            return List.of();
        }
    }

    private static EvidenceContext deriveDefaultContext(Map<String, byte[]> sortedFiles) {
        var entries = new ArrayList<List<String>>(sortedFiles.size());
        for (var e : sortedFiles.entrySet()) {
            entries.add(List.of(e.getKey(), ContentDigest.sha256(e.getValue()).value()));
        }
        ContentDigest snapshotDigest = CanonicalJson.sha256(entries);
        SnapshotIdentity snapshotId = SnapshotIdentity.from(DEFAULT_REPO, snapshotDigest);
        return new EvidenceContext(snapshotId, Optional.empty());
    }

    private static String normalizePythonType(String raw) {
        if (raw == null) return "";
        String t = raw.strip();
        if (t.startsWith(":")) t = t.substring(1).strip();
        if (t.startsWith("->")) t = t.substring(2).strip();
        if (t.startsWith("Optional[") && t.endsWith("]")) {
            t = t.substring("Optional[".length(), t.length() - 1).strip();
        }
        int bracket = t.indexOf('[');
        if (bracket > 0) t = t.substring(0, bracket).strip();
        if (t.isEmpty() || UNTYPED_PLACEHOLDERS.contains(t) || "None".equals(t)) return "";
        return t;
    }

    private static String normalizeTsType(String raw) {
        if (raw == null) return "";
        String t = raw.strip();
        if (t.startsWith(":")) t = t.substring(1).strip();
        if (t.startsWith("Promise<") && t.endsWith(">")) {
            t = t.substring("Promise<".length(), t.length() - 1).strip();
        }
        t = stripGenericArgs(t).strip();
        if (t.endsWith("[]")) t = "Array";
        if (t.isEmpty() || UNTYPED_PLACEHOLDERS.contains(t)) return "";
        return t;
    }

    private static String stripGenericArgs(String s) {
        if (s == null) return "";
        int lt = s.indexOf('<');
        return lt > 0 ? s.substring(0, lt).strip() : s.strip();
    }

    private static String tailIdent(String expr) {
        if (expr == null) return "";
        int dot = expr.lastIndexOf('.');
        return dot >= 0 && dot < expr.length() - 1 ? expr.substring(dot + 1) : expr;
    }

    private static String unquote(String raw) {
        if (raw == null) return "";
        String s = raw.strip();
        if (s.length() >= 2 && ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\"")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String detectLanguage(String path) {
        String low = path.toLowerCase(Locale.ROOT);
        if (low.endsWith(".py") || low.endsWith(".pyi")) return "python";
        if (low.endsWith(".tsx")) return "tsx";
        if (low.endsWith(".ts") || low.endsWith(".mts") || low.endsWith(".cts")) return "typescript";
        if (low.endsWith(".js") || low.endsWith(".mjs") || low.endsWith(".cjs") || low.endsWith(".jsx")) return "javascript";
        return null;
    }

    private static String languageGroup(String language) {
        return switch (language) {
            case "typescript", "tsx", "javascript" -> "tsjs";
            default -> language;
        };
    }

    private static String normalizePath(String path) {
        String norm = path.replace('\\', '/').strip();
        while (norm.startsWith("./")) norm = norm.substring(2);
        while (norm.startsWith("/")) norm = norm.substring(1);
        return norm;
    }

    private static TSNode field(TSNode node, String name) {
        if (node == null || node.isNull()) return null;
        var child = node.getChildByFieldName(name);
        return child == null || child.isNull() ? null : child;
    }

    private static String text(TSNode node, byte[] utf8) {
        if (node == null || node.isNull()) return "";
        int start = Math.max(0, Math.min(node.getStartByte(), utf8.length));
        int end = Math.max(start, Math.min(node.getEndByte(), utf8.length));
        return new String(utf8, start, end - start, StandardCharsets.UTF_8);
    }

    private static SourceCoordinates.Range spanOf(TSNode node, SourceCoordinates pos) {
        int start = Math.max(0, node.getStartByte());
        int end = Math.max(start, node.getEndByte());
        return pos.range(start, end, SourceCoordinates.Encoding.UTF8);
    }

    private static Map<String, Object> rangeToMap(SourceCoordinates.Range span) {
        var map = new TreeMap<String, Object>();
        map.put("end", BigDecimal.valueOf(span.end()));
        map.put("endColumn", BigDecimal.valueOf(span.endColumn()));
        map.put("endLine", BigDecimal.valueOf(span.endLine()));
        map.put("start", BigDecimal.valueOf(span.start()));
        map.put("startColumn", BigDecimal.valueOf(span.startColumn()));
        map.put("startLine", BigDecimal.valueOf(span.startLine()));
        return map;
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new IOException("SYNTAX_CANCELLED");
        }
    }
}
