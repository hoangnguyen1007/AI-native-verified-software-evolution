package com.evolution.analysis.graph;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.evidence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.graph.CanonicalGraphBuilderTest.*;

class CanonicalGraphContractTest {
    @Test void rejectsForeignManifestAndMissingEvidenceOrEndpoints() {
        var manifest=manifest();var frontend=frontend(manifest);
        var changed=AnalysisManifest.create(VERSION,manifest.snapshot(),manifest.modules(),List.of(),
                AnalysisConfiguration.create(VERSION,Map.of("changed","yes")),manifest.analyzer(),manifest.ruleSet(),manifest.graphSchema());
        assertThrows(IllegalArgumentException.class,()->new CanonicalGraphBuilder().build(changed,frontend));
        var graph=new CanonicalGraphBuilder().build(manifest,frontend);
        assertThrows(IllegalArgumentException.class,()->new KnowledgeGraph(graph.snapshot(),graph.contexts(),graph.nodes(),graph.edges(),List.of()));
        assertThrows(IllegalArgumentException.class,()->new KnowledgeGraph(graph.snapshot(),graph.contexts(),List.of(),graph.edges(),graph.evidence()));
        var node=graph.nodes().getFirst();
        var conflict=new KnowledgeGraph.Node(node.identity(),node.context(),"OTHER",node.evidence());
        var nodes=new ArrayList<>(graph.nodes());nodes.add(conflict);
        assertThrows(IllegalArgumentException.class,()->new KnowledgeGraph(graph.snapshot(),graph.contexts(),nodes,graph.edges(),graph.evidence()));
    }

    @Test void duplicateImportsAreIdempotentButConflictingPayloadsFail() {
        var graph=new CanonicalGraphBuilder().build(manifest(),frontend(manifest()));
        var nodes=new ArrayList<>(graph.nodes());nodes.addAll(graph.nodes());
        var copy=new KnowledgeGraph(graph.snapshot(),graph.contexts(),nodes,graph.edges(),graph.evidence());
        assertEquals(graph.identity(),copy.identity());
        var payload=graph.evidence().getFirst();
        assertThrows(IllegalArgumentException.class,()->new GraphEvidence(payload.identity(),payload.family(),"{}"));
    }

    @Test void candidateAndUnresolvedTargetsRetainExactStatesAndEvidence() {
        var fixture=occurrences();
        var graph=new CanonicalGraphBuilder().build(fixture.manifest(),fixture.frontend());
        var calls=graph.edges().stream().filter(e->e.kind().equals("java.calls")).toList();
        assertEquals(7,calls.size());
        assertEquals(1,calls.stream().filter(e->e.target().state()==KnowledgeGraph.TargetState.CANDIDATES).count());
        assertEquals(5,calls.stream().filter(e->e.target().state()==KnowledgeGraph.TargetState.UNRESOLVED).count());
        var resolved=calls.stream().filter(e->e.target().state()==KnowledgeGraph.TargetState.RESOLVED).findFirst().orElseThrow();
        assertEquals(2,resolved.evidence().size());
        for(var payload:resolved.evidence())assertTrue(graph.evidence(payload).orElseThrow().canonicalJson().contains("endColumn"));
        assertEquals(8,graph.nodes().stream().filter(n->n.kind().equals("OCCURRENCE")).count());
        assertEquals(9,graph.nodes().stream().filter(n->n.kind().equals("OBSERVATION")).count());
        assertTrue(graph.evidence().stream().anyMatch(e->e.canonicalJson().contains("unmapped")));
        assertEquals(7,graph.outgoing(fixture.manifest().identity().value(),fixture.source().identity().value()).stream().filter(e->e.kind().equals("java.calls")).count());
    }

