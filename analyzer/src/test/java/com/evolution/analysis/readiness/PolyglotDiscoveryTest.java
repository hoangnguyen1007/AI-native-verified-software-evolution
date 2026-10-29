package com.evolution.analysis.readiness;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PolyglotDiscoveryTest {
    @Test void visualBasicAndModernWorkspaceDescriptorsRemainDiscoverable(){
        for(String name:List.of("legacy/app.vbproj","go.work","Package.resolved","deno.json","bun.lock","Directory.Build.props"))
            assertTrue(ReadinessPlanner.descriptor(name),name);
    }
    @Test void modernModuleAndEmbeddedFilesAreNotSilentlyClassifiedAsNonSource() {
        var expected=Map.of("app/main.mts","typescript","app/main.cjs","javascript",
                "app/View.vue","vue","app/View.svelte","svelte","app/View.astro","astro",
                "legacy/Entry.vb","visualbasic","native/Entry.mm","objectivecpp","tools/build.kts","kotlin");
        expected.forEach((path,language)->assertEquals(Optional.of(language),ReadinessPlanner.language(path),path));
    }
}
