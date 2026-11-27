package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.source.SourceClassification;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;

import javax.tools.*;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Compiler parse phase only: no classpath resolution, processors, target plugins or code generation. */
public final class JavaRepairSyntax {
    public enum Status { PASSED, REJECTED, UNAVAILABLE }
    public record Profile(int sourceLevel, boolean preview, String origin, Optional<ContentDigest> evidence) {}
    public record ContextProfile(String module,String sourceSet,Profile profile,Map<String,Profile> contexts) {
        public ContextProfile { contexts = Map.copyOf(contexts); }
        ContextProfile(String module,String sourceSet,Profile profile) { this(module,sourceSet,profile,Map.of()); }

        public Profile forFile(RepairSource.File file) {
            if (module.equals(file.module()) && (sourceSet.equals(file.role().name())
                    || file.role()==SourceClassification.OTHER)) return profile;
            var exact=contexts.get(file.module()+"|"+file.role().name());
            if (exact!=null) return exact;
            if (file.role()==SourceClassification.OTHER) {
                var main=contexts.get(file.module()+"|MAIN");
                if (main!=null) return main;
            }
            // A finding can cite a reactor dependency or sibling module without an explicit manifest match.
            return defaultProfile();
        }
    }
    public record Error(String code,long line,long column) {}
    public record Check(Status status,String engine,String runtime,Profile profile,List<Error> diagnostics) {}
    public record DetailedError(String code, String message, long line, long column) {}
    public record DetailedCheck(Check check, List<DetailedError> details) {}
    record Parsed(Check check, CompilationUnitTree tree, SourcePositions positions) {}
    private static final int RUNTIME_LEVEL=Runtime.version().feature();
    private static final String ENGINE="jdk.javac.parse";

    public static ContextProfile profile(KnowledgeGraph graph,FindingDiagnosis.Pin pin) {
        var contexts=new LinkedHashMap<String,Profile>();
        ContextProfile primary=null;
        String pinnedAnalysis=pin.analysis().map(a -> a.value()).orElse(null);
        for (var context : graph.contexts()) {
            if (context.analysis().isEmpty()) continue;
            var manifest=graph.evidence(context.evidence()).filter(e -> e.family().equals("analysis.manifest"));
            if (manifest.isEmpty()) continue;
            var parsed=parseManifest(manifest.orElseThrow());
            if (parsed==null) continue;
            if (!parsed.module().isBlank() && !parsed.sourceSet().isBlank())
                contexts.putIfAbsent(parsed.module()+"|"+parsed.sourceSet(),parsed.profile());
            if (pinnedAnalysis!=null && context.identity().equals(pinnedAnalysis)
                    && context.analysis().equals(pin.analysis()))
                primary=parsed;
        }
        if (primary!=null) return new ContextProfile(primary.module(),primary.sourceSet(),primary.profile(),contexts);
        return new ContextProfile("","",defaultProfile(),contexts);
    }

    private static ContextProfile parseManifest(GraphEvidence artifact) {
        String json=artifact.canonicalJson();
        var values=new HashMap<String,Object>();
        try {
            GraphJson.streamObject(new StringReader(json),new GraphJson.StreamLimits(json.length(),128,Long.MAX_VALUE),
                    (key,member) -> {
                        if (!key.equals("configuration")) { member.skip(); return; }
                        member.object((field,value) -> {
                            if (!field.equals("values")) { value.skip(); return; }
                            value.object((name,item) -> {
                                if (Set.of("java.syntax","java.preview","java.module","java.source-set").contains(name))
                                    values.put(name,item.read());
                                else item.skip();
                            });
                        });
                    });
        } catch (IOException | IllegalArgumentException invalid) {
            return null;
        }
        String module=Objects.toString(values.get("java.module"),""),sourceSet=Objects.toString(values.get("java.source-set"),"");
        boolean preview="true".equals(values.get("java.preview"));
        if (values.get("java.syntax") instanceof String level && level.matches("[0-9]{1,4}"))
            return new ContextProfile(module,sourceSet,new Profile(Integer.parseInt(level),preview,"ANALYSIS_SYNTAX",Optional.of(artifact.identity())));
        // A Java API release is not a source-language level.
        return new ContextProfile(module,sourceSet,new Profile(RUNTIME_LEVEL,preview,"VALIDATOR_DEFAULT",Optional.of(artifact.identity())));
    }

    public static Profile defaultProfile() { return new Profile(RUNTIME_LEVEL,false,"VALIDATOR_DEFAULT",Optional.empty()); }

