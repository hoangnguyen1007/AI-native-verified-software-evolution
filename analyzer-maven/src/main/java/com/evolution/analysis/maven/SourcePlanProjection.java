package com.evolution.analysis.maven;

import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.buildmodel.BuildModelResult.PomEvidence;
import com.evolution.analysis.buildmodel.SourcePlanModel.*;
import com.evolution.analysis.contract.identity.ModuleIdentity;
import java.util.*;
import org.apache.maven.model.*;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/** Candidate declarations only; no target lifecycle, file access or ambient compiler defaults. */
final class SourcePlanProjection {
    private static final String COMPILER = "org.apache.maven.plugins:maven-compiler-plugin";
    private static final Set<String> SETTINGS = Set.of("source", "target", "release", "testSource", "testTarget",
            "testRelease", "encoding", "enablePreview", "generatedSourcesDirectory", "generatedTestSourcesDirectory");
    private final Model model;
    private final String pom;
    private final BuildModelRequest request;
    private final List<PomEvidence> inputs;
    private final Plugin compiler;
    private final List<PluginDeclaration> plugins;

    private SourcePlanProjection(Model model, String pom, BuildModelRequest request, List<PomEvidence> inputs) {
        this.model = model; this.pom = pom; this.request = request; this.inputs = inputs;
        var active = new LinkedHashMap<String, Plugin>();
        model.getBuild().getPlugins().forEach(p -> active.put(p.getKey(), p));
        var managed = new LinkedHashMap<String, Plugin>();
        if (model.getBuild().getPluginManagement() != null) {
            model.getBuild().getPluginManagement().getPlugins().forEach(p -> managed.put(p.getKey(), p));
        }
        compiler = active.getOrDefault(COMPILER, managed.get(COMPILER));
        var declarations = new ArrayList<PluginDeclaration>();
        active.values().forEach(p -> declarations.add(plugin(p, false)));
        managed.forEach((key, p) -> { if (!active.containsKey(key)) declarations.add(plugin(p, true)); });
        plugins = List.copyOf(declarations);
    }

    static SourcePlanModel project(Model model, String pom, BuildModelRequest request, List<PomEvidence> inputs) {
        var projection = new SourcePlanProjection(model, pom, request, inputs);
        return new SourcePlanModel(List.of(projection.sourceSet(Kind.MAIN), projection.sourceSet(Kind.TEST)), projection.plugins);
    }

