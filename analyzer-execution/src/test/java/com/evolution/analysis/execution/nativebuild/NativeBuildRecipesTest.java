package com.evolution.analysis.execution.nativebuild;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.readiness.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeBuildRecipesTest {
    static ContentDigest d(String v){return ContentDigest.sha256Utf8(v);}
    static ReadinessPlan plan(ReadinessPlan.Family family,List<String> sets,String variant){
        var inventory=new InventoryManifest(InventoryManifest.SCHEMA,RepositoryIdentity.fromCanonicalCoordinate("urn:test:native-recipe"),d("selection"),d("inventory"),0,0,0,0,Optional.empty(),Optional.empty());
        var contexts=sets.stream().map(set->new ReadinessPlan.Context("ctx-"+set,"module","root",set,variant,List.of(),Map.of(),List.of(),d("closure"),true)).toList();
        var ids=contexts.stream().map(ReadinessPlan.Context::id).toList();
        var recipe=new ReadinessPlan.Recipe("recipe",family,"root",ids,List.of(),List.of("COMPILE_SELECTED_SOURCE_SETS"),List.of(),List.of("native-output"),Map.of(),"ISOLATED_ROOT","BROKER_ONLY","CONFIGURED_WORKER_REQUIRED",List.of("guest-overlay"),List.of("protected-observation"));
        return new ReadinessPlan(ReadinessPlan.SCHEMA,inventory,new BuildContextSelection(BuildContextSelection.SCHEMA,Map.of(),ids,ids,List.of(),List.of()),List.of(new ReadinessPlan.Root("root","nested",family,List.of(),List.of())),List.of(),contexts,List.of(),List.of(recipe),new ReadinessPlan.OwnershipSummary(0,Map.of(),new ContentHandle(d("ledger"),0)));
    }
    static NativeBuildRecipes.Tool tool(String family){return new NativeBuildRecipes.Tool(family,new VersionedIdentifier("native."+family,"test-pin"),new ContentHandle(d("binary-"+family),100),"linux","x86_64");}
    @Test void mavenUsesOneRootCompileAndNeverDisablesRequiredProcessors(){
        var plan=plan(ReadinessPlan.Family.MAVEN,List.of("main","test"),"baseline");
        var result=NativeBuildRecipes.lower(plan,plan.recipes().getFirst(),tool("maven"),d("closure"),d("network"),d("resources"),Map.of());
        var recipe=result.recipe().orElseThrow();assertEquals("ISOLATED_ROOT",recipe.executionScope());assertEquals(List.of("--batch-mode","--no-transfer-progress","test-compile"),recipe.commands().getFirst().arguments());assertEquals("nested",recipe.commands().getFirst().rootDirectory());
        assertEquals(recipe.identity(),NativeBuildRecipes.lower(plan,plan.recipes().getFirst(),tool("maven"),d("closure"),d("network"),d("resources"),Map.of()).recipe().orElseThrow().identity());
    }
    @Test void unknownProfileAndCustomMavenSourceSetAreNotGivenFakeDefaultTasks(){
        for(var plan:List.of(plan(ReadinessPlan.Family.MAVEN,List.of("integration"),"baseline"),plan(ReadinessPlan.Family.MAVEN,List.of("main"),"dynamic-profile"))){
            var result=NativeBuildRecipes.lower(plan,plan.recipes().getFirst(),tool("maven"),d("c"),d("n"),d("r"),Map.of());assertTrue(result.recipe().isEmpty());assertFalse(result.pendingReasons().isEmpty());
        }
    }
    @Test void gradleRequiresExplicitNativeTaskMappingAndRejectsCommandInjection(){
        var plan=plan(ReadinessPlan.Family.GRADLE,List.of("custom"),"variant");var source=plan.recipes().getFirst();
        assertTrue(NativeBuildRecipes.lower(plan,source,tool("gradle"),d("c"),d("n"),d("r"),Map.of()).recipe().isEmpty());
        var result=NativeBuildRecipes.lower(plan,source,tool("gradle"),d("c"),d("n"),d("r"),Map.of("ctx-custom",List.of(":sub:compileCustomJava")));
        assertEquals(List.of("--no-daemon","--console=plain",":sub:compileCustomJava"),result.recipe().orElseThrow().commands().getFirst().arguments());
        assertThrows(IllegalArgumentException.class,()->NativeBuildRecipes.lower(plan,source,tool("gradle"),d("c"),d("n"),d("r"),Map.of("ctx-custom",List.of("compile; curl evil"))));
        assertThrows(IllegalArgumentException.class,()->NativeBuildRecipes.lower(plan,source,tool("maven"),d("c"),d("n"),d("r"),Map.of()));
    }
    @Test void unavailableNativeFamilyIsAnExplicitProviderObligation(){
        var plan=plan(ReadinessPlan.Family.CARGO,List.of("main"),"baseline");
        assertEquals(List.of("NATIVE_ADAPTER_REQUIRED"),NativeBuildRecipes.lower(plan,plan.recipes().getFirst(),tool("cargo"),d("c"),d("n"),d("r"),Map.of()).pendingReasons());
    }
}
