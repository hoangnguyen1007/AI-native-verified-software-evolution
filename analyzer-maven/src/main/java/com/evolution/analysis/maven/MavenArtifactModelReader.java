package com.evolution.analysis.maven;

import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.buildmodel.BuildModelResult.Dependency;
import com.evolution.analysis.classpath.ExactClasspathResult.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.*;
import org.apache.maven.model.profile.DefaultProfileActivationContext;
import org.apache.maven.model.profile.DefaultProfileSelector;
import org.apache.maven.model.profile.ProfileSelector;
import org.apache.maven.model.profile.activation.PropertyProfileActivator;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;

/** Effective external artifact descriptors over already bounded cache/supplied POM bytes. */
final class MavenArtifactModelReader {
    private final LocalArtifactCache cache;
    private final BuildModelPolicy policy;
    private final Map<MavenCoordinate, DescriptorOutcome> descriptors = new HashMap<>();

    MavenArtifactModelReader(LocalArtifactCache cache, BuildModelPolicy policy) {
        this.cache = Objects.requireNonNull(cache);
        this.policy = Objects.requireNonNull(policy);
    }

    DescriptorOutcome descriptor(MavenCoordinate coordinate) {
        return descriptors.computeIfAbsent(coordinate, this::build);
    }

    private DescriptorOutcome build(MavenCoordinate expected) {
        var issues = new ArrayList<Issue>();
        var used = new TreeMap<String, Evidence>();
        var tolerantInBuild = new HashSet<MavenCoordinate>();
        Counter reads = new Counter();
        try {
            Source source = source(expected, issues, used, reads, tolerantInBuild);
            var properties = new Properties();
            properties.putAll(policy.userProperties());
            var request = new DefaultModelBuildingRequest()
                    .setModelSource(source)
                    // Use Maven's tolerant mode only for a securely parsed descriptor whose
                    // duplicate dependency rows were proven semantically equivalent below.
                    .setValidationLevel(tolerantInBuild.contains(expected)
                            ? ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL
                            : ModelBuildingRequest.VALIDATION_LEVEL_MAVEN_3_1)
                    .setProcessPlugins(false)
                    .setLocationTracking(true)
                    .setSystemProperties(new Properties())
                    .setUserProperties(properties)
                    .setActiveProfileIds(policy.activeProfiles())
                    .setInactiveProfileIds(policy.inactiveProfiles())
                    .setModelResolver(new CacheResolver(issues, used, reads, tolerantInBuild));
            ModelBuildingResult result;
            try {
                result = builder().build(request);
            } catch (ModelBuildingException strictFailure) {
                if (reads.exceeded
                        || request.getValidationLevel() == ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL
                        || tolerantInBuild.isEmpty()) {
                    throw strictFailure;
                }
                // A parent or imported BOM can reveal a validated equivalent duplicate only
                // after Maven has started a strict build. Retry once, with the same bounded
                // resolver/read counter, now that the hierarchy has justified tolerant mode.
                request.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);
                result = builder().build(request);
            }
            Model model = result.getEffectiveModel();
            MavenCoordinate actual;
            try {
                actual = new MavenCoordinate(model.getGroupId(), model.getArtifactId(), model.getVersion());
            } catch (IllegalArgumentException exception) {
                issues.add(issue(Reason.POM_MODEL_FAILED, expected.notation(), Requirement.ARTIFACT_POM, used));
                return new DescriptorOutcome(Optional.empty(), issues);
            }
            if (!expected.equals(actual)) {
                issues.add(issue(Reason.COORDINATE_MISMATCH, expected.notation(), Requirement.ARTIFACT_POM, used));
                return new DescriptorOutcome(Optional.empty(), issues);
            }
            if (model.getDistributionManagement() != null
                    && model.getDistributionManagement().getRelocation() != null) {
                issues.add(issue(Reason.RELOCATION_UNSUPPORTED, expected.notation(), Requirement.ARTIFACT_POM, used));
                return new DescriptorOutcome(Optional.empty(), issues);
            }
            if (!result.getProblems().isEmpty()) {
                issues.add(issue(Reason.POM_MODEL_WARNING, expected.notation(), Requirement.ARTIFACT_POM, used));
            }
            List<Dependency> dependencies = model.getDependencies().stream()
                    .map(MavenArtifactModelReader::dependency)
                    .toList();
            List<Dependency> managed = model.getDependencyManagement() == null
                    ? List.of()
                    : model.getDependencyManagement().getDependencies().stream()
                            .map(MavenArtifactModelReader::dependency)
                            .toList();
            return new DescriptorOutcome(
                    Optional.of(new Descriptor(dependencies, managed, List.copyOf(used.values()))), issues);
        } catch (ModelRejected rejected) {
            issues.add(rejected.issue);
        } catch (ModelBuildingException exception) {
            if (reads.exceeded) {
                issues.add(issue(Reason.POM_MODEL_READ_LIMIT,
                        expected.notation(), Requirement.ARTIFACT_POM, used));
            } else if (issues.isEmpty()) {
                issues.add(issue(Reason.POM_MODEL_FAILED, expected.notation(), Requirement.ARTIFACT_POM, used));
            }
        }
        return new DescriptorOutcome(Optional.empty(), issues);
    }

    private Source source(
            MavenCoordinate coordinate,
            List<Issue> issues,
            Map<String, Evidence> used,
            Counter reads,
            Set<MavenCoordinate> tolerantInBuild) throws ModelRejected {
        try {
            LocalArtifactCache.Material material = cache.pom(coordinate);
            if (validate(material, coordinate, issues, used)) {
                tolerantInBuild.add(coordinate);
            }
            return new Source(coordinate, material, reads);
        } catch (LocalArtifactCache.Failure failure) {
            throw new ModelRejected(new Issue(failure.reason, failure.subject, failure.requirement, failure.evidence));
        }
    }

    private boolean validate(
            LocalArtifactCache.Material material,
            MavenCoordinate coordinate,
            List<Issue> issues,
            Map<String, Evidence> used) throws ModelRejected {
        used.put(material.record().evidence().logicalId(), material.record().evidence());
        Model raw;
        try {
            raw = SecurePomReader.read(material.bytes());
        } catch (SecurePomReader.Rejected rejected) {
            Reason reason = rejected.reason == BuildModelResult.Reason.UNSAFE_XML
                    ? Reason.UNSAFE_POM
                    : Reason.INVALID_POM;
            throw new ModelRejected(issue(reason, coordinate.notation(), Requirement.ARTIFACT_POM, used));
        }
        for (var profile : raw.getProfiles()) {
            var activation = profile.getActivation();
            boolean explicit = policy.activeProfiles().contains(profile.getId())
                    || policy.inactiveProfiles().contains(profile.getId());
            if (!explicit && activation != null
                    && (activation.getJdk() != null || activation.getOs() != null || activation.getFile() != null)) {
                issues.add(issue(
                        Reason.UNSUPPORTED_PROFILE_ACTIVATION,
                        coordinate.notation(),
                        Requirement.ANALYSIS_CONFIGURATION,
                        used));
            }
        }
        boolean tolerant = validateDuplicateDependencies(raw.getDependencies(), coordinate, issues, used);
        if (raw.getDependencyManagement() != null) {
            tolerant |= validateDuplicateDependencies(
                    raw.getDependencyManagement().getDependencies(), coordinate, issues, used);
        }
        for (var profile : raw.getProfiles()) {
            tolerant |= validateDuplicateDependencies(profile.getDependencies(), coordinate, issues, used);
            if (profile.getDependencyManagement() != null) {
                tolerant |= validateDuplicateDependencies(
                        profile.getDependencyManagement().getDependencies(), coordinate, issues, used);
            }
        }
        return tolerant;
    }

    private static boolean validateDuplicateDependencies(
            List<org.apache.maven.model.Dependency> dependencies,
            MavenCoordinate coordinate,
            List<Issue> issues,
            Map<String, Evidence> used) throws ModelRejected {
        Map<String, DependencyView> declarations = new HashMap<>();
        boolean redundant = false;
        for (org.apache.maven.model.Dependency dependency : dependencies) {
            String key = dependencyKey(dependency);
            DependencyView view = DependencyView.from(dependency);
            DependencyView prior = declarations.putIfAbsent(key, view);
            if (prior == null) continue;
            if (!prior.equals(view)) {
                throw new ModelRejected(issue(
                        Reason.POM_MODEL_FAILED,
                        coordinate.notation(),
                        Requirement.ARTIFACT_POM,
                        used));
            }
            redundant = true;
        }
        if (redundant) {
            boolean recorded = issues.stream().anyMatch(issue ->
                    issue.reason() == Reason.POM_MODEL_WARNING
                            && issue.subject().equals(coordinate.notation())
                            && issue.requirement() == Requirement.ARTIFACT_POM);
            if (!recorded) {
                issues.add(issue(
                        Reason.POM_MODEL_WARNING,
                        coordinate.notation(),
                        Requirement.ARTIFACT_POM,
                        used));
            }
        }
        return redundant;
    }

    private static String dependencyKey(org.apache.maven.model.Dependency dependency) {
        return Objects.toString(dependency.getGroupId(), "") + ":"
                + Objects.toString(dependency.getArtifactId(), "") + ":"
                + Objects.toString(dependency.getType(), "jar") + ":"
                + Objects.toString(dependency.getClassifier(), "");
    }

    private record DependencyView(
            String groupId,
            String artifactId,
            String version,
            String type,
            String classifier,
            String scope,
            boolean optional,
            String systemPath,
            List<String> exclusions) {
        private static DependencyView from(org.apache.maven.model.Dependency dependency) {
            List<String> exclusions = dependency.getExclusions().stream()
                    .map(exclusion -> Objects.toString(exclusion.getGroupId(), "") + ":"
                            + Objects.toString(exclusion.getArtifactId(), ""))
                    .sorted().toList();
            return new DependencyView(
                    Objects.toString(dependency.getGroupId(), ""),
                    Objects.toString(dependency.getArtifactId(), ""),
                    Objects.toString(dependency.getVersion(), ""),
                    Objects.toString(dependency.getType(), "jar"),
                    Objects.toString(dependency.getClassifier(), ""),
                    Objects.toString(dependency.getScope(), "compile"),
                    dependency.isOptional(),
                    Objects.toString(dependency.getSystemPath(), ""),
                    exclusions);
        }
    }

    private DefaultModelBuilder builder() {
        return new DefaultModelBuilderFactory() {
            @Override
            protected ProfileSelector newProfileSelector() {
                var selector = new DefaultProfileSelector().addProfileActivator(new PropertyProfileActivator());
                return (profiles, activationContext, collector) -> {
                    var isolated = new DefaultProfileActivationContext()
                            .setActiveProfileIds(policy.activeProfiles())
                            .setInactiveProfileIds(policy.inactiveProfiles())
                            .setSystemProperties(Map.<String, String>of())
                            .setUserProperties(policy.userProperties());
                    var projectProperties = new Properties();
                    projectProperties.putAll(activationContext.getProjectProperties());
                    isolated.setProjectProperties(projectProperties);
                    return selector.getActiveProfiles(profiles, isolated, collector);
                };
            }
        }.newInstance();
    }

    private final class CacheResolver implements ModelResolver {
        private final List<Issue> issues;
        private final Map<String, Evidence> used;
        private final Counter reads;
        private final Set<MavenCoordinate> tolerantInBuild;

        private CacheResolver(List<Issue> issues, Map<String, Evidence> used, Counter reads,
                Set<MavenCoordinate> tolerantInBuild) {
            this.issues = issues;
            this.used = used;
            this.reads = reads;
            this.tolerantInBuild = tolerantInBuild;
        }

        private ModelSource resolve(String group, String artifact, String version)
                throws UnresolvableModelException {
            MavenCoordinate coordinate;
            try {
                coordinate = new MavenCoordinate(group, artifact, version);
                return source(coordinate, issues, used, reads, tolerantInBuild);
            } catch (IllegalArgumentException exception) {
                issues.add(issue(Reason.NON_EXACT_VERSION,
                        Objects.toString(group, "unknown") + ":" + Objects.toString(artifact, "unknown"),
                        Requirement.EXACT_VERSION,
                        used));
                throw new UnresolvableModelException("Non-exact model coordinate", group, artifact, version);
            } catch (ModelRejected rejected) {
                issues.add(rejected.issue);
                throw new UnresolvableModelException("Model input unavailable", group, artifact, version);
            }
        }

        @Override
        public ModelSource resolveModel(String group, String artifact, String version)
                throws UnresolvableModelException {
            return resolve(group, artifact, version);
        }

        @Override
        public ModelSource resolveModel(Parent parent) throws UnresolvableModelException {
            return resolve(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
        }

        @Override
        public ModelSource resolveModel(org.apache.maven.model.Dependency dependency)
                throws UnresolvableModelException {
            return resolve(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion());
        }

        @Override
        public void addRepository(Repository repository) {
            // Repository declarations are inert data. This provider has no transport.
        }

        @Override
        public void addRepository(Repository repository, boolean replace) {
            // Repository declarations are inert data. This provider has no transport.
        }

        @Override
        public ModelResolver newCopy() {
            return new CacheResolver(issues, used, reads, tolerantInBuild);
        }
    }

    private final class Source implements ModelSource2 {
        private final MavenCoordinate coordinate;
        private final LocalArtifactCache.Material material;
        private final Counter reads;

        private Source(MavenCoordinate coordinate, LocalArtifactCache.Material material, Counter reads) {
            this.coordinate = coordinate;
            this.material = material;
            this.reads = reads;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            if (++reads.value > policy.maxModelReads()) {
                reads.exceeded = true;
                throw new IOException("Model read limit exceeded");
            }
            return new ByteArrayInputStream(material.bytes());
        }

        @Override
        public String getLocation() {
            return "artifact:" + coordinate.notation();
        }

        @Override
        public URI getLocationURI() {
            try {
                return new URI("memory", null, "/artifact/" + coordinate.notation(), null);
            } catch (URISyntaxException exception) {
                throw new IllegalStateException("Invalid logical artifact URI", exception);
            }
        }

        @Override
        public ModelSource2 getRelatedSource(String relative) {
            // Published dependency descriptors resolve parents by exact GAV, never host-relative files.
            return null;
        }
    }

    private static Dependency dependency(org.apache.maven.model.Dependency input) {
        return new Dependency(
                input.getGroupId(),
                input.getArtifactId(),
                Objects.toString(input.getVersion(), ""),
                Objects.toString(input.getType(), "jar"),
                Objects.toString(input.getClassifier(), ""),
                Objects.toString(input.getScope(), "compile"),
                input.isOptional(),
                input.getExclusions().stream()
                        .map(exclusion -> exclusion.getGroupId() + ":" + exclusion.getArtifactId())
                        .toList());
    }

    private static Issue issue(
            Reason reason, String subject, Requirement requirement, Map<String, Evidence> evidence) {
        return new Issue(reason, subject, requirement, List.copyOf(evidence.values()));
    }

    record Descriptor(
            List<Dependency> dependencies,
            List<Dependency> managedDependencies,
            List<Evidence> evidence) {
        Descriptor {
            dependencies = List.copyOf(dependencies);
            managedDependencies = List.copyOf(managedDependencies);
            evidence = List.copyOf(evidence);
        }
    }

    record DescriptorOutcome(Optional<Descriptor> descriptor, List<Issue> issues) {
        DescriptorOutcome {
            descriptor = Objects.requireNonNull(descriptor);
            issues = List.copyOf(issues);
        }
    }

    record Issue(Reason reason, String subject, Requirement requirement, List<Evidence> evidence) {
        Issue {
            evidence = List.copyOf(evidence);
        }

        Problem problem() {
            return new Problem(reason, subject, requirement, evidence);
        }
    }

    private static final class Counter {
        private int value;
        private boolean exceeded;
    }

    private static final class ModelRejected extends Exception {
        private final Issue issue;

        private ModelRejected(Issue issue) {
            super(issue.reason().name());
            this.issue = issue;
        }
    }
}
