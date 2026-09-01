package com.evolution.poc;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MarkerAnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.HexFormat;
import java.util.function.Supplier;
import java.util.stream.Stream;

final class ParserEvaluator {

    static final String SCHEMA_VERSION = "2.0";
    static final String LANGUAGE_LEVEL = "JAVA_21";

    record Options(
            Path sourceRoot,
            String configurationId,
            List<Path> classpathEntries,
            Path groundTruthFile,
            String targetRepositoryCommit) {
        Options {
            sourceRoot = sourceRoot.toAbsolutePath().normalize();
            configurationId = Objects.requireNonNull(configurationId, "configurationId");
            classpathEntries = List.copyOf(classpathEntries);
        }
    }

    enum RelationshipCategory {
        EXTENDS,
        IMPLEMENTS,
        ANNOTATED_WITH,
        RETURNS,
        CONSTRUCTOR_PARAMETER,
        CALLS
    }

    enum ResolutionStatus {
        RESOLVED,
        UNRESOLVED,
        ERROR
    }

    enum TargetOrigin {
        PROJECT_LOCAL,
        JDK,
        EXTERNAL_DEPENDENCY,
        LANGUAGE,
        UNRESOLVED
    }

    enum ExpectedResolution {
        RESOLVED,
        UNRESOLVED
    }

    enum GroundTruthOutcome {
        CORRECTLY_RESOLVED,
        INCORRECTLY_RESOLVED,
        UNRESOLVED,
        OMITTED,
        EVALUATION_ERROR
    }

    record SourceSpan(int beginLine, int beginColumn, int endLine, int endColumn) {
    }

    record Relationship(
            RelationshipCategory category,
            String sourceIdentity,
            String targetIdentity,
            TargetOrigin targetOrigin,
            String sourceFile,
            SourceSpan sourceSpan,
            ResolutionStatus resolutionStatus,
            String exceptionType,
            String exceptionMessage,
            String configurationId) {
    }

    record ParseFailure(String sourceFile, List<String> problems) {
    }

    record StatusCounts(int attempted, int resolved, int unresolved, int errors) {
    }

    record ClasspathEntry(String path, long sizeBytes, String sha256) {
    }

    record Summary(
            int parsedFiles,
            int failedFiles,
            int relationshipsAttempted,
            int relationshipsResolved,
            int relationshipsUnresolved,
            int relationshipErrors,
            Map<RelationshipCategory, StatusCounts> byCategory,
            Map<TargetOrigin, Integer> resolvedByOrigin) {
    }

    record ExperimentIdentity(
            String configurationId,
            String targetRepositoryCommit,
            String evaluatorArtifactSha256,
            String runtimeJavaVersion,
            String runtimeJavaVendor,
            String compilerRelease,
            String javaParserVersion,
            String parserLanguageLevel,
            String sourceRoot,
            List<ClasspathEntry> classpathManifest,
            String commandLine,
            String timestampUtc,
            long runtimeDurationMillis) {
    }

    record GroundTruthCase(
            String id,
            String description,
            RelationshipCategory category,
            String sourceIdentity,
            String sourceFile,
            SourceSpan sourceSpan,
            String expectedTargetIdentity,
            Map<String, ExpectedResolution> expectedResolutionByConfiguration) {
    }

    record GroundTruthResult(
            String id,
            String description,
            boolean attempted,
            boolean passed,
            GroundTruthOutcome outcome,
            ExpectedResolution expectedResolution,
            String expectedTargetIdentity,
            ResolutionStatus actualResolutionStatus,
            String actualTargetIdentity,
            String exceptionType,
            String exceptionMessage) {
    }

    record GroundTruthSummary(
            int total,
            int attempted,
            int correctlyResolved,
            int incorrectlyResolved,
            int unresolved,
            int omitted,
            int evaluationErrors,
            int passed,
            int failed) {
    }

    record EvaluationResult(
            String schemaVersion,
            ExperimentIdentity experiment,
            Summary summary,
            GroundTruthSummary groundTruthSummary,
            List<GroundTruthResult> groundTruthResults,
            List<ParseFailure> parseFailures,
            List<Relationship> relationships) {
    }

