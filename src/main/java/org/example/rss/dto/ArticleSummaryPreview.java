package org.example.rss.dto;

import org.example.rss.model.enums.SummaryStatus;

public record ArticleSummaryPreview(Long articleId, String articleTitle, Integer preferenceVersion,
                                    SummaryStatus status, String generatedTitle, String summary,
                                    String hiddenReason, boolean downloadedText, boolean reused, String message) {}
