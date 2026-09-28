package com.evolution.analysis.filesystem;

import com.evolution.analysis.contract.common.ContentDigest;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Fixed archive operation. It never receives a target path or executes code from the archive. */
public final class ArchiveWorkerMain {
    private record Candidate(String logical, String physical, int release, ContentDigest digest) { }
    private record Resource(String name, byte[] bytes) { }
    private record Result(ArchiveWorkerSupervisor.Status status, int entries, long expanded,
                          List<Resource> resources, List<Candidate> classes) {
        private static Result failed(ArchiveWorkerSupervisor.Status reason) {
            return new Result(reason, 0, 0, List.of(), List.of());
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) System.exit(2);
        int maximumInput, maximumOutput;
        try { maximumInput = Integer.parseInt(args[0]); maximumOutput = Integer.parseInt(args[1]); }
        catch (NumberFormatException invalid) { System.exit(2); return; }
        if (maximumInput < 1 || maximumOutput < 96) System.exit(2);
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(System.in));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(System.out))) {
            if (!ArchiveWorkerSupervisor.MAGIC.equals(in.readUTF())) System.exit(2);
            int release = in.readInt(); boolean collect = in.readBoolean(); int maxEntries = in.readInt();
            long maxExpanded = in.readLong(); int length = in.readInt();
            if (release < 1 || maxEntries < 1 || maxExpanded < 1 || length < 0 || length > maximumInput)
                System.exit(2);
            byte[] captured = in.readNBytes(length);
            if (captured.length != length || in.read() != -1) System.exit(2);
            ContentDigest digest = ContentDigest.sha256(captured);
            Result scanned = inspect(captured, release, collect, maxEntries, maxExpanded);
            byte[] response = encode(digest, scanned, maximumOutput);
            out.write(response); out.flush();
        }
    }

    private static Result inspect(byte[] captured, int release, boolean collect,
                                  int maxEntries, long maxExpanded) {
        Path temporary = null;
        try {
            try {
                temporary = Files.createTempFile("evolution-archive-worker-", ".zip");
                Files.write(temporary, captured, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException | SecurityException unavailable) {
                return Result.failed(ArchiveWorkerSupervisor.Status.SCRATCH_UNAVAILABLE);
            }
            try (ZipFile archive = new ZipFile(temporary.toFile())) {
                var names = new HashSet<String>(); var folded = new HashSet<String>();
                var candidates = new ArrayList<Candidate>(); var resources = new ArrayList<Resource>();
                byte[] manifest = null; int count = 0; long expanded = 0;
                var entries = archive.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (++count > maxEntries) return Result.failed(ArchiveWorkerSupervisor.Status.ARCHIVE_ENTRY_LIMIT);
                    String name = entry.getName();
                    if (name.length() > 4096)
                        return Result.failed(ArchiveWorkerSupervisor.Status.RESOURCE_LIMIT);
                    if (name.startsWith("/") || name.contains("\\") || name.contains(":")
                            || Arrays.stream(name.split("/", -1)).anyMatch(p -> p.equals("..") || p.equals(".")))
                        return Result.failed(ArchiveWorkerSupervisor.Status.UNSAFE_ARCHIVE_ENTRY);
                    if (!names.add(name) || !folded.add(name.toLowerCase(Locale.ROOT)))
                        return Result.failed(ArchiveWorkerSupervisor.Status.DUPLICATE_ARCHIVE_ENTRY);
                    if ((name.startsWith("BOOT-INF/lib/") || name.startsWith("WEB-INF/lib/"))
                            && name.endsWith(".jar"))
                        return Result.failed(ArchiveWorkerSupervisor.Status.NESTED_ARCHIVE_UNSELECTED);
                    if (entry.isDirectory()) continue;
                    boolean isManifest = name.equalsIgnoreCase("META-INF/MANIFEST.MF");
                    boolean wanted = collect && Set.of("META-INF/spring.factories",
                            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
                            .contains(name);
                    boolean isClass = collect && name.endsWith(".class");
                    int captureLimit = isManifest ? 65_536 : wanted ? 1_048_576 : 0;
                    var kept = captureLimit > 0 ? new ByteArrayOutputStream() : null;
                    MessageDigest classDigest = isClass ? MessageDigest.getInstance("SHA-256") : null;
                    try (InputStream stream = archive.getInputStream(entry)) {
                        byte[] buffer = new byte[8192]; int size;
                        while ((size = stream.read(buffer)) >= 0) {
                            if (size > maxExpanded - expanded)
                                return Result.failed(ArchiveWorkerSupervisor.Status.ARCHIVE_EXPANSION_LIMIT);
                            expanded += size;
                            if (kept != null) {
                                if (size > captureLimit - kept.size())
                                    return Result.failed(ArchiveWorkerSupervisor.Status.RESOURCE_LIMIT);
                                kept.write(buffer, 0, size);
                            }
                            if (classDigest != null) classDigest.update(buffer, 0, size);
                        }
                    }
                    if (isManifest) manifest = kept.toByteArray();
                    if (wanted) resources.add(new Resource(name, kept.toByteArray()));
                    if (isClass) candidates.add(new Candidate(name, name, 0,
                            new ContentDigest("sha256:" + HexFormat.of().formatHex(classDigest.digest()))));
                }
                if (count == 0) return Result.failed(ArchiveWorkerSupervisor.Status.CORRUPT_ARCHIVE);
                boolean multiRelease = false;
                if (manifest != null) {
                    try {
                        multiRelease = Boolean.parseBoolean(new java.util.jar.Manifest(
                                new ByteArrayInputStream(manifest)).getMainAttributes().getValue("Multi-Release"));
                    } catch (IOException invalid) {
                        return Result.failed(ArchiveWorkerSupervisor.Status.CORRUPT_ARCHIVE);
                    }
                }
                var selected = new TreeMap<String, Candidate>();
                for (Candidate candidate : candidates) {
                    String logical = candidate.physical(); int version = 0;
                    if (logical.startsWith("META-INF/versions/")) {
                        if (!multiRelease) continue;
                        String suffix = logical.substring("META-INF/versions/".length());
                        int slash = suffix.indexOf('/');
                        if (slash < 1) continue;
                        try { version = Integer.parseInt(suffix.substring(0, slash)); }
                        catch (NumberFormatException invalid) { continue; }
                        logical = suffix.substring(slash + 1);
                        if (version < 9 || version > release || logical.startsWith("META-INF/")) continue;
                    }
                    Candidate chosen = new Candidate(logical, candidate.physical(), version, candidate.digest());
                    Candidate previous = selected.get(logical);
                    if (previous == null || chosen.release() > previous.release()) selected.put(logical, chosen);
                }
                return new Result(ArchiveWorkerSupervisor.Status.COMPLETED, count, expanded,
                        resources, List.copyOf(selected.values()));
            }
        } catch (ZipException corrupt) {
            return Result.failed(ArchiveWorkerSupervisor.Status.CORRUPT_ARCHIVE);
        } catch (IOException | SecurityException | java.security.NoSuchAlgorithmException failure) {
            return Result.failed(ArchiveWorkerSupervisor.Status.SCRATCH_UNAVAILABLE);
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }

    private static byte[] encode(ContentDigest inputDigest, Result result, int maximum) throws IOException {
        byte[] encoded = encodeUnchecked(inputDigest, result);
        if (encoded.length <= maximum) return encoded;
        return encodeUnchecked(inputDigest, Result.failed(ArchiveWorkerSupervisor.Status.RESOURCE_LIMIT));
    }
    private static byte[] encodeUnchecked(ContentDigest digest, Result result) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(ArchiveWorkerSupervisor.MAGIC); out.writeUTF(digest.value());
            out.writeUTF(result.status().name());
            if (result.status() == ArchiveWorkerSupervisor.Status.COMPLETED) {
                out.writeInt(result.entries()); out.writeLong(result.expanded());
                out.writeInt(result.resources().size());
                for (Resource resource : result.resources()) {
                    out.writeUTF(resource.name()); out.writeInt(resource.bytes().length); out.write(resource.bytes());
                }
                out.writeInt(result.classes().size());
                for (Candidate candidate : result.classes()) {
                    out.writeUTF(candidate.logical()); out.writeUTF(candidate.physical());
                    out.writeInt(candidate.release()); out.writeUTF(candidate.digest().value());
                }
            }
        }
        return bytes.toByteArray();
    }
    private ArchiveWorkerMain() { }
}
