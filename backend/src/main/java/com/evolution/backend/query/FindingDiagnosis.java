package com.evolution.backend.query;

import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.SourceSpan;
import com.evolution.analysis.evidence.CapabilityGapIdentity;
import com.evolution.analysis.policy.ArchitecturePolicy;
import com.evolution.analysis.policy.PolicyEvaluation;
import com.evolution.analysis.query.ArchitectureCondition;
import com.evolution.analysis.query.ArchitectureQueryService;
import java.util.*;
import java.util.function.Supplier;
import static com.evolution.analysis.query.ArchitectureQuery.*;

/** Bounded retrieval and validation for a diagnosis proposal; never changes a finding or a score. */
public final class FindingDiagnosis {
    static final String VERSION = "architecture.finding-diagnosis:demo-v1";
    private static final int MAX_SOURCES = 16;
    private static final int MAX_LOOKUPS = 64;
    private static final int MAX_INPUT_BYTES = 32_768;

    public enum Mode { LOCAL_REPLAY, LIVE }
    public enum Status { CITATIONS_VALIDATED, WITHHELD }
    public enum Reason { UNSUPPORTED_FINDING, MISSING_SOURCE_EVIDENCE, GRAPH_PROOF_MISMATCH, QUERY_LIMIT,
        MODEL_SCOPE_NOT_REGISTERED, MODEL_UNAVAILABLE, INVALID_MODEL_CITATIONS }
    public record Pin(SnapshotIdentity snapshot, Optional<AnalysisIdentity> analysis, ContentDigest graph, String context,
               ContentDigest report, ContentDigest finding, ContentDigest bundle) {}
    public record Citation(ContentDigest identity, String context, ContentDigest evidence, String family,
                    String path, SourceSpan span, ContentDigest sourceDigest) {}
    public record Proof(VersionedIdentifier rule, Scope scope, String source, String target, String edge,
                 List<String> relationships, Classification classification, ContentDigest region) {}
    public record Gap(CapabilityGapIdentity identity, String reason) {}
    record Input(Pin pin, Proof proof, List<Citation> citations, AssessmentStatus evidenceStatus, List<Gap> gaps) {
        ContentDigest identity() { return ContentDigest.sha256(BoundedJson.encode(List.of(VERSION,this),MAX_INPUT_BYTES)); }
    }
    /** The first slice admits only a typed finding claim and exact source citation keys. */
    record Proposal(ContentDigest input, List<ContentDigest> citations) {}
    static final class InvalidModelOutput extends RuntimeException {}
    interface Model {
        Mode mode();
        VersionedIdentifier version();
        default boolean accepts(ContentDigest input) { return true; }
        Optional<Proposal> propose(Input input, Supplier<StopReason> stop);
    }
    record Outcome(String version, Status status, Optional<Reason> reason, Mode mode, VersionedIdentifier model,
                   Pin pin, Optional<ContentDigest> input, Optional<Proof> proof, Optional<String> explanation,
                   List<Citation> citations, AssessmentStatus evidenceStatus, List<Gap> gaps) {}

    private final Model model;
    FindingDiagnosis(Model model) { this.model = Objects.requireNonNull(model); }

    // An authored response rule replays the bounded response shape without any live model or network call.
    static FindingDiagnosis localReplay() {
        return new FindingDiagnosis(new Model() {
            @Override public Mode mode() { return Mode.LOCAL_REPLAY; }
            @Override public VersionedIdentifier version() { return new VersionedIdentifier("diagnosis.authored-replay","1"); }
            @Override public Optional<Proposal> propose(Input input,Supplier<StopReason> stop) {
                return Optional.of(new Proposal(input.identity(),input.citations().stream().map(Citation::identity).toList()));
            }
        });
    }

