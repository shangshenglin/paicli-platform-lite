package com.paicli.platform.server.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.model.ModelClient;
import com.paicli.platform.server.model.ModelRequest;
import com.paicli.platform.server.model.ModelResponse;
import com.paicli.platform.server.model.ModelStreamListener;
import com.paicli.platform.server.store.PlanStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanServiceTest {
    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void createsValidDagAndMarksRootStepsReadyOnStart() throws Exception {
        SqliteRuntimeStore runtime = runtime();
        PlanService service = service(runtime, new JsonModelClient(validPlan()));
        var session = runtime.createSession("plan", "project-a");

        var plan = service.generate(session.id(), null, "inspect project");
        assertThat(plan.status()).isEqualTo("WAITING_APPROVAL");
        var view = service.view(plan.id());
        assertThat(view.steps()).hasSize(3);
        assertThat(view.edges()).hasSize(2);

        service.start(plan.id());
        var steps = service.view(plan.id()).steps();
        assertThat(steps).filteredOn(step -> step.clientId().equals("step_1"))
                .singleElement().satisfies(step -> assertThat(step.status()).isEqualTo("READY"));
        assertThat(steps).filteredOn(step -> step.clientId().equals("step_3"))
                .singleElement().satisfies(step -> assertThat(step.status()).isEqualTo("PENDING"));
    }

    @Test
    void rejectsCyclicPlanBeforePersistence() throws Exception {
        SqliteRuntimeStore runtime = runtime();
        PlanService service = service(runtime, new JsonModelClient(cyclicPlan()));

        assertThatThrownBy(() -> service.generate(null, "default", "cycle"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void recordsReplanRevisionAndReplacesDraftSteps() throws Exception {
        SqliteRuntimeStore runtime = runtime();
        PlanService service = service(runtime, new JsonModelClient(validPlan()));

        var plan = service.generate(null, "default", "first");
        var replanned = service.replan(plan.id(), "narrow scope", oneStepPlan());

        assertThat(replanned.version()).isEqualTo(2);
        assertThat(replanned.status()).isEqualTo("WAITING_APPROVAL");
        assertThat(service.view(plan.id()).steps()).singleElement()
                .satisfies(step -> assertThat(step.title()).isEqualTo("Summarize"));
    }

    private PlanService service(SqliteRuntimeStore runtime, ModelClient model) {
        return new PlanService(new PlanStore(properties()), new PlanParser(mapper), runtime, model, mapper);
    }

    private SqliteRuntimeStore runtime() throws Exception {
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties());
        store.initialize();
        return store;
    }

    private PlatformProperties properties() {
        return new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
    }

    private static String validPlan() {
        return """
                {
                  "objective": "inspect project",
                  "summary": "Read independent inputs and summarize.",
                  "steps": [
                    {"client_id":"read_pom","title":"Read pom","description":"Read pom.xml","type":"INFORMATION_GATHERING","execution_mode":"REACT","dependencies":[],"done_criteria":["pom is available"]},
                    {"client_id":"read_readme","title":"Read README","description":"Read README.md","type":"INFORMATION_GATHERING","execution_mode":"REACT","dependencies":[],"done_criteria":["README is available"]},
                    {"client_id":"summary","title":"Summarize","description":"Summarize findings","type":"SYNTHESIS","execution_mode":"REACT","dependencies":["read_pom","read_readme"],"done_criteria":["summary mentions build and docs"]}
                  ]
                }
                """;
    }

    private static String cyclicPlan() {
        return """
                {
                  "objective": "cycle",
                  "summary": "Invalid graph.",
                  "steps": [
                    {"client_id":"a","title":"A","description":"A","type":"ANALYSIS","dependencies":["b"],"done_criteria":["a"]},
                    {"client_id":"b","title":"B","description":"B","type":"ANALYSIS","dependencies":["a"],"done_criteria":["b"]}
                  ]
                }
                """;
    }

    private static String oneStepPlan() {
        return """
                {
                  "objective": "first",
                  "summary": "One step.",
                  "steps": [
                    {"client_id":"s","title":"Summarize","description":"Summarize only","type":"SYNTHESIS","dependencies":[],"done_criteria":["summary exists"]}
                  ]
                }
                """;
    }

    private record JsonModelClient(String json) implements ModelClient {
        @Override
        public ModelResponse complete(String runId, ModelRequest request, ModelStreamListener listener) {
            return ModelResponse.text(json);
        }

        @Override
        public String name() {
            return "test";
        }
    }
}
