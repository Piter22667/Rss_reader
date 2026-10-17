package org.example.rss.service;

import jakarta.annotation.PreDestroy;
import org.example.rss.dto.ArticleSummaryBatchProgress;
import org.example.rss.dto.ArticleSummaryPreview;
import org.example.rss.model.PreferenceVersion;
import org.example.rss.model.enums.SummaryStatus;
import org.example.rss.repository.ArticleRepository;
import org.example.rss.repository.ArticleSummaryRepository;
import org.example.rss.repository.PreferenceVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutionException;

@Service
public class ArticleSummaryBatchService {
    private static final Logger log = LoggerFactory.getLogger(ArticleSummaryBatchService.class);
    private static final int BATCH_SIZE = 20;
    private final FeedSourceService sources;
    private final PreferenceVersionRepository preferences;
    private final ArticleRepository articles;
    private final ArticleSummaryRepository summaries;
    private final ArticleSummaryPreviewService processor;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final ExecutorService articleExecutor;
    private final int parallelism;
    // Progress is kept in memory; individual results are saved in the database.
    private final Map<Long, Batch> batches = new HashMap<>();
    private final Set<Long> manualRuns = new HashSet<>();
    private volatile boolean stopping;

    public ArticleSummaryBatchService(FeedSourceService sources, PreferenceVersionRepository preferences,
            ArticleRepository articles, ArticleSummaryRepository summaries, ArticleSummaryPreviewService processor,
            @Value("${rss.summary.parallelism:3}") int parallelism) {
        this.sources = sources;
        this.preferences = preferences;
        this.articles = articles;
        this.summaries = summaries;
        this.processor = processor;
        this.parallelism = Math.max(1, Math.min(parallelism, 8));
        this.articleExecutor = Executors.newFixedThreadPool(this.parallelism);
    }

