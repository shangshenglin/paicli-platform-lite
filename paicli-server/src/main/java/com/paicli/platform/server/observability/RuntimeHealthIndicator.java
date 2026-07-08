package com.paicli.platform.server.observability;

import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.nio.file.Files;

@Component("runtimeStorage")
public class RuntimeHealthIndicator implements HealthIndicator {
    private final SqliteRuntimeStore store;

    public RuntimeHealthIndicator(SqliteRuntimeStore store) { this.store = store; }

    @Override
    public Health health() {
        try {
            var path = store.databasePath();
            long usable = Files.getFileStore(path.getParent()).getUsableSpace();
            Health.Builder builder = usable < 50L * 1024 * 1024 ? Health.down() : Health.up();
            return builder.withDetail("database", path.toString())
                    .withDetail("databaseReadable", Files.isReadable(path))
                    .withDetail("usableDiskBytes", usable).build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
