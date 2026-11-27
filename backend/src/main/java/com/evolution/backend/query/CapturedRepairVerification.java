package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.SnapshotFile;
import com.evolution.analysis.evolution.EvolutionIndex;
import com.evolution.analysis.evolution.EvolutionSnapshot;
import com.evolution.analysis.evolution.SnapshotComparison;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.GraphStore;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.query.ArchitectureQuery.Scope;
import com.evolution.analysis.query.ArchitectureQueryService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Generic evidence-based repair verifier for arbitrary Java multi-module repositories. */
public final class CapturedRepairVerification {
    public static final String VERSION = "architecture.repair-verification:captured-v1";
    public static final VersionedIdentifier METHOD = new VersionedIdentifier("verification.java-repair-worker", "1");
    public static final VersionedIdentifier PREDICATE = new VersionedIdentifier("verification.candidate-build-and-test", "1");
    private static final String CONTROL_COMMENT = "\n// evo-mutation-control\n";
    private static final Set<String> PASSIVE_REASONS = Set.of(
            "RULE_COVERAGE_UNPROVED", "ROUTE_TRUTH_UNPROVED", "ROUTE_REGION_UNPROVED",
            "RULE_PROJECTION_UNAVAILABLE", "EXPECTED_CENSUS_UNAVAILABLE", "COVERAGE_UNPROVED",
            "SUBJECT_COVERAGE_UNPROVED", "ROUTE_CORRESPONDENCE_UNPROVED",
            "PROFILE_UNPROVED", "REPORT_SCOPE_UNPROVED", "CONTEXT_UNAVAILABLE");

    public enum Verdict { VERIFIED, UNVERIFIED, REJECTED }
    public enum Status { PASSED, FAILED, NOT_RUN, INCONCLUSIVE }

    public record EditPin(String path, ContentDigest before, ContentDigest after) {}
    public record TreeSummary(SnapshotIdentity snapshot, int fileCount) {}
    public record Plan(ContentDigest proposal, ContentDigest patch, ContentDigest policy, ContentDigest affectedRegion,
                       TreeSummary baseline, TreeSummary candidate, TreeSummary control,
                       List<EditPin> edits, List<String> affectedModules, List<String> requiredChecks) {
        public ContentDigest identity() { return CanonicalJson.sha256(List.of(VERSION, this)); }
    }
    public record Check(String id, Status status, String reason, Optional<ContentDigest> evidence) {}
    public record Comparison(ContentDigest report, ContentDigest graph, SnapshotIdentity snapshot,
                             ContentDigest result, SnapshotComparison.Status status, Set<String> transitions,
                             Set<String> reasons, boolean truncated, boolean regressionDetected) {}
    public record IsolatedProof(ContentDigest capture, ContentDigest image, ContentDigest inputs, String buildTool,
                                List<String> modules, int compileExit, int testExit, int testsRun, int testsFailed,
                                int testsErrors, ContentDigest classCensusDigest, int classCount, List<String> checks,
                                String isolationMode, List<CapturedRepairExecution.TestFailure> failingTests, String logTail,
                                Optional<CapturedRepairExecution.BehavioralDiffCapture> behavioralDiff,
                                boolean testsSkipped) {
        public IsolatedProof(ContentDigest capture, ContentDigest image, ContentDigest inputs, String buildTool,
                             List<String> modules, int compileExit, int testExit, int testsRun, int testsFailed,
                             int testsErrors, ContentDigest classCensusDigest, int classCount, List<String> checks) {
            this(capture, image, inputs, buildTool, modules, compileExit, testExit, testsRun, testsFailed,
                    testsErrors, classCensusDigest, classCount, checks, "QEMU_IN_DOCKER", List.of(), "", Optional.empty(), false);
        }
        public IsolatedProof(ContentDigest capture, ContentDigest image, ContentDigest inputs, String buildTool,
                             List<String> modules, int compileExit, int testExit, int testsRun, int testsFailed,
                             int testsErrors, ContentDigest classCensusDigest, int classCount, List<String> checks,
                             String isolationMode, List<CapturedRepairExecution.TestFailure> failingTests, String logTail) {
            this(capture, image, inputs, buildTool, modules, compileExit, testExit, testsRun, testsFailed,
                    testsErrors, classCensusDigest, classCount, checks, isolationMode, failingTests, logTail, Optional.empty(), false);
        }
        public IsolatedProof(ContentDigest capture, ContentDigest image, ContentDigest inputs, String buildTool,
                             List<String> modules, int compileExit, int testExit, int testsRun, int testsFailed,
                             int testsErrors, ContentDigest classCensusDigest, int classCount, List<String> checks,
                             String isolationMode, List<CapturedRepairExecution.TestFailure> failingTests, String logTail,
                             Optional<CapturedRepairExecution.BehavioralDiffCapture> behavioralDiff) {
            this(capture, image, inputs, buildTool, modules, compileExit, testExit, testsRun, testsFailed,
                    testsErrors, classCensusDigest, classCount, checks, isolationMode, failingTests, logTail, behavioralDiff, false);
        }
    }
    public record Result(String version, Verdict verdict, ContentDigest proposal, ContentDigest planIdentity, Plan plan,
                         List<Check> checks, Optional<Comparison> candidateComparison, Optional<Comparison> controlComparison,
                         Optional<IsolatedProof> isolatedProof, String patch) {}

    private record BaselineTree(RepositoryIdentity repository, Optional<String> revision, SnapshotIdentity snapshot, List<SnapshotFile> files) {}
    private record Assessment(Check check, Optional<Comparison> comparison) {}

    private final RepairVerification.WorkerEvidence worker;
    private final CapturedRepairExecution capturedExecution;
    private final Map<SnapshotIdentity, ContentDigest> knownGraphs;

    CapturedRepairVerification(RepairVerification.WorkerEvidence worker, CapturedRepairExecution capturedExecution) {
        this(worker, capturedExecution, Map.of());
    }

    CapturedRepairVerification(RepairVerification.WorkerEvidence worker, CapturedRepairExecution capturedExecution,
                               Map<SnapshotIdentity, ContentDigest> knownGraphs) {
        this.worker = worker;
        this.capturedExecution = capturedExecution;
        this.knownGraphs = Map.copyOf(knownGraphs);
    }

    static Optional<RepositoryIdentity> repository(KnowledgeGraph baseline) {
        return readBaselineTree(baseline).map(BaselineTree::repository);
    }

