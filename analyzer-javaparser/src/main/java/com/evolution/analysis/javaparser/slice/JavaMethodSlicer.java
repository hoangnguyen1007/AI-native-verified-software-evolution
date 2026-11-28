package com.evolution.analysis.javaparser.slice;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.polyglot.FailCloseResilienceAnalyzer;
import com.evolution.analysis.polyglot.FailCloseResilienceAnalyzer.ExceptionTransformation;
import com.evolution.analysis.polyglot.FailCloseResilienceAnalyzer.MethodErrorSummary;
import com.evolution.analysis.polyglot.FailCloseResilienceAnalyzer.PropagationDisposition;
import com.evolution.analysis.query.trace.MethodSliceResult;
import com.evolution.analysis.query.trace.MethodSliceResult.SliceDirection;
import com.evolution.analysis.query.trace.MethodSliceResult.SliceStatement;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.UnionType;

import java.util.*;

/**
 * Lazy, pure-Java intra-method CFG / DFG / CDG program slicer powered strictly by bundled
 * JavaParser 3.28.2.
 *
 * <p>Guarantees:
 * <ul>
 *   <li>Zero new dependencies, zero host {@code javac}/APT/target execution ({@code I1}).</li>
 *   <li>Zero mutation of canonical {@code KnowledgeGraph} ({@code I4}).</li>
 *   <li>Honest {@link TrustTier#SOURCE_SYNTAX} epistemic labeling and explicit passive
 *       intra-procedural limitations ({@code I3}).</li>
 * </ul>
 */
public final class JavaMethodSlicer {

    public static final int DEFAULT_MAX_STATEMENTS = 80;
    public static final int MIN_MAX_STATEMENTS = 1;
    public static final int MAX_MAX_STATEMENTS = 500;

    private static final String BASE_LIMITATION =
            "Intra-procedural passive AST slice (JavaParser SOURCE_SYNTAX): inter-procedural call targets, "
                    + "heap aliasing, reflection, and bytecode/APT-generated code are not traversed.";

    private JavaMethodSlicer() {}

    /** Convenience overload with default {@code maxStatements} and line-based method lookup. */
    public static MethodSliceResult slice(
            String path,
            String javaSource,
            int seedLine,
            SliceDirection direction) {
        return slice(path, javaSource, Optional.empty(), seedLine, direction, DEFAULT_MAX_STATEMENTS);
    }

    /**
     * Parses {@code javaSource}, locates the target method or constructor, constructs its
     * intra-method CFG, DFG (reaching definitions), and CDG (control dependence), and returns a
     * bounded {@link MethodSliceResult} sorted by source line.
     */
    public static MethodSliceResult slice(
            String path,
            String javaSource,
            Optional<String> methodSignature,
            int seedLine,
            SliceDirection direction,
            int maxStatements) {
        String safePath = Objects.requireNonNull(path, "path");
        String safeSource = Objects.requireNonNull(javaSource, "javaSource");
        Optional<String> safeSig = methodSignature == null
                ? Optional.empty()
                : methodSignature.map(String::strip).filter(s -> !s.isEmpty());
        SliceDirection safeDirection = direction == null ? SliceDirection.BACKWARD : direction;
        int clampedMax = maxStatements <= 0
                ? DEFAULT_MAX_STATEMENTS
                : Math.max(MIN_MAX_STATEMENTS, Math.min(MAX_MAX_STATEMENTS, maxStatements));

        var limitations = new ArrayList<String>();
        limitations.add(BASE_LIMITATION);

        if (safeSource.isBlank()) {
            limitations.add("Source text is empty.");
            return new MethodSliceResult(
                    safePath, safeSig.orElse(""), seedLine, safeDirection,
                    TrustTier.SOURCE_SYNTAX, false, 0, List.of(), List.of(), limitations);
        }

        ParserConfiguration config = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setAttributeComments(false);
        ParseResult<CompilationUnit> parseResult = new JavaParser(config).parse(safeSource);
        Optional<CompilationUnit> maybeCu = parseResult.getResult();
        if (maybeCu.isEmpty()) {
            limitations.add("JavaParser could not parse compilation unit.");
            return new MethodSliceResult(
                    safePath, safeSig.orElse(""), seedLine, safeDirection,
                    TrustTier.SOURCE_SYNTAX, false, 0, List.of(), List.of(), limitations);
        }

        CompilationUnit cu = maybeCu.get();
        CallableDeclaration<?> callable = locateCallable(cu, safeSig, seedLine);
        if (callable == null) {
            limitations.add("No enclosing method or constructor found for seed line " + seedLine
                    + safeSig.map(s -> " / signature '" + s + "'").orElse("") + ".");
            return new MethodSliceResult(
                    safePath, safeSig.orElse(""), seedLine, safeDirection,
                    TrustTier.SOURCE_SYNTAX, false, 0, List.of(), List.of(), limitations);
        }

        String resolvedSignature = callable.getSignature().asString();
        Optional<BlockStmt> bodyOpt = extractBody(callable);
        if (bodyOpt.isEmpty()) {
            limitations.add("Target declaration has no executable body (abstract or interface method).");
            return new MethodSliceResult(
                    safePath, resolvedSignature, seedLine, safeDirection,
                    TrustTier.SOURCE_SYNTAX, false, 0, List.of(), List.of(), limitations);
        }

        Set<String> enclosingFields = collectEnclosingFields(callable);
        Set<String> parameters = new LinkedHashSet<>();
        for (Parameter p : callable.getParameters()) {
            parameters.add(p.getNameAsString());
        }

        // Build CFG + CDG + Def/Use IR over the method body
        var builder = new IntraMethodGraphBuilder(enclosingFields, parameters);
        int headerBeginLine = callable.getBegin().map(p -> p.line).orElse(seedLine);
        boolean seededOnHeader = isSeedOnCallableHeader(callable, bodyOpt.get(), seedLine);
        int headerNodeId = -1;
        if (seededOnHeader) {
            headerNodeId = builder.addParameterHeaderNode(callable, headerBeginLine, parameters);
        }
        builder.buildBody(bodyOpt.get(), headerNodeId);

        int totalStatements = builder.nodes.size();
        if (totalStatements == 0) {
            limitations.add("Method body contains no executable statements.");
            return new MethodSliceResult(
                    safePath, resolvedSignature, seedLine, safeDirection,
                    TrustTier.SOURCE_SYNTAX, false, 0, List.of(), List.of(), limitations);
        }

        // Compute reaching definitions (DFG) over CFG
        var dataDeps = builder.computeDataDependencies(); // target -> Set<defSource>
        var controlDeps = builder.controlDeps;            // target -> Set<predicateSource>

        // Locate seed node(s)
        List<Integer> seedNodeIds = findSeedNodes(builder.nodes, seedLine, headerNodeId, limitations);
        if (seedNodeIds.isEmpty()) {
            limitations.add("No statement inside " + resolvedSignature + " matched line " + seedLine + ".");
            return new MethodSliceResult(
                    safePath, resolvedSignature, seedLine, safeDirection,
                    TrustTier.SOURCE_SYNTAX, false, totalStatements, List.of(), List.of(), limitations);
        }

        // Build forward adjacency maps (source -> targets)
        var forwardData = invertMap(dataDeps);
        var forwardControl = invertMap(controlDeps);

        var seedSet = new LinkedHashSet<>(seedNodeIds);
        var sliceDistances = new HashMap<Integer, Integer>();
        var reachedByData = new HashSet<Integer>();
        var reachedByControl = new HashSet<Integer>();

        for (int s : seedSet) {
            sliceDistances.put(s, 0);
        }

        if (safeDirection == SliceDirection.BACKWARD || safeDirection == SliceDirection.BOTH) {
            traverseSlice(seedSet, dataDeps, controlDeps, sliceDistances, reachedByData, reachedByControl);
        }
        if (safeDirection == SliceDirection.FORWARD || safeDirection == SliceDirection.BOTH) {
            traverseSlice(seedSet, forwardData, forwardControl, sliceDistances, reachedByData, reachedByControl);
        }

        // Bound to clampedMax statements (prioritize closest BFS distance from seed, then line order)
        boolean truncated = sliceDistances.size() > clampedMax;
        Set<Integer> retainedIds;
        if (truncated) {
            limitations.add("Slice truncated to maxStatements=" + clampedMax
                    + " (out of " + sliceDistances.size() + " reachable statements).");
            retainedIds = sliceDistances.entrySet().stream()
                    .sorted(Comparator
                            .<Map.Entry<Integer, Integer>>comparingInt(Map.Entry::getValue)
                            .thenComparingInt(e -> builder.nodes.get(e.getKey()).line)
                            .thenComparingInt(Map.Entry::getKey))
                    .limit(clampedMax)
                    .map(Map.Entry::getKey)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        } else {
            retainedIds = sliceDistances.keySet();
        }

        // Emit sorted SliceStatements with intra-slice dependsOnLines & dependencyKind
        var sortedIds = retainedIds.stream()
                .sorted(Comparator
                        .comparingInt((Integer id) -> builder.nodes.get(id).line)
                        .thenComparingInt(id -> builder.nodes.get(id).endLine)
                        .thenComparingInt(id -> id))
                .toList();

        var resultStatements = new ArrayList<SliceStatement>(sortedIds.size());
        var lineSet = new TreeSet<Integer>();

        for (int id : sortedIds) {
            StmtNode node = builder.nodes.get(id);
            var depLines = new TreeSet<Integer>();
            boolean hasDataEdge = false;
            boolean hasControlEdge = false;

            for (int dSrc : dataDeps.getOrDefault(id, Set.of())) {
                if (retainedIds.contains(dSrc) && dSrc != id) {
                    depLines.add(builder.nodes.get(dSrc).line);
                    hasDataEdge = true;
                }
            }
            for (int cSrc : controlDeps.getOrDefault(id, Set.of())) {
                if (retainedIds.contains(cSrc) && cSrc != id) {
                    depLines.add(builder.nodes.get(cSrc).line);
                    hasControlEdge = true;
                }
            }

            String depKind;
            if (seedSet.contains(id)) {
                depKind = "SEED";
            } else {
                boolean d = reachedByData.contains(id) || hasDataEdge;
                boolean c = reachedByControl.contains(id) || hasControlEdge;
                if (d && c) {
                    depKind = "BOTH";
                } else if (c) {
                    depKind = "CONTROL";
                } else {
                    depKind = "DATA";
                }
            }

            resultStatements.add(new SliceStatement(
                    node.line,
                    node.endLine,
                    node.kind,
                    node.code,
                    List.copyOf(depLines),
                    depKind));
            for (int l = node.line; l <= node.endLine; l++) {
                lineSet.add(l);
            }
        }

        return new MethodSliceResult(
                safePath,
                resolvedSignature,
                seedLine,
                safeDirection,
                TrustTier.SOURCE_SYNTAX,
                truncated,
                totalStatements,
                resultStatements,
                List.copyOf(lineSet),
                limitations);
    }

    private static void traverseSlice(
            Set<Integer> seeds,
            Map<Integer, Set<Integer>> dataEdges,
            Map<Integer, Set<Integer>> controlEdges,
            Map<Integer, Integer> distances,
            Set<Integer> reachedByData,
            Set<Integer> reachedByControl) {
        var queue = new ArrayDeque<Integer>(seeds);
        while (!queue.isEmpty()) {
            int cur = queue.poll();
            int nextDist = distances.getOrDefault(cur, 0) + 1;
            for (int next : dataEdges.getOrDefault(cur, Set.of())) {
                reachedByData.add(next);
                if (!distances.containsKey(next)) {
                    distances.put(next, nextDist);
                    queue.add(next);
                } else if (nextDist < distances.get(next)) {
                    distances.put(next, nextDist);
                }
            }
            for (int next : controlEdges.getOrDefault(cur, Set.of())) {
                reachedByControl.add(next);
                if (!distances.containsKey(next)) {
                    distances.put(next, nextDist);
                    queue.add(next);
                } else if (nextDist < distances.get(next)) {
                    distances.put(next, nextDist);
                }
            }
        }
    }

