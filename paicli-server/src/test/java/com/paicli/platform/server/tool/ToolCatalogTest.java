package com.paicli.platform.server.tool;

import com.paicli.platform.common.ToolEffect;
import com.paicli.platform.common.ToolRequest;
import com.paicli.platform.common.ToolResult;
import com.paicli.platform.server.model.ModelToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCatalogTest {

    @Test
    void searchesAndActivatesDeferredProviderSchemas() {
        ServerToolProvider provider = provider();
        ToolCatalog catalog = new ToolCatalog(List.of(provider));

        assertThat(catalog.definitionsForContext(Set.of(), Set.of())).extracting("name")
                .contains("tool_search")
                .doesNotContain("search_knowledge");
        assertThat(catalog.search("knowledge retrieval", 8, Set.of())).extracting("name")
                .containsExactly("search_knowledge");
        assertThat(catalog.definitionsForContext(Set.of(), Set.of("search_knowledge")))
                .extracting("name").contains("search_knowledge");

        ToolRouter router = new ToolRouter(null, null, List.of(provider), catalog);
        ToolResult result = router.execute(new ToolRequest(
                "call_search", "run", "tool_search",
                Map.of("query", "knowledge retrieval", "limit", 4), "idem"));

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("\"activatedTools\":[\"search_knowledge\"]");
        assertThat(router.effect("tool_search")).isEqualTo(ToolEffect.READ_ONLY);
    }

    private static ServerToolProvider provider() {
        return new ServerToolProvider() {
            @Override public String id() { return "knowledge"; }
            @Override public List<ModelToolDefinition> definitions() {
                return List.of(new ModelToolDefinition(
                        "search_knowledge", "Search indexed project knowledge",
                        Map.of("type", "object", "properties", Map.of())));
            }
            @Override public boolean supports(String toolName) { return "search_knowledge".equals(toolName); }
            @Override public ToolResult execute(ToolRequest request) {
                return ToolResult.success(request.toolCallId(), "ok", 0);
            }
        };
    }
}
