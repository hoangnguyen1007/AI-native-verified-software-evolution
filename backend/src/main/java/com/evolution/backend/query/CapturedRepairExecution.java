package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.SnapshotFile;
import com.evolution.analysis.graph.GraphJson;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Validates operator-configured isolated QEMU VM or hardened-container build, test, and bytecode dependency census captures. */
public final class CapturedRepairExecution {
    static final String VERSION = "captured-repair.vm-capture:v1";
    private static final java.util.regex.Pattern PINNED_BASE_PATTERN =
            java.util.regex.Pattern.compile("^(?:docker\\.io/library/)?(?:eclipse-temurin|node|python|golang)@sha256:[0-9a-f]{64}$");
    private static final Set<String> SUPPORTED_BUILD_TOOLS = Set.of("maven", "gradle", "node", "python", "go");
    private static final Set<String> CONTROLS = Set.of("Dockerfile", "guest-init.sh", "run.py", "ClassDependencyCensus.java");
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final GraphJson.Limits LIMITS = new GraphJson.Limits(MAX_BYTES, 32, 2_000_000);

    public record TestFailure(String className, String methodName, String kind, String message) {}

    public record BehavioralDiffCapture(
            boolean executed,
            String language,
            int baseExit,
            int candExit,
            int baselineTestsRun,
            int baselineTestsFailed,
            int candidateTestsRun,
            int candidateTestsFailed,
            ContentDigest outputSha256,
            String outputExcerpt,
            List<String> baselineFailingTests,
            List<String> candidateFailingTests) {
        public BehavioralDiffCapture(
                boolean executed,
                String language,
                int baseExit,
                int candExit,
                int baselineTestsRun,
                int baselineTestsFailed,
                int candidateTestsRun,
                int candidateTestsFailed,
                ContentDigest outputSha256,
                String outputExcerpt) {
            this(executed, language, baseExit, candExit, baselineTestsRun, baselineTestsFailed,
                    candidateTestsRun, candidateTestsFailed, outputSha256, outputExcerpt,
                    List.of(), List.of());
        }

        public BehavioralDiffCapture {
            baselineFailingTests = baselineFailingTests == null ? List.of() : baselineFailingTests.stream()
                    .filter(s -> s != null && !s.isBlank())
                    .map(String::strip)
                    .distinct()
                    .sorted()
                    .toList();
            candidateFailingTests = candidateFailingTests == null ? List.of() : candidateFailingTests.stream()
                    .filter(s -> s != null && !s.isBlank())
                    .map(String::strip)
                    .distinct()
                    .sorted()
                    .toList();
        }
    }

    record ClassCensus(String buildTool, List<String> modules, int testsRun, int testsFailed, int testsErrors,
                       int classCount, Map<String, ContentDigest> classes, Map<String, Set<String>> dependencies,
                       boolean testsSkipped) {
        ClassCensus(String buildTool, List<String> modules, int testsRun, int testsFailed, int testsErrors,
                    int classCount, Map<String, ContentDigest> classes, Map<String, Set<String>> dependencies) {
            this(buildTool, modules, testsRun, testsFailed, testsErrors, classCount, classes, dependencies, false);
        }
        boolean testsPassed() {
            boolean hasArtifacts = classCount > 0 || (Set.of("node", "python", "go").contains(buildTool) && testsRun > 0);
            return !testsSkipped && testsFailed == 0 && testsErrors == 0 && hasArtifacts;
        }
    }

