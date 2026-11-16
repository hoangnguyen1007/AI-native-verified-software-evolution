package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.RepositorySnapshot;
import com.evolution.analysis.contract.source.SnapshotFile;
import com.evolution.analysis.evolution.DemoBindingCensus;
import com.evolution.analysis.evolution.EvolutionIndex;
import com.evolution.analysis.evolution.EvolutionSnapshot;
import com.evolution.analysis.evolution.SnapshotComparison;
import com.evolution.analysis.execution.EvidenceVerifier;
import com.evolution.analysis.graph.GraphStore;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.query.ArchitectureQueryService;
import com.evolution.analysis.query.ArchitectureQuery.StopReason;
import java.io.IOException;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Joins the pinned worker input plan, protected runtime evidence, and canonical comparison results. */
public final class RepairVerification {
    public static final String VERSION = "architecture.repair-verification:demo-v1";
    public static final VersionedIdentifier METHOD = new VersionedIdentifier("verification.spring-repair-worker","1");
    public static final VersionedIdentifier PREDICATE = new VersionedIdentifier("verification.spring-profiles","1");
    private static final RepositoryIdentity REPOSITORY = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/m2-fixture.git");
    private static final List<SnapshotFile> COMMON_FILES = List.of(
            new SnapshotFile("build.gradle",ContentDigest.sha256Utf8("plugins { java }")),
            new SnapshotFile("src/main/java/app/App.java",new ContentDigest("sha256:fcbdc791160867c0dc3231d26753fd65d578e9a9abfbc0a97f3fd92b4145d73d")),
            new SnapshotFile("src/main/java/app/Store.java",new ContentDigest("sha256:f4e53088ee1a99dbe059b4d5475984604ced230335fa39b413c1ad65bf47a86c")));
    private static final ContentDigest CONTROL_SOURCE = ContentDigest.sha256Utf8(RepairProposal.SOURCE.replace("{} }","{ /* retained injection */ } }"));
    public static final List<String> RUNTIME_CHECKS = List.of("baseline.build","baseline.dev-off","baseline.dev-on",
            "candidate.build","candidate.dev-off","candidate.dev-on","control.build","control.dev-off","control.dev-on");

    /** Local operator composition can only return an already authenticated claim from the existing receipt verifier. */
    @FunctionalInterface public interface WorkerEvidence {
        Optional<EvidenceVerifier.VerifiedClaim> read(ContentDigest plan,BooleanSupplier cancelled) throws IOException;
    }
    public enum Verdict { VERIFIED, VERIFIED_DEMO, UNVERIFIED, REJECTED }
    enum Status { PASSED, FAILED, NOT_RUN, INCONCLUSIVE }
    record Tree(SnapshotIdentity snapshot,List<SnapshotFile> files) {}
    record Plan(ContentDigest proposal,ContentDigest patch,ContentDigest policy,ContentDigest affectedRegion,
                Tree baseline,Tree candidate,Tree control,List<String> requiredRuntimeChecks) {
        ContentDigest identity() { return CanonicalJson.sha256(List.of(VERSION,this)); }
    }
    record Check(String id,Status status,String reason,Optional<ContentDigest> evidence) {}
    record Comparison(ContentDigest report,ContentDigest graph,SnapshotIdentity snapshot,
                      ContentDigest result,SnapshotComparison.Status status,Set<String> transitions,
                      Set<String> reasons,boolean truncated,Optional<DemoBindingCensus.Receipt> bindingCensus) {}
    record Result(String version,Verdict verdict,ContentDigest proposal,ContentDigest planIdentity,Plan plan,
                  List<Check> checks,Optional<Comparison> candidateComparison,Optional<Comparison> controlComparison,
                  Optional<DemoRepairCapture.Proof> demoProof,String patch) {}
    private record Assessment(Check check,Optional<Comparison> comparison) {}

    private final WorkerEvidence worker;
    private final DemoRepairCapture demoCapture;
    RepairVerification(WorkerEvidence worker,DemoRepairCapture demoCapture) { this.worker=worker; this.demoCapture=demoCapture; }

