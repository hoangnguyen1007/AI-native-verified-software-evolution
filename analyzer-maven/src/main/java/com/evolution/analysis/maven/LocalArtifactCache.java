package com.evolution.analysis.maven;

import com.evolution.analysis.buildmodel.BuildModelRequest;
import com.evolution.analysis.buildmodel.MavenCoordinate;
import com.evolution.analysis.buildmodel.PomInput;
import com.evolution.analysis.classpath.ArtifactCoordinate;
import com.evolution.analysis.classpath.ClasspathResolutionPolicy;
import com.evolution.analysis.classpath.ExactClasspathResult.*;
import com.evolution.analysis.contract.common.ContentDigest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Bounded immutable reads from one explicitly selected Maven-style local repository. */
final class LocalArtifactCache {
    private final Path root;
    private final BuildModelRequest buildRequest;
    private final ClasspathResolutionPolicy policy;
    private final Map<ArtifactCoordinate, Material> materials = new HashMap<>();
    private final Map<ArtifactCoordinate, Failure> failures = new HashMap<>();
    private final List<ArtifactRecord> artifacts = new ArrayList<>();
    private final List<Attempt> attempts = new ArrayList<>();
    private long totalBytes;
    private int fileCount;

    LocalArtifactCache(Path selectedRoot, BuildModelRequest buildRequest, ClasspathResolutionPolicy policy)
            throws RootFailure {
        this.buildRequest = Objects.requireNonNull(buildRequest);
        this.policy = Objects.requireNonNull(policy);
        this.root = validateRoot(Objects.requireNonNull(selectedRoot));
    }

    Material pom(MavenCoordinate gav) throws Failure {
        ArtifactCoordinate coordinate = new ArtifactCoordinate(gav, "pom", "");
        PomInput supplied = buildRequest.artifactPoms().get(gav);
        return supplied == null
                ? local(coordinate, ArtifactKind.POM, policy.maxPomBytes())
                : supplied(coordinate, supplied);
    }

    Material jar(ArtifactCoordinate coordinate) throws Failure {
        if (!coordinate.extension().equals("jar")) {
            throw new IllegalArgumentException("Only JAR coordinates can be acquired as binaries");
        }
        return local(coordinate, ArtifactKind.JAR, policy.maxArtifactBytes());
    }

    List<ArtifactRecord> artifacts() {
        return List.copyOf(artifacts);
    }

    List<Attempt> attempts() {
        return List.copyOf(attempts);
    }

    private Material supplied(ArtifactCoordinate coordinate, PomInput input) throws Failure {
        Material cached = materials.get(coordinate);
        if (cached != null) return cached;
        Failure failed = failures.get(coordinate);
        if (failed != null) throw failed;
        String subject = "supplied:" + coordinate.gav().notation();
        byte[] bytes = input.bytes();
        try {
            reserve(coordinate, ArtifactKind.POM, ArtifactOrigin.SUPPLIED_BUILD_INPUT,
                    subject, bytes.length, policy.maxPomBytes());
            var record = new ArtifactRecord(
                    coordinate,
                    ArtifactKind.POM,
                    ArtifactOrigin.SUPPLIED_BUILD_INPUT,
                    Optional.empty(),
                    input.digest(),
                    bytes.length);
            var material = new Material(record, bytes);
            materials.put(coordinate, material);
            artifacts.add(record);
            attempts.add(new Attempt(ArtifactKind.POM, coordinate, ArtifactOrigin.SUPPLIED_BUILD_INPUT,
                    subject, AttemptOutcome.SUCCEEDED, Optional.of(input.digest())));
            return material;
        } catch (Failure failure) {
            failures.put(coordinate, failure);
            throw failure;
        }
    }

