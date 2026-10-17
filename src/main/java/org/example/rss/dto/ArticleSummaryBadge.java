package org.example.rss.dto;

import org.example.rss.model.enums.SummaryStatus;

public record ArticleSummaryBadge(Long articleId, SummaryStatus status, String hiddenReason) {}