    private SourceSetPlan sourceSet(Kind kind) {
        boolean test = kind == Kind.TEST;
        String executionId = test ? "default-testCompile" : "default-compile";
        var gaps = new ArrayList<Gap>();
        Xpp3Dom config = compiler == null ? null : dom(compiler.getConfiguration());
        if (compiler != null) {
            for (var execution : compiler.getExecutions()) {
                if (executionId.equals(execution.getId())) {
                    Xpp3Dom override = dom(execution.getConfiguration());
                    if (override != null) config = Xpp3Dom.mergeXpp3Dom(new Xpp3Dom(override), config == null ? null : new Xpp3Dom(config));
                    if (execution.getPhase() != null || execution.getGoals().stream().anyMatch(g -> !g.equals(test ? "testCompile" : "compile"))) {
                        gaps.add(Gap.ADDITIONAL_COMPILER_EXECUTION);
                    }
                } else if (!Set.of("default-compile", "default-testCompile").contains(execution.getId())) {
                    gaps.add(Gap.ADDITIONAL_COMPILER_EXECUTION);
                }
            }
            if (compiler.getVersion() == null || !compiler.getVersion().matches("3\\.\\d+(?:\\.\\d+)?")) {
                gaps.add(Gap.UNSUPPORTED_COMPILER_CONFIGURATION);
            }
        }
        if (config != null && Arrays.stream(config.getChildren()).anyMatch(c -> !SETTINGS.contains(c.getName()))) {
            gaps.add(Gap.UNSUPPORTED_COMPILER_CONFIGURATION);
        }
        if (plugins.stream().anyMatch(p -> !p.managementOnly() && !p.coordinate().equals(COMPILER))
                || !model.getBuild().getExtensions().isEmpty()
                || model.getBuild().getPlugins().stream().anyMatch(Plugin::isExtensions)) {
            gaps.add(Gap.PLUGIN_EFFECTS_NOT_EVALUATED);
        }
        if (!Set.of("jar", "war", "pom", "maven-plugin", "ejb", "rar").contains(model.getPackaging())) {
            gaps.add(Gap.PACKAGING_LIFECYCLE_NOT_EVALUATED);
        }

        var settings = new TreeMap<String, Setting>();
        for (String name : List.of("source", "target", "release", "testSource", "testTarget", "testRelease")) {
            settings.put(name, level(parameter(config, name, "maven.compiler." + name, null), name.endsWith("elease")));
        }
        Setting preview = parameter(config, "enablePreview", "maven.compiler.enablePreview", null);
        if (preview.value().isPresent() && !Set.of("true", "false").contains(preview.value().get())) preview = invalid(preview, Status.INVALID);
        settings.put("enablePreview", preview);
        var properties = new TreeMap<String, String>();
        model.getProperties().forEach((k, v) -> properties.put(k.toString(), v.toString()));
        properties.putAll(request.policy().userProperties());
        properties.forEach((key, value) -> {
            if ((key.startsWith("maven.compiler.") && !SETTINGS.contains(key.substring("maven.compiler.".length())))
                    || Set.of("maven.main.skip", "maven.test.skip").contains(key)) {
                Setting declaration = request.policy().userProperties().containsKey(key)
                        ? setting("userProperties." + key, value, Origin.USER_PROPERTY) : modelSetting("properties." + key, value);
                settings.put("property:" + key, invalid(declaration, Status.UNSUPPORTED));
                gaps.add(Gap.UNSUPPORTED_COMPILER_CONFIGURATION);
            }
        });
        Setting release = test ? prefer(settings.get("testRelease"), settings.get("release")) : settings.get("release");
        Setting source = test ? prefer(settings.get("testSource"), settings.get("source")) : settings.get("source");
        Setting syntax = prefer(release, source);
        Setting target = test ? prefer(settings.get("testTarget"), settings.get("target")) : settings.get("target");
        Setting encoding = parameter(config, "encoding", "encoding", "project.build.sourceEncoding");
        if (encoding.value().isPresent()) {
            String name = encoding.value().get();
            if (!name.matches("[A-Za-z0-9][A-Za-z0-9+._:-]*")) encoding = invalid(encoding, Status.INVALID);
            else {
                String canonical = switch (name.toUpperCase(Locale.ROOT)) {
                    case "UTF-8", "UTF8" -> "UTF-8";
                    case "UTF-16", "UTF16" -> "UTF-16";
                    case "UTF-16BE" -> "UTF-16BE";
                    case "UTF-16LE" -> "UTF-16LE";
                    case "ISO-8859-1", "ISO_8859_1", "ISO_8859-1", "LATIN1" -> "ISO-8859-1";
                    case "US-ASCII", "ASCII" -> "US-ASCII";
                    default -> null;
                };
                encoding = canonical == null ? invalid(encoding, Status.UNSUPPORTED) : withValue(encoding, canonical);
            }
        }
        if (encoding.status() == Status.UNSUPPORTED) gaps.add(Gap.UNSUPPORTED_ENCODING);
        settings.put("encoding", encoding);

        Build build = model.getBuild();
        String rootField = test ? "testSourceDirectory" : "sourceDirectory";
        String root = test ? build.getTestSourceDirectory() : build.getSourceDirectory();
        var roots = new ArrayList<Setting>();
        // Super-POM defaults do not establish a compile source set for an aggregator.
        if (!model.getPackaging().equals("pom") || declared(build, rootField)) roots.add(path(buildSetting(rootField, root), gaps));
        var resources = new ArrayList<Setting>();
        if (!model.getPackaging().equals("pom")) {
            var declarations = test ? build.getTestResources() : build.getResources();
            for (int i = 0; i < declarations.size(); i++) resources.add(path(modelSetting("build."
                    + (test ? "testResources" : "resources") + "[" + i + "].directory", declarations.get(i).getDirectory()), gaps));
        }
        Setting output = path(buildSetting(test ? "testOutputDirectory" : "outputDirectory",
                test ? build.getTestOutputDirectory() : build.getOutputDirectory()), gaps);
        String generatedName = test ? "generatedTestSourcesDirectory" : "generatedSourcesDirectory";
        Setting generated = parameter(config, generatedName, null, null);
        if (generated.status() == Status.UNSPECIFIED) generated = convention("compiler." + generatedName,
                "${project.build.directory}/" + (test ? "generated-test-sources/test-annotations" : "generated-sources/annotations"));
        var hints = roots.isEmpty() ? List.<Setting>of() : List.of(path(generated, gaps));
        if (!hints.isEmpty()) gaps.add(Gap.GENERATED_SOURCES_NOT_ACQUIRED);
        if (syntax.value().isEmpty() && !roots.isEmpty()) gaps.add(Gap.MISSING_SOURCE_LEVEL);
        if (release.value().isEmpty() && !roots.isEmpty()) gaps.add(Gap.MISSING_PLATFORM_RELEASE);
        if (encoding.value().isEmpty() && !roots.isEmpty()) gaps.add(Gap.MISSING_ENCODING);
        settings.values().forEach(s -> gap(s, gaps));
        gap(output, gaps);
        Setting otherRoot = path(modelSetting("build.otherSourceDirectory", test ? build.getSourceDirectory() : build.getTestSourceDirectory()), new ArrayList<>());
        if (roots.stream().anyMatch(r -> r.value().isPresent() && otherRoot.value().isPresent()
                && overlaps(r.value().get(), otherRoot.value().get()))) gaps.add(Gap.OVERLAPPING_SOURCE_ROOTS);
        return new SourceSetPlan(ModuleIdentity.from(request.snapshot().repository(), PomPaths.directory(pom)), kind,
                roots, resources, output, settings, syntax, prefer(release, target), release, encoding, hints, gaps);
    }

