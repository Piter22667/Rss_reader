package org.example.rss.service;

import org.example.rss.dto.PreferenceVersionDto;
import org.example.rss.model.FeedSource;
import org.example.rss.model.PreferenceVersion;
import org.example.rss.model.enums.Language;
import org.example.rss.model.enums.SummaryLength;
import org.example.rss.model.enums.SummaryStyle;
import org.example.rss.repository.PreferenceVersionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Service
@Transactional(readOnly = true)
public class PreferenceService {
    private final PreferenceVersionRepository preferenceVersionRepository;
    private final FeedSourceService feedSourceService;

    public PreferenceService(PreferenceVersionRepository preferenceVersionRepository,
                             FeedSourceService feedSourceService) {
        this.preferenceVersionRepository = preferenceVersionRepository;
        this.feedSourceService = feedSourceService;
    }

    public List<PreferenceVersionDto> getVersions(Long feedSourceId, String email) {
        feedSourceService.findOwned(feedSourceId, email);
        return preferenceVersionRepository.findByFeedSourceIdOrderByVersionNumberAsc(feedSourceId).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public PreferenceVersionDto savePreference(Long feedSourceId, String content, String language,
                                               String summaryLength, String style, String email) {
        FeedSource source = feedSourceService.findOwned(feedSourceId, email);
        if (content == null || content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Вподобання не можуть бути порожніми");
        }
        Integer maxVersion = preferenceVersionRepository.findMaxVersionNumberByFeedSourceId(feedSourceId);
        int nextVersion = (maxVersion == null) ? 1 : maxVersion + 1;

        PreferenceVersion version = new PreferenceVersion();
        version.setFeedSource(source);
        version.setVersionNumber(nextVersion);
        // The historical defaults are kept as a fallback when the form does not send a value.
        version.setLanguage(parseEnum(Language.class, language, Language.UK));
        version.setSummaryLength(parseEnum(SummaryLength.class, summaryLength, SummaryLength.MEDIUM));
        version.setStyle(parseEnum(SummaryStyle.class, style, SummaryStyle.NEUTRAL));
        version.setOriginalContent(content.trim());
        version.setCreatedAt(Instant.now());

        PreferenceVersion saved = preferenceVersionRepository.saveAndFlush(version);
        return toDto(saved);
    }

    private PreferenceVersionDto toDto(PreferenceVersion version) {
        return new PreferenceVersionDto(version.getId(), version.getVersionNumber(), version.getOriginalContent(),
                version.getLanguage(), version.getSummaryLength(), version.getStyle(), version.getCreatedAt());
    }

    private <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }
}
