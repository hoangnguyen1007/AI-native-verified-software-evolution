package com.evolution.analysis.ingestion;

import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import java.util.*;

/** Imports generated Java as immutable supplied data with exact source/recipe lineage. */
public final class GeneratedSourceBundleImporter {
    public static final String SCHEMA = "generated-source-bundle-v2";
    public static final VersionedIdentifier PROVIDER =
            new VersionedIdentifier("source.generated-bundle-import", "m4uv2.1-v2");
    public enum Status { IMPORTED, REJECTED }
    public enum Reason { PRODUCER_NOT_ALLOWED, STALE_INPUT, INPUT_UNAVAILABLE, DIGEST_MISMATCH,
        OUTPUT_COLLISION, OUTPUT_OUTSIDE_SOURCE_ROOT, UNKNOWN_SOURCE_SET, OUTPUT_LIMIT,
        INVALID_ENCODING }
    public record Problem(Reason reason, String subject) implements Comparable<Problem> {
        public Problem { Objects.requireNonNull(reason); subject = ContractChecks.text(subject, "generated import subject"); }
        @Override public int compareTo(Problem other) {
            int c = reason.compareTo(other.reason); return c != 0 ? c : subject.compareTo(other.subject);
        }
    }
    public record Policy(Set<VersionedIdentifier> allowedProducers, int maxOutputs, long maxTotalBytes) {
        public Policy {
            allowedProducers = Set.copyOf(Objects.requireNonNull(allowedProducers));
            if (maxOutputs < 1 || maxTotalBytes < 1) throw new IllegalArgumentException("Positive generated import bounds required");
        }
    }
    public record InputReference(String path, ContentDigest digest) implements Comparable<InputReference> {
        public InputReference {
            path = ContractChecks.repositoryRelativePath(path, "generator input path");
            Objects.requireNonNull(digest);
        }
        @Override public int compareTo(InputReference other) { return path.compareTo(other.path); }
    }
    public record Output(String path, byte[] bytes, ContentDigest declaredDigest,
                         UniversalSourceIngestion.SourceSet sourceSet) {
        public Output {
            path = ContractChecks.repositoryRelativePath(path, "generated output path");
            if (!path.endsWith(".java")) throw new IllegalArgumentException("Generated output must be Java source");
            bytes = Objects.requireNonNull(bytes).clone();
            Objects.requireNonNull(declaredDigest); Objects.requireNonNull(sourceSet);
        }
        @Override public byte[] bytes() { return bytes.clone(); }
        public ContentDigest actualDigest() { return ContentDigest.sha256(bytes); }
        public int size() { return bytes.length; }
    }
    private record OutputView(String path, ContentDigest declaredDigest,
                              UniversalSourceIngestion.SourceSet sourceSet) {}
    public record Bundle(ContentDigest identity, ContentDigest sourceIdentity, ContentDigest buildIdentity,
                         VersionedIdentifier producer, ContentDigest recipeDigest,
                         List<InputReference> inputs, List<Output> outputs) {
        public Bundle {
            Objects.requireNonNull(identity); Objects.requireNonNull(sourceIdentity); Objects.requireNonNull(buildIdentity);
            Objects.requireNonNull(producer); Objects.requireNonNull(recipeDigest);
            inputs = ContractChecks.sortedDistinct(inputs, Comparator.naturalOrder(), "generator inputs");
            if (inputs.isEmpty()) throw new IllegalArgumentException("Generator lineage needs a captured input");
            outputs = ContractChecks.sortedDistinct(outputs, Comparator.comparing(Output::path), "generated outputs");
            if (outputs.isEmpty()) throw new IllegalArgumentException("Generated bundle needs an output");
            if (!identity.equals(derive(sourceIdentity, buildIdentity, producer, recipeDigest, inputs, outputs)))
                throw new IllegalArgumentException("Generated bundle identity mismatch");
        }
        public static Bundle create(ContentDigest sourceIdentity, ContentDigest buildIdentity,
                VersionedIdentifier producer, ContentDigest recipeDigest,
                List<InputReference> inputs, List<Output> outputs) {
            var orderedInputs = inputs.stream().sorted().toList();
            var orderedOutputs = outputs.stream().sorted(Comparator.comparing(Output::path)).toList();
            return new Bundle(derive(sourceIdentity, buildIdentity, producer, recipeDigest,
                    orderedInputs, orderedOutputs), sourceIdentity, buildIdentity, producer,
                    recipeDigest, orderedInputs, orderedOutputs);
        }
        private static ContentDigest derive(ContentDigest source, ContentDigest build,
                VersionedIdentifier producer, ContentDigest recipe, List<InputReference> inputs,
                List<Output> outputs) {
            var views = outputs.stream().map(o -> new OutputView(o.path(), o.declaredDigest(), o.sourceSet())).toList();
            return IngestionEvidence.digest(List.of(SCHEMA, source, build, producer, recipe, inputs, views));
        }
    }
    public record Lineage(String outputPath, ContentDigest outputDigest, VersionedIdentifier producer,
                          ContentDigest recipeDigest, List<InputReference> inputReferences) {
        public Lineage {
            outputPath = ContractChecks.repositoryRelativePath(outputPath, "generated lineage output");
            Objects.requireNonNull(outputDigest); Objects.requireNonNull(producer); Objects.requireNonNull(recipeDigest);
            inputReferences = List.copyOf(inputReferences);
        }
    }
    public record Result(ContentDigest identity, Status status, Optional<RepositoryInputs> inputs,
                         List<Lineage> lineage, List<Problem> problems) {
        public Result {
            Objects.requireNonNull(identity); Objects.requireNonNull(status); Objects.requireNonNull(inputs);
            lineage = List.copyOf(lineage); problems = problems.stream().distinct().sorted().toList();
            if ((status == Status.IMPORTED) != inputs.isPresent()
                    || (status == Status.IMPORTED) != problems.isEmpty())
                throw new IllegalArgumentException("Generated import status and outputs disagree");
        }
    }

