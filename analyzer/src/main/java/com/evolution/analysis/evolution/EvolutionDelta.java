package com.evolution.analysis.evolution;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.EntityIdentity;
import com.evolution.analysis.contract.identity.EntityScope;
import com.evolution.analysis.contract.semantic.EntityKind;
import com.evolution.analysis.contract.semantic.EntityOrigin;
import com.evolution.analysis.contract.semantic.RelationshipKind;
import com.evolution.analysis.contract.semantic.RelationshipTarget;
import com.evolution.analysis.contract.semantic.SemanticRelationship;
import com.evolution.analysis.contract.identity.SourceDocumentIdentity;
import com.evolution.analysis.contract.identity.OccurrenceIdentity;
import com.evolution.analysis.contract.source.SourceSpan;
import com.evolution.analysis.graph.*;
import java.io.IOException;
import java.util.*;
import static com.evolution.analysis.evolution.EvolutionRows.*;
import static com.evolution.analysis.evolution.SnapshotComparison.*;

final class EvolutionDelta {
    private final EvolutionRun run;
    private final String scope;
    private final EvolutionInput left,right;
    private final Map<String,Object> lc,rc;
    private final EvolutionInput.Coverage lp,rp;
    private final boolean compatible;
    private final Set<String> openPopulations=new HashSet<>();

    EvolutionDelta(EvolutionRun run,String scope,EvolutionInput left,Map<String,Object> lc,EvolutionInput.Coverage lp,
                   EvolutionInput right,Map<String,Object> rc,EvolutionInput.Coverage rp,boolean compatible) {
        this.run=run;this.scope=scope;this.left=left;this.lc=lc;this.lp=lp;this.right=right;this.rc=rc;this.rp=rp;
        this.compatible=compatible;
    }

    boolean compare()throws IOException {
        read(left,lc);read(right,rc);
        merge("entities");merge("relations");
        return openPopulations.isEmpty();
    }

