package com.evolution.backend.query;

import com.sun.source.tree.*;
import com.sun.source.util.TreeScanner;

import javax.lang.model.element.Modifier;
import java.util.*;
import java.util.regex.Pattern;

/** Built-in recipes only. No repository-supplied plugins or arbitrary search/replace. */
final class JavaRepairRecipes {
    enum Recipe {
        PRIVATE_FIELD("java.remove-unused-private-field","Remove an unannotated private field that has no initializer or source references."),
        CONSTRUCTOR_PARAMETER("java.remove-empty-constructor-parameter","Remove an unannotated sole parameter from an empty constructor; review callers and reflection."),
        IMPORT("java.remove-unused-import","Remove a single named import with no other mention in this compilation unit."),
        INJECTED_FIELD("java.remove-unused-injected-field","Remove an unreferenced private @Autowired/@Inject/@Resource field and prune newly unused single imports."),
        CONSTRUCTOR_DEPENDENCY("java.remove-unused-constructor-dependency","Remove an unreferenced private field assigned from a constructor parameter, the parameter, and newly unused imports.");
        final String id, summary;
        Recipe(String id,String summary) { this.id=id; this.summary=summary; }
        Spec spec() { return new Spec(id,"1",summary); }
    }
    public record Spec(String id,String version,String summary) {}
    record Range(int start,int end) {}

    private static final Set<String> SERIALIZATION_FIELDS = Set.of("serialVersionUID","serialPersistentFields");
    private static final Set<String> DI_ANNOTATION_NAMES = Set.of(
            "Autowired","Inject","Resource","Qualifier",
            "org.springframework.beans.factory.annotation.Autowired",
            "org.springframework.beans.factory.annotation.Qualifier",
            "jakarta.inject.Inject","javax.inject.Inject",
            "jakarta.inject.Qualifier","javax.inject.Qualifier",
            "jakarta.annotation.Resource","javax.annotation.Resource");

    static Optional<Recipe> find(String id) { return Arrays.stream(Recipe.values()).filter(r -> r.id.equals(id)).findFirst(); }
    static List<Spec> catalog() { return Arrays.stream(Recipe.values()).map(Recipe::spec).toList(); }

    static List<String> candidates(Recipe recipe, RepairSource.File source, JavaRepairSyntax.Parsed parsed) {
        if (parsed.tree()==null || source.text().contains("\\u")) return List.of();
        if (recipe==Recipe.PRIVATE_FIELD || recipe==Recipe.CONSTRUCTOR_PARAMETER || recipe==Recipe.IMPORT)
            return edits(recipe,source,parsed).stream().map(r -> outside(source.text(),r)).toList();
        var targets=citationRanges(source);
        var text=source.text();
        var unit=parsed.tree();
        var results=new LinkedHashSet<String>();
        new TreeScanner<Void,Void>() {
            @Override public Void visitClass(ClassTree owner, Void unused) {
                if (owner.getKind()==Tree.Kind.CLASS) {
                    if (recipe==Recipe.INJECTED_FIELD) collectInjectedField(owner,unit,parsed,text,targets,results);
                    else if (recipe==Recipe.CONSTRUCTOR_DEPENDENCY) collectConstructorDep(owner,unit,parsed,text,targets,results);
                }
                return super.visitClass(owner,unused);
            }
        }.scan(unit,null);
        return List.copyOf(results);
    }

