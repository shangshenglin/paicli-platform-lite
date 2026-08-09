package com.paicli.platform.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.sandbox.LocalSandboxDriver;
import com.paicli.platform.server.store.PlanStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import com.paicli.platform.server.tool.ToolRouter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentResultServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void childAgentResultAutoPopulatesRealEvidence() throws Exception {
        PlatformProperties properties = new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties);
        store.initialize();
        PlanStore plans = new PlanStore(properties);
        ObjectMapper mapper = new ObjectMapper();
        ToolRouter router = new ToolRouter(new LocalSandboxDriver(properties));
        RunEvidenceCollector collector = new RunEvidenceCollector(store, mapper);
        CompletionContractService contracts = new CompletionContractService(store, plans, mapper);
        AgentResultService service = new AgentResultService(store, collector, contracts);

        var parentSession = store.createSession("parent", "project-a");
        var parentRun = store.createRun(parentSession.id(), "delegate");
        var parentTool = store.createToolCall(parentRun.id(), "provider-spawn", "spawn_agent", "{}", "spawn-key");
        store.createOrGetDelegation(parentRun.id(), parentTool.id(), "Backend", "修改 A.java 并运行测试",
                null, null, null, null, mapper.writeValueAsString(Map.of("requires_workspace_change", true)));
        var delegation = store.delegationsForRun(parentRun.id()).get(0);
        var childRunId = delegation.childRunId();

        // Child really writes a file and runs a passing test.
        store.markRunStatus(childRunId, com.paicli.platform.common.RunStatus.WAITING_TOOL);
        var write = store.createToolCall(childRunId, "provider-w", "write_file",
                "{\"path\":\"src/A.java\",\"content\":\"class A {}\"}", "child-write");
        store.markToolRunning(write.id());
        store.commitToolOutcome(delegation.childSessionId(), childRunId, write, true, "ok", null,
                "{\"path\":\"src/A.java\",\"changed\":true,\"afterSha256\":\"abc\"}",
                "{\"path\":\"src/A.java\",\"changed\":true}", 0);
        store.markRunStatus(childRunId, com.paicli.platform.common.RunStatus.WAITING_TOOL);
        var test = store.createToolCall(childRunId, "provider-t", "execute_command",
                "{\"command\":\"./mvnw test\"}", "child-test");
        store.markToolRunning(test.id());
        store.commitToolOutcome(delegation.childSessionId(), childRunId, test, true, "ok", null,
                "{\"shell\":\"bash\",\"exitCode\":0,\"timedOut\":false}",
                "{\"shell\":\"bash\",\"exitCode\":0}", 1);
        store.markRunStatus(childRunId, com.paicli.platform.common.RunStatus.WAITING_MODEL);
        store.appendMessage(delegation.childSessionId(), childRunId, "assistant", "完成修改并通过测试");
        store.completeRun(childRunId);

        var child = store.findRun(childRunId).orElseThrow();
        Map<String, Object> result = service.build(delegation, child);

        assertThat(result.get("status")).isEqualTo("COMPLETED");
        assertThat((List<?>) result.get("files_changed")).isNotEmpty();
        assertThat((List<?>) result.get("commands_executed")).isNotEmpty();
        assertThat((List<?>) result.get("tests")).anySatisfy(testEvidence -> {
            Map<?, ?> entry = (Map<?, ?>) testEvidence;
            assertThat(entry.get("family")).isEqualTo("MAVEN");
            assertThat(entry.get("status")).isEqualTo("PASSED");
            assertThat(entry.get("ordinal")).isEqualTo(1);
            assertThat(entry.get("after_last_mutation")).isEqualTo(true);
        });
        assertThat(((Map<?, ?>) result.get("completion_contract")).get("mode"))
                .isEqualTo("MUTATION_AND_TEST");
    }

    @Test
    void commandMutationEvidencePassesChildContractValidationWithoutFakeFilePath() throws Exception {
        PlatformProperties properties = new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties);
        store.initialize();
        PlanStore plans = new PlanStore(properties);
        ObjectMapper mapper = new ObjectMapper();
        RunEvidenceCollector collector = new RunEvidenceCollector(store, mapper);
        CompletionContractService contracts = new CompletionContractService(store, plans, mapper);
        AgentResultService service = new AgentResultService(store, collector, contracts);
        AgentResultValidator validator = new AgentResultValidator();

        var parentSession = store.createSession("command-parent", "project-command");
        var parentRun = store.createRun(parentSession.id(), "delegate command mutation");
        var parentTool = store.createToolCall(parentRun.id(), "provider-spawn", "spawn_agent", "{}", "spawn-command");
        store.createOrGetDelegation(parentRun.id(), parentTool.id(), "Backend", "run mutation script",
                null, null, null, null, mapper.writeValueAsString(Map.of("requires_workspace_change", true)));
        var delegation = store.delegationsForRun(parentRun.id()).get(0);
        var childRunId = delegation.childRunId();

        store.markRunStatus(childRunId, com.paicli.platform.common.RunStatus.WAITING_TOOL);
        var command = store.createToolCall(childRunId, "provider-command", "execute_command",
                "{\"command\":\"sed -i 's/old/new/' config.ini\"}", "child-command");
        store.markToolRunning(command.id());
        store.commitToolOutcome(delegation.childSessionId(), childRunId, command, true, "ok", null,
                "{\"shell\":\"bash\",\"exitCode\":0,\"timedOut\":false,\"workspaceChanged\":true}",
                "{\"shell\":\"bash\",\"exitCode\":0,\"workspaceChanged\":true}", 0);
        store.markRunStatus(childRunId, com.paicli.platform.common.RunStatus.WAITING_MODEL);
        store.appendMessage(delegation.childSessionId(), childRunId, "assistant", "脚本已修改配置并完成");
        store.completeRun(childRunId);

        assertThat(store.findDelegation(parentRun.id(), childRunId).orElseThrow().resultJson())
                .contains("workspace_mutations");
        var child = store.findRun(childRunId).orElseThrow();
        Map<String, Object> result = service.build(delegation, child);
        var validation = validator.validate(child, contracts.ensureForRun(childRunId), result);

        assertThat(((List<?>) result.get("files_changed"))).isEmpty();
        assertThat(((List<?>) result.get("workspace_mutations"))).singleElement().satisfies(item -> {
            Map<?, ?> mutation = (Map<?, ?>) item;
            assertThat(mutation.get("source")).isEqualTo("execute_command");
            assertThat(mutation.get("command")).isEqualTo("sed -i 's/old/new/' config.ini");
        });
        assertThat(validation.valid()).isTrue();
    }
}
