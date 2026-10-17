package org.example.rss.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

@Service
public class OpenRouterPreferenceService {
    private final OpenRouterClient openRouterClient;
    private final PreferenceService preferenceService;
    private final String systemPrompt;

    public OpenRouterPreferenceService(OpenRouterClient openRouterClient,
                                       PreferenceService preferenceService,
                                       @Value("classpath:prompts/improve-preferences.md") Resource promptResource) {
        this.openRouterClient = openRouterClient;
        this.preferenceService = preferenceService;
        this.systemPrompt = readPrompt(promptResource);
    }

    public String improve(Long feedSourceId, String content, String email) {
        preferenceService.getVersions(feedSourceId, email);
        if (content == null || content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Спершу введіть вподобання для покращення.");
        }
        return openRouterClient.complete(systemPrompt, content.trim());
    }

    private String readPrompt(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("Не вдалося прочитати промпт покращення вподобань", ex);
        }
    }
}