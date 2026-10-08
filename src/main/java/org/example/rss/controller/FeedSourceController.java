package org.example.rss.controller;

import jakarta.validation.Valid;
import org.example.rss.dto.FeedSourceForm;
import org.example.rss.model.FeedSource;
import org.example.rss.service.FeedSourceService;
import org.example.rss.service.ArticleImportService;
import org.example.rss.service.ArticleQueryService;
import org.example.rss.service.FeedDownloadException;
import org.example.rss.service.FeedParsingException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.security.Principal;
import java.util.List;

import org.example.rss.dto.PreferenceVersionDto;
import org.example.rss.service.PreferenceService;
import org.springframework.http.MediaType;

@Controller
public class FeedSourceController {
    private final FeedSourceService feedSourceService;
    private final ArticleImportService articleImportService;
    private final ArticleQueryService articleQueryService;
    private final PreferenceService preferenceService;

    public FeedSourceController(FeedSourceService feedSourceService, ArticleImportService articleImportService,
                                ArticleQueryService articleQueryService, PreferenceService preferenceService) {
        this.feedSourceService = feedSourceService;
        this.articleImportService = articleImportService;
        this.articleQueryService = articleQueryService;
        this.preferenceService = preferenceService;
    }

    @GetMapping("/")
    public String home(@RequestParam(required = false) Long feedId,
                       @RequestParam(defaultValue = "0") int page, Principal principal, Model model) {
        model.addAttribute("form", new FeedSourceForm());
        populatePage(model, principal.getName(), feedId, page);
        return "home";
    }

    @PostMapping("/feeds")
    public String add(@Valid @ModelAttribute("form") FeedSourceForm form, BindingResult bindingResult,
                      Principal principal, Model model, RedirectAttributes redirectAttributes) {
        if (!bindingResult.hasErrors()) {
            try {
                FeedSource source = feedSourceService.add(form, principal.getName());
                redirectAttributes.addAttribute("feedId", source.getId());
                redirectAttributes.addFlashAttribute("success", "RSS-джерело додано");
                return "redirect:/";
            } catch (FeedSourceService.DuplicateFeedSourceException ex) {
                bindingResult.rejectValue("url", "feed.duplicate", ex.getMessage());
            } catch (DataIntegrityViolationException ex) {
                // The database also prevents duplicates submitted concurrently.
                if (!isDuplicateUrl(ex)) throw ex;
                bindingResult.rejectValue("url", "feed.duplicate", "Це RSS-джерело вже є у вашому списку");
            }
        }
        populatePage(model, principal.getName(), null, 0);
        return "home";
    }

    @PostMapping("/feeds/{id}/delete")
    public String delete(@PathVariable Long id, Principal principal, RedirectAttributes redirectAttributes) {
        feedSourceService.delete(id, principal.getName());
        redirectAttributes.addFlashAttribute("success", "RSS-джерело видалено");
        return "redirect:/";
    }

    @PostMapping("/feeds/{id}/import")
    public String importArticles(@PathVariable Long id, Principal principal, RedirectAttributes redirectAttributes) {
        try {
            var result = articleImportService.importArticles(id, principal.getName());
            redirectAttributes.addFlashAttribute("success", "Оновлено. Додано: " + result.imported()
                    + ". Уже збережено: " + result.alreadyExists() + ". Пропущено: " + result.skipped() + ".");
        } catch (FeedDownloadException | FeedParsingException ex) {
            redirectAttributes.addFlashAttribute("importError", ex.getMessage());
        } catch (DataAccessException ex) {
            redirectAttributes.addFlashAttribute("importError", "Не вдалося зберегти статті. Спробуйте оновити джерело пізніше.");
        }
        redirectAttributes.addAttribute("feedId", id);
        return "redirect:/";
    }

    @PostMapping("/feeds/{id}/preferences")
    public String savePreferences(@PathVariable Long id, @RequestParam("content") String content,
                                  Principal principal, RedirectAttributes redirectAttributes) {
        preferenceService.savePreference(id, content, principal.getName());
        redirectAttributes.addFlashAttribute("success", "Вподобання збережено як нову версію");
        redirectAttributes.addAttribute("feedId", id);
        return "redirect:/";
    }

    @PostMapping(value = "/api/feeds/{id}/preferences", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public PreferenceVersionDto savePreferenceAjax(@PathVariable Long id, @RequestBody java.util.Map<String, String> payload,
                                                   Principal principal) {
        String content = payload.get("content");
        return preferenceService.savePreference(id, content, principal.getName());
    }

    @GetMapping(value = "/api/feeds/{id}/preferences", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<PreferenceVersionDto> getPreferencesAjax(@PathVariable Long id, Principal principal) {
        return preferenceService.getVersions(id, principal.getName());
    }

    private void populatePage(Model model, String email, Long feedId, int page) {
        List<FeedSource> sources = feedSourceService.list(email);
        FeedSource selected = feedId == null
                ? (sources.isEmpty() ? null : sources.get(0))
                : feedSourceService.findOwned(feedId, email);
        model.addAttribute("sources", sources);
        model.addAttribute("selectedSource", selected);
        model.addAttribute("email", email);
        if (selected != null) {
            var articlePage = articleQueryService.listOwned(selected.getId(), email, page);
            model.addAttribute("articlePage", articlePage);
            model.addAttribute("articles", articlePage.getContent());
            model.addAttribute("lastFetchedAtLabel", articleQueryService.formatDate(selected.getLastFetchedAt()));

            List<PreferenceVersionDto> preferences = preferenceService.getVersions(selected.getId(), email);
            model.addAttribute("preferences", preferences);
            model.addAttribute("latestPreference", preferences.isEmpty() ? null : preferences.get(preferences.size() - 1));
        }
    }

    private boolean isDuplicateUrl(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains("uq_feed_sources_user_url")) return true;
        }
        return false;
    }
}