    Result verify(CapturedRepair.Proposal proposal, KnowledgeGraph baseline, PublishedReports.Report report,
                  Optional<ContentDigest> candidateReport, Optional<ContentDigest> controlReport,
                  GraphStore graphs, PublishedReports reports, BooleanSupplier cancelled) {
        var value = proposal.candidate();
        var baselineTree = readBaselineTree(baseline).orElse(null);
        var edits = value.edits().stream().map(e -> new EditPin(e.path(), e.before(), e.after())).toList();
        boolean intact = baselineTree != null
                && baselineTree.snapshot().equals(value.baseline().snapshot())
                && baseline.snapshot().equals(baselineTree.snapshot())
                && ContentDigest.sha256Utf8(value.patch()).equals(value.patchDigest())
                && CapturedRepair.authentic(proposal)
                && matchesBeforeFiles(baselineTree.files(), edits);

        var candidateFiles = intact ? applyFiles(baselineTree.files(), edits) : List.<SnapshotFile>of();
        var candidateSnapshot = intact
                ? RepositorySnapshotOf(baselineTree.repository(), baselineTree.revision(), candidateFiles)
                : value.baseline().snapshot();
        var controlSnapshot = intact
                ? RepositorySnapshotOf(baselineTree.repository(), baselineTree.revision(), applyControl(baselineTree.files(), edits.getFirst(), baseline))
                : value.baseline().snapshot();
        var modules = affectedModules(value.edits(), baseline);
        var requiredChecks = List.of("baseline-and-patch", "candidate-compile", "candidate-test", "candidate-finding");
        var plan = new Plan(proposal.identity(), value.patchDigest(), policySource(report, value.baseline().finding()),
                value.affectedScope().region(),
                new TreeSummary(value.baseline().snapshot(), baselineTree == null ? 0 : baselineTree.files().size()),
                new TreeSummary(candidateSnapshot, candidateFiles.size()),
                new TreeSummary(controlSnapshot, baselineTree == null ? 0 : baselineTree.files().size()),
                edits, modules, requiredChecks);

        var pinned = check("baseline-and-patch", intact ? Status.PASSED : Status.FAILED,
                intact ? "EXACT_TREE_AND_PATCH_PINS" : "BASELINE_OR_PATCH_MISMATCH", plan.identity());

        Optional<CapturedRepairExecution.Proof> vmProof = Optional.empty();
        IOException vmTampered = null;
        if (intact && !cancelled.getAsBoolean() && capturedExecution != null) {
            try { vmProof = capturedExecution.prove(plan, candidateFiles); }
            catch (IOException invalid) { vmTampered = invalid; }
        }

        Optional<ContentDigest> resolvedCandidate = candidateReport.or(() -> intact ? findReport(candidateSnapshot, report, graphs, reports, cancelled) : Optional.empty());
        Optional<ContentDigest> resolvedControl = controlReport.or(() -> intact ? findReport(controlSnapshot, report, graphs, reports, cancelled) : Optional.empty());

        var candidateAssessment = compareCandidate(plan, baselineTree == null ? null : baselineTree.repository(),
                baseline, report, resolvedCandidate, intact, proposal, vmProof, graphs, reports, cancelled);
        var controlAssessment = compareControl(plan, baselineTree == null ? null : baselineTree.repository(),
                baseline, report, resolvedControl, intact, proposal, graphs, reports, cancelled);
        var isolatedCheck = evaluateIsolated(plan, intact, vmProof, vmTampered, cancelled);

        var checks = List.of(pinned, candidateAssessment.check(), controlAssessment.check(), isolatedCheck);
        Verdict verdict;
        if (checks.stream().anyMatch(c -> c.status() == Status.FAILED)) {
            verdict = Verdict.REJECTED;
        } else if (pinned.status() == Status.PASSED
                && candidateAssessment.check().status() == Status.PASSED
                && isolatedCheck.status() == Status.PASSED) {
            verdict = Verdict.VERIFIED;
        } else {
            verdict = Verdict.UNVERIFIED;
        }
        var isolatedView = vmProof.map(p -> new IsolatedProof(p.capture(), p.image(), p.inputs(), p.buildTool(),
                p.modules(), p.compileExit(), p.testExit(), p.testsRun(), p.testsFailed(), p.testsErrors(),
                p.classCensusDigest(), p.classCensus().classCount(), p.checks(),
                p.isolationMode(), p.failingTests(), p.logTail(), p.behavioralDiff(), p.testsSkipped()));
        return new Result(VERSION, verdict, proposal.identity(), plan.identity(), plan, checks,
                candidateAssessment.comparison(), controlAssessment.comparison(), isolatedView, value.patch());
    }

