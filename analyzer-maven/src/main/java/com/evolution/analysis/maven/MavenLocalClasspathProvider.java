package com.evolution.analysis.maven;

import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.buildmodel.BuildModelResult.Dependency;
import com.evolution.analysis.buildmodel.BuildModelResult.EffectivePom;
import com.evolution.analysis.buildmodel.BuildModelResult.ModuleModel;
import com.evolution.analysis.classpath.*;
import com.evolution.analysis.classpath.ExactClasspathResult.*;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.ModuleIdentity;
import java.nio.file.Path;
import java.util.*;

/**
 * Passive exact-classpath provider over one caller-selected standard Maven2-layout cache.
 * It has no settings reader, remote repositories, transport, lifecycle or plugin execution.
 */
public final class MavenLocalClasspathProvider implements ClasspathProvider {
    public static final VersionedIdentifier VERSION =
            new VersionedIdentifier("classpath.maven-local", "3.9.16-m3.4");

    private final Path selectedCacheRoot;

    public MavenLocalClasspathProvider(Path selectedCacheRoot) {
        this.selectedCacheRoot = Objects.requireNonNull(selectedCacheRoot);
    }

    @Override
    public ExactClasspathResult resolve(ClasspathResolutionRequest request) {
        Objects.requireNonNull(request);
        var reactor = ReactorIndex.create(request.buildModel());
        List<Manifest> manifests = new ArrayList<>();
        LocalArtifactCache cache;
        try {
            cache = new LocalArtifactCache(selectedCacheRoot, request.buildRequest(), request.policy());
        } catch (LocalArtifactCache.RootFailure failure) {
            for (ModuleModel module : request.buildModel().modules()) {
                for (SourcePlanModel.Kind kind : SourcePlanModel.Kind.values()) {
                    manifests.add(Manifest.create(
                            module.module().identity(),
                            kind,
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(new Problem(
                                    failure.reason,
                                    ".",
                                    Requirement.PASSIVE_LOCAL_CACHE,
                                    List.of()))));
                }
            }
            return ExactClasspathResult.create(
                    request.identity(), VERSION, manifests, List.of(), List.of(), ExactClasspathResult.LIMITATIONS);
        }

        var modelReader = new MavenArtifactModelReader(cache, request.buildRequest().policy());
        for (ModuleModel module : request.buildModel().modules()) {
            for (SourcePlanModel.Kind kind : SourcePlanModel.Kind.values()) {
                if (module.effectivePom().isEmpty()) {
                    manifests.add(Manifest.create(
                            module.module().identity(),
                            kind,
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(new Problem(
                                    Reason.BUILD_MODEL_INCOMPLETE,
                                    module.pomPath(),
                                    Requirement.COMPLETE_BUILD_MODEL,
                                    moduleEvidence(module)))));
                    continue;
                }
                manifests.add(new GraphResolution(
                        module,
                        kind,
                        request.policy(),
                        reactor,
                        cache,
                        modelReader).resolve());
            }
        }
        return ExactClasspathResult.create(
                request.identity(),
                VERSION,
                manifests,
                cache.artifacts(),
                cache.attempts(),
                ExactClasspathResult.LIMITATIONS);
    }

    private static final class GraphResolution {
        private final ModuleModel rootModule;
        private final EffectivePom rootPom;
        private final SourcePlanModel.Kind sourceSet;
        private final ClasspathResolutionPolicy policy;
        private final ReactorIndex reactor;
        private final LocalArtifactCache cache;
        private final MavenArtifactModelReader modelReader;
        private final List<SelectedNode> roots = new ArrayList<>();
        private final Map<String, SelectedNode> winners = new HashMap<>();
        private final Set<String> coordinates = new HashSet<>();
        private final List<Decision> decisions = new ArrayList<>();
        private final List<Problem> problems = new ArrayList<>();

        private GraphResolution(
                ModuleModel rootModule,
                SourcePlanModel.Kind sourceSet,
                ClasspathResolutionPolicy policy,
                ReactorIndex reactor,
                LocalArtifactCache cache,
                MavenArtifactModelReader modelReader) {
            this.rootModule = rootModule;
            this.rootPom = rootModule.effectivePom().orElseThrow();
            this.sourceSet = sourceSet;
            this.policy = policy;
            this.reactor = reactor;
            this.cache = cache;
            this.modelReader = modelReader;
        }

