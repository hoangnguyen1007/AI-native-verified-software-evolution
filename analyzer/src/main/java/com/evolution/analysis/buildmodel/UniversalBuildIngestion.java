package com.evolution.analysis.buildmodel;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.ingestion.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.SourceInput;
import java.util.*;
import static com.evolution.analysis.ingestion.IngestionEvidence.Reason.*;
import static com.evolution.analysis.buildmodel.SourcePlanModel.*;

/**
 * Passive router with an injected Maven provider; no target script, plugin,
 * process or network execution.
 */
public final class UniversalBuildIngestion {
    public static final VersionedIdentifier PROVIDER = new VersionedIdentifier("build.universal-ingestion",
            "m8.4-gradle-platforms-v3");

    public record Policy(Optional<Integer> syntaxLevel, Optional<Integer> platformRelease, Optional<String> encoding,
            int maxFiles, int maxCharacters, int maxTokens, int maxDepth) {
        public Policy {
            Objects.requireNonNull(syntaxLevel);
            Objects.requireNonNull(platformRelease);
            Objects.requireNonNull(encoding);
            if (maxFiles < 1 || maxCharacters < 1 || maxTokens < 1 || maxDepth < 1 || maxDepth > 128
                    || syntaxLevel.filter(v -> v < 1).isPresent() || platformRelease.filter(v -> v < 1).isPresent())
                throw new IllegalArgumentException("Invalid build ingestion limits");
            encoding.ifPresent(v -> ContractChecks.text(v, "build encoding"));
        }

        public static Policy defaults() {
            return new Policy(Optional.empty(), Optional.empty(), Optional.empty(), 16_384, 10_000_000, 2_000_000, 128);
        }
    }

    public UniversalBuildModel ingest(RepositoryInputs inputs, Policy policy, Optional<BuildModelProvider> maven,
            Map<MavenCoordinate, PomInput> externalPoms, BuildModelPolicy mavenPolicy) {
        var external = new TreeMap<String, ContentDigest>();
        externalPoms.forEach((k, v) -> external.put(k.notation(), v.digest()));
        var identity = IngestionEvidence.digest(List.of(PROVIDER, inputs.identity(), policy, external, mavenPolicy));
        var issues = new ArrayList<IngestionEvidence.Issue>();
        var modules = new ArrayList<UniversalBuildModel.Module>();
        var mavenResults = new ArrayList<BuildModelResult>();
        Map<String, PomInput> poms = new TreeMap<>();
        Set<String> directories = new TreeSet<>();
        Map<String, List<DeclarativeScript.Statement>> scripts = new TreeMap<>();
        int fileCount = 0;
        for (var file : inputs.snapshot().files()) {
            String path = file.path(), name = filename(path);
            if (!Set.of("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")
                    .contains(name))
                continue;
            if (++fileCount > policy.maxFiles()) {
                issue(issues, INPUT_LIMIT, path, file.contentDigest());
                continue;
            }
            directories.add(directory(path));
            var source = inputs.files().get(path);
            if (source == null) {
                issue(issues, INPUT_UNAVAILABLE, path, file.contentDigest());
                continue;
            }
            if (source.text().length() > policy.maxCharacters()) {
                issue(issues, INPUT_LIMIT, path, file.contentDigest());
                continue;
            }
            if (name.equals("pom.xml"))
                poms.put(path, new PomInput(source.bytes()));
            else
                try {
                    scripts.put(path, DeclarativeScript.parse(source.text(), policy.maxTokens(), policy.maxDepth()));
                } catch (DeclarativeScript.Limit failure) {
                    issue(issues, INPUT_LIMIT, path, file.contentDigest());
                } catch (IllegalArgumentException failure) {
                    issue(issues, MALFORMED_INPUT, path, file.contentDigest());
                }
        }
        Map<String, Map<String, GradleVersionCatalog.Catalog>> catalogs = new TreeMap<>(
                UniversalBuildIngestion::compareDirectories);
        for (var file : inputs.snapshot().files()) {
            String path = file.path(), root = null, catalogName = null;
            if (path.endsWith(".versions.toml")) {
                if (path.startsWith("gradle/")) {
                    String tail = path.substring("gradle/".length());
                    if (!tail.contains("/")) {
                        root = ".";
                        catalogName = tail.substring(0, tail.length() - ".versions.toml".length());
                    }
                } else {
                    int idx = path.lastIndexOf("/gradle/");
                    if (idx > 0) {
                        String tail = path.substring(idx + "/gradle/".length());
                        if (!tail.contains("/")) {
                            root = path.substring(0, idx);
                            catalogName = tail.substring(0, tail.length() - ".versions.toml".length());
                        }
                    }
                }
            }
            if (root == null || catalogName == null || !catalogName.matches("[A-Za-z][A-Za-z0-9_]*"))
                continue;
            if (++fileCount > policy.maxFiles()) {
                issue(issues, INPUT_LIMIT, path, file.contentDigest());
                continue;
            }
            var source = inputs.files().get(path);
            if (source == null) {
                issue(issues, INPUT_UNAVAILABLE, path, file.contentDigest());
                continue;
            }
            if (source.text().length() > policy.maxCharacters()) {
                issue(issues, INPUT_LIMIT, path, file.contentDigest());
                continue;
            }
            var catalog = GradleVersionCatalog.parse(path, source.text(), file.contentDigest(), policy.maxTokens());
            catalogs.computeIfAbsent(root, ignored -> new TreeMap<>()).put(catalogName, catalog);
            issues.addAll(catalog.issues());
        }
        int[] buildSrcCount = { fileCount };
        var buildSrcConstants = new TreeMap<String, Map<String, String>>(UniversalBuildIngestion::compareDirectories);
        var buildSrcEvidence = new TreeMap<String, List<BuildModelResult.PomEvidence>>(
                UniversalBuildIngestion::compareDirectories);
        extractBuildSrcConstants(inputs, buildSrcCount, policy, issues, buildSrcConstants, buildSrcEvidence);
        fileCount = buildSrcCount[0];
        Map<String, Map<String, String>> directoryProperties = new TreeMap<>(
                UniversalBuildIngestion::compareDirectories);
        Map<String, BuildModelResult.PomEvidence> propertyEvidence = new TreeMap<>();
        for (var file : inputs.snapshot().files())
            if (filename(file.path()).equals("gradle.properties")) {
                String path = file.path();
                if (++fileCount > policy.maxFiles()) {
                    issue(issues, INPUT_LIMIT, path, file.contentDigest());
                    continue;
                }
                var source = inputs.files().get(path);
                if (source == null) {
                    issue(issues, INPUT_UNAVAILABLE, path, file.contentDigest());
                    continue;
                }
                if (source.text().length() > policy.maxCharacters()) {
                    issue(issues, INPUT_LIMIT, path, file.contentDigest());
                    continue;
                }
                try {
                    var loaded = new Properties();
                    loaded.load(new java.io.StringReader(source.text()));
                    var map = new TreeMap<String, String>();
                    loaded.forEach((k, v) -> map.put(String.valueOf(k), String.valueOf(v)));
                    String propDir = directory(path);
                    directoryProperties.put(propDir, map);
                    propertyEvidence.put(propDir, new BuildModelResult.PomEvidence(path, file.contentDigest()));
                } catch (IllegalArgumentException | java.io.IOException failure) {
                    issue(issues, MALFORMED_INPUT, path, file.contentDigest());
                }
            }
        // Literal settings include rows are the project denominator, including projects
        // with no build file.
        Map<String, String> projectNames = new HashMap<>();
        Map<String, UniversalBuildModel.Tool> gradleProjects = new HashMap<>();
        Map<String, BuildModelResult.PomEvidence> settingsEvidence = new HashMap<>();
        Set<String> settingsRoots = new TreeSet<>(UniversalBuildIngestion::compareDirectories);
        Set<String> gradleIncludedChildren = new HashSet<>();
        var settingsEntries = scripts.entrySet().stream()
                .filter(e -> filename(e.getKey()).startsWith("settings."))
                .sorted(Comparator.comparing(e -> directory(e.getKey()), UniversalBuildIngestion::compareDirectories))
                .toList();
        for (var entry : settingsEntries) {
            String root = directory(entry.getKey());
            var statements = entry.getValue();
            // If an ancestor settings script already includes this directory as a
            // subproject
            // and this nested settings script only sets rootProject.name without child
            // includes,
            // keep the subproject part of the enclosing build rather than splitting it out.
            if (gradleIncludedChildren.contains(root) && isTrivialSubprojectSettings(statements)) {
                continue;
            }
            settingsRoots.add(root);
            var settingsTool = entry.getKey().endsWith(".kts") ? UniversalBuildModel.Tool.GRADLE_KOTLIN
                    : UniversalBuildModel.Tool.GRADLE_GROOVY;
            var settingsInput = new BuildModelResult.PomEvidence(entry.getKey(),
                    inputs.files().get(entry.getKey()).document().contentDigest());
            gradleProjects.put(root, settingsTool);
            settingsEvidence.put(root, settingsInput);
            Map<String, List<String>> settingsLists = new LinkedHashMap<>();
            Map<String, String> settingsDirVars = new LinkedHashMap<>();
            for (var statement : statements) {
                if (statement.key().equals("include") && statement.body().isEmpty()
                        && !statement.strings().isEmpty()
                        && statement.syntax().matches("include(?:\\(#(?:,#)*\\)|#(?:,#)*)")) {
                    for (String project : statement.strings())
                        registerIncludedProject(root, project, entry.getKey(), statement.offset(), identity,
                                inputs, settingsTool, settingsInput, policy, issues, directories, projectNames,
                                gradleProjects, settingsEvidence, gradleIncludedChildren);
                } else if (tryCaptureSettingsList(statement, settingsLists)) {
                    // Captured literal list/set variable (e.g. def explicitModules = [ ':app', ...
                    // ])
                } else if (tryCaptureSettingsDirVar(statement, settingsDirVars)) {
                    // Captured directory variable (e.g. File pluginsRoot = new File(rootDir,
                    // 'plugins'))
                } else if (tryListLoopInclude(statement, settingsLists,
                        included -> registerIncludedProject(root, included, entry.getKey(), statement.offset(),
                                identity,
                                inputs, settingsTool, settingsInput, policy, issues, directories, projectNames,
                                gradleProjects, settingsEvidence, gradleIncludedChildren))) {
                    // Resolved static list/set include loop (e.g. for (path in explicitModules) {
                    // include path })
                } else if (trySettingsDirectoryScan(statement, root, settingsDirVars, inputs, settingsTool,
                        settingsInput, policy, issues, directories, projectNames, gradleProjects, settingsEvidence,
                        gradleIncludedChildren, identity)) {
                    // Resolved passive directory discovery loop against captured snapshot build
                    // files
                } else if (statement.syntax().matches("includeBuild(?:\\(#\\)|#)")) {
                    try {
                        if (statement.strings().getFirst().contains("$")) {
                            issue(issues, DYNAMIC_BUILD_LOGIC, entry.getKey() + "#" + statement.offset(),
                                    settingsInput.digest());
                            continue;
                        }
                        String dir = join(root, statement.strings().getFirst());
                        if (directories.size() >= policy.maxFiles() && !directories.contains(dir)) {
                            issue(issues, INPUT_LIMIT, entry.getKey() + "#" + statement.offset(), identity);
                            continue;
                        }
                        directories.add(dir);
                        gradleProjects.putIfAbsent(dir, settingsTool);
                        settingsEvidence.putIfAbsent(dir, settingsInput);
                        if (!statement.body().isEmpty())
                            issue(issues, DYNAMIC_BUILD_LOGIC, entry.getKey() + "#" + statement.offset(),
                                    settingsInput.digest());
                    } catch (IllegalArgumentException failure) {
                        issue(issues, UNSAFE_PATH, entry.getKey() + "#" + statement.offset(),
                                settingsInput.digest());
                    }
                } else if (eachDirInclude(statement)) {
                    try {
                        String folder = statement.strings().getFirst();
                        if (folder.contains("$")) {
                            issue(issues, DYNAMIC_BUILD_LOGIC, entry.getKey() + "#" + statement.offset(),
                                    settingsInput.digest());
                            continue;
                        }
                        scanSettingsSubfolder(root, folder, "", inputs, entry.getKey(), statement.offset(), identity,
                                settingsTool, settingsInput, policy, issues, directories, projectNames,
                                gradleProjects, settingsEvidence, gradleIncludedChildren);
                    } catch (IllegalArgumentException failure) {
                        issue(issues, UNSAFE_PATH, entry.getKey() + "#" + statement.offset(),
                                settingsInput.digest());
                    }
                } else if (projectDirectoryAssignment(statement.syntax())) {
                    // Applied after all include rows, independently of statement order.
                } else if (statement.syntax().equals("rootProject.name=#")
                        && !statement.strings().getFirst().contains("$")
                        && !statement.strings().getFirst().isBlank())
                    projectNames.put(root, statement.strings().getFirst());
                else if (declarativeSettingsStatement(statement)) {
                    // Declarative settings configuration (pluginManagement,
                    // dependencyResolutionManagement, enableFeaturePreview, etc.).
                } else
                    issue(issues, DYNAMIC_BUILD_LOGIC, entry.getKey() + "#" + statement.offset(),
                            inputs.files().get(entry.getKey()).document().contentDigest());
            }
            for (var statement : entry.getValue())
                if (projectDirectoryAssignment(statement.syntax()))
                    try {
                        var strings = statement.strings();
                        String project = strings.getFirst();
                        if (strings.stream().anyMatch(s -> s.contains("$"))) {
                            issue(issues, DYNAMIC_BUILD_LOGIC, entry.getKey() + "#" + statement.offset(),
                                    settingsInput.digest());
                            continue;
                        }
                        if (!project.startsWith(":"))
                            throw new IllegalArgumentException();
                        String oldDir = join(root, project.substring(1).replace(':', '/')),
                                newDir = join(root, strings.get(1));
                        if (!gradleProjects.containsKey(oldDir)) {
                            issue(issues, DYNAMIC_BUILD_LOGIC, entry.getKey() + "#" + statement.offset(),
                                    settingsInput.digest());
                            continue;
                        }
                        if (!oldDir.equals(newDir) && gradleProjects.containsKey(newDir)) {
                            issue(issues, AMBIGUOUS_INPUT, entry.getKey() + "#" + statement.offset(),
                                    settingsInput.digest());
                            continue;
                        }
                        String name = projectNames.getOrDefault(oldDir, filename(oldDir));
                        projectNames.remove(oldDir);
                        gradleProjects.remove(oldDir);
                        settingsEvidence.remove(oldDir);
                        boolean wasChild = gradleIncludedChildren.remove(oldDir);
                        if (!scripts.containsKey(join(oldDir, "build.gradle"))
                                && !scripts.containsKey(join(oldDir, "build.gradle.kts"))
                                && !poms.containsKey(join(oldDir, "pom.xml")))
                            directories.remove(oldDir);
                        directories.add(newDir);
                        projectNames.put(newDir, name);
                        gradleProjects.put(newDir, settingsTool);
                        settingsEvidence.put(newDir, settingsInput);
                        if (wasChild && !newDir.equals(root))
                            gradleIncludedChildren.add(newDir);
                    } catch (IllegalArgumentException failure) {
                        issue(issues, UNSAFE_PATH, entry.getKey() + "#" + statement.offset(),
                                settingsInput.digest());
                    }
        }
        if (directories.isEmpty())
            directories.add(".");
        var orderedDirectories = directories.stream().sorted(UniversalBuildIngestion::compareDirectories).toList();
        Set<String> modeledMaven = new HashSet<>();
        for (String dir : orderedDirectories) {
            String pom = join(dir, "pom.xml"), groovy = join(dir, "build.gradle"),
                    kotlin = join(dir, "build.gradle.kts");
            boolean hasPom = inputs.snapshot().files().stream().anyMatch(f -> f.path().equals(pom));
            boolean hasGroovy = inputs.snapshot().files().stream().anyMatch(f -> f.path().equals(groovy));
            boolean hasKotlin = inputs.snapshot().files().stream().anyMatch(f -> f.path().equals(kotlin));
            boolean useMaven = hasPom && (modeledMaven.contains(pom) || !gradleIncludedChildren.contains(dir)
                    || (!hasGroovy && !hasKotlin));
            if (!useMaven && hasGroovy && hasKotlin)
                issue(issues, AMBIGUOUS_INPUT, dir, identity);
            if (useMaven) {
                if (modeledMaven.contains(pom))
                    continue;
                if (maven.isEmpty()) {
                    issue(issues, INPUT_UNAVAILABLE, pom, identity);
                    continue;
                }
                var result = maven.orElseThrow()
                        .build(new BuildModelRequest(inputs.snapshot(), pom, poms, externalPoms, mavenPolicy));
                mavenResults.add(result);
                for (var module : result.modules()) {
                    modeledMaven.add(module.pomPath());
                    if (module.effectivePom().isEmpty()) {
                        issue(issues, INPUT_UNAVAILABLE, module.pomPath(), result.identity());
                        var evidence = Optional.ofNullable(poms.get(module.pomPath()))
                                .map(p -> List.of(new BuildModelResult.PomEvidence(module.pomPath(), p.digest())))
                                .orElse(List.of());
                        var fallback = new Projection(module.module().path(), policy, evidence, issues, identity,
                                Map.of(), inputs.snapshot().files());
                        modules.add(new UniversalBuildModel.Module(module.module(), UniversalBuildModel.Tool.MAVEN,
                                Optional.of(module.pomPath()),
                                Optional.empty(), fallback.plan(module.module(), UniversalBuildModel.Tool.MAVEN),
                                List.of(), evidence));
                        continue;
                    }
                    var effective = module.effectivePom().orElseThrow();
                    var sourcePlan = withMavenKotlinRoots(effective.sourcePlan(), module.module().path(),
                            inputs.snapshot().files(), effective.inputs());
                    var projected = new UniversalBuildModel.Module(module.module(), UniversalBuildModel.Tool.MAVEN,
                            Optional.of(module.pomPath()),
                            Optional.of(effective.coordinate()), sourcePlan, List.of(), effective.inputs());
                    var prior = modules.stream().filter(
                            candidate -> candidate.descriptor().identity().equals(projected.descriptor().identity()))
                            .findFirst();
                    if (prior.isEmpty())
                        modules.add(projected);
                    else if (!prior.orElseThrow().equals(projected))
                        issue(issues, AMBIGUOUS_INPUT, module.pomPath(), result.identity());
                }
                if (result.hasGaps())
                    for (var problem : result.problems())
                        issue(issues, SOURCE_INCOMPLETE, problem.subject(), result.identity());
                continue;
            }
            Optional<String> build = hasKotlin ? Optional.of(kotlin)
                    : hasGroovy ? Optional.of(groovy) : Optional.empty();
            var tool = hasKotlin ? UniversalBuildModel.Tool.GRADLE_KOTLIN
                    : hasGroovy ? UniversalBuildModel.Tool.GRADLE_GROOVY
                            : gradleProjects.getOrDefault(dir, UniversalBuildModel.Tool.PLAIN_JAVA);
            var moduleCatalogs = new TreeMap<String, GradleVersionCatalog.Catalog>();
            for (var catEntry : catalogs.entrySet()) {
                String catRoot = catEntry.getKey();
                if (catRoot.equals(".") || dir.equals(catRoot) || dir.startsWith(catRoot + "/"))
                    moduleCatalogs.putAll(catEntry.getValue());
            }
            var evidence = new ArrayList<BuildModelResult.PomEvidence>();
            if (settingsEvidence.containsKey(dir))
                evidence.add(settingsEvidence.get(dir));
            build.flatMap(p -> Optional.ofNullable(inputs.files().get(p)))
                    .ifPresent(f -> evidence
                            .add(new BuildModelResult.PomEvidence(f.document().path(), f.document().contentDigest())));
            moduleCatalogs.values().forEach(c -> evidence.add(c.evidence()));
            var descriptor = ModuleDescriptor.create(inputs.snapshot().repository(), dir,
                    projectNames.getOrDefault(dir, dir.equals(".") ? "repository" : filename(dir)));
            var model = new Projection(dir, policy, evidence, issues, identity, moduleCatalogs,
                    inputs.snapshot().files());
            if (Set.of(UniversalBuildModel.Tool.GRADLE_GROOVY, UniversalBuildModel.Tool.GRADLE_KOTLIN).contains(tool)) {
                String gradleRoot = settingsEvidence.containsKey(dir) ? directory(settingsEvidence.get(dir).logicalId())
                        : settingsRoots.stream().filter(r -> r.equals(".") || dir.equals(r) || dir.startsWith(r + "/"))
                                .max(Comparator.comparingInt(String::length)).orElse(".");
                for (var srcRoot : List.of(".", gradleRoot)) {
                    var srcMap = buildSrcConstants.get(srcRoot);
                    if (srcMap != null && !srcMap.isEmpty()) {
                        model.variables.putAll(srcMap);
                        for (var ev : buildSrcEvidence.getOrDefault(srcRoot, List.of())) {
                            if (evidence.stream().noneMatch(e -> e.logicalId().equals(ev.logicalId())))
                                evidence.add(ev);
                        }
                    }
                }
                for (var propEntry : directoryProperties.entrySet()) {
                    String propDir = propEntry.getKey();
                    if (propDir.equals(".") || dir.equals(propDir) || dir.startsWith(propDir + "/")) {
                        model.variables.putAll(propEntry.getValue());
                        var propEv = propertyEvidence.get(propDir);
                        if (propEv != null
                                && evidence.stream().noneMatch(e -> e.logicalId().equals(propEv.logicalId())))
                            evidence.add(propEv);
                    }
                }
                if (!dir.equals(gradleRoot)) {
                    String rootKts = join(gradleRoot, "build.gradle.kts"),
                            rootGroovy = join(gradleRoot, "build.gradle");
                    String rootScript = scripts.containsKey(rootKts) ? rootKts
                            : scripts.containsKey(rootGroovy) ? rootGroovy : null;
                    if (rootScript != null) {
                        var rootStatements = scripts.get(rootScript);
                        var rootVars = Projection.extractRootVariables(rootStatements, model.variables);
                        model.variables.putAll(rootVars);
                        var inherited = rootStatements.stream()
                                .filter(s -> Set.of("allprojects", "subprojects").contains(s.key())
                                        && s.syntax().equals(s.key()) && !s.body().isEmpty())
                                .toList();
                        if (!inherited.isEmpty() || !rootVars.isEmpty()) {
                            var rootFile = inputs.files().get(rootScript);
                            if (rootFile != null && evidence.stream().noneMatch(e -> e.logicalId().equals(rootScript)))
                                evidence.add(new BuildModelResult.PomEvidence(rootFile.document().path(),
                                        rootFile.document().contentDigest()));
                        }
                        for (var block : inherited)
                            model.read(block.body(), "");
                    }
                }
            }
            build.ifPresent(p -> model.read(scripts.getOrDefault(p, List.of()), ""));
            if (Set.of(UniversalBuildModel.Tool.GRADLE_GROOVY, UniversalBuildModel.Tool.GRADLE_KOTLIN).contains(tool)) {
                model.finish(inputs.snapshot().files());
            }
            if (tool == UniversalBuildModel.Tool.PLAIN_JAVA) {
                boolean conventional = inputs.snapshot().files().stream().anyMatch(
                        f -> f.path().startsWith(join(dir, "src/main/java") + "/") && f.path().endsWith(".java")
                                || f.path().startsWith(join(dir, "src/main/kotlin") + "/") && f.path().endsWith(".kt"));
                if (!conventional)
                    model.mainRoots = List.of(inputs.snapshot().files().stream()
                            .anyMatch(f -> f.path().startsWith(join(dir, "src") + "/")
                                    && (f.path().endsWith(".java") || f.path().endsWith(".kt"))) ? join(dir, "src")
                                            : dir);
                else if (Projection.hasFilesUnder(inputs.snapshot().files(), join(dir, "src/main/kotlin"))) {
                    var extra = new ArrayList<>(model.mainRoots);
                    String ktRoot = join(dir, "src/main/kotlin");
                    if (!extra.contains(ktRoot))
                        extra.add(ktRoot);
                    model.mainRoots = List.copyOf(extra);
                }
                if (Projection.hasFilesUnder(inputs.snapshot().files(), join(dir, "src/test/kotlin"))) {
                    var extra = new ArrayList<>(model.testRoots);
                    String ktTest = join(dir, "src/test/kotlin");
                    if (!extra.contains(ktTest))
                        extra.add(ktTest);
                    model.testRoots = List.copyOf(extra);
                }
            }
            Optional<MavenCoordinate> coordinate = Optional.empty();
            if (model.group != null && model.version != null && projectNames.containsKey(dir))
                try {
                    coordinate = Optional.of(new MavenCoordinate(model.group, descriptor.displayName(), model.version));
                } catch (IllegalArgumentException failure) {
                    issue(issues, UNRESOLVED_COORDINATE, dir, identity);
                }
            if (!model.dependencies.isEmpty())
                issue(issues, CLASSPATH_RESOLUTION_REQUIRED, dir, identity);
            modules.add(new UniversalBuildModel.Module(descriptor, tool, build, coordinate,
                    model.plan(descriptor, tool), model.dependencies, evidence));
        }
        var sorted = issues.stream().distinct().sorted(Comparator.comparing(IngestionEvidence.Issue::identity))
                .toList();
        return new UniversalBuildModel(identity, inputs.identity(), modules, mavenResults, sorted,
                IngestionEvidence.gaps(inputs.snapshot().identity(), PROVIDER, identity, sorted));
    }

