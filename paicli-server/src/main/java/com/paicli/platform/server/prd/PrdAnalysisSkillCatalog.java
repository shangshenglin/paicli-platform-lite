package com.paicli.platform.server.prd;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class PrdAnalysisSkillCatalog {
    private final Map<String, SkillDefinition> skills;

    public PrdAnalysisSkillCatalog() {
        Map<String, List<String>> tools = new LinkedHashMap<>();
        tools.put("MAP_PRD", List.of("register_domain_nodes", "define_glossary", "record_design_prediction", "complete_skill"));
        tools.put("ANALYZE_NODE", List.of("submit_node_result", "submit_condition_matrix",
                "submit_hypothesis_checklist", "submit_prediction_report", "evaluate_node_progress"));
        tools.put("MERGE", List.of("submit_design_index", "record_clarification", "complete_skill"));
        tools.put("PROBE", List.of("run_local_probe", "record_clarification", "complete_skill"));
        tools.put("CLARIFY", List.of("ask_user_question", "resolve_clarification"));
        tools.put("HANDOFF", List.of("run_handoff_gate", "handoff_to_downstream", "complete_skill"));
        Map<String, SkillDefinition> loaded = new LinkedHashMap<>();
        tools.forEach((name, allowed) -> loaded.put(name,
                new SkillDefinition(name, allowed, read(name.toLowerCase() + ".md"))));
        this.skills = Map.copyOf(loaded);
    }

    public SkillDefinition skill(String name) {
        SkillDefinition value = skills.get(name);
        if (value == null) throw new IllegalArgumentException("unknown PRD analysis skill: " + name);
        return value;
    }

    public List<SkillDefinition> skills() {
        return skills.values().stream().sorted(java.util.Comparator.comparing(SkillDefinition::name)).toList();
    }

    private static String read(String file) {
        try {
            return new ClassPathResource("prd-analysis-skills/" + file)
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("load PRD analysis skill failed: " + file, e);
        }
    }

    public record SkillDefinition(String name, List<String> allowedTools, String instructions) { }
}
