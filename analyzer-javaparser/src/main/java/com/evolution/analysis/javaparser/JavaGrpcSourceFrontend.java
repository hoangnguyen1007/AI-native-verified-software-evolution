package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.polyglot.SourceCoordinates;
import com.github.javaparser.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/** Selective, parser-neutral gRPC syntax evidence; never loads target classes or plugins. */
public final class JavaGrpcSourceFrontend {
    public static final String PROVIDER="source.javaparser-grpc:m8.4-v1";
    public record Fact(String category,String nodeKind,String name,String written,SourceCoordinates.Range span,String owner,boolean recovered,Map<String,String> attributes) {}
    public record Summary(long nodes,long facts,long errors,String grammar,String toolIdentity) {}
    @FunctionalInterface public interface Sink {void accept(Fact fact)throws IOException;}
    private static final Pattern SERVER=Pattern.compile("(?:[A-Za-z_][A-Za-z0-9_]*\\.)*([A-Za-z_][A-Za-z0-9_]*)Grpc\\.([A-Za-z_][A-Za-z0-9_]*)ImplBase");
    private static final Pattern STUB=Pattern.compile("(?:[A-Za-z_][A-Za-z0-9_]*\\.)*([A-Za-z_][A-Za-z0-9_]*?)(?:BlockingStub|FutureStub|Stub)");
    /** Cheap encoding-aware admission hint; it confers no semantic fact or absence claim. */
    public static boolean candidate(byte[] bytes) {
        for(int i=0;i<bytes.length;i++) {
            if(bytes[i]=='G'&&i+3<bytes.length&&bytes[i+1]=='r'&&bytes[i+2]=='p'&&bytes[i+3]=='c')return true;
            if(bytes[i]=='G'&&i+6<bytes.length&&bytes[i+1]==0&&bytes[i+2]=='r'&&bytes[i+3]==0&&bytes[i+4]=='p'&&bytes[i+5]==0&&bytes[i+6]=='c')return true;
            if(bytes[i]=='\\'&&i+1<bytes.length&&bytes[i+1]=='u')return true;
            if(bytes[i]=='\\'&&i+2<bytes.length&&bytes[i+1]==0&&bytes[i+2]=='u')return true;
        }
        return false;
    }
    public Summary parse(byte[] bytes,Sink sink,BooleanSupplier cancelled)throws IOException {
        check(cancelled);String charset=bytes.length>=2&&(bytes[0]&255)==255&&(bytes[1]&255)==254?"UTF-16LE":bytes.length>=2&&(bytes[0]&255)==254&&(bytes[1]&255)==255?"UTF-16BE":"UTF-8";
        var coordinates=new SourceCoordinates(bytes,charset);String text=coordinates.text();
        if(text.contains("\\u"))throw new IOException("JAVA_RPC_UNICODE_MAPPING_REQUIRED");
        String input=text.startsWith("\ufeff")?" "+text.substring(1):text;
        // JavaParser 3.28.2 needs stored tokens to populate original source ranges.
        // This AST/token chain lives for this one selective file only.
        var parser=new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE).setStoreTokens(true).setTabSize(1));
        var parsed=parser.parse(input);var unit=parsed.getResult().orElseThrow(()->new IOException("JAVA_RPC_SOURCE_UNAVAILABLE"));boolean recovered=!parsed.isSuccessful();
        var aliases=new HashMap<String,Set<String>>();var invalid=new HashSet<String>();long[] counts={0,0};
        for(var variable:unit.findAll(VariableDeclarator.class)) {
            check(cancelled);String key=scope(variable)+"|"+variable.getNameAsString();var service=stub(variable.getType().asString()).or(()->variable.getInitializer().flatMap(JavaGrpcSourceFrontend::factory));
            if(service.isPresent())aliases.computeIfAbsent(key,ignored->new TreeSet<>()).add(service.orElseThrow());else invalid.add(key);
        }
        for(CallableDeclaration<?> callable:unit.findAll(CallableDeclaration.class))for(var parameter:callable.getParameters()) {
            String key=anchor(callable)+"|"+parameter.getNameAsString();var service=stub(parameter.getType().asString());
            if(service.isPresent())aliases.computeIfAbsent(key,ignored->new TreeSet<>()).add(service.orElseThrow());else invalid.add(key);
        }
        for(var lambda:unit.findAll(LambdaExpr.class))for(var parameter:lambda.getParameters())invalid.add(anchor(lambda)+"|"+parameter.getNameAsString());
        for(var type:unit.findAll(ClassOrInterfaceDeclaration.class)) {
            check(cancelled);var services=new TreeSet<String>();
            type.getExtendedTypes().forEach(parent->{var matcher=SERVER.matcher(parent.asString());if(matcher.matches()&&matcher.group(1).equals(matcher.group(2)))services.add(matcher.group(1));});
            if(services.isEmpty())continue;emit(type,"DECLARATION","class_declaration",type.getNameAsString(),"",Map.of(),coordinates,recovered,sink,counts);
            for(var method:type.getMethods())if(!method.isStatic()&&method.getParameters().size()==2&&method.getAnnotationByName("Override").isPresent()) {
                emit(method,"DECLARATION","method_declaration",method.getNameAsString(),slot(type,coordinates),Map.of(),coordinates,recovered,sink,counts);
                for(String service:services)emit(method,"RPC_HANDLER_CANDIDATE","method_declaration",service+"."+method.getNameAsString(),slot(type,coordinates),attributes(service,method.getNameAsString(),"GENERATED_JAVA_SERVER_BASE_SYNTAX"),coordinates,recovered,sink,counts);
            }
        }
        for(var call:unit.findAll(MethodCallExpr.class)) {
            check(cancelled);if(call.getScope().isEmpty())continue;var receiver=call.getScope().orElseThrow();var services=new TreeSet<String>();factory(receiver).ifPresent(services::add);
            String name=receiver.toString();boolean field=name.startsWith("this.");if(field)name=name.substring(5);
            if(name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                Node owner=call;
                while(owner!=null) {
                    if(owner instanceof CallableDeclaration<?>||owner instanceof TypeDeclaration<?>||owner instanceof LambdaExpr) {
                        String key=anchor(owner)+"|"+name;if(!field&&invalid.contains(key))break;
                        var candidates=aliases.get(key);if(candidates!=null&&(!field||owner instanceof TypeDeclaration<?>)){services.addAll(candidates);break;}
                    }
                    owner=owner.getParentNode().orElse(null);
                }
            }
            for(String service:services)emit(call,"RPC_CALL_CANDIDATE","method_call",service+"."+call.getNameAsString(),"",attributes(service,call.getNameAsString(),"GENERATED_JAVA_CLIENT_SYNTAX"),coordinates,recovered,sink,counts);
        }
        String tool;
        try(var own=JavaGrpcSourceFrontend.class.getResourceAsStream("JavaGrpcSourceFrontend.class");var core=JavaParser.class.getResourceAsStream("JavaParser.class")) {
            if(own==null||core==null)throw new IOException("JAVA_RPC_TOOL_BYTES_UNAVAILABLE");tool=CanonicalJson.sha256(List.of(PROVIDER,"javaparser:3.28.2",ContentDigest.sha256(own.readAllBytes()),ContentDigest.sha256(core.readAllBytes()))).value();
        }
        return new Summary(unit.stream().count(),counts[1],parsed.getProblems().size(),PROVIDER,tool);
    }
    private static Optional<String> factory(Expression value) {
        if(!value.isMethodCallExpr())return Optional.empty();var call=value.asMethodCallExpr();
        if(!Set.of("newStub","newBlockingStub","newFutureStub").contains(call.getNameAsString())||call.getScope().isEmpty())return Optional.empty();
        String type=call.getScope().orElseThrow().toString();int dot=type.lastIndexOf('.');String simple=type.substring(dot+1);
        return simple.matches("[A-Za-z_][A-Za-z0-9_]*Grpc")?Optional.of(simple.substring(0,simple.length()-4)):Optional.empty();
    }
    private static Optional<String> stub(String type){var matcher=STUB.matcher(type);return matcher.matches()?Optional.of(matcher.group(1)):Optional.empty();}
    private static String scope(Node node){for(Node parent=node;parent!=null;parent=parent.getParentNode().orElse(null))if(parent instanceof CallableDeclaration<?>||parent instanceof TypeDeclaration<?>||parent instanceof LambdaExpr)return anchor(parent);return "file";}
    private static String anchor(Node node){return node.getRange().map(Object::toString).orElse("unpositioned");}
    private static SourceCoordinates.Range range(Node node,SourceCoordinates coordinates){var r=node.getRange().orElseThrow();int begin=coordinates.at(r.begin.line,r.begin.column).offset(),end=coordinates.at(r.end.line,r.end.column).offset()+1;return coordinates.range(begin,end,SourceCoordinates.Encoding.UTF16);}
    private static String slot(Node node,SourceCoordinates coordinates){var span=range(node,coordinates);return span.start()+":"+span.end();}
    private static Map<String,String> attributes(String service,String method,String trigger){return Map.of("service",service,"method",method,"methodStyle","LOWER_CAMEL","serviceQualification","SIMPLE_NAME","trigger",trigger);}
    private static void emit(Node node,String category,String kind,String name,String owner,Map<String,String> attributes,SourceCoordinates coordinates,boolean recovered,Sink sink,long[] counts)throws IOException {
        if(node.getRange().isEmpty())throw new IOException("JAVA_RPC_SOURCE_RANGE_UNAVAILABLE");var span=range(node,coordinates);String written=coordinates.text().substring(span.start(),Math.min(span.end(),span.start()+4096));
        sink.accept(new Fact(category,kind,name,written,span,owner,recovered,attributes));counts[0]++;counts[1]++;
    }
    private static void check(BooleanSupplier cancelled)throws IOException{if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("SYNTAX_CANCELLED");}
}
