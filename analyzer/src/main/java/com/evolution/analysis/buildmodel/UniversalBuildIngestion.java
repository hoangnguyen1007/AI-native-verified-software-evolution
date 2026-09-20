package com.evolution.analysis.buildmodel;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.ingestion.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.SourceInput;
import java.util.*;
import static com.evolution.analysis.ingestion.IngestionEvidence.Reason.*;
import static com.evolution.analysis.buildmodel.SourcePlanModel.*;

/** Passive router with an injected Maven provider; no target script, plugin, process or network execution. */
public final class UniversalBuildIngestion {
    public static final VersionedIdentifier PROVIDER=new VersionedIdentifier("build.universal-ingestion","m4u.1");
    public record Policy(Optional<Integer> syntaxLevel, Optional<Integer> platformRelease, Optional<String> encoding,
                         int maxFiles,int maxCharacters,int maxTokens,int maxDepth) {
        public Policy { Objects.requireNonNull(syntaxLevel);Objects.requireNonNull(platformRelease);Objects.requireNonNull(encoding);
            if(maxFiles<1||maxCharacters<1||maxTokens<1||maxDepth<1||maxDepth>128
                    ||syntaxLevel.filter(v->v<1).isPresent()||platformRelease.filter(v->v<1).isPresent())throw new IllegalArgumentException("Invalid build ingestion limits");
            encoding.ifPresent(v->ContractChecks.text(v,"build encoding")); }
        public static Policy defaults(){return new Policy(Optional.empty(),Optional.empty(),Optional.empty(),2048,1_000_000,200_000,64);}
    }
    public UniversalBuildModel ingest(RepositoryInputs inputs,Policy policy,Optional<BuildModelProvider> maven,
                                      Map<MavenCoordinate,PomInput> externalPoms,BuildModelPolicy mavenPolicy) {
        var external=new TreeMap<String,ContentDigest>();externalPoms.forEach((k,v)->external.put(k.notation(),v.digest()));
        var identity=IngestionEvidence.digest(List.of(PROVIDER,inputs.identity(),policy,external,mavenPolicy));
        var issues=new ArrayList<IngestionEvidence.Issue>();var modules=new ArrayList<UniversalBuildModel.Module>();var mavenResults=new ArrayList<BuildModelResult>();
        Map<String,PomInput> poms=new TreeMap<>();
        Set<String> directories=new TreeSet<>();Map<String,List<DeclarativeScript.Statement>> scripts=new TreeMap<>();
        int fileCount=0;
        for(var file:inputs.snapshot().files()) {
            String path=file.path(),name=filename(path);
            if(!Set.of("pom.xml","build.gradle","build.gradle.kts","settings.gradle","settings.gradle.kts").contains(name))continue;
            if(++fileCount>policy.maxFiles()){issue(issues,INPUT_LIMIT,path,file.contentDigest());continue;}
            directories.add(directory(path));var source=inputs.files().get(path);
            if(source==null){issue(issues,INPUT_UNAVAILABLE,path,file.contentDigest());continue;}
            if(source.text().length()>policy.maxCharacters()){issue(issues,INPUT_LIMIT,path,file.contentDigest());continue;}
            if(name.equals("pom.xml"))poms.put(path,new PomInput(source.bytes()));
            else try {
                scripts.put(path,DeclarativeScript.parse(source.text(),policy.maxTokens(),policy.maxDepth()));
            }catch(DeclarativeScript.Limit failure){issue(issues,INPUT_LIMIT,path,file.contentDigest());}
            catch(IllegalArgumentException failure){issue(issues,MALFORMED_INPUT,path,file.contentDigest());}
        }
        // Literal settings include rows are the project denominator, including projects with no build file.
        Map<String,String> projectNames=new HashMap<>();
        for(var entry:scripts.entrySet())if(filename(entry.getKey()).startsWith("settings.")) {
            String root=directory(entry.getKey());
            for(var statement:entry.getValue()) {
                if(statement.key().equals("include")&&statement.body().isEmpty()&&!statement.strings().isEmpty()
                        && statement.syntax().matches("include(?:\\(#(?:,#)*\\)|#(?:,#)*)")) {
                    for(String project:statement.strings())try {
                        if(project.contains("$")){issue(issues,DYNAMIC_BUILD_LOGIC,entry.getKey()+"#"+statement.offset(),identity);continue;}
                        String relative=project.replaceFirst("^:","").replace(':','/');String dir=join(root,relative);
                        if(!directories.contains(dir)&&directories.size()>=policy.maxFiles()){issue(issues,INPUT_LIMIT,entry.getKey()+"#"+statement.offset(),identity);continue;}
                        directories.add(dir);projectNames.put(dir,relative.substring(relative.lastIndexOf('/')+1));
                    }catch(IllegalArgumentException failure){issue(issues,UNSAFE_PATH,entry.getKey()+"#"+statement.offset(),inputs.files().get(entry.getKey()).document().contentDigest());}
                } else if(statement.syntax().equals("rootProject.name=#")&&!statement.strings().getFirst().contains("$")&&!statement.strings().getFirst().isBlank())projectNames.put(root,statement.strings().getFirst());
                else issue(issues,DYNAMIC_BUILD_LOGIC,entry.getKey()+"#"+statement.offset(),inputs.files().get(entry.getKey()).document().contentDigest());
            }
        }
        if(directories.isEmpty())directories.add(".");
        Set<String> modeledMaven=new HashSet<>();
        for(String dir:directories) {
            String pom=join(dir,"pom.xml"),groovy=join(dir,"build.gradle"),kotlin=join(dir,"build.gradle.kts");
            boolean hasPom=inputs.snapshot().files().stream().anyMatch(f->f.path().equals(pom));
            boolean hasGroovy=inputs.snapshot().files().stream().anyMatch(f->f.path().equals(groovy));
            boolean hasKotlin=inputs.snapshot().files().stream().anyMatch(f->f.path().equals(kotlin));
            if(hasPom&&(hasGroovy||hasKotlin)||hasGroovy&&hasKotlin)issue(issues,AMBIGUOUS_INPUT,dir,identity);
            if(hasPom) {
                if(modeledMaven.contains(pom))continue;
                if(maven.isEmpty()){issue(issues,INPUT_UNAVAILABLE,pom,identity);continue;}
                var result=maven.orElseThrow().build(new BuildModelRequest(inputs.snapshot(),pom,poms,externalPoms,mavenPolicy));mavenResults.add(result);
                for(var module:result.modules()) {
                    modeledMaven.add(module.pomPath());
                    if(module.effectivePom().isEmpty()){issue(issues,INPUT_UNAVAILABLE,module.pomPath(),result.identity());continue;}
                    var effective=module.effectivePom().orElseThrow();
                    modules.add(new UniversalBuildModel.Module(module.module(),UniversalBuildModel.Tool.MAVEN,Optional.of(module.pomPath()),
                            Optional.of(effective.coordinate()),effective.sourcePlan(),List.of(),effective.inputs()));
                }
                if(result.hasGaps())for(var problem:result.problems())issue(issues,SOURCE_INCOMPLETE,problem.subject(),result.identity());
                continue;
            }
            Optional<String> build=hasKotlin?Optional.of(kotlin):hasGroovy?Optional.of(groovy):Optional.empty();
            var tool=hasKotlin?UniversalBuildModel.Tool.GRADLE_KOTLIN:hasGroovy?UniversalBuildModel.Tool.GRADLE_GROOVY:UniversalBuildModel.Tool.PLAIN_JAVA;
            var evidence=build.flatMap(p->Optional.ofNullable(inputs.files().get(p))).map(f->List.of(new BuildModelResult.PomEvidence(f.document().path(),f.document().contentDigest()))).orElse(List.of());
            var descriptor=ModuleDescriptor.create(inputs.snapshot().repository(),dir,projectNames.getOrDefault(dir,dir.equals(".")?"repository":filename(dir)));
            var model=new Projection(dir,policy,evidence,issues,identity);
            build.ifPresent(p->model.read(scripts.getOrDefault(p,List.of()),""));
            if(tool==UniversalBuildModel.Tool.PLAIN_JAVA) {
                boolean conventional=inputs.snapshot().files().stream().anyMatch(f->f.path().startsWith(join(dir,"src/main/java")+"/")&&f.path().endsWith(".java"));
                if(!conventional)model.mainRoots=List.of(inputs.snapshot().files().stream().anyMatch(f->f.path().startsWith(join(dir,"src")+"/")&&f.path().endsWith(".java"))?join(dir,"src"):dir);
            }
            Optional<MavenCoordinate> coordinate=Optional.empty();
            if(model.group!=null&&model.version!=null&&projectNames.containsKey(dir))try{coordinate=Optional.of(new MavenCoordinate(model.group,descriptor.displayName(),model.version));}
            catch(IllegalArgumentException failure){issue(issues,UNRESOLVED_COORDINATE,dir,identity);}
            if(!model.dependencies.isEmpty())issue(issues,CLASSPATH_RESOLUTION_REQUIRED,dir,identity);
            modules.add(new UniversalBuildModel.Module(descriptor,tool,build,coordinate,model.plan(descriptor,tool),model.dependencies,evidence));
        }
        var sorted=issues.stream().distinct().sorted(Comparator.comparing(IngestionEvidence.Issue::identity)).toList();
        return new UniversalBuildModel(identity,inputs.identity(),modules,mavenResults,sorted,IngestionEvidence.gaps(inputs.snapshot().identity(),PROVIDER,identity,sorted));
    }

