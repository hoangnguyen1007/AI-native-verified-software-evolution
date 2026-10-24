package com.evolution.analysis.javaevidence;

import com.evolution.analysis.acquisition.streaming.ContentHandle;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JavaEvidenceTablesTest {
    @Test void canonicalRowsRoundTripWithExactCountsAndNoSilentDuplicateKeys()throws Exception{
        var store=new JavaEvidenceTestStore();var tables=new JavaEvidenceTables(store,10000);
        var rows=new ArrayList<>(List.of(JavaEvidenceLedgerTest.requirement("b",List.of()),JavaEvidenceLedgerTest.requirement("a",List.of())));rows.sort(JavaEvidenceTables::compare);
        var table=tables.write("java-closure-requirement",rows.iterator(),()->false);var restored=new ArrayList<JavaEvidenceRecord>();tables.read(table,restored::add,()->false);
        assertEquals(rows,restored);assertEquals(2,table.rows());
        assertThrows(IOException.class,()->tables.write("java-closure-requirement",List.of(rows.getFirst(),rows.getFirst()).iterator(),()->false));
        assertThrows(IOException.class,()->tables.read(new JavaEvidenceTables.Table(table.schema(),table.content(),1),r->{},()->false));
    }
    @Test void malformedTailCancellationAndWorkerBoundsKeepOriginalTableAvailable()throws Exception{
        var store=new JavaEvidenceTestStore();var row=JavaEvidenceLedgerTest.requirement("a",List.of());var raw=store.put(row.json());var tables=new JavaEvidenceTables(store,10000);
        assertThrows(IOException.class,()->tables.read(new JavaEvidenceTables.Table("java-closure-requirement-table:m8.4-v1",raw,1),r->{},()->false));
        var table=tables.write(row.kind(),List.of(row).iterator(),()->false);
        assertThrows(IOException.class,()->tables.read(table,r->{},()->true));assertTrue(store.verify(table.content()));
        assertThrows(IOException.class,()->new JavaEvidenceTables(store,32).read(table,r->{},()->false));
    }
}
