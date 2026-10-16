package com.evolution.backend.query;

import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Read-only local HTTP server, owned and closed by its embedding application. */
public final class ArchitectureHttpServer implements AutoCloseable {
    private final ArchitectureQueryApi api;
    private final HttpServer server;
    private final ExecutorService executor;
    private final ScheduledThreadPoolExecutor deadlines;
    private final Semaphore slots;
    private final AtomicBoolean closed = new AtomicBoolean();
    public ArchitectureHttpServer(ArchitectureQueryApi api,int port) throws IOException {
        this.api=Objects.requireNonNull(api);
        if (port<0 || port>65535) throw new IllegalArgumentException("Invalid port");
        server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),port),16);
        int workers=api.limits().concurrentRequests();
        slots=new Semaphore(workers);
        deadlines=new ScheduledThreadPoolExecutor(1,runnable -> { var thread=new Thread(runnable,"architecture-http-deadlines"); thread.setDaemon(true); return thread; });
        deadlines.setRemoveOnCancelPolicy(true);
        executor=new ThreadPoolExecutor(workers,workers,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(workers),
                runnable -> { var thread=new Thread(runnable,"architecture-http"); thread.setDaemon(true); return thread; },
                new ThreadPoolExecutor.CallerRunsPolicy());
        server.setExecutor(executor);
        server.createContext("/",this::handle);
    }
    public void start() { if (closed.get()) throw new IllegalStateException("Server closed"); server.start(); }
    public URI uri() { return URI.create("http://127.0.0.1:"+server.getAddress().getPort()); }
    private void handle(HttpExchange exchange) throws IOException {
        boolean acquired=false;
        var expired=new AtomicBoolean();
        ScheduledFuture<?> deadline=deadlines.schedule(() -> { expired.set(true); exchange.close(); },api.limits().queryTimeoutMillis(),TimeUnit.MILLISECONDS);
        try (exchange) {
            ArchitectureQueryApi.Response response;
            if (!localOrigin(exchange)) response=api.error(403,"FORBIDDEN_ORIGIN");
            else if (!(acquired=slots.tryAcquire())) response=api.error(503,"BUSY");
            else if (exchange.getRequestURI().getRawQuery()!=null) response=api.error(400,"INVALID_REQUEST");
            else if (!acceptable(exchange.getRequestHeaders().containsKey("Accept") ? String.join(",",exchange.getRequestHeaders().get("Accept")) : null)) response=api.error(406,"NOT_ACCEPTABLE");
            else if (exchange.getRequestHeaders().containsKey("Content-Encoding")) response=api.error(415,"UNSUPPORTED_MEDIA_TYPE");
            else if (exchange.getRequestHeaders().getOrDefault("Content-Type",List.of()).size()>1) response=api.error(415,"UNSUPPORTED_MEDIA_TYPE");
            else {
                // The exchange deadline bounds a stalled body/read/write as well as cooperative query work.
                byte[] body=exchange.getRequestBody().readNBytes(api.limits().requestBytes()+1);
                response=api.handle(exchange.getRequestMethod(),exchange.getRequestURI().getRawPath(),
                        exchange.getRequestHeaders().getFirst("Content-Type"),body,() -> closed.get() || expired.get() || Thread.currentThread().isInterrupted());
            }
            var headers=exchange.getResponseHeaders();
            headers.set("Content-Type","application/json; charset=utf-8");
            headers.set("Cache-Control","no-store");
            headers.set("X-Content-Type-Options","nosniff");
            headers.set("Content-Security-Policy","default-src 'none'; frame-ancestors 'none'");
            if (response.status()==405) headers.set("Allow",ArchitectureQueryApi.allowedMethods(exchange.getRequestURI().getRawPath()));
            byte[] bytes=response.body();
            // HEAD has a useful error status and headers but never an HTTP response body.
            exchange.sendResponseHeaders(response.status(),exchange.getRequestMethod().equals("HEAD") ? -1 : bytes.length);
            if (!exchange.getRequestMethod().equals("HEAD")) exchange.getResponseBody().write(bytes);
        } finally { if (deadline!=null) deadline.cancel(false); if (acquired) slots.release(); }
    }
    private boolean localOrigin(HttpExchange exchange) {
        var hosts=exchange.getRequestHeaders().get("Host");
        if (hosts==null || hosts.size()!=1 || !Set.of("127.0.0.1:"+server.getAddress().getPort(),"localhost:"+server.getAddress().getPort()).contains(hosts.getFirst())) return false;
        var origins=exchange.getRequestHeaders().get("Origin");
        if (origins==null) return true;
        return origins.size()==1 && origins.getFirst().equals("http://"+hosts.getFirst());
    }
    static boolean acceptable(String accept) {
        if (accept==null) return true;
        // The most specific matching range determines JSON's quality, including an explicit q=0.
        int specificity=-1;
        java.math.BigDecimal quality=java.math.BigDecimal.ZERO;
        for (String range : accept.toLowerCase(Locale.ROOT).split(",")) {
            String[] fields=range.trim().split(";");
            if (!Set.of("application/json","application/*","*/*").contains(fields[0].trim())) continue;
            int current=fields[0].trim().equals("application/json") ? 2 : fields[0].trim().equals("application/*") ? 1 : 0;
            var q=java.math.BigDecimal.ONE;
            boolean valid=true; boolean seenQuality=false;
            for (int i=1;i<fields.length;i++) {
                String parameter=fields[i].trim();
                if (!parameter.startsWith("q=") || seenQuality) { valid=false; break; }
                seenQuality=true;
                try { q=new java.math.BigDecimal(parameter.substring(2)); valid=q.signum()>=0 && q.compareTo(java.math.BigDecimal.ONE)<=0; }
                catch (NumberFormatException invalid) { valid=false; }
            }
            if (valid && (current>specificity || current==specificity && q.compareTo(quality)>0)) { specificity=current; quality=q; }
        }
        return specificity>=0 && quality.signum()>0;
    }
    @Override public void close() {
        if (closed.compareAndSet(false,true)) { server.stop(0); executor.shutdownNow(); deadlines.shutdownNow(); }
    }
}
