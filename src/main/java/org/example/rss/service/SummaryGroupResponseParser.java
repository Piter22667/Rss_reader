package org.example.rss.service;

import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class SummaryGroupResponseParser {
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private static final Map<Character, String> CYRILLIC = Map.ofEntries(
            Map.entry('а', "a"), Map.entry('б', "b"), Map.entry('в', "v"),
            Map.entry('г', "h"), Map.entry('ґ', "g"), Map.entry('д', "d"),
            Map.entry('е', "e"), Map.entry('є', "ye"), Map.entry('ж', "zh"),
            Map.entry('з', "z"), Map.entry('и', "y"), Map.entry('і', "i"),
            Map.entry('ї', "yi"), Map.entry('й', "y"), Map.entry('к', "k"),
            Map.entry('л', "l"), Map.entry('м', "m"), Map.entry('н', "n"),
            Map.entry('о', "o"), Map.entry('п', "p"), Map.entry('р', "r"),
            Map.entry('с', "s"), Map.entry('т', "t"), Map.entry('у', "u"),
            Map.entry('ф', "f"), Map.entry('х', "kh"), Map.entry('ц', "ts"),
            Map.entry('ч', "ch"), Map.entry('ш', "sh"), Map.entry('щ', "shch"),
            Map.entry('ь', ""), Map.entry('ю', "yu"), Map.entry('я', "ya"),
            Map.entry('ы', "y"), Map.entry('э', "e"), Map.entry('ё', "yo"),
            Map.entry('ъ', "")
    );

    public record CategoryGroup(String key, String label, String description, List<Long> articleIds) {}
    public record GroupText(String title, String summaryText) {}

    public List<CategoryGroup> parseCategories(String response, Set<Long> allowedArticleIds, int minArticles) {
        JsonNode root = read(response);
        if (root == null || !root.isArray() || root.isEmpty()) {
            throw new IllegalArgumentException("Очікується непорожній JSON-масив із категоріями.");
        }
        if (root.size() > 20) {
            throw new IllegalArgumentException("Модель повернула забагато категорій.");
        }

        List<CategoryGroup> result = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        Set<Long> assigned = new HashSet<>();
        for (JsonNode item : root) {
            if (!item.isObject()) {
                throw new IllegalArgumentException("Кожна категорія має бути JSON-об'єктом.");
            }
            String label = requiredText(item, "category_label", 160);
            JsonNode keyNode = item.get("category_key");
            String rawKey = keyNode != null && keyNode.isTextual() ? keyNode.asString().trim() : null;
            String key = normalizeKey(rawKey);
            if (key.isBlank()) key = normalizeKey(label);
            if (key.isBlank()) key = "category-" + Integer.toUnsignedString(label.hashCode(), 16);
            if (!keys.add(key)) {
                throw new IllegalArgumentException("Модель повернула дубльований category_key.");
            }
            String description = optionalText(item, "category_description", 1000);

            JsonNode ids = item.get("article_ids");
            if (ids == null || !ids.isArray() || ids.isEmpty()) {
                throw new IllegalArgumentException("У категорії немає списку article_ids.");
            }
            List<Long> articleIds = new ArrayList<>();
            for (JsonNode id : ids) {
                if (!id.isIntegralNumber()) {
                    throw new IllegalArgumentException("article_ids має містити лише цілі числа.");
                }
                long value = id.asLong();
                if (!allowedArticleIds.contains(value)) {
                    throw new IllegalArgumentException("Модель повернула невідомий article_id.");
                }
                if (!assigned.add(value)) {
                    throw new IllegalArgumentException("Одна стаття не може належати двом категоріям.");
                }
                articleIds.add(value);
            }
            if (articleIds.size() < minArticles) {
                // Small groups are intentionally skipped: a single article is not a topic overview.
                continue;
            }
            result.add(new CategoryGroup(key, label, description, List.copyOf(articleIds)));
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("Модель не створила жодної категорії з мінімум "
                    + minArticles + " статтями.");
        }
        return result;
    }

    public GroupText parseGroupText(String response) {
        JsonNode root = read(response);
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Очікується JSON-об'єкт із текстом огляду.");
        }
        String title = requiredText(root, "title", 1000);
        String summary = requiredText(root, "summary_text", 50000);
        return new GroupText(title, summary);
    }

    private JsonNode read(String response) {
        try {
            return mapper.readTree(clean(response));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("OpenRouter повернув некоректний JSON.", ex);
        }
    }

    private String clean(String response) {
        String text = response == null ? "" : response.trim();
        if (!text.startsWith("```")) return text;
        int firstLineEnd = text.indexOf('\n');
        if (firstLineEnd >= 0) text = text.substring(firstLineEnd + 1);
        if (text.endsWith("```")) text = text.substring(0, text.length() - 3);
        return text.trim();
    }

    private String normalizeKey(String value) {
        if (value == null) return "";
        StringBuilder latin = new StringBuilder();
        for (char ch : value.toLowerCase(Locale.ROOT).toCharArray()) {
            String mapped = CYRILLIC.get(ch);
            latin.append(mapped == null ? ch : mapped);
        }
        String key = latin.toString()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return key.length() > 80 ? key.substring(0, 80) : key;
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

    private String optionalText(JsonNode item, String field, int maxLength) {
        JsonNode value = item.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) {
            throw new IllegalArgumentException("Поле " + field + " має бути текстом.");
        }
        String text = value.asString().trim();
        if (text.isEmpty()) return null;
        if (text.length() > maxLength) {
            throw new IllegalArgumentException("Поле " + field + " перевищує допустиму довжину.");
        }
        return text;
    }
}