    private static boolean isTrivialSubprojectSettings(List<DeclarativeScript.Statement> statements) {
        return statements.stream()
                .allMatch(s -> s.syntax().equals("rootProject.name=#") || declarativeSettingsStatement(s));
    }

    private static void registerIncludedProject(
            String root,
            String project,
            String scriptPath,
            int offset,
            ContentDigest identity,
            RepositoryInputs inputs,
            UniversalBuildModel.Tool settingsTool,
            BuildModelResult.PomEvidence settingsInput,
            Policy policy,
            List<IngestionEvidence.Issue> issues,
            Set<String> directories,
            Map<String, String> projectNames,
            Map<String, UniversalBuildModel.Tool> gradleProjects,
            Map<String, BuildModelResult.PomEvidence> settingsEvidence,
            Set<String> gradleIncludedChildren) {
        try {
            if (project.contains("$")) {
                issue(issues, DYNAMIC_BUILD_LOGIC, scriptPath + "#" + offset, identity);
                return;
            }
            String relative = project.replaceFirst("^:", "").replace(':', '/');
            String dir = join(root, relative);
            if (!directories.contains(dir) && directories.size() >= policy.maxFiles()) {
                issue(issues, INPUT_LIMIT, scriptPath + "#" + offset, identity);
                return;
            }
            directories.add(dir);
            projectNames.put(dir,
                    relative.isEmpty() ? filename(root) : relative.substring(relative.lastIndexOf('/') + 1));
            gradleProjects.put(dir, settingsTool);
            settingsEvidence.put(dir, settingsInput);
            if (!dir.equals(root))
                gradleIncludedChildren.add(dir);
            // Gradle also creates intermediate project descriptors for nested project
            // paths.
            for (int slash = relative.indexOf('/'); slash >= 0; slash = relative.indexOf('/', slash + 1)) {
                String parent = join(root, relative.substring(0, slash));
                if (!directories.contains(parent) && directories.size() >= policy.maxFiles()) {
                    issue(issues, INPUT_LIMIT, scriptPath + "#" + offset, identity);
                    break;
                }
                directories.add(parent);
                projectNames.putIfAbsent(parent, filename(parent));
                gradleProjects.putIfAbsent(parent, settingsTool);
                settingsEvidence.putIfAbsent(parent, settingsInput);
                if (!parent.equals(root))
                    gradleIncludedChildren.add(parent);
            }
        } catch (IllegalArgumentException failure) {
            issue(issues, UNSAFE_PATH, scriptPath + "#" + offset,
                    inputs.files().get(scriptPath).document().contentDigest());
        }
    }

