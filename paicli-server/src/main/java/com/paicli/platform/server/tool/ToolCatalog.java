package com.paicli.platform.server.tool;

import com.paicli.platform.server.model.ModelToolDefinition;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Component
public class ToolCatalog {
    private final List<ServerToolProvider> providers;

    @Autowired
    public ToolCatalog(List<ServerToolProvider> providers) {
        this.providers = providers.stream()
                .sorted(Comparator.comparing(ServerToolProvider::id))
                .toList();
    }

    public ToolCatalog() {
        this(List.of());
    }

    public List<ModelToolDefinition> definitions() {
        return definitions(Set.of());
    }

    public List<ModelToolDefinition> definitions(Set<String> allowedNames) {
        Set<String> allow = allowedNames == null ? Set.of() : allowedNames;
        List<ModelToolDefinition> definitions = new ArrayList<>(List.of(
                tool("list_dir", "List files under a workspace directory", Map.of(
                        "type", "object", "properties", Map.of("path", stringProperty()), "required", List.of("path"))),
                tool("read_file", "Read a UTF-8 workspace file", Map.of(
                        "type", "object", "properties", Map.of("path", stringProperty()), "required", List.of("path"))),
                tool("write_file", "Write a UTF-8 workspace file; requires approval. Use this for file writes instead of execute_command; parent directories are handled by the file tool when possible.", Map.of(
                        "type", "object", "properties", Map.of(
                                "path", stringProperty(), "content", stringProperty()),
                        "required", List.of("path", "content"))),
                tool("execute_command", "Execute a shell command in the workspace; requires approval. Do not use this as a fallback for ordinary file writes when write_file can satisfy the request.", Map.of(
                        "type", "object", "properties", Map.of(
                                "command", stringProperty(), "cwd", stringProperty()),
                        "required", List.of("command"))),
                tool("read_artifact", "Read a character range from an externalized tool result", Map.of(
                        "type", "object", "properties", Map.of(
                                "artifact_id", stringProperty(),
                                "offset", Map.of("type", "integer", "minimum", 0),
                                "limit", Map.of("type", "integer", "minimum", 1, "maximum", 32000)),
                        "required", List.of("artifact_id")))
        ));
        if (!allow.isEmpty()) definitions.removeIf(definition -> !allow.contains(definition.name()));
        Set<String> names = new HashSet<>();
        definitions.forEach(definition -> names.add(definition.name()));
        for (ServerToolProvider provider : providers) {
            for (ModelToolDefinition definition : provider.definitions()) {
                if (!allow.isEmpty() && !allow.contains(definition.name())) continue;
                if (!names.add(definition.name())) {
                    throw new IllegalStateException("Duplicate tool definition: " + definition.name());
                }
                definitions.add(definition);
            }
        }
        return List.copyOf(definitions);
    }

    private static ModelToolDefinition tool(String name, String description, Map<String, Object> parameters) {
        return new ModelToolDefinition(name, description, parameters);
    }

    private static Map<String, Object> stringProperty() {
        return Map.of("type", "string");
    }
}
