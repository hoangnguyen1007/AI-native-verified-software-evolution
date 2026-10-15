package com.evolution.analysis.metrics;

import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.graph.*;
import java.math.BigDecimal;
import java.util.*;
import static com.evolution.analysis.metrics.MetricEvidence.*;

/** Inventory and coverage retain unknown rows; neither is a health or ground-truth accuracy score. */
final class InventoryMetrics {
    final MetricEvidence input;
    InventoryMetrics(MetricEvidence input){this.input=input;}
    ArchitectureMetric.Result inventory(String subject){
        var kinds=new TreeMap<String,Long>();var origins=new TreeMap<String,Long>();var roles=new TreeMap<String,Long>();long total=0,nested=0,local=0,anonymous=0;
        for(var declaration:input.declarations.values())if(input.includes(declaration)){
            if(!input.spend(1))break;total++;increment(kinds,declaration.kind());increment(origins,declaration.origin());
            var document=input.documents.get(declaration.document());increment(roles,document==null?"NO_SOURCE_EVIDENCE":document.role());input.references.add(declaration.evidence());
            if(declaration.kind().equals("TYPE")){
                if(declaration.canonicalName().startsWith("java:v1:[\"member-type\","))nested++;
                if(declaration.canonicalName().startsWith("java:v1:[\"local-type\",")){local++;if(declaration.canonicalName().endsWith(",\"anonymous\",\"\"]"))anonymous++;}
            }
        }
        var files=new TreeMap<String,Long>();input.documents.values().stream().filter(input::includesDocument).forEach(d->increment(files,d.role()));
        var metadata=new TreeMap<String,Long>();
        for(var node:input.nodes.values())if(Set.of("MODULE","SOURCE_ROOT","PACKAGE","ANNOTATION_USE","TYPE_USE").contains(node.kind()))increment(metadata,node.kind());
        var details=new TreeMap<String,Object>(Map.of("declarationsByKind",kinds,"declarationsByOrigin",origins,"declarationsBySourceRole",roles,"sourceFilesByRole",files,
                "evidencedMetadataCounts",metadata,"nestedTypes",nested,"localTypesIncludingAnonymous",local,"anonymousTypes",anonymous,
                "interpretation","Context snapshot inventory; metadata counts are separate from declarations, and do not assert extraction completeness."));
        var statuses=new TreeMap<String,Long>();input.declarations.values().stream().filter(input::includes).forEach(d->increment(statuses,d.status()));details.put("declarationsBySemanticStatus",statuses);
        var relationships=new TreeMap<String,Long>();
        for(var edge:input.graph.edges())if(edge.context().equals(input.request.context())){if(!input.spend(1))break;increment(relationships,edge.kind()+":"+edge.target().state());}
        details.put("contextRelationshipRowsByKindAndTargetState",relationships);
        var modules=new HashSet<String>();input.nodes.values().stream().filter(n->n.kind().equals("MODULE")).map(KnowledgeGraph.Node::identity)
                .filter(id->input.request.population().modules().isEmpty()||input.request.population().modules().contains(id)).forEach(modules::add);
        var dependencies=new TreeMap<String,Long>();var roots=new TreeMap<String,Long>();var rootStates=new TreeMap<String,Long>();long buildModules=0,unknownCoordinates=0;
        for(var evidence:input.graph.evidence())if(evidence.family().equals("build.module")){
            var row=input.read(evidence);String owner=text(object(row.get("descriptor")),"identity");if(!modules.contains(owner))continue;buildModules++;input.references.add(evidence.identity());
            for(Object value:array(row.get("dependencies"))){if(!input.spend(1))break;var dep=object(value);increment(dependencies,text(dep,"configuration"));if(dep.get("coordinate")==null&&dep.get("projectPath")==null)unknownCoordinates++;}
            for(Object value:array(object(row.get("sourcePlan")).get("sourceSets"))){var set=object(value);String role=text(set,"semanticRole");
                for(Object item:array(set.get("sourceRoots"))){if(!input.spend(1))break;var root=object(item);increment(rootStates,text(root,"status"));if(root.get("value") instanceof String)increment(roots,role);else input.gap("SOURCE_ROOT_UNAVAILABLE");}}
        }
        details.put("buildModulesWithEvidence",buildModules);details.put("declaredDependenciesByConfiguration",dependencies);details.put("dependenciesWithoutResolvedCoordinateOrProjectPath",unknownCoordinates);
        details.put("declaredSourceRootsByRole",roots);details.put("sourceRootSettingStates",rootStates);
        if(!modules.isEmpty()&&buildModules<modules.size())input.gap("BUILD_INVENTORY_UNAVAILABLE");
        return input.count(subject,total,details,Optional.empty());
    }
    ArchitectureMetric.Result quality(String subject){
        var categories=new TreeMap<String,Map<String,Long>>();var statuses=new TreeMap<String,Long>();var origins=new TreeMap<String,Long>();
        var parse=new TreeMap<String,Long>();var gaps=new TreeMap<String,Long>();var attempts=new TreeMap<String,Long>();long spans=0,observations=0,mapped=0;
        var frontend=frontend();
        if(frontend.isPresent()){
            for(Object row:array(frontend.orElseThrow().get("coverage"))){var value=object(row);String kind=text(value,"category");
                if(kind.isEmpty())kind=text(object(value.get("category")),"value");
                if(kind.isEmpty()||categories.containsKey(kind)){input.gap("INVALID_COVERAGE_CATEGORY");continue;}
                try{long attempted=number(value.get("attempted")),emitted=number(value.get("emitted")),unmapped=number(value.get("unmappable"));
                    if(attempted<0||emitted<0||unmapped<0||attempted-emitted!=unmapped)throw new IllegalArgumentException("Invalid coverage operands");
                    categories.put(kind,Map.of("attempted",attempted,"emitted",emitted,"unmapped",unmapped));if(unmapped>0||!"IMPLEMENTED".equals(value.get("support")))input.gap("CATEGORY_COVERAGE_INCOMPLETE");
                }catch(IllegalArgumentException|ArithmeticException invalid){input.gap("INVALID_COVERAGE_OPERANDS");}}
            for(Object row:array(frontend.orElseThrow().get("sources"))){var value=object(row);String doc=text(value,"document");
                if(input.documents.containsKey(doc)&&input.includesDocument(input.documents.get(doc))){increment(parse,text(value,"state"));if(!"PROCESSED".equals(value.get("state")))input.gap("SOURCE_PARSE_INCOMPLETE");}}
        }else input.gap("QUALITY_COVERAGE_UNAVAILABLE");
        for(var node:input.nodes.values())for(var ref:node.evidence()){
            var evidence=input.graph.evidence(ref).orElseThrow();String family=evidence.family();
            if(!Set.of("java.observation","evidence.gap","evidence.attempt","evidence.conflict").contains(family))continue;var row=input.read(evidence);
            if(family.equals("java.observation")){
                var doc=input.documents.get(text(row,"document"));if(doc!=null&&!input.includesDocument(doc))continue;
                observations++;increment(statuses,text(row,"attribution"));increment(origins,text(row,"origin"));
                if(row.get("mappedOccurrence") instanceof String)mapped++;if(row.get("span") instanceof Map<?,?>)spans++;
            }else if(family.equals("evidence.gap"))increment(gaps,text(row,"reasonCode"));else if(family.equals("evidence.attempt"))increment(attempts,text(row,"outcome"));
            else increment(gaps,"PROVIDER_CONFLICT");input.references.add(ref);
        }
        var detail=new TreeMap<String,Object>();detail.put("categories",categories);detail.put("observationsBySemanticStatus",statuses);detail.put("observationsByOriginEvidenceState",origins);
        detail.put("sourceOutcomes",parse);detail.put("gapReasons",gaps);detail.put("acquisitionAttemptOutcomes",attempts);detail.put("attemptedObservationRows",observations);
        detail.put("emittedObservationRows",mapped);detail.put("unmappedObservationRows",observations-mapped);detail.put("fullSpanRows",spans);
        detail.put("spanRatioOperands",Map.of("numerator",spans,"denominator",observations));detail.put("classpathCompleteness","Manifest-bound inputs; missing-classpath gaps remain explicit, not inferred complete from parsing.");
        detail.put("accuracy","NOT_MEASURED_WITHOUT_GROUND_TRUTH");return input.count(subject,observations,detail,Optional.empty());
    }
    Optional<Map<String,Object>> frontend(){
        var analysis=input.graph.contexts().stream().filter(c->c.identity().equals(input.request.context())).findFirst().orElseThrow().analysis();if(analysis.isEmpty())return Optional.empty();
        for(var evidence:input.graph.evidence())if(Set.of("frontend.result","frontend.result-index").contains(evidence.family())&&evidence.canonicalJson().contains("\"analysis\":\""+analysis.orElseThrow().value()+"\"")){
            var row=input.read(evidence);if(analysis.orElseThrow().value().equals(row.get("analysis"))){input.references.add(evidence.identity());return Optional.of(row);}}
        return Optional.empty();
    }
    ArchitectureMetric.Result loc(String subject){
        var remaining=new TreeSet<String>();input.documents.values().stream().filter(input::includesDocument).forEach(d->remaining.add(d.identity()));
        long lines=0,decoded=0;var perFile=new TreeMap<String,Long>();
        for(var evidence:input.graph.evidence())if(evidence.family().equals("source.bytes")){
            if(input.exhausted)break;var row=input.read(evidence);var document=object(row.get("document"));String identity=text(document,"identity");
            if(!remaining.contains(identity))continue;
            try {
                var expected=input.documents.get(identity);
                if(expected.digest().isEmpty()||!expected.digest().orElseThrow().value().equals(document.get("contentDigest"))){input.gap("SOURCE_CONTENT_DIGEST_CONFLICT");continue;}
                String encoded=text(row,"bytesBase64");long estimate=((long)encoded.length()+3)/4*3;
                if(estimate>input.limits.maxSourceBytes()-input.sourceBytes+2){input.gap("SOURCE_BYTE_LIMIT");input.exhausted=true;break;}
                var bytes=Base64.getDecoder().decode(encoded);if(bytes.length>input.limits.maxSourceBytes()-input.sourceBytes){input.gap("SOURCE_BYTE_LIMIT");input.exhausted=true;break;}input.sourceBytes+=bytes.length;
                var sourceDoc=new SourceDocument(new SourceDocumentIdentity(identity),new RepositoryIdentity(text(document,"repository")),new ModuleIdentity(text(document,"module")),
                        text(document,"path"),new ContentDigest(text(document,"contentDigest")),SourceClassification.valueOf(text(document,"classification")));
                var decoding=object(row.get("decoding"));var endings=object(decoding.get("lineEndings"));
                var proofs=array(decoding.get("evidence")).stream().map(e->{var p=object(e);return new SourceInput.Evidence(text(p,"logicalId"),new ContentDigest(text(p,"digest")));}).toList();
                var policy=new SourceInput.Decoding(text(decoding,"schemaVersion"),text(decoding,"charset"),SourceInput.EncodingOrigin.valueOf(text(decoding,"origin")),proofs,
                        SourceInput.Bom.valueOf(text(decoding,"bom")),new SourceInput.LineEndings(SourceInput.LineEndingKind.valueOf(text(endings,"kind")),Math.toIntExact(number(endings.get("lf"))),Math.toIntExact(number(endings.get("crlf"))),Math.toIntExact(number(endings.get("cr")))));
                var source=new SourceInput(sourceDoc,bytes,policy);long count=physicalLines(source.text());lines+=count;decoded++;remaining.remove(identity);input.references.add(evidence.identity());
                if(perFile.size()<input.limits.maxDetailRows())perFile.put(identity,count);
            }catch(IllegalArgumentException|ArithmeticException invalid){input.gap("SOURCE_DECODING_OR_INTEGRITY_FAILED");}
        }
        if(!remaining.isEmpty())input.gap("SOURCE_BYTES_UNAVAILABLE");
        var detail=Map.<String,Object>of("decodedFiles",decoded,"missingFiles",remaining.size(),"perFile",perFile,"detailsTruncated",decoded>perFile.size(),"unitMeaning","Observed decoded physical lines; unavailable files never count as zero.");
        if(decoded==0&&!remaining.isEmpty())return input.absent(subject,AssessmentStatus.WITHHELD,"NO_DECODABLE_SOURCE_EVIDENCE",detail,Optional.empty(),Optional.empty());
        return input.count(subject,lines,detail,Optional.empty());
    }
    static long physicalLines(String text){
        if(text.isEmpty())return 0;long lines=0;for(int i=0;i<text.length();i++){
            char c=text.charAt(i);if(c=='\r'){lines++;if(i+1<text.length()&&text.charAt(i+1)=='\n')i++;}else if(c=='\n')lines++;}
        char last=text.charAt(text.length()-1);return lines+(last=='\n'||last=='\r'?0:1);
    }
    static List<Object> array(Object value){return value instanceof List<?>?GraphJson.array(value):List.of();}
    static long number(Object value){return value instanceof BigDecimal n?n.longValueExact():value instanceof Number n?n.longValue():0;}
    static void increment(Map<String,Long> map,String key){map.merge(key.isEmpty()?"UNKNOWN":key,1L,Long::sum);}
}
