package org.example.rss.dto;

import java.time.Instant;

public record ArticleImportResult(Long sourceId, int imported, int alreadyExists, int skipped, Instant fetchedAt) {}
