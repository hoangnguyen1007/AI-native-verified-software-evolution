package com.evolution.analysis.buildmodel;

import static org.junit.jupiter.api.Assertions.*;
import com.evolution.analysis.buildmodel.SourcePlanModel.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.*;
import org.junit.jupiter.api.Test;

class SourcePlanContractTest {
    private static final ModuleIdentity MODULE = ModuleIdentity.from(
            RepositoryIdentity.fromCanonicalCoordinate("https://example.test/source-plan.git"), ".");
    private static final Setting ABSENT = new Setting("compiler.source", Optional.empty(), Optional.empty(),
            Status.UNSPECIFIED, Origin.ABSENT, List.of());

    private static SourceSetPlan sourceSet(Kind kind, List<Setting> roots, Map<String, Setting> settings) {
        return new SourceSetPlan(MODULE, kind, roots, List.of(), ABSENT, settings, ABSENT, ABSENT, ABSENT, ABSENT, List.of(), List.of());
    }

    @Test void declarationsCannotInventValuesOrDropTheirProvenance() {
        var evidence = List.of(new BuildModelResult.PomEvidence("workspace:pom.xml", ContentDigest.sha256Utf8("pom")));
        assertThrows(IllegalArgumentException.class, () -> new Setting("compiler.release", Optional.of("${missing}"), Optional.of("21"),
                Status.UNRESOLVED, Origin.EFFECTIVE_MODEL, evidence));
        assertThrows(IllegalArgumentException.class, () -> new Setting("compiler.release", Optional.of("21"), Optional.of("21"),
                Status.DECLARED, Origin.EFFECTIVE_MODEL, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Setting("compiler.release", Optional.of("21"), Optional.of("21"),
                Status.DECLARED, Origin.ABSENT, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Setting("compiler.release", Optional.empty(), Optional.of("21"),
                Status.UNSPECIFIED, Origin.ABSENT, List.of()));
    }

    @Test void sourceSetsCannotBeMissingDuplicatedOrReordered() {
        var main = sourceSet(Kind.MAIN, List.of(), Map.of());
        var test = sourceSet(Kind.TEST, List.of(), Map.of());
        assertThrows(IllegalArgumentException.class, () -> new SourcePlanModel(List.of(main), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new SourcePlanModel(List.of(main, main), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new SourcePlanModel(List.of(test, main), List.of()));
    }

    @Test void mutableCollectionsAndMapOrderCannotAlterCanonicalPlans() {
        var roots = new ArrayList<Setting>();
        var settings = new HashMap<>(Map.of("source", ABSENT, "release", ABSENT));
        var main = sourceSet(Kind.MAIN, roots, settings);
        String before = CanonicalJson.write(main);
        roots.add(ABSENT); settings.clear();
        assertEquals(before, CanonicalJson.write(main));
        assertEquals(before, CanonicalJson.write(sourceSet(Kind.MAIN, List.of(), new TreeMap<>(Map.of("release", ABSENT, "source", ABSENT)))));
        assertThrows(UnsupportedOperationException.class, () -> main.compilerSettings().clear());
    }
}