    private static Map<Integer, Set<Integer>> invertMap(Map<Integer, Set<Integer>> deps) {
        var forward = new HashMap<Integer, Set<Integer>>();
        for (var e : deps.entrySet()) {
            int target = e.getKey();
            for (int src : e.getValue()) {
                forward.computeIfAbsent(src, k -> new LinkedHashSet<>()).add(target);
            }
        }
        return forward;
    }

    private static List<Integer> findSeedNodes(
            List<StmtNode> nodes,
            int seedLine,
            int headerNodeId,
            List<String> limitations) {
        var exact = new ArrayList<Integer>();
        var containing = new ArrayList<Integer>();
        for (StmtNode n : nodes) {
            if (n.line == seedLine) {
                exact.add(n.id);
            } else if (n.line <= seedLine && seedLine <= n.endLine) {
                containing.add(n.id);
            }
        }
        if (!exact.isEmpty()) return exact;
        if (!containing.isEmpty()) {
            // Prefer narrowest containing span
            containing.sort(Comparator.comparingInt(id -> nodes.get(id).endLine - nodes.get(id).line));
            return List.of(containing.getFirst());
        }
        if (headerNodeId >= 0) {
            return List.of(headerNodeId);
        }
        // Fallback: closest executable statement in the method
        StmtNode nearest = null;
        int bestDist = Integer.MAX_VALUE;
        for (StmtNode n : nodes) {
            int dist = Math.min(Math.abs(n.line - seedLine), Math.abs(n.endLine - seedLine));
            if (dist < bestDist) {
                bestDist = dist;
                nearest = n;
            }
        }
        if (nearest != null) {
            limitations.add("Seed line " + seedLine + " had no direct executable statement; snapped to nearest statement at line " + nearest.line + ".");
            return List.of(nearest.id);
        }
        return List.of();
    }

    private static CallableDeclaration<?> locateCallable(
            CompilationUnit cu,
            Optional<String> methodSignature,
            int seedLine) {
        var allCallables = new ArrayList<CallableDeclaration<?>>();
        allCallables.addAll(cu.findAll(MethodDeclaration.class));
        allCallables.addAll(cu.findAll(ConstructorDeclaration.class));
        if (allCallables.isEmpty()) {
            return null;
        }

        if (methodSignature.isPresent()) {
            String target = methodSignature.get();
            String normalizedTarget = normalizeSig(target);
            var sigMatches = allCallables.stream()
                    .filter(c -> matchesSignature(c, target, normalizedTarget))
                    .toList();
            if (!sigMatches.isEmpty()) {
                return pickBestBySeedLine(sigMatches, seedLine);
            }
        }

        var containing = allCallables.stream()
                .filter(c -> containsLine(c, seedLine))
                .toList();
        if (!containing.isEmpty()) {
            return pickBestBySeedLine(containing, seedLine);
        }
        return null;
    }

    private static CallableDeclaration<?> pickBestBySeedLine(List<CallableDeclaration<?>> candidates, int seedLine) {
        // Prefer innermost callable containing seedLine (smallest line span), else first in source order
        return candidates.stream()
                .sorted(Comparator
                        .<CallableDeclaration<?>>comparingInt(c -> containsLine(c, seedLine) ? 0 : 1)
                        .thenComparingInt(JavaMethodSlicer::lineSpan)
                        .thenComparingInt(c -> c.getBegin().map(p -> p.line).orElse(Integer.MAX_VALUE)))
                .findFirst()
                .orElse(candidates.getFirst());
    }

