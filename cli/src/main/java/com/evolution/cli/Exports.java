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
    static byte[] cytoscape(Map<String,Object> envelope){
        var data=GraphJson.object(envelope.get("data"));
        if(data.containsKey("nodes")&&data.containsKey("edges")){
            var cy=new LinkedHashMap<String,Object>();
            cy.put("format","cytoscape.js");
            var elements=new LinkedHashMap<String,Object>();
            var rawNodes=GraphJson.array(data.get("nodes"));
            var rawEdges=GraphJson.array(data.get("edges"));
            var cyNodes=new ArrayList<Map<String,Object>>();
            for(Object n:rawNodes){
                var row=GraphJson.object(n);
                var nodeData=new TreeMap<String,Object>();
                nodeData.put("id",row.get("id"));
                nodeData.put("label",row.get("label"));
                nodeData.put("kind",row.get("kind"));
                nodeData.put("scope",row.get("scope"));
                if(row.get("parent")!=null)nodeData.put("parent",row.get("parent"));
                if(row.get("origin")!=null)nodeData.put("origin",row.get("origin"));
                if(row.get("sourceSet")!=null)nodeData.put("sourceSet",row.get("sourceSet"));
                nodeData.put("violationCount",row.get("violationCount"));
                if(row.get("worstSeverity")!=null)nodeData.put("worstSeverity",row.get("worstSeverity"));
                nodeData.put("isCycle",row.get("isCycleParticipant"));
                if(row.get("properties")!=null)nodeData.put("properties",row.get("properties"));
                cyNodes.add(Map.of("data",nodeData));
            }
            var cyEdges=new ArrayList<Map<String,Object>>();
            for(Object e:rawEdges){
                var row=GraphJson.object(e);
                var edgeData=new TreeMap<String,Object>();
                edgeData.put("id",row.get("id"));
                edgeData.put("source",row.get("source"));
                edgeData.put("target",row.get("target"));
                edgeData.put("kind",row.get("kind"));
                edgeData.put("classification",row.get("classification"));
                edgeData.put("isViolation",row.get("isViolation"));
                edgeData.put("isCycle",row.get("isCycle"));
                edgeData.put("supportCount",row.get("supportCount"));
                if(row.get("properties")!=null)edgeData.put("properties",row.get("properties"));
                cyEdges.add(Map.of("data",edgeData));
            }
            elements.put("nodes",cyNodes);
            elements.put("edges",cyEdges);
            cy.put("elements",elements);
            if(data.containsKey("metadata"))cy.put("metadata",data.get("metadata"));
            return BoundedJson.encode(cy,32_000_000);
        }
        var edges=GraphJson.array(data.get("items"));
        var nodes=new TreeSet<String>();
        var cyEdges=new ArrayList<Map<String,Object>>();
        for(Object edge:edges){
            var row=GraphJson.object(edge);
            String src=GraphJson.string(row.get("source"));
            String tgt=GraphJson.string(row.get("target"));
            nodes.add(src);nodes.add(tgt);
            cyEdges.add(Map.of("data",Map.of("id",row.get("identity"),"source",src,"target",tgt,"classification",row.getOrDefault("classification","MUST"))));
        }
        var cyNodes=new ArrayList<Map<String,Object>>();
        for(String node:nodes)cyNodes.add(Map.of("data",Map.of("id",node,"label",node)));
        var cy=Map.of("format","cytoscape.js","elements",Map.of("nodes",cyNodes,"edges",cyEdges));
        return BoundedJson.encode(cy,32_000_000);
    }
    private static String escape(String text){return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
    private Exports(){}
}
