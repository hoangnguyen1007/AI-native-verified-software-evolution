package com.evolution.analysis.javaparser;

import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.ArrayCreationLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MarkerAnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.TypeParameter;
import com.github.javaparser.ast.type.VoidType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic Kotlin-to-JavaParser AST lowering engine for mixed Java/Kotlin and pure-Kotlin
 * repositories. Preserves 1-based source coordinates into the underlying {@link UnicodeSource}
 * so declarations, Spring annotations, constructors, synthesized JVM getters/setters, and call
 * sites participate natively in symbol resolution and architecture projection.
 */
final class KotlinAstLowering {
    private static final Set<String> MODIFIER_WORDS = Set.of(
            "public", "private", "protected", "internal", "open", "abstract", "final",
            "sealed", "data", "value", "inline", "inner", "enum", "annotation", "companion",
            "override", "suspend", "operator", "infix", "tailrec", "external", "const",
            "lateinit", "actual", "expect");

    private static final Set<String> CONTROL_KEYWORDS = Set.of(
            "if", "else", "for", "while", "do", "when", "try", "catch", "finally", "return",
            "throw", "break", "continue", "super", "this", "null", "true", "false", "is", "in",
            "as", "by", "where", "init", "constructor", "get", "set", "fun", "val", "var",
            "class", "interface", "object", "package", "import", "typealias", "typeof");

    private static final Pattern FILE_JVM_NAME = Pattern.compile(
            "@file\\s*:\\s*JvmName\\s*\\(\\s*\"([A-Za-z_][A-Za-z0-9_]*)\"\\s*\\)");
    private static final Pattern PACKAGE_DECL = Pattern.compile(
            "(?m)^\\s*package\\s+([A-Za-z_][A-Za-z0-9_.]*)");
    private static final Pattern IMPORT_DECL = Pattern.compile(
            "(?m)^\\s*import\\s+([A-Za-z_][A-Za-z0-9_.]*(?:\\.\\*)?)(?:\\s+as\\s+([A-Za-z_][A-Za-z0-9_]*))?");
    private static final Pattern CALL_SCAN = Pattern.compile(
            "(?:([A-Za-z_][A-Za-z0-9_]*(?:\\s*\\.\\s*[A-Za-z_][A-Za-z0-9_]*)+)|([A-Za-z_][A-Za-z0-9_]*))(?:\\s*<[^>{};()]*>)?\\s*\\(");
    private static final Pattern LOCAL_VAL_SCAN = Pattern.compile(
            "\\b(?:val|var)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?::\\s*([A-Za-z_][A-Za-z0-9_.<>?,\\s]*?))?\\s*=\\s*([^;\\n\\r]+)");

    private KotlinAstLowering() {}

    static CompilationUnit lower(String path, UnicodeSource translated, JavaSymbolSolver solver) {
        CompilationUnit cu = new CompilationUnit();
        cu.setData(Node.SYMBOL_RESOLVER_KEY, solver);
        String raw = translated.text;
        if (raw == null || raw.isBlank()) {
            return cu;
        }
        LineIndex index = new LineIndex(raw);
        String clean = maskCommentsAndStrings(raw);
        Range fileRange = index.range(0, raw.length() - 1);
        cu.setRange(fileRange);

        Map<String, String> importAliases = new HashMap<>();
        int declarationsStart = extractHeader(raw, clean, index, cu, importAliases);

        String fileFacadeName = deriveFileFacadeName(path, raw);
        ClassOrInterfaceDeclaration topLevelFacade = new ClassOrInterfaceDeclaration(
                new NodeList<>(Modifier.publicModifier(), Modifier.finalModifier()), false, fileFacadeName);
        topLevelFacade.setRange(fileRange);

        scanScope(raw, clean, declarationsStart, raw.length(), index, importAliases, cu, null, topLevelFacade);

        if (!topLevelFacade.getMembers().isEmpty()) {
            boolean nameTaken = cu.getTypes().stream()
                    .anyMatch(t -> t.getNameAsString().equals(fileFacadeName));
            if (!nameTaken) {
                Range firstMemberRange = topLevelFacade.getMembers().getFirst().flatMap(Node::getRange).orElse(fileRange);
                topLevelFacade.setRange(firstMemberRange);
                topLevelFacade.getName().setRange(firstMemberRange);
                cu.getTypes().add(topLevelFacade);
            }
        }

        ensureAllRanges(cu, fileRange);
        return cu;
    }

    private static int extractHeader(
            String raw,
            String clean,
            LineIndex index,
            CompilationUnit cu,
            Map<String, String> importAliases) {
        int lastHeaderEnd = 0;
        Matcher pkgMatcher = PACKAGE_DECL.matcher(clean);
        if (pkgMatcher.find()) {
            String pkgName = pkgMatcher.group(1);
            int s = pkgMatcher.start();
            int e = Math.max(s, pkgMatcher.end() - 1);
            Range r = index.range(s, e);
            PackageDeclaration pkg = new PackageDeclaration(parseNameWithRange(pkgName, r));
            pkg.setRange(r);
            cu.setPackageDeclaration(pkg);
            lastHeaderEnd = Math.max(lastHeaderEnd, pkgMatcher.end());
        }
        Matcher impMatcher = IMPORT_DECL.matcher(clean);
        while (impMatcher.find()) {
            String target = impMatcher.group(1);
            String alias = impMatcher.group(2);
            int s = impMatcher.start();
            int e = Math.max(s, impMatcher.end() - 1);
            Range r = index.range(s, e);
            boolean wildcard = target.endsWith(".*");
            String baseName = wildcard ? target.substring(0, target.length() - 2) : target;
            if (alias != null && !alias.isEmpty() && !wildcard) {
                importAliases.put(alias, baseName);
            }
            if (isValidQualifiedName(baseName)) {
                ImportDeclaration imp = new ImportDeclaration(parseNameWithRange(baseName, r), false, wildcard);
                imp.setRange(r);
                cu.addImport(imp);
            }
            lastHeaderEnd = Math.max(lastHeaderEnd, impMatcher.end());
        }
        return lastHeaderEnd;
    }

    private static void scanScope(
            String raw,
            String clean,
            int start,
            int end,
            LineIndex index,
            Map<String, String> importAliases,
            CompilationUnit cu,
            TypeDeclaration<?> enclosingType,
            ClassOrInterfaceDeclaration topLevelFacade) {
        int pos = start;
        while (pos < end) {
            pos = skipWhitespaceAndDelimiters(clean, pos, end);
            if (pos >= end) break;

            if (clean.startsWith("package ", pos) || clean.startsWith("import ", pos)
                    || clean.startsWith("typealias ", pos) || clean.startsWith("@file:", pos)) {
                pos = skipToEndOfLineOrStatement(clean, pos, end);
                continue;
            }

            int declStart = pos;
            List<AnnotationExpr> annotations = new ArrayList<>();
            Set<String> modifiers = new HashSet<>();

            while (pos < end) {
                pos = skipWhitespace(clean, pos, end);
                if (pos >= end) break;
                if (clean.charAt(pos) == '@') {
                    int annoEnd = consumeAnnotation(raw, clean, pos, end, index, annotations);
                    if (annoEnd <= pos) {
                        pos++;
                        break;
                    }
                    pos = annoEnd;
                    continue;
                }
                String word = readIdentifier(clean, pos, end);
                if (!word.isEmpty() && MODIFIER_WORDS.contains(word)) {
                    modifiers.add(word);
                    pos += word.length();
                    continue;
                }
                break;
            }

            pos = skipWhitespace(clean, pos, end);
            if (pos >= end) break;

            String keyword = readIdentifier(clean, pos, end);
            if (keyword.equals("class") || keyword.equals("interface") || keyword.equals("object")) {
                pos = parseTypeDeclaration(
                        raw, clean, declStart, pos, end, keyword, annotations, modifiers,
                        index, importAliases, cu, enclosingType, topLevelFacade);
            } else if (keyword.equals("fun")) {
                pos = parseFunctionDeclaration(
                        raw, clean, declStart, pos, end, annotations, modifiers,
                        index, importAliases, enclosingType, topLevelFacade);
            } else if (keyword.equals("val") || keyword.equals("var")) {
                pos = parsePropertyDeclaration(
                        raw, clean, declStart, pos, end, keyword.equals("var"), annotations, modifiers,
                        index, importAliases, enclosingType, topLevelFacade);
            } else if (keyword.equals("constructor") && enclosingType instanceof ClassOrInterfaceDeclaration cls) {
                pos = parseSecondaryConstructor(
                        raw, clean, declStart, pos, end, annotations, modifiers,
                        index, importAliases, cls);
            } else if (keyword.equals("init") && enclosingType instanceof ClassOrInterfaceDeclaration cls) {
                pos = parseInitBlock(raw, clean, pos, end, index, importAliases, cls);
            } else {
                pos = advancePastUnknownItem(clean, pos, end);
            }
        }
    }

