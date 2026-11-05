package com.evolution.analysis.filesystem;

import com.evolution.analysis.graph.GraphJson;
import java.nio.file.Path;
import java.sql.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SqliteGraphProjectionTest {
    @TempDir Path directory;
    @Test void hotRowOperationsReusePreparedStatementsAndRetainMapSemantics()throws Exception {
        try(var database=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("projection.sqlite"))) {
            try(var sql=database.createStatement()){sql.execute("CREATE TABLE rows(space TEXT NOT NULL,k TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(space,k)) WITHOUT ROWID");}database.setAutoCommit(false);
            var prepares=new AtomicInteger();var counted=(Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                if(method.getName().equals("prepareStatement"))prepares.incrementAndGet();
                try{return method.invoke(database,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
            });
            var map=new SqliteGraphProjection(counted,4096,()->false).records("values",GraphJson::string);
            for(int i=0;i<100;i++){String key=String.format(java.util.Locale.ROOT,"%05d",i);assertNull(map.put(key,"value"+i));assertEquals("value"+i,map.get(key));assertTrue(map.containsKey(key));}
            assertEquals("value0",map.putIfAbsent("00000","ignored"));assertEquals(100,map.size());assertEquals(100,map.entrySet().stream().count());assertEquals("00000",map.keySet().iterator().next());
            assertTrue(prepares.get()<16,"SQL preparations: "+prepares.get());
        }
    }
}