    Result verify(RepairProposal.Proposal proposal,KnowledgeGraph baseline,PublishedReports.Report report,
                  Optional<ContentDigest> candidateReport,Optional<ContentDigest> controlReport,
                  GraphStore graphs,PublishedReports reports,BooleanSupplier cancelled) {
        var edit = proposal.candidate().edit();
        var base = tree(edit.path(),edit.before());
        var candidate = tree(edit.path(),edit.after());
        var control = tree(edit.path(),CONTROL_SOURCE);
        var value = proposal.candidate();
        var plan = new Plan(proposal.identity(),value.patchDigest(),value.policySource(),value.affectedScope().region(),base,candidate,control,RUNTIME_CHECKS);
        boolean intact = base.snapshot().equals(value.baseline().snapshot()) && baseline.snapshot().equals(base.snapshot())
                && ContentDigest.sha256Utf8(value.patch()).equals(value.patchDigest());
        var pinned = check("fixture-inputs",intact ? Status.PASSED : Status.FAILED,
                intact ? "EXACT_TREE_AND_PATCH_PINS" : "BASELINE_OR_PATCH_MISMATCH",plan.identity());
        var before = new SnapshotComparison.ReportRef(SnapshotComparison.Side.LEFT,value.baseline().report(),report.evaluation().context());
        var observed = intact && !cancelled.getAsBoolean() && demoCapture!=null ? demoCapture.prove(plan) : Optional.<DemoRepairCapture.Proof>empty();
        var sources = observed.isPresent() ? demoCapture.candidateSources(candidate) : Optional.<Map<String,byte[]>>empty();
        var c = compare("candidate-finding",candidate,candidateReport,intact,proposal,baseline,report,before,graphs,reports,cancelled,false,sources);
        var n = compare("mutation-control",control,controlReport,intact,proposal,baseline,report,before,graphs,reports,cancelled,true,Optional.empty());
        var nativeCheck = observed.map(proof->check("isolated-runtime",Status.PASSED,"SCOPED_VM_CHECKS",proof.capture()))
                .orElseGet(()->runtime(plan,intact,cancelled));
        var checks = List.of(pinned,c.check(),n.check(),nativeCheck);
        boolean knownReanalysis = c.comparison().filter(RepairVerification::compatibleDemoScope).isPresent()
                && n.comparison().filter(RepairVerification::compatibleDemoScope).isPresent();
        boolean expectedDemoTransition = observed.stream().flatMap(proof->proof.cells().stream())
                .anyMatch(cell->cell.dev() && cell.baseline() && !cell.candidate() && cell.control());
        Verdict verdict;
        if (checks.stream().anyMatch(check->check.status()==Status.FAILED) || observed.isPresent() && !expectedDemoTransition) verdict=Verdict.REJECTED;
        else if (knownReanalysis && n.check().status()==Status.PASSED && expectedDemoTransition) verdict=Verdict.VERIFIED_DEMO;
        else if (observed.isEmpty() && checks.stream().allMatch(check->check.status()==Status.PASSED)) verdict=Verdict.VERIFIED;
        else verdict=Verdict.UNVERIFIED;
        return new Result(VERSION,verdict,proposal.identity(),plan.identity(),plan,checks,c.comparison(),n.comparison(),observed,value.patch());
    }

    private static boolean compatibleDemoScope(Comparison comparison) {
        // Runtime supplies the closed two-cell truth table. Other compatibility failures still withhold it.
        return !comparison.truncated() && Set.of("ROUTE_REGION_UNPROVED","ROUTE_TRUTH_UNPROVED","RULE_COVERAGE_UNPROVED")
                .containsAll(comparison.reasons());
    }

    private Check runtime(Plan plan,boolean intact,BooleanSupplier cancelled) {
        if (!intact || cancelled.getAsBoolean()) return check("isolated-runtime",Status.NOT_RUN,"INPUTS_NOT_ADMITTED",null);
        if (worker == null) return check("isolated-runtime",Status.NOT_RUN,"REGISTERED_WORKER_EVIDENCE_REQUIRED",null);
        try {
            var proof = worker.read(plan.identity(),cancelled);
            if (proof.isEmpty()) return check("isolated-runtime",Status.NOT_RUN,"PROTECTED_RECEIPT_MISSING",null);
            var verified = proof.orElseThrow(); var request = verified.expected();
            if (!request.scope().equals(plan.proposal()) || !request.inputClosure().equals(plan.identity())
                    || !request.method().equals(METHOD) || !request.predicate().equals(PREDICATE)
                    || !new HashSet<>(verified.checks()).equals(Set.copyOf(RUNTIME_CHECKS)))
                return check("isolated-runtime",Status.FAILED,"WORKER_RECEIPT_SCOPE_MISMATCH",verified.identity());
            return check("isolated-runtime",Status.PASSED,"PROTECTED_WORKER_CHECKS",verified.identity());
        } catch (IOException unavailable) {
            return check("isolated-runtime",Status.INCONCLUSIVE,"WORKER_EVIDENCE_UNAVAILABLE",null);
        }
    }

