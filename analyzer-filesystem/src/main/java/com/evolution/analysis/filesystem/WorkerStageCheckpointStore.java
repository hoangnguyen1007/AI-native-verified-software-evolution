package com.evolution.analysis.filesystem;

import com.evolution.analysis.contract.common.ContentDigest;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Immutable atomic checkpoints for completed trusted-worker units. */
final class WorkerStageCheckpointStore {
    private static final String MAGIC = "worker-stage-checkpoint-v1";
    enum Status { MISSING, RESTORED, CORRUPT, STALE, UNAVAILABLE }
    record Load(Status status, Optional<ContentDigest> output) {}

    Load load(Path directory, ContentDigest taskIdentity) {
        Path file;
        try { file = file(directory, taskIdentity); }
        catch (IOException | RuntimeException invalid) { return new Load(Status.UNAVAILABLE, Optional.empty()); }
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return new Load(Status.MISSING, Optional.empty());
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            return new Load(Status.CORRUPT, Optional.empty());
        try {
            if (Files.size(file) > 512) return new Load(Status.CORRUPT, Optional.empty());
            byte[] sealed;
            try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                sealed = input.readNBytes(513);
            }
            if (sealed.length < 32 || sealed.length > 512) return new Load(Status.CORRUPT, Optional.empty());
            byte[] payload = Arrays.copyOf(sealed, sealed.length - 32);
            byte[] seal = Arrays.copyOfRange(sealed, sealed.length - 32, sealed.length);
            if (!MessageDigest.isEqual(seal, digestBytes(payload)))
                return new Load(Status.CORRUPT, Optional.empty());
            try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(payload))) {
                if (!MAGIC.equals(data.readUTF())) return new Load(Status.CORRUPT, Optional.empty());
                ContentDigest storedTask = new ContentDigest(data.readUTF());
                ContentDigest output = new ContentDigest(data.readUTF());
                if (data.available() != 0) return new Load(Status.CORRUPT, Optional.empty());
                return storedTask.equals(taskIdentity)
                        ? new Load(Status.RESTORED, Optional.of(output))
                        : new Load(Status.STALE, Optional.empty());
            }
        } catch (IOException | RuntimeException invalid) {
            return new Load(Status.CORRUPT, Optional.empty());
        }
    }

    boolean save(Path directory, ContentDigest taskIdentity, ContentDigest output) {
        Path target;
        try { target = file(directory, taskIdentity); }
        catch (IOException | RuntimeException unavailable) { return false; }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
            return load(directory, taskIdentity).output().filter(output::equals).isPresent();
        Path staged = null;
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DataOutputStream data = new DataOutputStream(buffer)) {
                data.writeUTF(MAGIC);
                data.writeUTF(taskIdentity.value());
                data.writeUTF(output.value());
            }
            byte[] payload = buffer.toByteArray();
            if (payload.length + 32 > 512) return false;
            staged = Files.createTempFile(directory, "worker-stage-", ".tmp");
            try (FileChannel channel = FileChannel.open(staged, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                writeFully(channel, ByteBuffer.wrap(payload));
                writeFully(channel, ByteBuffer.wrap(digestBytes(payload)));
                channel.force(true);
            }
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException | RuntimeException failure) {
            return load(directory, taskIdentity).output().filter(output::equals).isPresent();
        } finally {
            if (staged != null) try { Files.deleteIfExists(staged); } catch (IOException ignored) { /* staged file is never evidence */ }
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) channel.write(buffer);
    }
    private static byte[] digestBytes(byte[] payload) {
        return HexFormat.of().parseHex(ContentDigest.sha256(payload).value().substring(7));
    }
    private static Path file(Path selected, ContentDigest identity) throws IOException {
        Path directory = Objects.requireNonNull(selected).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !directory.equals(directory.toRealPath())) throw new IOException("Invalid checkpoint directory");
        return directory.resolve(identity.value().substring(7) + ".worker");
    }
}
