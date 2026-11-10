package com.evolution.analysis.filesystem;

import com.evolution.analysis.ingestion.AnalysisWorkspace;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import java.io.*;
import java.lang.reflect.*;
import java.math.*;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;
import java.util.function.Function;

/** Private, disposable analysis spool. Values are streamed to disk, with disk indexes and
 * SHA-256 row checks. Only classes encountered in this process may be reconstructed;
 * input bytes cannot name or load a class. This is scratch state, never an evidence format. */
public final class FileAnalysisWorkspace implements AnalysisWorkspace, AutoCloseable {
    @Override public boolean concurrentTables(){return true;}
    private final Path directory;
    private final Connection db;
    private final RandomAccessFile data;
    private final Map<Integer,Integer> sizes=new HashMap<>();
    private final Map<String,PreparedStatement> queries=new HashMap<>();
    private final List<Class<?>> classes=new ArrayList<>();
    private final Map<Class<?>,Integer> classIds=new HashMap<>();
    private record RecordCodec(Method[] accessors,Constructor<?> constructor) {}
    private final Map<Class<?>,RecordCodec> codecs=new HashMap<>();
    private int sequence;
    private boolean closed;
    public FileAnalysisWorkspace(Path parent)throws IOException {
        Files.createDirectories(parent);
        cleanStale(parent);
        directory=Files.createTempDirectory(parent,"analysis-");
        data=new RandomAccessFile(directory.resolve("values.bin").toFile(),"rw");
        try {
            db=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("index.sqlite"));
            try(var s=db.createStatement()){
                s.execute("PRAGMA journal_mode=OFF");s.execute("PRAGMA synchronous=OFF");s.execute("PRAGMA cache_size=-2048");
                s.execute("PRAGMA temp_store=FILE");
                s.execute("CREATE TABLE rows(space INTEGER, ordinal INTEGER, key TEXT, start INTEGER, length INTEGER, digest BLOB, PRIMARY KEY(space,ordinal))");
                s.execute("CREATE UNIQUE INDEX row_keys ON rows(space,key)");
            }
            db.setAutoCommit(false);
            org.sqlite.ProgressHandler.setHandler(db,1000,new org.sqlite.ProgressHandler(){protected int progress(){return Thread.currentThread().isInterrupted()?1:0;}});
        } catch(SQLException failed){throw new IOException("ANALYSIS_SPOOL_OPEN_FAILED",failed);}
    }
    private void requireOpen(){if(closed)throw new IllegalStateException("ANALYSIS_SPOOL_CLOSED");if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();}
    private synchronized int space(){requireOpen();int next=sequence++;sizes.put(next,0);return next;}
    private PreparedStatement query(String sql)throws SQLException {var cached=queries.get(sql);if(cached==null){cached=db.prepareStatement(sql);queries.put(sql,cached);}cached.clearParameters();return cached;}
    @Override public <T> List<T> list(){return new Rows<>(space());}
    @Override public <T> List<T> freeze(List<T> rows){
        if(rows instanceof FileAnalysisWorkspace.Rows<?> raw){
            @SuppressWarnings("unchecked") var owned=(Rows<T>)raw;
            if(owned.owner()!=this)throw new IllegalArgumentException("FOREIGN_ANALYSIS_SPOOL");
            owned.sealed=true;int size=owned.size;
            return new ReplayRows<>(owned.space,size);
        }
        return ReplayList.copy(rows);
    }
    @Override public Set<CapabilityGapRecord> gaps(Collection<CapabilityGapRecord> initial){
        int space=space();var set=new AbstractSet<CapabilityGapRecord>(){
            public int size(){return count(space);}
            public boolean add(CapabilityGapRecord row){return insertKey(space,row.gapIdentity().value(),row);}
            public Iterator<CapabilityGapRecord> iterator(){return FileAnalysisWorkspace.this.<CapabilityGapRecord>ordered(space).iterator();}
        };set.addAll(initial);return set;
    }
    @Override public <T> List<T> distinct(Collection<T> rows,Function<T,String> key){
        int space=space(),source=-1;
        if(rows instanceof FileAnalysisWorkspace.Rows<?> owned&&owned.owner()==this)source=owned.space;
        if(rows instanceof FileAnalysisWorkspace.ReplayRows<?> owned&&owned.owner()==this)source=owned.space;
        if(source>=0)for(int i=0;i<rows.size();i++){var stored=this.<T>readStored(source,i);insertAlias(space,key.apply(stored.value()),stored.location());}
        else for(var row:rows)insertKey(space,key.apply(row),row);
        int output=space();
        synchronized(this){requireOpen();try {
            var q=query("INSERT INTO rows SELECT ?,ROW_NUMBER() OVER (ORDER BY key)-1,NULL,start,length,digest FROM rows WHERE space=?");
            q.setInt(1,output);q.setInt(2,space);q.executeUpdate();sizes.put(output,count(space));
        }catch(SQLException failed){throw failure(failed);}}
        return new ReplayRows<>(output,count(output));
    }
    private record Location(long start,long length,byte[] digest) {}
    private record Stored<T>(T value,Location location) {}
    private synchronized void insertAlias(int space,String key,Location location) {
        requireOpen();try {
            var q=query("INSERT OR IGNORE INTO rows VALUES(?,?,?,?,?,?)");
            q.setInt(1,space);q.setInt(2,count(space));q.setString(3,orderedKey(key));q.setLong(4,location.start());q.setLong(5,location.length());q.setBytes(6,location.digest());
            if(q.executeUpdate()>0)sizes.put(space,count(space)+1);
        }catch(SQLException failed){throw failure(failed);}
    }
    private synchronized int count(int space){requireOpen();return sizes.get(space);}
    private synchronized boolean insertKey(int space,String key,Object value){
        // SQLite UTF-8 collation differs from String.compareTo around supplementary
        // characters. UTF-16 code-unit hex preserves the contract's Java ordering.
        key=orderedKey(key);
        requireOpen();try{var q=query("SELECT 1 FROM rows WHERE space=? AND key=?");
            q.setInt(1,space);q.setString(2,key);try(var r=q.executeQuery()){if(r.next())return false;}
            write(space,count(space),key,value);return true;
        }catch(SQLException failed){throw failure(failed);}
    }
    private static String orderedKey(String key){var encoded=new StringBuilder(key.length()*4);for(int i=0;i<key.length();i++){int c=key.charAt(i);for(int shift=12;shift>=0;shift-=4)encoded.append(Character.forDigit((c>>>shift)&15,16));}return encoded.toString();}
    private <T> Iterable<T> ordered(int space){
        return ()->new Iterator<>(){
            String after;boolean done;Integer next;
            private void advance(){if(next!=null||done)return;synchronized(FileAnalysisWorkspace.this){requireOpen();
                try{var q=query(after==null?"SELECT ordinal,key FROM rows WHERE space=? ORDER BY key LIMIT 1":"SELECT ordinal,key FROM rows WHERE space=? AND key>? ORDER BY key LIMIT 1");
                    q.setInt(1,space);if(after!=null)q.setString(2,after);try(var r=q.executeQuery()){if(r.next()){next=r.getInt(1);after=r.getString(2);}else done=true;}
                }catch(SQLException failed){throw failure(failed);}
            }}
            public boolean hasNext(){advance();return !done;}
            public T next(){if(!hasNext())throw new NoSuchElementException();int index=next;next=null;return read(space,index);}
        };
    }
    private final class Rows<T> extends AbstractList<T> {
        final int space;int size;boolean sealed;
        Rows(int space){this.space=space;}
        FileAnalysisWorkspace owner(){return FileAnalysisWorkspace.this;}
        public int size(){requireOpen();return size;}
        public T get(int index){Objects.checkIndex(index,size);return read(space,index);}
        public boolean add(T value){if(sealed)throw new UnsupportedOperationException();write(space,size,null,value);size++;return true;}
        public T set(int index,T value){if(sealed)throw new UnsupportedOperationException();Objects.checkIndex(index,size);T previous=get(index);write(space,index,null,value);return previous;}
    }
    private final class ReplayRows<T> extends ReplayList<T> {
        final int space,size;ReplayRows(int space,int size){this.space=space;this.size=size;}
        FileAnalysisWorkspace owner(){return FileAnalysisWorkspace.this;}
        public int size(){requireOpen();return size;}
        public T get(int index){Objects.checkIndex(index,size);return read(space,index);}
    }
    private synchronized void write(int space,int index,String key,Object value){
        requireOpen();
        try {
            long start=data.length();data.seek(start);var digest=sha256();
            var destination=new OutputStream(){public void write(int b)throws IOException{data.write(b);}public void write(byte[] b,int off,int len)throws IOException{data.write(b,off,len);}};
            try(var out=new DataOutputStream(new BufferedOutputStream(new DigestOutputStream(destination,digest),65536))){encode(out,value);}
            var q=query("INSERT OR REPLACE INTO rows VALUES(?,?,?,?,?,?)");q.setInt(1,space);q.setInt(2,index);q.setString(3,key);q.setLong(4,start);q.setLong(5,data.getFilePointer()-start);q.setBytes(6,digest.digest());q.executeUpdate();
            sizes.put(space,Math.max(sizes.get(space),index+1));
        }catch(IOException|SQLException failed){throw failure(failed);}
    }
    private <T> T read(int space,int index){return this.<T>readStored(space,index).value();}
    @SuppressWarnings("unchecked") private synchronized <T> Stored<T> readStored(int space,int index){
        requireOpen();try{var q=query("SELECT start,length,digest FROM rows WHERE space=? AND ordinal=?");
            q.setInt(1,space);q.setInt(2,index);try(var r=q.executeQuery()){
                if(!r.next())throw new IllegalStateException("ANALYSIS_SPOOL_ROW_MISSING");long start=r.getLong(1),length=r.getLong(2);byte[] expected=r.getBytes(3);
                var digest=sha256();try(var in=new DigestInputStream(slice(start,length),digest)){in.transferTo(OutputStream.nullOutputStream());}
                if(!MessageDigest.isEqual(digest.digest(),expected))throw new IOException("ANALYSIS_SPOOL_HASH_MISMATCH");
                try(var in=new DataInputStream(new BufferedInputStream(slice(start,length),65536))){Object value=decode(in);if(in.read()!=-1)throw new IOException("ANALYSIS_SPOOL_TRAILING_BYTES");return new Stored<>((T)value,new Location(start,length,expected));}
            }
        }catch(IOException|SQLException failed){throw failure(failed);}
    }
    private InputStream slice(long start,long size)throws IOException {
        if(start<0||size<0||start>data.length()-size)throw new IOException("ANALYSIS_SPOOL_RANGE");
        return new InputStream(){long remaining=size,position=start;final byte[] single=new byte[1];
            public int read()throws IOException{int n;do{n=read(single,0,1);}while(n==0);return n<0?-1:single[0]&255;}
            public int read(byte[] b,int off,int len)throws IOException{Objects.checkFromIndexSize(off,len,b.length);if(len==0)return 0;if(remaining==0)return -1;int n=data.getChannel().read(java.nio.ByteBuffer.wrap(b,off,(int)Math.min(remaining,len)),position);if(n<0)throw new EOFException();remaining-=n;position+=n;return n;}
        };
    }
    private int type(Class<?> type)throws IOException {
        if(!type.getName().startsWith("com.evolution.analysis.")||!(type.isRecord()||type.isEnum()))throw new IOException("ANALYSIS_SPOOL_NON_NEUTRAL_TYPE:"+type.getName());
        Integer id=classIds.get(type);if(id!=null)return id;int next=classes.size();classes.add(type);classIds.put(type,next);return next;
    }
    private RecordCodec codec(Class<?> type)throws IOException {
        var cached=codecs.get(type);if(cached!=null)return cached;
        if(!type.isRecord())throw new IOException("ANALYSIS_SPOOL_RECORD_TYPE");
        var fields=type.getRecordComponents();var accessors=new Method[fields.length];var parameters=new Class<?>[fields.length];
        for(int i=0;i<fields.length;i++){accessors[i]=fields[i].getAccessor();parameters[i]=fields[i].getType();}
        try{var result=new RecordCodec(accessors,type.getConstructor(parameters));codecs.put(type,result);return result;}
        catch(NoSuchMethodException failed){throw new IOException("ANALYSIS_SPOOL_RECORD_CONSTRUCTOR",failed);}
    }
    private void encode(DataOutputStream out,Object value)throws IOException {
        if(value==null){out.writeByte(0);return;}
        if(value instanceof String s){out.writeByte(1);string(out,s);return;}
        if(value instanceof Boolean b){out.writeByte(b?2:3);return;}
        if(value instanceof Integer i){out.writeByte(4);out.writeInt(i);return;}
        if(value instanceof Long l){out.writeByte(5);out.writeLong(l);return;}
        if(value instanceof Optional<?> o){out.writeByte(6);out.writeBoolean(o.isPresent());if(o.isPresent())encode(out,o.orElseThrow());return;}
        if(value instanceof List<?> l){out.writeByte(7);out.writeInt(l.size());for(var item:l)encode(out,item);return;}
        if(value instanceof Set<?> s){out.writeByte(8);out.writeInt(s.size());for(var item:s)encode(out,item);return;}
        if(value instanceof Map<?,?> m){out.writeByte(9);out.writeInt(m.size());for(var e:m.entrySet()){encode(out,e.getKey());encode(out,e.getValue());}return;}
        if(value instanceof Enum<?> e){out.writeByte(10);out.writeInt(type(e.getDeclaringClass()));out.writeInt(e.ordinal());return;}
        if(value instanceof BigDecimal n){out.writeByte(12);string(out,n.toString());return;}
        if(value instanceof BigInteger n){out.writeByte(13);string(out,n.toString());return;}
        if(value instanceof java.time.Instant i){out.writeByte(14);out.writeLong(i.getEpochSecond());out.writeInt(i.getNano());return;}
        if(value instanceof java.net.URI u){out.writeByte(15);string(out,u.toString());return;}
        out.writeByte(11);out.writeInt(type(value.getClass()));
        try{for(var accessor:codec(value.getClass()).accessors())encode(out,accessor.invoke(value));}
        catch(ReflectiveOperationException failed){throw new IOException("ANALYSIS_SPOOL_RECORD_WRITE",failed);}
    }
    private Object decode(DataInputStream in)throws IOException {
        return switch(in.readUnsignedByte()){
            case 0->null;case 1->string(in);case 2->true;case 3->false;case 4->in.readInt();case 5->in.readLong();
            case 6->in.readBoolean()?Optional.of(decode(in)):Optional.empty();
            case 7->{int n=length(in);var list=new ArrayList<>(n);for(int i=0;i<n;i++)list.add(decode(in));yield List.copyOf(list);}
            case 8->{int n=length(in);var set=new LinkedHashSet<>();for(int i=0;i<n;i++)set.add(decode(in));yield Collections.unmodifiableSet(set);}
            case 9->{int n=length(in);var map=new LinkedHashMap<>();for(int i=0;i<n;i++)map.put(decode(in),decode(in));yield Collections.unmodifiableMap(map);}
            case 10->{Class<?> cls=registered(in);int ordinal=in.readInt();Object[] values=cls.getEnumConstants();if(values==null||ordinal<0||ordinal>=values.length)throw new IOException("ANALYSIS_SPOOL_ENUM");yield values[ordinal];}
            case 11->{Class<?> cls=registered(in);var metadata=codec(cls);Object[] args=new Object[metadata.accessors().length];for(int i=0;i<args.length;i++)args[i]=decode(in);
                try{yield metadata.constructor().newInstance(args);}catch(ReflectiveOperationException failed){throw new IOException("ANALYSIS_SPOOL_RECORD_READ:"+cls.getSimpleName(),failed);}}
            case 12->new BigDecimal(string(in));case 13->new BigInteger(string(in));case 14->java.time.Instant.ofEpochSecond(in.readLong(),in.readInt());case 15->java.net.URI.create(string(in));
            default->throw new IOException("ANALYSIS_SPOOL_TAG");
        };
    }
    private Class<?> registered(DataInputStream in)throws IOException {int id=in.readInt();if(id<0||id>=classes.size())throw new IOException("ANALYSIS_SPOOL_TYPE_ID");return classes.get(id);}
    private static int length(DataInputStream in)throws IOException {int size=in.readInt();if(size<0||size>256_000_000)throw new IOException("ANALYSIS_SPOOL_ROW_CAPACITY");return size;}
    // Preserve exact Java code units, including strings with unpaired surrogates.
    // UTF-8's default replacement must never silently alter neutral evidence.
    private static void string(DataOutputStream out,String value)throws IOException {out.writeInt(value.length());for(int i=0;i<value.length();i++)out.writeChar(value.charAt(i));}
    private static String string(DataInputStream in)throws IOException {int n=length(in);char[] text=new char[n];for(int i=0;i<n;i++)text[i]=in.readChar();return new String(text);}
    private static MessageDigest sha256(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private static IllegalStateException failure(Exception failed){if(Thread.currentThread().isInterrupted()){var cancelled=new java.util.concurrent.CancellationException("ANALYSIS_SPOOL_CANCELLED");cancelled.initCause(failed);throw cancelled;}return new IllegalStateException("ANALYSIS_SPOOL_FAILED",failed);}
    private static IOException retain(IOException first,IOException next){if(first==null)return next;if(first!=next)first.addSuppressed(next);return first;}
    @Override public synchronized void close()throws IOException {
        if(closed)return;closed=true;IOException failure=null;
        try{db.close();}catch(SQLException failed){failure=new IOException("ANALYSIS_SPOOL_CLOSE",failed);}
        try{data.close();}catch(IOException failed){failure=retain(failure,failed);}
        // Only this constructor's private flat directory; never follow links or remove caller files.
        try(var paths=Files.list(directory)){for(var path:paths.toList())Files.delete(path);}catch(IOException failed){failure=retain(failure,failed);}
        try{Files.delete(directory);}catch(IOException failed){failure=retain(failure,failed);}
        if(failure!=null)throw failure;
    }
    private static void cleanStale(Path parent) {
        try (var stream = Files.list(parent)) {
            for (var path : stream.toList()) {
                if (Files.isDirectory(path) && path.getFileName().toString().startsWith("analysis-")) {
                    try (var files = Files.list(path)) {
                        for (var f : files.toList()) Files.deleteIfExists(f);
                    } catch (IOException ignored) {}
                    try { Files.deleteIfExists(path); } catch (IOException ignored) {}
                }
            }
        } catch (IOException ignored) {}
    }
}