    public static DetailedCheck parseDetailed(String source, Profile profile) {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null || profile.sourceLevel() < 7 || profile.sourceLevel() > RUNTIME_LEVEL
                || profile.preview() && profile.sourceLevel() != RUNTIME_LEVEL) {
            return new DetailedCheck(failed(profile, "compiler.source-level-or-toolchain-unavailable").check(),
                    List.of(new DetailedError("compiler.source-level-or-toolchain-unavailable",
                            "System Java compiler or requested source level is unavailable", -1, -1)));
        }
        var errors = new ArrayList<Error>();
        var details = new ArrayList<DetailedError>();
        DiagnosticListener<JavaFileObject> listener = d -> {
            if (d.getKind() == Diagnostic.Kind.ERROR && errors.size() < 16) {
                errors.add(new Error(d.getCode(), d.getLineNumber(), d.getColumnNumber()));
                String msg = d.getMessage(Locale.ROOT);
                details.add(new DetailedError(d.getCode(), msg == null ? d.getCode() : msg, d.getLineNumber(), d.getColumnNumber()));
            }
        };
        var input = new SimpleJavaFileObject(URI.create("mem:///Repair.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
        };
        var options = new ArrayList<>(List.of("-proc:none", "--source", Integer.toString(profile.sourceLevel()), "-Xlint:-options"));
        if (profile.preview()) options.add("--enable-preview");
        try (var files = compiler.getStandardFileManager(listener, Locale.ROOT, StandardCharsets.UTF_8)) {
            var task = (JavacTask) compiler.getTask(new StringWriter(), files, listener, options, null, List.of(input));
            var iterator = task.parse().iterator();
            if (!iterator.hasNext()) {
                return errors.isEmpty()
                        ? new DetailedCheck(failed(profile, "compiler.no-compilation-unit").check(),
                            List.of(new DetailedError("compiler.no-compilation-unit", "No compilation unit parsed", -1, -1)))
                        : new DetailedCheck(check(Status.UNAVAILABLE, profile, errors), List.copyOf(details));
            }
            iterator.next();
            if (iterator.hasNext()) {
                return new DetailedCheck(failed(profile, "compiler.unexpected-compilation-units").check(),
                        List.of(new DetailedError("compiler.unexpected-compilation-units", "Multiple compilation units", -1, -1)));
            }
            if (!errors.isEmpty()) return new DetailedCheck(check(Status.REJECTED, profile, errors), List.copyOf(details));
            return new DetailedCheck(check(Status.PASSED, profile, List.of()), List.of());
        } catch (IOException | RuntimeException | StackOverflowError unavailable) {
            return new DetailedCheck(failed(profile, "compiler.parse-unavailable").check(),
                    List.of(new DetailedError("compiler.parse-unavailable", "Java syntax parser unavailable", -1, -1)));
        }
    }

    static Parsed parse(String source,Profile profile) {
        var compiler=ToolProvider.getSystemJavaCompiler();
        if (compiler==null || profile.sourceLevel()<7 || profile.sourceLevel()>RUNTIME_LEVEL
                || profile.preview() && profile.sourceLevel()!=RUNTIME_LEVEL)
            return failed(profile,"compiler.source-level-or-toolchain-unavailable");
        var errors=new ArrayList<Error>();
        DiagnosticListener<JavaFileObject> listener=d -> {
            if (d.getKind()==Diagnostic.Kind.ERROR && errors.size()<16)
                errors.add(new Error(d.getCode(),d.getLineNumber(),d.getColumnNumber()));
        };
        var input=new SimpleJavaFileObject(URI.create("mem:///Repair.java"),JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
        };
        var options=new ArrayList<>(List.of("-proc:none","--source",Integer.toString(profile.sourceLevel()),"-Xlint:-options"));
        if (profile.preview()) options.add("--enable-preview");
        try (var files=compiler.getStandardFileManager(listener,Locale.ROOT,StandardCharsets.UTF_8)) {
            var task=(JavacTask)compiler.getTask(new StringWriter(),files,listener,options,null,List.of(input));
            var iterator=task.parse().iterator();
            if (!iterator.hasNext()) return errors.isEmpty() ? failed(profile,"compiler.no-compilation-unit")
                    : new Parsed(check(Status.UNAVAILABLE,profile,errors),null,null);
            var unit=iterator.next();
            if (iterator.hasNext()) return failed(profile,"compiler.unexpected-compilation-units");
            if (!errors.isEmpty()) return new Parsed(check(Status.REJECTED,profile,errors),null,null);
            return new Parsed(check(Status.PASSED,profile,List.of()),unit,Trees.instance(task).getSourcePositions());
        } catch (IOException | RuntimeException | StackOverflowError unavailable) {
            return failed(profile,"compiler.parse-unavailable");
        }
    }

    private static Parsed failed(Profile profile,String code) {
        return new Parsed(check(Status.UNAVAILABLE,profile,List.of(new Error(code,-1,-1))),null,null);
    }
    private static Check check(Status status,Profile profile,List<Error> diagnostics) {
        return new Check(status,ENGINE,Runtime.version().toString(),profile,List.copyOf(diagnostics));
    }
    private JavaRepairSyntax() {}
}
