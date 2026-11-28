package com.evolution.analysis.policy;

import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.polyglot.FailCloseResilienceAnalyzer;
import com.evolution.analysis.polyglot.FailCloseResilienceAnalyzer.CallFrame;
import com.evolution.analysis.polyglot.FailCloseResilienceAnalyzer.ExceptionTransformation;
import com.evolution.analysis.polyglot.FailCloseResilienceAnalyzer.PathVerdict;
import com.evolution.analysis.polyglot.FailCloseResilienceAnalyzer.ResilienceReport;
import com.evolution.analysis.polyglot.FailCloseResilienceAnalyzer.ResilienceVerdict;
import com.evolution.analysis.query.*;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;
import static com.evolution.analysis.policy.PolicyEvaluation.*;
import static com.evolution.analysis.query.ArchitectureQuery.*;

/** Evaluates compiled plans inside one exact graph context. */
public final class PolicyEvaluator {
    private record Route(List<String> vertices,List<EdgeView> edges,boolean eligible) {}
    public PolicyEvaluation.Result evaluate(PolicyCompiler.Result compilation,PolicyGraphMapper.Plan plan,
                                            ArchitectureQueryService service,PolicyEvaluation.Limits limits){
        Objects.requireNonNull(compilation);Objects.requireNonNull(plan);Objects.requireNonNull(service);Objects.requireNonNull(limits);
        if(!compilation.identity().equals(plan.compilation())||!service.graphIdentity().equals(plan.graph()))
            throw new IllegalArgumentException("Foreign policy compilation or graph");
        var context=service.graph().contexts().stream().filter(c->c.identity().equals(plan.context())).findFirst()
                .orElseThrow(()->new IllegalArgumentException("Foreign policy context"));
        var expected=compilation.policy().stream().flatMap(p->p.rules().stream()).toList();
        if(!expected.equals(plan.rules().stream().map(PolicyGraphMapper.RulePlan::rule).toList()))throw new IllegalArgumentException("Incomplete or foreign rule denominator");
        for(var rule:plan.rules())if(!rule.request().graph().equals(plan.graph())||!rule.request().context().equals(plan.context())
                ||rule.request().scope()!=rule.rule().header().scope()||!rule.request().relationshipKinds().equals(rule.rule().header().relationships())
                ||rule.request().projection()!=Projection.BOUNDED_UNION||rule.request().direction()!=Direction.OUTGOING)
            throw new IllegalArgumentException("Foreign rule request");
        var evidenceContext=new EvidenceContext(service.graph().snapshot(),context.analysis());var mapping=plan.identity();
        var provenance=new ArrayList<EvidenceReference>();
        provenance.add(new EvidenceReference(context.evidence(),service.graph().evidence(context.evidence()).orElseThrow().family()));
        context.analysis().ifPresent(a->service.graph().contexts().stream().filter(c->c.identity().equals(a.value())).findFirst()
                .ifPresent(c->provenance.add(new EvidenceReference(c.evidence(),service.graph().evidence(c.evidence()).orElseThrow().family()))));
        var contextGaps=new ArrayList<CapabilityGapRecord>();Map<String,Object> contextPayload;
        try{contextPayload=GraphJson.object(GraphJson.read(
                service.graph().evidence(context.evidence()).orElseThrow().canonicalJson(),new GraphJson.Limits(1_000_000,64,20_000)));}
        catch(IllegalArgumentException malformed){contextPayload=Map.of();contextGaps.add(PolicyGaps.create(evidenceContext,PolicyEvaluation.VERSION,plan.graph(),"CONTEXT_EVIDENCE_UNAVAILABLE",plan.context()));}
        var spaces=contextPayload.get("space") instanceof String space?List.of(space):List.<String>of();
        var upstream=new ArrayList<UpstreamEvidence>();
        if(service.graph().nodes().size()>limits.maxInputItems())contextGaps.add(PolicyGaps.create(evidenceContext,PolicyEvaluation.VERSION,plan.graph(),"UPSTREAM_EVIDENCE_LIMIT",limits));
        else for(var node:service.graph().nodes())if((node.kind().equals("CAPABILITY_GAP")||node.kind().equals("PROVIDER_CONFLICT"))
                &&(node.context().equals(plan.context())||node.context().equals(service.graph().snapshot().value())||context.analysis().map(a->a.value().equals(node.context())).orElse(false))){
            for(var ref:node.evidence()){
                if(upstream.size()==limits.maxEvidenceReads()){
                    contextGaps.add(PolicyGaps.create(evidenceContext,PolicyEvaluation.VERSION,plan.graph(),"UPSTREAM_EVIDENCE_LIMIT",limits));break;
                }
                upstream.add(new UpstreamEvidence(node.context(),ref,service.graph().evidence(ref).orElseThrow().family()));
            }
            if(upstream.size()==limits.maxEvidenceReads()){
                contextGaps.add(PolicyGaps.create(evidenceContext,PolicyEvaluation.VERSION,plan.graph(),"UPSTREAM_EVIDENCE_LIMIT",limits));break;
            }
        }
        var collector=new PolicyEvidenceCollector(service.graph(),plan.context(),limits,evidenceContext);
        var allGaps=new ArrayList<>(plan.gaps());allGaps.addAll(contextGaps);var results=new ArrayList<RuleResult>();int remaining=limits.maxFindings();
        for(var mapped:plan.rules()){
            var gaps=new ArrayList<>(mapped.gaps());gaps.addAll(contextGaps);var routes=new ArrayList<Route>();boolean complete=mapped.status()==AssessmentStatus.COMPLETE&&contextGaps.isEmpty();
            boolean withheld=mapped.status()==AssessmentStatus.WITHHELD;
            if(withheld)gaps.add(gap(evidenceContext,mapping,mapped.rule(),"MAPPING_WITHHELD",limits));
            else if(remaining==0&&!mapped.candidates().isEmpty()){
                withheld=true;complete=false;gaps.add(gap(evidenceContext,mapping,mapped.rule(),"EVALUATION_LIMIT",limits));
            }else if(mapped.rule() instanceof ArchitecturePolicy.Cycles){
                var eligible=new TreeSet<String>();mapped.candidates().forEach(c->eligible.add(c.edge().identity()));
                var cycles=service.policyCycles(mapped.request(),eligible);gaps.addAll(cycles.gaps());
                complete&=cycles.status()==AssessmentStatus.COMPLETE&&!cycles.bounds().truncated()&&cycles.operation()==Operation.COMPLETED;
                var matches=new HashMap<String,PolicySelector.Match>();mapped.candidates().forEach(c->matches.put(c.edge().identity(),c.eligibility()));
                for(var cycle:cycles.items())routes.add(new Route(cycle.vertices(),cycle.edges(),cycle.edges().stream().allMatch(e->matches.get(e.identity())==PolicySelector.Match.YES)));
            }else for(var candidate:mapped.candidates()){
                routes.add(new Route(List.of(candidate.edge().source(),candidate.edge().target()),List.of(candidate.edge()),candidate.eligibility()==PolicySelector.Match.YES));
                if(routes.size()>remaining)break;
            }
            // Canonical ordering and one global output budget close the denominator across all rules.
            routes.sort(Comparator.comparing(r->com.evolution.analysis.contract.serialization.CanonicalJson.write(r.vertices())));
            if(routes.size()>remaining){routes.subList(remaining,routes.size()).clear();complete=false;gaps.add(gap(evidenceContext,mapping,mapped.rule(),"EVALUATION_LIMIT",limits));}
            var alternatives=new ArrayList<ArchitectureCondition.Alternative>();var findings=new ArrayList<Finding>();
            for(var route:routes){
                var evidence=collector.collect(route.edges());gaps.addAll(evidence.gaps());
                var alternative=new ArchitectureCondition.Alternative(new ArchitectureCondition.Term(route.edges().stream().map(EdgeView::identity).toList()),route.eligible()&&evidence.complete()&&contextGaps.isEmpty());
                alternatives.add(alternative);
                var predicate=new ArchitectureCondition.RulePredicate(List.of(alternative),true);
                var evaluated=service.ruleCondition(mapped.request(),predicate);gaps.addAll(evaluated.gaps());
                var region=evaluated.items().getFirst();
                if(region.classification()!=Classification.NEVER){
                    var local=new ArrayList<>(mapped.gaps());local.addAll(contextGaps);local.addAll(evidence.gaps());local.addAll(evaluated.gaps());
                    var status=local.isEmpty()?AssessmentStatus.COMPLETE:AssessmentStatus.PARTIAL;
                    var bundle=new EvidenceBundle(service.graph().snapshot(),context.analysis(),plan.graph(),plan.context(),compilation.policy().orElseThrow().identity(),
                            compilation.source(),compilation.identity(),mapping,spaces,provenance,upstream,mapped.rule(),mapped.request(),evaluated.query(),predicate,region,route.vertices(),route.edges(),
                            evidence.supports(),evidence.sources(),evidence.references(),status,ordered(local),
                            List.of("Witnesses replay the modeled region/formula; fresh Spring semantic or runtime replay is not claimed.",
                                    "Source spans belong to supporting declarations/occurrences; derived relationships have no invented source span."));
                    findings.add(new Finding(mapped.rule().header(),mapped.rule().kind(),region.classification(),bundle));
                }
                remaining--;
            }
            var predicate=new ArchitectureCondition.RulePredicate(alternatives,complete&&!withheld);
            var aggregate=service.ruleCondition(mapped.request(),predicate);
            gaps.addAll(aggregate.gaps());var region=aggregate.items().getFirst();
            var status=withheld?AssessmentStatus.WITHHELD:gaps.isEmpty()?AssessmentStatus.COMPLETE:AssessmentStatus.PARTIAL;
            results.add(new RuleResult(mapped.rule(),status,region.classification(),aggregate.query(),predicate,region,findings.stream().sorted(Comparator.comparing(Finding::identity)).toList(),ordered(gaps)));
            allGaps.addAll(gaps);
        }
        var status=compilation.policy().isEmpty()||!results.isEmpty()&&results.stream().allMatch(r->r.status()==AssessmentStatus.WITHHELD)
                ?AssessmentStatus.WITHHELD:allGaps.isEmpty()?AssessmentStatus.COMPLETE:AssessmentStatus.PARTIAL;
        return new PolicyEvaluation.Result(compilation.identity(),mapping,plan.graph(),plan.context(),limits,status,results,ordered(allGaps),upstream);
    }
    private static List<CapabilityGapRecord> ordered(List<CapabilityGapRecord> values){return values.stream().distinct().sorted().toList();}
    private static CapabilityGapRecord gap(EvidenceContext context,ContentDigest mapping,ArchitecturePolicy.Rule rule,String reason,PolicyEvaluation.Limits limits){
        return PolicyGaps.create(context,PolicyEvaluation.VERSION,mapping,reason,List.of(rule.identity(),limits));
    }

