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
                .map(v -> new PreferenceVersionDto(v.getId(), v.getVersionNumber(), v.getOriginalContent(), v.getCreatedAt()))
                .toList();
    }

    @Transactional
    public PreferenceVersionDto savePreference(Long feedSourceId, String content, String email) {
        FeedSource source = feedSourceService.findOwned(feedSourceId, email);
        if (content == null || content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Вподобання не можуть бути порожніми");
        }
        Integer maxVersion = preferenceVersionRepository.findMaxVersionNumberByFeedSourceId(feedSourceId);
        int nextVersion = (maxVersion == null) ? 1 : maxVersion + 1;

        PreferenceVersion version = new PreferenceVersion();
        version.setFeedSource(source);
        version.setVersionNumber(nextVersion);
        version.setLanguage(Language.UK);
        version.setSummaryLength(SummaryLength.MEDIUM);
        version.setStyle(SummaryStyle.NEUTRAL);
        version.setOriginalContent(content.trim());
        version.setCreatedAt(Instant.now());

        PreferenceVersion saved = preferenceVersionRepository.saveAndFlush(version);
        return new PreferenceVersionDto(saved.getId(), saved.getVersionNumber(), saved.getOriginalContent(), saved.getCreatedAt());
    }
}
