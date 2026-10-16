package com.evolution.cli;

import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import java.util.*;

/** Human reports are projections of returned canonical facts, never an alternate analysis. */
final class TerminalReports {
    private final TerminalView v;private final boolean full;private final Map<String,String> labels;
    private final Map<String,List<Map<String,Object>>> descriptors;
    TerminalReports(TerminalView view,boolean full,Map<String,String> labels,Map<String,List<Map<String,Object>>> descriptors){v=view;this.full=full;this.labels=Map.copyOf(labels);this.descriptors=Map.copyOf(descriptors);}
    private String tr(String en,String vi){return v.tr(en,vi);}
    private void line(String text){v.line(text);}
    private void section(String en,String vi){v.heading(tr(en,vi));}
    private static Map<String,Object> map(Object value){return value instanceof Map<?,?>?GraphJson.object(value):Map.of();}
    private static List<Object> list(Object value){return value instanceof List<?>?GraphJson.array(value):List.of();}
    private static String str(Object value){if(value instanceof Map<?,?> map&&map.keySet().equals(Set.of("id","version")))return map.get("id")+":"+map.get("version");return value==null?"—":String.valueOf(value);}
    private static long number(Object value){return value instanceof Number n?n.longValue():0;}
    private static String shortId(Object value){String s=str(value);int colon=s.lastIndexOf(':');return s.matches(".*sha256:[0-9a-f]{64}")?s.substring(colon+1,Math.min(s.length(),colon+13)):s;}
    private String name(Object id){return labels.getOrDefault(str(id),shortId(id));}
    private String arrow(){return v.ascii?" -> ":" → ";}
    private void field(String en,String vi,Object value){line("  "+tr(en,vi)+"  "+str(value));}
    void render(String command,Map<String,Object> envelope,Map<String,Object> selection){
        // Tests and callers may provide records; normalize once so every renderer sees the wire shape.
        envelope=map(GraphJson.read(CanonicalJson.write(envelope),GraphJson.Limits.defaults()));
        v.title(command);
        if(envelope.containsKey("error")){compact(envelope.get("error"),0);return;}
        Map<String,Object> data=map(envelope.get("data"));String status=str(data.getOrDefault("status",data.getOrDefault("evaluationStatus","AVAILABLE")));
        v.tone("["+status+"] "+statusLabel(status),!Set.of("COMPLETE","AVAILABLE","NOT_APPLICABLE").contains(status));
        if(!selection.isEmpty()){
            line("  "+str(selection.get("scope"))+" / "+str(selection.get("projection"))+" / "+tr("context ","ngữ cảnh ")+name(selection.get("context")));
            if("BOUNDED_UNION".equals(selection.get("projection")))line(tr("  Union of observed configurations; paths check joint feasibility.","  Hợp các cấu hình quan sát; đường đi được kiểm tra tính khả thi chung."));
        }
        if(command.equals("scan"))scan(data);
        else if(data.containsKey("contexts")&&!data.containsKey("items"))contexts(data);
        else if(command.equals("score")&&data.containsKey("dimensions"))score(data);
        else if(data.containsKey("request")&&data.containsKey("region")&&data.containsKey("sources"))bundle(data);
        else if(data.containsKey("changedEntity"))policyImpact(data);
        else if(data.containsKey("nodes")&&data.containsKey("edges"))visualGraph(data);
        else if(command.equals("use")){field("Selected context","Ngữ cảnh đã chọn",data.get("selected"));field("Context ID","ID ngữ cảnh",shortId(data.get("context")));line("  "+tr("Next: show / find <text>","Tiếp theo: show / find <từ khóa>"));}
        else if(command.equals("export")){field("File","Tệp",data.get("output"));field("Format","Định dạng",data.get("format"));field("Bytes written","Byte đã ghi",data.get("bytes"));field("Content SHA-256","SHA-256 nội dung",shortId(data.get("content")));}
        else if(data.containsKey("items"))items(command,data);
        else if(data.containsKey("metric"))metric(data);
        else compact(data,0);
        bounds(data);
        gaps(list(data.get("gaps")),command.equals("gaps"));
        if(data.get("continuation")!=null){section("More results","Còn kết quả");
            if(Boolean.TRUE.equals(selection.get("palette")))line("  "+tr("next  ·  continue the same query","next  ·  xem tiếp cùng truy vấn"));
            if(full||!Boolean.TRUE.equals(selection.get("palette"))){line("  "+tr("Continue the same command with:","Tiếp tục cùng lệnh với:"));line("  --cursor "+data.get("continuation"));}}
        if(full){section("Canonical detail","Chi tiết canonical");v.details(data,0);}
        line("");v.tone("  "+tr("Result ","Kết quả ")+shortId(envelope.get("resultIdentity"))+"  ·  --details / --json",false);
    }
    private String statusLabel(String status){return switch(status){
        case "COMPLETE"->tr("Complete within the declared scope","Đầy đủ trong phạm vi đã khai báo");
        case "PARTIAL"->tr("Usable observations; evidence remains incomplete","Có kết quả quan sát; bằng chứng còn thiếu");
        case "WITHHELD"->tr("Insufficient evidence for an assessment","Chưa đủ bằng chứng để đánh giá");
        case "TERMINAL_FAILURE"->tr("Analysis could not produce a usable result","Chưa tạo được kết quả phân tích sử dụng được");
        case "CANCELLED"->tr("Cancelled","Đã hủy");case "UNKNOWN"->tr("Cannot establish a conclusion","Chưa xác định được kết luận");
        case "NOT_APPLICABLE"->tr("Not applicable to this input","Không áp dụng với đầu vào này");default->"";};}
    private void scan(Map<String,Object> d){
        var a=map(d.get("acquisition"));var m=map(d.get("metrics"));var s=map(d.get("selection"));
        section("Observed analysis","Kết quả quan sát");
        field("Selected files / bytes","Tệp đã chọn / byte",str(a.get("files"))+" / "+str(a.get("bytes")));
        if(!m.isEmpty()){
            field("Modules · Java files · types","Module · tệp Java · kiểu",str(m.get("analyzedModules"))+" · "+str(m.get("javaFiles"))+" · "+str(m.get("declaredTypes")));
            field("Dependencies · components · entry points","Phụ thuộc · component · điểm vào",str(m.get("dependenciesCount"))+" · "+str(m.get("evidencedComponents"))+" · "+str(m.get("entryPointsCount")));
        }
        if(!s.isEmpty())field("Selection","Phạm vi thu thập",Boolean.TRUE.equals(s.get("allFiles"))?tr("All files","Mọi tệp"):tr("Source/build/config; tests ","Mã nguồn/build/cấu hình; kiểm thử ")+(Boolean.TRUE.equals(s.get("includeTests"))?tr("included","có"):tr("excluded","không")));
        if(s.containsKey("excluded"))field("Recorded exclusions","Đường dẫn loại trừ có ghi nhận",list(s.get("excluded")).size());
        if(!map(d.get("dependencies")).isEmpty())field("Dependency acquisition","Thu nhận thư viện",map(d.get("dependencies")).get("mode"));
        for(Object problem:list(a.get("problems"))){var p=map(problem);line("  ! "+str(p.getOrDefault("reason",p.get("code")))+"  "+str(p.getOrDefault("path",p.get("subject"))));}
        if("TERMINAL_FAILURE".equals(d.get("status"))){
            field("Stopped at","Dừng ở bước",d.getOrDefault("stage","analysis"));
            line(tr("  No usable analysis was established. Previous selection is preserved.","  Chưa có phân tích sử dụng được. Lựa chọn trước được giữ nguyên."));
            line("  "+tr("Try --exclude <path>; inspect scan --details and gaps.","Dùng --exclude <đường dẫn>; xem scan --details và gaps."));
        }
        if(d.containsKey("graph")){
            if(Boolean.FALSE.equals(d.get("snapshotSelected"))){section("Diagnostic snapshot saved","Đã lưu snapshot chẩn đoán");field("Graph","Đồ thị",shortId(d.get("graph")));line("  "+tr("Fix the reported gaps and scan again; this snapshot was not selected.","Xử lý các nguyên nhân rồi quét lại; snapshot này chưa được chọn."));}
            else{section("Snapshot saved","Đã lưu snapshot");field("Graph","Đồ thị",shortId(d.get("graph")));contexts(d);line("  "+tr("Next: show  /  find <text>  /  gaps","Tiếp theo: show  /  find <từ khóa>  /  gaps"));}
        }
        if(!list(d.get("issues")).isEmpty()){section("Analysis notices","Lưu ý phân tích");group(list(d.get("issues")),"reason");}
        if(!list(d.get("rootCauses")).isEmpty()){section("Root causes","Nguyên nhân");for(Object cause:list(d.get("rootCauses")).stream().limit(8).toList())compact(cause,1);}
        if(number(map(d.get("gapSummary")).get("total"))>0){section("Evidence gaps","Khoảng trống bằng chứng");var summary=map(d.get("gapSummary"));field("Records saved in graph","Bản ghi đã lưu trong đồ thị",summary.get("total"));for(var e:map(summary.get("byReason")).entrySet())line("  "+e.getValue()+"  "+e.getKey());line("  "+tr("Choose Build / acquisition to inspect the full gap inventory.","Chọn Build / thu thập để xem danh mục khoảng trống toàn bộ."));line("  gaps  /  explain <number>  /  --json");}
    }
    private void contexts(Map<String,Object> data){
        var rows=list(data.get("contexts"));if(rows.isEmpty()){compact(data,0);return;}
        section("Analysis contexts","Ngữ cảnh phân tích");
        for(Object row:rows){var r=map(row);String label=r.get("analysis")==null?tr("Build / acquisition","Build / thu thập"):str(r.getOrDefault("label",""));line("  "+str(r.get("index"))+"  "+(label.isBlank()?name(r.get("identity")):label)+"  ·  "+shortId(r.get("identity")));}
        if(data.get("selectedContext")!=null)field("Selected","Đã chọn",name(data.get("selectedContext")));
        else if(data.get("context")!=null)field("Selected","Đã chọn",name(data.get("context")));
        else line("  "+tr("Choose: use <number>. Contexts remain separate.","Chọn: use <số>. Mỗi ngữ cảnh được giữ riêng."));
    }
    private void items(String command,Map<String,Object> d){
        var rows=list(d.get("items"));
        if(d.containsKey("exactTotal"))field("This page / exact total","Trang này / tổng chính xác",rows.size()+" / "+(d.get("exactTotal")==null?tr("unknown","chưa xác định"):str(d.get("exactTotal"))));
        else field("Observed rows","Số dòng quan sát",rows.size());
        if(rows.isEmpty()){line("  "+tr("No rows observed; an incomplete result does not establish absence.","Chưa quan sát thấy dòng nào; kết quả chưa đầy đủ không chứng minh không tồn tại."));return;}
        if(command.equals("impact"))line("  "+tr("Potential structural impact. Arrows show dependency direction, not change propagation.","Ảnh hưởng cấu trúc tiềm năng. Mũi tên chỉ chiều phụ thuộc."));
        if(rows.stream().map(TerminalReports::map).anyMatch(r->r.containsKey("edges")||r.containsKey("source")&&r.containsKey("target")))line("  MUST: "+tr("all modeled configurations","mọi cấu hình trong mô hình")+" · MAY: "+tr("some","một số")+" · UNKNOWN: "+tr("unestablished","chưa xác định"));
        int n=0;for(Object item:rows){var r=map(item);n++;
            if(r.containsKey("nodeCounts")){inventory(r);continue;}
            if(r.containsKey("vertices")&&r.containsKey("edges")){path(r,n);continue;}
            if(r.containsKey("source")&&r.containsKey("target")){edge(r,n);continue;}
            if(r.containsKey("kind")&&r.containsKey("canonicalName")||r.containsKey("qualifiedName")){entity(r,n);continue;}
            if(command.equals("gaps")&&r.containsKey("reasonCode")){gap(r,n);continue;}
            if(r.containsKey("result")&&map(r.get("result")).containsKey("rule")){rule(r,n);continue;}
            if(r.containsKey("metric")&&r.containsKey("meaning")){v.heading("  "+n+"  "+str(r.get("metric")));line("     "+str(r.get("unit"))+" · "+str(r.get("scopes")));line("     "+str(r.get("meaning")));line("     metrics "+map(r.get("metric")).getOrDefault("id",r.get("metric")));continue;}
            if(r.containsKey("metric")){metric(r);continue;}
            if(r.containsKey("metadata")){section(n+"  "+str(r.get("family")),n+"  "+str(r.get("family")));compact(r.get("metadata"),1);if(Boolean.TRUE.equals(r.get("redacted")))line("  "+tr("Sensitive payload withheld","Nội dung nhạy cảm đã được ẩn"));continue;}
            v.heading("  "+n+"  "+str(r.getOrDefault("id",r.getOrDefault("kind",r.getOrDefault("family","")))));compact(item,1);
        }
        if(command.equals("find"))line("  "+tr("Use a row: deps 1  /  impact 1  /  explain 1 (same graph/context/scope).","Chọn một dòng: deps 1  /  impact 1  /  explain 1 (cùng đồ thị/ngữ cảnh/phạm vi)."));
        if(Set.of("gaps","spring","config").contains(command))line("  "+tr("Inspect a row: explain <number>","Xem một dòng: explain <số>"));
    }
    private void inventory(Map<String,Object> r){
        section("Observed inventory","Quan sát danh mục");bars(map(r.get("nodeCounts")));
        section("Observed relationships","Quan sát quan hệ");bars(map(r.get("edgeCounts")));
        line("  "+tr("Counts describe captured facts, not all possible runtime behavior.","Số lượng mô tả sự kiện đã thu nhận, không bao quát mọi hành vi runtime."));
    }
    private void bars(Map<String,Object> counts){
        long max=counts.values().stream().mapToLong(TerminalReports::number).max().orElse(0);
        int size=Math.max(4,Math.min(20,v.width-42));
        for(var e:new TreeMap<>(counts).entrySet()){
            long count=number(e.getValue());int filled=max==0?0:(int)Math.round((double)count/max*size);
            String label=label(e.getKey());
            if(v.width<56){line("  "+label+"  "+count);continue;}
            line("  "+fit(label,23)+"  "+(v.ascii?"#":"━").repeat(filled)+" ".repeat(size-filled)+"  "+count);
        }
    }
    private String label(String value){return switch(value){case "TYPE"->tr("Types","Kiểu");case "MODULE"->"Module";case "PACKAGE"->"Package";case "METHOD"->tr("Methods","Phương thức");case "FIELD"->tr("Fields","Trường");case "SOURCE_DOCUMENT"->tr("Source files","Tệp nguồn");case "graph.depends-on"->tr("Dependencies","Phụ thuộc");default->value;};}
    private static String fit(String value,int width){String clean=TerminalView.clean(value);var b=new StringBuilder();int cells=0;for(int cp:clean.codePoints().toArray()){String s=new String(Character.toChars(cp));int n=TerminalView.cells(s);if(cells+n>width-1){b.append("~");cells++;break;}b.append(s);cells+=n;}return b+" ".repeat(Math.max(0,width-cells));}
    private void entity(Map<String,Object> r,int n){
        var evidence=descriptors.getOrDefault(str(r.get("identity")),List.of());
        if(!evidence.isEmpty()){
            var metadata=map(evidence.getFirst().get("metadata"));Object label=metadata.getOrDefault("reasonCode",metadata.getOrDefault("kind",r.get("kind")));
            v.heading("  "+n+"  "+str(label));line("     "+str(r.get("kind"))+" · id "+shortId(r.get("identity")));
            for(var descriptor:evidence){var meta=map(descriptor.get("metadata"));compact(meta,1);}
            return;
        }
        Object title=r.get("qualifiedName");if(title==null)title=r.get("canonicalName");if(title==null)title=r.get("spelling");if(title==null)title=shortId(r.get("identity"));
        v.heading("  "+n+"  "+title);
        line("     "+str(r.get("kind"))+" · "+str(r.get("origin"))+" · "+str(r.get("sourceSet")));
        if(r.get("module")!=null)line("     "+tr("module ","module ")+name(r.get("module")));
        line("     id "+shortId(r.get("identity"))+"  ·  "+list(r.get("evidence")).size()+tr(" evidence refs"," tham chiếu bằng chứng"));
    }
    private void edge(Map<String,Object> r,int n){
        line("  "+n+"  "+name(r.get("source"))+arrow()+name(r.get("target")));
        line("     ["+str(r.get("classification"))+"] "+String.join(", ",list(r.get("kinds")).stream().map(TerminalReports::str).toList())+" · "+number(r.get("supportCount"))+tr(" supports"," bằng chứng hỗ trợ"));
        if(Boolean.TRUE.equals(r.get("supportsTruncated")))line("     "+tr("Support list truncated","Danh sách hỗ trợ đã bị giới hạn"));
    }
    private void path(Map<String,Object> r,int n){
        v.heading("  "+n+"  "+tr("Path","Đường đi")+"  ["+str(r.get("feasibility"))+"]");
        // Incoming traversal can reverse vertex order. Canonical edge.source/target is authoritative.
        int i=0;var edges=list(r.get("edges"));for(Object item:edges){var e=map(item);line("     "+(v.ascii?"| ":"│ ")+name(e.get("source"))+arrow()+name(e.get("target"))+"  ["+str(e.get("classification"))+"]");i++;}
        if(edges.isEmpty())line("     "+String.join(arrow(),list(r.get("vertices")).stream().map(this::name).toList()));
        var witness=map(r.get("witness"));if(!witness.isEmpty())line("     "+tr("Witness: formula ","Nhân chứng: công thức ")+witness.get("formulaReplayed")+tr(" / semantics "," / ngữ nghĩa ")+witness.get("semanticReplayed"));
        if("UNKNOWN".equals(r.get("feasibility")))line("     "+tr("Joint feasibility is unknown; no confirmed runtime path.","Tính khả thi chung chưa xác định; chưa khẳng định đường đi runtime."));
    }
    private void rule(Map<String,Object> r,int n){
        var result=map(r.get("result"));var rule=map(result.get("rule"));var header=map(rule.getOrDefault("header",rule));
        v.heading("  "+n+"  ["+str(header.get("severity"))+"] "+str(header.get("id")));
        line("     "+str(result.getOrDefault("kind",rule.get("kind")))+"  ["+str(result.getOrDefault("status","OBSERVED"))+" / "+str(result.get("classification"))+"]");
        if(result.containsKey("findings"))field("Observed findings","Phát hiện quan sát",list(result.get("findings")).size());
        if(r.containsKey("bundleIdentity"))line("     bundle "+str(r.get("bundleIdentity")));
        if(!list(result.get("gaps")).isEmpty())line("     "+list(result.get("gaps")).size()+tr(" evidence gaps"," khoảng trống bằng chứng"));
    }
    private void score(Map<String,Object> d){
        section("Assessment & evidence sufficiency","Đánh giá & mức đủ bằng chứng");
        field("Architecture score","Điểm kiến trúc",d.get("score")==null?tr("Withheld — insufficient evidence","Chưa đủ bằng chứng để công bố điểm"):str(d.get("score"))+" / 100");
        var c=map(d.get("confidence"));ratio(tr("Complete rules","Luật đủ bằng chứng"),number(c.get("completeRules")),number(c.get("declaredRules")));
        ratio(tr("Complete metrics","Metric đủ bằng chứng"),number(c.get("completeMetrics")),number(c.get("requiredMetrics")));
        line("  "+tr("Evidence sufficiency is not a probability of correctness.","Mức đủ bằng chứng không phải xác suất chính xác."));
        section("Dimensions","Các chiều đánh giá");
        for(Object row:list(d.get("dimensions"))){var r=map(row);line("  "+str(r.get("id"))+"  ["+str(r.get("status"))+"]  "+tr("weight ","trọng số ")+str(r.get("weight"))+"  ·  "+(r.get("score")==null?tr("withheld","chưa có điểm"):str(r.get("score"))+"/100"));if(r.get("appliedPenalty")!=null)line("     "+tr("Applied penalty ","Mức trừ ")+r.get("appliedPenalty"));}
        for(Object reason:list(d.get("reasons")))line("  ! "+reason);
        for(Object limitation:list(c.get("limitations")))line("  ! "+limitation);
        if(d.get("formula")!=null)field("Formula / calibration","Công thức / hiệu chuẩn",str(d.get("formula"))+" / "+str(d.get("calibration")));
    }
    private void bundle(Map<String,Object> d){
        var rule=map(d.get("rule"));var header=map(rule.getOrDefault("header",rule));var region=map(d.get("region"));
        field("Rule","Quy tắc",str(header.get("id"))+"  ["+str(header.get("severity"))+"]");
        field("Classification","Phân loại",region.get("classification"));
        section("Dependency evidence","Bằng chứng phụ thuộc");int n=0;for(Object relation:list(d.get("relationships")))edge(map(relation),++n);
        section("Source coordinates","Vị trí nguồn");
        for(Object source:list(d.get("sources"))){var span=map(map(source).get("span"));line("  "+name(span.get("document"))+":"+str(span.get("startLine"))+":"+str(span.get("startColumn"))+" – "+str(span.get("endLine"))+":"+str(span.get("endColumn")));}
        section("Witnesses","Nhân chứng");
        for(String key:List.of("witness","counterWitness")){
            var witness=map(region.get(key));if(witness.isEmpty()){line("  "+key+": "+tr("not supplied","chưa có trong kết quả"));continue;}
            line("  "+key+" · "+tr("world ","world ")+shortId(witness.get("world")));
            line("     "+tr("formula ","công thức ")+str(witness.get("formulaReplayed"))+tr(" / semantics "," / ngữ nghĩa ")+str(witness.get("semanticReplayed")));
            if(!map(witness.get("assignment")).isEmpty())compact(witness.get("assignment"),1);
        }
        for(Object limitation:list(d.get("limitations")))line("  ! "+limitation);
        line("  "+tr("Replay checks the recorded formula; it does not execute Spring.","Replay kiểm tra công thức đã ghi; không thực thi Spring."));
    }
    private void ratio(String label,long numerator,long denominator){
        String bar="";if(denominator>0){int n=(int)Math.max(0,Math.min(12,Math.round(12.0*numerator/denominator)));bar="  ["+(v.ascii?"#":"━").repeat(n)+(v.ascii?"-":"·").repeat(12-n)+"]";}
        line("  "+label+"  "+numerator+"/"+denominator+bar);
    }
    private void metric(Map<String,Object> d){
        v.heading("  "+str(d.get("metric"))+"  ["+str(d.get("status"))+"]");
        field("Value","Giá trị",d.get("value"));
        if(d.get("ratio")!=null)compact(d.get("ratio"),1);
        compact(d.get("details"),1);
        for(Object limitation:list(d.get("limitations")))line("  ! "+limitation);
    }
    private void bounds(Map<String,Object> d){
        var b=map(d.get("bounds"));if(b.isEmpty())return;
        boolean stopped=Boolean.TRUE.equals(b.get("truncated"))||Boolean.TRUE.equals(b.get("workBudgetExceeded"))||Boolean.TRUE.equals(b.get("outputBudgetExceeded"));
        if(stopped){section("Query limit reached","Đã chạm giới hạn truy vấn");line("  "+str(b.get("stopReason"))+" · "+tr("steps ","bước ")+str(b.get("stepsUsed"))+" · "+tr("frontier ","biên duyệt ")+list(b.get("frontier")).size());line("  "+tr("Narrow scope or filters; use --depth / --limit within supported bounds.","Thu hẹp phạm vi/bộ lọc; dùng --depth / --limit trong giới hạn hỗ trợ."));}
        if(number(b.get("unknownCandidates"))>0)field("Unknown candidates","Ứng viên chưa xác định",b.get("unknownCandidates"));
        if(number(b.get("rejectedInfeasible"))>0)field("Infeasible paths rejected","Đường đi không khả thi đã loại",b.get("rejectedInfeasible"));
    }
    private void gaps(List<Object> rows,boolean expanded){
        if(rows.isEmpty())return;section("Evidence gaps","Khoảng trống bằng chứng");field("Records in this result","Bản ghi trong kết quả này",rows.size());group(rows,"reasonCode");
        if(expanded)for(int i=0;i<Math.min(20,rows.size());i++)gap(map(rows.get(i)),i+1);
        else line("  "+tr("Inspect: gaps  /  gaps --details. Full records remain in --json.","Xem: gaps  /  gaps --details. --json giữ đầy đủ mọi bản ghi."));
    }
    private void group(List<Object> rows,String field){var counts=new TreeMap<String,Integer>();for(Object item:rows){var r=map(item);counts.merge(str(r.getOrDefault(field,r.getOrDefault("code","UNKNOWN"))),1,Integer::sum);}int n=0;for(var e:counts.entrySet()){if(n++==12){line("  "+tr("More categories in --details / --json","Còn nhóm khác trong --details / --json"));break;}line("  "+e.getValue()+"  "+e.getKey());}}
    private void gap(Map<String,Object> r,int n){
        v.heading("  "+n+"  "+str(r.get("reasonCode")));var subject=map(r.get("subject"));if(!subject.isEmpty())compact(subject,1);
        for(Object span:list(r.get("sourceSpans")))compact(span,1);
        for(Object limitation:list(r.get("limitations")).stream().limit(3).toList())line("     "+limitation);
        if(!list(r.get("evidenceRequirements")).isEmpty())compact(r.get("evidenceRequirements"),1);
    }
    private void compact(Object value,int depth){
        if(value==null)return;
        if(depth>3){line("  "+tr("Nested details: --details","Chi tiết lồng nhau: --details"));return;}
        if(value instanceof Map<?,?> source){int n=0;for(var e:new TreeMap<>(map(source)).entrySet()){
            if(Set.of("status","gaps","bounds","query","evidence","identity","context","resultIdentity").contains(e.getKey()))continue;
            if(n++==10){line("  "+tr("More fields: --details","Còn trường khác: --details"));break;}
            String pad=" ".repeat(2+depth*2);Object item=e.getValue();
            if(item instanceof Map<?,?>||item instanceof List<?>){line(pad+e.getKey()+":");compact(item,depth+1);}
            else line(pad+e.getKey()+"  "+(item instanceof String s&&s.contains("sha256:")?name(s):str(item)));
        }}else if(value instanceof List<?> rows){for(Object row:rows.stream().limit(8).toList())compact(row,depth+1);if(rows.size()>8)line("  "+tr("More rows: --details","Còn dòng khác: --details"));}
        else line(" ".repeat(2+depth*2)+name(value));
    }
    private void policyImpact(Map<String,Object> d){
        section("Policy-qualified structural impact","Ảnh hưởng cấu trúc có điều kiện chính sách");
        field("Changed symbol","Symbol thay đổi",name(d.get("changedEntity")));
        var affected=list(d.getOrDefault("affectedEntities",d.get("directDependents")));
        field("Reachable affected entities","Thực thể ảnh hưởng liên quan",affected.size());
        if(!affected.isEmpty()&&affected.size()<=10)for(Object ent:affected)if(!ent.equals(d.get("changedEntity")))line("    "+arrow()+name(ent));
        if(d.get("worstSeverity")!=null)field("Worst severity","Mức độ nghiêm trọng cao nhất",d.get("worstSeverity"));
        if(d.get("highestQuantifier")!=null)field("Highest quantifier","Lượng từ cao nhất",d.get("highestQuantifier"));

        var rules=list(d.getOrDefault("affectedRules",d.get("rules")));
        section("Affected policy rules","Quy tắc kiến trúc bị ảnh hưởng ("+rules.size()+")");
        if(rules.isEmpty()){line("  "+tr("No policy rules affected by this change.","Không có quy tắc chính sách nào bị ảnh hưởng."));}
        else for(Object rObj:rules){
            var r=map(rObj);
            String ruleId=r.get("ruleId")!=null?str(r.get("ruleId")):str(map(r.get("header")).get("id"));
            String sev=r.get("severity")!=null?str(r.get("severity")):str(map(r.get("header")).get("severity"));
            v.heading("  ["+sev+"] "+ruleId);
            if(r.containsKey("kind"))line("     "+tr("Scope / Kind","Phạm vi / Loại")+": "+str(r.get("kind")));
            line("     "+tr("Direct findings","Phát hiện trực tiếp")+": "+str(r.get("directFindingsCount"))+" · "+tr("Transitive","Gián tiếp")+": "+str(r.get("transitiveFindingsCount")));
        }

        var directFindings=list(d.get("directFindings"));
        var transFindings=list(d.get("transitiveFindings"));
        var allFindings=new ArrayList<Object>();
        allFindings.addAll(directFindings);
        allFindings.addAll(transFindings);
        if(allFindings.isEmpty()&&d.containsKey("findings"))allFindings.addAll(list(d.get("findings")));

        if(!allFindings.isEmpty()){
            section("Impact findings details","Chi tiết phát hiện ảnh hưởng ("+allFindings.size()+")");int n=0;
            for(Object fObj:allFindings){
                n++;var f=map(fObj);
                String ruleId=f.get("ruleId")!=null?str(f.get("ruleId")):str(map(f.get("rule")).get("id"));
                String sev=f.get("severity")!=null?str(f.get("severity")):str(map(f.get("rule")).get("severity"));
                boolean isDirect=Boolean.TRUE.equals(f.get("direct"))||Boolean.TRUE.equals(f.get("isDirect"));
                v.heading("  "+n+"  ["+sev+"] "+ruleId+" ("+(isDirect?tr("DIRECT","TRỰC TIẾP"):tr("TRANSITIVE","GIÁN TIẾP"))+")");
                line("     "+tr("Quantifier","Lượng từ")+": "+str(f.getOrDefault("classification",f.get("quantifier"))));
                var matched=list(f.get("matchedEntities"));
                if(!matched.isEmpty())line("     "+tr("Matched entities","Thực thể khớp")+": "+matched.stream().map(this::name).toList());
                else if(f.get("affectedVertex")!=null)line("     "+tr("Affected vertex","Đỉnh bị ảnh hưởng")+": "+name(f.get("affectedVertex")));
                if(f.get("bundleIdentity")!=null)line("     "+tr("Evidence bundle","Gói bằng chứng")+": "+shortId(f.get("bundleIdentity")));
                else{var bundle=map(f.get("bundle"));if(!bundle.isEmpty()&&bundle.get("identity")!=null)line("     "+tr("Evidence bundle","Gói bằng chứng")+": "+shortId(bundle.get("identity")));}
            }
        }
        if(d.get("witness") instanceof Map<?,?> w&&!w.isEmpty()){section("Feasibility witness","Nhân chứng khả thi");compact(w,1);}
    }
    private void visualGraph(Map<String,Object> d){
        section("Visual workbench graph projection","Phép chiếu đồ thị trực quan");
        var meta=map(d.get("metadata"));field("Projection ID","ID phép chiếu",shortId(d.get("id")));
        if(!meta.isEmpty()){
            field("Scope","Phạm vi",str(meta.get("scope")));field("Projection","Phép chiếu",str(meta.get("projection")));
            field("Total nodes","Tổng số đỉnh",str(meta.get("totalNodes")));
            field("Compound hierarchy nodes","Đỉnh phân cấp (Module/Package)",str(meta.get("compoundNodesCount")));
            field("Total edges","Tổng số cạnh",str(meta.get("totalEdges")));
        }
        var nodes=list(d.get("nodes"));var edges=list(d.get("edges"));
        long violationNodes=nodes.stream().map(TerminalReports::map).filter(n->number(n.get("violationCount"))>0).count();
        long cycleNodes=nodes.stream().map(TerminalReports::map).filter(n->Boolean.TRUE.equals(n.get("isCycleParticipant"))).count();
        field("Nodes with policy violations","Đỉnh có vi phạm chính sách",violationNodes);
        field("Nodes in dependency cycles","Đỉnh tham gia chu trình",cycleNodes);
        long violationEdges=edges.stream().map(TerminalReports::map).filter(e->Boolean.TRUE.equals(e.get("isViolation"))).count();
        long cycleEdges=edges.stream().map(TerminalReports::map).filter(e->Boolean.TRUE.equals(e.get("isCycle"))).count();
        field("Violating dependency edges","Cạnh phụ thuộc vi phạm",violationEdges);
        field("Cyclic dependency edges","Cạnh phụ thuộc chu trình",cycleEdges);
        line("");line("  "+tr("Export options:","Tùy chọn xuất:"));
        line("    evo export cytoscape -o graph.json");
        line("    evo export graphml   -o graph.graphml");
    }
}
