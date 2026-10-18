package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LfsObjectAcquirerTest {
    @TempDir Path root;
    @Test void validatedCachedObjectClosesPointerWithoutNetworkOrReacquisition()throws Exception{
        var cas=new StreamingContentStore(root);var actual=cas.put(new ByteArrayInputStream("actual large object".getBytes()),()->false);
        String pointer="version https://git-lfs.github.com/spec/v1\noid "+actual.digest().value()+"\nsize "+actual.size()+"\n";
        var raw=cas.put(new ByteArrayInputStream(pointer.getBytes()),()->false);var lfs=new LfsObjectAcquirer(cas,new GitHostPolicy());
        var parsed=lfs.pointer(raw).orElseThrow();assertEquals(raw,parsed.pointerBytes());assertEquals(actual,lfs.acquire(URI.create("https://never-resolve.invalid/repo.git"),parsed,()->false));
        assertThrows(IOException.class,()->lfs.pointer(cas.put(new ByteArrayInputStream(pointer.replace("size "+actual.size(),"size -1").getBytes()),()->false)));
    }
    @Test void brokerRejectsCredentialsWithoutAnExplicitReadOnlyReference()throws Exception{
        var policy=new GitHostPolicy(java.util.Set.of(),java.util.Map.of(),java.net.InetAddress::getAllByName,uri->java.util.Optional.of("Bearer test-value"));
        assertThrows(IOException.class,()->policy.authorization(URI.create("https://example.com/repo")));
        assertThrows(IOException.class,()->new PinnedHttps(policy).request(URI.create("/relative"),"GET",java.util.Map.of(),new byte[0],()->false));
    }
}
