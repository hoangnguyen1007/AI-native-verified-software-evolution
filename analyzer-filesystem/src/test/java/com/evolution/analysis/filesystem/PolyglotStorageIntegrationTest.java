package com.evolution.analysis.filesystem;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.polyglot.SemanticTables;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PolyglotStorageIntegrationTest {
    @TempDir Path directory;
    @Test void capturedTablesResumeAfterStoreReopenAndReauthenticateCorruption()throws Exception{
        var scope=ContentDigest.sha256Utf8("context");var store=new StreamingContentStore(directory);
        var tables=new SemanticTables(store,4096);var rows=java.util.stream.IntStream.range(0,257).mapToObj(i->new SemanticTables.Row(SemanticTables.Kind.CENSUS,String.format(Locale.ROOT,"%04d",i),scope,Map.of("category","A06","label","é😀")));
        var table=tables.write(SemanticTables.Kind.CENSUS,rows.iterator(),()->false);var keys=new ArrayList<String>();
        var first=tables.scan(table,Optional.empty(),100,row->keys.add(row.key()),()->false);assertFalse(first.end());assertEquals(100,first.cursor().rows());
        var reopened=new SemanticTables(new StreamingContentStore(directory),4096);Optional<SemanticTables.Cursor> cursor=Optional.of(first.cursor());
        while(true){var next=reopened.scan(table,cursor,100,row->keys.add(row.key()),()->false);if(next.end())break;cursor=Optional.of(next.cursor());}
        assertEquals(257,keys.size());assertEquals(257,new HashSet<>(keys).size());assertEquals("0256",keys.getLast());
        String hex=table.content().digest().value().substring(7);Path blob=directory.resolve("sha256").resolve(hex.substring(0,2)).resolve(hex.substring(2));
        byte[] bytes=Files.readAllBytes(blob);bytes[0]^=1;Files.write(blob,bytes);
        assertThrows(IOException.class,()->reopened.open(table,()->false));
    }
}