    static List<Range> edits(Recipe recipe, RepairSource.File source, JavaRepairSyntax.Parsed parsed) {
        if (parsed.tree()==null || source.text().contains("\\u")) return List.of();
        var targets=citationRanges(source);
        var result=new LinkedHashSet<Range>();
        var unit=parsed.tree();
        if (recipe==Recipe.IMPORT) {
            for (var item:unit.getImports()) {
                var range=range(parsed,item,source.text().length());
                if (range.isEmpty() || !cited(range.orElseThrow(),targets)
                        || !(item.getQualifiedIdentifier() instanceof MemberSelectTree select)
                        || select.getIdentifier().contentEquals("*")) continue;
                var edit=range.orElseThrow();
                if (removableText(source.text(),edit) && !mentions(outside(source.text(),edit),select.getIdentifier().toString())) result.add(edit);
            }
        } else if (recipe==Recipe.PRIVATE_FIELD || recipe==Recipe.CONSTRUCTOR_PARAMETER) {
            new TreeScanner<Void,Void>() {
                @Override public Void visitClass(ClassTree owner,Void unused) {
                    if (owner.getKind()==Tree.Kind.CLASS) {
                        for (var member:owner.getMembers()) {
                            if (recipe==Recipe.PRIVATE_FIELD && member instanceof VariableTree field
                                    && field.getModifiers().getFlags().equals(Set.of(Modifier.PRIVATE))
                                    && field.getModifiers().getAnnotations().isEmpty() && field.getInitializer()==null
                                    && !SERIALIZATION_FIELDS.contains(field.getName().toString())) {
                                range(parsed,field,source.text().length()).filter(edit -> cited(edit,targets)
                                        && source.text().charAt(edit.end()-1)==';' && removableText(source.text(),edit)
                                        && owner.getMembers().stream().noneMatch(other -> other!=field && other instanceof VariableTree
                                                && range(parsed,other,source.text().length()).filter(otherRange -> overlaps(edit,otherRange)).isPresent())
                                        && !mentions(outside(source.text(),edit),field.getName().toString())
                                        && !references(unit,field.getName().toString())).ifPresent(result::add);
                            }
                            if (recipe==Recipe.CONSTRUCTOR_PARAMETER && member instanceof MethodTree constructor
                                    && constructor.getReturnType()==null && constructor.getReceiverParameter()==null
                                    && constructor.getBody()!=null && constructor.getBody().getStatements().isEmpty()
                                    && constructor.getParameters().size()==1 && owner.getMembers().stream()
                                        .filter(m -> m instanceof MethodTree method && method.getReturnType()==null).count()==1) {
                                var parameter=constructor.getParameters().getFirst();
                                if (parameter.getModifiers().getAnnotations().isEmpty() && parameter.getModifiers().getFlags().isEmpty()
                                        && !parameter.getType().toString().contains("@"))
                                    range(parsed,parameter,source.text().length()).filter(edit -> cited(edit,targets)
                                            && removableText(source.text(),edit)).ifPresent(result::add);
                            }
                        }
                    }
                    return super.visitClass(owner,unused);
                }
            }.scan(unit,null);
        }
        return List.copyOf(result);
    }

    private static void collectInjectedField(ClassTree owner,CompilationUnitTree unit,JavaRepairSyntax.Parsed parsed,
                                             String text,List<Range> targets,Set<String> out) {
        for (var member : owner.getMembers()) {
            if (!(member instanceof VariableTree field)
                    || !field.getModifiers().getFlags().equals(Set.of(Modifier.PRIVATE))
                    || field.getModifiers().getAnnotations().isEmpty() || field.getInitializer()!=null
                    || SERIALIZATION_FIELDS.contains(field.getName().toString())) continue;
            boolean onlyDi=field.getModifiers().getAnnotations().stream()
                    .allMatch(ann -> DI_ANNOTATION_NAMES.contains(ann.getAnnotationType().toString()));
            if (!onlyDi) continue;
            var optEdit=range(parsed,field,text.length());
            if (optEdit.isEmpty()) continue;
            var edit=optEdit.orElseThrow();
            if (text.charAt(edit.end()-1)!=';' || !removableText(text,edit) || !citedOverlapping(edit,targets)) continue;
            boolean multiDecl=owner.getMembers().stream().anyMatch(other -> other!=field && other instanceof VariableTree
                    && range(parsed,other,text.length()).filter(otherRange -> overlaps(edit,otherRange)).isPresent());
            if (multiDecl) continue;
            String name=field.getName().toString();
            if (!mentionsInTypes(unit,parsed,text,List.of(edit),name) && !referencesInTypes(unit,parsed,List.of(edit),name))
                out.add(removeAndPruneImports(unit,parsed,text,List.of(edit)));
        }
    }

