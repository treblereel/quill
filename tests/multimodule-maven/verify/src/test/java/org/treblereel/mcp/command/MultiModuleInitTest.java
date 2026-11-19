package org.treblereel.mcp.command;

import org.treblereel.mcp.index.ProjectIndexStore;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.treblereel.mcp.db.SymbolRepository;
import org.treblereel.mcp.db.BeanRepository;
import org.treblereel.mcp.db.QuillDatabase;

class MultiModuleInitTest {

    // verify/ is CWD; multimodule-maven root is one level up
    static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir")).getParent();
    static final Path QUILL_DIR = PROJECT_ROOT.resolve(".quill");

    @AfterEach
    void cleanup() throws Exception {
        if (Files.exists(QUILL_DIR)) {
            try (var walk = Files.walk(QUILL_DIR)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void initIndexesAllModules() throws Exception {
        InitCommand cmd = new InitCommand();
        cmd.projectPath = PROJECT_ROOT;
        cmd.indexOnly = true;
        cmd.call();

        Path dbPath = ProjectIndexStore.findDbForHead(PROJECT_ROOT);
        assertNotNull(dbPath, "index db should be created");

        Jdbi jdbi = QuillDatabase.open(dbPath);
        var classes = jdbi.withHandle(repositoryHandle1 -> new SymbolRepository(repositoryHandle1).findAllClasses());
        // common: NotificationService (interface) + UserDTO (record) = 2
        // service: EmailNotificationService + SmsNotificationService + UserService = 3
        // Total: 5 main classes; compiled verify-module tests are indexed separately.
        assertEquals(5, classes.stream().filter(c -> "main".equals(c.sourceSet())).count(),
                "Should index main classes from both common and service modules");
        assertEquals(3, classes.stream().filter(c -> "test".equals(c.sourceSet())).count(),
                "Should index compiled tests from the verify module");

        // Verify cross-module classes
        assertTrue(classes.stream().anyMatch(c -> c.className().contains("NotificationService")));
        assertTrue(classes.stream().anyMatch(c -> c.className().contains("UserDTO")));
        assertTrue(classes.stream().anyMatch(c -> c.className().contains("EmailNotificationService")));
        assertTrue(classes.stream().anyMatch(c -> c.className().contains("UserService")));

        var beans = jdbi.withHandle(repositoryHandle2 -> new BeanRepository(repositoryHandle2).findBeans(null));
        // 3 CDI beans: EmailNotificationService, SmsNotificationService, UserService
        assertEquals(3, beans.size(), "Should find 3 CDI beans");

        // Verify cross-module injection is detected
        var userServiceBean = beans.stream()
                .filter(b -> jdbi.withHandle(repositoryHandle3 -> new SymbolRepository(repositoryHandle3).findClassById(b.classId()))
                        .map(c -> c.className().endsWith("UserService")).orElse(false))
                .findFirst();
        assertTrue(userServiceBean.isPresent(), "UserService should be a bean");

        var ips = jdbi.withHandle(repositoryHandle4 -> new BeanRepository(repositoryHandle4).findInjectionPoints(userServiceBean.get().id()));
        assertFalse(ips.isEmpty(), "UserService should have injection points");
        assertTrue(ips.stream().anyMatch(ip -> ip.targetType().contains("NotificationService")),
                "UserService should inject NotificationService from common module");
    }
}