        private Manifest resolve() {
            ArrayDeque<Pending> pending = new ArrayDeque<>();
            for (Dependency dependency : rootPom.dependencies()) {
                pending.addLast(new Pending(
                        dependency,
                        null,
                        1,
                        null,
                        Set.of(),
                        Set.of(),
                        moduleEvidence(rootModule)));
            }
            while (!pending.isEmpty()) process(pending.removeFirst(), pending);
            for (SelectedNode root : roots) normalizeScopes(root, null);

            List<Entry> entries = new ArrayList<>();
            List<ReactorEntry> reactorEntries = new ArrayList<>();
            for (SelectedNode root : roots) flatten(root, entries, reactorEntries);
            return Manifest.create(
                    rootModule.module().identity(),
                    sourceSet,
                    entries,
                    reactorEntries,
                    decisions.stream().distinct().toList(),
                    problems.stream().distinct().toList());
        }

        private void process(Pending next, ArrayDeque<Pending> pending) {
            ManagedDependency managed = manage(next.dependency, next.depth);
            Dependency dependency = managed.dependency;
            DependencyScope declaredScope = scope(dependency.scope(), subject(dependency));
            if (declaredScope == null) return;
            DependencyScope effectiveScope = next.parentScope == null
                    ? declaredScope
                    : derive(next.parentScope, declaredScope);
            if (effectiveScope == null || !included(effectiveScope)) return;
            if (next.depth > 1 && dependency.optional()) return;
            if (excluded(next.inheritedExclusions, dependency.groupId(), dependency.artifactId())) return;
            if (next.depth > policy.maxDepth()) {
                problem(Reason.DEPENDENCY_DEPTH_LIMIT, subject(dependency), Requirement.PASSIVE_LOCAL_CACHE, next.evidence);
                return;
            }

            ArtifactCoordinate coordinate;
            try {
                coordinate = artifact(dependency);
            } catch (IllegalArgumentException exception) {
                problem(Reason.NON_EXACT_VERSION, subject(dependency), Requirement.EXACT_VERSION, next.evidence);
                return;
            }
            if (next.path.contains(coordinate.notation())) {
                problem(Reason.DEPENDENCY_CYCLE, coordinate.notation(), Requirement.ARTIFACT_POM, next.evidence);
                return;
            }
            if (!coordinates.contains(coordinate.notation()) && coordinates.size() >= policy.maxCoordinates()) {
                problem(Reason.COORDINATE_LIMIT, coordinate.notation(), Requirement.PASSIVE_LOCAL_CACHE, next.evidence);
                return;
            }
            coordinates.add(coordinate.notation());

            SelectedNode winner = winners.get(coordinate.conflictKey());
            if (winner != null) {
                DecisionReason reason = winner.coordinate.equals(coordinate)
                        ? DecisionReason.DUPLICATE_PATH
                        : winner.depth < next.depth
                                ? DecisionReason.NEAREST
                                : DecisionReason.FIRST_DECLARATION;
                decisions.add(new Decision(reason, winner.coordinate, coordinate, winner.depth, next.depth));
                if (winner.depth > 1) {
                    winner.mergedScope = broader(winner.mergedScope, effectiveScope);
                    winner.scopeMerged = true;
                }
                return;
            }

            var node = new SelectedNode(coordinate, declaredScope, effectiveScope, next.depth, next.evidence);
            winners.put(coordinate.conflictKey(), node);
            if (next.parent == null) roots.add(node); else next.parent.children.add(node);
            if (managed.original.isPresent()) {
                decisions.add(new Decision(
                        DecisionReason.DEPENDENCY_MANAGEMENT,
                        coordinate,
                        managed.original.orElseThrow(),
                        next.depth,
                        next.depth));
            }

            if (effectiveScope == DependencyScope.SYSTEM) return;
            ModuleModel reactorModule = reactor.module(coordinate.gav()).orElse(null);
            List<Dependency> children = List.of();
            List<Evidence> descriptorEvidence = next.evidence;
            if (reactor.duplicate(coordinate.gav())) {
                node.withheld = true;
                problem(Reason.DUPLICATE_REACTOR_COORDINATE,
                        coordinate.gav().notation(), Requirement.COMPLETE_BUILD_MODEL, next.evidence);
            } else if (reactorModule != null) {
                node.reactorModule = reactorModule;
                EffectivePom pom = reactorModule.effectivePom().orElse(null);
                if (pom == null) {
                    problem(Reason.BUILD_MODEL_INCOMPLETE,
                            reactorModule.pomPath(), Requirement.COMPLETE_BUILD_MODEL, moduleEvidence(reactorModule));
                } else {
                    children = pom.dependencies();
                    descriptorEvidence = moduleEvidence(reactorModule);
                    node.evidence = merge(node.evidence, descriptorEvidence);
                }
            } else {
                MavenArtifactModelReader.DescriptorOutcome outcome = modelReader.descriptor(coordinate.gav());
                outcome.issues().forEach(issue -> problems.add(issue.problem()));
                if (outcome.issues().stream().anyMatch(issue -> issue.reason() == Reason.RELOCATION_UNSUPPORTED)) {
                    node.withheld = true;
                }
                if (outcome.descriptor().isPresent()) {
                    var descriptor = outcome.descriptor().orElseThrow();
                    children = descriptor.dependencies();
                    descriptorEvidence = descriptor.evidence();
                    node.evidence = merge(node.evidence, descriptorEvidence);
                }
            }

            Set<String> childExclusions = new HashSet<>(next.inheritedExclusions);
            childExclusions.addAll(dependency.exclusions());
            Set<String> childPath = new HashSet<>(next.path);
            childPath.add(coordinate.notation());
            for (Dependency child : children) {
                pending.addLast(new Pending(
                        child,
                        node,
                        next.depth + 1,
                        effectiveScope,
                        Set.copyOf(childExclusions),
                        Set.copyOf(childPath),
                        descriptorEvidence));
            }
        }

