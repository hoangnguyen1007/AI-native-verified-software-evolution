package com.evolution.analysis.buildmodel;

import com.evolution.analysis.contract.common.ContractChecks;

/** Exact model coordinate. Ranges, unresolved expressions and repository paths are not coordinates. */
public record MavenCoordinate(String groupId, String artifactId, String version) {
    public MavenCoordinate {
        groupId = part(groupId);
        artifactId = part(artifactId);
        version = part(version);
    }

    private static String part(String value) {
        ContractChecks.text(value, "coordinate component");
        if (!value.matches("[A-Za-z0-9_][A-Za-z0-9_.+-]*")) {
            throw new IllegalArgumentException("Unsupported or non-exact Maven coordinate");
        }
        return value;
    }

    public String notation() { return groupId + ":" + artifactId + ":" + version; }
}
