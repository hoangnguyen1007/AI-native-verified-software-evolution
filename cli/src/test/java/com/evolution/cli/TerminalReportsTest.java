package com.evolution.cli;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TerminalReportsTest {
    @Test void catalogShowsMeaningUnitsAndCommandInsteadOfAnAbsentMetricValue(){
        String text=render("metrics",Map.of("status","COMPLETE","items",List.of(Map.of("metric",Map.of("id","metric.inventory","version","m5.3-v1"),"meaning","Observed inventory","unit","entities","scopes",List.of("TYPE")))));
        assertTrue(text.contains("Observed inventory"));assertTrue(text.contains("entities"));assertTrue(text.contains("metrics metric.inventory"));assertFalse(text.contains("Giá trị"));
    }
    @Test void bundleDrawsRelationshipsAndSeparatesFormulaFromSemanticReplay(){
        String text=render("witness",Map.of("status","PARTIAL","request",Map.of(),"rule",Map.of("header",Map.of("id","layers","severity","ERROR")),"region",Map.of("classification","MAY","witness",Map.of("world","dev","formulaReplayed",true,"semanticReplayed",false)),"relationships",List.of(Map.of("source","A","target","B","classification","MAY")),"sources",List.of(Map.of("span",Map.of("document","File.java","startLine",3,"startColumn",2,"endLine",3,"endColumn",8)))));
        assertTrue(text.contains("A → B"));assertTrue(text.contains("File.java:3:2"));assertTrue(text.contains("ngữ nghĩa false"));
    }
    String render(String command,Map<String,Object> data){var text=new StringWriter();new TerminalView(new PrintWriter(text),true,false,false,80).response(command,EvoApp.envelope(data));return text.toString();}
    @Test void commandsUseCompactHeadersAndReadableObservedInventory(){
        String text=render("show",Map.of("status","PARTIAL","items",List.of(Map.of("nodeCounts",Map.of("TYPE",12,"MODULE",2),"edgeCounts",Map.of("graph.depends-on",7),"populationMeaning","observed"))));
        assertTrue(text.contains("12"));assertTrue(text.contains("PARTIAL"));assertFalse(text.contains("evo CLI 0.1.0"),"Full logo belongs at entry, not on every result");
        assertTrue(text.contains("Quan sát"));
    }
    @Test void pathsDrawActualEdgeDirectionAndExplainUnknownFeasibility(){
        String text=render("impact",Map.of("status","PARTIAL","items",List.of(Map.of("vertices",List.of("B","A"),"edges",List.of(Map.of("source","A","target","B","classification","MAY","definite",true)),"feasibility","UNKNOWN"))));
        assertTrue(text.contains("A → B"),text);assertTrue(text.contains("UNKNOWN"));assertTrue(text.contains("tiềm năng"));
    }
    @Test void withheldAssessmentNeverInventsScoreOrProbability(){
        String text=render("score",Map.of("status","WITHHELD","confidence",Map.of("sufficient",false,"declaredRules",4,"completeRules",2,"requiredMetrics",2,"completeMetrics",1),"dimensions",List.of(Map.of("id","coupling","status","WITHHELD","weight",25)),"gaps",List.of(Map.of("reasonCode","MISSING_INPUT"))));
        assertTrue(text.contains("2/4"));assertTrue(text.contains("1/2"));assertTrue(text.contains("Chưa đủ"));assertTrue(text.contains("MISSING_INPUT"));assertFalse(text.contains("100%"));
    }
    @Test void acquisitionFailureShowsReasonSubjectAndNextAction(){
        String text=render("scan",Map.of("status","TERMINAL_FAILURE","stage","acquisition","graphCreated",false,"acquisition",Map.of("status","PARTIAL","files",0,"bytes",0,"problems",List.of(Map.of("reason","FILE_BYTE_LIMIT","path","large.bin")))));
        assertTrue(text.contains("FILE_BYTE_LIMIT"));assertTrue(text.contains("large.bin"));assertTrue(text.contains("--exclude"));
    }
    @Test void everyReportFitsNarrowWidthAndEscapesUntrustedControls(){
        for(int width:List.of(32,48,80,160))for(boolean ascii:List.of(false,true)){
            var text=new StringWriter();new TerminalView(new PrintWriter(text),false,true,ascii,width).response("find",EvoApp.envelope(Map.of("status","PARTIAL","items",List.of(Map.of("kind","TYPE","qualifiedName","Long\u001b[31m"+"界".repeat(40),"identity","sha256:"+"a".repeat(64))))));
            String plain=text.toString().replaceAll("\u001b\\[[0-9;]*m","");assertFalse(plain.contains("\u001b"));for(String row:plain.lines().toList())assertTrue(TerminalView.cells(row)<=width,width+": "+row);
        }
    }
}
