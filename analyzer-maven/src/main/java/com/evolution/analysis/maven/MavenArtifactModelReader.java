package com.evolution.analysis.maven;

import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.buildmodel.BuildModelResult.Dependency;
import com.evolution.analysis.classpath.ExactClasspathResult.*;
import com.evolution.analysis.contract.common.ContentDigest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Profile;
import org.apache.maven.model.Relocation;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.*;
import org.apache.maven.model.profile.DefaultProfileActivationContext;
import org.apache.maven.model.profile.DefaultProfileSelector;
import org.apache.maven.model.profile.ProfileActivationContext;
import org.apache.maven.model.profile.ProfileSelector;
import org.apache.maven.model.profile.activation.ProfileActivator;
import org.apache.maven.model.profile.activation.PropertyProfileActivator;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;

/** Effective external artifact descriptors over already bounded cache/supplied POM bytes. */
final class MavenArtifactModelReader {
    private static final int MAX_RELOCATION_HOPS = 8;
    private final LocalArtifactCache cache;
    private final BuildModelPolicy policy;
    private final Optional<String> configuredJdk;
    private final Map<MavenCoordinate, DescriptorOutcome> descriptors = new HashMap<>();
    private final Set<ArtifactRecord> validatedPoms = new HashSet<>();

    private static final int DESCRIPTOR_CACHE_MAX = 4096;
    private static final Map<DescriptorCacheKey, CachedDescriptor> DESCRIPTOR_CACHE =
            Collections.synchronizedMap(new LinkedHashMap<>(512, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<DescriptorCacheKey, CachedDescriptor> eldest) {
                    return size() > DESCRIPTOR_CACHE_MAX;
                }
            });

    private record DescriptorCacheKey(MavenCoordinate coordinate, BuildModelPolicy policy, ContentDigest rootDigest) {}
    private record CachedDescriptor(DescriptorOutcome outcome, List<ArtifactRecord> inputs) {}

    MavenArtifactModelReader(LocalArtifactCache cache, BuildModelPolicy policy) {
        this.cache = Objects.requireNonNull(cache);
        this.policy = Objects.requireNonNull(policy);
        this.configuredJdk = configuredJdkVersion(policy);
    }

    DescriptorOutcome descriptor(MavenCoordinate coordinate) {
        return descriptors.computeIfAbsent(coordinate, key -> build(key, Set.of(), new Counter()));
    }

    private DescriptorOutcome build(MavenCoordinate expected, Set<MavenCoordinate> relocationChain, Counter reads) {
        var issues = new ArrayList<Issue>();
        var used = new TreeMap<String, Evidence>();
        var tolerantInBuild = new HashSet<MavenCoordinate>();
        try {
            var root = acquire(expected);
            DescriptorCacheKey key = null;
            if (relocationChain.isEmpty()) {
                key = new DescriptorCacheKey(expected, policy, root.record().contentDigest());
                var cached = DESCRIPTOR_CACHE.get(key);
                if (cached != null && currentInputs(root.record(), cached.inputs())) return cached.outcome();
            }
            Source source = source(expected, root, issues, used, reads, tolerantInBuild);

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
                var relocated = resolveRelocation(
                        expected, model.getDistributionManagement().getRelocation(),
                        relocationChain, reads, issues, used);
                if (relocationChain.isEmpty() && key != null && relocated.descriptor().isPresent()
                        && !reads.exceeded && relocated.issues().isEmpty() && tolerantInBuild.isEmpty()) {
                    DESCRIPTOR_CACHE.put(key, new CachedDescriptor(relocated, List.copyOf(reads.inputs.values())));
                }
                return relocated;
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
            String packaging = Objects.toString(model.getPackaging(), "jar");
            var outcome = new DescriptorOutcome(
                    Optional.of(new Descriptor(packaging, dependencies, managed, List.copyOf(used.values()), Optional.empty())),
                    issues);
            if (relocationChain.isEmpty() && key != null && !reads.exceeded && issues.isEmpty() && tolerantInBuild.isEmpty()) {
                DESCRIPTOR_CACHE.put(key, new CachedDescriptor(outcome, List.copyOf(reads.inputs.values())));
            }
            return outcome;
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

    private DescriptorOutcome resolveRelocation(
            MavenCoordinate expected,
            Relocation relocation,
            Set<MavenCoordinate> relocationChain,
            Counter reads,
            List<Issue> issues,
            Map<String, Evidence> used) {
        String group = blank(relocation.getGroupId()) ? expected.groupId() : relocation.getGroupId().trim();
        String artifact = blank(relocation.getArtifactId()) ? expected.artifactId() : relocation.getArtifactId().trim();
        String version = blank(relocation.getVersion()) ? expected.version() : relocation.getVersion().trim();
        MavenCoordinate target;
        try {
            target = new MavenCoordinate(group, artifact, version);
        } catch (IllegalArgumentException invalid) {
            issues.add(issue(Reason.RELOCATION_UNSUPPORTED, expected.notation(), Requirement.ARTIFACT_POM, used));
            return new DescriptorOutcome(Optional.empty(), issues);
        }
        var nextChain = new HashSet<MavenCoordinate>(relocationChain);
        nextChain.add(expected);
        if (nextChain.contains(target) || nextChain.size() > MAX_RELOCATION_HOPS) {
            issues.add(issue(Reason.RELOCATION_UNSUPPORTED, expected.notation(), Requirement.ARTIFACT_POM, used));
            return new DescriptorOutcome(Optional.empty(), issues);
        }
        DescriptorOutcome targetOutcome = build(target, nextChain, reads);
        if (targetOutcome.descriptor().isEmpty()) {
            issues.add(issue(Reason.RELOCATION_UNSUPPORTED, expected.notation(), Requirement.ARTIFACT_POM, used));
            return new DescriptorOutcome(Optional.empty(), issues);
        }
        var td = targetOutcome.descriptor().orElseThrow();
        var mergedEvidence = new TreeMap<String, Evidence>(used);
        for (var ev : td.evidence()) {
            mergedEvidence.putIfAbsent(ev.logicalId(), ev);
        }
        var mergedIssues = new ArrayList<Issue>(issues);
        mergedIssues.addAll(targetOutcome.issues());
        MavenCoordinate finalTarget = td.relocatedTo().orElse(target);
        var descriptor = new Descriptor(
                td.packaging(),
                td.dependencies(),
                td.managedDependencies(),
                List.copyOf(mergedEvidence.values()),
                Optional.of(finalTarget));
        return new DescriptorOutcome(Optional.of(descriptor), mergedIssues);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private LocalArtifactCache.Material acquire(MavenCoordinate coordinate) throws ModelRejected {
        try {
            return cache.pom(coordinate);
        } catch (LocalArtifactCache.Failure failure) {
            throw new ModelRejected(new Issue(failure.reason, failure.subject, failure.requirement, failure.evidence));
        }
    }

    private boolean currentInputs(ArtifactRecord root, List<ArtifactRecord> inputs) {
        if (!root.equals(inputs.getFirst())) return false;
        // Replay the resolver's acquisition order. Current bytes, provenance and budgets must all match.
        for (int i = 1; i < inputs.size(); i++) {
            var expected = inputs.get(i);
            try {
                if (!expected.equals(cache.pom(expected.coordinate().gav()).record())) return false;
            } catch (LocalArtifactCache.Failure unavailable) {
                return false;
            }
        }
        return true;
    }

    private Source source(MavenCoordinate coordinate, LocalArtifactCache.Material material,
            List<Issue> issues, Map<String, Evidence> used, Counter reads,
            Set<MavenCoordinate> tolerantInBuild) throws ModelRejected {
        var record = material.record();
        var evidence = record.evidence();
        used.put(evidence.logicalId(), evidence);
        if (!validatedPoms.contains(record)) {
            int before = issues.size();
            if (validate(material, coordinate, issues, used)) tolerantInBuild.add(coordinate);
            else if (issues.size() == before) validatedPoms.add(record);
        }
        reads.inputs.putIfAbsent(coordinate, record);
        return new Source(coordinate, material, reads);
    }

    private boolean validate(
            LocalArtifactCache.Material material,
            MavenCoordinate coordinate,
            List<Issue> issues,
            Map<String, Evidence> used) throws ModelRejected {
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
            if (!explicit && activation != null) {
                boolean unsupportedJdk = activation.getJdk() != null
                        && (configuredJdk.isEmpty() || !isValidJdkActivationSpec(activation.getJdk()));
                if (unsupportedJdk || activation.getOs() != null || activation.getFile() != null) {
                    issues.add(issue(
                            Reason.UNSUPPORTED_PROFILE_ACTIVATION,
                            coordinate.notation(),
                            Requirement.ANALYSIS_CONFIGURATION,
                            used));
                }
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

    private DefaultModelBuilder cachedBuilder;

    private DefaultModelBuilder builder() {
        if (cachedBuilder != null) return cachedBuilder;
        cachedBuilder = new DefaultModelBuilderFactory() {
            @Override
            protected ProfileSelector newProfileSelector() {
                var selector = new DefaultProfileSelector()
                        .addProfileActivator(new PropertyProfileActivator())
                        .addProfileActivator(new DeterministicJdkProfileActivator(configuredJdk));
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
        return cachedBuilder;
    }

    private static Optional<String> configuredJdkVersion(BuildModelPolicy policy) {
        var props = policy.userProperties();
        for (String key : List.of("java.version", "jdk.version", "maven.compiler.release", "maven.compiler.target")) {
            String value = props.get(key);
            if (value != null && !value.isBlank() && parseJavaVersion(value) != null) {
                return Optional.of(value.trim());
            }
        }
        return Optional.empty();
    }

    static boolean isValidJdkActivationSpec(String rawSpec) {
        if (rawSpec == null || rawSpec.isBlank()) return false;
        String spec = rawSpec.trim();
        if (spec.startsWith("!")) spec = spec.substring(1).trim();
        if (spec.isEmpty()) return false;
        if (spec.indexOf('[') >= 0 || spec.indexOf(']') >= 0 || spec.indexOf('(') >= 0 || spec.indexOf(')') >= 0) {
            return parseRanges(spec) != null;
        }
        return parseJavaVersion(spec) != null;
    }

    static boolean matchesJdkActivation(String configuredJdk, String rawSpec) {
        if (configuredJdk == null || configuredJdk.isBlank() || rawSpec == null || rawSpec.isBlank()) return false;
        int[] actual = parseJavaVersion(configuredJdk.trim());
        if (actual == null) return false;
        String spec = rawSpec.trim();
        boolean negated = spec.startsWith("!");
        if (negated) spec = spec.substring(1).trim();
        if (spec.isEmpty()) return false;
        boolean matched;
        if (spec.indexOf('[') >= 0 || spec.indexOf(']') >= 0 || spec.indexOf('(') >= 0 || spec.indexOf(')') >= 0) {
            var ranges = parseRanges(spec);
            if (ranges == null) return false;
            matched = false;
            for (var range : ranges) {
                if (range.contains(actual)) {
                    matched = true;
                    break;
                }
            }
        } else {
            int[] expected = parseJavaVersion(spec);
            if (expected == null) return false;
            // Prefix/major match: "17" matches "17", "17.0.9"; "1.8" matches "1.8.0_392" and "8".
            matched = true;
            for (int i = 0; i < expected.length; i++) {
                int a = i < actual.length ? actual[i] : 0;
                if (a != expected[i]) {
                    matched = false;
                    break;
                }
            }
        }
        return negated ? !matched : matched;
    }

    private record VersionRange(int[] lower, boolean lowerInclusive, int[] upper, boolean upperInclusive) {
        boolean contains(int[] version) {
            if (lower != null) {
                int cmp = compareVersions(version, lower);
                if (lowerInclusive ? cmp < 0 : cmp <= 0) return false;
            }
            if (upper != null) {
                int cmp = compareVersions(version, upper);
                if (upperInclusive ? cmp > 0 : cmp >= 0) return false;
            }
            return true;
        }
    }

    private static List<VersionRange> parseRanges(String spec) {
        var ranges = new ArrayList<VersionRange>();
        int pos = 0;
        int n = spec.length();
        while (pos < n) {
            while (pos < n && (spec.charAt(pos) == ' ' || spec.charAt(pos) == ',')) pos++;
            if (pos >= n) break;
            char open = spec.charAt(pos);
            if (open != '[' && open != '(') return null;
            int closeIdx = -1;
            for (int i = pos + 1; i < n; i++) {
                char c = spec.charAt(i);
                if (c == ']' || c == ')') {
                    closeIdx = i;
                    break;
                }
            }
            if (closeIdx < 0) return null;
            char close = spec.charAt(closeIdx);
            String body = spec.substring(pos + 1, closeIdx).trim();
            int comma = body.indexOf(',');
            if (comma < 0) {
                if (open != '[' || close != ']' || body.isEmpty()) return null;
                int[] exact = parseJavaVersion(body);
                if (exact == null) return null;
                ranges.add(new VersionRange(exact, true, exact, true));
            } else {
                String loStr = body.substring(0, comma).trim();
                String hiStr = body.substring(comma + 1).trim();
                int[] lo = loStr.isEmpty() ? null : parseJavaVersion(loStr);
                int[] hi = hiStr.isEmpty() ? null : parseJavaVersion(hiStr);
                if ((!loStr.isEmpty() && lo == null) || (!hiStr.isEmpty() && hi == null)) return null;
                if (lo == null && hi == null) return null;
                ranges.add(new VersionRange(lo, open == '[', hi, close == ']'));
            }
            pos = closeIdx + 1;
        }
        return ranges.isEmpty() ? null : ranges;
    }

    private static int[] parseJavaVersion(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String clean = raw.trim();
        int dash = clean.indexOf('-');
        if (dash > 0) clean = clean.substring(0, dash);
        int plus = clean.indexOf('+');
        if (plus > 0) clean = clean.substring(0, plus);
        String[] rawParts = clean.split("[._]");
        if (rawParts.length == 0 || rawParts.length > 6) return null;
        int[] parsed = new int[rawParts.length];
        for (int i = 0; i < rawParts.length; i++) {
            if (rawParts[i].isEmpty()) return null;
            try {
                int val = Integer.parseInt(rawParts[i]);
                if (val < 0) return null;
                parsed[i] = val;
            } catch (NumberFormatException invalid) {
                return null;
            }
        }
        // Normalize legacy 1.5..1.8 -> 5..8 so range comparisons like [11,) vs 1.8 work accurately.
        if (parsed.length >= 2 && parsed[0] == 1 && parsed[1] >= 1) {
            int[] normalized = new int[parsed.length - 1];
            System.arraycopy(parsed, 1, normalized, 0, normalized.length);
            return normalized;
        }
        return parsed;
    }

    private static int compareVersions(int[] left, int[] right) {
        int max = Math.max(left.length, right.length);
        for (int i = 0; i < max; i++) {
            int l = i < left.length ? left[i] : 0;
            int r = i < right.length ? right[i] : 0;
            if (l != r) return Integer.compare(l, r);
        }
        return 0;
    }

    private static final class DeterministicJdkProfileActivator implements ProfileActivator {
        private final Optional<String> configuredJdk;

        private DeterministicJdkProfileActivator(Optional<String> configuredJdk) {
            this.configuredJdk = configuredJdk;
        }

        @Override
        public boolean isActive(Profile profile, ProfileActivationContext context, ModelProblemCollector problems) {
            if (!presentInConfig(profile, context, problems) || configuredJdk.isEmpty()) {
                return false;
            }
            return matchesJdkActivation(configuredJdk.orElseThrow(), profile.getActivation().getJdk());
        }

        @Override
        public boolean presentInConfig(Profile profile, ProfileActivationContext context, ModelProblemCollector problems) {
            return profile != null && profile.getActivation() != null
                    && profile.getActivation().getJdk() != null
                    && !profile.getActivation().getJdk().isBlank();
        }
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
                return source(coordinate, acquire(coordinate), issues, used, reads, tolerantInBuild);
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
            String packaging,
            List<Dependency> dependencies,
            List<Dependency> managedDependencies,
            List<Evidence> evidence,
            Optional<MavenCoordinate> relocatedTo) {
        Descriptor(List<Dependency> dependencies, List<Dependency> managedDependencies, List<Evidence> evidence) {
            this("jar", dependencies, managedDependencies, evidence, Optional.empty());
        }
        Descriptor(String packaging, List<Dependency> dependencies, List<Dependency> managedDependencies, List<Evidence> evidence) {
            this(packaging, dependencies, managedDependencies, evidence, Optional.empty());
        }
        Descriptor {
            packaging = packaging == null || packaging.isBlank() ? "jar" : packaging;
            dependencies = List.copyOf(dependencies);
            managedDependencies = List.copyOf(managedDependencies);
            evidence = List.copyOf(evidence);
            relocatedTo = Objects.requireNonNull(relocatedTo);
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
        final Map<MavenCoordinate, ArtifactRecord> inputs = new LinkedHashMap<>();
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