    private static boolean tryCaptureSettingsList(DeclarativeScript.Statement statement,
            Map<String, List<String>> lists) {
        String syntax = statement.syntax();
        if (!statement.body().isEmpty())
            return false;
        if ((syntax.matches("(?:val|var|def)[A-Za-z_][A-Za-z0-9_]*=\\[(?:#(?:,#)*)?\\]")
                || syntax.matches(
                        "(?:val|var|def)[A-Za-z_][A-Za-z0-9_]*=(?:listOf|setOf|mutableListOf)\\((?:#(?:,#)*)?\\)"))
                && statement.head().size() >= 3) {
            if (statement.strings().stream().anyMatch(s -> s.contains("$")))
                return false;
            lists.put(statement.head().get(1).value(), List.copyOf(statement.strings()));
            return true;
        }
        if (syntax.matches("(?:val|var|def)[A-Za-z_][A-Za-z0-9_]*=[A-Za-z_][A-Za-z0-9_]*\\.toSet\\(\\)")
                && statement.head().size() >= 4) {
            String src = statement.head().get(3).value();
            if (lists.containsKey(src)) {
                lists.put(statement.head().get(1).value(), lists.get(src));
                return true;
            }
        }
        return false;
    }

    private static boolean tryCaptureSettingsDirVar(DeclarativeScript.Statement statement,
            Map<String, String> dirVars) {
        String syntax = statement.syntax();
        if (!statement.body().isEmpty() || statement.strings().size() != 1)
            return false;
        if (syntax.matches(
                "(?:File|val|var|def)[A-Za-z_][A-Za-z0-9_]*=(?:newFile|File)\\((?:rootDir|settingsDir),#\\)|(?:File|val|var|def)[A-Za-z_][A-Za-z0-9_]*=file\\(#\\)")
                && statement.head().size() >= 3) {
            String folder = statement.strings().getFirst();
            if (!folder.contains("$")) {
                dirVars.put(statement.head().get(1).value(), folder);
                return true;
            }
        }
        return false;
    }

    private static boolean tryListLoopInclude(DeclarativeScript.Statement statement, Map<String, List<String>> lists,
            java.util.function.Consumer<String> onInclude) {
        if (statement.body().isEmpty())
            return false;
        String syntax = statement.syntax();
        String listName = null;
        if (syntax.matches("for\\((?:String|var|def)?[A-Za-z_][A-Za-z0-9_]*in[A-Za-z_][A-Za-z0-9_]*\\)")
                || syntax.matches("for\\((?:String|var|def)?[A-Za-z_][A-Za-z0-9_]*:[A-Za-z_][A-Za-z0-9_]*\\)")) {
            int inIdx = -1;
            for (int i = 0; i < statement.head().size(); i++) {
                String val = statement.head().get(i).value();
                if (val.equals("in") || val.equals(":")) {
                    inIdx = i;
                    break;
                }
            }
            if (inIdx > 0 && inIdx + 1 < statement.head().size()) {
                listName = statement.head().get(inIdx + 1).value();
            }
        } else if (syntax.matches("[A-Za-z_][A-Za-z0-9_]*\\.(?:forEach|each)") && !statement.head().isEmpty()) {
            listName = statement.head().getFirst().value();
        }
        if (listName != null && lists.containsKey(listName)
                && statement.body().stream().anyMatch(child -> child.key().equals("include"))) {
            for (String item : lists.get(listName)) {
                onInclude.accept(item);
            }
            return true;
        }
        return false;
    }

    private static boolean trySettingsDirectoryScan(
            DeclarativeScript.Statement statement,
            String root,
            Map<String, String> dirVars,
            RepositoryInputs inputs,
            UniversalBuildModel.Tool settingsTool,
            BuildModelResult.PomEvidence settingsInput,
            Policy policy,
            List<IngestionEvidence.Issue> issues,
            Set<String> directories,
            Map<String, String> projectNames,
            Map<String, UniversalBuildModel.Tool> gradleProjects,
            Map<String, BuildModelResult.PomEvidence> settingsEvidence,
            Set<String> gradleIncludedChildren,
            ContentDigest identity) {
        String syntax = statement.syntax();
        // Closure definition or opt-in gradleProperty guard in settings.gradle
        if (syntax.matches("(?:def|val|var)[A-Za-z_][A-Za-z0-9_]*=\\{\\}")) {
            return true;
        }
        if (syntax.contains("providers.gradleProperty(")) {
            return true;
        }
        if (syntax.startsWith("if(") && !statement.body().isEmpty()) {
            // Optional includeBuild guarded by gradleProperty empty check: no passive
            // includeBuild added when property is unset.
            if (statement.body().stream()
                    .anyMatch(c -> c.key().equals("includeBuild") || c.syntax().contains("gradleProperty"))) {
                return true;
            }
            // Directory presence guard: if (pluginsRoot.isDirectory()) { for (...) {
            // include ... } }
            String headVar = statement.head().stream().filter(t -> dirVars.containsKey(t.value()))
                    .map(DeclarativeScript.Token::value).findFirst().orElse(null);
            if (headVar != null) {
                scanSettingsSubfolder(root, dirVars.get(headVar), "", inputs, settingsInput.logicalId(),
                        statement.offset(),
                        identity, settingsTool, settingsInput, policy, issues, directories, projectNames,
                        gradleProjects,
                        settingsEvidence, gradleIncludedChildren);
                return true;
            }
        }
        if (syntax.startsWith("for(") && syntax.contains("rootDir.listFiles") && !statement.body().isEmpty()) {
            String prefixFilter = "";
            for (var child : statement.body()) {
                if (child.syntax().contains(".startsWith(#)") && !child.strings().isEmpty()) {
                    prefixFilter = child.strings().getFirst();
                    break;
                }
            }
            if (!prefixFilter.contains("$")) {
                scanSettingsSubfolder(root, ".", prefixFilter, inputs, settingsInput.logicalId(), statement.offset(),
                        identity, settingsTool, settingsInput, policy, issues, directories, projectNames,
                        gradleProjects,
                        settingsEvidence, gradleIncludedChildren);
                return true;
            }
        }
        return false;
    }

    private static void scanSettingsSubfolder(
            String root,
            String folder,
            String namePrefixFilter,
            RepositoryInputs inputs,
            String scriptPath,
            int offset,
            ContentDigest identity,
            UniversalBuildModel.Tool settingsTool,
            BuildModelResult.PomEvidence settingsInput,
            Policy policy,
            List<IngestionEvidence.Issue> issues,
            Set<String> directories,
            Map<String, String> projectNames,
            Map<String, UniversalBuildModel.Tool> gradleProjects,
            Map<String, BuildModelResult.PomEvidence> settingsEvidence,
            Set<String> gradleIncludedChildren) {
        String baseDir = join(root, folder), prefix = baseDir.equals(".") ? "" : baseDir + "/";
        for (var f : inputs.snapshot().files()) {
            String p = f.path();
            if (!p.startsWith(prefix))
                continue;
            String rest = p.substring(prefix.length());
            int slash = rest.indexOf('/');
            if (slash <= 0)
                continue;
            String childName = rest.substring(0, slash), childFile = rest.substring(slash + 1);
            if (!Set.of("build.gradle", "build.gradle.kts").contains(childFile))
                continue;
            if (!namePrefixFilter.isEmpty() && !childName.startsWith(namePrefixFilter))
                continue;
            String dir = join(baseDir, childName);
            if (!directories.contains(dir) && directories.size() >= policy.maxFiles()) {
                issue(issues, INPUT_LIMIT, scriptPath + "#" + offset, identity);
                break;
            }
            directories.add(dir);
            projectNames.putIfAbsent(dir, childName);
            gradleProjects.putIfAbsent(dir, settingsTool);
            settingsEvidence.putIfAbsent(dir, settingsInput);
            if (!dir.equals(root))
                gradleIncludedChildren.add(dir);
        }
    }

    private static boolean projectDirectoryAssignment(String syntax) {
        return syntax.equals("project(#).projectDir=file(#)")
                || syntax.equals("project(#).projectDir=newFile(settingsDir,#)")
                || syntax.equals("project(#).projectDir=File(settingsDir,#)")
                || syntax.equals("project(#).projectDir=newFile(rootDir,#)")
                || syntax.equals("project(#).projectDir=File(rootDir,#)");
    }

    private static boolean eachDirInclude(DeclarativeScript.Statement statement) {
        String syntax = statement.syntax();
        return (syntax.equals("file(#).eachDir") || syntax.equals("newFile(settingsDir,#).eachDir")
                || syntax.equals("File(settingsDir,#).eachDir") || syntax.equals("newFile(rootDir,#).eachDir")
                || syntax.equals("File(rootDir,#).eachDir"))
                && !statement.body().isEmpty() && statement.strings().size() == 1
                && statement.body().stream().anyMatch(child -> child.key().equals("include"));
    }

    private static boolean declarativeSettingsStatement(DeclarativeScript.Statement statement) {
        String syntax = statement.syntax(), key = statement.key();
        if (!statement.body().isEmpty()) {
            return Set.of("pluginManagement", "dependencyResolutionManagement", "plugins", "buildCache", "gradle")
                    .contains(key) && syntax.equals(key);
        }
        return syntax.matches("enableFeaturePreview(?:\\(#\\)|#)")
                || key.equals("import")
                || syntax.matches("(?:val|var|def)[A-Za-z_][A-Za-z0-9_]*=(?:#|true|false|[0-9]+)")
                || syntax.matches("gradle\\.ext\\.[A-Za-z_][A-Za-z0-9_]*=(?:#|true|false|[0-9]+)");
    }

    private static final class Projection {
        final String dir;
        final Policy policy;
        final List<BuildModelResult.PomEvidence> evidence;
        final List<IngestionEvidence.Issue> issues;
        final ContentDigest identity;
        final Map<String, GradleVersionCatalog.Catalog> catalogs;
        final List<SnapshotFile> snapshotFiles;
        final List<UniversalBuildModel.Dependency> dependencies = new ArrayList<>();
        final Map<String, String> variables = new HashMap<>();
        String group, version;
        Optional<Integer> syntax, platform, target;
        Optional<String> encoding;
        List<String> mainRoots, testRoots, mainResources, testResources;
        final Map<String, List<String>> customRoots = new TreeMap<>(), customResources = new TreeMap<>();
        final Set<String> declared = new HashSet<>(), declaredRoots = new HashSet<>();
        boolean explicitSource, explicitTarget, releaseOption, androidModule, bootPluginApplied,
                springDependencyManagementApplied;
        Optional<Integer> androidCompileSdk = Optional.empty();
        Optional<MavenCoordinate> bootPlugin = Optional.empty();
        boolean bootConflict, bootImport;

        Projection(String dir, Policy policy, List<BuildModelResult.PomEvidence> evidence,
                List<IngestionEvidence.Issue> issues, ContentDigest identity,
                Map<String, GradleVersionCatalog.Catalog> catalogs, List<SnapshotFile> snapshotFiles) {
            this.dir = dir;
            this.policy = policy;
            this.evidence = evidence;
            this.issues = issues;
            this.identity = identity;
            this.catalogs = catalogs;
            this.snapshotFiles = snapshotFiles;
            syntax = policy.syntaxLevel();
            platform = policy.platformRelease();
            target = policy.syntaxLevel();
            encoding = policy.encoding();
            mainRoots = List.of(join(dir, "src/main/java"));
            testRoots = List.of(join(dir, "src/test/java"));
            mainResources = List.of(join(dir, "src/main/resources"));
            testResources = List.of(join(dir, "src/test/resources"));
        }

