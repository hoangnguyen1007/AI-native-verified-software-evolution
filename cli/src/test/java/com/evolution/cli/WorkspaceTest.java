package com.evolution.cli;
import java.nio.file.*;
import java.io.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class WorkspaceTest {
    @Test void numberedReferencesBindContextScopeAndPageWithoutGuessing()throws Exception{
        var f=new CliFixture().node("A");var r=f.request();var ws=new Workspace(directory);ws.references(r,java.util.List.of(CliFixture.id("A")));
        assertEquals(CliFixture.id("A"),ws.reference(r,1).orElseThrow());assertTrue(ws.reference(r,2).isEmpty());
        var other=new com.evolution.analysis.query.ArchitectureQuery.Request(r.graph(),CliFixture.id("other-context"),r.scope(),r.projection(),r.direction(),r.relationshipKinds(),r.population(),r.includeMust(),r.realization(),r.limits());
        assertTrue(ws.reference(other,1).isEmpty());
        other=new com.evolution.analysis.query.ArchitectureQuery.Request(r.graph(),r.context(),com.evolution.analysis.query.ArchitectureQuery.Scope.MODULE,r.projection(),r.direction(),r.relationshipKinds(),r.population(),r.includeMust(),r.realization(),r.limits());
        assertTrue(ws.reference(other,1).isEmpty());
    }
    @TempDir Path directory;
    @Test void changedGraphBytesCannotBecomeVerifiedSnapshot()throws Exception{var f=new CliFixture().node("A");var file=directory.resolve("bad.json");String json=new com.evolution.analysis.graph.GraphSnapshotCodec().encode(f.graph());Files.writeString(file,json.replace(CliFixture.id("A"),CliFixture.id("B")));assertEquals(com.evolution.analysis.graph.GraphStoreException.Reason.CORRUPT_SNAPSHOT,assertThrows(com.evolution.analysis.graph.GraphStoreException.class,()->Workspace.graph(file)).reason());}
    @Test void readsAreBoundedAndStrictUtf8()throws Exception{var file=directory.resolve("input");Files.write(file,new byte[]{(byte)0xff});assertThrows(java.nio.charset.CharacterCodingException.class,()->Workspace.json(file,16));Files.writeString(file,"0123456789");assertThrows(IOException.class,()->Workspace.read(file,5));}
    @Test void outputCreateNeverReplacesExistingBytes()throws Exception{var file=directory.resolve("out");Workspace.writeNew(file,new byte[]{1,2,3});assertThrows(FileAlreadyExistsException.class,()->Workspace.writeNew(file,new byte[]{9}));assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(file));}
    @Test void policySelectionIsVerifiedByContentHash()throws Exception{var ws=new Workspace(directory);ws.policy("policy".getBytes(java.nio.charset.StandardCharsets.UTF_8));assertEquals("policy",Workspace.utf8(ws.policy()));String id=Files.readString(ws.root.resolve("policy.txt"));Files.writeString(ws.root.resolve(id.substring(7)+".policy.yaml"),"changed");assertThrows(IOException.class,ws::policy);}
    @Test void malformedPreexistingSelectionIsPreserved()throws Exception{var ws=new Workspace(directory);Files.createDirectory(ws.root);Files.writeString(ws.root.resolve("selection.json"),"user content");assertThrows(IllegalArgumentException.class,()->ws.select(new CliFixture().graph(),java.util.Optional.empty()));assertEquals("user content",Files.readString(ws.root.resolve("selection.json")));}
}
