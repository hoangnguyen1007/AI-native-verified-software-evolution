package evo.polyglot;

import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys;
import org.jetbrains.kotlin.cli.common.config.ContentRootsKt;
import org.jetbrains.kotlin.cli.common.messages.*;
import org.jetbrains.kotlin.cli.jvm.compiler.*;
import org.jetbrains.kotlin.cli.jvm.config.JvmContentRootsKt;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.config.*;
import org.jetbrains.kotlin.descriptors.*;
import org.jetbrains.kotlin.diagnostics.Diagnostic;
import org.jetbrains.kotlin.diagnostics.rendering.DefaultErrorMessages;
import org.jetbrains.kotlin.load.kotlin.JvmPackagePartSource;
import org.jetbrains.kotlin.psi.*;
import org.jetbrains.kotlin.resolve.*;
import org.jetbrains.kotlin.resolve.calls.callUtil.CallUtilKt;
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedCallableMemberDescriptor;
import org.jetbrains.kotlin.types.*;
import org.jetbrains.kotlin.types.error.ErrorUtils;
import java.io.File;
import java.nio.file.Path;
import java.util.*;

/** Exact Kotlin/JVM 1.9.25 compiler binding adapter; no application loading or handwritten solver. */
public final class KotlinEngine {
    private final NeutralJvm out;private BindingContext binding;
    private final Map<String,int[]> originalOffsets=new HashMap<>();
    private KotlinEngine(NeutralJvm out){this.out=out;}
    private NeutralJvm.Source source(PsiElement node){return node==null||node.getContainingFile()==null||node.getContainingFile().getVirtualFile()==null?null:out.source(Path.of(node.getContainingFile().getVirtualFile().getPath()));}
    private int original(PsiElement node,int offset){
        var source=source(node);if(source==null)return -1;
        var mapped=originalOffsets.computeIfAbsent(source.path(),ignored->{
            String raw=source.text(),nativeText=node.getContainingFile().getText();var normalized=new StringBuilder();var positions=new ArrayList<Integer>();positions.add(0);
            for(int i=0;i<raw.length();i++){char c=raw.charAt(i);if(c=='\r'){if(i+1<raw.length()&&raw.charAt(i+1)=='\n')i++;normalized.append('\n');}else normalized.append(c);positions.add(i+1);}
            int prefix=0;if(!normalized.toString().equals(nativeText)&&normalized.length()>0&&normalized.charAt(0)=='\ufeff'&&normalized.substring(1).equals(nativeText))prefix=1;
            else if(!normalized.toString().equals(nativeText))throw new IllegalArgumentException("KOTLIN_PSI_TEXT_MAPPING_NOT_ESTABLISHED");
            int[] values=new int[nativeText.length()+1];for(int i=0;i<values.length;i++)values[i]=positions.get(i+prefix);return values;
        });
        return offset<0||offset>=mapped.length?-1:mapped[offset];
    }
    private void site(String category,String role,PsiElement node){var source=source(node);if(source!=null)out.site(source,category,role,original(node,node.getTextRange().getStartOffset()),original(node,node.getTextRange().getEndOffset()),node);}
    private void census(PsiElement node){
        if(node instanceof org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement error){var source=source(node);if(source!=null)out.syntaxFailed(source,"KOTLIN_SYNTAX",error.getErrorDescription());}
        if(node instanceof KtPackageDirective directive&&!directive.getFqName().isRoot())site("A01","namespace",node);
        if(node instanceof KtNamedDeclaration)site("A02","declaration",node);
        boolean namespacePart=false;for(PsiElement parent=node.getParent();parent!=null;parent=parent.getParent())if(parent instanceof KtPackageDirective){namespacePart=true;break;}
        if(node instanceof KtNameReferenceExpression&&!namespacePart)site("A03","reference",node);
        if(node instanceof KtTypeReference)site("A04","type-use",node);
        if(node instanceof KtSuperTypeListEntry)site("A05","heritage",node);
        if(node instanceof KtCallExpression)site("A06","call",node);
        if(node instanceof KtCallableReferenceExpression)site("A06","callable-reference",node);
        if(node instanceof KtNameReferenceExpression&&!namespacePart)site("A07","value-access",node);
        if(node instanceof KtAnnotationEntry)site("A08","annotation",node);
        if(node instanceof KtImportDirective)site("A09","import-boundary",node);
        if(node instanceof KtIfExpression conditional&&conditional.getCondition()!=null)site("A10","guard",conditional.getCondition());
        if(node instanceof KtWhenExpression conditional&&conditional.getSubjectExpression()!=null)site("A10","guard",conditional.getSubjectExpression());
        for(PsiElement child:node.getChildren())census(child);
    }
    private String fq(DeclarationDescriptor descriptor){return DescriptorUtils.getFqNameSafe(descriptor).asString();}
    private String typeKey(KotlinType type,Set<KotlinType> seen){
        if(type==null)return "ABSENT";if(ErrorUtils.INSTANCE.containsErrorType(type))return "ERROR_TYPE";
        var descriptor=type.getConstructor().getDeclarationDescriptor();String name=descriptor==null?"OPAQUE_NATIVE_TYPE":descriptor instanceof TypeParameterDescriptor parameter?"parameter:"+fq(parameter.getContainingDeclaration())+":"+parameter.getIndex():fq(descriptor);
        if(!seen.add(type))return name;
        String arguments=type.getArguments().stream().map(p->p.isStarProjection()?"*":p.getProjectionKind().name()+":"+typeKey(p.getType(),seen)).collect(java.util.stream.Collectors.joining(",","<",">"));
        seen.remove(type);return name+arguments+(type.isMarkedNullable()?"?":"");
    }
    private String typeKey(KotlinType type){return typeKey(type,Collections.newSetFromMap(new IdentityHashMap<>()));}
    private Map<String,Object> symbol(DeclarationDescriptor value){
        if(value==null)return null;var descriptor=value.getOriginal();PsiElement node=DescriptorToSourceUtils.getSourceFromDescriptor(descriptor);var source=source(node);
        String owner=descriptor.getContainingDeclaration()==null?"<module>":fq(descriptor.getContainingDeclaration());if(owner.isEmpty())owner="<root-namespace>";String kind=descriptor instanceof ClassDescriptor?"CLASS":descriptor instanceof ConstructorDescriptor?"CONSTRUCTOR":descriptor instanceof FunctionDescriptor?"FUNCTION":descriptor instanceof PropertyDescriptor?"PROPERTY":descriptor instanceof ValueParameterDescriptor?"PARAMETER":descriptor instanceof TypeParameterDescriptor?"TYPE_PARAMETER":descriptor instanceof TypeAliasDescriptor?"TYPE_ALIAS":"NATIVE_DECLARATION";
        String signature=kind+":"+descriptor.getName().asString();
        if(descriptor instanceof CallableDescriptor callable){signature+="("+callable.getValueParameters().stream().map(p->typeKey(p.getType())).collect(java.util.stream.Collectors.joining(","))+")";if(callable.getExtensionReceiverParameter()!=null)signature="extension:"+typeKey(callable.getExtensionReceiverParameter().getType())+":"+signature;}
        String origin=source!=null?"PROJECT":org.jetbrains.kotlin.builtins.KotlinBuiltIns.isBuiltIn(descriptor)?"PLATFORM":"UNKNOWN";String artifact=null;
        if(descriptor instanceof DeserializedCallableMemberDescriptor member&&member.getContainerSource() instanceof JvmPackagePartSource part&&part.getKnownJvmBinaryClass()!=null){artifact=part.getKnownJvmBinaryClass().getLocation();}
        if(source==null&&artifact!=null){
            for(Object entry:com.evolution.analysis.graph.GraphJson.array(out.input.get("classpath"))){String path=com.evolution.analysis.graph.GraphJson.string(entry);if(artifact.startsWith(path+"!")||artifact.startsWith(path+"/")){origin="DEPENDENCY";break;}}
        }
        if(DescriptorUtils.isLocal(descriptor)&&node!=null)owner+="@"+(source==null?"unmapped":source.path())+":"+original(node,node.getTextRange().getStartOffset());
        Object span=source==null?null:out.span(source,original(node,node.getTextRange().getStartOffset()),original(node,node.getTextRange().getEndOffset()));
        var result=out.symbol(owner,signature,descriptor.getName().asString(),kind,origin,span);result.put("nativeFqName",fq(descriptor));result.put("binaryLocator",artifact);return result;
    }
    private String caller(PsiElement node){for(PsiElement parent=node.getParent();parent!=null;parent=parent.getParent())if(parent instanceof KtDeclaration declaration){var descriptor=binding.get(BindingContext.DECLARATION_TO_DESCRIPTOR,declaration);var symbol=symbol(descriptor);if(symbol!=null)return (String)symbol.get("id");}return null;}
    private Map<String,Object> attributed(NeutralJvm.Site site){
        PsiElement node=(PsiElement)site.nativeNode();DeclarationDescriptor descriptor=null;KotlinType type=null;
        if(node instanceof KtPackageDirective directive){var value=out.fact("RESOLVED",List.of(),null,null);value.put("namespace",directive.getFqName().asString());return value;}
        if(site.category().equals("A10")){var result=out.fact("RESOLVED",List.of(),node.getText(),caller(node));result.put("truth","UNKNOWN");return result;}
        if(node instanceof KtNamedDeclaration declaration)descriptor=binding.get(BindingContext.DECLARATION_TO_DESCRIPTOR,declaration);
        else if(node instanceof KtCallExpression expression){var resolved=CallUtilKt.getResolvedCall(expression,binding);if(resolved!=null&&resolved.getStatus().isSuccess())descriptor=resolved.getResultingDescriptor();}
        else if(node instanceof KtCallableReferenceExpression reference)descriptor=binding.get(BindingContext.REFERENCE_TARGET,reference.getCallableReference());
        else if(node instanceof KtNameReferenceExpression reference){
            descriptor=binding.get(BindingContext.REFERENCE_TARGET,reference);
            if(site.category().equals("A07")&&descriptor!=null&&!(descriptor instanceof VariableDescriptor))return out.fact("NOT_APPLICABLE",List.of(),null,caller(node));
        }else if(node instanceof KtTypeReference reference){type=binding.get(BindingContext.TYPE,reference);descriptor=type==null?null:type.getConstructor().getDeclarationDescriptor();}
        else if(node instanceof KtSuperTypeListEntry entry){var reference=entry.getTypeReference();type=reference==null?null:binding.get(BindingContext.TYPE,reference);descriptor=type==null?null:type.getConstructor().getDeclarationDescriptor();}
        else if(node instanceof KtAnnotationEntry entry){var annotation=binding.get(BindingContext.ANNOTATION,entry);if(annotation!=null){type=annotation.getType();descriptor=type.getConstructor().getDeclarationDescriptor();}}
        else if(node instanceof KtImportDirective directive&&directive.getImportedReference()!=null){var reference=directive.getImportedReference();if(reference instanceof KtDotQualifiedExpression dotted)reference=dotted.getSelectorExpression();if(reference instanceof KtReferenceExpression target)descriptor=binding.get(BindingContext.REFERENCE_TARGET,target);}
        if(type!=null&&ErrorUtils.INSTANCE.containsErrorType(type))return out.unresolved("NATIVE_ERROR_TYPE");
        var target=symbol(descriptor);
        if(target==null){var candidates=node instanceof KtExpression expression?binding.get(BindingContext.AMBIGUOUS_REFERENCE_TARGET,expression):null;
            if(candidates!=null&&!candidates.isEmpty())return out.fact("CANDIDATES",candidates.stream().map(this::symbol).filter(Objects::nonNull).toList(),typeKey(type),caller(node));
            return out.unresolved("KOTLIN_COMPILER_BINDING_UNAVAILABLE");}
        var result=out.fact(target.get("origin").equals("UNKNOWN")?"PARTIAL":"RESOLVED",List.of(target),type==null?descriptor instanceof CallableDescriptor callable?typeKey(callable.getReturnType()):null:typeKey(type),caller(node));
        if(site.category().equals("A07")){boolean write=node.getParent() instanceof KtBinaryExpression binary&&binary.getLeft()==node&&Set.of("=","+=","-=","*=","/=","%=").contains(binary.getOperationReference().getText());boolean update=node.getParent() instanceof KtUnaryExpression unary&&Set.of("++","--").contains(unary.getOperationReference().getText());result.put("access",write||update?"WRITE_OR_READ_WRITE":"READ");}
        return result;
    }
    private void run()throws Exception{
        var configuration=new CompilerConfiguration();configuration.put(CommonConfigurationKeys.MODULE_NAME,out.packageScope);configuration.put(JVMConfigurationKeys.JDK_HOME,new File(System.getProperty("java.home")));
        configuration.put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY,new MessageCollector(){boolean errors;public void clear(){errors=false;}public boolean hasErrors(){return errors;}public void report(CompilerMessageSeverity severity,String message,CompilerMessageSourceLocation location){if(severity.isError())errors=true;if(location!=null){var source=out.source(Path.of(location.getPath()));if(source!=null)out.diagnostic(source,severity.isError()?"ERROR":"WARNING","KOTLIN_COMPILER",message);}}});
        for(var source:out.sources.values())ContentRootsKt.addKotlinSourceRoot(configuration,source.physical().toString());
        var classpath=com.evolution.analysis.graph.GraphJson.array(out.input.get("classpath")).stream().map(com.evolution.analysis.graph.GraphJson::string).map(File::new).toList();JvmContentRootsKt.addJvmClasspathRoots(configuration,classpath);
        var disposable=Disposer.newDisposable();
        try{
            var environment=KotlinCoreEnvironment.createForProduction(disposable,configuration,EnvironmentConfigFiles.JVM_CONFIG_FILES);
            for(var file:environment.getSourceFiles())census(file);
            var analysis=TopDownAnalyzerFacadeForJVM.analyzeFilesWithJavaIntegration(environment.getProject(),environment.getSourceFiles(),new CliBindingTrace(),configuration,environment::createPackagePartProvider);
            binding=analysis.getBindingContext();
            for(Diagnostic diagnostic:binding.getDiagnostics().all()){var source=source(diagnostic.getPsiElement());if(source!=null)out.diagnostic(source,diagnostic.getSeverity().name(),diagnostic.getFactory().getName(),DefaultErrorMessages.render(diagnostic));}
            out.emit(this::attributed);
        }finally{Disposer.dispose(disposable);}
    }
    public static void main(String[] args)throws Exception{var out=new NeutralJvm(Path.of(args[0]));if(!out.language.equals("kotlin")||!out.dialect.equals("1.9.25"))throw new IllegalArgumentException("KOTLIN_PROFILE_NOT_QUALIFIED");new KotlinEngine(out).run();}
}
