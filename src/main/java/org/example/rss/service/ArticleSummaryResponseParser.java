package org.example.rss.service;

import org.example.rss.dto.GeneratedArticleSummary;
import org.example.rss.model.enums.SummaryStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ArticleSummaryResponseParser {
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    public GeneratedArticleSummary parse(String response, Long articleId) {
        JsonNode root;
        try {
            root = mapper.readTree(response);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("OpenRouter повернув некоректний JSON.", ex);
        }
        if (root == null || !root.isArray() || root.size() != 1 || !root.get(0).isObject()) {
            throw new IllegalArgumentException("Очікується JSON-масив із результатом рівно однієї статті.");
        }
        JsonNode item = root.get(0);
        JsonNode id = item.get("article_id");
        if (id == null || !id.isIntegralNumber() || !id.toString().equals(articleId.toString())) {
            throw new IllegalArgumentException("Відповідь містить неправильний article_id.");
        }
        String status = requiredText(item, "status", 10);
        if ("OK".equals(status)) {
            String title = requiredText(item, "generated_title", 1000);
            String summary = requiredText(item, "summary_text", 50000);
            requireEmpty(item, "hidden_reason");
            return new GeneratedArticleSummary(SummaryStatus.OK, title, summary, null);
        }
        if ("HIDDEN".equals(status)) {
            String reason = requiredText(item, "hidden_reason", 5000);
            requireEmpty(item, "generated_title");
            requireEmpty(item, "summary_text");
            return new GeneratedArticleSummary(SummaryStatus.HIDDEN, null, null, reason);
        }
        throw new IllegalArgumentException("Від моделі очікується статус OK або HIDDEN.");
    }

    private String requiredText(JsonNode item, String field, int maxLength) {
        JsonNode value = item.get(field);
        if (value == null || !value.isTextual() || value.asString().isBlank()) {
            throw new IllegalArgumentException("У відповіді немає непорожнього текстового поля " + field + ".");
        }
        String text = value.asString().trim();
        if (text.length() > maxLength) {
            throw new IllegalArgumentException("Поле " + field + " перевищує допустиму довжину.");
        }
        return text;
    }

    private void requireEmpty(JsonNode item, String field) {
        JsonNode value = item.get(field);
        if (value != null && !value.isNull() && !(value.isTextual() && value.asString().isBlank())) {
            throw new IllegalArgumentException("Поле " + field + " має бути порожнім для цього статусу.");
        }
    }
}
