package com.paicli.platform.server.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.ToolRequest;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SessionSearchToolProviderTest {
    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void searchesHistoricalMessagesWithBm25AndSummarizesBySession() throws Exception {
        SqliteRuntimeStore store = store();
        var relevant = store.createSession("Docker approvals", "project-a");
        var relevantRun = store.createRun(relevant.id(), "How should dangerous Docker commands be approved?");
        store.appendAssistantMessage(relevant.id(), relevantRun.id(),
                "Persist the approval first, then execute the exact Docker command arguments.", null);
        store.completeRun(relevantRun.id());

        var irrelevant = store.createSession("UI polish", "project-a");
        var irrelevantRun = store.createRun(irrelevant.id(), "Improve colors");
        store.appendAssistantMessage(irrelevant.id(), irrelevantRun.id(),
                "The compact layout should keep buttons aligned.", null);
        store.completeRun(irrelevantRun.id());

        var otherProject = store.createSession("Docker notes elsewhere", "project-b");
        var otherRun = store.createRun(otherProject.id(), "Docker approval in another project");
        store.completeRun(otherRun.id());

        var current = store.createSession("Current", "project-a");
        var currentRun = store.createRun(current.id(), "Find our Docker approval history");
        SessionSearchToolProvider provider = new SessionSearchToolProvider(store, mapper);

        var result = provider.execute(new ToolRequest("tool-1", currentRun.id(), "session_search",
                Map.of("query", "docker approval exact command", "top_sessions", 2), "session-search-1"));

        assertThat(result.success()).isTrue();
        var root = mapper.readTree(result.content());
        assertThat(root.path("searchedMessages").asInt()).isGreaterThanOrEqualTo(4);
        assertThat(root.path("results")).hasSize(1);
        assertThat(root.path("results").get(0).path("sessionId").asText()).isEqualTo(relevant.id());
        assertThat(root.path("results").get(0).path("summary").asText()).contains("Docker");
        assertThat(root.path("results").toString()).doesNotContain(otherProject.id());
    }

    @Test
    void exposesSessionSearchToolDefinition() throws Exception {
        SessionSearchToolProvider provider = new SessionSearchToolProvider(store(), mapper);

        assertThat(provider.definitions()).singleElement().satisfies(definition -> {
            assertThat(definition.name()).isEqualTo("session_search");
            assertThat(definition.description()).contains("BM25");
        });
        assertThat(provider.requiresApproval("session_search")).isFalse();
    }

    private SqliteRuntimeStore store() throws Exception {
        SqliteRuntimeStore store = new SqliteRuntimeStore(
                new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local"));
        store.initialize();
        return store;
    }
}
