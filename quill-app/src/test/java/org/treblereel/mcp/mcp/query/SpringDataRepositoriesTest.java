package org.treblereel.mcp.mcp.query;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.mcp.JavaEvidenceFixture;
import org.treblereel.mcp.mcp.TestReads;

/**
 * Apollo AQ-018: asked what springSecurityUserService needs, no answer in any arm said where its
 * UserRepository and AuthorityRepository beans come from; Quill left them "not resolved".
 */
class SpringDataRepositoriesTest {
    @TempDir Path root;

    private static Map<String, String> sources(boolean enable) {
        Map<String, String> sources = new HashMap<>();
        sources.put(
                "src/main/java/org/springframework/data/repository/Repository.java",
                "package org.springframework.data.repository; public interface Repository<T, I> {}");
        sources.put(
                "src/main/java/org/springframework/data/jpa/repository/JpaRepository.java",
                "package org.springframework.data.jpa.repository; public interface JpaRepository<T, I> extends org.springframework.data.repository.Repository<T, I> {}");
        sources.put(
                "src/main/java/org/springframework/boot/autoconfigure/SpringBootApplication.java",
                "package org.springframework.boot.autoconfigure; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface SpringBootApplication {}");
        sources.put(
                "src/main/java/org/springframework/data/jpa/repository/config/EnableJpaRepositories.java",
                "package org.springframework.data.jpa.repository.config; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface EnableJpaRepositories {}");
        sources.put(
                "src/main/java/p/UserRepository.java",
                "package p; public interface UserRepository extends org.springframework.data.jpa.repository.JpaRepository<Object, Long> {}");
        sources.put("src/main/java/p/Plain.java", "package p; public interface Plain {}");
        sources.put(
                "src/main/java/p/App.java",
                "package p; @org.springframework.boot.autoconfigure.SpringBootApplication public class App {}");
        // Apollo: a test configuration in another module carries @EnableJpaRepositories.
        sources.put(
                "src/test/java/q/TestJpa.java",
                "package q; @org.springframework.data.jpa.repository.config.EnableJpaRepositories class TestJpa {}");
        if (enable)
            sources.put(
                    "src/main/java/p/JpaConfig.java",
                    "package p; @org.springframework.data.jpa.repository.config.EnableJpaRepositories public class JpaConfig {}");
        return sources;
    }

    private String describe(boolean enable, String type) throws Exception {
        var db = JavaEvidenceFixture.index(root.resolve(enable ? "on" : "off"), sources(enable));
        return TestReads.read(db, reader -> SpringDataRepositories.describe(reader, type));
    }

    @Test
    void aRepositoryInterfaceIsAProxyBeanRegisteredByBootAutoConfiguration() throws Exception {
        String text = describe(false, "p.UserRepository");
        assertTrue(text.contains("JpaRepository"), text);
        assertTrue(text.contains("userRepository"), text);
        assertTrue(text.contains("p.App"), text);
        assertTrue(text.contains("no @EnableJpaRepositories"), text);
        assertTrue(!text.contains("q.TestJpa"), text);
    }

    @Test
    void anExplicitEnableAnnotationIsNamed() throws Exception {
        String text = describe(true, "p.UserRepository");
        assertTrue(text.contains("@EnableJpaRepositories on p.JpaConfig"), text);
    }

    @Test
    void anOrdinaryInterfaceIsNotARepository() throws Exception {
        assertNull(describe(false, "p.Plain"));
    }

    /**
     * Apollo: AdminServiceApplication runs in another module and does not scan portal repositories.
     */
    @Test
    void onlyAutoConfigurationVisibleToTheRepositoryModuleCounts() throws Exception {
        Map<String, String> sources = sources(false);
        sources.put(
                "src/main/java/z/OtherApp.java",
                "package z; @org.springframework.boot.autoconfigure.SpringBootApplication public class OtherApp {}");
        var db = JavaEvidenceFixture.index(root.resolve("modules"), sources);
        db.useHandle(
                handle -> {
                    handle.execute(
                            "UPDATE classes SET module = 'other' WHERE class_name = 'z.OtherApp'");
                    handle.execute(
                            "INSERT INTO module_classpath(application_module, visible_module, distance, relation) VALUES ('.', '.', 0, 'self')");
                });
        String text =
                TestReads.read(
                        db, reader -> SpringDataRepositories.describe(reader, "p.UserRepository"));
        assertTrue(text.contains("p.App"), text);
        assertTrue(!text.contains("z.OtherApp"), text);
    }
}
