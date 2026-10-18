package com.evolution.analysis.filesystem;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StreamingScaleTest {
    @TempDir Path root;
    @Test void inventoryBeyondOneMillionAndContentBeyondHeap()throws Exception{
        String javaExecutable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        var builder=new ProcessBuilder(javaExecutable,"-Xmx48m","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),StreamingScaleProbe.class.getName(),root.toString(),"1000001").redirectErrorStream(true);
        var process=builder.start();boolean ended=process.waitFor(15,TimeUnit.MINUTES);if(!ended){process.destroyForcibly();fail("Scale worker exceeded watchdog; its durable state is retained");}
        String output=new String(process.getInputStream().readNBytes(8192),java.nio.charset.StandardCharsets.UTF_8);assertEquals(0,process.exitValue(),output);assertTrue(output.startsWith("PASS "),output);System.out.print(output);
    }
}
