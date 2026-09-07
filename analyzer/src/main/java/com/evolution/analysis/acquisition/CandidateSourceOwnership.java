package com.evolution.analysis.acquisition;

import com.evolution.analysis.buildmodel.BuildModelResult.PomEvidence;
import com.evolution.analysis.buildmodel.SourcePlanModel;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.ModuleIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.*;

/** Candidate Java file membership derived from acquired paths and declarative source plans. */
public record CandidateSourceOwnership(
        String schemaVersion,
        SnapshotIdentity snapshot,
        ContentDigest buildModel,
        VersionedIdentifier provider,
        List<Candidate> candidates,
        List<Problem> problems,
        List<String> limitations) {
    public static final String SCHEMA = "candidate-source-ownership-v1";
    public static final VersionedIdentifier PROVIDER =
            new VersionedIdentifier("workspace.source-ownership", "m3.3");
    public static final List<String> LIMITATIONS = List.of(
            "Ownership is candidate membership from declared roots, not decoded or semantically validated source input.",
            "Generated-source hints, resource filtering and plugin-computed roots remain unevaluated.");

    public CandidateSourceOwnership {
        if (!SCHEMA.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported candidate ownership schema");
        }
        ContractChecks.notNull(snapshot, "ownership snapshot");
        ContractChecks.notNull(buildModel, "build model identity");
        ContractChecks.notNull(provider, "ownership provider");
        candidates = ContractChecks.sortedDistinct(
                candidates, Comparator.comparing(Candidate::path), "source candidates");
        problems = ContractChecks.sortedDistinct(
                problems, Comparator.comparing(CanonicalJson::write), "ownership problems");
        limitations = ContractChecks.sortedStrings(limitations, "ownership limitations");
    }

    public enum Status { OWNED, OVERLAPPING, UNOWNED }
    public enum Reason { MISSING_SOURCE_ROOT, OVERLAPPING_FILE_OWNERSHIP, UNOWNED_SOURCE_FILE }
    public enum Requirement { FILESYSTEM_SOURCE_ROOT, BUILD_CONFIGURATION }

    public record Claim(
            ModuleIdentity module,
            SourcePlanModel.Kind sourceSet,
            String sourceRoot,
            List<PomEvidence> evidence) {
        public Claim {
            ContractChecks.notNull(module, "claim module");
            ContractChecks.notNull(sourceSet, "claim source set");
            sourceRoot = ContractChecks.modulePath(sourceRoot);
            evidence = ContractChecks.sortedDistinct(
                    evidence, Comparator.comparing(PomEvidence::logicalId), "claim evidence");
        }
    }

    public record Candidate(String path, ContentDigest contentDigest, Status status, List<Claim> claims) {
        public Candidate {
            path = ContractChecks.repositoryRelativePath(path, "candidate source path");
            ContractChecks.notNull(contentDigest, "candidate source digest");
            ContractChecks.notNull(status, "candidate ownership status");
            claims = ContractChecks.sortedDistinct(claims, CandidateSourceOwnership::compareClaims, "ownership claims");
            int expected = switch (status) {
                case UNOWNED -> 0;
                case OWNED -> 1;
                case OVERLAPPING -> 2;
            };
            if (status == Status.OVERLAPPING ? claims.size() < expected : claims.size() != expected) {
                throw new IllegalArgumentException("Candidate status does not match its ownership claims");
            }
        }
    }

    public record Problem(Reason reason, String subject, Requirement requirement, List<Claim> claims) {
        public Problem {
            ContractChecks.notNull(reason, "ownership reason");
            ContractChecks.text(subject, "ownership subject");
            ContractChecks.notNull(requirement, "ownership requirement");
            claims = ContractChecks.sortedDistinct(claims, CandidateSourceOwnership::compareClaims, "problem claims");
            int minimumClaims = switch (reason) {
                case UNOWNED_SOURCE_FILE -> 0;
                case MISSING_SOURCE_ROOT -> 1;
                case OVERLAPPING_FILE_OWNERSHIP -> 2;
            };
            if (reason == Reason.UNOWNED_SOURCE_FILE
                    ? !claims.isEmpty()
                    : claims.size() < minimumClaims) {
                throw new IllegalArgumentException("Ownership problem lacks its supporting claims");
            }
        }
    }

    public ContentDigest identity() {
        return ContentDigest.sha256Utf8(CanonicalJson.write(this));
    }

    static int compareClaims(Claim left, Claim right) {
        int module = left.module().compareTo(right.module());
        if (module != 0) return module;
        int sourceSet = left.sourceSet().compareTo(right.sourceSet());
        if (sourceSet != 0) return sourceSet;
        return left.sourceRoot().compareTo(right.sourceRoot());
    }
}
