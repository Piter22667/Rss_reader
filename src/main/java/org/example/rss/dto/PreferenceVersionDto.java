package org.example.rss.dto;

import java.time.Instant;

public record PreferenceVersionDto(
        Long id,
        Integer versionNumber,
        String content,
        Instant createdAt
) {}
