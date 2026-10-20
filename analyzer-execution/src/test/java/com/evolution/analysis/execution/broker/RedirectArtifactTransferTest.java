package com.evolution.analysis.execution.broker;

import com.evolution.analysis.acquisition.streaming.ContentHandle;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.filesystem.PinnedHttps;
import java.io.*;
import java.net.URI;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RedirectArtifactTransferTest {
    static final URI SOURCE=URI.create("https://downloads.example/distributions/tool.zip");
    static final ContentHandle PIN=new ContentHandle(ContentDigest.sha256Utf8("abc"),3);
    static List<RedirectArtifactTransfer.HopRule> rules(){return List.of(new RedirectArtifactTransfer.HopRule(URI.create("https://downloads.example/distributions/"),false),new RedirectArtifactTransfer.HopRule(URI.create("https://cdn.example/assets/"),true));}
    static PinnedHttps.Response response(int status,Map<String,String> headers,String body){return new PinnedHttps.Response(status,headers,new ByteArrayInputStream(body.getBytes()));}
    @Test void followsOnlyRegisteredHopsAndNeverLeaksSignedQueryIntoAudit()throws Exception{
        var hops=new ArrayList<RedirectArtifactTransfer.Hop>();var calls=new ArrayList<URI>();
        var transfer=new RedirectArtifactTransfer(Map.of(SOURCE,PIN),rules(),(uri,cancelled)->{
            calls.add(uri);return uri.equals(SOURCE)?response(307,Map.of("Location","https://cdn.example/assets/tool.zip?token=secret-token"),""):response(200,Map.of(),"abc");
        },hops::add);
        try(var bytes=transfer.open(SOURCE,()->false)){assertEquals("abc",new String(bytes.readAllBytes()));}
        assertEquals(2,calls.size());assertEquals(2,hops.size());assertFalse(hops.toString().contains("secret-token"));assertTrue(hops.stream().allMatch(h->h.endpoint().getRawQuery()==null));
    }
    @Test void downgradePrivateHostForeignPathAndUnregisteredQueryAreDeniedBeforeNextHop()throws Exception{
        for(String target:List.of("http://cdn.example/assets/tool.zip","https://127.0.0.1/private","https://cdn.example/foreign/tool.zip","https://downloads.example/distributions/tool.zip?token=forbidden","https://user:pass@cdn.example/assets/tool.zip")){
            var calls=new ArrayList<URI>();var transfer=new RedirectArtifactTransfer(Map.of(SOURCE,PIN),rules(),(uri,c)->{calls.add(uri);return response(302,Map.of("location",target),"");},h->{});
            assertThrows(IOException.class,()->transfer.open(SOURCE,()->false));assertEquals(List.of(SOURCE),calls);
        }
    }
    @Test void requiresPinnedOriginalAndRejectsLoopPoisonAndTruncation()throws Exception{
        var loop=new RedirectArtifactTransfer(Map.of(SOURCE,PIN),rules(),(uri,c)->response(302,Map.of("location",SOURCE.toString()),""),h->{});
        assertThrows(IOException.class,()->loop.open(SOURCE,()->false));
        assertThrows(IOException.class,()->loop.open(URI.create("https://downloads.example/distributions/other.zip"),()->false));
        for(String body:List.of("abd","ab","abcd")){
            var transfer=new RedirectArtifactTransfer(Map.of(SOURCE,PIN),rules(),(uri,c)->response(200,Map.of(),body),h->{});
            try(var bytes=transfer.open(SOURCE,()->false)){assertThrows(IOException.class,bytes::readAllBytes);}
        }
    }
}
