package org.example.rss.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prevents two expensive operations (per-article summaries and topic overviews)
 * from running for the same feed source at the same time.
 */
@Component
public class SourceProcessingGuard {
    private final Set<Long> activeSources = ConcurrentHashMap.newKeySet();

    public void acquire(Long sourceId) {
        if (!activeSources.add(sourceId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Дочекайтеся завершення поточної обробки цього джерела.");
        }
    }

    public void release(Long sourceId) {
        activeSources.remove(sourceId);
    }
}
