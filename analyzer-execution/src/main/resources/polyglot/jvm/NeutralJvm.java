package evo.polyglot;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.polyglot.LanguageProfile;
import com.evolution.analysis.polyglot.SourceCoordinates;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.CharBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

/** Platform protocol support for fixed native compiler adapters. It does not infer language bindings. */
public final class NeutralJvm {
    public record Source(String path,String text,ContentDigest hash,Path physical,SourceCoordinates coordinates,String role){}
    public record Site(Source source,String category,String role,int start,int end,Object nativeNode){}
    public final Map<String,Object> input;public final String language,dialect,scope,repository,packageScope;public final Path sourceRoot;
    public final Map<String,Source> sources=new TreeMap<>();private final List<Site> sites=new ArrayList<>();
    private final Map<Site,Integer> opaqueLocators=new IdentityHashMap<>();
    private final Map<Site,String> occurrenceKeys=new IdentityHashMap<>();
    private final Map<String,List<Map<String,Object>>> diagnostics=new TreeMap<>();private final Set<String> syntaxFailed=new HashSet<>();
    public NeutralJvm(Path request)throws Exception{
        input=GraphJson.object(GraphJson.read(Files.readString(request,StandardCharsets.UTF_8),new GraphJson.Limits(64000000,64,64000000)));
        if(!"native-jvm-request:m8.4-v1".equals(input.get("schema")))throw new IllegalArgumentException("NATIVE_JVM_REQUEST_SCHEMA");
        language=text(input,"language");dialect=text(input,"dialect");scope=text(input,"scope");new ContentDigest(scope);new ContentDigest(text(input,"tool"));
        repository=text(input,"repositoryScope");packageScope=text(input,"packageScope");sourceRoot=Path.of(text(input,"sourceRoot")).toRealPath();
        for(Object entry:GraphJson.array(input.get("files"))){
            var row=GraphJson.object(entry);String path=text(row,"path");logical(path);String content=text(row,"text");var encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(content));byte[] bytes=new byte[encoded.remaining()];encoded.get(bytes);var hash=ContentDigest.sha256(bytes);
            if(!hash.value().equals(row.get("sha256")))throw new IllegalArgumentException("NATIVE_JVM_SOURCE_HASH");
            Path physical=sourceRoot.resolve(path).normalize();if(!physical.startsWith(sourceRoot)||!physical.toRealPath().equals(physical))throw new IllegalArgumentException("NATIVE_JVM_SOURCE_ALIAS");
            if(!Arrays.equals(Files.readAllBytes(physical),bytes))throw new IllegalArgumentException("NATIVE_JVM_SOURCE_CHANGED");
            String role=GraphJson.string(row.getOrDefault("role","main"));if(!Set.of("main","test","generated","resolutionOnly").contains(role))throw new IllegalArgumentException("NATIVE_JVM_SOURCE_ROLE");
            if(sources.putIfAbsent(path,new Source(path,content,hash,physical,new SourceCoordinates(bytes,"UTF-8"),role))!=null)throw new IllegalArgumentException("NATIVE_JVM_DUPLICATE_SOURCE");
        }
        if(sources.isEmpty())throw new IllegalArgumentException("NATIVE_JVM_EMPTY_SOURCE_MEMBERSHIP");
    }
    public static String text(Map<String,Object> value,String key){return GraphJson.string(value.get(key));}
    public static void logical(String path){if(path.isBlank()||path.startsWith("/")||path.contains("\\")||path.contains("\0")||Arrays.stream(path.split("/",-1)).anyMatch(p->p.isEmpty()||p.equals(".")||p.equals("..")))throw new IllegalArgumentException("NATIVE_JVM_LOGICAL_PATH");}
    public Source source(Path physical){var path=physical.toAbsolutePath().normalize();return sources.values().stream().filter(s->s.physical().equals(path)).findFirst().orElse(null);}
    public void site(Source source,String category,String role,int start,int end,Object node){
        if(source==null)throw new IllegalArgumentException("NATIVE_JVM_SOURCE_MEMBERSHIP");
        if(start<0||end<=start||end>source.text().length()){diagnostic(source,"ERROR","NATIVE_RANGE_UNAVAILABLE","Native syntax site has no full original range");sites.add(new Site(source,category,"opaque-location",-1,-1,null));return;}
        source.coordinates().range(start,end,SourceCoordinates.Encoding.UTF16);sites.add(new Site(source,category,role,start,end,node));
    }
    public void syntaxFailed(Source source,String code,String message){syntaxFailed.add(source.path());diagnostic(source,"ERROR",code,message);}
    public void diagnostic(Source source,String severity,String code,String message){diagnostics.computeIfAbsent(source.path(),p->new ArrayList<>()).add(Map.of("severity",severity,"code",code,"message",message));}
    public String symbol(String owner,String signature){return LanguageProfile.symbol(language,repository,packageScope,owner,signature).value();}
    public Map<String,Object> symbol(String owner,String signature,String name,String kind,String origin,Object location){
        var value=new TreeMap<String,Object>();value.put("id",symbol(owner,signature));value.put("name",name);value.put("owner",owner);value.put("signature",signature);value.put("kind",kind);value.put("origin",origin);value.put("span",location);return value;
    }
    public Object span(Source source,int start,int end){if(start<0||end<=start)return null;var r=source.coordinates().range(start,end,SourceCoordinates.Encoding.UTF16);return Map.of("path",source.path(),"start",r.start(),"end",r.end(),"startLine",r.startLine(),"startColumn",r.startColumn(),"endLine",r.endLine(),"endColumn",r.endColumn());}
    public Map<String,Object> fact(String status,List<Map<String,Object>> symbols,Object type,String caller){
        var fact=new TreeMap<String,Object>();fact.put("status",status);fact.put("symbols",symbols);fact.put("targets",symbols.stream().map(s->s.get("id")).distinct().toList());fact.put("type",type);fact.put("caller",caller);
        fact.put("uncertainty",Set.of("PARTIAL","CONDITIONAL","AMBIGUOUS","CANDIDATES").contains(status)?List.of("NATIVE_BINDING_RETAINS_UNCERTAINTY"):List.of());return fact;
    }
    public Map<String,Object> unresolved(String reason){var value=fact("UNRESOLVED",List.of(),null,null);value.put("reason",reason);return value;}
    public Map<String,Object> unsupported(String reason){var value=fact("UNSUPPORTED",List.of(),null,null);value.put("reason",reason);return value;}
    private String baseKey(Site site){return site.source().path()+":"+site.category()+":"+String.format(Locale.ROOT,"%012d:%012d",site.start(),site.end())+":"+site.role()+(site.role().equals("opaque-location")?":occurrence:"+opaqueLocators.computeIfAbsent(site,ignored->opaqueLocators.size()):"");}
    private String key(Site site){return occurrenceKeys.getOrDefault(site,baseKey(site));}
    private Map<String,Object> obligation(Site site){var fields=new TreeMap<String,Object>();fields.put("document",site.source().path());fields.put("category",site.category());fields.put("role",site.role());fields.put("span",span(site.source(),site.start(),site.end()));return Map.of("kind","CENSUS","key",key(site),"scope",scope,"fields",fields);}
    public void emit(Function<Site,Map<String,Object>> attributed)throws Exception{
        var selected=new TreeMap<String,Site>();
        var categories=new HashMap<String,Set<String>>();for(var site:sites)categories.computeIfAbsent(site.source().path(),p->new HashSet<>()).add(site.category());
        for(var source:sources.values()){
            if(!syntaxFailed.contains(source.path())&&categories.getOrDefault(source.path(),Set.of()).contains("A08"))sites.add(new Site(source,"A08","implicit-generation-census",-1,-1,null));
            for(String category:List.of("A01","A11","A12"))sites.add(new Site(source,category,"scope",-1,-1,null));
            for(String category:LanguageProfile.requiredCategories())if(!Set.of("A01","A11","A12").contains(category)){
                if(syntaxFailed.contains(source.path()))sites.add(new Site(source,category,"opaque-syntax",-1,-1,null));
                else if(!categories.getOrDefault(source.path(),Set.of()).contains(category))sites.add(new Site(source,category,"syntax-absent",-1,-1,null));
            }
        }
        // Native compiler trees can contain distinct synthetic/annotated nodes
        // with identical source positions. Preserve each obligation in traversal
        // order; never deduplicate it or fail healthy sibling categories.
        var multiplicity=new HashMap<String,Integer>();for(var site:sites)multiplicity.merge(baseKey(site),1,Math::addExact);var ordinals=new HashMap<String,Integer>();
        for(var site:sites){String base=baseKey(site);if(multiplicity.get(base)>1)occurrenceKeys.put(site,base+":native-occurrence:"+String.format(Locale.ROOT,"%012d",ordinals.merge(base,1,Math::addExact)-1));if(selected.putIfAbsent(key(site),site)!=null)throw new IllegalArgumentException("NATIVE_JVM_DUPLICATE_OBLIGATION");}
        var census=new ArrayList<Map<String,Object>>();var observations=new ArrayList<Map<String,Object>>();
        for(var site:selected.values()){
            var expected=obligation(site);census.add(expected);Map<String,Object> value;
            if(site.role().equals("opaque-syntax")||site.role().equals("opaque-location")||(syntaxFailed.contains(site.source().path())&&!site.role().equals("scope")))value=fact("ERROR",List.of(),null,null);
            else if(Set.of("syntax-absent","implicit-generation-census").contains(site.role())&&site.category().equals("A08")){value=fact("UNSUPPORTED",List.of(),null,null);value.put("reason","NATIVE_IMPLICIT_GENERATION_CENSUS_REQUIRED");}
            else if(site.role().equals("syntax-absent"))value=fact("NOT_APPLICABLE",List.of(),null,null);
            else if(site.category().equals("A11")){value=fact(diagnostics.getOrDefault(site.source().path(),List.of()).stream().anyMatch(d->d.get("severity").equals("ERROR"))?"ERROR":"RESOLVED",List.of(),null,null);value.put("diagnostics",diagnostics.getOrDefault(site.source().path(),List.of()));}
            else if(Set.of("A01","A12").contains(site.category())&&site.role().equals("scope"))value=fact("RESOLVED",List.of(),null,null);
            else value=attributed.apply(site);
            value.put("obligation",CanonicalJson.sha256(expected).value());value.put("span",span(site.source(),site.start(),site.end()));value.put("role",site.role());
            observations.add(Map.of("kind","OBSERVATION","key",key(site),"scope",scope,"fields",value));
        }
        System.out.println(CanonicalJson.write(Map.of("schema","language-capture-result:m8.4-v1","scope",scope,"language",language,"dialect",dialect,"provider",input.get("provider"),"tool",input.get("tool"),"trust","PRODUCER_ASSERTION")));
        for(var source:sources.values())System.out.println(CanonicalJson.write(Map.of("kind","DOCUMENT","key",source.path(),"scope",scope,"fields",Map.of("content",Map.of("digest",source.hash().value(),"size",source.text().getBytes(StandardCharsets.UTF_8).length),"language",language,"role",source.role()))));
        for(var row:census)System.out.println(CanonicalJson.write(row));for(var row:observations)System.out.println(CanonicalJson.write(row));
        System.out.println(CanonicalJson.write(Map.of("schema","language-capture-end:m8.4-v1","scope",scope,"documents",sources.size(),"census",census.size(),"observations",observations.size(),"diagnostics",diagnostics.values().stream().flatMap(List::stream).toList())));
    }
}
