package com.evolution.analysis.spring.universal;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.EntityIdentity;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.ingestion.IngestionEvidence;
import com.evolution.analysis.spring.condition.*;
import java.util.*;

/** Declaration-level HTTP entry points. Handler activation and potential call paths remain separate evidence. */
public final class WebRouteMapper {
    private static final String PREFIX="org.springframework.web.bind.annotation.";
    private static final Map<String,String> SHORTCUTS=Map.of("GetMapping","GET","PostMapping","POST","PutMapping","PUT","DeleteMapping","DELETE","PatchMapping","PATCH");
    private static final Set<String> METHODS=Set.of("GET","HEAD","POST","PUT","PATCH","DELETE","OPTIONS","TRACE");
    public enum Status { MAPPED, NOT_CONTROLLER, UNKNOWN, LIMITED }
    public record Route(EntityIdentity controller,EntityIdentity handler,String path,List<String> methods,
                        List<String> params,List<String> headers,List<String> consumes,List<String> produces,
                        boolean implicitHead,ConditionEvidence evidence) {
        public Route {methods=sorted(methods);params=sorted(params);headers=sorted(headers);consumes=sorted(consumes);produces=sorted(produces);}
        public ContentDigest identity(){return IngestionEvidence.digest(this);}
    }
    public record Row(EntityIdentity handler,Status status,List<ContentDigest> routes,List<ConditionEvidence> evidence) {
        public Row {routes=List.copyOf(routes);evidence=List.copyOf(evidence);}
    }
    public record Result(ContentDigest inputIdentity,List<Route> routes,List<Row> rows,List<UniversalSpringEvidence.Issue> issues,List<CapabilityGapRecord> gaps) {
        public Result {routes=List.copyOf(routes);rows=List.copyOf(rows);issues=List.copyOf(issues);gaps=List.copyOf(gaps);}
        public ContentDigest identity(){return IngestionEvidence.digest(this);}
    }
    private record Mapping(List<String> paths,List<String> methods,List<String> params,List<String> headers,List<String> consumes,List<String> produces,ConditionEvidence evidence) {}
    public static Result map(SpringSourceEvidence source,Map<String,String> properties,int maxRoutes) {
        if(maxRoutes<1)throw new IllegalArgumentException("Positive route limit required");
        var input=IngestionEvidence.digest(List.of("spring.web-routes:m4u.2-v1",source.identity(),new TreeMap<>(properties),maxRoutes));
        var routes=new ArrayList<Route>();var rows=new ArrayList<Row>();var issues=new ArrayList<UniversalSpringEvidence.Issue>();
        for(var declaration:source.declarations().values())if(declaration.entity().origin()==EntityOrigin.PROJECT&&declaration.entity().kind()==EntityKind.METHOD) {
            var method=declaration.entity().identity();var uses=source.annotations(method);var mappings=uses.stream().filter(WebRouteMapper::mapping).toList();
            boolean unknown=uses.stream().anyMatch(a->a.name().isEmpty()&&a.use().spelling().matches("(?s).*\\w*Mapping.*"));
            if(mappings.isEmpty()&&!unknown)continue;
            var owner=source.owner(method);var evidence=source.evidence(method);var ids=new ArrayList<ContentDigest>();Status status;
            if(owner.isEmpty()||unknown||mappings.size()!=1) {status=Status.UNKNOWN;issue(issues,UniversalSpringEvidence.Reason.ROUTE_UNSUPPORTED,method,evidence);}
            else {
                EntityIdentity controller=owner.orElseThrow();
                boolean controllerKnown=source.annotations(controller).stream().anyMatch(a->a.name().filter(n->n.equals(PREFIX+"RestController")||n.equals("org.springframework.stereotype.Controller")).isPresent());
                if(!controllerKnown){
                    boolean inherited=source.frontend().typeDeclarations().stream().anyMatch(t->!t.type().equals(controller)&&source.ancestry(t.type(),maxRoutes).contains(controller)
                            &&source.annotations(t.type()).stream().anyMatch(a->a.name().filter(n->n.equals(PREFIX+"RestController")||n.equals("org.springframework.stereotype.Controller")).isPresent()));
                    status=inherited?Status.UNKNOWN:Status.NOT_CONTROLLER;
                    if(inherited)issue(issues,UniversalSpringEvidence.Reason.ROUTE_UNSUPPORTED,method,evidence);
                }
                else try {
                    var bases=source.annotations(controller).stream().filter(WebRouteMapper::mapping).toList();
                    if(bases.size()>1)throw new IllegalArgumentException();
                    Mapping base=bases.isEmpty()?new Mapping(List.of(""),List.of(),List.of(),List.of(),List.of(),List.of(),source.evidence(controller)):decode(bases.getFirst(),properties);
                    Mapping local=decode(mappings.getFirst(),properties);
                    long count=(long)base.paths().size()*local.paths().size();
                    if(count>maxRoutes-routes.size()) {status=Status.LIMITED;issue(issues,UniversalSpringEvidence.Reason.RESOURCE_LIMIT,method,evidence);}
                    else {
                        var methods=union(base.methods(),local.methods());var params=union(base.params(),local.params());var headers=union(base.headers(),local.headers());
                        var consumes=local.consumes().isEmpty()?base.consumes():local.consumes();var produces=local.produces().isEmpty()?base.produces():local.produces();
                        for(String parent:base.paths())for(String child:local.paths()) {
                            String path=combine(parent,child);
                            var proof=new ConditionEvidence.Derived(List.of(input,base.evidence().identity(),local.evidence().identity()),UniversalSpringEvidence.PROVIDER,"http-route:"+method.value()+":"+path);
                            var route=new Route(controller,method,path,methods,params,headers,consumes,produces,methods.contains("GET"),proof);
                            routes.add(route);ids.add(route.identity());
                        }
                        status=Status.MAPPED;
                    }
                }catch(IllegalArgumentException invalid){status=Status.UNKNOWN;issue(issues,UniversalSpringEvidence.Reason.ROUTE_UNSUPPORTED,method,evidence);}
            }
            rows.add(new Row(method,status,ids,List.of(evidence)));
        }
        var routeKeys=new HashMap<ContentDigest,Route>();
        for(var route:routes) {
            var key=IngestionEvidence.digest(List.of(route.path(),route.methods(),route.params(),route.headers(),route.consumes(),route.produces()));
            var previous=routeKeys.putIfAbsent(key,route);
            if(previous!=null&&!previous.handler().equals(route.handler()))issues.add(new UniversalSpringEvidence.Issue(UniversalSpringEvidence.Reason.ROUTE_AMBIGUOUS,key.value(),List.of(previous.evidence(),route.evidence())));
        }
        return new Result(input,routes.stream().distinct().sorted(Comparator.comparing(Route::identity)).toList(),rows,issues,
                UniversalSpringEvidence.gaps(source.manifest().snapshot().identity(),input,issues));
    }
    private static boolean mapping(SpringSourceEvidence.Annotation a){return a.name().filter(n->n.equals(PREFIX+"RequestMapping")||n.startsWith(PREFIX)&&SHORTCUTS.containsKey(n.substring(PREFIX.length()))).isPresent();}
    private static Mapping decode(SpringSourceEvidence.Annotation use,Map<String,String> properties) {
        var attrs=LiteralConditionAnnotation.parse(use.use().spelling());
        if(!Set.of("value","path","method","params","headers","consumes","produces","name").containsAll(attrs.keySet()))throw new IllegalArgumentException();
        var paths=LiteralConditionAnnotation.strings(attrs,"path",List.of());var alias=LiteralConditionAnnotation.strings(attrs,"value",List.of());
        if(!paths.isEmpty()&&!alias.isEmpty()&&!paths.equals(alias))throw new IllegalArgumentException();
        if(paths.isEmpty())paths=alias;if(paths.isEmpty())paths=List.of("");
        var methods=new ArrayList<String>();String shortcut=SHORTCUTS.get(use.name().orElseThrow().substring(PREFIX.length()));
        if(shortcut!=null){if(attrs.containsKey("method"))throw new IllegalArgumentException();methods.add(shortcut);}
        if(attrs.containsKey("method")) {
            var value=attrs.get("method");
            if(value instanceof LiteralConditionAnnotation.Enums enums)for(String name:enums.names()) {
                String method=name.substring(name.lastIndexOf('.')+1);if(!METHODS.contains(method))throw new IllegalArgumentException();methods.add(method);
            }else if(!(value instanceof LiteralConditionAnnotation.Strings strings&&strings.values().isEmpty()))throw new IllegalArgumentException();
        }
        return new Mapping(paths.stream().map(p->resolve(p,properties)).toList(),methods,
                LiteralConditionAnnotation.strings(attrs,"params",List.of()).stream().map(p->resolve(p,properties)).toList(),LiteralConditionAnnotation.strings(attrs,"headers",List.of()).stream().map(p->resolve(p,properties)).toList(),
                LiteralConditionAnnotation.strings(attrs,"consumes",List.of()).stream().map(p->resolve(p,properties)).toList(),LiteralConditionAnnotation.strings(attrs,"produces",List.of()).stream().map(p->resolve(p,properties)).toList(),use.evidence());
    }
    private static String resolve(String path,Map<String,String> properties) {
        for(int depth=0;path.contains("${")&&depth<32;depth++) {
            int start=path.indexOf("${"),end=path.indexOf('}',start);if(end<0)throw new IllegalArgumentException();
            String key=path.substring(start+2,end);int colon=key.indexOf(':');String value=properties.get(colon<0?key:key.substring(0,colon));
            if(value==null&&colon>=0)value=key.substring(colon+1);if(value==null)throw new IllegalArgumentException();path=path.substring(0,start)+value+path.substring(end+1);
            if(path.length()>16384)throw new IllegalArgumentException();
        }
        if(path.contains("${")||path.contains("#{")||path.indexOf('\0')>=0)throw new IllegalArgumentException();return path;
    }
    private static String combine(String parent,String child) {
        // Wildcard concatenation depends on the selected Ant/PathPattern strategy.
        if(parent.contains("*")||parent.contains("?"))throw new IllegalArgumentException();
        if(parent.isEmpty()&&child.isEmpty())return "";
        if(parent.isEmpty())return child.startsWith("/")?child:"/"+child;
        if(child.isEmpty())return parent.startsWith("/")?parent:"/"+parent;
        String base=parent.startsWith("/")?parent:"/"+parent;
        return base+(base.endsWith("/")&&child.startsWith("/")?child.substring(1):!base.endsWith("/")&&!child.startsWith("/")?"/"+child:child);
    }
    private static List<String> sorted(Collection<String> values){return values.stream().distinct().sorted().toList();}
    private static List<String> union(List<String>a,List<String>b){var values=new TreeSet<>(a);values.addAll(b);return List.copyOf(values);}
    private static void issue(List<UniversalSpringEvidence.Issue> issues,UniversalSpringEvidence.Reason reason,EntityIdentity method,ConditionEvidence evidence){issues.add(new UniversalSpringEvidence.Issue(reason,method.value(),List.of(evidence)));}
    private WebRouteMapper() {}
}
