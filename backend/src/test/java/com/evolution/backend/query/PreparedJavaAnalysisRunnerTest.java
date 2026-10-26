package com.evolution.backend.query;

import com.evolution.analysis.graph.*;
import com.evolution.analysis.ingestion.UniversalArchitecturePipeline;
import com.evolution.analysis.javaevidence.*;
import com.evolution.analysis.javaparser.JavaParserFrontend;
import com.evolution.analysis.query.ArchitectureQuery;
import com.evolution.backend.analysis.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import static org.junit.jupiter.api.Assertions.*;

/** Real frozen native bytes under the public fixture authority. No target execution.
 * The fixture classes are explicit trusted platform test inputs, not production code. */
@Tag("native-campaign")
class PreparedJavaAnalysisRunnerTest {
    @org.junit.jupiter.api.io.TempDir Path publicationDirectory;
    @Test void registeredReadinessProofsReachBothProductRunnersAndWithdrawalReopensThem()throws Exception{registeredCaptureReplaysTheWholeProductInFreshJvmsWithoutTestClasses("custom-producer");}
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"protobuf-client","protobuf-schema","lombok-model","mapstruct-mapper"})
    void registeredCaptureReplaysTheWholeProductInFreshJvmsWithoutTestClasses(String name)throws Exception{
        Path nativeRoot=Path.of(System.getProperty(Set.of("mapstruct-mapper","custom-producer").contains(name)?"native.spring.root":"native.fixture.root"));
        var type=Class.forName("com.evolution.analysis.javaparser.NativePipelineWiringFixture");
        var constructor=type.getDeclaredConstructor(Path.class);constructor.setAccessible(true);
        var fixture=constructor.newInstance(nativeRoot);
        var execute=type.getDeclaredMethod("execute",String.class,Map.class,boolean.class);execute.setAccessible(true);
        var result=execute.invoke(fixture,name,name.equals("custom-producer")?Map.of("captureReplayAttempt","00000000-0000-0000-0000-000000000001","captureReplayRecordedAt","2026-10-25T00:00:00Z"):Map.of(),name.equals("custom-producer"));
        var prepared=(JavaEvidenceAssembler.Prepared)value(result,"prepared");var semantic=(JavaSemanticEvidence.Bundle)value(result,"semantic");
        if(name.equals("custom-producer")){
            var detailed=(UniversalArchitecturePipeline.DetailedResult)value(result,"pipeline");
            var replayPolicy=UniversalArchitecturePipeline.Policy.builder(new JavaParserFrontend(),JavaParserFrontend.PROVIDER).truthEvaluationMode(UniversalArchitecturePipeline.TruthEvaluationMode.NONE).build();
            new JavaSpringSemanticProjection().project(prepared,detailed,replayPolicy,new JavaSemanticProjection().project(prepared,detailed));
        }
        var content=semantic.content();var tables=new JavaEvidenceTables(content,2_000_000);
        var receipt=object(content,(com.evolution.analysis.acquisition.streaming.ContentHandle)value(result,"receipt"));
        var claims=JavaEvidenceTables.Table.from(receipt.get("claims"));var envelopes=new ArrayList<JavaEvidenceRecord>();tables.read(claims,envelopes::add,()->false);
        var envelope=object(content,JavaEvidenceRecord.handle(envelopes.getFirst().fields().get("content")));
        var attestation=GraphJson.object(envelope.get("attestation"));var transport=GraphJson.object(envelope.get("receipt"));
        var tool=new com.evolution.analysis.contract.common.ContentDigest(attestation.get("tool").toString());
        var methods=Set.of("java.output-bytes","java.generated-lineage","java.resolution-context","java.binary-shape","java.source-binary-correspondence","java.semantic-occurrence","java.generator-not-applicable","java.build-validation",JavaEvidenceGapClosure.QUESTION,JavaReadinessClosure.QUESTION).stream().sorted()
                .map(q->new com.evolution.analysis.execution.EvidenceVerifier.Method(new com.evolution.analysis.contract.common.VersionedIdentifier("fixture.pipeline-native."+q,"1"),new com.evolution.analysis.contract.common.VersionedIdentifier(q,JavaEvidenceAuthority.VERSION),tool,List.copyOf(JavaEvidenceAuthority.requiredChecks(q)))).toList();
        var reader=new CapturedJavaReader(content,()->false);var captured=reader.capture(prepared,semantic,claims);
        var registration=new CapturedJavaReader.Registration("captured-java-registration:m8.4-v1",nativeRoot.resolve("native-import-cas").toString(),captured,prepared.policy(),
                Map.of("pipeline-fixture.controller","MCowBQYDK2VwAyEAw8eO+AJdUxvn+6U2uTRlt0hxLuXB6F2Wqq8cUcAoJxs="),
                Map.of("pipeline-fixture.validator","MCowBQYDK2VwAyEAq58LVuStl6kF+NO0q3rauqzI9PoIdxtRmeOIdm+EthY="),methods,
                new com.evolution.analysis.contract.common.ContentDigest(transport.get("launch").toString()),new com.evolution.analysis.contract.common.ContentDigest(transport.get("profile").toString()),1,"PUBLIC_TEST_POLICY_ONLY");
        Path registered=Path.of(System.getProperty("native.product.root",publicationDirectory.resolve("registered").toString())).resolve(name);java.nio.file.Files.createDirectories(registered);
        java.nio.file.Files.writeString(registered.resolve("registration.json"),com.evolution.analysis.contract.serialization.CanonicalJson.write(registration));
        var loaded=reader.load(registration,registered.resolve("reader-check"));
        assertEquals(prepared.view().identity(),loaded.prepared().view().identity());
        if(name.equals("custom-producer"))assertReadinessWithdrawal(registration,content,registered);
        var first=productProcess(registered);
        var reordered=new LinkedHashMap<>(GraphJson.object(GraphJson.read(com.evolution.analysis.contract.serialization.CanonicalJson.write(registration),GraphJson.Limits.defaults())));
        var reversedMethods=new ArrayList<>(methods);Collections.reverse(reversedMethods);reordered.put("methods",reversedMethods);
        java.nio.file.Files.writeString(registered.resolve("registration.json"),com.evolution.analysis.contract.serialization.CanonicalJson.write(reordered));
        var second=productProcess(registered);
        assertEquals(first.get("semantic"),second.get("semantic"),"Full Java/Spring graph, root graph, active/history counts and publication must be identical across fresh JVMs");
        assertNotEquals(first.get("audit"),second.get("audit"),"Operational UUID/time bindings must remain distinct");
        var summary=GraphJson.object(first.get("semantic"));assertEquals("PARTIAL",summary.get("state"));
        assertEquals(new java.math.BigDecimal(((Number)receipt.get("rawGapRecords")).longValue()),summary.get("history"));
        assertEquals(summary.get("all"),((java.math.BigDecimal)summary.get("current")).add((java.math.BigDecimal)summary.get("history")));
        assertEquals(summary.get("current"),summary.get("jobGaps"));
        var graph=new com.evolution.analysis.filesystem.FileGraphStore(registered.resolve("graphs")).load(new com.evolution.analysis.contract.common.ContentDigest(summary.get("graph").toString())).orElseThrow();
        assertTrue(graph.evidence().stream().anyMatch(e->e.family().startsWith("spring.")),"The determinism comparison includes actual Spring enrichment");
        if(name.equals("custom-producer")){
            var readiness=graph.evidence().stream().filter(e->e.family().equals("java.evidence.readiness-coverage")).findFirst().orElseThrow();
            var counts=GraphJson.object(GraphJson.object(GraphJson.read(readiness.canonicalJson(),GraphJson.Limits.defaults())).get("counts"));
            assertEquals(new java.math.BigDecimal(6),counts.get("SATISFIED"));assertEquals(java.math.BigDecimal.ZERO,counts.get("active"));assertEquals(java.math.BigDecimal.ZERO,counts.get("frontierCount"));
            var rootGraph=new com.evolution.analysis.filesystem.FileGraphStore(registered.resolve("graphs")).load(new com.evolution.analysis.contract.common.ContentDigest(summary.get("rootGraph").toString())).orElseThrow();
            var coverage=rootGraph.evidence().stream().filter(e->e.family().equals("java.evidence.repository-coverage")).findFirst().orElseThrow();
            var rootCounts=GraphJson.object(GraphJson.object(GraphJson.read(coverage.canonicalJson(),GraphJson.Limits.defaults())).get("counts"));
            assertEquals(java.math.BigDecimal.ZERO,rootCounts.get("readinessActive"));assertEquals(new java.math.BigDecimal(6),rootCounts.get("readinessRequirements"));
            assertCaptureBindingReplay(fixture,execute,registration,registered,graph,rootGraph);
        }
        var api=new ArchitectureQueryApi(new com.evolution.analysis.filesystem.FileGraphStore(registered.resolve("graphs")));
        for(String view:List.of("CURRENT","HISTORY","ALL")){
            var seen=new HashSet<String>();
            for(var context:graph.contexts()){
            var request=ArchitectureQuery.Request.dependencies(graph.identity(),context.identity());
            byte[] body=com.evolution.analysis.contract.serialization.CanonicalJson.write(Map.of("request",request,"arguments",Map.of("view",view),"page",ArchitectureQuery.Page.first())).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var response=api.handle("POST","/api/v1/graphs/"+graph.identity().value()+"/queries/gaps","application/json",body,()->false);assertEquals(200,response.status());
            var json=GraphJson.object(GraphJson.read(new String(response.body(),java.nio.charset.StandardCharsets.UTF_8),GraphJson.Limits.defaults()));
            var data=GraphJson.object(json.get("data"));assertNull(data.get("continuation"));
            var scoped=new HashSet<String>();for(var item:GraphJson.array(data.get("items"))){String id=GraphJson.object(item).get("identity").toString();assertTrue(scoped.add(id));seen.add(id);}
            if(data.get("exactTotal")!=null)assertEquals(((java.math.BigDecimal)data.get("exactTotal")).intValueExact(),GraphJson.array(data.get("items")).size());
            }
            assertEquals(((java.math.BigDecimal)summary.get(view.toLowerCase(Locale.ROOT))).intValueExact(),seen.size());
        }
        var forged=new LinkedHashMap<>(GraphJson.object(GraphJson.read(com.evolution.analysis.contract.serialization.CanonicalJson.write(registration),GraphJson.Limits.defaults())));forged.put("qualification","REGISTERED_VALIDATION");
        assertThrows(java.io.IOException.class,()->reader.load(CanonicalRecordReader.read(forged,CapturedJavaReader.Registration.class),registered.resolve("rejected")));
        forged.put("qualification","PUBLIC_TEST_POLICY_ONLY");forged.put("launch",com.evolution.analysis.contract.common.ContentDigest.sha256Utf8("foreign launch").value());
        assertThrows(java.io.IOException.class,()->reader.load(CanonicalRecordReader.read(forged,CapturedJavaReader.Registration.class),registered.resolve("rejected")));
    }
    private static void assertCaptureBindingReplay(Object fixture,java.lang.reflect.Method execute,CapturedJavaReader.Registration registration,Path registered,KnowledgeGraph first,KnowledgeGraph firstRoot)throws Exception{
        var result=execute.invoke(fixture,"custom-producer",Map.of("captureReplayAttempt","00000000-0000-0000-0000-000000000002","captureReplayRecordedAt","2026-10-26T12:34:56Z"),true);
        var prepared=(JavaEvidenceAssembler.Prepared)value(result,"prepared");var semantic=(JavaSemanticEvidence.Bundle)value(result,"semantic");
        var content=semantic.content();var receipt=object(content,(com.evolution.analysis.acquisition.streaming.ContentHandle)value(result,"receipt"));
        var reader=new CapturedJavaReader(content,()->false);var capture=reader.capture(prepared,semantic,JavaEvidenceTables.Table.from(receipt.get("claims")));
        assertNotEquals(registration.capture(),capture,"The operational binding inside the captured inputs must actually change");
        var reversed=new ArrayList<>(registration.methods());Collections.reverse(reversed);
        var changed=new CapturedJavaReader.Registration(registration.schema(),registration.contentDirectory(),capture,registration.policy(),registration.controllerKeys(),registration.validatorKeys(),reversed,
                new com.evolution.analysis.contract.common.ContentDigest(prepared.view().object("scope").get("context").toString()),registration.profile(),registration.fence(),registration.qualification());
        Path directory=java.nio.file.Files.createDirectories(registered.resolve("changed-binding"));
        java.nio.file.Files.writeString(directory.resolve("registration.json"),com.evolution.analysis.contract.serialization.CanonicalJson.write(changed));
        var output=productProcess(directory);var summary=GraphJson.object(output.get("semantic"));var graphs=new com.evolution.analysis.filesystem.FileGraphStore(directory.resolve("graphs"));
        var second=graphs.load(new com.evolution.analysis.contract.common.ContentDigest(summary.get("graph").toString())).orElseThrow();
        var secondRoot=graphs.load(new com.evolution.analysis.contract.common.ContentDigest(summary.get("rootGraph").toString())).orElseThrow();
        assertEquals(first.snapshot(),second.snapshot());assertNotEquals(first.identity(),second.identity());assertNotEquals(firstRoot.identity(),secondRoot.identity());
        assertEquals(payload(first,"java.evidence.semantic-projection").get("semanticIdentity"),payload(second,"java.evidence.semantic-projection").get("semanticIdentity"));
        assertNotEquals(payload(first,"java.evidence.semantic-audit-binding"),payload(second,"java.evidence.semantic-audit-binding"));
        var springA=payload(first,"java.evidence.spring-semantic-projection");var springB=payload(second,"java.evidence.spring-semantic-projection");
        assertEquals("DEFAULT_SCOPE_TRUTH_NONE",springA.get("scope"));assertEquals(springA,springB,"Complete canonical Spring semantic replay includes plan, condition metadata, framework version, order and gaps");
        var body=GraphJson.object(springA.get("body"));assertTrue(body.containsKey("plan"));
        assertEquals(springProductMeaning(first),springProductMeaning(second),"Actual product Spring outputs must agree as well as the additive replay");
        assertEquals(payload(firstRoot,"java.evidence.repository-coverage").get("counts"),payload(secondRoot,"java.evidence.repository-coverage").get("counts"));
        assertEquals(payload(firstRoot,"java.evidence.repository-coverage").get("rootCauseDelta"),payload(secondRoot,"java.evidence.repository-coverage").get("rootCauseDelta"));
        assertEquals(rootContexts(firstRoot),rootContexts(secondRoot));
        assertEquals(currentGapMeaning(first),currentGapMeaning(second));
        // Non-vacuity: this product context retains the registered UNKNOWN order gap.
        assertTrue(currentGapMeaning(first).stream().anyMatch(s->s.contains("ORDER_EVIDENCE_MISSING")));
    }
    /** Source-anchored observables of this registered two-profile component scope.
     * Every reference replacement has a checked target; unsupported shapes fail the control. */
    private static Object springProductMeaning(KnowledgeGraph graph){
        var plan=payload(graph,"spring.plan");var inventory=GraphJson.object(plan.get("inventory"));
        var framework=new TreeMap<>(GraphJson.object(inventory.get("frameworkEvidence")));framework.remove("classpathManifestIdentity");
        var result=new TreeMap<String,Object>();result.put("framework",framework);
        var observations=new HashMap<String,Object>();for(var value:GraphJson.array(inventory.get("rawObservations"))){var row=new TreeMap<>(GraphJson.object(value));String id=row.remove("identity").toString();observations.put(id,row);}
        var obligations=new HashMap<String,Object>();for(var value:GraphJson.array(inventory.get("obligations"))){var row=new TreeMap<>(GraphJson.object(value));String id=row.remove("identity").toString();var observation=observations.get(row.get("rawObservationIdentity").toString());assertNotNull(observation);row.put("rawObservationIdentity",observation);obligations.put(id,row);}
        result.put("rawObservations",observations.values().stream().map(com.evolution.analysis.contract.serialization.CanonicalJson::write).sorted().toList());result.put("obligations",obligations.values().stream().map(com.evolution.analysis.contract.serialization.CanonicalJson::write).sorted().toList());
        for(String field:List.of("provider","mechanismCatalog","catalogRevision","coverage","problems","limitations"))result.put("inventory."+field,inventory.get(field));
        for(String family:List.of("spring.components","spring.constructors")){
            var projected=new ArrayList<String>();for(var value:GraphJson.array(payload(graph,family).get("rows"))){var row=new TreeMap<>(GraphJson.object(value));row.put("evidence",sourceAnchoredEvidence(row.get("evidence")));projected.add(com.evolution.analysis.contract.serialization.CanonicalJson.write(row));}
            result.put(family,projected.stream().sorted().toList());
        }
        var expressions=new HashMap<String,Map<String,Object>>();
        graph.evidence().stream().filter(e->e.family().equals("spring.condition-expression")).forEach(e->{var row=GraphJson.object(GraphJson.read(e.canonicalJson(),GraphJson.Limits.defaults()));expressions.put(row.get("identity").toString(),row);});
        var conditions=new HashMap<String,Object>();var candidates=new HashMap<String,Object>();var events=new HashMap<String,Object>();
        for(var node:graph.nodes())if(Set.of("CONFIGURATION_CONDITION","BEAN_DEFINITION_CANDIDATE","REGISTRATION_EVENT").contains(node.kind())){
            var family=switch(node.kind()){case "CONFIGURATION_CONDITION"->"spring.condition";case "BEAN_DEFINITION_CANDIDATE"->"spring.candidate";default->"spring.registration-event";};
            var evidence=node.evidence().stream().map(graph::evidence).map(Optional::orElseThrow).filter(e->e.family().equals(family)).findFirst().orElseThrow();
            var row=GraphJson.object(GraphJson.read(evidence.canonicalJson(),GraphJson.Limits.defaults()));
            if(node.kind().equals("CONFIGURATION_CONDITION")){
                var condition=new TreeMap<>(row);String metadata=condition.get("metadataPath").toString();assertTrue(metadata.startsWith("m4a-obligation/"));var obligation=obligations.get(metadata.substring("m4a-obligation/".length()));assertNotNull(obligation);condition.put("metadataPath",obligation);condition.put("expression",expressionMeaning(GraphJson.object(row.get("expression")).get("rootIdentity").toString(),expressions,new HashSet<>()));
                conditions.put(node.identity(),condition);
            }else if(node.kind().equals("BEAN_DEFINITION_CANDIDATE"))candidates.put(node.identity(),row.get("definitionSlot"));
            else events.put(node.identity(),row.get("eventSlot"));
        }
        result.put("conditions",conditions.values().stream().map(com.evolution.analysis.contract.serialization.CanonicalJson::write).sorted().toList());
        for(String family:List.of("spring.candidate","spring.producer","spring.registration-event","spring.registration-step")){
            var rows=new ArrayList<String>();for(var evidence:graph.evidence())if(evidence.family().equals(family)){
                var row=new TreeMap<>(GraphJson.object(GraphJson.read(evidence.canonicalJson(),GraphJson.Limits.defaults())));
                if(family.equals("spring.candidate"))row.put("producer",producerMeaning(row.get("producer")));
                if(family.equals("spring.producer"))row=new TreeMap<>(producerMeaning(row));
                if(family.equals("spring.registration-event")){
                    row.remove("buildContextIdentity");var version=new TreeMap<>(GraphJson.object(row.get("frameworkSemantics")));version.remove("frameworkEvidence");row.put("frameworkSemantics",version);
                    var candidate=new TreeMap<>(GraphJson.object(row.get("candidate")));candidate.put("producer",producerMeaning(candidate.get("producer")));row.put("candidate",candidate);
                    var refs=new ArrayList<Object>();for(var value:GraphJson.array(row.get("conditions"))){var ref=new TreeMap<>(GraphJson.object(value));var condition=conditions.get(ref.get("occurrence").toString());assertNotNull(condition);ref.put("occurrence",condition);refs.add(ref);}row.put("conditions",refs);
                    assertNull(row.get("parent"));var eventObligations=new ArrayList<Object>();for(var ref:GraphJson.array(row.get("obligations"))){var obligation=obligations.get(ref.toString());assertNotNull(obligation);eventObligations.add(obligation);}row.put("obligations",eventObligations.stream().map(com.evolution.analysis.contract.serialization.CanonicalJson::write).sorted().toList());
                }
                if(family.equals("spring.registration-step")){var event=events.get(row.get("discoveryEvent").toString());assertNotNull(event);row.put("discoveryEvent",event);assertNull(row.get("gate"));}
                rows.add(com.evolution.analysis.contract.serialization.CanonicalJson.write(row));
            }result.put(family,rows.stream().sorted().toList());
        }
        var binding=new TreeMap<>(GraphJson.object(plan.get("binding")));var definitions=new ArrayList<String>();
        for(var value:GraphJson.array(binding.get("definitions"))){var row=new TreeMap<>(GraphJson.object(value));var candidate=candidates.get(row.get("candidate").toString());assertNotNull(candidate);row.put("candidate",candidate);assertNull(row.get("factoryOwner"));definitions.add(com.evolution.analysis.contract.serialization.CanonicalJson.write(row));}
        result.put("definitions",definitions.stream().sorted().toList());result.put("environment",binding.get("environment"));result.put("limits",binding.get("limits"));
        for(String empty:List.of("dependencies","groups","matches","obligations")){assertEquals(List.of(),binding.get(empty),empty);result.put(empty,binding.get(empty));}
        var registration=GraphJson.object(binding.get("registrationPlan"));for(String field:List.of("order","noParentContainer","establishedOrderPrefix","initialRegistry","queries","queryTypes","limits"))result.put("registration."+field,registration.get(field));
        return result;
    }
    private static Map<String,Object> producerMeaning(Object value){var result=new TreeMap<>(GraphJson.object(value));result.remove("buildContextIdentity");result.put("declarationEvidenceKey",sourceAnchoredEvidence(result.get("declarationEvidenceKey")));return result;}
    private static Object sourceAnchoredEvidence(Object value){var row=new TreeMap<>(GraphJson.object(value));if("DERIVED".equals(row.get("kind"))){assertTrue(Set.of("spring.component-scan-ingestion","spring.constructor-ingestion").contains(GraphJson.object(row.get("method")).get("id")));row.remove("inputs");}return row;}
    private static Object expressionMeaning(String identity,Map<String,Map<String,Object>> expressions,Set<String> visiting){
        assertTrue(visiting.add(identity));var row=new TreeMap<>(expressions.get(identity));assertNotNull(row);row.remove("identity");var version=new TreeMap<>(GraphJson.object(row.get("frameworkSemantics")));version.remove("frameworkEvidence");row.put("frameworkSemantics",version);
        row.put("childExpressionIdentities",GraphJson.array(row.get("childExpressionIdentities")).stream().map(v->expressionMeaning(v.toString(),expressions,new HashSet<>(visiting))).toList());return row;
    }
    private static Map<String,Object> payload(KnowledgeGraph graph,String family){
        var rows=graph.evidence().stream().filter(e->e.family().equals(family)).toList();assertEquals(1,rows.size(),family);
        return GraphJson.object(GraphJson.read(rows.getFirst().canonicalJson(),GraphJson.Limits.defaults()));
    }
    /** Root semantic coverage: exact slot metadata and result state, without child audit graph addresses. */
    private static List<String> rootContexts(KnowledgeGraph graph){
        return GraphJson.object(payload(graph,"java.evidence.repository-coverage").get("contexts")).values().stream().map(GraphJson::object).map(row->{
            var result=new TreeMap<>(row);result.remove("graph");return com.evolution.analysis.contract.serialization.CanonicalJson.write(result);
        }).sorted().toList();
    }
    /** Registered current-gap observable: all descriptive fields, natural subjects and requirements.
     * ORDER_EVIDENCE_MISSING alone has an audit-bound observation subject; its full stable
     * observation/condition/order meaning is retained in the compared Spring replay above. */
    private static List<String> currentGapMeaning(KnowledgeGraph graph){
        var rows=new ArrayList<String>();
        for(var node:graph.nodes())if(node.kind().equals("CAPABILITY_GAP"))for(var ref:node.evidence()){
            var item=graph.evidence(ref).orElseThrow();if(!item.family().equals("evidence.gap"))continue;
            var gap=GraphJson.object(GraphJson.read(item.canonicalJson(),GraphJson.Limits.defaults()));
            assertTrue(Set.of("CATEGORY_PARTIAL","ORDER_EVIDENCE_MISSING").contains(gap.get("reasonCode")),"New gap kinds require an explicit semantic projection contract");
            var meaning=new TreeMap<String,Object>();for(String key:List.of("schemaVersion","catalog","detectingProvider","mechanismCategory","reasonCode","sourceSpans","candidateProviders","affectedOutputs","diagnostics","limitations"))meaning.put(key,gap.get(key));
            var subject=GraphJson.object(gap.get("subject"));meaning.put("subject",gap.get("reasonCode").equals("ORDER_EVIDENCE_MISSING")?Map.of("kind",subject.get("kind"),"semanticObservation","DEFAULT_SCOPE_REGISTRATION_ORDER"):subject);
            var requirements=new ArrayList<Object>();for(var value:GraphJson.array(gap.get("evidenceRequirements"))){var requirement=new TreeMap<>(GraphJson.object(value));if(gap.get("reasonCode").equals("ORDER_EVIDENCE_MISSING"))requirement.put("requiredInputs",List.of(Map.of("kind",subject.get("kind"),"identity","DEFAULT_SCOPE_REGISTRATION_ORDER")));requirements.add(requirement);}meaning.put("evidenceRequirements",requirements);
            rows.add(com.evolution.analysis.contract.serialization.CanonicalJson.write(meaning));
        }
        return rows.stream().sorted().toList();
    }
    private static void assertReadinessWithdrawal(CapturedJavaReader.Registration registration,com.evolution.analysis.acquisition.streaming.RepositoryContentStore delegate,Path directory)throws Exception{
        var revoked=new java.util.concurrent.atomic.AtomicReference<com.evolution.analysis.acquisition.streaming.ContentHandle>();
        var content=new com.evolution.analysis.acquisition.streaming.RepositoryContentStore(){
            public com.evolution.analysis.acquisition.streaming.ContentHandle put(java.io.InputStream input,java.util.function.BooleanSupplier cancelled)throws java.io.IOException{return delegate.put(input,cancelled);}
            public java.io.InputStream open(com.evolution.analysis.acquisition.streaming.ContentHandle handle)throws java.io.IOException{return delegate.open(handle);}
            public java.io.InputStream range(com.evolution.analysis.acquisition.streaming.ContentHandle handle,long offset,long length)throws java.io.IOException{return delegate.range(handle,offset,length);}
            public boolean verify(com.evolution.analysis.acquisition.streaming.ContentHandle handle)throws java.io.IOException{return !handle.equals(revoked.get())&&delegate.verify(handle);}
        };
        var loaded=new CapturedJavaReader(content,()->false).load(registration,directory.resolve("revocation-views"));
        assertEquals(6,loaded.readinessProofs().size());
        assertTrue(new JavaReadinessClosure().reconcile(loaded.prepared().readiness(),registration.policy(),loaded.readinessProofs()).readinessClosed());
        revoked.set(JavaEvidenceRecord.handle(loaded.readinessProofs().getFirst().record().fields().get("answer")));
        var withdrawn=new JavaReadinessClosure().reconcile(loaded.prepared().readiness(),registration.policy(),loaded.readinessProofs());
        assertFalse(withdrawn.readinessClosed());assertEquals(1,withdrawn.active());
        assertEquals(1,withdrawn.requirements().values().stream().filter(r->r.state()==JavaReadinessClosure.State.REOPENED).count());
        var graphs=new InMemoryGraphStore();var policy=UniversalArchitecturePipeline.Policy.builder(new JavaParserFrontend(),JavaParserFrontend.PROVIDER).truthEvaluationMode(UniversalArchitecturePipeline.TruthEvaluationMode.NONE).build();
        var child=new PreparedJavaAnalysisRunner(loaded.prepared(),policy,Optional.of(loaded.semantic()),Optional.empty(),registration::policy,loaded.readinessProofs());
        var root=new PreparedJavaRepositoryRunner(loaded.prepared().readiness(),registration.policy(),registration::policy,loaded.readinessProofs(),List.of(child),graphs);
        var setup=new AnalysisJobs.Setup("withdrawn-root",loaded.prepared().base().snapshot().repository(),21,false,Optional.empty(),Optional.empty());
        try(var jobs=new AnalysisJobs(List.of(child.registration("withdrawn-child"),root.registration(setup)),graphs,new PublishedReports(),new AnalysisJobs.Limits(1,1,8,0,1000))){
            for(String name:List.of("withdrawn-child","withdrawn-root")){
                var result=finished(jobs,jobs.submit(name,"withdrawal-"+name).job().id());assertNotEquals(AnalysisJobs.State.SUCCEEDED,result.state());
                assertTrue(jobs.gaps(UUID.fromString(result.id()),50,Optional.empty()).items().stream().anyMatch(g->g.reasonCode().equals("EVIDENCE_REVOKED")||g.reasonCode().equals("READINESS_REOPENED")));
            }
        }
    }
    private static Map<String,Object> object(com.evolution.analysis.acquisition.streaming.RepositoryContentStore content,com.evolution.analysis.acquisition.streaming.ContentHandle handle)throws Exception{
        assertTrue(content.verify(handle));try(var input=content.open(handle)){return GraphJson.object(GraphJson.read(new String(input.readNBytes(8_000_001),java.nio.charset.StandardCharsets.UTF_8),new GraphJson.Limits(8_000_000,128,8_000_000)));}
    }
    private static Map<String,Object> productProcess(Path root)throws Exception{
        String classpath=Arrays.stream(System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")).split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                .filter(p->!p.replace('\\','/').contains("test-classes")&&!p.endsWith("-tests.jar")).collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
        assertFalse(classpath.replace('\\','/').contains("test-classes"));
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx1536m","-cp",classpath,CapturedJavaExecution.class.getName(),root.toString()).redirectErrorStream(true).start();
        try{assertTrue(process.waitFor(600,TimeUnit.SECONDS));String output=new String(process.getInputStream().readNBytes(32769),java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0,process.exitValue(),output);assertTrue(output.length()<32768);return GraphJson.object(GraphJson.read(output,new GraphJson.Limits(32768,64,32768)));
        }finally{if(process.isAlive())process.destroyForcibly();}
    }
    @Test void registeredCapturedContextPublishesCurrentAndHistoricalViewsThroughJobsAndApi()throws Exception{
        {
            var type=Class.forName("com.evolution.analysis.javaparser.NativePipelineWiringFixture");
            var constructor=type.getDeclaredConstructor(Path.class);constructor.setAccessible(true);
            var fixture=constructor.newInstance(Path.of(System.getProperty("native.fixture.root")));
            var execute=type.getDeclaredMethod("execute",String.class);execute.setAccessible(true);
            var result=execute.invoke(fixture,"protobuf-client");
            var prepared=(JavaEvidenceAssembler.Prepared)value(result,"prepared");
            var semantic=(JavaSemanticEvidence.Bundle)value(result,"semantic");
            var policy=UniversalArchitecturePipeline.Policy.builder(new JavaParserFrontend(),JavaParserFrontend.PROVIDER)
                    .truthEvaluationMode(UniversalArchitecturePipeline.TruthEvaluationMode.NONE).build();
            var publicationContent=new com.evolution.analysis.filesystem.StreamingContentStore(publicationDirectory.resolve("cas"));
            var publicationStore=new com.evolution.analysis.filesystem.JavaEvidencePublicationStore(publicationDirectory.resolve("publications"),publicationContent);
            var publication=new PreparedAnalysisPublication(publicationStore,publicationContent,com.evolution.analysis.contract.common.ContentDigest.sha256Utf8("registered captured context"));
            var runner=new PreparedJavaAnalysisRunner(prepared,policy,Optional.of(semantic),Optional.empty()).withPublication(publication);
            var reopened=new PreparedAnalysisPublication(new com.evolution.analysis.filesystem.JavaEvidencePublicationStore(publicationDirectory.resolve("publications"),publicationContent),publicationContent,
                    com.evolution.analysis.contract.common.ContentDigest.sha256Utf8("registered captured context"));
            var restarted=new PreparedJavaAnalysisRunner(prepared,policy,Optional.of(semantic),Optional.empty()).withPublication(reopened);
            var revoked=new PreparedJavaAnalysisRunner(prepared,policy,Optional.of(semantic),Optional.empty(),()->com.evolution.analysis.contract.common.ContentDigest.sha256Utf8("changed authority registry"));
            var bytesOnly=new PreparedJavaAnalysisRunner(prepared,policy,Optional.empty(),Optional.empty());
            var graphs=new InMemoryGraphStore();var reports=new PublishedReports();
            var rootRunner=new PreparedJavaRepositoryRunner(prepared.readiness(),prepared.policy(),prepared::policy,List.of(),List.of(restarted),graphs)
                    .withCheckpoint(publicationStore,publicationContent,com.evolution.analysis.contract.common.ContentDigest.sha256Utf8("registered native root"));
            var rootSetup=new AnalysisJobs.Setup("root-coverage",prepared.base().snapshot().repository(),21,false,Optional.empty(),Optional.empty());
            var changedPolicy=UniversalArchitecturePipeline.Policy.builder(new JavaParserFrontend(),JavaParserFrontend.PROVIDER)
                    .maxTypes(policy.maxTypes()+1).truthEvaluationMode(UniversalArchitecturePipeline.TruthEvaluationMode.NONE).build();
            var changedWork=new PreparedJavaAnalysisRunner(prepared,changedPolicy,Optional.of(semantic),Optional.empty()).withPublication(reopened);
            try(var jobs=new AnalysisJobs(List.of(runner.registration("captured-context"),restarted.registration("restarted-context"),revoked.registration("revoked-context"),bytesOnly.registration("no-semantic-census"),rootRunner.registration(rootSetup),changedWork.registration("changed-work-policy")),graphs,reports,new AnalysisJobs.Limits(1,1,8,180_000,1000))){
                var submitted=jobs.submit("captured-context","native-history");
                var view=finished(jobs,submitted.job().id());
                assertEquals(AnalysisJobs.State.PARTIAL,view.state(),()->view.error().toString());
                var graph=graphs.load(view.result().orElseThrow().graph().orElseThrow()).orElseThrow();
                assertTrue(publication.latest().isPresent(),"The real composed native runner must durably publish the captured evidence revision");
                var committed=publication.latest().orElseThrow();assertEquals(committed,reopened.latest().orElseThrow());
                assertEquals(Set.of("graph","input-footprint","readiness","semantic-view"),committed.artifacts().keySet());
                try(var input=publicationContent.open(committed.artifacts().get("graph"));var reader=new java.io.InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8)){
                    assertEquals(graph.identity(),new GraphSnapshotCodec().decode(reader,GraphJson.Limits.defaults()).identity());
                }
                assertTrue(graph.evidence().stream().anyMatch(e->e.family().equals("java.evidence.readiness-coverage")),
                        "The product must retain the complete readiness denominator alongside one analyzed context");
                assertTrue(graph.evidence().stream().anyMatch(e->e.family().equals("java.evidence.semantic-projection")),
                        "The real runner must expose the typed context semantic projection");
                assertTrue(graph.evidence().stream().anyMatch(e->e.family().equals("java.evidence.semantic-audit-binding")));
                var historical=graph.nodes().stream().filter(n->n.kind().equals("HISTORICAL_CAPABILITY_GAP")).map(KnowledgeGraph.Node::identity).collect(java.util.stream.Collectors.toSet());
                assertFalse(historical.isEmpty());
                var jobGaps=jobs.gaps(UUID.fromString(view.id()),50,Optional.empty()).items();
                assertTrue(jobGaps.stream().noneMatch(g->historical.contains(g.gapIdentity().value())));
                var current=graph.nodes().stream().filter(n->n.kind().equals("CAPABILITY_GAP")).map(KnowledgeGraph.Node::identity).collect(java.util.stream.Collectors.toSet());
                var union=new HashSet<>(current);jobGaps.forEach(g->union.add(g.gapIdentity().value()));
                assertEquals(union.size(),view.result().orElseThrow().gapCount());
                var restored=new GraphSnapshotCodec().decode(new GraphSnapshotCodec().encode(graph),GraphJson.Limits.defaults());
                var store=new InMemoryGraphStore();store.save(restored);
                var api=new ArchitectureQueryApi(store);
                var request=ArchitectureQuery.Request.dependencies(restored.identity(),restored.snapshot().value());
                byte[] body=com.evolution.analysis.contract.serialization.CanonicalJson.write(Map.of("request",request,"arguments",Map.of("view","HISTORY"),"page",ArchitectureQuery.Page.first())).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                var response=api.handle("POST","/api/v1/graphs/"+graph.identity().value()+"/queries/gaps","application/json",body,()->false);
                assertEquals(200,response.status());
                var json=GraphJson.object(GraphJson.read(new String(response.body(),java.nio.charset.StandardCharsets.UTF_8),new GraphJson.Limits(4_000_000,128,1_000_000)));
                var data=GraphJson.object(json.get("data"));
                assertEquals(historical,GraphJson.array(data.get("items")).stream().map(GraphJson::object).map(row->GraphJson.string(row.get("identity"))).collect(java.util.stream.Collectors.toSet()));
                var rejected=finished(jobs,jobs.submit("revoked-context","changed-policy").job().id());
                assertEquals(AnalysisJobs.State.FAILED,rejected.state());
                assertTrue(jobs.gaps(UUID.fromString(rejected.id()),50,Optional.empty()).items().stream().anyMatch(g->g.reasonCode().equals("EVIDENCE_REVOKED")));
                var incomplete=finished(jobs,jobs.submit("no-semantic-census","without-occurrence-validation").job().id());
                assertEquals(AnalysisJobs.State.PARTIAL,incomplete.state());
                assertTrue(jobs.gaps(UUID.fromString(incomplete.id()),50,Optional.empty()).items().stream().anyMatch(g->g.reasonCode().equals("SEMANTIC_MAPPING_UNPROVEN")));
                var replayed=finished(jobs,jobs.submit("restarted-context","restart-frozen-inputs").job().id());
                assertEquals(AnalysisJobs.State.PARTIAL,replayed.state());
                assertEquals(graph.identity(),replayed.result().orElseThrow().graph().orElseThrow());
                assertEquals(committed.document(),reopened.latest().orElseThrow().document(),"A new job UUID cannot create a different revision for identical captured inputs");
                var rootView=finished(jobs,jobs.submit("root-coverage","all-selected-contexts").job().id());
                assertEquals(AnalysisJobs.State.PARTIAL,rootView.state());
                var rootGraph=graphs.load(rootView.result().orElseThrow().graph().orElseThrow()).orElseThrow();
                assertEquals(prepared.base().snapshot().identity(),rootGraph.snapshot());
                assertEquals(1,rootGraph.nodes().stream().filter(n->n.kind().equals("JAVA_CONTEXT_RESULT")).count());
                assertTrue(rootGraph.nodes().stream().noneMatch(n->n.kind().equals("JAVA_TYPE")),"The root indexes the exact child graph without flattening its snapshot");
                assertTrue(rootGraph.evidence().stream().anyMatch(e->e.family().equals("java.evidence.repository-coverage")&&e.canonicalJson().contains(graph.identity().value())));
                assertEquals(graph.identity(),graphs.load(graph.identity()).orElseThrow().identity());
                var summary=rootGraph.evidence().stream().filter(e->e.family().equals("java.evidence.repository-coverage")).findFirst().orElseThrow();
                var counts=GraphJson.object(GraphJson.object(GraphJson.read(summary.canonicalJson(),GraphJson.Limits.defaults())).get("counts"));
                assertEquals(new java.math.BigDecimal(historical.size()),((java.math.BigDecimal)counts.get("rootCauseBefore")).subtract((java.math.BigDecimal)counts.get("rootCauseAfter")));
                var changed=finished(jobs,jobs.submit("changed-work-policy","new-bounded-policy").job().id());
                assertEquals(AnalysisJobs.State.PARTIAL,changed.state());
                var next=reopened.latest().orElseThrow();assertNotEquals(committed.artifacts().get("input-footprint"),next.artifacts().get("input-footprint"));
                assertEquals(Optional.of(committed.document().digest()),next.parent());
                try(var input=publicationContent.open(next.artifacts().get("input-footprint"))){
                    var footprint=GraphJson.object(GraphJson.read(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8),GraphJson.Limits.defaults()));
                    assertEquals(new java.math.BigDecimal(changedPolicy.maxTypes()),GraphJson.object(footprint.get("work")).get("maxTypes"));
                }
            }
        }
    }
    private static Object value(Object result,String name)throws Exception{var method=result.getClass().getDeclaredMethod(name);method.setAccessible(true);return method.invoke(result);}
    private static AnalysisJobs.View finished(AnalysisJobs jobs,String id)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(180);
        while(System.nanoTime()<until){var result=jobs.get(UUID.fromString(id));if(AnalysisJobs.terminal(result.state()))return result;Thread.sleep(10);}
        throw new AssertionError("Captured-context job did not finish within the registered test budget");
    }
}