    private static Assessment compare(String id,Tree expected,Optional<ContentDigest> input,boolean intact,
                                      RepairProposal.Proposal proposal,KnowledgeGraph baseline,PublishedReports.Report left,
                                      SnapshotComparison.ReportRef before,GraphStore graphs,PublishedReports reports,
                                      BooleanSupplier cancelled,boolean negative,Optional<Map<String,byte[]>> sources) {
        if (!intact || cancelled.getAsBoolean()) return partial(id,Status.NOT_RUN,"INPUTS_NOT_ADMITTED",null);
        if (input.isEmpty()) return partial(id,Status.NOT_RUN,"REANALYSIS_REPORT_REQUIRED",null);
        var reportId = input.orElseThrow();
        var report = reports.get(reportId);
        if (report.isEmpty()) return partial(id,Status.NOT_RUN,"REANALYSIS_REPORT_UNAVAILABLE",reportId);
        var after = report.orElseThrow();
        var graph = graphs.load(after.evaluation().graph());
        if (graph.isEmpty()) return partial(id,Status.NOT_RUN,"REANALYSIS_GRAPH_UNAVAILABLE",reportId);
        var observed = graph.orElseThrow();
        if (!observed.snapshot().equals(expected.snapshot())) return partial(id,Status.FAILED,"REANALYSIS_SNAPSHOT_MISMATCH",reportId);
        if (!after.identity().equals(reportId) || !after.evaluation().compilation().equals(left.evaluation().compilation()))
            return partial(id,Status.FAILED,"REANALYSIS_POLICY_MISMATCH",reportId);
        if (!withinBudget(observed) || !withinBudget(baseline))
            return partial(id,Status.INCONCLUSIVE,"DEMO_COMPARISON_LIMIT",reportId);
        var ref = new SnapshotComparison.ReportRef(SnapshotComparison.Side.RIGHT,reportId,after.evaluation().context());
        var selection = new SnapshotComparison.Selection(Set.of("MAIN"),Set.of(),Set.of(),false,false,true,false,false);
        var request = new SnapshotComparison.Request(REPOSITORY,baseline.identity(),observed.identity(),SnapshotComparison.Mode.STRICT,selection);
        var census=sources.flatMap(bytes -> DemoBindingCensus.verify(REPOSITORY,observed.snapshot(),observed.identity(),ref.context(),bytes));
        try (var index=EvolutionIndex.memory()) {
            var graphsReader=EvolutionSnapshot.memory(baseline,observed);
            var references=List.of(before,ref);
            var result=census.isPresent()
                    ? SnapshotComparison.compareWithDemoBindingCensus(request,graphsReader,references,reports.evolutionReader(),census.orElseThrow(),index,32,cancelled)
                    : SnapshotComparison.compare(request,graphsReader,references,reports.evolutionReader(),index,32,cancelled);
            var summary = result.summary();
            var rows = result.page(SnapshotComparison.Section.FINDINGS,50,Optional.empty());
            var compatibility = result.page(SnapshotComparison.Section.COMPATIBILITY,50,Optional.empty());
            boolean truncated = rows.continuation().isPresent() || compatibility.continuation().isPresent();
            var transitions = new TreeSet<String>();
            var reasons = new TreeSet<String>();
            compatibility.items().forEach(row -> collectReasons(row,reasons));
            String finding = proposal.candidate().baseline().finding().value();
            var usedCensus=Optional.<DemoBindingCensus.Receipt>empty();
            for (var row : rows.items()) {
                if (row.get("before") instanceof Map<?,?> operand && finding.equals(operand.get("finding"))
                        && row.get("transition") instanceof String transition) {
                    boolean proved=Boolean.FALSE.equals(row.get("observationOnly")) && row.get("proof")!=null;
                    transitions.add(proved ? transition : "UNPROVED");
                    if (proved && transition.equals("RESOLVED"))
                        usedCensus=census.map(DemoBindingCensus::receipt).filter(p -> p.identity().value().equals(row.get("bindingCensus")));
                    collectReasons(row,reasons);
                }
            }
            var receipt = new Comparison(reportId,observed.identity(),observed.snapshot(),summary.identity(),summary.status(),transitions,reasons,truncated,usedCensus);
            if (!negative && transitions.contains("PERSISTED"))
                return new Assessment(check(id,Status.FAILED,"FINDING_PERSISTS",summary.identity()),Optional.of(receipt));
            // A positive witness rejects a repair in this exact snapshot even when the cross-snapshot
            // comparison cannot prove that all route regions align. Absence still needs a closed proof.
            var witness = retainedFinding(after,observed,proposal,cancelled);
            if (witness.isPresent()) return new Assessment(check(id,negative ? Status.PASSED : Status.FAILED,
                    negative ? "CONTROL_FINDING_WITNESS" : "CANDIDATE_FINDING_WITNESS",witness.orElseThrow()),Optional.of(receipt));
            var expectedChange = negative ? "PERSISTED" : "RESOLVED";
            boolean scopedClosure=usedCensus.isPresent() && summary.status()==SnapshotComparison.Status.PARTIAL
                    && Set.of("RULE_COVERAGE_UNPROVED").containsAll(reasons) && transitions.equals(Set.of("RESOLVED","CLEAN"));
            boolean proved = !truncated && (summary.status()==SnapshotComparison.Status.COMPLETE || scopedClosure)
                    && transitions.contains(expectedChange) && Set.of(expectedChange,"CLEAN").containsAll(transitions);
            return new Assessment(check(id,proved ? Status.PASSED : Status.INCONCLUSIVE,
                    proved ? (negative ? "CONTROL_REJECTED" : scopedClosure ? "FINDING_RESOLVED_IN_DEMO_SCOPE" : "FINDING_RESOLVED")
                            : "FINDING_TRANSITION_UNPROVED",summary.identity()),Optional.of(receipt));
        } catch (IOException | java.util.concurrent.CancellationException unavailable) {
            return partial(id,Status.INCONCLUSIVE,"COMPARISON_UNAVAILABLE",reportId);
        }
    }

