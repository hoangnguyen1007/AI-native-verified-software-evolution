package com.evolution.analysis.frontend;

import com.evolution.analysis.contract.analysis.ClasspathEntry;
import com.evolution.analysis.contract.analysis.ClasspathEntryKind;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Explicit analyzed-platform symbol artifacts; physical locators never enter stable identity. */
public final class PlatformInput {
    public static final String SCHEMA = "platform-symbol-view-v1";

    private final ClasspathEntry entry;
    private final int release;
    private final String version;
    private final String vendor;
    private final List<Artifact> artifacts;

    public PlatformInput(
            ClasspathEntry entry,
            int release,
            String version,
            String vendor,
            List<Artifact> artifacts) {
        this.entry = Objects.requireNonNull(entry);
        if (entry.kind() != ClasspathEntryKind.JDK_MODULE) {
            throw new FrontendInputException("frontend.platform-kind", "Platform entry must identify a JDK view");
        }
        if (release < 1) throw new IllegalArgumentException("Platform release must be positive");
        this.release = release;
        this.version = ContractChecks.text(version, "platform version");
        this.vendor = ContractChecks.text(vendor, "platform vendor");
        this.artifacts = ContractChecks.sortedDistinct(
                artifacts, Comparator.comparing(Artifact::logicalName), "platform artifacts");
        if (this.artifacts.isEmpty()) throw new IllegalArgumentException("Platform symbol view requires an artifact");
        if (!entry.equals(deriveEntry(release, this.version, this.vendor, this.artifacts))) {
            throw new IllegalArgumentException("Platform entry does not match its exact symbol artifacts");
        }
    }

    public static PlatformInput create(
            int release, String version, String vendor, List<Artifact> artifacts) {
        List<Artifact> sorted = artifacts.stream().sorted(Comparator.comparing(Artifact::logicalName)).toList();
        return new PlatformInput(deriveEntry(release, version, vendor, sorted),
                release, version, vendor, sorted);
    }

    private static ClasspathEntry deriveEntry(
            int release, String version, String vendor, List<Artifact> artifacts) {
        List<ArtifactView> views = artifacts.stream()
                .map(value -> new ArtifactView(value.logicalName(), value.contentDigest(), value.format()))
                .toList();
        ContentDigest digest = ContentDigest.sha256Utf8(CanonicalJson.write(Map.of(
                "schema", SCHEMA, "release", release, "version", version,
                "vendor", vendor, "artifacts", views)));
        return new ClasspathEntry(ClasspathEntryKind.JDK_MODULE,
                "jdk-platform:" + release + ":" + ContentDigest.sha256Utf8(vendor + "\n" + version).value(), digest);
    }

    public ClasspathEntry entry() { return entry; }
    public int release() { return release; }
    public String version() { return version; }
    public String vendor() { return vendor; }
    public List<Artifact> artifacts() { return artifacts; }

    public enum Format { RUNTIME_MODULES, JAR, JMOD, CT_SYM }

    public record Artifact(String logicalName, ContentDigest contentDigest, Path path, Format format) {
        public Artifact {
            logicalName = ContractChecks.text(logicalName, "platform artifact name");
            ContractChecks.notNull(contentDigest, "platform artifact digest");
            path = Objects.requireNonNull(path).toAbsolutePath().normalize();
            ContractChecks.notNull(format, "platform artifact format");
        }
    }

    private record ArtifactView(String logicalName, ContentDigest contentDigest, Format format) {}
}