    /**
     * Verifies an ad-hoc repair proposal (no baseline policy finding/report) against the real baseline
     * tree, optional passive candidate reanalysis graph, and optional isolated sandbox execution proof.
     */
    Result verifyAdHoc(CapturedRepair.Proposal proposal, KnowledgeGraph baseline,
                       Optional<ContentDigest> candidateGraphId, GraphStore graphs, BooleanSupplier cancelled) {
        var value = proposal.candidate();
        var baselineTree = readBaselineTree(baseline).orElse(null);
        var edits = value.edits().stream().map(e -> new EditPin(e.path(), e.before(), e.after())).toList();
        boolean proposalAuthentic = ContentDigest.sha256Utf8(value.patch()).equals(value.patchDigest())
                && CapturedRepair.authentic(proposal)
                && baseline.snapshot().equals(value.baseline().snapshot());
        boolean intact = proposalAuthentic
                && baselineTree != null
                && baselineTree.snapshot().equals(value.baseline().snapshot())
                && matchesBeforeFiles(baselineTree.files(), edits);

        var candidateFiles = intact ? applyFiles(baselineTree.files(), edits) : List.<SnapshotFile>of();
        var candidateSnapshot = intact
                ? RepositorySnapshotOf(baselineTree.repository(), baselineTree.revision(), candidateFiles)
                : value.baseline().snapshot();
        var controlSnapshot = (intact && !edits.isEmpty() && !CapturedRepair.ABSENT_DIGEST.equals(edits.getFirst().before()))
                ? RepositorySnapshotOf(baselineTree.repository(), baselineTree.revision(), applyControl(baselineTree.files(), edits.getFirst(), baseline))
                : value.baseline().snapshot();
        var modules = affectedModules(value.edits(), baseline);
        var requiredChecks = List.of("baseline-and-patch", "candidate-compile", "candidate-test");
        var plan = new Plan(proposal.identity(), value.patchDigest(), ContentDigest.sha256Utf8("ad-hoc"),
                value.affectedScope().region(),
                new TreeSummary(value.baseline().snapshot(), baselineTree == null ? 0 : baselineTree.files().size()),
                new TreeSummary(candidateSnapshot, candidateFiles.size()),
                new TreeSummary(controlSnapshot, baselineTree == null ? 0 : baselineTree.files().size()),
                edits, modules, requiredChecks);

        Check pinned;
        if (intact) {
            pinned = check("baseline-and-patch", Status.PASSED, "EXACT_TREE_AND_PATCH_PINS", plan.identity());
        } else if (proposalAuthentic && baselineTree == null) {
            pinned = check("baseline-and-patch", Status.NOT_RUN, "BASELINE_TREE_UNAVAILABLE", plan.identity());
        } else {
            pinned = check("baseline-and-patch", Status.FAILED, "BASELINE_OR_PATCH_MISMATCH", plan.identity());
        }

        Optional<CapturedRepairExecution.Proof> vmProof = Optional.empty();
        IOException vmTampered = null;
        if (intact && !cancelled.getAsBoolean() && capturedExecution != null) {
            try { vmProof = capturedExecution.prove(plan, candidateFiles); }
            catch (IOException invalid) { vmTampered = invalid; }
        }

        Check reanalysisCheck;
        Optional<ContentDigest> resolvedCandGraph = candidateGraphId.or(() -> intact
                ? Optional.ofNullable(knownGraphs.get(candidateSnapshot))
                : Optional.empty());
        if (!intact || cancelled.getAsBoolean()) {
            reanalysisCheck = check("candidate-reanalysis", Status.NOT_RUN, "INPUTS_NOT_ADMITTED", null);
        } else if (resolvedCandGraph.isEmpty()) {
            reanalysisCheck = check("candidate-reanalysis", Status.NOT_RUN, "REANALYSIS_NOT_RUN", null);
        } else {
            var cgId = resolvedCandGraph.orElseThrow();
            var observedOpt = graphs.load(cgId);
            if (observedOpt.isEmpty()) {
                reanalysisCheck = check("candidate-reanalysis", Status.NOT_RUN, "REANALYSIS_GRAPH_UNAVAILABLE", cgId);
            } else if (!observedOpt.orElseThrow().snapshot().equals(candidateSnapshot)) {
                reanalysisCheck = check("candidate-reanalysis", Status.FAILED, "REANALYSIS_SNAPSHOT_MISMATCH", cgId);
            } else {
                reanalysisCheck = check("candidate-reanalysis", Status.PASSED, "PASSIVE_REANALYSIS_MATCHED", cgId);
            }
        }

        var isolatedCheck = evaluateIsolated(plan, intact, vmProof, vmTampered, cancelled);
        var checks = List.of(pinned, reanalysisCheck, isolatedCheck);

        Verdict verdict;
        if (checks.stream().anyMatch(c -> c.status() == Status.FAILED)) {
            verdict = Verdict.REJECTED;
        } else if (pinned.status() == Status.PASSED && isolatedCheck.status() == Status.PASSED) {
            verdict = Verdict.VERIFIED;
        } else {
            verdict = Verdict.UNVERIFIED;
        }
        var isolatedView = vmProof.map(p -> new IsolatedProof(p.capture(), p.image(), p.inputs(), p.buildTool(),
                p.modules(), p.compileExit(), p.testExit(), p.testsRun(), p.testsFailed(), p.testsErrors(),
                p.classCensusDigest(), p.classCensus().classCount(), p.checks(),
                p.isolationMode(), p.failingTests(), p.logTail(), p.behavioralDiff(), p.testsSkipped()));
        return new Result(VERSION, verdict, proposal.identity(), plan.identity(), plan, checks,
                Optional.empty(), Optional.empty(), isolatedView, value.patch());
    }

    private Check evaluateIsolated(Plan plan, boolean intact, Optional<CapturedRepairExecution.Proof> vmProof,
                                   IOException vmTampered, BooleanSupplier cancelled) {
        if (!intact || cancelled.getAsBoolean()) return check("isolated-build-and-test", Status.NOT_RUN, "INPUTS_NOT_ADMITTED", null);
        if (vmTampered != null) return check("isolated-build-and-test", Status.FAILED, "ISOLATED_CAPTURE_INVALID", null);
        if (vmProof.isPresent()) {
            var p = vmProof.orElseThrow();
            if (p.compileExit() == 0 && p.testsSkipped()) {
                return check("isolated-build-and-test", Status.NOT_RUN, "ISOLATED_BUILD_PASSED_TESTS_SKIPPED", p.capture());
            }
            boolean passed = p.compileExit() == 0 && p.testExit() == 0 && p.classCensus().testsPassed();
            return check("isolated-build-and-test", passed ? Status.PASSED : Status.FAILED,
                    passed ? "ISOLATED_VM_BUILD_AND_TESTS_PASSED" : "ISOLATED_BUILD_OR_TESTS_FAILED", p.capture());
        }
        if (worker != null) {
            try {
                var claim = worker.read(plan.identity(), cancelled);
                if (claim.isEmpty()) return check("isolated-build-and-test", Status.NOT_RUN, "PROTECTED_RECEIPT_MISSING", null);
                var verified = claim.orElseThrow();
                var expected = verified.expected();
                if (!expected.scope().equals(plan.proposal()) || !expected.inputClosure().equals(plan.identity())
                        || !expected.method().equals(METHOD) || !expected.predicate().equals(PREDICATE))
                    return check("isolated-build-and-test", Status.FAILED, "WORKER_RECEIPT_SCOPE_MISMATCH", verified.identity());
                return check("isolated-build-and-test", Status.PASSED, "PROTECTED_WORKER_CHECKS", verified.identity());
            } catch (IOException unavailable) {
                return check("isolated-build-and-test", Status.INCONCLUSIVE, "WORKER_EVIDENCE_UNAVAILABLE", null);
            }
        }
        return check("isolated-build-and-test", Status.NOT_RUN, "ISOLATED_EXECUTION_EVIDENCE_REQUIRED", null);
    }