    Outcome diagnose(ContentDigest report, PolicyEvaluation.Finding finding, ArchitectureQueryService queries) {
        var bundle = finding.bundle();
        var pin = new Pin(bundle.snapshot(),bundle.analysis(),bundle.graph(),bundle.context(),report,finding.identity(),bundle.identity());
        var gaps = bundle.gaps().stream().map(g -> new Gap(g.gapIdentity(),g.reasonCode())).toList();
        var status = bundle.status();
        if (bundle.sources().isEmpty()) return withhold(pin,status,gaps,Reason.MISSING_SOURCE_EVIDENCE);
        if (bundle.sources().size() > MAX_SOURCES) return withhold(pin,status,gaps,Reason.QUERY_LIMIT);
        boolean isSupportedKind = finding.kind() == ArchitecturePolicy.Kind.FORBIDDEN_DEPENDENCY
                || finding.kind() == ArchitecturePolicy.Kind.ACYCLIC
                || finding.kind() == ArchitecturePolicy.Kind.BOUNDARY
                || finding.kind() == ArchitecturePolicy.Kind.LAYER_BOUNDARY;
        if (!isSupportedKind || bundle.relationships().isEmpty() || bundle.path().isEmpty()
                || !Set.of(Classification.MAY,Classification.MUST).contains(finding.classification()))
            return withhold(pin,status,gaps,Reason.UNSUPPORTED_FINDING);

        var request = bundle.request(); var edge = bundle.relationships().getFirst();
        var edgeIds = bundle.relationships().stream().map(EdgeView::identity).toList();
        if (bundle.predicate().alternatives().size() != 1
                || !bundle.predicate().alternatives().getFirst().term().edges().equals(edgeIds)
                || bundle.relationships().stream().anyMatch(e -> e.supportsTruncated() || e.supports().isEmpty())
                || !queries.graph().identity().equals(pin.graph()) || !queries.graph().snapshot().equals(pin.snapshot())
                || queries.graph().contexts().stream().noneMatch(c -> c.identity().equals(pin.context())
                        && c.snapshot().equals(pin.snapshot()) && c.analysis().equals(pin.analysis())))
            return withhold(pin,status,gaps,Reason.GRAPH_PROOF_MISMATCH);
        if (finding.kind() == ArchitecturePolicy.Kind.FORBIDDEN_DEPENDENCY && bundle.relationships().size() == 1
                && !bundle.path().equals(List.of(edge.source(),edge.target())))
            return withhold(pin,status,gaps,Reason.GRAPH_PROOF_MISMATCH);
        if (queries.assessmentStopReason() != StopReason.NONE) return withhold(pin,status,gaps,Reason.QUERY_LIMIT);
        for (var e : bundle.relationships()) {
            Result<EdgeView> dependencies;
            try { dependencies = queries.dependencies(request,e.source(),Page.first()); }
            catch (IllegalArgumentException missingEndpoint) { return withhold(pin,status,gaps,Reason.GRAPH_PROOF_MISMATCH); }
            if (!readable(dependencies)) return withhold(pin,status,gaps,Reason.QUERY_LIMIT);
            if (!dependencies.items().contains(e)) return withhold(pin,status,gaps,Reason.GRAPH_PROOF_MISMATCH);
        }
        var region = queries.ruleCondition(request,bundle.predicate());
        if (!readable(region)) return withhold(pin,status,gaps,Reason.QUERY_LIMIT);
        if (!region.query().equals(bundle.query()) || !region.items().equals(List.of(bundle.region())))
            return withhold(pin,status,gaps,Reason.GRAPH_PROOF_MISMATCH);
        var witnessFailure = replayFailure(queries,bundle,false);
        if (witnessFailure.isEmpty() && finding.classification() == Classification.MAY)
            witnessFailure = replayFailure(queries,bundle,true);
        if (witnessFailure.isPresent()) return withhold(pin,status,gaps,witnessFailure.orElseThrow());

        var sources = new SourceLookup(queries,bundle);
        var citations = new LinkedHashMap<ContentDigest,Citation>();
        for (var source : bundle.sources()) {
            var citation = sources.resolve(source);
            if (citation.isEmpty()) return withhold(pin,status,gaps,sources.limited ? Reason.QUERY_LIMIT : Reason.MISSING_SOURCE_EVIDENCE);
            citations.putIfAbsent(citation.orElseThrow().identity(),citation.orElseThrow());
        }
        var rule = new VersionedIdentifier(finding.rule().id(),finding.rule().version());
        String proofSource = bundle.path().getFirst();
        String proofTarget = bundle.path().getLast();
        String proofEdge = bundle.relationships().size() == 1 ? edge.identity() : String.join(" -> ", bundle.path());
        List<String> proofKinds = bundle.relationships().stream().flatMap(e -> e.kinds().stream()).distinct().toList();
        var proof = new Proof(rule,request.scope(),proofSource,proofTarget,proofEdge,proofKinds,finding.classification(),bundle.region().jointRegion());
        var input = new Input(pin,proof,List.copyOf(citations.values()),status,gaps);
        ContentDigest inputId;
        try { inputId = input.identity(); }
        catch (BoundedJson.TooLarge large) { return withhold(pin,status,gaps,Reason.QUERY_LIMIT); }
        if (queries.assessmentStopReason() != StopReason.NONE) return withhold(pin,status,gaps,Reason.QUERY_LIMIT);
        if (!model.accepts(inputId)) return withhold(pin,status,gaps,Reason.MODEL_SCOPE_NOT_REGISTERED);
        Optional<Proposal> proposed;
        try { proposed = model.propose(input,queries::assessmentStopReason); }
        catch (InvalidModelOutput invalid) { return withhold(pin,status,gaps,Reason.INVALID_MODEL_CITATIONS); }
        catch (RuntimeException unavailable) { return withhold(pin,status,gaps,Reason.MODEL_UNAVAILABLE); }
        if (queries.assessmentStopReason() != StopReason.NONE) return withhold(pin,status,gaps,Reason.QUERY_LIMIT);
        if (proposed == null || proposed.isEmpty()) return withhold(pin,status,gaps,Reason.MODEL_UNAVAILABLE);
        var proposal = proposed.orElseThrow(); var selected = proposal.citations();
        if (!inputId.equals(proposal.input()) || selected == null || selected.isEmpty() || selected.size() > MAX_SOURCES
                || new HashSet<>(selected).size() != selected.size() || !citations.keySet().containsAll(selected))
            return withhold(pin,status,gaps,Reason.INVALID_MODEL_CITATIONS);

        String configuration = proof.classification() == Classification.MAY ? "in some modeled configurations. The witness and counterexample match the captured model."
                : "throughout the modeled scope. The witness matches the captured model.";
        String explanation = finding.kind() == ArchitecturePolicy.Kind.ACYCLIC
                ? "The cited sources support circular dependency cycle: " + String.join(" → ", bundle.path()) + " " + configuration
                : "The cited sources support the dependency in policy finding " + rule.id() + " " + configuration;
        return new Outcome(VERSION,Status.CITATIONS_VALIDATED,Optional.empty(),model.mode(),model.version(),pin,Optional.of(inputId),
                Optional.of(proof),Optional.of(explanation),selected.stream().map(citations::get).toList(),status,gaps);
    }

