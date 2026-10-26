package com.evolution.analysis.javaevidence;

import com.evolution.analysis.acquisition.streaming.ContentHandle;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JavaEvidenceTablesTest {
    @Test void anUnknownEmptyTableCannotClaimAKnownEmptyRecordCensus()throws Exception{
        var store=new JavaEvidenceTestStore();var tables=new JavaEvidenceTables(store,10000);var empty=store.put("");
        var invented=new JavaEvidenceTables.Table("java-invented-table:m8.4-v1",empty,0);
        assertThrows(IOException.class,()->tables.read(invented,row->{},()->false));
        assertThrows(IOException.class,()->tables.scan(invented,Optional.empty(),1,row->{},()->false));
        assertThrows(IOException.class,()->tables.write("java-invented",Collections.emptyIterator(),()->false));
    }
    @Test void boundedPagesAndDurableCursorsPreserveEveryRowAcrossCancellationAndRestart()throws Exception{
        var store=new JavaEvidenceTestStore();var tables=new JavaEvidenceTables(store,10000);
        var expected=new ArrayList<JavaEvidenceRecord>();for(int i=0;i<513;i++)expected.add(JavaEvidenceLedgerTest.requirement("partition-"+i,List.of()));expected.sort(JavaEvidenceTables::compare);
        var table=tables.write("java-closure-requirement",expected.iterator(),()->false);var observed=new ArrayList<JavaEvidenceRecord>();
        var first=tables.scan(table,Optional.empty(),128,observed::add,()->false);
        assertEquals(JavaEvidenceTables.ScanState.MORE,first.state());assertEquals(128,first.cursor().rows());
        var checkpoint=tables.checkpoint(first.cursor());
        var reopened=new JavaEvidenceTables(store,10000);var restored=reopened.restore(table,checkpoint,513,()->false);
        var cancelled=reopened.scan(table,Optional.of(restored),128,observed::add,()->true);
        assertEquals(JavaEvidenceTables.ScanState.CANCELLED,cancelled.state());assertEquals(128,cancelled.cursor().rows());
        var cursor=cancelled.cursor();int batches=1;
        while(true){var next=reopened.scan(table,Optional.of(cursor),128,observed::add,()->false);cursor=next.cursor();batches++;if(next.state()==JavaEvidenceTables.ScanState.END)break;}
        assertEquals(5,batches);assertEquals(513,cursor.rows());assertEquals(table.content().size(),cursor.offset());assertEquals(expected,observed);
        assertThrows(IOException.class,()->reopened.restore(table,checkpoint,127,()->false));
        var foreign=new JavaEvidenceTables.Table(table.schema(),table.content(),512);
        var saved=cursor;assertThrows(IOException.class,()->reopened.scan(foreign,Optional.of(saved),1,r->{},()->false));
    }
    @Test void cancellationInsideAPageAndForgedCheckpointsCannotSkipAUnicodeRow()throws Exception{
        var store=new JavaEvidenceTestStore();var tables=new JavaEvidenceTables(store,10000);var rows=new ArrayList<JavaEvidenceRecord>();
        for(String subject:List.of("é","é","A","a","source-😀"))rows.add(JavaEvidenceLedgerTest.requirement(subject,List.of()));rows.sort(JavaEvidenceTables::compare);
        var table=tables.write("java-closure-requirement",rows.iterator(),()->false);var seen=new ArrayList<JavaEvidenceRecord>();
        var prefix=tables.scan(table,Optional.empty(),5,seen::add,()->seen.size()==2);
        assertEquals(JavaEvidenceTables.ScanState.CANCELLED,prefix.state());assertEquals(2,prefix.cursor().rows());
        var saved=tables.checkpoint(prefix.cursor());
        var fields=com.evolution.analysis.graph.GraphJson.object(com.evolution.analysis.graph.GraphJson.read(new String(store.blobs.get(saved.digest()),java.nio.charset.StandardCharsets.UTF_8),com.evolution.analysis.graph.GraphJson.Limits.defaults()));
        for(String field:List.of("rows","offset","previous","extra")){
            var forged=new LinkedHashMap<>(fields);forged.put(field,field.equals("previous")?JavaEvidenceCodecTest.D:field.equals("rows")?"2":1);
            var handle=tables.putCanonical(forged,()->false);assertThrows(IOException.class,()->tables.restore(table,handle,5,()->false),field);
        }
        var restored=tables.restore(table,saved,5,()->false);var end=tables.scan(table,Optional.of(restored),5,seen::add,()->false);
        assertEquals(JavaEvidenceTables.ScanState.END,end.state());assertEquals(rows,seen);assertEquals(table.content().size(),end.cursor().offset());
    }
    @Test void malformedTailRetainsTheExactValidatedPrefixAndNeverClaimsEnd()throws Exception{
        var store=new JavaEvidenceTestStore();var tables=new JavaEvidenceTables(store,10000);
        var rows=new ArrayList<>(List.of(JavaEvidenceLedgerTest.requirement("a",List.of()),JavaEvidenceLedgerTest.requirement("b",List.of())));rows.sort(JavaEvidenceTables::compare);
        var raw=new java.io.ByteArrayOutputStream();for(var row:rows)raw.write((row.json()+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));long validBytes=raw.size();raw.write(new byte[]{(byte)0xc3,0x28,'\n'});
        var bytes=store.put(new java.io.ByteArrayInputStream(raw.toByteArray()),()->false);var table=new JavaEvidenceTables.Table("java-closure-requirement-table:m8.4-v1",bytes,3);
        var seen=new ArrayList<JavaEvidenceRecord>();var failure=assertThrows(JavaEvidenceTables.ScanFailure.class,()->tables.scan(table,Optional.empty(),10,seen::add,()->false));
        assertEquals(rows,seen);assertEquals(2,failure.cursor().rows());assertEquals(validBytes,failure.cursor().offset());
        var checkpoint=tables.checkpoint(failure.cursor());var restored=tables.restore(table,checkpoint,2,()->false);
        assertThrows(JavaEvidenceTables.ScanFailure.class,()->tables.scan(table,Optional.of(restored),1,row->fail("The invalid tail is not a valid row"),()->false));
        var complete=tables.write("java-closure-requirement",rows.iterator(),()->false);var wrong=new JavaEvidenceTables.Table(complete.schema(),complete.content(),3);
        var mismatch=assertThrows(JavaEvidenceTables.ScanFailure.class,()->tables.scan(wrong,Optional.empty(),10,row->{},()->false));assertEquals(2,mismatch.cursor().rows());
    }
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
