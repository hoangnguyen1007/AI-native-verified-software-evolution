package com.evolution.analysis.spring.universal;

import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpringSourceEvidenceImportsTest {

    @Test
    void parsesJavaAndKotlinImportsWithoutSemicolonsAndMatchesLegacy() {
        String kotlinSource = """
                package org.springframework.samples.petclinic.owner

                // import ignored.CommentedOut
                /* import ignored.BlockComment */
                import org.springframework.boot.autoconfigure.SpringBootApplication
                import org.springframework.web.bind.annotation.GetMapping // inline comment
                import org.springframework.web.bind.annotation.PostMapping
                import org.springframework.stereotype.Service as SpringService
                import org.springframework.context.annotation.*

                @SpringBootApplication
                class PetClinicApplication {
                    val text = "import fake.InString"
                }
                """;
        var fast = SpringSourceEvidence.parseImportsForTest(kotlinSource);
        var legacy = SpringSourceEvidence.parseImportsLegacyForTest(kotlinSource);

        assertEquals(legacy, fast);
        assertTrue(fast.allImports().contains("org.springframework.boot.autoconfigure.SpringBootApplication"));
        assertTrue(fast.allImports().contains("org.springframework.web.bind.annotation.GetMapping"));
        assertTrue(fast.allImports().contains("org.springframework.web.bind.annotation.PostMapping"));
        assertTrue(fast.allImports().contains("org.springframework.stereotype.Service"));
        assertTrue(fast.allImports().contains("org.springframework.context.annotation.*"));
        assertFalse(fast.allImports().contains("ignored.CommentedOut"));
        assertFalse(fast.allImports().contains("ignored.BlockComment"));
        assertFalse(fast.allImports().contains("fake.InString"));
        assertEquals("org.springframework.stereotype.Service", fast.explicitBySimpleName().get("Service"));
        assertEquals("org.springframework.stereotype.Service", fast.explicitBySimpleName().get("SpringService"));
        assertEquals(Set.of("org.springframework.context.annotation."), fast.wildcards());
    }

    @Test
    void parsesJavaImportsWithSemicolonsCommentsAndStaticImports() {
        String cleanJava = """
                package demo;
                import org.springframework.stereotype.Component;
                import static org.springframework.web.bind.annotation.RequestMethod.GET;
                import org.springframework.boot.autoconfigure.condition.*;
                /* import fake.Ignored; */
                @Component
                public class Sample {}
                """;
        assertEquals(
                SpringSourceEvidence.parseImportsLegacyForTest(cleanJava),
                SpringSourceEvidence.parseImportsForTest(cleanJava));

        String javaWithTextBlock = """
                package demo;
                import org.springframework.stereotype.Component;
                import static org.springframework.web.bind.annotation.RequestMethod.GET;
                import org.springframework.boot.autoconfigure.condition.*;
                /* import fake.Ignored; */
                @Component
                public class Sample {
                    String s = \"\"\"
                            import fake.TextBlock;
                            \"\"\";
                }
                """;
        var fast = SpringSourceEvidence.parseImportsForTest(javaWithTextBlock);

        assertEquals("org.springframework.stereotype.Component", fast.explicitBySimpleName().get("Component"));
        assertEquals("org.springframework.web.bind.annotation.RequestMethod.GET", fast.explicitBySimpleName().get("GET"));
        assertEquals(Set.of("org.springframework.boot.autoconfigure.condition."), fast.wildcards());
        assertFalse(fast.allImports().contains("fake.Ignored"));
        assertFalse(fast.allImports().contains("fake.TextBlock"));
    }
}