    private static boolean matchesSignature(CallableDeclaration<?> c, String rawTarget, String normalizedTarget) {
        String sig = c.getSignature().asString();
        if (sig.equals(rawTarget) || normalizeSig(sig).equals(normalizedTarget)) return true;
        String sourceSig = c.getNameAsString() + "("
                + String.join(", ", c.getParameters().stream().map(p -> p.getTypeAsString()).toList()) + ")";
        if (sourceSig.equals(rawTarget) || normalizeSig(sourceSig).equals(normalizedTarget)) return true;
        if (c.getNameAsString().equals(rawTarget)) return true;
        String owner = c.findAncestor(TypeDeclaration.class)
                .map(TypeDeclaration::getNameAsString)
                .orElse("");
        if (!owner.isEmpty()) {
            String qualifiedSig = owner + "." + sig;
            String qualifiedSourceSig = owner + "." + sourceSig;
            String qualifiedName = owner + "." + c.getNameAsString();
            if (qualifiedSig.equals(rawTarget) || normalizeSig(qualifiedSig).equals(normalizedTarget)
                    || qualifiedSourceSig.equals(rawTarget) || normalizeSig(qualifiedSourceSig).equals(normalizedTarget)
                    || qualifiedName.equals(rawTarget)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeSig(String s) {
        return s.replaceAll("\\s+", "");
    }

    private static boolean containsLine(Node node, int line) {
        Optional<Range> r = node.getRange();
        return r.isPresent() && r.get().begin.line <= line && line <= r.get().end.line;
    }

    private static int lineSpan(Node node) {
        return node.getRange().map(r -> r.end.line - r.begin.line).orElse(Integer.MAX_VALUE);
    }

    private static Optional<BlockStmt> extractBody(CallableDeclaration<?> callable) {
        if (callable instanceof MethodDeclaration md) {
            return md.getBody();
        }
        if (callable instanceof ConstructorDeclaration cd) {
            return Optional.of(cd.getBody());
        }
        return Optional.empty();
    }

    private static boolean isSeedOnCallableHeader(CallableDeclaration<?> callable, BlockStmt body, int seedLine) {
        int declBegin = callable.getBegin().map(p -> p.line).orElse(-1);
        int bodyBegin = body.getBegin().map(p -> p.line).orElse(-1);
        if (declBegin < 0) return false;
        if (seedLine >= declBegin && (bodyBegin < 0 || seedLine <= bodyBegin)) {
            // Check no statement inside body already starts on seedLine
            for (Statement s : body.getStatements()) {
                int sLine = s.getBegin().map(p -> p.line).orElse(-1);
                if (sLine == seedLine) return false;
            }
            return true;
        }
        return false;
    }

    private static Set<String> collectEnclosingFields(CallableDeclaration<?> callable) {
        var fields = new LinkedHashSet<String>();
        Optional<TypeDeclaration> ownerOpt = callable.findAncestor(TypeDeclaration.class);
        if (ownerOpt.isPresent()) {
            TypeDeclaration<?> owner = ownerOpt.get();
            for (BodyDeclaration<?> member : owner.getMembers()) {
                if (member instanceof FieldDeclaration fd) {
                    for (VariableDeclarator v : fd.getVariables()) {
                        fields.add(v.getNameAsString());
                    }
                }
            }
            if (owner instanceof RecordDeclaration rd) {
                for (Parameter comp : rd.getParameters()) {
                    fields.add(comp.getNameAsString());
                }
            }
        }
        return fields;
    }

    // ─── Intra-Method IR + CFG + CDG + Reaching-Definitions DFG ───

    private static final class StmtNode {
        final int id;
        final int line;
        final int endLine;
        final String kind;
        final String code;
        final Set<String> strongDefs = new LinkedHashSet<>();
        final Set<String> weakDefs = new LinkedHashSet<>();
        final Set<String> uses = new LinkedHashSet<>();

        StmtNode(int id, int line, int endLine, String kind, String code) {
            this.id = id;
            this.line = Math.max(1, line);
            this.endLine = Math.max(this.line, endLine);
            this.kind = kind;
            this.code = code;
        }
    }

    private record BuildOutcome(
            int entryNode,
            List<Integer> normalExits,
            List<Integer> breakExits,
            List<Integer> continueExits,
            List<Integer> throwExits
    ) {
        static BuildOutcome empty() {
            return new BuildOutcome(-1, List.of(), List.of(), List.of(), List.of());
        }
    }

    private static final class IntraMethodGraphBuilder {
        final Set<String> enclosingFields;
        final Set<String> localVars = new LinkedHashSet<>();
        final List<StmtNode> nodes = new ArrayList<>();
        final Map<Integer, Set<Integer>> cfgSucc = new HashMap<>();
        final Map<Integer, Set<Integer>> cfgPred = new HashMap<>();
        final Map<Integer, Set<Integer>> controlDeps = new HashMap<>();

        IntraMethodGraphBuilder(Set<String> enclosingFields, Set<String> parameters) {
            this.enclosingFields = enclosingFields;
            this.localVars.addAll(parameters);
        }

        int addParameterHeaderNode(CallableDeclaration<?> callable, int line, Set<String> parameters) {
            StmtNode node = addNode(line, line, "METHOD_SIGNATURE", compact(callable.getDeclarationAsString(false, false, true)));
            for (String p : parameters) {
                node.strongDefs.add("var:" + p);
            }
            return node.id;
        }

        void buildBody(BlockStmt body, int headerNodeId) {
            List<Integer> initialPreds = headerNodeId >= 0 ? List.of(headerNodeId) : List.of();
            buildStatementList(body.getStatements(), initialPreds, -1);
        }

        private StmtNode addNode(int line, int endLine, String kind, String code) {
            StmtNode n = new StmtNode(nodes.size(), line, endLine, kind, code);
            nodes.add(n);
            return n;
        }

        private void addCfgEdge(int from, int to) {
            if (from < 0 || to < 0) return;
            cfgSucc.computeIfAbsent(from, k -> new LinkedHashSet<>()).add(to);
            cfgPred.computeIfAbsent(to, k -> new LinkedHashSet<>()).add(from);
        }

        private void addControlDep(int ctrlPredicate, int dependentNode) {
            if (ctrlPredicate < 0 || dependentNode < 0 || ctrlPredicate == dependentNode) return;
            controlDeps.computeIfAbsent(dependentNode, k -> new LinkedHashSet<>()).add(ctrlPredicate);
        }

        private BuildOutcome buildStatementList(List<Statement> stmts, List<Integer> incomingPreds, int ctrlParent) {
            int firstEntry = -1;
            List<Integer> currentPreds = new ArrayList<>(incomingPreds);
            var breaks = new ArrayList<Integer>();
            var continues = new ArrayList<Integer>();
            var throwsOut = new ArrayList<Integer>();

            for (Statement stmt : stmts) {
                if (stmt instanceof EmptyStmt) continue;
                BuildOutcome out = buildStatement(stmt, currentPreds, ctrlParent);
                if (firstEntry < 0 && out.entryNode() >= 0) {
                    firstEntry = out.entryNode();
                }
                breaks.addAll(out.breakExits());
                continues.addAll(out.continueExits());
                throwsOut.addAll(out.throwExits());
                if (out.entryNode() >= 0) {
                    currentPreds = new ArrayList<>(out.normalExits());
                }
            }
            return new BuildOutcome(firstEntry, currentPreds, breaks, continues, throwsOut);
        }

        private BuildOutcome buildStatement(Statement stmt, List<Integer> preds, int ctrlParent) {
            if (stmt instanceof BlockStmt block) {
                return buildStatementList(block.getStatements(), preds, ctrlParent);
            }
            if (stmt instanceof LabeledStmt labeled) {
                return buildStatement(labeled.getStatement(), preds, ctrlParent);
            }
            if (stmt instanceof IfStmt ifStmt) {
                return buildIf(ifStmt, preds, ctrlParent);
            }
            if (stmt instanceof ForStmt forStmt) {
                return buildFor(forStmt, preds, ctrlParent);
            }
            if (stmt instanceof ForEachStmt forEach) {
                return buildForEach(forEach, preds, ctrlParent);
            }
            if (stmt instanceof WhileStmt whileStmt) {
                return buildWhile(whileStmt, preds, ctrlParent);
            }
            if (stmt instanceof DoStmt doStmt) {
                return buildDoWhile(doStmt, preds, ctrlParent);
            }
            if (stmt instanceof SwitchStmt switchStmt) {
                return buildSwitch(switchStmt, preds, ctrlParent);
            }
            if (stmt instanceof TryStmt tryStmt) {
                return buildTry(tryStmt, preds, ctrlParent);
            }
            if (stmt instanceof SynchronizedStmt syncStmt) {
                int line = lineOf(syncStmt);
                int endLine = endLineOf(syncStmt.getExpression(), line);
                StmtNode n = addNode(line, endLine, "SYNCHRONIZED", "synchronized (" + compact(syncStmt.getExpression().toString()) + ")");
                extractExprDefUse(syncStmt.getExpression(), n, false);
                connectPreds(preds, n.id);
                addControlDep(ctrlParent, n.id);
                return buildStatement(syncStmt.getBody(), List.of(n.id), n.id);
            }
            if (stmt instanceof ReturnStmt retStmt) {
                StmtNode n = addNode(lineOf(retStmt), endLineOf(retStmt), "RETURN", compact(retStmt.toString()));
                retStmt.getExpression().ifPresent(expr -> extractExprDefUse(expr, n, false));
                connectPreds(preds, n.id);
                addControlDep(ctrlParent, n.id);
                return new BuildOutcome(n.id, List.of(), List.of(), List.of(), List.of());
            }
            if (stmt instanceof ThrowStmt throwStmt) {
                StmtNode n = addNode(lineOf(throwStmt), endLineOf(throwStmt), "THROW", compact(throwStmt.toString()));
                extractExprDefUse(throwStmt.getExpression(), n, false);
                connectPreds(preds, n.id);
                addControlDep(ctrlParent, n.id);
                return new BuildOutcome(n.id, List.of(), List.of(), List.of(), List.of(n.id));
            }
            if (stmt instanceof BreakStmt breakStmt) {
                StmtNode n = addNode(lineOf(breakStmt), endLineOf(breakStmt), "BREAK", compact(breakStmt.toString()));
                connectPreds(preds, n.id);
                addControlDep(ctrlParent, n.id);
                return new BuildOutcome(n.id, List.of(), List.of(n.id), List.of(), List.of());
            }
            if (stmt instanceof ContinueStmt contStmt) {
                StmtNode n = addNode(lineOf(contStmt), endLineOf(contStmt), "CONTINUE", compact(contStmt.toString()));
                connectPreds(preds, n.id);
                addControlDep(ctrlParent, n.id);
                return new BuildOutcome(n.id, List.of(), List.of(), List.of(n.id), List.of());
            }
            if (stmt instanceof YieldStmt yieldStmt) {
                StmtNode n = addNode(lineOf(yieldStmt), endLineOf(yieldStmt), "YIELD", compact(yieldStmt.toString()));
                extractExprDefUse(yieldStmt.getExpression(), n, false);
                connectPreds(preds, n.id);
                addControlDep(ctrlParent, n.id);
                return new BuildOutcome(n.id, List.of(), List.of(), List.of(), List.of());
            }
            if (stmt instanceof ExpressionStmt exprStmt) {
                Expression expr = exprStmt.getExpression();
                String kind = classifyExpressionKind(expr);
                StmtNode n = addNode(lineOf(exprStmt), endLineOf(exprStmt), kind, compact(exprStmt.toString()));
                extractExprDefUse(expr, n, true);
                connectPreds(preds, n.id);
                addControlDep(ctrlParent, n.id);
                return new BuildOutcome(n.id, List.of(n.id), List.of(), List.of(), List.of(n.id));
            }
            if (stmt instanceof AssertStmt assertStmt) {
                StmtNode n = addNode(lineOf(assertStmt), endLineOf(assertStmt), "ASSERT", compact(assertStmt.toString()));
                extractExprDefUse(assertStmt.getCheck(), n, false);
                assertStmt.getMessage().ifPresent(m -> extractExprDefUse(m, n, false));
                connectPreds(preds, n.id);
                addControlDep(ctrlParent, n.id);
                return new BuildOutcome(n.id, List.of(n.id), List.of(), List.of(), List.of(n.id));
            }
            if (stmt instanceof ExplicitConstructorInvocationStmt ctorInv) {
                StmtNode n = addNode(lineOf(ctorInv), endLineOf(ctorInv), "CONSTRUCTOR_CALL", compact(ctorInv.toString()));
                ctorInv.getArguments().forEach(arg -> extractExprDefUse(arg, n, false));
                connectPreds(preds, n.id);
                addControlDep(ctrlParent, n.id);
                return new BuildOutcome(n.id, List.of(n.id), List.of(), List.of(), List.of(n.id));
            }
            if (stmt instanceof LocalClassDeclarationStmt localCls) {
                StmtNode n = addNode(lineOf(localCls), lineOf(localCls), "LOCAL_CLASS",
                        "class " + localCls.getClassDeclaration().getNameAsString());
                connectPreds(preds, n.id);
                addControlDep(ctrlParent, n.id);
                return new BuildOutcome(n.id, List.of(n.id), List.of(), List.of(), List.of());
            }
            if (stmt instanceof EmptyStmt) {
                return BuildOutcome.empty();
            }

            // Fallback generic statement
            StmtNode n = addNode(lineOf(stmt), endLineOf(stmt), "STATEMENT", compact(stmt.toString()));
            stmt.findAll(Expression.class).forEach(e -> {
                if (e.getParentNode().map(p -> p == stmt).orElse(false)) {
                    extractExprDefUse(e, n, false);
                }
            });
            connectPreds(preds, n.id);
            addControlDep(ctrlParent, n.id);
            return new BuildOutcome(n.id, List.of(n.id), List.of(), List.of(), List.of(n.id));
        }

        private BuildOutcome buildIf(IfStmt ifStmt, List<Integer> preds, int ctrlParent) {
            int line = lineOf(ifStmt);
            int endLine = endLineOf(ifStmt.getCondition(), line);
            StmtNode condNode = addNode(line, endLine, "IF_CONDITION", "if (" + compact(ifStmt.getCondition().toString()) + ")");
            extractExprDefUse(ifStmt.getCondition(), condNode, false);
            connectPreds(preds, condNode.id);
            addControlDep(ctrlParent, condNode.id);

            BuildOutcome thenOut = buildStatement(ifStmt.getThenStmt(), List.of(condNode.id), condNode.id);
            var normalExits = new ArrayList<Integer>();
            var breaks = new ArrayList<Integer>(thenOut.breakExits());
            var continues = new ArrayList<Integer>(thenOut.continueExits());
            var throwsOut = new ArrayList<Integer>(thenOut.throwExits());

            if (thenOut.entryNode() < 0) {
                normalExits.add(condNode.id);
            } else {
                normalExits.addAll(thenOut.normalExits());
            }

            if (ifStmt.getElseStmt().isPresent()) {
                BuildOutcome elseOut = buildStatement(ifStmt.getElseStmt().get(), List.of(condNode.id), condNode.id);
                if (elseOut.entryNode() < 0) {
                    normalExits.add(condNode.id);
                } else {
                    normalExits.addAll(elseOut.normalExits());
                }
                breaks.addAll(elseOut.breakExits());
                continues.addAll(elseOut.continueExits());
                throwsOut.addAll(elseOut.throwExits());
            } else {
                normalExits.add(condNode.id);
            }

            return new BuildOutcome(condNode.id, normalExits, breaks, continues, throwsOut);
        }

        private BuildOutcome buildFor(ForStmt forStmt, List<Integer> preds, int ctrlParent) {
            int line = lineOf(forStmt);
            int headerEnd = line;
            for (Expression u : forStmt.getUpdate()) {
                headerEnd = Math.max(headerEnd, endLineOf(u, line));
            }
            if (forStmt.getCompare().isPresent()) {
                headerEnd = Math.max(headerEnd, endLineOf(forStmt.getCompare().get(), line));
            }
            String headerCode = "for (" + joinExprs(forStmt.getInitialization()) + "; "
                    + forStmt.getCompare().map(Object::toString).orElse("") + "; "
                    + joinExprs(forStmt.getUpdate()) + ")";
            StmtNode header = addNode(line, headerEnd, "FOR_LOOP", compact(headerCode));
            for (Expression init : forStmt.getInitialization()) {
                extractExprDefUse(init, header, true);
            }
            forStmt.getCompare().ifPresent(cmp -> extractExprDefUse(cmp, header, false));
            for (Expression upd : forStmt.getUpdate()) {
                extractExprDefUse(upd, header, true);
            }
            connectPreds(preds, header.id);
            addControlDep(ctrlParent, header.id);

            BuildOutcome bodyOut = buildStatement(forStmt.getBody(), List.of(header.id), header.id);
            // Back-edges from body normal exits & continues to loop header
            for (int exit : bodyOut.normalExits()) {
                addCfgEdge(exit, header.id);
            }
            for (int cont : bodyOut.continueExits()) {
                addCfgEdge(cont, header.id);
            }
            var exits = new ArrayList<Integer>();
            exits.add(header.id);
            exits.addAll(bodyOut.breakExits());
            return new BuildOutcome(header.id, exits, List.of(), List.of(), bodyOut.throwExits());
        }

        private BuildOutcome buildForEach(ForEachStmt forEach, List<Integer> preds, int ctrlParent) {
            int line = lineOf(forEach);
            int headerEnd = endLineOf(forEach.getIterable(), line);
            String headerCode = "for (" + compact(forEach.getVariable().toString()) + " : "
                    + compact(forEach.getIterable().toString()) + ")";
            StmtNode header = addNode(line, headerEnd, "FOR_EACH", headerCode);
            extractExprDefUse(forEach.getIterable(), header, false);
            for (VariableDeclarator vd : forEach.getVariable().getVariables()) {
                localVars.add(vd.getNameAsString());
                header.strongDefs.add("var:" + vd.getNameAsString());
            }
            connectPreds(preds, header.id);
            addControlDep(ctrlParent, header.id);

            BuildOutcome bodyOut = buildStatement(forEach.getBody(), List.of(header.id), header.id);
            for (int exit : bodyOut.normalExits()) {
                addCfgEdge(exit, header.id);
            }
            for (int cont : bodyOut.continueExits()) {
                addCfgEdge(cont, header.id);
            }
            var exits = new ArrayList<Integer>();
            exits.add(header.id);
            exits.addAll(bodyOut.breakExits());
            return new BuildOutcome(header.id, exits, List.of(), List.of(), bodyOut.throwExits());
        }

        private BuildOutcome buildWhile(WhileStmt whileStmt, List<Integer> preds, int ctrlParent) {
            int line = lineOf(whileStmt);
            int headerEnd = endLineOf(whileStmt.getCondition(), line);
            StmtNode header = addNode(line, headerEnd, "WHILE_CONDITION",
                    "while (" + compact(whileStmt.getCondition().toString()) + ")");
            extractExprDefUse(whileStmt.getCondition(), header, false);
            connectPreds(preds, header.id);
            addControlDep(ctrlParent, header.id);

            BuildOutcome bodyOut = buildStatement(whileStmt.getBody(), List.of(header.id), header.id);
            for (int exit : bodyOut.normalExits()) {
                addCfgEdge(exit, header.id);
            }
            for (int cont : bodyOut.continueExits()) {
                addCfgEdge(cont, header.id);
            }
            var exits = new ArrayList<Integer>();
            exits.add(header.id);
            exits.addAll(bodyOut.breakExits());
            return new BuildOutcome(header.id, exits, List.of(), List.of(), bodyOut.throwExits());
        }

        private BuildOutcome buildDoWhile(DoStmt doStmt, List<Integer> preds, int ctrlParent) {
            int condLine = doStmt.getCondition().getBegin().map(p -> p.line).orElse(endLineOf(doStmt));
            int condEnd = endLineOf(doStmt.getCondition(), condLine);
            // Create condition node first so body statements can record control dependence on loop condition
            StmtNode condNode = addNode(condLine, condEnd, "DO_WHILE_CONDITION",
                    "while (" + compact(doStmt.getCondition().toString()) + ")");
            extractExprDefUse(doStmt.getCondition(), condNode, false);
            addControlDep(ctrlParent, condNode.id);

            BuildOutcome bodyOut = buildStatement(doStmt.getBody(), preds, condNode.id);
            int entry = bodyOut.entryNode() >= 0 ? bodyOut.entryNode() : condNode.id;
            if (bodyOut.entryNode() < 0) {
                connectPreds(preds, condNode.id);
            } else {
                for (int exit : bodyOut.normalExits()) {
                    addCfgEdge(exit, condNode.id);
                }
                for (int cont : bodyOut.continueExits()) {
                    addCfgEdge(cont, condNode.id);
                }
                addCfgEdge(condNode.id, bodyOut.entryNode());
            }
            var exits = new ArrayList<Integer>();
            exits.add(condNode.id);
            exits.addAll(bodyOut.breakExits());
            return new BuildOutcome(entry, exits, List.of(), List.of(), bodyOut.throwExits());
        }

        private BuildOutcome buildSwitch(SwitchStmt switchStmt, List<Integer> preds, int ctrlParent) {
            int line = lineOf(switchStmt);
            int headerEnd = endLineOf(switchStmt.getSelector(), line);
            StmtNode selector = addNode(line, headerEnd, "SWITCH_SELECTOR",
                    "switch (" + compact(switchStmt.getSelector().toString()) + ")");
            extractExprDefUse(switchStmt.getSelector(), selector, false);
            connectPreds(preds, selector.id);
            addControlDep(ctrlParent, selector.id);

            var exits = new ArrayList<Integer>();
            var continues = new ArrayList<Integer>();
            var throwsOut = new ArrayList<Integer>();
            List<Integer> fallthroughPreds = List.of();
            boolean hasDefault = false;

            for (SwitchEntry entry : switchStmt.getEntries()) {
                if (entry.getLabels().isEmpty()) {
                    hasDefault = true;
                } else {
                    for (Expression lbl : entry.getLabels()) {
                        extractExprDefUse(lbl, selector, false);
                    }
                }
                var entryPreds = new ArrayList<Integer>();
                entryPreds.add(selector.id);
                entryPreds.addAll(fallthroughPreds);

                BuildOutcome entryOut = buildStatementList(entry.getStatements(), entryPreds, selector.id);
                exits.addAll(entryOut.breakExits());
                continues.addAll(entryOut.continueExits());
                throwsOut.addAll(entryOut.throwExits());

                if (entry.getType() == SwitchEntry.Type.STATEMENT_GROUP) {
                    fallthroughPreds = entryOut.normalExits();
                } else {
                    // Arrow case (`case X -> ...`) does not fall through
                    exits.addAll(entryOut.normalExits());
                    fallthroughPreds = List.of();
                }
            }
            exits.addAll(fallthroughPreds);
            if (!hasDefault) {
                exits.add(selector.id);
            }
            return new BuildOutcome(selector.id, exits, List.of(), continues, throwsOut);
        }

        private BuildOutcome buildTry(TryStmt tryStmt, List<Integer> preds, int ctrlParent) {
            List<Integer> currentPreds = new ArrayList<>(preds);
            int tryEntry = -1;
            int tryCtrl = ctrlParent;

            if (!tryStmt.getResources().isEmpty()) {
                int resLine = lineOf(tryStmt);
                int resEnd = resLine;
                for (Expression r : tryStmt.getResources()) {
                    resEnd = Math.max(resEnd, endLineOf(r, resLine));
                }
                StmtNode resNode = addNode(resLine, resEnd, "TRY_RESOURCE",
                        "try (" + joinExprs(tryStmt.getResources()) + ")");
                for (Expression r : tryStmt.getResources()) {
                    extractExprDefUse(r, resNode, true);
                }
                connectPreds(currentPreds, resNode.id);
                addControlDep(ctrlParent, resNode.id);
                tryEntry = resNode.id;
                tryCtrl = resNode.id;
                currentPreds = List.of(resNode.id);
            }

            BuildOutcome tryOut = buildStatement(tryStmt.getTryBlock(), currentPreds, tryCtrl);
            if (tryEntry < 0) {
                tryEntry = tryOut.entryNode();
            }

            // Collect all CFG nodes inside the try block as potential throw predecessors into catch blocks
            var throwPreds = new ArrayList<Integer>(tryOut.throwExits());
            if (throwPreds.isEmpty() && tryEntry >= 0) {
                throwPreds.add(tryEntry);
            }

            var normalExits = new ArrayList<Integer>(tryOut.normalExits());
            var breaks = new ArrayList<Integer>(tryOut.breakExits());
            var continues = new ArrayList<Integer>(tryOut.continueExits());
            var uncaughtThrows = new ArrayList<Integer>();

            for (CatchClause cc : tryStmt.getCatchClauses()) {
                int cLine = lineOf(cc);
                int cEnd = endLineOf(cc.getParameter(), cLine);
                StmtNode catchNode = addNode(cLine, cEnd, "CATCH",
                        "catch (" + compact(cc.getParameter().toString()) + ")");
                String catchVar = cc.getParameter().getNameAsString();
                localVars.add(catchVar);
                catchNode.strongDefs.add("var:" + catchVar);
                connectPreds(throwPreds, catchNode.id);
                addControlDep(ctrlParent, catchNode.id);

                BuildOutcome catchOut = buildStatement(cc.getBody(), List.of(catchNode.id), catchNode.id);
                if (catchOut.entryNode() < 0) {
                    normalExits.add(catchNode.id);
                } else {
                    normalExits.addAll(catchOut.normalExits());
                }
                breaks.addAll(catchOut.breakExits());
                continues.addAll(catchOut.continueExits());
                uncaughtThrows.addAll(catchOut.throwExits());
            }

            if (tryStmt.getFinallyBlock().isPresent()) {
                var finallyPreds = new ArrayList<Integer>(normalExits);
                finallyPreds.addAll(uncaughtThrows);
                BuildOutcome finOut = buildStatement(tryStmt.getFinallyBlock().get(), finallyPreds, ctrlParent);
                if (finOut.entryNode() >= 0) {
                    normalExits = new ArrayList<>(finOut.normalExits());
                    breaks.addAll(finOut.breakExits());
                    continues.addAll(finOut.continueExits());
                    uncaughtThrows.addAll(finOut.throwExits());
                }
            }

            return new BuildOutcome(tryEntry, normalExits, breaks, continues, uncaughtThrows);
        }

        private void connectPreds(List<Integer> preds, int target) {
            for (int p : preds) {
                addCfgEdge(p, target);
            }
        }

        // ─── Expression Def/Use Extraction ───

        private void extractExprDefUse(Expression expr, StmtNode node, boolean topLevelStmtExpr) {
            if (expr == null) return;

            if (expr instanceof VariableDeclarationExpr vde) {
                for (VariableDeclarator vd : vde.getVariables()) {
                    // Initializer uses are evaluated before defining the variable
                    vd.getInitializer().ifPresent(init -> extractExprDefUse(init, node, false));
                    String name = vd.getNameAsString();
                    localVars.add(name);
                    node.strongDefs.add("var:" + name);
                }
                return;
            }

            if (expr instanceof AssignExpr assign) {
                // Evaluate RHS first
                extractExprDefUse(assign.getValue(), node, false);
                Expression target = assign.getTarget();
                boolean compound = assign.getOperator() != AssignExpr.Operator.ASSIGN;
                recordWriteTarget(target, node, compound);
                return;
            }

            if (expr instanceof UnaryExpr unary) {
                UnaryExpr.Operator op = unary.getOperator();
                boolean isMutating = op == UnaryExpr.Operator.PREFIX_INCREMENT
                        || op == UnaryExpr.Operator.POSTFIX_INCREMENT
                        || op == UnaryExpr.Operator.PREFIX_DECREMENT
                        || op == UnaryExpr.Operator.POSTFIX_DECREMENT;
                if (isMutating) {
                    recordWriteTarget(unary.getExpression(), node, true);
                } else {
                    extractExprDefUse(unary.getExpression(), node, false);
                }
                return;
            }

            if (expr instanceof MethodCallExpr call) {
                call.getArguments().forEach(arg -> extractExprDefUse(arg, node, false));
                if (call.getScope().isPresent()) {
                    Expression scope = call.getScope().get();
                    extractExprDefUse(scope, node, false);
                    // Statement-level receiver call (e.g., list.add(x); sb.append(y); order.setTotal(z);)
                    // weakly mutates receiver state without killing its original declaration.
                    if (topLevelStmtExpr) {
                        Optional<String> recvKey = resolveReceiverVarKey(scope);
                        recvKey.ifPresent(node.weakDefs::add);
                    }
                }
                return;
            }

            if (expr instanceof FieldAccessExpr fa) {
                if (fa.getScope() instanceof ThisExpr) {
                    node.uses.add("field:" + fa.getNameAsString());
                } else if (fa.getScope() instanceof NameExpr ne) {
                    String baseKey = resolveNameKey(ne.getNameAsString());
                    node.uses.add(baseKey);
                    node.uses.add("field:" + ne.getNameAsString() + "." + fa.getNameAsString());
                } else {
                    extractExprDefUse(fa.getScope(), node, false);
                }
                return;
            }

            if (expr instanceof NameExpr ne) {
                node.uses.add(resolveNameKey(ne.getNameAsString()));
                return;
            }

            if (expr instanceof ArrayAccessExpr aa) {
                extractExprDefUse(aa.getName(), node, false);
                extractExprDefUse(aa.getIndex(), node, false);
                return;
            }

            if (expr instanceof LambdaExpr lambda) {
                var savedLocals = new HashSet<>(localVars);
                var lambdaParams = new HashSet<String>();
                for (Parameter p : lambda.getParameters()) {
                    lambdaParams.add(p.getNameAsString());
                    localVars.add(p.getNameAsString());
                }
                // Collect captured outer variable reads/writes inside the lambda body
                lambda.getBody().findAll(NameExpr.class).forEach(ne -> {
                    if (!lambdaParams.contains(ne.getNameAsString())) {
                        node.uses.add(resolveNameKey(ne.getNameAsString()));
                    }
                });
                lambda.getBody().findAll(FieldAccessExpr.class).forEach(fa -> {
                    if (fa.getScope() instanceof ThisExpr) {
                        node.uses.add("field:" + fa.getNameAsString());
                    }
                });
                localVars.clear();
                localVars.addAll(savedLocals);
                return;
            }

            if (expr instanceof InstanceOfExpr ioe) {
                extractExprDefUse(ioe.getExpression(), node, false);
                ioe.getPattern().ifPresent(pat -> {
                    if (pat instanceof TypePatternExpr tp) {
                        localVars.add(tp.getNameAsString());
                        node.strongDefs.add("var:" + tp.getNameAsString());
                    }
                });
                return;
            }

            if (expr instanceof ObjectCreationExpr oce) {
                oce.getScope().ifPresent(s -> extractExprDefUse(s, node, false));
                oce.getArguments().forEach(a -> extractExprDefUse(a, node, false));
                return;
            }

            if (expr instanceof EnclosedExpr enc) {
                extractExprDefUse(enc.getInner(), node, topLevelStmtExpr);
                return;
            }

            // Recurse into direct child expressions for BinaryExpr, ConditionalExpr, CastExpr, ArrayCreationExpr, etc.
            for (Node child : expr.getChildNodes()) {
                if (child instanceof Expression childExpr) {
                    extractExprDefUse(childExpr, node, false);
                }
            }
        }

        private void recordWriteTarget(Expression target, StmtNode node, boolean alsoReads) {
            if (target instanceof EnclosedExpr enc) {
                recordWriteTarget(enc.getInner(), node, alsoReads);
                return;
            }
            if (target instanceof NameExpr ne) {
                String key = resolveNameKey(ne.getNameAsString());
                if (alsoReads) {
                    node.uses.add(key);
                }
                node.strongDefs.add(key);
                return;
            }
            if (target instanceof FieldAccessExpr fa) {
                if (fa.getScope() instanceof ThisExpr) {
                    String fKey = "field:" + fa.getNameAsString();
                    if (alsoReads) node.uses.add(fKey);
                    node.strongDefs.add(fKey);
                } else if (fa.getScope() instanceof NameExpr ne) {
                    String objKey = resolveNameKey(ne.getNameAsString());
                    String fKey = "field:" + ne.getNameAsString() + "." + fa.getNameAsString();
                    node.uses.add(objKey);
                    if (alsoReads) node.uses.add(fKey);
                    node.strongDefs.add(fKey);
                    node.weakDefs.add(objKey);
                } else {
                    extractExprDefUse(fa.getScope(), node, false);
                }
                return;
            }
            if (target instanceof ArrayAccessExpr aa) {
                extractExprDefUse(aa.getIndex(), node, false);
                extractExprDefUse(aa.getName(), node, false);
                resolveReceiverVarKey(aa.getName()).ifPresent(node.weakDefs::add);
                return;
            }
            extractExprDefUse(target, node, false);
        }

        private Optional<String> resolveReceiverVarKey(Expression scope) {
            if (scope instanceof EnclosedExpr enc) return resolveReceiverVarKey(enc.getInner());
            if (scope instanceof NameExpr ne) {
                String name = ne.getNameAsString();
                if (localVars.contains(name) || enclosingFields.contains(name)) {
                    return Optional.of(resolveNameKey(name));
                }
                // Heuristic: lowercase identifier is likely a local/field instance rather than a Class name
                if (!name.isEmpty() && Character.isLowerCase(name.charAt(0))) {
                    return Optional.of("var:" + name);
                }
                return Optional.empty();
            }
            if (scope instanceof FieldAccessExpr fa && fa.getScope() instanceof ThisExpr) {
                return Optional.of("field:" + fa.getNameAsString());
            }
            if (scope instanceof MethodCallExpr chained && chained.getScope().isPresent()) {
                return resolveReceiverVarKey(chained.getScope().get());
            }
            return Optional.empty();
        }

        private String resolveNameKey(String name) {
            if (localVars.contains(name)) {
                return "var:" + name;
            }
            if (enclosingFields.contains(name)) {
                return "field:" + name;
            }
            return "var:" + name;
        }

        // ─── Reaching Definitions Fixpoint (DFG) ───

        Map<Integer, Set<Integer>> computeDataDependencies() {
            int n = nodes.size();
            // Map<varKey, Set<defNodeId>> per CFG node
            List<Map<String, Set<Integer>>> in = new ArrayList<>(n);
            List<Map<String, Set<Integer>>> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                in.add(new HashMap<>());
                out.add(new HashMap<>());
            }

            boolean changed = true;
            int maxIterations = Math.max(16, n * 4);
            int iter = 0;
            while (changed && iter++ < maxIterations) {
                changed = false;
                for (int i = 0; i < n; i++) {
                    var newIn = new HashMap<String, Set<Integer>>();
                    for (int p : cfgPred.getOrDefault(i, Set.of())) {
                        for (var entry : out.get(p).entrySet()) {
                            newIn.computeIfAbsent(entry.getKey(), k -> new LinkedHashSet<>())
                                    .addAll(entry.getValue());
                        }
                    }

                    // OUT[i] = (IN[i] \ KILL[i]) U GEN[i]
                    var newOut = new HashMap<String, Set<Integer>>();
                    StmtNode stmt = nodes.get(i);
                    for (var entry : newIn.entrySet()) {
                        if (!stmt.strongDefs.contains(entry.getKey())) {
                            newOut.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
                        }
                    }
                    for (String sDef : stmt.strongDefs) {
                        var set = new LinkedHashSet<Integer>();
                        set.add(i);
                        newOut.put(sDef, set);
                    }
                    for (String wDef : stmt.weakDefs) {
                        newOut.computeIfAbsent(wDef, k -> new LinkedHashSet<>()).add(i);
                    }

                    if (!newIn.equals(in.get(i)) || !newOut.equals(out.get(i))) {
                        in.set(i, newIn);
                        out.set(i, newOut);
                        changed = true;
                    }
                }
            }

            var dataDeps = new HashMap<Integer, Set<Integer>>();
            for (int i = 0; i < n; i++) {
                StmtNode stmt = nodes.get(i);
                Map<String, Set<Integer>> reaching = in.get(i);
                for (String useVar : stmt.uses) {
                    Set<Integer> defs = reaching.get(useVar);
                    if (defs == null || defs.isEmpty()) continue;
                    for (int defNode : defs) {
                        if (defNode != i) {
                            dataDeps.computeIfAbsent(i, k -> new LinkedHashSet<>()).add(defNode);
                        }
                    }
                }
            }
            return dataDeps;
        }

        private static String classifyExpressionKind(Expression expr) {
            if (expr instanceof VariableDeclarationExpr) return "VARIABLE_DECLARATION";
            if (expr instanceof AssignExpr) return "ASSIGNMENT";
            if (expr instanceof MethodCallExpr) return "CALL";
            if (expr instanceof UnaryExpr) return "UPDATE";
            return "EXPRESSION";
        }

        private static int lineOf(Node node) {
            return node.getBegin().map(p -> p.line).orElse(1);
        }

        private static int endLineOf(Node node) {
            return node.getEnd().map(p -> p.line).orElse(lineOf(node));
        }

        private static int endLineOf(Node node, int fallback) {
            return node.getEnd().map(p -> p.line).orElse(fallback);
        }

        private static String joinExprs(List<Expression> exprs) {
            return String.join(", ", exprs.stream().map(Object::toString).toList());
        }

        private static String compact(String raw) {
            String normalized = raw.replaceAll("\\s+", " ").strip();
            return normalized.length() > 160 ? normalized.substring(0, 157) + "..." : normalized;
        }
    }

    // ─── WP1.2: Cached Intra-Method Error Propagation Summaries (Java) ───

    private static final int MAX_ERROR_SUMMARY_CACHE = 1024;

    private static final Map<String, List<MethodErrorSummary>> ERROR_SUMMARY_CACHE =
            Collections.synchronizedMap(new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<MethodErrorSummary>> eldest) {
                    return size() > MAX_ERROR_SUMMARY_CACHE;
                }
            });

