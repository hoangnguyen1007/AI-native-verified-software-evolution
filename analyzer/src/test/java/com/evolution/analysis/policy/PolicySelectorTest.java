package com.evolution.analysis.policy;

import com.evolution.analysis.frontend.JavaSymbolName;
import com.evolution.analysis.query.ArchitectureQuery.EntityView;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.policy.PolicySelector.Match.*;

class PolicySelectorTest {
    static ArchitecturePolicy.Selector pkg(String pattern){return new ArchitecturePolicy.Selector(Set.of(pattern),Set.of(),Set.of(),Set.of(),Set.of(),Set.of());}
    static EntityView entity(String canonical){return new EntityView("identity","context","TYPE",Optional.of(canonical),Optional.empty(),Optional.empty(),Optional.empty(),Optional.of("PROJECT"),Optional.empty(),List.of());}
    @Test void packagePrefixesRespectSegmentBoundariesAndNestedOwners() {
        var name=JavaSymbolName.topLevelType("com.example.web","Outer");
        assertEquals(YES,PolicySelector.match(pkg("com.example.web"),entity(name.canonicalName())));
        assertEquals(YES,PolicySelector.match(pkg("com.example.**"),entity(JavaSymbolName.memberType(name,"Inner").canonicalName())));
        assertEquals(NO,PolicySelector.match(pkg("com.example.web.**"),entity(JavaSymbolName.topLevelType("com.example.webbing","Other").canonicalName())));
        assertEquals(NO,PolicySelector.match(pkg("com.example"),entity(name.canonicalName())));
    }
    @Test void preservesUnicodePackageEqualityAndDefaultPackage() {
        assertEquals(YES,PolicySelector.match(pkg("café"),entity(JavaSymbolName.topLevelType("café","A").canonicalName())));
        assertEquals(NO,PolicySelector.match(pkg("cafe\u0301"),entity(JavaSymbolName.topLevelType("café","A").canonicalName())));
        assertEquals(YES,PolicySelector.match(pkg(""),entity(JavaSymbolName.topLevelType("","A").canonicalName())));
    }
    @Test void malformedOrAbsentNamesStayUnknownButFalseDominatesUnknown() {
        assertEquals(UNKNOWN,PolicySelector.match(pkg("com.example.**"),entity("java:v1:[\"type\",[\"com\"],[]]")));
        assertEquals(UNKNOWN,PolicySelector.match(pkg("com.example.**"),entity("java:v1:broken")));
        assertEquals(UNKNOWN,PolicySelector.match(pkg("com.example.**"),entity("guessed.name.A")));
        var filtered=new ArchitecturePolicy.Selector(Set.of("com.example.**"),Set.of(),Set.of(),Set.of("JDK"),Set.of(),Set.of());
        assertEquals(NO,PolicySelector.match(filtered,entity("guessed.name.A")));
        var all=new ArchitecturePolicy.Selector(Set.of(),Set.of("*"),Set.of(),Set.of(),Set.of(),Set.of());
        assertEquals(YES,PolicySelector.match(all,entity("missing")));
    }
    @Test void aggregateSourceSetMetadataCannotStandForEveryConstituentType() {
        var canonical=JavaSymbolName.packageName("p").canonicalName();
        var aggregate=new EntityView("id","context","PACKAGE",Optional.of(canonical),Optional.of("p"),Optional.empty(),Optional.empty(),Optional.of("PROJECT"),Optional.of("MAIN"),List.of());
        var main=new ArchitecturePolicy.Selector(Set.of(),Set.of(),Set.of(),Set.of(),Set.of("MAIN"),Set.of());
        assertEquals(UNKNOWN,PolicySelector.match(main,aggregate));
    }
}