    // ─── WP1.2: Declarative fail-close-tier-policy (Mechanism vs. Policy Separation, I4) ───

    public static final String FAIL_CLOSE_TIER_POLICY_KEY = "fail-close-tier-policy";
    public static final String FAIL_CLOSE_TIER_RULE_KIND = "FAIL_CLOSE_TIER_POLICY";
    public static final VersionedIdentifier FAIL_CLOSE_POLICY_VERSION =
            new VersionedIdentifier("policy.fail-close-tier", "wp1.2-v1");

    /** Declarative tier ordering entry (smaller rank = higher criticality, e.g. T0=0 > T1=1 > T2=2 > T3=3 > EXTERNAL=4). */
    public record TierDefinition(String name, int rank) implements Comparable<TierDefinition> {
        public TierDefinition {
            name = ContractChecks.token(name, "tier name");
            if (rank < 0 || rank > 1000) {
                throw new IllegalArgumentException("Tier rank must be in 0..1000");
            }
        }

        @Override
        public int compareTo(TierDefinition other) {
            int cmp = Integer.compare(rank, other.rank);
            return cmp != 0 ? cmp : name.compareTo(other.name);
        }
    }

    /** Single declarative rule inside {@code fail-close-tier-policy}. */
    public record FailCloseTierRule(
            String id,
            String version,
            ArchitecturePolicy.Severity severity,
            Set<String> fromTiers,
            Set<String> toTiers,
            boolean forbidLowerTierFailClose,
            boolean forbidExternalFailClose
    ) {
        public FailCloseTierRule {
            id = ContractChecks.namespacedId(id, "fail-close rule id");
            version = ContractChecks.token(version == null || version.isBlank() ? "1" : version, "fail-close rule version");
            Objects.requireNonNull(severity, "severity");
            fromTiers = Collections.unmodifiableSet(new TreeSet<>(Objects.requireNonNull(fromTiers, "fromTiers")));
            toTiers = Collections.unmodifiableSet(new TreeSet<>(toTiers == null ? Set.of() : toTiers));
            if (fromTiers.isEmpty()) {
                throw new IllegalArgumentException("fail-close-tier-policy rule requires non-empty fromTiers");
            }
        }
    }