        private void normalizeScopes(SelectedNode node, DependencyScope parentScope) {
            DependencyScope pathScope = parentScope == null
                    ? node.declaredScope
                    : derive(parentScope, node.declaredScope);
            if (pathScope != null) {
                node.scope = node.scopeMerged ? broader(pathScope, node.mergedScope) : pathScope;
                for (SelectedNode child : node.children) normalizeScopes(child, node.scope);
            }
        }

        private void flatten(SelectedNode node, List<Entry> entries, List<ReactorEntry> reactorEntries) {
            if (node.withheld) {
                // An ambiguous reactor coordinate must never fall back to a cache copy.
            } else if (node.scope == DependencyScope.SYSTEM) {
                problem(Reason.SYSTEM_PATH_UNAVAILABLE,
                        node.coordinate.notation(), Requirement.PASSIVE_LOCAL_CACHE, node.evidence);
            } else if (node.coordinate.extension().equals("pom")) {
                // POM dependencies contribute their transitive descriptor only, not a binary entry.
            } else if (node.reactorModule != null) {
                SourcePlanModel.Kind targetKind = node.coordinate.classifier().equals("tests")
                        ? SourcePlanModel.Kind.TEST
                        : SourcePlanModel.Kind.MAIN;
                Optional<String> output = node.reactorModule.effectivePom().stream()
                        .flatMap(pom -> pom.sourcePlan().sourceSets().stream())
                        .filter(plan -> plan.kind() == targetKind)
                        .findFirst()
                        .flatMap(plan -> plan.outputDirectory().value());
                reactorEntries.add(new ReactorEntry(
                        node.coordinate.gav(),
                        node.reactorModule.module().identity(),
                        targetKind,
                        node.scope,
                        output,
                        node.depth == 1,
                        node.depth,
                        node.evidence));
                problem(Reason.REACTOR_OUTPUT_NOT_ACQUIRED,
                        node.coordinate.notation(), Requirement.REACTOR_OUTPUT, node.evidence);
            } else if (!node.coordinate.extension().equals("jar")) {
                problem(Reason.UNSUPPORTED_ARTIFACT_TYPE,
                        node.coordinate.notation(), Requirement.DEPENDENCY_ARTIFACT, node.evidence);
            } else {
                try {
                    LocalArtifactCache.Material material = cache.jar(node.coordinate);
                    byte[] bytes = material.bytes();
                    if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') {
                        problem(Reason.INVALID_JAR,
                                node.coordinate.notation(), Requirement.DEPENDENCY_ARTIFACT,
                                merge(node.evidence, List.of(material.record().evidence())));
                    } else {
                        List<Evidence> evidence = merge(node.evidence, List.of(material.record().evidence()));
                        entries.add(new Entry(
                                node.coordinate,
                                node.scope,
                                node.coordinate.classpathEntry(material.record().contentDigest()),
                                node.coordinate.repositoryPath(),
                                node.depth == 1,
                                node.depth,
                                evidence));
                    }
                } catch (LocalArtifactCache.Failure failure) {
                    problems.add(new Problem(failure.reason, failure.subject, failure.requirement, failure.evidence));
                }
            }
            for (SelectedNode child : node.children) flatten(child, entries, reactorEntries);
        }