    @Test void canonicalRoundTripPreservesFullEvidenceAndIndexesWithoutCache() {
        var fixture=occurrences();var graph=new CanonicalGraphBuilder().build(fixture.manifest(),fixture.frontend());
        var codec=new GraphSnapshotCodec();var encoded=codec.encode(graph);
        var restored=new GraphSnapshotCodec().decode(encoded,GraphJson.Limits.defaults());
        assertNotSame(graph,restored);assertEquals(graph.identity(),restored.identity());
        assertEquals(graph.nodes(),restored.nodes());assertEquals(graph.edges(),restored.edges());assertEquals(graph.evidence(),restored.evidence());
        assertEquals(encoded,codec.encode(restored));
        assertEquals(graph.outgoing(fixture.manifest().identity().value(),fixture.source().identity().value()),
                restored.outgoing(fixture.manifest().identity().value(),fixture.source().identity().value()));
        assertThrows(IllegalArgumentException.class,()->codec.decode(encoded.replace("knowledge-graph:m5.1-v1","knowledge-graph:unknown"),GraphJson.Limits.defaults()));
        assertThrows(IllegalArgumentException.class,()->codec.decode(encoded.substring(0,encoded.length()-1),GraphJson.Limits.defaults()));
        assertThrows(IllegalArgumentException.class,()->codec.decode(encoded,new GraphJson.Limits(10,10,10)));
    }

    @Test void strictJsonRejectsDuplicateKeysUnsafeNumbersAndMalformedText() {
        for(String json:List.of("{\"x\":1,\"x\":2}","01","[1,]","1e99999999","\"\\uD800\"","{} trailing"))
            assertThrows(IllegalArgumentException.class,()->GraphJson.read(json,GraphJson.Limits.defaults()),json);
        assertThrows(IllegalArgumentException.class,()->GraphJson.read("[[[0]]]",new GraphJson.Limits(100,1,100)));
        assertThrows(IllegalArgumentException.class,()->GraphJson.read("[0,0,0]",new GraphJson.Limits(100,10,2)));
    }