    private static int parseTypeDeclaration(
            String raw,
            String clean,
            int declStart,
            int keywordPos,
            int end,
            String keyword,
            List<AnnotationExpr> annotations,
            Set<String> modifiers,
            LineIndex index,
            Map<String, String> importAliases,
            CompilationUnit cu,
            TypeDeclaration<?> enclosingType,
            ClassOrInterfaceDeclaration topLevelFacade) {
        int pos = keywordPos + keyword.length();
        pos = skipWhitespace(clean, pos, end);

        List<TypeParameter> typeParams = new ArrayList<>();
        if (pos < end && clean.charAt(pos) == '<') {
            int closeAngle = findMatchingBracket(clean, pos, end, '<', '>');
            if (closeAngle > pos) {
                parseTypeParameters(raw.substring(pos + 1, closeAngle), index.range(pos, closeAngle), typeParams);
                pos = skipWhitespace(clean, closeAngle + 1, end);
            }
        }

        boolean isCompanion = modifiers.contains("companion") && keyword.equals("object");
        String typeName = readIdentifier(raw, pos, end);
        int nameStart = pos;
        int nameEnd = pos;
        if (!typeName.isEmpty() && !CONTROL_KEYWORDS.contains(typeName)) {
            nameEnd = pos + typeName.length() - 1;
            pos += typeName.length();
        } else if (isCompanion) {
            typeName = "Companion";
            nameStart = keywordPos;
            nameEnd = keywordPos + keyword.length() - 1;
        } else {
            return advancePastUnknownItem(clean, pos, end);
        }

        int headerLimit = findHeaderEnd(clean, pos, end);
        pos = skipWhitespace(clean, pos, headerLimit);
        if (pos < headerLimit && clean.charAt(pos) == '<') {
            int closeAngle = findMatchingBracket(clean, pos, end, '<', '>');
            if (closeAngle > pos) {
                parseTypeParameters(raw.substring(pos + 1, closeAngle), index.range(pos, closeAngle), typeParams);
                headerLimit = findHeaderEnd(clean, closeAngle + 1, end);
                pos = skipWhitespace(clean, closeAngle + 1, headerLimit);
            }
        }

        // Optional visibility + annotations + 'constructor' keyword before primary constructor parens
        List<AnnotationExpr> ctorAnnotations = new ArrayList<>();
        while (pos < headerLimit) {
            if (clean.charAt(pos) == '@') {
                int next = consumeAnnotation(raw, clean, pos, end, index, ctorAnnotations);
                if (next <= pos) break;
                headerLimit = findHeaderEnd(clean, next, end);
                pos = skipWhitespace(clean, next, headerLimit);
                continue;
            }
            String w = readIdentifier(clean, pos, headerLimit);
            if (MODIFIER_WORDS.contains(w)) {
                pos = skipWhitespace(clean, pos + w.length(), headerLimit);
                continue;
            }
            if (w.equals("constructor")) {
                headerLimit = findHeaderEnd(clean, pos + w.length(), end);
                pos = skipWhitespace(clean, pos + w.length(), headerLimit);
            }
            break;
        }

        int primaryParensStart = -1;
        int primaryParensEnd = -1;
        if (pos < headerLimit && clean.charAt(pos) == '(') {
            primaryParensStart = pos;
            primaryParensEnd = findMatchingBracket(clean, pos, end, '(', ')');
            if (primaryParensEnd > pos) {
                headerLimit = findHeaderEnd(clean, primaryParensEnd + 1, end);
                pos = skipWhitespace(clean, primaryParensEnd + 1, headerLimit);
            }
        }

        // Optional supertype list ': SuperA(...), SuperB' before '{' or EOL
        int superStart = -1;
        int superEnd = -1;
        if (pos < headerLimit && clean.charAt(pos) == ':') {
            superStart = pos + 1;
            superEnd = findHeaderEnd(clean, superStart, end);
            pos = superEnd;
        } else {
            pos = headerLimit;
        }

        int bodyStart = -1;
        int bodyEnd = -1;
        if (pos < end && clean.charAt(pos) == '{') {
            bodyStart = pos;
            bodyEnd = findMatchingBracket(clean, pos, end, '{', '}');
            if (bodyEnd > pos) {
                pos = bodyEnd + 1;
            } else {
                bodyEnd = end - 1;
                pos = end;
            }
        }

        int declEnd = Math.max(declStart, (bodyEnd >= 0 ? bodyEnd : Math.max(superEnd - 1, Math.max(primaryParensEnd, nameEnd))));
        Range declRange = index.range(declStart, Math.min(declEnd, raw.length() - 1));
        Range nameRange = index.range(nameStart, Math.min(nameEnd, raw.length() - 1));

        if (modifiers.contains("enum") && keyword.equals("class")) {
            EnumDeclaration enumDecl = new EnumDeclaration(new NodeList<>(Modifier.publicModifier()), typeName);
            enumDecl.setRange(declRange);
            enumDecl.getName().setRange(nameRange);
            annotations.forEach(enumDecl::addAnnotation);
            if (bodyStart >= 0 && bodyEnd > bodyStart) {
                populateEnumBody(raw, clean, bodyStart + 1, bodyEnd, index, importAliases, cu, enumDecl, topLevelFacade);
            }
            attachType(cu, enclosingType, enumDecl);
            return pos;
        }

        boolean isInterface = keyword.equals("interface") || modifiers.contains("annotation");
        NodeList<Modifier> typeMods = new NodeList<>(Modifier.publicModifier());
        if (modifiers.contains("abstract") || modifiers.contains("sealed")) {
            if (!isInterface) typeMods.add(Modifier.abstractModifier());
        }
        if (enclosingType != null && !isInterface && (!modifiers.contains("inner") || keyword.equals("object"))) {
            typeMods.add(Modifier.staticModifier());
        }

        ClassOrInterfaceDeclaration typeDecl = new ClassOrInterfaceDeclaration(typeMods, isInterface, typeName);
        typeDecl.setRange(declRange);
        typeDecl.getName().setRange(nameRange);
        annotations.forEach(typeDecl::addAnnotation);
        typeParams.forEach(typeDecl::addTypeParameter);

        if (superStart >= 0 && superEnd > superStart) {
            parseSupertypes(raw, clean, superStart, superEnd, index, importAliases, typeDecl);
        }

        if (!isInterface && primaryParensStart >= 0 && primaryParensEnd > primaryParensStart) {
            Range ctorRange = index.range(primaryParensStart, primaryParensEnd);
            buildPrimaryConstructorAndProperties(
                    raw, clean, primaryParensStart + 1, primaryParensEnd, ctorRange,
                    ctorAnnotations, modifiers.contains("data"), index, importAliases, typeDecl);
        }

        if (keyword.equals("object")) {
            ClassOrInterfaceType selfType = parseClassType(typeName, nameRange, importAliases);
            if (!isCompanion) {
                VariableDeclarator instVar = new VariableDeclarator(selfType, new SimpleName("INSTANCE"));
                instVar.setRange(nameRange);
                FieldDeclaration instField = new FieldDeclaration(
                        new NodeList<>(Modifier.publicModifier(), Modifier.staticModifier(), Modifier.finalModifier()),
                        instVar);
                instField.setRange(nameRange);
                typeDecl.addMember(instField);
            } else if (enclosingType instanceof ClassOrInterfaceDeclaration outerClass) {
                VariableDeclarator compVar = new VariableDeclarator(selfType, new SimpleName(typeName));
                compVar.setRange(nameRange);
                FieldDeclaration compField = new FieldDeclaration(
                        new NodeList<>(Modifier.publicModifier(), Modifier.staticModifier(), Modifier.finalModifier()),
                        compVar);
                compField.setRange(nameRange);
                outerClass.addMember(compField);
            }
        }

        if (bodyStart >= 0 && bodyEnd > bodyStart) {
            scanScope(raw, clean, bodyStart + 1, bodyEnd, index, importAliases, cu, typeDecl, topLevelFacade);
        }

        if (isCompanion && enclosingType instanceof ClassOrInterfaceDeclaration outerClass) {
            for (MethodDeclaration companionMethod : typeDecl.getMethods()) {
                boolean hasSameOnOuter = outerClass.getMethodsByName(companionMethod.getNameAsString()).stream()
                        .anyMatch(m -> m.getParameters().size() == companionMethod.getParameters().size());
                if (!hasSameOnOuter) {
                    MethodDeclaration bridge = companionMethod.clone();
                    bridge.setModifiers(new NodeList<>(Modifier.publicModifier(), Modifier.staticModifier()));
                    companionMethod.getRange().ifPresent(bridge::setRange);
                    outerClass.addMember(bridge);
                }
            }
        }

        attachType(cu, enclosingType, typeDecl);
        return pos;
    }

    private static void attachType(
            CompilationUnit cu,
            TypeDeclaration<?> enclosingType,
            TypeDeclaration<?> created) {
        if (enclosingType != null) {
            enclosingType.addMember(created);
        } else {
            cu.getTypes().add(created);
        }
    }

    private static void populateEnumBody(
            String raw,
            String clean,
            int bodyStart,
            int bodyEnd,
            LineIndex index,
            Map<String, String> importAliases,
            CompilationUnit cu,
            EnumDeclaration enumDecl,
            ClassOrInterfaceDeclaration topLevelFacade) {
        int semiPos = findTopLevelChar(clean, bodyStart, bodyEnd, ';');
        int constantsEnd = semiPos >= 0 ? semiPos : bodyEnd;
        List<IntSpan> items = splitTopLevel(clean, bodyStart, constantsEnd, ',');
        for (IntSpan item : items) {
            int p = skipWhitespaceAndAnnotations(raw, clean, item.start, item.end, index, new ArrayList<>());
            String constName = readIdentifier(raw, p, item.end);
            if (!constName.isEmpty() && !CONTROL_KEYWORDS.contains(constName)) {
                Range r = index.range(p, p + constName.length() - 1);
                EnumConstantDeclaration constant = new EnumConstantDeclaration(constName);
                constant.setRange(r);
                constant.getName().setRange(r);
                enumDecl.addEntry(constant);
            }
        }
        if (semiPos >= 0 && semiPos + 1 < bodyEnd) {
            scanScope(raw, clean, semiPos + 1, bodyEnd, index, importAliases, cu, enumDecl, topLevelFacade);
        }
    }

    private static void parseSupertypes(
            String raw,
            String clean,
            int superStart,
            int superEnd,
            LineIndex index,
            Map<String, String> importAliases,
            ClassOrInterfaceDeclaration typeDecl) {
        List<IntSpan> entries = splitTopLevel(clean, superStart, superEnd, ',');
        for (IntSpan entry : entries) {
            int s = skipWhitespace(clean, entry.start, entry.end);
            if (s >= entry.end) continue;
            String sliceClean = clean.substring(s, entry.end);
            int byIdx = indexOfWord(sliceClean, "by");
            int effectiveEnd = byIdx >= 0 ? s + byIdx : entry.end;
            int parenPos = findTopLevelChar(clean, s, effectiveEnd, '(');
            boolean hasConstructorCall = parenPos > s;
            int typeEnd = hasConstructorCall ? parenPos : effectiveEnd;
            String typeSpelling = raw.substring(s, typeEnd).trim();
            if (typeSpelling.isEmpty()) continue;
            Range r = index.range(s, Math.max(s, typeEnd - 1));
            ClassOrInterfaceType superType = parseClassType(typeSpelling, r, importAliases);
            if (typeDecl.isInterface() || hasConstructorCall) {
                typeDecl.addExtendedType(superType);
            } else {
                typeDecl.addImplementedType(superType);
            }
        }
    }

