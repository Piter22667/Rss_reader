package org.example.rss.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class BrightDataWebUnlockerService {
    private static final Logger log = LoggerFactory.getLogger(BrightDataWebUnlockerService.class);

    private final String apiUrl;
    private final String apiToken;
    private final String zone;
    private final HttpClient httpClient;
    private final ExecutorService executorService;

    public BrightDataWebUnlockerService(
            @Value("${brightdata.api.url:https://api.brightdata.com/request}") String apiUrl,
            @Value("${brightdata.api.token}") String apiToken,
            @Value("${brightdata.zone:web_unlocker1}") String zone) {
        this.apiUrl = apiUrl;
        this.apiToken = apiToken;
        this.zone = zone;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(60))
                .build();
        this.executorService = Executors.newFixedThreadPool(10);
    }

    public String fetchMarkdown(String url) {
        try {
            // Bright Data REST API:
            // format: "raw" -> returns content directly in response body
            // data_format: "markdown" -> converts target web page directly to LLM-optimized Markdown
            String jsonBody = "{"
                    + "\"url\":\"" + escapeJson(url) + "\","
                    + "\"zone\":\"" + escapeJson(zone) + "\","
                    + "\"format\":\"raw\","
                    + "\"data_format\":\"markdown\","
                    + "\"render\":\"true\""
                    + "}";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(1000))
                    .header("Authorization", "Bearer " + apiToken)
                    .header("Content-Type", "application/json")
                    .header("x-unblock-data-format", "markdown")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                String raw = response.body();
                String content = extractMarkdownContent(raw);

                if (isMarkdown(content)) {
                    log.info("Successfully fetched Markdown for {}", url);
                    return content;
                } else {
                    log.warn("Bright Data returned HTML instead of Markdown for {}. First 150 chars: {}",
                            url, content != null ? content.substring(0, Math.min(content.length(), 150)) : "null");
                    return content;
                }
            } else {
                log.warn("Bright Data HTTP error for {}: status {}, body: {}", url, response.statusCode(), response.body());
                return null;
            }
        } catch (Exception ex) {
            log.error("Failed to fetch markdown via Bright Data for {}: {}", url, ex.getMessage());
            return null;
        }
    }

    public Map<String, String> fetchMarkdownBatch(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return Collections.emptyMap();
        }

        List<CompletableFuture<Map.Entry<String, String>>> futures = urls.stream()
                .map(url -> CompletableFuture.supplyAsync(() -> {
                    String md = fetchMarkdown(url);
                    return Map.entry(url, md != null ? md : "");
                }, executorService))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        Map<String, String> resultMap = new HashMap<>();
        for (var future : futures) {
            try {
                var entry = future.join();
                if (!entry.getValue().isBlank()) {
                    resultMap.put(entry.getKey(), entry.getValue());
                }
            } catch (Exception ex) {
                log.warn("Error collecting markdown result: {}", ex.getMessage());
            }
        }
        return resultMap;
    }

    public boolean isMarkdown(String content) {
        if (content == null || content.isBlank()) return false;
        String trimmed = content.trim();
        return !trimmed.startsWith("<!DOCTYPE")
                && !trimmed.startsWith("<!doctype")
                && !trimmed.startsWith("<html")
                && !trimmed.startsWith("<HTML");
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String extractMarkdownContent(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.trim();
        if (trimmed.startsWith("{") && trimmed.contains("\"body\"")) {
            int idx = trimmed.indexOf("\"body\"");
            int colon = trimmed.indexOf(":", idx);
            if (colon != -1) {
                int firstQuote = trimmed.indexOf("\"", colon);
                int lastQuote = trimmed.lastIndexOf("\"");
                if (firstQuote != -1 && lastQuote > firstQuote) {
                    return trimmed.substring(firstQuote + 1, lastQuote)
                            .replace("\\n", "\n")
                            .replace("\\\"", "\"")
                            .replace("\\\\", "\\");
                }
            }
        }
        return raw;
    }
}