        private ManagedDependency manage(Dependency dependency, int depth) {
            if (depth == 1) return new ManagedDependency(dependency, Optional.empty());
            String key;
            try {
                key = artifactKey(dependency);
            } catch (IllegalArgumentException exception) {
                return new ManagedDependency(dependency, Optional.empty());
            }
            for (Dependency managed : rootPom.managedDependencies()) {
                try {
                    if (!artifactKey(managed).equals(key) || managed.scope().equals("import")) continue;
                    String version = managed.version().isEmpty() ? dependency.version() : managed.version();
                    String scope = managed.scope().isEmpty() ? dependency.scope() : managed.scope();
                    List<String> exclusions = new ArrayList<>(dependency.exclusions());
                    exclusions.addAll(managed.exclusions());
                    Dependency selected = new Dependency(
                            dependency.groupId(),
                            dependency.artifactId(),
                            version,
                            dependency.type(),
                            dependency.classifier(),
                            scope,
                            dependency.optional() || managed.optional(),
                            exclusions.stream().distinct().sorted().toList());
                    if (selected.version().equals(dependency.version()) && selected.scope().equals(dependency.scope())
                            && selected.optional() == dependency.optional()
                            && selected.exclusions().equals(dependency.exclusions())) {
                        return new ManagedDependency(selected, Optional.empty());
                    }
                    Optional<ArtifactCoordinate> original;
                    try {
                        original = Optional.of(artifact(dependency));
                    } catch (IllegalArgumentException exception) {
                        original = Optional.empty();
                    }
                    return new ManagedDependency(selected, original);
                } catch (IllegalArgumentException ignored) {
                    // An unusable management row cannot match an exact dependency key.
                }
            }
            return new ManagedDependency(dependency, Optional.empty());
        }

        private DependencyScope scope(String value, String subject) {
            try {
                return switch (value.toLowerCase(Locale.ROOT)) {
                    case "", "compile" -> DependencyScope.COMPILE;
                    case "provided" -> DependencyScope.PROVIDED;
                    case "runtime" -> DependencyScope.RUNTIME;
                    case "test" -> DependencyScope.TEST;
                    case "system" -> DependencyScope.SYSTEM;
                    default -> throw new IllegalArgumentException();
                };
            } catch (IllegalArgumentException exception) {
                problem(Reason.UNSUPPORTED_SCOPE, subject, Requirement.COMPLETE_BUILD_MODEL, List.of());
                return null;
            }
        }

        private boolean included(DependencyScope scope) {
            return sourceSet == SourcePlanModel.Kind.TEST
                    || scope == DependencyScope.COMPILE
                    || scope == DependencyScope.PROVIDED
                    || scope == DependencyScope.SYSTEM;
        }

        private void problem(Reason reason, String subject, Requirement requirement, List<Evidence> evidence) {
            problems.add(new Problem(reason, subject, requirement, evidence));
        }

        private boolean excluded(Set<String> exclusions, String group, String artifact) {
            return exclusions.stream().anyMatch(exclusion -> {
                int delimiter = exclusion.indexOf(':');
                if (delimiter < 0) return false;
                String excludedGroup = exclusion.substring(0, delimiter);
                String excludedArtifact = exclusion.substring(delimiter + 1);
                return (excludedGroup.equals("*") || excludedGroup.equals(group))
                        && (excludedArtifact.equals("*") || excludedArtifact.equals(artifact));
            });
        }

        private static DependencyScope derive(DependencyScope parent, DependencyScope child) {
            if (child == DependencyScope.TEST || child == DependencyScope.PROVIDED || child == DependencyScope.SYSTEM) {
                return null;
            }
            return switch (parent) {
                case COMPILE -> child == DependencyScope.RUNTIME ? DependencyScope.RUNTIME : DependencyScope.COMPILE;
                case PROVIDED -> DependencyScope.PROVIDED;
                case RUNTIME -> DependencyScope.RUNTIME;
                case TEST -> DependencyScope.TEST;
                case SYSTEM -> null;
            };
        }

        private static DependencyScope broader(DependencyScope left, DependencyScope right) {
            List<DependencyScope> order = List.of(
                    DependencyScope.COMPILE,
                    DependencyScope.RUNTIME,
                    DependencyScope.PROVIDED,
                    DependencyScope.TEST,
                    DependencyScope.SYSTEM);
            return order.indexOf(left) <= order.indexOf(right) ? left : right;
        }