    @Test void orderingLocaleAndEvidenceChangesHaveCorrectDigestBehavior() {
        var fixture=occurrences();var graph=new CanonicalGraphBuilder().build(fixture.manifest(),fixture.frontend());
        var nodes=new ArrayList<>(graph.nodes());var edges=new ArrayList<>(graph.edges());var evidence=new ArrayList<>(graph.evidence());
        Collections.reverse(nodes);Collections.reverse(edges);Collections.reverse(evidence);
        var locale=Locale.getDefault();var zone=TimeZone.getDefault();
        try {Locale.setDefault(Locale.forLanguageTag("tr-TR"));TimeZone.setDefault(TimeZone.getTimeZone("Asia/Bangkok"));
            assertEquals(graph.identity(),new KnowledgeGraph(graph.snapshot(),graph.contexts(),nodes,edges,evidence).identity());
        } finally {Locale.setDefault(locale);TimeZone.setDefault(zone);}
        evidence.add(GraphEvidence.capture("new.provenance",Map.of("reason","additional observation")));
        assertNotEquals(graph.identity(),new KnowledgeGraph(graph.snapshot(),graph.contexts(),nodes,edges,evidence).identity());
    }
    @Test void contentAddressPreservesTelemetryWhileSemanticReplayExcludesOnlyKnownTimingFields() {
        var f=occurrences();var graph=new CanonicalGraphBuilder().build(f.manifest(),f.frontend());
        var a=GraphEvidence.capture("evidence.attempt",Map.of("startedAt","2026-10-14T00:00:00Z","completedAt","2026-10-14T00:00:01Z","outcome","DENIED"));
        var b=GraphEvidence.capture("evidence.attempt",Map.of("startedAt","2026-10-14T01:00:00Z","completedAt","2026-10-14T01:00:01Z","outcome","DENIED"));
        var first=new ArrayList<>(graph.evidence());first.add(a);var second=new ArrayList<>(graph.evidence());second.add(b);
        var one=new KnowledgeGraph(graph.snapshot(),graph.contexts(),graph.nodes(),graph.edges(),first);
        var two=new KnowledgeGraph(graph.snapshot(),graph.contexts(),graph.nodes(),graph.edges(),second);
        assertNotEquals(one.identity(),two.identity());assertEquals(one.semanticIdentity(),two.semanticIdentity());
        assertNotEquals(GraphEvidence.capture("source.configuration",Map.of("startedAt","one")).semanticIdentity(),
                GraphEvidence.capture("source.configuration",Map.of("startedAt","two")).semanticIdentity());
    }
    @Test void retainsTypedAttemptsConflictsAndRejectsForeignLedger() {
        var fixture=occurrences();var context=EvidenceContext.forAnalysis(fixture.manifest());
        var left=ProviderObservationReference.create(VERSION,"fixture.observation",ContentDigest.sha256Utf8("left-result"),ContentDigest.sha256Utf8("left"));
        var right=ProviderObservationReference.create(VERSION,"fixture.observation",ContentDigest.sha256Utf8("right-result"),ContentDigest.sha256Utf8("right"));
        var subject=new EvidenceSubject(EvidenceSubject.Kind.ARTIFACT,"g:a:1");
        var requirement=new EvidenceRequirement(EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT,"graph.acquire-artifact",List.of(subject),EvidenceRequirement.AuthorizationClass.LOCAL_READ,List.of("Missing artifact"));
        var attempt=AcquisitionAttemptRecord.create(context,VERSION,left,subject,requirement,List.of(ContentDigest.sha256Utf8("request")),Optional.empty(),
                AcquisitionAttemptRecord.TrustDecision.UNTRUSTED_INPUT,AcquisitionAttemptRecord.PermissionDecision.DENIED,Map.of("maxBytes",1024L),
                Optional.empty(),Optional.empty(),AcquisitionAttemptRecord.Outcome.DENIED,List.of(),List.of(),List.of("Permission denied"));
        var conflict=ProviderConflictRecord.create(context,left,right,List.of("semantic.target"),ProviderConflictRecord.AdjudicationStatus.UNRESOLVED,Optional.empty(),
                List.of(new AffectedOutput(AffectedOutput.Kind.RELATIONSHIP_CATEGORY,"java.calls")),List.of(),List.of("Conflict remains unresolved"));
        var ledger=EvidenceAcquisitionLedger.create(context,List.of(),List.of(attempt),List.of(conflict),List.of());
        var graph=new CanonicalGraphBuilder().build(fixture.manifest(),fixture.frontend(),List.of(),List.of(ledger));
        assertEquals(1,graph.nodes().stream().filter(n->n.kind().equals("ACQUISITION_ATTEMPT")).count());
        assertEquals(1,graph.nodes().stream().filter(n->n.kind().equals("PROVIDER_CONFLICT")).count());
        assertTrue(graph.evidence().stream().anyMatch(e->e.family().equals("evidence.attempt")&&e.canonicalJson().contains("DENIED")));
        var codec=new GraphSnapshotCodec();assertEquals(graph.evidence(),codec.decode(codec.encode(graph),GraphJson.Limits.defaults()).evidence());
        var foreign=EvidenceAcquisitionLedger.create(new EvidenceContext(SnapshotIdentity.from(REPO,ContentDigest.sha256Utf8("foreign")),Optional.empty()),List.of(),List.of(),List.of(),List.of());
        assertThrows(IllegalArgumentException.class,()->new CanonicalGraphBuilder().build(fixture.manifest(),fixture.frontend(),List.of(),List.of(foreign)));
        var gap=CapabilityGapRecord.create(context,VERSION,"java.calls","SEMANTIC_UNRESOLVED",subject,List.of(),List.of(left),List.of(requirement),List.of(),
                List.of(new AffectedOutput(AffectedOutput.Kind.GRAPH_PROJECTION,"graph.canonical")),List.of(attempt.attemptIdentity()),List.of(),List.of("Referenced attempt not supplied"));
        var raw=new com.evolution.analysis.ingestion.RepositoryInputs(fixture.manifest().snapshot(),Map.of());
        var missing=new CanonicalGraphBuilder().build(new CanonicalGraphInput(raw,Optional.empty(),Optional.empty(),List.of(),List.of(),List.of(gap)));
        assertTrue(missing.evidence().stream().anyMatch(e->e.family().equals("evidence.gap")&&e.canonicalJson().contains("REFERENCED_EVIDENCE_UNAVAILABLE")));
        assertTrue(missing.evidence().stream().anyMatch(e->e.family().equals("graph.unavailable-reference")&&e.canonicalJson().contains(attempt.attemptIdentity().value())));
    }

