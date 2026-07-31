package com.paicli.platform.server.context;

import com.paicli.platform.server.domain.MessageRecord;
import com.paicli.platform.server.model.ModelClient;
import com.paicli.platform.server.model.ModelMessage;
import com.paicli.platform.server.model.ModelRequest;
import com.paicli.platform.server.model.ModelStreamListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** Main-model semantic conversation summarizer with a deterministic availability fallback. */
@Component
public class ExtractiveSummarizer {
    private static final int MAX_INPUT_CHARS = 60_000;
    private static final List<String> REQUIRED_SECTIONS = List.of(
            "## 目标与硬约束", "## 计划状态", "## 已验证事实", "## 未验证假设",
            "## 技术决策", "## 失败尝试", "## 待办与下一步", "## 证据引用");
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
                        1. 用户目标、硬约束、输出契约与稳定偏好；
                        2. 当前计划、已完成步骤、进行中步骤与阻塞；
                        3. 工具结果能够验证的事实，以及仍未验证的模型假设；
                        4. 已做出的技术决策及原因；
                        5. 已失败的尝试、错误信息和不得原样重试的路径；
                        6. 未完成事项和下一步；
                        7. 重要文件、接口、Artifact、message id 与 tool call id；
                        8. 事实发生顺序，新事实覆盖旧事实时明确写出当前状态。

                        不要逐句复述，不要编造，不要把临时寒暄写入摘要。所有固定小节都必须出现；
                        没有内容时写“- 无”，不能删除小节：
                        ## 目标与硬约束
                        ## 计划状态
                        ## 已验证事实
                        ## 未验证假设
                        ## 技术决策
                        ## 失败尝试
                        ## 待办与下一步
                        ## 证据引用

                        历史对话：
                        """ + transcript;
                var request = new ModelRequest(List.of(
                        ModelMessage.system("你是 Agent 会话压缩器，只输出忠实、可继续执行的摘要。"),
                        ModelMessage.user(prompt)), List.of(), Math.min(2_048, Math.max(512, maxChars / 3)),
                        "disabled", "");
                var response = modelClient.complete("summary_" + UUID.randomUUID(), request,
                        ModelStreamListener.NO_OP);
                String value = response.content().trim();
                if (!value.isBlank() && !response.hasToolCalls()
                        && value.length() <= maxChars && isStructured(value)) {
                    return value;
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
            value.append("[message_id=").append(message.id()).append(" sequence=")
                    .append(message.sequence()).append(" role=").append(message.role()).append("]\n")
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
        StringBuilder goals = new StringBuilder();
        StringBuilder verified = new StringBuilder();
        StringBuilder assumptions = new StringBuilder();
        StringBuilder failures = new StringBuilder();
        StringBuilder references = new StringBuilder();
        for (MessageRecord message : messages) {
            String content = message.content() == null ? "" : message.content().replaceAll("\\s+", " ").trim();
            if (content.length() > 600) content = content.substring(0, 600) + "…";
            String line = "- " + (content.isBlank() ? "无内容" : content) + "\n";
            if ("user".equals(message.role())) {
                goals.append(line);
            } else if ("tool".equals(message.role())) {
                if (content.toLowerCase().contains("error") || content.toLowerCase().contains("failed")) {
                    failures.append(line);
                } else {
                    verified.append(line);
                }
            } else if ("assistant".equals(message.role())) {
                assumptions.append(line);
            }
            references.append("- message_id=").append(message.id())
                    .append(" sequence=").append(message.sequence());
            if (message.toolCallId() != null) references.append(" tool_call_id=").append(message.toolCallId());
            references.append('\n');
        }
        List<String> values = List.of(
                section(goals),
                "- 未提供独立计划快照；继续依据当前 Run/Plan 状态执行。\n",
                section(verified),
                section(assumptions),
                "- 无\n",
                section(failures),
                "- 继续完成用户当前目标，并在执行后验证结果。\n",
                section(references));
        return boundedStructured(values, maxChars);
    }

    private static boolean isStructured(String value) {
        int cursor = -1;
        for (String section : REQUIRED_SECTIONS) {
            int found = value.indexOf(section);
            if (found <= cursor) return false;
            cursor = found;
        }
        return true;
    }

    private static String section(StringBuilder value) {
        return value.isEmpty() ? "- 无\n" : value.toString();
    }

    private static String boundedStructured(List<String> values, int maxChars) {
        int structuralChars = REQUIRED_SECTIONS.stream().mapToInt(String::length).sum()
                + REQUIRED_SECTIONS.size() * 4;
        int contentBudget = Math.max(0, maxChars - structuralChars);
        int remainingSections = REQUIRED_SECTIONS.size();
        StringBuilder result = new StringBuilder(Math.max(0, maxChars));
        for (int index = 0; index < REQUIRED_SECTIONS.size(); index++) {
            if (index > 0) result.append('\n');
            result.append(REQUIRED_SECTIONS.get(index)).append('\n');
            int allowance = remainingSections == 0 ? 0 : contentBudget / remainingSections;
            String content = values.get(index);
            if (allowance >= 4) {
                if (content.length() <= allowance) {
                    result.append(content);
                    contentBudget -= content.length();
                } else {
                    String suffix = "\n- [本节因摘要字符预算截断]\n";
                    int prefixLength = Math.max(0, allowance - suffix.length());
                    result.append(content, 0, Math.min(prefixLength, content.length())).append(suffix);
                    contentBudget -= Math.min(allowance, prefixLength + suffix.length());
                }
            }
            remainingSections--;
        }
        if (result.length() <= maxChars) return result.toString();
        return result.substring(0, Math.max(0, maxChars));
    }
}