    record Proof(ContentDigest capture, ContentDigest image, ContentDigest inputs, String buildTool, List<String> modules,
                 int compileExit, int testExit, int testsRun, int testsFailed, int testsErrors,
                 ContentDigest classCensusDigest, ClassCensus classCensus, List<String> checks, boolean testsSkipped,
                 String isolationMode, List<TestFailure> failingTests, String logTail,
                 Optional<BehavioralDiffCapture> behavioralDiff) {
        Proof(ContentDigest capture, ContentDigest image, ContentDigest inputs, String buildTool, List<String> modules,
              int compileExit, int testExit, int testsRun, int testsFailed, int testsErrors,
              ContentDigest classCensusDigest, ClassCensus classCensus, List<String> checks) {
            this(capture, image, inputs, buildTool, modules, compileExit, testExit, testsRun, testsFailed,
                    testsErrors, classCensusDigest, classCensus, checks, classCensus.testsSkipped(),
                    "QEMU_IN_DOCKER", List.of(), "", Optional.empty());
        }
        Proof(ContentDigest capture, ContentDigest image, ContentDigest inputs, String buildTool, List<String> modules,
              int compileExit, int testExit, int testsRun, int testsFailed, int testsErrors,
              ContentDigest classCensusDigest, ClassCensus classCensus, List<String> checks, boolean testsSkipped) {
            this(capture, image, inputs, buildTool, modules, compileExit, testExit, testsRun, testsFailed,
                    testsErrors, classCensusDigest, classCensus, checks, testsSkipped,
                    "QEMU_IN_DOCKER", List.of(), "", Optional.empty());
        }
        Proof(ContentDigest capture, ContentDigest image, ContentDigest inputs, String buildTool, List<String> modules,
              int compileExit, int testExit, int testsRun, int testsFailed, int testsErrors,
              ContentDigest classCensusDigest, ClassCensus classCensus, List<String> checks, boolean testsSkipped,
              String isolationMode, List<TestFailure> failingTests, String logTail) {
            this(capture, image, inputs, buildTool, modules, compileExit, testExit, testsRun, testsFailed,
                    testsErrors, classCensusDigest, classCensus, checks, testsSkipped,
                    isolationMode, failingTests, logTail, Optional.empty());
        }
    }

    private record Loaded(ContentDigest captureDigest, ContentDigest proposal, ContentDigest plan, ContentDigest image,
                          ContentDigest inputs, String buildTool, List<String> modules, int compileExit, int testExit,
                          int testsRun, int testsFailed, int testsErrors, ContentDigest classCensusDigest,
                          ClassCensus classCensus, Map<String, ContentDigest> manifestFiles, List<String> checks,
                          boolean testsSkipped, String isolationMode, List<TestFailure> failingTests, String logTail,
                          Optional<BehavioralDiffCapture> behavioralDiff) {}

    private final Path singleCapture;
    private final ContentDigest singlePin;
    private final Path captureDirectory;

    public CapturedRepairExecution(Path captureFile, ContentDigest expectedDigest) throws IOException {
        this.singleCapture = Objects.requireNonNull(captureFile).toAbsolutePath().normalize();
        this.singlePin = Objects.requireNonNull(expectedDigest);
        this.captureDirectory = null;
        loadAndVerify(this.singleCapture, Optional.of(this.singlePin));
    }

