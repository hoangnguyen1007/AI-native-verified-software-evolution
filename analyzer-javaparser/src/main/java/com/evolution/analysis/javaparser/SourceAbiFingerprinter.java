package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.common.ContentDigest;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.TypeParameter;
import java.util.*;

/**
 * Deterministic, body-free, line-number-free Java source ABI fingerprinter producing:
 * <ul>
 *   <li>{@code externalAbiDigest} — intra-module early-cutoff key (visible to any file in the same module)</li>
 *   <li>{@code publicCrossModuleAbiDigest} — cross-module reactor early-cutoff key (public/protected surface)</li>
 *   <li>{@code springRelevantDigest} — Spring bean/wiring/annotation surface (body-free)</li>
 *   <li>{@code staticMembersDigest} — static member surface for targeted {@code import static T.*} invalidation</li>
 * </ul>
 * Strictly excludes executable method/constructor/initializer/lambda bodies, comments, whitespace, and source spans.
 */
public final class SourceAbiFingerprinter {
    private static final Set<String> LOMBOK_ANNOTATIONS = Set.of(
            "Data", "Value", "Builder", "SuperBuilder", "RequiredArgsConstructor",
            "AllArgsConstructor", "NoArgsConstructor", "UtilityClass", "Getter", "Setter",
            "EqualsAndHashCode", "ToString", "With", "WithBy", "Delegate", "FieldDefaults",
            "Accessors", "StandardException", "Slf4j", "XSlf4j", "Log4j2", "Log4j", "Log", "CommonsLog",
            "JBossLog", "Flogger", "CustomLog", "NonNull", "NonFinal", "Singular", "Tolerate", "Default");

    private static final Set<String> LOMBOK_CLASS_ANNOTATIONS = Set.of(
            "Data", "Value", "Builder", "SuperBuilder", "RequiredArgsConstructor",
            "AllArgsConstructor", "NoArgsConstructor", "UtilityClass", "Getter", "Setter",
            "EqualsAndHashCode", "ToString", "With", "Delegate", "FieldDefaults", "Accessors",
            "StandardException");

    public record AbiSummary(
            ContentDigest externalAbiDigest,
            ContentDigest publicCrossModuleAbiDigest,
            ContentDigest springRelevantDigest,
            ContentDigest staticMembersDigest,
            String packageName,
            Set<String> declaredFqns,
            Set<String> declaredSimpleNames,
            Set<String> supertypeNames,
            Set<String> explicitImportedFqns,
            Set<String> wildcardImportedPackages,
            Set<String> staticWildcardTypes,
            boolean hasLombokClassAnnotation,
            boolean hasLombokAnyAnnotation,
            boolean isMetaFile) {}

    private SourceAbiFingerprinter() {}

    public static AbiSummary fingerprint(CompilationUnit ast) {
        return fingerprint(ast, "");
    }

