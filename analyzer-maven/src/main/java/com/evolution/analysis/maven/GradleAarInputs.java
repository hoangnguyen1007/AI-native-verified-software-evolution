package com.evolution.analysis.maven;

import com.evolution.analysis.contract.analysis.ClasspathEntry;
import com.evolution.analysis.contract.analysis.ClasspathEntryKind;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.dependency.DependencyAcquisitionResult.Artifact;
import com.evolution.analysis.frontend.BinaryInput;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipInputStream;

/** Passive JVM views of verified Android archives. Member names never select filesystem paths. */
final class GradleAarInputs {
    private static final Pattern LIBRARY = Pattern.compile("libs/[^/\\\\\\p{Cntrl}]+\\.jar");
    private static final Pattern VERSIONED = Pattern.compile("^META-INF/versions/\\d+/");
    private static final int AAR_CACHE_MAX = 512;
    private record AarCacheKey(
            Path cacheRoot, ContentDigest digest, String logicalBase, long memberBudget, long expandedBudget) {}
    private static final Map<AarCacheKey, List<BinaryInput>> AAR_CACHE =
            Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<AarCacheKey, List<BinaryInput>> eldest) {
                    return size() > AAR_CACHE_MAX;
                }
            });
    static final java.util.concurrent.atomic.AtomicLong AAR_CACHE_HITS = new java.util.concurrent.atomic.AtomicLong();

    static List<BinaryInput> recover(Path cache, Artifact artifact, long memberBudget, long expandedBudget) throws IOException {
        Path root = cache.toAbsolutePath().normalize().toRealPath();
        // Always cryptographically verify the AAR bytes on disk before trusting any cached extraction.
        var bytes = MavenDependencyArtifactProvider.readVerifiedArtifact(cache, artifact, memberBudget);
        var cacheKey = new AarCacheKey(
                root, artifact.contentDigest(), artifact.coordinate().notation(), memberBudget, expandedBudget);
        List<BinaryInput> cached = AAR_CACHE.get(cacheKey);
        if (cached != null && allMaterializedVerified(cached)) {
            AAR_CACHE_HITS.incrementAndGet();
            return cached;
        }
        List<BinaryInput> recovered = recoverBytes(cache, artifact.coordinate().notation(), bytes, memberBudget, expandedBudget);
        AAR_CACHE.put(cacheKey, recovered);
        return recovered;
    }

    private static boolean allMaterializedVerified(List<BinaryInput> inputs) {
        for (var input : inputs) {
            try {
                Path path = input.path();
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false;
                if (!input.entry().contentDigest().equals(ContentDigest.sha256(Files.readAllBytes(path)))) return false;
            } catch (IOException | SecurityException unavailable) {
                return false;
            }
        }
        return true;
    }

    static List<BinaryInput> recoverBytes(Path cache, String logicalBase, byte[] bytes, long memberBudget, long expandedBudget) throws IOException {
        var members = new TreeMap<String,byte[]>();
        long remaining = memberBudget;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                checkCancelled();
                String name = entry.getName();
                if (entry.isDirectory() || !name.equals("classes.jar") && !LIBRARY.matcher(name).matches()) continue;
                if (members.containsKey(name)) throw new IOException("Duplicate embedded JAR");
                byte[] jar = zip.readNBytes(Math.toIntExact(remaining + 1));
                if (jar.length > remaining) throw new IOException("Embedded JAR byte budget exceeded");
                members.put(name,jar);
                remaining -= jar.length;
            }
        }

        var result = new ArrayList<BinaryInput>();
        var classes = new HashSet<String>();
        remaining = expandedBudget;
        for (var member : members.entrySet()) {
            var digest = ContentDigest.sha256(member.getValue());
            var path = MavenDependencyArtifactProvider.materializeEmbeddedJar(cache,member.getValue(),digest);
            var jarClasses = new HashSet<String>();
            var names = new HashSet<String>();
            var buffer = new byte[8192];
            try (var jar = new JarFile(path.toFile(),false)) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    checkCancelled();
                    var entry = entries.nextElement();
                    if (!names.add(entry.getName())) throw new IOException("Duplicate JAR entry");
                    if (entry.isDirectory()) continue;
                    var crc = new CRC32();
                    long size = 0;
                    try (var stream = jar.getInputStream(entry)) {
                        for (int read; (read = stream.read(buffer)) != -1;) {
                            checkCancelled();
                            remaining -= read;
                            if (remaining < 0) throw new IOException("Expanded JAR byte budget exceeded");
                            crc.update(buffer,0,read);
                            size += read;
                        }
                    }
                    if (size != entry.getSize() || crc.getValue() != entry.getCrc()) throw new IOException("Invalid embedded JAR entry");
                    String name = VERSIONED.matcher(entry.getName()).replaceFirst("");
                    if (name.endsWith(".class") && !name.equals("module-info.class")) jarClasses.add(name);
                }
            }
            if (jarClasses.stream().anyMatch(classes::contains)) throw new IOException("Conflicting classes in Android archive");
            classes.addAll(jarClasses);
            String logical = logicalBase;
            if (!member.getKey().equals("classes.jar")) logical += "!/" + member.getKey();
            result.add(new BinaryInput(new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,logical,digest),path));
        }
        return List.copyOf(result);
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    private GradleAarInputs() { }
}
