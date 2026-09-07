package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.*;
import com.evolution.analysis.acquisition.RepositoryAcquisitionResult.*;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.source.RepositorySnapshot;
import com.evolution.analysis.contract.source.SnapshotFile;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Passive local-directory reader. It never follows links, executes files, loads build extensions,
 * reads user settings, or accesses a network.
 */
public final class FilesystemRepositoryAcquirer {
    public static final VersionedIdentifier VERSION =
            new VersionedIdentifier("repository.filesystem", "m3.3");

    /**
     * Acquires a repository only when the normalized absolute root is already its real path.
     * Callers selecting through a symlinked ancestor or platform alias (for example macOS
     * {@code /tmp}) must pass {@code selectedRoot.toRealPath()} explicitly.
     */
    public RepositoryAcquisitionResult acquire(Path selectedRoot, RepositoryAcquisitionRequest request) {
        Objects.requireNonNull(selectedRoot, "selected repository root must not be null");
        return new Run(selectedRoot, Objects.requireNonNull(request)).acquire();
    }

    private static final class Run {
        private final Path selectedRoot;
        private final RepositoryAcquisitionRequest request;
        private final RepositoryAcquisitionPolicy policy;
        private final List<AcquiredFile> files = new ArrayList<>();
        private final List<String> directories = new ArrayList<>();
        private final List<Problem> problems = new ArrayList<>();
        private final List<Attempt> attempts = new ArrayList<>();
        private Path root;
        private long totalBytes;
        private int fileCount;
        private int directoryCount;
        private long discoveredEntryCount;
        private boolean incomplete;
        private boolean stop;

        private Run(Path selectedRoot, RepositoryAcquisitionRequest request) {
            this.selectedRoot = selectedRoot;
            this.request = request;
            this.policy = request.policy();
        }

        private RepositoryAcquisitionResult acquire() {
            Path absolute = selectedRoot.toAbsolutePath().normalize();
            if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
                return failed(Reason.ROOT_NOT_FOUND, Outcome.UNAVAILABLE);
            }
            if (Files.isSymbolicLink(absolute)) {
                return failed(Reason.ROOT_SYMBOLIC_LINK, Outcome.DENIED);
            }
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                root = absolute.toRealPath();
            } catch (IOException | SecurityException exception) {
                return failed(Reason.ROOT_READ_FAILED, Outcome.FAILED);
            }
            if (!attributes.isDirectory()) {
                return failed(Reason.ROOT_NOT_DIRECTORY, Outcome.DENIED);
            }
            if (!absolute.equals(root)) {
                return failed(Reason.ROOT_SYMBOLIC_LINK, Outcome.DENIED);
            }
            directories.add(".");
            directoryCount = 1;
            attempts.add(new Attempt(AttemptKind.ROOT, ".", Outcome.SUCCEEDED, Optional.empty()));
            visitDirectory(root, ".", 0);

            if (!files.stream().anyMatch(file -> file.path().equals(request.rootPom()))) {
                problems.add(new Problem(
                        Reason.MISSING_ROOT_POM, request.rootPom(), Requirement.BUILD_MODEL_INPUT));
            }

