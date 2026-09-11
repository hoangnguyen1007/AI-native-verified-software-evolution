package com.evolution.analysis.dependency;

import com.evolution.analysis.classpath.ArtifactCoordinate;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Ordered exact artifacts plus digest expectations and an explicit bounded network policy. */
public record DependencyAcquisitionRequest(
        String schemaVersion,
        List<Requirement> requirements,
        DependencyAcquisitionPolicy policy) {
    public static final String SCHEMA = "dependency-acquisition-request-v1";

    public DependencyAcquisitionRequest(List<Requirement> requirements, DependencyAcquisitionPolicy policy) {
        this(SCHEMA, requirements, policy);
    }

    public DependencyAcquisitionRequest {
        if (!SCHEMA.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported dependency acquisition request schema");
        }
        requirements = ContractChecks.distinctInOrder(requirements, "dependency requirements");
        if (requirements.isEmpty()) {
            throw new IllegalArgumentException("At least one dependency artifact is required");
        }
        ContractChecks.notNull(policy, "dependency acquisition policy");
        if (requirements.size() > policy.maxCoordinates()) {
            throw new IllegalArgumentException("Dependency requirement count exceeds the configured limit");
        }
    }

    public static DependencyAcquisitionRequest of(
            List<ArtifactCoordinate> coordinates, DependencyAcquisitionPolicy policy) {
        return new DependencyAcquisitionRequest(coordinates.stream()
                .map(value -> new Requirement(value, Optional.empty())).toList(), policy);
    }

    public ContentDigest identity() {
        return ContentDigest.sha256Utf8(CanonicalJson.write(Map.of(
                "schema", schemaVersion,
                "requirements", requirements,
                "policy", policy.identity())));
    }

    public record Requirement(ArtifactCoordinate coordinate, Optional<ContentDigest> expectedDigest) {
        public Requirement {
            ContractChecks.notNull(coordinate, "dependency artifact coordinate");
            expectedDigest = ContractChecks.notNull(expectedDigest, "expected artifact digest");
            if (!coordinate.extension().equals("pom") && !coordinate.extension().equals("jar")) {
                throw new IllegalArgumentException("Dependency acquisition supports exact POM and JAR files only");
            }
        }
    }
}
