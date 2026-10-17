package org.example.rss.service;

import org.example.rss.dto.ArticleSummaryPreview;
import org.example.rss.dto.GeneratedArticleSummary;
import org.example.rss.model.Article;
import org.example.rss.model.ArticleSummary;
import org.example.rss.model.PreferenceVersion;
import org.example.rss.model.enums.SummaryStatus;
import org.example.rss.model.enums.MarkdownStatus;
import org.example.rss.model.enums.Language;
import org.example.rss.model.enums.SummaryLength;
import org.example.rss.model.enums.SummaryStyle;
import org.example.rss.repository.ArticleRepository;
import org.example.rss.repository.ArticleSummaryRepository;
import org.example.rss.repository.FeedSourceRepository;
import org.example.rss.repository.PreferenceVersionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Manual processing of one article, with a saved result per preference version. */
@Service
public class ArticleSummaryService {
    private static final Logger log = LoggerFactory.getLogger(ArticleSummaryService.class);
    private final FeedSourceService sources;
    private final ArticleRepository articles;
    private final PreferenceVersionRepository preferences;
    private final BrightDataWebUnlockerService brightData;
    private final OpenRouterClient openRouter;
    private final TransactionTemplate transaction;
    private final String systemPrompt;
    private final String apiKey;
    private final String modelName;
    private final ArticleSummaryRepository summaries;
    private final FeedSourceRepository sourceRepository;
    private final ArticleSummaryResponseParser responseParser;

    public ArticleSummaryService(FeedSourceService sources, ArticleRepository articles,
                                 PreferenceVersionRepository preferences, BrightDataWebUnlockerService brightData,
                                 OpenRouterClient openRouter, PlatformTransactionManager transactionManager,
                                 ArticleSummaryRepository summaries, FeedSourceRepository sourceRepository,
                                 ArticleSummaryResponseParser responseParser,
                                 @Value("classpath:prompts/summary-preview.md") Resource prompt,
                                 @Value("${spring.ai.openai.api-key:}") String apiKey,
                                 @Value("${spring.ai.openai.chat.model:unknown}") String modelName) throws IOException {
        this.sources = sources;
        this.articles = articles;
        this.preferences = preferences;
        this.brightData = brightData;
        this.openRouter = openRouter;
        this.transaction = new TransactionTemplate(transactionManager);
        this.systemPrompt = prompt.getContentAsString(StandardCharsets.UTF_8);
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.summaries = summaries;
        this.sourceRepository = sourceRepository;
        this.responseParser = responseParser;
    }

    public ArticleSummaryPreview getSaved(Long sourceId, Long preferenceId, String email) {
        sources.findOwned(sourceId, email);
        PreferenceVersion preference = findPreference(sourceId, preferenceId);
        var page = articles.findOwned(sourceId, email, PageRequest.of(0, 1));
        if (page.isEmpty()) return null;
        Article article = page.getContent().get(0);
        ArticleSummary saved = summaries.findByArticle_IdAndPreferenceVersion_Id(article.getId(), preferenceId)
                .orElse(null);
        if (saved == null) return null;
        return result(article, preference, saved, false, true, null);
    }

    private PreferenceVersion findPreference(Long sourceId, Long preferenceId) {
        return preferences.findByIdAndFeedSource_Id(preferenceId, sourceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Збережену версію вподобань для цього джерела не знайдено."));
    }

    public ArticleSummaryPreview summarize(Long sourceId, Long preferenceId, String email, boolean retry, Long articleId) {
        sources.findOwned(sourceId, email);
        PreferenceVersion preference = findPreference(sourceId, preferenceId);

        Article article;
        if (articleId != null) {
            article = articles.findByIdAndFeedSource_IdAndFeedSource_User_Email(articleId, sourceId, email)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Статтю не знайдено."));
        } else {
            var page = articles.findOwned(sourceId, email, PageRequest.of(0, 1));
            if (page.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "У джерелі ще немає статей.");
            }
            article = page.getContent().get(0);
        }

        ArticleSummary saved = summaries.findByArticle_IdAndPreferenceVersion_Id(article.getId(), preferenceId)
                .orElse(null);
        if (saved != null && (saved.getStatus() != SummaryStatus.FAILED || !retry)) {
            return result(article, preference, saved, false, true, null);
        }
        if (apiKey.isBlank()) {
            return failed(article, preference, sourceId, email,
                    "Не налаштовано ключ OpenRouter: spring.ai.openai.api-key.");
        }

        if (article.getFullTextStatus() == MarkdownStatus.FAILED) {
            return failed(article, preference, sourceId, email,
                    "Текст найновішої статті раніше не вдалося отримати (FAILED). Автоматичного повтору немає.");
        }

        String markdown = article.getFullText();
        boolean downloaded = false;
        if (markdown == null || markdown.isBlank()) {
            long downloadStarted = System.nanoTime();
            markdown = brightData.fetchMarkdown(article.getLink());
            log.info("Article {}: Bright Data took {} ms", article.getId(), (System.nanoTime() - downloadStarted) / 1_000_000);
            boolean success = brightData.isMarkdown(markdown);
            saveDownloadedText(article.getId(), sourceId, email, markdown, success);
            if (!success) {
                return failed(article, preference, sourceId, email,
                        "Bright Data не повернув придатний текст статті. Статтю позначено FAILED.");
            }
            downloaded = true;
        }
        if (markdown.length() > 120000) {
            return failed(article, preference, sourceId, email,
                    "Текст перевищує ліміт пробного запуску (120 000 символів). Він збережений, але не надісланий у OpenRouter.");
        }

        String userPrompt = "article_id: " + article.getId() + "\n"
                + buildPrompt(article.getTitle(), preference, markdown);

        String summary;
        long generationStarted = System.nanoTime();
        try {
            summary = openRouter.complete(systemPrompt, userPrompt);
        } catch (OpenRouterException ex) {
            return failed(article, preference, sourceId, email,
                    "Не вдалося отримати підсумок від OpenRouter. Перевірте ключ, доступ до моделі та баланс. Повний текст статті збережено.");
        } finally {
            log.info("Article {}: OpenRouter took {} ms", article.getId(), (System.nanoTime() - generationStarted) / 1_000_000);
        }
        GeneratedArticleSummary generated;
        try {
            generated = responseParser.parse(summary, article.getId());
        } catch (IllegalArgumentException ex) {
            return failed(article, preference, sourceId, email, ex.getMessage());
        }
        ArticleSummary stored = saveSummary(article.getId(), preferenceId, sourceId, email, generated);
        return result(article, preference, stored, downloaded, false, null);
    }

