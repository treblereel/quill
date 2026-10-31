package org.treblereel.mcp.db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import org.treblereel.mcp.core.ConfigurationScanner;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.core.KotlinSourceDebug;
import org.treblereel.mcp.model.*;

class IndexSnapshotTest {
    @TempDir Path temp;

    @Test void freshAndDeltaIncludeSupplementaryEvidenceAndPreserveExplicitIds() {
        var fresh = QuillDatabase.createForBulkLoad(temp.resolve("fresh.db"));
        var delta = QuillDatabase.create(temp.resolve("delta.db"));
        var snapshot = snapshot(true, 77);
        IndexWriter.writeFresh(fresh, snapshot);
        IndexWriter.writeIncremental(delta, snapshot);
        for (var db : List.of(fresh, delta)) {
            assertEquals(77, db.withHandle(repositoryHandle1 -> new SymbolRepository(repositoryHandle1).findAllClasses()).getFirst().id());
            assertEquals(1, db.withHandle(repositoryHandle2 -> new SymbolRepository(repositoryHandle2).findKotlinDeclarations(77)).size());
            assertTrue(db.withHandle(repositoryHandle3 -> new SymbolRepository(repositoryHandle3).findKotlinSourceDebug(77)).isPresent());
            assertEquals(1, db.withHandle(repositoryHandle4 -> new IndexRepository(repositoryHandle4).findAllModuleClasspath()).size());
            assertEquals(1, db.withHandle(repositoryHandle5 -> new BeanRepository(repositoryHandle5).findExternalBeans(Map.of())).size());
        }
        var unchanged = IndexWriter.writeIncremental(delta, snapshot);
        assertEquals(0, unchanged.rowsInserted());
        assertEquals(0, unchanged.rowsDeleted());
        IndexWriter.writeIncremental(delta, snapshot(false, 77));
        assertTrue(delta.withHandle(repositoryHandle6 -> new SymbolRepository(repositoryHandle6).findKotlinDeclarations(77)).isEmpty());
        assertTrue(delta.withHandle(repositoryHandle7 -> new SymbolRepository(repositoryHandle7).findKotlinSourceDebug(77)).isEmpty());
        assertTrue(delta.withHandle(repositoryHandle8 -> new IndexRepository(repositoryHandle8).findAllModuleClasspath()).isEmpty());
        assertTrue(delta.withHandle(repositoryHandle9 -> new BeanRepository(repositoryHandle9).findExternalBeans(Map.of())).isEmpty());
    }

    @Test void failureInSupplementaryEvidenceRollsBackEntireSnapshotAndRestoresForeignKeys() {
        var db = QuillDatabase.create(temp.resolve("rollback.db"));
        IndexWriter.writeFresh(db, snapshot(true, 77));
        // Kotlin evidence references an absent owner after the main rows have been staged.
        assertThrows(RuntimeException.class, () -> IndexWriter.writeIncremental(db, snapshot(true, 88)));
        assertEquals(77, db.withHandle(repositoryHandle10 -> new SymbolRepository(repositoryHandle10).findAllClasses()).getFirst().id());
        assertEquals(1, db.withHandle(repositoryHandle11 -> new SymbolRepository(repositoryHandle11).findKotlinDeclarations(77)).size());
        assertEquals(1, db.withHandle(h -> h.createQuery("PRAGMA foreign_keys").mapTo(Integer.class).one()).intValue());
        var fresh = QuillDatabase.createForBulkLoad(temp.resolve("failed-fresh.db"));
        assertThrows(RuntimeException.class, () -> IndexWriter.writeFresh(fresh, snapshot(true, 88)));
        assertTrue(fresh.withHandle(repositoryHandle12 -> new SymbolRepository(repositoryHandle12).findAllClasses()).isEmpty());
    }

    @Test void storageFailuresAreNotReportedAsMissingEvidence() {
        var db = QuillDatabase.create(temp.resolve("broken.db"));
        db.useHandle(h -> {
            h.execute("DROP TABLE cdi_problems");
            h.execute("DROP TABLE class_external_deps");
            h.execute("DROP TABLE git_commit_files");
            h.execute("DROP TABLE git_commits");
        });
        assertThrows(RuntimeException.class, () -> db.withHandle(repositoryHandle13 -> new BeanRepository(repositoryHandle13).findCdiProblems()));
        assertThrows(RuntimeException.class, () -> db.withHandle(repositoryHandle14 -> new UsageRepository(repositoryHandle14).hasExternalDeps()));
        assertThrows(RuntimeException.class, () -> db.withHandle(repositoryHandle15 -> new GitRepository(repositoryHandle15).hasGitData()));
    }

    @Test void writesOwnTheirTransactionAndTemporaryTables() {
        var db = QuillDatabase.create(temp.resolve("owned-transaction.db"));
        db.useHandle(ambient -> {
            ambient.begin();
            try {
                IndexWriter.writeIncremental(db, snapshot(true, 77));
                IndexWriter.writeIncremental(db, snapshot(false, 77));
            } finally { ambient.rollback(); }
        });
        ReadQueryContext.read(db, context -> {
            assertEquals(77, context.symbols.findAllClasses().getFirst().id());
            assertTrue(context.symbols.findKotlinDeclarations(77).isEmpty());
            return null;
        });
    }

    private IndexSnapshot snapshot(boolean evidence, int owner) {
        return new IndexSnapshot(
                List.of(new ClassRecord(owner, "acme.Owner", "CLASS", null, List.of(),
                        "Owner.kt", 1, false, 1)),
                List.of(), List.of(), List.of(), Map.of("test", "complete"),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), null, List.of(), Map.of(),
                evidence ? List.of(new ExternalBeanRecord(-1, "dep.Bean", "CLASS", null,
                        List.of(), List.of(), false, false, null, List.of(), null,
                        List.of("dep.Bean"), "CDI", "dep", "dep.jar", List.of())) : List.of(),
                evidence ? List.of(new ModuleClasspathRecord("app", "app", 0, "self")) : List.of(),
                evidence ? List.of(new KotlinDeclarationRecord(77, "FUNCTION", "run", "run",
                        "()V", "kotlin_metadata", false, false, false, false, false, false, false)) : List.of(),
                evidence ? Map.of(77, new KotlinSourceDebug.Info("acme.Owner", List.of(), false, null)) : Map.of());
    }
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

        IndexSnapshot snapshot = new IndexFixture()
                .classes(classes)
                .metadata(metadata)
                .configuration(new ConfigurationScanner.Result(definitions, List.of())).snapshot();
        classes.clear();
        metadata.clear();
        definitions.clear();

        assertEquals(1, snapshot.classes().size());
        assertEquals("one", snapshot.metadata().get("index_id"));
        assertEquals(1, snapshot.configuration().definitions().size());
    }
}
