package com.evolution.analysis.evolution;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.graph.*;
import java.io.IOException;
import java.util.*;
import java.util.function.BooleanSupplier;
import static com.evolution.analysis.evolution.EvolutionRows.*;
import static com.evolution.analysis.evolution.SnapshotComparison.*;

final class EvolutionRun {
    static final VersionedIdentifier PROVIDER=new VersionedIdentifier("architecture.evolution","m11.2-v1");
    final Request request;
    final ContentDigest requestIdentity;
    final EvolutionIndex index;
    final int size;
    final BooleanSupplier cancelled;
    private boolean useful,comparable,residual,comparedJava;

    EvolutionRun(Request request,ContentDigest requestIdentity,EvolutionIndex index,int size,BooleanSupplier cancelled) {
        this.request=request;this.requestIdentity=requestIdentity;this.index=index;this.size=size;this.cancelled=cancelled;
    }

    Result compare(EvolutionSnapshot.Reader reader,List<ReportRef> reports,ReportReader reportReader)throws IOException {
        var left=read("LEFT",request.left(),reader);var right=read("RIGHT",request.right(),reader);
        for(var ref:reports) {
            EvolutionSnapshot.check(cancelled);
            var side=ref.side()==Side.LEFT?left:right;
            ReportStatus status=!side.readable?ReportStatus.UNAVAILABLE:
                    index.get("context-id",side.name+"|"+ref.context()).isEmpty()?ReportStatus.CORRUPT:
                    Objects.requireNonNull(reportReader.verify(side.graph,ref,cancelled));
            put(Section.INPUTS,side.name+"|report|"+ref.identity().value()+"|"+ref.context(),
                    row("side",side.name,"graph",side.graph,"context",ref.context(),"artifact",ref.identity(),"kind","REPORT","status",status));
            if(status!=ReportStatus.AVAILABLE){residual=true;if(side.header!=null)gap(side,ref.context(),"REPORT_"+status.name(),List.of(ref.identity()));}
        }
        var l=new Rows(index,"scopes",left.name+"|");var r=new Rows(index,"scopes",right.name+"|");
        if(!left.readable)l=null;if(!right.readable)r=null;
        while(head(l)!=null||head(r)!=null) {
            EvolutionSnapshot.check(cancelled);
            String key=scopeKey(head(l),head(r));
            var a=group(l,key);var b=group(r,key);
            align(key,left,a,right,b);
            index.put("run","checkpoint",row("request",requestIdentity,"throughScope",key));
        }
        if(!left.readable||!right.readable||!comparedJava) {
            residual=true;
            if(!comparedJava){
                if(left.readable)gap(left,null,"SELECTED_JAVA_CONTEXT_UNAVAILABLE",List.of(left.graph));
                if(right.readable)gap(right,null,"SELECTED_JAVA_CONTEXT_UNAVAILABLE",List.of(right.graph));
            }
        }
        var sections=new TreeMap<String,Census>();
        for(var section:Section.values()) {
            long count=0;var rows=new Rows(index,section.name(),"");
            try(var digest=new ArrayDigest()) {
                for(var item=rows.next();item!=null;item=rows.next()){
                    EvolutionSnapshot.check(cancelled);digest.add(item.value());count++;
                }
                sections.put(section.name(),new Census(count,digest.finish()));
            }
        }
        var status=residual?((comparable||useful)?Status.PARTIAL:Status.WITHHELD):Status.COMPLETE;
        var id=hash("architecture.evolution.result:v1",List.of(requestIdentity,left.disposition,right.disposition,status,sections));
        return new Result(new Summary(requestIdentity,id,status,sections),index);
    }

    private EvolutionInput read(String side,ContentDigest graph,EvolutionSnapshot.Reader reader)throws IOException {
        var input=new EvolutionInput(this,side,graph);
        try {input.read(reader);}
        catch(EvolutionSnapshot.ReadFailure failure){input.failed(failure.reason().name());}
        catch(GraphJson.LimitExceededException limit){input.failed("RESOURCE_LIMIT");}
        catch(IllegalArgumentException invalid){input.failed("CORRUPT");}
        put(Section.INPUTS,side+"|graph",row("side",side,"graph",graph,"snapshot",input.header==null?null:input.header.snapshot(),
                "kind","GRAPH","status",input.disposition));
        if(!input.readable) {
            put(Section.CONTEXTS,side+"|input",row("side",side,"graph",graph,"context",null,
                    "disposition","UNAVAILABLE","reason",input.disposition));
            if(input.header!=null)gap(input,null,"INPUT_"+input.disposition,List.of(graph));
        } else if(!input.expectedCensus) {
            put(Section.CONTEXTS,side+"|expected-census",row("side",side,"graph",graph,"context",null,
                    "disposition","UNAVAILABLE","reason","EXPECTED_CENSUS_UNAVAILABLE"));
            gap(input,null,"EXPECTED_CENSUS_UNAVAILABLE",List.of(graph));
            // This is an independent repository-wide obligation; it cannot erase proved selected context deltas.
        }
        if(input.readable)input.publishOriginalGaps();
        return input;
    }

