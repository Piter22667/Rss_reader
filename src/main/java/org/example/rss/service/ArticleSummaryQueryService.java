package org.example.rss.service;

import org.example.rss.dto.*;
import org.example.rss.model.Article;
import org.example.rss.model.ArticleSummary;
import org.example.rss.model.enums.SummaryStatus;
import org.example.rss.repository.ArticleSummaryRepository;
import org.example.rss.repository.PreferenceVersionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class ArticleSummaryQueryService {
    private static final int PAGE_SIZE = 10;
    private final FeedSourceService sources;
    private final PreferenceVersionRepository preferences;
    private final ArticleSummaryRepository summaries;
    private final ArticleQueryService articleQuery;

    public ArticleSummaryQueryService(FeedSourceService sources, PreferenceVersionRepository preferences,
                                      ArticleSummaryRepository summaries, ArticleQueryService articleQuery) {
        this.sources = sources;
        this.preferences = preferences;
        this.summaries = summaries;
        this.articleQuery = articleQuery;
    }

    public ArticleSummaryPage list(Long sourceId, Long preferenceId, String email, int requestedPage,
                                    Long focusArticleId, List<Long> visibleArticleIds) {
        sources.findOwned(sourceId, email);
        var preference = preferences.findByIdAndFeedSource_Id(preferenceId, sourceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Версію вподобань не знайдено."));
        int pageNumber = Math.max(0, Math.min(requestedPage, Integer.MAX_VALUE / PAGE_SIZE));
        if (focusArticleId != null) {
            ArticleSummary target = summaries.findByArticle_IdAndPreferenceVersion_Id(focusArticleId, preferenceId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Підсумок не знайдено."));
            if (target.getStatus() != SummaryStatus.OK) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Готового підсумку для цієї статті немає.");
            }
            Article article = target.getArticle();
            Instant date = article.getPublishedAt();
            if (date == null) date = article.getCreatedAt();
            pageNumber = (int) (summaries.countBefore(preferenceId, SummaryStatus.OK, date, focusArticleId) / PAGE_SIZE);
        }
        var page = summaries.findResults(preferenceId, SummaryStatus.OK, PageRequest.of(pageNumber, PAGE_SIZE));
        if (page.getTotalPages() > 0 && page.getNumber() >= page.getTotalPages()) {
            page = summaries.findResults(preferenceId, SummaryStatus.OK, PageRequest.of(page.getTotalPages() - 1, PAGE_SIZE));
        }
        List<ArticleSummaryCard> cards = new ArrayList<>();
        for (ArticleSummary summary : page.getContent()) cards.add(toCard(summary));

        List<ArticleSummaryCard> failures = new ArrayList<>();
        for (ArticleSummary summary : summaries.findByPreferenceVersion_IdAndStatusOrderByCreatedAtDesc(preferenceId, SummaryStatus.FAILED)) {
            failures.add(toCard(summary));
        }
        List<ArticleSummaryBadge> badges = new ArrayList<>();
        if (visibleArticleIds != null && !visibleArticleIds.isEmpty()) {
            if (visibleArticleIds.size() > 20) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Можна запросити мітки не більш як 20 карток.");
            }
            for (ArticleSummary summary : summaries.findByPreferenceVersion_IdAndArticle_IdIn(preferenceId, visibleArticleIds)) {
                badges.add(new ArticleSummaryBadge(summary.getArticle().getId(), summary.getStatus(), summary.getHiddenReason()));
            }
        }
        return new ArticleSummaryPage(cards, failures, badges, page.getNumber(), page.getTotalPages(),
                page.getTotalElements(), summaries.countByPreferenceVersion_IdAndStatus(preferenceId, SummaryStatus.HIDDEN),
                failures.size(), preference.getVersionNumber());
    }

    private ArticleSummaryCard toCard(ArticleSummary summary) {
        Article article = summary.getArticle();
        String title = summary.getGeneratedTitle();
        if (summary.getStatus() != SummaryStatus.OK) title = article.getTitle();
        return new ArticleSummaryCard(article.getId(), title, summary.getSummaryText(), safeLink(article.getLink()),
                articleQuery.formatDate(article.getPublishedAt()));
    }

    private String safeLink(String link) {
        if (link == null) return null;
        try {
            URI uri = URI.create(link);
            if (uri.getHost() != null && uri.getUserInfo() == null
                    && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) return link;
        } catch (IllegalArgumentException ignored) { }
        return null;
    }
}
