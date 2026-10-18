package com.evolution.cli;

import com.evolution.analysis.graph.GraphJson;
import java.io.PrintWriter;
import java.util.*;

/** Scrollback-friendly rendering. Untrusted text can never inject terminal controls. */
final class TerminalView {
    private static final int BLUE=0x8db5f5, MUTED=0x939aaa, RULE=0x424b5f, CAUTION=0xd9b866;
    // Interlaced hexagonal orbit with an eye aperture. Ordinary blocks need no special font.
    private static final List<String> MARK=List.of(
            "    eeeee    ",
            "  ee m   mm  ",
            " ee  sss  mm ",
            "ee ss   ssmmm",
            "ee s  l  s mm",
            "eeess   ss mm",
            " ee  sss  mm ",
            "  ee   m mm  ",
            "    mmmmm    ");
    private final PrintWriter out;final boolean vi,color,ascii;final int width;
    TerminalView(PrintWriter out,boolean vi,boolean color,boolean ascii,int width) {
        this.out=out;this.vi=vi;this.color=color;this.ascii=ascii;this.width=Math.max(32,Math.min(160,width));
    }
    String tr(String en,String vn){return vi?vn:en;}
    static String clean(Object value) {
        String text=String.valueOf(value);var result=new StringBuilder();
        text.codePoints().forEach(cp->{
            int type=Character.getType(cp);
            if(Character.isISOControl(cp)||type==Character.FORMAT||type==Character.LINE_SEPARATOR||type==Character.PARAGRAPH_SEPARATOR)
                result.append(String.format(Locale.ROOT,"\\u%04x",cp));
            else result.appendCodePoint(cp);
        });return result.toString();
    }
    // Combining marks do not consume a terminal cell; East Asian and emoji glyphs usually consume two.
    static int cells(String text) {return text.codePoints().map(cp->{int t=Character.getType(cp);return t==Character.NON_SPACING_MARK||t==Character.COMBINING_SPACING_MARK?0:
            cp>=0x1100&&(cp<=0x115f||cp>=0x2e80&&cp<=0xa4cf||cp>=0xac00&&cp<=0xd7a3||cp>=0xf900&&cp<=0xfaff||cp>=0xfe10&&cp<=0xfe6f||cp>=0xff01&&cp<=0xff60||cp>=0x1f300)?2:1;}).sum();}
    private String style(String text,int rgb){
        return color?"\u001b[38;2;"+(rgb>>16&255)+";"+(rgb>>8&255)+";"+(rgb&255)+"m"+text+"\u001b[0m":text;
    }
    private String display(String text){String result=clean(text);return ascii?result.replace(" · "," / ").replace("—","-").replace("→","->").replace("…","..."):result;}
    void line(String text){wrap(display(text),width).forEach(out::println);out.flush();}
    private static List<String> wrap(String text,int max){
        var lines=new ArrayList<String>();var row=new StringBuilder();int n=0;
        for(int cp:text.codePoints().toArray()) {String c=new String(Character.toChars(cp));int size=cells(c);
            if(n+size>max&&n>0){lines.add(row.toString());row.setLength(0);n=0;}row.append(c);n+=size;}
        lines.add(row.toString());return lines;
    }
    private String markRow(String pixels){
        var row=new StringBuilder("  ");
        for(char pixel:pixels.toCharArray()){
            int rgb=switch(pixel){
                case 'e'->0x2bb999;case 'm'->0x7de0c3;case 's'->0xd2f5eb;
                default->0xb2e56e;
            };
            row.append(pixel==' '?"  ":style(ascii?"##":"██",rgb));
        }
        return row.toString();
    }
    void banner(){
        String name="evo CLI "+EvoApp.RELEASE;
        String locale=tr("English","Tiếng Việt");
        out.println();
        for(int i=0;i<MARK.size();i++){
            String metadata=width<48?"":switch(i){
                case 2->style(name,BLUE);case 4->style(locale,MUTED);default->"";
            };
            out.println(markRow(MARK.get(i))+(metadata.isEmpty()?"":"    "+metadata));
        }
        if(width<48){out.println();out.println(style("  "+name,BLUE));out.println(style("  "+locale,MUTED));}
        out.println();out.println(style((ascii?"-":"─").repeat(width),RULE));out.flush();
    }
    void heading(String text){out.println();for(String row:wrap(display(text),width))out.println(style(row,BLUE));}
    void tone(String text,boolean caution){for(String row:wrap(display(text),width))out.println(style(row,caution?CAUTION:BLUE));out.flush();}
    void title(String title){heading(title.toUpperCase(Locale.ROOT));out.println(style((ascii?"-":"─").repeat(width),RULE));}
    private void action(int n,String command,String en,String vn){
        String prefix="  "+n+"  "+String.format(Locale.ROOT,"%-16s",command);
        List<String> rows=wrap(clean(tr(en,vn)),Math.max(10,width-cells(prefix)));
        if(width<48){out.println(style("  "+n+"  "+command,BLUE));for(String row:wrap(clean(tr(en,vn)),width-5))out.println("     "+style(row,MUTED));}
        else {out.println(style(prefix,BLUE)+rows.getFirst());for(String row:rows.subList(1,rows.size()))out.println(" ".repeat(cells(prefix))+row);}
    }
    void note(String en,String vn){line(tr(en,vn));}
    void palette(){guide(true);}
    void guide(boolean interactive){banner();
        heading(tr("Start","Bắt đầu"));
        out.println();
        action(1,"scan .","Analyze this repository","Phân tích repository này");
        action(2,"show","Snapshot overview","Tổng quan snapshot");
        action(3,"find <text>","Find symbols","Tìm symbol");
        action(4,"deps <id>","Dependencies / --incoming","Phụ thuộc / --incoming");
        action(5,"impact <id>","Potential structural impact","Phạm vi ảnh hưởng cấu trúc tiềm năng");
        action(6,"check <policy>","Check rules","Kiểm tra quy tắc");
        action(7,"score","Explain confidence","Giải thích độ tin cậy");
        action(8,"export json","JSON / SARIF / GraphML","JSON / SARIF / GraphML");
        heading("Commands");
        line("  "+tr("Acquisition","Thu nhận")+" acquire <input> · inventory <revision>");
        line("  "+tr("Snapshots","Snapshot")+"   open <file> · use <n> · provenance");
        line("  "+tr("Graph","Đồ thị")+"       reach <n> · paths <a> <b> · cycles");
        line("  "+tr("Evidence","Bằng chứng")+"   gaps · explain <n> · evidence <id>");
        line("  Spring      spring · config");
        line("  "+tr("Reports","Báo cáo")+"     metrics · rules · findings · bundle <id>");
        line("  Replay      witness <id> · replay <id>");
        line("  CLI         query · next · completion · help · --version");
        if(interactive)note("Type a command, a number, help, lang en|vi, or quit. Quotes preserve spaces.","Gõ lệnh, số thứ tự, help, lang en|vi hoặc quit. Dùng dấu nháy cho đường dẫn có khoảng trắng.");
        else {heading(tr("Options & help","Tùy chọn & trợ giúp"));line("--lang en|vi   --color auto|always|never   --ascii");line("--details   --json   --debug");line("-g FILE   -c ID|N   -s SCOPE   -v VIEW   -n 1..50   --depth 1..20");line("evo <command> --help");}
    }
    void response(String command,Map<String,Object> envelope){
        response(command,envelope,Map.of());
    }
    void response(String command,Map<String,Object> envelope,Map<String,Object> selection){
        response(command,envelope,selection,false,Map.of());
    }
    void response(String command,Map<String,Object> envelope,Map<String,Object> selection,boolean full,Map<String,String> labels){
        response(command,envelope,selection,full,labels,Map.of());
    }
    void response(String command,Map<String,Object> envelope,Map<String,Object> selection,boolean full,Map<String,String> labels,Map<String,List<Map<String,Object>>> descriptors){
        new TerminalReports(this,full,labels,descriptors).render(command,envelope,selection);out.flush();
    }
    void details(Object value,int indent){
        String pad=" ".repeat(Math.min(12,indent));
        if(value instanceof Map<?,?> map){
            for(var entry:map.entrySet()){
                String key=String.valueOf(entry.getKey());
                Object v=entry.getValue();
                if(v instanceof Map<?,?>||v instanceof List<?>){line(pad+key+":");details(v,indent+2);}
                else line(pad+key+"  "+(v==null?tr("unavailable","chưa có"):v));
            }
        }else if(value instanceof List<?> list){if(list.isEmpty())line(pad+"[]");else for(Object item:list)details(item,indent+2);}
        else line(pad+value);
    }
}
