package com.evolution.backend.query.repair;

import com.evolution.backend.query.CapturedRepairRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * In-app 1-click Sandbox Setup, read-only environment probe, and live hot-reload coordinator for
 * {@link CapturedRepairRunner}.
 *
 * <p>Eliminates terminal {@code -Devo.captured.repair.runner.*} flags while remaining strictly
 * fail-closed ({@code --network=none --read-only --cap-drop=ALL --security-opt=no-new-privileges:true},
 * pinned official image digests only, zero host target-build execution).
 */
public final class SandboxSetupService {
    public static final String ACTIVE_PROFILE_ID = "active";
    private static final Pattern SAFE_DOCKER_CONTEXT = Pattern.compile("^[A-Za-z0-9._-]{0,128}$");
    private static final Pattern SAFE_CACHE_TAG = Pattern.compile("^[A-Za-z0-9._:/-]{1,128}$");
    private static final Pattern SAFE_PYTHON_EXEC = Pattern.compile("^[A-Za-z0-9._:/\\\\ -]{1,260}$");
    private static final Pattern SAFE_PYTHON_BASENAME = Pattern.compile("^(?:python(?:3(?:\\.\\d{1,2})?)?|py)(?:\\.exe)?$", Pattern.CASE_INSENSITIVE);
    private static final Set<String> MULTI_USER_PYTHON_ALLOWLIST = Set.of("python", "python3", "python.exe", "python3.exe");
    private static final Map<String, Object> HARDENED_POLICY = Map.of(
            "networkMode", "none",
            "readOnlyRootfs", true,
            "capDrop", List.of("ALL"),
            "securityOpt", List.of("no-new-privileges:true"),
            "hostExecutionAllowed", false,
            "pinnedDigestRequired", true);

    private final Path defaultWorkRoot;
    private final RepairJobStore jobStore;
    private volatile Path storeRoot;
    private volatile boolean multiUserMode;
    private final AtomicReference<CapturedRepairRunner> runnerRef = new AtomicReference<>();
    private volatile String activeSource = "NONE";
    private volatile Optional<Long> lastProbedAtMs = Optional.empty();
    private volatile Optional<CapturedRepairRunner.EnvironmentProbe> lastProbe = Optional.empty();

    public SandboxSetupService(Path defaultWorkRoot, RepairJobStore jobStore, CapturedRepairRunner initialRunner) {
        this(defaultWorkRoot, jobStore, initialRunner, null, false);
    }

    public SandboxSetupService(
            Path defaultWorkRoot,
            RepairJobStore jobStore,
            CapturedRepairRunner initialRunner,
            Path storeRoot,
            boolean multiUserMode) {
        this.defaultWorkRoot = (defaultWorkRoot != null
                ? defaultWorkRoot
                : Path.of(System.getProperty("java.io.tmpdir", "."), "evo-sandbox-work"))
                .toAbsolutePath().normalize();
        this.jobStore = jobStore;
        this.storeRoot = resolveStoreRoot(storeRoot, jobStore);
        this.multiUserMode = multiUserMode;
        if (initialRunner != null) {
            this.runnerRef.set(initialRunner);
            this.activeSource = "SYSTEM_PROPERTIES";
        } else if (jobStore != null) {
            restorePersistedProfile();
        }
    }

    /** Binds server store directory and multi-user policy when attached to {@code ArchitectureQueryApi}. */
    public void bindServerPolicy(Path storeRoot, boolean multiUserMode) {
        Path resolved = resolveStoreRoot(storeRoot, this.jobStore);
        if (resolved != null) {
            this.storeRoot = resolved;
        }
        if (multiUserMode) {
            this.multiUserMode = true;
        }
        var current = this.runnerRef.get();
        if (current != null && !"SYSTEM_PROPERTIES".equals(this.activeSource)) {
            try {
                var cfg = current.config();
                validatePythonExec(cfg.pythonExecutable(), this.multiUserMode);
                boolean isDefWork = cfg.workRoot().toAbsolutePath().normalize().equals(defaultWorkRoot);
                validateHostPath(cfg.workRoot(), "workRoot", isDefWork);
                validateOptionalHostPath(cfg.m2Repository(), "m2Repository", false);
                validateOptionalHostPath(cfg.mavenZip(), "mavenZip", false);
                validateOptionalHostPath(cfg.gradleZip(), "gradleZip", false);
                validateOptionalHostPath(cfg.gradleHome(), "gradleHome", false);
                validateOptionalHostPath(cfg.npmCache(), "npmCache", false);
                validateOptionalHostPath(cfg.pipWheelhouse(), "pipWheelhouse", false);
                validateOptionalHostPath(cfg.goModCache(), "goModCache", false);
            } catch (RuntimeException invalidUnderPolicy) {
                this.runnerRef.set(null);
                this.activeSource = "NONE";
            }
        }
    }