    private static final Set<String> REST_TEMPLATE_METHODS = Set.of(
            "getForObject", "getForEntity", "postForObject", "postForEntity",
            "exchange", "execute", "put", "delete", "patchForObject", "headForHeaders"
    );

    private static final Set<String> WEB_CLIENT_TERMINAL_METHODS = Set.of(
            "retrieve", "exchange", "exchangeToMono", "exchangeToFlux", "bodyToMono", "bodyToFlux", "toEntity"
    );

    private static final Set<String> HTTP_CLIENT_METHODS = Set.of(
            "send", "sendAsync", "execute", "newCall", "openConnection"
    );

    private static final Set<String> LOGGING_OR_METRICS_RECEIVERS = Set.of(
            "log", "logger", "out", "err", "system", "metrics", "meter", "meterregistry", "counter", "tracer", "span"
    );

    private static final Set<String> LOGGING_OR_METRICS_METHODS = Set.of(
            "trace", "debug", "info", "warn", "error", "fatal", "log", "println", "print",
            "printStackTrace", "increment", "count", "record", "recordException", "addEvent", "setAttribute"
    );

    private static final Set<String> HTTP_5XX_STATUS_CONSTANTS = Set.of(
            "INTERNAL_SERVER_ERROR", "NOT_IMPLEMENTED", "BAD_GATEWAY", "SERVICE_UNAVAILABLE",
            "GATEWAY_TIMEOUT", "HTTP_VERSION_NOT_SUPPORTED", "VARIANT_ALSO_NEGOTIATES",
            "INSUFFICIENT_STORAGE", "LOOP_DETECTED", "BANDWIDTH_LIMIT_EXCEEDED",
            "NOT_EXTENDED", "NETWORK_AUTHENTICATION_REQUIRED"
    );