    private String prefix(EvolutionInput side){return side.name+"|"+scope+"|";}
    private void read(EvolutionInput side,Map<String,Object> context)throws IOException {
        String id=text(context,"context"),prefix=prefix(side);
        var frontend=run.index.get("frontends",side.name+"|"+id).orElse(Map.of());
        String frontendRef=maybe(frontend,"evidence");
        var shapes=new Rows(run.index,"context-shapes",side.name+"|"+frontendRef+"|");
        for(var item=shapes.next();item!=null;item=shapes.next()) {
            EvolutionSnapshot.check(run.cancelled);
            var ref=text(item.value(),"evidence");
            var shape=run.index.get("shapes",side.name+"|"+ref).orElseThrow(EvolutionInput::corrupt);
            unique(run.index,"shape-by-entity",prefix+text(shape,"id"),row("shape",shape.get("shape"),"evidence",ref));
        }
        scan(cursor->side.snapshot.nodes(id,run.size,cursor),number(context.get("nodes")),run.size,run.cancelled,node->{
            if(!id.equals(node.context()))throw EvolutionInput.corrupt();
            var selected=run.request.selection().nodeKinds();
            if(!isEntity(node.kind())){
                if(selected.contains(node.kind())){openPopulations.add(side.name);run.unresolved();
                    run.gap(side,id,"ENTITY_KIND_UNSUPPORTED",List.of(new ContentDigest(text(context,"evidence"))));}
                return;
            }
            var value=entity(side,context,node);
            if(value!=null) {
                String contextPrefix=side.name+"|"+id+"|";
                unique(run.index,"java-symbols",contextPrefix+node.identity(),value);
                String ownerKey=contextPrefix+hash("evolution.java-owner",List.of(value.get("stableScope"),value.get("canonicalName"))).value();
                var prior=run.index.get("owner-symbol",ownerKey);
                if(prior.isEmpty())run.index.put("owner-symbol",ownerKey,value);
                else {run.index.put("owner-symbol",ownerKey,row("ambiguous",true));openPopulations.add(side.name);}
                if(selected.isEmpty()||selected.contains(node.kind()))unique(run.index,"entities",prefix+node.identity(),value);
                else run.put(Section.CORRESPONDENCE,side.name+"|"+scope+"|excluded|"+node.identity(),
                        row("scope",scope,"side",side.name,"context",id,"identity",node.identity(),"kind",node.kind(),"disposition","NOT_SELECTED"));
            }
        });
        if(run.request.selection().relationshipKinds().isEmpty())return;
        boolean typeDependencies=run.request.selection().relationshipKinds().contains("graph.depends-on");
        scan(cursor->side.snapshot.edges(id,run.size,cursor),number(context.get("edges")),run.size,run.cancelled,edge->{
            if(!id.equals(edge.context()))throw EvolutionInput.corrupt();
            if(typeDependencies&&edge.kind().startsWith("java."))indexRawRelationship(side,id,edge);
            if(!run.request.selection().relationshipKinds().contains(edge.kind()))return;
            if(!run.request.selection().nodeKinds().isEmpty()&&(!entityPresent(prefix,edge.source())
                    ||edge.target().state()==KnowledgeGraph.TargetState.RESOLVED&&!entityPresent(prefix,edge.target().identities().getFirst())))return;
            boolean resolved=edge.target().state()==KnowledgeGraph.TargetState.RESOLVED&&edge.region().isEmpty();
            if(resolved)resolved=isCertain(run.index.get("java-symbols",side.name+"|"+id+"|"+edge.source()).orElse(null))&&
                    isCertain(run.index.get("java-symbols",side.name+"|"+id+"|"+edge.target().identities().getFirst()).orElse(null));
            // Unresolved, candidate and conditional relationships remain side-specific observations.
            var key=hash("architecture.evolution.subject:v1",List.of(scope,edge.kind(),edge.source(),
                    resolved?edge.target().identities().getFirst():List.of(side.name,edge.identity()))).value();
            var value=relation(side,context,edge,resolved);
            var prior=run.index.get("relations",prefix+key);
            if(prior.isPresent()) {
                // Track A defines a single edge per logical Java relation and per type-owner dependency.
                // Unknown producer discriminators must not be collapsed into one certain match.
                run.unresolved();run.gap(side,id,"RELATION_DISCRIMINATOR_UNPROVED",List.of(new ContentDigest(text(context,"evidence"))));
                openPopulations.add(side.name);
                run.index.put("relations",prefix+key,withCertainty(prior.orElseThrow(),false));
                unique(run.index,"relations",prefix+key+"-"+edge.identity(),withCertainty(value,false));
            } else unique(run.index,"relations",prefix+key,value);
        });
        if(typeDependencies)verifyDependencyProjection(side,id,frontendRef);
    }

    private void indexRawRelationship(EvolutionInput side,String context,KnowledgeGraph.Edge edge)throws IOException {
        for(var ref:edge.evidence()) {
            var proof=side.evidence(ref);String field=switch(proof.family()){
                case "java.occurrence"->"occurrences";case "java.derived-relationship"->"derivedRelationships";default->null;};
            if(field==null||!side.frontendMember(context,field,ref))continue;
            String prefix=prefix(side);
            run.index.put("raw-members",prefix+ref.value(),row("seen",true));
            if(Set.of("java.declares","java.has-parameter").contains(edge.kind())||edge.region().isPresent()
                    ||edge.target().state()!=KnowledgeGraph.TargetState.RESOLVED)continue;
            var raw=EvolutionInput.payload(proof);if(!"RESOLVED".equals(raw.get("status")))continue;
            var from=typeOwner(side,context,edge.source());var to=typeOwner(side,context,edge.target().identities().getFirst());
            if(from==null||to==null){openPopulations.add(side.name);run.unresolved();
                run.gap(side,context,"TYPE_OWNER_UNAVAILABLE",List.of(ref));continue;}
            if(from.equals(to))continue;
            var id=KnowledgeGraph.derivedIdentity("graph-relation",List.of("projection:m5.1-v1","graph.depends-on",from,to));
            if(!run.request.selection().nodeKinds().isEmpty()&&(!entityPresent(prefix,from)||!entityPresent(prefix,to)))continue;
            unique(run.index,"expected-support",prefix+id+"|"+ref.value(),row("raw",edge.identity()));
        }
    }

