package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.semantic.SemanticStatus;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.javaparser.asm.AsmBytecodeIndex;
import com.evolution.analysis.javaparser.asm.AsmTypeSolver;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.resolution.MethodUsage;
import com.github.javaparser.resolution.declarations.ResolvedAnnotationDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.model.typesystem.ReferenceTypeImpl;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused regression tests for ASM bytecode signature resolution, declaration adapters,
 * synthetic parameter/method filtering, nested annotation defaults, and bytecode record patterns.
 */
class AsmSignatureResolverTest {

    @TempDir Path tempDir;

    private Path compileToJar(String jarName, Map<String, String> sources) throws Exception {
        Path srcRoot = tempDir.resolve(jarName + "-src");
        Path outDir = tempDir.resolve(jarName + "-classes");
        Files.createDirectories(srcRoot);
        Files.createDirectories(outDir);
        List<Path> files = new ArrayList<>();
        for (var e : sources.entrySet()) {
            Path p = srcRoot.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, e.getValue());
            files.add(p);
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        var diags = new DiagnosticCollector<JavaFileObject>();
        try (var fm = compiler.getStandardFileManager(diags, null, null)) {
            boolean ok = compiler.getTask(
                    null, fm, diags,
                    List.of("--release", "21", "-parameters", "-d", outDir.toString()),
                    null, fm.getJavaFileObjectsFromPaths(files)).call();
            assertTrue(ok, () -> "Compilation failed: " + diags.getDiagnostics());
        }
        Path jarPath = tempDir.resolve(jarName + ".jar");
        try (var jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            try (var stream = Files.walk(outDir)) {
                for (Path cls : stream.filter(Files::isRegularFile).sorted().toList()) {
                    String entryName = outDir.relativize(cls).toString().replace('\\', '/');
                    jos.putNextEntry(new JarEntry(entryName));
                    jos.write(Files.readAllBytes(cls));
                    jos.closeEntry();
                }
            }
        }
        return jarPath;
    }