    private void align(String key,EvolutionInput left,Group a,EvolutionInput right,Group b)throws IOException {
        var x=a.single();var y=b.single();
        boolean selected=(a.selected||b.selected);
        String disposition=a.count>1||b.count>1?"AMBIGUOUS":
                !left.readable||!right.readable?"UNAVAILABLE":x!=null&&y!=null?"MATCHED":x!=null?"LEFT_ONLY":"RIGHT_ONLY";
        if(x!=null&&!available(x)||y!=null&&!available(y))disposition="UNAVAILABLE";
        if(!selected)disposition="NOT_SELECTED";
        emitContexts(a,disposition,y);emitContexts(b,disposition,x);
        if(!selected)return;
        var refs=new ArrayList<ContentDigest>();
        if(x!=null)refs.add(new ContentDigest(text(x,"evidence")));
        if(y!=null)refs.add(new ContentDigest(text(y,"evidence")));
        if(!disposition.equals("MATCHED")) {
            residual=true;
            if(request.mode()==Mode.OBSERVATION&&(x!=null||y!=null))useful=true;
            String reason=switch(disposition){case "AMBIGUOUS"->"CONTEXT_AMBIGUOUS";case "LEFT_ONLY","RIGHT_ONLY"->"CONTEXT_ONE_SIDED";default->"CONTEXT_UNAVAILABLE";};
            compatibility(key,"STRUCTURE","UNKNOWN",Set.of(reason),refs);
            if(x!=null)gap(left,text(x,"context"),reason,refs);if(y!=null)gap(right,text(y,"context"),reason,refs);
            return;
        }
        var reasons=new TreeSet<String>();
        if(!left.lineage||!right.lineage)reasons.add("LINEAGE_UNPROVED");
        boolean foreign=left.foreign||right.foreign;
        if(foreign)reasons.add("REPOSITORY_MISMATCH");
        String family=text(x,"family");
        if(family.equals("repository.snapshot")) {
            String verdict=foreign?"INCOMPATIBLE":reasons.isEmpty()?"COMPARABLE":"UNKNOWN";
            compatibility(key,"STRUCTURE",verdict,reasons,refs);
            if(!reasons.isEmpty())residual=true;
            return;
        }
        if(!family.equals("analysis.manifest")) {
            reasons.add("CAPABILITY_UNSUPPORTED");residual=true;
            compatibility(key,"STRUCTURE",foreign?"INCOMPATIBLE":"UNKNOWN",reasons,refs);
            gap(left,text(x,"context"),"CAPABILITY_UNSUPPORTED",refs);gap(right,text(y,"context"),"CAPABILITY_UNSUPPORTED",refs);
            return;
        }
        comparedJava=true;
        var lp=obj(x.get("profile"));var rp=obj(y.get("profile"));
        if(!Objects.equals(lp.get("tool"),rp.get("tool")))reasons.add("TOOL_DRIFT");
        if(!Objects.equals(lp.get("rules"),rp.get("rules")))reasons.add("RULE_DRIFT");
        if(!Objects.equals(lp.get("classpath"),rp.get("classpath")))reasons.add("CLASSPATH_DRIFT");
        if(!Objects.equals(lp.get("platform"),rp.get("platform")))reasons.add("PLATFORM_DRIFT");
        if(!Objects.equals(lp.get("schema"),rp.get("schema")))reasons.add("SCHEMA_DRIFT");
        if(!Objects.equals(lp.get("configuration"),rp.get("configuration")))reasons.add("CONFIGURATION_DRIFT");
        if(!Objects.equals(lp.get("provider"),rp.get("provider")))reasons.add("TOOL_DRIFT");
        if(!Boolean.TRUE.equals(lp.get("toolKnown"))||!Boolean.TRUE.equals(rp.get("toolKnown")))reasons.add("TOOL_UNPROVED");
        if(!Boolean.TRUE.equals(lp.get("rulesKnown"))||!Boolean.TRUE.equals(rp.get("rulesKnown")))reasons.add("RULE_UNPROVED");
        if(!Boolean.TRUE.equals(lp.get("platformKnown"))||!Boolean.TRUE.equals(rp.get("platformKnown")))reasons.add("PLATFORM_UNPROVED");
        if(!Boolean.TRUE.equals(lp.get("schemaKnown"))||!Boolean.TRUE.equals(rp.get("schemaKnown")))reasons.add("SCHEMA_UNSUPPORTED");
        var lCoverage=coverage(left,x);var rCoverage=coverage(right,y);
        boolean closed=lCoverage.closed()&&rCoverage.closed();
        boolean drift=reasons.stream().anyMatch(code->code.endsWith("_DRIFT"));
        boolean strict=reasons.isEmpty();
        if(!closed)reasons.add("COVERAGE_UNPROVED");
        var verdict=foreign||reasons.contains("SCHEMA_UNSUPPORTED")?"INCOMPATIBLE":drift?"QUALIFIED":reasons.isEmpty()?"COMPARABLE":"UNKNOWN";
        compatibility(key,"STRUCTURE",verdict,reasons,refs);
        if(!request.selection().relationshipKinds().isEmpty())compatibility(key,"SEMANTIC_RELATION",verdict,reasons,refs);
        compatibility(key,"EVIDENCE",foreign?"INCOMPATIBLE":"COMPARABLE",Set.of(),refs);
        if(request.mode()==Mode.OBSERVATION)useful=true;
        if(!strict||!closed) {
            residual=true;
            if(!lCoverage.closed())gap(left,text(x,"context"),"COVERAGE_UNPROVED",lCoverage.references());
            if(!rCoverage.closed())gap(right,text(y,"context"),"COVERAGE_UNPROVED",rCoverage.references());
            for(var reason:reasons)if(!reason.equals("COVERAGE_UNPROVED")){
                gap(left,text(x,"context"),reason,refs);gap(right,text(y,"context"),reason,refs);
            }
        }
        if(!foreign&&left.lineage&&right.lineage&&lCoverage.readable()&&rCoverage.readable())try {
            var closedSubjects=new EvolutionDelta(this,key,left,x,lCoverage,right,y,rCoverage,strict).compare();
            if(strict&&closed&&closedSubjects)comparable=true;
            if(!closedSubjects) {
                reasons.add("SUBJECT_COVERAGE_UNPROVED");
                String qualified=verdict.equals("INCOMPATIBLE")?verdict:drift?"QUALIFIED":"UNKNOWN";
                compatibility(key,"STRUCTURE",qualified,reasons,refs);
                if(!request.selection().relationshipKinds().isEmpty())compatibility(key,"SEMANTIC_RELATION",qualified,reasons,refs);
            }
        } catch(EvolutionSnapshot.ReadFailure invalid) {
            residual=true;
            compatibility(key,"STRUCTURE","UNKNOWN",Set.of("OPERAND_"+invalid.reason().name()),refs);
            if(!request.selection().relationshipKinds().isEmpty())compatibility(key,"SEMANTIC_RELATION","UNKNOWN",
                    Set.of("OPERAND_"+invalid.reason().name()),refs);
            gap(left,text(x,"context"),"OPERAND_"+invalid.reason().name(),refs);
            gap(right,text(y,"context"),"OPERAND_"+invalid.reason().name(),refs);
        } catch(IllegalArgumentException invalid) {
            residual=true;
            String reason=invalid instanceof GraphJson.LimitExceededException?"OPERAND_RESOURCE_LIMIT":"OPERAND_CORRUPT";
            compatibility(key,"STRUCTURE","UNKNOWN",Set.of(reason),refs);
            if(!request.selection().relationshipKinds().isEmpty())compatibility(key,"SEMANTIC_RELATION","UNKNOWN",Set.of(reason),refs);
            gap(left,text(x,"context"),reason,refs);gap(right,text(y,"context"),reason,refs);
        }
    }

