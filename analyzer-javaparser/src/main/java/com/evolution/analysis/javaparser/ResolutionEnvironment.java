package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.analysis.ClasspathEntry;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.EntityScope;
import com.evolution.analysis.contract.semantic.EntityOrigin;
import com.evolution.analysis.frontend.*;
import com.github.javaparser.symbolsolver.resolution.typesolvers.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarInputStream;

/** Sources, platform loader and verified JAR snapshots only; no application classloader or directory scanning. */
final class ResolutionEnvironment {
    final MemoryTypeSolver project = new MemoryTypeSolver();
    final ClassLoaderTypeSolver platform = new ClassLoaderTypeSolver(ClassLoader.getPlatformClassLoader());
    final CombinedTypeSolver solver = new CombinedTypeSolver(project, platform);
    private final ClasspathEntry platformEntry;
    private final List<Artifact> artifacts = new ArrayList<>();
    private record Artifact(ClasspathEntry entry, JarTypeSolver solver) {}
    record Origin(EntityOrigin kind, EntityScope scope) {}

    ResolutionEnvironment(FrontendRequest request) {
        platformEntry = request.platform().entry();
        try {
            Path runtime = Path.of(System.getProperty("java.home"));
            if (Runtime.version().feature() != 21 || !Files.isSameFile(runtime, request.platform().javaHome()))
                throw new FrontendInputException("frontend.platform-view", "Only the verified running JDK 21 platform view is supported");
            if (!platformEntry.logicalName().equals("jdk-runtime:" + Runtime.version()))
                throw new FrontendInputException("frontend.platform-version", "Platform coordinate must identify the running JDK version");
            verify(platformEntry, Files.readAllBytes(runtime.resolve("lib/modules")));
            var paths = new HashSet<Path>();
            for (var input : request.dependencies()) {
                if (!paths.add(input.path().toRealPath())) throw new FrontendInputException("frontend.duplicate-binary", "Aliased binary inputs are not allowed");
                byte[] bytes = Files.readAllBytes(input.path());
                verify(input.entry(), bytes);
                validateJar(bytes);
                var jar = new JarTypeSolver(new ByteArrayInputStream(bytes));
                artifacts.add(new Artifact(input.entry(), jar)); solver.add(jar);
            }
        } catch (IOException exception) {
            throw new FrontendInputException("frontend.input-io", "Cannot read a supplied platform or dependency artifact");
        }
    }
    private static void verify(ClasspathEntry entry, byte[] bytes) {
        if (!ContentDigest.sha256(bytes).equals(entry.contentDigest())) throw new FrontendInputException("frontend.artifact-digest", "Artifact bytes differ from the manifest");
    }
    private static void validateJar(byte[] bytes) throws IOException {
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') throw new FrontendInputException("frontend.jar-format", "Dependency is not a JAR archive");
        try (var jar = new JarInputStream(new ByteArrayInputStream(bytes))) {
            var manifest = jar.getManifest();
            if (manifest != null && manifest.getMainAttributes().getValue("Class-Path") != null)
                throw new FrontendInputException("frontend.jar-classpath", "JAR manifest classpaths require an explicit supported input plan");
            var seen = new HashSet<String>();
            for (var entry = jar.getNextJarEntry(); entry != null; entry = jar.getNextJarEntry()) {
                if (!seen.add(entry.getName())) throw new FrontendInputException("frontend.jar-duplicate", "Duplicate JAR entries are unsupported");
                if (entry.getName().startsWith("META-INF/versions/")) throw new FrontendInputException("frontend.multi-release", "Multi-release JAR views are not supported by this slice");
            }
        }
    }
    Origin origin(String qualifiedName) {
        if (platform.tryToSolveType(qualifiedName).isSolved()) return origin(EntityOrigin.JDK, platformEntry);
        for (var artifact : artifacts) {
            if (artifact.solver().getKnownClasses().contains(qualifiedName)) return origin(EntityOrigin.DEPENDENCY, artifact.entry());
        }
        throw new IllegalArgumentException("selected declaration has no verified origin");
    }
    boolean duplicateExternal(String name) {
        int count = platform.tryToSolveType(name).isSolved() ? 1 : 0;
        for (var artifact : artifacts) if (artifact.solver().getKnownClasses().contains(name)) count++;
        return count > 1;
    }
    private static Origin origin(EntityOrigin kind, ClasspathEntry entry) {
        return new Origin(kind, EntityScope.external(kind, entry.logicalName(), entry.contentDigest()));
    }
}
