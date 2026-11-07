package org.example.rss.service;

import org.example.rss.dto.SummaryGroupArticle;
import org.example.rss.dto.SummaryGroupCard;
import org.example.rss.dto.SummaryGroupPage;
import org.example.rss.model.Article;
import org.example.rss.model.ArticleSummary;
import org.example.rss.model.PreferenceVersion;
import org.example.rss.model.SummaryGroup;
import org.example.rss.model.enums.SummaryStatus;
import org.example.rss.repository.ArticleSummaryRepository;
import org.example.rss.repository.PreferenceVersionRepository;
import org.example.rss.repository.SummaryGroupRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class SummaryGroupQueryService {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")
            .withZone(ZoneId.of("Europe/Kyiv"));

    private final FeedSourceService sources;
    private final PreferenceVersionRepository preferences;
    private final SummaryGroupRepository groups;
    private final ArticleSummaryRepository summaries;
    private final ArticleQueryService articleQuery;

    public SummaryGroupQueryService(FeedSourceService sources, PreferenceVersionRepository preferences,
                                    SummaryGroupRepository groups, ArticleSummaryRepository summaries,
                                    ArticleQueryService articleQuery) {
        this.sources = sources;
        this.preferences = preferences;
        this.groups = groups;
        this.summaries = summaries;
        this.articleQuery = articleQuery;
    }

    public SummaryGroupPage list(Long sourceId, Long preferenceId, String email) {
        sources.findOwned(sourceId, email);
        PreferenceVersion preference = preferences.findByIdAndFeedSource_Id(preferenceId, sourceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Версію вподобань не знайдено."));

        List<SummaryGroup> groupRows = groups.findForVersionWithCategory(preferenceId);
        List<ArticleSummary> groupedRows = summaries.findGroupedForVersion(preferenceId);
        Map<Long, List<ArticleSummary>> rowsByGroup = new HashMap<>();
        for (ArticleSummary summary : groupedRows) {
            if (summary.getSummaryGroup() == null) continue;
            rowsByGroup.computeIfAbsent(summary.getSummaryGroup().getId(), ignored -> new ArrayList<>())
                    .add(summary);
        }

        List<SummaryGroupCard> cards = new ArrayList<>();
        for (SummaryGroup group : groupRows) {
            List<ArticleSummary> rows = rowsByGroup.getOrDefault(group.getId(), List.of());
            List<SummaryGroupArticle> articles = new ArrayList<>();
            for (ArticleSummary row : rows) articles.add(toArticle(row));
            cards.add(new SummaryGroupCard(group.getId(), group.getCategory().getLabel(),
                    group.getCategory().getCategoryKey(), group.getTitle(), group.getSummaryText(),
                    group.getArticleCount(), dateRange(rows), articles));
        }

        long ready = summaries.countByPreferenceVersion_IdAndStatus(preferenceId, SummaryStatus.OK);
        return new SummaryGroupPage(cards, cards.size(), groupedRows.size(), ready,
                preference.getVersionNumber());
    }

    private SummaryGroupArticle toArticle(ArticleSummary summary) {
        Article article = summary.getArticle();
        Instant date = article.getPublishedAt() == null ? article.getCreatedAt() : article.getPublishedAt();
        return new SummaryGroupArticle(article.getId(), article.getTitle(),
                articleQuery.safeLink(article.getLink()), articleQuery.formatDate(date));
    }

    private String dateRange(List<ArticleSummary> rows) {
        Instant min = null;
        Instant max = null;
        for (ArticleSummary row : rows) {
            Article article = row.getArticle();
            Instant date = article.getPublishedAt() == null ? article.getCreatedAt() : article.getPublishedAt();
            if (date == null) continue;
            if (min == null || date.isBefore(min)) min = date;
            if (max == null || date.isAfter(max)) max = date;
        }
        if (min == null) return null;
        String from = DATE.format(min);
        String to = DATE.format(max);
        return from.equals(to) ? from : from + "–" + to;
    }
}
