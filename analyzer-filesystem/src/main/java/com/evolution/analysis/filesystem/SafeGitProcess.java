package com.evolution.analysis.filesystem;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Native Git plumbing with empty ambient configuration, fixed arguments, progress lease and bounded diagnostics. */
final class SafeGitProcess implements AutoCloseable {
    final Process process;
    final InputStream output;
    final OutputStream input;
    private final ScheduledExecutorService watchdog;
    private final Thread errors;
    private final AtomicLong progress=new AtomicLong(System.nanoTime());
    private final AtomicBoolean stopped=new AtomicBoolean();
    private final ByteArrayOutputStream diagnostic=new ByteArrayOutputStream();
    SafeGitProcess(String executable,Path workspace,List<String> args,Optional<GitHostPolicy.Endpoint> endpoint,
                   GitHostPolicy policy,BooleanSupplier cancelled,long idleMillis)throws IOException{
        Path empty=StreamingContentStore.directory(workspace.resolve("empty-config"));
        var command=new ArrayList<String>(List.of(executable,"-c","credential.helper=","-c","core.hooksPath="+empty,"-c","protocol.allow=never",
                "-c","protocol.https.allow=always","-c","protocol.ssh.allow=always","-c","http.followRedirects=false","-c","http.sslVerify=true",
                "-c","http.proxy=","-c","http.emptyAuth=false","-c","http.delegation=none","-c","http.proactiveAuth=none","-c","core.autocrlf=false","-c","http.lowSpeedLimit=1","-c","http.lowSpeedTime="+Math.max(1,idleMillis/1000),"-c","fetch.fsckObjects=true","-c","transfer.fsckObjects=true"));
        if(endpoint.isPresent()){
            var target=endpoint.orElseThrow();String host=target.uri().getHost();
            if(target.uri().getScheme().equals("https")){
                String addresses=String.join(",",target.addresses().stream().map(a->a.getAddress().length==16?"["+a.getHostAddress()+"]":a.getHostAddress()).toList());
                int port=target.uri().getPort()==-1?443:target.uri().getPort();command.addAll(List.of("-c","http.curloptResolve="+host+":"+port+":"+addresses));
                if(policy.certificateAuthority().isPresent()){var ca=policy.certificateAuthority().orElseThrow();StreamingContentStore.safe(ca);command.addAll(List.of("-c","http.sslCAInfo="+ca,"-c","http.schannelUseSSLCAInfo=true"));}
            }else{
                var identity=policy.ssh(host).orElseThrow();StreamingContentStore.safe(identity.key());StreamingContentStore.safe(identity.knownHosts());
                String address=target.addresses().getFirst().getHostAddress();
                String ssh=quote(identity.executable())+" -F "+quote(empty.resolve("ssh-config").toString())+" -o BatchMode=yes -o IdentityAgent=none -o StrictHostKeyChecking=yes -o IdentitiesOnly=yes -o ProxyCommand=none -o ProxyJump=none -o GlobalKnownHostsFile="+quote(empty.resolve("no-hosts").toString())+" -o UserKnownHostsFile="+quote(identity.knownHosts().toString())+" -o HostKeyAlias="+quote(host)+" -o Hostname="+quote(address)+" -i "+quote(identity.key().toString());
                if(!Files.exists(empty.resolve("ssh-config")))Files.writeString(empty.resolve("ssh-config"),"");
                command.addAll(List.of("-c","core.sshCommand="+ssh));
            }
        }
        command.addAll(args);var builder=new ProcessBuilder(command).directory(workspace.toFile());var inherited=new HashMap<>(builder.environment());builder.environment().clear();
        for(String key:List.of("PATH","Path","SystemRoot","SYSTEMROOT","TEMP","TMP"))if(inherited.containsKey(key))builder.environment().put(key,inherited.get(key));
        builder.environment().put("GIT_CONFIG_NOSYSTEM","1");builder.environment().put("GIT_CONFIG_GLOBAL",empty.resolve("no-config").toString());
        builder.environment().put("GIT_TERMINAL_PROMPT","0");builder.environment().put("GIT_ASKPASS","");builder.environment().put("GIT_LFS_SKIP_SMUDGE","1");builder.environment().put("GIT_SSH_VARIANT","ssh");
        if(endpoint.isPresent()&&endpoint.orElseThrow().uri().getScheme().equals("https")){
            var auth=policy.authorization(endpoint.orElseThrow().uri());if(auth.isPresent()){
                builder.environment().put("GIT_CONFIG_COUNT","1");builder.environment().put("GIT_CONFIG_KEY_0","http."+endpoint.orElseThrow().uri()+".extraHeader");builder.environment().put("GIT_CONFIG_VALUE_0","Authorization: "+auth.orElseThrow());
            }
        }
        process=builder.start();input=process.getOutputStream();
        output=new FilterInputStream(new BufferedInputStream(process.getInputStream(),65536)){
            @Override public int read()throws IOException{int value=super.read();if(value>=0)progress.set(System.nanoTime());return value;}
            @Override public int read(byte[] b,int off,int len)throws IOException{int n=super.read(b,off,len);if(n>0)progress.set(System.nanoTime());return n;}
        };
        errors=new Thread(()->{try(var in=process.getErrorStream()){byte[] buffer=new byte[1024];int n;while((n=in.read(buffer))!=-1){progress.set(System.nanoTime());synchronized(diagnostic){if(diagnostic.size()<8192)diagnostic.write(buffer,0,Math.min(n,8192-diagnostic.size()));}}}catch(IOException ignored){ }},"git-diagnostics");errors.setDaemon(true);errors.start();
        watchdog=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"git-progress-lease");t.setDaemon(true);return t;});
        watchdog.scheduleWithFixedDelay(()->{if(cancelled.getAsBoolean()||endpoint.isPresent()&&System.nanoTime()-progress.get()>TimeUnit.MILLISECONDS.toNanos(idleMillis)){stopped.set(true);kill();}},100,100,TimeUnit.MILLISECONDS);
    }
    static String quote(String value)throws IOException{
        if(value.indexOf('\n')>=0||value.indexOf('\r')>=0||value.indexOf('"')>=0||value.indexOf('\'')>=0||value.indexOf('`')>=0||value.indexOf('$')>=0)throw new IOException("UNSAFE_OPERATOR_PATH");
        return "\""+value.replace('\\','/')+"\"";
    }
    int finish()throws IOException{try{int exit=process.waitFor();errors.join(1000);if(stopped.get())throw new IOException("GIT_IDLE_OR_CANCELLED");return exit;}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("GIT_INTERRUPTED");}}
    String failure(){
        String text; synchronized(diagnostic){text=diagnostic.toString(StandardCharsets.UTF_8);}String lower=text.toLowerCase(Locale.ROOT);
        if(lower.contains("authentication failed")||lower.contains("could not read username")||lower.contains("permission denied"))return "GIT_ACCESS_REQUIRED";
        if(lower.contains("no space left")||lower.contains("disk full")||lower.contains("not enough space"))return "GIT_DISK_FULL";
        if(lower.contains("not found")||lower.contains("does not appear to be a git repository"))return "GIT_NOT_FOUND";
        if(lower.contains("unsupported ssl backend"))return "GIT_TLS_BACKEND_UNAVAILABLE";
        if(lower.contains("revocation"))return "GIT_TLS_REVOCATION_UNAVAILABLE";
        if(lower.contains("self-signed")||lower.contains("unable to get local issuer"))return "GIT_TLS_ISSUER_UNTRUSTED";
        if(lower.contains("name mismatch")||lower.contains("no alternative certificate"))return "GIT_TLS_HOSTNAME_MISMATCH";
        if(lower.contains("certificate file")||lower.contains("ca cert"))return "GIT_TLS_CA_UNAVAILABLE";
        if(lower.contains("server closed abruptly")||lower.contains("connection reset")||lower.contains("transfer closed")||lower.contains("recv failure"))return "GIT_TRANSFER_INTERRUPTED";
        if(lower.contains("inflate: data stream error")||lower.contains("checksum mismatch"))return "GIT_OBJECT_CONTENT_INVALID";
        if(lower.matches("(?s).*(ssl|certificate|schannel).*"))return "GIT_TLS_FAILED";
        if(lower.contains("dumb http transport does not support shallow"))return "GIT_SHALLOW_UNAVAILABLE";
        if(lower.contains("could not resolve host"))return "GIT_DNS_UNAVAILABLE";
        if(lower.contains("failed to connect"))return "GIT_CONNECTION_UNAVAILABLE";
        return "GIT_TRANSFER_INCOMPLETE";
    }
    private void kill(){if(process.isAlive()){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();}}
    @Override public void close(){watchdog.shutdownNow();kill();try{input.close();output.close();}catch(IOException ignored){ }}
}