    record Fixture(AnalysisManifest manifest,FrontendResult frontend,Entity source) {}
    static Fixture occurrences() {
        var base=manifest();var module=base.modules().getFirst();
        var document=SourceDocument.create(REPO,module,"a/A.java",ContentDigest.sha256Utf8("class A {}"),SourceClassification.MAIN);
        var snapshot=RepositorySnapshot.create(REPO,Optional.empty(),false,List.of(SnapshotFile.from(document)),List.of(document));
        var manifest=AnalysisManifest.create(VERSION,snapshot,base.modules(),List.of(),base.configuration(),base.analyzer(),base.ruleSet(),base.graphSchema());
        var type=JavaSymbolName.topLevelType("p","A");
        var direct=new Derivation(DerivationKind.DIRECT,VERSION,List.of());
        var entities=new ArrayList<Entity>();
        entities.add(Entity.create(EntityOrigin.PROJECT,EntityScope.project(module.identity()),EntityKind.TYPE,type.canonicalName(),Optional.empty()));
        for(String name:List.of("run","one","two"))entities.add(Entity.create(EntityOrigin.PROJECT,EntityScope.project(module.identity()),EntityKind.METHOD,JavaSymbolName.method(type,name,List.of()).canonicalName(),Optional.empty()));
        var source=entities.get(1);var target=entities.get(2);var other=entities.get(3);
        var occurrences=new ArrayList<RelationshipOccurrence>();var observations=new ArrayList<ObservationRecord>();
        var states=List.of(SemanticStatus.RESOLVED,SemanticStatus.RESOLVED,SemanticStatus.AMBIGUOUS,SemanticStatus.UNRESOLVED,SemanticStatus.UNSUPPORTED,SemanticStatus.ERROR,SemanticStatus.PARTIAL,SemanticStatus.CONDITIONAL);
        for(int i=0;i<states.size();i++) {
            var state=states.get(i);var span=new SourceSpan(document.identity(),i+1,1,i+1,4);
            RelationshipTarget relationTarget=state==SemanticStatus.RESOLVED?new RelationshipTarget.Resolved(target.identity()):state==SemanticStatus.AMBIGUOUS?
                    new RelationshipTarget.Candidates(List.of(target.identity(),other.identity())):new RelationshipTarget.Unresolved("unresolved-"+i);
            var uncertainties=state==SemanticStatus.RESOLVED?List.<Uncertainty>of():List.of(new Uncertainty("graph.fixture.unknown","Missing fixture evidence",List.of("fixture.target-evidence")));
            var diagnostics=state==SemanticStatus.ERROR?List.of(new Diagnostic(DiagnosticSeverity.ERROR,"graph.fixture.error","Fixture error",Optional.of(span),Map.of())):List.<Diagnostic>of();
            var occurrence=RelationshipOccurrence.create(SemanticRelationship.create(source.identity(),new RelationshipKind("java.calls"),relationTarget),span,0,state,direct,uncertainties,diagnostics);
            occurrences.add(occurrence);observations.add(new ObservationRecord(document.identity(),new RelationshipKind("java.calls"),Optional.of(span),state,
                    ObservationRecord.EvidenceState.VERIFIED,ObservationRecord.EvidenceState.VERIFIED,Optional.of(occurrence.identity()),"call",diagnostics));
        }
        observations.add(new ObservationRecord(document.identity(),new RelationshipKind("java.calls"),Optional.empty(),SemanticStatus.UNSUPPORTED,
                ObservationRecord.EvidenceState.MISSING,ObservationRecord.EvidenceState.MISSING,Optional.empty(),"unmapped",
                List.of(new Diagnostic(DiagnosticSeverity.WARNING,"graph.fixture.unmapped","Unmapped fixture",Optional.empty(),Map.of()))));
        var coverage=FrontendRequest.CATEGORIES.stream().map(c->new CategoryCoverage(new RelationshipKind("java."+c),CategoryCoverage.Support.PARTIAL,c.equals("calls")?9:0,c.equals("calls")?8:0,c.equals("calls")?1:0)).toList();
        var declarations=entities.stream().map(e->new DeclarationRecord(e,"fixture",SemanticStatus.RESOLVED,direct,List.of(),List.of())).toList();
        var frontend=new FrontendResult(manifest.identity(),VERSION,FrontendResult.State.PARTIAL,declarations,occurrences,observations,
                List.of(new SourceOutcome(document.identity(),SourceOutcome.State.PROCESSED,List.of())),coverage,List.of());
        return new Fixture(manifest,frontend,source);
    }
}
