package com.evolution.analysis.filesystem;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.frontend.PlatformInput;
import com.evolution.analysis.input.PlatformSymbolProvider;
import com.evolution.analysis.input.PlatformSymbolRequest;
import com.evolution.analysis.input.PlatformSymbolResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/** Bounded no-follow acquisition of rt.jar or JMOD symbols from one explicit JDK home. */
public final class FilesystemJdkPlatformProvider implements PlatformSymbolProvider {
    public static final VersionedIdentifier PROVIDER = new VersionedIdentifier("platform.jdk-filesystem", "m3.7");
    private static final long RELEASE_FILE_LIMIT = 64 * 1024L;
    private static final Pattern PROPERTY = Pattern.compile("(?m)^([A-Z0-9_]+)=\"([^\"\\r\\n]*)\"$");

    @Override
    public PlatformSymbolResult acquire(Path configuredJdkHome, PlatformSymbolRequest request) {
        List<PlatformSymbolResult.Problem> problems = new ArrayList<>();
        List<PlatformSymbolResult.Attempt> attempts = new ArrayList<>();
        Path absolute = configuredJdkHome.toAbsolutePath().normalize();
        try {
            if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) return failed(request, problems, attempts,
                    PlatformSymbolResult.Reason.JDK_ROOT_NOT_FOUND, "jdk-root", PlatformSymbolResult.Outcome.UNAVAILABLE);
            if (Files.isSymbolicLink(absolute)) return failed(request, problems, attempts,
                    PlatformSymbolResult.Reason.JDK_ROOT_SYMBOLIC_LINK, "jdk-root", PlatformSymbolResult.Outcome.DENIED);
            if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) return failed(request, problems, attempts,
                    PlatformSymbolResult.Reason.JDK_ROOT_NOT_DIRECTORY, "jdk-root", PlatformSymbolResult.Outcome.FAILED);
            Path root = absolute.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!absolute.equals(root)) return failed(request, problems, attempts,
                    PlatformSymbolResult.Reason.JDK_ROOT_SYMBOLIC_LINK, "jdk-root", PlatformSymbolResult.Outcome.DENIED);

            Path releaseFile = root.resolve("release");
            if (!Files.exists(releaseFile, LinkOption.NOFOLLOW_LINKS)) return failed(request, problems, attempts,
                    PlatformSymbolResult.Reason.RELEASE_FILE_MISSING, "release", PlatformSymbolResult.Outcome.UNAVAILABLE);
            BasicFileAttributes releaseAttributes = Files.readAttributes(
                    releaseFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (releaseAttributes.isSymbolicLink() || !releaseAttributes.isRegularFile()
                    || releaseAttributes.size() > RELEASE_FILE_LIMIT) {
                return failed(request, problems, attempts, PlatformSymbolResult.Reason.RELEASE_FILE_INVALID,
                        "release", releaseAttributes.isSymbolicLink()
                                ? PlatformSymbolResult.Outcome.DENIED : PlatformSymbolResult.Outcome.FAILED);
            }
            byte[] releaseBytes = read(root, releaseFile, "release", RELEASE_FILE_LIMIT, attempts);
            String metadata = decodeRelease(releaseBytes);
            String version = property(metadata, "JAVA_VERSION").orElseThrow();
            String vendor = property(metadata, "IMPLEMENTOR")
                    .or(() -> property(metadata, "JAVA_VENDOR")).orElseThrow();
            int feature = feature(version);
            if (feature != request.release()) {
                Path ctSym = root.resolve("lib/ct.sym");
                if (feature > request.release() && Files.exists(ctSym, LinkOption.NOFOLLOW_LINKS)) {
                    BasicFileAttributes attributes = Files.readAttributes(
                            ctSym, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (attributes.isSymbolicLink() || !attributes.isRegularFile()) return failed(request, problems, attempts,
                            PlatformSymbolResult.Reason.SYMBOLIC_LINK, "lib/ct.sym", PlatformSymbolResult.Outcome.DENIED);
                    if (attributes.size() > request.maxArtifactBytes()) return failed(request, problems, attempts,
                            PlatformSymbolResult.Reason.ARTIFACT_BYTE_LIMIT, "lib/ct.sym", PlatformSymbolResult.Outcome.LIMIT_EXCEEDED);
                    if (attributes.size() > request.maxTotalBytes()) return failed(request, problems, attempts,
                            PlatformSymbolResult.Reason.TOTAL_BYTE_LIMIT, "lib/ct.sym", PlatformSymbolResult.Outcome.LIMIT_EXCEEDED);
                    byte[] bytes = read(root, ctSym, "lib/ct.sym#release-" + request.release(),
                            Math.min(request.maxArtifactBytes(), request.maxTotalBytes()), attempts);
                    if (!validCtSym(ctSym, request.release())) return failed(request, problems, attempts,
                            PlatformSymbolResult.Reason.RELEASE_NOT_IN_CT_SYM, "release:" + request.release(),
                            PlatformSymbolResult.Outcome.UNAVAILABLE);
                    var artifact = new PlatformInput.Artifact(
                            "lib/ct.sym#release-" + request.release(), ContentDigest.sha256(bytes), ctSym,
                            PlatformInput.Format.CT_SYM);
                    PlatformInput input = PlatformInput.create(request.release(),
                            "release-" + request.release() + "-from-" + version, vendor, List.of(artifact));
                    return PlatformSymbolResult.create(request, PROVIDER, Optional.of(input), List.of(), attempts);
                }
                return failed(request, problems, attempts,
                        PlatformSymbolResult.Reason.RELEASE_MISMATCH, "release:" + request.release(),
                        PlatformSymbolResult.Outcome.FAILED);
            }

            List<Path> symbols;
            PlatformInput.Format format;
            if (feature <= 8) {
                Path first = root.resolve("jre/lib/rt.jar");
                Path second = root.resolve("lib/rt.jar");
                Path selected = Files.exists(first, LinkOption.NOFOLLOW_LINKS) ? first : second;
                if (!Files.exists(selected, LinkOption.NOFOLLOW_LINKS)) return failed(request, problems, attempts,
                        PlatformSymbolResult.Reason.SYMBOL_ROOT_MISSING, "rt.jar", PlatformSymbolResult.Outcome.UNAVAILABLE);
                symbols = List.of(selected);
                format = PlatformInput.Format.JAR;
            } else {
                Path jmods = root.resolve("jmods");
                if (!Files.isDirectory(jmods, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(jmods)) {
                    return failed(request, problems, attempts, PlatformSymbolResult.Reason.SYMBOL_ROOT_MISSING,
                            "jmods", PlatformSymbolResult.Outcome.UNAVAILABLE);
                }
                if (!jmods.toAbsolutePath().normalize().equals(jmods.toRealPath(LinkOption.NOFOLLOW_LINKS))) {
                    return failed(request, problems, attempts, PlatformSymbolResult.Reason.SYMBOLIC_LINK,
                            "jmods", PlatformSymbolResult.Outcome.DENIED);
                }
                try (var stream = Files.list(jmods)) {
                    symbols = stream.filter(path -> path.getFileName().toString().endsWith(".jmod"))
                            .limit((long) request.maxArtifacts() + 1L)
                            .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
                }
                format = PlatformInput.Format.JMOD;
            }
            if (symbols.isEmpty()) return failed(request, problems, attempts,
                    PlatformSymbolResult.Reason.SYMBOL_ROOT_MISSING, "platform-symbols", PlatformSymbolResult.Outcome.UNAVAILABLE);
            if (symbols.size() > request.maxArtifacts()) return failed(request, problems, attempts,
                    PlatformSymbolResult.Reason.ARTIFACT_COUNT_LIMIT, "platform-symbols", PlatformSymbolResult.Outcome.LIMIT_EXCEEDED);

            long total = 0;
            List<PlatformInput.Artifact> artifacts = new ArrayList<>();
            for (Path symbol : symbols) {
                String logical = feature <= 8 ? "lib/rt.jar" : "jmods/" + symbol.getFileName();
                BasicFileAttributes attributes = Files.readAttributes(symbol, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink()) return failed(request, problems, attempts,
                        PlatformSymbolResult.Reason.SYMBOLIC_LINK, logical, PlatformSymbolResult.Outcome.DENIED);
                if (!attributes.isRegularFile()) return failed(request, problems, attempts,
                        PlatformSymbolResult.Reason.NON_REGULAR_ARTIFACT, logical, PlatformSymbolResult.Outcome.FAILED);
                if (attributes.size() > request.maxArtifactBytes()) return failed(request, problems, attempts,
                        PlatformSymbolResult.Reason.ARTIFACT_BYTE_LIMIT, logical, PlatformSymbolResult.Outcome.LIMIT_EXCEEDED);
                if (attributes.size() > request.maxTotalBytes() - total) return failed(request, problems, attempts,
                        PlatformSymbolResult.Reason.TOTAL_BYTE_LIMIT, "platform-symbols", PlatformSymbolResult.Outcome.LIMIT_EXCEEDED);
                total += attributes.size();
                byte[] bytes = read(root, symbol, logical, request.maxArtifactBytes(), attempts);
                if (!validArchive(symbol, format)) return failed(request, problems, attempts,
                        PlatformSymbolResult.Reason.INVALID_SYMBOL_ARCHIVE, logical,
                        PlatformSymbolResult.Outcome.FAILED);
                artifacts.add(new PlatformInput.Artifact(logical, ContentDigest.sha256(bytes), symbol, format));
            }
            PlatformInput input = PlatformInput.create(feature, version, vendor, artifacts);
            return PlatformSymbolResult.create(request, PROVIDER, Optional.of(input), List.of(), attempts);
        } catch (ArithmeticException | IllegalArgumentException exception) {
            return failed(request, problems, attempts, PlatformSymbolResult.Reason.RELEASE_FILE_INVALID,
                    "release", PlatformSymbolResult.Outcome.FAILED);
        } catch (IOException exception) {
            return failed(request, problems, attempts, PlatformSymbolResult.Reason.ARTIFACT_READ_FAILED,
                    "platform-symbols", PlatformSymbolResult.Outcome.FAILED);
        }
    }

    private static byte[] read(
            Path root, Path path, String logical, long limit,
            List<PlatformSymbolResult.Attempt> attempts) throws IOException {
        Path real = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!real.startsWith(root) || Files.isSymbolicLink(path)) throw new IOException("denied");
        BasicFileAttributes before = Files.readAttributes(real, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        long size = before.size();
        if (size > limit || size > Integer.MAX_VALUE - 8L) throw new IOException("limit");
        byte[] bytes;
        try (InputStream input = Files.newInputStream(real, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readAllBytes();
        }
        BasicFileAttributes after = Files.readAttributes(real, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (before.size() != after.size() || before.lastModifiedTime().compareTo(after.lastModifiedTime()) != 0
                || !java.util.Objects.equals(before.fileKey(), after.fileKey()) || bytes.length != before.size()) {
            throw new IOException("changed");
        }
        attempts.add(new PlatformSymbolResult.Attempt(logical, PlatformSymbolResult.Outcome.SUCCEEDED,
                Optional.of(ContentDigest.sha256(bytes))));
        return bytes;
    }

    private static Optional<String> property(String text, String name) {
        Matcher matcher = PROPERTY.matcher(text);
        while (matcher.find()) if (matcher.group(1).equals(name)) return Optional.of(matcher.group(2));
        return Optional.empty();
    }

    private static int feature(String version) {
        String numeric = version.startsWith("1.") ? version.substring(2) : version;
        int end = 0;
        while (end < numeric.length() && Character.isDigit(numeric.charAt(end))) end++;
        if (end == 0) throw new IllegalArgumentException("invalid version");
        return Integer.parseInt(numeric.substring(0, end));
    }

    private static String decodeRelease(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Invalid release metadata");
        }
    }

    private static boolean validArchive(Path path, PlatformInput.Format format) {
        try (ZipFile archive = new ZipFile(path.toFile())) {
            String prefix = format == PlatformInput.Format.JMOD ? "classes/" : "";
            return archive.stream().anyMatch(entry -> !entry.isDirectory()
                    && entry.getName().startsWith(prefix)
                    && entry.getName().endsWith(".class"));
        } catch (IOException exception) {
            return false;
        }
    }

    private static boolean validCtSym(Path path, int release) {
        String code = releaseCode(release);
        if (code == null) return false;
        try (ZipFile archive = new ZipFile(path.toFile())) {
            return archive.stream().anyMatch(entry -> {
                String[] parts = entry.getName().split("/", 3);
                return !entry.isDirectory() && parts.length == 3 && parts[0].contains(code)
                        && parts[2].endsWith(".sig") && !parts[2].equals("module-info.sig");
            });
        } catch (IOException exception) {
            return false;
        }
    }

    private static String releaseCode(int release) {
        if (release >= 0 && release <= 9) return Integer.toString(release);
        if (release >= 10 && release <= 35) return Character.toString((char) ('A' + release - 10));
        return null;
    }

    private static PlatformSymbolResult failed(
            PlatformSymbolRequest request,
            List<PlatformSymbolResult.Problem> problems,
            List<PlatformSymbolResult.Attempt> attempts,
            PlatformSymbolResult.Reason reason,
            String subject,
            PlatformSymbolResult.Outcome outcome) {
        problems.add(new PlatformSymbolResult.Problem(reason, subject,
                reason.name().contains("LIMIT") ? PlatformSymbolResult.Requirement.ACQUISITION_POLICY
                        : reason.name().startsWith("RELEASE") ? PlatformSymbolResult.Requirement.PLATFORM_METADATA
                        : reason.name().startsWith("JDK_ROOT") ? PlatformSymbolResult.Requirement.CONFIGURED_JDK
                        : PlatformSymbolResult.Requirement.PLATFORM_SYMBOLS));
        attempts.add(new PlatformSymbolResult.Attempt(subject, outcome, Optional.empty()));
        return PlatformSymbolResult.create(request, PROVIDER, Optional.empty(), problems, attempts);
    }
}
