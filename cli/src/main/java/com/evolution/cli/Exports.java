package com.evolution.cli;

import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.backend.query.PublishedReports;
import com.evolution.backend.query.BoundedJson;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Format adapters retain complete canonical records and explicit bounded-result metadata. */
final class Exports {
    static byte[] sarif(PublishedReports.Report report){
        var evaluation=report.evaluation();var results=new ArrayList<Object>();
        for(var finding:evaluation.findings()){
            var rule=finding.rule();var bundle=finding.bundle();var row=new TreeMap<String,Object>();
            row.put("ruleId",rule.id());row.put("level",switch(rule.severity()){case ERROR->"error";case WARNING->"warning";case INFO->"note";});
            row.put("message",Map.of("text",rule.id()+": "+finding.classification()+" architecture finding in the modeled configuration space."));
            row.put("partialFingerprints",Map.of("evoFindingKey/v1",finding.key().value()));
            var locations=new ArrayList<Object>();
            for(var source:bundle.sources()){
                var span=source.span();locations.add(Map.of("physicalLocation",Map.of("artifactLocation",Map.of("uri","evo-source:"+span.document().value()),
                        "region",Map.of("startLine",span.startLine(),"startColumn",span.startColumn(),"endLine",span.endLine(),"endColumn",span.endColumn())),
                        "properties",Map.of("sourceDigest",source.sourceDigest(),"canonicalSpan",span)));
            }
            if(!locations.isEmpty())row.put("locations",locations);
            row.put("properties",Map.of("findingIdentity",finding.identity(),"bundleIdentity",bundle.identity(),"classification",finding.classification(),"canonicalFinding",finding));results.add(row);
        }
        var rules=evaluation.rules().stream().map(r->(Object)Map.of("id",r.rule().header().id(),"properties",Map.of("version",r.rule().header().version(),"canonicalRule",r.rule()))).toList();
        var run=new TreeMap<String,Object>();run.put("tool",Map.of("driver",Map.of("name","evo","version","0.1.0","rules",rules)));run.put("results",results);run.put("columnKind","utf16CodeUnits");
        run.put("invocations",List.of(Map.of("executionSuccessful",evaluation.status().name().equals("COMPLETE"))));
        run.put("properties",Map.of("graph",evaluation.graph(),"context",evaluation.context(),"evaluationIdentity",evaluation.identity(),"evaluationStatus",evaluation.status(),"canonicalEvaluation",evaluation,
                "denominatorMeaning","All evaluated rule rows retained, including NEVER and UNKNOWN; observed findings do not establish completeness.","sourceUriMeaning","evo-source URIs identify immutable canonical source documents, not host filesystem paths."));
        return BoundedJson.encode(Map.of("version","2.1.0","$schema","https://json.schemastore.org/sarif-2.1.0.json","runs",List.of(run)),32_000_000);
    }
    static byte[] graphml(Map<String,Object> envelope){
        var data=GraphJson.object(envelope.get("data"));var edges=GraphJson.array(data.get("items"));var nodes=new TreeSet<String>();
        for(Object edge:edges){var row=GraphJson.object(edge);nodes.add(GraphJson.string(row.get("source")));nodes.add(GraphJson.string(row.get("target")));}
        var xml=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\">\n");
        xml.append("<key id=\"canonical\" for=\"all\" attr.name=\"evo.canonical\" attr.type=\"string\"/>\n<graph id=\"evo\" edgedefault=\"directed\">\n<data key=\"canonical\">").append(escape(CanonicalJson.write(envelope))).append("</data>\n");
        for(String node:nodes)xml.append("<node id=\"").append(escape(node)).append("\"/>\n");
        for(Object edge:edges){var row=GraphJson.object(edge);xml.append("<edge id=\"").append(escape(GraphJson.string(row.get("identity")))).append("\" source=\"").append(escape(GraphJson.string(row.get("source")))).append("\" target=\"").append(escape(GraphJson.string(row.get("target")))).append("\"><data key=\"canonical\">").append(escape(CanonicalJson.write(row))).append("</data></edge>\n");}
        return xml.append("</graph>\n</graphml>\n").toString().getBytes(StandardCharsets.UTF_8);
    }
    private static String escape(String text){return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
    private Exports(){}
}
