package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.filesystem.StreamingContentStore;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.polyglot.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class JavaPolyglotFrontendTest {
    @TempDir Path directory;
    JavaPolyglotFrontend.Context context(FrontendRequest input){return new JavaPolyglotFrontend.Context(CanonicalJson.sha256(input.manifest().snapshot()),"root","main","baseline",ContentDigest.sha256Utf8("unqualified-method-policy"),ContentDigest.sha256Utf8("bounded-test-worker"));}
    List<SemanticTables.Row> rows(SemanticTables tables,SemanticTables.Table table)throws Exception{var result=new ArrayList<SemanticTables.Row>();try(var reader=tables.open(table,()->false)){SemanticTables.Row row;while((row=reader.next())!=null)result.add(row);}return result;}
    @Test void architectureControlKeepsEveryCategoryAndTheProtectedJavaResult()throws Exception {
        var input=TestInputs.request("package fixture; import java.util.List; class Base {} @Deprecated class Derived extends Base { int counter; int twice(int n){return n*2;} int run(){counter+=1; if(counter>0) return twice(4); return 0;} }");var legacy=new JavaParserFrontend().analyze(input);var store=new StreamingContentStore(directory.resolve("architecture-cas"));var tables=new SemanticTables(store,65536);var bridge=new JavaPolyglotFrontend(store,tables);var prepared=bridge.prepare(input,context(input),()->false);var projected=bridge.project(prepared,legacy,()->false);assertEquals(CanonicalJson.write(legacy),CanonicalJson.write(projected.legacy()));var questions=rows(tables,prepared.request().census());var output=rows(tables,projected.observations());assertEquals(questions.size(),output.size());
        for(String category:LanguageProfile.requiredCategories()){
            var sites=questions.stream().filter(q->category.equals(q.fields().get("category"))).toList();assertFalse(sites.isEmpty(),category);assertTrue(sites.stream().anyMatch(q->!Set.of("syntax-absent","implicit-generation-census").contains(q.fields().get("role"))),category+": written architecture syntax cannot become absent");for(var site:sites){var observation=output.stream().filter(o->o.key().equals(site.key())).findFirst().orElseThrow();assertEquals(site.identity().value(),observation.fields().get("obligation"));assertNotEquals("ERROR",observation.fields().get("status"),category);}
        }
        assertTrue(output.stream().anyMatch(o->"implicit-generation-census".equals(o.fields().get("role"))&&"UNSUPPORTED".equals(o.fields().get("status"))));assertTrue(output.stream().anyMatch(o->"calls".equals(o.fields().get("role"))&&"RESOLVED".equals(o.fields().get("status"))));assertFalse(new SemanticReconciler().reconcile(prepared.request(),projected.observations(),tables,store,Map.of(),Set.of(),prepared.request()::policy,g->{},()->false).complete());
    }
    @Test void rawBomCoordinatesAreMappedWithoutChangingLegacyCoordinates()throws Exception{
        var baseline=TestInputs.request("class C {}");byte[] raw="\ufeffclass C {}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var document=com.evolution.analysis.contract.source.SourceDocument.create(TestInputs.REPO,TestInputs.MODULE,"fixture/C.java",ContentDigest.sha256(raw),com.evolution.analysis.contract.source.SourceClassification.MAIN);
        var decoding=new SourceInput.Decoding("source-decoding-v1","UTF-8",SourceInput.EncodingOrigin.ANALYSIS_POLICY,List.of(),SourceInput.Bom.UTF8,SourceInput.LineEndings.from("class C {}"));
        var input=TestInputs.request(List.of(new SourceInput(document,raw,decoding)),baseline.platform(),List.of());var legacy=new JavaParserFrontend().analyze(input);
        var store=new StreamingContentStore(directory.resolve("cas"));var tables=new SemanticTables(store,65536);var bridge=new JavaPolyglotFrontend(store,tables);var result=bridge.project(bridge.prepare(input,context(input),()->false),legacy,()->false);
        var declaration=rows(tables,result.observations()).stream().filter(row->row.fields().get("role").equals("declares")).findFirst().orElseThrow();var span=GraphJson.object(declaration.fields().get("span"));assertEquals(new java.math.BigDecimal(2),span.get("startColumn"));assertEquals(1,legacy.declarations().stream().filter(d->d.entity().kind()==com.evolution.analysis.contract.semantic.EntityKind.TYPE&&d.entity().declaration().isPresent()).findFirst().orElseThrow().entity().declaration().orElseThrow().startColumn());
    }
    @Test void bridgePreservesTheExactLegacyResultAndSeparatelyCensusesCallsAndReferences()throws Exception{
        var source="class C { int f; int twice(int value){return value*2;} void run(){ f=twice(4); Runnable r=this::run; } }";
        var input=TestInputs.request(source);var legacy=new JavaParserFrontend().analyze(input);String before=CanonicalJson.write(legacy);
        var store=new StreamingContentStore(directory.resolve("cas"));var tables=new SemanticTables(store,65536);var bridge=new JavaPolyglotFrontend(store,tables);
        var prepared=bridge.prepare(input,context(input),()->false);var result=bridge.project(prepared,legacy,()->false);
        assertEquals(before,CanonicalJson.write(result.legacy()));assertEquals(ContentDigest.sha256Utf8(before),result.legacyContent().digest());assertTrue(store.verify(result.legacyContent()));
        var output=rows(tables,result.observations());var calls=output.stream().filter(row->"calls".equals(row.fields().get("role"))).toList();var references=output.stream().filter(row->"method-references".equals(row.fields().get("role"))).toList();
        assertEquals(1,calls.size());assertEquals("RESOLVED",calls.getFirst().fields().get("status"));assertEquals(1,references.size());assertEquals("method-references",references.getFirst().fields().get("role"));
        var originalCalls=legacy.occurrences().stream().filter(o->o.relationship().kind().value().equals("java.calls")).map(o->o.identity().value()).toList();assertEquals(originalCalls,calls.getFirst().fields().get("legacyOccurrences"));
        var categories=rows(tables,prepared.request().census()).stream().map(row->row.fields().get("category")).collect(java.util.stream.Collectors.toSet());assertEquals(new HashSet<>(LanguageProfile.requiredCategories()),categories);
        var gaps=new ArrayList<CapabilityGapRecord>();var coverage=new SemanticReconciler().reconcile(prepared.request(),result.observations(),tables,store,Map.of(),Set.of(),prepared.request()::policy,gaps::add,()->false);assertEquals(0,coverage.omitted());assertFalse(coverage.complete());
    }
    @Test void anInternallyConsistentLegacyResultWithADroppedCallCannotShrinkTheIndependentCensus()throws Exception{
        var input=TestInputs.request("class C { void target(){} void run(){target();} }");var legacy=new JavaParserFrontend().analyze(input);
        var occurrences=legacy.occurrences().stream().filter(o->!o.relationship().kind().value().equals("java.calls")).toList();var observations=legacy.observations().stream().filter(o->!o.category().value().equals("java.calls")).toList();
        var coverage=legacy.coverage().stream().map(c->c.category().value().equals("java.calls")?new CategoryCoverage(c.category(),c.support(),0,0,0):c).toList();
        var dropped=new FrontendResult(legacy.analysis(),legacy.frontend(),legacy.state(),legacy.declarations(),occurrences,observations,legacy.sources(),coverage,legacy.diagnostics(),legacy.types(),legacy.annotations(),legacy.derivedRelationships(),legacy.typeDeclarations(),legacy.memberDeclarations());
        var store=new StreamingContentStore(directory.resolve("cas"));var tables=new SemanticTables(store,65536);var bridge=new JavaPolyglotFrontend(store,tables);var prepared=bridge.prepare(input,context(input),()->false);var result=bridge.project(prepared,dropped,()->false);
        var gaps=new ArrayList<CapabilityGapRecord>();var view=new SemanticReconciler().reconcile(prepared.request(),result.observations(),tables,store,Map.of(),Set.of(),prepared.request()::policy,gaps::add,()->false);assertEquals(1,view.omitted());assertTrue(gaps.stream().anyMatch(g->g.reasonCode().equals("SEMANTIC_OCCURRENCE_OMITTED")));
    }
    @Test void brokenInputRetainsOpaqueCategoryScopesAndForeignLegacyResultsAreRejected()throws Exception{
        var input=TestInputs.request("class C { void run( {");var store=new StreamingContentStore(directory.resolve("cas"));var tables=new SemanticTables(store,65536);var bridge=new JavaPolyglotFrontend(store,tables);var prepared=bridge.prepare(input,context(input),()->false);
        var legacy=new JavaParserFrontend().analyze(input);var result=bridge.project(prepared,legacy,()->false);var output=rows(tables,result.observations());assertTrue(output.stream().anyMatch(row->"ERROR".equals(row.fields().get("status"))));assertEquals(12,prepared.request().census().rows());
        var other=new JavaParserFrontend().analyze(TestInputs.request("class Other {}"));assertThrows(IllegalArgumentException.class,()->bridge.project(prepared,other,()->false));
    }
    @Test void absentWrittenAnnotationsDoNotCertifyAbsenceOfImplicitGeneration()throws Exception{
        var input=TestInputs.request("class C {}");var legacy=new JavaParserFrontend().analyze(input);String original=CanonicalJson.write(legacy);var store=new StreamingContentStore(directory.resolve("cas"));var tables=new SemanticTables(store,65536);var bridge=new JavaPolyglotFrontend(store,tables);var result=bridge.project(bridge.prepare(input,context(input),()->false),legacy,()->false);var obligations=rows(tables,result.request().census()).stream().filter(row->row.fields().get("category").equals("A08")).map(SemanticTables.Row::key).collect(java.util.stream.Collectors.toSet());var generated=rows(tables,result.observations()).stream().filter(row->obligations.contains(row.key())).findFirst().orElseThrow();assertEquals("UNSUPPORTED",generated.fields().get("status"));assertEquals("JAVA_IMPLICIT_GENERATION_CENSUS_REQUIRED",generated.fields().get("reason"));assertEquals(original,CanonicalJson.write(result.legacy()));
    }
}