    public static AbiSummary fingerprint(CompilationUnit ast, String path) {
        String pkg = ast.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        boolean metaFile = isMetaFilePath(path) || ast.getModule().isPresent();

        var external = new StringBuilder(1024);
        var crossMod = new StringBuilder(1024);
        var spring = new StringBuilder(1024);
        var statics = new StringBuilder(256);

        var declaredFqns = new TreeSet<String>();
        var declaredSimpleNames = new TreeSet<String>();
        var supertypeNames = new TreeSet<String>();
        var explicitImports = new TreeSet<String>();
        var wildcardPackages = new TreeSet<String>();
        var staticWildcardTypes = new TreeSet<String>();
        var simpleToImportedFqn = new HashMap<String, String>();
        boolean[] flags = {false, false}; // [0]=hasLombokClass, [1]=hasLombokAny

        external.append("abi:v3:pkg:").append(pkg).append('\n');
        crossMod.append("pub:v3:pkg:").append(pkg).append('\n');
        spring.append("spr:v3:pkg:").append(pkg).append('\n');
        statics.append("sta:v3:pkg:").append(pkg).append('\n');

        ast.getModule().ifPresent(mod -> {
            String s = normalizedText(mod);
            external.append("module:").append(s).append('\n');
            crossMod.append("module:").append(s).append('\n');
        });

        // Package annotations (package-info.java)
        ast.getPackageDeclaration().ifPresent(pd -> {
            if (!pd.getAnnotations().isEmpty()) {
                appendAnnotations(external, pd.getAnnotations(), flags, false);
                appendAnnotations(crossMod, pd.getAnnotations(), flags, false);
                appendAnnotations(spring, pd.getAnnotations(), flags, false);
                external.append('\n');
                crossMod.append('\n');
                spring.append('\n');
            }
        });

        for (ImportDeclaration imp : ast.getImports()) {
            String name = imp.getNameAsString();
            String line = (imp.isStatic() ? "imp:s:" : "imp:") + name + (imp.isAsterisk() ? ".*" : "") + '\n';
            external.append(line);
            crossMod.append(line);
            spring.append(line);
            if (imp.isAsterisk()) {
                if (imp.isStatic()) {
                    staticWildcardTypes.add(name);
                } else {
                    wildcardPackages.add(name);
                }
            } else {
                if (imp.isStatic()) {
                    int dot = name.lastIndexOf('.');
                    if (dot > 0) {
                        explicitImports.add(name.substring(0, dot));
                    }
                } else {
                    explicitImports.add(name);
                    int dot = name.lastIndexOf('.');
                    if (dot > 0) {
                        simpleToImportedFqn.put(name.substring(dot + 1), name);
                    }
                }
            }
        }

        // Walk ONLY non-local types (top-level types and recursively their member types).
        // Local and anonymous classes live inside executable bodies and are automatically excluded!
        for (TypeDeclaration<?> top : ast.getTypes()) {
            String fqn = pkg.isEmpty() ? top.getNameAsString() : pkg + "." + top.getNameAsString();
            writeType(top, fqn, pkg, false, true,
                    external, crossMod, spring, statics,
                    declaredFqns, declaredSimpleNames, supertypeNames, simpleToImportedFqn, flags);
        }

        return new AbiSummary(
                ContentDigest.sha256Utf8(external.toString()),
                ContentDigest.sha256Utf8(crossMod.toString()),
                ContentDigest.sha256Utf8(spring.toString()),
                ContentDigest.sha256Utf8(statics.toString()),
                pkg,
                Collections.unmodifiableSet(declaredFqns),
                Collections.unmodifiableSet(declaredSimpleNames),
                Collections.unmodifiableSet(supertypeNames),
                Collections.unmodifiableSet(explicitImports),
                Collections.unmodifiableSet(wildcardPackages),
                Collections.unmodifiableSet(staticWildcardTypes),
                flags[0],
                flags[1],
                metaFile);
    }

