package com.evolution.analysis.polyglot;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LanguageDiscoveryTest {
    @Test void ambiguousHeadersAndObjectiveCNamesRequireContext(){
        assertThrows(IllegalArgumentException.class,()->new LanguageDiscovery.Detection(LanguageDiscovery.State.NATIVE_MEMBERSHIP,List.of("c","cpp"),com.evolution.analysis.contract.common.ContentDigest.sha256Utf8("invalid")));
        assertEquals(LanguageDiscovery.State.AMBIGUOUS,detect("include/api.h","",Optional.empty()).state());
        assertEquals(Set.of("matlab","objectivec"),new HashSet<>(detect("app.m","",Optional.empty()).candidates()));
        assertEquals(Optional.of("cpp"),detect("include/api.h","",Optional.of("cpp")).language());
        assertEquals(LanguageDiscovery.State.CONFLICTED,detect("app.ts","",Optional.of("python")).state());
    }
    @Test void shebangIsEvidenceButContradictionIsNotSilentlyOverridden(){
        assertEquals(Optional.of("python"),detect("tools/check","#!/usr/bin/env -S python3.12 -I\n",Optional.empty()).language());
        assertEquals(LanguageDiscovery.State.CONFLICTED,detect("tools/check.ts","#!/usr/bin/python3\n",Optional.empty()).state());
        assertEquals(LanguageDiscovery.State.UNKNOWN,detect("tools/check","not source",Optional.empty()).state());
        assertEquals(LanguageDiscovery.State.UNKNOWN,detect("tools/check","#!/bin/sh --python",Optional.empty()).state());
        assertEquals(LanguageDiscovery.State.UNKNOWN,detect("tools/check","#!/bin/sh node",Optional.empty()).state());
    }
    private static LanguageDiscovery.Detection detect(String path,String prefix,Optional<String> nativeLanguage){return LanguageDiscovery.detect(path,prefix.getBytes(StandardCharsets.UTF_8),nativeLanguage);}
}