    private EvolutionInput.Coverage coverage(EvolutionInput input,Map<String,Object> context)throws IOException {
        try{return input.coverage(context);}
        catch(EvolutionSnapshot.ReadFailure|IllegalArgumentException failure){
            var refs=List.of(new ContentDigest(text(context,"evidence")));
            String reason=failure instanceof EvolutionSnapshot.ReadFailure read?read.reason().name():
                    failure instanceof GraphJson.LimitExceededException?"RESOURCE_LIMIT":"CORRUPT";
            gap(input,text(context,"context"),"OPERAND_"+reason,refs);
            return new EvolutionInput.Coverage(false,refs,false);
        }
    }

    private void compatibility(String scope,String capability,String verdict,Set<String> reasons,List<ContentDigest> refs)throws IOException {
        boolean proved=verdict.equals("COMPARABLE");
        put(Section.COMPATIBILITY,scope+"|"+capability,row("scope",scope,"capability",capability,"verdict",verdict,
                "reasons",reasons,"evidence",refs,"allowedOperations",proved?List.of("COMPARE"):
                        request.mode()==Mode.OBSERVATION?List.of("OBSERVE"):List.of(),
                "withheldOperations",proved?List.of():List.of("UNQUALIFIED_COMPARISON")));
    }

    void gap(EvolutionInput side,String context,String reason,List<ContentDigest> evidence)throws IOException {
        String subjectId=context==null?side.graph.value():context;
        var subject=new EvidenceSubject(EvidenceSubject.Kind.ARTIFACT,subjectId);
        var origin=new TreeSet<ContentDigest>(evidence);if(origin.isEmpty())origin.add(side.graph);
        var observation=origin.stream().map(ref->ProviderObservationReference.create(PROVIDER,"architecture.evolution",
                side.graph,ref)).toList();
        var scope=new EvidenceContext(side.header.snapshot(),side.analysis(context));
        var requirement=new EvidenceRequirement(EvidenceRequirement.Kind.REPOSITORY_CONTENT,"evolution.verify-scope",
                List.of(subject),EvidenceRequirement.AuthorizationClass.PASSIVE,List.of("Verify immutable comparison operands and the selected census."));
        var gap=CapabilityGapRecord.create(PROVIDER,scope,PROVIDER,"architecture.evolution",reason,subject,List.of(),
                observation,List.of(requirement),List.of(),List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE,
                        "architecture.evolution")),List.of(),List.of(),List.of());
        put(Section.GAPS,side.name+"|"+gap.gapIdentity().value()+"|"+hash("evolution.gap-evidence",origin).value(),
                row("side",side.name,"graph",side.graph,"context",context,"gap",gap));
    }

    void put(Section section,String key,Map<String,Object> value)throws IOException {index.put(section.name(),key,value);}
    void unresolved(){residual=true;}
    private static boolean available(Map<String,Object> row){return Boolean.TRUE.equals(row.get("available"));}
    private static EvolutionIndex.Entry head(Rows rows)throws IOException{return rows==null?null:rows.head();}
    private static String scopeKey(EvolutionIndex.Entry left,EvolutionIndex.Entry right) {
        String a=left==null?null:text(left.value(),"scope"),b=right==null?null:text(right.value(),"scope");
        return a==null?b:b==null||a.compareTo(b)<=0?a:b;
    }
    private Group group(Rows rows,String scope)throws IOException {
        if(rows==null||rows.head()==null||!scope.equals(rows.head().value().get("scope")))return new Group("",0,false,null);
        var first=rows.head();String prefix=first.key().substring(0,first.key().lastIndexOf('|')+1);
        long count=0;boolean selected=false;
        while(rows.head()!=null&&rows.head().key().startsWith(prefix)){
            EvolutionSnapshot.check(cancelled);var item=rows.next();count++;selected|=Boolean.TRUE.equals(item.value().get("selected"));
        }
        return new Group(prefix,count,selected,first.value());
    }
    private record Group(String prefix,long count,boolean selected,Map<String,Object> first){
        Map<String,Object> single(){return count==1?first:null;}
    }
    private void emitContexts(Group group,String disposition,Map<String,Object> opposite)throws IOException {
        if(group.count==0)return;
        var rows=new Rows(index,"scopes",group.prefix);
        for(var item=rows.next();item!=null;item=rows.next()){
            EvolutionSnapshot.check(cancelled);
            var value=new TreeMap<>(item.value());value.remove("profile");
            String state=Boolean.TRUE.equals(value.get("selected"))?disposition:"NOT_SELECTED";
            value.put("disposition",state);
            value.put("opposite",state.equals("MATCHED")&&opposite!=null?opposite.get("context"):null);
            value.put("candidateGroup",state.equals("AMBIGUOUS")?value.get("scope"):null);
            put(Section.CONTEXTS,text(value,"side")+"|"+text(value,"context"),value);
        }
    }
}
