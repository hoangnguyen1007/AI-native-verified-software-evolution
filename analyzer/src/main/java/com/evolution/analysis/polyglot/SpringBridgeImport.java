package com.evolution.analysis.polyglot;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.SourceDocument;
import com.evolution.analysis.spring.universal.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Passive projection of the protected Spring route provider. Declaration
 * ownership is retained; activation, contract conformance and routing are not inferred. */
public final class SpringBridgeImport {
    public static final VersionedIdentifier PROVIDER=new VersionedIdentifier("architecture.spring-bridges","m8.4-v1");
    public record CapturedSource(ContentHandle content,String encoding){public CapturedSource{Objects.requireNonNull(content);java.nio.charset.Charset.forName(encoding);}}
    public record Imported(SemanticTables.Row partition,SemanticTables.Table endpoints,SemanticTables.Table requirements){}
    private final RepositoryContentStore content;private final SemanticTables tables;
    public SpringBridgeImport(RepositoryContentStore content,SemanticTables tables){this.content=Objects.requireNonNull(content);this.tables=Objects.requireNonNull(tables);}
    public Imported read(String key,ContentDigest inventory,SpringSourceEvidence source,Map<String,CapturedSource> captured,
            Map<String,String> properties,int routeBudget,int sourceBytes,BooleanSupplier cancelled)throws IOException{
        if(routeBudget<1||sourceBytes<1)throw new IllegalArgumentException("SPRING_BRIDGE_WORKER_BUDGET");check(cancelled);
        var root=com.evolution.analysis.evidence.EvidenceContext.forSnapshot(source.manifest().snapshot().identity());var scope=ArchitectureBridges.scope(root,inventory);
        var context=CanonicalJson.sha256(List.of("spring-bridge-context:m8.4-v1",source.manifest().identity()));
        // Recompute the protected provider result instead of accepting supplied derived routes.
        var routes=WebRouteMapper.map(source,properties,routeBudget);var manifest=put(source.manifest(),sourceBytes,cancelled);var frontend=put(source.frontend(),sourceBytes,cancelled);var routeBytes=put(routes,sourceBytes,cancelled);
        var capture=put(Map.of("schema","spring-bridge-capture:m8.4-v1","provider",PROVIDER,"source",source.identity(),"manifest",manifest,"frontend",frontend,"routes",routeBytes,"framework",source.framework(),"routeBudget",routeBudget,"properties",new TreeMap<>(properties),"encodings",new TreeMap<>(captured)),sourceBytes,cancelled);
        var documents=new TreeMap<String,SemanticTables.Row>();var endpoints=new TreeMap<String,SemanticTables.Row>();var needs=new TreeMap<String,SemanticTables.Row>();var byIdentity=new HashMap<com.evolution.analysis.contract.identity.SourceDocumentIdentity,SourceDocument>();
        var selection=source.manifest().configuration().values();String selectedModule=selection.get("java.module"),selectedSet=selection.get("java.source-set");
        for(var document:source.manifest().snapshot().documents()){
            check(cancelled);byIdentity.put(document.identity(),document);var bytes=captured.get(document.path());
            boolean selected=selectedModule==null||document.module().value().equals(selectedModule)&&(selectedSet==null||document.classification().name().equals(selectedSet)||selectedSet.equals("MAIN")&&document.classification().name().equals("GENERATED_MAIN")||selectedSet.equals("TEST")&&document.classification().name().equals("GENERATED_TEST"));
            // Other module/source-set inventory is not a missing input to this
            // Java context. Explicitly captured hierarchy/support bytes remain usable.
            if(!selected&&bytes==null)continue;
            if(bytes==null||!bytes.content().digest().equals(document.contentDigest())||!content.verify(bytes.content())){need(needs,scope,key,"source."+CanonicalJson.sha256(document).value(),capture,"SPRING_SOURCE_BYTES_UNAVAILABLE");continue;}
            documents.put(document.path(),new SemanticTables.Row(SemanticTables.Kind.DOCUMENT,document.path(),context,Map.of("content",bytes.content(),"encoding",bytes.encoding(),"role",document.classification().name(),"sourceDocument",document.identity().value())));
        }
        var condition=new ArchitectureBridges.Condition(CanonicalJson.sha256(List.of(PROVIDER,source.manifest().identity(),new TreeMap<>(properties))),Map.of(),true);
        for(var route:routes.routes()){
            check(cancelled);var declaration=source.declarations().get(route.handler());var span=declaration==null?Optional.<com.evolution.analysis.contract.source.SourceSpan>empty():declaration.entity().declaration();String prefix="route."+route.identity().value().substring(7);
            if(span.isEmpty()||!byIdentity.containsKey(span.orElseThrow().document())){need(needs,scope,key,prefix,capture,"SPRING_HTTP_SOURCE_LOCATION_UNAVAILABLE");continue;}
            var original=span.orElseThrow();var document=byIdentity.get(original.document());var bytes=captured.get(document.path());if(!documents.containsKey(document.path())){need(needs,scope,key,prefix,capture,"SPRING_HTTP_SOURCE_BYTES_UNAVAILABLE");continue;}
            if(bytes.content().size()>sourceBytes){need(needs,scope,key,prefix,capture,"SPRING_SOURCE_WORKER_REQUIRED");continue;}
            ArchitectureBridges.Anchor anchor;
            try(var input=content.open(bytes.content())){var coordinates=new SourceCoordinates(input.readNBytes(sourceBytes),bytes.encoding());anchor=new ArchitectureBridges.Anchor(document.path(),bytes.content(),coordinates.at(original.startLine(),original.startColumn()).offset(),coordinates.at(original.endLine(),original.endColumn()).offset());}
            catch(IllegalArgumentException unsupported){need(needs,scope,key,prefix,capture,"SPRING_HTTP_SOURCE_COORDINATES_UNAVAILABLE");continue;}
            var methods=new TreeSet<>(route.methods());if(route.implicitHead())methods.add("HEAD");if(methods.isEmpty()){need(needs,scope,key,prefix,capture,"SPRING_HTTP_METHOD_SELECTION_REQUIRED");continue;}
            for(String method:methods){var descriptor=new TreeMap<String,String>();descriptor.put("method",method);descriptor.put("path",route.path());descriptor.put("handler",route.handler().value());descriptor.put("controller",route.controller().value());String owner=source.typeName(route.controller());if(owner!=null)descriptor.put("controllerName",owner);descriptor.put("sourceAnalysis",source.manifest().identity().value());descriptor.put("sourcePrecision","ORIGINAL_SOURCE_RANGE");descriptor.put("registrationMeaning","DECLARATION_ONLY");descriptor.put("activation","UNKNOWN");if(method.equals("HEAD")&&!route.methods().contains("HEAD"))descriptor.put("methodDerivation","SPRING_IMPLICIT_HEAD");
                String id=prefix+"."+method;var endpoint=new ArchitectureBridges.Endpoint(key,CanonicalJson.sha256(List.of("spring-handler-bridge-symbol:m8.4-v1",route.handler())),ArchitectureBridges.Profile.B02,"PROVIDES","CONDITIONAL",descriptor,condition,anchor,List.of(capture,bytes.content(),manifest,frontend,routeBytes));endpoints.put(id,ArchitectureBridges.row(SemanticTables.Kind.OBSERVATION,id,scope,endpoint));
                String exposureId=id+".exposes";var exposureDescriptor=new TreeMap<>(descriptor);exposureDescriptor.put("bindingRole","EXPOSES");var exposure=new ArchitectureBridges.Endpoint(key,endpoint.symbol(),ArchitectureBridges.Profile.B02,"REQUIRES","CONDITIONAL",exposureDescriptor,condition,anchor,endpoint.evidence());
                endpoints.put(exposureId,ArchitectureBridges.row(SemanticTables.Kind.OBSERVATION,exposureId,scope,exposure));needs.put(exposureId,ArchitectureBridges.row(SemanticTables.Kind.CENSUS,exposureId,scope,new ArchitectureBridges.Requirement(key,ArchitectureBridges.Profile.B02,"APPLICABLE",Optional.of(exposureId),List.of(),capture,"Mapped Spring declaration; exact contract and activation remain required.")));
                need(needs,scope,key,id,capture,"SPRING_HTTP_CONTRACT_AND_ACTIVATION_REQUIRED");}
        }
        for(var row:routes.rows())if(row.status()!=WebRouteMapper.Status.MAPPED)need(needs,scope,key,"handler."+CanonicalJson.sha256(row.handler()).value(),capture,"SPRING_HTTP_"+row.status());
        for(var gap:routes.gaps())need(needs,scope,key,"gap."+CanonicalJson.sha256(gap).value(),capture,"SPRING_ROUTE_PROVIDER_GAP");
        for(var profile:ArchitectureBridges.Profile.values()){String id="category."+profile;needs.put(id,ArchitectureBridges.row(SemanticTables.Kind.CENSUS,id,scope,new ArchitectureBridges.Requirement(key,profile,"UNKNOWN",Optional.empty(),List.of(),capture,"Independent interop census is still required.")));}
        var selectedModules=source.manifest().modules().stream().filter(value->selectedModule==null||value.identity().value().equals(selectedModule)).toList();var module=selectedModules.size()==1?selectedModules.getFirst().displayName():"NOT_RECORDED";
        var partition=new ArchitectureBridges.Partition(context,source.manifest().snapshot().identity().value(),"NOT_RECORDED",module,"NOT_RECORDED","NOT_RECORDED","java","PARTIAL",tables.write(SemanticTables.Kind.DOCUMENT,documents.values().iterator(),cancelled),Optional.of(capture));
        return new Imported(ArchitectureBridges.row(SemanticTables.Kind.CONTEXT,key,scope,partition),tables.write(SemanticTables.Kind.OBSERVATION,endpoints.values().iterator(),cancelled),tables.write(SemanticTables.Kind.CENSUS,needs.values().iterator(),cancelled));
    }
    private static void need(Map<String,SemanticTables.Row> needs,ContentDigest scope,String partition,String key,ContentHandle evidence,String reason){needs.put(key,ArchitectureBridges.row(SemanticTables.Kind.CENSUS,key,scope,new ArchitectureBridges.Requirement(partition,ArchitectureBridges.Profile.B02,"UNKNOWN",Optional.empty(),List.of(),evidence,reason)));}
    private ContentHandle put(Object value,int budget,BooleanSupplier cancelled)throws IOException{byte[] bytes=CanonicalJson.write(value).getBytes(StandardCharsets.UTF_8);if(bytes.length>budget)throw new IOException("SPRING_BRIDGE_METADATA_WORKER_REQUIRED");return content.put(new ByteArrayInputStream(bytes),cancelled);}
    private static void check(BooleanSupplier cancelled)throws IOException{if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("SPRING_BRIDGE_CANCELLED");}
}
