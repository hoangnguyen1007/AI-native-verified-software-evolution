package com.evolution.analysis.filesystem;

import com.evolution.analysis.contract.common.ContentDigest;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class JavaEvidencePublicationStoreTest {
    @TempDir Path root;
    @Test void replayAndFencingKeepPreviousRevisionWhenNextEvidenceCannotBeVerified()throws Exception{
        var content=new StreamingContentStore(root.resolve("cas"));var id=ContentDigest.sha256Utf8("registered-evidence");var first=content.put(new ByteArrayInputStream(new byte[]{1,2}),()->false);var second=content.put(new ByteArrayInputStream(new byte[]{3,4}),()->false);
        var publications=new JavaEvidencePublicationStore(root.resolve("publications"),content);var initial=publications.commit(id,Optional.empty(),Map.of("view",first));
        var reopened=new JavaEvidencePublicationStore(root.resolve("publications"),new StreamingContentStore(root.resolve("cas")));assertEquals(initial,reopened.load(id).orElseThrow());
        assertThrows(IOException.class,()->reopened.commit(id,Optional.empty(),Map.of("view",second)));assertEquals(initial,reopened.load(id).orElseThrow());
        var changed=reopened.commit(id,Optional.of(initial.document().digest()),Map.of("view",second));assertEquals(Optional.of(initial.document().digest()),changed.parent());assertTrue(content.verify(initial.document()));
        var absent=new com.evolution.analysis.acquisition.streaming.ContentHandle(ContentDigest.sha256Utf8("absent"),6);
        assertThrows(IOException.class,()->reopened.commit(id,Optional.of(changed.document().digest()),Map.of("view",absent)));assertEquals(changed,reopened.load(id).orElseThrow());
        assertThrows(IOException.class,()->reopened.commit(id,Optional.of(changed.document().digest()),Map.of("a".repeat(2_000_001),first)));assertEquals(changed,reopened.load(id).orElseThrow());
    }
}
