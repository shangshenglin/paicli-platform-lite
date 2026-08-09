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
 *
 * <p>Profile seeding is best-effort: SQLite can be transiently busy at startup
 * (scheduled maintenance or another connection), so the startup pass retries a
 * few times and then logs a warning instead of failing the whole application.
 * The coordinator re-invokes {@link #ensureProfiles(String)} before creating any
 * PRD Run, so profiles are guaranteed to exist before first use.
 */
@Component
public class PrdAnalysisSkillCatalog implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(PrdAnalysisSkillCatalog.class);
    private static final int PROFILE_SEED_ATTEMPTS = 5;
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
        try {
            ensureProfiles("default");
        } catch (RuntimeException e) {
            log.warn("PRD agent profile seeding failed at startup and will be retried lazily: {}", message(e));
        }
    }

    public void ensureProfiles(String projectKey) {
        String project = projectKey == null || projectKey.isBlank() ? "default" : projectKey.trim();
        RuntimeException last = null;
        for (int attempt = 1; attempt <= PROFILE_SEED_ATTEMPTS; attempt++) {
            try {
                ensureProfile(project, PROFILE_MAPPER, "PRD Mapper",
                        "你是 PaiCLI 的 PRD 分析映射角色。严格遵循 prd-map skill：先把 PRD 切成分析节点并声明依赖，"
                                + "再一次性调用 prd_submit_map 提交。不要深入提取实体或规则。",
                        List.of("prd_get_task_context", "prd_list_source_chunks", "read_artifact", "prd_submit_map"),
                        List.of("prd-map"), "low");
                ensureProfile(project, PROFILE_NODE_ANALYST, "PRD Node Analyst",
                        "你是 PaiCLI 的 PRD 分析节点角色。严格遵循 prd-node-analyze skill：只分析当前绑定节点，"
                                + "Evidence first，最后一次性调用 prd_submit_node_analysis 提交。不得写 Markdown。",
                        List.of("prd_get_task_context", "prd_read_node", "prd_search_sources",
                                "prd_get_dependency_summaries", "read_artifact", "prd_submit_node_analysis"),
                        List.of("prd-node-analyze"), "high");
                ensureProfile(project, PROFILE_RECONCILER, "PRD Reconciler",
                        "你是 PaiCLI 的 PRD 分析归并角色。严格遵循 prd-reconcile skill：基于结构化 findings 与校验报告"
                                + "跨节点去重、消解冲突、应用用户回答，最后一次性调用 prd_submit_reconciliation 提交。",
                        List.of("prd_get_task_context", "prd_get_findings", "prd_get_open_questions",
                                "prd_get_validation_report", "read_artifact", "prd_submit_reconciliation"),
                        List.of("prd-reconcile"), "high");
                return;
            } catch (RuntimeException e) {
                last = e;
                if (!isBusy(e) || attempt >= PROFILE_SEED_ATTEMPTS) break;
                sleep(300L * attempt);
            }
        }
        throw new IllegalStateException("failed to seed PRD agent profiles: " + message(last), last);
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
        ProductivityStore.AgentProfile existing = productivity.findAgentProfile(id).orElse(null);
        if (existing != null) {
            upgradeBundledProfileTools(existing, tools);
            return;
        }
        productivity.saveAgentProfile(id, projectKey, name, "PRD Analysis 系统角色（自动内置）", systemPrompt,
                null, write(tools), write(skills), "", "EXPERT", "MANUAL", "PROJECT", "INHERIT",
                "enabled", reasoningEffort, "bash", true, "system.prd", 0);
        log.info("Seeded PRD agent profile {}", id);
    }

    private void upgradeBundledProfileTools(ProductivityStore.AgentProfile existing, List<String> requiredTools) {
        if (!"system.prd".equals(existing.templateKey()) || !requiredTools.contains("read_artifact")) return;
        List<String> current = readStrings(existing.toolNamesJson());
        if (current.contains("read_artifact")) return;
        current.add("read_artifact");
        productivity.saveAgentProfile(existing.id(), existing.projectKey(), existing.name(), existing.description(),
                existing.systemPrompt(), existing.modelProfileId(), write(current), existing.skillNamesJson(),
                existing.outputSchema(), existing.collaborationRole(), existing.handoffPolicy(),
                existing.workspaceScope(), existing.approvalPolicy(), existing.thinkingMode(), existing.reasoningEffort(),
                existing.executionShell(), existing.enabled(), existing.templateKey(), existing.templateVersion());
        log.info("Upgraded PRD agent profile {} with read_artifact", existing.id());
    }

    private List<String> readStrings(String json) {
        try {
            return new java.util.ArrayList<>(mapper.readValue(json == null || json.isBlank() ? "[]" : json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { }));
        } catch (Exception e) {
            throw new IllegalStateException("failed to read PRD profile tool list", e);
        }
    }

    private static boolean isBusy(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && (message.contains("SQLITE_BUSY")
                    || message.contains("database is locked"))) {
                return true;
            }
        }
        return false;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String message(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize PRD profile seed", e);
        }
    }
}