    public Result importBundle(RepositoryInputs original, UniversalBuildModel build, Bundle bundle, Policy policy) {
        Objects.requireNonNull(original); Objects.requireNonNull(build); Objects.requireNonNull(bundle); Objects.requireNonNull(policy);
        if (!build.repositoryInputIdentity().equals(original.identity())
                || !bundle.sourceIdentity().equals(original.identity())
                || !bundle.buildIdentity().equals(build.identity()))
            throw new IllegalArgumentException("Generated bundle belongs to a different source/build revision");
        var problems = new ArrayList<Problem>();
        if (!policy.allowedProducers().contains(bundle.producer()))
            problems.add(new Problem(Reason.PRODUCER_NOT_ALLOWED, bundle.producer().toString()));
        for (var input : bundle.inputs()) {
            var source = original.files().get(input.path());
            if (source == null) problems.add(new Problem(Reason.INPUT_UNAVAILABLE, input.path()));
            else if (!source.document().contentDigest().equals(input.digest()))
                problems.add(new Problem(Reason.STALE_INPUT, input.path()));
        }
        if (bundle.outputs().size() > policy.maxOutputs())
            problems.add(new Problem(Reason.OUTPUT_LIMIT, "generated-output-count"));
        long total = 0;
        var generated = new TreeMap<String, SourceInput>();
        var lineage = new ArrayList<Lineage>();
        for (var output : bundle.outputs()) {
            total += output.size();
            if (total > policy.maxTotalBytes()) {
                problems.add(new Problem(Reason.OUTPUT_LIMIT, output.path())); continue;
            }
            if (!output.actualDigest().equals(output.declaredDigest())) {
                problems.add(new Problem(Reason.DIGEST_MISMATCH, output.path())); continue;
            }
            if (original.snapshot().files().stream().anyMatch(f -> f.path().equals(output.path()))) {
                problems.add(new Problem(Reason.OUTPUT_COLLISION, output.path())); continue;
            }
            var owner = build.modules().stream().filter(m -> m.descriptor().identity().equals(output.sourceSet().module()))
                    .findFirst();
            var plan = owner.stream().flatMap(m -> m.sourcePlan().sourceSets().stream())
                    .filter(s -> s.kind() == output.sourceSet().kind()
                            && s.name().equals(output.sourceSet().name())).findFirst();
            if (owner.isEmpty() || plan.isEmpty()) {
                problems.add(new Problem(Reason.UNKNOWN_SOURCE_SET, output.path())); continue;
            }
            boolean inRoot = plan.orElseThrow().sourceRoots().stream().flatMap(r -> r.value().stream())
                    .anyMatch(root -> root.equals(".") || output.path().startsWith(root + "/"));
            if (!inRoot) {
                problems.add(new Problem(Reason.OUTPUT_OUTSIDE_SOURCE_ROOT, output.path())); continue;
            }
            var classification = plan.orElseThrow().semanticRole() == SourceClassification.MAIN
                    ? SourceClassification.GENERATED_MAIN : SourceClassification.GENERATED_TEST;
            var document = SourceDocument.create(original.snapshot().repository(), owner.orElseThrow().descriptor(),
                    output.path(), output.actualDigest(), classification);
            try {
                generated.put(output.path(), new SourceInput(document, output.bytes()));
                lineage.add(new Lineage(output.path(), output.actualDigest(), bundle.producer(),
                        bundle.recipeDigest(), bundle.inputs()));
            } catch (FrontendInputException failure) {
                problems.add(new Problem(Reason.INVALID_ENCODING, output.path()));
            }
        }
        var sorted = problems.stream().distinct().sorted().toList();
        Optional<RepositoryInputs> next = Optional.empty();
        if (sorted.isEmpty()) {
            var files = new ArrayList<>(original.snapshot().files());
            var docs = new ArrayList<>(original.snapshot().documents());
            for (var source : generated.values()) {
                files.add(SnapshotFile.from(source.document())); docs.add(source.document());
            }
            var snapshot = RepositorySnapshot.create(original.snapshot().repository(),
                    original.snapshot().revision(), original.snapshot().dirty(), files, docs);
            var bytes = new TreeMap<>(original.files()); bytes.putAll(generated);
            next = Optional.of(new RepositoryInputs(snapshot, bytes));
        }
        ContentDigest identity = IngestionEvidence.digest(List.of(SCHEMA, PROVIDER, original.identity(),
                build.identity(), bundle.identity(), policy.allowedProducers().stream().sorted(Comparator.comparing(Object::toString)).toList(),
                policy.maxOutputs(), policy.maxTotalBytes(), lineage,
                next.map(RepositoryInputs::identity), sorted));
        return new Result(identity, sorted.isEmpty() ? Status.IMPORTED : Status.REJECTED,
                next, sorted.isEmpty() ? lineage : List.of(), sorted);
    }
}
