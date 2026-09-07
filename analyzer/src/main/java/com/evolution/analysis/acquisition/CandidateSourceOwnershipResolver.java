package com.evolution.analysis.acquisition;

import com.evolution.analysis.buildmodel.BuildModelRequest;
import com.evolution.analysis.buildmodel.BuildModelResult;
import com.evolution.analysis.buildmodel.SourcePlanModel;
import java.util.*;

/** Deterministic path membership only; decoding and semantic acceptance occur in later providers. */
public final class CandidateSourceOwnershipResolver {
    private CandidateSourceOwnershipResolver() {}

    public static CandidateSourceOwnership resolve(
            RepositoryAcquisitionResult acquisition,
            BuildModelRequest request,
            BuildModelResult buildModel) {
        Objects.requireNonNull(acquisition);
        Objects.requireNonNull(request);
        Objects.requireNonNull(buildModel);
        var snapshot = acquisition.snapshot().orElseThrow(
                () -> new IllegalArgumentException("Candidate ownership requires a complete acquisition"));
        if (!snapshot.identity().equals(request.snapshot().identity())) {
            throw new IllegalArgumentException("Build request belongs to a different acquired snapshot");
        }
        if (!request.identity().equals(buildModel.requestIdentity())) {
            throw new IllegalArgumentException("Build result belongs to a different build request");
        }

        Set<String> directories = Set.copyOf(acquisition.directories());
        List<AcquiredFile> javaFiles = acquisition.files().stream()
                .filter(file -> file.path().endsWith(".java"))
                .toList();
        Map<String, List<CandidateSourceOwnership.Claim>> claimsByFile = new TreeMap<>();
        List<CandidateSourceOwnership.Problem> problems = new ArrayList<>();

        for (BuildModelResult.ModuleModel module : buildModel.modules()) {
            module.effectivePom().ifPresent(pom -> pom.sourcePlan().sourceSets().forEach(sourceSet -> {
                for (SourcePlanModel.Setting root : sourceSet.sourceRoots()) {
                    if (root.value().isEmpty()) continue;
                    String path = root.value().orElseThrow();
                    var claim = new CandidateSourceOwnership.Claim(
                            sourceSet.module(), sourceSet.kind(), path, root.inputs());
                    if (!directories.contains(path)) {
                        problems.add(new CandidateSourceOwnership.Problem(
                                CandidateSourceOwnership.Reason.MISSING_SOURCE_ROOT,
                                path,
                                CandidateSourceOwnership.Requirement.FILESYSTEM_SOURCE_ROOT,
                                List.of(claim)));
                        continue;
                    }
                    String prefix = path.equals(".") ? "" : path + "/";
                    javaFiles.stream()
                            .filter(file -> file.path().startsWith(prefix))
                            .forEach(file -> claimsByFile
                                    .computeIfAbsent(file.path(), ignored -> new ArrayList<>())
                                    .add(claim));
                }
            }));
        }

        List<CandidateSourceOwnership.Candidate> candidates = new ArrayList<>();
        for (AcquiredFile file : javaFiles) {
            List<CandidateSourceOwnership.Claim> claims = claimsByFile.getOrDefault(file.path(), List.of()).stream()
                    .distinct()
                    .sorted(CandidateSourceOwnership::compareClaims)
                    .toList();
            CandidateSourceOwnership.Status status = claims.isEmpty()
                    ? CandidateSourceOwnership.Status.UNOWNED
                    : claims.size() == 1
                            ? CandidateSourceOwnership.Status.OWNED
                            : CandidateSourceOwnership.Status.OVERLAPPING;
            candidates.add(new CandidateSourceOwnership.Candidate(
                    file.path(), file.contentDigest(), status, claims));
            if (status == CandidateSourceOwnership.Status.UNOWNED) {
                problems.add(new CandidateSourceOwnership.Problem(
                        CandidateSourceOwnership.Reason.UNOWNED_SOURCE_FILE,
                        file.path(),
                        CandidateSourceOwnership.Requirement.BUILD_CONFIGURATION,
                        List.of()));
            } else if (status == CandidateSourceOwnership.Status.OVERLAPPING) {
                problems.add(new CandidateSourceOwnership.Problem(
                        CandidateSourceOwnership.Reason.OVERLAPPING_FILE_OWNERSHIP,
                        file.path(),
                        CandidateSourceOwnership.Requirement.BUILD_CONFIGURATION,
                        claims));
            }
        }
        return new CandidateSourceOwnership(
                CandidateSourceOwnership.SCHEMA,
                snapshot.identity(),
                buildModel.identity(),
                CandidateSourceOwnership.PROVIDER,
                candidates,
                problems.stream().distinct().toList(),
                CandidateSourceOwnership.LIMITATIONS);
    }
}
