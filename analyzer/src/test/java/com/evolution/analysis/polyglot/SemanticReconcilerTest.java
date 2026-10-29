package com.evolution.analysis.polyglot;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.evidence.*;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SemanticReconcilerTest {
    @Test void partialAndConditionalBindingsRemainUncertainRatherThanBecomingResolved()throws Exception{
        var fixture=new Fixture();var rows=new ArrayList<SemanticTables.Row>();
        for(var requirement:fixture.requirements){String status=requirement.key().equals("A06")?"PARTIAL":requirement.key().equals("A05")?"CONDITIONAL":Set.of("A01","A11","A12").contains(requirement.key())?"RESOLVED":"NOT_APPLICABLE";
            rows.add(new SemanticTables.Row(SemanticTables.Kind.OBSERVATION,requirement.key(),requirement.scope(),Map.of("obligation",requirement.identity().value(),"status",status,"targets",status.equals("PARTIAL")||status.equals("CONDITIONAL")?List.of(ContentDigest.sha256Utf8("possible target").value()):List.of(),"uncertainty",List.of("native result retains incomplete or conditional attribution"))));}
        var output=fixture.tables.write(SemanticTables.Kind.OBSERVATION,rows.iterator(),()->false);var gaps=new ArrayList<CapabilityGapRecord>();var view=fixture.reconcile(output,gaps);
        assertFalse(view.complete());assertTrue(gaps.stream().anyMatch(g->g.reasonCode().equals("SEMANTIC_PARTIAL")));assertTrue(gaps.stream().anyMatch(g->g.reasonCode().equals("SEMANTIC_CONDITIONAL")));assertEquals(3,view.resolved());
    }
    @Test void aResolvedCallCannotHideMultipleCandidateTargets()throws Exception{
        var fixture=new Fixture();var expected=fixture.requirements.stream().filter(row->row.key().equals("A06")).findFirst().orElseThrow();
        var bad=new SemanticTables.Row(SemanticTables.Kind.OBSERVATION,"A06",fixture.request.scope(),Map.of("obligation",expected.identity().value(),"status","RESOLVED","targets",List.of(ContentDigest.sha256Utf8("first").value(),ContentDigest.sha256Utf8("second").value())));
        var output=fixture.tables.write(SemanticTables.Kind.OBSERVATION,List.of(bad).iterator(),()->false);
        assertThrows(IOException.class,()->fixture.reconcile(output,new ArrayList<>()));
    }
    @Test void degradedNativeReasonsKeepTheirExactProviderTableAndObservationProvenance()throws Exception{
        var fixture=new Fixture();var rows=new ArrayList<SemanticTables.Row>();SemanticTables.Row degraded=null;
        for(var expected:fixture.requirements){var fields=new TreeMap<String,Object>();fields.put("obligation",expected.identity().value());fields.put("targets",List.of());fields.put("status",expected.key().equals("A06")?"UNRESOLVED":Set.of("A01","A11","A12").contains(expected.key())?"RESOLVED":"NOT_APPLICABLE");if(expected.key().equals("A06"))fields.put("reason","NATIVE_OVERLOAD_EVIDENCE_UNAVAILABLE");var row=new SemanticTables.Row(SemanticTables.Kind.OBSERVATION,expected.key(),expected.scope(),fields);rows.add(row);if(expected.key().equals("A06"))degraded=row;}
        var output=fixture.tables.write(SemanticTables.Kind.OBSERVATION,rows.iterator(),()->false);var gaps=new ArrayList<CapabilityGapRecord>();fixture.reconcile(output,gaps);var gap=gaps.stream().filter(g->g.reasonCode().equals("SEMANTIC_UNRESOLVED")).findFirst().orElseThrow();var observation=degraded;
        assertTrue(gap.observationReferences().stream().anyMatch(ref->ref.provider().equals(SemanticReconciler.PROVIDER)&&ref.sourceResultIdentity().equals(output.content().digest())&&ref.payloadDigest().equals(observation.identity())));assertEquals("NATIVE_OVERLOAD_EVIDENCE_UNAVAILABLE",gap.diagnostics().getFirst().details().get("nativeReason"));assertEquals(fixture.request.provider().id(),gap.diagnostics().getFirst().details().get("nativeProviderId"));assertEquals(fixture.request.provider().version(),gap.diagnostics().getFirst().details().get("nativeProviderVersion"));
    }
    @Test void identicalSourceBytesInDifferentRepositoriesCannotShareProofInputClosure()throws Exception{
        var fixture=new Fixture();var r=fixture.request;var otherContext=EvidenceContext.forSnapshot(SnapshotIdentity.from(RepositoryIdentity.fromCanonicalCoordinate("urn:test:other-polyglot-repository"),r.inventory()));var other=new LanguageSemanticRequest(otherContext,r.inventory(),r.root(),r.module(),r.sourceSet(),r.variant(),r.language(),r.dialect(),r.documents(),r.census(),r.resolution(),r.provider(),r.tool(),r.policy(),r.workPolicy());assertEquals(r.scope(),other.scope(),"Transport locators remain relative to a snapshot");assertNotEquals(r.identity(),other.identity());assertNotEquals(r.inputClosure(),other.inputClosure());
    }
    @Test void anExitZeroEmptyIndexerCannotEraseAClosedIndependentDenominator()throws Exception{
        var fixture=new Fixture();var empty=fixture.tables.write(SemanticTables.Kind.OBSERVATION,Collections.emptyIterator(),()->false);
        var gaps=new ArrayList<CapabilityGapRecord>();var view=fixture.reconcile(empty,gaps);
        assertEquals(12,view.total());assertEquals(12,view.omitted());assertFalse(view.complete());
        assertEquals(12,gaps.stream().filter(g->g.reasonCode().equals("SEMANTIC_OCCURRENCE_OMITTED")).count());
    }
    @Test void allAnsweredRowsRemainProducerAssertionsUntilIndependentMethodsQualify()throws Exception{
        var fixture=new Fixture();var observations=fixture.emissions(fixture.requirements);
        var gaps=new ArrayList<CapabilityGapRecord>();var result=fixture.reconcile(observations,gaps);
        assertEquals(9,result.notApplicable());assertEquals(3,result.resolved());assertEquals(0,result.omitted());assertFalse(result.complete());
        assertEquals(Set.of("CENSUS_METHOD_UNQUALIFIED","SEMANTIC_METHOD_UNQUALIFIED"),new HashSet<>(gaps.stream().map(CapabilityGapRecord::reasonCode).toList()));
        assertEquals(result.semanticIdentity(),fixture.reconcile(observations,new ArrayList<>()).semanticIdentity());
    }
    @Test void droppedDocumentOrCategoryKeepsTheCensusOpenEvenWhenNoRowsAreMissing()throws Exception{
        var fixture=new Fixture();var rows=fixture.requirements.subList(0,11);fixture.useCensus(rows);
        var gaps=new ArrayList<CapabilityGapRecord>();var result=fixture.reconcile(fixture.emissions(rows),gaps);
        assertFalse(result.complete());assertTrue(gaps.stream().anyMatch(g->g.reasonCode().equals("SEMANTIC_DOCUMENT_OR_CATEGORY_CENSUS_OPEN")));
    }
    @Test void extraEmissionsWrongBindingsAndForeignContextsAreRejected()throws Exception{
        var fixture=new Fixture();var extra=new SemanticTables.Row(SemanticTables.Kind.OBSERVATION,"z-extra",fixture.request.scope(),Map.of("status","NOT_APPLICABLE","targets",List.of(),"obligation","missing"));
        var output=fixture.tables.write(SemanticTables.Kind.OBSERVATION,List.of(extra).iterator(),()->false);
        assertThrows(IOException.class,()->fixture.reconcile(output,new ArrayList<>()));
        var bad=new SemanticTables.Row(SemanticTables.Kind.OBSERVATION,"A01",fixture.request.scope(),Map.of("status","NOT_APPLICABLE","targets",List.of(),"obligation",ContentDigest.sha256Utf8("wrong").value()));
        var wrong=fixture.tables.write(SemanticTables.Kind.OBSERVATION,List.of(bad).iterator(),()->false);
        assertThrows(IOException.class,()->fixture.reconcile(wrong,new ArrayList<>()));
        assertThrows(IOException.class,()->new SemanticReconciler().reconcile(fixture.request,output,fixture.tables,fixture.content,Map.of(),Set.of(),()->ContentDigest.sha256Utf8("revoked"),gap->{},()->false));
    }
    @Test void requiredPacksAndLogicalSymbolsDoNotClaimQualificationFromRegistryPresence(){
        assertThrows(IllegalArgumentException.class,()->new LanguageProfile("python","pyright",LanguageProfile.requiredCategories(),"LEGACY_JAVA_CONTRACT"));
        assertEquals(18,LanguageProfile.baseline().size());assertEquals(12,LanguageProfile.requiredCategories().size());
        assertEquals(17,LanguageProfile.baseline().stream().filter(p->p.qualification().equals("REQUIRED_UNQUALIFIED")).count());
        assertEquals(LanguageProfile.symbol("typescript","repo","pkg","Service","find(string)"),LanguageProfile.symbol("typescript","repo","pkg","Service","find(string)"));
        assertNotEquals(LanguageProfile.symbol("typescript","repo","pkg","Service","find(string)"),LanguageProfile.symbol("typescript","repo","other","Service","find(string)"));
    }
    static final class Fixture {
        final SemanticTablesTest.Memory content=new SemanticTablesTest.Memory();final SemanticTables tables=new SemanticTables(content,8192);
        LanguageSemanticRequest request;List<SemanticTables.Row> requirements;
        Fixture()throws Exception{
            var inventory=ContentDigest.sha256Utf8("inventory");var context=EvidenceContext.forSnapshot(SnapshotIdentity.from(RepositoryIdentity.fromCanonicalCoordinate("urn:test:polyglot"),inventory));
            var empty=tables.write(SemanticTables.Kind.DOCUMENT,Collections.emptyIterator(),()->false);var census=tables.write(SemanticTables.Kind.CENSUS,Collections.emptyIterator(),()->false);
            request=new LanguageSemanticRequest(context,inventory,"root","module","integrationTest","development","typescript","5.9.3",empty,census,content.text("[]"),new VersionedIdentifier("typescript.checker","5.9.3"),ContentDigest.sha256Utf8("tool"),ContentDigest.sha256Utf8("policy"),ContentDigest.sha256Utf8("work"));
            var document=new SemanticTables.Row(SemanticTables.Kind.DOCUMENT,"app.ts",request.scope(),Map.of("content",content.text("export const a=1;")));
            var documents=tables.write(SemanticTables.Kind.DOCUMENT,List.of(document).iterator(),()->false);
            requirements=LanguageProfile.requiredCategories().stream().map(category->new SemanticTables.Row(SemanticTables.Kind.CENSUS,category,request.scope(),Map.of("document","app.ts","category",category))).toList();
            request=with(documents,tables.write(SemanticTables.Kind.CENSUS,requirements.iterator(),()->false));
        }
        LanguageSemanticRequest with(SemanticTables.Table documents,SemanticTables.Table census){return new LanguageSemanticRequest(request.context(),request.inventory(),request.root(),request.module(),request.sourceSet(),request.variant(),request.language(),request.dialect(),documents,census,request.resolution(),request.provider(),request.tool(),request.policy(),request.workPolicy());}
        void useCensus(List<SemanticTables.Row> rows)throws IOException{request=with(request.documents(),tables.write(SemanticTables.Kind.CENSUS,rows.iterator(),()->false));}
        SemanticTables.Table emissions(List<SemanticTables.Row> rows)throws IOException{return tables.write(SemanticTables.Kind.OBSERVATION,rows.stream().map(row->new SemanticTables.Row(SemanticTables.Kind.OBSERVATION,row.key(),row.scope(),Map.of("obligation",row.identity().value(),"status",Set.of("A01","A11","A12").contains(row.key())?"RESOLVED":"NOT_APPLICABLE","targets",List.of()))).iterator(),()->false);}
        SemanticReconciler.Coverage reconcile(SemanticTables.Table output,List<CapabilityGapRecord> gaps)throws IOException{return new SemanticReconciler().reconcile(request,output,tables,content,Map.of(),Set.of(),request::policy,gaps::add,()->false);}
    }
}
