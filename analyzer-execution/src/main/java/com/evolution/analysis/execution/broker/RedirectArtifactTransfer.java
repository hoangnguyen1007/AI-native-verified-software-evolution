package com.evolution.analysis.execution.broker;

import com.evolution.analysis.acquisition.streaming.ContentHandle;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.filesystem.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Public, publisher-pinned tool downloads only. The default repository transfer still rejects redirects.
 * Every hop uses a fresh DNS-pinned TLS exchange with no credential provider; query tokens are never logged.
 */
public final class RedirectArtifactTransfer implements ArtifactBroker.Transfer {
    public record HopRule(URI repository,boolean queryAllowed) {
        public HopRule {new ArtifactBroker.Rule(repository);}
        boolean permits(URI endpoint){return (queryAllowed||endpoint.getRawQuery()==null)&&new ArtifactBroker.Rule(repository).permits(endpoint);}
    }
    public record Hop(ContentDigest uriIdentity,URI endpoint,int status,String reason) {}
    @FunctionalInterface public interface Exchange {PinnedHttps.Response get(URI uri,BooleanSupplier cancelled)throws IOException;}
    @FunctionalInterface public interface Recorder {void append(Hop hop)throws IOException;}
    private final Map<URI,ContentHandle> pins;
    private final List<HopRule> rules;
    private final Exchange exchange;
    private final Recorder recorder;
    public RedirectArtifactTransfer(Map<URI,ContentHandle> pins,List<HopRule> rules,Recorder recorder){
        this(pins,rules,(uri,c)->new PinnedHttps(new GitHostPolicy()).exchange(uri,"GET",Map.of(),new byte[0],c),recorder);
    }
    public RedirectArtifactTransfer(Map<URI,ContentHandle> pins,List<HopRule> rules,Exchange exchange,Recorder recorder){
        this.pins=Map.copyOf(pins);this.pins.keySet().forEach(ArtifactBroker.Rule::new);
        this.rules=List.copyOf(rules);this.exchange=Objects.requireNonNull(exchange);this.recorder=Objects.requireNonNull(recorder);
    }
    @Override public InputStream open(URI original,BooleanSupplier cancelled)throws IOException {
        ContentHandle pin=pins.get(original);if(pin==null)throw new IOException("TOOL_ARTIFACT_PIN_REQUIRED");
        URI endpoint=original;var visited=new HashSet<URI>();
        for(int hop=0;hop<8;hop++){
            if(cancelled.getAsBoolean())throw new IOException("BROKER_CANCELLED");
            if(!allowed(endpoint)||!visited.add(endpoint)){
                record(endpoint,0,"REDIRECT_ENDPOINT_DENIED");throw new IOException("REDIRECT_ENDPOINT_DENIED");
            }
            PinnedHttps.Response response;
            try{response=exchange.get(endpoint,cancelled);}catch(IOException unavailable){record(endpoint,0,"REDIRECT_HOP_UNAVAILABLE");throw new IOException("REDIRECT_HOP_UNAVAILABLE");}
            try{
                record(endpoint,response.status(),"");
                if(response.status()>=200&&response.status()<300)return pinned(response.body(),pin,cancelled);
                if(!Set.of(301,302,303,307,308).contains(response.status()))throw new IOException("REDIRECT_HOP_UNAVAILABLE");
                var locations=response.headers().entrySet().stream().filter(e->e.getKey().equalsIgnoreCase("location")).toList();
                if(locations.size()!=1)throw new IOException("REDIRECT_LOCATION_INVALID");
                String location=locations.getFirst().getValue();if(location.length()>8192)throw new IOException("REDIRECT_LOCATION_INVALID");
                try{endpoint=endpoint.resolve(new URI(location));}catch(URISyntaxException|IllegalArgumentException malformed){throw new IOException("REDIRECT_LOCATION_INVALID");}
            }catch(IOException|RuntimeException failed){response.close();throw failed;}
            response.close();
        }
        throw new IOException("REDIRECT_HOP_LIMIT");
    }
    private boolean allowed(URI uri){
        if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawFragment()!=null||!uri.normalize().equals(uri)||(uri.getPort()!=-1&&uri.getPort()!=443))return false;
        String path=uri.getPath();if(path==null||!path.startsWith("/")||path.indexOf('\\')>=0||path.indexOf('\0')>=0||path.indexOf('%')>=0||uri.getRawPath().toLowerCase(Locale.ROOT).matches(".*%(2f|5c|00).*"))return false;
        for(String component:path.split("/"))if(component.equals(".")||component.equals(".."))return false;
        return rules.stream().anyMatch(rule->rule.permits(uri));
    }
    private void record(URI uri,int status,String reason)throws IOException {
        URI redacted;
        try{redacted=new URI(uri.getScheme(),null,uri.getHost(),uri.getPort(),uri.getPath(),null,null);}catch(URISyntaxException invalid){throw new IOException("REDIRECT_ENDPOINT_DENIED");}
        recorder.append(new Hop(ContentDigest.sha256Utf8(uri.toASCIIString()),redacted,status,reason));
    }
    private static InputStream pinned(InputStream input,ContentHandle expected,BooleanSupplier cancelled){
        MessageDigest digest;try{digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        return new FilterInputStream(input){
            long bytes;boolean ended;
            private void check(int count)throws IOException{
                if(cancelled.getAsBoolean())throw new IOException("BROKER_CANCELLED");
                if(count<0&&!ended){ended=true;var actual=new ContentDigest("sha256:"+HexFormat.of().formatHex(digest.digest()));if(bytes!=expected.size()||!actual.equals(expected.digest()))throw new IOException("ARTIFACT_PIN_MISMATCH");}
                if(bytes>expected.size())throw new IOException("ARTIFACT_PIN_MISMATCH");
            }
            @Override public int read()throws IOException{int value=in.read();if(value>=0){bytes++;digest.update((byte)value);}check(value<0?-1:1);return value;}
            @Override public int read(byte[] buffer,int offset,int length)throws IOException{int count=in.read(buffer,offset,length);if(count>0){bytes=Math.addExact(bytes,count);digest.update(buffer,offset,count);}check(count);return count;}
        };
    }
}