    private static Optional<ContentDigest> retainedFinding(PublishedReports.Report report,KnowledgeGraph graph,
                                                            RepairProposal.Proposal proposal,BooleanSupplier cancelled) {
        var guard = ArchitectureQueryService.operationalGuard(cancelled,System.nanoTime()+5_000_000_000L,8_000_000);
        var queries = new ArchitectureQueryService(graph,guard,Optional.empty());
        var rule = proposal.candidate().affectedScope().rule();
        for (var finding : report.evaluation().findings()) {
            if (cancelled.getAsBoolean()) break;
            var bundle = finding.bundle();
            if (!finding.rule().id().equals(rule.id()) || !finding.rule().version().equals(rule.version())
                    || !bundle.snapshot().equals(graph.snapshot()) || !bundle.policySource().equals(proposal.candidate().policySource())
                    || bundle.region().witness().isEmpty()) continue;
            var proof = queries.replayRuleWitness(bundle.request(),bundle.predicate(),bundle.region().witness().orElseThrow(),false);
            if (proof.operation()==com.evolution.analysis.query.ArchitectureQuery.Operation.COMPLETED
                    && !proof.bounds().truncated() && proof.items().size()==1 && proof.items().getFirst().matched()) return Optional.of(proof.identity());
        }
        return Optional.empty();
    }

    private static boolean withinBudget(KnowledgeGraph graph) {
        return graph.nodes().size()<=4096 && graph.edges().size()<=4096 && graph.evidence().size()<=4096;
    }
    private static void collectReasons(Map<String,Object> row,Set<String> reasons) {
        if (row.get("reasons") instanceof List<?> values) for (var reason : values)
            if (reason instanceof String code && code.matches("[A-Z][A-Z0-9_]{0,95}")) reasons.add(code);
    }

    private static Tree tree(String path,ContentDigest edit) {
        var files = new ArrayList<>(COMMON_FILES);
        files.add(new SnapshotFile(path,edit));
        var snapshot = RepositorySnapshot.create(REPOSITORY,Optional.empty(),false,files,List.of());
        return new Tree(snapshot.identity(),snapshot.files());
    }
    private static Assessment partial(String id,Status status,String reason,ContentDigest evidence) {
        return new Assessment(check(id,status,reason,evidence),Optional.empty());
    }
    private static Check check(String id,Status status,String reason,ContentDigest evidence) {
        return new Check(id,status,reason,Optional.ofNullable(evidence));
    }
}
