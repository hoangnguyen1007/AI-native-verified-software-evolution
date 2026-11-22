package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.policy.PolicyEvaluation;
import com.evolution.analysis.query.ArchitectureQuery;
import com.evolution.analysis.query.ArchitectureQueryService;

import java.util.*;
import java.util.regex.Pattern;

import static com.evolution.analysis.query.ArchitectureQuery.*;

/** Bounded architecture context for each turn. Repository content never gains instruction authority. */
final class ArchitectureChat {
    static final String VERSION = "architecture.chat:v1";
    private static final Page SAMPLE = new Page(4, Optional.empty());
    private static final FindingDiagnosis REPLAY = FindingDiagnosis.localReplay();
    private static final Pattern DIRECT_OFF_TOPIC = Pattern.compile(
            "(?:tell me a joke|what(?: is|'s) the weather(?: today| tomorrow)?|give me a (?:cake|pasta) recipe|"
            + "kể (?:cho )?(?:tôi|mình) (?:nghe )?một câu chuyện cười|dự báo thời tiết (?:hôm nay|ngày mai)|"
            + "thời tiết (?:hôm nay|ngày mai) thế nào)[.!?]*",Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern VIETNAMESE = Pattern.compile("[ăđơưẠ-ỹ]",Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    enum Mode { EXPLORE, DESIGN, REPAIR }
    enum Kind { SUMMARY, STRUCTURE, SYMBOLS, DEPENDENCIES, FINDING, SOURCE }
    record Pin(ContentDigest graph, String snapshot, String context, Optional<FindingRef> finding) {}
    record Message(String role, String content) {}
    record FindingRef(ContentDigest report, ContentDigest identity, ContentDigest bundle) {}
    record Input(String context, Mode mode, List<Message> messages, Optional<String> focus,
                 Optional<FindingRef> finding, boolean allowRemote, boolean evidenceOnly, boolean includeSource) {
        String question() { return messages.getLast().content(); }

        static Input read(Object value) {
            var row = QueryJson.object(value,"context","mode","messages","focus","finding","allowRemote","evidenceOnly","includeSource");
            var messages = GraphJson.array(row.get("messages"));
            if (messages.isEmpty() || messages.size() > 7 || messages.size() % 2 == 0)
                throw new IllegalArgumentException("Expected at most three previous turns and one question");
            var history = new ArrayList<Message>();
            for (Object item : messages) {
                var turn = QueryJson.object(item,"role","content");
                var role = history.size() % 2 == 0 ? "user" : "assistant";
                var content = QueryJson.text(turn,"content").strip();
                if (!role.equals(turn.get("role")) || content.length() > (role.equals("user") ? 2400 : 4000))
                    throw new IllegalArgumentException("Invalid chat turn");
                history.add(new Message(role,content));
            }
            var focus = QueryJson.optionalText(row,"focus");
            if (focus.filter(s -> s.length() > 256).isPresent())
                throw new IllegalArgumentException("Focus exceeds the limit");
            var finding = Optional.ofNullable(row.get("finding")).map(ref -> {
                var pins = QueryJson.object(ref,"report","identity","bundle");
                return new FindingRef(digest(pins,"report"),digest(pins,"identity"),digest(pins,"bundle"));
            });
            return new Input(QueryJson.text(row,"context"),Mode.valueOf(QueryJson.text(row,"mode")),
                    List.copyOf(history),focus,finding,flag(row,"allowRemote"),flag(row,"evidenceOnly"),flag(row,"includeSource"));
        }
        private static boolean flag(Map<String,Object> row, String field) {
            if (!(row.getOrDefault(field,false) instanceof Boolean value)) throw new IllegalArgumentException("Boolean required");
            return value;
        }
        private static ContentDigest digest(Map<String,Object> row, String field) {
            return new ContentDigest(QueryJson.text(row,field));
        }
    }

    record Evidence(String id, Kind kind, String label, String identity, Map<String,?> data) {}
    record Context(Pin pin, List<Evidence> evidence, List<String> limitations, List<FindingDiagnosis.Citation> sources) {
        Map<String,Object> metadata() { return Map.of("pin",pin,"evidence",evidence,"limitations",limitations); }
        ContentDigest identity() { return ContentDigest.sha256Utf8(CanonicalJson.write(List.of(VERSION,metadata()))); }
    }
    record Result(String version, String status, Pin pin, ContentDigest evidenceIdentity, Optional<String> answer,
                  List<String> citations, List<Evidence> evidence, List<String> limitations,
                  Optional<ArchitectureChatModel.Receipt> model) {
        static Result local(Context context, boolean configured) {
            var limitations = new ArrayList<>(context.limitations());
            if (!configured) limitations.add("Configure a chat provider to get a natural-language answer.");
            return new Result(VERSION,"EVIDENCE_ONLY",context.pin(),context.identity(),Optional.empty(),
                    List.of(),context.evidence(),List.copyOf(limitations),Optional.empty());
        }

        static Result advice(Context context, ArchitectureChatModel.Completion completion) {
            var limitations = new ArrayList<>(context.limitations());
            limitations.addAll(completion.limitations());
            return new Result(VERSION,completion.status(),context.pin(),context.identity(),Optional.of(completion.answer()),
                    completion.citations(),context.evidence(),List.copyOf(limitations),Optional.of(completion.receipt()));
        }

        static Result offTopic(Pin pin, String question, Optional<ArchitectureChatModel.Receipt> model) {
            var context = new Context(pin,List.of(),List.of(),List.of());
            String answer = VIETNAMESE.matcher(question).find()
                    ? "Tôi hỗ trợ các câu hỏi về kiến trúc phần mềm. Bạn có thể hỏi về dependency, một finding hoặc cách kiểm chứng một thay đổi."
                    : "I can help with software architecture. Ask about a dependency, a finding, or how to verify a proposed change.";
            return new Result(VERSION,"OUT_OF_SCOPE",pin,context.identity(),Optional.of(answer),
                    List.of(),List.of(),List.of(),model);
        }
    }

    static boolean directlyOutOfScope(Input input) {
        // Deliberately match complete, clear requests. The model judges other phrasing and follow-ups.
        return DIRECT_OFF_TOPIC.matcher(input.question().replaceAll("\\s+"," ")).matches();
    }

    static Pin pin(Input input, KnowledgeGraph.Context partition, ContentDigest graph) {
        return new Pin(graph,partition.snapshot().value(),partition.identity(),input.finding());
    }

    static Context retrieve(Input input, KnowledgeGraph.Context partition, ArchitectureQueryService queries,
                            Optional<PolicyEvaluation.Finding> finding) {
        var pin = pin(input,partition,queries.graph().identity());
        var evidence = new ArrayList<Evidence>();
        var limitations = new ArrayList<String>();
        var request = Request.dependencies(pin.graph(),pin.context());
        var summary = queries.summary(request);
        add(evidence,Kind.SUMMARY,"Captured architecture summary",summary.query().value(),withStatus(summary,
                Map.of("counts",summary.items(),"gapCount",summary.gaps().size())));
        if (summary.status() != AssessmentStatus.COMPLETE) {
            limitations.add("Analysis status is " + summary.status() + ". Missing relationships can change conclusions.");
        }
        if (summary.items().stream().anyMatch(row -> row.nodeCounts().containsKey("MODULE"))) {
            var modules = queries.entities(new Request(pin.graph(),pin.context(),Scope.MODULE,request.projection(),
                    request.direction(),request.relationshipKinds(),request.population(),false,Optional.empty(),request.limits()),"",SAMPLE);
            add(evidence,Kind.STRUCTURE,"Captured modules",modules.query().value(),
                    withStatus(modules,Map.of("modules",modules.items().stream().map(ArchitectureChat::symbol).toList())));
        }
        if (input.focus().isPresent()) retrieveFocus(input.focus().orElseThrow(),pin,queries,evidence,limitations);
        var sources = finding.map(row -> retrieveFinding(input.finding().orElseThrow(),row,queries,evidence,limitations))
                .orElse(List.of());
        if (input.mode() == Mode.REPAIR && finding.isEmpty())
            limitations.add("Attach a published finding to use the captured-source repair workflow.");
        return new Context(pin,List.copyOf(evidence),List.copyOf(limitations),sources);
    }

    private static void retrieveFocus(String focus, Pin pin, ArchitectureQueryService queries,
                                      List<Evidence> evidence, List<String> limitations) {
        var request = new Request(pin.graph(),pin.context(),Scope.ENTITY,Projection.BOUNDED_UNION,Direction.OUTGOING,
                Set.of("graph.depends-on","java.calls","java.extends","java.implements","java.type-uses"),
                Population.all(),false,Optional.empty(),new Limits(1,100_000,6,100_000,500_000,1_000_000,100_000,200_000));
        var matches = queries.entities(request,focus,SAMPLE);
        add(evidence,Kind.SYMBOLS,"Symbols matching " + focus,matches.query().value(),
                withStatus(matches,Map.of("matches",matches.items().stream().map(ArchitectureChat::symbol).toList())));
        if (matches.exactTotal().orElse(0L) != 1 || matches.items().size() != 1) {
            limitations.add("Use a qualified name or symbol identity to select a single match.");
            return;
        }
        var symbol = matches.items().getFirst();
        var deps = queries.expand(request,symbol.identity());
        var edges = new ArrayList<Map<String,?>>();
        for (var neighborhood : deps.items()) {
            var labels = new HashMap<String,String>();
            neighborhood.entities().forEach(entity -> labels.put(entity.identity(),label(entity)));
            for (var edge : neighborhood.edges()) {
                edges.add(Map.of("identity",edge.identity(),"source",edge.source(),"target",edge.target(),
                        "kinds",edge.kinds(),"classification",edge.classification(),
                        "label",labels.getOrDefault(edge.target(),edge.target())));
            }
        }
        add(evidence,Kind.DEPENDENCIES,"Outgoing dependencies of " + label(symbol),deps.query().value(),
                withStatus(deps,Map.of("edges",edges)));
        if (deps.bounds().truncated() || deps.continuation().isPresent())
            limitations.add("Only a bounded sample of outgoing dependencies is included.");
    }

    private static List<FindingDiagnosis.Citation> retrieveFinding(FindingRef ref, PolicyEvaluation.Finding finding, ArchitectureQueryService queries,
                                        List<Evidence> evidence, List<String> limitations) {
        var bundle = finding.bundle();
        var diagnosis = REPLAY.diagnose(ref.report(),finding,queries);
        add(evidence,Kind.FINDING,finding.rule().id(),ref.identity().value(),
                Map.of("rule",finding.rule(),"kind",finding.kind(),"classification",finding.classification(),
                        "status",bundle.status(),"region",bundle.region().jointRegion(),"scope",bundle.request().scope(),
                        "path",bundle.path().stream().limit(8).toList(),"pathLength",bundle.path().size(),
                        "gapCount",bundle.gaps().size(),"diagnosis",diagnosis.status()));
        if (diagnosis.status() != FindingDiagnosis.Status.CITATIONS_VALIDATED)
            limitations.add("Source citations were withheld: " + diagnosis.reason().orElseThrow().name() + ".");
        for (var citation : diagnosis.citations()) {
            add(evidence,Kind.SOURCE,citation.path() + " · line " + citation.span().startLine(),citation.identity().value(),
                    Map.of("path",citation.path(),"span",citation.span(),"sourceDigest",citation.sourceDigest(),
                            "context",citation.context(),"reference",citation.evidence()));
        }
        if (bundle.path().size() > 8) limitations.add("The finding path is sampled.");
        return diagnosis.citations();
    }

    private static Map<String,?> symbol(EntityView entity) {
        return Map.of("identity",entity.identity(),"kind",entity.kind(),"name",label(entity),"context",entity.context());
    }

    private static String label(EntityView entity) {
        return entity.qualifiedName().or(entity::canonicalName).or(entity::spelling).orElse(entity.identity());
    }

    private static Map<String,?> withStatus(ArchitectureQuery.Result<?> result, Map<String,?> fields) {
        var data = new TreeMap<String,Object>(fields);
        data.put("status",result.status());
        data.put("operation",result.operation());
        data.put("truncated",result.bounds().truncated() || result.continuation().isPresent());
        data.put("stopReason",result.bounds().stopReason());
        result.exactTotal().ifPresent(total -> data.put("total",total));
        return Collections.unmodifiableMap(data);
    }

    private static void add(List<Evidence> evidence, Kind kind, String label, String identity, Map<String,?> data) {
        evidence.add(new Evidence("E" + (evidence.size() + 1),kind,label,identity,data));
    }

    private ArchitectureChat() {}
}
