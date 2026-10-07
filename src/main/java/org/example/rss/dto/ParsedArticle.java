package org.example.rss.dto;

import java.time.Instant;

/**
 * A feed entry normalized independently of RSS/Atom and persistence.
 * description and content are plain text; content is only what the feed supplied,
 * not a guarantee that the entire article is present. Optional fields may be null.
 */
public record ParsedArticle(
        String externalId,
        String title,
        String link,
        String description,
        Instant publishedAt,
        String content
) {}