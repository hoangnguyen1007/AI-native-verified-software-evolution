package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Explicit standard Git LFS batch protocol; no repository filter, hook or .lfsconfig is executed. */
public final class LfsObjectAcquirer {
    public record Pointer(ContentDigest digest,long size,ContentHandle pointerBytes){public Pointer{if(size<0)throw new IllegalArgumentException("Negative LFS size");}}
    private final StreamingContentStore content;private final PinnedHttps http;
    public LfsObjectAcquirer(StreamingContentStore content,GitHostPolicy policy){this.content=content;http=new PinnedHttps(policy);}
    public Optional<Pointer> pointer(ContentHandle handle)throws IOException{
        if(handle.size()>1024)return Optional.empty();String text;try(var in=content.open(handle)){text=new String(in.readAllBytes(),StandardCharsets.US_ASCII);}
        if(!text.startsWith("version https://git-lfs.github.com/spec/v1\n"))return Optional.empty();
        String oid=null;Long size=null;for(String line:text.split("\n")){
            if(line.startsWith("oid sha256:")){if(oid!=null)throw new IOException("LFS_POINTER_INVALID");oid=line.substring(4);}
            else if(line.startsWith("size ")){if(size!=null)throw new IOException("LFS_POINTER_INVALID");try{size=Long.parseLong(line.substring(5));}catch(NumberFormatException invalid){throw new IOException("LFS_POINTER_INVALID");}}
            else if(!line.equals("version https://git-lfs.github.com/spec/v1")&&!line.startsWith("ext-"))throw new IOException("LFS_POINTER_INVALID");
        }
        try{if(oid==null||size==null)return Optional.empty();return Optional.of(new Pointer(new ContentDigest(oid),size,handle));}catch(IllegalArgumentException invalid){throw new IOException("LFS_POINTER_INVALID");}
    }
    public static final int MAX_BATCH_SIZE = 100;

    public Map<ContentDigest, ContentHandle> acquireBatch(URI repository, List<Pointer> pointers,
            BooleanSupplier cancelled) throws IOException {
        var resolved = new LinkedHashMap<ContentDigest, ContentHandle>();
        var missingByOid = new LinkedHashMap<String, Pointer>();
        for (var pointer : pointers) {
            if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            var expected = new ContentHandle(pointer.digest(), pointer.size());
            boolean present = false;
            try {
                present = content.verify(expected);
            } catch (IOException unavailable) {
                present = false;
            }
            if (present) {
                resolved.put(pointer.digest(), expected);
            } else {
                missingByOid.putIfAbsent(pointer.digest().value().substring(7), pointer);
            }
        }
        if (missingByOid.isEmpty()) {
            return resolved;
        }
        if (!"https".equals(repository.getScheme())) {
            throw new IOException("LFS_HTTPS_ENDPOINT_REQUIRED");
        }
        var pending = new ArrayList<>(missingByOid.values());
        for (int start = 0; start < pending.size(); start += MAX_BATCH_SIZE) {
            if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            var chunk = pending.subList(start, Math.min(pending.size(), start + MAX_BATCH_SIZE));
            acquireChunk(repository, chunk, resolved, cancelled);
        }
        return resolved;
    }

