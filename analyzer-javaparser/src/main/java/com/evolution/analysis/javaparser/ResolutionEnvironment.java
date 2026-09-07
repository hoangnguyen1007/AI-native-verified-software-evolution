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
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipFile;

/** Sources, platform loader and verified JAR snapshots only; no application classloader or directory scanning. */
final class ResolutionEnvironment {
    final MemoryTypeSolver project = new MemoryTypeSolver();
    final CombinedTypeSolver platform = new CombinedTypeSolver();
    final CombinedTypeSolver solver = new CombinedTypeSolver(project, platform);
    private final ClasspathEntry platformEntry;
    private final List<Artifact> artifacts = new ArrayList<>();
    private record Artifact(BinaryInput input, JarTypeSolver solver) {}
    record Origin(EntityOrigin kind, EntityScope scope) {}

    ResolutionEnvironment(FrontendRequest request) {
        platformEntry = request.platform().entry();
        try {
            var platformPaths = new HashSet<Path>();
            for (var artifact : request.platform().artifacts()) {
                Path real = artifact.path().toRealPath();
                if (!platformPaths.add(real)) {
                    throw new FrontendInputException("frontend.duplicate-platform-artifact", "Aliased platform artifacts are not allowed");
                }
                byte[] bytes = Files.readAllBytes(real);
                verify(artifact.contentDigest(), bytes);
                switch (artifact.format()) {
                    case RUNTIME_MODULES -> {
                        Path runtimeModules = Path.of(System.getProperty("java.home")).resolve("lib/modules");
                        if (request.platform().release() != Runtime.version().feature()
                                || !Files.isSameFile(real, runtimeModules)) {
                            throw new FrontendInputException("frontend.platform-view", "Running-module symbols must identify the running JDK exactly");
                        }
                        platform.add(new ClassLoaderTypeSolver(ClassLoader.getPlatformClassLoader()));
                    }
                    case JAR -> {
                        validateJar(bytes);
                        platform.add(new JarTypeSolver(new ByteArrayInputStream(bytes)));
                    }
                    case JMOD -> platform.add(jmodSolver(real));
                }
            }
            var paths = new HashSet<Path>();
            for (var input : request.dependencies()) {
                if (!paths.add(input.path().toRealPath())) throw new FrontendInputException("frontend.duplicate-binary", "Aliased binary inputs are not allowed");
                byte[] bytes = Files.readAllBytes(input.path());
                verify(input.entry(), bytes);
                validateJar(bytes);
                var jar = new JarTypeSolver(new ByteArrayInputStream(bytes));
                artifacts.add(new Artifact(input, jar)); solver.add(jar);
            }
        } catch (IOException exception) {
            throw new FrontendInputException("frontend.input-io", "Cannot read a supplied platform or dependency artifact");
        }
    }
    private static void verify(ClasspathEntry entry, byte[] bytes) {
        verify(entry.contentDigest(), bytes);
    }
    private static void verify(ContentDigest expected, byte[] bytes) {
        if (!ContentDigest.sha256(bytes).equals(expected)) throw new FrontendInputException("frontend.artifact-digest", "Artifact bytes differ from the manifest");
    }
    private static JarTypeSolver jmodSolver(Path path) throws IOException {
        ByteArrayOutputStream normalized = new ByteArrayOutputStream();
        Set<String> seen = new HashSet<>();
        try (ZipFile jmod = new ZipFile(path.toFile());
                JarOutputStream jar = new JarOutputStream(normalized)) {
            List<? extends java.util.zip.ZipEntry> entries = jmod.stream()
                    .filter(entry -> !entry.isDirectory()
                            && entry.getName().startsWith("classes/")
                            && entry.getName().endsWith(".class")
                            && !entry.getName().equals("classes/module-info.class"))
                    .sorted(Comparator.comparing(java.util.zip.ZipEntry::getName)).toList();
            for (var source : entries) {
                String name = source.getName().substring("classes/".length());
                if (!seen.add(name)) throw new FrontendInputException("frontend.jmod-duplicate", "JMOD contains duplicate platform classes");
                JarEntry target = new JarEntry(name);
                target.setTime(0);
                jar.putNextEntry(target);
                try (InputStream input = jmod.getInputStream(source)) { input.transferTo(jar); }
                jar.closeEntry();
            }
        }
        if (seen.isEmpty()) throw new FrontendInputException("frontend.jmod-empty", "JMOD contains no platform classes");
        return new JarTypeSolver(new ByteArrayInputStream(normalized.toByteArray()));
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
            if (artifact.solver().getKnownClasses().contains(qualifiedName)) {
                if (artifact.input().entry().kind() == com.evolution.analysis.contract.analysis.ClasspathEntryKind.MODULE_OUTPUT) {
                    return new Origin(EntityOrigin.PROJECT,
                            EntityScope.project(artifact.input().reactorModule().orElseThrow()));
                }
                return origin(EntityOrigin.DEPENDENCY, artifact.input().entry());
            }
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
