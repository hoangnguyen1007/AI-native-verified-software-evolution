package com.evolution.analysis.javaparser.asm;

import com.github.javaparser.utils.Pair;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Immutable, lock-free per-archive bytecode index shared globally across modules and worker threads.
 * Supports both in-memory byte views and compact S4 pre-parsed {@link AsmClassStub} archives (dropping
 * raw method bytecode bytes to reduce RAM by 60%+ with zero open file handles on Windows).
 */
public final class AsmBytecodeIndex {
    public static final AsmBytecodeIndex EMPTY =
            new AsmBytecodeIndex(Map.of(), Map.of(), Map.of(), null, Map.of(), Optional.empty(), Optional.empty(), 64L);

    private final Map<String, String> knownClasses;
    private final Map<String, byte[]> classBytesByPoolName;
    private final Path backingArchivePath;
    private final Map<String, String> poolToArchiveEntry;
    private final Optional<Pair<String, List<String>>> moduleToExportedPackages;
    private final Optional<String> manifestClasspath;
    private final long weightBytes;
    private final ConcurrentHashMap<String, Optional<AsmClassStub>> stubCache;

    private AsmBytecodeIndex(
            Map<String, String> knownClasses,
            Map<String, byte[]> classBytesByPoolName,
            Map<String, Optional<AsmClassStub>> preloadedStubs,
            Path backingArchivePath,
            Map<String, String> poolToArchiveEntry,
            Optional<Pair<String, List<String>>> moduleToExportedPackages,
            Optional<String> manifestClasspath,
            long weightBytes) {
        this.knownClasses = knownClasses;
        this.classBytesByPoolName = classBytesByPoolName;
        this.backingArchivePath = backingArchivePath;
        this.poolToArchiveEntry = poolToArchiveEntry;
        this.moduleToExportedPackages = moduleToExportedPackages;
        this.manifestClasspath = manifestClasspath;
        this.weightBytes = weightBytes;
        this.stubCache = new ConcurrentHashMap<>(Math.max(64, preloadedStubs.size() * 2));
        this.stubCache.putAll(preloadedStubs);
    }

    public static AsmBytecodeIndex fromClassEntries(
            Map<String, byte[]> entries, Optional<String> manifestClasspath) {
        if (entries.isEmpty() && manifestClasspath.isEmpty()) {
            return EMPTY;
        }
        var known = new LinkedHashMap<String, String>(Math.max(16, entries.size() * 2));
        var byPool = new HashMap<String, byte[]>(Math.max(16, entries.size() * 2));
        long weight = 128L + manifestClasspath.map(s -> (long) s.length() * 2).orElse(0L);
        for (var entry : entries.entrySet()) {
            String entryName = entry.getKey();
            if (!entryName.endsWith(".class")) continue;
            String stripped = entryName.substring(0, entryName.length() - ".class".length());
            String poolName = stripped.replace('/', '.');
            String className = poolName.replace('$', '.');
            known.put(className, className.equals(poolName) ? className : poolName);
            byte[] raw = entry.getValue();
            byPool.put(poolName, raw);
            weight += raw.length + (long) poolName.length() * 4 + 96L;
        }
        var immutableKnown = Collections.unmodifiableMap(known);
        var immutablePool = Collections.unmodifiableMap(byPool);
        Optional<Pair<String, List<String>>> modInfo = Optional.empty();
        byte[] modBytes = immutablePool.get("module-info");
        if (modBytes != null) {
            try {
                AsmClassStub modStub = AsmClassStub.parse(modBytes);
                if (modStub.moduleExport() != null) {
                    modInfo = Optional.of(new Pair<>(
                            modStub.moduleExport().moduleName(),
                            modStub.moduleExport().exportedPackages()));
                }
            } catch (RuntimeException ignored) {
                // Corrupt module-info falls back cleanly without breaking archive loading.
            }
        }
        return new AsmBytecodeIndex(
                immutableKnown, immutablePool, Map.of(), null, Map.of(), modInfo, manifestClasspath, weight);
    }