    EvaluationResult evaluate(Options options) throws IOException {
        long startedNanos = System.nanoTime();
        Instant startedAt = Instant.now();
        validate(options);
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());
        typeSolver.add(new JavaParserTypeSolver(options.sourceRoot()));
        for (Path entry : options.classpathEntries()) {
            typeSolver.add(new JarTypeSolver(entry));
        }

        ParserConfiguration parserConfiguration = new ParserConfiguration()
                .setSymbolResolver(new JavaSymbolSolver(typeSolver))
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        JavaParser parser = new JavaParser(parserConfiguration);

        List<ParsedSource> parsedSources = new ArrayList<>();
        List<ParseFailure> parseFailures = new ArrayList<>();
        for (Path sourceFile : javaFiles(options.sourceRoot())) {
            ParseResult<CompilationUnit> parseResult = parser.parse(sourceFile);
            String relativeFile = relativePath(options.sourceRoot(), sourceFile);
            if (parseResult.isSuccessful() && parseResult.getResult().isPresent()) {
                parsedSources.add(new ParsedSource(relativeFile, parseResult.getResult().orElseThrow()));
            } else {
                List<String> problems = parseResult.getProblems().stream().map(Object::toString).toList();
                parseFailures.add(new ParseFailure(relativeFile, problems));
            }
        }

        Set<String> projectTypes = collectProjectTypes(parsedSources);
        List<Relationship> relationships = new ArrayList<>();
        for (ParsedSource parsedSource : parsedSources) {
            parsedSource.compilationUnit().accept(
                    new RelationshipVisitor(parsedSource.sourceFile(), options.configurationId(), projectTypes,
                            relationships),
                    null);
        }
        relationships.sort(Comparator
                .comparing(Relationship::sourceFile)
                .thenComparing(r -> r.sourceSpan().beginLine())
                .thenComparing(r -> r.sourceSpan().beginColumn())
                .thenComparing(r -> r.category().name())
                .thenComparing(Relationship::sourceIdentity));

