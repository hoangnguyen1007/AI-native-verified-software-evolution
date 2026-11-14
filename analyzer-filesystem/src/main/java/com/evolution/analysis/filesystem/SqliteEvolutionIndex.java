package com.evolution.analysis.filesystem;

import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evolution.EvolutionIndex;
import com.evolution.analysis.graph.GraphJson;
import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Request-private external sort. Neither SQLite nor its temporary rows authenticate graph facts. */
final class SqliteEvolutionIndex implements EvolutionIndex {
    private static final int ROW_LIMIT=64_000_000;
    private static final int PAGE_CHARACTERS=1_048_576;
    private final Path file;
    private final Connection connection;
    private final PreparedStatement get,put,page;
    private final BooleanSupplier cancelled;
    private boolean closed;

    SqliteEvolutionIndex(Path scratch,BooleanSupplier cancelled)throws IOException {
        this.cancelled=Objects.requireNonNull(cancelled);
        file=Files.createTempFile(StreamingContentStore.directory(scratch),"evolution-",".sqlite");
        Connection opened=null;
        try {
            opened=DriverManager.getConnection("jdbc:sqlite:"+file);
            try(var sql=opened.createStatement()) {
                sql.execute("PRAGMA journal_mode=DELETE");
                sql.execute("PRAGMA cache_size=-2048");
                sql.execute("PRAGMA temp_store=FILE");
                sql.execute("CREATE TABLE comparison_rows (space TEXT NOT NULL, k TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(space,k)) WITHOUT ROWID");
            }
            opened.setAutoCommit(false);
            get=opened.prepareStatement("SELECT payload FROM comparison_rows WHERE space=? AND k=?");
            put=opened.prepareStatement("INSERT INTO comparison_rows VALUES (?,?,?) ON CONFLICT(space,k) DO UPDATE SET payload=excluded.payload");
            page=opened.prepareStatement("SELECT k,payload FROM comparison_rows WHERE space=? AND k>? ORDER BY k LIMIT ?");
            connection=opened;
        }catch(SQLException error) {
            var failure=storage(error);
            if(opened!=null)try{opened.close();}catch(SQLException close){failure.addSuppressed(close);}
            try{Files.deleteIfExists(file);}catch(IOException cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
    }

    public Optional<Map<String,Object>> get(String table,String key)throws IOException {
        check();
        try {
            get.setString(1,table);get.setString(2,key);
            try(var rows=get.executeQuery()){return rows.next()?Optional.of(decode(rows.getString(1))):Optional.empty();}
        }catch(SQLException failure){throw storage(failure);}
    }

    public void put(String table,String key,Map<String,Object> value)throws IOException {
        check();
        String encoded=CanonicalJson.write(value);
        if(encoded.length()>ROW_LIMIT)throw new IOException("EVOLUTION_INDEX_ROW_RESOURCE_LIMIT");
        try {
            put.setString(1,table);put.setString(2,key);put.setString(3,encoded);put.executeUpdate();
        }catch(SQLException failure){throw storage(failure);}
    }

    public Batch page(String table,String after,int size)throws IOException {
        check();
        var result=new ArrayList<Entry>();
        try {
            page.setString(1,table);page.setString(2,after);page.setInt(3,size+1);
            try(var rows=page.executeQuery()){
                int characters=0;
                while(rows.next()){
                    check();if(result.size()==size||characters>=PAGE_CHARACTERS)return new Batch(result,true);
                    var encoded=rows.getString(2);characters+=encoded.length();
                    result.add(new Entry(rows.getString(1),decode(encoded)));
                }
            }
            return new Batch(result,false);
        }catch(SQLException failure){throw storage(failure);}
    }

    private static Map<String,Object> decode(String value){return GraphJson.object(GraphJson.read(value,GraphJson.Limits.defaults()));}
    private void check()throws IOException {
        if(closed)throw new IOException("EVOLUTION_INDEX_CLOSED");
        if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
    }
    private static IOException storage(SQLException error){return new IOException(error.getErrorCode()==13?
            "EVOLUTION_INDEX_RESOURCE_LIMIT":"EVOLUTION_INDEX_UNAVAILABLE",error);}

    @Override public void close()throws IOException {
        if(closed)return;closed=true;
        IOException failure=null;
        try{connection.close();}catch(SQLException error){failure=storage(error);}
        try{Files.deleteIfExists(file);}catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        if(failure!=null)throw failure;
    }
}