    private Assessment compareCandidate(Plan plan, RepositoryIdentity repository, KnowledgeGraph baseline, PublishedReports.Report left,
                                        Optional<ContentDigest> input, boolean intact, CapturedRepair.Proposal proposal,
                                        Optional<CapturedRepairExecution.Proof> vmProof,
                                        GraphStore graphs, PublishedReports reports, BooleanSupplier cancelled) {
        if (!intact || cancelled.getAsBoolean()) return partial("candidate-finding", Status.NOT_RUN, "INPUTS_NOT_ADMITTED", null);
        if (input.isEmpty()) return partial("candidate-finding", Status.NOT_RUN, "REANALYSIS_REPORT_REQUIRED", null);
        var reportId = input.orElseThrow();
        var afterOpt = reports.get(reportId);
        if (afterOpt.isEmpty()) return partial("candidate-finding", Status.NOT_RUN, "REANALYSIS_REPORT_UNAVAILABLE", reportId);
        var after = afterOpt.orElseThrow();
        var graphOpt = graphs.load(after.evaluation().graph());
        if (graphOpt.isEmpty()) return partial("candidate-finding", Status.NOT_RUN, "REANALYSIS_GRAPH_UNAVAILABLE", reportId);
        var observed = graphOpt.orElseThrow();
        if (!observed.snapshot().equals(plan.candidate().snapshot()))
            return partial("candidate-finding", Status.FAILED, "REANALYSIS_SNAPSHOT_MISMATCH", reportId);
        if (!after.identity().equals(reportId) || !after.evaluation().compilation().equals(left.evaluation().compilation()))
            return partial("candidate-finding", Status.FAILED, "REANALYSIS_POLICY_MISMATCH", reportId);

        var pairedRefs = pairReports(baseline, left, observed, after, reportId, reports);
        var sourceSets = realizedSourceSets(baseline);
        var request = new SnapshotComparison.Request(repository, baseline.identity(), observed.identity(),
                SnapshotComparison.Mode.STRICT, new SnapshotComparison.Selection(sourceSets, Set.of(), Set.of(), false, false, true, false, false));
        try (var index = EvolutionIndex.memory()) {
            var reader = EvolutionSnapshot.memory(baseline, observed);
            var result = SnapshotComparison.compare(request, reader, pairedRefs, reports.evolutionReader(), index, 50, cancelled);
            var summary = result.summary();
            var transitions = new TreeSet<String>();
            var reasons = new TreeSet<String>();
            boolean regressionDetected = hasIntroducedReportFinding(pairedRefs, reports);
            String targetFinding = proposal.candidate().baseline().finding().value();

            String compatCursor = null;
            do {
                var page = result.page(SnapshotComparison.Section.COMPATIBILITY, 50, Optional.ofNullable(compatCursor));
                page.items().forEach(row -> collectReasons(row, reasons));
                compatCursor = page.continuation().orElse(null);
            } while (compatCursor != null);

            String findingCursor = null;
            do {
                var page = result.page(SnapshotComparison.Section.FINDINGS, 50, Optional.ofNullable(findingCursor));
                for (var row : page.items()) {
                    if (row.get("transition") instanceof String transition) {
                        boolean observationOnly = Boolean.TRUE.equals(row.get("observationOnly"));
                        if (!observationOnly && Set.of("INTRODUCED", "UNKNOWN_TO_PRESENT").contains(transition)) {
                            regressionDetected = true;
                        }
                        if (row.get("before") instanceof Map<?, ?> operand && targetFinding.equals(operand.get("finding"))) {
                            boolean proved = !observationOnly && row.get("proof") != null;
                            transitions.add(proved ? transition : (observationOnly ? transition : "UNPROVED"));
                            collectReasons(row, reasons);
                        }
                    }
                }
                findingCursor = page.continuation().orElse(null);
            } while (findingCursor != null);

            var comparison = new Comparison(reportId, observed.identity(), observed.snapshot(),
                    summary.identity(), summary.status(), transitions, reasons, false, regressionDetected);
            if (transitions.contains("PERSISTED"))
                return new Assessment(check("candidate-finding", Status.FAILED, "FINDING_PERSISTS", summary.identity()), Optional.of(comparison));

            var witness = retainedTargetFinding(after, observed, proposal, cancelled);
            if (witness.isPresent())
                return new Assessment(check("candidate-finding", Status.FAILED, "CANDIDATE_FINDING_WITNESS", witness.orElseThrow()), Optional.of(comparison));

            if (regressionDetected)
                return new Assessment(check("candidate-finding", Status.FAILED, "REGRESSION_FINDING_INTRODUCED", summary.identity()), Optional.of(comparison));

            if (summary.status() == SnapshotComparison.Status.COMPLETE && transitions.contains("RESOLVED")
                    && Set.of("RESOLVED", "CLEAN").containsAll(transitions)) {
                return new Assessment(check("candidate-finding", Status.PASSED, "FINDING_RESOLVED", summary.identity()), Optional.of(comparison));
            }

            boolean passiveCompatible = PASSIVE_REASONS.containsAll(reasons)
                    && allSourcesProcessed(observed, after.evaluation().context())
                    && !hasMatchingRouteFinding(after, proposal);
            if (passiveCompatible && vmProof.isPresent() && vmProof.orElseThrow().classCensus().testsPassed()) {
                var census = vmProof.orElseThrow().classCensus();
                boolean stillPresentInBytecode = routeExistsInBytecode(baseline, proposal.candidate(), census);
                if (stillPresentInBytecode) {
                    return new Assessment(check("candidate-finding", Status.FAILED, "BYTECODE_DEPENDENCY_RETAINED", vmProof.orElseThrow().capture()), Optional.of(comparison));
                }
                return new Assessment(check("candidate-finding", Status.PASSED, "FINDING_RESOLVED_WITH_BYTECODE_CENSUS", summary.identity()), Optional.of(comparison));
            }
            return new Assessment(check("candidate-finding", Status.INCONCLUSIVE, "FINDING_TRANSITION_UNPROVED", summary.identity()), Optional.of(comparison));
        } catch (IOException | java.util.concurrent.CancellationException unavailable) {
            return partial("candidate-finding", Status.INCONCLUSIVE, "COMPARISON_UNAVAILABLE", reportId);
        }
    }

    private Assessment compareControl(Plan plan, RepositoryIdentity repository, KnowledgeGraph baseline, PublishedReports.Report left,
                                      Optional<ContentDigest> input, boolean intact, CapturedRepair.Proposal proposal,
                                      GraphStore graphs, PublishedReports reports, BooleanSupplier cancelled) {
        if (!intact || cancelled.getAsBoolean()) return partial("mutation-control", Status.NOT_RUN, "INPUTS_NOT_ADMITTED", null);
        if (input.isEmpty()) return partial("mutation-control", Status.NOT_RUN, "CONTROL_REPORT_OPTIONAL", null);
        var reportId = input.orElseThrow();
        var afterOpt = reports.get(reportId);
        if (afterOpt.isEmpty()) return partial("mutation-control", Status.NOT_RUN, "REANALYSIS_REPORT_UNAVAILABLE", reportId);
        var after = afterOpt.orElseThrow();
        var graphOpt = graphs.load(after.evaluation().graph());
        if (graphOpt.isEmpty()) return partial("mutation-control", Status.NOT_RUN, "REANALYSIS_GRAPH_UNAVAILABLE", reportId);
        var observed = graphOpt.orElseThrow();
        if (!observed.snapshot().equals(plan.control().snapshot()))
            return partial("mutation-control", Status.FAILED, "REANALYSIS_SNAPSHOT_MISMATCH", reportId);
        if (!after.identity().equals(reportId) || !after.evaluation().compilation().equals(left.evaluation().compilation()))
            return partial("mutation-control", Status.FAILED, "REANALYSIS_POLICY_MISMATCH", reportId);

        var witness = retainedTargetFinding(after, observed, proposal, cancelled);
        var comparison = new Comparison(reportId, observed.identity(), observed.snapshot(),
                reportId, SnapshotComparison.Status.PARTIAL, witness.isPresent() ? Set.of("PERSISTED") : Set.of("UNPROVED"),
                Set.of(), false, false);
        if (witness.isPresent())
            return new Assessment(check("mutation-control", Status.PASSED, "CONTROL_FINDING_WITNESS", witness.orElseThrow()), Optional.of(comparison));
        return new Assessment(check("mutation-control", Status.FAILED, "CONTROL_FAILED_TO_RETAIN_FINDING", reportId), Optional.of(comparison));
    }

