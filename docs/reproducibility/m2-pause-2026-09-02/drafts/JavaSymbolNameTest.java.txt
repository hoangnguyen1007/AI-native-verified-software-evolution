package com.evolution.analysis.frontend;

import static org.junit.jupiter.api.Assertions.*;

import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SourceDocumentIdentity;
import java.util.List;
import org.junit.jupiter.api.Test;

class JavaSymbolNameTest {
    @Test void freezesCallableTupleWithoutReturnTypeOrParameterNames() {
        var owner = JavaSymbolName.topLevelType("example", "A");
        assertEquals("java:v1:[\"method\",[\"type\",[\"example\"],\"A\"],\"run\",[[\"primitive\",\"int\"]]]",
                JavaSymbolName.method(owner, "run", List.of(ErasedType.primitive("int"))).canonicalName());
    }

    @Test void preservesJavaUnicodeEqualityWithoutNfcMerging() {
        var owner = JavaSymbolName.topLevelType("p", "A");
        assertNotEquals(JavaSymbolName.method(owner, "caf\u00e9", List.of()),
                JavaSymbolName.method(owner, "cafe\u0301", List.of()));
        assertEquals(JavaSymbolName.method(owner, "ig\u200cnored", List.of()),
                JavaSymbolName.method(owner, "ignored", List.of()));
        assertTrue(JavaSymbolName.method(owner, "cafe\u0301", List.of()).canonicalName().contains("e%000301"));
    }

    @Test void distinguishesLiteralDollarFromMemberOwnership() {
        assertNotEquals(JavaSymbolName.topLevelType("p", "Outer$Inner"),
                JavaSymbolName.memberType(JavaSymbolName.topLevelType("p", "Outer"), "Inner"));
    }

    @Test void preservesLocalOwnerAnchorsForNestedMemberTypes() {
        var owner = JavaSymbolName.topLevelType("p", "A");
        var document = SourceDocumentIdentity.from(RepositoryIdentity.fromCanonicalCoordinate("https://example.test/repo"), "src/A.java");
        var first = JavaSymbolName.localType(owner, document, 10, "L", false);
        var second = JavaSymbolName.localType(owner, document, 50, "L", false);
        assertNotEquals(JavaSymbolName.memberType(first, "N"), JavaSymbolName.memberType(second, "N"));
    }

    @Test void arrayRankAndPrimitiveNamesAreExplicit() {
        assertNotEquals(ErasedType.array(ErasedType.primitive("int"), 1),
                ErasedType.array(ErasedType.primitive("int"), 2));
        assertThrows(IllegalArgumentException.class, () -> ErasedType.primitive("void"));
        assertThrows(IllegalArgumentException.class, () -> ErasedType.array(ErasedType.primitive("int"), 0));
    }

    @Test void rejectsInvalidIdentifiersAndNonTypeOwners() {
        assertThrows(IllegalArgumentException.class, () -> JavaSymbolName.topLevelType("p", "A.B"));
        var method = JavaSymbolName.method(JavaSymbolName.topLevelType("p", "A"), "run", List.of());
        assertThrows(IllegalArgumentException.class, () -> JavaSymbolName.memberType(method, "Wrong"));
    }
}