    private boolean isMultiUserEffective() {
        return this.multiUserMode || com.evolution.backend.auth.RequestContext.isMultiUserMode();
    }

    public CapturedRepairRunner activeRunner() {
        return runnerRef.get();
    }

    public boolean isConfigured() {
        return runnerRef.get() != null;
    }

    public String activeSource() {
        return activeSource;
    }

    public void setExternalRunner(CapturedRepairRunner runner, String source) {
        this.runnerRef.set(runner);
        this.activeSource = runner == null ? "NONE" : (source == null ? "EXTERNAL" : source);
    }

    /** Strictly read-only host environment & Docker probe (never runs target builds). */
    public Map<String, Object> probe(Optional<String> pythonExec, Optional<String> dockerContext) {
        boolean multiUser = isMultiUserEffective();
        pythonExec.ifPresent(py -> validatePythonExec(py, multiUser));
        dockerContext.ifPresent(SandboxSetupService::validateDockerContext);
        var result = CapturedRepairRunner.probeEnvironment(pythonExec, dockerContext);
        long now = System.currentTimeMillis();
        this.lastProbe = Optional.of(result);
        this.lastProbedAtMs = Optional.of(now);
        return serializeProbe(result, now);
    }

    public Map<String, Object> statusView() {
        var runner = runnerRef.get();
        var out = new LinkedHashMap<String, Object>();
        out.put("configured", runner != null);
        out.put("source", activeSource);
        out.put("isolationMode", runner != null ? runner.config().isolationMode().name() : "PASSIVE_REANALYSIS_ONLY");
        out.put("supportedBuildTools", List.of("auto", "maven", "gradle", "node", "python", "go"));
        out.put("supportedIsolationModes", List.of("DOCKER_HARDENED_CONTAINER", "QEMU_IN_DOCKER"));
        out.put("securityPolicy", HARDENED_POLICY);
        out.put("defaultWorkRoot", defaultWorkRoot.toString());
        out.put("persisted", jobStore != null && jobStore.loadSandboxProfile(ACTIVE_PROFILE_ID).isPresent());
        if (runner != null) {
            out.put("config", serializeConfig(runner.config()));
        } else {
            out.put("config", null);
        }
        lastProbe.ifPresent(p -> out.put("lastProbe", serializeProbe(p, lastProbedAtMs.orElse(0L))));
        return Collections.unmodifiableMap(out);
    }

