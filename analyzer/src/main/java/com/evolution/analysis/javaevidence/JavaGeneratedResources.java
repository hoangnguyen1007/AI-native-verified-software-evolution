package com.evolution.analysis.javaevidence;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.charset.*;
import java.util.*;

/** Uses a validated native output-root association, never a guessed target/build suffix. */
public final class JavaGeneratedResources {
    public record Mapping(String rootKey,String nativeRoot,String relativePath,Optional<String> encoding,List<String> nativeOrder){public Mapping{nativeOrder=List.copyOf(nativeOrder);}}
    public static Mapping mapping(JavaEvidenceRecord output,JavaEvidenceAuthority.Verified proof,RepositoryContentStore content,int bytes)throws IOException{
        if(!proof.identity().equals(output.digest("rootProof"))||!proof.current()||!proof.record().text("question").equals("java.generated-lineage")||!proof.record().text("subject").equals(output.text("lineage"))||!proof.record().fields().get("scope").equals(output.fields().get("scope")))throw new IOException("JAVA_RESOURCE_OWNER_UNPROVEN");
        var handle=JavaEvidenceRecord.handle(proof.record().fields().get("answer"));if(handle.size()>bytes||!content.verify(handle))throw new IOException("JAVA_RESOURCE_LAYOUT_WORKER_REQUIRED");
        Map<String,Object> layout;try(var in=content.open(handle);var reader=new InputStreamReader(in,StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT))){layout=GraphJson.object(GraphJson.read(reader,new GraphJson.Limits(bytes,24,Math.max(bytes,1))));}
        if(!layout.keySet().equals(Set.of("schema","scope","roots"))||!"java-generated-root-layout:m8.4-v1".equals(layout.get("schema"))||!layout.get("scope").equals(output.fields().get("scope")))throw new IOException("JAVA_GENERATED_RESOURCE_LAYOUT_REQUIRED");
        byte[] path=JavaGeneratedOverlay.path(output.object("locator").get("path")).bytes();var matches=new ArrayList<Mapping>();var nativeOrder=new ArrayList<String>();
        for(Object value:GraphJson.array(layout.get("roots"))){var root=GraphJson.object(value);if("RESOURCE".equals(root.get("kind"))&&output.object("scope").get("sourceSet").equals(root.get("sourceSet"))){String name=root.get("root")==null?".":JavaGeneratedOverlay.path(root.get("root")).utf8().orElseThrow(()->new IOException("JAVA_RESOURCE_RAW_PATH_WORKER_REQUIRED"));if(nativeOrder.contains(name))throw new IOException("JAVA_RESOURCE_DUPLICATE_ROOT");nativeOrder.add(name);}}
        for(Object value:GraphJson.array(layout.get("roots"))){var root=GraphJson.object(value);
            if(!root.keySet().equals(Set.of("kind","role","sourceSet","root","encoding")))throw new IOException("JAVA_RESOURCE_ROOT_FIELDS");
            if(!"RESOURCE".equals(root.get("kind"))||!output.text("role").equals(root.get("role"))||!output.object("scope").get("sourceSet").equals(root.get("sourceSet")))continue;
            byte[] prefix=root.get("root")==null?new byte[0]:JavaGeneratedOverlay.path(root.get("root")).bytes();
            int start=0;if(prefix.length>0){if(path.length<=prefix.length||path[prefix.length]!='/'||!Arrays.equals(prefix,Arrays.copyOf(path,prefix.length)))continue;start=prefix.length+1;}
            var relative=LogicalPath.of(Arrays.copyOfRange(path,start,path.length)).utf8().orElseThrow(()->new IOException("JAVA_RESOURCE_RAW_PATH_WORKER_REQUIRED"));
            String key=root.get("root")==null?"work":HexFormat.of().formatHex(prefix);
            String nativeRoot=root.get("root")==null?".":JavaGeneratedOverlay.path(root.get("root")).utf8().orElseThrow(()->new IOException("JAVA_RESOURCE_RAW_PATH_WORKER_REQUIRED"));
            matches.add(new Mapping(key,nativeRoot,relative,Optional.ofNullable(root.get("encoding")).map(GraphJson::string),nativeOrder));
        }
        if(matches.size()!=1)throw new IOException("JAVA_GENERATED_RESOURCE_OWNER_AMBIGUOUS");return matches.getFirst();
    }
    public static boolean textConsumer(String path){return path.endsWith(".properties")||path.endsWith(".yml")||path.endsWith(".yaml")||path.endsWith(".xml")||path.endsWith(".imports")||path.endsWith(".factories");}
    private JavaGeneratedResources(){}
}