    private static void collectConstructorDep(ClassTree owner,CompilationUnitTree unit,JavaRepairSyntax.Parsed parsed,
                                              String text,List<Range> targets,Set<String> out) {
        var fields=new LinkedHashMap<String,VariableTree>();
        var constructors=new ArrayList<MethodTree>();
        for (var member : owner.getMembers()) {
            if (member instanceof VariableTree f) {
                var flags=f.getModifiers().getFlags();
                if (flags.contains(Modifier.PRIVATE) && Set.of(Modifier.PRIVATE,Modifier.FINAL).containsAll(flags)
                        && f.getModifiers().getAnnotations().isEmpty() && f.getInitializer()==null
                        && !SERIALIZATION_FIELDS.contains(f.getName().toString())) {
                    var fRange=range(parsed,f,text.length());
                    boolean multi=owner.getMembers().stream().anyMatch(other -> other!=f && other instanceof VariableTree
                            && fRange.flatMap(r -> range(parsed,other,text.length()).filter(or -> overlaps(r,or))).isPresent());
                    if (!multi) fields.put(f.getName().toString(),f);
                }
            } else if (member instanceof MethodTree m && m.getReturnType()==null && m.getReceiverParameter()==null && m.getBody()!=null) {
                constructors.add(m);
            }
        }
        if (constructors.size()!=1 || fields.isEmpty()) return;
        var ctor=constructors.getFirst();
        var params=ctor.getParameters();
        for (int i=0;i<params.size();i++) {
            var param=params.get(i);
            if (!param.getModifiers().getAnnotations().isEmpty() || param.getType().toString().contains("@")) continue;
            String paramName=param.getName().toString();
            String paramType=normalizedType(param.getType());
            for (var stmt : ctor.getBody().getStatements()) {
                var match=assignedField(stmt,paramName);
                if (match==null) continue;
                var field=fields.get(match.fieldName());
                if (field==null || !normalizedType(field.getType()).equals(paramType)) continue;
                var fRange=range(parsed,field,text.length());
                var pRange=range(parsed,param,text.length());
                var sRange=range(parsed,stmt,text.length());
                if (fRange.isEmpty() || pRange.isEmpty() || sRange.isEmpty()) continue;
                var fr=fRange.orElseThrow(); var pr=pRange.orElseThrow(); var sr=sRange.orElseThrow();
                if (text.charAt(fr.end()-1)!=';' || !removableText(text,fr) || !removableText(text,pr) || !removableText(text,sr)) continue;
                if (!citedOverlapping(fr,targets) && !citedOverlapping(pr,targets) && !citedOverlapping(sr,targets)) continue;
                Range paramRemoval=pr;
                if (params.size()>1) {
                    if (i<params.size()-1) {
                        var nextRange=range(parsed,params.get(i+1),text.length());
                        if (nextRange.isEmpty()) continue;
                        paramRemoval=new Range(pr.start(),nextRange.orElseThrow().start());
                    } else {
                        var prevRange=range(parsed,params.get(i-1),text.length());
                        if (prevRange.isEmpty()) continue;
                        paramRemoval=new Range(prevRange.orElseThrow().end(),pr.end());
                    }
                    if (!removableText(text,paramRemoval)) continue;
                }
                List<Range> removals=List.of(fr,paramRemoval,sr);
                if (overlapsAny(removals)) continue;
                if (mentionsInTypes(unit,parsed,text,removals,field.getName().toString())
                        || referencesInTypes(unit,parsed,removals,field.getName().toString())) continue;
                if (!paramName.equals(field.getName().toString())) {
                    var bodyRange=range(parsed,ctor.getBody(),text.length());
                    if (bodyRange.isEmpty()) continue;
                    String ctorRemaining=outside(text,sr);
                    int bStart=bodyRange.orElseThrow().start();
                    int bEnd=Math.max(bStart,bodyRange.orElseThrow().end()-(sr.end()-sr.start()));
                    if (bEnd<=ctorRemaining.length() && mentions(ctorRemaining.substring(bStart,bEnd),paramName)) continue;
                }
                out.add(removeAndPruneImports(unit,parsed,text,removals));
            }
        }
    }

    private record AssignmentMatch(String fieldName) {}
    private static AssignmentMatch assignedField(StatementTree stmt,String paramName) {
        if (!(stmt instanceof ExpressionStatementTree exprStmt)) return null;
        if (!(exprStmt.getExpression() instanceof AssignmentTree assign) || assign.getKind()!=Tree.Kind.ASSIGNMENT) return null;
        String fieldName=null;
        if (assign.getVariable() instanceof IdentifierTree id) fieldName=id.getName().toString();
        else if (assign.getVariable() instanceof MemberSelectTree sel
                && sel.getExpression() instanceof IdentifierTree recv && recv.getName().contentEquals("this"))
            fieldName=sel.getIdentifier().toString();
        if (fieldName==null) return null;
        ExpressionTree rhs=assign.getExpression();
        if (rhs instanceof IdentifierTree id && id.getName().contentEquals(paramName)) return new AssignmentMatch(fieldName);
        if (rhs instanceof MethodInvocationTree call && call.getArguments().size()==1
                && call.getArguments().getFirst() instanceof IdentifierTree arg && arg.getName().contentEquals(paramName)
                && call.getMethodSelect() instanceof MemberSelectTree ms && ms.getIdentifier().contentEquals("requireNonNull")
                && ms.getExpression().toString().endsWith("Objects"))
            return new AssignmentMatch(fieldName);
        return null;
    }

