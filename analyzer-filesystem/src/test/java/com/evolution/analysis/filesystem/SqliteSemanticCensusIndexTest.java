package com.evolution.analysis.filesystem;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.polyglot.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SqliteSemanticCensusIndexTest {
    @TempDir Path directory;
    @Test void pagingKeepsLiveMarksInsertionsAndIndependentReaders()throws Exception {
        var scope=ContentDigest.sha256Utf8("paging-context");
        try(var index=new SqliteSemanticCensusIndex(directory.resolve("paging.sqlite"),8192)) {
            var expected=new TreeSet<String>();for(int i=0;i<1100;i++){String key=String.format(Locale.ROOT,"%04d",2*i);expected.add(key);index.put("documents",key,new SemanticTables.Row(SemanticTables.Kind.DOCUMENT,key,scope,Map.of()));}
            try(var pending=index.rows("documents",true);var all=index.rows("documents",false)) {
                var actual=new ArrayList<String>();actual.add(pending.next().key());assertEquals("0000",all.next().key());
                index.mark("documents","0002");expected.remove("0002");
                index.put("documents","0001",new SemanticTables.Row(SemanticTables.Kind.DOCUMENT,"0001",scope,Map.of()));expected.add("0001");
                actual.add(pending.next().key());assertEquals("0001",all.next().key());assertEquals("0002",all.next().key(),"Unfiltered reader retains marked rows");
                index.mark("documents","0006");expected.remove("0006");
                SemanticTables.Row row;while((row=pending.next())!=null){actual.add(row.key());index.mark("documents",row.key());index.put("gaps",row.key(),row);}
                assertEquals(new ArrayList<>(expected),actual);assertNull(pending.next());
            }
            var cursor=index.rows("documents",false);assertNotNull(cursor.next());cursor.close();assertNull(cursor.next());
        }
    }

    @Test void largeRowsCrossTheTextBudgetWithoutOmission()throws Exception {
        var scope=ContentDigest.sha256Utf8("large-row-context");var value="é😀".repeat(70000);
        try(var index=new SqliteSemanticCensusIndex(directory.resolve("large.sqlite"),524288)) {
            for(int i=0;i<12;i++){String key=String.format(Locale.ROOT,"%02d",i);index.put("documents",key,new SemanticTables.Row(SemanticTables.Kind.DOCUMENT,key,scope,Map.of("value",value)));}
            try(var rows=index.rows("documents",false)){for(int i=0;i<12;i++){var row=rows.next();assertNotNull(row);assertEquals(String.format(Locale.ROOT,"%02d",i),row.key());assertEquals(value,row.fields().get("value"));}assertNull(rows.next());}
        }
    }
    @Test void diskJoinsKeepIndependentContextKeysAndMarksWithoutReusingExistingWork()throws Exception{
        Path file=directory.resolve("new-index.sqlite");var scope=ContentDigest.sha256Utf8("authored-context");
        try(var index=new SqliteSemanticCensusIndex(file,8192)){
            for(int i=0;i<1003;i++){String key=String.format(Locale.ROOT,"%04d",i);index.put("documents",key,new SemanticTables.Row(SemanticTables.Kind.DOCUMENT,key,scope,Map.of("source","é😀")));}
            assertEquals("é😀",index.get("documents","0001").orElseThrow().fields().get("source"));assertTrue(index.get("sources","0001").isEmpty());
            assertThrows(IOException.class,()->index.put("documents","0001",new SemanticTables.Row(SemanticTables.Kind.DOCUMENT,"0001",scope,Map.of())));
            try(var rows=index.rows("documents",true)){int count=0;SemanticTables.Row row;while((row=rows.next())!=null){index.mark("documents",row.key());index.put("gaps",row.key(),row);count++;}assertEquals(1003,count);}
            try(var rows=index.rows("documents",true)){assertNull(rows.next());}try(var rows=index.rows("gaps",false)){assertEquals("0000",rows.next().key());}
            assertThrows(IOException.class,()->index.mark("documents","unknown"));
            assertThrows(FileAlreadyExistsException.class,()->new SqliteSemanticCensusIndex(file,8192));
        }
        assertThrows(FileAlreadyExistsException.class,()->new SqliteSemanticCensusIndex(file,8192));
    }
}