        void read(List<DeclarativeScript.Statement> statements, String context) {
            if (context.isEmpty())
                for (var statement : statements) {
                    if (statement.syntax().equals("importorg.springframework.boot.gradle.plugin.SpringBootPlugin"))
                        bootImport = true;
                    if (statement.syntax().equals("plugins"))
                        for (var plugin : statement.body())
                            registerBootPlugin(plugin);
                }
            for (var s : statements) {
                String shape = s.syntax();
                boolean handled = false;
                if (context.equals("dependencies") || context.equals("dependencies.test")) {
                    String prefix = context.equals("dependencies.test") ? ".test" : "";
                    if (s.syntax().equals("constraints"))
                        for (var child : s.body())
                            dependency(child, prefix + ".constraint");
                    else
                        dependency(s, prefix);
                    continue;
                }
                if (!s.body().isEmpty()) {
                    boolean knownBlock = shape.equals(s.key())
                            && (context.isEmpty() && Set.of("dependencies", "java", "sourceSets").contains(s.key())
                                    || context.equals("java") && s.key().equals("toolchain")
                                    || context.equals("sourceSets") && s.key().matches("[A-Za-z][A-Za-z0-9_]*")
                                    || context.startsWith("sourceSets.") && context.split("\\.").length == 2
                                            && Set.of("java", "resources").contains(s.key()));
                    if (knownBlock) {
                        if (context.equals("sourceSets") && !Set.of("main", "test").contains(s.key())) {
                            registerCustom(s);
                        }
                        read(s.body(), context.isEmpty() ? s.key() : context + "." + s.key());
                        handled = true;
                    } else if (context.isEmpty() && shape.equals("allprojects")) {
                        read(s.body(), "");
                        handled = true;
                    } else if (context.isEmpty() && shape.equals("subprojects")) {
                        handled = true;
                    } else if (context.isEmpty() && (shape.equals("ext") || shape.equals("extra"))) {
                        read(s.body(), "ext");
                        handled = true;
                    } else if (context.isEmpty() && (shape.equals("android")
                            || shape.matches("configure<(?:Application|Library|BaseAppModule)Extension>"))) {
                        androidModule = true;
                        read(s.body(), "android");
                        handled = true;
                    } else if (context.equals("android") && shape.equals("compileOptions")) {
                        read(s.body(), "android.compileOptions");
                        handled = true;
                    } else if (context.equals("android") && shape.equals("sourceSets")) {
                        read(s.body(), "sourceSets");
                        handled = true;
                    } else if (context.equals("android") && shape.equals("dependencies")) {
                        read(s.body(), "dependencies");
                        handled = true;
                    } else if ((context.equals("android") || context.equals("kotlin")
                            || context.equals("kotlinCompile"))
                            && (shape.equals("kotlinOptions") || shape.equals("compilerOptions"))) {
                        read(s.body(), "kotlinOptions");
                        handled = true;
                    } else if (context.equals("android") && shape.equals("defaultConfig")) {
                        read(s.body(), "android.defaultConfig");
                        handled = true;
                    } else if (context.startsWith("android")) {
                        handled = true;
                    } else if (context.isEmpty() && shape.equals("kotlin")) {
                        read(s.body(), "kotlin");
                        handled = true;
                    } else if (context.equals("kotlin") && shape.equals("sourceSets")) {
                        read(s.body(), "kotlin.sourceSets");
                        handled = true;
                    } else if (context.equals("kotlin.sourceSets")
                            && shape.matches("[A-Za-z][A-Za-z0-9_]*\\.dependencies")) {
                        boolean isTest = s.key().toLowerCase(Locale.ROOT).endsWith("test");
                        read(s.body(), isTest ? "dependencies.test" : "dependencies");
                        handled = true;
                    } else if (context.equals("kotlin.sourceSets") && shape.matches(
                            "[A-Za-z][A-Za-z0-9_]*|val[A-Za-z][A-Za-z0-9_]*by(?:getting|creating)|(?:named|getByName)\\(#\\)")) {
                        String setName = s.key().equals("val") && s.head().size() >= 2 ? s.head().get(1).value()
                                : !s.strings().isEmpty() ? s.strings().getFirst() : s.key();
                        read(s.body(), "kotlin.sourceSets." + setName);
                        handled = true;
                    } else if (context.startsWith("kotlin.sourceSets.")) {
                        String setName = context.substring("kotlin.sourceSets.".length());
                        boolean isTest = setName.toLowerCase(Locale.ROOT).endsWith("test");
                        if (shape.equals("dependencies"))
                            read(s.body(), isTest ? "dependencies.test" : "dependencies");
                        else if (Set.of("kotlin", "java", "resources").contains(s.key()) && shape.equals(s.key()))
                            read(s.body(), "sourceSets." + (isTest ? "test" : "main") + "."
                                    + (s.key().equals("resources") ? "resources" : "java"));
                        handled = true;
                    } else if (context.startsWith("kotlin")) {
                        handled = true;
                    } else if (context.isEmpty() && shape.matches(
                            "tasks\\.withType(?:<JavaCompile>\\(\\)|\\(JavaCompile\\))(?:\\.configureEach)?")) {
                        read(s.body(), "javaCompile");
                        handled = true;
                    } else if (context.isEmpty() && shape.matches(
                            "tasks\\.withType(?:<KotlinCompile>\\(\\)|\\(KotlinCompile\\))(?:\\.configureEach)?")) {
                        read(s.body(), "kotlinCompile");
                        handled = true;
                    } else if (context.isEmpty() && shape.equals("buildscript")) {
                        for (var child : s.body()) {
                            if ((child.key().equals("ext") || child.key().equals("extra"))
                                    && child.syntax().equals(child.key()) && !child.body().isEmpty())
                                read(child.body(), "ext");
                            else if (child.body().isEmpty())
                                readVariable(child, "ext");
                        }
                        handled = true;
                    } else if (context.isEmpty()
                            && (shape.matches("tasks\\.(?:withType|named|register|configureEach).*")
                                    || s.key().equals("task")
                                    || (s.key().equals("afterEvaluate") && s.body().stream()
                                            .allMatch(child -> Set
                                                    .of("publishing", "signing", "spotless", "checkstyle", "jacoco",
                                                            "test", "task")
                                                    .contains(child.key())
                                                    || child.syntax().startsWith("tasks.")))
                                    || Set.of("publishing", "signing", "spotless", "checkstyle", "jacoco", "test",
                                            "springBoot", "hibernate", "graalvmNative", "idea", "eclipse")
                                            .contains(s.key()))) {
                        // Source-semantics-inert blocks. `configurations` can change the classpath and
                        // stays a gap.
                        handled = true;
                    } else if (shape.equals("plugins") && context.isEmpty()) {
                        for (var plugin : s.body())
                            if (!supportedPluginDeclaration(plugin))
                                gap(plugin);
                        handled = true;
                    } else if (shape.equals("repositories") && context.isEmpty()) {
                        for (var repository : s.body())
                            if (!(Set
                                    .of("mavenCentral()", "mavenLocal()", "google()", "gradlePluginPortal()",
                                            "jcenter()")
                                    .contains(repository.syntax())
                                    || repository.key().equals("maven") && !repository.body().isEmpty()
                                    || repository.syntax().matches("maven\\(url=#\\)|maven\\(#\\)")))
                                gap(repository);
                        handled = true;
                    }
                } else {
                    if (context.equals("sourceSets") && shape.equals(s.key())
                            && s.key().matches("[A-Za-z][A-Za-z0-9_]*")
                            && !Set.of("main", "test").contains(s.key())) {
                        registerCustom(s);
                        handled = true;
                    }
                    if ((shape.equals("importorg.springframework.boot.gradle.plugin.SpringBootPlugin")
                            || s.key().equals("import")) && context.isEmpty())
                        handled = true;
                    else if (readVariable(s, context)) {
                        handled = true;
                    } else if (context.isEmpty() && shape.matches("apply(?:plugin:#|\\(plugin=#\\))")
                            && s.strings().size() == 1) {
                        String pluginId = interpolate(s.strings().getFirst());
                        if (pluginId.startsWith("com.android."))
                            androidModule = true;
                        if (pluginId.equals("io.spring.dependency-management"))
                            springDependencyManagementApplied = true;
                        handled = !pluginId.contains("$");
                    } else if ((shape.equals("group=#") || shape.equals("group#")) && context.isEmpty()) {
                        try {
                            group = literal(s);
                        } catch (IllegalArgumentException failure) {
                            group = null;
                            gap(s);
                        }
                        handled = true;
                    } else if ((shape.equals("version=#") || shape.equals("version#")) && context.isEmpty()) {
                        try {
                            version = literal(s);
                        } catch (IllegalArgumentException failure) {
                            version = null;
                            gap(s);
                        }
                        handled = true;
                    } else if (Set.of("kotlin", "java").contains(context)
                            && shape.matches("jvmToolchain\\([0-9]+\\)")) {
                        int level = Integer
                                .parseInt(s.head().stream().filter(t -> !t.literal() && t.value().matches("[0-9]+"))
                                        .findFirst().orElseThrow().value());
                        platform = Optional.of(level);
                        declared.add("release");
                        if (!explicitSource) {
                            syntax = platform;
                            declared.add("source");
                        }
                        if (!explicitTarget) {
                            target = syntax;
                            declared.add("target");
                        }
                        handled = true;
                    } else if (Set.of("", "java", "java.toolchain", "javaCompile", "android.compileOptions")
                            .contains(context)
                            && shape.matches(
                                    "(?:sourceCompatibility|targetCompatibility|languageVersion|options\\.release)(?:=|\\.set\\().+")) {
                        var numbers = s.head().stream()
                                .filter(t -> !t.literal() && t.value().matches("[0-9]+|VERSION_(?:1_)?[0-9]+"))
                                .map(t -> t.value().replaceFirst("^VERSION_(?:1_)?", "")).toList();
                        try {
                            if (!shape.matches(
                                    "(?:sourceCompatibility|targetCompatibility)=(?:#|[0-9]+|(?:org\\.gradle\\.api\\.)?JavaVersion\\.VERSION_(?:1_)?[0-9]+|(?:org\\.gradle\\.api\\.)?JavaVersion\\.toVersion\\((?:#|[0-9]+)\\))"
                                            + "|languageVersion=JavaLanguageVersion\\.of\\([0-9]+\\)|options\\.release(?:=[0-9]+|\\.set\\([0-9]+\\))"))
                                throw new IllegalArgumentException();
                            int level = numbers.size() == 1 ? Integer.parseInt(numbers.getFirst())
                                    : Integer.parseInt(literal(s).replaceFirst("^1\\.", ""));
                            if (level < 1 || level > 999)
                                throw new IllegalArgumentException();
                            if (releaseOption && !s.key().equals("options")) {
                                gap(s);
                                handled = true;
                                continue;
                            }
                            if (s.key().equals("sourceCompatibility")) {
                                syntax = Optional.of(level);
                                explicitSource = true;
                                declared.add("source");
                                if (!explicitTarget) {
                                    target = syntax;
                                    declared.add("target");
                                }
                            } else if (s.key().equals("targetCompatibility")) {
                                target = Optional.of(level);
                                explicitTarget = true;
                                declared.add("target");
                            } else if (s.key().equals("languageVersion")) {
                                platform = Optional.of(level);
                                declared.add("release");
                                if (!explicitSource) {
                                    syntax = platform;
                                    declared.add("source");
                                }
                                if (!explicitTarget) {
                                    target = syntax;
                                    declared.add("target");
                                }
                            } else {
                                if (explicitSource || explicitTarget)
                                    gap(s);
                                releaseOption = true;
                                syntax = Optional.of(level);
                                platform = syntax;
                                target = syntax;
                                declared.addAll(List.of("source", "target", "release"));
                            }
                            handled = true;
                        } catch (IllegalArgumentException failure) {
                            if (s.key().equals("sourceCompatibility"))
                                syntax = Optional.empty();
                            else if (s.key().equals("targetCompatibility"))
                                target = Optional.empty();
                            else {
                                syntax = Optional.empty();
                                platform = Optional.empty();
                                target = Optional.empty();
                            }
                            gap(s);
                            handled = true;
                        }
                    } else if (Set.of("", "kotlin", "android", "kotlinOptions", "kotlinCompile").contains(context)
                            && (shape.matches("(?:kotlinOptions\\.)?jvmTarget=(?:#|JvmTarget\\.JVM_(?:1_)?[0-9]+)")
                                    || shape.matches("jvmTarget\\.set\\((?:#|JvmTarget\\.JVM_(?:1_)?[0-9]+)\\)"))) {
                        var numbers = s.head().stream()
                                .filter(t -> !t.literal() && t.value().matches("JVM_(?:1_)?[0-9]+"))
                                .map(t -> t.value().replaceFirst("^JVM_(?:1_)?", "")).toList();
                        try {
                            int level = numbers.size() == 1 ? Integer.parseInt(numbers.getFirst())
                                    : Integer.parseInt(literal(s).replaceFirst("^1\\.", ""));
                            if (level < 1 || level > 999)
                                throw new IllegalArgumentException();
                            target = Optional.of(level);
                            explicitTarget = true;
                            declared.add("target");
                            if (!explicitSource) {
                                syntax = target;
                                declared.add("source");
                            }
                        } catch (IllegalArgumentException failure) {
                            target = Optional.empty();
                            gap(s);
                        }
                        handled = true;
                    } else if ((shape.equals("options.encoding=#") && context.equals("javaCompile"))
                            || (shape.equals("encoding=#")
                                    && (context.equals("java") || context.equals("android.compileOptions")))) {
                        try {
                            encoding = Optional.of(literal(s));
                            declared.add("encoding");
                        } catch (IllegalArgumentException failure) {
                            encoding = Optional.empty();
                            gap(s);
                        }
                        handled = true;
                    } else if ((s.key().equals("srcDirs") || s.key().equals("srcDir") || s.key().equals("setSrcDirs")
                            || s.key().equals("resourceDirs"))
                            && context.matches("sourceSets\\.[A-Za-z][A-Za-z0-9_]*\\.(?:java|resources)")) {
                        if (s.strings().isEmpty() || !shape.matches(SRC_DIR_SHAPE)) {
                            gap(s);
                            handled = true;
                        } else {
                            var expanded = s.strings().stream().map(p -> expandStandardBuildDirs(interpolate(p)))
                                    .toList();
                            if (expanded.stream().anyMatch(p -> p.contains("$"))) {
                                gap(s);
                                handled = true;
                            } else
                                try {
                                    var roots = expanded.stream().map(p -> join(dir, p)).toList();
                                    String setName = context.split("\\.")[1];
                                    boolean test = setName.equals("test"), main = setName.equals("main"),
                                            resource = context.endsWith(".resources") || s.key().equals("resourceDirs");
                                    declaredRoots.addAll(roots);
                                    boolean replace = s.key().equals("setSrcDirs") || shape.startsWith("srcDirs=")
                                            || shape.startsWith("resourceDirs=");
                                    var prior = test ? (resource ? testResources : testRoots)
                                            : main ? (resource ? mainResources : mainRoots)
                                                    : (resource ? customResources.get(setName)
                                                            : customRoots.get(setName));
                                    if (!replace)
                                        roots = java.util.stream.Stream.concat(prior.stream(), roots.stream())
                                                .distinct().toList();
                                    if (test && resource)
                                        testResources = roots;
                                    else if (test)
                                        testRoots = roots;
                                    else if (main && resource)
                                        mainResources = roots;
                                    else if (main)
                                        mainRoots = roots;
                                    else if (resource)
                                        customResources.put(setName, roots);
                                    else
                                        customRoots.put(setName, roots);
                                    handled = true;
                                } catch (IllegalArgumentException failure) {
                                    issue(issues, UNSAFE_PATH, dir + "#" + s.offset(), identity);
                                    handled = true;
                                }
                        }
                        handled = true;
                    } else if ((shape.equals("version=#") || shape.equals("version#")) && context.isEmpty()) {
                        try {
                            version = literal(s);
                        } catch (IllegalArgumentException failure) {
                            version = null;
                            gap(s);
                        }
                        handled = true;
                    } else if (Set.of("kotlin", "java").contains(context)
                            && shape.matches("jvmToolchain\\([0-9]+\\)")) {
                        int level = Integer
                                .parseInt(s.head().stream().filter(t -> !t.literal() && t.value().matches("[0-9]+"))
                                        .findFirst().orElseThrow().value());
                        platform = Optional.of(level);
                        declared.add("release");
                        if (!explicitSource) {
                            syntax = platform;
                            declared.add("source");
                        }
                        if (!explicitTarget) {
                            target = syntax;
                            declared.add("target");
                        }
                        handled = true;
                    } else if (Set.of("", "java", "java.toolchain", "javaCompile", "android.compileOptions")
                            .contains(context)
                            && shape.matches(
                                    "(?:sourceCompatibility|targetCompatibility|languageVersion|options\\.release)(?:=|\\.set\\().+")) {
                        var numbers = s.head().stream()
                                .filter(t -> !t.literal() && t.value().matches("[0-9]+|VERSION_[0-9]+"))
                                .map(t -> t.value().replace("VERSION_", "")).toList();
                        try {
                            if (!shape.matches(
                                    "(?:sourceCompatibility|targetCompatibility)=(?:#|[0-9]+|JavaVersion\\.VERSION_[0-9]+)"
                                            + "|languageVersion=JavaLanguageVersion\\.of\\([0-9]+\\)|options\\.release(?:=[0-9]+|\\.set\\([0-9]+\\))"))
                                throw new IllegalArgumentException();
                            int level = numbers.size() == 1 ? Integer.parseInt(numbers.getFirst())
                                    : Integer.parseInt(literal(s).replaceFirst("^1\\.", ""));
                            if (level < 1 || level > 999)
                                throw new IllegalArgumentException();
                            if (releaseOption && !s.key().equals("options")) {
                                gap(s);
                                handled = true;
                                continue;
                            }
                            if (s.key().equals("sourceCompatibility")) {
                                syntax = Optional.of(level);
                                explicitSource = true;
                                declared.add("source");
                                if (!explicitTarget) {
                                    target = syntax;
                                    declared.add("target");
                                }
                            } else if (s.key().equals("targetCompatibility")) {
                                target = Optional.of(level);
                                explicitTarget = true;
                                declared.add("target");
                            } else if (s.key().equals("languageVersion")) {
                                platform = Optional.of(level);
                                declared.add("release");
                                if (!explicitSource) {
                                    syntax = platform;
                                    declared.add("source");
                                }
                                if (!explicitTarget) {
                                    target = syntax;
                                    declared.add("target");
                                }
                            } else {
                                if (explicitSource || explicitTarget)
                                    gap(s);
                                releaseOption = true;
                                syntax = Optional.of(level);
                                platform = syntax;
                                target = syntax;
                                declared.addAll(List.of("source", "target", "release"));
                            }
                            handled = true;
                        } catch (IllegalArgumentException failure) {
                            if (s.key().equals("sourceCompatibility"))
                                syntax = Optional.empty();
                            else if (s.key().equals("targetCompatibility"))
                                target = Optional.empty();
                            else {
                                syntax = Optional.empty();
                                platform = Optional.empty();
                                target = Optional.empty();
                            }
                            gap(s);
                            handled = true;
                        }
                    } else if ((shape.equals("options.encoding=#") && context.equals("javaCompile"))
                            || (shape.equals("encoding=#")
                                    && (context.equals("java") || context.equals("android.compileOptions")))) {
                        try {
                            encoding = Optional.of(literal(s));
                            declared.add("encoding");
                        } catch (IllegalArgumentException failure) {
                            encoding = Optional.empty();
                            gap(s);
                        }
                        handled = true;
                    } else if ((s.key().equals("srcDirs") || s.key().equals("srcDir") || s.key().equals("setSrcDirs")
                            || s.key().equals("resourceDirs"))
                            && context.matches("sourceSets\\.[A-Za-z][A-Za-z0-9_]*\\.(?:java|resources)")) {
                        if (s.strings().isEmpty() || !shape.matches(SRC_DIR_SHAPE)) {
                            gap(s);
                            handled = true;
                        } else {
                            var expanded = s.strings().stream().map(p -> expandStandardBuildDirs(interpolate(p)))
                                    .toList();
                            if (expanded.stream().anyMatch(p -> p.contains("$"))) {
                                gap(s);
                                handled = true;
                            } else
                                try {
                                    var roots = expanded.stream().map(p -> join(dir, p)).toList();
                                    String setName = context.split("\\.")[1];
                                    boolean test = setName.equals("test"), main = setName.equals("main"),
                                            resource = context.endsWith(".resources") || s.key().equals("resourceDirs");
                                    declaredRoots.addAll(roots);
                                    boolean replace = s.key().equals("setSrcDirs") || shape.startsWith("srcDirs=")
                                            || shape.startsWith("resourceDirs=");
                                    var prior = test ? (resource ? testResources : testRoots)
                                            : main ? (resource ? mainResources : mainRoots)
                                                    : (resource ? customResources.get(setName)
                                                            : customRoots.get(setName));
                                    if (!replace)
                                        roots = java.util.stream.Stream.concat(prior.stream(), roots.stream())
                                                .distinct().toList();
                                    if (test && resource)
                                        testResources = roots;
                                    else if (test)
                                        testRoots = roots;
                                    else if (main && resource)
                                        mainResources = roots;
                                    else if (main)
                                        mainRoots = roots;
                                    else if (resource)
                                        customResources.put(setName, roots);
                                    else
                                        customRoots.put(setName, roots);
                                    handled = true;
                                } catch (IllegalArgumentException failure) {
                                    issue(issues, UNSAFE_PATH, dir + "#" + s.offset(), identity);
                                    handled = true;
                                }
                        }
                    } else if ((context.equals("android") || context.equals("android.defaultConfig")
                            || (context.isEmpty() && androidModule))
                            && Set.of("compileSdk", "compileSdkVersion").contains(s.key())) {
                        extractAndroidCompileSdk(s).ifPresent(sdk -> {
                            androidModule = true;
                            androidCompileSdk = Optional.of(sdk);
                        });
                        handled = true;
                    } else if ((context.isEmpty()
                            && shape.matches("tasks\\.(?:withType|named|register|configureEach).*"))
                            || context.startsWith("android") || context.startsWith("kotlin")
                            || context.equals("javaCompile")) {
                        handled = true;
                    }
                }
                if (!handled)
                    gap(s);
            }
        }

