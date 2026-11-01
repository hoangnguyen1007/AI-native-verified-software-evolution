package com.evolution.analysis.polyglot;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArchitectureCaptureTest {
    static final class Fixture {
        final SemanticTablesTest.Memory memory=new SemanticTablesTest.Memory();final SemanticTables tables=new SemanticTables(memory,100_000);
        final Map<String,Object> input,report;final ArchitectureCapture.Request request;final List<CapabilityGapRecord> gaps=new ArrayList<>();
        Fixture()throws Exception {this("architecture-native-v1");}
        Fixture(String revision)throws Exception {
            input=resource(revision,"input.json");report=resource(revision,"capture.json");var scope=new ContentDigest(GraphJson.string(input.get("scope")));var documents=new ArrayList<SemanticTables.Row>();
            for(Object value:GraphJson.array(input.get("files"))){var file=GraphJson.object(value);String path=GraphJson.string(file.get("path"));var source=memory.text(GraphJson.string(file.get("text")));
                documents.add(new SemanticTables.Row(SemanticTables.Kind.DOCUMENT,path,scope,Map.of("content",source,"role",file.get("role"),"encoding","UTF-8")));}
            documents.sort(Comparator.comparing(SemanticTables.Row::key));var docs=tables.write(SemanticTables.Kind.DOCUMENT,documents.iterator(),()->false);
            var inventory=ContentDigest.sha256Utf8("independent authored inventory");var context=EvidenceContext.forSnapshot(SnapshotIdentity.from(RepositoryIdentity.fromCanonicalCoordinate("urn:fixture:architecture"),inventory));
            request=new ArchitectureCapture.Request(context,scope,GraphJson.string(input.get("repository")),GraphJson.string(input.get("app")),GraphJson.object(input.get("context")),GraphJson.array(input.get("profiles")).stream().map(GraphJson::object).toList(),docs,new ContentDigest(GraphJson.string(report.get("tool"))));
        }
        ArchitectureCapture.Result read(Map<String,Object> value)throws Exception{return ArchitectureCapture.read(request,memory.text(CanonicalJson.write(value)),tables,memory,1_000_000,gaps::add,()->false);}
        Map<String,Object> edit(){return GraphJson.object(GraphJson.read(CanonicalJson.write(report),new GraphJson.Limits(1_000_000,64,1_000_000)));}
        void rehash(Map<String,Object> value){value.remove("identity");value.put("identity",CanonicalJson.sha256(value).value());}
        static Map<String,Object> resource(String revision,String name)throws Exception{try(var stream=ArchitectureCaptureTest.class.getResourceAsStream("/polyglot/"+revision+"/"+name)){return GraphJson.object(GraphJson.read(new String(stream.readAllBytes(),StandardCharsets.UTF_8),new GraphJson.Limits(1_000_000,64,1_000_000)));}}
    }
    @Test void nativeHtmlAndSchemaCaptureReplaysWithExactBytesAndTypedGaps()throws Exception {
        var f=new Fixture();var result=f.read(f.report);assertEquals(3,result.documents());assertEquals(36,result.census());assertTrue(result.factCount()>0);assertTrue(result.gapCount()>0);
        assertTrue(f.gaps.stream().anyMatch(g->g.reasonCode().equals("RESOURCE_UNAVAILABLE")));assertTrue(f.gaps.stream().anyMatch(g->g.reasonCode().equals("ARCHITECTURE_METHOD_UNQUALIFIED")));
        assertEquals(result,f.read(f.report));var facts=new ArrayList<SemanticTables.Row>();try(var reader=f.tables.open(result.facts(),()->false)){SemanticTables.Row row;while((row=reader.next())!=null)facts.add(row);}
        var link=facts.stream().map(SemanticTables.Row::fields).filter(row->"RESOURCE_REFERENCE".equals(row.get("kind"))&&"logo.svg".equals(row.get("target"))).findFirst().orElseThrow();
        var span=GraphJson.object(link.get("span"));assertEquals(new java.math.BigDecimal(2),span.get("startLine"));
    }
    @Test void evenRehashedOmittedDocumentsCategoriesAndDispositionsAreRejected()throws Exception {
        var f=new Fixture();for(String field:List.of("documents","census","dispositions")){var edited=f.edit();GraphJson.array(edited.get(field)).remove(0);f.rehash(edited);assertThrows(IOException.class,()->f.read(edited),field);}
    }
    @Test void forgedTrustContextCoordinatesAndMissingSourceCannotBecomeAuthority()throws Exception {
        var f=new Fixture();var trust=f.edit();trust.put("qualified",true);f.rehash(trust);assertThrows(IOException.class,()->f.read(trust));
        var foreign=f.edit();foreign.put("scope",ContentDigest.sha256Utf8("other").value());f.rehash(foreign);assertThrows(IOException.class,()->f.read(foreign));
        var changed=f.edit();var fact=GraphJson.object(GraphJson.array(changed.get("facts")).getFirst());GraphJson.object(fact.get("span")).put("startLine",99);fact.remove("id");fact.put("id",CanonicalJson.sha256(fact).value());f.rehash(changed);assertThrows(IOException.class,()->f.read(changed));
        try(var reader=f.tables.open(f.request.documents(),()->false)){var doc=reader.next();f.memory.blobs.remove(LanguageSemanticRequest.handle(doc.fields().get("content")).digest());}assertThrows(IOException.class,()->f.read(f.report));
    }
    @Test void cancellationAndWorkerBudgetPreserveTheExplicitFailure()throws Exception {
        var f=new Fixture();var raw=f.memory.text(CanonicalJson.write(f.report));assertThrows(IOException.class,()->ArchitectureCapture.read(f.request,raw,f.tables,f.memory,10,f.gaps::add,()->false));
        assertThrows(IOException.class,()->ArchitectureCapture.read(f.request,raw,f.tables,f.memory,1_000_000,f.gaps::add,()->true));
    }
    @Test void pagedEvidenceNavigationPreservesFilteredEmptyPagesAndRejectsForeignContinuation()throws Exception {
        var f=new Fixture();var result=f.read(f.report);var query=new ArchitectureCaptureQuery(f.tables);var filter=new ArchitectureCaptureQuery.Filter(ArchitectureCaptureQuery.Section.FACTS,Optional.of("F05"),Optional.empty());
        Optional<ArchitectureCaptureQuery.Cursor> cursor=Optional.empty();var rows=new ArrayList<SemanticTables.Row>();int pages=0;
        do{var page=query.page(result,filter,cursor,1,()->false);rows.addAll(page.rows());cursor=page.next();assertTrue(++pages<100);}while(cursor.isPresent());
        assertTrue(rows.stream().anyMatch(r->"API_OPERATION".equals(r.fields().get("kind"))));assertTrue(rows.stream().allMatch(r->"F05".equals(r.fields().get("category"))));
        var first=query.page(result,filter,Optional.empty(),1,()->false);var other=new ArchitectureCaptureQuery.Filter(ArchitectureCaptureQuery.Section.FACTS,Optional.of("F07"),Optional.empty());
        assertThrows(IOException.class,()->query.page(result,other,first.next(),1,()->false));
    }
    @Test void refinedWrapperFootprintsCannotNameForeignOrOutOfRangeSource()throws Exception {
        var f=new Fixture();var edited=f.edit();var fact=GraphJson.array(edited.get("facts")).stream().map(GraphJson::object).filter(row->"RESOLVED".equals(row.get("status"))).findFirst().orElseThrow();
        GraphJson.object(fact.get("attributes")).put("wrapperFootprint",List.of(Map.of("path","outside.ts","start",0,"end",1)));fact.remove("id");fact.put("id",CanonicalJson.sha256(fact).value());f.rehash(edited);
        assertThrows(IOException.class,()->f.read(edited));
        GraphJson.object(fact.get("attributes")).put("wrapperFootprint",List.of(Map.of("path","index.html","start",0,"end",10000)));fact.remove("id");fact.put("id",CanonicalJson.sha256(fact).value());f.rehash(edited);assertThrows(IOException.class,()->f.read(edited));
    }
    @Test void sfcNativeReferenceCannotInventItsSupportingCompilerObservation()throws Exception {
        var supported=new Fixture("architecture-native-v4");assertTrue(supported.read(supported.report).factCount()>0);
        assertTrue(GraphJson.array(supported.report.get("facts")).stream().map(GraphJson::object).anyMatch(row->"native-module-reference".equals(GraphJson.object(row.get("attributes")).get("binding"))));
        var f=new Fixture("architecture-native-v2");var edited=f.edit();var fact=GraphJson.array(edited.get("facts")).stream().map(GraphJson::object).filter(row->"SFC_MODULE_REFERENCE".equals(row.get("kind"))&&"RESOLVED".equals(row.get("status"))).findFirst().orElseThrow();
        var attributes=GraphJson.object(fact.get("attributes"));attributes.put("binding","native-module-reference");attributes.put("nativeReference",ContentDigest.sha256Utf8("missing native module observation").value());fact.remove("id");fact.put("id",CanonicalJson.sha256(fact).value());f.rehash(edited);
        assertThrows(IOException.class,()->f.read(edited));
    }
    @Test void nativeSfcProgramCannotReferenceMissingOrForeignGeneratedArtifacts()throws Exception {
        var f=new Fixture("architecture-native-v2");var edited=f.edit();
        GraphJson.array(edited.get("native")).add(Map.of("path","App.vue","engine","@vue/language-core","mode","configured-sfc-program-noEmit","generated",ContentDigest.sha256Utf8("missing virtual artifact").value(),"mapping",ContentDigest.sha256Utf8("missing mapping artifact").value()));
        f.rehash(edited);assertThrows(IOException.class,()->f.read(edited));
        var foreign=f.edit();var artifact=GraphJson.array(foreign.get("artifacts")).stream().map(GraphJson::object).filter(a->"Card.vue".equals(a.get("source"))).findFirst().orElseThrow();
        GraphJson.array(foreign.get("native")).add(Map.of("path","App.vue","engine","@vue/language-core","mode","configured-sfc-program-noEmit","generated",artifact.get("id"),"mapping",artifact.get("id")));f.rehash(foreign);assertThrows(IOException.class,()->f.read(foreign));
    }
    @Test void nativeGeneratedCodeAndMapBytesStaySeparateAndTamperingCannotPublish()throws Exception {
        var f=new Fixture("architecture-native-v2");var result=f.read(f.report);assertEquals(5,result.documents());assertTrue(result.artifacts().rows()>=4);
        try(var reader=f.tables.open(result.artifacts(),()->false)){SemanticTables.Row row;while((row=reader.next())!=null){assertEquals("generated-helper",row.fields().get("population"));assertFalse(row.fields().containsKey("data"));assertTrue(f.memory.verify(LanguageSemanticRequest.handle(row.fields().get("content"))));}}
        var tampered=f.edit();var artifact=GraphJson.object(GraphJson.array(tampered.get("artifacts")).getFirst());artifact.put("data",Base64.getEncoder().encodeToString("forged code".getBytes(StandardCharsets.UTF_8)));f.rehash(tampered);assertThrows(IOException.class,()->f.read(tampered));
    }
    @Test void aRehashedProducerCannotHideAnUncertainFactGapOrChangeItsConditions()throws Exception {
        var f=new Fixture();var hidden=f.edit();GraphJson.array(hidden.get("obligations")).clear();f.rehash(hidden);assertThrows(IOException.class,()->f.read(hidden));
        var changed=f.edit();var fact=GraphJson.object(GraphJson.array(changed.get("facts")).getFirst());fact.put("conditions",Map.of("feature","red"));fact.remove("id");fact.put("id",CanonicalJson.sha256(fact).value());f.rehash(changed);assertThrows(IOException.class,()->f.read(changed));
    }
    @Test void dispositionVocabularyAndPopulationCannotBeForgedByRehashing()throws Exception {
        var f=new Fixture();
        for(String field:List.of("disposition","role")){
            var changed=f.edit();GraphJson.object(GraphJson.array(changed.get("dispositions")).getFirst()).put(field,"forged");f.rehash(changed);
            assertThrows(IOException.class,()->f.read(changed),field);
        }
        for(String population:List.of("forged","test","dependency")){
            var changed=f.edit();var fact=GraphJson.array(changed.get("facts")).stream().map(GraphJson::object).filter(row->"RESOLVED".equals(row.get("status"))).findFirst().orElseThrow();
            fact.put("population",population);fact.remove("id");fact.put("id",CanonicalJson.sha256(fact).value());f.rehash(changed);assertThrows(IOException.class,()->f.read(changed));
        }
    }
    @Test void anUncertaintyGapMustMatchItsFactCategorySourceAndStatus()throws Exception {
        var f=new Fixture();
        for(String field:List.of("category","reason")){
            var changed=f.edit();var row=GraphJson.array(changed.get("obligations")).stream().map(GraphJson::object).filter(g->GraphJson.string(g.get("reason")).startsWith("ARCHITECTURE_")).findFirst().orElseThrow();
            row.put(field,field.equals("category")?"F01":"ARCHITECTURE_ERROR");row.remove("id");row.put("id",CanonicalJson.sha256(List.of(f.request.scope(),row)).value());f.rehash(changed);
            assertThrows(IOException.class,()->f.read(changed),field);
        }
    }
    @Test void htmlMappingProjectionChecksExactParentBytesBeforePublication()throws Exception {
        var f=new Fixture();var changed=f.edit();var source=GraphJson.array(f.input.get("files")).stream().map(GraphJson::object).filter(row->GraphJson.string(row.get("path")).endsWith(".html")).findFirst().orElseThrow();
        String text=GraphJson.string(source.get("text"));var projection=new TreeMap<String,Object>(Map.of("schema","source-excerpt:m8.4-v1","language","html","start",0,"end",text.length(),"digest",ContentDigest.sha256Utf8(text).value()));
        GraphJson.array(changed.get("native")).add(Map.of("path",source.get("path"),"engine","@jridgewell/trace-mapping","mode","source-excerpt-mapping","sourceProjection",projection));f.rehash(changed);
        assertTrue(f.read(changed).factCount()>0);projection.put("digest",ContentDigest.sha256Utf8("different template").value());f.rehash(changed);assertThrows(IOException.class,()->f.read(changed));
    }
    @Test void nativeEmbeddedInputProjectionMustMatchTheOriginalSourceSlice()throws Exception {
        var f=new Fixture("architecture-native-v3");assertTrue(f.read(f.report).factCount()>0);
        for(String field:List.of("digest","language")){
            var changed=f.edit();var row=GraphJson.array(changed.get("native")).stream().map(GraphJson::object).filter(n->n.containsKey("sourceProjection")).findFirst().orElseThrow();
            GraphJson.object(row.get("sourceProjection")).put(field,field.equals("digest")?ContentDigest.sha256Utf8("forged excerpt").value():"unknown");f.rehash(changed);
            assertThrows(IOException.class,()->f.read(changed),field);
        }
    }
}