        private static ArtifactCoordinate artifact(Dependency dependency) {
            if (dependency.version().equalsIgnoreCase("LATEST")
                    || dependency.version().equalsIgnoreCase("RELEASE")) {
                throw new IllegalArgumentException("Dynamic Maven versions are not exact inputs");
            }
            MavenCoordinate gav = new MavenCoordinate(
                    dependency.groupId(), dependency.artifactId(), dependency.version());
            String type = dependency.type().isEmpty() ? "jar" : dependency.type();
            String classifier = dependency.classifier();
            return switch (type) {
                case "jar", "bundle", "maven-plugin", "ejb" -> new ArtifactCoordinate(gav, "jar", classifier);
                case "test-jar" -> new ArtifactCoordinate(gav, "jar", classifier.isEmpty() ? "tests" : classifier);
                case "ejb-client" -> new ArtifactCoordinate(gav, "jar", classifier.isEmpty() ? "client" : classifier);
                case "pom" -> new ArtifactCoordinate(gav, "pom", classifier);
                default -> new ArtifactCoordinate(gav, type, classifier);
            };
        }

        private static String artifactKey(Dependency dependency) {
            String version = dependency.version().isEmpty() ? "0" : dependency.version();
            return artifact(new Dependency(
                    dependency.groupId(), dependency.artifactId(), version, dependency.type(),
                    dependency.classifier(), dependency.scope(), dependency.optional(), dependency.exclusions()))
                    .conflictKey();
        }

        private static String subject(Dependency dependency) {
            return dependency.groupId() + ":" + dependency.artifactId();
        }

        private static List<Evidence> merge(List<Evidence> left, List<Evidence> right) {
            return java.util.stream.Stream.concat(left.stream(), right.stream())
                    .distinct()
                    .sorted(Comparator.comparing(Evidence::logicalId))
                    .toList();
        }

        private record Pending(
                Dependency dependency,
                SelectedNode parent,
                int depth,
                DependencyScope parentScope,
                Set<String> inheritedExclusions,
                Set<String> path,
                List<Evidence> evidence) {}

        private record ManagedDependency(Dependency dependency, Optional<ArtifactCoordinate> original) {}

        private static final class SelectedNode {
            private final ArtifactCoordinate coordinate;
            private final DependencyScope declaredScope;
            private DependencyScope scope;
            private DependencyScope mergedScope;
            private boolean scopeMerged;
            private final int depth;
            private List<Evidence> evidence;
            private final List<SelectedNode> children = new ArrayList<>();
            private ModuleModel reactorModule;
            private boolean withheld;

            private SelectedNode(
                    ArtifactCoordinate coordinate,
                    DependencyScope declaredScope,
                    DependencyScope scope,
                    int depth,
                    List<Evidence> evidence) {
                this.coordinate = coordinate;
                this.declaredScope = declaredScope;
                this.scope = scope;
                this.mergedScope = scope;
                this.depth = depth;
                this.evidence = List.copyOf(evidence);
            }
        }
    }

    private record ReactorIndex(
            Map<MavenCoordinate, ModuleModel> modules,
            Set<MavenCoordinate> duplicates) {
        private static ReactorIndex create(BuildModelResult result) {
            Map<MavenCoordinate, ModuleModel> modules = new HashMap<>();
            Set<MavenCoordinate> duplicates = new HashSet<>();
            for (ModuleModel module : result.modules()) {
                module.effectivePom().ifPresent(pom -> {
                    if (modules.putIfAbsent(pom.coordinate(), module) != null) duplicates.add(pom.coordinate());
                });
            }
            return new ReactorIndex(Map.copyOf(modules), Set.copyOf(duplicates));
        }

        private Optional<ModuleModel> module(MavenCoordinate coordinate) {
            return Optional.ofNullable(modules.get(coordinate));
        }

        private boolean duplicate(MavenCoordinate coordinate) {
            return duplicates.contains(coordinate);
        }
    }

    private static List<Evidence> moduleEvidence(ModuleModel module) {
        return module.effectivePom().stream()
                .flatMap(pom -> pom.inputs().stream())
                .map(input -> new Evidence(input.logicalId(), input.digest()))
                .distinct()
                .sorted(Comparator.comparing(Evidence::logicalId))
                .toList();
    }
}
