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
import java.util.jar.Attributes;
import java.util.jar.JarInputStream;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipFile;

/** Sources, platform loader and verified JAR snapshots only; no application classloader or directory scanning. */
final class ResolutionEnvironment {
    final MemoryTypeSolver project = new MemoryTypeSolver();
    final CombinedTypeSolver platform = new CombinedTypeSolver();
    final MemoryTypeSolver androidProject = new MemoryTypeSolver();
    final MemoryTypeSolver androidPlatform = new MemoryTypeSolver();
    final ExplicitImportTypeSolver explicitImports = new ExplicitImportTypeSolver();
    final CombinedTypeSolver solver = new CombinedTypeSolver(project, platform);
    final Set<Diagnostic> diagnostics = new TreeSet<>();
    private final FrontendRequest request;
    private final ClasspathEntry platformEntry;
    private final List<Artifact> artifacts = new ArrayList<>();
    private final Map<ContentDigest,JarTypeSolver> memberEvidence=new TreeMap<>();
    private final Map<Integer, MemoryTypeSolver> reactorSources = new TreeMap<>();
    private record Artifact(BinaryInput input, JarTypeSolver solver) {}
    record Origin(EntityOrigin kind, EntityScope scope) {}

    ResolutionEnvironment(FrontendRequest request) {
        this.request = Objects.requireNonNull(request);
        request.originClasspath();
        platformEntry = request.platform().entry();
        // An incomplete build closure does not invalidate the independently verified inputs.
        // Missing targets remain unresolved at the individual observation, never filled by host lookup.
        try {
            var platformPaths = new HashSet<Path>();
            for (var artifact : request.platform().artifacts()) {
                if(request.sourceRecovery()&&"false".equals(request.manifest().configuration().values().get(FrontendRequest.RECOVERY_PLATFORM_COMPATIBLE)))continue;
                Path real = artifact.path().toRealPath();
                if (!platformPaths.add(real)) {
                    throw new FrontendInputException("frontend.duplicate-platform-artifact", "Aliased platform artifacts are not allowed");
                }
                verify(artifact.contentDigest(), real);
                switch (artifact.format()) {
                    case RUNTIME_MODULES -> {
                        Path runtimeModules = Path.of(System.getProperty("java.home")).resolve("lib/modules");
                        if (request.platform().release() != Runtime.version().feature()
                                || !Files.isSameFile(real, runtimeModules)) {
                            throw new FrontendInputException("frontend.platform-view", "Running-module symbols must identify the running JDK exactly");
                        }
                        platform.add(new ClassLoaderTypeSolver(ClassLoader.getPlatformClassLoader()));
                    }
                    case JAR -> {
                        byte[] bytes=Files.readAllBytes(real);verify(artifact.contentDigest(),bytes);
                        platform.add(jarSolver(bytes, request.platform().release(), artifact.logicalName()));
                    }
                    case JMOD -> platform.add(jmodSolver(real));
                    case CT_SYM -> platform.add(ctSymSolver(real, request.platform().release()));
                }
            }
            var paths = new HashSet<Path>();
            for (var input : request.dependencies()) {
                if (!paths.add(input.path().toRealPath())) throw new FrontendInputException("frontend.duplicate-binary", "Aliased binary inputs are not allowed");
                byte[] bytes = Files.readAllBytes(input.path());
                verify(input.entry(), bytes);
                var jar = jarSolver(bytes, request.platform().release(), input.entry().logicalName());
                artifacts.add(new Artifact(input, jar));
            }
            if(request.correspondence().isPresent()){
                var binding=request.correspondence().orElseThrow();
                for(var view:binding.binaryViews().entrySet()){
                    if(view.getValue().size()>32_000_000)throw new FrontendInputException("frontend.native-member-worker","A larger member-evidence worker is required");
                    byte[] bytes;try(var in=binding.openBinaryView(view.getKey())){bytes=in.readNBytes(Math.toIntExact(view.getValue().size()+1));}
                    verify(view.getValue().digest(),bytes);var memberSolver=jarSolver(bytes,request.platform().release(),"native-member:"+view.getKey().value());memberSolver.setParent(solver);memberEvidence.put(view.getKey(),memberSolver);
                }
            }
            Map<Integer, ReactorSourceInput> sourcePositions = new TreeMap<>();
            request.reactorSources().forEach(input -> sourcePositions.put(input.order(), input));
            int binary = 0;
            int size = request.dependencies().size() + request.reactorSources().size();
            for (int order = 0; order < size; order++) {
                if (sourcePositions.containsKey(order)) {
                    var sourceSolver = new MemoryTypeSolver();
                    reactorSources.put(order, sourceSolver);
                    solver.add(sourceSolver);
                } else {
                    solver.add(artifacts.get(binary++).solver());
                }
            }
            solver.add(androidProject);
            solver.add(androidPlatform);
            if (request.sourceRecovery()) {
                solver.add(explicitImports);
            }
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
        for (Artifact artifact : artifacts) {
            if (artifact.solver().tryToSolveType("lombok.RequiredArgsConstructor").isSolved()
                    || artifact.solver().tryToSolveType("lombok.Data").isSolved()
                    || artifact.solver().tryToSolveType("lombok.Getter").isSolved()) {
                return true;
            }
        }
        return false;
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
    private static void verify(ContentDigest expected,Path path)throws IOException {
        java.security.MessageDigest digest;
        try{digest=java.security.MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        try(var input=new java.security.DigestInputStream(Files.newInputStream(path),digest)){input.transferTo(OutputStream.nullOutputStream());}
        if(!expected.value().equals("sha256:"+HexFormat.of().formatHex(digest.digest())))throw new FrontendInputException("frontend.artifact-digest","Artifact bytes differ from the manifest");
    }
    private static void verify(ContentDigest expected, byte[] bytes) {
        if (!ContentDigest.sha256(bytes).equals(expected)) throw new FrontendInputException("frontend.artifact-digest", "Artifact bytes differ from the manifest");
    }
    private static com.github.javaparser.resolution.TypeSolver jmodSolver(Path path) throws IOException {
        ByteArrayOutputStream normalized = new ByteArrayOutputStream();
        Set<String> seen = new HashSet<>();
        try (ZipFile jmod = new ZipFile(path.toFile());
                JarOutputStream jar = new JarOutputStream(normalized)) {
            // Temporary solver archive: avoid recompression CPU while retaining exact
            // class bytes and ZIP CRCs. The provider may materialize it as a temp file.
            jar.setLevel(java.util.zip.Deflater.NO_COMPRESSION);
            List<? extends java.util.zip.ZipEntry> entries = jmod.stream()
                    .filter(entry -> !entry.isDirectory()
                            && entry.getName().startsWith("classes/")
                            && entry.getName().endsWith(".class")
                            && !entry.getName().equals("classes/module-info.class"))
                    .sorted(Comparator.comparing(java.util.zip.ZipEntry::getName)).toList();
            for (var source : entries) {
                String name = source.getName().substring("classes/".length());
                if (!seen.add(name)) throw new FrontendInputException("frontend.jmod-duplicate", "JMOD contains duplicate platform classes");
                JarEntry target = new JarEntry(name);
                target.setTime(0);
                jar.putNextEntry(target);
                try (InputStream input = jmod.getInputStream(source)) { input.transferTo(jar); }
                jar.closeEntry();
            }
            if (seen.isEmpty()) {
                // Aggregator/native-only JDK modules legitimately declare no Java types.
                // Retain the original artifact/hash in the platform manifest; validate its descriptor.
                var descriptor = jmod.getEntry("classes/module-info.class");
                if (descriptor == null) throw new FrontendInputException("frontend.jmod-empty", "JMOD has no classes or module descriptor");
                try (var input = jmod.getInputStream(descriptor)) {
                    java.lang.module.ModuleDescriptor.read(input);
                } catch (java.lang.module.InvalidModuleDescriptorException invalid) {
                    throw new FrontendInputException("frontend.jmod-descriptor", "JMOD module descriptor is invalid");
                }
                return new MemoryTypeSolver();
            }
        }
        return new JarTypeSolver(new ByteArrayInputStream(normalized.toByteArray()));
    }
    private static JarTypeSolver ctSymSolver(Path path, int release) throws IOException {
        String code = releaseCode(release);
        if (code == null) throw new FrontendInputException("frontend.ct-sym-release", "ct.sym release is unsupported");
        ByteArrayOutputStream normalized = new ByteArrayOutputStream();
        Set<String> seen = new HashSet<>();
        try (ZipFile ctSym = new ZipFile(path.toFile());
                JarOutputStream jar = new JarOutputStream(normalized)) {
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
                if (!seen.add(name)) throw new FrontendInputException(
                        "frontend.ct-sym-duplicate", "ct.sym contains duplicate platform classes for the requested release");
                JarEntry target = new JarEntry(name);
                target.setTime(0);
                jar.putNextEntry(target);
                try (InputStream input = ctSym.getInputStream(source)) { input.transferTo(jar); }
                jar.closeEntry();
            }
        }
        if (seen.isEmpty()) throw new FrontendInputException(
                "frontend.ct-sym-empty", "ct.sym contains no classes for the requested release");
        return new JarTypeSolver(new ByteArrayInputStream(normalized.toByteArray()));
    }
    private static String releaseCode(int release) {
        if (release >= 0 && release <= 9) return Integer.toString(release);
        if (release >= 10 && release <= 35) return Character.toString((char) ('A' + release - 10));
        return null;
    }
    private record PhysicalJarEntry(String name, byte[] bytes) {}
    private record SelectedJarEntry(int version, byte[] bytes) {}
    private record NormalizedJar(byte[] bytes, Optional<String> manifestClasspath) {}

    private JarTypeSolver jarSolver(byte[] bytes, int targetRelease, String logicalName) throws IOException {
        var normalized = normalizeJar(bytes, targetRelease);
        normalized.manifestClasspath().ifPresent(classpath -> diagnostics.add(new Diagnostic(
                DiagnosticSeverity.WARNING, "frontend.jar-classpath-unmodeled",
                "JAR manifest Class-Path is recorded but not expanded; the exact supplied classpath remains authoritative",
                Optional.empty(), Map.of("artifact", logicalName, "classPathSha256",
                        ContentDigest.sha256Utf8(classpath).value()))));
        return new JarTypeSolver(new ByteArrayInputStream(normalized.bytes()));
    }

    /** Materializes the exact target-release class view so SymbolSolver never guesses MR-JAR selection. */
    private static NormalizedJar normalizeJar(byte[] bytes, int targetRelease) throws IOException {
        if(bytes.length>=22&&bytes[0]=='P'&&bytes[1]=='K'&&bytes[2]==5&&bytes[3]==6){
            var end=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            if(end.getShort(4)==0&&end.getShort(6)==0&&end.getShort(8)==0&&end.getShort(10)==0&&end.getInt(12)==0&&end.getInt(16)==0&&Short.toUnsignedInt(end.getShort(20))==bytes.length-22)
                return new NormalizedJar(bytes,Optional.empty());
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
        var normalized = new ByteArrayOutputStream();
        try (var output = new JarOutputStream(normalized)) {
            for (var entry : selected.entrySet()) {
                var target = new JarEntry(entry.getKey()); target.setTime(0);
                output.putNextEntry(target); output.write(entry.getValue().bytes()); output.closeEntry();
            }
        }
        return new NormalizedJar(normalized.toByteArray(), Optional.ofNullable(manifestClasspath));
    }
    boolean isKnownInPrimarySolvers(String qualifiedName) {
        if (project.tryToSolveType(qualifiedName).isSolved() || platform.tryToSolveType(qualifiedName).isSolved()) {
            return true;
        }
        for (var artifact : artifacts) {
            if (artifact.solver().getKnownClasses().contains(qualifiedName)
                    || artifact.solver().tryToSolveType(qualifiedName).isSolved()) {
                return true;
            }
        }
        for (var source : reactorSources.values()) {
            if (source.tryToSolveType(qualifiedName).isSolved()) {
                return true;
            }
        }
        return false;
    }

    Origin origin(String qualifiedName) {
        if (platform.tryToSolveType(qualifiedName).isSolved()) return origin(EntityOrigin.JDK, platformEntry);
        for (var artifact : artifacts) {
            if (artifact.solver().getKnownClasses().contains(qualifiedName)) {
                if (artifact.input().entry().kind() == com.evolution.analysis.contract.analysis.ClasspathEntryKind.MODULE_OUTPUT) {
                    return new Origin(EntityOrigin.PROJECT,
                            EntityScope.project(artifact.input().reactorModule().orElseThrow()));
                }
                return origin(EntityOrigin.DEPENDENCY, artifact.input().originEntry());
            }
        }
        if (androidProject.tryToSolveType(qualifiedName).isSolved()) {
            return new Origin(EntityOrigin.PROJECT, EntityScope.project(request.module()));
        }
        if (androidPlatform.tryToSolveType(qualifiedName).isSolved() || explicitImports.isRecoveredType(qualifiedName)) {
            return new Origin(
                    EntityOrigin.DEPENDENCY,
                    EntityScope.external(
                            EntityOrigin.DEPENDENCY,
                            "source-recovery:explicit-import",
                            ContentDigest.sha256Utf8(request.manifest().snapshot().identity().value())));
        }
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
    Optional<ContentDigest> selectedNativeSlot(String qualifiedName){if(platform.tryToSolveType(qualifiedName).isSolved())return Optional.empty();for(var artifact:artifacts)if(artifact.solver().getKnownClasses().contains(qualifiedName))return artifact.input().nativeOrigin().map(BinaryInput.NativeOrigin::slot);return Optional.empty();}
    com.evolution.analysis.contract.semantic.Derivation nativeDerivation(String qualifiedName){
        if(!platform.tryToSolveType(qualifiedName).isSolved())for(var artifact:artifacts)if(artifact.solver().getKnownClasses().contains(qualifiedName)){
            var input=artifact.input();var origin=input.nativeOrigin();
            if(input.reactorModule().isPresent()&&origin.flatMap(BinaryInput.NativeOrigin::proof).isPresent())return new com.evolution.analysis.contract.semantic.Derivation(
                    com.evolution.analysis.contract.semantic.DerivationKind.DERIVED,new com.evolution.analysis.contract.common.VersionedIdentifier("java.native-binary-import","m8.4-v1"),
                    List.of(origin.orElseThrow().slot().value(),com.evolution.analysis.contract.serialization.CanonicalJson.sha256(origin.orElseThrow().canonicalForm()).value(),input.originEntry().contentDigest().value()));
            break;
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
        int count = platform.tryToSolveType(name).isSolved() ? 1 : 0;
        for (var artifact : artifacts) if (artifact.solver().getKnownClasses().contains(name)) count++;
        for (var source : reactorSources.values()) if (source.tryToSolveType(name).isSolved()) count++;
        return count > 1;
    }
    private static Origin origin(EntityOrigin kind, ClasspathEntry entry) {
        return new Origin(kind, EntityScope.external(kind, entry.logicalName(), entry.contentDigest()));
    }
}
