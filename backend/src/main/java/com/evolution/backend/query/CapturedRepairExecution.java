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

/** Validates operator-configured isolated QEMU VM build, test, and bytecode dependency census captures. */
final class CapturedRepairExecution {
    static final String VERSION = "captured-repair.vm-capture:v1";
    private static final String BASE_PREFIX = "eclipse-temurin@sha256:";
    private static final Set<String> CONTROLS = Set.of("Dockerfile", "guest-init.sh", "run.py", "ClassDependencyCensus.java");
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final GraphJson.Limits LIMITS = new GraphJson.Limits(MAX_BYTES, 32, 2_000_000);

    record ClassCensus(String buildTool, List<String> modules, int testsRun, int testsFailed, int testsErrors,
                       int classCount, Map<String, ContentDigest> classes, Map<String, Set<String>> dependencies) {
        boolean testsPassed() { return testsFailed == 0 && testsErrors == 0 && classCount > 0; }
    }
    record Proof(ContentDigest capture, ContentDigest image, ContentDigest inputs, String buildTool, List<String> modules,
                 int compileExit, int testExit, int testsRun, int testsFailed, int testsErrors,
                 ContentDigest classCensusDigest, ClassCensus classCensus, List<String> checks) {}

    private record Loaded(ContentDigest captureDigest, ContentDigest proposal, ContentDigest plan, ContentDigest image,
                          ContentDigest inputs, String buildTool, List<String> modules, int compileExit, int testExit,
                          int testsRun, int testsFailed, int testsErrors, ContentDigest classCensusDigest,
                          ClassCensus classCensus, Map<String, ContentDigest> manifestFiles, List<String> checks) {}

    private final Path singleCapture;
    private final ContentDigest singlePin;
    private final Path captureDirectory;

    CapturedRepairExecution(Path captureFile, ContentDigest expectedDigest) throws IOException {
        this.singleCapture = Objects.requireNonNull(captureFile).toAbsolutePath().normalize();
        this.singlePin = Objects.requireNonNull(expectedDigest);
        this.captureDirectory = null;
        loadAndVerify(this.singleCapture, Optional.of(this.singlePin));
    }

    CapturedRepairExecution(Path captureDirectory) throws IOException {
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
                loaded.testsErrors(), loaded.classCensusDigest(), loaded.classCensus(), loaded.checks()));
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
                // Skip non-matching or incomplete sibling subdir captures; tampering on matching byPlan or direct capture rejects.
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
            if (!edit.after().equals(actualCandidate.get(edit.path()))) return false;
        }
        for (var entry : actualCandidate.entrySet()) {
            if (expectedMap.containsKey(entry.getKey())) continue;
            String path = entry.getKey();
            boolean testFile = path.startsWith("src/test/") || path.contains("/src/test/")
                    || path.startsWith("src/integrationTest/") || path.contains("/src/integrationTest/")
                    || path.startsWith("src/androidTest/") || path.contains("/src/androidTest/");
            if (!testFile && (path.endsWith(".java") || path.endsWith("pom.xml")
                    || path.endsWith(".gradle") || path.endsWith(".gradle.kts"))) {
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
        if (!Arrays.equals(bytes, CanonicalJson.write(json).getBytes(StandardCharsets.UTF_8))
                || !json.keySet().equals(Set.of("version", "proposal", "plan", "inputs", "controls", "base", "image",
                        "container", "buildTool", "modules", "compileExit", "testExit", "testsRun", "testsFailed",
                        "testsErrors", "classCensusDigest", "classCensus", "observed", "checks", "serial"))
                || !VERSION.equals(json.get("version"))
                || !(json.get("base") instanceof String base) || !base.startsWith(BASE_PREFIX)
                || !base.substring(BASE_PREFIX.length()).matches("[0-9a-f]{64}")
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
        byte[] manifest = read(root.resolve("context/inputs/manifest.sha256"), MAX_BYTES);
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

        String buildTool = GraphJson.string(json.get("buildTool"));
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
        if (compileExit == 0 && !observed.contains("EVO_CHECK candidate.compile"))
            throw new IOException("CAPTURED_REPAIR_TRANSCRIPT_INCOMPLETE");
        if (compileExit == 0 && testExit == 0 && testsFailed == 0 && testsErrors == 0 && !observed.contains("EVO_CHECK candidate.test"))
            throw new IOException("CAPTURED_REPAIR_TRANSCRIPT_INCOMPLETE");

        var classCensus = decodeCensus(buildTool, modules, testsRun, testsFailed, testsErrors, rawCensus);
        var checks = GraphJson.array(json.get("checks")).stream().map(GraphJson::string).toList();
        return new Loaded(actualDigest, proposal, plan, image, inputs, buildTool, modules, compileExit, testExit,
                testsRun, testsFailed, testsErrors, censusDigest, classCensus, Map.copyOf(inventory), checks);
    }

    private static ClassCensus decodeCensus(String buildTool, List<String> modules, int testsRun, int testsFailed,
                                            int testsErrors, Map<String, Object> raw) throws IOException {
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
                Collections.unmodifiableMap(classes), Collections.unmodifiableMap(deps));
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
