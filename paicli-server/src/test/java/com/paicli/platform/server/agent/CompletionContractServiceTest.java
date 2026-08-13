package com.paicli.platform.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.domain.CompletionMode;
import com.paicli.platform.server.store.PlanStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CompletionContractServiceTest {
    @TempDir
    Path tempDir;

    private CompletionContractService service(SqliteRuntimeStore store, PlanStore plans) {
        return new CompletionContractService(store, plans, new ObjectMapper());
    }

    @Test
    void rootClassifierCreatesMutationContractAndPersists() throws Exception {
        PlatformProperties properties = new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties);
        store.initialize();
        PlanStore plans = new PlanStore(properties);
        CompletionContractService service = service(store, plans);

        var session = store.createSession("contract", "project-c");
        var run = store.createRun(session.id(), "请修改 UserService.java，把 timeout 改成 30 秒");

        var contract = service.ensureForRun(run.id());

        assertThat(contract.mode()).isEqualTo(CompletionMode.MUTATION_REQUIRED);
        assertThat(contract.requiresWorkspaceChange()).isTrue();
        assertThat(contract.source()).isEqualTo("root_classifier");
        // Second call reuses the persisted contract without reclassification.
        assertThat(service.ensureForRun(run.id())).isEqualTo(contract);
        assertThat(store.events(run.id(), 0)).extracting("type").contains("run.completion_contract.created");
    }

    @Test
    void pureQuestionIsTextOnly() throws Exception {
        PlatformProperties properties = new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties);
        store.initialize();
        PlanStore plans = new PlanStore(properties);
        CompletionContractService service = service(store, plans);

        var session = store.createSession("contract", "project-c");
        var run = store.createRun(session.id(), "解释 RunVerificationService");

        var contract = service.ensureForRun(run.id());

        assertThat(contract.mode()).isEqualTo(CompletionMode.TEXT_ONLY);
        assertThat(contract.requiresWorkspaceChange()).isFalse();
        assertThat(contract.requiresTests()).isFalse();
    }

    @Test
    void collaborationIntentIsNotDowngradedByHistoricalDigest() throws Exception {
        PlatformProperties properties = new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties);
        store.initialize();
        CompletionContractService service = service(store, new PlanStore(properties));

        var session = store.createSession("collaboration", "project-c");
        var run = store.createRun(session.id(), """
                You are handling a persistent collaboration task.
                task_id: task-1
                title: Fix the solitaire dealing bug
                status: TODO
                description:
                The deal clusters ranks and must be fixed.
                acceptance_criteria:
                The game runs and the distribution is valid.
                trigger: MENTION
                instruction:
                Fix the dealing implementation and run its tests.

                <task_digest>
                Stage 1 analysis is historical context only.
                </task_digest>
                """);

        var contract = service.ensureForRun(run.id());

        assertThat(contract.mode()).isEqualTo(CompletionMode.MUTATION_AND_TEST);
        assertThat(contract.requiresWorkspaceChange()).isTrue();
        assertThat(contract.requiresTests()).isTrue();
    }

    @Test
    void delegationEnvelopeDrivesChildContract() throws Exception {
        PlatformProperties properties = new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties);
        store.initialize();
        PlanStore plans = new PlanStore(properties);
        CompletionContractService service = service(store, plans);

        var parentSession = store.createSession("parent", "project-c");
        var parentRun = store.createRun(parentSession.id(), "delegate");
        var parentTool = store.createToolCall(parentRun.id(), "parent-tool-1", "spawn_agent", "{}", "spawn-key-parent");
        ObjectMapper mapper = new ObjectMapper();
        String envelope = mapper.writeValueAsString(Map.of(
                "version", 2,
                "objective", "修改 A.java",
                "done_criteria", List.of("tests pass", "login works")));
        store.createOrGetDelegation(parentRun.id(), parentTool.id(), "Backend", "修改 A.java 并运行测试",
                null, null, null, null, envelope);
        var childRunId = store.delegationsForRun(parentRun.id()).get(0).childRunId();

        var contract = service.ensureForRun(childRunId);

        assertThat(contract.source()).isEqualTo("delegation_envelope");
        assertThat(contract.requiresWorkspaceChange()).isFalse();
        assertThat(contract.requiresTests()).isTrue();
        assertThat(contract.doneCriteria()).contains("tests pass", "login works");
    }

    @Test
    void workingPlanCompletionCanOnlyStrengthen() throws Exception {
        PlatformProperties properties = new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties);
        store.initialize();
        PlanStore plans = new PlanStore(properties);
        CompletionContractService service = service(store, plans);

        var session = store.createSession("contract", "project-c");
        var run = store.createRun(session.id(), "请修改 UserService.java");
        // Model tries to weaken (false) but the root classifier already requires mutation.
        store.saveWorkingPlan(run.id(), "modify", "[{\"id\":\"1\",\"title\":\"x\",\"status\":\"TODO\"}]", "ACTIVE",
                mapperJson(Map.of("requires_workspace_change", false, "requires_tests", true,
                        "required_test_families", List.of("MAVEN"))));

        var contract = service.ensureForRun(run.id());

        assertThat(contract.mode()).isEqualTo(CompletionMode.MUTATION_AND_TEST);
        assertThat(contract.requiresWorkspaceChange()).isTrue();
        assertThat(contract.requiresTests()).isTrue();
        assertThat(contract.requiredTestFamilies()).contains("MAVEN");
        assertThat(contract.source()).isEqualTo("working_plan_completion");
    }

    private static String mapperJson(Object value) throws Exception {
        return new ObjectMapper().writeValueAsString(value);
    }
}