        /**
         * Plugins whose effects on Java source roots/classpath are nil or already
         * modeled here.
         * Anything else (code generators, Android R/BuildConfig, kapt/ksp outputs,
         * unknown ids)
         * stays an explicit DYNAMIC_BUILD_LOGIC gap, i.e. PLUGIN_EFFECTS_NOT_EVALUATED.
         */
        private static final Set<String> INERT_PLUGINS = Set.of("java", "java-library", "java-platform", "application",
                "war", "jacoco",
                "maven-publish", "signing", "checkstyle", "pmd", "idea", "eclipse", "base", "distribution",
                "org.springframework.boot", "io.spring.dependency-management",
                "org.jetbrains.kotlin.jvm", "org.jetbrains.kotlin.multiplatform", "org.jetbrains.kotlin.plugin.spring",
                "org.jetbrains.kotlin.plugin.allopen", "org.jetbrains.kotlin.plugin.noarg",
                "org.jetbrains.kotlin.plugin.jpa",
                "org.jetbrains.kotlin.android", "kotlin-android", "android",
                "jvm", "multiplatform", "plugin.spring", "plugin.allopen", "plugin.noarg", "plugin.jpa",
                "com.diffplug.spotless", "com.diffplug.gradle.spotless", "com.github.ben-manes.versions",
                "org.sonarqube", "org.graalvm.buildtools.native",
                "org.cyclonedx.bom", "com.gorylenko.gradle-git-properties", "org.asciidoctor.jvm.convert",
                "io.gitlab.arturbosch.detekt", "org.jlleitschuh.gradle.ktlint", "com.vanniktech.maven.publish",
                "com.github.johnrengelman.shadow", "com.gradleup.shadow", "net.ltgt.errorprone",
                "org.jetbrains.dokka", "com.autonomousapps.dependency-analysis");

        boolean supportedPluginDeclaration(DeclarativeScript.Statement plugin) {
            if (!plugin.body().isEmpty())
                return false;
            String shape = plugin.syntax();
            if (INERT_PLUGINS.contains(shape))
                return true;
            String id;
            if (shape.matches("alias\\([A-Za-z][A-Za-z0-9_]*\\.plugins\\.[A-Za-z0-9_.-]+\\)(?:applyfalse)?")) {
                if (shape.endsWith("applyfalse"))
                    return true;
                int dot = shape.indexOf(".plugins.");
                String catName = shape.substring("alias(".length(), dot);
                String alias = shape.substring(dot + ".plugins.".length(), shape.indexOf(')'));
                var resolved = Optional.ofNullable(catalogs.get(catName))
                        .flatMap(c -> c.plugins().getOrDefault(alias, Optional.empty()));
                if (resolved.isEmpty())
                    return false;
                id = resolved.orElseThrow().id();
            } else if (shape.matches("(?:kotlin|id)(?:#|\\(#\\))(?:version#)?(?:applyfalse)?")
                    && !plugin.strings().isEmpty()) {
                if (plugin.strings().size() > 1
                        && (!fixedVersion(plugin.strings().get(1)) || plugin.strings().get(1).contains(" ")))
                    return false;
                if (shape.endsWith("applyfalse"))
                    return true;
                id = plugin.strings().getFirst();
            } else
                return false;
            return INERT_PLUGINS.contains(id);
        }

        void registerBootPlugin(DeclarativeScript.Statement statement) {
            String shape = statement.syntax();
            Optional<GradleVersionCatalog.Plugin> plugin = Optional.empty();
            boolean applyFalse = shape.endsWith("applyfalse");
            if (shape.matches("alias\\([A-Za-z][A-Za-z0-9_]*\\.plugins\\.[A-Za-z0-9_.-]+\\)(?:applyfalse)?")) {
                int dot = shape.indexOf(".plugins.");
                String catName = shape.substring("alias(".length(), dot);
                String alias = shape.substring(dot + ".plugins.".length(), shape.indexOf(')'));
                plugin = Optional.ofNullable(catalogs.get(catName))
                        .flatMap(c -> c.plugins().getOrDefault(alias, Optional.empty()));
            } else if (shape.matches("id(?:#|\\(#\\))(?:version#)?(?:applyfalse)?") && !statement.strings().isEmpty()) {
                String id = statement.strings().getFirst();
                String ver = statement.strings().size() > 1 ? statement.strings().get(1) : "";
                plugin = Optional.of(new GradleVersionCatalog.Plugin(id, ver));
            }
            if (plugin.isEmpty())
                return;
            String id = plugin.orElseThrow().id(), ver = plugin.orElseThrow().version();
            if (id.startsWith("com.android."))
                androidModule = true;
            if (id.equals("io.spring.dependency-management") && !applyFalse)
                springDependencyManagementApplied = true;
            if (!id.equals("org.springframework.boot") || ver.isEmpty() || !fixedVersion(ver))
                return;
            MavenCoordinate marker;
            try {
                marker = new MavenCoordinate("org.springframework.boot", "org.springframework.boot.gradle.plugin", ver);
            } catch (IllegalArgumentException invalid) {
                gap(statement);
                return;
            }
            if (bootPlugin.isPresent() && !bootPlugin.orElseThrow().equals(marker)) {
                bootConflict = true;
                bootPlugin = Optional.empty();
                gap(statement);
                return;
            }
            if (bootConflict)
                return;
            bootPlugin = Optional.of(marker);
            if (!applyFalse)
                bootPluginApplied = true;
            dependencies.add(new UniversalBuildModel.Dependency("gradlePlugin", Optional.of(marker), Optional.empty(),
                    "org.springframework.boot", evidence));
        }

        void finish(List<SnapshotFile> snapshotFiles) {
            if (bootPluginApplied && springDependencyManagementApplied && bootPlugin.isPresent() && !bootConflict) {
                var bom = new MavenCoordinate("org.springframework.boot", "spring-boot-dependencies",
                        bootPlugin.orElseThrow().version());
                if (dependencies.stream().noneMatch(
                        d -> d.configuration().endsWith(".platform") && d.coordinate().equals(Optional.of(bom)))) {
                    dependencies.add(new UniversalBuildModel.Dependency("implementation.platform", Optional.of(bom),
                            Optional.empty(), "io.spring.dependency-management:org.springframework.boot", evidence));
                }
            }
            Set<String> claimedCustom = new HashSet<>(declaredRoots);
            customRoots.values().forEach(claimedCustom::addAll);
            customResources.values().forEach(claimedCustom::addAll);
            var extraMain = new ArrayList<>(mainRoots);
            for (String candidate : List.of("src/main/kotlin", "src/commonMain/kotlin", "src/commonMain/java",
                    "src/androidMain/kotlin", "src/androidMain/java", "src/jvmMain/kotlin", "src/jvmMain/java",
                    "src/iosMain/kotlin", "src/debug/java", "src/debug/kotlin", "src/release/java",
                    "src/release/kotlin",
                    "build/generated/sources/annotationProcessor/java/main",
                    "build/generated/sources/delombok/java/main",
                    "build/generated/source/kapt/main",
                    "build/generated/source/kaptKotlin/main",
                    "build/generated/ksp/main/java",
                    "build/generated/ksp/main/kotlin",
                    "build/generated/source/proto/main/java",
                    "build/generated/source/proto/main/grpc",
                    "build/generated/source/proto/main/kotlin",
                    "build/generated/source/buildConfig/debug",
                    "build/generated/source/buildConfig/release",
                    "build/generated/aidl_source_output_dir/debug/out",
                    "build/generated/data_binding_base_class_source_out/debug/out")) {
                String path = join(dir, candidate);
                if (!extraMain.contains(path) && !claimedCustom.contains(path) && hasFilesUnder(snapshotFiles, path))
                    extraMain.add(path);
            }
            if (androidModule) {
                String prefix = dir.equals(".") ? "src/" : dir + "/src/";
                Set<String> excludedSets = new HashSet<>(Set.of("main", "test", "androidTest", "commonMain",
                        "commonTest", "androidMain", "androidUnitTest", "androidDeviceTest", "androidInstrumentedTest",
                        "jvmMain", "jvmTest", "iosMain", "iosTest", "debug", "release"));
                excludedSets.addAll(customRoots.keySet());
                for (var f : snapshotFiles)
                    if (f.path().startsWith(prefix) && (f.path().endsWith(".java") || f.path().endsWith(".kt"))) {
                        String rest = f.path().substring(prefix.length());
                        String[] parts = rest.split("/", 3);
                        if (parts.length == 3 && !excludedSets.contains(parts[0])
                                && Set.of("java", "kotlin").contains(parts[1])) {
                            String variantRoot = join(dir, "src/" + parts[0] + "/" + parts[1]);
                            if (!extraMain.contains(variantRoot) && !claimedCustom.contains(variantRoot))
                                extraMain.add(variantRoot);
                        }
                    }
            }
            mainRoots = List.copyOf(extraMain);
            var extraTest = new ArrayList<>(testRoots);
            for (String candidate : List.of("src/test/kotlin", "src/androidTest/java", "src/androidTest/kotlin",
                    "src/commonTest/kotlin", "src/commonTest/java", "src/jvmTest/kotlin", "src/jvmTest/java",
                    "src/androidUnitTest/kotlin", "src/androidUnitTest/java", "src/androidDeviceTest/kotlin",
                    "src/androidDeviceTest/java", "src/iosTest/kotlin",
                    "build/generated/sources/annotationProcessor/java/test",
                    "build/generated/source/kapt/test",
                    "build/generated/ksp/test/java",
                    "build/generated/ksp/test/kotlin")) {
                String path = join(dir, candidate);
                if (!extraTest.contains(path) && !claimedCustom.contains(path) && hasFilesUnder(snapshotFiles, path))
                    extraTest.add(path);
            }
            testRoots = List.copyOf(extraTest);
            var extraRes = new ArrayList<>(mainResources);
            for (String candidate : List.of("src/main/res", "src/commonMain/resources", "src/androidMain/res",
                    "src/debug/res", "src/release/res", "src/auto/res")) {
                String path = join(dir, candidate);
                if (!extraRes.contains(path) && !claimedCustom.contains(path) && hasFilesUnder(snapshotFiles, path))
                    extraRes.add(path);
            }
            mainResources = List.copyOf(extraRes);
        }