    private static String normalizedType(Tree type) { return type.toString().replaceAll("\\s+",""); }

    private static String removeAndPruneImports(CompilationUnitTree unit,JavaRepairSyntax.Parsed parsed,String text,List<Range> initial) {
        List<Range> expanded=new ArrayList<>();
        for (var r : initial) expanded.add(expandLineIfStandalone(text,r));
        expanded=mergeRanges(expanded);
        String afterPrimary=outsideAll(text,expanded);
        var importRemovals=new ArrayList<Range>();
        for (var item : unit.getImports()) {
            if (item.isStatic()) continue;
            var r=range(parsed,item,text.length());
            if (r.isEmpty() || !(item.getQualifiedIdentifier() instanceof MemberSelectTree select) || select.getIdentifier().contentEquals("*")) continue;
            var ir=r.orElseThrow();
            if (!removableText(text,ir) || expanded.stream().anyMatch(e -> overlaps(e,ir))) continue;
            String simple=select.getIdentifier().toString();
            String withoutThisImport=afterPrimary.replace(text.substring(ir.start(),ir.end()),"");
            if (!mentions(withoutThisImport,simple)) importRemovals.add(expandLineIfStandalone(text,ir));
        }
        if (importRemovals.isEmpty()) return afterPrimary;
        var all=new ArrayList<>(expanded);
        all.addAll(importRemovals);
        return outsideAll(text,mergeRanges(all));
    }

    private static Range expandLineIfStandalone(String text,Range range) {
        int lineStart=range.start();
        while (lineStart>0 && (text.charAt(lineStart-1)==' ' || text.charAt(lineStart-1)=='\t')) lineStart--;
        if (lineStart>0 && text.charAt(lineStart-1)!='\n' && text.charAt(lineStart-1)!='\r') return range;
        int lineEnd=range.end();
        while (lineEnd<text.length() && (text.charAt(lineEnd)==' ' || text.charAt(lineEnd)=='\t')) lineEnd++;
        if (lineEnd<text.length() && text.charAt(lineEnd)=='\r') lineEnd++;
        if (lineEnd<text.length() && text.charAt(lineEnd)=='\n') lineEnd++;
        else if (lineEnd<text.length()) return range;
        return new Range(lineStart,lineEnd);
    }

    private static List<Range> mergeRanges(List<Range> ranges) {
        var sorted=new ArrayList<>(ranges);
        sorted.sort(Comparator.comparingInt(Range::start).thenComparingInt(Range::end));
        var merged=new ArrayList<Range>();
        for (var r : sorted) {
            if (!merged.isEmpty() && r.start()<=merged.getLast().end()) {
                var last=merged.removeLast();
                merged.add(new Range(last.start(),Math.max(last.end(),r.end())));
            } else merged.add(r);
        }
        return merged;
    }

    private static boolean overlapsAny(List<Range> ranges) {
        var sorted=new ArrayList<>(ranges);
        sorted.sort(Comparator.comparingInt(Range::start));
        for (int i=1;i<sorted.size();i++) if (sorted.get(i).start()<sorted.get(i-1).end()) return true;
        return false;
    }

    private static String outsideAll(String text,List<Range> ranges) {
        var sorted=new ArrayList<>(ranges);
        sorted.sort(Comparator.comparingInt(Range::start));
        var out=new StringBuilder();
        int cursor=0;
        for (var r : sorted) {
            out.append(text,cursor,r.start());
            cursor=r.end();
        }
        return out.append(text,cursor,text.length()).toString();
    }

    private static boolean mentionsInTypes(CompilationUnitTree unit,JavaRepairSyntax.Parsed parsed,String text,List<Range> removals,String name) {
        String withoutRemovals=outsideAll(text,removals);
        int typeStart=text.length();
        for (var decl : unit.getTypeDecls()) {
            long pos=parsed.positions().getStartPosition(unit,decl);
            if (pos>=0 && pos<typeStart) typeStart=(int)pos;
        }
        int removedBefore=0;
        for (var r : removals) {
            if (r.end()<=typeStart) removedBefore+=r.end()-r.start();
            else if (r.start()<typeStart) removedBefore+=typeStart-r.start();
        }
        int adjusted=Math.max(0,Math.min(typeStart-removedBefore,withoutRemovals.length()));
        return mentions(withoutRemovals.substring(adjusted),name);
    }

