package org.example.rss.dto;

import java.util.List;

public record SummaryGroupCard(Long groupId, String category, String categoryKey, String title, String text,
                               int articleCount, String dateRangeLabel, List<SummaryGroupArticle> articles) {}
