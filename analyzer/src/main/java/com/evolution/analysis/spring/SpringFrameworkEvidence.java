package com.evolution.analysis.spring;

import com.evolution.analysis.classpath.ExactClasspathResult;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.*;

/** Exact Spring-relevant artifact evidence projected from one M3 classpath manifest. */
public record SpringFrameworkEvidence(boolean completeClasspath,
        Optional<ContentDigest> classpathManifestIdentity, List<Artifact> artifacts) {
    public SpringFrameworkEvidence {
        classpathManifestIdentity = ContractChecks.notNull(
                classpathManifestIdentity, "Spring classpath manifest identity");
        artifacts = ContractChecks.sortedDistinct(
                artifacts, Comparator.naturalOrder(), "Spring framework artifacts");
        if (artifacts.stream().map(Artifact::classpathLogicalName).distinct().count() != artifacts.size()) {
            throw new IllegalArgumentException("Spring framework evidence has duplicate classpath entries");
        }
    }

    public SpringFrameworkEvidence(boolean completeClasspath, List<Artifact> artifacts) {
        this(completeClasspath, Optional.empty(), artifacts);
    }

    public static SpringFrameworkEvidence from(ExactClasspathResult.Manifest manifest) {
        ContractChecks.notNull(manifest, "classpath manifest");
        List<Artifact> artifacts = manifest.entries().stream()
                .map(entry -> new Artifact(entry.coordinate().gav().notation(),
                        entry.classpathEntry().logicalName(), entry.classpathEntry().contentDigest()))
                .toList();
        return new SpringFrameworkEvidence(manifest.status() == ExactClasspathResult.Status.COMPLETE,
                Optional.of(manifest.identity()), artifacts);
    }

    public ContentDigest identity() {
        return ContentDigest.sha256Utf8(CanonicalJson.write(this));
    }

    public boolean containsEntityScope(com.evolution.analysis.contract.identity.EntityScope scope) {
        return artifacts.stream().anyMatch(value -> value.entityScope().equals(scope));
    }

    /** Exact accepted R0 artifact tuples, never a version-range or name-only support claim. */
    public boolean acceptedConditionFragment(boolean bootRequired) {
        if (!completeClasspath || classpathManifestIdentity.isEmpty()) return false;
        Set<String> framework = new TreeSet<>(), boot = new TreeSet<>();
        for (var artifact : artifacts) {
            if (artifact.groupId().equals("org.springframework")) framework.add(artifact.version());
            if (artifact.groupId().equals("org.springframework.boot")) boot.add(artifact.version());
        }
        if (framework.size() != 1 || boot.size() > 1 || bootRequired && boot.isEmpty()) return false;
        String version = framework.iterator().next();
        String expectedBoot = switch (version) {
            case "5.3.31" -> "2.7.18"; case "6.1.14" -> "3.3.5"; case "6.2.0" -> "3.4.0"; default -> "";
        };
        if (expectedBoot.isEmpty() || !boot.isEmpty() && !boot.equals(Set.of(expectedBoot))) return false;
        for (var expected : SpringMechanismScanner.expectedArtifacts(version, expectedBoot).entrySet()) {
            if (boot.isEmpty() && expected.getKey().startsWith("org.springframework.boot:")) continue;
            var matching = artifacts.stream().filter(a -> a.coordinate().equals(expected.getKey())).toList();
            if (matching.size() != 1 || !matching.getFirst().classpathLogicalName().equals(expected.getKey() + "@jar")
                    || !matching.getFirst().contentDigest().value().equals(expected.getValue())) return false;
        }
        return true;
    }

    public record Artifact(String coordinate, String classpathLogicalName, ContentDigest contentDigest)
            implements Comparable<Artifact> {
        public Artifact(String coordinate, ContentDigest contentDigest) {
            this(coordinate, coordinate + "@jar", contentDigest);
        }

        public Artifact {
            coordinate = ContractChecks.text(coordinate, "Spring artifact coordinate");
            String[] parts = coordinate.split(":", -1);
            if (parts.length != 3 || Arrays.stream(parts).anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("Spring artifact coordinate must be group:artifact:version");
            }
            classpathLogicalName = ContractChecks.text(
                    classpathLogicalName, "Spring artifact classpath logical name");
            ContractChecks.notNull(contentDigest, "Spring artifact digest");
        }

        public String groupId() { return coordinate.split(":", -1)[0]; }
        public String artifactId() { return coordinate.split(":", -1)[1]; }
        public String version() { return coordinate.split(":", -1)[2]; }

        public com.evolution.analysis.contract.identity.EntityScope entityScope() {
            return com.evolution.analysis.contract.identity.EntityScope.external(
                    com.evolution.analysis.contract.semantic.EntityOrigin.DEPENDENCY,
                    classpathLogicalName, contentDigest);
        }

        @Override public int compareTo(Artifact other) {
            int coordinateOrder = coordinate.compareTo(other.coordinate);
            if (coordinateOrder != 0) return coordinateOrder;
            int logicalNameOrder = classpathLogicalName.compareTo(other.classpathLogicalName);
            if (logicalNameOrder != 0) return logicalNameOrder;
            return contentDigest.compareTo(other.contentDigest);
        }
    }
}
