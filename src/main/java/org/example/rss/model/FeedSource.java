package org.example.rss.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

@Entity
@Table(name = "feed_sources")
@Data
public class FeedSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 2048)
    private String url;

    public String getFaviconUrl() {
        if (url == null || url.isBlank()) return null;
        try {
            java.net.URI uri = java.net.URI.create(url);
            if (uri.getHost() != null && uri.getUserInfo() == null
                    && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
                return uri.resolve("/favicon.ico").toString();
            }
        } catch (IllegalArgumentException ex) {
            // Older sources without valid URLs display the placeholder.
        }
        return null;
    }

    @Column(name = "is_active_for_scheduler", nullable = false)
    private boolean activeForScheduler = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_fetched_at")
    private Instant lastFetchedAt;
}
