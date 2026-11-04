package com.evolution.analysis.execution.nativebuild;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.filesystem.StreamingContentStore;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

final class NativeBuildFiles {
    static Path safe(String path)throws IOException{
        Path raw=Path.of(path);if(!raw.isAbsolute()||path.startsWith("\\\\"))throw new IOException("NATIVE_ABSOLUTE_LOCAL_PATH_REQUIRED");
        for(var component:raw)if(component.toString().equals("..")||component.toString().contains(":"))throw new IOException("NATIVE_PATH_ESCAPE");
        Path value=raw.normalize();
        for(Path part=value;part!=null;part=part.getParent())try{var attributes=Files.readAttributes(part,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(attributes.isSymbolicLink()||attributes.isOther())throw new IOException("NATIVE_PATH_ALIAS");}catch(NoSuchFileException missing){}
        return value;
    }
    static ContentHandle hash(Path path)throws IOException{
        safe(path.toString());if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("NATIVE_PIN_UNAVAILABLE");
        try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){var digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];long size=0;int read;while((read=input.read(buffer))!=-1){digest.update(buffer,0,read);size=Math.addExact(size,read);}return new ContentHandle(new ContentDigest("sha256:"+HexFormat.of().formatHex(digest.digest())),size);}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    static void require(NativeBuildProfile.FilePin pin)throws IOException{if(!hash(safe(pin.path())).equals(pin.content()))throw new IOException("NATIVE_PIN_CHANGED");}
    static Map<String,Object> read(Path path,int maximum)throws IOException{
        safe(path.toString());try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){byte[] bytes=input.readNBytes(Math.addExact(maximum,1));if(bytes.length>maximum)throw new IOException("NATIVE_RECORD_WORKER_REQUIRED");return GraphJson.object(GraphJson.read(new InputStreamReader(new ByteArrayInputStream(bytes),StandardCharsets.UTF_8),new GraphJson.Limits(maximum,128,maximum)));}
    }
    static void write(Path path,Object value)throws IOException{
        safe(path.toString());byte[] bytes=CanonicalJson.write(value).getBytes(StandardCharsets.UTF_8);Path pending=Files.createTempFile(path.getParent(),"native-record-",".part");
        try{try(var channel=java.nio.channels.FileChannel.open(pending,StandardOpenOption.WRITE)){var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}Files.createLink(path,pending);}finally{Files.deleteIfExists(pending);}
    }
    static ContentHandle handle(Object value){var row=GraphJson.object(value);if(!row.keySet().equals(Set.of("digest","size")))throw new IllegalArgumentException("NATIVE_HANDLE_FIELDS");return new ContentHandle(new ContentDigest(GraphJson.string(row.get("digest"))),((java.math.BigDecimal)row.get("size")).longValueExact());}
    static byte[] resource(String path)throws IOException{try(var input=NativeBuildFiles.class.getResourceAsStream("/"+path)){if(input==null)throw new IOException("NATIVE_CONTROLLER_RESOURCE_MISSING");return input.readAllBytes();}}
    static void controllerEnvironment(ProcessBuilder builder){
        var allowed=Set.of("SYSTEMROOT","WINDIR","TEMP","TMP","USERPROFILE","HOMEDRIVE","HOMEPATH","PROGRAMFILES","PROGRAMFILES(X86)","PROGRAMDATA","COMPUTERNAME","USERNAME","USERDOMAIN");
        builder.environment().keySet().removeIf(key->!allowed.contains(key.toUpperCase(Locale.ROOT)));
    }
    private NativeBuildFiles(){}
}
