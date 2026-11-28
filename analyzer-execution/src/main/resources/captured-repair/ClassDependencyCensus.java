import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Pure-JDK (Java 8–21 compatible) bytecode class & dependency census plus Surefire/Gradle XML aggregator
 * and failing test detail extractor for isolated repair builds.
 * Emits CanonicalJson-compatible classCensus JSON: {"classCount":...,"classes":{...},"dependencies":{...}}.
 */
public final class ClassDependencyCensus {
    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();
    private static final int MAX_FAILURES = 30;
    private static final int MAX_MSG_CHARS = 400;

    private static final class TestFailure {
        private final String className;
        private final String methodName;
        private final String kind;
        private final String message;

        TestFailure(String className, String methodName, String kind, String message) {
            this.className = className;
            this.methodName = methodName;
            this.kind = kind;
            this.message = message;
        }

        String className() { return className; }
        String methodName() { return methodName; }
        String kind() { return kind; }
        String message() { return message; }
    }

    public static void main(String[] args) throws Exception {
        if (args.length >= 1 && "--behavioral".equals(args[0])) {
            emitBehavioralJson(args);
            return;
        }
        if (args.length < 2) {
            System.err.println("Usage: ClassDependencyCensus <repo-root> <census-out.json> [<summary-out.env>] [<failures-out.json>] [<fail-ids-out.txt>]");
            System.exit(2);
        }
        final Path repoRoot = Paths.get(args[0]).toAbsolutePath().normalize();
        Path jsonOut = Paths.get(args[1]).toAbsolutePath().normalize();
        Path envOut = args.length >= 3 ? Paths.get(args[2]).toAbsolutePath().normalize() : null;
        Path failuresOut = args.length >= 4 ? Paths.get(args[3]).toAbsolutePath().normalize() : null;
        Path failIdsOut = args.length >= 5 ? Paths.get(args[4]).toAbsolutePath().normalize() : null;

        final Map<String, String> classes = new TreeMap<String, String>();
        final Map<String, Set<String>> dependencies = new TreeMap<String, Set<String>>();
        int[] testCounts = new int[3]; // run, failed, errors
        List<TestFailure> failures = new ArrayList<TestFailure>();
        Set<String> failingIds = new TreeSet<String>();

        final List<Path> classDirs = new ArrayList<Path>();
        final List<Path> surefireDirs = new ArrayList<Path>();
        Files.walkFileTree(repoRoot, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (name.equals(".git") || name.equals("node_modules")) return FileVisitResult.SKIP_SUBTREE;
                Path rel = repoRoot.relativize(dir);
                String unix = rel.toString().replace('\\', '/');
                if (unix.equals("target/classes") || unix.endsWith("/target/classes")
                        || unix.equals("build/classes/java/main") || unix.endsWith("/build/classes/java/main")) {
                    classDirs.add(dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (unix.equals("target/surefire-reports") || unix.endsWith("/target/surefire-reports")
                        || unix.equals("target/failsafe-reports") || unix.endsWith("/target/failsafe-reports")
                        || unix.equals("build/test-results/test") || unix.endsWith("/build/test-results/test")
                        || unix.equals("build/test-results/integrationTest") || unix.endsWith("/build/test-results/integrationTest")) {
                    surefireDirs.add(dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });

        for (Path classDir : classDirs) {
            Files.walkFileTree(classDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String fname = file.getFileName().toString();
                    if (!fname.endsWith(".class") || fname.equals("module-info.class")) {
                        return FileVisitResult.CONTINUE;
                    }
                    byte[] bytes = Files.readAllBytes(file);
                    ParsedClass parsed = parseClassFile(bytes);
                    if (parsed != null && parsed.thisClass() != null) {
                        classes.put(parsed.thisClass(), sha256(bytes));
                        Set<String> deps = dependencies.get(parsed.thisClass());
                        if (deps == null) {
                            deps = new TreeSet<String>();
                            dependencies.put(parsed.thisClass(), deps);
                        }
                        deps.addAll(parsed.dependencies());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        for (Path reportDir : surefireDirs) {
            try (Stream<Path> stream = Files.list(reportDir)) {
                List<Path> files = stream.sorted().collect(Collectors.toList());
                for (Path file : files) {
                    String fname = file.getFileName().toString();
                    if (fname.startsWith("TEST-") && fname.endsWith(".xml") && Files.isRegularFile(file)) {
                        accumulateSurefire(file, testCounts, failures, failingIds);
                    }
                }
            }
        }

        String json = toCanonicalCensusJson(classes, dependencies);
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        if (jsonOut.getParent() != null) Files.createDirectories(jsonOut.getParent());
        Files.write(jsonOut, jsonBytes);
        String censusSha256 = sha256(jsonBytes);

        if (envOut != null) {
            if (envOut.getParent() != null) Files.createDirectories(envOut.getParent());
            String env = "TESTS_RUN=" + testCounts[0] + "\n"
                    + "TESTS_FAILED=" + testCounts[1] + "\n"
                    + "TESTS_ERRORS=" + testCounts[2] + "\n"
                    + "CLASS_COUNT=" + classes.size() + "\n"
                    + "CLASS_CENSUS_SHA256=" + censusSha256 + "\n";
            Files.write(envOut, env.getBytes(StandardCharsets.UTF_8));
        }

        if (failuresOut != null) {
            if (failuresOut.getParent() != null) Files.createDirectories(failuresOut.getParent());
            Files.write(failuresOut, toFailuresJson(failures).getBytes(StandardCharsets.UTF_8));
        }

        if (failIdsOut != null) {
            if (failIdsOut.getParent() != null) Files.createDirectories(failIdsOut.getParent());
            String joined = failingIds.isEmpty() ? "" : (String.join("\n", failingIds) + "\n");
            Files.write(failIdsOut, joined.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void emitBehavioralJson(String[] args) throws IOException {
        if (args.length < 11) {
            System.err.println("Usage: ClassDependencyCensus --behavioral <baseExit> <candExit> <baseRun> <baseFail> <candRun> <candFail> <outShaHex> <baseFailIds> <candFailIds> <outJson>");
            System.exit(2);
        }
        int baseExit = Integer.parseInt(args[1]);
        int candExit = Integer.parseInt(args[2]);
        int baseRun = Integer.parseInt(args[3]);
        int baseFail = Integer.parseInt(args[4]);
        int candRun = Integer.parseInt(args[5]);
        int candFail = Integer.parseInt(args[6]);
        String outShaHex = args[7].trim();
        List<String> baseIds = readFailIds(Paths.get(args[8]));
        List<String> candIds = readFailIds(Paths.get(args[9]));
        Path outJson = Paths.get(args[10]).toAbsolutePath().normalize();

        StringBuilder sb = new StringBuilder();
        sb.append("{\"baseExit\":").append(baseExit)
                .append(",\"baselineFailingTests\":");
        appendJsonStringArray(baseIds, sb);
        sb.append(",\"baselineTestsFailed\":").append(baseFail)
                .append(",\"baselineTestsRun\":").append(baseRun)
                .append(",\"candExit\":").append(candExit)
                .append(",\"candidateFailingTests\":");
        appendJsonStringArray(candIds, sb);
        sb.append(",\"candidateTestsFailed\":").append(candFail)
                .append(",\"candidateTestsRun\":").append(candRun)
                .append(",\"executed\":true,\"language\":\"java\",\"outputExcerpt\":\"\",\"outputSha256\":");
        appendJsonString("sha256:" + outShaHex, sb);
        sb.append('}');
        if (outJson.getParent() != null) Files.createDirectories(outJson.getParent());
        Files.write(outJson, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> readFailIds(Path file) {
        if (file == null || !Files.isRegularFile(file)) return Collections.emptyList();
        try {
            Set<String> set = new TreeSet<String>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) set.add(trimmed);
            }
            return Collections.unmodifiableList(new ArrayList<String>(set));
        } catch (IOException ignored) {
            return Collections.emptyList();
        }
    }

    private static void appendJsonStringArray(List<String> values, StringBuilder out) {
        out.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) out.append(',');
            appendJsonString(values.get(i), out);
        }
        out.append(']');
    }

    private static void accumulateSurefire(Path xml, int[] counts, List<TestFailure> failures, Set<String> failingIds) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            org.w3c.dom.Document doc = dbf.newDocumentBuilder().parse(xml.toFile());
            org.w3c.dom.Element root = doc.getDocumentElement();
            if (root != null && "testsuite".equals(root.getTagName())) {
                counts[0] += parseAttrInt(root.getAttribute("tests"));
                counts[1] += parseAttrInt(root.getAttribute("failures"));
                counts[2] += parseAttrInt(root.getAttribute("errors"));
                org.w3c.dom.NodeList cases = root.getElementsByTagName("testcase");
                for (int i = 0; i < cases.getLength(); i++) {
                    org.w3c.dom.Node tc = cases.item(i);
                    if (!(tc instanceof org.w3c.dom.Element)) continue;
                    org.w3c.dom.Element el = (org.w3c.dom.Element) tc;
                    String cls = el.getAttribute("classname");
                    String method = el.getAttribute("name");
                    org.w3c.dom.NodeList children = el.getChildNodes();
                    for (int j = 0; j < children.getLength(); j++) {
                        org.w3c.dom.Node child = children.item(j);
                        if (!(child instanceof org.w3c.dom.Element)) continue;
                        org.w3c.dom.Element cel = (org.w3c.dom.Element) child;
                        String tag = cel.getTagName();
                        if ("failure".equals(tag) || "error".equals(tag)) {
                            String safeCls = cls == null ? "" : cls.trim();
                            String safeMethod = method == null ? "" : method.trim();
                            String id = safeCls.isEmpty() ? safeMethod : (safeMethod.isEmpty() ? safeCls : (safeCls + "#" + safeMethod));
                            if (!id.isEmpty() && failingIds.size() < 500) {
                                failingIds.add(id);
                            }
                            if (failures.size() < MAX_FAILURES) {
                                String msg = cel.getAttribute("message");
                                if (msg == null || msg.trim().isEmpty()) msg = cel.getTextContent();
                                if (msg == null) msg = "";
                                msg = msg.replaceAll("\\s+", " ").trim();
                                if (msg.length() > MAX_MSG_CHARS) msg = msg.substring(0, MAX_MSG_CHARS) + "...";
                                failures.add(new TestFailure(safeCls, safeMethod,
                                        "error".equals(tag) ? "ERROR" : "FAILURE", msg));
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // Unreadable or non-standard XML report leaves counts at zero unless valid suites exist.
        }
    }

    private static String toFailuresJson(List<TestFailure> failures) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < failures.size(); i++) {
            if (i > 0) sb.append(',');
            TestFailure f = failures.get(i);
            sb.append("{\"className\":");
            appendJsonString(f.className(), sb);
            sb.append(",\"kind\":");
            appendJsonString(f.kind(), sb);
            sb.append(",\"message\":");
            appendJsonString(f.message(), sb);
            sb.append(",\"methodName\":");
            appendJsonString(f.methodName(), sb);
            sb.append('}');
        }
        return sb.append(']').toString();
    }

    private static int parseAttrInt(String raw) {
        if (raw == null || raw.trim().isEmpty()) return 0;
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static final class ParsedClass {
        private final String thisClass;
        private final Set<String> dependencies;

        ParsedClass(String thisClass, Set<String> dependencies) {
            this.thisClass = thisClass;
            this.dependencies = dependencies;
        }

        String thisClass() { return thisClass; }
        Set<String> dependencies() { return dependencies; }
    }

    private static ParsedClass parseClassFile(byte[] bytes) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            int magic = in.readInt();
            if (magic != 0xCAFEBABE) return null;
            in.readUnsignedShort(); // minor
            in.readUnsignedShort(); // major
            int cpCount = in.readUnsignedShort();

            String[] utf8 = new String[cpCount];
            int[] classNameIdx = new int[cpCount];
            int[] descriptorIdx = new int[cpCount];
            Arrays.fill(classNameIdx, -1);
            Arrays.fill(descriptorIdx, -1);

            for (int i = 1; i < cpCount; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1:
                        utf8[i] = in.readUTF();
                        break;
                    case 3:
                    case 4:
                        in.readInt();
                        break;
                    case 5:
                    case 6:
                        in.readLong();
                        i++;
                        break;
                    case 7:
                        classNameIdx[i] = in.readUnsignedShort();
                        break;
                    case 8:
                    case 16:
                    case 19:
                    case 20: {
                        int ref = in.readUnsignedShort();
                        if (tag == 16) descriptorIdx[i] = ref;
                        break;
                    }
                    case 9:
                    case 10:
                    case 11:
                    case 17:
                    case 18:
                        in.readUnsignedShort();
                        in.readUnsignedShort();
                        break;
                    case 12:
                        in.readUnsignedShort(); // name
                        descriptorIdx[i] = in.readUnsignedShort();
                        break;
                    case 15:
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                        break;
                    default:
                        return null;
                }
            }

            in.readUnsignedShort(); // access_flags
            int thisIdx = in.readUnsignedShort();
            int superIdx = in.readUnsignedShort();
            String thisClass = normalizeClassName(utf8, classNameIdx, thisIdx);
            if (thisClass == null) return null;

            Set<String> deps = new TreeSet<String>();
            for (int i = 1; i < cpCount; i++) {
                if (classNameIdx[i] >= 0) {
                    String name = normalizeClassName(utf8, classNameIdx, i);
                    if (name != null) deps.add(name);
                }
                if (descriptorIdx[i] >= 0 && utf8[descriptorIdx[i]] != null) {
                    extractDescriptors(utf8[descriptorIdx[i]], deps);
                }
            }
            if (superIdx > 0) {
                String sup = normalizeClassName(utf8, classNameIdx, superIdx);
                if (sup != null) deps.add(sup);
            }

            int ifaceCount = in.readUnsignedShort();
            for (int i = 0; i < ifaceCount; i++) {
                String iface = normalizeClassName(utf8, classNameIdx, in.readUnsignedShort());
                if (iface != null) deps.add(iface);
            }

            int fieldsCount = in.readUnsignedShort();
            for (int i = 0; i < fieldsCount; i++) {
                in.readUnsignedShort(); // flags
                in.readUnsignedShort(); // name
                int desc = in.readUnsignedShort();
                if (desc > 0 && desc < utf8.length && utf8[desc] != null) extractDescriptors(utf8[desc], deps);
                readAttributes(in, utf8, deps);
            }

            int methodsCount = in.readUnsignedShort();
            for (int i = 0; i < methodsCount; i++) {
                in.readUnsignedShort(); // flags
                in.readUnsignedShort(); // name
                int desc = in.readUnsignedShort();
                if (desc > 0 && desc < utf8.length && utf8[desc] != null) extractDescriptors(utf8[desc], deps);
                readAttributes(in, utf8, deps);
            }

            readAttributes(in, utf8, deps);
            deps.remove(thisClass);
            return new ParsedClass(thisClass, deps);
        } catch (IOException | RuntimeException malformed) {
            return null;
        }
    }

    private static void readAttributes(DataInputStream in, String[] utf8, Set<String> deps) throws IOException {
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            int nameIdx = in.readUnsignedShort();
            int len = in.readInt();
            if (len < 0) throw new IOException("Negative attribute length");
            byte[] payload = new byte[len];
            in.readFully(payload);
            String attr = nameIdx > 0 && nameIdx < utf8.length ? utf8[nameIdx] : null;
            if (attr == null) continue;
            DataInputStream sub = new DataInputStream(new ByteArrayInputStream(payload));
            switch (attr) {
                case "Signature": {
                    int idx = sub.readUnsignedShort();
                    if (idx > 0 && idx < utf8.length && utf8[idx] != null) extractDescriptors(utf8[idx], deps);
                    break;
                }
                case "RuntimeVisibleAnnotations":
                case "RuntimeInvisibleAnnotations": {
                    int n = sub.readUnsignedShort();
                    for (int j = 0; j < n; j++) readAnnotation(sub, utf8, deps);
                    break;
                }
                case "RuntimeVisibleParameterAnnotations":
                case "RuntimeInvisibleParameterAnnotations": {
                    int params = sub.readUnsignedByte();
                    for (int p = 0; p < params; p++) {
                        int n = sub.readUnsignedShort();
                        for (int j = 0; j < n; j++) readAnnotation(sub, utf8, deps);
                    }
                    break;
                }
                case "RuntimeVisibleTypeAnnotations":
                case "RuntimeInvisibleTypeAnnotations": {
                    int n = sub.readUnsignedShort();
                    for (int j = 0; j < n; j++) {
                        skipTypeTarget(sub);
                        int pathLen = sub.readUnsignedByte();
                        skipBytes(sub, pathLen * 2);
                        readAnnotation(sub, utf8, deps);
                    }
                    break;
                }
                case "AnnotationDefault":
                    readElementValue(sub, utf8, deps);
                    break;
                case "Code": {
                    sub.readUnsignedShort(); // max_stack
                    sub.readUnsignedShort(); // max_locals
                    int codeLen = sub.readInt();
                    skipBytes(sub, codeLen);
                    int exLen = sub.readUnsignedShort();
                    skipBytes(sub, exLen * 8);
                    readAttributes(sub, utf8, deps);
                    break;
                }
                default:
                    break;
            }
        }
    }

    private static void readAnnotation(DataInputStream in, String[] utf8, Set<String> deps) throws IOException {
        int typeIdx = in.readUnsignedShort();
        if (typeIdx > 0 && typeIdx < utf8.length && utf8[typeIdx] != null) extractDescriptors(utf8[typeIdx], deps);
        int pairs = in.readUnsignedShort();
        for (int i = 0; i < pairs; i++) {
            in.readUnsignedShort(); // element_name_index
            readElementValue(in, utf8, deps);
        }
    }

    private static void readElementValue(DataInputStream in, String[] utf8, Set<String> deps) throws IOException {
        int tag = in.readUnsignedByte();
        switch (tag) {
            case 'B':
            case 'C':
            case 'D':
            case 'F':
            case 'I':
            case 'J':
            case 'S':
            case 'Z':
            case 's':
                in.readUnsignedShort();
                break;
            case 'e': {
                int typeIdx = in.readUnsignedShort();
                in.readUnsignedShort();
                if (typeIdx > 0 && typeIdx < utf8.length && utf8[typeIdx] != null) extractDescriptors(utf8[typeIdx], deps);
                break;
            }
            case 'c': {
                int classInfoIdx = in.readUnsignedShort();
                if (classInfoIdx > 0 && classInfoIdx < utf8.length && utf8[classInfoIdx] != null) extractDescriptors(utf8[classInfoIdx], deps);
                break;
            }
            case '@':
                readAnnotation(in, utf8, deps);
                break;
            case '[': {
                int numValues = in.readUnsignedShort();
                for (int i = 0; i < numValues; i++) readElementValue(in, utf8, deps);
                break;
            }
            default:
                throw new IOException("Unknown annotation element tag: " + tag);
        }
    }

    private static void skipTypeTarget(DataInputStream in) throws IOException {
        int t = in.readUnsignedByte();
        switch (t) {
            case 0x00:
            case 0x01:
            case 0x16:
                in.readUnsignedByte();
                break;
            case 0x10:
            case 0x11:
            case 0x12:
            case 0x17:
            case 0x42:
            case 0x43:
            case 0x44:
            case 0x45:
            case 0x46:
                in.readUnsignedShort();
                break;
            case 0x13:
            case 0x14:
            case 0x15:
                break;
            case 0x40:
            case 0x41: {
                int len = in.readUnsignedShort();
                skipBytes(in, len * 6);
                break;
            }
            case 0x47:
            case 0x48:
            case 0x49:
            case 0x4A:
            case 0x4B:
                in.readUnsignedShort();
                in.readUnsignedByte();
                break;
            default:
                throw new IOException("Unknown type_annotation target_type: " + t);
        }
    }

    private static void skipBytes(DataInputStream in, int n) throws IOException {
        int remaining = n;
        while (remaining > 0) {
            int skipped = in.skipBytes(remaining);
            if (skipped <= 0) throw new EOFException();
            remaining -= skipped;
        }
    }

    private static String normalizeClassName(String[] utf8, int[] classNameIdx, int idx) {
        if (idx <= 0 || idx >= classNameIdx.length || classNameIdx[idx] <= 0) return null;
        String raw = utf8[classNameIdx[idx]];
        if (raw == null || raw.isEmpty()) return null;
        int pos = 0;
        while (pos < raw.length() && raw.charAt(pos) == '[') pos++;
        if (pos == 0) return raw;
        if (pos < raw.length() && raw.charAt(pos) == 'L' && raw.endsWith(";")) {
            return raw.substring(pos + 1, raw.length() - 1);
        }
        return null;
    }

    private static void extractDescriptors(String text, Set<String> deps) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != 'L') continue;
            int end = i + 1;
            StringBuilder internal = new StringBuilder();
            while (end < text.length()) {
                char c = text.charAt(end);
                if (c == ';') {
                    if (internal.length() > 0) deps.add(internal.toString());
                    break;
                }
                if (c == '<' || c == '.') {
                    if (internal.length() > 0) deps.add(internal.toString());
                    break;
                }
                if (c == '*' || c == '+' || c == '-' || c == ':' || c == '(' || c == ')') break;
                internal.append(c);
                end++;
            }
        }
    }

    private static String toCanonicalCensusJson(Map<String, String> classes, Map<String, Set<String>> dependencies) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"classCount\":").append(classes.size()).append(",\"classes\":{");
        boolean first = true;
        for (Map.Entry<String, String> e : classes.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            appendJsonString(e.getKey(), sb);
            sb.append(':');
            appendJsonString(e.getValue(), sb);
        }
        sb.append("},\"dependencies\":{");
        first = true;
        for (Map.Entry<String, Set<String>> e : dependencies.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            appendJsonString(e.getKey(), sb);
            sb.append(":[");
            boolean firstDep = true;
            for (String dep : e.getValue()) {
                if (!firstDep) sb.append(',');
                firstDep = false;
                appendJsonString(dep, sb);
            }
            sb.append(']');
        }
        sb.append("}}");
        return sb.toString();
    }

    private static void appendJsonString(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append("\\u00")
                                .append(Character.forDigit((c >>> 4) & 0xF, 16))
                                .append(Character.forDigit(c & 0xF, 16));
                    } else {
                        out.append(c);
                    }
                    break;
            }
        }
        out.append('"');
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return "sha256:" + toHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX_CHARS[v >>> 4];
            out[i * 2 + 1] = HEX_CHARS[v & 0x0F];
        }
        return new String(out);
    }
}