    private CombinedTypeSolver buildSolver(Path jarPath) throws IOException {
        var bytesByEntry = new LinkedHashMap<String, byte[]>();
        try (var zf = new java.util.zip.ZipFile(jarPath.toFile())) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                var e = entries.nextElement();
                if (!e.isDirectory() && e.getName().endsWith(".class")) {
                    try (var in = zf.getInputStream(e)) {
                        bytesByEntry.put(e.getName(), in.readAllBytes());
                    }
                }
            }
        }
        var index = AsmBytecodeIndex.fromCompactArchiveEntries(jarPath, bytesByEntry, Map.of(), Optional.empty());
        var root = new CombinedTypeSolver();
        root.add(new AsmTypeSolver(index));
        root.add(new ReflectionTypeSolver(true));
        return root;
    }

    @Test
    void genericSupertypeSubstitutionSwappedParamsAndRepoHierarchyResolveAccurately() throws Exception {
        Path jar = compileToJar("generic-super", Map.of(
                "lib/StringMap.java", """
                        package lib;
                        import java.util.HashMap;
                        public class StringMap<V> extends HashMap<String, V> {}
                        """,
                "lib/ReversedMap.java", """
                        package lib;
                        import java.util.HashMap;
                        public class ReversedMap<V, K> extends HashMap<K, V> {}
                        """,
                "lib/CrudRepo.java", """
                        package lib;
                        public interface CrudRepo<T, ID> {
                            T findById(ID id);
                            T save(T entity);
                        }
                        """,
                "lib/User.java", """
                        package lib;
                        public class User { public String email() { return "a@b.c"; } }
                        """,
                "lib/UserRepo.java", """
                        package lib;
                        public interface UserRepo extends CrudRepo<User, Long> {}
                        """
        ));

        var solver = buildSolver(jar);
        ResolvedType stringType = new ReferenceTypeImpl(solver.solveType("java.lang.String"));
        ResolvedType intType = new ReferenceTypeImpl(solver.solveType("java.lang.Integer"));
        ResolvedType longType = new ReferenceTypeImpl(solver.solveType("java.lang.Long"));

        // 1. StringMap<Integer>.put(String, Integer) -> returns Integer (NOT String!)
        ResolvedReferenceTypeDeclaration stringMapDecl = solver.solveType("lib.StringMap");
        Optional<MethodUsage> putUsage = ((com.github.javaparser.symbolsolver.core.resolution.MethodUsageResolutionCapability) stringMapDecl)
                .solveMethodAsUsage("put", List.of(stringType, intType), null, List.of(intType));
        assertTrue(putUsage.isPresent(), "StringMap<Integer>.put(String, Integer) must solve");
        assertEquals("java.lang.Integer", putUsage.orElseThrow().returnType().describe());

        // 2. ReversedMap<Integer, String>.put(String, Integer) -> returns Integer (simultaneous swap!)
        ResolvedReferenceTypeDeclaration revMapDecl = solver.solveType("lib.ReversedMap");
        Optional<MethodUsage> revPut = ((com.github.javaparser.symbolsolver.core.resolution.MethodUsageResolutionCapability) revMapDecl)
                .solveMethodAsUsage("put", List.of(stringType, intType), null, List.of(intType, stringType));
        assertTrue(revPut.isPresent(), "ReversedMap<Integer, String>.put(String, Integer) must solve");
        assertEquals("java.lang.Integer", revPut.orElseThrow().returnType().describe());

        // 3. End-to-end call resolution through UserRepo extends CrudRepo<User, Long>
        var dep = new BinaryInput(
                new ClasspathEntry(ClasspathEntryKind.DEPENDENCY, "test:generic-super:1.0@jar", ContentDigest.sha256(Files.readAllBytes(jar))),
                jar);
        String client = """
                package app;
                import lib.*;
                class Use {
                    String go(UserRepo repo, StringMap<Integer> sm, ReversedMap<Integer, String> rm) {
                        Integer prev = sm.put("k", 1);
                        Integer revPrev = rm.put("k", prev);
                        return repo.findById(revPrev.longValue()).email();
                    }
                }
                """;
        var result = new JavaParserFrontend().analyze(TestInputs.request(Map.of("app/Use.java", client), List.of(dep)));
        var calls = result.observations().stream().filter(o -> o.category().value().equals("java.calls")).toList();
        assertEquals(5, calls.size());
        assertTrue(calls.stream().allMatch(o -> o.attribution() == SemanticStatus.RESOLVED),
                () -> "Unresolved calls: " + calls.stream().filter(o -> o.attribution() != SemanticStatus.RESOLVED).toList());
    }

    @Test
    void enclosingOuterTypeArgumentsArePreservedInBytecodeSignatures() throws Exception {
        Path jar = compileToJar("enclosing-outer", Map.of(
                "lib/Outer.java", """
                        package lib;
                        public class Outer<T> {
                            public class Inner {
                                public T get() { return null; }
                            }
                            public Inner make() { return new Inner(); }
                        }
                        """,
                "lib/Holder.java", """
                        package lib;
                        public class Holder {
                            public Outer<String>.Inner innerField;
                            public Outer<String>.Inner provide() { return null; }
                        }
                        """
        ));
        var dep = new BinaryInput(
                new ClasspathEntry(ClasspathEntryKind.DEPENDENCY, "test:enclosing-outer:1.0@jar", ContentDigest.sha256(Files.readAllBytes(jar))),
                jar);
        String client = """
                package app;
                import lib.Holder;
                class Use {
                    int len(Holder h) {
                        return h.provide().get().length() + h.innerField.get().length();
                    }
                }
                """;
        var result = new JavaParserFrontend().analyze(TestInputs.request(Map.of("app/Use.java", client), List.of(dep)));
        var calls = result.observations().stream().filter(o -> o.category().value().equals("java.calls")).toList();
        assertTrue(calls.size() >= 4);
        assertTrue(calls.stream().allMatch(o -> o.attribution() == SemanticStatus.RESOLVED),
                () -> "Unresolved calls: " + calls.stream().filter(o -> o.attribution() != SemanticStatus.RESOLVED).toList());
    }

    @Test
    void interfaceBridgeSyntheticFilteringNestedAnnotationDefaultsDiamondFieldsAndSyntheticCtorParams() throws Exception {
        Path jar = compileToJar("asm-decl-hardening", Map.of(
                "lib/BaseIface.java", """
                        package lib;
                        public interface BaseIface {
                            Object produce();
                            default Runnable helper() { return () -> {}; }
                        }
                        """,
                "lib/SubIface.java", """
                        package lib;
                        public interface SubIface extends BaseIface {
                            @Override String produce();
                        }
                        """,
                "lib/Container.java", """
                        package lib;
                        public class Container {
                            public @interface Inner { String value() default "ok"; }
                            public @interface NestedAnno {
                                Class<?> target() default String.class;
                                Inner single() default @Inner("one");
                                Inner[] many() default {@Inner("a"), @Inner("b")};
                            }
                            public class NonStaticInner {
                                public NonStaticInner(int x) {}
                            }
                        }
                        """,
                "lib/Color.java", """
                        package lib;
                        public enum Color {
                            RED("r");
                            private final String label;
                            Color(String label) { this.label = label; }
                            public String label() { return label; }
                        }
                        """,
                "lib/Diamond.java", """
                        package lib;
                        public class Diamond {
                            public interface Top { int ROOT = 1; }
                            public interface L1 extends Top { int A1 = 2; }
                            public interface R1 extends Top { int B1 = 3; }
                            public interface M1 extends L1, R1 {}
                            public interface L2 extends M1 {}
                            public interface R2 extends M1 {}
                            public interface M2 extends L2, R2 {}
                            public interface L3 extends M2 {}
                            public interface R3 extends M2 {}
                            public interface Bottom extends L3, R3 { int LEAF = 4; }
                        }
                        """
        ));

        var solver = buildSolver(jar);

        // 1. Interface bridge & synthetic lambda$ methods filtered out
        ResolvedReferenceTypeDeclaration baseIface = solver.solveType("lib.BaseIface");
        assertTrue(baseIface.getDeclaredMethods().stream().noneMatch(m -> m.getName().contains("lambda$")),
                "Synthetic lambda$ method must be filtered from interface declared methods");
        ResolvedReferenceTypeDeclaration subIface = solver.solveType("lib.SubIface");
        long produceCount = subIface.getDeclaredMethods().stream().filter(m -> m.getName().equals("produce")).count();
        assertEquals(1, produceCount, "Covariant bridge method on SubIface must be filtered");

        // 2. Nested annotation getClassName(), containerType(), ClassDefault, nested AnnotationDefault
        ResolvedAnnotationDeclaration nestedAnno = solver.solveType("lib.Container.NestedAnno").asAnnotation();
        assertEquals("Container.NestedAnno", nestedAnno.getClassName());
        assertEquals("NestedAnno", nestedAnno.getName());
        assertTrue(nestedAnno.containerType().isPresent());
        assertEquals("lib.Container", nestedAnno.containerType().orElseThrow().getQualifiedName());
        var membersByName = new HashMap<String, com.github.javaparser.resolution.declarations.ResolvedAnnotationMemberDeclaration>();
        for (var m : nestedAnno.getAnnotationMembers()) membersByName.put(m.getName(), m);
        assertTrue(membersByName.get("target").getDefaultValue() instanceof ClassExpr,
                "Class<?> default must materialize as ClassExpr");
        assertNotNull(membersByName.get("single").getDefaultValue(),
                "Nested annotation default must materialize as AnnotationExpr");
        assertNotNull(membersByName.get("many").getDefaultValue(),
                "Nested annotation array default must materialize as ArrayInitializerExpr");

        // 3. Diamond interface getAllFields() has zero exponential duplication
        ResolvedReferenceTypeDeclaration bottom = solver.solveType("lib.Diamond.Bottom");
        List<ResolvedFieldDeclaration> allFields = bottom.getAllFields();
        assertEquals(4, allFields.size(), "Diamond hierarchy must contain exactly 4 unique fields, got " + allFields);

        // 4. Synthetic constructor parameters stripped for non-static inner class & enum
        ResolvedReferenceTypeDeclaration nonStaticInner = solver.solveType("lib.Container.NonStaticInner");
        List<ResolvedConstructorDeclaration> innerCtors = nonStaticInner.getConstructors();
        assertEquals(1, innerCtors.size());
        assertEquals(1, innerCtors.getFirst().getNumberOfParams(), "Synthetic outer this$0 param must be stripped");
        assertEquals("int", innerCtors.getFirst().getParam(0).getType().describe());

        ResolvedReferenceTypeDeclaration colorEnum = solver.solveType("lib.Color");
        List<ResolvedConstructorDeclaration> enumCtors = colorEnum.getConstructors();
        assertEquals(1, enumCtors.size());
        assertEquals(1, enumCtors.getFirst().getNumberOfParams(), "Synthetic enum (String, int) params must be stripped");
        assertEquals("java.lang.String", enumCtors.getFirst().getParam(0).getType().describe());
    }

    @Test
    void bytecodeRecordPatternInferenceUsesAsmRecordComponents() throws Exception {
        Path jar = compileToJar("compiled-records", Map.of(
                "lib/CompiledPair.java", """
                        package lib;
                        public record CompiledPair(String key, int count) {}
                        """,
                "lib/CompiledBox.java", """
                        package lib;
                        public record CompiledBox<T>(T value) {}
                        """
        ));
        var dep = new BinaryInput(
                new ClasspathEntry(ClasspathEntryKind.DEPENDENCY, "test:compiled-records:1.0@jar", ContentDigest.sha256(Files.readAllBytes(jar))),
                jar);
        String client = """
                package app;
                import lib.CompiledPair;
                import lib.CompiledBox;
                class Use {
                    int simple(Object o) {
                        return switch (o) {
                            case CompiledPair(var k, var c) -> k.length() + c;
                            default -> 0;
                        };
                    }
                    int generic(CompiledBox<String> box) {
                        return switch (box) {
                            case CompiledBox<String>(var s) -> s.length();
                        };
                    }
                }
                """;
        var result = new JavaParserFrontend().analyze(TestInputs.request(Map.of("app/Use.java", client), List.of(dep)));
        var calls = result.observations().stream().filter(o -> o.category().value().equals("java.calls")).toList();
        assertEquals(2, calls.size());
        assertTrue(calls.stream().allMatch(o -> o.attribution() == SemanticStatus.RESOLVED),
                () -> "Bytecode record pattern calls failed: " + calls);
    }
}