    private static boolean overlaps(String left, String right) {
        return left.equals(".") || right.equals(".") || left.equals(right) || left.startsWith(right + "/") || right.startsWith(left + "/");
    }

    private Setting buildSetting(String field, String expression) {
        return declared(model.getBuild(), field) ? modelSetting("build." + field, expression) : convention("build." + field, expression);
    }

    private boolean declared(Build build, String field) {
        InputLocation location = build.getLocation(field);
        return location != null && location.getSource() != null
                && inputs.stream().anyMatch(input -> input.logicalId().equals(location.getSource().getLocation()));
    }

    /** Explicit goal configuration wins; user property supplies the parameter's expression default. */
    private Setting parameter(Xpp3Dom config, String name, String property, String fallbackProperty) {
        Xpp3Dom child = config == null ? null : config.getChild(name);
        if (child != null) {
            Setting result = modelSetting("compiler.configuration." + name, child.getValue() == null ? "" : child.getValue());
            return child.getChildCount() == 0 && config.getChildren(name).length == 1 && child.getAttributeNames().length == 0
                    ? result : invalid(result, Status.UNSUPPORTED);
        }
        if (property != null && request.policy().userProperties().containsKey(property)) {
            return setting("userProperties." + property, request.policy().userProperties().get(property), Origin.USER_PROPERTY);
        }
        if (property != null && model.getProperties().containsKey(property)) return modelSetting("properties." + property, model.getProperties().getProperty(property));
        if (fallbackProperty != null && request.policy().userProperties().containsKey(fallbackProperty)) {
            return setting("userProperties." + fallbackProperty, request.policy().userProperties().get(fallbackProperty), Origin.USER_PROPERTY);
        }
        if (fallbackProperty != null && model.getProperties().containsKey(fallbackProperty)) return modelSetting("properties." + fallbackProperty, model.getProperties().getProperty(fallbackProperty));
        return new Setting("compiler." + name, Optional.empty(), Optional.empty(), Status.UNSPECIFIED, Origin.ABSENT, List.of());
    }

    private Setting modelSetting(String selector, String expression) { return setting(selector, expression, Origin.EFFECTIVE_MODEL); }
    private Setting convention(String selector, String expression) { return setting(selector, expression, Origin.MAVEN_CONVENTION); }
    private Setting setting(String selector, String expression, Origin origin) {
        if (expression == null) return new Setting(selector, Optional.empty(), Optional.empty(), Status.UNSPECIFIED, Origin.ABSENT, List.of());
        Status status = expression.isBlank() ? Status.INVALID : expression.contains("${") ? Status.UNRESOLVED
                : origin == Origin.MAVEN_CONVENTION ? Status.DEFAULT : Status.DECLARED;
        return new Setting(selector, Optional.of(expression), status == Status.DECLARED || status == Status.DEFAULT
                ? Optional.of(expression) : Optional.empty(), status, origin,
                origin == Origin.EFFECTIVE_MODEL || origin == Origin.MAVEN_CONVENTION ? inputs : List.of());
    }

