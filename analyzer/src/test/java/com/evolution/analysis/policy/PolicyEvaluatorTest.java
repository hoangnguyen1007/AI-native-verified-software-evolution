package com.evolution.analysis.policy;

import com.evolution.analysis.graph.*;
import com.evolution.analysis.query.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.source.SourceSpan;
import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.query.ArchitectureQuery.*;

class PolicyEvaluatorTest {
    static final String CYCLE=PolicyCompilerTest.VALID.substring(0,PolicyCompilerTest.VALID.indexOf("    from:"))
            .replace("FORBIDDEN_DEPENDENCY","ACYCLIC")+"    within: {packages: [com.example.**]}\n";
    static PolicyGraphMapperTest.Fixture fixture(){return new PolicyGraphMapperTest.Fixture().type("com.example.web","Controller").type("com.example.persistence","Store");}
    static void prove(PolicyGraphMapperTest.Fixture f){
        for(int i=0;i<f.edges.size();i++){
            var edge=f.edges.get(i);if(!edge.kind().equals("graph.depends-on"))continue;
            var document=SourceDocumentIdentity.from(f.repository,"src/Dependency"+i+".java");
            var digest=ContentDigest.sha256Utf8("class Dependency"+i+" {}");
            var inventory=GraphEvidence.capture("source.document",Map.of("identity",document,"contentDigest",digest));
            f.evidence.add(inventory);f.nodes.add(new KnowledgeGraph.Node(document.value(),f.context,"SOURCE_DOCUMENT",List.of(inventory.identity())));
            var occurrence=GraphEvidence.capture("java.occurrence",Map.of("status","RESOLVED","derivation","DIRECT", "span",new SourceSpan(document,1,1,1,10)));
            f.evidence.add(occurrence);f.edges.set(i,new KnowledgeGraph.Edge(edge.identity(),edge.context(),edge.kind(),edge.source(),edge.target(),List.of(occurrence.identity()),edge.region()));
        }
    }
    static PolicyEvaluation.Result evaluate(PolicyGraphMapperTest.Fixture f,String yaml){
        var compiled=PolicyCompilerTest.compile(yaml);var service=new ArchitectureQueryService(f.graph());
        return new PolicyEvaluator().evaluate(compiled,new PolicyGraphMapper().bind(compiled,service,f.context,PolicyGraphMapper.Limits.defaults()),service,PolicyEvaluation.Limits.defaults());
    }
    @Test void cleanRuleIsRetainedWithoutWarningAndMissingEvidenceCannotCertifyAViolation(){
        var clean=new PolicyGraphMapperTest.Fixture().type("com.example.web","Controller").type("com.example.persistence","Store");
        var result=evaluate(clean,PolicyCompilerTest.VALID);
        assertEquals(1,result.rules().size());assertEquals(Classification.NEVER,result.rules().getFirst().classification());assertTrue(result.findings().isEmpty());
        var missing=evaluate(clean.edge("Controller","Store"),PolicyCompilerTest.VALID);
        assertEquals(Classification.UNKNOWN,missing.rules().getFirst().classification());assertFalse(missing.gaps().isEmpty());
    }
    @Test void definiteForbiddenFindingPreservesSourceRuleSeverityAndDeterministicIdentity(){
        var f=fixture().edge("Controller","Store");prove(f);var result=evaluate(f,PolicyCompilerTest.VALID);
        assertEquals(AssessmentStatus.COMPLETE,result.status(),result.gaps().toString());
        var rule=result.rules().getFirst();assertEquals(Classification.MUST,rule.classification());assertEquals(1,result.findings().size());
        var finding=result.findings().getFirst();assertEquals(ArchitecturePolicy.Severity.ERROR,finding.rule().severity());
        assertEquals(List.of(f.types.get("Controller"),f.types.get("Store")),finding.bundle().path());
        assertEquals(1,finding.bundle().sources().size());assertEquals(1,finding.bundle().supports().size());
        assertEquals(List.of("RESOLVED"),finding.bundle().supports().getFirst().semanticStatuses());
        assertEquals(List.of("DIRECT"),finding.bundle().supports().getFirst().derivations());
        assertTrue(finding.bundle().region().witness().orElseThrow().formulaReplayed());assertFalse(finding.bundle().region().witness().orElseThrow().semanticReplayed());
        assertTrue(finding.bundle().region().counterWitness().isEmpty());assertEquals(ContentDigest.sha256Utf8(PolicyCompilerTest.VALID),finding.bundle().policySource());
        assertEquals(result,evaluate(f,PolicyCompilerTest.VALID));assertEquals(result.identity(),evaluate(f,PolicyCompilerTest.VALID).identity());
        assertFalse(CanonicalJson.write(result).contains("class Dependency"));
        var g=new GraphSnapshotCodec().decode(new GraphSnapshotCodec().encode(f.graph()),GraphJson.Limits.defaults());
        var compiled=PolicyCompilerTest.compile(PolicyCompilerTest.VALID);var service=new ArchitectureQueryService(g);
        assertEquals(result,new PolicyEvaluator().evaluate(compiled,new PolicyGraphMapper().bind(compiled,service,f.context,PolicyGraphMapper.Limits.defaults()),service,PolicyEvaluation.Limits.defaults()));
    }
    @Test void boundaryAndLayerNegativesAndReversedEdgesStayClean(){
        var f=fixture().edge("Controller","Store");prove(f);
        String boundary=PolicyCompilerTest.VALID.replace("FORBIDDEN_DEPENDENCY","BOUNDARY").replace("to:","allowed:");
        assertEquals(Classification.NEVER,evaluate(f,boundary).rules().getFirst().classification());
        assertEquals(Classification.MUST,evaluate(f,boundary.replace("com.example.persistence.**","com.example.web.**")).rules().getFirst().classification());
        var reversed=fixture().edge("Store","Controller");prove(reversed);
        assertEquals(Classification.NEVER,evaluate(reversed,PolicyCompilerTest.VALID).rules().getFirst().classification());
        String layers=PolicyCompilerTest.VALID.substring(0,PolicyCompilerTest.VALID.indexOf("    from:")).replace("FORBIDDEN_DEPENDENCY","LAYER_BOUNDARY")+"""
                    layers:
                      - {name: web, select: {packages: [com.example.web.**]}}
                      - {name: data, select: {packages: [com.example.persistence.**]}}
                    allowed: []
                """;
        assertEquals(Classification.MUST,evaluate(f,layers).rules().getFirst().classification());
        assertEquals(Classification.NEVER,evaluate(f,layers.replace("allowed: []","allowed: [{from: web, to: [data]}]")).rules().getFirst().classification());
        assertEquals(Classification.UNKNOWN,evaluate(f,layers.replace("com.example.persistence.**","com.example.**")).rules().getFirst().classification());
    }
    @Test void cyclesAreCanonicalInducedAndAnEdgeAloneIsClean(){
        var f=fixture().type("elsewhere","Other").edge("Controller","Store").edge("Other","Other");prove(f);
        assertEquals(Classification.NEVER,evaluate(f,CYCLE).rules().getFirst().classification());
        f.edge("Store","Controller"); // Add source proof only for the newly added dependency.
        var raw=f.edges.getLast();var proof=f.edges.stream().filter(e->e.kind().equals("graph.depends-on")).findFirst().orElseThrow().evidence();
        f.edges.set(f.edges.size()-1,new KnowledgeGraph.Edge(raw.identity(),raw.context(),raw.kind(),raw.source(),raw.target(),proof,raw.region()));
        var result=evaluate(f,CYCLE);assertEquals(Classification.MUST,result.rules().getFirst().classification());assertEquals(1,result.findings().size());
        assertEquals(2,result.findings().getFirst().bundle().relationships().size());
        assertEquals(2,result.findings().getFirst().bundle().supports().size());
    }
    @Test void unknownAndEmptySelectorsNeverCertifyHealthy(){
        var f=fixture().edge("Controller","Store");prove(f);
        for(String yaml:List.of(PolicyCompilerTest.VALID.replace("com.example.web.**","missing.package"),PolicyCompilerTest.VALID.replace("packages: [com.example.web.**]","sourceSets: [MAIN]"))){
            var result=evaluate(f,yaml);assertEquals(Classification.UNKNOWN,result.rules().getFirst().classification());assertEquals(AssessmentStatus.PARTIAL,result.status());assertFalse(result.gaps().isEmpty());
        }
    }
    @Test void globalFindingAndEvidenceLimitsRetainEveryRuleAndKnownEvidence(){
        var f=fixture().edge("Controller","Store");prove(f);
        String two=PolicyCompilerTest.VALID+PolicyCompilerTest.VALID.substring(PolicyCompilerTest.VALID.indexOf("  - id:")).replace("web.persistence","web.second");
        var compiled=PolicyCompilerTest.compile(two);var service=new ArchitectureQueryService(f.graph());var plan=new PolicyGraphMapper().bind(compiled,service,f.context,PolicyGraphMapper.Limits.defaults());
        var result=new PolicyEvaluator().evaluate(compiled,plan,service,new PolicyEvaluation.Limits(1,1024,500_000));
        assertEquals(2,result.rules().size());assertEquals(1,result.findings().size());assertEquals(Classification.MUST,result.rules().getFirst().classification());
        assertEquals(Classification.UNKNOWN,result.rules().getLast().classification());assertEquals(AssessmentStatus.WITHHELD,result.rules().getLast().status());
        assertTrue(result.gaps().stream().anyMatch(g->g.reasonCode().equals("EVALUATION_LIMIT")));
        for(var limits:List.of(new PolicyEvaluation.Limits(50,1,500_000),new PolicyEvaluation.Limits(50,1024,1))){
            var limited=new PolicyEvaluator().evaluate(compiled,plan,service,limits);assertEquals(Classification.UNKNOWN,limited.rules().getFirst().classification());assertFalse(limited.gaps().isEmpty());
        }
    }
    @Test void invalidCompilationCancelledMappingAndForeignInputsAreExplicit(){
        var f=fixture().edge("Controller","Store");prove(f);var invalid=evaluate(f,PolicyCompilerTest.VALID.replace("ERROR","INVALID"));
        assertEquals(AssessmentStatus.WITHHELD,invalid.status());assertTrue(invalid.rules().isEmpty());assertFalse(invalid.gaps().isEmpty());
        var compiled=PolicyCompilerTest.compile(PolicyCompilerTest.VALID);var service=new ArchitectureQueryService(f.graph(),()->StopReason.CANCELLED,Optional.empty());
        var plan=new PolicyGraphMapper().bind(compiled,service,f.context,PolicyGraphMapper.Limits.defaults());
        var cancelled=new PolicyEvaluator().evaluate(compiled,plan,service,PolicyEvaluation.Limits.defaults());
        assertEquals(1,cancelled.rules().size());assertEquals(AssessmentStatus.WITHHELD,cancelled.status());assertEquals(Classification.UNKNOWN,cancelled.rules().getFirst().classification());
        assertThrows(IllegalArgumentException.class,()->new PolicyEvaluator().evaluate(PolicyCompilerTest.compile(PolicyCompilerTest.VALID.replace("ERROR","WARNING")),plan,service,PolicyEvaluation.Limits.defaults()));
        assertThrows(IllegalArgumentException.class,()->new PolicyEvaluator().evaluate(compiled,plan,new ArchitectureQueryService(fixture().graph()),PolicyEvaluation.Limits.defaults()));
    }
    @Test void conflictingSourceDigestsAndMalformedSpansCannotBecomeCertain(){
        for(boolean malformed:List.of(false,true)){
            var f=fixture().edge("Controller","Store");prove(f);var raw=f.edges.getLast();
            var document=f.nodes.stream().filter(n->n.kind().equals("SOURCE_DOCUMENT")).findFirst().orElseThrow().identity();
            var span=new TreeMap<String,Object>(Map.of("document",document,"startLine",1,"startColumn",1,"endLine",1,"endColumn",malformed?0:10));
            var bad=GraphEvidence.capture("java.occurrence",Map.of("status","RESOLVED","evidence",Map.of("kind","SOURCE","document",document,"rawDigest",ContentDigest.sha256Utf8("wrong"),"span",span)));
            f.evidence.add(bad);f.edges.set(f.edges.size()-1,new KnowledgeGraph.Edge(raw.identity(),raw.context(),raw.kind(),raw.source(),raw.target(),List.of(bad.identity()),raw.region()));
            var result=evaluate(f,PolicyCompilerTest.VALID);assertEquals(Classification.UNKNOWN,result.rules().getFirst().classification());
            assertTrue(result.gaps().stream().anyMatch(g->g.reasonCode().equals(malformed?"SOURCE_SPAN_INVALID":"SOURCE_DIGEST_MISMATCH")));
            assertTrue(result.findings().getFirst().bundle().region().witness().isEmpty());
        }
    }
    @Test void rawByteFormattingAndRuleVersionsChangeEvidenceWithoutChangingTheDetectedEdge(){
        var f=fixture().edge("Controller","Store");prove(f);var baseline=evaluate(f,PolicyCompilerTest.VALID);
        var formatted=evaluate(f,"# format only\n"+PolicyCompilerTest.VALID);var changed=evaluate(f,PolicyCompilerTest.VALID.replace("version: \"1\"","version: \"2\""));
        assertEquals(baseline.findings().getFirst().bundle().path(),formatted.findings().getFirst().bundle().path());
        assertEquals(baseline.findings().getFirst().bundle().policy(),formatted.findings().getFirst().bundle().policy());
        assertNotEquals(baseline.findings().getFirst().identity(),formatted.findings().getFirst().identity());
        assertEquals(baseline.findings().getFirst().key(),formatted.findings().getFirst().key());
        assertNotEquals(baseline.findings().getFirst().bundle().policy(),changed.findings().getFirst().bundle().policy());
        assertEquals("m6.2-v1",PolicyEvaluation.VERSION.version());assertEquals("m6.2-v1",PolicyEvaluation.EVIDENCE_VERSION.version());
        assertEquals("m6.2-v1",ArchitectureCondition.RULE_VERSION.version());
    }
    @Test void cumulativeEvidenceCharacterBudgetAndWithheldMappingStayExplicit(){
        var f=fixture().edge("Controller","Store");prove(f);var compiled=PolicyCompilerTest.compile(PolicyCompilerTest.VALID);var service=new ArchitectureQueryService(f.graph());
        var plan=new PolicyGraphMapper().bind(compiled,service,f.context,PolicyGraphMapper.Limits.defaults());
        var limited=new PolicyEvaluator().evaluate(compiled,plan,service,new PolicyEvaluation.Limits(50,1024,500_000,1));
        assertEquals(Classification.UNKNOWN,limited.rules().getFirst().classification());assertFalse(limited.gaps().isEmpty());
        plan=new PolicyGraphMapper().bind(compiled,service,f.context,new PolicyGraphMapper.Limits(1,ArchitectureQuery.Limits.defaults()));
        var withheld=new PolicyEvaluator().evaluate(compiled,plan,service,PolicyEvaluation.Limits.defaults());
        assertEquals(AssessmentStatus.WITHHELD,withheld.status());assertEquals(Classification.UNKNOWN,withheld.rules().getFirst().classification());assertTrue(withheld.findings().isEmpty());
    }
    @Test void packageAndModuleResultsRetainOriginalTypeRelationshipEvidence(){
        var f=new PolicyGraphMapperTest.Fixture().type("web","com.example.web","Controller").type("storage","com.example.persistence","Store").edge("Controller","Store");prove(f);
        var packages=evaluate(f,PolicyCompilerTest.VALID.replace("scope: TYPE","scope: PACKAGE"));
        assertEquals(Classification.MUST,packages.rules().getFirst().classification());assertEquals(1,packages.findings().getFirst().bundle().sources().size());
        String from=ModuleIdentity.from(f.repository,"web").value(),to=ModuleIdentity.from(f.repository,"storage").value();
        var modules=evaluate(f,PolicyCompilerTest.VALID.replace("scope: TYPE","scope: MODULE")
                .replace("packages: [com.example.web.**]","modules: ['"+from+"']").replace("packages: [com.example.persistence.**]","modules: ['"+to+"']"));
        assertEquals(Classification.MUST,modules.rules().getFirst().classification());assertEquals(List.of(from,to),modules.findings().getFirst().bundle().path());
        assertEquals(f.types.get("Controller"),modules.findings().getFirst().bundle().supports().getFirst().source());
    }
    @Test void parallelSupportTruncationIsVisibleAndCannotCertifyExactEvidence(){
        var f=fixture();for(int i=0;i<51;i++)f.edge("Controller","Store");prove(f);
        var result=evaluate(f,PolicyCompilerTest.VALID);assertEquals(1,result.findings().size());
        var finding=result.findings().getFirst();assertEquals(Classification.UNKNOWN,finding.classification());
        assertEquals(51,finding.bundle().relationships().getFirst().supportCount());assertTrue(finding.bundle().relationships().getFirst().supportsTruncated());
        assertTrue(result.gaps().stream().anyMatch(g->g.reasonCode().equals("SUPPORTS_INCOMPLETE")));
    }
    @Test void findingQuotaRetainsKnownMustViolationButQualifiesTheOpenDenominator(){
        var f=new PolicyGraphMapperTest.Fixture().type("com.example.web","Controller");
        for(int i=0;i<51;i++)f.type("com.example.persistence","Store"+i).edge("Controller","Store"+i);prove(f);
        var result=evaluate(f,PolicyCompilerTest.VALID);assertEquals(50,result.findings().size());assertEquals(Classification.MUST,result.rules().getFirst().classification());
        assertEquals(AssessmentStatus.PARTIAL,result.status());assertFalse(result.rules().getFirst().predicate().complete());
        assertEquals(50,result.findings().stream().map(PolicyEvaluation.Finding::key).distinct().count());
        assertTrue(result.gaps().stream().anyMatch(g->g.reasonCode().equals("EVALUATION_LIMIT")));
    }
    @Test void cancellationAfterMappingPreservesUnknownResultsAndNoInventedWitness(){
        var f=fixture().edge("Controller","Store");prove(f);var compiled=PolicyCompilerTest.compile(PolicyCompilerTest.VALID);
        var plan=new PolicyGraphMapper().bind(compiled,new ArchitectureQueryService(f.graph()),f.context,PolicyGraphMapper.Limits.defaults());
        var result=new PolicyEvaluator().evaluate(compiled,plan,new ArchitectureQueryService(f.graph(),()->StopReason.CANCELLED,Optional.empty()),PolicyEvaluation.Limits.defaults());
        assertEquals(Classification.UNKNOWN,result.rules().getFirst().classification());assertEquals(1,result.findings().size());
        assertTrue(result.findings().getFirst().bundle().region().witness().isEmpty());assertTrue(result.gaps().stream().anyMatch(g->g.reasonCode().equals("CANCELLED")));
    }

