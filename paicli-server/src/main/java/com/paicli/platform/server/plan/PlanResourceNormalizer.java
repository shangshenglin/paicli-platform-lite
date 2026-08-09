package com.paicli.platform.server.plan;

import java.util.Locale;

/**
 * Produces the canonical resource key used both by persisted plans and the scheduler.
 */
public final class PlanResourceNormalizer {
    private PlanResourceNormalizer() {
    }

    public static String normalize(String value) {
        if (value == null) return "";
        String normalized = value.trim()
                .replace('\\', '/')
                .toLowerCase(Locale.ROOT);
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        return normalized.replaceAll("/{2,}", "/");
    }
}
