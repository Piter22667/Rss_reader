package org.example.rss.dto;

import java.util.List;

public record SummaryGroupPage(List<SummaryGroupCard> groups, int totalGroups, int groupedArticles,
                               long readySummaries, Integer preferenceVersion) {}
