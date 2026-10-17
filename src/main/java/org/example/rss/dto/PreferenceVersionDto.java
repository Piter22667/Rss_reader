package org.example.rss.dto;

import org.example.rss.model.enums.Language;
import org.example.rss.model.enums.SummaryLength;
import org.example.rss.model.enums.SummaryStyle;

import java.time.Instant;

public record PreferenceVersionDto(
        Long id,
        Integer versionNumber,
        String content,
        Language language,
        SummaryLength summaryLength,
        SummaryStyle style,
        Instant createdAt
) {}
