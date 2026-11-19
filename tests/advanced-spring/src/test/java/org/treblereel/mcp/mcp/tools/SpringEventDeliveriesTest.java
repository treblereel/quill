package org.treblereel.mcp.mcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.IndexFixture;
import org.treblereel.mcp.db.IndexWriter;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.model.*;

/**
 * Apollo AQ-028: calls stopped at publishEvent, so listeners, multicaster and transaction phase
 * were grep work.
 */
class SpringEventDeliveriesTest {

    /**
     * Runs one index query and returns its JSON, as the tool methods these tests were written
     * against did.
     */
    private static String read(
            org.jdbi.v3.core.Jdbi jdbi,
            java.util.function.Function<
                            ReadQueryContext, ? extends com.fasterxml.jackson.databind.JsonNode>
                    query) {
        return ReadQueryContext.readWithGraph(jdbi, query).toString();
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String LISTENER = "org.springframework.context.event.EventListener";
    private static final String TX_LISTENER =
            "org.springframework.transaction.event.TransactionalEventListener";
    private static final String VOID = "()V";
    private static final String PUBLISH =
            "30|46|org.springframework.context.ApplicationEventPublisher|publishEvent|(Ljava/lang/Object;)V|";

    @TempDir Path tempDir;
    Jdbi jdbi;

    @BeforeEach
    void setUp() {
        jdbi = QuillDatabase.create(tempDir.resolve("events.db"));
        var classes =
                List.of(
                        new ClassRecord(
                                0,
                                "com.example.ReleaseController",
                                "CLASS",
                                "java.lang.Object",
                                List.of(),
                                "src/main/java/com/example/ReleaseController.java",
                                5,
                                true,
                                100),
                        new ClassRecord(
                                0,
                                "com.example.ReleaseEvent",
                                "CLASS",
                                "com.example.BaseEvent",
                                List.of(),
                                "src/main/java/com/example/ReleaseEvent.java",
                                3,
                                false,
                                50),
                        new ClassRecord(
                                0,
                                "com.example.BaseEvent",
                                "CLASS",
                                "org.springframework.context.ApplicationEvent",
                                List.of(),
                                "src/main/java/com/example/BaseEvent.java",
                                3,
                                false,
                                50),
                        new ClassRecord(
                                0,
                                "com.example.ReleaseListener",
                                "CLASS",
                                "java.lang.Object",
                                List.of(),
                                "src/main/java/com/example/ReleaseListener.java",
                                3,
                                true,
                                50),
                        new ClassRecord(
                                0,
                                "com.example.AuditListener",
                                "CLASS",
                                "java.lang.Object",
                                List.of(),
                                "src/main/java/com/example/AuditListener.java",
                                3,
                                true,
                                50),
                        new ClassRecord(
                                0,
                                "com.example.OtherListener",
                                "CLASS",
                                "java.lang.Object",
                                List.of(),
                                "src/main/java/com/example/OtherListener.java",
                                3,
                                true,
                                50));
        var members =
                List.of(
                        new ClassMemberRecord(
                                1,
                                "METHOD",
                                "release",
                                "release()",
                                VOID,
                                "void",
                                List.of(),
                                "public",
                                List.of()),
                        new ClassMemberRecord(
                                1,
                                "METHOD",
                                "publish",
                                "publish()",
                                VOID,
                                "void",
                                List.of(),
                                "private",
                                List.of()),
                        new ClassMemberRecord(
                                4,
                                "METHOD",
                                "onRelease",
                                "onRelease(com.example.ReleaseEvent)",
                                "(Lcom/example/ReleaseEvent;)V",
                                "void",
                                List.of("com.example.ReleaseEvent"),
                                "public",
                                List.of(LISTENER)),
                        new ClassMemberRecord(
                                5,
                                "METHOD",
                                "afterCommit",
                                "afterCommit(com.example.BaseEvent)",
                                "(Lcom/example/BaseEvent;)V",
                                "void",
                                List.of("com.example.BaseEvent"),
                                "public",
                                List.of(TX_LISTENER),
                                List.of(
                                        new MemberAnnotationRecord(
                                                TX_LISTENER,
                                                "METHOD",
                                                -1,
                                                "",
                                                "",
                                                Map.of("phase", "AFTER_COMMIT")))),
                        new ClassMemberRecord(
                                6,
                                "METHOD",
                                "onOther",
                                "onOther(java.lang.String)",
                                "(Ljava/lang/String;)V",
                                "void",
                                List.of("java.lang.String"),
                                "public",
                                List.of(LISTENER)));
        var calls =
                List.of(
                        new MethodCallRecord(
                                1, "release", VOID, 1, "publish", VOID, "virtual", 1, List.of(20)),
                        new MethodCallRecord(
                                1,
                                "publish",
                                VOID,
                                2,
                                "<init>",
                                VOID,
                                "special",
                                1,
                                List.of(45),
                                List.of(),
                                0,
                                0,
                                List.of(),
                                List.of(),
                                List.of(PUBLISH)));
        IndexWriter.writeIncremental(
                jdbi,
                new IndexFixture()
                        .classes(classes)
                        .classMembers(members)
                        .methodCalls(calls)
                        .metadata(
                                Map.of(
                                        "indexed_at",
                                        "2026-11-14T10:00:00",
                                        "last_commit",
                                        "abc1234"))
                        .snapshot());
    }

    @Test
    void outboundCallsResolveThePublishedEventToItsListeners() throws Exception {
        String text =
                read(
                        jdbi,
                        r ->
                                new QuillToolQueries()
                                        .getCallHierarchy(
                                                r,
                                                "com.example.ReleaseController",
                                                "release",
                                                "outbound",
                                                20,
                                                0));
        JsonNode events = JSON.readTree(text).path("event_deliveries");
        JsonNode publication = events.path("publications").get(0);
        assertEquals(
                "com.example.ReleaseController#publish",
                publication.path("publisher_method").asText(),
                text);
        assertEquals(
                "com.example.ReleaseEvent", publication.path("event_types").get(0).asText(), text);
        JsonNode listeners = publication.path("listeners");
        assertEquals(
                2,
                listeners.size(),
                "a supertype listener receives it too, an unrelated one does not: " + text);
        assertTrue(
                listeners.toString().contains("ReleaseListener#onRelease")
                        && listeners.toString().contains("@EventListener"),
                text);
        assertTrue(
                listeners.toString().contains("@TransactionalEventListener")
                        && listeners.toString().contains("AFTER_COMMIT"),
                text);
        assertTrue(
                events.path("multicaster").asText().contains("SimpleApplicationEventMulticaster"),
                text);
        assertEquals(0, events.path("transactional_publishing_path").size(), text);
    }

    @Test
    void inboundQueriesAndMethodsThatPublishNothingGetNoSection() throws Exception {
        assertFalse(
                JSON.readTree(
                                read(
                                        jdbi,
                                        r ->
                                                new QuillToolQueries()
                                                        .getCallHierarchy(
                                                                r,
                                                                "com.example.ReleaseController",
                                                                "release",
                                                                "inbound",
                                                                20,
                                                                0)))
                        .has("event_deliveries"));
        assertFalse(
                JSON.readTree(
                                read(
                                        jdbi,
                                        r ->
                                                new QuillToolQueries()
                                                        .getCallHierarchy(
                                                                r,
                                                                "com.example.ReleaseListener",
                                                                "onRelease",
                                                                "outbound",
                                                                20,
                                                                0)))
                        .has("event_deliveries"));
    }
}
