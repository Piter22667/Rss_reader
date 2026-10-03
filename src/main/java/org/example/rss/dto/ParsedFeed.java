package org.example.rss.dto;

import java.util.List;

public record ParsedFeed(
        String title,
        String format,
        List<ParsedArticle> articles,
        int skippedEntries
) {
    public ParsedFeed {
        articles = List.copyOf(articles);
    }
}