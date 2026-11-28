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

/** Bounded immutable reads from one explicitly selected standard Maven2-layout artifact cache. */
final class LocalArtifactCache {
    private final Path root;
    private final Map<MavenCoordinate,PomInput> suppliedPoms;
    private final java.util.function.Consumer<ArtifactCoordinate> missingArtifact;
    private final ClasspathResolutionPolicy policy;
    private final Map<ArtifactCoordinate, Material> materials = new HashMap<>();
    private final Map<ArtifactCoordinate, Failure> failures = new HashMap<>();
    private final List<ArtifactRecord> artifacts = new ArrayList<>();
    private final List<Attempt> attempts = new ArrayList<>();
    private long totalBytes;
    private int fileCount;

    LocalArtifactCache(Path selectedRoot, BuildModelRequest buildRequest, ClasspathResolutionPolicy policy)
            throws RootFailure {
        this(selectedRoot,Objects.requireNonNull(buildRequest).artifactPoms(),policy,ignored->{});
    }

    /** Neutral descriptor acquisition for non-Maven consumers. No target POM is synthesized. */
    LocalArtifactCache(Path selectedRoot,Map<MavenCoordinate,PomInput> suppliedPoms,ClasspathResolutionPolicy policy,
                       java.util.function.Consumer<ArtifactCoordinate> missingArtifact)throws RootFailure {
        this.suppliedPoms=Map.copyOf(suppliedPoms);this.missingArtifact=Objects.requireNonNull(missingArtifact);
        this.policy = Objects.requireNonNull(policy);
        this.root = validateRoot(Objects.requireNonNull(selectedRoot));
    }

    Material pom(MavenCoordinate gav) throws Failure {
        ArtifactCoordinate coordinate = new ArtifactCoordinate(gav, "pom", "");
        PomInput supplied = suppliedPoms.get(gav);
        return supplied == null
                ? local(coordinate, ArtifactKind.POM, policy.maxPomBytes())
                : supplied(coordinate, supplied);
    }

    Material jar(ArtifactCoordinate coordinate) throws Failure {
        return binary(coordinate);
    }

    Material binary(ArtifactCoordinate coordinate) throws Failure {
        String ext = coordinate.extension();
        if (!ext.equals("jar") && !ext.equals("aar") && !ext.equals("war")) {
            throw new IllegalArgumentException("Only JAR, AAR or WAR coordinates can be acquired as binaries");
        }
        return local(coordinate, ArtifactKind.JAR, policy.maxArtifactBytes());
    }

