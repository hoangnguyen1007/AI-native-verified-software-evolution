package com.evolution.analysis.buildmodel;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.ingestion.*;
import java.util.*;

/** Imports one selected, producer-asserted build island as data; target build code is never evaluated here. */
public final class CapturedSourcePlanImporter {
    public static final String SCHEMA = "captured-source-plan-v2";
    public static final VersionedIdentifier PROVIDER =
            new VersionedIdentifier("build.captured-source-plan", "m4uv2.1-v2");
    public enum Status { IMPORTED, REJECTED }
    public enum Reason { STALE_INPUT, PRODUCER_NOT_ALLOWED, CAPTURE_LIMIT, DIGEST_MISMATCH,
        ROOT_LIMIT, NO_SOURCE_ROOT, ROOT_OUTSIDE_MODULE, OVERLAPPING_SOURCE_SETS, INVALID_ENCODING }

    public record Policy(Set<VersionedIdentifier> allowedProducers, int maxRoots, long maxCaptureBytes) {
        public Policy {
            allowedProducers = Set.copyOf(Objects.requireNonNull(allowedProducers));
            if (maxRoots < 1 || maxCaptureBytes < 1)
                throw new IllegalArgumentException("Positive captured plan bounds required");
        }
    }
    public record Problem(Reason reason, String subject) implements Comparable<Problem> {
        public Problem { Objects.requireNonNull(reason); subject = ContractChecks.text(subject, "plan problem"); }
        @Override public int compareTo(Problem other) {
            int c = reason.compareTo(other.reason); return c != 0 ? c : subject.compareTo(other.subject);
        }
    }
    public record CustomSet(String name, SourceClassification semanticRole, List<String> sourceRoots) {
        public CustomSet {
            name = ContractChecks.token(name, "custom source-set name");
            if (Set.of("main", "test").contains(name)
                    || !Set.of(SourceClassification.MAIN, SourceClassification.TEST)
                            .contains(Objects.requireNonNull(semanticRole)))
                throw new IllegalArgumentException("Custom source set needs a distinct name and explicit role");
            sourceRoots = Bundle.roots(sourceRoots);
            if (sourceRoots.isEmpty()) throw new IllegalArgumentException("Custom source set needs a root");
        }
    }
    public record Bundle(ContentDigest identity, ContentDigest sourceIdentity, String modulePath,
                         VersionedIdentifier producer, String captureId, byte[] captureBytes,
                         ContentDigest declaredDigest, List<String> mainRoots, List<String> testRoots,
                         List<CustomSet> customSets,
                         int syntaxLevel, int bytecodeTarget, int platformRelease, String encoding) {
        public Bundle {
            Objects.requireNonNull(identity); Objects.requireNonNull(sourceIdentity);
            modulePath = ContractChecks.repositoryRelativePath(modulePath, "captured module path");
            Objects.requireNonNull(producer);
            captureId = ContractChecks.text(captureId, "captured plan ID");
            captureBytes = Objects.requireNonNull(captureBytes).clone();
            Objects.requireNonNull(declaredDigest);
            mainRoots = roots(mainRoots); testRoots = roots(testRoots);
            customSets = List.copyOf(Objects.requireNonNull(customSets)).stream()
                    .sorted(Comparator.comparing(CustomSet::name)).toList();
            if (customSets.stream().map(CustomSet::name).distinct().count() != customSets.size())
                throw new IllegalArgumentException("Duplicate captured custom source-set name");
            if (syntaxLevel < 1 || bytecodeTarget < 1 || platformRelease < 1)
                throw new IllegalArgumentException("Selected Java levels must be positive");
            encoding = ContractChecks.text(encoding, "captured encoding");
            if (!identity.equals(derive(sourceIdentity, modulePath, producer, captureId,
                    declaredDigest, mainRoots, testRoots, customSets, syntaxLevel, bytecodeTarget,
                    platformRelease, encoding)))
                throw new IllegalArgumentException("Captured source plan identity mismatch");
        }
        @Override public byte[] captureBytes() { return captureBytes.clone(); }
        public static Bundle create(ContentDigest source, String modulePath,
                VersionedIdentifier producer, String captureId, byte[] bytes, ContentDigest digest,
                List<String> mainRoots, List<String> testRoots,
                int syntaxLevel, int bytecodeTarget, int platformRelease, String encoding) {
            return create(source, modulePath, producer, captureId, bytes, digest,
                    mainRoots, testRoots, List.of(), syntaxLevel, bytecodeTarget, platformRelease, encoding);
        }
        public static Bundle create(ContentDigest source, String modulePath,
                VersionedIdentifier producer, String captureId, byte[] bytes, ContentDigest digest,
                List<String> mainRoots, List<String> testRoots, List<CustomSet> customSets,
                int syntaxLevel, int bytecodeTarget, int platformRelease, String encoding) {
            var main = roots(mainRoots); var test = roots(testRoots);
            var custom = List.copyOf(customSets).stream().sorted(Comparator.comparing(CustomSet::name)).toList();
            return new Bundle(derive(source, modulePath, producer, captureId, digest, main, test, custom,
                    syntaxLevel, bytecodeTarget, platformRelease, encoding), source, modulePath,
                    producer, captureId, bytes, digest, main, test, custom,
                    syntaxLevel, bytecodeTarget, platformRelease, encoding);
        }
        private static ContentDigest derive(ContentDigest source, String modulePath,
                VersionedIdentifier producer, String captureId, ContentDigest digest,
                List<String> main, List<String> test, List<CustomSet> custom,
                int syntax, int target, int release, String encoding) {
            return IngestionEvidence.digest(List.of(SCHEMA, source, modulePath, producer,
                    captureId, digest, main, test, custom, syntax, target, release, encoding));
        }
        private static List<String> roots(List<String> values) {
            Objects.requireNonNull(values);
            return values.stream().map(v -> ContractChecks.repositoryRelativePath(v, "captured source root"))
                    .distinct().sorted().toList();
        }
    }
    public record Result(ContentDigest identity, Status status, Optional<UniversalBuildModel> build,
                         List<Problem> problems) {
        public Result {
            Objects.requireNonNull(identity); Objects.requireNonNull(status); Objects.requireNonNull(build);
            problems = problems.stream().distinct().sorted().toList();
            if ((status == Status.IMPORTED) != build.isPresent()
                    || (status == Status.IMPORTED) != problems.isEmpty())
                throw new IllegalArgumentException("Captured plan result is inconsistent");
        }
    }

