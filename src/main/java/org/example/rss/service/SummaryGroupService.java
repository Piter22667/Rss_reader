package org.example.rss.service;

import jakarta.annotation.PreDestroy;
import org.example.rss.dto.SummaryGroupProgress;
import org.example.rss.model.ArticleSummary;
import org.example.rss.model.FeedSource;
import org.example.rss.model.PreferenceVersion;
import org.example.rss.model.SummaryCategory;
import org.example.rss.model.SummaryGroup;
import org.example.rss.model.enums.Language;
import org.example.rss.model.enums.SummaryLength;
import org.example.rss.model.enums.SummaryStatus;
import org.example.rss.model.enums.SummaryStyle;
import org.example.rss.repository.ArticleSummaryRepository;
import org.example.rss.repository.PreferenceVersionRepository;
import org.example.rss.repository.SummaryCategoryRepository;
import org.example.rss.repository.SummaryGroupRepository;
import org.example.rss.service.SummaryGroupResponseParser.CategoryGroup;
import org.example.rss.service.SummaryGroupResponseParser.GroupText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Builds topic overviews from already saved per-article AI summaries.
 * The LLM proposes a stable category taxonomy for the source, then writes one overview per category.
 */
@Service
public class SummaryGroupService {
    private static final Logger log = LoggerFactory.getLogger(SummaryGroupService.class);

    private final FeedSourceService sources;
    private final PreferenceVersionRepository preferences;
    private final ArticleSummaryRepository summaries;
    private final SummaryCategoryRepository categories;
    private final SummaryGroupRepository groups;
    private final OpenRouterClient openRouter;
    private final SummaryGroupResponseParser parser;
    private final SourceProcessingGuard guard;
    private final TransactionTemplate transaction;
    private final String classificationPrompt;
    private final String groupPrompt;
    private final String apiKey;
    private final String modelName;
    private final int maxArticles;
    private final int minArticles;
    private final int maxPerGroup;
    private final int parallelism;
    private final ExecutorService jobExecutor = Executors.newFixedThreadPool(2);
    private final ExecutorService groupExecutor;
    private final Map<Long, Job> jobs = new HashMap<>();
    private volatile boolean stopping;

    public SummaryGroupService(FeedSourceService sources, PreferenceVersionRepository preferences,
                               ArticleSummaryRepository summaries, SummaryCategoryRepository categories,
                               SummaryGroupRepository groups, OpenRouterClient openRouter,
                               SummaryGroupResponseParser parser, SourceProcessingGuard guard,
                               PlatformTransactionManager transactionManager,
                               @Value("classpath:prompts/summary-groups-classify.md") Resource classificationPrompt,
                               @Value("classpath:prompts/summary-group.md") Resource groupPrompt,
                               @Value("${spring.ai.openai.api-key:}") String apiKey,
                               @Value("${spring.ai.openai.chat.model:unknown}") String modelName,
                               @Value("${rss.summary.groups.max-articles:60}") int maxArticles,
                               @Value("${rss.summary.groups.min-articles:2}") int minArticles,
                               @Value("${rss.summary.groups.max-per-group:15}") int maxPerGroup,
                               @Value("${rss.summary.parallelism:3}") int parallelism) throws IOException {
        this.sources = sources;
        this.preferences = preferences;
        this.summaries = summaries;
        this.categories = categories;
        this.groups = groups;
        this.openRouter = openRouter;
        this.parser = parser;
        this.guard = guard;
        this.transaction = new TransactionTemplate(transactionManager);
        this.classificationPrompt = classificationPrompt.getContentAsString(StandardCharsets.UTF_8);
        this.groupPrompt = groupPrompt.getContentAsString(StandardCharsets.UTF_8);
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.maxArticles = Math.max(2, Math.min(maxArticles, 200));
        this.minArticles = Math.max(2, Math.min(minArticles, 10));
        this.maxPerGroup = Math.max(2, Math.min(maxPerGroup, 30));
        this.parallelism = Math.max(1, Math.min(parallelism, 8));
        this.groupExecutor = Executors.newFixedThreadPool(this.parallelism);
    }

