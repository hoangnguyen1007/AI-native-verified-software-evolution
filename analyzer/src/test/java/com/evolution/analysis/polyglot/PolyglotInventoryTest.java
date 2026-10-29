package com.evolution.analysis.polyglot;
import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PolyglotInventoryTest {
    @Test void capturedClassifierVersionRemainsPartOfTheDurableReportInsteadOfReadingLiveCodeVersion()throws Exception{
        var content=new SemanticTablesTest.Memory();var tables=new SemanticTables(content,4096);var entries=tables.write(SemanticTables.Kind.DOCUMENT,Collections.emptyIterator(),()->false);var digest=ContentDigest.sha256Utf8("inventory");
        var old=new PolyglotInventory.Report(digest,entries,0,0,new com.evolution.analysis.contract.common.VersionedIdentifier("language.discovery","captured-old"));var saved=old.identity();
        var upgraded=new PolyglotInventory.Report(digest,entries,0,0,new com.evolution.analysis.contract.common.VersionedIdentifier("language.discovery","new-provider"));assertNotEquals(saved,upgraded.identity());assertEquals(saved,old.identity());
        assertThrows(IllegalArgumentException.class,()->new PolyglotInventory.Report(digest,entries,1,0));
    }
    @Test void extensionlessEmbeddedUnknownUnavailableAndRawPathsAllRetainRows()throws Exception{
        var content=new SemanticTablesTest.Memory();var entries=new ArrayList<InventoryEntry>();
        for(var item:Map.of("app/View.vue","<script lang=\"ts\">const x=1;</script>","include/api.h","int f();","tools/run","#!/usr/bin/env python3\nprint(1)","unknown.format","opaque").entrySet())entries.add(file(LogicalPath.utf8(item.getKey()),content.text(item.getValue())));
        entries.add(file(LogicalPath.of(new byte[]{(byte)0xff,'x'}),content.text("raw")));
        entries.sort(Comparator.comparing(InventoryEntry::path));var manifest=new InventoryManifest(InventoryManifest.SCHEMA,RepositoryIdentity.fromCanonicalCoordinate("urn:test:polyglot-inventory"),ContentDigest.sha256Utf8("selection"),ContentDigest.sha256Utf8("inventory"),5,5,0,0,Optional.empty(),Optional.empty());
        InventoryReader inventory=new InventoryReader(){
            public Optional<InventoryManifest> manifest(String revision){return Optional.of(manifest);}
            public Page page(String revision,int size,Optional<String> cursor){int start=cursor.map(Integer::parseInt).orElse(0),end=Math.min(start+2,entries.size());return new Page(entries.subList(start,end),end<entries.size()?Optional.of(""+end):Optional.empty());}
        };
        var tables=new SemanticTables(content,8192);var report=new PolyglotInventory().scan("rev",inventory,content,tables,()->false);
        assertEquals(5,report.entries().rows());assertEquals(5,report.files());assertEquals(3,report.unresolvedClassification());
        var rows=new ArrayList<SemanticTables.Row>();try(var reader=tables.open(report.entries(),()->false)){SemanticTables.Row row;while((row=reader.next())!=null)rows.add(row);}
        assertTrue(rows.stream().anyMatch(row->row.fields().get("languageCandidates").equals(List.of("python"))));
        assertTrue(rows.stream().anyMatch(row->row.fields().get("languageCandidates").equals(List.of("vue"))));
        assertEquals(report.identity(),new PolyglotInventory().scan("rev",inventory,content,tables,()->false).identity());
        assertThrows(java.io.IOException.class,()->new PolyglotInventory().scan("rev",inventory,content,tables,()->true));
    }
    private static InventoryEntry file(LogicalPath path,ContentHandle content){return new InventoryEntry(path,InventoryEntry.Kind.FILE,"100644",InventoryEntry.Population.UNKNOWN,InventoryEntry.Disposition.ACQUIRED,Optional.of(content),Optional.empty(),Optional.empty(),List.of(),Optional.empty());}
}