    public synchronized ArticleSummaryBatchProgress start(
            Long sourceId,
            Long preferenceId,
            String email)
    {
        PreferenceVersion preference = checkAccess(sourceId, preferenceId, email);
        if (stopping){
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Застосунок завершує роботу.");
        }

        if (manualRuns.contains(sourceId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Дочекайтеся завершення повторної обробки статті.");
        }

        Batch previous = batches.get(sourceId);
        if (previous != null && previous.running) {
            return snapshot(previous, sourceId, preferenceId, email);
        }
        List<Long> ids = articles.findUnprocessedIds(sourceId, preferenceId, email, PageRequest.of(0, BATCH_SIZE));

        if (ids.isEmpty()){
            return idle(sourceId, preference, email, "Необроблених статей для цієї версії немає.");
        }

        processor.checkConfiguration();
        Batch batch = new Batch(preferenceId, preference.getVersionNumber(), ids.size());
        batches.put(sourceId, batch);
        try {
            executor.execute(() -> process(batch, sourceId, email, ids));
        } catch (RuntimeException ex) {
            batches.remove(sourceId);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Не вдалося запустити обробку.");
        }
        return snapshot(batch, sourceId, preferenceId, email);
    }

    public synchronized ArticleSummaryBatchProgress status(
            Long sourceId,
            Long preferenceId,
            String email)
    {
        PreferenceVersion preference = checkAccess(sourceId, preferenceId, email);
        Batch batch = batches.get(sourceId);

        if (batch == null || (!batch.running && !batch.preferenceId.equals(preferenceId))) {
            return idle(sourceId, preference, email, "Обробка запускається лише кнопкою.");
        }
        return snapshot(batch, sourceId, preferenceId, email);
    }

    public synchronized void beginManual(Long sourceId, String email) {
        sources.findOwned(sourceId, email);
        Batch batch = batches.get(sourceId);

        if ((batch != null && batch.running) || manualRuns.contains(sourceId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Дочекайтеся завершення поточної порції цього джерела.");
        }
        manualRuns.add(sourceId);
    }

    public synchronized ArticleSummaryBatchProgress requestStop(Long sourceId, Long preferenceId, String email) {
        PreferenceVersion preference = checkAccess(sourceId, preferenceId, email);
        Batch batch = batches.get(sourceId);
        if (batch == null) return idle(sourceId, preference, email, "Активної порції немає.");
        if (batch.running) {
            batch.stopRequested = true;
            batch.message = batch.activeArticles.isEmpty()
                    ? "Зупиняємо порцію. Нові статті не почнуться."
                    : "Очікуємо завершення вже запущених статей. Нові статті не почнуться.";
        }
        return snapshot(batch, sourceId, preferenceId, email);
    }

    public synchronized void endManual(Long sourceId) { manualRuns.remove(sourceId); }

    private PreferenceVersion checkAccess(
            Long sourceId,
            Long preferenceId,
            String email)
    {
        sources.findOwned(sourceId, email);
        return preferences.findByIdAndFeedSource_Id(preferenceId, sourceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Версію вподобань не знайдено."));
    }

    private void process(Batch batch, Long sourceId, String email, List<Long> ids)
    {
        var completed = new ExecutorCompletionService<Void>(articleExecutor);
        int submitted = 0;
        int pending = 0;
        try {
            while (submitted < ids.size() || pending > 0) {
                synchronized (this) {
                    // Keep only a small window of tasks; do not queue the whole portion.
                    while (!stopping && !batch.stopRequested && submitted < ids.size() && pending < parallelism) {
                        Long articleId = ids.get(submitted++);
                        completed.submit(() -> {
                            processArticle(batch, sourceId, email, articleId);
                            return null;
                        });
                        pending++;
                    }
                }
                if (pending == 0) break;
                try {
                    completed.take().get();
                } catch (ExecutionException ex) {
                    log.warn("Summary worker failed for source {}: {}", sourceId, ex.getCause().getClass().getSimpleName());
                }
                pending--;
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (this) {
                batch.running = false;
                if (batch.processed == batch.total) {
                    batch.message = "Порцію завершено.";
                } else if (batch.stopRequested) {
                    batch.message = "Порцію зупинено. Завершені результати збережено; решта статей чекають нового запуску.";
                } else {
                    batch.message = "Обробку перервано. Збережені результати залишилися в БД.";
                }
            }
        }
    }

    private void processArticle(Batch batch, Long sourceId, String email, Long articleId) {
        synchronized (this) {
            // A queued task may start after Stop was pressed: leave that article untouched.
            if (stopping || batch.stopRequested) return;
            batch.activeArticles.add(articleId);
            batch.message = "Отримуємо тексти та створюємо підсумки паралельно.";
        }
        SummaryStatus status = SummaryStatus.FAILED;
        try {
            ArticleSummaryPreview result = processor.preview(sourceId, batch.preferenceId, email, false, articleId);
            status = result.status();
        } catch (Exception ex) {
            log.warn("Summary failed for source {}, article {}: {}", sourceId, articleId, ex.getClass().getSimpleName());
            try {
                processor.recordFailure(sourceId, batch.preferenceId, email, articleId);
            } catch (Exception saveError) {
                log.warn("Could not save FAILED for article {}: {}", articleId, saveError.getClass().getSimpleName());
            }
        } finally {
            synchronized (this) {
                batch.activeArticles.remove(articleId);
                batch.processed++;
                if (status == SummaryStatus.OK) batch.ok++;
                else if (status == SummaryStatus.HIDDEN) batch.hidden++;
                else batch.failed++;
            }
        }
    }

    private ArticleSummaryBatchProgress snapshot(
            Batch batch,
            Long sourceId,
            Long selectedPreferenceId,
            String email)
    {
        return new ArticleSummaryBatchProgress(
                batch.running,
                batch.stopRequested,
                batch.preferenceId,
                batch.versionNumber,
                batch.total,
                batch.processed,
                batch.ok, batch.hidden, batch.failed, batch.activeArticles.stream().findFirst().orElse(null),
                batch.activeArticles.size(), parallelism,
                articles.countUnprocessed(sourceId, selectedPreferenceId, email),
                summaries.countByPreferenceVersion_Id(selectedPreferenceId) > 0, batch.message);
    }

    private ArticleSummaryBatchProgress idle(
            Long sourceId,
            PreferenceVersion preference,
            String email,
            String message)
    {
        return new ArticleSummaryBatchProgress(false, false, preference.getId(), preference.getVersionNumber(),
                0, 0, 0, 0, 0, null, 0, parallelism, articles.countUnprocessed(sourceId, preference.getId(), email),
                summaries.countByPreferenceVersion_Id(preference.getId()) > 0, message);
    }

    @PreDestroy
    public void stop() {
        stopping = true;
        executor.shutdownNow();
        articleExecutor.shutdownNow();
    }

    private static class Batch {
        private final Long preferenceId;
        private final Integer versionNumber;
        private final int total;
        private boolean running = true;
        private boolean stopRequested;
        private int processed;
        private int ok;
        private int hidden;
        private int failed;
        private final Set<Long> activeArticles = new HashSet<>();
        private String message = "Порцію поставлено в чергу.";

        private Batch(Long preferenceId, Integer versionNumber, int total) {
            this.preferenceId = preferenceId;
            this.versionNumber = versionNumber;
            this.total = total;
        }
    }
}
