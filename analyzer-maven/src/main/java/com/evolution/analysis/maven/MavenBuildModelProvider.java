package com.evolution.analysis.maven;

import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.buildmodel.BuildModelResult.*;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.source.ModuleDescriptor;
import java.util.*;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.*;
import org.apache.maven.model.profile.DefaultProfileSelector;
import org.apache.maven.model.profile.DefaultProfileActivationContext;
import org.apache.maven.model.profile.ProfileSelector;
import org.apache.maven.model.profile.activation.PropertyProfileActivator;

/** Passive in-memory effective models. No Maven session, container, transport or lifecycle exists here. */
public final class MavenBuildModelProvider implements BuildModelProvider {
    public static final VersionedIdentifier VERSION = new VersionedIdentifier("build.maven-model", "3.9.16-m3.1");

    @Override
    public BuildModelResult build(BuildModelRequest request) {
        return new Run(Objects.requireNonNull(request)).build();
    }

    private static final class Run {
        private final BuildModelRequest request;
        private final List<ModuleModel> modules = new ArrayList<>();
        private final List<Problem> problems = new ArrayList<>();
        private final List<Attempt> attempts = new ArrayList<>();
        private final Set<String> visited = new HashSet<>();
        private final Set<String> moduleDirectories = new HashSet<>();

        private Run(BuildModelRequest request) { this.request = request; }

