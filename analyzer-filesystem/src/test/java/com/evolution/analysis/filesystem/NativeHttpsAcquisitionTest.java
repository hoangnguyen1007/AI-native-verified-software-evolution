package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NativeHttpsAcquisitionTest {
    @TempDir Path root;
    void keytool(String...args)throws Exception{
        String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"keytool.exe":"keytool").toString();
        var command=new ArrayList<>(List.of(executable));command.addAll(List.of(args));var process=new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();assertTrue(process.waitFor(15,TimeUnit.SECONDS));assertEquals(0,process.exitValue());
    }
    @Test void nativeGitRetriesResetFallsBackFromShallowAndClosesLfsAndSubmodule()throws Exception{
        Path keystore=root.resolve("tls.p12"),ca=root.resolve("tls.pem");
        keytool("-genkeypair","-alias","fixture","-keyalg","RSA","-dname","CN=fixture.test","-ext","SAN=dns:fixture.test","-validity","1","-storetype","PKCS12","-keystore",keystore.toString(),"-storepass","fixture-password","-keypass","fixture-password","-noprompt");
        keytool("-exportcert","-rfc","-alias","fixture","-keystore",keystore.toString(),"-storepass","fixture-password","-file",ca.toString());
        var keys=KeyStore.getInstance("PKCS12");try(var input=Files.newInputStream(keystore)){keys.load(input,"fixture-password".toCharArray());}
        var km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,"fixture-password".toCharArray());var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(keys);
        var tls=SSLContext.getInstance("TLS");tls.init(km.getKeyManagers(),tm.getTrustManagers(),new SecureRandom());
        var server=HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);server.setHttpsConfigurator(new HttpsConfigurator(tls));var threads=Executors.newFixedThreadPool(4);server.setExecutor(threads);
        int port=server.getAddress().getPort();String base="https://fixture.test:"+port;byte[] lfs="actual-lfs-payload".getBytes();String oid=ContentDigest.sha256(lfs).value().substring(7);
        Path child=Files.createDirectory(root.resolve("child"));GitSnapshotAcquirerTest.git(child,"init","--quiet");Files.writeString(child.resolve("main.py"),"def child(): pass\n");GitSnapshotAcquirerTest.git(child,"add","--all");GitSnapshotAcquirerTest.git(child,"-c","user.name=Fixture","-c","user.email=fixture@example.invalid","commit","--quiet","-m","child");String childCommit=Files.readString(child.resolve(".git/refs/heads/master")).strip();GitSnapshotAcquirerTest.git(child,"update-server-info");
        Path source=Files.createDirectory(root.resolve("source"));GitSnapshotAcquirerTest.git(source,"init","--quiet");Files.writeString(source.resolve("schema.proto"),"message Root {}\n");
        Files.writeString(source.resolve("asset.bin"),"version https://git-lfs.github.com/spec/v1\noid sha256:"+oid+"\nsize "+lfs.length+"\n");
        Files.writeString(source.resolve(".gitmodules"),"[submodule \"child\"]\n path = module\n url = "+base+"/child.git\n");
        byte[] large=new byte[2_000_000];new Random(17).nextBytes(large);Files.write(source.resolve("large.bin"),large);GitSnapshotAcquirerTest.git(source,"add","--all");GitSnapshotAcquirerTest.git(source,"update-index","--add","--cacheinfo","160000,"+childCommit+",module");GitSnapshotAcquirerTest.git(source,"-c","user.name=Fixture","-c","user.email=fixture@example.invalid","commit","--quiet","-m","root");GitSnapshotAcquirerTest.git(source,"update-server-info");
        var reset=new AtomicBoolean();var requests=new AtomicInteger();var stalled=new CountDownLatch(1);var releaseStall=new CountDownLatch(1);
        server.createContext("/stall",exchange->{stalled.countDown();try{releaseStall.await(10,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}finally{exchange.close();}});
        server.createContext("/",exchange->{
            requests.incrementAndGet();String path=exchange.getRequestURI().getPath();
            try{
                byte[] bytes;String media="application/octet-stream";
                if(path.equals("/redirect")){exchange.getResponseHeaders().set("Location","https://127.0.0.1/private");exchange.sendResponseHeaders(302,-1);return;}
                if(path.equals("/repo.git/info/lfs/objects/batch")){
                    exchange.getRequestBody().readNBytes(65536);media="application/vnd.git-lfs+json";bytes=CanonicalJson.write(Map.of("objects",List.of(Map.of("oid",oid,"size",lfs.length,"actions",Map.of("download",Map.of("href",base+"/lfs-object")))))).getBytes(StandardCharsets.UTF_8);
                }else if(path.equals("/lfs-object"))bytes=lfs;
                else{
                    Path repository=path.startsWith("/repo.git/")?source.resolve(".git"):path.startsWith("/child.git/")?child.resolve(".git"):null;
                    if(repository==null){exchange.sendResponseHeaders(404,-1);return;}String relative=path.substring(path.indexOf('/',1)+1);Path file=repository.resolve(relative).normalize();
                    if(!file.startsWith(repository)||!Files.isRegularFile(file)){exchange.sendResponseHeaders(404,-1);return;}bytes=Files.readAllBytes(file);if(relative.equals("info/refs"))media="text/plain";
                }
                exchange.getResponseHeaders().set("Content-Type",media);exchange.sendResponseHeaders(200,bytes.length);
                if(path.startsWith("/repo.git/objects/")&&bytes.length>100_000&&reset.compareAndSet(false,true)){exchange.getResponseBody().write(bytes,0,bytes.length/2);return;}
                for(int offset=0;offset<bytes.length;offset+=8192){exchange.getResponseBody().write(bytes,offset,Math.min(8192,bytes.length-offset));exchange.getResponseBody().flush();if(bytes.length>100_000)try{Thread.sleep(2);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();break;}}
            }finally{exchange.close();}
        });server.start();
        var policy=new GitHostPolicy(Set.of("fixture.test"),Map.of(),host->new InetAddress[]{InetAddress.getLoopbackAddress()},uri->Optional.empty(),Optional.of(ca),tls);
        URI stalledUri=URI.create(base+"/stall");Path stalledWorkspace=Files.createDirectory(root.resolve("stalled-workspace"));
        try(var nativeProcess=new SafeGitProcess("git",stalledWorkspace,List.of("ls-remote",stalledUri.toString()),Optional.of(policy.endpoint(stalledUri)),policy,()->false,1500)){
            assertTrue(stalled.await(5,TimeUnit.SECONDS),"The request must reach the stalled TLS endpoint");
            // Native curl's low-speed guard may win before the controller's idle lease.
            try{assertNotEquals(0,nativeProcess.finish(),"A stalled transfer cannot succeed");}
            catch(IOException idle){assertEquals("GIT_IDLE_OR_CANCELLED",idle.getMessage());}
            assertFalse(nativeProcess.process.isAlive());
        }finally{releaseStall.countDown();}
        var cas=new StreamingContentStore(root.resolve("cas"));URI uri=URI.create(base+"/repo.git");var request=new StreamingAcquisitionRequest(RepositoryIdentity.fromCanonicalCoordinate(uri.toString()),Optional.empty(),false,List.of());
        try(var store=new SqliteInventoryStore(root.resolve("metadata"),cas)){
            var receipt=new GitSnapshotAcquirer(cas,store,policy,"git",5000).acquire(root.resolve("workspace"),"root",uri,"HEAD",request,()->false);
            assertEquals(JobJournal.State.COMPLETE,receipt.state(),receipt.reason());assertTrue(reset.get(),"Reset injection must actually run");assertEquals(5,receipt.manifest().orElseThrow().files());
            var asset=store.observation("root",LogicalPath.utf8("asset.bin")).orElseThrow().entry();assertEquals(ContentDigest.sha256(lfs),asset.content().orElseThrow().digest());assertTrue(asset.target().orElseThrow().startsWith("lfs-pointer:sha256:"));
            assertTrue(store.observation("root",LogicalPath.utf8("module/main.py")).isPresent());assertTrue(store.observation("root",LogicalPath.utf8("module")).orElseThrow().entry().target().orElseThrow().startsWith("closure:sha256:"));
            assertThrows(IOException.class,()->new PinnedHttps(policy).request(URI.create(base+"/redirect"),"GET",Map.of(),new byte[0],()->false));
        }finally{server.stop(0);threads.shutdownNow();}
        int before=requests.get();try(var store=new SqliteInventoryStore(root.resolve("metadata"),cas)){
            assertEquals(JobJournal.State.COMPLETE,new GitSnapshotAcquirer(cas,store,policy,"missing-git",1000).acquire(root.resolve("workspace"),"root",uri,"HEAD",request,()->false).state());assertEquals(before,requests.get());
        }
    }
}
