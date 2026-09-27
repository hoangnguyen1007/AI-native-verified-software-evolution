package com.evolution.analysis.ingestion;

import com.evolution.analysis.acquisition.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.frontend.*;
import java.util.*;

/** Converts complete captured bytes into selective text inputs; unreadable text stays in the file denominator. */
public final class RepositoryInputDecoder {
    public static final VersionedIdentifier PROVIDER =
            new VersionedIdentifier("repository.input-decoder", "m4uv2.1-v1");
    public record Policy(String charset, int maxTextFiles, int maxTextBytes) {
        public Policy {
            charset = ContractChecks.text(charset, "intake charset");
            if (!Set.of("UTF-8", "UTF-16", "UTF-16BE", "UTF-16LE", "ISO-8859-1", "US-ASCII")
                    .contains(charset.toUpperCase(Locale.ROOT)))
                throw new IllegalArgumentException("Intake charset outside the portable decoding catalog");
            if (maxTextFiles < 1 || maxTextBytes < 1)
                throw new IllegalArgumentException("Positive intake decode bounds required");
        }
    }
    public record Result(ContentDigest identity, RepositoryUnderstandingInventory inventory,
                         Optional<RepositoryInputs> inputs, List<IngestionEvidence.Issue> issues,
                         List<CapabilityGapRecord> gaps) {
        public Result {
            Objects.requireNonNull(identity); Objects.requireNonNull(inventory); Objects.requireNonNull(inputs);
            issues = List.copyOf(issues); gaps = List.copyOf(gaps);
            if ((inventory.acquisitionCompletion() == RepositoryAcquisitionResult.Completion.COMPLETE)
                    != inputs.isPresent())
                throw new IllegalArgumentException("Partial acquisition cannot produce an exact repository input");
        }
    }

    public Result decode(RepositoryAcquisitionResult acquisition, Policy policy) {
        Objects.requireNonNull(acquisition); Objects.requireNonNull(policy);
        var inventory = RepositoryUnderstandingInventory.from(acquisition);
        Optional<RepositoryInputs> output = Optional.empty();
        var issues = new ArrayList<IngestionEvidence.Issue>();
        var gaps = new ArrayList<CapabilityGapRecord>();
        if (acquisition.snapshot().isPresent()) {
            var snapshot = acquisition.snapshot().orElseThrow();
            var provisionalModule = ModuleDescriptor.create(snapshot.repository(), ".", "intake-observed");
            Map<String, SourceInput> decoded = new TreeMap<>();
            int attempted = 0;
            for (var file : acquisition.files()) {
                if (!isText(file.path())) continue;
                if (++attempted > policy.maxTextFiles() || file.size() > policy.maxTextBytes()) {
                    issues.add(new IngestionEvidence.Issue(IngestionEvidence.Reason.INPUT_LIMIT,
                            file.path(), List.of(file.contentDigest())));
                    continue;
                }
                if (inventory.observedFiles().stream().anyMatch(row -> row.path().equals(file.path())
                        && row.availability() == RepositoryUnderstandingInventory.Availability.LFS_POINTER)) {
                    issues.add(new IngestionEvidence.Issue(IngestionEvidence.Reason.INPUT_UNAVAILABLE,
                            file.path(), List.of(file.contentDigest())));
                    continue;
                }
                var document = SourceDocument.create(snapshot.repository(), provisionalModule,
                        file.path(), file.contentDigest(), classification(file.path()));
                try {
                    var decoding = new SourceInput.Decoding("source-decoding-v1", policy.charset(),
                            SourceInput.EncodingOrigin.ANALYSIS_POLICY, List.of(),
                            SourceInput.Bom.detect(file.bytes()), SourceInput.LineEndings.from(""));
                    decoded.put(file.path(), new SourceInput(document, file.bytes(), decoding));
                } catch (FrontendInputException failure) {
                    issues.add(new IngestionEvidence.Issue(IngestionEvidence.Reason.MALFORMED_INPUT,
                            file.path(), List.of(file.contentDigest())));
                }
            }
            output = Optional.of(new RepositoryInputs(snapshot, decoded));
            var sorted = issues.stream().distinct().sorted(Comparator.comparing(IngestionEvidence.Issue::identity)).toList();
            gaps.addAll(IngestionEvidence.gaps(snapshot.identity(), PROVIDER, acquisition.identity(), sorted));
        }
        var sorted = issues.stream().distinct().sorted(Comparator.comparing(IngestionEvidence.Issue::identity)).toList();
        ContentDigest identity = IngestionEvidence.digest(List.of(PROVIDER, acquisition.identity(), policy,
                inventory.identity(), output.map(RepositoryInputs::identity), sorted));
        return new Result(identity, inventory, output, sorted, gaps.stream().sorted().toList());
    }

    private static boolean isText(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return name.equals("pom.xml") || name.equals("build.gradle") || name.equals("build.gradle.kts")
                || name.equals("settings.gradle") || name.equals("settings.gradle.kts")
                || name.equals("spring.factories") || name.endsWith(".imports")
                || name.endsWith(".java") || name.endsWith(".xml")
                || name.endsWith(".properties") || name.endsWith(".yml") || name.endsWith(".yaml")
                || name.endsWith(".json") || name.endsWith(".toml") || name.endsWith(".lockfile");
    }
    private static SourceClassification classification(String path) {
        String lower = "/" + path.toLowerCase(Locale.ROOT) + "/";
        if (lower.contains("/src/test/") || lower.contains("/test/")) return SourceClassification.TEST;
        if (lower.endsWith(".java/")) return SourceClassification.MAIN;
        return SourceClassification.OTHER;
    }
}