    public synchronized SummaryGroupProgress start(Long sourceId, Long preferenceId, String email) {
        PreferenceVersion preference = checkAccess(sourceId, preferenceId, email);
        if (stopping) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Застосунок завершує роботу.");
        }
        Job previous = jobs.get(sourceId);
        if (previous != null && previous.running) {
            return snapshot(previous, preference);
        }
        if (apiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Спочатку налаштуйте spring.ai.openai.api-key.");
        }

        List<ArticleSummary> candidates = summaries.findForGrouping(preferenceId, SummaryStatus.OK,
                PageRequest.of(0, maxArticles));
        if (candidates.size() < minArticles) {
            return idle(preference, "Потрібно щонайменше " + minArticles
                    + " готових AI-підсумків. Обробіть статті у вкладці «AI-підсумки».");
        }

        guard.acquire(sourceId);
        Job job = new Job(preferenceId, preference.getVersionNumber(), candidates.size());
        jobs.put(sourceId, job);
        try {
            jobExecutor.execute(() -> generate(job, sourceId, email, preference, candidates));
        } catch (RuntimeException ex) {
            jobs.remove(sourceId);
            guard.release(sourceId);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Не вдалося запустити створення тематичних оглядів.");
        }
        return snapshot(job, preference);
    }

    public synchronized SummaryGroupProgress status(Long sourceId, Long preferenceId, String email) {
        PreferenceVersion preference = checkAccess(sourceId, preferenceId, email);
        Job job = jobs.get(sourceId);
        if (job == null || (!job.running && !job.preferenceId.equals(preferenceId))) {
            return idle(preference, "Тематичні огляди створюються лише кнопкою.");
        }
        return snapshot(job, preference);
    }

    public synchronized SummaryGroupProgress requestStop(Long sourceId, Long preferenceId, String email) {
        PreferenceVersion preference = checkAccess(sourceId, preferenceId, email);
        Job job = jobs.get(sourceId);
        if (job == null || !job.running) {
            return idle(preference, "Активної генерації оглядів немає.");
        }
        job.stopRequested = true;
        job.message = job.active == 0
                ? "Зупиняємо. Нові огляди не запускатимуться."
                : "Очікуємо завершення вже запущених оглядів. Нові не запускатимуться.";
        return snapshot(job, preference);
    }

    private PreferenceVersion checkAccess(Long sourceId, Long preferenceId, String email) {
        sources.findOwned(sourceId, email);
        return preferences.findByIdAndFeedSource_Id(preferenceId, sourceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Версію вподобань не знайдено."));
    }

    private void generate(Job job, Long sourceId, String email, PreferenceVersion preference,
                          List<ArticleSummary> candidates) {
        try {
            setPhase(job, "clustering", "Визначаємо категорії для статей…");
            List<SummaryCategory> existing = categories.findByFeedSource_IdOrderByLabelAsc(sourceId);
            String classificationUserPrompt = buildClassificationPrompt(preference, existing, candidates);
            Set<Long> allowedIds = new HashSet<>();
            for (ArticleSummary candidate : candidates) allowedIds.add(candidate.getId());

            List<CategoryGroup> parsed = null;
            RuntimeException classificationError = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    String response = openRouter.complete(classificationPrompt, classificationUserPrompt);
                    parsed = parser.parseCategories(response, allowedIds, minArticles);
                    break;
                } catch (OpenRouterException | IllegalArgumentException ex) {
                    classificationError = ex;
                    if (attempt == 0) {
                        log.info("Retrying topic classification for source {}: {}", sourceId,
                                ex.getClass().getSimpleName());
                    }
                }
            }
            if (parsed == null) {
                throw classificationError == null
                        ? new IllegalStateException("Порожня відповідь моделі під час визначення категорій.")
                        : classificationError;
            }
            if (job.stopRequested) {
                abort(job);
                return;
            }
            List<GroupTask> tasks = split(parsed);

            synchronized (this) {
                job.phase = "summaries";
                job.classifiedArticles = parsed.stream().mapToInt(group -> group.articleIds().size()).sum();
                job.totalGroups = tasks.size();
                job.message = "Створюємо тематичні огляди…";
            }

            Map<Long, ArticleSummary> byId = new HashMap<>();
            for (ArticleSummary candidate : candidates) byId.put(candidate.getId(), candidate);
            List<GeneratedGroup> generated = generateTexts(job, preference, tasks, byId);
            if (job.stopRequested) {
                abort(job);
                return;
            }
            if (generated.isEmpty()) {
                throw new IllegalStateException("Не вдалося створити жодного огляду.");
            }

            setPhase(job, "saving", "Зберігаємо огляди…");
            persist(sourceId, email, preference, generated);
            synchronized (this) {
                job.phase = "done";
                job.message = "Готово. Створено оглядів: " + generated.size() + ".";
                job.running = false;
            }
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Topic overview generation failed for source {}: {}", sourceId,
                    ex.getClass().getSimpleName(), ex);
            synchronized (this) {
                job.phase = "error";
                job.message = messageFor(ex);
                job.running = false;
            }
        } finally {
            guard.release(sourceId);
            synchronized (this) {
                if (job.running) job.running = false;
            }
        }
    }

    private List<GeneratedGroup> generateTexts(Job job, PreferenceVersion preference,
                                               List<GroupTask> tasks, Map<Long, ArticleSummary> byId)
            throws InterruptedException {
        ExecutorCompletionService<GeneratedGroup> completion =
                new ExecutorCompletionService<>(groupExecutor);
        List<GeneratedGroup> results = new ArrayList<>();
        int submitted = 0;
        int pending = 0;
        try {
            while (submitted < tasks.size() || pending > 0) {
                synchronized (this) {
                    while (!stopping && !job.stopRequested && submitted < tasks.size()
                            && pending < parallelism) {
                        GroupTask task = tasks.get(submitted++);
                        completion.submit(() -> generateOne(job, preference, task, byId));
                        pending++;
                    }
                }
                if (pending == 0) break;
                Future<GeneratedGroup> future = completion.take();
                pending--;
                try {
                    GeneratedGroup result = future.get();
                    if (result != null) results.add(result);
                } catch (ExecutionException ex) {
                    synchronized (this) {
                        job.failedGroups++;
                    }
                    log.warn("Topic overview worker failed: {}", ex.getCause() == null
                            ? ex.getClass().getSimpleName() : ex.getCause().getClass().getSimpleName());
                }
            }
        } finally {
            // Already submitted tasks are allowed to finish so no work is silently lost.
        }
        return results;
    }

    private GeneratedGroup generateOne(Job job, PreferenceVersion preference, GroupTask task,
                                       Map<Long, ArticleSummary> byId) {
        synchronized (this) {
            if (stopping || job.stopRequested) return null;
            job.active++;
            job.message = "Створюємо огляди: " + job.completedGroups + " з " + job.totalGroups + "…";
        }
        try {
            RuntimeException last = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    String response = openRouter.complete(groupPrompt,
                            buildGroupPrompt(preference, task, byId));
                    GroupText text = parser.parseGroupText(response);
                    synchronized (this) {
                        job.completedGroups++;
                        job.message = "Створюємо огляди: " + job.completedGroups
                                + " з " + job.totalGroups + "…";
                    }
                    return new GeneratedGroup(task, text.title(), text.summaryText());
                } catch (OpenRouterException | IllegalArgumentException ex) {
                    last = ex;
                    if (attempt == 0) {
                        log.info("Retrying topic overview for category {}: {}", task.category().key(),
                                ex.getClass().getSimpleName());
                    }
                }
            }
            throw last == null ? new IllegalStateException("Порожня відповідь моделі.") : last;
        } catch (RuntimeException ex) {
            synchronized (this) {
                job.failedGroups++;
                job.message = "Частину оглядів не вдалося створити. Продовжуємо…";
            }
            log.warn("Topic overview failed for category {}: {}", task.category().key(),
                    ex.getClass().getSimpleName());
            return null;
        } finally {
            synchronized (this) {
                job.active--;
            }
        }
    }

    private void persist(Long sourceId, String email, PreferenceVersion preference,
                         List<GeneratedGroup> generated) {
        transaction.executeWithoutResult(status -> {
            // Bulk operations clear the persistence context, so fetch fresh managed entities afterwards.
            summaries.clearGroupLinks(preference.getId());
            groups.deleteByPreferenceVersionId(preference.getId());

            FeedSource source = sources.findOwned(sourceId, email);
            PreferenceVersion managedPreference = preferences.getReferenceById(preference.getId());
            Map<String, SummaryCategory> categoryByKey = new HashMap<>();
            for (SummaryCategory category : categories.findByFeedSource_IdOrderByLabelAsc(sourceId)) {
                categoryByKey.put(category.getCategoryKey(), category);
            }

            Instant now = Instant.now();
            for (GeneratedGroup item : generated) {
                List<ArticleSummary> rows = summaries.findByIdInAndPreferenceVersion_Id(
                        item.task().articleIds(), preference.getId());
                if (rows.size() < minArticles) continue;

                CategoryGroup parsed = item.task().category();
                SummaryCategory category = categoryByKey.get(parsed.key());
                if (category == null) {
                    category = new SummaryCategory();
                    category.setFeedSource(source);
                    category.setCategoryKey(parsed.key());
                    category.setCreatedAt(now);
                }
                category.setLabel(parsed.label());
                category.setDescription(parsed.description());
                category.setLastUsedAt(now);
                category = categories.save(category);
                categoryByKey.put(parsed.key(), category);

                SummaryGroup group = new SummaryGroup();
                group.setFeedSource(source);
                group.setPreferenceVersion(managedPreference);
                group.setCategory(category);
                String title = item.title();
                if (item.task().parts() > 1) {
                    title = title + " (частина " + item.task().part() + ")";
                }
                group.setTitle(title);
                group.setSummaryText(item.summaryText());
                group.setArticleCount(rows.size());
                group.setModelName(modelName);
                group.setCreatedAt(now);
                group = groups.save(group);

                for (ArticleSummary row : rows) row.setSummaryGroup(group);
                summaries.saveAll(rows);
            }
            groups.flush();
        });
    }

    private List<GroupTask> split(List<CategoryGroup> parsed) {
        List<GroupTask> tasks = new ArrayList<>();
        for (CategoryGroup group : parsed) {
            List<Long> ids = group.articleIds();
            int parts = (ids.size() + maxPerGroup - 1) / maxPerGroup;
            for (int part = 0; part < parts; part++) {
                int from = part * maxPerGroup;
                int to = Math.min(ids.size(), from + maxPerGroup);
                tasks.add(new GroupTask(group, List.copyOf(ids.subList(from, to)), part + 1, parts));
            }
        }
        return tasks;
    }

    private String buildClassificationPrompt(PreferenceVersion preference, List<SummaryCategory> existing,
                                             List<ArticleSummary> candidates) {
        StringBuilder categoryText = new StringBuilder();
        if (existing.isEmpty()) {
            categoryText.append("(немає — створи нові)");
        } else {
            for (SummaryCategory category : existing) {
                categoryText.append("- ").append(category.getCategoryKey())
                        .append(" | ").append(category.getLabel())
                        .append(" | ").append(category.getDescription() == null ? "" : category.getDescription())
                        .append("\n");
            }
        }

        StringBuilder articleText = new StringBuilder();
        for (ArticleSummary summary : candidates) {
            articleText.append("id: ").append(summary.getId()).append("\n")
                    .append("Заголовок: ").append(summary.getArticle().getTitle()).append("\n")
                    .append("Підсумок: ").append(truncate(summary.getSummaryText(), 600)).append("\n\n");
        }

        return "Мова категорій: " + languageName(preference.getLanguage()) + "\n"
                + "Мінімальна кількість статей у категорії: " + minArticles + "\n\n"
                + "Наявні категорії:\n" + categoryText + "\n"
                + "Статті:\n<articles>\n" + articleText + "</articles>";
    }

    private String buildGroupPrompt(PreferenceVersion preference, GroupTask task,
                                    Map<Long, ArticleSummary> byId) {
        StringBuilder articleText = new StringBuilder();
        for (Long id : task.articleIds()) {
            ArticleSummary summary = byId.get(id);
            if (summary == null) continue;
            articleText.append("id: ").append(id).append("\n")
                    .append("Заголовок: ").append(summary.getArticle().getTitle()).append("\n")
                    .append("Підсумок: ").append(truncate(summary.getSummaryText(), 1500)).append("\n\n");
        }
        String partNote = task.parts() > 1
                ? "\nЦе частина " + task.part() + " з " + task.parts()
                + ". Заголовок має відрізнятися від інших частин.\n"
                : "";
        return "Мова: " + languageName(preference.getLanguage()) + "\n"
                + "Орієнтовна довжина: " + groupLength(preference.getSummaryLength()) + "\n"
                + "Стиль: " + styleName(preference.getStyle()) + "\n"
                + "Категорія: " + task.category().label() + "\n"
                + (task.category().description() == null ? "" : "Опис категорії: " + task.category().description() + "\n")
                + partNote
                + "Додаткові побажання користувача:\n"
                + (preference.getOriginalContent() == null ? "" : preference.getOriginalContent())
                + "\n\nСтатті:\n<articles>\n" + articleText + "</articles>";
    }

    private String languageName(Language language) {
        return language == Language.EN ? "англійська" : "українська";
    }

    private String groupLength(SummaryLength length) {
        if (length == SummaryLength.SHORT) return "80–120 слів";
        if (length == SummaryLength.DETAILED) return "300–500 слів";
        return "150–250 слів";
    }

    private String styleName(SummaryStyle style) {
        if (style == SummaryStyle.SIMPLE) {
            return "простий: зрозумілі слова, короткі речення, пояснення складних термінів";
        }
        if (style == SummaryStyle.PROFESSIONAL) {
            return "професійний: точні формулювання, доречна термінологія, без розмовних висловів";
        }
        return "нейтральний: спокійний виклад фактів без оцінок";
    }

    private String truncate(String value, int maxLength) {
        if (value == null) return "";
        String text = value.trim();
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "…";
    }

    private void setPhase(Job job, String phase, String message) {
        synchronized (this) {
            job.phase = phase;
            job.message = message;
        }
    }

    private void abort(Job job) {
        synchronized (this) {
            job.phase = "stopped";
            job.message = "Створення оглядів зупинено. Попередні огляди не змінено.";
            job.running = false;
        }
    }

    private String messageFor(Exception ex) {
        if (ex instanceof OpenRouterException) {
            return "Не вдалося отримати відповідь від OpenRouter. Перевірте ключ, доступ до моделі та баланс.";
        }
        if (ex instanceof IllegalArgumentException && ex.getMessage() != null) {
            return ex.getMessage();
        }
        return "Не вдалося створити тематичні огляди. Спробуйте ще раз.";
    }

    private SummaryGroupProgress snapshot(Job job, PreferenceVersion preference) {
        long ready = summaries.countByPreferenceVersion_IdAndStatus(preference.getId(), SummaryStatus.OK);
        boolean hasGroups = groups.countByPreferenceVersion_Id(preference.getId()) > 0;
        return new SummaryGroupProgress(job.running, job.stopRequested, job.preferenceId,
                job.versionNumber, job.phase, job.totalArticles, job.classifiedArticles, job.totalGroups,
                job.completedGroups, job.failedGroups, job.active, parallelism, ready, hasGroups,
                job.message);
    }

    private SummaryGroupProgress idle(PreferenceVersion preference, String message) {
        long ready = summaries.countByPreferenceVersion_IdAndStatus(preference.getId(), SummaryStatus.OK);
        boolean hasGroups = groups.countByPreferenceVersion_Id(preference.getId()) > 0;
        return new SummaryGroupProgress(false, false, preference.getId(), preference.getVersionNumber(),
                "idle", 0, 0, 0, 0, 0, 0, parallelism, ready, hasGroups, message);
    }

    @PreDestroy
    public void stop() {
        stopping = true;
        jobExecutor.shutdownNow();
        groupExecutor.shutdownNow();
    }

    private record GroupTask(CategoryGroup category, List<Long> articleIds, int part, int parts) {}

    private record GeneratedGroup(GroupTask task, String title, String summaryText) {}

    private static class Job {
        private final Long preferenceId;
        private final Integer versionNumber;
        private final int totalArticles;
        private boolean running = true;
        private boolean stopRequested;
        private String phase = "queued";
        private String message = "Створення оглядів поставлено в чергу.";
        private int classifiedArticles;
        private int totalGroups;
        private int completedGroups;
        private int failedGroups;
        private int active;

        private Job(Long preferenceId, Integer versionNumber, int totalArticles) {
            this.preferenceId = preferenceId;
            this.versionNumber = versionNumber;
            this.totalArticles = totalArticles;
        }
    }
}
