package com.evolution.analysis.polyglot;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BridgeAccountingTest {
 @Test void concreteBindingsAndEvidenceObligationsKeepSeparateClosedDenominators()throws Exception{
  var f=new ArchitectureBridgesTest.Fixture();f.pair(ArchitectureBridges.Profile.B06);f.require("capability","server",ArchitectureBridges.Profile.B03,Optional.empty(),List.of());f.require("missing","client",ArchitectureBridges.Profile.B06,Optional.of("missing.source"),List.of());var input=f.input();var result=f.run();var queries=new BridgeQueryService((scope,cancelled)->Optional.of(new BridgeQueryService.View(f.capture,1,input,result)),f.tables);var summary=queries.summary(input.scope(),()->false);
  assertEquals("3",summary.requirements());assertEquals("2",summary.outcomes().get("UNRESOLVED"));assertEquals("2",summary.accounting().bindings());assertEquals("1",summary.accounting().evidenceObligations());assertEquals("1",summary.accounting().bindingOutcomes().get("UNRESOLVED"));assertEquals("1",summary.accounting().evidenceOutcomes().get("UNRESOLVED"));assertFalse(summary.qualified());
 }
}