    @Test void failCloseTierPolicyFlagsHigherToLowerFailCloseAndNeverInventsMissingTiers(){
        String yaml="""
                schema: architecture-policy:v1
                id: resilience.tier-guard
                version: "1"
                fail-close-tier-policy:
                  tiers:
                    - {name: T0, rank: 0}
                    - {name: T1, rank: 1}
                    - {name: T2, rank: 2}
                    - {name: T3, rank: 3}
                    - {name: EXTERNAL, rank: 4}
                  rules:
                    - id: tier.no-fail-close-lower
                      severity: ERROR
                      fromTiers: [T0, T1, T2]
                      forbidLowerTierFailClose: true
                      forbidExternalFailClose: true
                """;
        var repo=RepositoryIdentity.fromCanonicalCoordinate("https://example.test/tier-policy");
        var snap=SnapshotIdentity.from(repo,ContentDigest.sha256Utf8("tier-snap"));
        var evCtx=com.evolution.analysis.evidence.EvidenceContext.forSnapshot(snap);
        var digest=ContentDigest.sha256Utf8("tier-graph");

        var callGraph=new com.evolution.analysis.polyglot.FailCloseResilienceAnalyzer.CallGraph(
                Map.of(
                        "ep:gw",new com.evolution.analysis.polyglot.FailCloseResilienceAnalyzer.CallNode("ep:gw","GET /api/checkout","ENDPOINT","ingress-gw","src/Gateway.java",12,24,true),
                        "ob:recs",new com.evolution.analysis.polyglot.FailCloseResilienceAnalyzer.CallNode("ob:recs","RecsClient.list","SERVICE","recs-svc","src/RecsClient.java",8,10,false),
                        "ob:partner",new com.evolution.analysis.polyglot.FailCloseResilienceAnalyzer.CallNode("ob:partner","PartnerApi.score","SERVICE","partner-api","src/PartnerApi.java",5,9,false)),
                List.of(
                        new com.evolution.analysis.polyglot.FailCloseResilienceAnalyzer.CallEdge("ep:gw","ob:recs","CALLS_SERVICE"),
                        new com.evolution.analysis.polyglot.FailCloseResilienceAnalyzer.CallEdge("ep:gw","ob:partner","CALLS_SERVICE")));
        var summaries=Map.<String,List<com.evolution.analysis.polyglot.FailCloseResilienceAnalyzer.MethodErrorSummary>>of(
                "ep:gw",List.of(
                        new com.evolution.analysis.polyglot.FailCloseResilienceAnalyzer.MethodErrorSummary(
                                "java","ep:gw","ep:gw:15#RecsClient.list","src/Gateway.java",15,15,"RecsClient.list","FEIGN",
                                Set.of("FeignException"),Set.of(),List.of(),
                                com.evolution.analysis.polyglot.FailCloseResilienceAnalyzer.PropagationDisposition.SUPPRESSED_FAIL_OPEN,
                                true,false,false,false,Optional.empty(),Optional.of("TRY_CATCH_DEFAULT"),
                                com.evolution.analysis.contract.semantic.TrustTier.SOURCE_SYNTAX),
                        new com.evolution.analysis.polyglot.FailCloseResilienceAnalyzer.MethodErrorSummary(
                                "java","ep:gw","ep:gw:20#PartnerApi.score","src/Gateway.java",20,20,"PartnerApi.score","FEIGN",
                                Set.of("FeignException"),Set.of("FeignException"),List.of(),
                                com.evolution.analysis.polyglot.FailCloseResilienceAnalyzer.PropagationDisposition.PROPAGATES_ERROR,
                                true,false,false,false,Optional.empty(),Optional.empty(),
                                com.evolution.analysis.contract.semantic.TrustTier.SOURCE_SYNTAX)));
        var report=com.evolution.analysis.polyglot.FailCloseResilienceAnalyzer.analyzeCallGraph(
                digest,evCtx,callGraph,summaries,com.evolution.analysis.polyglot.FailCloseResilienceAnalyzer.AnalysisConfig.defaults());

        var evaluator=new PolicyEvaluator();
        // 1. With declared tiers: flags T0 -> EXTERNAL FAIL_CLOSE with flamegraph; T0 -> T3 FAIL_OPEN stays clean
        var declared=evaluator.evaluateFailCloseTierPolicy(
                yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8),report,
                Map.of("ingress-gw","T0","recs-svc","T3","partner-api","EXTERNAL"),evCtx);
        assertEquals(AssessmentStatus.COMPLETE,declared.status());
        assertEquals(Classification.MUST,declared.classification());
        assertEquals(1,declared.findings().size());
        var finding=declared.findings().getFirst();
        assertEquals("tier.no-fail-close-lower",finding.ruleId());
        assertEquals("partner-api",finding.outboundService());
        assertEquals(Optional.of("T0"),finding.inboundTier());
        assertEquals(Optional.of("EXTERNAL"),finding.outboundTier());
        assertTrue(finding.flamegraphChain().contains("src/Gateway.java:12"));

