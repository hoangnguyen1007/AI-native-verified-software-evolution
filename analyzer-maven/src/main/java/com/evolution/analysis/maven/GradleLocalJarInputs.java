package com.evolution.analysis.maven;

import com.evolution.analysis.contract.analysis.ClasspathEntry;
import com.evolution.analysis.contract.analysis.ClasspathEntryKind;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.frontend.BinaryInput;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipInputStream;

/** Passive JVM view of a repository-captured local JAR, AAR, or Android SDK platform dependency. */
public final class GradleLocalJarInputs {
    private static final Pattern ANDROID_PLATFORM_DIR = Pattern.compile("android-([0-9]{1,4})");
    private static final int LOCAL_JAR_CACHE_MAX = 512;
    private record CachedAndroidPlatform(Path realPath, long size, long mtimeMillis, BinaryInput input) {}
    private record LocalJarCacheKey(Path cacheRoot, ContentDigest digest, long maxExpandedBytes) {}
    private static final Map<String, CachedAndroidPlatform> ANDROID_SDK_CACHE = new ConcurrentHashMap<>();
    private static final Map<LocalJarCacheKey, Path> LOCAL_JAR_CACHE =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<LocalJarCacheKey, Path> eldest) {
                    return size() > LOCAL_JAR_CACHE_MAX;
                }
            });

    public static Optional<BinaryInput> recoverAndroidPlatformJar(
            Path cache,
            Path androidSdkRoot,
            OptionalInt compileSdk,
            long maxArtifactBytes,
            long maxExpandedBytes) throws IOException {
        Objects.requireNonNull(cache);
        Objects.requireNonNull(compileSdk);
        if (androidSdkRoot == null) return Optional.empty();
        Path normalizedRoot = androidSdkRoot.toAbsolutePath().normalize();
        for (Path p = normalizedRoot; p != null; p = p.getParent()) {
            if (Files.isSymbolicLink(p)) return Optional.empty();
        }
        Path platformsDir = normalizedRoot.resolve("platforms");
        if (!Files.isDirectory(platformsDir, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();

        int selectedApi = -1;
        Path selectedJar = null;
        if (compileSdk.isPresent()) {
            Path exact = platformsDir.resolve("android-" + compileSdk.getAsInt()).resolve("android.jar");
            if (Files.isRegularFile(exact, LinkOption.NOFOLLOW_LINKS)) {
                selectedApi = compileSdk.getAsInt();
                selectedJar = exact;
            }
        }
        if (selectedJar == null) {
            record Candidate(int api, Path jar) {}
            var candidates = new java.util.ArrayList<Candidate>();
            try (var stream = Files.newDirectoryStream(platformsDir)) {
                for (Path dir : stream) {
                    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) continue;
                    var matcher = ANDROID_PLATFORM_DIR.matcher(dir.getFileName().toString());
                    if (!matcher.matches()) continue;
                    Path candidateJar = dir.resolve("android.jar");
                    if (Files.isRegularFile(candidateJar, LinkOption.NOFOLLOW_LINKS)) {
                        candidates.add(new Candidate(Integer.parseInt(matcher.group(1)), candidateJar));
                    }
                }
            }
            candidates.sort(Comparator.comparingInt(Candidate::api).reversed());
            if (!candidates.isEmpty()) {
                selectedApi = candidates.getFirst().api();
                selectedJar = candidates.getFirst().jar();
            }
        }
        if (selectedJar == null) return Optional.empty();
        Path realJar = selectedJar.toRealPath(LinkOption.NOFOLLOW_LINKS);
        for (Path p = realJar; p != null && !p.equals(normalizedRoot); p = p.getParent()) {
            if (Files.isSymbolicLink(p)) return Optional.empty();
        }
        long size = Files.size(realJar);
        if (size < 4 || size > maxArtifactBytes) return Optional.empty();
        long mtime = Files.getLastModifiedTime(realJar, LinkOption.NOFOLLOW_LINKS).toMillis();
        String cacheKey = cache.toAbsolutePath().normalize() + "|" + realJar + "|" + selectedApi;
        var cached = ANDROID_SDK_CACHE.get(cacheKey);
        if (cached != null && cached.size() == size && cached.mtimeMillis() == mtime
                && Files.isRegularFile(cached.input().path(), LinkOption.NOFOLLOW_LINKS)) {
            return Optional.of(cached.input());
        }
        byte[] bytes = Files.readAllBytes(realJar);
        ContentDigest digest = ContentDigest.sha256(bytes);
        if (bytes[0] != 'P' || bytes[1] != 'K' || bytes[2] != 3 || bytes[3] != 4) {
            throw new IOException("Invalid Android platform JAR header");
        }
        Path materialized = MavenDependencyArtifactProvider.materializeEmbeddedJar(cache, bytes, digest);
        if (!MavenDependencyArtifactProvider.isValidatedArtifact(cache, digest, "jar")) {
            validateJarEntries(materialized, maxExpandedBytes);
            MavenDependencyArtifactProvider.markValidatedArtifact(cache, digest, "jar");
        }
        String logical = "android-sdk:platforms/android-" + selectedApi + "/android.jar";
        var binary = new BinaryInput(new ClasspathEntry(ClasspathEntryKind.DEPENDENCY, logical, digest), materialized);
        ANDROID_SDK_CACHE.put(cacheKey, new CachedAndroidPlatform(realJar, size, mtime, binary));
        return Optional.of(binary);
    }

    private static void validateJarEntries(Path jarPath, long maxExpandedBytes) throws IOException {
        var names = new HashSet<String>();
        var buffer = new byte[8192];
        long remaining = maxExpandedBytes;
        try (var jar = new JarFile(jarPath.toFile(), false)) {
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
                        if (remaining < 0) throw new IOException("Expanded JAR byte budget exceeded");
                        crc.update(buffer, 0, read);
                        size += read;
                    }
                }
                if (size != entry.getSize() || crc.getValue() != entry.getCrc()) {
                    throw new IOException("Invalid JAR entry");
                }
            }
        }
        if (names.isEmpty()) throw new IOException("Empty JAR");
    }

    public static List<BinaryInput> recoverAll(
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
            throw new IOException("Local archive byte budget exceeded or empty");
        }
        if (!expectedDigest.equals(ContentDigest.sha256(bytes))) {
            throw new IOException("Local archive digest mismatch");
        }
        if (bytes[0] != 'P' || bytes[1] != 'K' || bytes[2] != 3 || bytes[3] != 4) {
            throw new IOException("Invalid local archive header");
        }
        if (logicalPath.toLowerCase(Locale.ROOT).endsWith(".aar")) {
            var entryCount = 0;
            try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                while (zip.getNextEntry() != null) {
                    checkCancelled();
                    entryCount++;
                }
            }
            if (entryCount == 0) {
                throw new IOException("Empty local AAR");
            }
            return GradleAarInputs.recoverBytes(cache, "local-aar:" + logicalPath, bytes, maxArtifactBytes,
                    maxExpandedBytes);
        }
        return List.of(recover(cache, logicalPath, bytes, expectedDigest, maxArtifactBytes, maxExpandedBytes));
    }

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
        Path cacheRoot = cache.toAbsolutePath().normalize();
        var cacheKey = new LocalJarCacheKey(cacheRoot, expectedDigest, maxExpandedBytes);
        Path cachedPath = LOCAL_JAR_CACHE.get(cacheKey);
        if (cachedPath != null && Files.isRegularFile(cachedPath, LinkOption.NOFOLLOW_LINKS)) {
            var session = com.evolution.analysis.input.VerifiedArtifactSession.current();
            boolean verified = session.isPresent()
                    ? session.orElseThrow().verifyDigest(cachedPath, expectedDigest)
                    : expectedDigest.equals(ContentDigest.sha256(Files.readAllBytes(cachedPath)));
            if (verified) {
                return new BinaryInput(
                        new ClasspathEntry(ClasspathEntryKind.DEPENDENCY, "local-jar:" + logicalPath, expectedDigest),
                        cachedPath);
            }
        }
        Path path = MavenDependencyArtifactProvider.materializeEmbeddedJar(cache, bytes, expectedDigest);
        if (!MavenDependencyArtifactProvider.isValidatedArtifact(cache, expectedDigest, "jar")) {
            validateJarEntries(path, maxExpandedBytes);
            MavenDependencyArtifactProvider.markValidatedArtifact(cache, expectedDigest, "jar");
        }
        LOCAL_JAR_CACHE.put(cacheKey, path);
        return new BinaryInput(
                new ClasspathEntry(ClasspathEntryKind.DEPENDENCY, "local-jar:" + logicalPath, expectedDigest),
                path);
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted())
            throw new CancellationException();
    }

    private GradleLocalJarInputs() {
    }
}
