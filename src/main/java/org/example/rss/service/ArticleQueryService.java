package org.example.rss.service;

import org.example.rss.dto.ArticleListItem;
import org.example.rss.repository.ArticleRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Service
@Transactional(readOnly = true)
public class ArticleQueryService {
    private static final int PAGE_SIZE = 20;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.of("Europe/Kyiv"));
    private final ArticleRepository articles;
    private final FeedSourceService sources;

    public ArticleQueryService(ArticleRepository articles, FeedSourceService sources) {
        this.articles = articles;
        this.sources = sources;
    }

    public Page<ArticleListItem> listOwned(Long sourceId, String email, int page) {
        sources.findOwned(sourceId, email);
        var result = articles.findOwned(sourceId, email, PageRequest.of(Math.max(0, page), PAGE_SIZE));
        if (result.getTotalPages() > 0 && result.getNumber() >= result.getTotalPages()) {
            result = articles.findOwned(sourceId, email, PageRequest.of(result.getTotalPages() - 1, PAGE_SIZE));
        }
        return result.map(article -> new ArticleListItem(article.getId(), article.getTitle(),
                safeLink(article.getLink()), article.getDescription(), formatDate(article.getPublishedAt())));
    }

    public String formatDate(Instant value) {
        return value == null ? null : DATE.format(value);
    }

    private String safeLink(String link) {
        if (link == null) return null;
        try {
            URI uri = URI.create(link);
            return uri.getHost() != null && uri.getUserInfo() == null
                    && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    ? link : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
