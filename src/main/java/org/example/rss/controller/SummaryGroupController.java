package org.example.rss.controller;

import org.example.rss.dto.SummaryGroupPage;
import org.example.rss.dto.SummaryGroupProgress;
import org.example.rss.service.SummaryGroupQueryService;
import org.example.rss.service.SummaryGroupService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.Map;

@RestController
public class SummaryGroupController {
    private final SummaryGroupService service;
    private final SummaryGroupQueryService queryService;

    public SummaryGroupController(SummaryGroupService service, SummaryGroupQueryService queryService) {
        this.service = service;
        this.queryService = queryService;
    }

    @PostMapping("/api/feeds/{id}/summary-groups")
    public SummaryGroupProgress start(@PathVariable Long id, @RequestParam Long preferenceId,
                                      Principal principal) {
        return service.start(id, preferenceId, principal.getName());
    }

    @GetMapping("/api/feeds/{id}/summary-groups/status")
    public SummaryGroupProgress status(@PathVariable Long id, @RequestParam Long preferenceId,
                                       Principal principal) {
        return service.status(id, preferenceId, principal.getName());
    }

    @PostMapping("/api/feeds/{id}/summary-groups/stop")
    public SummaryGroupProgress stop(@PathVariable Long id, @RequestParam Long preferenceId,
                                     Principal principal) {
        return service.requestStop(id, preferenceId, principal.getName());
    }

    @GetMapping("/api/feeds/{id}/summary-groups")
    public SummaryGroupPage list(@PathVariable Long id, @RequestParam Long preferenceId,
                                 Principal principal) {
        return queryService.list(id, preferenceId, principal.getName());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleError(ResponseStatusException exception) {
        String message = exception.getReason();
        if (message == null) message = "Не вдалося обробити тематичні огляди.";
        return ResponseEntity.status(exception.getStatusCode()).body(Map.of("error", message));
    }
}