    private static void writeType(
            TypeDeclaration<?> type,
            String fqn,
            String pkg,
            boolean enclosingLombok,
            boolean enclosingCrossModuleVisible,
            StringBuilder external,
            StringBuilder crossMod,
            StringBuilder spring,
            StringBuilder statics,
            Set<String> declaredFqns,
            Set<String> declaredSimpleNames,
            Set<String> supertypeNames,
            Map<String, String> simpleToImportedFqn,
            boolean[] flags) {
        String name = type.getNameAsString();
        declaredFqns.add(fqn);
        declaredSimpleNames.add(name);

        boolean typeHasLombok = enclosingLombok;
        for (AnnotationExpr ann : type.getAnnotations()) {
            String simple = ann.getName().getIdentifier();
            if (LOMBOK_CLASS_ANNOTATIONS.contains(simple)) {
                flags[0] = true;
                flags[1] = true;
                typeHasLombok = true;
            } else if (LOMBOK_ANNOTATIONS.contains(simple)) {
                flags[1] = true;
                typeHasLombok = true;
            }
        }

        boolean isInterface = type instanceof ClassOrInterfaceDeclaration ci && ci.isInterface();
        boolean isAnnotation = type instanceof AnnotationDeclaration;
        boolean implicitlyPublicMember = isInterface || isAnnotation;
        boolean crossModuleVisible = enclosingCrossModuleVisible
                && (type.isPublic() || type.isProtected()
                || (type.getParentNode().orElse(null) instanceof TypeDeclaration<?> p
                && (p instanceof AnnotationDeclaration || p instanceof ClassOrInterfaceDeclaration pci && pci.isInterface())));
        // JLS §6.6 / §8.4.8: A non-private, non-final package-private class or interface can be extended/implemented
        // by a public class in the same package, exporting its public/protected/Lombok members across modules!
        boolean canBeExtendedPublicly = !type.isPrivate()
                && !type.hasModifier(com.github.javaparser.ast.Modifier.Keyword.FINAL)
                && !(type instanceof EnumDeclaration) && !(type instanceof RecordDeclaration)
                && !(type instanceof AnnotationDeclaration);
        boolean crossModuleSurface = crossModuleVisible || (enclosingCrossModuleVisible && canBeExtendedPublicly);

        var header = new StringBuilder(192);
        header.append("T:").append(kindTag(type)).append(':').append(fqn).append(':');
        appendModifiers(header, type.getModifiers());
        header.append(':');
        appendAnnotations(header, type.getAnnotations(), flags, true);

        if (type instanceof ClassOrInterfaceDeclaration ci) {
            appendTypeParams(header, ci.getTypeParameters());
            appendTypeList(header, ":ext=", ci.getExtendedTypes());
            appendTypeList(header, ":impl=", ci.getImplementedTypes());
            appendTypeList(header, ":perm=", ci.getPermittedTypes());
            collectSupertypes(ci.getExtendedTypes(), pkg, simpleToImportedFqn, supertypeNames);
            collectSupertypes(ci.getImplementedTypes(), pkg, simpleToImportedFqn, supertypeNames);
            collectSupertypes(ci.getPermittedTypes(), pkg, simpleToImportedFqn, supertypeNames);
        } else if (type instanceof RecordDeclaration rec) {
            appendTypeParams(header, rec.getTypeParameters());
            header.append(":rec=(");
            for (int i = 0; i < rec.getParameters().size(); i++) {
                Parameter p = rec.getParameter(i);
                declaredSimpleNames.add(p.getNameAsString());
                if (i > 0) header.append(',');
                appendParameter(header, p);
            }
            header.append(')');
            appendTypeList(header, ":impl=", rec.getImplementedTypes());
            collectSupertypes(rec.getImplementedTypes(), pkg, simpleToImportedFqn, supertypeNames);
        } else if (type instanceof EnumDeclaration en) {
            appendTypeList(header, ":impl=", en.getImplementedTypes());
            collectSupertypes(en.getImplementedTypes(), pkg, simpleToImportedFqn, supertypeNames);
            header.append(":enums=[");
            for (int i = 0; i < en.getEntries().size(); i++) {
                EnumConstantDeclaration entry = en.getEntries().get(i);
                declaredSimpleNames.add(entry.getNameAsString());
                if (i > 0) header.append(',');
                header.append(entry.getNameAsString());
                appendAnnotations(header, entry.getAnnotations(), flags, false);
                if (!entry.getClassBody().isEmpty()) {
                    header.append("{");
                    for (BodyDeclaration<?> member : entry.getClassBody()) {
                        appendEnumConstantBodyMemberSignature(header, member, flags);
                    }
                    header.append("}");
                }
            }
            header.append(']');
        }

        // Implicit constructor synthesis depends on whether ANY explicit constructor exists (even private!)
        boolean hasExplicitCtor = !type.getConstructors().isEmpty()
                || (type instanceof RecordDeclaration rec && !rec.getCompactConstructors().isEmpty());
        header.append(":hasCtor=").append(hasExplicitCtor).append('\n');

        external.append(header);
        spring.append(header);
        if (crossModuleVisible) {
            crossMod.append(header);
        } else if (crossModuleSurface) {
            crossMod.append("INH:").append(header);
        }

        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof FieldDeclaration fd) {
                boolean isStatic = fd.isStatic() || implicitlyPublicMember;
                boolean isConst = (fd.isStatic() && fd.isFinal()) || implicitlyPublicMember;
                boolean hasAnns = !fd.getAnnotations().isEmpty();
                boolean fieldHasLombok = hasLombokAnnotation(fd.getAnnotations());
                if (fieldHasLombok) flags[1] = true;
                boolean includeExternal = !fd.isPrivate() || typeHasLombok || fieldHasLombok || hasAnns;
                boolean includeCrossMod = crossModuleSurface
                        && (fd.isPublic() || fd.isProtected() || implicitlyPublicMember || typeHasLombok || fieldHasLombok);
                boolean includeSpring = includeExternal || fd.isFinal();

                for (VariableDeclarator v : fd.getVariables()) {
                    if (includeExternal) declaredSimpleNames.add(v.getNameAsString());
                }
                if (!includeExternal && !includeSpring) {
                    continue;
                }
                var fb = new StringBuilder(96);
                fb.append("F:").append(fqn).append(':');
                appendModifiers(fb, fd.getModifiers());
                fb.append(':');
                appendAnnotations(fb, fd.getAnnotations(), flags, false);
                fb.append(':').append(normalizedType(fd.getElementType()));
                for (VariableDeclarator v : fd.getVariables()) {
                    fb.append('|').append(normalizedType(v.getType())).append(' ').append(v.getNameAsString());
                    if (isConst) {
                        v.getInitializer().ifPresent(init -> fb.append('=').append(normalizedExpr(init)));
                    } else if (typeHasLombok || fieldHasLombok) {
                        // For Lombok @RequiredArgsConstructor/@AllArgsConstructor/@Builder.Default, initializer presence affects synthesis!
                        fb.append(":hasInit=").append(v.getInitializer().isPresent());
                    }
                }
                fb.append('\n');
                if (includeExternal) external.append(fb);
                if (includeCrossMod) crossMod.append(fb);
                if (includeSpring) spring.append(fb);
                if (isStatic && includeExternal) statics.append(fb);

            } else if (member instanceof MethodDeclaration md) {
                boolean hasAnns = !md.getAnnotations().isEmpty();
                boolean includeExternal = !md.isPrivate() || hasAnns;
                boolean includeCrossMod = crossModuleSurface && (md.isPublic() || md.isProtected() || implicitlyPublicMember);
                if (!includeExternal) continue;
                declaredSimpleNames.add(md.getNameAsString());

                var mb = new StringBuilder(128);
                mb.append("M:").append(fqn).append(':');
                appendModifiers(mb, md.getModifiers());
                mb.append(':');
                appendAnnotations(mb, md.getAnnotations(), flags, false);
                appendTypeParams(mb, md.getTypeParameters());
                mb.append(':').append(normalizedType(md.getType())).append(' ').append(md.getNameAsString()).append('(');
                for (int i = 0; i < md.getParameters().size(); i++) {
                    if (i > 0) mb.append(',');
                    appendParameter(mb, md.getParameter(i));
                }
                mb.append(')');
                appendTypeList(mb, ":throws=", md.getThrownExceptions());
                mb.append('\n');
                external.append(mb);
                if (includeCrossMod) crossMod.append(mb);
                if (hasAnns || md.isPublic() || md.isProtected() || implicitlyPublicMember) {
                    spring.append(mb);
                }
                if (md.isStatic()) statics.append(mb);

            } else if (member instanceof ConstructorDeclaration cd) {
                boolean hasAnns = !cd.getAnnotations().isEmpty();
                boolean includeExternal = !cd.isPrivate() || hasAnns;
                boolean includeCrossMod = crossModuleVisible && (cd.isPublic() || cd.isProtected());
                var cb = new StringBuilder(96);
                cb.append("C:").append(fqn).append(':');
                appendModifiers(cb, cd.getModifiers());
                cb.append(':');
                appendAnnotations(cb, cd.getAnnotations(), flags, false);
                appendTypeParams(cb, cd.getTypeParameters());
                cb.append(':').append(cd.getNameAsString()).append('(');
                for (int i = 0; i < cd.getParameters().size(); i++) {
                    if (i > 0) cb.append(',');
                    appendParameter(cb, cd.getParameter(i));
                }
                cb.append(')');
                appendTypeList(cb, ":throws=", cd.getThrownExceptions());
                cb.append('\n');
                if (includeExternal) external.append(cb);
                if (includeCrossMod) crossMod.append(cb);
                // Spring constructor injection inspects all constructors of a bean (even single private constructors).
                spring.append(cb);

            } else if (member instanceof CompactConstructorDeclaration ccd) {
                boolean hasAnns = !ccd.getAnnotations().isEmpty();
                var cb = new StringBuilder(64);
                cb.append("CC:").append(fqn).append(':');
                appendModifiers(cb, ccd.getModifiers());
                cb.append(':');
                appendAnnotations(cb, ccd.getAnnotations(), flags, false);
                cb.append(':').append(ccd.getNameAsString()).append('\n');
                if (!ccd.isPrivate() || hasAnns) external.append(cb);
                if (crossModuleVisible && (ccd.isPublic() || ccd.isProtected())) crossMod.append(cb);
                spring.append(cb);

            } else if (member instanceof AnnotationMemberDeclaration amd) {
                declaredSimpleNames.add(amd.getNameAsString());
                var ab = new StringBuilder(96);
                ab.append("A:").append(fqn).append(':');
                appendModifiers(ab, amd.getModifiers());
                ab.append(':');
                appendAnnotations(ab, amd.getAnnotations(), flags, false);
                ab.append(':').append(normalizedType(amd.getType())).append(' ').append(amd.getNameAsString());
                amd.getDefaultValue().ifPresent(def -> ab.append('=').append(normalizedExpr(def)));
                ab.append('\n');
                external.append(ab);
                if (crossModuleVisible) crossMod.append(ab);
                spring.append(ab);

            } else if (member instanceof TypeDeclaration<?> nested) {
                String nestedFqn = fqn + "." + nested.getNameAsString();
                writeType(nested, nestedFqn, pkg, typeHasLombok, crossModuleSurface,
                        external, crossMod, spring, statics,
                        declaredFqns, declaredSimpleNames, supertypeNames, simpleToImportedFqn, flags);
            }
        }
    }

    private static boolean hasLombokAnnotation(NodeList<AnnotationExpr> anns) {
        for (AnnotationExpr ann : anns) {
            if (LOMBOK_ANNOTATIONS.contains(ann.getName().getIdentifier())) {
                return true;
            }
        }
        return false;
    }

    private static void appendEnumConstantBodyMemberSignature(
            StringBuilder sb, BodyDeclaration<?> member, boolean[] flags) {
        if (member instanceof MethodDeclaration md && !md.isPrivate()) {
            sb.append("m:");
            appendModifiers(sb, md.getModifiers());
            appendAnnotations(sb, md.getAnnotations(), flags, false);
            sb.append(normalizedType(md.getType())).append(' ').append(md.getNameAsString()).append('(');
            for (int i = 0; i < md.getParameters().size(); i++) {
                if (i > 0) sb.append(',');
                appendParameter(sb, md.getParameter(i));
            }
            sb.append(");");
        } else if (member instanceof FieldDeclaration fd && !fd.isPrivate()) {
            sb.append("f:");
            appendModifiers(sb, fd.getModifiers());
            sb.append(normalizedType(fd.getElementType()));
            for (var v : fd.getVariables()) {
                sb.append(' ').append(v.getNameAsString());
                if (fd.isStatic() && fd.isFinal()) {
                    v.getInitializer().ifPresent(init -> sb.append('=').append(normalizedExpr(init)));
                }
            }
            sb.append(';');
        }
    }

    private static void collectSupertypes(
            NodeList<ClassOrInterfaceType> types,
            String pkg,
            Map<String, String> simpleToImportedFqn,
            Set<String> supertypeNames) {
        for (ClassOrInterfaceType t : types) {
            String raw = t.getNameWithScope();
            String simple = t.getNameAsString();
            supertypeNames.add(raw);
            supertypeNames.add(simple);
            if (raw.contains(".")) {
                int firstDot = raw.indexOf('.');
                String head = raw.substring(0, firstDot);
                String importedHead = simpleToImportedFqn.get(head);
                if (importedHead != null) {
                    supertypeNames.add(importedHead + raw.substring(firstDot));
                }
            } else {
                String imported = simpleToImportedFqn.get(simple);
                if (imported != null) {
                    supertypeNames.add(imported);
                } else if (!pkg.isEmpty()) {
                    supertypeNames.add(pkg + "." + simple);
                }
            }
        }
    }

    private static String kindTag(TypeDeclaration<?> type) {
        if (type instanceof AnnotationDeclaration) return "ANN";
        if (type instanceof RecordDeclaration) return "REC";
        if (type instanceof EnumDeclaration) return "ENUM";
        if (type instanceof ClassOrInterfaceDeclaration ci && ci.isInterface()) return "IFACE";
        return "CLASS";
    }

    private static void appendModifiers(StringBuilder sb, NodeList<Modifier> modifiers) {
        if (modifiers.isEmpty()) return;
        var sorted = new ArrayList<String>(modifiers.size());
        for (Modifier m : modifiers) sorted.add(m.getKeyword().asString());
        Collections.sort(sorted);
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(sorted.get(i));
        }
    }

    private static void appendAnnotations(
            StringBuilder sb, NodeList<AnnotationExpr> anns, boolean[] flags, boolean isTypeLevel) {
        if (anns.isEmpty()) return;
        for (AnnotationExpr ann : anns) {
            String id = ann.getName().getIdentifier();
            if (LOMBOK_ANNOTATIONS.contains(id)) {
                flags[1] = true;
                if (isTypeLevel && LOMBOK_CLASS_ANNOTATIONS.contains(id)) {
                    flags[0] = true;
                }
            }
            sb.append('@').append(normalizedExpr(ann));
        }
    }

    private static void appendTypeParams(StringBuilder sb, NodeList<TypeParameter> params) {
        if (params.isEmpty()) return;
        sb.append('<');
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(',');
            TypeParameter tp = params.get(i);
            sb.append(tp.getNameAsString());
            if (!tp.getTypeBound().isEmpty()) {
                sb.append(" extends ");
                for (int j = 0; j < tp.getTypeBound().size(); j++) {
                    if (j > 0) sb.append('&');
                    sb.append(normalizedType(tp.getTypeBound().get(j)));
                }
            }
        }
        sb.append('>');
    }

    private static void appendTypeList(StringBuilder sb, String prefix, NodeList<? extends Type> types) {
        if (types.isEmpty()) return;
        sb.append(prefix);
        for (int i = 0; i < types.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(normalizedType(types.get(i)));
        }
    }

    private static void appendParameter(StringBuilder sb, Parameter p) {
        appendModifiers(sb, p.getModifiers());
        if (!p.getAnnotations().isEmpty()) {
            for (AnnotationExpr ann : p.getAnnotations()) {
                sb.append('@').append(normalizedExpr(ann));
            }
        }
        sb.append(normalizedType(p.getType()));
        if (p.isVarArgs()) sb.append("...");
        sb.append(' ').append(p.getNameAsString());
    }

    private static String normalizedType(Type type) {
        return normalizedText(type);
    }

    private static String normalizedExpr(Expression expr) {
        return normalizedText(expr);
    }

    /**
     * Produces a comment-free, whitespace-normalized canonical representation of a small AST subtree
     * (type, annotation, or constant initializer expression) without mutating the original node.
     */
    private static String normalizedText(Node node) {
        Node copy = node.clone();
        copy.removeComment();
        copy.getAllContainedComments().forEach(Node::remove);
        String raw = copy.toString();
        // Normalize CR/LF and runs of whitespace outside string/char literals so formatting edits never bust ABI.
        var out = new StringBuilder(raw.length());
        boolean inString = false;
        boolean inChar = false;
        boolean pendingSpace = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inString) {
                out.append(c);
                if (c == '\\' && i + 1 < raw.length()) {
                    out.append(raw.charAt(++i));
                } else if (c == '"') {
                    inString = false;
                }
            } else if (inChar) {
                out.append(c);
                if (c == '\\' && i + 1 < raw.length()) {
                    out.append(raw.charAt(++i));
                } else if (c == '\'') {
                    inChar = false;
                }
            } else if (c == '"') {
                if (pendingSpace && !out.isEmpty()) out.append(' ');
                pendingSpace = false;
                inString = true;
                out.append(c);
            } else if (c == '\'') {
                if (pendingSpace && !out.isEmpty()) out.append(' ');
                pendingSpace = false;
                inChar = true;
                out.append(c);
            } else if (Character.isWhitespace(c)) {
                pendingSpace = true;
            } else {
                if (pendingSpace && !out.isEmpty()) out.append(' ');
                pendingSpace = false;
                out.append(c);
            }
        }
        return out.toString();
    }

    static boolean isMetaFilePath(String path) {
        if (path == null || path.isEmpty()) return false;
        String p = path.replace('\\', '/');
        return p.equals("module-info.java") || p.endsWith("/module-info.java")
                || p.equals("package-info.java") || p.endsWith("/package-info.java")
                || p.equals("lombok.config") || p.endsWith("/lombok.config");
    }
}
