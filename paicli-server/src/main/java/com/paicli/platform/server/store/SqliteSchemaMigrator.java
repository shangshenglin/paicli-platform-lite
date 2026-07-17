package com.paicli.platform.server.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

final class SqliteSchemaMigrator {
    private static final List<Migration> MIGRATIONS = List.of(
            new Migration(1, "baseline runtime schema"),
            new Migration(2, "durable reasoning and message archive columns"),
            new Migration(3, "per-run thinking controls"),
            new Migration(4, "session groups and session deletion"),
            new Migration(5, "durable multi-agent run delegations"),
            new Migration(6, "fair run queue ordering for delegated runs"),
            new Migration(7, "durable multimodal input attachments"),
            new Migration(8, "automatic layered memory extraction and revision history"),
            new Migration(9, "durable per-turn model usage governance"),
            new Migration(10, "business productivity workbench and approval policies"),
            new Migration(11, "long-term productivity templates profiles budgets schedules and queue"),
            new Migration(12, "agent evaluation suites cases executions trials and baselines"),
            new Migration(13, "production run state tool recovery model attempts budgets and notification outbox"),
            new Migration(14, "evaluation output token metric and concurrent SQLite hardening")
    );

    private SqliteSchemaMigrator() { }

    static void ensureColumn(Connection connection, String table, String column, String definition)
            throws SQLException {
        boolean exists = false;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                if (column.equalsIgnoreCase(result.getString("name"))) {
                    exists = true;
                    break;
                }
            }
        }
        if (!exists) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            }
        }
    }

    static void recordAppliedVersions(Connection connection) throws SQLException {
        for (Migration migration : MIGRATIONS) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT OR IGNORE INTO schema_migrations(version,description,applied_at) VALUES(?,?,?)")) {
                statement.setInt(1, migration.version());
                statement.setString(2, migration.description());
                statement.setString(3, Instant.now().toString());
                statement.executeUpdate();
            }
        }
    }

    private record Migration(int version, String description) { }
}
