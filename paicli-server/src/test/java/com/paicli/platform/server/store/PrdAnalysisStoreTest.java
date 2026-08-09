package com.paicli.platform.server.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.config.PlatformProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PrdAnalysisStoreTest {
    @TempDir
    Path tempDir;

    private PlatformProperties properties;
    private PrdAnalysisStore store;

    @BeforeEach
    void setUp() throws Exception {
        properties = new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore runtime = new SqliteRuntimeStore(properties);
        runtime.initialize();
        store = new PrdAnalysisStore(properties, new ObjectMapper());
    }

    @Test
    void persistsJobNodesAtomicGlobalIdsClarificationsAndEvents() {
        var job = store.createJob("demo", "订单 PRD", "# 订单\n必须支付后发货", "{}", "{}",
                "prd-analysis/{jobId}/output");
        assertThat(job.artifactDir()).contains(job.id());
        assertThat(store.claimNext("worker-1")).get().extracting(PrdAnalysisStore.AnalysisJob::status)
                .isEqualTo("RUNNING");

        store.replaceNodes(job.id(), List.of(
                new PrdAnalysisStore.NodeDraft("N001", 1, "订单", 1, 1, 2,
                        "# 订单\n必须支付后发货", "[]", "[\"root\"]"),
                new PrdAnalysisStore.NodeDraft("N002", 2, "支付", 2, 3, 4,
                        "## 支付\n支付成功", "[\"N001\"]", "[]")));
        var nodes = store.nodes(job.id());
        store.persistNodeAction(job.id(), nodes.get(0).id(), "call-1", "submit_node_result",
                "{\"entities\":[]}", job.id() + ":node-1");
        store.persistNodeAction(job.id(), nodes.get(0).id(), "call-duplicate", "submit_node_result",
                "{\"entities\":[1]}", job.id() + ":node-1");
        assertThat(store.actions(job.id())).singleElement()
                .extracting(PrdAnalysisStore.AnalysisAction::providerCallId).isEqualTo("call-1");
        store.commitNodeAnalysis(nodes.get(0).id(), """
                {"entities":[{"id":"E001","name":"订单"}],
                 "rules":[{"id":"R001","name":"发货规则","entity_ref":"E001"}],
                 "flows":[],"condition_matrix":[{"rule_id":"R001"}],
                 "hypotheses":[],"prediction_report":[],"questions":[]}
                """);
        store.commitNodeAnalysis(nodes.get(1).id(), """
                {"entities":[{"id":"E001","name":"支付"}],"rules":[],"flows":[],
                 "condition_matrix":[],"hypotheses":[],"prediction_report":[],"questions":[]}
                """);

        assertThat(store.items(job.id())).extracting(PrdAnalysisStore.AnalysisItem::itemId)
                .containsExactly("E001", "E002", "R001");
        assertThat(store.nodes(job.id()).get(1).analysisJson()).contains("\"id\":\"E002\"")
                .contains("\"local_id\":\"E001\"");
        assertThat(store.actions(job.id())).singleElement()
                .extracting(PrdAnalysisStore.AnalysisAction::status).isEqualTo("COMPLETED");

        var question = store.upsertClarification(job.id(), "Q_PRB", "P1", "ORPHAN_FIELD",
                "字段 x 未使用", "fingerprint-x");
        store.upsertClarification(job.id(), "Q_PRB", "P0", "ORPHAN_FIELD",
                "字段 x 是否废弃", "fingerprint-x");
        assertThat(store.clarifications(job.id())).hasSize(1);
        assertThat(store.resolveClarification(job.id(), question.id(), "废弃").status()).isEqualTo("RESOLVED");
        assertThat(store.events(job.id(), 0, 100)).extracting(PrdAnalysisStore.AnalysisEvent::type)
                .contains("analysis.queued", "analysis.claimed", "map.completed", "node.completed",
                        "clarification.resolved");
    }

    @Test
    void recoversInterruptedJobAtDurableStage() throws Exception {
        var job = store.createJob("default", "恢复", "# 恢复", "{}", "{}",
                "prd-analysis/{jobId}/output");
        store.claimNext("crashed-worker").orElseThrow();

        SqliteRuntimeStore restarted = new SqliteRuntimeStore(properties);
        restarted.initialize();

        assertThat(store.findJob(job.id())).get().extracting(PrdAnalysisStore.AnalysisJob::status)
                .isEqualTo("QUEUED");
        assertThat(store.claimNext("new-worker")).get().extracting(PrdAnalysisStore.AnalysisJob::stage)
                .isEqualTo("MAP_PRD");
    }
}
