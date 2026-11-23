package com.evolution.backend.query;

import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.query.ArchitectureQuery.Summary;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Renders a current-project overview from the evidence packet, without model inference. */
final class ArchitectureChatOverview {
    private static final Pattern VI = Pattern.compile(
            "(?:(?:(?:ban (?:co the )?)?(?:cho (?:toi|minh) biet|tom tat|mo ta|giai thich|noi ve|cap nhat) )?"
            + "(?:context|ngu canh|boi canh|tong quan|hien trang|tinh trang|trang thai)(?: hien tai)?"
            + " (?:ve |cua )?(?:(?:du an|repo|repository|he thong)(?: nay)?)(?: hien tai)?"
            + "|(?:tom tat|mo ta|gioi thieu) (?:du an|he thong|repo)(?: nay)?(?: hien tai)?"
            + "|(?:du an|he thong|repo) nay (?:hien tai )?(?:the nao|dang o dau|co nhung gi))(?: (?:nhe|duoc khong|voi))?");
    private static final Pattern EN = Pattern.compile(
            "(?:(?:please )?(?:what is |what's |tell me (?:about )?|summarize |describe |show me |give me )?"
            + "(?:an? |the )?(?:current )?(?:(?:project|repository|system) (?:context|status|overview)"
            + "|(?:context|status|overview) (?:of|for) (?:this|the) (?:project|repository|system))"
            + "|(?:please )?(?:summarize|describe|give me an overview of) (?:this|the) (?:project|repository|system))");

    static boolean matches(String question) {
        String normalized = normalize(question);
        return VI.matcher(normalized).matches() || EN.matcher(normalized).matches();
    }

    record Answer(String text, String title, List<String> citations) {}