    public CapturedRepairExecution(Path captureDirectory) throws IOException {
        this.captureDirectory = Objects.requireNonNull(captureDirectory).toAbsolutePath().normalize();
        if (!Files.isDirectory(this.captureDirectory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("CAPTURED_REPAIR_DIR_UNAVAILABLE");
        this.singleCapture = null;
        this.singlePin = null;
    }

    Optional<Proof> prove(CapturedRepairVerification.Plan plan, List<SnapshotFile> expectedCandidateFiles) throws IOException {
        Loaded loaded = locate(plan);
        if (loaded == null) return Optional.empty();
        if (!loaded.proposal().equals(plan.proposal()) || !loaded.plan().equals(plan.identity())) return Optional.empty();
        if (!candidateTreeMatches(loaded.manifestFiles(), expectedCandidateFiles, plan.edits())) return Optional.empty();
        return Optional.of(new Proof(loaded.captureDigest(), loaded.image(), loaded.inputs(), loaded.buildTool(),
                loaded.modules(), loaded.compileExit(), loaded.testExit(), loaded.testsRun(), loaded.testsFailed(),
                loaded.testsErrors(), loaded.classCensusDigest(), loaded.classCensus(), loaded.checks(), loaded.testsSkipped(),
                loaded.isolationMode(), loaded.failingTests(), loaded.logTail(), loaded.behavioralDiff()));
    }

    private Loaded locate(CapturedRepairVerification.Plan plan) throws IOException {
        if (singleCapture != null) return loadAndVerify(singleCapture, Optional.of(singlePin));
        String planHex = plan.identity().value().substring(7);
        var candidates = new ArrayList<Path>();
        Path byPlan = captureDirectory.resolve(planHex).resolve("capture.json");
        if (Files.isRegularFile(byPlan, LinkOption.NOFOLLOW_LINKS)) candidates.add(byPlan);
        Path direct = captureDirectory.resolve("capture.json");
        if (Files.isRegularFile(direct, LinkOption.NOFOLLOW_LINKS)) candidates.add(direct);
        try (var children = Files.list(captureDirectory)) {
            int scanned = 0;
            for (var dir : children.sorted().toList()) {
                if (scanned++ >= 64) break;
                if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) continue;
                Path candidate = dir.resolve("capture.json");
                if (!candidate.equals(byPlan) && Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))
                    candidates.add(candidate);
            }
        }
        for (var file : candidates) {
            try {
                Loaded loaded = loadAndVerify(file, Optional.empty());
                if (loaded.plan().equals(plan.identity()) && loaded.proposal().equals(plan.proposal())) return loaded;
            } catch (IOException mismatch) {
                if (file.equals(byPlan) || file.equals(direct)) throw mismatch;
            }
        }
        return null;
    }

    private static boolean candidateTreeMatches(Map<String, ContentDigest> manifest, List<SnapshotFile> expected,
                                                List<CapturedRepairVerification.EditPin> edits) {
        var actualCandidate = new TreeMap<String, ContentDigest>();
        for (var entry : manifest.entrySet()) {
            if (entry.getKey().startsWith("candidate/"))
                actualCandidate.put(entry.getKey().substring("candidate/".length()), entry.getValue());
        }
        var expectedMap = new TreeMap<String, ContentDigest>();
        for (var file : expected) {
            expectedMap.put(file.path(), file.contentDigest());
            ContentDigest observed = actualCandidate.get(file.path());
            if (!file.contentDigest().equals(observed)) return false;
        }
        for (var edit : edits) {
            if (CapturedRepair.ABSENT_DIGEST.equals(edit.after())) {
                if (actualCandidate.containsKey(edit.path())) return false;
            } else if (!edit.after().equals(actualCandidate.get(edit.path()))) {
                return false;
            }
        }
        for (var entry : actualCandidate.entrySet()) {
            if (expectedMap.containsKey(entry.getKey())) continue;
            String path = entry.getKey();
            String lower = path.toLowerCase(Locale.ROOT);
            boolean testFile = path.startsWith("src/test/") || path.contains("/src/test/")
                    || path.startsWith("src/integrationTest/") || path.contains("/src/integrationTest/")
                    || path.startsWith("src/androidTest/") || path.contains("/src/androidTest/")
                    || lower.startsWith("tests/") || lower.contains("/tests/") || lower.contains("/__tests__/")
                    || lower.endsWith(".test.ts") || lower.endsWith(".spec.ts") || lower.endsWith(".test.js")
                    || lower.endsWith("_test.go") || lower.endsWith("_test.py")
                    || lower.substring(lower.lastIndexOf('/') + 1).startsWith("test_");
            if (!testFile && (lower.endsWith(".java") || lower.endsWith("pom.xml")
                    || lower.endsWith(".gradle") || lower.endsWith(".gradle.kts")
                    || lower.endsWith(".ts") || lower.endsWith(".tsx") || lower.endsWith(".js")
                    || lower.endsWith(".py") || lower.endsWith(".go")
                    || lower.endsWith("package.json") || lower.endsWith("tsconfig.json")
                    || lower.endsWith("pyproject.toml") || lower.endsWith("go.mod"))) {
                return false;
            }
        }
        return true;
    }

