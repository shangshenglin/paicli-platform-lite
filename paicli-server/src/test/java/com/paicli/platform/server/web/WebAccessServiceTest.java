package com.paicli.platform.server.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.config.WebProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebAccessServiceTest {
    @Test
    void reportsConfiguredSearchEndpointWhenProviderIsUnavailable() {
        WebAccessService service = new WebAccessService(
                new WebProperties(true, "http://127.0.0.1:1/search", "", "", "Authorization", 1, 100_000),
                new ObjectMapper());

        assertThatThrownBy(() -> service.search("spider solitaire", 5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("web search provider is unavailable at http://127.0.0.1:1/search")
                .hasMessageContaining("PAICLI_WEB_SEARCH_URL");
    }

    @Test
    void sendsConfiguredEngineSelectionToSearchProvider() throws Exception {
        AtomicReference<String> query = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] body = "{\"results\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            WebAccessService service = new WebAccessService(new WebProperties(true,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/search", "bing", "",
                    "Authorization", 1, 100_000), new ObjectMapper());

            assertThat(service.search("OpenAI", 5)).isEmpty();
            assertThat(query.get()).contains("q=OpenAI", "format=json", "engines=bing");
        } finally {
            server.stop(0);
        }
    }
}