    private void verifyDependencyProjection(EvolutionInput side,String context,String frontendRef)throws IOException {
        var prefix=prefix(side);
        var expected=new Rows(run.index,"expected-support",prefix);
        for(var item=expected.next();item!=null;item=expected.next()){
            EvolutionSnapshot.check(run.cancelled);
            if(run.index.get("projected-support",item.key()).isEmpty())throw EvolutionInput.corrupt();
        }
        for(var family:List.of("occurrences","derivedRelationships")){
            String base=side.name+"|"+frontendRef+"|"+family+"|";
            var rows=new Rows(run.index,"frontend-members",base);
            for(var item=rows.next();item!=null;item=rows.next()){
                EvolutionSnapshot.check(run.cancelled);
                if(run.index.get("raw-members",prefix+item.key().substring(base.length())).isEmpty()){
                    openPopulations.add(side.name);run.unresolved();
                    run.gap(side,context,"FRONTEND_RELATION_UNAVAILABLE",List.of(new ContentDigest(frontendRef)));
                    break;
                }
            }
        }
    }

    private boolean entityPresent(String prefix,String id)throws IOException {return run.index.get("entities",prefix+id).isPresent();}
    private static boolean isEntity(String kind) {
        try {return !Set.of(EntityKind.REPOSITORY,EntityKind.MODULE,EntityKind.SOURCE_ROOT,EntityKind.PACKAGE,
                EntityKind.ANNOTATION_USE).contains(EntityKind.valueOf(kind));}
        catch(IllegalArgumentException unknown){return false;}
    }

    private Map<String,Object> entity(EvolutionInput side,Map<String,Object> context,KnowledgeGraph.Node node)throws IOException {
        GraphEvidence declaration=null;
        for(var ref:node.evidence()){
            var proof=side.evidence(ref);
            if(proof.family().equals("java.declaration")) {
                if(declaration!=null)throw EvolutionInput.corrupt();declaration=proof;
            }
        }
        if(declaration==null){run.unresolved();openPopulations.add(side.name);run.gap(side,node.context(),"DECLARATION_UNPROVED",node.evidence());return null;}
        var raw=EvolutionInput.payload(declaration);var entity=obj(raw.get("entity"));
        String name=text(entity,"canonicalName"),origin=text(entity,"origin"),stable=text(entity,"stableScope");
        var identity=EntityIdentity.from(EntityOrigin.valueOf(origin),new EntityScope(stable),EntityKind.valueOf(node.kind()),name);
        if(!identity.value().equals(node.identity())||!node.identity().equals(text(entity,"identity"))
                ||!node.kind().equals(text(entity,"kind")))throw EvolutionInput.corrupt();
        boolean certain="RESOLVED".equals(raw.get("status"))&&side.frontendMember(node.context(),"declarations",declaration.identity());
        var location=location(side,node.context(),entity.get("declaration"));
        if(location!=null&&String.valueOf(location.get("classification")).startsWith("GENERATED"))certain=false;
        if(origin.equals("SYNTHETIC"))certain=false;
        var shape=run.index.get("shape-by-entity",prefix(side)+node.identity()).orElse(Map.of());
        if(!certain){
            openPopulations.add(side.name);
            run.unresolved();
            run.gap(side,node.context(),"DECLARATION_UNPROVED",node.evidence());
        }
        return row("identity",node.identity(),"kind",node.kind(),"context",node.context(),"canonicalName",name,"origin",origin,
                "stableScope",stable,"certain",certain,"status",raw.get("status"),"location",location,"shape",shape.get("shape"),
                "shapeEvidence",shape.get("evidence"),"evidence",node.evidence());
    }

