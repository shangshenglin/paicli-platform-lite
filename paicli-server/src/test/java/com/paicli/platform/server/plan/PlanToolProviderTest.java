package com.paicli.platform.server.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.ToolEffect;
import com.paicli.platform.common.ToolRequest;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.model.ModelClient;
import com.paicli.platform.server.store.PlanStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PlanToolProviderTest {
    @TempDir
    Path tempDir;

    @Test
    void exposesApprovedIdempotentPlanLifecycleOnlyToScopedExperts() throws Exception {
        PlatformProperties properties = new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore runtime = new SqliteRuntimeStore(properties);
        runtime.initialize();
        PlanStore plans = new PlanStore(properties);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        PlanService service = new PlanService(plans, new PlanParser(mapper), runtime,
                mock(ModelClient.class), mapper);
        PlanExecutionService execution = new PlanExecutionService(
                plans, runtime, new PlanValidator(runtime, mapper));
        PlanToolProvider provider = new PlanToolProvider(runtime, plans, service, execution, mapper);
        var session = runtime.createSession("expert", "project-a");
        var expert = runtime.createRun(session.id(), "create a plan", "disabled", "", List.of(),
                null, "expert-profile", 0, 0);
        String raw = """
                {
                  "objective":"inspect project",
                  "summary":"Inspect and report.",
                  "steps":[
                    {"client_id":"inspect","title":"Inspect","description":"Inspect files",
                     "type":"ANALYSIS","execution_mode":"REACT","dependencies":[],
                     "done_criteria":["inspection complete"]}
                  ]
                }
                """;
        ToolRequest create = new ToolRequest("tool-plan-create", expert.id(), "create_plan",
                Map.of("objective", "inspect project", "raw_plan_json", raw), "plan-create-key");

        var first = provider.execute(create);
        var second = provider.execute(create);
        String firstId = mapper.readTree(first.content()).path("plan").path("id").asText();
        String secondId = mapper.readTree(second.content()).path("plan").path("id").asText();

        assertThat(first.success()).isTrue();
        assertThat(second.success()).isTrue();
        assertThat(secondId).isEqualTo(firstId);
        assertThat(plans.plans("project-a", 20)).extracting("id").containsExactly(firstId);
        assertThat(provider.requiresApproval("create_plan")).isTrue();
        assertThat(provider.requiresApproval("replan_plan")).isTrue();
        assertThat(provider.requiresApproval("start_plan")).isTrue();
        assertThat(provider.requiresApproval("get_plan")).isFalse();
        assertThat(provider.effect("start_plan")).isEqualTo(ToolEffect.IDEMPOTENT_WRITE);
        assertThat(provider.effect("get_plan")).isEqualTo(ToolEffect.READ_ONLY);

        ToolRequest start = new ToolRequest("tool-plan-start", expert.id(), "start_plan",
                Map.of("plan_id", firstId), "plan-start-key");
        var started = provider.execute(start);
        var replayedStart = provider.execute(start);
        assertThat(started.success()).as(started.error()).isTrue();
        assertThat(replayedStart.success()).as(replayedStart.error()).isTrue();
        assertThat(mapper.readTree(replayedStart.content()).path("dispatch").isEmpty()).isTrue();
        assertThat(plans.events(firstId, 0, 50)).extracting("type")
                .filteredOn("plan.agent_started"::equals).hasSize(1);

        ToolRequest replan = new ToolRequest("tool-plan-replan-owned", expert.id(), "replan_plan",
                Map.of("plan_id", firstId, "reason", "refine", "raw_plan_json", raw), "plan-replan-key");
        assertThat(provider.execute(replan).success()).isTrue();
        assertThat(provider.execute(replan).success()).isTrue();
        assertThat(plans.findPlan(firstId).orElseThrow().version()).isEqualTo(2);

        var otherSession = runtime.createSession("other expert", "project-a");
        var otherExpert = runtime.createRun(otherSession.id(), "change plan", "disabled", "", List.of(),
                null, "other-profile", 0, 0);
        var denied = provider.execute(new ToolRequest("tool-plan-replan", otherExpert.id(), "replan_plan",
                Map.of("plan_id", firstId, "reason", "take over", "raw_plan_json", raw), "deny-key"));
        assertThat(denied.success()).isFalse();
        assertThat(denied.error()).contains("own or assigned Plan");

        var ordinarySession = runtime.createSession("ordinary", "project-a");
        var ordinary = runtime.createRun(ordinarySession.id(), "ordinary");
        var unavailable = provider.execute(new ToolRequest("tool-plan-list", ordinary.id(), "list_plans",
                Map.of(), "list-key"));
        assertThat(unavailable.success()).isFalse();
        assertThat(unavailable.error()).contains("require an Agent Profile");
    }
}