    private static boolean referencesInTypes(CompilationUnitTree unit,JavaRepairSyntax.Parsed parsed,List<Range> excluded,String name) {
        for (var decl : unit.getTypeDecls()) {
            boolean found=Boolean.TRUE.equals(new TreeScanner<Boolean,Void>() {
                @Override public Boolean reduce(Boolean a,Boolean b) { return Boolean.TRUE.equals(a) || Boolean.TRUE.equals(b); }
                private boolean excludedNode(Tree t) {
                    return range(parsed,t,Integer.MAX_VALUE).filter(r -> excluded.stream().anyMatch(e -> e.start()<=r.start() && r.end()<=e.end())).isPresent();
                }
                @Override public Boolean scan(Tree t,Void v) {
                    if (t==null || excludedNode(t)) return false;
                    return super.scan(t,v);
                }
                @Override public Boolean visitIdentifier(IdentifierTree tree,Void unused) { return tree.getName().contentEquals(name); }
                @Override public Boolean visitMemberSelect(MemberSelectTree tree,Void unused) {
                    return tree.getIdentifier().contentEquals(name) || Boolean.TRUE.equals(super.visitMemberSelect(tree,unused));
                }
            }.scan(decl,null));
            if (found) return true;
        }
        return false;
    }

    private static boolean references(CompilationUnitTree unit,String name) {
        return Boolean.TRUE.equals(new TreeScanner<Boolean,Void>() {
            @Override public Boolean reduce(Boolean a,Boolean b) { return Boolean.TRUE.equals(a) || Boolean.TRUE.equals(b); }
            @Override public Boolean visitIdentifier(IdentifierTree tree,Void unused) { return tree.getName().contentEquals(name); }
            @Override public Boolean visitMemberSelect(MemberSelectTree tree,Void unused) {
                return tree.getIdentifier().contentEquals(name) || Boolean.TRUE.equals(super.visitMemberSelect(tree,unused));
            }
        }.scan(unit,null));
    }
    private static boolean mentions(String source,String identifier) {
        return Pattern.compile("(?<!\\p{javaJavaIdentifierPart})"+Pattern.quote(identifier)+"(?!\\p{javaJavaIdentifierPart})")
                .matcher(source).find();
    }
    private static boolean removableText(String source,Range range) {
        String text=source.substring(range.start(),range.end());
        return !text.contains("//") && !text.contains("/*");
    }
    static String outside(String text,Range removed) { return text.substring(0,removed.start())+text.substring(removed.end()); }
    private static boolean cited(Range edit,List<Range> targets) {
        // A broad class declaration citation does not authorize editing every nested member.
        return targets.stream().anyMatch(target -> edit.start()<=target.start() && target.end()<=edit.end());
    }
    private static boolean citedOverlapping(Range edit,List<Range> targets) {
        return targets.stream().anyMatch(target -> overlaps(edit,target));
    }
    static boolean overlaps(Range a,Range b) { return a.start()<b.end() && b.start()<a.end(); }
    private static Optional<Range> range(JavaRepairSyntax.Parsed parsed,Tree tree,int length) {
        long start=parsed.positions().getStartPosition(parsed.tree(),tree), end=parsed.positions().getEndPosition(parsed.tree(),tree);
        return start<0 || end<=start || end>length ? Optional.empty() : Optional.of(new Range((int)start,(int)end));
    }
    static List<Range> citationRanges(RepairSource.File file) {
        String text=file.text();
        var starts=new ArrayList<Integer>();
        var ends=new ArrayList<Integer>();
        starts.add(0);
        for (int i=0;i<text.length();i++) {
            if (text.charAt(i)=='\r' || text.charAt(i)=='\n') {
                ends.add(i);
                if (text.charAt(i)=='\r' && i+1<text.length() && text.charAt(i+1)=='\n') i++;
                starts.add(i+1);
            }
        }
        ends.add(text.length());
        var result=new ArrayList<Range>();
        for (var citation:file.citations()) {
            var span=citation.span();
            if (span.endLine()>starts.size()) continue;
            long start=(long)starts.get(span.startLine()-1)+span.startColumn()-1;
            long end=(long)starts.get(span.endLine()-1)+span.endColumn()-1;
            if (start<=ends.get(span.startLine()-1) && end<=ends.get(span.endLine()-1) && end>start)
                result.add(new Range((int)start,(int)end));
        }
        return result;
    }
    private JavaRepairRecipes() {}
}
