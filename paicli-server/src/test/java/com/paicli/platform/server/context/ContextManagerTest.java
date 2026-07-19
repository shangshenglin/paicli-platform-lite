package com.paicli.platform.server.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.config.ModelProperties;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.prompt.PromptAssembler;
import com.paicli.platform.server.store.ProductivityStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import com.paicli.platform.server.tool.ToolCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ContextManagerTest {
    @TempDir
    Path tempDir;

    @Test
    void injectsOnlyExplicitMemoriesFromSessionProject() throws Exception {
        PlatformProperties platform = new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        ModelProperties model = new ModelProperties("demo", "", "", "demo", 128_000, 4_096,
                0.75, 6, 16_000, 60, "auto", "");
        SqliteRuntimeStore store = new SqliteRuntimeStore(platform);
        store.initialize();
        ObjectMapper mapper = new ObjectMapper();
        ContextManager manager = new ContextManager(store, new PromptAssembler(platform), new ToolCatalog(),
                new ConversationCompactor(store, new ExtractiveSummarizer(), model, mapper), model, platform, mapper);
        var session = store.createSession("agent", "alpha");
        Files.createDirectories(tempDir.resolve("projects/alpha"));
        Files.writeString(tempDir.resolve("projects/alpha/AGENTS.md"), "Use Java 17 and keep changes small.");
        Files.writeString(tempDir.resolve("projects/alpha/PAI.md"), "Answer project questions in Chinese.");
        store.createMemory("alpha", "language", "Always answer in Chinese", "preference");
        store.createMemory("beta", "secret", "must not leak", "test");
        var run = store.createRun(session.id(), "hello", "enabled", "max");
        var toolPlan = new com.paicli.platform.server.model.ModelResponse.ToolPlan(
                "call_reasoning", "list_dir", java.util.Map.of("path", "."));
        store.appendAssistantToolCall(session.id(), run.id(), "I will inspect files", "deep reasoning",
                mapper.writeValueAsString(java.util.List.of(toolPlan)));
        store.appendToolResult(session.id(), run.id(), "call_reasoning", "README.md");

        var messages = manager.prepare(session.id(), run.id()).request().messages();
        var request = manager.prepare(session.id(), run.id()).request();
        assertThat(request.thinkingMode()).isEqualTo("enabled");
        assertThat(request.reasoningEffort()).isEqualTo("max");
        assertThat(request.messages().stream().map(message -> message.content()).toList())
                .anyMatch(value -> value.contains("never pass the host absolute path"));
        assertThat(messages.stream().map(message -> message.content()).toList())
                .anyMatch(value -> value.contains("[language] Always answer in Chinese"))
                .anyMatch(value -> value.contains("Use Java 17 and keep changes small."))
                .anyMatch(value -> value.contains("Answer project questions in Chinese."))
                .noneMatch(value -> value.contains("must not leak"));
        assertThat(messages.stream().filter(message -> "assistant".equals(message.role())).toList())
                .singleElement().satisfies(message -> {
                    assertThat(message.reasoningContent()).isEqualTo("deep reasoning");
                    assertThat(message.toolCalls()).containsExactly(toolPlan);
                });
    }

    @Test
    void appliesAgentProfilePromptAndToolAllowList() throws Exception {
        PlatformProperties platform = new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        ModelProperties model = new ModelProperties("demo", "", "", "demo", 128_000, 4_096,
                0.75, 6, 16_000, 60, "auto", "");
        SqliteRuntimeStore store = new SqliteRuntimeStore(platform);
        store.initialize();
        ObjectMapper mapper = new ObjectMapper();
        ContextManager manager = new ContextManager(store, new PromptAssembler(platform), new ToolCatalog(),
                new ConversationCompactor(store, new ExtractiveSummarizer(), model, mapper), model, platform, mapper);
        var session = store.createSession("agent", "alpha");
        var run = store.createRun(session.id(), "review this code");
        var agent = new ProductivityStore.AgentProfile("agent_1", "alpha", "Code Reviewer",
                "Reviews code", "Only review correctness and risk.", null,
                "[\"read_file\"]", "[]", "summary, risks", "REVIEWER", "MANUAL",
                "PROJECT", "INHERIT", "reviewer", 1, true, Instant.now(), Instant.now());

        var request = manager.prepare(session.id(), run.id(), 128_000, 4_096, agent).request();

        assertThat(request.messages().stream().map(message -> message.content()).toList())
                .anyMatch(value -> value.contains("<agent_profile"))
                .anyMatch(value -> value.contains("Only review correctness and risk."))
                .anyMatch(value -> value.contains("summary, risks"));
        assertThat(request.tools()).extracting("name").containsExactly("read_file");
    }
}