    private static Loaded loadAndVerify(Path path, Optional<ContentDigest> expected) throws IOException {
        byte[] bytes = read(path, MAX_BYTES);
        ContentDigest actualDigest = ContentDigest.sha256(bytes);
        if (expected.isPresent() && !actualDigest.equals(expected.orElseThrow()))
            throw new IOException("CAPTURED_REPAIR_PIN_MISMATCH");
        var json = GraphJson.object(GraphJson.read(new String(bytes, StandardCharsets.UTF_8), LIMITS));
        var requiredKeys = Set.of("version", "proposal", "plan", "inputs", "controls", "base", "image",
                "container", "buildTool", "modules", "compileExit", "testExit", "testsRun", "testsFailed",
                "testsErrors", "classCensusDigest", "classCensus", "observed", "checks", "serial");
        var allowedKeys = new HashSet<>(requiredKeys);
        allowedKeys.add("testsSkipped");
        allowedKeys.add("isolationMode");
        allowedKeys.add("failingTests");
        allowedKeys.add("logTail");
        allowedKeys.add("behavioralDiff");
        if (!Arrays.equals(bytes, CanonicalJson.write(json).getBytes(StandardCharsets.UTF_8))
                || !json.keySet().containsAll(requiredKeys)
                || !allowedKeys.containsAll(json.keySet())
                || !VERSION.equals(json.get("version"))
                || !(json.get("base") instanceof String base) || !PINNED_BASE_PATTERN.matcher(base).matches()
                || !(json.get("container") instanceof String container) || !container.matches("[0-9a-f]{64}")) {
            throw new IOException("CAPTURED_REPAIR_FORMAT");
        }
        var proposal = digest(json, "proposal");
        var plan = digest(json, "plan");
        var image = digest(json, "image");
        var inputs = digest(json, "inputs");
        var controls = GraphJson.object(json.get("controls"));
        if (!controls.keySet().equals(CONTROLS)) throw new IOException("CAPTURED_REPAIR_CONTROLS");
        for (var name : CONTROLS) {
            try (var input = CapturedRepairExecution.class.getResourceAsStream("/captured-repair/" + name)) {
                if (input == null || !ContentDigest.sha256Utf8(new String(input.readAllBytes(), StandardCharsets.UTF_8)
                        .replace("\r\n", "\n")).equals(digest(controls, name))) {
                    throw new IOException("CAPTURED_REPAIR_CONTROL_CHANGED");
                }
            }
        }
        Path root = path.toAbsolutePath().normalize().getParent();
        Path manifestPath = root.resolve("manifest.sha256");
        if (!Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
            manifestPath = root.resolve("context/inputs/manifest.sha256");
        }
        byte[] manifest = read(manifestPath, MAX_BYTES);
        if (!inputs.equals(ContentDigest.sha256(manifest))) throw new IOException("CAPTURED_REPAIR_INPUTS_CHANGED");
        var inventory = new TreeMap<String, ContentDigest>();
        for (var line : new String(manifest, StandardCharsets.UTF_8).lines().toList()) {
            if (line.length() < 67 || !line.substring(0, 64).matches("[0-9a-f]{64}") || !line.startsWith("  ", 64))
                throw new IOException("CAPTURED_REPAIR_MANIFEST_INVALID");
            String logical = line.substring(66);
            if (logical.contains("..") || logical.startsWith("/") || inventory.putIfAbsent(logical, new ContentDigest("sha256:" + line.substring(0, 64))) != null)
                throw new IOException("CAPTURED_REPAIR_MANIFEST_INVALID");
        }
        if (!digest(controls, "ClassDependencyCensus.java").equals(inventory.get("ClassDependencyCensus.java")))
            throw new IOException("CAPTURED_REPAIR_CENSUS_TOOL_CHANGED");

        String serial = new String(read(root.resolve("serial.txt"), MAX_BYTES), StandardCharsets.UTF_8).replace("\r\n", "\n");
        if (!digest(json, "serial").equals(ContentDigest.sha256Utf8(serial))) throw new IOException("CAPTURED_REPAIR_SERIAL_CHANGED");
        var observed = serial.lines().filter(line -> line.startsWith("EVO_")).toList();
        var recordedObserved = GraphJson.array(json.get("observed")).stream().map(GraphJson::string).toList();
        if (!recordedObserved.equals(observed)) throw new IOException("CAPTURED_REPAIR_OBSERVED_MISMATCH");
        verifyNoDuplicateControlMarkers(observed);

        String buildTool = GraphJson.string(json.get("buildTool"));
        if (!SUPPORTED_BUILD_TOOLS.contains(buildTool)) {
            throw new IOException("CAPTURED_REPAIR_UNSUPPORTED_BUILD_TOOL");
        }
        var modules = GraphJson.array(json.get("modules")).stream().map(GraphJson::string).toList();
        int compileExit = exactInt(json.get("compileExit"));
        int testExit = exactInt(json.get("testExit"));
        int testsRun = exactInt(json.get("testsRun"));
        int testsFailed = exactInt(json.get("testsFailed"));
        int testsErrors = exactInt(json.get("testsErrors"));
        var censusDigest = digest(json, "classCensusDigest");
        var rawCensus = GraphJson.object(json.get("classCensus"));
        if (!CanonicalJson.sha256(rawCensus).equals(censusDigest))
            throw new IOException("CAPTURED_REPAIR_CENSUS_DIGEST_MISMATCH");

        if (!observed.contains("EVO_VM_INPUTS " + inputs.value().substring(7))
                || !observed.contains("EVO_VM_ISOLATION network=loopback-only disks=none")
                || !observed.contains("EVO_BUILD_TOOL " + buildTool)
                || !observed.contains("EVO_COMPILE_EXIT " + compileExit)
                || !observed.contains("EVO_TEST_EXIT " + testExit)
                || !observed.contains("EVO_TESTS run=" + testsRun + " failed=" + testsFailed + " errors=" + testsErrors)
                || !observed.contains("EVO_CLASS_CENSUS " + censusDigest.value())
                || !observed.contains("EVO_VM_COMPLETE")) {
            throw new IOException("CAPTURED_REPAIR_TRANSCRIPT_INCOMPLETE");
        }
        boolean testsSkipped = Boolean.TRUE.equals(json.get("testsSkipped"))
                || observed.contains("EVO_TESTS_SKIPPED true");
        if (compileExit == 0 && !observed.contains("EVO_CHECK candidate.compile"))
            throw new IOException("CAPTURED_REPAIR_TRANSCRIPT_INCOMPLETE");
        if (!testsSkipped && compileExit == 0 && testExit == 0 && testsFailed == 0 && testsErrors == 0
                && !observed.contains("EVO_CHECK candidate.test"))
            throw new IOException("CAPTURED_REPAIR_TRANSCRIPT_INCOMPLETE");

        String isolationMode = json.get("isolationMode") instanceof String im && !im.isBlank() ? im : "QEMU_IN_DOCKER";
        var failingTests = new ArrayList<TestFailure>();
        if (json.get("failingTests") instanceof List<?> rawFailures) {
            for (Object item : rawFailures) {
                var row = GraphJson.object(item);
                String rawMessage = Objects.toString(row.get("message"), "");
                String safeMessage = rawMessage.isEmpty() ? ""
                        : com.evolution.backend.query.llm.LlmTransport.redactSecrets(rawMessage).text();
                failingTests.add(new TestFailure(
                        Objects.toString(row.get("className"), ""),
                        Objects.toString(row.get("methodName"), ""),
                        Objects.toString(row.get("kind"), "FAILURE"),
                        safeMessage));
            }
        }
        String rawLogTail = json.get("logTail") instanceof String lt ? lt : "";
        String logTail = rawLogTail.isEmpty() ? ""
                : com.evolution.backend.query.llm.LlmTransport.redactSecrets(rawLogTail).text();

        Optional<BehavioralDiffCapture> behavioralDiff = Optional.empty();
        if (json.get("behavioralDiff") instanceof Map<?, ?> rawBehav) {
            var bm = GraphJson.object(rawBehav);
            boolean exec = Boolean.TRUE.equals(bm.get("executed"));
            String lang = Objects.toString(bm.get("language"), buildTool);
            int baseExit = bm.get("baseExit") instanceof BigDecimal bd ? exactInt(bd) : -1;
            int candExit = bm.get("candExit") instanceof BigDecimal bd ? exactInt(bd) : -1;
            int bRun = bm.get("baselineTestsRun") instanceof BigDecimal bd ? exactInt(bd) : 0;
            int bFail = bm.get("baselineTestsFailed") instanceof BigDecimal bd ? exactInt(bd) : 0;
            int cRun = bm.get("candidateTestsRun") instanceof BigDecimal bd ? exactInt(bd) : testsRun;
            int cFail = bm.get("candidateTestsFailed") instanceof BigDecimal bd ? exactInt(bd) : (testsFailed + testsErrors);
            if (!(bm.get("outputSha256") instanceof String s) || !s.matches("^sha256:[0-9a-f]{64}$")) {
                throw new IOException("CAPTURED_REPAIR_FORMAT");
            }
            ContentDigest outSha = new ContentDigest(s);
            String expectedObserved = "EVO_BEHAVIORAL_DIFF baseExit=" + baseExit
                    + " candExit=" + candExit
                    + " baseRun=" + bRun
                    + " candRun=" + cRun
                    + " baseFail=" + bFail
                    + " candFail=" + cFail
                    + " outSha=" + outSha.value().substring(7);
            if (!observed.contains(expectedObserved)) {
                throw new IOException("CAPTURED_REPAIR_BEHAVIORAL_MISMATCH");
            }
            String rawExcerpt = bm.get("outputExcerpt") instanceof String ex ? ex : "";
            String safeExcerpt = rawExcerpt.isEmpty() ? ""
                    : com.evolution.backend.query.llm.LlmTransport.redactSecrets(rawExcerpt).text();
            List<String> baseFailIds = decodeFailingTestIds(bm.get("baselineFailingTests"));
            List<String> candFailIds = decodeFailingTestIds(bm.get("candidateFailingTests"));
            behavioralDiff = Optional.of(new BehavioralDiffCapture(
                    exec, lang, baseExit, candExit, bRun, bFail, cRun, cFail, outSha, safeExcerpt,
                    baseFailIds, candFailIds));
        } else if (observed.stream().anyMatch(line -> line.startsWith("EVO_BEHAVIORAL_DIFF "))) {
            throw new IOException("CAPTURED_REPAIR_BEHAVIORAL_MISMATCH");
        }

        var classCensus = decodeCensus(buildTool, modules, testsRun, testsFailed, testsErrors, testsSkipped, rawCensus);
        var checks = GraphJson.array(json.get("checks")).stream().map(GraphJson::string).toList();
        for (String check : checks) {
            if (!observed.contains("EVO_CHECK " + check)) {
                throw new IOException("CAPTURED_REPAIR_OBSERVED_MISMATCH");
            }
        }
        return new Loaded(actualDigest, proposal, plan, image, inputs, buildTool, modules, compileExit, testExit,
                testsRun, testsFailed, testsErrors, censusDigest, classCensus, Map.copyOf(inventory), checks, testsSkipped,
                isolationMode, List.copyOf(failingTests), logTail, behavioralDiff);
    }