    private ArticleSummaryPreview failed(Article article, PreferenceVersion preference,
                                         Long sourceId, String email, String message) {
        GeneratedArticleSummary failure = new GeneratedArticleSummary(SummaryStatus.FAILED, null, null, null);
        ArticleSummary stored = saveSummary(article.getId(), preference.getId(), sourceId, email, failure);
        return result(article, preference, stored, false, false, message);
    }

    public void checkConfiguration() {
        if (apiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Спочатку налаштуйте spring.ai.openai.api-key.");
        }
    }

    public void recordFailure(Long sourceId, Long preferenceId, String email, Long articleId) {
        sources.findOwned(sourceId, email);
        PreferenceVersion preference = findPreference(sourceId, preferenceId);
        Article article = articles.findByIdAndFeedSource_IdAndFeedSource_User_Email(articleId, sourceId, email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Статтю не знайдено."));
        failed(article, preference, sourceId, email, "Обробку статті завершено з помилкою.");
    }

    private ArticleSummary saveSummary(Long articleId, Long preferenceId, Long sourceId, String email,
                                       GeneratedArticleSummary generated) {
        return transaction.execute(status -> {
            // Serialize short writes; the unique article/version pair remains one row.
            sourceRepository.findOwnedForImport(sourceId, email)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Джерело видалено."));
            ArticleSummary saved = summaries.findByArticle_IdAndPreferenceVersion_Id(articleId, preferenceId)
                    .orElse(null);
            if (saved != null && saved.getStatus() != SummaryStatus.FAILED) return saved;
            if (saved == null) {
                saved = new ArticleSummary();
                saved.setArticle(articles.getReferenceById(articleId));
                saved.setPreferenceVersion(preferences.getReferenceById(preferenceId));
            }
            saved.setStatus(generated.status());
            saved.setGeneratedTitle(generated.generatedTitle());
            saved.setSummaryText(generated.summaryText());
            saved.setHiddenReason(generated.hiddenReason());
            saved.setModelName(modelName);
            saved.setCreatedAt(Instant.now());
            return summaries.saveAndFlush(saved);
        });
    }

    private ArticleSummaryPreview result(Article article, PreferenceVersion preference, ArticleSummary saved,
                                        boolean downloaded, boolean reused, String message) {
        return new ArticleSummaryPreview(article.getId(), article.getTitle(), preference.getVersionNumber(),
                saved.getStatus(), saved.getGeneratedTitle(), saved.getSummaryText(), saved.getHiddenReason(),
                downloaded, reused, message);
    }

    private void saveDownloadedText(Long articleId, Long sourceId, String email, String markdown, boolean success) {
        // The short DB transaction starts only after the external download has finished.
        transaction.executeWithoutResult(status -> {
            sources.findOwned(sourceId, email);
            Article saved = articles.findById(articleId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Статтю видалено."));
            saved.setFullTextAttemptedAt(Instant.now());
            if (success) {
                saved.setFullText(markdown);
                saved.setFullTextStatus(MarkdownStatus.OK);
                saved.setFullTextFetchedAt(Instant.now());
            } else {
                saved.setFullTextStatus(MarkdownStatus.FAILED);
            }
            articles.save(saved);
        });
    }

    private String buildPrompt(String title, PreferenceVersion preference, String markdown) {
        String language = "українська";
        if (preference.getLanguage() == Language.EN) language = "англійська";

        String length = "100–150 слів";
        if (preference.getSummaryLength() == SummaryLength.SHORT) length = "40–70 слів";
        if (preference.getSummaryLength() == SummaryLength.DETAILED) length = "200–300 слів";

        String style = "нейтральний: спокійний виклад фактів без оцінок";
        if (preference.getStyle() == SummaryStyle.SIMPLE) {
            style = "простий: зрозумілі слова, короткі речення, пояснення складних термінів";
        }
        if (preference.getStyle() == SummaryStyle.PROFESSIONAL) {
            style = "професійний: точні формулювання, доречна термінологія, без розмовних висловів";
        }
        return "Мова: " + language + "\nОрієнтовна довжина: " + length + "\nСтиль: " + style
                + "\nДодаткові побажання користувача:\n" + preference.getOriginalContent()
                + "\n\nЗаголовок статті:\n" + title
                + "\n\n<article>\n" + markdown + "\n</article>";
    }
}
