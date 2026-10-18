package org.example.rss.dto;

public record ArticleSummaryBatchProgress(boolean running, boolean stopRequested, Long preferenceId, Integer preferenceVersion,
        int total, int processed, int ok, int hidden, int failed, Long currentArticleId, int active, int parallelism,
        long remaining, boolean hasResults, String message) {}