    /**
     * S4 compact archive constructor: parses each selected {@code .class} into an immutable {@link AsmClassStub}
     * immediately and discards raw method bytecode bytes (retaining raw bytes only for corrupt entries that
     * require Javassist fallback). Keeps zero open {@link ZipFile} handles so Windows temp cleanup never locks.
     */
    public static AsmBytecodeIndex fromCompactArchiveEntries(
            Path backingArchivePath,
            Map<String, byte[]> selectedClassBytes,
            Map<String, String> classEntryToArchiveEntry,
            Optional<String> manifestClasspath) {
        if (selectedClassBytes.isEmpty() && manifestClasspath.isEmpty()) {
            return EMPTY;
        }
        var known = new LinkedHashMap<String, String>(Math.max(16, selectedClassBytes.size() * 2));
        var preloaded = new HashMap<String, Optional<AsmClassStub>>(Math.max(16, selectedClassBytes.size() * 2));
        var fallbackRaw = new HashMap<String, byte[]>(4);
        var poolToEntry = new HashMap<String, String>(Math.max(16, selectedClassBytes.size() * 2));
        long weight = 128L + manifestClasspath.map(s -> (long) s.length() * 2).orElse(0L);
        Optional<Pair<String, List<String>>> modInfo = Optional.empty();

        for (var entry : selectedClassBytes.entrySet()) {
            String entryName = entry.getKey();
            if (!entryName.endsWith(".class")) continue;
            String stripped = entryName.substring(0, entryName.length() - ".class".length());
            String poolName = stripped.replace('/', '.');
            String className = poolName.replace('$', '.');
            known.put(className, className.equals(poolName) ? className : poolName);
            String physicalName = classEntryToArchiveEntry.get(entryName);
            if (physicalName != null) {
                poolToEntry.put(poolName, physicalName);
            }
            byte[] raw = entry.getValue();
            try {
                AsmClassStub stub = AsmClassStub.parse(raw);
                preloaded.put(poolName, Optional.of(stub));
                if ("module-info".equals(poolName) && stub.moduleExport() != null) {
                    modInfo = Optional.of(new Pair<>(
                            stub.moduleExport().moduleName(),
                            stub.moduleExport().exportedPackages()));
                }
                weight += estimateStubBytes(stub) + (long) poolName.length() * 4 + 96L;
            } catch (RuntimeException corrupt) {
                preloaded.put(poolName, Optional.empty());
                fallbackRaw.put(poolName, raw);
                weight += raw.length + (long) poolName.length() * 4 + 96L;
            }
        }
        return new AsmBytecodeIndex(
                Collections.unmodifiableMap(known),
                Collections.unmodifiableMap(fallbackRaw),
                preloaded,
                backingArchivePath,
                Collections.unmodifiableMap(poolToEntry),
                modInfo,
                manifestClasspath,
                weight);
    }

    private static long estimateStubBytes(AsmClassStub stub) {
        return 256L
                + (long) stub.fields().size() * 96L
                + (long) stub.methods().size() * 128L
                + (long) stub.annotations().size() * 48L;
    }

    public Map<String, String> knownClasses() {
        return knownClasses;
    }

    public Map<String, byte[]> classBytesByPoolName() {
        if (backingArchivePath == null || poolToArchiveEntry.isEmpty()) {
            return classBytesByPoolName;
        }
        // Rare fallback path (e.g. -Devo.perf.asmTypeSolver=false after warm cache):
        // materialize raw class bytes transiently from the verified archive, closing ZipFile immediately.
        var all = new HashMap<String, byte[]>(Math.max(16, poolToArchiveEntry.size() * 2));
        all.putAll(classBytesByPoolName);
        try (var zf = new ZipFile(backingArchivePath.toFile())) {
            for (var e : poolToArchiveEntry.entrySet()) {
                if (all.containsKey(e.getKey())) continue;
                ZipEntry ze = zf.getEntry(e.getValue());
                if (ze == null) continue;
                try (InputStream in = zf.getInputStream(ze)) {
                    all.put(e.getKey(), in.readAllBytes());
                }
            }
        } catch (IOException ignored) {
            // Return any already-available fallback bytes cleanly.
        }
        return Collections.unmodifiableMap(all);
    }

    public Optional<Pair<String, List<String>>> moduleToExportedPackages() {
        return moduleToExportedPackages;
    }

    public Optional<String> manifestClasspath() {
        return manifestClasspath;
    }

    public long weightBytes() {
        return weightBytes;
    }

    public String poolNameForCanonical(String canonicalName) {
        return knownClasses.get(canonicalName);
    }

    /**
     * Loads and caches the immutable {@link AsmClassStub} for a pool/binary name (e.g. {@code java.util.Map$Entry}).
     * Returns {@link Optional#empty()} on missing entry or unexpected/corrupt bytecode so callers can fall back cleanly.
     */
    public Optional<AsmClassStub> loadStub(String poolName) {
        if (poolName == null) return Optional.empty();
        Optional<AsmClassStub> cached = stubCache.get(poolName);
        if (cached != null) {
            return cached;
        }
        byte[] raw = classBytesByPoolName.get(poolName);
        if (raw == null) {
            return Optional.empty();
        }
        Optional<AsmClassStub> parsed;
        try {
            parsed = Optional.of(AsmClassStub.parse(raw));
        } catch (RuntimeException corrupt) {
            parsed = Optional.empty();
        }
        Optional<AsmClassStub> prior = stubCache.putIfAbsent(poolName, parsed);
        return prior != null ? prior : parsed;
    }
}
