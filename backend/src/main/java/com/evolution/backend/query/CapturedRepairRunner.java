package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Opt-in orchestrator for the bundled isolated QEMU-in-Docker repair verifier
 * ({@code /captured-repair/run.py}).
 *
 * <p>Never executes Maven or Gradle builds directly on the host; fails closed with
 * {@link #NOT_CONFIGURED} unless explicit isolated runner settings are supplied.
 */
public final class CapturedRepairRunner {
    public static final String NOT_CONFIGURED = "ISOLATED_RUNNER_NOT_CONFIGURED";
    public static final String OFFLINE_TOOLCHAIN_MISSING = "OFFLINE_TOOLCHAIN_MISSING";
    public static final Set<String> SUPPORTED_BUILD_TOOLS = Set.of("auto", "maven", "gradle", "node", "python", "go");
    private static final java.util.regex.Pattern SAFE_TEST_FILTER =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_.$#,*?/-]{1,2048}$");
    public static final java.util.regex.Pattern PINNED_BASE_IMAGE_PATTERN =
            java.util.regex.Pattern.compile("^(?:docker\\.io/library/)?(?:eclipse-temurin|node|python|golang)@sha256:[0-9a-f]{64}$");
    public static final String DEFAULT_JAVA_BASE_IMAGE =
            "eclipse-temurin@sha256:82801f9dcdcbad5a05082aba8c86d1da5b72ccb498e71318b1f91b5a477887b3";
    static final List<String> CONTROL_RESOURCES = List.of(
            "Dockerfile", "guest-init.sh", "run.py", "ClassDependencyCensus.java");

    public enum IsolationMode {
        QEMU_IN_DOCKER("qemu-docker"),
        DOCKER_HARDENED_CONTAINER("docker-container");

        final String cliFlag;
        IsolationMode(String cliFlag) { this.cliFlag = cliFlag; }
        public String cliFlag() { return cliFlag; }

        public static IsolationMode parse(String raw) {
            if (raw == null || raw.isBlank()) return QEMU_IN_DOCKER;
            return switch (raw.strip().toLowerCase(Locale.ROOT).replace('_', '-')) {
                case "qemu", "qemu-docker", "qemu-in-docker" -> QEMU_IN_DOCKER;
                case "docker", "container", "docker-container", "docker-hardened-container" -> DOCKER_HARDENED_CONTAINER;
                default -> throw new IllegalArgumentException("Unsupported isolation mode: " + raw);
            };
        }
    }

    public record Config(
            String pythonExecutable,
            Optional<String> baseImage,
            String buildTool,
            Optional<Path> mavenZip,
            Optional<Path> m2Repository,
            Optional<Path> gradleZip,
            Optional<Path> gradleHome,
            Optional<String> dockerContext,
            boolean skipTests,
            Path workRoot,
            Duration timeout,
            IsolationMode isolationMode,
            Optional<String> baseCacheTag,
            Optional<Path> npmCache,
            Optional<Path> pipWheelhouse,
            Optional<Path> goModCache) {
        public Config(
                String pythonExecutable,
                Optional<String> baseImage,
                String buildTool,
                Optional<Path> mavenZip,
                Optional<Path> m2Repository,
                Optional<Path> gradleZip,
                Optional<Path> gradleHome,
                Optional<String> dockerContext,
                boolean skipTests,
                Path workRoot,
                Duration timeout) {
            this(pythonExecutable, baseImage, buildTool, mavenZip, m2Repository, gradleZip, gradleHome,
                    dockerContext, skipTests, workRoot, timeout, IsolationMode.QEMU_IN_DOCKER, Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty());
        }
        public Config(
                String pythonExecutable,
                Optional<String> baseImage,
                String buildTool,
                Optional<Path> mavenZip,
                Optional<Path> m2Repository,
                Optional<Path> gradleZip,
                Optional<Path> gradleHome,
                Optional<String> dockerContext,
                boolean skipTests,
                Path workRoot,
                Duration timeout,
                IsolationMode isolationMode,
                Optional<String> baseCacheTag) {
            this(pythonExecutable, baseImage, buildTool, mavenZip, m2Repository, gradleZip, gradleHome,
                    dockerContext, skipTests, workRoot, timeout, isolationMode, baseCacheTag,
                    Optional.empty(), Optional.empty(), Optional.empty());
        }
        public Config {
            if (pythonExecutable == null || pythonExecutable.isBlank()) {
                pythonExecutable = defaultPython();
            }
            if (buildTool == null || buildTool.isBlank()) {
                buildTool = "auto";
            }
            buildTool = buildTool.strip().toLowerCase(Locale.ROOT);
            if (!SUPPORTED_BUILD_TOOLS.contains(buildTool)) {
                throw new IllegalArgumentException("Unsupported buildTool: " + buildTool);
            }
            baseImage = Objects.requireNonNull(baseImage);
            if (baseImage.isPresent() && !PINNED_BASE_IMAGE_PATTERN.matcher(baseImage.orElseThrow().strip()).matches()) {
                throw new IllegalArgumentException("Base image must be a pinned official digest (eclipse-temurin|node|python|golang@sha256:...)");
            }
            mavenZip = Objects.requireNonNull(mavenZip);
            m2Repository = Objects.requireNonNull(m2Repository);
            gradleZip = Objects.requireNonNull(gradleZip);
            gradleHome = Objects.requireNonNull(gradleHome);
            dockerContext = Objects.requireNonNull(dockerContext);
            Objects.requireNonNull(workRoot);
            if (timeout == null || timeout.isNegative() || timeout.isZero()) {
                timeout = Duration.ofMinutes(15);
            }
            isolationMode = isolationMode == null ? IsolationMode.QEMU_IN_DOCKER : isolationMode;
            if (Set.of("node", "python", "go").contains(buildTool)
                    && isolationMode != IsolationMode.DOCKER_HARDENED_CONTAINER) {
                throw new IllegalArgumentException(
                        "Polyglot build tool '" + buildTool + "' requires DOCKER_HARDENED_CONTAINER isolation mode");
            }
            baseCacheTag = baseCacheTag == null ? Optional.empty() : baseCacheTag;
            npmCache = npmCache == null ? Optional.empty() : npmCache;
            pipWheelhouse = pipWheelhouse == null ? Optional.empty() : pipWheelhouse;
            goModCache = goModCache == null ? Optional.empty() : goModCache;
        }

        public static String defaultPython() {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            return os.contains("win") ? "python" : "python3";
        }
    }

    public record InvocationResult(Path captureFile, ContentDigest captureDigest, CapturedRepairExecution execution) {}

    public record ToolchainReadiness(String buildTool, String status, Optional<String> cachePath, String detail) {}

    public record EnvironmentProbe(
            boolean pythonAvailable,
            String pythonExecutable,
            String pythonVersion,
            boolean dockerCliAvailable,
            boolean dockerDaemonReachable,
            String dockerContext,
            String dockerServerVersion,
            List<String> pinnedLocalImages,
            Map<String, String> detectedCaches,
            List<ToolchainReadiness> toolchains,
            boolean readyForHardenedContainers,
            String summary) {
        public EnvironmentProbe {
            pinnedLocalImages = List.copyOf(pinnedLocalImages);
            detectedCaches = Collections.unmodifiableMap(new LinkedHashMap<>(detectedCaches));
            toolchains = List.copyOf(toolchains);
        }
    }

    private final Config config;

    public CapturedRepairRunner(Config config) {
        this.config = Objects.requireNonNull(config);
    }

    public Config config() {
        return config;
    }

    /**
     * Reads opt-in runner configuration from system properties ({@code evo.captured.repair.runner.*}).
     * Returns {@link Optional#empty()} when unconfigured so callers fail closed cleanly.
     */
    public static Optional<Config> fromSystemProperties() {
        String enabled = System.getProperty("evo.captured.repair.runner.enabled");
        String python = System.getProperty("evo.captured.repair.runner.python");
        String base = System.getProperty("evo.captured.repair.runner.baseImage");
        String buildTool = System.getProperty("evo.captured.repair.runner.buildTool", "auto");
        String mavenZip = System.getProperty("evo.captured.repair.runner.mavenZip");
        String m2 = System.getProperty("evo.captured.repair.runner.m2");
        String gradleZip = System.getProperty("evo.captured.repair.runner.gradleZip");
        String gradleHome = System.getProperty("evo.captured.repair.runner.gradleHome");
        String dockerContext = System.getProperty("evo.captured.repair.runner.dockerContext");
        String skipTests = System.getProperty("evo.captured.repair.runner.skipTests", "false");
        String workRoot = System.getProperty("evo.captured.repair.runner.workRoot");
        String timeoutSeconds = System.getProperty("evo.captured.repair.runner.timeoutSeconds");
        String mode = System.getProperty("evo.captured.repair.runner.mode", "qemu-docker");
        String cacheTag = System.getProperty("evo.captured.repair.runner.baseCacheTag");
        String npmCache = System.getProperty("evo.captured.repair.runner.npmCache");
        String pipWheelhouse = System.getProperty("evo.captured.repair.runner.pipWheelhouse");
        String goModCache = System.getProperty("evo.captured.repair.runner.goModCache");

        boolean anyConfigured = mavenZip != null || m2 != null || gradleZip != null || gradleHome != null
                || npmCache != null || pipWheelhouse != null || goModCache != null;
        if ("false".equalsIgnoreCase(enabled != null ? enabled.strip() : null)) {
            return Optional.empty();
        }
        if (!"true".equalsIgnoreCase(enabled != null ? enabled.strip() : null) && !anyConfigured) {
            return Optional.empty();
        }
        if (workRoot == null || workRoot.isBlank()) {
            throw new IllegalArgumentException("evo.captured.repair.runner.workRoot is required when isolated runner is enabled");
        }
        Duration timeout = timeoutSeconds == null || timeoutSeconds.isBlank()
                ? Duration.ofMinutes(15)
                : Duration.ofSeconds(Long.parseLong(timeoutSeconds.trim()));
        return Optional.of(new Config(
                python,
                Optional.ofNullable(base).filter(s -> !s.isBlank()),
                buildTool,
                Optional.ofNullable(mavenZip).filter(s -> !s.isBlank()).map(Path::of),
                Optional.ofNullable(m2).filter(s -> !s.isBlank()).map(Path::of),
                Optional.ofNullable(gradleZip).filter(s -> !s.isBlank()).map(Path::of),
                Optional.ofNullable(gradleHome).filter(s -> !s.isBlank()).map(Path::of),
                Optional.ofNullable(dockerContext),
                Boolean.parseBoolean(skipTests),
                Path.of(workRoot).toAbsolutePath().normalize(),
                timeout,
                IsolationMode.parse(mode),
                Optional.ofNullable(cacheTag).filter(s -> !s.isBlank()),
                Optional.ofNullable(npmCache).filter(s -> !s.isBlank()).map(Path::of),
                Optional.ofNullable(pipWheelhouse).filter(s -> !s.isBlank()).map(Path::of),
                Optional.ofNullable(goModCache).filter(s -> !s.isBlank()).map(Path::of)));
    }

    /** Extracts the bundled control scripts from {@code /captured-repair/*} into {@code targetDir}. */
    static void extractControls(Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        for (String name : CONTROL_RESOURCES) {
            try (var in = CapturedRepairRunner.class.getResourceAsStream("/captured-repair/" + name)) {
                if (in == null) {
                    throw new IOException("CAPTURED_REPAIR_CONTROL_MISSING: " + name);
                }
                byte[] bytes = in.readAllBytes();
                Files.write(targetDir.resolve(name), bytes);
            }
        }
    }

    /**
     * Stages control resources + proposal/plan inputs and invokes the isolated Python/QEMU-in-Docker
     * verifier. Never runs Maven or Gradle directly on the host.
     */
    public InvocationResult run(
            Path repoRoot,
            CapturedRepair.Outcome proposalOutcome,
            CapturedRepairVerification.Result initialVerification,
            BooleanSupplier cancelled) throws IOException {
        return run(repoRoot, proposalOutcome, initialVerification, false, Optional.empty(), cancelled);
    }

    public InvocationResult run(
            Path repoRoot,
            CapturedRepair.Outcome proposalOutcome,
            CapturedRepairVerification.Result initialVerification,
            boolean behavioralDiff,
            Optional<String> behavioralProbesJson,
            BooleanSupplier cancelled) throws IOException {
        return run(repoRoot, proposalOutcome, initialVerification, behavioralDiff, behavioralProbesJson,
                Optional.empty(), cancelled);
    }

    public InvocationResult run(
            Path repoRoot,
            CapturedRepair.Outcome proposalOutcome,
            CapturedRepairVerification.Result initialVerification,
            boolean behavioralDiff,
            Optional<String> behavioralProbesJson,
            Optional<String> selectedTests,
            BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(repoRoot);
        Objects.requireNonNull(proposalOutcome);
        Objects.requireNonNull(initialVerification);
        if (cancelled != null && cancelled.getAsBoolean()) {
            throw new IOException("ISOLATED_RUNNER_CANCELLED");
        }
        String planHex = initialVerification.planIdentity().value().replace("sha256:", "");
        Path runDir = config.workRoot().resolve("run-" + planHex + "-" + UUID.randomUUID().toString().substring(0, 8));
        Path controlsDir = runDir.resolve("controls");
        Path outDir = runDir.resolve("out");
        extractControls(controlsDir);
        Files.createDirectories(outDir);

        Path proposalFile = runDir.resolve("proposal.json");
        Path planFile = runDir.resolve("verification.json");
        Files.writeString(proposalFile, CanonicalJson.write(proposalOutcome), StandardCharsets.UTF_8);
        Files.writeString(planFile, CanonicalJson.write(initialVerification), StandardCharsets.UTF_8);
        Optional<Path> probesFile = Optional.empty();
        if (!config.skipTests() && behavioralProbesJson != null && behavioralProbesJson.isPresent() && !behavioralProbesJson.orElseThrow().isBlank()) {
            Path pf = runDir.resolve("behavioral-probes.json");
            Files.writeString(pf, behavioralProbesJson.orElseThrow(), StandardCharsets.UTF_8);
            probesFile = Optional.of(pf);
        }

        var command = buildCommand(controlsDir.resolve("run.py"), repoRoot.toAbsolutePath().normalize(),
                proposalFile, planFile, outDir, config, behavioralDiff, probesFile, selectedTests);
        var process = new ProcessBuilder(command)
                .directory(runDir.toFile())
                .redirectErrorStream(true)
                .start();
        final int maxLogBytes = 256 * 1024;
        var tailBuffer = new java.io.ByteArrayOutputStream(8192);
        Thread drainer = new Thread(() -> {
            byte[] chunk = new byte[4096];
            try (var in = process.getInputStream()) {
                int read;
                while ((read = in.read(chunk)) != -1) {
                    synchronized (tailBuffer) {
                        tailBuffer.write(chunk, 0, read);
                        if (tailBuffer.size() > maxLogBytes * 2) {
                            byte[] current = tailBuffer.toByteArray();
                            tailBuffer.reset();
                            tailBuffer.write(current, current.length - maxLogBytes, maxLogBytes);
                        }
                    }
                }
            } catch (IOException ignored) {
                /* Process stream closed on termination. */
            }
        }, "evo-isolated-runner-log");
        drainer.setDaemon(true);
        drainer.start();

        long deadlineNanos = System.nanoTime() + config.timeout().toNanos();
        try {
            while (true) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    destroyProcessTree(process);
                    deleteContextDirQuietly(outDir.resolve("context"));
                    throw new IOException("ISOLATED_RUNNER_CANCELLED");
                }
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
                if (remainingMs <= 0L) {
                    destroyProcessTree(process);
                    deleteContextDirQuietly(outDir.resolve("context"));
                    throw new IOException("ISOLATED_RUNNER_TIMEOUT");
                }
                if (process.waitFor(Math.min(200L, remainingMs), TimeUnit.MILLISECONDS)) {
                    break;
                }
            }
            drainer.join(2000L);
        } catch (InterruptedException interrupted) {
            destroyProcessTree(process);
            deleteContextDirQuietly(outDir.resolve("context"));
            Thread.currentThread().interrupt();
            throw new IOException("ISOLATED_RUNNER_INTERRUPTED", interrupted);
        } finally {
            // Preserve manifest.sha256 at capture root if a mock/legacy script wrote it only under context/inputs/
            Path rootManifest = outDir.resolve("manifest.sha256");
            Path nestedManifest = outDir.resolve("context").resolve("inputs").resolve("manifest.sha256");
            if (!Files.exists(rootManifest, LinkOption.NOFOLLOW_LINKS)
                    && Files.isRegularFile(nestedManifest, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.copy(nestedManifest, rootManifest, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                    /* Best-effort preservation before context cleanup. */
                }
            }
            deleteContextDirQuietly(outDir.resolve("context"));
        }
        String output;
        synchronized (tailBuffer) {
            output = tailBuffer.toString(StandardCharsets.UTF_8);
        }
        if (process.exitValue() != 0) {
            throw new IOException(formatFailureMessage(process.exitValue(), output));
        }
        Path captureFile = outDir.resolve("capture.json");
        if (!Files.isRegularFile(captureFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("ISOLATED_CAPTURE_MISSING");
        }
        ContentDigest captureDigest = ContentDigest.sha256(Files.readAllBytes(captureFile));
        CapturedRepairExecution execution = new CapturedRepairExecution(captureFile, captureDigest);
        return new InvocationResult(captureFile, captureDigest, execution);
    }

    static String formatFailureMessage(int exitCode, String output) {
        String safeOutput = output == null ? "" : com.evolution.backend.query.llm.LlmTransport.redactSecrets(output).text();
        return "ISOLATED_RUNNER_FAILED: exit=" + exitCode
                + " tail=" + safeOutput.substring(Math.max(0, safeOutput.length() - 800));
    }

    static List<String> buildCommand(
            Path runPy,
            Path repoRoot,
            Path proposalFile,
            Path verificationFile,
            Path outDir,
            Config config) {
        return buildCommand(runPy, repoRoot, proposalFile, verificationFile, outDir, config, false, Optional.empty());
    }

    static List<String> buildCommand(
            Path runPy,
            Path repoRoot,
            Path proposalFile,
            Path verificationFile,
            Path outDir,
            Config config,
            boolean behavioralDiff,
            Optional<Path> behavioralProbesFile) {
        return buildCommand(runPy, repoRoot, proposalFile, verificationFile, outDir, config,
                behavioralDiff, behavioralProbesFile, Optional.empty());
    }

    static List<String> buildCommand(
            Path runPy,
            Path repoRoot,
            Path proposalFile,
            Path verificationFile,
            Path outDir,
            Config config,
            boolean behavioralDiff,
            Optional<Path> behavioralProbesFile,
            Optional<String> selectedTests) {
        var cmd = new ArrayList<String>();
        cmd.add(config.pythonExecutable());
        cmd.add(runPy.toString());
        cmd.add("--repo");
        cmd.add(repoRoot.toString());
        cmd.add("--proposal");
        cmd.add(proposalFile.toString());
        cmd.add("--verification");
        cmd.add(verificationFile.toString());
        cmd.add("--build-tool");
        cmd.add(config.buildTool());
        cmd.add("--mode");
        cmd.add(config.isolationMode().cliFlag());
        cmd.add("--out");
        cmd.add(outDir.toString());
        config.baseCacheTag().ifPresent(tag -> {
            cmd.add("--base-cache-tag");
            cmd.add(tag);
        });
        config.baseImage().ifPresent(b -> {
            cmd.add("--base");
            cmd.add(b);
        });
        config.m2Repository().ifPresent(p -> {
            cmd.add("--m2");
            cmd.add(p.toAbsolutePath().normalize().toString());
        });
        config.mavenZip().ifPresent(p -> {
            cmd.add("--maven-zip");
            cmd.add(p.toAbsolutePath().normalize().toString());
        });
        config.gradleZip().ifPresent(p -> {
            cmd.add("--gradle-zip");
            cmd.add(p.toAbsolutePath().normalize().toString());
        });
        config.gradleHome().ifPresent(p -> {
            cmd.add("--gradle-home");
            cmd.add(p.toAbsolutePath().normalize().toString());
        });
        config.npmCache().ifPresent(p -> {
            cmd.add("--npm-cache");
            cmd.add(p.toAbsolutePath().normalize().toString());
        });
        config.pipWheelhouse().ifPresent(p -> {
            cmd.add("--pip-wheelhouse");
            cmd.add(p.toAbsolutePath().normalize().toString());
        });
        config.goModCache().ifPresent(p -> {
            cmd.add("--go-mod-cache");
            cmd.add(p.toAbsolutePath().normalize().toString());
        });
        config.dockerContext().ifPresent(ctx -> {
            cmd.add("--docker-context");
            cmd.add(ctx);
        });
        if (config.skipTests()) {
            cmd.add("--skip-tests");
        }
        if (behavioralDiff && !config.skipTests()) {
            cmd.add("--behavioral-diff");
        }
        if (!config.skipTests() && behavioralProbesFile != null && behavioralProbesFile.isPresent()) {
            cmd.add("--behavioral-probes");
            cmd.add(behavioralProbesFile.orElseThrow().toAbsolutePath().normalize().toString());
        }
        if (!config.skipTests() && selectedTests != null && selectedTests.isPresent()) {
            String filter = selectedTests.orElseThrow().strip();
            if (!filter.isEmpty()) {
                if (!SAFE_TEST_FILTER.matcher(filter).matches()) {
                    throw new IllegalArgumentException("UNSAFE_SELECTED_TESTS_FILTER");
                }
                cmd.add("--selected-tests");
                cmd.add(filter);
            }
        }
        return List.copyOf(cmd);
    }

    /**
     * Strictly read-only environment probe detecting Python 3, Docker CLI/daemon availability,
     * pinned local base images, and local offline dependency caches. NEVER executes target builds.
     */
    public static EnvironmentProbe probeEnvironment(Optional<String> preferredPython, Optional<String> preferredDockerContext) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean windows = os.contains("win");
        String pyExec = preferredPython != null && preferredPython.isPresent() && !preferredPython.orElseThrow().isBlank()
                ? preferredPython.orElseThrow().strip()
                : Config.defaultPython();
        String dockerCtx = preferredDockerContext != null && preferredDockerContext.isPresent()
                ? preferredDockerContext.orElseThrow().strip()
                : (windows ? "desktop-linux" : "");

        var pyProbe = runShortProbe(List.of(pyExec, "--version"), Duration.ofSeconds(4));
        if (!pyProbe.ok() && preferredPython.isEmpty()) {
            String alt = "python".equals(pyExec) ? "python3" : "python";
            var altProbe = runShortProbe(List.of(alt, "--version"), Duration.ofSeconds(4));
            if (altProbe.ok()) {
                pyExec = alt;
                pyProbe = altProbe;
            }
        }
        boolean pyAvailable = pyProbe.ok() && pyProbe.stdout().toLowerCase(Locale.ROOT).contains("python 3");
        String pyVersion = pyAvailable ? pyProbe.stdout().strip() : "";

        var dockerVer = runShortProbe(List.of("docker", "--version"), Duration.ofSeconds(4));
        boolean dockerCli = dockerVer.ok();
        boolean dockerDaemon = false;
        String dockerServerVer = "";
        var pinnedImages = new ArrayList<String>();
        if (dockerCli) {
            var infoCmd = new ArrayList<String>();
            infoCmd.add("docker");
            if (!dockerCtx.isBlank()) {
                infoCmd.add("--context");
                infoCmd.add(dockerCtx);
            }
            infoCmd.addAll(List.of("info", "--format", "{{.ServerVersion}}"));
            var infoRes = runShortProbe(infoCmd, Duration.ofSeconds(5));
            if (!infoRes.ok() && !dockerCtx.isBlank()) {
                var defaultInfo = runShortProbe(List.of("docker", "info", "--format", "{{.ServerVersion}}"), Duration.ofSeconds(5));
                if (defaultInfo.ok()) {
                    dockerCtx = "";
                    infoRes = defaultInfo;
                }
            }
            if (infoRes.ok() && !infoRes.stdout().isBlank()) {
                dockerDaemon = true;
                dockerServerVer = infoRes.stdout().lines().findFirst().orElse("").strip();
                var imgCmd = new ArrayList<String>();
                imgCmd.add("docker");
                if (!dockerCtx.isBlank()) {
                    imgCmd.add("--context");
                    imgCmd.add(dockerCtx);
                }
                imgCmd.addAll(List.of("images", "--digests", "--format", "{{.Repository}}@{{.Digest}}"));
                var imgRes = runShortProbe(imgCmd, Duration.ofSeconds(5));
                if (imgRes.ok()) {
                    for (String line : imgRes.stdout().lines().toList()) {
                        String trimmed = line.strip();
                        if (PINNED_BASE_IMAGE_PATTERN.matcher(trimmed).matches() && !pinnedImages.contains(trimmed)) {
                            pinnedImages.add(trimmed);
                            if (pinnedImages.size() >= 16) break;
                        }
                    }
                }
            }
        }

        // Read-only local offline cache detection (zero mutations, zero execution)
        var caches = new LinkedHashMap<String, String>();
        String home = System.getProperty("user.home", "");
        Path userHome = home.isBlank() ? null : Path.of(home);
        if (userHome != null) {
            Path m2 = userHome.resolve(".m2").resolve("repository");
            if (Files.isDirectory(m2)) caches.put("m2Repository", m2.toAbsolutePath().normalize().toString());
            Path gradleHome = userHome.resolve(".gradle");
            if (Files.isDirectory(gradleHome)) caches.put("gradleHome", gradleHome.toAbsolutePath().normalize().toString());
            findDistributionZip(userHome.resolve(".m2").resolve("wrapper").resolve("dists"), "apache-maven-", ".zip")
                    .ifPresent(p -> caches.put("mavenZip", p.toString()));
            findDistributionZip(userHome.resolve(".gradle").resolve("wrapper").resolve("dists"), "gradle-", ".zip")
                    .ifPresent(p -> caches.put("gradleZip", p.toString()));
            Path npmUnix = userHome.resolve(".npm");
            String localAppData = System.getenv("LOCALAPPDATA");
            Path npmWin = (localAppData != null && !localAppData.isBlank()) ? Path.of(localAppData, "npm-cache") : null;
            if (npmWin != null && Files.isDirectory(npmWin)) caches.put("npmCache", npmWin.toAbsolutePath().normalize().toString());
            else if (Files.isDirectory(npmUnix)) caches.put("npmCache", npmUnix.toAbsolutePath().normalize().toString());
            Path pipCache = windows && localAppData != null && !localAppData.isBlank()
                    ? Path.of(localAppData, "pip", "cache")
                    : userHome.resolve(".cache").resolve("pip");
            if (Files.isDirectory(pipCache)) caches.put("pipWheelhouse", pipCache.toAbsolutePath().normalize().toString());
            String gopath = System.getenv("GOPATH");
            Path goMod = (gopath != null && !gopath.isBlank())
                    ? Path.of(gopath, "pkg", "mod")
                    : userHome.resolve("go").resolve("pkg").resolve("mod");
            if (Files.isDirectory(goMod)) caches.put("goModCache", goMod.toAbsolutePath().normalize().toString());
        }

        var toolchains = new ArrayList<ToolchainReadiness>();
        toolchains.add(buildReadiness("maven", dockerDaemon && pyAvailable,
                caches.containsKey("m2Repository") && caches.containsKey("mavenZip"),
                caches.get("m2Repository"),
                "Requires Docker daemon, Python 3, ~/.m2/repository, and pinned apache-maven-*-bin.zip"));
        toolchains.add(buildReadiness("gradle", dockerDaemon && pyAvailable,
                caches.containsKey("gradleHome") && caches.containsKey("gradleZip"),
                caches.get("gradleHome"),
                "Requires Docker daemon, Python 3, ~/.gradle cache, and pinned gradle-*-bin.zip"));
        toolchains.add(buildReadiness("node", dockerDaemon && pyAvailable,
                caches.containsKey("npmCache"),
                caches.get("npmCache"),
                "Requires Docker daemon, pinned node@sha256 image, and offline npm cache / node_modules"));
        toolchains.add(buildReadiness("python", dockerDaemon && pyAvailable,
                caches.containsKey("pipWheelhouse"),
                caches.get("pipWheelhouse"),
                "Requires Docker daemon, pinned python@sha256 image, and optional offline wheelhouse"));
        toolchains.add(buildReadiness("go", dockerDaemon && pyAvailable,
                caches.containsKey("goModCache"),
                caches.get("goModCache"),
                "Requires Docker daemon, pinned golang@sha256 image, and offline GOMODCACHE"));

        boolean ready = pyAvailable && dockerDaemon;
        String summary = ready
                ? ("Hardened Docker sandbox ready (Docker " + dockerServerVer + ", " + pyVersion
                        + ", " + caches.size() + " offline cache(s) detected). Zero host build execution.")
                : (!dockerCli ? "Docker CLI not found; repair verification stays fail-closed (PASSIVE_REANALYSIS_ONLY)."
                        : (!dockerDaemon ? "Docker daemon unreachable; start Docker Desktop/Engine to enable isolated sandbox runs."
                                : "Python 3 not found; required to orchestrate isolated container runs."));
        return new EnvironmentProbe(pyAvailable, pyExec, pyVersion, dockerCli, dockerDaemon, dockerCtx,
                dockerServerVer, pinnedImages, caches, toolchains, ready, summary);
    }

    private static ToolchainReadiness buildReadiness(String tool, boolean runtimeReady, boolean cacheReady,
                                                     String cachePath, String detail) {
        String status = !runtimeReady ? "DOCKER_UNAVAILABLE"
                : (cacheReady ? "READY" : OFFLINE_TOOLCHAIN_MISSING);
        return new ToolchainReadiness(tool, status, Optional.ofNullable(cachePath), detail);
    }

    private static Optional<Path> findDistributionZip(Path root, String prefix, String suffix) {
        if (root == null || !Files.isDirectory(root)) return Optional.empty();
        try (var stream = Files.walk(root, 4)) {
            return stream.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.startsWith(prefix) && name.endsWith(suffix);
                    })
                    .sorted()
                    .findFirst()
                    .map(p -> p.toAbsolutePath().normalize());
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    private record ShortProbe(boolean ok, String stdout) {}

    private static ShortProbe runShortProbe(List<String> command, Duration timeout) {
        try {
            var pb = new ProcessBuilder(command).redirectErrorStream(true);
            var proc = pb.start();
            var buf = new java.io.ByteArrayOutputStream(1024);
            Thread drainer = new Thread(() -> {
                try (var in = proc.getInputStream()) {
                    byte[] chunk = new byte[1024];
                    int n;
                    while ((n = in.read(chunk)) != -1) {
                        if (buf.size() < 32_768) {
                            buf.write(chunk, 0, Math.min(n, 32_768 - buf.size()));
                        }
                    }
                } catch (IOException ignored) {}
            }, "evo-sandbox-probe");
            drainer.setDaemon(true);
            drainer.start();
            boolean finished = proc.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                destroyProcessTree(proc);
                return new ShortProbe(false, "");
            }
            drainer.join(500L);
            return new ShortProbe(proc.exitValue() == 0, buf.toString(StandardCharsets.UTF_8));
        } catch (Exception unavailable) {
            return new ShortProbe(false, "");
        }
    }

    private static void deleteContextDirQuietly(Path contextDir) {
        if (contextDir == null) return;
        Path normalized = contextDir.toAbsolutePath().normalize();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path target = normalized;
        if (os.contains("win")) {
            String raw = normalized.toString();
            if (!raw.startsWith("\\\\?\\")) {
                target = Path.of(raw.startsWith("\\\\") ? "\\\\?\\UNC\\" + raw.substring(2) : "\\\\?\\" + raw);
            }
        }
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            Files.walkFileTree(target, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                    try {
                        file.toFile().setWritable(true);
                        Files.deleteIfExists(file);
                    } catch (IOException ignored) {}
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    try {
                        dir.toFile().setWritable(true);
                        Files.deleteIfExists(dir);
                    } catch (IOException ignored) {}
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            /* Best-effort cleanup of staging context directory. */
        }
    }

    private static void destroyProcessTree(Process process) {
        try {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (RuntimeException ignored) {
            /* Best-effort child cleanup. */
        }
        process.destroyForcibly();
    }

    // ─── WP4.4: Multi-JDK (8/11/17/21) Pinned Matrix & Two-Tier Isolated Certification ───

    public static final Map<Integer, String> JDK_IMAGE_MATRIX = Collections.unmodifiableMap(new TreeMap<>(Map.of(
            8, "eclipse-temurin@sha256:238468a6b1e411e775bf264e639189a938eb0e887f16a2dea027c854c616b4f1",
            11, "eclipse-temurin@sha256:9d369f804a33762f34b24cd9d1b9722fd810c16001da58bd63d0919f19a712c3",
            17, "eclipse-temurin@sha256:7a0b2bc7e1372bb05ee361fb79e8a9e822df47343b8716253b3bbaa75060653e",
            21, DEFAULT_JAVA_BASE_IMAGE)));

    public static final Set<Integer> SUPPORTED_JDK_VERSIONS = Set.of(8, 11, 17, 21);

    public enum CertificationTier {
        TIER_1_FAST_CONTAINER,
        TIER_2_HYPERVISOR_CERT
    }

    public record TwoTierCertificationResult(
            boolean certified,
            int detectedJdkVersion,
            String pinnedBaseImage,
            int tier1RoundsExecuted,
            boolean tier2HypervisorExecuted,
            Optional<CapturedRepairVerification.ThreeLayerCertification> threeLayerCertification,
            ContentDigest certificateDigest,
            String summary) {
        public TwoTierCertificationResult {
            certificateDigest = certificateDigest == null
                    ? CanonicalJson.sha256(List.of("two-tier-cert:v1", certified, detectedJdkVersion,
                    pinnedBaseImage, tier1RoundsExecuted, tier2HypervisorExecuted, summary))
                    : certificateDigest;
        }
    }

    /**
     * Passively detects the required JDK major version ({@code 8 | 11 | 17 | 21}) from
     * {@code pom.xml} or {@code build.gradle(.kts)} without executing any target build ({@code I1}).
     */
    public static int detectRequiredJdkVersion(Path repoRoot) {
        if (repoRoot == null || !Files.isDirectory(repoRoot, LinkOption.NOFOLLOW_LINKS)) {
            return 21;
        }
        Path pom = repoRoot.resolve("pom.xml");
        if (Files.isRegularFile(pom, LinkOption.NOFOLLOW_LINKS)) {
            try {
                String text = Files.readString(pom, StandardCharsets.UTF_8);
                if (text.length() > 262_144) text = text.substring(0, 262_144);
                text = java.util.regex.Pattern.compile("<!--.*?-->", java.util.regex.Pattern.DOTALL)
                        .matcher(text).replaceAll("");
                for (String tag : List.of("maven.compiler.release", "java.version",
                        "maven.compiler.source", "maven.compiler.target", "release", "source")) {
                    var m = java.util.regex.Pattern.compile(
                            "<" + java.util.regex.Pattern.quote(tag) + ">\\s*([^<$]+?)\\s*</"
                                    + java.util.regex.Pattern.quote(tag) + ">")
                            .matcher(text);
                    if (m.find()) {
                        OptionalInt parsed = normalizeJdkMajor(m.group(1));
                        if (parsed.isPresent()) return parsed.getAsInt();
                    }
                }
            } catch (IOException ignored) {}
        }
        for (String gName : List.of("build.gradle", "build.gradle.kts")) {
            Path gf = repoRoot.resolve(gName);
            if (Files.isRegularFile(gf, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    String text = Files.readString(gf, StandardCharsets.UTF_8);
                    if (text.length() > 262_144) text = text.substring(0, 262_144);
                    text = java.util.regex.Pattern.compile("/\\*.*?\\*/", java.util.regex.Pattern.DOTALL)
                            .matcher(text).replaceAll("");
                    text = java.util.regex.Pattern.compile("//[^\\n]*").matcher(text).replaceAll("");
                    for (String regex : List.of(
                            "jvmToolchain\\(\\s*(\\d+)\\s*\\)",
                            "JavaLanguageVersion\\.of\\(\\s*(\\d+)\\s*\\)",
                            "sourceCompatibility\\s*=?\\s*([^\\s;\\n]+)",
                            "targetCompatibility\\s*=?\\s*([^\\s;\\n]+)")) {
                        var m = java.util.regex.Pattern.compile(regex).matcher(text);
                        if (m.find()) {
                            OptionalInt parsed = normalizeJdkMajor(m.group(1));
                            if (parsed.isPresent()) return parsed.getAsInt();
                        }
                    }
                } catch (IOException ignored) {}
            }
        }
        return 21;
    }

    /** Resolves the pinned official SHA-256 base image digest for {@code jdkVersion} ({@code 8/11/17/21}). */
    public static String resolveBaseImageForJdk(int jdkVersion, Optional<String> explicitBaseImage) {
        if (explicitBaseImage != null && explicitBaseImage.isPresent() && !explicitBaseImage.orElseThrow().isBlank()) {
            String raw = explicitBaseImage.orElseThrow().strip();
            if (!PINNED_BASE_IMAGE_PATTERN.matcher(raw).matches()) {
                throw new IllegalArgumentException("Base image must be a pinned official digest: " + raw);
            }
            return raw;
        }
        String pinned = JDK_IMAGE_MATRIX.get(jdkVersion);
        if (pinned == null) {
            throw new IllegalArgumentException("Unsupported JDK version: " + jdkVersion + " (supported: 8, 11, 17, 21)");
        }
        return pinned;
    }

    /** Builds a Tier-1 fast-loop {@link Config} pinned to the detected JDK and {@code DOCKER_HARDENED_CONTAINER}. */
    public static Config tier1FastLoopConfig(Config base, Path repoRoot) {
        Objects.requireNonNull(base, "base");
        int jdk = detectRequiredJdkVersion(repoRoot);
        String pinned = resolveBaseImageForJdk(jdk, base.baseImage());
        return new Config(
                base.pythonExecutable(),
                Optional.of(pinned),
                base.buildTool(),
                base.mavenZip(),
                base.m2Repository(),
                base.gradleZip(),
                base.gradleHome(),
                base.dockerContext(),
                base.skipTests(),
                base.workRoot(),
                base.timeout(),
                IsolationMode.DOCKER_HARDENED_CONTAINER,
                base.baseCacheTag(),
                base.npmCache(),
                base.pipWheelhouse(),
                base.goModCache());
    }

    /** Builds a Tier-2 final-certification {@link Config} pinned to {@code QEMU_IN_DOCKER} for Negative Control. */
    public static Config tier2HypervisorConfig(Config base, Path repoRoot) {
        Objects.requireNonNull(base, "base");
        int jdk = detectRequiredJdkVersion(repoRoot);
        String pinned = resolveBaseImageForJdk(jdk, base.baseImage());
        return new Config(
                base.pythonExecutable(),
                Optional.of(pinned),
                base.buildTool(),
                base.mavenZip(),
                base.m2Repository(),
                base.gradleZip(),
                base.gradleHome(),
                base.dockerContext(),
                false,
                base.workRoot(),
                base.timeout(),
                IsolationMode.QEMU_IN_DOCKER,
                base.baseCacheTag(),
                base.npmCache(),
                base.pipWheelhouse(),
                base.goModCache());
    }

    /**
     * Builds the CLI invocation for {@code run.py} augmented with WP4.4 Tier-1/Tier-2 flags:
     * {@code --jdk-version}, {@code --m2-readonly-mount}, {@code --collect-jacoco}, and
     * {@code --dynamic-slice-timeout}.
     */
    public static List<String> buildTwoTierCommand(
            Path script,
            Path repoRoot,
            Path proposalFile,
            Path verificationFile,
            Path outDir,
            Config config,
            CertificationTier tier,
            int jdkVersion,
            boolean m2ReadonlyMount,
            boolean collectJacoco,
            int dynamicSliceTimeoutSeconds,
            boolean behavioralDiff,
            Optional<Path> behavioralProbesFile,
            Optional<String> selectedTests) {
        if (!SUPPORTED_JDK_VERSIONS.contains(jdkVersion)) {
            throw new IllegalArgumentException("Unsupported JDK version: " + jdkVersion);
        }
        Config effectiveConfig = tier == CertificationTier.TIER_1_FAST_CONTAINER
                ? new Config(config.pythonExecutable(), Optional.of(resolveBaseImageForJdk(jdkVersion, config.baseImage())),
                config.buildTool(), config.mavenZip(), config.m2Repository(), config.gradleZip(), config.gradleHome(),
                config.dockerContext(), config.skipTests(), config.workRoot(), config.timeout(),
                IsolationMode.DOCKER_HARDENED_CONTAINER, config.baseCacheTag(),
                config.npmCache(), config.pipWheelhouse(), config.goModCache())
                : new Config(config.pythonExecutable(), Optional.of(resolveBaseImageForJdk(jdkVersion, config.baseImage())),
                config.buildTool(), config.mavenZip(), config.m2Repository(), config.gradleZip(), config.gradleHome(),
                config.dockerContext(), false, config.workRoot(), config.timeout(),
                IsolationMode.QEMU_IN_DOCKER, config.baseCacheTag(),
                config.npmCache(), config.pipWheelhouse(), config.goModCache());

        var cmd = new ArrayList<>(buildCommand(
                script, repoRoot, proposalFile, verificationFile, outDir,
                effectiveConfig, behavioralDiff, behavioralProbesFile,
                tier == CertificationTier.TIER_1_FAST_CONTAINER ? selectedTests : Optional.empty()));
        cmd.add("--jdk-version");
        cmd.add(Integer.toString(jdkVersion));
        if (tier == CertificationTier.TIER_1_FAST_CONTAINER && m2ReadonlyMount) {
            cmd.add("--m2-readonly-mount");
        }
        if (collectJacoco) {
            cmd.add("--collect-jacoco");
        }
        if (dynamicSliceTimeoutSeconds > 0) {
            cmd.add("--dynamic-slice-timeout");
            cmd.add(Integer.toString(dynamicSliceTimeoutSeconds));
        }
        return List.copyOf(cmd);
    }

    /**
     * Orchestrates Two-Tier Certification: Tier-2 Hypervisor ({@code QEMU_IN_DOCKER} / Hyper-V)
     * is invoked AT MOST ONCE and ONLY when Tier-1 produced a 4-gate winning candidate.
     */
    public static TwoTierCertificationResult orchestrateTwoTier(
            Path repoRoot,
            com.evolution.backend.query.repair.ClosedLoopRepairOrchestrator.CampaignResult tier1Campaign,
            java.util.function.Function<
                    com.evolution.backend.query.repair.ClosedLoopRepairOrchestrator.FourGateEvaluation,
                    CapturedRepairVerification.ThreeLayerCertification> tier2HypervisorCertifier) {
        Objects.requireNonNull(tier1Campaign, "tier1Campaign");
        Objects.requireNonNull(tier2HypervisorCertifier, "tier2HypervisorCertifier");
        int jdk = detectRequiredJdkVersion(repoRoot);
        String pinned = resolveBaseImageForJdk(jdk, Optional.empty());
        if (!tier1Campaign.succeeded() || tier1Campaign.winningCandidate().isEmpty()) {
            return new TwoTierCertificationResult(
                    false, jdk, pinned, tier1Campaign.totalAttempts(), false, Optional.empty(), null,
                    "TIER_1_NO_4GATE_WINNER: Tier-2 hypervisor skipped (0 wasted VM boots)");
        }
        var winner = tier1Campaign.winningCandidate().orElseThrow();
        var cert = tier2HypervisorCertifier.apply(winner);
        boolean certified = cert != null && cert.allowsConfirmApply();
        return new TwoTierCertificationResult(
                certified, jdk, pinned, tier1Campaign.totalAttempts(), true,
                Optional.ofNullable(cert),
                cert == null ? null : cert.certificateDigest(),
                certified
                        ? "TWO_TIER_CERTIFIED(JDK=" + jdk + ", tier1Rounds=" + tier1Campaign.totalAttempts() + ", tier2Boots=1)"
                        : "TIER_2_CERTIFICATION_REJECTED: " + (cert == null ? "NULL_CERT" : String.join("; ", cert.failureReasons())));
    }

    private static OptionalInt normalizeJdkMajor(String raw) {
        if (raw == null || raw.isBlank()) return OptionalInt.empty();
        String s = raw.strip().replace("\"", "").replace("'", "");
        var mv = java.util.regex.Pattern.compile("VERSION_1_(\\d+)|VERSION_(\\d+)").matcher(s);
        if (mv.find()) {
            int val = Integer.parseInt(mv.group(1) != null ? mv.group(1) : mv.group(2));
            return OptionalInt.of(snapSupportedJdk(val));
        }
        var m1 = java.util.regex.Pattern.compile("^1\\.(\\d+)(?:\\.\\d+)?$").matcher(s);
        if (m1.matches()) {
            return OptionalInt.of(snapSupportedJdk(Integer.parseInt(m1.group(1))));
        }
        var mn = java.util.regex.Pattern.compile("^(\\d+)(?:\\.\\d+)?$").matcher(s);
        if (mn.matches()) {
            return OptionalInt.of(snapSupportedJdk(Integer.parseInt(mn.group(1))));
        }
        return OptionalInt.empty();
    }

    private static int snapSupportedJdk(int major) {
        if (major <= 8) return 8;
        if (major <= 11) return 11;
        if (major <= 17) return 17;
        return 21;
    }
}