    private Material local(ArtifactCoordinate coordinate, ArtifactKind kind, long perFileLimit) throws Failure {
        Material cached = materials.get(coordinate);
        if (cached != null) return cached;
        Failure failed = failures.get(coordinate);
        if (failed != null) throw failed;
        String relative = coordinate.repositoryPath();
        Path path = root.resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
        try {
            if (!path.startsWith(root)) {
                throw failure(coordinate, kind, relative, Reason.PATH_OUTSIDE_CACHE, AttemptOutcome.DENIED);
            }
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                Reason reason = kind == ArtifactKind.POM ? Reason.MISSING_POM : Reason.MISSING_ARTIFACT;
                throw failure(coordinate, kind, relative, reason, AttemptOutcome.UNAVAILABLE);
            }
            if (Files.isSymbolicLink(path)) {
                throw failure(coordinate, kind, relative, Reason.SYMBOLIC_LINK, AttemptOutcome.DENIED);
            }
            BasicFileAttributes before;
            Path real;
            try {
                before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                real = path.toRealPath();
            } catch (IOException | SecurityException exception) {
                throw failure(coordinate, kind, relative, Reason.ARTIFACT_READ_FAILED, AttemptOutcome.FAILED);
            }
            if (!real.startsWith(root)) {
                throw failure(coordinate, kind, relative, Reason.PATH_OUTSIDE_CACHE, AttemptOutcome.DENIED);
            }
            if (!real.equals(path.toAbsolutePath().normalize())) {
                throw failure(coordinate, kind, relative, Reason.SYMBOLIC_LINK, AttemptOutcome.DENIED);
            }
            if (!before.isRegularFile()) {
                throw failure(coordinate, kind, relative, Reason.NON_REGULAR_ARTIFACT, AttemptOutcome.DENIED);
            }
            reserve(coordinate, kind, ArtifactOrigin.LOCAL_CACHE, relative, before.size(), perFileLimit);
            byte[] bytes;
            try {
                bytes = readBounded(path, Math.min(perFileLimit, policy.maxTotalBytes() - totalBytes));
            } catch (LimitExceeded exception) {
                Reason reason = before.size() > perFileLimit
                        ? limitReason(kind)
                        : Reason.TOTAL_BYTE_LIMIT;
                throw failure(coordinate, kind, relative, reason, AttemptOutcome.LIMIT_EXCEEDED);
            } catch (IOException | SecurityException exception) {
                throw failure(coordinate, kind, relative, Reason.ARTIFACT_READ_FAILED, AttemptOutcome.FAILED);
            }
            BasicFileAttributes after;
            try {
                after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException | SecurityException exception) {
                throw failure(coordinate, kind, relative, Reason.ARTIFACT_CHANGED_DURING_READ, AttemptOutcome.FAILED);
            }
            if (Files.isSymbolicLink(path)
                    || !after.isRegularFile()
                    || before.size() != after.size()
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || before.fileKey() != null && after.fileKey() != null
                            && !before.fileKey().equals(after.fileKey())) {
                throw failure(coordinate, kind, relative, Reason.ARTIFACT_CHANGED_DURING_READ, AttemptOutcome.FAILED);
            }
            ContentDigest digest = ContentDigest.sha256(bytes);
            var record = new ArtifactRecord(coordinate, kind, ArtifactOrigin.LOCAL_CACHE,
                    Optional.of(relative), digest, bytes.length);
            var material = new Material(record, bytes);
            materials.put(coordinate, material);
            artifacts.add(record);
            totalBytes += bytes.length;
            attempts.add(new Attempt(kind, coordinate, ArtifactOrigin.LOCAL_CACHE,
                    relative, AttemptOutcome.SUCCEEDED, Optional.of(digest)));
            return material;
        } catch (Failure failure) {
            failures.put(coordinate, failure);
            throw failure;
        }
    }

    private void reserve(
            ArtifactCoordinate coordinate,
            ArtifactKind kind,
            ArtifactOrigin origin,
            String subject,
            long size,
            long perFileLimit) throws Failure {
        if (fileCount >= policy.maxFiles()) {
            throw failure(coordinate, kind, origin, subject, Reason.FILE_COUNT_LIMIT, AttemptOutcome.LIMIT_EXCEEDED);
        }
        fileCount++;
        if (size > perFileLimit) {
            throw failure(coordinate, kind, origin, subject, limitReason(kind), AttemptOutcome.LIMIT_EXCEEDED);
        }
        if (size > policy.maxTotalBytes() - totalBytes) {
            throw failure(coordinate, kind, origin, subject, Reason.TOTAL_BYTE_LIMIT, AttemptOutcome.LIMIT_EXCEEDED);
        }
        if (origin == ArtifactOrigin.SUPPLIED_BUILD_INPUT) totalBytes += size;
    }

    private Failure failure(
            ArtifactCoordinate coordinate,
            ArtifactKind kind,
            String subject,
            Reason reason,
            AttemptOutcome outcome) {
        return failure(coordinate, kind, ArtifactOrigin.LOCAL_CACHE, subject, reason, outcome);
    }

    private Failure failure(
            ArtifactCoordinate coordinate,
            ArtifactKind kind,
            ArtifactOrigin origin,
            String subject,
            Reason reason,
            AttemptOutcome outcome) {
        attempts.add(new Attempt(kind, coordinate, origin, subject, outcome, Optional.empty()));
        return new Failure(reason, requirement(kind), subject, List.of());
    }

    private static Requirement requirement(ArtifactKind kind) {
        return kind == ArtifactKind.POM ? Requirement.ARTIFACT_POM : Requirement.DEPENDENCY_ARTIFACT;
    }

    private static Reason limitReason(ArtifactKind kind) {
        return kind == ArtifactKind.POM ? Reason.POM_BYTE_LIMIT : Reason.ARTIFACT_BYTE_LIMIT;
    }

    private static Path validateRoot(Path selectedRoot) throws RootFailure {
        Path absolute = selectedRoot.toAbsolutePath().normalize();
        if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new RootFailure(Reason.CACHE_ROOT_NOT_FOUND);
        }
        if (Files.isSymbolicLink(absolute)) {
            throw new RootFailure(Reason.CACHE_ROOT_SYMBOLIC_LINK);
        }
        BasicFileAttributes attributes;
        Path real;
        try {
            attributes = Files.readAttributes(absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            real = absolute.toRealPath();
        } catch (IOException | SecurityException exception) {
            throw new RootFailure(Reason.CACHE_ROOT_READ_FAILED);
        }
        if (!attributes.isDirectory()) throw new RootFailure(Reason.CACHE_ROOT_NOT_DIRECTORY);
        if (!absolute.equals(real)) throw new RootFailure(Reason.CACHE_ROOT_SYMBOLIC_LINK);
        return real;
    }

    private static byte[] readBounded(Path path, long allowed) throws IOException, LimitExceeded {
        try (SeekableByteChannel channel = Files.newByteChannel(
                path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            var output = new ByteArrayOutputStream((int) Math.min(8192, allowed));
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            long count = 0;
            while (channel.read(buffer) >= 0) {
                buffer.flip();
                int amount = buffer.remaining();
                count += amount;
                if (count > allowed) throw new LimitExceeded();
                output.write(buffer.array(), buffer.position(), amount);
                buffer.clear();
            }
            return output.toByteArray();
        }
    }

    record Material(ArtifactRecord record, byte[] bytes) {
        Material {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    static final class Failure extends Exception {
        final Reason reason;
        final Requirement requirement;
        final String subject;
        final List<Evidence> evidence;

        Failure(Reason reason, Requirement requirement, String subject, List<Evidence> evidence) {
            super(reason.name());
            this.reason = reason;
            this.requirement = requirement;
            this.subject = subject;
            this.evidence = List.copyOf(evidence);
        }
    }

    static final class RootFailure extends Exception {
        final Reason reason;

        RootFailure(Reason reason) {
            super(reason.name());
            this.reason = reason;
        }
    }

    private static final class LimitExceeded extends Exception {}
}
