package com.evolution.analysis.filesystem;

import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.util.*;

/** Operator policy, never repository-controlled config. Every resolution is checked and pinned per connection. */
public final class GitHostPolicy {
    @FunctionalInterface public interface Resolver { InetAddress[] resolve(String host) throws UnknownHostException; }
    public record CredentialReference(String id,boolean readOnly){public CredentialReference{if(id==null||!id.matches("[A-Za-z0-9._-]{1,128}"))throw new IllegalArgumentException("Invalid credential reference");}}
    @FunctionalInterface public interface Credentials {
        Optional<String> authorization(URI endpoint) throws IOException;
        default Optional<CredentialReference> reference(URI endpoint){return Optional.empty();}
    }
    public record SshIdentity(Path key,Path knownHosts,String executable) {}
    public record Endpoint(URI uri,List<InetAddress> addresses) {}
    private final Set<String> internalHosts;
    private final Map<String,SshIdentity> ssh;
    private final Resolver resolver;
    private final Credentials credentials;
    private final Optional<Path> certificateAuthority;
    private final javax.net.ssl.SSLContext tls;
    public GitHostPolicy(){this(Set.of(),Map.of(),InetAddress::getAllByName,uri->Optional.empty());}
    public GitHostPolicy(Set<String> internalHosts,Map<String,SshIdentity> ssh,Resolver resolver,Credentials credentials){
        this(internalHosts,ssh,resolver,credentials,Optional.empty(),defaultTls());
    }
    public GitHostPolicy(Set<String> internalHosts,Map<String,SshIdentity> ssh,Resolver resolver,Credentials credentials,Optional<Path> certificateAuthority,javax.net.ssl.SSLContext tls){
        this.internalHosts=Set.copyOf(internalHosts);this.ssh=Map.copyOf(ssh);this.resolver=Objects.requireNonNull(resolver);this.credentials=Objects.requireNonNull(credentials);
        this.certificateAuthority=Objects.requireNonNull(certificateAuthority);this.tls=Objects.requireNonNull(tls);
    }
    public static URI syntax(String source){
        URI uri;try{uri=URI.create(source);}catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid remote URI");}
        String scheme=uri.getScheme(),path=uri.getRawPath();
        if(scheme==null||!Set.of("https","ssh").contains(scheme)||uri.getHost()==null||!uri.getHost().matches("[A-Za-z0-9.-]+")||uri.getFragment()!=null||uri.getQuery()!=null
                ||uri.getPort()<-1||uri.getPort()==0||uri.getPort()>65535||!uri.normalize().equals(uri)||path==null||!path.matches("/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*/?")
                ||Arrays.stream(path.split("/")).anyMatch(p->p.equals(".")||p.equals(".."))
                ||scheme.equals("https")&&uri.getUserInfo()!=null||scheme.equals("ssh")&&uri.getUserInfo()!=null&&!uri.getUserInfo().matches("[A-Za-z0-9_.-]+"))throw new IllegalArgumentException("Invalid remote URI");
        return uri;
    }
    public Endpoint endpoint(URI uri)throws IOException{
        if(uri.getScheme()==null||!Set.of("https","ssh").contains(uri.getScheme())||uri.getHost()==null||uri.getFragment()!=null||uri.getRawUserInfo()!=null&&uri.getScheme().equals("https"))throw new IOException("ENDPOINT_DENIED");
        if(uri.getPort()!=-1&&uri.getPort()!=(uri.getScheme().equals("https")?443:22)&&!internalHosts.contains(uri.getHost()))throw new IOException("ENDPOINT_PORT_DENIED");
        if(uri.getScheme().equals("ssh")&&!ssh.containsKey(uri.getHost()))throw new IOException("SSH_IDENTITY_REQUIRED");
        var addresses=List.of(resolver.resolve(uri.getHost()));if(addresses.isEmpty())throw new IOException("DNS_UNAVAILABLE");
        for(var address:addresses)if(!internalHosts.contains(uri.getHost())&&!publicAddress(address))throw new IOException("ENDPOINT_DENIED");
        return new Endpoint(uri,addresses);
    }
    public static boolean publicAddress(InetAddress address){
        if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress())return false;
        byte[] b=address.getAddress();int a=b[0]&255;
        if(b.length==16){
            if((a&0xe0)!=0x20)return false;
            int c=b[1]&255,d=b[2]&255,e=b[3]&255;
            return !(a==0x20&&c==1&&(d<2||d==0x0d&&e==0xb8))&&!(a==0x20&&c==2)&&!(a==0x3f&&(c&0xf0)==0xf0);
        }
        int c=b[1]&255;
        return a!=0&&a!=10&&a!=127&&a<224&&!(a==100&&c>=64&&c<=127)&&!(a==169&&c==254)&&!(a==172&&c>=16&&c<=31)
                &&!(a==192&&(c==0&&((b[2]&255)==0||(b[2]&255)==2)||c==168||c==88&&(b[2]&255)==99))
                &&!(a==198&&(c==18||c==19||c==51&&(b[2]&255)==100))&&!(a==203&&c==0&&(b[2]&255)==113);
    }
    public Optional<String> authorization(URI endpoint)throws IOException{
        var header=credentials.authorization(endpoint);if(header.isPresent()){
            var reference=credentials.reference(endpoint);if(reference.isEmpty()||!reference.orElseThrow().readOnly())throw new IOException("READ_ONLY_CREDENTIAL_REFERENCE_REQUIRED");
            if(header.orElseThrow().length()>65536||header.orElseThrow().indexOf('\r')>=0||header.orElseThrow().indexOf('\n')>=0)throw new IOException("CREDENTIAL_INVALID");
        }return header;
    }
    public Optional<CredentialReference> credentialReference(URI endpoint){return credentials.reference(endpoint);}
    public Optional<SshIdentity> ssh(String host){return Optional.ofNullable(ssh.get(host));}
    public Optional<Path> certificateAuthority(){return certificateAuthority;}
    javax.net.ssl.SSLContext tls(){return tls;}
    private static javax.net.ssl.SSLContext defaultTls(){try{return javax.net.ssl.SSLContext.getDefault();}catch(java.security.NoSuchAlgorithmException unavailable){throw new IllegalStateException(unavailable);}}
}
