package org.treblereel.mcp.mcp.prefetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.IndexFixture;
import org.treblereel.mcp.db.IndexWriter;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.mcp.TestReads;
import org.treblereel.mcp.model.BeanRecord;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.InjectionPointRecord;

/**
 * V55 CQ-025: asked whether an async path may use contextual beans, Codex read every class the
 * deferred supplier reaches for its scope.
 */
class ThreadHopsTest {
    @TempDir Path root;

    @Test
    void aMethodSwitchingThreadsNamesTheSwitchAndTheBeansReachedAfterIt() throws Exception {
        Jdbi db = QuillDatabase.create(root.resolve("hops.db"));
        IndexWriter.writeIncremental(
                db,
                new IndexFixture()
                        .classes(
                                List.of(
                                        new ClassRecord(
                                                0,
                                                "x.Auth",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of(),
                                                "Auth.java",
                                                1,
                                                true,
                                                10),
                                        new ClassRecord(
                                                0,
                                                "x.Cache",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of(),
                                                "Cache.java",
                                                1,
                                                true,
                                                10),
                                        new ClassRecord(
                                                0,
                                                "x.Req",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of(),
                                                "Req.java",
                                                1,
                                                true,
                                                10)))
                        .beans(
                                List.of(
                                        new BeanRecord(
                                                0,
                                                1,
                                                "CLASS",
                                                "@ApplicationScoped",
                                                List.of("@Default"),
                                                List.of(),
                                                false,
                                                null,
                                                null,
                                                null,
                                                null,
                                                List.of("x.Auth")),
                                        new BeanRecord(
                                                0,
                                                2,
                                                "CLASS",
                                                "@ApplicationScoped",
                                                List.of("@Default"),
                                                List.of(),
                                                false,
                                                null,
                                                null,
                                                null,
                                                null,
                                                List.of("x.Cache")),
                                        new BeanRecord(
                                                0,
                                                3,
                                                "CLASS",
                                                "@RequestScoped",
                                                List.of("@Default"),
                                                List.of(),
                                                false,
                                                null,
                                                null,
                                                null,
                                                null,
                                                List.of("x.Req"))))
                        .injectionPoints(
                                List.of(
                                        InjectionPointRecord.staticAnalysis(
                                                0,
                                                1,
                                                "FIELD",
                                                "x.Cache",
                                                List.of("@Default"),
                                                "cache",
                                                2,
                                                false,
                                                InjectionPointRecord.STATIC_CDI),
                                        InjectionPointRecord.staticAnalysis(
                                                0,
                                                1,
                                                "FIELD",
                                                "x.Req",
                                                List.of("@Default"),
                                                "req",
                                                3,
                                                false,
                                                InjectionPointRecord.STATIC_CDI)))
                        .metadata(Map.of("index_id", "hops"))
                        .snapshot());
        db.useHandle(
                handle -> {
                    handle.execute(
                            "INSERT INTO method_evidence(from_class_id,from_method,from_descriptor,caller_external_calls) VALUES "
                                    + "(1,'check','()V','[\"3|10|io.smallrye.mutiny.infrastructure.Infrastructure|getDefaultExecutor|()Ljava/util/concurrent/Executor;|\","
                                    + "\"4|10|io.smallrye.mutiny.Uni|runSubscriptionOn|(Ljava/util/concurrent/Executor;)Lio/smallrye/mutiny/Uni;|\"]')");
                    handle.execute(
                            "INSERT INTO method_calls(from_class_id,from_method,from_descriptor,to_class_id,to_method,to_descriptor,invocation_kind) VALUES "
                                    + "(1,'check','()V',1,'lambda$check$0','()Ljava/lang/Boolean;','static'),"
                                    + "(1,'lambda$check$0','()Ljava/lang/Boolean;',1,'perform','()Z','virtual')");
                    handle.execute(
                            "INSERT INTO field_accesses(from_class_id,from_method,from_descriptor,to_class_id,field_name,field_descriptor,access_kind,evidence_lines) VALUES "
                                    + "(1,'perform','()Z',1,'cache','Lx/Cache;','read_instance','[20]'),"
                                    + "(1,'perform','()Z',1,'req','Lx/Req;','read_instance','[21]')");
                    // Apollo AQ-025: a static ThreadLocal holder set on the request thread is empty
                    // after the switch.
                    handle.execute(
                            "INSERT INTO class_members(class_id,kind,name,signature,descriptor,type_name,parameter_types,modifiers,annotations) VALUES "
                                    + "(2,'FIELD','HOLDER','HOLDER','Ljava/lang/ThreadLocal;','java.lang.ThreadLocal<java.lang.String>','[]','private static final','[]')");
                });

        var hops = TestReads.read(db, reader -> ThreadHops.of(reader, 1, "check", "()V"));
        assertEquals(
                "[\"line 10 io.smallrye.mutiny.Uni#runSubscriptionOn(Infrastructure#getDefaultExecutor)\"]",
                hops.path("switches").toString(),
                hops.toString());
        assertEquals(
                "[\"x.Cache @ApplicationScoped: cache in perform\",\"x.Req @RequestScoped: req in perform\"]",
                hops.path("beans_reached_after").toString(),
                hops.toString());
        assertEquals("[\"x.Req\"]", hops.path("request_scoped_after").toString());
        assertTrue(
                hops.path("thread_bound_state")
                        .toString()
                        .contains("x.Cache.HOLDER java.lang.ThreadLocal<java.lang.String>"),
                hops.toString());
        assertNull(
                TestReads.read(db, reader -> ThreadHops.of(reader, 1, "perform", "()Z")),
                "no switch, no fact");
    }

