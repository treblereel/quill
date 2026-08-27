package org.treblereel.mcp.db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class JokerDatabase {

    private JokerDatabase() {}

    public static Connection create(Path dbPath) {
        try {
            Files.createDirectories(dbPath.getParent());
            Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("PRAGMA journal_mode=WAL");
                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS classes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        class_name TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        superclass TEXT,
                        interfaces TEXT,
                        source_file TEXT,
                        source_line INTEGER,
                        is_bean INTEGER NOT NULL DEFAULT 0,
                        source_tokens INTEGER NOT NULL DEFAULT 0
                    )""");
                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS beans (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        class_id INTEGER NOT NULL REFERENCES classes(id),
                        kind TEXT NOT NULL,
                        scope TEXT,
                        qualifiers TEXT,
                        stereotypes TEXT,
                        is_alternative INTEGER NOT NULL DEFAULT 0,
                        priority INTEGER,
                        profiles TEXT,
                        declaring_class_id INTEGER REFERENCES classes(id),
                        member_name TEXT,
                        bean_types TEXT
                    )""");
                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS injection_points (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        bean_id INTEGER NOT NULL REFERENCES beans(id),
                        kind TEXT NOT NULL,
                        target_type TEXT NOT NULL,
                        qualifiers TEXT,
                        field_name TEXT,
                        resolved_bean_id INTEGER REFERENCES beans(id),
                        is_ambiguous INTEGER NOT NULL DEFAULT 0
                    )""");
                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS dependencies (
                        from_class_id INTEGER NOT NULL REFERENCES classes(id),
                        to_class_id INTEGER NOT NULL REFERENCES classes(id),
                        kind TEXT NOT NULL,
                        injection_point_id INTEGER REFERENCES injection_points(id)
                    )""");
                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS metadata (
                        key TEXT PRIMARY KEY,
                        value TEXT
                    )""");
                stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_classes_name ON classes(class_name)");
                stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_ip_bean ON injection_points(bean_id)");
                stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_ip_resolved ON injection_points(resolved_bean_id)");
                stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_dep_from ON dependencies(from_class_id)");
                stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_dep_to ON dependencies(to_class_id)");
            }
            return conn;
        } catch (SQLException | IOException e) {
            throw new RuntimeException("Failed to create database at " + dbPath, e);
        }
    }

    public static Connection open(Path dbPath) {
        if (!Files.exists(dbPath)) {
            throw new IllegalStateException(
                    "Index not found at " + dbPath + ". Run 'joker init' first.");
        }
        try {
            return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to open database at " + dbPath, e);
        }
    }
}
