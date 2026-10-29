package com.evolution.analysis.filesystem;

import com.evolution.analysis.polyglot.SemanticTables;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

class SemanticStoragePathTest {
    @TempDir Path directory;

    @Test void windowsShortNamesWorkForCasAndBothAuthorityEntryPoints()throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        var root=Files.createDirectory(directory.resolve("semantic-storage-long-directory-name"));
        var alias=Path.of(command("for %I in ("+quoted(root)+") do @echo %~sI").strip());
        assumeFalse(alias.equals(root),"Volume does not provide a distinct 8.3 alias");
        assertTrue(Files.isSameFile(alias,root));
        var content=new StreamingContentStore(alias.resolve("cas"));
        var authority=Files.createDirectory(root.resolve("authority"));
        Files.writeString(authority.resolve("authority.json"),"{\"schema\":\"semantic-authority:m8.4-v1\",\"controllers\":{},\"validators\":{},\"methods\":[],\"revoked\":[]}");
        var policy=SemanticAuthorityStore.policy(authority);
        assertEquals(policy,SemanticAuthorityStore.policy(alias.resolve("authority")));
        assertEquals(policy,new SemanticAuthorityStore(alias.resolve("authority"),content,new SemanticTables(content,65536),policy).policy());
    }

    @Test void junctionAncestorsRemainRejected()throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        var target=Files.createDirectory(directory.resolve("target"));var link=directory.resolve("junction");
        Files.writeString(target.resolve("authority.json"),"{\"schema\":\"semantic-authority:m8.4-v1\",\"controllers\":{},\"validators\":{},\"methods\":[],\"revoked\":[]}");
        assertNotNull(SemanticAuthorityStore.policy(target));
        command("mklink /J "+quoted(link)+" "+quoted(target));
        try {
            assertThrows(IOException.class,()->new StreamingContentStore(link.resolve("cas")));
            assertThrows(IOException.class,()->SemanticAuthorityStore.policy(link));
            assertFalse(Files.exists(target.resolve("cas")),"Reject the ancestor before creating storage");
        }finally{Files.deleteIfExists(link);}
    }

    @Test void missingAuthorityIsNeverCreated() {
        var absent=directory.resolve("absent");
        assertThrows(IOException.class,()->SemanticAuthorityStore.policy(absent));
        assertFalse(Files.exists(absent));
    }

    private static String quoted(Path path) {
        var value=path.toAbsolutePath().toString();
        if(value.matches(".*[%!&|<>^\"\\r\\n].*"))throw new IllegalArgumentException("Unsafe test shell path");
        return "\""+value+"\"";
    }
    private static String command(String command)throws Exception {
        var process=new ProcessBuilder("cmd.exe","/d","/c",command).redirectErrorStream(true).start();
        if(!process.waitFor(10,TimeUnit.SECONDS)){process.destroyForcibly();throw new IOException("Windows path fixture timed out");}
        var output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        assertEquals(0,process.exitValue(),output);return output;
    }
}
