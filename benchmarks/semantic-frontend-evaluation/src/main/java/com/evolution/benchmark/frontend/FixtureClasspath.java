package com.evolution.benchmark.frontend;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.spi.ToolProvider;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Compiles controlled fixture dependencies with the running JDK; no build tool model is involved. */
final class FixtureClasspath {
    private FixtureClasspath() { }

    static Path compileJar(Path sourceRoot, Path work, String jarName) throws IOException {
        Path classes = work.resolve(jarName + "-classes");
        Files.createDirectories(classes);
        List<String> sources;
        try (var paths = Files.walk(sourceRoot)) {
            sources = paths.filter(path -> path.toString().endsWith(".java")).map(Path::toString).toList();
        }
        int exit = ToolProvider.findFirst("javac").orElseThrow(() -> new IllegalStateException("JDK javac is required"))
                .run(System.out, System.err, "--release", "21", "-d", classes.toString(), sources.getFirst());
        if (exit != 0) throw new IOException("fixture dependency compilation failed: " + jarName);
        Path jar = work.resolve(jarName + ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar)); var files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                output.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, output);
                output.closeEntry();
            }
        }
        return jar;
    }
}
