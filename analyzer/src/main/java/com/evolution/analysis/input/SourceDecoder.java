package com.evolution.analysis.input;

import com.evolution.analysis.acquisition.AcquiredFile;
import com.evolution.analysis.acquisition.CandidateSourceOwnership;
import com.evolution.analysis.acquisition.RepositoryAcquisitionResult;
import com.evolution.analysis.buildmodel.BuildModelResult;
import com.evolution.analysis.buildmodel.BuildModelRequest;
import com.evolution.analysis.buildmodel.SourcePlanModel;
import com.evolution.analysis.contract.source.RepositorySnapshot;
import com.evolution.analysis.contract.source.SourceClassification;
import com.evolution.analysis.contract.source.SourceDocument;
import com.evolution.analysis.frontend.FrontendInputException;
import com.evolution.analysis.frontend.SourceInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Strict, deterministic decoding over the complete M3.3 candidate ledger. */
public final class SourceDecoder {
    private SourceDecoder() {}

    public static SourceDecodingResult decode(
            RepositoryAcquisitionResult acquisition,
            CandidateSourceOwnership ownership,
            BuildModelRequest buildRequest,
            BuildModelResult buildModel,
            SourceDecodingPolicy policy) {
        Objects.requireNonNull(acquisition); Objects.requireNonNull(ownership);
        Objects.requireNonNull(buildRequest); Objects.requireNonNull(buildModel); Objects.requireNonNull(policy);
        RepositorySnapshot acquiredSnapshot = acquisition.snapshot().orElseThrow(
                () -> new IllegalArgumentException("Source decoding requires a complete acquisition"));
        if (!ownership.snapshot().equals(acquiredSnapshot.identity())
                || !buildRequest.snapshot().identity().equals(acquiredSnapshot.identity())
                || !buildModel.requestIdentity().equals(buildRequest.identity())
                || !ownership.buildModel().equals(buildModel.identity())) {
            throw new IllegalArgumentException("Source decoding inputs belong to different workspace evidence");
        }

        Map<String, AcquiredFile> files = acquisition.files().stream()
                .collect(Collectors.toUnmodifiableMap(AcquiredFile::path, Function.identity()));
        Map<String, com.evolution.analysis.contract.common.ContentDigest> acquiredJava = acquisition.files().stream()
                .filter(file -> file.path().endsWith(".java"))
                .collect(Collectors.toUnmodifiableMap(AcquiredFile::path, AcquiredFile::contentDigest));
        Map<String, com.evolution.analysis.contract.common.ContentDigest> ownedCandidates = ownership.candidates().stream()
                .collect(Collectors.toUnmodifiableMap(CandidateSourceOwnership.Candidate::path,
                        CandidateSourceOwnership.Candidate::contentDigest));
        if (!acquiredJava.equals(ownedCandidates)) {
            throw new IllegalArgumentException("Source ownership must cover every acquired Java candidate exactly");
        }
        Map<com.evolution.analysis.contract.identity.ModuleIdentity, BuildModelResult.ModuleModel> modules =
                buildModel.modules().stream().collect(Collectors.toUnmodifiableMap(
                        module -> module.module().identity(), Function.identity()));
        List<SourceDecodingResult.Outcome> outcomes = new ArrayList<>();
        List<SourceDocument> documents = new ArrayList<>();

        for (CandidateSourceOwnership.Candidate candidate : ownership.candidates()) {
            if (candidate.status() != CandidateSourceOwnership.Status.OWNED) {
                var reason = candidate.status() == CandidateSourceOwnership.Status.UNOWNED
                        ? SourceDecodingResult.Reason.UNOWNED_SOURCE
                        : SourceDecodingResult.Reason.OVERLAPPING_OWNERSHIP;
                outcomes.add(failed(candidate, SourceDecodingResult.Status.WITHHELD, reason,
                        SourceDecodingResult.Requirement.SOURCE_OWNERSHIP));
                continue;
            }
            AcquiredFile file = files.get(candidate.path());
            if (file == null || !file.contentDigest().equals(candidate.contentDigest())) {
                outcomes.add(failed(candidate, SourceDecodingResult.Status.WITHHELD,
                        SourceDecodingResult.Reason.MISSING_ACQUIRED_BYTES,
                        SourceDecodingResult.Requirement.ACQUIRED_SOURCE_BYTES));
                continue;
            }
            CandidateSourceOwnership.Claim claim = candidate.claims().getFirst();
            BuildModelResult.ModuleModel module = modules.get(claim.module());
            Optional<SourcePlanModel.SourceSetPlan> plan = module == null || module.effectivePom().isEmpty()
                    ? Optional.empty()
                    : module.effectivePom().orElseThrow().sourcePlan().sourceSets().stream()
                            .filter(value -> value.kind() == claim.sourceSet()).findFirst();
            if (plan.isEmpty()) {
                outcomes.add(failed(candidate, SourceDecodingResult.Status.WITHHELD,
                        SourceDecodingResult.Reason.MISSING_SOURCE_PLAN,
                        SourceDecodingResult.Requirement.BUILD_ENCODING));
                continue;
            }

            SourcePlanModel.Setting setting = plan.orElseThrow().encoding();
            Optional<String> charset;
            SourceInput.EncodingOrigin origin;
            List<SourceInput.Evidence> evidence;
            if (setting.status() == SourcePlanModel.Status.DECLARED
                    || setting.status() == SourcePlanModel.Status.DEFAULT) {
                charset = setting.value();
                origin = SourceInput.EncodingOrigin.BUILD_DECLARATION;
                evidence = setting.inputs().stream()
                        .map(item -> new SourceInput.Evidence(item.logicalId(), item.digest())).toList();
            } else if (setting.status() == SourcePlanModel.Status.UNSPECIFIED) {
                charset = policy.fallbackCharset();
                origin = SourceInput.EncodingOrigin.ANALYSIS_POLICY;
                evidence = List.of(new SourceInput.Evidence("policy:source-decoding", policy.identity()));
            } else {
                var reason = setting.status() == SourcePlanModel.Status.UNSUPPORTED
                        ? SourceDecodingResult.Reason.UNSUPPORTED_ENCODING
                        : SourceDecodingResult.Reason.INVALID_ENCODING_DECLARATION;
                outcomes.add(failed(candidate, SourceDecodingResult.Status.WITHHELD, reason,
                        SourceDecodingResult.Requirement.BUILD_ENCODING));
                continue;
            }
            if (charset.isEmpty()) {
                outcomes.add(failed(candidate, SourceDecodingResult.Status.WITHHELD,
                        SourceDecodingResult.Reason.MISSING_ENCODING,
                        SourceDecodingResult.Requirement.ANALYSIS_ENCODING_POLICY));
                continue;
            }

            SourceDocument document = SourceDocument.create(
                    acquiredSnapshot.repository(), module.module(), candidate.path(), candidate.contentDigest(),
                    claim.sourceSet() == SourcePlanModel.Kind.MAIN
                            ? SourceClassification.MAIN : SourceClassification.TEST);
            try {
                var decoding = new SourceInput.Decoding(
                        "source-decoding-v1", charset.orElseThrow(), origin, evidence,
                        SourceInput.Bom.detect(file.bytes()), SourceInput.LineEndings.from(""));
                SourceInput input = new SourceInput(document, file.bytes(), decoding);
                outcomes.add(new SourceDecodingResult.Outcome(candidate.path(), candidate.contentDigest(),
                        SourceDecodingResult.Status.DECODED, Optional.of(input), List.of()));
                documents.add(document);
            } catch (FrontendInputException exception) {
                SourceDecodingResult.Reason reason = exception.diagnostic().code().equals("frontend.encoding-bom")
                        ? SourceDecodingResult.Reason.BOM_ENCODING_MISMATCH
                        : SourceDecodingResult.Reason.MALFORMED_BYTE_SEQUENCE;
                outcomes.add(failed(candidate, SourceDecodingResult.Status.INVALID, reason,
                        SourceDecodingResult.Requirement.ACQUIRED_SOURCE_BYTES));
            }
        }

        RepositorySnapshot decodedSnapshot = RepositorySnapshot.create(
                acquiredSnapshot.repository(), acquiredSnapshot.revision(), acquiredSnapshot.dirty(),
                acquiredSnapshot.files(), documents);
        return SourceDecodingResult.create(acquisition.identity(), ownership.identity(), buildModel.identity(),
                policy, decodedSnapshot, outcomes);
    }

    private static SourceDecodingResult.Outcome failed(
            CandidateSourceOwnership.Candidate candidate,
            SourceDecodingResult.Status status,
            SourceDecodingResult.Reason reason,
            SourceDecodingResult.Requirement requirement) {
        return new SourceDecodingResult.Outcome(candidate.path(), candidate.contentDigest(), status,
                Optional.empty(), List.of(new SourceDecodingResult.Problem(reason, requirement)));
    }
}
