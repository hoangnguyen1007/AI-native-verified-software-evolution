package com.evolution.analysis.treesitter;

import com.evolution.analysis.polyglot.SourceCoordinates;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.treesitter.TSNode;

/** Data-only generated-API syntax candidates. Names do not establish compiler
 * binding, server activation, address selection or deployed traffic. */
final class GrpcSourceSyntax {
    private static final Set<String> CALLS=Set.of("call","call_expression","invocation_expression","object_creation_expression","new_expression");
    private static final Set<String> FUNCTIONS=Set.of("function_definition","function_declaration","method_declaration","method_definition","arrow_function");
    private static final Set<String> CLASSES=Set.of("class_definition","class_declaration");
    private static final String ID="[A-Za-z_][A-Za-z0-9_]*";
    private final String language;private final byte[] bytes;private final SourceCoordinates coordinates;
    private final TreeSitterSourceFrontend.Sink sink;private final BooleanSupplier cancelled;
    private final Map<String,Set<String>> aliases=new HashMap<>();
    private final Set<String> invalid=new HashSet<>();
    private final Map<Integer,Set<String>> serverClasses=new HashMap<>();
    private final Map<String,TSNode> pythonClasses=new LinkedHashMap<>();
    private final Map<Integer,String> pythonBases=new HashMap<>();
    private final Set<String> ambiguousPythonClasses=new HashSet<>();
    private final Map<String,Set<String>> serverTypes=new HashMap<>();
    private final Map<String,TSNode> typeAliases=new HashMap<>();
    private final Set<String> invalidTypeAliases=new HashSet<>();
    private boolean newShadowed;
    private boolean recovered;
    private long emitted;
    private GrpcSourceSyntax(String language,byte[] bytes,SourceCoordinates coordinates,TreeSitterSourceFrontend.Sink sink,BooleanSupplier cancelled){this.language=language;this.bytes=bytes;this.coordinates=coordinates;this.sink=sink;this.cancelled=cancelled;}
    static long acquire(String language,TSNode root,byte[] bytes,SourceCoordinates coordinates,TreeSitterSourceFrontend.Sink sink,BooleanSupplier cancelled)throws IOException {
        if(!Set.of("go","python","csharp","javascript","typescript","tsx").contains(language))return 0;
        String source=coordinates.text();
        if(language.equals("go")&&!source.contains("Client")&&!source.contains("Register")
                ||language.equals("python")&&!source.contains("_pb2_grpc")
                ||language.equals("csharp")&&!source.contains("Client")&&!source.contains("Base")
                ||Set.of("javascript","typescript","tsx").contains(language)&&!source.contains("addService")&&!source.contains("grpc.credentials"))return 0;
        var reader=new GrpcSourceSyntax(language,bytes,coordinates,sink,cancelled);
        reader.recovered=root.hasError();reader.walk(root,true);reader.inheritPythonServers();reader.registrations(root);reader.walk(root,false);return reader.emitted;
    }
    private void walk(TSNode root,boolean inventory)throws IOException {
        var pending=new ArrayDeque<TSNode>();pending.push(root);
        while(!pending.isEmpty()) {
            if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("SYNTAX_CANCELLED");
            var node=pending.pop();String kind=node.getType();
            if(inventory) {
                if(language.equals("go")&&Set.of("function_declaration","var_spec","const_spec","type_spec").contains(kind)&&text(field(node,"name")).equals("new"))newShadowed=true;
                if(Set.of("assignment","assignment_statement","short_var_declaration","variable_declarator").contains(kind))alias(node);
                if(CLASSES.contains(kind))serverClass(node);
                if(FUNCTIONS.contains(kind))parameters(node);
            } else {
                if(CALLS.contains(kind)){call(node);javascriptServer(node);}
                if(FUNCTIONS.contains(kind))handler(node);
            }
            // Syntax inside comments and string bodies never supplies a candidate.
            if(kind.contains("comment")||kind.contains("string"))continue;
            for(int i=node.getNamedChildCount()-1;i>=0;i--)pending.push(node.getNamedChild(i));
        }
    }
    private void parameters(TSNode function) {
        var parameters=field(function,"parameters");if(parameters==null)return;
        for(int i=0;i<parameters.getNamedChildCount();i++) {
            var parameter=parameters.getNamedChild(i);var name=field(parameter,"name");if(name==null&&parameter.getType().equals("identifier"))name=parameter;
            if(name==null&&Set.of("typed_parameter","default_parameter").contains(parameter.getType())&&parameter.getNamedChildCount()>0)name=parameter.getNamedChild(0);
            String value=text(name);if(value.matches(ID))invalid.add(function.getStartByte()+"|"+value);
            if(language.equals("go")&&value.equals("new"))newShadowed=true;
        }
    }
    private void alias(TSNode node) {
        TSNode left=field(node,"left"),right=field(node,"right");
        if(left==null)left=field(node,"name");if(right==null)right=field(node,"value");
        if(right==null&&node.getType().equals("variable_declarator"))for(int i=0;i<node.getNamedChildCount();i++){var child=node.getNamedChild(i);if(child.getType().equals("equals_value_clause")&&child.getNamedChildCount()==1)right=child.getNamedChild(0);}
        if(right==null&&node.getType().equals("variable_declarator")&&node.getNamedChildCount()>1)right=node.getNamedChild(node.getNamedChildCount()-1);
        if(left==null||right==null)return;String name=text(left).strip();
        if(!name.matches("(?:self\\.|this\\.)?"+ID))return;
        if(language.equals("python")&&scopeParent(node)==null)ambiguousPythonClasses.add(name);
        while(right.getType().equals("expression_list")&&right.getNamedChildCount()==1)right=right.getNamedChild(0);
        if(language.equals("go")) {
            if(name.equals("new"))newShadowed=true;
            String allocation=scope(node,name)+"|"+name;
            if(typeAliases.putIfAbsent(allocation,right)!=null)invalidTypeAliases.add(allocation);
        }
        var service=constructor(right);String key=scope(node,name)+"|"+name;
        if(service.isEmpty()){invalid.add(key);return;}
        aliases.computeIfAbsent(key,ignored->new TreeSet<>()).add(service.orElseThrow());
    }
    private Optional<String> constructor(TSNode node) {
        if(!CALLS.contains(node.getType()))return Optional.empty();String name=text(head(node)).strip();
        String expression;
        if(language.equals("go"))expression="(?:"+ID+"\\.)?New("+ID+")Client";
        else if(language.equals("python"))expression="(?:"+ID+"_pb2_grpc)\\.("+ID+")Stub";
        else if(language.equals("csharp"))expression="(?:"+ID+"\\.)*("+ID+")Client";
        else {
            var args=field(node,"arguments");if(args==null||!text(args).matches("(?s).*\\bgrpc\\.credentials\\..*"))return Optional.empty();
            expression="(?:"+ID+"\\.)+("+ID+")";
        }
        var match=Pattern.compile(expression).matcher(name);return match.matches()?Optional.of(match.group(1)):Optional.empty();
    }
    private void call(TSNode node)throws IOException {
        var function=head(node);String written=text(function).strip();int dot=written.lastIndexOf('.');if(dot<0)return;
        String method=written.substring(dot+1).strip();if(!method.matches(ID))return;
        Set<String> services=new TreeSet<>();var receiver=field(function,language.equals("go")?"operand":"object");
        if(receiver==null)receiver=field(function,"expression");
        if(receiver!=null)constructor(receiver).ifPresent(services::add);
        String name=written.substring(0,dot).strip();
        if(name.matches("(?:self\\.|this\\.)?"+ID)) {
            TSNode owner=node;
            while(owner!=null&&!owner.isNull()) {
                String key=scope(owner,name)+"|"+name;if(invalid.contains(key))break;
                var values=aliases.get(key);if(values!=null){services.addAll(values);break;}
                owner=scopeParent(owner);
            }
        }
        for(String service:services)emit(node,"RPC_CALL_CANDIDATE",service,method,Map.of("methodStyle",language.equals("csharp")?"CSHARP_ASYNC":Set.of("javascript","typescript","tsx").contains(language)?"LOWER_CAMEL":"EXACT","trigger","GENERATED_CLIENT_SYNTAX"));
    }
    private void serverClass(TSNode node) {
        boolean localPython=language.equals("python")&&node.getParent()!=null&&node.getParent().getType().equals("module");
        if(localPython) {
            String name=text(field(node,"name"));if(pythonClasses.putIfAbsent(name,node)!=null)ambiguousPythonClasses.add(name);
        }
        TSNode bases=field(node,language.equals("python")?"superclasses":"bases");
        if(bases==null)for(int i=0;i<node.getNamedChildCount();i++)if(node.getNamedChild(i).getType().equals("base_list"))bases=node.getNamedChild(i);
        if(bases==null)return;
        String expression=language.equals("python")?"\\b"+ID+"_pb2_grpc\\.("+ID+")Servicer\\b":"\\b(?:"+ID+"\\.)+("+ID+")Base\\b";
        var match=Pattern.compile(expression).matcher(text(bases));var services=new TreeSet<String>();while(match.find())services.add(match.group(1));
        if(!services.isEmpty())serverClasses.put(node.getStartByte(),services);
        if(localPython) {
            if(bases.getNamedChildCount()==1&&bases.getNamedChild(0).getType().equals("identifier"))pythonBases.put(node.getStartByte(),text(bases.getNamedChild(0)));
        }
    }
    private void inheritPythonServers() {
        for(var entry:pythonClasses.entrySet()) {
            String baseName=pythonBases.get(entry.getValue().getStartByte());var base=pythonClasses.get(baseName);
            if(base==null||ambiguousPythonClasses.contains(entry.getKey())||ambiguousPythonClasses.contains(baseName)||base.getStartByte()>=entry.getValue().getStartByte())continue;
            var services=serverClasses.get(base.getStartByte());
            if(services!=null&&!services.isEmpty())serverClasses.put(entry.getValue().getStartByte(),Set.copyOf(services));
        }
    }
    private void handler(TSNode node)throws IOException {
        String method=text(field(node,"name"));if(!method.matches(ID))return;
        if(language.equals("go")&&node.getType().equals("method_declaration")) {
            var receiver=field(node,"receiver");if(receiver==null||receiver.getNamedChildCount()!=1)return;
            String type=text(field(receiver.getNamedChild(0),"type")).replaceFirst("^\\*","").strip();
            for(String service:serverTypes.getOrDefault(type,Set.of()))emit(node,"RPC_HANDLER_CANDIDATE",service,method,Map.of("methodStyle","EXACT","trigger","SERVER_REGISTRATION_AND_RECEIVER_SYNTAX"));
            return;
        }
        var owner=parentClass(node);if(owner==null)return;var services=serverClasses.get(owner.getStartByte());if(services==null)return;
        if(language.equals("csharp")&&!text(node).matches("(?s)^\\s*(?:(?:public|protected|internal|private|async|sealed)\\s+)*override\\b.*"))return;
        for(String service:services)emit(node,"RPC_HANDLER_CANDIDATE",service,method,Map.of("methodStyle","EXACT","trigger","GENERATED_SERVER_BASE_SYNTAX"));
    }
    private void registrations(TSNode root)throws IOException {
        if(!language.equals("go"))return;var pending=new ArrayDeque<TSNode>();pending.push(root);
        while(!pending.isEmpty()) {
            if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("SYNTAX_CANCELLED");
            var node=pending.pop();
            if(node.getType().equals("call_expression")) {
                var service=Pattern.compile("(?:"+ID+"\\.)?Register("+ID+")Server").matcher(text(head(node)));var args=field(node,"arguments");
                if(service.matches()&&args!=null&&args.getNamedChildCount()==2) {
                    var implementation=args.getNamedChild(1);String type=type(implementation);
                    String alias=scope(node,text(implementation))+"|"+text(implementation);
                    if(type.isEmpty()&&!invalidTypeAliases.contains(alias)&&typeAliases.containsKey(alias))type=type(typeAliases.get(alias));
                    if(!type.isEmpty())serverTypes.computeIfAbsent(type,ignored->new TreeSet<>()).add(service.group(1));
                }
            }
            if(node.getType().contains("comment")||node.getType().contains("string"))continue;
            for(int i=node.getNamedChildCount()-1;i>=0;i--)pending.push(node.getNamedChild(i));
        }
    }
    private String type(TSNode node) {
        if(node!=null&&language.equals("go")&&node.getType().equals("call_expression")&&text(head(node)).equals("new")&&!newShadowed) {
            var arguments=field(node,"arguments");if(arguments==null||arguments.getNamedChildCount()!=1)return "";
            String allocated=text(arguments.getNamedChild(0));return allocated.matches(ID)?allocated:"";
        }
        if(node.getType().equals("unary_expression")&&text(node).startsWith("&"))node=field(node,"operand");
        if(node==null||!node.getType().equals("composite_literal"))return "";
        String type=text(field(node,"type"));return type.matches(ID)?type:"";
    }
    private void javascriptServer(TSNode node)throws IOException {
        if(!Set.of("javascript","typescript","tsx").contains(language)||!text(head(node)).endsWith(".addService"))return;
        var args=field(node,"arguments");if(args==null||args.getNamedChildCount()!=2)return;
        var service=Pattern.compile("(?:"+ID+"\\.)+("+ID+")\\.service").matcher(text(args.getNamedChild(0)).strip());if(!service.matches())return;
        var handlers=args.getNamedChild(1);if(!handlers.getType().equals("object"))return;
        for(int i=0;i<handlers.getNamedChildCount();i++) {
            var entry=handlers.getNamedChild(i);var key=field(entry,"key");if(key==null)key=field(entry,"name");if(key==null&&entry.getType().equals("shorthand_property_identifier"))key=entry;
            String name=text(key);if(name.matches(ID))emit(entry,"RPC_HANDLER_CANDIDATE",service.group(1),name,Map.of("methodStyle","LOWER_CAMEL","trigger","SERVER_ADD_SERVICE_SYNTAX"));
        }
    }
    private void emit(TSNode node,String category,String service,String method,Map<String,String> extra)throws IOException {
        var span=coordinates.range(node.getStartByte(),node.getEndByte(),SourceCoordinates.Encoding.UTF8);var attributes=new TreeMap<>(extra);attributes.put("service",service);attributes.put("method",method);attributes.put("serviceQualification","SIMPLE_NAME");
        TSNode owner=scopeParent(node);String id=owner==null?"":coordinates.range(owner.getStartByte(),owner.getEndByte(),SourceCoordinates.Encoding.UTF8).start()+":"+coordinates.range(owner.getStartByte(),owner.getEndByte(),SourceCoordinates.Encoding.UTF8).end();
        String written=text(head(node));if(written.length()>4096)written=written.substring(0,4096);
        sink.accept(new TreeSitterSourceFrontend.Fact(category,node.getType(),service+"."+method,written,span,id,recovered,attributes));emitted++;
    }
    private String scope(TSNode node,String name) {
        TSNode current=name.startsWith("self.")||name.startsWith("this.")?parentClass(node):scopeParent(node);
        return current==null?"file":Integer.toString(current.getStartByte());
    }
    private static TSNode scopeParent(TSNode node) {
        for(var parent=node.getParent();parent!=null&&!parent.isNull();parent=parent.getParent())if(FUNCTIONS.contains(parent.getType())||CLASSES.contains(parent.getType()))return parent;return null;
    }
    private static TSNode parentClass(TSNode node){for(var parent=node.getParent();parent!=null&&!parent.isNull();parent=parent.getParent())if(CLASSES.contains(parent.getType()))return parent;return null;}
    private static TSNode field(TSNode node,String name){var child=node.getChildByFieldName(name);return child==null||child.isNull()?null:child;}
    private static TSNode head(TSNode node){for(String name:List.of("function","type","constructor")){var child=field(node,name);if(child!=null)return child;}return node;}
    private String text(TSNode node){return node==null?"":new String(bytes,node.getStartByte(),node.getEndByte()-node.getStartByte(),StandardCharsets.UTF_8);}
}