    private static void buildPrimaryConstructorAndProperties(
            String raw,
            String clean,
            int paramsStart,
            int paramsEnd,
            Range ctorRange,
            List<AnnotationExpr> ctorAnnotations,
            boolean isDataClass,
            LineIndex index,
            Map<String, String> importAliases,
            ClassOrInterfaceDeclaration typeDecl) {
        ConstructorDeclaration ctor = new ConstructorDeclaration(
                new NodeList<>(Modifier.publicModifier()), typeDecl.getNameAsString());
        ctor.setRange(ctorRange);
        ctor.getName().setRange(ctorRange);
        BlockStmt ctorBody = new BlockStmt();
        ctorBody.setRange(ctorRange);
        ctor.setBody(ctorBody);
        ctorAnnotations.forEach(ctor::addAnnotation);

        List<ParsedParam> parsedParams = parseParameterList(raw, clean, paramsStart, paramsEnd, index, importAliases);
        int compIndex = 1;
        for (ParsedParam p : parsedParams) {
            Parameter param = new Parameter(p.type.clone(), new SimpleName(p.name));
            param.setVarArgs(p.vararg);
            param.setRange(p.range);
            param.getName().setRange(p.range);
            p.annotations.forEach(param::addAnnotation);
            ctor.addParameter(param);

            if (p.isVal || p.isVar) {
                addPropertyAndAccessors(typeDecl, p.name, p.type, p.isVar, false, p.annotations, p.range, null);
                if (isDataClass) {
                    MethodDeclaration comp = new MethodDeclaration(
                            new NodeList<>(Modifier.publicModifier()), p.type.clone(), "component" + compIndex++);
                    comp.setRange(p.range);
                    comp.getName().setRange(p.range);
                    BlockStmt body = new BlockStmt();
                    body.setRange(p.range);
                    comp.setBody(body);
                    typeDecl.addMember(comp);
                }
            }
        }
        typeDecl.addMember(ctor);

        boolean hasDefaults = parsedParams.stream().anyMatch(p -> p.hasDefault);
        if (hasDefaults && !parsedParams.isEmpty()) {
            ConstructorDeclaration overload = new ConstructorDeclaration(
                    new NodeList<>(Modifier.publicModifier()), typeDecl.getNameAsString());
            overload.setRange(ctorRange);
            overload.getName().setRange(ctorRange);
            for (ParsedParam p : parsedParams) {
                if (!p.hasDefault) {
                    Parameter req = new Parameter(p.type.clone(), new SimpleName(p.name));
                    req.setRange(p.range);
                    req.getName().setRange(p.range);
                    overload.addParameter(req);
                }
            }
            if (overload.getParameters().size() != ctor.getParameters().size()) {
                BlockStmt body = new BlockStmt();
                body.setRange(ctorRange);
                overload.setBody(body);
                typeDecl.addMember(overload);
            }
        }

        if (isDataClass) {
            ClassOrInterfaceType selfType = parseClassType(typeDecl.getNameAsString(), ctorRange, importAliases);
            MethodDeclaration copy = new MethodDeclaration(
                    new NodeList<>(Modifier.publicModifier()), selfType, "copy");
            copy.setRange(ctorRange);
            copy.getName().setRange(ctorRange);
            for (Parameter cp : ctor.getParameters()) {
                copy.addParameter(cp.clone());
            }
            BlockStmt copyBody = new BlockStmt();
            copyBody.setRange(ctorRange);
            copy.setBody(copyBody);
            typeDecl.addMember(copy);
        }
    }

    private static int parseSecondaryConstructor(
            String raw,
            String clean,
            int declStart,
            int keywordPos,
            int end,
            List<AnnotationExpr> annotations,
            Set<String> modifiers,
            LineIndex index,
            Map<String, String> importAliases,
            ClassOrInterfaceDeclaration enclosingClass) {
        int pos = skipWhitespace(clean, keywordPos + "constructor".length(), end);
        if (pos >= end || clean.charAt(pos) != '(') {
            return advancePastUnknownItem(clean, pos, end);
        }
        int closeParen = findMatchingBracket(clean, pos, end, '(', ')');
        if (closeParen <= pos) return advancePastUnknownItem(clean, pos, end);

        List<ParsedParam> params = parseParameterList(raw, clean, pos + 1, closeParen, index, importAliases);
        pos = skipWhitespace(clean, closeParen + 1, end);

        if (pos < end && clean.charAt(pos) == ':') {
            pos = skipWhitespace(clean, pos + 1, end);
            String delegate = readIdentifier(clean, pos, end);
            if (delegate.equals("this") || delegate.equals("super")) {
                pos = skipWhitespace(clean, pos + delegate.length(), end);
                if (pos < end && clean.charAt(pos) == '(') {
                    int delEnd = findMatchingBracket(clean, pos, end, '(', ')');
                    if (delEnd > pos) pos = skipWhitespace(clean, delEnd + 1, end);
                }
            }
        }

        int bodyStart = -1;
        int bodyEnd = -1;
        if (pos < end && clean.charAt(pos) == '{') {
            bodyStart = pos;
            bodyEnd = findMatchingBracket(clean, pos, end, '{', '}');
            if (bodyEnd > pos) pos = bodyEnd + 1;
        }

        int declEnd = bodyEnd > 0 ? bodyEnd : closeParen;
        Range ctorRange = index.range(declStart, Math.min(declEnd, raw.length() - 1));
        ConstructorDeclaration ctor = new ConstructorDeclaration(
                visibilityModifiers(modifiers, false), enclosingClass.getNameAsString());
        ctor.setRange(ctorRange);
        ctor.getName().setRange(index.range(keywordPos, keywordPos + "constructor".length() - 1));
        annotations.forEach(ctor::addAnnotation);
        for (ParsedParam p : params) {
            Parameter param = new Parameter(p.type.clone(), new SimpleName(p.name));
            param.setVarArgs(p.vararg);
            param.setRange(p.range);
            param.getName().setRange(p.range);
            p.annotations.forEach(param::addAnnotation);
            ctor.addParameter(param);
        }
        BlockStmt body = new BlockStmt();
        body.setRange(ctorRange);
        if (bodyStart >= 0 && bodyEnd > bodyStart) {
            extractBodyStatements(raw, clean, bodyStart + 1, bodyEnd, index, importAliases, body);
        }
        ctor.setBody(body);
        enclosingClass.addMember(ctor);
        return pos;
    }

    private static int parseInitBlock(
            String raw,
            String clean,
            int keywordPos,
            int end,
            LineIndex index,
            Map<String, String> importAliases,
            ClassOrInterfaceDeclaration enclosingClass) {
        int pos = skipWhitespace(clean, keywordPos + "init".length(), end);
        if (pos < end && clean.charAt(pos) == '{') {
            int bodyEnd = findMatchingBracket(clean, pos, end, '{', '}');
            if (bodyEnd > pos) {
                ConstructorDeclaration targetCtor = enclosingClass.getConstructors().stream().findFirst().orElseGet(() -> {
                    Range r = index.range(keywordPos, bodyEnd);
                    ConstructorDeclaration c = new ConstructorDeclaration(
                            new NodeList<>(Modifier.publicModifier()), enclosingClass.getNameAsString());
                    c.setRange(r);
                    c.getName().setRange(r);
                    BlockStmt b = new BlockStmt();
                    b.setRange(r);
                    c.setBody(b);
                    enclosingClass.addMember(c);
                    return c;
                });
                extractBodyStatements(raw, clean, pos + 1, bodyEnd, index, importAliases, targetCtor.getBody());
                return bodyEnd + 1;
            }
        }
        return advancePastUnknownItem(clean, pos, end);
    }

    private static int parseFunctionDeclaration(
            String raw,
            String clean,
            int declStart,
            int keywordPos,
            int end,
            List<AnnotationExpr> annotations,
            Set<String> modifiers,
            LineIndex index,
            Map<String, String> importAliases,
            TypeDeclaration<?> enclosingType,
            ClassOrInterfaceDeclaration topLevelFacade) {
        int pos = skipWhitespace(clean, keywordPos + "fun".length(), end);
        List<TypeParameter> typeParams = new ArrayList<>();
        if (pos < end && clean.charAt(pos) == '<') {
            int closeAngle = findMatchingBracket(clean, pos, end, '<', '>');
            if (closeAngle > pos) {
                parseTypeParameters(raw.substring(pos + 1, closeAngle), index.range(pos, closeAngle), typeParams);
                pos = skipWhitespace(clean, closeAngle + 1, end);
            }
        }

        // Handle optional receiver type for extension functions (e.g. fun String.toSlug() or fun List<String>.join())
        int parenPos = findTopLevelCharInHeader(clean, pos, end, '(');
        if (parenPos <= pos) {
            return advancePastUnknownItem(clean, pos, end);
        }

        String headerBeforeParen = raw.substring(pos, parenPos).trim();
        Type receiverType = null;
        String funName = headerBeforeParen;
        int nameStart = pos;
        int lastDot = findLastTopLevelDot(headerBeforeParen);
        if (lastDot > 0) {
            String recvSpelling = headerBeforeParen.substring(0, lastDot).trim();
            funName = headerBeforeParen.substring(lastDot + 1).trim();
            nameStart = pos + lastDot + 1;
            receiverType = mapKotlinType(recvSpelling, index.range(pos, pos + lastDot - 1), importAliases, false);
        }
        funName = stripBackticks(funName);
        if (!isValidSimpleIdentifier(funName)) {
            return advancePastUnknownItem(clean, parenPos, end);
        }
        Range nameRange = index.range(nameStart, Math.max(nameStart, parenPos - 1));

        int closeParen = findMatchingBracket(clean, parenPos, end, '(', ')');
        if (closeParen <= parenPos) {
            return advancePastUnknownItem(clean, parenPos + 1, end);
        }
        List<ParsedParam> params = parseParameterList(raw, clean, parenPos + 1, closeParen, index, importAliases);
        pos = skipWhitespace(clean, closeParen + 1, end);

        Type explicitReturnType = null;
        if (pos < end && clean.charAt(pos) == ':') {
            pos = skipWhitespace(clean, pos + 1, end);
            int retEnd = findReturnTypeEnd(clean, pos, end);
            if (retEnd > pos) {
                String retSpelling = raw.substring(pos, retEnd).trim();
                explicitReturnType = mapKotlinType(retSpelling, index.range(pos, retEnd - 1), importAliases, true);
                pos = skipWhitespace(clean, retEnd, end);
            }
        }

        // Skip optional 'where' clause before '{' or '='
        if (readIdentifier(clean, pos, end).equals("where")) {
            int whereEnd = findReturnTypeEnd(clean, pos + 5, end);
            pos = skipWhitespace(clean, whereEnd, end);
        }

        int bodyStart = -1;
        int bodyEnd = -1;
        boolean expressionBody = false;
        if (pos < end && clean.charAt(pos) == '{') {
            bodyStart = pos;
            bodyEnd = findMatchingBracket(clean, pos, end, '{', '}');
            if (bodyEnd > pos) {
                pos = bodyEnd + 1;
            } else {
                bodyEnd = end - 1;
                pos = end;
            }
        } else if (pos < end && clean.charAt(pos) == '=') {
            expressionBody = true;
            bodyStart = pos + 1;
            bodyEnd = findExpressionStatementEnd(clean, bodyStart, end);
            pos = bodyEnd;
        }

        int declEnd = bodyEnd > 0 ? bodyEnd : closeParen;
        Range methodRange = index.range(declStart, Math.min(declEnd, raw.length() - 1));

        Type returnType = explicitReturnType;
        if (returnType == null) {
            if (expressionBody && bodyStart >= 0 && bodyEnd > bodyStart) {
                returnType = inferExpressionType(raw.substring(bodyStart, bodyEnd).trim(), methodRange, importAliases);
            } else {
                VoidType vt = new VoidType();
                vt.setRange(nameRange);
                returnType = vt;
            }
        }

        boolean isTopLevel = enclosingType == null;
        boolean isInterfaceOwner = enclosingType instanceof ClassOrInterfaceDeclaration c && c.isInterface();
        boolean isObjectOwner = enclosingType instanceof ClassOrInterfaceDeclaration c
                && c.getFieldByName("INSTANCE").isPresent();
        boolean hasJvmStatic = annotations.stream().anyMatch(a -> a.getNameAsString().endsWith("JvmStatic"));
        NodeList<Modifier> methodMods = visibilityModifiers(modifiers, isTopLevel || hasJvmStatic);
        if (modifiers.contains("abstract") && !isInterfaceOwner) {
            methodMods.add(Modifier.abstractModifier());
        }

        MethodDeclaration method = new MethodDeclaration(methodMods, returnType, funName);
        method.setRange(methodRange);
        method.getName().setRange(nameRange);
        annotations.forEach(method::addAnnotation);
        if (modifiers.contains("override") && annotations.stream().noneMatch(a -> a.getNameAsString().equals("Override"))) {
            MarkerAnnotationExpr ov = new MarkerAnnotationExpr(parseNameWithRange("Override", nameRange));
            ov.setRange(nameRange);
            method.addAnnotation(ov);
        }
        typeParams.forEach(method::addTypeParameter);

        if (receiverType != null) {
            Parameter recvParam = new Parameter(receiverType, new SimpleName("receiver"));
            recvParam.setRange(nameRange);
            recvParam.getName().setRange(nameRange);
            method.addParameter(recvParam);
        }
        for (ParsedParam p : params) {
            Parameter param = new Parameter(p.type.clone(), new SimpleName(p.name));
            param.setVarArgs(p.vararg);
            param.setRange(p.range);
            param.getName().setRange(p.range);
            p.annotations.forEach(param::addAnnotation);
            method.addParameter(param);
        }

        if (bodyStart >= 0 && bodyEnd > bodyStart) {
            BlockStmt body = new BlockStmt();
            Range bRange = index.range(bodyStart, Math.min(bodyEnd, raw.length() - 1));
            body.setRange(bRange);
            if (expressionBody) {
                extractExpressionBodyStatements(raw, clean, bodyStart, bodyEnd, !(returnType instanceof VoidType), index, importAliases, body);
            } else {
                extractBodyStatements(raw, clean, bodyStart + 1, bodyEnd, index, importAliases, body);
            }
            method.setBody(body);
        } else if (isInterfaceOwner || modifiers.contains("abstract")) {
            method.setBody(null);
        } else {
            BlockStmt emptyBody = new BlockStmt();
            emptyBody.setRange(methodRange);
            method.setBody(emptyBody);
        }

        TypeDeclaration<?> targetOwner = enclosingType != null ? enclosingType : topLevelFacade;
        String methodName = method.getNameAsString();
        int paramCount = method.getParameters().size();
        targetOwner.getMembers().removeIf(m -> m instanceof MethodDeclaration md
                && md.getNameAsString().equals(methodName)
                && md.getParameters().size() == paramCount);
        targetOwner.addMember(method);

        if (isObjectOwner && !method.isStatic()) {
            // Allow both MyObject.INSTANCE.foo() and static-style MyObject.foo() resolution
            MethodDeclaration staticPeer = method.clone();
            staticPeer.addModifier(Modifier.Keyword.STATIC);
            method.getRange().ifPresent(staticPeer::setRange);
        }

        boolean hasDefaults = params.stream().anyMatch(p -> p.hasDefault);
        if (hasDefaults && method.getBody().isPresent()) {
            MethodDeclaration overload = new MethodDeclaration(methodMods, returnType.clone(), funName);
            overload.setRange(methodRange);
            overload.getName().setRange(nameRange);
            if (receiverType != null) {
                Parameter recvParam = new Parameter(receiverType.clone(), new SimpleName("receiver"));
                recvParam.setRange(nameRange);
                recvParam.getName().setRange(nameRange);
                overload.addParameter(recvParam);
            }
            for (ParsedParam p : params) {
                if (!p.hasDefault) {
                    Parameter req = new Parameter(p.type.clone(), new SimpleName(p.name));
                    req.setRange(p.range);
                    req.getName().setRange(p.range);
                    overload.addParameter(req);
                }
            }
            if (overload.getParameters().size() != method.getParameters().size()) {
                BlockStmt ovBody = new BlockStmt();
                ovBody.setRange(methodRange);
                overload.setBody(ovBody);
                targetOwner.addMember(overload);
            }
        }

        return pos;
    }

