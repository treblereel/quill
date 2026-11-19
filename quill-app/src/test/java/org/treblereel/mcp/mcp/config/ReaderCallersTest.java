package org.treblereel.mcp.mcp.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.mcp.JavaEvidenceFixture;
import org.treblereel.mcp.mcp.TestReads;

/**
 * Apollo AQ-036 (Codex): asked where emergencyPublish.supported.envs is set and used, the key
 * answer named PortalConfig#isEmergencyPublishAllowed but not the controllers calling it, so Codex
 * grepped for them.
 */
class ReaderCallersTest {
    @TempDir Path root;

    @Test
    void aProgrammaticReaderNamesItsProductionCallers() throws Exception {
        var db =
                JavaEvidenceFixture.index(
                        root,
                        "package p; class Cfg { boolean allowed() { return true; } } "
                                + "class Ctl { void check() { new Cfg().allowed(); } } class Other { void run() {} }");
        db.useHandle(
                handle ->
                        handle.execute(
                                "INSERT INTO configuration_usages(key, kind, class_id, class_name, member, annotation, source_set) "
                                        + "SELECT 'k.envs', 'config_key', id, class_name, 'allowed', 'p.Base#get -> org.springframework.core.env.Environment#getProperty', 'main' "
                                        + "FROM classes WHERE class_name = 'p.Cfg'"));
        var result = new ObjectMapper().createObjectNode();
        TestReads.read(
                db,
                reader -> {
                    ApplicationKeyPhase.append(reader, "k.envs", result);
                    return null;
                });
        assertTrue(
                result.path("reader_callers").toString().contains("p.Cfg#allowed <- p.Ctl#check"),
                result.toString());
    }
}