    public Result importBundle(RepositoryInputs inputs, Bundle bundle, Policy policy) {
        Objects.requireNonNull(inputs); Objects.requireNonNull(bundle); Objects.requireNonNull(policy);
        var problems = new ArrayList<Problem>();
        if (!bundle.sourceIdentity().equals(inputs.identity()))
            problems.add(new Problem(Reason.STALE_INPUT, bundle.modulePath()));
        if (!policy.allowedProducers().contains(bundle.producer()))
            problems.add(new Problem(Reason.PRODUCER_NOT_ALLOWED, bundle.producer().toString()));
        if (bundle.captureBytes().length > policy.maxCaptureBytes())
            problems.add(new Problem(Reason.CAPTURE_LIMIT, bundle.captureId()));
        if (!ContentDigest.sha256(bundle.captureBytes()).equals(bundle.declaredDigest()))
            problems.add(new Problem(Reason.DIGEST_MISMATCH, bundle.captureId()));
        int rootCount = bundle.mainRoots().size() + bundle.testRoots().size()
                + bundle.customSets().stream().mapToInt(s -> s.sourceRoots().size()).sum();
        if (rootCount > policy.maxRoots())
            problems.add(new Problem(Reason.ROOT_LIMIT, bundle.modulePath()));
        if (rootCount == 0)
            problems.add(new Problem(Reason.NO_SOURCE_ROOT, bundle.modulePath()));
        var groups = new ArrayList<List<String>>();
        groups.add(bundle.mainRoots()); groups.add(bundle.testRoots());
        bundle.customSets().forEach(s -> groups.add(s.sourceRoots()));
        for (var group : groups) for (String root : group)
            if (!under(bundle.modulePath(), root)) problems.add(new Problem(Reason.ROOT_OUTSIDE_MODULE, root));
        for (int left = 0; left < groups.size(); left++)
            for (int right = left + 1; right < groups.size(); right++)
                for (String a : groups.get(left)) for (String b : groups.get(right))
                    if (under(a, b) || under(b, a))
                        problems.add(new Problem(Reason.OVERLAPPING_SOURCE_SETS, a + "|" + b));
        if (!Set.of("UTF-8", "UTF-16", "UTF-16BE", "UTF-16LE", "ISO-8859-1", "US-ASCII")
                .contains(bundle.encoding().toUpperCase(Locale.ROOT)))
            problems.add(new Problem(Reason.INVALID_ENCODING, bundle.encoding()));
        Optional<UniversalBuildModel> build = Optional.empty();
        if (problems.isEmpty()) {
            var descriptor = ModuleDescriptor.create(inputs.snapshot().repository(), bundle.modulePath(),
                    "captured-source-plan");
            var evidence = new BuildModelResult.PomEvidence("captured-plan/" + bundle.captureId(),
                    bundle.declaredDigest());
            var plans = new ArrayList<SourcePlanModel.SourceSetPlan>();
            for (var kind : List.of(SourcePlanModel.Kind.MAIN, SourcePlanModel.Kind.TEST)) {
                var roots = kind == SourcePlanModel.Kind.MAIN ? bundle.mainRoots() : bundle.testRoots();
                plans.add(plan(descriptor.identity(), kind,
                        kind == SourcePlanModel.Kind.MAIN ? "main" : "test",
                        kind == SourcePlanModel.Kind.MAIN ? SourceClassification.MAIN : SourceClassification.TEST,
                        roots, bundle, evidence));
            }
            for (var custom : bundle.customSets())
                plans.add(plan(descriptor.identity(), SourcePlanModel.Kind.CUSTOM, custom.name(),
                        custom.semanticRole(), custom.sourceRoots(), bundle, evidence));
            var module = new UniversalBuildModel.Module(descriptor,
                    UniversalBuildModel.Tool.IMPORTED_SOURCE_PLAN, Optional.empty(), Optional.empty(),
                    new SourcePlanModel(plans, List.of()), List.of(), List.of(evidence));
            var issue = new IngestionEvidence.Issue(IngestionEvidence.Reason.CLASSPATH_RESOLUTION_REQUIRED,
                    bundle.modulePath(), List.of(bundle.declaredDigest()));
            var issues = List.of(issue);
            var identity = IngestionEvidence.digest(List.of(SCHEMA, PROVIDER, inputs.identity(), bundle.identity(),
                    policy.allowedProducers().stream().sorted(Comparator.comparing(Object::toString)).toList(),
                    policy.maxRoots(), policy.maxCaptureBytes()));
            build = Optional.of(new UniversalBuildModel(identity, inputs.identity(), List.of(module),
                    List.of(), issues, IngestionEvidence.gaps(inputs.snapshot().identity(), PROVIDER,
                            identity, issues)));
        }
        var sorted = problems.stream().distinct().sorted().toList();
        var identity = IngestionEvidence.digest(List.of(SCHEMA, PROVIDER, inputs.identity(), bundle.identity(),
                policy.allowedProducers().stream().sorted(Comparator.comparing(Object::toString)).toList(),
                policy.maxRoots(), policy.maxCaptureBytes(), build.map(UniversalBuildModel::identity), sorted));
        return new Result(identity, sorted.isEmpty() ? Status.IMPORTED : Status.REJECTED, build, sorted);
    }
    private static boolean under(String root, String path) {
        return root.equals(".") || path.equals(root) || path.startsWith(root + "/");
    }
    private static SourcePlanModel.SourceSetPlan plan(com.evolution.analysis.contract.identity.ModuleIdentity module,
            SourcePlanModel.Kind kind, String name, SourceClassification role, List<String> roots,
            Bundle bundle, BuildModelResult.PomEvidence evidence) {
        return new SourcePlanModel.SourceSetPlan(module, kind, name, role,
                roots.stream().map(root -> setting("sourceRoot", root, evidence)).toList(),
                List.of(), absent("output"), Map.of(),
                setting("source", Integer.toString(bundle.syntaxLevel()), evidence),
                setting("target", Integer.toString(bundle.bytecodeTarget()), evidence),
                setting("release", Integer.toString(bundle.platformRelease()), evidence),
                setting("encoding", bundle.encoding(), evidence), List.of(), List.of());
    }
    private static SourcePlanModel.Setting setting(String selector, String value,
            BuildModelResult.PomEvidence evidence) {
        return new SourcePlanModel.Setting(selector, Optional.of(value), Optional.of(value),
                SourcePlanModel.Status.DECLARED, SourcePlanModel.Origin.EFFECTIVE_MODEL,
                List.of(evidence));
    }
    private static SourcePlanModel.Setting absent(String selector) {
        return new SourcePlanModel.Setting(selector, Optional.empty(), Optional.empty(),
                SourcePlanModel.Status.UNSPECIFIED, SourcePlanModel.Origin.ABSENT, List.of());
    }
}