        Summary summary = summarize(parsedSources.size(), parseFailures.size(), relationships);
        List<GroundTruthResult> groundTruthResults = evaluateGroundTruth(options, relationships);
        long durationMillis = (System.nanoTime() - startedNanos) / 1_000_000;
        return new EvaluationResult(
                SCHEMA_VERSION,
                experimentIdentity(options, startedAt, durationMillis),
                summary,
                summarizeGroundTruth(groundTruthResults),
                groundTruthResults,
                List.copyOf(parseFailures),
                List.copyOf(relationships));
    }

    private ExperimentIdentity experimentIdentity(Options options, Instant startedAt, long durationMillis)
            throws IOException {
        Properties buildProperties = loadBuildProperties();
        List<ClasspathEntry> classpathManifest = new ArrayList<>();
        for (Path entry : options.classpathEntries()) {
            classpathManifest.add(new ClasspathEntry(entry.toString(), Files.size(entry), sha256(entry)));
        }
        String commandLine = ProcessHandle.current().info().commandLine().orElse("<unavailable>");
        return new ExperimentIdentity(
                options.configurationId(),
                options.targetRepositoryCommit(),
                evaluatorArtifactSha256(),
                System.getProperty("java.version"),
                System.getProperty("java.vendor"),
                buildProperties.getProperty("compiler.release", "<unknown>"),
                buildProperties.getProperty("javaparser.version", "<unknown>"),
                LANGUAGE_LEVEL,
                options.sourceRoot().toString(),
                List.copyOf(classpathManifest),
                commandLine,
                startedAt.toString(),
                durationMillis);
    }

    private Properties loadBuildProperties() throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = ParserEvaluator.class.getResourceAsStream("/parser-eval.properties")) {
            if (stream == null) {
                throw new IOException("Missing filtered build metadata: parser-eval.properties");
            }
            properties.load(stream);
        }
        return properties;
    }

    private String evaluatorArtifactSha256() throws IOException {
        try {
            Path codeSource = Path.of(ParserEvaluator.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(codeSource)) {
                return sha256(codeSource);
            }
            MessageDigest digest = sha256Digest();
            try (Stream<Path> stream = Files.walk(codeSource)) {
                for (Path file : stream.filter(Files::isRegularFile).sorted().toList()) {
                    digest.update(codeSource.relativize(file).toString().replace('\\', '/')
                            .getBytes(StandardCharsets.UTF_8));
                    digest.update(Files.readAllBytes(file));
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (URISyntaxException exception) {
            throw new IOException("Cannot locate evaluator artifact", exception);
        }
    }

    private String sha256(Path file) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream stream = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", exception);
        }
    }

    private void validate(Options options) {
        if (!Files.isDirectory(options.sourceRoot())) {
            throw new IllegalArgumentException("Source root is not a directory: " + options.sourceRoot());
        }
        for (Path entry : options.classpathEntries()) {
            if (!Files.isRegularFile(entry) || !entry.toString().endsWith(".jar")) {
                throw new IllegalArgumentException("Classpath entry is not a JAR file: " + entry);
            }
        }
        if (options.groundTruthFile() != null && !Files.isRegularFile(options.groundTruthFile())) {
            throw new IllegalArgumentException("Ground-truth file does not exist: " + options.groundTruthFile());
        }
    }

    private List<Path> javaFiles(Path sourceRoot) throws IOException {
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }

    private Set<String> collectProjectTypes(List<ParsedSource> parsedSources) {
        Set<String> projectTypes = new HashSet<>();
        for (ParsedSource parsedSource : parsedSources) {
            parsedSource.compilationUnit().findAll(TypeDeclaration.class)
                    .forEach(type -> projectTypes.add(typeIdentity(type)));
        }
        return Set.copyOf(projectTypes);
    }

    private Summary summarize(int parsedFiles, int failedFiles, List<Relationship> relationships) {
        Map<RelationshipCategory, MutableStatusCounts> mutableByCategory = new EnumMap<>(RelationshipCategory.class);
        Map<TargetOrigin, Integer> byOrigin = new EnumMap<>(TargetOrigin.class);
        for (Relationship relationship : relationships) {
            mutableByCategory.computeIfAbsent(relationship.category(), ignored -> new MutableStatusCounts())
                    .accept(relationship.resolutionStatus());
            if (relationship.resolutionStatus() == ResolutionStatus.RESOLVED) {
                byOrigin.merge(relationship.targetOrigin(), 1, Integer::sum);
            }
        }
        Map<RelationshipCategory, StatusCounts> byCategory = new EnumMap<>(RelationshipCategory.class);
        for (RelationshipCategory category : RelationshipCategory.values()) {
            MutableStatusCounts counts = mutableByCategory.getOrDefault(category, new MutableStatusCounts());
            byCategory.put(category, counts.freeze());
        }
        int resolved = (int) relationships.stream()
                .filter(relationship -> relationship.resolutionStatus() == ResolutionStatus.RESOLVED)
                .count();
        int unresolved = (int) relationships.stream()
                .filter(relationship -> relationship.resolutionStatus() == ResolutionStatus.UNRESOLVED)
                .count();
        int errors = relationships.size() - resolved - unresolved;
        return new Summary(parsedFiles, failedFiles, relationships.size(), resolved, unresolved, errors,
                java.util.Collections.unmodifiableMap(new EnumMap<>(byCategory)),
                java.util.Collections.unmodifiableMap(new EnumMap<>(byOrigin)));
    }

    private List<GroundTruthResult> evaluateGroundTruth(Options options, List<Relationship> relationships)
            throws IOException {
        if (options.groundTruthFile() == null) {
            return List.of();
        }
        List<GroundTruthCase> cases;
        try (Reader reader = Files.newBufferedReader(options.groundTruthFile())) {
            cases = new Gson().fromJson(reader, new TypeToken<List<GroundTruthCase>>() {
            }.getType());
        }
        validateGroundTruth(cases, options.configurationId());
        List<GroundTruthResult> results = new ArrayList<>();
        for (GroundTruthCase groundTruth : cases) {
            List<Relationship> matches = relationships.stream()
                    .filter(relationship -> relationship.category() == groundTruth.category())
                    .filter(relationship -> relationship.sourceIdentity().equals(groundTruth.sourceIdentity()))
                    .filter(relationship -> relationship.sourceFile().equals(groundTruth.sourceFile()))
                    .filter(relationship -> relationship.sourceSpan().equals(groundTruth.sourceSpan()))
                    .toList();
            ExpectedResolution expected = groundTruth.expectedResolutionByConfiguration()
                    .get(options.configurationId());
            if (matches.isEmpty()) {
                results.add(new GroundTruthResult(groundTruth.id(), groundTruth.description(), false, false,
                        GroundTruthOutcome.OMITTED, expected, groundTruth.expectedTargetIdentity(), null, null,
                        null, null));
                continue;
            }
            if (matches.size() > 1) {
                results.add(new GroundTruthResult(groundTruth.id(), groundTruth.description(), true, false,
                        GroundTruthOutcome.EVALUATION_ERROR, expected, groundTruth.expectedTargetIdentity(), null,
                        null, IllegalStateException.class.getName(),
                        "Ground-truth locator matched " + matches.size() + " relationships"));
                continue;
            }
            Relationship actual = matches.getFirst();
            GroundTruthOutcome outcome;
            boolean passed;
            if (actual.resolutionStatus() == ResolutionStatus.RESOLVED) {
                boolean correctTarget = Objects.equals(groundTruth.expectedTargetIdentity(), actual.targetIdentity());
                passed = expected == ExpectedResolution.RESOLVED && correctTarget;
                outcome = passed ? GroundTruthOutcome.CORRECTLY_RESOLVED
                        : GroundTruthOutcome.INCORRECTLY_RESOLVED;
            } else if (actual.resolutionStatus() == ResolutionStatus.UNRESOLVED) {
                outcome = GroundTruthOutcome.UNRESOLVED;
                passed = expected == ExpectedResolution.UNRESOLVED;
            } else {
                outcome = GroundTruthOutcome.EVALUATION_ERROR;
                passed = false;
            }
            results.add(new GroundTruthResult(groundTruth.id(), groundTruth.description(), true, passed, outcome,
                    expected, groundTruth.expectedTargetIdentity(), actual.resolutionStatus(), actual.targetIdentity(),
                    actual.exceptionType(), actual.exceptionMessage()));
        }
        return List.copyOf(results);
    }

    private void validateGroundTruth(List<GroundTruthCase> cases, String configurationId) {
        if (cases == null) {
            throw new IllegalArgumentException("Ground-truth document must be a JSON array");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (GroundTruthCase groundTruth : cases) {
            if (groundTruth.id() == null || !ids.add(groundTruth.id())) {
                throw new IllegalArgumentException("Ground-truth IDs must be non-null and unique: "
                        + groundTruth.id());
            }
            if (groundTruth.category() == null || groundTruth.sourceIdentity() == null
                    || groundTruth.sourceFile() == null || groundTruth.sourceSpan() == null) {
                throw new IllegalArgumentException("Ground-truth locator is incomplete: " + groundTruth.id());
            }
            if (groundTruth.expectedResolutionByConfiguration() == null
                    || !groundTruth.expectedResolutionByConfiguration().containsKey(configurationId)) {
                throw new IllegalArgumentException("Ground truth " + groundTruth.id()
                        + " has no expectation for configuration " + configurationId);
            }
        }
    }

    private GroundTruthSummary summarizeGroundTruth(List<GroundTruthResult> results) {
        int attempted = (int) results.stream().filter(GroundTruthResult::attempted).count();
        int correct = countOutcome(results, GroundTruthOutcome.CORRECTLY_RESOLVED);
        int incorrect = countOutcome(results, GroundTruthOutcome.INCORRECTLY_RESOLVED);
        int unresolved = countOutcome(results, GroundTruthOutcome.UNRESOLVED);
        int omitted = countOutcome(results, GroundTruthOutcome.OMITTED);
        int errors = countOutcome(results, GroundTruthOutcome.EVALUATION_ERROR);
        int passed = (int) results.stream().filter(GroundTruthResult::passed).count();
        return new GroundTruthSummary(results.size(), attempted, correct, incorrect, unresolved, omitted, errors,
                passed, results.size() - passed);
    }

    private int countOutcome(List<GroundTruthResult> results, GroundTruthOutcome outcome) {
        return (int) results.stream().filter(result -> result.outcome() == outcome).count();
    }

    private static String relativePath(Path sourceRoot, Path sourceFile) {
        return sourceRoot.relativize(sourceFile.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static String typeIdentity(TypeDeclaration<?> declaration) {
        List<String> names = new ArrayList<>();
        Node current = declaration;
        while (current instanceof TypeDeclaration<?> type) {
            names.add(type.getNameAsString());
            current = type.getParentNode().orElse(null);
        }
        java.util.Collections.reverse(names);
        String packageName = declaration.findCompilationUnit()
                .flatMap(CompilationUnit::getPackageDeclaration)
                .map(packageDeclaration -> packageDeclaration.getNameAsString() + ".")
                .orElse("");
        return packageName + String.join(".", names);
    }

    private static String callableIdentity(CallableDeclaration<?> callable) {
        TypeDeclaration<?> owner = callable.findAncestor(TypeDeclaration.class)
                .orElseThrow(() -> new IllegalStateException("Callable has no declaring type"));
        String name = callable instanceof ConstructorDeclaration ? "<init>" : callable.getNameAsString();
        String parameters = callable.getParameters().stream()
                .map(parameter -> parameter.getType().asString())
                .collect(java.util.stream.Collectors.joining(","));
        return typeIdentity(owner) + "#" + name + "(" + parameters + ")";
    }

    private static String sourceIdentity(Node node) {
        if (node instanceof TypeDeclaration<?> type) {
            return typeIdentity(type);
        }
        return node.findAncestor(CallableDeclaration.class)
                .map(ParserEvaluator::callableIdentity)
                .orElseGet(() -> node.findAncestor(TypeDeclaration.class)
                        .map(ParserEvaluator::typeIdentity)
                        .orElse("<unknown>"));
    }

    private static SourceSpan sourceSpan(Node node) {
        Range range = node.getRange().orElse(new Range(new Position(-1, -1), new Position(-1, -1)));
        return new SourceSpan(range.begin.line, range.begin.column, range.end.line, range.end.column);
    }

    private static TargetOrigin classifyType(ResolvedType type, Set<String> projectTypes) {
        if (type.isVoid() || type.isPrimitive()) {
            return TargetOrigin.LANGUAGE;
        }
        if (type.isArray()) {
            return classifyType(type.asArrayType().getComponentType(), projectTypes);
        }
        if (type.isReferenceType()) {
            return classifyQualifiedName(type.asReferenceType().getQualifiedName(), projectTypes);
        }
        return TargetOrigin.EXTERNAL_DEPENDENCY;
    }

    private static TargetOrigin classifyQualifiedName(String qualifiedName, Set<String> projectTypes) {
        if (projectTypes.contains(qualifiedName)) {
            return TargetOrigin.PROJECT_LOCAL;
        }
        if (qualifiedName.startsWith("java.") || qualifiedName.startsWith("jdk.")
                || qualifiedName.startsWith("sun.")) {
            return TargetOrigin.JDK;
        }
        return TargetOrigin.EXTERNAL_DEPENDENCY;
    }

    private record ParsedSource(String sourceFile, CompilationUnit compilationUnit) {
    }

    private record ResolvedTarget(String identity, TargetOrigin origin) {
    }

    private static final class MutableStatusCounts {
        private int attempted;
        private int resolved;
        private int unresolved;
        private int errors;

        void accept(ResolutionStatus status) {
            attempted++;
            switch (status) {
                case RESOLVED -> resolved++;
                case UNRESOLVED -> unresolved++;
                case ERROR -> errors++;
            }
        }

        StatusCounts freeze() {
            return new StatusCounts(attempted, resolved, unresolved, errors);
        }
    }

    private static final class RelationshipVisitor extends VoidVisitorAdapter<Void> {
        private final String sourceFile;
        private final String configurationId;
        private final Set<String> projectTypes;
        private final List<Relationship> relationships;

        private RelationshipVisitor(String sourceFile, String configurationId, Set<String> projectTypes,
                List<Relationship> relationships) {
            this.sourceFile = sourceFile;
            this.configurationId = configurationId;
            this.projectTypes = projectTypes;
            this.relationships = relationships;
        }

        @Override
        public void visit(ClassOrInterfaceDeclaration declaration, Void arg) {
            for (ClassOrInterfaceType type : declaration.getExtendedTypes()) {
                attempt(RelationshipCategory.EXTENDS, typeIdentity(declaration), type,
                        () -> resolveType(type));
            }
            for (ClassOrInterfaceType type : declaration.getImplementedTypes()) {
                attempt(RelationshipCategory.IMPLEMENTS, typeIdentity(declaration), type,
                        () -> resolveType(type));
            }
            super.visit(declaration, arg);
        }

        @Override
        public void visit(MarkerAnnotationExpr annotation, Void arg) {
            visitAnnotation(annotation);
            super.visit(annotation, arg);
        }

        @Override
        public void visit(NormalAnnotationExpr annotation, Void arg) {
            visitAnnotation(annotation);
            super.visit(annotation, arg);
        }

        @Override
        public void visit(SingleMemberAnnotationExpr annotation, Void arg) {
            visitAnnotation(annotation);
            super.visit(annotation, arg);
        }

        private void visitAnnotation(AnnotationExpr annotation) {
            attempt(RelationshipCategory.ANNOTATED_WITH, sourceIdentity(annotation), annotation, () -> {
                String qualifiedName = annotation.resolve().getQualifiedName();
                return new ResolvedTarget(qualifiedName, classifyQualifiedName(qualifiedName, projectTypes));
            });
        }

        @Override
        public void visit(MethodDeclaration method, Void arg) {
            attempt(RelationshipCategory.RETURNS, callableIdentity(method), method.getType(), () -> {
                ResolvedType type = method.getType().resolve();
                return new ResolvedTarget(type.describe(), classifyType(type, projectTypes));
            });
            super.visit(method, arg);
        }

        @Override
        public void visit(ConstructorDeclaration constructor, Void arg) {
            for (Parameter parameter : constructor.getParameters()) {
                attempt(RelationshipCategory.CONSTRUCTOR_PARAMETER, callableIdentity(constructor), parameter, () -> {
                    ResolvedType type = parameter.getType().resolve();
                    return new ResolvedTarget(type.describe(), classifyType(type, projectTypes));
                });
            }
            super.visit(constructor, arg);
        }

        @Override
        public void visit(MethodCallExpr call, Void arg) {
            attempt(RelationshipCategory.CALLS, sourceIdentity(call), call, () -> {
                ResolvedMethodDeclaration method = call.resolve();
                String owner = method.declaringType().getQualifiedName();
                return new ResolvedTarget(method.getQualifiedSignature(), classifyQualifiedName(owner, projectTypes));
            });
            super.visit(call, arg);
        }

        private ResolvedTarget resolveType(Type type) {
            ResolvedType resolvedType = type.resolve();
            String identity = resolvedType.isReferenceType()
                    ? resolvedType.asReferenceType().getQualifiedName()
                    : resolvedType.describe();
            return new ResolvedTarget(identity, classifyType(resolvedType, projectTypes));
        }

        private void attempt(RelationshipCategory category, String sourceIdentity, Node evidence,
                Supplier<ResolvedTarget> resolver) {
            try {
                ResolvedTarget target = resolver.get();
                relationships.add(new Relationship(category, sourceIdentity, target.identity(), target.origin(),
                        sourceFile, sourceSpan(evidence), ResolutionStatus.RESOLVED, null, null, configurationId));
            } catch (UnsolvedSymbolException exception) {
                relationships.add(unresolved(category, sourceIdentity, evidence, ResolutionStatus.UNRESOLVED,
                        exception));
            } catch (RuntimeException exception) {
                relationships.add(unresolved(category, sourceIdentity, evidence, ResolutionStatus.ERROR, exception));
            }
        }

        private Relationship unresolved(RelationshipCategory category, String sourceIdentity, Node evidence,
                ResolutionStatus status, RuntimeException exception) {
            return new Relationship(category, sourceIdentity, null, TargetOrigin.UNRESOLVED, sourceFile,
                    sourceSpan(evidence), status, exception.getClass().getName(),
                    Objects.toString(exception.getMessage(), ""), configurationId);
        }
    }
}
