package com.paicli.platform.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.SandboxDriver;
import com.paicli.platform.common.ToolRequest;
import com.paicli.platform.common.ToolResult;
import com.paicli.platform.server.collaboration.CollaborationRoutingService;
import com.paicli.platform.server.collaboration.CollaborationService;
import com.paicli.platform.server.collaboration.DeliveryManifestService;
import com.paicli.platform.server.collaboration.ExpertThreadDigestBuilder;
import com.paicli.platform.server.collaboration.ExpertThreadService;
import com.paicli.platform.server.collaboration.TaskDigestService;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.model.ModelClient;
import com.paicli.platform.server.store.CollaborationStore;
import com.paicli.platform.server.store.PlanStore;
import com.paicli.platform.server.store.ProductivityStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DelegationToolProviderTest {
    @TempDir
    Path tempDir;

    @Test
    void spawnPersistsDoneCriteriaAndGetAgentResultReturnsThemWithCriterionStatuses() throws Exception {
        PlatformProperties properties = new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties);
        store.initialize();
        ProductivityStore productivity = new ProductivityStore(properties);
        PlanStore plans = new PlanStore(properties);
        CollaborationStore collaboration = new CollaborationStore(properties);
        ObjectMapper mapper = new ObjectMapper();
        DelegationToolProvider provider = new DelegationToolProvider(store, productivity, mapper, plans,
                collaboration, new DelegationEnvelopeBuilder(), new AgentResultValidator());

        var session = store.createSession("agent", "project-p1");
        var parent = store.createRun(session.id(), "parent task");
        var profile = productivity.saveAgentProfile(null, "project-p1", "Backend", "expert", "prompt",
                "model-a", "[]", "[]", "", "EXPERT", "", "PROJECT", "INHERIT", true);
        var tool1 = store.createToolCall(parent.id(), "provider-call-1", "spawn_agent", "{}", "spawn-key-1");

        ToolResult spawned = provider.execute(new ToolRequest(tool1.id(), parent.id(), "spawn_agent",
                Map.of("agent_profile_id", profile.id(), "name", "Backend", "task", "export api",
                        "done_criteria", List.of("tests pass", "login works")), "spawn-key-1"));
        assertThat(spawned.success()).withFailMessage("spawn tool failed: " + spawned.error()).isTrue();
        Map<String, Object> spawnOut = mapper.readValue(spawned.content(), new com.fasterxml.jackson.core.type.TypeReference<>() { });
        @SuppressWarnings("unchecked")
        Map<String, Object> envelope = (Map<String, Object>) spawnOut.get("envelope");
        assertThat(envelope.get("done_criteria")).isEqualTo(List.of("tests pass", "login works"));
        String childRunId = (String) spawnOut.get("child_run_id");

        ToolResult result = provider.execute(new ToolRequest("call-2", parent.id(), "get_agent_result",
                Map.of("child_run_id", childRunId), "result-key-1"));
        assertThat(result.success()).isTrue();
        Map<String, Object> out = mapper.readValue(result.content(), new com.fasterxml.jackson.core.type.TypeReference<>() { });
        assertThat(out.get("done_criteria")).isEqualTo(List.of("tests pass", "login works"));
        @SuppressWarnings("unchecked")
        Map<String, Object> validation = (Map<String, Object>) out.get("validation");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> criteria = (List<Map<String, Object>>) validation.get("criteria");
        assertThat(criteria).hasSize(2);
        assertThat(criteria).extracting("criterion")
                .containsExactly("tests pass", "login works");
        // No explicit criterion evidence was reported -> deterministic UNVERIFIED, valid untouched.
        assertThat(criteria).extracting("status").containsExactly("UNVERIFIED", "UNVERIFIED");
        assertThat(validation.get("valid")).isEqualTo(Boolean.TRUE);
    }

    @Test
    void planStepDoneCriteriaJsonIsParsedAsJsonArrayForEnvelopeFallback() throws Exception {
        PlatformProperties properties = new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties);
        store.initialize();
        ProductivityStore productivity = new ProductivityStore(properties);
        PlanStore plans = new PlanStore(properties);
        CollaborationStore collaboration = new CollaborationStore(properties);
        ObjectMapper mapper = new ObjectMapper();
        DelegationToolProvider provider = new DelegationToolProvider(store, productivity, mapper, plans,
                collaboration, new DelegationEnvelopeBuilder(), new AgentResultValidator());

        var session = store.createSession("agent", "project-p1");
        var parent = store.createRun(session.id(), "parent task");
        var profile = productivity.saveAgentProfile(null, "project-p1", "Backend", "expert", "prompt",
                "model-a", "[]", "[]", "", "EXPERT", "", "PROJECT", "INHERIT", true);
        var tool3 = store.createToolCall(parent.id(), "provider-call-3", "spawn_agent", "{}", "spawn-key-2");
        plans.savePlan(null, parent.id(), "project-p1", "objective", "", "MANUAL", "{}", "[]",
                List.of(new PlanStore.StepDraft("s1", "c1", 1, "step 1", "desc", "EXECUTE", "AUTO",
                        "[\"tests pass\",\"login works\"]")), List.of());
        var step = plans.findStep("s1").orElseThrow();

        // No explicit done_criteria argument: the envelope must fall back to the plan step's
        // doneCriteriaJson parsed as a JSON array (not a single-element list of the raw string).
        ToolResult spawned = provider.execute(new ToolRequest(tool3.id(), parent.id(), "spawn_agent",
                Map.of("agent_profile_id", profile.id(), "name", "Backend", "task", "export api",
                        "plan_step_id", step.id()), "spawn-key-2"));
        assertThat(spawned.success()).withFailMessage("spawn tool failed: " + spawned.error()).isTrue();
        Map<String, Object> spawnOut = mapper.readValue(spawned.content(), new com.fasterxml.jackson.core.type.TypeReference<>() { });
        @SuppressWarnings("unchecked")
        Map<String, Object> envelope = (Map<String, Object>) spawnOut.get("envelope");
        assertThat(envelope.get("done_criteria")).isEqualTo(List.of("tests pass", "login works"));
    }

    @Test
    void stageDispatchAcceptanceCriteriaBecomeDoneCriteriaForValidator() throws Exception {
        PlatformProperties properties = new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties);
        store.initialize();
        ProductivityStore productivity = new ProductivityStore(properties);
        PlanStore plans = new PlanStore(properties);
        CollaborationStore collaboration = new CollaborationStore(properties);
        ObjectMapper mapper = new ObjectMapper();
        DelegationEnvelopeBuilder envelopeBuilder = new DelegationEnvelopeBuilder();
        AgentResultValidator validator = new AgentResultValidator();
        ExpertThreadService expertThreads = new ExpertThreadService(collaboration,
                new ExpertThreadDigestBuilder(collaboration, store, mapper));
        CollaborationService service = new CollaborationService(collaboration, store, productivity,
                org.mockito.Mockito.mock(CollaborationRoutingService.class), mapper,
                org.mockito.Mockito.mock(ModelClient.class), org.mockito.Mockito.mock(SandboxDriver.class),
                new TaskDigestService(collaboration, store, mapper),
                new DeliveryManifestService(collaboration, store, mapper), expertThreads, envelopeBuilder);
        DelegationToolProvider provider = new DelegationToolProvider(store, productivity, mapper, plans,
                collaboration, envelopeBuilder, validator);

        var root = collaboration.saveTask(null, "default", "root task", "desc", "IN_PROGRESS", 0,
                "TEAM", "team-a", "done when green", null, 0, null, "USER");
        var leaderSession = store.createSession("leader", "default");
        var leaderRun = store.createRun(leaderSession.id(), "leader work");
        var backend = productivity.saveAgentProfile(null, "default", "Backend", "expert", "prompt",
                "model-a", "[]", "[]", "", "EXPERT", "", "PROJECT", "INHERIT", true);
        var tool = store.createToolCall(leaderRun.id(), "provider-stage", "create_collaboration_subtask",
                "{}", "stage-key");

        service.createAndDispatchSubtask(root.id(), leaderRun.id(), tool.id(),
                new CollaborationService.TaskCommand("default", "Stage 1", "实现导出", "TODO", 0,
                        "AGENT", backend.id(), "测试通过并保持接口兼容", root.id(), 1, null, "AGENT:leader-a"));

        var delegation = store.delegationsForRun(leaderRun.id()).get(0);
        ToolResult result = provider.execute(new ToolRequest("call-r", leaderRun.id(), "get_agent_result",
                Map.of("child_run_id", delegation.childRunId()), "stage-result-key"));
        assertThat(result.success()).withFailMessage("get_agent_result failed: " + result.error()).isTrue();
        Map<String, Object> out = mapper.readValue(result.content(), new com.fasterxml.jackson.core.type.TypeReference<>() { });
        assertThat(out.get("done_criteria")).isEqualTo(List.of("测试通过并保持接口兼容"));
        @SuppressWarnings("unchecked")
        Map<String, Object> validation = (Map<String, Object>) out.get("validation");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> criteria = (List<Map<String, Object>>) validation.get("criteria");
        assertThat(criteria).singleElement().satisfies(criterion -> {
            assertThat(criterion.get("criterion")).isEqualTo("测试通过并保持接口兼容");
            assertThat(criterion.get("status")).isEqualTo("UNVERIFIED");
        });
        assertThat(validation.get("valid")).isEqualTo(Boolean.TRUE);
    }
}