    private static List<SnapshotComparison.ReportRef> pairReports(KnowledgeGraph baseline, PublishedReports.Report left,
                                                                  KnowledgeGraph observed, PublishedReports.Report right,
                                                                  ContentDigest rightId, PublishedReports reports) {
        var refs = new ArrayList<SnapshotComparison.ReportRef>();
        refs.add(new SnapshotComparison.ReportRef(SnapshotComparison.Side.LEFT, left.identity(), left.evaluation().context()));
        refs.add(new SnapshotComparison.ReportRef(SnapshotComparison.Side.RIGHT, rightId, right.evaluation().context()));
        var leftContexts = contextScopeMap(baseline);
        var rightContexts = contextScopeMap(observed);
        var leftByScope = new HashMap<String, PublishedReports.Report>();
        for (var id : reports.forGraph(baseline.identity())) {
            reports.get(id).filter(r -> r.evaluation().compilation().equals(left.evaluation().compilation()))
                    .ifPresent(r -> {
                        String scope = leftContexts.get(r.evaluation().context());
                        if (scope != null && !r.identity().equals(left.identity())) leftByScope.putIfAbsent(scope, r);
                    });
        }
        for (var id : reports.forGraph(observed.identity())) {
            if (id.equals(rightId)) continue;
            reports.get(id).filter(r -> r.evaluation().compilation().equals(left.evaluation().compilation()))
                    .ifPresent(r -> {
                        String scope = rightContexts.get(r.evaluation().context());
                        if (scope != null && leftByScope.containsKey(scope)) {
                            var match = leftByScope.get(scope);
                            refs.add(new SnapshotComparison.ReportRef(SnapshotComparison.Side.LEFT, match.identity(), match.evaluation().context()));
                            refs.add(new SnapshotComparison.ReportRef(SnapshotComparison.Side.RIGHT, id, r.evaluation().context()));
                        }
                    });
        }
        return List.copyOf(refs);
    }

