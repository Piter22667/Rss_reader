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

    @Column(name = "is_active_for_scheduler", nullable = false)
    private boolean activeForScheduler = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_fetched_at")
    private Instant lastFetchedAt;
}
