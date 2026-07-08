package com.paicli.platform.server.model;

import java.util.Map;

public record ModelToolDefinition(String name, String description, Map<String, Object> parameters) {
    public ModelToolDefinition {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}

