package com.evolution.analysis.treesitter;

import com.evolution.analysis.polyglot.SourceCoordinates;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.treesitter.*;

/** Neutral, data-only syntax acquisition. A written call is not a bound call target. */
public final class TreeSitterSourceFrontend {
    public static final String PROVIDER="source.tree-sitter:m8.4-rpc-local-inheritance-v5";
    /** Read the loaded adapter version at runtime, avoiding consumer constant inlining. */
    public static String provider(){return PROVIDER;}
    private record Grammar(String library,String binding,String version) {}
    private static final Map<String,Grammar> GRAMMARS=Map.ofEntries(
            entry("go","go","Go","0.25.0"),entry("python","python","Python","0.25.0"),entry("csharp","c-sharp","CSharp","0.23.1"),
            entry("javascript","javascript","Javascript","0.25.0"),entry("typescript","typescript","Typescript","0.23.2"),entry("tsx","tsx","Tsx","0.23.2"),
            entry("kotlin","kotlin","Kotlin","0.3.8.1"),entry("scala","scala","Scala","0.24.0"),entry("rust","rust","Rust","0.24.0"),
            entry("c","c","C","0.24.1"),entry("cpp","cpp","Cpp","0.23.4"),entry("php","php","Php","0.24.2"),entry("ruby","ruby","Ruby","0.23.1"),
            entry("dart","dart","Dart","master-a"),entry("swift","swift","Swift","0.5.0"),entry("objectivec","objc","Objc","main-a"));
    private static Map.Entry<String,Grammar> entry(String language,String library,String binding,String version){return Map.entry(language,new Grammar("tree-sitter-"+library,"org.treesitter.TreeSitter"+binding,version));}
    public static Set<String> languages(){var languages=new TreeSet<>(GRAMMARS.keySet());languages.add("protobuf");return Collections.unmodifiableSet(languages);}
    private static final Set<String> CALLS=Set.of("call_expression","invocation_expression","function_call_expression","call","method_invocation","object_creation_expression","method_call_expression","macro_invocation","constructor_invocation","new_expression");
    private static final Set<String> ROUTES=Set.of("get","post","put","patch","delete","options","head","route","all","HandleFunc","Handle","GET","POST","PUT","PATCH","DELETE","Get","Post","Put","Patch","Delete","Head","Options","All","MapGet","MapPost","MapPut","MapDelete","MapMethods","HttpGet","HttpPost","HttpPut","HttpDelete","Route","RequestMapping","GetMapping","PostMapping","PutMapping","PatchMapping","DeleteMapping","api_route","add_api_route","websocket","MethodFunc","path","re_path");
    private record Frame(TSNode node,String owner) {}
    public record Fact(String category,String nodeKind,String name,String written,SourceCoordinates.Range span,String owner,boolean recovered,Map<String,String> attributes) {
        public Fact {attributes=Map.copyOf(attributes);}
        public Fact(String category,String nodeKind,String name,String written,SourceCoordinates.Range span,String owner,boolean recovered){this(category,nodeKind,name,written,span,owner,recovered,Map.of());}
        public Map<String, Object> toMap() {
            var attrs = new TreeMap<String, Object>(attributes);
            var spanMap = new TreeMap<String, Object>();
            spanMap.put("end", java.math.BigDecimal.valueOf(span.end()));
            spanMap.put("endColumn", java.math.BigDecimal.valueOf(span.endColumn()));
            spanMap.put("endLine", java.math.BigDecimal.valueOf(span.endLine()));
            spanMap.put("start", java.math.BigDecimal.valueOf(span.start()));
            spanMap.put("startColumn", java.math.BigDecimal.valueOf(span.startColumn()));
            spanMap.put("startLine", java.math.BigDecimal.valueOf(span.startLine()));
            var map = new TreeMap<String, Object>();
            map.put("attributes", attrs);
            map.put("category", category);
            map.put("name", name);
            map.put("nodeKind", nodeKind);
            map.put("owner", owner);
            map.put("recovered", recovered);
            map.put("span", spanMap);
            map.put("written", written);
            return map;
        }
    }
    public record Summary(long nodes,long facts,long errors,String grammar,String toolIdentity) {
        public Map<String, Object> toMap() {
            var map = new TreeMap<String, Object>();
            map.put("errors", java.math.BigDecimal.valueOf(errors));
            map.put("facts", java.math.BigDecimal.valueOf(facts));
            map.put("grammar", grammar);
            map.put("nodes", java.math.BigDecimal.valueOf(nodes));
            map.put("toolIdentity", toolIdentity);
            return map;
        }
    }
    @FunctionalInterface public interface Sink {void accept(Fact fact)throws IOException;}
    private static final Map<String, java.lang.reflect.Constructor<?>> GRAMMAR_CTORS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.regex.Pattern PYTHON_HTTP_CLIENT = java.util.regex.Pattern.compile("(?:requests|httpx|session|client|aiohttp)\\.(?:get|post|put|patch|delete|head|options)");
    private static final java.util.regex.Pattern GO_HTTP_CLIENT = java.util.regex.Pattern.compile("(?:[A-Za-z_][A-Za-z0-9_]*(?:http|Http|client|Client)[A-Za-z0-9_]*)\\.(?:Get|Post|Head)");
    private static final java.util.regex.Pattern CSHARP_HTTP_CLIENT = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_.]*\\.(?:GetAsync|PostAsync|PutAsync|PatchAsync|DeleteAsync|GetStringAsync|GetByteArrayAsync)");
    private static final java.util.regex.Pattern JS_HTTP_CLIENT = java.util.regex.Pattern.compile("(?:axios|http|this\\.http|httpClient|this\\.httpClient|api|apiClient|client|ky|got|superagent)\\.(?:get|post|put|patch|delete|head|options)");
    private static final java.util.regex.Pattern RUST_HTTP_CLIENT = java.util.regex.Pattern.compile("(?:reqwest|client|http_client|httpClient)\\.(?:get|post|put|patch|delete)");
    private static final java.util.regex.Pattern DART_HTTP_CLIENT = java.util.regex.Pattern.compile("(?:dio|http|client)\\.(?:get|post|put|patch|delete|head)");
    private static final java.util.regex.Pattern KOTLIN_HTTP_CLIENT = java.util.regex.Pattern.compile("(?:[A-Za-z_][A-Za-z0-9_.]*)?(?:httpClient|ktor|client|okhttp)\\.(?:get|post|put|patch|delete|head)");
    private static final java.util.regex.Pattern EVENT_PRODUCER_CALL = java.util.regex.Pattern.compile("(?i)(?:[A-Za-z_][A-Za-z0-9_.]*)?(?:kafka|kafkaTemplate|producer|eventBus|eventPublisher|applicationEventPublisher|emitter|broker|nats|nc|amqp|channel|rabbitTemplate|sqs|sns|pubsub|redis)\\.(?:send|produce|publish|publishEvent|basic_publish|emit|post|convertAndSend|Publish)");
    private static final java.util.regex.Pattern EVENT_CONSUMER_CALL = java.util.regex.Pattern.compile("(?i)(?:[A-Za-z_][A-Za-z0-9_.]*)?(?:kafka|consumer|eventBus|emitter|broker|nats|nc|amqp|channel|pubsub|redis)\\.(?:subscribe|consume|basic_consume|on|Subscribe|QueueSubscribe)");
    private static final Set<String> EVENT_CONSUMER_ANNOTATIONS = Set.of("KafkaListener","RabbitListener","EventPattern","MessagePattern","SqsListener");
    private static final java.util.regex.Pattern HTTP_METHOD_PREFIX = java.util.regex.Pattern.compile("^(?:Map|Http)");
    private static final java.util.regex.Pattern HTTP_METHOD_SUFFIX = java.util.regex.Pattern.compile("(?:Async|Mapping)$");

    public Summary analyze(String language,byte[] bytes,Sink sink,BooleanSupplier cancelled)throws IOException {
        return parse(language,bytes,sink,cancelled,false);
    }
    public Summary analyze(String language,byte[] bytes,Sink sink,BooleanSupplier cancelled,boolean fastArchitecture)throws IOException {
        return parse(language,bytes,sink,cancelled,fastArchitecture);
    }
    public Summary parse(String language,byte[] bytes,Sink sink,BooleanSupplier cancelled)throws IOException {
        return parse(language,bytes,sink,cancelled,false);
    }
    public Summary parse(String language,byte[] bytes,Sink sink,BooleanSupplier cancelled,boolean fastArchitecture)throws IOException {
        if(language.equals("protobuf"))return ProtobufSourceFrontend.parse(bytes,sink,cancelled);
        if(System.getProperty("tree-sitter-lib")!=null)throw new IOException("SYNTAX_EXTERNAL_LIBRARY_OVERRIDE_REJECTED");
        var grammar=GRAMMARS.get(language);if(grammar==null)throw new IOException("SYNTAX_GRAMMAR_UNAVAILABLE");
        check(cancelled);
        var encoding = charset(bytes);
        var positions = new SourceCoordinates(bytes, encoding);
        // SourceCoordinates measures UTF-8 offsets over the original decoded text.
        // UTF-8 inputs have already been validated and can go straight to the parser.
        byte[] utf8 = encoding.equals("UTF-8") ? bytes : positions.text().getBytes(StandardCharsets.UTF_8);
        String tool=toolIdentity(grammar);long nodes=0,facts=0,errors=0;
        try(var parser=new TSParser()) {
            var ctor=GRAMMAR_CTORS.get(grammar.binding());
            if(ctor==null){ctor=Class.forName(grammar.binding()).getConstructor();GRAMMAR_CTORS.put(grammar.binding(),ctor);}
            var binding=(TSLanguage)ctor.newInstance();
            if(!parser.setLanguage(binding))throw new IOException("SYNTAX_GRAMMAR_ABI_MISMATCH");
            try(var tree=parser.parseWithOptions(new byte[8192],null,(buffer,offset,point)->{
                if(offset>=utf8.length)return 0;int count=Math.min(buffer.length,utf8.length-offset);System.arraycopy(utf8,offset,buffer,0,count);return count;
            },TSInputEncoding.TSInputEncodingUTF8,state->cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())) {
                check(cancelled);var root=tree.getRootNode();boolean recovered=root.hasError();var pending=new ArrayDeque<Frame>();pending.push(new Frame(root,""));
                while(!pending.isEmpty()) {
                    check(cancelled);var frame=pending.pop();var node=frame.node();nodes++;String kind=node.getType(),category=category(kind);String owner=frame.owner();
                    if(fastArchitecture&&category.equals("TYPE_SYNTAX"))category="";
                    if(language.equals("dart")&&kind.equals("selector")&&node.getNamedChildCount()>0&&node.getNamedChild(0).getType().equals("argument_part"))category="CALL";
                    if(language.equals("python")&&kind.equals("argument_list")&&!node.getParent().isNull()&&node.getParent().getType().equals("class_definition"))category="HERITAGE";
                    if(node.isError()||node.isMissing()){errors++;category="ERROR";}
                    if(!category.isEmpty()&&node.getEndByte()>node.getStartByte()) {
                        var span=positions.range(node.getStartByte(),node.getEndByte(),SourceCoordinates.Encoding.UTF8);
                        var nameNode=name(node);String name=text(nameNode,utf8);String written=text(head(node),utf8);
                        if(category.equals("DECLARATION")&&nameNode!=null)written=name;
                        else if(category.equals("HERITAGE")||category.equals("IMPORT"))written=text(node,utf8);
                        if(language.equals("dart")&&category.equals("CALL")&&kind.equals("selector")) {
                            var parent=node.getParent();written=new String(utf8,parent.getStartByte(),node.getStartByte()-parent.getStartByte(),StandardCharsets.UTF_8);
                        }
                        sink.accept(new Fact(category,kind,name,excerpt(written),span,owner,recovered));facts++;
                        if(category.equals("DECLARATION"))owner=span.start()+":"+span.end();
                        if(category.equals("CALL")||kind.equals("attribute")||kind.equals("annotation")) {
                            String tail=written.substring(Math.max(written.lastIndexOf('.'),written.lastIndexOf(':'))+1).trim();
                            String literal=ROUTES.contains(tail)&&!httpClient(language,written)?routeLiteral(node,utf8):null;
                            if(literal!=null&&literal.length()<=4096) {sink.accept(new Fact("ROUTE_CANDIDATE",kind,literal,excerpt(written),span,frame.owner(),recovered,Map.of("method",httpMethod(tail))));facts++;}
                            if(category.equals("CALL")&&httpClient(language,written)) {
                                String path=firstLiteral(node,utf8);
                                if(path!=null&&path.length()<=4096){sink.accept(new Fact("HTTP_CALL_CANDIDATE",kind,path,excerpt(written),span,frame.owner(),recovered,Map.of("method",clientMethod(node,written,utf8))));facts++;}
                            }
                            facts+=emitEventCandidate(category,kind,node,written,tail,utf8,span,frame.owner(),recovered,sink);
                        }
                    }
                    for(int i=node.getNamedChildCount()-1;i>=0;i--)pending.push(new Frame(node.getNamedChild(i),owner));
                }
                facts+=GrpcSourceSyntax.acquire(language,root,utf8,positions,sink,cancelled);
                if(recovered&&errors==0)errors=1;
            }
        } catch(ReflectiveOperationException|LinkageError failure){throw new IOException("SYNTAX_BUNDLED_GRAMMAR_UNAVAILABLE",failure);}
        return new Summary(nodes,facts,errors,grammar.library()+":"+grammar.version(),tool);
    }
    private static boolean httpClient(String language,String written) {
        if(language.equals("python"))return PYTHON_HTTP_CLIENT.matcher(written).matches();
        if(language.equals("go"))return Set.of("http.Get","http.Post","http.Head","http.NewRequest","http.NewRequestWithContext").contains(written)||GO_HTTP_CLIENT.matcher(written).matches();
        if(language.equals("csharp"))return CSHARP_HTTP_CLIENT.matcher(written).matches();
        if(language.equals("rust"))return written.equals("reqwest::get")||RUST_HTTP_CLIENT.matcher(written).matches();
        if(language.equals("dart"))return DART_HTTP_CLIENT.matcher(written).matches();
        if(Set.of("kotlin","scala").contains(language))return KOTLIN_HTTP_CLIENT.matcher(written).matches();
        if(!Set.of("javascript","typescript","tsx").contains(language))return false;
        return Set.of("fetch","window.fetch","globalThis.fetch","$fetch","useFetch","useSWR").contains(written)
                ||JS_HTTP_CLIENT.matcher(written).matches();
    }
    private static String httpMethod(String tail) {
        String method=HTTP_METHOD_SUFFIX.matcher(HTTP_METHOD_PREFIX.matcher(tail).replaceFirst("")).replaceFirst("").toUpperCase(Locale.ROOT);
        if(Set.of("GETSTRING","GETBYTEARRAY").contains(method))method="GET";
        return Set.of("GET","POST","PUT","PATCH","DELETE","HEAD","OPTIONS","TRACE").contains(method)?method:"UNKNOWN";
    }
    private static String firstLiteral(TSNode call,byte[] bytes) {
        String raw=firstStringToken(call,bytes);
        return raw!=null&&raw.indexOf('/')>=0?raw:null;
    }
    private static String firstStringToken(TSNode call,byte[] bytes) {
        var args=argumentsNode(call);if(args==null||args.getNamedChildCount()==0)return null;
        var first=args.getNamedChild(0);
        if(Set.of("argument","value_argument").contains(first.getType())&&first.getNamedChildCount()>=1)first=first.getNamedChild(first.getNamedChildCount()-1);
        String raw=text(first,bytes);
        // Escapes, interpolation and computed expressions need a language-native decoder.
        if(!Set.of("string","string_literal","line_string_literal","multi_line_string_literal","interpreted_string_literal","template_string").contains(first.getType())||interpolated(first)||raw.indexOf('\\')>=0||raw.length()<3)return null;
        char q=raw.charAt(0);
        return (q=='\''||q=='"'||q=='`')&&raw.charAt(raw.length()-1)==q?raw:null;
    }
    private static TSNode argumentsNode(TSNode node) {
        var args=field(node,"arguments");if(args!=null)return args;
        for(int i=0;i<node.getNamedChildCount();i++){
            var c=node.getNamedChild(i);
            if(Set.of("arguments","value_arguments","argument_list").contains(c.getType()))return c;
            if(c.getType().equals("call_suffix")){
                for(int j=0;j<c.getNamedChildCount();j++){
                    var nested=c.getNamedChild(j);
                    if(Set.of("arguments","value_arguments","argument_list").contains(nested.getType()))return nested;
                }
            }
        }
        return null;
    }
    private static int emitEventCandidate(String category,String kind,TSNode node,String written,String tail,byte[] utf8,
                                          SourceCoordinates.Range span,String owner,boolean recovered,Sink sink) throws IOException {
        boolean producer=category.equals("CALL")&&EVENT_PRODUCER_CALL.matcher(written).matches();
        boolean consumer=(category.equals("CALL")&&EVENT_CONSUMER_CALL.matcher(written).matches())
                ||EVENT_CONSUMER_ANNOTATIONS.contains(tail);
        if(!producer&&!consumer)return 0;
        String topic=firstStringToken(node,utf8);
        if(topic==null||topic.length()>258)return 0;
        String lower=written.toLowerCase(Locale.ROOT);
        String protocol=lower.contains("kafka")?"KAFKA":(lower.contains("rabbit")||lower.contains("amqp"))?"AMQP":lower.contains("nats")||lower.contains("nc.")?"NATS":lower.contains("sqs")||lower.contains("sns")?"AWS_MESSAGING":lower.contains("redis")?"REDIS_PUBSUB":"EVENT_BUS";
        sink.accept(new Fact(producer?"EVENT_PRODUCER_CANDIDATE":"EVENT_CONSUMER_CANDIDATE",kind,topic,excerpt(written),span,owner,recovered,Map.of("role",producer?"PRODUCER":"CONSUMER","protocol",protocol)));
        return 1;
    }
    private static String clientMethod(TSNode call,String written,byte[] bytes) {
        if(!written.endsWith("fetch"))return httpMethod(written.substring(written.lastIndexOf('.')+1));
        var args=field(call,"arguments");if(args.getNamedChildCount()==1)return "GET";
        if(args.getNamedChildCount()!=2)return "UNKNOWN";
        var options=args.getNamedChild(1);if(!options.getType().equals("object"))return "UNKNOWN";
        String method="GET";boolean seen=false;
        for(int i=0;i<options.getNamedChildCount();i++) {
            var pair=options.getNamedChild(i);if(!pair.getType().equals("pair"))return "UNKNOWN";
            var key=field(pair,"key");String name=text(key,bytes);
            if(name.equals("method")||name.equals("'method'")||name.equals("\"method\"")) {
                if(seen)return "UNKNOWN";seen=true;var value=field(pair,"value");String raw=text(value,bytes);
                if(value==null||!value.getType().equals("string")||raw.indexOf('\\')>=0||raw.length()<2)return "UNKNOWN";
                method=raw.substring(1,raw.length()-1);
                if(!method.equals(httpMethod(method)))return "UNKNOWN";
            }else if(key==null||!Set.of("property_identifier","string","number").contains(key.getType()))return "UNKNOWN";
        }
        return method;
    }
    private static String category(String kind) {
        if(CALLS.contains(kind))return "CALL";
        if(kind.contains("import")||Set.of("use_declaration","using_declaration","using_directive","preproc_include","namespace_use_declaration").contains(kind))return "IMPORT";
        if(kind.endsWith("_declaration")||kind.endsWith("_definition")||Set.of("class_specifier","struct_specifier","function_signature","function_item","struct_item","enum_item","trait_item","impl_item","method","singleton_method","class","module").contains(kind))return "DECLARATION";
        if(Set.of("annotation","marker_annotation","attribute","decorator").contains(kind))return "ANNOTATION";
        if(Set.of("base_list","superclass","super_interfaces","extends_clause","implements_clause","delegation_specifier","inheritance_clause").contains(kind))return "HERITAGE";
        if(kind.endsWith("_type")||Set.of("type_identifier","type_annotation","scoped_type_identifier").contains(kind))return "TYPE_SYNTAX";
        return "";
    }
    private static TSNode name(TSNode node){var named=field(node,"name");if(named!=null)return named;var declaration=field(node,"declarator");while(declaration!=null){var child=field(declaration,"declarator");if(child==null)return declaration;declaration=child;}return null;}
    private static TSNode field(TSNode node,String name){var child=node.getChildByFieldName(name);return child==null||child.isNull()?null:child;}
    private static TSNode head(TSNode node){for(String name:List.of("function","name","method","constructor","type")){var child=field(node,name);if(child!=null)return child;}return node.getNamedChildCount()>0?node.getNamedChild(0):node;}
    private static String text(TSNode node,byte[] bytes){return node==null?"":new String(bytes,node.getStartByte(),node.getEndByte()-node.getStartByte(),StandardCharsets.UTF_8);}
    private static String excerpt(String value){if(value.length()<=4096)return value;return value.substring(0,Character.isHighSurrogate(value.charAt(4095))?4095:4096);}
    private static String routeLiteral(TSNode call,byte[] bytes){
        var arguments=argumentsNode(call);if(arguments==null)return null;
        if(arguments.getNamedChildCount()==0)return null;
        var pending=new ArrayDeque<TSNode>();pending.add(arguments.getNamedChild(0));
        while(!pending.isEmpty()){var node=pending.removeFirst();String kind=node.getType();
            if(kind.contains("string")) {
                if(interpolated(node))continue;
                if(!kind.contains("interpolat")&&text(node,bytes).indexOf('/')>=0)return text(node,bytes);
            }
            if(!CALLS.contains(kind)&&!Set.of("binary_expression","interpolated_string_expression","template_string").contains(kind))for(int i=0;i<node.getNamedChildCount();i++)pending.addLast(node.getNamedChild(i));
        }return null;
    }
    private static boolean interpolated(TSNode root){
        var pending=new ArrayDeque<TSNode>();pending.add(root);
        while(!pending.isEmpty()){var node=pending.removeFirst();String kind=node.getType();if(kind.contains("interpolat")||kind.equals("template_substitution")||kind.equals("format_expression"))return true;
            for(int i=0;i<node.getNamedChildCount();i++)pending.addLast(node.getNamedChild(i));
        }return false;
    }
    private static String charset(byte[] bytes){if(bytes.length>=2&&(bytes[0]&255)==255&&(bytes[1]&255)==254)return "UTF-16LE";if(bytes.length>=2&&(bytes[0]&255)==254&&(bytes[1]&255)==255)return "UTF-16BE";return "UTF-8";}
    private static final Map<String,String> TOOLS=new java.util.concurrent.ConcurrentHashMap<>();
    private static String toolIdentity(Grammar grammar)throws IOException {
        String existing=TOOLS.get(grammar.library());if(existing!=null)return existing;
        String os=System.getProperty("os.name").toLowerCase(Locale.ROOT),arch=System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String platform=arch.contains("amd64")||arch.contains("x86_64")?"x86_64":arch.contains("aarch64")?"aarch64":"unsupported";
        String extension=os.contains("windows")?"dll":os.contains("mac")?"dylib":"so";platform+="-"+(os.contains("windows")?"windows":os.contains("mac")?"macos":"linux-gnu");
        String identity=CanonicalJson.sha256(List.of(PROVIDER,"tree-sitter:0.26.6",grammar.library(),grammar.version(),
                resource("lib/"+platform+"-tree-sitter."+extension),resource("lib/"+platform+"-"+grammar.library()+"."+extension),
                resource(grammar.binding().replace('.','/')+".class"),resource("com/evolution/analysis/treesitter/TreeSitterSourceFrontend.class"),resource("com/evolution/analysis/treesitter/GrpcSourceSyntax.class"))).value();
        TOOLS.put(grammar.library(),identity);return identity;
    }
    private static ContentDigest resource(String name)throws IOException {
        try(var input=TreeSitterSourceFrontend.class.getClassLoader().getResourceAsStream(name)) {
            if(input==null)throw new IOException("SYNTAX_BUNDLED_TOOL_BYTES_UNAVAILABLE");var digest=MessageDigest.getInstance("SHA-256");
            input.transferTo(new DigestOutputStream(OutputStream.nullOutputStream(),digest));return new ContentDigest("sha256:"+HexFormat.of().formatHex(digest.digest()));
        }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static void check(BooleanSupplier cancelled)throws IOException{if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("SYNTAX_CANCELLED");}
}
