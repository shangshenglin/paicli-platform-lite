package com.paicli.platform.server.model;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 离线模型适配器。它让 Runtime、SSE、工具幂等和恢复链路无需模型 API Key 即可验收。
 * 生产环境可通过配置切换为 OpenAI-compatible provider，Agent Runtime 不需要改动。
 */
@Component
@ConditionalOnProperty(prefix = "paicli.model", name = "provider", havingValue = "demo", matchIfMissing = true)
public class DemoModelClient implements ModelClient {
    @Override
    public ModelResponse complete(String runId, ModelRequest request, ModelStreamListener listener) {
        List<ModelMessage> messages = request.messages();
        if (messages == null || messages.isEmpty()) {
            return ModelResponse.text("No input was provided.");
        }
        ModelMessage last = messages.get(messages.size() - 1);
        if ("tool".equals(last.role())) {
            String content = "工具执行完成。结果如下：\n" + last.content();
            listener.onContentDelta(content);
            return ModelResponse.text(content);
        }

        String input = last.content() == null ? "" : last.content().trim();
        if (input.equalsIgnoreCase("/tool list") || input.equalsIgnoreCase("list files")) {
            return ModelResponse.tool(callId(), "list_dir", Map.of("path", "."));
        }
        if (input.startsWith("/tool read ")) {
            return ModelResponse.tool(callId(), "read_file", Map.of("path", input.substring(11).trim()));
        }
        if (input.startsWith("/tool exec ")) {
            return ModelResponse.tool(callId(), "execute_command", Map.of("command", input.substring(11).trim()));
        }
        if (input.startsWith("/tool write ")) {
            String payload = input.substring(12).trim();
            int separator = payload.indexOf('|');
            if (separator <= 0) {
                return ModelResponse.text("Usage: /tool write relative/path.txt|content");
            }
            return ModelResponse.tool(callId(), "write_file", Map.of(
                    "path", payload.substring(0, separator).trim(),
                    "content", payload.substring(separator + 1)));
        }
        String content = "PaiCLI Platform Lite 已收到：" + input
                + "\n\n当前使用离线 DemoModelClient；接入真实模型后这里会进入标准 ReAct 决策。"
        ;
        listener.onContentDelta(content);
        return ModelResponse.text(content);
    }

    @Override
    public String name() {
        return "demo";
    }

    private static String callId() {
        return "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
