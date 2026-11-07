package org.example.rss.dto;

public record SummaryGroupProgress(boolean running, boolean stopRequested, Long preferenceId,
                                   Integer preferenceVersion, String phase, int totalArticles,
                                   int classifiedArticles, int totalGroups, int completedGroups,
                                   int failedGroups, int active, int parallelism, long readySummaries,
                                   boolean hasGroups, String message) {}