    /**
     * Validates, persists (when {@link RepairJobStore} is attached), and live hot-reloads
     * {@link CapturedRepairRunner} with zero server restart.
     */
    public Map<String, Object> configure(Map<String, Object> input) {
        Objects.requireNonNull(input);
        boolean enabled = !Boolean.FALSE.equals(input.get("enabled"));
        if (!enabled) {
            return disable();
        }
        boolean multiUser = isMultiUserEffective();
        boolean autoDetect = Boolean.TRUE.equals(input.get("autoDetect"));
        Optional<String> reqPython = optString(input, "pythonExecutable");
        Optional<String> reqDockerCtx = optString(input, "dockerContext");
        reqPython.ifPresent(py -> validatePythonExec(py, multiUser));
        reqDockerCtx.ifPresent(SandboxSetupService::validateDockerContext);

        CapturedRepairRunner.EnvironmentProbe probeResult = null;
        if (autoDetect) {
            probeResult = CapturedRepairRunner.probeEnvironment(reqPython, reqDockerCtx);
            this.lastProbe = Optional.of(probeResult);
            this.lastProbedAtMs = Optional.of(System.currentTimeMillis());
        }

        String rawMode = optString(input, "isolationMode").orElse("DOCKER_HARDENED_CONTAINER");
        var isolationMode = CapturedRepairRunner.IsolationMode.parse(rawMode);
        String buildTool = optString(input, "buildTool").orElse("auto").toLowerCase(Locale.ROOT);
        if (!CapturedRepairRunner.SUPPORTED_BUILD_TOOLS.contains(buildTool)) {
            throw new IllegalArgumentException("Unsupported buildTool: " + buildTool);
        }
        if (Set.of("node", "python", "go").contains(buildTool)
                && isolationMode != CapturedRepairRunner.IsolationMode.DOCKER_HARDENED_CONTAINER) {
            throw new IllegalArgumentException("Polyglot build tools (node, python, go) require DOCKER_HARDENED_CONTAINER isolation mode.");
        }

        final CapturedRepairRunner.EnvironmentProbe probe = probeResult;
        String pythonExec = reqPython
                .or(() -> probe != null ? Optional.of(probe.pythonExecutable()) : Optional.empty())
                .orElseGet(CapturedRepairRunner.Config::defaultPython);
        validatePythonExec(pythonExec, multiUser);

        Optional<String> baseImage = optString(input, "baseImage");
        if (baseImage.isEmpty() && probe != null && !probe.pinnedLocalImages().isEmpty()) {
            baseImage = selectPreferredBaseImage(probe.pinnedLocalImages(), buildTool);
        }
        if (baseImage.isPresent()
                && !CapturedRepairRunner.PINNED_BASE_IMAGE_PATTERN.matcher(baseImage.orElseThrow()).matches()) {
            throw new IllegalArgumentException(
                    "baseImage must be a pinned official digest (eclipse-temurin|node|python|golang@sha256:<64hex>).");
        }

        Optional<String> dockerCtx = reqDockerCtx
                .or(() -> probe != null ? Optional.ofNullable(probe.dockerContext()).map(String::strip).filter(s -> !s.isBlank()) : Optional.empty());
        dockerCtx.ifPresent(SandboxSetupService::validateDockerContext);

        Optional<String> cacheTag = optString(input, "baseCacheTag");
        if (cacheTag.isPresent()
                && (cacheTag.orElseThrow().startsWith("-")
                        || !SAFE_CACHE_TAG.matcher(cacheTag.orElseThrow()).matches())) {
            throw new IllegalArgumentException("Invalid baseCacheTag");
        }

        Optional<Path> m2 = validateOptionalHostPath(optPath(input, "m2Repository").or(() -> autoPath(probe, "m2Repository")), "m2Repository", false);
        Optional<Path> mavenZip = validateOptionalHostPath(optPath(input, "mavenZip").or(() -> autoPath(probe, "mavenZip")), "mavenZip", false);
        Optional<Path> gradleZip = validateOptionalHostPath(optPath(input, "gradleZip").or(() -> autoPath(probe, "gradleZip")), "gradleZip", false);
        Optional<Path> gradleHome = validateOptionalHostPath(optPath(input, "gradleHome").or(() -> autoPath(probe, "gradleHome")), "gradleHome", false);
        Optional<Path> npmCache = validateOptionalHostPath(optPath(input, "npmCache").or(() -> autoPath(probe, "npmCache")), "npmCache", false);
        Optional<Path> pipWheelhouse = validateOptionalHostPath(optPath(input, "pipWheelhouse").or(() -> autoPath(probe, "pipWheelhouse")), "pipWheelhouse", false);
        Optional<Path> goModCache = validateOptionalHostPath(optPath(input, "goModCache").or(() -> autoPath(probe, "goModCache")), "goModCache", false);

        Optional<Path> requestedWorkRoot = optPath(input, "workRoot");
        Path workRoot;
        if (requestedWorkRoot.isPresent()) {
            workRoot = validateHostPath(requestedWorkRoot.orElseThrow(), "workRoot", false);
        } else {
            workRoot = validateHostPath(defaultWorkRoot, "workRoot", true);
        }
        boolean skipTests = Boolean.TRUE.equals(input.get("skipTests"));
        int timeoutSeconds = 900;
        if (input.containsKey("timeoutSeconds") && input.get("timeoutSeconds") != null) {
            if (!(input.get("timeoutSeconds") instanceof Number n)) {
                throw new IllegalArgumentException("timeoutSeconds must be an integer");
            }
            timeoutSeconds = n.intValue();
            if (timeoutSeconds < 30 || timeoutSeconds > 1800) {
                throw new IllegalArgumentException("timeoutSeconds must be between 30 and 1800");
            }
        }

        var cfg = new CapturedRepairRunner.Config(
                pythonExec,
                baseImage,
                buildTool,
                mavenZip,
                m2,
                gradleZip,
                gradleHome,
                dockerCtx,
                skipTests,
                workRoot,
                Duration.ofSeconds(timeoutSeconds),
                isolationMode,
                cacheTag,
                npmCache,
                pipWheelhouse,
                goModCache);

        var newRunner = new CapturedRepairRunner(cfg);
        this.runnerRef.set(newRunner);
        this.activeSource = jobStore != null ? "PERSISTED" : "IN_MEMORY";

        if (jobStore != null) {
            long now = System.currentTimeMillis();
            var profile = new RepairJobStore.PersistedSandboxProfile(
                    ACTIVE_PROFILE_ID,
                    cfg.isolationMode().name(),
                    cfg.buildTool(),
                    cfg.baseImage().orElse(CapturedRepairRunner.DEFAULT_JAVA_BASE_IMAGE),
                    serializeConfig(cfg),
                    probeResult != null && probeResult.readyForHardenedContainers() ? Optional.of(now) : lastProbedAtMs,
                    now);
            jobStore.saveSandboxProfile(profile);
        }

        var response = new LinkedHashMap<String, Object>(statusView());
        response.put("hotReloaded", true);
        if (probeResult != null) {
            response.put("probe", serializeProbe(probeResult, System.currentTimeMillis()));
        }
        return Collections.unmodifiableMap(response);
    }

