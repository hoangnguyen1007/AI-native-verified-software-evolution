package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.analysis.ClasspathEntry;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.EntityScope;
import com.evolution.analysis.contract.semantic.Diagnostic;
import com.evolution.analysis.contract.semantic.DiagnosticSeverity;
import com.evolution.analysis.contract.semantic.EntityOrigin;
import com.evolution.analysis.frontend.*;
import com.github.javaparser.symbolsolver.resolution.typesolvers.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.Attributes;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipFile;

/** Sources, platform loader and verified JAR snapshots only; no application classloader or directory scanning. */
final class ResolutionEnvironment {
    private static final WeightedLruCache<PlatformCacheKey, CachedArchive> PLATFORM_CACHE = new WeightedLruCache<>(384L << 20);
    private static final WeightedLruCache<JarCacheKey, CachedArchive> JAR_CACHE = new WeightedLruCache<>(512L << 20);
    private static final GroupIndexLruCache GROUP_INDEX_CACHE = new GroupIndexLruCache(128L << 20);
    private static final AtomicLong ARCHIVE_BUILDS = new AtomicLong();
    private static volatile RuntimeModulesDigest cachedRuntimeModules;

    static long cachedArchiveBuilds() {
        return ARCHIVE_BUILDS.get();
    }

    private record RuntimeModulesDigest(Path realPath, long size, java.nio.file.attribute.FileTime mtime, ContentDigest digest) {}
    private record PlatformCacheKey(PlatformInput.Format format, int release, ContentDigest digest) {}
    private record JarCacheKey(int targetRelease, ContentDigest digest) {}
    private record GroupIndexKey(int release, List<ContentDigest> digests) {}
    private record CachedGroupIndex(Map<String, Integer> firstSlotByClass, Set<String> duplicateClasses, long weightBytes) {}
    private record BinaryGroup(int baseArtifactIndex, CachedGroupIndex index) {}

    private final StrongTypeSolverCache platformSolverCache = new StrongTypeSolverCache();
    private final StrongTypeSolverCache rootSolverCache = new StrongTypeSolverCache();
    final MemoryTypeSolver project = new MemoryTypeSolver();
    final CombinedTypeSolver platform = new CombinedTypeSolver(
            CombinedTypeSolver.ExceptionHandlers.IGNORE_NONE, List.of(), platformSolverCache);
    final MemoryTypeSolver androidProject = new MemoryTypeSolver();
    final MemoryTypeSolver androidPlatform = new MemoryTypeSolver();
    final ExplicitImportTypeSolver explicitImports = new ExplicitImportTypeSolver();
    final CombinedTypeSolver solver = new CombinedTypeSolver(
            CombinedTypeSolver.ExceptionHandlers.IGNORE_NONE, List.of(project, platform), rootSolverCache);
    final Set<Diagnostic> diagnostics = new TreeSet<>();
    private final FrontendRequest request;
    private final ClasspathEntry platformEntry;
    private final List<Artifact> artifacts = new ArrayList<>();
    private final List<CachedGroupIndex> platformGroups = new ArrayList<>();
    private final List<BinaryGroup> binaryGroups = new ArrayList<>();
    private boolean hasRuntimeModulesPlatform;
    private final Map<ContentDigest, ArchiveTypeSolver> memberEvidence = new TreeMap<>();
    private final Map<Integer, MemoryTypeSolver> reactorSources = new TreeMap<>();
    private record Artifact(BinaryInput input, ArchiveTypeSolver solver) {}
    record Origin(EntityOrigin kind, EntityScope scope) {}

    void invalidateSolverCaches() {
        platformSolverCache.removeAll();
        rootSolverCache.removeAll();
    }

