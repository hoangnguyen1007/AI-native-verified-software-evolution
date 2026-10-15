package com.evolution.cli;

import java.util.Locale;
import java.util.OptionalInt;
import org.fusesource.jansi.internal.Kernel32;

/** Owns native console preparation/restoration; canonical streams are never wrapped or rewritten. */
final class TerminalSession implements AutoCloseable {
    static final int PROCESSED_OUTPUT=0x0001, VIRTUAL_TERMINAL_PROCESSING=0x0004;
    record Profile(boolean ansiSafe,int columns) {
        static Profile suppliedWriter(){return new Profile(true,0);}
    }
    interface WindowsConsole {
        long outputHandle();
        OptionalInt mode(long handle);
        boolean mode(long handle,int value);
        int columns(long handle);
        int outputCodePage();
        boolean outputCodePage(int value);
    }
    private final Profile profile;
    private final Runnable restore;
    private Thread shutdownHook;
    private boolean closed;

    private TerminalSession(Profile profile,Runnable restore){this.profile=profile;this.restore=restore;}
    Profile profile(){return profile;}

    static TerminalSession open(){
        if(!System.getProperty("os.name","").toLowerCase(Locale.ROOT).startsWith("windows"))
            return new TerminalSession(Profile.suppliedWriter(),()->{});
        var session=open(new JansiWindowsConsole(),System.console()!=null);
        session.shutdownHook=new Thread(session::restoreOnce,"evo-console-restore");
        try {Runtime.getRuntime().addShutdownHook(session.shutdownHook);}
        catch(IllegalStateException|SecurityException unavailable){session.shutdownHook=null;}
        return session;
    }

    static TerminalSession open(WindowsConsole console,boolean interactiveConsole){
        Runnable restore=()->{};
        try {
            long handle=console.outputHandle();
            var original=console.mode(handle);
            // A redirected stream can explicitly carry ANSI bytes; an unprepared real console cannot.
            if(original.isEmpty())return new TerminalSession(new Profile(!interactiveConsole,0),restore);
            int mode=original.getAsInt(),requested=mode|PROCESSED_OUTPUT|VIRTUAL_TERMINAL_PROCESSING;
            int page=console.outputCodePage();
            restore=()->{
                try {console.mode(handle,mode);}finally {if(page>0)console.outputCodePage(page);}
            };
            boolean utf8=page==65001||console.outputCodePage(65001);
            boolean ready=(mode&requested)==requested||console.mode(handle,requested);
            var verified=console.mode(handle);
            ready=ready&&verified.isPresent()&&(verified.getAsInt()&requested)==requested;
            int columns;
            try {columns=console.columns(handle);}catch(RuntimeException|LinkageError unavailable){columns=0;}
            return new TerminalSession(new Profile(ready&&utf8,columns),restore);
        }catch(RuntimeException|LinkageError unavailable){
            // Native extraction/access can fail. Never print unsupported SGR into the user's console.
            try {restore.run();}catch(RuntimeException|LinkageError ignored){}
            return new TerminalSession(new Profile(false,0),()->{});
        }
    }

    private synchronized void restoreOnce(){
        if(closed)return;closed=true;
        try {restore.run();}catch(RuntimeException|LinkageError ignored){}
    }
    @Override public void close(){
        restoreOnce();
        if(shutdownHook!=null)try {Runtime.getRuntime().removeShutdownHook(shutdownHook);}
        catch(IllegalStateException|SecurityException alreadyShuttingDown){}
    }

    /** JNI stays in this replaceable presentation adapter, pinned with the Jansi artifact. */
    private static final class JansiWindowsConsole implements WindowsConsole {
        public long outputHandle(){return Kernel32.GetStdHandle(Kernel32.STD_OUTPUT_HANDLE);}
        public OptionalInt mode(long handle){var value=new int[1];return Kernel32.GetConsoleMode(handle,value)!=0?OptionalInt.of(value[0]):OptionalInt.empty();}
        public boolean mode(long handle,int value){return Kernel32.SetConsoleMode(handle,value)!=0;}
        public int columns(long handle){var info=new Kernel32.CONSOLE_SCREEN_BUFFER_INFO();return Kernel32.GetConsoleScreenBufferInfo(handle,info)!=0?info.windowWidth():0;}
        public int outputCodePage(){return Kernel32.GetConsoleOutputCP();}
        public boolean outputCodePage(int value){return Kernel32.SetConsoleOutputCP(value)!=0;}
    }
}
