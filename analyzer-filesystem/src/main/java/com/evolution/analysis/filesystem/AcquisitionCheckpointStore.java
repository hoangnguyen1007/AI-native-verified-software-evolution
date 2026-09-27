package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.source.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Atomic, bounded capture of an acquisition stage; replay reads captured bytes, never the live repository. */
public final class AcquisitionCheckpointStore {
    private static final String MAGIC = "acquisition-checkpoint-v1";
    private static final int DIGEST_BYTES = 32;
    public enum Status { LOADED, STALE, CORRUPT, UNAVAILABLE, LIMIT_EXCEEDED }
    public record LoadResult(Status status, Optional<RepositoryAcquisitionResult> acquisition,
                             Optional<ContentDigest> checkpointDigest) {
        public LoadResult {
            Objects.requireNonNull(status); Objects.requireNonNull(acquisition);
            Objects.requireNonNull(checkpointDigest);
            if ((status == Status.LOADED) != acquisition.isPresent())
                throw new IllegalArgumentException("Only loaded checkpoints expose acquisition data");
        }
    }

    public ContentDigest save(Path selectedDirectory, String fileName,
                              RepositoryAcquisitionResult acquisition, long maxBytes) throws IOException {
        Objects.requireNonNull(acquisition); requireLimit(maxBytes);
        if (acquisition.snapshot().stream().anyMatch(s -> !s.documents().isEmpty()))
            throw new IllegalArgumentException("Acquisition checkpoint cannot contain invented decoded documents");
        Path directory = directory(selectedDirectory);
        Path target = target(directory, fileName);
        if (Files.isSymbolicLink(target)) throw new IllegalArgumentException("Checkpoint target is a link");
        byte[] payload = encode(acquisition, maxBytes);
        if (payload.length > maxBytes) throw new IllegalArgumentException("Checkpoint byte budget exceeded");
        byte[] digest = sha256(payload);
        ContentDigest identity = new ContentDigest("sha256:" + HexFormat.of().formatHex(digest));
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            var existing = load(directory, fileName, acquisition.request(), maxBytes);
            if (existing.status() == Status.LOADED && existing.checkpointDigest().orElseThrow().equals(identity))
                return identity;
            throw new FileAlreadyExistsException(fileName);
        }
        Path staged = Files.createTempFile(directory, "capture-", ".tmp");
        try {
            if (!staged.toAbsolutePath().normalize().getParent().equals(directory))
                throw new IllegalArgumentException("Checkpoint staging escaped selected directory");
            try (FileChannel channel = FileChannel.open(staged, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                writeFully(channel, ByteBuffer.wrap(payload));
                writeFully(channel, ByteBuffer.wrap(digest));
                channel.force(true);
            }
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
            return identity;
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    public LoadResult load(Path selectedDirectory, String fileName,
                           RepositoryAcquisitionRequest expectedRequest, long maxBytes) {
        Objects.requireNonNull(expectedRequest); requireLimit(maxBytes);
        Path directory;
        try { directory = directory(selectedDirectory); }
        catch (IOException | SecurityException | IllegalArgumentException failure) {
            return new LoadResult(Status.UNAVAILABLE, Optional.empty(), Optional.empty());
        }
        Path file = target(directory, fileName);
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            return new LoadResult(Status.UNAVAILABLE, Optional.empty(), Optional.empty());
        try {
            if (!file.equals(file.toRealPath()) || Files.size(file) > maxBytes + DIGEST_BYTES)
                return new LoadResult(Status.LIMIT_EXCEEDED, Optional.empty(), Optional.empty());
            byte[] sealed;
            try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                sealed = input.readNBytes((int) maxBytes + DIGEST_BYTES + 1);
            }
            if (sealed.length > maxBytes + DIGEST_BYTES)
                return new LoadResult(Status.LIMIT_EXCEEDED, Optional.empty(), Optional.empty());
            if (sealed.length < DIGEST_BYTES + 8)
                return new LoadResult(Status.CORRUPT, Optional.empty(), Optional.empty());
            byte[] payload = Arrays.copyOf(sealed, sealed.length - DIGEST_BYTES);
            byte[] digest = Arrays.copyOfRange(sealed, sealed.length - DIGEST_BYTES, sealed.length);
            if (!MessageDigest.isEqual(digest, sha256(payload)))
                return new LoadResult(Status.CORRUPT, Optional.empty(), Optional.empty());
            ContentDigest identity = new ContentDigest("sha256:" + HexFormat.of().formatHex(digest));
            try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(payload))) {
                if (!MAGIC.equals(data.readUTF()))
                    return new LoadResult(Status.CORRUPT, Optional.empty(), Optional.empty());
                ContentDigest resultIdentity = new ContentDigest(data.readUTF());
                ContentDigest requestIdentity = new ContentDigest(data.readUTF());
                if (!requestIdentity.equals(expectedRequest.identity()))
                    return new LoadResult(Status.STALE, Optional.empty(), Optional.of(identity));
                var provider = new VersionedIdentifier(data.readUTF(), data.readUTF());
                var completion = RepositoryAcquisitionResult.Completion.valueOf(data.readUTF());
                List<AcquiredFile> files = new ArrayList<>();
                int fileCount = count(data, expectedRequest.policy().maxFiles());
                long total = 0;
                for (int i = 0; i < fileCount; i++) {
                    String path = data.readUTF(); int length = data.readInt();
                    if (length < 0 || length > expectedRequest.policy().maxFileBytes()
                            || length > expectedRequest.policy().maxTotalBytes() - total)
                        throw new IOException("Checkpoint file exceeds capture policy");
                    byte[] bytes = data.readNBytes(length);
                    if (bytes.length != length) throw new EOFException();
                    total += length;
                    var stored = new ContentDigest(data.readUTF());
                    var capturedFile = new AcquiredFile(path, bytes);
                    if (!capturedFile.contentDigest().equals(stored)) throw new IOException("Captured file digest mismatch");
                    files.add(capturedFile);
                }
                List<String> directories = new ArrayList<>();
                for (int i = count(data, expectedRequest.policy().maxDirectories()); i > 0; i--)
                    directories.add(data.readUTF());
                List<RepositoryAcquisitionResult.Problem> problems = new ArrayList<>();
                for (int i = count(data, expectedRequest.policy().maxDiscoveredEntries() + 1); i > 0; i--)
                    problems.add(new RepositoryAcquisitionResult.Problem(
                            RepositoryAcquisitionResult.Reason.valueOf(data.readUTF()), data.readUTF(),
                            RepositoryAcquisitionResult.Requirement.valueOf(data.readUTF())));
                List<RepositoryAcquisitionResult.Attempt> attempts = new ArrayList<>();
                for (int i = count(data, expectedRequest.policy().maxDiscoveredEntries() + 1); i > 0; i--) {
                    var kind = RepositoryAcquisitionResult.AttemptKind.valueOf(data.readUTF());
                    var subject = data.readUTF();
                    var outcome = RepositoryAcquisitionResult.Outcome.valueOf(data.readUTF());
                    Optional<ContentDigest> evidence = data.readBoolean()
                            ? Optional.of(new ContentDigest(data.readUTF())) : Optional.empty();
                    attempts.add(new RepositoryAcquisitionResult.Attempt(kind, subject, outcome, evidence));
                }
                List<String> limitations = new ArrayList<>();
                for (int i = count(data, 1000); i > 0; i--) limitations.add(data.readUTF());
                if (data.available() != 0) throw new IOException("Unexpected checkpoint bytes");
                Optional<RepositorySnapshot> snapshot = completion == RepositoryAcquisitionResult.Completion.COMPLETE
                        ? Optional.of(RepositorySnapshot.create(expectedRequest.repository(),
                                expectedRequest.revision(), expectedRequest.dirty(),
                                files.stream().map(f -> new SnapshotFile(f.path(), f.contentDigest())).toList(), List.of()))
                        : Optional.empty();
                var acquisition = new RepositoryAcquisitionResult(RepositoryAcquisitionResult.SCHEMA,
                        expectedRequest, provider, completion, snapshot, files, directories,
                        problems, attempts, limitations);
                if (!acquisition.identity().equals(resultIdentity)) throw new IOException("Checkpoint result identity mismatch");
                return new LoadResult(Status.LOADED, Optional.of(acquisition), Optional.of(identity));
            }
        } catch (IOException | RuntimeException failure) {
            return new LoadResult(Status.CORRUPT, Optional.empty(), Optional.empty());
        }
    }

    private static byte[] encode(RepositoryAcquisitionResult acquisition, long maxBytes) throws IOException {
        var bytes = new BoundedBytes((int) maxBytes);
        try (DataOutputStream data = new DataOutputStream(bytes)) {
            data.writeUTF(MAGIC);
            data.writeUTF(acquisition.identity().value());
            data.writeUTF(acquisition.request().identity().value());
            data.writeUTF(acquisition.provider().id()); data.writeUTF(acquisition.provider().version());
            data.writeUTF(acquisition.completion().name());
            data.writeInt(acquisition.files().size());
            for (var file : acquisition.files()) {
                data.writeUTF(file.path()); data.writeInt(file.size()); data.write(file.bytes());
                data.writeUTF(file.contentDigest().value());
            }
            data.writeInt(acquisition.directories().size());
            for (String directory : acquisition.directories()) data.writeUTF(directory);
            data.writeInt(acquisition.problems().size());
            for (var problem : acquisition.problems()) {
                data.writeUTF(problem.reason().name()); data.writeUTF(problem.subject());
                data.writeUTF(problem.requirement().name());
            }
            data.writeInt(acquisition.attempts().size());
            for (var attempt : acquisition.attempts()) {
                data.writeUTF(attempt.kind().name()); data.writeUTF(attempt.subject());
                data.writeUTF(attempt.outcome().name()); data.writeBoolean(attempt.evidence().isPresent());
                if (attempt.evidence().isPresent()) data.writeUTF(attempt.evidence().orElseThrow().value());
            }
            data.writeInt(acquisition.limitations().size());
            for (String limitation : acquisition.limitations()) data.writeUTF(limitation);
        }
        return bytes.toByteArray();
    }

    private static int count(DataInputStream input, long limit) throws IOException {
        int value = input.readInt();
        if (value < 0 || value > limit) throw new IOException("Checkpoint count exceeds capture policy");
        return value;
    }
    private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) channel.write(buffer);
    }
    private static byte[] sha256(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void requireLimit(long maxBytes) {
        if (maxBytes < 1 || maxBytes > Integer.MAX_VALUE - DIGEST_BYTES - 1)
            throw new IllegalArgumentException("Checkpoint limit must fit bounded memory");
    }
    private static final class BoundedBytes extends ByteArrayOutputStream {
        private final int limit;
        BoundedBytes(int limit) { super(Math.min(limit, 8192)); this.limit = limit; }
        @Override public synchronized void write(int value) {
            if (count >= limit) throw new IllegalArgumentException("Checkpoint byte budget exceeded");
            super.write(value);
        }
        @Override public synchronized void write(byte[] value, int offset, int length) {
            if (length > limit - count) throw new IllegalArgumentException("Checkpoint byte budget exceeded");
            super.write(value, offset, length);
        }
    }
    private static Path directory(Path selected) throws IOException {
        Path directory = Objects.requireNonNull(selected).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !directory.equals(directory.toRealPath()))
            throw new IllegalArgumentException("Checkpoint directory must be a real selected directory");
        return directory;
    }
    private static Path target(Path directory, String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,100}")
                || name.equals(".") || name.equals(".."))
            throw new IllegalArgumentException("Invalid checkpoint file name");
        Path target = directory.resolve(name).toAbsolutePath().normalize();
        if (!target.getParent().equals(directory)) throw new IllegalArgumentException("Checkpoint escaped selected directory");
        return target;
    }
}
