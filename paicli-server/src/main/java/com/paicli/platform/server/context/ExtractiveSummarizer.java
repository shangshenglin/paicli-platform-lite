package com.paicli.platform.server.context;

import com.paicli.platform.server.domain.MessageRecord;
import com.paicli.platform.server.model.ModelClient;
import com.paicli.platform.server.model.ModelMessage;
import com.paicli.platform.server.model.ModelRequest;
import com.paicli.platform.server.model.ModelStreamListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Main-model semantic conversation summarizer with a deterministic availability fallback. */
@Component
public class ExtractiveSummarizer {
    private static final int MAX_INPUT_CHARS = 60_000;
    private final ModelClient modelClient;

    public ExtractiveSummarizer() { this.modelClient = null; }

    @Autowired
    public ExtractiveSummarizer(ModelClient modelClient) { this.modelClient = modelClient; }

    public String summarize(List<MessageRecord> messages, int maxChars) {
        if (modelClient != null && !"demo".equals(modelClient.name())) {
            try {
                String transcript = transcript(messages);
                String prompt = """
                        请将下面的历史对话压缩成可供 Agent 继续工作的结构化摘要。必须保留：
                        1. 用户目标、硬约束与偏好；
                        2. 已完成操作及可验证结果；
                        3. 已做出的技术决策及原因；
                        4. 未完成事项、阻塞和下一步；
                        5. 重要文件、接口、工具调用和错误信息；
                        6. 事实发生顺序，新事实覆盖旧事实时明确写出当前状态。

                        不要逐句复述，不要编造，不要把临时寒暄写入摘要。使用以下固定小节：
                        ## 目标与约束
                        ## 已完成
                        ## 决策与事实
                        ## 未完成与下一步
                        ## 关键引用

                        历史对话：
                        """ + transcript;
                var request = new ModelRequest(List.of(
                        ModelMessage.system("你是 Agent 会话压缩器，只输出忠实、可继续执行的摘要。"),
                        ModelMessage.user(prompt)), List.of(), Math.min(2_048, Math.max(512, maxChars / 3)),
                        "disabled", "");
                var response = modelClient.complete("summary_" + UUID.randomUUID(), request,
                        ModelStreamListener.NO_OP);
                String value = response.content().trim();
                if (!value.isBlank() && !response.hasToolCalls()) {
                    return value.length() > maxChars ? value.substring(0, maxChars) : value;
                }
            } catch (Exception ignored) {
                // Availability is more important than summary quality; deterministic fallback below.
            }
        }
        return deterministic(messages, maxChars);
    }

    private static String transcript(List<MessageRecord> messages) {
        StringBuilder value = new StringBuilder();
        for (MessageRecord message : messages) {
            value.append(message.role().toUpperCase()).append(": ")
                    .append(message.content() == null ? "" : message.content()).append('\n');
            if (message.toolCallsJson() != null && !message.toolCallsJson().isBlank()) {
                value.append("TOOL_CALLS: ").append(message.toolCallsJson()).append('\n');
            }
            value.append('\n');
            if (value.length() >= MAX_INPUT_CHARS) {
                value.setLength(MAX_INPUT_CHARS);
                value.append("\n[older transcript truncated at summary input budget]");
                break;
            }
        }
        return value.toString();
    }

    private static String deterministic(List<MessageRecord> messages, int maxChars) {
        StringBuilder result = new StringBuilder("Conversation summary (deterministic):\n");
        for (MessageRecord message : messages) {
            if (result.length() >= maxChars) break;
            String content = message.content() == null ? "" : message.content().replaceAll("\\s+", " ").trim();
            if (content.length() > 800) content = content.substring(0, 800) + "…";
            result.append("- ").append(message.role()).append(": ").append(content);
            if (message.toolCallsJson() != null && !message.toolCallsJson().isBlank()) {
                result.append(" tool_calls=").append(message.toolCallsJson());
            }
            result.append('\n');
        }
        if (result.length() > maxChars) return result.substring(0, maxChars);
        return result.toString();
    }
}