    private static Set<String> realizedSourceSets(KnowledgeGraph graph) {
        var sets = new TreeSet<String>();
        for (var ctx : graph.contexts()) {
            if (ctx.analysis().isEmpty()) continue;
            graph.evidence(ctx.evidence()).filter(e -> e.family().equals("analysis.manifest")).ifPresent(ev -> {
                try {
                    var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), GraphJson.Limits.defaults()));
                    var values = GraphJson.object(GraphJson.object(obj.get("configuration")).get("values"));
                    String set = Objects.toString(values.get("java.source-set"), "");
                    if (!set.isBlank()) sets.add(set);
                } catch (RuntimeException ignored) {}
            });
        }
        return sets.isEmpty() ? Set.of("MAIN") : Set.copyOf(sets);
    }

    private static boolean hasIntroducedReportFinding(List<SnapshotComparison.ReportRef> pairedRefs, PublishedReports reports) {
        var baselineKeys = new HashSet<String>();
        for (var ref : pairedRefs) {
            if (ref.side() != SnapshotComparison.Side.LEFT) continue;
            var rep = reports.get(ref.identity()).orElse(null);
            if (rep == null) continue;
            for (var f : rep.evaluation().findings()) {
                baselineKeys.add(findingRouteKey(f));
            }
        }
        for (var ref : pairedRefs) {
            if (ref.side() != SnapshotComparison.Side.RIGHT) continue;
            var rep = reports.get(ref.identity()).orElse(null);
            if (rep == null) return true;
            for (var f : rep.evaluation().findings()) {
                if (!baselineKeys.contains(findingRouteKey(f))) return true;
            }
        }
        return false;
    }

    private static String findingRouteKey(com.evolution.analysis.policy.PolicyEvaluation.Finding finding) {
        return finding.rule().id() + ":" + finding.rule().version() + "|" + finding.bundle().path();
    }

    private static Map<String, String> contextScopeMap(KnowledgeGraph graph) {
        var javaScopes = new HashMap<String, String>();
        var result = new HashMap<String, String>();
        for (var ctx : graph.contexts()) {
            if (ctx.analysis().isEmpty()) continue;
            graph.evidence(ctx.evidence()).filter(e -> e.family().equals("analysis.manifest")).ifPresent(ev -> {
                try {
                    var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), GraphJson.Limits.defaults()));
                    var values = GraphJson.object(GraphJson.object(obj.get("configuration")).get("values"));
                    String mod = Objects.toString(values.get("java.module"), "");
                    String set = Objects.toString(values.get("java.source-set"), "");
                    if (!mod.isBlank() && !set.isBlank()) {
                        String scope = mod + "|" + set;
                        javaScopes.put(ctx.identity(), scope);
                        result.put(ctx.identity(), "JAVA|" + scope);
                    }
                } catch (RuntimeException ignored) {}
            });
        }
        for (var ctx : graph.contexts()) {
            if (ctx.analysis().isEmpty() || result.containsKey(ctx.identity())) continue;
            graph.evidence(ctx.evidence()).filter(e -> e.family().equals("spring.context")).ifPresent(ev -> {
                String parentScope = javaScopes.get(ctx.analysis().orElseThrow().value());
                if (parentScope != null) result.put(ctx.identity(), "SPRING|" + parentScope);
            });
        }
        return result;
    }

    private Optional<ContentDigest> findReport(SnapshotIdentity snapshot, PublishedReports.Report baselineReport,
                                               GraphStore graphs, PublishedReports reports, BooleanSupplier cancelled) {
        var graphId = knownGraphs.get(snapshot);
        if (graphId == null) return Optional.empty();
        var baseGraph = graphs.load(baselineReport.evaluation().graph()).orElse(null);
        var candidateGraph = graphs.load(graphId).orElse(null);
        if (baseGraph == null || candidateGraph == null || !candidateGraph.snapshot().equals(snapshot))
            return Optional.empty();
        String targetScope = contextScopeMap(baseGraph).get(baselineReport.evaluation().context());
        var candidateScopes = contextScopeMap(candidateGraph);
        for (var id : reports.forGraph(graphId)) {
            if (cancelled.getAsBoolean()) break;
            var rep = reports.get(id).orElse(null);
            if (rep == null || !rep.evaluation().compilation().equals(baselineReport.evaluation().compilation()))
                continue;
            if (targetScope == null || targetScope.equals(candidateScopes.get(rep.evaluation().context())))
                return Optional.of(id);
        }
        return Optional.empty();
    }

    private static boolean allSourcesProcessed(KnowledgeGraph graph, String context) {
        boolean found = false;
        for (var ev : graph.evidence()) {
            if (!ev.family().equals("frontend.result-index")) continue;
            try {
                var row = GraphJson.object(GraphJson.read(ev.canonicalJson(), GraphJson.Limits.defaults()));
                if (!context.equals(row.get("analysis"))) continue;
                found = true;
                for (var s : GraphJson.array(row.get("sources"))) {
                    if (!"PROCESSED".equals(GraphJson.object(s).get("state"))) return false;
                }
            } catch (RuntimeException invalid) { return false; }
        }
        return found;
    }

    private static boolean routeExistsInBytecode(KnowledgeGraph baseline, CapturedRepair.Candidate candidate,
                                                 CapturedRepairExecution.ClassCensus census) {
        var routeIds = routePathEntities(candidate);
        if (routeIds.size() < 2) return true;
        var contexts = new LinkedHashSet<String>();
        candidate.baseline().analysis().ifPresent(a -> contexts.add(a.value()));
        contexts.add(candidate.baseline().context());
        var entityMatchers = new LinkedHashMap<String, EntityMatcher>();
        for (var id : routeIds) {
            var matcher = buildMatcher(baseline, contexts, id);
            if (matcher == null) return true;
            entityMatchers.put(id, matcher);
        }
        // Verify the primary edited source type is actually present in the compiled bytecode census.
        var sourceMatcher = entityMatchers.get(routeIds.getFirst());
        boolean compiledSourcePresent = census.classes().keySet().stream().anyMatch(sourceMatcher::matches);
        if (!compiledSourcePresent) return true;

        // For both single-hop (FORBIDDEN/BOUNDARY) and multi-hop (ACYCLIC) routes, the route exists iff every hop exists.
        for (int i = 0; i < routeIds.size() - 1; i++) {
            var from = entityMatchers.get(routeIds.get(i));
            var to = entityMatchers.get(routeIds.get(i + 1));
            boolean hopFound = false;
            for (var entry : census.dependencies().entrySet()) {
                if (!from.matches(entry.getKey())) continue;
                for (var dep : entry.getValue()) {
                    if (to.matches(dep)) {
                        hopFound = true;
                        break;
                    }
                }
                if (hopFound) break;
            }
            if (!hopFound) return false;
        }
        return true;
    }

    private static List<String> routePathEntities(CapturedRepair.Candidate candidate) {
        var proof = candidate.affectedScope();
        if (proof.edge().contains(" -> ")) {
            return Arrays.stream(proof.edge().split(" -> ")).map(String::trim).toList();
        }
        return List.of(proof.source(), proof.target());
    }

    private interface EntityMatcher { boolean matches(String internalClassName); }

    private static EntityMatcher buildMatcher(KnowledgeGraph graph, Set<String> contexts, String entityId) {
        var scopeTypes = new HashSet<String>();
        for (var context : contexts) {
            for (var node : graph.nodes(context)) {
                if (!node.identity().equals(entityId)) continue;
                for (var ref : node.evidence()) {
                    var ev = graph.evidence(ref).orElse(null);
                    if (ev == null || !ev.family().equals("java.declaration")) continue;
                    try {
                        var entity = GraphJson.object(GraphJson.object(GraphJson.read(ev.canonicalJson(), GraphJson.Limits.defaults())).get("entity"));
                        String canonical = Objects.toString(entity.get("canonicalName"), "");
                        if (!canonical.startsWith("java:v1:")) continue;
                        var tuple = GraphJson.array(GraphJson.read(canonical.substring("java:v1:".length()), GraphJson.Limits.defaults()));
                        if ("TYPE".equals(node.kind())) {
                            String internal = internalTypeName(tuple);
                            if (internal != null) return cls -> cls.equals(internal) || cls.startsWith(internal + "$");
                        } else if ("PACKAGE".equals(node.kind())) {
                            String pkgPrefix = internalPackagePrefix(tuple);
                            if (pkgPrefix != null) {
                                return cls -> pkgPrefix.isEmpty()
                                        ? !cls.contains("/")
                                        : (cls.startsWith(pkgPrefix) && !cls.substring(pkgPrefix.length()).contains("/"));
                            }
                        }
                    } catch (RuntimeException ignored) {}
                }
                if ("BEAN_DEFINITION_CANDIDATE".equals(node.kind())) {
                    var matchers = beanCandidateMatchers(graph, contexts, node);
                    if (!matchers.isEmpty()) return cls -> matchers.stream().anyMatch(m -> m.matches(cls));
                }
            }
            // Handle MODULE scope by collecting all PROJECT types whose stableScope == entityId.
            for (var node : graph.nodes(context)) {
                if (!"TYPE".equals(node.kind())) continue;
                for (var ref : node.evidence()) {
                    var ev = graph.evidence(ref).orElse(null);
                    if (ev == null || !ev.family().equals("java.declaration")) continue;
                    try {
                        var entity = GraphJson.object(GraphJson.object(GraphJson.read(ev.canonicalJson(), GraphJson.Limits.defaults())).get("entity"));
                        if (entityId.equals(entity.get("stableScope"))) {
                            String canonical = Objects.toString(entity.get("canonicalName"), "");
                            if (canonical.startsWith("java:v1:")) {
                                String internal = internalTypeName(GraphJson.array(GraphJson.read(canonical.substring("java:v1:".length()), GraphJson.Limits.defaults())));
                                if (internal != null) scopeTypes.add(internal);
                            }
                        }
                    } catch (RuntimeException ignored) {}
                }
            }
        }
        if (!scopeTypes.isEmpty()) return cls -> scopeTypes.stream().anyMatch(t -> cls.equals(t) || cls.startsWith(t + "$"));
        return null;
    }

    private static List<EntityMatcher> beanCandidateMatchers(KnowledgeGraph graph, Set<String> contexts, KnowledgeGraph.Node beanNode) {
        var result = new ArrayList<EntityMatcher>();
        var typeIds = new LinkedHashSet<String>();
        for (var ref : beanNode.evidence()) {
            var ev = graph.evidence(ref).orElse(null);
            if (ev == null || !ev.family().equals("spring.candidate")) continue;
            try {
                var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), GraphJson.Limits.defaults()));
                var producer = GraphJson.object(obj.get("producer"));
                String kind = Objects.toString(producer.get("producerKind"), "");
                if ("COMPONENT_CLASS".equals(kind) || "CONFIGURATION_CLASS".equals(kind) || "SUPPLIED_DEFINITION".equals(kind)) {
                    String slot = Objects.toString(producer.get("declarationSlot"), "");
                    if (!slot.isBlank()) typeIds.add(slot);
                }
                if (obj.get("exposedTypes") instanceof List<?> exposed) {
                    for (var item : exposed) {
                        if (item instanceof String id && !id.isBlank()) typeIds.add(id);
                    }
                }
                if ("BEAN_METHOD".equals(kind) && typeIds.isEmpty()) {
                    String owner = Objects.toString(producer.get("discoveryPathKey"), "");
                    if (!owner.isBlank()) typeIds.add(owner);
                }
            } catch (RuntimeException ignored) {}
        }
        for (var context : contexts) {
            for (var node : graph.nodes(context)) {
                if (!"TYPE".equals(node.kind()) || !typeIds.contains(node.identity())) continue;
                for (var ref : node.evidence()) {
                    var ev = graph.evidence(ref).orElse(null);
                    if (ev == null || !ev.family().equals("java.declaration")) continue;
                    try {
                        var entity = GraphJson.object(GraphJson.object(GraphJson.read(ev.canonicalJson(), GraphJson.Limits.defaults())).get("entity"));
                        String canonical = Objects.toString(entity.get("canonicalName"), "");
                        if (!canonical.startsWith("java:v1:")) continue;
                        var tuple = GraphJson.array(GraphJson.read(canonical.substring("java:v1:".length()), GraphJson.Limits.defaults()));
                        String internal = internalTypeName(tuple);
                        if (internal != null && !"java/lang/Object".equals(internal)) {
                            result.add(cls -> cls.equals(internal) || cls.startsWith(internal + "$"));
                        }
                    } catch (RuntimeException ignored) {}
                }
            }
        }
        return result;
    }

    private static String internalPackagePrefix(List<Object> tuple) {
        if (tuple.isEmpty()) return null;
        String tag = GraphJson.string(tuple.getFirst());
        if ("package".equals(tag) && tuple.size() >= 2) {
            String pkg = String.join("/", GraphJson.array(tuple.get(1)).stream().map(GraphJson::string).toList());
            return pkg.isEmpty() ? "" : pkg + "/";
        }
        if ("type".equals(tag) && tuple.size() >= 2) {
            String pkg = String.join("/", GraphJson.array(tuple.get(1)).stream().map(GraphJson::string).toList());
            return pkg.isEmpty() ? "" : pkg + "/";
        }
        if ("member-type".equals(tag) && tuple.size() >= 2 && tuple.get(1) instanceof List<?> owner) {
            @SuppressWarnings("unchecked")
            var ownerList = (List<Object>) owner;
            return internalPackagePrefix(ownerList);
        }
        return null;
    }

    private static String internalTypeName(List<Object> tuple) {
        if (tuple.size() < 3) return null;
        String tag = GraphJson.string(tuple.getFirst());
        if ("type".equals(tag)) {
            String pkg = String.join("/", GraphJson.array(tuple.get(1)).stream().map(GraphJson::string).toList());
            String name = GraphJson.string(tuple.get(2));
            return pkg.isEmpty() ? name : pkg + "/" + name;
        }
        if ("member-type".equals(tag) && tuple.get(1) instanceof List<?> owner) {
            @SuppressWarnings("unchecked")
            String base = internalTypeName((List<Object>) owner);
            return base == null ? null : base + "$" + GraphJson.string(tuple.get(2));
        }
        return null;
    }

    private static boolean hasMatchingRouteFinding(PublishedReports.Report report, CapturedRepair.Proposal proposal) {
        var rule = proposal.candidate().affectedScope().rule();
        String expectedSource = proposal.candidate().affectedScope().source();
        String expectedTarget = proposal.candidate().affectedScope().target();
        for (var f : report.evaluation().findings()) {
            if (!f.rule().id().equals(rule.id()) || !f.rule().version().equals(rule.version())) continue;
            var path = f.bundle().path();
            if (!path.isEmpty() && path.getFirst().equals(expectedSource) && path.getLast().equals(expectedTarget))
                return true;
        }
        return false;
    }

    private static Optional<ContentDigest> retainedTargetFinding(PublishedReports.Report report, KnowledgeGraph graph,
                                                                 CapturedRepair.Proposal proposal, BooleanSupplier cancelled) {
        var guard = ArchitectureQueryService.operationalGuard(cancelled, System.nanoTime() + 10_000_000_000L, 8_000_000);
        var queries = new ArchitectureQueryService(graph, guard, Optional.empty());
        var rule = proposal.candidate().affectedScope().rule();
        var policySource = policySource(report, proposal.candidate().baseline().finding());
        String expectedSource = proposal.candidate().affectedScope().source();
        String expectedTarget = proposal.candidate().affectedScope().target();
        for (var finding : report.evaluation().findings()) {
            if (cancelled.getAsBoolean()) break;
            var bundle = finding.bundle();
            if (!finding.rule().id().equals(rule.id()) || !finding.rule().version().equals(rule.version())
                    || !bundle.snapshot().equals(graph.snapshot()) || !bundle.policySource().equals(policySource)
                    || bundle.region().witness().isEmpty()) continue;
            var path = bundle.path();
            if (path.isEmpty() || !path.getFirst().equals(expectedSource) || !path.getLast().equals(expectedTarget)) continue;
            var proof = queries.replayRuleWitness(bundle.request(), bundle.predicate(), bundle.region().witness().orElseThrow(), false);
            if (proof.operation() == com.evolution.analysis.query.ArchitectureQuery.Operation.COMPLETED
                    && !proof.bounds().truncated() && proof.items().size() == 1 && proof.items().getFirst().matched())
                return Optional.of(proof.identity());
        }
        return Optional.empty();
    }

    private static ContentDigest policySource(PublishedReports.Report report, ContentDigest findingId) {
        return report.evaluation().findings().stream().filter(f -> f.identity().equals(findingId)).findFirst()
                .map(f -> f.bundle().policySource())
                .orElseGet(() -> report.evaluation().findings().stream().findFirst()
                        .map(f -> f.bundle().policySource()).orElse(report.evaluation().compilation()));
    }

    private static Optional<BaselineTree> readBaselineTree(KnowledgeGraph graph) {
        for (var ev : graph.evidence()) {
            if (!ev.family().equals("repository.snapshot")) continue;
            try {
                var raw = GraphJson.object(GraphJson.read(ev.canonicalJson(), GraphJson.Limits.defaults()));
                var repo = new RepositoryIdentity(GraphJson.string(raw.get("repository")));
                var revision = raw.get("revision") instanceof String rev && !rev.isBlank() ? Optional.of(rev) : Optional.<String>empty();
                var snapshotId = new SnapshotIdentity(GraphJson.string(raw.get("identity")));
                var files = new ArrayList<SnapshotFile>();
                for (var item : GraphJson.array(raw.get("files"))) {
                    var row = GraphJson.object(item);
                    files.add(new SnapshotFile(GraphJson.string(row.get("path")), new ContentDigest(GraphJson.string(row.get("contentDigest")))));
                }
                var reconstructed = com.evolution.analysis.contract.source.RepositorySnapshot.create(
                        repo, revision, Boolean.TRUE.equals(raw.get("dirty")), files, List.of());
                if (!reconstructed.identity().equals(snapshotId)) return Optional.empty();
                return Optional.of(new BaselineTree(repo, revision, snapshotId, reconstructed.files()));
            } catch (RuntimeException invalid) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static boolean matchesBeforeFiles(List<SnapshotFile> files, List<EditPin> edits) {
        var map = new HashMap<String, ContentDigest>();
        files.forEach(f -> map.put(f.path(), f.contentDigest()));
        return edits.stream().allMatch(e -> CapturedRepair.ABSENT_DIGEST.equals(e.before())
                ? !map.containsKey(e.path())
                : e.before().equals(map.get(e.path())));
    }

    private static List<SnapshotFile> applyFiles(List<SnapshotFile> baseline, List<EditPin> edits) {
        var byPath = new TreeMap<String, ContentDigest>();
        baseline.forEach(f -> byPath.put(f.path(), f.contentDigest()));
        for (var e : edits) {
            if (CapturedRepair.ABSENT_DIGEST.equals(e.after())) {
                byPath.remove(e.path());
            } else {
                byPath.put(e.path(), e.after());
            }
        }
        return byPath.entrySet().stream()
                .map(entry -> new SnapshotFile(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static List<SnapshotFile> applyControl(List<SnapshotFile> baseline, EditPin first, KnowledgeGraph graph) {
        var afterDigest = controlDigest(first.path(), first.before(), graph);
        return applyFiles(baseline, List.of(new EditPin(first.path(), first.before(), afterDigest)));
    }

    static ContentDigest controlDigest(String path, ContentDigest beforeDigest, KnowledgeGraph baseline) {
        byte[] bytes = readSourceBytes(baseline, path, beforeDigest);
        byte[] suffix = CONTROL_COMMENT.getBytes(StandardCharsets.UTF_8);
        byte[] combined = Arrays.copyOf(bytes, bytes.length + suffix.length);
        System.arraycopy(suffix, 0, combined, bytes.length, suffix.length);
        return ContentDigest.sha256(combined);
    }

    static byte[] readSourceBytes(KnowledgeGraph graph, String path, ContentDigest digest) {
        for (var ev : graph.evidence()) {
            if (!ev.family().equals("source.content")) continue;
            try {
                var row = GraphJson.object(GraphJson.read(ev.canonicalJson(), new GraphJson.Limits(1_000_000, 16, 128)));
                if (digest.value().equals(row.get("contentDigest"))) {
                    byte[] decoded = Base64.getDecoder().decode(GraphJson.string(row.get("bytesBase64")));
                    if (ContentDigest.sha256(decoded).equals(digest)) return decoded;
                }
            } catch (RuntimeException ignored) {}
        }
        return new byte[0];
    }

    private static SnapshotIdentity RepositorySnapshotOf(RepositoryIdentity repo, Optional<String> revision, List<SnapshotFile> files) {
        return com.evolution.analysis.contract.source.RepositorySnapshot.create(repo, revision, true, files, List.of()).identity();
    }

    private static List<String> affectedModules(List<CapturedRepair.Edit> edits, KnowledgeGraph graph) {
        var modulePaths = new HashMap<String, String>();
        for (var ev : graph.evidence()) {
            if (ev.family().equals("module.descriptor")) {
                try {
                    var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), GraphJson.Limits.defaults()));
                    if (obj.get("identity") instanceof String id && obj.get("path") instanceof String p) {
                        modulePaths.putIfAbsent(id, p);
                    }
                } catch (RuntimeException ignored) {}
            } else if (ev.family().equals("build.module")) {
                try {
                    var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), new GraphJson.Limits(2_000_000, 32, 100_000)));
                    if (obj.get("descriptor") instanceof Map<?, ?> rawDesc) {
                        var desc = GraphJson.object(rawDesc);
                        if (desc.get("identity") instanceof String id && desc.get("path") instanceof String p) {
                            modulePaths.putIfAbsent(id, p);
                        }
                    }
                } catch (RuntimeException ignored) {}
            }
        }
        var modules = new TreeSet<String>();
        for (var edit : edits) {
            for (var ev : graph.evidence()) {
                if (!ev.family().equals("source.document")) continue;
                try {
                    var doc = GraphJson.object(GraphJson.read(ev.canonicalJson(), GraphJson.Limits.defaults()));
                    if (edit.path().equals(doc.get("path")) && doc.get("module") instanceof String m) {
                        String resolved = modulePaths.getOrDefault(m, m);
                        if (!resolved.startsWith("module:")) {
                            modules.add(resolved.isBlank() ? "." : resolved);
                        }
                    }
                } catch (RuntimeException ignored) {}
            }
        }
        return List.copyOf(modules);
    }

    private static void collectReasons(Map<String, Object> row, Set<String> reasons) {
        if (row.get("reasons") instanceof List<?> values) {
            for (var reason : values) {
                if (reason instanceof String code && code.matches("[A-Z][A-Z0-9_]{0,95}")) reasons.add(code);
            }
        }
    }

    private static Assessment partial(String id, Status status, String reason, ContentDigest evidence) {
        return new Assessment(check(id, status, reason, evidence), Optional.empty());
    }

    private static Check check(String id, Status status, String reason, ContentDigest evidence) {
        return new Check(id, status, reason, Optional.ofNullable(evidence));
    }
}
