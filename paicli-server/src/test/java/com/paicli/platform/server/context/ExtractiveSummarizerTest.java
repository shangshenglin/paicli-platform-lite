package com.paicli.platform.server.context;

import com.paicli.platform.server.domain.MessageRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractiveSummarizerTest {

    @Test
    void deterministicFallbackKeepsStructuredWorkingMemorySchemaWithinBudget() {
        List<MessageRecord> messages = List.of(
                message("message_user", "user",
                        "实现上下文预算器，必须保留 Java 17 约束。" + " long requirement".repeat(80), null, 1),
                message("message_tool", "tool",
                        "Tests passed and Context Manifest was persisted.", "call_test", 2),
                message("message_assistant", "assistant",
                        "Assume the optional retrieval block can be trimmed first.", null, 3),
                message("message_failure", "tool",
                        "failed: previous command used an invalid option", "call_failed", 4));

        String summary = new ExtractiveSummarizer().summarize(messages, 2_000);

        assertThat(summary).hasSizeLessThanOrEqualTo(2_000);
        assertThat(summary).containsSubsequence(
                "## 目标与硬约束",
                "## 计划状态",
                "## 已验证事实",
                "## 未验证假设",
                "## 技术决策",
                "## 失败尝试",
                "## 待办与下一步",
                "## 证据引用");
        assertThat(summary).contains("message_id=message_tool")
                .contains("tool_call_id=call_failed");
    }

    private static MessageRecord message(String id, String role, String content,
                                         String toolCallId, long sequence) {
        return new MessageRecord(id, "session", "run", role, content, null,
                toolCallId, null, false, sequence, Instant.parse("2026-01-01T00:00:00Z"));
    }
}
