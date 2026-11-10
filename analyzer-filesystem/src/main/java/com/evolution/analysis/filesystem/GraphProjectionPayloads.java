package com.evolution.analysis.filesystem;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.zip.*;

/** Disposable content-addressed payload segments. The SQLite B-tree contains only
 * keys and byte locators; large JSON never participates in key rebalancing. */
final class GraphProjectionPayloads implements AutoCloseable {
    private final PreparedStatement find,insert;
    private final Path directory;
    private final int rowBytes;
    private final long segmentBytes;
    private final BooleanSupplier cancelled;
    private final List<Path> segments=new ArrayList<>();
    private final Map<Integer,FileChannel> readers=new LinkedHashMap<>(8,.75f,true);
    private final Map<String,String> cache=new LinkedHashMap<>(16,.75f,true);
    private int cachedCharacters;
    private FileChannel writer;
    private long position,allowance;
    private final FileStore fileStore;
    private boolean closed;

    GraphProjectionPayloads(Connection database,int rowBytes,BooleanSupplier cancelled)throws SQLException,IOException {
        this(database,rowBytes,cancelled,64L*1024*1024);
    }
    GraphProjectionPayloads(Connection database,int rowBytes,BooleanSupplier cancelled,long segmentBytes)throws SQLException,IOException {
        this.rowBytes=rowBytes;this.cancelled=cancelled;this.segmentBytes=segmentBytes;
        try(var sql=database.createStatement();var rows=sql.executeQuery("PRAGMA database_list")) {
            Path found=null;while(rows.next())if(rows.getString(2).equals("main"))found=Path.of(rows.getString(3));
            if(found==null||found.toString().isEmpty())throw new IOException("GRAPH_PROJECTION_DISK_INDEX_REQUIRED");
            directory=found.toAbsolutePath().getParent();
        }
        fileStore=Files.getFileStore(directory);
        try(var sql=database.createStatement()) {
            sql.execute("CREATE TABLE payloads(digest TEXT PRIMARY KEY,segment INTEGER NOT NULL,start INTEGER NOT NULL,length INTEGER NOT NULL,raw_length INTEGER NOT NULL,compressed INTEGER NOT NULL) WITHOUT ROWID");
        }
        find=database.prepareStatement("SELECT segment,start,length,raw_length,compressed FROM payloads WHERE digest=?");
        insert=database.prepareStatement("INSERT INTO payloads VALUES(?,?,?,?,?,?)");
    }
    String put(String value)throws SQLException,IOException {
        check();byte[] raw=value.getBytes(StandardCharsets.UTF_8);
        if(raw.length>rowBytes)throw new IOException("GRAPH_PARTITION_ROW_WORKER_REQUIRED");
        String digest=HexFormat.of().formatHex(StreamingContentStore.sha256().digest(raw));
        if(cache.containsKey(digest))return digest;
        find.setString(1,digest);try(var rows=find.executeQuery()){if(rows.next()){remember(digest,value);return digest;}}
        var deflater=new Deflater(Deflater.BEST_SPEED);
        byte[] packed;
        try(var output=new ByteArrayOutputStream();var zip=new DeflaterOutputStream(output,deflater)) {
            zip.write(raw);zip.finish();packed=output.toByteArray();
        }finally{deflater.end();}
        boolean compressed=packed.length<raw.length;byte[] bytes=compressed?packed:raw;
        if(writer==null||position>0&&position+bytes.length>segmentBytes) {
            if(writer!=null){writer.force(false);writer.close();writer=null;}
            Path file=Files.createTempFile(directory,"graph-payload-",".pack");segments.add(file);
            writer=FileChannel.open(file,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);position=0;
        }
        if(allowance<bytes.length){long free=fileStore.getUsableSpace();long reserve=64L*1024*1024;if(free<bytes.length+reserve)throw new IOException("GRAPH_PROJECTION_DISK_PRESSURE");allowance=Math.min(8L*1024*1024,free-reserve);}
        long start=position;var buffer=ByteBuffer.wrap(bytes);
        while(buffer.hasRemaining()){check();position+=writer.write(buffer,position);}
        allowance-=bytes.length;
        insert.setString(1,digest);insert.setInt(2,segments.size()-1);insert.setLong(3,start);insert.setInt(4,bytes.length);insert.setInt(5,raw.length);insert.setBoolean(6,compressed);insert.executeUpdate();
        remember(digest,value);return digest;
    }
    String get(String digest)throws SQLException,IOException {
        check();String existing=cache.get(digest);if(existing!=null)return existing;
        int segment,length,rawLength;long start;boolean compressed;
        find.setString(1,digest);try(var rows=find.executeQuery()) {
            if(!rows.next())throw new IOException("GRAPH_PROJECTION_PAYLOAD_MISSING");
            segment=rows.getInt(1);start=rows.getLong(2);length=rows.getInt(3);rawLength=rows.getInt(4);compressed=rows.getBoolean(5);
        }
        if(segment<0||segment>=segments.size()||start<0||length<0||length>rowBytes||rawLength<0||rawLength>rowBytes)throw new IOException("GRAPH_PROJECTION_PAYLOAD_LOCATOR_INVALID");
        FileChannel channel;
        if(segment==segments.size()-1)channel=writer;
        else {
            channel=readers.get(segment);
            if(channel==null) {
                if(readers.size()>=4){var first=readers.entrySet().iterator();var old=first.next();old.getValue().close();first.remove();}
                channel=FileChannel.open(segments.get(segment),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);readers.put(segment,channel);
            }
        }
        if(start>channel.size()-length)throw new IOException("GRAPH_PROJECTION_PAYLOAD_TRUNCATED");
        byte[] bytes=new byte[length];var buffer=ByteBuffer.wrap(bytes);long offset=start;
        while(buffer.hasRemaining()){check();int count=channel.read(buffer,offset);if(count<0)throw new EOFException("GRAPH_PROJECTION_PAYLOAD_TRUNCATED");offset+=count;}
        byte[] raw;
        if(compressed)try(var zip=new InflaterInputStream(new ByteArrayInputStream(bytes))){raw=zip.readNBytes(rawLength+1);}
        else raw=bytes;
        if(raw.length!=rawLength||!MessageDigest.isEqual(StreamingContentStore.sha256().digest(raw),HexFormat.of().parseHex(digest)))throw new IOException("GRAPH_PROJECTION_PAYLOAD_HASH_MISMATCH");
        String value=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(raw)).toString();remember(digest,value);return value;
    }
    private void remember(String digest,String value) {
        if(value.length()>524_288)return;
        String old=cache.put(digest,value);if(old!=null)cachedCharacters-=old.length();cachedCharacters+=value.length();
        var rows=cache.entrySet().iterator();while(cache.size()>64||cachedCharacters>524_288){cachedCharacters-=rows.next().getValue().length();rows.remove();}
    }
    private void check(){if(closed)throw new IllegalStateException("GRAPH_PROJECTION_CLOSED");if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();}
    public void close()throws IOException {
        if(closed)return;closed=true;
        IOException failure=null;var resources=new ArrayList<AutoCloseable>();resources.add(find);resources.add(insert);if(writer!=null)resources.add(writer);resources.addAll(readers.values());
        for(var resource:resources)try{resource.close();}catch(Exception problem){if(failure==null)failure=new IOException("GRAPH_PROJECTION_PAYLOAD_CLOSE",problem);else failure.addSuppressed(problem);}
        for(var file:segments)try{Files.deleteIfExists(file);}catch(IOException problem){if(failure==null)failure=problem;else failure.addSuppressed(problem);}
        cache.clear();if(failure!=null)throw failure;
    }
}
