package com.evolution.backend.analysis;

import com.evolution.analysis.contract.common.ContentDigest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class FastJavaSkeletonScannerTest {
    @TempDir Path tempDir;

    private static FastJavaSkeletonScanner.SourceFile source(String path, String module, String text) {
        return new FastJavaSkeletonScanner.SourceFile(path, module, ContentDigest.sha256Utf8(text), text);
    }

    @Test
    void extractsPackagesImportsTypesSpansAnnotationsAndInternalDependencies() {
        String coreSrc = """
                package com.acme.core;

                import org.springframework.stereotype.Service;

                @Service
                public class OrderService {
                    public record OrderLine(String sku, int qty) {}
                }
                """;
        String webSrc = """
                package com.acme.web;

                import com.acme.core.OrderService;
                import java.util.List;
                import org.springframework.web.bind.annotation.*;

                /*
                 * Fake commented declaration that MUST be ignored:
                 * @Repository
                 * class GhostRepository {}
                 */
                @RestController
                @RequestMapping("/orders")
                public class OrderController {
                    // class CommentedOut {}
                    private final String fake = "class InString {} @Service";
                    private final String block = \"\"\"
                            @Entity
                            class InTextBlock {}
                            \"\"\";
                    private final Class<?> cls = OrderService.class;
                    public enum Mode { FAST, FULL }
                }
                """;
        String kotlinSrc = """
                package com.acme.kt

                import com.acme.core.OrderService
                import org.springframework.stereotype.Component

                @Component
                class KotlinBridge(val svc: OrderService)
                """;

        var scanner = new FastJavaSkeletonScanner();
        var result = scanner.scan(List.of(
                source("web/src/main/java/com/acme/web/OrderController.java", "web", webSrc),
                source("core/src/main/java/com/acme/core/OrderService.java", "core", coreSrc),
                source("kt/src/main/kotlin/com/acme/kt/KotlinBridge.kt", "kt", kotlinSrc)),
                () -> false);

        assertEquals(3, result.files().size());
        // Sorted deterministically by repository path
        assertEquals("core/src/main/java/com/acme/core/OrderService.java", result.files().get(0).path());
        assertEquals("kt/src/main/kotlin/com/acme/kt/KotlinBridge.kt", result.files().get(1).path());
        assertEquals("web/src/main/java/com/acme/web/OrderController.java", result.files().get(2).path());

        var core = result.files().get(0);
        assertEquals("JAVA", core.language());
        assertEquals("com.acme.core", core.packageName());
        assertEquals(2, core.types().size());
        assertEquals("com.acme.core.OrderService", core.types().get(0).qualifiedName());
        assertEquals("CLASS", core.types().get(0).kind());
        assertFalse(core.types().get(0).nested());
        assertEquals(6, core.types().get(0).startLine());
        assertEquals(8, core.types().get(0).endLine());
        assertTrue(core.types().get(0).annotations().contains("Service"));
        assertEquals("com.acme.core.OrderService.OrderLine", core.types().get(1).qualifiedName());
        assertEquals("RECORD", core.types().get(1).kind());
        assertTrue(core.types().get(1).nested());

        var web = result.files().get(2);
        assertEquals(List.of("com.acme.core.OrderService", "java.util.List"), web.explicitImports());
        assertEquals(List.of("org.springframework.web.bind.annotation"), web.wildcardImports());
        // Comments, strings, text blocks, and ".class" literals must NOT produce false types/roles
        assertEquals(2, web.types().size());
        assertEquals("com.acme.web.OrderController", web.types().get(0).qualifiedName());
        assertEquals("com.acme.web.OrderController.Mode", web.types().get(1).qualifiedName());
        assertEquals("ENUM", web.types().get(1).kind());
        assertTrue(web.architecturalRoles().contains("RestController"));
        assertTrue(web.architecturalRoles().contains("RequestMapping"));
        assertFalse(web.architecturalRoles().contains("Repository"));
        assertFalse(web.architecturalRoles().contains("Entity"));

        var kt = result.files().get(1);
        assertEquals("KOTLIN", kt.language());
        assertEquals(1, kt.types().size());
        assertEquals("com.acme.kt.KotlinBridge", kt.types().get(0).qualifiedName());
        assertTrue(kt.architecturalRoles().contains("Component"));

        // Internal cross-module dependencies (std lib java.util.List excluded)
        assertEquals(2, result.dependencies().size());
        assertEquals("com.acme.kt.KotlinBridge", result.dependencies().get(0).sourceType());
        assertEquals("com.acme.core.OrderService", result.dependencies().get(0).targetType());
        assertEquals("com.acme.web.OrderController", result.dependencies().get(1).sourceType());
        assertEquals("com.acme.core.OrderService", result.dependencies().get(1).targetType());
        assertEquals("web", result.dependencies().get(1).sourceModule());
        assertEquals("core", result.dependencies().get(1).targetModule());
        assertEquals("EXPLICIT_IMPORT", result.dependencies().get(1).importKind());
    }

    @Test
    void parallelBatchIsDeterministicAndHonorsCancellation() {
        var inputs = new ArrayList<FastJavaSkeletonScanner.SourceFile>();
        for (int i = 0; i < 40; i++) {
            String prevImport = i > 0 ? "import com.acme.batch.Type" + (i - 1) + ";\n" : "";
            String code = "package com.acme.batch;\n" + prevImport + "public class Type" + i + " {}\n";
            inputs.add(source("mod/src/main/java/com/acme/batch/Type" + String.format("%02d", i) + ".java", "mod", code));
        }
        var scanner = new FastJavaSkeletonScanner();
        var run1 = scanner.scan(inputs, () -> false);
        var run2 = scanner.scan(inputs.reversed(), () -> false);

        assertEquals(40, run1.files().size());
        assertEquals(39, run1.dependencies().size());
        assertEquals(run1.files(), run2.files());
        assertEquals(run1.dependencies(), run2.dependencies());
        assertEquals(run1.packageFileCounts(), run2.packageFileCounts());

        var cancelled = new AtomicBoolean(true);
        assertThrows(CancellationException.class, () -> scanner.scan(inputs, cancelled::get));
    }

    @Test
    void scanDirectoryReadsFilesFromDiskSafely() throws Exception {
        Path file = tempDir.resolve("svc/src/main/java/com/acme/App.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "package com.acme;\n@SpringBootApplication\npublic class App {}\n", StandardCharsets.UTF_8);

        var scanner = new FastJavaSkeletonScanner();
        var result = scanner.scanDirectory(
                tempDir,
                List.of("svc/src/main/java/com/acme/App.java", "README.md", "../outside.java"),
                Map.of("svc/src/main/java/com/acme/App.java", "svc"),
                () -> false);

        assertEquals(1, result.files().size());
        assertEquals("svc", result.files().getFirst().modulePath());
        assertEquals(1, result.roleCounts().get("SpringBootApplication"));
    }
}
