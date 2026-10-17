package org.example.rss.service;

import org.example.rss.model.Article;
import org.example.rss.model.enums.MarkdownStatus;
import org.example.rss.repository.ArticleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ArticleUpdateService {
    private static final Logger log = LoggerFactory.getLogger(ArticleUpdateService.class);

    public static final int PRIORITY_HIGH = 10;
    public static final int PRIORITY_LOW = 0;

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private final ArticleImportService articleImportService;
    private final BrightDataWebUnlockerService brightDataWebUnlockerService;
    private final ArticleRepository articleRepository;
    private final TransactionTemplate transaction;
    private final int brightDataLimit;
    private final ThreadPoolExecutor executor;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public ArticleUpdateService(ArticleImportService articleImportService,
                                BrightDataWebUnlockerService brightDataWebUnlockerService,
                                ArticleRepository articleRepository,
                                PlatformTransactionManager transactionManager,
                                @Value("${rss.import.brightdata.limit:20}") int brightDataLimit,
                                @Value("${rss.import.brightdata.threads:4}") int threads) {
        this.articleImportService = articleImportService;
        this.brightDataWebUnlockerService = brightDataWebUnlockerService;
        this.articleRepository = articleRepository;
        this.brightDataLimit = Math.max(1, brightDataLimit);
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setTimeout(120);
        int poolSize = Math.max(1, threads);
        this.executor = new ThreadPoolExecutor(poolSize, poolSize, 0L, TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<>());
    }

    public void enqueueRefresh(Long sourceId, boolean includeMetadata, int priority) {
        if (sourceId == null) {
            return;
        }
        if (!inFlight.add(sourceId)) {
            log.debug("Feed {} is already queued for refresh, skipping duplicate", sourceId);
            return;
        }
        try {
            executor.execute(new RefreshTask(sourceId, includeMetadata, priority));
        } catch (RuntimeException ex) {
            inFlight.remove(sourceId);
            throw ex;
        }
    }

    private void refreshFeed(Long sourceId, boolean includeMetadata) {
        try {
            if (includeMetadata) {
                articleImportService.importMetadata(sourceId);
            }
            List<Article> candidates = articleRepository.findMarkdownCandidates(
                    sourceId, MarkdownStatus.FAILED, PageRequest.of(0, brightDataLimit));
            if (candidates.isEmpty()) {
                return;
            }
            List<String> links = candidates.stream().map(Article::getLink).distinct().toList();
            Map<String, String> markdown = brightDataWebUnlockerService.fetchMarkdownBatch(links);
            Instant now = Instant.now();
            transaction.execute(status -> {
                for (Article article : candidates) {
                    String md = markdown.get(article.getLink());
                    article.setFullTextAttemptedAt(now);
                    if (md != null && !md.isBlank()) {
                        article.setFullText(md);
                        article.setFullTextFetchedAt(now);
                        article.setFullTextStatus(MarkdownStatus.OK);
                    } else {
                        article.setFullTextStatus(MarkdownStatus.FAILED);
                    }
                    articleRepository.save(article);
                }
                return null;
            });
        } catch (Exception ex) {
            log.warn("Background refresh failed for source {}: {}", sourceId, ex.getMessage());
        } finally {
            inFlight.remove(sourceId);
        }
    }

    private final class RefreshTask implements Runnable, Comparable<RefreshTask> {
        private final Long sourceId;
        private final boolean includeMetadata;
        private final int priority;
        private final long sequence = SEQUENCE.incrementAndGet();

        private RefreshTask(Long sourceId, boolean includeMetadata, int priority) {
            this.sourceId = sourceId;
            this.includeMetadata = includeMetadata;
            this.priority = priority;
        }

        @Override
        public void run() {
            refreshFeed(sourceId, includeMetadata);
        }

        @Override
        public int compareTo(RefreshTask other) {
            int byPriority = Integer.compare(other.priority, this.priority);
            if (byPriority != 0) {
                return byPriority;
            }
            return Long.compare(this.sequence, other.sequence);
        }
    }
}