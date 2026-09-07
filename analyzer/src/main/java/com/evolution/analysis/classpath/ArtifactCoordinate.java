package com.evolution.analysis.classpath;

import com.evolution.analysis.buildmodel.MavenCoordinate;
import com.evolution.analysis.contract.analysis.ClasspathEntry;
import com.evolution.analysis.contract.analysis.ClasspathEntryKind;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.ContractChecks;
import java.util.Objects;

/** Exact Maven repository artifact coordinate after declared-type to extension/classifier mapping. */
public record ArtifactCoordinate(MavenCoordinate gav, String extension, String classifier)
        implements Comparable<ArtifactCoordinate> {
    public ArtifactCoordinate {
        ContractChecks.notNull(gav, "artifact GAV");
        extension = component(extension, "artifact extension", false);
        classifier = component(classifier, "artifact classifier", true);
    }

    public String notation() {
        return gav.notation() + (classifier.isEmpty() ? "" : ":" + classifier) + "@" + extension;
    }

    public String conflictKey() {
        return gav.groupId() + ":" + gav.artifactId() + ":" + classifier + "@" + extension;
    }

    public String repositoryPath() {
        String group = gav.groupId().replace('.', '/');
        String suffix = classifier.isEmpty() ? "" : "-" + classifier;
        return group + "/" + gav.artifactId() + "/" + gav.version() + "/"
                + gav.artifactId() + "-" + gav.version() + suffix + "." + extension;
    }

    public ClasspathEntry classpathEntry(ContentDigest digest) {
        return new ClasspathEntry(ClasspathEntryKind.DEPENDENCY, notation(), Objects.requireNonNull(digest));
    }

    public ArtifactCoordinate asPom() {
        return new ArtifactCoordinate(gav, "pom", "");
    }

    @Override
    public int compareTo(ArtifactCoordinate other) {
        return notation().compareTo(other.notation());
    }

    private static String component(String value, String name, boolean emptyAllowed) {
        Objects.requireNonNull(value, name + " must not be null");
        if (emptyAllowed && value.isEmpty()) return value;
        ContractChecks.token(value, name);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException(name + " contains unsupported characters");
        }
        return value;
    }
}
