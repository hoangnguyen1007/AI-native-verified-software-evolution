package com.evolution.cli;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
/** UTF-8 streams and exit status are owned only by the process boundary. */
public final class EvoMain {
    public static void main(String[] args){
        int code;
        try(var terminal=TerminalSession.open()){
            var console=System.console();var reader=console!=null?console.reader():new InputStreamReader(System.in,StandardCharsets.UTF_8);
            var out=new PrintWriter(new OutputStreamWriter(System.out,StandardCharsets.UTF_8),true);
            var err=new PrintWriter(new OutputStreamWriter(System.err,StandardCharsets.UTF_8),true);
            code=new EvoApp(Path.of("."),reader,out,err,console!=null,System.getenv(),terminal.profile()).execute(args);
            out.flush();err.flush();
        }
        System.exit(code);
    }
    private EvoMain(){}
}
