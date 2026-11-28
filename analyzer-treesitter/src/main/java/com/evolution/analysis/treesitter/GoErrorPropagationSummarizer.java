package com.evolution.analysis.treesitter;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.polyglot.FailCloseResilienceAnalyzer.ExceptionTransformation;
import com.evolution.analysis.polyglot.FailCloseResilienceAnalyzer.MethodErrorSummary;
import com.evolution.analysis.polyglot.FailCloseResilienceAnalyzer.PropagationDisposition;
import org.treesitter.TSInputEncoding;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterGo;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Standalone Tree-sitter Go intra-method error propagation summarizer ({@code WP1.2}).
 *
 * <p>Operates purely over Go source text using the bundled {@code tree-sitter-go} grammar
 * ({@code I1}: zero {@code go build}/SSA/host execution) with content-addressed LRU caching ({@code I2}):
 * <ul>
 *   <li>Detects outbound RPC/HTTP calls ({@code val, err := client.Call(...)}, {@code http.Get}, gRPC stubs).</li>
 *   <li>Tracks {@code if err != nil { return ..., err }} including {@code fmt.Errorf}, {@code errors.Wrap},
 *       {@code status.Errorf}, and HTTP {@code 5xx} writes.</li>
 *   <li>Enforces RPCShield {@code D2.2} last-failure attribution for fallback blocks
 *       ({@code if err != nil { val, err2 = fallback.Call(...) }} marks {@code fallback} as
 *       {@code PROPAGATES_ERROR}/{@code FAIL_CLOSE} and primary as {@code SUPPRESSED_FAIL_OPEN}/{@code FAIL_OPEN}),
 *       EXCEPT conjunctions ({@code err1 != nil && err2 != nil}) which mark BOTH as {@code FAIL_CLOSE}.</li>
 *   <li>Detects {@code errgroup.Wait()} propagation, suppression, and opaque async handlers ({@code I3}).</li>
 *   <li>Verifies counterfactual {@code D1.3} (at least one normal {@code Ok} return path exists).</li>
 * </ul>
 */
public final class GoErrorPropagationSummarizer {

    private static final int MAX_CACHE_ENTRIES = 1024;

