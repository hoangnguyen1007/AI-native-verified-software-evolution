package com.evolution.analysis.input;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.RepositorySnapshot;
import com.evolution.analysis.frontend.SourceInput;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Complete decoding ledger. Failed or unowned candidates remain in the denominator. */
public record SourceDecodingResult(
        ContentDigest identity,
        String schemaVersion,
        ContentDigest acquisitionIdentity,
        ContentDigest ownershipIdentity,
        ContentDigest buildModelIdentity,
        SourceDecodingPolicy policy,
        VersionedIdentifier provider,
        RepositorySnapshot snapshot,
        List<Outcome> outcomes,
        List<String> limitations) {
    public static final String SCHEMA = "source-decoding-result-v1";
    public static final VersionedIdentifier PROVIDER = new VersionedIdentifier("source.decoder", "m3.5");
    public static final List<String> LIMITATIONS = List.of(
            "Only the fixed portable charset catalog is supported; installed charset providers and heuristic detection are not consulted.",
            "Only exactly-owned acquired Java candidates become source documents; generated, overlapping and unowned candidates remain explicit outcomes.");

    public SourceDecodingResult {
        ContractChecks.notNull(identity, "source decoding identity");
        if (!SCHEMA.equals(schemaVersion)) throw new IllegalArgumentException("Unsupported source decoding schema");
        ContractChecks.notNull(acquisitionIdentity, "acquisition identity");
        ContractChecks.notNull(ownershipIdentity, "ownership identity");
        ContractChecks.notNull(buildModelIdentity, "build-model identity");
        ContractChecks.notNull(policy, "source decoding policy");
        ContractChecks.notNull(provider, "source decoding provider");
        ContractChecks.notNull(snapshot, "decoded snapshot");
        outcomes = ContractChecks.sortedDistinct(outcomes, Comparator.comparing(Outcome::path), "source decoding outcomes");
        limitations = ContractChecks.sortedStrings(limitations, "source decoding limitations");
        if (!identity.equals(derive(acquisitionIdentity, ownershipIdentity, buildModelIdentity, policy,
                provider, snapshot, outcomes, limitations))) {
            throw new IllegalArgumentException("Source decoding identity does not match inputs");
        }
    }

    static SourceDecodingResult create(
            ContentDigest acquisitionIdentity,
            ContentDigest ownershipIdentity,
            ContentDigest buildModelIdentity,
            SourceDecodingPolicy policy,
            RepositorySnapshot snapshot,
            List<Outcome> outcomes) {
        List<Outcome> sorted = outcomes.stream().sorted(Comparator.comparing(Outcome::path)).toList();
        List<String> limitations = LIMITATIONS.stream().sorted().toList();
        ContentDigest identity = derive(acquisitionIdentity, ownershipIdentity, buildModelIdentity,
                policy, PROVIDER, snapshot, sorted, limitations);
        return new SourceDecodingResult(identity, SCHEMA, acquisitionIdentity, ownershipIdentity,
                buildModelIdentity, policy, PROVIDER, snapshot, sorted, limitations);
    }

    private static ContentDigest derive(
            ContentDigest acquisition,
            ContentDigest ownership,
            ContentDigest buildModel,
            SourceDecodingPolicy policy,
            VersionedIdentifier provider,
            RepositorySnapshot snapshot,
            List<Outcome> outcomes,
            List<String> limitations) {
        List<OutcomeView> views = outcomes.stream().map(Outcome::view).toList();
        return ContentDigest.sha256Utf8(CanonicalJson.write(Map.of(
                "schema", SCHEMA,
                "acquisition", acquisition,
                "ownership", ownership,
                "buildModel", buildModel,
                "policy", policy,
                "provider", provider,
                "snapshot", snapshot.identity(),
                "documents", snapshot.documents(),
                "outcomes", views,
                "limitations", limitations)));
    }

    public boolean hasGaps() {
        return outcomes.stream().anyMatch(outcome -> outcome.status() != Status.DECODED);
    }

    public enum Status { DECODED, INVALID, WITHHELD }
    public enum Reason {
        UNOWNED_SOURCE, OVERLAPPING_OWNERSHIP, MISSING_ACQUIRED_BYTES, MISSING_SOURCE_PLAN,
        MISSING_ENCODING, INVALID_ENCODING_DECLARATION, UNSUPPORTED_ENCODING,
        BOM_ENCODING_MISMATCH, MALFORMED_BYTE_SEQUENCE
    }
    public enum Requirement { SOURCE_OWNERSHIP, ACQUIRED_SOURCE_BYTES, BUILD_ENCODING, ANALYSIS_ENCODING_POLICY }

    public record Problem(Reason reason, Requirement requirement) {
        public Problem {
            ContractChecks.notNull(reason, "source decoding reason");
            ContractChecks.notNull(requirement, "source decoding requirement");
        }
    }

    public record Outcome(
            String path,
            ContentDigest rawDigest,
            Status status,
            Optional<SourceInput> input,
            List<Problem> problems) {
        public Outcome {
            path = ContractChecks.repositoryRelativePath(path, "decoded source path");
            String checkedPath = path;
            ContractChecks.notNull(rawDigest, "raw source digest");
            ContractChecks.notNull(status, "source decoding status");
            ContractChecks.notNull(input, "decoded source input");
            problems = ContractChecks.sortedDistinct(problems, Comparator.comparing(CanonicalJson::write), "source decoding problems");
            if ((status == Status.DECODED) != input.isPresent() || (status == Status.DECODED) != problems.isEmpty()) {
                throw new IllegalArgumentException("Source decoding status does not match input/problems");
            }
            input.ifPresent(value -> {
                if (!value.document().path().equals(checkedPath) || !value.document().contentDigest().equals(rawDigest)) {
                    throw new IllegalArgumentException("Decoded input does not match its candidate");
                }
            });
        }

        private OutcomeView view() {
            return new OutcomeView(path, rawDigest, status,
                    input.map(SourceInput::document), input.map(SourceInput::decoding), problems);
        }
    }

    private record OutcomeView(
            String path,
            ContentDigest rawDigest,
            Status status,
            Optional<com.evolution.analysis.contract.source.SourceDocument> document,
            Optional<SourceInput.Decoding> decoding,
            List<Problem> problems) {}
}
