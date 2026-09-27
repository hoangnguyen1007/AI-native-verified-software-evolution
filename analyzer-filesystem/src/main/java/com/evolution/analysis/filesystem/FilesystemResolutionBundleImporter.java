package com.evolution.analysis.filesystem;

import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.contract.analysis.ClasspathEntry;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.source.SourceClassification;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.ingestion.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Imports one captured exact classpath within an explicit local selection; never evaluates a target build. */
public final class FilesystemResolutionBundleImporter {
    public static final String SCHEMA = "filesystem-resolution-bundle-v2";
    public static final String RESOLVED_GRAPH_SCHEMA = "filesystem-resolved-graph-v1";
    public static final VersionedIdentifier PROVIDER =
            new VersionedIdentifier("filesystem.resolution-bundle", "m4uv2.1-v2");

    public enum Trust { DECLARATION_ONLY, TRUSTED_CAPTURE }
    public enum Status { EXACT, PARTIAL }
    public enum Reason {
        UNTRUSTED_CAPTURE, WRONG_SOURCE_SET, PLATFORM_RELEASE_MISMATCH,
        CLASSPATH_ORDER_MISMATCH, MISSING_DECLARED_DEPENDENCY, DUPLICATE_LOGICAL_ENTRY,
        OUTSIDE_SELECTION, NON_REGULAR_ARTIFACT, FILE_UNAVAILABLE, FILE_CHANGED,
        DIGEST_MISMATCH, FILE_LIMIT, TOTAL_LIMIT, ARCHIVE_ENTRY_LIMIT,
        ARCHIVE_EXPANSION_LIMIT, UNSAFE_ARCHIVE_ENTRY, DUPLICATE_ARCHIVE_ENTRY,
        MULTI_RELEASE_UNSELECTED, NESTED_ARCHIVE_UNSELECTED, CORRUPT_ARCHIVE, UNSUPPORTED_PLATFORM_FORMAT,
        UNVERIFIED_REACTOR_OUTPUT, RESOURCE_LIMIT,
        RESOLVED_CONTEXT_MISMATCH, RESOLVED_ORDER_MISMATCH,
        RESOLVED_GRAPH_LIMIT,
        RESOLVED_SELECTION_MISMATCH, SELECTED_ARTIFACT_MISMATCH,
        UNBOUND_DECLARED_DEPENDENCY, UNDECLARED_DIRECT_SELECTOR,
        INVALID_RESOLVED_EDGE, UNREACHABLE_RESOLVED_ARTIFACT
    }
    public record Problem(Reason reason, String subject) implements Comparable<Problem> {
        public Problem { Objects.requireNonNull(reason); subject = ContractChecks.text(subject, "bundle problem subject"); }
        @Override public int compareTo(Problem other) {
            int c = reason.compareTo(other.reason); return c != 0 ? c : subject.compareTo(other.subject);
        }
    }
    public record Policy(int maxArtifacts, long maxArtifactBytes, long maxTotalBytes,
                         int maxArchiveEntries, long maxExpandedBytes,
                         Set<VersionedIdentifier> trustedProducers) {
        public Policy {
            if (maxArtifacts < 1 || maxArtifactBytes < 1 || maxTotalBytes < 1
                    || maxArchiveEntries < 1 || maxExpandedBytes < 1)
                throw new IllegalArgumentException("Positive artifact import bounds required");
            trustedProducers = Set.copyOf(Objects.requireNonNull(trustedProducers));
        }
    }
    /** A producer assertion is recorded separately from byte integrity and source/build freshness. */
    public record Manifest(ContentDigest identity, ContentDigest sourceIdentity, ContentDigest buildIdentity,
                           UniversalSourceIngestion.SourceSet sourceSet, List<ClasspathEntry> orderedEntries,
                           VersionedIdentifier producer, Trust trust) {
        public Manifest {
            Objects.requireNonNull(identity); Objects.requireNonNull(sourceIdentity); Objects.requireNonNull(buildIdentity);
            Objects.requireNonNull(sourceSet); orderedEntries = List.copyOf(orderedEntries);
            Objects.requireNonNull(producer); Objects.requireNonNull(trust);
            if (!identity.equals(derive(sourceIdentity, buildIdentity, sourceSet, orderedEntries, producer, trust)))
                throw new IllegalArgumentException("Captured classpath manifest identity mismatch");
        }
        public static Manifest create(ContentDigest sourceIdentity, ContentDigest buildIdentity,
                UniversalSourceIngestion.SourceSet sourceSet, List<ClasspathEntry> orderedEntries,
                VersionedIdentifier producer, Trust trust) {
            return new Manifest(derive(sourceIdentity, buildIdentity, sourceSet, orderedEntries, producer, trust),
                    sourceIdentity, buildIdentity, sourceSet, orderedEntries, producer, trust);
        }
        private static ContentDigest derive(ContentDigest source, ContentDigest build,
                UniversalSourceIngestion.SourceSet set, List<ClasspathEntry> entries,
                VersionedIdentifier producer, Trust trust) {
            return IngestionEvidence.digest(List.of(SCHEMA, "captured-classpath-v1", source, build, set,
                    entries, producer, trust));
        }
    }
    /** Producer-asserted selected configuration and artifact graph; bytes are checked by the artifact importer. */
    public record ResolvedNode(ClasspathEntry entry, String selectedCoordinate,
                               String variant, List<String> directSelectors) {
        public ResolvedNode {
            Objects.requireNonNull(entry);
            selectedCoordinate = ContractChecks.text(selectedCoordinate, "selected component");
            variant = ContractChecks.text(variant, "selected variant");
            directSelectors = List.copyOf(Objects.requireNonNull(directSelectors));
            if (directSelectors.stream().anyMatch(String::isBlank)
                    || directSelectors.stream().distinct().count() != directSelectors.size())
                throw new IllegalArgumentException("Direct selectors must be unique and nonblank");
        }
    }
    public record ResolvedEdge(String fromLogicalName, String toLogicalName) {
        public ResolvedEdge {
            fromLogicalName = ContractChecks.text(fromLogicalName, "resolved edge source");
            toLogicalName = ContractChecks.text(toLogicalName, "resolved edge target");
        }
    }
    public record ResolvedGraph(ContentDigest identity, ContentDigest sourceIdentity,
                                ContentDigest buildIdentity, UniversalSourceIngestion.SourceSet sourceSet,
                                String selectedConfiguration, List<ResolvedNode> orderedNodes,
                                List<ResolvedEdge> edges, VersionedIdentifier producer, Trust trust) {
        public ResolvedGraph {
            Objects.requireNonNull(identity); Objects.requireNonNull(sourceIdentity);
            Objects.requireNonNull(buildIdentity); Objects.requireNonNull(sourceSet);
            selectedConfiguration = ContractChecks.text(selectedConfiguration, "selected configuration");
            orderedNodes = List.copyOf(orderedNodes);
            edges = List.copyOf(edges);
            Objects.requireNonNull(producer); Objects.requireNonNull(trust);
            if (!identity.equals(derive(sourceIdentity, buildIdentity, sourceSet,
                    selectedConfiguration, orderedNodes, edges, producer, trust)))
                throw new IllegalArgumentException("Resolved graph identity mismatch");
        }
        public static ResolvedGraph create(ContentDigest sourceIdentity, ContentDigest buildIdentity,
                UniversalSourceIngestion.SourceSet sourceSet, String selectedConfiguration,
                List<ResolvedNode> orderedNodes, List<ResolvedEdge> edges,
                VersionedIdentifier producer, Trust trust) {
            return new ResolvedGraph(derive(sourceIdentity, buildIdentity, sourceSet,
                    selectedConfiguration, orderedNodes, edges, producer, trust),
                    sourceIdentity, buildIdentity, sourceSet, selectedConfiguration,
                    orderedNodes, edges, producer, trust);
        }
        private static ContentDigest derive(ContentDigest source, ContentDigest build,
                UniversalSourceIngestion.SourceSet set, String configuration,
                List<ResolvedNode> nodes, List<ResolvedEdge> edges,
                VersionedIdentifier producer, Trust trust) {
            return IngestionEvidence.digest(List.of(RESOLVED_GRAPH_SCHEMA, source, build, set,
                    configuration, nodes, edges, producer, trust));
        }
    }
    public record Receipt(String logicalId, ContentDigest digest, long bytes, int entries) {
        public Receipt { logicalId = ContractChecks.text(logicalId, "artifact receipt"); Objects.requireNonNull(digest); }
    }
    /** Selected library resource bytes retain both container and entry identity; no source span is invented. */
    public record LibraryResource(String artifactLogicalId, ContentDigest artifactDigest,
                                  String entryName, ContentDigest entryDigest, byte[] bytes) {
        public LibraryResource {
            artifactLogicalId = ContractChecks.text(artifactLogicalId, "resource artifact");
            Objects.requireNonNull(artifactDigest);
            entryName = ContractChecks.text(entryName, "resource entry");
            Objects.requireNonNull(entryDigest);
            bytes = Objects.requireNonNull(bytes).clone();
            if (!entryDigest.equals(ContentDigest.sha256(bytes)))
                throw new IllegalArgumentException("Resource entry digest mismatch");
        }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
    /** Effective classpath entry chosen for one Java release, with original archive and physical entry provenance. */
    public record SelectedClassEntry(String artifactLogicalId, ContentDigest artifactDigest,
                                     String logicalClassEntry, String physicalEntry,
                                     int release, ContentDigest entryDigest) {
        public SelectedClassEntry {
            artifactLogicalId = ContractChecks.text(artifactLogicalId, "class artifact");
            Objects.requireNonNull(artifactDigest);
            logicalClassEntry = ContractChecks.text(logicalClassEntry, "logical class entry");
            physicalEntry = ContractChecks.text(physicalEntry, "physical class entry");
            if (release < 0) throw new IllegalArgumentException("Negative selected release");
            Objects.requireNonNull(entryDigest);
        }
    }
    /** Candidates follow captured classpath order; this record does not choose a semantic winner. */
    public record ClassCollision(String logicalClassEntry, List<SelectedClassEntry> orderedCandidates) {
        public ClassCollision {
            logicalClassEntry = ContractChecks.text(logicalClassEntry, "colliding class entry");
            orderedCandidates = List.copyOf(orderedCandidates);
            String key = logicalClassEntry;
            if (orderedCandidates.size() < 2 || orderedCandidates.stream()
                    .anyMatch(c -> !c.logicalClassEntry().equals(key)))
                throw new IllegalArgumentException("Class collision needs matching ordered candidates");
        }
    }
    public record Result(ContentDigest identity, Status status, Optional<UniversalSourceIngestion.Resolution> resolution,
                         List<Receipt> receipts, List<LibraryResource> resources,
                         List<SelectedClassEntry> selectedClasses, List<ClassCollision> classCollisions,
                         List<Problem> problems) {
        public Result {
            Objects.requireNonNull(identity); Objects.requireNonNull(status); Objects.requireNonNull(resolution);
            receipts = List.copyOf(receipts);
            resources = List.copyOf(resources);
            selectedClasses = List.copyOf(selectedClasses);
            classCollisions = List.copyOf(classCollisions);
            problems = problems.stream().distinct().sorted().toList();
            if ((status == Status.EXACT) != resolution.isPresent() || (status == Status.EXACT) != problems.isEmpty())
                throw new IllegalArgumentException("Exact bundle must have a resolution and no problems");
        }
    }

    public Result importBundle(Path selectedRoot, RepositoryInputs inputs, UniversalBuildModel build,
            UniversalSourceIngestion.SourceSet sourceSet, Manifest manifest, PlatformInput platform,
            List<BinaryInput> binaries, Policy policy) {
        Objects.requireNonNull(selectedRoot); Objects.requireNonNull(inputs); Objects.requireNonNull(build);
        Objects.requireNonNull(sourceSet); Objects.requireNonNull(manifest); Objects.requireNonNull(platform);
        Objects.requireNonNull(binaries); Objects.requireNonNull(policy);
        if (!build.repositoryInputIdentity().equals(inputs.identity())
                || !manifest.sourceIdentity().equals(inputs.identity())
                || !manifest.buildIdentity().equals(build.identity())
                || !manifest.sourceSet().equals(sourceSet))
            throw new IllegalArgumentException("Bundle refers to a different source/build context");
        var module = build.modules().stream().filter(m -> m.descriptor().identity().equals(sourceSet.module()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown bundle module"));
        var plan = module.sourcePlan().sourceSets().stream().filter(s -> s.kind() == sourceSet.kind()
                && s.name().equals(sourceSet.name()))
                .findFirst().orElseThrow();
        var problems = new ArrayList<Problem>(); var receipts = new ArrayList<Receipt>();
        var resources = new ArrayList<LibraryResource>();
        var selectedClasses = new ArrayList<SelectedClassEntry>();
        if (manifest.trust() != Trust.TRUSTED_CAPTURE
                || !policy.trustedProducers().contains(manifest.producer()))
            problems.add(new Problem(Reason.UNTRUSTED_CAPTURE, sourceSet.toString()));
        if (plan.platformRelease().value().isEmpty()
                || !plan.platformRelease().value().orElseThrow().equals(Integer.toString(platform.release())))
            problems.add(new Problem(Reason.PLATFORM_RELEASE_MISMATCH, sourceSet.toString()));
        var supplied = binaries.stream().map(BinaryInput::entry).toList();
        if (!manifest.orderedEntries().equals(supplied))
            problems.add(new Problem(Reason.CLASSPATH_ORDER_MISMATCH, sourceSet.toString()));
        var names = new HashSet<String>();
        for (var entry : manifest.orderedEntries()) if (!names.add(entry.logicalName()))
            problems.add(new Problem(Reason.DUPLICATE_LOGICAL_ENTRY, entry.logicalName()));
        for (var dependency : module.dependencies()) {
            boolean relevant = compileDependency(dependency.configuration(), plan.semanticRole());
            if (relevant && dependency.coordinate().isPresent()
                    && manifest.orderedEntries().stream().noneMatch(e -> e.logicalName().equals(
                            dependency.coordinate().orElseThrow().notation() + "@jar")))
                problems.add(new Problem(Reason.MISSING_DECLARED_DEPENDENCY, dependency.notation()));
        }
        Path root = selectedRoot.toAbsolutePath().normalize();
        try {
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                    || !root.equals(root.toRealPath()))
                throw new IllegalArgumentException("Selected artifact root must be a real directory");
        } catch (IOException | SecurityException failure) {
            throw new IllegalArgumentException("Selected artifact root is unavailable");
        }
        var budget = new Budget(policy);
        if (platform.artifacts().size() + binaries.size() > policy.maxArtifacts())
            problems.add(new Problem(Reason.FILE_LIMIT, sourceSet.toString()));
        else {
            for (var artifact : platform.artifacts()) {
                if (artifact.format() == PlatformInput.Format.RUNTIME_MODULES) {
                    problems.add(new Problem(Reason.UNSUPPORTED_PLATFORM_FORMAT, artifact.logicalName()));
                    continue;
                }
                inspect(root, artifact.path(), artifact.logicalName(), artifact.contentDigest(), policy,
                        budget, receipts, resources, selectedClasses, problems, false, platform.release());
            }
            for (var binary : binaries) {
                if (binary.reactorModule().isPresent()) {
                    problems.add(new Problem(Reason.UNVERIFIED_REACTOR_OUTPUT, binary.entry().logicalName()));
                    continue;
                }
                inspect(root, binary.path(), binary.entry().logicalName(), binary.entry().contentDigest(),
                        policy, budget, receipts, resources, selectedClasses, problems, true, platform.release());
            }
        }
        var byClass = new TreeMap<String, List<SelectedClassEntry>>();
        for (var selected : selectedClasses)
            byClass.computeIfAbsent(selected.logicalClassEntry(), ignored -> new ArrayList<>()).add(selected);
        var classCollisions = byClass.entrySet().stream().filter(e -> e.getValue().size() > 1)
                .map(e -> new ClassCollision(e.getKey(), e.getValue())).toList();
        var sorted = problems.stream().distinct().sorted().toList();
        ContentDigest identity = IngestionEvidence.digest(List.of(SCHEMA, PROVIDER, inputs.identity(),
                build.identity(), manifest.identity(), platform.entry(), supplied, policy, receipts,
                resources.stream().map(r -> List.of(r.artifactLogicalId(), r.artifactDigest(),
                        r.entryName(), r.entryDigest())).toList(), selectedClasses, classCollisions, sorted));
        return new Result(identity, sorted.isEmpty() ? Status.EXACT : Status.PARTIAL,
                sorted.isEmpty() ? Optional.of(new UniversalSourceIngestion.Resolution(platform, binaries, identity))
                        : Optional.empty(), receipts, resources, selectedClasses, classCollisions, sorted);
    }

    /** Graph-aware import binds the classpath receipt to selected components and transitive reachability. */
    public Result importBundle(Path selectedRoot, RepositoryInputs inputs, UniversalBuildModel build,
            UniversalSourceIngestion.SourceSet sourceSet, Manifest manifest, PlatformInput platform,
            List<BinaryInput> binaries, Policy policy, ResolvedGraph graph) {
        Objects.requireNonNull(graph);
        var base = importBundle(selectedRoot, inputs, build, sourceSet, manifest, platform, binaries, policy);
        var problems = new ArrayList<>(base.problems());
        validateResolvedGraph(inputs, build, sourceSet, manifest, policy, graph, problems);
        var sorted = problems.stream().distinct().sorted().toList();
        var identity = IngestionEvidence.digest(List.of(RESOLVED_GRAPH_SCHEMA,
                base.identity(), graph.identity(), sorted));
        return new Result(identity, sorted.isEmpty() ? Status.EXACT : Status.PARTIAL,
                sorted.isEmpty() ? Optional.of(new UniversalSourceIngestion.Resolution(platform,
                        binaries, identity)) : Optional.empty(), base.receipts(),
                base.resources(), base.selectedClasses(), base.classCollisions(), sorted);
    }

    private static void validateResolvedGraph(RepositoryInputs inputs, UniversalBuildModel build,
            UniversalSourceIngestion.SourceSet set, Manifest manifest, Policy policy,
            ResolvedGraph graph, List<Problem> problems) {
        if (!graph.sourceIdentity().equals(inputs.identity())
                || !graph.buildIdentity().equals(build.identity())
                || !graph.sourceSet().equals(set)
                || !graph.producer().equals(manifest.producer())) {
            problems.add(new Problem(Reason.RESOLVED_CONTEXT_MISMATCH, set.toString()));
            return;
        }
        if (graph.trust() != Trust.TRUSTED_CAPTURE
                || !policy.trustedProducers().contains(graph.producer()))
            problems.add(new Problem(Reason.UNTRUSTED_CAPTURE, graph.producer().toString()));
        if (graph.orderedNodes().size() > policy.maxArtifacts()
                || graph.edges().size() > policy.maxArchiveEntries()) {
            problems.add(new Problem(Reason.RESOLVED_GRAPH_LIMIT, set.toString()));
            return;
        }
        var module = build.modules().stream().filter(m -> m.descriptor().identity().equals(set.module()))
                .findFirst().orElseThrow();
        String expectedConfiguration = set.kind() == SourcePlanModel.Kind.MAIN ? "compileClasspath"
                : set.kind() == SourcePlanModel.Kind.TEST ? "testCompileClasspath"
                : set.name() + "CompileClasspath";
        if ((module.tool() == UniversalBuildModel.Tool.GRADLE_GROOVY
                || module.tool() == UniversalBuildModel.Tool.GRADLE_KOTLIN)
                && !graph.selectedConfiguration().equals(expectedConfiguration))
            problems.add(new Problem(Reason.RESOLVED_CONTEXT_MISMATCH,
                    graph.selectedConfiguration()));
        var entries = graph.orderedNodes().stream().map(ResolvedNode::entry).toList();
        if (!entries.equals(manifest.orderedEntries())
                || entries.stream().map(ClasspathEntry::logicalName).distinct().count() != entries.size())
            problems.add(new Problem(Reason.RESOLVED_ORDER_MISMATCH, set.toString()));
        var nodes = new TreeMap<String, ResolvedNode>();
        graph.orderedNodes().forEach(n -> nodes.putIfAbsent(n.entry().logicalName(), n));
        var roots = new TreeSet<String>();
        var declaredSelectors = new TreeSet<String>();
        var role = module.sourcePlan().sourceSets().stream()
                .filter(p -> p.name().equals(set.name())).findFirst().orElseThrow().semanticRole();
        for (var dependency : module.dependencies()) {
            if (!compileDependency(dependency.configuration(), role)) continue;
            declaredSelectors.add(dependency.notation());
            if (dependency.projectPath().isPresent()) {
                // A project declaration cannot be satisfied by relabeling an external JAR.
                problems.add(new Problem(Reason.UNBOUND_DECLARED_DEPENDENCY, dependency.notation()));
                continue;
            }
            var matches = graph.orderedNodes().stream().filter(n ->
                    n.directSelectors().contains(dependency.notation())).toList();
            if (matches.size() != 1) {
                problems.add(new Problem(Reason.UNBOUND_DECLARED_DEPENDENCY, dependency.notation()));
                continue;
            }
            var node = matches.getFirst();
            roots.add(node.entry().logicalName());
            if (dependency.coordinate().isPresent()
                    && !node.selectedCoordinate().equals(dependency.coordinate().orElseThrow().notation()))
                problems.add(new Problem(Reason.RESOLVED_SELECTION_MISMATCH, dependency.notation()));
        }
        for (var node : graph.orderedNodes()) {
            if (node.entry().kind() == com.evolution.analysis.contract.analysis.ClasspathEntryKind.DEPENDENCY
                    && !node.entry().logicalName().startsWith(node.selectedCoordinate() + "@"))
                problems.add(new Problem(Reason.SELECTED_ARTIFACT_MISMATCH,
                        node.entry().logicalName()));
            for (String selector : node.directSelectors()) if (!declaredSelectors.contains(selector))
                problems.add(new Problem(Reason.UNDECLARED_DIRECT_SELECTOR, selector));
        }
        var outgoing = new TreeMap<String, List<String>>();
        for (var edge : graph.edges()) {
            if (!nodes.containsKey(edge.fromLogicalName()) || !nodes.containsKey(edge.toLogicalName()))
                problems.add(new Problem(Reason.INVALID_RESOLVED_EDGE,
                        edge.fromLogicalName() + "->" + edge.toLogicalName()));
            else outgoing.computeIfAbsent(edge.fromLogicalName(), ignored -> new ArrayList<>())
                    .add(edge.toLogicalName());
        }
        var reached = new HashSet<String>();
        var queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (reached.add(current)) outgoing.getOrDefault(current, List.of()).forEach(queue::addLast);
        }
        for (String logicalName : nodes.keySet()) if (!reached.contains(logicalName))
            problems.add(new Problem(Reason.UNREACHABLE_RESOLVED_ARTIFACT, logicalName));
    }

    private static boolean compileDependency(String configuration, SourceClassification role) {
        if (Set.of("implementation", "api", "compileOnly", "compileOnlyApi", "compile")
                .contains(configuration)) return true;
        return role == SourceClassification.TEST
                && Set.of("testImplementation", "testCompileOnly", "testCompile").contains(configuration);
    }

    private static void inspect(Path root, Path file, String logicalId, ContentDigest expected,
            Policy policy, Budget budget, List<Receipt> receipts, List<LibraryResource> resources,
            List<SelectedClassEntry> selectedClasses, List<Problem> problems,
            boolean collectResources, int targetRelease) {
        Path absolute = file.toAbsolutePath().normalize();
        BasicFileAttributes before;
        try {
            if (Files.isSymbolicLink(absolute) || !absolute.startsWith(root)
                    || !absolute.equals(absolute.toRealPath())) {
                problems.add(new Problem(Reason.OUTSIDE_SELECTION, logicalId)); return;
            }
            before = Files.readAttributes(absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile()) {
                problems.add(new Problem(Reason.NON_REGULAR_ARTIFACT, logicalId)); return;
            }
        } catch (IOException | SecurityException failure) {
            problems.add(new Problem(Reason.FILE_UNAVAILABLE, logicalId)); return;
        }
        if (before.size() > policy.maxArtifactBytes()) {
            problems.add(new Problem(Reason.FILE_LIMIT, logicalId)); return;
        }
        if (before.size() > policy.maxTotalBytes() - budget.bytes) {
            problems.add(new Problem(Reason.TOTAL_LIMIT, logicalId)); return;
        }
        long bytes = 0; MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        try (SeekableByteChannel channel = Files.newByteChannel(absolute,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (channel.read(buffer) >= 0) {
                buffer.flip(); int count = buffer.remaining(); bytes += count;
                if (bytes > policy.maxArtifactBytes() || bytes > policy.maxTotalBytes() - budget.bytes) {
                    problems.add(new Problem(Reason.FILE_LIMIT, logicalId)); return;
                }
                digest.update(buffer); buffer.clear();
            }
        } catch (IOException | SecurityException failure) {
            problems.add(new Problem(Reason.FILE_UNAVAILABLE, logicalId)); return;
        }
        budget.bytes += bytes;
        ContentDigest actual = new ContentDigest("sha256:" + HexFormat.of().formatHex(digest.digest()));
        if (!actual.equals(expected)) {
            problems.add(new Problem(Reason.DIGEST_MISMATCH, logicalId)); return;
        }
        var selected = new ArrayList<ResourceBytes>();
        var classes = new ArrayList<SelectedClassBytes>();
        int entries = inspectArchive(absolute, logicalId, policy, budget, problems,
                collectResources ? selected : null, collectResources ? classes : null, targetRelease);
        try {
            var after = Files.readAttributes(absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (Files.isSymbolicLink(absolute) || !absolute.equals(absolute.toRealPath())
                    || !after.isRegularFile() || before.size() != after.size()
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || before.fileKey() != null && after.fileKey() != null
                    && !before.fileKey().equals(after.fileKey())) {
                problems.add(new Problem(Reason.FILE_CHANGED, logicalId)); return;
            }
        } catch (IOException | SecurityException failure) {
            problems.add(new Problem(Reason.FILE_CHANGED, logicalId)); return;
        }
        if (entries >= 0) {
            receipts.add(new Receipt(logicalId, actual, bytes, entries));
            for (var item : selected)
                resources.add(new LibraryResource(logicalId, actual, item.name(),
                        ContentDigest.sha256(item.bytes()), item.bytes()));
            for (var item : classes)
                selectedClasses.add(new SelectedClassEntry(logicalId, actual, item.logicalName(),
                        item.physicalName(), item.release(), item.digest()));
        }
    }

    private record ResourceBytes(String name, byte[] bytes) {}
    private record SelectedClassBytes(String logicalName, String physicalName,
                                      int release, ContentDigest digest) {}
    private static int inspectArchive(Path file, String logicalId, Policy policy, Budget budget,
            List<Problem> problems, List<ResourceBytes> selected,
            List<SelectedClassBytes> selectedClasses, int targetRelease) {
        int count = 0; Set<String> names = new HashSet<>(), foldedNames = new HashSet<>();
        try (ZipFile archive = new ZipFile(file.toFile())) {
            boolean multiRelease = false;
            ZipEntry manifestEntry = null;
            var preflight = archive.entries();
            int physicalCount = 0;
            while (preflight.hasMoreElements()) {
                var candidate = preflight.nextElement();
                if (++physicalCount > policy.maxArchiveEntries()) {
                    problems.add(new Problem(Reason.ARCHIVE_ENTRY_LIMIT, logicalId)); return -1;
                }
                if (candidate.getName().equalsIgnoreCase("META-INF/MANIFEST.MF")) {
                    if (manifestEntry != null) {
                        problems.add(new Problem(Reason.DUPLICATE_ARCHIVE_ENTRY, logicalId)); return -1;
                    }
                    manifestEntry = candidate;
                }
            }
            if (manifestEntry != null) {
                try (InputStream stream = archive.getInputStream(manifestEntry)) {
                    byte[] bytes = stream.readNBytes(65_537);
                    if (bytes.length > 65_536) {
                        problems.add(new Problem(Reason.RESOURCE_LIMIT, logicalId + "/META-INF/MANIFEST.MF"));
                        return -1;
                    }
                    multiRelease = Boolean.parseBoolean(new java.util.jar.Manifest(new ByteArrayInputStream(bytes))
                            .getMainAttributes().getValue("Multi-Release"));
                }
            }
            var classView = new TreeMap<String, SelectedClassBytes>();
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement(); count++;
                if (count > policy.maxArchiveEntries()) {
                    problems.add(new Problem(Reason.ARCHIVE_ENTRY_LIMIT, logicalId)); return -1;
                }
                String name = entry.getName();
                if (name.startsWith("/") || name.contains("\\") || name.contains(":")
                        || Arrays.stream(name.split("/", -1)).anyMatch(p -> p.equals("..") || p.equals(".")) ) {
                    problems.add(new Problem(Reason.UNSAFE_ARCHIVE_ENTRY, logicalId)); return -1;
                }
                if (!names.add(name) || !foldedNames.add(name.toLowerCase(Locale.ROOT))) {
                    problems.add(new Problem(Reason.DUPLICATE_ARCHIVE_ENTRY, logicalId)); return -1;
                }
                if (name.startsWith("BOOT-INF/lib/") && name.endsWith(".jar")
                        || name.startsWith("WEB-INF/lib/") && name.endsWith(".jar")) {
                    problems.add(new Problem(Reason.NESTED_ARCHIVE_UNSELECTED, logicalId)); return -1;
                }
                if (entry.isDirectory()) continue;
                boolean wanted = selected != null && Set.of("META-INF/spring.factories",
                        "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
                        .contains(name);
                ByteArrayOutputStream captured = wanted ? new ByteArrayOutputStream() : null;
                String logicalClass = name;
                int release = 0;
                if (name.startsWith("META-INF/versions/")) {
                    logicalClass = null;
                    if (multiRelease) {
                        String remainder = name.substring("META-INF/versions/".length());
                        int separator = remainder.indexOf('/');
                        if (separator > 0) try {
                            int version = Integer.parseInt(remainder.substring(0, separator));
                            String selectedName = remainder.substring(separator + 1);
                            if (version >= 9 && version <= targetRelease
                                    && !selectedName.startsWith("META-INF/")) {
                                release = version; logicalClass = selectedName;
                            }
                        } catch (NumberFormatException ignored) { /* Invalid versioned name has no selected view. */ }
                    }
                }
                if (logicalClass != null && !logicalClass.endsWith(".class")) logicalClass = null;
                MessageDigest classDigest = logicalClass != null && selectedClasses != null
                        ? MessageDigest.getInstance("SHA-256") : null;
                try (InputStream stream = archive.getInputStream(entry)) {
                    byte[] buffer = new byte[8192]; int read;
                    while ((read = stream.read(buffer)) >= 0) {
                        budget.expandedBytes += read;
                        if (budget.expandedBytes > policy.maxExpandedBytes()) {
                            problems.add(new Problem(Reason.ARCHIVE_EXPANSION_LIMIT, logicalId)); return -1;
                        }
                        if (wanted) {
                            if (read > 1_048_576 - captured.size()) {
                                problems.add(new Problem(Reason.RESOURCE_LIMIT, logicalId + "/" + name)); return -1;
                            }
                            captured.write(buffer, 0, read);
                        }
                        if (classDigest != null) classDigest.update(buffer, 0, read);
                    }
                }
                if (wanted) selected.add(new ResourceBytes(name, captured.toByteArray()));
                if (classDigest != null) {
                    var candidate = new SelectedClassBytes(logicalClass, name, release,
                            new ContentDigest("sha256:" + HexFormat.of().formatHex(classDigest.digest())));
                    var previous = classView.get(logicalClass);
                    if (previous == null || candidate.release() > previous.release())
                        classView.put(logicalClass, candidate);
                }
            }
            if (count == 0) { problems.add(new Problem(Reason.CORRUPT_ARCHIVE, logicalId)); return -1; }
            if (selectedClasses != null) selectedClasses.addAll(classView.values());
            return count;
        } catch (IOException | SecurityException | NoSuchAlgorithmException failure) {
            problems.add(new Problem(Reason.CORRUPT_ARCHIVE, logicalId)); return -1;
        }
    }

    private static final class Budget {
        long bytes, expandedBytes;
        Budget(Policy policy) { Objects.requireNonNull(policy); }
    }
}
