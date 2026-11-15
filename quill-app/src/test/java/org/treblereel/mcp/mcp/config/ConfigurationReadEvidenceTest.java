package org.treblereel.mcp.mcp.config;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.mcp.JavaEvidenceFixture;
import org.treblereel.mcp.mcp.TestReads;
import org.treblereel.mcp.mcp.prefetch.PrefetchContext;

class ConfigurationReadEvidenceTest {
    @TempDir Path root;
    Jdbi db;
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setup() throws Exception {
        db =
                JavaEvidenceFixture.index(
                        root,
                        Map.of(
                                "src/main/java/org/eclipse/microprofile/config/inject/ConfigProperty.java",
                                        "package org.eclipse.microprofile.config.inject;import java.lang.annotation.*;@Retention(RetentionPolicy.RUNTIME)@Target({ElementType.FIELD,ElementType.METHOD,ElementType.PARAMETER})public @interface ConfigProperty {String name();}",
                                "src/main/java/p/Fixture.java",
                                        """
          package p;
          import org.eclipse.microprofile.config.inject.ConfigProperty;
          class Fixture {
            public static final String KEY="warehouse.enabled",OTHER="unrelated",ALIAS="warehouse.enabled";
            static String mutable="warehouse.enabled";
            @ConfigProperty(name=KEY) boolean enabled;
            boolean read(){return enabled;}
            boolean read(int x){return enabled && x>0;}
            void write(){enabled=true;}
          }
          class Other {boolean enabled;@ConfigProperty(name="warehouse.enabled") boolean enabled(){return enabled;}}
          """));
    }

    JsonNode query(String view) throws Exception {
        return new ConfigurationReferenceQueries()
                .find(db, "warehouse.enabled", null, "all", null, 12, 0, view);
    }

    @Test
    void exactFieldReadsKeepOverloadsExcludeWritesAndHomonymousFields() throws Exception {
        var data = query("declared");
        var reads = data.path("references").get(0).path("field_reads");
        assertEquals(2, reads.path("total").asInt(), data.toString());
        assertEquals(
                Set.of("()Z", "(I)Z"),
                new HashSet<>(
                        reads.path("sites")
                                .valueStream()
                                .map(x -> x.path("descriptor").asText())
                                .toList()));
        assertTrue(
                reads.path("sites")
                        .valueStream()
                        .allMatch(
                                x ->
                                        x.path("method").asText().equals("read")
                                                && !x.path("lines").isEmpty()));
        assertFalse(query("index").toString().contains("field_reads"));
        assertFalse(
                data.path("references")
                        .valueStream()
                        .filter(x -> x.path("className").asText().equals("p.Other"))
                        .findFirst()
                        .orElseThrow()
                        .has("field_reads"));
    }

    @Test
    void constantsAreExactStaticFinalAliasesNotClaimedSourceBindings() throws Exception {
        var data = query("declared");
        var constants = data.path("key_constants");
        assertEquals(
                Set.of("KEY", "ALIAS"),
                new HashSet<>(
                        constants
                                .path("items")
                                .valueStream()
                                .map(x -> x.path("field").asText())
                                .toList()),
                data.toString());
        assertTrue(
                constants.path("items").valueStream().allMatch(x -> x.path("line").asInt() == 4),
                constants.toString());
        assertTrue(constants.path("basis").asText().contains("not proven"));
        var packet =
                TestReads.read(
                        db,
                        readScope ->
                                PrefetchContext.collect(
                                        readScope, root, "Расскажи warehouse.enabled."));
        assertTrue(packet.toString().contains("key_constants"));
        assertTrue(packet.toString().contains("field_reads"));
        assertFalse(query("index").toString().contains("key_constants"));
    }

    /**
     * V34 CQ-040: whether a consumer names the key by literal or by constant decides whether a
     * rename edits it.
     */
    @Test
    void aConsumerStatesWhetherItNamesTheKeyByConstantOrLiteral() throws Exception {
        var data = query("declared");
        var field =
                data.path("references")
                        .valueStream()
                        .filter(
                                x ->
                                        x.path("className").asText().equals("p.Fixture")
                                                && x.path("entry_type").asText().equals("usage"))
                        .findFirst()
                        .orElseThrow();
        assertEquals("KEY", field.path("key_via").asText(), data.toString());
        assertEquals(6, field.path("line").asInt());
    }

    @Test
    void ambiguousOrMissingBytecodeDoesNotInventConstantEvidence() throws Exception {
        db.useHandle(
                h ->
                        h.execute(
                                "UPDATE jvm_api SET evidence='{\"status\":\"ambiguous_bytecode_owner\"}'"));
        assertTrue(query("declared").path("key_constants").path("items").isEmpty());
    }

    @Test
    void readPagesRemainExplicitlyBounded() throws Exception {
        int id =
                db.withHandle(
                        h ->
                                h.createQuery("SELECT id FROM classes WHERE class_name='p.Fixture'")
                                        .mapTo(Integer.class)
                                        .one());
        for (int i = 0; i < 10; i++) {
            final int n = i;
            db.useHandle(
                    h ->
                            h.execute(
                                    "INSERT INTO field_accesses(from_class_id,from_method,from_descriptor,to_class_id,field_name,field_descriptor,access_kind,evidence_lines) VALUES (?,?,'()Z',?,'enabled','Z','read_instance','[1,2,3,4,5,6,7,8,9]')",
                                    id,
                                    "extra" + n,
                                    id));
        }
        var reads = query("declared").path("references").get(0).path("field_reads");
        assertEquals(12, reads.path("total").asInt());
        assertEquals(8, reads.path("sites").size());
        assertTrue(reads.path("truncated").asBoolean());
        assertTrue(reads.toString().contains("lines_truncated"));
    }

    @Test
    void constantsRespectModulesAndTruncationAndProgrammaticMembersAreNotFields() throws Exception {
        db.useHandle(
                h -> h.execute("UPDATE classes SET module='other' WHERE class_name='p.Fixture'"));
        var scoped =
                new ConfigurationReferenceQueries()
                        .find(db, "warehouse.enabled", null, "all", ".", 12, 0, "declared");
        assertTrue(scoped.path("key_constants").path("items").isEmpty(), scoped.toString());
        db.useHandle(h -> h.execute("UPDATE classes SET module='.' WHERE class_name='p.Fixture'"));
        var api = json.createObjectNode().put("status", "captured");
        var members = api.putArray("members");
        for (int n = 0; n < 12; n++)
            members.addObject()
                    .put("kind", "FIELD")
                    .put("descriptor", "Ljava/lang/String;")
                    .put("access_flags", 24)
                    .put("name", "K" + n)
                    .putObject("constant")
                    .put("value", "warehouse.enabled");
        db.useHandle(
                h ->
                        h.createUpdate("UPDATE jvm_api SET evidence=:evidence")
                                .bind("evidence", api.toString())
                                .execute());
        assertEquals(8, query("declared").path("key_constants").path("items").size());
        assertTrue(query("declared").path("key_constants").path("truncated").asBoolean());
        db.useHandle(
                h -> h.execute("UPDATE configuration_usages SET annotation='Config#getValue'"));
        assertFalse(query("declared").toString().contains("field_reads"));
    }
}
