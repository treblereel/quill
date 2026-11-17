package org.treblereel.mcp.mcp.prefetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.mcp.JavaEvidenceFixture;
import org.treblereel.mcp.mcp.TestReads;

/**
 * Apollo AQ-045 and Cryostat CQ-045: asked what moving a bean to request scope changes outside an
 * HTTP request, Codex walked the callers with find_usages and a transitive call hierarchy (about
 * 180 KB of results); nothing said which entry points reach the class.
 */
class EntryContextsTest {
    @TempDir Path root;

    @Test
    void callersAreTracedToHttpHandlersExpressionsNonRequestEntriesAndUncalledRoots() throws Exception {
        Jdbi db = index();
        var lines = TestReads.read(db, reader -> EntryContexts.of(reader, reader.symbols.findClassByName("p.Target").orElseThrow()));
        String text = lines.toString();
        // Facade calls Target through its interface; Web#get reaches it through Facade.
        assertTrue(text.contains("HTTP handlers: 1 (Web#get)"), text);
        assertTrue(text.contains("annotation expressions: Gate#can"), text);
        assertTrue(text.contains("non-request entries: Listener#onEvent @EventListener"), text);
        assertTrue(text.contains("no indexed caller: Orphan#run2"), text);
        assertEquals(4, lines.size(), text);
    }

    @Test
    void aSubclassConstructorIsNotACallerOfItsSupertype() throws Exception {
        Jdbi db = index();
        var lines = TestReads.read(db, reader -> EntryContexts.of(reader, reader.symbols.findClassByName("p.Leaf").orElseThrow()));
        String text = String.join("\n", lines.valueStream().map(line -> line.asText()).toList());
        assertTrue(text.contains("UsesLeaf#go") && !text.contains("Sibling#<init>"), text);
    }

    @Test
    void theFieldIsComputedOnlyForQuestionsAboutScopeOrCallContext() {
        assertTrue(QuestionFocus.fields("Что изменится, если перевести X в request scope? Проверь контекст вызова вне HTTP-запроса.").contains("entry_contexts"));
        assertTrue(QuestionFocus.fields("Что изменится, если перевести X в @RequestScoped?").contains("entry_contexts"));
        assertTrue(!QuestionFocus.fields("Кто внедряет X?").contains("entry_contexts"));
    }

    private Jdbi index() throws Exception {
        Jdbi db = JavaEvidenceFixture.index(root, "package p; interface Check { boolean ok(); } "
                + "class Target implements Check { public boolean ok() { return true; } } "
                + "class Facade { Check delegate; boolean allowed() { return delegate.ok(); } } "
                + "class Web { Facade f; String get() { return f.allowed() ? \"y\" : \"n\"; } String post() { return \"\"; } } "
                + "class Listener { Target t; void onEvent() { t.ok(); } } "
                + "class Orphan { Target t; void run2() { t.ok(); } } "
                + "class Gate { Target t; boolean can() { return t.ok(); } } "
                + "abstract class Base implements Check {} class Leaf extends Base { public boolean ok() { return false; } } "
                + "class Sibling extends Base { Sibling() { super(); } public boolean ok() { return true; } } class UsesLeaf { Leaf l; void go() { l.ok(); } }");
        db.useHandle(h -> {
            h.execute("UPDATE classes SET source_set = 'main', module = 'app'");
            h.execute("INSERT OR REPLACE INTO metadata(key, value) VALUES ('framework_endpoints_detail', ?)",
                    "[{\"className\":\"p.Web\",\"methodName\":\"get\",\"descriptor\":\"()Ljava/lang/String;\",\"framework\":\"spring\"},"
                            + "{\"className\":\"p.Web\",\"methodName\":\"post\",\"descriptor\":\"()Ljava/lang/String;\",\"framework\":\"spring\"}]");
            h.execute("UPDATE class_members SET annotations = '[\"org.springframework.context.event.EventListener\"]' "
                    + "WHERE name = 'onEvent'");
            h.execute("UPDATE class_members SET annotations = '[\"org.springframework.security.access.prepost.PreAuthorize\"]', "
                    + "annotation_details = ? WHERE name = 'post'",
                    "[{\"annotationName\":\"org.springframework.security.access.prepost.PreAuthorize\",\"targetKind\":\"METHOD\","
                            + "\"values\":{\"value\":\"@gate.can()\"}}]");
            h.execute("INSERT INTO beans(class_id, kind, scope, qualifiers, bean_types, framework) SELECT id, 'CLASS', 'singleton', "
                    + "'[\"@Default\",\"@Qualifier(\\\"gate\\\")\"]', '[\"p.Gate\"]', 'spring' FROM classes WHERE class_name = 'p.Gate'");
        });
        return db;
    }
}