        static boolean hasFilesUnder(List<SnapshotFile> files, String root) {
            String prefix = root + "/";
            return files.stream().anyMatch(f -> f.path().startsWith(prefix));
        }

        void registerCustom(DeclarativeScript.Statement s) {
            if (customRoots.putIfAbsent(s.key(), List.of(join(dir, "src/" + s.key() + "/java"))) == null) {
                customResources.put(s.key(), List.of(join(dir, "src/" + s.key() + "/resources")));
                issue(issues, CUSTOM_SOURCE_SET_ROLE_UNRESOLVED, dir + "#" + s.offset(), identity);
            }
        }

        static boolean isDependencyConfiguration(String key) {
            return Set.of("implementation", "api", "compileOnly", "compileOnlyApi", "runtimeOnly",
                    "testImplementation", "testCompileOnly", "testRuntimeOnly", "annotationProcessor",
                    "testAnnotationProcessor", "compile", "testCompile", "runtime", "testRuntime",
                    "developmentOnly", "ksp", "kspTest", "kapt", "kaptTest", "coreLibraryDesugaring",
                    "androidTestImplementation", "androidTestCompileOnly", "androidTestRuntimeOnly", "androidTestUtil",
                    "debugImplementation", "releaseImplementation", "debugCompileOnly", "releaseCompileOnly",
                    "debugRuntimeOnly", "releaseRuntimeOnly", "testFixturesImplementation", "testFixturesApi",
                    "testFixturesCompileOnly", "testFixturesRuntimeOnly").contains(key)
                    || key.matches(
                            "[a-z][A-Za-z0-9_]*(?:Implementation|Api|CompileOnly|CompileOnlyApi|RuntimeOnly|AnnotationProcessor|Ksp|Kapt)");
        }

        void dependency(DeclarativeScript.Statement s, String qualifier) {
            if (!isDependencyConfiguration(s.key())) {
                gap(s);
                return;
            }
            var exclusions = new ArrayList<String>();
            if (!s.body().isEmpty()) {
                for (var child : s.body()) {
                    if (child.key().equals("exclude") && child.body().isEmpty()) {
                        String exGroup = null, exModule = null;
                        boolean bad = false;
                        for (int i = 1; i + 1 < child.head().size(); i++) {
                            String tok = child.head().get(i).value();
                            if ((tok.equals(":") || tok.equals("=")) && child.head().get(i + 1).literal()) {
                                String k = child.head().get(i - 1).value(),
                                        v = interpolate(child.head().get(i + 1).value());
                                if (v.contains("$")) {
                                    bad = true;
                                    break;
                                }
                                if (k.equals("group"))
                                    exGroup = v;
                                else if (k.equals("module"))
                                    exModule = v;
                            }
                        }
                        if (!bad && (exGroup != null || exModule != null))
                            exclusions
                                    .add((exGroup != null ? exGroup : "*") + ":" + (exModule != null ? exModule : "*"));
                        else
                            gap(child);
                    } else if (child.syntax().matches("(?:transitive|isTransitive)=(?:true|false)")
                            && child.body().isEmpty()) {
                        if (child.syntax().endsWith("false"))
                            exclusions.add("*:*");
                    } else
                        gap(child);
                }
            }
            String baseKey = s.key();
            if (qualifier.startsWith(".test")) {
                qualifier = qualifier.substring(".test".length());
                if (baseKey.equals("implementation"))
                    baseKey = "testImplementation";
                else if (baseKey.equals("api"))
                    baseKey = "testCompile";
                else if (baseKey.equals("compileOnly"))
                    baseKey = "testCompileOnly";
                else if (baseKey.equals("runtimeOnly"))
                    baseKey = "testRuntimeOnly";
            }
            Optional<MavenCoordinate> coordinate = Optional.empty();
            Optional<String> project = Optional.empty();
            String notation = "";
            String shape = s.syntax();
            try {
                String accessor = shape.substring(s.key().length());
                if (accessor.startsWith("(") && accessor.endsWith(")"))
                    accessor = accessor.substring(1, accessor.length() - 1);
                if ((accessor.startsWith("platform(") || accessor.startsWith("enforcedPlatform("))
                        && accessor.endsWith(")")) {
                    boolean enforced = accessor.startsWith("enforcedPlatform(");
                    qualifier += enforced ? ".enforced-platform" : ".platform";
                    accessor = accessor.substring(accessor.indexOf('(') + 1, accessor.length() - 1);
                    shape = s.key() + "(" + accessor + ")";
                }
                String configuration = baseKey + qualifier;
                if ((qualifier.endsWith(".platform") || qualifier.endsWith(".enforced-platform"))
                        && accessor.equals("SpringBootPlugin.BOM_COORDINATES")) {
                    notation = accessor;
                    if (bootImport && bootPlugin.isPresent())
                        coordinate = Optional.of(new MavenCoordinate("org.springframework.boot",
                                "spring-boot-dependencies", bootPlugin.orElseThrow().version()));
                    else
                        issue(issues, DEPENDENCY_VERSION_UNRESOLVED, dir + "#" + s.offset(), identity);
                    dependencies.add(new UniversalBuildModel.Dependency(configuration, coordinate, Optional.empty(),
                            notation, evidence, exclusions));
                    return;
                }
                if (accessor.matches("[A-Za-z][A-Za-z0-9_]*\\.[A-Za-z0-9_.-]+")
                        && catalogs.containsKey(accessor.substring(0, accessor.indexOf('.')))) {
                    int dot = accessor.indexOf('.');
                    String catName = accessor.substring(0, dot), rest = accessor.substring(dot + 1);
                    var selectedCatalog = catalogs.get(catName);
                    if (rest.startsWith("bundles.")) {
                        String bundle = rest.substring("bundles.".length());
                        var members = Optional.ofNullable(selectedCatalog.bundles().get(bundle)).orElse(List.of());
                        if (members.isEmpty()) {
                            issue(issues, DEPENDENCY_VERSION_UNRESOLVED, dir + "#" + s.offset(), identity);
                            dependencies.add(new UniversalBuildModel.Dependency(configuration, Optional.empty(),
                                    Optional.empty(), accessor, evidence, exclusions));
                        } else
                            for (String member : members)
                                catalogDependency(s, selectedCatalog, configuration, accessor + "/" + member, member,
                                        exclusions);
                    } else
                        catalogDependency(s, selectedCatalog, configuration, accessor, rest, exclusions);
                    return;
                }
                if (accessor.matches("projects(?:\\.[A-Za-z0-9_]+)+")) {
                    String clean = accessor.endsWith(".dependencyProject")
                            ? accessor.substring(0, accessor.length() - ".dependencyProject".length())
                            : accessor;
                    String projPath = ":" + clean.substring("projects.".length()).replace('.', ':');
                    dependencies.add(new UniversalBuildModel.Dependency(configuration, Optional.empty(),
                            Optional.of(projPath), projPath, evidence, exclusions));
                    return;
                }
                if (tryLocalFileDependency(s, configuration, accessor, exclusions))
                    return;
                if (accessor.matches("[A-Za-z_][A-Za-z0-9_.]*")) {
                    String varValue = lookupVariable(accessor);
                    if (varValue != null && !varValue.isBlank()) {
                        notation = interpolate(varValue).replaceFirst("@[A-Za-z0-9]+$", "");
                        String[] gav = notation.split(":", -1);
                        if (!notation.contains("$") && gav.length >= 3 && fixedVersion(gav[2]))
                            coordinate = Optional.of(new MavenCoordinate(gav[0], gav[1], gav[2]));
                        else
                            issue(issues, DEPENDENCY_VERSION_UNRESOLVED, dir + "#" + s.offset(), identity);
                        dependencies.add(new UniversalBuildModel.Dependency(configuration, coordinate, Optional.empty(),
                                notation, evidence, exclusions));
                        return;
                    }
                }
                if (accessor.matches("#(?:,#)+") && s.strings().size() > 1) {
                    for (String rawItem : s.strings()) {
                        String item = interpolate(rawItem), cleanItem = item.replaceFirst("@[A-Za-z0-9]+$", "");
                        Optional<MavenCoordinate> itemCoord = Optional.empty();
                        String[] gav = cleanItem.split(":", -1);
                        if (gav.length >= 3 && fixedVersion(gav[2]) && !item.contains("$")) {
                            try {
                                itemCoord = Optional.of(new MavenCoordinate(gav[0], gav[1], gav[2]));
                            } catch (IllegalArgumentException invalid) {
                                issue(issues, DEPENDENCY_VERSION_UNRESOLVED, dir + "#" + s.offset(), identity);
                            }
                        } else
                            issue(issues, DEPENDENCY_VERSION_UNRESOLVED, dir + "#" + s.offset(), identity);
                        dependencies.add(new UniversalBuildModel.Dependency(configuration, itemCoord, Optional.empty(),
                                cleanItem, evidence, exclusions));
                    }
                    return;
                }
                if (shape.matches(
                        "[A-Za-z0-9_]+(?:\\(project\\((?:#|path[:=]#(?:,configuration[:=]#)?)\\)\\)|project\\((?:#|path[:=]#(?:,configuration[:=]#)?)\\))")) {
                    notation = interpolate(s.strings().getFirst());
                    if (!notation.matches(":?[A-Za-z0-9_.-]+(?::[A-Za-z0-9_.-]+)*"))
                        throw new IllegalArgumentException();
                    project = Optional.of(notation);
                } else if (shape.matches("[A-Za-z0-9_]+(?:\\(#\\)|#)")) {
                    notation = literal(s).replaceFirst("@[A-Za-z0-9]+$", "");
                    String[] gav = notation.split(":", -1);
                    if (gav.length >= 3 && fixedVersion(gav[2]))
                        coordinate = Optional.of(new MavenCoordinate(gav[0], gav[1], gav[2]));
                    else
                        issue(issues, DEPENDENCY_VERSION_UNRESOLVED, dir + "#" + s.offset(), identity);
                } else if (shape.matches(
                        "[A-Za-z0-9_]+\\(?(?:group|name|version)[:=]#(?:,(?:group|name|version)[:=]#){2}\\)?")) {
                    var fields = new HashMap<String, String>();
                    for (int i = 2; i < s.head().size(); i++)
                        if (s.head().get(i).literal() && (s.head().get(i - 1).value().equals(":")
                                || s.head().get(i - 1).value().equals("="))) {
                            String key = s.head().get(i - 2).value();
                            if (fields.putIfAbsent(key, interpolate(s.head().get(i).value())) != null)
                                throw new IllegalArgumentException();
                        }
                    if (!fields.keySet().equals(Set.of("group", "name", "version")))
                        throw new IllegalArgumentException();
                    notation = fields.get("group") + ":" + fields.get("name") + ":" + fields.get("version");
                    if (fixedVersion(fields.get("version")))
                        coordinate = Optional.of(
                                new MavenCoordinate(fields.get("group"), fields.get("name"), fields.get("version")));
                    else
                        issue(issues, DEPENDENCY_VERSION_UNRESOLVED, dir + "#" + s.offset(), identity);
                } else {
                    gap(s);
                    return;
                }
                if (notation.contains("$")) {
                    coordinate = Optional.empty();
                    issue(issues, DEPENDENCY_VERSION_UNRESOLVED, dir + "#" + s.offset(), identity);
                }
                dependencies.add(new UniversalBuildModel.Dependency(configuration, coordinate, project, notation,
                        evidence, exclusions));
            } catch (IllegalArgumentException failure) {
                issue(issues, DEPENDENCY_VERSION_UNRESOLVED, dir + "#" + s.offset(), identity);
                dependencies
                        .add(new UniversalBuildModel.Dependency(baseKey + qualifier, Optional.empty(), Optional.empty(),
                                notation.isEmpty() ? s.syntax() : notation, evidence, exclusions));
            }
        }

        void catalogDependency(DeclarativeScript.Statement s, GradleVersionCatalog.Catalog selected,
                String configuration, String notation, String alias, List<String> exclusions) {
            Optional<MavenCoordinate> coordinate = Optional.ofNullable(selected)
                    .flatMap(c -> Optional.ofNullable(c.libraries().get(alias)))
                    .orElse(Optional.empty());
            if (coordinate.isEmpty())
                issue(issues, DEPENDENCY_VERSION_UNRESOLVED, dir + "#" + s.offset(), identity);
            dependencies.add(new UniversalBuildModel.Dependency(configuration, coordinate, Optional.empty(), notation,
                    evidence, exclusions));
        }

        static Map<String, String> extractRootVariables(List<DeclarativeScript.Statement> statements,
                Map<String, String> inherited) {
            var scratch = new Projection(".", Policy.defaults(), List.of(), new ArrayList<>(),
                    ContentDigest.sha256Utf8("root-vars"), Map.of(), List.of());
            scratch.variables.putAll(inherited);
            for (var s : statements) {
                String shape = s.syntax();
                if (!s.body().isEmpty()) {
                    if ((shape.equals("ext") || shape.equals("extra")) && shape.equals(s.key())) {
                        for (var child : s.body())
                            if (child.body().isEmpty())
                                scratch.readVariable(child, "ext");
                    } else if (shape.equals("buildscript")) {
                        for (var child : s.body()) {
                            if ((child.key().equals("ext") || child.key().equals("extra"))
                                    && child.syntax().equals(child.key()) && !child.body().isEmpty()) {
                                for (var grand : child.body())
                                    if (grand.body().isEmpty())
                                        scratch.readVariable(grand, "ext");
                            } else if (child.body().isEmpty())
                                scratch.readVariable(child, "ext");
                        }
                    }
                } else
                    scratch.readVariable(s, "");
            }
            return Map.copyOf(scratch.variables);
        }

