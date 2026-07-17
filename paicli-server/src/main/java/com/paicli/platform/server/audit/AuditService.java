package com.paicli.platform.server.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.config.PlatformProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.lang.reflect.Array;

@Service
public class AuditService {
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final int MAX_VALUE_CHARS = 4_000;
    private final ObjectMapper mapper;
    private final Path auditDirectory;
    private final Object lock = new Object();

    public AuditService(ObjectMapper mapper, PlatformProperties properties) {
        this.mapper = mapper;
        this.auditDirectory = properties.dataDir().resolve("audit").toAbsolutePath().normalize();
    }

    public void record(String type, String runId, String toolCallId, Map<String, ?> details) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("timestamp", Instant.now().toString());
        event.put("type", type);
        event.put("runId", runId);
        event.put("toolCallId", toolCallId);
        event.put("details", sanitize(details));
        try {
            synchronized (lock) {
                Files.createDirectories(auditDirectory);
                Files.writeString(todayFile(), mapper.writeValueAsString(event) + System.lineSeparator(),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (Exception e) {
            log.warn("Failed to write audit event {} for run {}: {}", type, runId, e.getMessage());
        }
    }

    public Path auditDirectory() {
        return auditDirectory;
    }

    private Path todayFile() {
        return auditDirectory.resolve("audit-" + LocalDate.now() + ".jsonl");
    }

    private static Map<String, Object> sanitize(Map<String, ?> source) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        if (source == null) return sanitized;
        source.forEach((key, value) -> {
            String normalizedKey = key == null ? "" : key;
            sanitized.put(normalizedKey, sanitizeValue(normalizedKey, value));
        });
        return sanitized;
    }

    private static Object sanitizeValue(String key, Object value) {
        if (key != null && key.matches("(?i).*(token|secret|password|authorization|api.?key|credential).*")) {
            return "***";
        }
        if (value == null || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((nestedKey, nestedValue) -> {
                String name = String.valueOf(nestedKey);
                nested.put(name, sanitizeValue(name, nestedValue));
            });
            return nested;
        }
        if (value instanceof Iterable<?> iterable) {
            java.util.ArrayList<Object> values = new java.util.ArrayList<>();
            iterable.forEach(item -> values.add(sanitizeValue("", item)));
            return List.copyOf(values);
        }
        if (value.getClass().isArray()) {
            java.util.ArrayList<Object> values = new java.util.ArrayList<>();
            for (int index = 0; index < Array.getLength(value); index++) {
                values.add(sanitizeValue("", Array.get(value, index)));
            }
            return List.copyOf(values);
        }
        String text = String.valueOf(value)
                .replaceAll("(?i)Bearer\\s+[^\\s\"'}]+", "Bearer ***")
                .replaceAll("(?i)(password|token|secret|api[_-]?key)(\\s*[=:]\\s*)[^,;\\s}\"]+", "$1$2***");
        return text.length() <= MAX_VALUE_CHARS
                ? text : text.substring(0, MAX_VALUE_CHARS) + "...[truncated]";
    }
}
