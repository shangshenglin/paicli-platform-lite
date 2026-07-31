package com.paicli.platform.server.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.ToolRequest;
import com.paicli.platform.common.ToolResult;
import com.paicli.platform.common.RunStatus;
import com.paicli.platform.server.config.ModelProperties;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.model.ModelToolDefinition;
import com.paicli.platform.server.plan.PlanToolProvider;
import com.paicli.platform.server.prompt.PromptAssembler;
import com.paicli.platform.server.store.ProductivityStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import com.paicli.platform.server.tool.ServerToolProvider;
import com.paicli.platform.server.tool.ToolCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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
                .anyMatch(value -> value.contains("不得传入宿主机绝对路径"));
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
        ContextManager manager = new ContextManager(store, new PromptAssembler(platform), planToolCatalog(),
                new ConversationCompactor(store, new ExtractiveSummarizer(), model, mapper), model, platform, mapper);
        var session = store.createSession("agent", "alpha");
        var run = store.createRun(session.id(), "review this code");
        var agent = new ProductivityStore.AgentProfile("agent_1", "alpha", "Code Reviewer",
                "Reviews code", "Only review correctness and risk.", null,
                "", "", "bash", "[\"read_file\"]", "[]", "summary, risks", "REVIEWER", "MANUAL",
                "PROJECT", "INHERIT", "reviewer", 1, true, Instant.now(), Instant.now());

        var request = manager.prepare(session.id(), run.id(), 128_000, 4_096, agent).request();

        assertThat(request.messages().stream().map(message -> message.content()).toList())
                .anyMatch(value -> value.contains("<agent_profile"))
                .anyMatch(value -> value.contains("Only review correctness and risk."))
                .anyMatch(value -> value.contains("summary, risks"));
        assertThat(request.tools()).extracting("name")
                .contains("read_file")
                .containsAll(PlanToolProvider.PROFILE_PLAN_TOOLS)
                .doesNotContain("write_file", "execute_command");
    }

    private static ToolCatalog planToolCatalog() {
        ServerToolProvider planTools = new ServerToolProvider() {
            @Override public String id() { return "plan-test"; }
            @Override public List<ModelToolDefinition> definitions() {
                return PlanToolProvider.PROFILE_PLAN_TOOLS.stream()
                        .map(name -> new ModelToolDefinition(name, name, Map.of("type", "object")))
                        .toList();
            }
            @Override public boolean supports(String toolName) {
                return PlanToolProvider.PROFILE_PLAN_TOOLS.contains(toolName);
            }
            @Override public ToolResult execute(ToolRequest request) { return null; }
        };
        return new ToolCatalog(List.of(planTools));
    }

    @Test
    void compactsBeforeContextWindowWhenRunBudgetWouldBeBurnedByRepeatedTurns() throws Exception {
        PlatformProperties platform = new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        ModelProperties model = new ModelProperties("demo", "", "", "demo", 128_000, 4_096,
                0.75, 6, 16_000, 60, "auto", "",
                3, 500, 60, "", 30, 60_000);
        SqliteRuntimeStore store = new SqliteRuntimeStore(platform);
        store.initialize();
        ObjectMapper mapper = new ObjectMapper();
        ContextManager manager = new ContextManager(store, new PromptAssembler(platform), new ToolCatalog(),
                new ConversationCompactor(store, new ExtractiveSummarizer(), model, mapper), model, platform, mapper);
        var session = store.createSession("budget-aware", "alpha");
        var run = store.createRun(session.id(), "continue");
        String bulkyObservation = "tool observation ".repeat(300);
        for (int index = 0; index < 10; index++) {
            store.appendMessage(session.id(), run.id(), "assistant", "step " + index + " " + bulkyObservation);
        }

        var prepared = manager.prepare(session.id(), run.id());

        assertThat(prepared.compaction().compacted()).isTrue();
        assertThat(prepared.compaction().beforeTokens()).isLessThan(128_000);
        assertThat(store.activeMessages(session.id()))
                .anyMatch(message -> "summary".equals(message.role()))
                .anyMatch(message -> message.content().contains("## 目标与硬约束"));
    }

    @Test
    void keepsReusableHistoryBeforeRunDynamicContextAndReportsItsManifest() throws Exception {
        PlatformProperties platform = new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        ModelProperties model = new ModelProperties("demo", "", "", "demo", 128_000, 4_096,
                0.75, 6, 16_000, 60, "auto", "");
        SqliteRuntimeStore store = new SqliteRuntimeStore(platform);
        store.initialize();
        ObjectMapper mapper = new ObjectMapper();
        ContextManager manager = new ContextManager(store, new PromptAssembler(platform), new ToolCatalog(),
                new ConversationCompactor(store, new ExtractiveSummarizer(), model, mapper), model, platform, mapper);
        var session = store.createSession("cacheable", "alpha");
        var previous = store.createRun(session.id(), "previous question");
        store.appendMessage(session.id(), previous.id(), "assistant", "previous answer");
        store.markRunStatus(previous.id(), RunStatus.COMPLETED);
        var current = store.createRun(session.id(), "current question");

        var first = manager.prepare(session.id(), current.id());
        var second = manager.prepare(session.id(), current.id());
        List<String> contents = first.request().messages().stream().map(message -> message.content()).toList();
        int previousAnswer = contents.indexOf("previous answer");
        int runtime = java.util.stream.IntStream.range(0, contents.size())
                .filter(index -> contents.get(index).contains("<runtime_context>")).findFirst().orElseThrow();
        int currentQuestion = contents.indexOf("current question");

        assertThat(previousAnswer).isLessThan(runtime);
        assertThat(runtime).isLessThan(currentQuestion);
        assertThat(first.manifest().priorConversationMessageCount()).isEqualTo(2);
        assertThat(first.manifest().currentRunMessageCount()).isEqualTo(1);
        assertThat(first.manifest().toolDefinitionTokens()).isPositive();
        assertThat(first.manifest().reusablePrefixTokens()).isPositive();
        assertThat(first.manifest().reusablePrefixSha256()).isEqualTo(second.manifest().reusablePrefixSha256());
        assertThat(first.estimatedInputTokens()).isEqualTo(
                TokenEstimator.estimateMessages(first.request().messages())
                        + TokenEstimator.estimateTools(first.request().tools()));
    }

    @Test
    void trimsOptionalMemoryWithinTheUnifiedInputBudget() throws Exception {
        PlatformProperties platform = new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        ModelProperties model = new ModelProperties("demo", "", "", "demo", 3_000, 256,
                0.80, 6, 16_000, 60, "auto", "");
        SqliteRuntimeStore store = new SqliteRuntimeStore(platform);
        store.initialize();
        ObjectMapper mapper = new ObjectMapper();
        ContextManager manager = new ContextManager(store, new PromptAssembler(platform), new ToolCatalog(),
                new ConversationCompactor(store, new ExtractiveSummarizer(), model, mapper), model, platform, mapper);
        var session = store.createSession("bounded", "alpha");
        for (int index = 0; index < 20; index++) {
            store.createMemory("alpha", "memory-" + index, "project fact " + index + " " + "x".repeat(800), "fact");
        }
        var run = store.createRun(session.id(), "use relevant project memory");

        var prepared = manager.prepare(session.id(), run.id());

        assertThat(prepared.manifest().memoryIncluded()).isTrue();
        assertThat(prepared.manifest().memoryTruncated()).isTrue();
        assertThat(prepared.estimatedInputTokens()).isLessThanOrEqualTo(prepared.manifest().hardInputLimit());
        assertThat(prepared.request().messages()).anyMatch(message ->
                message.content().contains("[context truncated to fit the model input budget]"));
    }

    @Test
    void activatesDeferredToolSchemaOnlyAfterToolSearchResult() throws Exception {
        PlatformProperties platform = new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        ModelProperties model = new ModelProperties("demo", "", "", "demo", 128_000, 4_096,
                0.75, 6, 16_000, 60, "auto", "");
        SqliteRuntimeStore store = new SqliteRuntimeStore(platform);
        store.initialize();
        ObjectMapper mapper = new ObjectMapper();
        ContextManager manager = new ContextManager(store, new PromptAssembler(platform), deferredToolCatalog(),
                new ConversationCompactor(store, new ExtractiveSummarizer(), model, mapper), model, platform, mapper);
        var session = store.createSession("deferred tools", "alpha");
        var run = store.createRun(session.id(), "find project knowledge");

        var before = manager.prepare(session.id(), run.id());
        assertThat(before.request().tools()).extracting("name")
                .contains("tool_search")
                .doesNotContain("search_knowledge");

        var search = new com.paicli.platform.server.model.ModelResponse.ToolPlan(
                "call_search", "tool_search", Map.of("query", "knowledge"));
        var searchMessage = store.appendAssistantToolCall(session.id(), run.id(), "", null,
                mapper.writeValueAsString(List.of(search)));
        var searchResult = store.appendToolResult(session.id(), run.id(), "call_search",
                "{\"activatedTools\":[\"search_knowledge\"]}");
        store.archiveAndAddSummary(session.id(), run.id(),
                List.of(searchMessage.id(), searchResult.id()), "tool directory was searched");

        var after = manager.prepare(session.id(), run.id());
        assertThat(after.request().tools()).extracting("name").contains("search_knowledge");
        assertThat(after.manifest().activatedToolNames()).containsExactly("search_knowledge");
        assertThat(after.manifest().toolNames()).contains("tool_search", "search_knowledge");
        assertThat(after.manifest().toolSelectionReasons())
                .containsEntry("tool_search", "core-context-tool")
                .containsEntry("search_knowledge", "tool-search-activation");
        assertThat(after.manifest().sectionTokens()).containsKeys(
                "stable", "priorConversation", "currentRun", "tools");
    }

    private static ToolCatalog deferredToolCatalog() {
        ServerToolProvider deferred = new ServerToolProvider() {
            @Override public String id() { return "knowledge-test"; }
            @Override public List<ModelToolDefinition> definitions() {
                return List.of(new ModelToolDefinition(
                        "search_knowledge", "Search indexed project knowledge",
                        Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string")))));
            }
            @Override public boolean supports(String toolName) { return "search_knowledge".equals(toolName); }
            @Override public ToolResult execute(ToolRequest request) { return null; }
        };
        return new ToolCatalog(List.of(deferred));
    }
}
