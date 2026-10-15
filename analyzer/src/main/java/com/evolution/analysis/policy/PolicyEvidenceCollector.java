package com.evolution.analysis.policy;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.SourceDocumentIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.SourceSpan;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.query.ArchitectureQuery.EdgeView;
import java.util.*;
import static com.evolution.analysis.policy.PolicyEvaluation.*;

/** Bounded extraction of neutral provenance payloads. Raw source bytes/text never enter a bundle. */
final class PolicyEvidenceCollector {
    record Collected(List<Support> supports,List<SourceEvidence> sources,List<EvidenceReference> references,List<CapabilityGapRecord> gaps) {
        boolean complete(){return gaps.isEmpty();}
    }
    private final KnowledgeGraph graph;
    private final String context;
    private final Limits limits;
    private final EvidenceContext evidenceContext;
    private final Map<String,KnowledgeGraph.Node> nodes=new HashMap<>();
    private final Map<String,KnowledgeGraph.Node> declarations=new HashMap<>();
    private final Map<String,KnowledgeGraph.Edge> edges=new HashMap<>();
    private final Map<String,ContentDigest> documents=new HashMap<>();
    private final Set<String> conflictingDocuments=new HashSet<>();
    private final Map<ContentDigest,Map<String,Object>> payloads=new HashMap<>();
    private final boolean inputComplete;
    private int reads,visits;
    private long characters;
    PolicyEvidenceCollector(KnowledgeGraph graph,String context,Limits limits,EvidenceContext evidenceContext){
        this.graph=graph;this.context=context;this.limits=limits;this.evidenceContext=evidenceContext;
        inputComplete=(long)graph.nodes().size()+graph.edges().size()<=limits.maxInputItems();
        if(!inputComplete)return;
        for(var node:graph.nodes()){
            if(node.context().equals(context))nodes.put(node.identity(),node);
            if(node.context().equals(evidenceContext.analysisIdentity().map(a->a.value()).orElse(context))
                    &&node.evidence().stream().anyMatch(r->graph.evidence(r).orElseThrow().family().equals("java.declaration")))declarations.put(node.identity(),node);
            if(node.kind().equals("SOURCE_DOCUMENT"))for(var ref:node.evidence()){
                var evidence=graph.evidence(ref).orElseThrow();if(!evidence.family().equals("source.document"))continue;
                var value=read(evidence);
                try{
                    if(!node.identity().equals(value.get("identity")))continue;
                    var digest=new ContentDigest(GraphJson.string(value.get("contentDigest")));
                    var old=documents.putIfAbsent(node.identity(),digest);
                    if(old!=null&&!old.equals(digest))conflictingDocuments.add(node.identity());
                }catch(IllegalArgumentException invalid){conflictingDocuments.add(node.identity());}
            }
        }
        for(var edge:graph.edges())if(edge.context().equals(context))edges.put(edge.identity(),edge);
    }
    Collected collect(List<EdgeView> path){
        var reasons=new TreeSet<String>();var supports=new TreeMap<String,Support>();
        var sources=new TreeMap<String,SourceEvidence>();var references=new TreeMap<ContentDigest,EvidenceReference>();
        if(!inputComplete)reasons.add("EVIDENCE_INPUT_LIMIT");
        else for(var logical:path){
            if(logical.supportsTruncated())reasons.add("SUPPORTS_INCOMPLETE");
            for(String id:logical.supports()){
                var raw=edges.get(id);
                if(raw==null||raw.target().identities().size()!=1){reasons.add("SUPPORT_UNAVAILABLE");continue;}
                var statuses=new TreeSet<String>();var derivations=new TreeSet<String>();var supportSources=new TreeMap<String,SourceEvidence>();
                for(var ref:raw.evidence())inspect(ref,references,supportSources,statuses,derivations,reasons,0);
                // Spring truth edges cite region facts. Their owner-qualified injection descriptor and producer
                // carry the declaration evidence, without manufacturing a span for the derived relation.
                if(raw.kind().startsWith("spring."))for(String endpoint:List.of(raw.source(),raw.target().identities().getFirst())){
                    var node=nodes.get(endpoint);if(node!=null)for(var ref:node.evidence())inspect(ref,references,supportSources,statuses,derivations,reasons,0);
                }
                if(supportSources.isEmpty())reasons.add("SOURCE_EVIDENCE_UNAVAILABLE");
                sources.putAll(supportSources);
                supports.put(id,new Support(id,raw.source(),raw.target().identities().getFirst(),raw.kind(),raw.region(),
                        raw.evidence().stream().limit(limits.maxEvidenceReads()).toList(),List.copyOf(statuses),List.copyOf(derivations)));
                if(raw.evidence().size()>limits.maxEvidenceReads())reasons.add("EVIDENCE_LIMIT");
            }
        }
        var gaps=reasons.stream().map(r->PolicyGaps.create(evidenceContext,EVIDENCE_VERSION,graph.identity(),r,
                List.of(context,path.stream().map(EdgeView::identity).toList(),limits))).toList();
        return new Collected(List.copyOf(supports.values()),List.copyOf(sources.values()),List.copyOf(references.values()),gaps);
    }
    private void inspect(ContentDigest ref,Map<ContentDigest,EvidenceReference> references,Map<String,SourceEvidence> sources,
                         Set<String> statuses,Set<String> derivations,Set<String> reasons,int depth){
        if(depth>8){reasons.add("EVIDENCE_LIMIT");return;}
        var evidence=graph.evidence(ref).orElse(null);if(evidence==null){reasons.add("EVIDENCE_UNAVAILABLE");return;}
        var value=read(evidence);if(value.isEmpty()){reasons.add("EVIDENCE_LIMIT_OR_INVALID");return;}
        references.put(ref,new EvidenceReference(ref,evidence.family()));
        visit(value,ref,sources,evidence.family().startsWith("java.")?statuses:new TreeSet<>(),derivations,reasons,0);
        if(evidence.family().startsWith("spring.")){
            var declared=new TreeSet<String>();declarationReferences(value,declared,reasons,0);
            for(String id:declared){var node=declarations.get(id);
                if(node!=null)for(var proof:node.evidence())inspect(proof,references,sources,statuses,derivations,reasons,depth+1);
            }
        }
    }
    private Map<String,Object> read(GraphEvidence evidence){
        var cached=payloads.get(evidence.identity());if(cached!=null)return cached;
        if(reads>=limits.maxEvidenceReads())return Map.of();reads++;
        if(evidence.canonicalJson().length()>limits.maxEvidenceCharacters()-characters)return Map.of();
        characters+=evidence.canonicalJson().length();
        try{
            var value=GraphJson.object(GraphJson.read(evidence.canonicalJson(),new GraphJson.Limits(1_000_000,64,20_000)));
            payloads.put(evidence.identity(),value);return value;
        }catch(IllegalArgumentException invalid){return Map.of();}
    }
    private void visit(Object value,ContentDigest ref,Map<String,SourceEvidence> sources,Set<String> statuses,Set<String> derivations,Set<String> reasons,int depth){
        if(depth>64||visits++>=limits.maxInputItems()){reasons.add("EVIDENCE_TRAVERSAL_LIMIT");return;}
        if(value instanceof Map<?,?> map){
            if(map.get("status") instanceof String status)statuses.add(status);
            if(map.get("derivation") instanceof String derivation)derivations.add(derivation);
            if(map.get("derivation") instanceof Map<?,?> derivation&&derivation.get("kind") instanceof String kind)derivations.add(kind);
            if(map.get("derivationKind") instanceof String derivation)derivations.add(derivation);
            if(map.containsKey("startLine")&&map.containsKey("endColumn")){
                try{
                    var document=new SourceDocumentIdentity(GraphJson.string(map.get("document")));
                    var span=new SourceSpan(document,integer(map.get("startLine")),integer(map.get("startColumn")),integer(map.get("endLine")),integer(map.get("endColumn")));
                    var digest=documents.get(document.value());
                    if(digest==null||conflictingDocuments.contains(document.value()))reasons.add("SOURCE_DIGEST_UNAVAILABLE");
                    else {var source=new SourceEvidence(span,digest,ref);sources.put(CanonicalJson.write(source),source);}
                }catch(IllegalArgumentException invalid){reasons.add("SOURCE_SPAN_INVALID");}
            }
            if(map.get("kind") instanceof String kind&&kind.equals("SOURCE")&&map.get("document") instanceof String document&&map.get("rawDigest") instanceof String digest){
                if(!digest.equals(Optional.ofNullable(documents.get(document)).map(ContentDigest::value).orElse("")))reasons.add("SOURCE_DIGEST_MISMATCH");
            }
            for(Object child:map.values())if(child instanceof Map<?,?>||child instanceof List<?>)visit(child,ref,sources,statuses,derivations,reasons,depth+1);
        }else if(value instanceof List<?> list)for(Object child:list)visit(child,ref,sources,statuses,derivations,reasons,depth+1);
    }
    private void declarationReferences(Object value,Set<String> result,Set<String> reasons,int depth){
        if(depth>64||visits++>=limits.maxInputItems()){reasons.add("EVIDENCE_TRAVERSAL_LIMIT");return;}
        if(value instanceof Map<?,?> map){
            for(String key:List.of("siteSlot","ownerDeclarationKey","declarationSlot"))if(map.get(key) instanceof String id&&declarations.containsKey(id))result.add(id);
            for(Object child:map.values())if(child instanceof Map<?,?>||child instanceof List<?>)declarationReferences(child,result,reasons,depth+1);
        }else if(value instanceof List<?> list)for(Object child:list)declarationReferences(child,result,reasons,depth+1);
    }
    private static int integer(Object value){try{return new java.math.BigDecimal(value.toString()).intValueExact();}
        catch(ArithmeticException|NullPointerException invalid){throw new IllegalArgumentException("Invalid source coordinate");}}
}