    private void acquireChunk(URI repository, List<Pointer> chunk, Map<ContentDigest, ContentHandle> resolved,
            BooleanSupplier cancelled) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                acquireChunkOnce(repository, chunk, resolved, cancelled);
                return;
            } catch (IOException failure) {
                last = failure;
                if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
                if (failure instanceof javax.net.ssl.SSLHandshakeException
                        || String.valueOf(failure.getMessage()).matches(".*(DENIED|INVALID|CHECKSUM|ACCESS_REQUIRED).*")) {
                    throw failure;
                }
                if (attempt < 2) {
                    try {
                        Thread.sleep(100L * (1L << attempt));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new java.util.concurrent.CancellationException();
                    }
                }
            }
        }
        throw Objects.requireNonNull(last);
    }

    private void acquireChunkOnce(URI repository, List<Pointer> chunk, Map<ContentDigest, ContentHandle> resolved,
            BooleanSupplier cancelled) throws IOException {
        URI endpoint = URI.create(repository.toString().replaceFirst("/$", "") + "/info/lfs/objects/batch");
        var byOid = new LinkedHashMap<String, Pointer>(chunk.size() * 2);
        var requestObjects = new ArrayList<Map<String, Object>>(chunk.size());
        for (var pointer : chunk) {
            String oid = pointer.digest().value().substring(7);
            byOid.put(oid, pointer);
            requestObjects.add(Map.of("oid", oid, "size", pointer.size()));
        }
        byte[] request = CanonicalJson.write(Map.of("operation", "download", "transfers", List.of("basic"),
                "objects", requestObjects)).getBytes(StandardCharsets.UTF_8);
        int maxMetaBytes = Math.max(1_048_576, chunk.size() * 16_384);
        try (var input = http.request(endpoint, "POST",
                Map.of("Accept", "application/vnd.git-lfs+json", "Content-Type", "application/vnd.git-lfs+json"),
                request, cancelled)) {
            byte[] bytes = input.readNBytes(maxMetaBytes + 1);
            if (bytes.length > maxMetaBytes) throw new IOException("LFS_METADATA_OVERSIZE");
            var json = GraphJson.object(GraphJson.read(new String(bytes, StandardCharsets.UTF_8),
                    new GraphJson.Limits(maxMetaBytes, 32, Math.max(10_000, chunk.size() * 200))));
            var objects = GraphJson.array(json.get("objects"));
            for (var rawObj : objects) {
                if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
                var object = GraphJson.object(rawObj);
                String oid = object.get("oid") instanceof String s ? s : null;
                if (oid == null) continue;
                var pointer = byOid.get(oid);
                if (pointer == null || resolved.containsKey(pointer.digest())) continue;
                if (!Long.toString(pointer.size()).equals(String.valueOf(object.get("size")))) {
                    throw new IOException("LFS_RESPONSE_INVALID");
                }
                if (object.containsKey("error") || !object.containsKey("actions")) {
                    continue;
                }
                var actions = GraphJson.object(object.get("actions"));
                if (!actions.containsKey("download")) continue;
                var action = GraphJson.object(actions.get("download"));
                URI target = URI.create(GraphJson.string(action.get("href")));
                var headers = new TreeMap<String, String>();
                if (action.containsKey("header")) {
                    for (var header : GraphJson.object(action.get("header")).entrySet()) {
                        headers.put(header.getKey(), GraphJson.string(header.getValue()));
                    }
                }
                var expected = new ContentHandle(pointer.digest(), pointer.size());
                try (var blob = http.request(target, "GET", headers, new byte[0], cancelled)) {
                    var actual = content.put(new GitSnapshotAcquirer.LimitedInput(blob, pointer.size()), cancelled);
                    if (blob.read() != -1 || !actual.equals(expected)) {
                        throw new IOException("LFS_OBJECT_CHECKSUM_MISMATCH");
                    }
                    resolved.put(pointer.digest(), actual);
                }
            }
        } catch (IllegalArgumentException invalid) {
            throw new IOException("LFS_RESPONSE_INVALID", invalid);
        }
    }

    public ContentHandle acquire(URI repository,Pointer pointer,BooleanSupplier cancelled)throws IOException{
        var expected=new ContentHandle(pointer.digest(),pointer.size());
        try{if(content.verify(expected))return expected;}catch(IOException unavailable){ }
        IOException last=null;for(int attempt=0;attempt<3;attempt++)try{return acquireOnce(repository,pointer,cancelled);}catch(IOException failure){last=failure;
            if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();
            if(failure instanceof javax.net.ssl.SSLHandshakeException||String.valueOf(failure.getMessage()).matches(".*(DENIED|INVALID|CHECKSUM|ACCESS_REQUIRED).*"))throw failure;
            if(attempt<2)try{Thread.sleep(100L*(1L<<attempt));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException();}
        }throw Objects.requireNonNull(last);
    }
    private ContentHandle acquireOnce(URI repository,Pointer pointer,BooleanSupplier cancelled)throws IOException{
        var expected=new ContentHandle(pointer.digest(),pointer.size());
        if(!"https".equals(repository.getScheme()))throw new IOException("LFS_HTTPS_ENDPOINT_REQUIRED");
        URI endpoint=URI.create(repository.toString().replaceFirst("/$","")+"/info/lfs/objects/batch");
        String oid=pointer.digest().value().substring(7);byte[] request=CanonicalJson.write(Map.of("operation","download","transfers",List.of("basic"),"objects",List.of(Map.of("oid",oid,"size",pointer.size())))).getBytes(StandardCharsets.UTF_8);
        try(var input=http.request(endpoint,"POST",Map.of("Accept","application/vnd.git-lfs+json","Content-Type","application/vnd.git-lfs+json"),request,cancelled)){
            byte[] bytes=input.readNBytes(1_048_577);if(bytes.length>1_048_576)throw new IOException("LFS_METADATA_OVERSIZE");
            var json=GraphJson.object(GraphJson.read(new String(bytes,StandardCharsets.UTF_8),new GraphJson.Limits(1_048_576,32,10000)));var objects=GraphJson.array(json.get("objects"));
            if(objects.size()!=1)throw new IOException("LFS_RESPONSE_INVALID");var object=GraphJson.object(objects.getFirst());
            if(!oid.equals(object.get("oid"))||!Long.toString(pointer.size()).equals(String.valueOf(object.get("size"))))throw new IOException("LFS_RESPONSE_INVALID");
            if(object.containsKey("error"))throw new IOException("LFS_OBJECT_UNAVAILABLE");var action=GraphJson.object(GraphJson.object(object.get("actions")).get("download"));URI target=URI.create(GraphJson.string(action.get("href")));
            var headers=new TreeMap<String,String>();if(action.containsKey("header"))for(var header:GraphJson.object(action.get("header")).entrySet())headers.put(header.getKey(),GraphJson.string(header.getValue()));
            try(var blob=http.request(target,"GET",headers,new byte[0],cancelled)){
                var actual=content.put(new GitSnapshotAcquirer.LimitedInput(blob,pointer.size()),cancelled);if(blob.read()!=-1||!actual.equals(expected))throw new IOException("LFS_OBJECT_CHECKSUM_MISMATCH");return actual;
            }
        }catch(IllegalArgumentException invalid){throw new IOException("LFS_RESPONSE_INVALID");}
    }
}