    private static final class Projection {
        final String dir;final Policy policy;final List<BuildModelResult.PomEvidence> evidence;final List<IngestionEvidence.Issue> issues;final ContentDigest identity;
        final List<UniversalBuildModel.Dependency> dependencies=new ArrayList<>();
        String group,version;Optional<Integer> syntax,platform,target;Optional<String> encoding;List<String> mainRoots,testRoots,mainResources,testResources;
        final Set<String> declared=new HashSet<>(),declaredRoots=new HashSet<>();
        boolean explicitSource,explicitTarget,releaseOption;
        Projection(String dir,Policy policy,List<BuildModelResult.PomEvidence> evidence,List<IngestionEvidence.Issue> issues,ContentDigest identity){
            this.dir=dir;this.policy=policy;this.evidence=evidence;this.issues=issues;this.identity=identity;
            syntax=policy.syntaxLevel();platform=policy.platformRelease();target=policy.syntaxLevel();encoding=policy.encoding();
            mainRoots=List.of(join(dir,"src/main/java"));testRoots=List.of(join(dir,"src/test/java"));
            mainResources=List.of(join(dir,"src/main/resources"));testResources=List.of(join(dir,"src/test/resources"));
        }
        void read(List<DeclarativeScript.Statement> statements,String context) {
            for(var s:statements) {
                String shape=s.syntax();boolean handled=false;
                if(context.equals("dependencies")) {dependency(s);continue;}
                if(!s.body().isEmpty()) {
                    boolean knownBlock=shape.equals(s.key()) && (context.isEmpty()&&Set.of("dependencies","java","sourceSets").contains(s.key())
                            ||context.equals("java")&&s.key().equals("toolchain")
                            ||context.equals("sourceSets")&&Set.of("main","test").contains(s.key())
                            ||Set.of("sourceSets.main","sourceSets.test").contains(context)&&Set.of("java","resources").contains(s.key()));
                    if(knownBlock) {
                        read(s.body(),context.isEmpty()?s.key():context+"."+s.key());handled=true;
                    } else if(context.isEmpty() && shape.matches("tasks\\.withType(?:<JavaCompile>\\(\\)|\\(JavaCompile\\))(?:\\.configureEach)?")) {
                        read(s.body(),"javaCompile");handled=true;
                    } else if(shape.equals("plugins")&&context.isEmpty()) {
                        for(var plugin:s.body())if(!(Set.of("java","java-library").contains(plugin.syntax())
                                ||plugin.syntax().matches("id(?:#|\\(#\\))")&&plugin.strings().size()==1&&Set.of("java","java-library").contains(plugin.strings().getFirst())))gap(plugin);
                        handled=true;
                    } else if(shape.equals("repositories")&&context.isEmpty()) {
                        for(var repository:s.body())if(!Set.of("mavenCentral()","mavenLocal()","google()").contains(repository.syntax()))gap(repository);
                        handled=true;
                    }
                } else {
                    if((shape.equals("group=#")||shape.equals("group#"))&&context.isEmpty()){try{group=literal(s);}catch(IllegalArgumentException failure){group=null;gap(s);}handled=true;}
                    else if((shape.equals("version=#")||shape.equals("version#"))&&context.isEmpty()){try{version=literal(s);}catch(IllegalArgumentException failure){version=null;gap(s);}handled=true;}
                    else if(Set.of("","java","java.toolchain","javaCompile").contains(context)&&shape.matches("(?:sourceCompatibility|targetCompatibility|languageVersion|options\\.release)(?:=|\\.set\\().+")) {
                        var numbers=s.head().stream().filter(t->!t.literal()&&t.value().matches("[0-9]+|VERSION_[0-9]+"))
                                .map(t->t.value().replace("VERSION_","")).toList();
                        try {
                            if(!shape.matches("(?:sourceCompatibility|targetCompatibility)=(?:#|[0-9]+|JavaVersion\\.VERSION_[0-9]+)"
                                    +"|languageVersion=JavaLanguageVersion\\.of\\([0-9]+\\)|options\\.release(?:=[0-9]+|\\.set\\([0-9]+\\))"))throw new IllegalArgumentException();
                            int level=numbers.size()==1?Integer.parseInt(numbers.getFirst()):Integer.parseInt(literal(s).replaceFirst("^1\\.",""));
                            if(level<1||level>999)throw new IllegalArgumentException();
                            if(releaseOption&&!s.key().equals("options")){gap(s);handled=true;continue;}
                            if(s.key().equals("sourceCompatibility")){syntax=Optional.of(level);explicitSource=true;declared.add("source");if(!explicitTarget){target=syntax;declared.add("target");}}
                            else if(s.key().equals("targetCompatibility")){target=Optional.of(level);explicitTarget=true;declared.add("target");}
                            else if(s.key().equals("languageVersion")) {
                                platform=Optional.of(level);declared.add("release");
                                if(!explicitSource) {syntax=platform;declared.add("source");}
                                if(!explicitTarget) {target=syntax;declared.add("target");}
                            } else {if(explicitSource||explicitTarget)gap(s);releaseOption=true;syntax=Optional.of(level);platform=syntax;target=syntax;declared.addAll(List.of("source","target","release"));}handled=true;
                        }catch(IllegalArgumentException failure){if(s.key().equals("sourceCompatibility"))syntax=Optional.empty();else if(s.key().equals("targetCompatibility"))target=Optional.empty();else {syntax=Optional.empty();platform=Optional.empty();target=Optional.empty();}gap(s);handled=true;}
                    } else if(shape.equals("options.encoding=#")&&context.equals("javaCompile")){try{encoding=Optional.of(literal(s));declared.add("encoding");}catch(IllegalArgumentException failure){encoding=Optional.empty();gap(s);}handled=true;}
                    else if((s.key().equals("srcDirs")||s.key().equals("srcDir")||s.key().equals("setSrcDirs"))&&context.startsWith("sourceSets.")) {
                        try {
                            if(s.strings().isEmpty()||!shape.matches("(?:srcDir|srcDirs|setSrcDirs)(?:\\(#(?:,#)*\\)|#|=\\[#(?:,#)*\\])"))throw new IllegalArgumentException();
                            var roots=s.strings().stream().map(p->join(dir,p)).toList();boolean test=context.contains(".test"),resource=context.endsWith(".resources");
                            declaredRoots.addAll(roots);
                            boolean replace=s.key().equals("setSrcDirs")||shape.startsWith("srcDirs=");
                            var prior=test?(resource?testResources:testRoots):(resource?mainResources:mainRoots);
                            if(!replace)roots=java.util.stream.Stream.concat(prior.stream(),roots.stream()).distinct().toList();
                            if(test&&resource)testResources=roots;else if(test)testRoots=roots;else if(resource)mainResources=roots;else mainRoots=roots;handled=true;
                        }catch(IllegalArgumentException failure){issue(issues,UNSAFE_PATH,dir+"#"+s.offset(),identity);handled=true;}
                    }
                }
                if(!handled)gap(s);
            }
        }
        void dependency(DeclarativeScript.Statement s) {
            var configurations=Set.of("implementation","api","compileOnly","runtimeOnly","testImplementation","testCompileOnly","testRuntimeOnly","annotationProcessor","testAnnotationProcessor","compile","testCompile","runtime");
            if(!configurations.contains(s.key())||!s.body().isEmpty()){gap(s);return;}
            Optional<MavenCoordinate> coordinate=Optional.empty();Optional<String> project=Optional.empty();String notation="";
            String shape=s.syntax();
            try {
                if(shape.matches("[A-Za-z]+(?:\\(project\\(#\\)\\)|project\\(#\\))")) {
                    notation=literal(s);if(!notation.matches(":?[A-Za-z0-9_.-]+(?::[A-Za-z0-9_.-]+)*"))throw new IllegalArgumentException();project=Optional.of(notation);
                } else if(shape.matches("[A-Za-z]+(?:\\(#\\)|#)")) {
                    notation=literal(s);String[] gav=notation.split(":",-1);
                    if(gav.length==3&&!gav[2].contains("+")&&!gav[2].startsWith("latest."))coordinate=Optional.of(new MavenCoordinate(gav[0],gav[1],gav[2]));
                    else issue(issues,DEPENDENCY_VERSION_UNRESOLVED,dir+"#"+s.offset(),identity);
                } else {gap(s);return;}
                if(notation.contains("$")){coordinate=Optional.empty();issue(issues,DEPENDENCY_VERSION_UNRESOLVED,dir+"#"+s.offset(),identity);}
                dependencies.add(new UniversalBuildModel.Dependency(s.key(),coordinate,project,notation,evidence.getFirst()));
            }catch(IllegalArgumentException failure){issue(issues,DEPENDENCY_VERSION_UNRESOLVED,dir+"#"+s.offset(),identity);}
        }
        String literal(DeclarativeScript.Statement s){if(s.strings().size()!=1||s.strings().getFirst().contains("$"))throw new IllegalArgumentException();return s.strings().getFirst();}
        void gap(DeclarativeScript.Statement s){issue(issues,DYNAMIC_BUILD_LOGIC,dir+"#"+s.offset(),identity);}
        SourcePlanModel plan(ModuleDescriptor descriptor,UniversalBuildModel.Tool tool) {
            if(syntax.isEmpty()||platform.isEmpty()||encoding.isEmpty())issue(issues,COMPILER_CONFIGURATION_MISSING,dir,identity);
            var origin=tool==UniversalBuildModel.Tool.PLAIN_JAVA?Origin.PLAIN_JAVA_CONVENTION:Origin.GRADLE_CONVENTION;
            var sets=new ArrayList<SourceSetPlan>();
            for(var kind:Kind.values()) {
                var gaps=new ArrayList<Gap>();if(syntax.isEmpty())gaps.add(Gap.MISSING_SOURCE_LEVEL);if(platform.isEmpty())gaps.add(Gap.MISSING_PLATFORM_RELEASE);if(encoding.isEmpty())gaps.add(Gap.MISSING_ENCODING);
                if(issues.stream().anyMatch(i->i.subject().startsWith(dir+"#")))gaps.add(Gap.PLUGIN_EFFECTS_NOT_EVALUATED);
                var roots=kind==Kind.MAIN?mainRoots:testRoots;var resources=kind==Kind.MAIN?mainResources:testResources;
                sets.add(new SourceSetPlan(descriptor.identity(),kind,roots.stream().map(p->setting("sourceRoot",Optional.of(p),declaredRoots.contains(p)?Origin.EFFECTIVE_MODEL:origin)).toList(),
                        resources.stream().map(p->setting("resourceRoot",Optional.of(p),declaredRoots.contains(p)?Origin.EFFECTIVE_MODEL:origin)).toList(),setting("output",Optional.empty(),origin),Map.of(),
                        setting("source",syntax.map(Object::toString),compilerOrigin("source")),setting("target",target.map(Object::toString),compilerOrigin("target")),
                        setting("release",platform.map(Object::toString),compilerOrigin("release")),setting("encoding",encoding,compilerOrigin("encoding")),List.of(),gaps));
            }
            return new SourcePlanModel(sets,List.of());
        }
        Origin compilerOrigin(String key){return declared.contains(key)?Origin.EFFECTIVE_MODEL:Origin.USER_PROPERTY;}
        Setting setting(String key,Optional<String> value,Origin origin){return new Setting(key,value,value,value.isEmpty()?Status.UNSPECIFIED:
                Set.of(Origin.GRADLE_CONVENTION,Origin.PLAIN_JAVA_CONVENTION).contains(origin)?Status.DEFAULT:Status.DECLARED,value.isEmpty()?Origin.ABSENT:origin,evidence);}
    }
    private static String directory(String path){int i=path.lastIndexOf('/');return i<0?".":path.substring(0,i);}
    private static String filename(String path){return path.substring(path.lastIndexOf('/')+1);}
    private static String join(String dir,String path){ContractChecks.repositoryRelativePath(path,"build path");String result=dir.equals(".")?path:dir+"/"+path;return ContractChecks.repositoryRelativePath(result,"build path");}
    private static void issue(List<IngestionEvidence.Issue> issues,IngestionEvidence.Reason reason,String subject,ContentDigest input){issues.add(new IngestionEvidence.Issue(reason,subject,List.of(input)));}
}