    /** Clears the content-addressed Java error propagation summary cache. */
    public static void clearErrorSummaryCache() {
        ERROR_SUMMARY_CACHE.clear();
    }

    /** Returns current number of cached Java error summary entries. */
    public static int errorSummaryCacheSize() {
        return ERROR_SUMMARY_CACHE.size();
    }

    /**
     * Computes cached intra-method error propagation summaries for all outbound RPC/HTTP calls
     * ({@code Feign}/{@code RestTemplate}/{@code WebClient}/{@code gRPC Stub}/{@code HttpClient})
     * in {@code javaSource}.
     */
    public static List<MethodErrorSummary> summarizeErrorPropagation(String path, String javaSource) {
        return summarizeErrorPropagation(path, javaSource, Optional.empty(), false);
    }

    /**
     * Computes cached intra-method error propagation summaries for one method (or all methods when
     * {@code methodSignature} is empty) in {@code javaSource}. When {@code includeInternalCalls} is
     * {@code true}, also summarizes error handling over intra-service bean/helper calls so backward
     * inter-method composition can propagate exceptions through intermediate callers.
     */
    public static List<MethodErrorSummary> summarizeErrorPropagation(
            String path,
            String javaSource,
            Optional<String> methodSignature,
            boolean includeInternalCalls) {
        String safePath = Objects.requireNonNull(path, "path").strip();
        String safeSource = Objects.requireNonNull(javaSource, "javaSource");
        Optional<String> safeSig = methodSignature == null
                ? Optional.empty()
                : methodSignature.map(String::strip).filter(s -> !s.isEmpty());
        if (safeSource.isBlank()) {
            return List.of();
        }
        String cacheKey = safePath + "|" + ContentDigest.sha256Utf8(safeSource).value()
                + "|" + safeSig.orElse("*") + "|" + includeInternalCalls;
        List<MethodErrorSummary> cached = ERROR_SUMMARY_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        ParserConfiguration config = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setAttributeComments(false);
        ParseResult<CompilationUnit> parseResult = new JavaParser(config).parse(safeSource);
        Optional<CompilationUnit> maybeCu = parseResult.getResult();
        if (maybeCu.isEmpty()) {
            return List.of();
        }
        CompilationUnit cu = maybeCu.get();
        List<String> imports = cu.getImports().stream()
                .map(ImportDeclaration::getNameAsString)
                .toList();
        boolean importsFeign = imports.stream().anyMatch(i -> i.contains("openfeign") || i.startsWith("feign."));
        boolean importsGrpc = imports.stream().anyMatch(i -> i.startsWith("io.grpc."));

        // Index all methods declared in this compilation unit for local fallback/handler inspection
        var methodsByName = new HashMap<String, List<MethodDeclaration>>();
        for (MethodDeclaration md : cu.findAll(MethodDeclaration.class)) {
            methodsByName.computeIfAbsent(md.getNameAsString(), k -> new ArrayList<>()).add(md);
        }

        var summaries = new ArrayList<MethodErrorSummary>();
        for (MethodDeclaration md : cu.findAll(MethodDeclaration.class)) {
            if (safeSig.isPresent() && !matchesSignature(md, safeSig.get(), normalizeSig(safeSig.get()))) {
                continue;
            }
            if (md.getBody().isEmpty()) {
                continue;
            }
            summarizeJavaMethod(
                    safePath, md, importsFeign, importsGrpc, methodsByName, includeInternalCalls, summaries);
        }

        summaries.sort(Comparator.comparingInt(MethodErrorSummary::line)
                .thenComparing(MethodErrorSummary::methodId)
                .thenComparing(MethodErrorSummary::callsiteId));
        List<MethodErrorSummary> immutable = List.copyOf(summaries);
        ERROR_SUMMARY_CACHE.put(cacheKey, immutable);
        return immutable;
    }

    private record JavaOutboundCall(
            MethodCallExpr expr,
            String targetCall,
            String protocol,
            Set<String> baseExceptions
    ) {}

