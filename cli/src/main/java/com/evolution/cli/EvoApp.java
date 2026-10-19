package com.evolution.cli;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.acquisition.*;
import com.evolution.analysis.assessment.*;
import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.evidence.EvidenceContext;
import com.evolution.analysis.filesystem.*;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.ingestion.*;
import com.evolution.analysis.input.*;
import com.evolution.analysis.javaparser.JavaParserFrontend;
import com.evolution.analysis.maven.MavenBuildModelProvider;
import com.evolution.analysis.metrics.*;
import com.evolution.analysis.policy.*;
import com.evolution.analysis.query.*;
import com.evolution.backend.query.*;
import com.evolution.backend.analysis.MavenScan;
import com.evolution.analysis.readiness.*;
import com.evolution.backend.analysis.ScanSelection;
import com.evolution.backend.analysis.RepositoryIntake;
import com.evolution.analysis.acquisition.streaming.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.AutoComplete;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.*;
import static com.evolution.analysis.query.ArchitectureQuery.*;

/** Commands embed canonical services. No HTTP server or graph database is started. */
public final class EvoApp {
    public static final String RELEASE="0.1.0";
    public static final String VERSION="evo "+RELEASE+" / architecture.cli:m7.2-v1";
    private final Path directory;private final BufferedReader input;private final PrintWriter out,err;
    private final boolean terminal;private final Map<String,String> environment;
    private final TerminalSession.Profile terminalProfile;
    private String sessionLang="en",sessionColor="auto";private boolean sessionAscii;
    private Root interactiveDefaults;
    private KnowledgeGraph sessionGraph;
    private Continuation continuation;
    private record Continuation(Root options,String name,String operation,KnowledgeGraph graph,InMemoryGraphStore store,
                                PublishedReports reports,PublishedReports.Report report,String failOn,String path,byte[] body){}
    public EvoApp(Path directory,Reader input,PrintWriter out,PrintWriter err,boolean terminal,Map<String,String> environment) {
        this(directory,input,out,err,terminal,environment,TerminalSession.Profile.suppliedWriter());
    }
    EvoApp(Path directory,Reader input,PrintWriter out,PrintWriter err,boolean terminal,Map<String,String> environment,TerminalSession.Profile terminalProfile) {
        this.directory=directory.toAbsolutePath().normalize();this.input=new BufferedReader(input);this.out=out;this.err=err;
        this.terminal=terminal;this.environment=Map.copyOf(environment);
        this.terminalProfile=Objects.requireNonNull(terminalProfile);
    }
    @Command(name="evo",version=VERSION,mixinStandardHelpOptions=true,sortOptions=false)
    private final class Root implements Callable<Integer> {
        @Option(names={"-w","--workspace"},scope=ScopeType.INHERIT,paramLabel="DIR",description="Session directory / Thư mục phiên") Path workspace=directory;
        @Option(names={"-g","--graph"},scope=ScopeType.INHERIT,paramLabel="FILE",description="Canonical snapshot / Snapshot canonical") Path graph;
        @Option(names={"-c","--context"},scope=ScopeType.INHERIT,paramLabel="ID|N",description="Exact context ID or index / Ngữ cảnh chính xác") String context;
        @Option(names="--lang",scope=ScopeType.INHERIT,paramLabel="en|vi",description="Interface language / Ngôn ngữ") String lang=sessionLang;
        @Option(names="--color",scope=ScopeType.INHERIT,paramLabel="auto|always|never",description="Terminal color / Màu terminal") String color=sessionColor;
        @Option(names="--ascii",scope=ScopeType.INHERIT,description="ASCII decoration / Trang trí ASCII") boolean ascii=sessionAscii;
        @Option(names="--width",scope=ScopeType.INHERIT,paramLabel="32..160",description="Report width / Chiều rộng") int width=defaultWidth();
        @Option(names="--json",scope=ScopeType.INHERIT,description="Canonical JSON only / Chỉ JSON canonical") boolean json;
        @Option(names="--details",scope=ScopeType.INHERIT,description="Full report fields / Chi tiết đầy đủ") boolean details;
        @Option(names="--debug",scope=ScopeType.INHERIT,description="Sanitized failure frames / Vị trí lỗi đã lọc") boolean debug;
        @Option(names={"-s","--scope"},scope=ScopeType.INHERIT,paramLabel="SCOPE",description="entity|type|package|module|spring-component / Phạm vi") String scope="type";
        @Option(names={"-v","--view"},scope=ScopeType.INHERIT,paramLabel="VIEW",description="union|must|may|unknown|realized / Phép chiếu") String projection="union";
        @Option(names="--world",scope=ScopeType.INHERIT,paramLabel="ID",description="Explicit realized world / Thế giới cấu hình") String world;
        @Option(names="--assignment",scope=ScopeType.INHERIT,paramLabel="FILE",description="Symbolic Boolean assignment / Gán biến Boolean JSON") Path assignment;
        @Option(names="--incoming",scope=ScopeType.INHERIT,description="Dependents / Phụ thuộc đến") boolean incoming;
        @Option(names="--kinds",scope=ScopeType.INHERIT,split=",",paramLabel="KIND,...",description="Relationship kinds / Loại quan hệ") Set<String> kinds=Set.of("graph.depends-on");
        @Option(names={"-n","--limit"},scope=ScopeType.INHERIT,paramLabel="1..50",description="Page/result size / Kích thước trang") int limit=20;
        @Option(names="--depth",scope=ScopeType.INHERIT,paramLabel="1..20",description="Traversal depth / Độ sâu duyệt") int depth=5;
        @Option(names="--cursor",scope=ScopeType.INHERIT,paramLabel="TOKEN",description="Continue same query / Tiếp tục cùng truy vấn") String cursor;
        @Option(names="--request",scope=ScopeType.INHERIT,paramLabel="FILE",description="Exact M7.1 request JSON / Yêu cầu JSON M7.1") Path request;
        @Option(names="--args",scope=ScopeType.INHERIT,paramLabel="JSON",description="Generic operation arguments / Tham số thao tác") String arguments;
        @Option(names="--other",scope=ScopeType.INHERIT,paramLabel="FILE",description="Other graph for compatibility / Đồ thị đối chiếu tương thích") Path other;
        @Option(names="--policy",scope=ScopeType.INHERIT,paramLabel="FILE",description="Explicit policy / Chính sách đầu vào") Path policy;
        @Spec CommandSpec spec;
        Request activeRequest;
        Map<String,String> labels=Map.of();
        Map<String,List<Map<String,Object>>> descriptors=Map.of();
        String stage="command";
        Workspace storage(){return new Workspace(resolve(workspace));}
        TerminalView view(){validate();return new TerminalView(out,lang.equals("vi"),!json&&colors(color),ascii,width);}
        void validate(){if(!Set.of("en","vi").contains(lang)||!Set.of("auto","always","never").contains(color)||width<32||width>160||limit<1||limit>50||depth<1||depth>20)throw new IllegalArgumentException();}
        public Integer call() throws Exception {validate();if(terminal&&!json)return interact(this);help(spec.commandLine(),this);return 0;}
    }
    public int execute(String... arguments) {
        var root=new Root();
        if(interactiveDefaults!=null){var d=interactiveDefaults;root.workspace=d.workspace;root.graph=d.graph;root.context=d.context;root.scope=d.scope;root.projection=d.projection;root.world=d.world;root.assignment=d.assignment;root.incoming=d.incoming;root.kinds=d.kinds;root.limit=d.limit;root.depth=d.depth;root.width=d.width;root.details=d.details;root.debug=d.debug;}
        var command=new CommandLine(root);configure(command,root);command.setOut(out).setErr(err);
        command.setExecutionStrategy(parse->{try{root.validate();}catch(IllegalArgumentException invalid){return error(root,2,"INVALID_INPUT","Invalid language, color, width or query limit.","Ngôn ngữ, màu, chiều rộng hoặc giới hạn không hợp lệ.");}
            if(parse.subcommand()==null&&parse.isUsageHelpRequested()){help(command,root);return 0;}
            if(parse.subcommand()==null&&parse.isVersionHelpRequested()&&root.json)return emit(root,"version",envelope(Map.of("version",VERSION)),0);
            return new RunLast().execute(parse);});
        command.setParameterExceptionHandler((failure,args)->error(root,2,"INVALID_USAGE","Invalid command or option. Run evo help.","Lệnh hoặc tùy chọn không hợp lệ. Chạy evo help."));
        command.setExecutionExceptionHandler((failure,cmd,parse)->{
            if(root.debug){err.println("[DEBUG] stage="+root.stage+" exception="+failure.getClass().getName());if(failure.getCause()!=null)err.println("  cause="+failure.getCause().getClass().getName());for(var frame:failure.getStackTrace())if(frame.getClassName().startsWith("com.evolution."))err.println("  "+frame.getClassName()+"."+frame.getMethodName()+":"+frame.getLineNumber());}
            boolean invalid=failure instanceof IllegalArgumentException||failure instanceof java.nio.charset.CharacterCodingException;
            if(failure instanceof InputFailure f)return inputError(root,f.getMessage());
            if(failure instanceof GraphStoreException f)return error(root,1,"SNAPSHOT_"+f.reason(),"Snapshot storage failed ("+f.reason()+"). The previous selection is preserved. Check storage permissions and graph limits.","Không lưu được snapshot ("+f.reason()+"). Lựa chọn trước được giữ lại. Kiểm tra quyền ghi và giới hạn đồ thị.");
            if(failure instanceof GraphJson.LimitExceededException)return error(root,3,"GRAPH_LIMIT","Graph/evidence exceeds the declared structural limit. Narrow the scan or query scope.","Đồ thị/bằng chứng vượt giới hạn cấu trúc. Thu hẹp phạm vi quét hoặc truy vấn.");
            if(failure instanceof FileAlreadyExistsException)return error(root,1,"OUTPUT_EXISTS","Output already exists. Choose another --out file.","Tệp đầu ra đã tồn tại. Chọn tệp --out khác.");
            if(failure instanceof BoundedJson.TooLarge)return error(root,3,"OUTPUT_LIMIT","Canonical output exceeds the limit. Narrow the query or reduce --limit.","Đầu ra canonical vượt giới hạn. Thu hẹp truy vấn hoặc giảm --limit.");
            return error(root,invalid?2:1,invalid?"INVALID_INPUT":"OPERATION_FAILED","Check selected inputs, context, paths and limits. Run evo help.","Kiểm tra đầu vào, ngữ cảnh, đường dẫn và giới hạn. Chạy evo help.");});
        try{return command.execute(arguments);}
        catch(OutOfMemoryError limit){if(root.debug)err.println("[DEBUG] stage="+root.stage);return error(root,3,"MEMORY_LIMIT","Memory limit reached. Narrow the scan scope or launch the standalone JAR with a larger heap.","Đã chạm giới hạn bộ nhớ. Thu hẹp phạm vi quét hoặc chạy JAR với heap lớn hơn.");}
        finally{out.flush();err.flush();}
    }
    private void configure(CommandLine command,Root r) {
        command.setColorScheme(new Help.ColorScheme.Builder(Help.Ansi.OFF).build());
        command.getCommandSpec().usageMessage().description("Architecture intelligence / Trí tuệ kiến trúc phần mềm")
                .footer("evo scan .  ->  evo show  ->  evo find Controller  ->  evo impact <id>","--json: canonical | --lang vi: Tiếng Việt | --color always: RGB",
                        "Exit: 0 complete, 1 operational, 2 input, 3 incomplete, 4 policy threshold, 130 cancelled");
        command.addSubcommand("help",CommandSpec.wrapWithoutInspection((Callable<Integer>)()->{help(command,r);return 0;}).name("help"));
        command.addSubcommand("scan",new Scan(r));
        command.addSubcommand("acquire",new Acquire(r));
        command.addSubcommand("inventory",new Inventory(r));
        command.addSubcommand("readiness",new Readiness(r));
        String[][] commands={{"show","summary","Snapshot overview / Tổng quan"},{"find","entities","Search symbols / Tìm symbol"},{"deps","dependencies","Dependencies / Phụ thuộc"},
                {"impact","impact","Potential structural impact / Ảnh hưởng cấu trúc tiềm năng"},{"paths","paths","Feasible paths / Đường đi khả thi"},{"cycles","cycles","Feasible cycles / Chu trình khả thi"},
                {"reach","reachability","Bounded reachability / Khả năng đi tới có giới hạn"},{"evidence","evidence","Exact evidence / Bằng chứng chính xác"},{"explain","explain","Entity evidence / Bằng chứng thực thể"},
                {"gaps","gaps","Explicit limitations / Hạn chế tường minh"},{"spring","spring","Spring inventory / Danh mục Spring"},{"config","configurations","Configurations & witnesses / Cấu hình & nhân chứng"},
                {"provenance","provenance","Analysis provenance / Nguồn gốc phân tích"},{"metrics","metric-catalog","Metric catalog or one metric / Danh mục hoặc một metric"},{"query","","All canonical operations / Mọi thao tác canonical"},
                {"check","rules","Check rules / Kiểm tra quy tắc"},{"score","assessment","Explain confidence / Giải thích độ tin cậy"},{"findings","findings","Observed findings / Phát hiện quan sát được"},
                {"rules","rules","Closed rule denominator / Toàn bộ mẫu số luật"},{"bundle","bundle","Finding evidence bundle / Gói bằng chứng"},{"witness","bundle","Witness replay inputs / Đầu vào tái hiện nhân chứng"},{"replay","replay","Re-evaluate bundle formula / Đánh giá lại công thức"},
                {"visual","visual-graph","Visual workbench graph projection / Phép chiếu đồ thị trực quan"}};
        for(var row:commands){var child=new CommandLine(new Query(r,row[0],row[1]));child.getCommandSpec().name(row[0]).usageMessage().description(row[2]);command.addSubcommand(row[0],child);}
        command.addSubcommand("open",new Open(r));command.addSubcommand("use",new Use(r));command.addSubcommand("export",new Export(r));
        command.addSubcommand("next",new Next(r));
        command.addSubcommand("completion",new Completion(command,r));
    }
    @Command(name="completion",mixinStandardHelpOptions=true,description="Generate shell completion / Tạo script hoàn thành lệnh")
    private final class Completion implements Callable<Integer>{
        final CommandLine root;final Root options;Completion(CommandLine root,Root options){this.root=root;this.options=options;}
        @Parameters(arity="0..1",defaultValue="powershell",paramLabel="powershell|bash") String shell;
        public Integer call(){
            if(options.json)throw new InputFailure("SCRIPT_OUTPUT_REQUIRED");
            if(shell.equals("bash")){out.print(AutoComplete.bash("evo",root));return 0;}
            if(!shell.equals("powershell"))throw new InputFailure("INVALID_SHELL");
            var commands=new TreeSet<>(root.getSubcommands().keySet());var options=new TreeSet<String>();
            root.getCommandSpec().options().forEach(o->options.addAll(List.of(o.names())));
            root.getSubcommands().values().forEach(c->c.getCommandSpec().options().forEach(o->options.addAll(List.of(o.names()))));
            out.println("Register-ArgumentCompleter -Native -CommandName evo,evo.cmd,evo.ps1 -ScriptBlock {");
            out.println("    param($wordToComplete, $commandAst, $cursorPosition)");
            out.println("    $evoCommands = @("+commands.stream().map(s->"'"+s+"'").collect(java.util.stream.Collectors.joining(","))+")");
            out.println("    $evoOptions = @("+options.stream().map(s->"'"+s+"'").collect(java.util.stream.Collectors.joining(","))+")");
            out.println("    $evoCandidates = if ($wordToComplete.StartsWith('-') -or $commandAst.CommandElements.Count -gt 2 -or ($commandAst.CommandElements.Count -eq 2 -and $wordToComplete -eq '')) { $evoOptions } else { $evoCommands }");
            out.println("    $evoCandidates | Where-Object { $_.StartsWith($wordToComplete, [StringComparison]::OrdinalIgnoreCase) } | ForEach-Object { [System.Management.Automation.CompletionResult]::new($_, $_, 'ParameterValue', $_) }");
            out.println("}");return 0;
        }
    }
    @Command(mixinStandardHelpOptions=true)
    private final class Query implements Callable<Integer> {
        final Root r;final String name,operation;
        @Parameters(arity="0..3",paramLabel="VALUE",description="Symbol ID, search, metric or operation / ID, tìm kiếm, metric hoặc thao tác") List<String> values=new ArrayList<>();
        @Option(names="--fail-on",paramLabel="none|info|warning|error",description="CI policy threshold / Ngưỡng chính sách CI") String failOn="none";
        Query(Root r,String name,String operation){this.r=r;this.name=name;this.operation=operation;}
        public Integer call() throws Exception {
            r.validate();var graph=load(r);String context=context(r,graph,false);
            if(name.equals("show")&&context==null)return emit(r,name,discovery(graph),0);
            if(context==null)throw new InputFailure("CONTEXT_REQUIRED");
            String op=operation;var operands=new ArrayList<>(values);
            if(name.equals("query")){if(operands.isEmpty())throw new InputFailure("OPERATION_REQUIRED");op=operands.removeFirst();}
            if(name.equals("metrics")&&!operands.isEmpty())op="metric";
            if(name.equals("impact")&&r.policy!=null)op="policy-impact";
            if(!Set.of("none","info","warning","error").contains(failOn))throw new InputFailure("INVALID_THRESHOLD");
            var store=new InMemoryGraphStore();store.save(graph);if(r.other!=null)store.save(Workspace.graph(resolve(r.other)));
            var reports=new PublishedReports();PublishedReports.Report report=null;byte[] policyToRemember=null;
            boolean isReportOp=(ArchitectureQueryApi.REPORT_OPERATIONS.contains(op)&&!op.equals("visual-graph"))||op.equals("replay")||(op.equals("visual-graph")&&r.policy!=null);
            if(!failOn.equals("none")&&!isReportOp)throw new InputFailure("THRESHOLD_NOT_APPLICABLE");
            if(isReportOp){
                Path policyFile=r.policy;if(name.equals("check")&&!operands.isEmpty())policyFile=Path.of(operands.removeFirst());
                byte[] policyInput=policyBytes(r,policyFile);report=evaluate(r,graph,context,policyInput);reports.publish(report);
                if(name.equals("check")&&policyFile!=null)policyToRemember=policyInput;
            }
            if(op.equals("replay")&&!(name.equals("query")&&r.request!=null)){
                if(operands.size()!=1)throw new InputFailure("BUNDLE_REQUIRED");String id=operands.getFirst();
                var bundle=report.evaluation().findings().stream().map(PolicyEvaluation.Finding::bundle).filter(b->b.identity().value().equals(id)).findFirst().orElseThrow(()->new InputFailure("BUNDLE_UNAVAILABLE"));
                var replayed=new ArchitectureMetricsService(graph).queries().ruleCondition(bundle.request(),bundle.predicate());return emit(r,name,envelope(replayed),status(replayed));
            }
            var body=body(r,graph,context,op,operands,isReportOp);String path="/api/v1/graphs/"+graph.identity().value();
            path+=report==null?"/queries/"+op:"/reports/"+report.identity().value()+"/"+op;
            return queryResponse(r,name,op,graph,store,reports,report,failOn,path,body,policyToRemember);
        }
    }
    private int queryResponse(Root r,String name,String op,KnowledgeGraph graph,InMemoryGraphStore store,PublishedReports reports,
                              PublishedReports.Report report,String failOn,String path,byte[] body,byte[] policyToRemember)throws IOException{
            var response=new ArchitectureQueryApi(store,reports,ApiLimits.defaults()).handle("POST",path,"application/json",body,()->Thread.currentThread().isInterrupted());
            var tree=GraphJson.object(GraphJson.read(Workspace.utf8(response.body()),GraphJson.Limits.defaults()));
            int code=response.status()==200?status(tree.get("data")):response.status()<500?2:1;
            if(!r.json&&report!=null&&op.equals("bundle")&&response.status()==200)r.activeRequest=QueryJson.request(graph.identity(),GraphJson.object(tree.get("data")).get("request"));
            if(response.status()==200&&policyToRemember!=null)r.storage().policy(policyToRemember);
            if(response.status()==200&&Set.of("entities","gaps","spring","configurations").contains(op)&&!r.json&&r.activeRequest!=null){var items=GraphJson.array(GraphJson.object(tree.get("data")).get("items"));r.storage().references(r.activeRequest,items.stream().map(v->GraphJson.string(GraphJson.object(v).get("identity"))).toList());}
            if(code==0&&report!=null&&!failOn.equals("none")&&report.evaluation().findings().stream().anyMatch(f->severity(f.rule().severity().name())>=severity(failOn)))code=4;
            if(!r.json&&r.activeRequest!=null&&response.status()==200){r.labels=displayLabels(graph,r.activeRequest,tree);r.descriptors=displayDescriptors(graph,r.activeRequest,tree);}
            if(interactiveDefaults!=null&&!r.json&&response.status()==200&&GraphJson.object(tree.get("data")).get("continuation") instanceof String cursor){
                var next=new TreeMap<>(GraphJson.object(GraphJson.read(Workspace.utf8(body),GraphJson.Limits.defaults())));
                var page=new TreeMap<>(GraphJson.object(next.get("page")));page.put("cursor",cursor);next.put("page",page);
                continuation=new Continuation(r,name,op,graph,store,reports,report,failOn,path,CanonicalJson.write(next).getBytes(StandardCharsets.UTF_8));
            }
            return emit(r,name,tree,code);
    }
    @Command(name="next",mixinStandardHelpOptions=true,description="Next page in the palette / Trang tiếp trong palette")
    private final class Next implements Callable<Integer>{
        final Root r;Next(Root r){this.r=r;}
        public Integer call()throws Exception{
            var next=continuation;continuation=null;
            if(next==null) return error(r,2,"NEXT_UNAVAILABLE","No next page in this palette. Run a paginated query first.","Chưa có trang tiếp trong palette. Chạy truy vấn phân trang trước.");
            if(matched(r,"--workspace","--graph","--context","--request","--args","--other","--policy","--scope","--view","--world","--assignment","--incoming","--kinds","--limit","--depth","--cursor"))throw new InputFailure("CONFLICTING_REQUEST");
            var options=next.options();options.lang=r.lang;options.color=r.color;options.json=r.json;
            if(matched(r,"--width"))options.width=r.width;if(matched(r,"--details"))options.details=r.details;
            if(matched(r,"--ascii"))options.ascii=r.ascii;
            return queryResponse(options,next.name(),next.operation(),next.graph(),next.store(),next.reports(),next.report(),next.failOn(),next.path(),next.body(),null);
        }
    }
    private static int severity(String value){return switch(value.toUpperCase(Locale.ROOT)){case "INFO"->1;case "WARNING"->2;case "ERROR"->3;default->0;};}
    private byte[] body(Root r,KnowledgeGraph graph,String context,String op,List<String> operands,boolean isReportOp)throws IOException{
        if(r.request!=null){if(!operands.isEmpty()||matched(r,"--args","--scope","--view","--world","--assignment","--incoming","--kinds","--limit","--depth","--cursor"))throw new InputFailure("CONFLICTING_REQUEST");byte[] bytes=Workspace.read(resolve(r.request),65_536);
            if(!isReportOp)r.activeRequest=QueryJson.request(graph.identity(),GraphJson.object(GraphJson.read(Workspace.utf8(bytes),GraphJson.Limits.defaults())).get("request"));return bytes;}
        Map<String,Object> args=new TreeMap<>();
        if(r.arguments!=null){if(!operands.isEmpty())throw new InputFailure("CONFLICTING_ARGUMENTS");args.putAll(GraphJson.object(GraphJson.read(r.arguments,new GraphJson.Limits(65_536,32,10_000))));}
        else {
            int required=0;
            switch(op){
                case "entities"->{if(operands.size()>1)throw new InputFailure("INVALID_ARGUMENTS");args.put("search",operands.isEmpty()?"":operands.getFirst());required=operands.size();}
                case "entity","dependencies","expand","impact","policy-impact","reachability","explain","finding","bundle","children"->{required=1;if(operands.size()==1)args.put("identity",operands.getFirst());}
                case "metric-definition"->{required=1;if(operands.size()==1)args.put("metric",operands.getFirst());}
                case "evidence"->{required=1;if(operands.size()==1)args.put("reference",operands.getFirst());}
                case "paths","shortest-path"->{required=2;if(operands.size()==2){args.put("source",operands.get(0));args.put("target",operands.get(1));}}
                case "metric"->{required=operands.size();if(operands.isEmpty()||operands.size()>2)throw new InputFailure("METRIC_REQUIRED");args.put("metric",operands.getFirst());args.put("subject",operands.size()==2?operands.get(1):context);}
                case "spring"->args.put("kinds",List.of("BEAN_PRODUCER","BEAN_DEFINITION_CANDIDATE","INJECTION_USE","ENDPOINT"));
                case "visual-graph"->{if(operands.size()>1)throw new InputFailure("INVALID_ARGUMENTS");if(operands.size()==1){args.put("seed",operands.getFirst());args.put("depth",r.depth);}required=operands.size();}
                default->{}
            }
            if(operands.size()!=required)throw new InputFailure("INVALID_ARGUMENTS");
        }
        var data=new TreeMap<String,Object>();data.put("arguments",args);
        Scope scope=Scope.valueOf(r.scope.toUpperCase(Locale.ROOT).replace('-','_'));
        Projection projection=switch(r.projection){case "union"->Projection.BOUNDED_UNION;case "unknown"->Projection.UNKNOWN_OVERLAY;default->Projection.valueOf(r.projection.toUpperCase(Locale.ROOT));};
        if(r.world!=null&&r.assignment!=null)throw new InputFailure("ONE_REALIZATION_REQUIRED");Optional<Realization> realization=Optional.empty();
        if(r.world!=null)realization=Optional.of(new Realization(Optional.of(r.world),Map.of()));
        if(r.assignment!=null){var assignment=new TreeMap<String,Boolean>();GraphJson.object(Workspace.json(resolve(r.assignment),65_536)).forEach((k,v)->{if(!(v instanceof Boolean b))throw new IllegalArgumentException();assignment.put(k,b);});realization=Optional.of(new Realization(Optional.empty(),assignment));}
        int queryDepth=r.depth;
        if(op.equals("visual-graph")&&args.containsKey("seed")){
            if(r.depth==20)throw new InputFailure("VISUAL_DEPTH_REQUIRES_1_TO_19_STEPS");
            queryDepth=r.depth+1;
        }
        var defaults=Limits.defaults();r.activeRequest=new Request(graph.identity(),context,scope,projection,op.equals("impact")||op.equals("policy-impact")||r.incoming?Direction.INCOMING:Direction.OUTGOING,r.kinds,Population.all(),false,realization,
                new Limits(queryDepth,defaults.maxSteps(),r.limit,defaults.maxVertices(),defaults.maxEdges(),defaults.maxSolverSteps(),defaults.maxFormulaNodes(),defaults.maxAuxiliaryStates()));
        if(!isReportOp)data.put("request",r.activeRequest);
        if(Set.of("entities","edges","dependencies","relationships","explain","gaps","spring","configurations","artifacts","metric-catalog","rules","findings","children","realizations").contains(op))data.put("page",new Page(r.limit,Optional.ofNullable(r.cursor)));
        else if(r.cursor!=null)throw new InputFailure("PAGE_NOT_APPLICABLE");
        if(r.arguments==null&&r.activeRequest!=null){
            if(Set.of("entity","dependencies","expand","impact","policy-impact","reachability","explain","children").contains(op)&&args.containsKey("identity"))args.put("identity",resolveEntity(r,graph,r.activeRequest,GraphJson.string(args.get("identity"))));
            if(Set.of("paths","shortest-path").contains(op))for(String key:List.of("source","target"))args.put(key,resolveEntity(r,graph,r.activeRequest,GraphJson.string(args.get(key))));
            if(op.equals("visual-graph")&&args.containsKey("seed"))args.put("seed",resolveEntity(r,graph,r.activeRequest,GraphJson.string(args.get("seed"))));
        }
        return CanonicalJson.write(data).getBytes(StandardCharsets.UTF_8);
    }
    private String resolveEntity(Root r,KnowledgeGraph graph,Request request,String input)throws IOException{
        if(input.matches("(?:[a-z][a-z0-9.-]*:)?sha256:[0-9a-f]{64}"))return input;
        if(input.matches("[1-9][0-9]{0,2}"))return r.storage().reference(request,Integer.parseInt(input)).orElseThrow(()->new InputFailure("REFERENCE_UNAVAILABLE"));
        boolean prefix=input.matches("[0-9a-f]{8,64}");
        var matches=new ArchitectureMetricsService(graph).queries().entities(request,prefix?"":input,new Page(50,Optional.empty()));
        if(matches.exactTotal().isEmpty()||matches.continuation().isPresent()||matches.items().isEmpty())throw new InputFailure("SYMBOL_NOT_UNIQUE");
        if(prefix){var candidates=matches.items().stream().filter(e->e.identity().substring(e.identity().lastIndexOf(':')+1).startsWith(input)).toList();
            if(candidates.size()==1)return candidates.getFirst().identity();throw new InputFailure("SYMBOL_NOT_UNIQUE");}
        var exact=matches.items().stream().filter(e->e.canonicalName().filter(input::equals).isPresent()||e.qualifiedName().filter(input::equals).isPresent()).toList();
        if(exact.size()==1)return exact.getFirst().identity();
        if(matches.exactTotal().orElseThrow()==1)return matches.items().getFirst().identity();
        throw new InputFailure("SYMBOL_NOT_UNIQUE");
    }
    private byte[] policyBytes(Root r,Path policyFile)throws IOException{
        byte[] bytes;
        try{bytes=policyFile!=null?Workspace.read(resolve(policyFile),262_144):r.storage().policy();}
        catch(NoSuchFileException missing){throw new InputFailure("POLICY_REQUIRED");}
        catch(IOException missing){if(policyFile==null&&!Files.exists(r.storage().root.resolve("policy.txt")))throw new InputFailure("POLICY_REQUIRED");throw missing;}
        return bytes;
    }
    private PublishedReports.Report evaluate(Root r,KnowledgeGraph graph,String context,Path policyFile)throws IOException{return evaluate(r,graph,context,policyBytes(r,policyFile));}
    private PublishedReports.Report evaluate(Root r,KnowledgeGraph graph,String context,byte[] bytes){
        var compiled=new PolicyCompiler().compile(bytes,EvidenceContext.forSnapshot(graph.snapshot()),PolicyCompiler.Limits.defaults());var queries=new ArchitectureMetricsService(graph).queries();
        var plan=new PolicyGraphMapper().bind(compiled,queries,context,PolicyGraphMapper.Limits.defaults());var evaluation=new PolicyEvaluator().evaluate(compiled,plan,queries,PolicyEvaluation.Limits.defaults());
        var metrics=new PolicyMetricsService().compute(compiled,plan,evaluation,queries,PolicyMetrics.Limits.defaults());
        var score=new ArchitectureAssessmentService().evaluate(metrics,evaluation,queries,Optional.of(ArchitectureScoreModel.candidate(Scope.valueOf(r.scope.toUpperCase(Locale.ROOT).replace('-','_')))),List.of(),ArchitectureAssessment.Limits.defaults());
        return new PublishedReports.Report(evaluation,Optional.of(metrics),Optional.of(score));
    }
    @Command(name="acquire",mixinStandardHelpOptions=true,description="Durable full snapshot acquisition / Thu nhận toàn snapshot có phục hồi")
    private final class Acquire implements Callable<Integer>{
        final Root r;Acquire(Root r){this.r=r;}
        @Parameters(paramLabel="DIR|HTTPS_URL|ZIP")String source;
        @Option(names="--repo")String repository;
        @Option(names="--resume",description="Continue the exact acquisition revision")String resume;
        @Option(names="--ref",defaultValue="HEAD")String ref;
        @Option(names="--exclude")List<String> excludes=new ArrayList<>();
        public Integer call()throws Exception{
            r.validate();String revision=resume==null?UUID.randomUUID().toString():resume;
            if(!revision.matches("[A-Za-z0-9._-]{1,128}"))throw new InputFailure("INVALID_ACQUISITION_REVISION");
            Path state=r.storage().root.resolve("acquisition");boolean remote=source.contains("://");Path local=remote?null:Workspace.safe(resolve(Path.of(source)));
            String coordinate=repository!=null?repository:remote?source:local.toUri().toASCIIString();var repo=RepositoryIdentity.fromCanonicalCoordinate(coordinate);
            var excluded=new TreeSet<>(excludes);if(!remote){excluded.add(".git");if(state.startsWith(local)&&!state.equals(local))excluded.add(local.relativize(state).toString().replace('\\','/'));}
            var request=new StreamingAcquisitionRequest(repo,Optional.empty(),!remote,List.copyOf(excluded));
            try(var intake=new RepositoryIntake(state)){
                StreamingRepositoryAcquirer.Receipt receipt;
                if(remote)receipt=intake.git(java.net.URI.create(source),ref,revision,request,new GitHostPolicy(),"git",()->Thread.currentThread().isInterrupted());
                else if(Files.isRegularFile(local,LinkOption.NOFOLLOW_LINKS))receipt=intake.archive(local,revision,request,()->Thread.currentThread().isInterrupted());
                else receipt=intake.local(local,revision,request,()->Thread.currentThread().isInterrupted());
                return emit(r,"acquire",envelope(Map.of("acquisition",receipt,"selection",request,"semanticCoverage","NOT_EVALUATED")),receipt.manifest().isPresent()?0:3);
            }
        }
    }
    @Command(name="inventory",mixinStandardHelpOptions=true,description="Page captured inventory / Xem inventory đã thu nhận")
    private final class Inventory implements Callable<Integer>{final Root r;Inventory(Root r){this.r=r;}@Parameters(paramLabel="REVISION")String revision;
        public Integer call()throws Exception{r.validate();try(var intake=new RepositoryIntake(r.storage().root.resolve("acquisition"))){
            var job=intake.journal().get(revision).orElseThrow(()->new InputFailure("ACQUISITION_NOT_FOUND"));
            return emit(r,"inventory",envelope(Map.of("revision",revision,"state",job.state(),"manifest",intake.inventory().manifest(revision),"page",intake.inventory().page(revision,r.limit,Optional.ofNullable(r.cursor)))),0);
        }}
    }
    @Command(name="readiness",mixinStandardHelpOptions=true,description="Plan all build roots, contexts and native evidence requirements / Lập kế hoạch readiness")
    private final class Readiness implements Callable<Integer>{
        final Root r;Readiness(Root r){this.r=r;}
        @Parameters(paramLabel="REVISION")String revision;
        @Option(names="--section",defaultValue="requirements")String section;
        @Option(names="--source-set")List<String> sourceSets=new ArrayList<>();
        @Option(names="--variant")List<String> variants=new ArrayList<>();
        public Integer call()throws Exception{
            r.validate();var requested=new TreeMap<String,List<String>>();if(!sourceSets.isEmpty())requested.put("sourceSet",sourceSets);if(!variants.isEmpty())requested.put("variant",variants);
            var policy=new ReadinessPlanner.Policy(requested,true,1_000_000);
            try(var intake=new RepositoryIntake(r.storage().root.resolve("acquisition"))){return emit(r,"readiness",envelope(intake.readinessPage(revision,section,r.limit,Optional.ofNullable(r.cursor),policy)),0);}
        }
    }
    @Command(name="scan",mixinStandardHelpOptions=true,description="Passive repository analysis / Phân tích repository thụ động")
    private final class Scan implements Callable<Integer>{
        final Root r;Scan(Root r){this.r=r;}
        @Parameters(arity="0..1",defaultValue=".",paramLabel="DIR") Path source;
        @Option(names="--repo",paramLabel="COORDINATE",description="Stable repository coordinate / Định danh repository") String repository;
        @Option(names="--jdk",paramLabel="DIR",description="Analyzed JDK; default captured host JDK / JDK phân tích") Path jdk=Path.of(System.getProperty("java.home"));
        @Option(names="--release",paramLabel="N",description="Explicit target release; default 21 / Java đích") int release=21;
        @Option(names="--revision",paramLabel="LABEL",description="Caller-supplied revision label / Nhãn revision do người gọi cung cấp") String revision;
        @Option(names="--dirty",description="Caller-supplied dirty flag; no VCS inspection / Cờ dirty do người gọi cung cấp") boolean dirty;
        @Option(names="--exclude",paramLabel="PATH",description="Additional recorded exclusion; repeatable / Loại trừ có ghi nhận") List<String> excludes=new ArrayList<>();
        @Option(names="--include-tests",description="Include test source trees / Gồm mã nguồn kiểm thử") boolean includeTests;
        @Option(names="--all-files",description="Capture all files, including assets and outputs / Thu thập mọi tệp") boolean allFiles;
        @Option(names="--maven-cache",paramLabel="DIR",description="Passive Maven2 cache / Cache Maven2 chỉ đọc") Path mavenCache=Path.of(System.getProperty("user.home"),".m2","repository");
        @Option(names="--artifact-repository",paramLabel="HTTPS_URI",description="Explicit credential-free dependency repository; repeatable") List<java.net.URI> artifactRepositories=new ArrayList<>();
        public Integer call()throws Exception{
            r.validate();if(matched(r,"--graph","--context","--request","--args","--other","--policy","--view","--scope","--world","--assignment","--incoming","--kinds","--limit","--depth","--cursor"))throw new InputFailure("QUERY_FLAGS_NOT_APPLICABLE");
            sessionGraph=null;
            var sourcePath=Workspace.safe(resolve(source));var repo=RepositoryIdentity.fromCanonicalCoordinate(repository!=null?repository:"urn:evo:local:"+ContentDigest.sha256Utf8(sourcePath.toString()).value().substring(7));
            var excluded=new TreeSet<>(List.of(".git",".evo"));excluded.addAll(excludes);Path session=r.storage().root;
            if(session.startsWith(sourcePath)&&!session.equals(sourcePath))excluded.add(sourcePath.relativize(session).toString().replace('\\','/'));
            var selection=ScanSelection.plan(sourcePath,includeTests,allFiles,List.copyOf(excluded));excluded.addAll(selection.excluded());
            r.stage="acquisition";
            progress(r,"[1/4] Reading selected source/build/configuration files…","[1/4] Đọc mã nguồn, build và cấu hình đã chọn…");
            String intakeRevision=UUID.randomUUID().toString();StreamingRepositoryAcquirer.Receipt captured;
            RepositoryAcquisitionResult acquisition;ReadinessPlan readiness;
            try(var intake=new RepositoryIntake(r.storage().root.resolve("acquisition"))){
                captured=intake.local(sourcePath,intakeRevision,new StreamingAcquisitionRequest(repo,Optional.ofNullable(revision),dirty,List.copyOf(excluded)),()->Thread.currentThread().isInterrupted());
                if(captured.manifest().isEmpty())return emit(r,"scan",envelope(Map.of("status",captured.state(),"acquisition",captured,"graphCreated",false)),3);
                readiness=intake.readiness(intakeRevision,ReadinessPlanner.Policy.comprehensive(),()->Thread.currentThread().isInterrupted());
                try{acquisition=intake.compatibility(intakeRevision,repo,Optional.ofNullable(revision),dirty,includeTests,allFiles,List.copyOf(excluded));}
                catch(IOException budget){return emit(r,"scan",envelope(Map.of("status","WAITING_FOR_SEMANTIC_WORKER","acquisition",captured,"graphCreated",false,"reason","COMPATIBILITY_VIEW_UNAVAILABLE")),3);}
            }
            var acquisitionInfo=Map.of("identity",acquisition.identity(),"status",acquisition.completion(),"files",acquisition.files().size(),"bytes",acquisition.files().stream().mapToLong(AcquiredFile::size).sum(),"problems",acquisition.problems(),"policy",acquisition.request().policy(),"fullInventory",captured.manifest().orElseThrow(),"view","BOUNDED_SEMANTIC_COMPATIBILITY");
            if(acquisition.snapshot().isEmpty())return emit(r,"scan",envelope(Map.of("status","TERMINAL_FAILURE","stage","acquisition","acquisition",acquisitionInfo,"selection",selection,"graphCreated",false)),1);
            progress(r,"[2/4] Capturing exact JDK symbols…","[2/4] Thu nhận symbol JDK…");
            r.stage="platform";
            var platform=new FilesystemJdkPlatformProvider().acquire(Workspace.safe(resolve(jdk)),new PlatformSymbolRequest(release,128,128_000_000,512_000_000));var defaults=UniversalBuildIngestion.Policy.defaults();
            var cachePath=Workspace.safe(resolve(mavenCache));
            var mavenInputs=MavenScan.inputs(acquisition,MavenScan.models(acquisition,cachePath),cachePath,platform.platform(),new com.evolution.analysis.dependency.DependencyAcquisitionPolicy(1024,64_000_000,256_000_000,16,artifactRepositories,5000,15000,0));
            r.stage="analysis";
            // Supply fallback evidence without Builder.defaultPlatform's Maven user-property overrides.
            // Declared Maven release/encoding must keep their authority over CLI defaults.
            var policy=new UniversalArchitecturePipeline.Policy(
                    new UniversalBuildIngestion.Policy(Optional.of(release),Optional.of(release),Optional.of("UTF-8"),defaults.maxFiles(),defaults.maxCharacters(),defaults.maxTokens(),defaults.maxDepth()),
                    new RepositoryInputDecoder.Policy("UTF-8",10_000,10_000_000),frontend(r),JavaParserFrontend.PROVIDER,
                    mavenInputs.resolutions(),platform.platform(),Optional.of(new MavenBuildModelProvider()),mavenInputs.externalPoms(),MavenScan.POLICY,
                    1000,100,UniversalArchitecturePipeline.TruthEvaluationMode.AUTO,Map.of(),Optional.empty(),Optional.empty(),com.evolution.analysis.spring.truth.TruthRegionEvaluation.Limits.conservative()).withReadiness(readiness.identity());
            progress(r,"[3/4] Analyzing Java / Spring and building the graph…","[3/4] Phân tích Java / Spring và dựng đồ thị…");
            var detailed=new UniversalArchitecturePipeline().runWithEvidence(acquisition,policy);var data=new TreeMap<String,Object>();data.put("status",detailed.report().status());data.put("reportIdentity",detailed.report().identity());data.put("gaps",detailed.report().gaps());
            data.put("acquisition",acquisitionInfo);data.put("selection",selection);data.put("metrics",detailed.report().metrics());
            data.put("dependencies",mavenInputs.summary());
            data.put("readiness",Map.of("identity",readiness.identity(),"selection",readiness.selection().identity(),"roots",readiness.roots().size(),"contexts",readiness.contexts().size(),"requirements",readiness.requirements().size(),"ownership",readiness.ownership()));
            var scanGaps=new TreeSet<>(detailed.report().gaps());scanGaps.addAll(mavenInputs.gaps());scanGaps.addAll(readiness.gaps(acquisition.snapshot().orElseThrow().identity()));data.put("gaps",List.copyOf(scanGaps));
            if(!scanGaps.isEmpty()&&detailed.report().status()==UniversalArchitectureReport.Status.COMPLETE)data.put("status","PARTIAL");
            data.put("issues",detailed.report().issues());data.put("rootCauses",detailed.report().rootCauses());
            data.put("platform",Map.of("status",platform.status(),"identity",platform.identity(),"problems",platform.problems()));
            data.put("inputPolicy",Map.of("release",release,"charsetFallback","UTF-8","dependencyAcquisition",mavenInputs.summary().get("mode"),"excludedPaths",excluded,"repository",repo,
                    "versionControl","NOT_INSPECTED","callerRevision",Optional.ofNullable(revision),"callerDirty",dirty));
            if(detailed.graphInput().isPresent()){
                r.stage="graph";progress(r,"[4/4] Saving a verified snapshot…","[4/4] Lưu snapshot đã kiểm tra…");var input=detailed.graphInput().orElseThrow();
                var graph=new CanonicalGraphBuilder().build(new CanonicalGraphInput(input.repository(),input.build(),input.ingestion(),input.spring(),input.issues(),List.copyOf(scanGaps),input.acquisition()));
                if(r.debug){err.println("[DEBUG] graph nodes="+graph.nodes().size()+" edges="+graph.edges().size()+" evidence="+graph.evidence().size()+" payloadChars="+graph.evidence().stream().mapToLong(e->e.canonicalJson().length()).sum());for(var e:graph.evidence().stream().sorted(Comparator.comparingInt((GraphEvidence e)->e.canonicalJson().length()).reversed()).limit(4).toList())err.println("  "+e.family()+" chars="+e.canonicalJson().length());}
                boolean usable=detailed.report().status()!=UniversalArchitectureReport.Status.TERMINAL_FAILURE;Optional<String> selected=usable?autoContext(graph):Optional.empty();r.stage="storage";
                if(usable)r.storage().select(graph,selected);else r.storage().publish(graph);
                if(usable&&interactiveDefaults!=null){interactiveDefaults.graph=null;interactiveDefaults.context=null;sessionGraph=graph;}
                data.put("graph",graph.identity());data.put("snapshotSelected",usable);data.put("contexts",contextRows(graph));data.put("selectedContext",selected);
                // The receipt is bounded independently of repository size. Every typed gap remains in the verified graph.
                data.remove("gaps");data.put("gapSummary",Map.of("total",scanGaps.size(),"byReason",reasonCounts(scanGaps,g->g.reasonCode()),"records","raw canonical graph","context",graph.snapshot(),"inspect","evo gaps --context "+graph.snapshot().value()+" --json"));
                data.remove("issues");data.put("issueSummary",Map.of("total",detailed.report().issues().size(),"byReason",reasonCounts(detailed.report().issues(),i->i.reason().name())));
                data.remove("rootCauses");data.put("rootCauseSummary",Map.of("total",detailed.report().rootCauses().size(),"byCategory",reasonCounts(detailed.report().rootCauses(),c->c.category().name())));
            }
            r.stage="receipt";
            return emit(r,"scan",envelope(data),detailed.graphInput().isEmpty()||detailed.report().status()==UniversalArchitectureReport.Status.TERMINAL_FAILURE?1:detailed.report().status()==UniversalArchitectureReport.Status.COMPLETE&&platform.platform().isPresent()&&scanGaps.isEmpty()?0:3);
        }
    }
    @Command(name="open",mixinStandardHelpOptions=true,description="Select verified graph snapshot / Chọn snapshot đã kiểm tra")
    private final class Open implements Callable<Integer>{final Root r;Open(Root r){this.r=r;}@Parameters(paramLabel="FILE") Path file;
        public Integer call()throws Exception{r.validate();sessionGraph=null;var graph=Workspace.graph(resolve(file));r.storage().select(graph,autoContext(graph));if(interactiveDefaults!=null){interactiveDefaults.graph=null;interactiveDefaults.context=null;sessionGraph=graph;}return emit(r,"open",discovery(graph),0);}}
    @Command(name="use",mixinStandardHelpOptions=true,description="Remember exact context / Ghi nhớ ngữ cảnh chính xác")
    private final class Use implements Callable<Integer>{final Root r;Use(Root r){this.r=r;}@Parameters(paramLabel="ID|N") String selected;
        public Integer call()throws Exception{r.validate();var graph=load(r);String context=findContext(graph,selected);if(r.graph==null)r.storage().rememberContext(graph,context);else r.storage().select(graph,Optional.of(context));if(interactiveDefaults!=null){interactiveDefaults.context=context;interactiveDefaults.graph=null;sessionGraph=graph;}return emit(r,"use",envelope(Map.of("graph",graph.identity(),"context",context,"selected",selected)),0);}}
    @Command(name="export",mixinStandardHelpOptions=true,description="JSON, SARIF, GraphML or Cytoscape / Xuất báo cáo")
    private final class Export implements Callable<Integer>{final Root r;Export(Root r){this.r=r;}
        @Parameters(paramLabel="json|sarif|graphml|cytoscape") String format;
        @Option(names={"-o","--out"},paramLabel="FILE",description="Create new file; otherwise stdout / Tạo tệp mới hoặc stdout") Path target;
        @Option(names="--operation",paramLabel="NAME",description="JSON query operation / Thao tác JSON") String operation="summary";
        public Integer call()throws Exception{
            r.validate();if(!Set.of("json","sarif","graphml","cytoscape").contains(format))throw new InputFailure("INVALID_FORMAT");var graph=load(r);String context=context(r,graph,true);byte[] bytes;int code;
            if(format.equals("sarif")){var report=evaluate(r,graph,context,r.policy);bytes=Exports.sarif(report);code=status(report.evaluation());}
            else{String op=format.equals("graphml")?"edges":format.equals("cytoscape")?"visual-graph":operation;var store=new InMemoryGraphStore();store.save(graph);if(r.other!=null)store.save(Workspace.graph(resolve(r.other)));var reports=new PublishedReports();String path="/api/v1/graphs/"+graph.identity().value();
                boolean isReportOp=ArchitectureQueryApi.REPORT_OPERATIONS.contains(op)&&(!op.equals("visual-graph")||r.policy!=null);
                if(isReportOp){var report=evaluate(r,graph,context,r.policy);reports.publish(report);path+="/reports/"+report.identity().value()+"/"+op;}else path+="/queries/"+op;
                var response=new ArchitectureQueryApi(store,reports,ApiLimits.defaults()).handle("POST",path,"application/json",body(r,graph,context,op,List.of(),isReportOp),()->false);var tree=GraphJson.object(GraphJson.read(Workspace.utf8(response.body()),GraphJson.Limits.defaults()));code=response.status()==200?status(tree.get("data")):2;
                if(response.status()!=200)return emit(r,"export",tree,code);bytes=format.equals("graphml")?Exports.graphml(tree):format.equals("cytoscape")?Exports.cytoscape(tree):response.body();}
            if(bytes.length>32_000_000)throw new InputFailure("OUTPUT_LIMIT");
            if(target==null){out.print(Workspace.utf8(bytes));out.println();}else{Workspace.writeNew(resolve(target),bytes);return emit(r,"export",envelope(Map.of("format",format,"output",target.toString(),"content",ContentDigest.sha256(bytes),"bytes",bytes.length,"status",code==0?"COMPLETE":"PARTIAL")),code);}return code;
        }
    }
    private void progress(Root r,String en,String vi){if(terminal&&!r.json){err.println("[evo] "+(r.lang.equals("vi")?vi:en));err.flush();}}
    private com.evolution.analysis.frontend.SemanticFrontend frontend(Root r){
        var frontend=new JavaParserFrontend();if(!r.debug)return frontend;
        return request->{try{return frontend.analyze(request);}catch(RuntimeException failure){err.println("[DEBUG] frontend="+failure.getClass().getName());for(var frame:failure.getStackTrace())if(frame.getClassName().startsWith("com.evolution."))err.println("  "+frame.getClassName()+"."+frame.getMethodName()+":"+frame.getLineNumber());throw failure;}};
    }
    private static <T> Map<String,Long> reasonCounts(Collection<T> rows,java.util.function.Function<T,String> reason){var counts=new TreeMap<String,Long>();for(T row:rows)counts.merge(reason.apply(row),1L,Long::sum);return counts;}
    private Path resolve(Path path){return path.isAbsolute()?path:directory.resolve(path);}
    private KnowledgeGraph load(Root r)throws IOException{
        if(r.graph!=null)return Workspace.graph(resolve(r.graph));
        if(!Files.exists(r.storage().root.resolve("selection.json"),LinkOption.NOFOLLOW_LINKS))throw new InputFailure("NO_SNAPSHOT");
        var selected=new ContentDigest(GraphJson.string(r.storage().selection().get("graph")));
        if(interactiveDefaults!=null&&sessionGraph!=null&&sessionGraph.identity().equals(selected))return sessionGraph;
        var graph=r.storage().selectedGraph();if(interactiveDefaults!=null)sessionGraph=graph;return graph;
    }
    private Optional<String> autoContext(KnowledgeGraph graph){var candidates=graph.contexts().stream().filter(c->c.analysis().isPresent()).toList();if(candidates.size()==1)return Optional.of(candidates.getFirst().identity());return graph.contexts().size()==1?Optional.of(graph.contexts().getFirst().identity()):Optional.empty();}
    private String context(Root r,KnowledgeGraph graph,boolean required)throws IOException{
        if(r.request!=null){var body=GraphJson.object(Workspace.json(resolve(r.request),65_536));
            if(body.get("request") instanceof Map<?,?> request){String selected=findContext(graph,GraphJson.string(request.get("context")));
                if(r.context!=null&&!findContext(graph,r.context).equals(selected))throw new InputFailure("CONFLICTING_REQUEST");return selected;}}
        if(r.context!=null)return findContext(graph,r.context);if(r.graph==null){Object selected=r.storage().selection().get("context");if(selected instanceof String value)return findContext(graph,value);}
        String selected=autoContext(graph).orElse(null);if(selected==null&&required)throw new InputFailure("CONTEXT_REQUIRED");return selected;
    }
    private boolean matched(Root r,String... options){for(var parse=r.spec.commandLine().getParseResult();parse!=null;parse=parse.subcommand())for(String option:options)if(parse.hasMatchedOption(option))return true;return false;}
    private String findContext(KnowledgeGraph graph,String selected){var contexts=graph.contexts().stream().sorted(Comparator.comparing(KnowledgeGraph.Context::identity)).toList();if(selected.matches("[1-9][0-9]{0,5}")){int i=Integer.parseInt(selected)-1;if(i>=0&&i<contexts.size())return contexts.get(i).identity();}return contexts.stream().map(KnowledgeGraph.Context::identity).filter(selected::equals).findFirst().orElseThrow(()->new InputFailure("CONTEXT_UNAVAILABLE"));}
    private List<Object> contextRows(KnowledgeGraph graph){var rows=new ArrayList<Object>();int n=0;var queries=new ArchitectureMetricsService(graph).queries();
        for(var c:graph.contexts().stream().sorted(Comparator.comparing(KnowledgeGraph.Context::identity)).toList()){
            var request=Request.dependencies(graph.identity(),c.identity());var row=new TreeMap<String,Object>();row.put("index",++n);row.put("identity",c.identity());row.put("analysis",c.analysis());row.put("snapshot",c.snapshot());
            var provenance=queries.provenance(request);String label=c.analysis().isPresent()?"Analysis "+n: "Build / acquisition";
            if(!provenance.items().isEmpty()){
                var metadata=provenance.items().getFirst().metadata();var build=metadata.get("build") instanceof Map<?,?>?GraphJson.object(metadata.get("build")):Map.<String,Object>of();
                var partition=build.get("moduleSourceSet") instanceof Map<?,?>?GraphJson.object(build.get("moduleSourceSet")):metadata;
                if(metadata.get("modulePath") instanceof String path)label=path+" / "+metadata.getOrDefault("sourceSet","?");
                else if(partition.get("module") instanceof String module){var entities=queries.entity(request,module).items();if(!entities.isEmpty())label=entities.getFirst().qualifiedName().orElse(module.substring(Math.max(0,module.length()-12)))+" / "+partition.getOrDefault("sourceSet","?");}
            }
            row.put("label",label);rows.add(row);
        }return rows;}
    private Map<String,Object> discovery(KnowledgeGraph graph){return envelope(Map.of("graph",graph.identity(),"snapshot",graph.snapshot(),"contexts",contextRows(graph),"next","evo use <index>"));}
    static Map<String,Object> envelope(Object data){return Map.of("apiVersion",ArchitectureQueryApi.VERSION,"queryVersion",ArchitectureQuery.VERSION,"resultIdentity",ContentDigest.sha256Utf8(CanonicalJson.write(data)),"data",data);}
    private int emit(Root r,String command,Map<String,Object> envelope,int code){byte[] encoded=BoundedJson.encode(envelope,32_000_000);String json=new String(encoded,StandardCharsets.UTF_8);
        if(r.json)out.println(json);else r.view().response(command,GraphJson.object(GraphJson.read(json,new GraphJson.Limits(32_000_000,128,2_000_000))),r.activeRequest==null?Map.of():Map.of("context",r.activeRequest.context(),"scope",r.activeRequest.scope(),"projection",r.activeRequest.projection().name(),"palette",interactiveDefaults!=null),r.details,r.labels,r.descriptors);return code;}
    private Map<String,List<Map<String,Object>>> displayDescriptors(KnowledgeGraph graph,Request request,Map<String,Object> response){
        var data=GraphJson.object(response.get("data"));if(!(data.get("items") instanceof List<?> items))return Map.of();
        var result=new HashMap<String,List<Map<String,Object>>>();var queries=new ArchitectureMetricsService(graph).queries();
        for(Object item:items){if(!(item instanceof Map<?,?>))continue;var row=GraphJson.object(item);if(!(row.get("kind") instanceof String kind)||Set.of("TYPE","METHOD","FIELD","PACKAGE","MODULE").contains(kind)||!row.containsKey("identity"))continue;
            String id=GraphJson.string(row.get("identity"));var descriptors=queries.explain(request,id,new Page(4,Optional.empty()));
            result.put(id,descriptors.items().stream().map(d->Map.<String,Object>of("family",d.family(),"metadata",d.metadata(),"redacted",d.redacted())).toList());
        }return result;
    }
    private Map<String,String> displayLabels(KnowledgeGraph graph,Request request,Map<String,Object> response){
        var ids=new LinkedHashSet<String>();collectDisplayIds(response.get("data"),ids,0);var labels=new HashMap<String,String>();var queries=new ArchitectureMetricsService(graph).queries();
        for(String id:ids.stream().limit(100).toList()){
            var result=queries.entity(request,id);if(!result.items().isEmpty()){var entity=result.items().getFirst();entity.qualifiedName().or(()->entity.spelling()).ifPresent(name->labels.put(id,name));}
        }return labels;
    }
    private static void collectDisplayIds(Object value,Set<String> ids,int depth){
        if(depth>12||ids.size()>=100)return;
        if(value instanceof Map<?,?> map)for(var e:map.entrySet()){
            String key=String.valueOf(e.getKey());if(Set.of("source","target","module","document").contains(key)&&e.getValue() instanceof String id)ids.add(id);
            else if(Set.of("vertices","path").contains(key)&&e.getValue() instanceof List<?> list){for(Object id:list)if(id instanceof String text)ids.add(text);}
            else if(Set.of("items","edges","entities","result","relationships","sources","span").contains(key))collectDisplayIds(e.getValue(),ids,depth+1);
        }else if(value instanceof List<?> list)for(Object item:list)collectDisplayIds(item,ids,depth+1);
    }
    static int status(Object value){Object normalized=GraphJson.read(CanonicalJson.write(value),GraphJson.Limits.defaults());if(!(normalized instanceof Map<?,?> map))return 0;
        if("CANCELLED".equals(map.get("operation"))||"CANCELLED".equals(map.get("status")))return 130;
        if(Set.of("PARTIAL","WITHHELD","UNKNOWN","TERMINAL_FAILURE").contains(String.valueOf(map.get("status"))))return 3;
        if(map.get("bounds") instanceof Map<?,?> bounds&&(Boolean.TRUE.equals(bounds.get("truncated"))||Boolean.TRUE.equals(bounds.get("workBudgetExceeded"))||Boolean.TRUE.equals(bounds.get("outputBudgetExceeded"))))return 3;
        if(map.get("evaluationStatus")!=null&&!map.get("evaluationStatus").equals("COMPLETE"))return 3;return 0;}
    private int error(Root r,int code,String name,String en,String vi){if(r.json)err.println(CanonicalJson.write(Map.of("cliVersion","architecture.cli:m7.2-v1","error",Map.of("code",name,"message",en))));else err.println("["+name+"] "+(r.lang.equals("vi")?vi:en));return code;}
    private int inputError(Root r,String code){return switch(code){
        case "NO_SNAPSHOT"->error(r,2,code,"No selected snapshot. Run evo scan . or evo open <snapshot.json>.","Chưa chọn snapshot. Chạy evo scan . hoặc evo open <snapshot.json>.");
        case "CONTEXT_REQUIRED","CONTEXT_UNAVAILABLE"->error(r,2,code,"Choose one exact context: evo show, then evo use <index> or --context <id>.","Chọn ngữ cảnh chính xác: evo show, rồi evo use <số> hoặc --context <id>.");
        case "POLICY_REQUIRED"->error(r,2,code,"Supply a policy with evo check <policy.yaml> or --policy <file>.","Cung cấp chính sách bằng evo check <policy.yaml> hoặc --policy <file>.");
        case "SYMBOL_NOT_UNIQUE"->error(r,2,code,"Symbol is missing, ambiguous or search is incomplete. Use evo find <text>, then copy its exact ID.","Symbol chưa có, mơ hồ hoặc tìm kiếm chưa đầy đủ. Dùng evo find <từ khóa>, rồi sao chép ID chính xác.");
        case "REFERENCE_UNAVAILABLE"->error(r,2,code,"Number is unavailable for this graph, context or scope. Run evo find <text> again, then evo deps <number>.","Số chọn không thuộc đồ thị, ngữ cảnh hoặc phạm vi này. Chạy lại evo find <từ khóa>, rồi evo deps <số>.");
        case "CONFLICTING_REQUEST"->error(r,2,code,"Use the exact request file or query flags; remove conflicting overrides.","Dùng tệp yêu cầu chính xác hoặc các cờ truy vấn; bỏ tùy chọn xung đột.");
        default->error(r,2,code,"Check command operands. Run evo <command> --help.","Kiểm tra tham số lệnh. Chạy evo <lệnh> --help.");};}
    private void help(CommandLine command,Root r){if(r.json)out.println(CanonicalJson.write(envelope(Map.of("version",VERSION,"commands",command.getSubcommands().keySet()))));else r.view().guide(false);out.flush();}
    private int defaultWidth(){if(terminalProfile.columns()>0)return Math.max(32,Math.min(160,terminalProfile.columns()));try{return Math.max(32,Math.min(160,Integer.parseInt(environment.getOrDefault("COLUMNS","96"))));}catch(NumberFormatException ignored){return 96;}}
    private boolean colors(String mode){if(!terminalProfile.ansiSafe()||mode.equals("never"))return false;if(mode.equals("always"))return true;return terminal&&!environment.containsKey("NO_COLOR")&&!environment.getOrDefault("TERM","").equals("dumb");}
    private int interact(Root r)throws IOException{sessionLang=r.lang;sessionColor=r.color;sessionAscii=r.ascii;interactiveDefaults=r;r.view().palette();
        while(true){out.print("evo > ");out.flush();String line;
            try{line=readLine();}catch(InputFailure limit){error(r,2,"INPUT_LIMIT","Command too long.","Lệnh quá dài.");continue;}
            if(line==null)return 0;line=line.strip();if(line.isEmpty())continue;
            List<String> words;try{words=new ArrayList<>(ShellWords.parse(line));}catch(IllegalArgumentException invalid){error(r,2,"INVALID_USAGE","Unclosed quote.","Dấu nháy chưa đóng.");continue;}
            if(!words.isEmpty()&&Set.of("evo","evo.cmd",".\\evo.cmd","./evo").contains(words.getFirst()))words.removeFirst();
            if(words.isEmpty()){r.view().palette();continue;}
            if(words.size()==1&&Set.of("quit","exit","q").contains(words.getFirst()))return 0;
            if(words.size()==1&&Set.of("help","?").contains(words.getFirst())){r.view().palette();continue;}
            if(words.size()==2&&words.getFirst().equals("lang")&&Set.of("en","vi").contains(words.get(1))){sessionLang=words.get(1);r.lang=sessionLang;r.view().palette();continue;}
            if(!words.getFirst().equals("next"))continuation=null;
            String[] shortcuts={"scan .","show","find","deps","impact","check","score","export json"};if(words.size()==1&&words.getFirst().matches("[1-8]")){line=shortcuts[Integer.parseInt(words.getFirst())-1];if(Set.of("find","deps","impact","check").contains(line)){out.print(line+" > ");out.flush();String value;try{value=readLine();}catch(InputFailure limit){error(r,2,"INPUT_LIMIT","Input too long.","Đầu vào quá dài.");continue;}if(value==null)return 0;execute(line,value);continue;}words=ShellWords.parse(line);}
            execute(words.toArray(String[]::new));}
    }
    private String readLine()throws IOException{var line=new StringBuilder();boolean exceeded=false;int c;
        while((c=input.read())!=-1&&c!='\n'){if(c=='\r'){input.mark(1);int next=input.read();if(next!='\n'&&next!=-1)input.reset();break;}if(line.length()<8192)line.append((char)c);else exceeded=true;}
        if(exceeded)throw new InputFailure("INPUT_LIMIT");return c==-1&&line.isEmpty()?null:line.toString();
    }
    private static final class InputFailure extends IllegalArgumentException{InputFailure(String code){super(code);}}
}
