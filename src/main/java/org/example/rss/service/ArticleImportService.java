package org.example.rss.service;

import org.example.rss.dto.ArticleImportResult;
import org.example.rss.dto.ParsedFeed;
import org.example.rss.model.Article;
import org.example.rss.repository.ArticleRepository;
import org.example.rss.repository.FeedSourceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Objects;

@Service
public class ArticleImportService {
    private final FeedSourceService feedSourceService;
    private final FeedReadingService feedReadingService;
    private final FeedSourceRepository sourceRepository;
    private final ArticleRepository articleRepository;
    private final TransactionTemplate transaction;

    public ArticleImportService(FeedSourceService sources, FeedReadingService reader,
                               FeedSourceRepository sourceRepository, ArticleRepository articles,
                               PlatformTransactionManager transactionManager) {
        this.feedSourceService = sources;
        this.feedReadingService = reader;
        this.sourceRepository = sourceRepository;
        this.articleRepository = articles;
        this.transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(30);
    }

    // Network operations run before the database transaction and its row lock.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ArticleImportResult importArticles(Long sourceId, String email) {
        String url = feedSourceService.findOwned(sourceId, email).getUrl();

        ParsedFeed feed = feedReadingService.read(url);

        return Objects.requireNonNull(transaction.execute(status -> save(sourceId, email, url, feed)));
    }

    private ArticleImportResult save(Long sourceId, String email, String url, ParsedFeed feed) {
        var source = sourceRepository.findOwnedForImport(sourceId, email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Джерело не знайдено"));
        if (!Objects.equals(source.getUrl(), url)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Адреса джерела змінилася. Повторіть оновлення");
        }
        int imported = 0, duplicates = 0, skipped = feed.skippedEntries();
        Instant fetchedAt = Instant.now();
        for (var entry : feed.articles()) {
            String guid = entry.externalId();
            if (guid != null && guid.isBlank()) guid = null;
            if (entry.link() == null || entry.link().isBlank() || entry.link().length() > 2048
                    || entry.title() == null || entry.title().isBlank()
                    || (guid != null && guid.length() > 2048)) {
                skipped++;
                continue;
            }
            if (articleRepository.existsInSource(sourceId, guid, entry.link())) {
                duplicates++;
                continue;
            }
            Article article = new Article();

            article.setFeedSource(source);
            article.setGuid(guid);
            article.setTitle(entry.title());
            article.setLink(entry.link());
            article.setDescription(entry.description());
            article.setPublishedAt(entry.publishedAt());
            article.setCreatedAt(fetchedAt);

            articleRepository.save(article);
            imported++;
        }
        source.setLastFetchedAt(fetchedAt);
        articleRepository.flush();
        sourceRepository.flush();
        return new ArticleImportResult(sourceId, imported, duplicates, skipped, fetchedAt);
    }
}
