package org.treblereel.mcp.db;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.treblereel.mcp.core.ConfigurationScanner;
import org.treblereel.mcp.model.ClassRecord;

class IndexSnapshotTest {

    @Test
    void ownsImmutableCopiesOfPublishedState() {
        List<ClassRecord> classes = new ArrayList<>();
        classes.add(new ClassRecord(1, "example.Service", "CLASS", "java.lang.Object",
                List.of(), "src/main/java/example/Service.java", 1, false, 10));
        Map<String, String> metadata = new HashMap<>(Map.of("index_id", "one"));
        List<ConfigurationScanner.Definition> definitions = new ArrayList<>();
        definitions.add(new ConfigurationScanner.Definition(
                "app.name", "property", "src/main/resources/application.properties",
                1, ".", "main"));

        IndexSnapshot snapshot = new IndexSnapshot(classes, List.of(), List.of(), List.of(),
                metadata, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                new ConfigurationScanner.Result(definitions, List.of()));
        classes.clear();
        metadata.clear();
        definitions.clear();

        assertEquals(1, snapshot.classes().size());
        assertEquals("one", snapshot.metadata().get("index_id"));
        assertEquals(1, snapshot.configuration().definitions().size());
    }
}
