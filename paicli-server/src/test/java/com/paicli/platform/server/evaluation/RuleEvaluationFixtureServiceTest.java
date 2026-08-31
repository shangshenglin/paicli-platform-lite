package com.paicli.platform.server.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.store.PlanStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleEvaluationFixtureServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void preparesDeterministicReadableWorkspace() throws Exception {
        Path workspaces = tempDir.resolve("workspaces");
        var service = new RuleEvaluationFixtureService(
                new PlatformProperties(tempDir, workspaces, 1, 50, "local"));

        service.prepare("eval-rule_fixture");

        Path root = workspaces.resolve("eval-rule_fixture");
        assertThat(Files.readString(root.resolve("README.md"))).contains("PaiCLI Evaluation Fixture");
        assertThat(Files.readString(root.resolve("AGENTS.md"))).contains("use only requested tools");
        assertThat(Files.readString(root.resolve("tests/README.md"))).contains("Do not claim tests passed");
    }

    @Test
    void rejectsWorkspaceTraversal() {
        var service = new RuleEvaluationFixtureService(
                new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local"));

        assertThatThrownBy(() -> service.prepare("../outside"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preparesAndCleansVersionedPlanAndWorkspaceMemoryState() throws Exception {
        Path workspaces = tempDir.resolve("workspaces");
        PlatformProperties properties = new PlatformProperties(tempDir, workspaces, 1, 50, "local");
        SqliteRuntimeStore runtime = new SqliteRuntimeStore(properties);
        runtime.initialize();
        PlanStore plans = new PlanStore(properties);
        ObjectMapper mapper = new ObjectMapper();
        var service = new RuleEvaluationFixtureService(properties, runtime, plans, null, mapper);
        String owner = "evaluation-fixture-state";
        var session = runtime.createInternalSession("fixture", "eval-project");
        var run = runtime.createRunInWorkspace(session.id(), "prompt", "auto", "", List.of(), null,
                null, 0, 0, "bash", owner);
        String spec = mapper.writeValueAsString(java.util.Map.of(
                "version", "state-v1",
                "files", java.util.Map.of("fixtures/input.txt", "fixture input"),
                "memories", List.of(java.util.Map.of("content", "Java 17", "confidence", 0.99)),
                "sessions", List.of(java.util.Map.of("title", "release decision", "messages", List.of(
                        java.util.Map.of("role", "assistant", "content", "Release window is Wednesday 22:30.")))),
                "plan", java.util.Map.of("objective", "verify", "stepTitle", "check")));

        service.prepare(owner, spec);
        String snapshot = service.prepareState(spec, "eval-project", session.id(), run.id(), owner);

        assertThat(workspaces.resolve(owner).resolve("fixtures/input.txt")).hasContent("fixture input");
        assertThat(snapshot).contains("state-v1", "memoryIds", "planIds", "sessionIds",
                "workspaceFiles", "fixtures/input.txt", "sha256");
        assertThat(plans.plansForSession(session.id(), 10)).hasSize(1);
        assertThat(runtime.searchableSessionMessageCount("eval-project")).isEqualTo(1);

        service.cleanup(snapshot, "eval-project");
        assertThat(plans.plansForSession(session.id(), 10)).isEmpty();
        assertThat(runtime.searchableSessionMessageCount("eval-project")).isZero();
    }
}
