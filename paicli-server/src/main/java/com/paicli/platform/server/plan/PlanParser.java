package com.paicli.platform.server.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.store.PlanStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class PlanParser {
    private static final Set<String> TYPES = Set.of("PLANNING", "INFORMATION_GATHERING",
            "TOOL_EXECUTION", "ASYNC_JOB", "USER_APPROVAL", "VALIDATION", "ANALYSIS",
            "SYNTHESIS", "DELEGATION", "FILE_READ", "FILE_WRITE", "COMMAND", "VERIFICATION");
    private static final Set<String> EXECUTION_MODES = Set.of("REACT", "MANUAL", "ASYNC", "NONE");
    private final ObjectMapper mapper;

    public PlanParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ParsedPlan parse(String fallbackObjective, String rawPlanJson) {
        String cleaned = clean(rawPlanJson);
        List<String> errors = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(cleaned);
            if (!root.isObject()) throw new IllegalArgumentException("plan JSON must be an object");
            String objective = value(root, "objective", fallbackObjective, 8_000);
            String summary = value(root, "summary", "", 2_000);
            JsonNode stepsNode = root.path("steps");
            if (!stepsNode.isArray()) errors.add("steps must be an array");
            if (stepsNode.isArray() && (stepsNode.isEmpty() || stepsNode.size() > 50)) {
                errors.add("steps size must be between 1 and 50");
            }

            List<ParsedStep> parsed = new ArrayList<>();
            Map<String, String> sourceToClient = new HashMap<>();
            if (stepsNode.isArray()) {
                for (int index = 0; index < stepsNode.size(); index++) {
                    JsonNode node = stepsNode.get(index);
                    String original = text(node.path("client_id").asText(""), "step_" + (index + 1), 120);
                    String clientId = "step_" + (index + 1);
                    sourceToClient.putIfAbsent(original, clientId);
                    String title = text(node.path("title").asText(""), "Step " + (index + 1), 160);
                    String description = text(node.path("description").asText(""), title, 4_000);
                    String type = text(node.path("type").asText("ANALYSIS"), "ANALYSIS", 40).toUpperCase();
                    if (!TYPES.contains(type)) errors.add(clientId + " has unsupported type: " + type);
                    String mode = text(node.path("execution_mode").asText("REACT"), "REACT", 40).toUpperCase();
                    if (!EXECUTION_MODES.contains(mode)) errors.add(clientId + " has unsupported execution_mode: " + mode);
                    List<String> criteria = stringList(node.path("done_criteria"), 20, 500);
                    List<String> deps = stringList(node.path("dependencies"), 20, 120);
                    parsed.add(new ParsedStep(clientId, original, index + 1, title, description, type, mode, criteria, deps));
                }
            }

            Map<String, String> clientToStepId = new LinkedHashMap<>();
            for (ParsedStep step : parsed) clientToStepId.put(step.clientId(), id("pstep"));
            List<PlanStore.StepDraft> stepDrafts = new ArrayList<>();
            List<PlanStore.EdgeDraft> edgeDrafts = new ArrayList<>();
            Map<String, List<String>> graph = new LinkedHashMap<>();
            for (ParsedStep step : parsed) {
                String stepId = clientToStepId.get(step.clientId());
                stepDrafts.add(new PlanStore.StepDraft(stepId, step.clientId(), step.ordinal(), step.title(),
                        step.description(), step.type(), step.executionMode(),
                        mapper.writeValueAsString(step.doneCriteria())));
                graph.put(step.clientId(), new ArrayList<>());
                for (String dep : step.dependencies()) {
                    String depClient = sourceToClient.getOrDefault(dep, dep);
                    String depStepId = clientToStepId.get(depClient);
                    if (depStepId == null) {
                        errors.add(step.clientId() + " depends on unknown step: " + dep);
                        continue;
                    }
                    if (depClient.equals(step.clientId())) {
                        errors.add(step.clientId() + " cannot depend on itself");
                        continue;
                    }
                    graph.get(step.clientId()).add(depClient);
                    edgeDrafts.add(new PlanStore.EdgeDraft(depStepId, stepId));
                }
            }
            errors.addAll(cycles(graph));
            if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
            return new ParsedPlan(objective, summary, cleaned, stepDrafts, edgeDrafts);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid plan JSON: " + e.getMessage(), e);
        }
    }

    private static List<String> cycles(Map<String, List<String>> graph) {
        List<String> errors = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        for (String node : graph.keySet()) detect(node, graph, visited, visiting, errors);
        return errors;
    }

    private static void detect(String node, Map<String, List<String>> graph, Set<String> visited,
                               Set<String> visiting, List<String> errors) {
        if (visited.contains(node)) return;
        if (!visiting.add(node)) {
            errors.add("plan dependency graph contains a cycle at " + node);
            return;
        }
        for (String dep : graph.getOrDefault(node, List.of())) detect(dep, graph, visited, visiting, errors);
        visiting.remove(node);
        visited.add(node);
    }

    private static List<String> stringList(JsonNode node, int maxItems, int maxChars) {
        List<String> values = new ArrayList<>();
        if (!node.isArray()) return values;
        for (JsonNode item : node) {
            if (values.size() >= maxItems) throw new IllegalArgumentException("list contains too many items");
            String value = item.asText("").trim();
            if (!value.isBlank()) values.add(value.length() > maxChars ? value.substring(0, maxChars) : value);
        }
        return values;
    }

    private static String value(JsonNode root, String name, String fallback, int max) {
        return text(root.path(name).asText(fallback == null ? "" : fallback), fallback == null ? "" : fallback, max);
    }

    private static String text(String value, String fallback, int max) {
        String resolved = value == null || value.isBlank() ? fallback : value.trim();
        if (resolved.length() > max) return resolved.substring(0, max);
        return resolved;
    }

    private static String clean(String value) {
        String text = value == null ? "" : value.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```json\\s*", "").replaceFirst("^```\\s*", "");
            text = text.replaceFirst("\\s*```$", "");
        }
        if (text.isBlank()) throw new IllegalArgumentException("plan JSON must not be blank");
        return text;
    }

    private static String id(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private record ParsedStep(String clientId, String originalId, int ordinal, String title, String description,
                              String type, String executionMode, List<String> doneCriteria,
                              List<String> dependencies) { }

    public record ParsedPlan(String objective, String summary, String rawJson,
                             List<PlanStore.StepDraft> steps, List<PlanStore.EdgeDraft> edges) { }
}