        // 2. Without declared repo tier metadata (I4): NEVER invents tiers; emits UNKNOWN + FAIL_CLOSE_TIER_METADATA_MISSING gap!
        var undeclared=evaluator.evaluateFailCloseTierPolicy(
                yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8),report,Map.of(),evCtx);
        assertEquals(AssessmentStatus.PARTIAL,undeclared.status());
        assertEquals(Classification.UNKNOWN,undeclared.classification());
        assertTrue(undeclared.gaps().stream()
                .anyMatch(g->g.reasonCode().equals(com.evolution.analysis.polyglot.FailCloseResilienceAnalyzer.GAP_REASON_TIER_METADATA_MISSING)));
        assertTrue(undeclared.findings().stream().allMatch(f->f.classification()==Classification.UNKNOWN));
    }

    @Test void evaluatesDeclarativeMetadataConsistencyPolicyAndWithholdsInvalidCompilation() {
        var f = fixture();
        var xmlEv = GraphEvidence.capture(MetadataConsistencyRulePack.FAMILY_XML_BEAN, Map.of(
                "beanId", "legacyBean",
                "className", "com.example.web.Controller",
                "scope", "prototype",
                "sourceFile", "src/main/resources/context.xml",
                "line", 7));
        f.evidence.add(xmlEv);
        f.nodes.add(new KnowledgeGraph.Node(ContentDigest.sha256Utf8("xml-bean-node").value(), f.context, "SPRING_BEAN", List.of(xmlEv.identity())));

        String yaml = """
                schema: architecture-policy:v1
                id: example.metadata-eval
                version: "1"
                packs:
                  - metadata-consistency-pack
                metadataRules:
                  - id: custom.forbid-prototype-xml-scope
                    version: "1"
                    severity: WARNING
                    forEach: { var: b, in: XML_BEANS }
                    assert:
                      field: b.scope
                      op: NOT_EQUALS
                      value: prototype
                      message: "XML bean should not use prototype scope"
                """;
        var evaluator = new PolicyEvaluator();
        var service = new ArchitectureQueryService(f.graph());
        var report = evaluator.evaluateMetadataPolicy(
                yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                service, f.context,
                MetadataConsistencyRulePack.ClasspathTypeLookup.emptyComplete(),
                MetadataConsistencyRulePack.EvaluationOptions.defaults());

        assertEquals(AssessmentStatus.COMPLETE, report.status());
        assertEquals(7, report.rules().size());
        assertEquals(1, report.findingsForRule("custom.forbid-prototype-xml-scope").size());
        assertEquals(Classification.MUST, report.findingsForRule("custom.forbid-prototype-xml-scope").getFirst().classification());

        // Built-in pack shortcut runs all 6 rules cleanly
        var packOnly = evaluator.evaluateMetadataConsistencyPack(
                service, f.context,
                MetadataConsistencyRulePack.ClasspathTypeLookup.emptyComplete(),
                MetadataConsistencyRulePack.EvaluationOptions.defaults());
        assertEquals(6, packOnly.rules().size());
        assertEquals(Classification.NEVER, packOnly.classification());

        // Invalid metadata YAML stays WITHHELD + UNKNOWN with compilation gaps
        var invalid = evaluator.evaluateMetadataPolicy(
                "schema: future:v99\nid: bad\nversion: \"1\"\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                service, f.context,
                MetadataConsistencyRulePack.ClasspathTypeLookup.emptyComplete(),
                MetadataConsistencyRulePack.EvaluationOptions.defaults());
        assertEquals(AssessmentStatus.WITHHELD, invalid.status());
        assertEquals(Classification.UNKNOWN, invalid.classification());
        assertFalse(invalid.gaps().isEmpty());
    }

    @Test void evaluatesSpringProxyTrapsNativelyForSelfInvocationAndCglibPrivateFinalTraps() {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/proxy-trap-policy");
        var snap = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("proxy-trap-snap"));
        var bean = new com.evolution.analysis.spring.universal.SpringProxyTrapDetector.BeanClassInfo(
                "com.acme.OrderService",
                Set.of("public"),
                Set.of("org.springframework.stereotype.Service"),
                true,
                List.of(
                        new com.evolution.analysis.spring.universal.SpringProxyTrapDetector.MethodInfo(
                                "com.acme.OrderService", "placeOrder", Set.of("public"), Set.of(), "OrderService.java", 10),
                        new com.evolution.analysis.spring.universal.SpringProxyTrapDetector.MethodInfo(
                                "com.acme.OrderService", "saveTx", Set.of("public"),
                                Set.of("org.springframework.transaction.annotation.Transactional"), "OrderService.java", 20),
                        new com.evolution.analysis.spring.universal.SpringProxyTrapDetector.MethodInfo(
                                "com.acme.OrderService", "hiddenTx", Set.of("private"),
                                Set.of("org.springframework.transaction.annotation.Transactional"), "OrderService.java", 30),
                        new com.evolution.analysis.spring.universal.SpringProxyTrapDetector.MethodInfo(
                                "com.acme.OrderService", "fetchOrders", Set.of("public", "final"),
                                Set.of("org.springframework.web.bind.annotation.GetMapping"), "OrderService.java", 40)),
                "OrderService.java",
                5);
        var calls = List.of(
                new com.evolution.analysis.spring.universal.SpringProxyTrapDetector.IntraClassCall(
                        "com.acme.OrderService", "placeOrder", "saveTx", true, "OrderService.java", 14),
                new com.evolution.analysis.spring.universal.SpringProxyTrapDetector.IntraClassCall(
                        "com.acme.OrderService", "placeOrder", "saveTx", false, "OrderService.java", 15));

        var evaluator = new PolicyEvaluator();
        var report = evaluator.evaluateSpringProxyTraps(
                snap, List.of(bean), calls,
                com.evolution.analysis.spring.universal.SpringAopMetadataExtractor.Result.empty(),
                PolicyEvaluator.SPRING_PROXY_TRAP_RULE_IDS);

        assertEquals(AssessmentStatus.COMPLETE, report.status());
        assertEquals(Classification.MUST, report.classification());
        assertEquals(2, report.rules().size());
        assertEquals(1, report.findingsForRule(com.evolution.analysis.spring.universal.SpringProxyTrapDetector.RULE_SELF_INVOCATION_BYPASS).size());
        assertEquals(2, report.findingsForRule(com.evolution.analysis.spring.universal.SpringProxyTrapDetector.RULE_CGLIB_PRIVATE_FINAL_TRAP).size());
        assertEquals(report.identity(), evaluator.evaluateSpringProxyTraps(
                snap, List.of(bean), calls,
                com.evolution.analysis.spring.universal.SpringAopMetadataExtractor.Result.empty(),
                PolicyEvaluator.SPRING_PROXY_TRAP_RULE_IDS).identity());

        // Single-rule filter restricts evaluation deterministically
        var selfOnly = evaluator.evaluateSpringProxyTraps(
                snap, List.of(bean), calls,
                com.evolution.analysis.spring.universal.SpringAopMetadataExtractor.Result.empty(),
                Set.of(com.evolution.analysis.spring.universal.SpringProxyTrapDetector.RULE_SELF_INVOCATION_BYPASS));
        assertEquals(1, selfOnly.rules().size());
        assertEquals(1, selfOnly.findings().size());
        assertTrue(selfOnly.findingsForRule(com.evolution.analysis.spring.universal.SpringProxyTrapDetector.RULE_CGLIB_PRIVATE_FINAL_TRAP).isEmpty());
    }
}
