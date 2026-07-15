package com.paicli.platform.server.store;

import com.paicli.platform.server.config.MaintenanceProperties;
import com.paicli.platform.server.config.PlatformProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteMaintenanceServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void prunesOnlyExpiredTerminalEventsAndOldOrphanFiles() throws Exception {
        PlatformProperties platform = new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore store = new SqliteRuntimeStore(platform);
        store.initialize();
        var session = store.createSession("maintenance");
        var run = store.createRun(session.id(), "hello");
        store.completeRun(run.id());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("paicli.db"));
             var statement = connection.prepareStatement(
                     "UPDATE run_events SET created_at=? WHERE event_type='run.queued'")) {
            statement.setString(1, Instant.now().minus(60, ChronoUnit.DAYS).toString());
            statement.executeUpdate();
        }
        Path orphan = Files.createDirectories(tempDir.resolve("artifacts/orphan-run"));
        Files.writeString(orphan.resolve("result.txt"), "orphan");
        Files.setLastModifiedTime(orphan, FileTime.from(Instant.now().minus(3, ChronoUnit.HOURS)));

        SqliteMaintenanceService service = new SqliteMaintenanceService(platform,
                new MaintenanceProperties(60_000, 30, 0, 1, false));
        service.maintain();

        assertThat(store.events(run.id(), 0)).extracting("type").containsExactly("run.completed");
        assertThat(orphan).doesNotExist();
    }
}
