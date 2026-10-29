package evo.polyglot;

import groovy.lang.GroovyClassLoader;
import org.codehaus.groovy.ast.*;
import org.codehaus.groovy.ast.expr.*;
import org.codehaus.groovy.ast.stmt.*;
import org.codehaus.groovy.control.*;
import org.codehaus.groovy.transform.stc.StaticTypesMarker;
import java.nio.file.Path;
import java.util.*;

/** Groovy 4.0.27 compiler AST/type metadata. Dynamic dispatch never becomes an exact call by name. */
public final class GroovyEngine {
    private record NativeSite(ASTNode node,MethodNode caller,ClassNode owner){}
    private final NeutralJvm out;private final Map<ClassNode,NeutralJvm.Source> project=new IdentityHashMap<>();
    private final Map<String,ASTNode> attributedCalls=new HashMap<>();
    private GroovyEngine(NeutralJvm out){this.out=out;}
    private int offset(NeutralJvm.Source source,int line,int column){
        if(line<1||column<1)return -1;int current=1,start=0;String text=source.text();
        for(int i=0;i<text.length()&&current<line;i++){char c=text.charAt(i);if(c=='\r'){if(i+1<text.length()&&text.charAt(i+1)=='\n')i++;current++;start=i+1;}else if(c=='\n'){current++;start=i+1;}}
        if(current!=line)return -1;int end=start;while(end<text.length()&&text.charAt(end)!='\r'&&text.charAt(end)!='\n')end++;
        // Groovy 4's ANTLR stream counts Unicode code points; neutral spans count UTF-16.
        return column-1>text.codePointCount(start,end)?-1:text.offsetByCodePoints(start,column-1);
    }
    private void site(NeutralJvm.Source source,String category,String role,ASTNode node,MethodNode caller,ClassNode owner){
        out.site(source,category,role,offset(source,node.getLineNumber(),node.getColumnNumber()),offset(source,node.getLastLineNumber(),node.getLastColumnNumber()),new NativeSite(node,caller,owner));
    }
    private NeutralJvm.Source source(ClassNode type){if(type==null)return null;type=type.redirect();var direct=project.get(type);if(direct!=null)return direct;return type.getModule()==null||type.getModule().getContext()==null?null:out.source(Path.of(type.getModule().getContext().getName()));}
    private Map<String,Object> typeSymbol(ClassNode type){
        if(type==null)return null;type=type.redirect();String origin=source(type)!=null?"PROJECT":ClassHelper.isPrimitiveType(type)?"PLATFORM":"UNKNOWN";
        // A resolved node already holds this Class object; getTypeClass does not initialize or load an application.
        if(origin.equals("UNKNOWN")&&type.isResolved()){
            Class<?> loaded=type.getTypeClass();if(loaded.getClassLoader()==null||loaded.getClassLoader()==ClassLoader.getPlatformClassLoader())origin="PLATFORM";
            else if(loaded.getProtectionDomain().getCodeSource()!=null){String location=loaded.getProtectionDomain().getCodeSource().getLocation().toExternalForm();
                for(Object path:com.evolution.analysis.graph.GraphJson.array(out.input.getOrDefault("platformArtifacts",List.of())))if(Path.of(path.toString()).toUri().toString().equals(location))origin="PLATFORM";
            }
        }
        return out.symbol(type.getPackageName()==null?"<root-namespace>":type.getPackageName(),"CLASS:"+type.getName(),type.getNameWithoutPackage(),"CLASS",origin,location(type,type));
    }
    private Object location(ASTNode node,ClassNode owner){var source=source(owner);if(source==null)return null;int start=offset(source,node.getLineNumber(),node.getColumnNumber()),end=offset(source,node.getLastLineNumber(),node.getLastColumnNumber());return start<0||end<=start?null:out.span(source,start,end);}
    private Map<String,Object> methodSymbol(MethodNode method){if(method==null)return null;var owner=method.getDeclaringClass();String signature="METHOD:"+method.getName()+"("+Arrays.stream(method.getParameters()).map(p->p.getType().getName()).collect(java.util.stream.Collectors.joining(","))+")";return out.symbol(owner.getName(),signature,method.getName(),method.isConstructor()?"CONSTRUCTOR":"METHOD",source(owner)!=null?"PROJECT":"UNKNOWN",location(method,owner));}
    private Map<String,Object> fieldSymbol(FieldNode field){return field==null?null:out.symbol(field.getOwner().getName(),"FIELD:"+field.getName(),field.getName(),"FIELD",source(field.getOwner())!=null?"PROJECT":"UNKNOWN",location(field,field.getOwner()));}
    private Map<String,Object> result(Map<String,Object> target,Object type,NativeSite site){
        if(target==null)return out.unresolved("GROOVY_COMPILER_TARGET_UNAVAILABLE");var caller=methodSymbol(site.caller());return out.fact(target.get("origin").equals("UNKNOWN")?"PARTIAL":"RESOLVED",List.of(target),type,caller==null?null:(String)caller.get("id"));
    }
    private Map<String,Object> attributed(NeutralJvm.Site value){
        var site=(NativeSite)value.nativeNode();var node=value.category().equals("A06")?attributedCalls.getOrDefault(value.source().path()+":"+value.start()+":"+value.end(),site.node()):site.node();
        if(value.category().equals("A10")){var result=out.fact("RESOLVED",List.of(),node.getText(),site.caller()==null?null:(String)methodSymbol(site.caller()).get("id"));result.put("truth","UNKNOWN");return result;}
        if(value.category().equals("A02")){
            if(node instanceof ClassNode type)return result(typeSymbol(type),null,site);
            if(node instanceof MethodNode method)return result(methodSymbol(method),method.getReturnType().getName(),site);
            if(node instanceof FieldNode field)return result(fieldSymbol(field),field.getType().getName(),site);
            if(node instanceof PropertyNode property)return result(out.symbol(property.getDeclaringClass().getName(),"PROPERTY:"+property.getName(),property.getName(),"PROPERTY","PROJECT",location(property,property.getDeclaringClass())),property.getType().getName(),site);
        }
        if(value.category().equals("A04")){ClassNode type=node instanceof FieldNode field?field.getType():node instanceof PropertyNode property?property.getType():node instanceof MethodNode method?method.getReturnType():node instanceof Parameter parameter?parameter.getType():null;return type==null?out.unsupported("GROOVY_NATIVE_TYPE_DETAIL_UNAVAILABLE"):result(typeSymbol(type),type.getName(),site);}
        if(value.category().equals("A05")&&node instanceof ClassNode type)return result(typeSymbol(type.getSuperClass()),type.getSuperClass().getName(),site);
        if(node instanceof MethodCallExpression call){MethodNode target=call.getMethodTarget();if(target==null)target=call.getNodeMetaData(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET);return result(methodSymbol(target),call.getType().getName(),site);}
        if(node instanceof StaticMethodCallExpression call){MethodNode target=call.getNodeMetaData(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET);return result(methodSymbol(target),call.getType().getName(),site);}
        if(node instanceof ConstructorCallExpression call){MethodNode target=call.getNodeMetaData(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET);return result(methodSymbol(target),call.getType().getName(),site);}
        if(node instanceof FieldExpression expression)return result(fieldSymbol(expression.getField()),expression.getType().getName(),site);
        if(node instanceof VariableExpression variable){
            var target=variable.getAccessedVariable();if(target instanceof FieldNode field)return result(fieldSymbol(field),variable.getType().getName(),site);
            if(target instanceof DynamicVariable)return out.unresolved("GROOVY_DYNAMIC_VALUE");
            if(target instanceof ASTNode declaration){var symbol=out.symbol(site.owner().getName(),"VALUE:"+target.getName()+"@"+declaration.getLineNumber()+":"+declaration.getColumnNumber(),target.getName(),target instanceof Parameter?"PARAMETER":"LOCAL","PROJECT",location(declaration,site.owner()));return result(symbol,target.getType().getName(),site);}
            return out.unresolved("GROOVY_NATIVE_VALUE_BINDING_UNAVAILABLE");
        }
        if(node instanceof AnnotationNode annotation)return result(typeSymbol(annotation.getClassNode()),annotation.getClassNode().getName(),site);
        if(node instanceof ImportNode imported)return result(typeSymbol(imported.getType()),imported.getClassName(),site);
        return out.unsupported("GROOVY_NATIVE_SUPPLEMENT_REQUIRED");
    }
    private void run()throws Exception{
        var configuration=new CompilerConfiguration();configuration.setDisabledGlobalASTTransformations(Set.of("groovy.grape.GrabAnnotationTransformation"));
        try(var loader=new GroovyClassLoader(GroovyEngine.class.getClassLoader(),configuration,false)){
            var unit=new CompilationUnit(configuration,null,loader);for(var source:out.sources.values())unit.addSource(source.physical().toFile());
            try{unit.compile(Phases.CONVERSION);}catch(CompilationFailedException invalid){for(var source:out.sources.values())out.syntaxFailed(source,"GROOVY_SYNTAX_ERROR",invalid.getClass().getSimpleName());out.emit(site->out.unsupported("GROOVY_PARSE_SCOPE_UNAVAILABLE"));return;}
            for(var module:unit.getAST().getModules()){
                var source=out.source(Path.of(module.getContext().getName()));if(source==null)throw new IllegalArgumentException("GROOVY_SOURCE_MEMBERSHIP_CHANGED");
                for(var imported:module.getImports()){site(source,"A03","import",imported,null,null);site(source,"A09","import-boundary",imported,null,null);}
                for(var imported:module.getStarImports()){site(source,"A03","star-import",imported,null,null);site(source,"A09","import-boundary",imported,null,null);}
                for(var type:module.getClasses())project.put(type,source);
                var visitor=new ClassCodeVisitorSupport(){
                    ClassNode owner;MethodNode caller;
                    protected SourceUnit getSourceUnit(){return module.getContext();}
                    public void visitClass(ClassNode node){var previous=owner;owner=node;if(node.getLineNumber()>0){site(source,"A02","class",node,null,node);if(node.getUnresolvedSuperClass()!=null&&node.getUnresolvedSuperClass().getLineNumber()>0)site(source,"A05","extends",node,null,node);}super.visitClass(node);owner=previous;}
                    protected void visitConstructorOrMethod(MethodNode node,boolean constructor){var previous=caller;caller=node;if(node.getLineNumber()>0){site(source,"A02",constructor?"constructor":"method",node,node,owner);site(source,"A04","declared-return",node,node,owner);for(var parameter:node.getParameters())if(parameter.getLineNumber()>0)site(source,"A04","declared-parameter",parameter,node,owner);}super.visitConstructorOrMethod(node,constructor);caller=previous;}
                    public void visitField(FieldNode node){if(node.getLineNumber()>0){site(source,"A02","field",node,caller,owner);site(source,"A04","declared-field-type",node,caller,owner);}super.visitField(node);}
                    public void visitProperty(PropertyNode node){if(node.getLineNumber()>0)site(source,"A02","property",node,caller,owner);super.visitProperty(node);}
                    public void visitMethodCallExpression(MethodCallExpression node){site(source,"A06","call",node,caller,owner);super.visitMethodCallExpression(node);}
                    public void visitStaticMethodCallExpression(StaticMethodCallExpression node){site(source,"A06","static-call",node,caller,owner);super.visitStaticMethodCallExpression(node);}
                    public void visitConstructorCallExpression(ConstructorCallExpression node){site(source,"A06","construct",node,caller,owner);super.visitConstructorCallExpression(node);}
                    public void visitVariableExpression(VariableExpression node){if(!node.isThisExpression()&&!node.isSuperExpression()){site(source,"A03","reference",node,caller,owner);site(source,"A07","value-access",node,caller,owner);}super.visitVariableExpression(node);}
                    public void visitFieldExpression(FieldExpression node){site(source,"A07","field-access",node,caller,owner);super.visitFieldExpression(node);}
                    public void visitIfElse(IfStatement node){site(source,"A10","guard",node.getBooleanExpression(),caller,owner);super.visitIfElse(node);}
                    public void visitAnnotations(AnnotatedNode node){for(var annotation:node.getAnnotations())if(annotation.getLineNumber()>0)site(source,"A08","annotation",annotation,caller,owner);super.visitAnnotations(node);}
                };
                for(var type:module.getClasses())visitor.visitClass(type);
            }
            try{unit.compile(Phases.INSTRUCTION_SELECTION);}catch(CompilationFailedException invalid){for(var source:out.sources.values())out.diagnostic(source,"ERROR","GROOVY_SEMANTIC_ERROR",invalid.getClass().getSimpleName());}
            // Static compilation may replace call nodes. Match its final native binding back to
            // the independently captured written occurrence, rather than reading a detached node.
            for(var module:unit.getAST().getModules()){
                var source=out.source(Path.of(module.getContext().getName()));
                var bindings=new ClassCodeVisitorSupport(){
                    protected SourceUnit getSourceUnit(){return module.getContext();}
                    private void record(ASTNode node){int start=offset(source,node.getLineNumber(),node.getColumnNumber()),end=offset(source,node.getLastLineNumber(),node.getLastColumnNumber());if(start>=0&&end>start)attributedCalls.put(source.path()+":"+start+":"+end,node);}
                    public void visitMethodCallExpression(MethodCallExpression node){record(node);super.visitMethodCallExpression(node);}
                    public void visitStaticMethodCallExpression(StaticMethodCallExpression node){record(node);super.visitStaticMethodCallExpression(node);}
                    public void visitConstructorCallExpression(ConstructorCallExpression node){record(node);super.visitConstructorCallExpression(node);}
                };
                for(var type:module.getClasses())bindings.visitClass(type);
            }
            out.emit(this::attributed);
        }
    }
    public static void main(String[] args)throws Exception{var out=new NeutralJvm(Path.of(args[0]));if(!out.language.equals("groovy")||!out.dialect.equals("4.0.27"))throw new IllegalArgumentException("GROOVY_PROFILE_NOT_QUALIFIED");new GroovyEngine(out).run();}
}
