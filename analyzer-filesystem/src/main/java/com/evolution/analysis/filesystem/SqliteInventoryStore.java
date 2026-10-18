package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.SnapshotFile;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Single-writer local index. An fsynced hash-chained operation log is authoritative and rebuildable.
 * No SQL type crosses the acquisition ports. Never place this store on a network filesystem. */
public final class SqliteInventoryStore implements InventoryReader, JobJournal, AutoCloseable {
    private final Path root;
    private final StreamingContentStore content;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private Connection db;
    private final boolean readOnly;
    private Runnable afterLogPublication=()->{};
    private boolean recoveryRequired;
    SqliteInventoryStore(Path root,StreamingContentStore content,Runnable afterLogPublication)throws IOException{this(root,content);this.afterLogPublication=Objects.requireNonNull(afterLogPublication);}
    private ContentDigest head = ContentDigest.sha256Utf8("inventory-journal:m8.4-v2");
    private long sequence;
    public SqliteInventoryStore(Path root, StreamingContentStore content) throws IOException {
        this(root,content,false);
    }
    public SqliteInventoryStore(Path root,StreamingContentStore content,boolean readOnly)throws IOException{
        this.readOnly=readOnly;
        this.root = StreamingContentStore.directory(root); this.content = Objects.requireNonNull(content);
        if(readOnly){
            lockChannel=null;lock=null;Path path=this.root.resolve("index.sqlite");StreamingContentStore.safe(path);
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("JOURNAL_NOT_FOUND");
            try{db=DriverManager.getConnection("jdbc:sqlite:"+path.toUri()+"?mode=ro");try(var sql=db.createStatement()){sql.execute("PRAGMA query_only=ON");sql.execute("PRAGMA busy_timeout=5000");}return;}
            catch(SQLException unavailable){throw new IOException("JOURNAL_UNAVAILABLE",unavailable);}
        }
        Path lockPath = this.root.resolve("writer.lock"); StreamingContentStore.safe(lockPath);
        lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try { lock = lockChannel.tryLock(); }
        catch (OverlappingFileLockException busy) { lockChannel.close(); throw new IOException("JOURNAL_BUSY"); }
        if (lock == null) { lockChannel.close(); throw new IOException("JOURNAL_BUSY"); }
        try {
            Path database = this.root.resolve("index.sqlite"); StreamingContentStore.safe(database);
            // The database is derived: rebuild on open from immutable operations, including after an interrupted SQL commit.
            if (Files.exists(database)) {
                try { connect(database); integrity(); }
                catch (SQLException corrupt) { if (db != null) db.close(); db = null; quarantine(database); }
            }
            if (db == null) connect(database);
            try (var sql = db.createStatement()) {
                sql.execute("CREATE TABLE IF NOT EXISTS inventory(revision TEXT NOT NULL,k TEXT NOT NULL,v BLOB NOT NULL,stamp TEXT NOT NULL,kind TEXT NOT NULL,status TEXT NOT NULL,v1key TEXT,PRIMARY KEY(revision,k)) WITHOUT ROWID");
                sql.execute("CREATE INDEX IF NOT EXISTS inventory_v1key ON inventory(revision,v1key)");
                sql.execute("CREATE TABLE IF NOT EXISTS jobs(id TEXT PRIMARY KEY,request TEXT NOT NULL,revision INTEGER NOT NULL,state TEXT NOT NULL,reason TEXT NOT NULL,digest TEXT,size INTEGER,sequence INTEGER NOT NULL)");
                sql.execute("CREATE INDEX IF NOT EXISTS jobs_request ON jobs(request)");
                sql.execute("CREATE TABLE IF NOT EXISTS seals(revision TEXT PRIMARY KEY,repository TEXT NOT NULL,selection TEXT NOT NULL,digest TEXT NOT NULL,entries INTEGER,files INTEGER,bytes INTEGER,excluded INTEGER,v1snapshot TEXT,v1content TEXT)");
                sql.execute("DELETE FROM inventory"); sql.execute("DELETE FROM jobs"); sql.execute("DELETE FROM seals");
            }
            replay(); integrity();
        } catch (SQLException | IOException | RuntimeException failure) {
            try { close(); } catch (IOException ignored) { }
            throw new IOException("JOURNAL_UNAVAILABLE", failure);
        }
    }
    private void connect(Path path) throws SQLException,IOException {
        db = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (var sql = db.createStatement()) {
            try(var r=sql.executeQuery("PRAGMA application_id")){if(r.next()&&r.getInt(1)!=0&&r.getInt(1)!=0x45564f32)throw new IOException("FOREIGN_METADATA_DATABASE");}
            try(var r=sql.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")){if(r.next()&&r.getInt(1)>0){try(var check=db.createStatement();var id=check.executeQuery("PRAGMA application_id")){if(!id.next()||id.getInt(1)!=0x45564f32)throw new IOException("FOREIGN_METADATA_DATABASE");}}}
            try (var result = sql.executeQuery("PRAGMA user_version")) { if (result.next() && result.getInt(1) > 2) throw new IOException("UNSUPPORTED_METADATA_SCHEMA"); }
            sql.execute("PRAGMA journal_mode=WAL"); sql.execute("PRAGMA synchronous=FULL"); sql.execute("PRAGMA busy_timeout=5000");
            sql.execute("PRAGMA temp_store=FILE"); sql.execute("PRAGMA cache_size=-8192");
            sql.execute("PRAGMA application_id="+0x45564f32);
            sql.execute("PRAGMA user_version=2");
        }
    }
    private void quarantine(Path database) throws IOException {
        String suffix = ".corrupt-" + UUID.randomUUID();
        for (String sidecar : List.of("", "-wal", "-shm")) {
            Path file = Path.of(database + sidecar); StreamingContentStore.safe(file);
            if (Files.exists(file)) Files.move(file, Path.of(file + suffix));
        }
    }
    private void integrity() throws SQLException {
        try (var s = db.createStatement(); var r = s.executeQuery("PRAGMA quick_check")) { if (!r.next() || !"ok".equals(r.getString(1))) throw new SQLException("Corrupt metadata"); }
    }
    private void replay() throws IOException, SQLException {
        Path operations = StreamingContentStore.directory(root.resolve("operations"));
        for (long next = 1; ; next++) {
            Path path = operations.resolve(String.format(Locale.ROOT, "%020d.commit", next));
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) break;
            StreamingContentStore.safe(path);
            try (var in = new DataInputStream(Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS))) {
                if (in.readLong() != next || !InventoryCodec.text(in).equals(head.value())) throw new IOException("JOURNAL_CHAIN_CORRUPT");
                var handle = InventoryCodec.handle(in).orElseThrow();
                if (in.read() != -1 || !content.verify(handle)) throw new IOException("JOURNAL_CONTENT_CORRUPT");
                try (var payload = content.open(handle)) { apply(payload, next); }
                head = CanonicalJson.sha256(List.of(head, handle)); sequence = next;
            }
        }
        try(var paths=Files.newDirectoryStream(operations,"*.commit")){for(var path:paths){String name=path.getFileName().toString();if(!name.matches("[0-9]{20}\\.commit")||Long.parseLong(name.substring(0,20))>sequence)throw new IOException("JOURNAL_CHAIN_INCOMPLETE");}}
    }
    private synchronized void commit(byte[] operation) throws IOException {
        boolean interrupted=Thread.interrupted();
        try{commitMetadata(operation);}finally{if(interrupted)Thread.currentThread().interrupt();}
    }
    /** A cancellation token cannot cancel the tiny durable checkpoint that records that cancellation. */
    private void commitMetadata(byte[] operation)throws IOException{
        if(readOnly)throw new IOException("READ_ONLY_JOURNAL");
        if(recoveryRequired)throw new IOException("JOURNAL_INDEX_RECOVERY_REQUIRED");
        ContentHandle handle = content.put(new ByteArrayInputStream(operation), () -> false);
        long next = Math.addExact(sequence, 1); Path target = root.resolve("operations").resolve(String.format(Locale.ROOT, "%020d.commit", next));
        Path temp = Files.createTempFile(root.resolve("operations"), "op-", ".part");
        try {
            try (var file = FileChannel.open(temp, StandardOpenOption.WRITE); var out = new DataOutputStream(Channels.newOutputStream(file))) {
                out.writeLong(next); InventoryCodec.text(out, head.value()); InventoryCodec.handle(out, Optional.of(handle)); out.flush(); file.force(true);
            }
            Files.createLink(target, temp);
            afterLogPublication.run();
            try { apply(new ByteArrayInputStream(operation), next); }
            catch (SQLException|IOException failure) { recoveryRequired=true;throw new IOException("JOURNAL_INDEX_RECOVERY_REQUIRED", failure); }
            sequence = next; head = CanonicalJson.sha256(List.of(head, handle));
        } finally { Files.deleteIfExists(temp); }
    }
    private void apply(InputStream payload, long seq) throws IOException, SQLException {
        db.setAutoCommit(false);
        try (var in = new DataInputStream(payload)) {
            String type = InventoryCodec.text(in);
            switch (type) {
                case "entries" -> {
                    String revision = InventoryCodec.text(in); int count = in.readInt();
                    if (count < 0 || count > 256) throw new IOException("CORRUPT_OPERATION");
                    try (var p = db.prepareStatement("INSERT INTO inventory VALUES(?,?,?,?,?,?,?) ON CONFLICT(revision,k) DO UPDATE SET v=excluded.v,stamp=excluded.stamp,kind=excluded.kind,status=excluded.status,v1key=excluded.v1key")) {
                        for (int i = 0; i < count; i++) {
                            String stamp = InventoryCodec.text(in); int length = in.readInt();
                            if (length < 0 || length > 8_388_608) throw new IOException("CORRUPT_OPERATION");
                            byte[] bytes = in.readNBytes(length); if (bytes.length != length) throw new EOFException();
                            var e = InventoryCodec.entry(bytes); p.setString(1, revision); p.setString(2, e.path().key()); p.setBytes(3, bytes); p.setString(4, stamp); p.setString(5, e.kind().name()); p.setString(6, e.disposition().name());p.setString(7,v1key(e.path())); p.addBatch();
                        } p.executeBatch();
                    }
                }
                case "job" -> {
                    String id = InventoryCodec.text(in), request = InventoryCodec.text(in); long revision = in.readLong(); String state = InventoryCodec.text(in), reason = InventoryCodec.text(in); var h = InventoryCodec.handle(in);
                    try (var p = db.prepareStatement("INSERT INTO jobs VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET revision=excluded.revision,state=excluded.state,reason=excluded.reason,digest=excluded.digest,size=excluded.size")) {
                        p.setString(1,id); p.setString(2,request); p.setLong(3,revision); p.setString(4,state); p.setString(5,reason); p.setString(6,h.map(v->v.digest().value()).orElse(null));
                        if (h.isPresent()) p.setLong(7,h.orElseThrow().size()); else p.setNull(7,Types.INTEGER); p.setLong(8,seq); p.executeUpdate();
                    }
                }
                case "seal" -> {
                    String revision = InventoryCodec.text(in);
                    try (var p = db.prepareStatement("INSERT INTO seals VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                        p.setString(1,revision); for (int i=2;i<=4;i++) p.setString(i,InventoryCodec.text(in));
                        for (int i=5;i<=8;i++) p.setLong(i,in.readLong()); p.setString(9,InventoryCodec.optional(in).orElse(null)); p.setString(10,InventoryCodec.optional(in).orElse(null)); p.executeUpdate();
                    }
                }
                default -> throw new IOException("UNKNOWN_OPERATION");
            }
            if (in.read() != -1) throw new IOException("CORRUPT_OPERATION"); db.commit();
        } catch (IOException | SQLException | RuntimeException failure) { db.rollback(); throw failure; }
        finally { db.setAutoCommit(true); }
    }
    public record Observation(InventoryEntry entry, String stamp) {}
    public synchronized void entries(String revision, List<Observation> observations) throws IOException {
        if (manifest(revision).isPresent()) throw new IOException("INVENTORY_ALREADY_SEALED");
        if (observations.isEmpty() || observations.size()>256) throw new IllegalArgumentException("Invalid inventory batch");
        var bytes = new ByteArrayOutputStream(); try (var out = new DataOutputStream(bytes)) {
            InventoryCodec.text(out,"entries"); InventoryCodec.text(out,revision); out.writeInt(observations.size());
            for (var observation : observations) { InventoryCodec.text(out,observation.stamp()); byte[] entry=InventoryCodec.entry(observation.entry()); out.writeInt(entry.length); out.write(entry); }
        } commit(bytes.toByteArray());
    }
    public synchronized Optional<Observation> observation(String revision, LogicalPath path) throws IOException {
        try (var p=db.prepareStatement("SELECT v,stamp FROM inventory WHERE revision=? AND k=?")) {
            p.setString(1,revision); p.setString(2,path.key()); try (var r=p.executeQuery()) { return r.next()?Optional.of(new Observation(InventoryCodec.entry(r.getBytes(1)),r.getString(2))):Optional.empty(); }
        } catch (SQLException failure) { throw new IOException("INVENTORY_UNAVAILABLE",failure); }
    }
    @Override public synchronized Page page(String revision,int size,Optional<String> cursor) throws IOException {
        if (size<1||size>1000) throw new IllegalArgumentException("Invalid page size");
        String after="";
        if(cursor.isPresent()) { String[] parts=cursor.orElseThrow().split(":",-1); if(parts.length!=2||!parts[0].equals(ContentDigest.sha256Utf8(revision).value().substring(7))||!parts[1].matches("[0-9a-f]+"))throw new IllegalArgumentException("Foreign inventory cursor"); after=parts[1]; }
        try(var p=db.prepareStatement("SELECT v,k FROM inventory WHERE revision=? AND k>? ORDER BY k LIMIT ?")) {
            p.setString(1,revision);p.setString(2,after);p.setInt(3,size+1); var entries=new ArrayList<InventoryEntry>();String last=after;boolean more=false;
            try(var r=p.executeQuery()){while(r.next()){if(entries.size()==size){more=true;break;}entries.add(InventoryCodec.entry(r.getBytes(1)));last=r.getString(2);}}
            return new Page(entries,more?Optional.of(ContentDigest.sha256Utf8(revision).value().substring(7)+":"+last):Optional.empty());
        }catch(SQLException failure){throw new IOException("INVENTORY_UNAVAILABLE",failure);}
    }
    public synchronized InventoryManifest seal(String revision,RepositoryIdentity repository,ContentDigest selection) throws IOException {
        var existing=manifest(revision); if(existing.isPresent())return existing.orElseThrow();
        var frontier=get(revision);if(frontier.isEmpty()||frontier.orElseThrow().state()!=State.RUNNING||!frontier.orElseThrow().reason().equals("FRONTIER_CLOSED"))throw new IOException("INVENTORY_FRONTIER_OPEN");
        long count=0,files=0,bytes=0,excluded=0; boolean compatible=true;
        var digest=StreamingContentStore.sha256();var v1=StreamingContentStore.sha256();
        var verified=new LinkedHashMap<ContentHandle,Boolean>(128,0.75f,true){@Override protected boolean removeEldestEntry(Map.Entry<ContentHandle,Boolean> entry){return size()>1024;}};
        try(var writer=new BufferedWriter(new OutputStreamWriter(new java.security.DigestOutputStream(OutputStream.nullOutputStream(),digest),java.nio.charset.StandardCharsets.UTF_8));
            var legacy=new BufferedWriter(new OutputStreamWriter(new java.security.DigestOutputStream(OutputStream.nullOutputStream(),v1),java.nio.charset.StandardCharsets.UTF_8))){
            writer.write('[');legacy.write('[');boolean first=true;Optional<String> cursor=Optional.empty();
            do{var page=page(revision,256,cursor);for(var entry:page.entries()){
                if(entry.disposition()==InventoryEntry.Disposition.PENDING||entry.disposition()==InventoryEntry.Disposition.GAP)throw new IOException("INVENTORY_FRONTIER_OPEN");
                if(!first)writer.write(',');first=false;CanonicalJson.writeTo(entry,writer);count=Math.addExact(count,1);
                if(entry.disposition()==InventoryEntry.Disposition.EXCLUDED){excluded=Math.addExact(excluded,1);continue;}
                if(entry.content().isPresent()&&!verified.containsKey(entry.content().orElseThrow())){if(!content.verify(entry.content().orElseThrow()))throw new IOException("CAS_CORRUPT");verified.put(entry.content().orElseThrow(),true);}
                if(entry.kind()==InventoryEntry.Kind.DIRECTORY)continue;
                if(entry.kind()==InventoryEntry.Kind.GITLINK){compatible=false;continue;}
                files=Math.addExact(files,1);bytes=Math.addExact(bytes,entry.content().map(ContentHandle::size).orElse(0L));
                try{
                    String path=ContractChecks.repositoryRelativePath(entry.path().utf8().orElseThrow(),"v1 path");
                    if(entry.kind()!=InventoryEntry.Kind.FILE||!entry.mode().equals("100644"))compatible=false;
                }catch(IllegalArgumentException|NoSuchElementException invalid){compatible=false;}
            }cursor=page.continuation();}while(cursor.isPresent());writer.write(']');
            try(var p=db.prepareStatement("SELECT v FROM inventory WHERE revision=? AND kind='FILE' AND status='ACQUIRED' ORDER BY v1key")){
                p.setString(1,revision);boolean firstV1=true;try(var rows=p.executeQuery()){while(rows.next()){
                    var entry=InventoryCodec.entry(rows.getBytes(1));try{var file=new SnapshotFile(entry.path().utf8().orElseThrow(),entry.content().orElseThrow().digest());if(!firstV1)legacy.write(',');firstV1=false;CanonicalJson.writeTo(file,legacy);}catch(IllegalArgumentException|NoSuchElementException incompatible){compatible=false;}
                }}
            }catch(SQLException failure){throw new IOException("INVENTORY_UNAVAILABLE",failure);}legacy.write(']');
        }
        ContentDigest inventory=new ContentDigest("sha256:"+HexFormat.of().formatHex(digest.digest()));ContentDigest old=new ContentDigest("sha256:"+HexFormat.of().formatHex(v1.digest()));
        var result=new InventoryManifest(InventoryManifest.SCHEMA,repository,selection,inventory,count,files,bytes,excluded,compatible?Optional.of(SnapshotIdentity.from(repository,old)):Optional.empty(),compatible?Optional.of(old):Optional.empty());
        var operation=new ByteArrayOutputStream();try(var out=new DataOutputStream(operation)){
            InventoryCodec.text(out,"seal");InventoryCodec.text(out,revision);InventoryCodec.text(out,repository.value());InventoryCodec.text(out,selection.value());InventoryCodec.text(out,inventory.value());
            out.writeLong(count);out.writeLong(files);out.writeLong(bytes);out.writeLong(excluded);InventoryCodec.optional(out,result.compatibleV1Snapshot().map(SnapshotIdentity::value));InventoryCodec.optional(out,result.compatibleV1Content().map(ContentDigest::value));
        }commit(operation.toByteArray());return result;
    }
    private static String v1key(LogicalPath path){return path.utf8().map(s->{var key=new StringBuilder(s.length()*4);for(int i=0;i<s.length();i++){String hex=Integer.toHexString(s.charAt(i));key.append("0".repeat(4-hex.length())).append(hex);}return key.toString();}).orElse(null);}
    @Override public synchronized Optional<InventoryManifest> manifest(String revision)throws IOException{
        try(var p=db.prepareStatement("SELECT * FROM seals WHERE revision=?")){p.setString(1,revision);try(var r=p.executeQuery()){
            if(!r.next())return Optional.empty();return Optional.of(new InventoryManifest(InventoryManifest.SCHEMA,new RepositoryIdentity(r.getString("repository")),new ContentDigest(r.getString("selection")),new ContentDigest(r.getString("digest")),r.getLong("entries"),r.getLong("files"),r.getLong("bytes"),r.getLong("excluded"),Optional.ofNullable(r.getString("v1snapshot")).map(SnapshotIdentity::new),Optional.ofNullable(r.getString("v1content")).map(ContentDigest::new)));
        }}catch(SQLException failure){throw new IOException("INVENTORY_UNAVAILABLE",failure);}
    }
    public synchronized boolean verifySeal(String revision,java.util.function.BooleanSupplier cancelled)throws IOException{
        var sealed=manifest(revision);if(sealed.isEmpty())return false;var hash=StreamingContentStore.sha256();
        var verified=new LinkedHashMap<ContentHandle,Boolean>(128,0.75f,true){@Override protected boolean removeEldestEntry(Map.Entry<ContentHandle,Boolean> entry){return size()>1024;}};
        try(var writer=new BufferedWriter(new OutputStreamWriter(new java.security.DigestOutputStream(OutputStream.nullOutputStream(),hash),java.nio.charset.StandardCharsets.UTF_8))){
            writer.write('[');boolean first=true;Optional<String> cursor=Optional.empty();do{var page=page(revision,256,cursor);for(var entry:page.entries()){
                if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();if(!first)writer.write(',');first=false;CanonicalJson.writeTo(entry,writer);
                if(entry.content().isPresent()&&!verified.containsKey(entry.content().orElseThrow())){if(!content.verify(entry.content().orElseThrow()))return false;verified.put(entry.content().orElseThrow(),true);}
            }cursor=page.continuation();}while(cursor.isPresent());writer.write(']');
        }catch(IOException unavailable){return false;}
        return sealed.orElseThrow().inventory().value().equals("sha256:"+HexFormat.of().formatHex(hash.digest()));
    }
    @Override public synchronized Record create(String id,ContentDigest request,Optional<ContentHandle> payload)throws IOException{
        var existing=get(id);if(existing.isPresent()){
            try(var p=db.prepareStatement("SELECT request FROM jobs WHERE id=?")){p.setString(1,id);try(var r=p.executeQuery()){if(!r.next()||!r.getString(1).equals(request.value()))throw new IOException("JOURNAL_REQUEST_CONFLICT");}}catch(SQLException failure){throw new IOException("JOURNAL_UNAVAILABLE",failure);}return existing.orElseThrow();
        }return writeJob(new Record(id,0,State.PENDING,"",payload),request.value());
    }
    @Override public synchronized Optional<Record> get(String id)throws IOException{
        try(var p=db.prepareStatement("SELECT * FROM jobs WHERE id=?")){p.setString(1,id);try(var r=p.executeQuery()){return r.next()?Optional.of(job(r)):Optional.empty();}}catch(SQLException failure){throw new IOException("JOURNAL_UNAVAILABLE",failure);}
    }
    private Record job(ResultSet r)throws SQLException{return new Record(r.getString("id"),r.getLong("revision"),State.valueOf(r.getString("state")),r.getString("reason"),Optional.ofNullable(r.getString("digest")).map(d->{try{return new ContentHandle(new ContentDigest(d),r.getLong("size"));}catch(SQLException e){throw new IllegalStateException(e);}}));}
    @Override public synchronized Record transition(String id,long expectedRevision,State state,String reason,Optional<ContentHandle> payload)throws IOException{
        boolean interrupted=Thread.interrupted();
        try{return transitionMetadata(id,expectedRevision,state,reason,payload);}finally{if(interrupted)Thread.currentThread().interrupt();}
    }
    private Record transitionMetadata(String id,long expectedRevision,State state,String reason,Optional<ContentHandle> payload)throws IOException{
        var current=get(id).orElseThrow(()->new IOException("JOURNAL_JOB_MISSING"));
        if(current.revision()!=expectedRevision||current.state()==State.COMPLETE||current.state()==State.CANCELLED||current.state()==State.FAILED)throw new IOException("JOURNAL_TRANSITION_CONFLICT");
        if(payload.isPresent()&&!content.verify(payload.orElseThrow()))throw new IOException("JOURNAL_PAYLOAD_UNAVAILABLE");
        try(var p=db.prepareStatement("SELECT request FROM jobs WHERE id=?")){p.setString(1,id);try(var r=p.executeQuery()){r.next();return writeJob(new Record(id,Math.addExact(expectedRevision,1),state,reason,payload),r.getString(1));}}catch(SQLException failure){throw new IOException("JOURNAL_UNAVAILABLE",failure);}
    }
    private Record writeJob(Record record,String request)throws IOException{
        var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){InventoryCodec.text(out,"job");InventoryCodec.text(out,record.id());InventoryCodec.text(out,request);out.writeLong(record.revision());InventoryCodec.text(out,record.state().name());InventoryCodec.text(out,record.reason());InventoryCodec.handle(out,record.payload());}commit(bytes.toByteArray());return record;
    }
    @Override public synchronized List<Record> history(long afterSequence,int size)throws IOException{
        if(afterSequence<0||size<1||size>1000)throw new IllegalArgumentException("Invalid history page");
        try(var p=db.prepareStatement("SELECT * FROM jobs WHERE sequence>? ORDER BY sequence LIMIT ?")){p.setLong(1,afterSequence);p.setInt(2,size);var rows=new ArrayList<Record>();try(var r=p.executeQuery()){while(r.next())rows.add(job(r));}return List.copyOf(rows);}catch(SQLException failure){throw new IOException("JOURNAL_UNAVAILABLE",failure);}
    }
    @Override public synchronized HistoryPage historyPage(long afterSequence,int size)throws IOException{
        return historyPage(afterSequence,size,0);
    }
    @Override public synchronized HistoryPage historyPage(long afterSequence,int size,long highWaterSequence)throws IOException{
        if(afterSequence<0||size<1||size>1000)throw new IllegalArgumentException("Invalid history page");
        long high=highWaterSequence;
        try{
            if(high<0)throw new IllegalArgumentException("Invalid history high-water mark");
            if(high==0)try(var s=db.createStatement();var r=s.executeQuery("SELECT coalesce(max(sequence),0) FROM jobs")){r.next();high=r.getLong(1);}
        }catch(SQLException failure){throw new IOException("JOURNAL_UNAVAILABLE",failure);}
        try(var p=db.prepareStatement("SELECT * FROM jobs WHERE sequence>? AND sequence<=? ORDER BY sequence LIMIT ?")){
            p.setLong(1,afterSequence);p.setLong(2,high);p.setInt(3,size+1);var rows=new ArrayList<HistoryEntry>();boolean more=false;long last=afterSequence;
            try(var r=p.executeQuery()){while(r.next()){if(rows.size()==size){more=true;break;}last=r.getLong("sequence");rows.add(new HistoryEntry(last,job(r)));}}
            long total;try(var s=db.prepareStatement("SELECT count(*) FROM jobs WHERE sequence<=?")){s.setLong(1,high);try(var r=s.executeQuery()){r.next();total=r.getLong(1);}}
            return new HistoryPage(rows,total,more?Optional.of(last):Optional.empty(),high);
        }catch(SQLException failure){throw new IOException("JOURNAL_UNAVAILABLE",failure);}
    }
    @Override public synchronized Optional<Record> byRequest(ContentDigest request)throws IOException{
        try(var p=db.prepareStatement("SELECT * FROM jobs WHERE request=? ORDER BY sequence LIMIT 2")){p.setString(1,request.value());try(var r=p.executeQuery()){
            if(!r.next())return Optional.empty();var row=job(r);if(r.next())throw new IOException("JOURNAL_REQUEST_AMBIGUOUS");return Optional.of(row);
        }}catch(SQLException failure){throw new IOException("JOURNAL_UNAVAILABLE",failure);}
    }
    @Override public synchronized List<Record> waiting(String reason,int size)throws IOException{
        if(size<1||size>1000)throw new IllegalArgumentException("Invalid waiting page");
        try(var p=db.prepareStatement("SELECT * FROM jobs WHERE reason=? ORDER BY sequence LIMIT ?")){p.setString(1,reason);p.setInt(2,size);var rows=new ArrayList<Record>();try(var r=p.executeQuery()){while(r.next())rows.add(job(r));}return List.copyOf(rows);}catch(SQLException failure){throw new IOException("JOURNAL_UNAVAILABLE",failure);}
    }
    @Override public synchronized void close()throws IOException{
        try{if(db!=null){db.close();db=null;}}catch(SQLException failure){throw new IOException("JOURNAL_CLOSE_FAILED",failure);}finally{if(lock!=null&&lock.isValid())lock.release();if(lockChannel!=null)lockChannel.close();}
    }
}
