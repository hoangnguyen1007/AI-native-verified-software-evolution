package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.filesystem.FilesystemJdkPlatformProvider;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.ingestion.IngestionEvidence;
import com.evolution.analysis.input.PlatformSymbolRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies 100% canonical output parity between ASM 9.10.1 {@code AsmTypeSolver} and Javassist across
 * generics, wildcards, recursive bounds, varargs, nested classes, records, sealed hierarchies, enums,
 * annotations with defaults, multi-release JARs, JMOD platform symbols, and corrupt bytecode fallback,
 * plus {@link ResolutionEnvironment#openVerificationSession()} deduplication and standalone tamper detection.
 */
class AsmTypeSolverEquivalenceTest {
    @TempDir Path tempDir;

    private Path compileToJar(String jarName, Map<String, String> sources, boolean multiRelease21, byte[] extraCorruptEntry) throws Exception {
        Path srcRoot = tempDir.resolve(jarName + "-src");
        Path outRoot = tempDir.resolve(jarName + "-out");
        Files.createDirectories(srcRoot);
        Files.createDirectories(outRoot);
        List<Path> files = new ArrayList<>();
        for (var e : sources.entrySet()) {
            Path p = srcRoot.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, e.getValue(), StandardCharsets.UTF_8);
            files.add(p);
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JDK javac required");
        var diags = new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
        try (var fm = compiler.getStandardFileManager(diags, null, StandardCharsets.UTF_8)) {
            boolean ok = compiler.getTask(null, fm, diags,
                    List.of("--release", "21", "-parameters", "-proc:none", "-d", outRoot.toString()),
                    null, fm.getJavaFileObjectsFromPaths(files)).call();
            assertTrue(ok, () -> "Fixture javac failed: " + diags.getDiagnostics());
        }
        Path jarPath = tempDir.resolve(jarName + ".jar");
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (multiRelease21) {
            manifest.getMainAttributes().putValue("Multi-Release", "true");
        }
        try (var jos = new JarOutputStream(Files.newOutputStream(jarPath), manifest)) {
            try (var walk = Files.walk(outRoot)) {
                List<Path> classFiles = walk.filter(Files::isRegularFile).sorted().toList();
                for (Path cf : classFiles) {
                    String rel = outRoot.relativize(cf).toString().replace('\\', '/');
                    jos.putNextEntry(new JarEntry(rel));
                    jos.write(Files.readAllBytes(cf));
                    jos.closeEntry();
                    if (multiRelease21 && rel.endsWith("MrHelper.class")) {
                        jos.putNextEntry(new JarEntry("META-INF/versions/21/" + rel));
                        jos.write(Files.readAllBytes(cf));
                        jos.closeEntry();
                    }
                }
            }
            if (extraCorruptEntry != null) {
                jos.putNextEntry(new JarEntry("lib/CorruptDummy.class"));
                jos.write(extraCorruptEntry);
                jos.closeEntry();
            }
        }
        return jarPath;
    }

    @Test
    void asmTypeSolverMatchesJavassistByteForByteAcrossRichBytecodeFeatures() throws Exception {
        var libSources = new LinkedHashMap<String, String>();
        libSources.put("lib/Tag.java", """
                package lib;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                public @interface Tag {
                    String value() default "default-tag";
                    int[] levels() default {1, 2};
                }
                """);
        libSources.put("lib/Status.java", """
                package lib;
                public enum Status {
                    OPEN(1), CLOSED(2);
                    public final int code;
                    Status(int code) { this.code = code; }
                    public int code() { return code; }
                }
                """);
        libSources.put("lib/Node.java", """
                package lib;
                import java.util.*;
                public sealed interface Node<T extends Comparable<T>> permits Leaf, Branch {
                    T key();
                    List<? extends T> values();
                }
                """);
        libSources.put("lib/Leaf.java", """
                package lib;
                import java.util.*;
                public record Leaf<T extends Comparable<T>>(T key) implements Node<T> {
                    @Override public List<? extends T> values() { return List.of(key); }
                }
                """);
        libSources.put("lib/Branch.java", """
                package lib;
                import java.util.*;
                public final class Branch<T extends Comparable<T>> implements Node<T> {
                    private final T key;
                    private final List<T> items;
                    public Branch(T key, List<T> items) { this.key = key; this.items = items; }
                    @Override public T key() { return key; }
                    @Override public List<? extends T> values() { return items; }
                    public static class Builder<E extends Comparable<E>> {
                        public Branch<E> of(E first, E... rest) {
                            var list = new ArrayList<E>();
                            list.add(first);
                            list.addAll(Arrays.asList(rest));
                            return new Branch<>(first, list);
                        }
                    }
                    public <R> List<R> transform(java.util.function.Function<? super T, ? extends R> fn) {
                        var out = new ArrayList<R>();
                        for (T item : items) out.add(fn.apply(item));
                        return out;
                    }
                }
                """);
        libSources.put("lib/MrHelper.java", """
                package lib;
                public class MrHelper {
                    public static String join(String sep, String... parts) {
                        return String.join(sep, parts);
                    }
                }
                """);

        Path jar = compileToJar("rich-lib", libSources, true, new byte[] {0x00, 0x01, 0x02, 0x03});
        var dep = new BinaryInput(
                new ClasspathEntry(ClasspathEntryKind.DEPENDENCY, "com.example:rich-lib:1.0.0@jar",
                        ContentDigest.sha256(Files.readAllBytes(jar))),
                jar);

        var platform = new FilesystemJdkPlatformProvider()
                .acquire(Path.of(System.getProperty("java.home")),
                        new PlatformSymbolRequest(21, 128, 128_000_000, 512_000_000))
                .platform().orElseThrow();

        var appSources = new LinkedHashMap<String, String>();
        appSources.put("app/Consumer.java", """
                package app;
                import java.util.*;
                import lib.*;
                @Tag("consumer")
                public class Consumer {
                    public String run() {
                        Leaf<String> leaf = new Leaf<>("a");
                        Branch.Builder<String> builder = new Branch.Builder<>();
                        Branch<String> branch = builder.of(leaf.key(), "b", "c");
                        List<Integer> lens = branch.transform(s -> s.length());
                        int code = Status.OPEN.code();
                        String joined = MrHelper.join("-", "x", "y");
                        return joined + ":" + lens.size() + ":" + code;
                    }
                }
                """);

        var baseReq = TestInputs.request(appSources, List.of(dep));
        var req = TestInputs.request(baseReq.sources(), platform, List.of(dep));
        var frontend = new JavaParserFrontend();

        FrontendResult asmResult;
        FrontendResult javassistResult;
        System.setProperty("evo.incremental.disabled", "true");
        try {
            System.setProperty("evo.perf.asmTypeSolver", "true");
            asmResult = frontend.analyze(req);

            System.setProperty("evo.perf.asmTypeSolver", "false");
            javassistResult = frontend.analyze(req);
        } finally {
            System.clearProperty("evo.perf.asmTypeSolver");
            System.clearProperty("evo.incremental.disabled");
        }

        assertEquals(FrontendResult.State.COMPLETED, asmResult.state());
        assertEquals(javassistResult, asmResult);
        assertEquals(CanonicalJson.write(javassistResult), CanonicalJson.write(asmResult));
        assertEquals(IngestionEvidence.digest(javassistResult), IngestionEvidence.digest(asmResult));
    }

    @Test
    void verificationSessionDeduplicatesArtifactHashingWithoutWeakeningStandaloneTamperCheck() throws Exception {
        Path jar = compileToJar("session-lib",
                Map.of("lib/Ping.java", "package lib; public class Ping { public static int v() { return 7; } }"),
                false, null);
        byte[] originalBytes = Files.readAllBytes(jar);
        ContentDigest originalDigest = ContentDigest.sha256(originalBytes);
        var dep = new BinaryInput(
                new ClasspathEntry(ClasspathEntryKind.DEPENDENCY, "com.example:session-lib:1.0.0@jar", originalDigest),
                jar);
        var frontend = new JavaParserFrontend();

        // Inside a VerificationSession, 3 module analyses reuse the same verified JAR digest without re-hashing disk!
        System.setProperty("evo.incremental.disabled", "true");
        try (var session = JavaParserFrontend.openVerificationSession()) {
            for (int i = 0; i < 3; i++) {
                var req = TestInputs.request(
                        Map.of("app/Caller" + i + ".java",
                                "package app; public class Caller" + i + " { int x() { return lib.Ping.v(); } }"),
                        List.of(dep));
                assertEquals(FrontendResult.State.COMPLETED, frontend.analyze(req).state());
            }
            assertTrue(session.diskHashCount() >= 1, "First use must verify on disk");
            assertTrue(session.sessionHitCount() >= 2,
                    "Subsequent modules in the same session should hit verified session cache, got " + session.sessionHitCount());
            assertTrue(session.verifiedArtifactCount() >= 1);
        } finally {
            System.clearProperty("evo.incremental.disabled");
        }

        // After session.close(), mutating the JAR on disk with SAME SIZE and SAME MTIME must STILL be rejected!
        var mtime = Files.getLastModifiedTime(jar);
        byte[] tampered = originalBytes.clone();
        tampered[tampered.length - 1] = (byte) (tampered[tampered.length - 1] ^ 0x5A);
        Files.write(jar, tampered);
        Files.setLastModifiedTime(jar, mtime);

        var tamperedReq = TestInputs.request(
                Map.of("app/AfterSession.java", "package app; public class AfterSession { int x() { return lib.Ping.v(); } }"),
                List.of(dep));
        FrontendInputException rejected = assertThrows(FrontendInputException.class, () -> frontend.analyze(tamperedReq));
        assertEquals("frontend.artifact-digest", rejected.diagnostic().code());
    }
}
