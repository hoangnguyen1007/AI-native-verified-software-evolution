package com.evolution.analysis.readiness;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.buildmodel.PassiveReadinessDiscovery;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.readiness.ReadinessPlan.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PassiveReadinessDiscoveryTest {
    @Test void gradleCompositeCustomSetsAndBuildLogicRemainSeparate(){
        var projection=discover(Map.of("settings.gradle.kts","include(\":app\")\nincludeBuild(\"tools\")\n", "app/build.gradle.kts","plugins { java }\nsourceSets { integrationTest { java { srcDirs(\"it/java\") } } }", "tools/build.gradle.kts","plugins { java }", "buildSrc/build.gradle.kts","plugins { java }"));
        assertTrue(projection.modules().stream().anyMatch(module->module.directory().equals("app")&&module.root().equals("GRADLE:.")));
        assertTrue(projection.roots().stream().anyMatch(root->root.directory().equals("tools")));
        assertTrue(projection.roots().stream().anyMatch(root->root.directory().equals("buildSrc")));
        assertTrue(projection.contexts().stream().anyMatch(context->context.sourceSet().equals("integrationTest")&&context.sourceRoots().contains("app/it/java")));
        assertTrue(projection.contexts().stream().anyMatch(context->context.sourceSet().equals("integrationTest")&&context.sourceRoots().contains("app/src/integrationTest")),"srcDirs adds to the conventional source roots");
        assertTrue(projection.unknown().stream().anyMatch(value->value.contains("variants-and-substitution")));
    }
    @Test void allBuildFamiliesRetainNativeRequirements(){
        var projection=discover(Map.ofEntries(Map.entry("ant/build.xml","<project/>"),Map.entry("bazel/MODULE.bazel","module(name='x')"),Map.entry("bazel/lib/BUILD.bazel","java_library(name='lib')"),Map.entry("scala/build.sbt","scalaVersion := \"3\""),Map.entry("ide/.classpath","<classpath/>"),Map.entry("web/package.json","{}"),Map.entry("py/pyproject.toml","[project]"),Map.entry("native/CMakeLists.txt","project(x)"),Map.entry("android/build.gradle","plugins { id 'com.android.application' }\nandroid { flavorDimensions 'tier'; productFlavors { free {} paid {} } }")));
        assertTrue(projection.roots().stream().map(Root::family).toList().containsAll(List.of(Family.ANT,Family.BAZEL,Family.SBT,Family.IDE,Family.NODE,Family.PYTHON,Family.CMAKE,Family.GRADLE)));
        assertTrue(projection.requirements().stream().anyMatch(requirement->requirement.reason().equals("ANDROID_SDK_REQUIRED")));
        assertEquals(1,projection.roots().stream().filter(root->root.family()==Family.BAZEL).count());
        assertTrue(projection.modules().stream().anyMatch(module->module.directory().equals("bazel/lib")&&module.root().equals("BAZEL:bazel")));
    }
    @Test void scriptStringsNeverManufactureProjectsAndTraversalIsAnObligation(){
        var projection=discover(Map.of("settings.gradle","println(\"include ':fake'\")\nincludeBuild('../escape')"));
        assertFalse(projection.modules().stream().anyMatch(module->module.directory().contains("fake")));
        assertTrue(projection.requirements().stream().anyMatch(requirement->requirement.reason().equals("BUILD_PATH_UNRESOLVED")));
    }
    @Test void unsafeReplacementRootsCannotBecomeConventionalRootFacts(){
        var projection=discover(Map.of("build.gradle","sourceSets { main { java { setSrcDirs(['../outside']) } } }"));
        assertTrue(projection.contexts().stream().filter(context->context.sourceSet().equals("main")).allMatch(context->context.sourceRoots().isEmpty()));
        assertTrue(projection.requirements().stream().anyMatch(requirement->requirement.reason().equals("BUILD_PATH_UNRESOLVED")));
    }
    private static ReadinessDiscovery.Projection discover(Map<String,String> files){
        var descriptors=new TreeMap<String,ReadinessDiscovery.Descriptor>();files.forEach((path,text)->{byte[] bytes=text.getBytes(StandardCharsets.UTF_8);descriptors.put(path,new ReadinessDiscovery.Descriptor(path,new ContentHandle(ContentDigest.sha256(bytes),bytes.length),bytes));});
        var manifest=new InventoryManifest(InventoryManifest.SCHEMA,RepositoryIdentity.fromCanonicalCoordinate("urn:test:generic-readiness"),ContentDigest.sha256Utf8("selection"),ContentDigest.sha256Utf8("inventory"),files.size(),files.size(),0,0,Optional.empty(),Optional.empty());
        return new PassiveReadinessDiscovery().discover(descriptors,manifest);
    }
}
