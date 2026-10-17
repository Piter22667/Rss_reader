package org.example.rss.controller;

import org.example.rss.dto.ArticleSummaryPreview;
import org.example.rss.service.ArticleSummaryPreviewService;
import org.example.rss.service.ArticleSummaryQueryService;
import org.example.rss.dto.ArticleSummaryPage;
import org.example.rss.dto.ArticleSummaryBatchProgress;
import org.example.rss.service.ArticleSummaryBatchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.Map;
import java.util.List;

@RestController
public class ArticleSummaryPreviewController {
    private final ArticleSummaryPreviewService service;
    private final ArticleSummaryQueryService queryService;
    private final ArticleSummaryBatchService batchService;

    public ArticleSummaryPreviewController(ArticleSummaryPreviewService service, ArticleSummaryQueryService queryService,
                                           ArticleSummaryBatchService batchService) {
        this.service = service;
        this.queryService = queryService;
        this.batchService = batchService;
    }

    @PostMapping("/api/feeds/{id}/summary-batch")
    public ArticleSummaryBatchProgress startBatch(@PathVariable Long id, @RequestParam Long preferenceId, Principal principal) {
        return batchService.start(id, preferenceId, principal.getName());
    }

    @GetMapping("/api/feeds/{id}/summary-batch")
    public ArticleSummaryBatchProgress batchStatus(@PathVariable Long id, @RequestParam Long preferenceId, Principal principal) {
        return batchService.status(id, preferenceId, principal.getName());
    }

    @PostMapping("/api/feeds/{id}/summary-batch/stop")
    public ArticleSummaryBatchProgress stopBatch(@PathVariable Long id, @RequestParam Long preferenceId, Principal principal) {
        return batchService.requestStop(id, preferenceId, principal.getName());
    }

    @GetMapping("/api/feeds/{id}/summaries")
    public ArticleSummaryPage list(@PathVariable Long id, @RequestParam Long preferenceId,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(required = false) Long focusArticleId,
                                    @RequestParam(required = false) List<Long> articleIds, Principal principal) {
        return queryService.list(id, preferenceId, principal.getName(), page, focusArticleId, articleIds);
    }

    @PostMapping("/api/feeds/{id}/summary-preview")
    public ArticleSummaryPreview preview(@PathVariable Long id, @RequestParam Long preferenceId,
                                         @RequestParam(defaultValue = "false") boolean retry,
                                         @RequestParam(required = false) Long articleId, Principal principal) {
        batchService.beginManual(id, principal.getName());
        try {
            return service.preview(id, preferenceId, principal.getName(), retry, articleId);
        } finally {
            batchService.endManual(id);
        }
    }

    @GetMapping("/api/feeds/{id}/summary-preview")
    public ResponseEntity<ArticleSummaryPreview> getSaved(@PathVariable Long id, @RequestParam Long preferenceId,
                                                         Principal principal) {
        ArticleSummaryPreview saved = service.getSaved(id, preferenceId, principal.getName());
        if (saved == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(saved);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleError(ResponseStatusException exception) {
        String message = exception.getReason();
        if (message == null) message = "Не вдалося виконати пробну сумаризацію.";
        return ResponseEntity.status(exception.getStatusCode()).body(Map.of("error", message));
    }
}
