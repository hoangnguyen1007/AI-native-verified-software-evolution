package com.evolution.analysis.maven;

import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.buildmodel.BuildModelResult.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.model.building.ModelSource2;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;

/** One effective-model attempt and its immutable, bounded, allowlisted input store. */
final class ModelInputs {
    final String root;
    final Map<String, PomEvidence> used = new TreeMap<>();
    private final BuildModelRequest request;
    private final List<Problem> problems;
    private final List<Attempt> attempts;
    private int modelReads;

    ModelInputs(BuildModelRequest request, String root, List<Problem> problems, List<Attempt> attempts) {
        this.request = request;
        this.root = root;
        this.problems = problems;
        this.attempts = attempts;
    }

    Source workspace(String path, String requester, AttemptKind kind) throws SecurePomReader.Rejected {
        PomInput input = request.workspacePoms().get(path);
        if (input == null) {
            attempts.add(new Attempt(requester, kind, "workspace:" + path, Outcome.UNAVAILABLE, Optional.empty()));
            throw reject(kind == AttemptKind.MODULE ? Reason.MISSING_MODULE_POM : Reason.MISSING_PARENT_POM, path);
        }
        return new Source("workspace:" + path, Optional.of(path), input, requester, kind, Optional.empty());
    }

    SecurePomReader.Rejected reject(Reason reason, String subject) {
        problems.add(new Problem(reason, subject, reason == Reason.UNSUPPORTED_ACTIVATION
                ? Requirement.ANALYSIS_CONFIGURATION : Requirement.BUILD_MODEL, List.copyOf(used.values())));
        return new SecurePomReader.Rejected(reason);
    }

    Model inspect(Source source) throws SecurePomReader.Rejected {
        PomEvidence evidence = new PomEvidence(source.id, source.input.digest());
        used.put(source.id, evidence);
        try {
            if (++modelReads > request.policy().maxModelReads()) throw new SecurePomReader.Rejected(Reason.MODEL_READ_LIMIT);
            if (source.input.size() > request.policy().maxPomBytes()) throw new SecurePomReader.Rejected(Reason.INPUT_LIMIT);
            Model model = SecurePomReader.read(source.input.bytes());
            if (source.coordinate.isPresent()) validateCoordinate(model, source.coordinate.orElseThrow());
            checkProfiles(model, source.id, evidence);
            if (model.getBuild() != null && (!model.getBuild().getExtensions().isEmpty()
                    || model.getBuild().getPlugins().stream().anyMatch(p -> p.isExtensions()))) {
                problems.add(new Problem(Reason.EXTENSIONS_NOT_LOADED, root,
                        Requirement.AUTHORIZED_EXTENSION_PROVIDER, List.of(evidence)));
            }
            attempts.add(new Attempt(source.requester, source.kind, source.id, Outcome.SUCCEEDED, Optional.of(evidence)));
            return model;
        } catch (SecurePomReader.Rejected exception) {
            boolean denied = exception.reason == Reason.UNSAFE_XML;
            attempts.add(new Attempt(source.requester, source.kind, source.id,
                    denied ? Outcome.DENIED : Outcome.FAILED, Optional.of(evidence)));
            throw reject(exception.reason, source.id);
        }
    }

    private void checkProfiles(Model model, String subject, PomEvidence evidence) {
        boolean hasUnevaluatedActivation = model.getProfiles().stream().anyMatch(profile -> {
            var activation = profile.getActivation();
            boolean explicit = request.policy().activeProfiles().contains(profile.getId())
                    || request.policy().inactiveProfiles().contains(profile.getId());
            return !explicit && activation != null
                    && (activation.getJdk() != null || activation.getOs() != null || activation.getFile() != null);
        });
        if (hasUnevaluatedActivation) {
            // Preserve a deterministic, explicitly qualified inactive baseline. A conditional
            // profile is missing configuration evidence; it is not proof that every other
            // declaration in this POM is unusable.
            problems.add(new Problem(Reason.UNSUPPORTED_ACTIVATION, subject,
                    Requirement.ANALYSIS_CONFIGURATION, List.of(evidence)));
        }
    }

    private static void validateCoordinate(Model model, MavenCoordinate expected) throws SecurePomReader.Rejected {
        var parent = model.getParent();
        String group = model.getGroupId() == null && parent != null ? parent.getGroupId() : model.getGroupId();
        String version = model.getVersion() == null && parent != null ? parent.getVersion() : model.getVersion();
        if (!expected.groupId().equals(group) || !expected.artifactId().equals(model.getArtifactId())
                || !expected.version().equals(version)) {
            throw new SecurePomReader.Rejected(Reason.COORDINATE_MISMATCH);
        }
    }

