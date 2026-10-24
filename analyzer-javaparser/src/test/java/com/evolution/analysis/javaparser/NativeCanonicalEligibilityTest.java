package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.SourceInput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Validates source evidence used to account for the raw native import denominator. */
class NativeCanonicalEligibilityTest {
    private SourceInput input(String text){
        var repository=RepositoryIdentity.fromCanonicalCoordinate("urn:evo:native-eligibility-test");var module=ModuleDescriptor.create(repository,".","fixture");byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
        return new SourceInput(SourceDocument.create(repository,module,"C.java",ContentDigest.sha256(bytes),SourceClassification.MAIN),bytes);
    }
    @Test void actualImportsRetainRawUnicodeAndCrLfSpansWhileCommentsAndStringsAreExcluded()throws Exception {
        var input=input("// import fake.Comment;\r\n\\u0069mport java.util.List;\r\nimport static java.util.Map.*;\r\nclass C { String s = \"import fake.String;\"; }");
        var spans=NativeJavaArchitectureFixtureMain.importSpans(input);var source=new OriginalSource(input.document().identity(),input.text());
        assertEquals(2,spans.size());assertEquals("\\u0069mport java.util.List;",source.slice(spans.getFirst()));assertEquals(2,spans.getFirst().startLine());assertEquals(1,spans.getFirst().startColumn());
        assertEquals("import static java.util.Map.*;",source.slice(spans.getLast()));
    }
    @Test void incompleteSourceCannotSupplyAnImportExclusion() {
        assertThrows(IOException.class,()->NativeJavaArchitectureFixtureMain.importSpans(input("import ; class C {}")));
    }
    @Test void absenceOfImportsIsAParsedEmptyInventory()throws Exception {
        assertTrue(NativeJavaArchitectureFixtureMain.importSpans(input("class C { String s = \"import fake.String;\"; }")).isEmpty());
    }
}
