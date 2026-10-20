package com.evolution.analysis.execution.broker;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.filesystem.*;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Controller for authored native fixtures only; it does not qualify or submit repository executions. */
public final class BrokerFixtureMain {
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]);boolean offline=args.length>1&&args[1].equals("offline");Files.createDirectories(root);
        var store=new StreamingContentStore(root.resolve("cas"));var pins=new TreeMap<URI,ContentHandle>();
        Path closure=root.resolve("fixture-closure.json");
        if(offline){
            if(Files.size(closure)>1048576)throw new IOException("FIXTURE_CLOSURE_TOO_LARGE");
            var map=GraphJson.object(GraphJson.read(Files.readString(closure),new GraphJson.Limits(1048576,16,100000)));
            for(var entry:map.entrySet()){
                var value=GraphJson.object(entry.getValue());pins.put(URI.create(entry.getKey()),new ContentHandle(new ContentDigest(GraphJson.string(value.get("digest"))),((java.math.BigDecimal)value.get("size")).longValueExact()));
            }
        }
        try(var channel=java.nio.channels.FileChannel.open(root.resolve(offline?"offline-materials.ndjson":"online-materials.ndjson"),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            ArtifactBroker.Recorder recorder=material->{
                byte[] line=(CanonicalJson.write(material)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                synchronized(channel){var buffer=java.nio.ByteBuffer.wrap(line);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
            };
            URI repository=URI.create("https://repo.maven.apache.org/maven2/");
            var broker=new ArtifactBroker(List.of(new ArtifactBroker.Rule(repository)),offline,store,new GitHostPolicy(),recorder);
            var grant=broker.grant("authored-native-fixture",1,Duration.ofMinutes(20),134217728);
            try(var server=new ArtifactBrokerServer(broker,grant,1,Map.of("central",repository),store,pins)){
                System.out.println("READY "+server.baseUri().getPort());System.out.flush();
                System.in.read();
                if(!offline){
                    var captured=new TreeMap<String,ContentHandle>();server.capturedClosure().forEach((uri,handle)->captured.put(uri.toString(),handle));
                    Files.writeString(closure,CanonicalJson.write(captured),StandardOpenOption.CREATE_NEW);
                    System.out.println("CAPTURED "+captured.size()+" "+CanonicalJson.sha256(captured).value());
                }
            }
        }
    }
}