    boolean hasLocalFile(ArtifactCoordinate coordinate) {
        String relative = coordinate.repositoryPath();
        Path path = root.resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
        return path.startsWith(root)
                && !Files.isSymbolicLink(path)
                && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    void clearMissingPomFailure(MavenCoordinate gav) {
        ArtifactCoordinate coordinate = new ArtifactCoordinate(gav, "pom", "");
        Failure existing = failures.get(coordinate);
        if (existing != null && existing.reason == Reason.MISSING_POM) {
            failures.remove(coordinate);
        }
    }

    /**
     * Passively resolves a Maven version range (e.g. {@code [1.0,2.0)}) against exact,
     * non-SNAPSHOT versions already present inside this validated local cache root.
     */
    Optional<String> resolveLocalVersionRange(String groupId, String artifactId, String rangeSpec, String extension, String classifier) {
        if (groupId == null || artifactId == null || rangeSpec == null || rangeSpec.length() < 3) {
            return Optional.empty();
        }
        List<VersionInterval> intervals = parseVersionRange(rangeSpec.trim());
        if (intervals.isEmpty()) return Optional.empty();
        String groupRel = groupId.replace('.', java.io.File.separatorChar);
        Path artifactDir = root.resolve(groupRel).resolve(artifactId).normalize();
        if (!artifactDir.startsWith(root) || Files.isSymbolicLink(artifactDir)
                || !Files.isDirectory(artifactDir, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            Path realDir = artifactDir.toRealPath();
            if (!realDir.startsWith(root) || !realDir.equals(artifactDir.toAbsolutePath().normalize())) {
                return Optional.empty();
            }
            var candidates = new ArrayList<String>();
            try (var stream = Files.list(artifactDir)) {
                var it = stream.iterator();
                int scanned = 0;
                while (it.hasNext() && scanned < 4096) {
                    Path entry = it.next();
                    scanned++;
                    if (Files.isSymbolicLink(entry) || !Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) continue;
                    String version = entry.getFileName().toString();
                    if (version.isEmpty() || version.endsWith("-SNAPSHOT")) continue;
                    if (!matchesAnyInterval(version, intervals)) continue;
                    try {
                        var gav = new MavenCoordinate(groupId, artifactId, version);
                        String ext = (extension == null || extension.isEmpty()) ? "jar" : extension;
                        var binCoord = new ArtifactCoordinate(gav, ext, classifier == null ? "" : classifier);
                        var pomCoord = new ArtifactCoordinate(gav, "pom", "");
                        if (hasLocalFile(binCoord) || hasLocalFile(pomCoord)) {
                            candidates.add(version);
                        }
                    } catch (IllegalArgumentException ignored) {
                        // Skip non-coordinate directory names deterministically.
                    }
                }
            }
            if (candidates.isEmpty()) return Optional.empty();
            candidates.sort(LocalArtifactCache::compareMavenVersions);
            return Optional.of(candidates.getLast());
        } catch (IOException | SecurityException ignored) {
            return Optional.empty();
        }
    }

    private record VersionInterval(String lower, boolean lowerInclusive, String upper, boolean upperInclusive) {}

    private static List<VersionInterval> parseVersionRange(String spec) {
        if (!(spec.startsWith("[") || spec.startsWith("(")) || !(spec.endsWith("]") || spec.endsWith(")"))) {
            return List.of();
        }
        var result = new ArrayList<VersionInterval>();
        int i = 0, len = spec.length();
        while (i < len) {
            while (i < len && Character.isWhitespace(spec.charAt(i))) i++;
            if (i >= len) break;
            char open = spec.charAt(i);
            if (open != '[' && open != '(') return List.of();
            int closeIdx = -1;
            for (int j = i + 1; j < len; j++) {
                char c = spec.charAt(j);
                if (c == ']' || c == ')') {
                    closeIdx = j;
                    break;
                }
            }
            if (closeIdx < 0) return List.of();
            char close = spec.charAt(closeIdx);
            String body = spec.substring(i + 1, closeIdx);
            int comma = body.indexOf(',');
            if (comma < 0) {
                String exact = body.trim();
                if (exact.isEmpty() || open != '[' || close != ']') return List.of();
                result.add(new VersionInterval(exact, true, exact, true));
            } else {
                if (body.indexOf(',', comma + 1) >= 0) return List.of();
                String low = body.substring(0, comma).trim();
                String high = body.substring(comma + 1).trim();
                if (low.isEmpty() && high.isEmpty()) return List.of();
                result.add(new VersionInterval(low.isEmpty() ? null : low, open == '[',
                        high.isEmpty() ? null : high, close == ']'));
            }
            i = closeIdx + 1;
            while (i < len && Character.isWhitespace(spec.charAt(i))) i++;
            if (i < len) {
                if (spec.charAt(i) != ',') return List.of();
                i++;
            }
        }
        return result;
    }

    private static boolean matchesAnyInterval(String version, List<VersionInterval> intervals) {
        for (var interval : intervals) {
            if (interval.lower != null) {
                int cmp = compareMavenVersions(version, interval.lower);
                if (interval.lowerInclusive ? cmp < 0 : cmp <= 0) continue;
            }
            if (interval.upper != null) {
                int cmp = compareMavenVersions(version, interval.upper);
                if (interval.upperInclusive ? cmp > 0 : cmp >= 0) continue;
            }
            return true;
        }
        return false;
    }

    static int compareMavenVersions(String a, String b) {
        if (a.equals(b)) return 0;
        String[] pa = a.split("[.\\-+]");
        String[] pb = b.split("[.\\-+]");
        int max = Math.max(pa.length, pb.length);
        for (int i = 0; i < max; i++) {
            String sa = i < pa.length ? pa[i] : "";
            String sb = i < pb.length ? pb[i] : "";
            if (sa.equals(sb)) continue;
            boolean na = !sa.isEmpty() && sa.chars().allMatch(Character::isDigit);
            boolean nb = !sb.isEmpty() && sb.chars().allMatch(Character::isDigit);
            if (na && nb) {
                int cmp = new java.math.BigInteger(sa).compareTo(new java.math.BigInteger(sb));
                if (cmp != 0) return cmp;
            } else if (sa.isEmpty()) {
                return nb ? (new java.math.BigInteger(sb).signum() == 0 ? 0 : -1) : qualifierRank(sb) < 0 ? 1 : -1;
            } else if (sb.isEmpty()) {
                return na ? (new java.math.BigInteger(sa).signum() == 0 ? 0 : 1) : qualifierRank(sa) < 0 ? -1 : 1;
            } else if (na) {
                return 1;
            } else if (nb) {
                return -1;
            } else {
                int ra = qualifierRank(sa), rb = qualifierRank(sb);
                if (ra != rb) return Integer.compare(ra, rb);
                int cmp = sa.compareToIgnoreCase(sb);
                if (cmp != 0) return cmp;
            }
        }
        return a.compareTo(b);
    }

    private static int qualifierRank(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        if (lower.startsWith("alpha") || lower.equals("a")) return -5;
        if (lower.startsWith("beta") || lower.equals("b")) return -4;
        if (lower.startsWith("milestone") || lower.equals("m")) return -3;
        if (lower.startsWith("rc") || lower.startsWith("cr")) return -2;
        if (lower.equals("snapshot")) return -1;
        if (lower.equals("final") || lower.equals("ga") || lower.equals("release")) return 0;
        if (lower.startsWith("sp")) return 1;
        return 0;
    }

    List<ArtifactRecord> artifacts() {
        return List.copyOf(artifacts);
    }

    List<Attempt> attempts() {
        return List.copyOf(attempts);
    }

    void accountExternallyAcquiredBytes(long size) {
        if (size < 0 || size > policy.maxTotalBytes() - totalBytes) {
            throw new IllegalArgumentException("External bytes exceed the shared artifact-read budget");
        }
        totalBytes += size;
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
            var activeSession = com.evolution.analysis.input.VerifiedArtifactSession.current().orElse(null);
            if (activeSession != null) {
                if (kind == ArtifactKind.JAR) {
                    var fastJar = activeSession.lookupVerifiedJarByPath(path);
                    if (fastJar.isPresent()) {
                        var hit = fastJar.orElseThrow();
                        if (hit.realPath().startsWith(root)) {
                            reserve(coordinate, kind, ArtifactOrigin.LOCAL_CACHE, relative, hit.size(), perFileLimit);
                            var record = new ArtifactRecord(coordinate, kind, ArtifactOrigin.LOCAL_CACHE,
                                    Optional.of(relative), hit.digest(), hit.size());
                            var material = new Material(record, hit.header());
                            materials.put(coordinate, material);
                            artifacts.add(record);
                            totalBytes += hit.size();
                            attempts.add(new Attempt(kind, coordinate, ArtifactOrigin.LOCAL_CACHE,
                                    relative, AttemptOutcome.SUCCEEDED, Optional.of(hit.digest())));
                            return material;
                        }
                    }
                } else {
                    var fastPom = activeSession.lookupVerifiedSmallFileByPath(path);
                    if (fastPom.isPresent()) {
                        var hit = fastPom.orElseThrow();
                        if (hit.realPath().startsWith(root)) {
                            byte[] pomBytes = hit.bytes();
                            reserve(coordinate, kind, ArtifactOrigin.LOCAL_CACHE, relative, pomBytes.length, perFileLimit);
                            var record = new ArtifactRecord(coordinate, kind, ArtifactOrigin.LOCAL_CACHE,
                                    Optional.of(relative), hit.digest(), pomBytes.length);
                            var material = new Material(record, pomBytes);
                            materials.put(coordinate, material);
                            artifacts.add(record);
                            totalBytes += pomBytes.length;
                            attempts.add(new Attempt(kind, coordinate, ArtifactOrigin.LOCAL_CACHE,
                                    relative, AttemptOutcome.SUCCEEDED, Optional.of(hit.digest())));
                            return material;
                        }
                    }
                }
            }
            BasicFileAttributes before;
            try {
                before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException missingFirst) {
                missingArtifact.accept(coordinate);
                try {
                    before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (NoSuchFileException missingAfter) {
                    Reason reason = kind == ArtifactKind.POM ? Reason.MISSING_POM : Reason.MISSING_ARTIFACT;
                    throw failure(coordinate, kind, relative, reason, AttemptOutcome.UNAVAILABLE);
                } catch (IOException | SecurityException exception) {
                    throw failure(coordinate, kind, relative, Reason.ARTIFACT_READ_FAILED, AttemptOutcome.FAILED);
                }
            } catch (IOException | SecurityException exception) {
                throw failure(coordinate, kind, relative, Reason.ARTIFACT_READ_FAILED, AttemptOutcome.FAILED);
            }
            if (before.isSymbolicLink()) {
                throw failure(coordinate, kind, relative, Reason.SYMBOLIC_LINK, AttemptOutcome.DENIED);
            }
            if (!before.isRegularFile()) {
                throw failure(coordinate, kind, relative, Reason.NON_REGULAR_ARTIFACT, AttemptOutcome.DENIED);
            }
            Path real;
            try {
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
            reserve(coordinate, kind, ArtifactOrigin.LOCAL_CACHE, relative, before.size(), perFileLimit);
            byte[] bytes = null;
            StreamedJar streamedJar = null;
            ContentDigest digest = null;
            long byteLen = before.size();
            boolean sessionHit = false;
            if (activeSession != null) {
                if (kind == ArtifactKind.JAR) {
                    var cachedJar = activeSession.lookupVerifiedJar(real, before.size());
                    if (cachedJar.isPresent()) {
                        var hit = cachedJar.orElseThrow();
                        streamedJar = new StreamedJar(hit.digest(), hit.size(), hit.header());
                        digest = hit.digest();
                        byteLen = hit.size();
                        sessionHit = true;
                    }
                } else {
                    var cachedPom = activeSession.lookupVerifiedSmallFile(real, before.size());
                    if (cachedPom.isPresent()) {
                        var hit = cachedPom.orElseThrow();
                        bytes = hit.bytes();
                        digest = hit.digest();
                        byteLen = bytes.length;
                        sessionHit = true;
                    }
                }
            }
            if (!sessionHit) {
                try {
                    long allowed = Math.min(perFileLimit, policy.maxTotalBytes() - totalBytes);
                    if (kind == ArtifactKind.JAR) {
                        streamedJar = streamJar(path, allowed);
                    } else {
                        bytes = readBounded(path, allowed);
                    }
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
                if (after.isSymbolicLink()
                    || !after.isRegularFile()
                    || before.size() != after.size()
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || before.fileKey() != null && after.fileKey() != null
                            && !before.fileKey().equals(after.fileKey())) {
                    throw failure(coordinate, kind, relative, Reason.ARTIFACT_CHANGED_DURING_READ, AttemptOutcome.FAILED);
                }
                digest = streamedJar != null ? streamedJar.digest() : ContentDigest.sha256(bytes);
                byteLen = streamedJar != null ? streamedJar.size() : bytes.length;
                if (activeSession != null) {
                    if (streamedJar != null) {
                        activeSession.recordVerifiedJar(real, digest, byteLen, streamedJar.header());
                    } else {
                        activeSession.recordVerifiedSmallFile(real, digest, bytes);
                    }
                }
            }
            var record = new ArtifactRecord(coordinate, kind, ArtifactOrigin.LOCAL_CACHE,
                    Optional.of(relative), digest, byteLen);
            var material = streamedJar != null
                    ? new Material(record, streamedJar.header())
                    : new Material(record, bytes);
            materials.put(coordinate, material);
            artifacts.add(record);
            totalBytes += byteLen;
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

    private record StreamedJar(ContentDigest digest, long size, byte[] header) {}

    private static final ThreadLocal<java.security.MessageDigest> JAR_SHA256 = ThreadLocal.withInitial(() -> {
        try {
            return java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    });

    private static StreamedJar streamJar(Path path, long allowed) throws IOException, LimitExceeded {
        try (SeekableByteChannel channel = Files.newByteChannel(
                path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            var md = JAR_SHA256.get();
            md.reset();
            ByteBuffer buffer = ByteBuffer.allocate(65_536);
            byte[] header = new byte[4];
            int headerLen = 0;
            long count = 0;
            while (channel.read(buffer) >= 0) {
                buffer.flip();
                int amount = buffer.remaining();
                if (amount == 0) {
                    buffer.clear();
                    continue;
                }
                if (headerLen < 4) {
                    int copy = Math.min(4 - headerLen, amount);
                    System.arraycopy(buffer.array(), buffer.position(), header, headerLen, copy);
                    headerLen += copy;
                }
                count += amount;
                if (count > allowed) throw new LimitExceeded();
                md.update(buffer);
                buffer.clear();
            }
            return new StreamedJar(
                    new ContentDigest("sha256:" + java.util.HexFormat.of().formatHex(md.digest())),
                    count,
                    headerLen == 4 ? header : Arrays.copyOf(header, headerLen));
        }
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

        boolean hasZipMagic() {
            return record.size() >= 4 && bytes.length >= 2 && bytes[0] == 'P' && bytes[1] == 'K';
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
