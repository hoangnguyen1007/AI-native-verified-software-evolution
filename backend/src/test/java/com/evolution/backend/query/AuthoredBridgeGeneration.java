package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Native generators on a fixed platform-authored schema; no target hooks or app execution. */
final class AuthoredBridgeGeneration {
    static void generate(Path work,Map<String,String> files)throws Exception{
        Files.createDirectories(work);Path repository=Path.of("..").toRealPath(),sdk=Path.of(System.getProperty("bridge.languages")).toRealPath();
        Map<String,Object> pins;try(var stream=AuthoredBridgeGeneration.class.getResourceAsStream("/bridge-generator-tools.json")){pins=GraphJson.object(GraphJson.read(new InputStreamReader(Objects.requireNonNull(stream),StandardCharsets.UTF_8),GraphJson.Limits.defaults()));}
        var tools=new ArrayList<Path>();for(Object row:GraphJson.array(pins.get("artifacts"))){var pin=GraphJson.object(row);Path tool=sdk.resolve(GraphJson.string(pin.get("file")));assertEquals(pin.get("sha256"),ContentDigest.sha256(Files.readAllBytes(tool)).value().substring(7));tools.add(tool);}
        Path schema=work.resolve("users.proto"),js=Files.createDirectory(work.resolve("js")),server=Files.createDirectory(work.resolve("server")),descriptor=work.resolve("users.pb");
        Files.writeString(schema,files.get("api/users.proto"),StandardCharsets.UTF_8);
        run(List.of(tools.getFirst().toString(),"--proto_path="+work,"--java_out="+server,"--plugin=protoc-gen-grpc-java="+tools.getLast(),"--grpc-java_out="+server,"--include_imports","--descriptor_set_out="+descriptor,schema.toString()),work.resolve("protoc.log"));
        run(List.of(System.getProperty("bridge.node"),repository.resolve("backend/src/test/resources/bridge-generate.cjs").toString(),repository.toString(),System.getProperty("bridge.native"),schema.toString(),js.toString()),work.resolve("pbjs.log"));
        var outputs=new TreeMap<String,ContentDigest>();for(String name:List.of("users.js","users.d.ts")){String target="generated/web/"+name,text=Files.readString(js.resolve(name));files.put(target,text);outputs.put(target,ContentDigest.sha256Utf8(text));}
        try(var sources=Files.walk(server)){for(Path source:sources.filter(p->p.toString().endsWith(".java")).sorted().toList()){String target="generated/server/"+server.relativize(source).toString().replace('\\','/'),text=Files.readString(source);files.put(target,text);outputs.put(target,ContentDigest.sha256Utf8(text));}}
        files.put("api/generation.json",CanonicalJson.write(Map.of("schema","authored-native-generation:m8.4-v1","source",Map.of("path","api/users.proto","digest",ContentDigest.sha256Utf8(files.get("api/users.proto"))),"outputs",outputs,"generators",pins,"client",GraphJson.read(Files.readString(js.resolve("generator.json")),GraphJson.Limits.defaults()),"descriptor",Map.of("digest",ContentDigest.sha256(Files.readAllBytes(descriptor)),"data",Base64.getEncoder().encodeToString(Files.readAllBytes(descriptor))))));
        // Compilation is a control for these first-party generated sources, with annotation processors disabled.
        Path classes=Files.createDirectory(work.resolve("classes"));var argv=new ArrayList<String>(List.of("-proc:none","-cp",System.getProperty("java.class.path"),"-d",classes.toString()));try(var sources=Files.walk(server)){argv.addAll(sources.filter(p->p.toString().endsWith(".java")).map(Path::toString).toList());}
        var log=new ByteArrayOutputStream();assertEquals(0,Objects.requireNonNull(javax.tools.ToolProvider.getSystemJavaCompiler()).run(null,log,log,argv.toArray(String[]::new)),"Native generated Java server must compile");
        assertTrue(files.keySet().stream().anyMatch(path->path.endsWith("UsersGrpc.java")));assertFalse(outputs.isEmpty());
    }
    private static void run(List<String> command,Path log)throws Exception{var process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();if(!process.waitFor(45,TimeUnit.SECONDS)){process.destroyForcibly();fail("First-party generator watchdog");}assertEquals(0,process.exitValue(),"Native generator failed; inspect local "+log.getFileName());}
}