    private static int parsePropertyDeclaration(
            String raw,
            String clean,
            int declStart,
            int keywordPos,
            int end,
            boolean isVar,
            List<AnnotationExpr> annotations,
            Set<String> modifiers,
            LineIndex index,
            Map<String, String> importAliases,
            TypeDeclaration<?> enclosingType,
            ClassOrInterfaceDeclaration topLevelFacade) {
        int pos = skipWhitespace(clean, keywordPos + 3, end);
        if (pos < end && clean.charAt(pos) == '<') {
            int closeAngle = findMatchingBracket(clean, pos, end, '<', '>');
            if (closeAngle > pos) pos = skipWhitespace(clean, closeAngle + 1, end);
        }

        int stmtEnd = findExpressionStatementEnd(clean, pos, end);
        if (stmtEnd <= pos) return advancePastUnknownItem(clean, pos, end);

        int eqPos = findTopLevelChar(clean, pos, stmtEnd, '=');
        int colonPos = findTopLevelChar(clean, pos, eqPos >= 0 ? eqPos : stmtEnd, ':');
        int nameLimit = colonPos >= 0 ? colonPos : (eqPos >= 0 ? eqPos : stmtEnd);

        String nameRegion = raw.substring(pos, nameLimit).trim();
        int byIdx = indexOfWord(clean.substring(pos, nameLimit), "by");
        if (byIdx >= 0) {
            nameRegion = raw.substring(pos, pos + byIdx).trim();
        }
        int lastDot = findLastTopLevelDot(nameRegion);
        String propName = stripBackticks(lastDot >= 0 ? nameRegion.substring(lastDot + 1).trim() : nameRegion);
        if (!isValidSimpleIdentifier(propName)) {
            return stmtEnd;
        }

        Range propRange = index.range(declStart, Math.min(stmtEnd - 1, raw.length() - 1));
        Type propType;
        if (colonPos >= 0) {
            int typeEnd = eqPos >= 0 ? eqPos : stmtEnd;
            String typeSlice = raw.substring(colonPos + 1, typeEnd).trim();
            int byInType = indexOfWord(clean.substring(colonPos + 1, typeEnd), "by");
            if (byInType >= 0) {
                typeSlice = raw.substring(colonPos + 1, colonPos + 1 + byInType).trim();
            }
            propType = mapKotlinType(typeSlice, propRange, importAliases, false);
        } else if (eqPos >= 0 && eqPos + 1 < stmtEnd) {
            propType = inferExpressionType(raw.substring(eqPos + 1, stmtEnd).trim(), propRange, importAliases);
        } else {
            propType = parseClassType("Object", propRange, importAliases);
        }

        BlockStmt initCalls = null;
        if (eqPos >= 0 && eqPos + 1 < stmtEnd) {
            initCalls = new BlockStmt();
            initCalls.setRange(propRange);
            extractExpressionBodyStatements(raw, clean, eqPos + 1, stmtEnd, false, index, importAliases, initCalls);
        }

        boolean isTopLevel = enclosingType == null;
        TypeDeclaration<?> owner = enclosingType != null ? enclosingType : topLevelFacade;
        if (owner instanceof ClassOrInterfaceDeclaration cls) {
            addPropertyAndAccessors(
                    cls, propName, propType, isVar, isTopLevel || modifiers.contains("const"),
                    annotations, propRange, initCalls);
        }
        return stmtEnd;
    }

    private static void addPropertyAndAccessors(
            ClassOrInterfaceDeclaration owner,
            String propName,
            Type propType,
            boolean isVar,
            boolean isStatic,
            List<AnnotationExpr> annotations,
            Range range,
            BlockStmt initCalls) {
        if (!owner.isInterface()) {
            boolean hasField = owner.getFieldByName(propName).isPresent();
            if (!hasField) {
                boolean publicField = isStatic || annotations.stream()
                        .anyMatch(a -> a.getNameAsString().endsWith("JvmField"));
                NodeList<Modifier> fieldMods = new NodeList<>(
                        publicField ? Modifier.publicModifier() : Modifier.privateModifier());
                if (isStatic) fieldMods.add(Modifier.staticModifier());
                if (!isVar) fieldMods.add(Modifier.finalModifier());
                VariableDeclarator declarator = new VariableDeclarator(propType.clone(), new SimpleName(propName));
                declarator.setRange(range);
                declarator.getName().setRange(range);
                FieldDeclaration field = new FieldDeclaration(fieldMods, declarator);
                field.setRange(range);
                annotations.forEach(field::addAnnotation);
                owner.addMember(field);
            }
        }

        String getterName = getterName(propName);
        boolean hasGetter = owner.getMethodsByName(getterName).stream().anyMatch(m -> m.getParameters().isEmpty());
        if (!hasGetter) {
            NodeList<Modifier> getterMods = new NodeList<>(Modifier.publicModifier());
            if (isStatic && !owner.isInterface()) getterMods.add(Modifier.staticModifier());
            MethodDeclaration getter = new MethodDeclaration(getterMods, propType.clone(), getterName);
            getter.setRange(range);
            getter.getName().setRange(range);
            if (!owner.isInterface()) {
                BlockStmt body = initCalls != null ? initCalls : new BlockStmt();
                body.setRange(range);
                getter.setBody(body);
            } else {
                getter.setBody(null);
            }
            owner.addMember(getter);
        }

        if (isVar) {
            String setterName = setterName(propName);
            boolean hasSetter = owner.getMethodsByName(setterName).stream().anyMatch(m -> m.getParameters().size() == 1);
            if (!hasSetter) {
                NodeList<Modifier> setterMods = new NodeList<>(Modifier.publicModifier());
                if (isStatic && !owner.isInterface()) setterMods.add(Modifier.staticModifier());
                VoidType vt = new VoidType();
                vt.setRange(range);
                MethodDeclaration setter = new MethodDeclaration(setterMods, vt, setterName);
                setter.setRange(range);
                setter.getName().setRange(range);
                Parameter valParam = new Parameter(propType.clone(), new SimpleName("value"));
                valParam.setRange(range);
                valParam.getName().setRange(range);
                setter.addParameter(valParam);
                if (!owner.isInterface()) {
                    BlockStmt body = new BlockStmt();
                    body.setRange(range);
                    setter.setBody(body);
                } else {
                    setter.setBody(null);
                }
                owner.addMember(setter);
            }
        }
    }

    private static String getterName(String propName) {
        if (propName.length() > 2 && propName.startsWith("is") && Character.isUpperCase(propName.charAt(2))) {
            return propName;
        }
        return "get" + Character.toUpperCase(propName.charAt(0)) + propName.substring(1);
    }

    private static String setterName(String propName) {
        if (propName.length() > 2 && propName.startsWith("is") && Character.isUpperCase(propName.charAt(2))) {
            return "set" + propName.substring(2);
        }
        return "set" + Character.toUpperCase(propName.charAt(0)) + propName.substring(1);
    }

    private static void extractExpressionBodyStatements(
            String raw,
            String clean,
            int exprStart,
            int exprEnd,
            boolean nonVoid,
            LineIndex index,
            Map<String, String> importAliases,
            BlockStmt targetBlock) {
        extractBodyStatements(raw, clean, exprStart, exprEnd, index, importAliases, targetBlock);
    }