    private Map<String,Object> relation(EvolutionInput side,Map<String,Object> context,KnowledgeGraph.Edge edge,boolean resolved)throws IOException {
        String id=text(context,"context");
        long occurrences=0,derived=0;
        // Sorted hashes retain multiplicity without relying on graph evidence hash ordering for the location axis.
        String supportPrefix=prefix(side)+edge.identity()+"|";
        for(var ref:edge.evidence()){
            EvolutionSnapshot.check(run.cancelled);
            var proof=side.evidence(ref);
            if(!Set.of("java.occurrence","java.derived-relationship").contains(proof.family())){resolved=false;continue;}
            var raw=EvolutionInput.payload(proof);var rel=obj(raw.get("relationship"));
            String field=proof.family().equals("java.occurrence")?"occurrences":"derivedRelationships";
            if(!side.frontendMember(id,field,ref)||!"RESOLVED".equals(raw.get("status")))resolved=false;
            if(edge.kind().equals("graph.depends-on"))
                unique(run.index,"projected-support",supportPrefix+ref.value(),row("present",true));
            if(!edge.kind().equals("graph.depends-on")&&(!edge.identity().equals(rel.get("identity"))||!edge.source().equals(rel.get("source"))
                    ||!edge.kind().equals(EvolutionInput.category(rel.get("kind")))))throw EvolutionInput.corrupt();
            var target=obj(rel.get("target"));
            if("RESOLVED".equals(raw.get("status"))) {
                var sourceId=new EntityIdentity(text(rel,"source"));var targetId=new EntityIdentity(text(target,"target"));
                var statement=SemanticRelationship.create(sourceId,new RelationshipKind(EvolutionInput.category(rel.get("kind"))),
                        new RelationshipTarget.Resolved(targetId));
                if(!statement.identity().value().equals(rel.get("identity")))throw EvolutionInput.corrupt();
                if(edge.kind().equals("graph.depends-on")) {
                    if(edge.target().state()!=KnowledgeGraph.TargetState.RESOLVED)throw EvolutionInput.corrupt();
                    if(!edge.identity().equals(KnowledgeGraph.derivedIdentity("graph-relation",List.of("projection:m5.1-v1",edge.kind(),
                            edge.source(),edge.target().identities().getFirst()))))throw EvolutionInput.corrupt();
                    if(!edge.source().equals(typeOwner(side,id,sourceId.value()))
                            ||!edge.target().identities().getFirst().equals(typeOwner(side,id,targetId.value())))throw EvolutionInput.corrupt();
                } else if(edge.target().state()!=KnowledgeGraph.TargetState.RESOLVED||!edge.target().identities().getFirst().equals(targetId.value()))
                    throw EvolutionInput.corrupt();
                if(proof.family().equals("java.occurrence")&&!OccurrenceIdentity.from(statement.identity(),span(raw.get("span")),
                        Math.toIntExact(number(raw.get("ordinal")))).value().equals(raw.get("identity")))throw EvolutionInput.corrupt();
            }
            if(proof.family().equals("java.occurrence")){
                occurrences++;
                supportLocation(side,id,supportPrefix,ref,raw.get("span"));
            }else{
                derived++;
                for(var span:list(raw.get("supportingSpans")))supportLocation(side,id,supportPrefix,ref,span);
            }
        }
        String locationDigest;
        var rows=new Rows(run.index,"locations",supportPrefix);
        try(var digest=new ArrayDigest()) {
            for(var item=rows.next();item!=null;item=rows.next())digest.add(item.value());
            locationDigest=digest.finish().value();
        }
        if(!resolved){openPopulations.add(side.name);run.unresolved();run.gap(side,id,"RELATION_PROOF_UNAVAILABLE",edge.evidence());}
        return row("identity",edge.identity(),"kind",edge.kind(),"source",edge.source(),"target",edge.target(),"context",id,
                "certain",resolved,"region",edge.region(),"occurrences",occurrences,"derivedSupports",derived,
                "locationDigest",locationDigest,"evidence",edge.evidence());
    }

