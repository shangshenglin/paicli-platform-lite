package com.paicli.platform.server.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.store.EvaluationStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationStarterPackServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void installsCompleteStarterPackIdempotentlyWithoutOverwritingExistingCases() throws Exception {
        PlatformProperties properties = new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        new SqliteRuntimeStore(properties).initialize();
        EvaluationStore store = new EvaluationStore(properties);
        EvaluationStarterPackService service = new EvaluationStarterPackService(store, new ObjectMapper());

        var first = service.install("starter-project");
        assertThat(first.version()).isEqualTo("1.3.0");
        assertThat(first.totalSuites()).isEqualTo(8);
        assertThat(first.totalCases()).isEqualTo(36);
        assertThat(first.installedSuites()).isEqualTo(8);
        assertThat(first.installedCases()).isEqualTo(36);
        assertThat(first.skippedCases()).isZero();

        var suites = store.suites("starter-project");
        assertThat(suites).extracting(EvaluationStore.EvaluationSuite::name)
                .containsExactlyInAnyOrder("官方·01 基础行为与安全", "官方·02 工具与审批",
                        "官方·03 上下文与受管能力", "官方·04 稳定性与预算",
                        "官方·05 Plan DAG 与验证", "官方·06 Context 与 Memory Harness",
                        "官方·07 AgentTeam 协作 Harness", "官方·08 Harness Loop");
        var advanced = suites.stream().filter(value -> value.name().contains("上下文")).findFirst().orElseThrow();
        assertThat(store.cases(advanced.id())).hasSize(6).allMatch(value -> !value.enabled());
        var harnessLoop = suites.stream().filter(value -> value.name().contains("Harness Loop")).findFirst().orElseThrow();
        assertThat(store.cases(harnessLoop.id())).hasSize(8).allMatch(EvaluationStore.EvaluationCase::enabled);
        var harness = suites.stream().filter(value -> value.name().contains("Memory Harness")).findFirst().orElseThrow();
        assertThat(store.cases(harness.id())).hasSize(6).allMatch(value -> !value.enabled());
        var teamHarness = suites.stream().filter(value -> value.name().contains("AgentTeam")).findFirst().orElseThrow();
        assertThat(store.cases(teamHarness.id())).hasSize(3).allMatch(value -> !value.enabled());

        var second = service.install("starter-project");
        assertThat(second.installedSuites()).isZero();
        assertThat(second.installedCases()).isZero();
        assertThat(second.skippedCases()).isEqualTo(36);
        assertThat(store.suites("starter-project")).hasSize(8);
    }
}
