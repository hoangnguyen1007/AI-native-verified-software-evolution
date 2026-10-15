package com.evolution.cli;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TerminalViewTest {
    @Test void untrustedControlsCannotChangeTerminalState(){String escaped=TerminalView.clean("type\u001b]52;c;SECRET\u0007\r\n\u202eevil");assertFalse(escaped.contains("\u001b"));assertFalse(escaped.contains("\r"));assertTrue(escaped.contains("\\u001b"));assertTrue(escaped.contains("\\u202e"));}
    @Test void vietnameseCombiningMarksAndWideCharactersFitNarrowTerminal(){var output=new StringWriter();var view=new TerminalView(new PrintWriter(output),true,false,true,32);view.line("Tên thực thể: "+"界e\u0301".repeat(30));for(String row:output.toString().lines().toList())assertTrue(TerminalView.cells(row)<=32,row);assertTrue(output.toString().contains("Tên"));}
    @Test void statusAndGapsStayVisibleWithoutColor(){var output=new StringWriter();new TerminalView(new PrintWriter(output),true,false,true,64).response("score",EvoApp.envelope(Map.of("status","WITHHELD","score",Optional.empty(),"gaps",List.of(Map.of("reasonCode","MISSING_EVIDENCE")))));String text=output.toString();assertTrue(text.contains("WITHHELD"));assertTrue(text.contains("MISSING_EVIDENCE"));assertFalse(text.contains("\u001b"));}
    @Test void shellWordsPreserveWindowsPathsQuotesAndMetacharacters(){assertEquals(List.of("open","C:\\a b\\graph.json","$(echo","x)"),ShellWords.parse("open \"C:\\a b\\graph.json\" $(echo x)"));assertEquals(List.of("find",""),ShellWords.parse("find ''"));assertThrows(IllegalArgumentException.class,()->ShellWords.parse("open 'broken"));}
    @Test void mintAndLimeMarkKeepsBlueTextAndIdenticalContentWithoutColor(){
        var colored=new StringWriter();var plain=new StringWriter();
        new TerminalView(new PrintWriter(colored),false,true,false,80).banner();
        new TerminalView(new PrintWriter(plain),false,false,false,80).banner();
        assertTrue(colored.toString().contains("\u001b[38;2;141;181;245m"));
        assertTrue(colored.toString().contains("\u001b[38;2;125;224;195m"));
        assertTrue(colored.toString().contains("\u001b[38;2;178;229;110m"));
        assertFalse(colored.toString().contains("\u001b[38;2;242;151;67m"));
        assertTrue(plain.toString().contains("██"));
        assertEquals(plain.toString(),colored.toString().replaceAll("\u001b\\[[0-9;]*m",""));
    }
    @Test void guideHasPlainRequestedLabelsAndNoSloganOrSubtitle(){
        var output=new StringWriter();new TerminalView(new PrintWriter(output),true,false,false,80).guide(false);
        String text=output.toString();assertTrue(text.contains("evo CLI 0.1.0"));
        assertTrue(text.contains("Bắt đầu"));assertTrue(text.contains("Commands"));
        assertTrue(text.contains("Kiểm tra quy tắc"));assertTrue(text.contains("Giải thích độ tin cậy"));
        for(String removed:List.of("ARCHITECTURE INTELLIGENCE","Truy đến","Follow evidence","Khám phá cấu trúc","Điều tra","Bắt đầu &","Java 21","Luật, phát hiện & bằng chứng","Giải thích sức khỏe"))assertFalse(text.contains(removed),removed);
    }
    @Test void logoAndGuideFitNarrowAndWideTerminalsWithPortableAsciiFallback(){
        for(int width:List.of(32,48,80,160))for(boolean ascii:List.of(false,true)){
            var output=new StringWriter();new TerminalView(new PrintWriter(output),false,true,ascii,width).guide(true);
            String text=output.toString().replaceAll("\u001b\\[[0-9;]*m","");
            for(String row:text.lines().toList())assertTrue(TerminalView.cells(row)<=width,width+": "+row);
            assertFalse(text.contains("\ufffd"));
            if(ascii)assertTrue(text.codePoints().allMatch(cp->cp<128));
        }
    }
}
