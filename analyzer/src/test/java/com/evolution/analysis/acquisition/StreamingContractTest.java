package com.evolution.analysis.acquisition;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.graph.PartitionedGraphReader;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StreamingContractTest {
    @Test void contentAndGraphOffsetsAreLongAndInvalidSizesCannotEnterTheContracts(){
        var digest=ContentDigest.sha256Utf8("evidence");var handle=new ContentHandle(digest,4_294_967_296L);assertEquals(4_294_967_296L,handle.size());
        assertThrows(IllegalArgumentException.class,()->new ContentHandle(digest,-1));
        assertThrows(IllegalArgumentException.class,()->new PartitionedGraphReader.Partition("wrong",digest,"context",handle,0,0));
        assertThrows(IllegalArgumentException.class,()->new PartitionedGraphReader.Partition(PartitionedGraphReader.SCHEMA,digest,"context",handle,-1,0));
    }
    @Test void selectionIdentityBindsExclusionsButDoesNotFoldRawNamesOrLanguageScope(){
        var repo=RepositoryIdentity.fromCanonicalCoordinate("urn:test:selection");var first=new StreamingAcquisitionRequest(repo,Optional.empty(),true,List.of("b","a"));var reordered=new StreamingAcquisitionRequest(repo,Optional.empty(),true,List.of("a","b"));
        assertEquals(first.identity(),reordered.identity());assertNotEquals(first.identity(),new StreamingAcquisitionRequest(repo,Optional.empty(),true,List.of("a")).identity());
        assertNotEquals(LogicalPath.utf8("A"),LogicalPath.utf8("a"));assertTrue(LogicalPath.of(new byte[]{(byte)0xff}).utf8().isEmpty());
    }
}
