package com.paicli.platform.server.store;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

final class SqliteConnectionFactory {
    private static final int BUSY_TIMEOUT_MS = 30_000;
    private final String jdbcUrl;

    SqliteConnectionFactory(Path databasePath) {
        this.jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath().normalize();
    }

    void initialize() throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=" + BUSY_TIMEOUT_MS);
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA wal_autocheckpoint=1000");
            statement.execute("PRAGMA foreign_keys=ON");
        }
    }

    Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=" + BUSY_TIMEOUT_MS);
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA wal_autocheckpoint=1000");
            statement.execute("PRAGMA foreign_keys=ON");
        }
        return connection;
    }
}
