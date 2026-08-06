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
    private static final Set<String> CORE_CONTEXT_TOOLS = Set.of(
            "list_dir", "read_file", "write_file", "execute_command", "read_artifact", "tool_search",
            "update_working_plan");
    /** Web-provider tools that become directly visible once {@code paicli.web.enabled=true}. */
    private static final Set<String> WEB_PROVIDER_TOOLS = Set.of(
            "web_search", "web_fetch", "github_repo_fetch");
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
        return allDefinitions(allowedNames);
    }

    public List<ModelToolDefinition> definitionsForContext(Set<String> allowedNames, Set<String> activatedNames) {
        Set<String> allow = allowedNames == null ? Set.of() : allowedNames;
        Set<String> activated = activatedNames == null ? Set.of() : activatedNames;
        if (!allow.isEmpty()) {
            Set<String> expanded = new HashSet<>(allow);
            expanded.add("tool_search");
            return allDefinitions(expanded);
        }
        List<ModelToolDefinition> all = allDefinitions(Set.of());
        Set<String> visible = new HashSet<>(CORE_CONTEXT_TOOLS);
        all.stream().map(ModelToolDefinition::name).filter(WEB_PROVIDER_TOOLS::contains).forEach(visible::add);
        return all.stream().filter(definition -> visible.contains(definition.name())
                || activated.contains(definition.name())).toList();
    }

    public List<ToolDirectoryEntry> search(String query, int requestedLimit, Set<String> allowedNames) {
        String value = query == null ? "" : query.trim().toLowerCase();
        List<String> terms = java.util.Arrays.stream(value.split("[^\\p{L}\\p{N}_-]+"))
                .filter(term -> !term.isBlank()).distinct().toList();
        int limit = Math.max(1, Math.min(requestedLimit, 12));
        return allDefinitions(allowedNames).stream()
                .filter(definition -> !CORE_CONTEXT_TOOLS.contains(definition.name()))
                .filter(definition -> terms.isEmpty() || terms.stream().anyMatch(
                        term -> (definition.name() + " " + definition.description())
                                .toLowerCase().contains(term)))
                .sorted(Comparator.comparing(ModelToolDefinition::name))
                .limit(limit)
                .map(definition -> new ToolDirectoryEntry(
                        definition.name(), definition.description(), providerId(definition.name())))
                .toList();
    }

    private List<ModelToolDefinition> allDefinitions(Set<String> allowedNames) {
        Set<String> allow = allowedNames == null ? Set.of() : allowedNames;
        List<ModelToolDefinition> definitions = new ArrayList<>(List.of(
                tool("list_dir", "List files under a workspace directory", Map.of(
                        "type", "object", "properties", Map.of("path", stringProperty()), "required", List.of("path"))),
                tool("read_file", "Read a UTF-8 workspace file", Map.of(
                        "type", "object", "properties", Map.of("path", stringProperty()), "required", List.of("path"))),
                tool("write_file", "Write a UTF-8 workspace file. Use this for file writes instead of execute_command; parent directories are handled by the file tool when possible.", Map.of(
                        "type", "object", "properties", Map.of(
                                "path", stringProperty(), "content", stringProperty()),
                        "required", List.of("path", "content"))),
                tool("execute_command", "Execute a shell command in the workspace. Read, build, and test commands run directly; destructive, privileged, remote, install, publish, and deployment commands require approval. Do not use this as a fallback for ordinary file writes when write_file can satisfy the request.", Map.of(
                        "type", "object", "properties", Map.of(
                                "command", stringProperty(),
                                "shell", Map.of("type", "string",
                                        "enum", List.of("sh", "bash", "powershell"),
                                        "description", "Whitelisted shell; defaults to this Run's execution environment"),
                                "cwd", stringProperty(),
                                "timeoutSeconds", Map.of("type", "integer", "minimum", 1, "maximum", 90),
                                "maxOutputBytes", Map.of("type", "integer", "minimum", 1024,
                                        "maximum", 4 * 1024 * 1024),
                                "env", Map.of("type", "object",
                                        "additionalProperties", Map.of("type", "string"),
                                        "description", "Non-sensitive explicit environment variables")),
                        "required", List.of("command"))),
                tool("read_artifact", "Read a character range from an externalized tool result", Map.of(
                        "type", "object", "properties", Map.of(
                                "artifact_id", stringProperty(),
                                "offset", Map.of("type", "integer", "minimum", 0),
                                "limit", Map.of("type", "integer", "minimum", 1, "maximum", 32000)),
                        "required", List.of("artifact_id"))),
                tool("tool_search", "Search the deferred tool directory. Matching tools are activated with full schemas on the next model turn.", Map.of(
                        "type", "object", "properties", Map.of(
                                "query", Map.of("type", "string",
                                        "description", "Capability, tool name, or task keyword"),
                                "limit", Map.of("type", "integer", "minimum", 1, "maximum", 12)),
                        "required", List.of("query")))
        ));
        if (!allow.isEmpty()) definitions.removeIf(definition -> !allowed(allow, definition.name()));
        Set<String> names = new HashSet<>();
        definitions.forEach(definition -> names.add(definition.name()));
        for (ServerToolProvider provider : providers) {
            for (ModelToolDefinition definition : provider.definitions()) {
                if (!allow.isEmpty() && !allowed(allow, definition.name())) continue;
                if (!names.add(definition.name())) {
                    throw new IllegalStateException("Duplicate tool definition: " + definition.name());
                }
                definitions.add(definition);
            }
        }
        return List.copyOf(definitions);
    }

    private String providerId(String toolName) {
        return providers.stream().filter(provider -> provider.supports(toolName))
                .findFirst().map(ServerToolProvider::id).orElse("sandbox");
    }

    private static ModelToolDefinition tool(String name, String description, Map<String, Object> parameters) {
        return new ModelToolDefinition(name, description, parameters);
    }

    private static boolean allowed(Set<String> allowedNames, String name) {
        if (allowedNames.contains(name)) return true;
        return allowedNames.stream()
                .filter(value -> value != null && value.endsWith("*") && value.length() > 1)
                .map(value -> value.substring(0, value.length() - 1))
                .anyMatch(name::startsWith);
    }

    private static Map<String, Object> stringProperty() {
        return Map.of("type", "string");
    }

    public record ToolDirectoryEntry(String name, String description, String provider) { }
}
