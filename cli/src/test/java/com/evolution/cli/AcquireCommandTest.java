package com.evolution.cli;

import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AcquireCommandTest {
    @TempDir Path root;
    record Result(int exit,Map<String,Object> value,String errors){}
    Result run(String...args){var output=new StringWriter();var error=new StringWriter();int exit=new EvoApp(root,new StringReader(""),new PrintWriter(output),new PrintWriter(error),false,Map.of()).execute(args);return new Result(exit,GraphJson.object(GraphJson.read(output.toString().strip(),GraphJson.Limits.defaults())),error.toString());}
    @Test void acquireInventoryAndResumeShareTheSameSealedManifest()throws Exception{
        Path source=Files.createDirectory(root.resolve("source"));for(String name:List.of("api.proto","main.py","nested/pom.xml","unknown.bin")){Path file=source.resolve(name);Files.createDirectories(file.getParent());Files.writeString(file,name);}
        var captured=run("acquire",source.toString(),"--resume","journey","--json");assertEquals(0,captured.exit(),captured.errors());
        assertTrue(captured.value().toString().contains("NOT_EVALUATED"));
        var page=run("inventory","journey","--json","--limit","2");assertEquals(0,page.exit(),page.errors());assertTrue(page.value().toString().contains("continuation"));
        var replay=run("acquire",source.toString(),"--resume","journey","--json");assertEquals(0,replay.exit(),replay.errors());assertEquals(captured.value(),replay.value());
    }
}