    public Map<String, Object> disable() {
        this.runnerRef.set(null);
        this.activeSource = "NONE";
        if (jobStore != null) {
            jobStore.deleteSandboxProfile(ACTIVE_PROFILE_ID);
        }
        var response = new LinkedHashMap<String, Object>(statusView());
        response.put("hotReloaded", true);
        return Collections.unmodifiableMap(response);
    }

    private void restorePersistedProfile() {
        var profileOpt = jobStore.loadSandboxProfile(ACTIVE_PROFILE_ID);
        if (profileOpt.isEmpty()) return;
        try {
            var p = profileOpt.orElseThrow();
            var cfgMap = p.config();
            String pyExec = optString(cfgMap, "pythonExecutable").orElseGet(CapturedRepairRunner.Config::defaultPython);
            validatePythonExec(pyExec, this.multiUserMode);
            Optional<String> dockerCtx = optString(cfgMap, "dockerContext");
            dockerCtx.ifPresent(SandboxSetupService::validateDockerContext);
            Optional<String> cacheTag = optString(cfgMap, "baseCacheTag");
            if (cacheTag.isPresent() && (cacheTag.orElseThrow().startsWith("-")
                    || !SAFE_CACHE_TAG.matcher(cacheTag.orElseThrow()).matches())) {
                throw new IllegalArgumentException("Invalid persisted baseCacheTag");
            }
            Optional<Path> persistedWork = optPath(cfgMap, "workRoot");
            Path workRoot = persistedWork.isEmpty() || persistedWork.orElseThrow().equals(defaultWorkRoot)
                    ? validateHostPath(defaultWorkRoot, "workRoot", true)
                    : validateHostPath(persistedWork.orElseThrow(), "workRoot", false);
            var cfg = new CapturedRepairRunner.Config(
                    pyExec,
                    optString(cfgMap, "baseImage"),
                    p.buildTool(),
                    validateOptionalHostPath(optPath(cfgMap, "mavenZip"), "mavenZip", false),
                    validateOptionalHostPath(optPath(cfgMap, "m2Repository"), "m2Repository", false),
                    validateOptionalHostPath(optPath(cfgMap, "gradleZip"), "gradleZip", false),
                    validateOptionalHostPath(optPath(cfgMap, "gradleHome"), "gradleHome", false),
                    dockerCtx,
                    Boolean.TRUE.equals(cfgMap.get("skipTests")),
                    workRoot,
                    Duration.ofSeconds(cfgMap.get("timeoutSeconds") instanceof Number n ? Math.max(30, Math.min(1800, n.longValue())) : 900L),
                    CapturedRepairRunner.IsolationMode.parse(p.isolationMode()),
                    cacheTag,
                    validateOptionalHostPath(optPath(cfgMap, "npmCache"), "npmCache", false),
                    validateOptionalHostPath(optPath(cfgMap, "pipWheelhouse"), "pipWheelhouse", false),
                    validateOptionalHostPath(optPath(cfgMap, "goModCache"), "goModCache", false));
            this.runnerRef.set(new CapturedRepairRunner(cfg));
            this.activeSource = "PERSISTED";
            this.lastProbedAtMs = p.verifiedAtMs();
        } catch (RuntimeException ignored) {
            // Corrupt or incompatible profile stays fail-closed (PASSIVE_REANALYSIS_ONLY).
            this.runnerRef.set(null);
            this.activeSource = "NONE";
        }
    }

