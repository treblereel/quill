package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.*;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.DependencyRecord;

class ArchitectureSourceSetsTest {
    @TempDir Path root;

    @Test void excludesBothTestEndpointsAndKeepsProductionAndCustomSourceSets() throws Exception {
        var db = QuillDatabase.createForBulkLoad(root.resolve("index.db"));
        var classes = List.of(cls(1, "main", "improvement-jpa"), cls(2, "integration-test", "improvement-jpa"),
                cls(3, "test", "improvement-jpa"), cls(4, "integrationTest", "improvement-jpa"),
                cls(5, null, "improvement-jpa"), cls(6, "analytics", "improvement-jpa"),
                cls(7, "main", "restate/jpa"), cls(8, "integration-test", "restate/jpa"));
        var dependencies = List.of(new DependencyRecord(1, 7, "CALLS", null, 1, List.of(10)),
                new DependencyRecord(2, 7, "CALLS", null, 32, List.of(20)),
                new DependencyRecord(3, 7, "CALLS", null, 3, List.of(30)),
                new DependencyRecord(4, 7, "CALLS", null, 4, List.of(40)),
                new DependencyRecord(5, 7, "CALLS", null, 5, List.of(50)),
                new DependencyRecord(6, 7, "CALLS", null, 6, List.of(60)),
                new DependencyRecord(1, 8, "CALLS", null, 8, List.of(70)));
        IndexWriter.writeFresh(db, new IndexFixture().classes(classes).dependencies(dependencies).snapshot());
        ReadQueryContext.readWithGraph(db, reader -> {
            assertEquals(3, reader.usages.findCurrentDependencyGraph(null, false, false).size());
            assertEquals(7, reader.usages.findCurrentDependencyGraph(null, false, true).size());
            try {
                var queries = new ArchitectureViolationQueries();
                var json = new ObjectMapper();
                var production = json.readTree(queries.findArchitectureViolations(reader, "module", "improvement-jpa",
                        List.of("restate/*"), List.of(), false, false, 40, 0));
                assertEquals(3, production.path("matched_source_classes").asInt());
                assertEquals(1, production.path("matched_forbidden_classes").asInt());
                assertEquals(3, production.path("violating_class_pairs").asInt());
                assertEquals(12, production.path("occurrence_count").asInt());
                var all = json.readTree(queries.findArchitectureViolations(reader, "module", "improvement-jpa",
                        List.of("restate/*"), List.of(), false, true, 40, 0));
                assertEquals(6, all.path("matched_source_classes").asInt());
                assertEquals(7, all.path("violating_class_pairs").asInt());
                assertEquals(59, all.path("occurrence_count").asInt());
            } catch (Exception ex) { throw new RuntimeException(ex); }
            return null;
        });
    }

    private ClassRecord cls(int id, String sourceSet, String module) {
        return new ClassRecord(id, "acme.Source" + id, "CLASS", null, List.of(),
                module + "/src/" + (sourceSet == null ? "main" : sourceSet) + "/java/acme/Source" + id + ".java",
                1, false, 0, null, "source", "current", module, sourceSet);
    }
}
