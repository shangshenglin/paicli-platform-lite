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
        assertThat(first.version()).isEqualTo("1.0.0");
        assertThat(first.totalSuites()).isEqualTo(5);
        assertThat(first.totalCases()).isEqualTo(19);
        assertThat(first.installedSuites()).isEqualTo(5);
        assertThat(first.installedCases()).isEqualTo(19);
        assertThat(first.skippedCases()).isZero();

        var suites = store.suites("starter-project");
        assertThat(suites).extracting(EvaluationStore.EvaluationSuite::name)
                .containsExactlyInAnyOrder("官方·01 基础行为与安全", "官方·02 工具与审批",
                        "官方·03 上下文与受管能力", "官方·04 稳定性与预算",
                        "官方·05 Plan DAG 与验证");
        var advanced = suites.stream().filter(value -> value.name().contains("上下文")).findFirst().orElseThrow();
        assertThat(store.cases(advanced.id())).hasSize(6).allMatch(value -> !value.enabled());

        var second = service.install("starter-project");
        assertThat(second.installedSuites()).isZero();
        assertThat(second.installedCases()).isZero();
        assertThat(second.skippedCases()).isEqualTo(19);
        assertThat(store.suites("starter-project")).hasSize(5);
    }
}
