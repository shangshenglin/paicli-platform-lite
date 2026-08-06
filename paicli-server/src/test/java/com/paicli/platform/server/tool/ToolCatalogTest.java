package com.paicli.platform.server.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.config.WebProperties;
import com.paicli.platform.server.web.WebAccessService;
import com.paicli.platform.server.web.WebToolProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCatalogTest {

    @Test
    void exposesWebToolsByDefaultWhenWebEnabled() {
        WebProperties webProperties = new WebProperties(
                true, "http://127.0.0.1:8888/search", "", "Authorization", 20, 100_000);
        WebToolProvider web = new WebToolProvider(
                new WebAccessService(webProperties, new ObjectMapper()), new ObjectMapper());
        ToolCatalog catalog = new ToolCatalog(List.of(web));

        var names = catalog.definitionsForContext(Set.of(), Set.of()).stream()
                .map(definition -> definition.name()).toList();

        assertThat(names).contains("web_search", "web_fetch", "github_repo_fetch");
    }

    @Test
    void hidesWebToolsWhenWebDisabled() {
        WebProperties webProperties = new WebProperties(false, "", "", "Authorization", 20, 100_000);
        WebToolProvider web = new WebToolProvider(
                new WebAccessService(webProperties, new ObjectMapper()), new ObjectMapper());
        ToolCatalog catalog = new ToolCatalog(List.of(web));

        var names = catalog.definitionsForContext(Set.of(), Set.of()).stream()
                .map(definition -> definition.name()).toList();

        assertThat(names).doesNotContain("web_search", "web_fetch", "github_repo_fetch");
    }
}
