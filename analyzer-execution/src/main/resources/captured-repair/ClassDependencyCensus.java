import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

/**
 * Pure-JDK bytecode class & dependency census plus Surefire XML aggregator for isolated repair builds.
 * Emits CanonicalJson-compatible classCensus JSON: {"classCount":...,"classes":{...},"dependencies":{...}}.
 */
public final class ClassDependencyCensus {
    private static final HexFormat HEX = HexFormat.of();

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: ClassDependencyCensus <repo-root> <census-out.json> [<summary-out.env>]");
            System.exit(2);
        }
        Path repoRoot = Path.of(args[0]).toAbsolutePath().normalize();
        Path jsonOut = Path.of(args[1]).toAbsolutePath().normalize();
        Path envOut = args.length >= 3 ? Path.of(args[2]).toAbsolutePath().normalize() : null;

        var classes = new TreeMap<String, String>();
        var dependencies = new TreeMap<String, Set<String>>();
        int[] testCounts = new int[3]; // run, failed, errors

        var classDirs = new ArrayList<Path>();
        var surefireDirs = new ArrayList<Path>();
        Files.walkFileTree(repoRoot, new SimpleFileVisitor<>() {
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
                        || unix.equals("target/failsafe-reports") || unix.endsWith("/target/failsafe-reports")) {
                    surefireDirs.add(dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });

        for (Path classDir : classDirs) {
            Files.walkFileTree(classDir, new SimpleFileVisitor<>() {
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
                        dependencies.computeIfAbsent(parsed.thisClass(), ignored -> new TreeSet<>())
                                .addAll(parsed.dependencies());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        for (Path reportDir : surefireDirs) {
            try (var stream = Files.list(reportDir)) {
                for (Path file : stream.sorted().toList()) {
                    String fname = file.getFileName().toString();
                    if (fname.startsWith("TEST-") && fname.endsWith(".xml") && Files.isRegularFile(file)) {
                        accumulateSurefire(file, testCounts);
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
            Files.writeString(envOut, env, StandardCharsets.UTF_8);
        }
    }

    private static void accumulateSurefire(Path xml, int[] counts) {
        try {
            var dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var doc = dbf.newDocumentBuilder().parse(xml.toFile());
            var root = doc.getDocumentElement();
            if (root != null && "testsuite".equals(root.getTagName())) {
                counts[0] += parseAttrInt(root.getAttribute("tests"));
                counts[1] += parseAttrInt(root.getAttribute("failures"));
                counts[2] += parseAttrInt(root.getAttribute("errors"));
            }
        } catch (Exception ignored) {
            // Unreadable or non-standard XML report leaves counts at zero unless valid suites exist.
        }
    }

    private static int parseAttrInt(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private record ParsedClass(String thisClass, Set<String> dependencies) {}

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
                    case 1 -> utf8[i] = in.readUTF();
                    case 3, 4 -> in.readInt();
                    case 5, 6 -> {
                        in.readLong();
                        i++;
                    }
                    case 7 -> classNameIdx[i] = in.readUnsignedShort();
                    case 8, 16, 19, 20 -> {
                        int ref = in.readUnsignedShort();
                        if (tag == 16) descriptorIdx[i] = ref;
                    }
                    case 9, 10, 11, 17, 18 -> {
                        in.readUnsignedShort();
                        in.readUnsignedShort();
                    }
                    case 12 -> {
                        in.readUnsignedShort(); // name
                        descriptorIdx[i] = in.readUnsignedShort();
                    }
                    case 15 -> {
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                    }
                    default -> {
                        return null;
                    }
                }
            }

            in.readUnsignedShort(); // access_flags
            int thisIdx = in.readUnsignedShort();
            int superIdx = in.readUnsignedShort();
            String thisClass = normalizeClassName(utf8, classNameIdx, thisIdx);
            if (thisClass == null) return null;

            var deps = new TreeSet<String>();
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
            byte[] payload = in.readNBytes(len);
            if (payload.length != len) throw new EOFException();
            String attr = nameIdx > 0 && nameIdx < utf8.length ? utf8[nameIdx] : null;
            if (attr == null) continue;
            var sub = new DataInputStream(new ByteArrayInputStream(payload));
            switch (attr) {
                case "Signature" -> {
                    int idx = sub.readUnsignedShort();
                    if (idx > 0 && idx < utf8.length && utf8[idx] != null) extractDescriptors(utf8[idx], deps);
                }
                case "RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations" -> {
                    int n = sub.readUnsignedShort();
                    for (int j = 0; j < n; j++) readAnnotation(sub, utf8, deps);
                }
                case "RuntimeVisibleParameterAnnotations", "RuntimeInvisibleParameterAnnotations" -> {
                    int params = sub.readUnsignedByte();
                    for (int p = 0; p < params; p++) {
                        int n = sub.readUnsignedShort();
                        for (int j = 0; j < n; j++) readAnnotation(sub, utf8, deps);
                    }
                }
                case "RuntimeVisibleTypeAnnotations", "RuntimeInvisibleTypeAnnotations" -> {
                    int n = sub.readUnsignedShort();
                    for (int j = 0; j < n; j++) {
                        skipTypeTarget(sub);
                        int pathLen = sub.readUnsignedByte();
                        skipBytes(sub, pathLen * 2);
                        readAnnotation(sub, utf8, deps);
                    }
                }
                case "AnnotationDefault" -> readElementValue(sub, utf8, deps);
                case "Code" -> {
                    sub.readUnsignedShort(); // max_stack
                    sub.readUnsignedShort(); // max_locals
                    int codeLen = sub.readInt();
                    skipBytes(sub, codeLen);
                    int exLen = sub.readUnsignedShort();
                    skipBytes(sub, exLen * 8);
                    readAttributes(sub, utf8, deps);
                }
                default -> {}
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
            case 'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 's' -> in.readUnsignedShort();
            case 'e' -> {
                int typeIdx = in.readUnsignedShort();
                in.readUnsignedShort();
                if (typeIdx > 0 && typeIdx < utf8.length && utf8[typeIdx] != null) extractDescriptors(utf8[typeIdx], deps);
            }
            case 'c' -> {
                int classInfoIdx = in.readUnsignedShort();
                if (classInfoIdx > 0 && classInfoIdx < utf8.length && utf8[classInfoIdx] != null) extractDescriptors(utf8[classInfoIdx], deps);
            }
            case '@' -> readAnnotation(in, utf8, deps);
            case '[' -> {
                int numValues = in.readUnsignedShort();
                for (int i = 0; i < numValues; i++) readElementValue(in, utf8, deps);
            }
            default -> throw new IOException("Unknown annotation element tag: " + tag);
        }
    }

    private static void skipTypeTarget(DataInputStream in) throws IOException {
        int t = in.readUnsignedByte();
        switch (t) {
            case 0x00, 0x01, 0x16 -> in.readUnsignedByte();
            case 0x10, 0x11, 0x12, 0x17, 0x42, 0x43, 0x44, 0x45, 0x46 -> in.readUnsignedShort();
            case 0x13, 0x14, 0x15 -> {}
            case 0x40, 0x41 -> {
                int len = in.readUnsignedShort();
                skipBytes(in, len * 6);
            }
            case 0x47, 0x48, 0x49, 0x4A, 0x4B -> {
                in.readUnsignedShort();
                in.readUnsignedByte();
            }
            default -> throw new IOException("Unknown type_annotation target_type: " + t);
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
                    if (!internal.isEmpty()) deps.add(internal.toString());
                    break;
                }
                if (c == '<' || c == '.') {
                    if (!internal.isEmpty()) deps.add(internal.toString());
                    break;
                }
                if (c == '*' || c == '+' || c == '-' || c == ':' || c == '(' || c == ')') break;
                internal.append(c);
                end++;
            }
        }
    }

    private static String toCanonicalCensusJson(Map<String, String> classes, Map<String, Set<String>> dependencies) {
        var sb = new StringBuilder();
        sb.append("{\"classCount\":").append(classes.size()).append(",\"classes\":{");
        boolean first = true;
        for (var e : classes.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            appendJsonString(e.getKey(), sb);
            sb.append(':');
            appendJsonString(e.getValue(), sb);
        }
        sb.append("},\"dependencies\":{");
        first = true;
        for (var e : dependencies.entrySet()) {
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
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u00")
                                .append(Character.forDigit((c >>> 4) & 0xF, 16))
                                .append(Character.forDigit(c & 0xF, 16));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return "sha256:" + HEX.formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
