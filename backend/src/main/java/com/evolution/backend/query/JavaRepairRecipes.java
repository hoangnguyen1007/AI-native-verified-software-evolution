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
        CONSTRUCTOR_DEPENDENCY("java.remove-unused-constructor-dependency","Remove an unreferenced private field assigned from a constructor parameter, the parameter, and newly unused imports."),
        CONVERT_FIELD_INJECTION("java.convert-field-injection-to-constructor","Convert a cited private @Autowired/@Inject field into a private final constructor-injected dependency."),
        LAZY_CYCLE_BREAKER("java.break-cycle-with-lazy-injection","Add Spring @Lazy to a cited @Autowired field or constructor parameter to break a circular bean dependency."),
        REMOVE_UNUSED_PRIVATE_METHOD("java.remove-unused-private-method","Remove an unreferenced, unannotated private method with no serialization or lifecycle role."),
        CONVERT_SETTER_INJECTION("java.convert-setter-injection-to-constructor","Convert a cited @Autowired/@Inject setter method and backing field into private final constructor injection."),
        CONVERT_ALL_FIELD_INJECTIONS("java.convert-all-field-injections-to-constructor","Convert all @Autowired/@Inject fields in the cited class into private final constructor-injected dependencies."),
        MAKE_DEPENDENCY_OPTIONAL("java.make-dependency-optional","Make a cited @Autowired injection site or constructor parameter optional."),
        OBJECT_PROVIDER_CYCLE_BREAKER("java.break-cycle-with-object-provider","Wrap a cited circularly injected dependency in Spring ObjectProvider<T> to break the cycle."),
        REPLACE_NEW_WITH_CONSTRUCTOR_INJECTION("java.replace-new-with-constructor-injection","Replace a cited direct no-arg instantiation of a collaborator with constructor injection."),
        REMOVE_UNUSED_STATIC_IMPORT("java.remove-unused-static-import","Remove a single static import with no other mention in this compilation unit.");
        final String id, summary;
        Recipe(String id,String summary) { this.id=id; this.summary=summary; }
        Spec spec() { return new Spec(id,"1",summary); }
    }
    public record Spec(String id,String version,String summary) {}
    record Range(int start,int end) {}

    private static final Set<String> SERIALIZATION_FIELDS = Set.of("serialVersionUID","serialPersistentFields");
    private static final Set<String> SERIALIZATION_METHODS = Set.of("readObject","writeObject","readResolve","writeReplace");
    private static final Set<String> EXCLUDED_NEW_TYPES = Set.of(
            "ArrayList","LinkedList","HashMap","LinkedHashMap","TreeMap","HashSet","LinkedHashSet","TreeSet",
            "ConcurrentHashMap","ConcurrentSkipListMap","ConcurrentSkipListSet","ConcurrentLinkedQueue",
            "CopyOnWriteArrayList","CopyOnWriteArraySet","ArrayDeque","PriorityQueue","Vector","Stack",
            "Properties","Hashtable","StringBuilder","StringBuffer","String","Object",
            "ReentrantLock","ReentrantReadWriteLock","StampedLock","Semaphore","CountDownLatch","CyclicBarrier","Phaser",
            "Date","Random","SecureRandom","ThreadLocal","BitSet","UUID","Optional",
            "List","Map","Set","Queue","Deque","Collection","Lock");
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
        if (recipe==Recipe.PRIVATE_FIELD || recipe==Recipe.CONSTRUCTOR_PARAMETER
                || recipe==Recipe.IMPORT || recipe==Recipe.REMOVE_UNUSED_STATIC_IMPORT)
            return edits(recipe,source,parsed).stream().map(r -> outside(source.text(),r)).toList();
        var targets=citationRanges(source);
        var text=source.text();
        var unit=parsed.tree();
        var results=new LinkedHashSet<String>();
        new TreeScanner<Void,Void>() {
            @Override public Void visitClass(ClassTree owner, Void unused) {
                if (owner.getKind()==Tree.Kind.CLASS) {
                    switch (recipe) {
                        case INJECTED_FIELD -> collectInjectedField(owner,unit,parsed,text,targets,results);
                        case CONSTRUCTOR_DEPENDENCY -> collectConstructorDep(owner,unit,parsed,text,targets,results);
                        case CONVERT_FIELD_INJECTION -> collectConvertFieldInjection(owner,unit,parsed,text,targets,results);
                        case LAZY_CYCLE_BREAKER -> collectLazyCycleBreaker(owner,unit,parsed,text,targets,results);
                        case REMOVE_UNUSED_PRIVATE_METHOD -> collectUnusedPrivateMethod(owner,unit,parsed,text,targets,results);
                        case CONVERT_SETTER_INJECTION -> collectConvertSetterInjection(owner,unit,parsed,text,targets,results);
                        case CONVERT_ALL_FIELD_INJECTIONS -> collectConvertAllFieldInjections(owner,unit,parsed,text,targets,results);
                        case MAKE_DEPENDENCY_OPTIONAL -> collectMakeDependencyOptional(owner,unit,parsed,text,targets,results);
                        case OBJECT_PROVIDER_CYCLE_BREAKER -> collectObjectProviderCycleBreaker(owner,unit,parsed,text,targets,results);
                        case REPLACE_NEW_WITH_CONSTRUCTOR_INJECTION -> collectReplaceNewWithConstructorInjection(owner,unit,parsed,text,targets,results);
                        default -> {}
                    }
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
                if (item.isStatic()) continue;
                var range=range(parsed,item,source.text().length());
                if (range.isEmpty() || !cited(range.orElseThrow(),targets)
                        || !(item.getQualifiedIdentifier() instanceof MemberSelectTree select)
                        || select.getIdentifier().contentEquals("*")) continue;
                var edit=range.orElseThrow();
                if (removableText(source.text(),edit) && !mentions(outside(source.text(),edit),select.getIdentifier().toString())) result.add(edit);
            }
        } else if (recipe==Recipe.REMOVE_UNUSED_STATIC_IMPORT) {
            for (var item:unit.getImports()) {
                if (!item.isStatic()) continue;
                var range=range(parsed,item,source.text().length());
                if (range.isEmpty() || !cited(range.orElseThrow(),targets)
                        || !(item.getQualifiedIdentifier() instanceof MemberSelectTree select)
                        || select.getIdentifier().contentEquals("*")) continue;
                var edit=expandLineIfStandalone(source.text(),range.orElseThrow());
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

    private record TextPatch(int start, int end, String replacement) {}

    private static String applyPatches(String text, List<TextPatch> patches) {
        var sorted = new ArrayList<>(patches);
        sorted.sort(Comparator.comparingInt(TextPatch::start).thenComparingInt(TextPatch::end));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).start() < sorted.get(i - 1).end()) throw new IllegalArgumentException("Overlapping patches");
        }
        var out = new StringBuilder();
        int cursor = 0;
        for (var p : sorted) {
            out.append(text, cursor, p.start()).append(p.replacement());
            cursor = p.end();
        }
        return out.append(text, cursor, text.length()).toString();
    }

    private static void collectConvertFieldInjection(ClassTree owner, CompilationUnitTree unit, JavaRepairSyntax.Parsed parsed,
                                                     String text, List<Range> targets, Set<String> out) {
        if (hasLombokConstructor(owner)) return;
        var constructors = concreteConstructors(owner);
        if (constructors.size() > 1) return;
        String nl = text.contains("\r\n") ? "\r\n" : "\n";
        for (var member : owner.getMembers()) {
            if (!(member instanceof VariableTree field)
                    || !field.getModifiers().getFlags().equals(Set.of(Modifier.PRIVATE))
                    || field.getModifiers().getAnnotations().isEmpty()
                    || field.getInitializer() != null) continue;
            var optRange = range(parsed, field, text.length());
            if (optRange.isEmpty()) continue;
            var fr = optRange.orElseThrow();
            if (text.charAt(fr.end() - 1) != ';' || !removableText(text, fr) || !citedOverlapping(fr, targets)) continue;
            if (hasOverlappingSiblingField(owner, field, fr, parsed, text.length())) continue;
            boolean hasInject = false;
            boolean onlyDi = true;
            String qualifier = "";
            for (var ann : field.getModifiers().getAnnotations()) {
                String annName = ann.getAnnotationType().toString();
                if (!DI_ANNOTATION_NAMES.contains(annName)) { onlyDi = false; break; }
                if (annName.endsWith("Autowired") || annName.endsWith("Inject")) hasInject = true;
                if (annName.endsWith("Qualifier")) {
                    var qRange = range(parsed, ann, text.length());
                    if (qRange.isPresent()) qualifier = text.substring(qRange.orElseThrow().start(), qRange.orElseThrow().end()) + " ";
                }
            }
            if (!onlyDi || !hasInject) continue;
            var typeRange = range(parsed, field.getType(), text.length());
            if (typeRange.isEmpty()) continue;
            String typeText = text.substring(typeRange.orElseThrow().start(), typeRange.orElseThrow().end()).trim();
            String fieldName = field.getName().toString();
            String indent = lineIndentAt(text, fr.start());
            String newField = "private final " + typeText + " " + fieldName + ";";
            var patches = new ArrayList<TextPatch>();
            if (constructors.isEmpty()) {
                String ctorBlock = nl + nl + indent + owner.getSimpleName() + "(" + qualifier + typeText + " " + fieldName + ") {"
                        + nl + indent + "    this." + fieldName + " = " + fieldName + ";"
                        + nl + indent + "}";
                patches.add(new TextPatch(fr.start(), fr.end(), newField + ctorBlock));
            } else {
                var ctor = constructors.getFirst();
                if (!appendCtorInjections(ctor, parsed, text, nl, List.of(new InjectedMember(field, fr, typeText, fieldName, qualifier)), patches)) continue;
                patches.add(new TextPatch(fr.start(), fr.end(), newField));
            }
            if (!hasOverlappingTextPatches(patches)) {
                String updated = applyPatches(text, patches);
                out.add(pruneUnusedDiImports(updated));
            }
        }
    }

    private static void collectLazyCycleBreaker(ClassTree owner, CompilationUnitTree unit, JavaRepairSyntax.Parsed parsed,
                                                String text, List<Range> targets, Set<String> out) {
        boolean hasLazyImport = unit.getImports().stream().anyMatch(imp -> {
            String q = imp.getQualifiedIdentifier().toString();
            return q.equals("org.springframework.context.annotation.Lazy") || q.equals("org.springframework.context.annotation.*");
        });
        String nl = text.contains("\r\n") ? "\r\n" : "\n";
        for (var member : owner.getMembers()) {
            if (member instanceof VariableTree field && !field.getModifiers().getAnnotations().isEmpty()) {
                boolean isDi = field.getModifiers().getAnnotations().stream()
                        .anyMatch(ann -> DI_ANNOTATION_NAMES.contains(ann.getAnnotationType().toString()));
                boolean alreadyLazy = field.getModifiers().getAnnotations().stream()
                        .anyMatch(ann -> ann.getAnnotationType().toString().equals("Lazy")
                                || ann.getAnnotationType().toString().equals("org.springframework.context.annotation.Lazy"));
                var fr = range(parsed, field, text.length());
                if (!isDi || alreadyLazy || fr.isEmpty() || !citedOverlapping(fr.orElseThrow(), targets)) continue;
                String indent = lineIndentAt(text, fr.orElseThrow().start());
                var patches = new ArrayList<TextPatch>();
                if (!hasLazyImport && !addImportPatch(unit, parsed, text, "org.springframework.context.annotation.Lazy", nl, patches)) continue;
                patches.add(new TextPatch(fr.orElseThrow().start(), fr.orElseThrow().start(), "@Lazy" + nl + indent));
                out.add(applyPatches(text, patches));
            } else if (member instanceof MethodTree ctor && ctor.getReturnType() == null && ctor.getReceiverParameter() == null) {
                for (var param : ctor.getParameters()) {
                    boolean alreadyLazy = param.getModifiers().getAnnotations().stream()
                            .anyMatch(ann -> ann.getAnnotationType().toString().equals("Lazy")
                                    || ann.getAnnotationType().toString().equals("org.springframework.context.annotation.Lazy"));
                    var pr = range(parsed, param, text.length());
                    if (alreadyLazy || pr.isEmpty() || !citedOverlapping(pr.orElseThrow(), targets)) continue;
                    var patches = new ArrayList<TextPatch>();
                    if (!hasLazyImport && !addImportPatch(unit, parsed, text, "org.springframework.context.annotation.Lazy", nl, patches)) continue;
                    patches.add(new TextPatch(pr.orElseThrow().start(), pr.orElseThrow().start(), "@Lazy "));
                    out.add(applyPatches(text, patches));
                }
            }
        }
    }

    private static void collectUnusedPrivateMethod(ClassTree owner, CompilationUnitTree unit, JavaRepairSyntax.Parsed parsed,
                                                   String text, List<Range> targets, Set<String> out) {
        for (var member : owner.getMembers()) {
            if (!(member instanceof MethodTree method) || method.getReturnType() == null
                    || method.getReceiverParameter() != null || !method.getModifiers().getAnnotations().isEmpty()) continue;
            var flags = method.getModifiers().getFlags();
            if (!flags.contains(Modifier.PRIVATE) || flags.contains(Modifier.NATIVE)) continue;
            String name = method.getName().toString();
            if (SERIALIZATION_METHODS.contains(name)) continue;
            var optMr = range(parsed, method, text.length());
            if (optMr.isEmpty()) continue;
            var mr = optMr.orElseThrow();
            if (!removableText(text, mr) || !citedOverlapping(mr, targets)) continue;
            var bodyRange = List.of(mr);
            if (!mentionsInTypes(unit, parsed, text, bodyRange, name)
                    && !referencesInTypes(unit, parsed, bodyRange, name)) {
                out.add(removeAndPruneImports(unit, parsed, text, bodyRange));
            }
        }
    }

    private static void collectConvertSetterInjection(ClassTree owner, CompilationUnitTree unit, JavaRepairSyntax.Parsed parsed,
                                                      String text, List<Range> targets, Set<String> out) {
        if (hasLombokConstructor(owner)) return;
        var constructors = concreteConstructors(owner);
        if (constructors.size() > 1) return;
        var fields = privateUnannotatedOrDiFields(owner);
        String nl = text.contains("\r\n") ? "\r\n" : "\n";
        for (var member : owner.getMembers()) {
            if (!(member instanceof MethodTree setter) || setter.getReturnType() == null
                    || setter.getReceiverParameter() != null
                    || !"void".equals(normalizedType(setter.getReturnType()))
                    || setter.getParameters().size() != 1
                    || setter.getBody() == null || setter.getBody().getStatements().size() != 1) continue;
            if (setter.getModifiers().getAnnotations().isEmpty()) continue;
            boolean hasInject = false;
            boolean onlyDi = true;
            for (var ann : setter.getModifiers().getAnnotations()) {
                String n = ann.getAnnotationType().toString();
                if (!DI_ANNOTATION_NAMES.contains(n)) { onlyDi = false; break; }
                if (n.endsWith("Autowired") || n.endsWith("Inject")) hasInject = true;
            }
            if (!onlyDi || !hasInject) continue;
            var param = setter.getParameters().getFirst();
            if (param.getType().toString().contains("@")) continue;
            String qualifier = extractQualifier(param.getModifiers().getAnnotations(), parsed, text);
            if (qualifier == null) qualifier = extractQualifier(setter.getModifiers().getAnnotations(), parsed, text);
            if (qualifier == null) qualifier = "";
            String paramName = param.getName().toString();
            String paramType = normalizedType(param.getType());
            var match = assignedField(setter.getBody().getStatements().getFirst(), paramName);
            if (match == null) continue;
            var field = fields.get(match.fieldName());
            if (field == null || field.getInitializer() != null || !normalizedType(field.getType()).equals(paramType)) continue;
            var optMr = range(parsed, setter, text.length());
            var optFr = range(parsed, field, text.length());
            var optTr = range(parsed, field.getType(), text.length());
            if (optMr.isEmpty() || optFr.isEmpty() || optTr.isEmpty()) continue;
            var mr = optMr.orElseThrow();
            var fr = optFr.orElseThrow();
            if (text.charAt(fr.end() - 1) != ';' || !removableText(text, mr) || !removableText(text, fr)) continue;
            if (!citedOverlapping(mr, targets) && !citedOverlapping(fr, targets)) continue;
            if (hasOverlappingSiblingField(owner, field, fr, parsed, text.length())) continue;
            String setterName = setter.getName().toString();
            if (mentionsInTypes(unit, parsed, text, List.of(mr), setterName)
                    || referencesInTypes(unit, parsed, List.of(mr), setterName)) continue;

            String typeText = text.substring(optTr.orElseThrow().start(), optTr.orElseThrow().end()).trim();
            String fieldName = field.getName().toString();
            String newField = "private final " + typeText + " " + fieldName + ";";
            var patches = new ArrayList<TextPatch>();
            var setterRemoval = expandLineIfStandalone(text, mr);
            patches.add(new TextPatch(setterRemoval.start(), setterRemoval.end(), ""));
            if (constructors.isEmpty()) {
                String indent = lineIndentAt(text, fr.start());
                String ctorBlock = nl + nl + indent + owner.getSimpleName() + "(" + qualifier + typeText + " " + fieldName + ") {"
                        + nl + indent + "    this." + fieldName + " = " + fieldName + ";"
                        + nl + indent + "}";
                patches.add(new TextPatch(fr.start(), fr.end(), newField + ctorBlock));
            } else {
                var ctor = constructors.getFirst();
                if (assignsFieldInConstructor(ctor, fieldName)) continue;
                if (!appendCtorInjections(ctor, parsed, text, nl, List.of(new InjectedMember(field, fr, typeText, fieldName, qualifier)), patches)) continue;
                patches.add(new TextPatch(fr.start(), fr.end(), newField));
            }
            if (!hasOverlappingTextPatches(patches)) {
                out.add(pruneUnusedDiImports(applyPatches(text, patches)));
            }
        }
    }

    private record InjectedMember(VariableTree field, Range range, String typeText, String fieldName, String qualifier) {}

    private static void collectConvertAllFieldInjections(ClassTree owner, CompilationUnitTree unit, JavaRepairSyntax.Parsed parsed,
                                                         String text, List<Range> targets, Set<String> out) {
        if (hasLombokConstructor(owner)) return;
        var constructors = concreteConstructors(owner);
        if (constructors.size() > 1) return;
        String nl = text.contains("\r\n") ? "\r\n" : "\n";
        var eligible = new ArrayList<InjectedMember>();
        boolean anyCited = false;
        for (var member : owner.getMembers()) {
            if (!(member instanceof VariableTree field)
                    || !field.getModifiers().getFlags().equals(Set.of(Modifier.PRIVATE))
                    || field.getModifiers().getAnnotations().isEmpty()
                    || field.getInitializer() != null) continue;
            var optFr = range(parsed, field, text.length());
            var optTr = range(parsed, field.getType(), text.length());
            if (optFr.isEmpty() || optTr.isEmpty()) continue;
            var fr = optFr.orElseThrow();
            if (text.charAt(fr.end() - 1) != ';' || !removableText(text, fr) || hasOverlappingSiblingField(owner, field, fr, parsed, text.length())) continue;
            boolean hasInject = false;
            boolean onlyDi = true;
            String qualifier = "";
            for (var ann : field.getModifiers().getAnnotations()) {
                String annName = ann.getAnnotationType().toString();
                if (!DI_ANNOTATION_NAMES.contains(annName)) { onlyDi = false; break; }
                if (annName.endsWith("Autowired") || annName.endsWith("Inject")) hasInject = true;
                if (annName.endsWith("Qualifier")) {
                    var qRange = range(parsed, ann, text.length());
                    if (qRange.isPresent()) qualifier = text.substring(qRange.orElseThrow().start(), qRange.orElseThrow().end()) + " ";
                }
            }
            if (!onlyDi || !hasInject) continue;
            if (citedOverlapping(fr, targets)) anyCited = true;
            String typeText = text.substring(optTr.orElseThrow().start(), optTr.orElseThrow().end()).trim();
            eligible.add(new InjectedMember(field, fr, typeText, field.getName().toString(), qualifier));
        }
        if (!anyCited || eligible.isEmpty()) return;
        var patches = new ArrayList<TextPatch>();
        if (constructors.isEmpty()) {
            for (int i = 0; i < eligible.size(); i++) {
                var item = eligible.get(i);
                String rep = "private final " + item.typeText() + " " + item.fieldName() + ";";
                if (i == eligible.size() - 1) {
                    String indent = lineIndentAt(text, item.range().start());
                    String paramsJoined = eligible.stream()
                            .map(e -> e.qualifier() + e.typeText() + " " + e.fieldName())
                            .collect(java.util.stream.Collectors.joining(", "));
                    var bodyLines = new StringBuilder();
                    for (var e : eligible) {
                        bodyLines.append(nl).append(indent).append("    this.").append(e.fieldName()).append(" = ").append(e.fieldName()).append(";");
                    }
                    rep = rep + nl + nl + indent + owner.getSimpleName() + "(" + paramsJoined + ") {" + bodyLines + nl + indent + "}";
                }
                patches.add(new TextPatch(item.range().start(), item.range().end(), rep));
            }
        } else {
            var ctor = constructors.getFirst();
            if (!appendCtorInjections(ctor, parsed, text, nl, eligible, patches)) return;
            for (var item : eligible) {
                patches.add(new TextPatch(item.range().start(), item.range().end(),
                        "private final " + item.typeText() + " " + item.fieldName() + ";"));
            }
        }
        if (!hasOverlappingTextPatches(patches)) {
            out.add(pruneUnusedDiImports(applyPatches(text, patches)));
        }
    }

    private static void collectMakeDependencyOptional(ClassTree owner, CompilationUnitTree unit, JavaRepairSyntax.Parsed parsed,
                                                      String text, List<Range> targets, Set<String> out) {
        String nl = text.contains("\r\n") ? "\r\n" : "\n";
        boolean hasOptionalImport = unit.getImports().stream().anyMatch(imp -> {
            String q = imp.getQualifiedIdentifier().toString();
            return q.equals("java.util.Optional") || q.equals("java.util.*");
        });
        for (var member : owner.getMembers()) {
            if (member instanceof VariableTree field) {
                var fr = range(parsed, field, text.length());
                if (fr.isEmpty() || !citedOverlapping(fr.orElseThrow(), targets)) continue;
                for (var ann : field.getModifiers().getAnnotations()) {
                    String annName = ann.getAnnotationType().toString();
                    if (!annName.endsWith("Autowired")) continue;
                    var ar = range(parsed, ann, text.length());
                    if (ar.isPresent() && !text.substring(ar.orElseThrow().start(), ar.orElseThrow().end()).contains("required")) {
                        int dot = annName.lastIndexOf('.');
                        String base = dot >= 0 ? "@" + annName : "@Autowired";
                        out.add(applyPatches(text, List.of(new TextPatch(ar.orElseThrow().start(), ar.orElseThrow().end(), base + "(required = false)"))));
                    }
                }
            } else if (member instanceof MethodTree method) {
                var mr = range(parsed, method, text.length());
                if (mr.isPresent() && citedOverlapping(mr.orElseThrow(), targets)) {
                    for (var ann : method.getModifiers().getAnnotations()) {
                        String annName = ann.getAnnotationType().toString();
                        if (!annName.endsWith("Autowired")) continue;
                        var ar = range(parsed, ann, text.length());
                        if (ar.isPresent() && !text.substring(ar.orElseThrow().start(), ar.orElseThrow().end()).contains("required")) {
                            String base = annName.contains(".") ? "@" + annName : "@Autowired";
                            out.add(applyPatches(text, List.of(new TextPatch(ar.orElseThrow().start(), ar.orElseThrow().end(), base + "(required = false)"))));
                        }
                    }
                }
                if (method.getReturnType() == null && method.getReceiverParameter() == null && method.getBody() != null) {
                    for (var param : method.getParameters()) {
                        var pr = range(parsed, param, text.length());
                        var ptr = range(parsed, param.getType(), text.length());
                        if (pr.isEmpty() || ptr.isEmpty() || !citedOverlapping(pr.orElseThrow(), targets)) continue;
                        if (isVarargsParameter(param, pr.orElseThrow(), ptr.orElseThrow(), text)) continue;
                        String typeText = text.substring(ptr.orElseThrow().start(), ptr.orElseThrow().end()).trim();
                        if (typeText.startsWith("Optional<") || typeText.startsWith("java.util.Optional<")
                                || Set.of("boolean", "byte", "short", "char", "int", "long", "float", "double").contains(typeText)) continue;
                        String paramName = param.getName().toString();
                        var patches = new ArrayList<TextPatch>();
                        if (!hasOptionalImport && !addImportPatch(unit, parsed, text, "java.util.Optional", nl, patches)) continue;
                        patches.add(new TextPatch(ptr.orElseThrow().start(), ptr.orElseThrow().end(), "Optional<" + typeText + ">"));
                        if (!rewriteOptionalCtorParamUsages(method.getBody(), parsed, text, paramName, patches)) continue;
                        if (!hasOverlappingTextPatches(patches)) {
                            out.add(applyPatches(text, patches));
                        }
                    }
                }
            }
        }
    }

    private static boolean isVarargsParameter(VariableTree param, Range pr, Range ptr, String text) {
        if (param.getType().getKind() != Tree.Kind.ARRAY_TYPE) return false;
        String paramDecl = stripCommentsAndLiterals(text.substring(ptr.start(), pr.end()));
        return paramDecl.contains("...");
    }

    private static boolean rewriteOptionalCtorParamUsages(BlockTree body, JavaRepairSyntax.Parsed parsed, String text,
                                                          String paramName, List<TextPatch> patches) {
        boolean[] unsupported = {false};
        new TreeScanner<Void, Void>() {
            private final Deque<Set<String>> scopes = new ArrayDeque<>();
            private boolean isShadowed() {
                for (var s : scopes) if (s.contains(paramName)) return true;
                return false;
            }
            @Override public Void visitBlock(BlockTree tree, Void v) {
                scopes.push(new HashSet<>());
                try { return super.visitBlock(tree, v); }
                finally { scopes.pop(); }
            }
            @Override public Void visitForLoop(ForLoopTree tree, Void v) {
                scopes.push(new HashSet<>());
                try { return super.visitForLoop(tree, v); }
                finally { scopes.pop(); }
            }
            @Override public Void visitEnhancedForLoop(EnhancedForLoopTree tree, Void v) {
                scopes.push(new HashSet<>());
                try { return super.visitEnhancedForLoop(tree, v); }
                finally { scopes.pop(); }
            }
            @Override public Void visitTry(TryTree tree, Void v) {
                scopes.push(new HashSet<>());
                try { return super.visitTry(tree, v); }
                finally { scopes.pop(); }
            }
            @Override public Void visitCatch(CatchTree tree, Void v) {
                scopes.push(new HashSet<>());
                try { return super.visitCatch(tree, v); }
                finally { scopes.pop(); }
            }
            @Override public Void visitLambdaExpression(LambdaExpressionTree tree, Void v) {
                var names = new HashSet<String>();
                for (var p : tree.getParameters()) names.add(p.getName().toString());
                scopes.push(names);
                try { return scan(tree.getBody(), v); }
                finally { scopes.pop(); }
            }
            @Override public Void visitClass(ClassTree tree, Void v) {
                var names = new HashSet<String>();
                for (var m : tree.getMembers()) {
                    if (m instanceof VariableTree f) names.add(f.getName().toString());
                }
                scopes.push(names);
                try { return super.visitClass(tree, v); }
                finally { scopes.pop(); }
            }
            @Override public Void visitMethod(MethodTree tree, Void v) {
                var names = new HashSet<String>();
                for (var p : tree.getParameters()) names.add(p.getName().toString());
                scopes.push(names);
                try { return scan(tree.getBody(), v); }
                finally { scopes.pop(); }
            }
            @Override public Void visitVariable(VariableTree tree, Void v) {
                scan(tree.getInitializer(), v);
                if (!scopes.isEmpty()) scopes.peek().add(tree.getName().toString());
                return null;
            }
            @Override public Void visitBindingPattern(BindingPatternTree tree, Void v) {
                if (!scopes.isEmpty()) scopes.peek().add(tree.getVariable().getName().toString());
                return null;
            }
            @Override public Void visitAssignment(AssignmentTree tree, Void v) {
                if (!isShadowed() && tree.getKind() == Tree.Kind.ASSIGNMENT
                        && tree.getExpression() instanceof IdentifierTree rhs && rhs.getName().contentEquals(paramName)
                        && isFieldTarget(tree.getVariable())) {
                    var rhsRange = range(parsed, rhs, text.length());
                    if (rhsRange.isPresent()) {
                        patches.add(new TextPatch(rhsRange.orElseThrow().start(), rhsRange.orElseThrow().end(), paramName + ".orElse(null)"));
                    } else {
                        unsupported[0] = true;
                    }
                    return null;
                }
                return super.visitAssignment(tree, v);
            }
            private boolean isFieldTarget(ExpressionTree lhs) {
                if (lhs instanceof MemberSelectTree ms && ms.getExpression() instanceof IdentifierTree recv
                        && recv.getName().contentEquals("this")) return true;
                return lhs instanceof IdentifierTree id && !id.getName().contentEquals(paramName);
            }
            @Override public Void visitMethodInvocation(MethodInvocationTree tree, Void v) {
                if (!isShadowed() && isSingleArgRequireNonNull(tree, paramName)) {
                    var callRange = range(parsed, tree, text.length());
                    if (callRange.isPresent()) {
                        patches.add(new TextPatch(callRange.orElseThrow().start(), callRange.orElseThrow().end(), paramName + ".orElseThrow()"));
                    } else {
                        unsupported[0] = true;
                    }
                    return null;
                }
                if (tree.getMethodSelect() instanceof IdentifierTree) {
                    scan(tree.getTypeArguments(), v);
                    scan(tree.getArguments(), v);
                    return null;
                }
                return super.visitMethodInvocation(tree, v);
            }
            @Override public Void visitIdentifier(IdentifierTree tree, Void v) {
                if (!isShadowed() && tree.getName().contentEquals(paramName)) {
                    unsupported[0] = true;
                }
                return null;
            }
        }.scan(body, null);
        return !unsupported[0];
    }

    private static boolean isSingleArgRequireNonNull(MethodInvocationTree call, String paramName) {
        if (call.getArguments().size() != 1) return false;
        if (!(call.getArguments().getFirst() instanceof IdentifierTree arg) || !arg.getName().contentEquals(paramName)) return false;
        if (call.getMethodSelect() instanceof MemberSelectTree ms && ms.getIdentifier().contentEquals("requireNonNull")) {
            String recv = ms.getExpression().toString();
            return recv.equals("Objects") || recv.equals("java.util.Objects");
        }
        return call.getMethodSelect() instanceof IdentifierTree id && id.getName().contentEquals("requireNonNull");
    }

    private static void collectObjectProviderCycleBreaker(ClassTree owner, CompilationUnitTree unit, JavaRepairSyntax.Parsed parsed,
                                                          String text, List<Range> targets, Set<String> out) {
        String nl = text.contains("\r\n") ? "\r\n" : "\n";
        boolean hasProviderImport = unit.getImports().stream().anyMatch(imp -> {
            String q = imp.getQualifiedIdentifier().toString();
            return q.equals("org.springframework.beans.factory.ObjectProvider")
                    || q.equals("org.springframework.beans.factory.*");
        });
        var constructors = concreteConstructors(owner);
        var fields = new LinkedHashMap<String, VariableTree>();
        for (var m : owner.getMembers()) {
            if (m instanceof VariableTree f && f.getModifiers().getFlags().contains(Modifier.PRIVATE)) {
                fields.put(f.getName().toString(), f);
            }
        }
        // Field-injected @Autowired / @Inject or ctor-injected private final field
        for (var member : owner.getMembers()) {
            if (member instanceof VariableTree field && field.getModifiers().getFlags().contains(Modifier.PRIVATE)) {
                boolean diField = !field.getModifiers().getAnnotations().isEmpty()
                        && field.getModifiers().getAnnotations().stream().anyMatch(a -> DI_ANNOTATION_NAMES.contains(a.getAnnotationType().toString()));
                var fr = range(parsed, field, text.length());
                var ftr = range(parsed, field.getType(), text.length());
                if (fr.isEmpty() || ftr.isEmpty()) continue;
                String typeText = text.substring(ftr.orElseThrow().start(), ftr.orElseThrow().end()).trim();
                if (typeText.startsWith("ObjectProvider<") || typeText.contains("ObjectProvider<")
                        || Set.of("boolean", "byte", "short", "char", "int", "long", "float", "double").contains(typeText)) continue;
                String fieldName = field.getName().toString();

                VariableTree ctorParam = null;
                StatementTree ctorAssign = null;
                boolean matchedByCtor = false;
                if (constructors.size() == 1) {
                    var ctor = constructors.getFirst();
                    for (var param : ctor.getParameters()) {
                        for (var stmt : ctor.getBody().getStatements()) {
                            var match = assignedField(stmt, param.getName().toString());
                            if (match != null && match.fieldName().equals(fieldName)) {
                                ctorParam = param;
                                ctorAssign = stmt;
                                var pr = range(parsed, param, text.length());
                                matchedByCtor = citedOverlapping(fr.orElseThrow(), targets)
                                        || (pr.isPresent() && citedOverlapping(pr.orElseThrow(), targets));
                                break;
                            }
                        }
                        if (ctorParam != null) break;
                    }
                }
                if (!(diField && citedOverlapping(fr.orElseThrow(), targets)) && !matchedByCtor) continue;
                var patches = new ArrayList<TextPatch>();
                if (!hasProviderImport && !addImportPatch(unit, parsed, text, "org.springframework.beans.factory.ObjectProvider", nl, patches)) {
                    continue;
                }
                patches.add(new TextPatch(ftr.orElseThrow().start(), ftr.orElseThrow().end(), "ObjectProvider<" + typeText + ">"));
                if (ctorParam != null) {
                    var ptr = range(parsed, ctorParam.getType(), text.length());
                    if (ptr.isEmpty()) continue;
                    patches.add(new TextPatch(ptr.orElseThrow().start(), ptr.orElseThrow().end(), "ObjectProvider<" + typeText + ">"));
                }
                var excludedRanges = new ArrayList<Range>();
                excludedRanges.add(fr.orElseThrow());
                if (ctorParam != null) range(parsed, ctorParam, text.length()).ifPresent(excludedRanges::add);
                if (ctorAssign != null) range(parsed, ctorAssign, text.length()).ifPresent(excludedRanges::add);
                rewriteFieldUsagesToGetObject(owner, parsed, text, fieldName, excludedRanges, patches);
                if (!hasOverlappingTextPatches(patches)) {
                    out.add(applyPatches(text, patches));
                }
            }
        }
    }

    private static void rewriteFieldUsagesToGetObject(ClassTree owner, JavaRepairSyntax.Parsed parsed, String text,
                                                      String fieldName, List<Range> excluded, List<TextPatch> patches) {
        String ownerSimpleName = owner.getSimpleName().toString();
        new TreeScanner<Void, Void>() {
            private final Deque<Set<String>> scopes = new ArrayDeque<>();
            private int classDepth = -1;

            private boolean isExcluded(Tree t) {
                return range(parsed, t, text.length())
                        .filter(r -> excluded.stream().anyMatch(ex -> ex.start() <= r.start() && r.end() <= ex.end()))
                        .isPresent();
            }

            private boolean isShadowed() {
                for (var s : scopes) if (s.contains(fieldName)) return true;
                return false;
            }

            private boolean isOwnerThisField(Tree t) {
                if (!(t instanceof MemberSelectTree ms) || !ms.getIdentifier().contentEquals(fieldName)) return false;
                if (ms.getExpression() instanceof IdentifierTree recv && recv.getName().contentEquals("this")) {
                    return classDepth == 0;
                }
                if (ms.getExpression() instanceof MemberSelectTree qualifiedThis
                        && qualifiedThis.getIdentifier().contentEquals("this")
                        && qualifiedThis.getExpression() instanceof IdentifierTree cls
                        && cls.getName().contentEquals(ownerSimpleName)) {
                    return true;
                }
                return false;
            }

            private boolean isDirectFieldLhs(ExpressionTree lhs) {
                if (lhs instanceof IdentifierTree id && id.getName().contentEquals(fieldName) && !isShadowed()) return true;
                return isOwnerThisField(lhs);
            }

            @Override public Void scan(Tree t, Void unused) {
                if (t == null || isExcluded(t)) return null;
                return super.scan(t, unused);
            }

            @Override public Void visitClass(ClassTree tree, Void unused) {
                if (tree == owner) {
                    classDepth = 0;
                    try {
                        for (var m : tree.getMembers()) scan(m, unused);
                        return null;
                    } finally {
                        classDepth = -1;
                    }
                }
                classDepth++;
                var innerFields = new HashSet<String>();
                for (var m : tree.getMembers()) {
                    if (m instanceof VariableTree f) innerFields.add(f.getName().toString());
                }
                scopes.push(innerFields);
                try {
                    for (var m : tree.getMembers()) scan(m, unused);
                    return null;
                } finally {
                    scopes.pop();
                    classDepth--;
                }
            }

            @Override public Void visitMethod(MethodTree tree, Void unused) {
                var paramNames = new HashSet<String>();
                for (var p : tree.getParameters()) paramNames.add(p.getName().toString());
                scopes.push(paramNames);
                try {
                    return scan(tree.getBody(), unused);
                } finally {
                    scopes.pop();
                }
            }

            @Override public Void visitBlock(BlockTree tree, Void unused) {
                scopes.push(new HashSet<>());
                try { return super.visitBlock(tree, unused); }
                finally { scopes.pop(); }
            }

            @Override public Void visitForLoop(ForLoopTree tree, Void unused) {
                scopes.push(new HashSet<>());
                try { return super.visitForLoop(tree, unused); }
                finally { scopes.pop(); }
            }

            @Override public Void visitEnhancedForLoop(EnhancedForLoopTree tree, Void unused) {
                scopes.push(new HashSet<>());
                try { return super.visitEnhancedForLoop(tree, unused); }
                finally { scopes.pop(); }
            }

            @Override public Void visitTry(TryTree tree, Void unused) {
                scopes.push(new HashSet<>());
                try { return super.visitTry(tree, unused); }
                finally { scopes.pop(); }
            }

            @Override public Void visitCatch(CatchTree tree, Void unused) {
                scopes.push(new HashSet<>());
                try { return super.visitCatch(tree, unused); }
                finally { scopes.pop(); }
            }

            @Override public Void visitSwitch(SwitchTree tree, Void unused) {
                scopes.push(new HashSet<>());
                try { return super.visitSwitch(tree, unused); }
                finally { scopes.pop(); }
            }

            @Override public Void visitCase(CaseTree tree, Void unused) {
                scopes.push(new HashSet<>());
                try { return super.visitCase(tree, unused); }
                finally { scopes.pop(); }
            }

            @Override public Void visitLambdaExpression(LambdaExpressionTree tree, Void unused) {
                var paramNames = new HashSet<String>();
                for (var p : tree.getParameters()) paramNames.add(p.getName().toString());
                scopes.push(paramNames);
                try { return scan(tree.getBody(), unused); }
                finally { scopes.pop(); }
            }

            @Override public Void visitVariable(VariableTree tree, Void unused) {
                scan(tree.getInitializer(), unused);
                if (!scopes.isEmpty()) scopes.peek().add(tree.getName().toString());
                return null;
            }

            @Override public Void visitBindingPattern(BindingPatternTree tree, Void unused) {
                if (!scopes.isEmpty()) scopes.peek().add(tree.getVariable().getName().toString());
                return null;
            }

            @Override public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
                if (tree.getMethodSelect() instanceof IdentifierTree) {
                    scan(tree.getTypeArguments(), unused);
                    scan(tree.getArguments(), unused);
                    return null;
                }
                return super.visitMethodInvocation(tree, unused);
            }

            @Override public Void visitAssignment(AssignmentTree tree, Void unused) {
                if (isDirectFieldLhs(tree.getVariable())) {
                    return scan(tree.getExpression(), unused);
                }
                return super.visitAssignment(tree, unused);
            }

            @Override public Void visitCompoundAssignment(CompoundAssignmentTree tree, Void unused) {
                if (isDirectFieldLhs(tree.getVariable())) {
                    return scan(tree.getExpression(), unused);
                }
                return super.visitCompoundAssignment(tree, unused);
            }

            @Override public Void visitUnary(UnaryTree tree, Void unused) {
                var k = tree.getKind();
                if ((k == Tree.Kind.PREFIX_INCREMENT || k == Tree.Kind.PREFIX_DECREMENT
                        || k == Tree.Kind.POSTFIX_INCREMENT || k == Tree.Kind.POSTFIX_DECREMENT)
                        && isDirectFieldLhs(tree.getExpression())) {
                    return null;
                }
                return super.visitUnary(tree, unused);
            }

            @Override public Void visitMemberSelect(MemberSelectTree tree, Void unused) {
                if (isExcluded(tree)) return null;
                if (tree.getIdentifier().contentEquals("getObject")) {
                    ExpressionTree expr = tree.getExpression();
                    if ((expr instanceof IdentifierTree id && id.getName().contentEquals(fieldName) && !isShadowed())
                            || isOwnerThisField(expr)) {
                        return null;
                    }
                }
                if (isOwnerThisField(tree)) {
                    range(parsed, tree, text.length()).ifPresent(r ->
                            patches.add(new TextPatch(r.start(), r.end(), text.substring(r.start(), r.end()) + ".getObject()")));
                    return null;
                }
                return super.visitMemberSelect(tree, unused);
            }

            @Override public Void visitIdentifier(IdentifierTree tree, Void unused) {
                if (!isExcluded(tree) && tree.getName().contentEquals(fieldName) && !isShadowed()) {
                    range(parsed, tree, text.length()).ifPresent(r ->
                            patches.add(new TextPatch(r.start(), r.end(), fieldName + ".getObject()")));
                }
                return null;
            }
        }.scan(owner, null);
    }

    private static void collectReplaceNewWithConstructorInjection(ClassTree owner, CompilationUnitTree unit, JavaRepairSyntax.Parsed parsed,
                                                                  String text, List<Range> targets, Set<String> out) {
        if (hasLombokConstructor(owner)) return;
        var constructors = concreteConstructors(owner);
        if (constructors.size() > 1) return;
        String nl = text.contains("\r\n") ? "\r\n" : "\n";
        for (var member : owner.getMembers()) {
            if (!(member instanceof VariableTree field)
                    || field.getModifiers().getFlags().contains(Modifier.STATIC)
                    || !field.getModifiers().getFlags().contains(Modifier.PRIVATE)
                    || !field.getModifiers().getAnnotations().isEmpty()
                    || !(field.getInitializer() instanceof NewClassTree nc)
                    || !nc.getArguments().isEmpty() || nc.getClassBody() != null) continue;
            var optFr = range(parsed, field, text.length());
            var optTr = range(parsed, field.getType(), text.length());
            if (optFr.isEmpty() || optTr.isEmpty()) continue;
            var fr = optFr.orElseThrow();
            if (text.charAt(fr.end() - 1) != ';' || !removableText(text, fr) || !citedOverlapping(fr, targets)) continue;
            if (hasOverlappingSiblingField(owner, field, fr, parsed, text.length())) continue;
            String typeText = text.substring(optTr.orElseThrow().start(), optTr.orElseThrow().end()).trim();
            if (typeText.equals("var")) continue;
            if (isExcludedInstantiationType(field.getType(), nc, unit)) continue;
            String fieldName = field.getName().toString();
            String newField = "private final " + typeText + " " + fieldName + ";";
            var patches = new ArrayList<TextPatch>();
            if (constructors.isEmpty()) {
                String indent = lineIndentAt(text, fr.start());
                String ctorBlock = nl + nl + indent + owner.getSimpleName() + "(" + typeText + " " + fieldName + ") {"
                        + nl + indent + "    this." + fieldName + " = " + fieldName + ";"
                        + nl + indent + "}";
                patches.add(new TextPatch(fr.start(), fr.end(), newField + ctorBlock));
            } else {
                var ctor = constructors.getFirst();
                if (!appendCtorInjections(ctor, parsed, text, nl, List.of(new InjectedMember(field, fr, typeText, fieldName, "")), patches)) continue;
                patches.add(new TextPatch(fr.start(), fr.end(), newField));
            }
            if (!hasOverlappingTextPatches(patches)) {
                out.add(applyPatches(text, patches));
            }
        }
    }

    private static boolean isExcludedInstantiationType(Tree fieldType, NewClassTree nc, CompilationUnitTree unit) {
        return isExcludedRawType(rawTypeName(fieldType), unit) || isExcludedRawType(rawTypeName(nc.getIdentifier()), unit);
    }

    private static String rawTypeName(Tree typeTree) {
        if (typeTree == null) return "";
        String s = typeTree.toString().replaceAll("\\s+", "");
        int lt = s.indexOf('<');
        if (lt >= 0) s = s.substring(0, lt);
        while (s.endsWith("[]")) s = s.substring(0, s.length() - 2);
        return s;
    }

    private static boolean isExcludedRawType(String rawName, CompilationUnitTree unit) {
        if (rawName.isEmpty()) return true;
        if (rawName.startsWith("java.") || rawName.startsWith("javax.")) return true;
        int dot = rawName.lastIndexOf('.');
        String simple = dot >= 0 ? rawName.substring(dot + 1) : rawName;
        if (simple.startsWith("Atomic") || EXCLUDED_NEW_TYPES.contains(simple)) return true;
        for (var imp : unit.getImports()) {
            if (imp.isStatic()) continue;
            String q = imp.getQualifiedIdentifier().toString();
            if (q.endsWith("." + simple) && (q.startsWith("java.") || q.startsWith("javax."))) return true;
        }
        return false;
    }

    private static boolean hasLombokConstructor(ClassTree owner) {
        return owner.getModifiers().getAnnotations().stream().anyMatch(ann -> {
            String n = ann.getAnnotationType().toString();
            return n.endsWith("RequiredArgsConstructor") || n.endsWith("AllArgsConstructor") || n.endsWith("NoArgsConstructor");
        });
    }

    private static List<MethodTree> concreteConstructors(ClassTree owner) {
        var constructors = new ArrayList<MethodTree>();
        for (var member : owner.getMembers()) {
            if (member instanceof MethodTree m && m.getReturnType() == null && m.getReceiverParameter() == null && m.getBody() != null) {
                constructors.add(m);
            }
        }
        return constructors;
    }

    private static Map<String, VariableTree> privateUnannotatedOrDiFields(ClassTree owner) {
        var fields = new LinkedHashMap<String, VariableTree>();
        for (var member : owner.getMembers()) {
            if (member instanceof VariableTree f && f.getModifiers().getFlags().contains(Modifier.PRIVATE)
                    && !f.getModifiers().getFlags().contains(Modifier.STATIC)
                    && f.getModifiers().getAnnotations().stream().allMatch(a -> DI_ANNOTATION_NAMES.contains(a.getAnnotationType().toString()))
                    && !SERIALIZATION_FIELDS.contains(f.getName().toString())) {
                fields.put(f.getName().toString(), f);
            }
        }
        return fields;
    }

    private static String extractQualifier(List<? extends AnnotationTree> annotations, JavaRepairSyntax.Parsed parsed, String text) {
        for (var ann : annotations) {
            if (ann.getAnnotationType().toString().endsWith("Qualifier")) {
                var qRange = range(parsed, ann, text.length());
                if (qRange.isPresent()) {
                    return text.substring(qRange.orElseThrow().start(), qRange.orElseThrow().end()) + " ";
                }
            }
        }
        return null;
    }

    private static boolean hasOverlappingSiblingField(ClassTree owner, VariableTree field, Range fr, JavaRepairSyntax.Parsed parsed, int len) {
        return owner.getMembers().stream().anyMatch(other -> other != field && other instanceof VariableTree
                && range(parsed, other, len).filter(or -> overlaps(fr, or)).isPresent());
    }

    private static boolean isDelegatingConstructor(MethodTree ctor) {
        if (ctor.getBody() == null || ctor.getBody().getStatements().isEmpty()) return false;
        StatementTree first = ctor.getBody().getStatements().getFirst();
        return first instanceof ExpressionStatementTree es
                && es.getExpression() instanceof MethodInvocationTree call
                && call.getMethodSelect() instanceof IdentifierTree id
                && id.getName().contentEquals("this");
    }

    private static boolean assignsFieldInConstructor(MethodTree ctor, String fieldName) {
        if (ctor.getBody() == null) return false;
        boolean[] assigned = {false};
        var paramNames = new HashSet<String>();
        for (var p : ctor.getParameters()) paramNames.add(p.getName().toString());
        new TreeScanner<Void, Void>() {
            private final Deque<Set<String>> scopes = new ArrayDeque<>(List.of(paramNames));
            private boolean isShadowed() {
                for (var s : scopes) if (s.contains(fieldName)) return true;
                return false;
            }
            @Override public Void visitClass(ClassTree tree, Void v) { return null; }
            @Override public Void visitLambdaExpression(LambdaExpressionTree tree, Void v) { return null; }
            @Override public Void visitBlock(BlockTree tree, Void v) {
                scopes.push(new HashSet<>());
                try { return super.visitBlock(tree, v); }
                finally { scopes.pop(); }
            }
            @Override public Void visitVariable(VariableTree tree, Void v) {
                scan(tree.getInitializer(), v);
                if (!scopes.isEmpty()) scopes.peek().add(tree.getName().toString());
                return null;
            }
            @Override public Void visitAssignment(AssignmentTree tree, Void v) {
                if (tree.getVariable() instanceof MemberSelectTree ms
                        && ms.getExpression() instanceof IdentifierTree recv && recv.getName().contentEquals("this")
                        && ms.getIdentifier().contentEquals(fieldName)) {
                    assigned[0] = true;
                } else if (tree.getVariable() instanceof IdentifierTree id
                        && id.getName().contentEquals(fieldName) && !isShadowed()) {
                    assigned[0] = true;
                }
                return super.visitAssignment(tree, v);
            }
        }.scan(ctor.getBody(), null);
        return assigned[0];
    }

    private static Optional<Range> findCtorParamParens(MethodTree ctor, JavaRepairSyntax.Parsed parsed, String text,
                                                       int ctorStart, int bodyStart) {
        int searchStart = ctorStart;
        var modRange = range(parsed, ctor.getModifiers(), text.length());
        if (modRange.isPresent() && modRange.orElseThrow().end() > searchStart) {
            searchStart = modRange.orElseThrow().end();
        }
        for (var ann : ctor.getModifiers().getAnnotations()) {
            var ar = range(parsed, ann, text.length());
            if (ar.isPresent() && ar.orElseThrow().end() > searchStart) {
                searchStart = ar.orElseThrow().end();
            }
        }
        for (var tp : ctor.getTypeParameters()) {
            var tpr = range(parsed, tp, text.length());
            if (tpr.isPresent() && tpr.orElseThrow().end() > searchStart) {
                searchStart = tpr.orElseThrow().end();
            }
        }
        String clean = stripCommentsAndLiterals(text);
        int openParen = -1;
        int limit = Math.min(bodyStart, clean.length());
        for (int i = searchStart; i < limit; i++) {
            if (clean.charAt(i) == '(') {
                openParen = i;
                break;
            }
        }
        if (openParen < 0) return Optional.empty();
        int depth = 0;
        for (int i = openParen; i < limit; i++) {
            char c = clean.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return Optional.of(new Range(openParen, i));
            }
        }
        return Optional.empty();
    }

    private static boolean appendCtorInjections(MethodTree ctor, JavaRepairSyntax.Parsed parsed, String text, String nl,
                                                List<InjectedMember> members, List<TextPatch> patches) {
        if (isDelegatingConstructor(ctor)) return false;
        var ctorRange = range(parsed, ctor, text.length());
        var bodyRange = range(parsed, ctor.getBody(), text.length());
        if (ctorRange.isEmpty() || bodyRange.isEmpty()) return false;
        for (var m : members) {
            if (ctor.getParameters().stream().anyMatch(p -> p.getName().contentEquals(m.fieldName()))) return false;
            if (assignsFieldInConstructor(ctor, m.fieldName())) return false;
        }
        String joinedParams = members.stream()
                .map(m -> m.qualifier() + m.typeText() + " " + m.fieldName())
                .collect(java.util.stream.Collectors.joining(", "));
        if (ctor.getParameters().isEmpty()) {
            var parens = findCtorParamParens(ctor, parsed, text, ctorRange.orElseThrow().start(), bodyRange.orElseThrow().start());
            if (parens.isEmpty()) return false;
            patches.add(new TextPatch(parens.orElseThrow().start() + 1, parens.orElseThrow().end(), joinedParams));
        } else {
            var lastParam = ctor.getParameters().getLast();
            var lpRange = range(parsed, lastParam, text.length());
            if (lpRange.isEmpty()) return false;
            patches.add(new TextPatch(lpRange.orElseThrow().end(), lpRange.orElseThrow().end(), ", " + joinedParams));
        }
        int closeBrace = bodyRange.orElseThrow().end() - 1;
        if (closeBrace <= bodyRange.orElseThrow().start() || text.charAt(closeBrace) != '}') return false;
        String ctorIndent = lineIndentAt(text, ctorRange.orElseThrow().start());
        var stmts = new StringBuilder();
        for (var m : members) {
            stmts.append(ctorIndent).append("    this.").append(m.fieldName()).append(" = ").append(m.fieldName()).append(";").append(nl);
        }
        int insertPos = closeBrace;
        while (insertPos > bodyRange.orElseThrow().start() + 1 && (text.charAt(insertPos - 1) == ' ' || text.charAt(insertPos - 1) == '\t')) {
            insertPos--;
        }
        if (text.charAt(insertPos - 1) == '\n' || text.charAt(insertPos - 1) == '\r') {
            patches.add(new TextPatch(insertPos, closeBrace, stmts + ctorIndent));
        } else {
            patches.add(new TextPatch(insertPos, closeBrace, nl + stmts + ctorIndent));
        }
        return true;
    }

    private static boolean hasOverlappingTextPatches(List<TextPatch> patches) {
        var sorted = new ArrayList<>(patches);
        sorted.sort(Comparator.comparingInt(TextPatch::start).thenComparingInt(TextPatch::end));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).start() < sorted.get(i - 1).end()) return true;
        }
        return false;
    }

    private static boolean addImportPatch(CompilationUnitTree unit, JavaRepairSyntax.Parsed parsed, String text,
                                          String fqn, String nl, List<TextPatch> patches) {
        String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
        for (var imp : unit.getImports()) {
            if (imp.isStatic()) continue;
            String q = imp.getQualifiedIdentifier().toString();
            if (q.equals(fqn)) return true;
            if (!q.endsWith(".*") && q.endsWith("." + simple)) return false;
        }
        for (var decl : unit.getTypeDecls()) {
            if (decl instanceof ClassTree cls && cls.getSimpleName().contentEquals(simple)) return false;
        }
        int pos = 0;
        if (!unit.getImports().isEmpty()) {
            var last = range(parsed, unit.getImports().getLast(), text.length());
            if (last.isPresent()) {
                pos = last.orElseThrow().end();
                patches.add(new TextPatch(pos, pos, nl + "import " + fqn + ";"));
                return true;
            }
        }
        if (unit.getPackage() != null) {
            var pkg = range(parsed, unit.getPackage(), text.length());
            if (pkg.isPresent()) {
                pos = pkg.orElseThrow().end();
                patches.add(new TextPatch(pos, pos, nl + nl + "import " + fqn + ";"));
                return true;
            }
        }
        patches.add(new TextPatch(0, 0, "import " + fqn + ";" + nl));
        return true;
    }

    private static String lineIndentAt(String text, int offset) {
        int start = offset;
        while (start > 0 && text.charAt(start - 1) != '\n' && text.charAt(start - 1) != '\r') start--;
        int end = start;
        while (end < text.length() && (text.charAt(end) == ' ' || text.charAt(end) == '\t')) end++;
        return text.substring(start, end);
    }

    private static String pruneUnusedDiImports(String source) {
        String updated = source;
        for (String lineImport : List.of(
                "org.springframework.beans.factory.annotation.Autowired",
                "jakarta.inject.Inject",
                "javax.inject.Inject")) {
            String simple = lineImport.substring(lineImport.lastIndexOf('.') + 1);
            String stmt = "import " + lineImport + ";";
            String stripped = stripCommentsAndLiterals(updated);
            int idx = stripped.indexOf(stmt);
            if (idx < 0) continue;
            String without = stripped.substring(0, idx) + " ".repeat(stmt.length()) + stripped.substring(idx + stmt.length());
            if (!mentionsStripped(without, simple)) {
                int lineStart = idx;
                while (lineStart > 0 && (updated.charAt(lineStart - 1) == ' ' || updated.charAt(lineStart - 1) == '\t')) lineStart--;
                int lineEnd = idx + stmt.length();
                if (lineEnd < updated.length() && updated.charAt(lineEnd) == '\r') lineEnd++;
                if (lineEnd < updated.length() && updated.charAt(lineEnd) == '\n') lineEnd++;
                updated = updated.substring(0, lineStart) + updated.substring(lineEnd);
            }
        }
        return updated;
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
        return mentionsStripped(stripCommentsAndLiterals(source), identifier);
    }
    private static boolean mentionsStripped(String strippedSource,String identifier) {
        return Pattern.compile("(?<!\\p{javaJavaIdentifierPart})"+Pattern.quote(identifier)+"(?!\\p{javaJavaIdentifierPart})")
                .matcher(strippedSource).find();
    }
    /** Length-preserving Java lexer that replaces comment and string/char literal contents with spaces. */
    static String stripCommentsAndLiterals(String source) {
        int n = source.length();
        var out = new StringBuilder(n);
        int i = 0;
        while (i < n) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                out.append("  ");
                i += 2;
                while (i < n && source.charAt(i) != '\n' && source.charAt(i) != '\r') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                out.append("  ");
                i += 2;
                while (i < n) {
                    char ch = source.charAt(i);
                    if (ch == '*' && i + 1 < n && source.charAt(i + 1) == '/') {
                        out.append("  ");
                        i += 2;
                        break;
                    }
                    out.append((ch == '\n' || ch == '\r') ? ch : ' ');
                    i++;
                }
            } else if (c == '"' && i + 2 < n && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"') {
                out.append("   ");
                i += 3;
                while (i < n) {
                    char ch = source.charAt(i);
                    if (ch == '\\' && i + 1 < n) {
                        out.append(' ');
                        char esc = source.charAt(i + 1);
                        out.append((esc == '\n' || esc == '\r') ? esc : ' ');
                        i += 2;
                        continue;
                    }
                    if (ch == '"' && i + 2 < n && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"') {
                        out.append("   ");
                        i += 3;
                        break;
                    }
                    out.append((ch == '\n' || ch == '\r') ? ch : ' ');
                    i++;
                }
            } else if (c == '"' || c == '\'') {
                char quote = c;
                out.append(' ');
                i++;
                while (i < n) {
                    char ch = source.charAt(i);
                    if (ch == '\\' && i + 1 < n) {
                        out.append(' ');
                        char esc = source.charAt(i + 1);
                        out.append((esc == '\n' || esc == '\r') ? esc : ' ');
                        i += 2;
                        continue;
                    }
                    if (ch == quote) {
                        out.append(' ');
                        i++;
                        break;
                    }
                    if (ch == '\n' || ch == '\r') {
                        out.append(ch);
                        i++;
                        break;
                    }
                    out.append(' ');
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
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