    ResolutionEnvironment(FrontendRequest request) {
        this.request = Objects.requireNonNull(request);
        request.originClasspath();
        platformEntry = request.platform().entry();
        int release = request.platform().release();
        // An incomplete build closure does not invalidate the independently verified inputs.
        // Missing targets remain unresolved at the individual observation, never filled by host lookup.
        try {
            var platformPaths = new HashSet<Path>();
            var platformArchiveSolvers = new ArrayList<ArchiveTypeSolver>();
            var platformGroupDigests = new ArrayList<ContentDigest>();
            for (var artifact : request.platform().artifacts()) {
                if(request.sourceRecovery()&&"false".equals(request.manifest().configuration().values().get(FrontendRequest.RECOVERY_PLATFORM_COMPATIBLE)))continue;
                Path real = artifact.path().toRealPath();
                if (!platformPaths.add(real)) {
                    throw new FrontendInputException("frontend.duplicate-platform-artifact", "Aliased platform artifacts are not allowed");
                }
                switch (artifact.format()) {
                    case RUNTIME_MODULES -> {
                        Path runtimeModules = Path.of(System.getProperty("java.home")).resolve("lib/modules");
                        boolean sameRuntimeModules = Files.exists(runtimeModules) && Files.isSameFile(real, runtimeModules);
                        if (sameRuntimeModules) {
                            verifyRuntimeModules(artifact.contentDigest(), real);
                        } else {
                            verify(artifact.contentDigest(), real);
                        }
                        if (release != Runtime.version().feature() || !sameRuntimeModules) {
                            throw new FrontendInputException("frontend.platform-view", "Running-module symbols must identify the running JDK exactly");
                        }
                        if (!platformArchiveSolvers.isEmpty()) {
                            var groupIndex = buildOrGetGroupIndex(release, platformGroupDigests, platformArchiveSolvers);
                            platformGroups.add(groupIndex);
                            platform.add(new IndexedArchiveGroupSolver(platformArchiveSolvers, groupIndex));
                            platformArchiveSolvers.clear();
                            platformGroupDigests.clear();
                        }
                        hasRuntimeModulesPlatform = true;
                        platform.add(new RuntimePlatformTypeSolver());
                    }
                    case JAR -> {
                        var key = new PlatformCacheKey(PlatformInput.Format.JAR, release, artifact.contentDigest());
                        var cached = PLATFORM_CACHE.get(key);
                        if (cached != null) {
                            verify(artifact.contentDigest(), real);
                        } else {
                            byte[] bytes = Files.readAllBytes(real);
                            verify(artifact.contentDigest(), bytes);
                            cached = buildJarArchive(bytes, release);
                            PLATFORM_CACHE.put(key, cached);
                        }
                        var s = solverFromCachedJar(cached, artifact.logicalName());
                        platformArchiveSolvers.add(s);
                        platformGroupDigests.add(artifact.contentDigest());
                    }
                    case JMOD -> {
                        verify(artifact.contentDigest(), real);
                        var key = new PlatformCacheKey(PlatformInput.Format.JMOD, 0, artifact.contentDigest());
                        var cached = PLATFORM_CACHE.get(key);
                        if (cached == null) {
                            cached = buildJmodArchive(real);
                            PLATFORM_CACHE.put(key, cached);
                        }
                        var s = new ArchiveTypeSolver(cached);
                        platformArchiveSolvers.add(s);
                        platformGroupDigests.add(artifact.contentDigest());
                    }
                    case CT_SYM -> {
                        verify(artifact.contentDigest(), real);
                        var key = new PlatformCacheKey(PlatformInput.Format.CT_SYM, release, artifact.contentDigest());
                        var cached = PLATFORM_CACHE.get(key);
                        if (cached == null) {
                            cached = buildCtSymArchive(real, release);
                            PLATFORM_CACHE.put(key, cached);
                        }
                        var s = new ArchiveTypeSolver(cached);
                        platformArchiveSolvers.add(s);
                        platformGroupDigests.add(artifact.contentDigest());
                    }
                }
            }
            if (!platformArchiveSolvers.isEmpty()) {
                var groupIndex = buildOrGetGroupIndex(release, platformGroupDigests, platformArchiveSolvers);
                platformGroups.add(groupIndex);
                platform.add(new IndexedArchiveGroupSolver(platformArchiveSolvers, groupIndex));
            }
            var paths = new HashSet<Path>();
            for (var input : request.dependencies()) {
                Path real = input.path().toRealPath();
                if (!paths.add(real)) throw new FrontendInputException("frontend.duplicate-binary", "Aliased binary inputs are not allowed");
                var key = new JarCacheKey(release, input.entry().contentDigest());
                var cached = JAR_CACHE.get(key);
                if (cached != null) {
                    verify(input.entry().contentDigest(), real);
                } else {
                    byte[] bytes = Files.readAllBytes(real);
                    verify(input.entry(), bytes);
                    cached = buildJarArchive(bytes, release);
                    JAR_CACHE.put(key, cached);
                }
                var jar = solverFromCachedJar(cached, input.entry().logicalName());
                artifacts.add(new Artifact(input, jar));
            }
            if(request.correspondence().isPresent()){
                var binding=request.correspondence().orElseThrow();
                for(var view:binding.binaryViews().entrySet()){
                    if(view.getValue().size()>32_000_000)throw new FrontendInputException("frontend.native-member-worker","A larger member-evidence worker is required");
                    byte[] bytes;try(var in=binding.openBinaryView(view.getKey())){bytes=in.readNBytes(Math.toIntExact(view.getValue().size()+1));}
                    verify(view.getValue().digest(),bytes);
                    var key = new JarCacheKey(release, view.getValue().digest());
                    var cached = JAR_CACHE.get(key);
                    if (cached == null) {
                        cached = buildJarArchive(bytes, release);
                        JAR_CACHE.put(key, cached);
                    }
                    var memberSolver = solverFromCachedJar(cached, "native-member:" + view.getKey().value());
                    memberSolver.setParent(solver);
                    memberEvidence.put(view.getKey(), memberSolver);
                }
            }
            Map<Integer, ReactorSourceInput> sourcePositions = new TreeMap<>();
            request.reactorSources().forEach(input -> sourcePositions.put(input.order(), input));
            int binary = 0;
            int size = request.dependencies().size() + request.reactorSources().size();
            int groupBaseArtifact = 0;
            var contiguousBinarySolvers = new ArrayList<ArchiveTypeSolver>();
            var contiguousDigests = new ArrayList<ContentDigest>();
            for (int order = 0; order < size; order++) {
                if (sourcePositions.containsKey(order)) {
                    if (!contiguousBinarySolvers.isEmpty()) {
                        var groupIndex = buildOrGetGroupIndex(release, contiguousDigests, contiguousBinarySolvers);
                        binaryGroups.add(new BinaryGroup(groupBaseArtifact, groupIndex));
                        solver.add(new IndexedArchiveGroupSolver(contiguousBinarySolvers, groupIndex));
                        contiguousBinarySolvers.clear();
                        contiguousDigests.clear();
                    }
                    var sourceSolver = new MemoryTypeSolver();
                    reactorSources.put(order, sourceSolver);
                    solver.add(sourceSolver);
                } else {
                    if (contiguousBinarySolvers.isEmpty()) {
                        groupBaseArtifact = binary;
                    }
                    var art = artifacts.get(binary++);
                    contiguousBinarySolvers.add(art.solver());
                    contiguousDigests.add(art.input().entry().contentDigest());
                }
            }
            if (!contiguousBinarySolvers.isEmpty()) {
                var groupIndex = buildOrGetGroupIndex(release, contiguousDigests, contiguousBinarySolvers);
                binaryGroups.add(new BinaryGroup(groupBaseArtifact, groupIndex));
                solver.add(new IndexedArchiveGroupSolver(contiguousBinarySolvers, groupIndex));
            }
            solver.add(androidProject);
            solver.add(androidPlatform);
            if (request.sourceRecovery()) {
                solver.add(explicitImports);
            }
            invalidateSolverCaches();
        } catch (IOException exception) {
            throw new FrontendInputException("frontend.input-io", "Cannot read a supplied platform or dependency artifact");
        }
    }
    private static final Map<String, String> PINNED_LOMBOK_DIGESTS = Map.of(
            "org.projectlombok:lombok:1.18.36@jar", "sha256:73b6b05b6a2d365b700bab08d30f94de9d336490bc0acce5b6181fef48cbf18e",
            "org.projectlombok:lombok:1.18.46@jar", "sha256:01f7b1a015e33e2b62d5f5f37053306357ab1415fd181fcba7794f5d198c1126");