    private static final Map<String, List<MethodErrorSummary>> SUMMARY_CACHE =
            Collections.synchronizedMap(new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<MethodErrorSummary>> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            });

    private static final Set<String> NON_RPC_RECEIVERS = Set.of(
            "fmt", "errors", "log", "slog", "zap", "logrus", "context", "json", "xml", "yaml",
            "strconv", "strings", "bytes", "bufio", "io", "os", "filepath", "path", "time", "math",
            "sort", "slices", "maps", "sync", "atomic", "reflect", "regexp", "unicode", "utf8",
            "binary", "hex", "base64", "sha256", "md5", "hmac", "rand", "uuid", "status", "codes",
            "metadata", "otel", "trace", "metrics", "prometheus", "testing", "require", "assert",
            "errgroup"
    );

    private static final Set<String> NON_RPC_METHODS = Set.of(
            "Errorf", "Sprintf", "Printf", "Println", "Print", "Fprintf", "Fprintln",
            "New", "Wrap", "Wrapf", "WithMessage", "WithStack", "Is", "As", "Unwrap", "Join",
            "Error", "String", "Close", "Cancel", "WithTimeout", "WithCancel", "WithValue",
            "Background", "TODO", "Marshal", "Unmarshal", "Encode", "Decode", "Atoi", "Itoa",
            "WriteHeader", "Write", "Header", "Debug", "Info", "Warn", "Warning", "Fatal",
            "Fatalf", "Panic", "Panicf", "Log", "Logf", "Inc", "Add", "Observe", "Set", "Wait", "Go"
    );

    private static final Set<String> LOGGING_RECEIVERS = Set.of(
            "log", "logger", "slog", "zap", "logrus", "fmt", "metrics", "meter", "prom", "span"
    );

    private static final Set<String> LOGGING_METHODS = Set.of(
            "Print", "Printf", "Println", "Debug", "Debugf", "Info", "Infof",
            "Warn", "Warnf", "Warning", "Error", "Errorf", "RecordError", "Inc", "Add", "Observe"
    );

    private static final Pattern HTTP_5XX_PATTERN = Pattern.compile(
            "\\b(?:StatusInternalServerError|StatusBadGateway|StatusServiceUnavailable|StatusGatewayTimeout"
                    + "|StatusNotImplemented|50[0-9]|5[1-9][0-9])\\b");
    private static final Pattern ERR_NEQ_NIL_PATTERN = Pattern.compile(
            "\\b([A-Za-z_][A-Za-z0-9_]*)\\s*!=\\s*nil\\b");

    private GoErrorPropagationSummarizer() {}

    /** Clears the content-addressed Go error propagation summary cache. */
    public static void clearCache() {
        SUMMARY_CACHE.clear();
    }

    /** Returns current number of cached Go source summary entries. */
    public static int cacheSize() {
        return SUMMARY_CACHE.size();
    }

    /**
     * Parses {@code goSource} with {@code tree-sitter-go} and returns cached deterministic
     * {@link MethodErrorSummary} entries for all outbound calls in the file.
     */
    public static List<MethodErrorSummary> summarizeErrorPropagation(String path, String goSource) {
        String safePath = Objects.requireNonNull(path, "path").strip();
        String safeSource = Objects.requireNonNull(goSource, "goSource");
        if (safeSource.isBlank()) {
            return List.of();
        }
        String cacheKey = safePath + "|" + ContentDigest.sha256Utf8(safeSource).value();
        List<MethodErrorSummary> cached = SUMMARY_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        List<MethodErrorSummary> computed = analyzeGoSource(safePath, safeSource);
        SUMMARY_CACHE.put(cacheKey, computed);
        return computed;
    }

    private static List<MethodErrorSummary> analyzeGoSource(String path, String source) {
        byte[] utf8 = source.getBytes(StandardCharsets.UTF_8);
        var summaries = new ArrayList<MethodErrorSummary>();
        var definedFunctions = new HashSet<String>();

        try (TSParser parser = new TSParser()) {
            if (!parser.setLanguage(new TreeSitterGo())) {
                return List.of();
            }
            try (TSTree tree = parser.parseStringEncoding(null, source, TSInputEncoding.TSInputEncodingUTF8)) {
                TSNode root = tree.getRootNode();
                if (root == null || root.isNull()) {
                    return List.of();
                }
                collectDefinedFunctions(root, utf8, definedFunctions);
                walkFunctions(root, path, utf8, definedFunctions, summaries);
            }
        }

        summaries.sort(Comparator.comparingInt(MethodErrorSummary::line)
                .thenComparing(MethodErrorSummary::methodId)
                .thenComparing(MethodErrorSummary::callsiteId));
        return List.copyOf(summaries);
    }

    private static void collectDefinedFunctions(TSNode node, byte[] utf8, Set<String> definedFunctions) {
        String type = node.getType();
        if ("function_declaration".equals(type) || "method_declaration".equals(type)) {
            TSNode nameNode = node.getChildByFieldName("name");
            if (nameNode != null && !nameNode.isNull()) {
                definedFunctions.add(slice(utf8, nameNode).strip());
            }
        }
        int count = node.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            collectDefinedFunctions(node.getNamedChild(i), utf8, definedFunctions);
        }
    }

    private static void walkFunctions(
            TSNode node,
            String path,
            byte[] utf8,
            Set<String> definedFunctions,
            List<MethodErrorSummary> out) {
        String type = node.getType();
        if ("function_declaration".equals(type) || "method_declaration".equals(type)) {
            analyzeFunction(node, path, utf8, definedFunctions, out);
            return;
        }
        int count = node.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            walkFunctions(node.getNamedChild(i), path, utf8, definedFunctions, out);
        }
    }

    private record GoCallSite(
            String callsiteId,
            int line,
            int endLine,
            String targetCall,
            String protocol,
            String errVar,
            boolean discardedError,
            TSNode stmtNode,
            TSNode enclosingIfNode,
            String errgroupVar,
            boolean insideErrgroupClosure,
            boolean closureReturnsError,
            boolean closureOpaque
    ) {}

    private static void analyzeFunction(
            TSNode fnNode,
            String path,
            byte[] utf8,
            Set<String> definedFunctions,
            List<MethodErrorSummary> out) {
        TSNode body = fnNode.getChildByFieldName("body");
        if (body == null || body.isNull()) {
            return;
        }
        String fnName = buildFunctionId(path, fnNode, utf8);
        String bodyText = slice(utf8, body);
        boolean counterfactualOk = hasNormalOkReturnPath(body, utf8);

        // 1. Identify errgroup variables declared in this function (e.g., g, gctx := errgroup.WithContext(ctx))
        Set<String> errgroupVars = detectErrgroupVars(body, utf8);
        Map<String, ErrgroupWaitOutcome> errgroupWaitOutcomes = analyzeErrgroupWaits(body, utf8, errgroupVars, definedFunctions);

        // 2. Collect top-level and nested statements to locate outbound RPC calls + errgroup.Go closures
        var callsites = new ArrayList<GoCallSite>();
        collectCallSites(body, path, utf8, errgroupVars, null, null, false, callsites);

        if (callsites.isEmpty()) {
            return;
        }

        // 3. Identify D2.2 conjunction error checks (err1 != nil && err2 != nil) across the function body
        Set<String> conjunctionErrVars = detectConjunctionErrorVars(body, utf8);

        // 4. Identify D2.2 fallback blocks: primary callsite -> fallback callsite inside `if primaryErr != nil { ... }`
        Map<String, GoCallSite> fallbackTargetByPrimaryCallsiteId = new HashMap<>();
        Map<String, GoCallSite> primaryByFallbackCallsiteId = new HashMap<>();
        Map<String, Boolean> primaryFallbackBlockAlwaysErrors = new HashMap<>();

        // Index statements in parent block to inspect follow-up `if err != nil` blocks accurately
        for (GoCallSite cs : callsites) {
            if (cs.insideErrgroupClosure()) {
                continue;
            }
            if (cs.errVar().isEmpty() || "_".equals(cs.errVar())) {
                continue;
            }
            if (conjunctionErrVars.contains(cs.errVar())) {
                continue;
            }
            TSNode guardIf = findGuardIfForCall(cs, utf8);
            if (guardIf == null || guardIf.isNull()) {
                continue;
            }
            TSNode consequence = guardIf.getChildByFieldName("consequence");
            if (consequence == null || consequence.isNull()) {
                continue;
            }
            // Check if any OTHER callsite lives inside this `if err != nil` consequence block -> fallback call!
            for (GoCallSite other : callsites) {
                if (other == cs || other.insideErrgroupClosure()) continue;
                if (isDescendant(other.stmtNode(), consequence)) {
                    // Check whether the fallback block recovers (does NOT unconditionally return error after fallback succeeds)
                    boolean blockUnconditionallyErrors = blockAlwaysReturnsErrorIgnoringNested(consequence, other, utf8);
                    fallbackTargetByPrimaryCallsiteId.put(cs.callsiteId(), other);
                    primaryByFallbackCallsiteId.put(other.callsiteId(), cs);
                    primaryFallbackBlockAlwaysErrors.put(cs.callsiteId(), blockUnconditionallyErrors);
                    break;
                }
            }
        }

        // 5. Evaluate each callsite
        for (GoCallSite cs : callsites) {
            MethodErrorSummary summary = evaluateCallSite(
                    fnName,
                    path,
                    cs,
                    body,
                    utf8,
                    counterfactualOk,
                    conjunctionErrVars,
                    fallbackTargetByPrimaryCallsiteId,
                    primaryFallbackBlockAlwaysErrors,
                    errgroupWaitOutcomes,
                    definedFunctions);
            out.add(summary);
        }
    }

    private static MethodErrorSummary evaluateCallSite(
            String fnName,
            String path,
            GoCallSite cs,
            TSNode fnBody,
            byte[] utf8,
            boolean counterfactualOk,
            Set<String> conjunctionErrVars,
            Map<String, GoCallSite> fallbackTargetByPrimary,
            Map<String, Boolean> primaryFallbackAlwaysErrors,
            Map<String, ErrgroupWaitOutcome> errgroupWaitOutcomes,
            Set<String> definedFunctions) {

        Set<String> baseErrors = Set.of("error");

        // Case 1: Inside errgroup.Go(...)
        if (cs.insideErrgroupClosure()) {
            ErrgroupWaitOutcome waitOutcome = errgroupWaitOutcomes.getOrDefault(
                    cs.errgroupVar(), ErrgroupWaitOutcome.missing());
            if (cs.closureOpaque() || waitOutcome.opaque()) {
                return new MethodErrorSummary(
                        "go", fnName, cs.callsiteId(), path, cs.line(), cs.endLine(),
                        cs.targetCall(), cs.protocol(), baseErrors, Set.of("error"),
                        List.of(), PropagationDisposition.UNKNOWN_OPAQUE, counterfactualOk,
                        false, false, true,
                        Optional.of("UNKNOWN_ASYNC_HANDLER"),
                        Optional.empty(), TrustTier.SOURCE_SYNTAX);
            }
            if (!cs.closureReturnsError() || waitOutcome.suppressed()) {
                return new MethodErrorSummary(
                        "go", fnName, cs.callsiteId(), path, cs.line(), cs.endLine(),
                        cs.targetCall(), cs.protocol(), baseErrors, Set.of(),
                        List.of(), PropagationDisposition.SUPPRESSED_FAIL_OPEN, counterfactualOk,
                        false, false, false, Optional.empty(),
                        Optional.of(!cs.closureReturnsError() ? "GO_ERRGROUP_CLOSURE_SUPPRESSED" : "GO_ERRGROUP_WAIT_SUPPRESSED"),
                        TrustTier.SOURCE_SYNTAX);
            }
            var transforms = new ArrayList<ExceptionTransformation>();
            transforms.add(new ExceptionTransformation("error", "errgroup.Wait", "GO_ERRGROUP_WAIT"));
            transforms.addAll(waitOutcome.transformations());
            return new MethodErrorSummary(
                    "go", fnName, cs.callsiteId(), path, cs.line(), cs.endLine(),
                    cs.targetCall(), cs.protocol(), baseErrors, Set.of("error"),
                    transforms, PropagationDisposition.PROPAGATES_ERROR, counterfactualOk,
                    false, false, false, Optional.empty(), Optional.empty(), TrustTier.SOURCE_SYNTAX);
        }

        // Case 2: Explicitly discarded error (val, _ := client.Call() or bare statement)
        if (cs.discardedError() || "_".equals(cs.errVar())) {
            return new MethodErrorSummary(
                    "go", fnName, cs.callsiteId(), path, cs.line(), cs.endLine(),
                    cs.targetCall(), cs.protocol(), baseErrors, Set.of(),
                    List.of(), PropagationDisposition.SUPPRESSED_FAIL_OPEN, counterfactualOk,
                    false, false, false, Optional.empty(),
                    Optional.of("GO_IGNORED_ERROR"), TrustTier.SOURCE_SYNTAX);
        }

        // Case 3: Direct return of call (`return client.Call(ctx)`)
        if ("return_statement".equals(cs.stmtNode().getType())) {
            return new MethodErrorSummary(
                    "go", fnName, cs.callsiteId(), path, cs.line(), cs.endLine(),
                    cs.targetCall(), cs.protocol(), baseErrors, Set.of("error"),
                    List.of(), PropagationDisposition.PROPAGATES_ERROR, counterfactualOk,
                    false, false, false, Optional.empty(), Optional.empty(), TrustTier.SOURCE_SYNTAX);
        }

        // Case 4: D2.2 Conjunction exception (`if err1 != nil && err2 != nil { return ..., err }`) -> BOTH FAIL_CLOSE!
        if (!cs.errVar().isEmpty() && conjunctionErrVars.contains(cs.errVar())) {
            TSNode conjIf = findConjunctionIf(fnBody, cs.errVar(), utf8);
            if (conjIf != null && !conjIf.isNull()) {
                TSNode consequence = conjIf.getChildByFieldName("consequence");
                BlockOutcome outcome = inspectErrorBlock(consequence, cs.errVar(), conjunctionErrVars, utf8, definedFunctions);
                if (outcome.propagates()) {
                    return new MethodErrorSummary(
                            "go", fnName, cs.callsiteId(), path, cs.line(), cs.endLine(),
                            cs.targetCall(), cs.protocol(), baseErrors, outcome.propagatedErrors(),
                            outcome.transformations(), PropagationDisposition.PROPAGATES_ERROR, counterfactualOk,
                            false, true, false, Optional.empty(), Optional.empty(), TrustTier.SOURCE_SYNTAX);
                }
            }
        }

        // Case 5: D2.2 Last-Failure Attribution — primary call masked by fallback block
        GoCallSite fallback = fallbackTargetByPrimary.get(cs.callsiteId());
        if (fallback != null && !Boolean.TRUE.equals(primaryFallbackAlwaysErrors.get(cs.callsiteId()))) {
            return new MethodErrorSummary(
                    "go", fnName, cs.callsiteId(), path, cs.line(), cs.endLine(),
                    cs.targetCall(), cs.protocol(), baseErrors, Set.of(),
                    List.of(), PropagationDisposition.SUPPRESSED_FAIL_OPEN, counterfactualOk,
                    true, false, false, Optional.empty(),
                    Optional.of("GO_FALLBACK_BLOCK:" + fallback.targetCall()),
                    TrustTier.SOURCE_SYNTAX);
        }

        // Case 6: Standard `if err != nil { ... }` check
        TSNode guardIf = findGuardIfForCall(cs, utf8);
        if (guardIf != null && !guardIf.isNull()) {
            TSNode consequence = guardIf.getChildByFieldName("consequence");
            BlockOutcome outcome = inspectErrorBlock(consequence, cs.errVar(), utf8, definedFunctions);
            if (outcome.opaque()) {
                return new MethodErrorSummary(
                        "go", fnName, cs.callsiteId(), path, cs.line(), cs.endLine(),
                        cs.targetCall(), cs.protocol(), baseErrors, Set.of("error"),
                        outcome.transformations(), PropagationDisposition.UNKNOWN_OPAQUE, counterfactualOk,
                        false, false, true,
                        Optional.of("OPAQUE_GO_ERROR_HANDLER"),
                        Optional.empty(), TrustTier.SOURCE_SYNTAX);
            }
            if (outcome.propagates()) {
                return new MethodErrorSummary(
                        "go", fnName, cs.callsiteId(), path, cs.line(), cs.endLine(),
                        cs.targetCall(), cs.protocol(), baseErrors, outcome.propagatedErrors(),
                        outcome.transformations(), PropagationDisposition.PROPAGATES_ERROR, counterfactualOk,
                        false, false, false, Optional.empty(), Optional.empty(), TrustTier.SOURCE_SYNTAX);
            }
            return new MethodErrorSummary(
                    "go", fnName, cs.callsiteId(), path, cs.line(), cs.endLine(),
                    cs.targetCall(), cs.protocol(), baseErrors, Set.of(),
                    outcome.transformations(), PropagationDisposition.SUPPRESSED_FAIL_OPEN, counterfactualOk,
                    false, false, false, Optional.empty(),
                    Optional.of("GO_ERR_SUPPRESSED_DEFAULT"), TrustTier.SOURCE_SYNTAX);
        }

        // Case 7: `err` returned directly in a subsequent `return ..., err` statement without an `if`
        if (!cs.errVar().isEmpty() && isReturnedDirectlyAfter(cs, utf8)) {
            return new MethodErrorSummary(
                    "go", fnName, cs.callsiteId(), path, cs.line(), cs.endLine(),
                    cs.targetCall(), cs.protocol(), baseErrors, Set.of("error"),
                    List.of(), PropagationDisposition.PROPAGATES_ERROR, counterfactualOk,
                    false, false, false, Optional.empty(), Optional.empty(), TrustTier.SOURCE_SYNTAX);
        }

        // Case 8: Error captured in variable but never checked or returned -> fail-open
        return new MethodErrorSummary(
                "go", fnName, cs.callsiteId(), path, cs.line(), cs.endLine(),
                cs.targetCall(), cs.protocol(), baseErrors, Set.of(),
                List.of(), PropagationDisposition.SUPPRESSED_FAIL_OPEN, counterfactualOk,
                false, false, false, Optional.empty(),
                Optional.of("GO_ERR_UNCHECKED"), TrustTier.SOURCE_SYNTAX);
    }

    private record BlockOutcome(
            boolean propagates,
            boolean opaque,
            Set<String> propagatedErrors,
            List<ExceptionTransformation> transformations
    ) {}

    private static BlockOutcome inspectErrorBlock(
            TSNode block,
            String errVar,
            byte[] utf8,
            Set<String> definedFunctions) {
        return inspectErrorBlock(block, errVar, Set.of(), utf8, definedFunctions);
    }

    private static BlockOutcome inspectErrorBlock(
            TSNode block,
            String errVar,
            Set<String> peerErrorVars,
            byte[] utf8,
            Set<String> definedFunctions) {
        if (block == null || block.isNull()) {
            return new BlockOutcome(false, false, Set.of(), List.of());
        }
        String text = slice(utf8, block);
        var transforms = new ArrayList<ExceptionTransformation>();
        var propagated = new TreeSet<String>();

        // 1. Check HTTP 5xx error responses (`http.Error(..., 500)`, `WriteHeader(http.StatusInternalServerError)`)
        if ((text.contains("http.Error") || text.contains("WriteHeader") || text.contains("Status(") || text.contains("JSON("))
                && HTTP_5XX_PATTERN.matcher(text).find()) {
            transforms.add(new ExceptionTransformation("error", "HTTP_5XX", "GO_HTTP_5XX_WRITE"));
            propagated.add("HTTP_5XX");
            return new BlockOutcome(true, false, propagated, transforms);
        }

        // 2. Inspect return statements inside the block
        var returns = new ArrayList<TSNode>();
        collectNodesByType(block, "return_statement", returns);
        boolean anyReturnSuppresses = false;
        for (TSNode ret : returns) {
            String retText = slice(utf8, ret).strip();
            // Bare `return` in void/named-return function: check if error was assigned before return
            if ("return".equals(retText)) {
                continue;
            }
            String exprPart = retText.substring("return".length()).strip();
            List<String> retExprs = splitTopLevelComma(exprPart);
            String lastExpr = retExprs.isEmpty() ? exprPart : retExprs.getLast().strip();

            if (lastExpr.equals("nil")) {
                anyReturnSuppresses = true;
                continue;
            }
            if (lastExpr.contains("fmt.Errorf(")) {
                transforms.add(new ExceptionTransformation("error", "fmt.Errorf", "GO_ERR_WRAP"));
                propagated.add("fmt.Errorf");
                return new BlockOutcome(true, false, propagated, transforms);
            }
            if (lastExpr.contains("errors.Wrap(") || lastExpr.contains("errors.Wrapf(")
                    || lastExpr.contains("errors.WithMessage(") || lastExpr.contains("errors.Join(")) {
                transforms.add(new ExceptionTransformation("error", "errors.Wrap", "GO_ERR_WRAP"));
                propagated.add("errors.Wrap");
                return new BlockOutcome(true, false, propagated, transforms);
            }
            if (lastExpr.contains("status.Errorf(") || lastExpr.contains("status.Error(")) {
                transforms.add(new ExceptionTransformation("error", "grpc.StatusError", "GO_GRPC_STATUS_WRAP"));
                propagated.add("grpc.StatusError");
                return new BlockOutcome(true, false, propagated, transforms);
            }
            if (lastExpr.contains("errors.New(")) {
                transforms.add(new ExceptionTransformation("error", "errors.New", "GO_ERR_NEW"));
                propagated.add("errors.New");
                return new BlockOutcome(true, false, propagated, transforms);
            }
            if (!errVar.isEmpty() && containsIdentifier(lastExpr, errVar)) {
                // Check if passed to an opaque external error handler call like `return nil, handler.Handle(err)`
                if (isOpaqueHandlerCallExpr(lastExpr, errVar, definedFunctions)) {
                    return new BlockOutcome(false, true, Set.of("error"), transforms);
                }
                propagated.add("error");
                return new BlockOutcome(true, false, propagated, transforms);
            }
            // Returns another non-nil error variable (e.g. `return nil, err2` or peer conjunction var `e1`)
            if (lastExpr.matches("[A-Za-z_][A-Za-z0-9_]*")
                    && (peerErrorVars.contains(lastExpr) || lastExpr.toLowerCase(Locale.ROOT).contains("err"))) {
                propagated.add("error");
                return new BlockOutcome(true, false, propagated, transforms);
            }
            // Returns a default value without error
            anyReturnSuppresses = true;
        }

        // 3. Check for opaque external handler call passing errVar (e.g., `errorHandler.Handle(w, err)`)
        if (!anyReturnSuppresses && !errVar.isEmpty() && containsOpaqueHandlerStatement(block, errVar, utf8, definedFunctions)) {
            return new BlockOutcome(false, true, Set.of("error"), transforms);
        }

        // 4. Otherwise error was logged / defaulted / suppressed
        return new BlockOutcome(false, false, Set.of(), transforms);
    }

    private static boolean isOpaqueHandlerCallExpr(String expr, String errVar, Set<String> definedFunctions) {
        String s = expr.strip();
        if (s.equals(errVar)) return false;
        int paren = s.indexOf('(');
        if (paren <= 0 || !s.endsWith(")")) return false;
        String callee = s.substring(0, paren).strip();
        if (callee.startsWith("fmt.") || callee.startsWith("errors.") || callee.startsWith("status.")) {
            return false;
        }
        String simpleName = callee.contains(".") ? callee.substring(callee.lastIndexOf('.') + 1) : callee;
        if (definedFunctions.contains(simpleName)) {
            return false;
        }
        return containsIdentifier(s.substring(paren), errVar);
    }

    private static boolean containsOpaqueHandlerStatement(
            TSNode block,
            String errVar,
            byte[] utf8,
            Set<String> definedFunctions) {
        var calls = new ArrayList<TSNode>();
        collectNodesByType(block, "call_expression", calls);
        for (TSNode call : calls) {
            TSNode fn = call.getChildByFieldName("function");
            TSNode args = call.getChildByFieldName("arguments");
            if (fn == null || fn.isNull() || args == null || args.isNull()) continue;
            String argText = slice(utf8, args);
            if (!containsIdentifier(argText, errVar)) continue;
            String fnText = slice(utf8, fn).strip();
            String recv = "";
            String method = fnText;
            int dot = fnText.lastIndexOf('.');
            if (dot > 0) {
                recv = fnText.substring(0, dot).strip();
                method = fnText.substring(dot + 1).strip();
            }
            String baseRecv = recv.contains(".") ? recv.substring(recv.lastIndexOf('.') + 1) : recv;
            if (LOGGING_RECEIVERS.contains(baseRecv.toLowerCase(Locale.ROOT))
                    || LOGGING_METHODS.contains(method)) {
                continue;
            }
            if (!definedFunctions.contains(method)) {
                return true;
            }
        }
        return false;
    }

    private static boolean blockAlwaysReturnsErrorIgnoringNested(TSNode consequence, GoCallSite nestedFallback, byte[] utf8) {
        // Walk direct child statements of `consequence` AFTER `nestedFallback.stmtNode()`.
        // If there is an unconditional return of a non-nil error outside any `if err2 != nil`, primary still fails close.
        int count = consequence.getNamedChildCount();
        boolean seenFallback = false;
        for (int i = 0; i < count; i++) {
            TSNode child = consequence.getNamedChild(i);
            if (child.getStartByte() == nestedFallback.stmtNode().getStartByte()
                    && child.getEndByte() == nestedFallback.stmtNode().getEndByte()) {
                seenFallback = true;
                continue;
            }
            if (!seenFallback) continue;
            if ("return_statement".equals(child.getType())) {
                String retText = slice(utf8, child).strip();
                if (retText.length() > "return".length()) {
                    List<String> exprs = splitTopLevelComma(retText.substring("return".length()).strip());
                    String last = exprs.isEmpty() ? "" : exprs.getLast().strip();
                    if (!last.equals("nil") && !last.isEmpty()
                            && (last.contains("err") || last.contains("Error") || last.contains("Wrap"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static Set<String> detectErrgroupVars(TSNode body, byte[] utf8) {
        var vars = new HashSet<String>();
        var decls = new ArrayList<TSNode>();
        collectNodesByType(body, "short_var_declaration", decls);
        collectNodesByType(body, "var_spec", decls);
        collectNodesByType(body, "assignment_statement", decls);

        for (TSNode node : decls) {
            String text = slice(utf8, node);
            if (text.contains("errgroup.WithContext") || text.contains("errgroup.Group")) {
                TSNode left = node.getChildByFieldName("left");
                if (left != null && !left.isNull()) {
                    String lhs = slice(utf8, left).strip();
                    String firstVar = lhs.contains(",") ? lhs.substring(0, lhs.indexOf(',')).strip() : lhs;
                    if (firstVar.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                        vars.add(firstVar);
                    }
                } else {
                    TSNode name = node.getChildByFieldName("name");
                    if (name != null && !name.isNull()) {
                        vars.add(slice(utf8, name).strip());
                    }
                }
            }
        }
        return vars;
    }

    private record ErrgroupWaitOutcome(
            boolean found,
            boolean propagates,
            boolean suppressed,
            boolean opaque,
            List<ExceptionTransformation> transformations
    ) {
        static ErrgroupWaitOutcome missing() {
            return new ErrgroupWaitOutcome(false, false, false, true, List.of());
        }
    }

    private static Map<String, ErrgroupWaitOutcome> analyzeErrgroupWaits(
            TSNode body,
            byte[] utf8,
            Set<String> errgroupVars,
            Set<String> definedFunctions) {
        var result = new HashMap<String, ErrgroupWaitOutcome>();
        if (errgroupVars.isEmpty()) return result;

        var calls = new ArrayList<TSNode>();
        collectNodesByType(body, "call_expression", calls);
        for (TSNode call : calls) {
            TSNode fn = call.getChildByFieldName("function");
            if (fn == null || fn.isNull()) continue;
            String fnText = slice(utf8, fn).strip();
            int dot = fnText.lastIndexOf('.');
            if (dot <= 0) continue;
            String recv = fnText.substring(0, dot).strip();
            String method = fnText.substring(dot + 1).strip();
            if (!"Wait".equals(method) || !errgroupVars.contains(recv)) continue;

            TSNode stmt = enclosingStatement(call);
            if (stmt == null || stmt.isNull()) continue;
            String stmtType = stmt.getType();

            // `return g.Wait()` or `return nil, g.Wait()`
            if ("return_statement".equals(stmtType)) {
                result.put(recv, new ErrgroupWaitOutcome(true, true, false, false, List.of()));
                continue;
            }
            // Bare `g.Wait()` -> ignored error
            if ("expression_statement".equals(stmtType)) {
                result.put(recv, new ErrgroupWaitOutcome(true, false, true, false, List.of()));
                continue;
            }
            // `_ = g.Wait()`
            String errVar = extractAssignedErrorVar(stmt, utf8);
            if ("_".equals(errVar)) {
                result.put(recv, new ErrgroupWaitOutcome(true, false, true, false, List.of()));
                continue;
            }
            // `if err := g.Wait(); err != nil { ... }` or `err := g.Wait(); if err != nil { ... }`
            TSNode enclosingIf = enclosingIfInitializer(stmt);
            TSNode guardIf = enclosingIf != null ? enclosingIf : findFollowingGuardIf(stmt, errVar, utf8);
            if (guardIf != null && !guardIf.isNull()) {
                TSNode consequence = guardIf.getChildByFieldName("consequence");
                BlockOutcome outcome = inspectErrorBlock(consequence, errVar, utf8, definedFunctions);
                if (outcome.opaque()) {
                    result.put(recv, new ErrgroupWaitOutcome(true, false, false, true, outcome.transformations()));
                } else if (outcome.propagates()) {
                    result.put(recv, new ErrgroupWaitOutcome(true, true, false, false, outcome.transformations()));
                } else {
                    result.put(recv, new ErrgroupWaitOutcome(true, false, true, false, outcome.transformations()));
                }
            } else if (!errVar.isEmpty() && isVarReturnedAfterStmt(stmt, errVar, utf8)) {
                result.put(recv, new ErrgroupWaitOutcome(true, true, false, false, List.of()));
            } else {
                result.put(recv, new ErrgroupWaitOutcome(true, false, true, false, List.of()));
            }
        }
        return result;
    }

    private static void collectCallSites(
            TSNode node,
            String path,
            byte[] utf8,
            Set<String> errgroupVars,
            TSNode currentIf,
            String activeErrgroupVar,
            boolean insideErrgroupClosure,
            List<GoCallSite> out) {
        String type = node.getType();

        // Detect `g.Go(...)` calls on an errgroup variable
        if ("call_expression".equals(type)) {
            TSNode fn = node.getChildByFieldName("function");
            if (fn != null && !fn.isNull()) {
                String fnText = slice(utf8, fn).strip();
                int dot = fnText.lastIndexOf('.');
                if (dot > 0) {
                    String recv = fnText.substring(0, dot).strip();
                    String method = fnText.substring(dot + 1).strip();
                    if ("Go".equals(method) && errgroupVars.contains(recv)) {
                        handleErrgroupGoCall(node, recv, path, utf8, out);
                        return;
                    }
                }
            }
        }

        TSNode nextIf = "if_statement".equals(type) ? node : currentIf;

        if ("short_var_declaration".equals(type) || "assignment_statement".equals(type)) {
            TSNode right = node.getChildByFieldName("right");
            if (right != null && !right.isNull()) {
                TSNode call = findDirectCallExpression(right);
                if (call != null && !call.isNull()) {
                    Optional<RpcCallInfo> info = classifyRpcCall(call, utf8);
                    if (info.isPresent()) {
                        String errVar = extractAssignedErrorVar(node, utf8);
                        boolean discarded = "_".equals(errVar);
                        int line = call.getStartPoint().getRow() + 1;
                        int endLine = call.getEndPoint().getRow() + 1;
                        String id = path + ":" + line + "#" + info.get().target();
                        out.add(new GoCallSite(
                                id, line, endLine, info.get().target(), info.get().protocol(),
                                errVar, discarded, node,
                                enclosingIfInitializer(node),
                                activeErrgroupVar, insideErrgroupClosure, false, false));
                    }
                }
            }
            return;
        }

        if ("return_statement".equals(type)) {
            TSNode call = findDirectCallExpression(node);
            if (call != null && !call.isNull()) {
                Optional<RpcCallInfo> info = classifyRpcCall(call, utf8);
                if (info.isPresent()) {
                    int line = call.getStartPoint().getRow() + 1;
                    int endLine = call.getEndPoint().getRow() + 1;
                    String id = path + ":" + line + "#" + info.get().target();
                    out.add(new GoCallSite(
                            id, line, endLine, info.get().target(), info.get().protocol(),
                            "", false, node, null,
                            activeErrgroupVar, insideErrgroupClosure, true, false));
                }
            }
            return;
        }

        if ("expression_statement".equals(type)) {
            TSNode call = findDirectCallExpression(node);
            if (call != null && !call.isNull()) {
                // Handle `g.Go(...)` inside expression_statement before classifying as a plain call
                TSNode fn = call.getChildByFieldName("function");
                if (fn != null && !fn.isNull()) {
                    String fnText = slice(utf8, fn).strip();
                    int dot = fnText.lastIndexOf('.');
                    if (dot > 0) {
                        String recv = fnText.substring(0, dot).strip();
                        String method = fnText.substring(dot + 1).strip();
                        if ("Go".equals(method) && errgroupVars.contains(recv)) {
                            handleErrgroupGoCall(call, recv, path, utf8, out);
                            return;
                        }
                    }
                }
                Optional<RpcCallInfo> info = classifyRpcCall(call, utf8);
                if (info.isPresent()) {
                    int line = call.getStartPoint().getRow() + 1;
                    int endLine = call.getEndPoint().getRow() + 1;
                    String id = path + ":" + line + "#" + info.get().target();
                    out.add(new GoCallSite(
                            id, line, endLine, info.get().target(), info.get().protocol(),
                            "_", true, node, null,
                            activeErrgroupVar, insideErrgroupClosure, false, false));
                }
            }
            return;
        }

        int count = node.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            collectCallSites(node.getNamedChild(i), path, utf8, errgroupVars, nextIf,
                    activeErrgroupVar, insideErrgroupClosure, out);
        }
    }

    private static void handleErrgroupGoCall(
            TSNode goCall,
            String errgroupVar,
            String path,
            byte[] utf8,
            List<GoCallSite> out) {
        TSNode args = goCall.getChildByFieldName("arguments");
        if (args == null || args.isNull() || args.getNamedChildCount() == 0) {
            return;
        }
        TSNode arg0 = args.getNamedChild(0);
        if (!"func_literal".equals(arg0.getType())) {
            // Non-inline opaque async function passed to errgroup.Go(fn)
            String fnRef = slice(utf8, arg0).strip();
            int line = goCall.getStartPoint().getRow() + 1;
            int endLine = goCall.getEndPoint().getRow() + 1;
            out.add(new GoCallSite(
                    path + ":" + line + "#" + errgroupVar + ".Go(" + fnRef + ")",
                    line, endLine, fnRef, "GO_ERRGROUP_ASYNC",
                    "", false, goCall, null,
                    errgroupVar, true, false, true));
            return;
        }
        TSNode closureBody = arg0.getChildByFieldName("body");
        if (closureBody == null || closureBody.isNull()) {
            return;
        }
        var innerCalls = new ArrayList<GoCallSite>();
        collectCallSites(closureBody, path, utf8, Set.of(), null, errgroupVar, true, innerCalls);
        for (GoCallSite inner : innerCalls) {
            boolean returnsErr = doesClosurePropagateError(closureBody, inner, utf8);
            out.add(new GoCallSite(
                    inner.callsiteId(),
                    inner.line(),
                    inner.endLine(),
                    inner.targetCall(),
                    inner.protocol(),
                    inner.errVar(),
                    inner.discardedError(),
                    inner.stmtNode(),
                    inner.enclosingIfNode(),
                    errgroupVar,
                    true,
                    returnsErr,
                    false));
        }
    }

    private static boolean doesClosurePropagateError(TSNode closureBody, GoCallSite inner, byte[] utf8) {
        if (inner.discardedError() || "_".equals(inner.errVar())) {
            return false;
        }
        if ("return_statement".equals(inner.stmtNode().getType())) {
            return true;
        }
        TSNode guard = findGuardIfForCall(inner, utf8);
        if (guard != null && !guard.isNull()) {
            BlockOutcome out = inspectErrorBlock(guard.getChildByFieldName("consequence"), inner.errVar(), utf8, Set.of());
            return out.propagates();
        }
        return !inner.errVar().isEmpty() && isReturnedDirectlyAfter(inner, utf8);
    }

    private record RpcCallInfo(String target, String protocol) {}

    private static Optional<RpcCallInfo> classifyRpcCall(TSNode call, byte[] utf8) {
        TSNode fn = call.getChildByFieldName("function");
        if (fn == null || fn.isNull()) return Optional.empty();
        if (!"selector_expression".equals(fn.getType())) {
            return Optional.empty();
        }
        String full = slice(utf8, fn).strip();
        int dot = full.lastIndexOf('.');
        if (dot <= 0 || dot + 1 >= full.length()) {
            return Optional.empty();
        }
        String operand = full.substring(0, dot).strip();
        String method = full.substring(dot + 1).strip();
        String baseRecv = operand.contains(".") ? operand.substring(operand.lastIndexOf('.') + 1) : operand;
        baseRecv = baseRecv.replaceAll("\\(.*\\)$", "").strip();

        if (NON_RPC_RECEIVERS.contains(baseRecv.toLowerCase(Locale.ROOT)) && !"http".equals(baseRecv)) {
            return Optional.empty();
        }
        if (NON_RPC_METHODS.contains(method)) {
            return Optional.empty();
        }
        String protocol;
        String lowerRecv = baseRecv.toLowerCase(Locale.ROOT);
        if ("http".equals(baseRecv) || lowerRecv.contains("http") || method.equals("Get") || method.equals("Post") || method.equals("Do")) {
            protocol = "HTTP_CLIENT";
        } else if (lowerRecv.contains("grpc") || lowerRecv.contains("stub") || lowerRecv.contains("client") || lowerRecv.contains("svc") || lowerRecv.contains("service") || lowerRecv.contains("fallback") || lowerRecv.contains("primary")) {
            protocol = "GRPC_STUB";
        } else {
            // Check if first argument looks like context (`ctx`) -> Go RPC idiom
            TSNode args = call.getChildByFieldName("arguments");
            if (args != null && !args.isNull() && slice(utf8, args).contains("ctx")) {
                protocol = "GO_RPC";
            } else {
                protocol = "GO_RPC";
            }
        }
        return Optional.of(new RpcCallInfo(full, protocol));
    }

    private static Set<String> detectConjunctionErrorVars(TSNode body, byte[] utf8) {
        var vars = new HashSet<String>();
        var ifs = new ArrayList<TSNode>();
        collectNodesByType(body, "if_statement", ifs);
        for (TSNode ifNode : ifs) {
            TSNode cond = ifNode.getChildByFieldName("condition");
            if (cond == null || cond.isNull()) continue;
            String condText = slice(utf8, cond);
            if (!condText.contains("&&")) continue;
            var found = new LinkedHashSet<String>();
            Matcher m = ERR_NEQ_NIL_PATTERN.matcher(condText);
            while (m.find()) {
                found.add(m.group(1));
            }
            if (found.size() >= 2) {
                vars.addAll(found);
            }
        }
        return vars;
    }

    private static TSNode findConjunctionIf(TSNode body, String errVar, byte[] utf8) {
        var ifs = new ArrayList<TSNode>();
        collectNodesByType(body, "if_statement", ifs);
        for (TSNode ifNode : ifs) {
            TSNode cond = ifNode.getChildByFieldName("condition");
            if (cond == null || cond.isNull()) continue;
            String condText = slice(utf8, cond);
            if (!condText.contains("&&")) continue;
            var found = new LinkedHashSet<String>();
            Matcher m = ERR_NEQ_NIL_PATTERN.matcher(condText);
            while (m.find()) {
                found.add(m.group(1));
            }
            if (found.size() >= 2 && found.contains(errVar)) {
                return ifNode;
            }
        }
        return null;
    }

    private static TSNode findGuardIfForCall(GoCallSite cs, byte[] utf8) {
        if (cs.enclosingIfNode() != null && !cs.enclosingIfNode().isNull()) {
            TSNode cond = cs.enclosingIfNode().getChildByFieldName("condition");
            if (cond != null && !cond.isNull() && conditionChecksNotNil(slice(utf8, cond), cs.errVar())) {
                return cs.enclosingIfNode();
            }
        }
        return findFollowingGuardIf(cs.stmtNode(), cs.errVar(), utf8);
    }

    private static TSNode findFollowingGuardIf(TSNode stmt, String errVar, byte[] utf8) {
        if (stmt == null || stmt.isNull() || errVar == null || errVar.isEmpty()) return null;
        TSNode parent = stmt.getParent();
        if (parent == null || parent.isNull()) return null;
        int count = parent.getNamedChildCount();
        boolean afterStmt = false;
        for (int i = 0; i < count; i++) {
            TSNode sibling = parent.getNamedChild(i);
            if (!afterStmt) {
                if (sibling.getStartByte() == stmt.getStartByte() && sibling.getEndByte() == stmt.getEndByte()) {
                    afterStmt = true;
                }
                continue;
            }
            String sType = sibling.getType();
            if ("if_statement".equals(sType)) {
                TSNode cond = sibling.getChildByFieldName("condition");
                if (cond != null && !cond.isNull() && conditionChecksNotNil(slice(utf8, cond), errVar)) {
                    return sibling;
                }
            }
            // Stop scanning if errVar is reassigned
            if ("short_var_declaration".equals(sType) || "assignment_statement".equals(sType)) {
                String reassigned = extractAssignedErrorVar(sibling, utf8);
                if (errVar.equals(reassigned)) {
                    break;
                }
            }
        }
        return null;
    }

    private static boolean isReturnedDirectlyAfter(GoCallSite cs, byte[] utf8) {
        return isVarReturnedAfterStmt(cs.stmtNode(), cs.errVar(), utf8);
    }

    private static boolean isVarReturnedAfterStmt(TSNode stmt, String errVar, byte[] utf8) {
        if (stmt == null || stmt.isNull() || errVar == null || errVar.isEmpty()) return false;
        TSNode parent = stmt.getParent();
        if (parent == null || parent.isNull()) return false;
        int count = parent.getNamedChildCount();
        boolean afterStmt = false;
        for (int i = 0; i < count; i++) {
            TSNode sibling = parent.getNamedChild(i);
            if (!afterStmt) {
                if (sibling.getStartByte() == stmt.getStartByte() && sibling.getEndByte() == stmt.getEndByte()) {
                    afterStmt = true;
                }
                continue;
            }
            String sType = sibling.getType();
            if ("return_statement".equals(sType)) {
                String retText = slice(utf8, sibling);
                if (containsIdentifier(retText, errVar)) {
                    return true;
                }
            }
            if ("short_var_declaration".equals(sType) || "assignment_statement".equals(sType)) {
                if (errVar.equals(extractAssignedErrorVar(sibling, utf8))) {
                    break;
                }
            }
        }
        return false;
    }

    private static boolean hasNormalOkReturnPath(TSNode body, byte[] utf8) {
        var returns = new ArrayList<TSNode>();
        collectNodesByType(body, "return_statement", returns);
        if (returns.isEmpty()) {
            // Void/HTTP handler without explicit return: check it doesn't ONLY write 5xx unconditionally
            String bodyText = slice(utf8, body);
            boolean has5xx = HTTP_5XX_PATTERN.matcher(bodyText).find();
            if (!has5xx) return true;
            // If 5xx is guarded inside an `if`, normal path exists
            var ifs = new ArrayList<TSNode>();
            collectNodesByType(body, "if_statement", ifs);
            return !ifs.isEmpty();
        }
        for (TSNode ret : returns) {
            String retText = slice(utf8, ret).strip();
            if ("return".equals(retText)) {
                return true;
            }
            String exprPart = retText.substring("return".length()).strip();
            List<String> exprs = splitTopLevelComma(exprPart);
            String last = exprs.isEmpty() ? exprPart : exprs.getLast().strip();
            if ("nil".equals(last)) {
                return true;
            }
            // Non-error return (doesn't mention error constructors or err variable)
            if (!last.contains("fmt.Errorf") && !last.contains("errors.") && !last.contains("status.Error")
                    && !last.matches("(?i).*err.*")) {
                return true;
            }
        }
        return false;
    }

    private static String extractAssignedErrorVar(TSNode declOrAssign, byte[] utf8) {
        TSNode left = declOrAssign.getChildByFieldName("left");
        if (left == null || left.isNull()) return "";
        String lhs = slice(utf8, left).strip();
        List<String> parts = splitTopLevelComma(lhs);
        if (parts.isEmpty()) return "";
        String last = parts.getLast().strip();
        if (last.equals("_")) return "_";
        return last.matches("[A-Za-z_][A-Za-z0-9_]*") ? last : "";
    }

    private static TSNode findDirectCallExpression(TSNode node) {
        if ("call_expression".equals(node.getType())) {
            return node;
        }
        int count = node.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            TSNode child = node.getNamedChild(i);
            if ("func_literal".equals(child.getType())) continue;
            TSNode found = findDirectCallExpression(child);
            if (found != null) return found;
        }
        return null;
    }

    private static TSNode enclosingStatement(TSNode node) {
        TSNode cur = node;
        while (cur != null && !cur.isNull()) {
            String t = cur.getType();
            if (t.endsWith("_statement") || "short_var_declaration".equals(t) || "var_declaration".equals(t)) {
                return cur;
            }
            cur = cur.getParent();
        }
        return null;
    }

    private static TSNode enclosingIfInitializer(TSNode stmt) {
        TSNode parent = stmt.getParent();
        if (parent != null && !parent.isNull() && "if_statement".equals(parent.getType())) {
            TSNode init = parent.getChildByFieldName("initializer");
            if (init != null && !init.isNull()
                    && init.getStartByte() == stmt.getStartByte()
                    && init.getEndByte() == stmt.getEndByte()) {
                return parent;
            }
        }
        return null;
    }

    private static boolean isDescendant(TSNode node, TSNode ancestor) {
        if (node == null || ancestor == null || node.isNull() || ancestor.isNull()) return false;
        return node.getStartByte() >= ancestor.getStartByte() && node.getEndByte() <= ancestor.getEndByte();
    }

    private static boolean conditionChecksNotNil(String condText, String errVar) {
        if (errVar == null || errVar.isEmpty()) return false;
        return Pattern.compile("\\b" + Pattern.quote(errVar) + "\\s*!=\\s*nil\\b").matcher(condText).find();
    }

    private static boolean containsIdentifier(String text, String ident) {
        if (ident == null || ident.isEmpty()) return false;
        return Pattern.compile("\\b" + Pattern.quote(ident) + "\\b").matcher(text).find();
    }

    private static String buildFunctionId(String path, TSNode fnNode, byte[] utf8) {
        TSNode nameNode = fnNode.getChildByFieldName("name");
        String name = (nameNode != null && !nameNode.isNull()) ? slice(utf8, nameNode).strip() : "anonymous";
        TSNode recvNode = fnNode.getChildByFieldName("receiver");
        if (recvNode != null && !recvNode.isNull()) {
            String recvText = slice(utf8, recvNode).replaceAll("[()*]", " ").strip();
            String[] tokens = recvText.split("\\s+");
            String recvType = tokens.length > 0 ? tokens[tokens.length - 1] : "";
            if (!recvType.isEmpty()) {
                return path + "#" + recvType + "." + name;
            }
        }
        return path + "#" + name;
    }

    private static void collectNodesByType(TSNode root, String targetType, List<TSNode> out) {
        if (root == null || root.isNull()) return;
        if (targetType.equals(root.getType())) {
            out.add(root);
        }
        int count = root.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            collectNodesByType(root.getNamedChild(i), targetType, out);
        }
    }

    private static List<String> splitTopLevelComma(String text) {
        var result = new ArrayList<String>();
        int depth = 0;
        int start = 0;
        boolean inQuote = false;
        char quoteChar = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuote) {
                if (c == '\\' && i + 1 < text.length()) {
                    i++;
                } else if (c == quoteChar) {
                    inQuote = false;
                }
                continue;
            }
            if (c == '"' || c == '\'' || c == '`') {
                inQuote = true;
                quoteChar = c;
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth = Math.max(0, depth - 1);
            } else if (c == ',' && depth == 0) {
                result.add(text.substring(start, i));
                start = i + 1;
            }
        }
        if (start <= text.length()) {
            result.add(text.substring(start));
        }
        return result;
    }

    private static String slice(byte[] utf8, TSNode node) {
        int start = Math.max(0, Math.min(utf8.length, node.getStartByte()));
        int end = Math.max(start, Math.min(utf8.length, node.getEndByte()));
        return new String(utf8, start, end - start, StandardCharsets.UTF_8);
    }
}
