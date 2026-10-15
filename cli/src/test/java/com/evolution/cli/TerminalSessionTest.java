package com.evolution.cli;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerminalSessionTest {
    private static final class Console implements TerminalSession.WindowsConsole {
        int current=3,page=850,columns=48,writes;boolean redirected,reject,ignore,broken;
        public long outputHandle(){if(broken)throw new UnsatisfiedLinkError("fixture");return 11;}
        public OptionalInt mode(long handle){return redirected?OptionalInt.empty():OptionalInt.of(current);}
        public boolean mode(long handle,int value){writes++;if(reject&&(value&4)!=0)return false;if(!ignore)current=value;return true;}
        public int columns(long handle){return columns;}
        public int outputCodePage(){return page;}
        public boolean outputCodePage(int value){page=value;return true;}
    }
    private String help(TerminalSession.Profile profile,boolean terminal,String... arguments){
        var output=new StringWriter();
        assertEquals(0,new EvoApp(Path.of("."),new StringReader(""),new PrintWriter(output),new PrintWriter(new StringWriter()),terminal,Map.of("COLUMNS","120"),profile).execute(arguments));
        return output.toString();
    }
    @Test void preparesClassicConsoleAndRestoresModeAndEncodingExactlyOnce(){
        var console=new Console();var session=TerminalSession.open(console,true);
        assertEquals(7,console.current);assertEquals(65001,console.page);assertTrue(session.profile().ansiSafe());
        session.close();assertEquals(3,console.current);assertEquals(850,console.page);
        int writes=console.writes;session.close();assertEquals(writes,console.writes);
    }
    @Test void existingProcessedOutputAndOtherModeBitsSurviveSession(){
        var console=new Console();console.current=0x0002|0x0010;
        try(var session=TerminalSession.open(console,true)){assertTrue(session.profile().ansiSafe());assertEquals(0x0017,console.current);}
        assertEquals(0x0012,console.current);
    }
    @Test void rejectedVirtualTerminalNeverLeaksAnsiEvenWhenColorIsForced(){
        var console=new Console();console.reject=true;
        try(var session=TerminalSession.open(console,true)){
            String text=help(session.profile(),true,"--color","always","--lang","vi","help");
            assertFalse(text.contains("\u001b"));assertTrue(text.contains("Bắt đầu"));assertTrue(text.contains("Kiểm tra quy tắc"));
        }
        assertEquals(3,console.current);assertEquals(850,console.page);
    }
    @Test void verifiesNativeModeRatherThanTrustingSuccessfulSetCall(){
        var console=new Console();console.ignore=true;
        try(var session=TerminalSession.open(console,true)){assertFalse(session.profile().ansiSafe());}
        assertEquals(850,console.page);
    }
    @Test void nativeBridgeFailureDegradesToReadablePlainText(){
        var console=new Console();console.broken=true;
        try(var session=TerminalSession.open(console,true)){
            assertFalse(help(session.profile(),true,"--color","always","help").contains("\u001b"));
        }
    }
    @Test void redirectedOutputRetainsExplicitColorButAutoAndJsonStayClean(){
        var console=new Console();console.redirected=true;
        try(var session=TerminalSession.open(console,false)){
            assertTrue(help(session.profile(),false,"--color","always","help").contains("\u001b"));
            assertFalse(help(session.profile(),false,"help").contains("\u001b"));
            assertFalse(help(session.profile(),false,"--color","always","--json","--version").contains("\u001b"));
        }
        assertEquals(0,console.writes);assertEquals(850,console.page);
    }
    @Test void realWindowWidthWinsOverStaleColumnsAndExplicitWidthStillWins(){
        var console=new Console();
        try(var session=TerminalSession.open(console,true)){
            String narrow=help(session.profile(),false,"--color","never","--ascii","help");
            for(String row:narrow.lines().toList())assertTrue(TerminalView.cells(row)<=48,row);
            assertTrue(narrow.contains("-".repeat(48)));assertFalse(narrow.contains("-".repeat(49)));
            assertTrue(help(session.profile(),false,"--color","never","--ascii","--width","80","help").contains("-".repeat(80)));
        }
    }
}
