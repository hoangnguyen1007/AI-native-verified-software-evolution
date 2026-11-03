package com.evolution.analysis.polyglot;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Data-only adapter from the implemented frontend/schema lane. Importing a
 * native declaration does not invent an application/deployment endpoint. */
public final class ArchitectureBridgeImport {
    public static final VersionedIdentifier PROVIDER=new VersionedIdentifier("architecture.bridge-import","m8.4-v1");
    public record Imported(SemanticTables.Row partition,SemanticTables.Table endpoints,SemanticTables.Table requirements){}
    private final RepositoryContentStore content;private final SemanticTables tables;
    public ArchitectureBridgeImport(RepositoryContentStore content,SemanticTables tables){this.content=Objects.requireNonNull(content);this.tables=Objects.requireNonNull(tables);}
    public Imported read(String key,ContentDigest inventory,ArchitectureCapture.Request request,ArchitectureCapture.Result capture,int workerBytes,BooleanSupplier cancelled)throws IOException{
        // Reconstruct from the raw bytes, not caller-supplied derived tables.
        var actual=ArchitectureCapture.read(request,capture.raw(),tables,content,workerBytes,gap->{},cancelled);if(!actual.equals(capture))throw new IOException("BRIDGE_ARCHITECTURE_CAPTURE_BINDING");
        var scope=ArchitectureBridges.scope(request.context(),inventory);var condition=new ArchitectureBridges.Condition(CanonicalJson.sha256(List.of("architecture-condition-domain:m8.4-v1",request.scope())),selections(request.configuration().getOrDefault("conditions",Map.of())),false);
        var documents=new TreeMap<String,SemanticTables.Row>();try(var rows=tables.open(request.documents(),cancelled)){SemanticTables.Row row;while((row=rows.next())!=null)documents.put(row.key(),row);}
        var endpoints=new TreeMap<String,SemanticTables.Row>();var requirements=new TreeMap<String,SemanticTables.Row>();
        var nativeEndpoints=new ArrayList<SemanticTables.Row>();var nativeArtifacts=new TreeMap<String,ContentHandle>();
        try(var rows=tables.open(capture.artifacts(),cancelled)){SemanticTables.Row row;while((row=rows.next())!=null)nativeArtifacts.put(row.key(),handle(row.fields().get("content")));}
        var clients=new ArrayList<SemanticTables.Row>();var operations=new TreeMap<String,ArchitectureBridges.Endpoint>();
        var mapping=put(Map.of("schema","architecture-bridge-mapping:m8.4-v1","mapper",PROVIDER,"capture",capture.raw(),"context",request.scope()),cancelled);
        var generator=put(Map.of("schema","architecture-generator-binding:m8.4-v1","tool",request.tool(),"provider",ArchitectureCapture.PROVIDER,"mapper",PROVIDER),cancelled);
        try(var rows=tables.open(capture.facts(),cancelled)){SemanticTables.Row row;while((row=rows.next())!=null){var fields=row.fields();
            if("BRIDGE_ENDPOINT".equals(fields.get("kind"))){nativeEndpoints.add(row);continue;}
            if(Set.of("CLIENT_API_USE","NETWORK_REQUEST").contains(fields.get("kind"))){clients.add(row);continue;}
            if("WORKER_BOUNDARY".equals(fields.get("kind"))){String id=row.key()+".client-binding";requirements.put(id,ArchitectureBridges.row(SemanticTables.Kind.CENSUS,id,scope,new ArchitectureBridges.Requirement(key,ArchitectureBridges.Profile.B07,"UNKNOWN",Optional.empty(),List.of(),capture.raw(),"Captured worker boundary requires exact payload/channel and registration correspondence.")));continue;}
            if(!"API_OPERATION".equals(fields.get("kind")))continue;var attrs=GraphJson.object(fields.get("attributes"));if(!(attrs.get("method") instanceof String method)||!(attrs.get("route") instanceof String path)||!(fields.get("target") instanceof String)||fields.get("span")==null){String id=row.key()+".operation-identity";requirements.put(id,ArchitectureBridges.row(SemanticTables.Kind.CENSUS,id,scope,new ArchitectureBridges.Requirement(key,ArchitectureBridges.Profile.B02,"UNKNOWN",Optional.empty(),List.of(),capture.raw(),"Native operation identity/location remains incomplete.")));continue;}
            String sourcePath=GraphJson.string(fields.get("path"));var source=handle(documents.get(sourcePath).fields().get("content"));var descriptor=new TreeMap<String,String>();descriptor.put("sourcePrecision",String.valueOf(attrs.getOrDefault("rangePrecision","NOT_RECORDED")));descriptor.put("contract",source.digest().value());descriptor.put("method",method);descriptor.put("path",path);if(attrs.get("contractVersion") instanceof String version)descriptor.put("version",version);if(attrs.get("version") instanceof String dialect)descriptor.put("dialect",dialect);
            // Request-schema closure is derived only from captured JSON bytes.
            // Selected service/routing still needs independent registration.
            var metadata=new OpenApiBridgeDescriptors(content).read(source,method,path,workerBytes,cancelled);var diagnostics=new TreeSet<>(metadata.diagnostics());
            for(var operand:metadata.operands().entrySet()){if(descriptor.containsKey(operand.getKey())&&!descriptor.get(operand.getKey()).equals(operand.getValue())){diagnostics.add("OPENAPI_NATIVE_"+operand.getKey().toUpperCase(Locale.ROOT)+"_CONFLICT");descriptor.put("native."+operand.getKey(),descriptor.remove(operand.getKey()));descriptor.put("source."+operand.getKey(),operand.getValue());}else descriptor.put(operand.getKey(),operand.getValue());}
            var evidence=new ArrayList<ContentHandle>(List.of(capture.raw(),source,mapping));metadata.parameters().ifPresent(evidence::add);String status=GraphJson.string(fields.get("status"));
            if(!diagnostics.isEmpty()){
                var attempt=put(Map.of("schema","architecture-openapi-attempt:m8.4-v1","provider",OpenApiBridgeDescriptors.PROVIDER,"source",source,"method",method,"path",path,"diagnostics",diagnostics),cancelled);evidence.add(attempt);descriptor.put("metadataDiagnostics",String.join(",",diagnostics));descriptor.put("nativeStatus",status);if(status.equals("RESOLVED"))status="PARTIAL";
                for(String diagnostic:diagnostics){String id=row.key()+"."+diagnostic;requirements.put(id,ArchitectureBridges.row(SemanticTables.Kind.CENSUS,id,scope,new ArchitectureBridges.Requirement(key,ArchitectureBridges.Profile.B02,"UNKNOWN",Optional.empty(),List.of(row.key()+".http"),attempt,diagnostic)));}
            }
            var span=GraphJson.object(fields.get("span"));var endpoint=new ArchitectureBridges.Endpoint(key,new ContentDigest(GraphJson.string(fields.get("target"))),ArchitectureBridges.Profile.B02,"PROVIDES",status,descriptor,condition,new ArchitectureBridges.Anchor(sourcePath,source,number(span,"start"),number(span,"end")),evidence);
            endpoints.put(row.key()+".http",ArchitectureBridges.row(SemanticTables.Kind.OBSERVATION,row.key()+".http",scope,endpoint));
            operations.put(row.key()+".http",endpoint);
        }}
        for(var row:nativeEndpoints){var fields=row.fields();var attrs=GraphJson.object(fields.get("attributes"));var profile=ArchitectureBridges.Profile.valueOf(GraphJson.string(attrs.get("profile")));
            if(!Set.of(ArchitectureBridges.Profile.B03,ArchitectureBridges.Profile.B04,ArchitectureBridges.Profile.B05,ArchitectureBridges.Profile.B07).contains(profile))throw new IOException("BRIDGE_NATIVE_PROFILE_UNSUPPORTED");
            String sourcePath=GraphJson.string(fields.get("path"));var source=handle(documents.get(sourcePath).fields().get("content"));var evidence=new ArrayList<ContentHandle>(List.of(capture.raw(),source,mapping));
            for(Object id:GraphJson.array(attrs.get("artifacts"))){var artifact=nativeArtifacts.get(GraphJson.string(id));if(artifact==null)throw new IOException("BRIDGE_NATIVE_ARTIFACT_MISSING");evidence.add(artifact);}
            var descriptor=new TreeMap<String,String>();GraphJson.object(attrs.get("descriptor")).forEach((name,value)->descriptor.put(name,GraphJson.string(value)));descriptor.put("sourcePrecision",GraphJson.string(attrs.get("rangePrecision")));
            var span=GraphJson.object(fields.get("span"));String id=row.key()+".bridge",direction=GraphJson.string(attrs.get("direction"));var endpoint=new ArchitectureBridges.Endpoint(key,new ContentDigest(GraphJson.string(fields.get("target"))),profile,direction,GraphJson.string(fields.get("status")),descriptor,condition,new ArchitectureBridges.Anchor(sourcePath,source,number(span,"start"),number(span,"end")),evidence);
            endpoints.put(id,ArchitectureBridges.row(SemanticTables.Kind.OBSERVATION,id,scope,endpoint));
            if(direction.equals("REQUIRES")){var candidates=GraphJson.array(attrs.get("candidates")).stream().map(GraphJson::string).map(candidate->candidate+".bridge").toList();requirements.put(id,ArchitectureBridges.row(SemanticTables.Kind.CENSUS,id,scope,new ArchitectureBridges.Requirement(key,profile,"APPLICABLE",Optional.of(id),candidates,capture.raw(),"Native source/model correspondence; declared compatibility only, without runtime delivery assurance.")));}
        }
        for(var row:clients){var fields=row.fields();var attrs=GraphJson.object(fields.get("attributes"));String id=row.key()+".client-binding";var candidates=new ArrayList<String>();
            for(var operation:operations.entrySet())if(ContentDigest.sha256Utf8(operation.getValue().descriptor().get("path")).value().equals(attrs.get("endpointDigest")))candidates.add(operation.getKey());
            var owner=attrs.get("owner");if(!(owner instanceof String)||fields.get("span")==null){requirements.put(id,ArchitectureBridges.row(SemanticTables.Kind.CENSUS,id,scope,new ArchitectureBridges.Requirement(key,ArchitectureBridges.Profile.B02,"UNKNOWN",Optional.empty(),candidates,capture.raw(),"Native client owner/location is incomplete.")));continue;}
            String sourcePath=GraphJson.string(fields.get("path"));var source=handle(documents.get(sourcePath).fields().get("content"));var descriptor=new TreeMap<String,String>();descriptor.put("sourcePrecision",String.valueOf(attrs.getOrDefault("rangePrecision","NOT_RECORDED")));if(attrs.get("method") instanceof String method&&method.matches("GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|TRACE|CONNECT"))descriptor.put("method",method);
            descriptor.put("bindingRole","CONSUMES");if(attrs.get("endpointDigest") instanceof String digest)descriptor.put("pathDigest",new ContentDigest(digest).value());
            var paths=candidates.stream().map(candidate->operations.get(candidate).descriptor().get("path")).distinct().toList();if(paths.size()==1)descriptor.put("path",paths.getFirst());
            // Path-hash agreement only enumerates candidates. It supplies no
            // contract identity/version/parameters, routing or method default.
            var span=GraphJson.object(fields.get("span"));String status=GraphJson.string(fields.get("status"));if(status.equals("RESOLVED"))status="CANDIDATES";
            var endpoint=new ArchitectureBridges.Endpoint(key,new ContentDigest((String)owner),ArchitectureBridges.Profile.B02,"REQUIRES",status,descriptor,condition,new ArchitectureBridges.Anchor(sourcePath,source,number(span,"start"),number(span,"end")),List.of(capture.raw(),source,mapping));
            endpoints.put(id,ArchitectureBridges.row(SemanticTables.Kind.OBSERVATION,id,scope,endpoint));requirements.put(id,ArchitectureBridges.row(SemanticTables.Kind.CENSUS,id,scope,new ArchitectureBridges.Requirement(key,ArchitectureBridges.Profile.B02,"APPLICABLE",Optional.of(id),candidates,capture.raw(),"Native client/wrapper occurrence; endpoint candidates remain uncertain without exact contract/routing evidence.")));
        }
        try(var rows=tables.open(capture.artifacts(),cancelled)){SemanticTables.Row row;while((row=rows.next())!=null){var fields=row.fields();String sourcePath=GraphJson.string(fields.get("source")),kind=GraphJson.string(fields.get("kind"));var source=handle(documents.get(sourcePath).fields().get("content"));var artifact=handle(fields.get("content"));String generatedPath="@generated/"+row.key().substring(7);
            documents.put(generatedPath,new SemanticTables.Row(SemanticTables.Kind.DOCUMENT,generatedPath,request.scope(),Map.of("content",artifact,"role","generated-helper","lineage",capture.raw())));
            var lineage=put(Map.of("schema","architecture-generated-lineage:m8.4-v1","source",source,"artifact",artifact,"nativeRow",row.identity(),"capture",capture.raw(),"generator",generator),cancelled);
            var descriptor=Map.of("source",source.digest().value(),"artifact",artifact.digest().value(),"generator",generator.digest().value(),"lineage",lineage.digest().value(),"sourcePrecision","DOCUMENT_SUPPORT");var evidence=List.of(capture.raw(),source,artifact,generator,lineage);
            String consumer=row.key()+".generated",producer=row.key()+".source";
            var sourceSymbol=CanonicalJson.sha256(List.of("architecture-source-symbol:m8.4-v1",request.repository(),request.app(),sourcePath));var generatedSymbol=CanonicalJson.sha256(List.of("architecture-generated-symbol:m8.4-v1",request.repository(),request.app(),sourcePath,kind));
            var original=new ArchitectureBridges.Endpoint(key,sourceSymbol,ArchitectureBridges.Profile.B08,"PROVIDES","RESOLVED",descriptor,condition,anchor(sourcePath,source,workerBytes),evidence);
            var generated=new ArchitectureBridges.Endpoint(key,generatedSymbol,ArchitectureBridges.Profile.B08,"REQUIRES","RESOLVED",descriptor,condition,new ArchitectureBridges.Anchor(generatedPath,artifact,Optional.empty()),evidence);
            endpoints.put(producer,ArchitectureBridges.row(SemanticTables.Kind.OBSERVATION,producer,scope,original));endpoints.put(consumer,ArchitectureBridges.row(SemanticTables.Kind.OBSERVATION,consumer,scope,generated));
            requirements.put(consumer,ArchitectureBridges.row(SemanticTables.Kind.CENSUS,consumer,scope,new ArchitectureBridges.Requirement(key,ArchitectureBridges.Profile.B08,"APPLICABLE",Optional.of(consumer),List.of(producer),lineage,"Native artifact/source byte lineage; no consumer call is inferred.")));
        }}
        // A frontend census is not an independent interop absence proof. Keep
        // every other mechanism open instead of manufacturing N/A outcomes.
        for(var profile:ArchitectureBridges.Profile.values())if(profile!=ArchitectureBridges.Profile.B08||capture.artifacts().rows()==0)requirements.put("category."+profile,ArchitectureBridges.row(SemanticTables.Kind.CENSUS,"category."+profile,scope,new ArchitectureBridges.Requirement(key,profile,"UNKNOWN",Optional.empty(),List.of(),capture.raw(),"Independent interop occurrence/absence evidence is required.")));
        // Operational capture state is separate from the still-unqualified method.
        var docs=tables.write(SemanticTables.Kind.DOCUMENT,documents.values().iterator(),cancelled);var child=new ArchitectureBridges.Child(put(request,cancelled),capture.identity(),capture.facts(),capture.obligations(),capture.artifacts());var partition=new ArchitectureBridges.Partition(request.scope(),request.context().snapshotIdentity().value(),dimension(request,"buildRoot"),dimension(request,"module"),dimension(request,"sourceSet"),dimension(request,"variant"),"frontendarchitecture",capture.gapCount()==0?"COMPLETE":"PARTIAL",docs,Optional.of(capture.raw()),Optional.of(child));
        return new Imported(ArchitectureBridges.row(SemanticTables.Kind.CONTEXT,key,scope,partition),tables.write(SemanticTables.Kind.OBSERVATION,endpoints.values().iterator(),cancelled),tables.write(SemanticTables.Kind.CENSUS,requirements.values().iterator(),cancelled));
    }
    private ArchitectureBridges.Anchor anchor(String path,ContentHandle source,int bytes)throws IOException{if(source.size()>bytes)throw new IOException("BRIDGE_ARCHITECTURE_SOURCE_WORKER_REQUIRED");try(var stream=content.open(source)){var coordinates=new SourceCoordinates(stream.readNBytes(bytes),"UTF-8");return new ArchitectureBridges.Anchor(path,source,0,coordinates.text().length());}}
    private ContentHandle put(Object value,BooleanSupplier cancelled)throws IOException{return content.put(new ByteArrayInputStream(CanonicalJson.write(value).getBytes(StandardCharsets.UTF_8)),cancelled);}
    private static Map<String,String> selections(Object value){var result=new TreeMap<String,String>();GraphJson.object(value).forEach((key,selection)->result.put(key,CanonicalJson.write(selection)));return result;}
    private static String dimension(ArchitectureCapture.Request request,String key){return request.configuration().get(key) instanceof String value&&!value.isBlank()?value:"NOT_RECORDED";}
    private static ContentHandle handle(Object value){return value instanceof ContentHandle handle?handle:LanguageSemanticRequest.handle(value);}
    private static long number(Map<String,Object> value,String key){return ((java.math.BigDecimal)value.get(key)).longValueExact();}
}