    private static void verifyNoDuplicateControlMarkers(List<String> observed) throws IOException {
        var singletons = List.of(
                "EVO_VM_INPUTS ", "EVO_VM_ISOLATION ", "EVO_BUILD_TOOL ",
                "EVO_COMPILE_EXIT ", "EVO_TEST_EXIT ", "EVO_TESTS ",
                "EVO_CLASS_COUNT ", "EVO_CLASS_CENSUS ", "EVO_TESTS_SKIPPED ",
                "EVO_BEHAVIORAL_DIFF ");
        var seenSingletons = new HashSet<String>();
        var seenChecks = new HashSet<String>();
        for (String line : observed) {
            if ("EVO_VM_COMPLETE".equals(line)) {
                if (!seenSingletons.add("EVO_VM_COMPLETE")) {
                    throw new IOException("CAPTURED_REPAIR_OBSERVED_DUPLICATE");
                }
                continue;
            }
            for (String prefix : singletons) {
                if (line.startsWith(prefix)) {
                    if (!seenSingletons.add(prefix)) {
                        throw new IOException("CAPTURED_REPAIR_OBSERVED_DUPLICATE");
                    }
                    break;
                }
            }
            if (line.startsWith("EVO_CHECK ")) {
                if (!seenChecks.add(line)) {
                    throw new IOException("CAPTURED_REPAIR_OBSERVED_DUPLICATE");
                }
            }
        }
    }

