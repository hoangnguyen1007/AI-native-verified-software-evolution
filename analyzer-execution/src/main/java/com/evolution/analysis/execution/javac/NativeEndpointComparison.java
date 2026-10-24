package com.evolution.analysis.execution.javac;

import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.graph.GraphJson;
import java.util.*;

/** Passive comparison of named JVM signatures at independently matched source sites.
 * This is deliberately narrower than canonical identity, binary shape or origin qualification.
 * In particular a classpath artifact cannot prove that a binary belongs to this project.
 */
final class NativeEndpointComparison {
    private NativeEndpointComparison(){}

    static Map<String,Object> compare(CapturedJavacReader.Capture capture,FrontendResult independent,
            Map<SourceDocumentIdentity,String> sourceUris,List<SourceInput> inputs){
        var nativeSites=new TreeMap<String,List<Map<String,Object>>>();
        for(var row:capture.observations())nativeSites.computeIfAbsent(site(GraphJson.string(row.get("source")),GraphJson.string(row.get("category")),row.get("span")),k->new ArrayList<>()).add(row);
        var sources=new HashMap<String,SourceInput>();inputs.forEach(s->sources.put(sourceUris.get(s.document().identity()),s));
        var independentSites=new HashMap<String,Long>();for(var observation:independent.observations())independentSites.merge(site(observation,sourceUris),1L,Math::addExact);
        var declarations=new HashMap<EntityIdentity,Entity>();independent.declarations().forEach(d->declarations.put(d.entity().identity(),d.entity()));
        var occurrences=new HashMap<OccurrenceIdentity,RelationshipOccurrence>();independent.occurrences().forEach(o->occurrences.put(o.identity(),o));
        var counts=new TreeMap<String,Long>();
        for(String key:List.of("comparedSites","targetSignatureAgreements","targetSignatureDisagreements","targetSignatureUnavailable",
                "callerSignatureAgreements","callerSignatureDisagreements","callerSignatureUnavailable","targetOriginDisagreements",
                "callerOriginDisagreements","originProofRequired","unmatchedOrDuplicateSites","nonResolvedSites"))counts.put(key,0L);
        var results=new ArrayList<Object>();
        for(var observation:independent.observations()){
            String key=site(observation,sourceUris);
            var rows=nativeSites.getOrDefault(key,List.of());
            if(rows.size()!=1||independentSites.get(key)!=1||observation.mappedOccurrence().isEmpty()){increment(counts,"unmatchedOrDuplicateSites");results.add(Map.of("site",key,"disposition",rows.size()!=1?"NATIVE_SITE_NOT_UNIQUE":independentSites.get(key)!=1?"CANONICAL_SITE_NOT_UNIQUE":"CANONICAL_OCCURRENCE_UNMAPPED"));continue;}
            var row=rows.getFirst();var occurrence=occurrences.get(observation.mappedOccurrence().orElseThrow());
            if(!"RESOLVED".equals(row.get("binding"))||occurrence.status()!=SemanticStatus.RESOLVED||!(occurrence.relationship().target() instanceof RelationshipTarget.Resolved)){
                increment(counts,"nonResolvedSites");results.add(Map.of("site",key,"disposition","NONRESOLVED_OR_RECOVERY_REQUIRES_ADJUDICATION"));continue;
            }
            increment(counts,"comparedSites");
            var target=declarations.get(((RelationshipTarget.Resolved)occurrence.relationship().target()).target());var caller=declarations.get(occurrence.relationship().source());
            var targetElement=GraphJson.object(row.get("target"));var callerElement=row.get("caller")==null?null:GraphJson.object(row.get("caller"));
            String targetSignature=signature(targetElement,target);
            // A native lexical scope is not interchangeable with its enclosing method/type.
            String callerSignature=GraphJson.array(row.get("enclosingScopes")).isEmpty()?signature(callerElement,caller):"UNAVAILABLE_LEXICAL_SCOPE";
            String targetOrigin=origin(targetElement,target,capture,sources);String callerOrigin=origin(callerElement,caller,capture,sources);
            tallySignature(counts,"target",targetSignature);tallySignature(counts,"caller",callerSignature);
            if(targetOrigin.startsWith("DISAGREES"))increment(counts,"targetOriginDisagreements");
            if(callerOrigin.startsWith("DISAGREES"))increment(counts,"callerOriginDisagreements");
            if(!targetOrigin.equals("MATCHED_SOURCE_CONTEXT")||!callerOrigin.equals("MATCHED_SOURCE_CONTEXT"))increment(counts,"originProofRequired");
            var checked=new TreeMap<String,Object>();checked.put("site",key);checked.put("targetSignature",targetSignature);checked.put("callerSignature",callerSignature);
            checked.put("targetOrigin",targetOrigin);checked.put("callerOrigin",callerOrigin);checked.put("canonicalTarget",target.identity());checked.put("canonicalCaller",caller.identity());
            checked.put("nativeTarget",CanonicalJson.sha256(targetElement));checked.put("nativeCaller",callerElement==null?null:CanonicalJson.sha256(callerElement));
            results.add(Collections.unmodifiableMap(checked));
        }
        var report=new TreeMap<String,Object>();report.put("schema","java-native-endpoint-comparison:m8.4-v1");report.putAll(counts);
        report.put("sites",List.copyOf(results));report.put("semanticAttestation",false);
        report.put("limitations",List.of("Named JVM signature agreement does not prove complete canonical identity or return/field type shape.",
                "Local/anonymous symbols, parameter indices and lexical scopes require explicit source correspondence.",
                "External artifact ownership, exact JDK/dependency scope, transform lineage and authenticated predicate checks remain separate."));
        return Collections.unmodifiableMap(report);
    }
    private static String site(ObservationRecord observation,Map<SourceDocumentIdentity,String> sourceUris){
        Object position=observation.span().map(s->(Object)Map.of("startLine",s.startLine(),"startColumn",s.startColumn(),"endLine",s.endLine(),"endColumn",s.endColumn())).orElse(null);
        return site(sourceUris.get(observation.document()),observation.category().value(),position);
    }
    private static String site(String source,String category,Object value){
        var result=new TreeMap<String,Object>();result.put("source",source);result.put("category",category);
        if(value==null)result.put("span",null);else{var span=GraphJson.object(value);for(String field:List.of("startLine","startColumn","endLine","endColumn"))result.put(field,span.get(field));}
        return CanonicalJson.write(result);
    }
    private static void increment(Map<String,Long> counts,String key){counts.compute(key,(k,v)->Math.addExact(v,1L));}
    private static void tallySignature(Map<String,Long> counts,String role,String result){increment(counts,role+"Signature"+(result.equals("AGREES_NAMED_JVM_SIGNATURE")?"Agreements":result.equals("DISAGREES_NAMED_JVM_SIGNATURE")?"Disagreements":"Unavailable"));}
    private static String origin(Map<String,Object> nativeElement,Entity entity,CapturedJavacReader.Capture capture,Map<String,SourceInput> sources){
        if(nativeElement==null||entity==null)return "ORIGIN_MAPPING_REQUIRED";
        if(mandatedSourceConstructor(nativeElement))return "CONSTRUCTOR_CORRESPONDENCE_REQUIRED";
        String artifact=(String)nativeElement.get("artifact");var source=sources.get(artifact);
        if(source!=null){
            if(entity.origin()!=EntityOrigin.PROJECT||!entity.stableScope().equals(EntityScope.project(source.document().module())))return "DISAGREES_SOURCE_CONTEXT";
            if(entity.declaration().isPresent()&&!entity.declaration().orElseThrow().document().equals(source.document().identity()))return "DISAGREES_SOURCE_DOCUMENT";
            return "MATCHED_SOURCE_CONTEXT";
        }
        if(artifact==null)return "ARTIFACT_BINDING_REQUIRED";
        var bound=capture.artifacts().get(artifact);
        if(bound==null||!bound.bytesBound())return "ARTIFACT_BINDING_REQUIRED";
        if(artifact.startsWith("jrt:/"))return entity.origin()==EntityOrigin.JDK?"JDK_SCOPE_PROOF_REQUIRED":"DISAGREES_JDK_ORIGIN";
        return "PROJECT_OWNERSHIP_OR_DEPENDENCY_SCOPE_REQUIRED";
    }
    private record Key(String kind,String owner,String name,List<String> parameters){}
    private static String signature(Map<String,Object> nativeElement,Entity entity){
        if(nativeElement==null||entity==null)return "UNAVAILABLE_ELEMENT";
        if(mandatedSourceConstructor(nativeElement))return "UNAVAILABLE_CONSTRUCTOR_CORRESPONDENCE";
        Key nativeKey,canonicalKey;
        try{nativeKey=nativeKey(nativeElement);}catch(IllegalArgumentException|IndexOutOfBoundsException malformed){return "UNAVAILABLE_NATIVE_SIGNATURE_FORMAT";}
        try{canonicalKey=canonicalKey(entity);}catch(IllegalArgumentException|IndexOutOfBoundsException|ArithmeticException malformed){return "UNAVAILABLE_CANONICAL_SIGNATURE_FORMAT";}
        if(nativeKey==null||canonicalKey==null)return "UNAVAILABLE_SYMBOL_MAPPING";
        return nativeKey.equals(canonicalKey)?"AGREES_NAMED_JVM_SIGNATURE":"DISAGREES_NAMED_JVM_SIGNATURE";
    }
    private static boolean mandatedSourceConstructor(Map<String,Object> element){
        return "CONSTRUCTOR".equals(element.get("kind"))&&"MANDATED".equals(element.get("modelOrigin"))&&element.get("declarationSpan")==null;
    }
    private static Key nativeKey(Map<String,Object> element){
        String kind=GraphJson.string(element.get("kind")),owner=(String)element.get("owner"),name=GraphJson.string(element.get("name"));
        if(owner==null)return null;
        return switch(kind){
            case "CLASS","INTERFACE","ENUM","ANNOTATION_TYPE","RECORD"->new Key("TYPE",owner,"",List.of());
            case "PACKAGE"->new Key("PACKAGE",owner,"",List.of());
            case "METHOD","CONSTRUCTOR"->{String descriptor=(String)element.get("descriptor");yield descriptor==null?null:new Key(kind,owner,kind.equals("CONSTRUCTOR")?"<init>":name,parameters(descriptor));}
            case "FIELD","ENUM_CONSTANT"->new Key("FIELD",owner,name,List.of());
            default->null;
        };
    }
    private static Key canonicalKey(Entity entity){
        if(!entity.canonicalName().startsWith("java:v1:"))return null;
        var tuple=GraphJson.array(GraphJson.read(entity.canonicalName().substring(8),new GraphJson.Limits(100_000,64,100_000)));
        if(tuple.isEmpty())return null;
        String tag=GraphJson.string(tuple.getFirst());
        return switch(tag){
            case "type","member-type"->{String owner=binary(tuple);yield entity.kind()==EntityKind.TYPE&&owner!=null?new Key("TYPE",owner,"",List.of()):null;}
            case "package"->entity.kind()==EntityKind.PACKAGE?new Key("PACKAGE",parts(tuple.get(1),"."),"",List.of()):null;
            case "method","constructor"->{boolean constructor=tag.equals("constructor");if(entity.kind()!=(constructor?EntityKind.CONSTRUCTOR:EntityKind.METHOD))yield null;
                String owner=binary(GraphJson.array(tuple.get(1)));if(owner==null)yield null;
                var parameters=new ArrayList<String>();for(Object value:GraphJson.array(tuple.get(constructor?2:3))){String type=descriptor(GraphJson.array(value));if(type==null)yield null;parameters.add(type);}
                yield new Key(constructor?"CONSTRUCTOR":"METHOD",owner,constructor?"<init>":decode(GraphJson.string(tuple.get(2))),List.copyOf(parameters));}
            case "field"->{String owner=binary(GraphJson.array(tuple.get(1)));yield entity.kind()==EntityKind.FIELD&&owner!=null?new Key("FIELD",owner,decode(GraphJson.string(tuple.get(2))),List.of()):null;}
            default->null;
        };
    }
    private static String binary(List<Object> tuple){
        if(tuple.isEmpty())return null;
        return switch(GraphJson.string(tuple.getFirst())){
            case "type"->{String pkg=parts(tuple.get(1),".");yield (pkg.isEmpty()?"":pkg+".")+decode(GraphJson.string(tuple.get(2)));}
            case "member-type"->{String owner=binary(GraphJson.array(tuple.get(1)));yield owner==null?null:owner+"$"+decode(GraphJson.string(tuple.get(2)));}
            default->null;
        };
    }
    private static String parts(Object value,String separator){return String.join(separator,GraphJson.array(value).stream().map(GraphJson::string).map(NativeEndpointComparison::decode).toList());}
    private static String decode(String value){
        var result=new StringBuilder();for(int at=0;at<value.length();){if(value.charAt(at)=='%'){if(at+7>value.length())throw new IllegalArgumentException("NATIVE_COMPARISON_IDENTIFIER_ENCODING");result.appendCodePoint(Integer.parseInt(value.substring(at+1,at+7),16));at+=7;}else result.append(value.charAt(at++));}return result.toString();
    }
    private static String descriptor(List<Object> tuple){
        if(tuple.isEmpty())return null;
        return switch(GraphJson.string(tuple.getFirst())){
            case "primitive"->switch(GraphJson.string(tuple.get(1))){case "boolean"->"Z";case "byte"->"B";case "short"->"S";case "char"->"C";case "int"->"I";case "long"->"J";case "float"->"F";case "double"->"D";default->null;};
            case "declared"->{String name=binary(GraphJson.array(tuple.get(1)));yield name==null?null:"L"+name.replace('.','/')+";";}
            case "array"->{if(!(tuple.get(2) instanceof java.math.BigDecimal count))yield null;int rank=count.intValueExact();String component=descriptor(GraphJson.array(tuple.get(1)));yield rank<1||rank>255||component==null?null:"[".repeat(rank)+component;}
            default->null;
        };
    }
    private static List<String> parameters(String descriptor){
        if(!descriptor.startsWith("("))throw new IllegalArgumentException("NATIVE_COMPARISON_DESCRIPTOR");
        var result=new ArrayList<String>();int at=1;
        while(at<descriptor.length()&&descriptor.charAt(at)!=')'){
            int start=at;while(at<descriptor.length()&&descriptor.charAt(at)=='[')at++;
            if(at>=descriptor.length())throw new IllegalArgumentException("NATIVE_COMPARISON_DESCRIPTOR");char token=descriptor.charAt(at++);
            if(token=='L'){int end=descriptor.indexOf(';',at);if(end<at+1)throw new IllegalArgumentException("NATIVE_COMPARISON_DESCRIPTOR");at=end+1;}
            else if("ZBSCIJFD".indexOf(token)<0)throw new IllegalArgumentException("NATIVE_COMPARISON_DESCRIPTOR");
            result.add(descriptor.substring(start,at));
        }
        if(at>=descriptor.length()-1||descriptor.charAt(at)!=')')throw new IllegalArgumentException("NATIVE_COMPARISON_DESCRIPTOR");return List.copyOf(result);
    }
}
