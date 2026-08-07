package com.paicli.platform.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.ToolRequest;
import com.paicli.platform.common.ToolResult;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.domain.WorkingPlanRecord;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkingPlanTest {
    @TempDir
    Path tempDir;

    @Test
    void serviceCreatesAndRevisesPlan() throws Exception {
        PlatformProperties platform = new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore store = new SqliteRuntimeStore(platform);
        store.initialize();
        WorkingPlanService service = new WorkingPlanService(store, new ObjectMapper());
        var session = store.createSession("plan");
        var run = store.createRun(session.id(), "multi-step");

        WorkingPlanRecord plan = service.update(run.id(), "fix login", List.of(
                new WorkingPlanService.WorkingPlanItem("s1", "reproduce", "COMPLETED", List.of("tool-call-1")),
                new WorkingPlanService.WorkingPlanItem("s2", "patch", "IN_PROGRESS", List.of())), "found the bug");

        assertThat(plan.revision()).isEqualTo(1);
        assertThat(service.latest(run.id())).hasValueSatisfying(value -> {
            assertThat(value.objective()).isEqualTo("fix login");
            assertThat(value.itemsJson()).contains("\"s2\"");
        });

        service.update(run.id(), "fix login", List.of(
                new WorkingPlanService.WorkingPlanItem("s1", "reproduce", "COMPLETED", List.of("tool-call-1")),
                new WorkingPlanService.WorkingPlanItem("s2", "patch", "COMPLETED", List.of("tool-call-9"))), "patch merged");
        assertThat(service.latest(run.id())).hasValueSatisfying(value -> assertThat(value.revision()).isEqualTo(2));
    }

    @Test
    void serviceRejectsBlankObjectiveAndInvalidItems() throws Exception {
        PlatformProperties platform = new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore store = new SqliteRuntimeStore(platform);
        store.initialize();
        WorkingPlanService service = new WorkingPlanService(store, new ObjectMapper());
        var session = store.createSession("plan-errors");
        var run = store.createRun(session.id(), "multi-step");

        assertThatThrownBy(() -> service.update(run.id(), "  ", List.of(
                new WorkingPlanService.WorkingPlanItem("s1", "x", "TODO", List.of())), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("objective");
        assertThatThrownBy(() -> service.update(run.id(), "fix", List.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("items");
        assertThatThrownBy(() -> service.update(run.id(), "fix", List.of(
                new WorkingPlanService.WorkingPlanItem("s1", "x", "DONE", List.of())), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status");
    }

    @Test
    void toolProviderCreatesPlanThroughRequest() throws Exception {
        PlatformProperties platform = new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore store = new SqliteRuntimeStore(platform);
        store.initialize();
        ObjectMapper mapper = new ObjectMapper();
        WorkingPlanService service = new WorkingPlanService(store, mapper);
        WorkingPlanToolProvider provider = new WorkingPlanToolProvider(service, mapper);
        var session = store.createSession("plan-tool");
        var run = store.createRun(session.id(), "multi-step");

        ToolResult result = provider.execute(new ToolRequest("call-1", run.id(), "update_working_plan", Map.of(
                "objective", "write docs",
                "items", List.of(Map.of("id", "d1", "title", "draft", "status", "TODO")),
                "reason", "start"), null));

        assertThat(result.success())
                .withFailMessage(result.error() + " | " + result.content()).isTrue();
        assertThat(result.content()).contains("\"revision\":1");
        assertThat(store.latestWorkingPlan(run.id())).hasValueSatisfying(value ->
                assertThat(value.objective()).isEqualTo("write docs"));
    }
}
