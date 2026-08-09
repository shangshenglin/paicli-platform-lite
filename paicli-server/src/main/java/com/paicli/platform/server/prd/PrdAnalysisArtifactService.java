package com.paicli.platform.server.prd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.io.AtomicFileWriter;
import com.paicli.platform.server.store.PrdAnalysisStore;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class PrdAnalysisArtifactService {
    public static final Set<String> ARTIFACT_NAMES = Set.of(
            "source_contract.json", "node_schedule.json", "glossary.json", "design_outline.json",
            "domain_analysis.md", "condition_matrices.json", "prediction_reports.json",
            "design_index.json", "probe_report.json", "state.json", "strategy_journal.jsonl",
            "handoff_manifest.json");

    private final Path root;
    private final ObjectMapper mapper;

    public PrdAnalysisArtifactService(PlatformProperties properties, ObjectMapper mapper) {
        this.root = properties.dataDir().toAbsolutePath().normalize();
        this.mapper = mapper;
    }

    public void renderMap(PrdAnalysisStore.AnalysisJob job, List<PrdAnalysisStore.AnalysisNode> nodes) {
        writeJson(job, "source_contract.json", readJson(job.sourceContractJson(), mapper.createObjectNode()));
        ArrayNode schedule = mapper.createArrayNode();
        ArrayNode glossary = mapper.createArrayNode();
        ArrayNode predictions = mapper.createArrayNode();
        for (PrdAnalysisStore.AnalysisNode node : nodes) {
            ObjectNode item = schedule.addObject();
            item.put("node_id", node.nodeKey());
            item.put("ordinal", node.ordinal());
            item.put("heading", node.heading());
            item.put("heading_level", node.headingLevel());
            item.put("start_line", node.startLine());
            item.put("end_line", node.endLine());
            item.set("dependencies", readJson(node.dependenciesJson(), mapper.createArrayNode()));
            item.set("tags", readJson(node.tagsJson(), mapper.createArrayNode()));
            ObjectNode term = glossary.addObject();
            term.put("term", node.heading());
            term.put("canonical", canonical(node.heading()));
            term.put("source_node", node.nodeKey());
            ObjectNode prediction = predictions.addObject();
            prediction.put("id", "PE_" + node.nodeKey());
            prediction.put("type", predictedType(node.tagsJson()));
            prediction.put("name", node.heading());
            prediction.put("source_node", node.nodeKey());
            prediction.put("status", "pending");
        }
        ObjectNode outline = mapper.createObjectNode();
        outline.set("predictions", predictions);
        outline.put("generated_at", Instant.now().toString());
        writeJson(job, "node_schedule.json", schedule);
        writeJson(job, "glossary.json", glossary);
        writeJson(job, "design_outline.json", outline);
        renderState(job, nodes, List.of());
    }

    public void renderAnalysis(PrdAnalysisStore.AnalysisJob job,
                               List<PrdAnalysisStore.AnalysisNode> nodes,
                               List<PrdAnalysisStore.Clarification> clarifications) {
        StringBuilder document = new StringBuilder("# Domain Analysis\n\n")
                .append("> Job: `").append(job.id()).append("` · generated ")
                .append(Instant.now()).append("\n\n## 1. Node navigation\n\n");
        for (PrdAnalysisStore.AnalysisNode node : nodes) {
            document.append("- `").append(node.nodeKey()).append("` ")
                    .append(node.heading()).append(" (lines ").append(node.startLine())
                    .append('-').append(node.endLine()).append(") — ").append(node.status()).append('\n');
        }
        appendItems(document, "## 2. Entities", nodes, "entities");
        appendItems(document, "## 3. Rules", nodes, "rules");
        appendItems(document, "## 4. Flows", nodes, "flows");
        document.append("\n## 5. Clarifications\n\n");
        if (clarifications.isEmpty()) document.append("No unresolved clarification.\n");
        for (PrdAnalysisStore.Clarification question : clarifications) {
            document.append("- `").append(question.id()).append("` [")
                    .append(question.severity()).append("] ").append(question.question())
                    .append(" — ").append(question.status());
            if (question.answer() != null && !question.answer().isBlank()) {
                document.append("; answer: ").append(question.answer());
            }
            document.append('\n');
        }
        write(job, "domain_analysis.md", document.toString());

        ArrayNode matrices = mapper.createArrayNode();
        ArrayNode reports = mapper.createArrayNode();
        for (PrdAnalysisStore.AnalysisNode node : nodes) {
            JsonNode analysis = readJson(node.analysisJson(), mapper.createObjectNode());
            ObjectNode matrix = matrices.addObject();
            matrix.put("node_id", node.nodeKey());
            matrix.set("conditions", analysis.path("condition_matrix").isMissingNode()
                    ? mapper.createArrayNode() : analysis.path("condition_matrix"));
            ObjectNode report = reports.addObject();
            report.put("node_id", node.nodeKey());
            report.set("predictions", analysis.path("prediction_report").isMissingNode()
                    ? mapper.createArrayNode() : analysis.path("prediction_report"));
        }
        writeJson(job, "condition_matrices.json", matrices);
        writeJson(job, "prediction_reports.json", reports);
        renderState(job, nodes, clarifications);
    }

    public ObjectNode renderDesignIndex(PrdAnalysisStore.AnalysisJob job,
                                        List<PrdAnalysisStore.AnalysisNode> nodes,
                                        List<PrdAnalysisStore.AnalysisItem> items,
                                        List<PrdAnalysisStore.Clarification> clarifications) {
        ObjectNode index = mapper.createObjectNode();
        index.put("job_id", job.id());
        index.put("generated_at", Instant.now().toString());
        ArrayNode entity = index.putArray("entities");
        ArrayNode rules = index.putArray("rules");
        ArrayNode flows = index.putArray("flows");
        Map<String, String> canonicalOwners = new LinkedHashMap<>();
        ArrayNode duplicates = index.putArray("duplicates");
        for (PrdAnalysisStore.AnalysisItem item : items) {
            JsonNode payload = readJson(item.payloadJson(), mapper.createObjectNode());
            ObjectNode entry = payload.deepCopy();
            entry.put("node_id", nodeKey(nodes, item.nodeId()));
            String canonical = item.kind() + ":" + canonical(item.name());
            if (canonicalOwners.containsKey(canonical)) {
                ObjectNode duplicate = duplicates.addObject();
                duplicate.put("canonical", canonical);
                duplicate.put("kept", canonicalOwners.get(canonical));
                duplicate.put("merged", item.itemId());
                continue;
            }
            canonicalOwners.put(canonical, item.itemId());
            switch (item.kind()) {
                case "ENTITY" -> entity.add(entry);
                case "RULE" -> rules.add(entry);
                case "FLOW" -> flows.add(entry);
                default -> { }
            }
        }
        ArrayNode questions = index.putArray("clarifications");
        clarifications.forEach(value -> {
            ObjectNode question = questions.addObject();
            question.put("id", value.id());
            question.put("source", value.source());
            question.put("severity", value.severity());
            question.put("category", value.category());
            question.put("question", value.question());
            question.put("status", value.status());
            if (value.answer() != null) question.put("answer", value.answer());
            question.put("created_at", value.createdAt().toString());
            if (value.resolvedAt() != null) question.put("resolved_at", value.resolvedAt().toString());
        });
        writeJson(job, "design_index.json", index);
        return index;
    }

    public void renderProbe(PrdAnalysisStore.AnalysisJob job, JsonNode report) {
        writeJson(job, "probe_report.json", report);
    }

    public void renderState(PrdAnalysisStore.AnalysisJob job,
                            List<PrdAnalysisStore.AnalysisNode> nodes,
                            List<PrdAnalysisStore.Clarification> clarifications) {
        ObjectNode state = mapper.createObjectNode();
        state.put("job_id", job.id());
        state.put("status", job.status());
        state.put("stage", job.stage());
        state.put("progress_version", job.progressVersion());
        state.put("total_nodes", nodes.size());
        state.put("completed_nodes", nodes.stream().filter(node -> "COMPLETED".equals(node.status())).count());
        state.put("failed_nodes", nodes.stream().filter(node -> "FAILED".equals(node.status())).count());
        state.put("open_questions", clarifications.stream().filter(value -> "OPEN".equals(value.status())).count());
        state.put("updated_at", job.updatedAt().toString());
        writeJson(job, "state.json", state);
    }

    public void renderJournal(PrdAnalysisStore.AnalysisJob job,
                              List<PrdAnalysisStore.AnalysisEvent> events) {
        StringBuilder journal = new StringBuilder();
        for (PrdAnalysisStore.AnalysisEvent event : events) {
            try {
                journal.append(mapper.writeValueAsString(Map.of(
                        "sequence", event.sequence(), "type", event.type(),
                        "payload", readJson(event.payloadJson(), mapper.createObjectNode()),
                        "created_at", event.createdAt().toString()))).append('\n');
            } catch (Exception e) {
                throw new IllegalStateException("render strategy journal failed", e);
            }
        }
        write(job, "strategy_journal.jsonl", journal.toString());
    }

    public ObjectNode renderHandoff(PrdAnalysisStore.AnalysisJob job, JsonNode designIndex,
                                    JsonNode probeReport) {
        ObjectNode manifest = mapper.createObjectNode();
        manifest.put("job_id", job.id());
        manifest.put("status", "READY");
        manifest.put("created_at", Instant.now().toString());
        manifest.putArray("required_files")
                .add("domain_analysis.md").add("design_index.json").add("source_contract.json");
        manifest.putArray("supporting_files")
                .add("node_schedule.json").add("condition_matrices.json")
                .add("prediction_reports.json").add("probe_report.json");
        manifest.set("summary", mapper.createObjectNode()
                .put("entities", designIndex.path("entities").size())
                .put("rules", designIndex.path("rules").size())
                .put("flows", designIndex.path("flows").size())
                .put("probe_passed", probeReport.path("passed").asBoolean(false)));
        writeJson(job, "handoff_manifest.json", manifest);
        return manifest;
    }

    public List<ArtifactDescriptor> artifacts(PrdAnalysisStore.AnalysisJob job) {
        List<ArtifactDescriptor> values = new ArrayList<>();
        for (String name : ARTIFACT_NAMES.stream().sorted().toList()) {
            Path path = resolve(job, name);
            try {
                if (Files.isRegularFile(path)) values.add(new ArtifactDescriptor(name, Files.size(path)));
            } catch (IOException e) {
                throw new IllegalStateException("inspect PRD analysis artifact failed", e);
            }
        }
        return values;
    }

    public Path artifact(PrdAnalysisStore.AnalysisJob job, String name) {
        if (!ARTIFACT_NAMES.contains(name)) throw new IllegalArgumentException("unknown PRD analysis artifact");
        Path path = resolve(job, name);
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("PRD analysis artifact not found");
        return path;
    }

    private void appendItems(StringBuilder document, String title,
                             List<PrdAnalysisStore.AnalysisNode> nodes, String field) {
        document.append('\n').append(title).append("\n\n");
        int count = 0;
        for (PrdAnalysisStore.AnalysisNode node : nodes) {
            JsonNode analysis = readJson(node.analysisJson(), mapper.createObjectNode());
            JsonNode values = analysis.path(field);
            if (!values.isArray()) continue;
            for (JsonNode item : values) {
                count++;
                document.append("### ").append(item.path("id").asText("ITEM"))
                        .append(" · ").append(item.path("name").asText(item.path("title").asText("Unnamed")))
                        .append("\n\n- Source: `").append(node.nodeKey()).append("` lines ")
                        .append(node.startLine()).append('-').append(node.endLine()).append('\n')
                        .append("- Description: ").append(item.path("description").asText(""))
                        .append("\n\n");
            }
        }
        if (count == 0) document.append("No items extracted.\n");
    }

    private void writeJson(PrdAnalysisStore.AnalysisJob job, String name, JsonNode value) {
        try {
            write(job, name, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n");
        } catch (Exception e) {
            throw new IllegalStateException("render PRD analysis JSON failed", e);
        }
    }

    private void write(PrdAnalysisStore.AnalysisJob job, String name, String value) {
        try {
            AtomicFileWriter.write(resolve(job, name), value.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("write PRD analysis artifact failed: " + name, e);
        }
    }

    private Path resolve(PrdAnalysisStore.AnalysisJob job, String name) {
        Path directory = root.resolve(job.artifactDir()).normalize();
        if (!directory.startsWith(root)) throw new IllegalStateException("artifact directory escapes data root");
        Path target = directory.resolve(name).normalize();
        if (!target.startsWith(directory)) throw new IllegalArgumentException("invalid artifact name");
        return target;
    }

    private JsonNode readJson(String value, JsonNode fallback) {
        try {
            return value == null || value.isBlank() ? fallback : mapper.readTree(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String nodeKey(List<PrdAnalysisStore.AnalysisNode> nodes, String id) {
        return nodes.stream().filter(node -> node.id().equals(id)).map(PrdAnalysisStore.AnalysisNode::nodeKey)
                .findFirst().orElse(id);
    }

    private static String canonical(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static String predictedType(String tagsJson) {
        String value = tagsJson.toLowerCase(Locale.ROOT);
        if (value.contains("flow")) return "FLOW";
        if (value.contains("rule")) return "RULE";
        if (value.contains("source")) return "SOURCE";
        return "ENTITY";
    }

    public record ArtifactDescriptor(String name, long size) { }
}
