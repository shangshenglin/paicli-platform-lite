package com.paicli.platform.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "paicli.maintenance")
public record MaintenanceProperties(long intervalMillis, int eventRetentionDays,
                                    int auditRetentionDays, int orphanFileGraceHours,
                                    boolean vacuumEnabled) {
    public MaintenanceProperties {
        if (intervalMillis < 0 || eventRetentionDays < 0 || auditRetentionDays < 0
                || orphanFileGraceHours < 0) {
            throw new IllegalArgumentException("maintenance intervals and retention values must not be negative");
        }
        intervalMillis = intervalMillis == 0 ? 3_600_000 : Math.max(intervalMillis, 60_000);
        orphanFileGraceHours = orphanFileGraceHours == 0 ? 24 : orphanFileGraceHours;
    }
}
