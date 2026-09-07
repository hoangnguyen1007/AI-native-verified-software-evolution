package com.evolution.analysis.filesystem;

import static org.junit.jupiter.api.Assertions.*;

import com.evolution.analysis.frontend.PlatformInput;
import com.evolution.analysis.input.PlatformSymbolRequest;
import com.evolution.analysis.input.PlatformSymbolResult;
import java.nio.file.*;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilesystemJdkPlatformProviderTest {
    @TempDir Path temp;

    @Test
    void acquiresSortedExactJmodsFromConfiguredJdkWithoutHostFallback() throws Exception {
        Path jdk = Files.createDirectories(temp.resolve("jdk-17"));
        Files.writeString(jdk.resolve("release"), "JAVA_VERSION=\"17.0.12\"\nIMPLEMENTOR=\"Fixture Vendor\"\n");
        Path jmods = Files.createDirectories(jdk.resolve("jmods"));
        archive(jmods.resolve("java.sql.jmod"), "classes/java/sql/Fixture.class");
        archive(jmods.resolve("java.base.jmod"), "classes/java/lang/Fixture.class");

        var request = new PlatformSymbolRequest(17, 10, 10_000, 20_000);
        PlatformSymbolResult first = new FilesystemJdkPlatformProvider().acquire(jdk, request);
        PlatformSymbolResult second = new FilesystemJdkPlatformProvider().acquire(jdk, request);

        Path relocated = Files.createDirectories(temp.resolve("relocated-jdk"));
        Files.copy(jdk.resolve("release"), relocated.resolve("release"));
        Path relocatedJmods = Files.createDirectories(relocated.resolve("jmods"));
        Files.copy(jmods.resolve("java.base.jmod"), relocatedJmods.resolve("java.base.jmod"));
        Files.copy(jmods.resolve("java.sql.jmod"), relocatedJmods.resolve("java.sql.jmod"));
        PlatformSymbolResult fromOtherLocator = new FilesystemJdkPlatformProvider().acquire(relocated, request);

        assertEquals(PlatformSymbolResult.Status.COMPLETE, first.status());
        assertEquals(first.identity(), second.identity());
        assertEquals(first.identity(), fromOtherLocator.identity(), "host JDK paths must not enter identity");
        assertEquals(17, first.platform().orElseThrow().release());
        assertEquals("17.0.12", first.platform().orElseThrow().version());
        assertEquals("Fixture Vendor", first.platform().orElseThrow().vendor());
        assertEquals(java.util.List.of("jmods/java.base.jmod", "jmods/java.sql.jmod"),
                first.platform().orElseThrow().artifacts().stream().map(PlatformInput.Artifact::logicalName).toList());
        assertTrue(first.platform().orElseThrow().artifacts().stream()
                .allMatch(value -> value.format() == PlatformInput.Format.JMOD));
    }

    @Test
    void releaseMismatchAndFiniteLimitsAreTyped() throws Exception {
        Path jdk = Files.createDirectories(temp.resolve("jdk"));
        Files.writeString(jdk.resolve("release"), "JAVA_VERSION=\"11.0.24\"\nIMPLEMENTOR=\"Fixture\"\n");
        Path jmods = Files.createDirectories(jdk.resolve("jmods"));
        archive(jmods.resolve("java.base.jmod"), "classes/java/lang/Fixture.class");
        archive(jmods.resolve("java.sql.jmod"), "classes/java/sql/Fixture.class");

        PlatformSymbolResult mismatch = new FilesystemJdkPlatformProvider().acquire(jdk,
                new PlatformSymbolRequest(17, 10, 10_000, 20_000));
        assertEquals(PlatformSymbolResult.Reason.RELEASE_MISMATCH, mismatch.problems().getFirst().reason());
        assertTrue(mismatch.platform().isEmpty());

        PlatformSymbolResult limited = new FilesystemJdkPlatformProvider().acquire(jdk,
                new PlatformSymbolRequest(11, 10, 1, 20_000));
        assertEquals(PlatformSymbolResult.Reason.ARTIFACT_BYTE_LIMIT, limited.problems().getFirst().reason());

        PlatformSymbolResult countLimited = new FilesystemJdkPlatformProvider().acquire(jdk,
                new PlatformSymbolRequest(11, 1, 10_000, 20_000));
        assertEquals(PlatformSymbolResult.Reason.ARTIFACT_COUNT_LIMIT,
                countLimited.problems().getFirst().reason());
    }

    @Test
    void javaEightUsesOnlyTheExplicitRtJar() throws Exception {
        Path jdk = Files.createDirectories(temp.resolve("jdk8"));
        Files.writeString(jdk.resolve("release"), "JAVA_VERSION=\"1.8.0_422\"\nIMPLEMENTOR=\"Fixture 8\"\n");
        Path rt = jdk.resolve("jre/lib/rt.jar");
        Files.createDirectories(rt.getParent()); archive(rt, "java/lang/Fixture.class");

        PlatformSymbolResult result = new FilesystemJdkPlatformProvider().acquire(jdk,
                new PlatformSymbolRequest(8, 1, 10_000, 10_000));
        assertEquals(PlatformSymbolResult.Status.COMPLETE, result.status());
        assertEquals(PlatformInput.Format.JAR,
                result.platform().orElseThrow().artifacts().getFirst().format());
    }

    private static void archive(Path path, String entry) throws Exception {
        try (var output = new JarOutputStream(Files.newOutputStream(path))) {
            JarEntry jarEntry = new JarEntry(entry); jarEntry.setTime(0);
            output.putNextEntry(jarEntry); output.write(new byte[] {1, 2, 3}); output.closeEntry();
        }
    }
}
