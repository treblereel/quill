package org.treblereel.mcp.mcp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.mcp.JavaEvidenceFixture;
import org.treblereel.mcp.mcp.TestReads;

/**
 * Apollo AQ-039: asked whether emergencyPublish.supported.envs can change in a running portal,
 * Claude read RefreshableConfig, RefreshablePropertySource and PortalDBPropertySource; the key's
 * evidence named Spring's documented order but not that RefreshableConfig#setup adds a property
 * source with addLast and schedules its refresh.
 */
class ProgrammaticPropertySourcesTest {
    @TempDir Path root;

    @Test
    void aMethodAddingAPropertySourceAndSchedulingWorkIsNamed() throws Exception {
        var db =
                JavaEvidenceFixture.index(
                        root,
                        "package p; class Cfg { void setup() {} } class Other { void run() {} }");
        db.useHandle(
                handle -> {
                    handle.execute(
                            "INSERT OR REPLACE INTO method_evidence(from_class_id, from_method, from_descriptor, caller_external_calls) "
                                    + "SELECT id, 'setup', '()V', ? FROM classes WHERE class_name = 'p.Cfg'",
                            "[\"31|72|org.springframework.core.env.MutablePropertySources|addLast|(Lorg/springframework/core/env/PropertySource;)V|\","
                                    + "\"47|79|java.util.concurrent.ScheduledExecutorService|scheduleWithFixedDelay|(Ljava/lang/Runnable;JJLjava/util/concurrent/TimeUnit;)Ljava/util/concurrent/ScheduledFuture;|\"]");
                    handle.execute(
                            "INSERT OR REPLACE INTO method_evidence(from_class_id, from_method, from_descriptor, caller_external_calls) "
                                    + "SELECT id, 'run', '()V', '[\"1|5|java.util.List|add|(Ljava/lang/Object;)Z|\"]' FROM classes WHERE class_name = 'p.Other'");
                });
        var lines =
                TestReads.read(
                        db, reader -> ProgrammaticPropertySources.describe(reader, Set.of()));
        assertEquals(1, lines.size(), lines.toString());
        assertTrue(
                lines.get(0)
                        .startsWith(
                                "p.Cfg#setup adds property sources (MutablePropertySources#addLast line 72)"),
                lines.toString());
        assertTrue(
                lines.get(0).contains("ScheduledExecutorService#scheduleWithFixedDelay line 79"),
                lines.toString());
    }
}
