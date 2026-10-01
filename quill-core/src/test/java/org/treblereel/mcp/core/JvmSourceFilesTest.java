package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JvmSourceFilesTest {

    @Test
    void findsJavaAndKotlinConventionalSources(@TempDir Path module) throws Exception {
        Path kotlin = module.resolve("src/main/kotlin/org/acme/OrderService.kt");
        Files.createDirectories(kotlin.getParent());
        Files.writeString(kotlin, "package org.acme\nclass OrderService");

        assertEquals(kotlin, JvmSourceFiles.findConventionalSource(
                module, "main", "org.acme.OrderService"));
        assertEquals(kotlin, JvmSourceFiles.findConventionalSource(
                module, "main", "org.acme.OrderService$Companion"));
        assertEquals(List.of(
                        module.resolve("src/main/java/org/acme/OrderService.java"),
                        kotlin),
                JvmSourceFiles.conventionalCandidates(
                        module, "main", "org.acme.OrderService"));
    }

    @Test
    void recognizesCommonJavaAndKotlinTestNames() {
        for (String name : List.of("OrderTest.java", "OrderTests.kt", "OrderIT.kt",
                "OrderITCase.java", "OrderSpec.kt")) {
            assertTrue(JvmSourceFiles.isConventionalTestName(name), name);
        }
        assertFalse(JvmSourceFiles.isConventionalTestName("Contest.kt"));
        assertFalse(JvmSourceFiles.isConventionalTestName("OrderTest.txt"));
    }
}
