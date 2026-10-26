package com.evolution.analysis.javaevidence;

import com.evolution.analysis.contract.common.ContentDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JavaEvidenceGapClosureTest {
    @Test void aPendingRootRequirementRemainsVisibleEvenWithoutAnyHistoricalGap()throws Exception{
        var fixture=new JavaEvidenceTestStore.AuthorityFixture();var scope=JavaEvidenceLedgerTest.scope();
        var requirement=JavaEvidenceLedgerTest.requirement("undiscovered output partition",List.of());
        var publication=new JavaEvidenceLedger().publish(scope,fixture.store.put("root census"),fixture.verifier.policyIdentity(),List.of(requirement),
                List.of(),List.of(),Optional.empty(),Map.of(),Set.of(),Set.of(),new JavaEvidenceTables(fixture.store,100_000),()->false);
        var snapshot=new com.evolution.analysis.contract.identity.SnapshotIdentity(scope.get("baseSnapshot").toString());
        var view=com.evolution.analysis.graph.GraphEvidence.capture("java.evidence.view",Map.of("scope",scope,"policy",fixture.verifier.policyIdentity()));
        var graph=new JavaEvidenceGraph(snapshot,snapshot,List.of(view)).withGapClosure(new JavaEvidenceGapClosure(publication,List.of()));
        assertFalse(graph.activeGaps(List.of()).isEmpty(),"An active requirement cannot vanish because no provider emitted a gap row");
    }
    @Test void unsignedGapReferencesCannotTurnAnUnrelatedClosureIntoProofOfResolution()throws Exception{
        var fixture=new JavaEvidenceTestStore.AuthorityFixture();
        var gap=JavaEvidenceGaps.create(JavaEvidenceLedgerTest.scope(),JavaEvidenceGaps.Reason.GENERATOR_INPUT_MISSING,
                new ContentDigest(JavaEvidenceCodecTest.D),ContentDigest.sha256Utf8("unrelated generator input"));
        var fields=new LinkedHashMap<>(JavaEvidenceLedgerTest.requirement("unrelated artifact",List.of()).fields());fields.put("gapRefs",List.of(gap.gapIdentity()));
        var requirement=new JavaEvidenceCodec().create(fields);var key=JavaEvidenceLedger.requirementKey(requirement);
        var proof=fixture.proof("java.output-bytes","unrelated artifact","some bytes",List.of());
        var publication=new JavaEvidenceLedger().publish(JavaEvidenceLedgerTest.scope(),fixture.store.put("census"),fixture.verifier.policyIdentity(),List.of(requirement),
                List.of(JavaEvidenceLedgerTest.decision(key,proof,List.of())),List.of(),Optional.empty(),Map.of(proof.identity(),proof),Set.of(),Set.of(key),new JavaEvidenceTables(fixture.store,100_000),()->false);
        assertEquals(List.of(gap),new JavaEvidenceGapClosure(publication,List.of(gap)).current(fixture.verifier.policyIdentity()).activeGaps(),
                "Audit-only gapRefs cannot authorize closing another evidence question");
    }
    @Test void historyIsInactiveOnlyWhileAllItsScopedProofsAndCensusRemainCurrent() throws Exception {
        var fixture = new JavaEvidenceTestStore.AuthorityFixture();
        var scope = JavaEvidenceLedgerTest.scope();
        var gap = JavaEvidenceGaps.create(scope, JavaEvidenceGaps.Reason.CONTENT_HASH_MISMATCH,
                new ContentDigest(JavaEvidenceCodecTest.D), ContentDigest.sha256Utf8("source"));
        var fields = new LinkedHashMap<>(JavaEvidenceLedgerTest.requirement("generated", List.of()).fields());
        fields.put("gapRefs", List.of(gap.gapIdentity().value()));
        var requirement = new JavaEvidenceCodec().create(fields);
        var key = JavaEvidenceLedger.requirementKey(requirement);
        var proof = fixture.proof("java.output-bytes", "generated", "actual captured bytes", List.of());
        var tables = new JavaEvidenceTables(fixture.store, 100_000);
        var published = new JavaEvidenceLedger().publish(scope, fixture.store.put("independent census"),
                fixture.verifier.policyIdentity(), List.of(requirement),
                List.of(JavaEvidenceLedgerTest.decision(key, proof, List.of())), List.of(), Optional.empty(),
                Map.of(proof.identity(), proof), Set.of(), Set.of(key), tables, () -> false);
        var binding=fixture.proof(JavaEvidenceGapClosure.QUESTION,gap.gapIdentity().value(),com.evolution.analysis.contract.serialization.CanonicalJson.write(JavaEvidenceGapClosure.bindingAnswer(scope,gap,List.of(requirement))),List.of());
        var closure = new JavaEvidenceGapClosure(published, List.of(gap),List.of(binding));
        var current = closure.current(fixture.verifier.policyIdentity());
        assertEquals(0, current.activeRequirements());
        assertTrue(current.closedDenominator());
        assertEquals(List.of(gap), current.historicalGaps());
        assertTrue(current.activeGaps().isEmpty());
        var contrary=fixture.proof(JavaEvidenceGapClosure.QUESTION,gap.gapIdentity().value(),"contradictory mapping",List.of());
        assertEquals(List.of(gap),new JavaEvidenceGapClosure(published,List.of(gap),List.of(binding,contrary)).current(fixture.verifier.policyIdentity()).activeGaps());
        var extraFields=new LinkedHashMap<>(JavaEvidenceLedgerTest.requirement("second generated root",List.of()).fields());
        extraFields.put("gapRefs",List.of(gap.gapIdentity().value()));var extra=new JavaEvidenceCodec().create(extraFields);var extraKey=JavaEvidenceLedger.requirementKey(extra);
        var extraProof=fixture.proof("java.output-bytes","second generated root","second captured bytes",List.of());
        var expanded=new JavaEvidenceLedger().publish(scope,fixture.store.put("expanded independent census"),fixture.verifier.policyIdentity(),List.of(requirement,extra),
                List.of(JavaEvidenceLedgerTest.decision(key,proof,List.of()),JavaEvidenceLedgerTest.decision(extraKey,extraProof,List.of())),List.of(),Optional.empty(),
                Map.of(proof.identity(),proof,extraProof.identity(),extraProof),Set.of(),Set.of(key,extraKey),tables,()->false);
        assertEquals(List.of(gap),new JavaEvidenceGapClosure(expanded,List.of(gap),List.of(binding)).current(fixture.verifier.policyIdentity()).activeGaps(),
                "A mapping proof for a smaller census cannot authorize a newly added requirement");
        assertEquals(List.of(gap),closure.current(ContentDigest.sha256Utf8("new policy")).activeGaps());
        fixture.store.blobs.remove(proof.answer());
        var reopened = closure.current(fixture.verifier.policyIdentity());
        assertEquals(1, reopened.activeRequirements());
        assertEquals(List.of(gap), reopened.activeGaps());
        assertTrue(reopened.historicalGaps().isEmpty());
        assertEquals(List.of(gap), current.historicalGaps(), "An old immutable result remains audit evidence");
    }

    @Test void anOpenCensusAndAnUnboundGapCannotDisappearBehindZeroActiveRows() throws Exception {
        var fixture = new JavaEvidenceTestStore.AuthorityFixture();
        var gap = JavaEvidenceGaps.create(JavaEvidenceLedgerTest.scope(), JavaEvidenceGaps.Reason.OUTPUT_CENSUS_OPEN,
                new ContentDigest(JavaEvidenceCodecTest.D), ContentDigest.sha256Utf8("frontier"));
        var published = new JavaEvidenceLedger().publish(JavaEvidenceLedgerTest.scope(), fixture.store.put("open census"),
                fixture.verifier.policyIdentity(), List.of(), List.of(), List.of(), Optional.empty(), Map.of(), Set.of(),
                Set.of(), new JavaEvidenceTables(fixture.store, 100_000), () -> false);
        var current = new JavaEvidenceGapClosure(published, List.of(gap)).current(fixture.verifier.policyIdentity());
        assertEquals(List.of(gap), current.activeGaps());
        assertTrue(current.historicalGaps().isEmpty());
        assertFalse(current.closedDenominator());
    }

    @Test void graphReloadKeepsHistoryInspectableWithoutQualifyingCurrentQueries() throws Exception {
        var fixture=new JavaEvidenceTestStore.AuthorityFixture();
        var repository=com.evolution.analysis.contract.identity.RepositoryIdentity.fromCanonicalCoordinate("https://example.invalid/gap-closure.git");
        var snapshot=com.evolution.analysis.contract.source.RepositorySnapshot.create(repository,Optional.empty(),false,List.of(),List.of());
        var scope=new LinkedHashMap<>(JavaEvidenceLedgerTest.scope());scope.put("baseSnapshot",snapshot.identity().value());
        var gap=JavaEvidenceGaps.create(scope,JavaEvidenceGaps.Reason.CONTENT_HASH_MISMATCH,new ContentDigest(JavaEvidenceCodecTest.D),ContentDigest.sha256Utf8("input"));
        var fields=new LinkedHashMap<>(JavaEvidenceLedgerTest.requirement("generated",List.of()).fields());fields.put("scope",scope);fields.put("gapRefs",List.of(gap.gapIdentity()));
        var requirement=new JavaEvidenceCodec().create(fields);var key=JavaEvidenceLedger.requirementKey(requirement);
        var proof=fixture.proof(scope,"java.output-bytes","generated","captured bytes",List.of());
        var publication=new JavaEvidenceLedger().publish(scope,fixture.store.put("census"),fixture.verifier.policyIdentity(),List.of(requirement),
                List.of(JavaEvidenceLedgerTest.decision(key,proof,List.of())),List.of(),Optional.empty(),Map.of(proof.identity(),proof),Set.of(),Set.of(key),new JavaEvidenceTables(fixture.store,100_000),()->false);
        var view=com.evolution.analysis.graph.GraphEvidence.capture("java.evidence.view",Map.of("scope",scope,"policy",fixture.verifier.policyIdentity()));
        var mapping=fixture.proof(scope,JavaEvidenceGapClosure.QUESTION,gap.gapIdentity().value(),com.evolution.analysis.contract.serialization.CanonicalJson.write(JavaEvidenceGapClosure.bindingAnswer(scope,gap,List.of(requirement))),List.of());
        var binding=new JavaEvidenceGraph(snapshot.identity(),snapshot.identity(),List.of(view)).withGapClosure(new JavaEvidenceGapClosure(publication,List.of(gap),List.of(mapping)));
        var input=new com.evolution.analysis.graph.CanonicalGraphInput(new com.evolution.analysis.ingestion.RepositoryInputs(snapshot,Map.of()),Optional.empty(),Optional.empty(),List.of(),List.of(),List.of(gap),Optional.empty(),Optional.of(binding));
        var graph=new com.evolution.analysis.graph.CanonicalGraphBuilder().build(input);
        var codec=new com.evolution.analysis.graph.GraphSnapshotCodec();graph=codec.decode(codec.encode(graph),com.evolution.analysis.graph.GraphJson.Limits.defaults());
        var queries=new com.evolution.analysis.query.ArchitectureQueryService(graph);
        var request=com.evolution.analysis.query.ArchitectureQuery.Request.dependencies(graph.identity(),snapshot.identity().value());
        assertTrue(queries.gaps(request,com.evolution.analysis.query.ArchitectureQuery.Page.first()).items().isEmpty());
        var history=queries.gaps(request,com.evolution.analysis.query.ArchitectureQuery.GapView.HISTORY,com.evolution.analysis.query.ArchitectureQuery.Page.first());
        assertEquals(List.of(gap.gapIdentity().value()),history.items().stream().map(com.evolution.analysis.query.ArchitectureQuery.EntityView::identity).toList());
        assertEquals(com.evolution.analysis.contract.assessment.AssessmentStatus.COMPLETE,history.status());
        assertFalse(queries.explain(request,gap.gapIdentity().value(),com.evolution.analysis.query.ArchitectureQuery.Page.first()).items().isEmpty());
        fixture.store.blobs.remove(proof.answer());
        assertThrows(IllegalArgumentException.class,()->new com.evolution.analysis.graph.CanonicalGraphBuilder().build(input),"Stale in-memory closure cannot publish historical status");
    }
}
