package com.evolution.analysis.execution.broker;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.execution.ExecutionGraph;
import com.evolution.analysis.filesystem.*;
import java.io.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Scoped byte acquisition. Resolution/model claims are not inferred from download success. */
public final class ArtifactBroker {
    public record Rule(URI repository) {
        public Rule {Objects.requireNonNull(repository);validate(repository);if(repository.getRawQuery()!=null)throw new IllegalArgumentException("QUERY_NOT_AUTHORIZED");}
        public boolean permits(URI endpoint){
            return Objects.equals(repository.getHost(),endpoint.getHost())&&port(repository)==port(endpoint)
                    &&(endpoint.getPath().equals(repository.getPath())||endpoint.getPath().startsWith(repository.getPath().endsWith("/")?repository.getPath():repository.getPath()+"/"));
        }
    }
    public enum State { ACQUIRED, REUSED, DENIED, UNAVAILABLE, REJECTED }
    /** endpoint is a redacted display URI, never a credential-bearing request selector. */
    public record Material(String attempt,long fence,ContentDigest requestIdentity,Optional<URI> endpoint,State state,Optional<ContentHandle> content,String reason) {
        public Material {ExecutionGraph.text(attempt);if(fence<1)throw new IllegalArgumentException("INVALID_MATERIAL_FENCE");Objects.requireNonNull(requestIdentity);Objects.requireNonNull(endpoint);Objects.requireNonNull(state);Objects.requireNonNull(content);Objects.requireNonNull(reason);if((state==State.ACQUIRED||state==State.REUSED)&&(endpoint.isEmpty()||content.isEmpty()))throw new IllegalArgumentException("MATERIAL_CONTENT_REQUIRED");}
    }
    @FunctionalInterface public interface Recorder {void append(Material material)throws IOException;}
    @FunctionalInterface public interface Transfer {InputStream open(URI endpoint,BooleanSupplier cancelled)throws IOException;}
    public static final class Grant {
        private final ArtifactBroker owner;
        private final String attempt;private final long fence;private final Instant expiry;private long remaining;private boolean revoked;
        private Grant(ArtifactBroker owner,String attempt,long fence,Instant expiry,long remaining){this.owner=owner;this.attempt=attempt;this.fence=fence;this.expiry=expiry;this.remaining=remaining;}
    }
    private final List<Rule> rules;private final boolean offline;private final RepositoryContentStore content;private final Transfer transfer;private final Recorder recorder;private final Clock clock;
    public ArtifactBroker(List<Rule> rules,boolean offline,RepositoryContentStore content,GitHostPolicy hostPolicy,Recorder recorder){
        this(rules,offline,content,(uri,cancelled)->new PinnedHttps(hostPolicy).request(uri,"GET",Map.of(),new byte[0],cancelled),recorder,Clock.systemUTC());
    }
    public ArtifactBroker(List<Rule> rules,boolean offline,RepositoryContentStore content,Transfer transfer,Recorder recorder,Clock clock){this.rules=List.copyOf(rules);this.offline=offline;this.content=Objects.requireNonNull(content);this.transfer=Objects.requireNonNull(transfer);this.recorder=Objects.requireNonNull(recorder);this.clock=Objects.requireNonNull(clock);}
    public synchronized Grant grant(String attempt,long fence,Duration duration,long bytes){
        ExecutionGraph.text(attempt);if(fence<1||duration.isNegative()||duration.isZero()||bytes<0)throw new IllegalArgumentException("INVALID_BROKER_GRANT");
        return new Grant(this,attempt,fence,clock.instant().plus(duration),bytes);
    }
    public synchronized void revoke(Grant grant){if(grant!=null&&grant.owner==this)grant.revoked=true;}
    /** Audit a rejected facade request; this never authorizes transfer, even after revocation. */
    void denied(Grant grant,URI request,int status)throws IOException {
        if(grant==null||grant.owner!=this)throw new IOException("BROKER_GRANT_INVALID");
        record(grant,Objects.requireNonNull(request),State.DENIED,Optional.empty(),"HTTP_REQUEST_DENIED_"+status);
    }
    private synchronized void active(Grant grant,long fence)throws IOException {
        if(grant==null||grant.owner!=this||grant.revoked||grant.fence!=fence||!clock.instant().isBefore(grant.expiry))throw new IOException("BROKER_GRANT_INVALID");
    }
    private synchronized void consume(Grant grant,long fence,int bytes)throws IOException {
        active(grant,fence);if(bytes>grant.remaining)throw new IOException("BROKER_RESOURCE_EXHAUSTED");grant.remaining-=bytes;
    }
    public Material acquire(Grant grant,long fence,URI endpoint,Optional<ContentHandle> expected,BooleanSupplier cancelled)throws IOException {
        Objects.requireNonNull(expected);Objects.requireNonNull(cancelled);active(grant,fence);
        if(cancelled.getAsBoolean())return record(grant,endpoint,State.UNAVAILABLE,Optional.empty(),"BROKER_CANCELLED");
        try{validate(endpoint);}catch(IllegalArgumentException denied){return record(grant,endpoint,State.DENIED,Optional.empty(),"ENDPOINT_DENIED");}
        if(rules.stream().noneMatch(rule->rule.permits(endpoint)))return record(grant,endpoint,State.DENIED,Optional.empty(),"ENDPOINT_DENIED");
        if(expected.isPresent()){
            boolean verified=false;try{verified=content.verify(expected.orElseThrow());}catch(IOException unavailable){record(grant,endpoint,State.UNAVAILABLE,Optional.empty(),"CACHE_READ_UNAVAILABLE");}
            if(cancelled.getAsBoolean())return record(grant,endpoint,State.UNAVAILABLE,Optional.empty(),"BROKER_CANCELLED");
            if(verified)return record(grant,endpoint,State.REUSED,expected,"");
        }
        if(offline)return record(grant,endpoint,State.UNAVAILABLE,Optional.empty(),"OFFLINE_MATERIAL_MISSING");
        State outcome;Optional<ContentHandle> capturedContent;String outcomeReason;
        try(var remote=transfer.open(endpoint,cancelled)){
            var limited=new FilterInputStream(remote){
                private void check(int count)throws IOException{if(cancelled.getAsBoolean())throw new IOException("BROKER_CANCELLED");consume(grant,fence,Math.max(count,0));}
                @Override public int read()throws IOException{int b=in.read();check(b<0?0:1);return b;}
                @Override public int read(byte[] b,int offset,int length)throws IOException{int n=in.read(b,offset,length);check(n);return n;}
            };
            var captured=content.put(limited,cancelled);active(grant,fence);
            boolean mismatch=expected.isPresent()&&!expected.orElseThrow().equals(captured);
            outcome=mismatch?State.REJECTED:State.ACQUIRED;capturedContent=Optional.of(captured);outcomeReason=mismatch?"MATERIAL_DIGEST_MISMATCH":"";
        }catch(java.util.concurrent.CancellationException cancelledWrite){
            outcome=State.UNAVAILABLE;capturedContent=Optional.empty();outcomeReason="BROKER_CANCELLED";
        }catch(IOException failed){
            String message=failed.getMessage();String reason=message!=null&&Set.of("BROKER_CANCELLED","BROKER_RESOURCE_EXHAUSTED","BROKER_GRANT_INVALID","ENDPOINT_DENIED").contains(message)?message:"BROKER_TRANSFER_UNAVAILABLE";
            outcome=State.UNAVAILABLE;capturedContent=Optional.empty();outcomeReason=reason;
        }
        return record(grant,endpoint,outcome,capturedContent,outcomeReason);
    }
    private Material record(Grant grant,URI uri,State state,Optional<ContentHandle> handle,String reason)throws IOException{
        if(state==State.ACQUIRED||state==State.REUSED)active(grant,grant.fence);
        Optional<URI> display=Optional.empty();
        if(!uri.isOpaque()){
            try{display=Optional.of(new URI(uri.getScheme(),null,uri.getHost(),uri.getPort(),uri.getPath(),null,null));}
            catch(java.net.URISyntaxException invalid){/* The original request identity remains, without leaking raw credentials. */}
        }
        var material=new Material(grant.attempt,grant.fence,ContentDigest.sha256Utf8(uri.toASCIIString()),display,state,handle,reason);recorder.append(material);return material;
    }
    private static int port(URI uri){return uri.getPort()==-1?443:uri.getPort();}
    private static void validate(URI uri){
        if(uri==null||!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getFragment()!=null||uri.getRawQuery()!=null||port(uri)<1||port(uri)>65535||!uri.normalize().equals(uri))throw new IllegalArgumentException("ENDPOINT_DENIED");
        String path=uri.getPath();if(path==null||!path.startsWith("/")||path.indexOf('\\')>=0||path.indexOf('\0')>=0||path.indexOf('%')>=0||uri.getRawPath().toLowerCase(Locale.ROOT).matches(".*%(2f|5c|00).*"))throw new IllegalArgumentException("ENDPOINT_DENIED");
        for(String component:path.split("/"))if(component.equals(".")||component.equals(".."))throw new IllegalArgumentException("ENDPOINT_DENIED");
    }
}
