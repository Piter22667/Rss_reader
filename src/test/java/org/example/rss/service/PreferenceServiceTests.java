package org.example.rss.service;

import org.example.rss.dto.PreferenceVersionDto;
import org.example.rss.model.FeedSource;
import org.example.rss.model.PreferenceVersion;
import org.example.rss.model.enums.Language;
import org.example.rss.model.enums.SummaryLength;
import org.example.rss.model.enums.SummaryStyle;
import org.example.rss.repository.PreferenceVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PreferenceServiceTests {
    private final PreferenceVersionRepository repository = mock(PreferenceVersionRepository.class);
    private final FeedSourceService feedSourceService = mock(FeedSourceService.class);
    private final PreferenceService service = new PreferenceService(repository, feedSourceService);

    @BeforeEach
    void setUp() {
        when(feedSourceService.findOwned(anyLong(), any())).thenReturn(new FeedSource());
        when(repository.findMaxVersionNumberByFeedSourceId(10L)).thenReturn(null);
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            PreferenceVersion version = invocation.getArgument(0);
            version.setId(1L);
            return version;
        });
    }

    @Test
    void usesHistoricalDefaultsWhenOptionsAreMissing() {
        PreferenceVersionDto dto = service.savePreference(10L, "text", null, null, null, "owner@example.com");
        assertEquals(Language.UK, dto.language());
        assertEquals(SummaryLength.MEDIUM, dto.summaryLength());
        assertEquals(SummaryStyle.NEUTRAL, dto.style());
    }

    @Test
    void storesSelectedOptions() {
        PreferenceVersionDto dto = service.savePreference(10L, "text", "en", "DETAILED", "simple", "owner@example.com");
        assertEquals(Language.EN, dto.language());
        assertEquals(SummaryLength.DETAILED, dto.summaryLength());
        assertEquals(SummaryStyle.SIMPLE, dto.style());
    }

    @Test
    void invalidOptionFallsBackToDefault() {
        PreferenceVersionDto dto = service.savePreference(10L, "text", "klingon", null, null, "owner@example.com");
        assertEquals(Language.UK, dto.language());
    }

    @Test
    void blankContentIsRejected() {
        assertThrows(ResponseStatusException.class,
                () -> service.savePreference(10L, "   ", null, null, null, "owner@example.com"));
    }
}