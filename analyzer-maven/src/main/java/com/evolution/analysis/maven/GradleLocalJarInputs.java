package com.evolution.analysis.maven;

import com.evolution.analysis.contract.analysis.ClasspathEntry;
import com.evolution.analysis.contract.analysis.ClasspathEntryKind;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.frontend.BinaryInput;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.jar.JarFile;
import java.util.zip.CRC32;

/** Passive JVM view of a repository-captured local JAR dependency. */
public final class GradleLocalJarInputs {
    public static BinaryInput recover(
            Path cache,
            String logicalPath,
            byte[] bytes,
            ContentDigest expectedDigest,
            long maxArtifactBytes,
            long maxExpandedBytes) throws IOException {
        Objects.requireNonNull(cache);
        Objects.requireNonNull(logicalPath);
        Objects.requireNonNull(expectedDigest);
        if (bytes == null || bytes.length < 4 || bytes.length > maxArtifactBytes) {
            throw new IOException("Local JAR byte budget exceeded or empty");
        }
        if (!expectedDigest.equals(ContentDigest.sha256(bytes))) {
            throw new IOException("Local JAR digest mismatch");
        }
        if (bytes[0] != 'P' || bytes[1] != 'K' || bytes[2] != 3 || bytes[3] != 4) {
            throw new IOException("Invalid local JAR header");
        }
        Path path = MavenDependencyArtifactProvider.materializeEmbeddedJar(cache, bytes, expectedDigest);
        var names = new HashSet<String>();
        var buffer = new byte[8192];
        long remaining = maxExpandedBytes;
        try (var jar = new JarFile(path.toFile(), false)) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                checkCancelled();
                var entry = entries.nextElement();
                if (!names.add(entry.getName())) throw new IOException("Duplicate JAR entry");
                if (entry.isDirectory()) continue;
                var crc = new CRC32();
                long size = 0;
                try (var stream = jar.getInputStream(entry)) {
                    for (int read; (read = stream.read(buffer)) != -1; ) {
                        checkCancelled();
                        remaining -= read;
                        if (remaining < 0) throw new IOException("Expanded local JAR byte budget exceeded");
                        crc.update(buffer, 0, read);
                        size += read;
                    }
                }
                if (size != entry.getSize() || crc.getValue() != entry.getCrc()) {
                    throw new IOException("Invalid local JAR entry");
                }
            }
        }
        if (names.isEmpty()) throw new IOException("Empty local JAR");
        return new BinaryInput(
                new ClasspathEntry(ClasspathEntryKind.DEPENDENCY, "local-jar:" + logicalPath, expectedDigest),
                path);
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    private GradleLocalJarInputs() {}
}