    private static Optional<String> selectPreferredBaseImage(List<String> images, String buildTool) {
        String desiredPrefix = switch (buildTool) {
            case "node" -> "node@sha256:";
            case "python" -> "python@sha256:";
            case "go" -> "golang@sha256:";
            default -> "eclipse-temurin@sha256:";
        };
        for (String img : images) {
            if (img.endsWith(desiredPrefix) || img.contains(desiredPrefix)) return Optional.of(img);
        }
        return Optional.empty();
    }

    private static Map<String, Object> serializeConfig(CapturedRepairRunner.Config cfg) {
        var m = new LinkedHashMap<String, Object>();
        m.put("isolationMode", cfg.isolationMode().name());
        m.put("buildTool", cfg.buildTool());
        m.put("pythonExecutable", cfg.pythonExecutable());
        cfg.baseImage().ifPresent(v -> m.put("baseImage", v));
        cfg.dockerContext().ifPresent(v -> m.put("dockerContext", v));
        cfg.m2Repository().ifPresent(p -> m.put("m2Repository", p.toString()));
        cfg.mavenZip().ifPresent(p -> m.put("mavenZip", p.toString()));
        cfg.gradleHome().ifPresent(p -> m.put("gradleHome", p.toString()));
        cfg.gradleZip().ifPresent(p -> m.put("gradleZip", p.toString()));
        cfg.npmCache().ifPresent(p -> m.put("npmCache", p.toString()));
        cfg.pipWheelhouse().ifPresent(p -> m.put("pipWheelhouse", p.toString()));
        cfg.goModCache().ifPresent(p -> m.put("goModCache", p.toString()));
        cfg.baseCacheTag().ifPresent(v -> m.put("baseCacheTag", v));
        m.put("workRoot", cfg.workRoot().toString());
        m.put("skipTests", cfg.skipTests());
        m.put("timeoutSeconds", cfg.timeout().toSeconds());
        return Collections.unmodifiableMap(m);
    }

    private static Map<String, Object> serializeProbe(CapturedRepairRunner.EnvironmentProbe probe, long probedAtMs) {
        var m = new LinkedHashMap<String, Object>();
        m.put("probedAtMs", probedAtMs);
        m.put("pythonAvailable", probe.pythonAvailable());
        m.put("pythonExecutable", probe.pythonExecutable());
        m.put("pythonVersion", probe.pythonVersion());
        m.put("dockerCliAvailable", probe.dockerCliAvailable());
        m.put("dockerDaemonReachable", probe.dockerDaemonReachable());
        m.put("dockerContext", probe.dockerContext());
        m.put("dockerServerVersion", probe.dockerServerVersion());
        m.put("pinnedLocalImages", probe.pinnedLocalImages());
        m.put("detectedCaches", probe.detectedCaches());
        m.put("toolchains", probe.toolchains());
        m.put("readyForHardenedContainers", probe.readyForHardenedContainers());
        m.put("securityPolicy", HARDENED_POLICY);
        m.put("summary", probe.summary());
        return Collections.unmodifiableMap(m);
    }

    private static void validateDockerContext(String ctx) {
        if (ctx.startsWith("-") || !SAFE_DOCKER_CONTEXT.matcher(ctx).matches()) {
            throw new IllegalArgumentException("Invalid dockerContext");
        }
    }