    boolean isVerifiedLombokArtifact(BinaryInput input) {
        String logical = input.originEntry().logicalName();
        String digest = input.originEntry().contentDigest().value();
        if (!logical.startsWith("org.projectlombok:lombok:") || !logical.endsWith("@jar")) return false;
        if (PINNED_LOMBOK_DIGESTS.containsKey(logical) || PINNED_LOMBOK_DIGESTS.containsValue(digest)) {
            return digest.equals(PINNED_LOMBOK_DIGESTS.get(logical));
        }
        for (Artifact artifact : artifacts) {
            if (artifact.input().originEntry().equals(input.originEntry())) {
                return artifact.solver().tryToSolveType("lombok.Data").isSolved()
                        && artifact.solver().tryToSolveType("lombok.Getter").isSolved()
                        && artifact.solver().tryToSolveType("lombok.Builder").isSolved();
            }
        }
        return false;
    }

    boolean hasAnyLombokClassInDependencies() {
        Artifact found = firstArtifact("lombok.RequiredArgsConstructor");
        if (found == null) found = firstArtifact("lombok.Data");
        if (found == null) found = firstArtifact("lombok.Getter");
        return found != null;
    }

    void addReactorDeclaration(
            int order,
            String qualifiedName,
            com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration declaration) {
        MemoryTypeSolver target = reactorSources.get(order);
        if (target == null) throw new FrontendInputException(
                "frontend.reactor-source-order", "Reactor source declaration has no classpath position");
        target.addDeclaration(qualifiedName, declaration);
    }
    private static void verify(ClasspathEntry entry, byte[] bytes) {
        verify(entry.contentDigest(), bytes);
    }
    private static void verifyRuntimeModules(ContentDigest expected, Path real) throws IOException {
        var attrs = Files.readAttributes(real, java.nio.file.attribute.BasicFileAttributes.class);
        var current = cachedRuntimeModules;
        if (current == null
                || !current.realPath().equals(real)
                || current.size() != attrs.size()
                || !current.mtime().equals(attrs.lastModifiedTime())) {
            ContentDigest computed = computeDigest(real);
            current = new RuntimeModulesDigest(real, attrs.size(), attrs.lastModifiedTime(), computed);
            cachedRuntimeModules = current;
        }
        if (!expected.equals(current.digest())) {
            throw new FrontendInputException("frontend.artifact-digest", "Artifact bytes differ from the manifest");
        }
    }
    private static ContentDigest computeDigest(Path path) throws IOException {
        java.security.MessageDigest digest;
        try { digest = java.security.MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        byte[] buffer = new byte[65536];
        try (var input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return new ContentDigest("sha256:" + HexFormat.of().formatHex(digest.digest()));
    }
    private static void verify(ContentDigest expected, Path path) throws IOException {
        // A path, length and mtime do not authenticate mutable bytes. Rehash on
        // every request; only the parsed, content-addressed archive is reusable.
        if (!expected.equals(computeDigest(path))) {
            throw new FrontendInputException("frontend.artifact-digest", "Artifact bytes differ from the manifest");
        }
    }
    private static void verify(ContentDigest expected, byte[] bytes) {
        if (!ContentDigest.sha256(bytes).equals(expected)) throw new FrontendInputException("frontend.artifact-digest", "Artifact bytes differ from the manifest");
    }
    private static CachedArchive buildJmodArchive(Path path) throws IOException {
        var classEntries = new LinkedHashMap<String, byte[]>();
        try (ZipFile jmod = new ZipFile(path.toFile())) {
            List<? extends java.util.zip.ZipEntry> entries = jmod.stream()
                    .filter(entry -> !entry.isDirectory()
                            && entry.getName().startsWith("classes/")
                            && entry.getName().endsWith(".class")
                            && !entry.getName().equals("classes/module-info.class"))
                    .sorted(Comparator.comparing(java.util.zip.ZipEntry::getName)).toList();
            for (var source : entries) {
                String name = source.getName().substring("classes/".length());
                try (InputStream input = jmod.getInputStream(source)) {
                    if (classEntries.put(name, input.readAllBytes()) != null) {
                        throw new FrontendInputException("frontend.jmod-duplicate", "JMOD contains duplicate platform classes");
                    }
                }
            }
            if (classEntries.isEmpty()) {
                // Aggregator/native-only JDK modules legitimately declare no Java types.
                // Retain the original artifact/hash in the platform manifest; validate its descriptor.
                var descriptor = jmod.getEntry("classes/module-info.class");
                if (descriptor == null) throw new FrontendInputException("frontend.jmod-empty", "JMOD has no classes or module descriptor");
                try (var input = jmod.getInputStream(descriptor)) {
                    java.lang.module.ModuleDescriptor.read(input);
                } catch (java.lang.module.InvalidModuleDescriptorException invalid) {
                    throw new FrontendInputException("frontend.jmod-descriptor", "JMOD module descriptor is invalid");
                }
                return CachedArchive.fromClassEntries(Map.of(), Optional.empty());
            }
        }
        return CachedArchive.fromClassEntries(classEntries, Optional.empty());
    }
    private static CachedArchive buildCtSymArchive(Path path, int release) throws IOException {
        String code = releaseCode(release);
        if (code == null) throw new FrontendInputException("frontend.ct-sym-release", "ct.sym release is unsupported");
        var classEntries = new LinkedHashMap<String, byte[]>();
        try (ZipFile ctSym = new ZipFile(path.toFile())) {
            List<? extends java.util.zip.ZipEntry> entries = ctSym.stream()
                    .filter(entry -> {
                        String[] parts = entry.getName().split("/", 3);
                        return !entry.isDirectory() && parts.length == 3 && parts[0].contains(code)
                                && parts[2].endsWith(".sig") && !parts[2].equals("module-info.sig");
                    })
                    .sorted(Comparator.comparing(java.util.zip.ZipEntry::getName)).toList();
            for (var source : entries) {
                String[] parts = source.getName().split("/", 3);
                String name = parts[2].substring(0, parts[2].length() - ".sig".length()) + ".class";
                try (InputStream input = ctSym.getInputStream(source)) {
                    if (classEntries.put(name, input.readAllBytes()) != null) {
                        throw new FrontendInputException(
                                "frontend.ct-sym-duplicate", "ct.sym contains duplicate platform classes for the requested release");
                    }
                }
            }
        }
        if (classEntries.isEmpty()) throw new FrontendInputException(
                "frontend.ct-sym-empty", "ct.sym contains no classes for the requested release");
        return CachedArchive.fromClassEntries(classEntries, Optional.empty());
    }
    private static String releaseCode(int release) {
        if (release >= 0 && release <= 9) return Integer.toString(release);
        if (release >= 10 && release <= 35) return Character.toString((char) ('A' + release - 10));
        return null;
    }
    private record PhysicalJarEntry(String name, byte[] bytes) {}
    private record SelectedJarEntry(int version, byte[] bytes) {}

    private ArchiveTypeSolver solverFromCachedJar(CachedArchive cached, String logicalName) {
        cached.manifestClasspath().ifPresent(classpath -> diagnostics.add(new Diagnostic(
                DiagnosticSeverity.WARNING, "frontend.jar-classpath-unmodeled",
                "JAR manifest Class-Path is recorded but not expanded; the exact supplied classpath remains authoritative",
                Optional.empty(), Map.of("artifact", logicalName, "classPathSha256",
                        ContentDigest.sha256Utf8(classpath).value()))));
        return new ArchiveTypeSolver(cached);
    }

    /** Materializes the exact target-release class view so SymbolSolver never guesses MR-JAR selection. */
    private static CachedArchive buildJarArchive(byte[] bytes, int targetRelease) throws IOException {
        if(bytes.length>=22&&bytes[0]=='P'&&bytes[1]=='K'&&bytes[2]==5&&bytes[3]==6){
            var end=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            if(end.getShort(4)==0&&end.getShort(6)==0&&end.getShort(8)==0&&end.getShort(10)==0&&end.getInt(12)==0&&end.getInt(16)==0&&Short.toUnsignedInt(end.getShort(20))==bytes.length-22)
                return CachedArchive.fromClassEntries(Map.of(), Optional.empty());
        }
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K' || bytes[2] != 3 || bytes[3] != 4)
            throw new FrontendInputException("frontend.jar-format", "Dependency is not a JAR archive");
        var physical = new ArrayList<PhysicalJarEntry>();
        var seen = new HashSet<String>();
        Manifest manifest;
        long expandedBytes = 0;
        try (var jar = new JarInputStream(new ByteArrayInputStream(bytes))) {
            manifest = jar.getManifest();
            if (manifest != null) seen.add("META-INF/MANIFEST.MF");
            for (var entry = jar.getNextJarEntry(); entry != null; entry = jar.getNextJarEntry()) {
                if (entry.isDirectory()) continue;
                if (!seen.add(entry.getName())) continue;
                if(!entry.getName().endsWith(".class")&&!entry.getName().equalsIgnoreCase("META-INF/MANIFEST.MF")) {
                    // Resources do not participate in JVM type resolution. Account for
                    // expansion with a fixed buffer rather than retaining their bytes.
                    byte[] buffer=new byte[65536];long size=0;int read;
                    while((read=jar.read(buffer))!=-1){size+=read;expandedBytes+=read;if(size>100_000_000||expandedBytes>1_000_000_000L)throw new FrontendInputException("frontend.jar-size","JAR expansion exceeds the bounded class-view limit");}
                    continue;
                }
                byte[] content = jar.readNBytes(100_000_001);
                if (content.length > 100_000_000)
                    throw new FrontendInputException("frontend.jar-size", "A JAR entry exceeds the bounded class-view limit");
                expandedBytes += content.length;
                if (expandedBytes > 1_000_000_000L || physical.size() >= 1_000_000)
                    throw new FrontendInputException("frontend.jar-size", "JAR expansion exceeds the bounded class-view limit");
                if (entry.getName().equalsIgnoreCase("META-INF/MANIFEST.MF")) {
                    if (manifest != null) continue;
                    manifest = new Manifest(new ByteArrayInputStream(content));
                } else {
                    physical.add(new PhysicalJarEntry(entry.getName(), content));
                }
            }
        }
        String manifestClasspath = manifest == null ? null
                : manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
        boolean multiRelease = manifest != null && Boolean.parseBoolean(
                manifest.getMainAttributes().getValue("Multi-Release"));
        var selected = new TreeMap<String, SelectedJarEntry>();
        for (var entry : physical) {
            String name = entry.name();
            int version = 0;
            if (name.startsWith("META-INF/versions/")) {
                if (!multiRelease) continue;
                String remainder = name.substring("META-INF/versions/".length());
                int separator = remainder.indexOf('/');
                if (separator < 1) continue;
                try {
                    version = Integer.parseInt(remainder.substring(0, separator));
                } catch (NumberFormatException ignored) {
                    continue;
                }
                name = remainder.substring(separator + 1);
                if (version < 9 || version > targetRelease || name.startsWith("META-INF/")) continue;
            }
            if (!name.endsWith(".class")) continue;
            var current = selected.get(name);
            if (current == null || version > current.version())
                selected.put(name, new SelectedJarEntry(version, entry.bytes()));
        }
        var classEntries = new LinkedHashMap<String, byte[]>(Math.max(16, selected.size() * 2));
        for (var entry : selected.entrySet()) {
            classEntries.put(entry.getKey(), entry.getValue().bytes());
        }
        return CachedArchive.fromClassEntries(classEntries, Optional.ofNullable(manifestClasspath));
    }

    private static javassist.ClassPath mapClassPath(Map<String, byte[]> pool) {
        return new javassist.ClassPath() {
            @Override
            public InputStream openClassfile(String classname) {
                byte[] raw = pool.get(classname);
                return raw == null ? null : new ByteArrayInputStream(raw);
            }

            @Override
            public java.net.URL find(String classname) {
                if (!pool.containsKey(classname)) return null;
                try {
                    return new java.net.URL(null, "file:/CachedArchive/" + classname.replace('.', '/') + ".class",
                            new java.net.URLStreamHandler() {
                                @Override
                                protected java.net.URLConnection openConnection(java.net.URL url) {
                                    return new java.net.URLConnection(url) {
                                        @Override public void connect() {}
                                        @Override public int getContentLength() {
                                            byte[] raw = pool.get(classname);
                                            return raw == null ? -1 : raw.length;
                                        }
                                        @Override public InputStream getInputStream() throws IOException {
                                            byte[] raw = pool.get(classname);
                                            if (raw == null) throw new FileNotFoundException(classname);
                                            return new ByteArrayInputStream(raw);
                                        }
                                    };
                                }
                            });
                } catch (java.net.MalformedURLException impossible) {
                    return null;
                }
            }
        };
    }

    private static final class CachedArchive {
        private static final CachedArchive EMPTY = new CachedArchive(Map.of(), Map.of(), Optional.empty(), Optional.empty(), 64L);
        final Map<String, String> knownClasses;
        final Map<String, byte[]> classBytesByPoolName;
        final Optional<com.github.javaparser.utils.Pair<String, List<String>>> moduleToExportedPackages;
        final Optional<String> manifestClasspath;
        final javassist.ClassPath classPath;
        final long weightBytes;

        private CachedArchive(
                Map<String, String> knownClasses,
                Map<String, byte[]> classBytesByPoolName,
                Optional<com.github.javaparser.utils.Pair<String, List<String>>> moduleToExportedPackages,
                Optional<String> manifestClasspath,
                long weightBytes) {
            this.knownClasses = knownClasses;
            this.classBytesByPoolName = classBytesByPoolName;
            this.moduleToExportedPackages = moduleToExportedPackages;
            this.manifestClasspath = manifestClasspath;
            this.weightBytes = weightBytes;
            this.classPath = mapClassPath(classBytesByPoolName);
        }

        Optional<String> manifestClasspath() {
            return manifestClasspath;
        }

        static CachedArchive fromClassEntries(Map<String, byte[]> entries, Optional<String> manifestClasspath) {
            ARCHIVE_BUILDS.incrementAndGet();
            if (entries.isEmpty() && manifestClasspath.isEmpty()) {
                return EMPTY;
            }
            var known = new LinkedHashMap<String, String>(Math.max(16, entries.size() * 2));
            var byPool = new HashMap<String, byte[]>(Math.max(16, entries.size() * 2));
            long weight = 128L + manifestClasspath.map(s -> (long) s.length() * 2).orElse(0L);
            for (var entry : entries.entrySet()) {
                String entryName = entry.getKey();
                if (!entryName.endsWith(".class")) continue;
                String stripped = entryName.substring(0, entryName.length() - ".class".length());
                String poolName = stripped.replace('/', '.');
                String className = poolName.replace('$', '.');
                known.put(className, className.equals(poolName) ? className : poolName);
                byte[] raw = entry.getValue();
                byPool.put(poolName, raw);
                weight += raw.length + (long) poolName.length() * 4 + 96L;
            }
            var immutableKnown = Collections.unmodifiableMap(known);
            var immutablePool = Collections.unmodifiableMap(byPool);
            Optional<com.github.javaparser.utils.Pair<String, List<String>>> modInfo = Optional.empty();
            if (immutablePool.containsKey("module-info")) {
                var initPool = new javassist.ClassPool(false);
                initPool.appendClassPath(mapClassPath(immutablePool));
                try {
                    modInfo = com.github.javaparser.symbolsolver.utils.JavassistModuleHelper
                            .getModuleWithExportedPackages(initPool.get("module-info"));
                } catch (javassist.NotFoundException impossible) {
                    throw new IllegalStateException("Unable to read module-info from class pool", impossible);
                }
            }
            return new CachedArchive(immutableKnown, immutablePool, modInfo, manifestClasspath, weight);
        }
    }

    private static CachedGroupIndex buildOrGetGroupIndex(
            int release, List<ContentDigest> digests, List<ArchiveTypeSolver> solvers) {
        var key = new GroupIndexKey(release, List.copyOf(digests));
        var cached = GROUP_INDEX_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        int expectedClasses = 16;
        for (var s : solvers) {
            expectedClasses += s.getKnownClasses().size();
        }
        var index = new HashMap<String, Integer>(Math.max(16, expectedClasses * 2));
        var dups = new HashSet<String>();
        long weight = 128L;
        for (int i = 0; i < solvers.size(); i++) {
            for (String cls : solvers.get(i).getKnownClasses()) {
                if (index.putIfAbsent(cls, i) != null) {
                    if (dups.add(cls)) weight += (long) cls.length() * 2 + 48L;
                } else {
                    weight += (long) cls.length() * 2 + 64L;
                }
            }
        }
        var built = new CachedGroupIndex(
                Collections.unmodifiableMap(index),
                Collections.unmodifiableSet(dups),
                weight);
        GROUP_INDEX_CACHE.put(key, built);
        return built;
    }

    private static final class StrongTypeSolverCache
            implements com.github.javaparser.resolution.cache.Cache<
                    String,
                    com.github.javaparser.resolution.model.SymbolReference<
                            com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration>> {
        private final Map<
                        String,
                        Optional<
                                com.github.javaparser.resolution.model.SymbolReference<
                                        com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration>>>
                map = new HashMap<>(4096);

        @Override
        public void put(
                String key,
                com.github.javaparser.resolution.model.SymbolReference<
                                com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration>
                        value) {
            map.put(key, Optional.of(value));
        }

        @Override
        public Optional<
                        com.github.javaparser.resolution.model.SymbolReference<
                                com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration>>
                get(String key) {
            var found = map.get(key);
            return found != null ? found : Optional.empty();
        }

        @Override
        public void remove(String key) {
            map.remove(key);
        }

        @Override
        public void removeAll() {
            map.clear();
        }

        @Override
        public boolean contains(String key) {
            return map.containsKey(key);
        }

        @Override
        public long size() {
            return map.size();
        }

        @Override
        public boolean isEmpty() {
            return map.isEmpty();
        }

        @Override
        public com.github.javaparser.resolution.cache.CacheStats stats() {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RuntimePlatformTypeSolver implements com.github.javaparser.resolution.TypeSolver {
        private static final ClassLoader PLATFORM_LOADER = ClassLoader.getPlatformClassLoader();
        private static final Set<String> JRT_PACKAGES = loadJrtPackages();
        private static final java.util.concurrent.ConcurrentHashMap<String, Optional<Class<?>>> CLASS_CACHE =
                new java.util.concurrent.ConcurrentHashMap<>(4096);
        private final Map<
                        Class<?>,
                        com.github.javaparser.resolution.model.SymbolReference<
                                com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration>>
                declarations = new IdentityHashMap<>(512);
        private com.github.javaparser.resolution.TypeSolver parent;

        private static Set<String> loadJrtPackages() {
            try {
                FileSystem jrt = FileSystems.getFileSystem(java.net.URI.create("jrt:/"));
                var pkgs = new HashSet<String>(1024);
                try (var stream = Files.list(jrt.getPath("/packages"))) {
                    stream.forEach(p -> {
                        Path fn = p.getFileName();
                        if (fn != null) pkgs.add(fn.toString());
                    });
                }
                return pkgs.isEmpty() ? null : Collections.unmodifiableSet(pkgs);
            } catch (Exception unavailable) {
                return null;
            }
        }

        private static boolean hasJrtPackagePrefix(String name) {
            if (JRT_PACKAGES == null) return true;
            int dot = name.lastIndexOf('.');
            while (dot > 0) {
                if (JRT_PACKAGES.contains(name.substring(0, dot))) return true;
                dot = name.lastIndexOf('.', dot - 1);
            }
            return false;
        }

        private static Optional<Class<?>> loadPlatformClass(String name) {
            String candidate = name;
            while (true) {
                try {
                    return Optional.of(PLATFORM_LOADER.loadClass(candidate));
                } catch (NoClassDefFoundError unavailable) {
                    return Optional.empty();
                } catch (ClassNotFoundException missing) {
                    int dot = candidate.lastIndexOf('.');
                    if (dot == -1) return Optional.empty();
                    candidate = candidate.substring(0, dot) + '$' + candidate.substring(dot + 1);
                }
            }
        }

        @Override
        public com.github.javaparser.resolution.TypeSolver getParent() {
            return parent;
        }

        @Override
        public void setParent(com.github.javaparser.resolution.TypeSolver parent) {
            Objects.requireNonNull(parent);
            if (this.parent != null) throw new IllegalStateException("This TypeSolver already has a parent.");
            if (parent == this) throw new IllegalStateException("The parent of this TypeSolver cannot be itself.");
            this.parent = parent;
        }

        @Override
        public com.github.javaparser.resolution.model.SymbolReference<
                        com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration>
                tryToSolveType(String name) {
            if (!hasJrtPackagePrefix(name)) {
                return com.github.javaparser.resolution.model.SymbolReference.unsolved();
            }
            Optional<Class<?>> found = CLASS_CACHE.computeIfAbsent(name, RuntimePlatformTypeSolver::loadPlatformClass);
            if (found.isEmpty()) {
                return com.github.javaparser.resolution.model.SymbolReference.unsolved();
            }
            Class<?> clazz = found.get();
            var existing = declarations.get(clazz);
            if (existing != null) return existing;
            var created = com.github.javaparser.resolution.model.SymbolReference.solved(
                    com.github.javaparser.symbolsolver.reflectionmodel.ReflectionFactory.typeDeclarationFor(
                            clazz, getRoot()));
            declarations.put(clazz, created);
            return created;
        }

        @Override
        public com.github.javaparser.resolution.model.SymbolReference<
                        com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration>
                tryToSolveTypeInModule(String qualifiedModuleName, String simpleTypeName) {
            var modOpt = ModuleLayer.boot().findModule(qualifiedModuleName);
            if (modOpt.isPresent()) {
                for (var export : modOpt.get().getDescriptor().exports()) {
                    if (!export.isQualified()) {
                        var candidate = tryToSolveType(export.source() + "." + simpleTypeName);
                        if (candidate.isSolved()) return candidate;
                    }
                }
            }
            return com.github.javaparser.resolution.model.SymbolReference.unsolved();
        }
    }

    private static final class IndexedArchiveGroupSolver implements com.github.javaparser.resolution.TypeSolver {
        private final List<ArchiveTypeSolver> delegates;
        private final Map<String, Integer> firstSlotByClass;
        private com.github.javaparser.resolution.TypeSolver parent;

        IndexedArchiveGroupSolver(List<ArchiveTypeSolver> solvers, CachedGroupIndex groupIndex) {
            this.delegates = List.copyOf(solvers);
            for (var s : this.delegates) {
                s.setParent(this);
            }
            this.firstSlotByClass = groupIndex.firstSlotByClass();
        }

        @Override
        public com.github.javaparser.resolution.TypeSolver getParent() {
            return parent;
        }

        @Override
        public void setParent(com.github.javaparser.resolution.TypeSolver parent) {
            Objects.requireNonNull(parent);
            if (this.parent != null) throw new IllegalStateException("This TypeSolver already has a parent.");
            if (parent == this) throw new IllegalStateException("The parent of this TypeSolver cannot be itself.");
            this.parent = parent;
        }

        @Override
        public com.github.javaparser.resolution.model.SymbolReference<com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration> tryToSolveType(String name) {
            Integer slot = firstSlotByClass.get(name);
            return slot == null
                    ? com.github.javaparser.resolution.model.SymbolReference.unsolved()
                    : delegates.get(slot).tryToSolveType(name);
        }

        @Override
        public com.github.javaparser.resolution.model.SymbolReference<com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration> tryToSolveTypeInModule(String qualifiedModuleName, String simpleTypeName) {
            for (var s : delegates) {
                var candidate = s.tryToSolveTypeInModule(qualifiedModuleName, simpleTypeName);
                if (candidate.isSolved()) return candidate;
            }
            return com.github.javaparser.resolution.model.SymbolReference.unsolved();
        }
    }

    private static final class ArchiveTypeSolver implements com.github.javaparser.resolution.TypeSolver {
        private final CachedArchive archive;
        private javassist.ClassPool classPool;
        private final Map<String, com.github.javaparser.resolution.model.SymbolReference<com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration>> solvedCache = new HashMap<>();
        private com.github.javaparser.resolution.TypeSolver parent;

        ArchiveTypeSolver(CachedArchive archive) {
            this.archive = Objects.requireNonNull(archive);
        }

        private javassist.ClassPool pool() {
            if (classPool == null) {
                var p = new javassist.ClassPool(false);
                p.appendClassPath(archive.classPath);
                classPool = p;
            }
            return classPool;
        }

        Set<String> getKnownClasses() {
            return archive.knownClasses.keySet();
        }

        @Override
        public com.github.javaparser.resolution.TypeSolver getParent() {
            return parent;
        }

        @Override
        public void setParent(com.github.javaparser.resolution.TypeSolver parent) {
            Objects.requireNonNull(parent);
            if (this.parent != null) throw new IllegalStateException("This TypeSolver already has a parent.");
            if (parent == this) throw new IllegalStateException("The parent of this TypeSolver cannot be itself.");
            this.parent = parent;
        }

        @Override
        public com.github.javaparser.resolution.model.SymbolReference<com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration> tryToSolveType(String name) {
            String poolName = archive.knownClasses.get(name);
            if (poolName == null) {
                return com.github.javaparser.resolution.model.SymbolReference.unsolved();
            }
            var cached = solvedCache.get(name);
            if (cached != null) {
                return cached;
            }
            try {
                var solved = com.github.javaparser.resolution.model.SymbolReference.solved(
                        com.github.javaparser.symbolsolver.javassistmodel.JavassistFactory.toTypeDeclaration(
                                pool().get(poolName), getRoot()));
                solvedCache.put(name, solved);
                return solved;
            } catch (javassist.NotFoundException e) {
                throw new IllegalStateException("Unable to get class with name " + poolName + " from class pool.", e);
            }
        }

        @Override
        public com.github.javaparser.resolution.model.SymbolReference<com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration> tryToSolveTypeInModule(String qualifiedModuleName, String simpleTypeName) {
            if (archive.moduleToExportedPackages.isPresent()) {
                var pair = archive.moduleToExportedPackages.get();
                if (pair.a.equals(qualifiedModuleName)) {
                    for (String pkg : pair.b) {
                        var candidate = tryToSolveType(pkg + "." + simpleTypeName);
                        if (candidate.isSolved()) return candidate;
                    }
                }
            }
            return com.github.javaparser.resolution.model.SymbolReference.unsolved();
        }
    }

    private static final class GroupIndexLruCache {
        private final long maxBytes;
        private final LinkedHashMap<GroupIndexKey, CachedGroupIndex> map = new LinkedHashMap<>(32, 0.75f, true);
        private long usedBytes;

        GroupIndexLruCache(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        synchronized CachedGroupIndex get(GroupIndexKey key) {
            return map.get(key);
        }

        synchronized void put(GroupIndexKey key, CachedGroupIndex value) {
            if (value.weightBytes() > maxBytes) return;
            var previous = map.put(key, value);
            if (previous != null) usedBytes -= previous.weightBytes();
            usedBytes += value.weightBytes();
            var it = map.entrySet().iterator();
            while (usedBytes > maxBytes && it.hasNext()) {
                var evicted = it.next().getValue();
                usedBytes -= evicted.weightBytes();
                it.remove();
            }
        }
    }

    private static final class WeightedLruCache<K, V extends CachedArchive> {
        private final long maxBytes;
        private final LinkedHashMap<K, V> map = new LinkedHashMap<>(64, 0.75f, true);
        private long usedBytes;

        WeightedLruCache(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        synchronized V get(K key) {
            return map.get(key);
        }

        synchronized void put(K key, V value) {
            if (value.weightBytes > maxBytes) return;
            V previous = map.put(key, value);
            if (previous != null) usedBytes -= previous.weightBytes;
            usedBytes += value.weightBytes;
            var it = map.entrySet().iterator();
            while (usedBytes > maxBytes && it.hasNext()) {
                var evicted = it.next().getValue();
                usedBytes -= evicted.weightBytes;
                it.remove();
            }
        }
    }

    private Artifact firstArtifact(String qualifiedName) {
        for (var bg : binaryGroups) {
            Integer slot = bg.index().firstSlotByClass().get(qualifiedName);
            if (slot != null) return artifacts.get(bg.baseArtifactIndex() + slot);
        }
        return null;
    }

    private boolean isKnownInPlatform(String qualifiedName) {
        for (var group : platformGroups) {
            if (group.firstSlotByClass().containsKey(qualifiedName)) return true;
        }
        return hasRuntimeModulesPlatform && platform.tryToSolveType(qualifiedName).isSolved();
    }

    boolean isKnownInPrimarySolvers(String qualifiedName) {
        if (isKnownInPlatform(qualifiedName)
                || firstArtifact(qualifiedName) != null
                || project.tryToSolveType(qualifiedName).isSolved()) {
            return true;
        }
        for (var source : reactorSources.values()) {
            if (source.tryToSolveType(qualifiedName).isSolved()) {
                return true;
            }
        }
        return false;
    }

    boolean isRecoveryScaffold(com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration type) {
        if (type instanceof ExplicitImportTypeSolver.ExplicitImportTypeDeclaration) return true;
        // Match the actual detached Android AST, not its name: independently
        // captured native member evidence may legitimately have the same name.
        for (var memory : List.of(androidProject,androidPlatform)) {
            var candidate = memory.tryToSolveType(type.getQualifiedName());
            if (!candidate.isSolved()) continue;
            var registered = candidate.getCorrespondingDeclaration();
            if (registered == type || type.toAst().filter(node -> registered.toAst().filter(other -> other == node).isPresent()).isPresent()) return true;
        }
        return false;
    }

    Origin origin(String qualifiedName) {
        if (isKnownInPlatform(qualifiedName)) return origin(EntityOrigin.JDK, platformEntry);
        Artifact selectedArtifact = firstArtifact(qualifiedName);
        if (selectedArtifact != null) {
            if (selectedArtifact.input().entry().kind() == com.evolution.analysis.contract.analysis.ClasspathEntryKind.MODULE_OUTPUT) {
                return new Origin(EntityOrigin.PROJECT,
                        EntityScope.project(selectedArtifact.input().reactorModule().orElseThrow()));
            }
            return origin(EntityOrigin.DEPENDENCY, selectedArtifact.input().originEntry());
        }
        // Recovery solvers are parser scaffolding, not declaration evidence.
        // Imports/Android names cannot establish an artifact, generated output,
        // member signature or origin. The caller retains an unresolved record.
        if (androidProject.tryToSolveType(qualifiedName).isSolved()
                || androidPlatform.tryToSolveType(qualifiedName).isSolved()
                || explicitImports.isRecoveredType(qualifiedName))
            throw new com.github.javaparser.resolution.UnsolvedSymbolException(qualifiedName);
        throw new IllegalArgumentException("selected declaration has no verified origin");
    }
    com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration nativeType(ContentDigest slot,String qualifiedName){
        var witness=memberEvidence.get(slot);if(witness!=null){var selected=witness.tryToSolveType(qualifiedName);if(selected.isSolved())return selected.getCorrespondingDeclaration();}
        for(var artifact:artifacts)if(artifact.input().nativeOrigin().filter(o->o.slot().equals(slot)).isPresent()){
            var selected=artifact.solver().tryToSolveType(qualifiedName);if(selected.isSolved())return selected.getCorrespondingDeclaration();
        }
        throw new FrontendInputException("frontend.native-member-owner","Correspondence owner is absent from the selected solver view");
    }
    Set<String> nativeClasses(ContentDigest slot){if(memberEvidence.containsKey(slot))return Set.copyOf(memberEvidence.get(slot).getKnownClasses());for(var artifact:artifacts)if(artifact.input().nativeOrigin().filter(o->o.slot().equals(slot)).isPresent())return Set.copyOf(artifact.solver().getKnownClasses());throw new FrontendInputException("frontend.native-member-slot","Correspondence slot is absent");}
    boolean privateMemberSlot(ContentDigest slot,String qualifiedName){return memberEvidence.containsKey(slot)&&memberEvidence.get(slot).getKnownClasses().contains(qualifiedName);}
    Optional<ContentDigest> selectedNativeSlot(String qualifiedName){
        if(isKnownInPlatform(qualifiedName))return Optional.empty();
        Artifact art = firstArtifact(qualifiedName);
        return art == null ? Optional.empty() : art.input().nativeOrigin().map(BinaryInput.NativeOrigin::slot);
    }
    com.evolution.analysis.contract.semantic.Derivation nativeDerivation(String qualifiedName){
        if(!isKnownInPlatform(qualifiedName)){
            Artifact art = firstArtifact(qualifiedName);
            if(art != null){
                var input=art.input();var origin=input.nativeOrigin();
                if(input.reactorModule().isPresent()&&origin.flatMap(BinaryInput.NativeOrigin::proof).isPresent())return new com.evolution.analysis.contract.semantic.Derivation(
                        com.evolution.analysis.contract.semantic.DerivationKind.DERIVED,new com.evolution.analysis.contract.common.VersionedIdentifier("java.native-binary-import","m8.4-v1"),
                        List.of(origin.orElseThrow().slot().value(),com.evolution.analysis.contract.serialization.CanonicalJson.sha256(origin.orElseThrow().canonicalForm()).value(),input.originEntry().contentDigest().value()));
            }
        }
        if (androidProject.tryToSolveType(qualifiedName).isSolved()) {
            return new com.evolution.analysis.contract.semantic.Derivation(
                    com.evolution.analysis.contract.semantic.DerivationKind.DERIVED,
                    new com.evolution.analysis.contract.common.VersionedIdentifier("java.android-synthetic-recovery", "m8.4-v1"),
                    List.of(request.manifest().snapshot().identity().value()));
        }
        if (androidPlatform.tryToSolveType(qualifiedName).isSolved() || explicitImports.isRecoveredType(qualifiedName)) {
            return new com.evolution.analysis.contract.semantic.Derivation(
                    com.evolution.analysis.contract.semantic.DerivationKind.DERIVED,
                    new com.evolution.analysis.contract.common.VersionedIdentifier("java.explicit-import-recovery", "m8.4-v1"),
                    List.of(request.manifest().snapshot().identity().value()));
        }
        return new com.evolution.analysis.contract.semantic.Derivation(com.evolution.analysis.contract.semantic.DerivationKind.DIRECT,new com.evolution.analysis.contract.common.VersionedIdentifier("java.source","1"),List.of());
    }
    boolean duplicateExternal(String name) {
        int count = isKnownInPlatform(name) ? 1 : 0;
        for (var bg : binaryGroups) {
            if (bg.index().duplicateClasses().contains(name)) return true;
            if (bg.index().firstSlotByClass().containsKey(name) && ++count > 1) return true;
        }
        for (var source : reactorSources.values()) {
            if (source.tryToSolveType(name).isSolved() && ++count > 1) return true;
        }
        return count > 1;
    }
    private static Origin origin(EntityOrigin kind, ClasspathEntry entry) {
        return new Origin(kind, EntityScope.external(kind, entry.logicalName(), entry.contentDigest()));
    }
}