    /**
     * V58/V59 CQ-025: answers missed the values the deferred supplier carries to the worker thread
     * (rawToken, permissionName).
     */
    @Test
    void aHandedOffLambdaNamesTheValuesItCaptures() {
        List<String> source =
                List.of(
                        "    private Uni<Boolean> checkSsarPermission(String rawToken, String permissionName) {",
                        "        var k8s = mapping.get();",
                        "        return Uni.createFrom()",
                        "                .<Boolean>item(() -> performSsarCheck(rawToken, permissionName, k8s))",
                        "                .runSubscriptionOn(Infrastructure.getDefaultExecutor())",
                        "                .onFailure()",
                        "                .recoverWithItem(",
                        "                        e -> {",
                        "                            log.warnf(e, \"failed for '%s' (%s)\", permissionName, k8s.resource());",
                        "                            return false;",
                        "                        });");
        assertEquals(
                "line 4: rawToken String, permissionName String, k8s PermissionMapper$K8sResourceVerb",
                ThreadHops.captured(
                        source,
                        4,
                        "(Ljava/lang/String;Ljava/lang/String;Lio/cryostat/PermissionMapper$K8sResourceVerb;)Ljava/lang/Boolean;",
                        Set.of("log")));
        assertEquals(
                "line 7: permissionName String, k8s PermissionMapper$K8sResourceVerb",
                ThreadHops.captured(
                        source,
                        7,
                        "(Ljava/lang/String;Lio/cryostat/PermissionMapper$K8sResourceVerb;Ljava/lang/Throwable;)Ljava/lang/Boolean;",
                        Set.of("log")));
    }

    @Test
    void theFactStatesWhetherContextPropagationIsOnTheClasspath() {
        assertEquals(
                "on the classpath: io.quarkus:quarkus-smallrye-context-propagation:3.40.1.1, io.smallrye.reactive:mutiny-smallrye-context-propagation:3.3.0;"
                        + " with them Quarkus carries the CDI request context active when the pipeline is built to the executor thread",
                ThreadHops.propagation(
                        List.of(
                                Path.of(
                                        "/m2/io/quarkus/quarkus-arc/3.40.1.1/quarkus-arc-3.40.1.1.jar"),
                                Path.of(
                                        "/m2/io/quarkus/quarkus-smallrye-context-propagation/3.40.1.1/quarkus-smallrye-context-propagation-3.40.1.1.jar"),
                                Path.of(
                                        "/m2/io/smallrye/reactive/mutiny-smallrye-context-propagation/3.3.0/mutiny-smallrye-context-propagation-3.3.0.jar"))));
        assertEquals(
                "no context-propagation artifact on the classpath (of 1 jars): request/session context is not carried to the other thread",
                ThreadHops.propagation(
                        List.of(
                                Path.of(
                                        "/m2/io/quarkus/quarkus-arc/3.40.1.1/quarkus-arc-3.40.1.1.jar"))));
        assertNull(ThreadHops.propagation(List.of()), "no captured classpath, no claim");
    }

    /**
     * Cryostat CQ-025: told only that the artifacts were present, Codex opened the libraries to find
     * which decorator wraps Uni callbacks and which provider carries the CDI context (7 of 11 calls).
     */
    @Test
    void thePropagationNamesTheRegisteredUniDecoratorAndTheCdiContextProvider() throws Exception {
        Path mutiny = jar("io/smallrye/reactive/mutiny-smallrye-context-propagation/3.3.0/mutiny-smallrye-context-propagation-3.3.0.jar",
                Map.of("META-INF/services/io.smallrye.mutiny.infrastructure.CallbackDecorator",
                        "# decorator\nio.smallrye.mutiny.context.DefaultContextPropagationInterceptor\n"));
        Path arc = jar("io/quarkus/quarkus-arc/3.40.1.1/quarkus-arc-3.40.1.1.jar",
                Map.of("io/quarkus/arc/runtime/context/ArcContextProvider.class", ""));
        String text = ThreadHops.propagation(List.of(arc, mutiny));
        assertTrue(text.contains("Uni callbacks decorated by io.smallrye.mutiny.context.DefaultContextPropagationInterceptor"), text);
        assertTrue(text.contains("CDI context captured by io.quarkus.arc.runtime.context.ArcContextProvider"), text);
    }

    private Path jar(String relative, Map<String, String> entries) throws Exception {
        Path jar = root.resolve("m2").resolve(relative);
        java.nio.file.Files.createDirectories(jar.getParent());
        try (var out = new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(jar))) {
            for (var entry : entries.entrySet()) {
                out.putNextEntry(new java.util.jar.JarEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return jar;
    }
}