    private String typeOwner(EvolutionInput side,String context,String entity)throws IOException {
        String prefix=side.name+"|"+context+"|";
        var declaration=run.index.get("java-symbols",prefix+entity).orElse(Map.of());
        for(int depth=0;depth<64;depth++) {
            if("TYPE".equals(declaration.get("kind")))return text(declaration,"identity");
            String canonical=maybe(declaration,"canonicalName"),stable=maybe(declaration,"stableScope");
            if(canonical==null||!canonical.startsWith("java:v1:")||stable==null)return null;
            var tuple=list(GraphJson.read(canonical.substring(8),GraphJson.Limits.defaults()));
            if(tuple.size()<2||!(tuple.get(1) instanceof List<?>))return null;
            String owner="java:v1:"+com.evolution.analysis.contract.serialization.CanonicalJson.write(tuple.get(1));
            declaration=run.index.get("owner-symbol",prefix+hash("evolution.java-owner",List.of(stable,owner)).value()).orElse(Map.of());
        }
        return null;
    }

    private static SourceSpan span(Object value) {
        var s=obj(value);
        return new SourceSpan(new SourceDocumentIdentity(text(s,"document")),Math.toIntExact(number(s.get("startLine"))),
                Math.toIntExact(number(s.get("startColumn"))),Math.toIntExact(number(s.get("endLine"))),Math.toIntExact(number(s.get("endColumn"))));
    }

    private void supportLocation(EvolutionInput side,String context,String prefix,ContentDigest ref,Object raw)throws IOException {
        var place=location(side,context,raw);
        if(place!=null)run.index.put("locations",prefix+hash("evolution.location",place).value()+"|"+ref.value(),place);
    }

    private Map<String,Object> location(EvolutionInput side,String context,Object raw)throws IOException {
        if(raw==null)return null;
        var span=obj(raw);var doc=run.index.get("documents",side.name+"|"+context+"|"+text(span,"document"))
                .orElseThrow(EvolutionInput::corrupt);
        return row("path",doc.get("path"),"classification",doc.get("classification"),"startLine",span.get("startLine"),
                "startColumn",span.get("startColumn"),"endLine",span.get("endLine"),"endColumn",span.get("endColumn"));
    }

    private void merge(String table)throws IOException {
        String lPrefix=prefix(left),rPrefix=prefix(right);
        var a=new Rows(run.index,table,lPrefix);var b=new Rows(run.index,table,rPrefix);
        while(a.head()!=null||b.head()!=null){
            EvolutionSnapshot.check(run.cancelled);
            String l=a.head()==null?null:a.head().key().substring(lPrefix.length());
            String r=b.head()==null?null:b.head().key().substring(rPrefix.length());
            int order=l==null?1:r==null?-1:l.compareTo(r);
            String key=order<=0?l:r;
            var x=order<=0?a.next().value():null;
            var y=order>=0?b.next().value():null;
            compareRow(table,key,x,y);
        }
    }

