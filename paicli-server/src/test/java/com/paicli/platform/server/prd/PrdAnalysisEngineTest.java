package com.paicli.platform.server.prd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.model.DemoModelClient;
import com.paicli.platform.server.store.PrdAnalysisStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.task.SyncTaskExecutor;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PrdAnalysisEngineTest {
    @TempDir
    Path tempDir;

    private PrdAnalysisStore store;
    private PrdAnalysisEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        PlatformProperties properties = new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        new SqliteRuntimeStore(properties).initialize();
        ObjectMapper json = new ObjectMapper();
        store = new PrdAnalysisStore(properties, json);
        PrdAnalysisArtifactService artifacts = new PrdAnalysisArtifactService(properties, json);
        engine = new PrdAnalysisEngine(store, new PrdNodeMapper(json), artifacts,
                new DemoModelClient(), json, new SyncTaskExecutor(),
                new PrdAnalysisStateMachine(), new PrdAnalysisSkillCatalog());
    }

    @Test
    void runsFullPipelineAndProducesHandoffBundleOffline() {
        var job = engine.create("default", "订单分析", """
                # 订单系统
                ## 订单
                **订单** 必须支付后才能发货。
                ## 发货流程
                创建订单 → 支付 → 发货
                """, "{}", 4);

        for (int iteration = 0; iteration < 20; iteration++) {
            var current = store.findJob(job.id()).orElseThrow();
            if ("COMPLETED".equals(current.status())) break;
            assertThat(current.status()).isEqualTo("QUEUED");
            engine.processOneStage(store.claimNext("test-worker").orElseThrow());
        }

        var completed = engine.view(job.id());
        assertThat(completed.job().status()).isEqualTo("COMPLETED");
        assertThat(completed.nodes()).allMatch(node -> "COMPLETED".equals(node.status()));
        assertThat(completed.artifacts()).extracting(PrdAnalysisArtifactService.ArtifactDescriptor::name)
                .contains("domain_analysis.md", "design_index.json", "probe_report.json",
                        "handoff_manifest.json", "strategy_journal.jsonl");
        Path handoff = tempDir.resolve(completed.job().artifactDir()).resolve("handoff_manifest.json");
        assertThat(Files.exists(handoff)).isTrue();
        assertThat(store.actions(job.id())).isNotEmpty()
                .allMatch(action -> "COMPLETED".equals(action.status()));
    }

    @Test
    void waitsForAndPersistsClarificationBeforeReprobe() {
        var job = engine.create("default", "待定规则", "# 规则\n结算时间待定。", "{}", 1);
        for (int iteration = 0; iteration < 20; iteration++) {
            var current = store.findJob(job.id()).orElseThrow();
            if ("AWAITING_USER".equals(current.status())) break;
            assertThat(current.status()).as(current.error()).isEqualTo("QUEUED");
            engine.processOneStage(store.claimNext("test-worker").orElseThrow());
        }
        var waiting = engine.view(job.id());
        assertThat(waiting.job().stage()).isEqualTo("CLARIFY");
        assertThat(waiting.clarifications()).singleElement()
                .extracting(PrdAnalysisStore.Clarification::status).isEqualTo("OPEN");

        var resumed = engine.resolve(job.id(), waiting.clarifications().get(0).id(), "每日 00:00 结算");
        assertThat(resumed.status()).isEqualTo("QUEUED");
        assertThat(resumed.stage()).isEqualTo("PROBE");
    }
}