    /** Parsed declarative {@code fail-close-tier-policy} document conforming to {@code architecture-policy:v1}. */
    public record FailCloseTierPolicy(
            String id,
            String version,
            Map<String, Integer> tierRanks,
            Map<String, String> declaredServiceTiers,
            List<FailCloseTierRule> rules
    ) {
        public FailCloseTierPolicy {
            id = ContractChecks.namespacedId(id, "policy id");
            version = ContractChecks.token(version, "policy version");
            tierRanks = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(tierRanks, "tierRanks")));
            declaredServiceTiers = Collections.unmodifiableMap(new TreeMap<>(
                    declaredServiceTiers == null ? Map.of() : declaredServiceTiers));
            rules = Objects.requireNonNull(rules, "rules").stream()
                    .sorted(Comparator.comparing(FailCloseTierRule::id))
                    .toList();
            if (tierRanks.isEmpty()) {
                throw new IllegalArgumentException("fail-close-tier-policy requires at least one declared tier");
            }
            if (rules.isEmpty() || rules.size() > 256) {
                throw new IllegalArgumentException("fail-close-tier-policy requires 1..256 rules");
            }
        }

        public ContentDigest identity() {
            return ContentDigest.sha256Utf8(CanonicalJson.write(List.of(FAIL_CLOSE_POLICY_VERSION, this)));
        }
    }

    /** Single policy finding for a violating or indeterminate RPC fail-close path, carrying its {@code file:line} flamegraph. */
    public record FailClosePolicyFinding(
            String ruleId,
            ArchitecturePolicy.Severity severity,
            Classification classification,
            TrustTier trustTier,
            String inboundId,
            String outboundId,
            String inboundService,
            String outboundService,
            Optional<String> inboundTier,
            Optional<String> outboundTier,
            String pathDigest,
            ResilienceVerdict resilienceVerdict,
            List<CallFrame> flamegraph,
            String flamegraphChain,
            Set<String> propagatedExceptions,
            List<ExceptionTransformation> transformations,
            Optional<String> gapReason
    ) {
        public FailClosePolicyFinding {
            ruleId = Objects.requireNonNull(ruleId, "ruleId").strip();
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(classification, "classification");
            trustTier = trustTier == null ? TrustTier.SOURCE_SYNTAX : trustTier;
            inboundId = Objects.requireNonNull(inboundId, "inboundId").strip();
            outboundId = Objects.requireNonNull(outboundId, "outboundId").strip();
            inboundService = inboundService == null ? "" : inboundService.strip();
            outboundService = outboundService == null ? "" : outboundService.strip();
            inboundTier = inboundTier == null ? Optional.empty() : inboundTier;
            outboundTier = outboundTier == null ? Optional.empty() : outboundTier;
            pathDigest = Objects.requireNonNull(pathDigest, "pathDigest").strip();
            Objects.requireNonNull(resilienceVerdict, "resilienceVerdict");
            flamegraph = List.copyOf(Objects.requireNonNull(flamegraph, "flamegraph"));
            flamegraphChain = flamegraphChain == null ? "" : flamegraphChain.strip();
            propagatedExceptions = Collections.unmodifiableSet(new TreeSet<>(
                    propagatedExceptions == null ? Set.of() : propagatedExceptions));
            transformations = (transformations == null ? List.<ExceptionTransformation>of() : transformations)
                    .stream().distinct().sorted().toList();
            gapReason = gapReason == null ? Optional.empty() : gapReason;
        }

        public ContentDigest identity() {
            return ContentDigest.sha256Utf8(CanonicalJson.write(List.of(FAIL_CLOSE_POLICY_VERSION, toView())));
        }

        public Map<String, Object> toView() {
            var m = new TreeMap<String, Object>();
            var fg = new TreeMap<String, Object>();
            fg.put("chain", flamegraphChain);
            fg.put("frames", flamegraph.stream().map(CallFrame::toView).toList());
            fg.put("resilienceVerdict", resilienceVerdict.name());
            fg.put("trustTier", trustTier.name());
            m.put("callPathFlamegraph", Collections.unmodifiableMap(fg));
            m.put("classification", classification.name());
            m.put("flamegraph", flamegraph.stream().map(CallFrame::toView).toList());
            m.put("flamegraphChain", flamegraphChain);
            gapReason.ifPresent(g -> m.put("gapReason", g));
            m.put("inboundId", inboundId);
            m.put("inboundService", inboundService);
            inboundTier.ifPresent(t -> m.put("inboundTier", t));
            m.put("outboundId", outboundId);
            m.put("outboundService", outboundService);
            outboundTier.ifPresent(t -> m.put("outboundTier", t));
            m.put("pathDigest", pathDigest);
            m.put("propagatedExceptions", List.copyOf(propagatedExceptions));
            m.put("resilienceVerdict", resilienceVerdict.name());
            m.put("ruleId", ruleId);
            m.put("severity", severity.name());
            m.put("transformations", transformations.stream().map(ExceptionTransformation::toView).toList());
            m.put("trustTier", trustTier.name());
            return Collections.unmodifiableMap(m);
        }
    }

    /** Deterministic outcome of evaluating a declarative {@code fail-close-tier-policy}. */
    public record FailClosePolicyEvaluationResult(
            ContentDigest policySource,
            ContentDigest policyIdentity,
            ContentDigest graphDigest,
            AssessmentStatus status,
            Classification classification,
            TrustTier trustTier,
            List<FailClosePolicyFinding> findings,
            List<CapabilityGapRecord> gaps
    ) {
        public FailClosePolicyEvaluationResult {
            Objects.requireNonNull(policySource, "policySource");
            Objects.requireNonNull(policyIdentity, "policyIdentity");
            Objects.requireNonNull(graphDigest, "graphDigest");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(classification, "classification");
            trustTier = trustTier == null ? TrustTier.SOURCE_SYNTAX : trustTier;
            findings = Objects.requireNonNull(findings, "findings").stream()
                    .sorted(Comparator.comparing(FailClosePolicyFinding::ruleId)
                            .thenComparing(FailClosePolicyFinding::inboundId)
                            .thenComparing(FailClosePolicyFinding::outboundId)
                            .thenComparing(FailClosePolicyFinding::pathDigest))
                    .toList();
            gaps = Objects.requireNonNull(gaps, "gaps").stream().distinct().sorted().toList();
        }

        public ContentDigest identity() {
            return ContentDigest.sha256Utf8(CanonicalJson.write(List.of(FAIL_CLOSE_POLICY_VERSION, toView())));
        }

        public Map<String, Object> toView() {
            var m = new TreeMap<String, Object>();
            m.put("classification", classification.name());
            m.put("findingCount", findings.size());
            m.put("findings", findings.stream().map(FailClosePolicyFinding::toView).toList());
            m.put("gapCount", gaps.size());
            m.put("gaps", gaps.stream().map(FailCloseResilienceAnalyzer::gapToView).toList());
            m.put("graphDigest", graphDigest.value());
            m.put("policyIdentity", policyIdentity.value());
            m.put("policySource", policySource.value());
            m.put("status", status.name());
            m.put("trustTier", trustTier.name());
            return Collections.unmodifiableMap(m);
        }
    }

    /**
     * Parses a declarative {@code fail-close-tier-policy} from {@code architecture-policy:v1} YAML
     * using hardened SnakeYAML {@link SafeConstructor} limits ({@code I1}: zero anchors/aliases/polymorphism).
     */
    public FailCloseTierPolicy parseFailCloseTierPolicy(byte[] yamlBytes) {
        Objects.requireNonNull(yamlBytes, "yamlBytes");
        if (yamlBytes.length == 0 || yamlBytes.length > 262_144) {
            throw new IllegalArgumentException("fail-close-tier-policy size must be 1..262144 bytes");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(yamlBytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Invalid UTF-8 in fail-close-tier-policy", e);
        }
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setAllowRecursiveKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(24);
        options.setCodePointLimit(262_144);
        Node root;
        try {
            root = new Yaml(new SafeConstructor(options)).compose(new StringReader(text));
        } catch (YAMLException e) {
            throw new IllegalArgumentException("Invalid YAML in fail-close-tier-policy", e);
        }
        if (!(root instanceof MappingNode rootMap) || !Tag.MAP.equals(root.getTag())) {
            throw new IllegalArgumentException("Policy root must be a YAML mapping");
        }
        validateNoAnchors(root, 0);
        var top = toMapping(rootMap, Set.of("schema", "id", "version", FAIL_CLOSE_TIER_POLICY_KEY, "tiers", "serviceTiers", "rules"));
        String schema = requireScalar(top, "schema");
        if (!ArchitecturePolicy.SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("Unsupported schema: " + schema + " (expected " + ArchitecturePolicy.SCHEMA + ")");
        }
        String id = requireScalar(top, "id");
        String version = requireScalar(top, "version");

        Map<String, Node> spec = top;
        if (top.containsKey(FAIL_CLOSE_TIER_POLICY_KEY)) {
            Node sub = top.get(FAIL_CLOSE_TIER_POLICY_KEY);
            if (!(sub instanceof MappingNode subMap)) {
                throw new IllegalArgumentException("fail-close-tier-policy must be a YAML mapping");
            }
            spec = toMapping(subMap, Set.of("tiers", "serviceTiers", "rules"));
        }

        // Parse declared tiers
        var tierRanks = new TreeMap<String, Integer>();
        Node tiersNode = spec.get("tiers");
        if (tiersNode != null) {
            if (!(tiersNode instanceof SequenceNode seq)) {
                throw new IllegalArgumentException("tiers must be a YAML sequence");
            }
            for (Node item : seq.getValue()) {
                if (!(item instanceof MappingNode tm)) {
                    throw new IllegalArgumentException("Each tier entry must be a mapping");
                }
                var tFields = toMapping(tm, Set.of("name", "rank"));
                String tName = requireScalar(tFields, "name");
                int rank = Integer.parseInt(requireScalar(tFields, "rank"));
                var def = new TierDefinition(tName, rank);
                if (tierRanks.putIfAbsent(def.name(), def.rank()) != null) {
                    throw new IllegalArgumentException("Duplicate tier name: " + def.name());
                }
            }
        }
        if (tierRanks.isEmpty()) {
            throw new IllegalArgumentException("fail-close-tier-policy must declare tiers explicitly");
        }

        // Parse optional explicit serviceTiers map (still strictly declared — never invented!)
        var declaredTiers = new TreeMap<String, String>();
        Node svcTiersNode = spec.get("serviceTiers");
        if (svcTiersNode != null) {
            if (!(svcTiersNode instanceof MappingNode sm)) {
                throw new IllegalArgumentException("serviceTiers must be a YAML mapping");
            }
            for (var tuple : sm.getValue()) {
                String svc = scalarValue(tuple.getKeyNode());
                String tier = scalarValue(tuple.getValueNode());
                if (!tierRanks.containsKey(tier)) {
                    throw new IllegalArgumentException("Unknown tier '" + tier + "' referenced in serviceTiers");
                }
                declaredTiers.put(svc, tier);
            }
        }

        // Parse rules
        Node rulesNode = spec.get("rules");
        if (!(rulesNode instanceof SequenceNode rulesSeq) || rulesSeq.getValue().isEmpty()) {
            throw new IllegalArgumentException("fail-close-tier-policy requires non-empty rules");
        }
        var rules = new ArrayList<FailCloseTierRule>();
        var seenIds = new HashSet<String>();
        for (Node rn : rulesSeq.getValue()) {
            if (!(rn instanceof MappingNode rm)) {
                throw new IllegalArgumentException("Each fail-close rule must be a mapping");
            }
            var rf = toMapping(rm, Set.of(
                    "id", "version", "kind", "severity",
                    "fromTiers", "toTiers", "forbidLowerTierFailClose", "forbidExternalFailClose"));
            if (rf.containsKey("kind") && !FAIL_CLOSE_TIER_RULE_KIND.equals(scalarValue(rf.get("kind")))) {
                continue; // Skip non-fail-close rules in a mixed architecture-policy:v1 file
            }
            String ruleId = requireScalar(rf, "id");
            if (!seenIds.add(ruleId)) {
                throw new IllegalArgumentException("Duplicate rule id: " + ruleId);
            }
            String ruleVer = rf.containsKey("version") ? scalarValue(rf.get("version")) : "1";
            ArchitecturePolicy.Severity sev = ArchitecturePolicy.Severity.valueOf(requireScalar(rf, "severity"));
            Set<String> fromTiers = requireStringSet(rf, "fromTiers");
            Set<String> toTiers = rf.containsKey("toTiers") ? stringSet(rf.get("toTiers")) : Set.of();
            for (String ft : fromTiers) {
                if (!tierRanks.containsKey(ft)) {
                    throw new IllegalArgumentException("Unknown fromTier: " + ft);
                }
            }
            for (String tt : toTiers) {
                if (!tierRanks.containsKey(tt)) {
                    throw new IllegalArgumentException("Unknown toTier: " + tt);
                }
            }
            boolean forbidLower = !rf.containsKey("forbidLowerTierFailClose")
                    || Boolean.parseBoolean(scalarValue(rf.get("forbidLowerTierFailClose")));
            boolean forbidExternal = !rf.containsKey("forbidExternalFailClose")
                    || Boolean.parseBoolean(scalarValue(rf.get("forbidExternalFailClose")));
            rules.add(new FailCloseTierRule(ruleId, ruleVer, sev, fromTiers, toTiers, forbidLower, forbidExternal));
        }
        return new FailCloseTierPolicy(id, version, tierRanks, declaredTiers, rules);
    }

    /**
     * Parses and evaluates a declarative {@code fail-close-tier-policy} against a {@link ResilienceReport}
     * using ONLY explicitly declared repository service-tier metadata ({@code I4}: never invents tiers).
     */
    public FailClosePolicyEvaluationResult evaluateFailCloseTierPolicy(
            byte[] yamlBytes,
            ResilienceReport resilienceReport,
            Map<String, String> declaredServiceTiers,
            EvidenceContext evidenceContext) {
        Objects.requireNonNull(yamlBytes, "yamlBytes");
        ContentDigest policySource = ContentDigest.sha256(yamlBytes);
        FailCloseTierPolicy policy = parseFailCloseTierPolicy(yamlBytes);
        return evaluateFailCloseTierPolicy(policy, policySource, resilienceReport, declaredServiceTiers, evidenceContext);
    }

    /**
     * Evaluates a parsed {@link FailCloseTierPolicy} against per-path verdicts in {@code resilienceReport}.
     *
     * <p>Honesty guarantee ({@code I3}/{@code I4}):
     * <ul>
     *   <li>Never invents service tiers when {@code declaredServiceTiers} lacks a service entry: emits
     *       {@link FailCloseResilienceAnalyzer#GAP_REASON_TIER_METADATA_MISSING} and classifies as
     *       {@link Classification#UNKNOWN}.</li>
     *   <li>Evaluates every individual {@link PathVerdict} without flattening {@code MIXED_PATHS} pairs.</li>
     *   <li>Attaches full {@code file:line} call-path flamegraphs to every emitted finding.</li>
     * </ul>
     */
    public FailClosePolicyEvaluationResult evaluateFailCloseTierPolicy(
            FailCloseTierPolicy policy,
            ContentDigest policySource,
            ResilienceReport resilienceReport,
            Map<String, String> declaredServiceTiers,
            EvidenceContext evidenceContext) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(policySource, "policySource");
        Objects.requireNonNull(resilienceReport, "resilienceReport");
        Objects.requireNonNull(evidenceContext, "evidenceContext");

        // Merge explicit policy serviceTiers with explicit repo metadata (repo metadata takes precedence if provided)
        var effectiveTiers = new TreeMap<String, String>(policy.declaredServiceTiers());
        if (declaredServiceTiers != null) {
            for (var e : declaredServiceTiers.entrySet()) {
                if (e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null && !e.getValue().isBlank()) {
                    effectiveTiers.put(e.getKey().strip(), e.getValue().strip());
                }
            }
        }

        var findings = new ArrayList<FailClosePolicyFinding>();
        var gaps = new TreeSet<CapabilityGapRecord>(resilienceReport.gaps());
        boolean anyMust = false;
        boolean anyUnknown = false;

        for (FailCloseTierRule rule : policy.rules()) {
            for (PathVerdict pv : resilienceReport.pathVerdicts().values()) {
                // Only FAIL_CLOSE and UNKNOWN paths can trigger a fail-close tier violation (FAIL_OPEN is clean)
                if (pv.verdict() == ResilienceVerdict.FAIL_OPEN) {
                    continue;
                }

                Optional<String> inTierOpt = lookupDeclaredTier(effectiveTiers, pv.inboundService(), pv.inboundId());
                if (inTierOpt.isPresent() && !rule.fromTiers().contains(inTierOpt.get())) {
                    continue;
                }
                Optional<String> outTierOpt = lookupDeclaredTier(effectiveTiers, pv.outboundService(), pv.outboundId());

                // I4: Never invent tiers when repository metadata does not declare them!
                if (inTierOpt.isEmpty() || outTierOpt.isEmpty()) {
                    String missingSvc = inTierOpt.isEmpty()
                            ? firstNonBlank(pv.inboundService(), pv.inboundId())
                            : firstNonBlank(pv.outboundService(), pv.outboundId());
                    gaps.add(FailCloseResilienceAnalyzer.createGap(
                            evidenceContext,
                            resilienceReport.graphDigest(),
                            FailCloseResilienceAnalyzer.GAP_REASON_TIER_METADATA_MISSING,
                            missingSvc,
                            "Tier metadata not declared for service '" + missingSvc + "' (I4: tier never invented)",
                            AffectedOutput.Kind.POLICY,
                            "policy.fail-close-tier"));
                    anyUnknown = true;
                    findings.add(new FailClosePolicyFinding(
                            rule.id(),
                            rule.severity(),
                            Classification.UNKNOWN,
                            TrustTier.SOURCE_SYNTAX,
                            pv.inboundId(),
                            pv.outboundId(),
                            pv.inboundService(),
                            pv.outboundService(),
                            inTierOpt,
                            outTierOpt,
                            pv.key().pathDigest(),
                            pv.verdict(),
                            pv.flamegraph(),
                            pv.flamegraphChain(),
                            pv.propagatedExceptions(),
                            pv.transformations(),
                            Optional.of(FailCloseResilienceAnalyzer.GAP_REASON_TIER_METADATA_MISSING)));
                    continue;
                }

                String inTier = inTierOpt.get();
                String outTier = outTierOpt.get();

                Integer inRank = policy.tierRanks().get(inTier);
                Integer outRank = policy.tierRanks().get(outTier);
                if (inRank == null || outRank == null) {
                    gaps.add(FailCloseResilienceAnalyzer.createGap(
                            evidenceContext,
                            resilienceReport.graphDigest(),
                            FailCloseResilienceAnalyzer.GAP_REASON_TIER_METADATA_MISSING,
                            inTier + "->" + outTier,
                            "Declared service tier is absent from policy tier ranking table",
                            AffectedOutput.Kind.POLICY,
                            "policy.fail-close-tier"));
                    anyUnknown = true;
                    continue;
                }

                boolean isLowerTier = outRank > inRank;
                boolean isExternal = "EXTERNAL".equalsIgnoreCase(outTier);
                boolean targetMatches = (!rule.toTiers().isEmpty() && rule.toTiers().contains(outTier))
                        || (rule.forbidLowerTierFailClose() && isLowerTier)
                        || (rule.forbidExternalFailClose() && isExternal);
                if (!targetMatches) {
                    continue;
                }

                if (pv.verdict() == ResilienceVerdict.UNKNOWN) {
                    anyUnknown = true;
                    findings.add(new FailClosePolicyFinding(
                            rule.id(),
                            rule.severity(),
                            Classification.UNKNOWN,
                            TrustTier.SOURCE_SYNTAX,
                            pv.inboundId(),
                            pv.outboundId(),
                            pv.inboundService(),
                            pv.outboundService(),
                            Optional.of(inTier),
                            Optional.of(outTier),
                            pv.key().pathDigest(),
                            pv.verdict(),
                            pv.flamegraph(),
                            pv.flamegraphChain(),
                            pv.propagatedExceptions(),
                            pv.transformations(),
                            pv.gapReason().or(() -> Optional.of(FailCloseResilienceAnalyzer.GAP_REASON_HANDLER_OPAQUE))));
                } else {
                    anyMust = true;
                    findings.add(new FailClosePolicyFinding(
                            rule.id(),
                            rule.severity(),
                            Classification.MUST,
                            TrustTier.SOURCE_SYNTAX,
                            pv.inboundId(),
                            pv.outboundId(),
                            pv.inboundService(),
                            pv.outboundService(),
                            Optional.of(inTier),
                            Optional.of(outTier),
                            pv.key().pathDigest(),
                            pv.verdict(),
                            pv.flamegraph(),
                            pv.flamegraphChain(),
                            pv.propagatedExceptions(),
                            pv.transformations(),
                            pv.gapReason()));
                }
            }
        }

        Classification overall = anyMust
                ? Classification.MUST
                : (anyUnknown || !gaps.isEmpty() ? Classification.UNKNOWN : Classification.NEVER);
        AssessmentStatus status = gaps.isEmpty() ? AssessmentStatus.COMPLETE : AssessmentStatus.PARTIAL;
        return new FailClosePolicyEvaluationResult(
                policySource,
                policy.identity(),
                resilienceReport.graphDigest(),
                status,
                overall,
                TrustTier.SOURCE_SYNTAX,
                findings,
                List.copyOf(gaps));
    }

    private static Optional<String> lookupDeclaredTier(Map<String, String> tiers, String serviceName, String nodeId) {
        if (serviceName != null && !serviceName.isBlank()) {
            String direct = tiers.get(serviceName.strip());
            if (direct != null && !direct.isBlank()) return Optional.of(direct);
        }
        if (nodeId != null && !nodeId.isBlank()) {
            String direct = tiers.get(nodeId.strip());
            if (direct != null && !direct.isBlank()) return Optional.of(direct);
        }
        return Optional.empty();
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : (b == null ? "unknown" : b);
    }

    private static void validateNoAnchors(Node node, int depth) {
        if (depth > 24) throw new IllegalArgumentException("YAML nesting limit exceeded");
        if (node.getAnchor() != null) throw new IllegalArgumentException("YAML anchors are unsupported");
        if (node instanceof MappingNode m) {
            var keys = new HashSet<String>();
            for (var t : m.getValue()) {
                String k = scalarValue(t.getKeyNode());
                if (!keys.add(k)) throw new IllegalArgumentException("Duplicate YAML key: " + k);
                validateNoAnchors(t.getKeyNode(), depth + 1);
                validateNoAnchors(t.getValueNode(), depth + 1);
            }
        } else if (node instanceof SequenceNode s) {
            for (Node child : s.getValue()) {
                validateNoAnchors(child, depth + 1);
            }
        } else if (node instanceof ScalarNode sc) {
            Tag tag = sc.getTag();
            if (!Tag.STR.equals(tag) && !Tag.INT.equals(tag) && !Tag.BOOL.equals(tag)) {
                throw new IllegalArgumentException("Unsupported YAML scalar tag: " + tag);
            }
        } else {
            throw new IllegalArgumentException("Unsupported YAML node type");
        }
    }

    private static Map<String, Node> toMapping(MappingNode map, Set<String> allowedKeys) {
        var result = new LinkedHashMap<String, Node>();
        for (var t : map.getValue()) {
            String key = scalarValue(t.getKeyNode());
            if (!allowedKeys.contains(key)) {
                throw new IllegalArgumentException("Unknown field in fail-close-tier-policy: " + key);
            }
            result.put(key, t.getValueNode());
        }
        return result;
    }

    private static String requireScalar(Map<String, Node> map, String key) {
        Node n = map.get(key);
        if (n == null) throw new IllegalArgumentException("Missing required field: " + key);
        return scalarValue(n);
    }

    private static String scalarValue(Node node) {
        if (!(node instanceof ScalarNode sc)) {
            throw new IllegalArgumentException("Expected scalar value");
        }
        String v = sc.getValue();
        if (v.length() > 512 || v.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Scalar value violates bounds");
        }
        return v;
    }

    private static Set<String> requireStringSet(Map<String, Node> map, String key) {
        Node n = map.get(key);
        if (n == null) throw new IllegalArgumentException("Missing required sequence: " + key);
        return stringSet(n);
    }

    private static Set<String> stringSet(Node node) {
        if (!(node instanceof SequenceNode seq)) {
            throw new IllegalArgumentException("Expected YAML sequence");
        }
        var out = new TreeSet<String>();
        for (Node item : seq.getValue()) {
            out.add(scalarValue(item));
        }
        return out;
    }

    // ─── WP2.2: Bounded Declarative RSL Metadata Consistency Evaluation ───

    /**
     * Evaluates a compiled {@link PolicyCompiler.MetadataCompilationResult} (built-in {@code metadata-consistency-pack}
     * rules and/or bounded declarative RSL rules) against {@code service} using exact classpath lookup.
     */
    public MetadataConsistencyRulePack.EvaluationReport evaluateMetadataPolicy(
            PolicyCompiler.MetadataCompilationResult compilation,
            ArchitectureQueryService service,
            String context,
            MetadataConsistencyRulePack.ClasspathTypeLookup classpathLookup,
            MetadataConsistencyRulePack.EvaluationOptions options
    ) {
        Objects.requireNonNull(compilation, "compilation");
        Objects.requireNonNull(service, "service");
        if (compilation.policy().isEmpty()) {
            return new MetadataConsistencyRulePack.EvaluationReport(
                    compilation.identity(),
                    service.graphIdentity(),
                    context == null ? "" : context,
                    AssessmentStatus.WITHHELD,
                    Classification.UNKNOWN,
                    List.of(),
                    List.of(),
                    compilation.gaps());
        }
        return new MetadataConsistencyRulePack().evaluate(
                compilation.policy().orElseThrow(), service, context, classpathLookup, options);
    }

    /** Compiles and evaluates a declarative {@code architecture-policy:v1} metadata consistency YAML document. */
    public MetadataConsistencyRulePack.EvaluationReport evaluateMetadataPolicy(
            byte[] yamlBytes,
            ArchitectureQueryService service,
            String context,
            MetadataConsistencyRulePack.ClasspathTypeLookup classpathLookup,
            MetadataConsistencyRulePack.EvaluationOptions options
    ) {
        Objects.requireNonNull(yamlBytes, "yamlBytes");
        Objects.requireNonNull(service, "service");
        var evCtx = EvidenceContext.forSnapshot(service.graph().snapshot());
        var compiled = new PolicyCompiler().compileMetadataPolicy(yamlBytes, evCtx, PolicyCompiler.Limits.defaults());
        return evaluateMetadataPolicy(compiled, service, context, classpathLookup, options);
    }

    /** Activates and evaluates the built-in 6-rule {@code metadata-consistency-pack} directly. */
    public MetadataConsistencyRulePack.EvaluationReport evaluateMetadataConsistencyPack(
            ArchitectureQueryService service,
            String context,
            MetadataConsistencyRulePack.ClasspathTypeLookup classpathLookup,
            MetadataConsistencyRulePack.EvaluationOptions options
    ) {
        Objects.requireNonNull(service, "service");
        var evCtx = EvidenceContext.forSnapshot(service.graph().snapshot());
        var compiled = new PolicyCompiler().compileBuiltInMetadataPack(
                MetadataConsistencyRulePack.PACK_ID, evCtx);
        return evaluateMetadataPolicy(compiled, service, context, classpathLookup, options);
    }
}
