package com.evolution.backend.query.advisory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic multi-dialect SQL DDL extractor (ANSI SQL, PostgreSQL, T-SQL, Oracle) with
 * automatic Java/Spring JPA {@code @Entity} / {@code @Table} / {@code @JoinTable} matching
 * (including exact, schema-stripped, plural/singular, and {@code camelCase <-> snake_case}).
 */
public final class SqlDdlExtractor {
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?i)\\bCREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:GLOBAL\\s+)?(?:TEMP(?:ORARY)?\\s+|UNLOGGED\\s+)?TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([`\"\\[]?[A-Za-z0-9_$.]+[`\"\\]]?(?:\\.[`\"\\[]?[A-Za-z0-9_$]+[`\"\\]]?)?)");
    private static final Pattern CREATE_VIEW = Pattern.compile(
            "(?i)\\bCREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:MATERIALIZED\\s+)?VIEW\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([`\"\\[]?[A-Za-z0-9_$.]+[`\"\\]]?(?:\\.[`\"\\[]?[A-Za-z0-9_$]+[`\"\\]]?)?)");
    private static final Pattern CREATE_TRIGGER = Pattern.compile(
            "(?i)\\bCREATE\\s+(?:OR\\s+REPLACE\\s+)?TRIGGER\\s+([`\"\\[]?[A-Za-z0-9_$.]+[`\"\\]]?)\\b[\\s\\S]{0,240}?\\bON\\s+([`\"\\[]?[A-Za-z0-9_$.]+[`\"\\]]?(?:\\.[`\"\\[]?[A-Za-z0-9_$]+[`\"\\]]?)?)");
    private static final Pattern CREATE_ROUTINE = Pattern.compile(
            "(?i)\\bCREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:FUNCTION|PROCEDURE)\\s+([`\"\\[]?[A-Za-z0-9_$.]+[`\"\\]]?(?:\\.[`\"\\[]?[A-Za-z0-9_$]+[`\"\\]]?)?)");
    private static final Pattern ALTER_TABLE_FK = Pattern.compile(
            "(?i)\\bALTER\\s+TABLE\\s+(?:ONLY\\s+)?([`\"\\[]?[A-Za-z0-9_$.]+[`\"\\]]?(?:\\.[`\"\\[]?[A-Za-z0-9_$]+[`\"\\]]?)?)[\\s\\S]{0,400}?\\bREFERENCES\\s+([`\"\\[]?[A-Za-z0-9_$.]+[`\"\\]]?(?:\\.[`\"\\[]?[A-Za-z0-9_$]+[`\"\\]]?)?)");
    private static final Pattern INLINE_REFERENCES = Pattern.compile(
            "(?i)\\bREFERENCES\\s+([`\"\\[]?[A-Za-z0-9_$.]+[`\"\\]]?(?:\\.[`\"\\[]?[A-Za-z0-9_$]+[`\"\\]]?)?)");
    private static final Pattern JPA_ENTITY_ANNOTATION = Pattern.compile("@(?:jakarta\\.persistence\\.|javax\\.persistence\\.)?Entity\\b");
    private static final Pattern JPA_TABLE_ANNOTATION_START = Pattern.compile(
            "@(?:jakarta\\.persistence\\.|javax\\.persistence\\.)?(?:Table|SecondaryTable|JoinTable)\\s*\\(");
    private static final Pattern JPA_TOP_LEVEL_NAME_ATTR = Pattern.compile(
            "(?:^|[,\\s])name\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern JAVA_CLASS_DECL = Pattern.compile(
            "\\b(?:class|record)\\s+([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final Pattern PACKAGE_DECL = Pattern.compile(
            "(?m)^\\s*package\\s+([A-Za-z_][A-Za-z0-9_.]*)\\s*;?");

    public record JpaEntityInfo(
            String entityIdentity,
            String qualifiedName,
            String simpleName,
            List<String> explicitTableNames,
            String sourcePath) {
        public JpaEntityInfo {
            explicitTableNames = List.copyOf(explicitTableNames);
        }
    }

    public record ExtractionOutput(List<AdvisoryOverlay.AdvisoryNode> nodes,
                                   List<AdvisoryOverlay.AdvisoryLink> links) {}

    private SqlDdlExtractor() {}

    /**
     * Scans a Java/Kotlin source file for ALL JPA {@code @Entity} / {@code @Table} / {@code @SecondaryTable} /
     * {@code @JoinTable} class or record declarations (supporting multiple {@code @Entity} classes per file).
     */
    public static List<JpaEntityInfo> parseJpaEntitiesFromSource(
            List<MarkdownAdrExtractor.GraphSymbolRef> fileSymbols,
            String sourcePath,
            String javaText) {
        if (javaText == null || javaText.isBlank()) return List.of();
        if (!JPA_ENTITY_ANNOTATION.matcher(javaText).find() && !JPA_TABLE_ANNOTATION_START.matcher(javaText).find()) {
            return List.of();
        }
        String pkg = "";
        Matcher pm = PACKAGE_DECL.matcher(javaText);
        if (pm.find()) pkg = pm.group(1);

        var bySimple = new LinkedHashMap<String, MarkdownAdrExtractor.GraphSymbolRef>();
        if (fileSymbols != null) {
            for (var sym : fileSymbols) {
                if (sym == null || sym.label() == null || sym.label().isBlank()) continue;
                String lbl = sym.label();
                int dot = lbl.lastIndexOf('.');
                String s = dot >= 0 && dot < lbl.length() - 1 ? lbl.substring(dot + 1) : lbl;
                bySimple.putIfAbsent(s, sym);
            }
        }

        // Collect all class/record declaration sites outside strings/comments
        String stripped = stripJavaCommentsAndStringsForStructure(javaText);
        var decls = new ArrayList<ClassDeclSite>();
        Matcher cm = JAVA_CLASS_DECL.matcher(stripped);
        while (cm.find()) {
            String simple = cm.group(1);
            int openBrace = findClassBodyStart(stripped, cm.end());
            int closeBrace = openBrace >= 0 ? findMatchingBrace(stripped, openBrace) : -1;
            decls.add(new ClassDeclSite(simple, cm.start(), openBrace, closeBrace));
        }

        if (decls.isEmpty()) {
            var fallbackSym = (fileSymbols != null && !fileSymbols.isEmpty()) ? fileSymbols.getFirst() : null;
            String eid = fallbackSym != null ? fallbackSym.identity() : ("file:" + sourcePath);
            String qn = fallbackSym != null ? fallbackSym.label() : "";
            return parseJpaEntityFromSource(eid, qn, sourcePath, javaText).map(List::of).orElse(List.of());
        }

        var results = new ArrayList<JpaEntityInfo>();
        var seenSimple = new HashSet<String>();
        for (int i = 0; i < decls.size(); i++) {
            var d = decls.get(i);
            // Determine annotation header window: from end of previous sibling/statement boundary up to class keyword
            int headerStart = findAnnotationHeaderStart(stripped, d.declStart(), i > 0 ? decls.get(i - 1) : null);
            String headerRaw = javaText.substring(Math.max(0, headerStart), d.declStart());
            String bodyRaw = (d.openBrace() >= 0 && d.closeBrace() > d.openBrace())
                    ? extractDirectClassBodyExcludingNestedDecls(javaText, stripped, d, decls, i)
                    : "";
            boolean isEntity = JPA_ENTITY_ANNOTATION.matcher(headerRaw).find()
                    || JPA_TABLE_ANNOTATION_START.matcher(headerRaw).find()
                    || JPA_TABLE_ANNOTATION_START.matcher(bodyRaw).find();
            if (!isEntity || !seenSimple.add(d.simpleName())) continue;

            var tables = new LinkedHashSet<String>();
            tables.addAll(extractJpaTableNames(headerRaw));
            if (!bodyRaw.isEmpty()) {
                tables.addAll(extractJpaTableNames(bodyRaw));
            }
            var sym = bySimple.get(d.simpleName());
            String entityId = sym != null
                    ? sym.identity()
                    : ("file:" + sourcePath + "#" + d.simpleName());
            String qname = sym != null
                    ? sym.label()
                    : (pkg.isEmpty() ? d.simpleName() : (pkg + "." + d.simpleName()));
            results.add(new JpaEntityInfo(entityId, qname, d.simpleName(), List.copyOf(tables), sourcePath));
        }
        return List.copyOf(results);
    }

    private record ClassDeclSite(String simpleName, int declStart, int openBrace, int closeBrace) {}

    private static int findAnnotationHeaderStart(String stripped, int declStart, ClassDeclSite prev) {
        int floor = 0;
        if (prev != null) {
            if (prev.closeBrace() >= 0 && prev.closeBrace() < declStart) {
                floor = prev.closeBrace() + 1;
            } else if (prev.openBrace() >= 0 && prev.openBrace() < declStart) {
                // Nested class inside prev: start after prev's opening brace
                floor = prev.openBrace() + 1;
            } else {
                floor = prev.declStart();
            }
        }
        // Walk backwards from declStart to the previous ';' or '{' or '}' outside parens
        int parenDepth = 0;
        int braceDepth = 0;
        for (int p = declStart - 1; p >= floor; p--) {
            char c = stripped.charAt(p);
            if (c == ')') parenDepth++;
            else if (c == '(') parenDepth = Math.max(0, parenDepth - 1);
            else if (c == '}') {
                if (parenDepth == 0 && braceDepth == 0) return p + 1;
                braceDepth++;
            } else if (c == '{') {
                if (parenDepth == 0 && braceDepth == 0) return p + 1;
                braceDepth = Math.max(0, braceDepth - 1);
            } else if (c == ';' && parenDepth == 0 && braceDepth == 0) {
                return p + 1;
            }
        }
        return floor;
    }

    private static String extractDirectClassBodyExcludingNestedDecls(
            String raw, String stripped, ClassDeclSite self, List<ClassDeclSite> all, int selfIdx) {
        int start = self.openBrace() + 1;
        int end = self.closeBrace();
        if (start >= end) return "";
        var sb = new StringBuilder(end - start);
        int cursor = start;
        for (int j = selfIdx + 1; j < all.size(); j++) {
            var nested = all.get(j);
            if (nested.declStart() >= end) break;
            if (nested.declStart() < cursor) continue;
            int nestedHeader = Math.max(cursor, findAnnotationHeaderStart(stripped, nested.declStart(), self));
            if (nestedHeader > cursor) {
                sb.append(raw, cursor, nestedHeader);
            }
            cursor = (nested.closeBrace() >= 0 && nested.closeBrace() < end)
                    ? nested.closeBrace() + 1
                    : Math.min(end, nested.declStart() + nested.simpleName().length());
        }
        if (cursor < end) sb.append(raw, cursor, end);
        return sb.toString();
    }

    private static int findClassBodyStart(String s, int from) {
        int len = s.length();
        int paren = 0;
        boolean sawNewlineAfterHeader = false;
        for (int i = from; i < len; i++) {
            char c = s.charAt(i);
            if (c == '(') paren++;
            else if (c == ')') paren = Math.max(0, paren - 1);
            else if (c == '{' && paren == 0) return i;
            else if (c == ';' && paren == 0) return -1;
            else if (paren == 0 && (c == '\n' || c == '\r')) {
                sawNewlineAfterHeader = true;
            } else if (paren == 0 && c == '@' && sawNewlineAfterHeader) {
                return -1; // Bodyless Kotlin declaration followed by next annotated declaration
            } else if (paren == 0 && sawNewlineAfterHeader && Character.isJavaIdentifierStart(c)) {
                int end = i;
                while (end < len && Character.isJavaIdentifierPart(s.charAt(end))) end++;
                String word = s.substring(i, end);
                if (Set.of("class", "record", "interface", "enum", "object", "data", "sealed",
                        "public", "private", "protected", "internal", "abstract", "final", "open", "fun").contains(word)) {
                    return -1;
                }
                i = end - 1;
            }
        }
        return -1;
    }

    private static int findMatchingBrace(String s, int openBrace) {
        int depth = 0;
        for (int i = openBrace; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static String stripJavaCommentsAndStringsForStructure(String raw) {
        int len = raw.length();
        var sb = new StringBuilder(len);
        int i = 0;
        while (i < len) {
            char c = raw.charAt(i);
            if (c == '/' && i + 1 < len && raw.charAt(i + 1) == '/') {
                sb.append("  ");
                i += 2;
                while (i < len && raw.charAt(i) != '\n') {
                    sb.append(' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < len && raw.charAt(i + 1) == '*') {
                sb.append("  ");
                i += 2;
                while (i + 1 < len && !(raw.charAt(i) == '*' && raw.charAt(i + 1) == '/')) {
                    sb.append(raw.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i + 1 < len) {
                    sb.append("  ");
                    i += 2;
                }
            } else if (c == '"' || c == '\'') {
                char q = c;
                sb.append(' ');
                i++;
                while (i < len) {
                    char ch = raw.charAt(i);
                    sb.append(ch == '\n' ? '\n' : ' ');
                    if (ch == '\\' && i + 1 < len) {
                        i++;
                        sb.append(raw.charAt(i) == '\n' ? '\n' : ' ');
                    } else if (ch == q) {
                        i++;
                        break;
                    }
                    i++;
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /** Scans a Java/Kotlin source file for JPA {@code @Entity} and {@code @Table(name="...")} declarations. */
    public static Optional<JpaEntityInfo> parseJpaEntityFromSource(
            String entityIdentity, String qualifiedName, String sourcePath, String javaText) {
        if (javaText == null || javaText.isBlank()) return Optional.empty();
        List<String> explicitTables = extractJpaTableNames(javaText);
        boolean hasTableAnnotation = JPA_TABLE_ANNOTATION_START.matcher(javaText).find();
        if (!JPA_ENTITY_ANNOTATION.matcher(javaText).find() && !hasTableAnnotation) {
            return Optional.empty();
        }
        String simpleName = qualifiedName != null && qualifiedName.contains(".")
                ? qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1)
                : qualifiedName;
        if (simpleName == null || simpleName.isBlank()) {
            Matcher cm = JAVA_CLASS_DECL.matcher(javaText);
            if (cm.find()) simpleName = cm.group(1);
        }
        if (simpleName == null || simpleName.isBlank()) return Optional.empty();
        return Optional.of(new JpaEntityInfo(
                entityIdentity,
                qualifiedName == null || qualifiedName.isBlank() ? simpleName : qualifiedName,
                simpleName,
                explicitTables,
                sourcePath));
    }

    /**
     * Parses top-level {@code name = "..."} attributes of {@code @Table}, {@code @SecondaryTable}, and
     * {@code @JoinTable} annotations while ignoring nested annotations such as {@code @Index(name = "...")}.
     */
    private static List<String> extractJpaTableNames(String javaText) {
        var tableNames = new LinkedHashSet<String>();
        Matcher m = JPA_TABLE_ANNOTATION_START.matcher(javaText);
        int len = javaText.length();
        while (m.find()) {
            int pos = m.end();
            int parenDepth = 1;
            int braceDepth = 0;
            boolean inStr = false;
            boolean esc = false;
            var topLevel = new StringBuilder();
            while (pos < len && parenDepth > 0) {
                char c = javaText.charAt(pos);
                if (inStr) {
                    if (parenDepth == 1 && braceDepth == 0) topLevel.append(c);
                    if (esc) esc = false;
                    else if (c == '\\') esc = true;
                    else if (c == '"') inStr = false;
                    pos++;
                    continue;
                }
                if (c == '"') {
                    inStr = true;
                    if (parenDepth == 1 && braceDepth == 0) topLevel.append(c);
                } else if (c == '(') {
                    parenDepth++;
                } else if (c == ')') {
                    parenDepth--;
                    if (parenDepth == 0) break;
                } else if (c == '{') {
                    braceDepth++;
                } else if (c == '}') {
                    braceDepth = Math.max(0, braceDepth - 1);
                } else if (parenDepth == 1 && braceDepth == 0) {
                    topLevel.append(c);
                }
                pos++;
            }
            Matcher nm = JPA_TOP_LEVEL_NAME_ATTR.matcher(topLevel.toString());
            if (nm.find()) {
                String t = cleanIdentifier(nm.group(1));
                if (!t.isEmpty()) tableNames.add(t);
            }
        }
        return List.copyOf(tableNames);
    }

    public static ExtractionOutput extract(
            String path,
            String contentSha256,
            String rawSql,
            List<JpaEntityInfo> jpaEntities) {
        if (rawSql == null || rawSql.isBlank()) {
            return new ExtractionOutput(List.of(), List.of());
        }
        String cleanedSql = stripSqlCommentsPreserveLines(rawSql);
        var nodes = new ArrayList<AdvisoryOverlay.AdvisoryNode>();
        var links = new ArrayList<AdvisoryOverlay.AdvisoryLink>();
        var tableNodeByBareName = new LinkedHashMap<String, AdvisoryOverlay.AdvisoryNode>();
        var seenLinks = new HashSet<String>();

        // 1. CREATE TABLE statements (plus inline REFERENCES foreign keys)
        Matcher tblMatcher = CREATE_TABLE.matcher(cleanedSql);
        while (tblMatcher.find()) {
            String fullIdent = cleanIdentifier(tblMatcher.group(1));
            String bare = bareName(fullIdent);
            if (bare.isEmpty()) continue;
            int startLine = lineNumberAt(cleanedSql, tblMatcher.start());
            int stmtEnd = findStatementEnd(cleanedSql, tblMatcher.end());
            int endLine = lineNumberAt(cleanedSql, stmtEnd);
            String nodeId = MarkdownAdrExtractor.advisoryId("SQL_TABLE", path + ":" + bare, startLine);
            var meta = new LinkedHashMap<String, Object>();
            meta.put("tableName", bare);
            if (!fullIdent.equals(bare)) meta.put("qualifiedTableName", fullIdent);
            var node = new AdvisoryOverlay.AdvisoryNode(nodeId, "SQL_TABLE", fullIdent, path, startLine, endLine, contentSha256, meta);
            nodes.add(node);
            tableNodeByBareName.putIfAbsent(bare, node);

            // Inline REFERENCES inside CREATE TABLE body
            String body = cleanedSql.substring(tblMatcher.end(), Math.min(cleanedSql.length(), stmtEnd));
            Matcher refMatcher = INLINE_REFERENCES.matcher(body);
            while (refMatcher.find()) {
                String refFull = cleanIdentifier(refMatcher.group(1));
                String refBare = bareName(refFull);
                if (refBare.isEmpty() || refBare.equals(bare)) continue;
                int refLine = lineNumberAt(cleanedSql, tblMatcher.end() + refMatcher.start());
                String key = bare + "|FOREIGN_KEY|" + refBare;
                if (seenLinks.add(key)) {
                    links.add(new AdvisoryOverlay.AdvisoryLink(
                            MarkdownAdrExtractor.advisoryId("FOREIGN_KEY", path + ":" + key, refLine),
                            "FOREIGN_KEY",
                            nodeId, fullIdent,
                            "sql:table:" + refBare, refFull, "SQL_TABLE",
                            path, refLine, refLine, contentSha256, "ddl-inline-references"));
                }
            }
        }

        // 2. ALTER TABLE ... FOREIGN KEY ... REFERENCES ...
        Matcher alterFk = ALTER_TABLE_FK.matcher(cleanedSql);
        while (alterFk.find()) {
            String srcFull = cleanIdentifier(alterFk.group(1));
            String dstFull = cleanIdentifier(alterFk.group(2));
            String srcBare = bareName(srcFull);
            String dstBare = bareName(dstFull);
            if (srcBare.isEmpty() || dstBare.isEmpty() || srcBare.equals(dstBare)) continue;
            int line = lineNumberAt(cleanedSql, alterFk.start());
            String srcId = tableNodeByBareName.containsKey(srcBare)
                    ? tableNodeByBareName.get(srcBare).id()
                    : "sql:table:" + srcBare;
            String key = srcBare + "|FOREIGN_KEY|" + dstBare;
            if (seenLinks.add(key)) {
                links.add(new AdvisoryOverlay.AdvisoryLink(
                        MarkdownAdrExtractor.advisoryId("FOREIGN_KEY", path + ":" + key, line),
                        "FOREIGN_KEY",
                        srcId, srcFull,
                        "sql:table:" + dstBare, dstFull, "SQL_TABLE",
                        path, line, line, contentSha256, "ddl-alter-table-fk"));
            }
        }

        // 3. CREATE VIEW
        Matcher viewMatcher = CREATE_VIEW.matcher(cleanedSql);
        while (viewMatcher.find()) {
            String fullIdent = cleanIdentifier(viewMatcher.group(1));
            String bare = bareName(fullIdent);
            if (bare.isEmpty()) continue;
            int startLine = lineNumberAt(cleanedSql, viewMatcher.start());
            int endLine = lineNumberAt(cleanedSql, findStatementEnd(cleanedSql, viewMatcher.end()));
            nodes.add(new AdvisoryOverlay.AdvisoryNode(
                    MarkdownAdrExtractor.advisoryId("SQL_VIEW", path + ":" + bare, startLine),
                    "SQL_VIEW", fullIdent, path, startLine, endLine, contentSha256,
                    Map.of("viewName", bare)));
        }

        // 4. CREATE TRIGGER
        Matcher trigMatcher = CREATE_TRIGGER.matcher(cleanedSql);
        while (trigMatcher.find()) {
            String trigName = cleanIdentifier(trigMatcher.group(1));
            String targetTbl = cleanIdentifier(trigMatcher.group(2));
            String targetBare = bareName(targetTbl);
            if (trigName.isEmpty()) continue;
            int startLine = lineNumberAt(cleanedSql, trigMatcher.start());
            int endLine = lineNumberAt(cleanedSql, findStatementEnd(cleanedSql, trigMatcher.end()));
            String trigId = MarkdownAdrExtractor.advisoryId("SQL_TRIGGER", path + ":" + trigName, startLine);
            nodes.add(new AdvisoryOverlay.AdvisoryNode(
                    trigId, "SQL_TRIGGER", trigName, path, startLine, endLine, contentSha256,
                    Map.of("targetTable", targetBare)));
            if (!targetBare.isEmpty()) {
                String key = trigId + "|SQL_TRIGGER_ON|" + targetBare;
                if (seenLinks.add(key)) {
                    links.add(new AdvisoryOverlay.AdvisoryLink(
                            MarkdownAdrExtractor.advisoryId("SQL_TRIGGER_ON", path + ":" + key, startLine),
                            "SQL_TRIGGER_ON", trigId, trigName,
                            "sql:table:" + targetBare, targetTbl, "SQL_TABLE",
                            path, startLine, startLine, contentSha256, "ddl-trigger-on"));
                }
            }
        }

        // 5. CREATE FUNCTION / PROCEDURE
        Matcher routineMatcher = CREATE_ROUTINE.matcher(cleanedSql);
        while (routineMatcher.find()) {
            String fullIdent = cleanIdentifier(routineMatcher.group(1));
            String bare = bareName(fullIdent);
            if (bare.isEmpty()) continue;
            int startLine = lineNumberAt(cleanedSql, routineMatcher.start());
            int endLine = lineNumberAt(cleanedSql, findStatementEnd(cleanedSql, routineMatcher.end()));
            nodes.add(new AdvisoryOverlay.AdvisoryNode(
                    MarkdownAdrExtractor.advisoryId("SQL_ROUTINE", path + ":" + bare, startLine),
                    "SQL_ROUTINE", fullIdent, path, startLine, endLine, contentSha256,
                    Map.of("routineName", bare)));
        }

        // 6. Deterministic SQL_TABLE <-> Java/Spring JPA @Entity matching (explicit @Table & camelCase <-> snake_case)
        if (jpaEntities != null && !jpaEntities.isEmpty() && !tableNodeByBareName.isEmpty()) {
            for (var tblEntry : tableNodeByBareName.entrySet()) {
                String tblBare = tblEntry.getKey();
                AdvisoryOverlay.AdvisoryNode tblNode = tblEntry.getValue();
                for (var jpa : jpaEntities) {
                    String matchBasis = matchJpaToTable(tblBare, jpa);
                    if (matchBasis == null) continue;
                    String key = tblNode.id() + "|SQL_TABLE_MAPS_JPA_ENTITY|" + jpa.entityIdentity();
                    if (seenLinks.add(key)) {
                        links.add(new AdvisoryOverlay.AdvisoryLink(
                                MarkdownAdrExtractor.advisoryId("SQL_TABLE_MAPS_JPA_ENTITY", path + ":" + key, tblNode.startLine()),
                                "SQL_TABLE_MAPS_JPA_ENTITY",
                                tblNode.id(), tblNode.label(),
                                jpa.entityIdentity(), jpa.qualifiedName(), "JPA_ENTITY",
                                path, tblNode.startLine(), tblNode.endLine(), contentSha256, matchBasis));
                    }
                }
            }
        }

        // Resolve intra-file synthetic sql:table:<bare> references to real SQL_TABLE node IDs
        var resolvedLinks = new ArrayList<AdvisoryOverlay.AdvisoryLink>(links.size());
        for (var l : links) {
            String srcId = resolveSqlTableId(l.sourceId(), tableNodeByBareName);
            String dstId = resolveSqlTableId(l.targetId(), tableNodeByBareName);
            if (!srcId.equals(l.sourceId()) || !dstId.equals(l.targetId())) {
                resolvedLinks.add(new AdvisoryOverlay.AdvisoryLink(
                        l.id(), l.kind(), srcId, l.sourceLabel(), dstId, l.targetLabel(), l.targetKind(),
                        l.sourcePath(), l.startLine(), l.endLine(), l.contentSha256(), l.matchBasis()));
            } else {
                resolvedLinks.add(l);
            }
        }

        return new ExtractionOutput(List.copyOf(nodes), List.copyOf(resolvedLinks));
    }

    private static String resolveSqlTableId(String id, Map<String, AdvisoryOverlay.AdvisoryNode> tableNodeByBareName) {
        if (id != null && id.startsWith("sql:table:")) {
            var targetNode = tableNodeByBareName.get(id.substring("sql:table:".length()));
            if (targetNode != null) return targetNode.id();
        }
        return id;
    }

    static String matchJpaToTable(String tableBareLower, JpaEntityInfo jpa) {
        String tbl = tableBareLower.toLowerCase(Locale.ROOT);
        for (String explicit : jpa.explicitTableNames()) {
            String expBare = bareName(explicit);
            if (expBare.equals(tbl)) {
                return "@Table(name=\"" + explicit + "\")";
            }
        }
        String snake = camelToSnake(jpa.simpleName());
        if (tbl.equals(snake)) return "camelCase<->snake_case(" + jpa.simpleName() + ")";
        if (tbl.equals(snake + "s") || tbl.equals(snake + "es")) {
            return "camelCase<->snake_case-plural(" + jpa.simpleName() + ")";
        }
        if (snake.endsWith("y") && tbl.equals(snake.substring(0, snake.length() - 1) + "ies")) {
            return "camelCase<->snake_case-plural(" + jpa.simpleName() + ")";
        }
        if (tbl.equals(jpa.simpleName().toLowerCase(Locale.ROOT))) {
            return "exact-entity-name(" + jpa.simpleName() + ")";
        }
        return null;
    }

    public static String camelToSnake(String camel) {
        if (camel == null || camel.isEmpty()) return "";
        var sb = new StringBuilder(camel.length() + 4);
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    char prev = camel.charAt(i - 1);
                    char next = (i + 1 < camel.length()) ? camel.charAt(i + 1) : 0;
                    if (Character.isLowerCase(prev) || Character.isDigit(prev)
                            || (Character.isUpperCase(prev) && next != 0 && Character.isLowerCase(next))) {
                        sb.append('_');
                    }
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String stripSqlCommentsPreserveLines(String sql) {
        var sb = new StringBuilder(sql.length());
        int len = sql.length();
        boolean inSingle = false;
        int i = 0;
        while (i < len) {
            char c = sql.charAt(i);
            if (inSingle) {
                if (c == '\'' && i + 1 < len && sql.charAt(i + 1) == '\'') {
                    sb.append("  ");
                    i += 2;
                    continue;
                }
                if (c == '\'') {
                    inSingle = false;
                    sb.append('\'');
                } else {
                    sb.append(c == '\n' ? '\n' : ' ');
                }
                i++;
                continue;
            }
            if (c == '\'') {
                inSingle = true;
                sb.append(c);
                i++;
            } else if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                while (i < len && sql.charAt(i) != '\n') {
                    sb.append(' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < len && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    sb.append(sql.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                i = Math.min(len, i + 2);
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private static int findStatementEnd(String sql, int from) {
        int semi = sql.indexOf(';', from);
        if (semi >= 0) return semi;
        return Math.min(sql.length(), from + 4096);
    }

    private static int lineNumberAt(String text, int offset) {
        int line = 1;
        int lim = Math.min(offset, text.length());
        for (int i = 0; i < lim; i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }

    private static String cleanIdentifier(String raw) {
        if (raw == null) return "";
        return raw.strip().replace("\"", "").replace("`", "").replace("[", "").replace("]", "").toLowerCase(Locale.ROOT);
    }

    private static String bareName(String cleaned) {
        int dot = cleaned.lastIndexOf('.');
        return dot >= 0 && dot < cleaned.length() - 1 ? cleaned.substring(dot + 1) : cleaned;
    }
}
