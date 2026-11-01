package com.evolution.analysis.polyglot;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArchitectureCaptureAuditTest {
    record Case(ArchitectureCaptureTest.Fixture fixture,ContentHandle raw,ContentHandle html,String source){}
    Case fixture()throws Exception{
        var f=new ArchitectureCaptureTest.Fixture();var input=GraphJson.array(f.input.get("files")).stream().map(GraphJson::object).filter(x->"index.html".equals(x.get("path"))).findFirst().orElseThrow();String text=GraphJson.string(input.get("text"));
        return new Case(f,f.memory.text(CanonicalJson.write(f.report)),f.memory.text(text),text);
    }
    ArchitectureCaptureAudit.Expected expected(Case c,String id,String kind,String sourceToken,Optional<String> target,List<String> statuses,long min,long max){
        Optional<ArchitectureCaptureAudit.Range> range=sourceToken==null?Optional.empty():Optional.of(new ArchitectureCaptureAudit.Range(c.source.indexOf(sourceToken),c.source.indexOf(sourceToken)+sourceToken.length()));
        return new ArchitectureCaptureAudit.Expected(id,"index.html",c.html,"F07",kind,range,target,Map.of(),statuses,min,max);
    }
    @Test void independentDenominatorRetainsMissingUncertainAndForbiddenOccurrences()throws Exception{
        var c=fixture();var f=c.fixture;var labels=List.of(expected(c,"logo","RESOURCE_REFERENCE","./logo.svg",Optional.of("logo.svg"),List.of("RESOLVED"),1,1),
            expected(c,"missing","RESOURCE_REFERENCE","./missing.js",Optional.empty(),List.of("RESOLVED"),1,1),
            expected(c,"omitted","MISSING_RESOURCE_PROVIDER",null,Optional.empty(),List.of("RESOLVED"),1,1),
            expected(c,"negative","NETWORK_REQUEST",null,Optional.empty(),List.of("RESOLVED"),0,0));
        var gaps=new ArrayList<CapabilityGapRecord>();var result=ArchitectureCaptureAudit.read(f.request,c.raw,labels,f.tables,f.memory,1_000_000,gaps::add,()->false);
        assertEquals(4,result.counts().expected());assertEquals(2,result.counts().satisfied());assertEquals(1,result.counts().omitted());assertEquals(1,result.counts().uncertain());assertEquals(4,result.dispositions().rows());assertFalse(result.qualified());
        assertTrue(gaps.stream().anyMatch(g->g.reasonCode().equals("ARCHITECTURE_OCCURRENCE_OMITTED")));assertTrue(gaps.stream().anyMatch(g->g.reasonCode().equals("ARCHITECTURE_OCCURRENCE_UNCERTAIN")));
        var forbidden=ArchitectureCaptureAudit.read(f.request,c.raw,List.of(expected(c,"forbidden","RESOURCE_REFERENCE","./logo.svg",Optional.of("logo.svg"),List.of("RESOLVED"),0,0)),f.tables,f.memory,1_000_000,g->{},()->false);
        assertEquals(1,forbidden.counts().forbidden());
        var mismatch=ArchitectureCaptureAudit.read(f.request,c.raw,List.of(expected(c,"wrong-target","RESOURCE_REFERENCE","./logo.svg",Optional.of("other.svg"),List.of("RESOLVED"),1,1)),f.tables,f.memory,1_000_000,g->{},()->false);assertEquals(1,mismatch.counts().mismatch());
        var ambiguous=ArchitectureCaptureAudit.read(f.request,c.raw,List.of(expected(c,"too-many","RESOURCE_REFERENCE",null,Optional.empty(),List.of("RESOLVED","UNRESOLVED"),1,1)),f.tables,f.memory,1_000_000,g->{},()->false);assertEquals(1,ambiguous.counts().ambiguous());
        var window=expected(c,"source-window","RESOURCE_REFERENCE","src=\"./logo.svg\"",Optional.of("logo.svg"),List.of("RESOLVED"),1,1);
        assertEquals(1,ArchitectureCaptureAudit.read(f.request,c.raw,List.of(window),f.tables,f.memory,1_000_000,g->{},()->false).counts().satisfied());
    }
    @Test void wrongSourceEmptyDenominatorAndInvalidRangeCannotBecomePassingEvidence()throws Exception{
        var c=fixture();var f=c.fixture;var wrong=new ArchitectureCaptureAudit.Expected("wrong","index.html",f.memory.text("different bytes"),"F07","RESOURCE_REFERENCE",Optional.empty(),Optional.empty(),Map.of(),List.of("RESOLVED"),1,1);
        assertThrows(IOException.class,()->ArchitectureCaptureAudit.read(f.request,c.raw,List.of(wrong),f.tables,f.memory,1_000_000,g->{},()->false));
        assertThrows(IllegalArgumentException.class,()->ArchitectureCaptureAudit.read(f.request,c.raw,List.of(),f.tables,f.memory,1_000_000,g->{},()->false));
        var outside=expected(c,"outside","RESOURCE_REFERENCE",null,Optional.empty(),List.of("RESOLVED"),1,1);
        var invalid=new ArchitectureCaptureAudit.Expected(outside.id(),outside.document(),outside.source(),outside.category(),outside.kind(),Optional.of(new ArchitectureCaptureAudit.Range(0,10000)),outside.target(),outside.attributes(),outside.statuses(),1,1);
        assertThrows(IOException.class,()->ArchitectureCaptureAudit.read(f.request,c.raw,List.of(invalid),f.tables,f.memory,1_000_000,g->{},()->false));
    }
    @Test void auditReplaysAndItsPagedContinuationBindsTheIndependentLabels()throws Exception{
        var c=fixture();var f=c.fixture;var labels=List.of(expected(c,"logo","RESOURCE_REFERENCE","./logo.svg",Optional.of("logo.svg"),List.of("RESOLVED"),1,1),expected(c,"negative","NETWORK_REQUEST",null,Optional.empty(),List.of("RESOLVED"),0,0));
        var a=ArchitectureCaptureAudit.read(f.request,c.raw,labels,f.tables,f.memory,1_000_000,g->{},()->false);var b=ArchitectureCaptureAudit.read(f.request,c.raw,labels.reversed(),new SemanticTables(f.memory,100_000),f.memory,1_000_000,g->{},()->false);assertEquals(a,b);
        var query=new ArchitectureCaptureQuery(f.tables);var page=query.occurrences(a,Optional.empty(),Optional.empty(),Optional.empty(),1,()->false);assertEquals(1,page.rows().size());assertTrue(page.next().isPresent());
        var other=ArchitectureCaptureAudit.read(f.request,c.raw,List.of(labels.getFirst()),f.tables,f.memory,1_000_000,g->{},()->false);
        assertThrows(IOException.class,()->query.occurrences(other,Optional.empty(),Optional.empty(),page.next(),1,()->false));
        assertThrows(IOException.class,()->ArchitectureCaptureAudit.read(f.request,c.raw,labels,f.tables,f.memory,1_000_000,g->{},()->true));
    }
}
