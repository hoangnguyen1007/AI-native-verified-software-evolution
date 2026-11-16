package com.evolution.analysis.evolution;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.RepositorySnapshot;
import com.evolution.analysis.contract.source.SnapshotFile;
import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.lang.model.element.Modifier;
import javax.tools.*;

/** Independent, closed source census for the sealed Spring demo's constructor-only fragment. */
public final class DemoBindingCensus {
    private static final String METHOD = "spring.demo-binding-census:javac21-v1";
    // All nine pinned jars were rehashed and checked for app/ classes, including multi-release entries.
    static final ContentDigest CLASSPATH_CENSUS = new ContentDigest("sha256:a7c5763f21dbaf56f84c53291c74e87c86d6232db1e2e3734443fae938f7d0e2");
    private static final String APP = "src/main/java/app/App.java";
    private static final String CLIENT = "src/main/java/app/Client.java";
    private static final String STORE = "src/main/java/app/Store.java";
    private static final Set<String> FILES = Set.of("build.gradle", APP, CLIENT, STORE);
    private static final String COMPONENT = "org.springframework.stereotype.Component";

    public record Receipt(String method, String parserRuntime, SnapshotIdentity snapshot, ContentDigest graph, String context,
                          List<SnapshotFile> files, ContentDigest classpathCensus, String profile, List<String> beans, int injectionSites) {
        public ContentDigest identity() { return CanonicalJson.sha256(this); }
    }

    private final Receipt receipt;

    private DemoBindingCensus(Receipt receipt) { this.receipt = receipt; }

    public Receipt receipt() { return receipt; }

    /** Read and parse only. No attribution, annotation processing, build, or target class loading. */
    public static Optional<DemoBindingCensus> verify(RepositoryIdentity repository, SnapshotIdentity expected,
            ContentDigest graph, String context, Map<String,byte[]> sources) {
        if (!sources.keySet().equals(FILES) || Runtime.version().feature()!=21) return Optional.empty();
        var files = new ArrayList<SnapshotFile>();
        var text = new TreeMap<String,String>();
        try {
            for (var entry : sources.entrySet()) {
                if (entry.getValue().length>4096) return Optional.empty();
                var bytes = entry.getValue().clone();
                var digest = ContentDigest.sha256(bytes);
                files.add(new SnapshotFile(entry.getKey(),digest));
                text.put(entry.getKey(),StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
            }
            var snapshot = RepositorySnapshot.create(repository,Optional.empty(),false,files,List.of());
            if (!snapshot.identity().equals(expected) || !"plugins { java }".equals(text.remove("build.gradle")))
                return Optional.empty();
            if (!checkJava(text)) return Optional.empty();
            return Optional.of(new DemoBindingCensus(new Receipt(METHOD,Runtime.version().toString(),expected,graph,context,
                    snapshot.files(),CLASSPATH_CENSUS,"dev",List.of("client","store"),0)));
        } catch (IOException | IllegalArgumentException unavailable) {
            return Optional.empty();
        }
    }

    private static boolean checkJava(Map<String,String> sources) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler==null) return false;
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var inputs = sources.entrySet().stream().map(entry -> new SimpleJavaFileObject(
                URI.create("memory:///"+entry.getKey()),JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return entry.getValue(); }
        }).toList();
        var seen = new HashSet<String>();
        try (var files = compiler.getStandardFileManager(diagnostics,Locale.ROOT,StandardCharsets.UTF_8)) {
            var task = (JavacTask)compiler.getTask(null,files,diagnostics,List.of("-proc:none","--release","21"),null,inputs);
            for (var unit : task.parse()) {
                if (unit.getPackageName()==null || !"app".equals(unit.getPackageName().toString())
                        || !unit.getPackageAnnotations().isEmpty() || unit.getModule()!=null || !unit.getImports().isEmpty()
                        || unit.getTypeDecls().size()!=1 || !(unit.getTypeDecls().getFirst() instanceof ClassTree type)
                        || !shape(type)) return false;
                var name = type.getSimpleName().toString();
                if (!unit.getSourceFile().toUri().getPath().equals("/src/main/java/app/"+name+".java") || !seen.add(name))
                    return false;
            }
        }
        return seen.equals(Set.of("App","Client","Store"))
                && diagnostics.getDiagnostics().stream().noneMatch(d -> d.getKind()==Diagnostic.Kind.ERROR);
    }

    private static boolean shape(ClassTree type) {
        if (type.getKind()!=Tree.Kind.CLASS || !type.getTypeParameters().isEmpty() || type.getExtendsClause()!=null
                || !type.getImplementsClause().isEmpty() || !type.getPermitsClause().isEmpty()) return false;
        var annotations = type.getModifiers().getAnnotations();
        return switch (type.getSimpleName().toString()) {
            case "App" -> type.getModifiers().getFlags().isEmpty() && type.getMembers().isEmpty()
                    && annotations.size()==1 && annotation(annotations.getFirst(),"org.springframework.context.annotation.ComponentScan","app");
            case "Client" -> type.getModifiers().getFlags().isEmpty() && emptyConstructor(type) && annotations.size()==2
                    && annotations.stream().anyMatch(a -> annotation(a,COMPONENT,null))
                    && annotations.stream().anyMatch(a -> annotation(a,"org.springframework.context.annotation.Profile","dev"));
            case "Store" -> type.getModifiers().getFlags().equals(Set.of(Modifier.PUBLIC)) && emptyConstructor(type)
                    && annotations.size()==1 && annotation(annotations.getFirst(),COMPONENT,null);
            default -> false;
        };
    }

    private static boolean emptyConstructor(ClassTree type) {
        return type.getMembers().size()==1 && type.getMembers().getFirst() instanceof MethodTree method
                && method.getName().contentEquals("<init>") && method.getReturnType()==null
                && method.getModifiers().getFlags().equals(Set.of(Modifier.PUBLIC)) && method.getModifiers().getAnnotations().isEmpty()
                && method.getParameters().isEmpty() && method.getReceiverParameter()==null && method.getTypeParameters().isEmpty()
                && method.getThrows().isEmpty() && method.getBody()!=null && method.getBody().getStatements().isEmpty();
    }

    private static boolean annotation(AnnotationTree annotation, String name, String value) {
        if (!name.equals(annotation.getAnnotationType().toString())) return false;
        var arguments = annotation.getArguments();
        return value==null ? arguments.isEmpty() : arguments.size()==1
                && arguments.getFirst() instanceof LiteralTree literal && value.equals(literal.getValue());
    }
}
