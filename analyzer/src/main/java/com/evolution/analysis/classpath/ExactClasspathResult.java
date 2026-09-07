package com.evolution.analysis.classpath;

import com.evolution.analysis.buildmodel.MavenCoordinate;
import com.evolution.analysis.buildmodel.SourcePlanModel;
import com.evolution.analysis.contract.analysis.ClasspathEntry;
import com.evolution.analysis.contract.analysis.ClasspathEntryKind;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.ModuleIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.*;

/** Deterministic per-module/source-set classpaths and passive acquisition evidence. */
public record ExactClasspathResult(
        ContentDigest identity,
        String schemaVersion,
        ContentDigest requestIdentity,
        VersionedIdentifier provider,
        List<Manifest> manifests,
        List<ArtifactRecord> artifacts,
        List<Attempt> attempts,
        List<String> limitations) {
    public static final String SCHEMA = "exact-classpath-result-v1";
    public static final List<String> LIMITATIONS = List.of(
            "Only exact local Maven repository POM and JAR coordinates are acquired; no network, settings, transport or lifecycle is used.",
            "Version ranges, relocations, system paths and non-JAR classpath artifacts remain explicit unsupported inputs.",
            "Reactor dependencies retain module/output requirements; compiled reactor outputs are not invented or acquired.",
            "Archive structural and multi-release platform validation remains a semantic-frontend responsibility.");

    public ExactClasspathResult {
        ContractChecks.notNull(identity, "classpath result identity");
        if (!SCHEMA.equals(schemaVersion)) throw new IllegalArgumentException("Unsupported classpath result schema");
        ContractChecks.notNull(requestIdentity, "classpath request identity");
        ContractChecks.notNull(provider, "classpath provider");
        manifests = ContractChecks.sortedDistinct(manifests, ExactClasspathResult::compareManifests, "classpath manifests");
        artifacts = ContractChecks.sortedDistinct(
                artifacts, Comparator.comparing(CanonicalJson::write), "acquired artifacts");
        attempts = ContractChecks.sortedDistinct(
                attempts, Comparator.comparing(CanonicalJson::write), "artifact attempts");
        limitations = ContractChecks.sortedStrings(limitations, "classpath limitations");
        ContentDigest expected = deriveIdentity(schemaVersion, requestIdentity, provider, manifests, artifacts, attempts, limitations);
        if (!identity.equals(expected)) throw new IllegalArgumentException("Classpath result identity does not match inputs");
    }

    public static ExactClasspathResult create(
            ContentDigest requestIdentity,
            VersionedIdentifier provider,
            List<Manifest> manifests,
            List<ArtifactRecord> artifacts,
            List<Attempt> attempts,
            List<String> limitations) {
        List<Manifest> sortedManifests = ContractChecks.sortedDistinct(
                manifests, ExactClasspathResult::compareManifests, "classpath manifests");
        List<ArtifactRecord> sortedArtifacts = ContractChecks.sortedDistinct(
                artifacts, Comparator.comparing(CanonicalJson::write), "acquired artifacts");
        List<Attempt> sortedAttempts = ContractChecks.sortedDistinct(
                attempts, Comparator.comparing(CanonicalJson::write), "artifact attempts");
        List<String> sortedLimitations = ContractChecks.sortedStrings(limitations, "classpath limitations");
        ContentDigest identity = deriveIdentity(
                SCHEMA, requestIdentity, provider, sortedManifests, sortedArtifacts, sortedAttempts, sortedLimitations);
        return new ExactClasspathResult(identity, SCHEMA, requestIdentity, provider,
                sortedManifests, sortedArtifacts, sortedAttempts, sortedLimitations);
    }

    public boolean hasGaps() {
        return manifests.stream().anyMatch(manifest -> manifest.status() == Status.PARTIAL);
    }

    private static ContentDigest deriveIdentity(
            String schema,
            ContentDigest request,
            VersionedIdentifier provider,
            List<Manifest> manifests,
            List<ArtifactRecord> artifacts,
            List<Attempt> attempts,
            List<String> limitations) {
        return ContentDigest.sha256Utf8(CanonicalJson.write(Map.of(
                "schema", schema,
                "request", request,
                "provider", provider,
                "manifests", manifests,
                "artifacts", artifacts,
                "attempts", attempts,
                "limitations", limitations)));
    }

    private static int compareManifests(Manifest left, Manifest right) {
        int module = left.module().compareTo(right.module());
        return module != 0 ? module : left.sourceSet().compareTo(right.sourceSet());
    }

    public enum DependencyScope { COMPILE, PROVIDED, RUNTIME, TEST, SYSTEM }
    public enum Status { COMPLETE, PARTIAL }
    public enum ArtifactKind { POM, JAR }
    public enum ArtifactOrigin { LOCAL_CACHE, SUPPLIED_BUILD_INPUT }
    public enum AttemptOutcome { SUCCEEDED, UNAVAILABLE, DENIED, FAILED, LIMIT_EXCEEDED }
    public enum DecisionReason { NEAREST, FIRST_DECLARATION, DUPLICATE_PATH, DEPENDENCY_MANAGEMENT }
    public enum Reason {
        CACHE_ROOT_NOT_FOUND,
        CACHE_ROOT_NOT_DIRECTORY,
        CACHE_ROOT_SYMBOLIC_LINK,
        CACHE_ROOT_READ_FAILED,
        PATH_OUTSIDE_CACHE,
        SYMBOLIC_LINK,
        NON_REGULAR_ARTIFACT,
        ARTIFACT_READ_FAILED,
        ARTIFACT_CHANGED_DURING_READ,
        COORDINATE_LIMIT,
        FILE_COUNT_LIMIT,
        POM_BYTE_LIMIT,
        POM_MODEL_READ_LIMIT,
        ARTIFACT_BYTE_LIMIT,
        TOTAL_BYTE_LIMIT,
        DEPENDENCY_DEPTH_LIMIT,
        DEPENDENCY_CYCLE,
        NON_EXACT_VERSION,
        MISSING_POM,
        MISSING_ARTIFACT,
        INVALID_POM,
        UNSAFE_POM,
        POM_MODEL_FAILED,
        POM_MODEL_WARNING,
        COORDINATE_MISMATCH,
        UNSUPPORTED_PROFILE_ACTIVATION,
        UNSUPPORTED_SCOPE,
        UNSUPPORTED_ARTIFACT_TYPE,
        SYSTEM_PATH_UNAVAILABLE,
        REACTOR_OUTPUT_NOT_ACQUIRED,
        DUPLICATE_REACTOR_COORDINATE,
        BUILD_MODEL_INCOMPLETE,
        RELOCATION_UNSUPPORTED,
        INVALID_JAR
    }
    public enum Requirement {
        PASSIVE_LOCAL_CACHE,
        ARTIFACT_POM,
        DEPENDENCY_ARTIFACT,
        EXACT_VERSION,
        ANALYSIS_CONFIGURATION,
        REACTOR_OUTPUT,
        COMPLETE_BUILD_MODEL
    }

    public record Evidence(String logicalId, ContentDigest digest) {
        public Evidence {
            ContractChecks.text(logicalId, "classpath evidence ID");
            ContractChecks.notNull(digest, "classpath evidence digest");
        }
    }

    public record Entry(
            ArtifactCoordinate coordinate,
            DependencyScope scope,
            ClasspathEntry classpathEntry,
            String repositoryPath,
            boolean direct,
            int depth,
            List<Evidence> evidence) {
        public Entry {
            ContractChecks.notNull(coordinate, "entry coordinate");
            ContractChecks.notNull(scope, "entry scope");
            ContractChecks.notNull(classpathEntry, "entry classpath value");
            repositoryPath = ContractChecks.repositoryRelativePath(repositoryPath, "artifact repository path");
            if (depth < 1 || direct != (depth == 1)) {
                throw new IllegalArgumentException("Classpath entry depth and direct flag disagree");
            }
            evidence = ContractChecks.sortedDistinct(evidence, Comparator.comparing(Evidence::logicalId), "entry evidence");
            if (classpathEntry.kind() != ClasspathEntryKind.DEPENDENCY
                    || !classpathEntry.logicalName().equals(coordinate.notation())
                    || !repositoryPath.equals(coordinate.repositoryPath())
                    || evidence.stream().noneMatch(item -> item.digest().equals(classpathEntry.contentDigest()))) {
                throw new IllegalArgumentException("Classpath entry does not match its coordinate and artifact evidence");
            }
        }
    }

    public record ReactorEntry(
            MavenCoordinate coordinate,
            ModuleIdentity module,
            SourcePlanModel.Kind targetSourceSet,
            DependencyScope scope,
            Optional<String> outputDirectory,
            boolean direct,
            int depth,
            List<Evidence> evidence) {
        public ReactorEntry {
            ContractChecks.notNull(coordinate, "reactor coordinate");
            ContractChecks.notNull(module, "reactor module");
            ContractChecks.notNull(targetSourceSet, "reactor target source set");
            ContractChecks.notNull(scope, "reactor scope");
            outputDirectory = ContractChecks.notNull(outputDirectory, "reactor output")
                    .map(ContractChecks::modulePath);
            if (depth < 1 || direct != (depth == 1)) {
                throw new IllegalArgumentException("Reactor entry depth and direct flag disagree");
            }
            evidence = ContractChecks.sortedDistinct(evidence, Comparator.comparing(Evidence::logicalId), "reactor evidence");
        }
    }

    public record Decision(
            DecisionReason reason,
            ArtifactCoordinate selected,
            ArtifactCoordinate omitted,
            int selectedDepth,
            int omittedDepth) {
        public Decision {
            ContractChecks.notNull(reason, "decision reason");
            ContractChecks.notNull(selected, "selected artifact");
            ContractChecks.notNull(omitted, "omitted artifact");
            if (!selected.conflictKey().equals(omitted.conflictKey()) || selectedDepth < 1 || omittedDepth < 1) {
                throw new IllegalArgumentException("Mediation decision must compare one valid conflict key");
            }
        }
    }

    public record Problem(Reason reason, String subject, Requirement requirement, List<Evidence> evidence) {
        public Problem {
            ContractChecks.notNull(reason, "classpath problem reason");
            ContractChecks.text(subject, "classpath problem subject");
            ContractChecks.notNull(requirement, "classpath problem requirement");
            evidence = ContractChecks.sortedDistinct(evidence, Comparator.comparing(Evidence::logicalId), "problem evidence");
        }
    }

    public record Manifest(
            ContentDigest identity,
            ModuleIdentity module,
            SourcePlanModel.Kind sourceSet,
            Status status,
            List<Entry> entries,
            List<ReactorEntry> reactorEntries,
            List<Decision> decisions,
            List<Problem> problems) {
        public Manifest {
            ContractChecks.notNull(identity, "manifest identity");
            ContractChecks.notNull(module, "manifest module");
            ContractChecks.notNull(sourceSet, "manifest source set");
            ContractChecks.notNull(status, "manifest status");
            entries = ContractChecks.distinctInOrder(entries, "classpath entries");
            reactorEntries = ContractChecks.distinctInOrder(reactorEntries, "reactor entries");
            decisions = ContractChecks.sortedDistinct(
                    decisions, Comparator.comparing(CanonicalJson::write), "mediation decisions");
            problems = ContractChecks.sortedDistinct(
                    problems, Comparator.comparing(CanonicalJson::write), "manifest problems");
            if (entries.stream().map(entry -> entry.coordinate().conflictKey()).distinct().count() != entries.size()) {
                throw new IllegalArgumentException("Manifest contains duplicate conflict keys");
            }
            Status expectedStatus = problems.isEmpty() ? Status.COMPLETE : Status.PARTIAL;
            if (status != expectedStatus) throw new IllegalArgumentException("Manifest status does not match problems");
            ContentDigest expected = manifestIdentity(module, sourceSet, status, entries, reactorEntries, decisions, problems);
            if (!identity.equals(expected)) throw new IllegalArgumentException("Manifest identity does not match inputs");
        }

        public static Manifest create(
                ModuleIdentity module,
                SourcePlanModel.Kind sourceSet,
                List<Entry> entries,
                List<ReactorEntry> reactorEntries,
                List<Decision> decisions,
                List<Problem> problems) {
            List<Entry> orderedEntries = ContractChecks.distinctInOrder(entries, "classpath entries");
            List<ReactorEntry> orderedReactor = ContractChecks.distinctInOrder(reactorEntries, "reactor entries");
            List<Decision> sortedDecisions = ContractChecks.sortedDistinct(
                    decisions, Comparator.comparing(CanonicalJson::write), "mediation decisions");
            List<Problem> sortedProblems = ContractChecks.sortedDistinct(
                    problems, Comparator.comparing(CanonicalJson::write), "manifest problems");
            Status status = sortedProblems.isEmpty() ? Status.COMPLETE : Status.PARTIAL;
            ContentDigest identity = manifestIdentity(
                    module, sourceSet, status, orderedEntries, orderedReactor, sortedDecisions, sortedProblems);
            return new Manifest(identity, module, sourceSet, status,
                    orderedEntries, orderedReactor, sortedDecisions, sortedProblems);
        }

        public List<ClasspathEntry> classpath() {
            return entries.stream().map(Entry::classpathEntry).toList();
        }

        private static ContentDigest manifestIdentity(
                ModuleIdentity module,
                SourcePlanModel.Kind sourceSet,
                Status status,
                List<Entry> entries,
                List<ReactorEntry> reactorEntries,
                List<Decision> decisions,
                List<Problem> problems) {
            return ContentDigest.sha256Utf8(CanonicalJson.write(Map.of(
                    "module", module,
                    "sourceSet", sourceSet,
                    "status", status,
                    "entries", entries,
                    "reactorEntries", reactorEntries,
                    "decisions", decisions,
                    "problems", problems)));
        }
    }

    public record ArtifactRecord(
            ArtifactCoordinate coordinate,
            ArtifactKind kind,
            ArtifactOrigin origin,
            Optional<String> repositoryPath,
            ContentDigest contentDigest,
            long size) {
        public ArtifactRecord {
            ContractChecks.notNull(coordinate, "acquired artifact coordinate");
            ContractChecks.notNull(kind, "acquired artifact kind");
            ContractChecks.notNull(origin, "acquired artifact origin");
            repositoryPath = ContractChecks.notNull(repositoryPath, "artifact repository path")
                    .map(path -> ContractChecks.repositoryRelativePath(path, "artifact repository path"));
            ContractChecks.notNull(contentDigest, "artifact content digest");
            if (size < 0) throw new IllegalArgumentException("Artifact size must not be negative");
            if ((origin == ArtifactOrigin.LOCAL_CACHE) != repositoryPath.isPresent()) {
                throw new IllegalArgumentException("Only local-cache evidence has a repository path");
            }
            if (kind == ArtifactKind.POM && !coordinate.extension().equals("pom")
                    || kind == ArtifactKind.JAR && !coordinate.extension().equals("jar")) {
                throw new IllegalArgumentException("Artifact kind and extension disagree");
            }
        }

        public Evidence evidence() {
            String id = origin == ArtifactOrigin.LOCAL_CACHE
                    ? "cache:" + repositoryPath.orElseThrow()
                    : "supplied:" + coordinate.gav().notation();
            return new Evidence(id, contentDigest);
        }
    }

    public record Attempt(
            ArtifactKind kind,
            ArtifactCoordinate coordinate,
            ArtifactOrigin origin,
            String subject,
            AttemptOutcome outcome,
            Optional<ContentDigest> contentDigest) {
        public Attempt {
            ContractChecks.notNull(kind, "artifact attempt kind");
            ContractChecks.notNull(coordinate, "artifact attempt coordinate");
            ContractChecks.notNull(origin, "artifact attempt origin");
            ContractChecks.text(subject, "artifact attempt subject");
            ContractChecks.notNull(outcome, "artifact attempt outcome");
            ContractChecks.notNull(contentDigest, "artifact attempt digest");
            if ((outcome == AttemptOutcome.SUCCEEDED) != contentDigest.isPresent()) {
                throw new IllegalArgumentException("Only successful artifact reads carry content evidence");
            }
        }
    }
}
