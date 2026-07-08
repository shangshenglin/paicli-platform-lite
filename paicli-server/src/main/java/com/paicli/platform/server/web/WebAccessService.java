package com.paicli.platform.server.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.config.WebProperties;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.LinkedHashSet;
import java.util.Set;

@Service
public class WebAccessService {
    private static final Pattern SCRIPT = Pattern.compile("(?is)<(script|style|noscript)[^>]*>.*?</\\1>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern SPACE = Pattern.compile("[ \\t\\x0B\\f\\r]+");
    private final WebProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient client;

    public WebAccessService(WebProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(properties.timeoutSeconds())).build();
    }

    public boolean enabled() { return properties.enabled(); }

    public List<SearchResult> search(String query, int requestedLimit) throws Exception {
        if (!enabled()) throw new IllegalStateException("web access is disabled");
        if (properties.searchUrl().isBlank()) throw new IllegalStateException("web search URL is not configured");
        String value = query == null ? "" : query.trim();
        if (value.isBlank()) throw new IllegalArgumentException("query must not be blank");
        int limit = Math.max(1, Math.min(requestedLimit, 10));
        String separator = properties.searchUrl().contains("?") ? "&" : "?";
        URI uri = URI.create(properties.searchUrl() + separator + "q="
                + URLEncoder.encode(value, StandardCharsets.UTF_8) + "&format=json");
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).GET()
                .timeout(Duration.ofSeconds(properties.timeoutSeconds()))
                .header("Accept", "application/json").header("User-Agent", "PaiCLI-Platform-Lite/0.7");
        if (!properties.apiKey().isBlank()) {
            builder.header(properties.apiKeyHeader(), properties.apiKey());
        }
        byte[] body = sendBounded(builder.build());
        JsonNode root = mapper.readTree(body);
        JsonNode results = root.path("results");
        if (!results.isArray()) throw new IllegalStateException("search provider response has no results array");
        List<SearchResult> values = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode result : results) {
            if (values.size() >= limit) break;
            String url = text(result, "url", "link");
            if (url.isBlank()) continue;
            try {
                URI publicUri = NetworkPolicy.requirePublicHttpUrl(url);
                String canonical = canonical(publicUri);
                if (!seen.add(canonical)) continue;
                values.add(new SearchResult(values.size() + 1, text(result, "title", "name"), canonical,
                        text(result, "content", "snippet", "description"),
                        "[" + (values.size() + 1) + "] " + canonical));
            } catch (IllegalArgumentException ignored) { }
        }
        return List.copyOf(values);
    }

    private static String canonical(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        if ((scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80)) port = -1;
        String path = uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath();
        try {
            return new URI(scheme, uri.getUserInfo(), host, port, path, uri.getQuery(), null).toString();
        } catch (Exception e) {
            return uri.toString();
        }
    }

    public FetchResult fetch(String url) throws Exception {
        if (!enabled()) throw new IllegalStateException("web access is disabled");
        URI current = NetworkPolicy.requirePublicHttpUrl(url);
        for (int redirects = 0; redirects <= 3; redirects++) {
            HttpRequest request = HttpRequest.newBuilder(current).GET()
                    .timeout(Duration.ofSeconds(properties.timeoutSeconds()))
                    .header("Accept", "text/html,text/plain,application/json;q=0.9,*/*;q=0.2")
                    .header("User-Agent", "PaiCLI-Platform-Lite/0.7").build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                response.body().close();
                String location = response.headers().firstValue("location")
                        .orElseThrow(() -> new IllegalStateException("redirect has no location"));
                current = NetworkPolicy.requirePublicHttpUrl(current.resolve(location).toString());
                continue;
            }
            if (status < 200 || status >= 300) {
                response.body().close();
                throw new IllegalStateException("web fetch returned HTTP " + status);
            }
            byte[] bytes;
            try (InputStream input = response.body()) {
                bytes = input.readNBytes(properties.maxResponseChars() * 4 + 1);
            }
            if (bytes.length > properties.maxResponseChars() * 4) {
                throw new IllegalStateException("web response exceeds configured size limit");
            }
            String contentType = response.headers().firstValue("content-type").orElse("");
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (contentType.toLowerCase(Locale.ROOT).contains("html") || text.stripLeading().startsWith("<")) {
                text = htmlToText(text);
            }
            if (text.length() > properties.maxResponseChars()) {
                text = text.substring(0, properties.maxResponseChars()) + "\n[response truncated]";
            }
            return new FetchResult(current.toString(), contentType, text);
        }
        throw new IllegalStateException("too many redirects");
    }

    private byte[] sendBounded(HttpRequest request) throws Exception {
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new IllegalStateException("search provider returned HTTP " + response.statusCode());
        }
        try (InputStream input = response.body()) {
            byte[] bytes = input.readNBytes(properties.maxResponseChars() * 4 + 1);
            if (bytes.length > properties.maxResponseChars() * 4) {
                throw new IllegalStateException("search response exceeds configured size limit");
            }
            return bytes;
        }
    }

    private static String htmlToText(String html) {
        String value = SCRIPT.matcher(html).replaceAll(" ");
        value = value.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)</(p|div|li|h[1-6])>", "\n");
        value = TAG.matcher(value).replaceAll(" ");
        value = value.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
        value = SPACE.matcher(value).replaceAll(" ");
        return value.replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static String text(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("").trim();
            if (!value.isBlank()) return value;
        }
        return "";
    }

    public record SearchResult(int rank, String title, String url, String snippet, String citation) { }
    public record FetchResult(String url, String contentType, String content) { }
}
