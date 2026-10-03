package org.example.rss.service;

import org.example.rss.dto.FeedSourceForm;
import org.example.rss.model.FeedSource;
import org.example.rss.model.User;
import org.example.rss.repository.FeedSourceRepository;
import org.example.rss.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FeedSourceServiceTests {
    private final FeedSourceRepository feeds = mock(FeedSourceRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final FeedMetadataService metadata = mock(FeedMetadataService.class);
    private final FeedSourceService service = new FeedSourceService(feeds, users, metadata);
    private final User owner = mock(User.class);

    @BeforeEach
    void setUp() {
        when(metadata.resolveTitle(anyString())).thenReturn("Feed title");
        when(owner.getId()).thenReturn(7L);
        when(users.findByEmail("owner@example.com")).thenReturn(Optional.of(owner));
    }

    @Test
    void addUsesAuthenticatedOwnerAndInitializesTimestamp() {
        FeedSourceForm form = new FeedSourceForm();
        form.setName("  Новини  ");
        form.setUrl("  https://example.com/feed  ");
        when(feeds.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        FeedSource result = service.add(form, "owner@example.com");
        assertSame(owner, result.getUser());
        assertEquals("Новини", result.getName());
        assertEquals("https://example.com/feed", result.getUrl());
        assertNotNull(result.getCreatedAt());
        assertTrue(result.isActiveForScheduler());
    }

    @Test
    void duplicateIsNotSaved() {
        FeedSourceForm form = new FeedSourceForm();
        form.setUrl("https://example.com/feed");
        when(feeds.existsByUser_IdAndUrl(7L, form.getUrl())).thenReturn(true);
        assertThrows(FeedSourceService.DuplicateFeedSourceException.class,
                () -> service.add(form, "owner@example.com"));
        verify(feeds, never()).saveAndFlush(any());
    }

    @Test
    void foreignSourceCannotBeViewedOrDeleted() {
        when(feeds.findByIdAndUser_Id(99L, 7L)).thenReturn(Optional.empty());
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.findOwned(99L, "owner@example.com"));
        assertEquals(404, error.getStatusCode().value());
        assertThrows(ResponseStatusException.class, () -> service.delete(99L, "owner@example.com"));
        verify(feeds, never()).delete(any(FeedSource.class));
    }

    @Test
    void ownedSourceCanBeDeleted() {
        FeedSource source = new FeedSource();
        when(feeds.findByIdAndUser_Id(10L, 7L)).thenReturn(Optional.of(source));
        service.delete(10L, "owner@example.com");
        verify(feeds).delete(source);
        verify(feeds).flush();
    }
}