    private static void extractBodyStatements(
            String raw,
            String clean,
            int bodyStart,
            int bodyEnd,
            LineIndex index,
            Map<String, String> importAliases,
            BlockStmt targetBlock) {
        String bodyRaw = raw.substring(bodyStart, bodyEnd);
        String bodyClean = clean.substring(bodyStart, bodyEnd);

        Matcher valMatcher = LOCAL_VAL_SCAN.matcher(bodyClean);
        while (valMatcher.find()) {
            String varName = valMatcher.group(1);
            if (!isValidSimpleIdentifier(varName)) continue;
            int absStart = bodyStart + valMatcher.start();
            int absEnd = Math.min(raw.length() - 1, bodyStart + valMatcher.end() - 1);
            Range r = index.range(absStart, Math.max(absStart, absEnd));
            String explicitType = valMatcher.group(2);
            String rhsRaw = bodyRaw.substring(valMatcher.start(3), valMatcher.end(3)).trim();
            Type varType = (explicitType != null && !explicitType.isBlank())
                    ? mapKotlinType(explicitType.trim(), r, importAliases, false)
                    : inferExpressionType(rhsRaw, r, importAliases);
            VariableDeclarator vd = new VariableDeclarator(varType, new SimpleName(varName));
            vd.setRange(r);
            vd.getName().setRange(r);
            VariableDeclarationExpr vde = new VariableDeclarationExpr(vd);
            vde.setRange(r);
            ExpressionStmt stmt = new ExpressionStmt(vde);
            stmt.setRange(r);
            targetBlock.addStatement(stmt);
        }

        Matcher callMatcher = CALL_SCAN.matcher(bodyClean);
        while (callMatcher.find()) {
            int matchStart = callMatcher.start();
            if (matchStart > 0) {
                char prev = bodyClean.charAt(matchStart - 1);
                if (Character.isJavaIdentifierPart(prev) || prev == '@') continue;
            }
            int openParenOffset = bodyStart + callMatcher.end() - 1;
            int closeParenOffset = findMatchingBracket(clean, openParenOffset, bodyEnd, '(', ')');
            int callEndOffset = closeParenOffset > openParenOffset ? closeParenOffset : openParenOffset;
            int callStartOffset = bodyStart + matchStart;
            Range callRange = index.range(callStartOffset, Math.min(callEndOffset, raw.length() - 1));

            String qualified = callMatcher.group(1);
            String simple = callMatcher.group(2);
            List<Expression> argExprs = parseCallArguments(
                    raw, clean, openParenOffset + 1, closeParenOffset > openParenOffset ? closeParenOffset : openParenOffset + 1,
                    index, importAliases);

            if (qualified != null) {
                String normalized = qualified.replaceAll("\\s+", "");
                int lastDot = normalized.lastIndexOf('.');
                String scopeText = normalized.substring(0, lastDot);
                String methodName = normalized.substring(lastDot + 1);
                if (CONTROL_KEYWORDS.contains(methodName) || !isValidSimpleIdentifier(methodName)) continue;
                Expression scopeExpr = buildScopeExpression(scopeText, callRange, importAliases);
                MethodCallExpr call = new MethodCallExpr(scopeExpr, new SimpleName(methodName), new NodeList<>(argExprs));
                call.setRange(callRange);
                call.getName().setRange(callRange);
                ExpressionStmt stmt = new ExpressionStmt(call);
                stmt.setRange(callRange);
                targetBlock.addStatement(stmt);
            } else if (simple != null) {
                if (CONTROL_KEYWORDS.contains(simple) || !isValidSimpleIdentifier(simple)) continue;
                if (Character.isUpperCase(simple.charAt(0))) {
                    ClassOrInterfaceType createdType = parseClassType(simple, callRange, importAliases);
                    ObjectCreationExpr creation = new ObjectCreationExpr(null, createdType, new NodeList<>(argExprs));
                    creation.setRange(callRange);
                    ExpressionStmt stmt = new ExpressionStmt(creation);
                    stmt.setRange(callRange);
                    targetBlock.addStatement(stmt);
                } else {
                    MethodCallExpr call = new MethodCallExpr(null, new SimpleName(simple), new NodeList<>(argExprs));
                    call.setRange(callRange);
                    call.getName().setRange(callRange);
                    ExpressionStmt stmt = new ExpressionStmt(call);
                    stmt.setRange(callRange);
                    targetBlock.addStatement(stmt);
                }
            }
        }
    }

    private static List<Expression> parseCallArguments(
            String raw,
            String clean,
            int argsStart,
            int argsEnd,
            LineIndex index,
            Map<String, String> importAliases) {
        if (argsEnd <= argsStart) return List.of();
        List<IntSpan> spans = splitTopLevel(clean, argsStart, argsEnd, ',');
        List<Expression> exprs = new ArrayList<>();
        for (IntSpan span : spans) {
            String argText = raw.substring(span.start, span.end).trim();
            if (argText.isEmpty()) continue;
            // Strip named argument prefix `paramName = value`
            int eq = argText.indexOf('=');
            if (eq > 0 && (eq + 1 >= argText.length() || argText.charAt(eq + 1) != '=')
                    && argText.charAt(eq - 1) != '!' && argText.charAt(eq - 1) != '<' && argText.charAt(eq - 1) != '>') {
                String lhs = argText.substring(0, eq).trim();
                if (isValidSimpleIdentifier(lhs)) {
                    argText = argText.substring(eq + 1).trim();
                }
            }
            if (argText.startsWith("*")) {
                argText = argText.substring(1).trim();
            }
            Range r = index.range(span.start, Math.max(span.start, span.end - 1));
            exprs.add(simpleArgumentExpression(argText, r, importAliases));
        }
        return exprs;
    }

    private static Expression simpleArgumentExpression(String text, Range range, Map<String, String> importAliases) {
        if (text.equals("null")) {
            NullLiteralExpr n = new NullLiteralExpr();
            n.setRange(range);
            return n;
        }
        if (text.equals("true") || text.equals("false")) {
            BooleanLiteralExpr b = new BooleanLiteralExpr(Boolean.parseBoolean(text));
            b.setRange(range);
            return b;
        }
        if (text.equals("this")) {
            ThisExpr t = new ThisExpr();
            t.setRange(range);
            return t;
        }
        if (text.startsWith("\"") && text.endsWith("\"") && text.length() >= 2) {
            StringLiteralExpr s = new StringLiteralExpr(sanitizeStringLiteralContent(text.substring(1, text.length() - 1)));
            s.setRange(range);
            return s;
        }
        if (text.matches("-?[0-9]+")) {
            IntegerLiteralExpr i = new IntegerLiteralExpr(text);
            i.setRange(range);
            return i;
        }
        if (text.matches("-?[0-9]+[Ll]")) {
            LongLiteralExpr l = new LongLiteralExpr(text);
            l.setRange(range);
            return l;
        }
        if (text.endsWith("::class") || text.endsWith("::class.java")) {
            String tName = text.substring(0, text.indexOf("::class")).trim();
            ClassExpr ce = new ClassExpr(mapKotlinType(tName, range, importAliases, false));
            ce.setRange(range);
            return ce;
        }
        if (isValidSimpleIdentifier(text)) {
            NameExpr ne = new NameExpr(new SimpleName(text));
            ne.setRange(range);
            return ne;
        }
        if (text.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")) {
            return buildScopeExpression(text, range, importAliases);
        }
        NullLiteralExpr fallback = new NullLiteralExpr();
        fallback.setRange(range);
        return fallback;
    }

    private static Expression buildScopeExpression(String dotted, Range range, Map<String, String> importAliases) {
        String[] parts = dotted.split("\\.");
        Expression current;
        if (parts[0].equals("this")) {
            current = new ThisExpr();
            current.setRange(range);
        } else {
            String first = importAliases.getOrDefault(parts[0], parts[0]);
            if (first.contains(".")) {
                String[] aliasParts = first.split("\\.");
                current = new NameExpr(new SimpleName(aliasParts[0]));
                current.setRange(range);
                for (int j = 1; j < aliasParts.length; j++) {
                    FieldAccessExpr fa = new FieldAccessExpr(current, aliasParts[j]);
                    fa.setRange(range);
                    current = fa;
                }
            } else {
                current = new NameExpr(new SimpleName(first));
                current.setRange(range);
            }
        }
        for (int i = 1; i < parts.length; i++) {
            if (!isValidSimpleIdentifier(parts[i])) break;
            FieldAccessExpr fa = new FieldAccessExpr(current, parts[i]);
            fa.setRange(range);
            current = fa;
        }
        return current;
    }

    private static record ParsedParam(
            String name,
            Type type,
            boolean isVal,
            boolean isVar,
            boolean vararg,
            boolean hasDefault,
            List<AnnotationExpr> annotations,
            Range range) {}

    private static List<ParsedParam> parseParameterList(
            String raw,
            String clean,
            int start,
            int end,
            LineIndex index,
            Map<String, String> importAliases) {
        List<IntSpan> items = splitTopLevel(clean, start, end, ',');
        List<ParsedParam> result = new ArrayList<>();
        int paramIdx = 1;
        for (IntSpan item : items) {
            int p = skipWhitespace(clean, item.start, item.end);
            if (p >= item.end) continue;

            List<AnnotationExpr> paramAnnos = new ArrayList<>();
            boolean isVal = false;
            boolean isVar = false;
            boolean vararg = false;

            while (p < item.end) {
                p = skipWhitespace(clean, p, item.end);
                if (p >= item.end) break;
                if (clean.charAt(p) == '@') {
                    int next = consumeAnnotation(raw, clean, p, item.end, index, paramAnnos);
                    if (next <= p) {
                        p++;
                        break;
                    }
                    p = next;
                    continue;
                }
                String w = readIdentifier(clean, p, item.end);
                if (w.equals("val")) {
                    isVal = true;
                    p += 3;
                    continue;
                }
                if (w.equals("var")) {
                    isVar = true;
                    p += 3;
                    continue;
                }
                if (w.equals("vararg")) {
                    vararg = true;
                    p += 6;
                    continue;
                }
                if (!w.isEmpty() && (MODIFIER_WORDS.contains(w) || w.equals("crossinline") || w.equals("noinline"))) {
                    p += w.length();
                    continue;
                }
                break;
            }

            p = skipWhitespace(clean, p, item.end);
            if (p >= item.end) continue;

            int eqPos = findTopLevelChar(clean, p, item.end, '=');
            boolean hasDefault = eqPos >= 0;
            int declEnd = hasDefault ? eqPos : item.end;
            int colonPos = findTopLevelChar(clean, p, declEnd, ':');

            String paramName;
            Type paramType;
            Range paramRange = index.range(item.start, Math.max(item.start, item.end - 1));
            if (colonPos > p) {
                paramName = stripBackticks(raw.substring(p, colonPos).trim());
                String typeText = raw.substring(colonPos + 1, declEnd).trim();
                paramType = mapKotlinType(typeText, paramRange, importAliases, false);
            } else {
                paramName = stripBackticks(raw.substring(p, declEnd).trim());
                paramType = parseClassType("Object", paramRange, importAliases);
            }
            if (!isValidSimpleIdentifier(paramName)) {
                paramName = "param" + paramIdx;
            }
            paramIdx++;
            result.add(new ParsedParam(paramName, paramType, isVal, isVar, vararg, hasDefault, paramAnnos, paramRange));
        }
        return result;
    }

