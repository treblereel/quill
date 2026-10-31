package org.treblereel.mcp.mcp;

import java.nio.file.Path;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.db.IndexGeneration;

final class ProjectEntryFixture implements org.junit.jupiter.api.extension.AfterEachCallback {
    private final java.util.Queue<IndexGeneration> owners = new java.util.concurrent.ConcurrentLinkedQueue<>();
    @Override public synchronized void afterEach(org.junit.jupiter.api.extension.ExtensionContext context) {
        closed = true;
        IndexGeneration generation;
        while ((generation = owners.poll()) != null) generation.close();
    }
    private boolean closed;
    synchronized ProjectRegistry.ProjectEntry entry(String name, Path root, Jdbi database) {
        String file = database.withHandle(h -> h.createQuery("PRAGMA database_list")
                .mapToMap().list().stream().filter(row -> "main".equals(row.get("name")))
                .map(row -> (String) row.get("file")).findFirst().orElseThrow());
        var generation = IndexGeneration.open(Path.of(file));
        if (closed) generation.close();
        else owners.add(generation);
        return new ProjectRegistry.ProjectEntry(name, root, generation);
    }
}
