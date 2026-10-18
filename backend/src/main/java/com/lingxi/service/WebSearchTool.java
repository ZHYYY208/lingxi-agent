package com.lingxi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingxi.config.LingxiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class WebSearchTool {
    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);
    private static final Pattern DDG_RESULT = Pattern.compile(
            "class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    private final LingxiProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public WebSearchTool(LingxiProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(6))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public boolean enabled() {
        return properties.webSearchEnabled();
    }

    public WebSearchResponse search(String query) {
        long started = System.currentTimeMillis();
        if (!enabled()) {
            return new WebSearchResponse(false, query,
                    "联网搜索未启用。本次回答仅使用企业知识库和内部经营数据库。",
                    List.of(), "WEB_SEARCH_DISABLED", 0);
        }
        if (properties.tavilyApiKey() != null && !properties.tavilyApiKey().isBlank()) {
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("api_key", properties.tavilyApiKey());
                payload.put("query", query);
                payload.put("search_depth", "basic");
                payload.put("max_results", 4);
                payload.put("include_answer", true);
                HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.tavily.com/search"))
                        .timeout(Duration.ofSeconds(properties.webSearchTimeoutSeconds()))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() >= 400) throw new IllegalStateException("HTTP " + response.statusCode());
                JsonNode root = mapper.readTree(response.body());
                List<SearchResult> results = new ArrayList<>();
                for (JsonNode item : root.path("results")) {
                    results.add(new SearchResult(
                            item.path("title").asText(""),
                            item.path("url").asText(""),
                            truncate(item.path("content").asText(""), 500)
                    ));
                }
                String answer = root.path("answer").asText("");
                String summary = answer.isBlank()
                        ? String.join("；", results.stream().map(SearchResult::title).limit(4).toList())
                        : answer;
                return new WebSearchResponse(true, query, summary, results, "",
                        System.currentTimeMillis() - started);
            } catch (Exception exc) {
                log.warn("Tavily search failed: {}", exc.getMessage());
                return new WebSearchResponse(false, query, "联网搜索暂时不可用，已回退到内部知识库。",
                        List.of(), exc.getMessage(), System.currentTimeMillis() - started);
            }
        }
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://html.duckduckgo.com/html/?q=" + encoded))
                    .timeout(Duration.ofSeconds(properties.webSearchTimeoutSeconds()))
                    .header("User-Agent", "LingxiKB/1.0 (+enterprise knowledge assistant)")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) throw new IllegalStateException("HTTP " + response.statusCode());
            Matcher matcher = DDG_RESULT.matcher(response.body());
            List<SearchResult> results = new ArrayList<>();
            while (matcher.find() && results.size() < 4) {
                String title = TAG.matcher(matcher.group(2)).replaceAll("").strip();
                if (!title.isBlank()) {
                    results.add(new SearchResult(title, matcher.group(1), ""));
                }
            }
            if (results.isEmpty()) {
                return new WebSearchResponse(false, query, "联网搜索没有返回可用的结果。",
                        List.of(), "EMPTY_RESULTS", System.currentTimeMillis() - started);
            }
            return new WebSearchResponse(true, query,
                    String.join("；", results.stream().map(SearchResult::title).toList()),
                    results, "", System.currentTimeMillis() - started);
        } catch (Exception exc) {
            log.warn("Web search failed: {}", exc.getMessage());
            return new WebSearchResponse(false, query, "联网搜索暂时不可用，已回退到内部知识库。",
                    List.of(), exc.getMessage(), System.currentTimeMillis() - started);
        }
    }

    private static String truncate(String value, int max) {
        return value == null ? "" : (value.length() > max ? value.substring(0, max) : value);
    }

    public record SearchResult(String title, String url, String snippet) {
    }

    public record WebSearchResponse(
            boolean available,
            String query,
            String summary,
            List<SearchResult> results,
            String error,
            long latencyMs
    ) {
    }
}