        boolean readVariable(DeclarativeScript.Statement s, String context) {
            if (!s.body().isEmpty())
                return false;
            String shape = s.syntax();
            if ((context.isEmpty() || context.equals("ext"))
                    && shape.matches("(?:val|var|def)[A-Za-z_][A-Za-z0-9_]*=.+") && s.head().size() >= 3) {
                if (shape.matches("(?:val|var|def)[A-Za-z_][A-Za-z0-9_]*=#") && s.strings().size() == 1) {
                    String v = interpolate(s.strings().getFirst());
                    if (!v.contains("$"))
                        variables.put(s.head().get(1).value(), v);
                } else if (shape.matches("(?:val|var|def)[A-Za-z_][A-Za-z0-9_]*=[0-9]+")) {
                    variables.put(s.head().get(1).value(), s.head().getLast().value());
                }
                return true;
            }
            if ((context.isEmpty() || context.equals("ext"))
                    && shape.matches("(?:(?:project|rootProject)\\.)?ext\\.[A-Za-z_][A-Za-z0-9_]*=.+")) {
                int eq = -1;
                for (int i = 0; i < s.head().size(); i++)
                    if (s.head().get(i).value().equals("=")) {
                        eq = i;
                        break;
                    }
                if (eq > 0) {
                    String name = s.head().get(eq - 1).value();
                    if (shape.endsWith("=#") && s.strings().size() == 1) {
                        String v = interpolate(s.strings().getFirst());
                        if (!v.contains("$"))
                            variables.put(name, v);
                    } else if (eq + 2 == s.head().size() && !s.head().getLast().literal()
                            && s.head().getLast().value().matches("[0-9]+")) {
                        variables.put(name, s.head().getLast().value());
                    }
                }
                return true;
            }
            if ((context.isEmpty() || context.equals("ext")) && (shape.matches("extra\\[#\\]=(?:#|[0-9]+)")
                    || shape.matches("extra\\.set\\(#,(?:#|[0-9]+)\\)"))) {
                if (!s.strings().isEmpty()) {
                    String name = s.strings().getFirst();
                    String v = s.strings().size() == 2 ? interpolate(s.strings().get(1))
                            : !s.head().getLast().literal() && s.head().getLast().value().matches("[0-9]+")
                                    ? s.head().getLast().value()
                                    : s.head().size() >= 2 && !s.head().get(s.head().size() - 2).literal()
                                            && s.head().get(s.head().size() - 2).value().matches("[0-9]+")
                                                    ? s.head().get(s.head().size() - 2).value()
                                                    : null;
                    if (v != null && !v.contains("$"))
                        variables.put(name, v);
                }
                return true;
            }
            if (context.equals("ext") && (shape.matches("[A-Za-z_][A-Za-z0-9_]*=.+")
                    || shape.matches("[A-Za-z_][A-Za-z0-9_]*\\.set\\((?:#|[0-9]+)\\)"))) {
                String name = s.head().getFirst().value();
                if ((shape.matches("[A-Za-z_][A-Za-z0-9_]*=#") || shape.matches("[A-Za-z_][A-Za-z0-9_]*\\.set\\(#\\)"))
                        && s.strings().size() == 1) {
                    String v = interpolate(s.strings().getFirst());
                    if (!v.contains("$"))
                        variables.put(name, v);
                } else if (shape.matches("[A-Za-z_][A-Za-z0-9_]*=[0-9]+")) {
                    variables.put(name, s.head().getLast().value());
                } else if (shape.matches("[A-Za-z_][A-Za-z0-9_]*\\.set\\([0-9]+\\)") && s.head().size() >= 5) {
                    variables.put(name, s.head().get(s.head().size() - 2).value());
                }
                return true;
            }
            return false;
        }

        boolean tryLocalFileDependency(DeclarativeScript.Statement s, String configuration, String accessor,
                List<String> exclusions) {
            if (accessor.matches("files\\(#(?:,#)*\\)") && !s.strings().isEmpty()) {
                var resolved = new ArrayList<String>(s.strings().size());
                for (String raw : s.strings()) {
                    String expanded = expandStandardBuildDirs(interpolate(raw));
                    if (expanded.contains("$"))
                        return false;
                    String normalized;
                    try {
                        normalized = join(dir, expanded);
                    } catch (IllegalArgumentException unsafe) {
                        issue(issues, UNSAFE_PATH, dir + "#" + s.offset(), identity);
                        return true;
                    }
                    if ((!normalized.endsWith(".jar") && !normalized.endsWith(".aar"))
                            || snapshotFiles.stream().noneMatch(f -> f.path().equals(normalized)))
                        return false;
                    resolved.add(normalized);
                }
                for (String jarPath : resolved) {
                    dependencies.add(new UniversalBuildModel.Dependency(configuration + ".local-file", Optional.empty(),
                            Optional.empty(), jarPath, evidence, exclusions));
                }
                return true;
            }
            if (accessor.startsWith("fileTree(") && accessor.endsWith(")")) {
                String rawDir;
                List<String> includeGlobs;
                if (accessor.matches("fileTree\\((?:(?:dir|directory)[:=])?#\\)") && s.strings().size() == 1) {
                    rawDir = s.strings().getFirst();
                    includeGlobs = List.of();
                } else if (accessor.matches(
                        "fileTree\\((?:dir|directory)[:=]#,(?:include|includes)[:=](?:#|\\[#(?:,#)*\\]|(?:listOf|setOf)\\(#(?:,#)*\\))\\)")
                        && s.strings().size() >= 2) {
                    rawDir = s.strings().getFirst();
                    includeGlobs = s.strings().subList(1, s.strings().size());
                } else if (accessor.matches(
                        "fileTree\\(mapOf\\(#to#(?:,#to(?:#|\\[#(?:,#)*\\]|(?:listOf|setOf)\\(#(?:,#)*\\)))?\\)\\)")
                        && s.strings().size() >= 2) {
                    if (!s.strings().getFirst().equals("dir"))
                        return false;
                    rawDir = s.strings().get(1);
                    if (s.strings().size() == 2)
                        includeGlobs = List.of();
                    else if (s.strings().size() >= 4 && s.strings().get(2).equals("include"))
                        includeGlobs = s.strings().subList(3, s.strings().size());
                    else
                        return false;
                } else
                    return false;
                String expandedDir = expandStandardBuildDirs(interpolate(rawDir));
                if (expandedDir.contains("$"))
                    return false;
                String normalizedDir;
                try {
                    normalizedDir = join(dir, expandedDir);
                } catch (IllegalArgumentException unsafe) {
                    issue(issues, UNSAFE_PATH, dir + "#" + s.offset(), identity);
                    return true;
                }
                var globs = new ArrayList<String>(includeGlobs.size());
                for (String inc : includeGlobs) {
                    String g = interpolate(inc);
                    if (!Set.of("*.jar", "**/*.jar", "*.aar", "**/*.aar").contains(g))
                        return false;
                    globs.add(g);
                }
                boolean recursive = globs.isEmpty() || globs.stream().anyMatch(g -> g.startsWith("**"));
                boolean wantsJars = globs.isEmpty() || globs.stream().anyMatch(g -> g.endsWith(".jar"));
                boolean wantsAars = globs.isEmpty() || globs.stream().anyMatch(g -> g.endsWith(".aar"));
                if (wantsJars || wantsAars) {
                    String prefix = normalizedDir.equals(".") ? "" : normalizedDir + "/";
                    var matches = new TreeSet<String>();
                    for (var f : snapshotFiles) {
                        if (!f.path().startsWith(prefix))
                            continue;
                        String rest = f.path().substring(prefix.length());
                        boolean matchedExt = (wantsJars && rest.endsWith(".jar"))
                                || (wantsAars && rest.endsWith(".aar"));
                        if ((recursive || !rest.contains("/")) && matchedExt)
                            matches.add(f.path());
                    }
                    for (String localFile : matches) {
                        dependencies.add(new UniversalBuildModel.Dependency(configuration + ".local-file",
                                Optional.empty(), Optional.empty(), localFile, evidence, exclusions));
                    }
                }
                return true;
            }
            return false;
        }

        String interpolate(String value) {
            if (!value.contains("$") || variables.isEmpty())
                return value;
            var out = new StringBuilder(value.length());
            for (int i = 0; i < value.length();) {
                char c = value.charAt(i);
                if (c == '$') {
                    if (i + 1 < value.length() && value.charAt(i + 1) == '{') {
                        int end = value.indexOf('}', i + 2);
                        if (end > i + 2) {
                            String resolved = lookupVariable(value.substring(i + 2, end).trim());
                            if (resolved != null) {
                                out.append(resolved);
                                i = end + 1;
                                continue;
                            }
                        }
                    } else if (i + 1 < value.length() && Character.isJavaIdentifierStart(value.charAt(i + 1))) {
                        int j = i + 2;
                        while (j < value.length()
                                && (Character.isJavaIdentifierPart(value.charAt(j)) || value.charAt(j) == '.'))
                            j++;
                        while (j > i + 2 && value.charAt(j - 1) == '.')
                            j--;
                        String resolved = lookupVariable(value.substring(i + 1, j));
                        if (resolved != null) {
                            out.append(resolved);
                            i = j;
                            continue;
                        }
                    }
                }
                out.append(c);
                i++;
            }
            return out.toString();
        }

        private String lookupVariable(String rawKey) {
            String direct = variables.get(rawKey);
            if (direct != null)
                return direct;
            for (String prefix : List.of("rootProject.ext.", "rootProject.", "project.ext.", "project.", "ext.")) {
                if (rawKey.startsWith(prefix)) {
                    String stripped = variables.get(rawKey.substring(prefix.length()));
                    if (stripped != null)
                        return stripped;
                }
            }
            return null;
        }

        String literal(DeclarativeScript.Statement s) {
            if (s.strings().size() != 1)
                throw new IllegalArgumentException();
            String v = interpolate(s.strings().getFirst());
            if (v.contains("$"))
                throw new IllegalArgumentException();
            return v;
        }

        Optional<Integer> extractAndroidCompileSdk(DeclarativeScript.Statement s) {
            if (!s.strings().isEmpty()) {
                String v = interpolate(s.strings().getFirst()).replaceFirst("^android-", "").trim();
                if (v.matches("[0-9]{1,3}")) {
                    int n = Integer.parseInt(v);
                    if (n >= 1 && n <= 999)
                        return Optional.of(n);
                }
            }
            for (int i = 1; i < s.head().size(); i++) {
                var tok = s.head().get(i);
                if (!tok.literal() && tok.value().matches("[0-9]{1,3}")) {
                    int n = Integer.parseInt(tok.value());
                    if (n >= 1 && n <= 999)
                        return Optional.of(n);
                }
            }
            if (s.head().size() >= 3) {
                String lastExpr = s.head().subList(2, s.head().size()).stream()
                        .map(DeclarativeScript.Token::value)
                        .collect(java.util.stream.Collectors.joining());
                String resolved = lookupVariable(lastExpr);
                if (resolved != null) {
                    String v = resolved.replaceFirst("^android-", "").trim();
                    if (v.matches("[0-9]{1,3}")) {
                        int n = Integer.parseInt(v);
                        if (n >= 1 && n <= 999)
                            return Optional.of(n);
                    }
                }
            }
            return Optional.empty();
        }

        void gap(DeclarativeScript.Statement s) {
            issue(issues, DYNAMIC_BUILD_LOGIC, dir + "#" + s.offset(), identity);
        }

        SourcePlanModel plan(ModuleDescriptor descriptor, UniversalBuildModel.Tool tool) {
            if (syntax.isEmpty() || platform.isEmpty() || encoding.isEmpty())
                issue(issues, COMPILER_CONFIGURATION_MISSING, dir, identity);
            var origin = tool == UniversalBuildModel.Tool.PLAIN_JAVA ? Origin.PLAIN_JAVA_CONVENTION
                    : tool == UniversalBuildModel.Tool.MAVEN ? Origin.MAVEN_CONVENTION : Origin.GRADLE_CONVENTION;
            Map<String, Setting> compilerSettings = androidModule
                    ? Map.of("android.compileSdk",
                            setting("android.compileSdk",
                                    androidCompileSdk.map(Object::toString).or(() -> Optional.of("latest")),
                                    androidCompileSdk.isPresent() ? Origin.EFFECTIVE_MODEL : Origin.GRADLE_CONVENTION))
                    : Map.of();
            var sets = new ArrayList<SourceSetPlan>();
            for (var kind : List.of(Kind.MAIN, Kind.TEST)) {
                var gaps = new ArrayList<Gap>();
                if (syntax.isEmpty())
                    gaps.add(Gap.MISSING_SOURCE_LEVEL);
                if (platform.isEmpty())
                    gaps.add(Gap.MISSING_PLATFORM_RELEASE);
                if (encoding.isEmpty())
                    gaps.add(Gap.MISSING_ENCODING);
                if (issues.stream().anyMatch(i -> i.subject().startsWith(dir + "#")
                        && i.reason() != CUSTOM_SOURCE_SET_ROLE_UNRESOLVED))
                    gaps.add(Gap.PLUGIN_EFFECTS_NOT_EVALUATED);
                var roots = kind == Kind.MAIN ? mainRoots : testRoots;
                var resources = kind == Kind.MAIN ? mainResources : testResources;
                sets.add(new SourceSetPlan(descriptor.identity(), kind,
                        roots.stream()
                                .map(p -> setting("sourceRoot", Optional.of(p),
                                        declaredRoots.contains(p) ? Origin.EFFECTIVE_MODEL : origin))
                                .toList(),
                        resources.stream()
                                .map(p -> setting("resourceRoot", Optional.of(p),
                                        declaredRoots.contains(p) ? Origin.EFFECTIVE_MODEL : origin))
                                .toList(),
                        setting("output", Optional.empty(), origin), compilerSettings,
                        setting("source", syntax.map(Object::toString), compilerOrigin("source")),
                        setting("target", target.map(Object::toString), compilerOrigin("target")),
                        setting("release", platform.map(Object::toString), compilerOrigin("release")),
                        setting("encoding", encoding, compilerOrigin("encoding")), List.of(), gaps));
            }
            for (var entry : customRoots.entrySet()) {
                String name = entry.getKey();
                var gaps = new ArrayList<Gap>();
                gaps.add(Gap.UNRESOLVED_SOURCE_ROLE);
                if (syntax.isEmpty())
                    gaps.add(Gap.MISSING_SOURCE_LEVEL);
                if (platform.isEmpty())
                    gaps.add(Gap.MISSING_PLATFORM_RELEASE);
                if (encoding.isEmpty())
                    gaps.add(Gap.MISSING_ENCODING);
                sets.add(new SourceSetPlan(descriptor.identity(), Kind.CUSTOM, name, SourceClassification.OTHER,
                        entry.getValue().stream().map(p -> setting("sourceRoot", Optional.of(p),
                                declaredRoots.contains(p) ? Origin.EFFECTIVE_MODEL : origin)).toList(),
                        customResources.get(name).stream().map(p -> setting("resourceRoot", Optional.of(p),
                                declaredRoots.contains(p) ? Origin.EFFECTIVE_MODEL : origin)).toList(),
                        setting("output", Optional.empty(), origin), compilerSettings,
                        setting("source", syntax.map(Object::toString), compilerOrigin("source")),
                        setting("target", target.map(Object::toString), compilerOrigin("target")),
                        setting("release", platform.map(Object::toString), compilerOrigin("release")),
                        setting("encoding", encoding, compilerOrigin("encoding")), List.of(), gaps));
            }
            return new SourcePlanModel(sets, List.of());
        }