            Optional<RepositorySnapshot> snapshot = Optional.empty();
            Completion completion;
            if (!incomplete) {
                snapshot = Optional.of(RepositorySnapshot.create(
                        request.repository(),
                        request.revision(),
                        request.dirty(),
                        files.stream()
                                .sorted()
                                .map(file -> new SnapshotFile(file.path(), file.contentDigest()))
                                .toList(),
                        List.of()));
                completion = Completion.COMPLETE;
            } else {
                completion = files.isEmpty() && directories.size() <= 1
                        ? Completion.FAILED
                        : Completion.PARTIAL;
            }
            return result(completion, snapshot);
        }

        private RepositoryAcquisitionResult failed(Reason reason, Outcome outcome) {
            problems.add(new Problem(reason, ".", Requirement.REPOSITORY_SELECTION));
            attempts.add(new Attempt(AttemptKind.ROOT, ".", outcome, Optional.empty()));
            return result(Completion.FAILED, Optional.empty());
        }

        private RepositoryAcquisitionResult result(
                Completion completion, Optional<RepositorySnapshot> snapshot) {
            return new RepositoryAcquisitionResult(
                    RepositoryAcquisitionResult.SCHEMA,
                    request,
                    VERSION,
                    completion,
                    snapshot,
                    files,
                    directories,
                    problems,
                    attempts,
                    RepositoryAcquisitionResult.LIMITATIONS);
        }

        private void visitDirectory(Path directory, String logicalDirectory, int depth) {
            if (stop) return;
            BasicFileAttributes before;
            List<Path> children;
            try {
                Path real = directory.toRealPath();
                if (!real.startsWith(root) || !real.equals(directory.toAbsolutePath().normalize())) {
                    reject(Reason.PATH_OUTSIDE_ROOT, logicalDirectory, Requirement.REPOSITORY_SELECTION,
                            AttemptKind.DIRECTORY, Outcome.DENIED, false);
                    return;
                }
                before = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!before.isDirectory() || Files.isSymbolicLink(directory)) {
                    reject(Reason.SYMBOLIC_LINK, logicalDirectory, Requirement.REPOSITORY_SELECTION,
                            AttemptKind.DIRECTORY, Outcome.DENIED, false);
                    return;
                }
            } catch (IOException | SecurityException exception) {
                reject(Reason.DIRECTORY_READ_FAILED, logicalDirectory, Requirement.FILESYSTEM_READ,
                        AttemptKind.DIRECTORY, Outcome.FAILED, false);
                return;
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                children = new ArrayList<>();
                for (Path child : stream) {
                    if (discoveredEntryCount >= policy.maxDiscoveredEntries()) {
                        reject(Reason.ENTRY_COUNT_LIMIT, logicalDirectory, Requirement.ACQUISITION_POLICY,
                                AttemptKind.DIRECTORY, Outcome.DENIED, true);
                        return;
                    }
                    discoveredEntryCount++;
                    children.add(child);
                }
            } catch (IOException | SecurityException exception) {
                reject(Reason.DIRECTORY_READ_FAILED, logicalDirectory, Requirement.FILESYSTEM_READ,
                        AttemptKind.DIRECTORY, Outcome.FAILED, false);
                return;
            }
            children.sort(Comparator.comparing(path -> path.getFileName().toString()));
            for (Path child : children) {
                if (stop) return;
                String logicalPath;
                try {
                    logicalPath = logical(root.relativize(child.toAbsolutePath().normalize()));
                } catch (IllegalArgumentException exception) {
                    reject(Reason.INVALID_LOGICAL_PATH, opaqueSubject(child), Requirement.REPOSITORY_SELECTION,
                            AttemptKind.FILE, Outcome.DENIED, false);
                    continue;
                }
                if (policy.excludes(logicalPath)) {
                    attempts.add(new Attempt(
                            AttemptKind.EXCLUSION, logicalPath, Outcome.EXCLUDED, Optional.empty()));
                    continue;
                }
                int childDepth = depth + 1;
                if (childDepth > policy.maxDepth()) {
                    reject(Reason.DEPTH_LIMIT, logicalPath, Requirement.ACQUISITION_POLICY,
                            AttemptKind.FILE, Outcome.DENIED, true);
                    continue;
                }
                if (Files.isSymbolicLink(child)) {
                    reject(Reason.SYMBOLIC_LINK, logicalPath, Requirement.REPOSITORY_SELECTION,
                            AttemptKind.FILE, Outcome.DENIED, false);
                    continue;
                }
                BasicFileAttributes entryAttributes;
                try {
                    Path absolute = child.toAbsolutePath().normalize();
                    Path real = child.toRealPath();
                    if (!real.startsWith(root)) {
                        reject(Reason.PATH_OUTSIDE_ROOT, logicalPath, Requirement.REPOSITORY_SELECTION,
                                AttemptKind.FILE, Outcome.DENIED, false);
                        continue;
                    }
                    if (!real.equals(absolute)) {
                        reject(Reason.SYMBOLIC_LINK, logicalPath, Requirement.REPOSITORY_SELECTION,
                                AttemptKind.FILE, Outcome.DENIED, false);
                        continue;
                    }
                    entryAttributes = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (IOException | SecurityException exception) {
                    reject(Reason.FILE_READ_FAILED, logicalPath, Requirement.FILESYSTEM_READ,
                            AttemptKind.FILE, Outcome.FAILED, false);
                    continue;
                }
                if (entryAttributes.isDirectory()) {
                    if (directoryCount >= policy.maxDirectories()) {
                        reject(Reason.DIRECTORY_COUNT_LIMIT, logicalPath, Requirement.ACQUISITION_POLICY,
                                AttemptKind.DIRECTORY, Outcome.DENIED, true);
                        continue;
                    }
                    directoryCount++;
                    directories.add(logicalPath);
                    attempts.add(new Attempt(
                            AttemptKind.DIRECTORY, logicalPath, Outcome.SUCCEEDED, Optional.empty()));
                    visitDirectory(child, logicalPath, childDepth);
                } else if (entryAttributes.isRegularFile()) {
                    visitFile(child, logicalPath, entryAttributes);
                } else {
                    reject(Reason.NON_REGULAR_ENTRY, logicalPath, Requirement.REPOSITORY_SELECTION,
                            AttemptKind.FILE, Outcome.DENIED, false);
                }
            }
            if (!stop) verifyDirectoryStable(directory, logicalDirectory, before);
        }

        private void verifyDirectoryStable(
                Path directory, String logicalDirectory, BasicFileAttributes before) {
            try {
                BasicFileAttributes after = Files.readAttributes(
                        directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (Files.isSymbolicLink(directory)
                        || !after.isDirectory()
                        || !before.lastModifiedTime().equals(after.lastModifiedTime())
                        || before.fileKey() != null && after.fileKey() != null
                                && !before.fileKey().equals(after.fileKey())) {
                    reject(Reason.DIRECTORY_CHANGED_DURING_READ, logicalDirectory,
                            Requirement.FILESYSTEM_READ, AttemptKind.DIRECTORY, Outcome.FAILED, false);
                }
            } catch (IOException | SecurityException exception) {
                reject(Reason.DIRECTORY_CHANGED_DURING_READ, logicalDirectory,
                        Requirement.FILESYSTEM_READ, AttemptKind.DIRECTORY, Outcome.FAILED, false);
            }
        }

        private void visitFile(Path path, String logicalPath, BasicFileAttributes before) {
            if (fileCount >= policy.maxFiles()) {
                reject(Reason.FILE_COUNT_LIMIT, logicalPath, Requirement.ACQUISITION_POLICY,
                        AttemptKind.FILE, Outcome.DENIED, true);
                return;
            }
            fileCount++;
            if (before.size() > policy.maxFileBytes()) {
                reject(Reason.FILE_BYTE_LIMIT, logicalPath, Requirement.ACQUISITION_POLICY,
                        AttemptKind.FILE, Outcome.DENIED, false);
                return;
            }
            if (before.size() > policy.maxTotalBytes() - totalBytes) {
                reject(Reason.TOTAL_BYTE_LIMIT, logicalPath, Requirement.ACQUISITION_POLICY,
                        AttemptKind.FILE, Outcome.DENIED, true);
                return;
            }
            byte[] bytes;
            try {
                bytes = readBounded(path, policy.maxFileBytes(), policy.maxTotalBytes() - totalBytes);
            } catch (LimitExceeded exception) {
                Reason reason = exception.file ? Reason.FILE_BYTE_LIMIT : Reason.TOTAL_BYTE_LIMIT;
                reject(reason, logicalPath, Requirement.ACQUISITION_POLICY,
                        AttemptKind.FILE, Outcome.DENIED, !exception.file);
                return;
            } catch (IOException | SecurityException exception) {
                reject(Reason.FILE_READ_FAILED, logicalPath, Requirement.FILESYSTEM_READ,
                        AttemptKind.FILE, Outcome.FAILED, false);
                return;
            }
            try {
                BasicFileAttributes after = Files.readAttributes(
                        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (Files.isSymbolicLink(path)
                        || !after.isRegularFile()
                        || before.size() != after.size()
                        || !before.lastModifiedTime().equals(after.lastModifiedTime())
                        || before.fileKey() != null && after.fileKey() != null
                                && !before.fileKey().equals(after.fileKey())) {
                    reject(Reason.FILE_CHANGED_DURING_READ, logicalPath, Requirement.FILESYSTEM_READ,
                            AttemptKind.FILE, Outcome.FAILED, false);
                    return;
                }
            } catch (IOException | SecurityException exception) {
                reject(Reason.FILE_CHANGED_DURING_READ, logicalPath, Requirement.FILESYSTEM_READ,
                        AttemptKind.FILE, Outcome.FAILED, false);
                return;
            }
            var file = new AcquiredFile(logicalPath, bytes);
            files.add(file);
            totalBytes += bytes.length;
            attempts.add(new Attempt(
                    AttemptKind.FILE, logicalPath, Outcome.SUCCEEDED, Optional.of(file.contentDigest())));
        }

        private static byte[] readBounded(Path path, long maxFileBytes, long remainingTotal)
                throws IOException, LimitExceeded {
            long allowed = Math.min(maxFileBytes, remainingTotal);
            try (SeekableByteChannel channel = Files.newByteChannel(
                    path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                var output = new ByteArrayOutputStream((int) Math.min(8192, allowed));
                ByteBuffer buffer = ByteBuffer.allocate(8192);
                long count = 0;
                while (channel.read(buffer) >= 0) {
                    buffer.flip();
                    int amount = buffer.remaining();
                    count += amount;
                    if (count > allowed) {
                        throw new LimitExceeded(count > maxFileBytes);
                    }
                    output.write(buffer.array(), buffer.position(), amount);
                    buffer.clear();
                }
                return output.toByteArray();
            }
        }

        private void reject(
                Reason reason,
                String subject,
                Requirement requirement,
                AttemptKind kind,
                Outcome outcome,
                boolean stopTraversal) {
            incomplete = true;
            stop |= stopTraversal;
            problems.add(new Problem(reason, subject, requirement));
            attempts.add(new Attempt(kind, subject, outcome, Optional.empty()));
        }

        private static String logical(Path relative) {
            String value = relative.toString().replace(java.io.File.separatorChar, '/');
            return com.evolution.analysis.contract.common.ContractChecks.repositoryRelativePath(
                    value, "filesystem path");
        }

        private static String opaqueSubject(Path path) {
            return "path:" + com.evolution.analysis.contract.common.ContentDigest
                    .sha256Utf8(path.getFileName().toString()).value();
        }

        private static final class LimitExceeded extends Exception {
            private final boolean file;

            private LimitExceeded(boolean file) {
                this.file = file;
            }
        }
    }
}
