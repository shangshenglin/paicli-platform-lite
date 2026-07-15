package com.paicli.platform.server.store;

import com.paicli.platform.server.config.MaintenanceProperties;
import com.paicli.platform.server.config.PlatformProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;

@Service
public class SqliteMaintenanceService {
    private static final Logger log = LoggerFactory.getLogger(SqliteMaintenanceService.class);
    private final SqliteConnectionFactory connections;
    private final MaintenanceProperties properties;
    private final Path dataRoot;

    public SqliteMaintenanceService(PlatformProperties platform, MaintenanceProperties properties) {
        this.dataRoot = platform.dataDir().toAbsolutePath().normalize();
        this.connections = new SqliteConnectionFactory(dataRoot.resolve("paicli.db"));
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${paicli.maintenance.interval-millis:3600000}")
    public void maintain() {
        try (Connection connection = connections.open()) {
            int deletedEvents = pruneEvents(connection);
            checkpoint(connection);
            if (properties.vacuumEnabled()) {
                try (Statement statement = connection.createStatement()) { statement.execute("VACUUM"); }
            }
            int deletedFiles = pruneFiles(connection);
            if (deletedEvents > 0 || deletedFiles > 0) {
                log.info("Runtime maintenance deleted {} expired events and {} orphan/expired files",
                        deletedEvents, deletedFiles);
            }
        } catch (Exception e) {
            log.warn("Runtime maintenance failed: {}", e.getMessage());
        }
    }

    private int pruneEvents(Connection connection) throws Exception {
        if (properties.eventRetentionDays() == 0) return 0;
        String cutoff = Instant.now().minus(properties.eventRetentionDays(), ChronoUnit.DAYS).toString();
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM run_events WHERE created_at<? AND run_id IN "
                        + "(SELECT id FROM runs WHERE status IN ('COMPLETED','FAILED','CANCELED'))")) {
            statement.setString(1, cutoff);
            return statement.executeUpdate();
        }
    }

    private static void checkpoint(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(PASSIVE)");
        }
    }

    private int pruneFiles(Connection connection) throws Exception {
        Instant orphanCutoff = Instant.now().minus(properties.orphanFileGraceHours(), ChronoUnit.HOURS);
        int deleted = pruneTemporaryFiles(dataRoot, orphanCutoff);
        deleted += pruneAuditFiles();
        Path artifacts = dataRoot.resolve("artifacts");
        if (!Files.isDirectory(artifacts)) return deleted;
        Set<String> runIds = runIds(connection);
        try (var directories = Files.list(artifacts)) {
            for (Path directory : directories.filter(Files::isDirectory).toList()) {
                if (!runIds.contains(directory.getFileName().toString())
                        && Files.getLastModifiedTime(directory).toInstant().isBefore(orphanCutoff)) {
                    deleteTree(directory);
                    deleted++;
                }
            }
        }
        return deleted;
    }

    private int pruneAuditFiles() throws Exception {
        if (properties.auditRetentionDays() == 0) return 0;
        Instant cutoff = Instant.now().minus(properties.auditRetentionDays(), ChronoUnit.DAYS);
        Path audit = dataRoot.resolve("audit");
        if (!Files.isDirectory(audit)) return 0;
        int deleted = 0;
        try (var files = Files.list(audit)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Instant modified = Files.getLastModifiedTime(file).toInstant();
                if (modified.isBefore(cutoff) && Files.deleteIfExists(file)) deleted++;
            }
        }
        return deleted;
    }

    private static int pruneTemporaryFiles(Path root, Instant cutoff) throws Exception {
        if (!Files.isDirectory(root)) return 0;
        int deleted = 0;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".tmp")).toList()) {
                FileTime modified = Files.getLastModifiedTime(path);
                if (modified.toInstant().isBefore(cutoff) && Files.deleteIfExists(path)) deleted++;
            }
        }
        return deleted;
    }

    private static Set<String> runIds(Connection connection) throws Exception {
        Set<String> ids = new HashSet<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT id FROM runs")) {
            while (result.next()) ids.add(result.getString(1));
        }
        return ids;
    }

    private static void deleteTree(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
