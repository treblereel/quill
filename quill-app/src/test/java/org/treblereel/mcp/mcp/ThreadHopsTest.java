package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.IndexFixture;
import org.treblereel.mcp.db.IndexWriter;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.model.BeanRecord;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.InjectionPointRecord;

/** V55 CQ-025: asked whether an async path may use contextual beans, Codex read every class the deferred supplier reaches for its scope. */
class ThreadHopsTest {
    @TempDir Path root;

    @Test void aMethodSwitchingThreadsNamesTheSwitchAndTheBeansReachedAfterIt() throws Exception {
        Jdbi db = QuillDatabase.create(root.resolve("hops.db"));
        IndexWriter.writeIncremental(db, new IndexFixture()
                .classes(List.of(
                        new ClassRecord(0, "x.Auth", "CLASS", "java.lang.Object", List.of(), "Auth.java", 1, true, 10),
                        new ClassRecord(0, "x.Cache", "CLASS", "java.lang.Object", List.of(), "Cache.java", 1, true, 10),
                        new ClassRecord(0, "x.Req", "CLASS", "java.lang.Object", List.of(), "Req.java", 1, true, 10)))
                .beans(List.of(
                        new BeanRecord(0, 1, "CLASS", "@ApplicationScoped", List.of("@Default"), List.of(), false, null, null, null, null, List.of("x.Auth")),
                        new BeanRecord(0, 2, "CLASS", "@ApplicationScoped", List.of("@Default"), List.of(), false, null, null, null, null, List.of("x.Cache")),
                        new BeanRecord(0, 3, "CLASS", "@RequestScoped", List.of("@Default"), List.of(), false, null, null, null, null, List.of("x.Req"))))
                .injectionPoints(List.of(
                        InjectionPointRecord.staticAnalysis(0, 1, "FIELD", "x.Cache", List.of("@Default"), "cache", 2, false, InjectionPointRecord.STATIC_CDI),
                        InjectionPointRecord.staticAnalysis(0, 1, "FIELD", "x.Req", List.of("@Default"), "req", 3, false, InjectionPointRecord.STATIC_CDI)))
                .metadata(Map.of("index_id", "hops")).snapshot());
        db.useHandle(handle -> {
            handle.execute("INSERT INTO method_evidence(from_class_id,from_method,from_descriptor,caller_external_calls) VALUES "
                    + "(1,'check','()V','[\"3|10|io.smallrye.mutiny.infrastructure.Infrastructure|getDefaultExecutor|()Ljava/util/concurrent/Executor;|\","
                    + "\"4|10|io.smallrye.mutiny.Uni|runSubscriptionOn|(Ljava/util/concurrent/Executor;)Lio/smallrye/mutiny/Uni;|\"]')");
            handle.execute("INSERT INTO method_calls(from_class_id,from_method,from_descriptor,to_class_id,to_method,to_descriptor,invocation_kind) VALUES "
                    + "(1,'check','()V',1,'lambda$check$0','()Ljava/lang/Boolean;','static'),"
                    + "(1,'lambda$check$0','()Ljava/lang/Boolean;',1,'perform','()Z','virtual')");
            handle.execute("INSERT INTO field_accesses(from_class_id,from_method,from_descriptor,to_class_id,field_name,field_descriptor,access_kind,evidence_lines) VALUES "
                    + "(1,'perform','()Z',1,'cache','Lx/Cache;','read_instance','[20]'),"
                    + "(1,'perform','()Z',1,'req','Lx/Req;','read_instance','[21]')");
        });

        var hops = TestReads.read(db, reader -> ThreadHops.of(reader, 1, "check", "()V"));
        assertEquals("[\"line 10 io.smallrye.mutiny.Uni#runSubscriptionOn(Infrastructure#getDefaultExecutor)\"]", hops.path("switches").toString(), hops.toString());
        assertEquals("[\"x.Cache @ApplicationScoped: cache in perform\",\"x.Req @RequestScoped: req in perform\"]",
                hops.path("beans_reached_after").toString(), hops.toString());
        assertEquals("[\"x.Req\"]", hops.path("request_scoped_after").toString());
        assertNull(TestReads.read(db, reader -> ThreadHops.of(reader, 1, "perform", "()Z")), "no switch, no fact");
    }
}
