package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.core.SourceFileHashes;
import org.treblereel.mcp.model.ClassRecord;

/** V32/V33 CQ-047: a test class fact named the profile's keys but not what they change or what the test replaces. */
class TestClassPrefetchTest {
    @TempDir Path root;
    Jdbi db;

    @BeforeEach void setup() throws Exception {
        db = JavaEvidenceFixture.index(root, Map.of(
                "src/main/java/p/Store.java", "package p;public class Store {}",
                "src/main/java/p/Bus.java", "package p;public class Bus {}",
                "src/main/resources/application.properties", "warehouse.enabled=false\nwarehouse.port=8080\n",
                "src/test/java/jakarta/inject/Inject.java", "package jakarta.inject;import java.lang.annotation.*;@Retention(RetentionPolicy.RUNTIME)public @interface Inject {}",
                "src/test/java/io/quarkus/test/InjectMock.java", "package io.quarkus.test;import java.lang.annotation.*;@Retention(RetentionPolicy.RUNTIME)public @interface InjectMock {}",
                "src/test/java/io/quarkus/test/junit/QuarkusTestProfile.java", "package io.quarkus.test.junit;public interface QuarkusTestProfile {java.util.Map<String,String> getConfigOverrides();}",
                "src/test/java/p/BaseTest.java", "package p;import jakarta.inject.Inject;public abstract class BaseTest { @Inject Bus bus; String plain; }",
                "src/test/java/p/WarehouseTest.java", """
                        package p;
                        import java.util.Map;
                        import io.quarkus.test.InjectMock;
                        import jakarta.inject.Inject;
                        import io.quarkus.test.junit.QuarkusTestProfile;
                        public class WarehouseTest extends BaseTest {
                          public static class Profile implements QuarkusTestProfile {
                            public Map<String,String> getConfigOverrides() {
                              return Map.of("warehouse.enabled","true");
                            }
                          }
                          @InjectMock Store store;
                          @Inject Store real;
                          int counter;
                        }
                        """));
        // The fixture leaves a nested class without a source file, so its profile scan skips it; the real index records it.
        String evidence = new ObjectMapper().createObjectNode().put("declaring_class", "p.WarehouseTest$Profile")
                .put("method", "getConfigOverrides()").put("status", "static_map").put("activation", "unknown")
                .put("source_sha256", SourceFileHashes.hash(root.resolve("src/test/java/p/WarehouseTest.java")))
                .put("binary_file", "target/classes/p/WarehouseTest$Profile.class")
                .put("binary_sha256", SourceFileHashes.hash(root.resolve("target/classes/p/WarehouseTest$Profile.class"))).toString();
        db.useHandle(handle -> {
            handle.execute("UPDATE classes SET source_set = CASE WHEN source_file LIKE '%/src/test/%' OR class_name = 'p.WarehouseTest$Profile' THEN 'test' ELSE 'main' END");
            handle.execute("INSERT INTO configuration_definitions(key, kind, file, line, evidence, module, source_set) "
                    + "VALUES ('warehouse.enabled', 'quarkus_test_profile', 'src/test/java/p/WarehouseTest.java', 9, ?, '.', 'test')", evidence);
        });
    }

    private ObjectNode relations(String name) throws Exception {
        return TestReads.read(db, reader -> {
            ClassRecord cls = reader.symbols.findClassesByName(name).getFirst();
            ObjectNode fact = new ObjectMapper().createObjectNode();
            ClassPrefetchRelations.append(reader, fact, cls);
            return fact;
        });
    }

    @Test void aTestProfileKeyStatesItsTestValueAndTheProductionDeclaration() throws Exception {
        ObjectNode fact = relations("p.WarehouseTest");
        String line = fact.path("configuration").get(0).asText();
        assertTrue(line.startsWith("warehouse.enabled=true [quarkus_test_profile"), fact.toString());
        assertTrue(line.endsWith("; main src/main/resources/application.properties:1=false"), line);
    }

    @Test void aTestClassStatesWhatItInjectsAndMocksIncludingInheritedFields() throws Exception {
        ObjectNode fact = relations("p.WarehouseTest");
        assertEquals(List.of("@Inject Store real", "@InjectMock Store store replaces no indexed default bean", "@Inject Bus bus (BaseTest)"),
                fact.path("test_injections").valueStream().map(n -> n.asText()).toList(), fact.toString());
        assertFalse(relations("p.Store").has("test_injections"), "production classes keep their injects section");
    }

    /** V41/V62 CQ-047: every Codex session asked list_beans for each mocked type to learn which bean the mock replaces. */
    @Test void aMockNamesTheBeanItReplacesAndAPseudoScope() throws Exception {
        db.useHandle(handle -> handle.execute("INSERT INTO external_beans(class_name, kind, scope, qualifiers, stereotypes, profiles, member_name, "
                + "bean_types, framework, artifact, injection_points) VALUES ('io.quarkus.kubernetes.client.runtime.KubernetesClientProducer', "
                + "'PRODUCER_METHOD', '@Singleton', '[\"@Default\"]', '[]', '[]', 'kubernetesClient', '[\"p.Store\",\"java.lang.Object\"]', "
                + "'quarkus', 'io.quarkus:quarkus-kubernetes-client:3.40.1.1', '[]')"));
        ObjectNode fact = relations("p.WarehouseTest");
        assertEquals("@InjectMock Store store replaces PRODUCER_METHOD io.quarkus.kubernetes.client.runtime.KubernetesClientProducer#kubernetesClient "
                + "@Singleton from io.quarkus:quarkus-kubernetes-client:3.40.1.1; @Singleton is a pseudo-scope: @InjectMock needs @MockitoConfig(convertScopes = true)",
                fact.path("test_injections").get(1).asText(), fact.toString());
        assertEquals("@Inject Store real", fact.path("test_injections").get(0).asText());
    }
}