    private static List<String> decodeFailingTestIds(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        var ids = new TreeSet<String>();
        for (Object item : list) {
            if (item instanceof String s && !s.isBlank()) {
                String safe = com.evolution.backend.query.llm.LlmTransport.redactSecrets(s.strip()).text();
                if (!safe.isEmpty()) ids.add(safe);
                if (ids.size() >= 500) break;
            }
        }
        return List.copyOf(ids);
    }

    private static ClassCensus decodeCensus(String buildTool, List<String> modules, int testsRun, int testsFailed,
                                            int testsErrors, boolean testsSkipped, Map<String, Object> raw) throws IOException {
        int classCount = exactInt(raw.get("classCount"));
        var classes = new TreeMap<String, ContentDigest>();
        for (var entry : GraphJson.object(raw.get("classes")).entrySet())
            classes.put(entry.getKey(), new ContentDigest(GraphJson.string(entry.getValue())));
        if (classes.size() != classCount) throw new IOException("CAPTURED_REPAIR_CLASS_COUNT_MISMATCH");
        var deps = new TreeMap<String, Set<String>>();
        for (var entry : GraphJson.object(raw.get("dependencies")).entrySet()) {
            var targets = new TreeSet<String>();
            for (var item : GraphJson.array(entry.getValue())) targets.add(GraphJson.string(item));
            deps.put(entry.getKey(), Set.copyOf(targets));
        }
        return new ClassCensus(buildTool, List.copyOf(modules), testsRun, testsFailed, testsErrors, classCount,
                Collections.unmodifiableMap(classes), Collections.unmodifiableMap(deps), testsSkipped);
    }

    private static int exactInt(Object value) throws IOException {
        if (!(value instanceof BigDecimal number)) throw new IOException("CAPTURED_REPAIR_NUMBER_REQUIRED");
        try { return number.intValueExact(); } catch (ArithmeticException invalid) { throw new IOException("CAPTURED_REPAIR_NUMBER_INVALID", invalid); }
    }

    private static ContentDigest digest(Map<String, Object> json, String name) {
        return new ContentDigest(GraphJson.string(json.get(name)));
    }

    private static byte[] read(Path path, int limit) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("CAPTURED_REPAIR_FILE_UNAVAILABLE");
        try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = stream.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("CAPTURED_REPAIR_LIMIT");
            return bytes;
        }
    }
}