    private void compareRow(String table,String key,Map<String,Object> a,Map<String,Object> b)throws IOException {
        boolean entity=table.equals("entities");String family=entity?"ENTITY":"RELATION";
        String subject=hash("architecture.evolution.subject:v1",List.of(scope,family,key)).value();
        boolean certainLeft=isCertain(a),certainRight=isCertain(b);
        String disposition=a==null?"RIGHT_ONLY":b==null?"LEFT_ONLY":certainLeft&&certainRight?"MATCHED":"UNAVAILABLE";
        run.put(Section.CORRESPONDENCE,subject,row("subject",subject,"scope",scope,"family",family,"disposition",disposition,
                "left",a,"right",b,"method",disposition.equals("MATCHED")?
                        entity?"EXACT_SCOPED_IDENTITY":"DIRECTED_ENDPOINTS_AND_KIND":null));
        boolean sourceChanged=!left.header.snapshot().equals(right.header.snapshot());
        boolean leftAbsent=sourceChanged&&a==null&&lp.closed()&&!openPopulations.contains(left.name)&&compatible&&certainRight;
        boolean rightAbsent=sourceChanged&&b==null&&rp.closed()&&!openPopulations.contains(right.name)&&compatible&&certainLeft;
        var before=sideValue(left,lc,lp,a,leftAbsent,certainLeft);
        var after=sideValue(right,rc,rp,b,rightAbsent,certainRight);
        if(leftAbsent||rightAbsent){
            emit(subject,family,family+(leftAbsent?"_ADDED":"_REMOVED"),before,after,List.of());return;
        }
        if(a==null||b==null||!compatible||!certainLeft||!certainRight){
            if(a!=null&&b!=null&&withoutContext(a).equals(withoutContext(b)))return;
            run.unresolved();
            if(run.request.mode()==Mode.OBSERVATION)emit(subject,family,"OBSERVATION_CHANGED",before,after,List.of("NO_DEFINITE_DELTA"));
            return;
        }
        if(entity&&a.get("shape")!=null&&b.get("shape")!=null&&!Objects.equals(a.get("shape"),b.get("shape"))) {
            if(sourceChanged)emit(subject,family,"ENTITY_CHANGED",before,after,List.of("SEMANTIC_PAYLOAD"));
            else {run.unresolved();if(run.request.mode()==Mode.OBSERVATION)
                emit(subject,family,"OBSERVATION_CHANGED",before,after,List.of("SAME_SOURCE_CONFLICT"));}
        }
        var axes=new ArrayList<String>();
        if(!Objects.equals(a.get(entity?"location":"locationDigest"),b.get(entity?"location":"locationDigest")))axes.add("LOCATION");
        if(!entity&&(!Objects.equals(a.get("occurrences"),b.get("occurrences"))||!Objects.equals(a.get("derivedSupports"),b.get("derivedSupports"))))axes.add("MULTIPLICITY");
        if(!Objects.equals(a.get("evidence"),b.get("evidence"))||!Objects.equals(a.get("shapeEvidence"),b.get("shapeEvidence")))axes.add("EVIDENCE");
        if(!axes.isEmpty())emit(subject,family,"SUPPORT_CHANGED",before,after,axes);
    }

    private static boolean isCertain(Map<String,Object> row){return row!=null&&Boolean.TRUE.equals(row.get("certain"));}
    private static Map<String,Object> withoutContext(Map<String,Object> value){var copy=new TreeMap<>(value);copy.remove("context");return copy;}
    private static Map<String,Object> withCertainty(Map<String,Object> row,boolean certain){var result=new TreeMap<>(row);result.put("certain",certain);return result;}
    private static Map<String,Object> sideValue(EvolutionInput side,Map<String,Object> context,EvolutionInput.Coverage coverage,
                                                Map<String,Object> value,boolean absent,boolean certain) {
        return row("graph",side.graph,"context",context.get("context"),"truth",certain?"PRESENT":absent?"ABSENT":"UNKNOWN",
                "observation",value,"proof",absent?row("kind","CLOSED_SELECTED_JAVA_CENSUS","provider",EvolutionRun.PROVIDER,
                        "evidence",coverage.references()):null);
    }
    private void emit(String subject,String family,String kind,Map<String,Object> before,Map<String,Object> after,List<String> axes)throws IOException {
        String attribution=left.header.snapshot().equals(right.header.snapshot())?"SAME_SOURCE_OBSERVATION":
                compatible?"REPOSITORY_INPUT_CHANGED":"MIXED_OR_UNATTRIBUTED";
        var preimage=row("request",run.requestIdentity,"subject",subject,"scope",scope,"family",family,"kind",kind,
                "before",before,"after",after,"axes",axes,"attribution",attribution);
        var id=hash("architecture.evolution.event:v1",preimage);
        var event=new TreeMap<>(preimage);event.put("identity",id);
        run.put(Section.EVENTS,id.value(),event);
    }
}