    private static void summarizeJavaMethod(
            String path,
            MethodDeclaration md,
            boolean importsFeign,
            boolean importsGrpc,
            Map<String, List<MethodDeclaration>> methodsByName,
            boolean includeInternalCalls,
            List<MethodErrorSummary> out) {
        BlockStmt body = md.getBody().orElseThrow();
        String ownerClass = md.findAncestor(TypeDeclaration.class)
                .map(rawOwner -> ((TypeDeclaration<?>) rawOwner).getNameAsString())
                .orElse("");
        String methodId = (ownerClass.isEmpty() ? path + "#" : path + "#" + ownerClass + ".")
                + md.getSignature().asString();

        // Build CFG/DFG/CDG over method body to support reaching-def & exit-path checks
        Set<String> enclosingFields = collectEnclosingFields(md);
        Set<String> paramNames = new LinkedHashSet<>();
        var varTypes = new HashMap<String, String>();
        var varAnnotations = new HashMap<String, Set<String>>();
        collectVariableTypes(md, varTypes, varAnnotations);
        for (Parameter p : md.getParameters()) {
            paramNames.add(p.getNameAsString());
        }
        var graphBuilder = new IntraMethodGraphBuilder(enclosingFields, paramNames);
        graphBuilder.buildBody(body, -1);
        Map<Integer, Set<Integer>> dataDeps = graphBuilder.computeDataDependencies();

        // Verify counterfactual D1.3 (at least one normal Ok return path exists when error branch not taken)
        boolean counterfactualOk = hasCounterfactualNormalOkPath(md, body);

        // Check method-level @CircuitBreaker / @Retry / @Bulkhead / @TimeLimiter fallbackMethod annotations
        Optional<ResilienceFallbackInfo> resilienceFallback = detectResilienceAnnotationFallback(md, methodsByName);

        // Detect outbound RPC/HTTP calls (plus optional internal calls)
        var detectedCalls = new ArrayList<JavaOutboundCall>();
        var visitedWebClientRoots = new HashSet<Integer>();
        for (MethodCallExpr call : body.findAll(MethodCallExpr.class)) {
            Optional<JavaOutboundCall> classified = classifyJavaCall(
                    call, varTypes, varAnnotations, importsFeign, importsGrpc, includeInternalCalls, visitedWebClientRoots);
            classified.ifPresent(detectedCalls::add);
        }

        for (JavaOutboundCall call : detectedCalls) {
            int line = call.expr().getBegin().map(p -> p.line).orElse(1);
            int endLine = call.expr().getEnd().map(p -> p.line).orElse(line);
            String callsiteId = path + ":" + line + "#" + call.targetCall();

            MethodErrorSummary summary = analyzeJavaCallPropagation(
                    methodId,
                    callsiteId,
                    path,
                    line,
                    endLine,
                    call,
                    md,
                    body,
                    graphBuilder,
                    dataDeps,
                    counterfactualOk,
                    resilienceFallback,
                    methodsByName);
            out.add(summary);
        }
    }

    private record ResilienceFallbackInfo(
            String annotationName,
            String fallbackMethodName,
            boolean fallbackPropagatesError,
            boolean fallbackOpaque,
            Optional<ExceptionTransformation> transformation
    ) {}

    private static Optional<ResilienceFallbackInfo> detectResilienceAnnotationFallback(
            MethodDeclaration md,
            Map<String, List<MethodDeclaration>> methodsByName) {
        for (AnnotationExpr ann : md.getAnnotations()) {
            String annName = ann.getName().getIdentifier();
            if (!Set.of("CircuitBreaker", "Retry", "Bulkhead", "TimeLimiter", "HystrixCommand").contains(annName)) {
                continue;
            }
            Optional<String> fbNameOpt = extractAnnotationStringAttr(ann, "fallbackMethod");
            if (fbNameOpt.isEmpty()) continue;
            String fbName = fbNameOpt.get();
            List<MethodDeclaration> fbDecls = methodsByName.getOrDefault(fbName, List.of());
            if (fbDecls.isEmpty()) {
                // Declared Resilience4j fallback (e.g. inherited or external stub) -> fail-open fallback
                return Optional.of(new ResilienceFallbackInfo(annName, fbName, false, false, Optional.empty()));
            }
            MethodDeclaration fbMethod = fbDecls.getFirst();
            if (fbMethod.getBody().isEmpty()) {
                return Optional.of(new ResilienceFallbackInfo(annName, fbName, false, true, Optional.empty()));
            }
            BlockStmt fbBody = fbMethod.getBody().get();
            if (blockThrowsOrReturns5xx(fbBody)) {
                String targetEx = extractFirstThrownOr5xx(fbBody);
                return Optional.of(new ResilienceFallbackInfo(
                        annName, fbName, true, false,
                        Optional.of(new ExceptionTransformation("Throwable", targetEx, annName.toUpperCase(Locale.ROOT) + "_FALLBACK_ERROR"))));
            }
            return Optional.of(new ResilienceFallbackInfo(annName, fbName, false, false, Optional.empty()));
        }
        return Optional.empty();
    }

