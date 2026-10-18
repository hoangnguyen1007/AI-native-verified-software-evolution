package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GitSnapshotAcquirerTest {
    @TempDir Path temp;
    static void git(Path cwd,String...args)throws Exception{
        var command=new ArrayList<>(List.of("git","-c","credential.helper=","-c","core.hooksPath="+cwd.resolve("none")));command.addAll(List.of(args));
        var builder=new ProcessBuilder(command).directory(cwd.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("GIT_CONFIG_NOSYSTEM","1");builder.environment().put("GIT_CONFIG_GLOBAL",cwd.resolve("none").toString());
        var process=builder.start();assertEquals(0,process.waitFor());
    }
    static String object(Path source,String type,byte[] bytes)throws Exception{
        var all=new ByteArrayOutputStream();all.write((type+" "+bytes.length+"\0").getBytes(java.nio.charset.StandardCharsets.US_ASCII));all.write(bytes);
        String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(all.toByteArray()));
        Path path=source.resolve(".git/objects/"+hash.substring(0,2)+"/"+hash.substring(2));Files.createDirectories(path.getParent());
        try(var out=new java.util.zip.DeflaterOutputStream(Files.newOutputStream(path))){out.write(all.toByteArray());}return hash;
    }
    @Test void hydratesPinnedObjectsWithoutCheckoutAndReplaysAfterRestart()throws Exception{
        Path source=Files.createDirectory(temp.resolve("source"));git(source,"init","--quiet");
        String blob=object(source,"blob","message X {}".getBytes());var treeBytes=new ByteArrayOutputStream();
        for(String name:List.of("CON:x","schema.proto")){treeBytes.write(("100644 "+name+"\0").getBytes());treeBytes.write(HexFormat.of().parseHex(blob));}
        treeBytes.write("100644 ".getBytes());treeBytes.write(255);treeBytes.write(0);treeBytes.write(HexFormat.of().parseHex(blob));
        String tree=object(source,"tree",treeBytes.toByteArray());String commit=object(source,"commit",("tree "+tree+"\nauthor Fixture <fixture@example.invalid> 0 +0000\ncommitter Fixture <fixture@example.invalid> 0 +0000\n\nfixture\n").getBytes());
        Files.writeString(source.resolve(".git/refs/heads/master"),commit+"\n");Files.writeString(source.resolve(".git/HEAD"),"ref: refs/heads/master\n");
        Path workspace=Files.createDirectory(temp.resolve("workspace"));git(workspace,"clone","--bare","--quiet",source.toString(),"objects.git");
        var cas=new StreamingContentStore(temp.resolve("cas"));URI uri=URI.create("https://git.example.invalid/repo.git");var request=new StreamingAcquisitionRequest(RepositoryIdentity.fromCanonicalCoordinate(uri.toString()),Optional.empty(),false,List.of());
        try(var store=new SqliteInventoryStore(temp.resolve("metadata"),cas)){
            var job=store.create("git",CanonicalJson.sha256(List.of(request.identity(),uri.toString(),"HEAD")),Optional.empty());
            store.transition("git",job.revision(),JobJournal.State.RUNNING,"PINNED_COMMIT",Optional.of(cas.put(new ByteArrayInputStream(commit.getBytes()),()->false)));
            var receipt=new GitSnapshotAcquirer(cas,store,new GitHostPolicy(),"git",1000).acquire(workspace,"git",uri,"HEAD",request,()->false);
            assertEquals(JobJournal.State.COMPLETE,receipt.state(),receipt.reason());assertEquals(3,receipt.manifest().orElseThrow().files());assertTrue(receipt.manifest().orElseThrow().compatibleV1Snapshot().isEmpty());
            assertEquals(1,store.page("git",10,Optional.empty()).entries().stream().filter(e->e.path().utf8().isEmpty()).count());
            assertFalse(Files.exists(workspace.resolve("schema.proto")));assertEquals("CON:x",store.page("git",10,Optional.empty()).entries().getFirst().path().utf8().orElseThrow());
        }
        try(var store=new SqliteInventoryStore(temp.resolve("metadata"),cas)){
            assertEquals(JobJournal.State.COMPLETE,new GitSnapshotAcquirer(cas,store,new GitHostPolicy(),"missing-git",1000).acquire(workspace,"git",uri,"HEAD",request,()->false).state());
        }
    }
    @Test void hostPolicyChecksAllResolvedAddressesAndHasNoVendorWhitelist()throws Exception{
        var policy=new GitHostPolicy(Set.of(),Map.of(),h->new InetAddress[]{InetAddress.getByName("8.8.8.8")},u->Optional.empty());
        assertEquals("self-hosted.example",policy.endpoint(GitHostPolicy.syntax("https://self-hosted.example/repo.git")).uri().getHost());
        var rebinding=new GitHostPolicy(Set.of(),Map.of(),h->new InetAddress[]{InetAddress.getByName("8.8.8.8"),InetAddress.getByName("127.0.0.1")},u->Optional.empty());
        assertThrows(IOException.class,()->rebinding.endpoint(URI.create("https://host.example/repo")));
        for(String address:List.of("127.0.0.1","10.0.0.1","169.254.169.254","100.64.0.1","::1","fc00::1"))assertFalse(GitHostPolicy.publicAddress(InetAddress.getByName(address)));
        for(String invalid:List.of("https://user:token@example.com/repo","https://example.com/a/../repo","http://example.com/repo","https://example.com/repo?token=x"))assertThrows(IllegalArgumentException.class,()->GitHostPolicy.syntax(invalid));
        assertThrows(IOException.class,()->policy.endpoint(URI.create("ssh://git@example.com/repo.git")));
    }
}
