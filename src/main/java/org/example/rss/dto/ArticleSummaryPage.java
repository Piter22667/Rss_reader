package org.example.rss.dto;

import java.util.List;

public record ArticleSummaryPage(List<ArticleSummaryCard> items, List<ArticleSummaryCard> failures,
                                  List<ArticleSummaryBadge> badges, int page, int totalPages,
                                  long ready, long hidden, long failed, Integer preferenceVersion) {}