    ModelResolver resolver() {
        return new ModelResolver() {
            private ModelSource resolve(String group, String artifact, String version, AttemptKind kind) throws UnresolvableModelException {
                MavenCoordinate coordinate;
                try {
                    coordinate = new MavenCoordinate(group, artifact, version);
                } catch (IllegalArgumentException exception) {
                    reject(Reason.UNRESOLVED_EXPRESSION, root);
                    throw new UnresolvableModelException("Non-exact model coordinate", group, artifact, version);
                }
                PomInput input = request.artifactPoms().get(coordinate);
                if (input == null) {
                    attempts.add(new Attempt(root, kind, "artifact:" + coordinate.notation(), Outcome.UNAVAILABLE, Optional.empty()));
                    reject(kind == AttemptKind.PARENT ? Reason.MISSING_PARENT_POM : Reason.MISSING_IMPORT_BOM, root);
                    throw new UnresolvableModelException("Model is absent from the supplied bundle", group, artifact, version);
                }
                return new Source("artifact:" + coordinate.notation(), Optional.empty(), input, root, kind, Optional.of(coordinate));
            }

            @Override public ModelSource resolveModel(String group, String artifact, String version) throws UnresolvableModelException {
                return resolve(group, artifact, version, AttemptKind.IMPORT_BOM);
            }
            @Override public ModelSource resolveModel(Parent parent) throws UnresolvableModelException {
                return resolve(parent.getGroupId(), parent.getArtifactId(), parent.getVersion(), AttemptKind.PARENT);
            }
            @Override public ModelSource resolveModel(org.apache.maven.model.Dependency dependency) throws UnresolvableModelException {
                return resolve(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion(), AttemptKind.IMPORT_BOM);
            }
            // Repository declarations are data only. This adapter has no transport or local-cache access.
            @Override public void addRepository(Repository repository) {}
            @Override public void addRepository(Repository repository, boolean replace) {}
            @Override public ModelResolver newCopy() { return resolver(); }
        };
    }

    final class Source implements ModelSource2 {
        private final String id;
        private final Optional<String> path;
        private final PomInput input;
        private final String requester;
        private final AttemptKind kind;
        private final Optional<MavenCoordinate> coordinate;

        private Source(String id, Optional<String> path, PomInput input, String requester,
                AttemptKind kind, Optional<MavenCoordinate> coordinate) {
            this.id = id;
            this.path = path;
            this.input = input;
            this.requester = requester;
            this.kind = kind;
            this.coordinate = coordinate;
        }

        @Override public InputStream getInputStream() throws IOException {
            inspect(this);
            return new ByteArrayInputStream(input.bytes());
        }
        @Override public String getLocation() { return id; }
        @Override public URI getLocationURI() {
            try {
                return new URI("memory", null, "/" + id, null);
            } catch (URISyntaxException exception) {
                throw new IllegalStateException("Invalid logical model URI", exception);
            }
        }
        @Override public ModelSource2 getRelatedSource(String relative) {
            if (path.isEmpty() || relative.isEmpty()) return null;
            String target;
            try {
                if (relative.contains("${")) {
                    reject(Reason.UNRESOLVED_EXPRESSION, id);
                    return null;
                }
                target = PomPaths.asPom(PomPaths.resolve(path.orElseThrow(), relative), request.workspacePoms());
            } catch (IllegalArgumentException exception) {
                // A relative-parent hint may leave this inventory. Record the denial, then let
                // Maven request the exact supplied artifact; only an unsatisfied lookup is a gap.
                attempts.add(new Attempt(id, AttemptKind.PARENT, "blocked-relative-parent", Outcome.DENIED, Optional.empty()));
                return null;
            }
            if (!request.workspacePoms().containsKey(target)) {
                // A local miss may still be satisfied by an exact supplied artifact POM.
                attempts.add(new Attempt(id, AttemptKind.PARENT, "workspace:" + target, Outcome.UNAVAILABLE, Optional.empty()));
                return null;
            }
            try {
                return workspace(target, id, AttemptKind.PARENT);
            } catch (SecurePomReader.Rejected exception) {
                return null;
            }
        }
    }
}
