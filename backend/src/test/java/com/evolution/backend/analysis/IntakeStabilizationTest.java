package com.evolution.backend.analysis;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class IntakeStabilizationTest {
    @TempDir Path temp;
    final StreamingAcquisitionRequest request=new StreamingAcquisitionRequest(RepositoryIdentity.fromCanonicalCoordinate("urn:test:stable-intake"),Optional.empty(),true,List.of());
    @Test void oneProductInvocationRecoversASettledDirectoryMutation()throws Exception {
        Path source=Files.createDirectory(temp.resolve("source"));for(int i=0;i<3;i++)Files.writeString(source.resolve("f"+i),"old");
        var polls=new AtomicInteger();
        try(var intake=new RepositoryIntake(temp.resolve("workspace"))) {
            var receipt=intake.localComplete(source,"stable",request,()->{
                if(polls.incrementAndGet()==10)try{for(int i=0;i<3;i++)Files.writeString(source.resolve("f"+i),"changed bytes");}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}return false;
            });
            assertEquals(JobJournal.State.COMPLETE,receipt.state(),receipt.reason());assertEquals(3,receipt.manifest().orElseThrow().files());
            assertEquals(39,receipt.manifest().orElseThrow().bytes());
        }
    }
    @Test void allInventoryPagesCompleteOnTheFirstInvocationAndLfsStillWaits()throws Exception {
        Path source=Files.createDirectory(temp.resolve("source"));for(int i=0;i<520;i++)Files.writeString(source.resolve("f"+i),"x");
        try(var intake=new RepositoryIntake(temp.resolve("workspace"))) {
            var result=intake.localComplete(source,"pages",request,()->false);assertEquals(JobJournal.State.COMPLETE,result.state(),result.reason());assertEquals(520,result.manifest().orElseThrow().files());
        }
        Files.writeString(source.resolve("pointer"),"version https://git-lfs.github.com/spec/v1\noid sha256:"+"a".repeat(64)+"\nsize 2\n");
        try(var intake=new RepositoryIntake(temp.resolve("blocked"))) {
            var result=intake.localComplete(source,"lfs",request,()->false);assertTrue(result.manifest().isEmpty());assertEquals(JobJournal.State.WAITING_FOR_INPUT,result.state());
        }
    }
}