    private static int consumeAnnotation(
            String raw,
            String clean,
            int atPos,
            int limit,
            LineIndex index,
            List<AnnotationExpr> out) {
        int pos = atPos + 1;
        pos = skipWhitespace(clean, pos, limit);
        if (pos >= limit) return atPos + 1;

        // Check for optional use-site target e.g. @field:Autowired, @get:Bean, @param:Qualifier("x")
        String firstWord = readIdentifier(raw, pos, limit);
        if (firstWord.isEmpty()) return atPos + 1;
        int afterFirst = skipWhitespace(clean, pos + firstWord.length(), limit);
        if (afterFirst < limit && clean.charAt(afterFirst) == ':') {
            pos = skipWhitespace(clean, afterFirst + 1, limit);
            if (pos < limit && clean.charAt(pos) == '[') {
                int closeBracket = findMatchingBracket(clean, pos, limit, '[', ']');
                return closeBracket > pos ? closeBracket + 1 : pos + 1;
            }
        }

        int nameStart = pos;
        int nameEnd = pos;
        while (nameEnd < limit) {
            char c = raw.charAt(nameEnd);
            if (Character.isJavaIdentifierPart(c) || c == '.') {
                nameEnd++;
            } else {
                break;
            }
        }
        if (nameEnd <= nameStart) return atPos + 1;
        String annoTypeName = raw.substring(nameStart, nameEnd);
        if (!isValidQualifiedName(annoTypeName)) return nameEnd;

        pos = skipWhitespace(clean, nameEnd, limit);
        if (pos < limit && clean.charAt(pos) == '<') {
            int closeAngle = findMatchingBracket(clean, pos, limit, '<', '>');
            if (closeAngle > pos) pos = skipWhitespace(clean, closeAngle + 1, limit);
        }

        int annoEnd = nameEnd - 1;
        String argsRaw = null;
        String argsClean = null;
        int openParen = -1;
        int closeParen = -1;
        if (pos < limit && clean.charAt(pos) == '(') {
            openParen = pos;
            closeParen = findMatchingBracket(clean, pos, limit, '(', ')');
            if (closeParen > pos) {
                annoEnd = closeParen;
                argsRaw = raw.substring(openParen + 1, closeParen).trim();
                argsClean = clean.substring(openParen + 1, closeParen);
                pos = closeParen + 1;
            }
        } else {
            pos = nameEnd;
        }

        Range annoRange = index.range(atPos, Math.min(annoEnd, raw.length() - 1));
        Name parsedName = parseNameWithRange(annoTypeName, annoRange);
        AnnotationExpr created;
        if (argsRaw == null || argsRaw.isEmpty()) {
            created = new MarkerAnnotationExpr(parsedName);
        } else {
            final String cleanArgs = argsClean;
            List<IntSpan> argSpans = splitTopLevel(cleanArgs, 0, cleanArgs.length(), ',');
            boolean hasNamedPairs = argSpans.stream().anyMatch(s -> {
                String seg = cleanArgs.substring(s.start, s.end);
                int eq = seg.indexOf('=');
                return eq > 0 && (eq + 1 >= seg.length() || seg.charAt(eq + 1) != '=');
            });
            if (!hasNamedPairs && argSpans.size() == 1) {
                Expression valExpr = parseAnnotationValue(argsRaw, annoRange);
                created = new SingleMemberAnnotationExpr(parsedName, valExpr);
            } else {
                NodeList<MemberValuePair> pairs = new NodeList<>();
                for (IntSpan s : argSpans) {
                    String segRaw = raw.substring(openParen + 1 + s.start, openParen + 1 + s.end).trim();
                    if (segRaw.isEmpty()) continue;
                    int eq = segRaw.indexOf('=');
                    if (eq > 0) {
                        String k = segRaw.substring(0, eq).trim();
                        String v = segRaw.substring(eq + 1).trim();
                        if (isValidSimpleIdentifier(k)) {
                            MemberValuePair mvp = new MemberValuePair(k, parseAnnotationValue(v, annoRange));
                            mvp.setRange(annoRange);
                            pairs.add(mvp);
                        }
                    } else {
                        MemberValuePair mvp = new MemberValuePair("value", parseAnnotationValue(segRaw, annoRange));
                        mvp.setRange(annoRange);
                        pairs.add(mvp);
                    }
                }
                created = new NormalAnnotationExpr(parsedName, pairs);
            }
        }
        created.setRange(annoRange);
        out.add(created);
        return pos;
    }