    private Outcome withhold(Pin pin,AssessmentStatus status,List<Gap> gaps,Reason reason) {
        return new Outcome(VERSION,Status.WITHHELD,Optional.of(reason),model.mode(),model.version(),pin,
                Optional.empty(),Optional.empty(),Optional.empty(),List.of(),status,gaps);
    }
    private static boolean readable(Result<?> result) {
        return result.operation() == Operation.COMPLETED && !result.bounds().truncated();
    }
    private static Optional<Reason> replayFailure(ArchitectureQueryService query,PolicyEvaluation.EvidenceBundle bundle,boolean counter) {
        var witness = counter ? bundle.region().counterWitness() : bundle.region().witness();
        if (witness.isEmpty()) return Optional.of(Reason.GRAPH_PROOF_MISMATCH);
        var replay = query.replayRuleWitness(bundle.request(),bundle.predicate(),witness.orElseThrow(),counter);
        if (!readable(replay)) return Optional.of(Reason.QUERY_LIMIT);
        return replay.items().size() == 1 && replay.items().getFirst().matched()
                ? Optional.empty() : Optional.of(Reason.GRAPH_PROOF_MISMATCH);
    }

    private static final class SourceLookup {
        private record Key(String context, ContentDigest reference) {}
        private record Document(String path,ContentDigest digest) {}
        private final ArchitectureQueryService queries;
        private final List<Request> requests;
        private final Map<ContentDigest,String> families = new HashMap<>();
        private final Map<Key,Optional<EvidenceView>> proofs = new HashMap<>();
        private final Map<SourceDocumentIdentity,Optional<Document>> documents = new HashMap<>();
        private int lookups;
        private boolean limited;