    static Answer render(String question, ArchitectureChat.Context context) {
        boolean vi = VI.matcher(normalize(question)).matches();
        String title = vi ? "Bối cảnh hiện tại của dự án" : "Current project context";
        var text = new StringBuilder("## ").append(title);
        var citations = new ArrayList<String>();
        for (var row : context.evidence()) {
            var data = row.data();
            switch (row.label()) {
                case "Captured architecture summary", "Captured analysis context" -> {
                    String value = code(data.get("status"));
                    if (data.get("counts") instanceof List<?> counts && !counts.isEmpty() && counts.getFirst() instanceof Summary summary) {
                        long modules = summary.nodeCounts().getOrDefault("MODULE",0L);
                        long types = summary.nodeCounts().getOrDefault("TYPE",0L);
                        long edges = summary.edgeCounts().values().stream().mapToLong(Long::longValue).sum();
                        value += (vi ? " · %s module · %s kiểu · %s lượt quan hệ theo loại" : " · %s modules · %s types · %s edge-kind counts")
                                .formatted(modules,types,edges);
                    }
                    String label = data.containsKey("context") ? vi ? "Ngữ cảnh phân tích cùng snapshot" : "Analysis context in this snapshot"
                            : vi ? "Phạm vi graph đang mở" : "Selected graph scope";
                    line(text,label,value,row,citations);
                }
                case "Published analysis job" -> line(text,vi ? "Job phân tích" : "Published analysis job",
                        "`%s` · `%s` · %s %s · %s %s".formatted(data.get("mode"),data.get("completion"),
                                data.get("contexts"),vi ? "ngữ cảnh" : "contexts",data.get("totalGaps"),vi ? "gap toàn job" : "total job gaps"),row,citations);
                case "Repository readiness overview" -> {
                    var readiness = GraphJson.object(data.get("readiness"));
                    line(text,vi ? "Kiểm kê build" : "Build discovery",
                            "`%s` · %s %s · %s %s".formatted(readiness.get("profile"),readiness.get("rootCount"),vi ? "root ứng viên" : "candidate roots",
                                    readiness.get("moduleCount"),vi ? "module ứng viên" : "candidate modules"),row,citations);
                    var coverage = GraphJson.object(readiness.get("coverage"));
                    text.append(vi ? "\n- Mục cần xác minh trong kế hoạch: " : "\n- Pending readiness requirements: ").append(coverage.get("pending"));
                    text.append(vi ? " · mục đầu vào dependency: " : " · dependency input entries: ").append(coverage.get("dependencyInputs"));
                    text.append(vi ? " · module trong thư mục dependency đã cài: " : " · modules under installed dependency directories: ")
                            .append(readiness.get("installedDependencyModules"));
                    var modules = GraphJson.array(readiness.get("modules")).stream().map(GraphJson::object)
                            .map(ArchitectureChatOverview::module)
                            .distinct().limit(10).toList();
                    if (!modules.isEmpty()) {
                        text.append(vi ? "\n\n### Mẫu module phát hiện" : "\n\n### Discovered module sample");
                        modules.forEach(module -> text.append("\n- ").append(module));
                    }
                }
                case "Analysis capability gaps" -> {
                    var reasons = GraphJson.array(data.get("topReasons")).stream().map(GraphJson::object).limit(4)
                            .map(reason -> code(reason.get("reason")) + " (" + reason.get("count") + ")").toList();
                    if (!reasons.isEmpty()) line(text,vi ? "Lý do gap thường gặp" : "Most frequent gap reasons",
                            String.join(", ",reasons) + " · " + data.get("counted") + "/" + data.get("total")
                                    + (vi ? " bản ghi trong sổ gap của job" : " job-ledger records tallied"),row,citations);
                }
                case "Captured modules", "Captured types" -> {
                    var names = GraphJson.array(data.get(row.kind() == ArchitectureChat.Kind.STRUCTURE ? "modules" : "matches"))
                            .stream().map(GraphJson::object).limit(8).map(record -> code(record.get("name"))).toList();
                    if (!names.isEmpty()) line(text,row.kind() == ArchitectureChat.Kind.STRUCTURE
                                    ? vi ? "Mẫu module trong graph" : "Graph module sample"
                                    : vi ? "Mẫu kiểu trong graph" : "Graph type sample",String.join(", ",names),row,citations);
                }
                default -> { }
            }
        }
        if (context.evidence().stream().noneMatch(row -> row.label().equals("Repository readiness overview")))
            text.append(vi ? "\n\nẢnh chụp hiện tại chưa có bản kiểm kê build đi kèm câu trả lời."
                    : "\n\nBuild discovery metadata is unavailable for this answer.");
        if (context.limitations().contains("Only four analysis contexts from this snapshot are included."))
            text.append(vi ? "\n\nBản tổng quan hiển thị mẫu bốn ngữ cảnh phân tích." : "\n\nThe overview shows a sample of four analysis contexts.");
        if (context.evidence().getFirst().data().get("status") != AssessmentStatus.COMPLETE)
            text.append(vi ? "\n\nĐộ bao phủ của phạm vi graph đang mở còn giới hạn. Các quan hệ chưa xác lập vẫn chưa rõ."
                    : "\n\nCoverage of the selected graph scope is limited. Unresolved relationships remain unknown.");
        return new Answer(text.toString(),title,List.copyOf(citations));
    }

    private static String module(Map<String,Object> module) {
        var languages = GraphJson.array(module.get("languages")).stream().map(GraphJson::string).toList();
        return code(module.get("directory")) + " · " + module.get("family")
                + (languages.isEmpty() ? "" : " · " + String.join(", ",languages));
    }

    private static void line(StringBuilder text, String label, String value, ArchitectureChat.Evidence row, List<String> citations) {
        text.append(citations.isEmpty() ? "\n\n- " : "\n- ").append("**").append(label).append("** ")
                .append(value).append(" [").append(row.id()).append("]");
        citations.add(row.id());
    }

    private static String code(Object value) { return "`" + value.toString().replace('`','ˋ').replaceAll("\\p{Cntrl}"," ") + "`"; }

    private static String normalize(String question) {
        return Normalizer.normalize(question.toLowerCase(Locale.ROOT),Normalizer.Form.NFD).replaceAll("\\p{M}","")
                .replace('đ','d').replace('’','\'').strip().replaceAll("[.!?]+$","").replaceAll("\\s+"," ");
    }

    private ArchitectureChatOverview() {}
}