    private static Expression parseAnnotationValue(String rawVal, Range range) {
        String trimmed = rawVal.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length() >= 2) {
            String inner = trimmed.substring(1, trimmed.length() - 1).trim();
            NodeList<Expression> values = new NodeList<>();
            if (!inner.isEmpty()) {
                for (String part : inner.split(",")) {
                    values.add(parseAnnotationValue(part.trim(), range));
                }
            }
            ArrayInitializerExpr arr = new ArrayInitializerExpr(values);
            arr.setRange(range);
            return arr;
        }
        if (trimmed.startsWith("arrayOf(") && trimmed.endsWith(")")) {
            String inner = trimmed.substring("arrayOf(".length(), trimmed.length() - 1).trim();
            NodeList<Expression> values = new NodeList<>();
            if (!inner.isEmpty()) {
                for (String part : inner.split(",")) {
                    values.add(parseAnnotationValue(part.trim(), range));
                }
            }
            ArrayInitializerExpr arr = new ArrayInitializerExpr(values);
            arr.setRange(range);
            return arr;
        }
        if (trimmed.endsWith("::class") || trimmed.endsWith("::class.java")) {
            String typeName = trimmed.substring(0, trimmed.indexOf("::class")).trim();
            ClassExpr ce = new ClassExpr(parseClassType(typeName, range, Map.of()));
            ce.setRange(range);
            return ce;
        }
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
            String content = sanitizeStringLiteralContent(trimmed.substring(1, trimmed.length() - 1));
            StringLiteralExpr s = new StringLiteralExpr(content);
            s.setRange(range);
            return s;
        }
        if (trimmed.equals("true") || trimmed.equals("false")) {
            BooleanLiteralExpr b = new BooleanLiteralExpr(Boolean.parseBoolean(trimmed));
            b.setRange(range);
            return b;
        }
        if (trimmed.matches("-?[0-9]+")) {
            IntegerLiteralExpr i = new IntegerLiteralExpr(trimmed);
            i.setRange(range);
            return i;
        }
        if (trimmed.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")) {
            return buildScopeExpression(trimmed, range, Map.of());
        }
        if (isValidSimpleIdentifier(trimmed)) {
            NameExpr ne = new NameExpr(new SimpleName(trimmed));
            ne.setRange(range);
            return ne;
        }
        StringLiteralExpr fallback = new StringLiteralExpr(sanitizeStringLiteralContent(trimmed));
        fallback.setRange(range);
        return fallback;
    }

    static Type mapKotlinType(
            String rawType,
            Range range,
            Map<String, String> importAliases,
            boolean allowVoid) {
        if (rawType == null) return parseClassType("Object", range, importAliases);
        String s = rawType.trim();
        // Strip inline annotations or suspend modifier
        while (s.startsWith("@")) {
            int sp = s.indexOf(' ');
            if (sp < 0) break;
            s = s.substring(sp + 1).trim();
        }
        if (s.startsWith("suspend ")) {
            s = s.substring("suspend ".length()).trim();
        }
        // Function type `(A, B) -> R`
        if (s.contains("->")) {
            return parseClassType("Object", range, importAliases);
        }
        while (s.startsWith("(") && s.endsWith(")") && s.length() > 2) {
            s = s.substring(1, s.length() - 1).trim();
        }
        boolean nullable = s.endsWith("?");
        if (nullable) {
            s = s.substring(0, s.length() - 1).trim();
        }
        if (s.isEmpty() || s.equals("*")) {
            return parseClassType("Object", range, importAliases);
        }
        if (s.startsWith("in ")) s = s.substring(3).trim();
        else if (s.startsWith("out ")) s = s.substring(4).trim();

        if (allowVoid && (s.equals("Unit") || s.equals("kotlin.Unit") || s.equals("Nothing"))) {
            VoidType vt = new VoidType();
            vt.setRange(range);
            return vt;
        }

        if (!nullable) {
            PrimitiveType.Primitive prim = switch (s) {
                case "Int", "kotlin.Int" -> PrimitiveType.Primitive.INT;
                case "Long", "kotlin.Long" -> PrimitiveType.Primitive.LONG;
                case "Boolean", "kotlin.Boolean" -> PrimitiveType.Primitive.BOOLEAN;
                case "Double", "kotlin.Double" -> PrimitiveType.Primitive.DOUBLE;
                case "Float", "kotlin.Float" -> PrimitiveType.Primitive.FLOAT;
                case "Byte", "kotlin.Byte" -> PrimitiveType.Primitive.BYTE;
                case "Short", "kotlin.Short" -> PrimitiveType.Primitive.SHORT;
                case "Char", "kotlin.Char" -> PrimitiveType.Primitive.CHAR;
                default -> null;
            };
            if (prim != null) {
                PrimitiveType pt = new PrimitiveType(prim);
                pt.setRange(range);
                return pt;
            }
        }

        if (s.equals("IntArray")) return primitiveArray(PrimitiveType.Primitive.INT, range);
        if (s.equals("LongArray")) return primitiveArray(PrimitiveType.Primitive.LONG, range);
        if (s.equals("BooleanArray")) return primitiveArray(PrimitiveType.Primitive.BOOLEAN, range);
        if (s.equals("ByteArray")) return primitiveArray(PrimitiveType.Primitive.BYTE, range);
        if (s.equals("DoubleArray")) return primitiveArray(PrimitiveType.Primitive.DOUBLE, range);
        if (s.equals("FloatArray")) return primitiveArray(PrimitiveType.Primitive.FLOAT, range);
        if (s.equals("ShortArray")) return primitiveArray(PrimitiveType.Primitive.SHORT, range);
        if (s.equals("CharArray")) return primitiveArray(PrimitiveType.Primitive.CHAR, range);

        int lt = s.indexOf('<');
        int gt = s.lastIndexOf('>');
        String base = (lt > 0 && gt > lt) ? s.substring(0, lt).trim() : s;
        String argsPart = (lt > 0 && gt > lt) ? s.substring(lt + 1, gt).trim() : null;

        if ((base.equals("Array") || base.equals("kotlin.Array")) && argsPart != null) {
            Type elemType = mapKotlinType(argsPart, range, importAliases, false);
            ArrayType at = new ArrayType(elemType);
            at.setRange(range);
            return at;
        }

        String mappedBase = switch (base) {
            case "Any", "kotlin.Any", "Unit", "kotlin.Unit", "Nothing" -> "Object";
            case "Int", "kotlin.Int" -> "Integer";
            case "Long", "kotlin.Long" -> "Long";
            case "Boolean", "kotlin.Boolean" -> "Boolean";
            case "Double", "kotlin.Double" -> "Double";
            case "Float", "kotlin.Float" -> "Float";
            case "Byte", "kotlin.Byte" -> "Byte";
            case "Short", "kotlin.Short" -> "Short";
            case "Char", "kotlin.Char" -> "Character";
            case "CharSequence", "kotlin.CharSequence" -> "CharSequence";
            case "String", "kotlin.String" -> "String";
            case "Throwable", "kotlin.Throwable" -> "Throwable";
            case "MutableList", "List", "kotlin.collections.List", "kotlin.collections.MutableList" -> "java.util.List";
            case "MutableSet", "Set", "kotlin.collections.Set", "kotlin.collections.MutableSet" -> "java.util.Set";
            case "MutableCollection", "Collection", "kotlin.collections.Collection", "kotlin.collections.MutableCollection" -> "java.util.Collection";
            case "MutableIterable", "Iterable", "kotlin.collections.Iterable" -> "Iterable";
            case "MutableMap", "Map", "kotlin.collections.Map", "kotlin.collections.MutableMap" -> "java.util.Map";
            default -> importAliases.getOrDefault(base, base);
        };

        ClassOrInterfaceType cit = parseClassType(mappedBase, range, importAliases);
        if (argsPart != null && !argsPart.isEmpty()) {
            List<IntSpan> argSpans = splitTopLevel(argsPart, 0, argsPart.length(), ',');
            NodeList<Type> typeArgs = new NodeList<>();
            for (IntSpan span : argSpans) {
                String argSlice = argsPart.substring(span.start, span.end).trim();
                Type mappedArg = mapKotlinType(argSlice, range, importAliases, false);
                if (mappedArg instanceof PrimitiveType pt) {
                    mappedArg = parseClassType(pt.toBoxedType().getNameAsString(), range, importAliases);
                }
                typeArgs.add(mappedArg);
            }
            if (!typeArgs.isEmpty()) {
                cit.setTypeArguments(typeArgs);
            }
        }
        return cit;
    }

    private static ArrayType primitiveArray(PrimitiveType.Primitive p, Range range) {
        PrimitiveType pt = new PrimitiveType(p);
        pt.setRange(range);
        ArrayType at = new ArrayType(pt);
        at.setRange(range);
        return at;
    }

    private static ClassOrInterfaceType parseClassType(
            String typeName,
            Range range,
            Map<String, String> importAliases) {
        String cleaned = typeName == null ? "Object" : typeName.trim();
        int lt = cleaned.indexOf('<');
        if (lt > 0) cleaned = cleaned.substring(0, lt).trim();
        cleaned = importAliases.getOrDefault(cleaned, cleaned);
        if (!isValidQualifiedName(cleaned)) {
            cleaned = "Object";
        }
        String[] parts = cleaned.split("\\.");
        ClassOrInterfaceType current = null;
        for (String part : parts) {
            SimpleName sn = new SimpleName(part);
            sn.setRange(range);
            current = new ClassOrInterfaceType(current, sn, null);
            current.setRange(range);
        }
        return current;
    }

    private static Type inferExpressionType(String exprRaw, Range range, Map<String, String> importAliases) {
        String e = exprRaw == null ? "" : exprRaw.trim();
        if (e.startsWith("return ")) e = e.substring("return ".length()).trim();
        if (e.equals("true") || e.equals("false")) {
            PrimitiveType pt = new PrimitiveType(PrimitiveType.Primitive.BOOLEAN);
            pt.setRange(range);
            return pt;
        }
        if (e.startsWith("\"")) {
            return parseClassType("String", range, importAliases);
        }
        if (e.matches("-?[0-9]+")) {
            PrimitiveType pt = new PrimitiveType(PrimitiveType.Primitive.INT);
            pt.setRange(range);
            return pt;
        }
        if (e.matches("-?[0-9]+[Ll]")) {
            PrimitiveType pt = new PrimitiveType(PrimitiveType.Primitive.LONG);
            pt.setRange(range);
            return pt;
        }
        if (e.startsWith("listOf(") || e.startsWith("mutableListOf(") || e.startsWith("emptyList(")) {
            return parseClassType("java.util.List", range, importAliases);
        }
        if (e.startsWith("setOf(") || e.startsWith("mutableSetOf(") || e.startsWith("emptySet(")) {
            return parseClassType("java.util.Set", range, importAliases);
        }
        if (e.startsWith("mapOf(") || e.startsWith("mutableMapOf(") || e.startsWith("emptyMap(")) {
            return parseClassType("java.util.Map", range, importAliases);
        }
        int openParen = e.indexOf('(');
        if (openParen > 0) {
            String head = e.substring(0, openParen).trim();
            int lt = head.indexOf('<');
            if (lt > 0) head = head.substring(0, lt).trim();
            if (isValidQualifiedName(head)) {
                String lastSeg = head.substring(head.lastIndexOf('.') + 1);
                if (!lastSeg.isEmpty() && Character.isUpperCase(lastSeg.charAt(0))) {
                    return parseClassType(head, range, importAliases);
                }
            }
        }
        return parseClassType("Object", range, importAliases);
    }

    private static void parseTypeParameters(String inner, Range range, List<TypeParameter> out) {
        List<IntSpan> spans = splitTopLevel(inner, 0, inner.length(), ',');
        for (IntSpan span : spans) {
            String item = inner.substring(span.start, span.end).trim();
            if (item.startsWith("reified ")) item = item.substring("reified ".length()).trim();
            if (item.startsWith("in ")) item = item.substring(3).trim();
            else if (item.startsWith("out ")) item = item.substring(4).trim();
            int colon = item.indexOf(':');
            String name = (colon > 0 ? item.substring(0, colon) : item).trim();
            if (isValidSimpleIdentifier(name)) {
                TypeParameter tp = new TypeParameter(name);
                tp.setRange(range);
                tp.getName().setRange(range);
                out.add(tp);
            }
        }
    }

    private static NodeList<Modifier> visibilityModifiers(Set<String> modifiers, boolean isStatic) {
        NodeList<Modifier> list = new NodeList<>();
        if (modifiers.contains("private")) list.add(Modifier.privateModifier());
        else if (modifiers.contains("protected")) list.add(Modifier.protectedModifier());
        else list.add(Modifier.publicModifier());
        if (isStatic) list.add(Modifier.staticModifier());
        return list;
    }

    private static String deriveFileFacadeName(String path, String raw) {
        Matcher m = FILE_JVM_NAME.matcher(raw);
        if (m.find() && isValidSimpleIdentifier(m.group(1))) {
            return m.group(1);
        }
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        if (fileName.endsWith(".kt")) {
            fileName = fileName.substring(0, fileName.length() - 3);
        }
        StringBuilder sb = new StringBuilder();
        boolean capNext = true;
        for (int i = 0; i < fileName.length(); i++) {
            char c = fileName.charAt(i);
            if (Character.isJavaIdentifierPart(c)) {
                sb.append(capNext ? Character.toUpperCase(c) : c);
                capNext = false;
            } else {
                capNext = true;
            }
        }
        if (sb.isEmpty() || !Character.isJavaIdentifierStart(sb.charAt(0))) {
            sb.insert(0, "KotlinFile");
        }
        sb.append("Kt");
        return sb.toString();
    }

    private static Name parseNameWithRange(String qualified, Range range) {
        String[] parts = qualified.split("\\.");
        Name current = null;
        for (String p : parts) {
            current = current == null ? new Name(p) : new Name(current, p);
            current.setRange(range);
        }
        return current != null ? current : new Name("unnamed");
    }

    private static void ensureAllRanges(CompilationUnit cu, Range fallback) {
        cu.walk(node -> {
            Range r = node.getRange().orElseGet(() -> node.getParentNode().flatMap(Node::getRange).orElse(fallback));
            node.setTokenRange(null);
            node.setRange(r);
        });
    }

    private static String maskCommentsAndStrings(String raw) {
        char[] out = raw.toCharArray();
        int i = 0;
        int n = out.length;
        while (i < n) {
            char c = out[i];
            if (c == '/' && i + 1 < n && out[i + 1] == '/') {
                out[i++] = ' ';
                out[i++] = ' ';
                while (i < n && out[i] != '\n' && out[i] != '\r') {
                    out[i++] = ' ';
                }
            } else if (c == '/' && i + 1 < n && out[i + 1] == '*') {
                out[i++] = ' ';
                out[i++] = ' ';
                int depth = 1;
                while (i < n && depth > 0) {
                    if (out[i] == '/' && i + 1 < n && out[i + 1] == '*') {
                        out[i++] = ' ';
                        out[i++] = ' ';
                        depth++;
                    } else if (out[i] == '*' && i + 1 < n && out[i + 1] == '/') {
                        out[i++] = ' ';
                        out[i++] = ' ';
                        depth--;
                    } else {
                        if (out[i] != '\n' && out[i] != '\r') out[i] = ' ';
                        i++;
                    }
                }
            } else if (c == '"' && i + 2 < n && out[i + 1] == '"' && out[i + 2] == '"') {
                // Keep outer quote chars for literal detection, blank interior
                i += 3;
                out[i - 2] = ' ';
                out[i - 1] = ' ';
                while (i < n) {
                    if (out[i] == '"' && i + 2 < n && out[i + 1] == '"' && out[i + 2] == '"') {
                        out[i] = ' ';
                        out[i + 1] = ' ';
                        // out[i + 2] stays '"'
                        i += 3;
                        break;
                    }
                    if (out[i] != '\n' && out[i] != '\r') out[i] = ' ';
                    i++;
                }
            } else if (c == '"') {
                i++;
                while (i < n && out[i] != '"' && out[i] != '\n' && out[i] != '\r') {
                    if (out[i] == '\\' && i + 1 < n) {
                        out[i++] = ' ';
                    }
                    if (i < n && out[i] != '\n' && out[i] != '\r') {
                        out[i++] = ' ';
                    }
                }
                if (i < n && out[i] == '"') i++;
            } else if (c == '\'') {
                out[i++] = ' ';
                while (i < n && out[i] != '\'' && out[i] != '\n' && out[i] != '\r') {
                    if (out[i] == '\\' && i + 1 < n) out[i++] = ' ';
                    if (i < n && out[i] != '\n' && out[i] != '\r') out[i++] = ' ';
                }
                if (i < n && out[i] == '\'') out[i++] = ' ';
            } else {
                i++;
            }
        }
        return new String(out);
    }

    private static int skipWhitespace(String clean, int pos, int end) {
        while (pos < end && Character.isWhitespace(clean.charAt(pos))) pos++;
        return pos;
    }

    private static int skipWhitespaceAndDelimiters(String clean, int pos, int end) {
        while (pos < end && (Character.isWhitespace(clean.charAt(pos)) || clean.charAt(pos) == ';')) pos++;
        return pos;
    }

    private static int skipWhitespaceAndAnnotations(
            String raw, String clean, int pos, int end, LineIndex index, List<AnnotationExpr> out) {
        while (pos < end) {
            pos = skipWhitespace(clean, pos, end);
            if (pos < end && clean.charAt(pos) == '@') {
                int next = consumeAnnotation(raw, clean, pos, end, index, out);
                if (next <= pos) break;
                pos = next;
            } else {
                break;
            }
        }
        return pos;
    }

    private static String readIdentifier(String text, int pos, int end) {
        if (pos >= end) return "";
        if (text.charAt(pos) == '`') {
            int close = text.indexOf('`', pos + 1);
            if (close > pos && close < end) {
                return text.substring(pos, close + 1);
            }
        }
        if (!Character.isJavaIdentifierStart(text.charAt(pos))) return "";
        int i = pos + 1;
        while (i < end && Character.isJavaIdentifierPart(text.charAt(i))) i++;
        return text.substring(pos, i);
    }

    private static int findMatchingBracket(String clean, int openPos, int limit, char openChar, char closeChar) {
        int depth = 0;
        for (int i = openPos; i < limit; i++) {
            char c = clean.charAt(i);
            if (c == openChar) depth++;
            else if (c == closeChar) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static int findHeaderEnd(String clean, int start, int limit) {
        int paren = 0;
        int angle = 0;
        int bracket = 0;
        for (int i = start; i < limit; i++) {
            char c = clean.charAt(i);
            if (c == '(') paren++;
            else if (c == ')') paren = Math.max(0, paren - 1);
            else if (c == '<') angle++;
            else if (c == '>') angle = Math.max(0, angle - 1);
            else if (c == '[') bracket++;
            else if (c == ']') bracket = Math.max(0, bracket - 1);
            else if (paren == 0 && angle == 0 && bracket == 0) {
                if (c == '{' || c == ';') return i;
                if (c == '\n' || c == '\r') {
                    int nextNonWs = skipWhitespace(clean, i + 1, limit);
                    if (nextNonWs < limit) {
                        char nc = clean.charAt(nextNonWs);
                        if (nc == ',' || nc == ':') continue;
                    }
                    int prevNonWs = i - 1;
                    while (prevNonWs >= start && (clean.charAt(prevNonWs) == ' ' || clean.charAt(prevNonWs) == '\t')) {
                        prevNonWs--;
                    }
                    if (prevNonWs >= start && (clean.charAt(prevNonWs) == ',' || clean.charAt(prevNonWs) == ':')) {
                        continue;
                    }
                    if (nextNonWs < limit && clean.charAt(nextNonWs) == '{') {
                        return nextNonWs;
                    }
                    return i;
                }
            }
        }
        return limit;
    }

    private static int findTopLevelCharInHeader(String clean, int start, int limit, char target) {
        int angle = 0;
        for (int i = start; i < limit; i++) {
            char c = clean.charAt(i);
            if (c == '<') angle++;
            else if (c == '>') angle = Math.max(0, angle - 1);
            else if (angle == 0) {
                if (c == target) return i;
                if (c == '{' || c == ';' || c == '=') return -1;
            }
        }
        return -1;
    }

    private static int findReturnTypeEnd(String clean, int start, int limit) {
        int angle = 0;
        int paren = 0;
        for (int i = start; i < limit; i++) {
            char c = clean.charAt(i);
            if (c == '<') angle++;
            else if (c == '>') angle = Math.max(0, angle - 1);
            else if (c == '(') paren++;
            else if (c == ')') paren = Math.max(0, paren - 1);
            else if (angle == 0 && paren == 0) {
                if (c == '{' || c == '=' || c == ';' || c == '\n' || c == '\r') return i;
                if (readIdentifier(clean, i, limit).equals("where")
                        && (i == start || !Character.isJavaIdentifierPart(clean.charAt(i - 1)))) {
                    return i;
                }
            }
        }
        return limit;
    }

    private static int findExpressionStatementEnd(String clean, int start, int limit) {
        int paren = 0;
        int brace = 0;
        int bracket = 0;
        for (int i = start; i < limit; i++) {
            char c = clean.charAt(i);
            if (c == '(') paren++;
            else if (c == ')') paren = Math.max(0, paren - 1);
            else if (c == '{') brace++;
            else if (c == '}') {
                if (brace == 0) return i;
                brace--;
            } else if (c == '[') bracket++;
            else if (c == ']') bracket = Math.max(0, bracket - 1);
            else if (paren == 0 && brace == 0 && bracket == 0) {
                if (c == ';') return i + 1;
                if (c == '\n' || c == '\r') {
                    int prev = i - 1;
                    while (prev >= start && (clean.charAt(prev) == ' ' || clean.charAt(prev) == '\t')) prev--;
                    if (prev >= start && Set.of('.', '+', '-', '*', '/', '=', ',', '?', ':').contains(clean.charAt(prev))) {
                        continue;
                    }
                    int next = skipWhitespace(clean, i + 1, limit);
                    if (next < limit && (clean.charAt(next) == '.' || clean.charAt(next) == '?')) {
                        continue;
                    }
                    return i;
                }
            }
        }
        return limit;
    }

    private static int findTopLevelChar(String clean, int start, int end, char target) {
        int paren = 0;
        int angle = 0;
        int brace = 0;
        int bracket = 0;
        for (int i = start; i < end; i++) {
            char c = clean.charAt(i);
            if (c == '(') paren++;
            else if (c == ')') paren = Math.max(0, paren - 1);
            else if (c == '<') angle++;
            else if (c == '>') angle = Math.max(0, angle - 1);
            else if (c == '{') brace++;
            else if (c == '}') brace = Math.max(0, brace - 1);
            else if (c == '[') bracket++;
            else if (c == ']') bracket = Math.max(0, bracket - 1);
            else if (paren == 0 && angle == 0 && brace == 0 && bracket == 0 && c == target) {
                if (target == '=' && ((i + 1 < end && clean.charAt(i + 1) == '=')
                        || (i > start && (clean.charAt(i - 1) == '!' || clean.charAt(i - 1) == '<' || clean.charAt(i - 1) == '>')))) {
                    continue;
                }
                return i;
            }
        }
        return -1;
    }

    private static record IntSpan(int start, int end) {}

    private static List<IntSpan> splitTopLevel(String clean, int start, int end, char delimiter) {
        List<IntSpan> list = new ArrayList<>();
        int paren = 0;
        int angle = 0;
        int brace = 0;
        int bracket = 0;
        int segStart = start;
        for (int i = start; i < end; i++) {
            char c = clean.charAt(i);
            if (c == '(') paren++;
            else if (c == ')') paren = Math.max(0, paren - 1);
            else if (c == '<') angle++;
            else if (c == '>') angle = Math.max(0, angle - 1);
            else if (c == '{') brace++;
            else if (c == '}') brace = Math.max(0, brace - 1);
            else if (c == '[') bracket++;
            else if (c == ']') bracket = Math.max(0, bracket - 1);
            else if (paren == 0 && angle == 0 && brace == 0 && bracket == 0 && c == delimiter) {
                if (i > segStart) list.add(new IntSpan(segStart, i));
                segStart = i + 1;
            }
        }
        if (end > segStart) list.add(new IntSpan(segStart, end));
        return list;
    }

    private static int findLastTopLevelDot(String s) {
        int angle = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == '>') angle++;
            else if (c == '<') angle = Math.max(0, angle - 1);
            else if (angle == 0 && c == '.') return i;
        }
        return -1;
    }

    private static int indexOfWord(String s, String word) {
        int idx = 0;
        while ((idx = s.indexOf(word, idx)) >= 0) {
            boolean leftOk = idx == 0 || !Character.isJavaIdentifierPart(s.charAt(idx - 1));
            int right = idx + word.length();
            boolean rightOk = right >= s.length() || !Character.isJavaIdentifierPart(s.charAt(right));
            if (leftOk && rightOk) return idx;
            idx = right;
        }
        return -1;
    }

    private static int skipToEndOfLineOrStatement(String clean, int pos, int end) {
        while (pos < end && clean.charAt(pos) != '\n' && clean.charAt(pos) != '\r' && clean.charAt(pos) != ';') {
            pos++;
        }
        return pos < end ? pos + 1 : end;
    }

    private static int advancePastUnknownItem(String clean, int pos, int end) {
        if (pos >= end) return end;
        char c = clean.charAt(pos);
        if (c == '{') {
            int close = findMatchingBracket(clean, pos, end, '{', '}');
            return close > pos ? close + 1 : pos + 1;
        }
        if (c == '(') {
            int close = findMatchingBracket(clean, pos, end, '(', ')');
            return close > pos ? close + 1 : pos + 1;
        }
        while (pos < end && clean.charAt(pos) != '\n' && clean.charAt(pos) != '\r' && clean.charAt(pos) != ';' && clean.charAt(pos) != '{') {
            pos++;
        }
        if (pos < end && clean.charAt(pos) == '{') {
            int close = findMatchingBracket(clean, pos, end, '{', '}');
            return close > pos ? close + 1 : pos + 1;
        }
        return pos < end ? pos + 1 : end;
    }

    private static String stripBackticks(String name) {
        String s = name.trim();
        if (s.startsWith("`") && s.endsWith("`") && s.length() > 2) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    private static boolean isValidSimpleIdentifier(String s) {
        if (s == null || s.isEmpty() || CONTROL_KEYWORDS.contains(s)) return false;
        if (!Character.isJavaIdentifierStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) return false;
        }
        return true;
    }

    private static boolean isValidQualifiedName(String s) {
        if (s == null || s.isEmpty()) return false;
        String[] parts = s.split("\\.", -1);
        for (String p : parts) {
            if (!isValidSimpleIdentifier(p)) return false;
        }
        return true;
    }

    private static String sanitizeStringLiteralContent(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                if (next == '$') {
                    sb.append('$');
                    i++;
                    continue;
                }
            }
            if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '"') sb.append("\\\"");
            else sb.append(c);
        }
        return sb.toString();
    }

    private static final class LineIndex {
        private final String text;
        private final List<Integer> lines = new ArrayList<>();

        LineIndex(String text) {
            this.text = text;
            lines.add(0);
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\r') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                    lines.add(i + 1);
                } else if (c == '\n') {
                    lines.add(i + 1);
                }
            }
        }

        Range range(int rawStart, int rawEnd) {
            int maxIdx = Math.max(0, text.length() - 1);
            int s = Math.max(0, Math.min(rawStart, maxIdx));
            int e = Math.max(s, Math.min(rawEnd, maxIdx));
            Position pStart = positionAt(s);
            Position pEnd = positionAt(e);
            return new Range(pStart, pEnd);
        }

        private Position positionAt(int offset) {
            int idx = java.util.Collections.binarySearch(lines, offset);
            int lineZero = idx >= 0 ? idx : -idx - 2;
            lineZero = Math.max(0, Math.min(lineZero, lines.size() - 1));
            int lineStart = lines.get(lineZero);
            int limit = lineZero == lines.size() - 1 ? text.length() : lines.get(lineZero + 1) - 1;
            int clamped = Math.max(lineStart, Math.min(offset, limit));
            int col = clamped - lineStart + 1;
            return new Position(lineZero + 1, Math.max(1, col));
        }
    }
}
