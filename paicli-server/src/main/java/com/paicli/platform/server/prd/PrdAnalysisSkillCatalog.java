package com.paicli.platform.server.prd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.store.ProductivityStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Seeds the three bundled PRD skills into the global skill directory and the
 * three system Agent Profiles (mapper / node-analyst / reconciler). Skills are
 * resolved through the existing SkillService; profiles are resolved through the
 * existing ProductivityStore. Seeding is idempotent and never overwrites user
 * edits.
 */
@Component
public class PrdAnalysisSkillCatalog implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(PrdAnalysisSkillCatalog.class);
    static final List<String> SKILLS = List.of("prd-map", "prd-node-analyze", "prd-reconcile");
    static final String PROFILE_MAPPER = "system.prd.mapper";
    static final String PROFILE_NODE_ANALYST = "system.prd.node-analyst";
    static final String PROFILE_RECONCILER = "system.prd.reconciler";

    private final Path skillsRoot;
    private final ProductivityStore productivity;
    private final ObjectMapper mapper;

    public PrdAnalysisSkillCatalog(PlatformProperties properties, ProductivityStore productivity,
                                   ObjectMapper mapper) {
        this.skillsRoot = properties.dataDir().resolve("skills").toAbsolutePath().normalize();
        this.productivity = productivity;
        this.mapper = mapper;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        seedSkills();
        ensureProfiles("default");
    }

    public void ensureProfiles(String projectKey) {
        String project = projectKey == null || projectKey.isBlank() ? "default" : projectKey.trim();
        ensureProfile(project, PROFILE_MAPPER, "PRD Mapper",
                "你是 PaiCLI 的 PRD 分析映射角色。严格遵循 prd-map skill：先把 PRD 切成分析节点并声明依赖，"
                        + "再一次性调用 prd_submit_map 提交。不要深入提取实体或规则。",
                List.of("prd_get_task_context", "prd_list_source_chunks", "prd_submit_map"),
                List.of("prd-map"), "low");
        ensureProfile(project, PROFILE_NODE_ANALYST, "PRD Node Analyst",
                "你是 PaiCLI 的 PRD 分析节点角色。严格遵循 prd-node-analyze skill：只分析当前绑定节点，"
                        + "Evidence first，最后一次性调用 prd_submit_node_analysis 提交。不得写 Markdown。",
                List.of("prd_get_task_context", "prd_read_node", "prd_search_sources",
                        "prd_get_dependency_summaries", "prd_submit_node_analysis"),
                List.of("prd-node-analyze"), "high");
        ensureProfile(project, PROFILE_RECONCILER, "PRD Reconciler",
                "你是 PaiCLI 的 PRD 分析归并角色。严格遵循 prd-reconcile skill：基于结构化 findings 与校验报告"
                        + "跨节点去重、消解冲突、应用用户回答，最后一次性调用 prd_submit_reconciliation 提交。",
                List.of("prd_get_task_context", "prd_get_findings", "prd_get_open_questions",
                        "prd_get_validation_report", "prd_submit_reconciliation"),
                List.of("prd-reconcile"), "high");
    }

    private void seedSkills() throws Exception {
        Files.createDirectories(skillsRoot);
        for (String name : SKILLS) {
            Path file = skillsRoot.resolve(name).resolve("SKILL.md");
            if (Files.isRegularFile(file)) continue;
            Files.createDirectories(file.getParent());
            try (InputStream in = getClass().getResourceAsStream("/prd-analysis/skills/" + name + "/SKILL.md")) {
                if (in == null) {
                    log.warn("Bundled PRD skill resource missing: {}", name);
                    continue;
                }
                Files.copy(in, file);
            }
        }
    }

    private void ensureProfile(String projectKey, String id, String name, String systemPrompt,
                               List<String> tools, List<String> skills, String reasoningEffort) {
        if (productivity.findAgentProfile(id).isPresent()) return;
        productivity.saveAgentProfile(id, projectKey, name, "PRD Analysis 系统角色（自动内置）", systemPrompt,
                null, write(tools), write(skills), "", "EXPERT", "MANUAL", "PROJECT", "INHERIT",
                "enabled", reasoningEffort, "bash", true, "system.prd", 0);
        log.info("Seeded PRD agent profile {}", id);
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize PRD profile seed", e);
        }
    }
}
