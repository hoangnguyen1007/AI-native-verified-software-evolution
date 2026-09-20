package com.evolution.analysis.javaparser;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.contract.semantic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ModernLanguageIngestionTest {
    private FrontendResult analyze(String code,int level) {
        var base=TestInputs.request(code);var p=base.plan();
        return new JavaParserFrontend().analyze(TestInputs.request(base.sources(),base.platform(),base.dependencies(),
                new FrontendPlan(java.util.Optional.of(level),java.util.Optional.of(level),false,p.classpathManifest(),p.sourceDecoding())));
    }
    @Test void exactLanguageLevelsPreserveRecordsAndSealedTypesThrough26() {
        for(int level:new int[]{17,21,22,23,24,25,26}) {
            var r=analyze("sealed interface S permits R {} record R(String dependency) implements S {}",level);
            assertTrue(r.sources().stream().allMatch(s->s.state()==SourceOutcome.State.PROCESSED),"level "+level+" "+r.diagnostics());
            assertTrue(r.occurrences().stream().anyMatch(o->o.relationship().kind().value().equals("java.permits")&&o.status()==SemanticStatus.RESOLVED));
            assertTrue(r.types().stream().anyMatch(t->t.role().value().equals("java.parameter-type")));
        }
    }
    @Test void newSyntaxIsNotSilentlyAcceptedAtOldLanguageLevels() {
        assertEquals(SourceOutcome.State.ERROR,analyze("record R(int value) {}",8).sources().getFirst().state());
        var r=analyze("class Base { Base(int x){} } class C extends Base { C(int x) { int positive = Math.abs(x); super(positive); } }",25);
        assertTrue(r.sources().stream().allMatch(s->s.state()==SourceOutcome.State.PROCESSED),r.diagnostics().toString());
        assertThrows(FrontendInputException.class,()->analyze("class C {}",27));
    }
}
