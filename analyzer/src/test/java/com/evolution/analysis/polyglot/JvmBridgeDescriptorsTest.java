package com.evolution.analysis.polyglot;

import com.evolution.analysis.acquisition.streaming.ContentHandle;
import com.evolution.analysis.contract.common.ContentDigest;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JvmBridgeDescriptorsTest {
    @Test void actualJavacClassPreservesOverloadsBinaryOwnerAndSyntheticFlagsWithoutLoadingIt()throws Exception{
        var content=new SemanticTablesTest.Memory();ContentHandle binary;try(var input=getClass().getResourceAsStream("/com/evolution/analysis/polyglot/JvmBridgeDescriptorsTest$Sample.class")){binary=content.put(Objects.requireNonNull(input),()->false);}var parsed=JvmBridgeDescriptors.read(binary,content,65536);
        assertTrue(parsed.owner().endsWith("JvmBridgeDescriptorsTest$Sample"));assertEquals(Optional.of("JvmBridgeDescriptorsTest.java"),parsed.sourceFile());var methods=parsed.declarations().stream().filter(m->m.name().equals("call")).map(JvmBridgeDescriptors.Member::descriptor).toList();assertTrue(methods.containsAll(List.of("(I)I","(Ljava/lang/String;)Ljava/lang/String;")));assertTrue(parsed.references().stream().anyMatch(m->m.owner().equals("java/lang/Integer")&&m.name().equals("parseInt")));
        var reference=new JvmBridgeDescriptors.Member(parsed.owner(),"call","(I)I","METHOD",0);assertTrue(parsed.declarations().stream().anyMatch(m->JvmBridgeDescriptors.matches(reference,m)));assertFalse(parsed.declarations().stream().anyMatch(m->JvmBridgeDescriptors.matches(new JvmBridgeDescriptors.Member("other/Owner","call","(I)I","METHOD",0),m)));
    }
    @Test void corruptedTruncatedUnknownTagsAndResourceLimitsAreExplicit()throws Exception{
        var content=new SemanticTablesTest.Memory();for(byte[] bytes:List.of(new byte[]{0,1},new byte[]{(byte)0xca,(byte)0xfe,(byte)0xba,(byte)0xbe,0,0,0,65,0,2,(byte)255})){var handle=content.put(new ByteArrayInputStream(bytes),()->false);assertThrows(IOException.class,()->JvmBridgeDescriptors.read(handle,content,65536));}
        var handle=content.text("not a class");assertThrows(IOException.class,()->JvmBridgeDescriptors.read(handle,content,1));content.blobs.put(handle.digest(),new byte[]{0});assertThrows(IOException.class,()->JvmBridgeDescriptors.read(handle,content,65536));
    }
    static final class Sample {int call(int n){return Integer.parseInt(Integer.toString(n));}String call(String text){return text;}}
}