    private static void validatePythonExec(String py, boolean multiUser) {
        if (py == null || py.isBlank() || py.startsWith("-") || py.indexOf('\0') >= 0
                || py.contains("..") || !SAFE_PYTHON_EXEC.matcher(py).matches()) {
            throw new IllegalArgumentException("Invalid pythonExecutable");
        }
        String trimmed = py.strip();
        if (multiUser) {
            if (trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains(":")
                    || !MULTI_USER_PYTHON_ALLOWLIST.contains(trimmed.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(
                        "In multi-user mode pythonExecutable must be a bare allowlisted PATH executable (python, python3, python.exe, python3.exe)");
            }
            return;
        }
        String normalized = trimmed.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String baseName = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        if (!SAFE_PYTHON_BASENAME.matcher(baseName).matches()) {
            throw new IllegalArgumentException("pythonExecutable must refer to a Python interpreter (python, python3, py)");
        }
    }

    private static Path resolveStoreRoot(Path explicitStoreRoot, RepairJobStore jobStore) {
        if (explicitStoreRoot != null) {
            return explicitStoreRoot.toAbsolutePath().normalize();
        }
        if (jobStore != null && jobStore.dbFile() != null) {
            Path parent = jobStore.dbFile().toAbsolutePath().normalize().getParent();
            if (parent != null) {
                if ("repairs".equalsIgnoreCase(Objects.toString(parent.getFileName(), "")) && parent.getParent() != null) {
                    return parent.getParent().toAbsolutePath().normalize();
                }
                return parent;
            }
        }
        return null;
    }

    private Optional<Path> validateOptionalHostPath(Optional<Path> opt, String key, boolean isDefaultWorkRoot) {
        return opt.map(p -> validateHostPath(p, key, isDefaultWorkRoot));
    }

    private Path validateHostPath(Path path, String key, boolean isDefaultWorkRoot) {
        Path norm = path.toAbsolutePath().normalize();
        // Reject symlinked target or any symlinked/reparse-point (junction) existing ancestor/parent directory
        Path cur = norm;
        while (cur != null) {
            if (Files.isSymbolicLink(cur)) {
                throw new IllegalArgumentException("Symlinked path or parent directory is not allowed for " + key);
            }
            if (cur.getParent() != null && Files.exists(cur, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                try {
                    var attrs = Files.readAttributes(
                            cur, java.nio.file.attribute.BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                    if (attrs.isSymbolicLink() || attrs.isOther()) {
                        throw new IllegalArgumentException("Symlinked or junction path is not allowed for " + key);
                    }
                } catch (IOException ignored) {
                    // Best-effort attribute inspection on non-readable ancestors
                }
            }
            cur = cur.getParent();
        }
        Path canonical = resolveCanonicalPrefix(norm);
        List<Path> forbiddenRoots = collectProtectedStoreRoots(isDefaultWorkRoot);
        for (Path forbidden : forbiddenRoots) {
            Path forbiddenCanon = resolveCanonicalPrefix(forbidden);
            if (pathsOverlap(canonical, forbiddenCanon) || pathsOverlap(norm, forbidden)) {
                throw new IllegalArgumentException(
                        "Configured " + key + " must not overlap server store, auth, or repairs data directory");
            }
        }
        return norm;
    }

    private List<Path> collectProtectedStoreRoots(boolean isDefaultWorkRoot) {
        var roots = new ArrayList<Path>();
        Path sr = this.storeRoot;
        if (sr != null) {
            Path normSr = sr.toAbsolutePath().normalize();
            if (isDefaultWorkRoot) {
                roots.add(normSr.resolve("auth"));
            } else {
                roots.add(normSr);
                roots.add(normSr.resolve("auth"));
                roots.add(normSr.resolve("repairs"));
            }
        }
        if (!isDefaultWorkRoot && jobStore != null && jobStore.dbFile() != null) {
            Path dbParent = jobStore.dbFile().toAbsolutePath().normalize().getParent();
            if (dbParent != null) {
                roots.add(dbParent);
            }
        }
        return roots;
    }

    private static Path resolveCanonicalPrefix(Path normalized) {
        var tail = new ArrayDeque<Path>();
        Path cur = normalized;
        while (cur != null && !Files.exists(cur)) {
            if (cur.getFileName() != null) {
                tail.addFirst(cur.getFileName());
            }
            cur = cur.getParent();
        }
        if (cur == null) {
            return normalized;
        }
        try {
            Path resolved = cur.toRealPath();
            for (Path part : tail) {
                resolved = resolved.resolve(part);
            }
            return resolved.normalize();
        } catch (IOException ignored) {
            return normalized;
        }
    }

    private static boolean pathsOverlap(Path a, Path b) {
        if (a == null || b == null) return false;
        Path na = a.toAbsolutePath().normalize();
        Path nb = b.toAbsolutePath().normalize();
        return na.equals(nb) || na.startsWith(nb) || nb.startsWith(na);
    }

    private static Optional<String> optString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) return Optional.empty();
        if (!(v instanceof String s)) throw new IllegalArgumentException("Expected string for " + key);
        String trimmed = s.strip();
        return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
    }

    private static Optional<Path> optPath(Map<String, Object> map, String key) {
        return optString(map, key).map(raw -> {
            if (raw.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid path for " + key);
            return Path.of(raw).toAbsolutePath().normalize();
        });
    }

    private static Optional<Path> autoPath(CapturedRepairRunner.EnvironmentProbe probe, String key) {
        if (probe == null) return Optional.empty();
        String found = probe.detectedCaches().get(key);
        return (found == null || found.isBlank()) ? Optional.empty() : Optional.of(Path.of(found));
    }
}
