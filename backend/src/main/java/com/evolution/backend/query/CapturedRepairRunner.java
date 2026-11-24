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
    static final List<String> CONTROL_RESOURCES = List.of(
            "Dockerfile", "guest-init.sh", "run.py", "ClassDependencyCensus.java");

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
            Duration timeout) {
        public Config {
            if (pythonExecutable == null || pythonExecutable.isBlank()) {
                pythonExecutable = defaultPython();
            }
            if (buildTool == null || buildTool.isBlank()) {
                buildTool = "auto";
            }
            if (!Set.of("auto", "maven", "gradle").contains(buildTool)) {
                throw new IllegalArgumentException("Unsupported buildTool: " + buildTool);
            }
            baseImage = Objects.requireNonNull(baseImage);
            mavenZip = Objects.requireNonNull(mavenZip);
            m2Repository = Objects.requireNonNull(m2Repository);
            gradleZip = Objects.requireNonNull(gradleZip);
            gradleHome = Objects.requireNonNull(gradleHome);
            dockerContext = Objects.requireNonNull(dockerContext);
            Objects.requireNonNull(workRoot);
            if (timeout == null || timeout.isNegative() || timeout.isZero()) {
                timeout = Duration.ofMinutes(15);
            }
        }

        private static String defaultPython() {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            return os.contains("win") ? "python" : "python3";
        }
    }

    public record InvocationResult(Path captureFile, ContentDigest captureDigest, CapturedRepairExecution execution) {}

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

        boolean anyConfigured = mavenZip != null || m2 != null || gradleZip != null || gradleHome != null;
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
                timeout));
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

        var command = buildCommand(controlsDir.resolve("run.py"), repoRoot.toAbsolutePath().normalize(),
                proposalFile, planFile, outDir, config);
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
                    throw new IOException("ISOLATED_RUNNER_CANCELLED");
                }
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
                if (remainingMs <= 0L) {
                    destroyProcessTree(process);
                    throw new IOException("ISOLATED_RUNNER_TIMEOUT");
                }
                if (process.waitFor(Math.min(200L, remainingMs), TimeUnit.MILLISECONDS)) {
                    break;
                }
            }
            drainer.join(2000L);
        } catch (InterruptedException interrupted) {
            destroyProcessTree(process);
            Thread.currentThread().interrupt();
            throw new IOException("ISOLATED_RUNNER_INTERRUPTED", interrupted);
        }
        String output;
        synchronized (tailBuffer) {
            output = tailBuffer.toString(StandardCharsets.UTF_8);
        }
        if (process.exitValue() != 0) {
            throw new IOException("ISOLATED_RUNNER_FAILED: exit=" + process.exitValue()
                    + " tail=" + output.substring(Math.max(0, output.length() - 800)));
        }
        Path captureFile = outDir.resolve("capture.json");
        if (!Files.isRegularFile(captureFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("ISOLATED_CAPTURE_MISSING");
        }
        ContentDigest captureDigest = ContentDigest.sha256(Files.readAllBytes(captureFile));
        CapturedRepairExecution execution = new CapturedRepairExecution(captureFile, captureDigest);
        return new InvocationResult(captureFile, captureDigest, execution);
    }

    static List<String> buildCommand(
            Path runPy,
            Path repoRoot,
            Path proposalFile,
            Path verificationFile,
            Path outDir,
            Config config) {
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
        cmd.add("--out");
        cmd.add(outDir.toString());
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
        config.dockerContext().ifPresent(ctx -> {
            cmd.add("--docker-context");
            cmd.add(ctx);
        });
        if (config.skipTests()) {
            cmd.add("--skip-tests");
        }
        return List.copyOf(cmd);
    }

    private static void destroyProcessTree(Process process) {
        try {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (RuntimeException ignored) {
            /* Best-effort child cleanup. */
        }
        process.destroyForcibly();
    }
}