    private Setting path(Setting setting, List<Gap> gaps) {
        if (setting.expression().isEmpty()) return setting;
        if (setting.status() == Status.UNSUPPORTED) { gap(setting, gaps); return setting; }
        String expression = setting.expression().get().replace('\\', '/');
        String expanded = expression.replace("${project.build.directory}", Objects.toString(model.getBuild().getDirectory(), "${project.build.directory}"));
        expanded = expanded.replace('\\', '/');
        if (expanded.equals("${project.basedir}") || expanded.equals("${basedir}")) expanded = ".";
        for (String prefix : List.of("${project.basedir}/", "${basedir}/")) {
            if (expanded.startsWith(prefix)) expanded = expanded.substring(prefix.length());
        }
        Setting result;
        if (expanded.contains("${")) result = invalid(setting, Status.UNRESOLVED);
        else {
            try { result = withValue(setting, PomPaths.resolve(pom, expanded)); }
            catch (IllegalArgumentException ex) { result = invalid(setting, Status.INVALID); gaps.add(Gap.UNSAFE_PATH); }
        }
        gap(result, gaps);
        return result;
    }

    private static Setting level(Setting setting, boolean release) {
        if (setting.value().isEmpty()) return setting;
        String raw = setting.value().get();
        String normalized = !release && raw.matches("1\\.[1-8]") ? raw.substring(2) : raw;
        if (!normalized.matches("[1-9][0-9]{0,2}")) return invalid(setting, Status.INVALID);
        return withValue(setting, normalized);
    }
    private static Setting prefer(Setting primary, Setting fallback) { return primary.status() == Status.UNSPECIFIED ? fallback : primary; }
    private static Setting invalid(Setting s, Status status) { return new Setting(s.selector(), s.expression(), Optional.empty(), status, s.origin(), s.inputs()); }
    private static Setting withValue(Setting s, String value) {
        return new Setting(s.selector(), s.expression(), Optional.of(value), s.origin() == Origin.MAVEN_CONVENTION ? Status.DEFAULT : Status.DECLARED, s.origin(), s.inputs());
    }
    private static void gap(Setting setting, List<Gap> gaps) {
        if (setting.status() == Status.UNRESOLVED) gaps.add(Gap.UNRESOLVED_SETTING);
        if (setting.status() == Status.INVALID) gaps.add(Gap.INVALID_SETTING);
        if (setting.status() == Status.UNSUPPORTED) gaps.add(setting.selector().endsWith(".encoding") || setting.selector().endsWith(".sourceEncoding")
                ? Gap.UNSUPPORTED_ENCODING : Gap.UNSUPPORTED_COMPILER_CONFIGURATION);
    }
    private PluginDeclaration plugin(Plugin plugin, boolean managementOnly) {
        return new PluginDeclaration(plugin.getKey(), Optional.ofNullable(plugin.getVersion()), managementOnly,
                configuration(plugin.getConfiguration()), plugin.getExecutions().stream().map(e -> new Execution(e.getId(),
                Optional.ofNullable(e.getPhase()), e.getGoals(), configuration(e.getConfiguration()))).toList(), inputs);
    }
    private static Xpp3Dom dom(Object value) { return value instanceof Xpp3Dom xml ? xml : null; }
    private static Optional<Configuration> configuration(Object value) { return Optional.ofNullable(dom(value)).map(SourcePlanProjection::configuration); }
    private static Configuration configuration(Xpp3Dom xml) {
        var attributes = new TreeMap<String, String>();
        for (String name : xml.getAttributeNames()) attributes.put(name, xml.getAttribute(name));
        return new Configuration(xml.getName(), Optional.ofNullable(xml.getValue()), attributes,
                Arrays.stream(xml.getChildren()).map(SourcePlanProjection::configuration).toList());
    }
}
