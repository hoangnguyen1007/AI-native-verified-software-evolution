package com.evolution.analysis.execution.broker;

import com.evolution.analysis.acquisition.streaming.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Attempt-scoped HTTP facade. Reachable only on controller loopback (guest access needs an authenticated tunnel).
 * Guest requests select a registered repository alias; they cannot select a host, credential or redirect policy.
 */
public final class ArtifactBrokerServer implements AutoCloseable {
    private final ArtifactBroker broker;
    private final ArtifactBroker.Grant grant;
    private final long fence;
    private final Map<String,URI> repositories;
    private final RepositoryContentStore content;
    private final ConcurrentMap<URI,ContentHandle> pins;
    private final AtomicBoolean closed=new AtomicBoolean();
    private final HttpServer server;
    private final ExecutorService workers;

    public ArtifactBrokerServer(ArtifactBroker broker,ArtifactBroker.Grant grant,long fence,Map<String,URI> repositories,
            RepositoryContentStore content,Map<URI,ContentHandle> capturedClosure)throws IOException {
        this.broker=Objects.requireNonNull(broker);this.grant=Objects.requireNonNull(grant);this.fence=fence;
        var aliases=new TreeMap<String,URI>();
        repositories.forEach((alias,uri)->{
            if(!alias.matches("[a-zA-Z0-9_-]+")||!uri.getPath().endsWith("/"))throw new IllegalArgumentException("INVALID_BROKER_ALIAS");
            new ArtifactBroker.Rule(uri);aliases.put(alias,uri);
        });
        this.repositories=Map.copyOf(aliases);this.content=Objects.requireNonNull(content);this.pins=new ConcurrentHashMap<>(capturedClosure);
        server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),32);
        workers=new ThreadPoolExecutor(4,4,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(32),r->{var t=new Thread(r,"artifact-broker");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(workers);server.createContext("/",this::handle);server.start();
    }
    public URI baseUri(){return URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/");}
    public Map<URI,ContentHandle> capturedClosure(){return Collections.unmodifiableMap(new TreeMap<>(pins));}
    private void handle(HttpExchange exchange)throws IOException {
        try {
            if(!Set.of("GET","HEAD").contains(exchange.getRequestMethod())){reject(exchange,405);return;}
            URI request=exchange.getRequestURI();String path=request.getRawPath();
            if(request.isAbsolute()||request.getRawQuery()!=null||request.getRawFragment()!=null||path==null||!path.startsWith("/")||!request.normalize().equals(request)) {reject(exchange,403);return;}
            int slash=path.indexOf('/',1);
            URI repository=slash<0?null:repositories.get(path.substring(1,slash));
            if(repository==null||slash==path.length()-1){reject(exchange,403);return;}
            URI endpoint;
            try {
                String relative=path.substring(slash+1);
                endpoint=repository.resolve(relative);
                // Validate decoded traversal, escaped slashes, and authority replacement before acquisition.
                var rule=new ArtifactBroker.Rule(endpoint);
                if(!new ArtifactBroker.Rule(repository).permits(rule.repository())){reject(exchange,403);return;}
            }catch(IllegalArgumentException denied){reject(exchange,403);return;}
            var material=broker.acquire(grant,fence,endpoint,Optional.ofNullable(pins.get(endpoint)),closed::get);
            if(material.state()!=ArtifactBroker.State.ACQUIRED&&material.state()!=ArtifactBroker.State.REUSED){reject(exchange,503);return;}
            var handle=material.content().orElseThrow();
            var prior=pins.putIfAbsent(endpoint,handle);
            if(prior!=null&&!prior.equals(handle)){reject(exchange,409);return;}
            if(!content.verify(handle)){reject(exchange,503);return;}
            exchange.getResponseHeaders().set("Content-Type","application/octet-stream");
            exchange.getResponseHeaders().set("Content-Length",Long.toString(handle.size()));
            exchange.getResponseHeaders().set("Cache-Control","no-store");
            if(exchange.getRequestMethod().equals("HEAD")){exchange.sendResponseHeaders(200,-1);return;}
            exchange.sendResponseHeaders(200,handle.size()==0?-1:handle.size());
            try(var source=content.open(handle);var sink=exchange.getResponseBody()){
                byte[] buffer=new byte[65536];int count;
                while((count=source.read(buffer))!=-1){if(closed.get())throw new IOException("BROKER_CLOSED");sink.write(buffer,0,count);}
            }
        }catch(IOException|RuntimeException unavailable){
            // Never expose endpoint credentials, target bytes or backend exception details in HTTP diagnostics.
            if(exchange.getResponseCode()==-1){
                // An unavailable audit sink must fail closed, without recursively retrying it.
                exchange.sendResponseHeaders(503,-1);
            }
        }finally{exchange.close();}
    }
    private void reject(HttpExchange exchange,int status)throws IOException{
        broker.denied(grant,exchange.getRequestURI(),status);
        exchange.sendResponseHeaders(status,-1);
    }
    @Override public void close(){if(closed.compareAndSet(false,true)){broker.revoke(grant);server.stop(0);workers.shutdownNow();}}
}
