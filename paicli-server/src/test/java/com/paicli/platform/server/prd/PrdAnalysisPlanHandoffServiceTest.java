package com.paicli.platform.server.prd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.model.ModelClient;
import com.paicli.platform.server.model.ModelRequest;
import com.paicli.platform.server.model.ModelResponse;
import com.paicli.platform.server.model.ModelStreamListener;
import com.paicli.platform.server.plan.PlanParser;
import com.paicli.platform.server.plan.PlanService;
import com.paicli.platform.server.store.PlanStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrdAnalysisPlanHandoffServiceTest {
    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rejectsIncompleteTaskAndCreatesPlanForCompletedTask() throws Exception {
        SqliteRuntimeStore runtime = new SqliteRuntimeStore(properties());
        runtime.initialize();
        PrdAnalysisStore store = new PrdAnalysisStore(properties(), mapper);
        PlanService plans = new PlanService(new PlanStore(properties()), new PlanParser(mapper),
                runtime, new StubModelClient(), mapper);
        PrdAnalysisPlanHandoffService handoff = new PrdAnalysisPlanHandoffService(store, plans, mapper);
        var session = runtime.createSession("prd-plan", "project-a");
        var task = store.createTask("project-a", "T", "USER", 2, session.id());

        assertThatThrownBy(() -> handoff.createPlan(task.id(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not completed");

        store.updateTaskStatus(task.id(), "COMPLETED", null);
        var plan = handoff.createPlan(task.id(), null);
        assertThat(plan.status()).isEqualTo("WAITING_APPROVAL");
        assertThat(plans.view(plan.id()).steps()).isNotEmpty();
    }

    @Test
    void rejectsWhenBlockingQuestionsRemain() throws Exception {
        SqliteRuntimeStore runtime = new SqliteRuntimeStore(properties());
        runtime.initialize();
        PrdAnalysisStore store = new PrdAnalysisStore(properties(), mapper);
        PlanService plans = new PlanService(new PlanStore(properties()), new PlanParser(mapper),
                runtime, new StubModelClient(), mapper);
        PrdAnalysisPlanHandoffService handoff = new PrdAnalysisPlanHandoffService(store, plans, mapper);
        var session = runtime.createSession("prd-plan", "project-a");
        var task = store.createTask("project-a", "T", "USER", 2, session.id());
        store.insertQuestion(task.id(), "RULE_AMBIGUITY", "BLOCKING", "Q?", "ctx");
        store.updateTaskStatus(task.id(), "COMPLETED", null);

        assertThatThrownBy(() -> handoff.createPlan(task.id(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blocking");
    }

    private PlatformProperties properties() {
        return new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
    }

    private static final class StubModelClient implements ModelClient {
        @Override
        public ModelResponse complete(String runId, ModelRequest request, ModelStreamListener listener) {
            return ModelResponse.text("stub");
        }

        @Override
        public String name() {
            return "stub";
        }
    }
}
