package com.evolution.analysis.filesystem;

import com.evolution.analysis.graph.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.function.*;

/** External-memory canonical projection, private to one build. SQLite is scratch, never authority. */
final class SqliteGraphProjection implements GraphProjectionStore {
    private final Connection database;
    private final int rowBytes;
    private final BooleanSupplier cancelled;
    // This request-private connection is single-owner; it closes these four hot
    // statements along with the scratch database. Page cursors remain independent.
    private final PreparedStatement lookup,exists,upsert,count;
    SqliteGraphProjection(Connection database,int rowBytes,BooleanSupplier cancelled)throws SQLException {
        this.database=database;this.rowBytes=rowBytes;this.cancelled=cancelled;
        lookup=database.prepareStatement("SELECT payload FROM rows WHERE space=? AND k=?");
        exists=database.prepareStatement("SELECT 1 FROM rows WHERE space=? AND k=?");
        upsert=database.prepareStatement("INSERT INTO rows(space,k,payload) VALUES(?,?,?) ON CONFLICT(space,k) DO UPDATE SET payload=excluded.payload");
        count=database.prepareStatement("SELECT COUNT(*) FROM rows WHERE space=?");
    }
    public <T> Map<String,T> records(String space,Function<Object,T> decode) {
        return new AbstractMap<>() {
            private void check() {if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();}
            private T read(String encoded) {return decode.apply(GraphJson.read(encoded,new GraphJson.Limits(rowBytes,128,Integer.MAX_VALUE)));}
            public T get(Object key) {
                check();try {var query=lookup;
                    query.setString(1,space);query.setString(2,(String)key);
                    try(var rows=query.executeQuery()){return rows.next()?read(rows.getString(1)):null;}
                }catch(SQLException failure){throw storage(failure);}
            }
            public boolean containsKey(Object key) {
                check();try {var query=exists;
                    query.setString(1,space);query.setString(2,(String)key);
                    try(var rows=query.executeQuery()){return rows.next();}
                }catch(SQLException failure){throw storage(failure);}
            }
            public T put(String key,T value) {
                T old=get(key);write(key,value);return old;
            }
            public T putIfAbsent(String key,T value) {
                T old=get(key);if(old==null)write(key,value);return old;
            }
            private void write(String key,T value) {
                check();String encoded=CanonicalJson.write(value);
                if(encoded.getBytes(StandardCharsets.UTF_8).length>rowBytes)throw new UncheckedIOException(new IOException("GRAPH_PARTITION_ROW_WORKER_REQUIRED"));
                try {var insert=upsert;
                    insert.setString(1,space);insert.setString(2,key);insert.setString(3,encoded);insert.executeUpdate();
                }catch(SQLException failure){throw storage(failure);}
            }
            public Set<Map.Entry<String,T>> entrySet() {
                return new AbstractSet<>() {
                    public int size() {
                        try {var query=count;
                            query.setString(1,space);try(var rows=query.executeQuery()){return rows.next()?Math.toIntExact(rows.getLong(1)):0;}
                        }catch(SQLException failure){throw storage(failure);}
                    }
                    public Iterator<Map.Entry<String,T>> iterator() {
                        return new Iterator<>() {
                            String after="";Iterator<Map.Entry<String,T>> page=Collections.emptyIterator();boolean end;
                            public boolean hasNext() {
                                check();if(page.hasNext())return true;if(end)return false;
                                var batch=new ArrayList<Map.Entry<String,T>>();long characters=0;
                                try(var query=database.prepareStatement("SELECT k,payload FROM rows WHERE space=? AND k>? ORDER BY k LIMIT 128")) {
                                    query.setString(1,space);query.setString(2,after);
                                    try(var rows=query.executeQuery()) {
                                        while(rows.next()) {
                                            String encoded=rows.getString(2);batch.add(Map.entry(rows.getString(1),read(encoded)));characters+=encoded.length();
                                            if(characters>=1_048_576)break;
                                        }
                                    }
                                }catch(SQLException failure){throw storage(failure);}
                                if(batch.isEmpty()){end=true;return false;}after=batch.getLast().getKey();page=batch.iterator();return true;
                            }
                            public Map.Entry<String,T> next() {if(!hasNext())throw new NoSuchElementException();return page.next();}
                        };
                    }
                };
            }
        };
    }
    private static UncheckedIOException storage(SQLException failure) {return new UncheckedIOException(new IOException("GRAPH_PROJECTION_STORAGE_UNAVAILABLE",failure));}
}