        Origin compilerOrigin(String key) {
            return declared.contains(key) ? Origin.EFFECTIVE_MODEL : Origin.USER_PROPERTY;
        }

        Setting setting(String key, Optional<String> value, Origin origin) {
            return new Setting(key, value, value,
                    value.isEmpty() ? Status.UNSPECIFIED
                            : Set.of(Origin.GRADLE_CONVENTION, Origin.PLAIN_JAVA_CONVENTION).contains(origin)
                                    ? Status.DEFAULT
                                    : Status.DECLARED,
                    value.isEmpty() ? Origin.ABSENT : origin, evidence);
        }
    }

    private static SourcePlanModel withMavenKotlinRoots(SourcePlanModel plan, String dir, List<SnapshotFile> files,
            List<BuildModelResult.PomEvidence> inputs) {
        String ktMain = join(dir, "src/main/kotlin"), ktTest = join(dir, "src/test/kotlin");
        List<String> mainGenerated = List.of(
                join(dir, "target/generated-sources/annotations"),
                join(dir, "target/generated-sources/protobuf/java"),
                join(dir, "target/generated-sources/protobuf/grpc-java"),
                join(dir, "target/generated-sources/delombok"));
        String testGenerated = join(dir, "target/generated-test-sources/test-annotations");
        boolean hasKtMain = Projection.hasFilesUnder(files, ktMain),
                hasKtTest = Projection.hasFilesUnder(files, ktTest);
        List<String> activeMainGenerated = mainGenerated.stream()
                .filter(root -> Projection.hasFilesUnder(files, root))
                .toList();
        boolean hasTestGenerated = Projection.hasFilesUnder(files, testGenerated);
        if (!hasKtMain && !hasKtTest && activeMainGenerated.isEmpty() && !hasTestGenerated)
            return plan;
        var updatedSets = new ArrayList<SourceSetPlan>();
        for (var set : plan.sourceSets()) {
            List<String> candidates = new ArrayList<>();
            if (set.kind() == Kind.MAIN) {
                if (hasKtMain)
                    candidates.add(ktMain);
                candidates.addAll(activeMainGenerated);
            } else if (set.kind() == Kind.TEST) {
                if (hasKtTest)
                    candidates.add(ktTest);
                if (hasTestGenerated)
                    candidates.add(testGenerated);
            }
            List<String> missing = candidates.stream()
                    .filter(candidate -> set.sourceRoots().stream().flatMap(r -> r.value().stream())
                            .noneMatch(candidate::equals))
                    .toList();
            if (!missing.isEmpty()) {
                var roots = new ArrayList<>(set.sourceRoots());
                for (String candidate : missing) {
                    roots.add(new Setting("sourceRoot", Optional.of(candidate), Optional.of(candidate), Status.DEFAULT,
                            Origin.MAVEN_CONVENTION, inputs));
                }
                updatedSets.add(new SourceSetPlan(set.module(), set.kind(), set.name(), set.semanticRole(), roots,
                        set.resourceRoots(),
                        set.outputDirectory(), set.compilerSettings(), set.syntaxLevel(), set.bytecodeTarget(),
                        set.platformRelease(), set.encoding(), set.generatedSourceHints(), set.gaps()));
            } else
                updatedSets.add(set);
        }
        return new SourcePlanModel(updatedSets, plan.plugins());
    }

    private static final String PATH_EXPR = "(?:#|(?:file|files)\\(#(?:,#)*\\))";
    private static final String PATH_LIST = PATH_EXPR + "(?:," + PATH_EXPR + ")*";
    private static final String SRC_DIR_SHAPE = "(?:srcDir|srcDirs|resourceDirs)(?:\\(" + PATH_LIST + "\\)|" + PATH_EXPR
            + "|\\+=(?:" + PATH_EXPR + "|\\[" + PATH_LIST + "\\]|(?:listOf|setOf)\\(" + PATH_LIST + "\\))"
            + "|=(?:\\[" + PATH_LIST + "\\]|files\\(#(?:,#)*\\)|(?:listOf|setOf)\\(" + PATH_LIST + "\\)))"
            + "|setSrcDirs\\((?:" + PATH_LIST + "|\\[" + PATH_LIST + "\\]|(?:listOf|setOf)\\(" + PATH_LIST + "\\))\\)";

    private static String directory(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? "." : path.substring(0, i);
    }

    private static int compareDirectories(String left, String right) {
        if (left.equals(right))
            return 0;
        if (left.equals("."))
            return -1;
        if (right.equals("."))
            return 1;
        String[] a = left.split("/"), b = right.split("/");
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            int cmp = a[i].compareTo(b[i]);
            if (cmp != 0)
                return cmp;
        }
        return Integer.compare(a.length, b.length);
    }

    public static String expandStandardBuildDirs(String path) {
        if (path == null || !path.contains("$"))
            return path;
        return path.replace("${project.buildDir}", "build").replace("${buildDir}", "build")
                .replaceAll("\\$project\\.buildDir(?![A-Za-z0-9_])", "build")
                .replaceAll("\\$buildDir(?![A-Za-z0-9_])", "build")
                .replace("${project.projectDir}", ".").replace("${projectDir}", ".")
                .replaceAll("\\$project\\.projectDir(?![A-Za-z0-9_])", ".")
                .replaceAll("\\$projectDir(?![A-Za-z0-9_])", ".");
    }

    public static Optional<String> normalizeRepositoryPath(String path) {
        try {
            return Optional.of(join(".", path));
        } catch (IllegalArgumentException unsafe) {
            return Optional.empty();
        }
    }

    private static boolean fixedVersion(String version) {
        if (version == null || version.isBlank())
            return false;
        String upper = version.toUpperCase(Locale.ROOT);
        return !version.contains("+") && !version.contains("$") && !version.contains("[") && !version.contains("(")
                && !version.contains("]") && !version.contains(")") && !upper.endsWith("-SNAPSHOT")
                && !upper.equals("LATEST") && !upper.equals("RELEASE") && !version.startsWith("latest.");
    }

    private static String filename(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    static String join(String dir, String path) {
        ContractChecks.text(path, "build path");
        if (path.startsWith("/") || path.contains("\\") || path.contains(":") || path.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Unsafe build path: " + path);
        var parts = new ArrayDeque<String>();
        if (!dir.equals("."))
            for (String seg : dir.split("/"))
                if (!seg.isEmpty() && !seg.equals("."))
                    parts.addLast(seg);
        for (String seg : path.split("/", -1)) {
            if (seg.isEmpty() || seg.equals("."))
                continue;
            if (seg.equals("..")) {
                if (parts.isEmpty())
                    throw new IllegalArgumentException("Build path escapes repository root: " + path);
                parts.removeLast();
            } else {
                ContractChecks.repositoryRelativePath(seg, "build path");
                parts.addLast(seg);
            }
        }
        return parts.isEmpty() ? "." : String.join("/", parts);
    }

    private static void extractBuildSrcConstants(
            RepositoryInputs inputs,
            int[] fileCounter,
            Policy policy,
            List<IngestionEvidence.Issue> issues,
            Map<String, Map<String, String>> constantsByRoot,
            Map<String, List<BuildModelResult.PomEvidence>> evidenceByRoot) {
        var ambiguousByRoot = new HashMap<String, Set<String>>();
        var typePattern = java.util.regex.Pattern.compile("(?:object|class|interface)\\s+([A-Za-z_][A-Za-z0-9_]*)\\b");
        var ktConstPattern = java.util.regex.Pattern.compile(
                "(?:const\\s+)?val\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?::\\s*(?:String|Int))?\\s*=\\s*(?:\"([^\"\\\\]*)\"|([0-9]+))");
        var javaConstPattern = java.util.regex.Pattern.compile(
                "(?:(?:public|protected|private)\\s+)?static\\s+final\\s+(?:String|int)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(?:\"([^\"\\\\]*)\"|([0-9]+))");
        for (var file : inputs.snapshot().files()) {
            String path = file.path();
            if (!(path.endsWith(".kt") || path.endsWith(".java")))
                continue;
            String root = null;
            if (path.startsWith("buildSrc/src/main/kotlin/") || path.startsWith("buildSrc/src/main/java/"))
                root = ".";
            else {
                int idx = path.indexOf("/buildSrc/src/main/");
                if (idx > 0 && (path.startsWith("kotlin/", idx + "/buildSrc/src/main/".length())
                        || path.startsWith("java/", idx + "/buildSrc/src/main/".length())))
                    root = path.substring(0, idx);
            }
            if (root == null)
                continue;
            if (++fileCounter[0] > policy.maxFiles()) {
                issue(issues, INPUT_LIMIT, path, file.contentDigest());
                continue;
            }
            var source = inputs.files().get(path);
            if (source == null) {
                issue(issues, INPUT_UNAVAILABLE, path, file.contentDigest());
                continue;
            }
            if (source.text().length() > policy.maxCharacters()) {
                issue(issues, INPUT_LIMIT, path, file.contentDigest());
                continue;
            }
            var rootConsts = constantsByRoot.computeIfAbsent(root, ignored -> new TreeMap<>());
            var ambiguous = ambiguousByRoot.computeIfAbsent(root, ignored -> new HashSet<>());
            var localConsts = new HashMap<String, String>();
            var typeStack = new ArrayDeque<String>();
            var depthStack = new ArrayDeque<Integer>();
            int braceDepth = 0;
            boolean blockComment = false;
            for (String rawLine : source.text().split("\\R", -1)) {
                StringBuilder cleaned = new StringBuilder();
                boolean inString = false;
                for (int i = 0; i < rawLine.length(); i++) {
                    char c = rawLine.charAt(i);
                    if (blockComment) {
                        if (c == '*' && i + 1 < rawLine.length() && rawLine.charAt(i + 1) == '/') {
                            blockComment = false;
                            i++;
                        }
                        continue;
                    }
                    if (!inString && c == '/' && i + 1 < rawLine.length() && rawLine.charAt(i + 1) == '/')
                        break;
                    if (!inString && c == '/' && i + 1 < rawLine.length() && rawLine.charAt(i + 1) == '*') {
                        blockComment = true;
                        i++;
                        continue;
                    }
                    if (c == '"' && (i == 0 || rawLine.charAt(i - 1) != '\\'))
                        inString = !inString;
                    cleaned.append(c);
                }
                String line = cleaned.toString().trim();
                if (!line.isEmpty()) {
                    var typeMatcher = typePattern.matcher(line);
                    if (typeMatcher.find() && line.contains("{")) {
                        typeStack.addLast(typeMatcher.group(1));
                        depthStack.addLast(braceDepth + 1);
                    }
                    var m = (path.endsWith(".kt") ? ktConstPattern : javaConstPattern).matcher(line);
                    if (m.find()) {
                        String varName = m.group(1), rawVal = m.group(2) != null ? m.group(2) : m.group(3);
                        String val = rawVal;
                        if (val.contains("$")) {
                            for (var e : localConsts.entrySet()) {
                                val = val.replace("${" + e.getKey() + "}", e.getValue());
                                val = val.replaceAll(
                                        "\\$" + java.util.regex.Pattern.quote(e.getKey()) + "(?![A-Za-z0-9_])",
                                        java.util.regex.Matcher.quoteReplacement(e.getValue()));
                            }
                        }
                        if (!val.contains("$")) {
                            localConsts.put(varName, val);
                            String qualified = typeStack.isEmpty() ? varName
                                    : String.join(".", typeStack) + "." + varName;
                            if (!ambiguous.contains(qualified)) {
                                String existing = rootConsts.putIfAbsent(qualified, val);
                                if (existing != null && !existing.equals(val)) {
                                    rootConsts.remove(qualified);
                                    ambiguous.add(qualified);
                                }
                            }
                        }
                    }
                }
                inString = false;
                for (int i = 0; i < line.length(); i++) {
                    char c = line.charAt(i);
                    if (c == '"' && (i == 0 || line.charAt(i - 1) != '\\'))
                        inString = !inString;
                    else if (!inString) {
                        if (c == '{')
                            braceDepth++;
                        else if (c == '}') {
                            braceDepth = Math.max(0, braceDepth - 1);
                            while (!depthStack.isEmpty() && braceDepth < depthStack.peekLast()) {
                                depthStack.removeLast();
                                typeStack.removeLast();
                            }
                        }
                    }
                }
            }
            evidenceByRoot.computeIfAbsent(root, ignored -> new ArrayList<>())
                    .add(new BuildModelResult.PomEvidence(path, file.contentDigest()));
        }
    }

    private static void issue(List<IngestionEvidence.Issue> issues, IngestionEvidence.Reason reason, String subject,
            ContentDigest input) {
        issues.add(new IngestionEvidence.Issue(reason, subject, List.of(input)));
    }
}