    private static Optional<String> extractAnnotationStringAttr(AnnotationExpr ann, String attrName) {
        if (ann instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if (attrName.equals(pair.getNameAsString()) && pair.getValue() instanceof StringLiteralExpr lit) {
                    String val = lit.getValue().strip();
                    if (!val.isEmpty()) return Optional.of(val);
                }
            }
        }
        return Optional.empty();
    }

    private static MethodErrorSummary analyzeJavaCallPropagation(
            String methodId,
            String callsiteId,
            String path,
            int line,
            int endLine,
            JavaOutboundCall call,
            MethodDeclaration md,
            BlockStmt body,
            IntraMethodGraphBuilder graphBuilder,
            Map<Integer, Set<Integer>> dataDeps,
            boolean counterfactualOk,
            Optional<ResilienceFallbackInfo> resilienceFallback,
            Map<String, List<MethodDeclaration>> methodsByName) {

        var activeExceptions = new TreeSet<String>(call.baseExceptions());
        var transformations = new ArrayList<ExceptionTransformation>();
        boolean opaque = false;
        String opaqueReason = null;
        String fallbackMechanism = null;

        // 1. Check CompletableFuture / Reactor async error handlers chained on this call or its assigned variable
        AsyncHandlerOutcome asyncOutcome = inspectAsyncHandlers(call.expr(), body, methodsByName);
        if (asyncOutcome.matched()) {
            if (asyncOutcome.opaque()) {
                return new MethodErrorSummary(
                        "java", methodId, callsiteId, path, line, endLine,
                        call.targetCall(), call.protocol(), call.baseExceptions(),
                        activeExceptions, transformations,
                        PropagationDisposition.UNKNOWN_OPAQUE, counterfactualOk,
                        false, false, true,
                        Optional.of(FailCloseResilienceAnalyzer.GAP_REASON_UNKNOWN_ASYNC),
                        Optional.empty(), TrustTier.SOURCE_SYNTAX);
            }
            if (asyncOutcome.suppressed()) {
                return new MethodErrorSummary(
                        "java", methodId, callsiteId, path, line, endLine,
                        call.targetCall(), call.protocol(), call.baseExceptions(),
                        Set.of(), transformations,
                        PropagationDisposition.SUPPRESSED_FAIL_OPEN, counterfactualOk,
                        false, false, false,
                        Optional.empty(),
                        Optional.of(asyncOutcome.mechanism()),
                        TrustTier.SOURCE_SYNTAX);
            }
            if (asyncOutcome.transformation().isPresent()) {
                ExceptionTransformation tr = asyncOutcome.transformation().get();
                transformations.add(tr);
                activeExceptions.clear();
                activeExceptions.add(tr.targetException());
            }
        }

        // 2. Walk enclosing TryStmt blocks (innermost -> outermost) when call is inside try-block or try-resources
        Node cur = call.expr();
        while (cur != null && cur != md) {
            Optional<Node> parentOpt = cur.getParentNode();
            if (parentOpt.isEmpty()) break;
            Node parent = parentOpt.get();
            if (parent instanceof TryStmt tryStmt && isInsideTryProtectedRegion(cur, tryStmt)) {
                var remainingAfterTry = new TreeSet<String>(activeExceptions);
                var newlyAdded = new TreeSet<String>();
                for (CatchClause cc : tryStmt.getCatchClauses()) {
                    List<String> caughtTypes = extractCaughtExceptionTypes(cc);
                    var matched = new TreeSet<String>();
                    for (String ex : List.copyOf(remainingAfterTry)) {
                        for (String ct : caughtTypes) {
                            if (FailCloseResilienceAnalyzer.exceptionMatches(ct, ex)) {
                                matched.add(ex);
                                break;
                            }
                        }
                    }
                    if (matched.isEmpty()) continue;
                    remainingAfterTry.removeAll(matched);

                    CatchOutcome outcome = inspectCatchClause(cc, methodsByName);
                    if (outcome.opaque()) {
                        opaque = true;
                        opaqueReason = "OPAQUE_CATCH_HANDLER:" + outcome.detail();
                        newlyAdded.addAll(matched);
                    } else if (outcome.rethrowsSame()) {
                        newlyAdded.addAll(matched);
                    } else if (outcome.wrapsToException().isPresent()) {
                        String targetEx = outcome.wrapsToException().get();
                        for (String srcEx : matched) {
                            transformations.add(new ExceptionTransformation(srcEx, targetEx, "CATCH_WRAP_RETHROW"));
                        }
                        newlyAdded.add(targetEx);
                    } else if (outcome.returnsHttp5xx()) {
                        for (String srcEx : matched) {
                            transformations.add(new ExceptionTransformation(srcEx, "HTTP_5XX", "CATCH_HTTP_5XX_RETURN"));
                        }
                        newlyAdded.add("HTTP_5XX");
                    } else {
                        // Suppressed with default / fallback value
                        fallbackMechanism = "TRY_CATCH_DEFAULT";
                    }
                }
                activeExceptions.clear();
                activeExceptions.addAll(remainingAfterTry);
                activeExceptions.addAll(newlyAdded);
            }
            cur = parent;
        }

        // 3. Apply method-level @CircuitBreaker / @Retry fallback if exceptions still escape try/catch
        if (!activeExceptions.isEmpty() && !opaque && resilienceFallback.isPresent()) {
            ResilienceFallbackInfo fb = resilienceFallback.get();
            if (fb.fallbackOpaque()) {
                opaque = true;
                opaqueReason = "OPAQUE_ANNOTATION_FALLBACK:" + fb.fallbackMethodName();
            } else if (fb.fallbackPropagatesError()) {
                fb.transformation().ifPresent(tr -> {
                    transformations.add(tr);
                    activeExceptions.clear();
                    activeExceptions.add(tr.targetException());
                });
            } else {
                activeExceptions.clear();
                String prefix = switch (fb.annotationName()) {
                    case "CircuitBreaker" -> "CIRCUIT_BREAKER_FALLBACK";
                    case "Retry" -> "RETRY_FALLBACK";
                    default -> "ANNOTATION_FALLBACK_" + fb.annotationName().toUpperCase(Locale.ROOT);
                };
                fallbackMechanism = prefix + ":" + fb.fallbackMethodName();
            }
        }

        // 4. Determine final intra-method disposition
        if (opaque) {
            return new MethodErrorSummary(
                    "java", methodId, callsiteId, path, line, endLine,
                    call.targetCall(), call.protocol(), call.baseExceptions(),
                    activeExceptions, transformations,
                    PropagationDisposition.UNKNOWN_OPAQUE, counterfactualOk,
                    false, false, true,
                    Optional.ofNullable(opaqueReason),
                    Optional.empty(), TrustTier.SOURCE_SYNTAX);
        }
        if (activeExceptions.isEmpty()) {
            return new MethodErrorSummary(
                    "java", methodId, callsiteId, path, line, endLine,
                    call.targetCall(), call.protocol(), call.baseExceptions(),
                    Set.of(), transformations,
                    PropagationDisposition.SUPPRESSED_FAIL_OPEN, counterfactualOk,
                    false, false, false,
                    Optional.empty(),
                    Optional.ofNullable(fallbackMechanism == null ? "TRY_CATCH_DEFAULT" : fallbackMechanism),
                    TrustTier.SOURCE_SYNTAX);
        }

        // Also check DFG/CFG if response variable from this call is checked for 5xx error status and re-returned as 5xx
        boolean responseReturns5xx = isReturnHttp5xxInEnclosingReturn(call.expr());
        if (responseReturns5xx && !activeExceptions.contains("HTTP_5XX")) {
            activeExceptions.add("HTTP_5XX");
        }

        return new MethodErrorSummary(
                "java", methodId, callsiteId, path, line, endLine,
                call.targetCall(), call.protocol(), call.baseExceptions(),
                activeExceptions, transformations,
                PropagationDisposition.PROPAGATES_ERROR, counterfactualOk,
                false, false, false,
                Optional.empty(), Optional.empty(), TrustTier.SOURCE_SYNTAX);
    }

    private record AsyncHandlerOutcome(
            boolean matched,
            boolean suppressed,
            boolean opaque,
            String mechanism,
            Optional<ExceptionTransformation> transformation
    ) {
        static AsyncHandlerOutcome none() {
            return new AsyncHandlerOutcome(false, false, false, "", Optional.empty());
        }
    }

    private static AsyncHandlerOutcome inspectAsyncHandlers(
            MethodCallExpr call,
            BlockStmt methodBody,
            Map<String, List<MethodDeclaration>> methodsByName) {
        // Walk up fluent chain enclosing `call` (e.g. `client.fetchAsync().exceptionally(ex -> fallback)`)
        var chainedCalls = new ArrayList<MethodCallExpr>();
        Node cur = call.getParentNode().orElse(null);
        while (cur instanceof MethodCallExpr parentCall) {
            chainedCalls.add(parentCall);
            cur = parentCall.getParentNode().orElse(null);
        }

        // Also check if `call` is assigned to a local variable (`CompletableFuture<X> f = ...; f.exceptionally(...)`)
        String assignedVar = findAssignedLocalVarName(call);
        if (assignedVar != null) {
            for (MethodCallExpr mc : methodBody.findAll(MethodCallExpr.class)) {
                if (mc.getScope().isPresent() && mc.getScope().get() instanceof NameExpr ne
                        && ne.getNameAsString().equals(assignedVar)) {
                    chainedCalls.add(mc);
                }
            }
        }

        for (MethodCallExpr chained : chainedCalls) {
            String m = chained.getNameAsString();
            if (Set.of("exceptionally", "exceptionallyAsync", "exceptionallyCompose", "handle", "handleAsync").contains(m)) {
                if (chained.getArguments().isEmpty()) {
                    return new AsyncHandlerOutcome(true, false, true, "COMPLETABLE_FUTURE_" + m.toUpperCase(Locale.ROOT), Optional.empty());
                }
                Expression arg = chained.getArgument(0);
                return classifyAsyncHandlerArg(arg, "COMPLETABLE_FUTURE_" + m.toUpperCase(Locale.ROOT), methodsByName);
            }
            if ("onErrorReturn".equals(m) || "onErrorComplete".equals(m)) {
                return new AsyncHandlerOutcome(true, true, false, "WEBCLIENT_ON_ERROR_RETURN", Optional.empty());
            }
            if ("onErrorResume".equals(m)) {
                if (chained.getArguments().isEmpty()) {
                    return new AsyncHandlerOutcome(true, false, true, "WEBCLIENT_ON_ERROR_RESUME", Optional.empty());
                }
                return classifyAsyncHandlerArg(chained.getArgument(0), "WEBCLIENT_ON_ERROR_RESUME", methodsByName);
            }
            if ("onErrorMap".equals(m)) {
                return new AsyncHandlerOutcome(true, false, false, "WEBCLIENT_ON_ERROR_MAP",
                        Optional.of(new ExceptionTransformation("WebClientException", "MappedException", "WEBCLIENT_ON_ERROR_MAP")));
            }
        }
        return AsyncHandlerOutcome.none();
    }

    private static AsyncHandlerOutcome classifyAsyncHandlerArg(
            Expression arg,
            String mechanism,
            Map<String, List<MethodDeclaration>> methodsByName) {
        if (arg instanceof LambdaExpr lambda) {
            Statement lBody = lambda.getBody();
            if (lBody instanceof BlockStmt blk) {
                if (blockThrowsOrReturns5xx(blk)) {
                    String target = extractFirstThrownOr5xx(blk);
                    return new AsyncHandlerOutcome(true, false, false, mechanism,
                            Optional.of(new ExceptionTransformation("CompletionException", target, mechanism + "_ERROR")));
                }
                if (containsOpaqueHandlerCall(blk, lambdaParamNames(lambda), methodsByName)) {
                    return new AsyncHandlerOutcome(true, false, true, mechanism, Optional.empty());
                }
                return new AsyncHandlerOutcome(true, true, false, mechanism, Optional.empty());
            }
            if (lBody instanceof ExpressionStmt es) {
                Expression expr = es.getExpression();
                if (isHttp5xxExpression(expr)) {
                    return new AsyncHandlerOutcome(true, false, false, mechanism,
                            Optional.of(new ExceptionTransformation("CompletionException", "HTTP_5XX", mechanism + "_HTTP_5XX")));
                }
                if (isOpaqueSingleExpressionHandler(expr, lambdaParamNames(lambda), methodsByName)) {
                    return new AsyncHandlerOutcome(true, false, true, mechanism, Optional.empty());
                }
                return new AsyncHandlerOutcome(true, true, false, mechanism, Optional.empty());
            }
            return new AsyncHandlerOutcome(true, false, true, mechanism, Optional.empty());
        }
        if (arg instanceof MethodReferenceExpr mref) {
            String refMethod = mref.getIdentifier();
            List<MethodDeclaration> localDecls = methodsByName.getOrDefault(refMethod, List.of());
            if (!localDecls.isEmpty() && localDecls.getFirst().getBody().isPresent()) {
                BlockStmt blk = localDecls.getFirst().getBody().get();
                if (blockThrowsOrReturns5xx(blk)) {
                    return new AsyncHandlerOutcome(true, false, false, mechanism,
                            Optional.of(new ExceptionTransformation("CompletionException", extractFirstThrownOr5xx(blk), mechanism + "_ERROR")));
                }
                return new AsyncHandlerOutcome(true, true, false, mechanism, Optional.empty());
            }
            // External/library method reference -> opaque async handler gap (I3)
            return new AsyncHandlerOutcome(true, false, true, mechanism, Optional.empty());
        }
        // Arbitrary Function/BiFunction variable or factory expression -> opaque async handler gap (I3)
        return new AsyncHandlerOutcome(true, false, true, mechanism, Optional.empty());
    }

    private static Set<String> lambdaParamNames(LambdaExpr lambda) {
        var names = new HashSet<String>();
        for (Parameter p : lambda.getParameters()) {
            names.add(p.getNameAsString());
        }
        return names;
    }

    private static boolean isOpaqueSingleExpressionHandler(
            Expression expr,
            Set<String> errorParams,
            Map<String, List<MethodDeclaration>> methodsByName) {
        if (!(expr instanceof MethodCallExpr mc)) {
            return false;
        }
        String mName = mc.getNameAsString();
        if (methodsByName.containsKey(mName)) {
            return false;
        }
        if (Set.of("empty", "emptyList", "emptyMap", "emptySet", "of", "ofNullable", "ok", "just", "defaultValue").contains(mName)) {
            return false;
        }
        // Passing the exception parameter into an unresolved external method call -> opaque async handler
        for (Expression a : mc.getArguments()) {
            for (NameExpr ne : a.findAll(NameExpr.class)) {
                if (errorParams.contains(ne.getNameAsString())) {
                    return true;
                }
            }
        }
        return false;
    }

    private record CatchOutcome(
            boolean rethrowsSame,
            Optional<String> wrapsToException,
            boolean returnsHttp5xx,
            boolean opaque,
            String detail
    ) {}

    private static CatchOutcome inspectCatchClause(
            CatchClause cc,
            Map<String, List<MethodDeclaration>> methodsByName) {
        BlockStmt catchBody = cc.getBody();
        String catchVar = cc.getParameter().getNameAsString();

        // 1. Check ThrowStmt inside catch body
        List<ThrowStmt> throwsInCatch = catchBody.findAll(ThrowStmt.class);
        for (ThrowStmt ts : throwsInCatch) {
            Expression thrown = ts.getExpression();
            if (thrown instanceof NameExpr ne && ne.getNameAsString().equals(catchVar)) {
                return new CatchOutcome(true, Optional.empty(), false, false, "rethrow");
            }
            if (thrown instanceof ObjectCreationExpr oce) {
                String exType = oce.getType().getNameWithScope();
                return new CatchOutcome(false, Optional.of(exType), false, false, "wrap:" + exType);
            }
            if (thrown instanceof MethodCallExpr mc) {
                if (!methodsByName.containsKey(mc.getNameAsString())) {
                    return new CatchOutcome(false, Optional.empty(), false, true, mc.toString());
                }
                return new CatchOutcome(false, Optional.of("RuntimeException"), false, false, "wrap-call");
            }
            return new CatchOutcome(false, Optional.of("RuntimeException"), false, false, "throw");
        }

        // 2. Check HTTP 5xx return or response write inside catch body
        if (containsHttp5xx(catchBody)) {
            return new CatchOutcome(false, Optional.empty(), true, false, "HTTP_5XX");
        }

        // 3. Check opaque external handler delegation passing catchVar (excluding loggers/metrics)
        if (containsOpaqueHandlerCall(catchBody, Set.of(catchVar), methodsByName)) {
            return new CatchOutcome(false, Optional.empty(), false, true, "opaque-catch-delegation");
        }

        // 4. Suppressed with default/fallback
        return new CatchOutcome(false, Optional.empty(), false, false, "suppressed");
    }

    private static boolean containsOpaqueHandlerCall(
            BlockStmt block,
            Set<String> errorVarNames,
            Map<String, List<MethodDeclaration>> methodsByName) {
        for (MethodCallExpr mc : block.findAll(MethodCallExpr.class)) {
            String methodName = mc.getNameAsString();
            if (LOGGING_OR_METRICS_METHODS.contains(methodName)) continue;
            if (mc.getScope().isPresent()) {
                String scopeLower = mc.getScope().get().toString().toLowerCase(Locale.ROOT);
                if (LOGGING_OR_METRICS_RECEIVERS.stream().anyMatch(scopeLower::contains)) {
                    continue;
                }
            }
            if (methodsByName.containsKey(methodName)) {
                continue; // Local method in same compilation unit
            }
            boolean passesErrorVar = false;
            for (Expression arg : mc.getArguments()) {
                for (NameExpr ne : arg.findAll(NameExpr.class)) {
                    if (errorVarNames.contains(ne.getNameAsString())) {
                        passesErrorVar = true;
                        break;
                    }
                }
                if (passesErrorVar) break;
            }
            if (passesErrorVar) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCounterfactualNormalOkPath(MethodDeclaration md, BlockStmt body) {
        // Check if normal execution (excluding catch blocks) has at least one non-throwing, non-5xx return/exit.
        var normalReturns = new ArrayList<ReturnStmt>();
        collectStatementsOutsideCatch(body, normalReturns, ReturnStmt.class);

        if (md.getType().isVoidType()) {
            // Void method: verify top-level body does not unconditionally throw or unconditionally send 5xx
            boolean topUnconditionalThrow = false;
            for (Statement s : body.getStatements()) {
                if (s instanceof ThrowStmt) {
                    topUnconditionalThrow = true;
                    break;
                }
                if (s instanceof ExpressionStmt es && isHttp5xxExpression(es.getExpression())) {
                    topUnconditionalThrow = true;
                    break;
                }
            }
            return !topUnconditionalThrow;
        }

        if (normalReturns.isEmpty()) {
            return false;
        }
        for (ReturnStmt ret : normalReturns) {
            if (ret.getExpression().isEmpty()) return true;
            if (!isHttp5xxExpression(ret.getExpression().get())) {
                return true;
            }
        }
        return false;
    }

    private static <T extends Statement> void collectStatementsOutsideCatch(Node root, List<T> out, Class<T> targetClass) {
        if (root instanceof CatchClause) {
            return;
        }
        if (targetClass.isInstance(root)) {
            out.add(targetClass.cast(root));
        }
        for (Node child : root.getChildNodes()) {
            collectStatementsOutsideCatch(child, out, targetClass);
        }
    }

    private static boolean blockThrowsOrReturns5xx(BlockStmt block) {
        return !block.findAll(ThrowStmt.class).isEmpty() || containsHttp5xx(block);
    }

    private static String extractFirstThrownOr5xx(BlockStmt block) {
        List<ThrowStmt> throwsList = block.findAll(ThrowStmt.class);
        if (!throwsList.isEmpty()) {
            Expression e = throwsList.getFirst().getExpression();
            if (e instanceof ObjectCreationExpr oce) {
                return oce.getType().getNameWithScope();
            }
            return "RuntimeException";
        }
        if (containsHttp5xx(block)) {
            return "HTTP_5XX";
        }
        return "RuntimeException";
    }

    private static boolean containsHttp5xx(Node node) {
        for (ReturnStmt ret : node.findAll(ReturnStmt.class)) {
            if (ret.getExpression().isPresent() && isHttp5xxExpression(ret.getExpression().get())) {
                return true;
            }
        }
        for (MethodCallExpr mc : node.findAll(MethodCallExpr.class)) {
            String name = mc.getNameAsString();
            if (("sendError".equals(name) || "setStatus".equals(name)) && has5xxArgument(mc)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isReturnHttp5xxInEnclosingReturn(Expression expr) {
        Node cur = expr;
        while (cur != null) {
            if (cur instanceof ReturnStmt ret && ret.getExpression().isPresent()) {
                return isHttp5xxExpression(ret.getExpression().get());
            }
            if (cur instanceof Statement) break;
            cur = cur.getParentNode().orElse(null);
        }
        return false;
    }

    private static boolean isHttp5xxExpression(Expression expr) {
        if (expr == null) return false;
        String text = expr.toString();
        if (text.contains("internalServerError(")) {
            return true;
        }
        for (String statusConst : HTTP_5XX_STATUS_CONSTANTS) {
            if (text.contains(statusConst)) {
                return true;
            }
        }
        for (MethodCallExpr mc : expr.findAll(MethodCallExpr.class)) {
            String name = mc.getNameAsString();
            if ("internalServerError".equals(name)) {
                return true;
            }
            if (("status".equals(name) || "statusCode".equals(name) || "sendError".equals(name) || "setStatus".equals(name))
                    && has5xxArgument(mc)) {
                return true;
            }
        }
        for (ObjectCreationExpr oce : expr.findAll(ObjectCreationExpr.class)) {
            String typeName = oce.getType().getNameAsString();
            if (typeName.contains("ResponseEntity") || typeName.contains("ResponseStatusException")) {
                for (Expression arg : oce.getArguments()) {
                    if (is5xxStatusExpr(arg)) return true;
                }
            }
        }
        return false;
    }

    private static boolean has5xxArgument(MethodCallExpr mc) {
        for (Expression arg : mc.getArguments()) {
            if (is5xxStatusExpr(arg)) return true;
        }
        return false;
    }

    private static boolean is5xxStatusExpr(Expression arg) {
        if (arg instanceof IntegerLiteralExpr ile) {
            try {
                int code = Integer.parseInt(ile.getValue());
                return code >= 500 && code <= 599;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        String text = arg.toString();
        for (String statusConst : HTTP_5XX_STATUS_CONSTANTS) {
            if (text.contains(statusConst)) return true;
        }
        return false;
    }

    private static boolean isInsideTryProtectedRegion(Node node, TryStmt tryStmt) {
        if (isDescendantOf(node, tryStmt.getTryBlock())) {
            return true;
        }
        for (Expression res : tryStmt.getResources()) {
            if (isDescendantOf(node, res)) return true;
        }
        return false;
    }

    private static boolean isDescendantOf(Node node, Node ancestor) {
        Node cur = node;
        while (cur != null) {
            if (cur == ancestor) return true;
            cur = cur.getParentNode().orElse(null);
        }
        return false;
    }

    private static List<String> extractCaughtExceptionTypes(CatchClause cc) {
        var types = new ArrayList<String>();
        var paramType = cc.getParameter().getType();
        if (paramType instanceof UnionType ut) {
            ut.getElements().forEach(t -> types.add(t.asString()));
        } else {
            types.add(paramType.asString());
        }
        return types;
    }

    private static String findAssignedLocalVarName(Expression expr) {
        Node cur = expr;
        while (cur != null) {
            if (cur instanceof VariableDeclarator vd) {
                return vd.getNameAsString();
            }
            if (cur instanceof AssignExpr ae && ae.getTarget() instanceof NameExpr ne) {
                return ne.getNameAsString();
            }
            if (cur instanceof Statement) break;
            cur = cur.getParentNode().orElse(null);
        }
        return null;
    }

    private static void collectVariableTypes(
            MethodDeclaration md,
            Map<String, String> varTypes,
            Map<String, Set<String>> varAnnotations) {
        md.findAncestor(TypeDeclaration.class).ifPresent(rawOwner -> {
            TypeDeclaration<?> owner = (TypeDeclaration<?>) rawOwner;
            for (BodyDeclaration<?> member : owner.getMembers()) {
                if (member instanceof FieldDeclaration fd) {
                    String typeStr = fd.getCommonType().asString();
                    var anns = new LinkedHashSet<String>();
                    fd.getAnnotations().forEach(a -> anns.add(a.getName().getIdentifier()));
                    for (VariableDeclarator vd : fd.getVariables()) {
                        varTypes.put(vd.getNameAsString(), typeStr);
                        varAnnotations.put(vd.getNameAsString(), anns);
                    }
                }
            }
            if (owner instanceof RecordDeclaration rd) {
                for (Parameter comp : rd.getParameters()) {
                    varTypes.put(comp.getNameAsString(), comp.getTypeAsString());
                }
            }
        });
        for (Parameter p : md.getParameters()) {
            varTypes.put(p.getNameAsString(), p.getTypeAsString());
        }
        md.getBody().ifPresent(body -> {
            for (VariableDeclarator vd : body.findAll(VariableDeclarator.class)) {
                varTypes.put(vd.getNameAsString(), vd.getTypeAsString());
            }
        });
    }

    private static Optional<JavaOutboundCall> classifyJavaCall(
            MethodCallExpr call,
            Map<String, String> varTypes,
            Map<String, Set<String>> varAnnotations,
            boolean importsFeign,
            boolean importsGrpc,
            boolean includeInternalCalls,
            Set<Integer> visitedWebClientRoots) {
        String methodName = call.getNameAsString();
        if (call.getScope().isEmpty()) {
            return Optional.empty();
        }
        Expression scope = call.getScope().get();

        // 1. WebClient fluent chain (detect once at retrieve/exchange/bodyToMono/bodyToFlux/toEntity)
        if (WEB_CLIENT_TERMINAL_METHODS.contains(methodName)) {
            String rootScope = rootReceiverIdentifier(scope);
            String rootType = rootScope != null ? varTypes.getOrDefault(rootScope, "") : "";
            String scopeStr = scope.toString();
            if (rootType.contains("WebClient") || scopeStr.contains("webClient") || scopeStr.contains("WebClient.")) {
                int rootId = System.identityHashCode(findRootExpression(call));
                if (visitedWebClientRoots.add(rootId)) {
                    String target = (rootScope != null ? rootScope : "webClient") + "." + methodName;
                    return Optional.of(new JavaOutboundCall(
                            call, target, "WEB_CLIENT",
                            Set.of("WebClientResponseException", "WebClientRequestException", "WebClientException")));
                }
                return Optional.empty();
            }
        }

        String receiver = directReceiverName(scope);
        if (receiver == null || receiver.isEmpty()) {
            return Optional.empty();
        }
        String recvLower = receiver.toLowerCase(Locale.ROOT);
        if (LOGGING_OR_METRICS_RECEIVERS.contains(recvLower) || LOGGING_OR_METRICS_METHODS.contains(methodName)) {
            return Optional.empty();
        }
        String recvType = varTypes.getOrDefault(receiver, "");
        String recvTypeLower = recvType.toLowerCase(Locale.ROOT);
        Set<String> recvAnns = varAnnotations.getOrDefault(receiver, Set.of());
        String target = receiver + "." + methodName;

        // 2. RestTemplate / RestClient
        if (recvType.contains("RestTemplate") || recvType.contains("RestClient")
                || recvLower.contains("resttemplate") || recvLower.contains("restclient")
                || (REST_TEMPLATE_METHODS.contains(methodName) && (recvLower.contains("rest") || recvLower.contains("http")))) {
            return Optional.of(new JavaOutboundCall(
                    call, target, "REST_TEMPLATE",
                    Set.of("RestClientException", "HttpStatusCodeException", "ResourceAccessException")));
        }

        // 3. gRPC Stub
        if (recvType.endsWith("BlockingStub") || recvType.endsWith("FutureStub") || recvType.endsWith("Stub")
                || recvLower.endsWith("stub") || recvLower.contains("grpc")
                || (importsGrpc && (recvLower.contains("client") || recvTypeLower.contains("stub")))) {
            return Optional.of(new JavaOutboundCall(
                    call, target, "GRPC_STUB",
                    Set.of("StatusRuntimeException")));
        }

        // 4. Feign Client
        if (recvAnns.contains("FeignClient") || recvTypeLower.contains("feign")
                || (importsFeign && (recvType.endsWith("Client") || recvLower.endsWith("client")))
                || (recvType.endsWith("Client") && !recvType.contains("HttpClient") && !recvType.contains("WebClient"))
                || (recvLower.endsWith("client") && !recvLower.contains("httpclient") && !recvLower.contains("webclient"))) {
            return Optional.of(new JavaOutboundCall(
                    call, target, "FEIGN",
                    Set.of("FeignException", "RetryableException")));
        }

        // 5. Generic HTTP Client (java.net.http.HttpClient, OkHttpClient, CloseableHttpClient)
        if (recvType.contains("HttpClient") || recvType.contains("OkHttpClient")
                || recvLower.contains("httpclient")
                || (HTTP_CLIENT_METHODS.contains(methodName) && recvLower.contains("http"))) {
            return Optional.of(new JavaOutboundCall(
                    call, target, "HTTP_CLIENT",
                    Set.of("IOException", "HttpConnectTimeoutException")));
        }

        // 6. Optional intra-service method calls for intermediate backward composition
        if (includeInternalCalls && !Character.isUpperCase(receiver.charAt(0))
                && !Set.of("exceptionally", "exceptionallyAsync", "handle", "handleAsync", "thenApply", "thenCompose",
                "join", "get", "block", "map", "flatMap", "onErrorReturn", "onErrorResume", "onErrorComplete")
                .contains(methodName)) {
            return Optional.of(new JavaOutboundCall(
                    call, target, "METHOD_CALL",
                    Set.of("RuntimeException", "Exception")));
        }

        return Optional.empty();
    }

    private static String directReceiverName(Expression scope) {
        if (scope instanceof EnclosedExpr enc) return directReceiverName(enc.getInner());
        if (scope instanceof NameExpr ne) return ne.getNameAsString();
        if (scope instanceof FieldAccessExpr fa && fa.getScope() instanceof ThisExpr) {
            return fa.getNameAsString();
        }
        return null;
    }

    private static String rootReceiverIdentifier(Expression scope) {
        Expression cur = scope;
        while (cur instanceof MethodCallExpr mc && mc.getScope().isPresent()) {
            cur = mc.getScope().get();
        }
        return directReceiverName(cur);
    }

    private static Expression findRootExpression(MethodCallExpr call) {
        Expression cur = call;
        while (cur instanceof MethodCallExpr mc && mc.getScope().isPresent()) {
            cur = mc.getScope().get();
        }
        return cur;
    }
}