        SourceLookup(ArchitectureQueryService queries,PolicyEvaluation.EvidenceBundle bundle) {
            this.queries = queries;
            String context = bundle.context();
            var list = new ArrayList<Request>();
            list.add(Request.dependencies(bundle.graph(),context));
            bundle.analysis().map(AnalysisIdentity::value).filter(id -> !id.equals(context))
                    .filter(id -> queries.graph().contexts().stream().anyMatch(c -> c.identity().equals(id)
                            && c.snapshot().equals(bundle.snapshot()) && c.analysis().equals(bundle.analysis())))
                    .ifPresent(id -> list.add(Request.dependencies(bundle.graph(),id)));
            String snap = bundle.snapshot().value();
            if (!snap.equals(context) && queries.graph().contexts().stream().anyMatch(c -> c.identity().equals(snap)))
                list.add(Request.dependencies(bundle.graph(),snap));
            requests = List.copyOf(list);
            bundle.evidence().forEach(ref -> families.put(ref.identity(),ref.family()));
        }
        Optional<Citation> resolve(PolicyEvaluation.SourceEvidence source) {
            String family = families.get(source.evidence());
            if (family == null) return Optional.empty();
            var document = documents.computeIfAbsent(source.span().document(),this::resolveDocument);
            if (document.isEmpty() || !document.orElseThrow().digest().equals(source.sourceDigest())) return Optional.empty();
            for (var request : requests) {
                var item = evidence(request,source.evidence());
                if (item.isPresent() && item.orElseThrow().family().equals(family) && containsSpan(item.orElseThrow().metadata(),source.span())) {
                    String path = document.orElseThrow().path();
                    var id = ContentDigest.sha256Utf8(CanonicalJson.write(List.of(VERSION,request.graph(),request.context(),source,path)));
                    return Optional.of(new Citation(id,request.context(),source.evidence(),family,path,source.span(),source.sourceDigest()));
                }
            }
            return Optional.empty();
        }
        private Optional<Document> resolveDocument(SourceDocumentIdentity identity) {
            Document document = null;
            for (var request : requests) {
                if (!reserve()) return Optional.empty();
                var entity = queries.entity(request,identity.value());
                if (!readable(entity)) { limited = true; return Optional.empty(); }
                for (var item : entity.items()) if (item.kind().equals("SOURCE_DOCUMENT")) for (var ref : item.evidence()) {
                    var descriptor = evidence(request,ref).filter(row -> Set.of("source.document","source.bytes").contains(row.family()));
                    if (descriptor.isEmpty()) continue;
                    var rawMetadata = descriptor.orElseThrow().metadata();
                    var metadata = descriptor.orElseThrow().family().equals("source.bytes") && rawMetadata.get("document") instanceof Map<?,?> doc
                            ? doc : rawMetadata;
                    if (!identity.value().equals(metadata.get("identity")) || !(metadata.get("path") instanceof String path)
                            || !(metadata.get("contentDigest") instanceof String digest)) return Optional.empty();
                    var candidate = new Document(path,new ContentDigest(digest));
                    if (document != null && !document.equals(candidate)) return Optional.empty();
                    document = candidate;
                }
            }
            return limited ? Optional.empty() : Optional.ofNullable(document);
        }
        private Optional<EvidenceView> evidence(Request request,ContentDigest reference) {
            return proofs.computeIfAbsent(new Key(request.context(),reference),key -> {
                if (!reserve()) return Optional.empty();
                var result = queries.evidence(request,reference);
                if (!readable(result)) { limited = true; return Optional.empty(); }
                return result.items().stream().filter(item -> item.identity().equals(reference)).findFirst();
            });
        }
        private boolean reserve() {
            if (++lookups <= MAX_LOOKUPS && queries.assessmentStopReason() == StopReason.NONE) return true;
            limited = true; return false;
        }
        private static boolean containsSpan(Object value,SourceSpan span) {
            if (value instanceof Map<?,?> row) {
                if (span.document().value().equals(row.get("document")) && coordinate(row.get("startLine"),span.startLine())
                        && coordinate(row.get("startColumn"),span.startColumn()) && coordinate(row.get("endLine"),span.endLine())
                        && coordinate(row.get("endColumn"),span.endColumn())) return true;
                return row.values().stream().anyMatch(child -> containsSpan(child,span));
            }
            return value instanceof List<?> list && list.stream().anyMatch(child -> containsSpan(child,span));
        }
        private static boolean coordinate(Object value,int expected) {
            return value instanceof Number number && Integer.toString(expected).equals(number.toString());
        }
    }
}
