package org.example.rss.dto;

import org.example.rss.model.enums.SummaryStatus;

public record GeneratedArticleSummary(SummaryStatus status, String generatedTitle,
                                      String summaryText, String hiddenReason) {}
