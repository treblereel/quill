package org.treblereel.mcp.db;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Pins each optional filter of the external-bean and dependency-graph queries. */
class FilteredRepositoryQueriesTest {
    @TempDir Path temp;
    private Jdbi db;

    @BeforeEach
    void seed() {
        db = QuillDatabase.create(temp.resolve("filters.db"));
        db.useHandle(
                h -> {
                    for (Object[] bean :
                            new Object[][] {
                                {1, "org.acme.Greeter", "CLASS", "@ApplicationScoped"},
                                {2, "org.acme.Outer$Greeter", "CLASS", "@Singleton"},
                                {
                                    3,
                                    "org.acme.LoudGreeter",
                                    "PRODUCER_METHOD",
                                    "@ApplicationScoped"
                                },
                                {4, "org.other.Clock", "CLASS", "@Dependent"}
                            })
                        h.createUpdate(
                                        """
                        INSERT INTO external_beans (id, class_name, kind, scope, qualifiers, stereotypes, profiles,
                          bean_types, framework, injection_points) VALUES (:id, :name, :kind, :scope, '[]', '[]', '[]', '[]', 'cdi', '[]')""")
                                .bind("id", bean[0])
                                .bind("name", bean[1])
                                .bind("kind", bean[2])
                                .bind("scope", bean[3])
                                .execute();
                    for (Object[] cls :
                            new Object[][] {
                                {1, "a.Main", "source", "app", "main"},
                                {2, "a.Other", "source", "app", "main"},
                                {3, "a.MainTest", "source", "app", "test"},
                                {4, "a.Gen", "generated", "app", "main"},
                                {5, "b.Lib", "source", "lib", "main"}
                            })
                        h.createUpdate(
                                        """
                        INSERT INTO classes (id, class_name, kind, origin, module, source_set)
                        VALUES (:id, :name, 'CLASS', :origin, :module, :set)""")
                                .bind("id", cls[0])
                                .bind("name", cls[1])
                                .bind("origin", cls[2])
                                .bind("module", cls[3])
                                .bind("set", cls[4])
                                .execute();
                    for (int[] edge : new int[][] {{1, 2}, {3, 1}, {1, 4}, {1, 5}, {2, 2}})
                        h.createUpdate(
                                        "INSERT INTO dependencies (from_class_id, to_class_id, kind) VALUES (:from, :to, 'CALL')")
                                .bind("from", edge[0])
                                .bind("to", edge[1])
                                .execute();
                });
    }

    private List<String> external(String... pairs) {
        Map<String, String> filter = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) filter.put(pairs[i], pairs[i + 1]);
        return db
                .withHandle(
                        h ->
                                new BeanRepository(h)
                                        .findExternalBeans(pairs.length == 0 ? null : filter))
                .stream()
                .map(b -> b.className())
                .toList();
    }

    @Test
    void externalBeansFilterByEachNameModeScopeAndKind() {
        assertEquals(
                List.of(
                        "org.acme.Greeter",
                        "org.acme.LoudGreeter",
                        "org.acme.Outer$Greeter",
                        "org.other.Clock"),
                external());
        assertEquals(
                List.of("org.acme.Greeter", "org.acme.Outer$Greeter"),
                external("class_name", "Greeter"));
        assertEquals(List.of("org.acme.Greeter"), external("class_name", "org.acme.Greeter"));
        assertEquals(
                List.of("org.acme.Greeter", "org.acme.LoudGreeter", "org.acme.Outer$Greeter"),
                external("class_name", "org.acme.*"));
        assertEquals(
                List.of("org.acme.Greeter", "org.acme.LoudGreeter"),
                external("scope", "@ApplicationScoped"));
        assertEquals(
                List.of("org.acme.LoudGreeter"),
                external("class_name", "*Greeter", "kind", "PRODUCER_METHOD"));
    }

    private List<String> graph(String module, boolean generated, boolean tests) {
        return db
                .withHandle(
                        h ->
                                new UsageRepository(h)
                                        .findCurrentDependencyGraph(module, generated, tests))
                .stream()
                .map(d -> d.fromClassId() + ">" + d.toClassId())
                .toList();
    }

    @Test
    void dependencyGraphFiltersModuleGeneratedAndTests() {
        assertEquals(List.of("1>2", "1>4", "1>5", "3>1"), graph(null, true, true));
        assertEquals(List.of("1>2", "1>5"), graph(null, false, false));
        assertEquals(List.of("1>2", "1>4"), graph("app", true, false));
        assertEquals(List.of("1>2", "1>4", "1>5", "3>1"), graph(" ", true, true));
    }
}
