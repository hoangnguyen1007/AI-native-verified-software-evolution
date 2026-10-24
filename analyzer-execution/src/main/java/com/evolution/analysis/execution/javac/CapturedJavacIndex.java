package com.evolution.analysis.execution.javac;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.*;
import javax.tools.*;

/** Guest-only native binding capture. Target compiler requests never execute on the host. */
public final class CapturedJavacIndex {
    private CapturedJavacIndex(){}
    private static final List<String> CATEGORIES=List.of("declares","extends","implements","permits","type-uses","calls","constructor-calls","reads-field","writes-field","method-references","has-parameter","parameter-type","returns","field-type","throws","annotated-with","type-parameter-bound","type-argument");
    private static final Set<String> OBSERVED=Set.copyOf(CATEGORIES);
    static final class OriginalEdges<T> {
        private final Set<T> nodes=Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<T> roots=Collections.newSetFromMap(new IdentityHashMap<>());
        private final Map<T,Set<T>> parents=new IdentityHashMap<>();
        void add(T parent,T child){nodes.add(child);if(parent==null)roots.add(child);else parents.computeIfAbsent(child,unused->Collections.newSetFromMap(new IdentityHashMap<>())).add(parent);}
        boolean contains(T parent,T child){return parent==null?roots.contains(child):parents.getOrDefault(child,Set.of()).contains(parent);}
        int size(){return nodes.size();}
    }
    private record Source(String uri,String text,String digest,long size,int[] lines){}
    private static int[] lines(String text){
        var starts=new ArrayList<Integer>();starts.add(0);
        for(int i=0;i<text.length();i++)if(text.charAt(i)=='\r'){if(i+1<text.length()&&text.charAt(i+1)=='\n')i++;starts.add(i+1);}else if(text.charAt(i)=='\n')starts.add(i+1);
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }
    static Map<String,Object> span(String source,long start,long end){
        return span(new Source("",source,"",0,lines(source)),start,end);
    }
    private static Map<String,Object> span(Source source,long start,long end){
        if(start<0||end<start||end>source.text().length()||betweenCrLf(source.text(),start)||betweenCrLf(source.text(),end))return null;
        int a=Arrays.binarySearch(source.lines(),(int)start),b=Arrays.binarySearch(source.lines(),(int)end);
        a=a<0?-a-2:a;b=b<0?-b-2:b;
        return Map.of("startOffset",start,"endOffset",end,"startLine",(long)a+1,"startColumn",start-source.lines()[a]+1,"endLine",(long)b+1,"endColumn",end-source.lines()[b]+1);
    }
    private static boolean betweenCrLf(String source,long offset){return offset>0&&offset<source.length()&&source.charAt((int)offset-1)=='\r'&&source.charAt((int)offset)=='\n';}
    /** Map the compiler's decoded terminal name back to the original UTF-16 source. */
    static Map<String,Object> terminalSpan(String source,long start,long end,String identifier){
        if(span(source,start,end)==null||identifier.isEmpty())return null;int cursor=(int)end;
        for(int i=identifier.length()-1;i>=0;i--){
            int escape=cursor-4;boolean encoded=false;
            if(escape>start){int value=0;boolean hex=true;for(int j=escape;j<cursor;j++){int digit=Character.digit(source.charAt(j),16);if(digit<0){hex=false;break;}value=value*16+digit;}
                int u=escape;while(u>start&&source.charAt(u-1)=='u')u--;
                if(hex&&u<escape&&u>start&&source.charAt(u-1)=='\\'&&value==identifier.charAt(i)){cursor=u-1;encoded=true;}}
            if(!encoded){if(cursor<=start||source.charAt(cursor-1)!=identifier.charAt(i))return null;cursor--;}
        }
        if(cursor>start&&Character.isJavaIdentifierPart(source.codePointBefore(cursor)))return null;
        return span(source,cursor,end);
    }
    /** Supported ordinary field declarators. Unmapped post-name dimensions/escaped comments stay absent. */
    static Map<String,Object> fieldSpan(String source,long afterType,long nativeEnd,long initializerStart,long initializerEnd,String identifier){
        if(afterType<0||nativeEnd<afterType||nativeEnd>source.length()||identifier.isEmpty())return null;
        boolean initialized=initializerStart>=0||initializerEnd>=0;
        if(initialized&&(initializerStart<afterType||initializerEnd<initializerStart||initializerEnd>nativeEnd))return null;
        int limit=initialized?(int)initializerStart:(int)nativeEnd,start=skipGaps(source,(int)afterType,limit),cursor=start;if(start<0)return null;
        for(int i=0;i<identifier.length();i++){
            if(cursor>=limit)return null;char actual=source.charAt(cursor++);
            if(actual=='\\'){
                int u=cursor;while(cursor<limit&&source.charAt(cursor)=='u')cursor++;if(cursor==u||cursor+4>limit)return null;
                int value=0;for(int end=cursor+4;cursor<end;cursor++){char c=source.charAt(cursor);int digit=c>127?-1:Character.digit(c,16);if(digit<0)return null;value=value*16+digit;}actual=(char)value;
            }
            if(actual!=identifier.charAt(i))return null;
        }
        int end=cursor;cursor=skipGaps(source,cursor,limit);if(cursor<0)return null;
        if(initialized){if(cursor>=limit||source.charAt(cursor++)!='='||skipGaps(source,cursor,limit)!=limit)return null;end=(int)initializerEnd;}
        else if(cursor<limit&&source.charAt(cursor)!=';'&&source.charAt(cursor)!=',')return null;
        return span(source,start,end);
    }
    private static int skipGaps(String source,int cursor,int end){
        while(cursor<end){char c=source.charAt(cursor);if(Character.isWhitespace(c)){cursor++;continue;}
            if(c=='/'&&cursor+1<end&&source.charAt(cursor+1)=='*'){int close=source.indexOf("*/",cursor+2);if(close<0||close+2>end)return -1;
                if(source.substring(cursor,close+2).contains("\\"))return -1;cursor=close+2;continue;}
            if(c=='/'&&cursor+1<end&&source.charAt(cursor+1)=='/'){int next=cursor+2;while(next<end&&source.charAt(next)!='\r'&&source.charAt(next)!='\n'){if(source.charAt(next)=='\\')return -1;next++;}cursor=next;continue;}
            break;
        }return cursor;
    }
    private static String digest(byte[] bytes)throws Exception{return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static void noAliases(Path path)throws IOException{for(Path p=path;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("INDEX_INPUT_ALIAS");}
    private static final class Capacity extends RuntimeException{Capacity(){super("INDEX_WORKER_CAPACITY_REQUIRED");}}
    private record TypeRole(TreePath path,String category,TreePath owner,Tree whole,boolean conditional){}
    /** Freeze paths and written roles before getElements/analyze may change the AST. */
    private static final class Syntax extends TreePathScanner<Void,Void> {
        final List<TreePath> paths=new ArrayList<>();final List<TypeRole> roles=new ArrayList<>();final Set<Tree> declarations=Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<Tree> annotations=Collections.newSetFromMap(new IdentityHashMap<>());final int maximum;final OriginalEdges<Tree> original=new OriginalEdges<>();
        final Map<Tree,long[]> positions=new IdentityHashMap<>();final SourcePositions sourcePositions;
        final Set<Tree> writtenArguments=Collections.newSetFromMap(new IdentityHashMap<>());
        Syntax(int maximum,SourcePositions sourcePositions){this.maximum=maximum;this.sourcePositions=sourcePositions;}
        @Override public Void scan(Tree tree,Void unused){
            if(tree==null)return null;TreePath parent=getCurrentPath();var path=parent==null?new TreePath((CompilationUnitTree)tree):new TreePath(parent,tree);
            Tree parentTree=parent==null?null:parent.getLeaf();if(original.contains(parentTree,tree))return null;
            original.add(parentTree,tree);if(paths.size()>=maximum)throw new Capacity();paths.add(path);
            positions.putIfAbsent(tree,new long[]{sourcePositions.getStartPosition(path.getCompilationUnit(),tree),sourcePositions.getEndPosition(path.getCompilationUnit(),tree)});return super.scan(tree,unused);
        }
        void type(TreePath parent,Tree type,String role,TreePath owner){if(type!=null)type(new TreePath(parent,type),role,owner,type,false,false);}
        void role(TypeRole role){
            // The generic syntax walk also sees arguments already recorded by a declaration role.
            // Keep their native owner; do not emit a second occurrence for the same written argument.
            if(role.category().equals("type-argument")){if(role.owner()==null&&writtenArguments.contains(role.path().getLeaf()))return;writtenArguments.add(role.path().getLeaf());}
            roles.add(role);
        }
        void type(TreePath path,String role,TreePath owner,Tree whole,boolean argument,boolean conditional){
            Tree tree=path.getLeaf();
            if(tree instanceof ParameterizedTypeTree generic){
                type(new TreePath(path,generic.getType()),role,owner,whole,argument,conditional);
                String nested=Set.of("extends","implements","permits","throws").contains(role)?"type-uses":role;
                for(Tree child:generic.getTypeArguments())type(new TreePath(path,child),nested,owner,child,true,false);return;
            }
            if(tree instanceof AnnotatedTypeTree annotated){type(new TreePath(path,annotated.getUnderlyingType()),role,owner,whole,argument,conditional);return;}
            if(tree instanceof ArrayTypeTree array){type(new TreePath(path,array.getType()),role,owner,array.getType(),argument,conditional);return;}
            if(tree instanceof WildcardTree wildcard){if(wildcard.getBound()!=null)type(new TreePath(path,wildcard.getBound()),role,owner,wildcard.getBound(),argument,conditional);return;}
            if(tree instanceof UnionTypeTree union){for(Tree child:union.getTypeAlternatives())type(new TreePath(path,child),role,owner,child,argument,conditional);return;}
            if(tree instanceof IntersectionTypeTree intersection){for(Tree child:intersection.getBounds())type(new TreePath(path,child),role,owner,child,argument,conditional);return;}
            if(tree instanceof PrimitiveTypeTree)return;
            if(!role.equals("type-uses"))role(new TypeRole(path,role,owner,whole,conditional));
            if(argument&&!role.equals("type-argument"))role(new TypeRole(path,"type-argument",owner,tree,conditional));
            if(tree instanceof MemberSelectTree select){String nested=Set.of("extends","implements","permits","throws").contains(role)?"type-uses":role;
                type(new TreePath(path,select.getExpression()),nested,owner,select.getExpression(),argument,true);}
        }
        @Override public Void visitClass(ClassTree tree,Void unused){
            declarations.add(tree);var path=getCurrentPath();type(path,tree.getExtendsClause(),"extends",path);
            for(Tree child:tree.getImplementsClause())type(path,child,tree.getKind()==Tree.Kind.INTERFACE?"extends":"implements",path);
            for(Tree child:tree.getPermitsClause())type(path,child,"permits",path);return super.visitClass(tree,unused);
        }
        @Override public Void visitMethod(MethodTree tree,Void unused){
            declarations.add(tree);var path=getCurrentPath();type(path,tree.getReturnType(),"returns",path);for(Tree child:tree.getThrows())type(path,child,"throws",path);
            for(var parameter:tree.getParameters())roles.add(new TypeRole(new TreePath(path,parameter),"has-parameter",path,parameter,false));return super.visitMethod(tree,unused);
        }
        @Override public Void visitVariable(VariableTree tree,Void unused){
            var path=getCurrentPath();Tree parent=path.getParentPath().getLeaf();
            if(parent instanceof ClassTree){declarations.add(tree);type(path,tree.getType(),"field-type",path);}
            else if(parent instanceof MethodTree method&&method.getParameters().contains(tree)){declarations.add(tree);type(path,tree.getType(),"parameter-type",path);}
            else if(parent instanceof LambdaExpressionTree lambda&&lambda.getParameters().contains(tree)||parent instanceof CatchTree)declarations.add(tree);
            return super.visitVariable(tree,unused);
        }
        @Override public Void visitTypeParameter(TypeParameterTree tree,Void unused){declarations.add(tree);var path=getCurrentPath();for(Tree child:tree.getBounds())type(path,child,"type-parameter-bound",path);return super.visitTypeParameter(tree,unused);}
        @Override public Void visitParameterizedType(ParameterizedTypeTree tree,Void unused){var path=getCurrentPath();for(Tree child:tree.getTypeArguments())type(path,child,"type-argument",null);return super.visitParameterizedType(tree,unused);}
        @Override public Void visitLambdaExpression(LambdaExpressionTree tree,Void unused){declarations.add(tree);return super.visitLambdaExpression(tree,unused);}
        @Override public Void visitBlock(BlockTree tree,Void unused){if(getCurrentPath().getParentPath().getLeaf() instanceof ClassTree)declarations.add(tree);return super.visitBlock(tree,unused);}
        @Override public Void visitAnnotation(AnnotationTree tree,Void unused){
            boolean nested=false;Tree child=tree;for(TreePath p=getCurrentPath().getParentPath();p!=null;child=p.getLeaf(),p=p.getParentPath()){
                if(p.getLeaf() instanceof AnnotationTree||p.getLeaf() instanceof MethodTree method&&method.getDefaultValue()==child){nested=true;break;}}
            if(!nested)annotations.add(tree);return super.visitAnnotation(tree,unused);
        }
        @Override public Void visitMethodInvocation(MethodInvocationTree tree,Void unused){var path=getCurrentPath();for(Tree child:tree.getTypeArguments())type(path,child,"type-argument",null);return super.visitMethodInvocation(tree,unused);}
        @Override public Void visitNewClass(NewClassTree tree,Void unused){var path=getCurrentPath();for(Tree child:tree.getTypeArguments())type(path,child,"type-argument",null);return super.visitNewClass(tree,unused);}
        @Override public Void visitMemberReference(MemberReferenceTree tree,Void unused){var path=getCurrentPath();if(tree.getTypeArguments()!=null)for(Tree child:tree.getTypeArguments())type(path,child,"type-argument",null);return super.visitMemberReference(tree,unused);}
    }
    private static String descriptor(TypeMirror value,Elements elements,Types types){
        if(value==null||value.getKind()==TypeKind.ERROR)return null;
        return switch(value.getKind()){
            case BOOLEAN->"Z";case BYTE->"B";case SHORT->"S";case INT->"I";case LONG->"J";case CHAR->"C";case FLOAT->"F";case DOUBLE->"D";case VOID->"V";
            case ARRAY->{String component=descriptor(((ArrayType)value).getComponentType(),elements,types);yield component==null?null:"["+component;}
            case DECLARED->"L"+elements.getBinaryName((TypeElement)((DeclaredType)value).asElement()).toString().replace('.','/')+";";
            case TYPEVAR,INTERSECTION->descriptor(types.erasure(value),elements,types);
            default->null;
        };
    }
    private static final class Capture {
        final JavacTask task;final Trees trees;final Elements elements;final Types types;final int maximum;
        final Map<String,Source> sources;final Syntax syntax;
        final List<Object> observations=new ArrayList<>(),declarations=new ArrayList<>();final Map<String,Object> artifacts=new TreeMap<>();final Set<String> erroneous;
        int visited;final Set<String> walked=new TreeSet<>();
        Capture(JavacTask task,Map<String,Source> sources,int maximum,Set<String> erroneous,Syntax syntax){this.task=task;this.sources=sources;this.maximum=maximum;this.erroneous=erroneous;this.syntax=syntax;trees=Trees.instance(task);elements=task.getElements();types=task.getTypes();}
        Map<String,Object> element(Element element){
            if(element==null)return null;
            var out=new TreeMap<String,Object>();out.put("kind",element.getKind().name());out.put("name",element.getSimpleName().toString());
            Element owner=element;while(owner!=null&&!(owner instanceof TypeElement))owner=owner.getEnclosingElement();
            out.put("owner",owner instanceof TypeElement type?elements.getBinaryName(type).toString():null);
            out.put("module",elements.getModuleOf(element)==null?null:elements.getModuleOf(element).getQualifiedName().toString());
            out.put("modelOrigin",elements.getOrigin(element).name());
            if(element instanceof PackageElement pkg){out.put("owner",pkg.getQualifiedName().toString());out.put("descriptor",null);out.put("artifact",null);return out;}
            try{
                String signature;
                if(element instanceof ExecutableElement method){
                    var parts=new ArrayList<String>();for(var parameter:method.getParameters()){String part=descriptor(parameter.asType(),elements,types);if(part==null)throw new IllegalArgumentException("UNKNOWN_PARAMETER");parts.add(part);}
                    String result=method.getKind()==ElementKind.CONSTRUCTOR?"V":descriptor(method.getReturnType(),elements,types);
                    signature=result==null?null:"("+String.join("",parts)+")"+result;
                }else signature=descriptor(element.asType(),elements,types);
                out.put("descriptor",signature);
            }catch(IllegalArgumentException unavailable){out.put("descriptor",null);}
            String artifact=null;var path=trees.getPath(element);
            try{
                // A real source path is authoritative, including type parameters
                // which javac's getFileObjectOf may not implement correctly.
                JavaFileObject file=path==null?null:path.getCompilationUnit().getSourceFile();
                for(Element current=element;current!=null&&file==null;current=current.getEnclosingElement())file=elements.getFileObjectOf(current);
                if(file!=null){
                    artifact=file.toUri().toString();
                    if(!artifacts.containsKey(artifact)){
                        var source=sources.get(artifact);var data=new TreeMap<String,Object>();data.put("uri",artifact);data.put("kind",file.getKind().name());
                        if(source!=null){data.put("digest",source.digest());data.put("size",source.size());data.put("state","CAPTURED_SOURCE");}
                        else{try(var stream=file.openInputStream()){
                            byte[] bytes=stream.readNBytes(2_000_001);if(bytes.length>2_000_000)throw new Capacity();data.put("digest",digest(bytes));data.put("size",bytes.length);data.put("state","OBSERVED_COMPILER_INPUT");
                        }}
                        artifacts.put(artifact,data);
                    }
                }
            }catch(Capacity limit){throw limit;}catch(Exception unavailable){out.put("artifactFailure",unavailable.getClass().getSimpleName());artifact=null;}
            out.put("artifact",artifact);
            if(path!=null&&sources.containsKey(path.getCompilationUnit().getSourceFile().toUri().toString())){
                var source=sources.get(path.getCompilationUnit().getSourceFile().toUri().toString());
                out.put("declarationSource",source.uri());out.put("declarationSpan",position(path,path.getLeaf()));
            }
            return out;
        }
        Element caller(TreePath path){
            for(TreePath current=path.getParentPath();current!=null;current=current.getParentPath())
                if(current.getLeaf() instanceof MethodTree||current.getLeaf() instanceof VariableTree||current.getLeaf() instanceof ClassTree){
                    Element element=trees.getElement(current);if(element!=null&&(!(current.getLeaf() instanceof VariableTree)
                            ||Set.of(ElementKind.FIELD,ElementKind.ENUM_CONSTANT).contains(element.getKind())))return element;
                }
            var unit=path.getCompilationUnit();return elements.getPackageElement(unit.getPackageName()==null?"":unit.getPackageName().toString());
        }
        void observe(String category,TreePath path,Element target){
            observe(category,path,target,caller(path),position(path,path.getLeaf()));
        }
        void observe(String category,TreePath path,Element target,Element owner,Map<String,Object> mappedSpan){
            var unit=path.getCompilationUnit();var source=sources.get(unit.getSourceFile().toUri().toString());if(source==null)return;
            var row=new TreeMap<String,Object>();row.put("source",source.uri());row.put("sourceContent",source.digest());row.put("category","java."+category);row.put("treeKind",path.getLeaf().getKind().name());row.put("nativeSpan",position(path,path.getLeaf()));row.put("span",mappedSpan);
            row.put("caller",element(owner));row.put("target",element(target));
            var enclosing=new ArrayList<Object>();for(TreePath p=path.getParentPath();p!=null;p=p.getParentPath())if(p.getLeaf() instanceof LambdaExpressionTree||p.getLeaf() instanceof BlockTree&&p.getParentPath()!=null&&p.getParentPath().getLeaf() instanceof ClassTree){
                long[] offsets=syntax.positions.get(p.getLeaf());enclosing.add(Map.of("kind",p.getLeaf().getKind().name(),"startOffset",offsets==null?-1:offsets[0],"endOffset",offsets==null?-1:offsets[1]));}
            row.put("enclosingScopes",enclosing);
            var type=trees.getTypeMirror(path);row.put("binding",erroneous.contains(source.uri())||type!=null&&type.getKind()==TypeKind.ERROR?"ERROR":target==null?category.equals("declares")&&(path.getLeaf() instanceof LambdaExpressionTree||path.getLeaf() instanceof BlockTree)?"SYNTAX_ONLY":"UNRESOLVED":"RESOLVED");
            row.put("type",type==null?null:type.toString());observations.add(row);
        }
        Map<String,Object> position(TreePath path,Tree tree){var unit=path.getCompilationUnit();var source=sources.get(unit.getSourceFile().toUri().toString());long[] offsets=syntax.positions.get(tree);return offsets==null?null:span(source,offsets[0],offsets[1]);}
        Map<String,Object> typePosition(TreePath path,Element target){
            if(!(path.getLeaf() instanceof MemberSelectTree select))return position(path,path.getLeaf());
            if(path.getParentPath()!=null&&path.getParentPath().getLeaf() instanceof AnnotationTree annotation&&annotation.getAnnotationType()==path.getLeaf())return position(path,path.getLeaf());
            var original=position(path,path.getLeaf());if(original==null)return null;var source=sources.get(path.getCompilationUnit().getSourceFile().toUri().toString());
            return terminalSpan(source.text(),((Number)original.get("startOffset")).longValue(),((Number)original.get("endOffset")).longValue(),select.getIdentifier().toString());
        }
        Map<String,Object> declarationPosition(TreePath path){
            var parent=path.getParentPath();Tree tree=path.getLeaf();
            if(tree instanceof ClassTree type&&type.getSimpleName().length()==0&&parent!=null&&parent.getLeaf() instanceof NewClassTree)return position(parent,parent.getLeaf());
            if(tree instanceof VariableTree variable&&parent!=null&&parent.getLeaf() instanceof ClassTree type&&type.getKind()!=Tree.Kind.RECORD&&variable.getType()!=null){
                var source=sources.get(path.getCompilationUnit().getSourceFile().toUri().toString());var typeSpan=position(path,variable.getType());var original=position(path,tree);
                if(typeSpan==null||original==null)return null;var init=variable.getInitializer()==null?null:position(path,variable.getInitializer());
                if(variable.getInitializer()!=null&&init==null)return null;
                return fieldSpan(source.text(),((Number)typeSpan.get("endOffset")).longValue(),((Number)original.get("endOffset")).longValue(),init==null?-1:((Number)init.get("startOffset")).longValue(),init==null?-1:((Number)init.get("endOffset")).longValue(),variable.getName().toString());
            }return position(path,tree);
        }
        void walk(Syntax syntax){
            for(var path:syntax.paths){
                if(++visited>maximum)throw new Capacity();Tree tree=path.getLeaf();var unit=path.getCompilationUnit();var source=sources.get(unit.getSourceFile().toUri().toString());if(source==null)continue;
                var target=trees.getElement(path);
                if(tree instanceof ClassTree||tree instanceof MethodTree||tree instanceof VariableTree||tree instanceof TypeParameterTree){
                    var row=new TreeMap<String,Object>();row.put("source",source.uri());row.put("element",element(target));row.put("treeKind",tree.getKind().name());row.put("span",position(path,tree));declarations.add(row);
                }
                if(syntax.declarations.contains(tree))observe("declares",path,target,caller(path),declarationPosition(path));
                if(tree instanceof MethodInvocationTree){
                    boolean constructor=target!=null&&target.getKind()==ElementKind.CONSTRUCTOR;
                    var mapped=constructor&&path.getParentPath()!=null&&path.getParentPath().getLeaf() instanceof ExpressionStatementTree?position(path,path.getParentPath().getLeaf()):position(path,tree);
                    observe(constructor?"constructor-calls":"calls",path,target,caller(path),mapped);
                }else if(tree instanceof NewClassTree){
                    var parent=path.getParentPath();var owner=parent==null?null:trees.getElement(parent);
                    if(owner==null||owner.getKind()!=ElementKind.ENUM_CONSTANT)observe("constructor-calls",path,target);
                }else if(tree instanceof MemberReferenceTree)observe("method-references",path,target);
                if(tree instanceof IdentifierTree||tree instanceof MemberSelectTree){
                    if(target instanceof TypeElement||target instanceof TypeParameterElement)observe("type-uses",path,target,caller(path),typePosition(path,target));
                    if(target instanceof VariableElement variable&&Set.of(ElementKind.FIELD,ElementKind.ENUM_CONSTANT).contains(variable.getKind())&&!Set.of("this","super","class").contains(variable.getSimpleName().toString())){
                        Tree current=tree;var parent=path.getParentPath();while(parent!=null&&parent.getLeaf() instanceof ParenthesizedTree){current=parent.getLeaf();parent=parent.getParentPath();}
                        Tree up=parent==null?null:parent.getLeaf();boolean write=up instanceof AssignmentTree assignment&&assignment.getVariable()==current;
                        boolean both=up instanceof CompoundAssignmentTree assignment&&assignment.getVariable()==current||up instanceof UnaryTree unary&&Set.of(Tree.Kind.PREFIX_INCREMENT,Tree.Kind.PREFIX_DECREMENT,Tree.Kind.POSTFIX_INCREMENT,Tree.Kind.POSTFIX_DECREMENT).contains(unary.getKind());
                        if(!write)observe("reads-field",path,variable);if(write||both)observe("writes-field",path,variable);
                    }
                }
                if(syntax.annotations.contains(tree))observe("annotated-with",path,trees.getElement(new TreePath(path,((AnnotationTree)tree).getAnnotationType())));
            }
            for(var role:syntax.roles){var target=trees.getElement(role.path());if(role.conditional()&&!(target instanceof TypeElement||target instanceof TypeParameterElement))continue;
                Element owner=role.owner()==null?caller(role.path()):trees.getElement(role.owner());
                var mapped=Set.of("extends","implements","permits","throws","has-parameter").contains(role.category())?position(role.path(),role.whole()):typePosition(role.path(),target);
                observe(role.category(),role.path(),target,owner,mapped);
            }
            for(var path:syntax.paths)if(path.getLeaf() instanceof CompilationUnitTree unit)walked.add(unit.getSourceFile().toUri().toString());
        }
    }
    static boolean guestEnvironment(String os,String machine,String user,String home,Path work){
        return os.equals("Linux")&&machine.equals("Virtual Machine")&&user.equals("evobuild")
                &&work.toString().matches("/work/[a-f0-9]{32}")&&home.equals(work.resolve("home").toString());
    }
    public static void main(String[] args)throws Exception{
        Path work=args.length==0?Path.of("/invalid"):Path.of(args[0]).normalize();String machine="";
        if(System.getProperty("os.name").equals("Linux"))machine=Files.readString(Path.of("/sys/class/dmi/id/product_name")).strip();
        if(!guestEnvironment(System.getProperty("os.name"),machine,System.getProperty("user.name"),System.getenv().getOrDefault("HOME",""),work))throw new IllegalStateException("ISOLATED_JAVAC_INDEX_REQUIRED");
        if(args.length<5||Runtime.version().feature()!=21)throw new IllegalArgumentException("INDEX_CONTEXT_REQUIRED");
        noAliases(work);Path output=work.resolve(args[1]).normalize();if(!output.startsWith(work.resolve("index")))throw new IllegalArgumentException("INDEX_OUTPUT_SCOPE");noAliases(output);
        int maximum=Integer.parseInt(args[3]);if(maximum<1)throw new IllegalArgumentException("INDEX_WORKER_BUDGET");
        var sources=new TreeMap<String,Source>();var files=new ArrayList<Path>();Path sealed=Path.of("/opt/evo-inputs",work.getFileName().toString());
        for(int i=4;i<args.length;i++){
            Path file=Path.of(args[i]).normalize();noAliases(file);
            if(!file.isAbsolute()||!(file.startsWith(sealed)||file.startsWith(work))||Files.isWritable(file))throw new IOException("INDEX_SOURCE_NOT_SEALED");
            if(Files.size(file)>32_000_000)throw new IOException("INDEX_SOURCE_WORKER_CAPACITY_REQUIRED");
            byte[] raw=Files.readAllBytes(file);String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
            String uri=file.toUri().toString();if(sources.putIfAbsent(uri,new Source(uri,text,digest(raw),raw.length,lines(text)))!=null)throw new IOException("INDEX_DUPLICATE_SOURCE");files.add(file);
        }
        for(String entry:args[2].split(File.pathSeparator,-1)){
            Path path=entry.isEmpty()?work:Path.of(entry).normalize();noAliases(path);
            if(!path.isAbsolute()||!(path.startsWith(sealed)||path.startsWith(work))||Files.isWritable(path))throw new IOException("INDEX_CLASSPATH_NOT_SEALED");
        }
        var options=List.of("--release","21","-encoding","UTF-8","-proc:none","-classpath",args[2],"-Xlint:none");
        var diagnostics=new DiagnosticCollector<JavaFileObject>();var result=new TreeMap<String,Object>();result.put("schema","java-native-selective-index:m8.4-v2");result.put("provider","isolated.javac-index:m8.4-v2");result.put("runtime",Runtime.version().toString());result.put("options",options);result.put("sourceFiles",sources.values().stream().map(s->Map.of("uri",s.uri(),"digest",s.digest(),"size",s.size())).toList());
        var compiler=ToolProvider.getSystemJavaCompiler();if(compiler==null)throw new IOException("INDEX_JDK_COMPILER_UNAVAILABLE");
        var errors=new HashSet<String>();var reports=new ArrayList<Object>();Capture captured=null;String state="PARTIAL";String failure=null;
        try(var manager=compiler.getStandardFileManager(diagnostics,Locale.ROOT,StandardCharsets.UTF_8)){
            var task=(JavacTask)compiler.getTask(new StringWriter(),manager,diagnostics,options,null,manager.getJavaFileObjectsFromPaths(files));
            var units=new ArrayList<CompilationUnitTree>();task.parse().forEach(units::add);
            var syntax=new Syntax(maximum,Trees.instance(task).getSourcePositions());for(var unit:units)syntax.scan(unit,null);
            captured=new Capture(task,sources,maximum,errors,syntax);
            task.analyze();for(var diagnostic:diagnostics.getDiagnostics())if(diagnostic.getKind()==Diagnostic.Kind.ERROR){state="ERROR";if(diagnostic.getSource()!=null)errors.add(diagnostic.getSource().toUri().toString());}
            captured.walk(syntax);
            result.put("knownModules",task.getElements().getAllModuleElements().stream().map(module->module.getQualifiedName().toString()).sorted().toList());
        }catch(Capacity exhausted){failure="INDEX_WORKER_CAPACITY_REQUIRED";}catch(IOException|RuntimeException unavailable){state="ERROR";failure=unavailable.getClass().getSimpleName();}
        for(var diagnostic:diagnostics.getDiagnostics()){
            var row=new TreeMap<String,Object>();row.put("kind",diagnostic.getKind().name());row.put("code",diagnostic.getCode());String uri=diagnostic.getSource()==null?null:diagnostic.getSource().toUri().toString();row.put("source",uri);
            row.put("span",uri==null||!sources.containsKey(uri)?null:span(sources.get(uri),diagnostic.getStartPosition(),diagnostic.getEndPosition()));reports.add(row);
        }
        result.put("state",state);result.put("failure",failure);result.put("diagnostics",reports);result.put("declarations",captured==null?List.of():captured.declarations);result.put("observations",captured==null?List.of():captured.observations);result.put("artifacts",captured==null?Map.of():captured.artifacts);
        var frontier=new TreeSet<>(sources.keySet());if(captured!=null)frontier.removeAll(captured.walked);result.put("walkedSources",captured==null?List.of():List.copyOf(captured.walked));result.put("frontier",List.copyOf(frontier));
        var coverage=new TreeMap<String,Object>();for(String category:CATEGORIES){var row=new TreeMap<String,Object>();row.put("state",OBSERVED.contains(category)?"OBSERVED_NOT_QUALIFIED_CENSUS":"UNAVAILABLE");row.put("observed",OBSERVED.contains(category)&&captured!=null?captured.observations.stream().map(value->(Map<?,?>)value).filter(value->("java."+category).equals(value.get("category"))).count():null);coverage.put("java."+category,row);}result.put("categories",coverage);result.put("qualified",false);result.put("semanticTrust","PRODUCER_ASSERTION");
        Files.writeString(output,json(result),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
    }
    static String json(Object value){
        if(value==null)return "null";if(value instanceof Number||value instanceof Boolean)return value.toString();
        if(value instanceof Map<?,?> map){var parts=new ArrayList<String>();for(String key:map.keySet().stream().map(Object::toString).sorted().toList())parts.add(json(key)+":"+json(map.get(key)));return "{"+String.join(",",parts)+"}";}
        if(value instanceof Collection<?> values)return "["+String.join(",",values.stream().map(CapturedJavacIndex::json).toList())+"]";
        if(!(value instanceof String text))throw new IllegalArgumentException("INDEX_JSON_VALUE");var out=new StringBuilder("\"");
        for(char c:text.toCharArray())switch(c){case '"'->out.append("\\\"");case '\\'->out.append("\\\\");default->{if(c<32||c>126)out.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else out.append(c);}}return out.append('"').toString();
    }
}
