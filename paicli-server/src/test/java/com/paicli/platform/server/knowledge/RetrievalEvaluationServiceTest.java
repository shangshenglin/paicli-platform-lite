package com.paicli.platform.server.knowledge;

import com.paicli.platform.server.config.PlatformProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalEvaluationServiceTest {
    @TempDir Path tempDir;

    @Test
    void computesAllAblationMetricsFromLabelledCitations() {
        KnowledgeService knowledge = new KnowledgeService(new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local"));
        knowledge.upsert("alpha", "runtime.md",
                "Tool calls must be persisted before execution. Recovery reuses the idempotency key.");
        knowledge.upsert("alpha", "console.md", "The console uses a bounded grid layout.");
        RetrievalEvaluationService evaluations = new RetrievalEvaluationService(knowledge);

        var report = evaluations.evaluate(new RetrievalEvaluationService.EvaluationRequest("alpha", List.of(
                new RetrievalEvaluationService.EvaluationCase("durability", "persisted tool call recovery",
                        List.of("runtime.md#chunk-1"), List.of("runtime.md#chunk-1")))));

        assertThat(report.caseCount()).isEqualTo(1);
        assertThat(report.ablations()).containsKeys("BM25", "EMBEDDING", "BM25+EMBEDDING+RRF",
                "BM25+EMBEDDING+RRF+RERANK");
        var rerank = report.ablations().get("BM25+EMBEDDING+RRF+RERANK");
        assertThat(rerank.recallAt5()).isEqualTo(1);
        assertThat(rerank.recallAt10()).isEqualTo(1);
        assertThat(rerank.mrr()).isEqualTo(1);
        assertThat(rerank.ndcgAt10()).isEqualTo(1);
        assertThat(rerank.citationHitRate()).isPositive();
        assertThat(rerank.answerGroundedRate()).isEqualTo(1);
    }
}
