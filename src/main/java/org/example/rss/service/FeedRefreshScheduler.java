package org.example.rss.service;

import org.example.rss.model.FeedSource;
import org.example.rss.repository.FeedSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class FeedRefreshScheduler {
    private static final Logger log = LoggerFactory.getLogger(FeedRefreshScheduler.class);

    private final FeedSourceRepository feedSourceRepository;
    private final ArticleImportService articleImportService;
    private final boolean enabled;

    public FeedRefreshScheduler(FeedSourceRepository feedSourceRepository,
                                ArticleImportService articleImportService,
                                @Value("${rss.scheduler.enabled:true}") boolean enabled) {
        this.feedSourceRepository = feedSourceRepository;
        this.articleImportService = articleImportService;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${rss.scheduler.interval-ms:300000}",
            initialDelayString = "${rss.scheduler.initial-delay-ms:60000}")
    public void refreshAllFeeds() {
        if (!enabled) {
            return;
        }
        List<FeedSource> sources = feedSourceRepository.findAllByActiveForSchedulerTrue();
        log.debug("Scheduler refreshing {} feed(s)", sources.size());
        for (FeedSource source : sources) {
            try {
                // Scheduled updates import RSS metadata only, without Bright Data.
                articleImportService.importMetadata(source.getId());
            } catch (Exception ex) {
                log.warn("RSS refresh failed for source {}: {}", source.getId(), ex.getMessage());
            }
        }
    }
}
