package com.evolution.analysis.acquisition;

import com.evolution.analysis.buildmodel.BuildModelPolicy;
import com.evolution.analysis.buildmodel.BuildModelRequest;
import com.evolution.analysis.buildmodel.PomInput;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.RepositorySnapshot;
import com.evolution.analysis.contract.source.SnapshotFile;
import java.util.*;
import java.util.stream.Collectors;

/** Bounded passive filesystem observations. A partial inventory never becomes a repository snapshot. */
public record RepositoryAcquisitionResult(
        String schemaVersion,
        RepositoryAcquisitionRequest request,
        VersionedIdentifier provider,
        Completion completion,
        Optional<RepositorySnapshot> snapshot,
        List<AcquiredFile> files,
        List<String> directories,
        List<Problem> problems,
        List<Attempt> attempts,
        List<String> limitations) {
    public static final String SCHEMA = "repository-acquisition-result-v1";
    public static final List<String> LIMITATIONS = List.of(
            "Filesystem acquisition does not decode source files or infer source-document facts.",
            "Symbolic links and non-regular entries are not followed; their contents require a separately approved provider policy.",
            "Explicitly excluded paths are outside this snapshot selection and remain visible in acquisition attempts.",
            "Concurrent filesystem mutation is checked around file reads; a stable snapshot is withheld when detected.");

    public RepositoryAcquisitionResult {
        if (!SCHEMA.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported repository acquisition result schema");
        }
        ContractChecks.notNull(request, "acquisition request");
        ContractChecks.notNull(provider, "acquisition provider");
        ContractChecks.notNull(completion, "acquisition completion");
        ContractChecks.notNull(snapshot, "snapshot");
        files = ContractChecks.sortedDistinct(files, Comparator.naturalOrder(), "acquired files");
        directories = ContractChecks.sortedDistinct(
                directories.stream().map(RepositoryAcquisitionResult::directory).toList(),
                Comparator.naturalOrder(), "acquired directories");
        problems = ContractChecks.sortedDistinct(
                problems, Comparator.comparing(CanonicalJson::write), "acquisition problems");
        attempts = ContractChecks.sortedDistinct(
                attempts, Comparator.comparing(CanonicalJson::write), "acquisition attempts");
        limitations = ContractChecks.sortedStrings(limitations, "acquisition limitations");
        if ((completion == Completion.COMPLETE) != snapshot.isPresent()) {
            throw new IllegalArgumentException("Only a complete acquisition may expose a repository snapshot");
        }
        if (completion == Completion.COMPLETE
                && problems.stream().anyMatch(problem -> problem.reason() != Reason.MISSING_ROOT_POM)) {
            throw new IllegalArgumentException("A complete acquisition cannot retain an incomplete-inventory problem");
        }
        if (completion != Completion.COMPLETE && problems.isEmpty()) {
            throw new IllegalArgumentException("An incomplete acquisition requires an explicit problem");
        }
        if (snapshot.isPresent()) {
            validateSnapshot(request, files, snapshot.orElseThrow());
        }
    }

    private static String directory(String path) {
        return ".".equals(path) ? path : ContractChecks.repositoryRelativePath(path, "acquired directory");
    }

    private static void validateSnapshot(
            RepositoryAcquisitionRequest request, List<AcquiredFile> files, RepositorySnapshot snapshot) {
        if (!request.repository().equals(snapshot.repository())
                || !request.revision().equals(snapshot.revision())
                || request.dirty() != snapshot.dirty()) {
            throw new IllegalArgumentException("Snapshot provenance does not match its acquisition request");
        }
        List<SnapshotFile> inventory = files.stream()
                .map(file -> new SnapshotFile(file.path(), file.contentDigest()))
                .toList();
        if (!inventory.equals(snapshot.files())) {
            throw new IllegalArgumentException("Snapshot inventory does not match acquired bytes");
        }
        if (!snapshot.documents().isEmpty()) {
            throw new IllegalArgumentException("Filesystem acquisition must not invent decoded source documents");
        }
    }

    public enum Completion { COMPLETE, PARTIAL, FAILED }
    public enum Reason {
        ROOT_NOT_FOUND, ROOT_NOT_DIRECTORY, ROOT_SYMBOLIC_LINK, ROOT_READ_FAILED, DIRECTORY_READ_FAILED,
        SYMBOLIC_LINK, NON_REGULAR_ENTRY, PATH_OUTSIDE_ROOT, INVALID_LOGICAL_PATH,
        ENTRY_COUNT_LIMIT, FILE_COUNT_LIMIT, DIRECTORY_COUNT_LIMIT, FILE_BYTE_LIMIT, TOTAL_BYTE_LIMIT, DEPTH_LIMIT,
        FILE_READ_FAILED, FILE_CHANGED_DURING_READ, DIRECTORY_CHANGED_DURING_READ, MISSING_ROOT_POM
    }
    public enum Requirement { REPOSITORY_SELECTION, FILESYSTEM_READ, ACQUISITION_POLICY, BUILD_MODEL_INPUT }
    public enum AttemptKind { ROOT, DIRECTORY, FILE, EXCLUSION }
    public enum Outcome { SUCCEEDED, FAILED, DENIED, UNAVAILABLE, EXCLUDED }

    /** Sanitized stable reason; raw host exceptions and local root locators are never retained. */
    public record Problem(Reason reason, String subject, Requirement requirement) {
        public Problem {
            ContractChecks.notNull(reason, "acquisition reason");
            ContractChecks.text(subject, "acquisition subject");
            ContractChecks.notNull(requirement, "acquisition requirement");
        }
    }

    public record Attempt(
            AttemptKind kind,
            String subject,
            Outcome outcome,
            Optional<ContentDigest> evidence) {
        public Attempt {
            ContractChecks.notNull(kind, "attempt kind");
            ContractChecks.text(subject, "attempt subject");
            ContractChecks.notNull(outcome, "attempt outcome");
            ContractChecks.notNull(evidence, "attempt evidence");
            if ((kind == AttemptKind.FILE && outcome == Outcome.SUCCEEDED) != evidence.isPresent()) {
                throw new IllegalArgumentException("Only successful file reads carry content evidence");
            }
        }
    }

    public Map<String, PomInput> workspacePoms() {
        TreeMap<String, PomInput> result = new TreeMap<>();
        files.stream()
                .filter(file -> file.path().equals("pom.xml") || file.path().endsWith("/pom.xml"))
                .forEach(file -> result.put(file.path(), new PomInput(file.bytes())));
        return Collections.unmodifiableMap(result);
    }

    /** A partial acquisition or missing entry POM cannot be promoted to build-model input. */
    public Optional<BuildModelRequest> buildModelRequest(BuildModelPolicy policy) {
        ContractChecks.notNull(policy, "build model policy");
        if (snapshot.isEmpty()) {
            return Optional.empty();
        }
        Map<String, PomInput> poms = workspacePoms();
        if (!poms.containsKey(request.rootPom())) {
            return Optional.empty();
        }
        return Optional.of(new BuildModelRequest(snapshot.orElseThrow(), request.rootPom(), poms, Map.of(), policy));
    }

    public boolean hasGaps() {
        return completion != Completion.COMPLETE || !problems.isEmpty();
    }

    /** Raw bytes are represented by their already-verified digests in result identity. */
    public ContentDigest identity() {
        Map<String, ContentDigest> inventory = files.stream().collect(Collectors.toMap(
                AcquiredFile::path,
                AcquiredFile::contentDigest,
                (left, right) -> left,
                TreeMap::new));
        return ContentDigest.sha256Utf8(CanonicalJson.write(Map.of(
                "schema", schemaVersion,
                "request", request.identity(),
                "provider", provider,
                "completion", completion,
                "snapshot", snapshot.map(RepositorySnapshot::identity),
                "files", inventory,
                "directories", directories,
                "problems", problems,
                "attempts", attempts,
                "limitations", limitations)));
    }
}
