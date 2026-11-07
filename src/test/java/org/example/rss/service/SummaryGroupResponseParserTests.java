package org.example.rss.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummaryGroupResponseParserTests {
    private final SummaryGroupResponseParser parser = new SummaryGroupResponseParser();

    @Test
    void parsesCategoriesAndSkipsGroupsWithOneArticle() {
        String response = """
                [
                  {"category_key":"ai","category_label":"Штучний інтелект","category_description":"Моделі","article_ids":[1,2]},
                  {"category_key":"gadgets","category_label":"Гаджети","article_ids":[3]}
                ]
                """;

        var groups = parser.parseCategories(response, Set.of(1L, 2L, 3L), 2);

        assertEquals(1, groups.size());
        assertEquals("ai", groups.get(0).key());
        assertEquals("Штучний інтелект", groups.get(0).label());
        assertEquals(List.of(1L, 2L), groups.get(0).articleIds());
    }

    @Test
    void rejectsArticleAssignedToTwoCategories() {
        String response = """
                [
                  {"category_key":"ai","category_label":"ШІ","article_ids":[1,2]},
                  {"category_key":"news","category_label":"Новини","article_ids":[2,3]}
                ]
                """;

        assertThrows(IllegalArgumentException.class,
                () -> parser.parseCategories(response, Set.of(1L, 2L, 3L), 2));
    }

    @Test
    void rejectsUnknownArticleId() {
        String response = """
                [{"category_key":"ai","category_label":"ШІ","article_ids":[1,99]}]
                """;

        assertThrows(IllegalArgumentException.class,
                () -> parser.parseCategories(response, Set.of(1L, 2L), 2));
    }

    @Test
    void parsesGroupTextAndStripsCodeFence() {
        String response = """
                ```json
                {"title":"Огляд тижня","summary_text":"Короткий текст огляду."}
                ```
                """;

        var text = parser.parseGroupText(response);

        assertEquals("Огляд тижня", text.title());
        assertEquals("Короткий текст огляду.", text.summaryText());
    }

    @Test
    void rejectsEmptyGroupText() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parseGroupText("{\"title\":\"\",\"summary_text\":\"text\"}"));
    }

    @Test
    void normalizesCategoryKey() {
        String response = """
                [{"category_key":"Artificial Intelligence","category_label":"ШІ","article_ids":[1,2]}]
                """;

        var groups = parser.parseCategories(response, Set.of(1L, 2L), 2);

        assertEquals("artificial-intelligence", groups.get(0).key());
        assertTrue(groups.get(0).key().matches("[a-z0-9-]+"));
    }

    @Test
    void transliteratesCyrillicCategoryKey() {
        String response = """
                [{"category_key":"ШІ","category_label":"Штучний інтелект","article_ids":[1,2]}]
                """;

        var groups = parser.parseCategories(response, Set.of(1L, 2L), 2);

        assertEquals("shi", groups.get(0).key());
    }

    @Test
    void derivesKeyFromLabelWhenKeyIsMissing() {
        String response = """
                [{"category_label":"Політика","article_ids":[1,2]}]
                """;

        var groups = parser.parseCategories(response, Set.of(1L, 2L), 2);

        assertEquals("polityka", groups.get(0).key());
    }
}