        private BuildModelResult build() {
            var pending = new ArrayDeque<Pending>();
            pending.add(new Pending(request.rootPom(), Optional.empty(), Set.of()));
            while (!pending.isEmpty()) {
                Pending next = pending.removeFirst();
                if (next.ancestors.contains(next.path)) {
                    problem(Reason.MODULE_CYCLE, next.path);
                    continue;
                }
                if (!visited.add(next.path)) {
                    problem(Reason.DUPLICATE_MODULE, next.path);
                    continue;
                }
                if (!moduleDirectories.add(PomPaths.directory(next.path))) {
                    problem(Reason.DUPLICATE_MODULE, next.path);
                    continue;
                }
                var context = new ModelInputs(request, next.path, problems, attempts);
                var descriptor = ModuleDescriptor.create(request.snapshot().repository(), PomPaths.directory(next.path), next.path);
                Optional<EffectivePom> effective = Optional.empty();
                Model raw = null;
                try {
                    if (visited.size() > request.policy().maxPomCount()
                            || (long) request.workspacePoms().size() + request.artifactPoms().size() > request.policy().maxPomCount()) {
                        throw context.reject(Reason.INPUT_LIMIT, next.path);
                    }
                    var source = context.workspace(next.path, next.aggregator.orElse(next.path), AttemptKind.MODULE);
                    raw = context.inspect(source);
                    var policy = request.policy();
                    var properties = new Properties();
                    properties.putAll(policy.userProperties());
                    var modelRequest = new DefaultModelBuildingRequest()
                            .setModelSource(source)
                            .setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MAVEN_3_1)
                            .setProcessPlugins(false)
                            .setLocationTracking(true)
                            .setSystemProperties(new Properties())
                            .setUserProperties(properties)
                            .setActiveProfileIds(policy.activeProfiles())
                            .setInactiveProfileIds(policy.inactiveProfiles())
                            .setModelResolver(context.resolver());
                    var builder = new DefaultModelBuilderFactory() {
                        @Override protected ProfileSelector newProfileSelector() {
                            var selector = new DefaultProfileSelector().addProfileActivator(new PropertyProfileActivator());
                            // Maven creates fresh import requests. Keep the declared policy for BOMs as well.
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
                    var result = builder.build(modelRequest);
                    if (!result.getProblems().isEmpty()) problem(Reason.MODEL_WARNING, next.path);
                    try {
                        effective = Optional.of(project(result.getEffectiveModel(), result, context));
                    } catch (IllegalArgumentException exception) {
                        problem(Reason.INVALID_MODEL, next.path);
                    }
                } catch (SecurePomReader.Rejected exception) {
                    // The input boundary already recorded the typed reason and attempted evidence.
                } catch (ModelBuildingException exception) {
                    problem(Reason.INVALID_MODEL, next.path);
                    // Recovered models may explain failure; they never become effective output.
                    Model failed = exception.getResult() == null ? null : exception.getResult().getEffectiveModel();
                    if (failed != null && hasDependencyExpression(failed)) {
                        problem(Reason.UNRESOLVED_EXPRESSION, next.path);
                    }
                }
                modules.add(new ModuleModel(descriptor, next.path, next.aggregator, effective));
                // Failed models retain discoverable literal raw modules and healthy siblings.
                List<String> children = effective.map(EffectivePom::declaredModules)
                        .orElse(raw == null ? List.of() : raw.getModules());
                var ancestors = new HashSet<>(next.ancestors);
                ancestors.add(next.path);
                for (String child : children) {
                    if (child.contains("${")) {
                        problem(Reason.UNRESOLVED_EXPRESSION, next.path);
                        continue;
                    }
                    try {
                        String path = PomPaths.asPom(PomPaths.resolve(next.path, child), request.workspacePoms());
                        pending.addLast(new Pending(path, Optional.of(next.path), Set.copyOf(ancestors)));
                    } catch (IllegalArgumentException exception) {
                        problem(Reason.PATH_OUTSIDE_WORKSPACE, next.path);
                    }
                }
            }
            var coordinates = new HashMap<MavenCoordinate, String>();
            for (ModuleModel module : modules) {
                module.effectivePom().ifPresent(pom -> {
                    String previous = coordinates.putIfAbsent(pom.coordinate(), module.pomPath());
                    if (previous != null) {
                        problem(Reason.DUPLICATE_COORDINATE, previous);
                        problem(Reason.DUPLICATE_COORDINATE, module.pomPath());
                    }
                });
            }
            return new BuildModelResult(BuildModelResult.SCHEMA, request.identity(), VERSION, modules, problems, attempts,
                    BuildModelResult.DECLARATIVE_LIMITATIONS);
        }

        private EffectivePom project(Model model, ModelBuildingResult result, ModelInputs context) {
            var coordinate = new MavenCoordinate(model.getGroupId(), model.getArtifactId(), model.getVersion());
            var properties = new TreeMap<String, String>();
            model.getProperties().forEach((key, value) -> properties.put(key.toString(), value.toString()));
            if (model.getBuild() != null && (!model.getBuild().getExtensions().isEmpty()
                    || model.getBuild().getPlugins().stream().anyMatch(plugin -> plugin.isExtensions()))) {
                problems.add(new Problem(Reason.EXTENSIONS_NOT_LOADED, context.root,
                        Requirement.AUTHORIZED_EXTENSION_PROVIDER, List.copyOf(context.used.values())));
            }
            if (properties.values().stream().anyMatch(value -> value.contains("${")) || hasDependencyExpression(model)) {
                problem(Reason.UNRESOLVED_EXPRESSION, context.root);
            }
            var profiles = new TreeSet<String>();
            for (String modelId : result.getModelIds()) {
                result.getActivePomProfiles(modelId).forEach(profile -> profiles.add(profile.getId()));
            }
            return new EffectivePom(coordinate, model.getPackaging(), model.getModules(),
                    model.getDependencies().stream().map(Run::dependency).toList(),
                    model.getDependencyManagement() == null ? List.of() : model.getDependencyManagement().getDependencies().stream().map(Run::dependency).toList(),
                    properties, List.copyOf(profiles), List.copyOf(context.used.values()));
        }

        private static boolean hasDependencyExpression(Model model) {
            return model.getDependencies().stream().anyMatch(Run::hasExpression)
                    || model.getDependencyManagement() != null
                    && model.getDependencyManagement().getDependencies().stream().anyMatch(Run::hasExpression);
        }

        private static boolean hasExpression(org.apache.maven.model.Dependency dependency) {
            return java.util.stream.Stream.of(dependency.getGroupId(), dependency.getArtifactId(),
                    dependency.getVersion(), dependency.getClassifier(), dependency.getType(), dependency.getScope())
                    .filter(Objects::nonNull).anyMatch(value -> value.contains("${"));
        }

        private static Dependency dependency(org.apache.maven.model.Dependency input) {
            return new Dependency(input.getGroupId(), input.getArtifactId(), Objects.toString(input.getVersion(), ""),
                    input.getType(), Objects.toString(input.getClassifier(), ""), Objects.toString(input.getScope(), "compile"),
                    input.isOptional(), input.getExclusions().stream().map(e -> e.getGroupId() + ":" + e.getArtifactId()).toList());
        }

        private void problem(Reason reason, String path) {
            PomInput input = request.workspacePoms().get(path);
            problems.add(new Problem(reason, path, Requirement.BUILD_MODEL,
                    input == null ? List.of() : List.of(new PomEvidence("workspace:" + path, input.digest()))));
        }

        private record Pending(String path, Optional<String> aggregator, Set<String> ancestors) {}
    }
}
