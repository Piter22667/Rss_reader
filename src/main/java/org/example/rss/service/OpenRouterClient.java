package org.example.rss.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class OpenRouterClient {
    private final ChatClient chatClient;

    public OpenRouterClient(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * Send a system + user prompt and return the model's text answer.
     *
     * @throws OpenRouterException when the model returns no content or the call fails
     */
    public String complete(String systemPrompt, String userPrompt) {
        try {
            String content = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
            if (content == null || content.isBlank()) {
                throw new OpenRouterException("OpenRouter не повернув текст відповіді.");
            }
            return content.trim();
        } catch (OpenRouterException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new OpenRouterException("Не вдалося звернутися до OpenRouter: " + ex.getMessage(), ex);
        }
    }
}