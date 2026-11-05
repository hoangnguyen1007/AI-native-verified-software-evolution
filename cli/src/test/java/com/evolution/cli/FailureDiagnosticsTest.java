package com.evolution.cli;

import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.io.*;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FailureDiagnosticsTest {
    @Test void sqlitePrimaryAndExtendedCodesRemainDistinctThroughIoWrappers() {
        for(var expected:Map.of(5,"STORAGE_BUSY",6,"STORAGE_BUSY",517,"STORAGE_BUSY",13,"STORAGE_FULL",8,"STORAGE_PERMISSION_DENIED",3,"STORAGE_PERMISSION_DENIED",23,"STORAGE_PERMISSION_DENIED",11,"STORAGE_CORRUPT",14,"STORAGE_OPEN_FAILED",10,"STORAGE_IO_FAILED").entrySet()) {
            var failure=new IOException("GRAPH_PARTITION_STORAGE_UNAVAILABLE",new SQLException("private text","HY000",expected.getKey()));
            assertEquals(expected.getValue(),FailureDiagnostics.classification(new UncheckedIOException(failure)));
        }
        assertEquals("STORAGE_IO_FAILED",FailureDiagnostics.classification(new SQLException("Different JDBC vendor","HY000",13)));
        assertEquals("MEMORY_LIMIT",FailureDiagnostics.classification(new IOException("GRAPH_PARTITION_STORAGE_UNAVAILABLE",new SQLException("private","HY000",7))));
        assertEquals("STORAGE_PERMISSION_DENIED",FailureDiagnostics.classification(new AccessDeniedException("private-path")));
        assertEquals("STORAGE_NOT_FOUND",FailureDiagnostics.classification(new NoSuchFileException("private-path")));
        assertEquals("STORAGE_FULL",FailureDiagnostics.classification(new FileSystemException("private",null,"No space left on device")));
    }
    @Test void sanitizedCauseAndSuppressedFramesRetainSqlEvidenceWithoutMessagesOrPaths() {
        var sql=new SQLException("password=secret-value at C:/private/workspace","HY000",517);
        sql.setStackTrace(new StackTraceElement[]{new StackTraceElement("org.sqlite.Store","write","C:/private/Store.java",42)});
        var failure=new IOException("GRAPH_PARTITION_STORAGE_UNAVAILABLE",sql);failure.addSuppressed(new AccessDeniedException("C:/private/cleanup"));
        assertEquals("STORAGE_BUSY",FailureDiagnostics.classification(failure));
        var diagnostic=FailureDiagnostics.describe(failure,"graph",true);String json=CanonicalJson.write(diagnostic);
        assertTrue(json.contains("GRAPH_PARTITION_STORAGE_UNAVAILABLE"));assertTrue(json.contains("\"sqlCode\":517"));assertTrue(json.contains("HY000"));
        assertTrue(json.contains("org.sqlite.Store.write:42"));assertTrue(json.contains("suppressed"));
        assertFalse(json.contains("secret-value"));assertFalse(json.contains("C:/private"));assertFalse(json.contains("password"));
        assertFalse(CanonicalJson.write(FailureDiagnostics.describe(new IOException(),"command",false)).isEmpty());
    }
    @Test void cancellationMemoryAndCyclicCausesAreBoundedWithoutTrustingArbitraryMessages() {
        assertEquals("CANCELLED",FailureDiagnostics.classification(new IOException(new InterruptedException())));
        assertEquals("MEMORY_LIMIT",FailureDiagnostics.classification(new IOException(new OutOfMemoryError("private"))));
        var a=new IOException();var b=new IllegalStateException("PRIVATE_TOKEN");a.initCause(b);b.initCause(a);
        assertEquals("STORAGE_IO_FAILED",FailureDiagnostics.classification(a));assertFalse(CanonicalJson.write(FailureDiagnostics.describe(a,"unsafe stage\nprivate",true)).contains("PRIVATE_TOKEN"));
    }
}
